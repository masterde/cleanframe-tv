// Unit checks for app/device.js (the Android adapter) without a TV.
import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

// Values created inside the vm realm have foreign prototypes; compare them as plain JSON.
const plain = (x) => JSON.parse(JSON.stringify(x));
const source = fs.readFileSync(new URL('../app/device.js', import.meta.url), 'utf8');

function memoryStorage() {
  const data = {};
  return { getItem: (k) => (k in data ? data[k] : null), setItem: (k, v) => { data[k] = String(v); }, removeItem: (k) => { delete data[k]; } };
}

function load(bridge, localStorage) {
  const window = { location: { hostname: 'appassets.androidplatform.net' } };
  if (localStorage) window.localStorage = localStorage;
  if (bridge) window.CleanFrameAndroid = bridge;
  vm.runInNewContext(source, { window, JSON, Object, Array, String, RegExp, setTimeout, clearTimeout });
  return window.FrameDevice;
}

function fakeBridge(apps, extra = {}) {
  const calls = [];
  return Object.assign({
    calls,
    appsJson: () => JSON.stringify(apps),
    launch: (id) => { calls.push(['launch', id]); return id === 'broken.app' ? 'Not installed' : ''; },
    setPhotoSource: (source) => { calls.push(['source', source]); },
    closeApp: (id) => { calls.push(['close', id]); return id.indexOf('input.') === 0 ? 'Inputs can’t be closed.' : ''; },
    openSettings: () => { calls.push(['settings']); return ''; },
    openPreviousLauncher: () => { calls.push(['previous']); return ''; },
    is24Hour: () => true,
    photosJson: () => '[]',
    refreshPhotos: () => { calls.push(['refresh']); },
    takeConfig: () => ''
  }, extra);
}

const installed = [
  { appId: 'com.google.android.youtube.tv', name: 'YouTube', kind: 'app', icon: '/icons/com.google.android.youtube.tv.png' },
  { appId: 'org.smarttube.stable', name: 'SmartTube', kind: 'app', icon: '/icons/org.smarttube.stable.png' },
  { appId: 'com.amazon.amazonvideo.livingroom', name: 'Prime Video', kind: 'app', icon: '/icons/a.png' },
  { appId: 'it.rainet.androidtv', name: 'RaiPlay', kind: 'app', icon: '/icons/it.rainet.androidtv.png' },
  { appId: 'input.hw5', name: 'Nintendo Switch', kind: 'input' },
  { appId: 'input.hw6', name: 'PC', kind: 'input' }
];

// Brands follow BRANDS order and pick the first matching package (SmartTube before YouTube).
let dev = load(fakeBridge(installed));
const primary = dev.primaryApps();
assert.deepEqual(plain(primary.map((a) => a.id)), ['primevideo', 'youtube', 'nintendoswitch']);
assert.equal(primary.find((a) => a.id === 'youtube').appId, 'org.smarttube.stable');
assert.equal(primary.find((a) => a.id === 'youtube').name, 'YouTube');
assert.equal(primary.find((a) => a.id === 'nintendoswitch').appId, 'input.hw5');
assert.ok(primary.every((a) => a.mark === true));

// apps(): every entry, brand only on the chosen package, native icons kept for unbranded apps.
let listed;
dev.apps((err, list) => { assert.equal(err, null); listed = list; });
assert.equal(listed.length, 6);
assert.equal(listed.find((a) => a.appId === 'org.smarttube.stable').brand, 'youtube');
assert.equal(listed.find((a) => a.appId === 'com.google.android.youtube.tv').brand, '');
assert.equal(listed.find((a) => a.appId === 'it.rainet.androidtv').icon, '/icons/it.rainet.androidtv.png');
assert.equal(listed.find((a) => a.appId === 'input.hw6').brand, '');

// Launch results become errors with a message.
const bridge = fakeBridge(installed);
dev = load(bridge);
let result = 'unset';
dev.launchApp('org.smarttube.stable', (err) => { result = err; });
assert.equal(result, null);
dev.launchApp('broken.app', (err) => { result = err; });
assert.equal(result.message, 'Not installed');
dev.settings((err) => { result = err; });
assert.equal(result, null);
dev.closeApp('com.amazon.amazonvideo.livingroom', (err) => { result = err; });
assert.equal(result, null);
dev.closeApp('input.hw5', (err) => { result = err; });
assert.match(result.message, /Inputs/);
assert.deepEqual(plain(bridge.calls), [['launch', 'org.smarttube.stable'], ['launch', 'broken.app'], ['settings'], ['close', 'com.amazon.amazonvideo.livingroom'], ['close', 'input.hw5']]);
assert.equal(dev.is24Hour(), true);

