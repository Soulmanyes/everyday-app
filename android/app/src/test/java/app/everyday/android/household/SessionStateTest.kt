package app.everyday.android.household

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionStateTest {
    private val home = Membership("https://server", "t1", householdId = "h1", householdName = "Home", memberId = "m1")
    private val grans = Membership("https://server", "t2", householdId = "h2", householdName = "Gran's", memberId = "m2")

    @Test
    fun `a phone from before 2_0 is one household, picked`() {
        // 1.x stored only the flat fields: the update keeps the phone joined.
        val old = SessionState(token = "t1", householdName = "Home", memberId = "m1")
        assertEquals(listOf("t1"), old.memberships.map { it.token })
        assertNull(old.picked!!.householdId)
    }

    @Test
    fun `joining another household picks it and keeps the first`() {
        val s = SessionState().adding(home).adding(grans)
        assertEquals("t2", s.token)
        assertEquals(listOf("t2", "t1"), s.memberships.map { it.token })
        assertEquals(home, s.others.single())
    }

    @Test
    fun `picking swaps households and keeps every field`() {
        val s = SessionState().adding(home).adding(grans.copy(notificationsOn = false)).picking("t1")
        assertEquals(home, s.picked)
        assertEquals(grans.copy(notificationsOn = false), s.others.single())
        // Picking an unknown token changes nothing.
        assertEquals(s, s.picking("nope"))
    }

    @Test
    fun `updating changes only that household`() {
        val s = SessionState().adding(home).adding(grans).updatingMembership("t1") { it.copy(pendingRequests = 2) }
        assertEquals(2, s.others.single().pendingRequests)
        assertEquals(0, s.pendingRequests)
        assertEquals(3, s.updatingMembership("t2") { it.copy(pendingRequests = 3) }.pendingRequests)
    }

    @Test
    fun `removing the picked household picks the next, and the last one leaves no household`() {
        val s = SessionState().adding(home).adding(grans)
        val one = s.removing("t2")
        assertEquals(home, one.picked)
        assertEquals(emptyList<Membership>(), one.others)
        val none = one.removing("t1")
        assertFalse(none.joined)
        assertEquals(emptyList<Membership>(), none.memberships)
        // Removing one that isn't picked leaves the picked one alone.
        assertEquals(grans, s.removing("t1").picked)
    }
}
