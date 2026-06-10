package com.example.urlimagewidget

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class BlurCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private var originalBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null

    // Rectangles in normalized (0.0 to 1.0) coordinates relative to originalBitmap
    val blurRects = mutableListOf<RectF>()
    val blurStrengths = mutableListOf<Int>() // Strength for each box (1 to 25)
    var selectedIndex = -1

    var onSelectionChangedListener: ((hasSelection: Boolean) -> Unit)? = null
    var onStrengthChangedListener: ((strength: Int) -> Unit)? = null

    private val borderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val selectedBorderPaint = Paint().apply {
        color = Color.parseColor("#4CAF50") // Green highlight
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val handlePaint = Paint().apply {
        color = Color.parseColor("#4CAF50")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val handleBorderPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    // View boundaries and drawing coordinates
    private val imageBounds = RectF()
    private val tempRect = RectF()

    // Touch variables
    private var touchMode = MODE_NONE
    private var activeIndex = -1
    private var lastX = 0f
    private var lastY = 0f
    private var activeResizeCorner = CORNER_NONE

    companion object {
        private const val MODE_NONE = 0
        private const val MODE_DRAG = 1
        private const val MODE_RESIZE = 2

        private const val CORNER_NONE = 0
        private const val CORNER_TL = 1
        private const val CORNER_TR = 2
        private const val CORNER_BL = 3
        private const val CORNER_BR = 4

        private const val HANDLE_RADIUS = 24f // Big touch target radius
    }

    fun setBitmap(bitmap: Bitmap) {
        originalBitmap = bitmap
        invalidate()
    }

    private fun blurRegion(bitmap: Bitmap, radius: Int): Bitmap {
        val scale = 0.15f
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        val blurred = IntArray(w * h)
        
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dx in -radius..radius) {
                    val nx = x + dx
                    if (nx in 0 until w) {
                        val p = pixels[y * w + nx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                blurred[y * w + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
        System.arraycopy(blurred, 0, pixels, 0, pixels.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dy in -radius..radius) {
                    val ny = y + dy
                    if (ny in 0 until h) {
                        val p = pixels[ny * w + x]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                        count++
                    }
                }
                blurred[y * w + x] = (0xFF shl 24) or ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
            }
        }
        small.setPixels(blurred, 0, w, 0, 0, w, h)
        val result = Bitmap.createScaledBitmap(small, bitmap.width, bitmap.height, true)
        small.recycle()
        return result
    }

    private fun calculateImageBounds() {
        val bmp = originalBitmap ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        val imgW = bmp.width.toFloat()
        val imgH = bmp.height.toFloat()

        val viewRatio = viewW / viewH
        val imgRatio = imgW / imgH

        val drawW: Float
        val drawH: Float
        if (imgRatio > viewRatio) {
            drawW = viewW
            drawH = viewW / imgRatio
        } else {
            drawW = viewH * imgRatio
            drawH = viewH
        }

        val left = (viewW - drawW) / 2f
        val top = (viewH - drawH) / 2f
        imageBounds.set(left, top, left + drawW, top + drawH)
    }

    private fun toScreenRect(norm: RectF, out: RectF) {
        out.set(
            imageBounds.left + norm.left * imageBounds.width(),
            imageBounds.top + norm.top * imageBounds.height(),
            imageBounds.left + norm.right * imageBounds.width(),
            imageBounds.top + norm.bottom * imageBounds.height()
        )
    }

    private fun toNormalizedRect(screen: RectF, out: RectF) {
        out.set(
            ((screen.left - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f),
            ((screen.top - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f),
            ((screen.right - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f),
            ((screen.bottom - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = originalBitmap ?: return

        calculateImageBounds()

        // 1. Draw Original Image
        canvas.drawBitmap(bmp, null, imageBounds, null)

        // 2. Draw Blurred overlays
        for (i in blurRects.indices) {
            val strength = if (i in blurStrengths.indices) blurStrengths[i] else 10
            toScreenRect(blurRects[i], tempRect)

            canvas.save()
            canvas.clipRect(imageBounds)
            
            // Expand the crop region to include the cropExpansion area so we have pixels outside the box to blend
            val featherRadius = (strength * 6.0f).coerceIn(25f, 150f)
            val cropExpansion = featherRadius * 2.0f

            // Convert cropExpansion in screen space to pixel coordinates on the bitmap
            val cropWBitmap = (cropExpansion / imageBounds.width() * bmp.width).toInt()
            val cropHBitmap = (cropExpansion / imageBounds.height() * bmp.height).toInt()

            val leftPx = ((blurRects[i].left * bmp.width).toInt() - cropWBitmap).coerceIn(0, bmp.width - 1)
            val topPx = ((blurRects[i].top * bmp.height).toInt() - cropHBitmap).coerceIn(0, bmp.height - 1)
            val rightPx = ((blurRects[i].right * bmp.width).toInt() + cropWBitmap).coerceIn(0, bmp.width - 1)
            val bottomPx = ((blurRects[i].bottom * bmp.height).toInt() + cropHBitmap).coerceIn(0, bmp.height - 1)

            val subW = (rightPx - leftPx + 1).coerceAtLeast(1)
            val subH = (bottomPx - topPx + 1).coerceAtLeast(1)

            val region = Bitmap.createBitmap(bmp, leftPx, topPx, subW, subH)
            val blr = blurRegion(region, strength)
            region.recycle()

            // Calculate expanded screen bounds matching the expanded bitmap crop region
            val expandedTempRect = RectF(
                imageBounds.left + (leftPx.toFloat() / bmp.width) * imageBounds.width(),
                imageBounds.top + (topPx.toFloat() / bmp.height) * imageBounds.height(),
                imageBounds.left + (rightPx.toFloat() / bmp.width) * imageBounds.width(),
                imageBounds.top + (bottomPx.toFloat() / bmp.height) * imageBounds.height()
            )

            // Draw blurred overlay with feathered edges using BitmapShader to avoid saveLayer color-bleeding entirely!
            val shader = BitmapShader(blr, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            
            // Adjust shader matrix to match expandedTempRect screen coordinates
            val matrix = Matrix()
            val srcBounds = RectF(0f, 0f, blr.width.toFloat(), blr.height.toFloat())
            matrix.setRectToRect(srcBounds, expandedTempRect, Matrix.ScaleToFit.FILL)
            shader.setLocalMatrix(matrix)

            // 1. Draw solid interior rectangle (guarantees core stays 100% blurred)
            val solidPaint = Paint().apply {
                isAntiAlias = true
                setShader(shader)
                style = Paint.Style.FILL
            }
            canvas.drawRect(tempRect, solidPaint)

            // 2. Draw outer feathered area (seamless transition with absolutely zero halo color bleeding)
            val maskPaint = Paint().apply {
                isAntiAlias = true
                setShader(shader)
                style = Paint.Style.FILL
                maskFilter = BlurMaskFilter(featherRadius, BlurMaskFilter.Blur.NORMAL)
            }
            val maskRect = RectF(
                tempRect.left - featherRadius * 0.8f,
                tempRect.top - featherRadius * 0.8f,
                tempRect.right + featherRadius * 0.8f,
                tempRect.bottom + featherRadius * 0.8f
            )
            canvas.drawRect(maskRect, maskPaint)
            blr.recycle()
            canvas.restore()

            // Draw border around the crop box
            if (i == selectedIndex) {
                canvas.drawRect(tempRect, selectedBorderPaint)
                
                // Draw corner resize handles
                canvas.drawCircle(tempRect.left, tempRect.top, 14f, handlePaint)
                canvas.drawCircle(tempRect.left, tempRect.top, 14f, handleBorderPaint)
                
                canvas.drawCircle(tempRect.right, tempRect.top, 14f, handlePaint)
                canvas.drawCircle(tempRect.right, tempRect.top, 14f, handleBorderPaint)
                
                canvas.drawCircle(tempRect.left, tempRect.bottom, 14f, handlePaint)
                canvas.drawCircle(tempRect.left, tempRect.bottom, 14f, handleBorderPaint)
                
                canvas.drawCircle(tempRect.right, tempRect.bottom, 14f, handlePaint)
                canvas.drawCircle(tempRect.right, tempRect.bottom, 14f, handleBorderPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Check if clicked resizing corner on selected item
                if (selectedIndex >= 0) {
                    toScreenRect(blurRects[selectedIndex], tempRect)
                    if (Math.hypot((x - tempRect.left).toDouble(), (y - tempRect.top).toDouble()) < HANDLE_RADIUS * 2) {
                        touchMode = MODE_RESIZE
                        activeResizeCorner = CORNER_TL
                        lastX = x; lastY = y
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    if (Math.hypot((x - tempRect.right).toDouble(), (y - tempRect.top).toDouble()) < HANDLE_RADIUS * 2) {
                        touchMode = MODE_RESIZE
                        activeResizeCorner = CORNER_TR
                        lastX = x; lastY = y
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    if (Math.hypot((x - tempRect.left).toDouble(), (y - tempRect.bottom).toDouble()) < HANDLE_RADIUS * 2) {
                        touchMode = MODE_RESIZE
                        activeResizeCorner = CORNER_BL
                        lastX = x; lastY = y
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                    if (Math.hypot((x - tempRect.right).toDouble(), (y - tempRect.bottom).toDouble()) < HANDLE_RADIUS * 2) {
                        touchMode = MODE_RESIZE
                        activeResizeCorner = CORNER_BR
                        lastX = x; lastY = y
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }

                // Check if clicked inside any box to drag
                for (i in blurRects.indices.reversed()) {
                    toScreenRect(blurRects[i], tempRect)
                    if (tempRect.contains(x, y)) {
                        selectedIndex = i
                        onSelectionChangedListener?.invoke(true)
                        val strength = if (i in blurStrengths.indices) blurStrengths[i] else 10
                        onStrengthChangedListener?.invoke(strength)
                        touchMode = MODE_DRAG
                        activeIndex = i
                        lastX = x; lastY = y
                        parent?.requestDisallowInterceptTouchEvent(true)
                        invalidate()
                        return true
                    }
                }

                // Clicked outside all boxes
                selectedIndex = -1
                onSelectionChangedListener?.invoke(false)
                invalidate()
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = x - lastX
                val dy = y - lastY

                if (touchMode == MODE_DRAG && activeIndex >= 0) {
                    val rectNorm = blurRects[activeIndex]
                    toScreenRect(rectNorm, tempRect)
                    
                    var newL = tempRect.left + dx
                    var newT = tempRect.top + dy
                    val w = tempRect.width()
                    val h = tempRect.height()

                    // Constrain inside imageBounds
                    if (newL < imageBounds.left) newL = imageBounds.left
                    if (newT < imageBounds.top) newT = imageBounds.top
                    if (newL + w > imageBounds.right) newL = imageBounds.right - w
                    if (newT + h > imageBounds.bottom) newT = imageBounds.bottom - h

                    tempRect.set(newL, newT, newL + w, newT + h)
                    toNormalizedRect(tempRect, rectNorm)
                    
                    lastX = x; lastY = y
                    invalidate()
                    return true
                }

                if (touchMode == MODE_RESIZE && selectedIndex >= 0) {
                    val rectNorm = blurRects[selectedIndex]
                    toScreenRect(rectNorm, tempRect)

                    when (activeResizeCorner) {
                        CORNER_TL -> {
                            tempRect.left = (tempRect.left + dx).coerceIn(imageBounds.left, tempRect.right - 8f)
                            tempRect.top = (tempRect.top + dy).coerceIn(imageBounds.top, tempRect.bottom - 8f)
                        }
                        CORNER_TR -> {
                            tempRect.right = (tempRect.right + dx).coerceIn(tempRect.left + 8f, imageBounds.right)
                            tempRect.top = (tempRect.top + dy).coerceIn(imageBounds.top, tempRect.bottom - 8f)
                        }
                        CORNER_BL -> {
                            tempRect.left = (tempRect.left + dx).coerceIn(imageBounds.left, tempRect.right - 8f)
                            tempRect.bottom = (tempRect.bottom + dy).coerceIn(tempRect.top + 8f, imageBounds.bottom)
                        }
                        CORNER_BR -> {
                            tempRect.right = (tempRect.right + dx).coerceIn(tempRect.left + 8f, imageBounds.right)
                            tempRect.bottom = (tempRect.bottom + dy).coerceIn(tempRect.top + 8f, imageBounds.bottom)
                        }
                    }

                    toNormalizedRect(tempRect, rectNorm)
                    lastX = x; lastY = y
                    invalidate()
                    return true
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touchMode = MODE_NONE
                activeIndex = -1
                activeResizeCorner = CORNER_NONE
            }
        }
        return super.onTouchEvent(event)
    }

    fun addBlurBox() {
        // Adds a default box centered inside the image bounds
        blurRects.add(RectF(0.3f, 0.3f, 0.7f, 0.7f))
        blurStrengths.add(10) // default strength
        selectedIndex = blurRects.size - 1
        onSelectionChangedListener?.invoke(true)
        onStrengthChangedListener?.invoke(10)
        invalidate()
    }

    fun deleteSelectedBox() {
        if (selectedIndex in blurRects.indices) {
            blurRects.removeAt(selectedIndex)
            if (selectedIndex in blurStrengths.indices) {
                blurStrengths.removeAt(selectedIndex)
            }
            selectedIndex = -1
            onSelectionChangedListener?.invoke(false)
            invalidate()
        }
    }

    fun clearAll() {
        blurRects.clear()
        blurStrengths.clear()
        selectedIndex = -1
        onSelectionChangedListener?.invoke(false)
        invalidate()
    }

    fun updateSelectedStrength(strength: Int) {
        if (selectedIndex in blurStrengths.indices) {
            blurStrengths[selectedIndex] = strength
            invalidate()
        }
    }
}
