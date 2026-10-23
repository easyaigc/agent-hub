import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import childProcess, { spawn } from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import {
  DesktopCreationBroker,
  BeforeDesktopSubmit,
} from '../packages/codex-adapter/dist/desktop-creation.js';
import { NativeDesktopCreationDriver } from '../packages/codex-adapter/dist/desktop-creation-driver.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopRuntime } from '../packages/codex-adapter/dist/desktop-ipc.js';
import { protectWindowsStateFile } from '../packages/codex-adapter/dist/desktop-platform.js';
import { NotificationObserver } from '../packages/notification-runtime/dist/index.js';

const root = await mkdtemp(join(tmpdir(), 'codex-manage-creation-'));
const brokers = new Set();
let passed = 0;
const test = async (name, run) => {
  await run();
  passed++;
  console.log(JSON.stringify({ test: name, status: 'passed', scope: 'fixture-boundary' }));
};
const target = { hostId: 'host-a', projectId: 'project-a', directory: root };
const input = (id, changes = {}) => ({
  hostId: 'host-a',
  clientOperationId: id,
  projectId: 'project-a',
  text: 'hello',
  attachments: [],
  ...changes,
});
const make = (name, create, verify = async () => true, available = true) => {
  const broker = new DesktopCreationBroker(
    join(root, name),
    'host-a',
    { available, reason: available ? null : 'Not accepted', create },
    verify,
  );
  brokers.add(broker);
  return broker;
};
try {
  await test('unverified native driver never advertises creation', async () => {
    assert.equal(new NativeDesktopCreationDriver(undefined, '26.1002.52244').available, false);
    const broker = make(
      'disabled',
      async () => {
        throw Error('Must never run');
      },
      undefined,
      false,
    );
    assert.throws(() => broker.prepare(input('disabled-0001'), target), /Not accepted/);
  });
  await test('native project identity remains bound across journal restart and legacy retry', async () => {
    const nativeTarget = { ...target, nativeProjectId: 'selected-native-project' };
    const broker = make('native-target', async () => 'native-thread-0003');
    broker.prepare(input('native-target-01'), nativeTarget);
    broker.prepare(input('legacy-target-01'), target);
    broker.close();
    const restored = make('native-target', async (plan) => {
      assert.equal(plan.nativeProjectId, 'selected-native-project');
      return 'native-thread-0003';
    });
    assert.throws(
      () =>
        restored.prepare(input('native-target-01'), {
          ...target,
          nativeProjectId: 'different-native-project',
        }),
      /不同内容或目标/,
    );
    assert.equal(
      restored.prepare(input('legacy-target-01'), nativeTarget).nativeProjectId,
      undefined,
    );
    assert.equal((await restored.submit('native-target-01')).phase, 'confirmed');
  });
  await test('host, explicit projectless, immutable intent and unsafe IDs', async () => {
    const broker = make('targets', async () => 'native-thread-0001');
    assert.throws(
      () => broker.prepare(input('wrong-host-01', { hostId: 'host-b' }), target),
      /目标/,
    );
    assert.throws(() => broker.prepare(input('__proto__'), target), /操作 ID/);
    assert.throws(
      () =>
        broker.prepare(input('ambiguous-01', { projectId: null }), { ...target, projectId: null }),
      /不明确/,
    );
    broker.prepare(input('immutable-01'), target);
    assert.throws(
      () => broker.prepare(input('immutable-01', { text: 'changed' }), target),
      /不同内容/,
    );
    const generation = {
      model: 'fixture-model',
      effort: 'low',
      permissionMode: 'full-access',
      speed: 'fast',
    };
    broker.prepare(input('settings-01', generation), target);
    assert.equal((await broker.query('settings-01')).permissionMode, 'full-access');
    assert.equal((await broker.query('settings-01')).speed, 'fast');
    assert.throws(
      () => broker.prepare(input('settings-01', { ...generation, speed: 'standard' }), target),
      /不同内容/,
    );
    assert.throws(
      () => broker.prepare(input('speed-invalid', { speed: 'invalid' }), target),
      /生成设置/,
    );
    assert.throws(
      () => broker.prepare(input('settings-01', { ...generation, effort: 'high' }), target),
      /不同内容/,
    );
    assert.throws(
      () => broker.prepare(input('settings-invalid', { permissionMode: 'arbitrary' }), target),
      /生成设置/,
    );
    broker.prepare(input('plan-mode-01', { collaborationMode: 'plan' }), target);
    assert.equal((await broker.query('plan-mode-01')).collaborationMode, 'plan');
    assert.throws(
      () => broker.prepare(input('plan-mode-01', { collaborationMode: 'default' }), target),
      /不同内容/,
    );
    assert.throws(
      () => broker.prepare(input('plan-mode-invalid', { collaborationMode: 'invalid' }), target),
      /计划模式/,
    );
    const record = broker.prepare(input('projectless-01', { projectId: null }), {
      ...target,
      projectId: null,
      directory: null,
    });
    assert.equal(record.projectId, null);
    assert.equal(record.directory, null);
  });
  await test('simultaneous requests create once and preserve first input', async () => {
    let called = 0;
    const broker = make('once', async (plan) => {
      called++;
      assert.equal(plan.text, 'hello');
      await new Promise((r) => setTimeout(r, 20));
      return 'native-thread-0002';
    });
    broker.prepare(input('once-operation'), target);
    const results = await Promise.all([
      broker.submit('once-operation'),
      broker.submit('once-operation'),
    ]);
    assert.equal(called, 1);
    assert.deepEqual(
      results.map((r) => r.phase),
      ['confirmed', 'confirmed'],
    );
    await broker.submit('once-operation');
    assert.equal(called, 1);
  });
  await test('creation notifications exclude live confirmation and retain genuine uncertainty', async () => {
    let verifyStarted, releaseVerification;
    const started = new Promise((resolve) => {
      verifyStarted = resolve;
    });
    const verification = new Promise((resolve) => {
      releaseVerification = resolve;
    });
    const broker = make(
      'notification-confirmation',
      async () => 'native-notification-thread',
      async () => {
        verifyStarted();
        return verification;
      },
    );
    const observer = new NotificationObserver({
      agentId: 'codex',
      hostId: 'host-a',
      workspaceId: 'host-a',
      runtimeId: 'desktop-runtime',
    });
    const observe = () =>
      observer.observe({
        threadId: '',
        title: 'Creation',
        live: true,
        requests: [],
        uncertainOperations: broker.notificationOperations(),
      });
    broker.prepare(input('notification-normal'), target);
    const submission = broker.submit('notification-normal');
    await started;
    const journal = JSON.parse(
      await readFile(join(root, 'notification-confirmation', 'creations.json'), 'utf8'),
    );
    assert.equal(journal['notification-normal'].phase, 'outcome-unknown');
    assert.deepEqual(observe().events, [], 'Durable intermediate phase is not a user action');
    releaseVerification(true);
    assert.equal((await submission).phase, 'confirmed');
    assert.deepEqual(observe().events, []);
    let accepted = false,
      calls = 0;
    const unknown = make(
      'notification-unresolved',
      async () => {
        calls++;
        return 'native-unknown-thread';
      },
      async () => accepted,
    );
    unknown.prepare(input('notification-unknown'), target);
    assert.equal((await unknown.submit('notification-unknown')).phase, 'outcome-unknown');
    const now = Date.now();
    assert.deepEqual(unknown.notificationOperations(now), []);
    assert.deepEqual(unknown.notificationOperations(now + 29_999), []);
    assert.deepEqual(unknown.notificationOperations(now + 30_000), ['notification-unknown']);
    const details = unknown.notificationOperationDetails(now + 30_000);
    assert.equal(details.length, 1);
    assert.equal(details[0].clientOperationId, 'notification-unknown');
    assert.equal(details[0].threadId, 'native-unknown-thread');
    assert.equal(details[0].text, input('notification-unknown').text);
    details[0].attachments.push({ id: 'mutated-copy' });
    assert.deepEqual(unknown.notificationOperationDetails(now + 30_000)[0].attachments, []);
    accepted = true;
    await unknown.refreshNotificationOperations();
    assert.equal((await unknown.query('notification-unknown')).phase, 'confirmed');
    assert.deepEqual(unknown.notificationOperations(), []);
    assert.equal(calls, 1, 'Reconciliation never replays the input');
  });
  await test('notification refresh never resumes a first input or resolves a missing owner', async () => {
    let verified = false,
      sends = 0,
      resumes = 0;
    const broker = new DesktopCreationBroker(
      join(root, 'notification-readonly'),
      'host-a',
      {
        available: true,
        reason: null,
        create: async () => {
          sends++;
          return 'native-readonly-thread';
        },
        reconcile: async () => {
          resumes++;
          throw Error('Notification must not resume');
        },
      },
      async () => verified,
    );
    brokers.add(broker);
    broker.prepare(input('notification-readonly'), target);
    await broker.submit('notification-readonly');
    const now = Date.now();
    assert.deepEqual(broker.notificationOperations(now), []);
    await broker.refreshNotificationOperations();
    assert.equal((await broker.inspect('notification-readonly')).phase, 'outcome-unknown');
    assert.deepEqual(broker.notificationOperations(now + 30_000), ['notification-readonly']);
    assert.equal(
      broker.notificationOperationDetails(now + 30_000)[0].clientOperationId,
      'notification-readonly',
    );
    verified = true;
    await broker.refreshNotificationOperations();
    assert.equal((await broker.inspect('notification-readonly')).phase, 'confirmed');
    assert.deepEqual(broker.notificationOperations(now + 30_001), []);
    assert.equal(sends, 1);
    assert.equal(resumes, 0);
  });
  await test('attachments remain scoped, require bytes and recheck integrity', async () => {
    const bytes = Buffer.from('private fixture nonce');
    const file = {
      id: 'attachment-0001',
      name: 'check.txt',
      mediaType: 'text/plain',
      sizeBytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    };
    let called = 0;
    const broker = make('files', async (plan) => {
      called++;
      assert.deepEqual(await readFile(plan.files[0].path), bytes);
      return 'native-thread-0003';
    });
    broker.prepare(input('files-operation', { attachments: [file] }), target);
    await assert.rejects(broker.submit('files-operation'), /上传/);
    assert.throws(() => broker.upload('files-operation', 'another-file', bytes), /不属于/);
    assert.throws(() => broker.upload('files-operation', file.id, Buffer.from('bad')), /完整性/);
    broker.upload('files-operation', file.id, bytes);
    assert.equal((await broker.submit('files-operation')).phase, 'confirmed');
    assert.equal(called, 1);
    assert.throws(() => broker.upload('files-operation', file.id, bytes), /不能改写/);
    assert.throws(
      () =>
        broker.prepare(
          input('path-operation', { attachments: [{ ...file, name: '../escape' }] }),
          target,
        ),
      /清单/,
    );
  });
  await test('long Unicode attachment names survive upload, restart and immutable submission', async () => {
    const bytes = Buffer.from('synthetic Unicode filename attachment');
    const names = ['附件文件名长度复现'.repeat(9) + '.md', '文'.repeat(197) + '.md', 'legacy.txt'];
    const files = names.map((name, index) => ({
      id: 'unicode-file-' + index,
      name,
      mediaType: 'text/markdown',
      sizeBytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    }));
    const intent = input('unicode-operation', { attachments: files });
    const broker = make('unicode-files', async () => {
      throw Error('Submission happens only after restart');
    });
    broker.prepare(intent, target);
    for (const file of files) broker.upload(intent.clientOperationId, file.id, bytes);
    assert.throws(
      () =>
        broker.prepare(
          { ...intent, attachments: files.map((file) => ({ ...file, name: 'changed.md' })) },
          target,
        ),
      /不同内容/,
    );
    assert.throws(
      () =>
        broker.prepare(
          input('name-too-long', {
            attachments: [{ ...files[0], name: '文'.repeat(198) + '.md' }],
          }),
          target,
        ),
      /文件名过长/,
    );
    broker.close();
    let calls = 0;
    const restored = make('unicode-files', async (plan) => {
      calls++;
      assert.deepEqual(
        plan.files.map((file) => file.name),
        names,
      );
      for (const file of plan.files) {
        assert.deepEqual(await readFile(file.path), bytes);
        assert.equal(
          createHash('sha256')
            .update(await readFile(file.path))
            .digest('hex'),
          file.sha256,
        );
      }
      assert.equal(
        plan.files[2].path,
        join(root, 'unicode-files', intent.clientOperationId, files[2].id, files[2].name),
      );
      return 'unicode-native-thread';
    });
    assert.deepEqual(restored.prepare(intent, target).attachments, files);
    assert.equal((await restored.submit(intent.clientOperationId)).phase, 'confirmed');
    await restored.submit(intent.clientOperationId);
    assert.equal(calls, 1);
  });
  await test('native creation confirmation and download registration use the uploaded Unicode path', async () => {
    const bytes = Buffer.from('synthetic Unicode confirmation');
    const file = {
      id: 'unicode-confirm-file',
      name: '文'.repeat(100) + '.md',
      mediaType: 'text/markdown',
      sizeBytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    };
    const threadId = 'unicode-confirm-thread';
    let snapshot, uploadedPath;
    const runtime = Object.assign(Object.create(DesktopRuntime.prototype), {
      ipc: { ready: true, follow: async () => snapshot },
      reader: {
        ready: true,
        read: async () => ({ thread: { projectId: null } }),
        registerOwnerThread() {},
      },
    });
    const session = Object.assign(Object.create(RuntimeSession.prototype), {
      runtime,
      options: { directory: join(root, 'unicode-confirm') },
      state: { hostId: 'host-a', threads: [], creations: {}, attachments: [] },
      desktopCreationDriver: { inputTransport: 'desktop-ipc' },
      loaded: new Set(),
      desktopSnapshot: async (thread) => {
        thread.messages = [{ role: 'user', attachments: [] }];
      },
      save() {},
      emit() {},
    });
    const broker = make(
      'unicode-confirm/desktop-creations',
      async (plan) => {
        uploadedPath = plan.files[0].path;
        snapshot = {
          id: threadId,
          hostId: 'local',
          workspaceKind: 'projectless',
          turns: [
            {
              items: [
                {
                  type: 'userMessage',
                  content: [
                    { type: 'text', text: plan.text },
                    {
                      type: 'text',
                      text: `用户附件 ${JSON.stringify(file.name)}，已上传至本机 ${JSON.stringify(uploadedPath)}。按需要用工具读取该文件。`,
                    },
                  ],
                },
              ],
            },
          ],
        };
        return threadId;
      },
      (record, id) => session.verifyDesktopCreation(record, id),
    );
    broker.prepare(input('unicode-confirm-op', { projectId: null, attachments: [file] }), {
      ...target,
      projectId: null,
      directory: null,
    });
    broker.upload('unicode-confirm-op', file.id, bytes);
    assert.equal((await broker.submit('unicode-confirm-op')).phase, 'confirmed');
    assert.equal(session.state.attachments[0].path, uploadedPath);
    assert.equal(session.state.attachments[0].name, file.name);
    assert.equal(session.state.threads[0].messages[0].attachments[0].name, file.name);
    await writeFile(uploadedPath, 'changed');
    assert.equal(
      await session.verifyDesktopCreation(await broker.inspect('unicode-confirm-op'), threadId),
      false,
    );
  });
  await test('desktop UI operations serialize across projects', async () => {
    let active = 0,
      peak = 0;
    const broker = make('serial', async (plan) => {
      active++;
      peak = Math.max(peak, active);
      await new Promise((r) => setTimeout(r, 20));
      active--;
      return 'native-' + plan.clientOperationId;
    });
    broker.prepare(input('serial-project-a'), target);
    broker.prepare(input('serial-project-b', { projectId: 'project-b' }), {
      ...target,
      projectId: 'project-b',
    });
    await Promise.all([broker.submit('serial-project-a'), broker.submit('serial-project-b')]);
    assert.equal(peak, 1);
  });
  await test('timeout is durable and never resubmits or opens another draft', async () => {
    let called = 0;
    let broker = make('unknown', async () => {
      called++;
      throw Error('Disconnected after Send');
    });
    broker.prepare(input('unknown-operation'), target);
    broker.prepare(input('other-operation'), target);
    assert.equal((await broker.submit('unknown-operation')).phase, 'outcome-unknown');
    await broker.submit('unknown-operation');
    assert.equal(called, 1);
    await assert.rejects(broker.submit('other-operation'), /前一次/);
    const refused = await broker.inspect('other-operation');
    assert.equal(refused.phase, 'rejected');
    assert.match(refused.error, /前一次/);
    broker.close();
    broker = make('unknown', async () => {
      called++;
      return 'must-not-create';
    });
    assert.equal((await broker.submit('unknown-operation')).phase, 'outcome-unknown');
    assert.equal(called, 1);
  });
  await test('background acceptance checks blockers and preserves unknown independent targets', async () => {
    let writes = 0;
    const blocked = make('background-blocked', async () => {
      writes++;
      throw Error('lost after Send');
    });
    blocked.prepare(input('background-unknown'), target);
    await blocked.submit('background-unknown');
    blocked.prepare(input('background-refused'), target);
    await assert.rejects(blocked.submit('background-refused', true), /前一次/);
    assert.equal((await blocked.inspect('background-refused')).phase, 'rejected');
    assert.equal(writes, 1);
    blocked.prepare(input('runtime-background-refused'), target);
    const session = {
      requireReady() {},
      desktopCreationDriver: { backgroundSubmission: true, async refresh() {} },
      desktopCreation: blocked,
      creationView: (record) => record,
    };
    await assert.rejects(
      RuntimeSession.prototype.submitDesktopCreation.call(session, 'runtime-background-refused'),
      /前一次/,
    );
    assert.equal((await blocked.inspect('runtime-background-refused')).phase, 'rejected');
    assert.equal(writes, 1, 'The runtime background entry must not swallow a known blocker');
    const calls = [];
    const independent = new DesktopCreationBroker(
      join(root, 'independent-background'),
      'host-a',
      {
        available: true,
        reason: null,
        independentTargets: true,
        async create(plan) {
          calls.push(plan.clientOperationId);
          if (plan.clientOperationId === 'independent-unknown') throw Error('lost after Send');
          return 'independent-native-thread';
        },
      },
      async () => true,
    );
    brokers.add(independent);
    independent.prepare(input('independent-unknown'), target);
    await independent.submit('independent-unknown');
    const original = await independent.inspect('independent-unknown');
    independent.prepare(input('independent-next'), target);
    await independent.submit('independent-next', true);
    await independent.submit('independent-next');
    assert.equal((await independent.inspect('independent-next')).phase, 'confirmed');
    assert.deepEqual(await independent.inspect('independent-unknown'), original);
    await independent.submit('independent-unknown', true);
    await independent.submit('independent-next', true);
    assert.deepEqual(calls, ['independent-unknown', 'independent-next']);
  });
  await test('background first input consumes only original not-written proof and never replays a written unknown', async () => {
    const directory = join(root, 'background-first-input');
    const stateDir = join(directory, 'state');
    const cwd = join(directory, 'workspace');
    await mkdir(stateDir, { recursive: true, mode: 0o700 });
    await mkdir(cwd, { mode: 0o700 });
    const hostId = randomUUID(),
      projectId = randomUUID(),
      threadId = randomUUID(),
      operationId = randomUUID();
    const metadata = { id: threadId, projectId, cwd, turns: [] };
    const owner = {
      ...metadata,
      hostId: 'local',
      workspaceKind: 'project',
      latestModel: 'fixture-model',
      latestThreadSettings: { model: 'fixture-model', effort: null },
      threadRuntimeStatus: { type: 'idle' },
    };
    const stateFile = join(stateDir, 'runtime.json');
    await writeFile(
      stateFile,
      JSON.stringify({
        hostId,
        projects: [{ id: projectId, hostId, name: 'Fixture', roots: [cwd] }],
        threads: [],
        attachments: [],
        receipts: [],
        creations: {},
      }),
      { mode: 0o600 },
    );
    protectWindowsStateFile(stateFile);
    const config = join(directory, 'bridge.json');
    await writeFile(config, JSON.stringify({ hostId }), { mode: 0o600 });
    protectWindowsStateFile(config);
    const executable = join(directory, 'metadata-fixture.cjs');
    const methodsFile = join(directory, 'metadata-methods.log');
    await writeFile(
      executable,
      `const {createInterface}=require('node:readline');
const {appendFileSync}=require('node:fs');
createInterface({input:process.stdin}).on('line',line=>{const q=JSON.parse(line);appendFileSync(${JSON.stringify(methodsFile)},q.method+'\\n',{mode:0o600});if(q.id===undefined)return;const result=['thread/start','thread/read'].includes(q.method)?{thread:${JSON.stringify(metadata)}}:{};process.stdout.write(JSON.stringify({id:q.id,result})+'\\n');});
process.stdin.on('end',()=>process.exit(0));\n`,
      { mode: 0o700 },
    );
    const originalSpawn = childProcess.spawn;
    childProcess.spawn = (command, args, options) => {
      if (command !== executable) return originalSpawn(command, args, options);
      return originalSpawn(process.execPath, [executable, ...args], options);
    };
    syncBuiltinESMExports();
    let session;
    let releaseWire;
    try {
      session = new RuntimeSession({
        directory: stateDir,
        root: cwd,
        name: 'Background first-input fixture',
        mode: 'desktop-runtime',
        desktopPreparedBridge: config,
        executable,
        socket: join(directory, 'fixture.sock'),
      });
      const runtime = session.runtime,
        ipc = runtime.ipc;
      runtime.desktopAvailable = async () => true;
      ipc.ready = true;
      ipc.clientId = 'fixture-follower';
      ipc.follow = async () => owner;
      ipc.owners.set(threadId, 'fixture-owner');
      ipc.states.set(threadId, { revision: 1, state: owner });
      let rejectGeneration = true;
      runtime.reader = {
        ready: true,
        close() {},
        registerOwnerThread() {},
        async read(method) {
          if (method === 'thread/read') return { thread: metadata };
          if (method === 'model/list') {
            if (rejectGeneration) throw Error('Synthetic generation preflight failure');
            return {
              data: [
                {
                  model: 'fixture-model',
                  isDefault: true,
                  supportedReasoningEfforts: [{ reasoningEffort: 'xhigh' }],
                },
              ],
            };
          }
          if (method === 'config/read')
            return { config: { sandbox_mode: 'workspace-write', approval_policy: 'on-request' } };
          throw Error('Unexpected synthetic metadata read: ' + method);
        },
      };
      const wires = [];
      const written = Promise.withResolvers();
      const prepFile = join(stateDir, 'desktop-prepared-bridge', operationId + '.json');
      const preparation = () => JSON.parse(readFileSync(prepFile, 'utf8'));
      ipc.socket = {
        destroyed: false,
        destroy() {
          this.destroyed = true;
        },
        write(frame) {
          const request = JSON.parse(frame.subarray(4).toString());
          assert.equal(request.method, 'thread-follower-start-turn');
          const prep = preparation();
          const receipt = JSON.parse(readFileSync(stateFile, 'utf8')).receipts[0];
          assert.equal(prep.phase, 'input-submitting');
          assert.equal(prep.inputBoundary.state, 'may-have-written');
          assert.equal(receipt.creationInput.state, 'may-have-written');
          assert.equal(receipt.fingerprint, prep.inputBoundary.checkpoint.replyFingerprint);
          assert.equal(receipt.state, 'submitting');
          wires.push(request);
          releaseWire = () =>
            ipc.receive({
              type: 'response',
              requestId: request.requestId,
              method: request.method,
              resultType: 'error',
              handledByClientId: 'fixture-owner',
              error: 'Synthetic uncertainty after the actual write fence',
            });
          written.resolve();
        },
      };
      const broker = session.desktopCreation;
      await session.desktopCreationDriver.refresh();
      broker.prepare(
        {
          hostId,
          projectId,
          clientOperationId: operationId,
          text: 'Immutable original first input',
          effort: 'xhigh',
          attachments: [],
        },
        { hostId, projectId, directory: cwd, existingThreadIds: [] },
      );
      await broker.submit(operationId, true);
      assert.equal((await broker.submit(operationId)).phase, 'outcome-unknown');
      const originalProof = preparation();
      const starts = () =>
        readFileSync(methodsFile, 'utf8')
          .split('\n')
          .filter((method) => method === 'thread/start').length;
      assert.equal(starts(), 1);
      assert.equal(wires.length, 0);
      assert.equal(session.state.receipts.length, 0);
      rejectGeneration = false;
      await Promise.all([broker.query(operationId), broker.inspect(operationId)]);
      await broker.refreshNotificationOperations();
      assert.equal(wires.length, 0, 'Reads cannot continue the failed original preflight');
      assert.deepEqual(preparation(), originalProof);
      if (process.platform === 'win32') {
        assert.equal(originalProof.phase, 'input-preparing');
        assert.equal(originalProof.diagnostic.disposition, 'observe-only');
        await broker.submit(operationId, true);
        assert.equal(
          wires.length,
          0,
          'Windows cannot authorize a retry without directory durability',
        );
        return;
      }
      assert.equal(originalProof.phase, 'input-retryable');
      assert.equal(originalProof.inputBoundary.state, 'not-written');
      assert.equal(originalProof.inputBoundary.checkpoint.writerPid, process.pid);
      assert.equal(originalProof.inputBoundary.checkpoint.clientMessageId, 'first-' + operationId);
      assert.equal(originalProof.diagnostic.disposition, 'retry-original-preflight');
      await Promise.all([broker.submit(operationId, true), broker.submit(operationId, true)]);
      const timeout = setTimeout(
        () => written.reject(Error('Background continuation did not write')),
        5000,
      );
      try {
        await written.promise;
      } finally {
        clearTimeout(timeout);
      }
      await broker.submit(operationId, true);
      assert.equal(
        wires.length,
        1,
        'Concurrent background submissions share the original in-flight operation',
      );
      assert.equal(wires[0].params.turnStart.request.clientUserMessageId, 'first-' + operationId);
      assert.equal(preparation().fingerprint, originalProof.fingerprint);
      releaseWire();
      assert.equal((await broker.submit(operationId)).phase, 'outcome-unknown');
      assert.equal(preparation().phase, 'input-submitting');
      assert.equal(preparation().diagnostic.disposition, 'observe-only');
      assert.equal(session.state.receipts.length, 1);
      await broker.query(operationId);
      await broker.inspect(operationId);
      await broker.refreshNotificationOperations();
      await Promise.all([broker.submit(operationId, true), broker.submit(operationId, true)]);
      assert.equal(
        wires.length,
        1,
        'A possibly-written unknown remains query-only even with background=true',
      );
      assert.equal(starts(), 1, 'Explicit continuation must not prepare another native thread');
    } finally {
      try {
        releaseWire?.();
        session?.close();
      } finally {
        childProcess.spawn = originalSpawn;
        syncBuiltinESMExports();
      }
    }
  });
  await test('ending an uncertain wait preserves its journal and requires explicit acknowledgement', async () => {
    let called = 0;
    const broker = make('detach', async (plan) => {
      called++;
      if (plan.clientOperationId === 'detached-operation') throw Error('lost response');
      return 'native-detach-followup';
    });
    broker.prepare(input('detached-operation'), target);
    broker.prepare(input('new-distinct-operation'), target);
    assert.equal((await broker.submit('detached-operation')).phase, 'outcome-unknown');
    assert.throws(() => broker.detach('detached-operation', false), /确认/);
    const detached = broker.detach('detached-operation', true);
    assert.equal(detached.phase, 'outcome-unknown');
    assert.equal(detached.detached, true);
    assert.equal(detached.text, 'hello');
    const session = Object.assign(Object.create(RuntimeSession.prototype), {
      state: { hostId: 'host-a', threads: [] },
      desktopCreation: broker,
    });
    const publicReceipt = session.detachDesktopCreation('detached-operation', true);
    assert.equal(
      publicReceipt.detached,
      true,
      'Native client must receive the detach acknowledgement',
    );
    assert.equal(publicReceipt.hostId, 'host-a');
    assert.equal(publicReceipt.clientOperationId, 'detached-operation');
    assert.equal(publicReceipt.phase, 'outcome-unknown');
    assert.equal(publicReceipt.threadId, null);
    for (const field of ['text', 'directory', 'receiptPath', 'fingerprint'])
      assert(
        !Object.hasOwn(publicReceipt, field),
        'Private operation details must remain on the Host',
      );
    assert.equal((await broker.submit('detached-operation')).phase, 'outcome-unknown');
    assert.equal(called, 1);
    assert.equal((await broker.submit('new-distinct-operation')).phase, 'confirmed');
    assert.equal(called, 2);
    const query = await broker.query('detached-operation');
    assert.equal(query.clientOperationId, 'detached-operation');
    assert.equal(query.phase, 'outcome-unknown');
    assert.equal(query.detached, true);
    broker.close();
    const restored = make('detach', async () => {
      throw Error('must never replay');
    });
    assert.equal((await restored.submit('detached-operation')).detached, true);
  });
  await test('ending a prepared wait refuses that original operation without executing it', async () => {
    let called = 0;
    const broker = make('detach-prepared', async () => {
      called++;
      return 'should-not-create';
    });
    broker.prepare(input('prepared-detach'), target);
    assert.equal(broker.detach('prepared-detach', false).phase, 'rejected');
    assert.equal((await broker.submit('prepared-detach')).phase, 'rejected');
    assert.equal(called, 0);
  });
  await test('copied ID requires independent proof and query only reconciles', async () => {
    let called = 0,
      accepted = false;
    const broker = make(
      'proof',
      async () => {
        called++;
        return 'native-thread-0004';
      },
      async (_record, id) => {
        assert.equal(id, 'native-thread-0004');
        return accepted;
      },
    );
    broker.prepare(input('proof-operation'), target);
    assert.equal((await broker.submit('proof-operation')).phase, 'outcome-unknown');
    accepted = true;
    assert.equal((await broker.query('proof-operation')).phase, 'confirmed');
    assert.equal(called, 1);
  });
  await test('definite refusal before Send differs from uncertain submission', async () => {
    let called = 0;
    const broker = make('refusal', async () => {
      called++;
      throw new BeforeDesktopSubmit('Wrong workspace');
    });
    broker.prepare(input('refused-operation'), target);
    assert.equal((await broker.submit('refused-operation')).phase, 'rejected');
    assert.equal((await broker.submit('refused-operation')).phase, 'rejected');
    assert.equal(called, 1);
  });
  await test('only one live writer owns a creation journal', async () => {
    const broker = make('lease', async () => 'native-thread-0005');
    assert.throws(() => make('lease', async () => 'wrong-second-writer'), /active writer/);
    broker.close();
    make('lease', async () => 'native-thread-0006');
  });
  await test('crash before confirmation retains exact native ID for reconciliation', async () => {
    let broker = make(
      'confirm-restart',
      async () => 'native-thread-0007',
      async () => false,
    );
    broker.prepare(input('confirm-restart-01'), target);
    await broker.submit('confirm-restart-01');
    broker.close();
    broker = make('confirm-restart', async () => {
      throw Error('Must never replay');
    });
    const confirmed = await broker.query('confirm-restart-01');
    assert.equal(confirmed.phase, 'confirmed');
    assert.equal(confirmed.threadId, 'native-thread-0007');
  });
  await test('native receipt recovers the copied ID after losing the helper response', async () => {
    let called = 0;
    const broker = make('native-receipt', async (plan) => {
      called++;
      await writeFile(
        plan.receiptPath,
        JSON.stringify({
          phase: 'submitted',
          hostId: plan.hostId,
          clientOperationId: plan.clientOperationId,
          threadId: 'native-receipt-thread',
        }),
        { mode: 0o600 },
      );
      protectWindowsStateFile(plan.receiptPath);
      throw Error('Parent transport disconnected');
    });
    broker.prepare(input('receipt-operation'), target);
    assert.equal((await broker.submit('receipt-operation')).phase, 'outcome-unknown');
    const result = await broker.query('receipt-operation');
    assert.equal(result.phase, 'confirmed');
    assert.equal(result.threadId, 'native-receipt-thread');
    assert.equal(called, 1);
  });
  await test('SIGKILL after durable submitting never executes again on restart', async () => {
    const code = `
      const { DesktopCreationBroker } = await import(process.argv[1]);
      const broker = new DesktopCreationBroker(process.argv[2], 'host-a', {
        available: true, reason: null, create: async () => {
          process.send('submitted'); await new Promise(() => {});
        }
      }, async () => true);
      broker.prepare({hostId:'host-a', clientOperationId:'killed-operation', projectId:null, text:'fixture', attachments:[]}, {hostId:'host-a',projectId:null,directory:null});
      await broker.submit('killed-operation');
    `;
    const child = spawn(
      process.execPath,
      [
        '--input-type=module',
        '-e',
        code,
        new URL('../packages/codex-adapter/dist/desktop-creation.js', import.meta.url).href,
        join(root, 'killed'),
      ],
      { stdio: ['ignore', 'ignore', 'ignore', 'ipc'] },
    );
    const closed = new Promise((done) => child.once('close', done));
    try {
      await new Promise((done, reject) => {
        const timer = setTimeout(() => reject(Error('Crash fixture did not submit')), 5000);
        child.once('message', () => {
          clearTimeout(timer);
          done();
        });
        child.once('error', reject);
      });
      child.kill('SIGKILL');
      await closed;
      const broker = make('killed', async () => {
        throw Error('Must not replay a killed submission');
      });
      assert.equal((await broker.submit('killed-operation')).phase, 'outcome-unknown');
    } finally {
      child.kill('SIGKILL');
      await closed;
    }
  });
  console.log(
    JSON.stringify({
      suite: 'desktop-creation',
      passed,
      failed: 0,
      realDesktopCreationVerified: false,
    }),
  );
} finally {
  for (const broker of brokers) broker.close();
  await rm(root, { recursive: true, force: true });
}
