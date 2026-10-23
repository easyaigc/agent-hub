import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { resolve, join } from 'node:path';
import { randomUUID, createHash } from 'node:crypto';
import { setTimeout as pause } from 'node:timers/promises';
import { DesktopDomBridge } from '../packages/codex-adapter/dist/desktop-dom-bridge.js';
import { DesktopDomSuggestions } from '../apps/host-agent/dist/desktop-dom-suggestions.js';

// A real, isolated Chromium renderer with synthetic content. Never attaches to Codex or user Chrome.
const executable =
  process.env.DOM_BRIDGE_TEST_CHROME ??
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
if (!existsSync(executable))
  throw new Error('Set DOM_BRIDGE_TEST_CHROME to a local Chromium executable');
const directory = resolve('.local/desktop-dom-bridge-smoke', randomUUID());
await mkdir(directory, { recursive: true, mode: 0o700 });
const profile = join(directory, 'browser');
await mkdir(profile, { mode: 0o700 });
const threadId = '00000000-0000-4000-8000-000000000001';
const sourceTurnId = '00000000-0000-4000-8000-000000000002';
const otherThreadId = '00000000-0000-4000-8000-000000000003';
const expected = '核对只读建议，保留 <标记> & 换行\n第二行，不提交输入。';
const escape = (value) =>
  value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
const widget = (text) =>
  `<span data-cumberbatch inert aria-hidden="true"><span class="cumberbatch-content">${escape(text)}</span><span>Press Tab to accept</span></span>`;
const composer = (body, style = '') =>
  `<div class="ProseMirror" role="textbox" contenteditable="true" style="${style}"><p>${body}</p></div>`;
