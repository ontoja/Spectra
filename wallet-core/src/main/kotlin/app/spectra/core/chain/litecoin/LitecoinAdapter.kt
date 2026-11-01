package app.spectra.core.chain.litecoin

import app.spectra.core.chain.AccountView
import app.spectra.core.chain.AddressCheck
import app.spectra.core.chain.BroadcastOutcome
import app.spectra.core.chain.ChainAdapter
import app.spectra.core.chain.ChainPayload
import app.spectra.core.chain.FeeLevel
import app.spectra.core.chain.LitecoinAddress
import app.spectra.core.chain.PreparedTransaction
import app.spectra.core.chain.RawEncoding
import app.spectra.core.chain.SignedTransaction
import app.spectra.core.chain.TrackedTransaction
import app.spectra.core.chain.TransferRequest
import app.spectra.core.chain.TxWarning
import app.spectra.core.crypto.Bip32PublicNode
import app.spectra.core.crypto.DerivationPath
import app.spectra.core.crypto.Hashes
import app.spectra.core.encoding.Hex
import app.spectra.core.model.AddressRole
import app.spectra.core.model.Amount
import app.spectra.core.model.AssetBalance
import app.spectra.core.model.AssetId
import app.spectra.core.model.DerivationScheme
import app.spectra.core.model.HistoryItem
import app.spectra.core.model.Network
import app.spectra.core.model.StatusObservation
import app.spectra.core.model.TokenInfo
import app.spectra.core.model.TxDirection
import app.spectra.core.model.TxState
import app.spectra.core.model.TxType
import app.spectra.core.model.WalletError
import app.spectra.core.rpc.HttpStatusException
import app.spectra.core.rpc.HttpTransport
import app.spectra.core.rpc.IdentityProbe
import app.spectra.core.rpc.RpcEndpoint
import app.spectra.core.rpc.RpcManager
import app.spectra.core.rpc.TtlCache
import app.spectra.core.rpc.arr
import app.spectra.core.rpc.bool
import app.spectra.core.rpc.dbl
import app.spectra.core.rpc.get
import app.spectra.core.rpc.long
import app.spectra.core.rpc.str
import app.spectra.core.wallet.Keyring
import kotlinx.serialization.json.JsonElement
import java.math.BigInteger

class EsploraIdentityProbe(private val http: HttpTransport) : IdentityProbe {
    override suspend fun verify(endpoint: RpcEndpoint) {
        val genesis = http.getText(endpoint.url.trimEnd('/') + "/block-height/0").trim()
        if (genesis != endpoint.network.genesisHash) throw WalletError.WrongNetwork(endpoint.network.displayName, "genesis ${genesis.take(12)}…")
    }
}

data class DiscoveredAddress(val address: String, val role: AddressRole, val index: Int, val path: String, val publicKey: ByteArray, val txCount: Long, val confirmed: Long, val unconfirmed: Long)

data class LitecoinAccountState(val addresses: List<DiscoveredAddress>) {
    val confirmed get() = addresses.sumOf { it.confirmed }
    val unconfirmed get() = addresses.sumOf { it.unconfirmed }
    fun nextUnused(role: AddressRole) = addresses.filter { it.role == role && it.txCount == 0L }.minByOrNull { it.index }
}

