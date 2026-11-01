package app.spectra.wallet.domain

import app.spectra.core.chain.AccountView
import app.spectra.core.chain.FeeLevel
import app.spectra.core.chain.solana.PreparedSolanaMessage
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.TxType
import app.spectra.core.model.WalletError
import app.spectra.core.staking.EpochStatus
import app.spectra.core.staking.StakePosition
import app.spectra.core.staking.StakeReward
import app.spectra.core.staking.Validator

data class StakingOverview(val positions: List<StakePosition>, val epoch: EpochStatus, val inflationPct: Double?, val rewards: List<StakeReward>, val liquidLamports: Long)

enum class StakeOp(val type: TxType, val title: String) {
    DELEGATE(TxType.STAKE_DELEGATE, "Potwierdź delegację"), DEACTIVATE(TxType.STAKE_DEACTIVATE, "Potwierdź dezaktywację"), WITHDRAW(TxType.STAKE_WITHDRAW, "Potwierdź wypłatę")
}

data class PreparedStake(val op: StakeOp, val message: PreparedSolanaMessage, val account: AccountView, val lamports: Long, val stakeAccount: String, val validator: String?)

class StakingRepository(private val c: AppContainer) {
    private fun network(env: NetworkEnv) = Network(Chain.SOLANA, env)

    suspend fun view(accountId: String, env: NetworkEnv): AccountView =
        c.wallets.account(accountId)?.view(network(env)) ?: throw WalletError.Unexpected("Account not found")

    suspend fun validators(env: NetworkEnv): List<Validator> = c.staking.validators(network(env))

    suspend fun overview(accountId: String, env: NetworkEnv): StakingOverview {
        val v = view(accountId, env)
        val n = network(env)
        val positions = c.staking.positions(n, v.primaryAddress)
        return StakingOverview(positions, c.staking.epoch(n), runCatching { c.staking.inflationPercent(n) }.getOrNull(),
            runCatching { c.staking.recentRewards(n, positions.map { it.stakeAccount }) }.getOrDefault(emptyList()), c.solanaRpc.getBalance(n, v.primaryAddress))
    }

    suspend fun prepareDelegate(accountId: String, env: NetworkEnv, vote: String, lamports: Long): PreparedStake {
        val v = view(accountId, env)
        val (msg, stakeAddr) = c.staking.prepareDelegate(v, vote, lamports, FeeLevel.NORMAL)
        return PreparedStake(StakeOp.DELEGATE, msg, v, lamports, stakeAddr, vote)
    }

    suspend fun prepareDeactivate(accountId: String, env: NetworkEnv, p: StakePosition) =
        view(accountId, env).let { v -> PreparedStake(StakeOp.DEACTIVATE, c.staking.prepareDeactivate(v, p, FeeLevel.NORMAL), v, p.delegatedLamports, p.stakeAccount, p.voter) }

    suspend fun prepareWithdraw(accountId: String, env: NetworkEnv, p: StakePosition) =
        view(accountId, env).let { v -> PreparedStake(StakeOp.WITHDRAW, c.staking.prepareWithdraw(v, p, FeeLevel.NORMAL), v, p.lamports, p.stakeAccount, p.voter) }

    suspend fun execute(prepared: PreparedStake, vaultId: String, accountId: String): ExecuteResult {
        TransactionRepository.requireSigningAllowed(prepared.account.network)
        if (System.currentTimeMillis() > prepared.message.expiresAtMillis) return ExecuteResult.Failed(null, WalletError.BlockhashExpired())
        val signed = try {
            c.auth.withKeyring(vaultId, prepared.op.title, "Konto stakingowe ${prepared.stakeAccount.take(6)}…") { k ->
                c.solana.signMessage(prepared.account, prepared.message.messageBytes, prepared.message.lastValidBlockHeight, k)
            }
        } catch (e: Throwable) { return ExecuteResult.Failed(null, e) } ?: return ExecuteResult.Cancelled
        return c.transactions.recordAndSubmit(accountId, signed, prepared.op.type, "SOL", prepared.lamports.toString(), 9, prepared.message.fee.units.toString(),
            prepared.validator ?: prepared.stakeAccount, prepared.account.primaryAddress)
    }
}
