package app.everyday.android.ui.calendar

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import app.everyday.android.data.ChoreWithDetails
import app.everyday.android.data.EventView
import app.everyday.android.data.MemberEntity
import app.everyday.android.data.ReminderMode
import app.everyday.android.data.ReminderView
import app.everyday.android.reminders.MINUTES_PER_DAY
import app.everyday.android.reminders.Window
import app.everyday.android.reminders.isOnWeekday
import app.everyday.android.ui.choreColors
import app.everyday.android.ui.chores.ChoreDetailsSheet
import app.everyday.android.ui.chores.ChoresViewModel
import app.everyday.android.ui.dayShort
import app.everyday.android.ui.ChoreIcon
import app.everyday.android.ui.ReminderIcon
import app.everyday.android.ui.formatMinute
import app.everyday.android.ui.formatWindow
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

private val HOUR_HEIGHT = 56.dp
private val GUTTER = 34.dp
private const val TIMED_LENGTH_MINUTES = 30

/** Something on the timetable: an event, or one of the picked profile's chore reminders. */
private sealed interface Entry {
    /** Selection key, unique across both kinds. */
    val key: String

    data class Event(val view: EventView) : Entry {
        override val key get() = "event:${view.event.id}"
    }

    data class ChoreReminder(val view: ReminderView) : Entry {
        override val key get() = "reminder:${view.reminder.id}"
    }
}

/** One block on the calendar: an event, a chore reminder's time, or one window of a persistent chore reminder. */
private data class CalendarItem(
    val entry: Entry,
    val startMinute: Int,
    val endMinute: Int,
    val label: String,
)

