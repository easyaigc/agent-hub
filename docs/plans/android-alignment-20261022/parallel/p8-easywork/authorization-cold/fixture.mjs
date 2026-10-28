import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { Issuer } from '../authorization-fence/phase2/fixture.mjs';
import { canonical, hash } from '../authorization-boundaries/fixture.mjs';

export const KIND = 'p8-synthetic-authorization-cold-v1';
const hex = (value) => assert.match(value, /^[a-f0-9]{64}$/);

/** One issuer lifetime, one frozen writer, one reader. No reset or state reconstruction API. */
export class ColdContract {
  constructor(caseId) {
    this.caseId = caseId;
    this.phase = 'writer-active';
    this.issuer = new Issuer(caseId, 'hold-refresh-drop-revoke', 'cold-writer-reader');
    this.writer = null;
    this.reader = null;
    this.late = null;
  }
  dispatch(request) {
    assert(this.phase === 'writer-active' || this.phase === 'reader-active');
    if (this.phase === 'reader-active') {
      const original = this.writer.requests.find((x) => x.method === 'DELETE');
      assert.equal(request.method, 'DELETE');
      assert.equal(new URL(request.url).pathname, original.path);
      assert.equal(hash(request.raw), original.bodyHash);
      assert.equal(hash(request.authorization), original.tokenHash);
    }
    const result = this.issuer.dispatch(request);
    if (result.hold) {
      assert.equal(this.late, null);
      this.late = result.reply;
    }
    return result;
  }
  freeze(proof) {
    const digest = hash(canonical(proof));
    if (this.writer) {
      assert.equal(digest, this.writer.sha256);
      return this.writer;
    }
    assert.equal(this.phase, 'writer-active');
    assert.equal(proof.caseId, this.caseId);
    assert.equal(proof.phase, 'writer-frozen');
    assert(Number.isInteger(proof.pid) && proof.pid > 0);
    for (const key of [
      'sourceSha256',
      'appSha256',
      'runnerSha256',
      'fenceSha256',
      'savedSha256',
      'ticketSha256',
      'revokeBodyHash',
      'revokeAuthorizationHash',
    ])
      hex(proof[key]);
    assert.equal(this.issuer.requests.length, 4);
    assert.equal(this.issuer.errors.length, 0);
    assert(this.late);
    assert(this.issuer.dropped);
    assert(this.issuer.peers[0].revoked);
    const request = this.issuer.requests.find((x) => x.method === 'DELETE');
    assert.equal(proof.revokeOperationId, request.operationId);
    assert.equal(proof.revokeBodyHash, request.bodyHash);
    assert.equal(proof.revokeAuthorizationHash, request.tokenHash);
    assert.equal(proof.signerThumbprint, request.thumbprint);
    this.writer = {
      sha256: digest,
      proof: structuredClone(proof),
      requests: structuredClone(this.issuer.requests),
    };
    this.phase = 'writer-frozen';
    return this.writer;
  }
  beginReader(input, oldSocketClosed) {
    assert(this.writer);
    assert.equal(input.writerSha256, this.writer.sha256);
    assert(Number.isInteger(input.pid) && input.pid > 0 && input.pid !== this.writer.proof.pid);
    assert(input.processStartElapsed > this.writer.proof.processStartElapsed);
    for (const key of ['sourceSha256', 'appSha256', 'runnerSha256'])
      assert.equal(input[key], this.writer.proof[key]);
    assert(oldSocketClosed, 'Original writer socket must be closed before reader begins');
    if (this.reader) {
      assert.equal(canonical(input), canonical(this.reader));
      return;
    }
    assert.equal(this.phase, 'writer-frozen');
    this.reader = structuredClone(input);
    this.phase = 'reader-active';
  }
  finish(proof) {
    assert(this.reader);
    assert.equal(proof.writerSha256, this.writer.sha256);
    assert.equal(proof.pid, this.reader.pid);
    assert.equal(proof.processStartElapsed, this.reader.processStartElapsed);
    assert.equal(proof.writerPid, this.writer.proof.pid);
    assert.equal(proof.phase, 'reader-complete');
    for (const key of ['sourceSha256', 'appSha256', 'runnerSha256', 'signerThumbprint'])
      assert.equal(proof[key], this.writer.proof[key]);
    assert.equal(proof.revokeOperationId, this.writer.proof.revokeOperationId);
    assert.equal(proof.writerReceiptUnchanged, true);
    assert.equal(proof.pendingLateSaveRejected, true);
    assert.equal(proof.confirmedLateSaveRejected, true);
    assert.equal(proof.grantRemainsAbsent, true);
    const deletes = this.issuer.requests.filter((x) => x.method === 'DELETE');
    assert.equal(deletes.length, 2);
    for (const key of ['operationId', 'bodyHash', 'tokenHash', 'thumbprint', 'grantId'])
      assert.equal(deletes[0][key], deletes[1][key]);
    assert.notEqual(deletes[0].jti, deletes[1].jti);
    assert.equal(this.issuer.requests.length, 5);
    assert.equal(this.issuer.errors.length, 0);
    const digest = hash(canonical(proof));
    if (this.completed) {
      assert.equal(digest, this.completed.sha256);
      return this.completed;
    }
    assert.equal(this.phase, 'reader-active');
    this.completed = { sha256: digest, proof: structuredClone(proof) };
    this.phase = 'complete';
    return this.completed;
  }
  stats() {
    return {
      fixture: KIND,
      caseId: this.caseId,
      phase: this.phase,
      writerSha256: this.writer?.sha256 ?? null,
      readerPid: this.reader?.pid ?? null,
      requests: this.issuer.requests,
      errors: this.issuer.errors,
      realDesktopRequests: 0,
      realModelInputs: 0,
    };
  }
}

