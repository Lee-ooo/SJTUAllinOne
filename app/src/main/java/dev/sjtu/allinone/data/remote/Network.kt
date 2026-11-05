package dev.sjtu.allinone.data.remote

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException as OkioIOException

open class RemoteException(message: String) : IOException(message)
class AuthRequiredException : RemoteException("登录已失效，请重新登录")
class RateLimitException : RemoteException("平台请求过于频繁，请稍后重试")
class SourceChangedException(message: String = "平台返回结构已变化，未更新缓存") : RemoteException(message)
class EmptyDataException : RemoteException("平台未返回有效数据，未更新缓存")

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resumeWith(Result.success(response))
        }
    })
    continuation.invokeOnCancellation { cancel() }
}

fun okHttpClient(cookieJar: CookieJar): OkHttpClient = OkHttpClient.Builder()
    .cookieJar(cookieJar)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

class JsonTransport(
    private val client: OkHttpClient,
    private val expectedHost: String,
) {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun get(url: HttpUrl): Pair<JsonElement, Response> = execute(Request.Builder().url(url).get().build())

    suspend fun execute(request: Request): Pair<JsonElement, Response> = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).await()
        } catch (error: SocketTimeoutException) {
            throw RemoteException("连接学校服务超时，请检查网络")
        } catch (error: IOException) {
            if (error is RemoteException) throw error
            throw RemoteException("网络连接失败，已保留本地缓存")
        }
        response.use {
            it.requireHost(expectedHost)
            if (it.code == 401 || it.code == 403) throw AuthRequiredException()
            if (it.code == 429) throw RateLimitException()
            if (!it.isSuccessful) throw RemoteException("平台暂时不可用（HTTP ${it.code}）")
            val contentType = it.header("Content-Type").orEmpty()
            if (!contentType.lowercase().contains("json")) throw AuthRequiredException()
            val body = it.body?.string() ?: throw EmptyDataException()
            val element = try { json.parseToJsonElement(body) } catch (_: Exception) {
                throw SourceChangedException()
            }
            return@withContext element to it.newBuilder().body("".toResponseBodyCompat()).build()
        }
    }

    private fun String.toResponseBodyCompat() = okhttp3.ResponseBody.create("application/json".toMediaTypeOrNull(), this)
}

fun JsonElement.requireObject(): JsonObject = this as? JsonObject ?: throw SourceChangedException()
fun JsonElement.requireArray(): JsonArray = this as? JsonArray ?: throw SourceChangedException()
fun JsonObject.requiredData(): JsonElement = this["data"]?.takeIf { it !is JsonNull }
    ?: throw EmptyDataException()
fun JsonObject.requireBusinessSuccess() {
    val errno = this["errno"] ?: throw SourceChangedException()
    val value = (errno as? JsonPrimitive)?.content?.toIntOrNull() ?: throw SourceChangedException()
    if (value != 0) {
        if (value == 401 || value == 403 || value == 10002) throw AuthRequiredException()
        throw RemoteException("教务接口返回错误码 $value")
    }
}
fun JsonObject.requireIdsSuccess(): JsonElement {
    val code = (this["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: throw SourceChangedException()
    if (code == 401 || code == 403) throw AuthRequiredException()
    if (code != 200) throw RemoteException("教室服务返回错误码 $code")
    return this["data"]?.takeIf { it !is JsonNull } ?: throw EmptyDataException()
}

fun JsonObject.string(vararg names: String): String? = names.firstNotNullOfOrNull { name ->
    (this[name] as? JsonPrimitive)?.content?.takeUnless { it == "null" }
}
fun JsonObject.int(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
    (this[name] as? JsonPrimitive)?.content?.toIntOrNull()
}
fun JsonObject.long(vararg names: String): Long? = names.firstNotNullOfOrNull { name ->
    (this[name] as? JsonPrimitive)?.content?.toLongOrNull()
}
fun JsonObject.boolean(vararg names: String): Boolean = names.firstNotNullOfOrNull { name ->
    (this[name] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
} ?: false

fun HttpUrl.isTrustedCanvasNext(): Boolean = scheme == "https" && host == "oc.sjtu.edu.cn" &&
    (port == 443) && encodedPath.startsWith("/api/v1/")
