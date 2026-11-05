package dev.sjtu.allinone

import androidx.test.platform.app.InstrumentationRegistry
import dev.sjtu.allinone.data.remote.SessionCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

/** Synthetic cookie only. Invoke save then restore around an external process restart. */
class SessionStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val account = "session-storage-regression"
    private val url = "https://j.sjtu.edu.cn/app/fixture".toHttpUrl()

    @Test fun saveSyntheticSession() {
        val jar = SessionCookieJar(context, url.host, "school") { account }
        jar.saveFromResponse(url, listOf(Cookie.Builder().name("synthetic-session").value("test-only")
            .hostOnlyDomain(url.host).path("/app").secure().build()))
        assertEquals(1, jar.loadForRequest(url).size)
    }

    @Test fun restoreSyntheticSessionAndCheckIsolation() {
        val jar = SessionCookieJar(context, url.host, "school") { account }
        try {
            assertEquals("test-only", jar.loadForRequest(url).single().value)
            assertTrue(jar.loadForRequest("https://oc.sjtu.edu.cn/app/fixture".toHttpUrl()).isEmpty())
            assertTrue(jar.loadForRequest("http://j.sjtu.edu.cn/app/fixture".toHttpUrl()).isEmpty())
            assertTrue(jar.loadForRequest("https://j.sjtu.edu.cn/".toHttpUrl()).isEmpty())
            assertTrue(SessionCookieJar(context, url.host, "school") { "another-account" }.loadForRequest(url).isEmpty())
            assertTrue(SessionCookieJar(context, url.host, "canvas") { account }.loadForRequest(url).isEmpty())
            jar.saveFromResponse(url, listOf(Cookie.Builder().name("synthetic-session").value("expired")
                .hostOnlyDomain(url.host).path("/app").secure().expiresAt(1).build()))
            assertTrue(jar.loadForRequest(url).isEmpty())
        } finally { jar.clear(account) }
    }
}
