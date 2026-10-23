import assert from 'node:assert/strict';
import { createServer as httpsServer } from 'node:https';
import { createServer as httpServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { mkdtemp, readFile, chmod, rm } from 'node:fs/promises';
import { createHash, randomUUID, X509Certificate } from 'node:crypto';
import { join } from 'node:path';
import { replyIntentFingerprint } from '../../packages/codex-adapter/dist/runtime-session.js';

/** Native UI/HTTP fixture only. Its authority and task receipts are synthetic. */
export async function startReplyRecoveryUIFixture(adb, serial) {
  const directory = await mkdtemp('.local/reply-recovery-ui-tls-');
  const keyPath = join(directory, 'key.pem'),
    certificatePath = join(directory, 'certificate.pem');
  execFileSync(
    'openssl',
    [
      'req',
      '-x509',
      '-newkey',
      'rsa:2048',
      '-nodes',
      '-days',
      '1',
      '-subj',
      '/CN=localhost',
      '-addext',
      'subjectAltName=IP:127.0.0.1,DNS:localhost',
      '-keyout',
      keyPath,
      '-out',
      certificatePath,
    ],
    { stdio: 'ignore' },
  );
  await chmod(keyPath, 0o600);
  await chmod(certificatePath, 0o600);
  const certificate = await readFile(certificatePath);
  const fingerprint = createHash('sha256')
    .update(new X509Certificate(certificate).raw)
    .digest('hex');
  const token = 'synthetic-recovery-' + randomUUID();
  let endpoint, target, descriptor, mode, reply, receipt, creation, creationPhase, ledgerId;
  let replyAttempts = [],
    creationSubmits = [],
    acceptedInputs = 0;
  const requests = [],
    scenarios = [],
    fixtureErrors = [];
  const retiredHosts = new Set();
  const thread = (id = 'recovery-thread') => ({
    id,
    hostId: target.hostId,
    title: '恢复验收聊天',
    state: 'idle',
    projectId: null,
  });
  const creationResult = () => ({
    hostId: target.hostId,
    clientOperationId: creation.clientOperationId,
    projectId: creation.projectId,
    phase: creationPhase,
    threadId: creationPhase === 'confirmed' ? 'created-thread' : null,
    error:
      mode === 'creation-preflight' && creationPhase === 'outcome-unknown' && creationSubmits.length
        ? '合成前置检查未通过，首条未写入'
        : null,
    uploaded: [],
    thread: creationPhase === 'confirmed' ? thread('created-thread') : null,
    detached: false,
    ...(mode === 'creation-preflight' &&
    creationPhase === 'outcome-unknown' &&
    creationSubmits.length
      ? {
          preparation: {
            preparedThreadId: 'prepared-thread',
            preparationPhase: 'input-retryable',
            diagnostic: {
              stage: 'generation',
              code: 'generation-preflight-failed',
              disposition: 'retry-original-preflight',
            },
          },
        }
      : {}),
  });
  const metrics = () => ({
    mode,
    hostId: target.hostId,
    replyAttempts: replyAttempts.length,
    creationSubmits: creationSubmits.length,
    acceptedInputs,
    replyFingerprints: replyAttempts.map((v) => replyIntentFingerprint(v, target)),
    replyIds: replyAttempts.map((v) => v.clientMessageId),
    creationIds: creationSubmits.map((v) => v.clientOperationId),
    creationPhase,
    fixtureErrors: [...fixtureErrors],
  });
  const reset = (value) => {
    if (target) {
      scenarios.push(metrics());
      retiredHosts.add(target.hostId);
    }
    mode = value;
    target = {
      agentId: 'codex',
      hostId: 'recovery-' + randomUUID(),
      workspaceId: 'fixture-workspace',
      runtimeId: 'fixture-runtime',
    };
    reply = receipt = creation = undefined;
    creationPhase = 'prepared';
    ledgerId = randomUUID();
    replyAttempts = [];
    creationSubmits = [];
    acceptedInputs = 0;
    descriptor = {
      version: 1,
      agentId: 'codex',
      name: '合成恢复验收电脑',
      target,
      fingerprint,
      endpoints: [endpoint],
      endpointHints: [{ endpoint, networkKind: 'lan' }],
    };
  };
  const send = (response, status, value) => {
    response.writeHead(status, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' });
    response.end(JSON.stringify(value));
  };
  const server = httpsServer(
    { key: await readFile(keyPath), cert: certificate },
    async (request, response) => {
      try {
        const url = new URL(request.url, endpoint);
        if (url.pathname === '/host') return send(response, 200, descriptor);
        const authorized = request.headers.authorization === 'Bearer ' + token;
        requests.push({ method: request.method, path: url.pathname, authorized });
        if (!authorized) return send(response, 401, { error: 'Synthetic token required' });
        if (retiredHosts.has(url.searchParams.get('hostId')))
          return send(response, 409, { error: 'Retired synthetic target' });
        // Bootstrap workspace and legacy history readers bind the returned identity;
        // named command reads/writes carry the complete captured target in their URL.
        if (url.pathname !== '/v1/workspace')
          assert.equal(url.searchParams.get('hostId'), target.hostId);
        if (url.searchParams.has('target'))
          assert.deepEqual(JSON.parse(url.searchParams.get('target')), target);
        else
          assert(
            request.method === 'GET' && ['/v1/workspace', '/v1/history'].includes(url.pathname),
          );
        if (request.method === 'GET') {
          if (url.pathname === '/v1/workspace')
            return send(response, 200, {
              hostId: target.hostId,
              name: descriptor.name,
              connectionMode: 'desktop-runtime',
              readTarget: target,
              projects: [],
              threads: [
                thread(),
                ...(creationPhase === 'confirmed' ? [thread('created-thread')] : []),
              ],
              nextProjectCursor: null,
              nextThreadCursor: null,
              agentConnectionState: { runtimeConnected: true },
              capabilities: [
                'reply',
                'desktop:creations',
                'desktop:creations:projectless',
                'commands:target-bound:v1',
                'generation-settings',
                'models:read',
              ],
            });
          if (url.pathname === '/v1/history')
            return send(response, 200, {
              hostId: target.hostId,
              threadId: url.searchParams.get('threadId'),
              readTarget: target,
              messages: [
                { id: 'history', role: 'assistant', text: '合成恢复历史', status: 'completed' },
              ],
              runtime: {
                state: 'idle',
                turnId: null,
                plan: [],
                error: null,
                approvals: [],
                receipts: receipt ? [receipt] : [],
                canControl: true,
              },
            });
          if (url.pathname === '/v1/models')
            return send(response, 200, {
              models: [
                {
                  id: 'fixture-model',
                  name: '合成模型',
                  isDefault: true,
                  defaultEffort: 'medium',
                  supportedEfforts: ['medium', 'high'],
                  supportedSpeeds: [],
                },
              ],
            });
          if (url.pathname === '/v1/receipts')
            return receipt
              ? send(response, 200, receipt)
              : send(response, 404, { error: 'No synthetic receipt' });
          if (url.pathname === '/v1/replies/status') {
            if (mode === 'unsupported')
              return send(response, 404, { error: 'Old Host has no inspection route' });
            const strong = ['strong', 'stale', 'wrong-target', 'missing-receipt'].includes(mode);
            const fp = reply ? replyIntentFingerprint(reply, target) : null;
            const value = {
              version: 2,
              hostId: target.hostId,
              readTarget: target,
              target,
              threadId: 'recovery-thread',
              clientMessageId: url.searchParams.get('id'),
              status: receipt ? 'accepted' : strong ? 'not-accepted' : 'unavailable',
              observedAt: new Date(Date.now() - (mode === 'stale' ? 61_000 : 0)).toISOString(),
              evidence:
                strong && reply
                  ? {
                      kind: 'operator-fenced-checkpoint-v2',
                      fingerprint: fp,
                      oldPid: 100,
                      newPid: 200,
                      ledgerSha256: 'a'.repeat(64),
                      fenceSha256: 'b'.repeat(64),
                      ledgerId,
                    }
                  : null,
              capturedTarget: strong || receipt ? target : null,
              fingerprintVersion: strong || receipt ? 2 : null,
              fingerprint: strong || receipt ? fp : null,
              receipt: receipt ?? null,
            };
            if (mode === 'wrong-target')
              value.capturedTarget = { ...target, runtimeId: 'other-runtime' };
            if (mode === 'missing-receipt') delete value.receipt;
            return send(response, 200, value);
          }
          if (url.pathname === '/v1/desktop/creation-receipts')
            return creation
              ? send(response, 200, creationResult())
              : send(response, 404, { error: 'No creation' });
        }
        if (request.method === 'POST') {
          let data = '';
          for await (const chunk of request) data += chunk;
          const body = JSON.parse(data);
          assert.equal(body.hostId, target.hostId);
          if (url.pathname === '/v1/replies') {
            replyAttempts.push(body);
            if (!reply) {
              reply = body;
              request.socket.destroy();
              return;
            }
            assert.equal(mode, 'strong');
            assert.equal(
              replyIntentFingerprint(body, target),
              replyIntentFingerprint(reply, target),
            );
            assert.equal(acceptedInputs, 0);
            acceptedInputs++;
            receipt = {
              hostId: target.hostId,
              threadId: body.threadId,
              clientMessageId: body.clientMessageId,
              state: 'completed',
              turnId: 'synthetic-recovered-turn',
              error: null,
              text: body.text,
              attachmentIds: body.attachmentIds,
            };
            return send(response, 200, receipt);
          }
          if (url.pathname === '/v1/desktop/creations') {
            assert(!creation);
            creation = body;
            return send(response, 200, creationResult());
          }
          if (url.pathname === '/v1/desktop/creations/submit') {
            assert.equal(body.clientOperationId, creation.clientOperationId);
            creationSubmits.push(body);
            if (creationSubmits.length === 1) {
              creationPhase = 'outcome-unknown';
              if (mode === 'creation-unknown') {
                request.socket.destroy();
                return;
              }
              return send(response, 200, creationResult());
            }
            assert.equal(mode, 'creation-preflight');
            assert.equal(creationSubmits.length, 2);
            acceptedInputs++;
            creationPhase = 'confirmed';
            return send(response, 200, creationResult());
          }
          if (url.pathname === '/v1/desktop/creations/detach')
            return send(response, 200, { ...creationResult(), detached: true });
        }
        send(response, 404, { error: 'Unsupported synthetic route' });
      } catch (error) {
        fixtureErrors.push(error.message);
        if (!response.destroyed)
          send(response, 500, { error: 'Synthetic fixture assertion failed' });
      }
    },
  );
  const control = httpServer((request, response) => {
    const url = new URL(request.url, 'http://fixture.invalid');
    if (url.pathname === '/reset') {
      reset(url.searchParams.get('mode') ?? 'unknown');
      return send(response, 200, {
        pairing:
          'codex-host://pair?descriptor=' +
          Buffer.from(JSON.stringify({ ...descriptor, token })).toString('base64url'),
      });
    }
    if (url.pathname === '/state') {
      mode = url.searchParams.get('mode');
      return send(response, 200, metrics());
    }
    if (url.pathname === '/results') return send(response, 200, metrics());
    send(response, 404, { error: 'Unknown test control' });
  });
  const ports = [];
  try {
    for (const service of [server, control]) {
      await new Promise((done) => service.listen(0, '127.0.0.1', done));
      ports.push(service.address().port);
    }
    endpoint = 'https://127.0.0.1:' + ports[0];
    reset('unknown');
    for (const port of ports)
      execFileSync(adb, ['-s', serial, 'reverse', 'tcp:' + port, 'tcp:' + port]);
    return {
      control: 'http://127.0.0.1:' + ports[1],
      metrics: () => ({ current: metrics(), completed: scenarios, requests, fixtureErrors }),
      async close() {
        for (const port of ports)
          execFileSync(adb, ['-s', serial, 'reverse', '--remove', 'tcp:' + port]);
        for (const service of [server, control]) {
          service.closeAllConnections();
          await new Promise((done) => service.close(done));
        }
        await rm(directory, { recursive: true, force: true });
      },
    };
  } catch (error) {
    server.close();
    control.close();
    await rm(directory, { recursive: true, force: true });
    throw error;
  }
}