const server = createServer((request, response) => {
  const mode = new URL(request.url, 'http://localhost').searchParams.get('mode') ?? 'normal';
  let body = composer(widget(expected));
  if (mode === 'empty') body = composer('<br>');
  if (mode === 'regenerated') body = composer(widget('同一轮次重新生成后的展示原文。'));
  if (mode === 'draft') body = composer('尚未发送的草稿' + widget(expected));
  if (mode === 'hidden') body += composer(widget('隐藏副本'), 'display:none');
  if (mode === 'ambiguous') body += composer(widget('第二个可见输入框'));
  if (mode === 'two-suggestions') body = composer(widget(expected) + widget('第二条'));
  if (mode === 'oversized') body = composer(widget('长'.repeat(17000)));
  if (mode === 'long') body = composer(widget('长'.repeat(7500)));
  response.writeHead(200, {
    'Content-Type': 'text/html; charset=utf-8',
    'Cache-Control': 'no-store',
  });
  response.end(
    `<html><head><meta charset="utf-8"><title>Isolated DOM Bridge test</title><style>body{padding:20px}.ProseMirror{padding:16px;width:650px;white-space:pre-wrap;border:1px solid #ddd}.cumberbatch-content{color:#aaa}</style></head><body>${body}</body></html>`,
  );
});
await new Promise((done) => server.listen(0, '127.0.0.1', done));
const origin = `http://127.0.0.1:${server.address().port}`;
const browser = spawn(
  executable,
  [
    '--headless=new',
    `--user-data-dir=${profile}`,
    '--remote-debugging-port=0',
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-background-networking',
    '--disable-sync',
    `${origin}/threads/${threadId}`,
  ],
  { stdio: ['ignore', 'ignore', 'pipe'] },
);
browser.stderr.resume();
const NativeWebSocket = globalThis.WebSocket;
const methods = [];
const commandById = new Map();
const cdpErrors = [];
globalThis.WebSocket = class extends NativeWebSocket {
  constructor(...args) {
    super(...args);
    this.addEventListener('message', (event) => {
      const value = JSON.parse(event.data);
      if (value.error) cdpErrors.push({ method: commandById.get(value.id), error: value.error });
    });
  }
  send(value) {
    const command = JSON.parse(value);
    methods.push(command.method);
    commandById.set(command.id, command.method);
    super.send(value);
  }
};
let harness;
const report = {
  kind: 'real-isolated-chromium-synthetic-dom',
  originalCodexVerified: false,
  checks: [],
  browserPid: browser.pid,
  status: 'running',
};
try {
  let port;
  for (let i = 0; i < 150; i++) {
    if (browser.exitCode !== null) throw new Error('The isolated browser exited');
    try {
      port = Number((await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0]);
      break;
    } catch {
      // The isolated browser has not published its debugging port yet.
    }
    await pause(100);
  }
  assert(port > 0);
  const endpoint = `http://127.0.0.1:${port}`;
  let target;
  for (let i = 0; i < 80; i++) {
    const targets = await (await fetch(endpoint + '/json/list')).json();
    target = targets.find(
      (value) => value.type === 'page' && value.url.startsWith(origin + '/threads/'),
    );
    if (target) break;
    await pause(100);
  }
  assert(target);
  const config = { endpoint, targetId: target.id, origin };
  const bridge = new DesktopDomBridge(config);
  harness = new NativeWebSocket(target.webSocketDebuggerUrl);
  await once(harness, 'open');
  let next = 0;
  const pending = new Map();
  let loaded;
  harness.addEventListener('message', (event) => {
    const value = JSON.parse(event.data);
    if (value.method === 'Page.loadEventFired') loaded?.();
    const request = pending.get(value.id);
    if (request) {
      pending.delete(value.id);
      if (value.error) request.reject(new Error('Harness navigation failed'));
      else request.resolve(value.result);
    }
  });
  const call = (method, params = {}) =>
    new Promise((resolve, reject) => {
      const id = ++next;
      pending.set(id, { resolve, reject });
      harness.send(JSON.stringify({ id, method, params }));
    });
  await call('Page.enable');
  const navigate = async (mode) => {
    let timer;
    const done = new Promise((resolve, reject) => {
      loaded = resolve;
      timer = setTimeout(() => reject(new Error('Fixture load timeout')), 5000);
    });
    try {
      await call('Page.navigate', { url: `${origin}/threads/${threadId}?mode=${mode}` });
      await done;
    } finally {
      clearTimeout(timer);
      loaded = undefined;
    }
  };
  const check = async (name, action) => {
    await action();
    report.checks.push(name);
    console.log('PASS ' + name);
  };
  await navigate('normal');
  await check(
    'real DOM reads aria-hidden/inert content and preserves text without acceptance hint',
    async () => {
      const result = await bridge.readExisting(threadId);
      assert.equal(result.status, 'present');
      assert.equal(result.text, expected);
      assert.equal(result.contentSha256, createHash('sha256').update(expected).digest('hex'));
    },
  );
  await check('concurrent reads do not open a second inspection session', async () => {
    const first = bridge.readExisting(threadId);
    await assert.rejects(bridge.readExisting(threadId), { code: 'busy' });
    assert.equal((await first).text, expected);
  });
  await check('wrong chat is rejected before returning text', async () => {
    await assert.rejects(bridge.readExisting(otherThreadId), {
      code: 'source-binding-unavailable',
    });
  });
  await navigate('hidden');
  await check('hidden duplicate input is ignored', async () => {
    assert.equal((await bridge.readExisting(threadId)).text, expected);
  });
  await navigate('ambiguous');
  await check('two visible inputs fail closed', async () => {
    await assert.rejects(bridge.readExisting(threadId), { code: 'ambiguous-composer' });
  });
  await navigate('two-suggestions');
  await check('two visible predictions fail closed', async () => {
    await assert.rejects(bridge.readExisting(threadId), { code: 'ambiguous-suggestion' });
  });
  await navigate('draft');
  await check('existing draft suppresses the prediction result', async () => {
    assert.deepEqual((await bridge.readExisting(threadId)).status, 'draft-present');
  });
  await navigate('empty');
  await check('missing prediction is distinct from an unavailable source', async () => {
    assert.equal((await bridge.readExisting(threadId)).status, 'empty');
  });
  await navigate('oversized');
  await check('oversized DOM text is rejected', async () => {
    await assert.rejects(bridge.readExisting(threadId), { code: 'content-too-large' });
  });
  await navigate('long');
  await check('long text below the limit remains complete', async () => {
    const result = await bridge.readExisting(threadId);
    assert.equal(result.status, 'present');
    assert.equal(result.text, '长'.repeat(7500));
  });
  await navigate('normal');
  const configPath = join(directory, 'bridge.json');
  await writeFile(configPath, JSON.stringify(config), { mode: 0o600 });
  const machine = {
    agentId: 'codex',
    hostId: 'fixture-host',
    workspaceId: 'fixture-workspace',
    runtimeId: 'desktop-runtime',
  };
  let sourceReads = 0,
    changed = false;
  const reader = {
    readingTarget: () => machine,
    desktopSuggestionSource: async () => ({
      threadId,
      sourceTurnId: changed && ++sourceReads > 1 ? otherThreadId : sourceTurnId,
    }),
  };
  const host = new DesktopDomSuggestions(reader, configPath);
  await check('Host reader returns the actual DOM result with full source identity', async () => {
    const result = await host.read(threadId, sourceTurnId, machine);
    assert.equal(result.text, expected);
    assert.equal(result.sourceTurnId, sourceTurnId);
    assert.deepEqual(result.target, machine);
    assert.match(result.id, /^[a-f0-9]{64}$/);
  });
  await check('mobile response uses actual displayed text with a finite lease', async () => {
    const result = await host.readNextTurn(threadId, sourceTurnId, machine);
    assert.equal(result.source, 'desktop-display');
    assert.equal(result.suggestion.text, expected);
    assert.equal(result.suggestion.validity, 'desktop-display');
    assert(Date.parse(result.suggestion.expiresAt) > Date.now());
    assert(Date.parse(result.suggestion.expiresAt) <= Date.now() + 6000);
    assert(host.capabilities.includes('desktop:suggestions:displayed:v1'));
  });
  await navigate('regenerated');
  await check(
    'same source turn returns regenerated renderer text rather than an old prediction',
    async () => {
      assert.equal(
        (await host.readNextTurn(threadId, sourceTurnId, machine)).suggestion.text,
        '同一轮次重新生成后的展示原文。',
      );
    },
  );
  await navigate('empty');
  await check('desktop dismissal returns no suggestion for the same source turn', async () => {
    assert.equal((await host.readNextTurn(threadId, sourceTurnId, machine)).suggestion, null);
  });
  await navigate('normal');
  await check('Host rejects stale requested turn and wrong machine', async () => {
    await assert.rejects(host.read(threadId, otherThreadId, machine), { code: 'stale-turn' });
    await assert.rejects(
      host.read(threadId, sourceTurnId, { ...machine, hostId: 'another-host' }),
      { code: 'wrong-target' },
    );
  });
  changed = true;
  await check('Host revalidates the source after reading DOM', async () => {
    await assert.rejects(host.read(threadId, sourceTurnId, machine), { code: 'stale-turn' });
  });
  await check('unconfigured bridge has an explicit unavailable result', async () => {
    await assert.rejects(new DesktopDomSuggestions(reader).read(threadId, sourceTurnId, machine), {
      code: 'not-configured',
    });
  });
  await check('only the fixed DOM read commands were sent by the product bridge', async () => {
    assert(methods.length > 0);
    assert(
      methods.every((method) =>
        ['DOM.getDocument', 'DOM.querySelectorAll', 'DOM.describeNode', 'DOM.getBoxModel'].includes(
          method,
        ),
      ),
    );
  });
  report.status = 'passed';
  report.methodCounts = Object.fromEntries(
    [...new Set(methods)].map((method) => [
      method,
      methods.filter((value) => value === method).length,
    ]),
  );
} catch (error) {
  report.status = 'failed';
  report.error = error.message;
  process.exitCode = 1;
  console.error(error);
} finally {
  report.cdpErrors = cdpErrors;
  report.methodCounts = Object.fromEntries(
    [...new Set(methods)].map((method) => [
      method,
      methods.filter((value) => value === method).length,
    ]),
  );
  globalThis.WebSocket = NativeWebSocket;
  harness?.close();
  server.closeAllConnections();
  server.close();
  browser.kill('SIGTERM');
  if (browser.exitCode === null && browser.signalCode === null)
    await Promise.race([once(browser, 'exit'), pause(5000)]);
  if (browser.exitCode === null && browser.signalCode === null) {
    browser.kill('SIGKILL');
    await once(browser, 'exit');
  }
  report.browserExited = browser.exitCode !== null || browser.signalCode !== null;
  await writeFile(join(directory, 'result.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(
    JSON.stringify({
      status: report.status,
      checks: report.checks.length,
      originalCodexVerified: false,
      browserExited: report.browserExited,
      evidence: directory,
    }),
  );
}
