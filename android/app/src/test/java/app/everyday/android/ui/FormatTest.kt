package app.everyday.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun `before the start reads in days, hours and minutes`() {
        assertEquals("At start", formatBefore(0))
        assertEquals("30 min before", formatBefore(30))
        assertEquals("1 h before", formatBefore(60))
        assertEquals("1 h 30 min before", formatBefore(90))
        assertEquals("1 day before", formatBefore(1440))
        assertEquals("2 days before", formatBefore(2880))
        assertEquals("1 day 3 h before", formatBefore(1440 + 180))
        assertEquals("7 days before", formatBefore(10080))
    }
}
