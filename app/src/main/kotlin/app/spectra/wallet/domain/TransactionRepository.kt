package app.spectra.wallet.domain

import app.spectra.core.chain.BroadcastOutcome
import app.spectra.core.chain.PreparedTransaction
import app.spectra.core.chain.RawEncoding
import app.spectra.core.chain.SignedTransaction
import app.spectra.core.chain.TrackedTransaction
import app.spectra.core.chain.TransferRequest
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.TxDirection
import app.spectra.core.model.TxState
import app.spectra.core.model.TxStateMachine
import app.spectra.core.model.TxType
import app.spectra.core.model.WalletError
import app.spectra.wallet.BuildConfig
import app.spectra.wallet.data.TransactionEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.coroutines.coroutineContext

sealed interface ExecuteResult {
    data class Submitted(val recordId: String) : ExecuteResult
    data object Cancelled : ExecuteResult
    data class Failed(val recordId: String?, val error: Throwable) : ExecuteResult
}

class TransactionRepository(private val c: AppContainer) {
    private val dao get() = c.db.transactions()
    private val monitorLock = Mutex()

    val all: Flow<List<TransactionEntity>> = dao.all()
    fun byId(id: String) = dao.byId(id)

    companion object {
        /** Mainnet signing is gated at build time until security review and integration testing are complete. */
        fun signingAllowed(network: Network) = network.env == NetworkEnv.TESTNET || BuildConfig.ALLOW_MAINNET_SIGNING

        fun requireSigningAllowed(network: Network) {
            if (!signingAllowed(network)) throw WalletError.FeatureUnavailable(
                "Podpisywanie w sieci głównej jest wyłączone w tej eksperymentalnej wersji. Użyj sieci testowej.")
        }
    }

    suspend fun prepare(request: TransferRequest): PreparedTransaction = c.adapter(request.account.network).prepareTransfer(request)

    /**
     * Authorise → sign locally → persist signed bytes and id → broadcast once → record outcome.
     * An RPC acceptance moves the record to PENDING only; confirmation comes from [monitorOnce].
     */
    suspend fun execute(prepared: PreparedTransaction, vaultId: String, accountId: String): ExecuteResult {
        val n = prepared.request.account.network
        requireSigningAllowed(n)
        if (prepared.expiresAtMillis != null && System.currentTimeMillis() > prepared.expiresAtMillis) return ExecuteResult.Failed(null, WalletError.QuoteExpired())
        val r = prepared.request
        val signed = try {
            c.auth.withKeyring(vaultId, "Potwierdź transakcję", "Wysyłasz ${r.token.symbol} w sieci ${n.displayName}") { k -> c.adapter(n).sign(prepared, k) }
        } catch (e: Throwable) { return ExecuteResult.Failed(null, e) } ?: return ExecuteResult.Cancelled
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val record = TransactionEntity(id, accountId, n.id, signed.txId, prepared.type.name, TxDirection.OUT.name, TxState.SIGNED.name, r.token.id.key, r.token.symbol,
            prepared.recipientAmount.units.toString(), prepared.recipientAmount.decimals, prepared.networkFee.units.toString(), r.recipient, r.account.primaryAddress,
            signed.rawHex, signed.encoding.name, signed.lastValidBlockHeight, signed.nonce, null, null, "LOCAL", null, now, null, now, now / 1000)
        dao.upsert(record)
        return submit(record, signed)
    }

    /** Broadcasts an already-signed transaction exactly once and records the outcome. */
    suspend fun submit(record: TransactionEntity, signed: SignedTransaction): ExecuteResult {
        var rec = record.copy(state = TxStateMachine.transition(TxState.valueOf(record.state), TxState.BROADCASTING).name, submittedAt = System.currentTimeMillis())
        dao.upsert(rec)
        val outcome = c.adapter(signed.network).broadcast(signed)
        rec = when (outcome) {
            is BroadcastOutcome.Accepted -> rec.copy(state = TxState.PENDING.name)
            is BroadcastOutcome.Unknown -> rec.copy(state = TxState.UNKNOWN.name, error = "Nie wiadomo, czy transakcja dotarła do sieci: ${outcome.reason}")
            is BroadcastOutcome.Rejected -> rec.copy(state = TxState.FAILED.name, error = outcome.error.message?.take(200))
        }
        dao.upsert(rec.copy(updatedAt = System.currentTimeMillis()))
        return if (outcome is BroadcastOutcome.Rejected) ExecuteResult.Failed(rec.id, outcome.error) else ExecuteResult.Submitted(rec.id)
    }

    /**
     * For records in UNKNOWN/DROPPED state: re-checks status by id first; only if the network has no record of it does it
     * re-send the *same* signed bytes (identical id, so it can never double-spend).
     */
    suspend fun reconcileAndRebroadcast(recordId: String): ExecuteResult {
        val rec = dao.get(recordId) ?: return ExecuteResult.Failed(null, WalletError.Unexpected("Not found"))
        requireSigningAllowed(Network.fromId(rec.networkId))
        val obs = checkStatus(rec)
        if (obs != null && obs.state != TxState.PENDING && obs.state != TxState.DROPPED && obs.state != TxState.UNKNOWN) return ExecuteResult.Submitted(recordId)
        if (obs?.state == TxState.EXPIRED) return ExecuteResult.Failed(recordId, WalletError.BlockhashExpired())
        val raw = rec.rawSigned ?: return ExecuteResult.Failed(recordId, WalletError.Unexpected("No signed data stored"))
        val signed = SignedTransaction(Network.fromId(rec.networkId), rec.txId!!, raw, RawEncoding.valueOf(rec.rawEncoding ?: "HEX"), rec.lastValidBlockHeight, rec.nonce)
        val outcome = c.adapter(signed.network).broadcast(signed)
        val next = when (outcome) {
            is BroadcastOutcome.Accepted -> TxState.PENDING
            is BroadcastOutcome.Unknown -> TxState.UNKNOWN
            is BroadcastOutcome.Rejected -> TxState.valueOf(rec.state)
        }
        dao.upsert(rec.copy(state = next.name, error = (outcome as? BroadcastOutcome.Rejected)?.error?.message ?: rec.error, updatedAt = System.currentTimeMillis()))
        return if (outcome is BroadcastOutcome.Rejected) ExecuteResult.Failed(recordId, outcome.error) else ExecuteResult.Submitted(recordId)
    }