// A bridge exception while reading the catalogue is reported, not thrown, so All apps can retry.
dev = load(fakeBridge(installed, { appsJson: () => { throw new Error('Java exception was raised during method invocation'); } }));
result = 'unset';
dev.apps((err, list) => { result = err; listed = list; });
assert.match(result.message, /installed apps/);

// Config is handed over once as an object; malformed config is ignored.
dev = load(fakeBridge(installed, { takeConfig: () => '{"home":["primevideo"],"clock":"24"}' }));
assert.deepEqual(plain(dev.takeConfig()), { home: ['primevideo'], clock: '24' });
dev = load(fakeBridge(installed, { takeConfig: () => '{oops' }));
assert.equal(dev.takeConfig(), null);

// Browser preview: no bridge -> deterministic mock catalogue and two mock Bing photos.
dev = load(null);
dev.apps((err, list) => { assert.equal(err, null); listed = list; });
assert.equal(listed.length, 10);
assert.ok(listed.some((a) => a.appId.indexOf('input.') === 0));
assert.equal(dev.photos().length, 2);
dev.launchApp('org.smarttube.stable', (err) => { result = err; });
assert.match(result.message, /TV/);
dev.closeApp('org.smarttube.stable', (err) => { result = err; });
assert.match(result.message, /Preview/);

// The photo source reaches Android only when it changes (the TV contacts Bing only for 'bing').
const sourceBridge = fakeBridge(installed);
dev = load(sourceBridge);
['builtin', 'builtin', 'bing', 'bing', 'builtin'].forEach((s) => dev.photoSource(s));
assert.deepEqual(plain(sourceBridge.calls), [['source', 'builtin'], ['source', 'bing'], ['source', 'builtin']]);
dev = load(fakeBridge(installed, { setPhotoSource: undefined }));
dev.photoSource('bing');

// A bridge failure at startup falls back to the last catalogue that loaded, so Home keeps its brands.
const storage = memoryStorage();
load(fakeBridge(installed), storage).primaryApps();
let failing = load(fakeBridge(installed, { appsJson: () => { throw new Error('Java exception'); } }), storage);
assert.deepEqual(plain(failing.primaryApps().map((a) => a.id)), ['primevideo', 'youtube', 'nintendoswitch']);
failing.apps((err) => { result = err; });
assert.match(result.message, /Could not read installed apps/);
failing = load(fakeBridge(installed, { appsJson: () => { throw new Error('Java exception'); } }), memoryStorage());
assert.deepEqual(plain(failing.primaryApps()), []);
failing = load(fakeBridge(installed, { appsJson: () => '{not json' }), storage);
assert.equal(failing.primaryApps().length, 3);

// Remote buttons: state from the bridge, learning, assigning and Accessibility settings.
const buttonBridge = fakeBridge(installed, {
  buttonsJson: () => JSON.stringify({ enabled: true, buttons: [{ code: 192, name: 'Button 5', target: 'org.smarttube.stable' }, { code: 'x' }] }),
  learnButton: () => { buttonBridge.calls.push(['learn']); return ''; },
  cancelLearnButton: () => { buttonBridge.calls.push(['cancel']); },
  setButton: (code, target) => { buttonBridge.calls.push(['set', code, target]); return code === 4 ? 'This button can’t be assigned.' : ''; },
  openAccessibilitySettings: () => { buttonBridge.calls.push(['a11y']); return ''; }
});
dev = load(buttonBridge);
assert.deepEqual(plain(dev.buttons()), { enabled: true, buttons: [{ code: 192, name: 'Button 5', target: 'org.smarttube.stable' }] });
dev.learnButton((err) => { result = err; }); assert.equal(result, null);
dev.cancelLearnButton();
dev.setButton(193, 'org.jellyfin.androidtv', (err) => { result = err; }); assert.equal(result, null);
dev.setButton(4, 'x', (err) => { result = err; }); assert.match(result.message, /can’t be assigned/);
dev.accessibilitySettings((err) => { result = err; }); assert.equal(result, null);
assert.deepEqual(plain(buttonBridge.calls), [['learn'], ['cancel'], ['set', 193, 'org.jellyfin.androidtv'], ['set', 4, 'x'], ['a11y']]);
dev = load(fakeBridge(installed, { buttonsJson: () => '{oops' }));
assert.deepEqual(plain(dev.buttons()), { enabled: false, buttons: [] });
dev = load(null);
assert.deepEqual(plain(dev.buttons()), { enabled: false, buttons: [] });
dev.learnButton((err) => { result = err; }); assert.match(result.message, /Preview/);
dev.setButton(192, 'x', (err) => { result = err; }); assert.match(result.message, /Preview/);

