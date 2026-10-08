package app.everyday.android.ui.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.everyday.android.data.ChoreCompletion
import app.everyday.android.ui.dayLetter
import app.everyday.android.ui.editor.DayToggle
import app.everyday.android.ui.editor.Section
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChoreEditorScreen(choreId: String?, onDone: () -> Unit) {
    val vm: ChoreEditorViewModel = viewModel(key = "chore-" + (choreId ?: "new"), factory = ChoreEditorViewModel.factory(choreId))
    val draft = vm.draft
    val members by vm.members.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "New chore" else "Edit chore") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (!vm.isNew) {
                        IconButton(onClick = { confirmDelete = true }, enabled = !vm.busy) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                    TextButton(onClick = { vm.save(onDone) }, enabled = !vm.busy) { Text("Save") }
                },
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
                onValueChange = { text -> vm.update { it.copy(title = text.take(60)) } },
                label = { Text("Chore") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.notes,
                onValueChange = { text -> vm.update { it.copy(notes = text.take(500)) } },
                label = { Text("Notes (optional)") },
                modifier = Modifier.fillMaxWidth(),
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

            Section("Who does it") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ChoreCompletion.entries.forEachIndexed { i, type ->
                        SegmentedButton(
                            selected = draft.completion == type,
                            onClick = { vm.update { it.copy(completion = type) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ChoreCompletion.entries.size),
                        ) { Text(if (type == ChoreCompletion.ANY) "One person" else "Each person") }
                    }
                }
                Text(
                    if (draft.completion == ChoreCompletion.ANY) {
                        "Done for the day once anyone marks it done."
                    } else {
                        "Every assigned person ticks off their own part."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Assigned to") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    members.forEach { m ->
                        FilterChip(
                            selected = m.id in draft.assigneeIds,
                            onClick = { vm.toggleAssignee(m.id) },
                            label = { Text(m.displayName) },
                        )
                    }
                }
            }

            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete chore?") },
            text = { Text("It's removed for the whole household, with everyone's reminders for it.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onDone) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
