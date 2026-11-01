package app.spectra.wallet.domain

import android.content.Context
import app.spectra.core.chain.ChainAdapter
import app.spectra.core.chain.ethereum.EthereumAdapter
import app.spectra.core.chain.ethereum.EthereumIdentityProbe
import app.spectra.core.chain.litecoin.EsploraIdentityProbe
import app.spectra.core.chain.litecoin.LitecoinAdapter
import app.spectra.core.chain.solana.SolanaAdapter
import app.spectra.core.chain.solana.SolanaIdentityProbe
import app.spectra.core.chain.solana.SolanaRpc
import app.spectra.core.chain.solana.SolanaTokenDirectory
import app.spectra.core.market.PriceService
import app.spectra.core.model.AssetId
import app.spectra.core.model.AssetKind
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.TokenInfo
import app.spectra.core.rpc.HttpTransport
import app.spectra.core.rpc.JsonRpcClient
import app.spectra.core.rpc.RpcManager
import app.spectra.core.rpc.arr
import app.spectra.core.rpc.bool
import app.spectra.core.rpc.get
import app.spectra.core.rpc.long
import app.spectra.core.rpc.str
import app.spectra.core.staking.SolanaStakingService
import app.spectra.core.swap.JupiterProvider
import app.spectra.core.swap.LifiProvider
import app.spectra.wallet.data.PreferencesRepository
import app.spectra.wallet.data.SpectraDatabase
import app.spectra.wallet.security.AuthorizationCoordinator
import app.spectra.wallet.security.SecurityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** Manual dependency graph. One instance per process. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = SpectraDatabase.build(context)
    val prefs = PreferencesRepository(context)
    val security = SecurityManager(context)
    val auth = AuthorizationCoordinator(security)

    @Volatile private var coingeckoKey: String? = null

    val http = HttpTransport()
    private val jsonRpc = JsonRpcClient(http)
    val rpc = RpcManager(
        endpointsProvider = { network, kind -> db.rpc().forNetwork(network.id, kind.name).map { it.toEndpoint() } },
        probes = mapOf(Chain.SOLANA to SolanaIdentityProbe(jsonRpc), Chain.ETHEREUM to EthereumIdentityProbe(jsonRpc), Chain.LITECOIN to EsploraIdentityProbe(http)),
    )

    private val jupiterTokens = SolanaTokenDirectory { network, mints ->
        if (network.isTestnet || mints.isEmpty()) emptyMap() else {
            val j = http.getJson("https://lite-api.jup.ag/tokens/v2/search?query=" + mints.take(100).joinToString(","))
            j.arr?.mapNotNull { t ->
                val id = t["id"].str ?: return@mapNotNull null
                val symbol = TokenInfo.sanitize(t["symbol"].str, 12)
                val name = TokenInfo.sanitize(t["name"].str, 40)
                val verified = t["isVerified"].bool == true || t["tags"].arr?.any { it.str == "verified" } == true
                id to TokenInfo(AssetId(network, AssetKind.SPL_TOKEN, id), symbol, name, t["decimals"].long?.toInt(), null, null,
                    verified = verified, possibleSpam = !verified && TokenInfo.looksLikeSpam(symbol, name))
            }?.toMap() ?: emptyMap()
        }
    }

    val solanaRpc = SolanaRpc(rpc, jsonRpc)
    val solana = SolanaAdapter(solanaRpc, jupiterTokens)
    val ethereum = EthereumAdapter(rpc, jsonRpc, http, { n, from -> db.transactions().maxPendingNonce(n.id, from)?.plus(1) })
    val litecoin = LitecoinAdapter(rpc, http)
    val staking = SolanaStakingService(solanaRpc, solana)
    val prices = PriceService(http, apiKey = { coingeckoKey })
    val jupiter = JupiterProvider(http)
    val lifi = LifiProvider(http, { n, token, owner, spender -> ethereum.allowance(n, token, owner, spender) })

    fun adapter(network: Network): ChainAdapter = adapter(network.chain)
    fun adapter(chain: Chain): ChainAdapter = when (chain) {
        Chain.SOLANA -> solana
        Chain.ETHEREUM -> ethereum
        Chain.LITECOIN -> litecoin
    }

    val wallets = WalletRepository(this)
    val portfolio = PortfolioRepository(this)
    val transactions = TransactionRepository(this)
    val swaps = SwapRepository(this)
    val stakingRepo = StakingRepository(this)

    init {
        appScope.launch { db.rpc().insertIfAbsent(DefaultEndpoints.all) }
        appScope.launch { prefs.flow.collect { coingeckoKey = it.coingeckoApiKey } }
    }
}
