package app.everyday.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashing for the household code and admin PIN (slow PBKDF2, because they are guessable), and for
 * device tokens (SHA-256, because they are 32 random bytes). Stored format: pbkdf2_sha256$iterations$salt$hash.
 */
class Secrets(private val iterations: Int = 210_000) {
    private val random = SecureRandom()

    fun hash(secret: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2_sha256\$$iterations\$${b64(salt)}\$${b64(pbkdf2(secret, salt, iterations))}"
    }

    fun verify(secret: String, stored: String): Boolean {
        val parts = stored.split('$')
        if (parts.size != 4 || parts[0] != "pbkdf2_sha256") return false
        val expected = Base64.getDecoder().decode(parts[3])
        val actual = pbkdf2(secret, Base64.getDecoder().decode(parts[2]), parts[1].toInt())
        return MessageDigest.isEqual(expected, actual)
    }

    fun newToken(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        fun tokenHash(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Compares strings without leaking where they differ through timing. */
        fun constantTimeEquals(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

        private fun pbkdf2(secret: String, salt: ByteArray, iterations: Int): ByteArray =
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(secret.toCharArray(), salt, iterations, 256))
                .encoded

        private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    }
}
