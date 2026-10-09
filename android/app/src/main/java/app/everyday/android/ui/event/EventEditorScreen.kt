package app.everyday.android.ui.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.everyday.android.reminders.MINUTES_PER_DAY
import app.everyday.android.ui.dayLetter
import app.everyday.android.ui.editor.DayToggle
import app.everyday.android.ui.editor.Section
import app.everyday.android.ui.editor.TimeDialog
import app.everyday.android.ui.formatMinute
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditorScreen(eventId: String?, onDone: () -> Unit) {
    val vm: EventEditorViewModel = viewModel(key = eventId ?: "new-event", factory = EventEditorViewModel.factory(eventId))
    val draft = vm.draft
    val members by vm.members.collectAsStateWithLifecycle()
    var pickingDate by remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(false) }
    var pickingEnd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New event" else "Edit event") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = { TextButton(onClick = { vm.save(onDone) }) { Text("Save") } },
            )
        },
    ) { padding ->
        if (!vm.loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = draft.title,
                onValueChange = { text -> vm.update { it.copy(title = text) } },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { text -> vm.update { it.copy(notes = text) } },
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true, false).forEachIndexed { i, weekly ->
                    SegmentedButton(
                        selected = draft.weekly == weekly,
                        onClick = { vm.update { it.copy(weekly = weekly) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(if (weekly) "Every week" else "Once") }
                }
            }

            if (draft.weekly) {
                Section("Days") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DayOfWeek.entries.forEach { day ->
                            val bit = day.value - 1
                            DayToggle(dayLetter(day), selected = draft.weekdays and (1 shl bit) != 0) { vm.toggleDay(bit) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { vm.update { it.copy(weekdays = 0b1111111) } }, label = { Text("Every day") })
                        AssistChip(onClick = { vm.update { it.copy(weekdays = 0b0011111) } }, label = { Text("Weekdays") })
                        AssistChip(onClick = { vm.update { it.copy(weekdays = 0b1100000) } }, label = { Text("Weekends") })
                    }
                }
            } else {
                Section("Date") {
                    AssistChip(
                        onClick = { pickingDate = true },
                        label = { Text(draft.date?.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")) ?: "Choose a date") },
                    )
                }
            }

            val allDay = draft.startMinute == null
            Section("Time") {
                Row(
                    Modifier.toggleable(value = allDay, role = Role.Switch, onValueChange = { vm.setAllDay(it) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("All day", Modifier.weight(1f))
                    Switch(checked = allDay, onCheckedChange = null)
                }
                if (!allDay) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { pickingStart = true }, label = { Text("Starts " + formatMinute(draft.startMinute!!)) })
                        val end = draft.endMinute
                        if (end == null) {
                            AssistChip(
                                onClick = { pickingEnd = true },
                                label = { Text("Add end time") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp)) },
                            )
                        } else {
                            InputChip(
                                selected = false,
                                onClick = { pickingEnd = true },
                                label = { Text("Ends " + formatMinute(end)) },
                                trailingIcon = {
                                    IconButton(onClick = { vm.update { it.copy(endMinute = null) } }, Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove end time", Modifier.size(16.dp))
                                    }
                                },
                            )
                        }
                    }
                }
            }

            if (members.isNotEmpty()) {
                Section("Share with") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        members.forEach { m ->
                            FilterChip(selected = m.id in draft.memberIds, onClick = { vm.toggleMember(m.id) }, label = { Text(m.displayName) })
                        }
                    }
                    Text(
                        "It shows on their timetable, and they're told once. Each person adds their own reminders. Only you can change the event.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (draft.date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        vm.update { it.copy(date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()) }
                    }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
    if (pickingStart) {
        TimeDialog("Starts", initialMinute = draft.startMinute ?: (9 * 60), onDismiss = { pickingStart = false }) { start ->
            vm.update { it.copy(startMinute = start, endMinute = it.endMinute?.takeIf { end -> end > start }) }
            pickingStart = false
        }
    }
    if (pickingEnd) {
        val start = draft.startMinute ?: 0
        TimeDialog("Ends", initialMinute = draft.endMinute ?: minOf(start + 60, 23 * 60 + 59), onDismiss = { pickingEnd = false }) { end ->
            // Picking 00:00 as the end means midnight at the end of the day.
            vm.update { it.copy(endMinute = if (end == 0) MINUTES_PER_DAY else end) }
            pickingEnd = false
        }
    }
}
