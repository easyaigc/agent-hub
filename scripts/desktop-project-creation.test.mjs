import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { DesktopProjectCreation } from '../packages/codex-adapter/dist/desktop-project-creation.js';
import { DesktopWorkspaceManagement } from '../packages/codex-adapter/dist/desktop-workspace-management.js';
import { MetadataConnection } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import { CodexRejection } from '../packages/codex-adapter/dist/runtime.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopWorkspaceView } from '../packages/codex-adapter/dist/desktop-workspace-view.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

const target = {
  agentId: 'codex',
  hostId: 'host-a',
  workspaceId: 'host-a',
  runtimeId: 'desktop-runtime',
};

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
      return this.workspace();
    };
    DesktopWorkspaceView.prototype.workspace = async function () {
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
