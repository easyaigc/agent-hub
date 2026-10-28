import assert from 'node:assert/strict';
import { createHash, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
const golden = JSON.parse(await readFile(new URL('../ewdap-vector.json', import.meta.url)));
import {
  canonical,
  hash,
  MOBILE_PUBLIC,
  openPublicDelivery,
  sealDelivery,
  verifyProof,
} from './fixture.mjs';

// Pure helpers only: no fixture start, listeners, Android key store or service contact.
const digest = (value) => createHash('sha256').update(value).digest('base64url');
const url = 'http://127.0.0.1:12345/v2/remote-access/device-grants/refresh';
const token = digest('public synthetic fixture token');
const body = canonical({ operationId: 'public-original-operation' });
const keys = generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const jwk = keys.publicKey.export({ format: 'jwk' });
function proof(changes = {}, key = keys.privateKey) {
  const header = { typ: 'dpop+jwt', alg: 'ES256', jwk };
  const payload = {
    htm: 'POST',
    htu: url,
    iat: Math.floor(Date.now() / 1000),
    jti: randomUUID(),
    ath: digest(token),
    bht: digest(body),
    ...changes,
  };
  const input =
    Buffer.from(canonical(header)).toString('base64url') +
    '.' +
    Buffer.from(canonical(payload)).toString('base64url');
  return (
    input +
    '.' +
    sign('sha256', Buffer.from(input), { key, dsaEncoding: 'ieee-p1363' }).toString('base64url')
  );
}
const expected = () => ({ method: 'POST', url, token, body, jwk });

test('independent compact JWE preserves delivery ID, Unicode and complete target', () => {
  assert.deepEqual(openPublicDelivery(golden.delivery, golden.deliveryId), golden.payload);
  const id = randomUUID(),
    value = {
      target: { desktopDeviceId: 'synthetic', workspaceId: 'workspace', runtimeId: 'runtime' },
      proof: '中文🙂 / \\ \n',
    };
  const compact = sealDelivery(value, id);
  assert.deepEqual(openPublicDelivery(compact, id), {
    ...value,
    protocol: 'EWDAP/1',
    deliveryId: id,
  });
  const header = JSON.parse(Buffer.from(compact.split('.')[0], 'base64url'));
  assert.equal(header.alg, 'ECDH-ES');
  assert.equal(header.enc, 'A256GCM');
  assert.equal(header.epk.crv, 'X25519');
  assert(!('d' in header.epk));
});

test('JWE helper refuses other recipients and rejects wrong IDs and authenticated tampering', () => {
  const id = randomUUID(),
    parts = sealDelivery({ status: 'synthetic' }, id).split('.');
  assert.throws(() =>
    sealDelivery({}, id, generateKeyPairSync('x25519').publicKey.export({ format: 'jwk' }).x),
  );
  assert.throws(() => openPublicDelivery(parts.join('.'), randomUUID()));
  for (const index of [2, 3, 4]) {
    const copy = [...parts],
      bytes = Buffer.from(copy[index], 'base64url');
    bytes[0] ^= 1;
    copy[index] = bytes.toString('base64url');
    assert.throws(() => openPublicDelivery(copy.join('.'), id));
  }
  assert.equal(MOBILE_PUBLIC, 'hSDwCYkwp1R0i33ctD73Wg2_Og0mOBr066SpjqqbTmo');
});

test('independent verifier validates raw ES256 signature and exact token/body hashes', () => {
  const verified = verifyProof(proof(), expected());
  assert.equal(verified.thumbprint, digest(canonical(jwk)));
  assert.deepEqual(verified.jwk, jwk);
});

test('DPoP verifier refuses altered method, URL, token, body, clock and signer', () => {
  const value = proof();
  for (const changed of [
    { method: 'DELETE' },
    { url: url + '/wrong' },
    { token: digest('wrong') },
    { body: body + ' ' },
    {
      jwk: generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).publicKey.export({
        format: 'jwk',
      }),
    },
  ])
    assert.throws(() => verifyProof(value, { ...expected(), ...changed }));
  assert.throws(() => verifyProof(proof({ iat: 1 }), expected()));
  assert.throws(() =>
    verifyProof(
      proof({}, generateKeyPairSync('ec', { namedCurve: 'prime256v1' }).privateKey),
      expected(),
    ),
  );
  const parts = value.split('.'),
    signature = Buffer.from(parts[2], 'base64url');
  signature[0] ^= 1;
  parts[2] = signature.toString('base64url');
  assert.throws(() => verifyProof(parts.join('.'), expected()));
});

test('one immutable operation uses fresh proofs while exact proof replay is refused', () => {
  const seen = new Set(),
    value = proof();
  verifyProof(value, { ...expected(), seen });
  assert.throws(() => verifyProof(value, { ...expected(), seen }));
  verifyProof(proof(), { ...expected(), seen });
  assert.equal(seen.size, 2);
});

test('bodyless unauthenticated proof cannot substitute token or nonempty request bytes', () => {
  const value = proof({ ath: undefined, bht: undefined });
  verifyProof(value, { method: 'POST', url, jwk });
  assert.throws(() => verifyProof(value, expected()));
});

test('canonical operation fingerprints sort nested keys and preserve meaningful changes', () => {
  const left = {
      operationId: 'public-original',
      target: { runtimeId: 'runtime', workspaceId: 'workspace' },
    },
    right = {
      target: { workspaceId: 'workspace', runtimeId: 'runtime' },
      operationId: 'public-original',
    };
  assert.equal(hash(canonical(left)), hash(canonical(right)));
  assert.notEqual(hash(canonical(left)), hash(canonical({ ...right, operationId: 'different' })));
});
