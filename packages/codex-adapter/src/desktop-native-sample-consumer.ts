import { createHash } from 'node:crypto';
import {
  closeSync,
  constants,
  fstatSync,
  lstatSync,
  openSync,
  readFileSync,
  readdirSync,
  realpathSync,
} from 'node:fs';
import { basename, dirname, isAbsolute, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { machineRuntimeTargetKey } from '@codex-manage/protocol';

// These modules are consumers only, outside the unchanged native launch/capture
// graph. Unknown modules and every producer dependency must still match exactly.
const consumerOnly = new Set([
  'desktop-ipc.js',
  'desktop-original-session.js',
  'desktop-prepared-bridge.js',
  'desktop-thread-preparer.js',
  'desktop-native-suggestion-channel.js',
  'desktop-native-sample-consumer.js',
  'desktop-shared-control.js',
  'desktop-title.js',
  'history-reader.js',
  'runtime-persistence.js',
  'runtime-session.js',
]);
const hash = (value: Buffer | string) => createHash('sha256').update(value).digest('hex');
const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

function ownedBytes(path: string, maximum: number, privateFile = false): Buffer {
  if (realpathSync(path) !== path) throw new Error('Native installation alias');
  const fd = openSync(path, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    const before = fstatSync(fd, { bigint: true });
    if (
      !before.isFile() ||
      before.uid !== BigInt(process.getuid!()) ||
      before.mode & BigInt(privateFile ? 0o077 : 0o022) ||
      before.size > BigInt(maximum)
    )
      throw new Error('Owned bounded native input required');
    const bytes = readFileSync(fd);
    const after = fstatSync(fd, { bigint: true }),
      current = lstatSync(path, { bigint: true });
    for (const field of ['dev', 'ino', 'uid', 'mode', 'size', 'mtimeNs', 'ctimeNs'] as const)
      if (before[field] !== after[field] || before[field] !== current[field])
        throw new Error('Native input changed');
    return bytes;
  } finally {
    closeSync(fd);
  }
}

function inventory(program: string, directory: string): Record<string, string> {
  for (const path of [program, directory]) {
    const stat = lstatSync(path);
    if (
      !stat.isDirectory() ||
      stat.isSymbolicLink() ||
      stat.uid !== process.getuid!() ||
      stat.mode & 0o022 ||
      realpathSync(path) !== path
    )
      throw new Error('Owned native installation required');
  }
  const names = readdirSync(directory)
    .filter((name) => name.endsWith('.js'))
    .sort();
  if (!names.length || names.length > 256) throw new Error('Bounded native installation required');
  const files: Record<string, string> = {};
  let total = 0;
  const read = (name: string, path: string) => {
    const bytes = ownedBytes(path, 2 * 1024 * 1024);
    total += bytes.length;
    if (total > 16 * 1024 * 1024) throw new Error('Native installation too large');
    files[name] = hash(bytes);
  };
  for (const name of names) read(name, join(directory, name));
  for (const name of ['desktop-native-suggestions-runtime.mjs', 'desktop-native-trace-policy.mjs'])
    read('scripts/' + name, join(program, 'scripts', name));
  read(
    'resources/desktop-completion-notify-observer.py',
    join(dirname(directory), 'resources/desktop-completion-notify-observer.py'),
  );
  return files;
}

/** Read a proven unchanged producer installation, never execute configured code. */
export function compatibleNativeSuggestionImplementation(scope: {
  configPath: string;
  traceRoot: string;
  targetKey: string;
  producerSha256: string;
  consumerSha256: string;
}): boolean {
  if (process.platform !== 'darwin') return false;
  try {
    if (![scope.producerSha256, scope.consumerSha256].every((v) => /^[a-f0-9]{64}$/.test(v)))
      return false;
    const configBytes = ownedBytes(scope.configPath, 16384, true);
    const config = JSON.parse(configBytes.toString('utf8'));
    if (
      typeof config.entry !== 'string' ||
      !isAbsolute(config.entry) ||
      config.traceRoot !== scope.traceRoot ||
      machineRuntimeTargetKey(config.target) !== scope.targetKey
    )
      return false;
    const producerProgram = dirname(dirname(config.entry));
    if (config.entry !== join(producerProgram, 'scripts/desktop-native-suggestions-runtime.mjs'))
      return false;
    const directory = dirname(fileURLToPath(import.meta.url)),
      container = dirname(dirname(directory));
    const consumerProgram =
      basename(container) === 'packages'
        ? dirname(container)
        : basename(container) === '@codex-manage' && basename(dirname(container)) === 'node_modules'
          ? dirname(dirname(container))
          : undefined;
    if (!consumerProgram) return false;
    const producerDirectory = join(producerProgram, 'packages/codex-adapter/dist');
    const producer = inventory(producerProgram, producerDirectory),
      consumer = inventory(consumerProgram, directory);
    if (
      hash(JSON.stringify(producer)) !== scope.producerSha256 ||
      hash(JSON.stringify(consumer)) !== scope.consumerSha256
    )
      return false;
    for (const name of new Set([...Object.keys(producer), ...Object.keys(consumer)]))
      if (!consumerOnly.has(name) && producer[name] !== consumer[name]) return false;
    return (
      same(producer, inventory(producerProgram, producerDirectory)) &&
      configBytes.equals(ownedBytes(scope.configPath, 16384, true))
    );
  } catch {
    return false;
  }
}
