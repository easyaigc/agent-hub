// Actual debug metadata policy, host JVM only. No Android/Binder/Host/device operation.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdirSync, readFileSync, readdirSync } from 'node:fs';
import { delimiter, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const root = process.cwd();
const cache = resolve(
  process.env.GRADLE_USER_HOME || '.local/gradle-home',
  'caches/modules-2/files-2.1',
);
function artifact(group, name, version) {
  const directory = resolve(cache, group, name, version);
  const filename = `${name}-${version}.jar`;
  const found = readdirSync(directory).flatMap((hash) =>
    readdirSync(resolve(directory, hash))
      .filter((file) => file === filename)
      .map((file) => resolve(directory, hash, file)),
  );
  assert.equal(found.length, 1, `Expected cached ${group}/${filename}`);
  return found[0];
}
const local = JSON.parse(readFileSync(resolve(root, '.local/mobile-toolchain.json'), 'utf8'));
const java = resolve(process.env.JAVA_HOME || local.javaHome, 'bin/java');
const stdlib = artifact('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.21');
const runtime = [stdlib].join(delimiter);
const compiler = [
  artifact('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.21'),
  artifact('org.jetbrains.kotlin', 'kotlin-build-tools-api', '2.4.21'),
  stdlib,
  artifact('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.21'),
  artifact('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
  artifact('org.jetbrains.kotlin', 'kotlin-daemon-embeddable', '2.4.21'),
  artifact('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
  artifact('org.jetbrains', 'annotations', '13.0'),
].join(delimiter);
const sources = [
  'scripts/mobile/fixtures/native/coldboot/NativeBootstrapCiphertextProof.kt',
  'scripts/mobile/fixtures/NaturalColdBootCipherVectors.kt',
].map((path) => resolve(root, path));
const output = resolve(root, '.build/p9-coldboot-cipher');
mkdirSync(output, { recursive: true });
function run(args) {
  const result = spawnSync(java, args, { stdio: 'inherit', timeout: 120_000 });
  assert.equal(result.status, 0, 'Cold-boot ciphertext auxiliary failed');
}
run([
  '-cp',
  compiler,
  'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
  '-no-stdlib',
  '-no-reflect',
  '-jvm-target',
  '17',
  '-classpath',
  runtime,
  '-d',
  output,
  ...sources,
]);
run([
  '-cp',
  [output, runtime].join(delimiter),
  'com.agenthub.android.NaturalColdBootCipherVectorsKt',
]);
console.log(
  JSON.stringify({
    scope: 'Actual fixed bootstrap v3 authenticator on JVM only; no AndroidKeyStore/native proof',
    sourceSha256: Object.fromEntries(
      sources.map((path) => [path, createHash('sha256').update(readFileSync(path)).digest('hex')]),
    ),
  }),
);
