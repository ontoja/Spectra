package app.spectra.wallet.security

import app.spectra.core.crypto.SecureMemory
import app.spectra.core.wallet.Keyring
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fresh, per-operation user authorisation. A ViewModel suspends in [authorize]; the UI host shows the PIN/biometric
 * sheet and completes the request with the DEK (or null if cancelled). Nothing is cached between operations.
 */
class AuthorizationCoordinator(private val security: SecurityManager) {
    class Request(val title: String, val detail: String?, internal val result: CompletableDeferred<ByteArray?>) {
        fun complete(dek: ByteArray?) { if (!result.complete(dek)) dek?.let { SecureMemory.wipe(it) } }
    }

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()
    private val mutex = Mutex()

    suspend fun authorize(title: String, detail: String? = null): ByteArray? = mutex.withLock {
        val req = Request(title, detail, CompletableDeferred())
        _pending.value = req
        try { req.result.await() } finally { _pending.value = null }
    }

    /**
     * Authorises, decrypts the vault and runs [block] with a short-lived [Keyring]. All secret material is wiped afterwards.
     * Returns null when the user cancels.
     */
    suspend fun <T> withKeyring(vaultId: String, title: String, detail: String? = null, block: suspend (Keyring) -> T): T? {
        val dek = authorize(title, detail) ?: return null
        var mnemonic: ByteArray? = null
        try {
            mnemonic = security.readVault(dek, vaultId)
            return Keyring.fromMnemonic(mnemonic).use { block(it) }
        } finally {
            SecureMemory.wipe(dek, mnemonic)
        }
    }

    /** Authorised read of the mnemonic (recovery phrase reveal). Caller must wipe. */
    suspend fun revealMnemonic(vaultId: String): ByteArray? {
        val dek = authorize("Pokaż frazę odzyskiwania", "Wymagane jest ponowne potwierdzenie tożsamości.") ?: return null
        try { return security.readVault(dek, vaultId) } finally { SecureMemory.wipe(dek) }
    }
}
