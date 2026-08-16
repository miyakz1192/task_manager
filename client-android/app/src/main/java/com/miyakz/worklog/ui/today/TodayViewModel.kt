package com.miyakz.worklog.ui.today

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.miyakz.worklog.WorkLogApp
import com.miyakz.worklog.data.local.RecordEntity
import com.miyakz.worklog.data.repository.RecordRepository
import com.miyakz.worklog.data.repository.SyncRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodayViewModel(
    private val recordRepository: RecordRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    val todayRecords: StateFlow<List<RecordEntity>> = recordRepository.observeTodayRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var inputText by mutableStateOf("")
        private set

    var suggestions by mutableStateOf<List<String>>(emptyList())
        private set

    var isSyncing by mutableStateOf(false)
        private set

    init {
        // Best-effort auto-sync on screen launch; failures are silent by design.
        viewModelScope.launch { runSync() }
    }

    fun onInputChanged(text: String) {
        inputText = text
        viewModelScope.launch {
            suggestions = recordRepository.suggestTexts(text)
        }
    }

    fun selectSuggestion(text: String) {
        inputText = text
        suggestions = emptyList()
    }

    fun addRecord() {
        val text = inputText
        if (text.isBlank()) return
        viewModelScope.launch {
            recordRepository.addRecord(text)
            inputText = ""
            suggestions = emptyList()
        }
    }

    fun deleteRecord(taskId: String) {
        viewModelScope.launch { recordRepository.deleteRecord(taskId) }
    }

    fun syncManually() {
        viewModelScope.launch { runSync() }
    }

    private suspend fun runSync() {
        isSyncing = true
        try {
            syncRepository.syncNow()
        } finally {
            isSyncing = false
        }
    }
}

class TodayViewModelFactory(private val app: WorkLogApp) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return TodayViewModel(app.recordRepository, app.syncRepository) as T
    }
}
