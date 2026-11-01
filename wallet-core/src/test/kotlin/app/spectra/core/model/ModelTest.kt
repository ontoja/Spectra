package app.spectra.core.model

import app.spectra.core.swap.RouteSupport
import app.spectra.core.swap.SwapProviderId
import app.spectra.core.swap.SwapRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class ModelTest {
    @Test fun amountParsing() {
        assertEquals(BigInteger.valueOf(75_000_000), Amount.parse("0,075", 9)!!.units)
        assertEquals(BigInteger.valueOf(1), Amount.parse("0.00000001", 8)!!.units)
        assertNull(Amount.parse("0.000000001", 8))
        assertNull(Amount.parse("1e5", 8))
        assertNull(Amount.parse("-1", 8))
        assertNull(Amount.parse("", 8))
    }

    @Test fun stateMachine() {
        assertTrue(TxStateMachine.canTransition(TxState.BROADCASTING, TxState.UNKNOWN))
        assertTrue(TxStateMachine.canTransition(TxState.CONFIRMED, TxState.PENDING)) // reorg
        assertFalse(TxStateMachine.canTransition(TxState.SIGNED, TxState.CONFIRMED))
        assertFalse(TxStateMachine.canTransition(TxState.FINALIZED, TxState.PENDING))
        assertTrue(TxState.FINALIZED.isTerminal && !TxState.UNKNOWN.isTerminal)
    }

    @Test fun swapRouting() {
        val solMain = Network(Chain.SOLANA, NetworkEnv.MAINNET)
        val ethMain = Network(Chain.ETHEREUM, NetworkEnv.MAINNET)
        val ltcMain = Network(Chain.LITECOIN, NetworkEnv.MAINNET)
        assertEquals(RouteSupport.Supported(SwapProviderId.JUPITER), SwapRouter.route(solMain, solMain))
        assertEquals(RouteSupport.Supported(SwapProviderId.LIFI), SwapRouter.route(ethMain, solMain))
        assertTrue(SwapRouter.route(ltcMain, ethMain) is RouteSupport.Unsupported)
        assertTrue(SwapRouter.route(Network(Chain.SOLANA, NetworkEnv.TESTNET), solMain) is RouteSupport.Unsupported)
    }

    @Test fun untrustedMetadataIsSanitized() {
        assertEquals("USDC", TokenInfo.sanitize("US‮DC\u0000", 12))
        assertEquals("?", TokenInfo.sanitize("​", 12))
        assertTrue(TokenInfo.looksLikeSpam("FREE", "Visit claim-reward.xyz"))
        assertFalse(TokenInfo.looksLikeSpam("USDC", "USD Coin"))
    }

    @Test fun networkIdentities() {
        assertEquals(11155111L, Network(Chain.ETHEREUM, NetworkEnv.TESTNET).evmChainId)
        assertEquals(Network(Chain.LITECOIN, NetworkEnv.TESTNET), Network.fromId("litecoin:testnet"))
        assertEquals("ethereum:mainnet:ERC20:0xabc", AssetId(Network(Chain.ETHEREUM, NetworkEnv.MAINNET), AssetKind.ERC20, "0xabc").key)
    }
}
