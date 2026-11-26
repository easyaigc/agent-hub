import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, writeFile, readFile, rm, mkdir } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { DesktopProjectCreation } from '../packages/codex-adapter/dist/desktop-project-creation.js';
import { DesktopWorkspaceManagement } from '../packages/codex-adapter/dist/desktop-workspace-management.js';
import { MetadataConnection } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import { CodexRejection } from '../packages/codex-adapter/dist/runtime.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopWorkspaceView } from '../packages/codex-adapter/dist/desktop-workspace-view.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopProjectVisibility } from '../packages/codex-adapter/dist/desktop-project-visibility.js';
import { CodexHistoryReader } from '../packages/codex-adapter/dist/history-reader.js';

const target = {
  agentId: 'codex',
  hostId: 'host-a',
  workspaceId: 'host-a',
  runtimeId: 'desktop-runtime',
};

test('a transient initial save failure becomes rejected without RPC and releases the root for a new operation', async () => {
  const records = {};
  let saves = 0,
    writes = 0;
  const broker = new DesktopProjectCreation(
    target,
    records,
    () => {
      if (++saves === 1) throw new Error('transient disk failure');
    },
    async (record, before) => {
      before();
      writes++;
      return record.operationId;
    },
    async (record) => record.projectId,
  );
  await assert.rejects(
    broker.submit('P', '/synthetic/root', 'initial-save-fail'),
    /transient disk failure/,
  );
  assert.equal(records['initial-save-fail'].state, 'rejected');
  assert.equal(writes, 0);
  assert.equal(
    await broker.submit('P', '/synthetic/root', 'after-initial-save'),
    'after-initial-save',
  );
  assert.equal(writes, 1);
});

test('restored inactive prepared operations are rejected before a new submission while active preparations stay pending', async () => {
  const records = {
    'orphan-op': {
      target,
      operationId: 'orphan-op',
      name: 'P',
      root: '/synthetic/root',
      state: 'prepared',
    },
  };
  let release,
    writes = 0;
  const broker = new DesktopProjectCreation(
    target,
    records,
    () => {},
    async (record, before) => {
      if (record.operationId === 'active-preparation')
        await new Promise((resolve) => {
          release = resolve;
        });
      before();
      writes++;
      return record.operationId;
    },
    async (record) => record.projectId,
  );
  const active = broker.submit('P', '/synthetic/root', 'active-preparation');
  assert.equal(records['orphan-op'].state, 'rejected');
  assert.equal(records['active-preparation'].state, 'prepared');
  assert.throws(() => broker.submit('P', '/synthetic/root', 'blocked-by-active'), /原操作/);
  assert.equal(
    records['active-preparation'].state,
    'prepared',
    'An in-flight preparation is never mistaken for an orphan',
  );
  release();
  await active;
  assert.equal(writes, 1);
});

test('same-root refusal has a terminal receipt and does not block a new operation after the original settles', async () => {
  const records = {};
  let release,
    writes = 0;
  const broker = new DesktopProjectCreation(
    target,
    records,
    () => {},
    async (record, before) => {
      before();
      writes++;
      if (record.operationId === 'original-pending')
        await new Promise((resolve) => {
          release = resolve;
        });
      return record.operationId;
    },
    async (record) => record.projectId,
  );
  const first = broker.submit('P', '/synthetic/root', 'original-pending', {
    name: 'P',
    directory: '.',
  });
  assert.throws(
    () => broker.submit('P', '/synthetic/root', 'refused-operation', { name: 'P', directory: '.' }),
    /原操作/,
  );
  assert.equal(records['refused-operation'].state, 'rejected');
  assert.equal(records['original-pending'].state, 'submitting');
  release();
  await first;
  assert.equal(
    await broker.submit('P', '/synthetic/root', 'new-operation-after', {
      name: 'P',
      directory: '.',
    }),
    'new-operation-after',
  );
  assert.equal(writes, 2);
});

