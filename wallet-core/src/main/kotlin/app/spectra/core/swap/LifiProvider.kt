package app.spectra.core.swap

import app.spectra.core.chain.EthereumAddress
import app.spectra.core.encoding.Hex
import app.spectra.core.model.Amount
import app.spectra.core.model.AssetKind
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.WalletError
import app.spectra.core.rpc.HttpStatusException
import app.spectra.core.rpc.HttpTransport
import app.spectra.core.rpc.SpectraJson
import app.spectra.core.rpc.arr
import app.spectra.core.rpc.get
import app.spectra.core.rpc.long
import app.spectra.core.rpc.str
import java.math.BigInteger
import java.net.URLEncoder

/** LI.FI aggregation API (https://docs.li.fi). Used for Ethereum same-chain and Ethereum↔Solana routes. */
class LifiProvider(
    private val http: HttpTransport,
    private val allowanceReader: suspend (Network, String, String, String) -> BigInteger,
    private val baseUrl: String = "https://li.quest/v1",
    private val clock: () -> Long = System::currentTimeMillis,
) : SwapProvider {
    override val id = SwapProviderId.LIFI

    companion object {
        const val QUOTE_TTL_MS = 60_000L
        fun chainId(n: Network): String = when (n.chain) {
            Chain.ETHEREUM -> "1"
            Chain.SOLANA -> "1151111081099710"
            Chain.LITECOIN -> throw WalletError.RouteUnavailable("Litecoin is not supported by LI.FI")
        }
    }

    override suspend fun quote(request: SwapRequest): SwapQuote {
        val from = request.fromAccount.network
        val to = request.toAccount.network
        val fromToken = SwapRouter.mintOrContract(request.fromToken).let { if (from.chain == Chain.SOLANA && request.fromToken.id.kind == AssetKind.NATIVE) "11111111111111111111111111111111" else it }
        val toToken = SwapRouter.mintOrContract(request.toToken).let { if (to.chain == Chain.SOLANA && request.toToken.id.kind == AssetKind.NATIVE) "11111111111111111111111111111111" else it }
        val outDecimals = request.toToken.decimals ?: throw WalletError.UnknownDecimals(toToken)
        val slippage = request.slippageBps / 10_000.0
        val url = "$baseUrl/quote?fromChain=${chainId(from)}&toChain=${chainId(to)}&fromToken=${enc(fromToken)}&toToken=${enc(toToken)}" +
            "&fromAmount=${request.amount.units}&fromAddress=${enc(request.fromAccount.primaryAddress)}&toAddress=${enc(request.toAccount.primaryAddress)}&slippage=$slippage"
        val q = try { http.getJson(url) } catch (e: HttpStatusException) {
            val msg = runCatching { SpectraJson.parseToJsonElement(e.body)["message"].str }.getOrNull()
            throw WalletError.RouteUnavailable("LI.FI: ${msg ?: "HTTP ${e.code}"}")
        }
        val est = q["estimate"]
        val out = est["toAmount"].str?.toBigIntegerOrNull() ?: throw WalletError.ProviderError("LI.FI", "Malformed quote")
        val min = est["toAmountMin"].str?.toBigIntegerOrNull() ?: throw WalletError.ProviderError("LI.FI", "Malformed quote")
        if (est["fromAmount"].str != request.amount.units.toString()) throw WalletError.ProviderError("LI.FI", "Quote input does not match request")
        fun line(e: kotlinx.serialization.json.JsonElement): FeeLine {
            val dec = e["token"]["decimals"].long?.toInt()
            val amt = e["amount"].str?.toBigIntegerOrNull()
            return FeeLine(e["name"].str ?: "Fee", if (dec != null && amt != null) Amount(amt, dec) else null, e["token"]["symbol"].str, e["included"].str?.toBoolean() ?: true)
        }
        val approvalAddress = est["approvalAddress"].str
        val approval = if (from.chain == Chain.ETHEREUM && request.fromToken.id.kind == AssetKind.ERC20 && approvalAddress != null) {
            val spender = EthereumAddress.checksum(approvalAddress)
            val current = allowanceReader(from, request.fromToken.id.address!!, request.fromAccount.primaryAddress, spender)
            if (current < request.amount.units) ApprovalRequirement(request.fromToken.id.address, spender, request.amount, Amount(current, request.amount.decimals)) else null
        } else null
        val steps = q["includedSteps"].arr?.mapNotNull { it["toolDetails"]["name"].str ?: it["tool"].str } ?: emptyList()
        return SwapQuote(id, request, Amount(out, outDecimals), Amount(min, outDecimals),
            providerFees = est["feeCosts"].arr?.map(::line) ?: emptyList(),
            networkFees = est["gasCosts"].arr?.map(::line) ?: emptyList(),
            priceImpactPct = null, route = steps.ifEmpty { listOfNotNull(q["toolDetails"]["name"].str) },
            estimatedDurationSec = est["executionDuration"].long, expiresAtMillis = clock() + QUOTE_TTL_MS, approval = approval, raw = q, tool = q["tool"].str)
    }

    override suspend fun buildTransaction(quote: SwapQuote): SwapTransaction {
        if (clock() > quote.expiresAtMillis) throw WalletError.QuoteExpired()
        val tr = quote.raw["transactionRequest"] ?: throw WalletError.ProviderError("LI.FI", "No transaction in quote")
        return when (quote.request.fromAccount.network.chain) {
            Chain.ETHEREUM -> {
                val chainId = tr["chainId"].str?.toLongOrNull() ?: tr["chainId"].long ?: 1L
                if (chainId != quote.request.fromAccount.network.evmChainId) throw WalletError.WrongNetwork("Ethereum", "chainId $chainId")
                if (!tr["from"].str.equals(quote.request.fromAccount.primaryAddress, true)) throw WalletError.ProviderError("LI.FI", "Transaction sender mismatch")
                SwapTransaction.Evm(EthereumAddress.checksum(tr["to"].str!!), Hex.decode(tr["data"].str ?: "0x"),
                    tr["value"].str?.let { if (it.startsWith("0x")) BigInteger(it.removePrefix("0x").ifEmpty { "0" }, 16) else BigInteger(it) } ?: BigInteger.ZERO,
                    tr["gasLimit"].str?.let { BigInteger(it.removePrefix("0x"), 16) }, chainId)
            }
            Chain.SOLANA -> SwapTransaction.Solana(tr["data"].str ?: throw WalletError.ProviderError("LI.FI", "No Solana transaction"), null)
            Chain.LITECOIN -> throw WalletError.RouteUnavailable("Litecoin unsupported")
        }
    }

    override suspend fun status(request: SwapRequest, sourceTxId: String, tool: String?): SwapStatusObservation {
        val url = "$baseUrl/status?txHash=${enc(sourceTxId)}&fromChain=${chainId(request.fromAccount.network)}&toChain=${chainId(request.toAccount.network)}" +
            (tool?.let { "&bridge=${enc(it)}" } ?: "")
        val s = try { http.getJson(url) } catch (e: HttpStatusException) {
            if (e.code == 404) return SwapStatusObservation(SwapStatus.SUBMITTED, message = "Not yet indexed by provider")
            throw e
        }
        val recv = s["receiving"]
        val msg = s["substatusMessage"].str
        val st = when (s["status"].str) {
            "DONE" -> when (s["substatus"].str) { "PARTIAL" -> SwapStatus.PARTIAL; "REFUNDED" -> SwapStatus.REFUNDED; else -> SwapStatus.COMPLETED }
            "FAILED" -> SwapStatus.FAILED
            "PENDING" -> if (request.crossChain) SwapStatus.BRIDGING else SwapStatus.SUBMITTED
            "NOT_FOUND" -> SwapStatus.SUBMITTED
            else -> SwapStatus.UNKNOWN
        }
        return SwapStatusObservation(st, recv["txHash"].str, recv["amount"].str, msg)
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
