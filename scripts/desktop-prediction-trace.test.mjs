import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { DesktopPredictionTraceReader } from '../packages/codex-adapter/dist/desktop-prediction-trace.js';

import {
  fixtures,
  writeJson,
  setup,
  execution,
  bundle,
  recoverableFixture,
} from './fixtures/desktop-trace.mjs';

test('source recovery excludes previous desktop bundles before candidate ambiguity', () =>
  setup((root) => {
    const a = recoverableFixture(root);
    const b = recoverableFixture(
      root,
      execution({ sourceThreadId: a.fixture.sourceThreadId, sourceTurnId: a.fixture.sourceTurnId }),
    );
    b.fixture.manifest.started_at_unix_ms = 2;
    writeJson(join(b.fixture.directory, 'manifest.json'), b.fixture.manifest);
    const reader = new DesktopPredictionTraceReader(root);
    const ids = [a.fixture.sourceThreadId, a.fixture.sourceTurnId, root];
    assert.equal(reader.readSourcePredictions(...ids).length, 2);
    assert.equal(reader.readSourcePredictions(...ids, 2).length, 1);
    assert.equal(
      reader.readSourcePredictions(...ids, 2)[0].binding.predictionThreadId,
      b.fixture.predictionThreadId,
    );
    assert.equal(reader.readSourcePredictions(...ids, 3).length, 0);
    assert.equal(reader.readSourcePredictions(...ids, NaN), null);
  }));

test('source recovery reads durable native completion without a notifier and rechecks changes', () =>
  setup((root) => {
    const a = recoverableFixture(root);
    const b = recoverableFixture(root);
    const reader = new DesktopPredictionTraceReader(root);
    const read = () =>
      reader.readSourcePredictions(a.fixture.sourceThreadId, a.fixture.sourceTurnId, root);
    assert.equal(read().length, 1);
    assert.equal(read()[0].text, 'Synthetic follow-up suggestion.');
    assert.equal(read()[0].binding.predictionThreadId, a.fixture.predictionThreadId);
    assert.equal(
      reader.readSourcePredictions(b.fixture.sourceThreadId, b.fixture.sourceTurnId, root).length,
      1,
    );
    assert.equal(
      reader.readSourcePredictions(a.fixture.sourceThreadId, randomUUID(), root).length,
      0,
    );
    a.terminal.value.last_agent_message = '{"suggestion":"contradiction"}';
    writeJson(a.terminal.path, a.terminal.value);
    assert.equal(read(), null);
  }));

test('discovery filesystem validation grows linearly with unrelated trace bundles', () => {
  const checks = (count) =>
    setup((root) => {
      for (let index = 0; index < count; index++) {
        const unrelated = bundle(root);
        unrelated.protocolEvent('session_configured', {
          type: 'session_configured',
          thread_source: 'vscode',
        });
      }
      const a = recoverableFixture(root);
      const original = fs.lstatSync;
      let calls = 0;
      fs.lstatSync = (...args) => {
        if (String(args[0]).startsWith(root)) calls++;
        return original(...args);
      };
      syncBuiltinESMExports();
      try {
        const result = new DesktopPredictionTraceReader(root).readSourcePredictions(
          a.fixture.sourceThreadId,
          a.fixture.sourceTurnId,
          root,
        );
        assert.equal(result?.length, 1);
        return calls;
      } finally {
        fs.lstatSync = original;
        syncBuiltinESMExports();
      }
    });
  const small = checks(32),
    large = checks(96);
  assert(
    large < small * 4.5,
    `Tripling unrelated traces must not cause quadratic metadata checks: ${small} -> ${large}`,
  );
});

test('recovery preserves all same-source candidates and rejects forged markers, paths and unfinished tails', () =>
  setup((root) => {
    const a = recoverableFixture(root);
    recoverableFixture(
      root,
      execution({ sourceThreadId: a.fixture.sourceThreadId, sourceTurnId: a.fixture.sourceTurnId }),
    );
    const reader = new DesktopPredictionTraceReader(root);
    const read = () =>
      reader.readSourcePredictions(a.fixture.sourceThreadId, a.fixture.sourceTurnId, root);
    assert.equal(read().length, 2, 'The caller must suppress ambiguous predictions');
    a.request.request.input[0].content[0].text = 'Ordinary user request';
    writeJson(a.request.requestPath, a.request.request);
    assert.equal(read(), null);
    a.request.request.input[0].content[0].text =
      'This is an app-generated request for a suggested next user message. Synthetic fixture.';
    writeJson(a.request.requestPath, a.request.request);
    a.configured.value.cwd = tmpdir();
    writeJson(a.configured.path, a.configured.value);
    assert.equal(read(), null);
    a.configured.value.cwd = root;
    writeJson(a.configured.path, a.configured.value);
    fs.appendFileSync(a.fixture.logPath, '{"incomplete":');
    assert.equal(read(), null);
  }));

