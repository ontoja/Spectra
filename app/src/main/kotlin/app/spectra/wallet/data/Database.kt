package app.spectra.wallet.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import kotlinx.coroutines.flow.Flow

/* Public metadata only. Recovery phrases and keys live exclusively in the encrypted vault files. */

@Entity(tableName = "vaults")
data class VaultEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val imported: Boolean,
    val backupConfirmed: Boolean,
    val schemeSolana: String,
    val schemeEthereum: String,
    val schemeLitecoin: String,
)

@Entity(tableName = "accounts", indices = [Index("vaultId")])
data class AccountEntity(
    @PrimaryKey val id: String,
    val vaultId: String,
    val accountIndex: Int,
    val name: String,
    val createdAt: Long,
)

@Entity(tableName = "chain_addresses", indices = [Index("accountId"), Index("networkId")])
data class ChainAddressEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val networkId: String,
    val scheme: String,
    val address: String,
    val path: String,
    val publicKeyHex: String,
    val accountXpub: String?,
)

@Entity(tableName = "tokens", indices = [Index("networkId")])
data class TokenEntity(
    @PrimaryKey val assetKey: String,
    val networkId: String,
    val kind: String,
    val address: String?,
    val symbol: String,
    val name: String,
    val decimals: Int?,
    val coingeckoId: String?,
    val verified: Boolean,
    val possibleSpam: Boolean,
    val hidden: Boolean,
    val userAdded: Boolean,
    val support: String,
    val supportNote: String?,
)

@Entity(tableName = "balances", primaryKeys = ["accountId", "assetKey"], indices = [Index("accountId")])
data class BalanceEntity(
    val accountId: String,
    val assetKey: String,
    val networkId: String,
    val units: String,
    val decimals: Int,
    val pendingUnits: String?,
    val sourceAddress: String?,
    val updatedAt: Long,
)

@Entity(tableName = "prices", primaryKeys = ["priceKey", "currency"])
data class PriceEntity(val priceKey: String, val currency: String, val price: String, val change24h: Double?, val updatedAt: Long)

@Entity(tableName = "transactions", indices = [Index("accountId"), Index(value = ["accountId", "networkId", "txId"], unique = true), Index("state")])
data class TransactionEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val networkId: String,
    val txId: String?,
    val type: String,
    val direction: String,
    val state: String,
    val assetKey: String?,
    val symbol: String?,
    val amountUnits: String?,
    val decimals: Int?,
    val feeUnits: String?,
    val counterparty: String?,
    val fromAddress: String?,
    /** Signed raw transaction (public once broadcast). Kept to reconcile unknown broadcast outcomes without re-signing. */
    val rawSigned: String?,
    val rawEncoding: String?,
    val lastValidBlockHeight: Long?,
    val nonce: Long?,
    val confirmations: Long?,
    val error: String?,
    val source: String,
    val swapId: String?,
    val createdAt: Long,
    val submittedAt: Long?,
    val updatedAt: Long,
    val timestamp: Long?,
)

@Entity(tableName = "swaps", indices = [Index("accountId")])
data class SwapEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val provider: String,
    val tool: String?,
    val fromNetworkId: String,
    val toNetworkId: String,
    val fromAssetKey: String,
    val toAssetKey: String,
    val fromSymbol: String,
    val toSymbol: String,
    val amountUnits: String,
    val fromDecimals: Int,
    val estimatedOutUnits: String,
    val minimumOutUnits: String,
    val toDecimals: Int,
    val fromAddress: String,
    val toAddress: String,
    val status: String,
    val approvalTxId: String?,
    val sourceTxId: String?,
    val destinationTxId: String?,
    val message: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "rpc_endpoints", indices = [Index("networkId")])
data class RpcEndpointEntity(
    @PrimaryKey val id: String,
    val networkId: String,
    val url: String,
    val role: String,
    val kind: String,
    val enabled: Boolean,
    val builtIn: Boolean,
)

@Dao
interface WalletDao {
    @Query("SELECT * FROM vaults ORDER BY createdAt") fun vaults(): Flow<List<VaultEntity>>
    @Query("SELECT * FROM vaults ORDER BY createdAt") suspend fun vaultsNow(): List<VaultEntity>
    @Query("SELECT * FROM vaults WHERE id = :id") suspend fun vault(id: String): VaultEntity?
    @Upsert suspend fun upsertVault(v: VaultEntity)
    @Query("DELETE FROM vaults WHERE id = :id") suspend fun deleteVault(id: String)

    @Query("SELECT * FROM accounts ORDER BY createdAt") fun accounts(): Flow<List<AccountEntity>>
    @Query("SELECT * FROM accounts ORDER BY createdAt") suspend fun accountsNow(): List<AccountEntity>
    @Query("SELECT * FROM accounts WHERE id = :id") suspend fun account(id: String): AccountEntity?
    @Query("SELECT * FROM accounts WHERE vaultId = :vaultId ORDER BY accountIndex") suspend fun accountsOf(vaultId: String): List<AccountEntity>
    @Upsert suspend fun upsertAccount(a: AccountEntity)
    @Query("DELETE FROM accounts WHERE vaultId = :vaultId") suspend fun deleteAccountsOf(vaultId: String)

