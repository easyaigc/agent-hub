import assert from 'node:assert/strict';
import { AsyncLocalStorage } from 'node:async_hooks';
import { createHash, randomUUID } from 'node:crypto';
import { appendFile, chmod, mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

// Explicit opt-in DEV only. Real desktop history and production HTTP/scan code;
// deterministic filesystem injection is confined to files created in this directory.
// No public control/debug endpoint, execution transport or installed-source patch.
const input = process.argv[2];
assert(input, 'Pass a private acceptance configuration file');
const config = JSON.parse(await readFile(input, 'utf8'));
assert.equal(config.purpose, 'attachment-ctime-trigger-acceptance-v1');
assert(/^[a-f0-9-]{36}$/.test(config.hostId));
assert(/^[a-f0-9-]{36}$/.test(config.threadId));
assert(/^[a-f0-9]{16}$/.test(config.nonce));
assert(typeof config.token === 'string' && config.token.length >= 48);
assert(config.directory && config.projectRoot && config.publicEndpoint && config.programRoot);
assert.equal(new URL(config.publicEndpoint).protocol, 'https:');
const directory = resolve(config.directory);
const programRoot = resolve(config.programRoot);
const { startRuntimeServer } = await import(
  pathToFileURL(join(programRoot, 'apps/host-agent/dist/runtime-server.js'))
);
await mkdir(directory, { recursive: true, mode: 0o700 });
const filesRoot = join(directory, 'fixtures');
const state = join(directory, 'host');
await mkdir(filesRoot, { recursive: true, mode: 0o700 });
await mkdir(state, { recursive: true, mode: 0o700 });
const fixtures = [
  ['ctime-read.txt', 'ctime 瞬时下载', 'read-once', 'CTIME_READ_'],
  ['ctime-resolve.txt', 'ctime 瞬时解析', 'inspect-once', 'CTIME_RESOLVE_'],
  ['ctime-busy.txt', 'ctime 持续变化', 'read-always', 'CTIME_BUSY_'],
  ['ordinary.txt', 'ctime 不变对照', 'none', 'CTIME_STABLE_'],
].map(([name, label, mode, prefix]) => ({
  name,
  label,
  mode,
  path: join(filesRoot, name),
  marker: prefix + config.nonce,
}));
for (const file of fixtures) {
  // Do not rewrite artifacts on a restart: keep the original model's referenced bytes.
  try {
    await writeFile(file.path, file.marker + '\n', { flag: 'wx', mode: 0o600 });
  } catch (error) {
    if (error.code !== 'EEXIST') throw error;
    assert.equal(await readFile(file.path, 'utf8'), file.marker + '\n');
  }
}
try {
  await writeFile(
    join(state, 'runtime.json'),
    JSON.stringify({
      hostId: config.hostId,
      projects: [],
      threads: [],
      attachments: [],
      receipts: [],
      creations: {},
    }),
    { flag: 'wx', mode: 0o600 },
  );
} catch (error) {
  if (error.code !== 'EEXIST') throw error;
  assert.equal(JSON.parse(await readFile(join(state, 'runtime.json'))).hostId, config.hostId);
}
// Do not inherit the formal Host's listener, TLS, bridge or trace configuration.
for (const name of Object.keys(process.env)) {
  if (
    name.startsWith('HOST_AGENT_') ||
    name.startsWith('CODEX_DESKTOP_') ||
    name === 'CODEX_RUNTIME_MODE'
  )
    delete process.env[name];
}
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  HOST_AGENT_STATE_DIR: state,
  HOST_AGENT_ROOT: resolve(config.projectRoot),
  HOST_AGENT_NAME: 'MacBook ctime DEV ' + config.nonce,
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_PORT: '0',
  HOST_AGENT_TOKEN: config.token,
  HOST_AGENT_DISCOVERY: '0',
  HOST_AGENT_TRANSFER_DIR: join(directory, 'transfers'),
});
const audit = join(directory, 'trigger-audit.jsonl');
const storage = new AsyncLocalStorage();
const server = await startRuntimeServer();
assert.equal(server.session.hostId, config.hostId);
const files = server.session.attachmentFiles;
const stamp = (s) =>
  Object.fromEntries(
    ['dev', 'ino', 'size', 'mtimeNs', 'ctimeNs', 'birthtimeNs', 'mode'].map((k) => [
      k,
      String(s[k]),
    ]),
  );
