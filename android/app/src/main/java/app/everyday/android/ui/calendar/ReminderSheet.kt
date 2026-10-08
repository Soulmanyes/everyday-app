package app.everyday.android.ui.calendar

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
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderView
import app.everyday.android.reminders.Window
import app.everyday.android.reminders.isOnWeekday
import app.everyday.android.ui.formatMinute
import app.everyday.android.ui.kindIcon
import app.everyday.android.ui.formatWeekdays
import app.everyday.android.ui.formatWindow
import java.time.LocalDate

/** Details and quick actions for a reminder tapped on the calendar. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReminderSheet(
    item: ReminderView,
    today: LocalDate,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onToggleActive: () -> Unit,
    onToggleDone: (Boolean) -> Unit,
    /** Display names by member id, for shared reminders. */
    memberNames: Map<String, String>,
    onOptOut: () -> Unit,
) {
    val r = item.reminder
    val persistent = r.mode == ReminderMode.PERSISTENT
    val schedule = if (persistent) {
        item.windows.sortedBy { it.startMinute }.joinToString(", ") { formatWindow(Window(it.startMinute, it.endMinute)) }
    } else {
        item.times.map { it.minuteOfDay }.sorted().joinToString(", ") { formatMinute(it) }
    }
    val doneToday = item.state?.doneOn == today.toString()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(kindIcon(r.choreId), contentDescription = null)
                Text(r.message, style = MaterialTheme.typography.titleLarge)
            }
            Text(
                if (r.choreId != null) "Chore reminder · only for you, can't be shared" else "Reminder · can be shared",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                (if (persistent) "Persistent" else "Timed") + " · " + formatWeekdays(r.weekdays),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(schedule, style = MaterialTheme.typography.bodyLarge)
            if (!r.active) Text("Paused", color = MaterialTheme.colorScheme.error)
            if (!item.isOwn) {
                Text(
                    "Shared by ${memberNames[r.ownerMemberId] ?: "a removed member"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (item.recipients.isNotEmpty()) {
                Text(
                    "Shared with " + item.recipients.joinToString(", ") { memberNames[it.memberId] ?: "a removed member" } +
                        if (!r.ownerReceives) " · not reminding you" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Only the owner edits or pauses; an invitee's own switch is opting out.
                if (item.isOwn) {
                    Button(onClick = onEdit) { Text("Edit") }
                    OutlinedButton(onClick = onToggleActive) { Text(if (r.active) "Pause" else "Resume") }
                } else {
                    OutlinedButton(onClick = onOptOut) {
                        Text(if (r.ownerMemberId in memberNames) "Opt out" else "Remove for me")
                    }
                }
                if (persistent && r.active && isOnWeekday(r.weekdays, today)) {
                    OutlinedButton(onClick = { onToggleDone(!doneToday) }) {
                        Text(if (doneToday) "Undo done" else "Done today")
                    }
                }
            }
        }
    }
}
