package com.rdxindia.evtrack.data

import com.rdxindia.evtrack.util.ImageHasher
import kotlinx.coroutines.flow.Flow
import java.io.File

class ReadingRepository(private val dao: ReadingDao) {
    val readings: Flow<List<Reading>> = dao.getAll()

    suspend fun insert(reading: Reading): Long = dao.insert(reading)

    suspend fun getMaxOdo(): Int? = dao.getMaxOdo()

    suspend fun getById(id: Long): Reading? = dao.getById(id)

    suspend fun getUserEdited(): List<Reading> = dao.getUserEdited()

    suspend fun getAllOnce(): List<Reading> = dao.getAllOnce()

    /**
     * Hashes stored image copies for rows saved before hashing existed.
     * Rows whose image file is gone stay null and simply can't be matched.
     * Returns how many rows were backfilled.
     */
    suspend fun backfillImageHashes(): Int {
        var updated = 0
        for (reading in dao.getMissingImageHash()) {
            val path = reading.imagePath ?: continue
            val file = File(path)
            if (!file.exists()) continue
            val hash = ImageHasher.hashFile(file) ?: continue
            dao.setImageHash(reading.id, hash)
            updated++
        }
        return updated
    }
}
