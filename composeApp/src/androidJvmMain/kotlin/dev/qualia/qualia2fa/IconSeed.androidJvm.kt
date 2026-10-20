package dev.qualia.qualia2fa

import java.security.MessageDigest

/**
 * The first four bytes of `SHA-256(secret)`, as an integer.
 *
 * The secret is already a high-entropy string that every device holding the entry has, so it is a
 * stable seed for a colour: the same account gets the same badge everywhere, and renaming the
 * entry does not change it. It is **not** used as key material and never leaves the process.
 *
 * Upper case first: the vault stores and reports the secret in upper case, and a caller that typed
 * or pasted a lower-case one must not get a different badge for the same account.
 */
internal actual fun seedColorIndex(secretBase32: String): Int {
    val digest =
        MessageDigest.getInstance("SHA-256").digest(secretBase32.uppercase().toByteArray(Charsets.UTF_8))
    return ((digest[0].toInt() and 0xFF) shl 24) or
        ((digest[1].toInt() and 0xFF) shl 16) or
        ((digest[2].toInt() and 0xFF) shl 8) or
        (digest[3].toInt() and 0xFF)
}
