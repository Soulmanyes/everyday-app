package app.everyday.android.chores

import app.everyday.android.data.ChoreCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ChoreRulesTest {
    private val today = LocalDate.parse("2026-10-08")
    private val yesterday = today.minusDays(1)

    @Test
    fun `ANY chore done by someone else counts as done for everyone`() {
        val completions = listOf(Completion("alex", today))
        assertEquals(setOf(today), ChoreRules.doneDatesFor(ChoreCompletion.ANY, completions, "sam"))
        assertTrue(ChoreRules.isComplete(ChoreCompletion.ANY, listOf("sam"), completions, today))
    }

    @Test
    fun `EACH chore counts as done only for the member who ticked their part`() {
        val completions = listOf(Completion("alex", today))
        assertEquals(setOf(today), ChoreRules.doneDatesFor(ChoreCompletion.EACH, completions, "alex"))
        assertTrue(ChoreRules.doneDatesFor(ChoreCompletion.EACH, completions, "sam").isEmpty())
        assertFalse(ChoreRules.isComplete(ChoreCompletion.EACH, listOf("sam", "alex"), completions, today))
        val both = completions + Completion("sam", today)
        assertTrue(ChoreRules.isComplete(ChoreCompletion.EACH, listOf("sam", "alex"), both, today))
    }

    @Test
    fun `completions on another day don't count today`() {
        val completions = listOf(Completion("sam", yesterday))
        assertFalse(ChoreRules.isComplete(ChoreCompletion.ANY, listOf("sam"), completions, today))
        assertEquals(setOf(yesterday), ChoreRules.doneDatesFor(ChoreCompletion.ANY, completions, "sam"))
    }

    @Test
    fun `EACH chore with no assignees is never complete`() {
        assertFalse(ChoreRules.isComplete(ChoreCompletion.EACH, emptyList(), listOf(Completion("sam", today)), today))
    }
}
