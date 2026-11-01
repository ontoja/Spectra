package app.spectra.wallet.domain

import app.spectra.core.chain.AccountView
import app.spectra.core.crypto.Bip39
import app.spectra.core.crypto.SecureMemory
import app.spectra.core.model.Chain
import app.spectra.core.model.DerivationScheme
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.WalletError
import app.spectra.core.wallet.Keyring
import app.spectra.wallet.data.AccountEntity
import app.spectra.wallet.data.ChainAddressEntity
import app.spectra.wallet.data.VaultEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.util.UUID

data class AccountSummary(val account: AccountEntity, val vault: VaultEntity, val addresses: List<ChainAddressEntity>) {
    fun view(network: Network): AccountView? = addresses.firstOrNull { it.networkId == network.id }?.toView(account.accountIndex)
}

data class SchemeCandidate(val scheme: DerivationScheme, val address: String, val balanceUnits: Long?, val error: String?)

/** Progress callback: (completedSteps, totalSteps, label). Each step corresponds to a real completed operation. */
typealias Progress = suspend (Int, Int, String) -> Unit

class WalletRepository(private val c: AppContainer) {
    private val dao get() = c.db.wallet()

    val accounts: Flow<List<AccountSummary>> = combine(dao.accounts(), dao.vaults()) { accs, vaults ->
        accs.mapNotNull { a -> vaults.firstOrNull { it.id == a.vaultId }?.let { v -> AccountSummary(a, v, dao.addressesOf(a.id)) } }
    }

    suspend fun accountsNow(): List<AccountSummary> {
        val vaults = dao.vaultsNow()
        return dao.accountsNow().mapNotNull { a -> vaults.firstOrNull { it.id == a.vaultId }?.let { AccountSummary(a, it, dao.addressesOf(a.id)) } }
    }

    suspend fun account(id: String): AccountSummary? {
        val a = dao.account(id) ?: return null
        val v = dao.vault(a.vaultId) ?: return null
        return AccountSummary(a, v, dao.addressesOf(id))
    }

    /** Derives addresses for one account index on mainnet and testnet for all chains. */
    private fun deriveAll(k: Keyring, schemes: Map<Chain, DerivationScheme>, index: Int, accountId: String, chain: Chain): List<ChainAddressEntity> =
        NetworkEnv.entries.map { env ->
            val n = Network(chain, env)
            val scheme = schemes.getValue(chain)
            val d = k.deriveAddress(n, scheme, index)
            val xpub = if (chain == Chain.LITECOIN) k.accountPublicNode(n, scheme, index).serialize() else null
            ChainAddressEntity("$accountId:${n.id}", accountId, n.id, scheme.name, d.address, scheme.path(index, env)?.toString() ?: d.path, d.publicKeyHex, xpub)
        }

    /**
     * Creates and stores a new encrypted vault. [words] are wiped from the derived byte buffer after use; the caller
     * should drop its own references. Requires the DEK obtained from PIN creation or fresh authorisation.
     */
    suspend fun createVault(dek: ByteArray, words: List<String>, schemes: Map<Chain, DerivationScheme>, imported: Boolean, progress: Progress): String = withContext(Dispatchers.Default) {
        val total = 6
        progress(0, total, "Sprawdzanie frazy")
        if (Bip39.validate(words) != Bip39.Validation.Valid) throw WalletError.Unexpected("Invalid recovery phrase")
        val vaultId = UUID.randomUUID().toString()
        val accountId = "$vaultId:0"
        progress(1, total, "Generowanie ziarna (BIP-39)")
        val phrase = Bip39.phraseBytes(words)
        try {
            Keyring.fromMnemonic(phrase).use { k ->
                progress(2, total, "Generowanie klucza Solana")
                val sol = deriveAll(k, schemes, 0, accountId, Chain.SOLANA)
                progress(3, total, "Generowanie klucza Ethereum")
                val eth = deriveAll(k, schemes, 0, accountId, Chain.ETHEREUM)
                progress(4, total, "Generowanie klucza Litecoin")
                val ltc = deriveAll(k, schemes, 0, accountId, Chain.LITECOIN)
                progress(5, total, "Szyfrowanie i zapis")
                c.security.writeVault(dek, vaultId, phrase)
                // Verify the stored ciphertext decrypts back to the same phrase before committing metadata.
                val check = c.security.readVault(dek, vaultId)
                val same = SecureMemory.constantTimeEquals(check, phrase)
                SecureMemory.wipe(check)
                if (!same) { c.security.deleteVault(vaultId); throw WalletError.Unexpected("Vault verification failed") }
                val count = dao.vaultsNow().size
                dao.upsertVault(VaultEntity(vaultId, if (count == 0) "Portfel główny" else "Portfel ${count + 1}", System.currentTimeMillis(), imported, backupConfirmed = !imported,
                    schemes.getValue(Chain.SOLANA).name, schemes.getValue(Chain.ETHEREUM).name, schemes.getValue(Chain.LITECOIN).name))
                val accCount = dao.accountsNow().size
                dao.upsertAccount(AccountEntity(accountId, vaultId, 0, "Konto ${accCount + 1}", System.currentTimeMillis()))
                dao.upsertAddresses(sol + eth + ltc)
                progress(6, total, "Gotowe")
            }
        } finally { SecureMemory.wipe(phrase) }
        if (c.prefs.current().activeAccountId == null) c.prefs.setActiveAccount(accountId)
        accountId
    }

