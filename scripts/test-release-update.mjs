// Checks the release tooling's signing and manifest format without touching any real key or repository.
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import { buildManifest, signManifest, verifyManifest, gradleVersion, sha256, DEFAULT_BASE } from './release-update.mjs';

const { privateKey, publicKey } = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const priv = privateKey.export({ type: 'pkcs8', format: 'pem' }), pub = publicKey.export({ type: 'spki', format: 'pem' });

const text = buildManifest({ sequence: 5, issued: '2026-11-10T12:00:00Z', base: DEFAULT_BASE,
  bundle: { version: 5, file: 'bundle-5.zip', sha256: sha256(Buffer.from('b')), size: 1, minApk: 2 },
  apk: { versionCode: 3, versionName: '0.3.0', file: 'CleanFrameTV-0.3.0.apk', sha256: sha256(Buffer.from('a')), size: 1 } });
const parsed = JSON.parse(text);
assert.equal(parsed.bundle.url, DEFAULT_BASE + 'bundle-5.zip');
assert.equal(parsed.apk.url, DEFAULT_BASE + 'CleanFrameTV-0.3.0.apk');
assert.match(parsed.bundle.sha256, /^[0-9a-f]{64}$/);
assert.equal('apk' in JSON.parse(buildManifest({ sequence: 1, issued: 'x', base: DEFAULT_BASE, bundle: parsed.bundle && { version: 1, file: 'f', sha256: sha256(''), size: 1, minApk: 1 } })), false);

const sig = signManifest(text, priv);
assert.ok(verifyManifest(text, sig, pub));
assert.ok(!verifyManifest(text.replace('"sequence":5', '"sequence":6'), sig, pub));
assert.ok(!verifyManifest(text, sig, crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).publicKey.export({ type: 'spki', format: 'pem' })));

const other = crypto.generateKeyPairSync('ec', { namedCurve: 'secp384r1' }).privateKey.export({ type: 'pkcs8', format: 'pem' });
assert.throws(() => signManifest(text, other), /P-256/);
assert.throws(() => signManifest(text, crypto.generateKeyPairSync('ed25519').privateKey.export({ type: 'pkcs8', format: 'pem' })), /P-256/);

assert.deepEqual(gradleVersion('versionCode = 7\n versionName = "1.2.3"'), { versionCode: 7, versionName: '1.2.3' });
assert.throws(() => gradleVersion('nothing'), /not found/);
console.log('release-update checks passed.');
