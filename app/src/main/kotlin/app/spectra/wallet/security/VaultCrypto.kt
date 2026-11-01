package app.spectra.wallet.security

import app.spectra.core.crypto.SecureMemory
import kotlinx.serialization.Serializable
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Hardware-bound key operations (Android Keystore in production, a fake in unit tests).
 * Keys never leave the backend.
 */
interface HardwareKeyBackend {
    /** HMAC-SHA256 with a non-exportable key: binds PIN guesses to this device. */
    fun pepper(input: ByteArray): ByteArray
    /** AES-256-GCM with a non-exportable key; returns iv || ciphertext. */
    fun wrap(plain: ByteArray): ByteArray
    fun unwrap(blob: ByteArray): ByteArray
    fun deleteKeys()
}

@Serializable
data class KdfParams(val alg: String = "argon2id", val memoryKiB: Int = 19_456, val iterations: Int = 2, val parallelism: Int = 1, val salt: String)

/** Public, non-secret key metadata persisted in the app's no-backup directory. */
@Serializable
data class KeyFile(
    val version: Int = 1,
    val kdf: KdfParams,
    /** Keystore-wrapped( AES-GCM(pinKey, DEK) ). */
    val pinWrapped: String,
    /** AES-GCM(biometric Keystore key, DEK) ciphertext and IV, when biometrics are enabled. */
    val bioWrapped: String? = null,
    val bioIv: String? = null,
)

sealed class VaultException(msg: String) : Exception(msg) {
    class WrongPin : VaultException("Incorrect PIN")
    class HardwareKeyUnavailable(cause: Throwable?) : VaultException("Device key unavailable: ${cause?.javaClass?.simpleName}")
    class Corrupted(what: String) : VaultException("Encrypted data is corrupted: $what")
}

/**
 * Envelope encryption for wallet secrets.
 *
 * DEK (random 256-bit) encrypts each vault (AES-256-GCM, AAD bound to vault id).
 * DEK is wrapped by pinKey = Argon2id(HMAC_keystore(PIN), salt), then wrapped again by a Keystore AES key.
 * A 6-digit PIN alone is not treated as a strong secret: the Keystore pepper prevents off-device brute force, Argon2id slows
 * on-device guessing, and [LockoutPolicy] rate-limits attempts in the UI.
 */
class VaultCrypto(private val backend: HardwareKeyBackend) {
    companion object {
        private const val DEK_AAD = "spectra:dek:v1"
        private const val GCM_TAG_BITS = 128
        private const val IV_LEN = 12
        val b64: Base64.Encoder = Base64.getEncoder()
        val b64d: Base64.Decoder = Base64.getDecoder()
    }

    fun deriveKey(pin: CharArray, kdf: KdfParams): ByteArray {
        require(kdf.alg == "argon2id")
        val pinBytes = ByteArray(pin.size) { pin[it].code.toByte() }
        val peppered = try { backend.pepper(pinBytes) } catch (e: Exception) { throw VaultException.HardwareKeyUnavailable(e) } finally { SecureMemory.wipe(pinBytes) }
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(kdf.memoryKiB).withIterations(kdf.iterations).withParallelism(kdf.parallelism).withSalt(b64d.decode(kdf.salt)).build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(32)
        gen.generateBytes(peppered, out)
        SecureMemory.wipe(peppered)
        return out
    }

    /** Creates a fresh DEK and key file for [pin]. Caller owns (and must wipe) the returned DEK. */
    fun create(pin: CharArray): Pair<KeyFile, ByteArray> {
        require(pin.size == 6 && pin.all { it in '0'..'9' }) { "PIN must be six digits" }
        val kdf = KdfParams(salt = b64.encodeToString(SecureMemory.randomBytes(16)))
        val dek = SecureMemory.randomBytes(32)
        return KeyFile(kdf = kdf, pinWrapped = wrapForPin(dek, pin, kdf)) to dek
    }

