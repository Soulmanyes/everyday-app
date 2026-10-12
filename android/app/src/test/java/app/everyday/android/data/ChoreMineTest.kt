package app.everyday.android.data

import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class ChoreMineTest {
    private fun chore(createdBy: String?, vararg assignees: String) = ChoreWithDetails(
        ChoreEntity("c1", "Bins", null, 1, ChoreCompletion.ANY, createdBy = createdBy),
        assignees.mapIndexed { i, m -> ChoreAssignmentEntity("c1", m, i) },
        emptyList(),
    )

    @Test
    fun `mine are the chores I'm assigned to or created`() {
        assertTrue(chore(null, "sam").isMine("sam"))
        assertTrue(chore("sam", "alex").isMine("sam"))
        assertFalse(chore("alex", "alex").isMine("sam"))
        // Chores from before Phase 9.9 have no creator: they count only for their assignees.
        assertFalse(chore(null, "alex").isMine("sam"))
        assertFalse(chore(null, "sam").isMine(null))
    }
}
