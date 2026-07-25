package com.rdxindia.evtrack.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * Content hashing for matching a picked image back to a saved reading.
 *
 * Filenames can't do this job: images are renamed to `reading_<millis>.jpg`
 * when copied into internal storage, so the original gallery name is gone.
 * The stored copy is a byte-for-byte stream copy of the source, so a SHA-256
 * over the raw bytes is identical on both sides.
 */
object ImageHasher {

    private const val BUFFER_SIZE = 64 * 1024

    fun hashFile(file: File): String? = try {
        file.inputStream().use { hashStream(it) }
    } catch (_: Exception) {
        null
    }

    fun hashUri(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { hashStream(it) }
    } catch (_: Exception) {
        null
    }

    private fun hashStream(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
