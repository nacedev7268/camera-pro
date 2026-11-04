package com.camerapro.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var btnCapture: ImageButton
    private lateinit var progressBar: ProgressBar
    private lateinit var modePhoto: TextView
    private lateinit var modePortrait: TextView
    private lateinit var zoomControls: LinearLayout

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var camera: androidx.camera.core.Camera? = null

    private var isPortraitMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        btnCapture = findViewById(R.id.btnCapture)
        progressBar = findViewById(R.id.progressBar)
        modePhoto = findViewById(R.id.modePhoto)
        modePortrait = findViewById(R.id.modePortrait)
        zoomControls = findViewById(R.id.zoomControls)

        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        btnCapture.setOnClickListener { takePhoto() }

        modePhoto.setOnClickListener {
            isPortraitMode = false
            modePhoto.setTextColor(0xFFFFFFFF.toInt())
            modePortrait.setTextColor(0xFF888888.toInt())
            Toast.makeText(this, "PHOTO mode", Toast.LENGTH_SHORT).show()
        }

        modePortrait.setOnClickListener {
            isPortraitMode = true
            modePortrait.setTextColor(0xFFFFFFFF.toInt())
            modePhoto.setTextColor(0xFF888888.toInt())
            Toast.makeText(this, "PORTRAIT mode", Toast.LENGTH_SHORT).show()
        }

        setupZoomControls()
    }

    private fun setupZoomControls() {
        val zoomLevels = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
        for (level in zoomLevels) {
            val btn = Button(this)
            btn.text = "${level}x"
            btn.textSize = 12f
            btn.setPadding(16, 8, 16, 8)
            btn.setOnClickListener {
                camera?.cameraControl?.setZoomRatio(level)
            }
            zoomControls.addView(btn)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
            } catch (exc: Exception) {
                Toast.makeText(this@MainActivity, "Camera error: ${exc.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return
        progressBar.visibility = View.VISIBLE

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "CameraPro_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraPro")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            .build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Capture failed: ${exc.message}", Toast.LENGTH_SHORT).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val uri = output.savedUri
                    if (uri != null && isPortraitMode) {
                        applyPortraitEffect(uri)
                    } else {
                        progressBar.visibility = View.GONE
                        Toast.makeText(this@MainActivity, "Saved to Gallery", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun applyPortraitEffect(uri: Uri) {
        Toast.makeText(this, "Applying Portrait Blur…", Toast.LENGTH_SHORT).show()
        cameraExecutor.execute {
            try {
                val inputStream: InputStream? = contentResolver.openInputStream(uri)
                val originalBitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                if (originalBitmap == null) {
                    runOnUiThread {
                        progressBar.visibility = View.GONE
                        Toast.makeText(this, "Failed to read image", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                val scaledBitmap = scaleDown(originalBitmap, 1024f)

                val options = SelfieSegmenterOptions.Builder()
                    .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                    .build()
                val segmenter = Segmentation.getClient(options)
                val inputImage = InputImage.fromBitmap(scaledBitmap, 0)

                segmenter.process(inputImage)
                    .addOnSuccessListener { mask ->
                        try {
                            val blurred = blurBackground(originalBitmap, mask.buffer, mask.width, mask.height)
                            saveProcessedImage(blurred)
                            runOnUiThread {
                                progressBar.visibility = View.GONE
                                Toast.makeText(this, "Portrait saved to Gallery", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                progressBar.visibility = View.GONE
                                Toast.makeText(this, "Blur error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .addOnFailureListener { e ->
                        runOnUiThread {
                            progressBar.visibility = View.GONE
                            Toast.makeText(this, "Segmentation failed: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
            } catch (e: Exception) {
                runOnUiThread {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun scaleDown(bitmap: Bitmap, maxSize: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val ratio = minOf(maxSize / w, maxSize / h)
        return if (ratio < 1f) {
            Bitmap.createScaledBitmap(bitmap, (w * ratio).toInt(), (h * ratio).toInt(), true)
        } else bitmap
    }

    private fun blurBackground(original: Bitmap, maskBuffer: java.nio.ByteBuffer, maskW: Int, maskH: Int): Bitmap {
        val w = original.width
        val h = original.height

        val blurred = original.copy(Bitmap.Config.ARGB_8888, true)
        val rs = RenderScript.create(this)
        val inputAlloc = Allocation.createFromBitmap(rs, original)
        val outputAlloc = Allocation.createTyped(rs, inputAlloc.type)
        val blurScript = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        blurScript.setRadius(25f)
        blurScript.setInput(inputAlloc)
        blurScript.forEach(outputAlloc)
        outputAlloc.copyTo(blurred)
        rs.destroy()

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawBitmap(blurred, 0f, 0f, null)

        val maskBitmap = Bitmap.createBitmap(maskW, maskH, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(maskW * maskH)
        maskBuffer.rewind()
        for (i in 0 until maskW * maskH) {
            val conf = maskBuffer.float
            val alpha = (conf.coerceIn(0f, 1f) * 255).toInt()
            pixels[i] = (alpha shl 24) or 0x00FFFFFF
        }
        maskBitmap.setPixels(pixels, 0, maskW, 0, 0, maskW, maskH)

        val scaledMask = Bitmap.createScaledBitmap(maskBitmap, w, h, true)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
        canvas.drawBitmap(original, 0f, 0f, null)
        canvas.drawBitmap(scaledMask, 0f, 0f, paint)

        return result
    }

    private fun saveProcessedImage(bitmap: Bitmap) {
        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "CameraPro_Portrait_$name")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraPro")
            }
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            contentResolver.openOutputStream(it)?.use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Permissions required", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}
