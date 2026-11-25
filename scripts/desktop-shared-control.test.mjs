import assert from 'node:assert/strict';
import test from 'node:test';
import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { realpathSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  DesktopSharedControl,
  verifyDesktopSharedLauncher,
  verifyDesktopSharedService,
} from '../packages/codex-adapter/dist/desktop-shared-control.js';

const threadId = randomUUID();
const turnId = randomUUID();

test('Node launcher verification binds the live interpreter and first script argument', async () => {
  if (process.platform !== 'darwin') return;
  const directory = await mkdtemp(join(tmpdir(), 'desktop-shared-launcher-'));
  const wrapper = join(directory, 'codex-shared.mjs');
  const node = realpathSync(process.execPath);
  const source = `#!${node}\nconsole.log('ready'); setInterval(() => {}, 1000);\n`;
  let child, decoy;
  try {
    await writeFile(wrapper, source, { mode: 0o700 });
    child = spawn(node, [wrapper, 'app-server'], { stdio: ['ignore', 'pipe', 'pipe'] });
    await once(child.stdout, 'data');
    assert.equal(await verifyDesktopSharedLauncher(child.pid, node, wrapper, source), true);
    const otherScript = join(directory, 'other.mjs');
    await writeFile(otherScript, source, { mode: 0o700 });
    decoy = spawn(node, [otherScript, wrapper], { stdio: ['ignore', 'pipe', 'pipe'] });
    await once(decoy.stdout, 'data');
    // A wrapper mentioned later in argv must not make another script trusted.
    assert.equal(await verifyDesktopSharedLauncher(decoy.pid, node, wrapper, source), false);
    assert.equal(
      await verifyDesktopSharedLauncher(child.pid, node, wrapper.slice(0, -4), source),
      false,
    );
    assert.equal(
      await verifyDesktopSharedLauncher(child.pid, node, wrapper + '.other', source),
      false,
    );
    assert.equal(
      await verifyDesktopSharedLauncher(child.pid, '/different/node', wrapper, source),
      false,
    );
    assert.equal(
      await verifyDesktopSharedLauncher(child.pid, node, wrapper, '#!/usr/bin/env node\n'),
      false,
    );
  } finally {
    for (const process of [child, decoy]) {
      if (process && process.exitCode === null) {
        const exited = once(process, 'exit');
        process.kill('SIGTERM');
        await exited;
      }
    }
    await rm(directory, { recursive: true });
  }
});

function fixture() {
  const calls = [],
    responses = [];
  let identity = 'original-desktop-owner';
  let loaded = true;
  let inspections = 0;
  let readGate;
  const client = {
    onEvent() {},
    onRequest() {},
    onDisconnect() {},
    async connect() {},
    close() {},
    respond(id, result) {
      responses.push({ id, result });
    },
    async call(method, params) {
      calls.push({ method, params });
      if (method === 'thread/loaded/list') return { data: loaded ? [threadId] : [] };
      if (method === 'thread/read') {
        if (readGate) await readGate;
        return {
          thread: {
            id: threadId,
            status: { type: 'idle' },
            historyMode: 'paginated',
            model: 'original-model',
            reasoningEffort: 'xhigh',
          },
        };
      }
      if (method === 'thread/turns/list') return { data: [{ id: turnId, status: 'completed' }] };
      if (method === 'thread/items/list')
        return {
          data: [
            { turnId, item: { id: 'answer', type: 'agentMessage', text: 'synthetic answer' } },
          ],
          nextCursor: 'older-page',
        };
      if (method === 'thread/resume') return { thread: { id: threadId }, model: 'original-model' };
      if (method === 'turn/start') return { turn: { id: 'accepted-turn', status: 'inProgress' } };
      throw new Error('Unexpected method ' + method);
    },
  };
  const transport = new DesktopSharedControl('ws://127.0.0.1:12345', '/synthetic/official-cli', {
    client,
    inspectService: async () => {
      inspections++;
      return identity;
    },
  });
  return {
    transport,
    client,
    calls,
    responses,
    setIdentity: (value) => {
      identity = value;
    },
    setLoaded: (value) => {
      loaded = value;
    },
    setReadGate: (value) => {
      readGate = value;
    },
    inspections: () => inspections,
  };
}

