package app.spectra.wallet.ui.components

import app.spectra.core.model.AssetId
import app.spectra.core.model.AssetKind
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.TokenInfo
import app.spectra.core.swap.PopularTokens
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenLogoTest {
    @Test fun everyPopularTokenHasABundledLogo() {
        (PopularTokens.ethereum + PopularTokens.solana).forEach { assertTrue("${it.symbol} on ${it.id.network.id}", hasBundledLogo(it)) }
    }

    @Test fun logoNeverAttachesBySymbolAlone() {
        val fakeUsdc = TokenInfo(AssetId(Network(Chain.SOLANA, NetworkEnv.MAINNET), AssetKind.SPL_TOKEN, "So11111111111111111111111111111111111111112"), "USDC", "USD Coin", 6)
        assertFalse(hasBundledLogo(fakeUsdc))
        val real = PopularTokens.ethereum.first { it.symbol == "DAI" }
        assertFalse("testnet copy must not get the logo", hasBundledLogo(real.copy(id = real.id.copy(network = Network(Chain.ETHEREUM, NetworkEnv.TESTNET)))))
        assertTrue("EVM match is case-insensitive", hasBundledLogo(real.copy(id = real.id.copy(address = real.id.address!!.lowercase()))))
    }
}
