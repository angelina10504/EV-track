package com.rdxindia.evtrack.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "readings")
data class Reading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,               // epoch millis, set at save time
    val odoKm: Int?,
    val batteryPct: Int?,
    val rangeKm: Int?,
    val eventType: String,             // CHARGE_START | CHARGE_END | MANUAL
    val userEdited: Boolean,           // true if any field differs from OCR output
    val ocrOdo: Int?,
    val ocrBattery: Int?,
    val ocrRange: Int?,                // raw OCR values, kept for future model training
    val imagePath: String?,            // copy of the image in app-internal storage
    val synced: Boolean = false        // for future server sync
)

object EventType {
    const val CHARGE_START = "CHARGE_START"
    const val CHARGE_END = "CHARGE_END"
    const val MANUAL = "MANUAL"
}
