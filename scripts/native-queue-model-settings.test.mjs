import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { stripTypeScriptTypes } from 'node:module';
import { test } from 'node:test';

// Isolate actual production methods so this stays a lightweight source-boundary
// test: no Host, native queue, desktop history, compiler or workspace build.
const read = (name) => readFileSync(new URL('../' + name, import.meta.url), 'utf8');
const runtime = read('packages/codex-adapter/src/runtime.ts');
const desktop = read('packages/codex-adapter/src/desktop-ipc.ts');
const generation = read('packages/codex-adapter/src/generation-settings.ts');
// An isolated integration candidate can run these same assertions before adoption.
const session = readFileSync(
  process.env.NATIVE_QUEUE_MODEL_SETTINGS_SOURCE ||
    new URL('../packages/codex-adapter/src/runtime-session.ts', import.meta.url),
  'utf8',
);
function block(text, prefix, end) {
  const start = text.indexOf(prefix);
  assert(start >= 0, 'Missing production declaration ' + prefix);
  const close = text.indexOf(end, start);
  assert(close > start, 'Missing production boundary ' + prefix);
  return text.slice(start, close + end.length);
}
const helpers = runtime.slice(
  runtime.indexOf('export const object ='),
  runtime.indexOf('\n\n', runtime.indexOf('export const object =')),
);
const queueOriginal = block(session, '  private async enqueueNative(', '\n  }');
const program = [
  'import { createHash } from "node:crypto";',
  helpers,
  block(session, 'export class RuntimeError extends Error {', '\n}'),
  block(generation, 'export function desktopModelSettings(', '\n}'),
  'export class DesktopIpc { currentModels = new Map(); owners = new Map(); states = new Map(); modelReads = new Map(); modelEpoch = 0; socket = {}; ready = true; onModelSettings = () => {};',
  block(desktop, '  state(id: string)', '\n  }'),
  block(desktop, '  modelSettings(id: string)', '\n  }'),
  block(desktop, '  async refreshModelSettings(', '\n  }'),
  block(desktop, '  private recordModelSettings(', '\n  }'),
  '}',
  'export class DesktopRuntime {}',
  'export class QueueSession { nativeQueueRevisions = new Map(); nativeQueues = new Map();',
  queueOriginal,
  '}',
].join('\n');
const compiled = stripTypeScriptTypes(program, { mode: 'transform' });
const { desktopModelSettings, DesktopIpc, DesktopRuntime, QueueSession } = await import(
  'data:text/javascript,' + encodeURIComponent(compiled)
);
const sha = (s) => createHash('sha256').update(s).digest('hex');
const model = 'gpt-6.1-sol',
  threadId = 'synthetic-original-thread';
