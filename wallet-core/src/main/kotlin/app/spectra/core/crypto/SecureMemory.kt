package app.spectra.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom

/** Best-effort helpers for handling secret byte arrays. JVM strings cannot be wiped; avoid them for secrets. */
object SecureMemory {
    val random: SecureRandom by lazy { SecureRandom() }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun wipe(vararg arrays: ByteArray?) { arrays.forEach { it?.fill(0) } }

    fun wipe(chars: CharArray?) { chars?.fill('\u0000') }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
}

/** Runs [block] with [secret] and wipes it afterwards, even on failure. */
inline fun <T> ByteArray.useAndWipe(block: (ByteArray) -> T): T = try { block(this) } finally { fill(0) }
