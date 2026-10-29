import './windows-hidden-spawn-guard.mjs';
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, realpath, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { randomUUID, createHash } from 'node:crypto';
import { PassThrough } from 'node:stream';
import { EventEmitter } from 'node:events';
import {
  createHostResponsesProxy,
  readHostResponsesProxyConfig,
} from '../apps/host-agent/dist/responses-proxy.js';
import { parseImageRequest } from '../apps/host-agent/dist/image-proxy.js';
import {
  DesktopImages,
  prepareDesktopImage,
  validateDesktopImageResponse,
} from '../packages/codex-adapter/dist/desktop-images.js';

const model = 'gpt-image-2';
const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=',
  'base64',
);
const reference = { image_url: 'data:image/png;base64,' + png.toString('base64') };
const response = {
  created: 123,
  data: [{ b64_json: png.toString('base64') }],
  size: '1x1',
  output_format: 'png',
  quality: 'medium',
  background: 'opaque',
  usage: { total_tokens: 4 },
};
const sha = (b) => createHash('sha256').update(b).digest('hex');
const request = { model, prompt: 'private image intent', size: '2560x1440', quality: 'high' };

async function fixture(options = {}) {
  const path = await realpath(await mkdtemp(join(tmpdir(), 'images-proxy-')));
  const config = {
    bind: '127.0.0.1',
    port: 8797,
    directory: path,
    tokenFile: join(path, 'access-token'),
    models: ['text-fixture'],
    maxBytes: 2048,
    maxConcurrency: 4,
    timeoutMs: 2000,
    ...(options.disabled
      ? {}
      : { images: { models: [model], maxBytes: 8192, maxConcurrency: 1, timeoutMs: 3000 } }),
  };
  const calls = [];
  const send = (kind) => async (body, signal, beforeSubmit) => {
    await beforeSubmit?.();
    calls.push({ kind, body: JSON.parse(body), signal });
    await options.observeSubmission?.(path);
    const upstream = new PassThrough();
    upstream.statusCode = options.status ?? 200;
    if (options.pending)
      signal.addEventListener('abort', () => upstream.destroy(new Error('aborted')), {
        once: true,
      });
    else setImmediate(() => upstream.end(JSON.stringify(options.response ?? response)));
    return upstream;
  };
  const proxy = await createHostResponsesProxy(
    config,
    {
      send: async () => {
        throw Error('Unexpected Responses transport');
      },
      close() {},
    },
    { generate: send('generate'), edit: send('edit'), close() {} },
  );
  await new Promise((done, reject) => {
    proxy.server.once('error', reject);
    proxy.server.listen(0, '127.0.0.1', done);
  });
  await proxy.ready();
  const url = `http://127.0.0.1:${proxy.server.address().port}`;
  const key = (await readFile(config.tokenFile, 'utf8')).trim();
  const api = (path, init = {}) =>
    fetch(url + path, { ...init, headers: { Authorization: 'Bearer ' + key, ...init.headers } });
  const post = (value = request, route = '/v1/images/generations', id = randomUUID()) =>
    api(route, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'idempotency-key': id },
      body: JSON.stringify(value),
    });
  return {
    path,
    proxy,
    calls,
    api,
    post,
    async close() {
      await proxy.close();
      await rm(path, { recursive: true });
    },
  };
}

test('image capability is opt-in, separately bounded, and only advertises verified models', () => {
  assert.equal(
    readHostResponsesProxyConfig({ HOST_RESPONSES_PROXY_IMAGE_MODELS: 'invalid' }),
    undefined,
  );
  const env = {
    HOST_RESPONSES_PROXY_ENABLED: '1',
    HOST_RESPONSES_PROXY_IMAGE_MODELS: JSON.stringify([model]),
  };
  const config = readHostResponsesProxyConfig(env);
  assert.deepEqual(config.models, []);
  assert.deepEqual(config.images.models, [model]);
  assert.equal(config.images.timeoutMs, 600000);
  assert.equal(config.images.maxConcurrency, 1);
  assert.throws(() =>
    readHostResponsesProxyConfig({
      ...env,
      HOST_RESPONSES_PROXY_IMAGE_MODELS: '["unverified-model"]',
    }),
  );
  assert.throws(() =>
    readHostResponsesProxyConfig({ ...env, HOST_RESPONSES_PROXY_IMAGE_CONCURRENCY: '0' }),
  );
});

