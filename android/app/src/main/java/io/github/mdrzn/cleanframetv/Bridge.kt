package io.github.mdrzn.cleanframetv

import android.text.format.DateFormat
import android.view.KeyEvent
import android.webkit.JavascriptInterface

/**
 * window.CleanFrameAndroid — the only surface the web interface sees (app/device.js).
 * Methods are synchronous and run on a WebView binder thread.
 */
class Bridge(private val activity: HomeActivity, private val catalog: AndroidCatalog, private val photos: Photos, private val feed: BingFeed, private val pexels: PexelsFeed, private val weather: WeatherFeed, private val prayer: PrayerFeed, private val updates: UpdateChecker, private val receiver: ReceiverInstaller) {
    @Volatile var pendingConfig: String? = null

    @JavascriptInterface fun appsJson(): String = Catalog.toJson(catalog.entries())
    @JavascriptInterface fun launch(appId: String): String = catalog.launch(appId)
    @JavascriptInterface fun closeApp(appId: String): String = catalog.close(appId)
    @JavascriptInterface fun openSettings(): String = catalog.openSettings()
    @JavascriptInterface fun openPreviousLauncher(): String = catalog.openPreviousLauncher()
    @JavascriptInterface fun is24Hour(): Boolean = DateFormat.is24HourFormat(activity)
    @JavascriptInterface fun photosJson(): String = photos.json()
    @JavascriptInterface fun refreshPhotos() { feed.refresh(force = true) }
    @JavascriptInterface fun refreshSource(source: String) { if (source == PexelsFeed.PEXELS) pexels.refresh(force = true) else if (source == "bing") feed.refresh(force = true) }
    @JavascriptInterface fun setPhotoSource(source: String) { feed.setSource(source); pexels.setSource(source) }
    @JavascriptInterface fun pexelsKeywords(): String = pexels.keywords
    @JavascriptInterface fun editKeywords() = activity.runOnUiThread { activity.editKeywords() }
    @JavascriptInterface fun weatherJson(): String = weather.json()
    @JavascriptInterface fun weatherPlace(): String = weather.place
    @JavascriptInterface fun editWeatherPlace() = activity.runOnUiThread { activity.editWeatherPlace() }
    @JavascriptInterface fun prayerJson(): String = prayer.json()
    @JavascriptInterface fun setPrayerEnabled(on: Boolean) { prayer.setEnabled(on) }
    @JavascriptInterface fun memoryJson(): String = catalog.memoryJson()
    @JavascriptInterface fun freeMemory() { catalog.freeMemory { freed, avail -> activity.memoryFreed(freed, avail) } }
    @JavascriptInterface fun updatesJson(): String = updates.json()
    @JavascriptInterface fun checkUpdates() { updates.check(manual = true) }
    /** The interface rendered; a bundle downloaded by an update is trusted from now on. */
    @JavascriptInterface fun appReady() = activity.runOnUiThread { activity.bundleReady() }
    @JavascriptInterface fun receiverJson(): String = receiver.json()
    @JavascriptInterface fun receiverPrimary() { receiver.primary() }
    @JavascriptInterface fun receiverUpdate() { receiver.update() }
    @JavascriptInterface fun openUpload() = activity.runOnUiThread { activity.openUpload() }
    @JavascriptInterface fun buttonsJson(): String = Buttons.listJson(KeyRemapService.running, catalog.buttons(), ::buttonName)

    /** The next assignable key arrives as the `cleanframe:button-learned` event, with its code and name. */
    @JavascriptInterface fun learnButton(): String {
        if (!KeyRemapService.running) return "Turn on CleanFrame TV remote buttons in Accessibility settings first."
        KeyRemapService.learn(LEARN_MS) { code -> activity.buttonLearned(code, buttonName(code)) }
        return ""
    }
    @JavascriptInterface fun cancelLearnButton() = KeyRemapService.cancelLearning()
    @JavascriptInterface fun setButton(code: Int, target: String): String {
        if (!Buttons.assignable(code)) return "This button can’t be assigned."
        catalog.saveButtons(Buttons.withButton(catalog.buttons(), code, target))
        return ""
    }
    @JavascriptInterface fun openAccessibilitySettings(): String = catalog.openAccessibilitySettings()

    @JavascriptInterface fun takeConfig(): String { val value = pendingConfig ?: ""; pendingConfig = null; return value }

    private fun buttonName(code: Int) = Buttons.name(code, KeyEvent.keyCodeToString(code))

    companion object {
        /** Matches the interface's own timeout for "Press a remote button". */
        const val LEARN_MS = 30_000L
    }
}
