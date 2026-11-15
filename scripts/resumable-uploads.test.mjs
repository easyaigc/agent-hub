import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import {
  appendFile,
  copyFile,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  rename,
  rm,
  stat,
  writeFile,
} from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { join, resolve } from 'node:path';
import { ResumableUploads } from '../apps/host-agent/dist/resumable-uploads.js';

const root = resolve('.local/upload-resume-20261115/testing');
const hostId = 'test-upload-host';
const chunkSize = 256 * 1024;
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const hashFile = async (path) => {
  const hash = createHash('sha256');
  for await (const part of createReadStream(path)) hash.update(part);
  return hash.digest('hex');
};
const manifest = (sizeBytes, sha256) => ({
  version: 1,
  hostId,
  id: randomUUID(),
  name: 'test.bin',
  mediaType: 'application/octet-stream',
  sizeBytes,
  sha256,
  purpose: 'file-transfer',
  threadId: null,
  creationId: null,
  attachmentId: null,
});
async function fixture(t) {
  await mkdir(root, { recursive: true });
  const dir = await mkdtemp(join(root, 'case-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const outputs = join(dir, 'outputs');
  await mkdir(outputs);
  let calls = 0;
  const hooks = {
    async validate() {},
    async complete(m, path) {
      calls++;
      const destination = join(outputs, m.id);
      await copyFile(path, destination);
      return { destination };
    },
  };
  return {
    dir,
    outputs,
    hooks,
    store: () => new ResumableUploads(hostId, join(dir, 'state'), hooks),
    calls: () => calls,
  };
}
async function rejectedStatus(promise, status) {
  await assert.rejects(promise, (error) => {
    assert.equal(error.status ?? error.statusCode, status, `unexpected error: ${error.stack}`);
    return true;
  });
}
async function files(dir) {
  const found = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) found.push(...(await files(p)));
    else if (entry.isFile()) found.push(p);
  }
  return found;
}

test('offsets, chunk integrity, manifest identity and idempotent replay', async (t) => {
  const f = await fixture(t),
    s = f.store();
  const a = Buffer.from('first durable chunk'),
    b = Buffer.from('second chunk');
  const m = manifest(a.length + b.length, digest(Buffer.concat([a, b])));
  let receipt = await s.prepare(m);
  assert.equal(receipt.phase, 'uploading');
  assert.equal(receipt.committedBytes, 0);
  assert.equal(receipt.maxChunkBytes, chunkSize);
  await assert.rejects(s.prepare({ ...m, name: 'different.bin' }));
  await rejectedStatus(s.chunk(m.id, 1, digest(a), a), 409);
  await rejectedStatus(s.chunk(m.id, 0, digest(b), a), 400);
  assert.equal((await s.status(m.id)).committedBytes, 0);
  receipt = await s.chunk(m.id, 0, digest(a), a);
  assert.equal(receipt.committedBytes, a.length);
  assert.equal((await s.chunk(m.id, 0, digest(a), a)).committedBytes, a.length);
  const changed = Buffer.alloc(a.length, 42);
  await assert.rejects(s.chunk(m.id, 0, digest(changed), changed));
  await rejectedStatus(s.chunk(m.id, a.length + 1, digest(b), b), 409);
  await assert.rejects(
    s.chunk(m.id, a.length, digest(Buffer.alloc(chunkSize + 1)), Buffer.alloc(chunkSize + 1)),
  );
  assert.equal((await s.status(m.id)).committedBytes, a.length);
  await s.chunk(m.id, a.length, digest(b), b);
  const done = await s.complete(m.id);
  assert.equal(done.phase, 'completed');
  assert.equal(done.committedBytes, m.sizeBytes);
  assert.equal(await hashFile(join(f.outputs, m.id)), m.sha256);
  assert.equal((await s.complete(m.id)).phase, 'completed');
  assert.equal((await s.cancel(m.id)).phase, 'completed');
  await assert.rejects(s.chunk(m.id, 0, digest(a), a));
  assert.equal(f.calls(), 1);
  assert.deepEqual(await readdir(f.outputs), [m.id]);
});

