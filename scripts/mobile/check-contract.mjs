import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import Ajv from 'ajv';
import {
  parseVoiceTranscriptionRequest,
  parseChatActionRequest,
  parseWorkspaceManagementRequest,
} from '../../packages/protocol/dist/index.js';

const schema = JSON.parse(
  await readFile('packages/protocol/schemas/agent-management-v1.schema.json', 'utf8'),
);
const keywords = new Set([
  '$schema',
  '$id',
  'title',
  'description',
  'definitions',
  '$ref',
  'type',
  'properties',
  'additionalProperties',
  'required',
  'uniqueItems',
  'minLength',
  'maxLength',
  'format',
  'pattern',
  'enum',
  'minItems',
  'maxItems',
  'items',
  'minimum',
  'maximum',
  'oneOf',
  'const',
]);
function checkSupported(rule) {
  assert(rule && typeof rule === 'object' && !Array.isArray(rule));
  for (const [key, value] of Object.entries(rule)) {
    assert(
      keywords.has(key),
      'Implement shared validator support before adding schema keyword ' + key,
    );
    if (key === 'properties' || key === 'definitions') Object.values(value).forEach(checkSupported);
    if (key === 'items' || (key === 'additionalProperties' && typeof value === 'object'))
      checkSupported(value);
    if (key === 'oneOf') value.forEach(checkSupported);
    if (key === 'type')
      assert(['object', 'array', 'string', 'integer', 'number', 'boolean'].includes(value));
    if (key === 'format') assert.equal(value, 'uri');
    if (key === '$ref') assert(value.startsWith('#/definitions/'));
  }
}
checkSupported(schema);
const ajv = new Ajv({ allErrors: true, strictKeywords: true });
ajv.addSchema(schema);
const taskHubSchema = JSON.parse(
  await readFile('packages/protocol/schemas/task-hub-mobile-v1.schema.json', 'utf8'),
);
checkSupported(taskHubSchema);
ajv.addSchema(taskHubSchema);
const taskHub = JSON.parse(
  await readFile('packages/protocol/fixtures/mobile/task-hub-v1.json', 'utf8'),
);
for (const row of taskHub.contracts) {
  assert.equal(
    ajv.getSchema(taskHubSchema.$id + '#/definitions/' + row.definition)(row.value),
    row.valid,
    row.name,
  );
}
const fixtures = JSON.parse(
  await readFile('packages/protocol/fixtures/mobile/state-v1.json', 'utf8'),
);
for (const test of fixtures.contracts) {
  let valid = false;
  try {
    valid = ajv.getSchema(schema.$id + '#/definitions/' + test.definition)(JSON.parse(test.json));
  } catch (error) {
    if (!(error instanceof SyntaxError)) throw error;
  }
  assert.equal(valid, test.valid, test.name);
}
const httpSchema = JSON.parse(
  await readFile('packages/protocol/schemas/codex-mobile-http-v1.schema.json', 'utf8'),
);
checkSupported(httpSchema);
ajv.addSchema(httpSchema);
for (const test of fixtures.http) {
  if (test.definition === 'chatActionRequest' || test.definition === 'workspaceManagementRequest') {
    let accepted = false;
    try {
      (test.definition === 'chatActionRequest'
        ? parseChatActionRequest
        : parseWorkspaceManagementRequest)(JSON.parse(test.json));
      accepted = true;
    } catch {
      // A rejected named request is compared with the negative fixture below.
    }
    assert.equal(accepted, test.valid, test.name + ': Node named action boundary');
  }

  assert.equal(
    ajv.getSchema(httpSchema.$id + '#/definitions/' + test.definition)(JSON.parse(test.json)),
    test.valid,
    test.name,
  );
}
const sharingVoice = JSON.parse(
  await readFile('packages/protocol/fixtures/mobile/ai-sharing-voice-v1.json', 'utf8'),
);
for (const test of sharingVoice.manifests) {
  assert.equal(
    ajv.getSchema(httpSchema.$id + '#/definitions/voiceManifest')(test.value),
    test.expected,
    test.name,
  );
  let accepted = true;
  try {
    parseVoiceTranscriptionRequest(test.value);
  } catch {
    accepted = false;
  }
  assert.equal(accepted, test.expected, test.name + ': Host request parser');
}
console.log(sharingVoice.manifests.length + ' sharing/voice manifest Node/schema vectors passed');
console.log(
  fixtures.contracts.length +
    ' domain and ' +
    fixtures.http.length +
    ' Codex HTTP Node/schema mobile contract vectors passed',
);
