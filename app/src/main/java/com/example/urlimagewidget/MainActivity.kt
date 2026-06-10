package com.example.urlimagewidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var scrollWidgetList: ScrollView
    private lateinit var layoutWidgetList: LinearLayout
    private lateinit var layoutEmptyState: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply Material You dynamic colors
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)

        // Style status bar and navigation bar to match dark theme background
        window.statusBarColor = Color.parseColor("#121212")
        window.navigationBarColor = Color.parseColor("#121212")
        val decorView = window.decorView
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        // Display logo and title next to each other using a custom view in the ActionBar/Toolbar
        supportActionBar?.let {
            it.setDisplayShowCustomEnabled(true)
            it.setDisplayShowTitleEnabled(false)
            it.setDisplayShowHomeEnabled(false)
            it.setCustomView(R.layout.layout_action_bar)
            it.show()
        }

        setContentView(R.layout.activity_main)

        scrollWidgetList = findViewById(R.id.scroll_widget_list)
        layoutWidgetList = findViewById(R.id.layout_widget_list)
        layoutEmptyState = findViewById(R.id.layout_empty_state)
    }

    override fun onResume() {
        super.onResume()
        refreshWidgetList()
    }

    private fun refreshWidgetList() {
        layoutWidgetList.removeAllViews()

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, ImageWidgetProvider::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        if (widgetIds == null || widgetIds.isEmpty()) {
            scrollWidgetList.visibility = View.GONE
            layoutEmptyState.visibility = View.VISIBLE
            return
        }

        scrollWidgetList.visibility = View.VISIBLE
        layoutEmptyState.visibility = View.GONE

        val prefs = getSharedPreferences(WidgetConfigurationActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val inflater = LayoutInflater.from(this)

        for (widgetId in widgetIds) {
            val itemView = inflater.inflate(R.layout.item_widget, layoutWidgetList, false)

            val thumbnailView = itemView.findViewById<ImageView>(R.id.widget_thumbnail)
            val titleView = itemView.findViewById<TextView>(R.id.text_widget_title)
            val urlView = itemView.findViewById<TextView>(R.id.text_widget_url)
            val btnEdit = itemView.findViewById<ImageButton>(R.id.button_edit_widget)
            val btnRefresh = itemView.findViewById<ImageButton>(R.id.button_refresh_widget)

            // Read properties
            val url = prefs.getString(WidgetConfigurationActivity.PREF_PREFIX_KEY + widgetId, "") ?: ""
            val cols = prefs.getInt(WidgetConfigurationActivity.PREF_COLS_KEY + widgetId, -1)
            val rows = prefs.getInt(WidgetConfigurationActivity.PREF_ROWS_KEY + widgetId, -1)
            val sizeStr = if (cols > 0 && rows > 0) "${cols}x${rows}" else "Not configured"

            titleView.text = "Widget #$widgetId ($sizeStr)"
            if (url.isNotEmpty()) {
                urlView.text = url
                btnRefresh.visibility = View.VISIBLE
            } else {
                urlView.text = "Tap Edit to configure"
                btnRefresh.visibility = View.GONE
            }

            // Load thumbnail image if it exists
            val file = java.io.File(filesDir, "widget_image_$widgetId.png")
            if (file.exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        thumbnailView.setImageBitmap(bitmap)
                    } else {
                        thumbnailView.setImageResource(R.drawable.placeholder_image)
                    }
                } catch (e: Exception) {
                    thumbnailView.setImageResource(R.drawable.placeholder_image)
                }
            } else {
                thumbnailView.setImageResource(R.drawable.placeholder_image)
            }

            // Edit Click Action
            btnEdit.setOnClickListener {
                val intent = Intent(this, WidgetConfigurationActivity::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                }
                startActivity(intent)
            }

            // Refresh Click Action
            btnRefresh.setOnClickListener {
                if (url.isNotEmpty()) {
                    ImageWidgetProvider.triggerWidgetUpdate(this, widgetId, url)
                    Toast.makeText(this, "Refreshing Widget #$widgetId...", Toast.LENGTH_SHORT).show()
                }
            }

            layoutWidgetList.addView(itemView)
        }
    }
}