test('recovery tolerates an unrelated large log appending, but detects changed discovery bytes', () =>
  setup((root) => {
    const unrelated = bundle(root);
    unrelated.protocolEvent('session_configured', {
      type: 'session_configured',
      thread_source: 'vscode',
    });
    fs.appendFileSync(unrelated.logPath, 'x'.repeat(9 * 1024 * 1024));
    const a = recoverableFixture(root);
    const reader = new DesktopPredictionTraceReader(root);
    const original = fs.readSync;
    let mutate = () => {
      fs.appendFileSync(unrelated.logPath, 'appended tail');
      fs.mkdirSync(join(unrelated.directory, 'payloads', 'unrelated-new-directory'));
    };
    let changed = false;
    fs.readSync = function (...args) {
      const count = original(...args);
      if (!changed && args[1].includes(Buffer.from('This is an app-generated request'))) {
        changed = true;
        mutate();
      }
      return count;
    };
    syncBuiltinESMExports();
    try {
      assert.equal(
        reader.readSourcePredictions(a.fixture.sourceThreadId, a.fixture.sourceTurnId, root).length,
        1,
      );
      assert(changed);
      changed = false;
      mutate = () => {
        const fd = fs.openSync(unrelated.logPath, 'r+');
        try {
          fs.writeSync(fd, Buffer.from('!'), 0, 1, 0);
        } finally {
          fs.closeSync(fd);
        }
      };
      assert.equal(
        reader.readSourcePredictions(a.fixture.sourceThreadId, a.fixture.sourceTurnId, root),
        null,
      );
      assert(changed);
    } finally {
      fs.readSync = original;
      syncBuiltinESMExports();
    }
  }));

function read(root, fixture) {
  return new DesktopPredictionTraceReader(root).readBinding(
    fixture.predictionThreadId,
    fixture.predictionTurnId,
  );
}

function readPrediction(root, fixture) {
  return new DesktopPredictionTraceReader(root).readPrediction(
    fixture.predictionThreadId,
    fixture.predictionTurnId,
  );
}

function completedFixture(root, options) {
  const fixture = bundle(root);
  const request = fixture.addRequest();
  const completion = fixture.addResponse(request, options);
  const ending = fixture.endTurn();
  return { fixture, request, completion, ending };
}

test('synthetic interleaved HTTP/WS predictions bind only their four exact IDs, without request disclosure', () =>
  setup((root) => {
    const a = bundle(root);
    const b = bundle(root);
    const first = a.addRequest();
    b.addRequest({ ws: true });
    a.addRequest({ ws: true });
    b.addRequest();
    for (const fixture of [b, a]) {
      const result = read(root, fixture);
      assert.equal(result.sourceThreadId, fixture.sourceThreadId);
      assert.equal(result.sourceTurnId, fixture.sourceTurnId);
      assert.equal(result.predictionThreadId, fixture.predictionThreadId);
      assert.equal(result.predictionTurnId, fixture.predictionTurnId);
      assert.equal(result.evidence.requests.length, 2);
      assert.equal(result.evidence.schemaVersion, 1);
      assert(!JSON.stringify(result).includes(root));
      assert(!JSON.stringify(result).includes(fixtures.synthetic.requestBody));
    }
    assert.equal(
      read(root, a).evidence.requests[0].requestPayloadSha256,
      createHash('sha256').update(fs.readFileSync(first.requestPath)).digest('hex'),
    );
  }));

test('consistent duplicate requests across bundles are allowed; conflicting source is never cached', () =>
  setup((root) => {
    const a = bundle(root);
    a.addRequest();
    const duplicate = bundle(root, a);
    duplicate.addRequest();
    const reader = new DesktopPredictionTraceReader(root);
    assert.equal(
      reader.readBinding(a.predictionThreadId, a.predictionTurnId).evidence.requests.length,
      2,
    );
    duplicate.addRequest({ metadata: { prediction_source_turn_id: randomUUID() } });
    assert.equal(reader.readBinding(a.predictionThreadId, a.predictionTurnId), null);
  }));

test('a conflicting source thread also rejects the whole execution', () =>
  setup((root) => {
    const a = bundle(root);
    a.addRequest();
    const otherSource = randomUUID();
    a.addRequest({
      metadata: { prediction_source_thread_id: otherSource, forked_from_thread_id: otherSource },
    });
    assert.equal(read(root, a), null);
  }));

