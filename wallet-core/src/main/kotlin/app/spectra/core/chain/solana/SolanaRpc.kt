package app.spectra.core.chain.solana

import app.spectra.core.model.Network
import app.spectra.core.model.WalletError
import app.spectra.core.rpc.IdentityProbe
import app.spectra.core.rpc.JsonRpcClient
import app.spectra.core.rpc.RpcEndpoint
import app.spectra.core.rpc.RpcManager
import app.spectra.core.rpc.arr
import app.spectra.core.rpc.get
import app.spectra.core.rpc.long
import app.spectra.core.rpc.params
import app.spectra.core.rpc.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

class SolanaIdentityProbe(private val client: JsonRpcClient) : IdentityProbe {
    override suspend fun verify(endpoint: RpcEndpoint) {
        val genesis = client.call(endpoint.url, "getGenesisHash").str
        val expected = endpoint.network.genesisHash
        if (genesis != expected) throw WalletError.WrongNetwork(endpoint.network.displayName, genesis ?: "unknown")
    }
}

data class TokenAccount(val address: String, val mint: String, val program: PublicKey, val amount: Long, val decimals: Int, val frozen: Boolean)
data class SimulationResult(val error: String?, val unitsConsumed: Long?, val logs: List<String>)
data class SignatureStatus(val err: String?, val confirmationStatus: String?, val slot: Long?)

/** Typed wrapper over Solana JSON-RPC (https://solana.com/docs/rpc). */
class SolanaRpc(private val rpc: RpcManager, private val client: JsonRpcClient) {
    private suspend fun read(n: Network, method: String, p: JsonElement = params()): JsonElement =
        rpc.read(n) { ep -> client.call(ep.url, method, p) }

    suspend fun getBalance(n: Network, address: String): Long =
        read(n, "getBalance", params(address, mapOf("commitment" to "confirmed")))["value"].long ?: 0L

    suspend fun accountInfo(n: Network, address: String): JsonElement? =
        read(n, "getAccountInfo", params(address, mapOf("encoding" to "jsonParsed", "commitment" to "confirmed")))["value"]
            ?.takeIf { it !is JsonNull }

    suspend fun tokenAccounts(n: Network, owner: String, program: PublicKey, mint: String? = null): List<TokenAccount> {
        val filter = if (mint != null) mapOf("mint" to mint) else mapOf("programId" to program.base58)
        val v = read(n, "getTokenAccountsByOwner", params(owner, filter, mapOf("encoding" to "jsonParsed", "commitment" to "confirmed")))["value"].arr ?: return emptyList()
        return v.mapNotNull { e ->
            val info = e["account"]["data"]["parsed"]["info"] ?: return@mapNotNull null
            val accProgram = e["account"]["owner"].str?.let { runCatching { PublicKey.of(it) }.getOrNull() } ?: program
            TokenAccount(
                address = e["pubkey"].str ?: return@mapNotNull null,
                mint = info["mint"].str ?: return@mapNotNull null,
                program = accProgram,
                amount = info["tokenAmount"]["amount"].str?.toLongOrNull() ?: return@mapNotNull null,
                decimals = info["tokenAmount"]["decimals"].long?.toInt() ?: return@mapNotNull null,
                frozen = info["state"].str == "frozen",
            )
        }
    }

    suspend fun latestBlockhash(n: Network): Pair<String, Long> {
        val v = read(n, "getLatestBlockhash", params(mapOf("commitment" to "confirmed")))["value"]
        return (v["blockhash"].str ?: throw WalletError.Unexpected("No blockhash")) to (v["lastValidBlockHeight"].long ?: 0L)
    }

    suspend fun blockHeight(n: Network): Long = read(n, "getBlockHeight", params(mapOf("commitment" to "confirmed"))).long ?: 0L

    suspend fun rentExemptMinimum(n: Network, size: Long): Long = read(n, "getMinimumBalanceForRentExemption", params(size)).long
        ?: throw WalletError.Unexpected("Rent query failed")

    suspend fun recentPriorityFees(n: Network, accounts: List<String>): List<Long> =
        read(n, "getRecentPrioritizationFees", params(accounts)).arr?.mapNotNull { it["prioritizationFee"].long } ?: emptyList()

    suspend fun simulate(n: Network, wireBase64: String): SimulationResult {
        val v = read(n, "simulateTransaction", params(wireBase64, mapOf("encoding" to "base64", "sigVerify" to false, "replaceRecentBlockhash" to true, "commitment" to "confirmed")))["value"]
        val err = v["err"]?.takeIf { it !is JsonNull }?.toString()
        return SimulationResult(err, v["unitsConsumed"].long, v["logs"].arr?.mapNotNull { it.str } ?: emptyList())
    }

    suspend fun signatureStatus(n: Network, signature: String): SignatureStatus? {
        val v = read(n, "getSignatureStatuses", params(listOf(signature), mapOf("searchTransactionHistory" to true)))["value"].arr?.firstOrNull()
        if (v == null || v is JsonNull) return null
        return SignatureStatus(v["err"]?.takeIf { it !is JsonNull }?.toString(), v["confirmationStatus"].str, v["slot"].long)
    }

    suspend fun signatures(n: Network, address: String, limit: Int): List<JsonElement> =
        read(n, "getSignaturesForAddress", params(address, mapOf("limit" to limit))).arr ?: emptyList()

    suspend fun transaction(n: Network, signature: String): JsonElement? =
        read(n, "getTransaction", params(signature, mapOf("encoding" to "jsonParsed", "maxSupportedTransactionVersion" to 0, "commitment" to "confirmed")))
            .takeIf { it !is JsonNull }

    suspend fun programAccounts(n: Network, program: String, filters: List<Map<String, Any>>): List<JsonElement> =
        read(n, "getProgramAccounts", params(program, mapOf("encoding" to "jsonParsed", "commitment" to "confirmed", "filters" to filters))).arr ?: emptyList()

    suspend fun voteAccounts(n: Network): JsonElement = read(n, "getVoteAccounts", params(mapOf("commitment" to "confirmed")))
    suspend fun epochInfo(n: Network): JsonElement = read(n, "getEpochInfo", params(mapOf("commitment" to "confirmed")))
    suspend fun inflationRate(n: Network): JsonElement = read(n, "getInflationRate")
    suspend fun inflationReward(n: Network, addresses: List<String>, epoch: Long): List<JsonElement?> =
        read(n, "getInflationReward", params(addresses, mapOf("epoch" to epoch, "commitment" to "confirmed"))).arr?.map { it.takeIf { e -> e !is JsonNull } } ?: emptyList()

    /** Single-shot broadcast; see [RpcManager.submit]. */
    suspend fun sendTransaction(n: Network, wireBase64: String): String =
        rpc.submit(n) { ep ->
            client.call(ep.url, "sendTransaction", params(wireBase64, mapOf("encoding" to "base64", "preflightCommitment" to "confirmed"))).str
                ?: throw WalletError.Unexpected("No signature returned")
        }
}
