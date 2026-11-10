package com.fotoia.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var cameraLayer: View
    private lateinit var resultLayer: View
    private lateinit var previewView: PreviewView
    private lateinit var qualityGroup: MaterialButtonToggleGroup
    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnSave: MaterialButton

    private var imageCapture: ImageCapture? = null
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var job: Job? = null
    private var originalBmp: Bitmap? = null
    private var enhancedBmp: Bitmap? = null
    private var capturing = false

    private val enhancer by lazy { Enhancer(applicationContext) }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else Toast.makeText(this, R.string.camera_denied, Toast.LENGTH_LONG).show()
        }

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
        resultLayer = findViewById(R.id.resultLayer)
        previewView = findViewById(R.id.previewView)
        qualityGroup = findViewById(R.id.qualityGroup)
        imageView = findViewById(R.id.imageView)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        btnSave = findViewById(R.id.btnSave)

        findViewById<View>(R.id.btnShutter).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.btnGallery).setOnClickListener {
            pickImage.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        findViewById<View>(R.id.btnFlip).setOnClickListener {
            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                CameraSelector.LENS_FACING_FRONT
            } else {
                CameraSelector.LENS_FACING_BACK
            }
            startCamera()
        }
        findViewById<View>(R.id.btnDiscard).setOnClickListener { closeResult() }
        btnSave.setOnClickListener { saveEnhanced() }

        // Segurar a foto mostra o "antes"; soltar mostra o "depois".
        imageView.setOnTouchListener { _, event ->
            val before = originalBmp
            val after = enhancedBmp
            if (before != null && after != null) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> imageView.setImageBitmap(before)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> imageView.setImageBitmap(after)
                }
            }
            true
        }

        onBackPressedDispatcher.addCallback(this, backCallback)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    /** Lado maior da foto que entra na IA (a saida tem 4x esse tamanho). */
    private fun inputSide(): Int = when (qualityGroup.checkedButtonId) {
        R.id.qFast -> 512
        R.id.qMax -> 1280
        else -> 896
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .build()
                val selector = CameraSelector.Builder().requireLensFacing(lensFacing).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, preview, capture)
                imageCapture = capture
            } catch (e: Exception) {
                imageCapture = null
                Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        if (capturing) return
        capturing = true
        val file = File(cacheDir, "captura.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    process(file)
                }

                override fun onError(exc: ImageCaptureException) {
                    capturing = false
                    Toast.makeText(this@MainActivity, R.string.capture_error, Toast.LENGTH_LONG).show()
                }
            }
        )
    }

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
            if (ok) process(file)
            else showError("não consegui abrir a imagem")
        }
    }

    private fun process(file: File) {
        val side = inputSide()
        showResult()
        job?.cancel()
        job = lifecycleScope.launch {
            try {
                val loaded = withContext(Dispatchers.Default) { ImageLoader.load(file, side) }
                originalBmp = loaded.bitmap
                imageView.setImageBitmap(loaded.bitmap)
                statusText.text = getString(R.string.enhancing, 0)

                val result = withContext(Dispatchers.Default) {
                    var out = enhancer.enhance(loaded.bitmap) { done, total ->
                        val pct = done * 100 / max(1, total)
                        runOnUiThread {
                            if (job?.isActive == true) {
                                progressBar.progress = pct
                                statusText.text = getString(R.string.enhancing, pct)
                            }
                        }
                    }
                    // Nao salva maior do que a foto original da camera.
                    val origLong = max(loaded.originalWidth, loaded.originalHeight)
                    val outLong = max(out.width, out.height)
                    if (outLong > origLong && origLong >= max(loaded.bitmap.width, loaded.bitmap.height)) {
                        val scale = origLong.toFloat() / outLong
                        val w = max(1, Math.round(out.width * scale))
                        val h = max(1, Math.round(out.height * scale))
                        out = Bitmap.createScaledBitmap(out, w, h, true)
                    }
                    out
                }
                enhancedBmp = result
                imageView.setImageBitmap(result)
                progressBar.visibility = View.GONE
                statusText.setText(R.string.done_hint)
                btnSave.isEnabled = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                showError(e.message ?: e.javaClass.simpleName)
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    private fun showError(detail: String) {
        Toast.makeText(this, getString(R.string.enhance_error, detail), Toast.LENGTH_LONG).show()
        closeResult()
    }

    private fun showResult() {
        originalBmp = null
        enhancedBmp = null
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
        resultLayer.visibility = View.GONE
        cameraLayer.visibility = View.VISIBLE
        backCallback.isEnabled = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun saveEnhanced() {
        val bmp = enhancedBmp ?: return
        btnSave.isEnabled = false
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "FotoIA_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FotoIA")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: return@withContext false
                    val written = contentResolver.openOutputStream(uri)?.use {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
                    } ?: false
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    written
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) {
                Toast.makeText(this@MainActivity, R.string.saved, Toast.LENGTH_LONG).show()
                closeResult()
            } else {
                Toast.makeText(this@MainActivity, R.string.save_error, Toast.LENGTH_LONG).show()
                btnSave.isEnabled = true
            }
        }
    }
}