    private fun wrapForPin(dek: ByteArray, pin: CharArray, kdf: KdfParams): String {
        val pinKey = deriveKey(pin, kdf)
        try {
            val inner = gcmEncrypt(pinKey, dek, DEK_AAD.toByteArray())
            val outer = try { backend.wrap(inner) } catch (e: Exception) { throw VaultException.HardwareKeyUnavailable(e) }
            return b64.encodeToString(outer)
        } finally { SecureMemory.wipe(pinKey) }
    }

    /** Returns the DEK for a correct PIN. Throws [VaultException.WrongPin] otherwise. */
    fun unlock(pin: CharArray, file: KeyFile): ByteArray {
        val outer = try { b64d.decode(file.pinWrapped) } catch (e: IllegalArgumentException) { throw VaultException.Corrupted("key file") }
        val inner = try { backend.unwrap(outer) } catch (e: Exception) { throw VaultException.HardwareKeyUnavailable(e) }
        val pinKey = deriveKey(pin, file.kdf)
        try {
            return gcmDecrypt(pinKey, inner, DEK_AAD.toByteArray()) ?: throw VaultException.WrongPin()
        } finally { SecureMemory.wipe(pinKey, inner) }
    }

    fun changePin(oldPin: CharArray, newPin: CharArray, file: KeyFile): KeyFile {
        val dek = unlock(oldPin, file)
        try {
            require(newPin.size == 6 && newPin.all { it in '0'..'9' })
            val kdf = KdfParams(salt = b64.encodeToString(SecureMemory.randomBytes(16)))
            return file.copy(kdf = kdf, pinWrapped = wrapForPin(dek, newPin, kdf))
        } finally { SecureMemory.wipe(dek) }
    }

    fun encryptVault(dek: ByteArray, vaultId: String, secret: ByteArray): ByteArray = gcmEncrypt(dek, secret, aad(vaultId))

    fun decryptVault(dek: ByteArray, vaultId: String, blob: ByteArray): ByteArray =
        gcmDecrypt(dek, blob, aad(vaultId)) ?: throw VaultException.Corrupted("vault $vaultId")

    private fun aad(vaultId: String) = "spectra:vault:$vaultId:v1".toByteArray()

    fun gcmEncrypt(key: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val iv = SecureMemory.randomBytes(IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        c.updateAAD(aad)
        return iv + c.doFinal(plain)
    }

    /** Returns null on authentication failure (wrong key or tampered data). */
    fun gcmDecrypt(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray? {
        if (blob.size < IV_LEN + 16) return null
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_LEN))
        c.updateAAD(aad)
        return try { c.doFinal(blob, IV_LEN, blob.size - IV_LEN) } catch (e: AEADBadTagException) { null }
    }
}

/** Escalating delays after failed PIN attempts. Counters persist across restarts. */
class LockoutPolicy(private val store: Store, private val clock: () -> Long) {
    interface Store {
        var failures: Int
        var lockedUntil: Long
    }

    sealed interface Decision {
        data object Allowed : Decision
        data class Locked(val untilMillis: Long, val failures: Int) : Decision
    }

    fun check(): Decision {
        val until = store.lockedUntil
        return if (until > clock()) Decision.Locked(until, store.failures) else Decision.Allowed
    }

    fun onFailure(): Decision {
        val f = store.failures + 1
        store.failures = f
        val delay = delayFor(f)
        if (delay > 0) store.lockedUntil = clock() + delay
        return check()
    }

    fun onSuccess() { store.failures = 0; store.lockedUntil = 0 }

    val failures get() = store.failures

    companion object {
        fun delayFor(failures: Int): Long = when {
            failures < 5 -> 0
            failures == 5 -> 30_000
            failures == 6 -> 60_000
            failures == 7 -> 5 * 60_000
            failures == 8 -> 15 * 60_000
            else -> 60 * 60_000
        }
    }
}
