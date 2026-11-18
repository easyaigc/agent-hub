import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { basename, join, resolve } from 'node:path';

const root = resolve(process.env.UNREAD_BADGE_OUTPUT ?? '.local/unread-badge-dev-20261011');
await mkdir(root, { recursive: true, mode: 0o700 });
const directory = await mkdtemp(join(root, 'ledger-'));
try {
  const env = {
    ...process.env,
    DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer',
    CODEX_NOTIFICATION_TEST_DIRECTORY: directory,
  };
  const executable = join(directory, 'ledger-smoke');
  execFileSync(
    'xcrun',
    [
      'swiftc',
      '-D',
      'DEBUG',
      'apps/ios/Sources/ThreadReading.swift',
      'apps/ios/Sources/SharedReadingPolicy.swift',
      'apps/ios/Sources/Localization.swift',
      'apps/ios/Sources/NotificationModels.swift',
      'apps/ios/Sources/MobileUnreadBadge.swift',
      'scripts/mobile-unread-badge-native-smoke.swift',
      '-o',
      executable,
    ],
    { env, stdio: 'inherit' },
  );
  console.log(execFileSync(executable, { env, encoding: 'utf8' }).trim());
  assert.equal(
    Number(execFileSync(executable, ['count'], { env, encoding: 'utf8' })),
    2,
    'Device-owned count must survive another process',
  );
  const target = {
    agentId: 'codex',
    hostId: 'host-a',
    workspaceId: 'space',
    runtimeId: 'desktop-runtime',
    account: { authority: 'local', subject: 'account-a' },
  };
  await Promise.all(
    Array.from(
      { length: 24 },
      (_, index) =>
        new Promise((done, reject) => {
          const child = spawn(executable, ['receive'], { env, stdio: ['pipe', 'ignore', 'pipe'] });
          child.once('error', reject);
          child.once('exit', (code) =>
            code === 0 ? done() : reject(Error('Concurrent receiver failed: ' + code)),
          );
          child.stdin.end(
            JSON.stringify({
              version: 1,
              id: createHash('sha256').update(String(index)).digest('hex'),
              target,
              readTarget: target,
              kind: 'approval',
              occurredAt: '2026-10-11T00:00:00Z',
              threadId: 'concurrent-' + index,
              requestId: 'request-' + index,
            }),
          );
        }),
    ),
  );
  assert.equal(Number(execFileSync(executable, ['count'], { env, encoding: 'utf8' })), 26);
  execFileSync(executable, ['locked-read'], { env, timeout: 2000, stdio: 'inherit' });
  execFileSync(executable, ['locked-visible'], { env, timeout: 15000, stdio: 'inherit' });
  execFileSync(executable, ['cache-invalidation'], { env, timeout: 15000, stdio: 'inherit' });
  const clearDirectory = join(directory, 'clear-scope');
  await mkdir(clearDirectory, { mode: 0o700 });
  execFileSync(executable, ['clear-scope'], {
    env: { ...env, CODEX_NOTIFICATION_TEST_DIRECTORY: clearDirectory },
    timeout: 15000,
    stdio: 'inherit',
  });
  const report = join(root, basename(directory) + '-report.json');
  await writeFile(
    report,
    JSON.stringify(
      {
        status: 'passed',
        scenarios: [
          'device-ledger',
          'cross-process-restart',
          '24-concurrent-extension-writes',
          'visible-read-lock-retry',
          '100-coalesced-visible-reads',
          'corrupt-ledger-preserved',
          'warm-cache-cross-process-replacement-same-generation-and-mtime',
          'clear-scope-isolation-and-legacy',
          'clear-restart-refresh-dedupe-and-new-events',
          'clear-nonblocking-and-corrupt-ledger-preserved',
        ],
        count: 26,
      },
      null,
      2,
    ),
    { mode: 0o600 },
  );
  console.log('Unread smoke report: ' + report);
  console.log('App/extension concurrent writes and process restart passed');
} finally {
  await rm(directory, { recursive: true, force: true });
}
