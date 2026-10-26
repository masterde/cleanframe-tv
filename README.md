# CleanFrame TV

A quieter home screen for Android TV. Your apps and HDMI inputs, a photograph and the time, with an interface made for the TV remote.

![CleanFrame TV: List layout over a photograph, with the clock and Home controls](docs/images/cleanframe-tv-list.jpg)

CleanFrame TV is an Android TV port of [CleanFrame](https://github.com/nthnclrk/CleanFrame) by Nathan Clark, a home screen originally built for Samsung The Frame (Tizen). It keeps CleanFrame's interface and wraps it in a small Android launcher. All Samsung-specific code has been removed.

**Use it at your own risk.** It has been tested on one TV. It replaces your home screen, so keep the [recovery steps](#going-back) at hand the first time you install it.

## What it does

- **List or Dock layout**, with icons, names or both, over a photograph or a light/dark background.
- **Photos:** fifteen built-in wallpapers, or the **Bing photo of the day** (optional, see [Network and privacy](#network-and-privacy)), rotating each time Home opens or every 5, 15 or 60 minutes.
- **Photo mode** hides everything except the photo, the clock and the photo's caption. Any key brings Home back.
- **HDMI inputs as apps:** each HDMI port can sit on Home like an app (for example "Nintendo Switch" or "PC") and opens that input directly.
- **All apps** lists installed TV apps and inputs. Choose up to nine for Home and arrange them with the remote.
- **Hold OK on an app to close it** and free its memory. A short press opens it.
- **Remote buttons (optional):** in Settings → Android TV → Remote buttons, press a vendor button that apps normally can't use (such as Xiaomi's Mi or Netflix key) and choose the app or input it opens.
- **24- or 12-hour clock**, defaulting to the TV's setting.
- Back on Home does nothing, so you can't fall out of the launcher by accident.

![CleanFrame TV: Dock layout with glass focus](docs/images/cleanframe-tv-dock.jpg)

## Tested hardware

| Area | Result |
| --- | --- |
| Reference TV | Xiaomi Mi TV Q1 (MiTV-MOOQ1), Android TV 10, MediaTek MT9632, 1.8 GB RAM, 1080p interface on a 4K panel |
| Verified there | Home button, remote navigation in List and Dock, launching apps, All apps with HDMI inputs, Photo mode, Bing photos, hold-to-close, Mi/Netflix button remapping |
| Memory | About 165 MB PSS for the launcher including its WebView; about 70 MB of that is file-backed and reclaimable |
| Other devices | Should run on Android TV 8.0 (API 26) and newer, but untested. Google TV restricts third-party launchers more and is untested. |

Reports from other devices are welcome; see [Contribute](#contribute).

## Install

Download the APK from [Releases](https://github.com/mdrzn/cleanframe-tv/releases/latest) (or [build it](#build-from-source)). You need a computer with [ADB](https://developer.android.com/tools/adb) and debugging enabled on the TV: Settings → Device Preferences → About, select Build seven times, then turn on Developer options → USB debugging (on many TVs this also enables network ADB on port 5555).

```sh
adb connect <tv-address>:5555
adb install CleanFrameTV-0.2.0.apk
adb shell cmd package set-home-activity io.github.mdrzn.cleanframetv/.HomeActivity
adb shell input keyevent KEYCODE_HOME
```

CleanFrame TV should now appear whenever you press Home. The [install guide](docs/install.md) covers building the APK, naming inputs, choosing Home apps from the computer, remote buttons and troubleshooting.

### Going back

- On the TV: **Settings → Android TV → Previous launcher**.
- From a computer, make another launcher the home screen again, for example Projectivy:

  ```sh
  adb shell cmd package set-home-activity com.spocky.projengmenu/.ui.home.MainActivity
  ```

  To return to the stock launcher later, note its name before installing CleanFrame TV:
  `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`.
- Uninstalling (`adb uninstall io.github.mdrzn.cleanframetv`) also hands Home back to another installed launcher.

### Reverting the TV tuning

The steps below undo everything done to one TCL Android TV (2 GB RAM) while setting up this build: the debloat, the animation speed, the Home switch, the Play Store setting and the CleanFrame TV extras. Each step is independent, so you can undo only some of them. Connect first with `adb connect <tv-address>:5555` (use `adb -s <tv-address>:5555 ...` if more than one device is attached). If adb says "No route to host" while the TV is reachable, run `adb kill-server` and connect again.

**1. Apps disabled to free memory.** These were turned off for the current user with `pm disable-user --user 0`. This brings them all back:

```sh
for p in \
  com.google.android.tvlauncher com.google.android.tvrecommendations \
  com.google.android.tungsten.setupwraith com.google.android.katniss \
  com.google.android.music com.google.android.play.games \
  com.google.android.apps.tachyon com.google.android.feedback \
  com.google.android.tv.bugreportsender \
  com.tcl.bi com.tcl.appmarket2 com.tcl.messagebox com.tcl.usercenter \
  com.tcl.esticker com.tcl.notereminder com.tcl.showmode com.tcl.tvweishi \
  com.tcl.copydatatotv com.tcl.MultiScreenInteraction_TV com.tcl.waterfall.overseas \
  com.tcl.partnercustomizer
do adb shell pm enable --user 0 "$p"; done
```

What each group was: `com.google.android.tvlauncher` is the stock Google TV home screen (see step 4); `com.google.android.katniss` is Google search and the Assistant, so voice search and the microphone button stop working while it is off; `com.google.android.tungsten.setupwraith` is the setup wizard; the `com.tcl.*` entries are TCL's own services and shop. `com.tcl.partnercustomizer` is TCL's quick-launch button screen (the "Coming soon" page); with it back on, that button opens it again unless you assigned the button to an app in CleanFrame TV (see Remote buttons). To bring back only one, run `adb shell pm enable --user 0 <package>`. `adb shell pm list packages -d` lists everything currently disabled, and that list also contains apps the TV's maker disabled before you started, which you should leave alone.

**2. ZEE5 removed.** `com.graymatrix.did` was removed for the current user with `pm uninstall --user 0`. Restore it with:

```sh
adb shell cmd package install-existing com.graymatrix.did
```

If that reports the package is not installed, install ZEE5 again from the Play Store.

**3. Animation speed.** The three animation scales were set to 0.5 (faster). Back to the default:

```sh
adb shell settings put global window_animation_scale 1.0
adb shell settings put global transition_animation_scale 1.0
adb shell settings put global animator_duration_scale 1.0
```

**4. Home screen.** Re-enable the stock launcher (step 1 does this), then find its name and make it the home screen:

```sh
adb shell pm enable --user 0 com.google.android.tvlauncher
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd package set-home-activity <component from the line above>
```

The resolve command lists the launcher Android would use now. If it still names CleanFrame TV, press Home once and pick the stock launcher in the chooser, or use **Settings → Android TV → Previous launcher** on the TV.

**5. This build.** Builds you compile yourself are signed with a debug key, so the official release APK cannot be installed over them. To switch back to the release, uninstall first and then install it (Settings and photos are lost; the Photo source and Home apps can be set again from the TV):

```sh
adb uninstall io.github.mdrzn.cleanframetv
adb install CleanFrameTV-<version>.apk
```

**6. Play Store updates.** Automatic app updates were turned off. To turn them on again: Play Store → profile icon → **Settings → Auto-update apps → Auto-update apps at any time**. The Play Store's background updater is one of the larger memory users, which is why it was turned off.

**7. Weather, prayer times and the date line.** These are off until you set them. To turn them off again, open **Settings → Date & weather**: clear the Location and save, and set Hijri date and Prayer times to Off. Night mode is in **Settings → Background** (set it to Off). None of them change anything else on the TV.

Clearing background apps with `adb shell am kill-all` changes no setting, so there is nothing to undo.

## Configure

Everything can be changed with the remote in **Settings**: background and photo source, night mode, layout, clock, date and weather (location, °C or °F, Hijri date, prayer times), typography, focus style, Apps on Home, and the Android TV group (TV settings, free up memory, remote buttons, previous launcher).

### Remote buttons

Many remotes have buttons that only ever open the TV maker's choice: on Xiaomi TVs the Mi button opens Live TV and the Netflix button opens Netflix. CleanFrame TV can give them a new job, all from the remote:

1. **Settings → Android TV → Remote buttons**. The first time, choose **Turn on remote buttons** and switch on *CleanFrame TV remote buttons* in Android's Accessibility settings (Android only lets you do this yourself).
2. **Add a button**, press the button on the remote, then pick the app or HDMI input it should open.
3. Choose an assigned button to change it or remove it.

![Remote buttons on a Xiaomi Mi TV: the Mi button (Button 5) opens YouTube and the Netflix button (Button 6) opens Jellyfin](docs/images/cleanframe-tv-remote-buttons.jpg)

Arrows, OK, Back, Home, volume and power keep their jobs and can't be assigned. Buttons show Android's key names, so a vendor key may appear as "Button 5".

### From a computer

The same preferences can be set from a computer, which is the easiest way to name HDMI inputs:

```sh
adb shell am start -n io.github.mdrzn.cleanframetv/.HomeActivity --es config '{
  "inputNames": {"input.hw5": "Nintendo Switch", "input.hw6": "PC"},
  "home": ["primevideo", "youtube", "jellyfin", "nintendoswitch"],
  "layout": "list", "bg": "photo", "source": "bing", "clock": "24"
}'
```

The [install guide](docs/install.md#configure-from-a-computer) lists every key and shows how to find input IDs.

## Network and privacy

CleanFrame TV makes no network requests unless you turn on one of these options. With the built-in wallpapers and no location set, which is the default, it stays offline. There are no accounts, analytics or ads.

- **Bing photos** (Background → Photo source): downloads the latest photos from `www.bing.com` at most every 12 hours, at 1920×1080, keeping the last eight. Bing photos are fetched for wallpaper use on your TV and are not redistributed with the app.
- **Pexels photos** (Background → Photo source): asks a search function for photos matching your keywords and downloads them from `images.pexels.com`. Only your keywords are sent.
- **Uploads**: the upload page opens only while its dialog is showing, for at most 10 minutes, behind a 6-digit code, and only answers computers and phones on your own network.
- **Weather** (Date & weather → Location): sends the place name you typed to Open-Meteo to find its coordinates, then asks it for the current weather at most every 30 minutes. No API key or account is used, and the TV's own location is never used.
- **Prayer times** (Date & weather → Prayer times): asks Aladhan (`api.aladhan.com`) for today's and tomorrow's times once a day, sending only the place's coordinates. It needs a weather location and uses the Gulf Region calculation method.
- **Hijri date** and **Night mode** use the TV's clock and calculate everything on the TV (Night mode uses the weather's sunset and sunrise when weather is on).

The optional remote-button service is an Android accessibility service. Android lets such a service see key presses so it can catch the buttons you assign. It acts only on those, passes every other key through, stores nothing and cannot read the screen. It stays off unless you enable it.

## Build from source

You need JDK 21, the Android SDK (platform 36, build tools 36.1.0), Node.js 22+ and Python 3.10+.

```sh
git clone <this repository>
cd cleanframe-tv
npm ci
npm run check            # web interface checks
npm run android:build    # android/app/build/outputs/apk/release/app-release.apk
```

Without a release key the APK is signed with the debug key, which is fine for your own TV. Release signing is described in [development](docs/development.md#signing); keys always stay outside the repository.

To try the interface in a browser without a TV:

```sh
npm run dev:public
```

Open [localhost:4795/home/](http://localhost:4795/home/) and use the arrow keys, Enter and Escape. Opening apps needs the TV; the preview says so.

| Location | Purpose |
| --- | --- |
| `app/` | The web interface (HTML, CSS, ES5 JavaScript), shared by the TV and the browser preview |
| `app/device.js` | The only code that talks to Android, through the `window.CleanFrameAndroid` bridge |
| `android/` | The Kotlin launcher: WebView host, app and input catalogue, Bing photos, remote buttons |
| `scripts/` | Preview, checks and asset generation |
| [Development](docs/development.md) / [testing](docs/testing.md) | How the pieces fit, debugging on a TV, checks |
| [Interaction behaviour](docs/interaction-behaviour.md) | What each key does, screen by screen |

## Contribute

Issues and pull requests are welcome; read [CONTRIBUTING.md](CONTRIBUTING.md) first. Reports should include the TV or box model, the Android version and what you saw, with private details removed.

## Licence and credits

CleanFrame TV is based on [CleanFrame](https://github.com/nthnclrk/CleanFrame) by Nathan Clark. The interface and its design, the bundled wallpapers and their materials come from that project. The original code and this port use the [MIT licence](LICENSE). Fonts, photographs and logos keep their own terms; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

CleanFrame TV is an independent project with no affiliation to Google, Xiaomi, Samsung, Microsoft (Bing) or any streaming service. Brand marks identify the services they belong to.
