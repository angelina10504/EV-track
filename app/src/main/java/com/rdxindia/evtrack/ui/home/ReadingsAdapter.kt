package com.rdxindia.evtrack.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rdxindia.evtrack.R
import com.rdxindia.evtrack.data.Reading
import com.rdxindia.evtrack.databinding.ItemReadingBinding
import java.text.DateFormat
import java.util.Date

class ReadingsAdapter(
    private val onClick: (Reading) -> Unit
) : ListAdapter<Reading, ReadingsAdapter.ViewHolder>(Diff) {

    object Diff : DiffUtil.ItemCallback<Reading>() {
        override fun areItemsTheSame(oldItem: Reading, newItem: Reading) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Reading, newItem: Reading) = oldItem == newItem
    }

    inner class ViewHolder(val binding: ItemReadingBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemReadingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val reading = getItem(position)
        val context = holder.binding.root.context
        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)

        holder.binding.textTimestamp.text = dateFormat.format(Date(reading.timestamp))
        holder.binding.textEventType.text = reading.eventType
        holder.binding.textValues.text = context.getString(
            R.string.reading_values_format,
            reading.odoKm?.toString() ?: context.getString(R.string.value_missing),
            reading.batteryPct?.toString() ?: context.getString(R.string.value_missing),
            reading.rangeKm?.toString() ?: context.getString(R.string.value_missing)
        )
        holder.binding.badgeEdited.visibility = if (reading.userEdited) View.VISIBLE else View.GONE
        holder.binding.root.setOnClickListener { onClick(reading) }
    }
}
