import { execFileSync } from 'node:child_process';
import { mkdirSync, mkdtempSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

// Executes the production Swift reader and identity types; no HTTP, desktop or model.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..');
const checks = join(root, '.local/desktop-suggestion-native');
mkdirSync(checks, { recursive: true, mode: 0o700 });
const output = mkdtempSync(join(checks, 'run-'));
const check = process.argv[2] ?? 'reader';
const sources = {
  reader: 'DesktopSuggestionReadSmoke.swift',
  'source-validity': 'DesktopSuggestionSourceValiditySmoke.swift',
  display: 'DesktopSuggestionDisplaySmoke.swift',
  'native-version': 'DesktopSuggestionDisplaySmoke.swift',
  'runtime-version': 'DesktopSuggestionDisplaySmoke.swift',
  'read-ahead': 'DesktopSuggestionReadAheadSmoke.swift',
  'shared-vectors': 'DesktopSuggestionSharedVectors.swift',
};
if (check !== 'all' && !Object.hasOwn(sources, check)) throw Error('Unknown suggestion check');
if (process.platform !== 'darwin') {
  console.log('iOS suggestion Swift checks skipped: macOS and Xcode are required.');
  process.exit(0);
}
const env = {
  ...process.env,
  DEVELOPER_DIR: process.env.DEVELOPER_DIR || '/Applications/Xcode.app/Contents/Developer',
};
const nativeTarget = process.arch === 'arm64' ? 'MacosArm64' : 'MacosX64';
execFileSync(
  process.execPath,
  ['scripts/mobile/gradle.mjs', ':mobile-core:linkDebugFramework' + nativeTarget],
  {
    cwd: root,
    env,
    stdio: 'inherit',
  },
);
const framework = join(
  root,
  '.build/native/mobile-core/bin',
  process.arch === 'arm64' ? 'macosArm64' : 'macosX64',
  'debugFramework',
);
for (const name of check === 'all' ? Object.keys(sources) : [check]) {
  const executable = join(output, 'desktop-suggestions-' + name);
  execFileSync(
    'xcrun',
    [
      'swiftc',
      '-parse-as-library',
      '-swift-version',
      '5',
      '-strict-concurrency=complete',
      '-warnings-as-errors',
      '-module-cache-path',
      join(output, 'module-cache'),
      '-F',
      framework,
      '-framework',
      'MobileCore',
      'apps/ios/Sources/Localization.swift',
      'apps/ios/Sources/ThreadReading.swift',
      'apps/ios/Sources/NotificationModels.swift',
      'apps/ios/Sources/DesktopSuggestions.swift',
      'apps/ios/Tests/' + sources[name],
      '-o',
      executable,
    ],
    { cwd: root, env, stdio: 'inherit' },
  );
  execFileSync(
    executable,
    [name, join(root, 'packages/protocol/fixtures/mobile/desktop-suggestions-v1.json')],
    { env, stdio: 'inherit' },
  );
}
