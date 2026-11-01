package app.spectra.wallet.security

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import app.spectra.core.crypto.SecureMemory
import app.spectra.core.rpc.SpectraJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.crypto.Cipher

enum class LockState { NO_WALLET, LOCKED, UNLOCKED, KEYS_LOST }

sealed interface UnlockResult {
    class Success(val dek: ByteArray) : UnlockResult
    data class WrongPin(val failures: Int, val lockedUntil: Long?) : UnlockResult
    data class LockedOut(val untilMillis: Long) : UnlockResult
    data object KeysLost : UnlockResult
    data object Corrupted : UnlockResult
}

/**
 * Owns the encrypted vault files, PIN/biometric unwrapping and the app lock state.
 * The DEK is never cached: every unlock or signing request obtains it fresh and the caller wipes it after use.
 */
class SecurityManager(private val context: Context) {
    private val dir = File(context.noBackupFilesDir, "vault").apply { mkdirs() }
    private val keyFile = AtomicFile(File(dir, "keys.json"))
    val backend = KeystoreBackend(context)
    val crypto = VaultCrypto(backend)
    private val prefs = context.getSharedPreferences("spectra_security", Context.MODE_PRIVATE)
    private val lockout = LockoutPolicy(object : LockoutPolicy.Store {
        override var failures: Int
            get() = prefs.getInt("failures", 0)
            set(v) { prefs.edit().putInt("failures", v).commit() }
        override var lockedUntil: Long
            get() = prefs.getLong("locked_until", 0)
            set(v) { prefs.edit().putLong("locked_until", v).commit() }
    }, System::currentTimeMillis)

    private val _state = MutableStateFlow(if (hasCredentials()) LockState.LOCKED else LockState.NO_WALLET)
    val state: StateFlow<LockState> = _state.asStateFlow()

    private var backgroundedAt: Long? = null

    fun hasCredentials(): Boolean = keyFile.baseFile.exists()

    private fun readKeyFile(): KeyFile? = runCatching {
        SpectraJson.decodeFromString(KeyFile.serializer(), keyFile.readFully().toString(Charsets.UTF_8))
    }.getOrNull()

    private fun writeKeyFile(k: KeyFile) {
        val out = keyFile.startWrite()
        try {
            out.write(SpectraJson.encodeToString(KeyFile.serializer(), k).toByteArray())
            keyFile.finishWrite(out)
        } catch (e: Exception) { keyFile.failWrite(out); throw e }
    }

    /** Creates PIN credentials; returns the new DEK (caller wipes). Existing vaults must not exist. */
    suspend fun createCredentials(pin: CharArray): ByteArray = withContext(Dispatchers.Default) {
        check(!hasCredentials()) { "Credentials already exist" }
        backend.deleteKeys()
        val (file, dek) = crypto.create(pin)
        writeKeyFile(file)
        lockout.onSuccess()
        _state.value = LockState.UNLOCKED
        dek
    }

    fun lockoutDecision(): LockoutPolicy.Decision = lockout.check()

    suspend fun unlockWithPin(pin: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        when (val d = lockout.check()) {
            is LockoutPolicy.Decision.Locked -> return@withContext UnlockResult.LockedOut(d.untilMillis)
            else -> Unit
        }
        val file = readKeyFile() ?: return@withContext UnlockResult.Corrupted
        try {
            val dek = crypto.unlock(pin, file)
            lockout.onSuccess()
            UnlockResult.Success(dek)
        } catch (e: VaultException.WrongPin) {
            val d = lockout.onFailure()
            UnlockResult.WrongPin(lockout.failures, (d as? LockoutPolicy.Decision.Locked)?.untilMillis)
        } catch (e: VaultException.HardwareKeyUnavailable) {
            _state.value = LockState.KEYS_LOST
            UnlockResult.KeysLost
        } catch (e: VaultException.Corrupted) {
            UnlockResult.Corrupted
        }
    }

    suspend fun changePin(oldPin: CharArray, newPin: CharArray): UnlockResult = withContext(Dispatchers.Default) {
        when (val d = lockout.check()) { is LockoutPolicy.Decision.Locked -> return@withContext UnlockResult.LockedOut(d.untilMillis); else -> Unit }
        val file = readKeyFile() ?: return@withContext UnlockResult.Corrupted
        try {
            writeKeyFile(crypto.changePin(oldPin, newPin, file))
            lockout.onSuccess()
            UnlockResult.Success(ByteArray(0))
        } catch (e: VaultException.WrongPin) {
            val d = lockout.onFailure()
            UnlockResult.WrongPin(lockout.failures, (d as? LockoutPolicy.Decision.Locked)?.untilMillis)
        } catch (e: VaultException.HardwareKeyUnavailable) { UnlockResult.KeysLost }
    }

