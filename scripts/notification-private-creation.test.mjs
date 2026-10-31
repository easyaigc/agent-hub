import assert from 'node:assert/strict';
import { test } from 'node:test';
import {
  mkdtempSync,
  mkdirSync,
  openSync,
  readFileSync,
  writeFileSync,
  existsSync,
  rmSync,
  statSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  NotificationPublisher,
  writeNotificationState,
} from '../packages/notification-runtime/dist/index.js';

const target = {
  agentId: 'codex',
  hostId: 'fixture',
  workspaceId: 'fixture',
  runtimeId: 'desktop-runtime',
};
test('notification constructor reserves lock and snapshots privately; old metadata is never adopted', () => {
  const root = mkdtempSync(join(tmpdir(), 'notification-private-'));
  const statePath = join(root, 'state', 'notifications.json');
  const nativeCreated = new Set(),
    calls = [];
  const options = {
    target,
    statePath,
    makePrivateDirectory(path) {
      mkdirSync(path, { recursive: true });
    },
    createPrivateFile(path) {
      const fd = openSync(path, 'wx', 0o600);
      nativeCreated.add(path);
      calls.push(['create', path]);
      return fd;
    },
    protectFile(path) {
      assert(nativeCreated.has(path), 'protect must not adopt a normal default-owner file');
      calls.push(['protect', path]);
    },
  };
  let publisher;
  try {
    publisher = new NotificationPublisher(options);
    assert(calls.some(([op, path]) => op === 'create' && path === statePath + '.lock'));
    assert(calls.some(([op, path]) => op === 'create' && path.endsWith('.tmp')));
    const original = readFileSync(statePath);
    publisher.close();
    publisher = undefined;
    // close() performs its normal atomic save. The legacy-startup assertion
    // compares the stable on-disk object after that completed publication.
    const stat = statSync(statePath, { bigint: true });
    assert(!existsSync(statePath + '.lock'));
    nativeCreated.delete(statePath); // Existing legacy snapshot need not have been created by this hook.
    publisher = new NotificationPublisher(options);
    assert.deepEqual(readFileSync(statePath), original);
    assert.equal(statSync(statePath, { bigint: true }).ctimeNs, stat.ctimeNs);
    assert(!calls.some(([op, path]) => op === 'protect' && path === statePath));
  } finally {
    publisher?.close();
    rmSync(root, { recursive: true });
  }
});

test('native reservation failure preserves existing state without fallback creation or publish', () => {
  const root = mkdtempSync(join(tmpdir(), 'notification-refusal-'));
  try {
    const path = join(root, 'state.json');
    writeFileSync(path, 'original');
    const failure = Object.assign(new Error('fixed refusal'), {
      code: 'UNSAFE_WINDOWS_PRIVATE_STATE',
    });
    let protections = 0;
    assert.throws(
      () =>
        writeNotificationState(
          path,
          { replacement: true },
          () => protections++,
          () => {
            throw failure;
          },
        ),
      (e) => e === failure,
    );
    assert.equal(readFileSync(path, 'utf8'), 'original');
    assert.equal(protections, 0);
  } finally {
    rmSync(root, { recursive: true });
  }
});