test('unrelated threads and turns do not open their request payloads', () =>
  setup((root) => {
    const a = bundle(root);
    a.addRequest();
    const otherTurn = a.addRequest({ idOverride: { predictionTurnId: randomUUID() } });
    fs.unlinkSync(otherTurn.requestPath);
    fs.symlinkSync('/definitely-not-a-request', otherTurn.requestPath);
    const unrelated = bundle(root);
    fs.writeFileSync(join(unrelated.directory, 'manifest.json'), 'invalid unrelated manifest');
    assert.equal(read(root, a).sourceThreadId, a.sourceThreadId);
  }));

test('complete lines bind; an incomplete first event cannot bind and incomplete conflicts wait for newline', () =>
  setup((root) => {
    const a = bundle(root);
    a.addRequest();
    const complete = fs.readFileSync(a.logPath, 'utf8');
    fs.writeFileSync(a.logPath, complete.slice(0, -1));
    assert.equal(read(root, a), null);
    fs.writeFileSync(a.logPath, complete);
    const conflict = a.addRequest({ metadata: { prediction_source_turn_id: randomUUID() } });
    a.events.pop();
    a.saveEvents(JSON.stringify(conflict.event));
    assert.equal(read(root, a).sourceTurnId, a.sourceTurnId);
    fs.appendFileSync(a.logPath, '\n');
    assert.equal(read(root, a), null);
  }));

const mutations = [
  [
    'manifest schema',
    (a) => {
      a.manifest.schema_version = 2;
    },
  ],
  [
    'manifest root',
    (a) => {
      a.manifest.root_thread_id = randomUUID();
    },
  ],
  [
    'manifest trace ID',
    (a) => {
      a.manifest.trace_id = randomUUID();
    },
  ],
  [
    'manifest event path',
    (a) => {
      a.manifest.raw_event_log = '../trace.jsonl';
    },
  ],
  [
    'event schema',
    (a, r) => {
      r.event.schema_version = 2;
    },
  ],
  [
    'event rollout',
    (a, r) => {
      r.event.rollout_id = randomUUID();
    },
  ],
  [
    'event sequence',
    (a, r) => {
      r.event.seq += 1;
    },
  ],
  [
    'envelope thread',
    (a, r) => {
      r.event.thread_id = randomUUID();
    },
  ],
  [
    'envelope turn',
    (a, r) => {
      r.event.codex_turn_id = randomUUID();
    },
  ],
  [
    'payload thread',
    (a, r) => {
      r.event.payload.thread_id = randomUUID();
    },
  ],
  [
    'payload turn',
    (a, r) => {
      r.event.payload.codex_turn_id = randomUUID();
    },
  ],
  [
    'payload type',
    (a, r) => {
      r.event.payload.type = 'inference_completed';
    },
  ],
  [
    'payload kind',
    (a, r) => {
      r.event.payload.request_payload.kind.type = 'inference_response';
    },
  ],
  [
    'payload ref traversal',
    (a, r) => {
      r.event.payload.request_payload.path = '../../private.json';
    },
  ],
  [
    'payload ref absolute',
    (a, r) => {
      r.event.payload.request_payload.path = r.requestPath;
    },
  ],
  [
    'payload ref ordinal',
    (a, r) => {
      r.event.payload.request_payload.raw_payload_id = 'raw_payload:2';
    },
  ],
  [
    'client thread',
    (a, r) => {
      r.request.client_metadata.thread_id = randomUUID();
    },
  ],
  [
    'client turn',
    (a, r) => {
      r.request.client_metadata.turn_id = randomUUID();
    },
  ],
  [
    'client session',
    (a, r) => {
      r.request.client_metadata.session_id = randomUUID();
    },
  ],
  [
    'inner thread',
    (a, r) => {
      r.metadata.thread_id = randomUUID();
    },
  ],
  [
    'inner turn',
    (a, r) => {
      r.metadata.turn_id = randomUUID();
    },
  ],
  [
    'inner session',
    (a, r) => {
      r.metadata.session_id = randomUUID();
    },
  ],
  [
    'request kind',
    (a, r) => {
      r.metadata.request_kind = 'prewarm';
    },
  ],
  [
    'missing source turn',
    (a, r) => {
      delete r.metadata.prediction_source_turn_id;
    },
  ],
  [
    'bad source UUID',
    (a, r) => {
      r.metadata.prediction_source_thread_id = 'guessed-thread';
    },
  ],
  [
    'source equals prediction',
    (a, r) => {
      r.metadata.prediction_source_turn_id = a.predictionTurnId;
    },
  ],
  [
    'fork mismatch',
    (a, r) => {
      r.metadata.forked_from_thread_id = randomUUID();
    },
  ],
];
for (const [label, mutate] of mutations) {
  test(`synthetic ${label} cannot bind`, () =>
    setup((root) => {
      const a = bundle(root);
      const request = a.addRequest();
      mutate(a, request);
      request.request.client_metadata['x-codex-turn-metadata'] = JSON.stringify(request.metadata);
      writeJson(request.requestPath, request.request);
      writeJson(join(a.directory, 'manifest.json'), a.manifest);
      a.saveEvents();
      assert.equal(read(root, a), null);
    }));
}

