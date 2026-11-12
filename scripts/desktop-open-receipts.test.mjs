import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setImmediate } from 'node:timers/promises';
import { test } from 'node:test';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';

const threadId = 'original-desktop-thread';
const operationId = 'explicit-desktop-open-receipt';

function fixture(t) {
  const root = mkdtempSync(join(tmpdir(), 'desktop-open-receipts-'));
  t.after(() => rmSync(root, { recursive: true, force: true }));
  const options = { mode: 'desktop-runtime', root, directory: join(root, 'state'), name: 'test' };
  const open = (navigate) => {
    const session = new RuntimeSession(options);
    session.desktopCreationDriver = { open: navigate, close() {} };
    session.open = async () => {};
    return session;
  };
  const disk = () => JSON.parse(readFileSync(join(options.directory, 'runtime.json'), 'utf8'));
  return { open, disk };
}
function remember(session) {
  session.rememberDesktopThread({
    id: threadId,
    hostId: session.hostId,
    projectId: null,
    title: 'Original test thread',
  });
}

test('durable open intent precedes navigation and the confirmed receipt survives restart', async (t) => {
  const { open, disk } = fixture(t);
  let navigations = 0;
  const first = open(async (actualThread, actualOperation) => {
    navigations++;
    assert.equal(actualThread, threadId);
    assert.equal(actualOperation, operationId);
    assert.deepEqual(disk().desktopOpenOperations, [
      {
        hostId: first.hostId,
        threadId,
        clientOperationId: operationId,
        phase: 'opening',
        error: null,
      },
    ]);
    return true;
  });
  try {
    remember(first);
    await first.activateDesktopThread(threadId, operationId);
    await setImmediate();
    assert.equal((await first.desktopOpenStatus(threadId, operationId)).phase, 'opened');
    assert.equal(disk().desktopOpenOperations[0].phase, 'opened');
  } finally {
    first.close();
  }
  const restarted = open(async () => assert.fail('A recorded open must not navigate again'));
  try {
    const receipt = await restarted.desktopOpenStatus(threadId, operationId);
    assert.equal(receipt.phase, 'opened');
    assert.equal(receipt.hostId, first.hostId);
    assert.deepEqual(await restarted.activateDesktopThread(threadId, operationId), receipt);
    await assert.rejects(
      restarted.activateDesktopThread('different-thread', operationId),
      (error) => error.status === 409,
    );
    await assert.rejects(
      restarted.desktopOpenStatus('different-thread', operationId),
      (error) => error.status === 404,
    );
    assert.equal(navigations, 1);
  } finally {
    restarted.close();
  }
});

test('an in-flight open at shutdown stays queryable without replay or a late write', async (t) => {
  const { open, disk } = fixture(t);
  let resolve;
  const pending = new Promise((done) => (resolve = done));
  let navigations = 0;
  const first = open(() => {
    navigations++;
    return pending;
  });
  remember(first);
  await first.activateDesktopThread(threadId, operationId);
  await setImmediate();
  first.close();
  resolve(true);
  await setImmediate();
  assert.equal(disk().desktopOpenOperations[0].phase, 'opening');
  const restarted = open(async () => assert.fail('Unknown navigation must never be replayed'));
  try {
    const receipt = await restarted.desktopOpenStatus(threadId, operationId);
    assert.equal(receipt.phase, 'outcome-unknown');
    assert.deepEqual(await restarted.activateDesktopThread(threadId, operationId), receipt);
    assert.equal(navigations, 1);
  } finally {
    restarted.close();
  }
});

test('a write failure never dispatches an unsaved open or acknowledges an unsaved result', async (t) => {
  const { open, disk } = fixture(t);
  let navigations = 0;
  const session = open(async () => {
    navigations++;
    return true;
  });
  const realSave = session.persistence.save.bind(session.persistence);
  try {
    remember(session);
    session.persistence.save = () => {
      throw Error('injected write failure');
    };
    await assert.rejects(session.activateDesktopThread(threadId, operationId));
    assert.equal(navigations, 0);
    session.persistence.save = (state) => {
      if (state.desktopOpenOperations?.some((op) => op.phase === 'opened'))
        throw Error('injected confirmation failure');
      return realSave(state);
    };
    await session.activateDesktopThread(threadId, operationId);
    await setImmediate();
    assert.equal(navigations, 1);
    assert.equal(disk().desktopOpenOperations[0].phase, 'opening');
    assert.equal((await session.desktopOpenStatus(threadId, operationId)).phase, 'outcome-unknown');
    await session.activateDesktopThread(threadId, operationId);
    assert.equal(navigations, 1);
  } finally {
    session.persistence.save = realSave;
    session.close();
  }
});
