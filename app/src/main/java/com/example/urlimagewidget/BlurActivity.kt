package com.example.urlimagewidget

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

class BlurActivity : AppCompatActivity() {

    private lateinit var blurCanvasView: BlurCanvasView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var btnAddBox: Button
    private lateinit var btnDeleteBox: Button
    private lateinit var btnClearAll: Button
    private lateinit var btnCancel: Button
    private lateinit var btnSave: Button

    private var appWidgetId = -1
    private var imageUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blur)

        // Make status bar dark
        window.statusBarColor = Color.parseColor("#121212")
        window.navigationBarColor = Color.parseColor("#121212")
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false

        blurCanvasView = findViewById(R.id.blur_canvas_view)
        loadingSpinner = findViewById(R.id.blur_loading)
        btnAddBox = findViewById(R.id.button_add_box)
        btnDeleteBox = findViewById(R.id.button_delete_box)
        btnClearAll = findViewById(R.id.button_clear_all)
        btnCancel = findViewById(R.id.button_blur_cancel)
        btnSave = findViewById(R.id.button_blur_save)

        appWidgetId = intent.getIntExtra("appWidgetId", -1)
        imageUrl = intent.getStringExtra("imageUrl") ?: ""

        if (appWidgetId == -1 || imageUrl.isEmpty()) {
            Toast.makeText(this, "Invalid arguments", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Restore saved blur boxes
        restoreBlurRects()

        // Toggle delete button and strength layout visibility based on selection
        val strengthLayout = findViewById<View>(R.id.layout_blur_strength)
        val strengthLabel = findViewById<android.widget.TextView>(R.id.text_blur_strength_label)
        val strengthSeekBar = findViewById<android.widget.SeekBar>(R.id.seek_bar_blur_strength)

        blurCanvasView.onSelectionChangedListener = { hasSelection ->
            btnDeleteBox.visibility = if (hasSelection) View.VISIBLE else View.GONE
            strengthLayout.visibility = if (hasSelection) View.VISIBLE else View.GONE
            if (hasSelection && blurCanvasView.selectedIndex in blurCanvasView.blurStrengths.indices) {
                val currentStrength = blurCanvasView.blurStrengths[blurCanvasView.selectedIndex]
                strengthSeekBar.progress = currentStrength
                strengthLabel.text = "Blur Strength: $currentStrength"
            }
        }

        blurCanvasView.onStrengthChangedListener = { strength ->
            strengthSeekBar.progress = strength
            strengthLabel.text = "Blur Strength: $strength"
        }

        strengthSeekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val p = progress.coerceIn(1, 25)
                strengthLabel.text = "Blur Strength: $p"
                blurCanvasView.updateSelectedStrength(p)
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })

        btnAddBox.setOnClickListener {
            blurCanvasView.addBlurBox()
        }

        btnDeleteBox.setOnClickListener {
            blurCanvasView.deleteSelectedBox()
        }

        btnClearAll.setOnClickListener {
            blurCanvasView.clearAll()
        }

        btnCancel.setOnClickListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }

        btnSave.setOnClickListener {
            saveBlurRects()
            setResult(Activity.RESULT_OK)
            finish()
        }

        // Fetch image using Glide
        loadImage()
    }

    private fun loadImage() {
        loadingSpinner.visibility = View.VISIBLE
        Glide.with(this)
            .asBitmap()
            .load(imageUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    loadingSpinner.visibility = View.GONE
                    blurCanvasView.setBitmap(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    loadingSpinner.visibility = View.GONE
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    loadingSpinner.visibility = View.GONE
                    Toast.makeText(this@BlurActivity, "Failed to load image for blur", Toast.LENGTH_SHORT).show()
                    finish()
                }
            })
    }

    private fun restoreBlurRects() {
        val prefs = getSharedPreferences(WidgetConfigurationActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val rectsStr = prefs.getString(PREF_BLUR_RECTS_KEY + appWidgetId, "") ?: ""
        if (rectsStr.isNotEmpty()) {
            try {
                val boxes = rectsStr.split(";").filter { it.isNotEmpty() }
                for (box in boxes) {
                    val pts = box.split(",").map { it.toFloat() }
                    if (pts.size >= 4) {
                        blurCanvasView.blurRects.add(RectF(pts[0], pts[1], pts[2], pts[3]))
                        val strength = if (pts.size >= 5) pts[4].toInt() else 10
                        blurCanvasView.blurStrengths.add(strength)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveBlurRects() {
        val prefs = getSharedPreferences(WidgetConfigurationActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val sb = StringBuilder()
        for (i in blurCanvasView.blurRects.indices) {
            val rect = blurCanvasView.blurRects[i]
            val strength = if (i in blurCanvasView.blurStrengths.indices) blurCanvasView.blurStrengths[i] else 10
            sb.append(rect.left).append(",")
              .append(rect.top).append(",")
              .append(rect.right).append(",")
              .append(rect.bottom).append(",")
              .append(strength).append(";")
        }
        prefs.edit().putString(PREF_BLUR_RECTS_KEY + appWidgetId, sb.toString()).apply()
    }

    companion object {
        const val PREF_BLUR_RECTS_KEY = "appwidget_blur_rects_"
    }
}
