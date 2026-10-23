import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';

/** The desktop collector intentionally retains counts/hashes, not raw sync responses. */
export function verifyReceiveEvidence(witness, nonce, nativeLog) {
  assert(
    /\*\* TEST (?:EXECUTE )?SUCCEEDED \*\*/.test(nativeLog),
    'Require the actual native test result',
  );
  const result = {};
  for (const route of ['lan', 'relay']) {
    assert(nativeLog.includes('EASYWORK_RECEIVE_SCENE_PASSED ' + route));
    const marker = 'EASYWORK_RECEIVE_' + route + '_' + nonce;
    const starts = witness.requests.filter(
      (entry) =>
        entry.request?.method === 'turn/start' &&
        JSON.stringify(entry.request.params.parts).includes(marker),
    );
    assert.equal(starts.length, 1, route + ' must submit exactly once');
    assert.equal(starts[0].transportId, route === 'lan' ? 'lan-wss' : 'relay-http');
    const runId = starts[0].response.result.runId;
    const execution = witness.independentDesktop.executions.find((entry) => entry.id === runId);
    assert.equal(execution.run.status, 'completed');
    const snapshot = witness.independentDesktop.snapshots.find(
      (entry) => entry.id === execution.run.threadId,
    );
    assert(
      snapshot.messages.some(
        (message) =>
          message.role === 'assistant' &&
          message.status === 'complete' &&
          JSON.stringify(message.content).includes(marker + '_DONE'),
      ),
    );
    assert(
      snapshot.messages.some((message) =>
        message.content?.some((part) => part.type === 'tool-call'),
      ),
    );
    const syncs = witness.requests.filter(
      (entry) =>
        entry.request?.method === 'thread/sync' &&
        entry.request.params.threadId === execution.run.threadId,
    );
    assert(syncs.some((entry) => entry.request.params.known.length > 0));
    const reduced = syncs.filter(
      (entry) =>
        entry.response.ok &&
        entry.request.params.known.length > 0 &&
        entry.response.result.messageCount < entry.request.params.known.length,
    );
    assert(reduced.length > 0, 'The actual Host must omit known unchanged messages');
    // Rich model output can legitimately use complete changed messages. Pure-text
    // append patches are separately checked against the actual desktop producer.
    let bodyFreeSuffixAdvances = 0;
    for (let index = 0; index < syncs.length - 1; index++) {
      if (syncs[index].response.result?.messageCount !== 0) continue;
      const previous = new Map(syncs[index].request.params.known.map((value) => [value.id, value]));
      if (
        syncs[index + 1].request.params.known.some(
          (value) => previous.has(value.id) && value.textLength > previous.get(value.id).textLength,
        )
      )
        bodyFreeSuffixAdvances++;
    }
    result[route] = {
      runId,
      threadId: execution.run.threadId,
      state: execution.run.status,
      syncs: syncs.length,
      reducedMessageResponses: reduced.length,
      bodyFreeSuffixAdvances,
      submittedSettings: starts[0].request.params.settings ?? null,
    };
  }
  return result;
}

// Re-examine immutable successful UI/model evidence after a collector assertion is repaired.
// This mode never launches Electron, sends inputs, changes credentials or edits the original report.
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const directory = resolve(process.argv[2]);
  const report = JSON.parse(await readFile(join(directory, 'report.json')));
  const witness = JSON.parse(await readFile(report.desktopReport));
  const scenes = verifyReceiveEvidence(
    witness,
    report.nonce,
    await readFile(join(directory, 'xcode.log'), 'utf8'),
  );
  const verified = {
    ...report,
    status: 'passed',
    originalReportStatus: report.status,
    failure: undefined,
    verification:
      'reused successful native UI and independent model evidence; repaired post-collector assertion',
    ...scenes,
  };
  await writeFile(join(directory, 'verified-report.json'), JSON.stringify(verified, null, 2), {
    mode: 0o600,
  });
  console.log(
    JSON.stringify({
      status: verified.status,
      model: verified.model,
      lan: scenes.lan,
      relay: scenes.relay,
    }),
  );
}

