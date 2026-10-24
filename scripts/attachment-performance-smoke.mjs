import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { mkdtemp, mkdir, writeFile, rm, rename, stat, utimes, symlink } from 'node:fs/promises';
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
  await writeFile(join(root, 'oversized.bin'), Buffer.alloc(20 * 1024 * 1024 + 1));
  await assert.rejects(
    files.inspect(join(root, 'oversized.bin'), [root]),
    (e) => e.status === 413 && e.code === 'FILE_TOO_LARGE',
  );

  const count = full ? 513 : 16;
  const paths = [];
  let totalBytes = 0;
  for (let index = 0; index < count; index++) {
    const size = (full ? (index < 80 ? 16 : 1.25) : 16) * 1024 * 1024;
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
  assert.equal(opened.flatMap((v) => v.messages.flatMap((m) => m.attachments)).length, count);
  assert.equal(
    actualScans,
    count,
    'all cold files hashed exactly once despite workspace snapshots',
  );
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
  const attachment = firstThread.messages[0].attachments[0];
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
    scansAfterCold + 1,
    'only the download re-hashes after warm projections',
  );
  const warmHashes = actualScans - scansAfterCold - 1;
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
  const changedAttachment = changedHistory.messages.find((m) => m.id === fileMessageId)
    .attachments[0];
  assert.equal(changedAttachment.sha256, sha('HTTP_CHANGED'));
  assert.equal(
    Buffer.from(await request(downloadRoute(changedAttachment.id))).toString(),
    'HTTP_CHANGED',
  );
  await writeFile(paths[0] + '.replacement', 'HTTP_REPLACED');
  await rename(paths[0] + '.replacement', paths[0]);
  await request(downloadRoute(changedAttachment.id), token, 409);
  const replacedHistory = await session.open(firstThread.threadId);
  const replacedAttachment = replacedHistory.messages.find((m) => m.id === fileMessageId)
    .attachments[0];
  assert.equal(replacedAttachment.sha256, sha('HTTP_REPLACED'));
  await rm(paths[0]);
  await request(resolveRoute(), token, 410);
  await request(downloadRoute(replacedAttachment.id), token, 410);
  assert.equal(
    (await session.open(firstThread.threadId)).messages.find((m) => m.id === fileMessageId)
      .attachments.length,
    0,
  );
  const beforeBusy = session.history(firstThread.threadId).messages;
  const inspect = session.attachmentFiles.inspect.bind(session.attachmentFiles);
  session.attachmentFiles.inspect = async () => {
    throw new AttachmentFileError(503, 'busy');
  };
  await assert.rejects(session.open(firstThread.threadId), (error) => error.status === 503);
  assert.equal(
    session.history(firstThread.threadId).messages,
    beforeBusy,
    'overload must retain complete messages instead of publishing missing attachments',
  );
  session.attachmentFiles.inspect = inspect;
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
    'Attachment regression passed: bounded cold scan/HTTP, warm cache, concurrent dedupe, latest snapshot, immediate approvals, identity changes, deletion, symlink roots, limits, full downloads. Fixture evidence only.',
  );
} finally {
  service?.close();
  RuntimeSession.prototype.connect = originalConnect;
  DesktopWorkspaceView.prototype.workspace = originalWorkspace;
  DesktopTranscription.prototype.readiness = originalTranscriptionReadiness;
  await rm(directory, { recursive: true, force: true });
}
