import './windows-hidden-spawn-guard.mjs';
import assert from 'node:assert/strict';
import {
  mkdtemp,
  writeFile,
  chmod,
  mkdir,
  readFile,
  rm,
  symlink,
  realpath,
  cp,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  createHash,
  randomUUID,
  generateKeyPairSync,
  createPublicKey,
  diffieHellman,
  hkdfSync,
  createDecipheriv,
} from 'node:crypto';
import https from 'node:https';
import { request as httpRequest } from 'node:http';
import childProcess, { spawnSync } from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { fsyncSync, fstatSync, readFileSync } from 'node:fs';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { PrivateConnections } from '../apps/host-agent/dist/private-connections.js';
import { codexPrivateConnectionContext } from '../packages/protocol/dist/index.js';
import { RuntimePersistenceError } from '../packages/codex-adapter/dist/runtime-persistence.js';
import { approvalRequest } from '../packages/codex-adapter/dist/approval-requests.js';
import {
  RuntimeSession,
  replyIntentFingerprint,
} from '../packages/codex-adapter/dist/runtime-session.js';

const directory = await mkdtemp(join(tmpdir(), 'codex-manage-runtime-'));
const appForm = {
  mode: 'form',
  serverName: 'node_repl',
  message: 'Allow Calculator?',
  requestedSchema: { type: 'object', properties: {} },
  _meta: {
    codex_approval_kind: 'mcp_tool_call',
    connector_id: 'computer-use',
    persist: ['session', 'always'],
    tool_params: { app: 'Calculator' },
  },
};
const projectApp = (params) => approvalRequest('mcpServer/elicitation/request', params);
assert.equal(projectApp(appForm).decisionLabels.accept, '本会话允许');
assert.deepEqual(projectApp(appForm).decisions, ['accept', 'acceptAlways', 'decline', 'cancel']);
assert.deepEqual(projectApp(appForm).responses.accept, {
  action: 'accept',
  content: {},
  _meta: { persist: 'session' },
});
assert.match(projectApp(appForm).detail, /截屏/);
assert.deepEqual(projectApp({ ...appForm, _meta: {} }).decisions, ['accept', 'decline', 'cancel']);
assert.deepEqual(
  projectApp({ ...appForm, _meta: { persist: 'always', tool_params: { plan_token: 'bound' } } })
    .decisions,
  ['accept', 'decline', 'cancel'],
);
for (const schema of [
  { type: 'object', properties: { approved: { type: 'boolean', const: false } } },
  { type: 'object', properties: { path: { type: 'string' } }, required: ['path'] },
  { type: 'object', properties: {}, required: 'invalid' },
  { type: 'object', properties: {}, allOf: [{ minProperties: 1 }] },
])
  assert.deepEqual(projectApp({ ...appForm, requestedSchema: schema }).decisions, [
    'decline',
    'cancel',
  ]);
assert.deepEqual(
  projectApp({ ...appForm, mode: 'url', url: 'https://example.invalid' }).decisions,
  ['decline', 'cancel'],
);
assert.deepEqual(projectApp({ ...appForm, mode: 'openai/userVerification' }).decisions, [
  'decline',
  'cancel',
]);
assert.deepEqual(
  projectApp({
    ...appForm,
    requestedSchema: {
      type: 'object',
      properties: { approved: { type: 'boolean' } },
      required: ['approved'],
    },
  }).responses.accept,
  { action: 'accept', content: { approved: true }, _meta: { persist: 'session' } },
);
const permissions = approvalRequest('item/permissions/requestApproval', {
  permissions: { fileSystem: { write: ['/fixture'] } },
});
assert.deepEqual(permissions.responses.accept, {
  permissions: { fileSystem: { write: ['/fixture'] } },
  scope: 'turn',
});
assert.deepEqual(permissions.responses.decline, { permissions: {}, scope: 'turn' });
const root = join(directory, 'workspace');
await mkdir(root);
await mkdir(join(root, 'child'));
const fixture = join(directory, 'codex-fixture.cjs');
// This fixture tests our protocol boundary, not model quality or desktop sharing.
await writeFile(
  fixture,
  `#!${process.execPath}
const readline = require('node:readline');
const fs = require('node:fs');
const path = require('node:path');
let count=0, turnCount=0; const threads=new Map(); let pendingApproval;
const send = value=>process.stdout.write(JSON.stringify(value)+'\\n');
const event=(method,params)=>send({method,params});
const emptyFile=${JSON.stringify(join(directory, 'empty-thread'))};
const planWire=${JSON.stringify(join(directory, 'plan-wire'))};
const complete=(threadId,id)=>{const thread=threads.get(threadId);if(thread?.active!==id)return;thread.active=null;event('turn/completed',{threadId,turn:{id,status:'completed',items:[]}});};
readline.createInterface({input:process.stdin}).on('line', line=>{
 const m=JSON.parse(line), p=m.params||{}; let result={};
 if(!m.method){if(pendingApproval){fs.writeFileSync(${JSON.stringify(join(directory, 'approval-response'))},JSON.stringify(m));const a=pendingApproval;pendingApproval=null;event('serverRequest/resolved',{threadId:a.threadId,requestId:m.id});complete(a.threadId,a.turnId);}return;}
 if(m.method==='initialize') result={userAgent:'fixture'};
 else if(m.method==='config/read') result={config:fs.existsSync(${JSON.stringify(join(directory, 'permissions.json'))})?JSON.parse(fs.readFileSync(${JSON.stringify(join(directory, 'permissions.json'))},'utf8')):{}};
 else if(m.method==='thread/start'){fs.writeFileSync(${JSON.stringify(join(directory, 'start-wire'))},JSON.stringify(p));const id='thread-'+process.pid+'-'+(++count);const t={id,turns:[],historyMode:path.basename(p.cwd)==='child'?'paginated':'legacy'};threads.set(id,t);if(path.basename(p.cwd)==='child')fs.writeFileSync(emptyFile,id);result={thread:t};}
 else if(m.method==='thread/resume'){
  if(!threads.has(p.threadId)&&fs.existsSync(emptyFile)&&fs.readFileSync(emptyFile,'utf8')===p.threadId){send({id:m.id,error:{code:-32000,message:'no rollout found for thread id'}});return;}
  const t=threads.get(p.threadId)||{id:p.threadId,turns:[],historyMode:'legacy'};threads.set(p.threadId,t);result={thread:t};
 }
 else if(m.method==='collaborationMode/list')result={data:[{mode:'plan',name:'Plan',model:'fixture'},{mode:'default',name:'Default',model:'fixture'}]};
 else if(m.method==='model/list') result={data:p.cursor ? [{id:'alt-id',model:'fixture-fast',serviceTiers:[{id:'priority',name:'Fast'}],displayName:'Fixture Fast',isDefault:false,defaultReasoningEffort:'medium',supportedReasoningEfforts:[{reasoningEffort:'low'},{reasoningEffort:'medium'}]}, {id:'hidden',displayName:'Hidden',hidden:true}] : [{id:'fixture',displayName:'Fixture',isDefault:true,defaultReasoningEffort:'high',supportedReasoningEfforts:[{reasoningEffort:'low'},{reasoningEffort:'medium'},{reasoningEffort:'high'},{reasoningEffort:'xhigh'}]}],nextCursor:p.cursor ? null : 'models-page-two'};
 else if(m.method==='turn/start'){
  fs.writeFileSync(${JSON.stringify(join(directory, 'turn-wire'))},JSON.stringify(p));
  const threadId=p.threadId,id='turn-'+process.pid+'-'+(++turnCount);const t=threads.get(threadId);t.active=id;result={turn:{id,status:'inProgress',items:[]}};
  const text=p.input.find(v=>v.type==='text')?.text||'';
  if(p.collaborationMode)fs.writeFileSync(planWire,JSON.stringify(p));
  setTimeout(()=>{
   event('turn/started',{threadId,turn:{id,status:'inProgress'}});
   event('item/started',{threadId,turnId:id,item:{id:'u-'+id,type:'userMessage',clientId:p.clientUserMessageId,content:p.input}});
   event('item/started',{threadId,turnId:id,item:{id:'a-'+id,type:'agentMessage',text:''}});
   if(text.startsWith('compact'))event('item/started',{threadId,turnId:id,item:{id:'compact-'+id,type:'contextCompaction'}});
   event('item/agentMessage/delta',{threadId,itemId:'a-'+id,delta:'stream one '});
   event('turn/plan/updated',{threadId,plan:[{step:'Read input',status:'in_progress'}]});
   if(text==='approve'){
    pendingApproval={threadId,turnId:id};send({id:'approval-'+id,method:'item/commandExecution/requestApproval',params:{threadId,turnId:id,itemId:'cmd',command:'fixture command',availableDecisions:['accept','decline','cancel']}});
   } else if(text==='approve-app'){
    pendingApproval={threadId,turnId:id};send({id:'app-'+id,method:'mcpServer/elicitation/request',params:{threadId,turnId:id,serverName:'node_repl',mode:'form',message:'Allow Calculator?',requestedSchema:{type:'object',properties:{}},_meta:{persist:'always',tool_params:{app:'com.apple.calculator'}}}});
   } else if(text==='question'){
    pendingApproval={threadId,turnId:id};send({id:'question-'+id,method:'item/tool/requestUserInput',params:{threadId,turnId:id,itemId:'input',isBlocking:true,questions:[{id:'choice',header:'Choice',question:'Choose',options:[{label:'A',description:'a'}]}]}});
   } else if(text.startsWith('disconnect'))setTimeout(()=>process.exit(0),30);
   else if(text!=='hold'&&text!=='compact-hold')setTimeout(()=>{
    if(text==='plan-fixture')event('item/completed',{threadId,turnId:id,item:{id:'plan-'+id,type:'plan',text:'# Runtime plan'}});
    if(text==='compact')event('item/completed',{threadId,turnId:id,item:{id:'compact-'+id,type:'contextCompaction'}});
    event('item/agentMessage/delta',{threadId,itemId:'a-'+id,delta:'stream two'});
    const file=path.join(${JSON.stringify(root)},'result.txt');fs.writeFileSync(file,'verified output');
    event('item/completed',{threadId,item:{id:'a-'+id,type:'agentMessage',text:'[result]('+file+') [again]('+file+')'}});
    event('turn/plan/updated',{threadId,plan:[{step:'Read input',status:'completed'}]});complete(threadId,id);
   },300);
  },10);
 }
 else if(m.method==='turn/steer')result={turnId:p.expectedTurnId};
 else if(m.method==='turn/interrupt'){
  threads.get(p.threadId).active=null;setTimeout(()=>event('turn/completed',{threadId:p.threadId,turn:{id:p.turnId,status:'interrupted',items:[]}}),5);
 }
 else if(m.method!=='initialized'){send({id:m.id,error:{code:-32601,message:'unsupported'}});return;}
 if(m.id!==undefined && !(m.method==='turn/start' && p.input[0]?.text==='disconnect-before-response')){
  if(m.method==='turn/start' && p.input[0]?.text==='late-response')setTimeout(()=>send({id:m.id,result}),600);
  else send({id:m.id,result});
 }
});
`,
);
await chmod(fixture, 0o700);
// Windows cannot execute a Unix shebang. Only this generated fixture uses Node;
// retain the real subprocess/JSONL boundary and leave every other command alone.
const originalSpawn = childProcess.spawn;
childProcess.spawn = (command, args, options) =>
  command === fixture
    ? originalSpawn(process.execPath, [fixture, ...args], options)
    : originalSpawn(command, args, options);
