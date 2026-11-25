import assert from 'node:assert/strict';
import test from 'node:test';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { DesktopProjectCreation } from '../packages/codex-adapter/dist/desktop-project-creation.js';
import { DesktopWorkspaceManagement } from '../packages/codex-adapter/dist/desktop-workspace-management.js';
import { MetadataConnection } from '../packages/codex-adapter/dist/desktop-thread-preparer.js';
import { CodexRejection } from '../packages/codex-adapter/dist/runtime.js';

const target = {
  agentId: 'codex',
  hostId: 'host-a',
  workspaceId: 'host-a',
  runtimeId: 'desktop-runtime',
};
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
