package com.miyakz.worklog.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miyakz.worklog.BuildConfig
import com.miyakz.worklog.data.local.RecordEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_LABEL_FORMAT = DateTimeFormatter.ofPattern("M/d(E)", Locale.JAPANESE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(viewModel: TodayViewModel) {
    val records by viewModel.records.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    var showDatePicker by remember { mutableStateOf(false) }
    val isToday = selectedDate == LocalDate.now()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    DateNavigator(
                        date = selectedDate,
                        isToday = isToday,
                        onPreviousDay = viewModel::goToPreviousDay,
                        onNextDay = viewModel::goToNextDay,
                        onDateClick = { showDatePicker = true },
                    )
                },
                actions = {
                    IconButton(onClick = viewModel::syncManually) {
                        if (viewModel.isSyncing) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                        } else {
                            Icon(Icons.Filled.Sync, contentDescription = "同期")
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(),
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isToday) {
                    Text(
                        "${selectedDate.format(DATE_LABEL_FORMAT)} の記録として追加されます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp),
                    )
                }
                RecordInput(
                    text = viewModel.inputText,
                    suggestions = viewModel.suggestions,
                    onTextChange = viewModel::onInputChanged,
                    onSuggestionSelected = viewModel::selectSuggestion,
                    onSubmit = viewModel::addRecord,
                )
                HorizontalDivider()
                RecordList(
                    records = records,
                    isToday = isToday,
                    onDelete = { viewModel.deleteRecord(it.taskId) },
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) build ${BuildConfig.BUILD_TIME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }

    if (showDatePicker) {
        DateNavigatorPickerDialog(
            initialDate = selectedDate,
            onDismiss = { showDatePicker = false },
            onDateSelected = { date ->
                viewModel.goToDate(date)
                showDatePicker = false
            },
        )
    }
}

@Composable
private fun DateNavigator(
    date: LocalDate,
    isToday: Boolean,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onDateClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onPreviousDay) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "前日")
        }
        Text(
            text = if (isToday) "本日" else date.format(DATE_LABEL_FORMAT),
            modifier = Modifier.clickable(onClick = onDateClick),
        )
        IconButton(onClick = onNextDay, enabled = !isToday) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "翌日")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateNavigatorPickerDialog(
    initialDate: LocalDate,
    onDismiss: () -> Unit,
    onDateSelected: (LocalDate) -> Unit,
) {
    // DatePicker operates on UTC-midnight millis for calendar dates,
    // independent of the device's local timezone.
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                utcTimeMillis <= System.currentTimeMillis()
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    onDateSelected(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                } ?: onDismiss()
            }) {
                Text("選択")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル")
            }
        },
    ) {
        DatePicker(state = datePickerState)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordInput(
    text: String,
    suggestions: List<String>,
    onTextChange: (String) -> Unit,
    onSuggestionSelected: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    ExposedDropdownMenuBox(
        expanded = suggestions.isNotEmpty(),
        onExpandedChange = {},
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("作業内容") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable, enabled = true),
            trailingIcon = {
                IconButton(onClick = onSubmit) {
                    Text("追加")
                }
            },
        )
        ExposedDropdownMenu(
            expanded = suggestions.isNotEmpty(),
            onDismissRequest = {},
        ) {
            suggestions.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = { onSuggestionSelected(suggestion) },
                )
            }
        }
    }
}

@Composable
private fun RecordList(
    records: List<RecordEntity>,
    isToday: Boolean,
    onDelete: (RecordEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (records.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (isToday) "本日の記録はまだありません" else "この日の記録はまだありません",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        items(records, key = { it.taskId }) { record ->
            RecordRow(record = record, onDelete = { onDelete(record) })
        }
    }
}

@Composable
private fun RecordRow(record: RecordEntity, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(record.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatTime(record.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "削除")
        }
    }
}

private fun formatTime(createdAtIso: String): String =
    runCatching {
        Instant.parse(createdAtIso).atZone(ZoneId.systemDefault()).format(TIME_FORMAT)
    }.getOrDefault(createdAtIso)
