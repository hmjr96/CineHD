package com.cinehd.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.cinehd.tv.databinding.ActivityMainBinding

/**
 * Hosts a full-screen WebView that displays https://cinehd.vc/home with
 * Android-TV-friendly D-Pad focus, back-button logic, fullscreen video,
 * splash / loading / error screens, and forced landscape orientation.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CineHDTV"
        private const val HOME_URL = "https://cinehd.vc/home"
        private const val SITE_DOMAIN = "cinehd.vc"
        private const val FILE_CHOOSER_CODE = 54321
    }

    private lateinit var binding: ActivityMainBinding
    private var webView: WebView? = null

    // Fullscreen video support
    private var customView: View? = null

    // File chooser support (if the site ever needs file upload)
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // ---------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen, landscape, keep screen on for video playback
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show splash + loading initially
        showSplash()

        // Wire the retry button
        binding.retryButton.setOnClickListener {
            retryLoading()
        }
        binding.retryButton.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.retryButton.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start()
            } else {
                binding.retryButton.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
            }
        }

        if (!isNetworkAvailable()) {
            showErrorScreen()
            return
        }

        setupWebView()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView?.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    override fun onDestroy() {
        // Destroy the WebView properly to avoid memory leaks
        webView?.let {
            it.parent?.let { p -> (p as FrameLayout).removeView(it) }
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // WebView setup
    // ---------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webviewContainer.removeAllViews()

        val wv = WebView(this)
        wv.id = View.generateViewId()
        wv.isFocusable = true
        wv.isFocusableInTouchMode = true
        wv.isClickable = true
        wv.isLongClickable = true
        wv.isScrollContainer = true

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        wv.layoutParams = lp
        binding.webviewContainer.addView(wv)
        webView = wv

        // ---- WebSettings ----
        val ws: WebSettings = wv.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.databaseEnabled = true
        ws.builtInZoomControls = false
        ws.displayZoomControls = false
        ws.setSupportZoom(false)
        ws.loadWithOverviewMode = true
        ws.useWideViewPort = true
        ws.cacheMode = WebSettings.LOAD_DEFAULT
        ws.allowFileAccess = false
        ws.allowContentAccess = true
        ws.mediaPlaybackRequiresUserGesture = false  // allow autoplay
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        ws.javaScriptCanOpenWindowsAutomatically = false
        ws.setGeolocationEnabled(false)

        // Cookies
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

        // Algorithmic darkening is OFF — let the site control its own theme
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(ws, false)
            }
        } catch (_: Exception) {
        }

        // ---- WebViewClient: in-app navigation + error handling ----
        wv.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                return handleUrl(url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                runOnUiThread { showLoading() }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                runOnUiThread {
                    hideSplash()
                    hideLoading()
                    CookieManager.getInstance().flush()
                }
                // Inject D-Pad focus helper after the page is fully loaded
                injectFocusHelper()
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: SslErrorHandler?,
                error: SslError?
            ) {
                // SECURITY: never bypass SSL errors — cancel the load
                handler?.cancel()
                runOnUiThread { showErrorScreen() }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                // Ignore sub-resource errors (images, CSS, ads) — only fail on main frame
                if (request != null && request.isForMainFrame) {
                    runOnUiThread { showErrorScreen() }
                }
            }
        }

        // ---- WebChromeClient: fullscreen video + progress ----
        wv.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress >= 100) {
                    hideLoading()
                }
            }

            // ---- Fullscreen video ----
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                binding.webviewContainer.addView(
                    view,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                hideSystemUI()
                // Hide the main WebView while fullscreen video is showing
                wv.visibility = View.GONE
            }

            override fun onHideCustomView() {
                customView?.let {
                    binding.webviewContainer.removeView(it)
                }
                customView = null
                wv.visibility = View.VISIBLE
                hideSystemUI()  // re-enter immersive mode
            }

            // ---- File chooser (rare, but keep the site functional) ----
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                try {
                    val intent = params?.createIntent()
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, FILE_CHOOSER_CODE)
                } catch (e: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    return false
                }
                return true
            }
        }

        // Load the home page
        wv.loadUrl(HOME_URL)
        wv.requestFocus()
    }

    // ---------------------------------------------------------------------
    // URL handling — keep internal links inside, send external to system
    // ---------------------------------------------------------------------

    private fun handleUrl(url: String): Boolean {
        val uri = Uri.parse(url)

        // Internal cinehd.vc links → load inside WebView
        if (uri.host != null && (
                uri.host == SITE_DOMAIN ||
                uri.host!!.endsWith(".$SITE_DOMAIN")
            )) {
            return false  // let WebView load it
        }

        // External links → ask the system to open them (Chrome or other)
        if (uri.scheme == "http" || uri.scheme == "https") {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Log.w(TAG, "No app to handle external URL: $url")
            }
            return true
        }

        // Other schemes (mailto:, tel:, intent:) → pass to system
        return try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            startActivity(intent)
            true
        } catch (e: Exception) {
            true  // block unknown schemes
        }
    }

    // ---------------------------------------------------------------------
    // D-Pad / Remote key handling
    // ---------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            // Back button — context-aware navigation
            KeyEvent.KEYCODE_BACK -> {
                return handleBack()
            }

            // Media Play/Pause → dispatch to any focused <video> element
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                dispatchMediaKey("playPause")
                return true
            }

            // Seek forward/backward
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_STEP_FORWARD -> {
                dispatchMediaKey("seekForward")
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD -> {
                dispatchMediaKey("seekBackward")
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleBack(): Boolean {
        // 1. Exit fullscreen video first
        if (customView != null) {
            // The WebChromeClient will call onHideCustomView
            webView?.let {
                // Simulate hiding custom view
                customView?.let { cv ->
                    binding.webviewContainer.removeView(cv)
                }
                customView = null
                it.visibility = View.VISIBLE
                hideSystemUI()  // re-enter immersive mode
            }
            return true
        }

        // 2. If the WebView has history → go back
        webView?.let {
            if (it.canGoBack()) {
                it.goBack()
                return true
            }
        }

        // 3. Already at home → exit app
        @Suppress("DEPRECATION")
        super.onBackPressed()
        return true
    }

    /**
     * Injects a small JS bridge that lets us control media playback from
     * Android remote keys.
     */
    private fun dispatchMediaKey(action: String) {
        val js = """
            (function() {
                var v = document.querySelector('video');
                if (!v) return;
                if ('$action' === 'playPause') {
                    if (v.paused) v.play(); else v.pause();
                } else if ('$action' === 'seekForward') {
                    v.currentTime = Math.min(v.duration || 0, v.currentTime + 10);
                } else if ('$action' === 'seekBackward') {
                    v.currentTime = Math.max(0, v.currentTime - 10);
                }
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    /**
     * Injects CSS + JS to make the site more D-Pad friendly:
     * - Adds a visible focus ring on the currently focused element
     * - Makes elements focusable if they are clickable but not focusable
     */
    private fun injectFocusHelper() {
        val js = """
            (function() {
                if (window.__cinehdFocusInjected) return;
                window.__cinehdFocusInjected = true;

                var style = document.createElement('style');
                style.innerHTML = `
                    *:focus-visible,
                    *:focus {
                        outline: 3px solid #ff6b35 !important;
                        outline-offset: 2px !important;
                        box-shadow: 0 0 12px rgba(255,107,53,0.6) !important;
                        transition: outline 0.15s ease, box-shadow 0.15s ease !important;
                    }
                    body {
                        -webkit-user-select: none;
                        user-select: none;
                    }
                    input, textarea, [contenteditable] {
                        -webkit-user-select: text;
                        user-select: text;
                    }
                `;
                document.head.appendChild(style);

                // Make clickable elements focusable for D-Pad navigation
                function makeFocusable() {
                    var els = document.querySelectorAll('a, button, [role="button"], [onclick], input, select, [tabindex]');
                    els.forEach(function(el) {
                        if (!el.hasAttribute('tabindex') && el.tabIndex < 0) {
                            el.setAttribute('tabindex', '0');
                        }
                    });
                }
                makeFocusable();

                // Re-run when DOM changes (SPA navigation)
                var observer = new MutationObserver(function() {
                    makeFocusable();
                });
                observer.observe(document.body, { childList: true, subtree: true });

                // Focus the first meaningful element so D-Pad has a starting point
                var firstFocus = document.querySelector('a, button, [role="button"], input, [tabindex]');
                if (firstFocus && document.activeElement === document.body) {
                    firstFocus.focus();
                }
            })();
        """.trimIndent()
        webView?.evaluateJavascript(js, null)
    }

    // ---------------------------------------------------------------------
    // Splash / Loading / Error / Retry
    // ---------------------------------------------------------------------

    private fun showSplash() {
        binding.splashScreen.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.VISIBLE
        binding.errorScreen.visibility = View.GONE
        binding.webviewContainer.visibility = View.GONE
    }

    private fun hideSplash() {
        if (binding.splashScreen.visibility == View.VISIBLE) {
            binding.splashScreen.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction {
                    binding.splashScreen.visibility = View.GONE
                    binding.webviewContainer.visibility = View.VISIBLE
                }
                .start()
        } else {
            binding.webviewContainer.visibility = View.VISIBLE
        }
    }

    private fun showLoading() {
        if (binding.splashScreen.visibility != View.VISIBLE) {
            binding.loadingIndicator.visibility = View.VISIBLE
        }
    }

    private fun hideLoading() {
        binding.loadingIndicator.visibility = View.GONE
    }

    private fun showErrorScreen() {
        hideLoading()
        binding.splashScreen.visibility = View.GONE
        binding.webviewContainer.visibility = View.GONE
        binding.errorScreen.visibility = View.VISIBLE
        binding.retryButton.requestFocus()
    }

    private fun retryLoading() {
        binding.errorScreen.visibility = View.GONE
        binding.splashScreen.visibility = View.VISIBLE
        binding.loadingIndicator.visibility = View.VISIBLE

        if (!isNetworkAvailable()) {
            showErrorScreen()
            return
        }

        if (webView == null) {
            setupWebView()
        } else {
            webView?.reload()
        }
    }

    // ---------------------------------------------------------------------
    // System UI
    // ---------------------------------------------------------------------

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------------------------------------------------------------
    // Network check
    // ---------------------------------------------------------------------

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
    }

    // ---------------------------------------------------------------------
    // File chooser callback
    // ---------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILE_CHOOSER_CODE) {
            var results: Array<Uri>? = null
            if (resultCode == Activity.RESULT_OK && data != null) {
                data.data?.let { results = arrayOf(it) }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

}
