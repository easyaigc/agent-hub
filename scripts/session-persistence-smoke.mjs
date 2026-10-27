import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmod, lstat, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';

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
// Permission-negative fixtures live outside the repository so concurrent
// format/file discovery cannot stumble into an intentionally unreadable tree.
const deniedDirectory = await mkdtemp(join(tmpdir(), 'agent-hub-persistence-denied-'));
const native = spawnSync(binary, [directory, deniedDirectory], { encoding: 'utf8' });
// A native assertion trap skips Swift defer. Restore only this invocation's
// intentionally unreadable fixture before checking the child result.
const fixture = join(deniedDirectory, 'History-v1');
try {
  const attributes = await lstat(fixture);
  if (attributes.isDirectory() && !attributes.isSymbolicLink()) await chmod(fixture, 0o700);
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
await rm(deniedDirectory, { recursive: true });
if (native.stderr) process.stderr.write(native.stderr);
if (native.error) throw native.error;
assert.equal(native.status, 0, `Native persistence check failed (${native.signal ?? 'exit'})`);
const result = JSON.parse(native.stdout);
result.sourceSha256 = createHash('sha256')
  .update(await readFile(source))
  .digest('hex');
await writeFile(join(directory, 'result.json'), JSON.stringify(result, null, 2), { mode: 0o600 });
console.log(JSON.stringify(result));
