package com.kadaliao.kaixinguo.account

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity

/**
 * An isolated :account_login process. The user interacts with the real
 * official site; this app never collects passwords or OTPs. The result is
 * a one-time intra-app session handoff, not proof of cloud account identity.
 */
class OfficialSignInActivity : ComponentActivity() {
    private var browser: WebView? = null
    private var closing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Must precede CookieManager and WebView construction in this process.
        WebView.setDataDirectorySuffix("kxg-official-account")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val cancel = Button(this).apply {
            text = "返回"
            setOnClickListener { closeWith(null) }
        }
        val showLogin = Button(this).apply {
            text = "打开登录窗口"
            setOnClickListener {
                browser?.evaluateJavascript(
                    "window.dispatchEvent(new CustomEvent('hongguo:pc-open-login',{detail:{}}));",
                    null,
                )
            }
        }
        val complete = Button(this).apply {
            text = "完成登录"
            setOnClickListener { finishWithOfficialCookie() }
        }
        controls.addView(cancel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(showLogin, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f))
        controls.addView(complete, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f))
        root.addView(controls)
        val web = WebView(this)
        browser = web
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            saveFormData = false
            useWideViewPort = true
            loadWithOverviewMode = true
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                if (request == null) return true
                val uri = request.url
                // Keep main-frame login on the actual official site. HTTPS
                // cross-origin challenge frames are managed by the site.
                return request.isForMainFrame && !isOfficial(uri)
            }

            override fun onReceivedSslError(
                view: WebView?, handler: SslErrorHandler?, error: SslError?,
            ) {
                handler?.cancel()
                Toast.makeText(this@OfficialSignInActivity, "网页登录证书验证失败", Toast.LENGTH_LONG).show()
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, false)
            removeAllCookies { web.loadUrl(OFFICIAL) }
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun finishWithOfficialCookie() {
        if (closing || !isOfficial(Uri.parse(browser?.url ?: ""))) return
        val cookie = CookieManager.getInstance().getCookie(OFFICIAL).orEmpty()
        val hasSession = cookie.length in 1..16384 &&
            Regex("(?:^|;\\s*)sessionid(?:_ss)?=[^;\\s]+").containsMatchIn(cookie)
        if (!hasSession) {
            Toast.makeText(this, "尚未取得官网登录状态，请先完成登录", Toast.LENGTH_LONG).show()
            return
        }
        closeWith(cookie)
    }

    private fun closeWith(cookie: String?) {
        if (closing) return
        closing = true
        val result = Intent()
        if (!cookie.isNullOrBlank()) {
            result.putExtra(EXTRA_WEBSITE_SESSION, cookie)
            setResult(Activity.RESULT_OK, result)
        } else {
            setResult(Activity.RESULT_CANCELED, result)
        }
        // The isolated browser profile is cleared on every exit.
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        browser?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            clearCache(true)
            destroy()
        }
        browser = null
        finish()
    }

    override fun onDestroy() {
        if (!closing) {
            CookieManager.getInstance().removeAllCookies(null)
            browser?.destroy()
            browser = null
        }
        super.onDestroy()
    }

    companion object {
        private const val OFFICIAL = "https://hongguoduanju.com/"
        const val EXTRA_WEBSITE_SESSION = "com.kadaliao.kaixinguo.account.website_session"

        private fun isOfficial(uri: Uri): Boolean =
            uri.scheme == "https" && uri.host == "hongguoduanju.com" &&
                uri.userInfo == null
    }
}
