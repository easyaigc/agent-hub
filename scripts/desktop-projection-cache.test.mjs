import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, rm, writeFile, symlink, chmod, mkdir, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { AttachmentFileError } from '../packages/codex-adapter/dist/attachment-files.js';
import {
  DesktopProjectionCache,
  boundedProjectionInput,
} from '../packages/codex-adapter/dist/desktop-projection-cache.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';

const value = (text) => ({ message: { text }, referenceText: text });
test('LRU enforces entry and byte bounds; expiration, invalidation and oversized entries', () => {
  let now = 0;
  const c = new DesktopProjectionCache(2, 200, () => now);
  const input = value('a');
  c.set('a', 't', '1', input, false);
  input.message.text = 'mutated input';
  const read = c.get('a', '1');
  assert.equal(read.message.text, 'a');
  read.message.text = 'mutated result';
  assert.equal(c.get('a', '1').message.text, 'a');
  c.set('b', 't', '1', value('b'), false);
  assert(c.get('a', '1'));
  c.set('c', 'u', '1', value('c'), true);
  assert.equal(c.get('b', '1'), undefined);
  assert(c.get('c', '1'));
  now = 59999;
  assert(c.get('c', '1'));
  now = 60000;
  assert.equal(c.get('c', '1'), undefined);
  assert.equal(c.get('a', 'changed'), undefined);
  c.set('huge', 't', '1', value('x'.repeat(201)), false);
  assert.equal(c.get('huge', '1'), undefined);
  c.set('x', 't', '1', value('x'), false);
  c.invalidate('t');
  assert.equal(c.get('x', '1'), undefined);
  c.set('x', 't', '1', value('x'), false);
  c.clear();
  assert.equal(c.get('x', '1'), undefined);
  const small = new DesktopProjectionCache(10, 100, () => 0);
  small.set('x', 't', '1', value('a'.repeat(20)), false);
  small.set('y', 't', '1', value('b'.repeat(20)), false);
  assert.equal(small.get('x', '1'), undefined);
  assert(small.get('y', '1'));
});

async function fixture(run) {
  // The exact-path grant uses physical paths; macOS /var aliases /private/var.
  const dir = await realpath(await mkdtemp(join(tmpdir(), 'projection-test-')));
  const session = new RuntimeSession({
    root: dir,
    directory: join(dir, 'state'),
    name: 'test',
    mode: 'desktop-runtime',
    socket: join(dir, 'absent.sock'),
  });
  const thread = {
    id: 'thread',
    hostId: session.hostId,
    projectId: null,
    title: 'test',
    state: 'unknown',
    turnId: null,
    messages: [],
    plan: [],
    error: null,
    desktopDirectory: dir,
  };
  session.state.threads = [thread];
  const state = {
    turns: [
      {
        turnId: 'turn',
        status: 'completed',
        items: [{ id: 'one', type: 'agentMessage', text: 'first' }],
      },
    ],
  };
  try {
    await run({ session, thread, state, dir });
  } finally {
    session.close();
    await rm(dir, { recursive: true, force: true });
  }
}

test('projection invalidates on wire, status, context and receipt changes; duplicate IDs retain last item', () =>
  fixture(async ({ session, thread, state }) => {
    state.turns[0].items[0].text = 'first ' + 'x'.repeat(2048);
    let calls = 0;
    const original = session.upsert.bind(session);
    session.upsert = async (...args) => {
      calls++;
      return original(...args);
    };
    const project = () => session.projectDesktopMessages(thread, state);
    await project();
    assert.equal(calls, 1);
    await project();
    assert.equal(calls, 1);
    state.turns[0].items[0].text = 'changed ' + 'x'.repeat(2048);
    await project();
    assert.equal(calls, 2);
    state.turns[0].status = 'inProgress';
    await project();
    assert.equal(calls, 3);
    thread.desktopDirectory += '-changed';
    await project();
    assert.equal(calls, 4);
    thread.projectId = 'changed';
    await project();
    assert.equal(calls, 5);
    session.options.root += '-changed';
    await project();
    assert.equal(calls, 6);
    state.turns[0].items.push({ id: 'one', type: 'agentMessage', text: 'last' });
    await project();
    assert.equal(thread.messages.length, 1);
    assert.equal(thread.messages[0].text, 'last');
    state.turns[0].items = [
      {
        id: 'user',
        type: 'userMessage',
        clientId: 'client',
        content: [{ type: 'text', text: 'wire' }],
      },
    ];
    session.state.receipts.push({
      threadId: thread.id,
      clientMessageId: 'client',
      state: 'uncertain',
      reply: { text: 'receipt one', attachmentIds: [], mode: 'next-turn' },
    });
    await project();
    assert.equal(thread.messages.at(-1).text, 'receipt one');
    session.state.receipts[0].reply.text = 'receipt two';
    await project();
    assert.equal(thread.messages.at(-1).text, 'receipt two');
    assert.equal(session.state.receipts[0].state, 'uncertain');
  }));