test('OpenAI parameters become explicit preferences while model, identity and one submission stay structural', () => {
  const p = prepareDesktopImage(
    {
      ...request,
      background: 'transparent',
      output_format: 'webp',
      output_compression: 90,
      safety_identifier: 'hashed-user',
    },
    'generations',
    [model],
  );
  const body = JSON.parse(p.body);
  assert.equal(body.model, model);
  assert.equal(body.n, 1);
  assert.equal(body.stream, false);
  assert.equal(body.safety_identifier, 'hashed-user');
  assert.match(body.prompt, /16:9/);
  assert.match(body.prompt, /2560 pixels wide/);
  assert.match(body.prompt, /transparent alpha/);
  assert.equal(body.size, undefined);
  assert.equal(body.quality, undefined);
  assert.equal(body.output_format, undefined);
  assert.deepEqual(p.preferenceFields, [
    'size',
    'quality',
    'background',
    'output_format',
    'output_compression',
  ]);
  const omitted = JSON.parse(
    prepareDesktopImage(
      {
        prompt: 'null optional fields',
        model: null,
        n: null,
        size: null,
        quality: null,
        stream: null,
      },
      'generations',
      [model],
    ).body,
  );
  assert.equal(omitted.model, model);
  assert.equal(omitted.n, 1);
  assert.equal(omitted.prompt, 'null optional fields');
  for (const value of [
    { model: 'text-fixture' },
    { n: 2 },
    { size: '99999x1024' },
    { size: '1025x1024' },
    { stream: 'true' },
    { response_format: 'url' },
    { partial_images: 1 },
    { base_url: 'https://other.invalid' },
  ])
    assert.throws(() => prepareDesktopImage({ ...request, ...value }, 'generations', [model]));
});

test('editing preserves ordered image and mask references and rejects local paths and ambiguous identities', () => {
  const p = prepareDesktopImage(
    {
      ...request,
      images: [reference, { file_id: 'file-example' }],
      mask: reference,
      input_fidelity: 'high',
    },
    'edits',
    [model],
  );
  const body = JSON.parse(p.body);
  assert.deepEqual(body.images, [reference, { file_id: 'file-example' }]);
  assert.deepEqual(body.mask, reference);
  assert.match(body.prompt, /input fidelity/);
  for (const images of [
    [],
    [{ image_url: 'file:///private/image.png' }],
    [{ image_url: reference.image_url, file_id: 'file-both' }],
    [{ image_url: 'data:image/jpeg;base64,' + png.toString('base64') }],
    Array(17).fill(reference),
  ])
    assert.throws(() => prepareDesktopImage({ ...request, images }, 'edits', [model]));
});

test('SDK multipart files are normalized without treating filenames as filesystem paths', async () => {
  const form = new FormData();
  form.append('model', model);
  form.append('prompt', 'edit');
  form.append('stream', 'true');
  form.append('image[]', new Blob([png], { type: 'image/png' }), '../../private.png');
  form.append('image[]', new Blob([png]), 'second.png');
  form.append('mask', new Blob([png], { type: 'image/png' }), 'mask.png');
  const multipart = new Request('http://localhost/', { method: 'POST', body: form });
  const parsed = await parseImageRequest(
    Buffer.from(await multipart.arrayBuffer()),
    multipart.headers.get('content-type'),
    'edits',
  );
  assert.equal(parsed.images.length, 2);
  assert.deepEqual(parsed.mask, reference);
  assert.equal(parsed.stream, true);
  assert.equal(parsed.image, undefined);
  const duplicate = new FormData();
  duplicate.append('prompt', 'one');
  duplicate.append('prompt', 'two');
  const bad = new Request('http://localhost/', { method: 'POST', body: duplicate });
  await assert.rejects(
    parseImageRequest(
      Buffer.from(await bad.arrayBuffer()),
      bad.headers.get('content-type'),
      'edits',
    ),
  );
});

test('image output uses actual bytes and rejects incomplete, malformed or falsely declared output', () => {
  assert.equal(validateDesktopImageResponse(response).size, '1x1');
  for (const value of [
    { ...response, data: [] },
    { ...response, size: '2560x1440' },
    { ...response, output_format: 'jpeg' },
    { ...response, data: [{ b64_json: 'broken' }] },
  ])
    assert.throws(
      () => validateDesktopImageResponse(value),
      (e) => e.code === 'invalid_image_response',
    );
});