    @Query("SELECT * FROM chain_addresses WHERE accountId = :accountId") suspend fun addressesOf(accountId: String): List<ChainAddressEntity>
    @Query("SELECT * FROM chain_addresses WHERE accountId = :accountId") fun addressesFlow(accountId: String): Flow<List<ChainAddressEntity>>
    @Query("SELECT * FROM chain_addresses") suspend fun allAddresses(): List<ChainAddressEntity>
    @Upsert suspend fun upsertAddresses(a: List<ChainAddressEntity>)
    @Query("DELETE FROM chain_addresses WHERE accountId IN (SELECT id FROM accounts WHERE vaultId = :vaultId)") suspend fun deleteAddressesOf(vaultId: String)

    @Transaction
    suspend fun removeVault(vaultId: String) {
        deleteAddressesOf(vaultId)
        deleteAccountsOf(vaultId)
        deleteVault(vaultId)
    }
}

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM tokens") fun tokens(): Flow<List<TokenEntity>>
    @Query("SELECT * FROM tokens WHERE networkId = :networkId") suspend fun tokensOf(networkId: String): List<TokenEntity>
    @Query("SELECT * FROM tokens WHERE assetKey = :key") suspend fun token(key: String): TokenEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertTokensIfAbsent(t: List<TokenEntity>)
    @Upsert suspend fun upsertToken(t: TokenEntity)
    @Query("UPDATE tokens SET hidden = :hidden WHERE assetKey = :key") suspend fun setHidden(key: String, hidden: Boolean)

    @Query("SELECT * FROM balances WHERE accountId = :accountId") fun balances(accountId: String): Flow<List<BalanceEntity>>
    @Query("SELECT * FROM balances") fun allBalances(): Flow<List<BalanceEntity>>
    @Upsert suspend fun upsertBalances(b: List<BalanceEntity>)
    @Query("DELETE FROM balances WHERE accountId = :accountId AND networkId = :networkId AND assetKey NOT IN (:keep)") suspend fun pruneBalances(accountId: String, networkId: String, keep: List<String>)
    @Query("DELETE FROM balances WHERE accountId NOT IN (SELECT id FROM accounts)") suspend fun pruneOrphanBalances()

    @Query("SELECT * FROM prices WHERE currency = :currency") fun prices(currency: String): Flow<List<PriceEntity>>
    @Upsert suspend fun upsertPrices(p: List<PriceEntity>)
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY COALESCE(timestamp, createdAt / 1000) DESC LIMIT 500") fun all(): Flow<List<TransactionEntity>>
    @Query("SELECT * FROM transactions WHERE id = :id") fun byId(id: String): Flow<TransactionEntity?>
    @Query("SELECT * FROM transactions WHERE id = :id") suspend fun get(id: String): TransactionEntity?
    @Query("SELECT * FROM transactions WHERE state IN ('SIGNED','BROADCASTING','PENDING','CONFIRMED','UNKNOWN') AND source = 'LOCAL'") suspend fun inFlight(): List<TransactionEntity>
    @Query("SELECT MAX(nonce) FROM transactions WHERE networkId = :networkId AND fromAddress = :from AND nonce IS NOT NULL AND state IN ('SIGNED','BROADCASTING','PENDING','UNKNOWN')") suspend fun maxPendingNonce(networkId: String, from: String): Long?
    @Query("SELECT * FROM transactions WHERE accountId = :accountId AND networkId = :networkId AND txId = :txId") suspend fun find(accountId: String, networkId: String, txId: String): TransactionEntity?
    @Upsert suspend fun upsert(t: TransactionEntity)
    @Query("DELETE FROM transactions WHERE accountId NOT IN (SELECT id FROM accounts)") suspend fun pruneOrphans()

    @Query("SELECT * FROM swaps ORDER BY createdAt DESC") fun swaps(): Flow<List<SwapEntity>>
    @Query("SELECT * FROM swaps WHERE id = :id") suspend fun swap(id: String): SwapEntity?
    @Query("SELECT * FROM swaps WHERE status NOT IN ('COMPLETED','PARTIAL','REFUNDED','FAILED','EXPIRED','QUOTED')") suspend fun activeSwaps(): List<SwapEntity>
    @Upsert suspend fun upsertSwap(s: SwapEntity)
}

@Dao
interface RpcDao {
    @Query("SELECT * FROM rpc_endpoints") fun all(): Flow<List<RpcEndpointEntity>>
    @Query("SELECT * FROM rpc_endpoints WHERE networkId = :networkId AND kind = :kind") suspend fun forNetwork(networkId: String, kind: String): List<RpcEndpointEntity>
    @Upsert suspend fun upsert(e: RpcEndpointEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIfAbsent(e: List<RpcEndpointEntity>)
    @Query("DELETE FROM rpc_endpoints WHERE id = :id AND builtIn = 0") suspend fun deleteCustom(id: String)
}

@Database(
    entities = [VaultEntity::class, AccountEntity::class, ChainAddressEntity::class, TokenEntity::class, BalanceEntity::class,
        PriceEntity::class, TransactionEntity::class, SwapEntity::class, RpcEndpointEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class SpectraDatabase : RoomDatabase() {
    abstract fun wallet(): WalletDao
    abstract fun portfolio(): PortfolioDao
    abstract fun transactions(): TransactionDao
    abstract fun rpc(): RpcDao

    companion object {
        /** Explicit migrations only — no destructive fallback, transaction history must survive upgrades. */
        val MIGRATIONS: Array<Migration> = emptyArray()

        fun build(context: Context): SpectraDatabase =
            Room.databaseBuilder(context, SpectraDatabase::class.java, "spectra.db").addMigrations(*MIGRATIONS).build()
    }
}
