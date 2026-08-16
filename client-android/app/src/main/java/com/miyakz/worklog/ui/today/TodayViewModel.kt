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
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodayViewModel(
    private val recordRepository: RecordRepository,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val records: StateFlow<List<RecordEntity>> = _selectedDate
        .flatMapLatest { date -> recordRepository.observeRecordsForDate(date) }
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
        val date = _selectedDate.value
        viewModelScope.launch {
            recordRepository.addRecord(text, date)
            inputText = ""
            suggestions = emptyList()
        }
    }

    fun deleteRecord(taskId: String) {
        viewModelScope.launch { recordRepository.deleteRecord(taskId) }
    }

    fun goToPreviousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun goToNextDay() {
        val next = _selectedDate.value.plusDays(1)
        if (!next.isAfter(LocalDate.now())) {
            _selectedDate.value = next
        }
    }

    fun goToDate(date: LocalDate) {
        if (!date.isAfter(LocalDate.now())) {
            _selectedDate.value = date
        }
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
