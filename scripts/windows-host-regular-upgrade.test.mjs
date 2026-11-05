import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, readFileSync, rmSync, truncateSync } from 'node:fs';
import { join, resolve } from 'node:path';
import {
  makePrivateDirectorySync,
  writePrivateFileSync,
  closeWindowsPrivateStateBackend,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import {
  assertUpgradeRuntimeIdentity,
  publishUpgradeRuntime,
  replaceUpgradeFile,
} from './windows-host-regular-upgrade.mjs';
const digest = (b) => createHash('sha256').update(b).digest('hex');

test('large runtime state preserves identity checks and does not widen configuration budgets', () => {
  const base = resolve('.local/upgrade-state-fixture-' + randomUUID());
  const file = join(base, 'runtime.json');
  const hostId = randomUUID();
  try {
    makePrivateDirectorySync(base);
    const bytes = JSON.stringify({ hostId, cache: 'x'.repeat(3 * 1024 * 1024) });
    writePrivateFileSync(file, bytes, { flag: 'wx' });
    assertUpgradeRuntimeIdentity(file, hostId);
    assert.throws(
      () => assertUpgradeRuntimeIdentity(file, randomUUID()),
      /HOST_UPGRADE_HOST_CHANGED/,
    );
    assert.throws(
      () =>
        replaceUpgradeFile(
          { target: file, bytes: '{}', expected: digest(bytes), directory: base, name: 'large' },
          () => assert.fail('oversized configuration must not be replaced'),
        ),
      /HOST_UPGRADE_FILE_REJECTED/,
    );
    assert.equal(existsSync(join(base, 'large.prepared')), false);
    truncateSync(file, 64 * 1024 * 1024 + 1);
    assert.throws(() => assertUpgradeRuntimeIdentity(file, hostId), /HOST_UPGRADE_FILE_REJECTED/);
  } finally {
    closeWindowsPrivateStateBackend();
    rmSync(base, { recursive: true });
  }
});

test('publishes a closed private runtime, reuses verified bytes and rejects a changed artifact', () => {
  const base = resolve('.local/upgrade-fixture-' + randomUUID());
  const source = join(base, 'source'),
    destination = join(base, 'program'),
    staging = join(base, 'staging');
  try {
    makePrivateDirectorySync(source);
    const files = {};
    for (const name of [
      'bin/node.exe',
      'scripts/windows-desktop-worker.mjs',
      'apps/host-agent/dist/runtime-entry.js',
    ]) {
      const path = join(source, name),
        bytes = Buffer.from('fixture:' + name);
      makePrivateDirectorySync(resolve(path, '..'));
      writePrivateFileSync(path, bytes, { flag: 'wx' });
      files[name] = { bytes: bytes.length, sha256: digest(bytes) };
    }
    const manifest = Buffer.from(
      JSON.stringify({
        format: 'windows-host-regular-runtime-v1',
        sourceCommit: '1'.repeat(40),
        files,
      }),
    );
    writePrivateFileSync(join(source, 'runtime-manifest.json'), manifest, { flag: 'wx' });
    const sha = digest(manifest);
    publishUpgradeRuntime(source, sha, destination, staging);
    assert.equal(existsSync(staging), false);
    assert.deepEqual(readFileSync(join(destination, 'runtime-manifest.json')), manifest);
    assert.deepEqual(publishUpgradeRuntime(source, sha, destination, staging).files, files);
    writePrivateFileSync(join(source, 'bin/node.exe'), 'changed');
    assert.throws(
      () => publishUpgradeRuntime(source, sha, destination, staging),
      /HOST_RUNTIME_FILE_CHANGED/,
    );
    assert.equal(readFileSync(join(destination, 'bin/node.exe'), 'utf8'), 'fixture:bin/node.exe');
  } finally {
    closeWindowsPrivateStateBackend();
    rmSync(base, { recursive: true });
  }
});

test('replacement fences reject stale input before write and preserve actual backup on conflict', () => {
  const base = resolve('.local/upgrade-fixture-' + randomUUID());
  const target = join(base, 'target.json');
  try {
    makePrivateDirectorySync(base);
    writePrivateFileSync(target, 'old', { flag: 'wx' });
    assert.throws(
      () =>
        replaceUpgradeFile(
          { target, bytes: 'new', expected: digest('stale'), directory: base, name: 'stale' },
          () => assert.fail('must not invoke native replace'),
        ),
      /HOST_UPGRADE_CONCURRENT_CHANGE/,
    );
    assert.equal(existsSync(join(base, 'stale.prepared')), false);
    const conflict = (_script, args) => {
      const backup = args[args.indexOf('-Backup') + 1];
      writePrivateFileSync(backup, 'concurrent', { flag: 'wx' });
      writePrivateFileSync(target, 'new');
      return JSON.stringify({ replacedSha256: digest('concurrent') });
    };
    assert.throws(
      () =>
        replaceUpgradeFile(
          { target, bytes: 'new', expected: digest('old'), directory: base, name: 'race' },
          conflict,
        ),
      /HOST_UPGRADE_REPLACEMENT_UNCERTAIN/,
    );
    assert.equal(readFileSync(join(base, 'race.backup'), 'utf8'), 'concurrent');
  } finally {
    closeWindowsPrivateStateBackend();
    rmSync(base, { recursive: true });
  }
});