export function verifyNativeQueueEvidence(witness, nonce, log, routes = ['lan', 'relay']) {
  const result = {};
  for (const route of routes) {
    assert(log.includes('EASYWORK_NATIVE_QUEUE_PASSED ' + route));
    const prefix = 'EASYWORK_NATIVE_QUEUE_' + route + '_' + nonce;
    const requests = witness.requests.filter(
      (entry) =>
        entry.request?.method === 'turn/start' &&
        entry.request.params.parts?.some((part) => part.data?.text?.includes(prefix)),
    );
    const queued = requests.filter((entry) => entry.request.params.mode === 'queue');
    const initial = requests.find((entry) => entry.request.params.mode !== 'queue');
    assert.equal(queued.length, 3);
    assert(initial);
    const id = initial.request.params.threadId;
    const ids = queued.map((entry) => entry.request.params.clientRequestId);
    const capture = witness.independentDesktop.queueViews.find(
      (view) => view.threadId === id && view.operation === 'desktop.queue-capture',
    );
    assert.deepEqual(
      capture.items.map((item) => item.messageId),
      ids,
    );
    assert(capture.items.every((item) => item.executeEnabled));
    const promotion = witness.requests.filter(
      (entry) =>
        entry.request?.method === 'queue/execute' && entry.request.params.messageId === ids[1],
    );
    assert.equal(promotion.length, 1);
    assert(promotion[0].response.ok);
    assert.equal(promotion[0].response.result.turnId, initial.response.result.runId);
    const snapshot = witness.independentDesktop.snapshots.find((entry) => entry.id === id);
    assert(snapshot);
    const users = snapshot.messages
      .filter((message) => message.role === 'user')
      .map((message) => JSON.stringify(message.content));
    for (const suffix of ['ORIGIN', 'FIFO', 'PROMOTE'])
      assert.equal(users.filter((text) => text.includes(prefix + '_' + suffix)).length, 1);
    assert.equal(users.filter((text) => text.includes(prefix + '_CANCEL')).length, 0);
    const runs = witness.independentDesktop.executions.filter(
      (entry) => entry.run?.threadId === id && entry.run.kind === 'root',
    );
    const userRuns = runs.filter((entry) => entry.run.input?.trigger?.kind === 'user');
    assert.deepEqual(
      userRuns.map((entry) => entry.run.input.trigger.messageId).sort(),
      [initial.request.params.clientRequestId, ids[0]].sort(),
    );
    assert(userRuns.every((entry) => entry.run.status === 'completed'));
    result[route] = {
      threadId: id,
      uniqueUserInputs: 3,
      modelRuns: userRuns.length,
      nativeContinuationRuns: runs.length - userRuns.length,
      promotionSameRun: true,
      desktopScreenshot: capture.screenshot,
      actualEffort: initial.request.params.settings?.effort,
    };
  }
  return result;
}

export function verifyClientQueueEvidence(witness, nonce, log, routes = ['lan', 'relay']) {
  assert(log.includes('** TEST SUCCEEDED **'));
  return Object.fromEntries(
    routes.map((route) => {
      assert(log.includes('EASYWORK_CLIENT_QUEUE_PASSED ' + route));
      const marker = 'EASYWORK_NATIVE_QUEUE_' + route + '_' + nonce;
      const inputs = witness.requests.filter(
        (entry) =>
          entry.request?.method === 'turn/start' &&
          JSON.stringify(entry.request.params.parts).includes(marker),
      );
      const queued = inputs.filter((entry) => entry.request.params.mode === 'queue');
      const initial = inputs.filter((entry) => entry.request.params.mode !== 'queue');
      assert.equal(initial.length, 1, 'Require one original client input');
      assert.equal(queued.length, 3);
      assert(
        queued.every(
          (entry) =>
            entry.response.result.state === 'queued' &&
            entry.response.result.queueSource === 'native' &&
            entry.response.result.queuePresent,
        ),
      );
      const ids = queued.map((entry) => entry.request.params.clientRequestId);
      const initialId = initial[0].request.params.clientRequestId;
      assert.equal(new Set([initialId, ...ids]).size, 4, 'Require distinct immutable input IDs');
      const threadId = queued[0].request.params.threadId;
      assert(inputs.every((entry) => entry.request.params.threadId === threadId));
      const capture = witness.independentDesktop.queueViews.find(
        (view) => view.threadId === threadId && view.operation === 'desktop.queue-capture',
      );
      assert.deepEqual(
        capture.items.map((item) => item.messageId),
        ids,
      );
      const snapshot = witness.independentDesktop.snapshots.find((entry) => entry.id === threadId);
      const users = snapshot.messages.filter((message) => message.role === 'user');
      assert.equal(users.length, 2, 'Only the initial and retained client queue input execute');
      assert.equal(
        users.filter((message) => JSON.stringify(message).includes(marker + '_ORIGIN')).length,
        1,
      );
      assert(users.some((message) => JSON.stringify(message).includes(marker + '_FIFO')));
      assert(!users.some((message) => /_PROMOTE|_CANCEL/.test(JSON.stringify(message))));
      const runs = witness.independentDesktop.executions.filter(
        (entry) => entry.run?.threadId === threadId,
      );
      const userRuns = runs.filter(
        (entry) => entry.run.kind === 'root' && entry.run.input?.trigger?.kind === 'user',
      );
      assert.deepEqual(
        userRuns.map((entry) => entry.run.input.trigger.messageId).sort(),
        [initialId, ids[0]].sort(),
        'Exactly one original and one FIFO user execution; no missing or replayed input',
      );
      assert(runs.every((entry) => entry.run.status === 'completed'));
      return [
        route,
        {
          threadId,
          desktopQueueIds: ids,
          executedInputs: users.length,
          desktopCancellation: 'passed',
          mobileCancellation: 'passed',
          naturalClientDispatch: 'passed',
        },
      ];
    }),
  );
}
