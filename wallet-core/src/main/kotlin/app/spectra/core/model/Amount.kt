package app.spectra.core.model

import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode

/** Exact integer amount in the asset's smallest unit (lamports, wei, litoshis, token base units). */
data class Amount(val units: BigInteger, val decimals: Int) : Comparable<Amount> {
    init { require(decimals in 0..36) }

    fun toDecimal(): BigDecimal = BigDecimal(units, decimals)
    operator fun plus(o: Amount) = Amount(units + o.sameScale(o).units, decimals)
    operator fun minus(o: Amount) = Amount(units - o.sameScale(o).units, decimals)
    override fun compareTo(other: Amount): Int = units.compareTo(sameScale(other).units)
    val isZero get() = units.signum() == 0
    val isNegative get() = units.signum() < 0

    private fun sameScale(o: Amount): Amount {
        require(o.decimals == decimals) { "Amount decimals mismatch" }
        return o
    }

    companion object {
        fun zero(decimals: Int) = Amount(BigInteger.ZERO, decimals)
        fun of(units: Long, decimals: Int) = Amount(BigInteger.valueOf(units), decimals)

        /** Parses a user-entered decimal string. Accepts ',' or '.' as the decimal separator; rejects excess precision. */
        fun parse(input: String, decimals: Int): Amount? {
            val s = input.trim().replace(',', '.')
            if (s.isEmpty() || !s.matches(Regex("^\\d+(\\.\\d*)?$|^\\.\\d+$"))) return null
            val bd = try { BigDecimal(s) } catch (e: NumberFormatException) { return null }
            if (bd.stripTrailingZeros().scale() > decimals) return null
            return Amount(bd.setScale(decimals, RoundingMode.UNNECESSARY).unscaledValue(), decimals)
        }
    }
}
