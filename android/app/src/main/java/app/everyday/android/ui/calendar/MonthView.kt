package app.everyday.android.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.everyday.android.data.BusyEventEntity
import app.everyday.android.data.ChoreWithDetails
import app.everyday.android.data.EventView
import app.everyday.android.data.MemberEntity
import app.everyday.android.ui.ChoreIcon
import app.everyday.android.ui.EventIcon
import app.everyday.android.ui.choreColors
import app.everyday.android.ui.dayLetter
import app.everyday.android.ui.formatMinute
import app.everyday.android.ui.memberColorOf
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** How many items a month cell lists before "+N". */
private const val CELL_ITEMS = 3

/**
 * The month view of the Events tab (Phase 7): a month grid of the household's chores and the events the
 * picked profile sees, for the members in [filter]. Tapping a day opens a sheet listing that day.
 */
@Composable
fun MonthView(
    month: YearMonth,
    today: LocalDate,
    events: List<EventView>,
    chores: List<ChoreWithDetails>,
    busy: List<BusyEventEntity>,
    members: List<MemberEntity>,
    filter: MonthFilter,
    onMonthChange: (YearMonth) -> Unit,
    onFilterChange: (MonthFilter) -> Unit,
    onDayClick: (LocalDate) -> Unit,
) {
    // Members removed since the filter was saved don't count; with nobody left, show everyone.
    val effective = effectiveFilter(filter, members)
    val days = remember(month) { monthDays(month) }
    val membersById = members.associateBy { it.id }
    var pickingMembers by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onMonthChange(month.minusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous month")
            }
            Text(
                month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (month != YearMonth.from(today)) TextButton(onClick = { onMonthChange(YearMonth.from(today)) }) { Text("This month") }
            IconButton(onClick = { onMonthChange(month.plusMonths(1)) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next month")
            }
        }
        // Who shows: only worth offering in a household with others in it.
        if (members.size > 1) {
            TextButton(onClick = { pickingMembers = true }, modifier = Modifier.padding(start = 8.dp)) {
                Text("Showing: " + filterLabel(effective, members))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            DayOfWeek.entries.forEach { d ->
                Text(
                    dayLetter(d),
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        HorizontalDivider()
        Column(Modifier.fillMaxSize().padding(bottom = 80.dp)) {
            days.chunked(7).forEach { week ->
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    week.forEach { day ->
                        DayCell(
                            day = day,
                            inMonth = YearMonth.from(day) == month,
                            isToday = day == today,
                            items = monthItemsOn(day, events, chores, effective, busy),
                            membersById = membersById,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = { onDayClick(day) },
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    if (pickingMembers) {
        MemberFilterDialog(effective, members, onDismiss = { pickingMembers = false }) {
            onFilterChange(it)
            pickingMembers = false
        }
    }
}

/** [filter] limited to current members; everyone when none of its members are left. */
fun effectiveFilter(filter: MonthFilter, members: List<MemberEntity>): MonthFilter {
    if (filter.everyone) return filter
    val current = members.map { it.id }.toSet()
    val limited = MonthFilter(everyone = false, filter.eventMemberIds intersect current, filter.choreMemberIds intersect current)
    return if (limited.memberIds.isEmpty()) MonthFilter() else limited
}

/** "Everyone", "Alex, Jo (chores)", "Alex (events)" or "3 people". */
private fun filterLabel(filter: MonthFilter, members: List<MemberEntity>): String {
    if (filter.everyone) return "Everyone"
    val shown = members.filter { it.id in filter.memberIds }
    if (shown.size > 2) return "${shown.size} people"
    return shown.joinToString(", ") { m ->
        when {
            m.id !in filter.choreMemberIds -> "${m.displayName} (events)"
            m.id !in filter.eventMemberIds -> "${m.displayName} (chores)"
            else -> m.displayName
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate,
    inMonth: Boolean,
    isToday: Boolean,
    items: List<MonthItem>,
    membersById: Map<String, MemberEntity>,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.clickable(onClickLabel = "Open day", onClick = onClick).padding(1.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).size(20.dp)
                .background(if (isToday) colors.primary else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isToday -> colors.onPrimary
                    inMonth -> colors.onSurface
                    else -> colors.onSurfaceVariant.copy(alpha = 0.5f)
                },
            )
        }
        items.take(CELL_ITEMS).forEach { item ->
            val (bg, fg) = itemColors(item, membersById)
            Text(
                item.title,
                modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(2.dp)).padding(horizontal = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
        if (items.size > CELL_ITEMS) {
            Text(
                "+${items.size - CELL_ITEMS}",
                modifier = Modifier.padding(horizontal = 2.dp),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp),
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * Same colours as the week timetable: chore green for chores, the event colour for events. Busy events
 * take their owner's member colour, so you can tell whose they are.
 */
@Composable
private fun itemColors(item: MonthItem, membersById: Map<String, MemberEntity>): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    return when (item) {
        is MonthItem.Chore -> colors.choreColors()
        is MonthItem.Event -> colors.tertiaryContainer to colors.onTertiaryContainer
        is MonthItem.Busy -> {
            val c = memberColorOf(membersById[item.busy.ownerMemberId]?.color, item.busy.ownerMemberId)
            c to if (c.luminance() > 0.5f) Color.Black else Color.White
        }
    }
}

/**
 * Everyone, or per member their events and their chores, each on or off. Done needs something showing;
 * everything ticked saves as Everyone, so members added later show too.
 */
@Composable
private fun MemberFilterDialog(current: MonthFilter, members: List<MemberEntity>, onDismiss: () -> Unit, onDone: (MonthFilter) -> Unit) {
    val all = members.map { it.id }.toSet()
    var events by remember { mutableStateOf(if (current.everyone) all else current.eventMemberIds) }
    var chores by remember { mutableStateOf(if (current.everyone) all else current.choreMemberIds) }
    val everyone = events == all && chores == all
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Show chores and events of") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CheckRow("Everyone", everyone, color = null) { on ->
                    events = if (on) all else emptySet()
                    chores = if (on) all else emptySet()
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    ColumnLabel("Events")
                    ColumnLabel("Chores")
                }
                members.forEach { m ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.padding(start = 12.dp, end = 8.dp).size(10.dp).background(memberColorOf(m.color, m.id), CircleShape))
                        Text(m.displayName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Box(Modifier.width(FILTER_COLUMN), contentAlignment = Alignment.Center) {
                            Checkbox(checked = m.id in events, onCheckedChange = { on -> events = if (on) events + m.id else events - m.id })
                        }
                        Box(Modifier.width(FILTER_COLUMN), contentAlignment = Alignment.Center) {
                            Checkbox(checked = m.id in chores, onCheckedChange = { on -> chores = if (on) chores + m.id else chores - m.id })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onDone(if (everyone) MonthFilter() else MonthFilter(everyone = false, eventMemberIds = events, choreMemberIds = chores)) },
                enabled = events.isNotEmpty() || chores.isNotEmpty(),
            ) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Width of the Events and Chores checkbox columns in the filter dialog. */
private val FILTER_COLUMN = 64.dp

@Composable
private fun ColumnLabel(text: String) {
    Text(
        text,
        modifier = Modifier.width(FILTER_COLUMN),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, color: Color?, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        color?.let { Box(Modifier.size(10.dp).background(it, CircleShape)) }
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Everything the month shows on [day], each opening its event or chore sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonthDaySheet(day: LocalDate, items: List<MonthItem>, members: List<MemberEntity>, onDismiss: () -> Unit, onSelect: (MonthItem) -> Unit) {
    val names = members.associate { it.id to it.displayName }
    val membersById = members.associateBy { it.id }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(day.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), style = MaterialTheme.typography.titleLarge)
            if (items.isEmpty()) {
                Text("Nothing on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items.forEach { item ->
                val (bg, fg) = itemColors(item, membersById)
                Row(
                    // Busy events have nothing more to show, so they don't open anything.
                    Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp))
                        .clickable(enabled = item !is MonthItem.Busy) { onSelect(item) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(if (item is MonthItem.Chore) ChoreIcon else EventIcon, contentDescription = null, tint = fg)
                    Column(Modifier.weight(1f)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = fg)
                        Text(daySheetDetail(item, names), style = MaterialTheme.typography.bodySmall, color = fg)
                    }
                }
            }
        }
    }
}

/** "16:00 – 17:00", "All day · shared by Sam", "Chore · Sam, Alex", or "18:00 – 19:00 · Alex" for Busy. */
private fun daySheetDetail(item: MonthItem, names: Map<String, String>): String = when (item) {
    is MonthItem.Busy -> timeRange(item.busy.startMinute, item.busy.endMinute) + " · " + (names[item.busy.ownerMemberId] ?: "a removed member")
    is MonthItem.Chore -> "Chore · " + item.details.assigneeIds.joinToString(", ") { names[it] ?: "a removed member" }
    is MonthItem.Event -> {
        val e = item.view.event
        val time = timeRange(e.startMinute, e.endMinute)
        if (item.view.isOwn) time else "$time · shared by ${names[e.ownerMemberId] ?: "a removed member"}"
    }
}

private fun timeRange(start: Int?, end: Int?): String = when {
    start == null -> "All day"
    end == null -> formatMinute(start)
    else -> "${formatMinute(start)} – ${formatMinute(end)}"
}