test('missing optional fork metadata is allowed, but is never substituted for missing source IDs', () =>
  setup((root) => {
    const a = bundle(root);
    const request = a.addRequest({ metadata: { forked_from_thread_id: undefined } });
    assert.equal(read(root, a).sourceThreadId, a.sourceThreadId);
    delete request.metadata.prediction_source_thread_id;
    request.request.client_metadata['x-codex-turn-metadata'] = JSON.stringify(request.metadata);
    writeJson(request.requestPath, request.request);
    assert.equal(read(root, a), null);
  }));

for (const location of ['root', 'bundle', 'payloads', 'manifest', 'events', 'request']) {
  test(`unsafe permissions on ${location} reject binding`, () =>
    setup((root) => {
      const a = bundle(root);
      const request = a.addRequest();
      const path = {
        root,
        bundle: a.directory,
        payloads: join(a.directory, 'payloads'),
        manifest: join(a.directory, 'manifest.json'),
        events: a.logPath,
        request: request.requestPath,
      }[location];
      fs.chmodSync(
        path,
        location === 'root' ? 0o755 : fs.statSync(path).isDirectory() ? 0o775 : 0o664,
      );
      assert.equal(read(root, a), null);
    }));
  test(`symlink ${location} rejects binding`, () =>
    setup((root) => {
      const a = bundle(root);
      const request = a.addRequest();
      const path = {
        root,
        bundle: a.directory,
        payloads: join(a.directory, 'payloads'),
        manifest: join(a.directory, 'manifest.json'),
        events: a.logPath,
        request: request.requestPath,
      }[location];
      const moved = path + '-original';
      fs.renameSync(path, moved);
      fs.symlinkSync(moved, path);
      try {
        assert.equal(read(root, a), null);
      } finally {
        fs.unlinkSync(path);
        fs.renameSync(moved, path);
      }
    }));
}

test('native 0755/0644 descendants bind inside a continuously private 0700 root', () =>
  setup((root) => {
    const a = bundle(root);
    const request = a.addRequest({ ws: true });
    for (const path of [a.directory, join(a.directory, 'payloads')]) fs.chmodSync(path, 0o755);
    for (const path of [join(a.directory, 'manifest.json'), a.logPath, request.requestPath])
      fs.chmodSync(path, 0o644);
    assert.equal(read(root, a).sourceThreadId, a.sourceThreadId);
    assert(!JSON.stringify(read(root, a)).includes(fixtures.synthetic.requestBody));

    const originalRead = fs.readSync;
    let changed = false;
    fs.readSync = function (...args) {
      const result = originalRead(...args);
      if (!changed && args[1].includes(fixtures.synthetic.requestBody)) {
        changed = true;
        fs.chmodSync(root, 0o755);
      }
      return result;
    };
    syncBuiltinESMExports();
    try {
      assert.equal(read(root, a), null);
      assert(changed);
    } finally {
      fs.readSync = originalRead;
      syncBuiltinESMExports();
      fs.chmodSync(root, 0o700);
    }
  }));

test('other-writable descendants and special permission bits remain invalid inside a private root', () =>
  setup((root) => {
    const a = bundle(root);
    const request = a.addRequest();
    for (const mode of [0o646, 0o4644]) {
      fs.chmodSync(request.requestPath, mode);
      assert.equal(read(root, a), null);
    }
    fs.chmodSync(request.requestPath, 0o644);
    for (const mode of [0o757, 0o1755]) {
      fs.chmodSync(join(a.directory, 'payloads'), mode);
      assert.equal(read(root, a), null);
    }
  }));

test('hardlinked requests, oversized payloads and malformed complete events are rejected', () =>
  setup((root) => {
    const a = bundle(root);
    const request = a.addRequest();
    const alias = join(root, 'hardlink');
    fs.linkSync(request.requestPath, alias);
    assert.equal(read(root, a), null);
    fs.unlinkSync(alias);
    fs.truncateSync(request.requestPath, 33 * 1024 * 1024);
    assert.equal(read(root, a), null);
    writeJson(request.requestPath, request.request);
    fs.appendFileSync(a.logPath, '{broken}\n');
    assert.equal(read(root, a), null);
  }));