test('missing directory is a durable unaccepted receipt and a corrected same input can use a new operation', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'project-prewrite-refusal-'));
  const create = DesktopWorkspaceManagement.prototype.createProject,
    find = DesktopWorkspaceManagement.prototype.findCreatedProject;
  const workspace = RuntimeSession.prototype.workspace;
  let session,
    writes = 0;
  try {
    session = new RuntimeSession({
      mode: 'desktop-runtime',
      directory,
      root: directory,
      name: 'Refusal fixture',
      executable: '/not-started',
    });
    Object.defineProperty(session.runtime, 'ready', { value: true, writable: true });
    DesktopWorkspaceManagement.prototype.createProject = async (_record, before) => {
      before();
      writes++;
      return 'p';
    };
    DesktopWorkspaceManagement.prototype.findCreatedProject = async () => 'p';
    RuntimeSession.prototype.workspace = async function () {
      return {
        hostId: this.hostId,
        projects: [],
        threads: [],
        capabilities: [],
        nextProjectCursor: null,
        nextThreadCursor: null,
      };
    };
    await assert.rejects(
      session.createProject('P', 'missing-folder', 'missing-dir-op'),
      /项目目录不存在/,
    );
    const refusal = await session.projectCreationReceipt('missing-dir-op');
    assert.equal(refusal.state, 'rejected');
    assert.equal(refusal.requestDirectory, 'missing-folder');
    assert.equal(writes, 0);
    await mkdir(join(directory, 'missing-folder'));
    await session.createProject('P', 'missing-folder', 'corrected-dir-op');
    assert.equal((await session.projectCreationReceipt('corrected-dir-op')).state, 'completed');
    assert.equal(writes, 1);
    assert.equal((await session.projectCreationReceipt('missing-dir-op')).state, 'rejected');
    // The path can also disappear after metadata connection setup but before
    // the official RPC. The real write-boundary callback still owns that check.
    DesktopWorkspaceManagement.prototype.createProject = async (_record, before) => {
      await rm(join(directory, 'missing-folder'), { recursive: true });
      before();
      assert.fail('No official write is permitted after the final path check fails');
    };
    await assert.rejects(session.createProject('P', 'missing-folder', 'late-path-refusal'));
    assert.equal((await session.projectCreationReceipt('late-path-refusal')).state, 'rejected');
    await mkdir(join(directory, 'missing-folder'));
    DesktopWorkspaceManagement.prototype.createProject = async (_record, before) => {
      session.runtime.ready = false;
      before();
      assert.fail('No official write is permitted after the final runtime check fails');
    };
    await assert.rejects(
      session.createProject('P', 'missing-folder', 'late-ready-refusal'),
      /未连接/,
    );
    assert.equal((await session.projectCreationReceipt('late-ready-refusal')).state, 'rejected');
    session.runtime.ready = true;
    DesktopWorkspaceManagement.prototype.createProject = async (_record, before) => {
      before();
      writes++;
      return 'p';
    };
    await session.createProject('P', 'missing-folder', 'after-race-refusals');
    assert.equal(writes, 2, 'Both known pre-write refusals leave the corrected input usable');
  } finally {
    session?.close();
    DesktopWorkspaceManagement.prototype.createProject = create;
    DesktopWorkspaceManagement.prototype.findCreatedProject = find;
    RuntimeSession.prototype.workspace = workspace;
    await rm(directory, { recursive: true });
  }
});

test('creation preserves raw relative input independently of canonical name/root and refuses changed same-ID payloads', async () => {
  const records = {};
  const request = { name: '  Project  ', directory: '.' };
  const broker = new DesktopProjectCreation(
    target,
    records,
    () => {},
    async (_record, before) => {
      before();
      return 'p';
    },
    async () => 'p',
  );
  assert.equal(await broker.submit('Project', '/synthetic/canonical', 'relative-op', request), 'p');
  assert.deepEqual(records['relative-op'].request, request);
  assert.throws(
    () =>
      broker.submit('Project', '/synthetic/canonical', 'relative-op', {
        ...request,
        directory: './',
      }),
    /不同/,
  );
  assert.equal(await broker.submit('Project', '/synthetic/canonical', 'relative-op', request), 'p');
});

