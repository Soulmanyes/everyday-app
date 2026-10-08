package app.everyday.android.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderWithDetails
import app.everyday.android.reminders.Window
import app.everyday.android.reminders.isOnWeekday
import app.everyday.android.ui.dayShort
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val HOUR_HEIGHT = 56.dp
private val GUTTER = 34.dp
private const val TIMED_LENGTH_MINUTES = 30

/** One block on the calendar: a timed reminder's time, or one window of a persistent reminder. */
private data class CalendarItem(
    val reminder: ReminderWithDetails,
    val startMinute: Int,
    val endMinute: Int,
    val label: String,
)

@Composable
fun CalendarScreen(onEditReminder: (String?) -> Unit) {
    val vm: CalendarViewModel = viewModel(factory = CalendarViewModel.Factory)
    val reminders by vm.reminders.collectAsStateWithLifecycle()
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    // Ticks every minute so "today" and the now-line stay current.
    val now by produceState(LocalTime.now()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = LocalTime.now()
        }
    }
    val today = remember(now) { LocalDate.now() }
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
    val days = (0L until 7L).map { weekStart.plusDays(it) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            WeekHeader(weekStart, weekOffset, onChange = { weekOffset = it })
            DayHeaders(days, today)
            AllDayRow(days, today, reminders, onSelect = { selectedId = it })
            HorizontalDivider()
            TimeGrid(days, today, now, reminders, onSelect = { selectedId = it })
        }
        if (reminders.isEmpty()) {
            Text(
                "No reminders yet.\nTap + to add one.",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FloatingActionButton(
            onClick = { onEditReminder(null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "Add reminder") }
    }

    val selected = reminders.firstOrNull { it.reminder.id == selectedId }
    if (selected != null) {
        ReminderSheet(
            item = selected,
            today = today,
            onDismiss = { selectedId = null },
            onEdit = { selectedId = null; onEditReminder(selected.reminder.id) },
            onToggleActive = { vm.setActive(selected.reminder.id, !selected.reminder.active) },
            onToggleDone = { done -> vm.setDoneToday(selected.reminder.id, done) },
        )
    }
}

@Composable
private fun WeekHeader(weekStart: LocalDate, weekOffset: Int, onChange: (Int) -> Unit) {
    val end = weekStart.plusDays(6)
    val fmt = DateTimeFormatter.ofPattern("d MMM")
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(weekOffset - 1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week")
        }
        Text(
            "${weekStart.format(fmt)} – ${end.format(fmt)}",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        if (weekOffset != 0) TextButton(onClick = { onChange(0) }) { Text("This week") }
        IconButton(onClick = { onChange(weekOffset + 1) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week")
        }
    }
}

@Composable
private fun DayHeaders(days: List<LocalDate>, today: LocalDate) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Spacer(Modifier.width(GUTTER))
        for (day in days) {
            val isToday = day == today
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(dayShort(day.dayOfWeek), style = MaterialTheme.typography.labelSmall)
                Box(
                    Modifier.size(26.dp).background(
                        if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent,
                        CircleShape,
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        day.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Persistent reminders with an all-day window, shown above the hour grid like calendar apps do. */
@Composable
private fun AllDayRow(
    days: List<LocalDate>,
    today: LocalDate,
    reminders: List<ReminderWithDetails>,
    onSelect: (String) -> Unit,
) {
    val allDay = reminders.filter { r ->
        r.reminder.mode == ReminderMode.PERSISTENT && r.windows.any { Window(it.startMinute, it.endMinute).isAllDay }
    }
    if (allDay.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Text(
            "all\nday",
            modifier = Modifier.width(GUTTER),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (day in days) {
            Column(Modifier.weight(1f).padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                allDay.filter { isOnWeekday(it.reminder.weekdays, day) }.forEach { r ->
                    ReminderBlock(r, r.reminder.message, day, today, Modifier.fillMaxWidth().height(22.dp)) {
                        onSelect(r.reminder.id)
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeGrid(
    days: List<LocalDate>,
    today: LocalDate,
    now: LocalTime,
    reminders: List<ReminderWithDetails>,
    onSelect: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(Unit) {
        // Start the view an hour before now, or at 07:00 early in the morning.
        val hour = maxOf(7, now.hour - 1).coerceAtMost(16)
        scroll.scrollTo(with(density) { (HOUR_HEIGHT * hour).roundToPx() })
    }
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Column(Modifier.width(GUTTER)) {
            for (hour in 0 until 24) {
                Box(Modifier.height(HOUR_HEIGHT).fillMaxWidth()) {
                    if (hour > 0) {
                        Text(
                            "%02d".format(hour),
                            modifier = Modifier.offset(x = 6.dp, y = (-7).dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        for (day in days) {
            BoxWithConstraints(
                Modifier.weight(1f).height(HOUR_HEIGHT * 24).border(0.5.dp, lineColor.copy(alpha = 0.5f)),
            ) {
                for (hour in 1 until 24) {
                    HorizontalDivider(Modifier.offset(y = HOUR_HEIGHT * hour), thickness = 0.5.dp, color = lineColor)
                }
                val items = itemsFor(day, reminders)
                val lanes = assignLanes(items)
                val laneCount = (lanes.maxOrNull() ?: 0) + 1
                val laneWidth = maxWidth / laneCount
                items.forEachIndexed { i, item ->
                    val top = minutesToDp(item.startMinute)
                    val height = maxOf(minutesToDp(item.endMinute - item.startMinute), 20.dp)
                    ReminderBlock(
                        item.reminder,
                        item.label,
                        day,
                        today,
                        Modifier.offset(x = laneWidth * lanes[i], y = top).width(laneWidth).height(height).padding(1.dp),
                    ) { onSelect(item.reminder.reminder.id) }
                }
                if (day == today) {
                    Box(
                        Modifier.offset(y = minutesToDp(now.hour * 60 + now.minute))
                            .fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.error),
                    )
                }
            }
        }
    }
}

@Composable
private fun ReminderBlock(
    r: ReminderWithDetails,
    label: String,
    day: LocalDate,
    today: LocalDate,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val persistent = r.reminder.mode == ReminderMode.PERSISTENT
    val doneToday = persistent && day == today && r.state?.doneOn == today.toString()
    val colors = MaterialTheme.colorScheme
    val (bg, fg) = when {
        doneToday -> colors.surfaceVariant to colors.onSurfaceVariant
        persistent -> colors.primaryContainer to colors.onPrimaryContainer
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    Box(
        modifier
            .alpha(if (r.reminder.active) 1f else 0.4f)
            .background(bg, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
        Text(
            (if (doneToday) "✓ " else "") + label,
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
            fontWeight = FontWeight.Medium,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun minutesToDp(minutes: Int): Dp = HOUR_HEIGHT * (minutes / 60f)

private fun itemsFor(day: LocalDate, reminders: List<ReminderWithDetails>): List<CalendarItem> =
    reminders.filter { isOnWeekday(it.reminder.weekdays, day) }.flatMap { r ->
        when (r.reminder.mode) {
            ReminderMode.TIMED -> r.times.map {
                CalendarItem(r, it.minuteOfDay, minOf(it.minuteOfDay + TIMED_LENGTH_MINUTES, 1440), r.reminder.message)
            }
            ReminderMode.PERSISTENT -> r.windows
                .filterNot { Window(it.startMinute, it.endMinute).isAllDay }
                .map { CalendarItem(r, it.startMinute, it.endMinute, r.reminder.message) }
        }
    }.sortedWith(compareBy({ it.startMinute }, { -it.endMinute }))

/** Greedy lane assignment so overlapping items sit side by side. Items must be sorted by start. */
private fun assignLanes(items: List<CalendarItem>): List<Int> {
    val laneEnds = mutableListOf<Int>()
    return items.map { item ->
        val lane = laneEnds.indexOfFirst { it <= item.startMinute }
        if (lane >= 0) {
            laneEnds[lane] = item.endMinute
            lane
        } else {
            laneEnds += item.endMinute
            laneEnds.lastIndex
        }
    }
}
