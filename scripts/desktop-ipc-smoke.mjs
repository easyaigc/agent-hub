import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import { randomUUID } from 'node:crypto';
import { mkdtemp, mkdir, chmod, rm, writeFile, readFile, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import {
  desktopFixtureEndpoint,
  listenDesktopFixture,
  prepareDesktopFixtureIpc,
  prepareDesktopFixtureRuntime,
} from './fixtures/desktop-ipc-transport.mjs';

const {
  DesktopIpc,
  DesktopCommandNotSubmitted,
  DesktopRuntime,
  applyDesktopPatches,
  desktopTurns,
} = await import('../packages/codex-adapter/dist/desktop-ipc.js');
const { desktopFrameLimits } = await import('../packages/codex-adapter/dist/desktop-framing.js');
const { RuntimeSession } = await import('../packages/codex-adapter/dist/runtime-session.js');
const { RuntimePersistence } =
  await import('../packages/codex-adapter/dist/runtime-persistence.js');
const { DesktopWorkspaceView } =
  await import('../packages/codex-adapter/dist/desktop-workspace-view.js');
const { refreshWindowsDesktopOpenState } =
  await import('../packages/codex-adapter/dist/desktop-prepared-bridge.js');
const { NotificationObserver } = await import('../packages/notification-runtime/dist/observer.js');

const directory = await mkdtemp(join(tmpdir(), 'codex-manage-desktop-'));
const ownerDirectory = await mkdtemp(join(tmpdir(), 'codex-owner-cwd-'));
const deniedDirectory = await mkdtemp(join(tmpdir(), 'codex-denied-'));
let cwd = directory,
  messageText = '正在执行';
await chmod(directory, 0o700);
await mkdir(join(directory, 'ipc'), { mode: 0o700 });
const path = desktopFixtureEndpoint(join(directory, 'ipc', 'desktop.sock'));
const previousCodexHome = process.env.CODEX_HOME;
if (process.platform === 'win32') process.env.CODEX_HOME = directory;
let socket,
  following = 0,
  source = 'owner',
  revision = 10,
  running = true;
const commands = [];
const unarchivedHints = [];
let queueFixture = false;
let rejectNextSteer = false;
let fixtureTurns,
  completeFixtureTurns,
  fixtureSettings,
  fixturePermissions,
  fixtureUnread = false;
let queueTurn = 0;
let ownerAvailable = true;
let transientOwnerMisses = 0;
let fixtureRequests = [];
const ownerDiscoveryRequests = [];
let notificationOwnerFixture;
let heldSnapshot;
const state = () => ({
  id: 'thread',
  hostId: 'local',
  hasUnreadTurn: fixtureUnread,
  cwd,
  title: 'Owner-only chat',
  resumeState: 'resumed',
  latestModel: 'desktop-fixture-model',
  latestReasoningEffort: 'xhigh',
  latestThreadSettings: fixtureSettings,
  currentPermissions: fixturePermissions,
  latestCollaborationMode: {
    mode: 'plan',
    settings: {
      model: 'desktop-fixture-model',
      reasoning_effort: 'xhigh',
      developer_instructions: null,
    },
  },
  threadRuntimeStatus: { type: running ? 'active' : 'idle' },
  turns: fixtureTurns ?? [
    {
      turnId: 'turn',
      status: running ? 'inProgress' : 'completed',
      items: [{ id: 'text', type: 'agentMessage', text: messageText }],
    },
  ],
  requests: fixtureRequests,
});
function write(value, fragmented = false) {
  const body = Buffer.from(JSON.stringify(value));
  const header = Buffer.alloc(4);
  header.writeUInt32LE(body.length);
  const frame = Buffer.concat([header, body]);
  if (fragmented) {
    socket.write(frame.subarray(0, 2));
    socket.write(frame.subarray(2, 7));
    socket.write(frame.subarray(7));
  } else socket.write(frame);
}
function snapshot(id = 'thread') {
  if (heldSnapshot?.id === id) {
    heldSnapshot.release = () => {
      heldSnapshot = undefined;
      snapshot(id);
    };
    return;
  }
  const fixtureOwner = notificationOwnerFixture?.owners.get(id);
  write(
    {
      type: 'broadcast',
      method: 'thread-stream-state-changed',
      version: 11,
      sourceClientId: fixtureOwner?.clientId ?? source,
      params: {
        hostId: 'local',
        conversationId: id,
        change: {
          type: 'snapshot',
          revision,
          conversationState: fixtureOwner?.state ?? { ...state(), id },
        },
      },
    },
    true,
  );
}
const server = createServer((connected) => {
  socket = connected;
  // Cold discovery replies can arrive after a deliberately closed fixture peer.
  connected.on('error', () => {});
  let buffer = Buffer.alloc(0);
  socket.on('data', (bytes) => {
    buffer = Buffer.concat([buffer, bytes]);
    while (buffer.length >= 4 && buffer.length >= buffer.readUInt32LE(0) + 4) {
      const size = buffer.readUInt32LE(0);
      const message = JSON.parse(buffer.subarray(4, size + 4));
      buffer = buffer.subarray(size + 4);
      notificationOwnerFixture?.ipcMethods.push(message.method);
      if (message.type === 'request') {
        if (message.method === 'initialize')
          write({
            type: 'response',
            requestId: message.requestId,
            resultType: 'success',
            method: message.method,
            result: { clientId: 'phone' },
          });
        else if (message.method === 'thread-owner-discovery') {
          const id = message.params.conversationId;
          ownerDiscoveryRequests.push({ id, timeoutMs: message.timeoutMs });
          const fixture = notificationOwnerFixture;
          if (fixture?.coldIds.has(id) || fixture?.owners.has(id)) {
            fixture.started.push(id);
            fixture.active++;
            fixture.maxActive = Math.max(fixture.maxActive, fixture.active);
            const owner = fixture.owners.get(id);
            const respond = () => {
              fixture.active--;
              if (!owner) fixture.replied.add(id);
              fixture.replies.push({ id, ownerClientId: owner?.clientId ?? null });
              if (connected.destroyed || socket !== connected) return;
              write(
                owner
                  ? {
                      type: 'response',
                      requestId: message.requestId,
                      resultType: 'success',
                      method: message.method,
                      handledByClientId: owner.clientId,
                      result: { supportsUntrustedAppInput: true },
                    }
                  : {
                      type: 'response',
                      requestId: message.requestId,
                      resultType: 'error',
                      error: 'no-client-found',
                    },
              );
            };
            const ownerBarrier = fixture.ownerBarriers?.get(id);
            if (owner && ownerBarrier) ownerBarrier.push(respond);
            else if (owner) respond();
            else setTimeout(respond, 120);
            continue;
          }
          if (
            !id.startsWith('discovery-timeout') ||
            (id === 'discovery-timeout-budget' && message.timeoutMs >= 200)
          )
            write(
              ['thread', 'another-loaded-chat', 'discovery-timeout-budget'].includes(id) &&
                ownerAvailable &&
                !(id === 'thread' && transientOwnerMisses-- > 0)
                ? {
                    type: 'response',
                    requestId: message.requestId,
                    resultType: 'success',
                    method: message.method,
                    handledByClientId: 'owner',
                    result: { supportsUntrustedAppInput: true },
                  }
                : {
                    type: 'response',
                    requestId: message.requestId,
                    resultType: 'error',
                    error: 'no-client-found',
                  },
            );
        } else {
          commands.push(message);
          let result = { ok: true };
          if (message.method === 'thread-follower-load-complete-history') {
            if (completeFixtureTurns) fixtureTurns = completeFixtureTurns;
            revision++;
            snapshot(message.params.conversationId);
            result = { revision };
          } else if (queueFixture && message.method === 'thread-follower-start-turn') {
            const request = message.params.turnStart.request;
            const turnId = 'queue-turn-' + ++queueTurn;
            fixtureTurns.push({
              turnId: null,
              status: 'inProgress',
              params: request,
              items: [],
            });
            running = true;
            revision++;
            snapshot();
            Object.assign(fixtureTurns.at(-1), {
              turnId,
              items: [
                {
                  id: turnId + '-user',
                  type: 'userMessage',
                  clientId: request.clientUserMessageId,
                  content: request.input,
                },
              ],
            });
            revision++;
            snapshot();
            result = { result: { turn: { id: turnId, status: 'inProgress' } } };
          } else if (queueFixture && message.method === 'thread-follower-steer-turn') {
            const active = fixtureTurns.findLast((turn) => turn.status === 'inProgress');
            if (rejectNextSteer) {
              rejectNextSteer = false;
              active.items.push({
                id: 'rejected-steer',
                type: 'steeringUserMessage',
                status: 'pending',
                clientUserMessageId: message.params.clientUserMessageId,
                input: message.params.input,
              });
              revision++;
              snapshot();
              active.items.pop();
              revision++;
              snapshot();
              write({
                type: 'response',
                requestId: message.requestId,
                resultType: 'error',
                error: 'steer rejected before acceptance',
              });
              continue;
            }
            active.items.push({
              id: 'steer-user',
              type: 'steeringUserMessage',
              clientUserMessageId: message.params.clientUserMessageId,
              serverUserMessageId: 'steer-user',
              serverClientUserMessageId: message.params.clientUserMessageId,
              status: 'accepted',
              input: message.params.input,
            });
            revision++;
            snapshot();
            result = { result: { turnId: active.turnId } };
          }
          write({
            type: 'response',
            requestId: message.requestId,
            resultType: 'success',
            method: message.method,
            handledByClientId: message.targetClientId,
            result,
          });
        }
      } else if (message.method === 'thread-unarchived') {
        unarchivedHints.push(message);
      } else if (message.method === 'thread-stream-following-changed') {
        following++;
        notificationOwnerFixture?.followingRequests?.push({
          id: message.params.conversationId,
          targets: message.targetClientIds,
        });
        snapshot(message.params.conversationId);
      }
    }
  });
});
await listenDesktopFixture(server, path);
if (process.platform === 'win32')
  await assert.rejects(
    new DesktopIpc(path).connect(),
    /Verified Windows desktop named pipe required/,
  );
const client = prepareDesktopFixtureIpc(new DesktopIpc(path));
let unavailable = 0,
  received = 0;
client.onUnavailable = () => unavailable++;
client.onSnapshot = () => received++;
const discovered = [];
client.onDiscovered = (id) => discovered.push(id);
const settle = () => new Promise((resolve) => setTimeout(resolve, 35));
async function connectUnverifiedBuild(runtime) {
  prepareDesktopFixtureRuntime(runtime);
  const exec = childProcess.execFileSync;
  const warn = console.warn;
  const warnings = [];
  childProcess.execFileSync = (file, args, options) => {
    if (file === '/usr/libexec/PlistBuddy') return '26.928.20755\n';
    return exec(file, args, options);
  };
  console.warn = (value) => warnings.push(value);
  syncBuiltinESMExports();
  try {
    await runtime.connect();
    assert.equal(runtime.version, '26.928.20755');
    assert.equal(runtime.ready, true);
    assert.deepEqual(warnings, []);
    return warnings;
  } finally {
    childProcess.execFileSync = exec;
    console.warn = warn;
    syncBuiltinESMExports();
  }
}
async function waitForFixture(check, description) {
  const deadline = Date.now() + 5000;
  while (!check()) {
    assert(Date.now() < deadline, description);
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}
async function oversizedIpcBoundaries() {
  const fixturePath = desktopFixtureEndpoint(join(directory, 'oversized.sock'));
  const peers = new Set(),
    dispatched = [],
    discovery = [];
  let peer,
    output = Promise.resolve();
  const encode = (value) => {
    const body = Buffer.from(JSON.stringify(value)),
      header = Buffer.alloc(4);
    header.writeUInt32LE(body.length);
    return Buffer.concat([header, body]);
  };
  const enqueue = (work) => (output = output.then(work));
  const send = (value) => enqueue(() => peer.write(encode(value)));
  const normal = (id) => ({
    type: 'broadcast',
    method: 'thread-stream-state-changed',
    version: 11,
    sourceClientId: 'bounded-owner',
    params: {
      hostId: 'local',
      conversationId: id,
      change: {
        type: 'snapshot',
        revision: 1,
        conversationState: {
          id,
          hostId: 'local',
          cwd: directory,
          title: 'Bounded fixture',
          threadRuntimeStatus: { type: id === 'idle-submit' ? 'idle' : 'active' },
          turns: [
            {
              turnId: 'protected-turn',
              status: id === 'idle-submit' ? 'completed' : 'inProgress',
              items: [],
            },
          ],
          requests: [],
        },
      },
    },
  });
  async function opaque(size, next) {
    const header = Buffer.alloc(4),
      filler = Buffer.alloc(256 * 1024, 0x61);
    header.writeUInt32LE(size);
    peer.write(header.subarray(0, 1));
    peer.write(header.subarray(1, 3));
    peer.write(header.subarray(3));
    for (let offset = 0; offset < size && !peer.destroyed; offset += filler.length) {
      if (!peer.write(filler.subarray(0, Math.min(filler.length, size - offset))))
        await new Promise((resolve) => {
          const done = () => {
            peer.off('drain', done);
            peer.off('close', done);
            resolve();
          };
          peer.once('drain', done);
          peer.once('close', done);
        });
    }
    if (next && !peer.destroyed) peer.write(encode(next));
  }
  const fixtureServer = createServer((connected) => {
    peer = connected;
    peers.add(connected);
    connected.on('error', () => {});
    let buffered = Buffer.alloc(0);
    connected.on('data', (bytes) => {
      buffered = Buffer.concat([buffered, bytes]);
      while (buffered.length >= 4 && buffered.length >= buffered.readUInt32LE(0) + 4) {
        const size = buffered.readUInt32LE(0),
          message = JSON.parse(buffered.subarray(4, size + 4));
        buffered = buffered.subarray(size + 4);
        if (message.method === 'initialize')
          send({
            type: 'response',
            requestId: message.requestId,
            resultType: 'success',
            result: { clientId: 'bounded-client' },
          });
        else if (message.method === 'thread-owner-discovery') {
          discovery.push(message.params.conversationId);
          send({
            type: 'response',
            requestId: message.requestId,
            resultType: 'success',
            handledByClientId: 'bounded-owner',
            result: { supportsUntrustedAppInput: true },
          });
        } else if (message.method === 'thread-stream-following-changed') {
          const id = message.params.conversationId;
          if (id === 'oversized')
            enqueue(() => opaque(desktopFrameLimits.materializedBytes + 1, normal('healthy')));
          else send(normal(id));
        } else if (message.type === 'request') {
          dispatched.push(message);
          // Drop an opaque response, then deliver an unrelated verified snapshot.
          enqueue(() => opaque(desktopFrameLimits.materializedBytes + 1, normal('healthy')));
        }
      }
    });
  });
  await listenDesktopFixture(fixtureServer, fixturePath);
  const ipc = prepareDesktopFixtureIpc(new DesktopIpc(fixturePath));
  let session, timeoutClient;
  const shortenCommand = (target) => {
    const request = target.request.bind(target);
    target.request = (method, params, owner, timeout, beforeExecutionWrite) =>
      request(
        method,
        params,
        owner,
        method.startsWith('thread-follower-') ? 200 : timeout,
        beforeExecutionWrite,
      );
  };
  try {
    await ipc.connect();
    await ipc.follow('healthy', 1000, true);
    const protectedState = ipc.state('healthy');
    const blocked = ipc.follow('oversized', 1000, true);
    await assert.rejects(
      blocked,
      (error) => error.status === 503 && error.message.includes('此对话'),
    );
    await output;
    assert.equal(ipc.ready, true);
    assert.equal(ipc.hasOwner('healthy'), true);
    assert.equal(ipc.state('healthy').turns[0].turnId, protectedState.turns[0].turnId);
    assert.equal(ipc.state('healthy').turns[0].status, 'inProgress');
    assert.equal(ipc.owners.get('healthy'), 'bounded-owner');
    assert.equal(ipc.hasOwner('oversized'), false);
    const probes = discovery.filter((id) => id === 'oversized').length;
    await assert.rejects(ipc.follow('oversized', 1000, true), (error) => error.status === 503);
    await assert.rejects(
      ipc.command('oversized', 'thread-follower-start-turn', {}),
      (error) => error instanceof DesktopCommandNotSubmitted && error.ownerUnavailable,
    );
    assert.equal(
      discovery.filter((id) => id === 'oversized').length,
      probes,
      'No tight oversized-chat retry',
    );
    assert.equal(dispatched.length, 0, 'Unreadable selected chat must not send input');
    shortenCommand(ipc);
    await assert.rejects(
      ipc.command('healthy', 'thread-follower-start-turn', {}),
      (error) =>
        !(error instanceof DesktopCommandNotSubmitted) &&
        error.message.includes('outcome uncertain'),
    );
    await output;
    assert.equal(dispatched.length, 1);
    assert.equal(ipc.ready, true);
    assert.equal(ipc.hasOwner('healthy'), true);
    ipc.close();
    await settle();

    session = new RuntimeSession({
      root: directory,
      directory: join(directory, 'opaque-reply-session'),
      name: 'Opaque reply fixture',
      mode: 'desktop-runtime',
      socket: fixturePath,
    });
    const runtime = session.runtime;
    prepareDesktopFixtureRuntime(runtime);
    runtime.reader.connect = async () =>
      Object.defineProperty(runtime.reader, 'ready', { value: true, configurable: true });
    runtime.defaultPermissions = async () => ({
      approvalPolicy: 'never',
      sandbox: 'danger-full-access',
    });
    session.state.threads = [
      {
        id: 'idle-submit',
        hostId: session.hostId,
        projectId: null,
        title: 'Fixture',
        state: 'unknown',
        turnId: null,
        messages: [],
        plan: [],
        error: null,
      },
    ];
    await session.connect();
    shortenCommand(runtime.ipc);
    const reply = {
      hostId: session.hostId,
      threadId: 'idle-submit',
      clientMessageId: 'opaque-once',
      text: 'Synthetic fixture input',
      attachmentIds: [],
      mode: 'next-turn',
    };
    const before = dispatched.length;
    const receipt = await session.submit(reply);
    assert.equal(receipt.state, 'uncertain');
    await output;
    const repeat = await session.submit(reply);
    assert.equal(repeat.state, 'uncertain');
    assert.equal(
      dispatched.length,
      before + 1,
      'Unknown response must not replay the same immutable input',
    );
    const status = session.replyDeliveryStatus(
      'idle-submit',
      'opaque-once',
      session.readingTarget(),
    );
    assert.equal(status.status, 'accepted');
    assert.equal(status.receipt.state, 'uncertain');
    assert.equal(status.evidence, null, 'Discard cannot manufacture negative proof');
    session.close();
    session = undefined;
    await settle();

    timeoutClient = prepareDesktopFixtureIpc(new DesktopIpc(fixturePath, { drainTimeoutMs: 30 }));
    await timeoutClient.connect();
    const disconnected = new Promise((resolve) => {
      timeoutClient.onDisconnect = resolve;
    });
    const unfinished = Buffer.alloc(4);
    unfinished.writeUInt32LE(desktopFrameLimits.materializedBytes + 1);
    peer.write(unfinished);
    await disconnected;
    assert.equal(
      timeoutClient.ready,
      false,
      'Unfinished opaque frame must fail closed at deadline',
    );
    console.log(
      'Oversized IPC fixture passed: bounded opaque drain, scoped snapshot503, protected owner/Turn, one uncertain command/no replay, unfinished deadline. No actual desktop or model.',
    );
  } finally {
    session?.close();
    ipc.close();
    timeoutClient?.close();
    for (const connected of peers) connected.destroy();
    await new Promise((resolve) => fixtureServer.close(resolve));
  }
}
const originalSpawn = childProcess.spawn;
try {
  await oversizedIpcBoundaries();
  await client.connect();
  const restoredId = randomUUID();
  assert.throws(() => new DesktopIpc(path).refreshUnarchivedThread(restoredId));
  assert.throws(() => client.refreshUnarchivedThread('not-an-exact-thread-id'));
  let catalogReads = 0;
  const openHost = randomUUID();
  const unarchivedCatalog = {
    hostId: openHost,
    threads: [{ id: restoredId, hostId: openHost, archived: false }],
  };
  let currentCatalog = unarchivedCatalog;
  const openRuntime = {
    ipc: client,
    reader: {
      desktopWorkspace: async () => {
        catalogReads++;
        return currentCatalog;
      },
    },
  };
  for (const platform of ['darwin', 'linux'])
    assert.equal(
      await refreshWindowsDesktopOpenState(openRuntime, openHost, restoredId, platform),
      false,
    );
  assert.equal(catalogReads, 0, 'Other platforms do not read metadata or publish hints');
  for (const catalog of [
    { ...unarchivedCatalog, hostId: 'other-host' },
    { ...unarchivedCatalog, threads: [] },
    { ...unarchivedCatalog, threads: [{ id: restoredId, hostId: 'other-host', archived: false }] },
    { ...unarchivedCatalog, threads: [{ id: restoredId, hostId: openHost, archived: true }] },
    { ...unarchivedCatalog, threads: [{ id: restoredId, hostId: openHost }] },
    { ...unarchivedCatalog, threads: [...unarchivedCatalog.threads, ...unarchivedCatalog.threads] },
  ]) {
    currentCatalog = catalog;
    assert.equal(
      await refreshWindowsDesktopOpenState(openRuntime, openHost, restoredId, 'win32'),
      false,
    );
  }
  const freshCatalog = Promise.withResolvers();
  openRuntime.reader.desktopWorkspace = () => freshCatalog.promise;
  const refreshing = refreshWindowsDesktopOpenState(openRuntime, openHost, restoredId, 'win32');
  await settle();
  assert.equal(unarchivedHints.length, 0, 'Wait for fresh metadata before publishing');
  freshCatalog.resolve(unarchivedCatalog);
  assert.equal(await refreshing, true);
  await settle();
  assert.deepEqual(unarchivedHints, [
    {
      type: 'broadcast',
      method: 'thread-unarchived',
      version: 1,
      sourceClientId: 'phone',
      params: { hostId: 'local', conversationId: restoredId },
    },
  ]);
  assert.equal(client.hasOwner(restoredId), false, 'A metadata hint is not an owner snapshot');
  assert.equal(client.state(restoredId), undefined);
  openRuntime.reader.desktopWorkspace = async () => {
    throw new Error('Metadata unavailable');
  };
  assert.equal(
    await refreshWindowsDesktopOpenState(openRuntime, openHost, restoredId, 'win32'),
    false,
  );
  assert.equal(unarchivedHints.length, 1);
  const discoveredId = '00000000-0000-4000-8000-000000000001';
  snapshot(discoveredId);
  await settle();
  assert.deepEqual(discovered, [discoveredId]);
  assert.equal(client.hasOwner(discoveredId), false, 'Discovery hints must not establish an owner');
  assert.equal(client.state(discoveredId), undefined);
  await client.follow(discoveredId, 30);
  assert.equal(client.hasOwner(discoveredId), false, 'A refused owner remains unavailable');
  assert.equal(await client.follow('unloaded'), undefined);
  assert.equal(client.hasOwner('unloaded'), false);
  assert.equal(await client.follow('discovery-timeout-no-owner', 30), undefined);
  assert.equal(client.hasOwner('discovery-timeout-no-owner'), false);
  const shortProbe = client.follow('discovery-timeout-budget', 30);
  const selected = client.follow('discovery-timeout-budget', 200);
  assert.equal(await shortProbe, undefined);
  assert.equal((await selected).id, 'discovery-timeout-budget');
  assert.equal(client.hasOwner('discovery-timeout-budget'), true);
  assert.deepEqual(
    ownerDiscoveryRequests.filter((v) => v.id === 'discovery-timeout-budget'),
    [
      { id: 'discovery-timeout-budget', timeoutMs: 30 },
      { id: 'discovery-timeout-budget', timeoutMs: 200 },
    ],
  );
  const followCount = following;
  const [first, same] = await Promise.all([client.follow('thread'), client.follow('thread')]);
  assert.equal(following, followCount + 1);
  assert.equal(first, same);
  assert.equal(first.turns[0].items[0].text, '正在执行');
  assert.equal(client.hasOwner('thread'), true);
  const readFollowCount = following;
  const readState = (params, version = 3) =>
    write({
      type: 'broadcast',
      method: 'thread-read-state-changed',
      version,
      sourceClientId: 'desktop-main',
      params,
    });
  readState({ hostId: 'other-host', conversationId: 'thread', hasUnreadTurn: true });
  readState({ hostId: 'local', conversationId: 'thread', hasUnreadTurn: 'true' });
  readState({ hostId: 'local', conversationId: 'thread', hasUnreadTurn: true }, 2);
  await settle();
  assert.equal(following, readFollowCount);
  assert.equal(client.state('thread').hasUnreadTurn, false);
  fixtureUnread = true;
  readState({ hostId: 'local', conversationId: 'thread', hasUnreadTurn: true });
  readState({ hostId: 'local', conversationId: 'unloaded', hasUnreadTurn: true });
  await settle();
  assert.equal(client.state('thread').hasUnreadTurn, true);
  assert.equal(client.hasOwner('unloaded'), false, 'Unread metadata cannot establish an owner');
  assert.equal(client.state('unloaded'), undefined);
  assert.equal(following, readFollowCount + 1);
  // Broadcasts from another identity cannot overwrite the owner's read value.
  readState({ hostId: 'local', conversationId: 'thread', hasUnreadTurn: false });
  await settle();
  assert.equal(client.state('thread').hasUnreadTurn, true);
  fixtureUnread = false;
  readState({ hostId: 'local', conversationId: 'thread', hasUnreadTurn: false });
  await settle();
  assert.equal(client.state('thread').hasUnreadTurn, false);
  write({
    type: 'broadcast',
    method: 'thread-stream-state-changed',
    version: 11,
    sourceClientId: 'spoof',
    params: {
      hostId: 'local',
      conversationId: 'thread',
      change: { type: 'snapshot', revision: 99, conversationState: { ...state(), turns: [] } },
    },
  });
  await settle();
  assert.equal(client.state('thread').turns.length, 1);
  write({
    type: 'broadcast',
    method: 'thread-stream-state-changed',
    version: 11,
    sourceClientId: 'owner',
    params: {
      hostId: 'local',
      conversationId: 'thread',
      change: {
        type: 'patches',
        baseRevision: 10,
        revision: 11,
        patches: [{ op: 'replace', path: ['turns', 0, 'items', 0, 'text'], value: '实时增量' }],
      },
    },
  });
  await settle();
  assert.equal(client.state('thread').turns[0].items[0].text, '实时增量');
  const beforeDuplicate = { unavailable, following };
  for (const change of [
    { type: 'patches', baseRevision: 10, revision: 11, patches: [] },
    { type: 'patches', baseRevision: 8, revision: 9, patches: [] },
    { type: 'snapshot', revision: 9, conversationState: { ...state(), turns: [] } },
  ])
    write({
      type: 'broadcast',
      method: 'thread-stream-state-changed',
      version: 11,
      sourceClientId: 'owner',
      params: { hostId: 'local', conversationId: 'thread', change },
    });
  await settle();
  assert.deepEqual({ unavailable, following }, beforeDuplicate);
  assert.equal(client.hasOwner('thread'), true);
  assert.equal(client.state('thread').turns[0].items[0].text, '实时增量');
  revision = 11;
  heldSnapshot = { id: 'thread' };
  const checkingOwner = client.follow('thread', 1000, true);
  await settle();
  assert.equal(
    client.hasOwner('thread'),
    true,
    'Fresh same-owner verification retains a live stream',
  );
  assert(heldSnapshot.release);
  heldSnapshot.release();
  assert.equal((await checkingOwner).id, 'thread');
  assert.equal(client.waiters.get('thread').size, 0, 'Fresh snapshot waiters are released');
  revision = 15;
  write({
    type: 'broadcast',
    method: 'thread-stream-state-changed',
    version: 11,
    sourceClientId: 'owner',
    params: {
      hostId: 'local',
      conversationId: 'thread',
      change: { type: 'patches', baseRevision: 12, revision: 13, patches: [] },
    },
  });
  await settle();
  assert.ok(unavailable > 0);
  assert.ok(following >= 2);
  for (let attempt = 0; attempt < 10 && !client.hasOwner('thread'); attempt++) await settle();
  assert.equal(client.hasOwner('thread'), true);
  await client.command('thread', 'thread-follower-interrupt-turn', {
    mode: 'user-stop',
    expectedTurnId: 'turn',
  });
  await client.command('thread', 'thread-follower-command-approval-decision', {
    requestId: 'approval',
    decision: 'accept',
  });
  assert.equal(commands[0].targetClientId, 'owner');
  assert.equal(commands[0].version, 4);
  assert.equal(commands[0].params.conversationId, 'thread');
  assert.equal(commands[0].params.expectedTurnId, 'turn');
  assert.equal(commands[1].params.requestId, 'approval');
  assert.equal(commands[1].params.decision, 'accept');
  const beforeUnloaded = commands.length;
  await assert.rejects(
    client.command('unloaded', 'thread-follower-start-turn', {}),
    DesktopCommandNotSubmitted,
  );
  assert.equal(commands.length, beforeUnloaded, 'Owner refusal must precede execution submission');
  // A single thread can disappear without a desktop-client disconnect event.
  // Cache presence must not authorize another command or a capability probe.
  ownerAvailable = false;
  assert.equal(client.hasOwner('thread'), true);
  assert.equal(await client.follow('thread', 1000, true), undefined);
  assert.equal(client.hasOwner('thread'), false);
  assert.equal(client.state('thread'), undefined);
  await assert.rejects(
    client.command('thread', 'thread-follower-start-turn', {}),
    DesktopCommandNotSubmitted,
  );
  assert.equal(commands.length, beforeUnloaded);
  ownerAvailable = true;
  assert.equal((await client.follow('thread')).id, 'thread');
  // Exercise the actual adapter -> desktop wire boundary, including the raw
  // params retained by the desktop renderer (not just normalized server items).
  running = false;
  revision++;
  snapshot();
  await settle();
  const runtime = Object.assign(Object.create(DesktopRuntime.prototype), {
    ipc: client,
    options: { socket: path },
    reader: { read: async () => ({ config: {} }) },
  });
  const input = [
    { type: 'text', text: '首条文字' },
    { type: 'localImage', path: '/tmp/image.png' },
    { type: 'text', text: '用户附件文件路径' },
    { type: 'text', text: '已有标记', text_elements: [{ placeholder: 'marker' }] },
  ];
  await runtime.call('turn/start', { threadId: 'thread', input, clientUserMessageId: 'first' });
  const start = commands.at(-1);
  assert.equal(start.method, 'thread-follower-start-turn');
  assert.equal(start.targetClientId, 'owner');
  assert.equal(start.version, 2);
  const rawInput = start.params.turnStart.request.input;
  assert.equal(
    rawInput.some(
      (part) =>
        part.type === 'text' &&
        part.text_elements.some((element) => element.placeholder === 'aeon-continuation'),
    ),
    false,
  );
  assert.deepEqual(rawInput[0], { ...input[0], text_elements: [] });
  assert.deepEqual(rawInput[1], input[1]);
  assert.deepEqual(rawInput[2], { ...input[2], text_elements: [] });
  assert.deepEqual(rawInput[3], input[3]);
  assert.equal(Object.hasOwn(input[0], 'text_elements'), false);
  running = true;
  revision++;
  snapshot();
  await settle();
  const beforeSteer = commands.length;
  await assert.rejects(
    runtime.call('turn/steer', { threadId: 'thread', input, expectedTurnId: 'stale' }),
    /当前任务已变化/,
  );
  assert.equal(commands.length, beforeSteer);
  await runtime.call('turn/steer', {
    threadId: 'thread',
    input,
    clientUserMessageId: 'steer',
    expectedTurnId: 'turn',
  });
  assert.equal(commands.at(-1).method, 'thread-follower-steer-turn');
  assert.deepEqual(commands.at(-1).params.input, rawInput);
  assert.equal(commands.at(-1).params.restoreMessage.id, 'steer');
  assert.deepEqual(commands.at(-1).params.restoreMessage.context.commentAttachments, []);
  assert.deepEqual(commands.at(-1).params.attachments, []);
  running = false;
  revision++;
  snapshot();
  await settle();
  const commandCount = commands.length;
  await assert.rejects(
    runtime.call('turn/start', {
      threadId: 'thread',
      input: [{ type: 'text', text: 'invalid', text_elements: null }],
    }),
    /Invalid desktop text elements/,
  );
  assert.equal(commands.length, commandCount);
  write({
    type: 'broadcast',
    method: 'thread-stream-state-changed',
    version: 12,
    sourceClientId: 'owner',
    params: {
      hostId: 'local',
      conversationId: 'thread',
      change: { type: 'snapshot', revision: 20, conversationState: state() },
    },
  });
  await settle();
  assert.equal(client.hasOwner('thread'), false);
  source = 'owner';
  snapshot();
  await settle();
  write({
    type: 'broadcast',
    method: 'client-status-changed',
    params: { clientId: 'owner', status: 'disconnected' },
  });
  await settle();
  assert.equal(client.hasOwner('thread'), false);
  assert.ok(received >= 3);
  assert.throws(
    () => applyDesktopPatches({}, [{ op: 'add', path: ['__proto__', 'polluted'], value: true }]),
    /Invalid/,
  );
  assert.throws(
    () => applyDesktopPatches({ list: [] }, [{ op: 'add', path: ['list', 2], value: true }]),
    /Invalid/,
  );
  const changed = applyDesktopPatches({ items: ['a', 'c'] }, [
    { op: 'add', path: ['items', 1], value: 'b' },
    { op: 'remove', path: ['items', 0] },
  ]);
  assert.deepEqual(changed.items, ['b', 'c']);
  const largeBranch = { toolOutput: 'x'.repeat(8 * 1024 * 1024) };
  const immutable = { turns: [{ text: 'before', nested: { status: 'running' } }], largeBranch };
  const patched = applyDesktopPatches(immutable, [
    { op: 'replace', path: ['turns', 0, 'text'], value: 'after' },
    { op: 'replace', path: ['turns', 0, 'nested', 'status'], value: 'completed' },
  ]);
  assert.equal(patched.largeBranch, largeBranch, 'Untouched large history retains its storage');
  assert.equal(immutable.turns[0].text, 'before');
  assert.equal(immutable.turns[0].nested.status, 'running');
  assert.equal(patched.turns[0].nested.status, 'completed');
  assert.throws(() =>
    applyDesktopPatches(immutable, [
      { op: 'replace', path: ['turns', 0, 'text'], value: 'partial' },
      { op: 'replace', path: ['missing', 'text'], value: 'invalid' },
    ]),
  );
  assert.equal(immutable.turns[0].text, 'before', 'Rejected batches cannot partially mutate state');
  assert.deepEqual(
    desktopTurns({
      turnHistory: {
        kind: 'canonical',
        history: {
          entitiesByKey: { a: { turnId: 'first' }, b: { turnId: 'last' } },
          islands: [{ entries: [{ value: 'a' }, { value: 'b' }] }],
        },
      },
    }).map((v) => v.turnId),
    ['first', 'last'],
  );
  // An original desktop chat without a user event can be absent from its
  // metadata catalog. Adopt it only after real owner discovery and a snapshot.
  client.close();
  socket.destroy();
  source = 'owner';
  revision = 30;
  const readerFixture = join(directory, 'reader.cjs');
  const catalogFile = join(directory, 'catalog.json');
  const readerRequestsFile = join(directory, 'reader-requests.jsonl');
  await writeFile(
    catalogFile,
    JSON.stringify({
      projects: [{ id: 'project', name: 'Fixture', roots: [{ path: directory }] }],
      threads: [],
    }),
  );
  await writeFile(
    readerFixture,
    `#!${process.execPath}
const readline = require('node:readline');
const fs = require('node:fs');
readline.createInterface({ input: process.stdin }).on('line', line => {
  const request = JSON.parse(line);
  if (request.id === undefined) return;
  fs.appendFileSync(${JSON.stringify(readerRequestsFile)}, JSON.stringify(request) + '\\n');
  let result;
  if (request.method === 'thread/queue/list') { process.stdout.write(JSON.stringify({id:request.id,error:{code:-32601,message:'Native queue unavailable in legacy desktop fixture'}}) + '\\n'); return; }
  if (request.method === 'initialize') result = {};
  else if (request.method === 'project/list') result = { data: JSON.parse(fs.readFileSync(${JSON.stringify(catalogFile)}, 'utf8')).projects, nextCursor: null };
  else if (request.method === 'thread/list') result = { data: JSON.parse(fs.readFileSync(${JSON.stringify(catalogFile)}, 'utf8')).threads.filter(t => request.params.archived === true ? t.archived === true : t.archived !== true).filter(t => !request.params.sectionId || t.section?.id === request.params.sectionId), nextCursor: null };
  else if (request.method === 'model/list') result = { data: [{id:'desktop-fixture-model',displayName:'Desktop Model',isDefault:true,defaultReasoningEffort:'xhigh',supportedReasoningEfforts:[{reasoningEffort:'low'},{reasoningEffort:'xhigh'}]}, {id:'desktop-fast',serviceTiers:[{id:'priority',name:'Fast'}],displayName:'Desktop Fast',isDefault:false,defaultReasoningEffort:'low',supportedReasoningEfforts:[{reasoningEffort:'low'}]}] };
  else if (request.method === 'config/read') result = { config: {} };
  else if (request.method === 'thread/read')
    result = { thread: { id: request.params.threadId, historyMode: 'paginated' } };
  else if (request.method === 'thread/turns/list') { process.stdout.write(JSON.stringify({id:request.id,error:{code:-32601,message:'Legacy reader has no turn summaries'}}) + '\\n'); return; }
  else if (request.method === 'thread/items/list') result = { data: request.params.cursor ? [{ item: { id: 'older-tool', type: 'mcpToolCall', server: 'fixture', tool: 'inspect', status: 'failed', error: { message: 'historical failure' } } }] : [{ item: { id: 'historical-answer', type: 'agentMessage', text: 'completed' } }, { item: { id: 'historical-command', type: 'commandExecution', command: 'printf historical', aggregatedOutput: 'historical', exitCode: 0, status: 'completed' } }], nextCursor: request.params.cursor ? null : 'older' };
  else throw Error('Fixture reader must never execute or load a chat');
  process.stdout.write(JSON.stringify({ id: request.id, result }) + '\\n');
});
`,
  );
  await chmod(readerFixture, 0o700);
  // Windows does not execute Unix shebangs. Only this generated reader uses
  // Node explicitly; retain the real child/stdin/stdout/JSONL and error boundary.
  const readerExecutables = new Set([readerFixture]);
  childProcess.spawn = (command, args, options) =>
    readerExecutables.has(command)
      ? originalSpawn(process.execPath, [command, ...args], options)
      : originalSpawn(command, args, options);
  syncBuiltinESMExports();
  const session = new RuntimeSession({
    directory: join(directory, 'session'),
    root: directory,
    name: 'Fixture',
    mode: 'desktop-runtime',
    socket: path,
    executable: readerFixture,
  });
  try {
    // Mock only installed-version discovery; use the actual reader and IPC
    // handshake. Build metadata must not trigger warnings or require an env label.
    await connectUnverifiedBuild(session.runtime);
    assert(session.capabilities.includes('reply'));
    assert(session.capabilities.includes('stop'));
    assert(session.capabilities.includes('approvals'));
    assert(!session.capabilities.includes('windows:desktop:testing'));
    // No single-thread allowlist: another actual owner is independently usable.
    assert.equal(
      (await session.runtime.ipc.follow('another-loaded-chat')).id,
      'another-loaded-chat',
    );
    await session.runtime.ipc.command('another-loaded-chat', 'thread-follower-interrupt-turn', {
      expectedTurnId: 'turn',
      mode: 'user-stop',
    });
    assert.equal(commands.at(-1).params.conversationId, 'another-loaded-chat');
    assert.equal(commands.at(-1).targetClientId, 'owner');
    assert.equal((await session.workspace()).threads.length, 0);
    await assert.rejects(session.open('unloaded'), /不属于当前运行时/);
    const beforeFollowing = following;
    const [opened, duplicate] = await Promise.all([session.open('thread'), session.open('thread')]);
    assert.equal(following, beforeFollowing + 1);
    assert.equal(opened.runtime.canControl, true);
    assert.equal(opened.hostId, session.hostId);
    assert.equal(opened.thread.projectId, 'project');
    assert.equal(opened.thread.title, 'Owner-only chat');
    assert.equal(duplicate.threadId, 'thread');
    assert.equal((await session.workspace()).threads.length, 1);
    assert.equal(opened.thread.hasUnreadTurn, false);
    // A renderer can update read state without a broadcast. The catalog fallback
    // catches it after a quiet interval, without repeatedly requesting a fresh
    // transcript while stream updates are still arriving.
    fixtureUnread = true;
    await session.workspace();
    await settle();
    assert.equal(session.history('thread').thread.hasUnreadTurn, false);
    const actualNow = Date.now;
    let elapsed = 11_000;
    Date.now = () => actualNow() + elapsed;
    try {
      await session.workspace();
      await settle();
      assert.equal(session.history('thread').thread.hasUnreadTurn, true);
      fixtureUnread = false;
      elapsed += 11_000;
      await session.workspace();
      await settle();
      assert.equal(session.history('thread').thread.hasUnreadTurn, false);
    } finally {
      Date.now = actualNow;
    }
    fixtureUnread = true;
    write({
      type: 'broadcast',
      method: 'thread-read-state-changed',
      version: 3,
      sourceClientId: 'desktop-main',
      params: { hostId: 'local', conversationId: 'thread', hasUnreadTurn: true },
    });
    await settle();
    assert.equal(session.history('thread').thread.hasUnreadTurn, true);
    assert.equal((await session.workspace()).threads[0].hasUnreadTurn, true);
    const unreadBatch = await session.poll(0, '', new AbortController().signal, 'thread');
    assert.equal(unreadBatch.events[0].snapshot.thread.hasUnreadTurn, true);
    assert.equal(unreadBatch.events[0].snapshot.runtime.canControl, true);
    fixtureUnread = false;
    write({
      type: 'broadcast',
      method: 'thread-read-state-changed',
      version: 3,
      sourceClientId: 'desktop-main',
      params: { hostId: 'local', conversationId: 'thread', hasUnreadTurn: false },
    });
    await settle();
    assert.equal(session.history('thread').thread.hasUnreadTurn, false);
    assert(session.capabilities.includes('queue') && session.capabilities.includes('steer'));
    // Mobile acknowledgements never write the desktop flag or send a desktop command.
    running = false;
    revision++;
    snapshot();
    await settle();
    const mobileReader = 'a'.repeat(64),
      otherReader = 'b'.repeat(64);
    const mobileBeforeCommands = commands.length;
    const mobileHead = session.projectReadState(session.history('thread'), mobileReader).thread
      .readState.latest;
    session.acknowledgeRead('thread', mobileReader, mobileHead);
    assert.equal(session.history('thread').thread.hasUnreadTurn, false);
    assert.equal(commands.length, mobileBeforeCommands);
    assert.deepEqual(
      session.projectReadState(session.history('thread'), mobileReader).thread.readState.read,
      mobileHead,
    );
    assert.equal(
      session.projectReadState(session.history('thread'), otherReader).thread.readState.read,
      undefined,
    );
    assert.equal(session.history('thread').thread.mobileReaders, undefined);
    messageText += ' changed in the same message';
    revision++;
    snapshot();
    await settle();
    const nextHead = session.projectReadState(session.history('thread'), mobileReader).thread
      .readState.latest;
    assert(nextHead.revision > mobileHead.revision);
    assert.equal(nextHead.messageId, mobileHead.messageId);
    assert.notEqual(nextHead.contentVersion, mobileHead.contentVersion);
    assert.throws(() => session.acknowledgeRead('thread', mobileReader, mobileHead), /旧阅读记录/);
    // Desktop compaction uses completed, rather than an item status field.
    running = true;
    queueFixture = true;
    running = true;
    fixtureTurns = [
      {
        turnId: 'async-turn',
        status: 'inProgress',
        items: [
          {
            id: 'async-source',
            type: 'agentMessage',
            text: '',
            questions: [
              { title: '选择格式', options: ['简洁版', '详细版'] },
              { title: '自由填写', options: [] },
              { title: '可跳过', options: ['继续'] },
            ],
          },
        ],
      },
    ];
    revision++;
    snapshot();
    await settle();
    const asyncPending = session.history('thread').runtime.approvals;
    assert.equal(asyncPending.length, 3);
    assert(asyncPending.every((q) => q.interactionMode === 'async'));
    assert.equal(session.history('thread').runtime.state, 'running');
    assert.equal(session.history('thread').thread.pendingAsyncQuestionCount, 3);
    assert.equal(
      (await session.workspace()).threads.find((t) => t.id === 'thread').pendingAsyncQuestionCount,
      3,
    );
    const questionObserver = new NotificationObserver(session.notificationTarget());
    const questionNotices = questionObserver.observe(
      session.notificationObservation('thread'),
    ).events;
    assert.deepEqual(
      questionNotices.map((event) => event.taskSummary),
      ['选择格式', '自由填写', '可跳过'],
    );
    const [choice, freeform, skipped] = asyncPending;
    const choiceAnswers = { [choice.questions[0].id]: ['简洁版'] };
    const beforeAsync = commands.length;
    await assert.rejects(
      session.answer('thread', choice.id, 'other-turn', '', choiceAnswers),
      /变化/,
    );
    await assert.rejects(session.answer('thread', choice.id, choice.turnId, '', {}), /有效回复/);
    assert.equal(commands.length, beforeAsync);
    await Promise.all([
      session.answer('thread', choice.id, choice.turnId, '', choiceAnswers),
      session.answer('thread', choice.id, choice.turnId, '', choiceAnswers),
    ]);
    assert.equal(
      commands.slice(beforeAsync).filter((c) => c.method === 'thread-follower-steer-turn').length,
      1,
    );
    const reply = commands.at(-1);
    assert.equal(reply.targetClientId, 'owner');
    assert.equal(session.history('thread').runtime.turnId, 'async-turn');
    assert(reply.params.input[0].text.includes('<send_user_message_question_reply>'));
    assert.equal(
      JSON.parse(reply.params.input[0].text.split('\n')[1])[0].questionItemId,
      choice.questions[0].id,
    );
    assert.equal(session.history('thread').runtime.state, 'running');
    assert.equal(session.history('thread').thread.pendingAsyncQuestionCount, 2);
    assert(
      !session
        .history('thread')
        .messages.some(
          (m) => m.role === 'user' && m.text.includes('<send_user_message_question_reply>'),
        ),
    );
    await assert.rejects(
      session.answer('thread', choice.id, choice.turnId, '', {
        [choice.questions[0].id]: ['详细版'],
      }),
      /不同/,
    );
    const beforeSkip = commands.length;
    await session.answer('thread', skipped.id, skipped.turnId, 'skip', {});
    revision++;
    snapshot();
    await settle();
    await session.answer('thread', skipped.id, skipped.turnId, 'skip', {});
    assert.equal(commands.length, beforeSkip, 'Skip must never stop or send input to the model');
    fixtureTurns[0].status = 'completed';
    running = false;
    revision++;
    snapshot();
    await settle();
    assert.equal(session.history('thread').runtime.state, 'idle');
    assert.equal(session.history('thread').runtime.approvals.length, 1);
    assert.equal(session.history('thread').thread.pendingAsyncQuestionCount, 1);
    assert.equal(
      (await session.workspace()).threads.find((t) => t.id === 'thread').pendingAsyncQuestionCount,
      1,
    );
    const idleQuestion = questionObserver
      .observe(session.notificationObservation('thread'))
      .events.find((event) => event.kind === 'question');
    assert.equal(idleQuestion.requestId, freeform.questions[0].id);
    assert.equal(idleQuestion.taskSummary, '自由填写');
    assert.equal(
      idleQuestion.id,
      questionNotices[1].id,
      'Completing a task must not duplicate its outstanding question',
    );
    await session.answer('thread', freeform.id, freeform.turnId, '', {
      [freeform.questions[0].id]: ['中文自定义答案'],
    });
    assert.equal(commands.at(-1).method, 'thread-follower-start-turn');
    assert.equal(session.history('thread').runtime.approvals.length, 0);
    assert.equal(session.history('thread').thread.pendingAsyncQuestionCount, 0);
    assert.equal(
      (await session.workspace()).threads.find((t) => t.id === 'thread').pendingAsyncQuestionCount,
      0,
    );
    assert.equal(
      session.notificationObservation('thread').requests.length,
      0,
      'Answered and skipped questions must retire their notices',
    );
    fixtureTurns.at(-1).status = 'completed';
    running = false;
    revision++;
    snapshot();
    await settle();
    const persistedAsync = JSON.parse(
      await readFile(join(directory, 'session/runtime.json'), 'utf8'),
    );
    assert.equal(Object.keys(persistedAsync.asyncQuestionActions).length, 3);
    assert.equal(persistedAsync.asyncQuestionActions[skipped.id].decision, 'skip');
    assert(Object.values(persistedAsync.asyncQuestionActions).every((action) => action.resolved));
    assert.equal(
      persistedAsync.receipts.filter((r) => r.clientMessageId.startsWith('async-question-')).length,
      2,
    );
    // Accepted desktop answers retire the same question without a Host action.
    fixtureTurns = [
      {
        turnId: 'desktop-async-answer',
        status: 'inProgress',
        items: [
          {
            id: 'desktop-question',
            type: 'agentMessage',
            questions: [{ title: '桌面回答', options: [] }],
          },
          {
            id: 'desktop-reply',
            type: 'userMessage',
            content: [
              {
                type: 'text',
                text:
                  '<send_user_message_question_reply>\n' +
                  JSON.stringify([
                    {
                      questionItemId: JSON.stringify([
                        'request_user_input_async',
                        'desktop-question',
                        0,
                      ]),
                      question: '桌面回答',
                      answer: '已回答',
                    },
                  ]) +
                  '\n</send_user_message_question_reply>',
              },
            ],
          },
        ],
      },
    ];
    running = true;
    revision++;
    snapshot();
    await settle();
    assert.equal(session.history('thread').runtime.approvals.length, 0);
    // Saving an immutable answer is not evidence that the desktop accepted it.
    fixtureTurns = [
      {
        turnId: 'unconfirmed-question-turn',
        status: 'inProgress',
        items: [
          {
            id: 'unconfirmed-question-source',
            type: 'agentMessage',
            questions: [
              { title: '被拒绝的回答', options: [] },
              { title: '结果未知的回答', options: [] },
            ],
          },
        ],
      },
    ];
    revision++;
    snapshot();
    await settle();
    const [rejectedQuestion, uncertainQuestion] = session.history('thread').runtime.approvals;
    const rejectedAnswers = { [rejectedQuestion.questions[0].id]: ['保留待确认'] };
    const originalQuestionCommand = session.runtime.ipc.command.bind(session.runtime.ipc);
    let questionWireReached = false,
      releaseQuestionWire;
    const questionWireBarrier = new Promise((done) => {
      releaseQuestionWire = done;
    });
    session.runtime.ipc.command = async (...args) => {
      if (args[1] === 'thread-follower-steer-turn') {
        questionWireReached = true;
        await questionWireBarrier;
      }
      return originalQuestionCommand(...args);
    };
    rejectNextSteer = true;
    const rejectedWork = session.answer(
      'thread',
      rejectedQuestion.id,
      rejectedQuestion.turnId,
      '',
      rejectedAnswers,
    );
    const rejectedResult = assert.rejects(rejectedWork, /rejected before acceptance/);
    await waitForFixture(() => questionWireReached, 'Saved async answer before wire submission');
    revision++;
    snapshot();
    await settle();
    assert.equal(session.history('thread').runtime.approvals.length, 2);
    assert.equal(session.notificationObservation('thread').requests.length, 2);
    releaseQuestionWire();
    await rejectedResult;
    session.runtime.ipc.command = originalQuestionCommand;
    await settle();
    assert.equal(session.history('thread').runtime.approvals.length, 2);
    const rejectedAction = session.state.asyncQuestionActions[rejectedQuestion.id];
    assert.equal(rejectedAction.resolved, false);
    const afterRejectedWire = commands.length;
    await assert.rejects(
      session.answer('thread', rejectedQuestion.id, rejectedQuestion.turnId, '', rejectedAnswers),
      /rejected before acceptance/,
    );
    assert.equal(
      commands.length,
      afterRejectedWire,
      'A failed immutable answer cannot be silently replayed',
    );
    await assert.rejects(
      session.answer('thread', rejectedQuestion.id, rejectedQuestion.turnId, '', {
        [rejectedQuestion.questions[0].id]: ['替换原答案'],
      }),
      /不同/,
    );
    // Old records without the new confirmation field must keep failures visible.
    delete rejectedAction.resolved;
    revision++;
    snapshot();
    await settle();
    assert.equal(session.history('thread').runtime.approvals.length, 2);
    const rejectedReceipt = session.state.receipts.find(
      (r) => r.clientMessageId === rejectedAction.reply.clientMessageId,
    );
    fixtureTurns[0].items.push({
      id: 'pending-async-reply',
      type: 'steeringUserMessage',
      status: 'pending',
      clientUserMessageId: rejectedAction.reply.clientMessageId,
      input: [{ type: 'text', text: rejectedAction.reply.text }],
    });
    revision++;
    snapshot();
    await settle();
    assert.equal(
      rejectedReceipt.state,
      'failed',
      'An optimistic desktop draft cannot confirm an answer',
    );
    assert.equal(session.history('thread').runtime.approvals.length, 2);
    fixtureTurns[0].items.at(-1).status = 'accepted';
    revision++;
    snapshot();
    await settle();
    await session.answer(
      'thread',
      rejectedQuestion.id,
      rejectedQuestion.turnId,
      '',
      rejectedAnswers,
    );
    assert.equal(commands.length, afterRejectedWire);
    assert.equal(session.history('thread').runtime.approvals.length, 1);

    const uncertainAnswers = { [uncertainQuestion.questions[0].id]: ['等待真实接收'] };
    session.runtime.ipc.command = async (...args) => {
      if (args[1] !== 'thread-follower-steer-turn') return originalQuestionCommand(...args);
      // This fixture simulates acceptance followed by a lost response; cross the
      // same final execution fence before creating the synthetic native input.
      args[3]?.();
      fixtureTurns[0].items.push({
        id: 'uncertain-async-reply',
        type: 'steeringUserMessage',
        status: 'pending',
        clientUserMessageId: args[2].clientUserMessageId,
        input: args[2].input,
      });
      revision++;
      snapshot();
      await settle();
      throw Error('Fixture answer response lost');
    };
    await assert.rejects(
      session.answer(
        'thread',
        uncertainQuestion.id,
        uncertainQuestion.turnId,
        '',
        uncertainAnswers,
      ),
      /结果未知/,
    );
    session.runtime.ipc.command = originalQuestionCommand;
    revision++;
    snapshot();
    await settle();
    const uncertainAction = session.state.asyncQuestionActions[uncertainQuestion.id];
    const uncertainReceipt = session.state.receipts.find(
      (r) => r.clientMessageId === uncertainAction.reply.clientMessageId,
    );
    assert.equal(uncertainReceipt.state, 'uncertain');
    assert.equal(session.history('thread').runtime.approvals.length, 1);
    assert.equal(session.notificationObservation('thread').requests.length, 1);
    await assert.rejects(
      session.answer(
        'thread',
        uncertainQuestion.id,
        uncertainQuestion.turnId,
        '',
        uncertainAnswers,
      ),
      /结果未知/,
    );
    assert.equal(commands.length, afterRejectedWire);
    const unresolvedState = JSON.parse(
      await readFile(join(directory, 'session/runtime.json'), 'utf8'),
    );
    assert.equal(unresolvedState.asyncQuestionActions[uncertainQuestion.id].resolved, false);
    fixtureTurns[0].items.at(-1).status = 'accepted';
    revision++;
    snapshot();
    await settle();
    await session.answer(
      'thread',
      uncertainQuestion.id,
      uncertainQuestion.turnId,
      '',
      uncertainAnswers,
    );
    assert.equal(
      commands.length,
      afterRejectedWire,
      'Late acceptance reconciles the original answer without another input',
    );
    assert.equal(session.history('thread').runtime.approvals.length, 0);
    assert.equal(session.notificationObservation('thread').requests.length, 0);
    // A confirmed disposition survives a temporarily uncertain receipt after restart.
    uncertainReceipt.state = 'uncertain';
    assert.deepEqual(
      await session.answer(
        'thread',
        uncertainQuestion.id,
        uncertainQuestion.turnId,
        '',
        uncertainAnswers,
      ),
      { ok: true },
    );
    assert.equal(commands.length, afterRejectedWire);
    fixtureTurns[0].status = 'completed';
    revision++;
    snapshot();
    await settle();
    // Real long chats can omit accepted steers entirely from their tail snapshot.
    // Recover only through the same owner's complete history and exact client IDs.
    const completedTurnId = fixtureTurns[0].turnId;
    completeFixtureTurns = [
      {
        turnId: completedTurnId,
        status: 'completed',
        items: [
          {
            id: 'canonical-first-answer',
            type: 'userMessage',
            clientId: rejectedReceipt.clientMessageId,
            content: [],
          },
          {
            id: 'canonical-second-answer',
            type: 'userMessage',
            clientId: uncertainReceipt.clientMessageId,
            content: [],
          },
        ],
      },
    ];
    fixtureTurns = [{ turnId: completedTurnId, status: 'completed', items: [] }];
    for (const receipt of [rejectedReceipt, uncertainReceipt]) {
      receipt.state = 'uncertain';
      delete receipt.acceptedTurnId;
    }
    const unresolved = {
      ...uncertainReceipt,
      clientMessageId: 'unmatched-old-answer',
      reply: { ...uncertainReceipt.reply, clientMessageId: 'unmatched-old-answer' },
    };
    session.state.receipts.push(unresolved);
    const sentBeforeRecovery = commands.filter((c) =>
      ['thread-follower-start-turn', 'thread-follower-steer-turn'].includes(c.method),
    ).length;
    revision++;
    snapshot();
    await settle();
    await session.open('thread');
    assert.equal(rejectedReceipt.state, 'completed');
    assert.equal(uncertainReceipt.state, 'completed');
    assert.equal(
      unresolved.state,
      'uncertain',
      'A completed turn alone cannot confirm an unmatched steer',
    );
    assert.equal(
      commands.filter((c) =>
        ['thread-follower-start-turn', 'thread-follower-steer-turn'].includes(c.method),
      ).length,
      sentBeforeRecovery,
    );
    assert.equal(commands.at(-1).method, 'thread-follower-load-complete-history');
    session.state.receipts = session.state.receipts.filter((r) => r !== unresolved);
    uncertainReceipt.state = 'uncertain';
    fixtureTurns = [{ turnId: completedTurnId, status: 'completed', items: [] }];
    revision++;
    snapshot();
    await settle();
    assert.equal(
      uncertainReceipt.state,
      'completed',
      'Persisted owner acceptance survives later tail-only snapshots',
    );
    completeFixtureTurns = undefined;
    queueFixture = false;
    queueTurn = 0;
    console.log(
      'Async questions: nonblocking snapshots, option/freeform/skip, active steer, completed follow-up, desktop answers, target checks, concurrent dedupe, confirmation timing, rejected/unknown drafts and late acceptance without replay passed (fixture).',
    );
    fixtureTurns = [
      {
        turnId: 'compaction-turn',
        status: 'inProgress',
        items: [
          { id: 'before-compaction', type: 'agentMessage', text: 'Before compression' },
          { id: 'compaction', type: 'contextCompaction', completed: false, startedAtMs: 1234 },
        ],
      },
    ];
    revision++;
    snapshot();
    await settle();
    const compressing = session.history('thread').messages.find((item) => item.id === 'compaction');
    assert.equal(compressing.kind, 'contextCompaction');
    assert.equal(compressing.text, '压缩上下文');
    assert.equal(compressing.status, 'inProgress');
    const initialProgress = await session.poll(0, '', new AbortController().signal, 'thread');
    assert.equal(
      initialProgress.events[0].snapshot.messages.find((item) => item.id === 'compaction').status,
      'inProgress',
    );
    write({
      type: 'broadcast',
      method: 'thread-stream-state-changed',
      version: 11,
      sourceClientId: 'owner',
      params: {
        hostId: 'local',
        conversationId: 'thread',
        change: {
          type: 'patches',
          baseRevision: revision,
          revision: ++revision,
          patches: [{ op: 'replace', path: ['turns', 0, 'items', 1, 'completed'], value: true }],
        },
      },
    });
    await settle();
    const finishedProgress = await session.poll(
      initialProgress.cursor,
      initialProgress.epoch,
      new AbortController().signal,
      'thread',
    );
    assert.equal(
      finishedProgress.events[0].snapshot.messages.find((item) => item.id === 'compaction').status,
      'completed',
    );
    assert.equal(finishedProgress.events[0].snapshot.runtime.turnId, 'compaction-turn');
    for (const status of ['interrupted', 'failed', 'completed']) {
      fixtureTurns[0].status = status;
      running = false;
      revision++;
      snapshot();
      await settle();
      assert.equal(
        session.history('thread').messages.find((item) => item.id === 'compaction').status,
        status,
      );
    }
    fixtureTurns[0].status = 'inProgress';
    running = true;
    revision++;
    snapshot();
    await settle();
    write({
      type: 'broadcast',
      method: 'client-status-changed',
      params: { clientId: 'owner', status: 'disconnected' },
    });
    await settle();
    assert.equal(session.history('thread').runtime.canControl, false);
    assert.deepEqual(session.history('thread').runtime.generationSettings, {
      model: null,
      effort: null,
      permissionMode: null,
      speed: null,
    });
    assert.equal(
      session.history('thread').messages.find((item) => item.id === 'compaction').status,
      'unknown',
    );
    const reconnectedCompaction = await session.open('thread');
    assert.equal(
      reconnectedCompaction.messages.find((item) => item.id === 'compaction').status,
      'inProgress',
    );
    fixtureTurns[0].items = [];
    revision++;
    snapshot();
    await settle();
    assert.equal(
      session.history('thread').messages.find((item) => item.id === 'compaction').status,
      'unknown',
    );
    queueFixture = true;
    running = true;
    fixtureTurns = [{ turnId: 'turn', status: 'inProgress', items: [] }];
    revision++;
    snapshot();
    await settle();
    const queuedReply = (id) => ({
      hostId: session.hostId,
      threadId: 'thread',
      clientMessageId: id,
      mode: 'queue',
      text: id,
      attachmentIds: [],
      ...(id === 'queue-c'
        ? {
            collaborationMode: 'plan',
            model: 'desktop-fast',
            effort: 'low',
            permissionMode: 'full-access',
          }
        : {}),
    });
    const beforeQueueWrites = commands.length;
    assert.equal((await session.submit(queuedReply('queue-a'))).state, 'failed');
    assert.equal((await session.submit(queuedReply('queue-a'))).state, 'failed');
    const legacy = structuredClone(
      session.state.receipts.find((r) => r.clientMessageId === 'queue-a'),
    );
    legacy.clientMessageId = 'legacy-host';
    legacy.state = 'queued';
    legacy.error = null;
    session.state.receipts.push(legacy);
    session.save();
    assert.equal(session.receipt('legacy-host').queueDisposition, 'legacy-review');
    assert.equal(session.receipt('legacy-host').queuePresent, false);
    assert.equal(session.receipt('legacy-host').canExecuteNow, false);
    await assert.rejects(session.executeQueue('thread', 'legacy-host'), /不能自动执行或重发/);
    assert.equal(
      commands.length,
      beforeQueueWrites,
      'Unsupported queues and legacy review never execute',
    );
    const beforeCommands = commands.length;
    await assert.rejects(
      session.submit({ ...queuedReply('stale-steer'), mode: 'steer', expectedTurnId: 'stale' }),
      /任务已变化/,
    );
    assert.equal(commands.length, beforeCommands);
    await assert.rejects(
      session.submit({
        ...queuedReply('plan-steer'),
        mode: 'steer',
        expectedTurnId: 'turn',
        collaborationMode: 'plan',
      }),
      /不能切换计划模式/,
    );
    const steered = await session.submit({
      ...queuedReply('queue-steer'),
      mode: 'steer',
      expectedTurnId: 'turn',
    });
    assert.equal(steered.turnId, 'turn');
    assert.equal(
      session.history('thread').messages.find((item) => item.id === 'steer-user')?.role,
      'user',
    );
    rejectNextSteer = true;
    const rejected = await session.submit({
      ...queuedReply('rejected-steer'),
      mode: 'steer',
      expectedTurnId: 'turn',
    });
    assert.equal(rejected.state, 'failed');
    assert.equal(
      session.history('thread').messages.some((item) => item.id === 'rejected-steer'),
      false,
    );
    fixtureTurns[0].status = 'completed';
    running = false;
    revision++;
    snapshot();
    await settle();
    assert.equal(session.receipt('legacy-host').state, 'queued');
    assert.equal(queueTurn, 0, 'Completing a turn cannot drain old Host records');
    assert.equal(session.receipt('rejected-steer').state, 'failed');
    const direct = await session.submit({ ...queuedReply('queue-c'), mode: 'next-turn' });
    assert.equal(direct.state, 'submitted');
    assert.equal(session.history('thread').runtime.turnId, 'queue-turn-1');
    fixtureTurns.at(-1).status = 'failed';
    fixtureTurns.at(-1).error = { message: 'Remote compaction connection failed' };
    running = false;
    revision++;
    snapshot();
    await settle();
    assert.equal(session.history('thread').runtime.state, 'failed');
    assert.equal(session.receipt('queue-c').state, 'failed');
    assert.equal(session.receipt('queue-c').error, 'Remote compaction connection failed');
    assert.equal(session.receipt('queue-c').supersededByTurnId, undefined);
    const failedHistory = session.history('thread').messages;
    assert.equal(failedHistory.at(-1).kind, 'turnFailure');
    assert.equal(failedHistory.at(-1).text, 'Remote compaction connection failed');
    const beforeDesktopContinuation = commands.length;
    fixtureTurns.push({
      turnId: 'desktop-only-continuation',
      status: 'inProgress',
      items: [{ id: 'desktop-only-input', type: 'userMessage', content: [] }],
    });
    running = true;
    revision++;
    snapshot();
    await settle();
    assert.equal(session.receipt('queue-c').state, 'failed');
    assert.equal(session.receipt('queue-c').error, 'Remote compaction connection failed');
    assert.equal(session.receipt('queue-c').supersededByTurnId, 'desktop-only-continuation');
    assert.equal(session.receipt('rejected-steer').supersededByTurnId, undefined);
    assert.equal(
      commands.length,
      beforeDesktopContinuation,
      'Observing a later turn cannot send input',
    );
    assert.equal(
      session.history('thread').messages.filter((item) => item.id === failedHistory.at(-1).id)
        .length,
      1,
      'Repeated snapshots retain one historical failure without appending duplicates',
    );
    fixtureTurns.pop();
    running = false;
    revision++;
    snapshot();
    await settle();
    assert.equal(
      session.receipt('queue-c').supersededByTurnId,
      'desktop-only-continuation',
      'A partial snapshot cannot undo already verified later-turn evidence',
    );
    assert.equal(
      commands.find(
        (v) =>
          v.method === 'thread-follower-start-turn' &&
          v.params.turnStart.request.clientUserMessageId === 'queue-c',
      ).params.turnStart.request.collaborationMode.mode,
      'plan',
    );
    const queuedSettings = commands.find(
      (v) => v.params.turnStart?.request?.clientUserMessageId === 'queue-c',
    ).params.turnStart.request;
    assert.equal(queuedSettings.collaborationMode.settings.model, 'desktop-fast');
    assert.equal(queuedSettings.collaborationMode.settings.reasoning_effort, 'low');
    assert.equal(queuedSettings.permissions, ':danger-full-access');
    // Even an unsupported native API cannot revive a stored Host FIFO.
    session.nativeQueueUnsupported = true;
    running = false;
    revision++;
    snapshot();
    await settle();
    assert.equal(session.receipt('legacy-host').state, 'queued');
    assert.equal(queueTurn, 1);
    assert.equal((await session.submit(queuedReply('unsupported-idle'))).state, 'failed');
    assert.equal(queueTurn, 1, 'An idle queue request cannot silently start a turn');
    session.nativeQueueUnsupported = false;
    // Native queue ownership: one metadata add, delete before one steer,
    // desktop deletion reconciliation, and lost delete response without replay.
    {
      const originalQueue = session.nativeQueue;
      const originalValidation = session.nativeQueueThread;
      const values = new Map();
      let adds = 0,
        deletes = 0,
        loseDelete = false;
      session.nativeQueueThread = () => true;
      session.nativeQueue = {
        available: true,
        list: async () => structuredClone([...values.values()]),
        add: async (threadId, id, input, beforeWrite) => {
          beforeWrite();
          adds++;
          const item = { id: 'native-' + id, clientUserMessageId: id, input };
          values.set(item.id, item);
          return structuredClone(item);
        },
        remove: async (threadId, id, beforeWrite) => {
          beforeWrite();
          deletes++;
          const removed = values.delete(id);
          if (loseDelete) throw new Error('delete response lost');
          return removed;
        },
        close() {},
      };
      try {
        fixtureTurns = [{ turnId: 'native-active', status: 'inProgress', items: [] }];
        running = true;
        revision++;
        snapshot();
        await settle();
        const message = (id) => ({
          hostId: session.hostId,
          threadId: 'thread',
          clientMessageId: id,
          text: id,
          attachmentIds: [],
          mode: 'queue',
        });
        for (const id of [
          'native-promote',
          'native-cancel',
          'native-desktop-cancel',
          'native-unknown',
        ])
          assert.equal((await session.submit(message(id))).state, 'queued');
        assert.equal(adds, 4);
        assert(session.capabilities.includes('queue:native'));
        assert.equal(session.receipt('native-promote').queueSource, 'native');
        const before = commands.length;
        const results = await Promise.all([
          session.executeQueue('thread', 'native-promote', 'native-active'),
          session.executeQueue('thread', 'native-promote', 'native-active'),
        ]);
        assert(results.every((v) => v.ok));
        assert(!values.has('native-native-promote'));
        assert.equal(
          commands.slice(before).filter((v) => v.method === 'thread-follower-steer-turn').length,
          1,
        );
        assert.equal(session.receipt('native-promote').mode, 'queue');
        await session.executeQueue('thread', 'native-promote');
        assert.equal(commands.length, before + 1);
        assert((await session.cancelQueue('thread', 'native-cancel')).ok);
        assert(!values.has('native-native-cancel'));
        assert.equal(session.receipt('native-cancel').state, 'cancelled');
        values.delete('native-native-desktop-cancel');
        session.nativeQueueReadAt.clear();
        await session.refreshNativeQueue('thread');
        assert.equal(session.receipt('native-desktop-cancel').queuePresent, false);
        const cancelled = session.state.receipts.find(
          (v) => v.clientMessageId === 'native-desktop-cancel',
        );
        cancelled.nativeQueue.missingSince = Date.now() - 2500;
        session.nativeQueueReadAt.clear();
        await session.refreshNativeQueue('thread');
        assert.equal(cancelled.state, 'cancelled');
        loseDelete = true;
        await assert.rejects(
          session.executeQueue('thread', 'native-unknown', 'native-active'),
          /delete response lost/,
        );
        assert.equal(session.receipt('native-unknown').state, 'uncertain');
        const lastDeletes = deletes;
        assert.equal((await session.executeQueue('thread', 'native-unknown')).ok, false);
        assert.equal(deletes, lastDeletes);
        assert.equal(commands.length, before + 1, 'No steer after uncertain native removal');
        assert.equal((await session.submit(message('native-unknown'))).state, 'uncertain');
        assert.equal(adds, 4, 'No uncertain native add replay');
        assert.equal((await session.submit(message('native-sync-failure'))).state, 'queued');
        const originalList = session.nativeQueue.list;
        session.nativeQueue.list = async () => {
          throw new Error('queue read disconnected');
        };
        session.nativeQueueReadAt.clear();
        await session.refreshNativeQueue('thread');
        assert.match(session.receipt('native-sync-failure').queueSyncError, /同步失败/);
        assert.equal(session.receipt('native-sync-failure').state, 'queued');
        assert.equal(session.receipt('native-sync-failure').canExecuteNow, false);
        assert.equal(adds, 5, 'Failed queue observation never resends input');
        session.nativeQueue.list = originalList;
        session.nativeQueueReadAt.clear();
        await session.refreshNativeQueue('thread');
        assert.equal(session.receipt('native-sync-failure').queueSyncError, undefined);
      } finally {
        session.nativeQueue = originalQueue;
        session.nativeQueueThread = originalValidation;
        session.nativeQueues.clear();
        session.nativeQueueReadAt.clear();
        session.nativeQueueErrors.clear();
      }
    }
    session.state.threads
      .find((v) => v.id === 'thread')
      .messages.push({
        id: 'old-plan-confirmation',
        role: 'tool',
        kind: 'planImplementation',
        text: 'planImplementation',
        files: [],
        status: 'completed',
        detail: '',
        attachments: [],
      });
    // Completed plans are actionable even though there is no active turn or approval.
    running = false;
    fixtureTurns = [
      {
        turnId: 'plan-turn',
        status: 'completed',
        items: [
          { id: 'plan-body', type: 'plan', text: '# Fixture plan\nInspect input, then implement.' },
          {
            id: 'implement-plan:plan-turn',
            type: 'planImplementation',
            turnId: 'plan-turn',
            planContent: '# Fixture plan\nInspect input, then implement.',
            isCompleted: false,
          },
        ],
      },
    ];
    revision++;
    snapshot();
    await settle();
    const planning = session.history('thread');
    assert.equal(planning.runtime.collaborationMode, 'plan');
    assert.equal(planning.runtime.proposedPlan.id, 'implement-plan:plan-turn');
    assert.equal(planning.runtime.turnId, null);
    assert.equal(planning.runtime.approvals.length, 0);
    assert.equal(planning.messages.find((v) => v.id === 'plan-body').role, 'assistant');
    assert(!planning.messages.some((v) => v.kind === 'planImplementation'));
    const planReply = {
      hostId: session.hostId,
      threadId: 'thread',
      clientMessageId: 'plan-implement',
      text: 'PLEASE IMPLEMENT THIS PLAN:\n' + planning.runtime.proposedPlan.text,
      attachmentIds: [],
      mode: 'next-turn',
      collaborationMode: 'default',
      expectedPlanId: planning.runtime.proposedPlan.id,
    };
    await assert.rejects(
      session.submit({ ...planReply, clientMessageId: 'stale-plan', expectedPlanId: 'stale' }),
      /计划已变化/,
    );
    const implemented = await session.submit(planReply);
    assert.equal(implemented.state, 'submitted');
    const planCommand = commands.at(-1).params.turnStart.request;
    assert.equal(planCommand.collaborationMode.mode, 'default');
    assert.equal(planCommand.collaborationMode.settings.model, 'desktop-fixture-model');
    assert.equal(planCommand.collaborationMode.settings.reasoning_effort, 'xhigh');
    assert.equal(planCommand.collaborationMode.settings.developer_instructions, null);
    assert.equal(planCommand.input[0].text, planReply.text);
    assert(!('expectedPlanId' in planCommand));
    await settle();
    assert.equal(session.history('thread').runtime.proposedPlan, null);
    assert.equal(
      (await session.submit(planReply)).turnId,
      implemented.turnId,
      'Retry returns the same receipt after the plan is consumed',
    );
    await assert.rejects(
      session.submit({ ...planReply, clientMessageId: 'plan-repeat' }),
      /计划已变化/,
    );
    fixtureTurns.at(-1).status = 'completed';
    running = false;
    revision++;
    snapshot();
    await settle();
    const planned = await session.submit({
      ...planReply,
      clientMessageId: 'plan-new-turn',
      expectedPlanId: undefined,
      text: 'Make a new plan',
      collaborationMode: 'plan',
    });
    assert.equal(planned.state, 'submitted');
    assert.equal(commands.at(-1).params.turnStart.request.collaborationMode.mode, 'plan');
    fixtureTurns.at(-1).status = 'completed';
    running = false;
    revision++;
    snapshot();
    await settle();
    fixtureSettings = { effort: null };
    revision++;
    snapshot();
    await settle();
    await session.submit({
      ...planReply,
      clientMessageId: 'plan-null-effort',
      expectedPlanId: undefined,
      collaborationMode: 'default',
    });
    assert.equal(
      commands.at(-1).params.turnStart.request.collaborationMode.settings.reasoning_effort,
      null,
    );
    fixtureTurns.at(-1).status = 'completed';
    running = false;
    revision++;
    snapshot();
    await settle();
    fixtureSettings = undefined;
    const models = await session.models();
    assert.equal(models.models[1].id, 'desktop-fast');
    const configured = {
      ...planReply,
      clientMessageId: 'settings-next',
      speed: 'fast',
      expectedPlanId: undefined,
      collaborationMode: 'plan',
      model: 'desktop-fast',
      effort: 'low',
      permissionMode: 'read-only',
    };
    await assert.rejects(session.submit({ ...configured, effort: 'xhigh' }), /不支持/);
    await assert.rejects(session.submit({ ...configured, model: 'missing-model' }), /未提供/);
    assert.equal((await session.submit(configured)).state, 'submitted');
    const settingsWire = commands.at(-1).params.turnStart.request;
    assert.deepEqual(settingsWire.collaborationMode.settings, {
      model: 'desktop-fast',
      reasoning_effort: 'low',
      developer_instructions: null,
    });
    assert.equal(settingsWire.permissions, ':read-only');
    assert.deepEqual(settingsWire.sandboxPolicy, { type: 'readOnly' });
    assert.equal(settingsWire.serviceTier, 'priority');
    assert(!('speed' in settingsWire));
    const beforeConflictingReply = commands.length;
    await assert.rejects(
      session.submit({ ...configured, speed: 'standard' }),
      (error) => error.status === 409,
    );
    assert.equal(commands.length, beforeConflictingReply, 'Conflicting ID must not send IPC');
    assert.equal(settingsWire.approvalPolicy, 'on-request');
    assert.equal(settingsWire.approvalsReviewer, 'user');
    assert(!('permissionMode' in settingsWire));
    fixtureTurns.at(-1).status = 'completed';
    running = false;
    const standard = { ...configured, clientMessageId: 'speed-standard', speed: 'standard' };
    assert.equal((await session.submit(standard)).state, 'submitted');
    assert.equal(commands.at(-1).params.turnStart.request.serviceTier, 'default');
    fixtureTurns.at(-1).status = 'completed';
    running = false;
    revision++;
    snapshot();
    await settle();
    fixtureSettings = {
      model: 'desktop-fast',
      effort: 'low',
      permissions: ':workspace',
      approvalPolicy: 'on-request',
      approvalsReviewer: 'guardian_subagent',
      serviceTier: 'priority',
    };
    revision++;
    snapshot();
    await settle();
    assert.deepEqual(session.history('thread').runtime.generationSettings, {
      model: 'desktop-fast',
      effort: 'low',
      permissionMode: 'guardian-approvals',
      speed: 'fast',
    });
    fixtureSettings = {
      model: 'desktop-fixture-model',
      effort: 'xhigh',
      permissions: ':danger-full-access',
      approvalPolicy: 'never',
      approvalsReviewer: 'user',
    };
    revision++;
    snapshot();
    await settle();
    assert.equal(
      session.history('thread').runtime.generationSettings.permissionMode,
      'full-access',
    );
    // A saved full-access selection must never override the actual running sandbox.
    fixturePermissions = {
      sandboxPolicy: { type: 'readOnly' },
      approvalPolicy: 'on-request',
      approvalsReviewer: 'user',
    };
    revision++;
    snapshot();
    await settle();
    assert.equal(session.history('thread').runtime.generationSettings.permissionMode, 'read-only');
    const fullAgainstActual = {
      ...configured,
      clientMessageId: 'full-against-actual',
      permissionMode: 'full-access',
    };
    assert.equal((await session.submit(fullAgainstActual)).state, 'submitted');
    assert.deepEqual(commands.at(-1).params.turnStart.request.sandboxPolicy, {
      type: 'dangerFullAccess',
    });
    assert.equal(session.history('thread').runtime.generationSettings.permissionMode, 'read-only');
    fixtureTurns.at(-1).status = 'completed';
    running = false;
    fixturePermissions = undefined;
    // Defaults are read afresh from this Host's desktop settings for each new turn.
    const desktopStateFile = join(directory, '.codex-global-state.json');
    for (const [choice, expected] of [
      ['full-access', 'dangerFullAccess'],
      ['guardian-approvals', 'workspaceWrite'],
    ]) {
      await writeFile(
        desktopStateFile,
        JSON.stringify({
          'electron-persisted-atom-state': {
            'permission-selection-by-host-id:local': { kind: 'agent-mode', agentMode: choice },
          },
        }),
      );
      const defaultsReply = {
        ...configured,
        permissionMode: undefined,
        clientMessageId: 'defaults-' + choice,
      };
      assert.equal((await session.submit(defaultsReply)).state, 'submitted');
      const wire = commands.at(-1).params.turnStart.request;
      assert.equal(wire.sandboxPolicy.type, expected);
      assert.equal(wire.approvalPolicy, choice === 'full-access' ? 'never' : 'on-request');
      assert.equal(wire.approvalsReviewer, choice === 'full-access' ? 'user' : 'guardian_subagent');
      fixtureTurns.at(-1).status = 'completed';
      running = false;
    }
    await rm(desktopStateFile);
    // A newer profile selection supersedes the legacy cached full-access mode.
    await writeFile(
      desktopStateFile,
      JSON.stringify({
        'electron-persisted-atom-state': {
          'permission-selection-by-host-id:local': { kind: 'profile', profileId: 'restricted' },
          'agent-mode-by-host-id': { local: 'full-access' },
        },
      }),
    );
    const beforeCustom = commands.length;
    const customResult = await session.submit({
      ...configured,
      permissionMode: undefined,
      clientMessageId: 'custom-default',
    });
    assert.equal(customResult.state, 'failed');
    assert.equal(
      commands.length,
      beforeCustom,
      'Unresolved custom defaults cannot silently grant full access',
    );
    await rm(desktopStateFile);
    fixtureSettings = {
      permissions: ':read-only',
      sandboxPolicy: { type: 'dangerFullAccess' },
      approvalPolicy: 'on-request',
      approvalsReviewer: 'user',
    };
    revision++;
    snapshot();
    await settle();
    assert.equal(session.history('thread').runtime.generationSettings.permissionMode, 'read-only');
    fixtureSettings = {
      permissions: 'custom-profile',
      sandboxPolicy: { type: 'dangerFullAccess' },
      approvalPolicy: 'never',
      approvalsReviewer: 'user',
    };
    revision++;
    snapshot();
    await settle();
    assert.equal(session.history('thread').runtime.generationSettings.permissionMode, null);

    fixtureSettings = undefined;
    queueFixture = false;
    fixtureTurns = undefined;
    await writeFile(
      catalogFile,
      JSON.stringify({
        projects: [],
        threads: [{ id: 'history-thread', name: 'Historical tools', preview: '', projectId: null }],
      }),
    );
    const workspace = await session.workspace();
    assert.deepEqual(workspace.clientType, { id: 'codex', name: 'Codex' });
    const historical = await session.open('history-thread');
    assert.equal(historical.runtime.canControl, false);
    assert.equal(historical.runtime.state, 'unknown');
    assert.equal(historical.messages[0].role, 'tool');
    assert.equal(historical.messages[0].kind, 'commandExecution');
    assert.equal(historical.messages[0].detail, 'historical\nExit 0');
    const older = await session.open('history-thread', historical.nextCursor);
    assert.deepEqual(
      older.messages.map((m) => m.id),
      ['older-tool', 'historical-command', 'historical-answer'],
    );
    assert.equal(older.messages[0].status, 'failed');
    assert.equal(older.messages[0].detail, 'historical failure');
    const refreshed = await session.open('history-thread');
    assert.deepEqual(
      refreshed.messages.map((m) => m.id),
      ['older-tool', 'historical-command', 'historical-answer'],
    );
    assert.equal(refreshed.messages[1].detail, 'historical\nExit 0');
    // The cache can contain pages older than the page being revisited.
    refreshed.messages.unshift({
      id: 'oldest-cached-tool',
      role: 'tool',
      text: 'older',
      files: [],
      kind: 'commandExecution',
      status: 'completed',
      detail: 'older',
      attachments: [],
    });
    const revisited = await session.open('history-thread', 'older');
    assert.deepEqual(
      revisited.messages.map((m) => m.id),
      ['oldest-cached-tool', 'older-tool', 'historical-command', 'historical-answer'],
    );
    await writeFile(join(ownerDirectory, 'owned.txt'), 'original-owner-file');
    await writeFile(join(deniedDirectory, 'denied.txt'), 'explicitly-referenced-outside-report');
    await symlink(join(deniedDirectory, 'denied.txt'), join(ownerDirectory, 'escape.txt'));
    cwd = ownerDirectory;
    messageText = `[owned](${join(ownerDirectory, 'owned.txt')}) [denied](${join(deniedDirectory, 'denied.txt')}) [escape](${join(ownerDirectory, 'escape.txt')})`;
    revision++;
    snapshot();
    await settle();
    const updated = await session.open('thread');
    const files = updated.messages.flatMap((v) => v.attachments);
    assert.equal(
      files.length,
      2,
      'explicit original assistant links register project-external resources too',
    );
    assert.equal(files[0].name, 'owned.txt');
    const outsideFile = files.find((file) => file.name === 'denied.txt');
    assert.equal(
      (await session.download('thread', outsideFile.id)).data.toString(),
      'explicitly-referenced-outside-report',
    );
    assert.equal(
      (await session.download('thread', files[0].id)).data.toString(),
      'original-owner-file',
    );
    assert.equal(updated.thread.desktopDirectory, undefined);
    await assert.rejects(session.open('other-thread'), /不属于当前运行时/);
    await assert.rejects(session.download('other-thread', files[0].id), /不属于当前运行时/);
    await writeFile(join(ownerDirectory, 'owned.txt'), 'changed');
    await assert.rejects(session.download('thread', files[0].id), /发生变化/);
  } finally {
    session.close();
  }
  // A persisted, confirmed creation retains the user's selected display project
  // when the read-only catalog can only infer two projects from a shared cwd.
  const savedPath = join(directory, 'session/runtime.json');
  const saved = new RuntimePersistence(savedPath, join(directory, 'session')).load();
  delete saved.historyCacheVersion;
  saved.threads.find((v) => v.id === 'thread').desktopCreationProjectId = 'project-b';
  saved.threads
    .find((v) => v.id === 'thread')
    .messages.push({
      id: 'compaction-before-restart',
      role: 'tool',
      text: '压缩上下文',
      files: [],
      kind: 'contextCompaction',
      status: 'inProgress',
      detail: '',
      attachments: [],
    });
  saved.threads.push({
    id: 'projectless-fixture',
    hostId: saved.hostId,
    projectId: null,
    desktopCreationProjectId: null,
    title: 'Projectless',
    state: 'unknown',
    turnId: null,
    messages: [],
    plan: [],
    error: null,
  });
  await writeFile(savedPath, JSON.stringify(saved), { mode: 0o600 });
  await writeFile(
    catalogFile,
    JSON.stringify({
      projects: [
        { id: 'project-a', name: 'A', roots: [{ path: ownerDirectory }] },
        { id: 'project-b', name: 'B', roots: [{ path: ownerDirectory }] },
        { id: 'project', name: 'Fixture', roots: [{ path: directory }] },
      ],
      threads: [
        { id: 'thread', cwd: ownerDirectory, name: 'Owner-only chat', projectId: null },
        { id: 'projectless-fixture', cwd: directory, name: 'Projectless', projectId: null },
        { id: 'discovery-timeout', cwd: directory, name: 'No owner response', projectId: null },
      ],
    }),
  );
  const restored = new RuntimeSession({
    directory: join(directory, 'session'),
    root: directory,
    name: 'Fixture',
    mode: 'desktop-runtime',
    socket: path,
    executable: readerFixture,
    desktopVersion: '26.930.61225',
  });
  let view;
  try {
    assert.equal(
      restored.history('thread').messages.find((item) => item.id === 'compaction-before-restart')
        .status,
      'unknown',
    );
    const warnings = await connectUnverifiedBuild(restored.runtime);
    assert.deepEqual(warnings, []);
    // Reproduce a fresh Host/sidebar refresh while the original desktop is
    // executing. No history/open request has subscribed this restored session.
    running = true;
    fixtureTurns = [{ turnId: 'sidebar-turn', status: 'inProgress', items: [] }];
    const commandsBeforeRefresh = commands.length;
    const workspace = await restored.workspace();
    assert.equal(workspace.threads.find((v) => v.id === 'thread').state, 'running');
    assert.equal(workspace.threads.find((v) => v.id === 'thread').turnId, 'sidebar-turn');
    assert.equal(workspace.threads.find((v) => v.id === 'projectless-fixture').state, 'unknown');
    assert.equal(workspace.threads.find((v) => v.id === 'discovery-timeout').state, 'unknown');
    assert(!restored.loaded.has('thread'), 'List refresh must not open chat history');
    assert.equal(
      commands.length,
      commandsBeforeRefresh,
      'Refreshing must not execute desktop commands',
    );
    assert.equal(workspace.threads.find((v) => v.id === 'thread').projectId, 'project-b');
    assert.deepEqual(workspace.threads.find((v) => v.id === 'thread').projectIds, ['project-b']);
    assert.equal(workspace.threads.find((v) => v.id === 'projectless-fixture').projectId, null);
    assert.equal(
      workspace.threads.find((v) => v.id === 'thread').desktopCreationProjectId,
      undefined,
    );
    assert.equal(restored.history('thread').thread.desktopCreationProjectId, undefined);
    // A full refresh replaces presentation order and hides removed metadata,
    // while retaining durable execution/recovery state internally.
    const catalog = JSON.parse(await readFile(catalogFile, 'utf8'));
    catalog.projects.reverse();
    catalog.threads = catalog.threads.filter((v) => v.id !== 'projectless-fixture');
    await writeFile(catalogFile, JSON.stringify(catalog));
    view = new DesktopWorkspaceView(
      restored,
      'Fixture',
      readerFixture,
      join(directory, '.codex-global-state.json'),
    );
    const refreshed = await view.workspace();
    let refreshCalls = 0;
    const refreshWorkspace = restored.workspace.bind(restored);
    restored.workspace = async (...args) => {
      refreshCalls++;
      return refreshWorkspace(...args);
    };
    const coalesced = await Promise.all([view.workspace(), view.workspace(), view.workspace()]);
    assert.equal(refreshCalls, 1, 'Concurrent catalog requests share one refresh');
    assert.equal(coalesced[0], coalesced[1]);
    restored.workspace = refreshWorkspace;
    assert.equal(refreshed.threads.find((v) => v.id === 'thread').state, 'running');
    assert.deepEqual(
      refreshed.projects.map((v) => v.id),
      ['project', 'project-b', 'project-a'],
    );
    assert(!refreshed.threads.some((v) => v.id === 'projectless-fixture'));
    fixtureRequests = [
      {
        id: 'sidebar-approval',
        method: 'item/commandExecution/requestApproval',
        params: { threadId: 'thread', turnId: 'sidebar-turn', command: 'fixture approval' },
      },
    ];
    revision++;
    snapshot();
    await settle();
    assert.equal(
      (await view.workspace()).threads.find((v) => v.id === 'thread').state,
      'waiting-for-approval',
    );
    fixtureRequests = [];
    running = false;
    fixtureTurns[0].status = 'completed';
    revision++;
    snapshot();
    await settle();
    assert.equal((await view.workspace()).threads.find((v) => v.id === 'thread').state, 'idle');
    write({
      type: 'broadcast',
      method: 'client-status-changed',
      params: { clientId: 'owner', status: 'disconnected' },
    });
    await settle();
    assert.equal(restored.history('thread').runtime.state, 'unknown');
    ownerAvailable = false;
    assert.equal((await view.workspace()).threads.find((v) => v.id === 'thread').state, 'unknown');
    ownerAvailable = true;
    running = true;
    fixtureTurns[0].status = 'inProgress';
    revision++;
    assert.equal((await view.workspace()).threads.find((v) => v.id === 'thread').state, 'running');
    assert.equal(commands.length, commandsBeforeRefresh);
    const sidebarFile = join(directory, '.codex-global-state.json');
    const sidebar = {
      'app-server-project-id-by-legacy-project-id-by-host': {
        ['local:' + directory]: { 'display-a': 'project-a' },
      },
      'thread-project-assignments': { thread: { projectKind: 'local', projectId: 'display-a' } },
    };
    await writeFile(sidebarFile, JSON.stringify(sidebar));
    const moved = await view.workspace();
    assert.equal(moved.threads.find((v) => v.id === 'thread').projectId, 'project-a');
    assert.deepEqual(moved.threads.find((v) => v.id === 'thread').projectIds, ['project-a']);
    const history = restored.history('thread');
    const projected = view.project(history);
    assert.equal(projected.thread.projectId, 'project-a');
    assert.equal(projected.runtime, history.runtime);
    assert.equal(projected.messages, history.messages);
    const events = view.project({ events: [{ threadId: 'thread', snapshot: history }] });
    assert.equal(events.events[0].snapshot.thread.projectId, 'project-a');
    assert.equal(events.events[0].threadId, 'thread');
    const catalogRegistrations = [];
    const registerCatalogThread = restored.runtime.reader.registerCatalogThread.bind(
      restored.runtime.reader,
    );
    restored.runtime.reader.registerCatalogThread = (id) => {
      catalogRegistrations.push(id);
      registerCatalogThread(id);
    };
    const archiveCatalog = JSON.parse(await readFile(catalogFile, 'utf8'));
    archiveCatalog.threads.find((v) => v.id === 'thread').archived = true;
    await writeFile(catalogFile, JSON.stringify(archiveCatalog));
    await view.workspace();
    for (const outcome of ['completed', 'interrupted']) {
      running = false;
      fixtureTurns[0].status = outcome;
      revision++;
      snapshot();
      await settle();
      const archivedTask = (await view.workspace()).threads.find((v) => v.id === 'thread');
      assert.equal(archivedTask.archived, true);
      assert.equal(archivedTask.state, 'idle', 'Archive catalog preserves the observed task state');
      assert.equal(archivedTask.lastTurnStatus, outcome);
    }
    restored.state.threads.find((v) => v.id === 'thread').archived = true;
    const batchArchivedTask = (await view.workspace()).threads.find((v) => v.id === 'thread');
    assert.equal(batchArchivedTask.state, 'idle');
    assert.equal(batchArchivedTask.lastTurnStatus, 'interrupted');
    const cachedArchived = restored.state.threads.find((v) => v.id === 'thread');
    for (const outcome of ['completed', 'interrupted', 'failed', 'inProgress', undefined]) {
      cachedArchived.state = 'unknown';
      cachedArchived.lastTurnStatus = outcome;
      const afterOwnerLoss = (await view.workspace()).threads.find((v) => v.id === 'thread');
      assert.equal(
        cachedArchived.state,
        'unknown',
        'Presentation must not restore execution state',
      );
      assert.equal(
        afterOwnerLoss.state,
        outcome === 'failed'
          ? 'failed'
          : ['completed', 'interrupted'].includes(outcome)
            ? 'idle'
            : 'unknown',
      );
      assert.equal(afterOwnerLoss.lastTurnStatus, outcome);
    }
    archiveCatalog.threads.find((v) => v.id === 'thread').archived = false;
    await writeFile(catalogFile, JSON.stringify(archiveCatalog));
    cachedArchived.lastTurnStatus = 'completed';
    assert.equal(
      (await view.workspace()).threads.find((v) => v.id === 'thread').state,
      'unknown',
      'An active catalog entry cannot infer completion from an old task',
    );
    archiveCatalog.threads.find((v) => v.id === 'thread').archived = true;
    await writeFile(catalogFile, JSON.stringify(archiveCatalog));
    await view.workspace();
    restored.state.threads.find((v) => v.id === 'thread').archived = false;
    running = true;
    fixtureTurns[0].status = 'inProgress';
    revision++;
    snapshot();
    await settle();
    assert(
      catalogRegistrations.includes('thread'),
      'Known archived IDs must be re-registered after reader restart',
    );
    restored.runtime.reader.registerCatalogThread = registerCatalogThread;
    const staleActive = { ...history, thread: { ...history.thread, archived: false } };
    assert.equal(
      view.project(staleActive).thread.archived,
      true,
      'History cannot undo authoritative archive state',
    );
    assert.equal(
      view.project({ events: [{ snapshot: staleActive }] }).events[0].snapshot.thread.archived,
      true,
    );
    archiveCatalog.threads.find((v) => v.id === 'thread').archived = false;
    await writeFile(catalogFile, JSON.stringify(archiveCatalog));
    await view.workspace();
    assert.equal(
      view.project({ ...history, thread: { ...history.thread, archived: true } }).thread.archived,
      false,
      'Restoring replaces stale archived history',
    );

    const largeCatalog = JSON.parse(await readFile(catalogFile, 'utf8'));
    const unavailable = Array.from({ length: 96 }, (_, index) => ({
      id: 'discovery-timeout-' + index,
      cwd: directory,
      name: 'Unresponsive owner ' + index,
      projectId: null,
    }));
    largeCatalog.threads.push(...unavailable);
    await writeFile(catalogFile, JSON.stringify(largeCatalog));
    const began = Date.now();
    const largeWorkspace = await view.workspace();
    assert(Date.now() - began < 2000, 'Unresponsive owners must not block the complete catalog');
    for (const thread of unavailable)
      assert.equal(
        largeWorkspace.threads.find((value) => value.id === thread.id)?.state,
        'unknown',
      );
    assert.equal(largeWorkspace.threads.find((value) => value.id === 'thread').state, 'running');
    assert.equal(commands.length, commandsBeforeRefresh, 'Background discovery must not execute');
    // A restarted Host has no owner cache, and its old running snapshot may
    // outlive a real completion. The next exact-target command must discover
    // the current owner before deciding whether the target is still busy.
    write({
      type: 'broadcast',
      method: 'client-status-changed',
      params: { clientId: 'owner', status: 'disconnected' },
    });
    await settle();
    assert.equal(restored.history('thread').runtime.canControl, false);
    running = false;
    fixtureTurns[0].status = 'completed';
    revision++;
    queueFixture = true;
    transientOwnerMisses = 1;
    const freshMessageId = 'fresh-owner-after-restart';
    const beforeFresh = commands.filter(
      (command) => command.method === 'thread-follower-start-turn',
    ).length;
    const fresh = await restored.submit({
      hostId: restored.hostId,
      threadId: 'thread',
      clientMessageId: freshMessageId,
      text: 'Targeted owner recovery fixture',
      attachmentIds: [],
      mode: 'next-turn',
    });
    assert.equal(fresh.state, 'submitted');
    assert.equal(
      commands.filter((command) => command.method === 'thread-follower-start-turn').length,
      beforeFresh + 1,
    );
    // The same owner is still alive but its completion broadcast was missed.
    // Owner discovery alone must not reuse the old running snapshot.
    assert.equal(restored.history('thread').runtime.state, 'running');
    fixtureTurns.at(-1).status = 'completed';
    running = false;
    revision++;
    const freshAfterCompletion = await restored.submit({
      hostId: restored.hostId,
      threadId: 'thread',
      clientMessageId: 'same-owner-after-missed-completion',
      text: 'Fresh completion fixture',
      attachmentIds: [],
      mode: 'next-turn',
    });
    assert.equal(freshAfterCompletion.state, 'submitted');
    assert.equal(
      commands.filter((command) => command.method === 'thread-follower-start-turn').length,
      beforeFresh + 2,
    );
    assert.equal(
      (
        await restored.submit({
          hostId: restored.hostId,
          threadId: 'thread',
          clientMessageId: freshMessageId,
          text: 'Targeted owner recovery fixture',
          attachmentIds: [],
          mode: 'next-turn',
        })
      ).turnId,
      fresh.turnId,
    );
    assert.equal(
      commands.filter((command) => command.method === 'thread-follower-start-turn').length,
      beforeFresh + 2,
    );
    fixtureTurns.at(-1).status = 'completed';
    running = false;
    revision++;
    transientOwnerMisses = 3;
    delete restored.state.threads.find((thread) => thread.id === 'thread').desktopCreationProjectId;
    const openedTargets = [];
    restored.desktopCreationDriver = {
      recoverCreationOwner: true,
      openAvailable: true,
      open: async (id) => {
        openedTargets.push(id);
        transientOwnerMisses = 0;
        return true;
      },
      close() {},
    };
    const recoveredReply = {
      hostId: restored.hostId,
      threadId: 'thread',
      clientMessageId: 'unloaded-history-owner-reply',
      text: 'Recover only the original historical target',
      attachmentIds: [],
      mode: 'next-turn',
    };
    const actualFollow = restored.runtime.ipc.follow.bind(restored.runtime.ipc);
    let commandSnapshotMiss = true;
    restored.runtime.ipc.follow = async (id, timeout, fresh) => {
      if (timeout === 15000 && commandSnapshotMiss) {
        commandSnapshotMiss = false;
        return undefined;
      }
      return actualFollow(id, timeout, fresh);
    };
    assert.equal((await restored.submit(recoveredReply)).state, 'submitted');
    assert.deepEqual(openedTargets, ['thread', 'thread']);
    assert.equal(
      commands.filter((command) => command.method === 'thread-follower-start-turn').length,
      beforeFresh + 3,
    );
    await restored.submit(recoveredReply);
    assert.deepEqual(
      openedTargets,
      ['thread', 'thread'],
      'Accepted receipts never activate or execute again',
    );
    assert.equal(
      commands.filter((command) => command.method === 'thread-follower-start-turn').length,
      beforeFresh + 3,
    );
    ownerAvailable = false;
    let lateOpenCalls = 0;
    restored.desktopCreationDriver = {
      open: async (id) => {
        assert.equal(id, 'thread');
        lateOpenCalls++;
        return false;
      },
      close() {},
    };
    const beforeLateCommands = commands.length;
    await restored.activateDesktopThread('thread', 'late-owner-open-operation');
    await settle();
    assert.equal(
      (await restored.desktopOpenStatus('thread', 'late-owner-open-operation')).phase,
      'outcome-unknown',
    );
    ownerAvailable = true;
    const lateStatus = await restored.desktopOpenStatus('thread', 'late-owner-open-operation');
    assert.equal(lateStatus.phase, 'opened');
    assert.equal(lateStatus.error, null);
    await restored.desktopOpenStatus('thread', 'late-owner-open-operation');
    assert.equal(lateOpenCalls, 1, 'Late confirmation must not repeat navigation');
    assert.equal(commands.length, beforeLateCommands, 'Status queries must not execute input');
    view.close();
  } finally {
    view?.close();
    restored.close();
  }
  // New metadata can precede the original desktop owner. A failed first probe
  // must retain priority on the next catalog refresh while cold discovery is
  // still busy. Exercise only catalog refresh, IPC frames and public notification
  // observations; do not open a chat or inspect the session's queue/cache fields.
  for (const { enabled, delayedOwner, label } of [
    { enabled: true, delayedOwner: false, label: 'enabled' },
    { enabled: false, delayedOwner: false, label: 'disabled' },
    { enabled: true, delayedOwner: true, label: 'enabled-late-owner' },
    { enabled: false, delayedOwner: true, label: 'disabled-after-miss' },
  ]) {
    const coldThreads = Array.from({ length: 64 }, (_, index) => ({
      id: `notification-cold-${label}-${index}`,
      cwd: directory,
      name: `Cold history ${index}`,
      projectId: null,
    }));
    const newId = randomUUID();
    const unknownId = `notification-new-unknown-${label}`;
    const runId = `notification-turn-${label}`;
    const requestId = `notification-approval-${label}`;
    const fixture = {
      coldIds: new Set(coldThreads.map((thread) => thread.id)),
      owners: new Map(),
      started: [],
      replied: new Set(),
      replies: [],
      ipcMethods: [],
      active: 0,
      maxActive: 0,
    };
    notificationOwnerFixture = fixture;
    await writeFile(catalogFile, JSON.stringify({ projects: [], threads: coldThreads }));
    const beforeCommands = commands.length;
    const beforeReader = (await readFile(readerRequestsFile, 'utf8')).trim().split('\n').length;
    const notifications = new RuntimeSession({
      directory: join(directory, `notification-session-${label}`),
      root: directory,
      name: 'Notification discovery fixture',
      mode: 'desktop-runtime',
      socket: path,
      executable: readerFixture,
    });
    const observer = new NotificationObserver(notifications.notificationTarget());
    // Keep cold probes outside the IPC timeout until the new catalog is
    // admitted. Wall-clock delays alone can drain the queue on a busy machine.
    const coldBarriers = [];
    const coldIds = new Set(coldThreads.map((thread) => thread.id));
    const ipcRequest = notifications.runtime.ipc.request.bind(notifications.runtime.ipc);
    notifications.runtime.ipc.request = async (method, params, ...args) => {
      if (method === 'thread-owner-discovery' && coldIds.has(params.conversationId))
        await new Promise((resolve) => coldBarriers.push(resolve));
      return ipcRequest(method, params, ...args);
    };
    const releaseCold = () => coldBarriers.splice(0).forEach((resolve) => resolve());
    const advanceDiscovery = async (check, description) => {
      const deadline = Date.now() + 20_000;
      while (!check()) {
        assert(Date.now() < deadline, description);
        releaseCold();
        await new Promise((resolve) => setTimeout(resolve, 20));
      }
    };
    const events = [];
    const observations = [];
    let firstApproval;
    let retryStartIndex;
    const unsubscribe = notifications.onNotificationObservation((value) => {
      observations.push(value);
      const currentEvents = observer.observe(value).events;
      events.push(...currentEvents);
      if (!firstApproval && currentEvents.some((event) => event.kind === 'approval'))
        firstApproval = {
          value,
          coldReplied: coldThreads.filter((thread) => fixture.replied.has(thread.id)).length,
          probes: fixture.started.filter((id) => id === newId).length,
          retryOrder: delayedOwner ? fixture.started.slice(retryStartIndex) : [],
        };
    });
    try {
      await connectUnverifiedBuild(notifications.runtime);
      const coldWorkspace = await notifications.workspace();
      assert(coldWorkspace.threads.every((thread) => thread.state === 'unknown'));
      assert(coldBarriers.length > 0, 'Cold owner discovery must still be in progress');
      assert(fixture.replied.size < coldThreads.length, 'Cold queue must not be drained yet');
      notifications.setNotificationDiscovery(true);
      if (!enabled && !delayedOwner) notifications.setNotificationDiscovery(false);
      const newOwner = {
        clientId: `notification-owner-${label}`,
        state: {
          id: newId,
          hostId: 'local',
          cwd: directory,
          title: 'New desktop approval',
          resumeState: 'resumed',
          threadRuntimeStatus: { type: 'active' },
          turns: [
            {
              turnId: `old-${runId}`,
              status: 'completed',
              items: [
                {
                  id: 'old-input',
                  type: 'userMessage',
                  content: [{ type: 'text', text: '旧任务正文不得作为新任务摘要' }],
                },
              ],
            },
            {
              turnId: runId,
              status: 'inProgress',
              items: [
                {
                  id: 'live-input',
                  type: 'userMessage',
                  content: [
                    {
                      type: 'localImage',
                      path: '/private/fixture/image.png',
                      text: '附件伪文字不得参与摘要',
                    },
                    {
                      type: 'image',
                      image_url: 'data:image/png;base64,ATTACHMENT_BINARY',
                      text: '图片伪文字',
                    },
                    {
                      type: 'text',
                      text: '请修复\t通知\u0000摘要，参考[方案](/private/fixture/plan.md)。\n仅处理本轮。\n\n第二段长正文不应回显。',
                    },
                    {
                      type: 'text',
                      text: '用户附件 "notes.txt"，已上传至本机 "/private/fixture/notes.txt"。按需要用工具读取该文件。',
                    },
                  ],
                },
              ],
            },
          ],
          requests: [
            {
              id: requestId,
              method: 'item/commandExecution/requestApproval',
              params: {
                threadId: newId,
                turnId: runId,
                command: 'fixture only',
                reason: '执行验证 /private/fixture/check.sh',
              },
            },
          ],
        },
      };
      if (!delayedOwner) fixture.owners.set(newId, newOwner);
      fixture.coldIds.add(newId);
      fixture.coldIds.add(unknownId);
      await writeFile(
        catalogFile,
        JSON.stringify({
          projects: [],
          threads: [
            ...coldThreads,
            { id: newId, cwd: directory, name: 'New desktop approval', projectId: null },
            { id: unknownId, cwd: directory, name: 'New unknown chat', projectId: null },
          ],
        }),
      );
      const firstNewWorkspace = await notifications.workspace();
      if (delayedOwner) {
        await advanceDiscovery(
          () => fixture.replies.some((reply) => reply.id === newId),
          'The first newly cataloged target must receive an owner discovery reply',
        );
        assert.deepEqual(
          fixture.replies.filter((reply) => reply.id === newId),
          [{ id: newId, ownerClientId: null }],
          'New metadata must first be probed before its original owner exists',
        );
        assert.equal(fixture.started.filter((id) => id === newId).length, 1);
        assert.equal(
          firstNewWorkspace.threads.find((thread) => thread.id === newId).state,
          'unknown',
        );
        assert.equal(firstApproval, undefined);
        assert.equal(events.length, 0, 'Ownerless metadata must not fabricate notification events');
        const ownerless = notifications.notificationObservation(newId);
        assert.equal(ownerless.live, false);
        assert.equal(ownerless.outcome, undefined);
        assert.equal(ownerless.taskSummary, undefined);
        assert.deepEqual(ownerless.requests, []);
        assert.deepEqual(observer.observe(ownerless).events, []);
        assert(
          coldThreads.filter((thread) => fixture.replied.has(thread.id)).length <=
            coldThreads.length / 2,
          'The first ownerless priority probe must occur well before the cold queue drains',
        );
        // Turning discovery off after the miss must also clear the retained
        // new-target candidate; ordinary catalog discovery remains available.
        if (!enabled) notifications.setNotificationDiscovery(false);
        fixture.owners.set(newId, newOwner);
        retryStartIndex = fixture.started.length;
        await notifications.workspace();
      }
      await advanceDiscovery(
        () => firstApproval,
        `No live approval observed with discovery ${label}`,
      );
      assert.equal(firstApproval.value.live, true);
      assert.equal(firstApproval.value.threadId, newId);
      assert.equal(firstApproval.value.runId, runId);
      assert.equal(firstApproval.value.outcome, 'inProgress');
      assert.equal(
        firstApproval.probes,
        delayedOwner ? 2 : 1,
        'A failed first probe must retry once on catalog refresh, without a tight retry loop',
      );
      assert.deepEqual(
        fixture.replies.filter((reply) => reply.id === newId),
        [
          ...(delayedOwner ? [{ id: newId, ownerClientId: null }] : []),
          { id: newId, ownerClientId: newOwner.clientId },
        ],
        'Only the original owner may establish the live notification snapshot',
      );
      assert.equal(firstApproval.value.taskSummary, '请修复 通知摘要，参考方案。 仅处理本轮。');
      assert.deepEqual(firstApproval.value.requests, [
        { id: requestId, runId, kind: 'approval', taskSummary: '执行验证 [本地路径]' },
      ]);
      const firstEvent = events.find((event) => event.requestId === requestId);
      assert.equal(firstEvent.threadTitle, 'New desktop approval');
      assert.equal(
        firstEvent.taskSummary,
        firstApproval.value.requests[0].taskSummary,
        'The specific approval action takes priority over the original task input',
      );
      const initialColdLastIndex = Math.max(
        ...coldThreads.map((thread) => fixture.started.indexOf(thread.id)),
      );
      if (enabled) {
        assert(
          firstApproval.coldReplied <= coldThreads.length / 2,
          'Subscribed notification discovery must observe approval well before cold history drains',
        );
        assert(
          coldThreads.some((thread) => !fixture.started.includes(thread.id)),
          'New owner must bypass cold chats that have not even been probed',
        );
      } else {
        assert.equal(
          firstApproval.coldReplied,
          coldThreads.length,
          'Disabled notification discovery must preserve ordinary cold queue order',
        );
        if (!delayedOwner) assert(fixture.started.indexOf(newId) > initialColdLastIndex);
        else {
          assert(
            coldThreads.every((thread) => firstApproval.retryOrder.includes(thread.id)),
            'Disabling after an ownerless probe must clear priority for the next refresh',
          );
          assert(
            firstApproval.retryOrder.indexOf(newId) >
              Math.max(...coldThreads.map((thread) => firstApproval.retryOrder.indexOf(thread.id))),
            'The retained candidate must not bypass any cold target after discovery is disabled',
          );
        }
      }
      const unknown = notifications.notificationObservation(unknownId);
      assert.equal(unknown.live, false);
      assert.equal(unknown.outcome, undefined);
      assert.equal(unknown.taskSummary, undefined);
      assert.deepEqual(observer.observe(unknown).events, []);

      const ownerState = fixture.owners.get(newId).state;
      const publish = async (turn, requests = []) => {
        ownerState.turns = [ownerState.turns[0], turn];
        ownerState.requests = requests;
        revision++;
        snapshot(newId);
        await waitForFixture(() => {
          const latest = observations.findLast((value) => value.threadId === newId);
          return latest?.runId === turn.turnId && latest?.outcome === turn.status;
        }, 'Updated live input must arrive over owner IPC');
      };
      const fallbackRun = `fallback-${runId}`;
      await publish(
        {
          turnId: fallbackRun,
          status: 'inProgress',
          items: [
            {
              id: 'image-only',
              type: 'userMessage',
              content: [{ type: 'localImage', path: '/private/fixture/only.png' }],
            },
            {
              id: 'pending-steer',
              type: 'steeringUserMessage',
              status: 'pending',
              input: [{ type: 'text', text: '未接受追加不得成为任务摘要' }],
            },
          ],
        },
        [
          {
            id: requestId,
            method: 'item/commandExecution/requestApproval',
            params: {
              threadId: newId,
              turnId: fallbackRun,
              reason: '验证 "C:\\fixture\\secret file.txt" 与 \\\\server\\share\\secret.txt',
              command: 'irrelevant command',
            },
          },
          {
            id: 'live-question',
            method: 'item/tool/requestUserInput',
            params: {
              threadId: newId,
              turnId: fallbackRun,
              questions: [{ question: '请选择实际部署范围\n\n详细说明', header: '真实问题' }],
            },
          },
          {
            id: 'wrong-turn',
            method: 'item/commandExecution/requestApproval',
            params: { threadId: newId, turnId: runId, reason: '旧任务请求不得参与' },
          },
          {
            id: 'wrong-thread',
            method: 'item/commandExecution/requestApproval',
            params: { threadId: unknownId, turnId: fallbackRun, reason: '其他聊天请求不得参与' },
          },
        ],
      );
      const fallback = notifications.notificationObservation(newId);
      assert.equal(
        fallback.taskSummary,
        undefined,
        'Image-only input cannot inherit the previous turn or pending steer',
      );
      assert.equal(fallback.requests.length, 2);
      const fallbackEvents = observer.observe(fallback).events;
      assert.equal(fallbackEvents[0].taskSummary, '验证 [本地路径] 与 [本地路径]');
      assert.equal(fallbackEvents[1].taskSummary, '请选择实际部署范围');
      assert.notEqual(
        fallbackEvents[0].id,
        firstEvent.id,
        'Reused approval IDs remain isolated by run',
      );

      const boundedRun = `bounded-${runId}`;
      const boundedInput = '任'.repeat(158) + '🚀后续正文';
      await publish(
        {
          turnId: boundedRun,
          status: 'inProgress',
          items: [
            {
              id: 'bounded-user',
              type: 'userMessage',
              content: [{ type: 'text', text: boundedInput }],
            },
          ],
        },
        [
          {
            id: 'bounded-request',
            method: 'item/fileChange/requestApproval',
            params: { threadId: newId, turnId: boundedRun },
          },
        ],
      );
      const bounded = notifications.notificationObservation(newId);
      assert.equal(bounded.taskSummary, '任'.repeat(158) + '…');
      assert(bounded.taskSummary.length <= 160 && bounded.taskSummary.isWellFormed());
      const boundedEvent = observer.observe(bounded).events[0];
      assert.equal(boundedEvent.taskSummary, bounded.taskSummary);

      // A different IPC identity cannot inject user text into the owner's snapshot.
      write({
        type: 'broadcast',
        method: 'thread-stream-state-changed',
        version: 11,
        sourceClientId: 'untrusted-summary-owner',
        params: {
          hostId: 'local',
          conversationId: newId,
          change: {
            type: 'snapshot',
            revision: revision + 100,
            conversationState: {
              ...ownerState,
              turns: [
                {
                  turnId: boundedRun,
                  status: 'inProgress',
                  items: [{ type: 'userMessage', content: [{ type: 'text', text: '伪造摘要' }] }],
                },
              ],
            },
          },
        },
      });
      await settle();
      assert.equal(notifications.notificationObservation(newId).taskSummary, bounded.taskSummary);

      const unknownRun = `unknown-${runId}`;
      const beforeUnknown = events.length;
      await publish({
        turnId: unknownRun,
        status: 'unknown',
        items: [{ type: 'userMessage', content: [{ type: 'text', text: '未知状态不得生成摘要' }] }],
      });
      assert.equal(notifications.notificationObservation(newId).taskSummary, undefined);
      assert.equal(events.length, beforeUnknown, 'Unknown state cannot produce a terminal event');
      const beforeLoss = events.length;
      const beforeLossObservation = observations.length;
      write({
        type: 'broadcast',
        method: 'client-status-changed',
        params: { clientId: `notification-owner-${label}`, status: 'disconnected' },
      });
      await waitForFixture(
        () =>
          observations
            .slice(beforeLossObservation)
            .some((value) => value.threadId === newId && !value.live),
        'Owner loss must produce a non-live observation',
      );
      const lost = notifications.notificationObservation(newId);
      assert.equal(lost.live, false);
      assert.equal(lost.outcome, undefined);
      assert.equal(lost.taskSummary, undefined);
      assert.deepEqual(lost.requests, []);
      assert.equal(events.length, beforeLoss, 'Owner loss must not generate terminal events');
      assert(events.every((event) => ['approval', 'question'].includes(event.kind)));
      assert.equal(fixture.maxActive, 4, 'New discovery must share the four-worker IPC budget');
      assert.equal(commands.length, beforeCommands, 'Discovery must not submit desktop input');
      assert(
        fixture.ipcMethods.every((method) =>
          ['initialize', 'thread-owner-discovery', 'thread-stream-following-changed'].includes(
            method,
          ),
        ),
        'Notification discovery may only initialize, discover owners and follow read-only snapshots',
      );
      const readerRequests = (await readFile(readerRequestsFile, 'utf8'))
        .trim()
        .split('\n')
        .slice(beforeReader)
        .map((line) => JSON.parse(line));
      assert(
        readerRequests.every((request) =>
          ['initialize', 'project/list', 'thread/list'].includes(request.method),
        ),
        'Notification discovery must not read, resume or execute chat history: ' +
          JSON.stringify(
            readerRequests
              .filter(
                (request) =>
                  !['initialize', 'project/list', 'thread/list'].includes(request.method),
              )
              .map((request) => ({ method: request.method, threadId: request.params?.threadId })),
          ),
      );
      console.log(
        `Notification catalog discovery ${label}: ${firstApproval.probes} new-target probe(s)${delayedOwner ? ' (first ownerless, then owner available after catalog refresh)' : ''}; approval observed after ${firstApproval.coldReplied}/${coldThreads.length} cold replies; max concurrent owner probes ${fixture.maxActive}; live task summaries, request fallback, UTF-16 bounds, owner isolation; no history/input or false terminal events. Synthetic fixture only.`,
      );
    } finally {
      unsubscribe();
      notifications.close();
      releaseCold();
      await waitForFixture(() => fixture.active === 0, 'Fixture owner replies must settle');
      notificationOwnerFixture = undefined;
    }
  }
  // A connected peer can transfer one thread while retaining another. A foreign
  // stream is only a hint; hold discovery replies to prove it cannot supply the
  // snapshot before the desktop registry authenticates the current owner.
  for (const enabled of [true, false]) {
    const label = enabled ? 'enabled' : 'disabled';
    const newId = randomUUID();
    const retainedId = randomUUID();
    const oldClientId = `handoff-old-peer-${label}`;
    const newClientId = `handoff-new-peer-${label}`;
    const handoffRun = `handoff-turn-${label}`;
    const handoffRequest = `handoff-approval-${label}`;
    const forgedRun = `forged-turn-${label}`;
    const forgedRequest = `forged-approval-${label}`;
    const makeState = (id, turnId, text, approvalId) => ({
      id,
      hostId: 'local',
      cwd: directory,
      title: 'Notification owner handoff',
      resumeState: 'resumed',
      threadRuntimeStatus: { type: 'active' },
      turns: [
        {
          turnId,
          status: 'inProgress',
          items: [{ type: 'userMessage', content: [{ type: 'text', text }] }],
        },
      ],
      requests: approvalId
        ? [
            {
              id: approvalId,
              method: 'item/commandExecution/requestApproval',
              params: { threadId: id, turnId, reason: '验证接管后的真实审批' },
            },
          ]
        : [],
    });
    const oldOwner = {
      clientId: oldClientId,
      state: makeState(newId, `cached-turn-${label}`, '接管前的缓存任务'),
    };
    const retainedOwner = {
      clientId: oldClientId,
      state: makeState(retainedId, `retained-turn-${label}`, '旧桌面仍持有另一聊天'),
    };
    const nextOwner = {
      clientId: newClientId,
      state: makeState(newId, handoffRun, '接管后的真实任务摘要', handoffRequest),
    };
    const fixture = {
      coldIds: new Set(),
      owners: new Map([
        [newId, oldOwner],
        [retainedId, retainedOwner],
      ]),
      ownerBarriers: new Map(),
      started: [],
      replied: new Set(),
      replies: [],
      followingRequests: [],
      ipcMethods: [],
      active: 0,
      maxActive: 0,
    };
    notificationOwnerFixture = fixture;
    await writeFile(
      catalogFile,
      JSON.stringify({
        projects: [],
        threads: [newId, retainedId].map((id) => ({
          id,
          cwd: directory,
          name: 'Notification owner handoff',
          projectId: null,
        })),
      }),
    );
    const beforeCommands = commands.length;
    const beforeReader = (await readFile(readerRequestsFile, 'utf8')).trim().split('\n').length;
    const notifications = new RuntimeSession({
      directory: join(directory, `notification-handoff-${label}`),
      root: directory,
      name: 'Notification owner handoff fixture',
      mode: 'desktop-runtime',
      socket: path,
      executable: readerFixture,
    });
    const observer = new NotificationObserver(notifications.notificationTarget());
    const observations = [];
    const events = [];
    const unsubscribe = notifications.onNotificationObservation((value) => {
      observations.push(value);
      events.push(...observer.observe(value).events);
    });
    const releaseOwner = (id) => {
      const replies = fixture.ownerBarriers.get(id) ?? [];
      fixture.ownerBarriers.delete(id);
      replies.forEach((respond) => respond());
    };
    const broadcastHint = (clientId, conversationState) => {
      write({
        type: 'broadcast',
        method: 'thread-stream-state-changed',
        version: 11,
        sourceClientId: clientId,
        params: {
          hostId: 'local',
          conversationId: newId,
          change: { type: 'snapshot', revision: revision + 100, conversationState },
        },
      });
    };
    const waitForObservation = (id, turnId, text, since = 0) =>
      waitForFixture(() => {
        const latest = observations.slice(since).findLast((value) => value.threadId === id);
        return latest?.live && latest.runId === turnId && latest.taskSummary === text;
      }, 'The target thread must receive its authenticated live snapshot');
    try {
      await connectUnverifiedBuild(notifications.runtime);
      notifications.setNotificationDiscovery(enabled);
      await notifications.workspace();
      await waitForObservation(newId, oldOwner.state.turns[0].turnId, '接管前的缓存任务');
      await waitForObservation(
        retainedId,
        retainedOwner.state.turns[0].turnId,
        '旧桌面仍持有另一聊天',
      );
      assert.equal(notifications.runtime.ipc.hasOwner(newId), true);
      const initialReplies = fixture.replies.filter((reply) => reply.id === newId);
      assert.deepEqual(initialReplies, [{ id: newId, ownerClientId: oldClientId }]);

      const exerciseHint = async (clientId, hintState, authenticatedOwner) => {
        const beforeObservations = observations.length;
        const beforeEvents = events.length;
        const beforeProbes = fixture.started.filter((id) => id === newId).length;
        const beforeFollows = fixture.followingRequests.length;
        const beforeReplies = fixture.replies.filter((reply) => reply.id === newId).length;
        const barrier = [];
        fixture.ownerBarriers.set(newId, barrier);
        broadcastHint(clientId, hintState);
        await waitForFixture(
          () => barrier.length === 1,
          'A known owner must revalidate even when optional notifications are disabled',
        );
        const cached = notifications.notificationObservation(newId);
        assert.equal(cached.live, true);
        assert.equal(cached.taskSummary, '接管前的缓存任务');
        assert.deepEqual(cached.requests, []);
        assert.equal(events.length, beforeEvents, 'An unauthenticated hint cannot emit approval');
        assert.equal(
          fixture.started.filter((id) => id === newId).length,
          beforeProbes + 1,
          'Known owner recovery must not depend on optional notification discovery',
        );
        assert.equal(fixture.followingRequests.length, beforeFollows);
        assert.equal(fixture.replies.filter((reply) => reply.id === newId).length, beforeReplies);
        releaseOwner(newId);
        await waitForObservation(
          newId,
          authenticatedOwner.state.turns[0].turnId,
          authenticatedOwner.state.turns[0].items[0].content[0].text,
          beforeObservations,
        );
        assert.deepEqual(
          fixture.replies.filter((reply) => reply.id === newId).slice(beforeReplies),
          [{ id: newId, ownerClientId: authenticatedOwner.clientId }],
        );
        assert.deepEqual(fixture.followingRequests.slice(beforeFollows), [
          { id: newId, targets: [authenticatedOwner.clientId] },
        ]);
      };
      await exerciseHint(
        `forged-source-${label}`,
        makeState(newId, forgedRun, '伪造摘要不得接受', forgedRequest),
        oldOwner,
      );
      assert.equal(notifications.notificationObservation(newId).taskSummary, '接管前的缓存任务');
      assert.equal(events.length, 0, 'Forged approval must never become a notification event');

      // Change only the registry's real owner, without disconnecting the old peer.
      fixture.owners.set(newId, nextOwner);
      await exerciseHint(newClientId, nextOwner.state, nextOwner);
      {
        const handedOff = notifications.notificationObservation(newId);
        assert.equal(handedOff.live, true);
        assert.equal(handedOff.runId, handoffRun);
        assert.equal(handedOff.taskSummary, '接管后的真实任务摘要');
        assert.deepEqual(handedOff.requests, [
          {
            id: handoffRequest,
            runId: handoffRun,
            kind: 'approval',
            taskSummary: '验证接管后的真实审批',
          },
        ]);
        assert.equal(events.length, 1, 'The authenticated new owner emits exactly one approval');
        assert.equal(events[0].kind, 'approval');
        assert.equal(events[0].threadId, newId);
        assert.equal(events[0].requestId, handoffRequest);
        assert.equal(events[0].taskSummary, handedOff.requests[0].taskSummary);
      }
      assert(observations.every((value) => value.runId !== forgedRun));
      assert(observations.every((value) => value.taskSummary !== '伪造摘要不得接受'));
      assert(events.every((event) => event.requestId !== forgedRequest));

      const beforeRetained = observations.length;
      retainedOwner.state = makeState(retainedId, `retained-update-${label}`, '旧桌面连接持续可用');
      revision++;
      snapshot(retainedId);
      await waitForObservation(
        retainedId,
        `retained-update-${label}`,
        '旧桌面连接持续可用',
        beforeRetained,
      );
      assert.equal(notifications.notificationObservation(newId).live, true);
      assert.equal(commands.length, beforeCommands, 'Owner hints must not submit desktop input');
      assert(
        fixture.ipcMethods.every((method) =>
          ['initialize', 'thread-owner-discovery', 'thread-stream-following-changed'].includes(
            method,
          ),
        ),
        'Owner handoff uses only discovery and read-only following',
      );
      const readerRequests = (await readFile(readerRequestsFile, 'utf8'))
        .trim()
        .split('\n')
        .slice(beforeReader)
        .map((line) => JSON.parse(line));
      assert(
        readerRequests.every((request) =>
          ['initialize', 'project/list', 'thread/list'].includes(request.method),
        ),
        'Owner hints must not read, resume or execute chat history',
      );
      console.log(
        `Notification owner handoff ${label}: cached live owner, forged source rejected, registry-authenticated new summary and approval, old peer remains live; no input/history commands. Synthetic fixture only.`,
      );
    } finally {
      unsubscribe();
      notifications.close();
      for (const id of fixture.ownerBarriers.keys()) releaseOwner(id);
      await waitForFixture(() => fixture.active === 0, 'Handoff owner replies must settle');
      notificationOwnerFixture = undefined;
    }
  }
  console.log(
    'Desktop IPC boundary passed: advisory unknown/stale build labels, private socket, fragmented frames, owner isolation, live patches, compaction lifecycle and owner/restart recovery, revision-gap resync, renderer-safe start/steer text inputs, exact-turn stop, approval routing, unloaded rejection, profile/owner loss and live owner discovery of catalog-hidden chats. Synthetic fixture only.',
  );
} finally {
  childProcess.spawn = originalSpawn;
  syncBuiltinESMExports();
  client.close();
  socket?.destroy();
  await new Promise((resolve) => server.close(resolve));
  await rm(directory, { recursive: true, force: true });
  await rm(ownerDirectory, { recursive: true, force: true });
  await rm(deniedDirectory, { recursive: true, force: true });
  if (process.platform === 'win32') {
    if (previousCodexHome === undefined) delete process.env.CODEX_HOME;
    else process.env.CODEX_HOME = previousCodexHome;
  }
}
