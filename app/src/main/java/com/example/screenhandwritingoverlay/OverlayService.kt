package com.example.screenhandwritingoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.screenhandwritingoverlay.ACTION_START"
        const val ACTION_STOP = "com.example.screenhandwritingoverlay.ACTION_STOP"
        const val ACTION_TOGGLE = "com.example.screenhandwritingoverlay.ACTION_TOGGLE"

        const val CHANNEL_ID = "handwriting_overlay_channel"
        const val NOTIFICATION_ID = 1001

        var isRunning = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private lateinit var windowManager: WindowManager
    private var drawingView: DrawingView? = null
    private var toolbarView: View? = null

    private lateinit var drawingViewParams: WindowManager.LayoutParams
    private lateinit var toolbarParams: WindowManager.LayoutParams

    private var isPassThroughMode = false

    // Color cycle list: Red (赤), Blue (青), Yellow (黄), Black (黒), White (白)
    private val colorList = listOf(
        Color.RED,
        Color.BLUE,
        Color.YELLOW,
        Color.BLACK,
        Color.WHITE
    )
    private var currentColorIndex = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopOverlayService()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE -> {
                if (isRunning) {
                    stopOverlayService()
                    return START_NOT_STICKY
                }
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        if (!isRunning) {
            setupOverlayWindows()
            isRunning = true
        }

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, OverlayService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_qs_tile)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_close,
                getString(R.string.desc_close),
                stopPendingIntent
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_text)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupOverlayWindows() {
        try {
            // 1. Setup Drawing Canvas View Window
            drawingViewParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            val canvasView = DrawingView(this).apply {
                // Requirement: 細めで赤字 (Thin & Red) by default
                currentColor = Color.RED
                currentStrokeOption = DrawingView.StrokeWidthOption.THIN
            }
            drawingView = canvasView
            windowManager.addView(canvasView, drawingViewParams)

            // 2. Setup Floating Toolbar View Window
            val themeContext = ContextThemeWrapper(this, R.style.Theme_ScreenHandwritingOverlay)
            val inflater = LayoutInflater.from(themeContext)
            val toolbar = inflater.inflate(R.layout.layout_overlay_toolbar, null)
            toolbarView = toolbar

            val displayMetrics = resources.displayMetrics
            val toolbarWidthPx = (320 * displayMetrics.density).toInt()

            toolbarParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = maxOf(24, (displayMetrics.widthPixels - toolbarWidthPx) / 2)
                y = (100 * displayMetrics.density).toInt()
            }

            setupToolbarInteractions(toolbar, canvasView)
            windowManager.addView(toolbar, toolbarParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupToolbarInteractions(toolbar: View, canvasView: DrawingView) {
        val btnDragHandle = toolbar.findViewById<ImageView>(R.id.btn_drag_handle)
        val btnModeToggle = toolbar.findViewById<View>(R.id.btn_mode_toggle)
        val ivModeIcon = toolbar.findViewById<ImageView>(R.id.iv_mode_icon)
        val tvModeText = toolbar.findViewById<TextView>(R.id.tv_mode_text)
        val btnUndo = toolbar.findViewById<ImageButton>(R.id.btn_undo)
        val btnClear = toolbar.findViewById<ImageButton>(R.id.btn_clear)
        val btnWidthToggle = toolbar.findViewById<TextView>(R.id.btn_width_toggle)
        val btnColorToggle = toolbar.findViewById<ImageButton>(R.id.btn_color_toggle)
        val btnClose = toolbar.findViewById<ImageButton>(R.id.btn_close)

        // Draggable Floating Toolbar
        val touchListener = object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = toolbarParams.x
                        initialY = toolbarParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        toolbarParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        toolbarParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(toolbarView, toolbarParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        v.performClick()
                        return true
                    }
                }
                return false
            }
        }

        btnDragHandle.setOnTouchListener(touchListener)

        // Mode Toggle (Handwriting Draw vs Pass-Through Touch)
        btnModeToggle.setOnClickListener {
            isPassThroughMode = !isPassThroughMode
            updateModeUI(btnModeToggle, ivModeIcon, tvModeText, canvasView)
        }

        // Undo
        btnUndo.setOnClickListener {
            canvasView.undo()
        }

        // Clear
        btnClear.setOnClickListener {
            canvasView.clear()
        }

        // Width Option Toggle (細 -> 中 -> 太 -> 細)
        btnWidthToggle.setOnClickListener {
            val nextOption = when (canvasView.currentStrokeOption) {
                DrawingView.StrokeWidthOption.THIN -> DrawingView.StrokeWidthOption.MEDIUM
                DrawingView.StrokeWidthOption.MEDIUM -> DrawingView.StrokeWidthOption.THICK
                DrawingView.StrokeWidthOption.THICK -> DrawingView.StrokeWidthOption.THIN
            }
            canvasView.currentStrokeOption = nextOption
            btnWidthToggle.text = nextOption.label
        }

        // Color Toggle (Default Red -> Blue -> Green -> Yellow -> White -> Black)
        btnColorToggle.setOnClickListener {
            currentColorIndex = (currentColorIndex + 1) % colorList.size
            val selectedColor = colorList[currentColorIndex]
            canvasView.currentColor = selectedColor
            btnColorToggle.setColorFilter(selectedColor)
        }
        btnColorToggle.setColorFilter(Color.RED)

        // Close Service
        btnClose.setOnClickListener {
            stopOverlayService()
        }
    }

    private fun updateModeUI(
        btnModeToggle: View,
        ivModeIcon: ImageView,
        tvModeText: TextView,
        canvasView: DrawingView
    ) {
        if (isPassThroughMode) {
            // Automatically clear written content when entering screen touch/interact mode
            canvasView.clear()

            // Set NOT_TOUCHABLE on canvas so touches pass to app beneath
            drawingViewParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

            canvasView.isTouchPassThrough = true
            windowManager.updateViewLayout(drawingView, drawingViewParams)

            tvModeText.text = getString(R.string.mode_pass_through)
            ivModeIcon.setImageResource(R.drawable.ic_touch)
            btnModeToggle.background?.setTint(Color.parseColor("#2E7D32")) // Green
        } else {
            // Remove NOT_TOUCHABLE so touches draw on canvas
            drawingViewParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR

            canvasView.isTouchPassThrough = false
            windowManager.updateViewLayout(drawingView, drawingViewParams)

            tvModeText.text = getString(R.string.mode_draw)
            ivModeIcon.setImageResource(R.drawable.ic_pen)
            btnModeToggle.background?.setTint(Color.parseColor("#D32F2F")) // Red
        }
    }

    private fun stopOverlayService() {
        if (isRunning) {
            drawingView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            toolbarView?.let {
                try {
                    windowManager.removeView(it)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            drawingView = null
            toolbarView = null
            isRunning = false
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopOverlayService()
        super.onDestroy()
    }
}