package dev.sjtu.allinone.auth

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEndpointsTest {
    @Test fun `school verification includes app scoped session cookies`() {
        val api = sessionCookieUrl("school").toHttpUrl()
        val cookie = Cookie.parse(api, "session=synthetic; Path=/app; Secure")!!
        assertFalse(cookie.matches("https://j.sjtu.edu.cn/".toHttpUrl()))
        assertTrue(cookie.matches(api))
        assertFalse(cookie.matches(sessionCookieUrl("canvas").toHttpUrl()))
    }
}
