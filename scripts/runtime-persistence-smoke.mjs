import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { RuntimePersistence } from '../packages/codex-adapter/dist/runtime-persistence.js';

const directory = await mkdtemp(join(tmpdir(), 'codex-runtime-persistence-'));
const file = join(directory, 'runtime.json');
let store;
try {
  const legacy = {
    hostId: 'host-a',
    threads: [{ id: 'thread-a', messages: [{ id: 'message-a', text: 'preserved history' }] }],
    receipts: [{ id: 'stable-operation', state: 'uncertain' }],
    attachments: [{ id: 'attachment-a', threadId: 'thread-a' }],
  };
  await writeFile(file, JSON.stringify(legacy), { mode: 0o600 });
  store = new RuntimePersistence(file, directory);
  const migrated = store.load();
  assert.deepEqual(migrated, legacy);
  store.save(migrated);
  const core = JSON.parse(await readFile(file, 'utf8'));
  assert.deepEqual(core.receipts, legacy.receipts);
  assert.deepEqual(core.attachments, legacy.attachments);
  assert.deepEqual(core.threads[0].messages, []);
  assert.equal(core.historyCacheVersion, 1);
  assert.deepEqual(new RuntimePersistence(file, directory).load().threads, legacy.threads);

  // A 100 MiB cold transcript must not be serialized during a receipt commit.
  let historySerializations = 0;
  const largeMessage = {
    id: 'large-message',
    text: 'x'.repeat(100 * 1024 * 1024),
    toJSON() {
      historySerializations++;
      return { id: this.id, text: this.text };
    },
  };
  migrated.threads.push({ id: '../untrusted-id', messages: [largeMessage] });
  for (let i = 0; i < 5; i++) {
    migrated.receipts[0].state = 'submitted';
    store.save(migrated);
  }
  assert.equal(historySerializations, 0);
  assert((await readFile(file)).length < 1024);
  // Latest updates coalesce; graceful shutdown flushes history before return.
  largeMessage.text = 'latest history';
  store.dirty(migrated.threads[1]);
  store.close();
  assert.equal(historySerializations, 1);
  const restored = new RuntimePersistence(file, directory).load();
  assert.equal(restored.threads[1].messages[0].text, 'latest history');
  assert.equal(restored.receipts[0].state, 'submitted');
  const cacheDirectory = join(directory, 'runtime-history-v1');
  const files = await readdir(cacheDirectory);
  assert(files.every((name) => /^[a-f0-9]{64}\.json$/.test(name)));

  // Foreign/corrupt replaceable caches cannot change the durable identity or receipts.
  for (const name of files)
    await writeFile(
      join(cacheDirectory, name),
      JSON.stringify({
        version: 1,
        hostId: 'other-host',
        threadId: '../untrusted-id',
        messages: [{ text: 'foreign' }],
      }),
    );
  const isolated = new RuntimePersistence(file, directory).load();
  assert.equal(isolated.hostId, legacy.hostId);
  assert(isolated.threads.every((thread) => thread.messages.length === 0));
  assert.equal(isolated.receipts[0].state, 'submitted');
  await writeFile(join(cacheDirectory, files[0]), '{broken');
  assert.equal(new RuntimePersistence(file, directory).load().hostId, legacy.hostId);
  console.log(
    'Runtime persistence passed: legacy migration, immediate durable receipts, 100 MiB cold-history isolation, latest cache coalescing, shutdown flush and host isolation.',
  );
} finally {
  store?.close();
  await rm(directory, { recursive: true, force: true });
}
