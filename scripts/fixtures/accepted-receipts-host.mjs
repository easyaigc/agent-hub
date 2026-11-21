// Task-only child controller. It never exposes these controls on the product HTTP API.
import assert from 'node:assert/strict';
import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { omitOldTurn } from './accepted-receipts-projection.mjs';
import { RuntimeSession } from '../../packages/codex-adapter/dist/runtime-session.js';
import { CodexHistoryReader } from '../../packages/codex-adapter/dist/history-reader.js';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';

const threadId = process.env.RECEIPT_TEST_THREAD;
assert(threadId && process.send, 'Require the task-owned parent and exact thread');
const hiddenTurn = process.env.RECEIPT_TEST_HIDDEN_TURN;
let release;
const gate =
  process.env.RECEIPT_TEST_HOLD === '1'
    ? new Promise((done) => {
        release = done;
      })
    : Promise.resolve();
let summaryReads = 0;
let summaryObservation;
let metadataHistoryMode;
const readerRpc = CodexHistoryReader.prototype.rpc;
CodexHistoryReader.prototype.rpc = async function (method, params) {
  const result = await readerRpc.call(this, method, params);
  if (method === 'thread/read' && params?.threadId === threadId && params.includeTurns === false)
    metadataHistoryMode = result?.thread?.historyMode ?? null;
  return result;
};
const summarize = CodexHistoryReader.prototype.receiptTurnEvidence;
CodexHistoryReader.prototype.receiptTurnEvidence = async function (...args) {
  if (args[0] !== threadId) return summarize.apply(this, args);
  summaryReads++;
  await gate;
  try {
    const result = await summarize.apply(this, args);
    summaryObservation = {
      requested: args[1],
      turns: result.map((turn) => ({ id: turn.id, status: turn.status })),
    };
    return result;
  } catch (error) {
    summaryObservation = { error: error.message };
    throw error;
  }
};
if (hiddenTurn) {
  const project = RuntimeSession.prototype.desktopSnapshot;
  RuntimeSession.prototype.desktopSnapshot = function (thread, state, ...args) {
    return project.call(
      this,
      thread,
      thread.id === threadId ? omitOldTurn(state, hiddenTurn) : state,
      ...args,
    );
  };
  const readHistory = CodexHistoryReader.prototype.history;
  CodexHistoryReader.prototype.history = async function (...args) {
    const page = await readHistory.apply(this, args);
    if (args[0] !== threadId) return page;
    return {
      ...page,
      messages: page.messages.filter((message) => message.runId !== hiddenTurn),
      turns: page.turns.filter((turn) => turn.id !== hiddenTurn),
      ...(page.latestTurn?.id === hiddenTurn ? { latestTurn: undefined } : {}),
    };
  };
}
// Install the task-only read boundary before connect() can publish its first snapshot.
const service = await startRuntimeServer();
const session = service.session;
assert(
  session instanceof RuntimeSession,
  'The tested Host must use the exact patched runtime module',
);
assert(
  session.runtime.reader instanceof CodexHistoryReader,
  'The tested Host must use the exact patched reader module',
);
let showUnknown = false;
const history = session.history.bind(session);
session.history = (...args) => {
  const snapshot = history(...args);
  if (args[0] === threadId && showUnknown)
    snapshot.runtime.receipts.push({
      hostId: session.hostId,
      threadId,
      clientMessageId: 'synthetic-unknown-ui-control',
      state: 'uncertain',
      turnId: null,
      error: null,
      text: 'Synthetic unknown presentation control; never submitted',
    });
  return snapshot;
};
await session.workspace();
await session.open(threadId);
await session.refreshHistory(threadId, undefined, true);
const status = () => ({
  pid: process.pid,
  hostId: session.hostId,
  ready: session.ready,
  frozenBuild: globalThis.__acceptedReceiptsFrozenBuild,
  frozenLoaded: Object.keys(globalThis.__acceptedReceiptsFrozenLoaded ?? {}),
  summaryReads,
  summaryObservation,
  metadataHistoryMode,
  receipts: session.state.receipts
    .filter((r) => r.threadId === threadId)
    .map((r) => ({
      id: r.clientMessageId,
      state: r.state,
      turnId: r.turnId,
      acceptedTurnId: r.acceptedTurnId,
    })),
});
process.send({ type: 'ready', port: service.server.address().port, ...status() });
let closing = false;
process.on('message', async ({ requestId, operation, reply, enabled }) => {
  if (closing) return;
  try {
    let result;
    if (operation === 'status') {
      await session.refreshHistory(threadId, undefined, true);
      result = status();
    } else if (operation === 'submit') {
      assert.equal(reply.threadId, threadId);
      assert.equal(reply.hostId, session.hostId);
      result = await session.submit(reply);
    } else if (operation === 'disconnect') {
      // Destroy only this follower's actual socket, not the original desktop or formal Host.
      session.runtime.ipc.close();
      await delay(80);
      result = status();
    } else if (operation === 'reconcile') {
      release?.();
      release = undefined;
      result = status();
    } else if (operation === 'unknown') {
      showUnknown = enabled === true;
      session.emit(threadId, 'synthetic-unknown-presentation');
      result = status();
    } else if (operation === 'close') {
      closing = true;
      release?.();
      await writeFile(
        join(process.env.HOST_AGENT_STATE_DIR, 'task-receipts.json'),
        JSON.stringify(
          session.state.receipts.filter((r) => r.threadId === threadId),
          null,
          2,
        ),
        { mode: 0o600 },
      );
      result = status();
      await service.close();
    } else throw Error('Unsupported task control');
    process.send({ requestId, result }, () => {
      if (closing) process.exit(0);
    });
  } catch (error) {
    process.send({ requestId, error: error.message });
  }
});