@Composable
fun CalendarScreen(
    onEditEvent: (String?) -> Unit,
    onEditChoreReminder: (String) -> Unit,
    onEditChore: (String) -> Unit,
    onAddChoreReminder: (String) -> Unit,
) {
    val vm: CalendarViewModel = viewModel(factory = CalendarViewModel.Factory)
    val events by vm.events.collectAsStateWithLifecycle()
    val choreReminders by vm.choreReminders.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val entries = remember(events, choreReminders) { events.map { Entry.Event(it) } + choreReminders.map { Entry.ChoreReminder(it) } }
    var weekOffset by rememberSaveable { mutableIntStateOf(0) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    // Day view: the focused day as an epoch day, or null for the week view.
    var focusedEpochDay by rememberSaveable { mutableStateOf<Long?>(null) }
    // Month view (Phase 7): the shown month as "yyyy-MM", the day whose sheet is open, and the item picked there.
    var monthMode by rememberSaveable { mutableStateOf(false) }
    var shownMonth by rememberSaveable { mutableStateOf<String?>(null) }
    var monthDayEpoch by rememberSaveable { mutableStateOf<Long?>(null) }
    var monthItemKey by rememberSaveable { mutableStateOf<String?>(null) }
    val chores by vm.chores.collectAsStateWithLifecycle()
    val monthFilter by vm.monthFilter.collectAsStateWithLifecycle()

    // Ticks every minute so "today" and the now-line stay current.
    val now by produceState(LocalTime.now()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = LocalTime.now()
        }
    }
    val today = remember(now) { LocalDate.now() }
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekOffset.toLong())
    val focusedDay = focusedEpochDay?.let(LocalDate::ofEpochDay)
    val days = if (focusedDay != null) listOf(focusedDay) else (0L until 7L).map { weekStart.plusDays(it) }

    fun backToWeek() {
        val day = focusedDay ?: return
        // Show the week containing the day the user ended up on.
        weekOffset = ChronoUnit.WEEKS.between(
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
            day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
        ).toInt()
        focusedEpochDay = null
    }
    BackHandler(enabled = focusedDay != null) { backToWeek() }

    val month = shownMonth?.let(YearMonth::parse) ?: YearMonth.from(today)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (focusedDay == null) {
                ViewSwitch(monthMode, onChange = { monthMode = it })
            }
            if (monthMode && focusedDay == null) {
                MonthView(
                    month = month,
                    today = today,
                    events = events,
                    chores = chores,
                    members = members,
                    filter = monthFilter,
                    onMonthChange = { shownMonth = it.toString() },
                    onFilterChange = vm::setMonthFilter,
                    onDayClick = { monthDayEpoch = it.toEpochDay() },
                )
            } else if (focusedDay != null) {
                DayHeader(focusedDay, today, onChange = { focusedEpochDay = it.toEpochDay() }, onBackToWeek = ::backToWeek)
            } else {
                WeekHeader(weekStart, weekOffset, onChange = { weekOffset = it })
                DayHeaders(days, today, onDayClick = { focusedEpochDay = it.toEpochDay() })
            }
            AllDayRow(days, today, entries, onSelect = { selectedKey = it })
            HorizontalDivider()
            TimeGrid(days, today, now, entries, detailed = focusedDay != null, onSelect = { selectedKey = it })
        }
        if (entries.isEmpty() && !monthMode) {
            Text(
                "Nothing on your timetable yet.\nTap + to add an event.",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FloatingActionButton(
            onClick = { onEditEvent(null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "Add event") }
    }

    when (val selected = entries.firstOrNull { it.key == selectedKey }) {
        is Entry.Event -> EventDetailsSheet(vm, selected.view, today, members, onDismiss = { selectedKey = null }, onEditEvent = onEditEvent)
        is Entry.ChoreReminder -> {
            val r = selected.view.reminder
            ReminderSheet(
                item = selected.view,
                today = today,
                onDismiss = { selectedKey = null },
                onEdit = { selectedKey = null; onEditChoreReminder(r.id) },
                onToggleActive = { vm.setActive(r.id, !r.active) },
                onToggleDone = { done -> vm.setDoneToday(r.id, done) },
            )
        }
        null -> {}
    }

    MonthSheets(
        vm = vm,
        events = events,
        chores = chores,
        members = members,
        filter = effectiveFilter(monthFilter, members),
        today = today,
        day = monthDayEpoch?.let(LocalDate::ofEpochDay),
        itemKey = monthItemKey,
        onDayDismiss = { monthDayEpoch = null },
        onSelect = { monthItemKey = it },
        onItemDismiss = { monthItemKey = null },
        onEditEvent = onEditEvent,
        onEditChore = onEditChore,
        onAddChoreReminder = onAddChoreReminder,
    )
}

/** Week | Month switch at the top of the Events tab. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewSwitch(month: Boolean, onChange: (Boolean) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        listOf(false to "Week", true to "Month").forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = month == value,
                onClick = { onChange(value) },
                shape = SegmentedButtonDefaults.itemShape(i, 2),
            ) { Text(label) }
        }
    }
}

/**
 * The month view's sheets: the tapped day's list, and over it the event or chore picked there. The day
 * sheet stays open underneath, so closing an item returns to the day.
 */
@Composable
private fun MonthSheets(
    vm: CalendarViewModel,
    events: List<EventView>,
    chores: List<ChoreWithDetails>,
    members: List<MemberEntity>,
    filter: MonthFilter,
    today: LocalDate,
    day: LocalDate?,
    itemKey: String?,
    onDayDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onItemDismiss: () -> Unit,
    onEditEvent: (String) -> Unit,
    onEditChore: (String) -> Unit,
    onAddChoreReminder: (String) -> Unit,
) {
    if (day == null) return
    val items = monthItemsOn(day, events, chores, filter)
    val selected = items.firstOrNull { it.key == itemKey }
    if (selected == null) {
        MonthDaySheet(day, items, members, onDismiss = onDayDismiss, onSelect = { onSelect(it.key) })
        return
    }
    when (selected) {
        is MonthItem.Event -> EventDetailsSheet(vm, selected.view, today, members, onDismiss = onItemDismiss, onEditEvent = onEditEvent)
        is MonthItem.Chore -> {
            val choresVm: ChoresViewModel = viewModel(factory = ChoresViewModel.Factory)
            ChoreDetailsSheet(choresVm, selected.details, today, onDismiss = onItemDismiss, onEditChore = onEditChore, onAddReminder = onAddChoreReminder)
        }
    }
}

/** [EventSheet] wired to [vm]: shown from the Events timetable and from the Reminders list. */
@Composable
fun EventDetailsSheet(
    vm: CalendarViewModel,
    view: EventView,
    today: LocalDate,
    members: List<MemberEntity>,
    onDismiss: () -> Unit,
    onEditEvent: (String) -> Unit,
) {
    val id = view.event.id
    EventSheet(
        item = view,
        today = today,
        memberNames = members.associate { it.id to it.displayName },
        onDismiss = onDismiss,
        onEdit = { onDismiss(); onEditEvent(id) },
        onDelete = { onDismiss(); vm.deleteEvent(id) },
        onLeave = { onDismiss(); vm.leaveEvent(id) },
        onAddTime = { minute, relative -> vm.addEventTime(id, minute, relative) },
        onRemoveTime = { minute, relative -> vm.removeEventTime(id, minute, relative) },
        onSetPersistent = { vm.setPersistent(id, it) },
        onSetActive = { vm.setEventActive(id, it) },
        onToggleDone = { done -> view.persistentReminder?.let { vm.setDoneToday(it.reminder.id, done) } },
    )
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

/** Header for the day view: the date, arrows to move a day, and a way back to the week. */
@Composable
private fun DayHeader(day: LocalDate, today: LocalDate, onChange: (LocalDate) -> Unit, onBackToWeek: () -> Unit) {
    val fmt = DateTimeFormatter.ofPattern("EEEE d MMM")
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onChange(day.minusDays(1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        Text(
            if (day == today) "Today, ${day.format(DateTimeFormatter.ofPattern("d MMM"))}" else day.format(fmt),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onBackToWeek) { Text("Week") }
        IconButton(onClick = { onChange(day.plusDays(1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

@Composable
private fun DayHeaders(days: List<LocalDate>, today: LocalDate, onDayClick: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Spacer(Modifier.width(GUTTER))
        for (day in days) {
            val isToday = day == today
            Column(
                Modifier.weight(1f).clickable(onClickLabel = "Open day") { onDayClick(day) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
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

/** All-day events and all-day chore reminders, shown above the hour grid like calendar apps do. */
@Composable
private fun AllDayRow(
    days: List<LocalDate>,
    today: LocalDate,
    entries: List<Entry>,
    onSelect: (String) -> Unit,
) {
    val allDay = entries.filter { it.isAllDay() }
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
                allDay.filter { it.isOn(day) }.forEach { entry ->
                    EntryBlock(entry, entry.title(), day, today, Modifier.fillMaxWidth().height(22.dp)) { onSelect(entry.key) }
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
    entries: List<Entry>,
    detailed: Boolean,
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
                val items = itemsFor(day, entries, detailed)
                val lanes = assignLanes(items)
                val laneCount = (lanes.maxOrNull() ?: 0) + 1
                val laneWidth = maxWidth / laneCount
                items.forEachIndexed { i, item ->
                    val top = minutesToDp(item.startMinute)
                    val height = maxOf(minutesToDp(item.endMinute - item.startMinute), 20.dp)
                    EntryBlock(
                        item.entry,
                        item.label,
                        day,
                        today,
                        Modifier.offset(x = laneWidth * lanes[i], y = top).width(laneWidth).height(height).padding(1.dp),
                        // Blocks too short for two lines would show a clipped second line.
                        singleLine = height < 32.dp,
                    ) { onSelect(item.entry.key) }
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
private fun EntryBlock(
    entry: Entry,
    label: String,
    day: LocalDate,
    today: LocalDate,
    modifier: Modifier,
    singleLine: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val persistent = when (entry) {
        is Entry.Event -> entry.view.persistentReminder
        is Entry.ChoreReminder -> entry.view.takeIf { it.reminder.mode == ReminderMode.PERSISTENT }
    }
    val doneToday = day == today && persistent != null && persistent.state?.doneOn == today.toString()
    val (bg, fg) = when {
        doneToday -> colors.surfaceVariant to colors.onSurfaceVariant
        entry is Entry.ChoreReminder -> colors.choreColors()
        else -> colors.tertiaryContainer to colors.onTertiaryContainer
    }
    // Events show a bell when they remind me; paused reminders fade.
    val reminding = when (entry) {
        is Entry.Event -> entry.view.reminders.any { it.reminder.active }
        is Entry.ChoreReminder -> entry.view.reminder.active
    }
    val faded = when (entry) {
        is Entry.Event -> entry.view.paused
        is Entry.ChoreReminder -> !entry.view.reminder.active
    }
    Row(
        modifier
            .alpha(if (faded) 0.4f else 1f)
            .background(bg, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val icon = when {
            entry is Entry.ChoreReminder -> ChoreIcon
            reminding -> ReminderIcon
            else -> null
        }
        icon?.let { Icon(it, contentDescription = null, tint = fg, modifier = Modifier.padding(top = 1.dp).size(9.dp)) }
        Text(
            (if (doneToday) "✓ " else "") + label,
            color = fg,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
            fontWeight = FontWeight.Medium,
            overflow = TextOverflow.Ellipsis,
            maxLines = if (singleLine) 1 else Int.MAX_VALUE,
        )
    }
}

private fun minutesToDp(minutes: Int): Dp = HOUR_HEIGHT * (minutes / 60f)

private fun Entry.title() = when (this) {
    is Entry.Event -> view.event.title
    is Entry.ChoreReminder -> view.title
}

private fun Entry.isOn(day: LocalDate) = when (this) {
    is Entry.Event -> view.isOn(day)
    is Entry.ChoreReminder -> isOnWeekday(view.reminder.weekdays, day)
}

private fun Entry.isAllDay() = when (this) {
    is Entry.Event -> view.event.startMinute == null
    is Entry.ChoreReminder -> view.reminder.mode == ReminderMode.PERSISTENT && view.windows.any { Window(it.startMinute, it.endMinute).isAllDay }
}

/** Calendar blocks for one day. [detailed] (day view) adds times to the labels; the week view has no room. */
private fun itemsFor(day: LocalDate, entries: List<Entry>, detailed: Boolean): List<CalendarItem> =
    entries.filter { it.isOn(day) }.flatMap { entry ->
        when (entry) {
            is Entry.Event -> {
                val e = entry.view.event
                val start = e.startMinute ?: return@flatMap emptyList()
                val end = e.endMinute ?: minOf(start + TIMED_LENGTH_MINUTES, MINUTES_PER_DAY)
                val label = if (detailed) "${formatMinute(start)}  ${e.title}" else e.title
                listOf(CalendarItem(entry, start, end, label))
            }
            is Entry.ChoreReminder -> {
                val r = entry.view
                when (r.reminder.mode) {
                    ReminderMode.TIMED -> r.times.map {
                        CalendarItem(
                            entry,
                            it.minuteOfDay,
                            minOf(it.minuteOfDay + TIMED_LENGTH_MINUTES, MINUTES_PER_DAY),
                            if (detailed) "${formatMinute(it.minuteOfDay)}  ${r.title}" else r.title,
                        )
                    }
                    ReminderMode.PERSISTENT -> r.windows
                        .filterNot { Window(it.startMinute, it.endMinute).isAllDay }
                        .map {
                            val window = Window(it.startMinute, it.endMinute)
                            CalendarItem(entry, it.startMinute, it.endMinute, if (detailed) "${r.title}  ${formatWindow(window)}" else r.title)
                        }
                }
            }
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