test('superseded projection stops at cooperative boundary', () =>
  fixture(async ({ session, thread, state }) => {
    state.turns[0].items = Array.from({ length: 100 }, (_, i) => ({
      id: String(i),
      type: 'agentMessage',
      text: String(i),
    }));
    let valid = true,
      calls = 0;
    const original = session.upsert.bind(session);
    session.upsert = async (...args) => {
      calls++;
      if (calls === 5) valid = false;
      return original(...args);
    };
    await session.projectDesktopMessages(thread, state, () => valid);
    assert.equal(calls, 5);
  }));

test('explicit history refresh invalidates presentation reuse', () =>
  fixture(async ({ session, thread, state }) => {
    state.turns[0].items[0].text = 'long ' + 'x'.repeat(2048);
    let calls = 0;
    const original = session.upsert.bind(session);
    session.upsert = async (...args) => {
      calls++;
      return original(...args);
    };
    await session.projectDesktopMessages(thread, state);
    await session.projectDesktopMessages(thread, state);
    assert.equal(calls, 1);
    session.open = async () => ({});
    session.runtime.ipc.refreshSnapshot = () => {};
    await session.refreshHistory(thread.id);
    await session.projectDesktopMessages(thread, state);
    assert.equal(calls, 2);
  }));

test('a superseded job publishes only the latest snapshot', () =>
  fixture(async ({ session, thread, state }) => {
    session.desktopControls = () => {};
    session.runtime.ipc.hasOwner = () => true;
    session.desktopReadReply = () => undefined;
    let release, started;
    const ready = new Promise((done) => {
      started = done;
    });
    const gate = new Promise((done) => {
      release = done;
    });
    const original = session.projectDesktopMessages.bind(session);
    let calls = 0;
    session.projectDesktopMessages = async (...args) => {
      if (++calls === 1) {
        started();
        await gate;
      }
      return original(...args);
    };
    const emitted = [];
    session.emit = (...args) => emitted.push(args);
    const work = session.desktopSnapshot(thread, state);
    await ready;
    const latest = structuredClone(state);
    latest.turns[0].items[0].text = 'latest';
    assert.equal(session.desktopSnapshot(thread, latest), work);
    release();
    await work;
    assert.equal(calls, 2);
    assert.equal(thread.messages[0].text, 'latest');
    assert.equal(emitted.filter((v) => v[1] === 'desktop/snapshot').length, 1);
  }));

test('native paths are rechecked on each snapshot, including busy and authorization', () =>
  fixture(async ({ session, thread, state, dir }) => {
    const path = join(dir, 'file.txt');
    await writeFile(path, 'version one');
    state.turns[0].items = [
      { id: 'file', type: 'userMessage', content: [{ type: 'localImage', path }] },
    ];
    const project = () => session.projectDesktopMessages(thread, state);
    await project();
    const first = thread.messages[0].attachments[0];
    assert(first);
    assert.equal((await session.download(thread.id, first.id)).data.toString(), 'version one');
    await writeFile(path, 'version two');
    await project();
    await assert.rejects(session.download(thread.id, first.id));
    const second = thread.messages[0].attachments[0];
    assert.notEqual(second.sha256, first.sha256);
    assert.equal((await session.download(thread.id, second.id)).data.toString(), 'version two');
    const inspect = session.attachmentFiles.inspect;
    session.attachmentFiles.inspect = async () => {
      throw new AttachmentFileError(503, 'busy');
    };
    try {
      await assert.rejects(project(), (error) => error.status === 503);
    } finally {
      session.attachmentFiles.inspect = inspect;
    }
    await writeFile(path, 'version three');
    session.open = async () => ({});
    session.runtime.ipc.refreshSnapshot = () => {};
    await session.refreshHistory(thread.id);
    await project();
    const third = thread.messages[0].attachments[0];
    assert.notEqual(third.sha256, second.sha256);
    if (process.platform !== 'win32') {
      await chmod(path, 0o000);
      await assert.rejects(session.download(thread.id, third.id), (error) => error.status === 403);
      await chmod(path, 0o600);
    }
    const outside = await mkdtemp(join(tmpdir(), 'projection-outside-'));
    try {
      const target = join(outside, 'target.txt');
      await writeFile(target, 'outside');
      await rm(path);
      await symlink(target, path);
      await assert.rejects(session.download(thread.id, third.id), (error) => error.status === 403);
      await project();
      assert.equal(thread.messages[0].attachments.length, 0);
      state.turns[0].items[0].content[0].path = target;
      await project();
      assert.equal(thread.messages[0].attachments.length, 0);
      const otherRoot = join(dir, 'other-root');
      await mkdir(otherRoot);
      thread.desktopDirectory = otherRoot;
      state.turns[0].items[0].content[0].path = 'file.txt';
      await project();
      assert.equal(thread.messages[0].attachments.length, 0);
    } finally {
      await rm(outside, { recursive: true, force: true });
    }
  }));

