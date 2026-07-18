package com.rdxindia.evtrack

import android.app.Application
import com.rdxindia.evtrack.data.AppDatabase
import com.rdxindia.evtrack.data.ReadingRepository

class EvTrackApp : Application() {
    val repository: ReadingRepository by lazy {
        ReadingRepository(AppDatabase.get(this).readingDao())
    }
}
