import './windows-hidden-spawn-guard.mjs';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, realpath, readFile, writeFile, readdir, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID, createHash } from 'node:crypto';
import { PassThrough } from 'node:stream';
import { EventEmitter } from 'node:events';
import { execFileSync, spawn } from 'node:child_process';
import {
  createHostResponsesProxy,
  readHostResponsesProxyConfig,
  startHostResponsesProxy,
  startOptionalHostResponsesProxy,
} from '../apps/host-agent/dist/responses-proxy.js';
import {
  DesktopResponses,
  ResponsesProxyError,
  ResponsesEventReader,
  ResponseChatStream,
  prepareDesktopResponse,
  chatCompletionToResponse,
} from '../packages/codex-adapter/dist/desktop-responses.js';

const hash = (value) => createHash('sha256').update(value).digest('hex');
const model = 'fixture-model';
const body = {
  model,
  input: [{ role: 'user', content: 'private input probe' }],
  instructions: '',
  stream: true,
  store: false,
};
const response = {
  id: 'resp_fixture',
  object: 'response',
  model,
  created_at: 123,
  status: 'completed',
  output: [
    { type: 'message', role: 'assistant', content: [{ type: 'output_text', text: '原样 hello' }] },
  ],
  usage: { input_tokens: 5, output_tokens: 3, total_tokens: 8 },
};
const event = (type, data = {}) =>
  `event: ${type}\r\ndata: ${JSON.stringify({ type, ...data })}\r\n\r\n`;
const stream = Buffer.from(
  ': heartbeat\r\n\r\n' +
    event('response.created', { response: { ...response, status: 'in_progress', output: [] } }) +
    event('response.output_text.delta', { delta: '原样 hello' }) +
    event('response.completed', { response }),
);
async function directory() {
  return realpath(await mkdtemp(join(tmpdir(), 'responses-proxy-')));
}