const oldTurn = {
  turnId: 'synthetic-original-turn',
  status: 'inProgress',
  params: {
    model,
    effort: 'xhigh',
    collaborationMode: { settings: { model, reasoning_effort: 'xhigh' } },
  },
};
const unknownStream = () => ({
  latestModel: model,
  latestReasoningEffort: null,
  turns: [structuredClone(oldTurn)],
});
function fixture({ ownerEffort, noOwnerRead = true, streamEffort = null, settings = {} } = {}) {
  const ipc = new DesktopIpc();
  ipc.owners.set(threadId, 'owner');
  if (!noOwnerRead)
    ipc.recordModelSettings(threadId, 'owner', { model, reasoningEffort: ownerEffort });
  const runtime = new DesktopRuntime();
  runtime.ipc = ipc;
  const session = new QueueSession();
  session.runtime = runtime;
  session.ready = true;
  session.closed = false;
  session.nativeQueueUnsupported = false;
  const stream = { ...unknownStream(), latestReasoningEffort: streamEffort };
  const projected = desktopModelSettings(stream);
  const thread = {
    id: threadId,
    turnId: oldTurn.turnId,
    runtimeModel: projected.model,
    runtimeEffort: projected.effort,
    permissionMode: 'full-access',
    speed: 'standard',
    collaborationMode: 'default',
  };
  let addCalls = 0,
    saves = 0,
    settingsRequests = 0;
  ipc.request = async () => {
    settingsRequests++;
    throw new Error('Queue admission must not start a new owner-settings read');
  };
  session.thread = (id) => {
    assert.equal(id, threadId);
    return thread;
  };
  session.refreshNativeQueue = async (id) => {
    assert.equal(id, threadId);
  };
  session.nativeQueue = {
    available: true,
    add: async () => {
      addCalls++;
      // Stop at an in-memory admission port. Never call a real native queue or
      // run the beforeWrite callback; these assertions test only settings guards.
      throw new Error('fixture-admission-boundary-no-write');
    },
  };
  session.save = () => {
    saves++;
  };
  const receipt = {
    clientMessageId: 'synthetic-operation',
    state: 'received',
    turnId: null,
    error: null,
    reply: {
      threadId,
      text: 'synthetic test input',
      attachmentIds: [],
      mode: 'queue',
      ...settings,
    },
  };
  return {
    session,
    ipc,
    thread,
    receipt,
    get addCalls() {
      return addCalls;
    },
    get saves() {
      return saves;
    },
    run: async () => {
      await session.enqueueNative(receipt);
      assert.equal(settingsRequests, 0, 'Queue admission only uses the existing verified read');
    },
  };
}
function refused(f) {
  assert.equal(f.addCalls, 0);
  assert.equal(f.receipt.state, 'failed');
  assert.equal(f.receipt.turnId, null);
  assert.equal(f.receipt.submissionState, 'not-submitted');
  assert.match(f.receipt.error, /原生队列沿用当前桌面设置/);
  assert.equal(f.receipt.nativeQueue, undefined);
}
function admittedToFixture(f) {
  assert.equal(
    f.addCalls,
    1,
    'matching current settings must reach admission, not stale stream rejection',
  );
  assert.equal(f.receipt.error, 'fixture-admission-boundary-no-write');
  assert.equal(f.receipt.turnId, null);
  assert.equal(f.receipt.submissionState, 'not-submitted');
}