    // ---------- Vault files ----------

    private fun vaultFile(id: String): AtomicFile {
        require(id.matches(Regex("[A-Za-z0-9-]{8,64}")))
        return AtomicFile(File(dir, "vault_$id.bin"))
    }

    fun writeVault(dek: ByteArray, vaultId: String, secret: ByteArray) {
        val blob = crypto.encryptVault(dek, vaultId, secret)
        val f = vaultFile(vaultId)
        val out = f.startWrite()
        try { out.write(blob); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out); throw e }
    }

    /** Decrypts a vault's mnemonic bytes. Caller wipes the result. */
    fun readVault(dek: ByteArray, vaultId: String): ByteArray {
        val f = vaultFile(vaultId)
        if (!f.baseFile.exists()) throw VaultException.Corrupted("vault $vaultId missing")
        return crypto.decryptVault(dek, vaultId, f.readFully())
    }

    fun vaultExists(vaultId: String) = vaultFile(vaultId).baseFile.exists()

    /** Overwrites then deletes the vault file (best effort on flash storage). */
    fun deleteVault(vaultId: String) {
        val f = vaultFile(vaultId).baseFile
        if (f.exists()) { runCatching { f.writeBytes(SecureMemory.randomBytes(f.length().toInt().coerceAtLeast(64))) }; f.delete() }
    }

    // ---------- Biometrics ----------

    fun biometricEnabled(): Boolean = readKeyFile()?.bioWrapped != null

    fun prepareBiometricEnable(): KeystoreBackend.BioCipher {
        backend.createBiometricKey()
        return backend.biometricCipher(null)
    }

    fun completeBiometricEnable(cipher: Cipher, dek: ByteArray) {
        val file = readKeyFile() ?: return
        val ct = cipher.doFinal(dek)
        writeKeyFile(file.copy(bioWrapped = VaultCrypto.b64.encodeToString(ct), bioIv = VaultCrypto.b64.encodeToString(cipher.iv)))
    }

    /** Returns [KeystoreBackend.BioCipher.Invalidated] if enrollment changed; biometrics are then disabled automatically. */
    fun prepareBiometricUnlock(): KeystoreBackend.BioCipher {
        val file = readKeyFile() ?: return KeystoreBackend.BioCipher.Missing
        val iv = file.bioIv ?: return KeystoreBackend.BioCipher.Missing
        val r = backend.biometricCipher(VaultCrypto.b64d.decode(iv))
        if (r !is KeystoreBackend.BioCipher.Ready) disableBiometrics()
        return r
    }

    fun completeBiometricUnlock(cipher: Cipher): ByteArray? {
        val file = readKeyFile() ?: return null
        return runCatching { cipher.doFinal(VaultCrypto.b64d.decode(file.bioWrapped)) }.getOrNull()?.also { lockout.onSuccess() }
    }

    fun disableBiometrics() {
        readKeyFile()?.let { writeKeyFile(it.copy(bioWrapped = null, bioIv = null)) }
        backend.deleteBiometricKey()
    }

    // ---------- Lock state ----------

    fun markUnlocked() { if (hasCredentials()) _state.value = LockState.UNLOCKED }
    fun lock() { if (hasCredentials() && _state.value == LockState.UNLOCKED) _state.value = LockState.LOCKED }

    fun onBackground(autoLockMillis: Long) {
        backgroundedAt = SystemClock.elapsedRealtime()
        if (autoLockMillis == 0L) lock()
    }

    fun onForeground(autoLockMillis: Long) {
        val at = backgroundedAt ?: return
        backgroundedAt = null
        if (SystemClock.elapsedRealtime() - at >= autoLockMillis) lock()
    }

    /** Removes all secrets and keys from this device. Irreversible without the recovery phrase(s). */
    fun wipeAll() {
        dir.listFiles()?.forEach { f -> runCatching { f.writeBytes(SecureMemory.randomBytes(f.length().toInt().coerceAtLeast(64))) }; f.delete() }
        backend.deleteKeys()
        prefs.edit().clear().commit()
        _state.value = LockState.NO_WALLET
    }
}
