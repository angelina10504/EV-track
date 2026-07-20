package com.rdxindia.evtrack.ui.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rdxindia.evtrack.R
import com.rdxindia.evtrack.databinding.ActivityCaptureBinding
import com.rdxindia.evtrack.ui.review.ReviewActivity
import com.rdxindia.evtrack.util.ImageUtils
import com.rdxindia.evtrack.util.SharpnessMeter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var torchOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonCapture.setOnClickListener { takePhoto() }
        binding.buttonTorch.setOnClickListener { toggleTorch() }

        startCamera()
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    binding.buttonTorch.visibility = android.view.View.VISIBLE
                }
                setUpTapToFocus()
            } catch (_: Exception) {
                Toast.makeText(this, R.string.camera_error, Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpTapToFocus() {
        binding.previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = binding.previewView.meteringPointFactory
                    .createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(
                    point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                ).build()
                camera?.cameraControl?.startFocusAndMetering(action)
            }
            true
        }
    }

    private fun toggleTorch() {
        torchOn = !torchOn
        camera?.cameraControl?.enableTorch(torchOn)
        binding.buttonTorch.setText(if (torchOn) R.string.torch_off else R.string.torch)
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        binding.buttonCapture.isEnabled = false

        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    checkSharpnessThenProceed(file)
                }

                override fun onError(exception: ImageCaptureException) {
                    binding.buttonCapture.isEnabled = true
                    Toast.makeText(
                        this@CaptureActivity, R.string.camera_error, Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    /**
     * The PreviewView shows a center-crop of the sensor frame, but the saved
     * still is the full frame — so the display the user carefully fit into the
     * guide window ends up small in the file. Crop the capture to the guide
     * window region (with margin) so OCR gets what the user framed.
     */
    private fun cropToGuideWindow(file: File) {
        val bitmap = ImageUtils.loadDownscaledBitmap(
            this, Uri.fromFile(file), ImageUtils.OCR_RETRY_DIMENSION
        ) ?: return
        val viewW = binding.previewView.width.toFloat()
        val viewH = binding.previewView.height.toFloat()
        if (viewW <= 0 || viewH <= 0) return
        val guide = binding.guideOverlay.windowRect()

        // FILL_CENTER: the image is scaled to cover the view and centered.
        val scale = maxOf(viewW / bitmap.width, viewH / bitmap.height)
        val offsetX = (bitmap.width * scale - viewW) / 2f
        val offsetY = (bitmap.height * scale - viewH) / 2f
        val marginX = guide.width() * 0.12f
        val marginY = guide.height() * 0.25f

        val left = (((guide.left - marginX) + offsetX) / scale).toInt().coerceAtLeast(0)
        val top = (((guide.top - marginY) + offsetY) / scale).toInt().coerceAtLeast(0)
        val right = (((guide.right + marginX) + offsetX) / scale).toInt().coerceAtMost(bitmap.width)
        val bottom = (((guide.bottom + marginY) + offsetY) / scale).toInt().coerceAtMost(bitmap.height)
        if (right - left < 200 || bottom - top < 100) return

        val cropped = android.graphics.Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        file.outputStream().use { out ->
            cropped.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
        }
    }

    private fun checkSharpnessThenProceed(file: File) {
        lifecycleScope.launch {
            val score = withContext(Dispatchers.Default) {
                cropToGuideWindow(file)
                val bitmap = ImageUtils.loadDownscaledBitmap(
                    this@CaptureActivity, Uri.fromFile(file), 800
                )
                bitmap?.let { sharpnessOf(it) } ?: 0.0
            }
            if (score < SharpnessMeter.BLURRY_BELOW) {
                MaterialAlertDialogBuilder(this@CaptureActivity)
                    .setTitle(R.string.blurry_title)
                    .setMessage(R.string.blurry_message)
                    .setPositiveButton(R.string.retake) { _, _ ->
                        file.delete()
                        binding.buttonCapture.isEnabled = true
                    }
                    .setNegativeButton(R.string.use_anyway) { _, _ -> proceed(file) }
                    .setCancelable(false)
                    .show()
            } else {
                proceed(file)
            }
        }
    }

    private fun sharpnessOf(bitmap: Bitmap): Double {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val luminance = IntArray(w * h) { i ->
            val p = pixels[i]
            (299 * ((p shr 16) and 0xFF) + 587 * ((p shr 8) and 0xFF) + 114 * (p and 0xFF)) / 1000
        }
        return SharpnessMeter.score(luminance, w, h)
    }

    private fun proceed(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        startActivity(ReviewActivity.newIntent(this, uri))
        finish()
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, CaptureActivity::class.java)
    }
}