const record = (row) =>
  appendFile(audit, JSON.stringify({ at: new Date().toISOString(), ...row }) + '\n', {
    mode: 0o600,
  });
for (const operation of ['inspect', 'read']) {
  const original = files[operation].bind(files);
  files[operation] = (...args) => {
    const file = fixtures.find((f) => f.path === args[0]);
    if (!file) return original(...args);
    return storage.run(
      { id: randomUUID(), operation, file, scan: 0, checks: 0, scanning: false },
      async () => {
        const context = storage.getStore();
        // Force this single resolver case cold; normal/cached paths are unchanged.
        if (file.mode === 'inspect-once' && operation === 'inspect') files.cache.delete(file.path);
        try {
          const result = await original(...args);
          await record({
            id: context.id,
            operation,
            name: file.name,
            result: 'success',
            scans: context.scan,
            sha256:
              operation === 'read'
                ? createHash('sha256').update(result).digest('hex')
                : result.sha256,
          });
          return result;
        } catch (error) {
          await record({
            id: context.id,
            operation,
            name: file.name,
            result: 'rejected',
            scans: context.scan,
            status: error.status,
            code: error.code,
            fileChange: error.fileChange,
          });
          throw error;
        }
      },
    );
  };
}
const scan = files.scan.bind(files);
files.scan = async (...args) => {
  const context = storage.getStore();
  if (!context || context.file.path !== args[0]) return scan(...args);
  context.scan++;
  context.checks = 0;
  context.scanning = true;
  try {
    return await scan(...args);
  } finally {
    context.scanning = false;
  }
};
const checked = files.checked.bind(files);
files.checked = async (...args) => {
  const context = storage.getStore();
  if (context?.scanning && context.file.path === args[0] && ++context.checks === 2) {
    const inject =
      (context.file.mode === 'read-once' && context.operation === 'read' && context.scan === 1) ||
      (context.file.mode === 'inspect-once' &&
        context.operation === 'inspect' &&
        context.scan === 1) ||
      (context.file.mode === 'read-always' && context.operation === 'read');
    if (inject) {
      const before = await stat(context.file.path, { bigint: true });
      await chmod(context.file.path, 0o400);
      await chmod(context.file.path, 0o600);
      const after = await stat(context.file.path, { bigint: true });
      assert.notEqual(before.ctimeNs, after.ctimeNs);
      for (const key of ['dev', 'ino', 'size', 'mtimeNs', 'birthtimeNs', 'mode'])
        assert.equal(after[key], before[key], key + ' must not change');
      await record({
        id: context.id,
        operation: context.operation,
        name: context.file.name,
        scan: context.scan,
        event: 'ctime-only-injected-after-byte-read',
        before: stamp(before),
        after: stamp(after),
      });
    }
  }
  return checked(...args);
};
await server.session.open(config.threadId);
const ready = {
  purpose: config.purpose,
  nonce: config.nonce,
  hostId: config.hostId,
  threadId: config.threadId,
  pid: process.pid,
  node: process.version,
  port: server.server.address().port,
  publicEndpoint: config.publicEndpoint,
  source: programRoot,
  acceptanceScript: resolve(new URL(import.meta.url).pathname),
  fixtures,
  audit,
  isolatedState: state,
  productionFilesModuleSha256: createHash('sha256')
    .update(await readFile(join(programRoot, 'packages/codex-adapter/dist/attachment-files.js')))
    .digest('hex'),
};
await writeFile(join(directory, 'ready.json'), JSON.stringify(ready, null, 2), { mode: 0o600 });
console.log(JSON.stringify(ready));
// startRuntimeServer owns SIGINT/SIGTERM cleanup. It never starts a model Turn.
