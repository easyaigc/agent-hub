import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import Ajv from 'ajv';

const schema = JSON.parse(
  readFileSync(
    new URL(
      '../../packages/protocol/schemas/computer-file-originals-v1.schema.json',
      import.meta.url,
    ),
    'utf8',
  ),
);
const validate = new Ajv({ allErrors: true }).compile(schema);
const id = '00000000-0000-4000-8000-000000000001';
const hash = 'a'.repeat(64);
const graph = () => ({
  schema: 'agent-hub/computer-file-originals/v1',
  sourcePlatform: 'android',
  destinationPolicy: 'inert',
  journalFormat: 'agent-hub/android-computer-files-v1',
  journals: [{ name: 'computer-files-v1:' + hash, size: 256, sha256: hash }],
  chunks: [],
  absentCompletedChunks: [],
});
const originalName = (index) => `computer-file-original:${id}:${index}`;

test('present and retired original names use the same canonical 0 through 4095 domain', () => {
  for (const index of ['0', '9', '10', '999', '1000', '3999', '4000', '4089', '4090', '4095']) {
    const value = graph();
    value.chunks = [{ name: originalName(index), size: 1, sha256: hash }];
    assert.equal(validate(value), true, index);
    value.chunks = [];
    value.absentCompletedChunks = [originalName(index)];
    assert.equal(validate(value), true, index);
  }
  for (const index of ['00', '01', '-1', '4096', '9999', '10000', '1.0', '../0', '0/extra']) {
    const value = graph();
    value.chunks = [{ name: originalName(index), size: 1, sha256: hash }];
    assert.equal(validate(value), false, index);
    value.chunks = [];
    value.absentCompletedChunks = [originalName(index)];
    assert.equal(validate(value), false, index);
  }
});

test('portable descriptors grant no active destination, source-device key, URI or retry authority', () => {
  assert.equal(validate(graph()), true);
  for (const changes of [
    { destinationPolicy: 'active' },
    { sourcePlatform: 'ios' },
    { sourceDeviceKey: 'synthetic-key' },
    { uri: 'content://synthetic/file' },
    { retry: true },
    { phase: 'READY' },
    { activate: true },
  ])
    assert.equal(validate({ ...graph(), ...changes }), false);
  const value = graph();
  value.chunks = [{ name: originalName('0'), size: 1, sha256: hash, path: '/synthetic/file' }];
  assert.equal(validate(value), false);
});

test('descriptor shape keeps journal and chunk quotas and finite integer sizes', () => {
  for (const size of [0, -1, 1.5, 1048577, Number.POSITIVE_INFINITY]) {
    const value = graph();
    value.chunks = [{ name: originalName('0'), size, sha256: hash }];
    assert.equal(validate(value), false);
  }
  for (const size of [0, 900001, 2.5]) {
    const value = graph();
    value.journals[0].size = size;
    assert.equal(validate(value), false);
  }
  const value = graph();
  value.absentCompletedChunks = [originalName('0'), originalName('0')];
  assert.equal(validate(value), false);
});
