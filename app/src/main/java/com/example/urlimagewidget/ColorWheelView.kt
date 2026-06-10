package com.example.urlimagewidget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class ColorWheelView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val colors = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
    )

    private val indicatorPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val indicatorBorderPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    var hue = 0f
    var saturation = 0f
    var brightness = 1f
    var alphaVal = 255

    var onColorChangedListener: ((color: Int) -> Unit)? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = Math.min(centerX, centerY) - 8f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return

        // 1. Draw Hue Wheel
        val hueShader = SweepGradient(centerX, centerY, colors, null)
        val huePaint = Paint().apply {
            isAntiAlias = true
            shader = hueShader
        }
        canvas.drawCircle(centerX, centerY, radius, huePaint)

        // 2. Draw Saturation overlay (white at center, transparent at edge)
        val satShader = RadialGradient(
            centerX, centerY, radius,
            Color.WHITE, 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        val satPaint = Paint().apply {
            isAntiAlias = true
            shader = satShader
        }
        canvas.drawCircle(centerX, centerY, radius, satPaint)

        // 3. Draw black/white indicator at selected position
        val angleRad = Math.toRadians(hue.toDouble())
        val indicatorX = centerX + radius * saturation * Math.cos(angleRad).toFloat()
        val indicatorY = centerY + radius * saturation * Math.sin(angleRad).toFloat()

        canvas.drawCircle(indicatorX, indicatorY, 12f, indicatorPaint)
        canvas.drawCircle(indicatorX, indicatorY, 12f, indicatorBorderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val dx = event.x - centerX
                val dy = event.y - centerY
                val distance = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                
                // Calculate Hue (0 to 360)
                var angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
                if (angle < 0) angle += 360f
                hue = angle

                // Calculate Saturation (0 to 1)
                saturation = (distance / radius).coerceIn(0f, 1f)

                invalidate()
                triggerColorUpdate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun triggerColorUpdate() {
        val hsv = floatArrayOf(hue, saturation, brightness)
        val color = Color.HSVToColor(alphaVal, hsv)
        onColorChangedListener?.invoke(color)
    }

    fun setColor(color: Int) {
        alphaVal = Color.alpha(color)
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
        invalidate()
    }
}
