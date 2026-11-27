import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdirSync } from 'node:fs';
import { mkdir, mkdtemp, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';

test('private-state refusal leaves a transfer retryable without publishing or stranding scratch', async (t) => {
  const real = await import('../packages/codex-adapter/dist/index.js');
  const runningOnWindows = process.platform === 'win32';
  const reject = () =>
    Object.assign(new Error('Private storage refused'), { code: 'UNSAFE_WINDOWS_PRIVATE_STATE' });
  let refuseProtection = () => false;
  let refuseCheck = () => false;
  const adapter = t.mock.module('../packages/codex-adapter/dist/index.js', {
    namedExports: {
      RuntimeError: real.RuntimeError,
      makePrivateDirectorySync: runningOnWindows
        ? real.makePrivateDirectorySync
        : (path) => mkdirSync(path, { recursive: true, mode: 0o700 }),
      openPrivateFile: (...args) => real.openPrivateFile(...args),
      renamePrivateFileSync: (...args) => real.renamePrivateFileSync(...args),
      protectWindowsStateDirectory(path) {
        if (runningOnWindows) real.protectWindowsStateDirectory(path);
      },
      protectWindowsStateFile(path) {
        if (refuseProtection(path)) throw reject();
        real.protectWindowsStateFile(path);
      },
      assertPrivateFile(path) {
        if (refuseCheck(path)) throw reject();
        if (runningOnWindows || process.platform !== 'win32') real.assertPrivateFile(path);
      },
    },
  });
  t.after(() => adapter.restore());
  const { FileTransfers } = await import('../apps/host-agent/dist/file-transfers.js');
  const root = await realpath(await mkdtemp(join(tmpdir(), 'transfer-private-')));
  t.after(() => rm(root, { recursive: true, force: true }));
  const hostId = randomUUID();
  const state = join(root, 'state');
  const destination = join(root, 'received');
  const records = join(state, 'file-transfers');
  await mkdir(state, { mode: 0o700 });
  const store = new FileTransfers(hostId, state, destination);
  const bytes = Buffer.from('fixture bytes');
  const manifest = {
    hostId,
    id: randomUUID(),
    name: 'sample.txt',
    mediaType: 'text/plain',
    sizeBytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex'),
  };
  async function* source() {
    yield bytes;
  }

  refuseProtection = (path) => path.startsWith(records);
  await assert.rejects(store.prepare(manifest), { code: 'UNSAFE_WINDOWS_PRIVATE_STATE' });
  assert.deepEqual(await readdir(records), []);

  refuseProtection = () => false;
  const prepared = await store.prepare(manifest);
  refuseProtection = (path) => path.endsWith('.partial');
  await assert.rejects(store.upload(manifest.id, source()), {
    code: 'UNSAFE_WINDOWS_PRIVATE_STATE',
  });
  assert.deepEqual(await readdir(join(destination, '.incoming')), []);
  assert.deepEqual(await store.status(manifest.id), prepared);

  refuseProtection = () => false;
  const completed = await store.upload(manifest.id, source());
  assert.equal(completed.phase, 'completed');
  const receiptPath = join(records, manifest.id + '.json');
  const original = JSON.stringify(prepared);
  await writeFile(receiptPath, original);
  refuseCheck = (path) => path === completed.destinationPath;
  const originalPlatform = Object.getOwnPropertyDescriptor(process, 'platform');
  Object.defineProperty(process, 'platform', { ...originalPlatform, value: 'win32' });
  try {
    await assert.rejects(store.status(manifest.id), { status: 409 });
  } finally {
    Object.defineProperty(process, 'platform', originalPlatform);
  }
  assert.equal(await readFile(receiptPath, 'utf8'), original);
  assert.deepEqual(await readFile(completed.destinationPath), bytes);
});
