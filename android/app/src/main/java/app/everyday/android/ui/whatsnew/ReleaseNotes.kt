package app.everyday.android.ui.whatsnew

/** What one release changed, as shown in the "What's new" popup and in Settings. */
data class ReleaseNote(val versionCode: Int, val versionName: String, val changes: List<String>)

/**
 * Newest first. Every release adds an entry for its new versionCode, so the popup has something
 * to show after the update.
 */
val RELEASE_NOTES = listOf(
    ReleaseNote(
        19, "2.6.3",
        listOf(
            "The outline round a day with a one-off event is now in the colour of whoever's event it is. When several people have one, the outline is split between their colours.",
        ),
    ),
    ReleaseNote(
        18, "2.6.2",
        listOf(
            "In the month view, days with a one-off event now have an outline, so they stand out from the weekly routine.",
        ),
    ),
    ReleaseNote(
        17, "2.6.1",
        listOf(
            "The month view now shows how many things each person has, even when their name is long. It reads \"Sam 3\" instead of \"Sam · 3\", leaving more room for the name.",
        ),
    ),
    ReleaseNote(
        16, "2.6.0",
        listOf(
            "Import from calendar: copy events from your phone's calendar, such as Google Calendar, into Everyday. Find it in Settings.",
            "After importing, Everyday lists the events' calendar reminders once, so you can add the ones you want.",
            "Reminders can now be up to 7 days before an event starts, with 1 day and 2 days before in the list.",
        ),
    ),
    ReleaseNote(
        15, "2.5.0",
        listOf(
            "Personal chores: when you add a chore just for yourself, turn on \"Personal (only I see it)\" and nobody else in the household sees it.",
            "The Chores tab now starts on Mine: the chores you do and the ones you made for others. Switch to All to see the whole household's.",
        ),
    ),
    ReleaseNote(
        14, "2.4.0",
        listOf(
            "Two ways to share an event: \"Add to the event\" (they take part, with their own reminders) or \"Share for scheduling\" (they only see the title and times).",
            "Both have Everyone: the whole household, including people who join later.",
            "Events shared with you for scheduling show with their title. Tap one to see it, or Hide it.",
            "On a shared event you can now choose whether everyone else sees \"Busy\" or nothing.",
        ),
    ),
    ReleaseNote(
        13, "2.3.0",
        listOf(
            "The Events tab now starts on your own events and chores: tap \"Showing\" above the month to add other people.",
            "The month view is calmer: each day shows one line per person with how many things they have. Tap a day for the full list.",
            "The week view now shows others' Busy times and the chores too, for the people you've chosen.",
            "Settings → Show chores on the Events tab: turn it off to keep chores on the Chores tab only.",
            "Admins: tapping a \"someone wants to join\" notification opens the right household.",
        ),
    ),
    ReleaseNote(
        12, "2.2.0",
        listOf(
            "Events can last several days: add an end date to a one-off event (a holiday, a trip), or pick the day a weekly event ends on (every Fri 18:00 to Sun 20:00).",
            "They show on every day they cover, in the week and month views.",
            "Reminders on them: times you pick ring on each day, and a notification that stays can show each day or over the whole event.",
        ),
    ),
    ReleaseNote(
        11, "2.1.0",
        listOf(
            "Admins: Settings → Invite someone shares a one-time invite link. No need to find or remake the join code.",
            "Open invite links are listed in Manage members, where you can cancel them.",
        ),
    ),
    ReleaseNote(
        10, "2.0.0",
        listOf(
            "Other families can set up their own, separate household: admins make invites in Settings → Manage members.",
            "Be in more than one household on one phone. The app asks which to open, and each has its own switch for reminders and notifications.",
            "Join codes are now made by the app, and can be shared as a link. Admins: make a new join code in Manage members.",
            "Admins can delete their household. Settings has a privacy note about what's stored.",
        ),
    ),
    ReleaseNote(
        9, "1.6.0",
        listOf(
            "Instant updates: changes from other phones (chores, shared events, join requests) arrive straight away instead of within 15 minutes.",
        ),
    ),
    ReleaseNote(
        8, "1.5.0",
        listOf(
            "This \"What's new\" list, shown once after each update. You can read it again in Settings.",
        ),
    ),
    ReleaseNote(
        7, "1.4.0",
        listOf(
            "Month view on the Events tab: switch between Week and Month.",
            "Choose whose events and chores the month shows: everyone, or each member's events and chores.",
            "Personal events can show to others as \"Busy\" (times only), or not at all.",
        ),
    ),
    ReleaseNote(
        6, "1.3.0",
        listOf(
            "The timetable is now the Events tab.",
            "New Reminders tab: all your reminders in one list, with a switch to pause each one.",
        ),
    ),
    ReleaseNote(5, "1.2.1", listOf("Behind-the-scenes updates.")),
    ReleaseNote(
        4, "1.2.0",
        listOf(
            "Events: weekly or on a date, just for you or shared with members. Add your own reminders to any event.",
            "New \"Both\" alert option (sound and vibrate). \"Sound\" is now sound only.",
        ),
    ),
    ReleaseNote(
        3, "1.1.0",
        listOf(
            "Dark theme by default. Turn on \"Follow phone theme\" in Settings to match your phone.",
            "Pick your own colour in Settings.",
        ),
    ),
)

/** The last versionCode released before the popup existed (1.4.0); phones updating from it saved nothing. */
const val LAST_CODE_BEFORE_WHATS_NEW = 7

/**
 * The notes to show once at launch, newest first.
 *
 * [lastSeen] is the versionCode whose notes were last dismissed, or null if never saved. A fresh
 * install shows nothing. An update without a saved value came from a version before the popup.
 */
fun pendingNotes(
    lastSeen: Int?,
    current: Int,
    freshInstall: Boolean,
    notes: List<ReleaseNote> = RELEASE_NOTES,
): List<ReleaseNote> {
    val from = lastSeen ?: if (freshInstall) return emptyList() else LAST_CODE_BEFORE_WHATS_NEW
    return notes.filter { it.versionCode in (from + 1)..current }
}
