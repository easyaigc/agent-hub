import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, rm, realpath, cp, appendFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import {
  DesktopControllerBridge,
  controllerAdapterDigest,
} from '../packages/codex-adapter/dist/desktop-controller.js';
import { DesktopCommandNotSubmitted } from '../packages/codex-adapter/dist/desktop-ipc.js';

import {
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '../packages/codex-adapter/dist/desktop-platform.js';

// Fixture checks for the controller boundary. These never count as original desktop acceptance.
const directory = await realpath(await mkdtemp(join(tmpdir(), 'codex-controller-')));
protectWindowsStateDirectory(directory);
const writePrivate = async (...args) => {
  await writeFile(...args);
  protectWindowsStateFile(args[0]);
};
const controllerId = '11111111-1111-1111-1111-111111111111';
const childId = '22222222-2222-2222-2222-222222222222';
const config = join(directory, 'controller.json');
await writePrivate(
  config,
  JSON.stringify({
    hostId: 'host',
    threadId: controllerId,
    desktopVersion: 'build',
    validationOnly: true,
  }),
  { mode: 0o600 },
);
const catalog = {
  projects: [
    {
      hostId: 'local',
      projectKind: 'local',
      label: 'Project',
      path: directory,
      projectId: 'actual-native-project',
    },
    {
      hostId: 'local',
      projectKind: 'local',
      label: 'Other',
      path: directory,
      projectId: 'other-native-project',
    },
  ],
};
const tool = (name, args, result) => ({
  type: 'mcpToolCall',
  server: 'codex_app',
  tool: name,
  status: 'completed',
  arguments: args,
  result: { content: [{ type: 'text', text: JSON.stringify(result) }] },
});
const states = new Map([
  [
    controllerId,
    {
      id: controllerId,
      turns: [
        { turnId: 'catalog', status: 'completed', items: [tool('list_projects', {}, catalog)] },
      ],
    },
  ],
]);
let starts = 0,
  navigation = 0,
  firstInput = 0,
  failFirst = false,
  mismatch = false;
const runtime = {
  version: 'build',
  ipc: {
    follow: async (id) => states.get(id),
    state: (id) => states.get(id),
    hasOwner: (id) => states.has(id),
  },
  call: async (method, args) => {
    assert.equal(method, 'turn/start');
    assert.equal(args.threadId, controllerId);
    const id = args.clientUserMessageId.replace('controller-', '');
    const text = args.input[0].text;
    let items;
    if (text.includes('"kind":"create"')) {
      starts++;
      const data = JSON.parse(text.split('数据：\n')[1]);
      items = [
        tool('list_projects', {}, catalog),
        tool(
          'create_thread',
          {
            title: data.title,
            prompt: data.prompt,
            target: data.directory
              ? {
                  type: 'project',
                  projectId: mismatch
                    ? 'wrong-project'
                    : (data.projectId ?? 'actual-native-project'),
                  environment: { type: 'local' },
                }
              : { type: 'projectless', directoryName: 'mobile-' + id.slice(0, 8) },
          },
          { threadId: childId, hostId: 'local' },
        ),
      ];
      states.set(childId, {
        id: childId,
        cwd: directory,
        workspaceKind: data.directory ? 'project' : 'projectless',
        turns: [],
      });
    } else {
      navigation++;
      items = [tool('navigate_to_codex_page', { threadId: childId }, { navigated: true })];
      states.set(childId, { id: childId, turns: [] });
    }
    const turnId = 'turn-' + id;
    states.get(controllerId).turns.push({
      turnId,
      status: 'completed',
      items: [{ type: 'userMessage', clientId: args.clientUserMessageId }, ...items],
    });
    return { turnId };
  },
};
const make = (name) =>
  new DesktopControllerBridge(
    config,
    () => runtime.version,
    'host',
    runtime,
    join(directory, name),
    async (plan, id) => {
      assert.equal(id, childId);
      const receipt = JSON.parse(await readFile(plan.receiptPath, 'utf8'));
      assert.equal(receipt.threadId, childId);
      assert.equal(receipt.clientOperationId, plan.clientOperationId);
      if (failFirst) throw Error('simulated first-input disconnect');
      firstInput++;
    },
  );
const plan = (id, projectless = false) => ({
  hostId: 'host',
  clientOperationId: id,
  projectId: projectless ? null : 'host-project',
  directory: projectless ? null : directory,
  ...(projectless ? {} : { nativeProjectId: 'actual-native-project' }),
  text: 'Actual user text',
  attachments: [],
  files: [],
  receiptPath: join(directory, id, 'native-result.json'),
});
let passed = 0;
const test = async (name, run) => {
  await run();
  passed++;
  console.log(JSON.stringify({ test: name, status: 'passed', scope: 'fixture-boundary' }));
};
try {
  await test('concurrent capability refreshes publish one settled owner result', async () => {
    const bridge = make('atomic-refresh');
    await bridge.refresh();
    const originalFollow = runtime.ipc.follow;
    let release;
    let calls = 0;
    runtime.ipc.follow = async () => {
      calls++;
      return new Promise((resolve) => {
        release = resolve;
      });
    };
    const first = bridge.refresh();
    const second = bridge.refresh();
    assert.equal(calls, 1);
    assert.equal(bridge.available, true);
    assert.equal(bridge.openAvailable, true);
    release(states.get(controllerId));
    await Promise.all([first, second]);
    assert.equal(bridge.available, true);
    runtime.ipc.follow = async () => undefined;
    await bridge.refresh();
    assert.equal(bridge.available, false);
    assert.equal(bridge.openAvailable, false);
    runtime.ipc.follow = originalFollow;
    bridge.close();
  });
  await test('known failure before writing a command is durable and never replayed', async () => {
    const bridge = make('pre-submit-rejection');
    const originalCall = runtime.call;
    let called = 0;
    runtime.call = async () => {
      called++;
      throw new DesktopCommandNotSubmitted('no live owner before write');
    };
    await assert.rejects(bridge.create(plan('pre-submit-operation')), /no live owner/);
    const saved = JSON.parse(
      await readFile(join(directory, 'pre-submit-rejection', 'pre-submit-operation.json'), 'utf8'),
    );
    assert.equal(saved.phase, 'rejected');
    runtime.call = originalCall;
    await assert.rejects(bridge.reconcile(plan('pre-submit-operation')), /no live owner/);
    assert.equal(called, 1);
    bridge.close();
  });
  await test('creation configuration is bound to actual installed build, not an env label', async () => {
    const bridge = make('installed-version');
    await bridge.refresh();
    assert.equal(bridge.available, true);
    runtime.version = 'other-installed-build';
    await bridge.refresh();
    assert.equal(bridge.available, false);
    runtime.version = 'build';
    await bridge.refresh();
    assert.equal(bridge.available, true);
    bridge.close();
  });
  await test('unverified controller cannot advertise production capabilities', async () => {
    const bridge = make('gate');
    await bridge.refresh();
    assert(bridge.available);
    assert(bridge.validationOnly);
    bridge.close();
    await writePrivate(
      config,
      JSON.stringify({ hostId: 'host', threadId: controllerId, desktopVersion: 'build' }),
      { mode: 0o600 },
    );
    const disabled = make('disabled');
    await disabled.refresh();
    assert.equal(disabled.available, false);
    assert.match(disabled.reason, /尚未完成真实验收/);
    disabled.close();
    await writePrivate(
      config,
      JSON.stringify({
        hostId: 'host',
        threadId: controllerId,
        desktopVersion: 'build',
        validationOnly: true,
      }),
      { mode: 0o600 },
    );
  });
  await test('production proof distinguishes stale builds from unloaded controller owners', async () => {
    const proofPath = join(directory, 'verification.json');
    const proof = {
      kind: 'real-desktop-controller',
      hostId: 'host',
      controllerThreadId: controllerId,
      desktopVersion: 'build',
      adapterSha256: controllerAdapterDigest(),
      gates: Object.fromEntries(
        [
          'project',
          'empty-project',
          'projectless',
          'first-attachments',
          'owner-controls',
          'recovery',
        ].map((gate) => [gate, true]),
      ),
      unloadedOpen: true,
    };
    const production = {
      hostId: 'host',
      threadId: controllerId,
      desktopVersion: 'build',
      verification: proofPath,
    };
    await writePrivate(config, JSON.stringify(production), { mode: 0o600 });
    await writePrivate(proofPath, JSON.stringify(proof), { mode: 0o600 });
    const bridge = make('proof');
    await bridge.refresh();
    assert.equal(bridge.available, true);
    await writePrivate(proofPath, JSON.stringify({ ...proof, adapterSha256: 'stale' }));
    await bridge.refresh();
    assert.equal(bridge.available, false);
    assert.match(bridge.reason, /版本与验收记录不一致/);
    await writePrivate(proofPath, JSON.stringify(proof));
    const controllerState = states.get(controllerId);
    states.delete(controllerId);
    await bridge.refresh();
    assert.equal(bridge.available, false);
    assert.match(bridge.reason, /控制聊天未加载/);
    states.set(controllerId, controllerState);
    bridge.close();

    // Replace only an isolated build. The running process retains its verified
    // capabilities; a fresh process must reject the prior build's proof.
    const copy = join(directory, 'adapter');
    await cp(new URL('../packages/codex-adapter/dist/', import.meta.url), copy, {
      recursive: true,
    });
    await cp(
      new URL('../packages/codex-adapter/windows/', import.meta.url),
      join(directory, 'windows'),
      { recursive: true },
    );
    const url = pathToFileURL(join(copy, 'desktop-controller.js')).href;
    const loaded = await import(url);
    const build = loaded.controllerAdapterDigest();
    const isolated = new loaded.DesktopControllerBridge(
      config,
      'build',
      'host',
      runtime,
      join(directory, 'rebuild'),
      async () => {},
    );
    await isolated.refresh();
    assert.equal(isolated.available, true);
    await appendFile(join(copy, 'desktop-ipc.js'), '\n// isolated development rebuild\n');
    assert.equal(loaded.controllerAdapterDigest(), build);
    await isolated.refresh();
    assert.equal(isolated.available, true);
    const fresh = JSON.parse(
      execFileSync(
        process.execPath,
        [
          '--input-type=module',
          '-e',
          `
      const { DesktopControllerBridge, controllerAdapterDigest } = await import(process.argv[1]);
      const bridge = new DesktopControllerBridge(process.argv[2], 'build', 'host', {
        ipc: { follow: async () => ({}), hasOwner: () => true }
      }, process.argv[3], async () => {});
      await bridge.refresh();
      console.log(JSON.stringify({ digest: controllerAdapterDigest(), available: bridge.available, reason: bridge.reason }));
      bridge.close();
    `,
          url,
          config,
          join(directory, 'fresh'),
        ],
        { encoding: 'utf8' },
      ),
    );
    assert.notEqual(fresh.digest, build);
    assert.equal(fresh.available, false);
    assert.match(fresh.reason, /版本与验收记录不一致/);
    isolated.close();
    await writePrivate(config, JSON.stringify({ ...production, validationOnly: true }), {
      mode: 0o600,
    });
  });
  await test('exact native catalog mapping and receipt before first input', async () => {
    const bridge = make('project');
    await bridge.refresh();
    assert.deepEqual(bridge.projectTarget('Project', [directory]), {
      directory,
      nativeProjectId: 'actual-native-project',
    });
    assert.throws(() => bridge.projectTarget('Missing', [directory]), /尚未确认/);
    await Promise.all([bridge.create(plan('project-0001')), bridge.create(plan('project-0001'))]);
    assert.equal(starts, 1);
    assert.equal(firstInput, 1);
    bridge.close();
  });
  await test('projects sharing a directory preserve the selected native project through recovery', async () => {
    const bridge = make('shared-root');
    await bridge.refresh();
    const selected = bridge.projectTarget('Other', [directory]);
    assert.deepEqual(selected, { directory, nativeProjectId: 'other-native-project' });
    const count = starts;
    const operation = { ...plan('shared-root-001'), ...selected };
    await bridge.create(operation);
    assert.equal(starts, count + 1);
    bridge.close();
    const restored = make('shared-root');
    assert.equal(await restored.reconcile(operation), childId);
    assert.equal(starts, count + 1);
    restored.close();
  });
  await test('restart reconciliation uses actual result without another create', async () => {
    failFirst = true;
    const bridge = make('recovery');
    await assert.rejects(bridge.create(plan('recovery-001')), /disconnect/);
    bridge.close();
    failFirst = false;
    const restored = make('recovery');
    const count = starts;
    assert.equal(await restored.reconcile(plan('recovery-001')), childId);
    assert.equal(starts, count);
    restored.close();
  });
  await test('wrong native project is never accepted or replayed', async () => {
    mismatch = true;
    const bridge = make('mismatch');
    const operation = plan('mismatch-001');
    await assert.rejects(bridge.create(operation), /mapping mismatch/);
    const count = starts;
    await assert.rejects(bridge.reconcile(operation), /mapping mismatch/);
    assert.equal(starts, count);
    bridge.close();
    mismatch = false;
  });
  await test('projectless uses original workspace kind', async () => {
    const bridge = make('projectless');
    assert.equal(await bridge.create(plan('projectless-001', true)), childId);
    bridge.close();
  });
  await test('opening an unloaded owner navigates without sending target input', async () => {
    states.delete(childId);
    const bridge = make('open');
    const count = firstInput;
    assert.equal(await bridge.open(childId, 'open-owner-001'), true);
    assert.equal(navigation, 1);
    assert.equal(firstInput, count);
    assert.equal(await bridge.open(childId, 'open-owner-001'), true);
    assert.equal(navigation, 1);
    bridge.close();
  });
  await test('open-only proof permits navigation without enabling creation', async () => {
    const proofPath = join(directory, 'open-proof.json');
    const proof = {
      kind: 'real-desktop-controller-open',
      hostId: 'host',
      controllerThreadId: controllerId,
      desktopVersion: 'build',
      adapterSha256: controllerAdapterDigest(),
      gates: { open: true },
      unloadedOpen: true,
    };
    await writePrivate(proofPath, JSON.stringify(proof), { mode: 0o600 });
    protectWindowsStateFile(proofPath);
    await writePrivate(
      config,
      JSON.stringify({
        hostId: 'host',
        threadId: controllerId,
        desktopVersion: 'build',
        openOnly: true,
        verification: proofPath,
      }),
      { mode: 0o600 },
    );
    const bridge = make('open-only');
    await bridge.refresh();
    assert.equal(bridge.openAvailable, true);
    assert.equal(bridge.available, false);
    const createCount = starts;
    await assert.rejects(bridge.create(plan('open-only-create')), /unavailable|不可用/i);
    assert.equal(starts, createCount);
    states.delete(childId);
    const inputCount = firstInput;
    assert.equal(await bridge.open(childId, 'open-only-nav'), true);
    assert.equal(firstInput, inputCount);
    for (const invalid of [
      { ...proof, hostId: 'wrong-host' },
      { ...proof, adapterSha256: 'old-code' },
      { ...proof, unloadedOpen: false },
      { ...proof, gates: { open: false } },
    ]) {
      await writePrivate(proofPath, JSON.stringify(invalid));
      await bridge.refresh();
      assert.equal(bridge.openAvailable, false);
      assert.equal(bridge.available, false);
    }
    await writePrivate(proofPath, JSON.stringify(proof));
    await writePrivate(
      config,
      JSON.stringify({
        hostId: 'host',
        threadId: controllerId,
        desktopVersion: 'build',
        openOnly: 'true',
        verification: proofPath,
      }),
    );
    await bridge.refresh();
    assert.equal(bridge.available, false);
    assert.equal(bridge.openAvailable, false);
    await writePrivate(
      config,
      JSON.stringify({
        hostId: 'host',
        threadId: controllerId,
        desktopVersion: 'build',
        verification: proofPath,
      }),
    );
    await bridge.refresh();
    assert.equal(
      bridge.available,
      false,
      'An open proof cannot enable the full creation controller',
    );
    assert.equal(bridge.openAvailable, false);
    bridge.close();
  });
  await test('creation acceptance and verified opening have independent availability', async () => {
    const proofPath = join(directory, 'independent-open-proof.json');
    const proof = {
      kind: 'real-desktop-controller-open',
      hostId: 'host',
      controllerThreadId: controllerId,
      desktopVersion: 'build',
      adapterSha256: controllerAdapterDigest(),
      gates: { open: true },
      unloadedOpen: true,
    };
    await writePrivate(proofPath, JSON.stringify(proof), { mode: 0o600 });
    protectWindowsStateFile(proofPath);
    await writePrivate(
      config,
      JSON.stringify({
        hostId: 'host',
        threadId: controllerId,
        desktopVersion: 'build',
        validationOnly: true,
        openVerification: proofPath,
      }),
    );
    const bridge = make('independent-scopes');
    await bridge.refresh();
    assert.equal(bridge.available, true);
    assert.equal(bridge.validationOnly, true);
    assert.equal(bridge.openAvailable, true);
    assert.equal(bridge.openValidationOnly, false);
    await writePrivate(
      config,
      JSON.stringify({
        hostId: 'host',
        threadId: controllerId,
        desktopVersion: 'build',
        openVerification: proofPath,
        verification: 'missing-creation-proof.json',
      }),
    );
    await bridge.refresh();
    assert.equal(bridge.available, false);
    assert.equal(bridge.openAvailable, true);
    assert.equal(bridge.openValidationOnly, false);
    await writePrivate(proofPath, JSON.stringify({ ...proof, hostId: 'wrong-host' }));
    await bridge.refresh();
    assert.equal(bridge.openAvailable, false);
    bridge.close();
  });
  console.log(JSON.stringify({ passed, scope: 'fixture-boundary' }));
} finally {
  await rm(directory, { recursive: true, force: true });
}
