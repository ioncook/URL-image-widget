package com.example.urlimagewidget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CropOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bgPaint = Paint().apply {
        color = Color.parseColor("#99000000") // 60% black overlay
    }
    
    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }

    val cropRect = RectF()
    var cornerRadiusPx = 0f

    init {
        // Default radius to 16dp
        val density = context.resources.displayMetrics.density
        cornerRadiusPx = 16f * density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cropRect.isEmpty) return

        canvas.save()
        val path = Path().apply {
            addRoundRect(cropRect, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
        }
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            canvas.clipOutPath(path)
        } else {
            @Suppress("DEPRECATION")
            canvas.clipPath(path, android.graphics.Region.Op.DIFFERENCE)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        canvas.restore()

        canvas.drawRoundRect(cropRect, cornerRadiusPx, cornerRadiusPx, borderPaint)
    }
}
