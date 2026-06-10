package com.example.urlimagewidget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.work.*
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

class WidgetConfigurationActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var urlEditText: EditText
    private lateinit var intervalEditText: EditText
    private lateinit var frameSpinner: Spinner
    private lateinit var fitSpinner: Spinner
    private lateinit var thicknessLayout: LinearLayout
    private lateinit var thicknessLabel: TextView
    private lateinit var thicknessSeekBar: SeekBar
    private lateinit var customColorLayout: LinearLayout
    private lateinit var colorEditText: EditText
    private lateinit var blurElementsButton: Button

    private lateinit var previewFrame: FrameLayout
    private lateinit var previewImage: ImageView
    private lateinit var previewLoading: LinearLayout
    private lateinit var previewFullImage: ImageView
    private lateinit var cropOverlay: CropOverlayView

    // Crop dragging states
    private var imageWidth = 0
    private var imageHeight = 0
    private var isWideImage = false
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var cropOffsetX = 0.5f
    private var cropOffsetY = 0.5f
    private var detectedSize = "3x2"

    private var loadedBitmap: android.graphics.Bitmap? = null
    private var basePreviewWidth = 0
    private var basePreviewHeight = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply Material You dynamic colors
        com.google.android.material.color.DynamicColors.applyToActivityIfAvailable(this)
        
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)

        // Force status bar and navigation bar to be dark matching activity background
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

        setContentView(R.layout.activity_configuration)

        urlEditText = findViewById(R.id.edit_text_url)
        intervalEditText = findViewById(R.id.edit_text_interval)
        frameSpinner = findViewById(R.id.spinner_frame)
        fitSpinner = findViewById(R.id.spinner_fit)
        thicknessLayout = findViewById(R.id.layout_frame_thickness)
        thicknessLabel = findViewById(R.id.text_thickness_label)
        thicknessSeekBar = findViewById(R.id.seek_bar_thickness)
        customColorLayout = findViewById(R.id.layout_custom_color)
        colorEditText = findViewById(R.id.edit_text_color)
        blurElementsButton = findViewById(R.id.button_blur_elements)

        previewFrame = findViewById(R.id.preview_widget_frame)
        previewImage = findViewById(R.id.preview_widget_image)
        previewLoading = findViewById(R.id.preview_loading_container)
        previewFullImage = findViewById(R.id.preview_full_image)
        cropOverlay = findViewById(R.id.crop_overlay)

        blurElementsButton.setOnClickListener {
            val url = urlEditText.text.toString().trim()
            if (url.isNotEmpty()) {
                val intent = Intent(this, BlurActivity::class.java).apply {
                    putExtra("appWidgetId", appWidgetId)
                    putExtra("imageUrl", url)
                }
                startActivity(intent)
            }
        }

        val saveButton: Button = findViewById(R.id.button_save)

        // Populate Frame Spinner
        val frameOptions = arrayOf("Off", "Dark", "Light", "Material", "Custom")
        val frameAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, frameOptions)
        frameAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        frameSpinner.adapter = frameAdapter

        // Populate Fit Spinner
        val fitOptions = arrayOf("Stretch", "Crop", "Fill")
        val fitAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fitOptions)
        fitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        fitSpinner.adapter = fitAdapter

        // Find the widget id from the intent
        appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Detect size dynamically from home screen widget configuration options
        val widgetSizeResult = getWidgetSizeResult()
        val size = Pair(widgetSizeResult.width, widgetSizeResult.height)
        detectedSize = "${widgetSizeResult.cols}x${widgetSizeResult.rows}"

        // Calculate aspect ratio using raw launcher Dp options for exact home screen proportions
        val aspect = if (size.first > 0f && size.second > 0f) size.first / size.second else 1.5f

        val density = resources.displayMetrics.density
        val maxPx = (260 * density).toInt()

        if (aspect >= 1.0f) {
            basePreviewWidth = maxPx
            basePreviewHeight = (maxPx / aspect).toInt()
        } else {
            basePreviewHeight = maxPx
            basePreviewWidth = (maxPx * aspect).toInt()
        }

        val frameParams = previewFrame.layoutParams
        frameParams.width = basePreviewWidth
        frameParams.height = basePreviewHeight
        previewFrame.layoutParams = frameParams

        // Prepopulate fields if configuring an existing widget
        val existingUrl = prefs.getString(PREF_PREFIX_KEY + appWidgetId, "")
        val existingInterval = prefs.getInt(PREF_INTERVAL_KEY + appWidgetId, 60)
        val existingFrame = prefs.getString(PREF_FRAME_KEY + appWidgetId, "Off")
        val existingFit = prefs.getString(PREF_FIT_KEY + appWidgetId, "Crop") // Default to Crop
        val existingColor = prefs.getString(PREF_COLOR_KEY + appWidgetId, "#FF5722")
        val existingPadding = prefs.getInt(PREF_PADDING_KEY + appWidgetId, 8)
        cropOffsetX = prefs.getFloat(PREF_CROP_X_KEY + appWidgetId, 0.5f)
        cropOffsetY = prefs.getFloat(PREF_CROP_Y_KEY + appWidgetId, 0.5f)

        urlEditText.setText(existingUrl)
        intervalEditText.setText(existingInterval.toString())
        colorEditText.setText(existingColor)
        thicknessSeekBar.progress = existingPadding
        thicknessLabel.text = "Frame Thickness: ${existingPadding}dp"

        val colorPickerTrigger = findViewById<View>(R.id.view_color_picker_trigger)
        try {
            colorPickerTrigger.background.setTint(Color.parseColor(existingColor))
        } catch (e: Exception) {}

        val pickerClickListener = View.OnClickListener {
            showColorPickerDialog()
        }
        colorEditText.setOnClickListener(pickerClickListener)
        colorPickerTrigger.setOnClickListener(pickerClickListener)

        val framePos = frameOptions.indexOf(existingFrame)
        if (framePos >= 0) frameSpinner.setSelection(framePos)

        val fitPos = fitOptions.indexOf(existingFit)
        if (fitPos >= 0) fitSpinner.setSelection(fitPos)

        // Set up real-time preview updating listeners
        setupPreviewListeners()
        setupDragCropListener()

        saveButton.setOnClickListener {
            val context = this@WidgetConfigurationActivity
            val urlString = urlEditText.text.toString().trim()
            val intervalString = intervalEditText.text.toString().trim()
            val intervalMinutes = intervalString.toIntOrNull() ?: 60
            val selectedFrame = frameSpinner.selectedItem.toString()
            val selectedFit = fitSpinner.selectedItem.toString()
            val customColor = colorEditText.text.toString().trim()
            val framePadding = thicknessSeekBar.progress

            if (urlString.isNotEmpty()) {
                val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                val materialColor = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (isDark) getColor(android.R.color.system_accent1_800) else getColor(android.R.color.system_accent1_700)
                } else {
                    val typedValue = TypedValue()
                    if (theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)) {
                        typedValue.data
                    } else {
                        Color.parseColor("#FF5722")
                    }
                }

                val finalResult = getWidgetSizeResult()
                // Save settings to SharedPreferences
                prefs.edit().apply {
                    putString(PREF_PREFIX_KEY + appWidgetId, urlString)
                    putInt(PREF_INTERVAL_KEY + appWidgetId, intervalMinutes)
                    putString(PREF_FRAME_KEY + appWidgetId, selectedFrame)
                    putString(PREF_FIT_KEY + appWidgetId, selectedFit)
                    putString(PREF_GRID_SIZE_KEY + appWidgetId, detectedSize)
                    putInt(PREF_MATERIAL_COLOR_KEY + appWidgetId, materialColor)
                    putString(PREF_COLOR_KEY + appWidgetId, customColor)
                    putInt(PREF_PADDING_KEY + appWidgetId, framePadding)
                    putFloat(PREF_CROP_X_KEY + appWidgetId, cropOffsetX)
                    putFloat(PREF_CROP_Y_KEY + appWidgetId, cropOffsetY)
                    putFloat(PREF_WIDTH_DP_KEY + appWidgetId, finalResult.width)
                    putFloat(PREF_HEIGHT_DP_KEY + appWidgetId, finalResult.height)
                    putInt(PREF_COLS_KEY + appWidgetId, finalResult.cols)
                    putInt(PREF_ROWS_KEY + appWidgetId, finalResult.rows)
                    putBoolean(PREF_IS_LANDSCAPE_KEY + appWidgetId, resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels)
                    apply()
                }

                // Schedule periodic sync
                ImageWidgetProvider.schedulePeriodicUpdate(context, appWidgetId, urlString, intervalMinutes)

                // Trigger immediate sync
                ImageWidgetProvider.triggerWidgetUpdate(context, appWidgetId, urlString)

                // Return RESULT_OK
                val resultValue = Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                setResult(Activity.RESULT_OK, resultValue)
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateLivePreview()
    }

    private fun setupPreviewListeners() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateLivePreview()
            }
        }

        urlEditText.addTextChangedListener(watcher)
        colorEditText.addTextChangedListener(watcher)

        frameSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = frameSpinner.selectedItem.toString()
                if (selected == "Custom") {
                    customColorLayout.visibility = View.VISIBLE
                } else {
                    customColorLayout.visibility = View.GONE
                }
                if (selected == "Off") {
                    thicknessLayout.visibility = View.GONE
                } else {
                    thicknessLayout.visibility = View.VISIBLE
                }
                updateLivePreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        fitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateLivePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }



        thicknessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val p = if (progress < 2) 2 else progress
                if (p != progress) {
                    seekBar?.progress = p
                    return
                }
                thicknessLabel.text = "Frame Thickness: ${p}dp"
                updateLivePreview()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Initial preview update
        updateLivePreview()
    }

    private fun setupDragCropListener() {
        val touchListener = View.OnTouchListener { _, event ->
            val fitStyle = fitSpinner.selectedItem?.toString() ?: "Stretch"
            if (fitStyle != "Crop") return@OnTouchListener false

            val imageBounds = getImageBounds(previewFullImage)
            if (imageBounds.isEmpty) return@OnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (imageBounds.contains(event.x, event.y)) {
                        lastTouchX = event.x
                        lastTouchY = event.y
                        // Disallow parent ScrollView from intercepting our vertical drag gestures
                        cropOverlay.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    cropOverlay.parent?.requestDisallowInterceptTouchEvent(true)
                    val deltaX = event.x - lastTouchX
                    val deltaY = event.y - lastTouchY

                    val rect = cropOverlay.cropRect
                    val w = rect.width()
                    val h = rect.height()

                    var newLeft = rect.left + deltaX
                    var newTop = rect.top + deltaY

                    if (newLeft < imageBounds.left) newLeft = imageBounds.left
                    if (newTop < imageBounds.top) newTop = imageBounds.top
                    if (newLeft + w > imageBounds.right) newLeft = imageBounds.right - w
                    if (newTop + h > imageBounds.bottom) newTop = imageBounds.bottom - h

                    rect.set(newLeft, newTop, newLeft + w, newTop + h)
                    cropOverlay.invalidate()

                    val scrollRangeX = imageBounds.width() - w
                    val scrollRangeY = imageBounds.height() - h

                    cropOffsetX = if (scrollRangeX > 0) (newLeft - imageBounds.left) / scrollRangeX else 0.5f
                    cropOffsetY = if (scrollRangeY > 0) (newTop - imageBounds.top) / scrollRangeY else 0.5f

                    updateCroppedPreview()

                    lastTouchX = event.x
                    lastTouchY = event.y
                    true
                }
                else -> false
            }
        }
        cropOverlay.setOnTouchListener(touchListener)
    }

    private fun getImageBounds(imageView: ImageView): RectF {
        val drawable = imageView.drawable ?: return RectF()
        val imageWidth = drawable.intrinsicWidth
        val imageHeight = drawable.intrinsicHeight

        val viewWidth = imageView.width
        val viewHeight = imageView.height

        if (imageWidth <= 0 || imageHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            return RectF()
        }

        val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
        val imgRatio = imageWidth.toFloat() / imageHeight.toFloat()

        val drawWidth: Float
        val drawHeight: Float
        if (imgRatio > viewRatio) {
            drawWidth = viewWidth.toFloat()
            drawHeight = viewWidth.toFloat() / imgRatio
        } else {
            drawWidth = viewHeight.toFloat() * imgRatio
            drawHeight = viewHeight.toFloat()
        }

        val left = (viewWidth - drawWidth) / 2
        val top = (viewHeight - drawHeight) / 2
        return RectF(left, top, left + drawWidth, top + drawHeight)
    }

    private fun updateCropBoxPosition(isInitial: Boolean = false) {
        val imageBounds = getImageBounds(previewFullImage)
        if (imageBounds.isEmpty) return

        val density = resources.displayMetrics.density
        val frameStyle = frameSpinner.selectedItem?.toString() ?: "Off"
        val paddingDp = if (frameStyle == "Off") 0 else thicknessSeekBar.progress
        val paddingPx = (paddingDp * density).toInt()

        val innerW = basePreviewWidth - 2 * paddingPx
        val innerH = basePreviewHeight - 2 * paddingPx
        val aspect = if (innerW > 0 && innerH > 0) innerW.toFloat() / innerH.toFloat() else (basePreviewWidth.toFloat() / basePreviewHeight.toFloat())

        val imgRatio = imageBounds.width() / imageBounds.height()

        val cropWidth: Float
        val cropHeight: Float

        if (imgRatio > aspect) {
            cropHeight = imageBounds.height()
            cropWidth = cropHeight * aspect
            isWideImage = true
        } else {
            cropWidth = imageBounds.width()
            cropHeight = cropWidth / aspect
            isWideImage = false
        }

        val innerRadiusDp = if (frameStyle == "Off") 16 else {
            val r = 16 - paddingDp
            if (r < 2) 2 else r
        }
        cropOverlay.cornerRadiusPx = innerRadiusDp * density

        if (isInitial) {
            val scrollRangeX = imageBounds.width() - cropWidth
            val scrollRangeY = imageBounds.height() - cropHeight

            val left = imageBounds.left + if (scrollRangeX > 0) scrollRangeX * cropOffsetX else 0f
            val top = imageBounds.top + if (scrollRangeY > 0) scrollRangeY * cropOffsetY else 0f

            cropOverlay.cropRect.set(left, top, left + cropWidth, top + cropHeight)
        } else {
            val currentLeft = cropOverlay.cropRect.left
            val currentTop = cropOverlay.cropRect.top

            var newLeft = currentLeft
            var newTop = currentTop

            if (newLeft < imageBounds.left) newLeft = imageBounds.left
            if (newTop < imageBounds.top) newTop = imageBounds.top
            if (newLeft + cropWidth > imageBounds.right) newLeft = imageBounds.right - cropWidth
            if (newTop + cropHeight > imageBounds.bottom) newTop = imageBounds.bottom - cropHeight

            cropOverlay.cropRect.set(newLeft, newTop, newLeft + cropWidth, newTop + cropHeight)
        }
        
        android.util.Log.d("WidgetConfig", "updateCropBoxPosition: imageBounds=$imageBounds, aspect=$aspect, cropWidth=$cropWidth, cropHeight=$cropHeight, rect=${cropOverlay.cropRect}, offsets=$cropOffsetX, $cropOffsetY")
        cropOverlay.invalidate()
    }

    private fun dpToCells(dp: Int): Int {
        if (dp < 100) return 1
        if (dp < 180) return 2
        if (dp < 260) return 3
        if (dp < 340) return 4
        return ((dp + 30) / 70).coerceAtLeast(5)
    }

    private fun updateCroppedPreview() {
        val bmp = loadedBitmap ?: return
        val parts = detectedSize.split("x")
        val colsVal = parts[0].toIntOrNull() ?: 3
        val rowsVal = parts[1].toIntOrNull() ?: 2
        
        val density = resources.displayMetrics.density
        val frameStyle = frameSpinner.selectedItem?.toString() ?: "Off"
        val paddingDp = if (frameStyle == "Off") 0 else thicknessSeekBar.progress
        val paddingPx = (paddingDp * density).toInt()

        val innerW = basePreviewWidth - 2 * paddingPx
        val innerH = basePreviewHeight - 2 * paddingPx
        val targetRatio = if (innerW > 0 && innerH > 0) innerW.toFloat() / innerH.toFloat() else (colsVal.toFloat() / rowsVal.toFloat())

        val width = bmp.width
        val height = bmp.height
        val srcRatio = width.toFloat() / height.toFloat()
        
        var cropWidth = width
        var cropHeight = height
        var cropX = 0
        var cropY = 0

        if (srcRatio > targetRatio) {
            cropWidth = (height * targetRatio).toInt()
            cropX = ((width - cropWidth) * cropOffsetX).toInt()
        } else {
            cropHeight = (width / targetRatio).toInt()
            cropY = ((height - cropHeight) * cropOffsetY).toInt()
        }

        var finalCropX = cropX
        var finalCropY = cropY
        if (finalCropX < 0) finalCropX = 0
        if (finalCropY < 0) finalCropY = 0
        if (finalCropX + cropWidth > width) finalCropX = width - cropWidth
        if (finalCropY + cropHeight > height) finalCropY = height - cropHeight
        
        try {
            val cropped = android.graphics.Bitmap.createBitmap(bmp, finalCropX, finalCropY, cropWidth, cropHeight)
            android.util.Log.d("WidgetConfig", "Cropping bitmap: bmp=${bmp.width}x${bmp.height}, cropX=$finalCropX, cropY=$finalCropY, cropW=$cropWidth, cropH=$cropHeight, targetRatio=$targetRatio, offsets=$cropOffsetX, $cropOffsetY")
            previewImage.setImageBitmap(cropped)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateLivePreview() {
        val url = urlEditText.text.toString().trim()
        val frameStyle = frameSpinner.selectedItem?.toString() ?: "Off"
        val fitStyle = fitSpinner.selectedItem?.toString() ?: "Stretch"
        val customColorStr = colorEditText.text.toString().trim()

        updatePreviewDimensions()

        val density = resources.displayMetrics.density
        val paddingDp = if (frameStyle == "Off") 0 else thicknessSeekBar.progress
        val paddingPx = (paddingDp * density).toInt()
        previewFrame.setPadding(paddingPx, paddingPx, paddingPx, paddingPx)

        // Apply background tint to FrameLayout template
        val background = previewFrame.background as? GradientDrawable
        if (background != null) {
            when (frameStyle) {
                "Off" -> background.setColor(Color.TRANSPARENT)
                "Dark" -> background.setColor(Color.parseColor("#2C2C2C"))
                "Light" -> background.setColor(Color.parseColor("#F5F5F5"))
                "Material" -> {
                    background.setColor(resolveMaterialColor())
                }
                "Custom" -> {
                    try {
                        background.setColor(Color.parseColor(customColorStr))
                    } catch (e: Exception) {
                        background.setColor(Color.parseColor("#2C2C2C"))
                    }
                }
            }
        }

        // Fetch image using Glide
        if (url.isNotEmpty()) {
            previewLoading.visibility = View.VISIBLE
            Glide.with(this)
                .load(url)
                .into(object : com.bumptech.glide.request.target.CustomTarget<Drawable>() {
                    override fun onResourceReady(
                        resource: Drawable,
                        transition: com.bumptech.glide.request.transition.Transition<in Drawable>?
                    ) {
                        previewLoading.visibility = View.GONE
                        blurElementsButton.visibility = View.VISIBLE
                        val bitmap = (resource as? android.graphics.drawable.BitmapDrawable)?.bitmap
                        if (bitmap != null) {
                            val blurred = applyPreviewBlur(bitmap)
                            loadedBitmap = blurred
                            previewImage.setImageBitmap(blurred)
                            previewFullImage.setImageBitmap(blurred)
                        } else {
                            loadedBitmap = null
                            previewImage.setImageDrawable(resource)
                            previewFullImage.setImageDrawable(resource)
                        }
                        applyFitStyle(resource, fitStyle)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        previewImage.setImageDrawable(placeholder)
                        previewFullImage.setImageDrawable(placeholder)
                    }

                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        previewLoading.visibility = View.GONE
                        blurElementsButton.visibility = View.GONE
                    }
                })
        } else {
            blurElementsButton.visibility = View.GONE
            previewImage.setImageResource(R.drawable.placeholder_image)
            previewImage.scaleType = ImageView.ScaleType.FIT_XY
            
            val params = previewImage.layoutParams as FrameLayout.LayoutParams
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            previewImage.layoutParams = params
            previewImage.translationX = 0f
            previewImage.translationY = 0f

            val innerRadiusDp = if (frameStyle == "Off") 16 else {
                val r = 16 - paddingDp
                if (r < 2) 2 else r
            }
            previewImage.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, innerRadiusDp * density)
                }
            }
            previewImage.clipToOutline = true
            
            previewFrame.visibility = View.VISIBLE
            findViewById<View>(R.id.layout_crop_adjuster).visibility = View.GONE
            previewLoading.visibility = View.GONE
        }
    }

    private fun getWidgetSizeResult(): WidgetSizeResult {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existingUrl = prefs.getString(PREF_PREFIX_KEY + appWidgetId, "")
        val isInitialSetup = existingUrl.isNullOrEmpty()

        val appWidgetManager = AppWidgetManager.getInstance(this)
        val options = intent?.getBundleExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS)
            ?: appWidgetManager.getAppWidgetOptions(appWidgetId)
        
        val dynamicResult = WidgetSizeResolver.resolveWidgetSize(options, this, isInitialSetup)
        
        if (dynamicResult.width > 0f && dynamicResult.height > 0f) {
            android.util.Log.d("WidgetConfig", "getWidgetSizeResult: Using dynamically resolved size: $dynamicResult")
            return dynamicResult
        }
        
        val savedWidth = prefs.getFloat(PREF_WIDTH_DP_KEY + appWidgetId, -1f)
        val savedHeight = prefs.getFloat(PREF_HEIGHT_DP_KEY + appWidgetId, -1f)
        val savedCols = prefs.getInt(PREF_COLS_KEY + appWidgetId, -1)
        val savedRows = prefs.getInt(PREF_ROWS_KEY + appWidgetId, -1)
        
        val metrics = resources.displayMetrics
        val isCurrentLandscape = metrics.widthPixels > metrics.heightPixels
        val savedIsLandscape = prefs.getBoolean(PREF_IS_LANDSCAPE_KEY + appWidgetId, false)
        val hasSavedOrientation = prefs.contains(PREF_IS_LANDSCAPE_KEY + appWidgetId)

        if (savedWidth > 0f && savedHeight > 0f && savedCols > 0 && savedRows > 0) {
            if (!hasSavedOrientation || savedIsLandscape == isCurrentLandscape) {
                val cachedAspect = savedWidth / savedHeight
                val dynamicAspect = dynamicResult.width / dynamicResult.height
                if (Math.abs(cachedAspect - dynamicAspect) < 0.05f) {
                    android.util.Log.d("WidgetConfig", "getWidgetSizeResult: Using cached size: $savedWidth x $savedHeight, ${savedCols}x${savedRows}")
                    return WidgetSizeResult(savedWidth, savedHeight, savedCols, savedRows)
                } else {
                    android.util.Log.d("WidgetConfig", "getWidgetSizeResult: Cached aspect ($cachedAspect) differs from dynamic aspect ($dynamicAspect). Ignoring cache.")
                }
            } else {
                android.util.Log.d("WidgetConfig", "getWidgetSizeResult: Saved orientation ($savedIsLandscape) differs from current screen ($isCurrentLandscape). Ignoring cache.")
            }
        }
        
        return dynamicResult
    }

    private fun getWidgetSizeDp(): Pair<Float, Float> {
        val result = getWidgetSizeResult()
        return Pair(result.width, result.height)
    }

    private fun applyFitStyle(drawable: Drawable, fitStyle: String) {
        val density = resources.displayMetrics.density
        val frameStyle = frameSpinner.selectedItem?.toString() ?: "Off"
        val paddingDp = if (frameStyle == "Off") 0 else thicknessSeekBar.progress
        val paddingPx = (paddingDp * density).toInt()

        // Toggle crop adjuster layout based on fit style
        val cropAdjusterLayout = findViewById<View>(R.id.layout_crop_adjuster)
        if (fitStyle == "Crop") {
            cropAdjusterLayout.visibility = View.VISIBLE
            previewImage.scaleType = ImageView.ScaleType.FIT_XY
            if (previewFullImage.width > 0 && previewFullImage.height > 0) {
                updateCropBoxPosition(true)
                updateCroppedPreview()
            } else {
                previewFullImage.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View?, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                    ) {
                        previewFullImage.removeOnLayoutChangeListener(this)
                        updateCropBoxPosition(true)
                        updateCroppedPreview()
                    }
                })
            }
        } else {
            cropAdjusterLayout.visibility = View.GONE
            previewImage.scaleType = ImageView.ScaleType.FIT_XY
        }

        val maxFrameWidth = basePreviewWidth
        val maxFrameHeight = basePreviewHeight
        val containerWidth = maxFrameWidth - 2 * paddingPx
        val containerHeight = maxFrameHeight - 2 * paddingPx

        if (containerWidth <= 0 || containerHeight <= 0) return

        val params = previewImage.layoutParams as FrameLayout.LayoutParams

        when (fitStyle) {
            "Stretch" -> {
                previewImage.scaleType = ImageView.ScaleType.FIT_XY
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                previewImage.translationX = 0f
                previewImage.translationY = 0f
            }
            "Fill" -> {
                val imgWidth = drawable.intrinsicWidth
                val imgHeight = drawable.intrinsicHeight
                if (imgWidth > 0 && imgHeight > 0) {
                    val imgRatio = imgWidth.toFloat() / imgHeight.toFloat()
                    val containerRatio = containerWidth.toFloat() / containerHeight.toFloat()

                    val scaledImageWidth: Int
                    val scaledImageHeight: Int

                    if (imgRatio > containerRatio) {
                        scaledImageWidth = containerWidth
                        scaledImageHeight = (containerWidth / imgRatio).toInt()
                    } else {
                        scaledImageWidth = (containerHeight * imgRatio).toInt()
                        scaledImageHeight = containerHeight
                    }

                    // Shrink the preview frame to wrap the image exactly with margins
                    val newFrameWidth = scaledImageWidth + 2 * paddingPx
                    val newFrameHeight = scaledImageHeight + 2 * paddingPx

                    val fParams = previewFrame.layoutParams
                    fParams.width = newFrameWidth
                    fParams.height = newFrameHeight
                    previewFrame.layoutParams = fParams
                }

                previewImage.scaleType = ImageView.ScaleType.FIT_XY
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                previewImage.translationX = 0f
                previewImage.translationY = 0f
            }
            "Crop" -> {
                params.width = FrameLayout.LayoutParams.MATCH_PARENT
                params.height = FrameLayout.LayoutParams.MATCH_PARENT
                previewImage.translationX = 0f
                previewImage.translationY = 0f
            }
        }
        previewImage.layoutParams = params

        val innerRadiusDp = if (frameStyle == "Off") 16 else {
            val r = 16 - paddingDp
            if (r < 2) 2 else r
        }
        previewImage.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, innerRadiusDp * density)
            }
        }
        previewImage.clipToOutline = true
    }

    private fun updatePreviewDimensions() {
        val size = getWidgetSizeDp()
        val widthDp = size.first
        val heightDp = size.second
        
        val frameStyle = frameSpinner.selectedItem?.toString() ?: "Off"
        val paddingDp = if (frameStyle == "Off") 0 else thicknessSeekBar.progress
        
        val innerW = widthDp - 2 * paddingDp
        val innerH = heightDp - 2 * paddingDp
        
        val innerAspect = if (innerW > 0f && innerH > 0f) innerW / innerH else (widthDp / heightDp)
        
        val density = resources.displayMetrics.density
        val maxInnerPx = (240 * density).toInt()
        
        val innerPreviewW: Int
        val innerPreviewH: Int
        if (innerAspect >= 1.0f) {
            innerPreviewW = maxInnerPx
            innerPreviewH = (maxInnerPx / innerAspect).toInt()
        } else {
            innerPreviewH = maxInnerPx
            innerPreviewW = (maxInnerPx * innerAspect).toInt()
        }
        
        val paddingPx = (paddingDp * density).toInt()
        basePreviewWidth = innerPreviewW + 2 * paddingPx
        basePreviewHeight = innerPreviewH + 2 * paddingPx
        
        val frameParams = previewFrame.layoutParams
        frameParams.width = basePreviewWidth
        frameParams.height = basePreviewHeight
        previewFrame.layoutParams = frameParams
    }

    private fun resolveMaterialColor(): Int {
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (isDark) getColor(android.R.color.system_accent1_800) else getColor(android.R.color.system_accent1_700)
        } else {
            val typedValue = TypedValue()
            if (theme.resolveAttribute(com.google.android.material.R.attr.colorPrimary, typedValue, true)) {
                typedValue.data
            } else {
                Color.parseColor("#FF5722")
            }
        }
    }

    private fun showColorPickerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val preview = dialogView.findViewById<View>(R.id.dialog_color_preview)
        val colorWheel = dialogView.findViewById<ColorWheelView>(R.id.color_wheel_view)
        val brightnessSeekBar = dialogView.findViewById<SeekBar>(R.id.seek_bar_brightness)
        val alphaSeekBar = dialogView.findViewById<SeekBar>(R.id.seek_bar_alpha)
        val hexText = dialogView.findViewById<TextView>(R.id.text_color_hex)

        val currentColorStr = colorEditText.text.toString().trim()
        var currentColor = Color.parseColor("#FF5722")
        try {
            currentColor = Color.parseColor(currentColorStr)
        } catch (e: Exception) {}

        colorWheel.setColor(currentColor)
        brightnessSeekBar.progress = (colorWheel.brightness * 100).toInt()
        alphaSeekBar.progress = colorWheel.alphaVal

        preview.setBackgroundColor(currentColor)
        hexText.text = String.format("#%08X", currentColor)

        colorWheel.onColorChangedListener = { color ->
            preview.setBackgroundColor(color)
            hexText.text = String.format("#%08X", color)
        }

        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                colorWheel.brightness = progress / 100f
                colorWheel.triggerColorUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        alphaSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                colorWheel.alphaVal = progress
                colorWheel.triggerColorUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        androidx.appcompat.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pick Custom Color")
            .setView(dialogView)
            .setPositiveButton("OK") { _, _ ->
                val finalHex = hexText.text.toString()
                colorEditText.setText(finalHex)
                findViewById<View>(R.id.view_color_picker_trigger)?.background?.setTint(Color.parseColor(finalHex))
                updateLivePreview()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_BLUR && resultCode == Activity.RESULT_OK) {
            updateLivePreview()
        }
    }

    private fun applyPreviewBlur(srcBmp: android.graphics.Bitmap): android.graphics.Bitmap {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rectsStr = prefs.getString("appwidget_blur_rects_$appWidgetId", "") ?: ""
        if (rectsStr.isEmpty()) return srcBmp

        val outBmp = srcBmp.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        try {
            val w = outBmp.width
            val h = outBmp.height
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
                    
                    // Convert cropExpansion into pixel dimensions relative to preview image width/height
                    val cropW = cropExpansion.toInt()
                    val cropH = cropExpansion.toInt()

                    val leftPx = (left - cropW).coerceIn(0, w - 1)
                    val topPx = (top - cropH).coerceIn(0, h - 1)
                    val rightPx = (right + cropW).coerceIn(0, w - 1)
                    val bottomPx = (bottom + cropH).coerceIn(0, h - 1)

                    val rectWidth = rightPx - leftPx + 1
                    val rectHeight = bottomPx - topPx + 1
                    if (rectWidth > 0 && rectHeight > 0) {
                        val region = android.graphics.Bitmap.createBitmap(outBmp, leftPx, topPx, rectWidth, rectHeight)
                        val blurredRegion = blurRegion(region, strength)
                        
                        val canvas = android.graphics.Canvas(outBmp)
                        val shader = android.graphics.BitmapShader(blurredRegion, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                        
                        // Adjust shader matrix to match expanded pixel coordinates
                        val matrix = android.graphics.Matrix()
                        val srcBounds = android.graphics.RectF(0f, 0f, blurredRegion.width.toFloat(), blurredRegion.height.toFloat())
                        val targetBounds = android.graphics.RectF(leftPx.toFloat(), topPx.toFloat(), rightPx.toFloat(), bottomPx.toFloat())
                        matrix.setRectToRect(srcBounds, targetBounds, android.graphics.Matrix.ScaleToFit.FILL)
                        shader.setLocalMatrix(matrix)

                        // 1. Draw solid interior rectangle (guarantees core stays 100% blurred)
                        val solidPaint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            setShader(shader)
                            style = android.graphics.Paint.Style.FILL
                        }
                        canvas.drawRect(
                            left.toFloat(), 
                            top.toFloat(), 
                            right.toFloat(), 
                            bottom.toFloat(), 
                            solidPaint
                        )

                        // 2. Draw outer feathered area (seamless transition with absolutely zero halo color bleeding)
                        val maskPaint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            setShader(shader)
                            style = android.graphics.Paint.Style.FILL
                            maskFilter = android.graphics.BlurMaskFilter(featherRadius, android.graphics.BlurMaskFilter.Blur.NORMAL)
                        }
                        val maskRect = android.graphics.RectF(
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
        return outBmp
    }

    private fun blurRegion(bitmap: android.graphics.Bitmap, radius: Int): android.graphics.Bitmap {
        val scale = 0.15f
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)

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
        val result = android.graphics.Bitmap.createScaledBitmap(small, bitmap.width, bitmap.height, true)
        small.recycle()
        return result
    }

    companion object {
        private const val REQUEST_CODE_BLUR = 1001
        const val PREFS_NAME = "com.example.urlimagewidget.ImageWidgetProvider"
        const val PREF_PREFIX_KEY = "appwidget_url_"
        const val PREF_INTERVAL_KEY = "appwidget_interval_"
        const val PREF_FRAME_KEY = "appwidget_frame_"
        const val PREF_COLOR_KEY = "appwidget_color_"
        const val PREF_PADDING_KEY = "appwidget_padding_"
        const val PREF_FIT_KEY = "appwidget_fit_"
        const val PREF_CROP_X_KEY = "appwidget_crop_x_"
        const val PREF_CROP_Y_KEY = "appwidget_crop_y_"
        const val PREF_GRID_SIZE_KEY = "appwidget_grid_size_"
        const val PREF_MATERIAL_COLOR_KEY = "appwidget_material_color_"
        const val PREF_WIDTH_DP_KEY = "appwidget_width_dp_"
        const val PREF_HEIGHT_DP_KEY = "appwidget_height_dp_"
        const val PREF_COLS_KEY = "appwidget_cols_"
        const val PREF_ROWS_KEY = "appwidget_rows_"
        const val PREF_IS_LANDSCAPE_KEY = "appwidget_is_landscape_"
    }
}
