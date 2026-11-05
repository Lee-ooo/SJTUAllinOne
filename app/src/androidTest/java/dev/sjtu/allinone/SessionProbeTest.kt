package dev.sjtu.allinone

import android.webkit.CookieManager
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import android.content.Intent
import android.os.SystemClock
import dev.sjtu.allinone.auth.LoginActivity
import dev.sjtu.allinone.auth.sessionCookieUrl

/** Explicit read-only login diagnosis; never logs headers, URLs or response data. */
class SessionProbeTest {
    @Test fun nativeValidationFromUiThread(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as SJTUApplication
        withContext(Dispatchers.Main) {
            val cookies = CookieManager.getInstance().getCookie(sessionCookieUrl("school"))
            assumeTrue("Existing school WebView session required", !cookies.isNullOrBlank())
            app.container.schoolCookies.importWebView(cookies)
            app.container.repository.validateSession("school")
        }
    }

    @Test fun schoolLoginScreenRecognizesExistingSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var hasSession = false
        instrumentation.runOnMainSync {
            hasSession = !CookieManager.getInstance().getCookie(sessionCookieUrl("school")).isNullOrBlank()
        }
        assumeTrue("Existing school WebView session required", hasSession)
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, LoginActivity::class.java)
                .putExtra(LoginActivity.EXTRA_SOURCE, LoginActivity.SOURCE_SCHOOL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val deadline = SystemClock.elapsedRealtime() + 40_000
        while (!activity.isFinishing && !activity.isDestroyed && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(200)
        }
        assertTrue("Login screen should finish after native validation", activity.isFinishing)
    }

    @Test fun probeExistingCanvasSession(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as SJTUApplication
        var cookies: String? = null
        instrumentation.runOnMainSync {
            cookies = CookieManager.getInstance().getCookie(sessionCookieUrl("canvas"))
        }
        assumeTrue("Existing Canvas WebView session required", !cookies.isNullOrBlank())
        app.container.canvasCookies.importWebView(cookies)
        app.container.repository.validateSession("canvas")
        assertEquals("VALID", app.container.db.sync().get(app.container.activeAccount, "canvas")?.authState)
        Log.i("SessionProbe", "canvas nativeValidation=OK")
    }

    @Test fun probeExistingSchoolSession(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as SJTUApplication
        var root: String? = null
        var api: String? = null
        instrumentation.runOnMainSync {
            val manager = CookieManager.getInstance()
            root = manager.getCookie("https://j.sjtu.edu.cn/")
            api = manager.getCookie(sessionCookieUrl("school"))
        }
        assumeTrue("Existing school WebView session required", !api.isNullOrBlank())
        Log.i("SessionProbe", "school rootPresent=${!root.isNullOrBlank()} apiPresent=${!api.isNullOrBlank()}")
        app.container.schoolCookies.importWebView(api)
        try {
            app.container.repository.validateSession("school")
            assertEquals("VALID", app.container.db.sync().get(app.container.activeAccount, "timetable")?.authState)
            Log.i("SessionProbe", "school nativeValidation=OK")
        } catch (error: Exception) {
            Log.i("SessionProbe", "school nativeValidation=${error.javaClass.simpleName}")
            throw AssertionError("Native validation failed: ${error.javaClass.simpleName}")
        }
    }
}
