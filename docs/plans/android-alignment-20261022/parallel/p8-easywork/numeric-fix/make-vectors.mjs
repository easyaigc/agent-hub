import assert from 'node:assert/strict';
import {
  createCipheriv,
  createHash,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  hkdfSync,
} from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';

const root = new URL('../../../../../../', import.meta.url);
const source = new URL('apps/android/src/androidTest/assets/easywork-crypto-vector.json', root);
const originalBytes = await readFile(source);
const original = JSON.parse(originalBytes);
const { binding } = original.credential;
// Public RFC 7748 synthetic keys, matching the pre-existing independent Node vector.
const mobile = createPrivateKey({
  key: Buffer.concat([
    Buffer.from('302e020100300506032b656e04220420', 'hex'),
    Buffer.from(original.credential.privateKey, 'base64url'),
  ]),
  type: 'pkcs8',
  format: 'der',
});
const desktop = createPublicKey({
  key: { kty: 'OKP', crv: 'X25519', x: binding.desktopPublicKey },
  format: 'jwk',
});
const secret = diffieHellman({ privateKey: mobile, publicKey: desktop });
const key = Buffer.from(
  hkdfSync('sha256', secret, `easyai-remote:${binding.bindingId}`, 'v1:desktop-to-mobile', 32),
);
const frame = (sequence) => {
  const value = {
    version: 1,
    bindingId: binding.bindingId,
    senderDeviceId: binding.desktopDeviceId,
    senderSessionId: 'p8-numeric-node-vector',
    recipientDeviceId: binding.mobileDeviceId,
    sequence,
    sentAt: '2026-10-28T00:00:00.000Z',
  };
  const nonce = createHash('sha256')
    .update('public-synthetic-numeric-vector:' + sequence)
    .digest()
    .subarray(0, 12);
  const cipher = createCipheriv('aes-256-gcm', key, nonce);
  cipher.setAAD(
    Buffer.from(
      JSON.stringify([
        1,
        value.bindingId,
        value.senderDeviceId,
        value.senderSessionId,
        value.recipientDeviceId,
        sequence,
        value.sentAt,
      ]),
    ),
  );
  const encrypted = Buffer.concat([
    cipher.update(JSON.stringify(original.payload)),
    cipher.final(),
    cipher.getAuthTag(),
  ]);
  return {
    ...value,
    nonce: nonce.toString('base64url'),
    ciphertext: encrypted.toString('base64url'),
  };
};
const c = (field, token, accepted, id) => ({ id, field, token, accepted });
const vectors = {
  version: 1,
  sourceBase: 'bf5d1a7aeaaea4623078973f8e58ca70e288e7ab',
  sourceFixtureSha256: createHash('sha256').update(originalBytes).digest('hex'),
  frames: { minimum: frame(1), maximum: frame(Number.MAX_SAFE_INTEGER) },
  cases: [
    c('sequence', '42', true, 'sequence-integer'),
    c('sequence', '42.0', true, 'sequence-integral-decimal'),
    c('sequence', '4.2e1', true, 'sequence-integral-exponent'),
    c('version', '1', true, 'version-integer'),
    c('version', '1.0', true, 'version-integral-decimal'),
    c('version', '1e0', true, 'version-integral-exponent'),
    ...[
      ['"42"', 'string'],
      ['true', 'true'],
      ['false', 'false'],
      ['null', 'null'],
      ['{}', 'object'],
      ['[]', 'array'],
      ['42.5', 'fraction'],
      ['0', 'zero'],
      ['-1', 'negative'],
      ['9007199254740992', 'above-safe'],
      ['9223372036854775807', 'long-max'],
      ['1e999', 'overflow'],
      ['1e-999', 'underflow'],
      ['NaN', 'nan-token'],
      ['Infinity', 'infinity-token'],
    ].map(([token, id]) => c('sequence', token, false, 'sequence-' + id)),
    ...[
      ['"1"', 'string'],
      ['true', 'true'],
      ['false', 'false'],
      ['null', 'null'],
      ['{}', 'object'],
      ['[]', 'array'],
      ['1.5', 'fraction'],
      ['0', 'zero'],
      ['-1', 'negative'],
      ['2', 'unsupported'],
      ['9007199254740992', 'above-safe'],
      ['1e999', 'overflow'],
    ].map(([token, id]) => c('version', token, false, 'version-' + id)),
  ],
};
const target = new URL('numeric-vectors.json', import.meta.url);
if (process.argv.includes('--write'))
  await writeFile(target, JSON.stringify(vectors, null, 2) + '\n');
else assert.deepEqual(JSON.parse(await readFile(target)), vectors);
secret.fill(0);
key.fill(0);
console.log(
  JSON.stringify({
    independentBoundaryFrames: 2,
    numericCases: vectors.cases.length,
    acceptedCases: vectors.cases.filter((c) => c.accepted).length,
    rejectedCases: vectors.cases.filter((c) => !c.accepted).length,
  }),
);
