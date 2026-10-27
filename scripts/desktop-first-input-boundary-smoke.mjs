import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm } from 'node:fs/promises';
import { readFileSync, fsyncSync, fstatSync } from 'node:fs';
import { randomUUID, createHash } from 'node:crypto';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { BeforeDesktopSubmit } from '../packages/codex-adapter/dist/desktop-creation.js';

// Isolated original-path boundary fixtures; no desktop, model, production state or network.
const base = await mkdtemp(join(tmpdir(), 'codex-first-input-boundary-'));
const sessions = new Set();
const hash = (x) => createHash('sha256').update(x).digest('hex');
const gate = () => {
  let resolve;
  const promise = new Promise((done) => {
    resolve = done;
  });
  return { promise, resolve };
};
let checks = 0;
async function test(name, run) {
  await run();
  checks++;
  console.log(
    JSON.stringify({ test: name, status: 'passed', scope: 'isolated-first-input-boundary' }),
  );
}

async function fixture(name, attachments = []) {
  const dir = join(base, name),
    stateDir = join(dir, 'state'),
    cwd = join(dir, 'workspace');
  await mkdir(stateDir, { recursive: true, mode: 0o700 });
  await mkdir(cwd, { mode: 0o700 });
  const hostId = randomUUID(),
    projectId = randomUUID(),
    threadId = randomUUID();
  const metadata = { id: threadId, projectId, cwd, turns: [] };
  const native = {
    ...metadata,
    hostId: 'local',
    workspaceKind: 'project',
    latestModel: 'fixture-model',
    latestThreadSettings: { model: 'fixture-model', effort: null },
    threadRuntimeStatus: { type: 'idle' },
  };
  await writeFile(
    join(stateDir, 'runtime.json'),
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
  const config = join(dir, 'bridge.json');
  await writeFile(config, JSON.stringify({ hostId }), { mode: 0o600 });
  const cli = join(dir, 'metadata-fixture.mjs');
  await writeFile(
    cli,
    `#!${process.execPath}\nimport {createInterface} from 'node:readline';\ncreateInterface({input:process.stdin}).on('line',line=>{const q=JSON.parse(line);if(q.id===undefined)return;const result=q.method==='thread/start'||q.method==='thread/read'?{thread:${JSON.stringify(metadata)}}:{};process.stdout.write(JSON.stringify({id:q.id,result})+'\\n');});\nprocess.stdin.on('end',()=>process.exit(0));\n`,
    { mode: 0o700 },
  );
  const session = new RuntimeSession({
    directory: stateDir,
    root: cwd,
    name: 'Fixture',
    mode: 'desktop-runtime',
    desktopPreparedBridge: config,
    executable: cli,
    socket: join(dir, 'ipc', 'fixture.sock'),
  });
  sessions.add(session);
  const runtime = session.runtime,
    ipc = runtime.ipc;
  runtime.desktopAvailable = async () => true;
  ipc.ready = true;
  ipc.clientId = 'fixture-follower';
  ipc.follow = async () => native;
  ipc.owners.set(threadId, 'fixture-owner');
  ipc.states.set(threadId, { revision: 1, state: native });
  const controls = {
    rejectModel: false,
    generationGate: null,
    permissionGate: null,
    ownerGate: null,
    rejectOwner: false,
    uncertainWire: false,
    wires: [],
    atWire: null,
  };
  runtime.reader = {
    ready: true,
    close() {},
    registerOwnerThread() {},
    registerCatalogThread() {},
    async read(method) {
      if (method === 'thread/read') return { thread: metadata };
      if (method === 'model/list') {
        if (controls.generationGate) {
          controls.generationGate.entered.resolve();
          await controls.generationGate.release.promise;
        }
        if (controls.rejectModel) throw new Error('secret-private-generation-error-body');
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
      if (method === 'config/read') {
        if (controls.permissionGate) {
          controls.permissionGate.entered.resolve();
          await controls.permissionGate.release.promise;
        }
        return { config: { sandbox_mode: 'workspace-write', approval_policy: 'on-request' } };
      }
      throw Error('Unexpected fixture read ' + method);
    },
  };
  const originalFollow = ipc.follow;
  ipc.follow = async (...args) => {
    if (controls.ownerGate) {
      controls.ownerGate.entered.resolve();
      await controls.ownerGate.release.promise;
    }
    if (controls.rejectOwner) return undefined;
    return originalFollow(...args);
  };
  // Exercise the actual command -> request -> write fence, substituting only the socket.
  ipc.socket = {
    destroyed: false,
    destroy() {
      this.destroyed = true;
    },
    write(frame) {
      const q = JSON.parse(frame.subarray(4).toString());
      assert.equal(q.method, 'thread-follower-start-turn');
      controls.atWire?.(q);
      controls.wires.push(q);
      queueMicrotask(() =>
        ipc.receive({
          type: 'response',
          requestId: q.requestId,
          method: q.method,
          resultType: controls.uncertainWire ? 'error' : 'success',
          handledByClientId: 'fixture-owner',
          error: 'fixture delivery rejected after write',
          result: { result: { turn: { id: 'fixture-turn', status: 'inProgress' } } },
        }),
      );
    },
  };
  const broker = session.desktopCreation,
    bridge = session.desktopCreationDriver;
  const operationId = randomUUID();
  const input = {
    hostId,
    projectId,
    clientOperationId: operationId,
    text: 'immutable first body',
    effort: 'xhigh',
    attachments,
  };
  await bridge.refresh();
  broker.prepare(input, { hostId, projectId, directory: cwd, existingThreadIds: [] });
  const prepPath = join(stateDir, 'desktop-prepared-bridge', operationId + '.json');
  const prep = () => JSON.parse(readFileSync(prepPath));
  const plan = () => broker.plan(broker.records[operationId]);
  return {
    dir,
    stateDir,
    session,
    runtime,
    ipc,
    broker,
    bridge,
    controls,
    input,
    operationId,
    threadId,
    prepPath,
    prep,
    plan,
  };
}

try {
  await test('generation failure preserves original phase/diagnostic and same-ID continuation writes once', async () => {
    const f = await fixture('generation');
    f.controls.rejectModel = true;
    const failed = await f.broker.submit(f.operationId);
    assert.equal(failed.phase, 'outcome-unknown');
    assert.equal(f.prep().phase, 'input-retryable');
    assert.equal(f.prep().diagnostic.code, 'generation-preflight-failed');
    assert(!JSON.stringify(failed).includes('secret-private'));
    const originalFingerprint = f.prep().fingerprint;
    assert.equal(f.controls.wires.length, 0);
    assert.equal(f.session.state.receipts.length, 0);
    let observed = 0;
    f.controls.atWire = () => {
      observed++;
      const p = f.prep(),
        ledger = JSON.parse(readFileSync(join(f.stateDir, 'runtime.json')));
      assert.equal(p.phase, 'input-submitting');
      assert.equal(p.inputBoundary.state, 'may-have-written');
      assert.equal(p.fingerprint, originalFingerprint);
      const r = ledger.receipts.find((r) => r.clientMessageId === 'first-' + f.operationId);
      assert.equal(r.state, 'submitting');
      assert.equal(r.creationInput.state, 'may-have-written');
      assert.equal(r.fingerprint, p.inputBoundary.checkpoint.replyFingerprint);
      assert.deepEqual(r.target, p.inputBoundary.checkpoint.target);
    };
    f.controls.rejectModel = false;
    await Promise.all([f.broker.query(f.operationId), f.broker.query(f.operationId)]);
    await f.broker.inspect(f.operationId);
    await f.broker.refreshNotificationOperations();
    assert.equal(
      f.controls.wires.length,
      0,
      'Polling and notification reads cannot continue a failed preflight',
    );
    assert.equal(f.prep().phase, 'input-retryable');
    await Promise.all([f.broker.submit(f.operationId), f.broker.submit(f.operationId)]);
    assert.equal(f.controls.wires.length, 1);
    assert.equal(observed, 1);
    assert.equal(f.prep().phase, 'submitted');
    assert.equal(f.session.state.receipts.length, 1);
    assert.equal(
      f.controls.wires[0].params.turnStart.request.clientUserMessageId,
      'first-' + f.operationId,
    );
  });
  await test('attachment failure before write remains recoverable without replacing thread or input', async () => {
    const bytes = Buffer.from('fixture attachment'),
      id = randomUUID();
    const file = {
      id,
      name: 'input.txt',
      mediaType: 'text/plain',
      sizeBytes: bytes.length,
      sha256: hash(bytes),
    };
    const f = await fixture('attachments', [file]);
    const path = join(f.stateDir, 'desktop-creations', f.operationId, id, file.name);
    f.broker.upload(f.operationId, id, bytes);
    const original = f.session.open.bind(f.session);
    f.session.open = async (...args) => {
      const v = await original(...args);
      await writeFile(path, 'changed');
      return v;
    };
    await f.broker.submit(f.operationId);
    assert.equal(f.controls.wires.length, 0);
    assert.equal(f.prep().diagnostic.code, 'attachments-preflight-failed');
    assert.equal(f.prep().phase, 'input-retryable');
    f.session.open = original;
    await writeFile(path, bytes);
    await f.broker.query(f.operationId);
    assert.equal(f.controls.wires.length, 0);
    await f.broker.submit(f.operationId);
    assert.equal(f.controls.wires.length, 1);
    assert.equal(f.prep().threadId, f.threadId);
  });
  await test('final owner discovery failure reuses only its proven not-written receipt', async () => {
    const f = await fixture('owner');
    const permissions = f.runtime.defaultPermissions.bind(f.runtime),
      open = f.bridge.open.bind(f.bridge);
    f.runtime.defaultPermissions = async (...args) => {
      f.controls.rejectOwner = true;
      f.bridge.open = async () => false; // No real URI navigation in the fixture.
      return permissions(...args);
    };
    await f.broker.submit(f.operationId);
    assert.equal(f.controls.wires.length, 0);
    assert.equal(f.prep().phase, 'input-retryable');
    assert.equal(f.prep().diagnostic.code, 'owner-preflight-failed');
    const r = f.session.state.receipts[0];
    assert.equal(r.state, 'failed');
    assert.equal(r.creationInput.state, 'not-written');
    f.controls.rejectOwner = false;
    f.bridge.open = open;
    f.runtime.defaultPermissions = permissions;
    await f.broker.query(f.operationId);
    assert.equal(f.controls.wires.length, 0);
    await f.broker.submit(f.operationId);
    assert.equal(f.controls.wires.length, 1);
    assert.equal(f.session.state.receipts.length, 1);
    assert.equal(f.session.state.receipts[0], r);
  });
  await test('mutable caller and same-ID conflict during delayed generation do not duplicate first input', async () => {
    const f = await fixture('coalesce');
    const delayed = { entered: gate(), release: gate() };
    f.controls.generationGate = delayed;
    const original = f.plan();
    const first = f.bridge.create(original);
    await delayed.entered.promise;
    original.text = 'mutated caller';
    await assert.rejects(f.bridge.reconcile(original), /不同/);
    const same = f.bridge.reconcile(f.plan());
    delayed.release.resolve();
    await Promise.all([first, same]);
    assert.equal(f.controls.wires.length, 1);
    assert.equal(
      f.controls.wires[0].params.turnStart.request.input[0].text,
      'immutable first body',
    );
  });
  for (const action of ['cancel', 'close'])
    await test(
      action + ' after asynchronous permission read produces zero execution writes',
      async () => {
        const f = await fixture(action);
        const delayed = { entered: gate(), release: gate() };
        f.controls.permissionGate = delayed;
        const work = f.broker.submit(f.operationId);
        const settled = work.catch(() => {});
        await delayed.entered.promise;
        assert.equal(f.prep().phase, 'input-preparing');
        if (action === 'cancel') f.broker.detach(f.operationId, true);
        else f.session.close();
        delayed.release.resolve();
        await settled;
        assert.equal(f.controls.wires.length, 0);
        if (action === 'cancel') {
          await f.broker.query(f.operationId);
          assert.equal(f.controls.wires.length, 0);
        }
      },
    );
  await test('machine runtime changed after generation await cannot create a pre-wire recovery proof', async () => {
    const f = await fixture('machine-runtime');
    const delayed = { entered: gate(), release: gate() };
    f.controls.generationGate = delayed;
    const work = f.broker.submit(f.operationId);
    await delayed.entered.promise;
    const readingTarget = f.session.readingTarget.bind(f.session),
      original = readingTarget();
    f.session.readingTarget = () => ({
      ...original,
      runtimeId: 'different-runtime',
    });
    delayed.release.resolve();
    await work;
    assert.equal(f.controls.wires.length, 0);
    assert.equal(f.prep().phase, 'input-preparing');
    assert.equal(f.prep().diagnostic.disposition, 'observe-only');
    f.session.readingTarget = readingTarget;
    await f.broker.query(f.operationId);
    assert.equal(f.controls.wires.length, 0);
  });
  await test('Codex account metadata changed after generation await preserves one first input', async () => {
    const f = await fixture('same-machine-account');
    const delayed = { entered: gate(), release: gate() };
    f.controls.generationGate = delayed;
    const work = f.broker.submit(f.operationId);
    await delayed.entered.promise;
    const originalTarget = f.session.readingTarget.bind(f.session);
    f.session.readingTarget = () => ({
      ...originalTarget(),
      account: { authority: 'legacy-fixture', subject: 'changed-account' },
    });
    delayed.release.resolve();
    await work;
    assert.equal(f.controls.wires.length, 1);
    assert.equal(f.prep().phase, 'submitted');
    assert.equal(f.session.state.receipts.length, 1);
    assert.equal(
      f.controls.wires[0].params.turnStart.request.clientUserMessageId,
      'first-' + f.operationId,
    );
    f.session.readingTarget = originalTarget;
    await f.broker.query(f.operationId);
    assert.equal(f.controls.wires.length, 1, 'Confirmed first input is never replayed');
  });
  await test('preflight recovery cannot change the captured immutable target metadata', async () => {
    const f = await fixture('recovery-account');
    f.controls.rejectModel = true;
    await f.broker.submit(f.operationId);
    assert.equal(f.prep().phase, 'input-retryable');
    const target = f.session.readingTarget.bind(f.session),
      old = target();
    f.session.readingTarget = () => ({
      ...old,
      account: { authority: 'legacy-fixture', subject: 'different-captured-metadata' },
    });
    f.controls.rejectModel = false;
    await f.broker.query(f.operationId);
    assert.equal(f.controls.wires.length, 0);
    assert.equal(f.prep().phase, 'input-retryable');
    await f.broker.submit(f.operationId);
    assert.equal(f.controls.wires.length, 0);
    assert.equal(f.prep().phase, 'input-preparing');
    assert.equal(f.prep().diagnostic.disposition, 'observe-only');
    f.session.readingTarget = target;
  });
  for (const kind of ['file', 'directory'])
    await test(
      kind + ' fsync failure at final write checkpoint produces zero IPC and no retry proof',
      async () => {
        const f = await fixture('fsync-' + kind);
        f.session.persistence.options.fsync = (fd) => {
          if (
            f.session.state.receipts.some((r) => r.creationInput?.state === 'may-have-written') &&
            fstatSync(fd).isDirectory() === (kind === 'directory')
          )
            throw new Error('private-fsync-fixture');
          return fsyncSync(fd);
        };
        await f.broker.submit(f.operationId);
        assert.equal(f.controls.wires.length, 0);
        assert.equal(f.prep().phase, 'input-submitting');
        assert.equal(f.prep().inputBoundary.state, 'may-have-written');
        await f.broker.query(f.operationId);
        assert.equal(f.controls.wires.length, 0);
        delete f.session.persistence.options.fsync;
      },
    );
  await test('legacy unknown/input-submitting cannot become rejected or replayed from new BeforeDesktopSubmit', async () => {
    const f = await fixture('legacy');
    f.controls.rejectModel = true;
    await f.broker.submit(f.operationId);
    const p = f.prep();
    p.phase = 'input-submitting';
    delete p.inputBoundary;
    delete p.diagnostic;
    await writeFile(f.prepPath, JSON.stringify(p), { mode: 0o600 });
    f.controls.rejectModel = false;
    const before = await readFile(f.prepPath);
    await f.broker.query(f.operationId);
    await f.broker.submit(f.operationId);
    assert.equal(f.controls.wires.length, 0);
    assert.deepEqual(await readFile(f.prepPath), before);
    f.bridge.reconcile = async () => {
      throw new BeforeDesktopSubmit('later probe is not original proof');
    };
    assert.equal((await f.broker.query(f.operationId)).phase, 'outcome-unknown');
  });
  await test('post-write rejected/unknown input is query-only and notification observation cannot execute', async () => {
    const f = await fixture('uncertain');
    f.controls.uncertainWire = true;
    await f.broker.submit(f.operationId);
    assert.equal(f.controls.wires.length, 1);
    assert.equal(f.prep().phase, 'input-submitting');
    await f.broker.query(f.operationId);
    await f.broker.refreshNotificationOperations();
    await f.broker.query(f.operationId);
    await f.broker.submit(f.operationId);
    assert.equal(f.controls.wires.length, 1);
    const n = await fixture('observer');
    n.controls.rejectModel = true;
    await n.broker.submit(n.operationId);
    n.controls.rejectModel = false;
    await n.broker.refreshNotificationOperations();
    assert.equal(n.controls.wires.length, 0);
    assert.equal(n.prep().phase, 'input-retryable');
  });
  console.log(
    JSON.stringify({
      suite: 'desktop-first-input-boundary',
      checks,
      result: 'passed',
      liveModelInputs: 0,
      productionStateWrites: 0,
    }),
  );
} finally {
  for (const session of sessions) {
    try {
      session.close();
    } catch {
      // Fault-injection fixtures may also reject their final cleanup save.
    }
  }
  await rm(base, { recursive: true, force: true });
}
