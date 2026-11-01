package app.spectra.wallet.domain

import app.spectra.core.chain.FeeLevel
import app.spectra.core.chain.ethereum.CallDescription
import app.spectra.core.chain.ethereum.Erc20Abi
import app.spectra.core.chain.solana.SolanaPrograms
import app.spectra.core.model.Network
import app.spectra.core.model.TxState
import app.spectra.core.model.TxType
import app.spectra.core.model.WalletError
import app.spectra.core.swap.RouteSupport
import app.spectra.core.swap.SwapProvider
import app.spectra.core.swap.SwapProviderId
import app.spectra.core.swap.SwapQuote
import app.spectra.core.swap.SwapRequest
import app.spectra.core.swap.SwapRouter
import app.spectra.core.swap.SwapStatus
import app.spectra.core.swap.SwapTransaction
import app.spectra.wallet.data.SwapEntity
import kotlinx.coroutines.flow.Flow
import java.math.BigInteger
import java.util.UUID

/** Result of inspecting a provider transaction before signing. */
data class SwapInspection(val programsOrContract: List<String>, val warnings: List<String>)

class SwapRepository(private val c: AppContainer) {
    private val dao get() = c.db.transactions()
    val swaps: Flow<List<SwapEntity>> = dao.swaps()

    fun route(from: Network, to: Network): RouteSupport = SwapRouter.route(from, to)

    private fun provider(id: SwapProviderId): SwapProvider = when (id) { SwapProviderId.JUPITER -> c.jupiter; SwapProviderId.LIFI -> c.lifi }

    suspend fun quote(request: SwapRequest): SwapQuote {
        val r = route(request.fromAccount.network, request.toAccount.network)
        if (r is RouteSupport.Unsupported) throw WalletError.RouteUnavailable(r.reason)
        if (request.fromToken.id == request.toToken.id) throw WalletError.RouteUnavailable("Wybierz dwa różne aktywa")
        return provider((r as RouteSupport.Supported).provider).quote(request)
    }

    /** Exact-amount ERC-20 approval (never unlimited). Requires its own authorisation. */
    suspend fun approve(quote: SwapQuote, vaultId: String, accountId: String): ExecuteResult {
        val req = quote.approval ?: return ExecuteResult.Failed(null, WalletError.Unexpected("No approval required"))
        val acct = quote.request.fromAccount
        TransactionRepository.requireSigningAllowed(acct.network)
        val tx = c.ethereum.prepareCall(acct, req.tokenContract, BigInteger.ZERO, Erc20Abi.approve(req.spender, req.required.units), FeeLevel.NORMAL, null)
        val signed = c.auth.withKeyring(vaultId, "Zatwierdź limit tokena", "Pozwolenie dla ${req.spender.take(10)}… na ${quote.request.fromToken.symbol} (dokładna kwota)") { k ->
            c.ethereum.signTx(acct, tx, k)
        } ?: return ExecuteResult.Cancelled
        return c.transactions.recordAndSubmit(accountId, signed, TxType.APPROVAL, quote.request.fromToken.symbol, req.required.units.toString(), req.required.decimals,
            tx.maxFee.toString(), req.spender, acct.primaryAddress)
    }

    /** Inspects the provider transaction: wallet must be fee payer/sender; unknown programs or call effects are surfaced. */
    fun inspect(quote: SwapQuote, tx: SwapTransaction): SwapInspection = when (tx) {
        is SwapTransaction.Solana -> {
            val parsed = app.spectra.core.chain.solana.ParsedSolanaTransaction.parse(java.util.Base64.getDecoder().decode(tx.wireBase64))
            if (parsed.staticKeys.firstOrNull()?.base58 != quote.request.fromAccount.primaryAddress) throw WalletError.ProviderError(quote.provider.displayName, "Wallet is not the fee payer")
            val known = setOf(SolanaPrograms.SYSTEM, SolanaPrograms.TOKEN, SolanaPrograms.TOKEN_2022, SolanaPrograms.ASSOCIATED_TOKEN, SolanaPrograms.COMPUTE_BUDGET).map { it.base58 }.toSet() + app.spectra.core.swap.JupiterProvider.PROGRAM_ID
            val programs = parsed.programIds.map { it.base58 }
            SwapInspection(programs, buildList {
                programs.filter { it !in known }.takeIf { it.isNotEmpty() }?.let { add("Transakcja wywołuje programy, których skutków Spectra nie potrafi zweryfikować: ${it.joinToString { p -> p.take(6) + "…" }}") }
                if (parsed.usesLookupTables) add("Transakcja używa tabel adresów (ALT); nie wszystkie konta są widoczne przed podpisem.")
            })
        }
        is SwapTransaction.Evm -> {
            val d = Erc20Abi.describe(tx.data)
            SwapInspection(listOf(tx.to), buildList {
                add("Wywołanie kontraktu ${tx.to.take(10)}… — Spectra nie może w pełni zweryfikować jego skutków. Sprawdź minimalną kwotę.")
                if (d is CallDescription.TokenApproval) add("Uwaga: transakcja wymiany zawiera zgodę na wydawanie tokenów.")
            })
        }
    }

    suspend fun buildForReview(quote: SwapQuote): Pair<SwapTransaction, SwapInspection> {
        if (System.currentTimeMillis() > quote.expiresAtMillis) throw WalletError.QuoteExpired()
        val tx = provider(quote.provider).buildTransaction(quote)
        return tx to inspect(quote, tx)
    }