/** Litecoin via an Esplora-compatible REST API (default: litecoinspace.org). Spending supports BIP-84 only. */
class LitecoinAdapter(
    private val rpc: RpcManager,
    private val http: HttpTransport,
    private val clock: () -> Long = System::currentTimeMillis,
) : ChainAdapter {
    companion object {
        const val GAP_LIMIT = 20
        const val FINAL_CONFIRMATIONS = 6L
        const val DROP_AFTER_MS = 6 * 3600_000L
        const val QUOTE_VALIDITY_MS = 120_000L
    }

    private val stateCache = TtlCache<String, LitecoinAccountState>(30_000)

    private suspend fun get(n: Network, path: String): JsonElement = rpc.read(n) { ep -> http.getJson(ep.url.trimEnd('/') + path) }
    private suspend fun getText(n: Network, path: String): String = rpc.read(n) { ep -> http.getText(ep.url.trimEnd('/') + path) }

    private fun addressFor(node: Bip32PublicNode, scheme: DerivationScheme, n: Network): String = when (scheme) {
        DerivationScheme.LTC_BIP84 -> LitecoinAddress.p2wpkh(node.publicKey, n.env)
        DerivationScheme.LTC_BIP49 -> LitecoinAddress.p2shP2wpkh(node.publicKey, n.env)
        DerivationScheme.LTC_BIP44 -> LitecoinAddress.p2pkh(node.publicKey, n.env)
        else -> error("Not a Litecoin scheme")
    }

    /** Gap-limit discovery over receive and change chains using the account public node. */
    suspend fun discover(account: AccountView, force: Boolean = false): LitecoinAccountState {
        val key = "${account.network.id}:${account.scheme}:${account.accountIndex}:${account.accountXpub}"
        if (!force) stateCache.get(key)?.let { return it }
        val xpub = Bip32PublicNode.deserialize(account.accountXpub ?: throw WalletError.Unexpected("Missing Litecoin account public key"))
        val base = account.scheme.path(account.accountIndex, account.network.env)!!
        val found = ArrayList<DiscoveredAddress>()
        for (role in AddressRole.entries) {
            val chainNode = xpub.deriveChild(role.chainIndex.toLong())
            var gap = 0
            var i = 0
            while (gap < GAP_LIMIT && i < 1000) {
                val child = chainNode.deriveChild(i.toLong())
                val addr = addressFor(child, account.scheme, account.network)
                val info = get(account.network, "/address/$addr")
                val cs = info["chain_stats"]; val ms = info["mempool_stats"]
                val txCount = (cs["tx_count"].long ?: 0) + (ms["tx_count"].long ?: 0)
                val confirmed = (cs["funded_txo_sum"].long ?: 0) - (cs["spent_txo_sum"].long ?: 0)
                val unconfirmed = (ms["funded_txo_sum"].long ?: 0) - (ms["spent_txo_sum"].long ?: 0)
                found += DiscoveredAddress(addr, role, i, base.child(role.chainIndex.toLong()).child(i.toLong()).toString(), child.publicKey, txCount, confirmed, unconfirmed)
                gap = if (txCount == 0L) gap + 1 else 0
                i++
                // Only the first unused address is needed beyond the gap for display; stop early on the change chain.
                if (role == AddressRole.CHANGE && gap >= 5) break
            }
        }
        return LitecoinAccountState(found).also { stateCache.put(key, it) }
    }

    override suspend fun balances(account: AccountView, knownTokens: List<TokenInfo>): List<AssetBalance> {
        val s = discover(account, force = true)
        val pending = s.unconfirmed
        return listOf(AssetBalance(TokenInfo.native(account.network), Amount.of(s.confirmed + minOf(0, pending), 8), s.nextUnused(AddressRole.RECEIVE)?.address,
            if (pending > 0) Amount.of(pending, 8) else null))
    }

    override suspend fun tokenInfo(network: Network, address: String): TokenInfo = throw WalletError.FeatureUnavailable("Litecoin has no token standard supported by Spectra")

    override suspend fun history(account: AccountView, limit: Int): List<HistoryItem> {
        val s = discover(account)
        val mine = s.addresses.map { it.address }.toSet()
        val txs = LinkedHashMap<String, JsonElement>()
        for (a in s.addresses.filter { it.txCount > 0 }.take(10)) {
            get(account.network, "/address/${a.address}/txs").arr?.forEach { t -> t["txid"].str?.let { txs.putIfAbsent(it, t) } }
        }
        return txs.values.map { t ->
            val received = t["vout"].arr?.filter { it["scriptpubkey_address"].str in mine }?.sumOf { it["value"].long ?: 0 } ?: 0
            val sent = t["vin"].arr?.filter { it["prevout"]["scriptpubkey_address"].str in mine }?.sumOf { it["prevout"]["value"].long ?: 0 } ?: 0
            val fee = t["fee"].long ?: 0
            val delta = received - sent
            val outgoing = sent > 0
            val counterparty = if (outgoing) t["vout"].arr?.firstOrNull { it["scriptpubkey_address"].str !in mine }?.get("scriptpubkey_address").str
                else t["vin"].arr?.firstOrNull()?.get("prevout")?.get("scriptpubkey_address").str
            val confirmed = t["status"]["confirmed"].bool == true
            HistoryItem(account.network, t["txid"].str!!, t["status"]["block_time"].long, if (outgoing) TxType.TRANSFER else TxType.RECEIVE,
                when { !outgoing -> TxDirection.IN; counterparty == null -> TxDirection.SELF; else -> TxDirection.OUT },
                AssetId.native(account.network), "LTC", Amount.of(if (outgoing) -(delta + fee) else delta, 8), counterparty,
                if (confirmed) TxState.CONFIRMED else TxState.PENDING, if (outgoing) Amount.of(fee, 8) else null)
        }.sortedByDescending { it.timestampSec ?: Long.MAX_VALUE }.take(limit)
    }

    suspend fun feeRate(n: Network, level: FeeLevel): Long {
        val rec = runCatching { get(n, "/v1/fees/recommended") }.getOrNull()
        val v = if (rec != null) when (level) { FeeLevel.SLOW -> rec["hourFee"].dbl; FeeLevel.NORMAL -> rec["halfHourFee"].dbl; FeeLevel.FAST -> rec["fastestFee"].dbl }
        else get(n, "/fee-estimates").let { when (level) { FeeLevel.SLOW -> it["6"].dbl; FeeLevel.NORMAL -> it["3"].dbl; FeeLevel.FAST -> it["1"].dbl } }
        return kotlin.math.ceil(v ?: throw WalletError.Unexpected("Fee estimate unavailable")).toLong().coerceAtLeast(1)
    }

    override suspend fun prepareTransfer(request: TransferRequest): PreparedTransaction {
        val acc = request.account
        val n = acc.network
        if (!acc.scheme.canSpend) throw WalletError.FeatureUnavailable("Spending from ${acc.scheme.label} accounts is not supported in v0.1")
        val check = LitecoinAddress.validate(request.recipient, n.env)
        if (check !is AddressCheck.Valid) throw WalletError.InvalidAddress(check.toString())
        val recipientScript = LitecoinAddress.scriptPubKey(check.normalized, n.env)!!
        val state = discover(acc, force = true)
        val warnings = mutableListOf<TxWarning>()
        if (state.addresses.any { it.address == check.normalized }) warnings += TxWarning.RECIPIENT_IS_SELF
        val utxos = ArrayList<Utxo>()
        val pubkeys = HashMap<String, ByteArray>()
        for (a in state.addresses.filter { it.txCount > 0 }) {
            get(n, "/address/${a.address}/utxo").arr?.forEach { u ->
                utxos += Utxo(u["txid"].str!!, u["vout"].long!!.toInt(), u["value"].long!!, u["status"]["confirmed"].bool == true, a.address, a.path)
            }
            pubkeys[a.path] = a.publicKey
        }
        if (utxos.any { !it.confirmed }) warnings += TxWarning.UNCONFIRMED_FUNDS_EXCLUDED
        val change = state.nextUnused(AddressRole.CHANGE) ?: throw WalletError.Unexpected("No change address available")
        val changeScript = LitecoinAddress.scriptPubKey(change.address, n.env)!!
        val rate = feeRate(n, request.feeLevel)
        val selection = CoinSelector.select(utxos, request.amount.units.toLong(), rate, recipientScript, changeScript, request.sendMax).getOrElse { e ->
            throw when (val err = (e as? CoinSelectionException)?.error) {
                is CoinSelectionError.DustOutput -> WalletError.DustAmount(Amount.of(err.minimum, 8))
                is CoinSelectionError.InsufficientConfirmedFunds -> WalletError.InsufficientBalance(Amount.of(err.required, 8), Amount.of(err.confirmedAvailable, 8))
                else -> WalletError.Unexpected("Coin selection failed")
            }
        }
        val outputs = mutableListOf(TxOutput(recipientScript, selection.recipientValue))
        if (selection.change > 0) outputs += TxOutput(changeScript, selection.change)
        val estimatedFeeAtRate = selection.vsize * rate
        if (selection.fee > estimatedFeeAtRate) warnings += TxWarning.DUST_CHANGE_ADDED_TO_FEE
        return PreparedTransaction(request, TxType.TRANSFER, Amount.of(selection.recipientValue, 8), Amount.of(selection.fee, 8), Amount.zero(8), warnings,
            clock() + QUOTE_VALIDITY_MS, "$rate lit/vB × ~${selection.vsize} vB; ${selection.inputs.size} input(s)" + if (selection.change > 0) "; change → ${change.address}" else "",
            ChainPayload.Litecoin(selection, outputs, if (selection.change > 0) change.address else null, pubkeys))
    }

    override fun sign(prepared: PreparedTransaction, keyring: Keyring): SignedTransaction {
        val p = prepared.payload as ChainPayload.Litecoin
        val inputs = p.selection.inputs.map { u -> SigningInput(u, p.inputPubKeys[u.path] ?: error("Missing public key for input")) }
        val signed = LitecoinTransactions.sign(inputs, p.outputs) { u ->
            val node = keyring.secpNode(DerivationPath.parse(u.path))
            val pubOk = node.publicKey.contentEquals(p.inputPubKeys[u.path]) &&
                LitecoinAddress.p2wpkh(node.publicKey, prepared.request.account.network.env) == u.address
            val key = node.privateKey.copyOf()
            node.wipe()
            require(pubOk) { "Derived key does not match UTXO owner" }
            key
        }
        return SignedTransaction(prepared.request.account.network, signed.txid, signed.rawHex, RawEncoding.HEX,
            spentOutpoints = p.selection.inputs.map { "${it.txid}:${it.vout}" })
    }

    override suspend fun broadcast(signed: SignedTransaction): BroadcastOutcome = try {
        val txid = rpc.submit(signed.network) { ep -> http.postText(ep.url.trimEnd('/') + "/tx", signed.rawHex) }.trim()
        if (txid == signed.txId) BroadcastOutcome.Accepted(txid) else BroadcastOutcome.Unknown(signed.txId, "API returned a different txid")
    } catch (e: HttpStatusException) {
        val b = e.body.lowercase()
        when {
            "already in block chain" in b || "txn-already-known" in b || "txn-already-in-mempool" in b -> BroadcastOutcome.Accepted(signed.txId)
            "missingorspent" in b || "missing-inputs" in b || "txn-mempool-conflict" in b -> BroadcastOutcome.Rejected(WalletError.UtxoConflict())
            e.code in 400..499 -> BroadcastOutcome.Rejected(WalletError.Rejected(e.body.take(200)))
            else -> BroadcastOutcome.Unknown(signed.txId, "HTTP ${e.code}")
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
        val st = try { get(n, "/tx/${tracked.txId}/status") } catch (e: HttpStatusException) {
            if (e.code == 404) null else throw e
        }
        if (st == null) {
            return if (clock() - tracked.submittedAtMillis > DROP_AFTER_MS) StatusObservation(TxState.DROPPED, error = "Not found by the indexer")
            else StatusObservation(TxState.PENDING)
        }
        if (st["confirmed"].bool != true) return StatusObservation(TxState.PENDING)
        val height = st["block_height"].long ?: return StatusObservation(TxState.PENDING)
        val tip = getText(n, "/blocks/tip/height").trim().toLong()
        val conf = tip - height + 1
        return StatusObservation(if (conf >= FINAL_CONFIRMATIONS) TxState.FINALIZED else TxState.CONFIRMED, conf, height.toString())
    }

    /** Discovery for legacy/nested schemes on import: returns total balance found (display only). */
    suspend fun scanBalance(account: AccountView): Long = discover(account, force = true).let { it.confirmed + it.unconfirmed }
}

