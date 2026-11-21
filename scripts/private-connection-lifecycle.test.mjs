import assert from 'node:assert/strict';
import test from 'node:test';
import { generateKeyPairSync, createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import { syncBuiltinESMExports } from 'node:module';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { PrivateConnections } from '../apps/host-agent/dist/private-connections.js';
import {
  privateConnectionManagementRoute,
  privateConnectionHostKeyAuthorized,
} from '../apps/host-agent/dist/private-connection-routes.js';
import { codexPrivateConnectionRevokeIntent } from '../apps/host-agent/dist/private-connection-contract.js';
const target = {
  agentId: 'codex',
  hostId: '11111111-1111-4111-8111-111111111111',
  workspaceId: 'fixture-workspace',
  runtimeId: 'desktop-runtime',
};
const hostKey = 'fixture-admin-key-not-a-real-credential';
const hash = (data) => createHash('sha256').update(data).digest('hex');
const publicKey = () =>
  generateKeyPairSync('x25519')
    .publicKey.export({ format: 'der', type: 'spki' })
    .subarray(-32)
    .toString('base64url');
const keySha = (key) => hash(Buffer.from(key, 'base64url'));
const statusInput = (key, t = target) => ({
  target: t,
  publicKeySha256: keySha(key),
});
const intent = (key, t = target, id = randomUUID()) => ({
  ...statusInput(key, t),
  clientOperationId: id,
});
function fixture(run) {
  const directory = fs.mkdtempSync(join(fs.realpathSync(tmpdir()), 'private-binding-lifecycle-'));
  fs.chmodSync(directory, 0o700);
  try {
    return run(directory);
  } finally {
    fs.rmSync(directory, { recursive: true });
    assert(!fs.existsSync(directory));
  }
}
async function asyncFixture(run) {
  const directory = fs.mkdtempSync(
    join(fs.realpathSync(tmpdir()), 'private-binding-lifecycle-http-'),
  );
  fs.chmodSync(directory, 0o700);
  try {
    return await run(directory);
  } finally {
    fs.rmSync(directory, { recursive: true });
    assert(!fs.existsSync(directory));
  }
}
const statePath = (d) => join(d, 'private-connections-v1.json');
const state = (d) => JSON.parse(fs.readFileSync(statePath(d), 'utf8'));
function claim(connections, key, t = target) {
  connections.claim({ version: 1, target: t, publicKey: key }, t);
}
function statusError(action, expected) {
  assert.throws(action, (e) => e.status === expected);
}
function replaceFs(name, fn, run) {
  const original = fs[name];
  fs[name] = fn(original);
  syncBuiltinESMExports();
  try {
    return run();
  } finally {
    fs[name] = original;
    syncBuiltinESMExports();
  }
}
function receipt(input) {
  return {
    clientOperationId: input.clientOperationId,
    inputSha256: hash(codexPrivateConnectionRevokeIntent(input)),
    state: 'confirmed',
    binding: {
      ...statusInputFromIntent(input),
      revoked: true,
      hostKeyAuthorized: true,
    },
  };
}
function statusInputFromIntent(input) {
  return { target: input.target, publicKeySha256: input.publicKeySha256 };
}

test('legacy load is read-only; actual class preserves account metadata and receipts across revoke/restart/future claim', () =>
  fixture((d) => {
    const key = publicKey(),
      other = publicKey();
    const metadata = {
      ...target,
      account: { authority: 'legacy-provider', subject: 'legacy-account' },
    };
    const c = new PrivateConnections(d);
    claim(c, key, metadata);
    const saved = state(d);
    delete saved.operations;
    fs.writeFileSync(statePath(d), JSON.stringify(saved), { mode: 0o600 });
    const legacy = fs.readFileSync(statePath(d));
    const loaded = new PrivateConnections(d);
    assert.deepEqual(fs.readFileSync(statePath(d)), legacy);
    const token = state(d).bindings[0].token;
    assert(
      loaded.accepts(Buffer.from(token), {
        ...target,
        account: { authority: 'changed', subject: 'changed' },
      }),
    );
    const before = loaded.status(statusInput(key), target);
    assert.deepEqual(Object.keys(before).sort(), [
      'hostKeyAuthorized',
      'publicKeySha256',
      'revoked',
      'target',
    ]);
    assert.deepEqual(before.target, target);
    const input = intent(key),
      result = loaded.revoke(input, target);
    assert.equal(result.state, 'confirmed');
    assert.equal(result.binding.revoked, true);
    assert.equal(loaded.accepts(Buffer.from(token), target), false);
    const persisted = fs.readFileSync(statePath(d));
    assert.deepEqual(
      loaded.revoke(
        {
          clientOperationId: input.clientOperationId,
          publicKeySha256: input.publicKeySha256,
          target,
        },
        target,
      ),
      result,
    );
    assert.deepEqual(fs.readFileSync(statePath(d)), persisted);
    statusError(() => loaded.revoke({ ...input, publicKeySha256: keySha(other) }, target), 409);
    assert.deepEqual(fs.readFileSync(statePath(d)), persisted);
    const restarted = new PrivateConnections(d);
    assert.deepEqual(
      restarted.operation({ target, clientOperationId: input.clientOperationId }, target),
      result,
    );
    claim(restarted, other);
    assert.deepEqual(state(d).operations, [result]);
    assert.deepEqual(state(d).bindings[0].target, metadata);
    assert.equal(
      /"(?:token|tokenHash|tokenSha256|publicKey)":/.test(JSON.stringify(result)),
      false,
    );
  }));

test('strict selectors, wrong machine, missing device and ambiguous same key fail before writes', () =>
  fixture((d) => {
    const c = new PrivateConnections(d),
      key = publicKey();
    claim(c, key);
    const original = fs.readFileSync(statePath(d));
    statusError(() => c.status({ ...statusInput(key), token: 'extra' }, target), 400);
    statusError(
      () =>
        c.status(
          statusInput(key, {
            ...target,
            account: { authority: 'a', subject: 'b' },
          }),
          target,
        ),
      400,
    );
    statusError(() => c.revoke({ ...intent(key), extra: true }, target), 400);
    statusError(
      () => c.revoke(intent(key, { ...target, runtimeId: 'foreign-runtime' }), target),
      409,
    );
    statusError(() => c.status(statusInput(publicKey()), target), 404);
    assert.deepEqual(fs.readFileSync(statePath(d)), original);
    const malformed = state(d);
    malformed.bindings.push({
      ...malformed.bindings[0],
      token: 'A'.repeat(43),
    });
    fs.writeFileSync(statePath(d), JSON.stringify(malformed));
    const ambiguous = new PrivateConnections(d);
    const bytes = fs.readFileSync(statePath(d));
    statusError(() => ambiguous.status(statusInput(key), target), 409);
    statusError(() => ambiguous.revoke(intent(key), target), 409);
    statusError(() => claim(ambiguous, key), 409);
    assert.equal(ambiguous.accepts(Buffer.from(malformed.bindings[0].token), target), false);
    assert.deepEqual(fs.readFileSync(statePath(d)), bytes);
  }));

test('failure before write preserves bytes and original unknown ID, with transient auth/status rather than 401', () =>
  fixture((d) => {
    const c = new PrivateConnections(d),
      key = publicKey();
    claim(c, key);
    const token = state(d).bindings[0].token;
    const original = fs.readFileSync(statePath(d));
    const input = intent(key);
    replaceFs(
      'writeFileSync',
      (original) =>
        function (path, ...args) {
          if (typeof path === 'string' && path.startsWith(statePath(d) + '.tmp-'))
            throw Object.assign(Error('fixture failure'), { code: 'EIO' });
          return original.call(this, path, ...args);
        },
      () => statusError(() => c.revoke(input, target), 503),
    );
    assert.deepEqual(fs.readFileSync(statePath(d)), original);
    statusError(() => c.status(statusInput(key), target), 503);
    statusError(() => c.accepts(Buffer.from(token), target), 503);
    statusError(() => c.revoke(input, target), 503);
    assert.deepEqual(c.operation({ target, clientOperationId: input.clientOperationId }, target), {
      clientOperationId: input.clientOperationId,
      inputSha256: hash(codexPrivateConnectionRevokeIntent(input)),
      state: 'unknown',
    });
    const restart = new PrivateConnections(d);
    statusError(
      () => restart.operation({ target, clientOperationId: input.clientOperationId }, target),
      404,
    );
    assert(restart.accepts(Buffer.from(token), target));
  }));

test('rename may publish then throw: old RAM never authorizes; original operation re-read and sync confirms', () =>
  fixture((d) => {
    const c = new PrivateConnections(d),
      key = publicKey();
    claim(c, key);
    const token = state(d).bindings[0].token,
      input = intent(key);
    replaceFs(
      'renameSync',
      (original) =>
        function (from, to) {
          const value = original.call(this, from, to);
          if (to === statePath(d))
            throw Object.assign(Error('fixture after publication'), {
              code: 'EIO',
            });
          return value;
        },
      () => statusError(() => c.revoke(input, target), 503),
    );
    assert.equal(state(d).bindings[0].revoked, true);
    statusError(() => c.accepts(Buffer.from(token), target), 503);
    statusError(() => c.status(statusInput(key), target), 503);
    const result = c.operation({ target, clientOperationId: input.clientOperationId }, target);
    assert.equal(result.state, 'confirmed');
    assert.equal(c.accepts(Buffer.from(token), target), false);
    assert.equal(c.status(statusInput(key), target).revoked, true);
    assert.deepEqual(
      new PrivateConnections(d).operation(
        { target, clientOperationId: input.clientOperationId },
        target,
      ),
      result,
    );
  }));

test('directory sync failure exposes no durable status; repeated original query waits for real sync', () =>
  fixture((d) => {
    if (process.platform === 'win32') return;
    const c = new PrivateConnections(d),
      key = publicKey();
    claim(c, key);
    const input = intent(key);
    replaceFs(
      'fsyncSync',
      (original) =>
        function (fd) {
          if (fs.fstatSync(fd).isDirectory())
            throw Object.assign(Error('fixture dir sync'), { code: 'EIO' });
          return original.call(this, fd);
        },
      () => {
        statusError(() => c.revoke(input, target), 503);
        statusError(() => c.status(statusInput(key), target), 503);
        assert.equal(
          c.operation({ target, clientOperationId: input.clientOperationId }, target).state,
          'unknown',
        );
      },
    );
    assert.equal(
      c.operation({ target, clientOperationId: input.clientOperationId }, target).state,
      'confirmed',
    );
  }));

test('receipt count and serialized byte bounds reject before mutation and preserve old receipts', () =>
  fixture((d) => {
    const key = publicKey(),
      c = new PrivateConnections(d);
    claim(c, key);
    let saved = state(d);
    saved.bindings[0].revoked = true;
    saved.operations = Array.from({ length: 1024 }, () => receipt(intent(key)));
    fs.writeFileSync(statePath(d), JSON.stringify(saved));
    const full = new PrivateConnections(d),
      original = fs.readFileSync(statePath(d));
    statusError(() => full.revoke(intent(key), target), 429);
    assert.deepEqual(fs.readFileSync(statePath(d)), original);
    const long = {
      agentId: 'codex',
      hostId: 'h'.repeat(256),
      workspaceId: 'w'.repeat(256),
      runtimeId: 'r'.repeat(256),
    };
    saved = {
      version: 1,
      bindings: [{ ...saved.bindings[0], target: long }],
      operations: [],
    };
    for (;;) {
      const next = receipt(intent(key, long));
      if (
        Buffer.byteLength(JSON.stringify({ ...saved, operations: [...saved.operations, next] })) >
        1024 * 1024
      )
        break;
      saved.operations.push(next);
    }
    assert(saved.operations.length < 1024);
    fs.writeFileSync(statePath(d), JSON.stringify(saved));
    const bounded = new PrivateConnections(d),
      bytes = fs.readFileSync(statePath(d));
    statusError(() => bounded.revoke(intent(key, long), long), 429);
    assert.deepEqual(fs.readFileSync(statePath(d)), bytes);
  }));

test('HTTP real management route accepts exact Host key only; device flag grants no admin role', () =>
  asyncFixture(async (d) => {
    const c = new PrivateConnections(d),
      key = publicKey(),
      other = publicKey();
    claim(c, key);
    claim(c, other);
    const device = state(d).bindings[0].token;
    const ordinary = state(d).bindings[1].token;
    const server = createServer(async (req, res) => {
      try {
        if (req.headers.origin) throw { status: 403 };
        const supplied = Buffer.from((req.headers.authorization ?? '').replace(/^Bearer /, ''));
        const admin = privateConnectionHostKeyAuthorized(supplied, Buffer.from(hostKey));
        if (
          !req.headers.authorization?.startsWith('Bearer ') ||
          !(admin || c.accepts(supplied, target))
        )
          throw { status: 401 };
        const url = new URL(req.url, 'http://localhost');
        if (
          await privateConnectionManagementRoute(req, res, url, {
            hostAccessKeyAuthenticated: admin,
            connections: c,
            target,
            body: async (request, limit) => {
              const parts = [];
              let bytes = 0;
              for await (const part of request) {
                bytes += part.length;
                if (bytes > limit) throw { status: 413 };
                parts.push(part);
              }
              return Buffer.concat(parts);
            },
          })
        )
          return;
        res.writeHead(200);
        res.end('ordinary-route');
      } catch (error) {
        res.writeHead(error.status ?? 500, {
          'Content-Type': 'application/json',
        });
        res.end(JSON.stringify({ status: error.status, code: error.code }));
      }
    });
    server.listen(0, '127.0.0.1');
    await once(server, 'listening');
    const base = 'http://127.0.0.1:' + server.address().port;
    const get = (auth, query) =>
      fetch(base + '/v1/private-connections/status?' + new URLSearchParams(query), {
        headers: { Authorization: 'Bearer ' + auth },
      });
    try {
      const q = {
        target: JSON.stringify(target),
        publicKeySha256: keySha(key),
      };
      assert.equal((await get(device, q)).status, 403);
      assert.equal(
        (
          await fetch(base + '/ordinary', {
            headers: { Authorization: 'Bearer ' + device },
          })
        ).status,
        200,
      );
      assert.equal((await get(hostKey, q)).status, 200);
      assert.equal((await get(hostKey, { ...q, extra: 'reject' })).status, 400);
      assert.equal(
        (
          await fetch(
            base +
              '/v1/private-connections/status?' +
              new URLSearchParams(q) +
              '&publicKeySha256=' +
              keySha(key),
            { headers: { Authorization: 'Bearer ' + hostKey } },
          )
        ).status,
        400,
      );
      const input = intent(key);
      const post = (auth) =>
        fetch(base + '/v1/private-connections/revoke', {
          method: 'POST',
          headers: {
            Authorization: 'Bearer ' + auth,
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(input),
        });
      assert.equal((await post(device)).status, 403);
      const accepted = await post(hostKey);
      assert.equal(accepted.status, 200);
      const r = await accepted.json();
      assert.equal(r.binding.revoked, true);
      assert.equal(
        (
          await get(ordinary, {
            target: JSON.stringify(target),
            clientOperationId: input.clientOperationId,
          })
        ).status,
        403,
      );
      const read = await get(hostKey, {
        target: JSON.stringify(target),
        clientOperationId: input.clientOperationId,
      });
      assert.equal(read.status, 200);
      assert.deepEqual(await read.json(), r);
      assert.equal(
        (
          await fetch(base + '/ordinary', {
            headers: { Authorization: 'Bearer ' + device },
          })
        ).status,
        401,
      );
      assert.equal(
        (
          await fetch(base + '/ordinary', {
            headers: { Authorization: 'Bearer ' + ordinary },
          })
        ).status,
        200,
      );
      const uncertain = intent(other);
      replaceFs(
        'writeFileSync',
        (original) =>
          function (path, ...args) {
            if (typeof path === 'string' && path.startsWith(statePath(d) + '.tmp-'))
              throw Object.assign(Error('fixture HTTP save failure'), {
                code: 'EIO',
              });
            return original.call(this, path, ...args);
          },
        () => statusError(() => c.revoke(uncertain, target), 503),
      );
      const transient = await fetch(base + '/ordinary', {
        headers: { Authorization: 'Bearer ' + ordinary },
      });
      assert.equal(transient.status, 503);
      assert.equal((await transient.json()).code, 'PRIVATE_CONNECTION_STATE_UNCERTAIN');
      const queryUnknown = await get(hostKey, {
        target: JSON.stringify(target),
        clientOperationId: uncertain.clientOperationId,
      });
      assert.equal(queryUnknown.status, 200);
      assert.equal((await queryUnknown.json()).state, 'unknown');
    } finally {
      server.closeAllConnections();
      await new Promise((resolve) => server.close(resolve));
      assert.equal(server.listening, false);
    }
  }));
