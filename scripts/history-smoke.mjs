import './windows-hidden-spawn-guard.mjs';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { CodexHistoryReader } from '../packages/codex-adapter/dist/index.js';

const directory = await mkdtemp(join(tmpdir(), 'codex-manage-history-'));
const executable = join(directory, 'fixture.mjs');
const log = join(directory, 'methods.jsonl');
const sidebarFile = join(directory, '.codex-global-state.json');
// Synthetic subprocess exercises the real JSONL boundary without user history or credentials.
await writeFile(
  executable,
  `#!/usr/bin/env node
import { createInterface } from 'node:readline';
import { appendFileSync, readFileSync, existsSync } from 'node:fs';
createInterface({input:process.stdin}).on('line', line => {
 const r=JSON.parse(line); appendFileSync(${JSON.stringify(log)},r.method+'\\n');
 if(r.id===undefined)return;
 let result={};
 if(r.method==='project/list')result=r.params.cursor?{data:[{id:'later-project',name:'fixture',roots:[{path:'/later'}]}]}:{data:[{id:'p',name:'fixture',roots:[{path:'/fixture'}]},{id:'nested',name:'fixture',roots:[{path:'/fixture/nested'}]},{id:'ambiguous-a',name:'first',roots:[{path:'/shared'}]},{id:'ambiguous-b',name:'second',roots:[{path:'/shared'}]}],nextCursor:'projects-page-2'};
 if(r.method==='thread/list')result={data:r.params.cursor?[{id:'page-2-legacy',projectId:null,cwd:'/fixture',name:'older fixture',preview:''},{id:'page-2-project',projectId:null,cwd:'/later',name:'other fixture',preview:''}]:[{id:'t',projectId:'p',name:'fixture thread',preview:''},{id:'legacy',projectId:null,cwd:${JSON.stringify(process.platform === 'win32' ? '/FiXtUrE/' : '/fixture/')},name:'legacy title',preview:''},{id:'nested-thread',projectId:null,cwd:'/fixture/nested',name:'nested title',preview:''},{id:'canonical',projectId:'nested',cwd:'/fixture',name:'assigned title',preview:''},{id:'descendant',projectId:null,cwd:'/fixture/other',name:'unassigned child',preview:''},{id:'ambiguous',projectId:null,cwd:'/shared',name:'ambiguous title',preview:''},{id:'same-name',projectId:null,cwd:'/elsewhere/fixture',name:'different directory',preview:''}],nextCursor:r.params.cursor?null:'threads-page-2'};
 if(r.method==='thread/list' && r.params.archived===true)result={data:[],nextCursor:null};
 if(r.method==='thread/list' && r.params.sectionId)result={data:(existsSync(${JSON.stringify(join(directory, 'server-pins.json'))})?JSON.parse(readFileSync(${JSON.stringify(join(directory, 'server-pins.json'))},'utf8')):[]).map(id=>({id})),nextCursor:null};
 const command={id:'cmd',type:'commandExecution',command:'printf fixture',aggregatedOutput:'fixture',exitCode:0,status:'completed'};
 const diff={id:'diff',type:'fileChange',changes:[{path:'example.swift',diff:'+fixture'}],status:'completed'};
 const tool={id:'mcp',type:'mcpToolCall',server:'fixture',tool:'inspect',status:'failed',error:{message:'fixture failure'}};
 if(r.method==='thread/read')result={thread:{id:r.params.threadId,...(r.params.threadId==='legacy'?{turns:[{items:[command,diff,tool]}]}:{historyMode:'paginated'})}};
 if(r.method==='thread/items/list')result={data:r.params.cursor?[{item:tool},{item:{id:'old',type:'userMessage',content:[{type:'text',text:'older'}]}}]:[{item:{id:'a',type:'agentMessage',text:'reply'}},{item:diff},{item:command},{item:{id:'u',type:'userMessage',content:[{type:'text',text:'prompt'}]}}],nextCursor:r.params.cursor?null:'older-page'};
 if(r.method==='thread/list' && r.params.sortKey==='recency_at' && existsSync(${JSON.stringify(join(directory, 'legacy-sort'))})) {
  process.stdout.write(JSON.stringify({id:r.id,error:{code:-32602,message:'Unsupported recency sort'}})+'\\n');return;
 }
 process.stdout.write(JSON.stringify({id:r.id,result})+'\\n');
});
`,
  { mode: 0o700 },
);
// Use the real Node executable on every platform; Windows does not execute
// a Unix shebang. Keep the actual subprocess/stdin/stdout JSONL boundary.
const originalSpawn = childProcess.spawn;
childProcess.spawn = (command, args, options) =>
  command === executable
    ? originalSpawn(process.execPath, [executable, ...args], options)
    : originalSpawn(command, args, options);
