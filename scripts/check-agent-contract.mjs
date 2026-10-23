import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import Ajv from 'ajv';

const root = process.cwd();
const schemaPath = 'packages/protocol/schemas/agent-management-v1.schema.json';
const schemaData = await readFile(schemaPath);
const manifest = JSON.parse(await readFile('packages/protocol/contract-manifest.json', 'utf8'));
assert.equal(manifest.contract, 'agent-management');
assert.equal(manifest.version, 1);
assert.equal(manifest.sha256, createHash('sha256').update(schemaData).digest('hex'));
const schema = JSON.parse(schemaData);
const ajv = new Ajv({ allErrors: true, strictKeywords: true });
const validator = ajv.compile(schema);
const connection = {
  target: {
    agentId: 'easywork',
    hostId: 'host-example',
    workspaceId: 'workspace-example',
    runtimeId: 'local',
    account: { authority: 'https://account.example', subject: 'owner-example' },
  },
  candidates: [
    {
      id: 'lan',
      transportId: 'lan-wss',
      endpoint: 'https://computer.example:4319',
      peerFingerprint: 'a'.repeat(64),
      networkKind: 'vpn',
    },
    { id: 'relay', transportId: 'relay-http', endpoint: 'https://relay.example' },
  ],
  preference: 'automatic',
  credentialRef: 'keychain-reference-example',
};
assert.equal(validator(connection), true, JSON.stringify(validator.errors));
for (const invalid of [
  { ...connection, preference: 'arbitrary' },
  { ...connection, shell: 'arbitrary-command' },
  { ...connection, candidates: [{ ...connection.candidates[0], networkKind: 'public' }] },
  { ...connection, target: { ...connection.target, runtimeId: '' } },
  { ...connection, candidates: [{ ...connection.candidates[0], peerFingerprint: 'untrusted' }] },
])
  assert.equal(validator(invalid), false);

const target = connection.target;
const fixtures = {
  generationSettings: [
    'AgentGenerationSettings',
    { model: 'provider/model', effort: 'high', permissionMode: 'confirm_high_risk', mode: 'plan' },
  ],
  model: [
    'AgentModel',
    { id: 'provider/model', name: 'Model', isDefault: true, supportedEfforts: ['high'] },
  ],
  plan: [
    'AgentPlan',
    {
      id: 'plan',
      version: 2,
      runId: 'run',
      status: 'ready',
      text: 'Plan',
      steps: [{ title: 'Read', status: 'pending' }],
    },
  ],
  project: [
    'AgentProject',
    { id: 'project', target, name: 'Project', roots: ['workspace://project'] },
  ],
  thread: ['AgentThread', { id: 'thread', target, title: 'Chat', state: 'waiting-for-approval' }],
  resource: [
    'AgentResource',
    {
      id: 'resource',
      target,
      threadId: 'thread',
      name: 'input.png',
      mediaType: 'image/png',
      sizeBytes: 10,
      sha256: 'a'.repeat(64),
    },
  ],
  message: [
    'AgentMessage',
    {
      id: 'message',
      role: 'assistant',
      status: 'inProgress',
      parts: [
        { type: 'text', text: 'hello' },
        { type: 'resource', resourceId: 'resource', name: 'file', mediaType: 'text/plain' },
        {
          type: 'tool',
          id: 'tool',
          name: 'read',
          status: 'completed',
          input: '{}',
          output: 'content',
        },
        { type: 'diff', id: 'diff', text: '+hello' },
        {
          type: 'extension',
          namespace: 'provider.reasoning',
          data: { value: ['content', 1, true] },
        },
      ],
    },
  ],
  approval: [
    'AgentApproval',
    {
      id: 'approval',
      target,
      threadId: 'thread',
      runId: 'run',
      kind: 'question',
      title: 'Pick',
      detail: '',
      decisions: ['answer', 'deny'],
      questions: [
        {
          id: 'format',
          question: 'Which?',
          options: [{ label: 'short', description: 'Short answer' }],
          multiple: true,
          custom: false,
          isSecret: false,
        },
      ],
    },
  ],
  receipt: [
    'AgentOperationReceipt',
    {
      operationId: 'operation',
      target,
      threadId: 'thread',
      state: 'uncertain',
      error: 'reconcile',
      result: { runId: 'run' },
    },
  ],
  cursor: ['AgentEventCursor', { target, threadId: 'thread', epoch: 'epoch', sequence: 2 }],
  connectionState: [
    'AgentConnectionState',
    {
      target,
      phase: 'recovering',
      transportId: 'lan-wss',
      processHealthy: true,
      runtimeConnected: false,
      historyReadable: true,
    },
  ],
  command: [
    'AgentCommand',
    {
      version: 1,
      target,
      operationId: 'op',
      command: { operation: 'run.stop', threadId: 'thread', runId: 'run' },
    },
  ],
};
for (const [name, [, input]] of Object.entries(fixtures)) {
  const validate = ajv.getSchema(schema.$id + '#/definitions/' + name);
  assert.equal(validate(input), true, JSON.stringify(validate.errors));
  assert.equal(validate({ ...input, shell: 'arbitrary-method' }), false);
}
const commandValidator = ajv.getSchema(schema.$id + '#/definitions/command');
for (const operation of [
  { operation: 'models.list' },
  {
    operation: 'message.send',
    threadId: 'thread',
    parts: [{ type: 'text', text: 'queued' }],
    mode: 'queue',
    settings: fixtures.generationSettings[1],
  },
  {
    operation: 'message.send',
    threadId: 'thread',
    parts: [{ type: 'text', text: 'steer' }],
    mode: 'steer',
    expectedRunId: 'run',
  },
  {
    operation: 'plan.approve',
    threadId: 'thread',
    planId: 'plan',
    version: 2,
    settings: fixtures.generationSettings[1],
  },
  {
    operation: 'plan.revise',
    threadId: 'thread',
    planId: 'plan',
    version: 2,
    parts: [{ type: 'text', text: 'revise' }],
  },
  { operation: 'queue.cancel', threadId: 'thread', messageId: 'queued' },
  { operation: 'queue.execute', threadId: 'thread', messageId: 'queued', expectedRunId: 'run' },
]) {
  assert.equal(
    commandValidator({ version: 1, target, operationId: 'op', command: operation }),
    true,
    JSON.stringify(commandValidator.errors),
  );
  assert.equal(
    commandValidator({
      version: 1,
      target,
      operationId: 'op',
      command: { ...operation, method: 'arbitrary' },
    }),
    false,
  );
}
assert.equal(
  commandValidator({
    version: 1,
    target,
    operationId: 'op',
    command: { operation: 'run.stop', threadId: 'thread', runId: 'run' },
  }),
  true,
);
assert.equal(
  commandValidator({
    version: 1,
    target,
    operationId: 'op',
    command: { operation: 'electron.ipc', method: 'arbitrary' },
  }),
  false,
);

