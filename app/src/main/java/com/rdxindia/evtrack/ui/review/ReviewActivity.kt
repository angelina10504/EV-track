package com.rdxindia.evtrack.ui.review

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rdxindia.evtrack.EvTrackApp
import com.rdxindia.evtrack.R
import com.rdxindia.evtrack.data.EventType
import com.rdxindia.evtrack.databinding.ActivityReviewBinding
import com.rdxindia.evtrack.parser.ExtractionResult
import kotlinx.coroutines.launch

class ReviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReviewBinding
    private val viewModel: ReviewViewModel by viewModels {
        ReviewViewModel.factory(application, (application as EvTrackApp).repository)
    }

    private lateinit var imageUri: Uri
    private var maxOdo: Int? = null
    private var fieldsPrefilled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriString == null) {
            finish()
            return
        }
        imageUri = Uri.parse(uriString)

        binding.radioManual.isChecked = true

        binding.headerDebug.setOnClickListener {
            val visible = binding.textDebug.visibility == View.VISIBLE
            binding.textDebug.visibility = if (visible) View.GONE else View.VISIBLE
            binding.headerDebug.setText(
                if (visible) R.string.debug_header_collapsed else R.string.debug_header_expanded
            )
        }

        binding.inputOdo.doAfterTextChanged { validateFields() }
        binding.inputBattery.doAfterTextChanged { validateFields() }
        binding.inputRange.doAfterTextChanged { validateFields() }

        binding.buttonSave.setOnClickListener { onSaveClicked() }
        binding.buttonDiscard.setOnClickListener { finish() }

        viewModel.process(imageUri)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is ReviewState.Loading -> showLoading()
                        is ReviewState.Ready -> showReady(state)
                        is ReviewState.Saved -> {
                            Toast.makeText(
                                this@ReviewActivity, R.string.reading_saved, Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    }
                }
            }
        }
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.textProcessing.visibility = View.VISIBLE
        binding.contentGroup.visibility = View.GONE
    }

    private fun showReady(state: ReviewState.Ready) {
        binding.progressBar.visibility = View.GONE
        binding.textProcessing.visibility = View.GONE
        binding.contentGroup.visibility = View.VISIBLE

        maxOdo = state.maxOdo

        if (state.bitmap != null) {
            binding.imagePreview.setImageBitmap(state.bitmap)
        }
        binding.textNoText.visibility = if (state.noText) View.VISIBLE else View.GONE

        if (!fieldsPrefilled) {
            fieldsPrefilled = true
            val extraction = state.extraction
            binding.inputOdo.setText(extraction.odo?.toString() ?: "")
            binding.inputBattery.setText(extraction.battery?.toString() ?: "")
            binding.inputRange.setText(extraction.range?.toString() ?: "")
            renderDebug(extraction)
            validateFields()
        }
    }

    private fun renderDebug(extraction: ExtractionResult) {
        val builder = StringBuilder()
        if (extraction.rawLines.isEmpty()) {
            builder.append(getString(R.string.debug_no_lines))
        } else {
            extraction.rawLines.forEach { line ->
                builder.append("\"").append(line.text).append("\"  ")
                    .append(line.boxString()).append('\n')
            }
        }
        if (extraction.confidenceNotes.isNotEmpty()) {
            builder.append('\n').append(getString(R.string.debug_notes_header)).append('\n')
            extraction.confidenceNotes.forEach { builder.append("• ").append(it).append('\n') }
        }
        binding.textDebug.text = builder.toString().trimEnd()
    }

    private fun currentOdo(): Int? = binding.inputOdo.text?.toString()?.trim()?.toIntOrNull()
    private fun currentBattery(): Int? = binding.inputBattery.text?.toString()?.trim()?.toIntOrNull()
    private fun currentRange(): Int? = binding.inputRange.text?.toString()?.trim()?.toIntOrNull()

    /** Non-blocking sanity warnings shown under the relevant field. */
    private fun validateFields() {
        val odo = currentOdo()
        val battery = currentBattery()
        val range = currentRange()
        val lastOdo = maxOdo

        binding.layoutBattery.error = if (battery != null && battery !in 0..100) {
            getString(R.string.warn_battery_range)
        } else null

        binding.layoutOdo.error = when {
            odo != null && lastOdo != null && odo < lastOdo ->
                getString(R.string.warn_odo_lower, lastOdo.toString())
            odo != null && lastOdo != null && odo > lastOdo + 1000 ->
                getString(R.string.warn_odo_jump, lastOdo.toString())
            else -> null
        }

        binding.layoutRange.error = if (range != null && range > 250) {
            getString(R.string.warn_range_high)
        } else null
    }

    private fun onSaveClicked() {
        validateFields()

        // Battery out of 0-100 is cleared (with a warning) rather than saved;
        // everything else is a non-blocking warning per spec.
        val battery = currentBattery()
        if (battery != null && battery !in 0..100) {
            binding.inputBattery.setText("")
            binding.layoutBattery.error = getString(R.string.warn_battery_cleared)
            return
        }

        val eventType = when (binding.radioGroupEvent.checkedRadioButtonId) {
            R.id.radioChargeStart -> EventType.CHARGE_START
            R.id.radioChargeEnd -> EventType.CHARGE_END
            else -> EventType.MANUAL
        }

        binding.buttonSave.isEnabled = false
        viewModel.save(
            imageUri = imageUri,
            odo = currentOdo(),
            battery = currentBattery(),
            range = currentRange(),
            eventType = eventType
        )
    }

    companion object {
        private const val EXTRA_IMAGE_URI = "extra_image_uri"

        fun newIntent(context: Context, imageUri: Uri): Intent =
            Intent(context, ReviewActivity::class.java)
                .putExtra(EXTRA_IMAGE_URI, imageUri.toString())
    }
}
