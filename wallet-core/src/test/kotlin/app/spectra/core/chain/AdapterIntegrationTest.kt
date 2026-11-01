package app.spectra.core.chain

import app.spectra.core.chain.ethereum.EthereumAdapter
import app.spectra.core.chain.ethereum.EthereumIdentityProbe
import app.spectra.core.chain.litecoin.EsploraIdentityProbe
import app.spectra.core.chain.litecoin.LitecoinAdapter
import app.spectra.core.chain.solana.SolanaAdapter
import app.spectra.core.chain.solana.SolanaIdentityProbe
import app.spectra.core.chain.solana.SolanaRpc
import app.spectra.core.model.Chain
import app.spectra.core.model.Network
import app.spectra.core.model.NetworkEnv
import app.spectra.core.model.TxState
import app.spectra.core.model.WalletError
import app.spectra.core.rpc.EndpointRole
import app.spectra.core.rpc.HttpTransport
import app.spectra.core.rpc.JsonRpcClient
import app.spectra.core.rpc.RpcEndpoint
import app.spectra.core.rpc.RpcManager
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adapter behaviour against controlled fixtures (no real network, no real funds). */
class AdapterIntegrationTest {
    private val server = MockWebServer()
    private val http = HttpTransport()
    private val client = JsonRpcClient(http)
    private val methods = mutableListOf<String>()

    @After fun tearDown() = server.shutdown()