test('an event log changed while its request is read cannot produce an atomic binding', () =>
  setup((root) => {
    const a = bundle(root);
    a.addRequest();
    const originalRead = fs.readSync;
    let changed = false;
    fs.readSync = function (...args) {
      const result = originalRead(...args);
      if (!changed && args[1].includes(fixtures.synthetic.requestBody)) {
        changed = true;
        fs.appendFileSync(a.logPath, '{');
      }
      return result;
    };
    syncBuiltinESMExports();
    try {
      assert.equal(read(root, a), null);
      assert(changed);
    } finally {
      fs.readSync = originalRead;
      syncBuiltinESMExports();
    }
  }));

test('foreign file ownership is rejected before opening a request', () =>
  setup((root) => {
    const a = bundle(root);
    const request = a.addRequest();
    const originalStat = fs.lstatSync;
    const originalRead = fs.readSync;
    let requestRead = false;
    fs.lstatSync = function (...args) {
      const result = originalStat(...args);
      if (args[0] === request.requestPath) result.uid += 1n;
      return result;
    };
    fs.readSync = function (...args) {
      const result = originalRead(...args);
      if (args[1].includes(fixtures.synthetic.requestBody)) requestRead = true;
      return result;
    };
    syncBuiltinESMExports();
    try {
      assert.equal(read(root, a), null);
      assert.equal(requestRead, false);
    } finally {
      fs.lstatSync = originalStat;
      fs.readSync = originalRead;
      syncBuiltinESMExports();
    }
  }));

test('invalid input, missing root and canonical-root aliases return null', () =>
  setup((root) => {
    const a = bundle(root);
    a.addRequest();
    assert.equal(
      new DesktopPredictionTraceReader(root + '/.').readBinding(
        a.predictionThreadId,
        a.predictionTurnId,
      ),
      null,
    );
    assert.equal(
      new DesktopPredictionTraceReader(root + '/missing').readBinding(
        a.predictionThreadId,
        a.predictionTurnId,
      ),
      null,
    );
    assert.equal(
      new DesktopPredictionTraceReader(root).readBinding('../thread', a.predictionTurnId),
      null,
    );
  }));

test('native-shaped completion returns only the bound suggestion, completion time and digest', () =>
  setup((root) => {
    const { fixture, completion } = completedFixture(root, { text: '  Synthetic\n建议 😀  ' });
    for (const path of [fixture.directory, join(fixture.directory, 'payloads')])
      fs.chmodSync(path, 0o755);
    for (const name of fs.readdirSync(join(fixture.directory, 'payloads')))
      fs.chmodSync(join(fixture.directory, 'payloads', name), 0o644);
    const result = readPrediction(root, fixture);
    assert.deepEqual(result.binding, read(root, fixture));
    assert.equal(result.text, '  Synthetic\n建议 😀  ');
    assert.equal(result.completedAt, '1970-01-01T00:00:00.003Z');
    assert.equal(
      result.responsePayloadSha256,
      createHash('sha256').update(fs.readFileSync(completion.responsePath)).digest('hex'),
    );
    assert.deepEqual(Object.keys(result).sort(), [
      'binding',
      'completedAt',
      'responsePayloadSha256',
      'text',
    ]);
    for (const privateValue of [
      root,
      fixtures.synthetic.requestBody,
      fixtures.synthetic.responseMetadata,
      completion.response.response_id,
    ])
      assert(!JSON.stringify(result).includes(privateValue));
  }));

test('interleaved completed predictions remain bound to their own request and response', () =>
  setup((root) => {
    const a = bundle(root);
    const b = bundle(root);
    const ar = a.addRequest();
    const br = b.addRequest({ ws: true });
    b.addResponse(br, { text: 'Second synthetic suggestion.' });
    a.addResponse(ar, { text: 'First synthetic suggestion.' });
    a.endTurn();
    b.endTurn();
    assert.equal(readPrediction(root, a).text, 'First synthetic suggestion.');
    assert.equal(readPrediction(root, b).text, 'Second synthetic suggestion.');
    assert.equal(readPrediction(root, a).binding.sourceTurnId, a.sourceTurnId);
    assert.equal(readPrediction(root, b).binding.sourceTurnId, b.sourceTurnId);
  }));

