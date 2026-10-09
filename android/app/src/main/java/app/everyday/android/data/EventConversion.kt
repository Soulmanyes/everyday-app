package app.everyday.android.data

import app.everyday.android.reminders.Window
import java.security.MessageDigest

/** md5("<base>:<suffix>") read as a UUID, the same as Postgres `md5(...)::uuid` in the server's V7. */
fun derivedId(base: String, suffix: String): String {
    val hex = MessageDigest.getInstance("MD5").digest("$base:$suffix".toByteArray()).joinToString("") { "%02x".format(it) }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

/**
 * Turns the general reminders of Room 5 into events (Phase 6), exactly as the server's V7 migration
 * does, so the phone and the server end up with the same ids and sync carries on:
 * - A timed reminder becomes one all-day weekly event with the same fixed times.
 * - Each window of a persistent reminder becomes an event at that window (0-1440 = all day), with a
 *   reminder from the start until the end (or all day).
 * - The first event and the owner's reminder keep the reminder's id; others get [derivedId]s.
 * - Invitees who accepted share the event and get their own copy; pending and declined are dropped.
 * - The owner keeps no reminder if "Remind me too" was off or the owner was removed.
 */
object EventConversion {
    data class OldReminder(
        val id: String,
        val ownerMemberId: String,
        val message: String,
        val mode: ReminderMode,
        val weekdays: Int,
        val active: Boolean,
        val ownerReceives: Boolean,
        val createdAt: Long,
        val updatedAt: Long,
        val dirty: Boolean,
        val times: List<Int>,
        val windows: List<Window>,
        /** Members who accepted the invite. */
        val accepted: List<String>,
        val states: List<ReminderStateEntity>,
    )

    data class Result(
        val events: List<EventEntity>,
        val members: List<EventMemberEntity>,
        /** The owner's reminders on the first event: these rows already exist and are updated. */
        val updated: List<ReminderEntity>,
        /** New reminder rows. */
        val inserted: List<ReminderEntity>,
        val times: List<ReminderTimeEntity>,
        val states: List<ReminderStateEntity>,
        /** Invitees' state rows on the original reminders, replaced by the ones on their copies. */
        val deletedStates: List<Pair<String, String>>,
    )

    fun convert(reminders: List<OldReminder>, removedMembers: Set<String>): Result {
        val events = mutableListOf<EventEntity>()
        val members = mutableListOf<EventMemberEntity>()
        val updated = mutableListOf<ReminderEntity>()
        val inserted = mutableListOf<ReminderEntity>()
        val times = mutableListOf<ReminderTimeEntity>()
        val states = mutableListOf<ReminderStateEntity>()
        val deletedStates = mutableListOf<Pair<String, String>>()

        for (r in reminders) {
            val ownerKeeps = r.ownerReceives && r.ownerMemberId !in removedMembers
            // A reminder with no days, or a persistent one with no windows, never fired: it stays silent.
            val active = r.active && r.weekdays != 0 && (r.mode == ReminderMode.TIMED || r.windows.isNotEmpty())
            val occurrences: List<Window?> = if (r.mode == ReminderMode.PERSISTENT && r.windows.isNotEmpty()) {
                r.windows.sortedBy { it.startMinute }.map { if (it.isAllDay) null else it }
            } else {
                listOf(null)
            }
            occurrences.forEachIndexed { k, window ->
                val eventId = if (k == 0) r.id else derivedId(r.id, "event:$k")
                val lead = if (r.mode == ReminderMode.PERSISTENT && window != null) 0 else null
                events += EventEntity(
                    id = eventId,
                    ownerMemberId = r.ownerMemberId,
                    title = r.message,
                    weekdays = if (r.weekdays == 0) 0b1111111 else r.weekdays,
                    startMinute = window?.startMinute,
                    endMinute = window?.endMinute,
                    createdAt = r.createdAt,
                    updatedAt = r.updatedAt,
                    dirty = r.dirty,
                )
                fun reminder(id: String, owner: String, dirty: Boolean, deleted: Boolean = false) = ReminderEntity(
                    id = id,
                    ownerMemberId = owner,
                    message = "",
                    mode = r.mode,
                    weekdays = 0,
                    active = active,
                    createdAt = r.createdAt,
                    updatedAt = r.updatedAt,
                    deleted = deleted,
                    dirty = dirty,
                    eventId = eventId,
                    leadMinutes = lead,
                )
                fun copyTimes(reminderId: String) {
                    if (r.mode == ReminderMode.TIMED) r.times.forEach { times += ReminderTimeEntity(derivedId(reminderId, "time:$it"), reminderId, it) }
                }

                if (k == 0) {
                    // The original row; its times stay as they are.
                    updated += reminder(r.id, r.ownerMemberId, r.dirty, deleted = !ownerKeeps)
                } else if (ownerKeeps) {
                    val id = derivedId(r.id, "reminder:$k")
                    inserted += reminder(id, r.ownerMemberId, r.dirty)
                    r.states.filter { it.memberId == r.ownerMemberId }.forEach { states += it.copy(reminderId = id) }
                }
                for (member in r.accepted) {
                    members += EventMemberEntity(eventId, member, updatedAt = r.updatedAt)
                    val id = derivedId(r.id, "member:$member:$k")
                    inserted += reminder(id, member, dirty = false)
                    copyTimes(id)
                    r.states.filter { it.memberId == member }.forEach { states += it.copy(reminderId = id) }
                }
            }
            r.states.filter { it.memberId != r.ownerMemberId }.forEach { deletedStates += r.id to it.memberId }
        }
        return Result(events, members, updated, inserted, times, states, deletedStates)
    }
}
