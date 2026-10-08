package app.everyday.android.ui.editor

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.everyday.android.data.RecipientStatus
import app.everyday.android.data.ReminderMode
import app.everyday.android.reminders.MINUTES_PER_DAY
import app.everyday.android.reminders.Window
import androidx.compose.foundation.shape.RoundedCornerShape
import app.everyday.android.ui.ChoreIcon
import app.everyday.android.ui.choreColors
import app.everyday.android.ui.dayLetter
import app.everyday.android.ui.formatMinute
import app.everyday.android.ui.formatWindow
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(reminderId: String?, choreId: String?, onDone: () -> Unit) {
    val vm: EditorViewModel = viewModel(
        key = reminderId ?: "new-${choreId.orEmpty()}",
        factory = EditorViewModel.factory(reminderId, choreId),
    )
    val draft = vm.draft
    val members by vm.members.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var addingTime by remember { mutableStateOf(false) }
    var addingWindowStart by remember { mutableStateOf<Int?>(null) }
    var pickingWindowStart by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New reminder" else "Edit reminder") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!vm.isNew) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                    TextButton(onClick = { vm.save(onDone) }) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (!vm.loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            vm.choreTitle?.let {
                val (bg, fg) = MaterialTheme.colorScheme.choreColors()
                Row(
                    Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(ChoreIcon, contentDescription = null, tint = fg)
                    Text(
                        "Chore reminder for \"$it\". Only for you, so it can't be shared. It stays quiet on days the chore is already done.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = fg,
                    )
                }
            }
            OutlinedTextField(
                value = draft.message,
                onValueChange = { text -> vm.update { it.copy(message = text) } },
                label = { Text("Message") },
                modifier = Modifier.fillMaxWidth(),
            )

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ReminderMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = draft.mode == mode,
                        onClick = { vm.update { it.copy(mode = mode) } },
                        shape = SegmentedButtonDefaults.itemShape(i, ReminderMode.entries.size),
                    ) { Text(if (mode == ReminderMode.TIMED) "Timed" else "Persistent") }
                }
            }
            Text(
                if (draft.mode == ReminderMode.TIMED) {
                    "A notification pops up at each time."
                } else {
                    "A notification stays in the notification bar during each time window until you tap Done."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

            if (draft.mode == ReminderMode.TIMED) {
                Section("Times") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.times.forEach { minute ->
                            InputChip(
                                selected = false,
                                onClick = { vm.removeTime(minute) },
                                label = { Text(formatMinute(minute)) },
                                trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", Modifier.size(16.dp)) },
                            )
                        }
                        AssistChip(
                            onClick = { addingTime = true },
                            label = { Text("Add time") },
                            leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp)) },
                        )
                    }
                }
            } else {
                val allDay = draft.windows == listOf(ALL_DAY)
                Section("When it shows") {
                    Row(
                        Modifier.toggleable(value = allDay, role = Role.Switch, onValueChange = { vm.setAllDay(it) }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("All day", Modifier.weight(1f))
                        Switch(checked = allDay, onCheckedChange = null)
                    }
                    if (!allDay) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            draft.windows.forEach { w ->
                                InputChip(
                                    selected = false,
                                    onClick = { vm.removeWindow(w) },
                                    label = { Text(formatWindow(w)) },
                                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", Modifier.size(16.dp)) },
                                )
                            }
                            AssistChip(
                                onClick = { pickingWindowStart = true },
                                label = { Text("Add time window") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, Modifier.size(18.dp)) },
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.toggleable(value = draft.active, role = Role.Switch, onValueChange = { on -> vm.update { it.copy(active = on) } }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Enabled")
                    Text(
                        "Turn off to pause without deleting",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = draft.active, onCheckedChange = null)
            }

            // Chore reminders are never shared.
            if (draft.choreId == null && members.isNotEmpty()) {
                Section("Share with") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        members.forEach { m ->
                            val answer = vm.answers[m.id]?.takeIf { m.id in draft.recipientIds }
                            FilterChip(
                                selected = m.id in draft.recipientIds,
                                onClick = { vm.toggleRecipient(m.id) },
                                label = { Text(m.displayName + answer?.let { " · " + answerLabel(it) }.orEmpty()) },
                            )
                        }
                    }
                    Text(
                        "They get an invite and can opt in or out. Only you can change it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (draft.recipientIds.isNotEmpty()) {
                        Row(
                            Modifier.toggleable(
                                value = draft.ownerReceives,
                                role = Role.Switch,
                                onValueChange = { on -> vm.update { it.copy(ownerReceives = on) } },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Remind me too")
                                Text(
                                    "Turn off for a heads-up meant only for others, like \"I'm out tonight\"",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(checked = draft.ownerReceives, onCheckedChange = null)
                        }
                    }
                }
            }

            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (addingTime) {
        TimeDialog("Add time", initialMinute = 9 * 60, onDismiss = { addingTime = false }) {
            vm.addTime(it)
            addingTime = false
        }
    }
    if (pickingWindowStart) {
        TimeDialog("Window starts", initialMinute = 16 * 60, onDismiss = { pickingWindowStart = false }) {
            pickingWindowStart = false
            addingWindowStart = it
        }
    }
    addingWindowStart?.let { start ->
        TimeDialog("Window ends", initialMinute = minOf(start + 60, 23 * 60 + 59), onDismiss = { addingWindowStart = null }) { end ->
            // Picking 00:00 as the end means midnight at the end of the day.
            vm.addWindow(Window(start, if (end == 0) MINUTES_PER_DAY else end))
            addingWindowStart = null
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete reminder?") },
            text = { Text("This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onDone) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

private fun answerLabel(status: RecipientStatus) = when (status) {
    RecipientStatus.PENDING -> "pending"
    RecipientStatus.ACCEPTED -> "in"
    RecipientStatus.DECLINED -> "out"
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

@Composable
internal fun DayToggle(letter: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp)
            .background(if (selected) colors.primary else colors.surfaceVariant, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = if (selected) colors.onPrimary else colors.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimeDialog(title: String, initialMinute: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val state = rememberTimePickerState(
        initialHour = initialMinute / 60,
        initialMinute = initialMinute % 60,
        is24Hour = DateFormat.is24HourFormat(LocalContext.current),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onConfirm(state.hour * 60 + state.minute) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
