package app.spectra.core.swap

import app.spectra.core.model.Amount
import app.spectra.core.model.WalletError
import app.spectra.core.rpc.HttpStatusException
import app.spectra.core.rpc.HttpTransport
import app.spectra.core.rpc.arr
import app.spectra.core.rpc.dbl
import app.spectra.core.rpc.get
import app.spectra.core.rpc.long
import app.spectra.core.rpc.str
import app.spectra.core.rpc.toJson

/** Jupiter Swap API (https://dev.jup.ag/docs/swap-api). Mainnet only. */
class JupiterProvider(
    private val http: HttpTransport,
    private val baseUrl: String = "https://lite-api.jup.ag/swap/v1",
    private val clock: () -> Long = System::currentTimeMillis,
) : SwapProvider {
    override val id = SwapProviderId.JUPITER

    companion object {
        const val QUOTE_TTL_MS = 30_000L
        const val PROGRAM_ID = "JUP6LkbZbjS1jKKwapdHNy74zcZ3tLUZoi5QNyVTaV4"
    }

    override suspend fun quote(request: SwapRequest): SwapQuote {
        val input = SwapRouter.mintOrContract(request.fromToken)
        val output = SwapRouter.mintOrContract(request.toToken)
        if (input == output) throw WalletError.RouteUnavailable("Source and destination assets are the same")
        val outDecimals = request.toToken.decimals ?: throw WalletError.UnknownDecimals(output)
        val q = try {
            http.getJson("$baseUrl/quote?inputMint=$input&outputMint=$output&amount=${request.amount.units}&slippageBps=${request.slippageBps}&restrictIntermediateTokens=true")
        } catch (e: HttpStatusException) {
            throw WalletError.RouteUnavailable("Jupiter: " + (runCatching { app.spectra.core.rpc.SpectraJson.parseToJsonElement(e.body)["error"].str }.getOrNull() ?: "HTTP ${e.code}"))
        }
        val out = q["outAmount"].str?.toBigIntegerOrNull() ?: throw WalletError.ProviderError("Jupiter", "Malformed quote")
        val min = q["otherAmountThreshold"].str?.toBigIntegerOrNull() ?: throw WalletError.ProviderError("Jupiter", "Malformed quote")
        if (q["inAmount"].str != request.amount.units.toString()) throw WalletError.ProviderError("Jupiter", "Quote input does not match request")
        val route = q["routePlan"].arr?.mapNotNull { it["swapInfo"]["label"].str } ?: emptyList()
        val platformFee = q["platformFee"]["amount"].str?.toBigIntegerOrNull()
        return SwapQuote(id, request, Amount(out, outDecimals), Amount(min, outDecimals),
            providerFees = listOfNotNull(platformFee?.let { FeeLine("Platform fee", Amount(it, outDecimals), request.toToken.symbol, true) },
                FeeLine("AMM/liquidity fees (included in rate)", null, null, true)),
            networkFees = listOf(FeeLine("Solana network + priority fee (set when building)", null, "SOL", false)),
            priceImpactPct = q["priceImpactPct"].dbl?.times(100), route = route, estimatedDurationSec = 30,
            expiresAtMillis = clock() + QUOTE_TTL_MS, approval = null, raw = q)
    }

    override suspend fun buildTransaction(quote: SwapQuote): SwapTransaction {
        if (clock() > quote.expiresAtMillis) throw WalletError.QuoteExpired()
        val body = toJson(mapOf(
            "quoteResponse" to quote.raw,
            "userPublicKey" to quote.request.fromAccount.primaryAddress,
            "wrapAndUnwrapSol" to true,
            "dynamicComputeUnitLimit" to true,
            "prioritizationFeeLamports" to mapOf("priorityLevelWithMaxLamports" to mapOf("maxLamports" to 1_000_000, "priorityLevel" to "medium")),
        ))
        val r = http.postJson("$baseUrl/swap", body.toString())
        val tx = r["swapTransaction"].str ?: throw WalletError.ProviderError("Jupiter", r["error"].str ?: "No transaction returned")
        return SwapTransaction.Solana(tx, r["lastValidBlockHeight"].long)
    }

    /** Same-chain Solana swaps settle atomically; status equals the source transaction status (tracked by the chain adapter). */
    override suspend fun status(request: SwapRequest, sourceTxId: String, tool: String?): SwapStatusObservation =
        SwapStatusObservation(SwapStatus.SUBMITTED, message = "Atomic swap; see transaction status")
}

