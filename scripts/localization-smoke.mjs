import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { cp, mkdir, mkdtemp, readFile, readdir } from 'node:fs/promises';
import { join, resolve } from 'node:path';

const resources = resolve('apps/ios/Resources');
const tables = {};
const env = {
  ...process.env,
  DEVELOPER_DIR: process.env.DEVELOPER_DIR || '/Applications/Xcode.app/Contents/Developer',
};
for (const language of ['en', 'zh-Hans']) {
  const file = join(resources, language + '.lproj', 'Localizable.strings');
  tables[language] = JSON.parse(
    execFileSync('plutil', ['-convert', 'json', '-o', '-', file], { encoding: 'utf8' }),
  );
}
assert.deepEqual(Object.keys(tables.en).sort(), Object.keys(tables['zh-Hans']).sort());
const formats = (text) => [...text.matchAll(/%%|%(?:\d+\$)?@/g)].map((m) => m[0]).sort();
for (const [key, value] of Object.entries(tables.en)) {
  assert.ok(value.length > 0, 'Empty English translation: ' + key);
  assert.deepEqual(formats(value), formats(key), 'Interpolation mismatch: ' + key);
  assert.equal(tables['zh-Hans'][key], key, 'Chinese source mismatch: ' + key);
  assert.ok(!/[\u4e00-\u9fff]/u.test(value), 'Untranslated English entry: ' + key);
}
// Check literal call sites as well as table parity. Interpolated formats are
// exercised below using the actual Swift implementation and Foundation.
for (const directory of ['Sources', 'NotificationService', 'NotificationContent']) {
  const folder = resolve('apps/ios', directory);
  for (const filename of (await readdir(folder)).filter((name) => name.endsWith('.swift'))) {
    const source = await readFile(join(folder, filename), 'utf8');
    for (const match of source.matchAll(/L10n\.tr\(("(?:[^"\\]|\\.)*")\)/g)) {
      if (match[1].includes('\\(')) continue;
      const key = JSON.parse(match[1]).replaceAll('%', '%%');
      assert.ok(Object.hasOwn(tables.en, key), `Missing translation in ${filename}: ${key}`);
    }
  }
}
for (const language of ['en', 'zh-Hans']) {
  const descriptions = JSON.parse(
    execFileSync(
      'plutil',
      ['-convert', 'json', '-o', '-', join(resources, language + '.lproj', 'InfoPlist.strings')],
      { encoding: 'utf8' },
    ),
  );
  for (const permission of ['Camera', 'LocalNetwork', 'Microphone', 'SpeechRecognition'])
    assert.ok(descriptions['NS' + permission + 'UsageDescription']);
}
const project = await readFile('apps/ios/CodexManage.xcodeproj/project.pbxproj', 'utf8');
assert.ok(project.includes('Localizable.strings'));
await mkdir('.local/localization-checks', { recursive: true });
const output = await mkdtemp(resolve('.local/localization-checks/run-'));
for (const language of ['en', 'zh-Hans'])
  await cp(join(resources, language + '.lproj'), join(output, language + '.lproj'), {
    recursive: true,
  });
const executable = join(output, 'localization-smoke');
execFileSync(
  'xcrun',
  [
    'swiftc',
    '-parse-as-library',
    'apps/ios/Sources/Localization.swift',
    'apps/ios/Sources/OperationFailure.swift',
    'apps/ios/Sources/ThreadReading.swift',
    'apps/ios/Sources/NotificationModels.swift',
    'apps/ios/Sources/BusinessNotificationModels.swift',
    'scripts/localization-native-smoke.swift',
    '-o',
    executable,
  ],
  { env, stdio: 'inherit' },
);
console.log(execFileSync(executable, { env, encoding: 'utf8' }).trim());
console.log(
  `Verified ${Object.keys(tables.en).length} bilingual entries and four permission prompts.`,
);