syncBuiltinESMExports();
const token = randomUUID() + randomUUID();
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'managed-runtime',
  CODEX_EXECUTABLE: fixture,
  HOST_AGENT_TOKEN: token,
  HOST_AGENT_PORT: '0',
  HOST_AGENT_STATE_DIR: join(directory, 'state'),
  HOST_AGENT_ROOT: root,
  HOST_AGENT_DISCOVERY: '1',
  HOST_AGENT_DISCOVERY_PORT: '0',
  HOST_AGENT_DISCOVERY_BIND: '127.0.0.1',
  HOST_AGENT_DISCOVERY_BONJOUR: '0',
  HOST_AGENT_HTTPS_ENDPOINTS: '["https://fixture.tail.ts.net:8443"]',
});
let service;
let endpoint;
let hostId;
let epoch = '',
  cursor = 0;
async function api(path, payload, status = 200, authorization = token) {
  const response = await fetch(endpoint + path, {
    method: payload ? 'POST' : 'GET',
    headers: {
      Authorization: 'Bearer ' + authorization,
      'Content-Type': 'application/json',
      'X-Agent-Hub-Approvals': 'mcp-v1',
    },
    body: payload ? JSON.stringify(payload) : undefined,
  });
  assert.equal(response.status, status, path);
  return response.json();
}
async function poll() {
  const batch = await api(
    '/v1/events?' + new URLSearchParams({ hostId, epoch, after: String(cursor) }),
  );
  epoch = batch.epoch;
  cursor = batch.cursor;
  return batch;
}
async function until(threadId, predicate) {
  for (let i = 0; i < 30; i++) {
    const batch = await poll();
    const snapshot = batch.events.find((v) => v.threadId === threadId)?.snapshot;
    if (snapshot && predicate(snapshot)) return snapshot;
  }
  throw Error('Fixture condition timed out');
}
try {
  service = await startRuntimeServer();
  endpoint = `http://127.0.0.1:${service.server.address().port}`;
  await api('/v1/workspace', undefined, 401, 'wrong');
  const workspace = await api('/v1/workspace');
  hostId = workspace.hostId;
  const routesPath = '/v1/connection-routes?hostId=' + hostId;
  await api(routesPath, undefined, 401, 'wrong');
  await api('/v1/connection-routes?hostId=other', undefined, 400);
  await api(routesPath + '&hostId=' + hostId, undefined, 400);
  await api(routesPath + '&unknown=1', undefined, 400);
  const routes = await api(routesPath);
  assert.deepEqual(routes, {
    version: 1,
    target: workspace.readTarget,
    httpsEndpoints: ['https://fixture.tail.ts.net:8443'],
  });
  const suggestionQuery = new URLSearchParams({
    hostId,
    threadId: '00000000-0000-4000-8000-000000000001',
    sourceTurnId: '00000000-0000-4000-8000-000000000002',
    target: JSON.stringify(workspace.readTarget),
  });
  const suggestionPath = '/v1/desktop/composer-suggestion?' + suggestionQuery;
  assert.equal((await api(suggestionPath, undefined, 503)).code, 'not-configured');
  await api(suggestionPath, undefined, 401, 'wrong');
  await api(suggestionPath + '&expression=arbitrary', undefined, 400);
  await api(suggestionPath + '&threadId=duplicate', undefined, 400);
  const badSuggestionTarget = new URLSearchParams(suggestionQuery);
  badSuggestionTarget.set('target', '{}');
  await api('/v1/desktop/composer-suggestion?' + badSuggestionTarget, undefined, 400);
  await api(suggestionPath, { hostId, threadId: suggestionQuery.get('threadId') }, 405);
  const privatePort = service.direct.server.address().port;
  const mobile = generateKeyPairSync('x25519');
  const publicKey = mobile.publicKey
    .export({ type: 'spki', format: 'der' })
    .subarray(-32)
    .toString('base64url');
  const claim = { version: 1, target: workspace.readTarget, publicKey };
  const connect = (payload, headers = {}) =>
    new Promise((done, reject) => {
      const req = https.request(
        {
          host: '127.0.0.1',
          port: privatePort,
          path: '/connection/claim',
          method: 'POST',
          rejectUnauthorized: false,
          headers: {
            'content-type': 'application/json',
            authorization: 'Bearer ' + token,
            ...headers,
          },
        },
        (res) => {
          let value = '';
          res.on('data', (chunk) => (value += chunk));
          res.on('end', () => done({ status: res.statusCode, data: value }));
        },
      );
      req.on('error', reject);
      req.end(JSON.stringify(payload));
    });
  assert.equal((await connect(claim, { Origin: 'https://untrusted.example' })).status, 403);
  assert.equal((await connect(claim, { authorization: '' })).status, 401);
  assert.equal((await connect(claim, { authorization: 'Bearer wrong-host-key' })).status, 401);
  assert.equal(
    (await connect({ ...claim, target: { ...claim.target, runtimeId: 'other' } })).status,
    409,
  );
  assert.equal((await connect({ ...claim, command: 'shell' })).status, 400);
  assert.equal((await connect({ ...claim, publicKey: 'A'.repeat(43) })).status, 400);
  const granted = await connect(claim);
  assert.equal(granted.status, 200);
  assert.equal(granted.data.includes(token), false);
  const reply = JSON.parse(granted.data);
  const secret = diffieHellman({
    privateKey: mobile.privateKey,
    publicKey: createPublicKey({
      key: Buffer.concat([
        Buffer.from('302a300506032b656e032100', 'hex'),
        Buffer.from(reply.ephemeralPublicKey, 'base64url'),
      ]),
      format: 'der',
      type: 'spki',
    }),
  });
  const context = Buffer.from(codexPrivateConnectionContext(claim.target, publicKey));
  const key = Buffer.from(hkdfSync('sha256', secret, Buffer.alloc(0), context, 32));
  const encrypted = Buffer.from(reply.ciphertext, 'base64url');
  const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(reply.nonce, 'base64url'));
  decipher.setAAD(context);
  decipher.setAuthTag(encrypted.subarray(-16));
  const deviceToken = Buffer.concat([
    decipher.update(encrypted.subarray(0, -16)),
    decipher.final(),
  ]).toString();
  assert.notEqual(deviceToken, token);
  assert.equal((await api('/v1/workspace', undefined, 200, deviceToken)).hostId, hostId);
  await api('/v1/workspace', undefined, 401, 'wrong');
  const restoredPrivateConnections = new PrivateConnections(join(directory, 'state'));
  assert.equal(restoredPrivateConnections.accepts(Buffer.from(deviceToken), claim.target), true);
  assert.equal(
    restoredPrivateConnections.accepts(Buffer.from(deviceToken), {
      ...claim.target,
      runtimeId: 'other',
    }),
    false,
  );
  await api('/v1/workspace');
  await api('/v1/discovery/peers?hostId=' + hostId, undefined, 401, 'wrong');
  await api('/v1/discovery/peers', undefined, 400);
  await api('/v1/discovery/peers?hostId=other', undefined, 400);
  await api('/v1/discovery/peers?hostId=' + hostId + '&command=status', undefined, 400);
  await api('/v1/discovery/peers?hostId=' + hostId + '&hostId=' + hostId, undefined, 400);
  const vpnDiscovery = await api('/v1/discovery/peers?hostId=' + hostId);
  assert.equal(vpnDiscovery.version, 1);
  assert.equal(vpnDiscovery.target.hostId, hostId);
  assert.ok(vpnDiscovery.peers.length <= 32);
  assert.equal(JSON.stringify(vpnDiscovery).includes(token), false);
  assert(workspace.capabilities.includes('reply'));
  assert(workspace.capabilities.includes('commands:target-bound:v1'));
  const targetQuery = (target) =>
    '?' + new URLSearchParams({ hostId, target: JSON.stringify(target) });
  const staleCreation = {
    hostId,
    projectId: workspace.projects[0].id,
    clientOperationId: randomUUID(),
  };
  for (const field of ['agentId', 'hostId', 'workspaceId', 'runtimeId'])
    await api(
      '/v1/threads' + targetQuery({ ...workspace.readTarget, [field]: 'stale-target' }),
      staleCreation,
      409,
    );
  await api(
    '/v1/threads' +
      targetQuery({
        ...workspace.readTarget,
        account: { ...workspace.readTarget.account, subject: 'stale-account' },
      }),
    staleCreation,
    400,
  );
  await api(
    '/v1/threads?' + new URLSearchParams({ hostId, target: '{broken' }),
    staleCreation,
    400,
  );
  assert.equal(
    (await api('/v1/workspace')).threads.length,
    workspace.threads.length,
    'Wrong target creates no thread',
  );
  const targetedModels = await api('/v1/models' + targetQuery(workspace.readTarget));
  assert(Array.isArray(targetedModels.models));
  const changedAccountTarget = {
    ...workspace.readTarget,
    account: { authority: 'legacy-fixture', subject: 'changed-account' },
  };
  assert(
    Array.isArray((await api('/v1/models' + targetQuery(changedAccountTarget))).models),
    'Codex account metadata does not change machine connection identity',
  );
  // Keep the actual HTTP body pending after its Query target passes ingress.
  // Only observe the session method: managed mode has no desktop management.
  {
    const originalTarget = service.session.readingTarget;
    const originalManagement = service.session.manageWorkspace;
    let currentTarget = workspace.readTarget;
    const managementCalls = [];
    service.session.readingTarget = () => currentTarget;
    service.session.manageWorkspace = (request) => {
      managementCalls.push(request);
      return { ...request, items: [] };
    };
    try {
      for (const [nextTarget, expectedStatus] of [
        [{ ...workspace.readTarget, runtimeId: 'changed-during-management-body' }, 409],
        [changedAccountTarget, 200],
      ]) {
        currentTarget = workspace.readTarget;
        managementCalls.length = 0;
        const payload = {
          hostId,
          target: nextTarget,
          clientOperationId: randomUUID(),
          action: 'threads:archive',
          ids: ['synthetic-management-thread'],
        };
        const bytes = Buffer.from(JSON.stringify(payload));
        const path = '/v1/workspace-management' + targetQuery(workspace.readTarget);
        const entered = Promise.withResolvers();
        const onRequest = (incoming) => {
          // The original handler runs first and yields while reading this body.
          if (incoming.url === path) entered.resolve();
        };
        service.server.on('request', onRequest);
        let request;
        const response = new Promise((resolve, reject) => {
          request = httpRequest(
            endpoint + path,
            {
              method: 'POST',
              headers: {
                Authorization: 'Bearer ' + token,
                'Content-Type': 'application/json',
                'Content-Length': bytes.length,
              },
            },
            (incoming) => {
              incoming.on('error', reject);
              incoming.resume();
              incoming.on('end', () => resolve(incoming.statusCode));
            },
          );
          request.on('error', reject);
          request.setTimeout(5000, () =>
            request.destroy(Error('Management body fixture timed out')),
          );
          request.flushHeaders();
        });
        try {
          await Promise.race([
            entered.promise,
            response.then(() => assert.fail('Management responded before the body was sent')),
          ]);
          assert.equal(currentTarget, workspace.readTarget, 'Ingress checked Query target A');
          currentTarget = nextTarget;
          request.end(bytes);
          assert.deepEqual(
            { status: await response, calls: managementCalls.length },
            { status: expectedStatus, calls: expectedStatus === 200 ? 1 : 0 },
            'Recheck the Query machine target after body await, without binding Codex account metadata',
          );
          if (expectedStatus === 200) assert.deepEqual(managementCalls, [payload]);
        } finally {
          service.server.off('request', onRequest);
          request.destroy();
        }
      }
    } finally {
      service.session.readingTarget = originalTarget;
      service.session.manageWorkspace = originalManagement;
    }
    console.log(
      'Workspace management HTTP body fence passed: target A to B returns 409 with zero management calls; account-only change reaches the scoped session method.',
    );
  }
  await api('/v1/history?hostId=wrong&threadId=no', undefined, 403);
  await api(
    '/v1/projects',
    { hostId, name: 'bad', directory: '..', clientOperationId: randomUUID() },
    403,
  );
  await symlink(tmpdir(), join(root, 'escape'), process.platform === 'win32' ? 'junction' : 'dir');
  await api(
    '/v1/projects',
    { hostId, name: 'bad', directory: 'escape', clientOperationId: randomUUID() },
    403,
  );
  const newProject = await api('/v1/projects', {
    hostId,
    name: 'Child',
    directory: 'child',
    clientOperationId: 'project-id',
  });
  assert(newProject.projects.some((p) => p.name === 'Child'));
  const create = { hostId, projectId: workspace.projects[0].id, clientOperationId: randomUUID() };
  const thread = await api('/v1/threads' + targetQuery(changedAccountTarget), create);
  const absentReplyId = randomUUID();
  const freshDelivery = await api(
    '/v1/replies/status?' + new URLSearchParams({ hostId, threadId: thread.id, id: absentReplyId }),
  );
  assert.equal(
    freshDelivery.status,
    'unavailable',
    'A fresh command ledger cannot prove historical absence',
  );
  assert.equal(freshDelivery.evidence, null);
  await api(
    '/v1/replies/status?' +
      new URLSearchParams({ hostId, threadId: thread.id, id: absentReplyId, arbitrary: 'no' }),
    undefined,
    400,
  );
  const restoredDirectory = join(directory, 'restored-reply-ledger-fixture');
  await mkdir(restoredDirectory);
  await cp(join(directory, 'state', 'runtime.json'), join(restoredDirectory, 'runtime.json'));
  const restoredSession = new RuntimeSession({
    ...service.session.options,
    directory: restoredDirectory,
  });
  try {
    await restoredSession.connect();
    const proof = restoredSession.replyDeliveryStatus(thread.id, absentReplyId);
    assert.equal(
      proof.status,
      'unavailable',
      'Copying a runtime.json does not prove old in-flight work ended',
    );
    assert.equal(proof.evidence, null);
    assert.equal(proof.hostId, hostId);
    assert.equal(proof.threadId, thread.id);
    assert.equal(proof.clientMessageId, absentReplyId);
    assert.equal(proof.receipt, null);
    assert.equal(
      restoredSession.state.receipts.length,
      0,
      'Inspection does not reserve or execute an input',
    );
  } finally {
    restoredSession.close();
  }
  await replySafetyBoundaries(service.session, thread);
  const httpGate = (() => {
    let resolve;
    const promise = new Promise((done) => {
      resolve = done;
    });
    return { promise, resolve };
  })();
  const httpEntered = (() => {
    let resolve;
    const promise = new Promise((done) => {
      resolve = done;
    });
    return { promise, resolve };
  })();
  const actualValidator = service.session.validateGeneration;
  const fullTarget = service.session.readingTarget();
  const httpIntent = {
    hostId,
    threadId: thread.id,
    clientMessageId: randomUUID(),
    text: 'http immutable boundary',
    attachmentIds: [],
    mode: 'next-turn',
    model: 'fixture',
    target: fullTarget,
  };
  service.session.validateGeneration = async () => {
    httpEntered.resolve();
    await httpGate.promise;
    return new Map();
  };
  const httpSubmission = api('/v1/replies', httpIntent);
  try {
    await httpEntered.promise;
    const query = new URLSearchParams({
      hostId,
      threadId: thread.id,
      id: httpIntent.clientMessageId,
      target: JSON.stringify(fullTarget),
    });
    const confirming = await api('/v1/replies/status?' + query);
    assert.equal(confirming.status, 'confirming');
    assert.equal(confirming.version, 2);
    await api('/v1/replies/status?' + query, undefined, 401, 'invalid-token');
    await api(
      '/v1/replies',
      { ...httpIntent, target: { ...fullTarget, workspaceId: 'other' } },
      409,
    );
    await api('/v1/replies/status?' + query + '&target=null', undefined, 400);
    httpGate.resolve();
    const accepted = await httpSubmission;
    assert.equal(accepted.fingerprintVersion, 2);
    service.session.runtime.ready = false;
    assert.equal((await api('/v1/replies/status?' + query)).status, 'accepted');
    service.session.runtime.ready = true;
  } finally {
    httpGate.resolve();
    service.session.runtime.ready = true;
    service.session.validateGeneration = actualValidator;
  }
  await until(thread.id, (snapshot) => snapshot.runtime.state === 'idle');

  const defaultStart = JSON.parse(await readFile(join(directory, 'start-wire'), 'utf8'));
  assert.equal(defaultStart.sandbox, 'workspace-write');
  assert.equal(defaultStart.approvalsReviewer, 'guardian_subagent');
  // Exercise computer configuration and automatic-review fallback through the HTTP boundary.
  for (const [config, expected] of [
    [{ sandbox_mode: 'danger-full-access', approval_policy: 'never' }, 'dangerFullAccess'],
    [
      {
        sandbox_mode: 'workspace-write',
        approval_policy: 'on-request',
        approvals_reviewer: 'auto_review',
      },
      'workspaceWrite',
    ],
    [
      { sandbox_mode: 'read-only', approval_policy: 'on-request', approvals_reviewer: 'user' },
      'readOnly',
    ],
  ]) {
    await writeFile(join(directory, 'permissions.json'), JSON.stringify(config));
    const target = await api('/v1/threads', { ...create, clientOperationId: randomUUID() });
    const receipt = await api('/v1/replies', {
      hostId,
      threadId: target.id,
      clientMessageId: randomUUID(),
      text: 'permission-default',
      attachmentIds: [],
      mode: 'next-turn',
    });
    assert.equal(receipt.state, 'submitted');
    const wire = JSON.parse(await readFile(join(directory, 'turn-wire'), 'utf8'));
    assert.equal(wire.sandboxPolicy.type, expected);
    assert.equal(wire.approvalPolicy, config.approval_policy);
    assert.equal(wire.approvalsReviewer, config.approvals_reviewer ?? 'user');
    assert(!('permissions' in wire));
    await until(target.id, (snapshot) => snapshot.runtime.state === 'idle');
  }
  await rm(join(directory, 'permissions.json'));
  assert.equal((await api('/v1/threads', create)).id, thread.id);
  const projectlessCreate = { ...create, projectId: null, clientOperationId: randomUUID() };
  const projectless = await api('/v1/threads', projectlessCreate);
  assert.equal(projectless.projectId, null);
  assert.equal((await api('/v1/threads', projectlessCreate)).id, projectless.id);
  await api('/v1/threads', { ...projectlessCreate, projectId: create.projectId }, 409);
  await api(
    '/v1/threads',
    { ...create, projectId: newProject.projects.find((p) => p.name === 'Child').id },
    409,
  );
  const other = await api('/v1/threads', { ...create, clientOperationId: randomUUID() });
  const compactionThread = await api('/v1/threads', { ...create, clientOperationId: randomUUID() });
  const compactionReply = {
    hostId,
    threadId: compactionThread.id,
    clientMessageId: randomUUID(),
    text: 'compact',
    attachmentIds: [],
    mode: 'next-turn',
  };
  await api('/v1/replies', compactionReply);
  await until(compactionThread.id, (s) =>
    s.messages.some((m) => m.kind === 'contextCompaction' && m.status === 'inProgress'),
  );
  await until(compactionThread.id, (s) =>
    s.messages.some((m) => m.kind === 'contextCompaction' && m.status === 'completed'),
  );
  // Actual HTTP boundary: independent readers, exact content, target isolation and durable receipts.
  const readerId = createHash('sha256').update('mobile-reader-a').digest('hex');
  const secondReader = createHash('sha256').update('mobile-reader-b').digest('hex');
  const readHistoryURL =
    '/v1/history?' + new URLSearchParams({ hostId, threadId: compactionThread.id, readerId });
  const readHistory = await api(readHistoryURL);
  const position = readHistory.thread.readState.latest;
  const target = readHistory.readTarget;
  const reading = { hostId, threadId: compactionThread.id, readerId, position, target };
  assert.equal(
    (await api('/v1/threads/read', reading)).read.contentVersion,
    position.contentVersion,
  );
  assert.deepEqual(await api('/v1/threads/read', reading), await api('/v1/threads/read', reading));
  assert.deepEqual((await api(readHistoryURL)).thread.readState.read, position);
  assert.equal(
    (await api(readHistoryURL.replace(readerId, secondReader))).thread.readState.read,
    undefined,
  );
  assert.equal(
    (await api('/v1/workspace?readerId=' + readerId)).threads.find(
      (t) => t.id === compactionThread.id,
    ).readState.read.revision,
    position.revision,
  );
  await api('/v1/threads/read', { ...reading, hostId: 'wrong' }, 403);
  await api(
    '/v1/threads/read',
    { ...reading, target: { ...target, runtimeId: 'other-client-runtime' } },
    409,
  );
  await api(
    '/v1/threads/read',
    {
      ...reading,
      target: { ...target, account: { authority: 'legacy-fixture', subject: 'other-account' } },
    },
    200,
  );
  await api('/v1/threads/read', { ...reading, shell: 'forbidden' }, 400);
  await api('/v1/threads/read', { ...reading, readerId: '__proto__' }, 400);
  await api('/v1/threads/read', { ...reading, position: { ...position, extra: true } }, 400);
  await api(
    '/v1/threads/read',
    { ...reading, position: { ...position, contentVersion: '0'.repeat(64) } },
    409,
  );
  await api('/v1/replies', {
    ...compactionReply,
    clientMessageId: randomUUID(),
    text: 'new-reply',
  });
  await until(compactionThread.id, (s) => s.runtime.state === 'idle');
  const newerReading = (await api(readHistoryURL)).thread.readState;
  assert(newerReading.latest.revision > position.revision);
  assert.notEqual(newerReading.latest.contentVersion, position.contentVersion);
  assert.deepEqual(newerReading.read, position);
  await api('/v1/threads/read', reading, 409);
  const disk = JSON.parse(await readFile(join(directory, 'state', 'runtime.json'), 'utf8'));
  assert(
    Object.values(disk.threads.find((t) => t.id === compactionThread.id).mobileReaders).some(
      (p) => p.contentVersion === position.contentVersion,
    ),
  );
  const heldCompaction = await api('/v1/replies', {
    ...compactionReply,
    clientMessageId: randomUUID(),
    text: 'compact-hold',
  });
  const held = await until(compactionThread.id, (s) =>
    s.messages.some(
      (m) => m.id === 'compact-' + heldCompaction.turnId && m.status === 'inProgress',
    ),
  );
  await api('/v1/stop', {
    hostId,
    threadId: compactionThread.id,
    turnId: held.runtime.turnId,
  });
  await until(compactionThread.id, (s) =>
    s.messages.some(
      (m) => m.id === 'compact-' + heldCompaction.turnId && m.status === 'interrupted',
    ),
  );
  const bytes = Buffer.from('mobile upload');
  const hash = createHash('sha256').update(bytes).digest('hex');
  async function upload(expectedHash) {
    const r = await fetch(
      endpoint +
        '/v1/attachments?' +
        new URLSearchParams({ hostId, threadId: thread.id, name: 'input.txt' }),
      {
        method: 'POST',
        headers: {
          Authorization: 'Bearer ' + token,
          'Content-Type': 'text/plain',
          'X-Content-SHA256': expectedHash,
        },
        body: bytes,
      },
    );
    return { status: r.status, data: await r.json() };
  }
  assert.equal((await upload('wrong')).status, 400);
  const file = (await upload(hash)).data;
  const request = {
    hostId,
    threadId: thread.id,
    clientMessageId: randomUUID(),
    text: 'hold',
    attachmentIds: [file.id],
    mode: 'next-turn',
  };
  await api('/v1/replies', { ...request, threadId: other.id }, 403);
  const catalog = await api('/v1/models?' + new URLSearchParams({ hostId }));
  assert.deepEqual(
    catalog.models.map((v) => v.id),
    ['fixture', 'fixture-fast'],
  );
  assert.deepEqual(catalog.models[1].supportedEfforts, ['low', 'medium']);
  assert.deepEqual(catalog.models[1].supportedSpeeds, ['standard', 'fast']);
  await api('/v1/replies', { ...request, attachmentIds: [], speed: 'fast' }, 400);
  await api('/v1/replies', { ...request, attachmentIds: [], speed: 'invalid' }, 400);
  await api('/v1/replies', { ...request, attachmentIds: [], speed: true }, 400);
  await api('/v1/replies', { ...request, attachmentIds: [], model: 'not-offered' }, 400);
  await api(
    '/v1/replies',
    { ...request, attachmentIds: [], model: 'fixture-fast', effort: 'xhigh' },
    400,
  );
  await api('/v1/replies', { ...request, attachmentIds: [], permissionMode: 'bad' }, 400);
  const planRequest = {
    ...request,
    clientMessageId: randomUUID(),
    text: 'plan-fixture',
    attachmentIds: [],
    collaborationMode: 'plan',
    model: 'fixture-fast',
    effort: 'medium',
    permissionMode: 'guardian-approvals',
    speed: 'fast',
  };
  await api('/v1/replies', { ...planRequest, collaborationMode: 'invalid' }, 400);
  await api('/v1/replies', { ...planRequest, expectedPlanId: 'stale' }, 409);
  const simultaneous = await Promise.all([
    api('/v1/replies', planRequest),
    api('/v1/replies', planRequest),
  ]);
  assert(simultaneous.every((v) => v.clientMessageId === planRequest.clientMessageId));
  const planReceipt = simultaneous.find((v) => v.turnId);
  assert(planReceipt, 'At least one request confirms the single submission');
  const planResult = await until(
    thread.id,
    (s) => s.runtime.proposedPlan != null && s.runtime.turnId == null,
  );
  assert.equal(
    planResult.runtime.receipts.filter((v) => v.clientMessageId === planRequest.clientMessageId)
      .length,
    1,
  );
  assert.equal(planResult.runtime.collaborationMode, 'plan');
  assert.equal(planResult.runtime.proposedPlan.text, '# Runtime plan');
  assert.equal(planResult.messages.find((v) => v.kind === 'plan').role, 'assistant');
  const wire = JSON.parse(await readFile(join(directory, 'plan-wire'), 'utf8'));
  assert.equal(wire.collaborationMode.mode, 'plan');
  assert.equal(wire.collaborationMode.settings.model, 'fixture-fast');
  assert.equal(wire.collaborationMode.settings.reasoning_effort, 'medium');
  assert(!('permissions' in wire), 'Public App Server needs a concrete sandbox policy');
  assert.equal(wire.sandboxPolicy.type, 'workspaceWrite');
  assert(wire.sandboxPolicy.writableRoots.includes(await realpath(root)));
  assert.equal(wire.approvalPolicy, 'on-request');
  assert.equal(wire.approvalsReviewer, 'guardian_subagent');
  assert.equal(wire.serviceTier, 'priority');
  assert(!('speed' in wire));
  assert.deepEqual(planResult.runtime.generationSettings, {
    model: 'fixture-fast',
    effort: 'medium',
    permissionMode: 'guardian-approvals',
    speed: 'fast',
  });
  assert.equal(wire.collaborationMode.settings.developer_instructions, null);
  assert.equal((await api('/v1/replies', planRequest)).turnId, planReceipt.turnId);
  const implement = {
    ...planRequest,
    clientMessageId: randomUUID(),
    text: 'PLEASE IMPLEMENT THIS PLAN:\n# Runtime plan',
    speed: 'standard',
    collaborationMode: 'default',
    expectedPlanId: planResult.runtime.proposedPlan.id,
    permissionMode: 'full-access',
  };
  await api('/v1/replies', implement);
  await until(thread.id, (s) => s.runtime.turnId == null && s.runtime.proposedPlan == null);
  assert.equal(
    JSON.parse(await readFile(join(directory, 'plan-wire'), 'utf8')).collaborationMode.mode,
    'default',
  );
  const implementedWire = JSON.parse(await readFile(join(directory, 'plan-wire'), 'utf8'));
  assert(!('permissions' in implementedWire));
  assert.deepEqual(implementedWire.sandboxPolicy, { type: 'dangerFullAccess' });
  assert.equal(implementedWire.approvalPolicy, 'never');
  assert.equal(implementedWire.approvalsReviewer, 'user');
  assert.equal(implementedWire.serviceTier, 'default');
  await api('/v1/replies', { ...implement, clientMessageId: randomUUID() }, 409);
  const receipt = await api('/v1/replies', request);
  assert.equal(receipt.state, 'submitted');
  assert.equal((await api('/v1/replies', request)).turnId, receipt.turnId);
  await api('/v1/replies', { ...request, text: 'changed' }, 409);
  const running = await until(
    thread.id,
    (s) => s.runtime.state === 'running' && s.messages.some((m) => m.text.includes('stream one')),
  );
  assert(
    running.messages.some(
      (m) => m.role === 'user' && m.text === 'hold' && m.attachments.some((a) => a.id === file.id),
    ),
  );
  assert.equal(running.runtime.plan[0].status, 'in_progress');
  assert(running.messages.some((m) => m.role === 'assistant' && m.status === 'inProgress'));
  await api(
    '/v1/replies',
    { ...request, clientMessageId: randomUUID(), mode: 'steer', expectedTurnId: 'stale' },
    409,
  );
  await api(
    '/v1/replies',
    {
      ...request,
      clientMessageId: randomUUID(),
      mode: 'steer',
      expectedTurnId: receipt.turnId,
      speed: 'fast',
    },
    400,
  );
  const steer = await api('/v1/replies', {
    ...request,
    clientMessageId: randomUUID(),
    text: 'steer',
    mode: 'steer',
    expectedTurnId: receipt.turnId,
  });
  assert.equal(steer.turnId, receipt.turnId);
  const queued = await api('/v1/replies', {
    ...request,
    clientMessageId: randomUUID(),
    text: 'next',
    mode: 'queue',
  });
  assert.equal(queued.state, 'failed');
  assert.equal(queued.queuePresent, false);
  assert.equal(
    (
      await api('/v1/replies', {
        ...request,
        clientMessageId: queued.clientMessageId,
        text: 'next',
        mode: 'queue',
      })
    ).state,
    'failed',
  );
  for (const invalid of [{ text: 'replacement' }, { expectedTurnId: 3 }])
    await api(
      '/v1/queue/execute',
      {
        hostId,
        threadId: thread.id,
        clientMessageId: queued.clientMessageId,
        ...invalid,
      },
      400,
    );
  await api(
    '/v1/queue/execute',
    {
      hostId,
      threadId: thread.id,
      clientMessageId: queued.clientMessageId,
      expectedTurnId: 'stale',
    },
    409,
  );
  await api(
    '/v1/queue/cancel',
    {
      hostId,
      threadId: thread.id,
      clientMessageId: queued.clientMessageId,
    },
    409,
  );
  await api('/v1/stop', { hostId, threadId: thread.id, turnId: 'stale' }, 409);
  await api('/v1/stop', { hostId, threadId: thread.id, turnId: receipt.turnId });
  await until(thread.id, (s) => s.runtime.state === 'idle');
  await api('/v1/replies', {
    ...request,
    clientMessageId: randomUUID(),
    text: 'approve-app',
    attachmentIds: [],
  });
  const appWaiting = await until(thread.id, (s) => s.runtime.approvals.length > 0);
  const appApproval = appWaiting.runtime.approvals[0];
  assert.equal(appApproval.title, 'Allow Calculator?');
  assert.match(appApproval.detail, /com.apple.calculator/);
  assert.equal(appApproval.decisionLabels.acceptAlways, '始终允许');
  assert.equal(appApproval.responses, undefined, 'Private response mapping stays on Host');
  assert.equal(appApproval.desktopMethod, undefined);
  const legacy = await fetch(
    endpoint + '/v1/history?' + new URLSearchParams({ hostId, threadId: thread.id }),
    { headers: { Authorization: 'Bearer ' + token } },
  ).then((r) => r.json());
  assert.deepEqual(legacy.runtime.approvals[0].decisions, ['decline']);
  assert.match(legacy.runtime.approvals[0].detail, /更新手机/);
  assert.deepEqual(
    appWaiting.runtime.approvals[0].decisions,
    ['accept', 'acceptAlways', 'decline', 'cancel'],
    'Legacy projection cannot mutate current or other clients',
  );
  const appAnswer = {
    hostId,
    threadId: thread.id,
    approvalId: appApproval.id,
    turnId: appApproval.turnId,
    decision: 'acceptAlways',
  };
  await api('/v1/approvals', { ...appAnswer, decision: 'acceptForSession' }, 400);
  await api('/v1/approvals', appAnswer);
  await api('/v1/approvals', appAnswer, 409);
  // The API confirms dispatch; the fixture writes its response before emitting idle.
  await until(thread.id, (s) => s.runtime.state === 'idle');
  assert.deepEqual(
    JSON.parse(await readFile(join(directory, 'approval-response'), 'utf8')).result,
    { action: 'accept', content: {}, _meta: { persist: 'always' } },
  );
  await api('/v1/replies', {
    ...request,
    clientMessageId: randomUUID(),
    text: 'approve',
    attachmentIds: [],
  });
  const waiting = await until(thread.id, (s) => s.runtime.approvals.length > 0);
  const approval = waiting.runtime.approvals[0];
  const answer = {
    hostId,
    threadId: thread.id,
    approvalId: approval.id,
    turnId: approval.turnId,
    decision: 'accept',
  };
  await api('/v1/approvals', { ...answer, threadId: other.id }, 409);
  await api('/v1/approvals', answer);
  await api('/v1/approvals', answer, 409);
  await until(thread.id, (s) => s.runtime.state === 'idle');
  await api('/v1/replies', {
    ...request,
    clientMessageId: randomUUID(),
    text: 'question',
    attachmentIds: [],
  });
  const questions = await until(thread.id, (s) =>
    s.runtime.approvals.some((a) => a.kind === 'question'),
  );
  const question = questions.runtime.approvals[0];
  await api(
    '/v1/approvals',
    { hostId, threadId: thread.id, approvalId: question.id, turnId: question.turnId, answers: {} },
    400,
  );
  await api('/v1/approvals', {
    hostId,
    threadId: thread.id,
    approvalId: question.id,
    turnId: question.turnId,
    answers: { choice: ['A'] },
  });
  await until(thread.id, (s) => s.runtime.state === 'idle');
  await api('/v1/replies', {
    ...request,
    clientMessageId: randomUUID(),
    text: 'complete',
    attachmentIds: [],
  });
  const autoQueue = await api('/v1/replies', {
    ...request,
    clientMessageId: randomUUID(),
    text: 'queued complete',
    model: 'fixture-fast',
    speed: 'fast',
    mode: 'queue',
    attachmentIds: [],
  });
  assert.equal(autoQueue.state, 'failed');
  await until(thread.id, (s) => s.runtime.state === 'idle');
  const directOutput = await api('/v1/replies', {
    ...request,
    clientMessageId: randomUUID(),
    text: 'queued complete',
    model: 'fixture-fast',
    speed: 'fast',
    attachmentIds: [],
  });
  const done = await until(thread.id, (s) =>
    s.runtime.receipts.some(
      (r) => r.clientMessageId === directOutput.clientMessageId && r.state === 'completed',
    ),
  );
  assert.equal(done.runtime.generationSettings.speed, 'fast');
  const output = done.messages.flatMap((m) => m.attachments).find((a) => a.name === 'result.txt');
  assert(
    done.messages.some(
      (m) => m.role === 'assistant' && m.attachments.length === 1 && m.text.includes('[again]'),
    ),
  );
  assert(output);
  const download = await fetch(
    endpoint +
      '/v1/attachments?' +
      new URLSearchParams({ hostId, threadId: thread.id, id: output.id }),
    { headers: { Authorization: 'Bearer ' + token } },
  );
  assert.equal(download.status, 200);
  assert.equal(await download.text(), 'verified output');
  await api(
    '/v1/attachments?' + new URLSearchParams({ hostId, threadId: other.id, id: output.id }),
    undefined,
    404,
  );
  const reset = await api(
    '/v1/events?' + new URLSearchParams({ hostId, epoch: 'expired', after: '999999' }),
  );
  assert(reset.reset);
  assert(reset.events.length >= 2);
  const targetedReset = await api(
    '/v1/events?' +
      new URLSearchParams({ hostId, threadId: thread.id, epoch: 'expired', after: '0' }),
  );
  assert(targetedReset.reset);
  assert.equal(targetedReset.events.length, 1);
  assert.equal(targetedReset.events[0].threadId, thread.id);
  for (const path of [
    '/v1/workspace',
    '/v1/history?' + new URLSearchParams({ hostId, threadId: thread.id }),
    '/v1/events?' + new URLSearchParams({ hostId, threadId: thread.id, epoch: 'expired' }),
  ]) {
    const response = await fetch(endpoint + path, {
      headers: { Authorization: 'Bearer ' + token, 'Accept-Encoding': 'gzip' },
    });
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('content-encoding'), 'gzip', path);
    const value = await response.json(); // Fetch, like URLSession, decodes the wire encoding.
    assert.equal(value.hostId, hostId);
    if (value.events) assert.equal(value.events[0].threadId, thread.id);
    if (value.messages) assert(value.messages.length > 0);
  }
  await api(
    '/v1/events?' + new URLSearchParams({ hostId, threadId: 'unregistered', after: '0' }),
    undefined,
    404,
  );
  assert.equal((await api('/v1/models?hostId=' + hostId)).models[0].id, 'fixture');
  const late = await api('/v1/replies', {
    ...request,
    clientMessageId: randomUUID(),
    text: 'late-response',
    attachmentIds: [],
  });
  assert.equal(late.state, 'completed');
  const lateHistory = await api(
    '/v1/history?' + new URLSearchParams({ hostId, threadId: thread.id }),
  );
  assert.equal(lateHistory.runtime.state, 'idle');
  assert.equal(
    lateHistory.runtime.turnId,
    null,
    'A late start response cannot revive a completed turn',
  );
  const persisted = JSON.parse(await readFile(join(directory, 'state', 'runtime.json'), 'utf8'));
  assert.equal(
    persisted.receipts.find((r) => r.clientMessageId === directOutput.clientMessageId).state,
    'completed',
  );
  // Exercise failure through the actual HTTP command boundary, with no model.
  const persistence = service.session.persistence;
  const durableSave = persistence.save.bind(persistence);
  const inject = (failedWrite) => {
    let writes = 0;
    persistence.save = (state) => {
      if (++writes === failedWrite)
        throw new RuntimePersistenceError(
          'publish',
          Object.assign(new Error('fixture'), { code: 'EACCES' }),
        );
      durableSave(state);
    };
  };
  const preWire = {
    ...request,
    clientMessageId: randomUUID(),
    text: 'never executed',
    attachmentIds: [],
  };
  const previousWire = await readFile(join(directory, 'turn-wire'));
  inject(1);
  await api('/v1/replies', preWire, 503);
  persistence.save = durableSave;
  assert.equal((await api('/v1/replies', preWire)).state, 'failed');
  assert.deepEqual(await readFile(join(directory, 'turn-wire')), previousWire);
  assert.equal(
    service.session.state.receipts.filter(
      (value) => value.clientMessageId === preWire.clientMessageId,
    ).length,
    1,
  );
  // Failure after execution remains an HTTP failure even if the recovery
  // receipt write succeeds. Reusing the ID can only query, never execute again.
  const postWire = { ...request, clientMessageId: randomUUID(), text: 'hold', attachmentIds: [] };
  const beforePostWire = await readFile(join(directory, 'turn-wire'));
  let postWireFailed = false;
  persistence.save = (state) => {
    if (!postWireFailed && !readFileSync(join(directory, 'turn-wire')).equals(beforePostWire)) {
      postWireFailed = true;
      throw new RuntimePersistenceError(
        'publish',
        Object.assign(new Error('fixture'), { code: 'EACCES' }),
      );
    }
    durableSave(state);
  };
  await api('/v1/replies', postWire, 503);
  assert.equal(
    postWireFailed,
    true,
    'The injected failure must follow an actual fixture execution write',
  );
  persistence.save = durableSave;
  const acceptedWire = await readFile(join(directory, 'turn-wire'));
  const uncertain = await api('/v1/replies', postWire);
  assert.equal(uncertain.state, 'uncertain');
  assert.deepEqual(await readFile(join(directory, 'turn-wire')), acceptedWire);
  await until(thread.id, (snapshot) => snapshot.runtime.state === 'running');
  await api('/v1/stop', { hostId, threadId: thread.id, turnId: uncertain.turnId });
  await until(thread.id, (snapshot) => snapshot.runtime.state === 'idle');
  // A real delayed session save throws, while health/workspace stay alive.
  persistence.save = () => {
    throw new RuntimePersistenceError('protect', { code: 'ETIMEDOUT' });
  };
  service.session.emit(thread.id, 'isolated-save-failure');
  await new Promise((resolve) => setTimeout(resolve, 250));
  await Promise.all(Array.from({ length: 10 }, () => api('/healthz')));
  await api('/v1/workspace');
  persistence.save = durableSave;
  service.session.save();
  const crashRequest = {
    ...request,
    clientMessageId: randomUUID(),
    text: 'disconnect',
    attachmentIds: [],
  };
  const empty = await api('/v1/threads', {
    ...create,
    projectId: newProject.projects.find((p) => p.name === 'Child').id,
    clientOperationId: randomUUID(),
  });
  await api('/v1/replies', crashRequest);
  await until(thread.id, (s) => s.runtime.state === 'unknown');
  assert.equal((await api('/healthz')).capabilities.includes('reply'), false);
  const crashReceipt = await api(
    '/v1/receipts?' + new URLSearchParams({ hostId, id: crashRequest.clientMessageId }),
  );
  assert.equal(crashReceipt.state, 'uncertain');
  let recovered = false;
  for (let i = 0; i < 30; i++) {
    if ((await api('/healthz')).capabilities.includes('reply')) {
      recovered = true;
      break;
    }
    await new Promise((done) => setTimeout(done, 100));
  }
  assert(recovered, 'Managed runtime reconnects without replaying an uncertain reply');
  const restored = await Promise.all(
    [1, 2].map(() => api('/v1/history?' + new URLSearchParams({ hostId, threadId: empty.id }))),
  );
  assert(
    restored.every(
      (h) => h.threadId === empty.id && h.thread.id === empty.id && !('codexId' in h.thread),
    ),
  );
  const physical = JSON.parse(
    await readFile(join(directory, 'state', 'runtime.json'), 'utf8'),
  ).threads.find((t) => t.id === empty.id).codexId;
  assert(
    physical && physical !== empty.id && physical.endsWith('-1'),
    'Concurrent empty restores create one owned replacement',
  );
  const emptyReply = await api('/v1/replies', {
    ...request,
    threadId: empty.id,
    clientMessageId: randomUUID(),
    text: 'hold',
    attachmentIds: [],
  });
  await until(empty.id, (h) => h.runtime.state === 'running');
  await api('/v1/stop', { hostId, threadId: empty.id, turnId: emptyReply.turnId });
  await until(empty.id, (h) => h.runtime.state === 'idle');
  assert.equal((await api('/v1/replies', crashRequest)).state, 'uncertain');
  const pendingCrash = {
    ...request,
    clientMessageId: randomUUID(),
    text: 'disconnect-before-response',
    attachmentIds: [],
  };
  assert.equal((await api('/v1/replies', pendingCrash)).state, 'uncertain');
  assert.equal(
    (await api('/v1/receipts?' + new URLSearchParams({ hostId, id: pendingCrash.clientMessageId })))
      .state,
    'uncertain',
  );
  for (let i = 0; i < 30 && !(await api('/healthz')).capabilities.includes('reply'); i++)
    await new Promise((done) => setTimeout(done, 100));
  await api('/v1/history?' + new URLSearchParams({ hostId, threadId: empty.id }), undefined, 502);
  persistence.save = () => {
    throw new RuntimePersistenceError('publish', { code: 'EACCES' });
  };
  assert.throws(
    () => service.close(),
    (error) => error.status === 503,
  );
  assert.equal(service.server.listening, false, 'A failed final save still closes HTTP');
  assert.equal(service.session.closed, true);
  assert.equal(service.session.persistence.status.backgroundSave, 'idle');
  console.log(
    'Runtime boundary passed: authentication, target isolation, project roots/symlinks, creation idempotency, upload hash, streaming/progress, reply deduplication, steer, queue execution/cancel, stop, approval races, user questions, downloads, event resync, durable receipts, failed commits before/after execution without replay, background failure HTTP survival, failed shutdown cleanup, disconnect before/after RPC response and safe reconnection.',
  );
} finally {
  try {
    service?.close();
  } finally {
    childProcess.spawn = originalSpawn;
    syncBuiltinESMExports();
    await rm(directory, { recursive: true, force: true });
  }
}

