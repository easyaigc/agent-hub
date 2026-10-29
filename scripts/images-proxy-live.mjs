// Explicit, billable integration acceptance. Never included in pnpm check.
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { randomUUID, createHash } from 'node:crypto';
import { request as httpRequest } from 'node:http';
import { createHostResponsesProxy } from '../apps/host-agent/dist/responses-proxy.js';
import {
  DesktopResponses,
  DesktopImages,
  inspectImage,
} from '../packages/codex-adapter/dist/index.js';

if (process.argv.length !== 4 || process.argv[2] !== '--state-dir')
  throw Error('Use --state-dir with a new private acceptance directory');
const directory = resolve(process.argv[3]);
await mkdir(directory, { mode: 0o700 });
const sha = (value) => createHash('sha256').update(value).digest('hex');
const model = 'gpt-image-2',
  taskId = 'images-proxy-live-' + randomUUID();
const variant = process.env.IMAGE_PROXY_ACCEPTANCE_VARIANT ?? 'circle';
if (!['circle', 'triangle', 'bars'].includes(variant)) throw Error('Unknown acceptance variant');
const config = {
  bind: '127.0.0.1',
  port: 8797,
  directory,
  tokenFile: join(directory, 'access-token'),
  models: [],
  maxBytes: 8192,
  maxConcurrency: 2,
  timeoutMs: 180000,
  images: { models: [model], maxBytes: 32 * 1024 * 1024, maxConcurrency: 1, timeoutMs: 600000 },
};
const executable = process.env.CODEX_EXECUTABLE ?? 'codex';
const evidence = {
  taskId,
  startedAt: new Date().toISOString(),
  model,
  variant,
  automaticRetries: 0,
  productionServiceChanged: false,
  operations: [],
  fingerprints: {},
  upstreamEvents: [],
};
const imageTransport = new DesktopImages(process.env.CODEX_HOME, executable);
for (const method of ['generate', 'edit']) {
  const original = imageTransport[method].bind(imageTransport);
  imageTransport[method] = async (...args) => {
    const res = await original(...args);
    const id = evidence.operations.at(-1)?.id;
    res.on('error', (error) =>
      evidence.upstreamEvents.push({
        id,
        method,
        event: 'error',
        name: error.name,
        code: error.code,
      }),
    );
    res.on('aborted', () =>
      evidence.upstreamEvents.push({
        id,
        method,
        event: 'aborted',
        complete: res.complete,
        readableEnded: res.readableEnded,
      }),
    );
    res.on('end', () =>
      evidence.upstreamEvents.push({
        id,
        method,
        event: 'end',
        complete: res.complete,
        readableEnded: res.readableEnded,
      }),
    );
    res.on('close', () =>
      evidence.upstreamEvents.push({
        id,
        method,
        event: 'close',
        complete: res.complete,
        readableEnded: res.readableEnded,
      }),
    );
    return res;
  };
}
const proxy = await createHostResponsesProxy(
  config,
  new DesktopResponses(process.env.CODEX_HOME, executable),
  imageTransport,
);
for (const file of [
  'apps/host-agent/src/responses-proxy.ts',
  'apps/host-agent/src/image-proxy.ts',
  'packages/codex-adapter/src/desktop-images.ts',
  'packages/codex-adapter/src/desktop-responses.ts',
  'apps/host-agent/dist/responses-proxy.js',
  'apps/host-agent/dist/image-proxy.js',
  'packages/codex-adapter/dist/desktop-images.js',
  'packages/codex-adapter/dist/desktop-responses.js',
])
  evidence.fingerprints[file] = sha(await readFile(file));
const save = () =>
  writeFile(join(directory, 'evidence.json'), JSON.stringify(evidence, null, 2), { mode: 0o600 });
