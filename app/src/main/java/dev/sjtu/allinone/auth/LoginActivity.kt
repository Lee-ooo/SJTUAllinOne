package dev.sjtu.allinone.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.sjtu.allinone.SJTUApplication
import dev.sjtu.allinone.data.remote.AuthRequiredException
import dev.sjtu.allinone.data.remote.RemoteException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {
    private val source by lazy { intent.getStringExtra(EXTRA_SOURCE) ?: SOURCE_SCHOOL }
    private var webView: WebView? = null
    private var validating by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startUrl = if (source == SOURCE_CANVAS) CANVAS_LOGIN else SCHOOL_LOGIN
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (source == SOURCE_CANVAS) "登录 Canvas" else "登录学在交大",
                            style = MaterialTheme.typography.titleLarge)
                        Text("请仅在学校域名页面完成登录。应用不会读取或保存密码。",
                            style = MaterialTheme.typography.bodySmall)
                        if (validating) LinearProgressIndicator(Modifier.fillMaxWidth())
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Button(onClick = { validateSession() }, enabled = !validating) {
                            Text("我已完成登录，验证并继续")
                        }
                    }
                    val context = LocalContext.current
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        factory = {
                            WebView(context).also { view ->
                                webView = view
                                view.settings.javaScriptEnabled = true
                                view.settings.domStorageEnabled = true
                                view.settings.allowFileAccess = false
                                view.settings.allowContentAccess = false
                                view.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                view.settings.safeBrowsingEnabled = true
                                CookieManager.getInstance().setAcceptCookie(true)
                                CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
                                view.webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView, request: WebResourceRequest,
                                    ): Boolean {
                                        val url = request.url
                                        if (!request.isForMainFrame) return false
                                        val trusted = url.scheme == "https" && url.host in TRUSTED_HOSTS
                                        if (!trusted) error = "已阻止跳转到非学校登录页面"
                                        return !trusted
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        super.onPageFinished(view, url)
                                        val targetHost = if (source == SOURCE_CANVAS) "oc.sjtu.edu.cn" else "j.sjtu.edu.cn"
                                        if (view.url?.startsWith("https://$targetHost/") == true) {
                                            validateSession()
                                        }
                                    }
                                }
                                if (savedInstanceState == null || view.restoreState(savedInstanceState) == null) {
                                    view.loadUrl(startUrl)
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        webView?.let {
            it.stopLoading()
            it.webViewClient = WebViewClient()
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    private fun validateSession() {
        if (validating) return
        val container = (application as SJTUApplication).container
        validating = true
        error = null
        val targetUrl = sessionCookieUrl(source)
        CookieManager.getInstance().flush()
        val cookies = CookieManager.getInstance().getCookie(targetUrl)
        lifecycleScope.launch {
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (source == SOURCE_CANVAS) container.canvasCookies.importWebView(cookies)
                    else container.schoolCookies.importWebView(cookies)
                }
                container.repository.validateSession(source)
                setResult(Activity.RESULT_OK)
                Toast.makeText(this@LoginActivity, "登录验证成功", Toast.LENGTH_SHORT).show()
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = when (failure) {
                    is AuthRequiredException -> "尚未取得该平台的有效会话，请完成登录后重试验证。"
                    is RemoteException -> "登录验证未完成：${failure.message}"
                    else -> "登录验证发生异常，请重试；网页登录状态已保留。"
                }
            } finally {
                validating = false
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        const val SOURCE_SCHOOL = "school"
        const val SOURCE_CANVAS = "canvas"
        private const val SCHOOL_LOGIN = "https://j.sjtu.edu.cn/app/ui/index"
        private const val CANVAS_LOGIN = "https://oc.sjtu.edu.cn/login/openid_connect"
        private val TRUSTED_HOSTS = setOf("j.sjtu.edu.cn", "jaccount.sjtu.edu.cn", "oc.sjtu.edu.cn")
    }
}