// Weather: only a complete reading counts; the preview and a failing bridge show none.
dev = load(fakeBridge(installed, { weatherJson: () => '{"place":"Manama","temp":32,"code":1,"day":true}', weatherPlace: () => 'Bahrain' }));
assert.deepEqual(plain(dev.weather()), { place: 'Manama', temp: 32, code: 1, day: true });
assert.equal(dev.weatherPlace(), 'Bahrain');
dev = load(fakeBridge(installed, { weatherJson: () => '' }));
assert.equal(dev.weather(), null);
dev = load(fakeBridge(installed, { weatherJson: () => '{"temp":"hot"}' }));
assert.equal(dev.weather(), null);
dev = load(null);
assert.equal(dev.weather(), null);
assert.equal(dev.weatherPlace(), '');
dev.editWeatherPlace((err) => { result = err; }); assert.match(result.message, /Preview/);

// Prayer times: only complete data counts; Android is told about on/off changes once.
dev = load(fakeBridge(installed, { prayerJson: () => '{"date":"26-10-2026","t":{"Fajr":"04:12","Dhuhr":"11:25","Asr":"14:48","Maghrib":"17:20","Isha":"18:50"},"next":"04:13"}' }));
assert.equal(plain(dev.prayer()).t.Asr, '14:48');
assert.equal(plain(dev.prayer()).next, '04:13');
dev = load(fakeBridge(installed, { prayerJson: () => '' }));
assert.equal(dev.prayer(), null);
dev = load(fakeBridge(installed, { prayerJson: () => '{"t":{}}' }));
assert.equal(dev.prayer(), null);
const prayerBridge = fakeBridge(installed, { setPrayerEnabled: (on) => { prayerBridge.calls.push(['prayer', on]); } });
dev = load(prayerBridge);
dev.setPrayerEnabled(false); dev.setPrayerEnabled(false); dev.setPrayerEnabled(true); dev.setPrayerEnabled(true);
assert.deepEqual(plain(prayerBridge.calls), [['prayer', false], ['prayer', true]]);
dev = load(null);
assert.equal(dev.prayer(), null);
dev.setPrayerEnabled(true);

// Memory: numbers only; the preview has none and explains why.
dev = load(fakeBridge(installed, { memoryJson: () => '{"availMb":470,"totalMb":2048}', freeMemory: () => {} }));
assert.deepEqual(plain(dev.memory()), { availMb: 470, totalMb: 2048 });
dev.freeMemory((err) => { result = err; }); assert.equal(result, null);
dev = load(fakeBridge(installed, { memoryJson: () => '{"availMb":"lots"}' }));
assert.equal(dev.memory(), null);
dev = load(null);
assert.equal(dev.memory(), null);
dev.freeMemory((err) => { result = err; }); assert.match(result.message, /Preview/);

console.log('device.js adapter checks passed.');

// No known brand installed: Home starts with the first installed apps (not inputs), unmarked.
dev = load(fakeBridge([
  { appId: 'it.rainet.androidtv', name: 'RaiPlay', kind: 'app', icon: '/icons/r.png' },
  { appId: 'input.hw4', name: 'HDMI 1', kind: 'input' }
]));
assert.deepEqual(plain(dev.primaryApps()), [{ id: 'it.rainet.androidtv', name: 'RaiPlay', mark: false, appId: 'it.rainet.androidtv', icon: '/icons/r.png' }]);
assert.ok(plain(dev.brandIds()).indexOf('jellyfin') >= 0);
console.log('device.js fallback checks passed.');
