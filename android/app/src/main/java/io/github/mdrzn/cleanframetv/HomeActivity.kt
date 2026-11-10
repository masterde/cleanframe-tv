package io.github.mdrzn.cleanframetv

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.File

/** The launcher: one full-screen WebView running the CleanFrame interface from app/. */
class HomeActivity : Activity() {
    private lateinit var catalog: AndroidCatalog
    private lateinit var photos: Photos
    private lateinit var feed: BingFeed
    private lateinit var pexels: PexelsFeed
    private lateinit var upload: UploadServer
    private lateinit var weather: WeatherFeed
    private lateinit var prayer: PrayerFeed
    private lateinit var updates: UpdateChecker
    private lateinit var bundles: BundleStore
    private val handler = Handler(Looper.getMainLooper())
    private var servedBundle = 0
    private var generation = 0
    private var lastRendererGone = 0L
    private lateinit var bridge: Bridge
    private var web: WebView? = null
    private var pageReady = false
    private var forwarded = false

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = event("cleanframe:apps-changed")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Started as an ordinary app (adb `am start -n`, an app list) while CleanFrame is the home screen:
        // Android would keep this copy beside the real home one, so hand the intent over and close.
        if (!intent.hasCategory(Intent.CATEGORY_HOME) && isDefaultHome()) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply { intent.extras?.let { putExtras(it) } })
            forwarded = true
            finish()
            return
        }
        catalog = AndroidCatalog(this)
        photos = Photos(this)
        feed = BingFeed(this, photos) { event("cleanframe:photos-updated") }
        pexels = PexelsFeed(this, photos) { event("cleanframe:photos-updated") }
        upload = UploadServer(this, photos, pexels) { event("cleanframe:photos-updated") }
        weather = WeatherFeed(this) { event("cleanframe:weather-updated") }
        prayer = PrayerFeed(this, weather) { event("cleanframe:weather-updated") }
        val prefs = getSharedPreferences("cleanframe", MODE_PRIVATE)
        bundles = BundleStore(File(filesDir, "bundles"), object : KeyValue {
            override fun get(key: String) = prefs.getString(key, null)
            // commit(): the launch counter must survive the process being killed by a crashing bundle.
            override fun put(key: String, value: String) { prefs.edit().putString(key, value).commit() }
        })
        updates = UpdateChecker(this, BuildConfig.UPDATE_PUBLIC_KEY, BuildConfig.UPDATE_BASE_URL, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, bundles, { runOnUiThread { rebuildWeb() } }) { text ->
            runOnUiThread { android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_LONG).show() }
        }
        bridge = Bridge(this, catalog, photos, feed, pexels, weather, prayer, updates)
        takeDebug(intent)
        takeConfig(intent)
        createWebView()
        registerReceiver(packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED); addDataScheme("package")
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        // A downloaded bundle is served from app storage; any problem falls back to the interface built into the APK.
        val builtIn = WebViewAssetLoader.AssetsPathHandler(this)
        val served = try { bundles.beginLaunch() } catch (e: Exception) { Log.w(AndroidCatalog.TAG, "Bundle lookup failed", e); 0 }
        servedBundle = served
        val assets: WebViewAssetLoader.PathHandler = if (served == 0) builtIn else BundlePathHandler(bundles.dir(served), builtIn)
        val loader = WebViewAssetLoader.Builder()
            .setDomain(HOST)
            .addPathHandler("/assets/", assets)
            .addPathHandler("/photos/", WebViewAssetLoader.InternalStoragePathHandler(this, photos.root))
            .addPathHandler("/icons/", WebViewAssetLoader.InternalStoragePathHandler(this, File(filesDir, "icons")))
            .build()
        val view = WebView(this)
        view.setBackgroundColor(Color.BLACK)
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
        view.overScrollMode = View.OVER_SCROLL_NEVER
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
            // index.html asks for a 1920px-wide viewport; overview mode fits it to the screen, so the
            // 1920x1080 stage lays out 1:1 whatever the TV's density (320dpi TVs report 960x540 otherwise).
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
        }
        view.addJavascriptInterface(bridge, "CleanFrameAndroid")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // The launcher never navigates away from its own bundled pages.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != HOST

            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                view.evaluateJavascript("innerWidth + 'x' + innerHeight + ' @' + devicePixelRatio") {
                    Log.i(AndroidCatalog.TAG, "Viewport $it")
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(AndroidCatalog.TAG, "WebView renderer gone (crash=${detail.didCrash()}); recreating")
                // A bundle that kills the renderer before it is trusted, or twice within a minute, is never served again.
                val now = System.currentTimeMillis()
                if (servedBundle != 0 && (servedBundle == bundles.pending || now - lastRendererGone < 60_000)) bundles.markBad(servedBundle)
                lastRendererGone = now
                rebuildWeb()
                return true
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.i("CleanFrameJS", "${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
        setContentView(view)
        web = view
        view.requestFocus()
        view.loadUrl("https://$HOST/assets/index.html")
        watchPending(served)
    }

    /** Tears the page down and builds it again, so a staged bundle is served (or a failed one dropped). */
    private fun rebuildWeb() {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        web = null; pageReady = false
        createWebView()
    }

    /** A pending bundle has about 20 seconds to call [bundleReady]; otherwise it is marked bad and the page is rebuilt without it. */
    private fun watchPending(served: Int) {
        val mine = ++generation
        if (served == 0 || served != bundles.pending) return
        handler.postDelayed({
            if (mine == generation && bundles.pending == served) {
                Log.w(AndroidCatalog.TAG, "Interface $served did not report ready; going back")
                bundles.markBad(served)
                rebuildWeb()
            }
        }, READY_MS)
    }

    /** Called (on the main thread) when the served page reports that it rendered. */
    fun bundleReady() {
        if (bundles.markReady(servedBundle)) Log.i(AndroidCatalog.TAG, "Interface $servedBundle is now the active interface")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeDebug(intent)
        takeConfig(intent)
        // Home pressed while already home: close panels and Photo mode.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) js("window.CleanFrameHome && window.CleanFrameHome()")
    }

    override fun onResume() {
        super.onResume()
        if (forwarded) return
        web?.onResume()
        web?.requestFocus()
        feed.refresh(force = false)
        pexels.refresh(force = false)
        updates.maybeCheck()
    }

    override fun onPause() {
        if (forwarded) { super.onPause(); return }
        // Leaving Home (e.g. for Accessibility settings) ends "Press a remote button".
        KeyRemapService.cancelLearning()
        web?.onPause()
        super.onPause()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Back (and Escape) go to the interface; the launcher itself never closes.
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) js("window.CleanFrameBack && window.CleanFrameBack()")
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) web?.clearCache(false)
    }

    override fun onDestroy() {
        if (forwarded) { super.onDestroy(); return }
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(packages)
        feed.close()
        pexels.close()
        updates.close()
        weather.close()
        prayer.close()
        upload.stop()
        web?.destroy()
        super.onDestroy()
    }

    /** Pexels keywords, typed with the TV keyboard. */
    fun editKeywords() {
        val field = android.widget.EditText(this).apply {
            setText(pexels.keywords); setSelection(text.length); setSingleLine(true)
            hint = "mountains, ocean, city at night"
        }
        val box = android.widget.FrameLayout(this).apply { setPadding(64, 24, 64, 0); addView(field) }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Pexels keywords")
            .setMessage("Separate keywords with commas. Home cycles through them.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> Thread { pexels.setKeywords(field.text.toString()); event("cleanframe:photos-updated") }.start() }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    /** Shown after Settings → Android TV → Free up memory finishes. */
    fun memoryFreed(freedMb: Long, availMb: Long) = runOnUiThread {
        android.widget.Toast.makeText(this, if (freedMb > 0) "Freed $freedMb MB. $availMb MB available." else "Nothing more to free. $availMb MB available.", android.widget.Toast.LENGTH_LONG).show()
    }

    /** Weather location, typed with the TV keyboard. Empty turns weather off. */
    fun editWeatherPlace() {
        val field = android.widget.EditText(this).apply {
            setText(weather.place); setSelection(text.length); setSingleLine(true)
            hint = "Bahrain"
        }
        val box = android.widget.FrameLayout(this).apply { setPadding(64, 24, 64, 0); addView(field) }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Weather location")
            .setMessage("A city or country, such as Bahrain or Manama. Leave empty to turn weather off. Only this name is sent to Open-Meteo.")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> weather.setPlace(field.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
        field.requestFocus()
    }

    /** Opens the upload page for as long as this dialog is showing (at most 10 minutes). */
    fun openUpload() {
        val session = upload.start()
        val message = if (session == null) "The TV isn’t connected to a local network." else
            "On a computer or phone on the same Wi-Fi, open\n\n${session.url}\n\nand enter this code:\n\n${session.code.chunked(3).joinToString(" ")}\n\n" +
                "Uploads appear in Background → Uploads. The page closes when you close this, or after 10 minutes."
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Upload photos")
            .setMessage(message)
            .setPositiveButton("Done", null)
            .setOnDismissListener { upload.stop() }
            .show()
    }

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    /**
     * `--ez debug true|false` turns chrome://inspect access and the remote-button service's keycode
     * logging on or off. Debug builds always allow chrome://inspect.
     */
    private fun takeDebug(intent: Intent?) {
        val prefs = getSharedPreferences("cleanframe", MODE_PRIVATE)
        if (intent?.hasExtra(DEBUG) == true) prefs.edit().putBoolean(DEBUG, intent.getBooleanExtra(DEBUG, false)).apply()
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        WebView.setWebContentsDebuggingEnabled(debuggable || prefs.getBoolean(DEBUG, false))
    }

    /** `adb shell am start -n <pkg>/.HomeActivity --es config '<json>'` — see README. */
    private fun takeConfig(intent: Intent?) {
        val json = intent?.getStringExtra("config") ?: return
        try {
            val config = Catalog.splitConfig(json)
            if (config.inputNames.isNotEmpty()) catalog.saveInputNames(config.inputNames)
            config.buttons?.let { catalog.saveButtons(it) }
            Log.i(AndroidCatalog.TAG, "Configuration received (${config.inputNames.size} input names, ${config.buttons?.size ?: "unchanged"} buttons)")
            // Input names show up on the next catalogue refresh; interface settings need a reload.
            if (config.web == "{}" && config.inputNames.isEmpty()) return
            bridge.pendingConfig = config.web
            if (pageReady) web?.reload()
        } catch (e: Exception) {
            Log.w(AndroidCatalog.TAG, "Ignoring malformed config", e)
        }
    }

    private fun event(name: String) = js("window.dispatchEvent(new Event('$name'))")

    /** Called from [KeyRemapService] (main thread) when Settings is assigning a remote button. */
    fun buttonLearned(code: Int, name: String) =
        js("window.dispatchEvent(new CustomEvent('cleanframe:button-learned',{detail:{code:$code,name:${Catalog.quote(name)}}}))")

    private fun js(code: String) {
        runOnUiThread { if (pageReady) web?.evaluateJavascript(code, null) }
    }

    companion object {
        const val HOST = "appassets.androidplatform.net"
        const val DEBUG = "debug"
        const val READY_MS = 20_000L
    }
}