test('current explicit null remains null despite xhigh in the active Turn', (t) => {
  t.diagnostic(
    `sessionSHA=${sha(session)}; desktopSHA=${sha(desktop)}; generationSHA=${sha(generation)}; methodBundleSHA=${sha(program)}`,
  );
  assert.deepEqual(desktopModelSettings(unknownStream()), { model, effort: null });
});
test('an explicit live effort remains known', () => {
  assert.deepEqual(desktopModelSettings({ ...unknownStream(), latestReasoningEffort: 'xhigh' }), {
    model,
    effort: 'xhigh',
  });
});
test('null thread setting overrides collaboration fallback, not replaced by Turn context', () => {
  assert.equal(
    desktopModelSettings({
      latestModel: model,
      latestThreadSettings: { effort: null },
      latestCollaborationMode: { settings: { model, reasoning_effort: 'xhigh' } },
      turns: [oldTurn],
    }).effort,
    null,
  );
});
test('known collaboration setting is used only without a newer setting', () => {
  assert.equal(
    desktopModelSettings({
      latestCollaborationMode: { settings: { model, reasoning_effort: 'xhigh' } },
    }).effort,
    'xhigh',
  );
});
test('unknown settings do not infer effort from an active Turn', () => {
  assert.equal(desktopModelSettings({ latestModel: model, turns: [oldTurn] }).effort, null);
});
test('explicit xhigh with null current projection is refused before queue admission', async () => {
  const f = fixture({ settings: { model, effort: 'xhigh', permissionMode: 'full-access' } });
  const original = structuredClone(f.receipt.reply);
  await f.run();
  refused(f);
  assert.deepEqual(f.receipt.reply, original);
});
test('omitted settings do not force an inherited native queue override', async () => {
  const f = fixture();
  await f.run();
  admittedToFixture(f);
});
test('matching xhigh current stream is not a blanket queue refusal', async () => {
  const f = fixture({ streamEffort: 'xhigh', settings: { effort: 'xhigh' } });
  await f.run();
  admittedToFixture(f);
});
test('fresh verified original-owner xhigh read agrees with current-settings admission', async () => {
  const f = fixture({ noOwnerRead: false, ownerEffort: 'xhigh', settings: { effort: 'xhigh' } });
  assert.equal(f.ipc.modelSettings(threadId).effort, 'xhigh');
  await f.run();
  admittedToFixture(f);
});
test('fresh explicit owner null must not fall back to stale stream xhigh', async () => {
  const f = fixture({
    noOwnerRead: false,
    ownerEffort: null,
    streamEffort: 'xhigh',
    settings: { effort: 'xhigh' },
  });
  assert.equal(f.ipc.modelSettings(threadId).effort, null);
  await f.run();
  refused(f);
});
test('expired current-owner cache cannot authorize inferred xhigh', async () => {
  const f = fixture({ noOwnerRead: false, ownerEffort: 'xhigh', settings: { effort: 'xhigh' } });
  f.ipc.currentModels.get(threadId).checkedAt = Date.now() - 10001;
  assert.equal(f.ipc.modelSettings(threadId), undefined);
  await f.run();
  refused(f);
});
test('changed owner cannot authorize a previous owner cache', async () => {
  const f = fixture({ noOwnerRead: false, ownerEffort: 'xhigh', settings: { effort: 'xhigh' } });
  f.ipc.owners.set(threadId, 'other-owner');
  assert.equal(f.ipc.modelSettings(threadId), undefined);
  await f.run();
  refused(f);
});
test('offline cache cannot authorize xhigh', async () => {
  const f = fixture({ noOwnerRead: false, ownerEffort: 'xhigh', settings: { effort: 'xhigh' } });
  f.ipc.ready = false;
  assert.equal(f.ipc.modelSettings(threadId), undefined);
  await f.run();
  refused(f);
});
test('permission, speed, model and collaboration guards stay exact', async () => {
  for (const settings of [
    { permissionMode: 'read-only' },
    { speed: 'fast' },
    { model: 'different-model' },
    { collaborationMode: 'plan' },
  ]) {
    const f = fixture({ settings });
    await f.run();
    refused(f);
  }
});
test('a model-settings read is discarded when stream state changes while pending', async () => {
  const ipc = new DesktopIpc();
  ipc.owners.set(threadId, 'owner');
  const oldState = unknownStream();
  ipc.states.set(threadId, { state: oldState });
  let requested;
  const entered = new Promise((r) => {
    requested = r;
  });
  let finish;
  const pending = new Promise((r) => {
    finish = r;
  });
  ipc.request = async (method) => {
    if (method === 'thread-owner-discovery')
      return { handledByClientId: 'owner', result: { supportsUntrustedAppInput: true } };
    assert.equal(method, 'thread-follower-read-model-settings');
    requested();
    await pending;
    return {
      handledByClientId: 'owner',
      method,
      result: { settings: { model, reasoningEffort: 'xhigh' } },
    };
  };
  const read = ipc.refreshModelSettings(threadId);
  await entered;
  ipc.states.set(threadId, { state: { ...oldState, updated: true } });
  finish();
  await read;
  assert.equal(ipc.modelSettings(threadId), undefined);
  assert.equal(ipc.modelReads.size, 0);
});
test('unchanged original owner/state read becomes current settings, including explicit null', async () => {
  for (const effort of ['xhigh', null]) {
    const ipc = new DesktopIpc();
    ipc.owners.set(threadId, 'owner');
    ipc.states.set(threadId, { state: unknownStream() });
    ipc.request = async (method) =>
      method === 'thread-owner-discovery'
        ? { handledByClientId: 'owner', result: { supportsUntrustedAppInput: true } }
        : {
            handledByClientId: 'owner',
            method: 'thread-follower-read-model-settings',
            result: { settings: { model, reasoningEffort: effort } },
          };
    await ipc.refreshModelSettings(threadId);
    assert.deepEqual(ipc.modelSettings(threadId), { model, effort });
  }
});
