import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

// Synthetic native evidence. Never reads a user's App Group or conversations.
const root = resolve(process.env.SIDEBAR_PERFORMANCE_OUTPUT ?? '.local/sidebar-performance');
await mkdir(root, { recursive: true, mode: 0o700 });
const directory = await mkdtemp(join(root, 'run-'));
const executable = join(directory, 'sidebar-performance');
const sources = [
  'apps/ios/Sources/ThreadReading.swift',
  'apps/ios/Sources/Localization.swift',
  'apps/ios/Sources/NotificationModels.swift',
  'apps/ios/Sources/MobileUnreadBadge.swift',
  'scripts/sidebar-performance-native-smoke.swift',
];
const env = { ...process.env, CODEX_NOTIFICATION_TEST_DIRECTORY: join(directory, 'ledger') };
execFileSync('xcrun', ['swiftc', '-O', '-D', 'DEBUG', ...sources, '-o', executable], {
  env,
  stdio: 'inherit',
});
const measurements = JSON.parse(execFileSync(executable, { env, encoding: 'utf8' }));
assert(measurements.every((sample) => sample.rowLookupsRequireFile === false));
const report = {
  status: 'passed',
  evidence: 'macOS-native-synthetic-ledger',
  sourceSha256: Object.fromEntries(
    await Promise.all(
      sources.map(async (file) => [
        file,
        createHash('sha256')
          .update(await readFile(file))
          .digest('hex'),
      ]),
    ),
  ),
  measurements,
};
await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2) + '\n', {
  mode: 0o600,
});
console.log(JSON.stringify({ ...report, report: join(directory, 'report.json') }, null, 2));
