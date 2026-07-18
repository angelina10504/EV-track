package com.rdxindia.evtrack.ui.detail

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rdxindia.evtrack.EvTrackApp
import com.rdxindia.evtrack.R
import com.rdxindia.evtrack.data.Reading
import com.rdxindia.evtrack.databinding.ActivityDetailBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

class DetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDetailBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getLongExtra(EXTRA_READING_ID, -1L)
        if (id < 0) {
            finish()
            return
        }

        lifecycleScope.launch {
            val reading = (application as EvTrackApp).repository.getById(id)
            if (reading == null) {
                finish()
            } else {
                bind(reading)
            }
        }
    }

    private suspend fun bind(reading: Reading) {
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val missing = getString(R.string.value_missing)

        binding.textTimestamp.text = dateFormat.format(Date(reading.timestamp))
        binding.textEventType.text = reading.eventType
        binding.textOdo.text = getString(R.string.detail_odo, reading.odoKm?.toString() ?: missing)
        binding.textBattery.text =
            getString(R.string.detail_battery, reading.batteryPct?.toString() ?: missing)
        binding.textRange.text =
            getString(R.string.detail_range, reading.rangeKm?.toString() ?: missing)
        binding.textOcrValues.text = getString(
            R.string.detail_ocr_values,
            reading.ocrOdo?.toString() ?: missing,
            reading.ocrBattery?.toString() ?: missing,
            reading.ocrRange?.toString() ?: missing
        )
        binding.badgeEdited.visibility = if (reading.userEdited) View.VISIBLE else View.GONE

        val path = reading.imagePath
        if (path != null && File(path).exists()) {
            val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(path) }
            if (bitmap != null) binding.imageReading.setImageBitmap(bitmap)
        }
    }

    companion object {
        private const val EXTRA_READING_ID = "extra_reading_id"

        fun newIntent(context: Context, readingId: Long): Intent =
            Intent(context, DetailActivity::class.java).putExtra(EXTRA_READING_ID, readingId)
    }
}
