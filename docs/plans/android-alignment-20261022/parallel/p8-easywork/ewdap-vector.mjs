import assert from 'node:assert/strict';
import {
  createCipheriv,
  createHash,
  createPrivateKey,
  diffieHellman,
  createPublicKey,
} from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

// Fixed synthetic keys/nonce for an independently generated interoperability fixture.
// Never use these public test values for a real device or grant.
const root = new URL('../../../../../', import.meta.url);
const original = JSON.parse(
  await readFile(new URL('apps/android/src/androidTest/assets/easywork-crypto-vector.json', root)),
);
const credential = original.credential;
const identity = {
  deviceId: credential.binding.mobileDeviceId,
  privateKey: credential.privateKey,
  publicKey: credential.binding.mobilePublicKey,
};
const key = createPrivateKey({
  format: 'der',
  type: 'pkcs8',
  key: Buffer.concat([
    Buffer.from('302e020100300506032b656e04220420', 'hex'),
    createHash('sha256').update('public synthetic p8 ewdap ephemeral').digest(),
  ]),
});
const publicKey = createPublicKey(key).export({ format: 'jwk' });
const shared = diffieHellman({
  privateKey: key,
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
const algorithm = Buffer.from('A256GCM');
const encryptionKey = createHash('sha256')
  .update(
    Buffer.concat([u32(1), shared, u32(algorithm.length), algorithm, u32(0), u32(0), u32(256)]),
  )
  .digest();
const deliveryId = '3d1d45bb-8db4-43bd-80d5-27c7fae639d3';
const header = Buffer.from(
  JSON.stringify({
    alg: 'ECDH-ES',
    enc: 'A256GCM',
    typ: 'ewdap+jwe',
    version: 1,
    deliveryId,
    epk: publicKey,
  }),
).toString('base64url');
const nonce = createHash('sha256')
  .update('public synthetic p8 ewdap nonce')
  .digest()
  .subarray(0, 12);
const payload = {
  protocol: 'EWDAP/1',
  deliveryId,
  synthetic: 'independent Node delivery; no real credentials',
};
const cipher = createCipheriv('aes-256-gcm', encryptionKey, nonce);
cipher.setAAD(Buffer.from(header));
const bytes = Buffer.concat([cipher.update(JSON.stringify(payload)), cipher.final()]);
const output =
  JSON.stringify(
    {
      version: 1,
      identity,
      deliveryId,
      payload,
      delivery: [
        header,
        '',
        nonce.toString('base64url'),
        bytes.toString('base64url'),
        cipher.getAuthTag().toString('base64url'),
      ].join('.'),
    },
    null,
    2,
  ) + '\n';
const file = new URL('ewdap-vector.json', import.meta.url);
if (process.argv.includes('--write')) await writeFile(file, output);
else assert.deepEqual(JSON.parse(await readFile(file)), JSON.parse(output));
shared.fill(0);
encryptionKey.fill(0);
console.log('Synthetic Node EWDAP vector verified: ' + fileURLToPath(file).split('/').at(-1));
