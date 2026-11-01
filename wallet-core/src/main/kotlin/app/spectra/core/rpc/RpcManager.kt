package app.spectra.core.rpc

import app.spectra.core.model.Network
import app.spectra.core.model.WalletError
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class EndpointRole { PRIMARY, FALLBACK, CUSTOM }

/**
 * An RPC or REST endpoint for one network. [kind] distinguishes node RPC from indexer/explorer APIs: a custom node
 * endpoint never implies that token indexing or prices also come from it.
 */
@Serializable
data class RpcEndpoint(
    val id: String,
    val network: Network,
    val url: String,
    val role: EndpointRole,
    val kind: EndpointKind = EndpointKind.NODE,
    val enabled: Boolean = true,
)

@Serializable
enum class EndpointKind { NODE, INDEXER }

enum class EndpointState { UNKNOWN, HEALTHY, DEGRADED, RATE_LIMITED, UNREACHABLE, WRONG_CHAIN }

data class EndpointHealth(
    val state: EndpointState = EndpointState.UNKNOWN,
    val latencyMs: Long? = null,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    val cooldownUntil: Long = 0,
    val identityVerified: Boolean = false,
)

/** Verifies the endpoint serves the expected chain (genesis hash / chain id). Throws [WalletError.WrongNetwork]. */
fun interface IdentityProbe { suspend fun verify(endpoint: RpcEndpoint) }

/**
 * Selects endpoints, verifies chain identity once per endpoint, fails over on read errors with exponential backoff,
 * and tracks health. Writes (broadcasts) are sent to exactly one endpoint and never retried automatically.
 */
