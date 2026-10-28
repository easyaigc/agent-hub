import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

assert(process.argv[2]);
assert(process.argv.length === 3 || (process.argv.length === 5 && process.argv[3] === '--sources'));
const localSources = process.argv[3] === '--sources' ? resolve(process.argv[4]) : null;
const folder = resolve(process.argv[2]);
await mkdir(folder, { mode: 0o700 });
const lock = JSON.parse(await readFile(new URL('./aosp-source-lock.json', import.meta.url)));
const sources = [];
for (const source of lock.sources) {
  let bytes;
  if (localSources) bytes = await readFile(join(localSources, source.name + '.java'));
  else {
    const response = await fetch(source.url, { signal: AbortSignal.timeout(25000) });
    assert(response.ok);
    bytes = Buffer.from(await response.text(), 'base64');
  }
  assert.equal(createHash('sha256').update(bytes).digest('hex'), source.sha256);
  const name = join(folder, source.name + '.java');
  await writeFile(name, bytes, { mode: 0o600, flag: 'wx' });
  sources.push(name);
}
// Only compile-time annotations are supplied. The six AOSP implementation files stay byte exact.
const annotations = {
  'android/compat/annotation/UnsupportedAppUsage.java':
    'package android.compat.annotation; public @interface UnsupportedAppUsage {}',
  'android/annotation/SystemApi.java':
    'package android.annotation; public @interface SystemApi { public enum Client { MODULE_LIBRARIES }; Client client(); }',
  'libcore/util/NonNull.java':
    'package libcore.util; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE,java.lang.annotation.ElementType.METHOD,java.lang.annotation.ElementType.PARAMETER,java.lang.annotation.ElementType.FIELD}) public @interface NonNull {}',
  'libcore/util/Nullable.java':
    'package libcore.util; @java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE,java.lang.annotation.ElementType.METHOD,java.lang.annotation.ElementType.PARAMETER,java.lang.annotation.ElementType.FIELD}) public @interface Nullable {}',
};
for (const [path, text] of Object.entries(annotations)) {
  const name = join(folder, path);
  await mkdir(dirname(name), { recursive: true, mode: 0o700 });
  await writeFile(name, text, { mode: 0o600, flag: 'wx' });
  sources.push(name);
}
const tool = (name) => (process.env.JAVA_HOME ? join(process.env.JAVA_HOME, 'bin', name) : name);
const classes = join(folder, 'classes');
await mkdir(classes, { mode: 0o700 });
for (const [name, args] of [
  ['javac', ['-encoding', 'UTF-8', '-d', classes, ...sources]],
  ['jar', ['--create', '--file', join(folder, 'aosp-json.jar'), '-C', classes, '.']],
]) {
  const result = spawnSync(tool(name), args, { encoding: 'utf8' });
  process.stdout.write(result.stdout ?? '');
  process.stderr.write(result.stderr ?? '');
  assert.equal(result.status, 0);
}
await writeFile(join(folder, 'inputs.json'), JSON.stringify(lock, null, 2) + '\n', {
  mode: 0o600,
  flag: 'wx',
});
console.log(
  JSON.stringify({
    commit: lock.commit,
    sourceFiles: 6,
    logicEdits: 0,
    jar: join(folder, 'aosp-json.jar'),
  }),
);