test('restart truncates an uncommitted disk tail and resumes durable prefix', async (t) => {
  const f = await fixture(t),
    a = Buffer.from('durable-prefix-unique-123'),
    b = Buffer.from('remaining-data');
  const m = manifest(a.length + b.length, digest(Buffer.concat([a, b])));
  let s = f.store();
  await s.prepare(m);
  await s.chunk(m.id, 0, digest(a), a);
  const candidates = [];
  for (const path of await files(join(f.dir, 'state'))) {
    if ((await stat(path)).size === a.length && (await readFile(path)).equals(a))
      candidates.push(path);
  }
  assert.equal(candidates.length, 1, 'identify the actual durable data file');
  await appendFile(candidates[0], Buffer.from('uncommitted-tail'));
  s = f.store();
  assert.equal((await s.status(m.id)).committedBytes, a.length);
  await s.chunk(m.id, a.length, digest(b), b);
  assert.equal((await s.complete(m.id)).phase, 'completed');
  assert.equal(await hashFile(join(f.outputs, m.id)), m.sha256);
});

test('whole-file checksum and cancellation prevent publication', async (t) => {
  const f = await fixture(t),
    s = f.store(),
    a = Buffer.from('content');
  const bad = manifest(a.length, digest(Buffer.from('different')));
  await s.prepare(bad);
  await s.chunk(bad.id, 0, digest(a), a);
  await assert.rejects(s.complete(bad.id));
  assert.equal(f.calls(), 0);
  const m = manifest(a.length, digest(a));
  await s.prepare(m);
  assert.equal((await s.cancel(m.id)).phase, 'cancelled');
  await assert.rejects(s.chunk(m.id, 0, digest(a), a));
  await assert.rejects(s.complete(m.id));
  assert.equal((await f.store().status(m.id)).phase, 'cancelled');
  assert.deepEqual(await readdir(f.outputs), []);
});

test('state persistence failure leaves the last durable offset authoritative', async (t) => {
  const f = await fixture(t),
    a = Buffer.from('committed'),
    b = Buffer.from('not-yet-committed');
  const m = manifest(a.length + b.length, digest(Buffer.concat([a, b])));
  let sabotage = false;
  const record = join(f.dir, 'state', 'resumable-uploads', `${m.id}.json`);
  const backup = `${record}.backup`;
  f.hooks.validate = async () => {
    if (sabotage) {
      sabotage = false;
      await rename(record, backup);
      await mkdir(record);
    }
  };
  let s = f.store();
  await s.prepare(m);
  await s.chunk(m.id, 0, digest(a), a);
  sabotage = true;
  await assert.rejects(s.chunk(m.id, a.length, digest(b), b));
  await rm(record, { recursive: true });
  await rename(backup, record);
  s = f.store();
  assert.equal((await s.status(m.id)).committedBytes, a.length);
  assert.equal((await s.chunk(m.id, a.length, digest(b), b)).committedBytes, m.sizeBytes);
  assert.equal((await s.complete(m.id)).phase, 'completed');
  assert.equal(await hashFile(join(f.outputs, m.id)), m.sha256);
  assert.equal(f.calls(), 1);
});

test(
  '64 MiB upload remains resumable across a fresh store and publishes once',
  { timeout: 180000 },
  async (t) => {
    const f = await fixture(t);
    const block = Buffer.alloc(chunkSize);
    for (let i = 0; i < block.length; i++) block[i] = (i * 31 + (i >>> 8)) & 255;
    const count = 256;
    const hash = createHash('sha256');
    for (let i = 0; i < count; i++) hash.update(block);
    const m = manifest(count * block.length, hash.digest('hex'));
    let s = f.store();
    await s.prepare(m);
    for (let i = 0; i < count; i++) {
      if (i === 128) {
        s = f.store();
        assert.equal((await s.status(m.id)).committedBytes, i * block.length);
      }
      const receipt = await s.chunk(m.id, i * block.length, digest(block), block);
      assert.equal(receipt.committedBytes, (i + 1) * block.length);
    }
    assert.equal((await s.complete(m.id)).phase, 'completed');
    assert.equal(await hashFile(join(f.outputs, m.id)), m.sha256);
    assert.equal((await f.store().complete(m.id)).phase, 'completed');
    assert.equal(f.calls(), 1);
    assert.deepEqual(await readdir(f.outputs), [m.id]);
  },
);