test('Host image routes are disabled without image configuration and models stay isolated', async () => {
  const disabled = await fixture({ disabled: true });
  try {
    assert.equal((await disabled.post()).status, 404);
    assert.equal(disabled.calls.length, 0);
  } finally {
    await disabled.close();
  }
  const f = await fixture();
  try {
    const models = await (await f.api('/v1/models')).json();
    assert.deepEqual(
      models.data.map((x) => x.id),
      ['text-fixture', model],
    );
    assert.equal((await f.post({ ...request, model: 'text-fixture' })).status, 400);
    assert.equal(f.calls.length, 0);
  } finally {
    await f.close();
  }
});

test('real HTTP generation returns OpenAI data, actual output, immutable receipts and no prompt/image logging', async () => {
  const id = randomUUID();
  async function inspectCommittedReceipt(path, phase) {
    const directory = join(path, 'operations');
    // Atomic-save staging names can disappear after readdir; only committed receipts are stable.
    const records = (await readdir(directory)).filter((name) => /^[a-f0-9]{64}\.json$/.test(name));
    assert.deepEqual(records, [sha(id) + '.json']);
    const saved = await readFile(join(directory, records[0]), 'utf8');
    assert(!saved.includes(request.prompt));
    assert(!saved.includes(png.toString('base64')));
    const value = JSON.parse(saved);
    assert.equal(value.id, id);
    assert.equal(value.endpoint, '/v1/images/generations');
    assert.equal(value.phase, phase);
    assert.equal(value.upstreamSubmissions, 1);
    assert.equal(value.requestSha256, sha(JSON.stringify(request)));
    return value;
  }
  let submitted;
  const f = await fixture({
    observeSubmission: async (path) => {
      assert.equal(submitted, undefined);
      submitted = await inspectCommittedReceipt(path, 'submitting');
    },
  });
  try {
    const res = await f.post(request, '/v1/images/generations', id);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('x-agent-hub-image-parameters'), 'prompt-preferences');
    const value = await res.json();
    assert.deepEqual(value, response);
    assert.equal(f.calls.length, 1);
    assert.equal(submitted.phase, 'submitting');
    assert.match(f.calls[0].body.prompt, /16:9/);
    const receipt = await (await f.api('/v1/requests/' + id)).json();
    assert.equal(receipt.phase, 'completed');
    assert.equal(receipt.upstreamSubmissions, 1);
    assert.equal(receipt.imageOutput.size, '1x1');
    assert.equal(receipt.requestSha256, sha(JSON.stringify(request)));
    assert.notEqual(receipt.requestSha256, receipt.upstreamRequestSha256);
    assert.equal((await f.post(request, '/v1/images/generations', id)).status, 409);
    assert.equal(f.calls.length, 1);
    const saved = await inspectCommittedReceipt(f.path, 'completed');
    assert.equal(saved.responseSha256, receipt.responseSha256);
    assert.deepEqual(saved.imageOutput, receipt.imageOutput);
    const reservedId = randomUUID();
    await writeFile(join(f.path, 'operations', sha(reservedId) + '.json'), '', { mode: 0o600 });
    assert.equal((await f.post(request, '/v1/images/generations', reservedId)).status, 409);
    assert.equal(f.calls.length, 1);
  } finally {
    await f.close();
  }
});

test('real HTTP SDK multipart edit invokes only the fixed edit transport', async () => {
  const f = await fixture();
  try {
    const form = new FormData();
    form.append('model', model);
    form.append('prompt', 'change background');
    form.append('size', '1536x1024');
    form.append('image', new Blob([png], { type: 'image/png' }), 'source.png');
    const result = await f.api('/v1/images/edits', { method: 'POST', body: form });
    assert.equal(result.status, 200);
    assert.deepEqual(await result.json(), response);
    assert.equal(f.calls[0].kind, 'edit');
    assert.deepEqual(f.calls[0].body.images, [reference]);
    assert.match(f.calls[0].body.prompt, /3:2/);
  } finally {
    await f.close();
  }
});

