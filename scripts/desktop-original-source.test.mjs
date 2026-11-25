import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseOriginalDesktopSource } from '../packages/codex-adapter/dist/desktop-original-source.js';

const endpoint = 'ws://127.0.0.1:57443';
const source = () => ({
  version: 1,
  kind: 'original-desktop-shared-service',
  url: endpoint,
  producer: { pid: 20, desktopPid: 10, desktopStartedAt: 1000, wrapperStartedAt: 2000 },
  serverPid: 30,
  serverStartedAt: 3000,
  manifestSha256: 'a'.repeat(64),
  ownerSha256: 'b'.repeat(64),
});

test('one exact utility may sit between the original main and wrapper', () => {
  const direct = source();
  assert.deepEqual(parseOriginalDesktopSource(direct, endpoint), direct);
  const utility = source();
  Object.assign(utility.producer, { logPid: 15, logStartedAt: 1500 });
  assert.deepEqual(parseOriginalDesktopSource(utility, endpoint), utility);
});

test('no other endpoint, credentials, URL metadata or redirected source may gain trust', () => {
  for (const url of [
    'ws://localhost:57443',
    'ws://192.168.1.1:57443',
    'wss://127.0.0.1:57443',
    'ws://127.0.0.1:57443/',
    'ws://127.0.0.1:57443?x=1',
    'ws://secret@127.0.0.1:57443',
    'ws://127.0.0.1:57443/#x',
  ])
    assert.throws(() => parseOriginalDesktopSource({ ...source(), url }, url));
  assert.throws(() =>
    parseOriginalDesktopSource({ ...source(), url: 'ws://127.0.0.1:57444' }, endpoint),
  );
});

test('PID reuse, impossible lineage, missing utility pair or unknown process roles revoke trust', () => {
  for (const change of [
    { serverPid: 20 },
    { serverPid: 0 },
    { serverPid: 2 ** 40 },
    { serverStartedAt: 1999 },
    { serverStartedAt: Date.now() + 60_000 },
    { producer: { ...source().producer, desktopPid: 20 } },
    { producer: { ...source().producer, logPid: 15 } },
    { producer: { ...source().producer, logStartedAt: 1500 } },
    { producer: { ...source().producer, logPid: 20, logStartedAt: 1500 } },
    { producer: { ...source().producer, logPid: 15, logStartedAt: 999 } },
    { producer: { ...source().producer, logPid: 15, logStartedAt: 2001 } },
    { producer: { ...source().producer, ownerClientId: 'invented' } },
  ])
    assert.throws(() => parseOriginalDesktopSource({ ...source(), ...change }, endpoint));
});

test('unrecognized or incomplete provenance cannot turn a stored read into a live source', () => {
  for (const change of [
    { version: 2 },
    { kind: 'history-reader' },
    { ownerSha256: 'x'.repeat(64) },
    { manifestSha256: undefined },
    { trusted: true },
    { turnId: 'unverified' },
  ])
    assert.throws(() => parseOriginalDesktopSource({ ...source(), ...change }, endpoint));
});
