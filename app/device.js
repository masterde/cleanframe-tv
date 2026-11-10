/* Android TV adapter. Talks to the launcher shell through the synchronous window.CleanFrameAndroid bridge. */
(function () {
  'use strict';
  /* Brand order is the default Home order. Each brand lists the Android packages that open it, best first. */
  var BRANDS = [
    { id: 'primevideo', name: 'Prime Video', packages: ['com.amazon.amazonvideo.livingroom', 'com.amazon.avod.thirdpartyclient'] },
    { id: 'youtube', name: 'YouTube', packages: ['org.smarttube.stable', 'org.smarttube.beta', 'com.teamsmart.videomanager.tv', 'com.google.android.youtube.tv'] },
    { id: 'netflix', name: 'Netflix', packages: ['com.netflix.ninja'] },
    { id: 'disneyplus', name: 'Disney+', packages: ['com.disney.disneyplus', 'com.disneyplus.mea'] },
    { id: 'jellyfin', name: 'Jellyfin', packages: ['org.jellyfin.androidtv', 'com.github.damontecres.wholphin'] },
    { id: 'twitch', name: 'Twitch', packages: ['com.s0und.s0undtv', 'tv.twitch.android.app'] },
    { id: 'appletv', name: 'Apple TV', packages: ['com.apple.atve.androidtv.appletv'] },
    { id: 'stremio', name: 'Stremio', packages: ['com.stremio.one'] },
    { id: 'spotify', name: 'Spotify', packages: ['com.spotify.tv.android'] },
    { id: 'hbomax', name: 'HBO Max', packages: ['com.wbd.stream', 'com.hbo.hbonow'] },
    { id: 'tubi', name: 'Tubi', packages: ['com.tubitv'] },
    { id: 'shahid', name: 'Shahid', packages: ['net.mbc.shahidTV'] },
    { id: 'osnplus', name: 'OSN+', packages: ['com.osn.go'] },
    { id: 'stctv', name: 'stc tv', packages: ['com.intigral.jawwytv'] },
    { id: 'putio', name: 'put.io', packages: ['io.put.putio'] },
    { id: 'nintendoswitch', name: 'Nintendo Switch', input: /switch/i }
  ];
  var PREVIEW = 'Preview: opening apps needs the TV.';
  var MOCK_APPS = [
    { appId: 'com.amazon.amazonvideo.livingroom', name: 'Prime Video', kind: 'app' },
    { appId: 'org.smarttube.stable', name: 'SmartTube', kind: 'app' },
    { appId: 'com.netflix.ninja', name: 'Netflix', kind: 'app' },
    { appId: 'org.jellyfin.androidtv', name: 'Jellyfin', kind: 'app' },
    { appId: 'com.s0und.s0undtv', name: 's0undTV', kind: 'app' },
    { appId: 'it.rainet.androidtv', name: 'RaiPlay', kind: 'app' },
    { appId: 'it.mediaset.infinitytv', name: 'Mediaset Infinity', kind: 'app' },
    { appId: 'com.android.tv.settings', name: 'Settings', kind: 'app' },
    { appId: 'input.hw5', name: 'Nintendo Switch', kind: 'input' },
    { appId: 'input.hw6', name: 'PC', kind: 'input' }
  ];
  var MOCK_PHOTOS = [
    { id: 'bing-mock-1', file: 'summit', base: 'assets/', name: 'Mock Bing photo', caption: 'Preview photo, © Example', tone: 'dark', ut: { tr: 'mist', br: 'light' }, frostVeil: 0.12 },
    { id: 'bing-mock-2', file: 'cove', base: 'assets/', name: 'Second mock photo', caption: 'Preview photo 2, © Example', tone: 'light', ut: { tr: 'light', br: 'mist' }, frostVeil: 0.12 }
  ];

  function bridge() { return window.CleanFrameAndroid || null; }
  function parse(text, fallback) {
    if (typeof text !== 'string' || !text) return fallback;
    try { return JSON.parse(text); } catch (e) { return fallback; }
  }
  var CATALOG_CACHE = 'cleanframe.catalog.v1';
  function valid(list) {
    return Array.isArray(list) ? list.filter(function (a) { return a && typeof a.appId === 'string' && typeof a.name === 'string'; }) : null;
  }
  /* The installed apps and inputs. Throws when the bridge fails; a good answer is kept for cachedInstalled(). */
  function installed() {
    var b = bridge(), list;
    if (!b) return valid(MOCK_APPS);
    list = valid(parse(b.appsJson(), null));
    if (!list) throw new Error('Unreadable app catalogue');
    try { window.localStorage.setItem(CATALOG_CACHE, JSON.stringify(list)); } catch (e) {}
    return list;
  }
  /* Startup must not fail: fall back to the last catalogue that loaded, so Home keeps its apps. */
  function cachedInstalled() {
    try { return installed(); } catch (e) {
      try { return valid(parse(window.localStorage.getItem(CATALOG_CACHE), null)) || []; } catch (e2) { return []; }
    }
  }
  /* brandId -> chosen appId, choosing the first listed package (or input name match) that is installed. */
  function brandChoices(list) {
    var byId = {}, chosen = {};
    list.forEach(function (a) { byId[a.appId] = a; });
    BRANDS.forEach(function (brand) {
      var i;
      if (brand.input) {
        for (i = 0; i < list.length; i++) if (list[i].kind === 'input' && brand.input.test(list[i].name)) { chosen[brand.id] = list[i].appId; return; }
        return;
      }
      for (i = 0; i < brand.packages.length; i++) if (byId[brand.packages[i]]) { chosen[brand.id] = brand.packages[i]; return; }
    });
    return chosen;
  }
  function result(error, done) { if (done) done(error ? { message: String(error) } : null); }
  function call(method, done) {
    var b = bridge();
    if (!b) { result(PREVIEW, done); return; }
    try { result(b[method](), done); } catch (e) { result('The TV did not respond. Please try again.', done); }
  }

  function primaryApps() {
    var list = cachedInstalled(), chosen = brandChoices(list), out;
    out = BRANDS.filter(function (brand) { return chosen[brand.id]; }).map(function (brand) {
      return { id: brand.id, name: brand.name, mark: true, appId: chosen[brand.id] };
    });
    /* No known brand installed: start Home with the first few installed apps instead of an empty list. */
    if (!out.length) out = list.filter(function (a) { return a.kind !== 'input'; }).slice(0, 5).map(function (a) {
      return { id: a.appId, name: a.name, mark: false, appId: a.appId, icon: typeof a.icon === 'string' ? a.icon : '' };
    });
    return out;
  }
  function brandIds() { return BRANDS.map(function (brand) { return brand.id; }); }
  function apps(done) {
    var list, chosen, brandOf = {};
    /* A Java exception in the bridge arrives as a thrown Error; report it so All apps offers a retry. */
    try { list = installed(); } catch (e) { done({ message: 'Could not read installed apps.' }); return; }
    chosen = brandChoices(list);
    Object.keys(chosen).forEach(function (id) { brandOf[chosen[id]] = id; });
    done(null, list.map(function (a) {
      return { appId: a.appId, name: a.name, brand: brandOf[a.appId] || '', icon: typeof a.icon === 'string' ? a.icon : '', kind: a.kind === 'input' ? 'input' : 'app' };
    }));
  }
  function launchApp(id, done) {
    var b = bridge();
    if (!b) { result(PREVIEW, done); return; }
    try { result(b.launch(String(id)), done); } catch (e) { result('The TV did not respond. Please try again.', done); }
  }
  /* Ends a backgrounded app's processes; the TV keeps HDMI inputs as they are. */
  function closeApp(id, done) {
    var b = bridge();
    if (!b || !b.closeApp) { result('Preview: closing apps needs the TV.', done); return; }
    try { result(b.closeApp(String(id)), done); } catch (e) { result('The TV did not respond. Please try again.', done); }
  }
  function launch(brand, done) {
    var chosen = brandChoices(cachedInstalled());
    if (!chosen[brand]) { result('This app is not installed.', done); return; }
    launchApp(chosen[brand], done);
  }
  function settings(done) { call('openSettings', done); }
  /* Remote buttons (Settings → Android TV): Android's accessibility service catches the assigned keys. */
  var NO_BUTTONS = 'Preview: remote buttons need the TV.';
  function buttons() {
    var b = bridge(), state = null;
    if (b && b.buttonsJson) try { state = parse(b.buttonsJson(), null); } catch (e) { state = null; }
    if (!state || typeof state !== 'object') return { enabled: false, buttons: [] };
    return { enabled: !!state.enabled, buttons: Array.isArray(state.buttons) ? state.buttons.filter(function (x) {
      return x && typeof x.code === 'number' && typeof x.name === 'string' && typeof x.target === 'string';
    }) : [] };
  }
  function learnButton(done) {
    var b = bridge();
    if (!b || !b.learnButton) { result(NO_BUTTONS, done); return; }
    try { result(b.learnButton(), done); } catch (e) { result('The TV did not respond. Please try again.', done); }
  }
  function cancelLearnButton() { var b = bridge(); if (b && b.cancelLearnButton) try { b.cancelLearnButton(); } catch (e) {} }
  function setButton(code, target, done) {
    var b = bridge();
    if (!b || !b.setButton) { result(NO_BUTTONS, done); return; }
    try { result(b.setButton(code, String(target || '')), done); } catch (e) { result('The TV did not respond. Please try again.', done); }
  }
  function accessibilitySettings(done) { call('openAccessibilitySettings', done); }
  function previousLauncher(done) { call('openPreviousLauncher', done); }
  function is24Hour() { var b = bridge(); try { return b ? !!b.is24Hour() : true; } catch (e) { return true; } }
  function photos() {
    var b = bridge(), list = b ? parse(b.photosJson(), []) : MOCK_PHOTOS;
    return Array.isArray(list) ? list.filter(function (p) { return p && typeof p.id === 'string' && typeof p.base === 'string'; }) : [];
  }
  /* Tells Android which photo source is in use (only changes are sent); it contacts Bing only for 'bing'. */
  var sentSource = null;
  function photoSource(source) {
    var b = bridge();
    if (!b || !b.setPhotoSource || source === sentSource) return;
    try { b.setPhotoSource(String(source)); sentSource = source; } catch (e) {}
  }
  function refreshPhotos() { var b = bridge(); if (b) try { b.refreshPhotos(); } catch (e) {} }
  /* Downloads now for 'bing' or 'pexels' (Pexels goes through the owner's search function). */
  function refreshSource(source) { var b = bridge(); if (b) try { if (b.refreshSource) b.refreshSource(String(source)); else b.refreshPhotos(); } catch (e) {} }
  function pexelsKeywords() { var b = bridge(); try { return b && b.pexelsKeywords ? String(b.pexelsKeywords()) : 'landscape, nature'; } catch (e) { return ''; } }
  /* Interleaves Pexels photos by keyword (newest first within each) so each rotation step shows the next keyword. Photos without a known keyword go last. */
  function orderByKeyword(walls, keywords) {
    var names = String(keywords || '').split(/[,\n]/).map(function (k) { return k.replace(/\s+/g, ' ').trim().toLowerCase(); }).filter(Boolean);
    var groups = names.map(function () { return []; }), rest = [], out = [], more = true, round = 0, g;
    (walls || []).forEach(function (w) { var i = names.indexOf(String(w && w.keyword || '').toLowerCase()); (i >= 0 ? groups[i] : rest).push(w); });
    while (more) { more = false; for (g = 0; g < groups.length; g++) if (round < groups[g].length) { out.push(groups[g][round]); more = true; } round++; }
    return out.concat(rest);
  }
  function editKeywords(done) { var b = bridge(); if (!b || !b.editKeywords) { result(PREVIEW, done); return; } try { b.editKeywords(); result('', done); } catch (e) { result('The TV did not respond. Please try again.', done); } }
  /* Current weather {place, temp, code, day} for the owner's place, or null when weather is off or not downloaded yet. */
  function weather() {
    var b = bridge(), value;
    if (!b || !b.weatherJson) return null;
    try { value = parse(b.weatherJson(), null); } catch (e) { value = null; }
    return value && typeof value.temp === 'number' && typeof value.code === 'number' ? value : null;
  }
  function weatherPlace() { var b = bridge(); try { return b && b.weatherPlace ? String(b.weatherPlace()) : ''; } catch (e) { return ''; } }
  function editWeatherPlace(done) { var b = bridge(); if (!b || !b.editWeatherPlace) { result(PREVIEW, done); return; } try { b.editWeatherPlace(); result('', done); } catch (e) { result('The TV did not respond. Please try again.', done); } }
  /* Today's prayer times {date, t: {Fajr..Isha as "HH:mm"}, next: tomorrow's Fajr} for the weather location, or null when off or not downloaded yet. */
  function prayer() {
    var b = bridge(), value;
    if (!b || !b.prayerJson) return null;
    try { value = parse(b.prayerJson(), null); } catch (e) { value = null; }
    return value && value.t && typeof value.t.Fajr === 'string' && typeof value.next === 'string' ? value : null;
  }
  /* Tells Android whether Prayer times is on (only changes are sent); it contacts Aladhan only when on. */
  var sentPrayer = null;
  function setPrayerEnabled(on) {
    var b = bridge();
    if (!b || !b.setPrayerEnabled || on === sentPrayer) return;
    try { b.setPrayerEnabled(!!on); sentPrayer = on; } catch (e) {}
  }
  /* The TV's free and total RAM in MB, or null in the preview. */
  function memory() {
    var b = bridge(), value;
    if (!b || !b.memoryJson) return null;
    try { value = parse(b.memoryJson(), null); } catch (e) { value = null; }
    return value && typeof value.availMb === 'number' && typeof value.totalMb === 'number' ? value : null;
  }
  function freeMemory(done) { var b = bridge(); if (!b || !b.freeMemory) { result(PREVIEW, done); return; } try { b.freeMemory(); result('', done); } catch (e) { result('The TV did not respond. Please try again.', done); } }
  /* Update status {state: 'off'|'current'|'available'|'pending'|'error', version, bundle, text} from the TV, or a labelled sample in the browser preview. */
  function updates() {
    var b = bridge(), value;
    if (!b) return { state: 'preview', version: '', text: 'Preview: the TV checks for updates.' };
    if (!b.updatesJson) return null;
    try { value = parse(b.updatesJson(), null); } catch (e) { value = null; }
    return value && typeof value === 'object' && typeof value.text === 'string' ? value : null;
  }
  function checkUpdates(done) { var b = bridge(); if (!b || !b.checkUpdates) { result(PREVIEW, done); return; } try { b.checkUpdates(); result('', done); } catch (e) { result('The TV did not respond. Please try again.', done); } }
  /* Tells the TV this interface rendered, so a downloaded bundle is kept. No-op in the browser preview and for the built-in interface. */
  function appReady() { var b = bridge(); if (b && b.appReady) { try { b.appReady(); } catch (e) { /* the shell's watchdog handles a silent page */ } } }
  /* AirPlay receiver (a separate open-source app): {installed, version, busy, text}, or a labelled sample in the browser preview. */
  function receiver() {
    var b = bridge(), value;
    if (!b) return { installed: false, version: '', busy: false, text: 'Preview: the TV installs the AirPlay receiver.' };
    if (!b.receiverJson) return null;
    try { value = parse(b.receiverJson(), null); } catch (e) { value = null; }
    return value && typeof value === 'object' && typeof value.text === 'string' ? value : null;
  }
  function receiverPrimary(done) { var b = bridge(); if (!b || !b.receiverPrimary) { result(PREVIEW, done); return; } try { b.receiverPrimary(); result('', done); } catch (e) { result('The TV did not respond. Please try again.', done); } }
  function receiverUpdate(done) { var b = bridge(); if (!b || !b.receiverUpdate) { result(PREVIEW, done); return; } try { b.receiverUpdate(); result('', done); } catch (e) { result('The TV did not respond. Please try again.', done); } }
  function openUpload(done) { var b = bridge(); if (!b || !b.openUpload) { result(PREVIEW, done); return; } try { b.openUpload(); result('', done); } catch (e) { result('The TV did not respond. Please try again.', done); } }
  function takeConfig() {
    var b = bridge(), value;
    if (!b) return null;
    try { value = parse(b.takeConfig(), null); } catch (e) { value = null; }
    return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
  }

  window.FrameDevice = {
    primaryApps: primaryApps, brandIds: brandIds, apps: apps, launch: launch, launchApp: launchApp, closeApp: closeApp, settings: settings,
    buttons: buttons, learnButton: learnButton, cancelLearnButton: cancelLearnButton, setButton: setButton, accessibilitySettings: accessibilitySettings,
    previousLauncher: previousLauncher, is24Hour: is24Hour, photos: photos, refreshPhotos: refreshPhotos, refreshSource: refreshSource, pexelsKeywords: pexelsKeywords, orderByKeyword: orderByKeyword, editKeywords: editKeywords, weather: weather, weatherPlace: weatherPlace, prayer: prayer, setPrayerEnabled: setPrayerEnabled, memory: memory, freeMemory: freeMemory, updates: updates, checkUpdates: checkUpdates, receiver: receiver, receiverPrimary: receiverPrimary, receiverUpdate: receiverUpdate, appReady: appReady, editWeatherPlace: editWeatherPlace, openUpload: openUpload, photoSource: photoSource,
    takeConfig: takeConfig, android: !!bridge()
  };
})();