    private fun serve(handler: (method: String, body: String, path: String) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val method = Regex("\"method\":\"([^\"]+)\"").find(body)?.groupValues?.get(1) ?: request.path!!
                synchronized(methods) { methods += method }
                return handler(method, body, request.path!!)
            }
        }
        server.start()
    }

    private fun json(result: String) = MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":$result}""")
    private fun err(code: Int, msg: String) = MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":$code,"message":"$msg"}}""")
    private fun manager(n: Network) = RpcManager({ _, _ -> listOf(RpcEndpoint("t", n, server.url("/").toString().trimEnd('/'), EndpointRole.PRIMARY)) },
        mapOf(Chain.SOLANA to SolanaIdentityProbe(client), Chain.ETHEREUM to EthereumIdentityProbe(client), Chain.LITECOIN to EsploraIdentityProbe(http)), sleeper = { })

    private val sol = Network(Chain.SOLANA, NetworkEnv.TESTNET)
    private val eth = Network(Chain.ETHEREUM, NetworkEnv.TESTNET)
    private val ltc = Network(Chain.LITECOIN, NetworkEnv.TESTNET)

    @Test fun solanaExpiredBlockhashIsReportedAsExpired() = runTest {
        serve { m, _, _ -> when (m) {
            "getGenesisHash" -> json("\"EtWTRABZaYq6iMfeYKouRu166VU2xqa1wcaWoxPkrZBG\"")
            "getSignatureStatuses" -> json("""{"context":{"slot":1},"value":[null]}""")
            "getBlockHeight" -> json("1000")
            else -> err(-32601, "no")
        } }
        val a = SolanaAdapter(SolanaRpc(manager(sol), client), null)
        val s = a.status(TrackedTransaction(sol, "sig", "me", lastValidBlockHeight = 900, submittedAtMillis = 0))
        assertEquals(TxState.EXPIRED, s.state)
        val p = a.status(TrackedTransaction(sol, "sig", "me", lastValidBlockHeight = 1500, submittedAtMillis = 0))
        assertEquals(TxState.PENDING, p.state)
    }

    @Test fun solanaWrongGenesisIsRejected() = runTest {
        serve { m, _, _ -> if (m == "getGenesisHash") json("\"5eykt4UsFv8P8NJdTREpY1vzqKqZKvdpKuc147dw2N9d\"") else json("""{"context":{"slot":1},"value":5}""") }
        val a = SolanaAdapter(SolanaRpc(manager(sol), client), null)
        val r = runCatching { a.status(TrackedTransaction(sol, "sig", "me", 1, submittedAtMillis = 0)) }
        assertTrue(r.exceptionOrNull() is WalletError.WrongNetwork)
        assertTrue("no request beyond identity probe", methods.all { it == "getGenesisHash" })
    }

    @Test fun ethereumReplacementDetected() = runTest {
        serve { m, _, _ -> when (m) {
            "eth_chainId" -> json("\"0xaa36a7\"")
            "eth_getTransactionReceipt", "eth_getTransactionByHash" -> json("null")
            "eth_getTransactionCount" -> json("\"0x8\"")
            else -> err(-32601, "no")
        } }
        val a = EthereumAdapter(manager(eth), client, http, { _, _ -> null })
        val s = a.status(TrackedTransaction(eth, "0xabc", "0x3535353535353535353535353535353535353535", nonce = 7, submittedAtMillis = System.currentTimeMillis()))
        assertEquals(TxState.REPLACED, s.state)
    }

    @Test fun ethereumConfirmedVsFinalizedAndReverted() = runTest {
        var status = "0x1"
        serve { m, body, _ -> when (m) {
            "eth_chainId" -> json("\"0xaa36a7\"")
            "eth_getTransactionReceipt" -> json("""{"blockNumber":"0x64","status":"$status","gasUsed":"0x5208","effectiveGasPrice":"0x3b9aca00"}""")
            "eth_blockNumber" -> json("\"0x6e\"")
            "eth_getBlockByNumber" -> json("""{"number":"0x5a"}""")
            else -> err(-32601, "no")
        } }
        val a = EthereumAdapter(manager(eth), client, http, { _, _ -> null })
        val t = TrackedTransaction(eth, "0xabc", "0x35", submittedAtMillis = 0)
        val s = a.status(t)
        assertEquals(TxState.CONFIRMED, s.state)
        assertEquals(11L, s.confirmations)
        assertEquals("21000000000000", s.feePaid!!.units.toString())
        status = "0x0"
        assertEquals(TxState.FAILED, a.status(t).state)
    }

    @Test fun ethereumBroadcastTimeoutIsUnknownNotFailure() = runTest {
        serve { m, _, _ -> when (m) {
            "eth_chainId" -> json("\"0xaa36a7\"")
            "eth_sendRawTransaction" -> MockResponse().setResponseCode(502)
            else -> err(-32601, "no")
        } }
        val a = EthereumAdapter(manager(eth), client, http, { _, _ -> null })
        val out = a.broadcast(SignedTransaction(eth, "0xhash", "0x02", RawEncoding.HEX, nonce = 1))
        assertTrue(out is BroadcastOutcome.Unknown)
        assertEquals(1, methods.count { it == "eth_sendRawTransaction" })
    }

    @Test fun ethereumNonceTooLowIsConflict() = runTest {
        serve { m, _, _ -> when (m) {
            "eth_chainId" -> json("\"0xaa36a7\"")
            "eth_sendRawTransaction" -> err(-32000, "nonce too low")
            else -> err(-32601, "no")
        } }
        val a = EthereumAdapter(manager(eth), client, http, { _, _ -> null })
        val out = a.broadcast(SignedTransaction(eth, "0xhash", "0x02", RawEncoding.HEX, nonce = 1))
        assertTrue(out is BroadcastOutcome.Rejected && out.error is WalletError.NonceConflict)
    }

    @Test fun litecoinSpentInputsAreUtxoConflict() = runTest {
        serve { _, _, path -> when {
            path.endsWith("/block-height/0") -> MockResponse().setBody("4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0")
            path.endsWith("/tx") -> MockResponse().setResponseCode(400).setBody("sendrawtransaction RPC error: {\"code\":-25,\"message\":\"bad-txns-inputs-missingorspent\"}")
            else -> MockResponse().setResponseCode(404)
        } }
        val a = LitecoinAdapter(manager(ltc), http)
        val out = a.broadcast(SignedTransaction(ltc, "aa", "0200", RawEncoding.HEX))
        assertTrue(out is BroadcastOutcome.Rejected && out.error is WalletError.UtxoConflict)
    }

    @Test fun litecoinConfirmationCounting() = runTest {
        serve { _, _, path -> when {
            path.endsWith("/block-height/0") -> MockResponse().setBody("4966625a4b2851d9fdee139e56211a0d88575f59ed816ff5e6a63deb4e3e29a0")
            path.contains("/tx/") && path.endsWith("/status") -> MockResponse().setBody("""{"confirmed":true,"block_height":100}""")
            path.endsWith("/blocks/tip/height") -> MockResponse().setBody("102")
            else -> MockResponse().setResponseCode(404)
        } }
        val a = LitecoinAdapter(manager(ltc), http)
        val s = a.status(TrackedTransaction(ltc, "ab".repeat(32), "x", submittedAtMillis = 0))
        assertEquals(TxState.CONFIRMED, s.state)
        assertEquals(3L, s.confirmations)
    }
}