await save();
try {
  await new Promise((done, reject) => {
    proxy.server.once('error', reject);
    proxy.server.listen(0, '127.0.0.1', done);
  });
  await proxy.ready();
  const url = `http://127.0.0.1:${proxy.server.address().port}`;
  const key = (await readFile(config.tokenFile, 'utf8')).trim();
  // Native HTTP makes the explicit acceptance timeout authoritative. Node fetch
  // otherwise has an independent ~300s header/body idle limit for nonstreaming.
  const api = async (path, init = {}) => {
    const headers = { Authorization: 'Bearer ' + key, ...init.headers };
    let body = init.body;
    if (body instanceof FormData) {
      const multipart = new Request(url + path, { method: 'POST', body });
      headers['content-type'] = multipart.headers.get('content-type');
      body = Buffer.from(await multipart.arrayBuffer());
    }
    if (body !== undefined) {
      body = Buffer.from(body);
      headers['content-length'] = String(body.length);
    }
    return new Promise((done, reject) => {
      const req = httpRequest(
        url + path,
        { method: init.method ?? 'GET', headers, signal: AbortSignal.timeout(660000) },
        (res) => {
          let count = 0;
          const chunks = [];
          res.on('data', (chunk) => {
            count += chunk.length;
            if (count > 64 * 1024 * 1024) {
              res.destroy(new Error('Acceptance response budget exceeded'));
              return;
            }
            chunks.push(chunk);
          });
          res.on('error', reject);
          res.on('end', () => {
            const responseHeaders = new Headers();
            for (const [name, value] of Object.entries(res.headers))
              if (value !== undefined)
                responseHeaders.set(name, Array.isArray(value) ? value.join(',') : value);
            done(
              new Response(Buffer.concat(chunks), {
                status: res.statusCode,
                headers: responseHeaders,
              }),
            );
          });
        },
      );
      req.on('error', reject);
      req.end(body);
    });
  };
  evidence.models = await (await api('/v1/models')).json();
  const unsupported = await api('/v1/images/generations', {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ model: 'unverified-image-model', prompt: 'Model rejection preflight' }),
  });
  evidence.unsupportedModelStatus = unsupported.status;
  if (unsupported.status !== 400) throw Error('Unverified model was not rejected');
  async function execute(name, path, body, headers = {}) {
    const id = randomUUID(),
      op = { id, name, path, startedAt: new Date().toISOString(), phase: 'waiting' };
    evidence.operations.push(op);
    await save();
    console.log(JSON.stringify({ taskId, name, id, phase: op.phase }));
    const start = Date.now();
    let image;
    try {
      const res = await api(path, {
        method: 'POST',
        headers: { ...headers, 'idempotency-key': id },
        body,
      });
      op.status = res.status;
      op.elapsedMs = Date.now() - start;
      let data;
      if (res.headers.get('content-type')?.includes('text/event-stream')) {
        const raw = await res.text();
        op.responseSha256 = sha(raw);
        const events = raw.split(/\r?\n\r?\n/).flatMap((block) => {
          const value = block.split(/\r?\n/).find((x) => x.startsWith('data: '));
          return value ? [JSON.parse(value.slice(6))] : [];
        });
        data = events.find((x) => x.type === 'image_edit.completed');
        if (!data) throw Error('Missing final edit event');
        image = Buffer.from(data.b64_json, 'base64');
        op.finalEventType = data.type;
      } else {
        data = await res.json();
        if (res.status !== 200) throw Error('Host rejected image operation');
        image = Buffer.from(data.data[0].b64_json, 'base64');
      }
      const info = inspectImage(image);
      op.output = { ...info, size: data.size, quality: data.quality };
      op.imageSha256 = sha(image);
      op.imageBytes = image.length;
      await writeFile(join(directory, name + '.' + info.format), image, {
        mode: 0o600,
        flag: 'wx',
      });
      op.receipt = await (await api('/v1/requests/' + id)).json();
      if (
        op.receipt.phase !== 'completed' ||
        op.receipt.upstreamSubmissions !== 1 ||
        op.receipt.imageOutput.size !== `${info.width}x${info.height}`
      )
        throw Error('Receipt does not match output');
      if (op.responseSha256 && op.receipt.responseSha256 !== op.responseSha256)
        throw Error('SSE bytes do not match receipt');
      op.phase = 'passed';
      op.elapsedMs = Date.now() - start;
    } catch (cause) {
      op.error = {
        name: cause?.name ?? 'Error',
        code: cause?.cause?.code ?? cause?.code ?? 'acceptance_failed',
      };
      op.phase = 'failed-or-unknown';
      op.elapsedMs = Date.now() - start;
      try {
        op.receipt = await (await api('/v1/requests/' + id)).json();
      } catch {
        op.receiptUnavailable = true;
      }
      image = undefined;
    }
    await save();
    console.log(JSON.stringify(op));
    return image;
  }
  const generated = await execute(
    'generated',
    '/v1/images/generations',
    JSON.stringify({
      model,
      prompt:
        variant === 'bars'
          ? 'A solid dark blue horizontal bar centered on a flat pink background. No text, gradients or borders.'
          : variant === 'triangle'
            ? 'A small solid orange equilateral triangle centered on a flat purple background. No text, shading or borders.'
            : 'A minimal solid blue background with one small white circle centered. No text or borders.',
      size: '1536x864',
      quality: 'high',
      output_format: 'png',
      n: null,
      background: null,
    }),
    { 'content-type': 'application/json' },
  );
  if (generated) {
    const form = new FormData();
    form.append('model', model);
    form.append(
      'prompt',
      variant === 'bars'
        ? 'Change only the pink background to solid yellow. Preserve the centered dark blue horizontal bar and its size. No text, gradients or borders.'
        : variant === 'triangle'
          ? 'Change only the purple background to solid white. Preserve the orange triangle, its centered position and the source composition. No text, shading or borders.'
          : 'Change only the blue background to green. Preserve the centered white circle and the source composition. No text or borders.',
    );
    form.append('size', '1536x864');
    form.append('input_fidelity', 'high');
    form.append('stream', 'true');
    form.append('partial_images', '0');
    form.append('image', new Blob([generated], { type: 'image/png' }), 'synthetic-reference.png');
    await execute('edited', '/v1/images/edits', form);
  }
} finally {
  await proxy.close();
  for (const op of evidence.operations) {
    const receiptPath = join(directory, 'operations', sha(op.id) + '.json');
    try {
      op.finalReceipt = JSON.parse(await readFile(receiptPath, 'utf8'));
    } catch {
      op.finalReceiptUnavailable = true;
    }
  }
  evidence.finishedAt = new Date().toISOString();
  evidence.listenerClosed = true;
  await save();
  console.log(
    JSON.stringify({
      taskId,
      evidence: join(directory, 'evidence.json'),
      passed: evidence.operations.filter((x) => x.phase === 'passed').length,
      total: evidence.operations.length,
      listenerClosed: true,
    }),
  );
}
if (evidence.operations.length !== 2 || evidence.operations.some((x) => x.phase !== 'passed'))
  process.exitCode = 1;
