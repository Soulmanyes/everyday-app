package app.everyday.android.sync

import app.everyday.android.data.MemberSettingsEntity
import app.everyday.android.data.ReminderStateEntity
import app.everyday.android.data.ReminderWithDetails

/** What to write to Room after a sync. Every row written is clean (not dirty). */
data class MergePlan(
    val saveReminders: List<SyncedReminder> = emptyList(),
    /** Shared reminders this member is no longer invited to: removed from this phone only. */
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
 * - A row missing from the snapshot is never deleted, except a shared reminder (not owned, not dirty)
 *   that the member is no longer invited to.
 * - A refused row the server doesn't have is kept but marked clean, so it can't block sync forever.
 */
object SyncMerge {
    fun plan(
        memberId: String,
        local: List<ReminderWithDetails>,
        localStates: List<ReminderStateEntity>,
        localSettings: MemberSettingsEntity?,
        pushed: Pushed,
        server: SyncSnapshot,
    ): MergePlan {
        val localById = local.associateBy { it.reminder.id }
        val serverIds = server.reminders.map { it.reminder.id }.toSet()

        val save = server.reminders.mapNotNull { s ->
            val l = localById[s.reminder.id]
            when {
                l != null && changedSincePush(l.reminder.dirty, l.reminder.updatedAt, pushed.reminders[l.reminder.id]) -> null
                else -> s.keepMyAnswer(l, memberId, pushed)
            }
        }
        val deleteLocal = local.filter { l ->
            l.reminder.id !in serverIds && l.reminder.ownerMemberId != memberId && !l.reminder.dirty
        }.map { it.reminder.id }
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

        return MergePlan(save, deleteLocal, clearDirty, saveStates, clearDirtyStates, saveSettings, clearDirtySettings)
    }

    /** Dirty and not the version that was pushed (or not pushed at all): edited during the sync. */
    private fun changedSincePush(dirty: Boolean, updatedAt: Long, pushedAt: Long?) = dirty && pushedAt != updatedAt

    /** Keeps this member's own answer to an invite if they changed it again during the sync. */
    private fun SyncedReminder.keepMyAnswer(local: ReminderWithDetails?, memberId: String, pushed: Pushed): SyncedReminder {
        val mine = local?.recipients?.firstOrNull { it.memberId == memberId } ?: return clean()
        if (!changedSincePush(mine.dirty, mine.updatedAt, pushed.answers[reminder.id])) return clean()
        return clean().copy(recipients = recipients.filter { it.memberId != memberId } + mine)
    }

    private fun SyncedReminder.clean() = copy(
        reminder = reminder.copy(dirty = false),
        recipients = recipients.map { it.copy(dirty = false) },
    )
}