test('shared desktop reads preserve the loaded owner and never resume or submit input', async () => {
  const f = fixture();
  try {
    await f.transport.connect();
    const state = await f.transport.follow(threadId);
    assert.equal(f.transport.hasOwner(threadId), true);
    assert.equal(state.id, threadId);
    assert.equal(state.turns[0].turnId, turnId);
    assert.equal(state.turns[0].items[0].id, 'answer');
    assert.deepEqual(f.transport.modelSettings(threadId), {
      model: 'original-model',
      effort: 'xhigh',
    });
    assert(
      f.calls.every((v) =>
        ['thread/loaded/list', 'thread/read', 'thread/turns/list', 'thread/items/list'].includes(
          v.method,
        ),
      ),
    );
    assert(
      f.calls
        .filter((v) => v.method === 'thread/read')
        .every((v) => v.params.includeTurns === false),
    );
    await assert.rejects(f.transport.completeHistory(threadId), /paginated/);
  } finally {
    f.transport.close();
  }
});

test('explicit commands use the same loaded service, retain speed defaults and fence the sole write', async () => {
  const f = fixture();
  let fenced = 0;
  try {
    await f.transport.connect();
    const result = await f.transport.command(
      threadId,
      'thread-follower-start-turn',
      {
        turnStart: {
          request: {
            threadId: 'wrong-target',
            input: [{ type: 'text', text: 'synthetic' }],
            attachments: [],
            commentAttachments: [],
          },
        },
      },
      () => {
        fenced++;
      },
    );
    assert.equal(result.result.turn.id, 'accepted-turn');
    assert.equal(fenced, 1);
    const attachment = f.calls.find((v) => v.method === 'thread/resume');
    assert.deepEqual(attachment.params, { threadId, excludeTurns: true });
    const writes = f.calls.filter((v) => v.method === 'turn/start');
    assert.equal(writes.length, 1);
    assert.equal(writes[0].params.threadId, threadId);
    for (const key of ['speed', 'serviceTier', 'service_tier', 'attachments', 'commentAttachments'])
      assert.equal(Object.hasOwn(writes[0].params, key), false);
  } finally {
    f.transport.close();
  }
});

test('missing loaded owner, replaced service and failed durability prevent model dispatch', async () => {
  for (const reason of ['unloaded', 'replacement', 'durability']) {
    const f = fixture();
    try {
      await f.transport.connect();
      if (reason === 'unloaded') f.setLoaded(false);
      if (reason === 'replacement') f.setIdentity('different-owner');
      await assert.rejects(
        f.transport.command(
          threadId,
          'thread-follower-start-turn',
          { turnStart: { request: { input: [] } } },
          () => {
            throw new Error('durability failed');
          },
        ),
      );
      assert.equal(
        f.calls.some((v) => v.method === 'turn/start'),
        false,
      );
      if (reason === 'replacement') assert.equal(f.transport.ready, false);
    } finally {
      f.transport.close();
    }
  }
});

test('a lost command result remains unknown and is never automatically replayed', async () => {
  const f = fixture();
  const call = f.client.call.bind(f.client);
  f.client.call = async (method, params) => {
    if (method !== 'turn/start') return call(method, params);
    f.calls.push({ method, params });
    throw new Error('connection lost after write');
  };
  try {
    await f.transport.connect();
    await assert.rejects(
      f.transport.command(threadId, 'thread-follower-start-turn', {
        turnStart: { request: { input: [] } },
      }),
      /lost after write/,
    );
    await f.transport.follow(threadId, 1000, true);
    assert.equal(f.calls.filter((v) => v.method === 'turn/start').length, 1);
  } finally {
    f.transport.close();
  }
});

