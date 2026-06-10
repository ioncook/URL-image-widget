package com.example.urlimagewidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.*
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class ImageDownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val widgetId = inputData.getInt(KEY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val imageUrl = inputData.getString(KEY_IMAGE_URL)

        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID || imageUrl.isNullOrEmpty()) {
            return Result.failure()
        }

        val appWidgetManager = AppWidgetManager.getInstance(context)
        val views = RemoteViews(context.packageName, R.layout.widget_layout)

        // Show loading progress on the widget
        views.setViewVisibility(R.id.widget_loading_container, View.VISIBLE)
        views.setTextViewText(R.id.widget_status_text, "Downloading...")
        appWidgetManager.partiallyUpdateAppWidget(widgetId, views)

        try {
            val downloaded = downloadBitmap(imageUrl)
            if (downloaded != null) {
                val prefs = context.getSharedPreferences(
                    WidgetConfigurationActivity.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                
                // Apply blur elements if any exist
                val rectsStr = prefs.getString(BlurActivity.PREF_BLUR_RECTS_KEY + widgetId, "") ?: ""
                val rawBitmap = if (rectsStr.isNotEmpty()) {
                    val mutableBmp = downloaded.copy(Bitmap.Config.ARGB_8888, true)
                    applyBlurRects(mutableBmp, rectsStr)
                    downloaded.recycle()
                    mutableBmp
                } else {
                    downloaded
                }

                // Read frame configuration
                val frameStyle = prefs.getString(WidgetConfigurationActivity.PREF_FRAME_KEY + widgetId, "Off") ?: "Off"
                val fitStyle = prefs.getString(WidgetConfigurationActivity.PREF_FIT_KEY + widgetId, "Crop") ?: "Crop"
                val paddingDp = prefs.getInt(WidgetConfigurationActivity.PREF_PADDING_KEY + widgetId, 8)
                val cropX = prefs.getFloat(WidgetConfigurationActivity.PREF_CROP_X_KEY + widgetId, 0.5f)
                val cropY = prefs.getFloat(WidgetConfigurationActivity.PREF_CROP_Y_KEY + widgetId, 0.5f)

                // Determine target dimensions based on saved/updated dimensions in preferences
                val density = context.resources.displayMetrics.density
                
                // Read exact dimensions computed by configuration or options changed listener
                val widthDp = prefs.getFloat(WidgetConfigurationActivity.PREF_WIDTH_DP_KEY + widgetId, 180f)
                val heightDp = prefs.getFloat(WidgetConfigurationActivity.PREF_HEIGHT_DP_KEY + widgetId, 110f)
                
                // Target size (scaled at 1x density matching exact screen pixel bounds to prevent memory overflow)
                val targetWidth = (widthDp * density).toInt()
                val targetHeight = (heightDp * density).toInt()

                // Determine rounding radius from system theme corner radius dynamically
                val isMoto = android.os.Build.MANUFACTURER.lowercase().contains("motorola") ||
                             android.os.Build.BRAND.lowercase().contains("moto")
                val outerRadiusPx = if (isMoto) {
                    16f * density
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    val resId = context.resources.getIdentifier("system_app_widget_background_radius", "dimen", "android")
                    if (resId != 0) context.resources.getDimension(resId) else 16f * density
                } else {
                    16f * density
                }

                val innerRadiusPx = if (frameStyle == "Off") {
                    outerRadiusPx
                } else {
                    val paddingPx = paddingDp * density
                    (outerRadiusPx - paddingPx).coerceAtLeast(0f)
                }
                
                val radiusPx = innerRadiusPx

                // Determine frame background color if not Off
                val customColorStr = prefs.getString(WidgetConfigurationActivity.PREF_COLOR_KEY + widgetId, "#FF5722") ?: "#FF5722"
                var frameColor = Color.TRANSPARENT
                when (frameStyle) {
                    "Dark" -> frameColor = Color.parseColor("#2C2C2C")
                    "Light" -> frameColor = Color.parseColor("#F5F5F5")
                    "Material" -> {
                        val isDark = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                        frameColor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            if (isDark) context.getColor(android.R.color.system_accent1_800) else context.getColor(android.R.color.system_accent1_700)
                        } else {
                            val typedValue = android.util.TypedValue()
                            if (context.theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)) {
                                typedValue.data
                            } else {
                                Color.parseColor("#FF5722")
                            }
                        }
                    }
                    "Custom" -> {
                        try {
                            frameColor = Color.parseColor(customColorStr)
                        } catch (e: Exception) {
                            frameColor = Color.parseColor("#2C2C2C")
                        }
                    }
                }

                // Process bitmap based on selected fit style and frame configuration
                val processedBitmap = if (frameStyle == "Off") {
                    // No frame: just scale/crop/stretch to the target widget size and round to outerRadiusPx
                    val img = when (fitStyle) {
                        "Stretch" -> Bitmap.createScaledBitmap(rawBitmap, targetWidth, targetHeight, true)
                        "Fill" -> scaleToFitBitmap(rawBitmap, targetWidth, targetHeight)
                        else -> cropBitmap(rawBitmap, targetWidth, targetHeight, cropX, cropY)
                    }
                    getRoundedCornerBitmap(img, radiusPx)
                } else {
                    // Frame is ON: draw frame background and place inner rounded image inside
                    val paddingPx = paddingDp * density
                    val containerWidth = (targetWidth - 2 * paddingPx).toInt().coerceAtLeast(1)
                    val containerHeight = (targetHeight - 2 * paddingPx).toInt().coerceAtLeast(1)

                    val innerImg = when (fitStyle) {
                        "Stretch" -> Bitmap.createScaledBitmap(rawBitmap, containerWidth, containerHeight, true)
                        "Fill" -> scaleToFitBitmap(rawBitmap, containerWidth, containerHeight)
                        else -> cropBitmap(rawBitmap, containerWidth, containerHeight, cropX, cropY)
                    }

                    // For Fill mode, match the frame size to the scaled image + padding, otherwise fill target bounds
                    val frameWidth = if (fitStyle == "Fill") (innerImg.width + 2 * paddingPx).toInt() else targetWidth
                    val frameHeight = if (fitStyle == "Fill") (innerImg.height + 2 * paddingPx).toInt() else targetHeight

                    val output = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(output)

                    val bgPaint = Paint().apply {
                        isAntiAlias = true
                        color = frameColor
                    }
                    canvas.drawRoundRect(RectF(0f, 0f, frameWidth.toFloat(), frameHeight.toFloat()), outerRadiusPx, outerRadiusPx, bgPaint)

                    val roundedImage = getRoundedCornerBitmap(innerImg, innerRadiusPx)
                    canvas.drawBitmap(roundedImage, paddingPx, paddingPx, null)
                    output
                }

                // Save bitmap to a local file for persistence
                val file = File(context.filesDir, "widget_image_$widgetId.png")
                FileOutputStream(file).use { out ->
                    processedBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                // Trigger WidgetProvider update to refresh layout with the saved file
                ImageWidgetProvider.updateAppWidget(context, appWidgetManager, widgetId)
                return Result.success()
            } else {
                showError(appWidgetManager, widgetId, views, "Failed to decode image")
                return Result.failure()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            showError(appWidgetManager, widgetId, views, "Error: ${e.message}")
            return Result.failure()
        }
    }

    private fun showError(
        appWidgetManager: AppWidgetManager,
        widgetId: Int,
        views: RemoteViews,
        message: String
    ) {
        views.setViewVisibility(R.id.widget_loading_container, View.VISIBLE)
        views.setTextViewText(R.id.widget_status_text, message)
        appWidgetManager.updateAppWidget(widgetId, views)
    }

    private fun cropBitmap(srcBmp: Bitmap, targetWidth: Int, targetHeight: Int, offsetX: Float, offsetY: Float): Bitmap {
        val width = srcBmp.width
        val height = srcBmp.height

        val srcRatio = width.toFloat() / height.toFloat()
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()

        var cropWidth = width
        var cropHeight = height
        var cropX = 0
        var cropY = 0

        if (srcRatio > targetRatio) {
            // Source is wider than target
            cropWidth = (height * targetRatio).toInt()
            cropX = ((width - cropWidth) * offsetX).toInt()
        } else {
            // Source is taller than target
            cropHeight = (width / targetRatio).toInt()
            cropY = ((height - cropHeight) * offsetY).toInt()
        }

        // Constrain bounds to prevent cropping outside bitmap
        var finalCropX = cropX
        var finalCropY = cropY
        if (finalCropX < 0) finalCropX = 0
        if (finalCropY < 0) finalCropY = 0
        if (finalCropX + cropWidth > width) finalCropX = width - cropWidth
        if (finalCropY + cropHeight > height) finalCropY = height - cropHeight

        val cropped = Bitmap.createBitmap(srcBmp, finalCropX, finalCropY, cropWidth, cropHeight)
        return Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
    }

    private fun scaleToFitBitmap(srcBmp: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val width = srcBmp.width
        val height = srcBmp.height
        val ratio = width.toFloat() / height.toFloat()
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()

        var newWidth = targetWidth
        var newHeight = targetHeight

        if (ratio > targetRatio) {
            newHeight = (targetWidth / ratio).toInt()
        } else {
            newWidth = (targetHeight * ratio).toInt()
        }

        if (newWidth <= 0) newWidth = 1
        if (newHeight <= 0) newHeight = 1

        return Bitmap.createScaledBitmap(srcBmp, newWidth, newHeight, true)
    }

    private fun getRoundedCornerBitmap(bitmap: Bitmap, radiusPx: Float): Bitmap {
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint()
        val rect = Rect(0, 0, bitmap.width, bitmap.height)
        val rectF = RectF(rect)

        paint.isAntiAlias = true
        canvas.drawARGB(0, 0, 0, 0)
        paint.color = -0xbdbdbe
        canvas.drawRoundRect(rectF, radiusPx, radiusPx, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(bitmap, rect, rect, paint)
        return output
    }

    private fun applyBlurRects(bitmap: Bitmap, rectsStr: String) {
        try {
            val w = bitmap.width
            val h = bitmap.height
            val boxes = rectsStr.split(";").filter { it.isNotEmpty() }
            for (box in boxes) {
                val pts = box.split(",").map { it.toFloat() }
                if (pts.size >= 4) {
                    val left = (pts[0] * w).toInt().coerceIn(0, w - 1)
                    val top = (pts[1] * h).toInt().coerceIn(0, h - 1)
                    val right = (pts[2] * w).toInt().coerceIn(0, w - 1)
                    val bottom = (pts[3] * h).toInt().coerceIn(0, h - 1)
                    val strength = if (pts.size >= 5) pts[4].toInt() else 10
                    
                    val featherRadius = (strength * 6.0f).coerceIn(25f, 150f)
                    val cropExpansion = featherRadius * 2.0f
                    
                    // Convert cropExpansion into pixel dimensions relative to image width/height
                    val cropW = cropExpansion.toInt()
                    val cropH = cropExpansion.toInt()

                    val leftPx = (left - cropW).coerceIn(0, w - 1)
                    val topPx = (top - cropH).coerceIn(0, h - 1)
                    val rightPx = (right + cropW).coerceIn(0, w - 1)
                    val bottomPx = (bottom + cropH).coerceIn(0, h - 1)

                    val rectWidth = rightPx - leftPx + 1
                    val rectHeight = bottomPx - topPx + 1
                    if (rectWidth > 0 && rectHeight > 0) {
                        val region = Bitmap.createBitmap(bitmap, leftPx, topPx, rectWidth, rectHeight)
                        val blurredRegion = blurRegion(region, strength)
                        
                        val canvas = Canvas(bitmap)
                        val shader = BitmapShader(blurredRegion, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                        
                        // Adjust shader matrix to match expanded pixel coordinates
                        val matrix = Matrix()
                        val srcBounds = RectF(0f, 0f, blurredRegion.width.toFloat(), blurredRegion.height.toFloat())
                        val targetBounds = RectF(leftPx.toFloat(), topPx.toFloat(), rightPx.toFloat(), bottomPx.toFloat())
                        matrix.setRectToRect(srcBounds, targetBounds, Matrix.ScaleToFit.FILL)
                        shader.setLocalMatrix(matrix)

                        // 1. Draw solid interior rectangle (guarantees core stays 100% blurred)
                        val solidPaint = Paint().apply {
                            isAntiAlias = true
                            setShader(shader)
                            style = Paint.Style.FILL
                        }
                        canvas.drawRect(
                            left.toFloat(), 
                            top.toFloat(), 
                            right.toFloat(), 
                            bottom.toFloat(), 
                            solidPaint
                        )

                        // 2. Draw outer feathered area (seamless transition with absolutely zero halo color bleeding)
                        val maskPaint = Paint().apply {
                            isAntiAlias = true
                            setShader(shader)
                            style = Paint.Style.FILL
                            maskFilter = BlurMaskFilter(featherRadius, BlurMaskFilter.Blur.NORMAL)
                        }
                        val maskRect = RectF(
                            left.toFloat() - featherRadius * 0.8f,
                            top.toFloat() - featherRadius * 0.8f,
                            right.toFloat() + featherRadius * 0.8f,
                            bottom.toFloat() + featherRadius * 0.8f
                        )
                        canvas.drawRect(maskRect, maskPaint)
                        
                        region.recycle()
                        blurredRegion.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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

    private fun downloadBitmap(urlStr: String): Bitmap? {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null
        try {
            val url = URL(urlStr)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.doInput = true
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                inputStream = BufferedInputStream(connection.inputStream)

                val bytes = inputStream.readBytes()
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                options.inSampleSize = calculateInSampleSize(options, 1024, 1024)
                options.inJustDecodeBounds = false

                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }
        } finally {
            inputStream?.close()
            connection?.disconnect()
        }
        return null
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    companion object {
        const val KEY_WIDGET_ID = "widget_id"
        const val KEY_IMAGE_URL = "image_url"
    }
}
