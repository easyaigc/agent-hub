import assert from 'node:assert/strict';
import Ajv from 'ajv';
import {
  createCipheriv,
  createDecipheriv,
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  hkdfSync,
} from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import { codexPrivateConnectionContext } from '../../packages/protocol/dist/index.js';

// Public, deterministic test material only. Never used by the production identity generator.
const rawPrivate = (offset) => Buffer.from(Array.from({ length: 32 }, (_, i) => i + offset));
const privateKey = (raw) =>
  createPrivateKey({
    key: Buffer.concat([Buffer.from('302e020100300506032b656e04220420', 'hex'), raw]),
    type: 'pkcs8',
    format: 'der',
  });
const mobilePrivate = rawPrivate(1),
  desktopPrivate = rawPrivate(33);
const mobileKey = privateKey(mobilePrivate),
  desktopKey = privateKey(desktopPrivate);
const publicBytes = (key) =>
  createPublicKey(key).export({ type: 'spki', format: 'der' }).subarray(-32);
const publicKey = publicBytes(mobileKey).toString('base64url');
const target = {
  agentId: 'codex',
  hostId: 'fixture-private-host',
  workspaceId: 'fixture-workspace',
  runtimeId: 'fixture-runtime',
  account: { authority: 'fixture😀', subject: 'owner' },
};
const context = Buffer.from(codexPrivateConnectionContext(target, publicKey));
const shared = diffieHellman({ privateKey: mobileKey, publicKey: createPublicKey(desktopKey) });
assert.deepEqual(
  shared,
  diffieHellman({ privateKey: desktopKey, publicKey: createPublicKey(mobileKey) }),
);
const key = Buffer.from(hkdfSync('sha256', shared, Buffer.alloc(0), context, 32));
const token = Buffer.alloc(32, 9).toString('base64url');
const nonce = Buffer.from(Array.from({ length: 12 }, (_, i) => i + 60));
const cipher = createCipheriv('aes-256-gcm', key, nonce);
cipher.setAAD(context);
const ciphertext = Buffer.concat([cipher.update(token), cipher.final(), cipher.getAuthTag()]);
const reply = {
  version: 1,
  target,
  publicKey,
  ephemeralPublicKey: publicBytes(desktopKey).toString('base64url'),
  nonce: nonce.toString('base64url'),
  ciphertext: ciphertext.toString('base64url'),
};
const vector = {
  version: 1,
  purpose: 'synthetic public cross-platform v1 test vector, never a production credential',
  identity: {
    deviceId: 'fixture-mobile',
    privateKey: mobilePrivate.toString('base64url'),
    publicKey,
  },
  target,
  contextUTF8Hex: context.toString('hex'),
  reply,
  expectedToken: token,
};
const filename = 'packages/protocol/fixtures/mobile/private-connection-v1.json';
if (process.argv.includes('--write'))
  await writeFile(filename, JSON.stringify(vector, null, 2) + '\n');
else assert.deepEqual(JSON.parse(await readFile(filename, 'utf8')), vector);
const decipher = createDecipheriv('aes-256-gcm', key, nonce);
decipher.setAAD(context);
decipher.setAuthTag(ciphertext.subarray(-16));
assert.equal(
  Buffer.concat([decipher.update(ciphertext.subarray(0, -16)), decipher.final()]).toString(),
  token,
);
for (const badContext of [
  Buffer.from(
    codexPrivateConnectionContext(
      { ...target, account: { ...target.account, subject: 'different' } },
      publicKey,
    ),
  ),
  Buffer.from('wrong-context'),
]) {
  const bad = createDecipheriv('aes-256-gcm', key, nonce);
  bad.setAAD(badContext);
  bad.setAuthTag(ciphertext.subarray(-16));
  assert.throws(() => {
    bad.update(ciphertext.subarray(0, -16));
    bad.final();
  });
}
console.log(
  'Synthetic v1 UTF-16/HKDF/X25519/AES-GCM vector and wrong-context authentication checks passed',
);

const schema = JSON.parse(
  await readFile('packages/protocol/schemas/codex-host-discovery-v1.schema.json', 'utf8'),
);
const validator = new Ajv({ allErrors: true, strictKeywords: true });
validator.addSchema(schema);
const claim = { version: 1, target, publicKey };
for (const [definition, valid, invalid] of [
  [
    'privateClaim',
    claim,
    [
      { ...claim, token: token },
      { ...claim, version: '1' },
      { ...claim, target: { ...target, agentId: 'other' } },
    ],
  ],
  [
    'privateReply',
    reply,
    [
      { ...reply, nonce: reply.nonce + '=' },
      { ...reply, publicKey: 'bad' },
      { ...reply, extra: true },
    ],
  ],
  [
    'vpnSnapshot',
    { version: 1, target, peers: [{ name: 'fixture', addresses: ['100.64.0.1'] }] },
    [
      { version: 1, target, peers: Array(33).fill({ name: 'fixture', addresses: ['100.64.0.1'] }) },
      { version: 1, target, peers: [{ name: 'fixture', addresses: ['100.64.0.1', '100.64.0.1'] }] },
    ],
  ],
]) {
  const validate = validator.getSchema(schema.$id + '#/definitions/' + definition);
  assert(validate(valid), definition);
  for (const value of invalid)
    assert.equal(validate(value), false, definition + ' rejected boundary');
}
console.log('Strict private claim/reply and bounded peer-directory Node/schema boundaries passed');
