package app.everyday.android.calendarimport

import app.everyday.android.data.EventDraft
import app.everyday.android.data.MAX_EVENT_END_DAYS
import app.everyday.android.data.problem
import app.everyday.android.reminders.MINUTES_PER_DAY
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Import from the phone's calendar (Phase 10). The phone's calendar (Google Calendar syncs onto it) is read
 * with READ_CALENDAR, and the events the member ticks become their own events. No ongoing sync.
 */

/** A calendar on the phone, such as a Google account's calendar. */
data class PhoneCalendar(val id: Long, val name: String, val account: String, val color: Int?, val primary: Boolean)

/**
 * One occurrence of a calendar event in the coming months, as the phone's calendar expands repeats. [begin] and
 * [end] are epoch millis; an all-day event's are UTC midnights, [end] exclusive. [originalId] is set on a changed
 * occurrence of a repeat, which the phone keeps as an event of its own.
 */
data class PhoneInstance(
    val eventId: Long,
    val calendarId: Long,
    val uid: String?,
    val title: String?,
    val description: String?,
    val allDay: Boolean,
    val begin: Long,
    val end: Long,
    val rrule: String?,
    val originalId: Long? = null,
    /** When the event (the first occurrence of a repeat) starts, epoch millis. */
    val seriesStart: Long? = null,
)

/** One Everyday event to create; [sourceKey] remembers what it came from, so it isn't imported twice. */
data class ImportItem(val sourceKey: String, val draft: EventDraft)

/**
 * A calendar event the member can tick: one weekly Everyday event (a weekly or daily repeat without an end), or
 * one-off events, one per occurrence in the coming months (a single event, or any other repeat).
 */
data class ImportChoice(
    /** The phone's event id. */
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val allDay: Boolean,
    /** What ticking it imports: occurrences already imported are left out. */
    val items: List<ImportItem>,
    /** Occurrences imported before (and still in Everyday), not imported again. */
    val importedBefore: Int,
    /** True when it imports as one weekly event. */
    val weekly: Boolean,
    /** Source keys of all its occurrences, imported before or not. */
    val keys: List<String> = emptyList(),
) {
    val alreadyImported get() = items.isEmpty() && importedBefore > 0
}

/** A calendar reminder offered after importing, to add as an Everyday reminder on each imported event. */
data class ReminderOffer(
    val eventId: Long,
    /** Minutes before the start ([relative]), or a clock time on the day of an all-day event. */
    val minute: Int,
    val relative: Boolean,
    /** As the calendar has it: minutes before the start (midnight for an all-day event). */
    val calendarMinutes: Int,
) {
    /** Everyday reminds up to 7 days before the start. */
    val tooEarly get() = relative && minute > MAX_BEFORE_MINUTES
}

/** "Before the start" reminders go up to 7 days (Phase 10). */
const val MAX_BEFORE_MINUTES = 7 * MINUTES_PER_DAY

/** How far ahead the import looks, and how far a repeat that isn't weekly becomes one-off events. */
const val IMPORT_MONTHS = 3L

/** A weekly repeat becomes a weekly event only if it has already started, or starts within this many days. */
private const val WEEKLY_START_DAYS = 6L

private const val MAX_TITLE = 200
private const val MAX_NOTES = 500

object ImportPlanner {
    /**
     * Groups [instances] (occurrences ending after now, up to [IMPORT_MONTHS] ahead) by calendar event. [imported]
     * holds source keys imported before; [zone] is the phone's, which reminders use too.
     */
    fun plan(instances: List<PhoneInstance>, today: LocalDate, zone: ZoneId, imported: Set<String>): List<ImportChoice> {
        // The same event can be in two calendars (same UID): list it once.
        val seen = mutableSetOf<String>()
        return instances.groupBy { it.eventId }.values
            .map { list -> list.sortedBy { it.begin } }
            .sortedWith(compareBy({ it.first().begin }, { it.first().eventId }))
            .mapNotNull { list -> choice(list, today, zone, imported) }
            .filter { c -> c.keys.map { seen.add(it) }.any { it } }
    }

    /** Whether [key] was imported before: a weekly series covers its occurrences, and the other way round. */
    private fun importedBefore(key: String, imported: Set<String>): Boolean {
        if (key in imported) return true
        if (OCCURRENCE !in key) return imported.any { it.startsWith(key + OCCURRENCE) }
        return key.substringBefore(OCCURRENCE) in imported
    }

    private fun choice(list: List<PhoneInstance>, today: LocalDate, zone: ZoneId, imported: Set<String>): ImportChoice? {
        val first = list.first()
        val base = first.uid?.takeIf { it.isNotBlank() } ?: "id:${first.eventId}"
        val title = first.title?.trim()?.ifEmpty { null }?.take(MAX_TITLE) ?: "(No title)"
        val notes = cleanNotes(first.description)

        val weekly = weeklyDraft(list, today, zone)?.copy(title = title, notes = notes)?.takeIf { it.problem() == null }
        val all = if (weekly != null) {
            listOf(ImportItem(base, weekly))
        } else {
            // A single event is known by its UID; occurrences of a repeat (and changed ones) also by their start.
            val single = first.rrule == null && first.originalId == null && list.size == 1
            list.map { inst ->
                val s = shape(inst, zone)
                ImportItem(
                    if (single) base else "$base$OCCURRENCE${inst.begin}",
                    EventDraft(
                        title = title,
                        notes = notes,
                        weekly = false,
                        date = s.date,
                        startMinute = s.startMinute,
                        endMinute = s.endMinute,
                        endDays = s.endDays,
                    ),
                )
            }.filter { it.draft.problem() == null }
        }
        if (all.isEmpty()) return null
        val fresh = all.filter { !importedBefore(it.sourceKey, imported) }
        return ImportChoice(first.eventId, first.calendarId, title, first.allDay, fresh, all.size - fresh.size, weekly != null, all.map { it.sourceKey })
    }

