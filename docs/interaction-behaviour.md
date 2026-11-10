# Interaction behaviour — CleanFrame TV 0.1

Based on CleanFrame 0.6.1. Changes for Android TV: Photo mode replaces Art Mode, the Samsung group is now Android TV, HDMI inputs are entries like apps, Back on Home does nothing, the clock can be 24-hour, photos can come from Bing, holding OK closes an app, and remote buttons can be reassigned.

## Home

- Dock: Left/Right scrolls the apps without wrapping. Up enters the top-right controls, starting with Photo mode or the last selected control. Left/Right moves between Photo mode, All apps and Settings, and moving past either end returns to the apps. Down or Back returns to the selected app. The clock is at the top left.
- List: Up/Down scrolls the apps without wrapping. Right enters the bottom-right controls, aligned with the clock above. Up/Down moves between Photo mode, All apps and Settings, and moving past either end returns to the apps. Left or Back returns to the selected app. The list fades at both ends as it scrolls.
- With the Pexels photo source, each rotation step moves to the next Pexels keyword (in the order you entered them) and shows a photo for that keyword (newest first); the rotation interval is the one chosen under Background → Rotation.
- Information sits in three zones so nothing is crowded: the clock and date (top left in Dock, top right in List), the weather under the controls in Dock or under the date in List, and the Hijri date with the next prayer above the dock (Dock) or above the controls (List). Zones that are off take no space. Photo mode keeps the clock, date, weather, Hijri date and next prayer, and moves the Hijri date and prayer to the bottom left.
- While the Home controls have focus, no app looks selected; the dock or list returns to the app you left when you go back. OK opens the selected item. Focus alone never launches an app.
- On an app (Home or All apps), OK opens it when the key is released. Holding OK for 0.65 seconds closes that app instead and shows "Closed <app>." under it. All other controls act when OK goes down. Held OK and Back act once until the key is released.
- Back on Home with the app selected does nothing: the launcher has nowhere to exit to. Back from the Home controls returns to the selected app.
- Pressing Home while CleanFrame TV is showing closes any open panel and Photo mode and returns to Home.
- Icons-only apps and controls reveal their names when focused. App presentation and control presentation are independent.
- A panel keeps focus within its own controls. Back closes one panel at a time.
- Returning to the launcher keeps the selected app or panel item. There is no auto-return timer.

## Photo mode

Photo mode hides the apps, controls and scrims, leaving the photograph, the clock and, for Bing photos, the photo's caption. Any key leaves Photo mode without acting on that key, and focus returns to the control it came from. Home also leaves it.

## Opening apps and inputs

Apps open with their TV (leanback) launch intent, or their phone launch intent if they have none. HDMI inputs open through Android's TV input framework (the passthrough channel of that input), which shows the input directly in the TV's own player. A missing app or unavailable input produces a real error message; nothing is simulated.

The default Home apps are brands found on the TV, in this order: Prime Video, YouTube (SmartTube is preferred over the official app), Netflix, Disney+, Jellyfin, Twitch (s0undTV, then the official app), Apple TV, Stremio, Spotify, HBO Max, Tubi and the HDMI input named like "Switch". If none is installed, Home starts with the first installed apps. Known brands use bundled marks; other apps use their installed icon, with initials as a fallback.

## Closing apps

Hold-to-close ends the app's background processes, which frees its memory. It is the strongest close Android allows a launcher; Settings' Force stop is reserved for system apps. Apps that keep a foreground service, such as SmartTube's phone-remote link, are left running by Android. Inputs cannot be closed. In the browser preview the action reports that it needs the TV.

## Remote buttons

Settings → Android TV → Remote buttons lists the assigned buttons ("Button 5 · Opens YouTube") and Add a button. If the accessibility service is off, the screen offers Turn on remote buttons, which opens Android's Accessibility settings, and Add a button is inactive until the service runs.

Add a button shows "Press a remote button". The next assignable key goes to CleanFrame TV instead of its usual job (a vendor key no longer opens its own screen); arrows, OK, Back, Home, volume and power keep working, and Back or Cancel ends the prompt, as do 30 seconds without a key or leaving Home. The key opens a picker: the All apps grid titled "Choose what <button> opens", where OK assigns the focused app or input at once (holding OK does not close apps here). Choosing an assigned button opens the same picker with its current target marked Assigned and a Remove action at the top. After a change, focus returns to that button.

With the service enabled, an assigned button opens its app or input from anywhere, before the TV's own handler sees it. Unassigned keys behave as before. Assignments can also be made from a computer (see the [install guide](install.md#remote-buttons)).

## Settings and appearance

Settings opens a grouped overview: Background, Layout, Typography, Focus, Apps on Home and Android TV. OK opens a group. Appearance groups use a two-pane screen with visible choices on the left and the committed Home preview on the right.

| Group | Choices |
| --- | --- |
| Background | Photo, Light, Dark; Photo source (Built-in or Bing), Wallpaper and Rotation when Photo is active |
| Layout | List or Dock; Text, Icons or Both for apps and for Home controls; Clock 24-hour or 12-hour |
| Typography | Manrope and the other bundled typefaces |
| Focus | Type or Glass; Soft, Frost or Clear tint material |
| Apps on Home | Membership and arrangement editor |
| Android TV | TV settings (Android's Settings app), Remote buttons (below) and Previous launcher (the other installed launcher, Projectivy first, or Android's home chooser) |

