import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createHash } from 'node:crypto';
import {
  classifyProfileCacheRefresh,
  readCurrentPackageCheckin,
  PROFILE_CACHE_PATH,
} from './android-profile-cache.mjs';

function sample() {
  const uid = 12000;
  const oldTime = 1700000000123;
  const newTime = 1700100000456;
  const encode = (time) => {
    const raw = Buffer.alloc(24);
    raw.writeInt32BE(1);
    raw.writeInt32BE(0x50000, 4);
    raw.writeBigInt64BE(BigInt(time), 8);
    return {
      FDPathStatStable: true,
      present: true,
      rawHex: raw.toString('hex'),
      schema: 1,
      resultCode: 0x50000,
      packageUpdateEpochMs: time,
      profileSize: 0,
      sha256: createHash('sha256').update(raw).digest('hex'),
    };
  };
  const full = (time, changedAt) => {
    const profile24 = encode(time);
    return {
      packageState: { app: { uid, sha256: 'b'.repeat(64) } },
      inventory: {
        app: {
          directories: ['./files'],
          files: { [PROFILE_CACHE_PATH]: profile24.sha256 },
          stat: {
            [PROFILE_CACHE_PATH]: [
              String(uid),
              String(uid),
              '600',
              '24',
              '1',
              '42',
              '51',
              changedAt,
              changedAt,
            ],
          },
        },
      },
      extra: {
        profile24,
        appDirectoryStat: {
          './files': [
            String(uid),
            String(uid),
            '771',
            '4096',
            '3',
            '42',
            '50',
            changedAt,
            changedAt,
          ],
        },
      },
    };
  };
  return {
    before: full(oldTime, '1699999999'),
    after: full(newTime, '1700100002'),
    identity: {
      packageName: 'com.example.acceptance',
      uid,
      versionCode: 1,
      apiLevel: 37,
      apkSHA256: 'b'.repeat(64),
    },
    packageCheckin: `vers,1\npkg,com.example.acceptance,12000,1,${newTime},?-1?,1\n`,
    upgrade: {
      previousAPK_SHA256: 'a'.repeat(64),
      installedAPK_SHA256: 'b'.repeat(64),
      protectedInstallAccepted: true,
    },
    library: { version: '1.4.0', embeddedBaselineProfile: false },
    window: { startEpoch: 1700100001, endEpoch: 1700100030 },
  };
}

test('fixed cache classifier binds only one performance record to independent exact package time', () => {
  const value = sample();
  const frozen = JSON.stringify(value);
  const result = classifyProfileCacheRefresh(value);
  assert.equal(result.path, PROFILE_CACHE_PATH);
  assert.equal(result.packageUpdateTimeMs, 1700100000456);
  assert.equal(result.completeUserDataProtectionPassed, null);
  assert.equal(result.allOtherGuardsStillRequired, true);
  assert.equal(JSON.stringify(value), frozen, 'observations are never rewritten');
});

const cases = {
  'API/schema mismatch': (x) => {
    x.identity.apiLevel = 36;
  },
  'wrong package row': (x) => {
    x.packageCheckin = x.packageCheckin.replace('com.example.acceptance', 'com.example.other');
  },
  'duplicate package row': (x) => {
    x.packageCheckin += x.packageCheckin.split('\n')[1] + '\n';
  },
  'legacy or trailing checkin field': (x) => {
    x.packageCheckin = x.packageCheckin.replace(',?-1?,1', ',1690000000000,?-1?,1');
  },
  'same-second but different independent millisecond': (x) => {
    x.packageCheckin = x.packageCheckin.replace('1700100000456', '1700100000457');
  },
  'wrong uid': (x) => {
    x.after.packageState.app.uid++;
  },
  'App changed during Native': (x) => {
    x.after.packageState.app.sha256 = 'c'.repeat(64);
  },
  'no preceding new App': (x) => {
    x.upgrade.previousAPK_SHA256 = x.identity.apkSHA256;
  },
  'package update occurred after capture started': (x) => {
    x.window.startEpoch = 1700099999;
  },
  'upgrade unconfirmed': (x) => {
    x.upgrade.protectedInstallAccepted = false;
  },
  'different library': (x) => {
    x.library.version = '1.4.1';
  },
  'embedded profile': (x) => {
    x.library.embeddedBaselineProfile = true;
  },
  'unsettled FD': (x) => {
    x.after.extra.profile24.FDPathStatStable = false;
  },
  'truncated or trailing payload': (x) => {
    x.after.extra.profile24.rawHex += '00';
  },
  'raw cache SHA mismatch': (x) => {
    x.after.extra.profile24.sha256 = '0'.repeat(64);
  },
  'inventory cache SHA mismatch': (x) => {
    x.after.inventory.app.files[PROFILE_CACHE_PATH] = '0'.repeat(64);
  },
  'unknown cache field': (x) => {
    x.after.extra.profile24.extra = true;
  },
  'schema changed': (x) => {
    x.after.extra.profile24.schema = 2;
  },
  'result code changed': (x) => {
    x.after.extra.profile24.resultCode++;
  },
  'profile size changed': (x) => {
    x.after.extra.profile24.profileSize = 1;
  },
  'raw epoch disagrees with decoded metadata': (x) => {
    x.after.extra.profile24.packageUpdateEpochMs++;
  },
  'file identity changed': (x) => {
    x.after.inventory.app.stat[PROFILE_CACHE_PATH][6] = '52';
  },
  'file owner changed': (x) => {
    x.after.inventory.app.stat[PROFILE_CACHE_PATH][0] = '12001';
  },
  'file is not private': (x) => {
    x.after.inventory.app.stat[PROFILE_CACHE_PATH][2] = '644';
  },
  hardlink: (x) => {
    x.after.inventory.app.stat[PROFILE_CACHE_PATH][4] = '2';
  },
  'directory mode changed': (x) => {
    x.after.extra.appDirectoryStat['./files'][2] = '700';
  },
  'directory membership vanished': (x) => {
    x.after.inventory.app.directories = [];
  },
  'directory inode changed': (x) => {
    x.after.extra.appDirectoryStat['./files'][6] = '77';
  },
  'unbound parent timestamp': (x) => {
    x.after.extra.appDirectoryStat['./files'][7] = '1700100003';
  },
  'outside actual finite window': (x) => {
    x.after.inventory.app.stat[PROFILE_CACHE_PATH][7] = '1700100032';
    x.after.inventory.app.stat[PROFILE_CACHE_PATH][8] = '1700100032';
    x.after.extra.appDirectoryStat['./files'][7] = '1700100032';
    x.after.extra.appDirectoryStat['./files'][8] = '1700100032';
  },
};
for (const [name, mutate] of Object.entries(cases)) {
  test(`cache classifier holds ${name}`, () => {
    const value = sample();
    assert.doesNotThrow(() => classifyProfileCacheRefresh(value));
    mutate(value);
    assert.throws(() => classifyProfileCacheRefresh(value));
  });
}

test('public package decoder rejects incomplete and unrecognized row layouts', () => {
  const x = sample();
  assert.throws(() => readCurrentPackageCheckin('pkg,com.example.acceptance,12000,1,', x.identity));
  assert.throws(() => readCurrentPackageCheckin(x.packageCheckin + '\0', x.identity));
  assert.throws(() =>
    readCurrentPackageCheckin(
      x.packageCheckin.replace('1700100000456', '9007199254740992'),
      x.identity,
    ),
  );
});