test('late negative lookup cannot demote an independently confirmed original creation receipt', async () => {
  const record = {
    target,
    operationId: 'confirmed-race',
    name: 'P',
    root: '/synthetic/root',
    state: 'outcome-unknown',
  };
  let release;
  const broker = new DesktopProjectCreation(
    target,
    { 'confirmed-race': record },
    () => {},
    async () => assert.fail('Recovery never writes'),
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const pending = broker.submit('P', '/synthetic/root', 'confirmed-race');
  record.state = 'completed';
  record.projectId = 'p';
  release(undefined);
  assert.equal(await pending, 'p');
  assert.equal(record.state, 'completed');
});

test('independent creation receipt retains raw input and queries an unknown operation without a create replay', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'project-original-receipt-'));
  const original = DesktopWorkspaceManagement.prototype.findCreatedProject;
  const create = DesktopWorkspaceManagement.prototype.createProject;
  let session,
    reads = 0;
  try {
    session = new RuntimeSession({
      mode: 'desktop-runtime',
      directory,
      root: directory,
      name: 'Receipt fixture',
      executable: '/not-started',
    });
    const record = {
      target: session.readingTarget(),
      operationId: 'relative-receipt',
      name: 'Project',
      root: directory,
      request: { name: '  Project  ', directory: '.' },
      state: 'outcome-unknown',
    };
    session.state.projectCreations = { 'relative-receipt': record };
    DesktopWorkspaceManagement.prototype.findCreatedProject = async function (observed) {
      reads++;
      assert.equal(observed, record);
      return 'native-project';
    };
    DesktopWorkspaceManagement.prototype.createProject = async () =>
      assert.fail('Receipt query cannot create');
    const result = await session.projectCreationReceipt('relative-receipt');
    assert.equal(result.state, 'completed');
    assert.equal(result.requestName, '  Project  ');
    assert.equal(result.requestDirectory, '.');
    assert.equal(result.directory, directory);
    assert.equal(result.projectId, 'native-project');
    assert.equal(reads, 1);
    await session.projectCreationReceipt('relative-receipt');
    assert.equal(
      reads,
      1,
      'Completed receipts survive later rename/removal without reinterpreting metadata',
    );
    session.state.projectCreations['prepared-receipt'] = {
      ...record,
      operationId: 'prepared-receipt',
      state: 'prepared',
      projectId: undefined,
    };
    assert.equal(
      (await session.projectCreationReceipt('prepared-receipt')).state,
      'rejected',
      'A restored unsent preparation has a terminal receipt without querying or writing the official server',
    );
    assert.equal(reads, 1);
  } finally {
    session?.close();
    DesktopWorkspaceManagement.prototype.findCreatedProject = original;
    DesktopWorkspaceManagement.prototype.createProject = create;
    await rm(directory, { recursive: true });
  }
});

test('pending project membership is resolved before thread grouping; catalog-less legacy reads never create adoption evidence', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'pending-project-threads-'));
  const path = join(directory, '.codex-global-state.json');
  const reader = new CodexHistoryReader(target.hostId, 'fixture', '/not-started', path);
  let saved,
    observations = 0;
  const visibility = new DesktopProjectVisibility(
    () => saved,
    (value) => {
      saved = structuredClone(value);
    },
  );
  const creation = { target, state: 'completed', projectId: 'pending' };
  visibility.created(target, directory, 'pending');
  reader.rpc = async (method, params) => ({
    data:
      method === 'project/list'
        ? ['current', 'pending'].map((id) => ({
            id,
            name: id,
            roots: [{ path: '/synthetic/shared-root' }],
          }))
        : params.archived || params.sectionId
          ? []
          : [{ id: 't', cwd: '/synthetic/shared-root', preview: 'T' }],
    nextCursor: null,
  });
  const resolve = (catalog) => {
    observations++;
    const e = catalog.desktopCatalog;
    return visibility.observe(
      target,
      e.profile,
      e.fingerprint,
      e.nativeProjects,
      catalog.projects,
      e.pendingDeletionIds,
      [creation],
    );
  };
  try {
    await writeFile(
      path,
      JSON.stringify({
        'local-projects': { current: { name: 'Current' } },
        'thread-project-assignments': { t: { projectKind: 'local', projectId: 'pending' } },
      }),
    );
    const catalog = await reader.desktopWorkspace(undefined, resolve);
    assert.equal(catalog.threads[0].projectId, 'pending');
    assert.deepEqual(catalog.sidebar.pendingProjectIds, ['pending']);
    assert.deepEqual(catalog.sidebar.projectThreadIds.pending, ['t']);
    assert.equal(catalog.projects.find((p) => p.id === 'pending').desktopVisibility, 'pending');
    await writeFile(path, JSON.stringify({ 'legacy-window-setting': true }));
    const legacy = await reader.desktopWorkspace(undefined, resolve);
    assert.equal(legacy.desktopCatalog, undefined);
    assert.equal(
      observations,
      1,
      'Returning raw legacy metadata is not a positive desktop catalog observation',
    );
  } finally {
    reader.close();
    await rm(directory, { recursive: true });
  }
});

