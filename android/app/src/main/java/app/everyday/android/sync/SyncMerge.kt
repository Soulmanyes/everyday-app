package app.everyday.android.sync

import app.everyday.android.data.EventMemberEntity
import app.everyday.android.data.EventWithMembers
import app.everyday.android.data.MemberSettingsEntity
import app.everyday.android.data.ReminderStateEntity
import app.everyday.android.data.ReminderWithDetails

/** What to write to Room after a sync. Every row written is clean (not dirty), unless kept from this phone. */
data class MergePlan(
    val saveEvents: List<SyncedEvent> = emptyList(),
    /** Events no longer shared with this member: removed from this phone only. */
    val deleteEvents: List<String> = emptyList(),
    /** Pushed events the server refused and doesn't have: kept as they are, but no longer dirty. */
    val clearDirtyEvents: List<String> = emptyList(),
    val saveReminders: List<SyncedReminder> = emptyList(),
    /** Reminders that aren't this member's (left over from before events): removed from this phone only. */
    val deleteLocal: List<String> = emptyList(),
    /** Pushed rows the server refused and doesn't have: kept as they are, but no longer dirty. */
    val clearDirty: List<String> = emptyList(),
    val saveStates: List<ReminderStateEntity> = emptyList(),
    val clearDirtyStates: List<String> = emptyList(),
    val saveSettings: MemberSettingsEntity? = null,
    val clearDirtySettings: Boolean = false,
)

/**
 * Merges a server snapshot into what this phone has for [memberId]. Pure, so the rules are unit-tested:
 * - A row edited again during the sync (dirty, and its updatedAt differs from the pushed one) is kept.
 * - Otherwise the server's version wins: it already applied last write wins.
 * - A row of this member missing from the snapshot is never deleted. Events of others that are missing
 *   (no longer shared with this member) are removed, and so is any reminder that isn't this member's.
 * - A refused row the server doesn't have is kept but marked clean, so it can't block sync forever.
 */
object SyncMerge {
    /**
     * The events and reminders that belong to one household (Phase 9), so merging its snapshot never
     * removes another household's rows from a phone that's in several. A row belongs by its owner's
     * household ([householdOf]: member id to household id). Owners without a known household (cached
     * before 2.0, or the local profile) count only while the phone is in a single household.
     */
    fun <T> inHousehold(rows: List<T>, owner: (T) -> String, householdId: String?, householdOf: Map<String, String?>, single: Boolean): List<T> =
        rows.filter { row -> householdOf[owner(row)]?.let { it == householdId } ?: single }

    fun plan(
        memberId: String,
        localEvents: List<EventWithMembers>,
        local: List<ReminderWithDetails>,
        localStates: List<ReminderStateEntity>,
        localSettings: MemberSettingsEntity?,
        pushed: Pushed,
        server: SyncSnapshot,
    ): MergePlan {
        val localEventById = localEvents.associateBy { it.event.id }
        val serverEventIds = server.events.map { it.event.id }.toSet()
        val saveEvents = server.events.mapNotNull { s ->
            val l = localEventById[s.event.id]
            when {
                l != null && changedSincePush(l.event.dirty, l.event.updatedAt, pushed.events[l.event.id]) -> null
                else -> s.keepMyLeave(l, memberId, pushed)
            }
        }
        val deleteEvents = localEvents.filter { l ->
            l.event.id !in serverEventIds && l.event.ownerMemberId != memberId && !l.event.dirty
        }.map { it.event.id }
        val clearDirtyEvents = localEvents.filter { l ->
            l.event.id !in serverEventIds && l.event.dirty && pushed.events[l.event.id] == l.event.updatedAt
        }.map { it.event.id }

        val localById = local.associateBy { it.reminder.id }
        val serverIds = server.reminders.map { it.reminder.id }.toSet()
        val save = server.reminders.mapNotNull { s ->
            val l = localById[s.reminder.id]
            when {
                l != null && changedSincePush(l.reminder.dirty, l.reminder.updatedAt, pushed.reminders[l.reminder.id]) -> null
                else -> s.copy(reminder = s.reminder.copy(dirty = false))
            }
        }
        val deleteLocal = local.filter { it.reminder.id !in serverIds && it.reminder.ownerMemberId != memberId }.map { it.reminder.id }
        val clearDirty = local.filter { l ->
            l.reminder.id !in serverIds && l.reminder.dirty && pushed.reminders[l.reminder.id] == l.reminder.updatedAt
        }.map { it.reminder.id }

        // State rows only for reminders that will exist on this phone (they reference the reminder).
        val kept = (localById.keys + serverIds) - deleteLocal.toSet()
        val localStateById = localStates.associateBy { it.reminderId }
        val saveStates = server.states.filter { it.reminderId in kept }.mapNotNull { s ->
            val l = localStateById[s.reminderId]
            if (l != null && changedSincePush(l.dirty, l.updatedAt, pushed.states[l.reminderId])) null else s.copy(dirty = false)
        }
        val serverStateIds = server.states.map { it.reminderId }.toSet()
        val clearDirtyStates = localStates.filter {
            it.reminderId !in serverStateIds && it.dirty && pushed.states[it.reminderId] == it.updatedAt
        }.map { it.reminderId }

        val settingsChanged = localSettings != null && changedSincePush(localSettings.dirty, localSettings.updatedAt, pushed.settings)
        val saveSettings = server.settings?.takeIf { !settingsChanged }?.copy(dirty = false)
        val clearDirtySettings = server.settings == null && localSettings?.dirty == true && pushed.settings == localSettings.updatedAt

        return MergePlan(
            saveEvents, deleteEvents, clearDirtyEvents, save, deleteLocal, clearDirty,
            saveStates, clearDirtyStates, saveSettings, clearDirtySettings,
        )
    }

    /** Dirty and not the version that was pushed (or not pushed at all): edited during the sync. */
    private fun changedSincePush(dirty: Boolean, updatedAt: Long, pushedAt: Long?) = dirty && pushedAt != updatedAt

    /**
     * Keeps this member's leaving of a shared event if it happened during the sync (not pushed yet). Another
     * member's event in the snapshot is one this member takes part in: under Everyone (Phase 9.8) it isn't
     * named in the list, so it gets a row of its own here, which leaving then hides as usual.
     */
    private fun SyncedEvent.keepMyLeave(local: EventWithMembers?, memberId: String, pushed: Pushed): SyncedEvent {
        val named = members.map { it.copy(dirty = false) }
        val implied = event.ownerMemberId != memberId && named.none { it.memberId == memberId }
        val clean = copy(event = event.copy(dirty = false), members = if (implied) named + EventMemberEntity(event.id, memberId) else named)
        val mine = local?.members?.firstOrNull { it.memberId == memberId } ?: return clean
        if (!changedSincePush(mine.dirty, mine.updatedAt, pushed.leaves[event.id])) return clean
        return clean.copy(members = clean.members.filter { it.memberId != memberId } + mine)
    }
}