    /** Adds the next HD account to an existing vault (requires fresh authorisation). */
    suspend fun addAccount(vaultId: String): String? {
        val vault = dao.vault(vaultId) ?: return null
        val next = (dao.accountsOf(vaultId).maxOfOrNull { it.accountIndex } ?: -1) + 1
        val schemes = mapOf(Chain.SOLANA to DerivationScheme.valueOf(vault.schemeSolana), Chain.ETHEREUM to DerivationScheme.valueOf(vault.schemeEthereum), Chain.LITECOIN to DerivationScheme.valueOf(vault.schemeLitecoin))
        if (schemes.getValue(Chain.SOLANA) == DerivationScheme.SOL_KEYGEN_SEED) throw WalletError.FeatureUnavailable("This Solana derivation supports a single account only")
        val accountId = "$vaultId:$next"
        val rows = c.auth.withKeyring(vaultId, "Dodaj konto", "Wyprowadzenie nowych adresów wymaga potwierdzenia.") { k ->
            withContext(Dispatchers.Default) { Chain.entries.flatMap { deriveAll(k, schemes, next, accountId, it) } }
        } ?: return null
        dao.upsertAccount(AccountEntity(accountId, vaultId, next, "Konto ${dao.accountsNow().size + 1}", System.currentTimeMillis()))
        dao.upsertAddresses(rows)
        return accountId
    }

    suspend fun rename(accountId: String, name: String) {
        val a = dao.account(accountId) ?: return
        dao.upsertAccount(a.copy(name = name.trim().take(32).ifEmpty { a.name }))
    }

    suspend fun markBackupConfirmed(vaultId: String) {
        dao.vault(vaultId)?.let { dao.upsertVault(it.copy(backupConfirmed = true)) }
    }

    /**
     * Discovery for imports: derives account-0 addresses for every supported scheme and reads mainnet balances so the
     * user can choose explicitly. Nothing is selected silently.
     */
    suspend fun discoverSchemes(words: List<String>): Map<Chain, List<SchemeCandidate>> = withContext(Dispatchers.Default) {
        val phrase = Bip39.phraseBytes(words)
        val derived = try {
            Keyring.fromMnemonic(phrase).use { k ->
                DerivationScheme.entries.map { s ->
                    val n = Network(s.chain, NetworkEnv.MAINNET)
                    val addr = k.deriveAddress(n, s, 0)
                    val xpub = if (s.chain == Chain.LITECOIN) k.accountPublicNode(n, s, 0).serialize() else null
                    Triple(s, addr.address, xpub)
                }
            }
        } finally { SecureMemory.wipe(phrase) }
        coroutineScope {
            derived.map { (s, addr, xpub) ->
                async {
                    val n = Network(s.chain, NetworkEnv.MAINNET)
                    val view = AccountView(n, s, 0, addr, xpub)
                    runCatching {
                        if (s.chain == Chain.LITECOIN) c.litecoin.scanBalance(view)
                        else c.adapter(n).balances(view, emptyList()).sumOf { if (it.token.id.isNative) it.amount.units.toLong() else if (it.amount.units.signum() > 0) 1L else 0L }
                    }.fold({ SchemeCandidate(s, addr, it, null) }, { SchemeCandidate(s, addr, null, it.message ?: "Błąd sieci") })
                }
            }.awaitAll()
        }.groupBy { it.scheme.chain }
    }

    /** Deletes a vault and its metadata. Deleting the final vault wipes all keys and local data. */
    suspend fun removeVault(vaultId: String) {
        c.security.deleteVault(vaultId)
        dao.removeVault(vaultId)
        c.db.portfolio().pruneOrphanBalances()
        c.db.transactions().pruneOrphans()
        val remaining = dao.accountsNow()
        if (dao.vaultsNow().isEmpty()) {
            c.security.wipeAll()
            withContext(Dispatchers.IO) { c.db.clearAllTables() }
            c.db.rpc().insertIfAbsent(DefaultEndpoints.all)
            c.prefs.clear()
        } else if (remaining.none { it.id == c.prefs.current().activeAccountId }) {
            c.prefs.setActiveAccount(remaining.firstOrNull()?.id)
        }
    }
}
