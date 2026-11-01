package app.spectra.core.chain.ethereum

import app.spectra.core.chain.AccountView
import app.spectra.core.chain.AddressCheck
import app.spectra.core.chain.AddressValidator
import app.spectra.core.chain.AddressWarning
import app.spectra.core.chain.BroadcastOutcome
import app.spectra.core.chain.ChainAdapter
import app.spectra.core.chain.ChainPayload
import app.spectra.core.chain.EthereumAddress
import app.spectra.core.chain.FeeLevel
import app.spectra.core.chain.PreparedTransaction
import app.spectra.core.chain.RawEncoding
import app.spectra.core.chain.SignedTransaction
import app.spectra.core.chain.TrackedTransaction
import app.spectra.core.chain.TransferRequest
import app.spectra.core.chain.TxWarning
import app.spectra.core.crypto.DerivationPath
import app.spectra.core.encoding.Hex
import app.spectra.core.model.Amount
import app.spectra.core.model.AssetBalance
import app.spectra.core.model.AssetId
import app.spectra.core.model.AssetKind
import app.spectra.core.model.HistoryItem
import app.spectra.core.model.Network
import app.spectra.core.model.StatusObservation
import app.spectra.core.model.TokenInfo
import app.spectra.core.model.TxDirection
import app.spectra.core.model.TxState
import app.spectra.core.model.TxType
import app.spectra.core.model.WalletError
import app.spectra.core.rpc.EndpointKind
import app.spectra.core.rpc.HttpTransport
import app.spectra.core.rpc.IdentityProbe
import app.spectra.core.rpc.JsonRpcClient
import app.spectra.core.rpc.JsonRpcException
import app.spectra.core.rpc.RpcEndpoint
import app.spectra.core.rpc.RpcManager
import app.spectra.core.rpc.arr
import app.spectra.core.rpc.get
import app.spectra.core.rpc.params
import app.spectra.core.rpc.str
import app.spectra.core.wallet.Keyring
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.math.BigInteger
import java.time.Instant

class EthereumIdentityProbe(private val client: JsonRpcClient) : IdentityProbe {
    override suspend fun verify(endpoint: RpcEndpoint) {
        val id = client.call(endpoint.url, "eth_chainId").str?.let { hexToBig(it) }
        if (id == null || id.toLong() != endpoint.network.evmChainId) {
            throw WalletError.WrongNetwork(endpoint.network.displayName, "chainId ${id ?: "?"}")
        }
    }
}

internal fun hexToBig(h: String): BigInteger = if (h == "0x" || h.isEmpty()) BigInteger.ZERO else BigInteger(h.removePrefix("0x"), 16)
internal fun BigInteger.toQuantity(): String = "0x" + toString(16)

data class Eip1559Fees(val baseFee: BigInteger, val maxPriorityFeePerGas: BigInteger, val maxFeePerGas: BigInteger)