test('phone creation stays pending until positively observed, then desktop removal survives restart without changing receipts', () => {
  let saved;
  const store = () =>
    new DesktopProjectVisibility(
      () => structuredClone(saved),
      (value) => {
        saved = structuredClone(value);
      },
    );
  const project = {
    id: 'native-id',
    hostId: target.hostId,
    name: 'Original',
    roots: ['/synthetic/project'],
  };
  const receipt = {
    target,
    operationId: 'original-op',
    state: 'completed',
    projectId: project.id,
    name: 'Original',
    root: project.roots[0],
  };
  const receiptBytes = JSON.stringify(receipt);
  const observe = (visibility, visible, deletions = new Set()) =>
    visibility.observe(
      target,
      '/synthetic/profile',
      'display-fingerprint',
      [project],
      visible,
      deletions,
      [receipt],
    );
  const first = store();
  first.created(target, '/synthetic/profile', project.id);
  assert.equal(observe(first, [])[0].desktopVisibility, 'pending');
  assert.equal(observe(store(), [{ ...project, name: 'Renamed' }])[0].name, 'Renamed');
  assert.deepEqual(
    observe(store(), []),
    [],
    'Previously adopted projects disappear even while retained by project/list',
  );
  assert.deepEqual(
    observe(store(), []),
    [],
    'Restart cannot resurrect a removed project from its creation receipt',
  );
  assert.equal(
    JSON.stringify(receipt),
    receiptBytes,
    'Visibility provenance is separate from immutable creation intent',
  );
  assert.equal(
    observe(store(), [project])[0].id,
    project.id,
    'A positive current desktop catalog can restore an explicitly re-added project',
  );
});

test('old receipts and identity mappings never invent desktop adoption; exact pending deletion can close an unobserved creation', () => {
  let saved;
  const visibility = new DesktopProjectVisibility(
    () => saved,
    (value) => {
      saved = structuredClone(value);
    },
  );
  const project = {
    id: 'same-native-and-legacy-id',
    hostId: target.hostId,
    name: 'Old receipt',
    roots: [],
  };
  const receipt = { target, state: 'completed', projectId: project.id };
  const read = (profile, visible, deletions = new Set()) =>
    visibility.observe(target, profile, 'fp', [project], visible, deletions, [receipt]);
  assert.equal(
    read('profile', [])[0].desktopVisibility,
    'unknown',
    'A missed desktop observation remains explicit uncertainty',
  );
  assert.deepEqual(read('profile', [], new Set([project.id])), []);
  assert.equal(
    read('different-profile', [])[0].desktopVisibility,
    'unknown',
    'Another profile cannot inherit adoption/removal',
  );
  assert.equal(
    read('different-profile', [project])[0].id,
    project.id,
    'Native IDs that already equal display IDs need no invented mapping',
  );
  const foreign = { ...receipt, target: { ...target, hostId: 'foreign' } };
  assert.deepEqual(
    visibility.observe(target, 'profile', 'fp', [project], [], new Set(), [foreign]),
    [],
  );
});

test('failed visibility persistence prevents publishing a new adoption/removal result', () => {
  const visibility = new DesktopProjectVisibility(
    () => undefined,
    () => {
      throw new Error('disk unavailable');
    },
  );
  const project = { id: 'p', hostId: target.hostId, name: 'P', roots: [] };
  assert.throws(
    () => visibility.observe(target, 'profile', 'fp', [project], [project], new Set(), []),
    /disk unavailable/,
  );
});

test('completed project receipt recovery does not re-query renamed or deleted metadata and never recreates it', async () => {
  const receipt = {
    target,
    operationId: 'completed',
    name: 'Original',
    root: '/synthetic/root',
    state: 'completed',
    projectId: 'p',
  };
  const original = structuredClone(receipt);
  const broker = new DesktopProjectCreation(
    target,
    { completed: receipt },
    () => assert.fail('completed receipt must stay immutable'),
    async () => assert.fail('completed operation must not write'),
    async () => assert.fail('current metadata cannot demote a confirmed receipt'),
  );
  assert.equal(await broker.submit('Original', '/synthetic/root', 'completed'), 'p');
  assert.equal(await broker.submit('Original', '/synthetic/root', 'completed'), 'p');
  assert.deepEqual(receipt, original);
});