test('legacy preset hints preserve the exact sandbox and conflicting policies never dispatch', async () => {
  for (const [profile, policy] of [
    [':read-only', { type: 'readOnly' }],
    [
      ':workspace',
      { type: 'workspaceWrite', writableRoots: ['/synthetic/workspace'], networkAccess: false },
    ],
    [':danger-full-access', { type: 'dangerFullAccess' }],
  ]) {
    const f = fixture();
    try {
      await f.transport.connect();
      await f.transport.command(threadId, 'thread-follower-start-turn', {
        turnStart: {
          request: {
            input: [],
            permissions: profile,
            sandboxPolicy: policy,
            approvalPolicy: 'on-request',
          },
        },
      });
      const write = f.calls.find((v) => v.method === 'turn/start');
      assert.equal(Object.hasOwn(write.params, 'permissions'), false);
      assert.deepEqual(write.params.sandboxPolicy, policy);
      assert.equal(write.params.approvalPolicy, 'on-request');
    } finally {
      f.transport.close();
    }
  }
  for (const permissions of [':danger-full-access', 'custom-profile']) {
    const f = fixture();
    let fenced = false;
    try {
      await f.transport.connect();
      await assert.rejects(
        f.transport.command(
          threadId,
          'thread-follower-start-turn',
          {
            turnStart: {
              request: {
                input: [],
                permissions,
                sandboxPolicy: { type: 'readOnly' },
              },
            },
          },
          () => {
            fenced = true;
          },
        ),
        /权限配置冲突/,
      );
      assert.equal(fenced, false);
      assert.equal(
        f.calls.some((v) => v.method === 'turn/start'),
        false,
      );
    } finally {
      f.transport.close();
    }
  }
});

test('close fences late metadata and a response requires the matching authoritative resolution', async () => {
  const f = fixture();
  try {
    await f.transport.connect();
    await f.transport.command(threadId, 'thread-follower-start-turn', {
      turnStart: { request: { input: [] } },
    });
    await f.transport.follow(threadId, 1000, true);
    f.client.onRequest(42, 'item/tool/requestUserInput', { threadId, turnId });
    const response = f.transport.command(threadId, 'thread-follower-submit-user-input', {
      requestId: 42,
      response: { answers: {} },
    });
    while (!f.responses.length) await new Promise((done) => setImmediate(done));
    assert.equal(f.transport.state(threadId).requests.length, 1);
    f.client.onEvent('serverRequest/resolved', { threadId: randomUUID(), requestId: 42 });
    assert.equal(f.transport.state(threadId).requests.length, 1);
    f.client.onEvent('serverRequest/resolved', { threadId, requestId: 42 });
    await response;
    assert.equal(f.transport.state(threadId).requests.length, 0);
    let release;
    f.setReadGate(
      new Promise((done) => {
        release = done;
      }),
    );
    const reading = f.transport.follow(threadId, 1000, true);
    await new Promise((done) => setImmediate(done));
    f.transport.close();
    release();
    await reading;
    assert.equal(f.transport.hasOwner(threadId), false);
    assert.equal(f.transport.state(threadId), undefined);
  } finally {
    f.transport.close();
  }
});

test('cold historical owner scans share one loaded catalog and do not run process probes per thread', async () => {
  const f = fixture();
  try {
    await f.transport.connect();
    const baseline = f.inspections();
    const results = await Promise.all(
      Array.from({ length: 500 }, () => f.transport.follow(randomUUID())),
    );
    assert(results.every((value) => value === undefined));
    assert.equal(f.calls.filter((call) => call.method === 'thread/loaded/list').length, 1);
    assert(f.inspections() - baseline <= 1);
    assert.equal(
      f.calls.some((call) => call.method === 'thread/resume' || call.method === 'turn/start'),
      false,
    );
  } finally {
    f.transport.close();
  }
});

test('shared service ownership verification rejects non-loopback and non-root targets', async () => {
  for (const url of [
    'wss://127.0.0.1:12345',
    'ws://192.168.1.2:12345',
    'ws://127.0.0.1:12345/path',
    'ws://user@127.0.0.1:12345',
    'ws://127.0.0.1:12345/?x=1',
  ])
    await assert.rejects(verifyDesktopSharedService(url, '/synthetic/official-cli'));
});
