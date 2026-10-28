import assert from 'node:assert/strict';
import {
  createCipheriv,
  createHash,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
} from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { canonical, hash, openPublicDelivery } from '../../authorization-boundaries/fixture.mjs';

const root = new URL('../../../../../../../', import.meta.url);
const asset = new URL('apps/android/src/androidTest/assets/easywork-canonical-keys-v1.json', root);
const credential = JSON.parse(
  await readFile(new URL('apps/android/src/androidTest/assets/easywork-crypto-vector.json', root)),
).credential;
const prior = JSON.parse(await readFile(new URL('../phase7/startup-delta.json', import.meta.url)));
const params = {
  threadId: 'synthetic-thread',
  clientRequestId: 'stable-operation',
  text: 'immutable synthetic input',
  'metadata/path': { '问"题/😀': '原文/值' },
};
const cases = [
  ['slash-key', { 'a/b': 'value/with/slash' }],
  ['quote-and-slash', { 'a"/b': 'quote " and \\/' }],
  ['chinese-key', { '中文/键': '原文/值' }],
  ['supplementary-key', { '😀/键': 'A😀é' }],
  ['combining-key', { 'é/é': 'é/é' }],
  ['line-separator-key', { 'line\u2028/\u2029': 'value\u2028/\u2029' }],
  ['control-key', { '\u0000\b\f\n\r\t/': '\u0001\u000b\u001f' }],
  ['nested-array', { z: [{ 'nested/key': 'nested/value' }, { 'quote"': '/' }], a: null }],
  ['ascii-sort', { 'z/path': 'z', 'a/path': 'a', 'm/path': 'm' }],
  ['exact-four-source-graph', prior.sourceGraph],
  ['request-hash-slash-key', { target: credential.binding.target, method: 'turn/start', params }],
  [
    'ordinary-declared-keys',
    {
      target: credential.binding.target,
      method: 'turn/start',
      params: {
        threadId: 'synthetic-thread',
        clientRequestId: 'stable-operation',
        text: 'original / " 中文 😀',
      },
    },
  ],
];
// Exercise the delivered protocol function, with only its single TypeScript signature erased.
const patch = await readFile(
  new URL('docs/integrations/easywork-ewdap-1/implementation.patch', root),
  'utf8',
);
const start = patch.indexOf(
  '+export function canonicalDeviceAuthorizationJson(value: unknown): string {',
);
assert(start > 0);
const fragment = patch
  .slice(start)
  .split('\ndiff --git ')[0]
  .split('\n')
  .filter((s) => s.startsWith('+'))
  .map((s) => s.slice(1))
  .join('\n');
const protocol = Function(
  fragment.replace('export function', 'function').replace('(value: unknown): string', '(value)') +
    '\nreturn canonicalDeviceAuthorizationJson;',
)();
const vectors = cases.map(([id, input]) => {
  const bytes = protocol(input);
  assert.equal(canonical(input), bytes);
  return { id, input, canonical: bytes, sha256: hash(bytes) };
});
assert.equal(
  vectors.find((v) => v.id === 'exact-four-source-graph').sha256,
  prior.sourceGraphSha256,
);
// Fixed public-only encryption fixture. Its unsorted, slash-escaped header MUST remain the AAD.
const privateKey = createPrivateKey({
  format: 'der',
  type: 'pkcs8',
  key: Buffer.concat([
    Buffer.from('302e020100300506032b656e04220420', 'hex'),
    createHash('sha256').update('public p8 canonical header ephemeral').digest(),
  ]),
});
const identity = {
  deviceId: credential.binding.mobileDeviceId,
  privateKey: credential.privateKey,
  publicKey: credential.binding.mobilePublicKey,
};
const deliveryId = '846952ad-8e58-407c-861f-76eb5a286e85';
const header = {
  typ: 'ewdap+jwe',
  version: 1,
  deliveryId,
  alg: 'ECDH-ES',
  enc: 'A256GCM',
  epk: createPublicKey(privateKey).export({ format: 'jwk' }),
  'evidence/path': 'old/quote"/中文',
};
const headerBytes = JSON.stringify(header).replaceAll('/', '\\/');
const encoded = Buffer.from(headerBytes).toString('base64url');
const shared = diffieHellman({
  privateKey,
  publicKey: createPublicKey({
    format: 'jwk',
    key: { kty: 'OKP', crv: 'X25519', x: identity.publicKey },
  }),
});
const u32 = (n) => {
  const b = Buffer.alloc(4);
  b.writeUInt32BE(n);
  return b;
};
const key = createHash('sha256')
  .update(Buffer.concat([u32(1), shared, u32(7), Buffer.from('A256GCM'), u32(0), u32(0), u32(256)]))
  .digest();
shared.fill(0);
const nonce = createHash('sha256')
  .update('public p8 canonical header nonce')
  .digest()
  .subarray(0, 12);
const payload = { protocol: 'EWDAP/1', deliveryId, 'old/key': 'original/quote"/😀' };
const cipher = createCipheriv('aes-256-gcm', key, nonce);
cipher.setAAD(Buffer.from(encoded));
const encrypted = Buffer.concat([
  cipher.update(JSON.stringify(payload).replaceAll('/', '\\/')),
  cipher.final(),
]);
key.fill(0);
const delivery = [
  encoded,
  '',
  nonce.toString('base64url'),
  encrypted.toString('base64url'),
  cipher.getAuthTag().toString('base64url'),
].join('.');
assert.deepEqual(openPublicDelivery(delivery, deliveryId), payload);
const changed = delivery.split('.');
changed[0] = Buffer.from(protocol(header)).toString('base64url');
assert.notEqual(changed[0], encoded);
assert.throws(() => openPublicDelivery(changed.join('.'), deliveryId));
const result = {
  version: 1,
  scope:
    'String/member escaping and sorted non-numeric protocol keys; no general RFC8785/number-format claim',
  vectors,
  protectedDelivery: {
    identity,
    deliveryId,
    delivery,
    deliverySha256: hash(delivery),
    protectedHeaderSha256: hash(headerBytes),
    payloadCanonical: protocol(payload),
  },
};
if (process.argv.includes('--write'))
  await writeFile(asset, JSON.stringify(result, null, 2) + '\n');
else assert.deepEqual(JSON.parse(await readFile(asset)), result);
console.log(
  JSON.stringify({
    nodeProtocolAndFixtureVectors: vectors.length,
    protectedOriginalJwe: 'accepted',
    reencodedProtectedHeader: 'rejected',
    sourceGraphSha256: prior.sourceGraphSha256,
  }),
);