syncBuiltinESMExports();
const reader = new CodexHistoryReader('fixture-host', 'Fixture', executable);
try {
  await reader.connect();
  assert(reader.ready);
  const workspace = await reader.workspace();
  assert.equal(workspace.connectionMode, 'history-only');
  assert.deepEqual(workspace.clientType, { id: 'codex', name: 'Codex' });
  assert(workspace.threads.every((t) => t.state === 'unknown' && t.hostId === 'fixture-host'));
  assert.deepEqual(
    workspace.threads.map((t) => [t.id, t.projectId]),
    [
      ['t', 'p'],
      ['legacy', 'p'],
      ['nested-thread', 'nested'],
      ['canonical', 'nested'],
      ['descendant', null],
      ['ambiguous', null],
      ['same-name', null],
    ],
  );
  assert.equal(workspace.threads.find((t) => t.id === 'legacy').title, 'legacy title');
  assert.deepEqual(workspace.threads.find((t) => t.id === 'ambiguous').projectIds, [
    'ambiguous-a',
    'ambiguous-b',
  ]);
  assert.deepEqual(workspace.threads.find((t) => t.id === 'canonical').projectIds, ['nested']);
  assert.deepEqual(workspace.threads.find((t) => t.id === 'descendant').projectIds, []);
  const next = await reader.workspace(workspace.nextProjectCursor, workspace.nextThreadCursor);
  assert.deepEqual(
    next.threads.map((t) => [t.id, t.projectId]),
    [
      ['page-2-legacy', 'p'],
      ['page-2-project', 'later-project'],
    ],
  );
  await assert.rejects(reader.history('other-thread'));
  const first = await reader.history('t');
  assert.deepEqual(
    first.messages.map((m) => m.text),
    ['prompt', 'printf fixture', 'example.swift', 'reply'],
  );
  assert.deepEqual(
    first.messages.map((m) => m.role),
    ['user', 'tool', 'tool', 'assistant'],
  );
  assert.equal(first.messages[1].kind, 'commandExecution');
  assert.equal(first.messages[1].detail, 'fixture\nExit 0');
  assert.equal(first.messages[2].detail, '+fixture');
  assert.equal(first.nextCursor, 'older-page');
  const older = await reader.history('t', first.nextCursor);
  assert.deepEqual(
    older.messages.map((m) => m.text),
    ['older', 'fixture · inspect'],
  );
  assert.equal(older.messages[1].status, 'failed');
  assert.equal(older.messages[1].detail, 'fixture failure');
  assert.equal(older.nextCursor, null);
  const legacy = await reader.history('legacy');
  assert.deepEqual(
    legacy.messages.map((m) => m.kind),
    ['commandExecution', 'fileChange', 'mcpToolCall'],
  );
  // The desktop keeps legacy display IDs separately from app-server IDs.
  // Identical roots must not duplicate explicitly assigned or projectless chats.
  const sidebar = {
    'app-server-project-id-by-legacy-project-id-by-host': {
      ['local:' + directory]: { 'display-a': 'ambiguous-a', 'display-b': 'ambiguous-b' },
      'remote:other': { 'display-a': 'nested' },
    },
    'thread-project-assignments': {
      ambiguous: { projectKind: 'local', projectId: 'display-b' },
      legacy: { projectKind: 'local', projectId: 'display-a' },
      'same-name': { projectKind: 'local', projectId: 'removed-project' },
      descendant: { projectKind: 'local', projectId: 'display-a' },
    },
    'thread-project-membership-host-ids': { descendant: 'other-host' },
    'projectless-thread-ids': ['legacy'],
    'pinned-thread-ids': ['canonical'],
    'app-server-migrated-pinned-thread-ids-by-host': {
      ['local:' + directory]: ['page-2-legacy', 'ambiguous', 'page-2-legacy'],
      'remote:other': ['t'],
    },
  };
  await writeFile(sidebarFile, JSON.stringify(sidebar));
  await writeFile(
    join(directory, 'server-pins.json'),
    JSON.stringify(['page-2-legacy', 'ambiguous']),
  );
  const desktop = new CodexHistoryReader('fixture-host', 'Fixture', executable, sidebarFile);
  try {
    await desktop.connect();
    const snapshot = await desktop.desktopWorkspace();
    assert.equal(snapshot.projects.length, 5);
    assert.equal(snapshot.threads.length, 9);
    assert.equal(snapshot.nextProjectCursor, null);
    assert.equal(snapshot.nextThreadCursor, null);
    assert.deepEqual(snapshot.pinnedThreadIds, ['page-2-legacy', 'ambiguous']);
    assert.deepEqual(snapshot.threads.find((t) => t.id === 'ambiguous').projectIds, [
      'ambiguous-b',
    ]);
    assert.deepEqual(snapshot.threads.find((t) => t.id === 'legacy').projectIds, []);
    assert.equal(snapshot.threads.find((t) => t.id === 'same-name').projectId, null);
    assert.equal(snapshot.threads.find((t) => t.id === 'descendant').projectId, null);
    assert.equal(
      snapshot.threads.find((t) => t.id === 'page-2-project').projectId,
      'later-project',
    );
    sidebar['thread-project-assignments'].ambiguous.projectId = 'display-a';
    sidebar['app-server-migrated-pinned-thread-ids-by-host']['local:' + directory] = [];
    await writeFile(sidebarFile, JSON.stringify(sidebar));
    await writeFile(join(directory, 'server-pins.json'), '[]');
    assert.deepEqual((await desktop.desktopWorkspace()).pinnedThreadIds, []);
    assert.deepEqual(
      (await desktop.desktopWorkspace()).threads.find((t) => t.id === 'ambiguous').projectIds,
      ['ambiguous-a'],
    );
    delete sidebar['app-server-migrated-pinned-thread-ids-by-host']['local:' + directory];
    await writeFile(sidebarFile, JSON.stringify(sidebar));
    // Removing legacy pin state must not override the authoritative server section.
    assert.deepEqual((await desktop.desktopWorkspace()).pinnedThreadIds, []);
    await writeFile(join(directory, 'legacy-sort'), 'unsupported');
    const compatible = await desktop.desktopWorkspace();
    assert.equal(
      compatible.threads.length,
      9,
      'An older server can fall back on read-only catalog ordering',
    );
    assert.equal(compatible.sidebar.version, 1);
    await writeFile(sidebarFile, '{broken');
    await assert.rejects(desktop.desktopWorkspace(), /sidebar metadata unavailable/);
  } finally {
    desktop.close();
  }
  const methods = (await readFile(log, 'utf8')).trim().split('\n');
  assert(
    methods.every((method) =>
      [
        'initialize',
        'initialized',
        'project/list',
        'thread/list',
        'thread/read',
        'thread/items/list',
      ].includes(method),
    ),
  );
  reader.close();
  assert.equal(reader.ready, false);
  await assert.rejects(reader.workspace());
  console.log(
    'History boundary: JSONL handshake, host binding, legacy grouping, canonical and desktop assignments, projectless/host isolation, complete pagination, refresh moves, corrupt sidebar rejection, read-only calls and disconnect passed',
  );
} finally {
  reader.close();
  childProcess.spawn = originalSpawn;
  syncBuiltinESMExports();
  await rm(directory, { recursive: true, force: true });
}
