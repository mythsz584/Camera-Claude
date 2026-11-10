package com.fotoia.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private enum class Mode { PORTRAIT, PHOTO, VIDEO }

    // Tela da camera
    private lateinit var cameraLayer: View
    private lateinit var previewView: PreviewView
    private lateinit var gridOverlay: View
    private lateinit var btnFlash: ImageButton
    private lateinit var btnAi: ImageButton
    private lateinit var btnQuality: ImageButton
    private lateinit var btnNight: ImageButton
    private lateinit var btnMore: ImageButton
    private lateinit var btnZoomA: TextView
    private lateinit var btnZoomB: TextView
    private lateinit var zoomBar: SliderBar
    private lateinit var zoomText: TextView
    private lateinit var exposureBar: SliderBar
    private lateinit var exposureText: TextView
    private lateinit var previewContainer: View
    private lateinit var focusRing: View
    private lateinit var btnGallery: ImageView
    private lateinit var btnShutter: View
    private lateinit var btnFlip: ImageButton
    private lateinit var recText: TextView
    private lateinit var countdownText: TextView
    private lateinit var tabPortrait: TextView
    private lateinit var tabPhoto: TextView
    private lateinit var tabVideo: TextView
    private lateinit var tabMore: TextView

    // Tela do resultado
    private lateinit var resultLayer: View
    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnSave: MaterialButton

    // Camera
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var extensionActive = false

    // Opcoes escolhidas pelo usuario
    private var mode = Mode.PHOTO
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var aiOn = true
    private var night = false
    private var gridOn = true
    private var timerSec = 0
    private var inputSide = 896
    private var enhanceLevel = 2   // 0 = natural, 1 = forte, 2 = extremo

    // Luz (barra da esquerda): posicao de -1 a +1 e o valor em EV que ela representa
    private var exposureFraction = 0f
    private var exposureEv = 0f
    private var exposureIndex = 0
    private var exposureHintShown = false

    // Toque para focar
    private var tapOk = false
    private var tapX = 0f
    private var tapY = 0f

    // Zoom total pedido (camera x digital) e a parte digital, feita por recorte
    private var totalZoom = 1f
    private var digitalZoom = 1f

    // Trabalho em andamento
    private var job: Job? = null
    private var countdownJob: Job? = null
    private var originalBmp: Bitmap? = null
    private var enhancedBmp: Bitmap? = null      // resolucao total, para salvar
    private var enhancedPreview: Bitmap? = null  // copia menor, para mostrar na tela
    private var capturing = false

    private val enhancer by lazy { Enhancer(applicationContext) }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera() else toast(R.string.camera_denied)
        }

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) processUri(uri)
        }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeResult()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cameraLayer = findViewById(R.id.cameraLayer)
        previewView = findViewById(R.id.previewView)
        gridOverlay = findViewById(R.id.gridOverlay)
        btnFlash = findViewById(R.id.btnFlash)
        btnAi = findViewById(R.id.btnAi)
        btnQuality = findViewById(R.id.btnQuality)
        btnNight = findViewById(R.id.btnNight)
        btnMore = findViewById(R.id.btnMore)
        btnZoomA = findViewById(R.id.btnZoomA)
        btnZoomB = findViewById(R.id.btnZoomB)
        zoomBar = findViewById(R.id.zoomBar)
        zoomText = findViewById(R.id.zoomText)
        exposureBar = findViewById(R.id.exposureBar)
        exposureText = findViewById(R.id.exposureText)
        previewContainer = findViewById(R.id.previewContainer)
        focusRing = findViewById(R.id.focusRing)
        btnGallery = findViewById(R.id.btnGallery)
        btnShutter = findViewById(R.id.btnShutter)
        btnFlip = findViewById(R.id.btnFlip)
        recText = findViewById(R.id.recText)
        countdownText = findViewById(R.id.countdownText)
        tabPortrait = findViewById(R.id.tabPortrait)
        tabPhoto = findViewById(R.id.tabPhoto)
        tabVideo = findViewById(R.id.tabVideo)
        tabMore = findViewById(R.id.tabMore)
        resultLayer = findViewById(R.id.resultLayer)
        imageView = findViewById(R.id.imageView)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        btnSave = findViewById(R.id.btnSave)

        // TextureView permite ampliar a imagem da camera para o zoom digital.
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        zoomBar.onValueChanged = { value ->
            totalZoom = value
            applyZoom()
        }
        zoomText.setOnClickListener {
            totalZoom = 1f
            applyZoom()
        }
        exposureBar.alignLeft = true
        exposureBar.logScale = false
        exposureBar.centerMark = true
        exposureBar.setRange(-1f, 1f)
        exposureBar.setValue(0f)
        exposureBar.onValueChanged = { value -> setExposure(value) }
        exposureText.setOnClickListener { resetExposure() }
        updateExposureLabel()

        val pinch = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    totalZoom *= detector.scaleFactor
                    applyZoom()
                    return true
                }
            }
        )
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        previewView.setOnTouchListener { _, event ->
            pinch.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapOk = true
                    tapX = event.x
                    tapY = event.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> tapOk = false
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - tapX) > slop || abs(event.y - tapY) > slop) tapOk = false
                }
                MotionEvent.ACTION_UP -> {
                    // Toque simples: foca e mede a luz naquele ponto.
                    if (tapOk && event.eventTime - event.downTime < 500) {
                        focusAt(event.x, event.y, event.rawX, event.rawY)
                    }
                    tapOk = false
                }
            }
            true
        }

        btnGallery.outlineProvider = ViewOutlineProvider.BACKGROUND
        btnGallery.clipToOutline = true

        btnFlash.setOnClickListener { cycleFlash() }
        btnAi.setOnClickListener {
            aiOn = !aiOn
            toast(if (aiOn) R.string.ai_on else R.string.ai_off)
            refreshUi()
        }
        btnQuality.setOnClickListener { showQualityMenu(it) }
        btnNight.setOnClickListener { toggleNight() }
        btnMore.setOnClickListener { showOptionsMenu(it) }
        btnZoomA.setOnClickListener { setZoom(first = true) }
        btnZoomB.setOnClickListener { setZoom(first = false) }
        btnGallery.setOnClickListener { openGallery() }
        btnShutter.setOnClickListener { onShutter() }
        btnFlip.setOnClickListener {
            if (recording != null) return@setOnClickListener
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            bindCamera()
        }
        tabPortrait.setOnClickListener { setMode(Mode.PORTRAIT) }
        tabPhoto.setOnClickListener { setMode(Mode.PHOTO) }
        tabVideo.setOnClickListener { setMode(Mode.VIDEO) }
        tabMore.setOnClickListener { showMoreMenu(it) }

        findViewById<View>(R.id.btnDiscard).setOnClickListener { closeResult() }
        btnSave.setOnClickListener { saveEnhanced() }

        // Segurar a foto mostra o "antes"; soltar mostra o "depois".
        imageView.setOnTouchListener { _, event ->
            val before = originalBmp
            val after = enhancedPreview
            if (before != null && after != null) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> imageView.setImageBitmap(before)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> imageView.setImageBitmap(after)
                }
            }
            true
        }

        onBackPressedDispatcher.addCallback(this, backCallback)
        refreshUi()

        if (hasPermission(Manifest.permission.CAMERA)) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onStart() {
        super.onStart()
        if (camera != null) {
            totalZoom = 1f
            resetExposure()
            applyZoom()
            previewView.postDelayed({
                applyZoom()
                applyExposureIndex()
            }, 700)
        }
    }

    override fun onStop() {
        super.onStop()
        recording?.stop()
        countdownJob?.cancel()
        countdownText.visibility = View.GONE
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val extFuture = ExtensionsManager.getInstanceAsync(applicationContext, provider)
                extFuture.addListener({
                    extensionsManager = try {
                        extFuture.get()
                    } catch (e: Exception) {
                        null
                    }
                    bindCamera()
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                toast(R.string.camera_error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Liga a camera de acordo com o modo, a lente e as opcoes atuais. */
    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val base = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        val wanted = when {
            mode == Mode.PORTRAIT -> ExtensionMode.BOKEH
            mode == Mode.PHOTO && night -> ExtensionMode.NIGHT
            else -> ExtensionMode.NONE
        }
        var selector = base
        extensionActive = false
        val manager = extensionsManager
        if (wanted != ExtensionMode.NONE && manager != null) {
            try {
                if (manager.isExtensionAvailable(base, wanted)) {
                    selector = manager.getExtensionEnabledCameraSelector(base, wanted)
                    extensionActive = true
                }
            } catch (e: Exception) {
                extensionActive = false
            }
        }

        try {
            provider.unbindAll()
            imageCapture = null
            videoCapture = null
            totalZoom = 1f
            exposureFraction = 0f
            exposureEv = 0f
            exposureIndex = 0
            exposureHintShown = false
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            val cam = if (mode == Mode.VIDEO) {
                val recorder = Recorder.Builder()
                    .setQualitySelector(
                        QualitySelector.from(
                            Quality.FHD,
                            FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
                        )
                    )
                    .build()
                val video = VideoCapture.withOutput(recorder)
                videoCapture = video
                provider.bindToLifecycle(this, selector, preview, video)
            } else {
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashMode)
                    .build()
                imageCapture = capture
                provider.bindToLifecycle(this, selector, preview, capture)
            }
            camera = cam
            if (mode == Mode.VIDEO) {
                cam.cameraControl.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
            }
        } catch (e: Exception) {
            camera = null
            imageCapture = null
            videoCapture = null
            toast(R.string.camera_error)
        }
        // Os limites de zoom podem chegar um instante depois de ligar a camera.
        exposureBar.setValue(0f)
        updateExposureLabel()
        applyZoom()
        previewView.postDelayed({ applyZoom() }, 600)
    }

    private fun setMode(newMode: Mode) {
        if (recording != null || newMode == mode) return
        countdownJob?.cancel()
        countdownText.visibility = View.GONE
        mode = newMode
        if (mode == Mode.VIDEO && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
        bindCamera()
        if (mode == Mode.PORTRAIT && !extensionActive && camera != null) {
            toast(R.string.portrait_unavailable)
        }
    }

    private fun toggleNight() {
        if (mode != Mode.PHOTO) return
        night = !night
        bindCamera()
        if (night && !extensionActive) {
            night = false
            toast(R.string.night_unavailable)
            refreshUi()
        } else {
            toast(if (night) R.string.night_on else R.string.night_off)
        }
    }

    private fun cycleFlash() {
        if (mode == Mode.VIDEO) {
            // No video o flash funciona como lanterna: ligado ou desligado.
            flashMode = if (flashMode == ImageCapture.FLASH_MODE_ON) {
                ImageCapture.FLASH_MODE_OFF
            } else {
                ImageCapture.FLASH_MODE_ON
            }
            camera?.cameraControl?.enableTorch(flashMode == ImageCapture.FLASH_MODE_ON)
        } else {
            flashMode = when (flashMode) {
                ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
                ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
                else -> ImageCapture.FLASH_MODE_OFF
            }
            imageCapture?.flashMode = flashMode
        }
        toast(
            when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.string.flash_on
                ImageCapture.FLASH_MODE_AUTO -> R.string.flash_auto
                else -> R.string.flash_off
            }
        )
        refreshUi()
    }

    /** Os dois niveis de zoom oferecidos: grande-angular + 1x quando existe, senao 1x + 2x. */
    private fun zoomLevels(): Pair<Float, Float> {
        val state = camera?.cameraInfo?.zoomState?.value
        val minRatio = state?.minZoomRatio ?: 1f
        val maxRatio = state?.maxZoomRatio ?: 1f
        return if (minRatio < 0.95f) {
            Pair(minRatio, 1f)
        } else {
            Pair(1f, min(2f, maxRatio))
        }
    }

    private fun setZoom(first: Boolean) {
        val levels = zoomLevels()
        totalZoom = if (first) levels.first else levels.second
        applyZoom()
    }

    /**
     * Aplica o zoom total: primeiro o zoom da propria camera, ate o limite dela;
     * o que passar disso e zoom digital (amplia a previa e recorta o centro da foto).
     */
    private fun applyZoom() {
        val state = camera?.cameraInfo?.zoomState?.value
        val minRatio = state?.minZoomRatio ?: 1f
        val maxRatio = max(state?.maxZoomRatio ?: 1f, minRatio)
        // No video nao ha recorte, entao o limite e o da camera.
        val limit = if (mode == Mode.VIDEO) maxRatio else max(MAX_ZOOM, maxRatio)
        totalZoom = totalZoom.coerceIn(minRatio, limit)
        val cameraZoom = min(totalZoom, maxRatio)
        camera?.cameraControl?.setZoomRatio(cameraZoom)
        digitalZoom = max(1f, totalZoom / cameraZoom)
        previewView.scaleX = digitalZoom
        previewView.scaleY = digitalZoom
        zoomBar.setRange(minRatio, limit)
        zoomBar.setValue(totalZoom)
        zoomText.text = if (totalZoom < 10f) {
            String.format(Locale.US, "%.1fx", totalZoom)
        } else {
            String.format(Locale.US, "%.0fx", totalZoom)
        }
        refreshUi()
    }

    private fun zoomLabel(ratio: Float): String {
        return if (ratio < 0.95f) {
            String.format(Locale.US, "%.1f", ratio).removePrefix("0")
        } else {
            String.format(Locale.US, "%.0fx", ratio)
        }
    }

    // ------------------------------------------------------------ luz e foco

    /** Barra da esquerda: [fraction] vai de -1 (mais escuro) a +1 (mais claro). */
    private fun setExposure(fraction: Float) {
        exposureFraction = fraction.coerceIn(-1f, 1f)
        val state = camera?.cameraInfo?.exposureState
        if (state != null && state.isExposureCompensationSupported) {
            val range = state.exposureCompensationRange
            val index = if (exposureFraction >= 0f) {
                Math.round(exposureFraction * range.upper)
            } else {
                Math.round(-exposureFraction * range.lower)
            }
            exposureEv = index * state.exposureCompensationStep.toFloat()
            if (index != exposureIndex) {
                exposureIndex = index
                applyExposureIndex()
            }
        } else {
            // A camera nao aceita o ajuste: o valor vale so para o realce da IA.
            exposureEv = exposureFraction * 2f
            exposureIndex = 0
            if (!exposureHintShown && exposureFraction != 0f && camera != null) {
                exposureHintShown = true
                toast(R.string.exposure_ai_only)
            }
        }
        updateExposureLabel()
    }

    private fun applyExposureIndex() {
        val cam = camera ?: return
        try {
            if (cam.cameraInfo.exposureState.isExposureCompensationSupported) {
                cam.cameraControl.setExposureCompensationIndex(exposureIndex)
            }
        } catch (e: Exception) {
            // camera fechada ou ajuste recusado: fica como esta
        }
    }

    private fun resetExposure() {
        exposureBar.setValue(0f)
        setExposure(0f)
    }

    private fun updateExposureLabel() {
        exposureText.text = String.format(Locale.US, "☀ %+.1f", exposureEv)
    }

    private fun focusAt(x: Float, y: Float, rawX: Float, rawY: Float) {
        val cam = camera ?: return
        try {
            val point = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            return
        }
        val origin = IntArray(2)
        previewContainer.getLocationOnScreen(origin)
        focusRing.animate().cancel()
        focusRing.x = rawX - origin[0] - focusRing.width / 2f
        focusRing.y = rawY - origin[1] - focusRing.height / 2f
        focusRing.alpha = 1f
        focusRing.scaleX = 1.4f
        focusRing.scaleY = 1.4f
        focusRing.visibility = View.VISIBLE
        focusRing.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
            focusRing.animate().setStartDelay(700).alpha(0f).setDuration(300).start()
        }.start()
    }

    // ------------------------------------------------------------- menus

    private fun showQualityMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val sizes = listOf(
            512 to R.string.q_fast,
            896 to R.string.q_balanced,
            1280 to R.string.q_max,
            1792 to R.string.q_ultra
        )
        val levels = listOf(R.string.e_natural, R.string.e_strong, R.string.e_extreme)

        popup.menu.add(0, 0, 0, R.string.menu_size).isEnabled = false
        sizes.forEachIndexed { index, size ->
            popup.menu.add(0, 100 + index, 1 + index, mark(size.first == inputSide) + getString(size.second))
        }
        popup.menu.add(0, 1, 10, R.string.menu_enhance).isEnabled = false
        levels.forEachIndexed { index, name ->
            popup.menu.add(0, 200 + index, 11 + index, mark(index == enhanceLevel) + getString(name))
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                in 100..199 -> {
                    val chosen = sizes[item.itemId - 100]
                    inputSide = chosen.first
                    toast(getString(R.string.quality_set, getString(chosen.second)))
                }
                in 200..299 -> {
                    enhanceLevel = item.itemId - 200
                    toast(getString(R.string.enhance_set, getString(levels[enhanceLevel])))
                }
            }
            true
        }
        popup.show()
    }

    private fun currentLevel(): Enhancer.Level = when (enhanceLevel) {
        0 -> Enhancer.NATURAL
        1 -> Enhancer.STRONG
        else -> Enhancer.EXTREME
    }

    private fun showOptionsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, if (gridOn) R.string.grid_hide else R.string.grid_show)
        popup.menu.add(0, 2, 1, mark(timerSec == 0) + getString(R.string.timer_off))
        popup.menu.add(0, 3, 2, mark(timerSec == 3) + getString(R.string.timer_3))
        popup.menu.add(0, 4, 3, mark(timerSec == 10) + getString(R.string.timer_10))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> gridOn = !gridOn
                2 -> timerSec = 0
                3 -> timerSec = 3
                4 -> timerSec = 10
            }
            refreshUi()
            true
        }
        popup.show()
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, R.string.enhance_from_gallery)
        popup.menu.add(0, 2, 1, R.string.quality)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> openGallery()
                2 -> showQualityMenu(anchor)
            }
            true
        }
        popup.show()
    }

    private fun mark(selected: Boolean): String = if (selected) "✓ " else ""

    private fun openGallery() {
        if (recording != null) return
        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // ------------------------------------------------------------- disparo

    private fun onShutter() {
        if (mode == Mode.VIDEO) {
            toggleRecording()
            return
        }
        val running = countdownJob
        if (running != null && running.isActive) {
            // Tocar de novo durante a contagem cancela.
            running.cancel()
            countdownText.visibility = View.GONE
            return
        }
        if (timerSec == 0) {
            takePhoto()
            return
        }
        countdownJob = lifecycleScope.launch {
            try {
                countdownText.visibility = View.VISIBLE
                for (i in timerSec downTo 1) {
                    countdownText.text = i.toString()
                    delay(1000)
                }
            } finally {
                countdownText.visibility = View.GONE
            }
            takePhoto()
        }
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        previewView.animate().alpha(0.3f).setDuration(80).withEndAction {
            previewView.animate().alpha(1f).setDuration(120).start()
        }.start()
        val crop = digitalZoom
        val ev = exposureEv
        val file = File(cacheDir, "captura.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    if (aiOn) process(file, crop, ev) else saveOriginal(file, crop)
                }

                override fun onError(exc: ImageCaptureException) {
                    capturing = false
                    toast(R.string.capture_error)
                }
            }
        )
    }

    /** Com a IA desligada a foto vai direto para a galeria, sem processamento. */
    private fun saveOriginal(file: File, crop: Float) {
        lifecycleScope.launch {
            val thumb = withContext(Dispatchers.IO) {
                try {
                    val uri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageValues()
                    ) ?: return@withContext null
                    val written = contentResolver.openOutputStream(uri)?.use { out ->
                        if (crop > 1.01f) {
                            // Zoom digital: salva so o centro da foto.
                            ImageLoader.load(file, 100000, crop).bitmap
                                .compress(Bitmap.CompressFormat.JPEG, 95, out)
                        } else {
                            file.inputStream().use { it.copyTo(out) }
                            true
                        }
                    } ?: false
                    finishPending(uri)
                    if (written) ImageLoader.load(file, 256, crop).bitmap else null
                } catch (e: Exception) {
                    null
                }
            }
            if (thumb != null) {
                btnGallery.setImageBitmap(thumb)
                toast(R.string.saved)
            } else {
                toast(R.string.save_error)
            }
        }
    }

    // --------------------------------------------------------------- video

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        val current = recording
        if (current != null) {
            current.stop()
            return
        }
        val video = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "FotoIA_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/FotoIA")
        }
        val output = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(values)
            .build()
        try {
            var pending = video.output.prepareRecording(this, output)
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                pending = pending.withAudioEnabled()
            }
            recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Status -> {
                        val seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                        recText.text = String.format(Locale.US, "● %02d:%02d", seconds / 60, seconds % 60)
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        toast(if (event.hasError()) R.string.video_error else R.string.video_saved)
                        refreshUi()
                    }
                    else -> { }
                }
            }
            recText.text = "● 00:00"
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (e: Exception) {
            recording = null
            toast(R.string.video_error)
        }
        refreshUi()
    }

    // ------------------------------------------------------------ aparencia

    private fun refreshUi() {
        val white = Color.WHITE
        val gray = 0xFF9E9E9E.toInt()
        val yellow = 0xFFFFC107.toInt()

        btnFlash.setImageResource(
            when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> R.drawable.ic_flash_on
                ImageCapture.FLASH_MODE_AUTO -> R.drawable.ic_flash_auto
                else -> R.drawable.ic_flash_off
            }
        )
        btnFlash.setColorFilter(if (flashMode == ImageCapture.FLASH_MODE_OFF) white else yellow)
        btnAi.setColorFilter(if (aiOn) yellow else gray)
        btnAi.isEnabled = mode != Mode.VIDEO
        btnAi.alpha = if (mode == Mode.VIDEO) 0.4f else 1f

        btnNight.visibility = if (mode == Mode.PHOTO) View.VISIBLE else View.INVISIBLE
        btnNight.setBackgroundResource(if (night) R.drawable.circle_active else R.drawable.circle_dark)
        btnNight.setColorFilter(if (night) Color.BLACK else yellow)

        gridOverlay.visibility = if (gridOn) View.VISIBLE else View.GONE

        val levels = zoomLevels()
        val currentZoom = totalZoom
        btnZoomA.text = zoomLabel(levels.first)
        btnZoomB.text = zoomLabel(levels.second)
        val firstSelected = kotlin.math.abs(currentZoom - levels.first) <=
            kotlin.math.abs(currentZoom - levels.second)
        btnZoomA.setTextColor(if (firstSelected) yellow else white)
        btnZoomB.setTextColor(if (firstSelected) white else yellow)
        btnZoomB.visibility = if (levels.second > levels.first + 0.05f) View.VISIBLE else View.GONE

        val isRecording = recording != null
        btnShutter.setBackgroundResource(
            when {
                isRecording -> R.drawable.shutter_rec
                mode == Mode.VIDEO -> R.drawable.shutter_video
                else -> R.drawable.shutter
            }
        )
        recText.visibility = if (isRecording) View.VISIBLE else View.GONE
        val idleVisibility = if (isRecording) View.INVISIBLE else View.VISIBLE
        btnFlip.visibility = idleVisibility
        btnGallery.visibility = idleVisibility

        styleTab(tabPortrait, mode == Mode.PORTRAIT)
        styleTab(tabPhoto, mode == Mode.PHOTO)
        styleTab(tabVideo, mode == Mode.VIDEO)
    }

    private fun styleTab(tab: TextView, selected: Boolean) {
        tab.setTextColor(if (selected) Color.WHITE else 0xFF9E9E9E.toInt())
        tab.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
    }

    // ------------------------------------------------------- melhoria por IA

    private fun processUri(uri: Uri) {
        lifecycleScope.launch {
            val file = File(cacheDir, "galeria.jpg")
            val ok = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { input.copyTo(it) }
                    } != null
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) process(file) else showError("não consegui abrir a imagem")
        }
    }

    /**
     * Melhora a foto com a IA. [crop] e o zoom digital (recorte do centro) e [ev] o ajuste
     * manual de luz usado no disparo; fotos da galeria usam 0 (so o ajuste automatico).
     */
    private fun process(file: File, crop: Float = 1f, ev: Float = 0f) {
        val side = inputSide
        val level = currentLevel()
        showResult()
        job?.cancel()
        job = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.Default) { ImageLoader.load(file, side, crop) }
                originalBmp = loaded.bitmap
                imageView.setImageBitmap(loaded.bitmap)
                statusText.text = getString(R.string.enhancing, 0)

                val result = withContext(Dispatchers.Default) {
                    val params = enhancer.analyze(loaded.bitmap, level, ev)
                    enhancer.enhance(loaded.bitmap, params) { done, total ->
                        val pct = done * 100 / max(1, total)
                        runOnUiThread {
                            if (job?.isActive == true) {
                                progressBar.progress = pct
                                statusText.text = getString(R.string.enhancing, pct)
                            }
                        }
                    }
                }
                // Fotos muito grandes nao podem ser desenhadas inteiras na tela: mostra uma copia menor.
                val preview = withContext(Dispatchers.Default) { previewOf(result) }
                enhancedBmp = result
                enhancedPreview = preview
                imageView.setImageBitmap(preview)
                progressBar.visibility = View.GONE
                statusText.setText(R.string.done_hint)
                btnSave.isEnabled = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                toast(R.string.out_of_memory)
                closeResult()
            } catch (e: Throwable) {
                showError(e.message ?: e.javaClass.simpleName)
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun previewOf(bmp: Bitmap): Bitmap {
        val longSide = max(bmp.width, bmp.height)
        if (longSide <= MAX_PREVIEW_SIDE) return bmp
        val scale = MAX_PREVIEW_SIDE.toFloat() / longSide
        val w = max(1, Math.round(bmp.width * scale))
        val h = max(1, Math.round(bmp.height * scale))
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    private fun showError(detail: String) {
        toast(getString(R.string.enhance_error, detail))
        closeResult()
    }

    private fun showResult() {
        originalBmp = null
        enhancedBmp = null
        enhancedPreview = null
        imageView.setImageDrawable(null)
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE
        statusText.setText(R.string.preparing)
        btnSave.isEnabled = false
        cameraLayer.visibility = View.GONE
        resultLayer.visibility = View.VISIBLE
        backCallback.isEnabled = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun closeResult() {
        job?.cancel()
        job = null
        imageView.setImageDrawable(null)
        originalBmp = null
        enhancedBmp = null
        enhancedPreview = null
        resultLayer.visibility = View.GONE
        cameraLayer.visibility = View.VISIBLE
        backCallback.isEnabled = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun saveEnhanced() {
        val bmp = enhancedBmp ?: return
        btnSave.isEnabled = false
        lifecycleScope.launch {
            val thumb = withContext(Dispatchers.IO) {
                try {
                    val uri = contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageValues()
                    ) ?: return@withContext null
                    val written = contentResolver.openOutputStream(uri)?.use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    } ?: false
                    finishPending(uri)
                    if (written) thumbnailOf(bmp) else null
                } catch (e: Exception) {
                    null
                }
            }
            if (thumb != null) {
                btnGallery.setImageBitmap(thumb)
                toast(R.string.saved)
                closeResult()
            } else {
                toast(R.string.save_error)
                btnSave.isEnabled = true
            }
        }
    }

    // ------------------------------------------------------------- utilidades

    private fun imageValues(): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "FotoIA_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FotoIA")
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    private fun finishPending(uri: Uri) {
        val values = ContentValues()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        contentResolver.update(uri, values, null, null)
    }

    private fun thumbnailOf(bmp: Bitmap): Bitmap {
        val scale = 256f / max(bmp.width, bmp.height)
        val w = max(1, Math.round(bmp.width * scale))
        val h = max(1, Math.round(bmp.height * scale))
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        /** Zoom maximo oferecido na barra (camera x recorte digital). */
        const val MAX_ZOOM = 100f

        /** Lado maior da copia mostrada na tela de resultado. */
        const val MAX_PREVIEW_SIDE = 2400
    }

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }
}
