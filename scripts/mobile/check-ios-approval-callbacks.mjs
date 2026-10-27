import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, mkdtemp, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

// Production Swift dispatch/callback methods with synthetic, held transport completions.
// Does not constitute physical-device, desktop-owner or model acceptance.
if (process.platform !== 'darwin') {
  console.log('iOS approval callback Swift boundary skipped: Apple SDK required');
  process.exit(0);
}
const root = resolve(import.meta.dirname, '../..');
const parent = join(root, '.local/ios-approval-callbacks');
await mkdir(parent, { recursive: true, mode: 0o700 });
const output = await mkdtemp(join(parent, 'run-'));
const files = [
  'apps/ios/Sources/SessionStore.swift',
  'apps/ios/Sources/Models.swift',
  'apps/ios/Tests/ApprovalCallbacksSmoke.swift',
];
const [store, models, harness] = await Promise.all(
  files.map((file) => readFile(join(root, file), 'utf8')),
);
const start = store.indexOf('  // BEGIN: approval-callback-boundary');
const end = store.indexOf('  // END: approval-callback-boundary', start);
assert(start >= 0 && end > start, 'Missing production approval callback boundary');
const declarations = ['ApprovalQuestion', 'QuestionOption', 'Approval'].map((name) => {
  const found = models.match(new RegExp('(?:^|\\n)struct ' + name + ':[^\\n]*\\{[\\s\\S]*?\\n\\}'));
  assert(found, name);
  return found[0];
});
for (const marker of ['// PRODUCTION_APPROVAL_MODELS', '// PRODUCTION_APPROVAL_BOUNDARY'])
  assert.equal(harness.split(marker).length, 2, 'One production injection point required');
const source = harness
  .replace('// PRODUCTION_APPROVAL_MODELS', declarations.join('\n'))
  .replace('// PRODUCTION_APPROVAL_BOUNDARY', store.slice(start, end));
const file = join(output, 'verify.swift'),
  binary = join(output, 'verify');
await writeFile(file, source, { mode: 0o600 });
execFileSync('xcrun', ['swiftc', '-swift-version', '5', '-parse-as-library', file, '-o', binary], {
  cwd: root,
  stdio: 'inherit',
});
const log = execFileSync(binary, { encoding: 'utf8' });
assert.match(log, /PASS: 31 production approval dispatch\/callback boundaries/);
const sha = (value) => createHash('sha256').update(value).digest('hex');
await writeFile(
  join(output, 'report.json'),
  JSON.stringify(
    {
      status: 'passed',
      scope: 'production Swift methods, synthetic held transport',
      source: Object.fromEntries(files.map((file, i) => [file, sha([store, models, harness][i])])),
      generatedSwiftSha256: sha(source),
      checks: 31,
      newModelInputs: 0,
      physicalDevice: false,
    },
    null,
    2,
  ) + '\n',
  { mode: 0o600 },
);
process.stdout.write(log);
