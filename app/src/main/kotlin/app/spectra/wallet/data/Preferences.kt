package app.spectra.wallet.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.spectra.wallet.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class AutoLock(val millis: Long, val label: String) {
    IMMEDIATE(0, "Natychmiast"), S30(30_000, "30 sekund"), M1(60_000, "1 minuta"), M5(300_000, "5 minut"), M15(900_000, "15 minut")
}

data class UserPreferences(
    val currency: String = "PLN",
    val theme: ThemeMode = ThemeMode.LIGHT,
    val hideBalances: Boolean = false,
    val aggregatePortfolio: Boolean = false,
    val autoLock: AutoLock = AutoLock.M1,
    val testnetsEnabled: Boolean = false,
    val viewTestnet: Boolean = false,
    val activeAccountId: String? = null,
    val blockScreenshotsEverywhere: Boolean = false,
    val rootWarningAcknowledged: Boolean = false,
    val coingeckoApiKey: String? = null,
)

private val Context.dataStore by preferencesDataStore("spectra_prefs")

class PreferencesRepository(private val context: Context) {
    private object K {
        val currency = stringPreferencesKey("currency")
        val theme = stringPreferencesKey("theme")
        val hide = booleanPreferencesKey("hide_balances")
        val aggregate = booleanPreferencesKey("aggregate")
        val autoLock = stringPreferencesKey("auto_lock")
        val testnets = booleanPreferencesKey("testnets")
        val viewTestnet = booleanPreferencesKey("view_testnet")
        val active = stringPreferencesKey("active_account")
        val blockAll = booleanPreferencesKey("block_screens")
        val rootAck = booleanPreferencesKey("root_ack")
        val cgKey = stringPreferencesKey("coingecko_key")
        val lastPriceAt = longPreferencesKey("last_price_at")
    }

    val flow: Flow<UserPreferences> = context.dataStore.data.map { p -> p.toModel() }

    private fun Preferences.toModel() = UserPreferences(
        currency = this[K.currency] ?: "PLN",
        theme = this[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.LIGHT,
        hideBalances = this[K.hide] ?: false,
        aggregatePortfolio = this[K.aggregate] ?: false,
        autoLock = this[K.autoLock]?.let { runCatching { AutoLock.valueOf(it) }.getOrNull() } ?: AutoLock.M1,
        testnetsEnabled = this[K.testnets] ?: false,
        viewTestnet = (this[K.viewTestnet] ?: false) && (this[K.testnets] ?: false),
        activeAccountId = this[K.active],
        blockScreenshotsEverywhere = this[K.blockAll] ?: false,
        rootWarningAcknowledged = this[K.rootAck] ?: false,
        coingeckoApiKey = this[K.cgKey],
    )

    suspend fun current(): UserPreferences = flow.first()

    suspend fun setCurrency(v: String) = context.dataStore.edit { it[K.currency] = v }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setHideBalances(v: Boolean) = context.dataStore.edit { it[K.hide] = v }
    suspend fun setAggregate(v: Boolean) = context.dataStore.edit { it[K.aggregate] = v }
    suspend fun setAutoLock(v: AutoLock) = context.dataStore.edit { it[K.autoLock] = v.name }
    suspend fun setTestnetsEnabled(v: Boolean) = context.dataStore.edit { it[K.testnets] = v; if (!v) it[K.viewTestnet] = false }
    suspend fun setViewTestnet(v: Boolean) = context.dataStore.edit { it[K.viewTestnet] = v }
    suspend fun setActiveAccount(id: String?) = context.dataStore.edit { if (id == null) it.remove(K.active) else it[K.active] = id }
    suspend fun setBlockScreenshots(v: Boolean) = context.dataStore.edit { it[K.blockAll] = v }
    suspend fun setRootAck(v: Boolean) = context.dataStore.edit { it[K.rootAck] = v }
    suspend fun setCoingeckoKey(v: String?) = context.dataStore.edit { if (v.isNullOrBlank()) it.remove(K.cgKey) else it[K.cgKey] = v.trim() }
    suspend fun clear() = context.dataStore.edit { it.clear() }
}
