package com.example.urlimagewidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.util.SizeF

data class WidgetSizeResult(
    val width: Float,
    val height: Float,
    val cols: Int,
    val rows: Int
)

object WidgetSizeResolver {
    
    fun dpToCols(dp: Int): Int {
        if (dp < 100) return 1
        if (dp < 180) return 2
        if (dp < 260) return 3
        if (dp < 340) return 4
        return ((dp + 30) / 70).coerceAtLeast(5)
    }

    fun dpToRows(dp: Int): Int {
        if (dp < 100) return 1
        if (dp < 250) return 2
        if (dp < 360) return 3
        if (dp < 470) return 4
        return ((dp + 45) / 90).coerceAtLeast(5)
    }

    fun resolveWidgetSize(options: Bundle?, context: Context, isInitialSetup: Boolean = false): WidgetSizeResult {
        if (options == null) {
            return WidgetSizeResult(180f, 110f, 3, 2)
        }
        
        val rawWidth: Float
        val rawHeight: Float
        
        val metrics = context.resources.displayMetrics
        val isLandscape = metrics.widthPixels > metrics.heightPixels

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val sizes = options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
            if (!sizes.isNullOrEmpty()) {
                val size = if (isLandscape) {
                    sizes.minWithOrNull(Comparator { s1, s2 ->
                        if (s1.width != s2.width) {
                            s2.width.compareTo(s1.width)
                        } else {
                            s1.height.compareTo(s2.height)
                        }
                    }) ?: sizes[0]
                } else {
                    sizes.minWithOrNull(Comparator { s1, s2 ->
                        if (s1.width != s2.width) {
                            s1.width.compareTo(s2.width)
                        } else {
                            s2.height.compareTo(s1.height)
                        }
                    }) ?: sizes[0]
                }
                rawWidth = size.width
                rawHeight = size.height
            } else {
                val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180).toFloat()
                val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110).toFloat()
                val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 180).toFloat()
                val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).toFloat()
                rawWidth = if (isLandscape) maxWidth else minWidth
                rawHeight = if (isLandscape) minHeight else maxHeight
            }
        } else {
            val minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180).toFloat()
            val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110).toFloat()
            val maxWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 180).toFloat()
            val maxHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).toFloat()
            rawWidth = if (isLandscape) maxWidth else minWidth
            rawHeight = if (isLandscape) minHeight else maxHeight
        }

        var resolvedWidth = rawWidth
        var resolvedHeight = rawHeight

        // Fallback to default XML dimensions if it is initial setup or options bundle contains zeros/invalid values
        if (isInitialSetup || resolvedWidth < 40f || resolvedHeight < 40f) {
            resolvedWidth = 180f
            resolvedHeight = 110f
        }

        // Determine detected columns and rows
        val cols = dpToCols(resolvedWidth.toInt())
        val rows = dpToRows(resolvedHeight.toInt())

        // Calculate screen sizes in DP
        val density = metrics.density
        val screenWidthDp = metrics.widthPixels / density
        val screenHeightDp = metrics.heightPixels / density

        // Estimate visual cell aspect ratio
        val cellAspectRatio = if (isLandscape) {
            val colsVal = if (cols > 0) cols else 1
            val cellWidthDp = resolvedWidth / colsVal
            val gridCols = if (cellWidthDp > 110f) 6f else 7f
            val gridRows = 5f
            (screenWidthDp / gridCols) / ((screenHeightDp - 80f) / gridRows)
        } else {
            val colsVal = if (cols > 0) cols else 1
            val rowsVal = if (rows > 0) rows else 1
            val cellWidthDp = resolvedWidth / colsVal
            val cellHeightDp = resolvedHeight / rowsVal
            val gridCols = if (cellWidthDp > 84f) 4f else 5f
            val gridRows = if (cellHeightDp > 115f) 6f else 7f
            (screenWidthDp / gridCols) / ((screenHeightDp - 130f) / gridRows)
        }

        // Calculate widget visual aspect ratio
        val widgetAspectRatio = (cols.toFloat() / rows.toFloat()) * cellAspectRatio

        // Adjust raw width/height to follow this aspect ratio
        val adjustedWidth = resolvedWidth
        val adjustedHeight = resolvedWidth / widgetAspectRatio

        android.util.Log.d("WidgetSizeResolver", "rawSize=${resolvedWidth}x${resolvedHeight}, cols=$cols, rows=$rows, isLandscape=$isLandscape, screenDp=${screenWidthDp}x${screenHeightDp}, cellAspect=$cellAspectRatio, widgetAspect=$widgetAspectRatio, adjusted=${adjustedWidth}x${adjustedHeight}")

        return WidgetSizeResult(adjustedWidth, adjustedHeight, cols, rows)
    }
}