test('recovers a receipt persisted before its empty partial was created', async (t) => {
  const f = await fixture(t),
    bytes = Buffer.from('after-crash');
  const m = manifest(bytes.length, digest(bytes));
  await f.store().prepare(m);
  const partial = join(f.dir, 'state', 'resumable-uploads', `${m.id}.partial`);
  assert.equal((await stat(partial)).size, 0);
  await rm(partial);
  const s = f.store();
  assert.equal((await s.status(m.id)).committedBytes, 0);
  assert.equal((await s.chunk(m.id, 0, digest(bytes), bytes)).committedBytes, bytes.length);
  assert.equal((await s.complete(m.id)).phase, 'completed');
  assert.equal(await hashFile(join(f.outputs, m.id)), m.sha256);
});

test('prune expires only old unpublished staging and preserves receipts and published output', async (t) => {
  const f = await fixture(t),
    s = f.store(),
    bytes = Buffer.from('retained');
  const expired = manifest(bytes.length, digest(bytes));
  const recent = manifest(bytes.length, digest(bytes));
  const done = manifest(bytes.length, digest(bytes));
  const unknown = manifest(bytes.length, digest(bytes));
  for (const m of [expired, recent, done, unknown]) {
    await s.prepare(m);
    await s.chunk(m.id, 0, digest(bytes), bytes);
  }
  await s.complete(done.id);
  const state = join(f.dir, 'state', 'resumable-uploads');
  const age = async (m, mutate = () => {}) => {
    const path = join(state, `${m.id}.json`);
    const receipt = JSON.parse(await readFile(path, 'utf8'));
    receipt.updatedAt = new Date(Date.now() - 8 * 86400_000).toISOString();
    mutate(receipt);
    await writeFile(path, JSON.stringify(receipt));
  };
  await age(expired);
  await age(done);
  await age(unknown, (r) => {
    r.phase = 'verifying';
    r.publicationStarted = true;
  });
  await f.store().prune();
  assert.equal((await s.status(expired.id)).phase, 'cancelled');
  assert.equal((await s.status(expired.id)).reason, 'expired');
  await assert.rejects(stat(join(state, `${expired.id}.partial`)), { code: 'ENOENT' });
  assert.equal((await s.status(recent.id)).phase, 'uploading');
  assert.equal((await stat(join(state, `${recent.id}.partial`))).size, bytes.length);
  assert.equal((await s.status(unknown.id)).phase, 'verifying');
  assert.equal((await stat(join(state, `${unknown.id}.partial`))).size, bytes.length);
  assert.equal((await s.status(done.id)).phase, 'completed');
  assert.equal(await hashFile(join(f.outputs, done.id)), done.sha256);
});

test('whole-file checksum failure remains cancellable after restart', async (t) => {
  const f = await fixture(t),
    bytes = Buffer.from('bad-hash');
  const m = manifest(bytes.length, digest(Buffer.from('other')));
  let s = f.store();
  await s.prepare(m);
  await s.chunk(m.id, 0, digest(bytes), bytes);
  await rejectedStatus(s.complete(m.id), 400);
  s = f.store();
  assert.equal((await s.status(m.id)).phase, 'uploading');
  assert.equal((await s.cancel(m.id)).phase, 'cancelled');
  assert.equal((await f.store().status(m.id)).phase, 'cancelled');
  assert.equal(f.calls(), 0);
  await assert.rejects(stat(join(f.dir, 'state', 'resumable-uploads', `${m.id}.partial`)), {
    code: 'ENOENT',
  });
});

test('unknown publication result is reconciled idempotently before completion', async (t) => {
  const f = await fixture(t),
    bytes = Buffer.from('publish-once');
  const m = manifest(bytes.length, digest(bytes));
  const destination = join(f.outputs, m.id);
  let attempts = 0,
    publications = 0;
  f.hooks.complete = async (_m, path) => {
    attempts++;
    try {
      assert.equal(await hashFile(destination), m.sha256);
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      await copyFile(path, destination);
      publications++;
      throw new Error('publication response lost', { cause: error });
    }
    return { destination };
  };
  let s = f.store();
  await s.prepare(m);
  await s.chunk(m.id, 0, digest(bytes), bytes);
  await assert.rejects(s.complete(m.id), /publication response lost/);
  s = f.store();
  assert.equal((await s.status(m.id)).publicationStarted, true);
  await rejectedStatus(s.cancel(m.id), 409);
  assert.equal((await s.complete(m.id)).phase, 'completed');
  assert.equal((await f.store().complete(m.id)).phase, 'completed');
  assert.equal(attempts, 2);
  assert.equal(publications, 1);
  assert.deepEqual(await readdir(f.outputs), [m.id]);
});
