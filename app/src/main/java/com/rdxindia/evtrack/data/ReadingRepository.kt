package com.rdxindia.evtrack.data

import kotlinx.coroutines.flow.Flow

class ReadingRepository(private val dao: ReadingDao) {
    val readings: Flow<List<Reading>> = dao.getAll()

    suspend fun insert(reading: Reading): Long = dao.insert(reading)

    suspend fun getMaxOdo(): Int? = dao.getMaxOdo()

    suspend fun getById(id: Long): Reading? = dao.getById(id)
}
