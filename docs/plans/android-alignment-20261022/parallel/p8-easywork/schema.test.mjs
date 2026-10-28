import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import Ajv from 'ajv';

const schema = JSON.parse(
  await readFile(new URL('provider-descriptor.schema.json', import.meta.url)),
);
const validate = new Ajv({ allErrors: true }).compile(schema);
const descriptor = () => ({
  version: 1,
  provider: 'easywork',
  target: {
    authority: 'https://issuer.example',
    subject: 'synthetic',
    desktopDeviceId: 'desktop',
    workspaceId: 'workspace',
    runtimeId: 'runtime',
  },
  requiresPairing: true,
  credentialState: 'none',
  preference: 'automatic',
  candidateOrigins: ['https://192.168.1.10:9443'],
});
test('proposed descriptor accepts descriptive metadata without authority to execute', () => {
  for (const preference of ['automatic', 'lan-only', 'relay-only'])
    assert(validate({ ...descriptor(), preference }));
});
test('proposed descriptor refuses inherited grants, local keys and OIDC material', () => {
  for (const field of [
    'credentialRef',
    'grant',
    'binding',
    'deviceId',
    'privateKey',
    'dpopAlias',
    'accessToken',
    'refreshToken',
    'oidcSession',
    'pkceVerifier',
    'pendingClaim',
    'refreshOperation',
    'certificateFingerprint',
  ]) {
    assert.equal(validate({ ...descriptor(), [field]: 'synthetic-secret' }), false, field);
  }
  assert.equal(
    validate({
      ...descriptor(),
      target: { ...descriptor().target, accessToken: 'synthetic-secret' },
    }),
    false,
  );
  assert.equal(validate({ ...descriptor(), requiresPairing: false }), false);
  assert.equal(validate({ ...descriptor(), credentialState: 'authorized' }), false);
});
test('proposed descriptor bounds target and candidates without making a transport trust claim', () => {
  for (const target of [
    undefined,
    { ...descriptor().target, runtimeId: '' },
    { ...descriptor().target, runtimeId: '   ' },
    { ...descriptor().target, subject: 'x'.repeat(513) },
  ])
    assert.equal(validate({ ...descriptor(), target }), false);
  for (const origin of [
    'http://public.example',
    'https://user:password@example.test',
    'https://relay.test/path',
    'https://relay.test?query',
    'https://relay.test#fragment',
  ])
    assert.equal(validate({ ...descriptor(), candidateOrigins: [origin] }), false);
  assert.equal(
    validate({
      ...descriptor(),
      candidateOrigins: Array.from({ length: 17 }, (_, i) => 'https://node' + i + '.example'),
    }),
    false,
  );
  assert.equal(
    validate({
      ...descriptor(),
      candidateOrigins: ['https://same.example', 'https://same.example'],
    }),
    false,
  );
  assert.equal(validate({ ...descriptor(), provider: 'unregistered' }), false);
});