test('explicit refresh waits for a successor read, coalesces only before it starts and isolates cancelled waiters', async () => {
  const view = new DesktopWorkspaceView(
    { hostId: 'h' },
    'fixture',
    undefined,
    '/synthetic/runtime.json',
  );
  const reads = [];
  view.refresh = () => new Promise((resolve, reject) => reads.push({ resolve, reject }));
  const tick = () => new Promise((resolve) => setImmediate(resolve));
  try {
    const old = view.workspace();
    const a = view.workspace({ fresh: true });
    const b = view.workspace({ fresh: true });
    assert.equal(a, b);
    assert.equal(reads.length, 1);
    const controller = new AbortController();
    const cancelled = view.workspace({ fresh: true, signal: controller.signal });
    controller.abort(new Error('caller cancelled'));
    await assert.rejects(cancelled, /caller cancelled/);
    reads[0].resolve({ name: 'old' });
    assert.equal((await old).name, 'old');
    await tick();
    assert.equal(reads.length, 2);
    const later = view.workspace({ fresh: true });
    reads[1].resolve({ name: 'fresh' });
    assert.equal((await a).name, 'fresh');
    assert.equal((await b).name, 'fresh');
    await tick();
    assert.equal(
      reads.length,
      3,
      'An intent arriving after successor start needs its own later read',
    );
    reads[2].resolve({ name: 'later' });
    assert.equal((await later).name, 'later');
  } finally {
    view.close();
  }
});

test('closing the catalog rejects queued refreshes instead of reporting stale success', async () => {
  const view = new DesktopWorkspaceView(
    { hostId: 'h' },
    'fixture',
    undefined,
    '/synthetic/runtime.json',
  );
  let release;
  view.refresh = () =>
    new Promise((resolve) => {
      release = resolve;
    });
  const old = view.workspace();
  const queued = view.workspace({ fresh: true });
  const rejected = assert.rejects(queued, /closed/);
  view.close();
  await rejected;
  release({ name: 'old' });
  await assert.rejects(old, /closed/);
});

for (const [projectPages, threadPages] of [
  [2, 3],
  [3, 2],
])
  test(`catalog hydration exhausts ${projectPages} project pages and ${threadPages} thread pages independently`, async () => {
    const calls = [];
    const cursor = (prefix, index, count) => (index + 1 < count ? prefix + (index + 1) : null);
    const session = {
      hostId: 'h',
      connectDesktopReader: async () => {},
      desktopCatalogProjects: (catalog) => catalog.projects,
      workspace: async (project, thread) => {
        calls.push([project, thread]);
        const p = project ? Number(project.slice(1)) : 0,
          t = thread ? Number(thread.slice(1)) : 0;
        return {
          hostId: 'h',
          projects: [],
          threads: [],
          nextProjectCursor: cursor('p', p, projectPages),
          nextThreadCursor: cursor('t', t, threadPages),
        };
      },
    };
    const view = new DesktopWorkspaceView(session, 'fixture', undefined, '/synthetic/runtime.json');
    view.reader.desktopWorkspace = async () => ({
      hostId: 'h',
      projects: [],
      threads: [],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
    try {
      const result = await view.workspace();
      assert.equal(
        calls.length,
        Math.max(projectPages, threadPages),
        'The exhausted dimension must not restart its first page cursor',
      );
      assert.equal(result.nextProjectCursor, null);
      assert.equal(result.nextThreadCursor, null);
      assert.equal(
        'desktopCatalog' in result,
        false,
        'Internal provenance is never a public workspace field',
      );
    } finally {
      view.close();
    }
  });

test('only completed same-machine creation provenance extends project visibility after restart', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'project-visibility-provenance-'));
  const record = {
    target,
    operationId: 'visibility-operation',
    name: 'Demo',
    root: directory,
    state: 'completed',
    projectId: 'confirmed-native-project',
  };
  await writeFile(
    join(directory, 'runtime.json'),
    JSON.stringify({
      hostId: target.hostId,
      projects: [],
      threads: [],
      attachments: [],
      receipts: [],
      creations: {},
      projectCreations: {
        confirmed: record,
        uncertain: { ...record, state: 'outcome-unknown', projectId: 'uncertain-project' },
        foreign: {
          ...record,
          target: { ...target, hostId: 'another-host' },
          projectId: 'foreign-project',
        },
        otherRuntime: {
          ...record,
          target: { ...target, runtimeId: 'managed-runtime' },
          projectId: 'other-runtime',
        },
        rejected: { ...record, state: 'rejected', projectId: 'rejected-project' },
      },
    }),
    { mode: 0o600 },
  );
  let session;
  try {
    session = new RuntimeSession({
      mode: 'desktop-runtime',
      directory,
      root: directory,
      name: 'Project visibility fixture',
      executable: '/not-started',
    });
    assert.deepEqual([...session.confirmedCreatedProjectIds()], ['confirmed-native-project']);
  } finally {
    session?.close();
    await rm(directory, { recursive: true, force: true });
  }
});
test('creation is durable before write; duplicates coalesce and changed inputs conflict', async () => {
  const records = {};
  let durable,
    writes = 0;
  const broker = new DesktopProjectCreation(
    target,
    records,
    () => {
      durable = structuredClone(records);
    },
    async (record, beforeWrite) => {
      beforeWrite();
      assert.equal(durable[record.operationId].state, 'submitting');
      writes++;
      await new Promise((resolve) => setImmediate(resolve));
      return 'project-1';
    },
    async () => 'project-1',
  );
  assert.throws(() => broker.submit('Demo', '/allowed/demo', '__proto__'), /操作 ID 无效/);
  const first = broker.submit('Demo', '/allowed/demo', 'operation-1');
  const same = broker.submit('Demo', '/allowed/demo', 'operation-1');
  assert.equal(first, same);
  assert.throws(() => broker.submit('Changed', '/allowed/demo', 'operation-1'), /不同/);
  assert.throws(() => broker.submit('Demo', '/allowed/other', 'operation-1'), /不同/);
  assert.throws(() => broker.submit('Demo', '/allowed/demo', 'new-operation'), /原操作/);
  assert.equal(await first, 'project-1');
  assert.equal(await broker.submit('Demo', '/allowed/demo', 'operation-1'), 'project-1');
  assert.equal(writes, 1);
});

