package app.spectra.wallet.security

import android.content.Context
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore implementation. Keys are non-exportable, StrongBox-backed when the device has a StrongBox, and
 * otherwise TEE-backed where available (the platform decides; Spectra does not claim hardware backing it cannot verify).
 */
class KeystoreBackend(private val context: Context) : HardwareKeyBackend {
    companion object {
        private const val STORE = "AndroidKeyStore"
        const val PEPPER_ALIAS = "spectra.pin.pepper.v1"
        const val WRAP_ALIAS = "spectra.dek.wrap.v1"
        const val BIO_ALIAS = "spectra.dek.bio.v1"
    }

    private val ks: KeyStore get() = KeyStore.getInstance(STORE).apply { load(null) }
    private val hasStrongBox get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)

    private fun generate(alias: String, algorithm: String, spec: (Boolean) -> KeyGenParameterSpec): SecretKey {
        val gen = KeyGenerator.getInstance(algorithm, STORE)
        if (hasStrongBox) {
            try { gen.init(spec(true)); return gen.generateKey() } catch (e: StrongBoxUnavailableException) { /* fall back to TEE */ }
        }
        gen.init(spec(false))
        return gen.generateKey()
    }

    private fun pepperKey(): SecretKey = (ks.getKey(PEPPER_ALIAS, null) as? SecretKey) ?: generate(PEPPER_ALIAS, KeyProperties.KEY_ALGORITHM_HMAC_SHA256) { sb ->
        KeyGenParameterSpec.Builder(PEPPER_ALIAS, KeyProperties.PURPOSE_SIGN).setDigests(KeyProperties.DIGEST_SHA256).setIsStrongBoxBacked(sb).build()
    }

    private fun wrapKey(): SecretKey = (ks.getKey(WRAP_ALIAS, null) as? SecretKey) ?: generate(WRAP_ALIAS, KeyProperties.KEY_ALGORITHM_AES) { sb ->
        KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).setIsStrongBoxBacked(sb).build()
    }

    override fun pepper(input: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run { init(pepperKey()); doFinal(input) }

    override fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wrapKey())
        return c.iv + c.doFinal(plain)
    }

    override fun unwrap(blob: ByteArray): ByteArray {
        val key = ks.getKey(WRAP_ALIAS, null) as? SecretKey ?: throw IllegalStateException("Wrap key missing")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, 12))
        return c.doFinal(blob, 12, blob.size - 12)
    }

    override fun deleteKeys() {
        listOf(PEPPER_ALIAS, WRAP_ALIAS, BIO_ALIAS).forEach { runCatching { ks.deleteEntry(it) } }
    }

    // ---- Biometric-bound key (requires a BiometricPrompt CryptoObject for every use) ----

    fun createBiometricKey() {
        runCatching { ks.deleteEntry(BIO_ALIAS) }
        generate(BIO_ALIAS, KeyProperties.KEY_ALGORITHM_AES) { sb ->
            KeyGenParameterSpec.Builder(BIO_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                .setInvalidatedByBiometricEnrollment(true)
                .setIsStrongBoxBacked(sb).build()
        }
    }

    fun deleteBiometricKey() { runCatching { ks.deleteEntry(BIO_ALIAS) } }

    sealed interface BioCipher {
        data class Ready(val cipher: Cipher) : BioCipher
        /** Enrollment changed or key otherwise invalidated: biometrics must be re-enabled with the PIN. */
        data object Invalidated : BioCipher
        data object Missing : BioCipher
    }

    fun biometricCipher(decryptIv: ByteArray?): BioCipher {
        val key = ks.getKey(BIO_ALIAS, null) as? SecretKey ?: return BioCipher.Missing
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        return try {
            if (decryptIv == null) c.init(Cipher.ENCRYPT_MODE, key) else c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, decryptIv))
            BioCipher.Ready(c)
        } catch (e: KeyPermanentlyInvalidatedException) {
            deleteBiometricKey()
            BioCipher.Invalidated
        }
    }
}
