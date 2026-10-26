# Install guide

This guide installs CleanFrame TV from a computer with [ADB](https://developer.android.com/tools/adb) and makes it the TV's home screen. Nothing here needs root.

## Before you start

1. Enable debugging on the TV: Settings → Device Preferences → About, select **Build** seven times, then turn on Developer options → **USB debugging**. On many TVs this also enables network ADB on port 5555; otherwise look for a separate network debugging switch.
2. Connect and accept the prompt on the TV:

   ```sh
   adb connect <tv-address>:5555
   adb devices          # the TV should be listed as "device"
   ```

3. Note the current launcher so you can return to it:

   ```sh
   adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
   ```

   The last line is the launcher's component, for example `com.spocky.projengmenu/.ui.home.MainActivity`.

## Get the APK

Download `CleanFrameTV-<version>.apk` from [Releases](https://github.com/mdrzn/cleanframe-tv/releases/latest) and compare its SHA-256 with the one in the release notes (`sha256sum`, or `Get-FileHash` on Windows). Or build it yourself (see the README): `npm run android:build` writes `android/app/build/outputs/apk/release/app-release.apk`, signed with your own key or the debug key. An update must be signed with the same key as the installed copy, so switching between release downloads and your own builds needs an uninstall first.

## Install and make it Home

```sh
adb install CleanFrameTV-0.2.0.apk
adb shell cmd package set-home-activity io.github.mdrzn.cleanframetv/.HomeActivity
adb shell input keyevent KEYCODE_HOME
```

If the TV asks which app to use for Home, choose CleanFrame TV and **Always**. Press Home on the remote to check.

To update later, run `adb install -r` with the new APK and press Home. While the old copy is replaced there is briefly no launcher, and some TVs show another screen until Home is pressed.

## Configure from a computer

Everything below can also be changed with the remote in Settings. From a computer, send a JSON configuration; it is merged into the saved preferences and the interface reloads:

```sh
adb shell am start -n io.github.mdrzn.cleanframetv/.HomeActivity --es config '{"layout":"dock","clock":"12"}'
```

| Key | Values |
| --- | --- |
| `home` | Up to nine Home entries, in order: a brand id (`primevideo`, `youtube`, `netflix`, `disneyplus`, `jellyfin`, `twitch`, `appletv`, `stremio`, `spotify`, `hbomax`, `tubi`, `shahid`, `osnplus`, `stctv`, `putio`, `nintendoswitch`), a package name such as `it.rainet.androidtv`, or an input id such as `input.hw6` |
| `inputNames` | Names for HDMI inputs: `{"input.hw5": "Nintendo Switch"}`. An empty name restores the TV's own label. |
| `bg` | `photo`, `light`, `dark` |
| `source` | `builtin` (bundled wallpapers, default), `bing` (Bing photo of the day), `pexels` (Pexels photos for your keywords) or `mine` (uploaded photos) |
| `wall` | A bundled wallpaper (`misty`, `cove`, `forest`, `valley`, `night`, `peaks`, `bridge`, `ridge`, `golden`, `summit`, `goldcoast`, `sydney`, `amber`, `mint`, `alpine`) or a downloaded Bing photo (`bing-YYYYMMDD`) |
| `mode` | Photo rotation: `fixed`, `open` (each time Home opens), `5`, `15`, `60` (minutes) |
| `layout` | `list`, `dock` |
| `apps`, `controls` | How apps and Home controls are shown: `icons`, `both`, `text` |
| `clock` | `24`, `12` |
| `focus`, `material` | Focus style `type` or `glass`; glass tint `soft`, `frost` or `tint` |
| `font` | A typeface offered in Settings → Typography, for example `manrope` |
| `buttons` | Remote button targets; see [Remote buttons](#remote-buttons) |

Brand ids pick the first installed app of that brand. `youtube`, for example, prefers SmartTube over the official app. `nintendoswitch` is the HDMI input whose name contains "Switch".

### Finding HDMI input ids

All apps lists every HDMI port. Its id is `input.` followed by the last part of the TV's input id, in lower case:

```sh
adb shell dumpsys tv_input | grep HDMIInputService/
#   com.mediatek.tvinput/.hdmi.HDMIInputService/HW5 ...  ->  input.hw5
adb shell dumpsys tv_input | grep hdmi_port
#   TvInputHardwareInfo {id=5, ... hdmi_port=2 ...}       ->  HW5 is HDMI 2
```

The service names differ between TV makers; the last segment is what matters.

## Remote buttons

Some remotes have buttons that the TV maker handles before any app sees them. On Xiaomi TVs, for example, the Mi button opens Live TV and the Netflix button always opens Netflix. CleanFrame TV can send such buttons to an app or input of your choice through an optional accessibility service.

### On the TV

1. Open **Settings → Android TV → Remote buttons**.
2. The first time, choose **Turn on remote buttons**: Android's Accessibility settings open. Turn on **CleanFrame TV remote buttons** (Android only lets you do this yourself), then press Back.
3. Choose **Add a button**, press the remote button you want to use, and pick the app or input it should open.

Choose an assigned button to change what it opens or to remove it. Arrows, OK, Back, Home, volume and power can't be assigned. Buttons are named after Android's key names, so a vendor key may appear as, for example, "Button 5" (see the screenshot in the [README](../README.md#remote-buttons)).

### From a computer

1. **Find the button's keycode.** Turn on debug mode and enable the service (step 3), then watch the log while pressing the button:

   ```sh
   adb shell am start -n io.github.mdrzn.cleanframetv/.HomeActivity --ez debug true
   adb logcat -s CleanFrameTV:I
   #   CleanFrameTV: Key 192 (KEYCODE_BUTTON_5)
   ```

   On the Xiaomi Mi TV Q1 the Mi button is `192` and the Netflix button `193`.

2. **Assign targets** (package names or input ids). An empty object `{}` removes all assignments:

   ```sh
   adb shell am start -n io.github.mdrzn.cleanframetv/.HomeActivity --es config '{"buttons":{"192":"org.smarttube.stable","193":"org.jellyfin.androidtv"}}'
   ```

3. **Enable the service** on the TV under Settings → Device Preferences → Accessibility → **CleanFrame TV remote buttons**. From a computer, check the current value first, because this setting replaces the list of enabled services:

   ```sh
   adb shell settings get secure enabled_accessibility_services     # null if none
   adb shell settings put secure enabled_accessibility_services io.github.mdrzn.cleanframetv/.KeyRemapService
   adb shell settings put secure accessibility_enabled 1
   ```

   If other services were listed, join them with `:` instead of replacing them.

Test with the physical remote: keys sent with `adb shell input keyevent` bypass accessibility services and still reach the TV's own handler. Turn debug mode off again with `--ez debug false`.

The service acts only on the assigned keycodes. Every other key passes through unchanged; it stores nothing and cannot read the screen.

## Remote debugging

```sh
adb shell am start -n io.github.mdrzn.cleanframetv/.HomeActivity --ez debug true
```

With ADB connected, the launcher's page then appears under `chrome://inspect` in Chrome on the computer. Logs from the launcher and its page:

```sh
adb logcat -s CleanFrameTV:I CleanFrameJS:I
```

At start the launcher logs its viewport, which should read `Viewport "1920x1080 @…"`.

## Troubleshooting

| Problem | What to do |
| --- | --- |
| After closing an app, the old launcher appears | Android keeps the previous launcher's screen until it is stopped: `adb shell am force-stop <old launcher package>` once, or restart the TV. |
| The interface is too large or cut off | Check the viewport line in the log (above) and report it with your TV model. |
| After a restart the TV shows an HDMI input instead of CleanFrame TV | Some TVs (Xiaomi, for example) reopen the last HDMI input over the home screen at power-on if it was still active when the TV went off. This happens with any launcher; press Home. |
| An app shows "Closed" but keeps running | Apps with a foreground service (for example SmartTube's phone-remote link) cannot be closed by a launcher; use Settings → Apps → Force stop. |
| A remote button still opens the TV's own screen | The service is not enabled, the keycode is wrong, or you tested with `adb shell input keyevent`. |
| Bing photos don't appear | Check that Photo source is Bing and the TV is online; the log shows "Bing photo ready" or the error. Built-in wallpapers are used until photos arrive. |

## Going back or uninstalling

On the TV, Settings → Android TV → **Previous launcher** opens the other installed launcher. To make it the home screen again:

```sh
adb shell cmd package set-home-activity <component noted before installing>
```

To remove CleanFrame TV completely, first turn off its accessibility service if you enabled it, then:

```sh
adb uninstall io.github.mdrzn.cleanframetv
```
