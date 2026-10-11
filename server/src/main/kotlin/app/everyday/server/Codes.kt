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
    private const val MEMBER_INVITE_LENGTH = 16
    /** Member invites (Phase 9.5). Join codes can't contain "JOIN": O and I aren't in the alphabet. */
    const val MEMBER_INVITE_PREFIX = "JOIN"

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

    /** A one-time invite into a household, shared as a link, e.g. `JOIN-7K3P-QX9M-2B4D-H8VW`. */
    fun newMemberInvite(): String =
        MEMBER_INVITE_PREFIX + "-" + randomChars(MEMBER_INVITE_LENGTH).chunked(4).joinToString("-")

    /** The member invite in the form its hash is stored for, or null if [input] isn't shaped like one. */
    fun normalizeMemberInvite(input: String): String? {
        val chars = normalize(input)
        if (!chars.startsWith(MEMBER_INVITE_PREFIX)) return null
        val rest = chars.removePrefix(MEMBER_INVITE_PREFIX)
        if (rest.length != MEMBER_INVITE_LENGTH || rest.any { it !in ALPHABET }) return null
        return MEMBER_INVITE_PREFIX + rest
    }

    private fun normalize(input: String) = input.uppercase().filter { it.isLetterOrDigit() }

    private fun randomChars(n: Int) = buildString { repeat(n) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }
}