test('final-only SSE commits its receipt before clients can close on completion', async () => {
  const f = await fixture();
  const id = randomUUID();
  try {
    const result = await f.post(
      { ...request, stream: true, partial_images: 0 },
      '/v1/images/generations',
      id,
    );
    assert.equal(result.headers.get('content-type'), 'text/event-stream; charset=utf-8');
    const bytes = await result.text();
    assert.match(bytes, /event: image_generation.completed/);
    const data = JSON.parse(bytes.split('data: ')[1]);
    assert.equal(data.b64_json, response.data[0].b64_json);
    assert.equal(data.size, '1x1');
    const receipt = await (await f.api('/v1/requests/' + id)).json();
    assert.equal(receipt.phase, 'completed');
    assert.equal(receipt.responseSha256, sha(bytes));
    assert.equal(receipt.responseBytes, Buffer.byteLength(bytes));
  } finally {
    await f.close();
  }
});

test('malformed success is outcome-unknown, upstream rejection fails, neither gets retried', async () => {
  for (const options of [{ response: { ...response, size: '4096x4096' } }, { status: 403 }]) {
    const f = await fixture(options),
      id = randomUUID();
    try {
      assert.equal(
        (await f.post(request, '/v1/images/generations', id)).status,
        options.status ?? 502,
      );
      const receipt = await (await f.api('/v1/requests/' + id)).json();
      assert.equal(receipt.phase, options.status ? 'failed' : 'outcome-unknown');
      assert.equal(receipt.upstreamSubmissions, 1);
      assert.equal(f.calls.length, 1);
      assert.equal((await f.post(request, '/v1/images/generations', id)).status, 409);
    } finally {
      await f.close();
    }
  }
});

test('image concurrency and disconnect share the original durable no-replay boundary', async () => {
  const f = await fixture({ pending: true });
  const id = randomUUID(),
    controller = new AbortController();
  try {
    const pending = f.api('/v1/images/generations', {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'idempotency-key': id },
      body: JSON.stringify({ ...request, stream: true }),
      signal: controller.signal,
    });
    await pending;
    assert.equal((await f.post(request, '/v1/images/generations', id)).status, 409);
    assert.equal((await f.post()).status, 429);
    controller.abort();
    for (let i = 0; i < 30; i++) {
      const receipt = await (await f.api('/v1/requests/' + id)).json();
      if (receipt.phase === 'outcome-unknown') break;
      await new Promise((done) => setTimeout(done, 10));
    }
    const receipt = await (await f.api('/v1/requests/' + id)).json();
    assert.equal(receipt.phase, 'outcome-unknown');
    assert.equal((await f.post(request, '/v1/images/generations', id)).status, 409);
    assert.equal(f.calls.length, 1);
  } finally {
    controller.abort();
    await f.close();
  }
});

test('desktop image adapter retains fixed upstreams and read-only authorization for both operations', async () => {
  const claims = {
    exp: Date.now() / 1000 + 3600,
    sub: 'fixture',
    'https://api.openai.com/auth': { chatgpt_account_id: 'fixture-account' },
  };
  const token =
    'header.' + Buffer.from(JSON.stringify(claims)).toString('base64url') + '.signature';
  const metadata = async () => ({
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
  });
  const urls = [];
  let submissions = 0;
  const fake = (url, options, callback) => {
    urls.push(url);
    assert.equal(options.headers.Authorization, 'Bearer ' + token);
    assert.equal(options.headers.Accept, 'application/json');
    const req = new EventEmitter();
    req.end = () => {
      const res = new PassThrough();
      res.statusCode = 200;
      setImmediate(() => {
        callback(res);
        res.end(JSON.stringify(response));
      });
    };
    return req;
  };
  const adapter = new DesktopImages('/unused', '/unused', metadata, fake);
  try {
    for (const method of ['generate', 'edit']) {
      const result = await adapter[method](
        Buffer.from('{}'),
        new AbortController().signal,
        async () => {
          submissions++;
        },
      );
      for await (const _part of result) {
        /* consume */
      }
    }
    assert.deepEqual(urls, [
      'https://chatgpt.com/backend-api/codex/images/generations',
      'https://chatgpt.com/backend-api/codex/images/edits',
    ]);
    assert.equal(submissions, 2);
  } finally {
    adapter.close();
  }
});
