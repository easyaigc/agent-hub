import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';

assert.equal(process.platform, 'darwin', 'Native persistence smoke requires macOS and Xcode');
const directory = resolve('.local/session-persistence/' + Date.now());
await mkdir(directory, { recursive: true, mode: 0o700 });
const source = 'apps/ios/Sources/SessionPersistence.swift';
const binary = join(directory, 'persistence-smoke');
execFileSync(
  'xcrun',
  ['swiftc', '-parse-as-library', source, 'scripts/session-persistence-smoke.swift', '-o', binary],
  { stdio: 'inherit' },
);
const output = execFileSync(binary, [directory], { encoding: 'utf8' });
const result = JSON.parse(output);
result.sourceSha256 = createHash('sha256')
  .update(await readFile(source))
  .digest('hex');
await writeFile(join(directory, 'result.json'), JSON.stringify(result, null, 2), { mode: 0o600 });
console.log(JSON.stringify(result));
