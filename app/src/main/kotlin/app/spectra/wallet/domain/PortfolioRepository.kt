package app.spectra.wallet.domain

import app.spectra.core.market.ChartRange
import app.spectra.core.model.Amount
import app.spectra.core.model.AssetKind
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.TokenInfo
import app.spectra.core.model.WalletError
import app.spectra.wallet.data.BalanceEntity
import app.spectra.wallet.data.PriceEntity
import app.spectra.wallet.data.TokenEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

data class AssetRow(
    val token: TokenInfo,
    val accountId: String,
    val amount: Amount,
    val pending: Amount?,
    /** Null when no price is available — never zero. */
    val fiatValue: BigDecimal?,
    val unitPrice: BigDecimal?,
    val change24h: Double?,
    val balanceUpdatedAt: Long,
    val priceUpdatedAt: Long?,
    val hidden: Boolean,
)

data class PortfolioState(
    val rows: List<AssetRow>,
    val totalFiat: BigDecimal,
    val unpricedAssets: Int,
    val change24hFiat: BigDecimal?,
    val oldestBalanceUpdate: Long?,
    val priceUpdatedAt: Long?,
    val isTestnet: Boolean,
) {
    val isEmpty get() = rows.isEmpty()
}

data class RefreshResult(val errors: Map<Network, Throwable>, val priceError: Throwable?)

class PortfolioRepository(private val c: AppContainer) {
    companion object {
        const val STALE_AFTER_MS = 5 * 60_000L
        fun priceKey(t: TokenInfo): String? = when {
            t.id.network.isTestnet -> null
            t.coingeckoId != null -> "cg:${t.coingeckoId}"
            t.id.kind == AssetKind.ERC20 -> "ethereum:${t.id.address!!.lowercase()}"
            t.id.kind == AssetKind.SPL_TOKEN || t.id.kind == AssetKind.SPL_TOKEN_2022 -> "solana:${t.id.address}"
            else -> null
        }
    }

    fun observe(accountIds: Set<String>, env: NetworkEnv, currency: String, includeHidden: Boolean = false): Flow<PortfolioState> =
        combine(c.db.portfolio().allBalances(), c.db.portfolio().tokens(), c.db.portfolio().prices(currency)) { balances, tokens, prices ->
            build(balances.filter { it.accountId in accountIds && Network.fromId(it.networkId).env == env }, tokens.associateBy { it.assetKey }, prices.associateBy { it.priceKey }, env, includeHidden)
        }