test('loss and restart query the original operation without repeating writes', async () => {
  const records = {};
  let writes = 0,
    visible = false;
  const broker = new DesktopProjectCreation(
    target,
    records,
    () => {},
    async (_record, beforeWrite) => {
      beforeWrite();
      writes++;
      throw new Error('transport lost after dispatch');
    },
    async () => (visible ? 'late-project' : undefined),
  );
  await assert.rejects(broker.submit('Demo', '/allowed/demo', 'operation-2'), /结果待确认/);
  assert.equal(records['operation-2'].state, 'outcome-unknown');
  const restarted = new DesktopProjectCreation(
    target,
    JSON.parse(JSON.stringify(records)),
    () => {},
    async () => assert.fail('restart must not write'),
    async () => (visible ? 'late-project' : undefined),
  );
  await assert.rejects(restarted.submit('Demo', '/allowed/demo', 'operation-2'), /结果待确认/);
  visible = true;
  assert.equal(await restarted.submit('Demo', '/allowed/demo', 'operation-2'), 'late-project');
  assert.equal(writes, 1);
});

test('persistence failure, official rejection and target mismatch never authorize another write', async () => {
  const records = {};
  let writes = 0;
  const failing = new DesktopProjectCreation(
    target,
    records,
    () => {
      throw new Error('disk full');
    },
    async () => {
      writes++;
      return 'bad';
    },
    async () => undefined,
  );
  await assert.rejects(failing.submit('Demo', '/allowed/demo', 'operation-3'), /disk full/);
  assert.equal(writes, 0);
  const rejected = {};
  const broker = new DesktopProjectCreation(
    target,
    rejected,
    () => {},
    async (_record, beforeWrite) => {
      beforeWrite();
      writes++;
      throw new CodexRejection('unsupported method');
    },
    async () => assert.fail('explicit rejection has no success to infer'),
  );
  await assert.rejects(broker.submit('Demo', '/allowed/demo', 'operation-4'), /unsupported/);
  await assert.rejects(broker.submit('Demo', '/allowed/demo', 'operation-4'), /unsupported/);
  assert.equal(writes, 1);
  const other = new DesktopProjectCreation(
    { ...target, hostId: 'host-b' },
    rejected,
    () => {},
    async () => assert.fail('other target'),
    async () => undefined,
  );
  assert.throws(() => other.submit('Demo', '/allowed/demo', 'operation-4'), /不同/);
});

