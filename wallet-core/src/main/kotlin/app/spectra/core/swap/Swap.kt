package app.spectra.core.swap

import app.spectra.core.chain.AccountView
import app.spectra.core.model.Amount
import app.spectra.core.model.AssetKind
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.TokenInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
enum class SwapProviderId(val displayName: String) { JUPITER("Jupiter"), LIFI("LI.FI") }

data class SwapRequest(
    val fromAccount: AccountView,
    val toAccount: AccountView,
    val fromToken: TokenInfo,
    val toToken: TokenInfo,
    val amount: Amount,
    val slippageBps: Int,
) {
    val crossChain get() = fromAccount.network.chain != toAccount.network.chain
}

data class FeeLine(val label: String, val amount: Amount?, val symbol: String?, val included: Boolean)

data class ApprovalRequirement(val tokenContract: String, val spender: String, val required: Amount, val currentAllowance: Amount)

data class SwapQuote(
    val provider: SwapProviderId,
    val request: SwapRequest,
    val estimatedOut: Amount,
    val minimumOut: Amount,
    val providerFees: List<FeeLine>,
    val networkFees: List<FeeLine>,
    val priceImpactPct: Double?,
    val route: List<String>,
    val estimatedDurationSec: Long?,
    val expiresAtMillis: Long,
    val approval: ApprovalRequirement?,
    /** Provider response needed to build the transaction; never shown as an executable amount by itself. */
    val raw: JsonElement,
    val tool: String? = null,
)

/** Transaction produced by a provider, to be inspected and signed locally. */
sealed interface SwapTransaction {
    class Solana(val wireBase64: String, val lastValidBlockHeight: Long?) : SwapTransaction
    class Evm(val to: String, val data: ByteArray, val value: java.math.BigInteger, val gasLimit: java.math.BigInteger?, val chainId: Long) : SwapTransaction
}

@Serializable
enum class SwapStatus { QUOTED, AWAITING_APPROVAL, APPROVAL_PENDING, AWAITING_AUTHORIZATION, SUBMITTED, SOURCE_CONFIRMED, BRIDGING, COMPLETED, PARTIAL, REFUNDED, FAILED, EXPIRED, UNKNOWN;
    val isTerminal get() = this in setOf(COMPLETED, PARTIAL, REFUNDED, FAILED, EXPIRED)
}

data class SwapStatusObservation(val status: SwapStatus, val destinationTxId: String? = null, val receivedAmount: String? = null, val message: String? = null)

sealed interface RouteSupport {
    data class Supported(val provider: SwapProviderId) : RouteSupport
    data class Unsupported(val reason: String) : RouteSupport
}

interface SwapProvider {
    val id: SwapProviderId
    suspend fun quote(request: SwapRequest): SwapQuote
    suspend fun buildTransaction(quote: SwapQuote): SwapTransaction
    /** Status of a submitted swap by its source transaction id. */
    suspend fun status(request: SwapRequest, sourceTxId: String, tool: String?): SwapStatusObservation
}

object SwapRouter {
    fun route(from: Network, to: Network): RouteSupport = when {
        from.isTestnet || to.isTestnet -> RouteSupport.Unsupported("Swap providers operate on mainnet only; no testnet routes are available.")
        from.chain == Chain.LITECOIN || to.chain == Chain.LITECOIN ->
            RouteSupport.Unsupported("No verified provider supports native Litecoin swaps in Spectra v0.1 (LI.FI does not list Litecoin).")
        from.chain == Chain.SOLANA && to.chain == Chain.SOLANA -> RouteSupport.Supported(SwapProviderId.JUPITER)
        else -> RouteSupport.Supported(SwapProviderId.LIFI)
    }

    fun mintOrContract(t: TokenInfo): String = when (t.id.kind) {
        AssetKind.NATIVE -> when (t.id.network.chain) {
            Chain.SOLANA -> "So11111111111111111111111111111111111111112"
            Chain.ETHEREUM -> "0x0000000000000000000000000000000000000000"
            Chain.LITECOIN -> error("unsupported")
        }
        else -> t.id.address!!
    }
}