class EthereumAdapter(
    private val rpc: RpcManager,
    private val client: JsonRpcClient,
    private val http: HttpTransport,
    /** Highest locally tracked pending nonce + 1 for the address, so in-flight transactions are not replaced by accident. */
    private val localNonceFloor: suspend (Network, String) -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChainAdapter {

    companion object {
        val MIN_PRIORITY_FEE: BigInteger = BigInteger.valueOf(100_000_000) // 0.1 gwei
        const val QUOTE_VALIDITY_MS = 45_000L
        const val DROP_AFTER_MS = 30 * 60_000L
    }

    private suspend fun call(n: Network, method: String, p: JsonElement = params()): JsonElement = rpc.read(n) { ep -> client.call(ep.url, method, p) }

    suspend fun nativeBalance(n: Network, address: String): BigInteger = hexToBig(call(n, "eth_getBalance", params(address, "latest")).str ?: "0x0")

    suspend fun ethCall(n: Network, to: String, data: ByteArray): String =
        call(n, "eth_call", params(mapOf("to" to to, "data" to "0x" + Hex.encode(data)), "latest")).str ?: "0x"

    suspend fun isContract(n: Network, address: String): Boolean = (call(n, "eth_getCode", params(address, "latest")).str ?: "0x").length > 2

    suspend fun tokenBalance(n: Network, contract: String, owner: String): BigInteger =
        Erc20Abi.decodeUint(ethCall(n, contract, Erc20Abi.balanceOf(owner))) ?: throw WalletError.UnsupportedToken("balanceOf failed")

    suspend fun allowance(n: Network, contract: String, owner: String, spender: String): BigInteger =
        Erc20Abi.decodeUint(ethCall(n, contract, Erc20Abi.allowance(owner, spender))) ?: BigInteger.ZERO

    suspend fun fees(n: Network, level: FeeLevel): Eip1559Fees {
        val h = call(n, "eth_feeHistory", params("0x5", "latest", listOf(10, 50, 90)))
        val base = h["baseFeePerGas"].arr?.lastOrNull()?.str?.let(::hexToBig) ?: throw WalletError.Unexpected("No base fee")
        val col = when (level) { FeeLevel.SLOW -> 0; FeeLevel.NORMAL -> 1; FeeLevel.FAST -> 2 }
        val rewards = h["reward"].arr?.mapNotNull { it.arr?.getOrNull(col)?.str?.let(::hexToBig) }?.sorted() ?: emptyList()
        val tip = (rewards.getOrNull(rewards.size / 2) ?: MIN_PRIORITY_FEE).max(MIN_PRIORITY_FEE)
        val baseMultiplier = when (level) { FeeLevel.SLOW -> BigInteger.valueOf(5).divide(BigInteger.valueOf(4)); else -> BigInteger.TWO }
        val maxFee = if (level == FeeLevel.SLOW) base * BigInteger.valueOf(5) / BigInteger.valueOf(4) + tip else base * baseMultiplier + tip
        return Eip1559Fees(base, tip, maxFee)
    }

    suspend fun nextNonce(n: Network, address: String): Long {
        val pending = hexToBig(call(n, "eth_getTransactionCount", params(address, "pending")).str ?: "0x0").toLong()
        val local = localNonceFloor(n, address) ?: 0
        return maxOf(pending, local)
    }

    suspend fun estimateGas(n: Network, from: String, to: String, value: BigInteger, data: ByteArray): BigInteger {
        val tx = mutableMapOf<String, Any>("from" to from, "to" to to, "value" to value.toQuantity())
        if (data.isNotEmpty()) tx["data"] = "0x" + Hex.encode(data)
        return try {
            hexToBig(call(n, "eth_estimateGas", params(tx)).str ?: throw WalletError.Unexpected("No gas estimate"))
        } catch (e: JsonRpcException) {
            throw if (e.message.contains("insufficient funds", true)) WalletError.InsufficientBalance(null, null)
            else WalletError.SimulationFailed(e.message.take(200))
        }
    }

    // ---------- Token discovery & metadata ----------

    private fun blockscoutBase(n: Network) = if (n.isTestnet) "https://eth-sepolia.blockscout.com/api/v2" else "https://eth.blockscout.com/api/v2"

    private suspend fun indexer(n: Network, path: String): JsonElement =
        rpc.read(n, EndpointKind.INDEXER) { ep -> http.getJson(ep.url.trimEnd('/') + path) }

    override suspend fun balances(account: AccountView, knownTokens: List<TokenInfo>): List<AssetBalance> {
        val n = account.network
        val me = account.primaryAddress
        val out = mutableListOf(AssetBalance(TokenInfo.native(n), Amount(nativeBalance(n, me), 18), me))
        val seen = HashSet<String>()
        // Indexer discovery (untrusted metadata). Balances are re-read from the node for manually added tokens.
        val discovered = runCatching { indexer(n, "/addresses/$me/token-balances") }.getOrNull()
        discovered.arr?.forEach { e ->
            val t = e["token"] ?: return@forEach
            if (t["type"].str != "ERC-20") return@forEach
            val addr = (t["address_hash"].str ?: t["address"].str)?.let { runCatching { EthereumAddress.checksum(it) }.getOrNull() } ?: return@forEach
            val decimals = t["decimals"].str?.toIntOrNull()
            val value = e["value"].str?.toBigIntegerOrNull() ?: return@forEach
            val known = knownTokens.firstOrNull { it.id.address.equals(addr, true) }
            val symbol = TokenInfo.sanitize(t["symbol"].str, 12)
            val name = TokenInfo.sanitize(t["name"].str, 40)
            val info = known ?: TokenInfo(AssetId(n, AssetKind.ERC20, addr), symbol, name, decimals, null,
                possibleSpam = TokenInfo.looksLikeSpam(symbol, name) || t["reputation"].str == "scam")
            if (info.decimals == null) return@forEach
            seen += addr.lowercase()
            out += AssetBalance(info, Amount(value, info.decimals), me)
        }
        for (t in knownTokens.filter { it.id.kind == AssetKind.ERC20 && it.id.address!!.lowercase() !in seen }) {
            val dec = t.decimals ?: continue
            runCatching { tokenBalance(n, t.id.address!!, me) }.getOrNull()?.let { out += AssetBalance(t, Amount(it, dec), me) }
        }
        return out
    }

    override suspend fun tokenInfo(network: Network, address: String): TokenInfo {
        val check = EthereumAddress.validate(address)
        if (check !is AddressCheck.Valid) throw WalletError.InvalidAddress("contract")
        val a = check.normalized
        if (!isContract(network, a)) throw WalletError.UnsupportedToken("No contract at this address on ${network.displayName}")
        val decimals = Erc20Abi.decodeUint(ethCall(network, a, Hex.decode(Erc20Abi.DECIMALS)))?.takeIf { it.bitLength() <= 8 }?.toInt()
            ?: throw WalletError.UnknownDecimals(a)
        val symbol = TokenInfo.sanitize(runCatching { Erc20Abi.decodeString(ethCall(network, a, Hex.decode(Erc20Abi.SYMBOL))) }.getOrNull(), 12)
        val name = TokenInfo.sanitize(runCatching { Erc20Abi.decodeString(ethCall(network, a, Hex.decode(Erc20Abi.NAME))) }.getOrNull(), 40)
        return TokenInfo(AssetId(network, AssetKind.ERC20, a), symbol, name, decimals, possibleSpam = TokenInfo.looksLikeSpam(symbol, name))
    }

    // ---------- History (indexer) ----------

    override suspend fun history(account: AccountView, limit: Int): List<HistoryItem> {
        val n = account.network
        val me = account.primaryAddress.lowercase()
        val items = ArrayList<HistoryItem>()
        runCatching { indexer(n, "/addresses/${account.primaryAddress}/transactions") }.getOrNull()?.get("items").arr?.take(limit)?.forEach { t ->
            val hash = t["hash"].str ?: return@forEach
            val from = t["from"]["hash"].str?.lowercase()
            val to = t["to"]["hash"].str?.lowercase()
            val value = t["value"].str?.toBigIntegerOrNull() ?: BigInteger.ZERO
            val isTokenOrCall = (t["tx_types"].arr?.any { it.str == "token_transfer" || it.str == "contract_call" } == true) && value.signum() == 0
            val dir = when { from == me && to == me -> TxDirection.SELF; from == me -> TxDirection.OUT; else -> TxDirection.IN }
            items += HistoryItem(n, hash, t["timestamp"].str?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() },
                if (isTokenOrCall) TxType.CONTRACT_CALL else if (dir == TxDirection.IN) TxType.RECEIVE else TxType.TRANSFER, dir,
                AssetId.native(n), "ETH", Amount(value, 18), if (dir == TxDirection.IN) from else to,
                when (t["status"].str) { "ok" -> TxState.CONFIRMED; "error" -> TxState.FAILED; else -> TxState.PENDING },
                t["fee"]["value"].str?.toBigIntegerOrNull()?.let { Amount(it, 18) }.takeIf { from == me })
        }
        runCatching { indexer(n, "/addresses/${account.primaryAddress}/token-transfers?type=ERC-20") }.getOrNull()?.get("items").arr?.take(limit)?.forEach { t ->
            val hash = t["transaction_hash"].str ?: t["tx_hash"].str ?: return@forEach
            val token = t["token"]
            val addr = token["address_hash"].str ?: token["address"].str
            val dec = t["total"]["decimals"].str?.toIntOrNull() ?: token["decimals"].str?.toIntOrNull() ?: return@forEach
            val value = t["total"]["value"].str?.toBigIntegerOrNull() ?: return@forEach
            val from = t["from"]["hash"].str?.lowercase()
            val dir = if (from == me) TxDirection.OUT else TxDirection.IN
            items.removeAll { it.txId == hash && it.type == TxType.CONTRACT_CALL }
            items += HistoryItem(n, hash, t["timestamp"].str?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() },
                TxType.TOKEN_TRANSFER, dir, addr?.let { AssetId(n, AssetKind.ERC20, runCatching { EthereumAddress.checksum(it) }.getOrDefault(it)) },
                TokenInfo.sanitize(token["symbol"].str, 12), Amount(value, dec),
                if (dir == TxDirection.IN) from else t["to"]["hash"].str, TxState.CONFIRMED)
        }
        return items.sortedByDescending { it.timestampSec ?: Long.MAX_VALUE }.take(limit)
    }

    // ---------- Transfers ----------

    override suspend fun prepareTransfer(request: TransferRequest): PreparedTransaction {
        val n = request.account.network
        val chainId = n.evmChainId!!
        val check = AddressValidator.validate(n, request.recipient)
        if (check !is AddressCheck.Valid) throw WalletError.InvalidAddress(check.toString())
        val from = request.account.primaryAddress
        val to = check.normalized
        val warnings = mutableListOf<TxWarning>()
        if (check.warning == AddressWarning.NO_CHECKSUM) warnings += TxWarning.NO_ADDRESS_CHECKSUM
        if (to.equals(from, true)) warnings += TxWarning.RECIPIENT_IS_SELF
        if (isContract(n, to)) warnings += TxWarning.RECIPIENT_IS_CONTRACT
        val fees = fees(n, request.feeLevel)
        val nonce = nextNonce(n, from)
        val balance = nativeBalance(n, from)
        val token = request.token
        val (txTo, value, data, type, recipientAmount) = if (token.id.isNative) {
            val gas = estimateGas(n, from, to, BigInteger.ONE, ByteArray(0))
            val gasLimit = if (gas == BigInteger.valueOf(21_000)) gas else gas * BigInteger.valueOf(12) / BigInteger.TEN
            val maxCost = gasLimit * fees.maxFeePerGas
            val amount = if (request.sendMax) balance - maxCost else request.amount.units
            if (amount.signum() <= 0 || amount + maxCost > balance) throw WalletError.InsufficientBalance(Amount(amount.max(BigInteger.ZERO) + maxCost, 18), Amount(balance, 18))
            if (request.sendMax) warnings += TxWarning.MAX_LEAVES_RESIDUAL
            val tx = Eip1559Transaction(chainId, BigInteger.valueOf(nonce), fees.maxPriorityFeePerGas, fees.maxFeePerGas, gasLimit, to, amount)
            return prepared(request, tx, TxType.TRANSFER, Amount(amount, 18), fees, warnings)
        } else {
            val contract = token.id.address ?: throw WalletError.UnsupportedToken("missing contract")
            val decimals = token.decimals ?: throw WalletError.UnknownDecimals(contract)
            val tokenBal = tokenBalance(n, contract, from)
            val amount = if (request.sendMax) tokenBal else request.amount.units
            if (amount.signum() <= 0 || amount > tokenBal) throw WalletError.InsufficientBalance(Amount(amount, decimals), Amount(tokenBal, decimals))
            if (to.equals(contract, true)) throw WalletError.InvalidAddress("Recipient is the token contract itself; tokens sent there are usually lost")
            val data = Erc20Abi.transfer(to, amount)
            Quint(contract, BigInteger.ZERO, data, TxType.TOKEN_TRANSFER, Amount(amount, decimals))
        }
        val gas = estimateGas(n, from, txTo, value, data)
        val gasLimit = gas * BigInteger.valueOf(13) / BigInteger.TEN
        if (gasLimit * fees.maxFeePerGas > balance) throw WalletError.InsufficientFeeBalance(Amount(gasLimit * fees.maxFeePerGas, 18), Amount(balance, 18))
        val tx = Eip1559Transaction(chainId, BigInteger.valueOf(nonce), fees.maxPriorityFeePerGas, fees.maxFeePerGas, gasLimit, txTo, value, data)
        return prepared(request, tx, type, recipientAmount, fees, warnings)
    }

    private data class Quint(val to: String, val value: BigInteger, val data: ByteArray, val type: TxType, val amount: Amount)

    private fun prepared(r: TransferRequest, tx: Eip1559Transaction, type: TxType, amount: Amount, fees: Eip1559Fees, warnings: List<TxWarning>) =
        PreparedTransaction(r, type, amount, Amount(tx.maxFee, 18), Amount.zero(18), warnings, clock() + QUOTE_VALIDITY_MS,
            "Max ${tx.maxFeePerGas} wei/gas × ${tx.gasLimit} gas (base ${fees.baseFee}, tip ${fees.maxPriorityFeePerGas}); nonce ${tx.nonce}",
            ChainPayload.Ethereum(tx))

    /** Builds an arbitrary contract call (swap, approval) with fresh fees and nonce. Effects are described by [Erc20Abi.describe]. */
    suspend fun prepareCall(account: AccountView, to: String, value: BigInteger, data: ByteArray, level: FeeLevel, gasLimitHint: BigInteger?): Eip1559Transaction {
        val n = account.network
        val fees = fees(n, level)
        val nonce = nextNonce(n, account.primaryAddress)
        val est = runCatching { estimateGas(n, account.primaryAddress, to, value, data) }.getOrElse { if (gasLimitHint != null) gasLimitHint else throw it }
        val gasLimit = est * BigInteger.valueOf(13) / BigInteger.TEN
        val balance = nativeBalance(n, account.primaryAddress)
        if (gasLimit * fees.maxFeePerGas + value > balance) throw WalletError.InsufficientFeeBalance(Amount(gasLimit * fees.maxFeePerGas + value, 18), Amount(balance, 18))
        return Eip1559Transaction(n.evmChainId!!, BigInteger.valueOf(nonce), fees.maxPriorityFeePerGas, fees.maxFeePerGas, gasLimit, to, value, data)
    }

    override fun sign(prepared: PreparedTransaction, keyring: Keyring): SignedTransaction {
        val tx = (prepared.payload as ChainPayload.Ethereum).tx
        return signTx(prepared.request.account, tx, keyring)
    }

    fun signTx(account: AccountView, tx: Eip1559Transaction, keyring: Keyring): SignedTransaction {
        require(tx.chainId == account.network.evmChainId) { "Chain id mismatch" }
        val path = account.scheme.path(account.accountIndex, account.network.env) ?: error("No path")
        val node = keyring.secpNode(path)
        try {
            require(EthereumAddress.fromPublicKey(node.publicKey).equals(account.primaryAddress, true)) { "Derived key does not match account address" }
            val signed = tx.sign(node.privateKey)
            return SignedTransaction(account.network, signed.hash, signed.rawHex, RawEncoding.HEX, nonce = tx.nonce.toLong())
        } finally { node.wipe() }
    }

    override suspend fun broadcast(signed: SignedTransaction): BroadcastOutcome = try {
        val hash = rpc.submit(signed.network) { ep -> client.call(ep.url, "eth_sendRawTransaction", params(signed.rawHex)).str }
        if (hash.equals(signed.txId, true)) BroadcastOutcome.Accepted(signed.txId) else BroadcastOutcome.Unknown(signed.txId, "RPC returned a different hash")
    } catch (e: JsonRpcException) {
        val m = e.message.lowercase()
        when {
            "already known" in m || "known transaction" in m -> BroadcastOutcome.Accepted(signed.txId)
            "nonce too low" in m || "replacement transaction underpriced" in m -> BroadcastOutcome.Rejected(WalletError.NonceConflict(signed.nonce ?: -1))
            "insufficient funds" in m -> BroadcastOutcome.Rejected(WalletError.InsufficientBalance(null, null))
            "chain id" in m || "chainid" in m -> BroadcastOutcome.Rejected(WalletError.WrongNetwork(signed.network.displayName, "rejected chain id"))
            else -> BroadcastOutcome.Rejected(WalletError.Rejected(e.message.take(200)))
        }
    } catch (e: WalletError.NoInternet) {
        BroadcastOutcome.Rejected(e)
    } catch (e: WalletError.RateLimited) {
        BroadcastOutcome.Rejected(e)
    } catch (e: Exception) {
        BroadcastOutcome.Unknown(signed.txId, e.message ?: e.javaClass.simpleName)
    }

    override suspend fun status(tracked: TrackedTransaction): StatusObservation {
        val n = tracked.network
        val receipt = call(n, "eth_getTransactionReceipt", params(tracked.txId)).takeIf { it !is JsonNull }
        if (receipt == null) {
            val tx = call(n, "eth_getTransactionByHash", params(tracked.txId)).takeIf { it !is JsonNull }
            if (tx != null) return StatusObservation(TxState.PENDING)
            val nonce = tracked.nonce
            if (nonce != null) {
                val latest = hexToBig(call(n, "eth_getTransactionCount", params(tracked.fromAddress, "latest")).str ?: "0x0").toLong()
                if (latest > nonce) return StatusObservation(TxState.REPLACED, error = "Nonce $nonce was used by a different transaction")
            }
            return if (clock() - tracked.submittedAtMillis > DROP_AFTER_MS) StatusObservation(TxState.DROPPED, error = "Not found in mempool or chain")
            else StatusObservation(TxState.PENDING)
        }
        val block = receipt["blockNumber"].str?.let(::hexToBig) ?: return StatusObservation(TxState.PENDING)
        val latest = hexToBig(call(n, "eth_blockNumber").str ?: "0x0")
        val finalized = runCatching { call(n, "eth_getBlockByNumber", params("finalized", false))["number"].str?.let(::hexToBig) }.getOrNull()
        val gasUsed = receipt["gasUsed"].str?.let(::hexToBig)
        val price = receipt["effectiveGasPrice"].str?.let(::hexToBig)
        val fee = if (gasUsed != null && price != null) Amount(gasUsed * price, 18) else null
        val confirmations = (latest - block + BigInteger.ONE).toLong().coerceAtLeast(0)
        return when (receipt["status"].str) {
            "0x1" -> StatusObservation(if (finalized != null && block <= finalized) TxState.FINALIZED else TxState.CONFIRMED, confirmations, block.toString(), fee)
            else -> StatusObservation(TxState.FAILED, confirmations, block.toString(), fee, "Execution reverted")
        }
    }
}
