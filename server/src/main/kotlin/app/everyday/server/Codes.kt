package app.everyday.server

import java.security.SecureRandom

/**
 * Codes people type or share (Phase 9). The alphabet leaves out 0, O, 1 and I, which are easy to mix up.
 * Input is forgiving: case, spaces and dashes don't matter.
 */
object Codes {
    private const val ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
    private val random = SecureRandom()

    const val JOIN_ID_LENGTH = 4
    private const val JOIN_SECRET_LENGTH = 8
    private const val INVITE_LENGTH = 12
    const val INVITE_PREFIX = "NEW"

    /** A household's join code, e.g. `HZ7K-2P9X-QM4T`: the join id, then the secret. */
    data class JoinCode(val joinId: String, val secret: String) {
        override fun toString() = (joinId + secret).chunked(4).joinToString("-")
    }

    fun newJoinCode() = JoinCode(randomChars(JOIN_ID_LENGTH), randomChars(JOIN_SECRET_LENGTH))

    /** Null if [input] isn't shaped like a join code. */
    fun parseJoinCode(input: String): JoinCode? {
        val chars = normalize(input)
        if (chars.length != JOIN_ID_LENGTH + JOIN_SECRET_LENGTH || chars.any { it !in ALPHABET }) return null
        return JoinCode(chars.take(JOIN_ID_LENGTH), chars.drop(JOIN_ID_LENGTH))
    }

    /** A one-time invite to create a household, e.g. `NEW-7K3P-QX9M-2B4D`. */
    fun newInvite(): String = INVITE_PREFIX + "-" + randomChars(INVITE_LENGTH).chunked(4).joinToString("-")

    /** The invite in the form its hash is stored for, or null if [input] isn't shaped like one. */
    fun normalizeInvite(input: String): String? {
        val chars = normalize(input).removePrefix(INVITE_PREFIX)
        if (chars.length != INVITE_LENGTH || chars.any { it !in ALPHABET }) return null
        return INVITE_PREFIX + chars
    }

    private fun normalize(input: String) = input.uppercase().filter { it.isLetterOrDigit() }

    private fun randomChars(n: Int) = buildString { repeat(n) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}
