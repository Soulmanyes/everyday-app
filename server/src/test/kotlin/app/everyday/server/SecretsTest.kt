package app.everyday.server

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecretsTest {
    private val secrets = Secrets(iterations = 1_000)

    @Test
    fun `hash verifies only the original secret`() {
        val stored = secrets.hash("4821")
        assertTrue(secrets.verify("4821", stored))
        assertFalse(secrets.verify("4822", stored))
        assertFalse(secrets.verify("4821", "garbage"))
    }

    @Test
    fun `same secret hashes differently each time`() {
        assertNotEquals(secrets.hash("4821"), secrets.hash("4821"))
    }

    @Test
    fun `tokens are unique and hash consistently`() {
        val token = secrets.newToken()
        assertNotEquals(token, secrets.newToken())
        assertTrue(Secrets.tokenHash(token) == Secrets.tokenHash(token))
    }
}
