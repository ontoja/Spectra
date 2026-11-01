package app.spectra.core.staking

import app.spectra.core.chain.AccountView
import app.spectra.core.chain.FeeLevel
import app.spectra.core.chain.solana.PreparedSolanaMessage
import app.spectra.core.chain.solana.PublicKey
import app.spectra.core.chain.solana.SolanaAdapter
import app.spectra.core.chain.solana.SolanaPrograms
import app.spectra.core.chain.solana.SolanaRpc
import app.spectra.core.chain.solana.StakeInstructions
import app.spectra.core.chain.solana.SystemInstructions
import app.spectra.core.model.Amount
import app.spectra.core.model.Network
import app.spectra.core.model.WalletError
import app.spectra.core.rpc.arr
import app.spectra.core.rpc.dbl
import app.spectra.core.rpc.get
import app.spectra.core.rpc.long
import app.spectra.core.rpc.str

data class Validator(
    val votePubkey: String,
    val nodePubkey: String,
    val commission: Int,
    val activatedStakeLamports: Long,
    val delinquent: Boolean,
    val lastVote: Long?,
)

/**
 * Lifecycle of a stake account, derived from delegation epochs. Warm-up/cool-down rate limits can stretch
 * activation or deactivation over more than one epoch; the label is then conservative ("activating"/"deactivating").
 */
enum class StakeState { INITIALIZED, ACTIVATING, ACTIVE, DEACTIVATING, INACTIVE }

data class StakePosition(
    val stakeAccount: String,
    val lamports: Long,
    val delegatedLamports: Long,
    val voter: String?,
    val activationEpoch: Long?,
    val deactivationEpoch: Long?,
    val staker: String,
    val withdrawer: String,
    val rentReserve: Long,
    val state: StakeState,
) {
    val canDeactivate get() = state == StakeState.ACTIVE || state == StakeState.ACTIVATING
    val canWithdraw get() = state == StakeState.INACTIVE || state == StakeState.INITIALIZED
}

data class EpochStatus(val epoch: Long, val slotIndex: Long, val slotsInEpoch: Long) {
    val progress: Double get() = if (slotsInEpoch == 0L) 0.0 else slotIndex.toDouble() / slotsInEpoch
}

data class StakeReward(val stakeAccount: String, val epoch: Long, val lamports: Long, val postBalance: Long)

class SolanaStakingService(private val rpc: SolanaRpc, private val adapter: SolanaAdapter) {
    companion object {
        const val MAX_EPOCH = "18446744073709551615"
    }

    suspend fun validators(n: Network): List<Validator> {
        val v = rpc.voteAccounts(n)
        fun parse(list: kotlinx.serialization.json.JsonElement?, delinquent: Boolean) = list.arr?.mapNotNull { e ->
            Validator(e["votePubkey"].str ?: return@mapNotNull null, e["nodePubkey"].str ?: "", e["commission"].long?.toInt() ?: return@mapNotNull null,
                e["activatedStake"].long ?: 0, delinquent, e["lastVote"].long)
        } ?: emptyList()
        return (parse(v["current"], false).sortedByDescending { it.activatedStakeLamports } + parse(v["delinquent"], true))
    }

    suspend fun epoch(n: Network): EpochStatus {
        val e = rpc.epochInfo(n)
        return EpochStatus(e["epoch"].long ?: 0, e["slotIndex"].long ?: 0, e["slotsInEpoch"].long ?: 0)
    }

    /** Network inflation (validator + foundation) — informative only, not a promised yield. */
    suspend fun inflationPercent(n: Network): Double? = rpc.inflationRate(n)["validator"].dbl?.times(100)

    suspend fun positions(n: Network, owner: String): List<StakePosition> {
        val current = epoch(n).epoch
        val accounts = rpc.programAccounts(n, SolanaPrograms.STAKE.base58, listOf(
            mapOf("dataSize" to StakeInstructions.STAKE_ACCOUNT_SPACE),
            mapOf("memcmp" to mapOf("offset" to 44, "bytes" to owner)),
        ))
        return accounts.mapNotNull { a ->
            val addr = a["pubkey"].str ?: return@mapNotNull null
            val lamports = a["account"]["lamports"].long ?: 0
            val parsed = a["account"]["data"]["parsed"]
            val info = parsed["info"]
            val meta = info["meta"]
            val delegation = info["stake"]["delegation"]
            val act = delegation["activationEpoch"].str?.toLongOrNull()
            val deactStr = delegation["deactivationEpoch"].str
            val deact = deactStr?.takeIf { it != MAX_EPOCH }?.toLongOrNull()
            val state = when {
                parsed["type"].str == "initialized" || delegation == null -> StakeState.INITIALIZED
                deact != null && current > deact -> StakeState.INACTIVE
                deact != null -> StakeState.DEACTIVATING
                act != null && act >= current -> StakeState.ACTIVATING
                else -> StakeState.ACTIVE
            }
            StakePosition(addr, lamports, delegation["stake"].str?.toLongOrNull() ?: 0, delegation["voter"].str, act, deact,
                meta["authorized"]["staker"].str ?: "", meta["authorized"]["withdrawer"].str ?: "", meta["rentExemptReserve"].str?.toLongOrNull() ?: 0, state)
        }
    }

