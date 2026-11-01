package app.spectra.core.rpc

import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.WalletError
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RpcManagerTest {
    private val net = Network(Chain.ETHEREUM, NetworkEnv.TESTNET)
    private val primary = RpcEndpoint("p", net, "https://primary", EndpointRole.PRIMARY)
    private val fallback = RpcEndpoint("f", net, "https://fallback", EndpointRole.FALLBACK)
    private val custom = RpcEndpoint("c", net, "https://custom", EndpointRole.CUSTOM)

    private fun manager(eps: List<RpcEndpoint>, probe: IdentityProbe = IdentityProbe { }) =
        RpcManager({ _, _ -> eps }, mapOf(Chain.ETHEREUM to probe), sleeper = { })

    @Test fun readsFailOverToFallback() = runTest {
        val m = manager(listOf(primary, fallback))
        val used = ArrayList<String>()
        val r = m.read(net) { ep -> used += ep.id; if (ep.id == "p") throw WalletError.Timeout("x") else "ok" }
        assertEquals("ok", r)
        assertEquals(listOf("p", "f"), used)
        assertEquals(EndpointState.HEALTHY, m.health("f").state)
        assertEquals(1, m.health("p").consecutiveFailures)
    }

    @Test fun customEndpointHasPriority() = runTest {
        val m = manager(listOf(primary, fallback, custom))
        assertEquals("c", m.read(net) { it.id })
    }

    @Test fun submitIsNeverRetried() = runTest {
        val m = manager(listOf(primary, fallback))
        var calls = 0
        val r = runCatching { m.submit(net) { calls++; throw WalletError.Timeout("send") } }
        assertTrue(r.exceptionOrNull() is WalletError.Timeout)
        assertEquals(1, calls)
    }

    @Test fun wrongChainEndpointIsExcluded() = runTest {
        val m = manager(listOf(primary, fallback), IdentityProbe { ep -> if (ep.id == "p") throw WalletError.WrongNetwork("Sepolia", "chainId 1") })
        assertEquals("f", m.read(net) { it.id })
        assertEquals(EndpointState.WRONG_CHAIN, m.health("p").state)
        assertEquals("f", m.read(net) { it.id })
    }

    @Test fun nonRetryableErrorsPropagate() = runTest {
        val m = manager(listOf(primary, fallback))
        var calls = 0
        val r = runCatching { m.read(net) { calls++; throw WalletError.InvalidAddress("bad") } }
        assertTrue(r.exceptionOrNull() is WalletError.InvalidAddress)
        assertEquals(1, calls)
    }

    @Test fun rateLimitTriggersCooldown() = runTest {
        var now = 1_000L
        val m = RpcManager({ _, _ -> listOf(primary, fallback) }, emptyMap(), clock = { now }, sleeper = { })
        m.read(net) { ep -> if (ep.id == "p") throw WalletError.RateLimited(5) else "ok" }
        assertEquals(EndpointState.RATE_LIMITED, m.health("p").state)
        assertEquals("f", m.read(net) { it.id })
        now += 120_000
        assertEquals("p", m.read(net) { it.id })
    }
}
