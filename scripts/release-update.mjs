#!/usr/bin/env node
// Builds the signed update files for CleanFrame TV (see docs/plans/2026-11-10-auto-update-design.md).
// Run it on your trusted machine. It writes manifest.json, manifest.json.sig, bundle-<n>.zip and (with --apk) the APK into a
// local clone of the `updates` branch. It never commits or pushes: do that yourself after reading the files.
//
//   node scripts/release-update.mjs --key ~/safe/update-signing.pem --out ../cleanframe-tv-updates [--apk path/to.apk] [--base https://…/updates/]
//
// The private key must be an unencrypted-or-passphrase-free PEM readable by Node; keep it OUTSIDE this repository (the script refuses otherwise).
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const DEFAULT_BASE = 'https://raw.githubusercontent.com/masterde/cleanframe-tv/updates/';

export const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');

/** The manifest text exactly as it is signed and shipped (the TV verifies these bytes). */
export function buildManifest({ sequence, issued, base, bundle, apk }) {
  const m = { sequence, issued };
  if (bundle) m.bundle = { version: bundle.version, url: base + bundle.file, sha256: bundle.sha256, size: bundle.size, minApk: bundle.minApk };
  if (apk) m.apk = { versionCode: apk.versionCode, versionName: apk.versionName, url: base + apk.file, sha256: apk.sha256, size: apk.size };
  return JSON.stringify(m);
}

/** ECDSA P-256 / SHA-256, DER signature, base64: what UpdateVerifier checks. */
export function signManifest(manifestText, privateKeyPem) {
  const key = crypto.createPrivateKey(privateKeyPem);
  if (key.asymmetricKeyType !== 'ec' || key.asymmetricKeyDetails.namedCurve !== 'prime256v1') throw new Error('The update-signing key must be an ECDSA P-256 (prime256v1) key');
  return crypto.sign('sha256', Buffer.from(manifestText), { key, dsaEncoding: 'der' }).toString('base64');
}

export function verifyManifest(manifestText, signatureB64, publicKeyPem) {
  return crypto.verify('sha256', Buffer.from(manifestText), { key: crypto.createPublicKey(publicKeyPem), dsaEncoding: 'der' }, Buffer.from(signatureB64, 'base64'));
}

export function gradleVersion(text) {
  const code = /versionCode\s*=\s*(\d+)/.exec(text), name = /versionName\s*=\s*"([^"]+)"/.exec(text);
  if (!code || !name) throw new Error('versionCode/versionName not found in android/app/build.gradle.kts');
  return { versionCode: Number(code[1]), versionName: name[1] };
}

function fail(message) { console.error('release-update: ' + message); process.exit(1); }
function run(cmd, args, cwd) { const r = spawnSync(cmd, args, { cwd, stdio: 'inherit' }); if (r.status !== 0) fail(`${cmd} ${args.join(' ')} failed`); }

function main() {
  const args = process.argv.slice(2), opt = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : undefined; };
  const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
  const keyPath = opt('--key'), outDir = opt('--out'), apkPath = opt('--apk'), base = opt('--base') || DEFAULT_BASE;
  if (!keyPath || !outDir) fail('usage: --key <private.pem> --out <updates-clone> [--apk <file>] [--base <https url ending in />]');
  if (!base.startsWith('https://') || !base.endsWith('/')) fail('--base must be an https URL ending in /');
  const realKey = fs.realpathSync(keyPath);
  if (realKey.startsWith(root + path.sep)) fail('the private key is inside this repository; move it somewhere private first');
  if (!fs.statSync(outDir).isDirectory()) fail('--out is not a directory');
  const git = (...a) => spawnSync('git', a, { cwd: root, encoding: 'utf8' });
  if (git('status', '--porcelain').stdout.trim()) fail('the working tree is not clean: commit or stash first');
  run('npm', ['run', 'check'], root);

  const { versionCode, versionName } = gradleVersion(fs.readFileSync(path.join(root, 'android/app/build.gradle.kts'), 'utf8'));
  const manifestFile = path.join(outDir, 'manifest.json');
  const previous = fs.existsSync(manifestFile) ? JSON.parse(fs.readFileSync(manifestFile, 'utf8')) : { sequence: 0, bundle: { version: 0 } };
  const sequence = previous.sequence + 1, bundleVersion = ((previous.bundle && previous.bundle.version) || 0) + 1;

  const staging = fs.mkdtempSync(path.join(os.tmpdir(), 'cleanframe-bundle-'));
  fs.cpSync(path.join(root, 'app'), staging, { recursive: true });
  fs.writeFileSync(path.join(staging, 'bundle.json'), JSON.stringify({ version: bundleVersion, minApk: versionCode }));
  const bundleFile = `bundle-${bundleVersion}.zip`, bundleOut = path.join(outDir, bundleFile);
  run('zip', ['-X', '-r', '-q', bundleOut, '.'], staging);
  fs.rmSync(staging, { recursive: true, force: true });
  const bundleBytes = fs.readFileSync(bundleOut);

  let apk;
  if (apkPath) {
    const file = `CleanFrameTV-${versionName}.apk`, bytes = fs.readFileSync(apkPath);
    fs.writeFileSync(path.join(outDir, file), bytes);
    apk = { versionCode, versionName, file, sha256: sha256(bytes), size: bytes.length };
  }
  const text = buildManifest({ sequence, issued: new Date().toISOString().replace(/\.\d+Z$/, 'Z'), base,
    bundle: { version: bundleVersion, file: bundleFile, sha256: sha256(bundleBytes), size: bundleBytes.length, minApk: versionCode }, apk });
  const signature = signManifest(text, fs.readFileSync(keyPath));
  fs.writeFileSync(manifestFile, text);
  fs.writeFileSync(path.join(outDir, 'manifest.json.sig'), signature);
  console.log(`Wrote sequence ${sequence}, bundle ${bundleVersion}${apk ? `, APK ${versionName}` : ''} to ${outDir}. Review, then commit and push that clone yourself.`);
}

if (process.argv[1] && fs.realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) main();
