# Auto-update for CleanFrame TV

Status: design agreed with the owner on 2026-11-10; not built yet.

## Goal

When the owner ships a new feature, every CleanFrame TV (a TCL Android TV and two Chromecasts with Google TV today) gets it without adb.

## Decisions (owner)

- Host: **public GitHub**. On 2026-11-10 the owner made `masterde/cleanframe-tv` itself public (no separate updates repo exists), so the update files go on an orphan branch named `updates` in that repo, pushed with the repo's deploy key. The TVs read `https://raw.githubusercontent.com/masterde/cleanframe-tv/updates/manifest.json` and the files next to it over HTTPS. Keep the update files off `main`.
- Because the source repo is public, review what it exposes before every release: the Pexels function URL in `PexelsFeed.kt`, the commit author email, and no keys, passwords or device addresses (the repo's own rule).
- Scope: **both** a web-bundle update (the files in `app/`) and an APK update.
- Checking: **signed updates, checked daily** (and shortly after the launcher starts), plus a manual "Check for updates" in Settings.

## Why two kinds of update

- Most work is in `app/` (HTML, CSS, JS) which the Kotlin shell serves from the APK with `WebViewAssetLoader`. A new bundle can be downloaded, verified and served from app storage with no Android installer, so it is silent and fast.
- Kotlin changes (bridge methods, services) need a new APK. Android shows the system installer for that, so a TV asks for one OK press. Installs done with adb have no installer of record, so no update can be unattended; do not promise silent APK updates.
- Every bundle states the lowest APK `versionCode` it needs. A TV on an older APK ignores bundles that need a newer one and offers the APK first.

## Files on the update host

```
manifest.json        signed description of the latest release
manifest.json.sig    base64 ECDSA P-256 / SHA-256 signature of manifest.json's exact bytes
bundle-<n>.zip       the contents of app/ plus bundle.json
CleanFrameTV-<versionName>.apk
```

`manifest.json`:

```json
{
  "sequence": 12,
  "issued": "2026-11-10T12:00:00Z",
  "bundle": { "version": 12, "url": "…/bundle-12.zip", "sha256": "…", "size": 1234567, "minApk": 2 },
  "apk": { "versionCode": 3, "versionName": "0.3.0", "url": "…/CleanFrameTV-0.3.0.apk", "sha256": "…", "size": 17276829 }
}
```

## Security (non-negotiable; this repo was hit by a supply-chain injection in October 2026)

- The public half of an **update-signing key** (ECDSA P-256) is compiled into the APK. The private half lives only on a trusted machine, outside every repo, passphrase-protected, and never on the TVs or in CI.
- The TV verifies the manifest signature **before** trusting any field, then verifies each downloaded file against the SHA-256 in that signed manifest. Anything that fails is discarded.
- Replay protection: the manifest `sequence` must be higher than the last one installed; downgrades are refused.
- HTTPS only; the only allowed host is the one configured at build time; redirects off that host are refused.
- Bundle extraction guards against zip-slip (reject absolute paths, `..`, symlinks), caps file count and total size, and writes to a temp directory that is renamed atomically.
- The downloaded JS runs with the app's bridge, so the signature is the only thing standing between a compromised host and code execution on the TVs. Keep the private key off any machine that has had an incident, and rotate it (ship a new APK with the new public key) if in doubt.
- The APK is verified by Android itself: it only installs over the existing app if it is signed with the **same app-signing key**. That is a separate key from the update-signing key.

## App-signing key (decision needed)

No release key exists today: `release` builds fall back to the Mac's auto-generated debug key, so all installs are signed with `~/.android/debug.keystore`. Options:

1. Keep using that key. Existing TVs keep updating in place. Back it up somewhere safe; if it is lost, no TV can take an APK update without being uninstalled first.
2. Create a proper release key and move every TV to it. That needs an uninstall and reinstall per TV (settings are lost), so it is cheapest while there are only three TVs.

## Client behaviour

1. At launcher start and then every 24 hours (and on "Check for updates"), fetch `manifest.json` and `manifest.json.sig`, verify, compare.
2. Bundle newer and compatible: download, verify SHA-256, unzip to `files/bundles/<version>.tmp`, rename to `files/bundles/<version>`, and record it as "pending". The next time Home loads it is served instead of the built-in assets.
3. Health check: the page calls a new bridge method `appReady()` shortly after it renders. If a pending bundle does not report ready within about 20 seconds, or the page crashes, mark that version bad and go back to the previous bundle (or the built-in one). Never retry a bad version.
4. APK newer: download to cache, verify, then hand it to `PackageInstaller`. The owner must allow "install unknown apps" for CleanFrame once; the Settings row opens that screen. Android then asks for confirmation.
5. Keep the previous bundle and one older for rollback; delete the rest.
6. No network request is made until the owner turns Updates on in Settings; the default is on for the owner's own builds (a build flag), off for anyone else's.

## Settings UI

Settings → Android TV → **Updates**: shows the installed APK and bundle versions, the last check, and any pending update; actions **Check now** and **Install update**. Toast text follows the existing style.

## Release tooling (on the owner's trusted machine)

`scripts/release-update.sh` (or `.mjs`): build the bundle zip from `app/`, run the build for the APK, write `manifest.json` with the next `sequence`, sign it with the update-signing key, and copy the four files into a local clone of the updates repo. Committing and pushing that repo is a manual step. It must refuse to run if the working tree is dirty or if the tests fail.

## Tests

- Kotlin unit tests: manifest parse, signature accept/reject, tampered manifest, tampered file hash, sequence replay and downgrade, `minApk` gating, zip-slip and size-limit rejections, host allow-list.
- JS adapter tests for the new bridge methods; the health-check handshake.
- Manual: a bad bundle rolls back; an APK update installs on a Chromecast and a TCL.

## Phases

1. Update-signing key + manifest format + release script + Kotlin verifier with tests.
2. Bundle download/serve from app storage + health check and rollback + Settings → Updates (bundle only).
3. APK download and `PackageInstaller` flow, the "install unknown apps" screen, and docs (README privacy section lists the new network use).

## Open items for the owner

- Create the public updates repo and decide its name.
- Choose the app-signing key option above.
- Create the update-signing key on a trusted machine and decide where the private key is kept.