test('response reading waits for the complete response and successful turn ending', () =>
  setup((root) => {
    const fixture = bundle(root);
    const request = fixture.addRequest();
    assert.equal(readPrediction(root, fixture), null);
    const completion = fixture.addResponse(request);
    assert.equal(readPrediction(root, fixture), null);
    fixture.endTurn();
    assert(readPrediction(root, fixture));
    const complete = fs.readFileSync(fixture.logPath, 'utf8');
    fs.writeFileSync(fixture.logPath, complete.slice(0, -1));
    assert.equal(readPrediction(root, fixture), null);
    assert(read(root, fixture));
    fs.writeFileSync(fixture.logPath, complete);
    fs.appendFileSync(fixture.logPath, '{');
    assert.equal(readPrediction(root, fixture), null);
    assert(read(root, fixture));
    fs.writeFileSync(fixture.logPath, complete);
    fs.writeFileSync(completion.responsePath, '{"output_items":');
    assert.equal(readPrediction(root, fixture), null);
    writeJson(completion.responsePath, completion.response);
    assert(readPrediction(root, fixture));
  }));

test('a completed response never substitutes for changed source metadata', () =>
  setup((root) => {
    const { fixture, request } = completedFixture(root);
    assert(readPrediction(root, fixture));
    request.metadata.prediction_source_turn_id = fixture.predictionTurnId;
    request.request.client_metadata['x-codex-turn-metadata'] = JSON.stringify(request.metadata);
    writeJson(request.requestPath, request.request);
    assert.equal(readPrediction(root, fixture), null);
  }));

const completionMutations = [
  [
    'completion inference ID',
    ({ completion }) => (completion.event.payload.inference_call_id = randomUUID()),
  ],
  ['completion thread', ({ completion }) => (completion.event.thread_id = randomUUID())],
  ['completion turn', ({ completion }) => (completion.event.codex_turn_id = randomUUID())],
  [
    'completion contradictory inner thread',
    ({ completion }) => (completion.event.payload.thread_id = randomUUID()),
  ],
  [
    'completion contradictory inner turn',
    ({ completion }) => (completion.event.payload.codex_turn_id = randomUUID()),
  ],
  ['completion response ID', ({ completion }) => (completion.response.response_id = 'resp_other')],
  [
    'completion upstream ID',
    ({ completion }) => (completion.response.upstream_request_id = 'other'),
  ],
  [
    'completion kind',
    ({ completion }) => (completion.event.payload.response_payload.kind.type = 'inference_request'),
  ],
  [
    'completion kind extra property',
    ({ completion }) => (completion.event.payload.response_payload.kind.extra = true),
  ],
  [
    'completion traversal',
    ({ completion }) => (completion.event.payload.response_payload.path = '../../other.json'),
  ],
  [
    'completion ordinal',
    ({ completion }) =>
      (completion.event.payload.response_payload.raw_payload_id = 'raw_payload:999'),
  ],
  ['completion before request', ({ completion }) => (completion.event.wall_time_unix_ms = 0)],
  ['ending before completion', ({ ending }) => (ending.wall_time_unix_ms = 1)],
  ['ending invalid date', ({ ending }) => (ending.wall_time_unix_ms = Number.MAX_SAFE_INTEGER)],
  ['ending failure', ({ ending }) => (ending.payload.status = 'failed')],
  ['ending cancellation', ({ ending }) => (ending.payload.status = 'cancelled')],
  ['ending thread', ({ ending }) => (ending.thread_id = randomUUID())],
  ['ending outer turn', ({ ending }) => (ending.codex_turn_id = randomUUID())],
  ['ending inner turn', ({ ending }) => (ending.payload.codex_turn_id = randomUUID())],
  ['message wrong role', ({ completion }) => (completion.response.output_items[1].role = 'user')],
  [
    'message incomplete',
    ({ completion }) => (completion.response.output_items[1].status = 'in_progress'),
  ],
  [
    'message wrong phase',
    ({ completion }) => (completion.response.output_items[1].phase = 'commentary'),
  ],
  [
    'message wrong internal turn',
    ({ completion }) =>
      (completion.response.output_items[1].internal_chat_message_metadata_passthrough.turn_id =
        randomUUID()),
  ],
  [
    'message refusal',
    ({ completion }) => (completion.response.output_items[1].content[0].type = 'refusal'),
  ],
  [
    'message split content',
    ({ completion }) =>
      completion.response.output_items[1].content.push({
        type: 'output_text',
        text: '{"suggestion":"conflict"}',
      }),
  ],
  [
    'second final message',
    ({ completion }) =>
      completion.response.output_items.push(structuredClone(completion.response.output_items[1])),
  ],
  [
    'conflicting final message',
    ({ completion }) => {
      const other = structuredClone(completion.response.output_items[1]);
      other.content[0].text = '{"suggestion":"conflicting text"}';
      completion.response.output_items.push(other);
    },
  ],
  ['no final message', ({ completion }) => completion.response.output_items.pop()],
  [
    'unexpected tool output',
    ({ completion }) =>
      completion.response.output_items.push({
        type: 'function_call',
        name: 'synthetic',
        arguments: '{}',
      }),
  ],
];
for (const [label, mutate] of completionMutations) {
  test(`synthetic ${label} cannot yield a prediction`, () =>
    setup((root) => {
      const value = completedFixture(root);
      mutate(value);
      writeJson(value.completion.responsePath, value.completion.response);
      value.fixture.saveEvents();
      assert.equal(readPrediction(root, value.fixture), null);
      // Completion rejection must not change the existing request-only API.
      assert(read(root, value.fixture));
    }));
}

