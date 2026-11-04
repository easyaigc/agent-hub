import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import {
  chmodSync,
  existsSync,
  linkSync,
  lstatSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  realpathSync,
  rmSync,
  symlinkSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { DesktopNativeSampleDiagnostics } from '../packages/codex-adapter/dist/desktop-native-sample-diagnostics.js';

function fixture(t) {
  const root = realpathSync(mkdtempSync(join(tmpdir(), 'native-diagnostics-test-')));
  if (process.platform !== 'win32') chmodSync(root, 0o700);
  t.after(() => {
    rmSync(root, { recursive: true });
    assert.equal(existsSync(root), false);
  });
  return { root, diagnostics: new DesktopNativeSampleDiagnostics(root) };
}
function rows(path) {
  return readFileSync(path, 'utf8').trim().split('\n').map(JSON.parse);
}

test('persists only fixed, validated fields for supported events in a private journal', (t) => {
  const { root, diagnostics: d } = fixture(t);
  assert.equal(d.file, join(root, 'native-diagnostics', 'producer.jsonl'));
  const id = randomUUID(),
    secret = 'PRIVATE prompt /Users/example token';
  for (const event of ['starting', 'listening', 'notice', 'stopped', 'closed', 'capture']) {
    assert.doesNotThrow(() =>
      d.write(event, {
        pid: 123,
        sourceThreadId: id,
        requestId: `thread/sample:${id}`,
        stage: 'tick',
        state: 'pending',
        code: 'native_bridge_log_truncated',
        prompt: secret,
        body: secret,
        path: secret,
        error: secret,
        desktopPid: -1,
        sourceTurnId: secret,
        osCode: secret,
      }),
    );
  }
  d.write('unrecognized', { body: secret });
  const content = readFileSync(d.file, 'utf8'),
    records = rows(d.file);
  assert.equal(records.length, 6);
  assert.ok(!content.includes(secret));
  assert.deepEqual(
    records.map((row) => row.event),
    ['starting', 'listening', 'notice', 'stopped', 'closed', 'capture'],
  );
  for (const row of records) {
    assert.deepEqual(
      Object.keys(row).sort(),
      [
        'version',
        'at',
        'event',
        'pid',
        'sourceThreadId',
        'requestId',
        'stage',
        'state',
        'code',
      ].sort(),
    );
    assert.equal(row.version, 1);
    assert.ok(Number.isFinite(Date.parse(row.at)));
    assert.equal(row.sourceThreadId, id);
  }
  d.write('capture', {
    stage: { toString: () => 'tick', secret },
    state: { toString: () => 'pending', secret },
  });
  const last = rows(d.file).at(-1);
  assert.equal(last.event, 'capture');
  assert.equal(Object.hasOwn(last, 'stage'), false);
  assert.equal(Object.hasOwn(last, 'state'), false);
  assert.ok(!readFileSync(d.file, 'utf8').includes(secret));
  if (process.platform !== 'win32') {
    assert.equal(lstatSync(join(root, 'native-diagnostics')).mode & 0o777, 0o700);
    assert.equal(lstatSync(d.file).mode & 0o777, 0o600);
  }
});

test('rotates at 128 KiB and retains only current plus two older files', (t) => {
  const { diagnostics: d } = fixture(t);
  d.write('starting');
  for (let i = 1; i <= 3; i++) {
    writeFileSync(d.file, String(i).repeat(128 * 1024));
    d.write('notice', { pid: i });
    assert.equal(rows(d.file)[0].pid, i);
    assert.equal(readFileSync(d.file + '.1', 'utf8'), String(i).repeat(128 * 1024));
    if (i > 1) assert.equal(readFileSync(d.file + '.2', 'utf8'), String(i - 1).repeat(128 * 1024));
  }
  assert.deepEqual(readdirSync(join(d.file, '..')).sort(), [
    'producer.jsonl',
    'producer.jsonl.1',
    'producer.jsonl.2',
  ]);
});

for (const kind of ['symbolic', 'hard']) {
  for (const suffix of ['', '.1', '.2']) {
    test(`refuses ${kind} link at journal${suffix} without modifying its target`, (t) => {
      const { root, diagnostics: d } = fixture(t);
      d.write('starting');
      const target = join(root, 'outside');
      writeFileSync(target, 'protected target', { mode: 0o600 });
      if (process.platform !== 'win32') chmodSync(target, 0o600);
      const alias = d.file + suffix;
      if (!suffix) rmSync(alias);
      try {
        if (kind === 'symbolic') symlinkSync(target, alias, 'file');
        else linkSync(target, alias);
      } catch (error) {
        if (process.platform === 'win32' && error.code === 'EPERM') {
          t.skip('link creation unavailable');
          return;
        }
        throw error;
      }
      const prior = suffix ? readFileSync(d.file) : null;
      assert.doesNotThrow(() => d.write('notice', { pid: 1 }));
      assert.equal(readFileSync(target, 'utf8'), 'protected target');
      if (prior) assert.deepEqual(readFileSync(d.file), prior);
      assert.equal(d.failed, true);
    });
  }
}