    suspend fun recentRewards(n: Network, stakeAccounts: List<String>, epochs: Int = 3): List<StakeReward> {
        if (stakeAccounts.isEmpty()) return emptyList()
        val current = epoch(n).epoch
        val out = ArrayList<StakeReward>()
        for (e in (current - epochs) until current) {
            if (e < 0) continue
            val r = runCatching { rpc.inflationReward(n, stakeAccounts, e) }.getOrNull() ?: continue
            r.forEachIndexed { i, x -> if (x != null) out += StakeReward(stakeAccounts[i], x["epoch"].long ?: e, x["amount"].long ?: 0, x["postBalance"].long ?: 0) }
        }
        return out
    }

    suspend fun rentReserve(n: Network): Long = rpc.rentExemptMinimum(n, StakeInstructions.STAKE_ACCOUNT_SPACE)

    /**
     * Creates a new stake account (address derived with seed from the wallet, so no extra keypair is needed), initialises it
     * with the wallet as both staker and withdrawer, and delegates it. Both authorities remain with the user.
     */
    suspend fun prepareDelegate(account: AccountView, votePubkey: String, stakeLamports: Long, level: FeeLevel): Pair<PreparedSolanaMessage, String> {
        val n = account.network
        val owner = PublicKey.of(account.primaryAddress)
        val vote = PublicKey.of(votePubkey)
        val validators = validators(n)
        val v = validators.firstOrNull { it.votePubkey == votePubkey } ?: throw WalletError.FeatureUnavailable("Validator not found in the current vote account set")
        if (v.delinquent) throw WalletError.FeatureUnavailable("Validator is delinquent (not voting)")
        val reserve = rentReserve(n)
        val balance = rpc.getBalance(n, owner.base58)
        var seedIndex = 0
        var stakeAddr: PublicKey
        while (true) {
            stakeAddr = PublicKey.createWithSeed(owner, "stake:$seedIndex", SolanaPrograms.STAKE)
            if (rpc.accountInfo(n, stakeAddr.base58) == null) break
            seedIndex++
            if (seedIndex > 256) throw WalletError.Unexpected("No free stake account seed")
        }
        val total = stakeLamports + reserve
        val ixs = listOf(
            SystemInstructions.createAccountWithSeed(owner, stakeAddr, owner, "stake:$seedIndex", total, StakeInstructions.STAKE_ACCOUNT_SPACE, SolanaPrograms.STAKE),
            StakeInstructions.initialize(stakeAddr, owner, owner),
            StakeInstructions.delegate(stakeAddr, vote, owner),
        )
        if (total > balance) throw WalletError.InsufficientBalance(Amount.of(total, 9), Amount.of(balance, 9))
        val prepared = adapter.prepareCustom(n, owner, ixs, level, listOf(owner.base58, stakeAddr.base58))
        if (total + prepared.fee.units.toLong() > balance) throw WalletError.InsufficientBalance(Amount.of(total + prepared.fee.units.toLong(), 9), Amount.of(balance, 9))
        return prepared to stakeAddr.base58
    }

    suspend fun prepareDeactivate(account: AccountView, position: StakePosition, level: FeeLevel): PreparedSolanaMessage {
        requireAuthority(account, position.staker, "stake")
        if (!position.canDeactivate) throw WalletError.FeatureUnavailable("Stake account is not active")
        val owner = PublicKey.of(account.primaryAddress)
        return adapter.prepareCustom(account.network, owner, listOf(StakeInstructions.deactivate(PublicKey.of(position.stakeAccount), owner)), level, listOf(owner.base58, position.stakeAccount))
    }

    suspend fun prepareWithdraw(account: AccountView, position: StakePosition, level: FeeLevel): PreparedSolanaMessage {
        requireAuthority(account, position.withdrawer, "withdraw")
        if (!position.canWithdraw) throw WalletError.FeatureUnavailable("Stake must be fully deactivated (after the epoch boundary) before withdrawal")
        val owner = PublicKey.of(account.primaryAddress)
        val live = rpc.getBalance(account.network, position.stakeAccount)
        return adapter.prepareCustom(account.network, owner, listOf(StakeInstructions.withdraw(PublicKey.of(position.stakeAccount), owner, owner, live)), level, listOf(owner.base58, position.stakeAccount))
    }

    private fun requireAuthority(account: AccountView, authority: String, what: String) {
        if (authority != account.primaryAddress) throw WalletError.FeatureUnavailable("This wallet is not the $what authority of the stake account")
    }
}
