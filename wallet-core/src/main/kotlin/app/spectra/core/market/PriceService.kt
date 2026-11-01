package app.spectra.core.market

import app.spectra.core.model.WalletError
import app.spectra.core.rpc.HttpTransport
import app.spectra.core.rpc.arr
import app.spectra.core.rpc.dbl
import app.spectra.core.rpc.get
import app.spectra.core.rpc.obj
import java.math.BigDecimal

data class PricePoint(val price: BigDecimal, val change24hPct: Double?, val fetchedAtMillis: Long)

enum class ChartRange(val days: String) { DAY("1"), WEEK("7"), MONTH("30"), YEAR("365"), ALL("max") }

/**
 * Indicative market prices from CoinGecko's public API. Never used for executable swap amounts.
 * Missing prices are returned as absent (never zero).
 */
class PriceService(
    private val http: HttpTransport,
    private val baseUrl: String = "https://api.coingecko.com/api/v3",
    private val apiKey: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private fun headers() = apiKey()?.takeIf { it.isNotBlank() }?.let { mapOf("x-cg-demo-api-key" to it) } ?: emptyMap()

    suspend fun prices(ids: Collection<String>, currency: String): Map<String, PricePoint> {
        if (ids.isEmpty()) return emptyMap()
        val c = currency.lowercase()
        val j = http.getJson("$baseUrl/simple/price?ids=${ids.joinToString(",")}&vs_currencies=$c&include_24hr_change=true", headers())
        val now = clock()
        return ids.mapNotNull { id ->
            val o = j[id] ?: return@mapNotNull null
            val p = o[c].dbl ?: return@mapNotNull null
            id to PricePoint(BigDecimal.valueOf(p), o["${c}_24h_change"].dbl, now)
        }.toMap()
    }

    /** Contract/mint prices on "ethereum" or "solana" platforms. */
    suspend fun tokenPrices(platform: String, addresses: Collection<String>, currency: String): Map<String, PricePoint> {
        if (addresses.isEmpty()) return emptyMap()
        val c = currency.lowercase()
        val j = http.getJson("$baseUrl/simple/token_price/$platform?contract_addresses=${addresses.joinToString(",")}&vs_currencies=$c&include_24hr_change=true", headers())
        val now = clock()
        return j.obj?.entries?.mapNotNull { (k, o) ->
            val p = o[c].dbl ?: return@mapNotNull null
            k.lowercase() to PricePoint(BigDecimal.valueOf(p), o["${c}_24h_change"].dbl, now)
        }?.toMap() ?: emptyMap()
    }

    suspend fun chart(id: String, currency: String, range: ChartRange): List<Pair<Long, Double>> {
        val j = http.getJson("$baseUrl/coins/$id/market_chart?vs_currency=${currency.lowercase()}&days=${range.days}", headers())
        return j["prices"].arr?.mapNotNull { p -> val a = p.arr ?: return@mapNotNull null; (a[0].dbl?.toLong() ?: return@mapNotNull null) to (a[1].dbl ?: return@mapNotNull null) }
            ?: throw WalletError.ProviderError("CoinGecko", "No chart data")
    }

    companion object {
        val SUPPORTED_CURRENCIES = listOf("PLN", "USD", "EUR", "GBP", "CHF", "CZK", "JPY")
    }
}
