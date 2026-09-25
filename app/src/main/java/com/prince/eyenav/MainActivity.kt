package com.prince.eyenav

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.webkit.DownloadListener
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat

/**
 * PRINCE PORTFOLIO // Android shell
 *
 * The website is the single visual source of truth. Android renders the exact
 * live portfolio through the system WebView while DOC remains available as the
 * background voice/accessibility service.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        const val PORTFOLIO_URL = "https://divinegamingblogspot-dot.github.io/Prince-Resume/"
    }

    private lateinit var webView: WebView

    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startDocVoiceCore()
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.rgb(8, 9, 13)
        window.navigationBarColor = Color.rgb(8, 9, 13)

        webView = buildWebView()
        setContentView(webView)

        webView.loadUrl(savedInstanceState?.getString("web_url") ?: PORTFOLIO_URL)

        if (
            android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startDocVoiceCore()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (webView.canGoBack()) webView.goBack()
                    else finish()
                }
            }
        )
    }

    private fun buildWebView(): WebView {
        return WebView(this).apply {
            setBackgroundColor(Color.rgb(8, 9, 13))
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false

            // Keep the portfolio's CSS 3D, transforms, gradients and animations
            // on the hardware rendering path instead of creating native-view
            // approximations that can visibly differ from Chrome.
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = true
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                useWideViewPort = true
                loadWithOverviewMode = false
                javaScriptCanOpenWindowsAutomatically = true
                mediaPlaybackRequiresUserGesture = false
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = "$userAgentString PrincePortfolioAndroid/1.0"
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    return handleUrl(request.url.toString(), view)
                }

                @Deprecated("Deprecated in API 24")
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    url: String
                ): Boolean {
                    return handleUrl(url, view)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    // Re-apply the website viewport without changing its DOM or
                    // styling. This only prevents Android WebView's legacy
                    // desktop-width fallback on older devices.
                    view.evaluateJavascript(
                        "(function(){var m=document.querySelector('meta[name=viewport]');" +
                            "if(!m){m=document.createElement('meta');m.name='viewport';" +
                            "m.content='width=device-width,initial-scale=1,viewport-fit=cover';" +
                            "document.head.appendChild(m);}})();",
                        null
                    )
                }
            }

            setDownloadListener(DownloadListener { url, _, _, _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (_: Throwable) { }
            })
        }
    }

    private fun handleUrl(rawUrl: String, view: WebView): Boolean {
        val url = rawUrl.trim()
        if (url.isBlank()) return true

        val lower = url.lowercase()
        if (
            lower.startsWith("http://") ||
            lower.startsWith("https://")
        ) {
            // Keep every portfolio page inside the app so navigation, Nova,
            // animations, 3D and internal links behave like the website.
            view.loadUrl(url)
            return true
        }

        return try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (_: Throwable) {
            true
        }
    }

    private fun startDocVoiceCore() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, DocVoiceService::class.java)
                    .setAction(DocVoiceService.ACTION_START)
            )
        } catch (_: Throwable) { }
    }

    private fun requestBatteryExemption() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (
                android.os.Build.VERSION.SDK_INT >= 23 &&
                !pm.isIgnoringBatteryOptimizations(packageName)
            ) {
                startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        } catch (_: Throwable) { }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("web_url", webView.url ?: PORTFOLIO_URL)
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = null
        webView.destroy()
        super.onDestroy()
    }
}
