import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import {
  mkdtemp,
  mkdir,
  writeFile,
  rm,
  rename,
  stat,
  utimes,
  symlink,
  truncate,
} from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { setTimeout } from 'node:timers/promises';
import {
  AttachmentFiles,
  AttachmentFileError,
} from '../packages/codex-adapter/dist/attachment-files.js';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopWorkspaceView } from '../packages/codex-adapter/dist/desktop-workspace-view.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopTranscription } from '../packages/codex-adapter/dist/desktop-transcription.js';

const directory = await mkdtemp(join(tmpdir(), 'attachment-performance-'));
const root = join(directory, 'root');
const outside = join(directory, 'outside');
await mkdir(root);
await mkdir(outside);
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const full = process.env.ATTACHMENT_PERF_FULL === '1';
let service;
const originalConnect = RuntimeSession.prototype.connect;
const originalWorkspace = DesktopWorkspaceView.prototype.workspace;
const originalTranscriptionReadiness = DesktopTranscription.prototype.readiness;
const snapshots = new Map();
let refreshes = 0;
try {
  const files = new AttachmentFiles(3);
  const path = join(root, 'identity.bin');
  await writeFile(path, 'one');
  let scans = 0;
  const scan = files.scan.bind(files);
  files.scan = (...args) => {
    scans++;
    return scan(...args);
  };
  const first = await files.inspect(path, [root]);
  assert.equal(first.sha256, sha('one'));
  await Promise.all(Array.from({ length: 20 }, () => files.inspect(path, [root])));
  assert.equal(scans, 1, 'unchanged snapshots must not re-read/hash bytes');
  await assert.rejects(files.inspect(path, [outside]), (error) => error.status === 403);
  const prior = await stat(path);
  await writeFile(path, 'two');
  await utimes(path, prior.atime, prior.mtime);
  const modified = await files.inspect(path, [root]);
  assert.equal(modified.sha256, sha('two'), 'same size/restored mtime must invalidate by ctime');
  await assert.rejects(files.read(path, [root], first.sha256), (error) => error.status === 409);
  await writeFile(path + '.new', 'six');
  await utimes(path + '.new', prior.atime, prior.mtime);
  await rename(path + '.new', path);
  const replacement = await files.inspect(path, [root]);
  assert.equal(replacement.sha256, sha('six'), 'same-path replacement must invalidate');
  assert.equal((await files.read(path, [root], replacement.sha256)).toString(), 'six');
  await rm(path);
  await assert.rejects(files.inspect(path, [root]));
  await assert.rejects(
    files.read(path, [root], replacement.sha256),
    (error) => error.status === 410 && error.code === 'ENOENT',
  );
  await writeFile(join(outside, 'secret'), 'outside');
  await symlink(join(outside, 'secret'), path);
  await assert.rejects(files.inspect(path, [root]), (error) => error.status === 403);
  await assert.rejects(files.read(path, [root], sha('outside')), (error) => error.status === 403);
  await rm(path);
  for (let index = 0; index < 5; index++) {
    const target = join(root, `capacity-${index}`);
    await writeFile(target, 'bounded');
    await files.inspect(target, [root]);
  }
  assert.equal(files.cache.size, 3);
  const cold = join(root, 'dedupe.bin');
  await writeFile(cold, Buffer.alloc(16 * 1024 * 1024, 7));
  const beforeDedupe = scans;
  await Promise.all(Array.from({ length: 20 }, () => files.inspect(cold, [root])));
  assert.equal(scans, beforeDedupe + 1, 'concurrent cold references share the same scan');
  await Promise.all([
    files.inspect(cold, [root]),
    assert.rejects(files.inspect(cold, [outside]), (error) => error.status === 403),
  ]);
  // Change the real file between reading its bytes and final path/identity validation.
  const checked = files.checked.bind(files);
  let validations = 0;
  files.checked = async (...args) => {
    if (++validations === 2) await writeFile(cold, 'changed-during-read');
    return checked(...args);
  };
  await assert.rejects(
    files.read(cold, [root], sha(Buffer.alloc(16 * 1024 * 1024, 7))),
    (error) => error.status === 409,
  );
  files.checked = checked;
  const largePath = join(root, 'large.bin');
  const largeBytes = Buffer.alloc(24 * 1024 * 1024 + 1, 37);
  await writeFile(largePath, largeBytes);
  const large = await files.inspect(largePath, [root]);
  assert.equal(large.sizeBytes, largeBytes.length);
  assert.equal(large.sha256, sha(largeBytes));
  assert.deepEqual(await files.read(largePath, [root], large.sha256), largeBytes);
  await assert.rejects(files.inspect(largePath, [outside]), (error) => error.status === 403);

  const count = full ? 513 : 16;
  const paths = [];
  let totalBytes = 0;
  for (let index = 0; index < count; index++) {
    const size = (full ? (index < 80 ? 4 : 1.25) : 1) * 1024 * 1024;
    const target = join(root, `file-${index}.bin`);
    await writeFile(target, Buffer.alloc(size, index % 251));
    paths.push(target);
    totalBytes += size;
  }
  const threads = Array.from({ length: full ? 101 : 4 }, (_, index) => ({
    id: `thread-${index}`,
    projectId: 'project',
    projectIds: ['project'],
    title: 'Fixture',
    state: 'unknown',
    messages: [],
    plan: [],
    turnId: null,
    error: null,
  }));
  const catalog = {
    projects: [{ id: 'project', name: 'Fixture', roots: [root] }],
    threads,
    nextProjectCursor: null,
    nextThreadCursor: null,
  };
  threads.forEach((thread, index) => {
    const assigned = paths.filter((_, file) => file % threads.length === index);
    snapshots.set(thread.id, {
      id: thread.id,
      hostId: 'local',
      cwd: root,
      title: 'Fixture',
      threadRuntimeStatus: { type: 'idle' },
      turns: [
        {
          turnId: `turn-${index}`,
          status: 'completed',
          items: assigned.map((path, item) => ({
            id: `message-${item}`,
            type: 'agentMessage',
            text: `[file](${path})`,
          })),
        },
      ],
      requests: [],
    });
  });
  // Fixture transport only: use the production HTTP server and projection code,
  // without opening a desktop, loading real history, or starting model execution.
  RuntimeSession.prototype.connect = async function () {
    Object.defineProperty(this.runtime, 'ready', { value: true });
    this.runtime.ipc.hasOwner = (id) => snapshots.has(id);
    this.runtime.ipc.state = (id) => snapshots.get(id);
    this.runtime.ipc.follow = async (id) => snapshots.get(id);
    this.runtime.ipc.refreshSnapshot = (id) => {
      refreshes++;
      this.runtime.onEvent('desktop/snapshot', { threadId: id, state: snapshots.get(id) });
    };
    this.runtime.reader.workspace = async () => catalog;
    this.state.projects = catalog.projects.map((v) => ({ ...v, hostId: this.hostId }));
    this.state.threads = threads.map((v) => ({ ...v, hostId: this.hostId }));
  };
  DesktopWorkspaceView.prototype.workspace = function () {
    return this.session.workspace();
  };
  // This attachment transport fixture must not start desktop auth helpers.
  DesktopTranscription.prototype.readiness = async () => ({
    available: false,
    reason: 'Attachment fixture has no desktop dictation',
    principal: null,
  });
  const token = randomUUID() + randomUUID();
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    HOST_AGENT_STATE_DIR: join(directory, 'state'),
    HOST_AGENT_ROOT: root,
    HOST_AGENT_TOKEN: token,
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
  });
  service = await startRuntimeServer();
  const endpoint = `http://127.0.0.1:${service.server.address().port}`;
  const session = service.session;
  let actualScans = 0;
  const actualScan = session.attachmentFiles.scan.bind(session.attachmentFiles);
  session.attachmentFiles.scan = (...args) => {
    actualScans++;
    return actualScan(...args);
  };
  let inspections = 0,
    checks = 0;
  const actualInspect = session.attachmentFiles.inspect.bind(session.attachmentFiles);
  const actualChecked = session.attachmentFiles.checked.bind(session.attachmentFiles);
  session.attachmentFiles.inspect = (...args) => {
    inspections++;
    return actualInspect(...args);
  };
  session.attachmentFiles.checked = (...args) => {
    checks++;
    return actualChecked(...args);
  };
  const observations = [];
  async function request(path, authorization = token, status = 200) {
    const start = performance.now();
    const response = await fetch(endpoint + path, {
      headers: { Authorization: `Bearer ${authorization}` },
      signal: AbortSignal.timeout(5000),
    });
    assert.equal(response.status, status, path);
    const body = await response.arrayBuffer();
    observations.push({ route: path.split('?')[0], ms: performance.now() - start });
    return body;
  }
  const start = performance.now();
  const cpu = process.cpuUsage();
  let completed = false;
  const coldScan = Promise.all(threads.map((thread) => session.open(thread.id))).then((v) => {
    completed = true;
    return v;
  });
  for (let round = 0; !completed || round < 12; round++) {
    await Promise.all([
      request('/healthz'),
      request('/v1/workspace'),
      request('/v1/workspace', 'wrong', 401),
    ]);
    await setTimeout(10);
  }
  const opened = await coldScan;
  assert.equal(opened.flatMap((v) => v.messages.flatMap((m) => m.attachments)).length, 0);
  for (const openedThread of opened) {
    for (const message of openedThread.messages) assert.match(message.text, /^\[file\]\(/);
  }
  assert.equal(actualScans, 0, 'desktop projection must not scan ordinary links');
  assert.equal(inspections, 0, 'desktop projection must not inspect ordinary links');
  assert.equal(checks, 0, 'desktop projection must not stat ordinary links');
  const coldMs = performance.now() - start;
  const coldCPU = process.cpuUsage(cpu);
  const scansAfterCold = actualScans;
  await Promise.all(threads.map((thread) => session.open(thread.id)));
  assert.equal(actualScans, scansAfterCold, 'warm snapshots must not hash attachment files');
  const beforeRefresh = refreshes;
  await session.workspace();
  assert(refreshes - beforeRefresh <= 2, 'workspace must not refresh every owner');
  await Promise.all([...session.desktopProjections.values()].map((job) => job.work));
  const firstThread = opened[0];
  const fileMessageId = firstThread.messages[0].id;
  const attachment = (
    await session.resolveMessageFile(firstThread.threadId, fileMessageId, paths[0])
  ).attachment;
  const bytes = await request(
    `/v1/attachments?${new URLSearchParams({
      hostId: session.hostId,
      threadId: firstThread.threadId,
      id: attachment.id,
    })}`,
  );
  assert.equal(sha(Buffer.from(bytes)), attachment.sha256, 'HTTP download retains full content');
  const owner = snapshots.get(firstThread.threadId);
  const active = {
    ...owner,
    threadRuntimeStatus: { type: 'active' },
    requests: [
      {
        id: 'approval',
        method: 'item/commandExecution/requestApproval',
        params: { threadId: firstThread.threadId, turnId: 'active', command: 'fixture' },
      },
    ],
    turns: [
      ...owner.turns,
      {
        turnId: 'active',
        status: 'inProgress',
        items: [{ id: 'latest', type: 'agentMessage', text: 'first' }],
      },
    ],
  };
  const latest = structuredClone(active);
  latest.turns.at(-1).items[0].text = 'latest-complete-message';
  const work = session.desktopSnapshot(session.thread(firstThread.threadId), active);
  assert.equal(session.history(firstThread.threadId).runtime.state, 'waiting-for-approval');
  for (let repeat = 0; repeat < 20; repeat++)
    assert.equal(session.desktopSnapshot(session.thread(firstThread.threadId), latest), work);
  await work;
  assert.equal(
    session.history(firstThread.threadId).messages.at(-1).text,
    'latest-complete-message',
  );
  assert.equal(session.desktopProjections.size, 0);
  // An older projection must not publish after owner loss and a new projection.
  const project = session.projectDesktopMessages.bind(session);
  let release;
  const gate = new Promise((done) => {
    release = done;
  });
  let hold = true;
  session.projectDesktopMessages = async (...args) => {
    if (hold) {
      hold = false;
      await gate;
    }
    return project(...args);
  };
  const stale = session.desktopSnapshot(session.thread(firstThread.threadId), active);
  session.event('desktop/unavailable', { threadId: firstThread.threadId });
  await stale;
  const recovered = session.desktopSnapshot(session.thread(firstThread.threadId), latest);
  release();
  await recovered;
  assert.equal(
    session.history(firstThread.threadId).messages.at(-1).text,
    'latest-complete-message',
  );
  session.projectDesktopMessages = project;
  assert.equal(
    actualScans,
    scansAfterCold + 2,
    'only explicit resolution and download scan the selected file',
  );
  const warmHashes = actualScans - scansAfterCold - 2;
  const downloadRoute = (id) =>
    `/v1/attachments?${new URLSearchParams({
      hostId: session.hostId,
      threadId: firstThread.threadId,
      id,
    })}`;
  await writeFile(paths[0], 'HTTP_CHANGED');
  await request(downloadRoute(attachment.id), token, 409);
  const resolveRoute = (id = attachment.id, threadId = firstThread.threadId) =>
    `/v1/attachments/resolve?${new URLSearchParams({ hostId: session.hostId, threadId, id })}`;
  await request(resolveRoute(), 'wrong-token', 401);
  await request(resolveRoute(attachment.id, threads[1].id), token, 410);
  await request(resolveRoute() + '&path=' + encodeURIComponent(paths[0]), token, 400);
  await request(resolveRoute() + '&id=' + attachment.id, token, 400);
  const resolution = JSON.parse(Buffer.from(await request(resolveRoute())).toString());
  assert.equal(resolution.sourceAttachmentId, attachment.id);
  assert.equal(resolution.attachment.sha256, sha('HTTP_CHANGED'));
  assert(!('path' in resolution.attachment));
  assert.equal(
    Buffer.from(await request(downloadRoute(resolution.attachment.id))).toString(),
    'HTTP_CHANGED',
    'a stale link resolves current bytes without reopening the conversation',
  );
  const changedHistory = await session.open(firstThread.threadId);
  assert.deepEqual(changedHistory.messages.find((m) => m.id === fileMessageId).attachments, []);
  const changedAttachment = (
    await session.resolveMessageFile(firstThread.threadId, fileMessageId, paths[0])
  ).attachment;
  assert.equal(changedAttachment.sha256, sha('HTTP_CHANGED'));
  assert.equal(
    Buffer.from(await request(downloadRoute(changedAttachment.id))).toString(),
    'HTTP_CHANGED',
  );
  await writeFile(paths[0] + '.replacement', 'HTTP_REPLACED');
  await rename(paths[0] + '.replacement', paths[0]);
  await request(downloadRoute(changedAttachment.id), token, 409);
  const replacedHistory = await session.open(firstThread.threadId);
  assert.deepEqual(replacedHistory.messages.find((m) => m.id === fileMessageId).attachments, []);
  const replacedAttachment = (
    await session.resolveMessageFile(firstThread.threadId, fileMessageId, paths[0])
  ).attachment;
  assert.equal(replacedAttachment.sha256, sha('HTTP_REPLACED'));
  await rm(paths[0]);
  await request(resolveRoute(), token, 410);
  await request(downloadRoute(replacedAttachment.id), token, 410);
  assert.equal(
    (await session.open(firstThread.threadId)).messages.find((m) => m.id === fileMessageId)
      .attachments.length,
    0,
  );

  // A real sparse disk image must remain a link, without entering scan at all.
  const disk = join(root, 'disk.raw');
  const onDemand = join(root, 'on-demand.bin');
  await writeFile(disk, '');
  // POSIX truncate creates holes; Windows must not allocate a near-terabyte file.
  await truncate(disk, process.platform === 'win32' ? 32 * 1024 ** 2 : 926 * 1024 ** 3);
  await writeFile(onDemand, '');
  const onDemandSize = 8 * 1024 * 1024 + 1;
  await truncate(onDemand, onDemandSize);
  const config = join(root, 'config.toml');
  const database = join(root, 'state.sqlite');
  const small = join(root, 'small.md');
  await writeFile(config, 'fixture config');
  await writeFile(database, 'fixture database');
  await writeFile(small, 'small report');
  const specialId = 'sparse-projection';
  const special = {
    ...threads[0],
    id: specialId,
    hostId: session.hostId,
    messages: [],
  };
  session.state.threads.push(special);
  const sparseState = {
    ...snapshots.get(threads[0].id),
    id: specialId,
    turns: [
      {
        turnId: 'sparse-turn',
        status: 'completed',
        items: [
          { id: 'before', type: 'agentMessage', text: 'before large link' },
          { id: 'disk-message', type: 'agentMessage', text: `[disk](${disk})` },
          { id: 'demand-message', type: 'agentMessage', text: `[demand](${onDemand})` },
          { id: 'small', type: 'agentMessage', text: `[small](${small})` },
          { id: 'config', type: 'agentMessage', text: `[config](${config})` },
          { id: 'database', type: 'agentMessage', text: `[database](${database})` },
          {
            id: 'command',
            type: 'commandExecution',
            status: 'completed',
            command: 'fixture',
            aggregatedOutput: `[fake](${small})`,
          },
          { id: 'after', type: 'agentMessage', text: 'after large link' },
        ],
      },
    ],
  };
  snapshots.set(specialId, sparseState);
  const discovered = session.history(specialId, true);
  assert.equal(discovered.messages.length, 0);
  assert.notEqual(discovered.synchronization.state, 'live', 'owner discovery alone is not live');
  const emptyId = 'verified-empty';
  session.state.threads.push({ ...special, id: emptyId, messages: [] });
  const emptyState = { ...sparseState, id: emptyId, turns: [] };
  snapshots.set(emptyId, emptyState);
  assert.notEqual(session.history(emptyId, true).synchronization.state, 'live');
  await session.desktopSnapshot(session.thread(emptyId), emptyState);
  const verifiedEmpty = session.history(emptyId, true);
  assert.equal(verifiedEmpty.messages.length, 0);
  assert.equal(verifiedEmpty.synchronization.state, 'live', 'verified empty history can be live');
  const scannedPaths = [];
  session.attachmentFiles.scan = (...args) => {
    scannedPaths.push(args[0]);
    return actualScan(...args);
  };
  const beforeSpecial = { inspections, checks, scans: actualScans };
  const originalProject = session.projectDesktopMessages.bind(session);
  let releaseProjection;
  const projectionGate = new Promise((resolve) => {
    releaseProjection = resolve;
  });
  let projectionCalls = 0;
  let childLabel = 'before child update';
  const projectSubagent = session.runtime.projectSubagent.bind(session.runtime);
  session.runtime.projectSubagent = (...args) => {
    const projected = projectSubagent(...args);
    return args[0].id === 'before' ? { ...projected, text: childLabel } : projected;
  };
  session.loaded.add(specialId);
  session.runtime.subagentParents.set('fixture-child-update', new Set([specialId]));
  session.projectDesktopMessages = async (...args) => {
    projectionCalls++;
    await projectionGate;
    const result = await originalProject(...args);
    if (projectionCalls === 1) {
      childLabel = 'after child update';
      // A real child-state publication reuses the parent's exact state object.
      session.runtime.publishSubagentParents('fixture-child-update');
    }
    return result;
  };
  try {
    const pending = session.desktopSnapshot(session.thread(specialId), sparseState);
    assert.notEqual(
      session.history(specialId, true).synchronization.state,
      'live',
      'pending is not live',
    );
    for (let index = 0; index < 10; index++)
      assert.equal(session.desktopSnapshot(session.thread(specialId), sparseState), pending);
    releaseProjection();
    await pending;
    assert.equal(projectionCalls, 2, 'bursts coalesce while derived child updates are retained');
  } finally {
    releaseProjection();
    session.projectDesktopMessages = originalProject;
    session.runtime.projectSubagent = projectSubagent;
    session.runtime.subagentParents.delete('fixture-child-update');
  }
  const projected = session.history(specialId, true);
  assert.equal(projected.messages[0].text, 'after child update');
  assert.equal(projected.synchronization.state, 'live');
  assert.deepEqual(
    projected.messages.map((message) => message.id),
    ['before', 'disk-message', 'demand-message', 'small', 'config', 'database', 'command', 'after'],
  );
  assert.equal(projected.messages[1].text, `[disk](${disk})`);
  assert.equal(projected.messages[2].text, `[demand](${onDemand})`);
  assert.equal(projected.messages[1].attachments.length, 0);
  assert.equal(projected.messages[2].attachments.length, 0);
  for (const [id, path] of [
    ['small', small],
    ['config', config],
    ['database', database],
  ]) {
    const message = projected.messages.find((item) => item.id === id);
    assert.equal(message.text, `[${id}](${path})`);
    assert.deepEqual(message.attachments, []);
  }
  assert.deepEqual(projected.messages.find((item) => item.id === 'command').attachments, []);
  assert.deepEqual(
    { inspections, checks, scans: actualScans },
    beforeSpecial,
    'ordinary links and command output must cause zero inspection, stat, or scan',
  );
  assert(
    !scannedPaths.includes(disk) && !scannedPaths.includes(onDemand),
    'automatic projection must not scan ordinary files',
  );
  await assert.rejects(
    session.resolveMessageFile(threads[1].id, 'demand-message', onDemand),
    (error) => error.status === 403,
  );
  await assert.rejects(
    session.resolveMessageFile(specialId, 'before', onDemand),
    (error) => error.status === 403,
  );
  const resolved = await session.resolveMessageFile(specialId, 'demand-message', onDemand);
  assert.equal(resolved.attachment.sizeBytes, onDemandSize);
  assert.equal(resolved.attachment.sha256, sha(Buffer.alloc(onDemandSize)));
  assert(scannedPaths.includes(onDemand), 'explicit resolution reads the selected file');
  const downloaded = await session.download(specialId, resolved.attachment.id);
  assert.equal(downloaded.data.length, onDemandSize);
  assert.equal(sha(downloaded.data), resolved.attachment.sha256);
  assert(!scannedPaths.includes(disk), 'the unrelated disk image must remain unread');
  const media = join(root, 'user-media.bin');
  await writeFile(media, '');
  await truncate(media, onDemandSize);
  const mediaState = {
    ...sparseState,
    turns: [
      {
        turnId: 'media-turn',
        status: 'completed',
        items: [
          {
            id: 'user-media',
            type: 'userMessage',
            content: [{ type: 'localImage', path: media }],
          },
        ],
      },
    ],
  };
  await session.desktopSnapshot(session.thread(specialId), mediaState);
  const userMedia = session
    .history(specialId)
    .messages.find((message) => message.id === 'user-media');
  assert.equal(userMedia.attachments.length, 1, 'non-link user media retains its attachment');
  assert.equal(userMedia.attachments[0].sizeBytes, onDemandSize);
  assert(scannedPaths.includes(media), 'non-link media keeps its existing inspection behavior');
  const beforeBusy = session.history(specialId).messages;
  const inspect = session.attachmentFiles.inspect.bind(session.attachmentFiles);
  session.attachmentFiles.inspect = async () => {
    throw new AttachmentFileError(503, 'busy');
  };
  try {
    await assert.rejects(
      session.desktopSnapshot(session.thread(specialId), mediaState),
      (error) => error.status === 503,
    );
    assert.equal(
      session.history(specialId).messages,
      beforeBusy,
      'overload must retain complete messages instead of publishing missing attachments',
    );
  } finally {
    session.attachmentFiles.inspect = inspect;
  }
  await symlink(join(outside, 'secret'), paths[0]);
  await request(resolveRoute(), token, 403);
  await request(downloadRoute(replacedAttachment.id), token, 403);
  assert.equal(
    (await session.open(firstThread.threadId)).messages.find((m) => m.id === fileMessageId)
      .attachments.length,
    0,
  );
  const maxHTTP = Math.max(...observations.map((v) => v.ms));
  assert(maxHTTP < 2000, `HTTP stalled during cold scan: ${maxHTTP} ms`);
  console.log(
    JSON.stringify(
      {
        fixture: true,
        full,
        uniqueFiles: count,
        totalBytes,
        coldScanMs: Math.round(coldMs),
        coldCPUms: Math.round((coldCPU.user + coldCPU.system) / 1000),
        httpRequests: observations.length,
        maxHTTPms: Math.round(maxHTTP),
        coldHashes: scansAfterCold,
        warmHashes,
        routes: [...new Set(observations.map((v) => v.route))],
      },
      null,
      2,
    ),
  );
  console.log(
    'Attachment regression passed: zero-read link projection/HTTP, warm cache, concurrent dedupe, latest snapshot, immediate approvals, identity changes, deletion, symlink roots, limits, full downloads. Fixture evidence only.',
  );
} finally {
  service?.close();
  RuntimeSession.prototype.connect = originalConnect;
  DesktopWorkspaceView.prototype.workspace = originalWorkspace;
  DesktopTranscription.prototype.readiness = originalTranscriptionReadiness;
  await rm(directory, { recursive: true, force: true });
}
