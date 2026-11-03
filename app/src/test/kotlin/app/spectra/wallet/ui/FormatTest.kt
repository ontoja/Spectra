package app.spectra.wallet.ui

import app.spectra.core.model.Amount
import app.spectra.wallet.ui.format.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class FormatTest {
    @Test fun polishFiatFormatting() {
        assertEquals("17,66 zł", Fmt.fiat(BigDecimal("17.66"), "PLN"))
        assertEquals("1 234,50 zł", Fmt.fiat(BigDecimal("1234.5"), "PLN"))
        assertEquals("$3,10".replace(",", ","), Fmt.fiat(BigDecimal("3.1"), "USD"))
        assertEquals("17" to ",66 zł", Fmt.fiatParts(BigDecimal("17.66"), "PLN"))
        assertEquals("−0,26 zł", Fmt.signedFiat(BigDecimal("-0.26"), "PLN"))
    }

    @Test fun tokenAmounts() {
        assertEquals("0,1499", Fmt.amount(Amount.of(149_900_000, 9)))
        assertEquals("0,0002", Fmt.amount(Amount.of(200_000_000_000_000, 18)))
        assertEquals("0,075", Fmt.exact(Amount.of(75_000_000, 9)))
        assertEquals("7xKp…3fQa", Fmt.shortAddress("7xKpAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA3fQa"))
        assertEquals("−1,45%", Fmt.percent(-1.45))
    }

    @org.junit.Test fun dayHeaders() {
        val zone = java.time.ZoneId.of("Europe/Warsaw")
        val now = java.time.ZonedDateTime.of(2026, 11, 3, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
        fun at(y: Int, m: Int, d: Int, h: Int = 12) = java.time.ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toEpochSecond()
        org.junit.Assert.assertEquals("Dzisiaj", app.spectra.wallet.ui.format.Fmt.dayHeader(at(2026, 11, 3, 0), now, zone))
        org.junit.Assert.assertEquals("Wczoraj", app.spectra.wallet.ui.format.Fmt.dayHeader(at(2026, 11, 2, 23), now, zone))
        org.junit.Assert.assertEquals("28 października", app.spectra.wallet.ui.format.Fmt.dayHeader(at(2026, 10, 28), now, zone))
        org.junit.Assert.assertEquals("5 grudnia 2025", app.spectra.wallet.ui.format.Fmt.dayHeader(at(2025, 12, 5), now, zone))
    }
}
