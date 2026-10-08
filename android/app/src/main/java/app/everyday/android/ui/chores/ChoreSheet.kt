package app.everyday.android.ui.chores

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.everyday.android.data.ChoreCompletion
import app.everyday.android.data.ChoreWithDetails
import app.everyday.android.data.MemberEntity
import app.everyday.android.ui.ChoreIcon
import app.everyday.android.ui.formatWeekdays
import java.time.LocalDate

/** Details of a chore tapped on the timetable, with done/undo for today. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChoreSheet(
    item: ChoreWithDetails,
    today: LocalDate,
    membersById: Map<String, MemberEntity>,
    myId: String?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onMarkDone: () -> Unit,
    onUndo: () -> Unit,
    onAddReminder: () -> Unit,
) {
    val c = item.chore
    val todays = item.completions.filter { it.doneOn == today.toString() }
    val doneBy = todays.map { it.memberId }.toSet()
    val assigned = myId != null && myId in item.assigneeIds
    fun name(id: String) = membersById[id]?.displayName ?: "Someone"

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(ChoreIcon, contentDescription = null)
                Text(c.title, style = MaterialTheme.typography.titleLarge)
            }
            Text(
                formatWeekdays(c.weekdays) + " · " +
                    if (c.completion == ChoreCompletion.ANY) "One person does it" else "Each person does it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            c.notes?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                item.assigneeIds.forEach { id ->
                    MemberChip(id, name(id), ticked = c.completion == ChoreCompletion.EACH && id in doneBy)
                }
            }
            if (item.assigneeIds.isEmpty()) {
                Text("Nobody is assigned. Edit the chore to assign someone.", color = MaterialTheme.colorScheme.error)
            }

            if (item.isOn(today)) {
                val status = when {
                    c.completion == ChoreCompletion.ANY && todays.isNotEmpty() -> "Done today by ${name(todays.first().memberId)}"
                    c.completion == ChoreCompletion.EACH && item.isCompleteOn(today) -> "Everyone has done it today"
                    c.completion == ChoreCompletion.EACH -> "${doneBy.count { it in item.assigneeIds }} of ${item.assigneeIds.size} done today"
                    else -> "Not done yet today"
                }
                Text(status, style = MaterialTheme.typography.bodyLarge)
            }

            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (item.isOn(today)) when (c.completion) {
                    ChoreCompletion.ANY -> if (todays.isEmpty()) {
                        Button(onClick = onMarkDone, enabled = !busy) { Text("Mark done") }
                    } else {
                        OutlinedButton(onClick = onUndo, enabled = !busy) { Text("Undo done") }
                    }
                    // Each person ticks only their own part.
                    ChoreCompletion.EACH -> if (assigned) {
                        if (myId !in doneBy) {
                            Button(onClick = onMarkDone, enabled = !busy) { Text("Done my part") }
                        } else {
                            OutlinedButton(onClick = onUndo, enabled = !busy) { Text("Undo my part") }
                        }
                    }
                }
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                if (assigned) OutlinedButton(onClick = onAddReminder) { Text("Add reminder") }
            }
        }
    }
}