    /** Signs and submits a reviewed swap once. The swap record is persisted before broadcast; it is never re-executed automatically. */
    suspend fun execute(quote: SwapQuote, tx: SwapTransaction, vaultId: String, accountId: String): ExecuteResult {
        val req = quote.request
        TransactionRepository.requireSigningAllowed(req.fromAccount.network)
        if (System.currentTimeMillis() > quote.expiresAtMillis) return ExecuteResult.Failed(null, WalletError.QuoteExpired())
        if (quote.approval != null) return ExecuteResult.Failed(null, WalletError.FeatureUnavailable("Najpierw zatwierdź limit tokena"))
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        var swap = SwapEntity(id, accountId, quote.provider.name, quote.tool, req.fromAccount.network.id, req.toAccount.network.id, req.fromToken.id.key, req.toToken.id.key,
            req.fromToken.symbol, req.toToken.symbol, req.amount.units.toString(), req.amount.decimals, quote.estimatedOut.units.toString(), quote.minimumOut.units.toString(),
            quote.minimumOut.decimals, req.fromAccount.primaryAddress, req.toAccount.primaryAddress, SwapStatus.AWAITING_AUTHORIZATION.name, null, null, null, null, now, now)
        dao.upsertSwap(swap)
        val title = "Potwierdź wymianę"
        val detail = "${req.fromToken.symbol} → ${req.toToken.symbol} przez ${quote.provider.displayName}"
        val signed = try {
            c.auth.withKeyring(vaultId, title, detail) { k ->
                when (tx) {
                    is SwapTransaction.Solana -> c.solana.signExternal(req.fromAccount, tx.wireBase64, tx.lastValidBlockHeight, k).second
                    is SwapTransaction.Evm -> {
                        if (tx.chainId != req.fromAccount.network.evmChainId) throw WalletError.WrongNetwork(req.fromAccount.network.displayName, "chainId ${tx.chainId}")
                        val call = c.ethereum.prepareCall(req.fromAccount, tx.to, tx.value, tx.data, FeeLevel.NORMAL, tx.gasLimit)
                        c.ethereum.signTx(req.fromAccount, call, k)
                    }
                }
            }
        } catch (e: Throwable) {
            dao.upsertSwap(swap.copy(status = SwapStatus.FAILED.name, message = e.message?.take(200), updatedAt = System.currentTimeMillis()))
            return ExecuteResult.Failed(null, e)
        }
        if (signed == null) { dao.upsertSwap(swap.copy(status = SwapStatus.QUOTED.name, message = "Anulowano", updatedAt = System.currentTimeMillis())); return ExecuteResult.Cancelled }
        swap = swap.copy(status = SwapStatus.SUBMITTED.name, sourceTxId = signed.txId, updatedAt = System.currentTimeMillis())
        dao.upsertSwap(swap)
        val result = c.transactions.recordAndSubmit(accountId, signed, TxType.SWAP, req.fromToken.symbol, req.amount.units.toString(), req.amount.decimals, null,
            req.toAccount.primaryAddress, req.fromAccount.primaryAddress, swapId = id)
        if (result is ExecuteResult.Failed) dao.upsertSwap(swap.copy(status = SwapStatus.FAILED.name, message = result.error.message?.take(200), updatedAt = System.currentTimeMillis()))
        return result
    }

    /** Reconciles active swaps from source-transaction state and provider status. */
    suspend fun monitorOnce() {
        for (s in dao.activeSwaps()) {
            val source = s.sourceTxId?.let { txId -> c.db.transactions().find(s.accountId, s.fromNetworkId, txId) } ?: continue
            val srcState = TxState.valueOf(source.state)
            val next: Pair<SwapStatus, String?> = when {
                srcState == TxState.FAILED -> SwapStatus.FAILED to (source.error ?: "Transakcja źródłowa nie powiodła się")
                srcState == TxState.EXPIRED || srcState == TxState.DROPPED || srcState == TxState.REPLACED -> SwapStatus.EXPIRED to "Transakcja źródłowa nie została włączona do łańcucha"
                srcState != TxState.CONFIRMED && srcState != TxState.FINALIZED -> SwapStatus.SUBMITTED to null
                s.provider == SwapProviderId.JUPITER.name -> SwapStatus.COMPLETED to "Wymiana atomowa potwierdzona w łańcuchu"
                else -> {
                    val req = SwapRequest(
                        app.spectra.core.chain.AccountView(Network.fromId(s.fromNetworkId), app.spectra.core.model.DerivationScheme.defaultFor(Network.fromId(s.fromNetworkId).chain), 0, s.fromAddress),
                        app.spectra.core.chain.AccountView(Network.fromId(s.toNetworkId), app.spectra.core.model.DerivationScheme.defaultFor(Network.fromId(s.toNetworkId).chain), 0, s.toAddress),
                        app.spectra.core.model.TokenInfo.native(Network.fromId(s.fromNetworkId)), app.spectra.core.model.TokenInfo.native(Network.fromId(s.toNetworkId)),
                        app.spectra.core.model.Amount(BigInteger(s.amountUnits), s.fromDecimals), 0)
                    runCatching { c.lifi.status(req, s.sourceTxId!!, s.tool) }.fold(
                        { o -> (if (o.status == SwapStatus.SUBMITTED) SwapStatus.SOURCE_CONFIRMED else o.status) to (o.message ?: o.destinationTxId?.let { "Tx docelowa: $it" }) },
                        { SwapStatus.UNKNOWN to "Status dostawcy chwilowo niedostępny" })
                }
            }
            if (next.first.name != s.status || (next.second != null && next.second != s.message)) {
                dao.upsertSwap(s.copy(status = next.first.name, message = next.second ?: s.message, updatedAt = System.currentTimeMillis()))
            }
        }
    }
}
