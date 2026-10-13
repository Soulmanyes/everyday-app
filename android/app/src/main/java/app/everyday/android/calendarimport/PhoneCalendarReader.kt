package app.everyday.android.calendarimport

import android.content.ContentResolver
import android.content.ContentUris
import android.database.Cursor
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads the phone's calendars (Phase 10). Needs READ_CALENDAR; only used while importing. */
class PhoneCalendarReader(private val resolver: ContentResolver) {
    suspend fun calendars(): List<PhoneCalendar> = withContext(Dispatchers.IO) {
        resolver.query(
            Calendars.CONTENT_URI,
            arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR, Calendars.IS_PRIMARY),
            null, null, "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
        ).rows { c ->
            PhoneCalendar(
                id = c.getLong(0),
                name = c.getString(1) ?: "Calendar",
                account = c.getString(2).orEmpty(),
                color = if (c.isNull(3)) null else c.getInt(3),
                primary = !c.isNull(4) && c.getInt(4) == 1,
            )
        }
    }

    /** Occurrences in [calendarIds] between [from] and [to] (epoch millis) that end after [from]; not cancelled or declined. */
    suspend fun instances(calendarIds: Collection<Long>, from: Long, to: Long): List<PhoneInstance> = withContext(Dispatchers.IO) {
        if (calendarIds.isEmpty()) return@withContext emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        resolver.query(
            uri,
            arrayOf(
                Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.UID_2445, Instances.TITLE, Instances.DESCRIPTION,
                Instances.ALL_DAY, Instances.BEGIN, Instances.END, Instances.RRULE, Instances.ORIGINAL_ID,
            ),
            "${Instances.CALENDAR_ID} IN (${calendarIds.joinToString(",")}) AND ${Instances.STATUS} IS NOT ${Events.STATUS_CANCELED}" +
                " AND ${Instances.SELF_ATTENDEE_STATUS} IS NOT ${Attendees.ATTENDEE_STATUS_DECLINED} AND ${Instances.END} > ?",
            arrayOf(from.toString()),
            "${Instances.BEGIN}",
        ).rows { c ->
            PhoneInstance(
                eventId = c.getLong(0),
                calendarId = c.getLong(1),
                uid = c.getString(2),
                title = c.getString(3),
                description = c.getString(4),
                allDay = c.getInt(5) == 1,
                begin = c.getLong(6),
                end = c.getLong(7),
                rrule = c.getString(8)?.takeIf { it.isNotBlank() },
                originalId = if (c.isNull(9)) null else c.getLong(9),
            )
        }
    }

    /** Pop-up reminder minutes of each of [eventIds] (not email or SMS ones, nor "use the calendar's default"). */
    suspend fun reminderMinutes(eventIds: Collection<Long>): Map<Long, List<Int>> = withContext(Dispatchers.IO) {
        if (eventIds.isEmpty()) return@withContext emptyMap()
        resolver.query(
            Reminders.CONTENT_URI,
            arrayOf(Reminders.EVENT_ID, Reminders.MINUTES),
            "${Reminders.EVENT_ID} IN (${eventIds.joinToString(",")}) AND ${Reminders.MINUTES} >= 0" +
                " AND ${Reminders.METHOD} IN (${Reminders.METHOD_DEFAULT}, ${Reminders.METHOD_ALERT})",
            null, null,
        ).rows { c -> c.getLong(0) to c.getInt(1) }.groupBy({ it.first }, { it.second })
    }

    private fun <T> Cursor?.rows(read: (Cursor) -> T): List<T> = this?.use { c ->
        buildList { while (c.moveToNext()) add(read(c)) }
    }.orEmpty()
}
