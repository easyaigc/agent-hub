import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

// Isolated synthetic keys only; this does not read phone data or contact a Host.
const root = resolve(process.env.READING_INDEX_OUTPUT ?? '.local/reading-index-performance');
await mkdir(root, { recursive: true, mode: 0o700 });
const directory = await mkdtemp(join(root, 'run-'));
const executable = join(directory, 'reading-index');
const sources = [
  'apps/ios/Sources/ThreadReading.swift',
  'apps/ios/Sources/Localization.swift',
  'apps/ios/Sources/NotificationModels.swift',
  'apps/ios/Sources/MobileUnreadBadge.swift',
  'scripts/reading-index-native-smoke.swift',
];
const env = { ...process.env, CODEX_NOTIFICATION_TEST_DIRECTORY: join(directory, 'ledger') };
execFileSync('xcrun', ['swiftc', '-O', '-D', 'DEBUG', ...sources, '-o', executable], {
  env,
  stdio: 'inherit',
});
const report = JSON.parse(execFileSync(executable, { env, encoding: 'utf8' }));
report.sourceSha256 = Object.fromEntries(
  await Promise.all(
    sources.map(async (file) => [
      file,
      createHash('sha256')
        .update(await readFile(file))
        .digest('hex'),
    ]),
  ),
);
await writeFile(join(directory, 'report.json'), JSON.stringify(report, null, 2) + '\n', {
  mode: 0o600,
});
console.log(JSON.stringify({ ...report, report: join(directory, 'report.json') }, null, 2));
