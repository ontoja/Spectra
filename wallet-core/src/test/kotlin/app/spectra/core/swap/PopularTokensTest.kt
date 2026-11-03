package app.spectra.core.swap

import app.spectra.core.chain.AddressCheck
import app.spectra.core.chain.EthereumAddress
import app.spectra.core.chain.SolanaAddress
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopularTokensTest {
    @Test fun ethereumAddressesCarryValidEip55Checksums() {
        PopularTokens.ethereum.forEach { t ->
            val a = t.id.address!!
            assertEquals("${t.symbol} checksum", AddressCheck.Valid(a), EthereumAddress.validate(a))
        }
    }

    @Test fun solanaMintsAreValid32BytePublicKeys() {
        PopularTokens.solana.forEach { t -> assertEquals("${t.symbol} mint", 32, SolanaAddress.decode(t.id.address!!)?.size) }
    }

    @Test fun noDuplicatesAndMainnetOnly() {
        listOf(PopularTokens.solana, PopularTokens.ethereum).forEach { list ->
            assertEquals(list.size, list.map { it.id.key }.toSet().size)
            assertEquals(list.size, list.map { it.symbol }.toSet().size)
            assertTrue(list.all { !it.id.network.isTestnet && it.verified && it.decimals != null })
        }
        Chain.entries.forEach { assertTrue(PopularTokens.forNetwork(Network(it, NetworkEnv.TESTNET)).isEmpty()) }
        assertTrue(PopularTokens.forNetwork(Network(Chain.LITECOIN, NetworkEnv.MAINNET)).isEmpty())
    }
}
