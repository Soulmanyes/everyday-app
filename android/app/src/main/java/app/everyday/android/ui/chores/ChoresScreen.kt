package app.everyday.android.ui.chores

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.everyday.android.chores.ChoreRules
import app.everyday.android.data.ChoreWithDetails
import app.everyday.android.data.MemberEntity
import app.everyday.android.data.toCompletion
import app.everyday.android.reminders.isOnWeekday
import app.everyday.android.ui.choreColors
import app.everyday.android.data.ChoreSlotEntity
import app.everyday.android.reminders.Window
import app.everyday.android.ui.dayShort
import app.everyday.android.ui.formatMinute
import app.everyday.android.ui.formatWindow
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Member dot colours. No greens: chore cards are green. */
private val MEMBER_COLORS = listOf(
    Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF8E24AA), Color(0xFFFB8C00),
    Color(0xFF6D4C41), Color(0xFF3949AB), Color(0xFFD81B60), Color(0xFF00ACC1),
)

/** Colours handed out in household order, so members don't share one (up to 8 members). */
private val LocalMemberColors = compositionLocalOf { emptyMap<String, Color>() }

@Composable
fun memberColor(id: String): Color =
    LocalMemberColors.current[id] ?: MEMBER_COLORS[Math.floorMod(id.hashCode(), MEMBER_COLORS.size)]

/**
 * An assignee's slots on a chore: the current profile's from its reminders on this phone (shown at
 * once), everyone else's from the server's timetable.
 */
private val LocalChoreSlots = compositionLocalOf<(ChoreWithDetails, String) -> List<ChoreSlotEntity>> { { c, m -> c.slots.filter { it.memberId == m } } }

@Composable
fun slotsOf(chore: ChoreWithDetails, memberId: String) = LocalChoreSlots.current(chore, memberId)

fun formatSlot(slot: ChoreSlotEntity) = slot.endMinute?.let { formatWindow(Window(slot.startMinute, it)) } ?: formatMinute(slot.startMinute)

fun ChoreWithDetails.isCompleteOn(date: LocalDate) =
    ChoreRules.isComplete(chore.completion, assigneeIds, completions.map { it.toCompletion() }, date)

/**
 * The weekly chore timetable: a column per weekday with each chore and its assignees.
 * Tapping a day header opens that day full width; tapping a chore opens its sheet.
 */
@Composable
fun ChoresScreen(onEditChore: (String?) -> Unit, onAddReminder: (String) -> Unit) {
    val vm: ChoresViewModel = viewModel(factory = ChoresViewModel.Factory)
    val chores by vm.chores.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    // Checks every minute so "today" rolls over at midnight while the screen is open.
    val today by produceState(LocalDate.now()) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            value = LocalDate.now()
        }
    }

    if (!session.joined) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Chores are shared with your household.\nJoin one in Settings to see the timetable.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val membersById = members.associateBy { it.id }
    val colors = members.mapIndexed { i, m -> m.id to MEMBER_COLORS[i % MEMBER_COLORS.size] }.toMap()
    val mySlots by vm.mySlots.collectAsStateWithLifecycle()
    val myId = session.memberId
    val slots: (ChoreWithDetails, String) -> List<ChoreSlotEntity> = { c, m ->
        if (m == myId) mySlots[c.chore.id].orEmpty() else c.slots.filter { it.memberId == m }
    }
    CompositionLocalProvider(LocalMemberColors provides colors, LocalChoreSlots provides slots) {
        ChoresContent(vm, chores, members, membersById, myId, today, onEditChore, onAddReminder)
    }
}

