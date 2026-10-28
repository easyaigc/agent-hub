import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import { PairingCase } from './fixture.mjs';
import {
  MOBILE_PUBLIC,
  canonical,
  openPublicDelivery,
} from '../authorization-boundaries/fixture.mjs';
const vector = JSON.parse(
  await readFile(
    new URL(
      '../../../../../../apps/android/src/androidTest/assets/easywork-crypto-vector.json',
      import.meta.url,
    ),
  ),
);
const digest = (x) => createHash('sha256').update(x).digest('base64url');
function fixture(mode) {
  const c = new PairingCase(randomUUID(), mode, vector),
    keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' }),
    jwk = keys.publicKey.export({ format: 'jwk' });
  const id = randomUUID(),
    body = {
      operationId: id,
      connectionCode: '0123456789ABCDEF',
      device: {
        deviceId: 'p8-fence-' + c.id + '-a',
        platform: 'android',
        publicKey: MOBILE_PUBLIC,
        authPublicKey: jwk,
      },
    };
  const request = (method, path, value, token) => {
    const raw = value ? canonical(value) : '',
      url = 'http://127.0.0.1:12345' + path;
    const head = { typ: 'dpop+jwt', alg: 'ES256', jwk },
      payload = {
        htm: method,
        htu: url,
        iat: Math.floor(Date.now() / 1000),
        jti: randomUUID(),
        ...(token ? { ath: digest(token) } : {}),
        ...(raw ? { bht: digest(raw) } : {}),
      };
    const input =
      Buffer.from(canonical(head)).toString('base64url') +
      '.' +
      Buffer.from(canonical(payload)).toString('base64url');
    const signature = sign('sha256', Buffer.from(input), {
      key: keys.privateKey,
      dsaEncoding: 'ieee-p1363',
    });
    return c.auth({
      method,
      url,
      raw,
      authorization: token ? 'DPoP ' + token : undefined,
      dpop: input + '.' + signature.toString('base64url'),
    });
  };
  const claim = request('POST', '/v2/remote-access/pairing/claims', body);
  const pending = openPublicDelivery(claim.reply.sealedDelivery, claim.reply.claimId);
  const poll = () =>
    request(
      'GET',
      '/v2/remote-access/pairing/claims/' + claim.reply.claimId,
      null,
      pending.pendingToken,
    );
  return { c, claim, poll, request, body };
}
for (const status of ['rejected', 'expired'])
  test('pending status only resolves explicitly: ' + status, () => {
    const { c, poll } = fixture(status === 'rejected' ? 'reject' : 'expire');
    assert.equal(poll().reply.status, 'pending');
    c.resolve(status);
    assert.equal(poll().reply.status, status);
    assert.throws(() => c.resolve('approved'));
    assert.equal(c.issuer.requests.filter((x) => x.method === 'POST').length, 1);
  });
test('lost claim retains one exact immutable operation for an explicit retry', () => {
  const { c, claim, request, body } = fixture('drop');
  assert(claim.drop);
  const again = request('POST', '/v2/remote-access/pairing/claims', body);
  assert(!again.drop);
  const rows = c.issuer.requests;
  assert.equal(rows.length, 2);
  for (const k of ['operationId', 'bodyHash', 'tokenHash', 'thumbprint', 'grantId'])
    assert.equal(rows[0][k], rows[1][k]);
  assert.notEqual(rows[0].jti, rows[1].jti);
});
test('mismatched grant is actually sealed for the original claim and remains a different mobile binding', () => {
  const { c, poll, claim } = fixture('mismatch');
  c.resolve('approved');
  const grant = openPublicDelivery(poll().reply.sealedDelivery, claim.reply.claimId);
  assert.equal(grant.binding.mobileDeviceId, 'foreign-mobile');
  assert.notEqual(grant.deviceId, grant.binding.mobileDeviceId);
});
test('LAN descriptor and returned binding expose the exact intended negative boundary', () => {
  for (const mode of ['wrong-pin', 'wrong-host-target', 'wrong-binding', 'expired-local']) {
    const c = new PairingCase(randomUUID(), mode, vector),
      origin = 'https://127.0.0.1:12345',
      pin = 'a'.repeat(64),
      offer = c.descriptor(origin, pin, true),
      host = c.descriptor(origin, pin);
    if (mode === 'wrong-pin') assert.notEqual(offer.fingerprint, host.fingerprint);
    if (mode === 'wrong-host-target')
      assert.notEqual(offer.target.runtimeId, host.target.runtimeId);
    if (mode === 'expired-local') assert(new Date(offer.expiresAt) < new Date());
    if (mode === 'wrong-binding') {
      const reply = c.lan(
        '/pairing/claim',
        'POST',
        { code: '123456', mobileDeviceId: 'mobile-vector', publicKey: MOBILE_PUBLIC },
        origin,
        pin,
      ).reply;
      assert.notEqual(reply.binding.target.runtimeId, host.target.runtimeId);
    }
  }
});
test('runtime remains unavailable and arbitrary service operations are refused', () => {
  const { c } = fixture('approve-block');
  const result = c.auth({
    url: 'http://127.0.0.1:12345/v2/remote-access/relay/envelopes',
    method: 'POST',
    raw: '{}',
  });
  assert.equal(result.status, 503);
  assert.equal(c.blockedRelay, 1);
  assert.throws(() =>
    c.auth({ url: 'http://127.0.0.1:12345/run-shell', method: 'POST', raw: '{}' }),
  );
  assert.equal(c.stats().realModelInputs, 0);
  assert.equal(c.stats().realDesktopRequests, 0);
});