for (const text of [
  '{"suggestion":null}',
  '{"suggestion":""}',
  '{"suggestion":"  \\n  "}',
  '{"suggestion":"first","suggestion":"second"}',
  '{"suggestion":"text","extra":true}',
  '{"suggestion":false}',
  '{"other":"text"}',
  'ordinary non-JSON text',
  '```json\n{"suggestion":"text"}\n```',
  JSON.stringify({ suggestion: 'x'.repeat(16_001) }),
]) {
  test(`synthetic absent, conflicting or invalid suggestion is withheld (${text.slice(0, 45)})`, () =>
    setup((root) => {
      const { fixture, completion } = completedFixture(root);
      completion.response.output_items[1].content[0].text = text;
      writeJson(completion.responsePath, completion.response);
      assert.equal(readPrediction(root, fixture), null);
    }));
}

test('a 16000-character suggestion is accepted without normalization', () =>
  setup((root) => {
    const text = 'a'.repeat(15_999) + '\n';
    const { fixture } = completedFixture(root, { text });
    assert.equal(readPrediction(root, fixture).text, text);
  }));

test('retry requests and duplicate terminal events are withheld even when values agree', () =>
  setup((root) => {
    const { fixture } = completedFixture(root);
    fixture.addRequest();
    assert.equal(readPrediction(root, fixture), null);
    assert.equal(read(root, fixture).evidence.requests.length, 2);
    fixture.events.pop();
    fixture.saveEvents();
    fixture.endTurn();
    assert.equal(readPrediction(root, fixture), null);
    fixture.events.pop();
    const completion = structuredClone(fixture.events[2]);
    completion.seq = fixture.events.length + 1;
    fixture.events.push(completion);
    fixture.saveEvents();
    assert.equal(readPrediction(root, fixture), null);
  }));

test('responses cannot borrow the request or terminal success of another trace bundle', () =>
  setup((root) => {
    const { fixture, request, completion } = completedFixture(root);
    const other = bundle(root, fixture);
    fixture.events.splice(2, 1);
    fixture.events[2].seq = 3;
    fixture.saveEvents();
    other.addResponse(request);
    assert.equal(readPrediction(root, fixture), null);
    // Restore the original completion; a second request in another bundle remains ambiguous.
    fixture.events.splice(2, 0, completion.event);
    fixture.events[3].seq = 4;
    fixture.saveEvents();
    other.events.splice(1);
    other.addRequest();
    assert.equal(readPrediction(root, fixture), null);
    assert.equal(read(root, fixture).evidence.requests.length, 2);
  }));

test('unrelated turn responses are never opened by readPrediction', () =>
  setup((root) => {
    const { fixture } = completedFixture(root);
    const unrelated = fixture.addRequest({ idOverride: { predictionTurnId: randomUUID() } });
    const completion = fixture.addResponse(unrelated);
    fs.unlinkSync(unrelated.requestPath);
    fs.symlinkSync('/definitely-not-a-request', unrelated.requestPath);
    fs.unlinkSync(completion.responsePath);
    fs.symlinkSync('/definitely-not-a-response', completion.responsePath);
    assert(readPrediction(root, fixture));
  }));

test('unsafe response permissions, symlinks, hardlinks and oversized payloads are rejected', () =>
  setup((root) => {
    const { fixture, completion } = completedFixture(root);
    for (const mode of [0o664, 0o4644]) {
      fs.chmodSync(completion.responsePath, mode);
      assert.equal(readPrediction(root, fixture), null);
    }
    fs.chmodSync(completion.responsePath, 0o644);
    const alias = join(root, 'response-original');
    fs.renameSync(completion.responsePath, alias);
    fs.symlinkSync(alias, completion.responsePath);
    assert.equal(readPrediction(root, fixture), null);
    fs.unlinkSync(completion.responsePath);
    fs.renameSync(alias, completion.responsePath);
    fs.linkSync(completion.responsePath, alias);
    assert.equal(readPrediction(root, fixture), null);
    fs.unlinkSync(alias);
    fs.truncateSync(completion.responsePath, 8 * 1024 * 1024 + 1);
    assert.equal(readPrediction(root, fixture), null);
  }));