async function replySafetyBoundaries(template, thread) {
  const target = template.readingTarget();
  const seed = {
    hostId: template.hostId,
    projects: structuredClone(template.state.projects),
    threads: [
      {
        ...structuredClone(template.state.threads.find((v) => v.id === thread.id)),
        turnId: null,
        state: 'idle',
        messages: [],
      },
    ],
    receipts: [],
    attachments: [],
    creations: {},
    historyCacheVersion: 1,
  };
  const sessions = [];
  let checks = 0;
  const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
  const privateJson = async (file, value) => {
    await writeFile(file, JSON.stringify(value), { mode: 0o600 });
    await chmod(file, 0o600);
  };
  const make = async (label, checkpoint) => {
    const stateDir = join(directory, 'reply-safety-' + label);
    await mkdir(stateDir, { recursive: true, mode: 0o700 });
    await privateJson(join(stateDir, 'runtime.json'), seed);
    return configure(
      new RuntimeSession({
        ...template.options,
        directory: stateDir,
        ...(checkpoint ? { replyCheckpointFile: checkpoint } : {}),
      }),
    );
  };
  const configure = (session) => {
    sessions.push(session);
    session.runtime.ready = true;
    session.loaded.add(thread.id);
    session.computerPermissions = async () => ({});
    const wires = [];
    session.runtime.call = async (method, params) => {
      if (['turn/start', 'turn/steer'].includes(method)) {
        wires.push(structuredClone({ method, params }));
        return { turn: { id: 'fixture-turn-' + wires.length, status: 'completed' } };
      }
      return { data: [] };
    };
    session.fixtureWires = wires;
    return session;
  };
  const request = (session, text = 'immutable fixture input') => ({
    hostId: session.hostId,
    threadId: thread.id,
    clientMessageId: randomUUID(),
    text,
    attachmentIds: [],
    mode: 'next-turn',
  });
  const deferred = () => {
    let resolve;
    const promise = new Promise((done) => {
      resolve = done;
    });
    return { promise, resolve };
  };
  try {
    const delayed = await make('delayed');
    const gate = deferred(),
      entered = deferred();
    delayed.validateGeneration = async () => {
      entered.resolve();
      await gate.promise;
      return new Map();
    };
    const caller = { ...request(delayed), model: 'fixture', effort: 'xhigh' };
    const captured = structuredClone(caller),
      first = delayed.submit(caller, target);
    // Registration is synchronous, before the first async catalogue read begins.
    assert.equal(
      delayed.replyDeliveryStatus(thread.id, captured.clientMessageId, target).status,
      'confirming',
    );
    await entered.promise;
    assert.equal(delayed.state.receipts.length, 0);
    assert.throws(
      () => delayed.replyDeliveryStatus('wrong-thread', captured.clientMessageId, target),
      (e) => e.status === 409,
    );
    await assert.rejects(
      delayed.submit({ ...captured, text: 'different' }, target),
      (e) => e.status === 409,
    );
    for (const wrong of [
      { ...target, workspaceId: 'other' },
      { ...target, runtimeId: 'other' },
      { ...target, hostId: 'other-host' },
    ]) {
      await assert.rejects(delayed.submit(captured, wrong), (e) => e.status === 409);
      assert.throws(
        () => delayed.replyDeliveryStatus(thread.id, captured.clientMessageId, wrong),
        (e) => e.status === 409,
      );
    }
    caller.clientMessageId = 'mutated-id';
    caller.text = 'mutated text';
    caller.attachmentIds.push('bad');
    caller.effort = 'low';
    const duplicate = delayed.submit(structuredClone(captured), target);
    gate.resolve();
    await Promise.all([first, duplicate]);
    assert.equal(
      delayed.fixtureWires.length,
      1,
      'Coalesced same-ID input crosses the wire exactly once',
    );
    assert.equal(delayed.fixtureWires[0].params.input[0].text, captured.text);
    assert.equal(delayed.fixtureWires[0].params.clientUserMessageId, captured.clientMessageId);
    assert.equal(delayed.fixtureWires[0].params.effort, 'xhigh');
    assert.equal(delayed.replySubmissions.size, 0, 'Cleanup uses captured ID');
    assert(Object.isFrozen(delayed.state.receipts[0].reply));
    assert.equal(delayed.state.receipts[0].fingerprint, replyIntentFingerprint(captured, target));
    await delayed.submit(captured, target);
    assert.equal(delayed.fixtureWires.length, 1);
    delayed.runtime.ready = false;
    assert.equal(
      delayed.replyDeliveryStatus(thread.id, captured.clientMessageId, target).status,
      'accepted',
      'Offline positive receipt remains readable',
    );
    assert.equal(
      delayed.replyDeliveryStatus(thread.id, randomUUID(), target).status,
      'unavailable',
    );
    checks++;

    const attachmentSession = await make('attachments');
    const originalBytes = Buffer.from('isolated image fixture'),
      originalPath = join(directory, 'reply-immutable-image');
    await writeFile(originalPath, originalBytes, { mode: 0o600 });
    const attachment = {
      id: randomUUID(),
      hostId: template.hostId,
      threadId: thread.id,
      name: 'fixture.png',
      mediaType: 'image/png',
      path: originalPath,
      sizeBytes: originalBytes.length,
      sha256: hash(originalBytes),
    };
    attachmentSession.state.attachments.push(attachment);
    const attachmentGate = deferred(),
      attachmentEntered = deferred();
    attachmentSession.validateGeneration = async () => {
      attachmentEntered.resolve();
      await attachmentGate.promise;
      return new Map();
    };
    const attachmentInput = {
      ...request(attachmentSession),
      attachmentIds: [attachment.id],
      model: 'fixture',
    };
    const attachmentWork = attachmentSession.submit(attachmentInput, target);
    await attachmentEntered.promise;
    attachment.path = join(directory, 'mutated-path');
    attachment.name = 'mutated';
    attachmentInput.attachmentIds.length = 0;
    attachmentGate.resolve();
    await attachmentWork;
    assert.equal(attachmentSession.fixtureWires[0].params.input[1].path, originalPath);
    assert.equal(
      attachmentSession.receipt(attachmentSession.state.receipts[0].clientMessageId)
        .attachmentSnapshot,
      undefined,
    );
    assert.equal(
      JSON.stringify(attachmentSession.history(thread.id)).includes(originalPath),
      false,
      'Receipt projections never expose attachment paths',
    );
    checks++;

    for (const failAt of [1, 2]) {
      const failed = await make('save-' + failAt),
        originalSave = failed.persistence.save.bind(failed.persistence);
      let writes = 0;
      failed.persistence.save = (state) => {
        if (++writes === failAt)
          throw new RuntimePersistenceError('injected-save', new Error('fixture'));
        originalSave(state);
      };
      const input = request(failed);
      await assert.rejects(failed.submit(input, target), (e) => e.status === 503);
      assert.equal(failed.fixtureWires.length, 0);
      assert.equal(failed.state.receipts.length, 1);
      failed.persistence.save = originalSave;
      await failed.submit(input, target);
      assert.equal(
        failed.fixtureWires.length,
        0,
        'A failed reservation is never silently replayed',
      );
      checks++;
    }
    const flushKinds = {
      'file-and-directory-fsync': ['file', 'directory'],
      'file-flush-only-unproven': ['file'],
    }[template.persistence.status.commandDurability];
    assert(flushKinds, 'Every declared durability contract needs an explicit fault matrix');
    // Target real commit boundaries, not a global flush ordinal: Windows has
    // file-only flushes, so ordinal 4 is already after the execution write.
    for (const boundary of ['reserve', 'dispatch', 'write-hook']) {
      for (const kind of flushKinds) {
        const failed = await make('fsync-' + boundary + '-' + kind);
        const originalCall = failed.callWithOwnerRecovery;
        const injectedError = new Error('fixture ' + boundary + ' ' + kind + ' flush failure');
        let activeBoundary = 'reserve',
          injections = 0,
          wiresAtInjection;
        failed.callWithOwnerRecovery = async function (...args) {
          activeBoundary = 'dispatch';
          const beforeWire = args[5];
          args[5] = () => {
            activeBoundary = 'write-hook';
            try {
              return beforeWire();
            } finally {
              activeBoundary = 'post-hook';
            }
          };
          try {
            return await originalCall.apply(this, args);
          } finally {
            activeBoundary = 'post-dispatch';
          }
        };
        failed.persistence.options.fsync = (fd) => {
          const info = fstatSync(fd);
          assert(info.isFile() || info.isDirectory(), 'Only real ledger file/directory handles');
          const actualKind = info.isDirectory() ? 'directory' : 'file';
          assert(
            flushKinds.includes(actualKind),
            'Actual flush handles must match declared durability',
          );
          if (!injections && activeBoundary === boundary && actualKind === kind) {
            injections++;
            wiresAtInjection = failed.fixtureWires.length;
            throw injectedError;
          }
          fsyncSync(fd);
        };
        const input = request(failed);
        try {
          await assert.rejects(
            failed.submit(input, target),
            (error) =>
              error.status === 503 &&
              error.cause instanceof RuntimePersistenceError &&
              error.cause.stage === kind + '-fsync' &&
              error.cause.cause === injectedError,
          );
          assert.equal(injections, 1, boundary + '/' + kind + ' fault must actually be reached');
          assert.equal(wiresAtInjection, 0, 'The injected commit must precede execution');
          assert.equal(
            failed.fixtureWires.length,
            0,
            boundary + '/' + kind + ' failure prevents execution',
          );
        } finally {
          delete failed.persistence.options.fsync;
          failed.callWithOwnerRecovery = originalCall;
        }
        await failed.submit(input, target);
        assert.equal(
          failed.fixtureWires.length,
          0,
          'Restored storage cannot replay the same failed input',
        );
        checks++;
      }
    }
    for (const phase of ['catalogue', 'permissions']) {
      const closing = await make('close-' + phase),
        wait = deferred(),
        entered = deferred();
      const input = { ...request(closing), ...(phase === 'catalogue' ? { model: 'fixture' } : {}) };
      const suspended = async () => {
        entered.resolve();
        await wait.promise;
        return phase === 'catalogue' ? new Map() : {};
      };
      if (phase === 'catalogue') closing.validateGeneration = suspended;
      else closing.computerPermissions = suspended;
      const work = closing.submit(input, target);
      const rejected = assert.rejects(work, (e) => e.status === 503);
      await entered.promise;
      closing.close();
      const fencedBytes = await readFile(closing.file);
      wait.resolve();
      await rejected;
      assert.equal(closing.fixtureWires.length, 0);
      assert.deepEqual(
        await readFile(closing.file),
        fencedBytes,
        'Old awaited work cannot save after close',
      );
      assert.equal(closing.replySubmissions.size, 0);
      checks++;
    }

    const paginated = await make('close-paginated-catalogue'),
      pageGate = deferred(),
      pageEntered = deferred();
    let pageReads = 0;
    paginated.runtime.call = async (method) => {
      if (method === 'model/list') {
        pageReads++;
        pageEntered.resolve();
        await pageGate.promise;
        return { data: [], nextCursor: 'late-page' };
      }
      throw new Error('Unexpected post-close read');
    };
    const pageWork = paginated.submit({ ...request(paginated), model: 'fixture' }, target);
    const pageRejected = assert.rejects(pageWork, (e) => e.status === 503);
    await pageEntered.promise;
    paginated.close();
    pageGate.resolve();
    await pageRejected;
    assert.equal(pageReads, 1, 'Nested catalogue await cannot issue a second read after close');
    assert.equal(paginated.fixtureWires.length, 0);
    checks++;

    const changedAccount = await make('changed-machine'),
      accountGate = deferred(),
      accountEntered = deferred();
    const initialReadingTarget = changedAccount.readingTarget.bind(changedAccount);
    changedAccount.validateGeneration = async () => {
      accountEntered.resolve();
      await accountGate.promise;
      return new Map();
    };
    const changing = changedAccount.submit(
      { ...request(changedAccount), model: 'fixture' },
      target,
    );
    const targetRejected = assert.rejects(changing, (e) => e.status === 409);
    await accountEntered.promise;
    changedAccount.readingTarget = () => ({
      ...target,
      runtimeId: 'changed-after-await',
    });
    accountGate.resolve();
    await targetRejected;
    await assert.rejects(
      changedAccount.submit(request(changedAccount), changedAccount.readingTarget()),
      (e) => e.status === 409,
      'A changed machine runtime cannot reuse this writer namespace',
    );
    assert.equal(changedAccount.fixtureWires.length, 0);
    changedAccount.readingTarget = initialReadingTarget;
    checks++;

    const accountRetained = await make('same-machine-changed-account'),
      metadataGate = deferred(),
      metadataEntered = deferred();
    const retainedCaller = { ...request(accountRetained), model: 'fixture' };
    accountRetained.validateGeneration = async () => {
      metadataEntered.resolve();
      await metadataGate.promise;
      return new Map();
    };
    const retainedWork = accountRetained.submit(retainedCaller, target);
    await metadataEntered.promise;
    const changedMetadata = {
      ...target,
      account: { authority: 'legacy-fixture', subject: 'changed-after-await' },
    };
    accountRetained.readingTarget = () => changedMetadata;
    metadataGate.resolve();
    await retainedWork;
    assert.equal(accountRetained.fixtureWires.length, 1);
    assert.equal(
      accountRetained.replyDeliveryStatus(
        thread.id,
        retainedCaller.clientMessageId,
        changedMetadata,
      ).status,
      'accepted',
    );
    await accountRetained.submit(retainedCaller, target);
    assert.equal(accountRetained.fixtureWires.length, 1, 'Same immutable input is not replayed');
    checks++;

    const hasDirectoryFlush = flushKinds.includes('directory');
    const assertUnavailable = (status) => {
      assert.equal(status.status, 'unavailable');
      for (const key of [
        'evidence',
        'capturedTarget',
        'fingerprint',
        'fingerprintVersion',
        'receipt',
      ])
        assert.equal(status[key], null, 'Unavailable must not manufacture ' + key);
    };
    const coldDir = join(directory, 'reply-safety-cold');
    await mkdir(coldDir, { mode: 0o700 });
    await privateJson(join(coldDir, 'runtime.json'), seed);
    const coldIntent = { ...request(template), text: 'exact cold continuation' };
    const operations = [
      {
        threadId: thread.id,
        clientMessageId: coldIntent.clientMessageId,
        fingerprint: replyIntentFingerprint(coldIntent, target),
      },
    ];
    const predecessor = spawnSync(process.execPath, ['-e', ''], { encoding: 'utf8' });
    assert.equal(predecessor.status, 0);
    const bootstrapSource = join(directory, 'cold-bootstrap-source'),
      bootstrapLog = join(directory, 'cold-bootstrap-log');
    await writeFile(bootstrapSource, 'isolated operator boundary audit fixture', { mode: 0o600 });
    await writeFile(bootstrapLog, 'isolated exited predecessor fixture', { mode: 0o600 });
    const bootstrapFenceFile = join(directory, 'cold-bootstrap-fence.json');
    const bootstrapFence = {
      version: 2,
      kind: 'operator-audited-legacy-fence-v2',
      oldPid: predecessor.pid,
      target,
      ledgerSha256: hash(await readFile(join(coldDir, 'runtime.json'))),
      boundary: 'receipt-before-model-write-no-deletion',
      singleWriter: true,
      operations,
      sourceFiles: [{ file: bootstrapSource, sha256: hash(await readFile(bootstrapSource)) }],
      logFiles: [{ file: bootstrapLog, sha256: hash(await readFile(bootstrapLog)) }],
    };
    await privateJson(bootstrapFenceFile, bootstrapFence);
    const bootstrapCheckpointFile = join(directory, 'cold-bootstrap-checkpoint.json');
    const bootstrapCheckpoint = {
      version: 2,
      kind: 'reply-ledger-fenced-handoff-v2',
      target,
      oldPid: predecessor.pid,
      ledgerSha256: bootstrapFence.ledgerSha256,
      fenceFile: bootstrapFenceFile,
      fenceSha256: hash(await readFile(bootstrapFenceFile)),
      operations,
    };
    const sessionModule = new URL(
      '../packages/codex-adapter/dist/runtime-session.js',
      import.meta.url,
    ).href;
    const old = spawnSync(
      process.execPath,
      [
        '--input-type=module',
        '-e',
        `
      import {RuntimeSession,replyIntentFingerprint} from ${JSON.stringify(sessionModule)};
      import {writeFileSync} from 'node:fs';
      writeFileSync(${JSON.stringify(bootstrapCheckpointFile)},JSON.stringify({...${JSON.stringify(bootstrapCheckpoint)},newPid:process.pid}),{mode:0o600});
      const session = new RuntimeSession(${JSON.stringify({ ...template.options, directory: coldDir, replyCheckpointFile: bootstrapCheckpointFile })});
      const target=session.readingTarget();
      for(let i=0;i<41;i++){const reply={hostId:session.hostId,threadId:${JSON.stringify(thread.id)},clientMessageId:'retained-'+i,text:'fixture-'+i,attachmentIds:[],mode:'next-turn'};
        session.state.receipts.push({clientMessageId:reply.clientMessageId,hostId:reply.hostId,threadId:reply.threadId,reply,
        fingerprintVersion:2,target,attachmentSnapshot:[],fingerprint:replyIntentFingerprint(reply,target),
        state:['received','queued','submitting','submitted','uncertain','failed','cancelled'][i%7],turnId:null,error:null});}
      session.save();session.close();`,
      ],
      { encoding: 'utf8' },
    );
    assert.equal(old.status, 0, old.stderr);
    const ledgerFile = join(coldDir, 'runtime.json'),
      fenceFile = join(coldDir, 'reply-writer-closed-v2.json');
    const originalColdLedger = await readFile(ledgerFile);
    assert.equal(
      JSON.parse(originalColdLedger).replyLedger.contract,
      hasDirectoryFlush ? 'fsync-before-model-v2' : 'file-flush-only',
      "The exited predecessor must use this platform's real persistence contract",
    );
    const checkpoint = {
      version: 2,
      kind: 'reply-ledger-fenced-handoff-v2',
      target: {
        hostId: target.hostId,
        ...(target.account ? { account: { ...target.account } } : {}),
        agentId: target.agentId,
        runtimeId: target.runtimeId,
        workspaceId: target.workspaceId,
      },
      oldPid: old.pid,
      newPid: process.pid,
      ledgerSha256: hash(await readFile(ledgerFile)),
      fenceFile,
      fenceSha256: hash(await readFile(fenceFile)),
      operations,
    };
    const checkpointFile = join(directory, 'reply-checkpoint.json');
    await privateJson(checkpointFile, checkpoint);
    if (!hasDirectoryFlush) {
      assert.throws(
        () =>
          new RuntimeSession({
            ...template.options,
            directory: coldDir,
            replyCheckpointFile: checkpointFile,
          }),
        /Incompatible reply ledger continuity/,
        'A file-only predecessor cannot certify a directory-durable handoff',
      );
      assert.deepEqual(await readFile(ledgerFile), originalColdLedger);
      checks++;
    }
    const cold = configure(
      new RuntimeSession({
        ...template.options,
        directory: coldDir,
        // File-only recovery still restores receipts, without an absence proof.
        ...(hasDirectoryFlush ? { replyCheckpointFile: checkpointFile } : {}),
      }),
    );
    const proof = cold.replyDeliveryStatus(thread.id, coldIntent.clientMessageId, target);
    assert.equal(proof.version, 2);
    if (hasDirectoryFlush) {
      assert.equal(proof.status, 'not-accepted');
      assert.equal(proof.evidence.kind, 'operator-fenced-checkpoint-v2');
      assert.equal(proof.evidence.fingerprint, operations[0].fingerprint);
      assert.equal(proof.evidence.oldPid, old.pid);
      assert.equal(proof.evidence.newPid, process.pid);
      assert.equal(proof.fingerprintVersion, 2);
    } else assertUnavailable(proof);
    let proofFlushes = 0;
    cold.persistence.options.fsync = () => {
      proofFlushes++;
      throw new Error('negative-proof-fsync-fixture');
    };
    try {
      if (hasDirectoryFlush) {
        assert.throws(
          () => cold.replyDeliveryStatus(thread.id, coldIntent.clientMessageId, target),
          (e) => e.status === 503,
          'An uncommittable successor cannot certify non-acceptance',
        );
        assert.equal(proofFlushes, 1);
      } else {
        assertUnavailable(cold.replyDeliveryStatus(thread.id, coldIntent.clientMessageId, target));
        assert.equal(proofFlushes, 0, 'File-only recovery never attempts an absence-proof commit');
      }
    } finally {
      delete cold.persistence.options.fsync;
    }
    assert.equal(cold.fixtureWires.length, 0);
    checks++;

    assert.equal(
      cold.replyDeliveryStatus(thread.id, randomUUID(), target).status,
      'unavailable',
      'A checkpoint certifies only its explicit IDs',
    );
    assert.equal(
      cold.replyDeliveryStatus(thread.id, coldIntent.clientMessageId).status,
      'unavailable',
      'A negative needs explicit full target',
    );
    assert.equal(cold.state.receipts.length, 41);
    cold.runtime.ready = false;
    try {
      for (let i = 0; i < 41; i++) {
        const retained = cold.state.receipts[i];
        assert.equal(retained.clientMessageId, 'retained-' + i);
        assert.equal(
          cold.replyDeliveryStatus(thread.id, retained.clientMessageId, target).status,
          'accepted',
        );
        await cold.submit(structuredClone(retained.reply), target);
      }
    } finally {
      cold.runtime.ready = true;
    }
    assert.equal(cold.state.receipts[1].state, 'queued', 'Old queued items stay unsubmitted');
    assert.equal(cold.state.receipts[2].state, 'uncertain');
    assert.equal(cold.state.receipts[3].state, 'uncertain');
    assert.equal(
      cold.fixtureWires.length,
      0,
      'Restored receipts, including unknowns, never replay',
    );
    assert.equal(
      cold.receipt('retained-0').clientMessageId,
      'retained-0',
      'The >30 history projection never prunes the ledger',
    );
    await assert.rejects(
      cold.submit({ ...cold.state.receipts[2].reply, text: 'unknown receipt conflict' }, target),
      (e) => e.status === 409,
    );
    for (const i of [1, 2, 3, 4])
      await cold.submit(structuredClone(cold.state.receipts[i].reply), target);
    assert.equal(
      cold.fixtureWires.length,
      0,
      'Reconnection cannot replay queued or uncertain inputs',
    );
    if (hasDirectoryFlush) {
      await assert.rejects(
        cold.submit({ ...coldIntent, text: 'checkpoint conflict' }, target),
        (e) => e.status === 409,
      );
      const continuation = cold.submit(coldIntent, target),
        lateOriginal = cold.submit(structuredClone(coldIntent), target);
      await Promise.all([continuation, lateOriginal]);
      assert.equal(cold.fixtureWires.length, 1, 'Late original and continuation still coalesce');
    } else {
      assertUnavailable(cold.replyDeliveryStatus(thread.id, coldIntent.clientMessageId, target));
      assert.equal(
        cold.fixtureWires.length,
        0,
        'Unavailable is never a continuation authorization',
      );
    }
    checks++;

    assert.throws(
      () => new RuntimeSession({ ...template.options, directory: coldDir }),
      /live or unverified writer/,
      'A second writer cannot start',
    );
    for (const mutation of [
      'fresh',
      'missing',
      'incompatible',
      'incomplete',
      'wrong-sha',
      'old-live',
      'new-pid',
      'wrong-target',
      'bad-fence',
      'legacy',
    ]) {
      const invalidDir = join(directory, 'reply-safety-invalid-' + mutation);
      await mkdir(invalidDir, { mode: 0o700 });
      let state = JSON.parse(originalColdLedger.toString('utf8'));
      if (mutation === 'fresh' || mutation === 'legacy') delete state.replyLedger;
      if (mutation === 'incompatible') state.replyLedger.version = 99;
      if (mutation === 'incomplete') delete state.replyLedger.writerNonce;
      if (mutation !== 'missing') await privateJson(join(invalidDir, 'runtime.json'), state);
      const invalid = {
        ...checkpoint,
        ledgerSha256:
          mutation === 'missing'
            ? checkpoint.ledgerSha256
            : hash(await readFile(join(invalidDir, 'runtime.json'))),
      };
      if (mutation === 'wrong-sha') invalid.ledgerSha256 = '0'.repeat(64);
      if (mutation === 'old-live') invalid.oldPid = process.pid;
      if (mutation === 'new-pid') invalid.newPid++;
      if (mutation === 'wrong-target') invalid.target = { ...target, workspaceId: 'wrong' };
      if (mutation === 'bad-fence') invalid.fenceSha256 = '0'.repeat(64);
      const invalidFile = join(directory, 'reply-invalid-' + mutation + '.json');
      await privateJson(invalidFile, invalid);
      assert.throws(
        () =>
          new RuntimeSession({
            ...template.options,
            directory: invalidDir,
            replyCheckpointFile: invalidFile,
          }),
        undefined,
        'Fresh/missing/incompatible handoff cannot yield a negative: ' + mutation,
      );
      checks++;
    }
    // Explicit legacy migration stays an operator attestation. These are isolated
    // artifact/hash fixtures, not evidence that any real old Host was fenced.
    const legacyColdDir = join(directory, 'reply-safety-legacy-cold');
    await mkdir(legacyColdDir, { mode: 0o700 });
    await privateJson(join(legacyColdDir, 'runtime.json'), seed);
    const auditSource = join(directory, 'legacy-audit-source'),
      auditLog = join(directory, 'legacy-audit-log');
    await writeFile(auditSource, 'isolated operator-audited boundary fixture', { mode: 0o600 });
    await writeFile(auditLog, 'isolated exited-writer log fixture', { mode: 0o600 });
    const legacyFence = {
      version: 2,
      kind: 'operator-audited-legacy-fence-v2',
      oldPid: old.pid,
      target,
      ledgerSha256: hash(await readFile(join(legacyColdDir, 'runtime.json'))),
      boundary: 'receipt-before-model-write-no-deletion',
      singleWriter: true,
      operations,
      sourceFiles: [{ file: auditSource, sha256: hash(await readFile(auditSource)) }],
      logFiles: [{ file: auditLog, sha256: hash(await readFile(auditLog)) }],
    };
    const legacyFenceFile = join(directory, 'legacy-audit-fence.json');
    await privateJson(legacyFenceFile, legacyFence);
    const legacyCheckpoint = {
      ...checkpoint,
      ledgerSha256: legacyFence.ledgerSha256,
      fenceFile: legacyFenceFile,
      fenceSha256: hash(await readFile(legacyFenceFile)),
    };
    const legacyCheckpointFile = join(directory, 'legacy-audit-checkpoint.json');
    await privateJson(legacyCheckpointFile, legacyCheckpoint);
    const migrated = configure(
      new RuntimeSession({
        ...template.options,
        directory: legacyColdDir,
        replyCheckpointFile: legacyCheckpointFile,
      }),
    );
    const migratedStatus = migrated.replyDeliveryStatus(
      thread.id,
      coldIntent.clientMessageId,
      target,
    );
    if (hasDirectoryFlush) assert.equal(migratedStatus.status, 'not-accepted');
    else assertUnavailable(migratedStatus);
    await assert.rejects(
      migrated.submit({ ...coldIntent, text: 'operator scope conflict' }, target),
      (e) => e.status === 409,
      'An operator scope keeps its immutable input even without a usable absence proof',
    );
    assert.equal(migrated.fixtureWires.length, 0, 'Checkpoint restore/query never executes');
    assert.throws(
      () => migrated.replyDeliveryStatus(thread.id, coldIntent.clientMessageId, null),
      (e) => e.status === 400,
    );
    checks++;
    const tamperedDir = join(directory, 'reply-safety-tampered-audit');
    await mkdir(tamperedDir, { mode: 0o700 });
    await privateJson(join(tamperedDir, 'runtime.json'), seed);
    await writeFile(auditLog, 'changed isolated log');
    assert.throws(
      () =>
        new RuntimeSession({
          ...template.options,
          directory: tamperedDir,
          replyCheckpointFile: legacyCheckpointFile,
        }),
      /artifact SHA mismatch/,
    );
    checks++;
    const unauditedDir = join(directory, 'reply-safety-unaudited-v2');
    await mkdir(unauditedDir, { mode: 0o700 });
    await privateJson(join(unauditedDir, 'runtime.json'), seed);
    const unauditedProcess = spawnSync(
      process.execPath,
      [
        '--input-type=module',
        '-e',
        `
      import {RuntimeSession} from ${JSON.stringify(sessionModule)};
      const session=new RuntimeSession(${JSON.stringify({ ...template.options, directory: unauditedDir })});session.save();session.close();`,
      ],
      { encoding: 'utf8' },
    );
    assert.equal(unauditedProcess.status, 0, unauditedProcess.stderr);
    assert.equal(
      JSON.parse(await readFile(join(unauditedDir, 'runtime.json'), 'utf8')).replyLedger.contract,
      hasDirectoryFlush ? 'fsync-before-model-v2' : 'file-flush-only',
    );
    const unauditedFenceFile = join(unauditedDir, 'reply-writer-closed-v2.json');
    const unauditedCheckpoint = {
      ...checkpoint,
      oldPid: unauditedProcess.pid,
      ledgerSha256: hash(await readFile(join(unauditedDir, 'runtime.json'))),
      fenceFile: unauditedFenceFile,
      fenceSha256: hash(await readFile(unauditedFenceFile)),
    };
    const unauditedFile = join(directory, 'unaudited-checkpoint.json');
    await privateJson(unauditedFile, unauditedCheckpoint);
    assert.throws(
      () =>
        new RuntimeSession({
          ...template.options,
          directory: unauditedDir,
          replyCheckpointFile: unauditedFile,
        }),
      hasDirectoryFlush ? /no audited historical coverage/ : /Incompatible reply ledger continuity/,
      'Startup alone cannot certify absence: missing audit on POSIX, file-only continuity on Windows',
    );
    checks++;
    const legacy = await make('legacy');
    legacy.state.receipts.push({
      ...structuredClone(seed.threads[0]),
      hostId: legacy.hostId,
      threadId: thread.id,
      clientMessageId: 'legacy-op',
      reply: request(legacy),
      fingerprint: 'legacy-hash',
      state: 'uncertain',
      turnId: null,
      error: null,
    });
    legacy.save();
    const legacyStatus = legacy.replyDeliveryStatus(thread.id, 'legacy-op', target);
    assert.equal(legacyStatus.status, 'accepted');
    assert.equal(legacyStatus.capturedTarget, null);
    assert.equal(legacyStatus.receipt.target, null);
    assert.equal(legacyStatus.fingerprintVersion, null);
    await assert.rejects(
      legacy.submit({ ...request(legacy), clientMessageId: 'legacy-op' }, target),
      (e) => e.status === 409,
    );
    assert.equal(legacy.fixtureWires.length, 0);
    checks++;
    const windows = await make('windows');
    windows.persistence.options.platform = 'win32';
    assert.equal(windows.persistence.status.commandDurability, 'file-flush-only-unproven');
    assert.equal(
      windows.replyDeliveryStatus(thread.id, randomUUID(), target).status,
      'unavailable',
    );
    checks++;
    console.log(
      `Reply safety auxiliary boundaries passed: ${checks} grouped checks; immutable caller/attachments, pending confirming, exact target conflicts, same-ID single IPC, file/directory fsync faults with zero IPC, close fences, >30 retained receipts, offline positives, live writer rejection, ${hasDirectoryFlush ? 'scoped cold checkpoint' : 'file-only cold recovery with rejected checkpoint'} and unavailable legacy/fresh/missing/incompatible/Windows proofs. No real model or Host was used.`,
    );
  } finally {
    for (const session of sessions.reverse()) session.close();
  }
}
