import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { mkdir, mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';

// Native Foundation boundary checks with a transport double; no phone, model or Codex execution.
if (process.platform !== 'darwin') {
  console.log('EasyWork reading native regression requires macOS Swift.');
  process.exit(0);
}
const root = resolve(
  process.env.EASYWORK_READING_CHECK_OUTPUT ?? '.local/easywork-reading-boundary',
);
await mkdir(root, { recursive: true, mode: 0o700 });
const output = await mkdtemp(join(root, 'run-'));
const executable = join(output, 'reading-boundary');
const sources = [
  'apps/ios/Sources/ThreadReading.swift',
  'apps/ios/Sources/SharedReadingPolicy.swift',
  'apps/ios/Sources/Localization.swift',
  'apps/ios/Sources/NotificationModels.swift',
  'apps/ios/Sources/EasyWorkReading.swift',
  'scripts/mobile/easywork-reading-native-smoke.swift',
];
// Compile the exact provider boundary. Its separately compiled iOS DTO bridge
// needs MobileCore and belongs to the application build, outside this fixture.
const production = await readFile('apps/ios/Sources/EasyWorkReading.swift', 'utf8');
const bridge = production.indexOf('// The reading adapter owns its platform DTO bridge;');
assert(bridge > production.indexOf('enum EasyWorkReading {'));
const boundary = join(output, 'EasyWorkReading.swift');
await writeFile(boundary, production.slice(0, bridge).replace(/^import MobileCore\n/m, ''), {
  mode: 0o600,
});
try {
  execFileSync(
    'xcrun',
    [
      'swiftc',
      '-parse-as-library',
      '-D',
      'DEBUG',
      ...sources.map((file) =>
        file === 'apps/ios/Sources/EasyWorkReading.swift' ? boundary : file,
      ),
      '-o',
      executable,
    ],
    {
      stdio: 'inherit',
    },
  );
  const result = execFileSync(executable, { encoding: 'utf8' });
  await writeFile(join(output, 'native.log'), result, { mode: 0o600 });
  const sourceSha256 = Object.fromEntries(
    await Promise.all(
      sources.map(async (file) => [
        file,
        createHash('sha256')
          .update(await readFile(file))
          .digest('hex'),
      ]),
    ),
  );
  await writeFile(
    join(output, 'summary.json'),
    JSON.stringify(
      {
        status: 'passed-auxiliary-foundation',
        scope: [
          'strict-target-and-counter',
          'ordered-bounded-list',
          'explicit-empty-head',
          'lost-acknowledgement-read-only-reconciliation',
          'stale-version-no-write',
          'rendered-link-projection-and-late-edit',
        ],
        sourceSha256,
        transport: 'test-double',
        physicalIPhone: false,
        codexTests: false,
        fullFeaturePassed: false,
      },
      null,
      2,
    ) + '\n',
    { mode: 0o600 },
  );
  console.log(result.trim());
  console.log('Evidence:', join(output, 'summary.json'));
} finally {
  await rm(boundary, { force: true });
  await rm(executable, { force: true });
}
