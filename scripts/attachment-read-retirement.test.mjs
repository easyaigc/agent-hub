import assert from 'node:assert/strict';
import { mkdtemp, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { AttachmentReads } from '../apps/host-agent/dist/attachment-reads.js';
import { RuntimeError } from '../packages/codex-adapter/dist/index.js';

const id = '12345678-1234-1234-1234-123456789abc';
const target = [id, 'scope', 'thread', 'source'];
const deferred = () => {
  let resolve;
  const promise = new Promise((done) => (resolve = done));
  return { promise, resolve };
};

async function fixture(run) {
  const directory = await mkdtemp(join(tmpdir(), 'attachment-retirement-'));
  let calls = 0;
  const session = {
    async prepareAttachmentRead(_thread, _source, output, _signal, reserve) {
      calls++;
      await reserve(128);
      await output.writeFile('private snapshot');
      throw new RuntimeError(409, 'source changed');
    },
  };
  const reads = new AttachmentReads(session, directory);
  try {
    await run(reads, () => calls);
  } finally {
    try {
      await reads.close();
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
  }
}

test('cancel before preparation remembers the original scoped ID without starting source work', async () => {
  await fixture(async (reads, calls) => {
    await assert.rejects(reads.cancel(...target), { status: 410 });
    await assert.rejects(reads.prepare(...target), { status: 410 });
    await assert.rejects(reads.prepare(id, 'other-scope', 'thread', 'source'), { status: 403 });
    assert.equal(calls(), 0);
    assert.equal(reads.reserved, 0);
  });
});

test('failure becomes visible only after snapshot and reservation retirement', async () => {
  await fixture(async (reads, calls) => {
    const entered = deferred();
    const release = deferred();
    const retire = reads.retire;
    let captured;
    reads.retire = async function (entry) {
      if (entry.id === id && !captured) {
        captured = entry;
        entered.resolve();
        await release.promise;
      }
      return retire.call(this, entry);
    };
    try {
      assert.equal((await reads.prepare(...target)).phase, 'preparing');
      await entered.promise;
      const path = captured.path;
      assert.ok(path);
      const snapshot = await stat(path);
      if (process.platform !== 'win32') assert.equal(snapshot.mode & 0o077, 0);
      assert.equal(captured.reserved, 128);
      assert.equal(reads.reserved, 128);
      assert.equal((await reads.prepare(...target)).phase, 'preparing');
      release.resolve();
      await captured.work;
      await assert.rejects(reads.prepare(...target), { status: 409, message: 'source changed' });
      await assert.rejects(stat(path), { code: 'ENOENT' });
      assert.equal(captured.reserved, 0);
      assert.equal(reads.reserved, 0);
      await assert.rejects(reads.prepare(...target), { status: 409, message: 'source changed' });
      assert.equal(calls(), 1);
      assert.equal(reads.entries.get(id), captured);
    } finally {
      release.resolve();
      reads.retire = retire;
    }
  });
});

test('cleanup failure remains visible instead of reporting successful retirement', async () => {
  await fixture(async (reads) => {
    const entered = deferred();
    const release = deferred();
    const retire = reads.retire;
    const cleanupError = new Error('synthetic retirement failure');
    let captured;
    reads.retire = async function (entry) {
      if (entry.id === id && !captured) {
        captured = entry;
        entered.resolve();
        await release.promise;
        throw cleanupError;
      }
      return retire.call(this, entry);
    };
    try {
      await reads.prepare(...target);
      await entered.promise;
      const path = captured.path;
      assert.equal((await reads.prepare(...target)).phase, 'preparing');
      release.resolve();
      await captured.work;
      await assert.rejects(reads.prepare(...target), (error) => error === cleanupError);
      assert.ok(await stat(path));
      assert.equal(reads.reserved, 128);
    } finally {
      release.resolve();
      reads.retire = retire;
    }
  });
});
