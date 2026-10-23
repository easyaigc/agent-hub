import { randomUUID } from 'node:crypto';
import fs from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '../../packages/codex-adapter/dist/desktop-platform.js';

// Schema-derived fixtures only. No captured request/history or live desktop proof.
const fixtures = {
  synthetic: {
    upstream: 'openai/codex rust-v0.163.0-alpha.10.2',
    requestBody: 'synthetic private request body must never escape the reader',
    responseMetadata: 'synthetic private reasoning payload must never escape the reader',
  },
};

function writeJson(path, value) {
  fs.writeFileSync(path, JSON.stringify(value), { mode: 0o600 });
  protectWindowsStateFile(path);
}

function setup(run) {
  const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'prediction-trace-synthetic-')));
  fs.chmodSync(root, 0o700);
  protectWindowsStateDirectory(root);
  try {
    return run(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

function execution(overrides = {}) {
  return {
    predictionThreadId: randomUUID(),
    predictionTurnId: randomUUID(),
    sourceThreadId: randomUUID(),
    sourceTurnId: randomUUID(),
    ...overrides,
  };
}

function bundle(root, ids = execution()) {
  const traceId = randomUUID();
  const sessionId = randomUUID();
  const directory = join(root, `trace-${traceId}-${ids.predictionThreadId}`);
  fs.mkdirSync(directory, { mode: 0o700 });
  protectWindowsStateDirectory(directory);
  fs.mkdirSync(join(directory, 'payloads'), { mode: 0o700 });
  protectWindowsStateDirectory(join(directory, 'payloads'));
  const manifest = {
    schema_version: 1,
    trace_id: traceId,
    rollout_id: ids.predictionThreadId,
    root_thread_id: ids.predictionThreadId,
    started_at_unix_ms: 1,
    raw_event_log: 'trace.jsonl',
    payloads_dir: 'payloads',
  };
  writeJson(join(directory, 'manifest.json'), manifest);
  const events = [
    {
      schema_version: 1,
      seq: 1,
      wall_time_unix_ms: 1,
      rollout_id: ids.predictionThreadId,
      thread_id: null,
      codex_turn_id: null,
      payload: {
        type: 'rollout_started',
        trace_id: traceId,
        root_thread_id: ids.predictionThreadId,
      },
    },
  ];
  const logPath = join(directory, 'trace.jsonl');
  const saveEvents = (tail = '') => {
    fs.writeFileSync(logPath, events.map((event) => JSON.stringify(event) + '\n').join('') + tail, {
      mode: 0o600,
    });
    protectWindowsStateFile(logPath);
  };
  saveEvents();
  return {
    ...ids,
    traceId,
    directory,
    manifest,
    events,
    logPath,
    saveEvents,
    addRequest({
      ws = false,
      metadata: metadataOverride = {},
      idOverride = {},
      recovery = false,
    } = {}) {
      const target = { ...ids, ...idOverride };
      const ordinal = events.length;
      const metadata = {
        session_id: sessionId,
        thread_id: target.predictionThreadId,
        turn_id: target.predictionTurnId,
        request_kind: 'turn',
        forked_from_thread_id: target.sourceThreadId,
        prediction_source_thread_id: target.sourceThreadId,
        prediction_source_turn_id: target.sourceTurnId,
        ...(recovery
          ? { thread_source: 'composer_predictions', turn_trigger: 'composer_predictions' }
          : {}),
        ...metadataOverride,
      };
      const request = {
        ...(ws ? { type: 'response.create' } : {}),
        model: 'synthetic-model',
        stream: true,
        input: recovery
          ? [
              {
                type: 'message',
                role: 'user',
                content: [
                  {
                    type: 'input_text',
                    text: 'This is an app-generated request for a suggested next user message. Synthetic fixture.',
                  },
                ],
              },
            ]
          : [{ type: 'message', content: fixtures.synthetic.requestBody }],
        client_metadata: {
          session_id: sessionId,
          thread_id: target.predictionThreadId,
          turn_id: target.predictionTurnId,
          'x-codex-turn-metadata': JSON.stringify(metadata),
        },
      };
      const requestPath = join(directory, 'payloads', `${ordinal}.json`);
      writeJson(requestPath, request);
      const event = {
        schema_version: 1,
        seq: events.length + 1,
        wall_time_unix_ms: 1,
        rollout_id: ids.predictionThreadId,
        thread_id: target.predictionThreadId,
        codex_turn_id: target.predictionTurnId,
        payload: {
          type: 'inference_started',
          inference_call_id: randomUUID(),
          thread_id: target.predictionThreadId,
          codex_turn_id: target.predictionTurnId,
          model: 'synthetic-model',
          provider_name: 'synthetic-provider',
          request_payload: {
            raw_payload_id: `raw_payload:${ordinal}`,
            kind: { type: 'inference_request' },
            path: `payloads/${ordinal}.json`,
          },
        },
      };
      events.push(event);
      saveEvents();
      return { request, requestPath, event, metadata };
    },
    addResponse(request, { text = 'Synthetic follow-up suggestion.' } = {}) {
      const ordinal = events.length;
      const response = {
        response_id: 'resp_synthetic_' + randomUUID(),
        upstream_request_id: null,
        token_usage: { input_tokens: 20, output_tokens: 10, total_tokens: 30 },
        output_items: [
          {
            type: 'reasoning',
            id: 'rs_synthetic',
            summary: [],
            content: [],
            encrypted_content: fixtures.synthetic.responseMetadata,
            internal_chat_message_metadata_passthrough: {
              turn_id: request.event.codex_turn_id,
            },
          },
          {
            type: 'message',
            id: 'msg_synthetic',
            role: 'assistant',
            status: 'completed',
            phase: 'final_answer',
            content: [
              {
                type: 'output_text',
                text: JSON.stringify({ suggestion: text }),
                annotations: [],
                logprobs: [],
              },
            ],
            internal_chat_message_metadata_passthrough: {
              turn_id: request.event.codex_turn_id,
              create_time: 0.002,
            },
          },
        ],
      };
      const responsePath = join(directory, 'payloads', `${ordinal}.json`);
      writeJson(responsePath, response);
      const event = {
        schema_version: 1,
        seq: events.length + 1,
        wall_time_unix_ms: 2,
        rollout_id: ids.predictionThreadId,
        thread_id: request.event.thread_id,
        codex_turn_id: request.event.codex_turn_id,
        // Native completion identifies the execution in the envelope only.
        payload: {
          type: 'inference_completed',
          inference_call_id: request.event.payload.inference_call_id,
          response_id: response.response_id,
          upstream_request_id: null,
          response_payload: {
            raw_payload_id: `raw_payload:${ordinal}`,
            kind: { type: 'inference_response' },
            path: `payloads/${ordinal}.json`,
          },
        },
      };
      events.push(event);
      saveEvents();
      return { response, responsePath, event };
    },
    endTurn(status = 'completed') {
      const event = {
        schema_version: 1,
        seq: events.length + 1,
        wall_time_unix_ms: 3,
        rollout_id: ids.predictionThreadId,
        thread_id: ids.predictionThreadId,
        codex_turn_id: ids.predictionTurnId,
        payload: { type: 'codex_turn_ended', codex_turn_id: ids.predictionTurnId, status },
      };
      events.push(event);
      saveEvents();
      return event;
    },
    protocolEvent(eventType, value) {
      const ordinal = events.length;
      const path = join(directory, 'payloads', `${ordinal}.json`);
      writeJson(path, value);
      const event = {
        schema_version: 1,
        seq: events.length + 1,
        wall_time_unix_ms: eventType === 'session_configured' ? 1 : 4,
        rollout_id: ids.predictionThreadId,
        thread_id: null,
        codex_turn_id: null,
        payload: {
          type: 'protocol_event_observed',
          event_type: eventType,
          event_payload: {
            raw_payload_id: `raw_payload:${ordinal}`,
            kind: { type: 'protocol_event' },
            path: `payloads/${ordinal}.json`,
          },
        },
      };
      events.push(event);
      saveEvents();
      return { path, value, event };
    },
  };
}

function recoverableFixture(root, ids = execution()) {
  const fixture = bundle(root, ids);
  const configured = fixture.protocolEvent('session_configured', {
    type: 'session_configured',
    thread_id: ids.predictionThreadId,
    thread_source: 'composer_predictions',
    forked_from_id: ids.sourceThreadId,
    cwd: root,
  });
  const request = fixture.addRequest({ recovery: true });
  const completion = fixture.addResponse(request);
  fixture.endTurn();
  const terminal = fixture.protocolEvent('turn_complete', {
    type: 'task_complete',
    turn_id: ids.predictionTurnId,
    root_turn_id: ids.predictionTurnId,
    last_agent_message: JSON.stringify({ suggestion: 'Synthetic follow-up suggestion.' }),
  });
  return { fixture, configured, request, completion, terminal };
}

export { fixtures, writeJson, setup, execution, bundle, recoverableFixture };
