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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
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
import app.everyday.android.data.Sharing
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
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EventEditorScreen(eventId: String?, onDone: () -> Unit) {
    val vm: EventEditorViewModel = viewModel(key = eventId ?: "new-event", factory = EventEditorViewModel.factory(eventId))
    val draft = vm.draft
    val members by vm.members.collectAsStateWithLifecycle()
    var pickingDate by remember { mutableStateOf(false) }
    var pickingEndDate by remember { mutableStateOf(false) }
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
                        onClick = {
                            vm.update {
                                // A weekly event over several days starts on one weekday and lasts under a week.
                                val fits = !weekly || (it.endDays <= 6 && Integer.bitCount(it.weekdays) == 1)
                                it.copy(weekly = weekly, endDays = if (fits) it.endDays else 0)
                            }
                        },
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
                        AssistChip(onClick = { vm.update { it.copy(weekdays = 0b1111111, endDays = 0) } }, label = { Text("Every day") })
                        AssistChip(onClick = { vm.update { it.copy(weekdays = 0b0011111, endDays = 0) } }, label = { Text("Weekdays") })
                        AssistChip(onClick = { vm.update { it.copy(weekdays = 0b1100000, endDays = 0) } }, label = { Text("Weekends") })
                    }
                }
                // Phase 9.6: an event on one day of the week can go on for several days (e.g. Fri to Sun).
                if (Integer.bitCount(draft.weekdays) == 1) {
                    val startDay = DayOfWeek.of(Integer.numberOfTrailingZeros(draft.weekdays) + 1)
                    Section("Ends on") {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            (0..6).forEach { days ->
                                FilterChip(
                                    selected = draft.endDays == days,
                                    onClick = { vm.setEndDays(days) },
                                    label = { Text(if (days == 0) "Same day" else dayName(startDay.plus(days.toLong()))) },
                                )
                            }
                        }
                    }
                }
            } else {
                val dates = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
                Section("Date") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = { pickingDate = true },
                            label = { Text((if (draft.endDays > 0) "Starts " else "") + (draft.date?.format(dates) ?: "Choose a date")) },
                        )
                        // Phase 9.6: an end date makes it last several days.
                        val date = draft.date
                        if (date != null && draft.endDays == 0) {
                            AssistChip(
                                onClick = { pickingEndDate = true },
                                label = { Text("Add end date") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp)) },
                            )
                        } else if (date != null) {
                            InputChip(
                                selected = false,
                                onClick = { pickingEndDate = true },
                                label = { Text("Ends " + date.plusDays(draft.endDays.toLong()).format(dates)) },
                                trailingIcon = {
                                    IconButton(onClick = { vm.setEndDays(0) }, Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Remove end date", Modifier.size(16.dp))
                                    }
                                },
                            )
                        }
                    }
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
                                label = { Text(if (draft.endDays > 0) "Add end time (on the last day)" else "Add end time") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp)) },
                            )
                        } else {
                            InputChip(
                                selected = false,
                                onClick = { pickingEnd = true },
                                label = { Text((if (draft.endDays > 0) "Ends on the last day " else "Ends ") + formatMinute(end)) },
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

            // Two ways to involve someone (Phase 9.8): taking part, or seeing it for scheduling. Everyone covers
            // the whole household, members who join later too; people picked in the other list keep their way.
            if (members.isNotEmpty()) {
                Section("Add to the event") {
                    val all = draft.everyone == Sharing.MEMBER
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = all, onClick = { vm.toggleEveryone(Sharing.MEMBER) }, label = { Text("Everyone") })
                        members.forEach { m ->
                            FilterChip(
                                selected = all || m.id in draft.memberIds,
                                onClick = { vm.toggleMember(m.id, Sharing.MEMBER) },
                                label = { Text(m.displayName) },
                                enabled = !all,
                            )
                        }
                    }
                    Text(
                        (if (all) "Everyone, including people who join later, takes part. " else "They take part. ") +
                            "It's on their timetable, they add their own reminders, and they're told once. Only you can change the event.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (members.isNotEmpty() && draft.everyone != Sharing.MEMBER) {
                Section("Share for scheduling") {
                    val all = draft.everyone == Sharing.SCHEDULING
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = all, onClick = { vm.toggleEveryone(Sharing.SCHEDULING) }, label = { Text("Everyone") })
                        members.forEach { m ->
                            FilterChip(
                                selected = m.id in draft.schedulingIds || (all && m.id !in draft.memberIds),
                                onClick = { vm.toggleMember(m.id, Sharing.SCHEDULING) },
                                label = { Text(m.displayName) },
                                enabled = !all,
                            )
                        }
                    }
                    Text(
                        (if (all) "Everyone else, including people who join later, " else "They ") +
                            "can see the title and times, but they don't take part: no reminders, and they aren't told.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // People it isn't shared with see it as Busy, or not at all (Phase 7; shared events too since 9.8).
            val shared = draft.memberIds.isNotEmpty() || draft.schedulingIds.isNotEmpty()
            if (members.isNotEmpty() && draft.everyone == Sharing.NONE) {
                Section(if (shared) "Everyone else sees" else "Others see") {
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(true to "Busy", false to "Nothing").forEachIndexed { i, (value, label) ->
                            SegmentedButton(
                                selected = draft.showBusy == value,
                                onClick = { vm.update { it.copy(showBusy = value) } },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                            ) { Text(label) }
                        }
                    }
                    Text(
                        if (draft.showBusy) {
                            "They see \"Busy\" at these times, without the title or notes."
                        } else {
                            "They don't see it at all."
                        },
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
    if (pickingEndDate) {
        val start = draft.date ?: LocalDate.now()
        val last = start.plusDays(EventEditorViewModel.MAX_END_DAYS.toLong())
        val utcMillis = { d: LocalDate -> d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = utcMillis(start.plusDays(maxOf(draft.endDays, 1).toLong())),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis in utcMillis(start)..utcMillis(last)
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingEndDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val end = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        vm.setEndDays(ChronoUnit.DAYS.between(start, end).toInt())
                    }
                    pickingEndDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingEndDate = false }) { Text("Cancel") } },
        ) { DatePicker(state = state, title = { Text("Ends on", Modifier.padding(start = 24.dp, top = 16.dp)) }) }
    }
    if (pickingStart) {
        TimeDialog("Starts", initialMinute = draft.startMinute ?: (9 * 60), onDismiss = { pickingStart = false }) { start ->
            // Over several days the end is on another day, so it can be before the start time.
            vm.update { it.copy(startMinute = start, endMinute = it.endMinute?.takeIf { end -> it.endDays > 0 || end > start }) }
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

private fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, Locale.getDefault())
