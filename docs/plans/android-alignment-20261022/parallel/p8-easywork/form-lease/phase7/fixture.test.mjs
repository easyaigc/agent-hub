import assert from 'node:assert/strict';
import test from 'node:test';
import { generateKeyPairSync, randomUUID, createHash, sign } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { CommitCase, codec } from './fixture.mjs';
import {
  MOBILE_PUBLIC,
  canonical,
  openPublicDelivery,
} from '../../authorization-boundaries/fixture.mjs';
const vector = JSON.parse(
  await readFile(
    new URL(
      '../../../../../../../apps/android/src/androidTest/assets/easywork-crypto-vector.json',
      import.meta.url,
    ),
  ),
);
const digest = (x) => createHash('sha256').update(x).digest('base64url');
function setup() {
  const c = new CommitCase(randomUUID(), vector),
    keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }),
    jwk = keys.publicKey.export({ format: 'jwk' });
  const signed = (method, path, value, token, change = {}) => {
    const url = 'http://127.0.0.1:12345' + path,
      raw = value ? canonical(value) : '',
      header = { typ: 'dpop+jwt', alg: 'ES256', jwk };
    const payload = {
      htm: method,
      htu: url,
      iat: Math.floor(Date.now() / 1000),
      jti: randomUUID(),
      ...(token ? { ath: digest(token) } : {}),
      ...(raw ? { bht: digest(raw) } : {}),
    };
    const input =
      Buffer.from(canonical(header)).toString('base64url') +
      '.' +
      Buffer.from(canonical(payload)).toString('base64url');
    return {
      method,
      url,
      raw,
      authorization: token ? 'DPoP ' + token : undefined,
      dpop:
        input +
        '.' +
        sign('sha256', Buffer.from(input), {
          key: keys.privateKey,
          dsaEncoding: 'ieee-p1363',
        }).toString('base64url'),
      ...change,
    };
  };
  const request = (...args) => c.auth(signed(...args));
  const claim = request('POST', '/v2/remote-access/pairing/claims', {
    operationId: randomUUID(),
    connectionCode: '0123456789ABCDEF',
    device: {
      deviceId: 'p8-fence-' + c.id + '-a',
      platform: 'android',
      publicKey: MOBILE_PUBLIC,
      authPublicKey: jwk,
    },
  });
  const pending = openPublicDelivery(claim.reply.sealedDelivery, claim.reply.claimId);
  c.resolve('approved');
  const grant = openPublicDelivery(
    request(
      'GET',
      '/v2/remote-access/pairing/claims/' + claim.reply.claimId,
      null,
      pending.pendingToken,
    ).reply.sealedDelivery,
    claim.reply.claimId,
  );
  const crypto = codec(grant.binding),
    mobile = randomUUID();
  let seq = 1;
  const send = (payload) =>
    request(
      'POST',
      '/v2/remote-access/relay/envelopes',
      crypto.seal(payload, mobile, seq++, 'mobile-to-desktop'),
      grant.accessToken,
    );
  const poll = () =>
    request('GET', '/v2/remote-access/relay/pending', null, grant.accessToken).reply.envelopes;
  const read = (method, params = {}) => {
    const q = {
      protocolVersion: 2,
      kind: 'request',
      requestId: randomUUID(),
      target: grant.target,
      method,
      params,
    };
    send({ kind: 'protocol', envelope: q });
    return q;
  };
  send({ kind: 'key-confirmation', value: crypto.confirmation });
  return { c, grant, crypto, request, signed, send, poll, read };
}
test('actual synthetic grant and DPoP authorize encrypted fixed context/workspace reads', () => {
  const x = setup();
  x.read('runtime/context');
  x.read('project/list');
  const q = x.read('thread/list', { limit: 100 });
  assert.equal(x.c.stats().held, 1);
  assert(
    !x
      .poll()
      .map((v) => x.crypto.open(v, 'desktop-to-mobile'))
      .some((v) => v.envelope?.requestId === q.requestId),
  );
  x.c.release('ok');
  const response = x
    .poll()
    .map((v) => x.crypto.open(v, 'desktop-to-mobile'))
    .find((v) => v.envelope?.requestId === q.requestId).envelope;
  assert.equal(response.ok, true);
  assert.deepEqual(response.result, { threads: [], nextCursor: null });
  assert.deepEqual(response.target, x.grant.target);
  assert.deepEqual(
    x.c.stats().reads.map((v) => v.method),
    ['runtime/context', 'project/list', 'thread/list'],
  );
  assert.equal(x.c.stats().requests.filter((v) => v.method === 'POST').length, 1);
  assert(x.c.stats().transport.every((v) => v.grantId === x.grant.grantId));
  assert.equal(x.c.stats().realDesktopRequests, 0);
  assert.equal(x.c.stats().realModelInputs, 0);
});
test('last workspace read failure is explicit and cannot release twice', () => {
  const x = setup();
  const q = x.read('thread/list', { limit: 100 });
  x.c.release('error');
  const r = x
    .poll()
    .map((v) => x.crypto.open(v, 'desktop-to-mobile'))
    .find((v) => v.envelope?.requestId === q.requestId).envelope;
  assert.equal(r.ok, false);
  assert.equal(r.error.code, 'synthetic_final_read_refused');
  assert.throws(() => x.c.release('ok'));
});
test('write, model, arbitrary read and changed parameters never reach a dispatcher', () => {
  for (const method of ['turn/start', 'model/list', 'thread/read', 'run-shell', 'http/proxy']) {
    const x = setup();
    assert.throws(() => x.read(method, { operationId: randomUUID() }));
    assert.equal(x.c.stats().reads.length, 0);
  }
  const x = setup();
  assert.throws(() => x.read('thread/list', { limit: 100, cursor: 'unregistered' }));
  assert.throws(() => x.read('runtime/context', { target: 'foreign' }));
});
test('changed encrypted target or authenticated ciphertext is refused', () => {
  const x = setup();
  assert.throws(() =>
    x.send({
      kind: 'protocol',
      envelope: {
        protocolVersion: 2,
        kind: 'request',
        requestId: randomUUID(),
        target: { ...x.grant.target, runtimeId: 'foreign' },
        method: 'runtime/context',
        params: {},
      },
    }),
  );
  const frame = x.crypto.seal(
    { kind: 'key-confirmation', value: x.crypto.confirmation },
    randomUUID(),
    1,
    'mobile-to-desktop',
  );
  frame.ciphertext = frame.ciphertext.slice(0, -2) + 'AA';
  assert.throws(() =>
    x.request('POST', '/v2/remote-access/relay/envelopes', frame, x.grant.accessToken),
  );
});
test('DPoP URI/body/verb/token and replay are checked before relay parsing', () => {
  const x = setup();
  for (const change of [
    { raw: '{}' },
    { method: 'POST' },
    { url: 'http://127.0.0.1:12345/v2/remote-access/relay/pending?foreign=1' },
  ])
    assert.throws(() =>
      x.c.auth(
        x.signed('GET', '/v2/remote-access/relay/pending', null, x.grant.accessToken, change),
      ),
    );
  assert.equal(
    x.c.auth(
      x.signed('GET', '/v2/remote-access/relay/pending', null, x.grant.accessToken, {
        authorization: 'Bearer ' + x.grant.accessToken,
      }),
    ).status,
    403,
  );
  const req = x.signed('GET', '/v2/remote-access/relay/pending', null, x.grant.accessToken);
  x.c.auth(req);
  assert.throws(() => x.c.auth(req));
  assert.equal(
    x.c.auth(x.signed('GET', '/v2/remote-access/relay/pending', null, 'foreign-token')).status,
    403,
  );
});
test('frame replay, malformed sequence and identity changes are refused', () => {
  const x = setup();
  const f = x.crypto.seal(
    {
      kind: 'protocol',
      envelope: {
        protocolVersion: 2,
        kind: 'request',
        requestId: randomUUID(),
        target: x.grant.target,
        method: 'runtime/context',
        params: {},
      },
    },
    randomUUID(),
    1,
    'mobile-to-desktop',
  );
  // An unconfirmed fresh channel cannot send protocol traffic.
  assert.throws(() =>
    x.request('POST', '/v2/remote-access/relay/envelopes', f, x.grant.accessToken),
  );
  for (const change of [
    { sequence: 1.5 },
    { sequence: '1' },
    { bindingId: 'foreign' },
    { recipientDeviceId: 'foreign' },
  ])
    assert.throws(() => x.crypto.open({ ...f, ...change }));
  assert.throws(() =>
    x.request('POST', '/v2/remote-access/relay/envelopes', f, x.grant.accessToken),
  );
});
test('acknowledgement requires the exact original outgoing binding/session/sequence', () => {
  const x = setup(),
    frame = x.poll()[0];
  const ack = {
    bindingId: frame.bindingId,
    senderDeviceId: frame.senderDeviceId,
    senderSessionId: frame.senderSessionId,
    sequence: frame.sequence,
  };
  assert.throws(() =>
    x.request(
      'POST',
      '/v2/remote-access/relay/acknowledgements',
      { envelopes: [{ ...ack, bindingId: 'foreign' }] },
      x.grant.accessToken,
    ),
  );
  x.request(
    'POST',
    '/v2/remote-access/relay/acknowledgements',
    { envelopes: [ack] },
    x.grant.accessToken,
  );
  assert.equal(x.poll().length, 0);
});
test('fixture explicitly refuses non-loopback service and unknown endpoint', () => {
  const x = setup();
  assert.throws(() =>
    x.c.auth({
      ...x.signed('GET', '/v2/remote-access/relay/pending', null, x.grant.accessToken),
      url: 'https://example.com/v2/remote-access/relay/pending',
    }),
  );
  assert.throws(() => x.request('POST', '/run-shell', {}));
});
