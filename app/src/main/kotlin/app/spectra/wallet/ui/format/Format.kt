package app.spectra.wallet.ui.format

import app.spectra.core.model.Amount
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Polish-locale formatting matching the reference ("17,66 zł", "0,1499 SOL"). */
object Fmt {
    private val pl = Locale.forLanguageTag("pl-PL")
    private val symbols = DecimalFormatSymbols(pl).apply { groupingSeparator = ' ' }
    const val MASK = "••••"

    fun currencySymbol(code: String) = when (code) { "PLN" -> "zł"; "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; "CHF" -> "CHF"; "CZK" -> "Kč"; "JPY" -> "¥"; else -> code }
    private fun prefixed(code: String) = code in setOf("USD", "GBP", "JPY")

    fun fiat(v: BigDecimal, currency: String): String {
        val f = DecimalFormat("#,##0.00", symbols)
        val s = f.format(v.abs())
        val sign = if (v.signum() < 0) "−" else ""
        return if (prefixed(currency)) "$sign${currencySymbol(currency)}$s" else "$sign$s ${currencySymbol(currency)}"
    }

    /** Splits into integer and ",dd zł" parts for the hero balance. */
    fun fiatParts(v: BigDecimal, currency: String): Pair<String, String> {
        val full = fiat(v, currency)
        if (prefixed(currency)) return full to ""
        val idx = full.lastIndexOf(',')
        return if (idx < 0) full to "" else full.substring(0, idx) to full.substring(idx)
    }

    fun signedFiat(v: BigDecimal, currency: String) = (if (v.signum() > 0) "+" else "") + fiat(v, currency)

    fun percent(p: Double) = (if (p > 0) "+" else if (p < 0) "−" else "") + DecimalFormat("0.00", symbols).format(kotlin.math.abs(p)) + "%"

    /** Token amount with up to [maxDecimals] significant fraction digits, trailing zeros removed. */
    fun amount(a: Amount, maxDecimals: Int = 6): String {
        val d = a.toDecimal()
        val scale = when {
            d.abs() >= BigDecimal(1000) -> 2
            d.abs() >= BigDecimal.ONE -> 4
            else -> maxDecimals.coerceAtMost(a.decimals)
        }
        var v = d.setScale(minOf(scale, a.decimals), RoundingMode.DOWN)
        if (v.signum() == 0 && d.signum() != 0) v = d.stripTrailingZeros()
        val f = DecimalFormat("#,##0.##########", symbols).apply { maximumFractionDigits = a.decimals }
        return f.format(v)
    }

    fun amountWithSymbol(a: Amount, symbol: String) = "${amount(a)} $symbol"

    /** Exact amount, all decimals, for review screens. */
    fun exact(a: Amount): String = a.toDecimal().stripTrailingZeros().toPlainString().replace('.', ',')

    fun shortAddress(a: String, head: Int = 4, tail: Int = 4) = if (a.length <= head + tail + 1) a else "${a.take(head)}…${a.takeLast(tail)}"

    fun relativeTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val s = (now - epochMillis) / 1000
        return when {
            s < 60 -> "przed chwilą"
            s < 3600 -> "${s / 60} min temu"
            s < 86_400 -> "${s / 3600} godz. temu"
            else -> "${s / 86_400} dni temu"
        }
    }

    fun dateTime(epochSec: Long): String = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", pl).format(java.util.Date(epochSec * 1000))
}
