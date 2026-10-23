import {
  createPrivateKey,
  createPublicKey,
  diffieHellman,
  hkdfSync,
  createCipheriv,
  createDecipheriv,
} from 'node:crypto';
import { readFile, writeFile } from 'node:fs/promises';
import assert from 'node:assert/strict';

// Fixed synthetic keys, not credentials. Independent Node crypto checks Android's actual bytes.
const mobile = Buffer.from(
  '77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a',
  'hex',
);
const desktop = Buffer.from(
  '5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb',
  'hex',
);
const key = (bytes) =>
  createPrivateKey({
    key: Buffer.concat([Buffer.from('302e020100300506032b656e04220420', 'hex'), bytes]),
    format: 'der',
    type: 'pkcs8',
  });
const publicBytes = (bytes) =>
  createPublicKey(key(bytes)).export({ format: 'der', type: 'spki' }).subarray(-32);
const target = {
  account: { authority: 'https://acceptance.example', subject: 'synthetic-user' },
  desktopDeviceId: 'desktop-vector',
  workspaceId: 'workspace-vector',
  runtimeId: 'runtime-vector',
};
const binding = {
  bindingId: 'binding-vector',
  account: target.account,
  desktopDeviceId: target.desktopDeviceId,
  mobileDeviceId: 'mobile-vector',
  desktopPublicKey: publicBytes(desktop).toString('base64url'),
  mobilePublicKey: publicBytes(mobile).toString('base64url'),
  target,
  status: 'active',
};
const secret = diffieHellman({ privateKey: key(mobile), publicKey: createPublicKey(key(desktop)) });
const derive = (direction) =>
  Buffer.from(
    hkdfSync('sha256', secret, `easyai-remote:${binding.bindingId}`, `v1:${direction}`, 32),
  );
const header = (v) =>
  Buffer.from(
    JSON.stringify([
      1,
      v.bindingId,
      v.senderDeviceId,
      ...(v.senderSessionId ? [v.senderSessionId] : []),
      v.recipientDeviceId,
      v.sequence,
      v.sentAt,
    ]),
  );
const payload = {
  kind: 'protocol',
  proof: '中文🙂 / 反斜杠\\与换行\n',
  envelope: {
    protocolVersion: 2,
    kind: 'response',
    requestId: 'vector',
    ok: true,
    result: { verified: true },
  },
};
const frame = (legacy) => {
  const v = {
    version: 1,
    bindingId: binding.bindingId,
    senderDeviceId: binding.desktopDeviceId,
    ...(!legacy ? { senderSessionId: 'desktop-session-vector' } : {}),
    recipientDeviceId: binding.mobileDeviceId,
    sequence: 42,
    sentAt: '2026-10-14T00:00:00.000Z',
  };
  const nonce = Buffer.from('000102030405060708090a0b', 'hex');
  const cipher = createCipheriv('aes-256-gcm', derive('desktop-to-mobile'), nonce);
  cipher.setAAD(header(v));
  return {
    ...v,
    nonce: nonce.toString('base64url'),
    ciphertext: Buffer.concat([
      cipher.update(JSON.stringify(payload)),
      cipher.final(),
      cipher.getAuthTag(),
    ]).toString('base64url'),
  };
};
if (process.argv[2] === 'generate') {
  await writeFile(
    process.argv[3],
    JSON.stringify(
      {
        credential: { binding, privateKey: mobile.toString('base64url') },
        confirmation: Buffer.from(
          hkdfSync('sha256', secret, binding.bindingId, 'easyai-remote:key-confirmation:v1', 16),
        ).toString('base64url'),
        payload,
        frame: frame(false),
        legacy: frame(true),
      },
      null,
      2,
    ) + '\n',
  );
} else if (process.argv[2] === 'verify') {
  const v = JSON.parse(await readFile(process.argv[3], 'utf8'));
  assert.equal(v.bindingId, binding.bindingId);
  assert.equal(v.senderDeviceId, binding.mobileDeviceId);
  assert.equal(v.recipientDeviceId, binding.desktopDeviceId);
  const encrypted = Buffer.from(v.ciphertext, 'base64url');
  const decipher = createDecipheriv(
    'aes-256-gcm',
    derive('mobile-to-desktop'),
    Buffer.from(v.nonce, 'base64url'),
  );
  decipher.setAAD(header(v));
  decipher.setAuthTag(encrypted.subarray(-16));
  assert.deepEqual(
    JSON.parse(
      Buffer.concat([decipher.update(encrypted.subarray(0, -16)), decipher.final()]).toString(),
    ),
    payload,
  );
  console.log(JSON.stringify({ verified: true, sequence: v.sequence, bidirectionalInterop: true }));
} else throw new Error('Use generate <fixture> or verify <native-output>');