export async function startFixture(directory) {
  const folder = resolve(directory);
  await mkdir(folder, { mode: 0o700 });
  const caseId = randomUUID(),
    instanceId = randomUUID(),
    contract = new ColdContract(caseId),
    sockets = new Set();
  let held = null,
    config;
  const sources = {};
  for (const [name, url] of [
    ['cold', import.meta.url],
    ['issuer', new URL('../authorization-fence/phase2/fixture.mjs', import.meta.url)],
    ['crypto', new URL('../authorization-boundaries/fixture.mjs', import.meta.url)],
    [
      'asset',
      new URL(
        '../../../../../../apps/android/src/androidTest/assets/easywork-crypto-vector.json',
        import.meta.url,
      ),
    ],
  ])
    sources[name] = hash(await readFile(new URL(url)));
  const send = (res, value, status = 200) => {
    res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store' });
    res.end(JSON.stringify(value));
  };
  const body = async (req) => {
    let size = 0;
    const parts = [];
    for await (const b of req) {
      size += b.length;
      assert(size <= 65536);
      parts.push(b);
    }
    return Buffer.concat(parts).toString('utf8');
  };
  const stats = () => ({
    ...contract.stats(),
    instanceId,
    held: held ? 1 : 0,
    oldSocketClosed: held?.res.destroyed ?? false,
    sidecarPid: process.pid,
  });
  const service = createServer(async (req, res) => {
    try {
      const result = contract.dispatch({
        method: req.method,
        url: new URL(req.url, config.origin).href,
        raw: await body(req),
        authorization: req.headers.authorization,
        dpop: req.headers.dpop,
      });
      if (result.hold) {
        assert(!held);
        held = { res, reply: result.reply };
        return;
      }
      if (result.drop) {
        res.destroy();
        return;
      }
      send(res, result.reply, result.status);
    } catch (error) {
      contract.issuer.errors.push(error.name);
      if (!res.headersSent) send(res, { error: { code: 'cold_contract_refused' } }, 400);
      else res.destroy();
    }
  });
  service.on('connection', (socket) => {
    sockets.add(socket);
    socket.on('error', () => {});
    socket.on('close', () => sockets.delete(socket));
  });
  const control = createServer(async (req, res) => {
    try {
      const path = new URL(req.url, 'http://fixture.invalid').pathname;
      if (path === '/health' && req.method === 'GET')
        return send(res, {
          ...config,
          onlyLoopback: true,
          realDesktop: false,
          realModel: false,
          ...stats(),
        });
      assert.equal(req.headers['x-p8-fixture'], instanceId);
      if (path === '/stats' && req.method === 'GET') return send(res, stats());
      if (path === '/freeze-writer' && req.method === 'POST') {
        const proof = JSON.parse(await body(req));
        assert.equal(proof.instanceId, instanceId);
        assert.equal(proof.configSha256, hash(canonical(config)));
        const frozen = contract.freeze(proof);
        const file = join(folder, 'writer-frozen.json');
        try {
          await writeFile(file, JSON.stringify(frozen, null, 2) + '\n', {
            mode: 0o600,
            flag: 'wx',
          });
        } catch (error) {
          if (error.code !== 'EEXIST') throw error;
          assert.equal(JSON.parse(await readFile(file)).sha256, frozen.sha256);
        }
        return send(res, { writerSha256: frozen.sha256, ...stats() });
      }
      if (path === '/reader-begin' && req.method === 'POST') {
        const value = JSON.parse(await body(req));
        contract.beginReader(value, held?.res.destroyed === true);
        await writeFile(join(folder, 'reader-begin.json'), JSON.stringify(value, null, 2) + '\n', {
          mode: 0o600,
        });
        return send(res, stats());
      }
      if (path === '/late-delivery' && req.method === 'GET') {
        assert.equal(contract.phase, 'reader-active');
        assert(held?.res.destroyed);
        return send(res, held.reply);
      }
      if (path === '/finish-reader' && req.method === 'POST') {
        const proof = JSON.parse(await body(req));
        const result = contract.finish(proof);
        await writeFile(
          join(folder, 'reader-complete.json'),
          JSON.stringify(result, null, 2) + '\n',
          { mode: 0o600 },
        );
        return send(res, { readerSha256: result.sha256, ...stats() });
      }
      send(res, { error: 'No reset/start/reconstruction operation' }, 404);
    } catch {
      if (!res.headersSent) send(res, { error: 'cold_control_refused' }, 400);
      else res.destroy();
    }
  });
  const listen = (s) =>
    new Promise((done, fail) => {
      s.once('error', fail);
      s.listen(0, '127.0.0.1', done);
    });
  try {
    await listen(service);
    await listen(control);
  } catch (error) {
    service.close();
    control.close();
    throw error;
  }
  config = {
    version: 1,
    fixture: KIND,
    caseId,
    instanceId,
    sidecarPid: process.pid,
    sidecarStartedAt: new Date().toISOString(),
    sourceGraphSha256: hash(canonical(sources)),
    sourceFilesSha256: sources,
    sourceFixtureSha256: sources.asset,
    origin: `http://127.0.0.1:${service.address().port}`,
    controlOrigin: `http://127.0.0.1:${control.address().port}`,
  };
  await writeFile(join(folder, 'config.json'), JSON.stringify(config, null, 2) + '\n', {
    mode: 0o600,
  });
  return {
    config,
    async close() {
      for (const socket of sockets) socket.destroy();
      await Promise.all(
        [service, control].map(
          (s) =>
            new Promise((done) => {
              s.close(done);
              s.closeAllConnections?.();
            }),
        ),
      );
    },
  };
}
if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  assert.equal(process.argv[2], '--start');
  assert(process.argv[3]);
  const fixture = await startFixture(process.argv[3]);
  console.log(JSON.stringify({ status: 'cold-fixture-ready', ...fixture.config }));
  let closing = false;
  const close = async () => {
    if (closing) return;
    closing = true;
    await fixture.close();
    process.exit(0);
  };
  process.once('SIGINT', close);
  process.once('SIGTERM', close);
}
