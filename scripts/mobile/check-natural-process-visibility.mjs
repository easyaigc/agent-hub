// Exact parsers and fixed-fake-command host POSIX pipes; no Android, real diagnostic or device.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = process.cwd();
const local = JSON.parse(readFileSync(resolve(root, '.local/mobile-toolchain.json'), 'utf8'));
const java = resolve(process.env.JAVA_HOME || local.javaHome, 'bin/java');
const javac = resolve(process.env.JAVA_HOME || local.javaHome, 'bin/javac');
const output = resolve(root, '.build/p9-natural-visibility');
mkdirSync(output, { recursive: true });
const sources = [
  'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NaturalProcessVisibility.java',
  'scripts/mobile/fixtures/NaturalVisibilityVectors.java',
].map((path) => resolve(root, path));
function run(command, args) {
  const result = spawnSync(command, args, { stdio: 'inherit', timeout: 120_000 });
  assert.equal(result.status, 0, 'Visibility parser auxiliary failed');
}
run(javac, ['--release', '17', '-d', output, ...sources]);
console.log(
  JSON.stringify({
    scope: 'Synthetic parser/fixed-fake-command host pipe vectors; no native visibility proof',
    sourceSha256: Object.fromEntries(
      sources.map((path) => [path, createHash('sha256').update(readFileSync(path)).digest('hex')]),
    ),
  }),
);
run(java, ['-cp', output, 'com.agenthub.android.NaturalVisibilityVectors']);