class RpcManager(
    private val endpointsProvider: suspend (Network, EndpointKind) -> List<RpcEndpoint>,
    private val probes: Map<app.spectra.core.model.Chain, IdentityProbe>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
) {
    private val health = ConcurrentHashMap<String, EndpointHealth>()
    private val identityLock = Mutex()

    fun health(endpointId: String): EndpointHealth = health[endpointId] ?: EndpointHealth()
    fun healthSnapshot(): Map<String, EndpointHealth> = HashMap(health)
    fun resetHealth(endpointId: String) { health.remove(endpointId) }

    private suspend fun ordered(network: Network, kind: EndpointKind): List<RpcEndpoint> {
        val all = endpointsProvider(network, kind).filter { it.enabled }
        // A configured custom endpoint takes precedence; defaults remain as fallback for reads.
        val sorted = all.sortedBy { when (it.role) { EndpointRole.CUSTOM -> 0; EndpointRole.PRIMARY -> 1; EndpointRole.FALLBACK -> 2 } }
        val now = clock()
        val (ready, cooling) = sorted.partition { health(it.id).cooldownUntil <= now && health(it.id).state != EndpointState.WRONG_CHAIN }
        return ready + cooling.filter { health(it.id).state != EndpointState.WRONG_CHAIN }
    }

    suspend fun ensureIdentity(endpoint: RpcEndpoint) {
        if (endpoint.kind != EndpointKind.NODE) return
        if (health(endpoint.id).identityVerified) return
        identityLock.withLock {
            if (health(endpoint.id).identityVerified) return
            val probe = probes[endpoint.network.chain] ?: return
            try {
                probe.verify(endpoint)
                health.compute(endpoint.id) { _, h -> (h ?: EndpointHealth()).copy(identityVerified = true) }
            } catch (e: WalletError.WrongNetwork) {
                health[endpoint.id] = EndpointHealth(EndpointState.WRONG_CHAIN, lastError = e.message)
                throw e
            }
        }
    }

    /** Read with failover across endpoints and bounded exponential backoff. */
    suspend fun <T> read(network: Network, kind: EndpointKind = EndpointKind.NODE, maxAttempts: Int = 4, block: suspend (RpcEndpoint) -> T): T {
        val endpoints = ordered(network, kind)
        if (endpoints.isEmpty()) throw WalletError.RpcUnavailable(network.id, "No endpoint configured")
        var last: Throwable? = null
        var attempt = 0
        while (attempt < maxAttempts) {
            val ep = endpoints[attempt % endpoints.size]
            val start = clock()
            try {
                ensureIdentity(ep)
                val r = block(ep)
                markSuccess(ep, clock() - start)
                return r
            } catch (e: WalletError.WrongNetwork) {
                last = e
            } catch (e: Throwable) {
                if (!isRetryable(e)) { markFailure(ep, e); throw e }
                markFailure(ep, e)
                last = e
                val backoff = when (e) {
                    is WalletError.RateLimited -> (e.retryAfterSeconds ?: 2).coerceAtMost(10) * 1000
                    else -> 250L shl attempt.coerceAtMost(4)
                }
                if (attempt + 1 < maxAttempts) sleeper(backoff)
            }
            attempt++
        }
        throw normalize(last, network)
    }

    /**
     * Single-shot write to the best endpoint. Returns the endpoint used so callers can record it.
     * The caller must treat timeouts as "unknown outcome" and reconcile by transaction id before any retry.
     */
    suspend fun <T> submit(network: Network, block: suspend (RpcEndpoint) -> T): T {
        val ep = ordered(network, EndpointKind.NODE).firstOrNull()
            ?: throw WalletError.RpcUnavailable(network.id, "No endpoint configured")
        ensureIdentity(ep)
        val start = clock()
        return try {
            block(ep).also { markSuccess(ep, clock() - start) }
        } catch (e: Throwable) {
            markFailure(ep, e)
            throw e
        }
    }

    private fun isRetryable(e: Throwable) = when (e) {
        is WalletError.RateLimited, is WalletError.Timeout, is WalletError.RpcUnavailable -> true
        is HttpStatusException -> e.code >= 500 || e.code == 408
        is WalletError.NoInternet -> false
        is JsonRpcException -> e.retryable
        is kotlinx.serialization.SerializationException -> true
        else -> false
    }

    private fun normalize(e: Throwable?, network: Network): Throwable = when (e) {
        null -> WalletError.RpcUnavailable(network.id, "unknown")
        is WalletError -> e
        is HttpStatusException -> WalletError.RpcUnavailable(network.id, "HTTP ${e.code}")
        else -> WalletError.RpcUnavailable(network.id, e.javaClass.simpleName, e)
    }

    private fun markSuccess(ep: RpcEndpoint, latency: Long) {
        health.compute(ep.id) { _, h ->
            (h ?: EndpointHealth()).copy(state = if (latency > 4000) EndpointState.DEGRADED else EndpointState.HEALTHY,
                latencyMs = latency, consecutiveFailures = 0, lastError = null, cooldownUntil = 0)
        }
    }

    private fun markFailure(ep: RpcEndpoint, e: Throwable) {
        health.compute(ep.id) { _, h ->
            val prev = h ?: EndpointHealth()
            val failures = prev.consecutiveFailures + 1
            val state = when (e) {
                is WalletError.RateLimited -> EndpointState.RATE_LIMITED
                is WalletError.WrongNetwork -> EndpointState.WRONG_CHAIN
                else -> if (failures >= 3) EndpointState.UNREACHABLE else EndpointState.DEGRADED
            }
            val cooldown = if (failures >= 3 || e is WalletError.RateLimited) clock() + minOf(60_000L, 5_000L * failures) else 0L
            prev.copy(state = state, consecutiveFailures = failures, lastError = e.message?.take(120), cooldownUntil = cooldown)
        }
    }
}

class JsonRpcException(val code: Int, override val message: String, val data: String? = null) : Exception(message) {
    val retryable: Boolean get() = code == -32005 || code == -32603 && message.contains("timeout", ignoreCase = true)
}

/** In-memory TTL cache for read results. */
class TtlCache<K : Any, V : Any>(private val ttlMs: Long, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Entry<V>(val value: V, val at: Long)
    private val map = ConcurrentHashMap<K, Entry<V>>()
    fun get(key: K): V? = map[key]?.takeIf { clock() - it.at < ttlMs }?.value
    fun put(key: K, value: V) { map[key] = Entry(value, clock()) }
    fun invalidate(key: K) { map.remove(key) }
    fun clear() = map.clear()
    suspend fun getOrLoad(key: K, loader: suspend () -> V): V = get(key) ?: loader().also { put(key, it) }
}