    private fun build(balances: List<BalanceEntity>, tokens: Map<String, TokenEntity>, prices: Map<String, PriceEntity>, env: NetworkEnv, includeHidden: Boolean): PortfolioState {
        val rows = balances.mapNotNull { b ->
            val t = tokens[b.assetKey] ?: return@mapNotNull null
            if (t.hidden && !includeHidden) return@mapNotNull null
            val info = t.toInfo()
            val amount = Amount(BigInteger(b.units), b.decimals)
            if (!info.id.isNative && amount.isZero && !t.userAdded) return@mapNotNull null
            val p = priceKey(info)?.let { prices[it] }
            val unit = p?.price?.toBigDecimalOrNull()
            AssetRow(info, b.accountId, amount, b.pendingUnits?.let { Amount(BigInteger(it), b.decimals) },
                unit?.let { amount.toDecimal().multiply(it).setScale(2, RoundingMode.HALF_UP) }, unit, p?.change24h, b.updatedAt, p?.updatedAt, t.hidden)
        }.sortedWith(compareByDescending<AssetRow> { it.fiatValue ?: BigDecimal.ZERO }.thenBy { !it.token.id.isNative }.thenBy { it.token.id.network.chain.ordinal })
        val priced = rows.filter { it.fiatValue != null }
        val total = priced.fold(BigDecimal.ZERO) { a, r -> a + r.fiatValue!! }
        val change = priced.filter { it.change24h != null }.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO) { a, r ->
            val pct = r.change24h!!
            val prev = r.fiatValue!!.divide(BigDecimal.valueOf(1 + pct / 100), 8, RoundingMode.HALF_UP)
            a + (r.fiatValue - prev)
        }?.setScale(2, RoundingMode.HALF_UP)
        return PortfolioState(rows, total, rows.count { it.fiatValue == null && it.amount.units.signum() > 0 && !it.token.id.network.isTestnet },
            change, rows.minOfOrNull { it.balanceUpdatedAt }, priced.mapNotNull { it.priceUpdatedAt }.minOrNull(), env == NetworkEnv.TESTNET)
    }

    /** Refreshes balances for [accountId] on every chain of [env], then prices. Failures are reported per network; cached data stays. */
    suspend fun refresh(accountId: String, env: NetworkEnv): RefreshResult {
        val acc = c.wallets.account(accountId) ?: return RefreshResult(emptyMap(), null)
        val errors = HashMap<Network, Throwable>()
        coroutineScope {
            Chain.entries.map { chain ->
                async {
                    val n = Network(chain, env)
                    val view = acc.view(n) ?: return@async
                    try {
                        val known = c.db.portfolio().tokensOf(n.id).filter { it.userAdded || !it.hidden }.map { it.toInfo() }
                        val bal = c.adapter(n).balances(view, known)
                        val now = System.currentTimeMillis()
                        c.db.portfolio().insertTokensIfAbsent(bal.map { it.token.toEntity(hidden = it.token.possibleSpam, userAdded = false) })
                        c.db.portfolio().upsertBalances(bal.map { b ->
                            BalanceEntity(accountId, b.token.id.key, n.id, b.amount.units.toString(), b.amount.decimals, b.pending?.units?.toString(), b.accountAddress, now)
                        })
                        c.db.portfolio().pruneBalances(accountId, n.id, bal.map { it.token.id.key })
                    } catch (e: Throwable) {
                        errors[n] = e
                    }
                }
            }.awaitAll()
        }
        val priceError = if (env == NetworkEnv.MAINNET) runCatching { refreshPrices() }.exceptionOrNull() else null
        return RefreshResult(errors, priceError)
    }

    suspend fun refreshPrices() {
        val currency = c.prefs.current().currency
        val tokens = c.db.portfolio().tokensOf("solana:mainnet") + c.db.portfolio().tokensOf("ethereum:mainnet") + c.db.portfolio().tokensOf("litecoin:mainnet")
        val infos = tokens.filter { !it.hidden || it.userAdded }.map { it.toInfo() } + Chain.entries.map { TokenInfo.native(Network(it, NetworkEnv.MAINNET)) }
        val now = System.currentTimeMillis()
        val out = ArrayList<PriceEntity>()
        val cgIds = infos.mapNotNull { it.coingeckoId }.distinct()
        c.prices.prices(cgIds, currency).forEach { (id, p) -> out += PriceEntity("cg:$id", currency, p.price.toPlainString(), p.change24hPct, now) }
        val erc = infos.filter { it.id.kind == AssetKind.ERC20 && it.coingeckoId == null && !it.possibleSpam }.mapNotNull { it.id.address?.lowercase() }
        if (erc.isNotEmpty()) runCatching { c.prices.tokenPrices("ethereum", erc.take(20), currency) }.getOrNull()?.forEach { (a, p) -> out += PriceEntity("ethereum:$a", currency, p.price.toPlainString(), p.change24hPct, now) }
        val spl = infos.filter { (it.id.kind == AssetKind.SPL_TOKEN || it.id.kind == AssetKind.SPL_TOKEN_2022) && !it.possibleSpam }.mapNotNull { it.id.address }
        if (spl.isNotEmpty()) runCatching { c.prices.tokenPrices("solana", spl.take(20), currency) }.getOrNull()?.forEach { (a, p) ->
            val orig = spl.firstOrNull { it.lowercase() == a } ?: a
            out += PriceEntity("solana:$orig", currency, p.price.toPlainString(), p.change24hPct, now)
        }
        if (out.isEmpty() && cgIds.isNotEmpty()) throw WalletError.ProviderError("CoinGecko", "No prices returned")
        c.db.portfolio().upsertPrices(out)
    }

    suspend fun chart(token: TokenInfo, range: ChartRange): List<Pair<Long, Double>> {
        val id = token.coingeckoId ?: throw WalletError.FeatureUnavailable("Brak danych rynkowych dla tego aktywa")
        return c.prices.chart(id, c.prefs.current().currency, range)
    }

    /** Validates a user-supplied mint/contract on-chain and adds it to the token list. */
    suspend fun addCustomToken(network: Network, address: String): TokenInfo {
        val info = c.adapter(network).tokenInfo(network, address.trim())
        c.db.portfolio().upsertToken(info.toEntity(hidden = false, userAdded = true))
        return info
    }

    suspend fun setHidden(assetKey: String, hidden: Boolean) = c.db.portfolio().setHidden(assetKey, hidden)
}
