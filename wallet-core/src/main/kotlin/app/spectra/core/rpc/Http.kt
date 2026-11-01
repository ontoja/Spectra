package app.spectra.core.rpc

import app.spectra.core.model.WalletError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

val SpectraJson = Json { ignoreUnknownKeys = true; isLenient = false; explicitNulls = false }

/** HTTP transport with strict timeouts and normalised errors. No cookies, no analytics headers. */
class HttpTransport(
    client: OkHttpClient? = null,
    private val userAgent: String = "SpectraWallet/0.1",
) {
    val client: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String = execute(url, Request.Builder().url(url).get(), headers)

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement = SpectraJson.parseToJsonElement(getText(url, headers))

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): JsonElement =
        SpectraJson.parseToJsonElement(execute(url, Request.Builder().url(url).post(body.toRequestBody(JSON)), headers))

    suspend fun postText(url: String, body: String, contentType: String = "text/plain"): String =
        execute(url, Request.Builder().url(url).post(body.toRequestBody(contentType.toMediaType())), emptyMap())

    private suspend fun execute(url: String, builder: Request.Builder, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        require(url.startsWith("https://") || url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")) {
            "Only HTTPS endpoints are allowed"
        }
        builder.header("User-Agent", userAgent).header("Accept", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        try {
            client.newCall(builder.build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.code == 429 -> throw WalletError.RateLimited(resp.header("Retry-After")?.toLongOrNull())
                    resp.code in 500..599 -> throw HttpStatusException(resp.code, text.take(300))
                    !resp.isSuccessful -> throw HttpStatusException(resp.code, text.take(300))
                    else -> text
                }
            }
        } catch (e: WalletError) {
            throw e
        } catch (e: HttpStatusException) {
            throw e
        } catch (e: UnknownHostException) {
            throw WalletError.NoInternet(e)
        } catch (e: InterruptedIOException) {
            throw WalletError.Timeout(hostOf(url))
        } catch (e: IOException) {
            throw WalletError.RpcUnavailable(hostOf(url), e.javaClass.simpleName, e)
        }
    }

    companion object {
        val JSON = "application/json".toMediaType()
        fun hostOf(url: String) = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)
    }
}

class HttpStatusException(val code: Int, val body: String) : IOException("HTTP $code")
