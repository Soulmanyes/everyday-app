package app.everyday.android.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderWithDetails
import app.everyday.android.reminders.Window
import app.everyday.android.reminders.isOnWeekday
import app.everyday.android.ui.formatMinute
import app.everyday.android.ui.formatWeekdays
import app.everyday.android.ui.formatWindow
import java.time.LocalDate

/** Details and quick actions for a reminder tapped on the calendar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderSheet(
    item: ReminderWithDetails,
    today: LocalDate,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onToggleActive: () -> Unit,
    onToggleDone: (Boolean) -> Unit,
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
            Text(r.message, style = MaterialTheme.typography.titleLarge)
            Text(
                (if (persistent) "Persistent" else "Timed") + " · " + formatWeekdays(r.weekdays),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(schedule, style = MaterialTheme.typography.bodyLarge)
            if (!r.active) Text("Paused", color = MaterialTheme.colorScheme.error)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onEdit) { Text("Edit") }
                OutlinedButton(onClick = onToggleActive) { Text(if (r.active) "Pause" else "Resume") }
                if (persistent && r.active && isOnWeekday(r.weekdays, today)) {
                    OutlinedButton(onClick = { onToggleDone(!doneToday) }) {
                        Text(if (doneToday) "Undo done" else "Done today")
                    }
                }
            }
        }
    }
}
