package app.everyday.android.ui.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseNotesTest {
    private val notes = listOf(
        ReleaseNote(10, "1.7.0", listOf("c")),
        ReleaseNote(9, "1.6.0", listOf("b")),
        ReleaseNote(8, "1.5.0", listOf("a")),
        ReleaseNote(7, "1.4.0", listOf("old")),
    )

    private fun codes(lastSeen: Int?, current: Int, fresh: Boolean) =
        pendingNotes(lastSeen, current, fresh, notes).map { it.versionCode }

    @Test fun freshInstallShowsNothing() = assertTrue(codes(null, 8, fresh = true).isEmpty())

    @Test fun updateFromBeforeThePopupShowsTheNewVersionsOnly() = assertEquals(listOf(8), codes(null, 8, fresh = false))

    @Test fun skippedVersionsAreAllListedNewestFirst() = assertEquals(listOf(10, 9), codes(8, 10, fresh = false))

    @Test fun alreadySeenShowsNothing() = assertTrue(codes(9, 9, fresh = false).isEmpty())

    @Test fun downgradeShowsNothing() = assertTrue(codes(10, 8, fresh = false).isEmpty())

    @Test fun releaseWithoutNotesShowsNothing() = assertTrue(codes(10, 11, fresh = false).isEmpty())

    @Test fun notesAreNewestFirstWithUniqueCodes() {
        val codes = RELEASE_NOTES.map { it.versionCode }
        assertEquals(codes.sortedDescending().distinct(), codes)
    }
}
