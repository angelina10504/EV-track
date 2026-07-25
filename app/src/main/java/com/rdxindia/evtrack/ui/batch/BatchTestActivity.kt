package com.rdxindia.evtrack.ui.batch

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rdxindia.evtrack.EvTrackApp
import com.rdxindia.evtrack.R
import com.rdxindia.evtrack.databinding.ActivityBatchTestBinding
import kotlinx.coroutines.launch
import java.io.File

/**
 * Debug-only batch evaluation screen. Reachable from developer settings only —
 * it is never part of the normal capture → review → save flow.
 */
class BatchTestActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchTestBinding
    private val viewModel: BatchTestViewModel by viewModels {
        BatchTestViewModel.factory(application, (application as EvTrackApp).repository)
    }
    private var csvFile: File? = null

    private val pickImages = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)
    ) { uris ->
        if (uris.isNotEmpty()) start(uris)
    }

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri == null) return@registerForActivityResult
        val uris = viewModel.listFolder(treeUri)
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.batch_folder_empty, Toast.LENGTH_LONG).show()
        } else {
            start(uris)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchTestBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.batch_title)

        binding.buttonPickImages.setOnClickListener {
            pickImages.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        binding.buttonPickFolder.setOnClickListener { pickFolder.launch(null) }
        binding.buttonShare.setOnClickListener { shareCsv() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun start(uris: List<Uri>) {
        viewModel.useReadingsAsTruth = binding.checkUseReadings.isChecked
        viewModel.runBatch(uris)
    }

    private fun render(state: BatchState) {
        when (state) {
            is BatchState.Idle -> {
                binding.progressRow.visibility = View.GONE
            }
            is BatchState.Running -> {
                binding.progressRow.visibility = View.VISIBLE
                setInputsEnabled(false)
                binding.textProgress.text = getString(
                    R.string.batch_progress, state.done + 1, state.total, state.current
                )
            }
            is BatchState.Done -> {
                binding.progressRow.visibility = View.GONE
                setInputsEnabled(true)
                binding.textReport.text = state.report
                csvFile = state.csvFile
                binding.buttonShare.isEnabled = state.csvFile != null
                Toast.makeText(
                    this, getString(R.string.batch_done, state.rowCount), Toast.LENGTH_SHORT
                ).show()
            }
            is BatchState.Failed -> {
                binding.progressRow.visibility = View.GONE
                setInputsEnabled(true)
                binding.textReport.text = getString(R.string.batch_failed, state.message)
            }
        }
    }

    private fun setInputsEnabled(enabled: Boolean) {
        binding.buttonPickImages.isEnabled = enabled
        binding.buttonPickFolder.isEnabled = enabled
        binding.checkUseReadings.isEnabled = enabled
    }

    private fun shareCsv() {
        val file = csvFile ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.batch_share_csv)))
    }

    companion object {
        private const val MAX_IMAGES = 100

        fun newIntent(context: Context): Intent = Intent(context, BatchTestActivity::class.java)
    }
}