for (const change of ['request', 'root']) {
  test(`a ${change} changed while reading the response invalidates the entire prediction`, () =>
    setup((root) => {
      const { fixture, request } = completedFixture(root);
      const originalRead = fs.readSync;
      let changed = false;
      fs.readSync = function (...args) {
        const result = originalRead(...args);
        if (!changed && args[1].includes(fixtures.synthetic.responseMetadata)) {
          changed = true;
          if (change === 'request') fs.appendFileSync(request.requestPath, ' ');
          else fs.chmodSync(root, 0o755);
        }
        return result;
      };
      syncBuiltinESMExports();
      try {
        assert.equal(readPrediction(root, fixture), null);
        assert(changed);
      } finally {
        fs.readSync = originalRead;
        syncBuiltinESMExports();
        fs.chmodSync(root, 0o700);
      }
    }));
}

test('availability checks only the private stable root without reading trace contents', () =>
  setup((root) => {
    const fixture = bundle(root);
    fs.writeFileSync(join(fixture.directory, 'manifest.json'), 'invalid but irrelevant');
    const reader = new DesktopPredictionTraceReader(root);
    const originalRead = fs.readSync;
    fs.readSync = () => {
      throw new Error('Availability must not read a payload or manifest');
    };
    syncBuiltinESMExports();
    try {
      assert.equal(reader.isAvailable(), true);
      assert.equal(new DesktopPredictionTraceReader(root + '/missing').isAvailable(), false);
      assert.equal(new DesktopPredictionTraceReader(root + '/.').isAvailable(), false);
      fs.chmodSync(root, 0o755);
      assert.equal(reader.isAvailable(), false);
    } finally {
      fs.readSync = originalRead;
      syncBuiltinESMExports();
      fs.chmodSync(root, 0o700);
    }
    const originalOpen = fs.opendirSync;
    fs.opendirSync = (...args) => {
      const directory = originalOpen(...args);
      fs.chmodSync(root, 0o755);
      return directory;
    };
    syncBuiltinESMExports();
    try {
      assert.equal(reader.isAvailable(), false);
    } finally {
      fs.opendirSync = originalOpen;
      syncBuiltinESMExports();
      fs.chmodSync(root, 0o700);
    }
  }));

test('latest native source uses request order, ignores old completion order and refuses ties or incomplete newest', () =>
  setup((root) => {
    const old = recoverableFixture(root);
    const next = recoverableFixture(
      root,
      execution({
        sourceThreadId: old.fixture.sourceThreadId,
        sourceTurnId: old.fixture.sourceTurnId,
      }),
    );
    for (const event of old.fixture.events) event.wall_time_unix_ms += 10;
    for (const event of next.fixture.events) event.wall_time_unix_ms += 100;
    // A late old completion cannot supersede a newer request.
    for (const event of old.fixture.events)
      if (
        event.payload.type === 'inference_completed' ||
        event.payload.type === 'codex_turn_ended' ||
        event.payload.event_type === 'turn_complete'
      )
        event.wall_time_unix_ms += 1000;
    old.fixture.saveEvents();
    next.fixture.saveEvents();
    const reader = new DesktopPredictionTraceReader(root);
    const read = () =>
      reader.readSourcePredictions(
        old.fixture.sourceThreadId,
        old.fixture.sourceTurnId,
        root,
        0,
        true,
      );
    assert.equal(read()[0].binding.predictionThreadId, next.fixture.predictionThreadId);
    // Superseded executions need not have completed; their proven request binding is sufficient.
    old.fixture.events.splice(3);
    old.fixture.saveEvents();
    assert.equal(read()[0].binding.predictionThreadId, next.fixture.predictionThreadId);
    const saved = [...next.fixture.events];
    next.fixture.events.splice(3);
    next.fixture.saveEvents();
    assert.equal(read(), null, 'No fallback while the newest request is incomplete');
    next.fixture.events.splice(0, next.fixture.events.length, ...saved);
    next.fixture.saveEvents();
    old.request.event.wall_time_unix_ms = next.request.event.wall_time_unix_ms;
    old.fixture.saveEvents();
    assert.equal(
      read(),
      null,
      'Equal request times cannot be ordered by directory or file timestamps',
    );
  }));
