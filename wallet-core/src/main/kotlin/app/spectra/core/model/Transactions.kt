package app.spectra.core.model

import kotlinx.serialization.Serializable

/** Explicit transaction lifecycle. RPC acceptance alone never moves a transaction past [PENDING]. */
@Serializable
enum class TxState {
    DRAFT, ESTIMATED, AWAITING_AUTHORIZATION, SIGNED, BROADCASTING, PENDING,
    CONFIRMED, FINALIZED, FAILED, EXPIRED, REPLACED, DROPPED, UNKNOWN;

    val isTerminal: Boolean get() = this in setOf(FINALIZED, FAILED, EXPIRED, REPLACED, DROPPED)
    val isInFlight: Boolean get() = this in setOf(SIGNED, BROADCASTING, PENDING, CONFIRMED, UNKNOWN)
}

object TxStateMachine {
    private val allowed: Map<TxState, Set<TxState>> = mapOf(
        TxState.DRAFT to setOf(TxState.ESTIMATED),
        TxState.ESTIMATED to setOf(TxState.ESTIMATED, TxState.AWAITING_AUTHORIZATION, TxState.DRAFT),
        TxState.AWAITING_AUTHORIZATION to setOf(TxState.SIGNED, TxState.ESTIMATED),
        TxState.SIGNED to setOf(TxState.BROADCASTING),
        TxState.BROADCASTING to setOf(TxState.PENDING, TxState.FAILED, TxState.UNKNOWN),
        // Reorgs may move a confirmed transaction back to pending.
        TxState.PENDING to setOf(TxState.PENDING, TxState.CONFIRMED, TxState.FINALIZED, TxState.FAILED, TxState.EXPIRED, TxState.REPLACED, TxState.DROPPED, TxState.UNKNOWN),
        TxState.CONFIRMED to setOf(TxState.CONFIRMED, TxState.FINALIZED, TxState.PENDING, TxState.FAILED, TxState.DROPPED, TxState.REPLACED),
        TxState.UNKNOWN to setOf(TxState.PENDING, TxState.CONFIRMED, TxState.FINALIZED, TxState.FAILED, TxState.EXPIRED, TxState.REPLACED, TxState.DROPPED, TxState.UNKNOWN),
    )

    fun canTransition(from: TxState, to: TxState) = allowed[from]?.contains(to) == true

    fun transition(from: TxState, to: TxState): TxState {
        require(canTransition(from, to)) { "Illegal transaction state transition $from -> $to" }
        return to
    }
}

@Serializable
enum class TxType { TRANSFER, TOKEN_TRANSFER, RECEIVE, SWAP, APPROVAL, STAKE_DELEGATE, STAKE_DEACTIVATE, STAKE_WITHDRAW, CONTRACT_CALL, UNKNOWN }

@Serializable
enum class TxDirection { OUT, IN, SELF }

/** Result of reconciling a transaction with the network. */
data class StatusObservation(
    val state: TxState,
    val confirmations: Long? = null,
    val blockRef: String? = null,
    val feePaid: Amount? = null,
    val error: String? = null,
)

/** A history entry discovered from the network (indexer or RPC). */
data class HistoryItem(
    val network: Network,
    val txId: String,
    val timestampSec: Long?,
    val type: TxType,
    val direction: TxDirection,
    val asset: AssetId?,
    val symbol: String?,
    val amount: Amount?,
    val counterparty: String?,
    val state: TxState,
    val fee: Amount? = null,
)
