package org.ap.protocol.classroom

import java.security.SecureRandom

/** 6-character join codes without look-alikes (no 0/O, 1/I/L). Must match `validCode` in firestore.rules. */
object JoinCode {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6

    private val random = SecureRandom()

    fun random(): String = (1..LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    /** Accepts what people actually type or paste: lower case, spaces, the display dash. */
    fun normalize(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    /** "K7M4QX" → "K7M-4QX" for reading aloud and copying by eye. */
    fun display(code: String): String = code.take(3) + "-" + code.drop(3)

    /** "K, 7, M, 4, Q, X" so TalkBack reads it character by character. */
    fun spoken(code: String): String = code.toList().joinToString(", ")

    /** Must match the host in AndroidManifest and hosting/public (App Links + assetlinks.json). */
    const val LINK_HOST = "antiproxy-52424.web.app"

    fun link(code: String): String = "https://$LINK_HOST/join/$code"

    /** The code from a join link path such as "/join/A5AXM2", or null if the path isn't one. */
    fun fromLinkPath(path: String?): String? {
        if (path == null || !path.startsWith("/join/")) return null
        return normalize(path.removePrefix("/join/")).takeIf { isValid(it) }
    }
}