// Round-trip the actual Swift connection declarations, so encoding/casing drift
// fails this check instead of leaving a second manually maintained fixture.
if (process.platform === 'darwin') {
  const macTarget = process.arch === 'arm64' ? 'MacosArm64' : 'MacosX64';
  execFileSync(
    process.execPath,
    ['scripts/mobile/gradle.mjs', ':mobile-core:linkDebugFramework' + macTarget],
    { stdio: 'inherit' },
  );
  const source = await readFile('apps/ios/Sources/AgentClient.swift', 'utf8');
  const shared = await readFile('apps/ios/Sources/NotificationModels.swift', 'utf8');
  const localization = await readFile('apps/ios/Sources/Localization.swift', 'utf8');
  const declarations =
    localization +
    '\n' +
    shared.slice(0, shared.indexOf('enum TaskNoticeKind:')) +
    '\n' +
    source.slice(0, source.indexOf('/// Product operations.')) +
    '\n' +
    source.slice(source.indexOf('extension AgentConnection {')) +
    '\n' +
    `enum SharedMobileRules {
      static func validateDomain(_ decoder: Decoder, definition: String) throws {
        let raw = try AgentJSONValue(from: decoder)
        let json = String(decoding: try JSONEncoder().encode(raw), as: UTF8.self)
        if let reason = DomainContract.shared.validate(json: json, definition: definition) {
          throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: reason))
        }
      }
    }`;
  const directory = path.join(root, '.build/agent-contract');
  await mkdir(directory, { recursive: true });
  const swift = path.join(directory, 'Contract.swift');
  const executable = path.join(directory, 'contract');
  await writeFile(
    swift,
    declarations +
      '\nlet input = FileHandle.standardInput.readDataToEndOfFile()\nswitch CommandLine.arguments.dropFirst().first ?? "connection" {\n' +
      Object.values(fixtures)
        .map(
          ([type]) =>
            `case "${type}": FileHandle.standardOutput.write(try JSONEncoder().encode(AgentDomainCodec.decode(${type}.self, from: input)))`,
        )
        .join('\n') +
      '\ndefault: FileHandle.standardOutput.write(try JSONEncoder().encode(AgentDomainCodec.decode(AgentConnection.self, from: input)))\n}\n',
  );
  execFileSync(
    'xcrun',
    [
      'swiftc',
      '-F',
      '.build/native/mobile-core/bin/' +
        macTarget[0].toLowerCase() +
        macTarget.slice(1) +
        '/debugFramework',
      '-framework',
      'MobileCore',
      swift,
      '-o',
      executable,
    ],
    {
      env: {
        ...process.env,
        DEVELOPER_DIR: process.env.DEVELOPER_DIR || '/Applications/Xcode.app/Contents/Developer',
      },
    },
  );
  for (const [name, [type, input]] of Object.entries(fixtures)) {
    const output = JSON.parse(
      execFileSync(executable, [type], { input: JSON.stringify(input), encoding: 'utf8' }),
    );
    assert.equal(ajv.getSchema(schema.$id + '#/definitions/' + name)(output), true);
    assert.deepEqual(output, input);
  }
  for (const preference of ['automatic', 'lan-only', 'relay-only']) {
    const input = { ...connection, preference };
    const result = JSON.parse(
      execFileSync(executable, { input: JSON.stringify(input), encoding: 'utf8' }),
    );
    assert.equal(validator(result), true);
    assert.deepEqual(result, input);
  }
}
const index = process.argv.indexOf('--easywork-root');
if (index >= 0) {
  assert.deepEqual(
    await readFile(
      path.join(
        process.argv[index + 1],
        'packages/agent-protocol/contracts/agent-management-v1.schema.json',
      ),
    ),
    schemaData,
    'Fixed protocol artifact has drifted across repositories',
  );
  assert.deepEqual(
    JSON.parse(
      await readFile(
        path.join(process.argv[index + 1], 'packages/agent-protocol/contracts/manifest.json'),
        'utf8',
      ),
    ),
    manifest,
  );
}
console.log(
  'Agent management v1: strict schema and native Swift round-trip passed; sha256=' +
    createHash('sha256').update(schemaData).digest('hex'),
);
