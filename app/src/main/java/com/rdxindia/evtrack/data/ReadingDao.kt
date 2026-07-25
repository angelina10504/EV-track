package com.rdxindia.evtrack.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Insert
    suspend fun insert(reading: Reading): Long

    @Query("SELECT * FROM readings ORDER BY timestamp DESC")
    fun getAll(): Flow<List<Reading>>

    @Query("SELECT MAX(odoKm) FROM readings")
    suspend fun getMaxOdo(): Int?

    @Query("SELECT * FROM readings WHERE id = :id")
    suspend fun getById(id: Long): Reading?

    /** Readings the user corrected — their values are the strongest ground truth. */
    @Query("SELECT * FROM readings WHERE userEdited = 1 ORDER BY timestamp DESC")
    suspend fun getUserEdited(): List<Reading>

    /** All saved readings, newest first — the full ground-truth pool. */
    @Query("SELECT * FROM readings ORDER BY timestamp DESC")
    suspend fun getAllOnce(): List<Reading>

    /** Rows saved before hashing existed, or whose hash failed to compute. */
    @Query("SELECT * FROM readings WHERE imageHash IS NULL AND imagePath IS NOT NULL")
    suspend fun getMissingImageHash(): List<Reading>

    @Query("UPDATE readings SET imageHash = :hash WHERE id = :id")
    suspend fun setImageHash(id: Long, hash: String)
}
