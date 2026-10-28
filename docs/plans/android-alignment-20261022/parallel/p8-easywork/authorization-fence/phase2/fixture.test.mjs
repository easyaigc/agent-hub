import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import test from 'node:test';
import { Issuer } from './fixture.mjs';
import {
  CODES,
  MOBILE_PUBLIC,
  canonical,
  openPublicDelivery,
} from '../../authorization-boundaries/fixture.mjs';

// Pure issuer dispatch only. No listeners, device, Android storage or private installed identity.
const digest = (value) => createHash('sha256').update(value).digest('base64url');
function setup(mode = 'normal') {
  const issuer = new Issuer(randomUUID(), mode),
    keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }),
    jwk = keys.publicKey.export({ format: 'jwk' });
  function request(method, path, body, token, corrupt = false) {
    const url = 'http://127.0.0.1:12345' + path,
      raw = body ? canonical(body) : '';
    const header = { typ: 'dpop+jwt', alg: 'ES256', jwk },
      payload = {
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
    const signature = sign('sha256', Buffer.from(input), {
      key: keys.privateKey,
      dsaEncoding: 'ieee-p1363',
    });
    if (corrupt) signature[0] ^= 1;
    return issuer.dispatch({
      method,
      url,
      raw,
      authorization: token ? 'DPoP ' + token : undefined,
      dpop: input + '.' + signature.toString('base64url'),
    });
  }
  function claim(code = CODES[0]) {
    const response = request('POST', '/v2/remote-access/pairing/claims', {
      operationId: randomUUID(),
      connectionCode: code,
      device: {
        deviceId: 'p8-fence-' + issuer.caseId + '-a',
        platform: 'android',
        name: 'public synthetic fixture',
        publicKey: MOBILE_PUBLIC,
        authPublicKey: jwk,
      },
    });
    const pending = openPublicDelivery(response.reply.sealedDelivery, response.reply.claimId);
    const approved = request(
      'GET',
      '/v2/remote-access/pairing/claims/' + response.reply.claimId,
      null,
      pending.pendingToken,
    );
    return openPublicDelivery(approved.reply.sealedDelivery, response.reply.claimId);
  }
  return { issuer, request, claim, jwk };
}

test('combined held refresh and lost revoke leaves an accepted late result and stable DELETE', () => {
  const { issuer, request, claim } = setup('hold-refresh-drop-revoke'),
    grant = claim(),
    refreshId = randomUUID(),
    revokeId = randomUUID();
  const refresh = request(
    'POST',
    '/v2/remote-access/device-grants/refresh',
    { operationId: refreshId },
    grant.refreshToken,
  );
  assert(refresh.hold);
  const rotated = openPublicDelivery(refresh.reply.sealedDelivery, refreshId);
  const first = request(
    'DELETE',
    '/v2/remote-access/device-grants/' + grant.grantId,
    { operationId: revokeId },
    grant.accessToken,
  );
  assert(first.drop);
  const resumed = request(
    'DELETE',
    '/v2/remote-access/device-grants/' + grant.grantId,
    { operationId: revokeId },
    grant.accessToken,
  );
  assert(!resumed.drop);
  assert.equal(resumed.reply.status, 'revoked');
  assert.equal(request('GET', '/v2/remote-access/bindings', null, rotated.accessToken).status, 403);
  const rows = issuer.requests.filter((x) => x.method === 'DELETE');
  assert.equal(rows[0].operationId, rows[1].operationId);
  assert.equal(rows[0].bodyHash, rows[1].bodyHash);
  assert.equal(rows[0].tokenHash, rows[1].tokenHash);
  assert.notEqual(rows[0].jti, rows[1].jti);
});

test('same phone may hold two independent grants and revoking one leaves the other usable', () => {
  const { request, claim } = setup(),
    a = claim(),
    b = claim(CODES[1]);
  assert.notEqual(a.target.runtimeId, b.target.runtimeId);
  request(
    'DELETE',
    '/v2/remote-access/device-grants/' + a.grantId,
    { operationId: randomUUID() },
    a.accessToken,
  );
  assert.equal(request('GET', '/v2/remote-access/bindings', null, b.accessToken).status, 200);
  assert.equal(request('GET', '/v2/remote-access/bindings', null, a.accessToken).status, 403);
});

test('refresh receipt resumption requires exact original body and token with a fresh proof', () => {
  const { issuer, request, claim } = setup(),
    grant = claim(),
    id = randomUUID();
  const a = request(
    'POST',
    '/v2/remote-access/device-grants/refresh',
    { operationId: id },
    grant.refreshToken,
  );
  const b = request(
    'POST',
    '/v2/remote-access/device-grants/refresh',
    { operationId: id },
    grant.refreshToken,
  );
  assert.equal(a.reply.sealedDelivery, b.reply.sealedDelivery);
  assert.throws(() =>
    request(
      'POST',
      '/v2/remote-access/device-grants/refresh',
      { operationId: id, changed: true },
      grant.refreshToken,
    ),
  );
  assert.equal(issuer.peers[0].rotation, 1);
});

test('invalid signature cannot mutate an issuer or create a device grant', () => {
  const { issuer, request, jwk } = setup();
  assert.throws(() =>
    request(
      'POST',
      '/v2/remote-access/pairing/claims',
      {
        operationId: randomUUID(),
        connectionCode: CODES[0],
        device: {
          deviceId: 'p8-fence-' + issuer.caseId + '-a',
          platform: 'android',
          publicKey: MOBILE_PUBLIC,
          authPublicKey: jwk,
        },
      },
      null,
      true,
    ),
  );
  assert.equal(issuer.peers.length, 0);
  assert.equal(issuer.requests.length, 0);
});
