import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';

export const PROFILE_CACHE_PATH = './files/profileInstalled';
const PROFILE_KEYS = [
  'FDPathStatStable',
  'packageUpdateEpochMs',
  'present',
  'profileSize',
  'rawHex',
  'resultCode',
  'schema',
  'sha256',
].sort();
const sha = (value) => createHash('sha256').update(value).digest('hex');
const same = (a, b) => assert.deepEqual(a, b);

/** Current AOSP checkin schema: tag, package, appId, versionCode, lastUpdateMs, installer, source. */
export function readCurrentPackageCheckin(text, identity) {
  assert.equal(typeof text, 'string');
  assert(Buffer.byteLength(text) <= 65536 && !text.includes('\0'));
  assert.equal(identity.apiLevel, 37, 'Unverified package checkin schema');
  assert(/^[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+$/.test(identity.packageName));
  assert(Number.isSafeInteger(identity.uid) && identity.uid >= 10000);
  const rows = text
    .split(/\r?\n/)
    .map((line) => line.split(','))
    .filter((row) => row[0] === 'pkg' && row[1] === identity.packageName);
  assert.equal(rows.length, 1, 'Ambiguous or absent package row');
  const row = rows[0];
  assert.equal(row.length, 7, 'Unverified package checkin layout');
  assert.equal(row[2], String(identity.uid));
  assert.equal(row[3], String(identity.versionCode));
  assert(/^[1-9][0-9]{0,15}$/.test(row[4]) && /^[0-9]+$/.test(row[6]));
  const lastUpdateTimeMs = Number(row[4]);
  assert(Number.isSafeInteger(lastUpdateTimeMs) && lastUpdateTimeMs > 0);
  return {
    packageName: row[1],
    uid: identity.uid,
    lastUpdateTimeMs,
    rawLineSHA256: sha(row.join(',')),
  };
}

function profile(value, inventory, uid) {
  same(Object.keys(value).sort(), PROFILE_KEYS);
  assert.equal(value.present, true);
  assert.equal(value.FDPathStatStable, true);
  assert(/^[a-f0-9]{48}$/.test(value.rawHex));
  const raw = Buffer.from(value.rawHex, 'hex');
  assert.equal(value.sha256, sha(raw));
  assert.equal(inventory.files[PROFILE_CACHE_PATH], value.sha256);
  const decoded = {
    schema: raw.readInt32BE(0),
    resultCode: raw.readInt32BE(4),
    packageUpdateEpochMs: Number(raw.readBigInt64BE(8)),
    profileSize: Number(raw.readBigInt64BE(16)),
  };
  for (const [key, number] of Object.entries(decoded)) {
    assert(Number.isSafeInteger(value[key]));
    assert.equal(value[key], number);
  }
  assert.equal(decoded.schema, 1);
  assert.equal(decoded.resultCode, 0x50000, 'Only the source-verified no-embedded-profile cache');
  assert.equal(decoded.profileSize, 0);
  assert(decoded.packageUpdateEpochMs > 0);
  const stat = inventory.stat[PROFILE_CACHE_PATH];
  assert(Array.isArray(stat) && stat.length === 9);
  same(stat.slice(0, 5), [String(uid), String(uid), '600', '24', '1']);
  assert(stat.every((item) => typeof item === 'string' && /^[0-9]+$/.test(item)));
  return { raw, decoded, stat };
}

/**
 * Classify one exact derived performance-cache refresh after an independently proven App upgrade.
 * This is not a whole-user-data verdict. Callers must retain all other file/directory/OS guards,
 * verify the pinned actual AndroidX implementation, and keep original failed observations intact.
 */
export function classifyProfileCacheRefresh(input) {
  const { before, after, identity, packageCheckin, upgrade, window, library } = input;
  assert.equal(library.version, '1.4.0');
  assert.equal(library.embeddedBaselineProfile, false);
  assert(/^[a-f0-9]{64}$/.test(identity.apkSHA256));
  assert(/^[a-f0-9]{64}$/.test(upgrade.previousAPK_SHA256));
  assert.notEqual(upgrade.previousAPK_SHA256, identity.apkSHA256);
  assert.equal(upgrade.installedAPK_SHA256, identity.apkSHA256);
  assert.equal(upgrade.protectedInstallAccepted, true);
  assert(Number.isFinite(window.startEpoch) && Number.isFinite(window.endEpoch));
  assert(window.endEpoch > window.startEpoch && window.endEpoch - window.startEpoch <= 1800);
  for (const full of [before, after]) {
    assert.equal(full.packageState.app.uid, identity.uid);
    assert.equal(full.packageState.app.sha256, identity.apkSHA256);
  }
  same(before.packageState, after.packageState);
  const current = readCurrentPackageCheckin(packageCheckin, identity);
  const a = profile(before.extra.profile24, before.inventory.app, identity.uid);
  const z = profile(after.extra.profile24, after.inventory.app, identity.uid);
  assert.equal(z.decoded.packageUpdateEpochMs, current.lastUpdateTimeMs);
  assert(
    current.lastUpdateTimeMs / 1000 <= window.startEpoch,
    'Package was already installed before the capture window',
  );
  assert(a.decoded.packageUpdateEpochMs < current.lastUpdateTimeMs);
  same(a.raw.subarray(0, 8), z.raw.subarray(0, 8));
  same(a.raw.subarray(16), z.raw.subarray(16));
  same(a.stat.slice(0, 7), z.stat.slice(0, 7));
  const da = before.extra.appDirectoryStat['./files'];
  const dz = after.extra.appDirectoryStat['./files'];
  assert(Array.isArray(da) && Array.isArray(dz) && da.length === 9 && dz.length === 9);
  assert(da.every((item) => typeof item === 'string' && /^[0-9]+$/.test(item)));
  assert(dz.every((item) => typeof item === 'string' && /^[0-9]+$/.test(item)));
  same(da.slice(0, 7), dz.slice(0, 7));
  same(dz.slice(0, 2), [String(identity.uid), String(identity.uid)]);
  assert(['700', '771'].includes(dz[2]));
  assert(
    before.inventory.app.directories.includes('./files') &&
      after.inventory.app.directories.includes('./files'),
  );
  for (const index of [7, 8]) {
    assert(Number(a.stat[index]) <= Number(z.stat[index]));
    assert(Number(da[index]) <= Number(dz[index]));
    assert.equal(z.stat[index], dz[index], 'Exact leaf rewrite must bind its parent timestamp');
    assert(
      window.startEpoch <= Number(z.stat[index]) && Number(z.stat[index]) <= window.endEpoch + 1,
    );
  }
  return {
    policy: 'profileinstaller-1.4.0-exact-upgrade-cache-v1',
    path: PROFILE_CACHE_PATH,
    parentDirectory: './files',
    beforeSHA256: before.extra.profile24.sha256,
    afterSHA256: after.extra.profile24.sha256,
    packageName: current.packageName,
    uid: current.uid,
    packageUpdateTimeMs: current.lastUpdateTimeMs,
    independentPackageCheckinLineSHA256: current.rawLineSHA256,
    changedPayloadByteRange: [8, 16],
    parentFirstSevenStatFieldsExact: true,
    allOtherGuardsStillRequired: true,
    sourceAttributionAndActualAPKVerificationStillRequired: true,
    originalRawFailureMustRemainUnchanged: true,
    completeUserDataProtectionPassed: null,
  };
}