test('fixed official metadata transport creates and verifies catalog, without execution RPC', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'project-create-boundary-'));
  const cli = join(directory, 'metadata-cli.mjs');
  await writeFile(
    cli,
    `#!/usr/bin/env node
import { createInterface } from 'node:readline';
import { readFileSync, writeFileSync, appendFileSync } from 'node:fs';
import { join } from 'node:path';
const file = join(process.env.CODEX_HOME, 'catalog.json');
for await (const line of createInterface({input:process.stdin})) {
  const request=JSON.parse(line); if(request.id===undefined) continue;
  appendFileSync(join(process.env.CODEX_HOME,'methods.jsonl'),JSON.stringify(request)+'\\n');
  let result={};
  if(request.method==='project/create') {
    result={project:{id:'official-project-id',...request.params}};
    writeFileSync(file,JSON.stringify(result.project));
  } else if(request.method==='project/list') {
    let data=[];try{data=[JSON.parse(readFileSync(file,'utf8'))]}catch{}
    result={data,nextCursor:null};
  } else if(request.method!=='initialize') throw new Error('unexpected method');
  process.stdout.write(JSON.stringify({id:request.id,result})+'\\n');
}
`,
    { mode: 0o700 },
  );
  try {
    const metadata = new DesktopWorkspaceManagement(cli, directory);
    const record = {
      target,
      name: 'Demo',
      root: directory,
      operationId: 'operation-5',
      state: 'prepared',
    };
    let preflight = 0;
    assert.equal(
      await metadata.createProject(record, () => {
        preflight++;
      }),
      'official-project-id',
    );
    assert.equal(await metadata.findCreatedProject(record), 'official-project-id');
    assert.equal(preflight, 1);
    assert.equal(
      await metadata.findCreatedProject({ ...record, operationId: 'another-operation' }),
      undefined,
    );
    await assert.rejects(metadata.findCreatedProject({ ...record, root: '/different' }), /不一致/);
    const edited = JSON.parse(await readFile(join(directory, 'catalog.json'), 'utf8'));
    edited.name = 'Renamed on desktop';
    edited.roots = [{ path: '/synthetic/new-root' }];
    await writeFile(join(directory, 'catalog.json'), JSON.stringify(edited));
    assert.equal(
      await metadata.findCreatedProject(record, true),
      'official-project-id',
      'An exact original operation marker confirms creation after later desktop edits',
    );
    assert.equal(
      await metadata.findCreatedProject({ ...record, operationId: 'unrelated-operation' }, true),
      undefined,
    );
    await assert.rejects(
      metadata.createProject({ ...record, operationId: 'not-written' }, () => {
        throw new CodexRejection('directory changed');
      }),
      /directory changed/,
    );
    const methods = (await readFile(join(directory, 'methods.jsonl'), 'utf8'))
      .trim()
      .split('\n')
      .map(JSON.parse);
    assert.equal(methods.filter((item) => item.method === 'project/create').length, 1);
    const create = methods.find((item) => item.method === 'project/create');
    assert.deepEqual(create.params.roots, [{ path: directory }]);
    assert.equal(create.params.idempotencyKey, record.operationId);
    assert.equal(create.params.metadata['agent-hub:host'], target.hostId);
    assert(
      methods.every((item) =>
        ['initialize', 'project/create', 'project/list'].includes(item.method),
      ),
    );
    const transport = new MetadataConnection(cli, directory, directory, 'management');
    try {
      await transport.initialize();
      assert.throws(() => transport.rpc('turn/start', {}), /只允许/);
    } finally {
      assert.equal(await transport.close(), 0);
    }
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('HTTP project creation and subsequent GET use the same projected catalog without exposing orphans', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'project-create-http-view-'));
  const environment = { ...process.env };
  const connect = RuntimeSession.prototype.connect;
  const create = RuntimeSession.prototype.createProject;
  const rawWorkspace = RuntimeSession.prototype.workspace;
  const viewWorkspace = DesktopWorkspaceView.prototype.workspace;
  let host,
    writes = 0,
    catalogUnavailable = false,
    connectedFixture = false;
  try {
    for (const key of Object.keys(process.env))
      if (
        key.startsWith('HOST_AGENT_') ||
        key.startsWith('CODEX_DESKTOP_') ||
        ['CODEX_SHARED_SERVER_URL', 'HOST_NOTIFICATION_RELAY_URL'].includes(key)
      )
        delete process.env[key];
    Object.assign(process.env, {
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      HOST_AGENT_STATE_DIR: directory,
      HOST_AGENT_ROOT: directory,
      HOST_AGENT_TOKEN: 'synthetic-project-test-token-0000000000',
      HOST_AGENT_PORT: '0',
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_DISCOVERY: '0',
      CODEX_DESKTOP_SUGGESTION_OBSERVER: '0',
      CODEX_SOCKET: join(directory, 'fixture-only.sock'),
    });
    const workspace = (session, projected) => ({
      hostId: session.hostId,
      ready: true,
      capabilities: ['projects:read', 'projects:create'],
      projects: [
        'desktop-project',
        ...(writes ? ['created-project'] : []),
        ...(projected ? [] : ['old-orphan']),
      ].map((id) => ({ id, hostId: session.hostId, name: id, roots: [directory] })),
      threads: [],
      nextProjectCursor: null,
      nextThreadCursor: null,
    });
    RuntimeSession.prototype.connect = async function () {
      connectedFixture = true;
      Object.defineProperty(this.runtime, 'ready', { value: true, writable: true });
    };
    RuntimeSession.prototype.workspace = async function () {
      return workspace(this, false);
    };
    RuntimeSession.prototype.createProject = async function (name, root, operation) {
      assert.equal(name, 'Added');
      assert.equal(root, directory);
      assert.equal(operation, 'operation-http');
      writes++;
      this.state.projectCreations = {
        'operation-http': {
          target: this.readingTarget(),
          operationId: operation,
          name,
          root,
          state: 'completed',
          projectId: 'created-project',
        },
      };
      return this.workspace();
    };
    DesktopWorkspaceView.prototype.workspace = async function () {
      if (catalogUnavailable) throw new Error('catalog offline after confirmed creation');
      return workspace(this.session, true);
    };
    host = await startRuntimeServer();
    assert.equal(connectedFixture, true, 'Production server must use the fenced fixture session');
    const url = `http://127.0.0.1:${host.server.address().port}`;
    const headers = {
      Authorization: `Bearer ${process.env.HOST_AGENT_TOKEN}`,
      'Content-Type': 'application/json',
    };
    const response = await fetch(url + '/v1/projects', {
      method: 'POST',
      headers,
      body: JSON.stringify({
        hostId: host.session.hostId,
        name: 'Added',
        directory,
        clientOperationId: 'operation-http',
      }),
    });
    assert.equal(response.status, 200);
    const posted = await response.json();
    assert.deepEqual(
      posted.projects.map((p) => p.id),
      ['desktop-project', 'created-project'],
    );
    const refreshed = await (await fetch(url + '/v1/workspace', { headers })).json();
    assert.deepEqual(refreshed.projects, posted.projects);
    assert(posted.capabilities.includes('workspace:refresh:v1'));
    assert(posted.capabilities.includes('projects:create:receipts:v1'));
    catalogUnavailable = true;
    const query = new URLSearchParams({
      hostId: host.session.hostId,
      target: JSON.stringify(host.session.readingTarget()),
      id: 'operation-http',
    });
    const receiptResponse = await fetch(url + '/v1/projects/creations?' + query, { headers });
    assert.equal(
      receiptResponse.status,
      200,
      'The original receipt must not depend on a fresh directory',
    );
    assert.equal((await receiptResponse.json()).projectId, 'created-project');
    const wrong = new URLSearchParams(query);
    wrong.set('target', JSON.stringify({ ...host.session.readingTarget(), runtimeId: 'foreign' }));
    assert.equal((await fetch(url + '/v1/projects/creations?' + wrong, { headers })).status, 409);
    assert.equal(writes, 1, 'Refreshing the catalog cannot replay project creation');
  } finally {
    if (host) {
      await new Promise((done) => {
        host.server.once('close', done);
        host.close();
      });
      await host.diagnostics.close();
    }
    RuntimeSession.prototype.connect = connect;
    RuntimeSession.prototype.createProject = create;
    RuntimeSession.prototype.workspace = rawWorkspace;
    DesktopWorkspaceView.prototype.workspace = viewWorkspace;
    for (const key of Object.keys(process.env)) if (!(key in environment)) delete process.env[key];
    Object.assign(process.env, environment);
    await rm(directory, { recursive: true, force: true });
  }
});
