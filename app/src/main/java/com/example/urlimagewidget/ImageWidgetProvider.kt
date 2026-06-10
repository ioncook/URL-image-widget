package com.example.urlimagewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PorterDuff
import android.os.Bundle
import android.widget.RemoteViews
import androidx.work.*

open class ImageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val prefs = context.getSharedPreferences(
            WidgetConfigurationActivity.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        
        // Resolve new visual size and update SharedPreferences
        val size = WidgetSizeResolver.resolveWidgetSize(newOptions, context)
        val isLandscape = context.resources.displayMetrics.widthPixels > context.resources.displayMetrics.heightPixels
        prefs.edit().apply {
            putFloat(WidgetConfigurationActivity.PREF_WIDTH_DP_KEY + appWidgetId, size.width)
            putFloat(WidgetConfigurationActivity.PREF_HEIGHT_DP_KEY + appWidgetId, size.height)
            putInt(WidgetConfigurationActivity.PREF_COLS_KEY + appWidgetId, size.cols)
            putInt(WidgetConfigurationActivity.PREF_ROWS_KEY + appWidgetId, size.rows)
            putBoolean(WidgetConfigurationActivity.PREF_IS_LANDSCAPE_KEY + appWidgetId, isLandscape)
            apply()
        }

        val url = prefs.getString(WidgetConfigurationActivity.PREF_PREFIX_KEY + appWidgetId, null)
        if (!url.isNullOrEmpty()) {
            triggerWidgetUpdate(context, appWidgetId, url)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (ACTION_REFRESH == intent.action) {
            val appWidgetId = intent.getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID,
                AppWidgetManager.INVALID_APPWIDGET_ID
            )
            if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                // Retrieve URL and trigger refresh
                val prefs = context.getSharedPreferences(
                    WidgetConfigurationActivity.PREFS_NAME,
                    Context.MODE_PRIVATE
                )
                val url = prefs.getString(WidgetConfigurationActivity.PREF_PREFIX_KEY + appWidgetId, null)
                if (!url.isNullOrEmpty()) {
                    triggerWidgetUpdate(context, appWidgetId, url)
                }
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val prefs = context.getSharedPreferences(
            WidgetConfigurationActivity.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        val edit = prefs.edit()
        val workManager = WorkManager.getInstance(context.applicationContext)
        for (appWidgetId in appWidgetIds) {
            edit.remove(WidgetConfigurationActivity.PREF_PREFIX_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_INTERVAL_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_FRAME_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_PADDING_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_FIT_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_CROP_X_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_CROP_Y_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_GRID_SIZE_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_MATERIAL_COLOR_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_WIDTH_DP_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_HEIGHT_DP_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_COLS_KEY + appWidgetId)
            edit.remove(WidgetConfigurationActivity.PREF_ROWS_KEY + appWidgetId)
            workManager.cancelUniqueWork("widget_update_$appWidgetId")
            
            // Delete the persisted image file
            val file = java.io.File(context.filesDir, "widget_image_$appWidgetId.png")
            if (file.exists()) {
                file.delete()
            }
        }
        edit.apply()
    }

    companion object {
        private const val ACTION_REFRESH = "com.example.urlimagewidget.REFRESH"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = context.getSharedPreferences(
                WidgetConfigurationActivity.PREFS_NAME,
                Context.MODE_PRIVATE
            )

            // Resolve dynamic dimensions from options and update cache if changed
            val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
            val resolvedSize = WidgetSizeResolver.resolveWidgetSize(options, context)
            val storedWidth = prefs.getFloat(WidgetConfigurationActivity.PREF_WIDTH_DP_KEY + appWidgetId, -1f)
            val storedHeight = prefs.getFloat(WidgetConfigurationActivity.PREF_HEIGHT_DP_KEY + appWidgetId, -1f)
            val storedCols = prefs.getInt(WidgetConfigurationActivity.PREF_COLS_KEY + appWidgetId, -1)
            val storedRows = prefs.getInt(WidgetConfigurationActivity.PREF_ROWS_KEY + appWidgetId, -1)
            
            if (storedWidth != resolvedSize.width || storedHeight != resolvedSize.height || storedCols != resolvedSize.cols || storedRows != resolvedSize.rows) {
                android.util.Log.d("ImageWidgetProvider", "updateAppWidget: Size/grid changed. Saving and triggering update.")
                val isLandscape = context.resources.displayMetrics.widthPixels > context.resources.displayMetrics.heightPixels
                prefs.edit().apply {
                    putFloat(WidgetConfigurationActivity.PREF_WIDTH_DP_KEY + appWidgetId, resolvedSize.width)
                    putFloat(WidgetConfigurationActivity.PREF_HEIGHT_DP_KEY + appWidgetId, resolvedSize.height)
                    putInt(WidgetConfigurationActivity.PREF_COLS_KEY + appWidgetId, resolvedSize.cols)
                    putInt(WidgetConfigurationActivity.PREF_ROWS_KEY + appWidgetId, resolvedSize.rows)
                    putBoolean(WidgetConfigurationActivity.PREF_IS_LANDSCAPE_KEY + appWidgetId, isLandscape)
                    apply()
                }
                
                val url = prefs.getString(WidgetConfigurationActivity.PREF_PREFIX_KEY + appWidgetId, null)
                if (!url.isNullOrEmpty()) {
                    triggerWidgetUpdate(context, appWidgetId, url)
                }
            }

            val views = RemoteViews(context.packageName, R.layout.widget_layout)

            // Setup refresh broadcast on click
            val intent = Intent(context, ImageWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_tap_target, pendingIntent)

            // Read frame configuration settings
            val frameStyle = prefs.getString(WidgetConfigurationActivity.PREF_FRAME_KEY + appWidgetId, "Off") ?: "Off"
            val fitStyle = prefs.getString(WidgetConfigurationActivity.PREF_FIT_KEY + appWidgetId, "Crop") ?: "Crop"
            val customColorStr = prefs.getString(WidgetConfigurationActivity.PREF_COLOR_KEY + appWidgetId, "#FF5722") ?: "#FF5722"
            val storedPadding = prefs.getInt(WidgetConfigurationActivity.PREF_PADDING_KEY + appWidgetId, 8)

            val density = context.resources.displayMetrics.density
            val paddingDp = if (frameStyle == "Off" || fitStyle == "Fill") 0 else storedPadding
            val paddingPx = (paddingDp * density).toInt()

            // Apply dynamic background color tint
            var frameColor = Color.TRANSPARENT
            if (fitStyle != "Fill") {
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
            }

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

            val innerRadiusPx = if (frameStyle == "Off" || fitStyle == "Fill") {
                outerRadiusPx
            } else {
                (outerRadiusPx - paddingPx).coerceAtLeast(0f)
            }

            if (frameStyle == "Off" || fitStyle == "Fill") {
                // Remove background completely for Off style or Fill style
                views.setInt(R.id.widget_container, "setBackgroundResource", 0)
                // Use the outer background shape to define the outer corner rounding path for the image container
                views.setInt(R.id.widget_image_container, "setBackgroundResource", R.drawable.widget_background)
                views.setColorStateList(R.id.widget_image_container, "setBackgroundTintList", ColorStateList.valueOf(Color.TRANSPARENT))
            } else {
                views.setInt(R.id.widget_container, "setBackgroundResource", R.drawable.widget_background)
                views.setColorStateList(R.id.widget_container, "setBackgroundTintList", ColorStateList.valueOf(frameColor))
                // Use the inner background shape to define the inner corner rounding path for the image container
                views.setInt(R.id.widget_image_container, "setBackgroundResource", R.drawable.widget_inner_background)
                views.setColorStateList(R.id.widget_image_container, "setBackgroundTintList", ColorStateList.valueOf(Color.TRANSPARENT))
            }
            // Explicitly enable clipToOutline on the image container for launcher compatibility
            views.setBoolean(R.id.widget_image_container, "setClipToOutline", true)

            // Dynamically set corner outline radius on API 31+ for pixel-perfect rounding matching the active device theme
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                views.setViewOutlinePreferredRadius(R.id.widget_image_container, innerRadiusPx, android.util.TypedValue.COMPLEX_UNIT_PX)
            }

            // Apply dynamic padding AFTER setting background resources to prevent Android from resetting the padding
            views.setViewPadding(R.id.widget_container, paddingPx, paddingPx, paddingPx, paddingPx)

            // Toggle active ImageView depending on fit style to avoid reflection calls
            val activeImageViewId = when (fitStyle) {
                "Fill" -> R.id.widget_image_fill
                "Crop" -> R.id.widget_image_crop
                else -> R.id.widget_image
            }
            val imageViews = listOf(R.id.widget_image, R.id.widget_image_crop, R.id.widget_image_fill)
            for (id in imageViews) {
                views.setViewVisibility(id, if (id == activeImageViewId) android.view.View.VISIBLE else android.view.View.GONE)
            }

            // Check if we have a persisted image file and set it
            val file = java.io.File(context.filesDir, "widget_image_$appWidgetId.png")
            if (file.exists()) {
                val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                if (bitmap != null) {
                    views.setViewVisibility(R.id.widget_loading_container, android.view.View.GONE)
                    views.setImageViewBitmap(activeImageViewId, bitmap)
                }
            }

            // Request the app widget manager to update the widget setup (binding tap listener)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        fun triggerWidgetUpdate(context: Context, widgetId: Int, url: String) {
            val data = workDataOf(
                ImageDownloadWorker.KEY_WIDGET_ID to widgetId,
                ImageDownloadWorker.KEY_IMAGE_URL to url
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<ImageDownloadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueue(workRequest)
        }

        fun schedulePeriodicUpdate(context: Context, widgetId: Int, url: String, intervalMinutes: Int) {
            val data = workDataOf(
                ImageDownloadWorker.KEY_WIDGET_ID to widgetId,
                ImageDownloadWorker.KEY_IMAGE_URL to url
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // System limit: PeriodicWorkRequest minimum is 15 minutes
            val clampedInterval = if (intervalMinutes < 15) 15 else intervalMinutes

            val workRequest = PeriodicWorkRequestBuilder<ImageDownloadWorker>(
                clampedInterval.toLong(), java.util.concurrent.TimeUnit.MINUTES
            )
                .setInputData(data)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                "widget_update_$widgetId",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
        }
    }
}