@Composable
private fun ChoresContent(
    vm: ChoresViewModel,
    chores: List<ChoreWithDetails>,
    members: List<MemberEntity>,
    membersById: Map<String, MemberEntity>,
    myId: String?,
    today: LocalDate,
    onEditChore: (String?) -> Unit,
    onAddReminder: (String) -> Unit,
) {
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedDay by rememberSaveable { mutableStateOf<DayOfWeek?>(null) }
    BackHandler(enabled = focusedDay != null) { focusedDay = null }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            vm.error?.let { message ->
                Text(
                    message,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable(onClickLabel = "Dismiss") { vm.error = null }
                        .padding(12.dp),
                )
            }
            val day = focusedDay
            if (day != null) {
                DayView(day, today, chores, membersById, onBack = { focusedDay = null }, onSelect = { selectedId = it })
            } else {
                WeekView(today, chores, members, onDayClick = { focusedDay = it }, onSelect = { selectedId = it })
            }
        }
        if (chores.isEmpty()) {
            Text(
                "No chores yet.\nTap + to add one.",
                modifier = Modifier.align(Alignment.Center),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FloatingActionButton(
            onClick = { onEditChore(null) },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Icon(Icons.Default.Add, contentDescription = "Add chore") }
    }

    val selected = chores.firstOrNull { it.chore.id == selectedId }
    if (selected != null) {
        ChoreSheet(
            item = selected,
            today = today,
            membersById = membersById,
            myId = myId,
            busy = vm.busy,
            onDismiss = { selectedId = null },
            onEdit = { selectedId = null; onEditChore(selected.chore.id) },
            onMarkDone = { vm.markDone(selected.chore.id) },
            onUndo = { vm.undoDone(selected.chore.id) },
            onAddReminder = { selectedId = null; onAddReminder(selected.chore.id) },
            onAddTime = { minute -> vm.addTime(selected.chore, minute) },
            onRemoveTime = { minute -> vm.removeTime(selected.chore.id, minute) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeekView(
    today: LocalDate,
    chores: List<ChoreWithDetails>,
    members: List<MemberEntity>,
    onDayClick: (DayOfWeek) -> Unit,
    onSelect: (String) -> Unit,
) {
    // Key for the coloured dots on the cards.
    if (chores.isNotEmpty()) {
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            members.forEach { MemberChip(it.id, it.displayName) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        DayOfWeek.entries.forEach { day ->
            val isToday = day == today.dayOfWeek
            Box(
                Modifier.weight(1f).clickable(onClickLabel = "Open day") { onDayClick(day) }.padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    dayShort(day),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 88.dp)) {
        DayOfWeek.entries.forEach { day ->
            val isToday = day == today.dayOfWeek
            Column(
                Modifier.weight(1f)
                    .background(if (isToday) MaterialTheme.colorScheme.surfaceContainerLow else Color.Transparent)
                    .padding(2.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                chores.filter { it.isOn(day) }.forEach { c ->
                    ChoreCard(c, done = isToday && c.isCompleteOn(today)) { onSelect(c.chore.id) }
                }
            }
        }
    }
}

/** A narrow card for the week view: title and a dot per assignee. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoreCard(c: ChoreWithDetails, done: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth()
            .background(if (done) colors.surfaceVariant else colors.choreColors().first, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            (if (done) "✓ " else "") + c.chore.title,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 11.sp),
            fontWeight = FontWeight.Medium,
            color = if (done) colors.onSurfaceVariant else colors.choreColors().second,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        val times = c.assigneeIds.flatMap { slotsOf(c, it) }.map { it.startMinute }.distinct().sorted()
        if (times.isNotEmpty()) {
            Text(
                times.take(2).joinToString(" ") { formatMinute(it) } + if (times.size > 2) " …" else "",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 10.sp),
                color = if (done) colors.onSurfaceVariant else colors.choreColors().second,
                maxLines = 2,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            c.assigneeIds.forEach { Box(Modifier.size(8.dp).background(memberColor(it), CircleShape)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayView(
    day: DayOfWeek,
    today: LocalDate,
    chores: List<ChoreWithDetails>,
    membersById: Map<String, MemberEntity>,
    onBack: () -> Unit,
    onSelect: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            day.getDisplayName(TextStyle.FULL, Locale.getDefault()) + if (day == today.dayOfWeek) " (today)" else "",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onBack) { Text("Week") }
    }
    HorizontalDivider()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 72.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val dayChores = chores.filter { it.isOn(day) }
        if (dayChores.isEmpty()) {
            Text("No chores on this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        dayChores.forEach { c ->
            val done = day == today.dayOfWeek && c.isCompleteOn(today)
            val colors = MaterialTheme.colorScheme
            Column(
                Modifier.fillMaxWidth()
                    .background(if (done) colors.surfaceVariant else colors.choreColors().first, RoundedCornerShape(8.dp))
                    .clickable { onSelect(c.chore.id) }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    (if (done) "✓ " else "") + c.chore.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (done) colors.onSurfaceVariant else colors.choreColors().second,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    c.assigneeIds.forEach { id ->
                        MemberChip(id, membersById[id]?.displayName ?: "?", detail = slotsOf(c, id).joinToString(", ") { formatSlot(it) })
                    }
                }
            }
        }
    }
}

@Composable
fun MemberChip(id: String, name: String, ticked: Boolean = false, detail: String = "") {
    Row(
        Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(10.dp).background(memberColor(id), CircleShape))
        Text((if (ticked) "✓ " else "") + name + if (detail.isNotEmpty()) " · $detail" else "", style = MaterialTheme.typography.labelMedium)
    }
}

private fun ChoreWithDetails.isOn(day: DayOfWeek) = chore.weekdays and (1 shl (day.value - 1)) != 0

fun ChoreWithDetails.isOn(date: LocalDate) = isOnWeekday(chore.weekdays, date)
