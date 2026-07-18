package com.rdxindia.evtrack.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.rdxindia.evtrack.data.Reading
import com.rdxindia.evtrack.data.ReadingRepository
import kotlinx.coroutines.flow.Flow

class HomeViewModel(repository: ReadingRepository) : ViewModel() {

    val readings: Flow<List<Reading>> = repository.readings

    companion object {
        fun factory(repository: ReadingRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                HomeViewModel(repository) as T
        }
    }
}
