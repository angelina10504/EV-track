package com.rdxindia.evtrack.ui.home

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.rdxindia.evtrack.EvTrackApp
import com.rdxindia.evtrack.R
import com.rdxindia.evtrack.data.DevSettings
import com.rdxindia.evtrack.data.EngineMode
import com.rdxindia.evtrack.databinding.ActivityMainBinding
import com.rdxindia.evtrack.ui.batch.BatchTestActivity
import com.rdxindia.evtrack.ui.capture.CaptureActivity
import com.rdxindia.evtrack.ui.detail.DetailActivity
import com.rdxindia.evtrack.ui.review.ReviewActivity
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory((application as EvTrackApp).repository)
    }
    private val adapter = ReadingsAdapter { reading ->
        startActivity(DetailActivity.newIntent(this, reading.id))
    }

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openReview(uri)
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.recyclerReadings.layoutManager = LinearLayoutManager(this)
        binding.recyclerReadings.adapter = adapter

        binding.buttonTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchCamera()
            } else {
                requestCameraPermission.launch(Manifest.permission.CAMERA)
            }
        }

        // Developer tooling (engine switch, batch evaluation) is debug-only:
        // in a release build the entry point does not exist at all.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        binding.buttonDevSettings.visibility = if (debuggable) View.VISIBLE else View.GONE
        binding.buttonDevSettings.setOnClickListener { showDevMenu() }

        binding.buttonPickGallery.setOnClickListener {
            pickMedia.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.readings.collect { readings ->
                    adapter.submitList(readings)
                    binding.textEmpty.visibility =
                        if (readings.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun launchCamera() {
        startActivity(CaptureActivity.newIntent(this))
    }

    /** Developer-only menu; entry point for debug tooling, not the user flow. */
    private fun showDevMenu() {
        val options = arrayOf(
            getString(R.string.dev_option_engine),
            getString(R.string.dev_option_batch)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dev_settings)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEnginePicker()
                    1 -> startActivity(BatchTestActivity.newIntent(this))
                }
            }
            .show()
    }

    /** Developer-only OCR engine selection; takes effect on the next photo. */
    private fun showEnginePicker() {
        val modes = listOf(EngineMode.ML_KIT, EngineMode.PADDLE, EngineMode.BOTH)
        val labels = arrayOf(
            getString(R.string.engine_mode_mlkit),
            getString(R.string.engine_mode_paddle),
            getString(R.string.engine_mode_both)
        )
        val current = modes.indexOf(DevSettings.engineMode(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dev_engine_title)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                DevSettings.setEngineMode(this, modes[which])
                Toast.makeText(
                    this, getString(R.string.engine_mode_saved, labels[which]), Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .show()
    }

    private fun openReview(imageUri: Uri) {
        startActivity(ReviewActivity.newIntent(this, imageUri))
    }
}
