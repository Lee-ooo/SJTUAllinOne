package dev.sjtu.allinone.data.remote

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Response

@Serializable
private data class StoredCookie(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expiresAt: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
    val hostOnly: Boolean,
    val persistent: Boolean,
)

/** Cookies are encrypted at rest and never shared between the two authenticated clients. */
private class SessionStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("encrypted_sessions", Context.MODE_PRIVATE)
    private val json = Json
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("session-store", null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).run {
            init(KeyGenParameterSpec.Builder(
                "session-store",
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }

    @Synchronized fun read(keyName: String): List<Cookie> {
        val encoded = prefs.getString(keyName, null) ?: return emptyList()
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12))
            val plain = cipher.doFinal(bytes, 12, bytes.size - 12).toString(Charsets.UTF_8)
            json.decodeFromString<List<StoredCookie>>(plain).mapNotNull { item ->
                runCatching {
                    Cookie.Builder().name(item.name).value(item.value)
                        .let { if (item.hostOnly) it.hostOnlyDomain(item.domain) else it.domain(item.domain) }
                        .path(item.path).let { if (item.secure) it.secure() else it }.let {
                            if (item.httpOnly) it.httpOnly() else it
                        }.let {
                            if (item.persistent) it.expiresAt(item.expiresAt) else it
                        }.build()
                }.getOrNull()
            }
        }.getOrElse {
            prefs.edit().remove(keyName).apply()
            emptyList()
        }
    }

    @Synchronized fun write(keyName: String, cookies: List<Cookie>) {
        val items = cookies.map {
            StoredCookie(it.name, it.value, it.domain, it.path, it.expiresAt, it.secure, it.httpOnly, it.hostOnly, it.persistent)
        }
        runCatching {
            val plain = json.encodeToString(items).toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val bytes = cipher.iv + cipher.doFinal(plain)
            check(prefs.edit().putString(keyName, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit())
        }.getOrElse { throw RemoteException("无法安全保存登录会话，请重试") }
    }

    @Synchronized fun clear(prefix: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        check(editor.commit()) { "无法清除登录会话，请重试" }
    }
}

class SessionCookieJar(
    context: Context,
    private val host: String,
    private val source: String,
    private val accountId: () -> String,
) : CookieJar {
    private val store = SessionStore(context)
    private val baseUrl = HttpUrl.Builder().scheme("https").host(host).build()

    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.host != host || url.scheme != "https") return
        val merged = store.read(storageKey()).filterNot { old -> cookies.any { it.name == old.name && it.path == old.path && it.domain == old.domain } } + cookies
        store.write(storageKey(), merged.filter { it.persistent || it.expiresAt > System.currentTimeMillis() })
    }

    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (url.host != host || url.scheme != "https") return emptyList()
        val valid = store.read(storageKey()).filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        return valid
    }

    @Synchronized fun importWebView(cookieHeader: String?) {
        if (cookieHeader.isNullOrBlank()) return
        val imported = cookieHeader.split(';').mapNotNull { part ->
            runCatching { Cookie.parse(baseUrl, part.trim()) }.getOrNull()
        }
        saveFromResponse(baseUrl, imported)
    }

    @Synchronized fun hasStoredCookies(): Boolean = store.read(storageKey()).isNotEmpty()

    @Synchronized fun clear(accountId: String) = store.clear("${accountId}:${source}:")
    private fun storageKey() = "${accountId()}:${source}:$host"
}

class MemoryCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        this.cookies.removeAll { old -> cookies.any { it.name == old.name && it.domain == old.domain && it.path == old.path } }
        this.cookies += cookies
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
}

fun Response.requireHost(host: String) {
    val url = request.url
    if (url.scheme != "https" || url.host != host) throw AuthRequiredException()
}
