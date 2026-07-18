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
}
