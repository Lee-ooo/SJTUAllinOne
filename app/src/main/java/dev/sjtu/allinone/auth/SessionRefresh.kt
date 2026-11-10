package dev.sjtu.allinone.auth

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.sjtu.allinone.AppContainer
import dev.sjtu.allinone.auth.LoginActivity.Companion.SOURCE_CANVAS
import dev.sjtu.allinone.auth.LoginActivity.Companion.SOURCE_SCHOOL
import dev.sjtu.allinone.data.remote.AuthRequiredException
import dev.sjtu.allinone.data.remote.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private val trustedHosts = setOf("j.sjtu.edu.cn", "jaccount.sjtu.edu.cn", "oc.sjtu.edu.cn")

internal fun loginUrl(source: String) = if (source == SOURCE_CANVAS) {
    "https://oc.sjtu.edu.cn/login/openid_connect"
} else {
    "https://j.sjtu.edu.cn/app/ui/index"
}

@SuppressLint("SetJavaScriptEnabled")
internal fun loginWebView(
    context: Context,
    source: String,
    onBlocked: () -> Unit = {},
    onPlatformPage: () -> Unit,
) = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
    settings.safeBrowsingEnabled = true
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
    val targetHost = if (source == SOURCE_CANVAS) "oc.sjtu.edu.cn" else "j.sjtu.edu.cn"
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            val url = request.url
            val trusted = url.scheme == "https" && url.host in trustedHosts
            if (!trusted) onBlocked()
            return !trusted
        }

        override fun onPageFinished(view: WebView, url: String) {
            if (view.url == url && url.startsWith("https://$targetHost/")) onPlatformPage()
        }
    }
}

internal fun WebView.destroyLoginView() {
    stopLoading()
    webViewClient = WebViewClient()
    destroy()
}

private fun AppContainer.sessionCookies(source: String) =
    if (source == SOURCE_CANVAS) canvasCookies else schoolCookies

internal suspend fun AppContainer.validateWebSession(source: String) {
    withContext(Dispatchers.IO) {
        val manager = CookieManager.getInstance()
        manager.flush()
        sessionCookies(source).importWebView(manager.getCookie(sessionCookieUrl(source)))
    }
    repository.validateSession(source)
}

internal suspend fun AppContainer.refreshSavedSessions(context: Context) {
    for (source in listOf(SOURCE_SCHOOL, SOURCE_CANVAS)) {
        val hasNativeSession = withContext(Dispatchers.IO) { sessionCookies(source).hasStoredCookies() }
        if (!hasNativeSession && CookieManager.getInstance().getCookie(sessionCookieUrl(source)).isNullOrBlank()) {
            continue
        }
        try {
            withTimeoutOrNull(15_000) { repository.validateSession(source) }
        } catch (_: AuthRequiredException) {
            try {
                renewWebSession(context, source)
            } catch (_: RemoteException) {
                // Keep the login available in the account dialog when silent renewal fails.
            }
        } catch (_: RemoteException) {
            // A network or server failure does not establish that the session expired.
        }
    }
}

private suspend fun AppContainer.renewWebSession(context: Context, source: String) {
    val pages = Channel<Unit>(Channel.CONFLATED)
    val view = loginWebView(context, source) { pages.trySend(Unit) }
    try {
        view.loadUrl(loginUrl(source))
        withTimeoutOrNull(20_000) {
            for (page in pages) {
                try {
                    validateWebSession(source)
                    break
                } catch (_: AuthRequiredException) {
                    // An intermediate platform page may finish before the SSO redirect.
                }
            }
        }
    } finally {
        view.destroyLoginView()
        pages.cancel()
    }
}