test('changed inline image produces a fresh content digest', () =>
  fixture(async ({ session, thread, state }) => {
    const image = (s) => Buffer.from(s).toString('base64');
    const item = {
      id: 'image',
      type: 'agentMessage',
      text: 'synthetic',
      output: [{ type: 'image', mimeType: 'image/png', data: image('image one') }],
    };
    state.turns[0].items = [item];
    await session.projectDesktopMessages(thread, state);
    const first = thread.messages[0].attachments[0];
    assert(first);
    item.output[0].data = image('image two');
    await session.projectDesktopMessages(thread, state);
    assert.notEqual(thread.messages[0].attachments[0].sha256, first.sha256);
  }));

test('an error from a superseded job does not reject its newer snapshot', () =>
  fixture(async ({ session, thread, state }) => {
    session.desktopControls = () => {};
    session.runtime.ipc.hasOwner = () => true;
    session.desktopReadReply = () => undefined;
    let release, started;
    const ready = new Promise((done) => {
      started = done;
    });
    const gate = new Promise((done) => {
      release = done;
    });
    const original = session.projectDesktopMessages.bind(session);
    let calls = 0;
    session.projectDesktopMessages = async (...args) => {
      if (++calls === 1) {
        started();
        await gate;
        throw new Error('stale file inspection failed');
      }
      return original(...args);
    };
    const emitted = [];
    session.emit = (...args) => emitted.push(args);
    const work = session.desktopSnapshot(thread, state);
    await ready;
    const latest = structuredClone(state);
    latest.turns[0].items[0].text = 'newest after stale failure';
    assert.equal(session.desktopSnapshot(thread, latest), work);
    release();
    await work;
    assert.equal(calls, 2);
    assert.equal(thread.messages[0].text, 'newest after stale failure');
    assert.equal(emitted.filter((entry) => entry[1] === 'desktop/snapshot').length, 1);
    assert.equal(emitted.filter((entry) => entry[1] === 'desktop/projection-failed').length, 0);
  }));

test('short plain messages bypass the cache and preserve text', () =>
  fixture(async ({ session, thread, state }) => {
    let gets = 0,
      sets = 0;
    const cache = session.desktopProjectionCache;
    const get = cache.get.bind(cache),
      set = cache.set.bind(cache);
    cache.get = (...args) => {
      gets++;
      return get(...args);
    };
    cache.set = (...args) => {
      sets++;
      return set(...args);
    };
    await session.projectDesktopMessages(thread, state);
    await session.projectDesktopMessages(thread, state);
    assert.equal(thread.messages[0].text, 'first');
    assert.equal(gets, 0);
    assert.equal(sets, 0);
  }));

test('oversized rich input bypasses fingerprint caching without losing media bytes', () =>
  fixture(async ({ session, thread, state }) => {
    const bytes = Buffer.alloc(64 * 1024, 7);
    const item = {
      id: 'large-image',
      type: 'agentMessage',
      text: 'large synthetic image',
      output: [{ type: 'image', mimeType: 'image/png', data: bytes.toString('base64') }],
    };
    assert.equal(boundedProjectionInput(item), false);
    assert.equal(boundedProjectionInput({ text: 'small', output: [] }), true);
    assert.equal(boundedProjectionInput(Array.from({ length: 513 }, () => 'x')), false);
    state.turns[0].items = [item];
    session.desktopProjectionCache.get = () =>
      assert.fail('oversized input must bypass fingerprint cache');
    await session.projectDesktopMessages(thread, state);
    const first = thread.messages[0].attachments[0];
    assert(first);
    assert.equal(first.sizeBytes, bytes.length);
    await session.projectDesktopMessages(thread, state);
    assert.deepEqual(thread.messages[0].attachments[0], first);
    assert.deepEqual((await session.download(thread.id, first.id)).data, bytes);
  }));