    private suspend fun checkStatus(rec: TransactionEntity) = rec.txId?.let { txId ->
        runCatching {
            c.adapter(Network.fromId(rec.networkId)).status(TrackedTransaction(Network.fromId(rec.networkId), txId, rec.fromAddress ?: "", rec.nonce, rec.lastValidBlockHeight, rec.submittedAt ?: rec.createdAt))
        }.getOrNull()
    }

    /** One reconciliation pass over all in-flight local transactions. Safe to run after process restart. */
    suspend fun monitorOnce() = monitorLock.withLock {
        for (rec in dao.inFlight()) {
            val current = TxState.valueOf(rec.state)
            if (current == TxState.SIGNED) {
                // Signed but never handed to the network (e.g. app killed). Leave for the user to submit or discard; never auto-send.
                continue
            }
            val obs = checkStatus(rec) ?: continue
            val next = if (TxStateMachine.canTransition(current, obs.state)) obs.state else current
            if (next != current || obs.confirmations != rec.confirmations) {
                dao.upsert(rec.copy(state = next.name, confirmations = obs.confirmations, feeUnits = obs.feePaid?.units?.toString() ?: rec.feeUnits,
                    error = obs.error ?: rec.error, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun monitorLoop() {
        while (coroutineContext.isActive) {
            runCatching { monitorOnce() }
            runCatching { c.swaps.monitorOnce() }
            delay(12_000)
        }
    }

    /** Pulls recent history from indexers/RPC for display; locally initiated records take precedence. */
    suspend fun syncHistory(accountId: String, env: NetworkEnv): Map<Network, Throwable> {
        val acc = c.wallets.account(accountId) ?: return emptyMap()
        val errors = HashMap<Network, Throwable>()
        for (chain in app.spectra.core.model.Chain.entries) {
            val n = Network(chain, env)
            val view = acc.view(n) ?: continue
            try {
                c.adapter(n).history(view).forEach { h ->
                    val existing = dao.find(accountId, n.id, h.txId)
                    if (existing != null && existing.source == "LOCAL") return@forEach
                    val now = System.currentTimeMillis()
                    dao.upsert(TransactionEntity(existing?.id ?: UUID.randomUUID().toString(), accountId, n.id, h.txId, h.type.name, h.direction.name, h.state.name,
                        h.asset?.key, h.symbol, h.amount?.units?.toString(), h.amount?.decimals, h.fee?.units?.toString(), h.counterparty, view.primaryAddress,
                        null, null, null, null, null, null, "REMOTE", null, existing?.createdAt ?: now, null, now, h.timestampSec))
                }
            } catch (e: Throwable) { errors[n] = e }
        }
        return errors
    }

    /** Records a locally signed non-transfer operation (swap, approval, staking) and broadcasts it. */
    suspend fun recordAndSubmit(accountId: String, signed: SignedTransaction, type: TxType, symbol: String?, amountUnits: String?, decimals: Int?, feeUnits: String?, counterparty: String?, fromAddress: String, swapId: String? = null): ExecuteResult {
        val now = System.currentTimeMillis()
        val rec = TransactionEntity(UUID.randomUUID().toString(), accountId, signed.network.id, signed.txId, type.name, TxDirection.OUT.name, TxState.SIGNED.name, null, symbol,
            amountUnits, decimals, feeUnits, counterparty, fromAddress, signed.rawHex, signed.encoding.name, signed.lastValidBlockHeight, signed.nonce, null, null, "LOCAL", swapId, now, null, now, now / 1000)
        dao.upsert(rec)
        return submit(rec, signed)
    }

    suspend fun discardSigned(recordId: String) {
        val rec = dao.get(recordId) ?: return
        if (rec.state == TxState.SIGNED.name) dao.upsert(rec.copy(state = TxState.DROPPED.name, error = "Odrzucono przed wysłaniem", updatedAt = System.currentTimeMillis()))
    }

    suspend fun submitStoredSigned(recordId: String): ExecuteResult {
        val rec = dao.get(recordId) ?: return ExecuteResult.Failed(null, WalletError.Unexpected("Not found"))
        if (rec.state != TxState.SIGNED.name) return ExecuteResult.Failed(recordId, WalletError.Unexpected("Not in signed state"))
        val n = Network.fromId(rec.networkId)
        requireSigningAllowed(n)
        if (n.chain == app.spectra.core.model.Chain.SOLANA && rec.lastValidBlockHeight != null && runCatching { c.solanaRpc.blockHeight(n) }.getOrDefault(0) > rec.lastValidBlockHeight) {
            dao.upsert(rec.copy(state = TxState.DROPPED.name, error = "Blockhash wygasł przed wysłaniem", updatedAt = System.currentTimeMillis()))
            return ExecuteResult.Failed(recordId, WalletError.BlockhashExpired())
        }
        return submit(rec, SignedTransaction(n, rec.txId!!, rec.rawSigned!!, RawEncoding.valueOf(rec.rawEncoding!!), rec.lastValidBlockHeight, rec.nonce))
    }
}
