package dev.sjtu.allinone.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasPaginationTest {
    private fun response(link: String): Response = Response.Builder()
        .request(Request.Builder().url("https://oc.sjtu.edu.cn/api/v1/courses").build())
        .protocol(Protocol.HTTP_1_1).code(200).message("OK").header("Link", link)
        .body(okhttp3.ResponseBody.create(null, "[]")).build()

    @Test fun `next link must stay on Canvas api`() {
        val current = "https://oc.sjtu.edu.cn/api/v1/courses?per_page=100".toHttpUrl()
        assertEquals("https://oc.sjtu.edu.cn/api/v1/courses?page=2",
            nextCanvasUrl(current, response("<https://oc.sjtu.edu.cn/api/v1/courses?page=2>; rel=\"next\""))?.toString())
        var failed = false
        try {
            nextCanvasUrl(current, response("<https://evil.example/api/v1/courses?page=2>; rel=\"next\""))
        } catch (_: SourceChangedException) { failed = true }
        assertTrue(failed)
    }

    @Test fun `no next header or rel stops pagination`() {
        val current = "https://oc.sjtu.edu.cn/api/v1/courses".toHttpUrl()
        assertNull(nextCanvasUrl(current, response("")))
        assertNull(nextCanvasUrl(current, response("<https://oc.sjtu.edu.cn/api/v1/courses?page=2>; rel=\"last\"")))
    }

    @Test fun `tampered scheme is rejected`() {
        var failed = false
        try {
            nextCanvasUrl("https://oc.sjtu.edu.cn/api/v1/courses".toHttpUrl(),
                response("<http://oc.sjtu.edu.cn/api/v1/courses?page=2>; rel=\"next\""))
        } catch (_: SourceChangedException) { failed = true }
        assertTrue(failed)
    }
}
