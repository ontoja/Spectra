package app.spectra.core.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/** JSON-RPC 2.0 over HTTPS. */
class JsonRpcClient(private val http: HttpTransport) {
    private val ids = AtomicLong(1)

    suspend fun call(url: String, method: String, params: JsonElement = JsonArray(emptyList())): JsonElement {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.getAndIncrement())
            put("method", method)
            put("params", params)
        }
        val resp = http.postJson(url, body.toString()).jsonObject
        resp["error"]?.takeIf { it !is JsonNull }?.let { err ->
            val o = err.jsonObject
            throw JsonRpcException(
                o["code"]?.jsonPrimitive?.intOrNull ?: 0,
                o["message"]?.jsonPrimitive?.contentOrNull ?: "RPC error",
                o["data"]?.toString()?.take(2000),
            )
        }
        return resp["result"] ?: JsonNull
    }
}

fun params(vararg items: Any?): JsonArray = JsonArray(items.map { toJson(it) })

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
    is List<*> -> JsonArray(v.map { toJson(it) })
    else -> JsonPrimitive(v.toString())
}

val JsonElement?.obj: JsonObject? get() = this as? JsonObject
val JsonElement?.arr: JsonArray? get() = this as? JsonArray
val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.contentOrNull
val JsonElement?.long: Long? get() = (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
val JsonElement?.dbl: Double? get() = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)
