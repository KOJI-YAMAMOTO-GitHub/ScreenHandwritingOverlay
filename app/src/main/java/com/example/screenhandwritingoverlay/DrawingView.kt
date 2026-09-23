package com.example.screenhandwritingoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Stroke(
        val path: Path,
        val color: Int,
        val strokeWidth: Float
    )

    enum class StrokeWidthOption(val dpValue: Float, val label: String) {
        THIN(4f, "細め"),
        MEDIUM(8f, "普通"),
        THICK(16f, "太め")
    }

    private val density = context.resources.displayMetrics.density

    // Default configuration: Thin (細め) & Red (赤字)
    var currentColor: Int = Color.RED
    var currentStrokeOption: StrokeWidthOption = StrokeWidthOption.THIN
        set(value) {
            field = value
            currentStrokeWidth = value.dpValue * density
        }

    private var currentStrokeWidth: Float = StrokeWidthOption.THIN.dpValue * density

    private val strokes = mutableListOf<Stroke>()
    private var currentPath: Path? = null
    private var currentPaint: Paint = createPaint(currentColor, currentStrokeWidth)

    private var lastX = 0f
    private var lastY = 0f

    var isTouchPassThrough: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var onStrokeDrawnListener: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    private fun createPaint(color: Int, strokeWidth: Float): Paint {
        return Paint().apply {
            this.color = color
            this.strokeWidth = strokeWidth
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
            isDither = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw existing completed strokes
        for (stroke in strokes) {
            val paint = createPaint(stroke.color, stroke.strokeWidth)
            canvas.drawPath(stroke.path, paint)
        }

        // Draw current path in progress
        currentPath?.let { path ->
            val paint = createPaint(currentColor, currentStrokeWidth)
            canvas.drawPath(path, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isTouchPassThrough) {
            return false
        }

        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val path = Path()
                path.moveTo(x, y)
                currentPath = path
                lastX = x
                lastY = y
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath?.let { path ->
                    val dx = Math.abs(x - lastX)
                    val dy = Math.abs(y - lastY)
                    if (dx >= 4 || dy >= 4) {
                        path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
                        lastX = x
                        lastY = y
                    }
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentPath?.let { path ->
                    path.lineTo(x, y)
                    strokes.add(Stroke(path, currentColor, currentStrokeWidth))
                    currentPath = null
                    invalidate()
                    onStrokeDrawnListener?.invoke()
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun undo(): Boolean {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            invalidate()
            onStrokeDrawnListener?.invoke()
            return true
        }
        return false
    }

    fun clear() {
        strokes.clear()
        currentPath = null
        invalidate()
        onStrokeDrawnListener?.invoke()
    }

    fun hasStrokes(): Boolean {
        return strokes.isNotEmpty()
    }
}