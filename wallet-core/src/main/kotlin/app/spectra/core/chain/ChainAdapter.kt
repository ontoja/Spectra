package app.spectra.core.chain

import app.spectra.core.model.Amount
import app.spectra.core.model.AssetBalance
import app.spectra.core.model.DerivationScheme
import app.spectra.core.model.HistoryItem
import app.spectra.core.model.Network
import app.spectra.core.model.StatusObservation
import app.spectra.core.model.TokenInfo
import app.spectra.core.model.TxType
import app.spectra.core.wallet.Keyring

enum class FeeLevel { SLOW, NORMAL, FAST }

/** Public view of an account on one network. For Litecoin, [accountXpub] enables watch-only address discovery. */
data class AccountView(
    val network: Network,
    val scheme: DerivationScheme,
    val accountIndex: Int,
    val primaryAddress: String,
    val accountXpub: String? = null,
)

data class TransferRequest(
    val account: AccountView,
    val token: TokenInfo,
    val recipient: String,
    val amount: Amount,
    val sendMax: Boolean,
    val feeLevel: FeeLevel = FeeLevel.NORMAL,
)

enum class TxWarning {
    RECIPIENT_IS_SELF, RECIPIENT_NEW_ACCOUNT, CREATES_TOKEN_ACCOUNT, OFF_CURVE_RECIPIENT, RECIPIENT_IS_CONTRACT,
    FEE_ESTIMATE_HIGH, DUST_CHANGE_ADDED_TO_FEE, UNCONFIRMED_FUNDS_EXCLUDED, NO_ADDRESS_CHECKSUM, TOKEN_EXTENSION_WARNING,
    MAX_LEAVES_RESIDUAL,
}

/** A fully estimated transaction awaiting user authorisation. Contains no secrets. */
data class PreparedTransaction(
    val request: TransferRequest,
    val type: TxType,
    val recipientAmount: Amount,
    val networkFee: Amount,
    /** Additional native cost such as Solana token-account rent. */
    val extraNativeCost: Amount,
    val warnings: List<TxWarning>,
    val expiresAtMillis: Long?,
    val feeDetail: String,
    val payload: ChainPayload,
)

sealed interface ChainPayload {
    class Solana(val messageBytes: ByteArray, val lastValidBlockHeight: Long, val blockhash: String) : ChainPayload
    class Ethereum(val tx: app.spectra.core.chain.ethereum.Eip1559Transaction) : ChainPayload
    class Litecoin(val selection: app.spectra.core.chain.litecoin.CoinSelection, val outputs: List<app.spectra.core.chain.litecoin.TxOutput>, val changeAddress: String?, val inputPubKeys: Map<String, ByteArray>) : ChainPayload
}

/** A locally signed transaction. The id is known before broadcast and must be persisted first. */
data class SignedTransaction(
    val network: Network,
    val txId: String,
    val rawHex: String,
    val encoding: RawEncoding,
    val lastValidBlockHeight: Long? = null,
    val nonce: Long? = null,
    val spentOutpoints: List<String> = emptyList(),
)

enum class RawEncoding { HEX, BASE64 }

sealed interface BroadcastOutcome {
    data class Accepted(val txId: String) : BroadcastOutcome
    data class Rejected(val error: Throwable) : BroadcastOutcome
    /** The request may or may not have reached the network. Never retry without reconciling by [txId]. */
    data class Unknown(val txId: String, val reason: String) : BroadcastOutcome
}

data class TrackedTransaction(
    val network: Network,
    val txId: String,
    val fromAddress: String,
    val nonce: Long? = null,
    val lastValidBlockHeight: Long? = null,
    val submittedAtMillis: Long,
)

interface ChainAdapter {
    suspend fun balances(account: AccountView, knownTokens: List<TokenInfo>): List<AssetBalance>
    suspend fun history(account: AccountView, limit: Int = 25): List<HistoryItem>
    suspend fun prepareTransfer(request: TransferRequest): PreparedTransaction
    fun sign(prepared: PreparedTransaction, keyring: Keyring): SignedTransaction
    suspend fun broadcast(signed: SignedTransaction): BroadcastOutcome
    suspend fun status(tracked: TrackedTransaction): StatusObservation
    suspend fun tokenInfo(network: Network, address: String): TokenInfo
}