    /** One weekly event for a weekly or daily repeat without an end, if every occurrence has the same times. */
    private fun weeklyDraft(list: List<PhoneInstance>, today: LocalDate, zone: ZoneId): EventDraft? {
        if (!openEndedWeekly(list.first().rrule ?: return null)) return null
        val shapes = list.map { shape(it, zone) }
        val s = shapes.first()
        // Today's occurrence may be over already (and not listed): a series that has begun counts as started.
        val started = list.first().seriesStart?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() <= today } ?: false
        if (!started && s.date > today.plusDays(WEEKLY_START_DAYS)) return null
        if (shapes.any { it.startMinute != s.startMinute || it.endMinute != s.endMinute || it.endDays != s.endDays }) return null
        val weekdays = shapes.fold(0) { mask, it -> mask or (1 shl (it.date.dayOfWeek.value - 1)) }
        return EventDraft(weekly = true, weekdays = weekdays, startMinute = s.startMinute, endMinute = s.endMinute, endDays = s.endDays)
    }

    /** Whether an RRULE repeats every week (on chosen days) or every day, with no end: what a weekly event can be. */
    fun openEndedWeekly(rrule: String): Boolean {
        val parts = rrule.removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.associate {
            val (k, v) = (it.split('=', limit = 2) + "").take(2)
            k.trim().uppercase() to v.trim().uppercase()
        }
        if (parts["FREQ"] !in setOf("WEEKLY", "DAILY")) return false
        if ((parts["INTERVAL"] ?: "1") != "1") return false
        // COUNT and UNTIL end it; anything else (BYMONTH, BYSETPOS, ...) picks days a weekly event can't.
        if (parts.keys.any { it !in setOf("FREQ", "INTERVAL", "BYDAY", "WKST") }) return false
        // Plain day names only ("MO", not "1MO").
        return parts["BYDAY"]?.split(',')?.all { it in DAY_NAMES } ?: true
    }

    private val DAY_NAMES = setOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

    /** When an occurrence happens, as an Everyday event has it. */
    internal data class Shape(val date: LocalDate, val startMinute: Int?, val endMinute: Int?, val endDays: Int)

    internal fun shape(inst: PhoneInstance, zone: ZoneId): Shape {
        if (inst.allDay) {
            // All-day times are UTC midnights with an exclusive end.
            val start = LocalDate.ofEpochDay(Math.floorDiv(inst.begin, DAY_MILLIS))
            val end = LocalDate.ofEpochDay(Math.floorDiv(inst.end, DAY_MILLIS))
            val days = (ChronoUnit.DAYS.between(start, end) - 1).coerceIn(0, MAX_EVENT_END_DAYS.toLong()).toInt()
            return Shape(start, null, null, days)
        }
        val s = Instant.ofEpochMilli(inst.begin).atZone(zone)
        val start = s.hour * 60 + s.minute
        if (inst.end <= inst.begin) return Shape(s.toLocalDate(), start, null, 0)
        val e = Instant.ofEpochMilli(inst.end).atZone(zone)
        var days = ChronoUnit.DAYS.between(s.toLocalDate(), e.toLocalDate()).toInt()
        var end = e.hour * 60 + e.minute
        // Ending at midnight is the end of the day before.
        if (end == 0 && days > 0) {
            days--
            end = MINUTES_PER_DAY
        }
        if (days > MAX_EVENT_END_DAYS) {
            days = MAX_EVENT_END_DAYS
            end = MINUTES_PER_DAY
        }
        if (days == 0 && end <= start) return Shape(s.toLocalDate(), start, null, 0)
        return Shape(s.toLocalDate(), start, end, days)
    }

    /**
     * The calendar reminders of imported [choices] to offer, one per distinct time: [minutesOf] gives each event's
     * reminder minutes. An all-day event's reminder counts back from midnight, often to the day before; Everyday
     * reminds at that clock time on the day instead.
     */
    fun offers(choices: List<ImportChoice>, minutesOf: (Long) -> List<Int>): List<ReminderOffer> = choices.flatMap { c ->
        minutesOf(c.eventId).filter { it >= 0 }.map { m ->
            if (c.allDay) {
                ReminderOffer(c.eventId, Math.floorMod(-m, MINUTES_PER_DAY), relative = false, calendarMinutes = m)
            } else {
                ReminderOffer(c.eventId, m, relative = true, calendarMinutes = m)
            }
        }.distinctBy { it.minute to it.relative }.sortedWith(compareBy({ it.tooEarly }, { it.minute }))
    }

    /** A calendar description as plain notes: HTML (Google Calendar's) stripped, cut to what the server takes. */
    fun cleanNotes(description: String?): String {
        if (description.isNullOrBlank()) return ""
        val text = description
            .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
            .replace(Regex("<[^>]*>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&amp;", "&")
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
        return text.take(MAX_NOTES).trim()
    }

    private const val DAY_MILLIS = 86_400_000L

    /** Joins a UID and an occurrence's start in a source key; UIDs often contain "@" (Google's end in "@google.com"). */
    const val OCCURRENCE = "|start="
}
