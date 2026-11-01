package app.spectra.wallet.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network as AndroidNetwork
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.wallet.data.UserPreferences
import app.spectra.wallet.domain.AccountSummary
import app.spectra.wallet.domain.AppContainer
import app.spectra.wallet.domain.PortfolioState
import app.spectra.wallet.ui.common.humanMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Session(val prefs: UserPreferences, val accounts: List<AccountSummary>, val active: AccountSummary?) {
    val env: NetworkEnv get() = if (prefs.viewTestnet) NetworkEnv.TESTNET else NetworkEnv.MAINNET
    val scopeIds: Set<String> get() = if (prefs.aggregatePortfolio) accounts.map { it.account.id }.toSet() else setOfNotNull(active?.account?.id)
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(private val c: AppContainer, context: Context) : ViewModel() {
    val session: StateFlow<Session?> = combine(c.prefs.flow, c.wallets.accounts) { p, accs ->
        Session(p, accs, accs.firstOrNull { it.account.id == p.activeAccountId } ?: accs.firstOrNull())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val portfolio: StateFlow<PortfolioState?> = session.filterNotNull()
        .map { Triple(it.scopeIds, it.env, it.prefs.currency) }.distinctUntilChanged()
        .flatMapLatest { (ids, env, cur) -> c.portfolio.observe(ids, env, cur) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    var refreshing by mutableStateOf(false); private set
    var refreshErrors by mutableStateOf<Map<Network, String>>(emptyMap()); private set
    var priceError by mutableStateOf<String?>(null); private set

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online

    init {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        _online.value = cm.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: AndroidNetwork) { val was = _online.value; _online.value = true; if (!was) refresh() }
            override fun onLost(network: AndroidNetwork) { _online.value = false }
        })
        viewModelScope.launch {
            session.filterNotNull().map { it.scopeIds to it.env }.distinctUntilChanged().collect { refresh() }
        }
    }

    fun refresh() {
        val s = session.value ?: return
        if (refreshing) return
        refreshing = true
        viewModelScope.launch {
            val errors = HashMap<Network, String>()
            var pe: String? = null
            for (id in s.scopeIds) {
                val r = c.portfolio.refresh(id, s.env)
                r.errors.forEach { (n, e) -> errors[n] = e.humanMessage() }
                r.priceError?.let { pe = it.humanMessage() }
                runCatching { c.transactions.syncHistory(id, s.env) }
            }
            refreshErrors = errors
            priceError = pe
            refreshing = false
        }
    }

    fun setActive(id: String) = viewModelScope.launch { c.prefs.setActiveAccount(id) }
    fun toggleHide() = viewModelScope.launch { c.prefs.setHideBalances(!(session.value?.prefs?.hideBalances ?: false)) }
    fun setAggregate(v: Boolean) = viewModelScope.launch { c.prefs.setAggregate(v) }
    fun setViewTestnet(v: Boolean) = viewModelScope.launch { c.prefs.setViewTestnet(v) }
}