Up/Down moves between rows. Left/Right moves between choices in a horizontal row. Focus alone changes neither the preview nor saved preferences in these appearance groups. Wallpaper has its own temporary photo preview, described below. OK applies the focused value. A checkmark identifies the applied value; the filled plate identifies remote focus. Applying the current value does not write again.

Material applies to a photographic Dock, or Glass focus on a photographic List. Inactive material choices remain visible with an explanation and are skipped during navigation. Their stored value is retained. Photo source, wallpaper and rotation are also inactive on flat backgrounds.

A font or photo change applies only after its resources are ready and storage succeeds. A load or storage failure keeps the previous appearance and checkmark, with a Try again action. Back cancels a pending change, leaves the group and keeps completed changes. Late load callbacks cannot apply a cancelled choice. There is no Save button. Returning to a group restores its last choice focus; returning to Settings restores its category focus. Rotation is nested within Background, and Back returns to its Rotation row.

The selected font applies to Home app names, the clock and Home controls. Settings, All apps, the Home editor and status text keep Manrope. The preview shows the committed Home appearance and its clock keeps updating. An unavailable optional face is omitted. If the baseline font is slow or missing, Home remains usable with a local fallback and recovers when the font loads.

## All apps

All apps lists the installed apps that have a launcher entry, one per app (the TV activity is preferred), and the HDMI inputs, all sorted by name. System apps that only have a phone-style entry (vendor services, the desk clock) are hidden, and CleanFrame TV does not list itself. HDMI inputs show the TV's label or the name given to them. Tuners, composite/AV inputs and devices behind an HDMI port are not listed. The list refreshes when apps are installed, removed or updated.

The five-column grid scrolls with remote focus. OK launches an app; its On Home badge shows membership. Holding OK closes it. Up from the first row reaches Apps on Home, which opens the editor. A catalogue failure shows a retry and keeps saved Home choices. The browser preview uses a fixed mock catalogue.

## Apps on Home

Open the editor from Settings or All apps. On Home shows the current order on the left. Not on Home shows Recently removed first, followed by other installed apps and inputs on the right. Home holds one to nine entries.

- Reorder: OK on an app name picks it up. Up/Down moves it. OK places it and saves the order. Back puts an unplaced app back without saving that move.
- Remove: Right from an app name reaches Remove from Home. OK saves the removal and moves focus to the same app in Recently removed. The app stays installed.
- Add: OK on an app in Not on Home adds it and saves. A recently removed app returns to its previous position within the editing session; another app is added at the end. Focus follows the app to its Remove from Home control, so another OK can reverse the addition.
- Undo reverses a completed addition, removal or reorder from the current editing session. Done or Back closes the editor and keeps completed changes. Undo history ends when the editor closes.

Removing the last Home app or adding a tenth is prevented. A failed storage write keeps the committed choices and order, with a retry message. Saved app ids and names survive a restart or a failed catalogue refresh.

## Preferences and wallpaper

The launcher stores appearance, Home order, recently removed apps, the last launched Home app, wallpaper and rotation in the WebView's local storage (`cleanframe.home.v1`, with upstream's migrations). List and Dock share these preferences. An ADB configuration (`--es config`) is merged into them at start; see the [install guide](install.md#configure-from-a-computer). HDMI input names and remote-button assignments are kept by the Android side.

The built-in gallery contains 15 wallpapers. With Photo source set to Bing, the wallpaper list holds the downloaded Bing photos instead (up to eight, newest first); until the first download, the built-in photo stays in place and the source row says so. Settings → Background → Wallpaper keeps the two-pane Settings frame. Up/Down browses a vertical thumbnail list without wrapping; the right-hand Home preview shows the candidate while the surrounding Settings backdrop stays on the applied photo. OK applies the focused photo and stays in Wallpaper. The tick marks the applied photo and preview status distinguishes unsaved browsing. Back restores the latest applied photo, cancels pending loading and returns to Background's Wallpaper row. Photo and material assets change as one complete bundle; a failed load restores the applied preview and offers retry. Preview loading waits briefly for remote focus to settle. OK during loading queues application for that photo; another browse move or Back cancels it. Browsing never saves, and repeated OK on the applied photo does not write again.

Settings → Background → Rotation offers Fixed, Each time Home opens, Every 5 minutes, Every 15 minutes and Every hour. Each time Home opens advances once when the launcher loads; a return to the already-running launcher does not advance it. Timed rotation counts only visible Photo Home time in either List or Dock. Panels, flat backgrounds and time spent in other apps pause the cycle. Changing the photo or rotation mode starts a new interval.

## Clock and rendering

Both layouts show the TV's local time, in 24-hour or 12-hour format without AM/PM or seconds. The default follows the TV's time format setting. It updates at the minute boundary and on return to the launcher. No date or clock panel is shown.

Photographic materials and blurred panel backgrounds are generated ahead of time: for bundled wallpapers before packaging, for Bing photos once on the TV when each photo arrives. Photo colour and contrast metadata are prepared at the same time. Navigation does not sample image pixels or apply live backdrop blur. Flat backgrounds use matching light or dark panel colours. Focus feedback uses transforms and opacity; reduced motion removes animated scaling and movement while retaining opacity feedback.
