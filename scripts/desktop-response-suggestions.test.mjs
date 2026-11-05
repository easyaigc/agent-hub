import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFileSync, readdirSync, rmSync } from 'node:fs';
import http from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { DesktopResponseSuggestions } from '../packages/codex-adapter/dist/desktop-response-suggestions.js';
import { SuggestionObservationError } from '../packages/codex-adapter/dist/desktop-suggestion-observer.js';
import {
  makePrivateDirectorySync,
  writePrivateFileSync,
} from '../packages/codex-adapter/dist/desktop-platform.js';

const target = {
  agentId: 'codex',
  hostId: 'host-a',
  workspaceId: 'workspace-a',
  runtimeId: 'desktop-runtime',
};
const desktop = { pid: 10, startedAtUnixMs: 1000, executableSha256: 'a'.repeat(64) };
const peer = {
  ...desktop,
  executorPid: 11,
  executorStartedAtUnixMs: 1001,
  executorSha256: 'b'.repeat(64),
};
const secret = 'fixture-authorization-never-persist';
const waitFor = async (fn, description) => {
  const deadline = Date.now() + 10000;
  while (Date.now() < deadline) {
    const value = await fn();
    if (value) return value;
    await delay(25);
  }
  throw new Error(`Timed out: ${description}`);
};
const listen = (server) =>
  new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
const close = (server) => new Promise((resolve) => server.close(resolve));
const frame = (type, value) => `data: ${JSON.stringify({ type, ...value })}\n\n`;

