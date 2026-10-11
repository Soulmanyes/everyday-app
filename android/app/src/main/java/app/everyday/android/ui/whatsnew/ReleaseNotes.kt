package app.everyday.android.ui.whatsnew

/** What one release changed, as shown in the "What's new" popup and in Settings. */
data class ReleaseNote(val versionCode: Int, val versionName: String, val changes: List<String>)

/**
 * Newest first. Every release adds an entry for its new versionCode, so the popup has something
 * to show after the update.
 */
val RELEASE_NOTES = listOf(
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