async function fixture(options = {}) {
  const path = options.path ?? (await directory());
  const config = {
    bind: '127.0.0.1',
    port: 8797,
    directory: path,
    tokenFile: join(path, 'access-token'),
    models: [model],
    maxBytes: 1024,
    maxConcurrency: 1,
    timeoutMs: 3000,
    ...options.config,
  };
  let calls = 0,
    closed = false;
  const captured = [];
  const transport = {
    async send(bytes, signal, beforeSubmit) {
      await beforeSubmit?.();
      calls++;
      captured.push(JSON.parse(bytes));
      const result = new PassThrough();
      result.statusCode = options.status ?? 200;
      const abort = () => result.destroy(new Error('Fixture aborted'));
      signal.addEventListener('abort', abort, { once: true });
      result.once('close', () => signal.removeEventListener('abort', abort));
      if (options.send) options.send(result, signal);
      else
        setImmediate(() => {
          for (let i = 0; i < (options.bytes ?? stream).length; i += 3)
            result.write((options.bytes ?? stream).subarray(i, i + 3));
          result.end();
        });
      return result;
    },
    close() {
      closed = true;
    },
  };
  const proxy = await createHostResponsesProxy(config, transport);
  await new Promise((resolve, reject) => {
    proxy.server.once('error', reject);
    proxy.server.listen(0, '127.0.0.1', resolve);
  });
  await proxy.ready();
  const token = (await readFile(config.tokenFile, 'utf8')).trim();
  const base = `http://127.0.0.1:${proxy.server.address().port}`;
  const headers = { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' };
  const request = (payload = body, id = randomUUID(), route = '/v1/responses', extra = {}) =>
    fetch(base + route, {
      method: 'POST',
      headers: { ...headers, 'Idempotency-Key': id, ...extra },
      body: JSON.stringify(payload),
    });
  const receipt = async (id) => {
    for (let i = 0; i < 100; i++) {
      const value = await (await fetch(base + '/v1/requests/' + id, { headers })).json();
      if (value.phase && !['prepared', 'submitting', 'streaming'].includes(value.phase))
        return value;
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    throw new Error('Receipt did not reach a terminal observation');
  };
  return {
    path,
    config,
    proxy,
    base,
    headers,
    request,
    receipt,
    captured,
    get calls() {
      return calls;
    },
    get closed() {
      return closed;
    },
    async close(remove = true) {
      await proxy.close();
      if (remove) await rm(path, { recursive: true });
    },
  };
}

test('an occupied optional proxy port leaves the existing listener healthy', async () => {
  const original = await fixture();
  const path = await directory();
  const reports = [];
  try {
    const result = await startOptionalHostResponsesProxy(
      {
        HOST_RESPONSES_PROXY_ENABLED: '1',
        HOST_RESPONSES_PROXY_BIND: '127.0.0.1',
        HOST_RESPONSES_PROXY_PORT: new URL(original.base).port,
        HOST_RESPONSES_PROXY_STATE_DIR: path,
        HOST_RESPONSES_PROXY_MODELS: JSON.stringify([model]),
      },
      (message) => reports.push(JSON.parse(message)),
    );
    assert.equal(result, undefined);
    assert.deepEqual(reports, [
      { service: 'host-responses-proxy', status: 'unavailable', code: 'EADDRINUSE' },
    ]);
    assert.equal(
      (await fetch(original.base + '/healthz', { headers: original.headers })).status,
      200,
    );
    assert.equal((await readdir(path)).includes('listener-owner.json'), false);
  } finally {
    await original.close();
    await rm(path, { recursive: true });
  }
});

test('disabled by default has no filesystem or auth side effects, and remote listeners require TLS', async () => {
  const path = join(await directory(), 'not-created');
  assert.equal(
    readHostResponsesProxyConfig({
      HOST_RESPONSES_PROXY_BIND: 'not-an-IP',
      HOST_RESPONSES_PROXY_MODELS: 'invalid',
    }),
    undefined,
  );
  assert.equal(
    await startHostResponsesProxy({ HOST_AGENT_STATE_DIR: path, CODEX_EXECUTABLE: 'must-not-run' }),
    undefined,
  );
  await assert.rejects(stat(path), { code: 'ENOENT' });
  assert.throws(() => readHostResponsesProxyConfig({ HOST_RESPONSES_PROXY_ENABLED: 'true' }));
  assert.throws(
    () =>
      readHostResponsesProxyConfig({
        HOST_RESPONSES_PROXY_ENABLED: '1',
        HOST_RESPONSES_PROXY_BIND: '0.0.0.0',
        HOST_RESPONSES_PROXY_MODELS: '["fixture-model"]',
      }),
    (e) => e.code === 'proxy_tls_required',
  );
  assert.throws(() =>
    readHostResponsesProxyConfig({
      HOST_RESPONSES_PROXY_ENABLED: '1',
      HOST_RESPONSES_PROXY_MODELS: '[]',
    }),
  );
  await rm(join(path, '..'), { recursive: true });
});

test('authorization, browser Origin, routes, model allowlist and size are checked before upstream I/O', async () => {
  const f = await fixture();
  try {
    assert.equal((await fetch(f.base + '/v1/models')).status, 401);
    assert.equal(
      (
        await fetch(f.base + '/v1/models', {
          headers: { Authorization: 'Bearer original-host-key' },
        })
      ).status,
      401,
    );
    assert.equal(
      (
        await fetch(f.base + '/v1/models', {
          headers: { ...f.headers, Origin: 'https://example.invalid' },
        })
      ).status,
      401,
    );
    const models = await (await fetch(f.base + '/v1/models', { headers: f.headers })).json();
    assert.deepEqual(
      models.data.map((v) => v.id),
      [model],
    );
    assert.equal((await f.request(body, randomUUID(), '/anything')).status, 404);
    assert.equal((await f.request({ ...body, model: 'other' })).status, 400);
    assert.equal((await f.request({ ...body, store: true })).status, 400);
    assert.equal((await f.request({ ...body, url: 'http://localhost/admin' })).status, 400);
    assert.equal((await f.request({ ...body, previous_response_id: 'other' })).status, 400);
    assert.equal((await f.request({ ...body, input: 'x'.repeat(1500) })).status, 413);
    assert.equal(f.calls, 0);
    assert.equal((await readdir(join(f.path, 'operations'))).length, 0);
    assert.equal((await stat(f.config.tokenFile)).mode & 0o777, 0o600);
  } finally {
    await f.close();
  }
});

test('Responses SSE is byte-for-byte preserved across fragmented UTF-8, with durable immutable duplicate rejection', async () => {
  const f = await fixture();
  const id = randomUUID();
  try {
    const result = await f.request(body, id);
    assert.equal(result.status, 200);
    assert.equal(result.headers.get('x-request-id'), id);
    assert.deepEqual(Buffer.from(await result.arrayBuffer()), stream);
    const receipt = await f.receipt(id);
    assert.equal(receipt.phase, 'completed');
    assert.equal(receipt.upstreamSubmissions, 1);
    assert.equal(receipt.upstreamSha256, hash(stream));
    assert.equal(receipt.responseSha256, hash(stream));
    assert.equal((await f.request(body, id)).status, 409);
    assert.equal((await f.request({ ...body, input: 'new input' }, id)).status, 409);
    assert.deepEqual(await f.receipt(id), receipt);
    assert.equal(f.calls, 1);
    const saved = (
      await Promise.all(
        (await readdir(join(f.path, 'operations'))).map((name) =>
          readFile(join(f.path, 'operations', name), 'utf8'),
        ),
      )
    ).join('');
    assert(!saved.includes('private input probe'));
    assert(!saved.includes(f.headers.Authorization));
    await f.close(false);
    const restarted = await fixture({ path: f.path });
    try {
      assert.equal((await restarted.request(body, id)).status, 409);
      assert.deepEqual(await restarted.receipt(id), receipt);
      assert.equal(restarted.calls, 0);
    } finally {
      await restarted.close();
    }
  } finally {
    await f.proxy.close();
    await rm(f.path, { recursive: true, force: true });
  }
});

test('Chat consumers can close at DONE without interrupting the upstream completion receipt', async () => {
  const f = await fixture({
    send(result) {
      result.write(stream);
      setTimeout(() => result.end(), 80);
    },
  });
  try {
    const id = randomUUID();
    const result = await f.request(
      { model, messages: [{ role: 'user', content: 'hello' }], stream: true },
      id,
      '/v1/chat/completions',
    );
    const reader = result.body.getReader();
    let raw = '';
    for (;;) {
      const part = await reader.read();
      if (part.done) break;
      raw += Buffer.from(part.value).toString();
      if (raw.includes('[DONE]')) {
        await reader.cancel();
        break;
      }
    }
    assert(raw.includes('[DONE]'));
    const receipt = await f.receipt(id);
    assert.equal(receipt.phase, 'completed');
    assert.equal(receipt.upstreamSubmissions, 1);
    assert.equal(receipt.upstreamSha256, hash(stream));
  } finally {
    await f.close();
  }
});

test('nonstreaming clients get the original completed response object, and chat clients get compatible chunks and usage', async () => {
  const f = await fixture();
  try {
    const r = await f.request({ ...body, stream: false });
    assert.deepEqual(await r.json(), response);
    const chat = {
      model,
      messages: [
        { role: 'system', content: 'system instructions' },
        { role: 'user', content: 'hello' },
      ],
      stream: true,
      stream_options: { include_usage: true },
      temperature: 0.7,
      max_tokens: 100,
      reasoning_effort: 'xhigh',
    };
    const id = randomUUID(),
      result = await f.request(chat, id, '/v1/chat/completions');
    const raw = await result.text();
    assert(raw.endsWith('data: [DONE]\n\n'));
    const chunks = raw
      .split('\n\n')
      .filter((v) => v.startsWith('data: {'))
      .map((v) => JSON.parse(v.slice(6)));
    assert.equal(chunks.map((v) => v.choices?.[0]?.delta?.content ?? '').join(''), '原样 hello');
    assert.equal(chunks.at(-2).choices[0].finish_reason, 'stop');
    assert.equal(chunks.at(-1).usage.total_tokens, 8);
    const submitted = f.captured.at(-1);
    assert.equal(submitted.instructions, 'system instructions');
    assert.equal(submitted.store, false);
    assert.equal(submitted.stream, true);
    assert.deepEqual(submitted.reasoning, { effort: 'xhigh' });
    assert(!('temperature' in submitted));
    assert(!('max_tokens' in submitted));
    assert.equal((await f.receipt(id)).phase, 'completed');
    const nonstream = await f.request(
      { ...chat, stream: false },
      randomUUID(),
      '/v1/chat/completions',
    );
    assert.equal((await nonstream.json()).choices[0].message.content, '原样 hello');
  } finally {
    await f.close();
  }
});

test('broken streams remain unknown across restart and are never resubmitted', async () => {
  const f = await fixture({
    bytes: Buffer.from(event('response.output_text.delta', { delta: 'partial' })),
  });
  const id = randomUUID();
  try {
    const result = await f.request(body, id);
    await assert.rejects(result.text());
    const receipt = await f.receipt(id);
    assert.equal(receipt.phase, 'outcome-unknown');
    assert.equal(receipt.upstreamSubmissions, 1);
    assert.equal(f.calls, 1);
    await f.close(false);
    const restarted = await fixture({ path: f.path });
    try {
      assert.equal((await restarted.request(body, id)).status, 409);
      assert.equal((await restarted.receipt(id)).phase, 'outcome-unknown');
      assert.equal(restarted.calls, 0);
    } finally {
      await restarted.close();
    }
  } finally {
    await f.proxy.close();
    await rm(f.path, { recursive: true, force: true });
  }
});

test('upstream errors are sanitized and concurrency does not trigger additional submissions', async () => {
  const rejected = await fixture({
    status: 401,
    bytes: Buffer.from('private upstream credential detail'),
  });
  try {
    const id = randomUUID(),
      response = await rejected.request(body, id);
    assert.equal(response.status, 401);
    assert(!(await response.text()).includes('private upstream'));
    assert.equal((await rejected.receipt(id)).phase, 'failed');
    assert.equal(rejected.calls, 1);
  } finally {
    await rejected.close();
  }
  let release;
  const f = await fixture({
    send(result) {
      result.write(event('response.created', { response: { ...response, status: 'in_progress' } }));
      release = () => result.end(event('response.completed', { response }));
    },
  });
  try {
    const first = await f.request();
    assert.equal((await f.request()).status, 429);
    assert.equal(f.calls, 1);
    release();
    await first.text();
  } finally {
    await f.close();
  }
});

test('an active state writer cannot be taken over by a second listener', async () => {
  const f = await fixture();
  let second;
  try {
    second = await createHostResponsesProxy(f.config, {
      async send() {
        throw Error('must not submit');
      },
      close() {},
    });
    await new Promise((resolve) => second.server.listen(0, '127.0.0.1', resolve));
    await assert.rejects(second.ready(), (e) => e.code === 'proxy_state_in_use');
    assert.equal((await fetch(f.base + '/healthz', { headers: f.headers })).status, 200);
  } finally {
    await second?.close();
    await f.close();
  }
});

test('a confirmed-dead writer recovers the same submitting receipt as unknown without resending', async () => {
  const f = await fixture();
  const id = randomUUID();
  await (await f.request(body, id)).text();
  const original = await f.receipt(id);
  await f.close(false);
  const child = spawn(process.execPath, ['-e', 'process.exit(0)'], {
    stdio: 'ignore',
    windowsHide: true,
  });
  await new Promise((resolve) => child.once('exit', resolve));
  await writeFile(
    join(f.path, 'listener-owner.json'),
    JSON.stringify({
      pid: child.pid,
      nonce: randomUUID(),
      bind: f.config.bind,
      port: f.config.port,
    }),
    { mode: 0o600 },
  );
  await writeFile(
    join(f.path, 'operations', hash(id) + '.json'),
    JSON.stringify({ ...original, phase: 'submitting' }),
    { mode: 0o600 },
  );
  const restarted = await fixture({ path: f.path });
  try {
    assert.equal((await restarted.receipt(id)).phase, 'outcome-unknown');
    assert.equal((await restarted.request(body, id)).status, 409);
    assert.equal(restarted.calls, 0);
  } finally {
    await restarted.close();
  }
});

test('configuration preparation keeps the switch off and preserves existing TLS and proxy key', async () => {
  const path = await directory();
  try {
    const args = [
      'scripts/configure-responses-proxy.mjs',
      '--state-dir',
      path,
      '--models',
      '["fixture-model"]',
      '--addresses',
      '["127.0.0.1"]',
    ];
    const first = JSON.parse(
      execFileSync(process.execPath, args, { encoding: 'utf8', windowsHide: true }),
    );
    const files = ['access-token', 'tls-key.pem', 'tls-cert.pem'];
    const before = await Promise.all(files.map((file) => readFile(join(path, file))));
    const second = JSON.parse(
      execFileSync(process.execPath, args, { encoding: 'utf8', windowsHide: true }),
    );
    assert.equal(first.enabled, false);
    assert.equal(second.environment.HOST_RESPONSES_PROXY_ENABLED, '0');
    assert.deepEqual(await Promise.all(files.map((file) => readFile(join(path, file)))), before);
    assert.equal(second.certificateFingerprint, first.certificateFingerprint);
    assert(!JSON.stringify(second).includes(before[0].toString()));
    await assert.rejects(stat(join(path, 'operations')), { code: 'ENOENT' });
  } finally {
    await rm(path, { recursive: true });
  }
});

test('protocol mapping preserves tool call identity, rejects unsupported content, and bounds incomplete SSE', () => {
  const converted = chatCompletionToResponse({
    model,
    messages: [
      {
        role: 'assistant',
        content: null,
        tool_calls: [
          { id: 'call_one', type: 'function', function: { name: 'lookup', arguments: '{"x":1}' } },
        ],
      },
      { role: 'tool', tool_call_id: 'call_one', content: 'result' },
    ],
    tools: [{ type: 'function', function: { name: 'lookup', parameters: { type: 'object' } } }],
  });
  assert.equal(converted.input[0].call_id, 'call_one');
  assert.equal(converted.input[1].call_id, 'call_one');
  assert.throws(() =>
    chatCompletionToResponse({ model, messages: [{ role: 'user', content: [{ type: 'audio' }] }] }),
  );
  const mapper = new ResponseChatStream(false);
  mapper.event({ type: 'response.created', response });
  const initial = mapper.event({
    type: 'response.output_item.added',
    output_index: 2,
    item: { type: 'function_call', call_id: 'call_one', name: 'lookup' },
  });
  assert(initial.includes('call_one'));
  assert(
    mapper
      .event({ type: 'response.function_call_arguments.delta', output_index: 2, delta: '{"x":' })
      .includes('arguments'),
  );
  assert(mapper.event({ type: 'response.completed', response }).includes('tool_calls'));
  const parser = new ResponsesEventReader(() => {});
  parser.push(Buffer.from('data: {"type":"partial"}'));
  assert.throws(
    () => parser.finish(),
    (e) => e.code === 'truncated_upstream_event',
  );
  const normalized = prepareDesktopResponse(
    { model, input: [{ role: 'system', content: 'test' }], stream: true },
    [model],
  );
  assert.equal(JSON.parse(normalized.body).input[0].role, 'developer');
});

test('adapter uses only the fixed backend, current readonly credentials, no redirects or retries', async () => {
  let metadataReads = 0,
    calls = 0,
    submitted = 0;
  const claims = {
    sub: 'fixture-user',
    exp: Math.floor(Date.now() / 1000) + 3600,
    'https://api.openai.com/auth': { chatgpt_account_id: 'fixture-account' },
  };
  const token =
    'header.' + Buffer.from(JSON.stringify(claims)).toString('base64url') + '.signature';
  const value = {
    auth: { authMethod: 'chatgpt', authToken: token },
    account: {
      account: { type: 'chatgpt' },
      workspaceRouting: {
        backendOrigin: 'https://chatgpt.com',
        accountRoutingOverride: 'NO_CONSTRAINT',
        chatgptAccountId: 'fixture-account',
      },
    },
    config: { config: {} },
    requirements: { requirements: null },
  };
  const metadata = async () => {
    metadataReads++;
    return value;
  };
  const fakeRequest = (endpoint, options, callback) => {
    calls++;
    assert.equal(endpoint, 'https://chatgpt.com/backend-api/codex/responses');
    assert.equal(options.headers.Authorization, 'Bearer ' + token);
    assert.equal(options.headers['ChatGPT-Account-Id'], 'fixture-account');
    const req = new EventEmitter();
    req.end = (bytes) => {
      assert.equal(bytes.toString(), '{}');
      const result = new PassThrough();
      result.statusCode = 200;
      setImmediate(() => {
        callback(result);
        result.end(stream);
      });
    };
    return req;
  };
  const adapter = new DesktopResponses('/unused', '/unused', metadata, fakeRequest);
  try {
    const response = await adapter.send(
      Buffer.from('{}'),
      new AbortController().signal,
      async () => {
        submitted++;
      },
    );
    for await (const _chunk of response) {
      /* consume */
    }
    assert.equal(calls, 1);
    assert.equal(metadataReads, 1);
    assert.equal(submitted, 1);
    value.account.workspaceRouting.backendOrigin = 'https://enterprise.invalid';
    await assert.rejects(
      adapter.send(Buffer.from('{}'), new AbortController().signal),
      (e) => e instanceof ResponsesProxyError && e.code === 'unsupported_desktop_route',
    );
    assert.equal(calls, 1);
    assert.equal(metadataReads, 2);
  } finally {
    adapter.close();
  }
  await assert.rejects(adapter.send(Buffer.from('{}'), new AbortController().signal));
  assert.equal(calls, 1);
});