test(
  'response collector fences identity, generation, persistence and lifecycle across real loopback HTTP',
  { timeout: 60000 },
  async () => {
    const root = join(
      process.env.AGENT_HUB_RESPONSE_TEST_PARENT || tmpdir(),
      `response-suggestions-${randomUUID()}`,
    );
    makePrivateDirectorySync(root);
    let calls = 0,
      trusted = true,
      hold;
    const peerQueue = [];
    let peerCalls = 0;
    const heldCapacityResponses = [];
    const upstream = http.createServer((req, res) => {
      if (req.method === 'GET' && req.url === '/backend/models') {
        req.socket.destroy();
        return;
      }
      const chunks = [];
      req.on('data', (chunk) => chunks.push(chunk));
      req.on('end', () => {
        calls++;
        const body = JSON.parse(Buffer.concat(chunks));
        const reply = () => {
          const id = body.fixtureResponseId;
          const events = frame('response.created', { response: { id } });
          const output =
            body.fixtureMode === 'failed'
              ? frame('response.failed', { response: { id } })
              : body.fixtureMode === 'cancelled'
                ? frame('response.incomplete', { response: { id } })
                : frame('response.output_item.done', {
                    item: {
                      type: 'message',
                      role: 'assistant',
                      content: [
                        {
                          type: 'output_text',
                          text: JSON.stringify({
                            suggestion: body.fixtureMode === 'empty' ? null : body.fixtureText,
                          }),
                        },
                      ],
                    },
                  }) + frame('response.completed', { response: { id, status: 'completed' } });
          res.writeHead(200, { 'content-type': 'text/event-stream' });
          res.end(events + output);
        };
        if (body.fixtureMode === 'capacity') {
          res.writeHead(200, { 'content-type': 'text/event-stream' });
          res.write(': held\n\n');
          heldCapacityResponses.push(res);
        } else if (body.fixtureMode === 'held') hold = reply;
        else reply();
      });
    });
    await listen(upstream);
    const reservation = http.createServer();
    await listen(reservation);
    const port = reservation.address().port;
    await close(reservation);
    const bindingId = randomUUID(),
      config = join(root, 'config.json');
    writePrivateFileSync(config, JSON.stringify({ version: 1, port, bindingId }), { flag: 'wx' });
    const options = {
      directory: root,
      configPath: config,
      target: () => target,
      fixture: {
        desktop: async () => desktop,
        peer: () => {
          peerCalls++;
          return peerQueue.length
            ? peerQueue.shift()()
            : Promise.resolve(trusted ? peer : { ...peer, pid: 12 });
        },
        forwarder: { upstream: `http://127.0.0.1:${upstream.address().port}/backend` },
      },
    };
    let collector = new DesktopResponseSuggestions(options);
    const endpoint = `http://127.0.0.1:${port}/_agent_hub/${bindingId}/backend-api/responses`;
    const payload = (thread, turn, responseId, text, mode = 'completed') =>
      JSON.stringify({
        fixtureResponseId: responseId,
        fixtureText: text,
        fixtureMode: mode,
        client_metadata: {
          'x-codex-turn-metadata': JSON.stringify({
            prediction_source_thread_id: thread,
            prediction_source_turn_id: turn,
          }),
        },
      });
    const send = (thread, turn, responseId, text, mode = 'completed') =>
      fetch(endpoint, {
        method: 'POST',
        headers: { authorization: `Bearer ${secret}` },
        body: payload(thread, turn, responseId, text, mode),
      });
    const sendWithWriteReceipt = (thread, turn, responseId, text) => {
      let writtenResolve, writtenReject;
      const written = new Promise((resolve, reject) => {
        writtenResolve = resolve;
        writtenReject = reject;
      });
      const response = new Promise((resolve, reject) => {
        const request = http.request(
          endpoint,
          { method: 'POST', agent: false, headers: { authorization: `Bearer ${secret}` } },
          (incoming) => {
            const chunks = [];
            incoming.on('data', (chunk) => chunks.push(chunk));
            incoming.on('end', () =>
              resolve({ status: incoming.statusCode, text: Buffer.concat(chunks).toString() }),
            );
            incoming.on('error', reject);
          },
        );
        request.once('finish', writtenResolve);
        request.once('error', (error) => {
          writtenReject(error);
          reject(error);
        });
        request.end(payload(thread, turn, responseId, text));
      });
      response.catch(() => {});
      return { written, response };
    };
    const read = (thread, turn, which = target) =>
      collector.readBoundSourceAsync(which, thread, turn);
    const notReadable = async (thread, turn) => {
      try {
        return (await read(thread, turn)).suggestion === null;
      } catch (error) {
        if ([409, 503].includes(error.status)) return true;
        throw error;
      }
    };
    const eventuallyText = (thread, turn, text) =>
      waitFor(async () => {
        const result = await read(thread, turn);
        return result.suggestion?.text === text && result;
      }, 'persisted suggestion');
    const ready = () =>
      waitFor(
        () => collector.capabilities.includes('desktop:suggestions:read:v1'),
        'collector ready',
      );
    const persisted = (thread) => {
      const directory = join(root, 'desktop-response-suggestions');
      for (const key of readdirSync(directory)) {
        try {
          return JSON.parse(
            JSON.parse(readFileSync(join(directory, key, `${thread}.json`), 'utf8')).payload,
          );
        } catch (error) {
          if (error.code !== 'ENOENT') throw error;
        }
      }
      return null;
    };
    const t1 = randomUUID(),
      t2 = randomUUID(),
      s1 = randomUUID(),
      s2 = randomUUID();
    try {
      collector.start();
      await ready();
      const response = await send(t1, s1, 'resp-one', '首条 𠮷');
      assert.equal(response.status, 200);
      await response.text();
      const first = await eventuallyText(t1, s1, '首条 𠮷');
      assert.equal(first.threadId, t1);
      assert.equal(first.sourceTurnId, s1);
      assert.equal(first.provenance.responseId, 'resp-one');
      const models = await fetch(endpoint.replace(/\/responses$/, '/models'));
      assert.equal(models.status, 502, 'failed passthrough reports upstream failure');
      await models.text();
      assert.ok(collector.capabilities.includes('desktop:suggestions:read:v1'));
      const afterModelsFailure = await read(t1, s1);
      assert.equal(afterModelsFailure.suggestion?.text, '首条 𠮷');
      assert.equal(afterModelsFailure.provenance.responseId, 'resp-one');
      assert.equal((await read(t1, randomUUID())).suggestion, null);
      await assert.rejects(
        read(t1, s1, { ...target, hostId: 'different' }),
        (e) => e instanceof SuggestionObservationError && e.status === 409,
      );
      const beforeReads = calls;
      await read(t1, s1);
      await read(t1, s1);
      assert.equal(calls, beforeReads, 'reads must not send generation requests');

      const prepared = await collector.prepareBoundSourceAsync(
        target,
        t1,
        s1,
        performance.now() + 8000,
      );
      const heldRequest = send(t1, s1, 'resp-new', '替换', 'held');
      await waitFor(() => hold, 'new request reaches upstream');
      await waitFor(
        async () => (await read(t1, s1)).suggestion === null,
        'new request invalidates old suggestion',
      );
      await assert.rejects(prepared.commit(), (e) => e.status === 409);
      prepared.dispose();
      hold();
      await (await heldRequest).text();
      await eventuallyText(t1, s1, '替换');

      await Promise.all(
        [send(t1, s1, 'resp-A', '来源甲'), send(t2, s2, 'resp-B', '来源乙')].map(async (p) =>
          (await p).text(),
        ),
      );
      const [a, b] = await Promise.all([
        eventuallyText(t1, s1, '来源甲'),
        eventuallyText(t2, s2, '来源乙'),
      ]);
      assert.equal(a.provenance.responseId, 'resp-A');
      assert.equal(b.provenance.responseId, 'resp-B');
      assert.equal((await read(t1, s2)).suggestion, null);
      const independent = await collector.prepareBoundSourceAsync(
        target,
        t1,
        s1,
        performance.now() + 8000,
      );
      await (await send(t2, s2, 'resp-B-next', '来源乙更新')).text();
      await eventuallyText(t2, s2, '来源乙更新');
      assert.equal(
        (await independent.commit()).suggestion?.text,
        '来源甲',
        'another thread must not invalidate this prepared read',
      );
      independent.dispose();

      let rejectSlowPeer,
        slowPeerStarted = false;
      peerQueue.push(() => {
        slowPeerStarted = true;
        return new Promise((_, reject) => {
          rejectSlowPeer = reject;
        });
      });
      const rejectedPeerResponse = await send(t1, s1, 'resp-rejected-peer', '拒绝来源');
      assert.equal(rejectedPeerResponse.status, 200);
      await rejectedPeerResponse.text();
      await waitFor(() => slowPeerStarted, 'delayed peer verification started');
      await waitFor(
        () => notReadable(t1, s1),
        'pending unverified peer fences old same-thread result',
      );
      rejectSlowPeer(new Error('synthetic peer rejected'));
      await delay(150);
      assert.equal(
        await notReadable(t1, s1),
        true,
        'peer rejection must not restore the previous result',
      );

      await (await send(t1, s1, 'resp-before-slow', '慢验证之前')).text();
      await eventuallyText(t1, s1, '慢验证之前');
      let resolveOldPeer,
        oldPeerStarted = false;
      peerQueue.push(() => {
        oldPeerStarted = true;
        return new Promise((resolve) => {
          resolveOldPeer = resolve;
        });
      });
      const older = await send(t1, s1, 'resp-slow-old', '旧慢请求');
      assert.equal(older.status, 200);
      await older.text();
      await waitFor(() => oldPeerStarted, 'older delayed peer started');
      await waitFor(() => notReadable(t1, s1), 'older request fences previous same-thread result');
      await (await send(t1, s1, 'resp-newer', '较新请求')).text();
      await eventuallyText(t1, s1, '较新请求');
      resolveOldPeer(peer);
      await delay(250);
      const newest = await read(t1, s1);
      assert.equal(
        newest.suggestion?.text,
        '较新请求',
        'old delayed peer must not overwrite newer request',
      );
      assert.equal(newest.provenance.responseId, 'resp-newer');

      trusted = false;
      const untrusted = await send(t1, s1, 'resp-untrusted', '不可发布');
      assert.equal(untrusted.status, 200);
      assert.match(await untrusted.text(), /不可发布/);
      await delay(250);
      assert.notEqual((await read(t1, s1)).suggestion?.text, '不可发布');
      trusted = true;
      for (const mode of ['empty', 'failed', 'cancelled']) {
        const baseline = `旧结果-${mode}`;
        await (await send(t1, s1, `resp-before-${mode}`, baseline)).text();
        await eventuallyText(t1, s1, baseline);
        const r = await send(t1, s1, `resp-${mode}`, '不应回退', mode);
        assert.equal(r.status, 200);
        await r.text();
        await waitFor(() => {
          const record = persisted(t1);
          return record?.responseId === `resp-${mode}` && record.state === mode;
        }, `${mode} terminal record persisted`);
        await waitFor(
          async () => (await read(t1, s1)).suggestion === null,
          `${mode} must not return prior result`,
        );
      }
      await (await send(t1, s1, 'resp-before-capacity', '容量前旧结果')).text();
      await eventuallyText(t1, s1, '容量前旧结果');
      await delay(300);
      const beforeCapacity = peerCalls;
      const open = await Promise.all(
        Array.from({ length: 64 }, () =>
          send(randomUUID(), randomUUID(), randomUUID(), '保持连接', 'capacity'),
        ),
      );
      assert.ok(open.every((response) => response.status === 200));
      await waitFor(
        () => heldCapacityResponses.length === 64 && peerCalls === beforeCapacity + 64,
        '64 live connections verified',
      );
      const overflowRequest = sendWithWriteReceipt(t1, s1, 'resp-over-capacity', '超限新请求');
      await overflowRequest.written;
      await waitFor(() => notReadable(t1, s1), 'over-capacity new request fences old result');
      heldCapacityResponses[0].end();
      const overflow = await overflowRequest.response;
      assert.equal(overflow.status, 200);
      assert.match(overflow.text, /超限新请求/, 'over-capacity request must still forward');
      assert.equal(
        peerCalls,
        beforeCapacity + 64,
        'over-capacity peer must not enter verification queue',
      );
      for (const response of heldCapacityResponses) response.end();
      await Promise.all(open.map((response) => response.text()));
      await ready();
      assert.equal(
        (await read(t1, s1)).suggestion,
        null,
        'blind interval must not restore the previous result',
      );
      await (await send(t1, s1, 'resp-after-capacity', '容量恢复后')).text();
      const recovered = await eventuallyText(t1, s1, '容量恢复后');
      assert.equal(recovered.provenance.responseId, 'resp-after-capacity');
      const files = (directory) =>
        readdirSync(directory, { withFileTypes: true }).flatMap((entry) =>
          entry.isDirectory() ? files(join(directory, entry.name)) : [join(directory, entry.name)],
        );
      for (const file of files(root))
        assert.equal(
          readFileSync(file).includes(Buffer.from(secret)),
          false,
          'credential must not persist',
        );
      collector.close();
      await assert.rejects(
        read(t1, s1),
        (e) => e instanceof SuggestionObservationError && e.status === 503,
      );
      await waitFor(async () => {
        try {
          await fetch(endpoint);
          return false;
        } catch {
          return true;
        }
      }, 'listener closed');
      await assert.rejects(read(t1, s1));
      collector = new DesktopResponseSuggestions(options);
      collector.start();
      await ready();
      assert.equal(
        (await read(t2, s2)).suggestion,
        null,
        'new Host epoch must not republish previous result',
      );
    } finally {
      for (const response of heldCapacityResponses) response.destroy();
      collector.close();
      upstream.closeAllConnections();
      await close(upstream);
      await waitFor(async () => {
        try {
          await fetch(endpoint);
          return false;
        } catch {
          return true;
        }
      }, 'listener released');
      rmSync(root, { recursive: true, force: true });
    }
  },
);
