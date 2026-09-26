package com.example.screenhandwritingoverlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class OverlayService : Service() {

    companion object {
        const val ACTION_START = "com.example.screenhandwritingoverlay.ACTION_START"
        const val ACTION_STOP = "com.example.screenhandwritingoverlay.ACTION_STOP"
        const val ACTION_TOGGLE = "com.example.screenhandwritingoverlay.ACTION_TOGGLE"

        const val ACTION_SCREENSHOT_RESULT = "com.example.screenhandwritingoverlay.ACTION_SCREENSHOT_RESULT"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_RESULT_DATA = "EXTRA_RESULT_DATA"

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

    private class DisplayOverlayContainer(
        val displayId: Int,
        val targetContext: Context,
        val windowManager: WindowManager,
        val drawingView: DrawingView,
        val toolbarView: View,
        val drawingParams: WindowManager.LayoutParams,
        val toolbarParams: WindowManager.LayoutParams
    )

    private lateinit var displayManager: DisplayManager
    private val activeOverlays = mutableMapOf<Int, DisplayOverlayContainer>()

    private var isPassThroughMode = false
    private var currentColorIndex = 0

    // Color cycle list: Red (赤), Blue (青), Yellow (黄), Black (黒), White (白)
    private val colorList = listOf(
        Color.RED,
        Color.BLUE,
        Color.YELLOW,
        Color.BLACK,
        Color.WHITE
    )

    private var currentStrokeOption = DrawingView.StrokeWidthOption.THIN

    private var mediaProjectionCode: Int? = null
    private var mediaProjectionData: Intent? = null
    private var mediaProjection: MediaProjection? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            val display = displayManager.getDisplay(displayId) ?: return
            setupOverlayForDisplay(display)
        }

        override fun onDisplayRemoved(displayId: Int) {
            removeOverlayForDisplay(displayId)
        }

        override fun onDisplayChanged(displayId: Int) {
            // Display changed
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
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
            ACTION_SCREENSHOT_RESULT -> {
                val resultCode = intent.getIntParameterSafe(EXTRA_RESULT_CODE, 0)
                val resultData = intent.getParcelableExtraSafe<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && resultData != null) {
                    mediaProjectionCode = resultCode
                    mediaProjectionData = resultData
                    captureScreen()
                }
                return START_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()

        if (!isRunning) {
            setupOverlayWindowsAllDisplays()
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
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
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

    private fun setupOverlayWindowsAllDisplays() {
        val displays = displayManager.displays
        for (display in displays) {
            setupOverlayForDisplay(display)
        }
    }

    private fun setupOverlayForDisplay(display: Display) {
        if (activeOverlays.containsKey(display.displayId)) return

        try {
            val targetContext: Context
            val windowManager: WindowManager

            if (display.displayId == Display.DEFAULT_DISPLAY) {
                targetContext = this
                windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            } else {
                val windowContext = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                targetContext = windowContext
                windowManager = windowContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            }

            val displayMetrics = targetContext.resources.displayMetrics
            val density = displayMetrics.density
            val screenWidth = displayMetrics.widthPixels
            val toolbarWidthPx = (320 * density).toInt()

            // 1. Drawing Canvas
            val drawingParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                if (isPassThroughMode) {
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                },
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }

            val canvasView = DrawingView(targetContext).apply {
                currentColor = colorList[currentColorIndex]
                currentStrokeOption = this@OverlayService.currentStrokeOption
                isTouchPassThrough = this@OverlayService.isPassThroughMode
            }
            windowManager.addView(canvasView, drawingParams)

            // 2. Floating Toolbar
            val themeContext = ContextThemeWrapper(targetContext, R.style.Theme_ScreenHandwritingOverlay)
            val inflater = LayoutInflater.from(themeContext)
            val toolbar = inflater.inflate(R.layout.layout_overlay_toolbar, null)

            val toolbarParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = if (screenWidth > 0) maxOf(20, (screenWidth - toolbarWidthPx) / 2) else 50
                y = (100 * density).toInt()
            }

            val container = DisplayOverlayContainer(
                display.displayId,
                targetContext,
                windowManager,
                canvasView,
                toolbar,
                drawingParams,
                toolbarParams
            )

            setupToolbarInteractions(container)
            windowManager.addView(toolbar, toolbarParams)

            activeOverlays[display.displayId] = container

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupToolbarInteractions(container: DisplayOverlayContainer) {
        val toolbar = container.toolbarView
        val canvasView = container.drawingView
        val windowManager = container.windowManager
        val toolbarParams = container.toolbarParams

        val btnDragHandle = toolbar.findViewById<ImageView>(R.id.btn_drag_handle)
        val btnModeToggle = toolbar.findViewById<View>(R.id.btn_mode_toggle)
        val btnUndo = toolbar.findViewById<ImageButton>(R.id.btn_undo)
        val btnClear = toolbar.findViewById<ImageButton>(R.id.btn_clear)
        val btnWidthToggle = toolbar.findViewById<TextView>(R.id.btn_width_toggle)
        val btnColorToggle = toolbar.findViewById<ImageButton>(R.id.btn_color_toggle)
        val btnScreenshot = toolbar.findViewById<ImageButton>(R.id.btn_screenshot)
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
                        windowManager.updateViewLayout(toolbar, toolbarParams)
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

        // Sync initial UI
        updateContainerModeUI(container)
        btnColorToggle.setColorFilter(colorList[currentColorIndex])
        btnWidthToggle.text = currentStrokeOption.label

        // Mode Toggle
        btnModeToggle.setOnClickListener {
            isPassThroughMode = !isPassThroughMode
            for (c in activeOverlays.values) {
                if (isPassThroughMode) {
                    c.drawingView.clear()
                }
                updateContainerModeUI(c)
            }
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
            currentStrokeOption = when (currentStrokeOption) {
                DrawingView.StrokeWidthOption.THIN -> DrawingView.StrokeWidthOption.MEDIUM
                DrawingView.StrokeWidthOption.MEDIUM -> DrawingView.StrokeWidthOption.THICK
                DrawingView.StrokeWidthOption.THICK -> DrawingView.StrokeWidthOption.THIN
            }
            for (c in activeOverlays.values) {
                c.drawingView.currentStrokeOption = currentStrokeOption
                val widthText = c.toolbarView.findViewById<TextView>(R.id.btn_width_toggle)
                widthText?.text = currentStrokeOption.label
            }
        }

        // Color Toggle
        btnColorToggle.setOnClickListener {
            currentColorIndex = (currentColorIndex + 1) % colorList.size
            val selectedColor = colorList[currentColorIndex]
            for (c in activeOverlays.values) {
                c.drawingView.currentColor = selectedColor
                val colorBtn = c.toolbarView.findViewById<ImageButton>(R.id.btn_color_toggle)
                colorBtn?.setColorFilter(selectedColor)
            }
        }

        // Screenshot
        btnScreenshot.setOnClickListener {
            if (mediaProjectionCode != null && mediaProjectionData != null) {
                captureScreen()
            } else {
                val intent = Intent(this, ScreenCaptureActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }

        // Close Service
        btnClose.setOnClickListener {
            stopOverlayService()
        }
    }

    private fun updateContainerModeUI(container: DisplayOverlayContainer) {
        val tvModeText = container.toolbarView.findViewById<TextView>(R.id.tv_mode_text)
        val ivModeIcon = container.toolbarView.findViewById<ImageView>(R.id.iv_mode_icon)
        val btnModeToggle = container.toolbarView.findViewById<View>(R.id.btn_mode_toggle)

        if (isPassThroughMode) {
            container.drawingParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE

            container.drawingView.isTouchPassThrough = true
            container.windowManager.updateViewLayout(container.drawingView, container.drawingParams)

            tvModeText?.text = getString(R.string.mode_pass_through)
            ivModeIcon?.setImageResource(R.drawable.ic_touch)
            btnModeToggle?.background?.setTint(Color.parseColor("#2E7D32")) // Green
        } else {
            container.drawingParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR

            container.drawingView.isTouchPassThrough = false
            container.windowManager.updateViewLayout(container.drawingView, container.drawingParams)

            tvModeText?.text = getString(R.string.mode_draw)
            ivModeIcon?.setImageResource(R.drawable.ic_pen)
            btnModeToggle?.background?.setTint(Color.parseColor("#D32F2F")) // Red
        }
    }

    private fun captureScreen() {
        val code = mediaProjectionCode ?: return
        val data = mediaProjectionData ?: return

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        if (mediaProjection == null) {
            mediaProjection = projectionManager.getMediaProjection(code, data)
        }
        val proj = mediaProjection ?: return

        // Hide all toolbars before capture
        for (c in activeOverlays.values) {
            c.toolbarView.visibility = View.INVISIBLE
        }

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val displayMetrics = resources.displayMetrics
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels
                val densityDpi = displayMetrics.densityDpi

                val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                val virtualDisplay = proj.createVirtualDisplay(
                    "ScreenCapture",
                    width, height, densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface, null, null
                )

                imageReader.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            val planes = image.planes
                            val buffer = planes[0].buffer
                            val pixelStride = planes[0].pixelStride
                            val rowStride = planes[0].rowStride
                            val rowPadding = rowStride - pixelStride * width

                            val bitmap = Bitmap.createBitmap(
                                width + rowPadding / pixelStride,
                                height,
                                Bitmap.Config.ARGB_8888
                            )
                            bitmap.copyPixelsFromBuffer(buffer)

                            val croppedBitmap = if (rowPadding > 0) {
                                Bitmap.createBitmap(bitmap, 0, 0, width, height)
                            } else {
                                bitmap
                            }

                            saveBitmapToGallery(croppedBitmap)

                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            image.close()
                            virtualDisplay?.release()
                            imageReader.close()

                            Handler(Looper.getMainLooper()).post {
                                for (c in activeOverlays.values) {
                                    c.toolbarView.visibility = View.VISIBLE
                                }
                            }
                        }
                    }
                }, Handler(Looper.getMainLooper()))

            } catch (e: Exception) {
                e.printStackTrace()
                for (c in activeOverlays.values) {
                    c.toolbarView.visibility = View.VISIBLE
                }
            }
        }, 150)
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val filename = "HandwritingOverlay_${System.currentTimeMillis()}.png"
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScreenHandwritingOverlay")
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
            resolver.openOutputStream(uri)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(this, getString(R.string.screenshot_saved), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, getString(R.string.screenshot_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeOverlayForDisplay(displayId: Int) {
        val container = activeOverlays.remove(displayId) ?: return
        try {
            container.windowManager.removeView(container.drawingView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            container.windowManager.removeView(container.toolbarView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopOverlayService() {
        displayManager.unregisterDisplayListener(displayListener)
        for (displayId in activeOverlays.keys.toList()) {
            removeOverlayForDisplay(displayId)
        }
        activeOverlays.clear()

        mediaProjection?.stop()
        mediaProjection = null
        isRunning = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopOverlayService()
        super.onDestroy()
    }

    private fun Intent.getIntParameterSafe(key: String, defaultValue: Int): Int {
        return getIntExtra(key, defaultValue)
    }

    @Suppress("DEPRECATION")
    private fun <T : android.os.Parcelable> Intent.getParcelableExtraSafe(key: String): T? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, android.os.Parcelable::class.java) as? T
        } else {
            getParcelableExtra(key)
        }
    }
}