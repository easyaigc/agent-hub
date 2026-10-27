import assert from 'node:assert/strict';
import {
  mkdtemp,
  mkdir,
  writeFile,
  symlink,
  rm,
  realpath,
  readFile,
  chmod,
  rename,
} from 'node:fs/promises';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { request as httpRequest } from 'node:http';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';
import { DesktopTranscription } from '../packages/codex-adapter/dist/desktop-transcription.js';

const base = await realpath(await mkdtemp(join(tmpdir(), 'message-file-resources-')));
const state = join(base, 'state'),
  root = join(base, 'project'),
  outside = join(base, 'reports');
for (const path of [state, root, outside]) await mkdir(path, { mode: 0o700 });
const report = join(outside, 'report.md'),
  sibling = join(outside, 'private.txt'),
  missing = join(outside, 'missing.md');
const options = {
  mode: 'desktop-runtime',
  directory: state,
  root,
  name: 'Message resource fixture',
};
let session, service;
const originalConnect = RuntimeSession.prototype.connect,
  originalReadiness = DesktopTranscription.prototype.readiness;
try {
  await writeFile(report, 'CURRENT_REPORT_ONE');
  await writeFile(sibling, 'UNREFERENCED');
  session = new RuntimeSession(options);
  session.state.threads = ['authorized', 'other'].map((id) => ({
    id,
    hostId: session.hostId,
    projectId: null,
    title: id,
    state: 'unknown',
    turnId: null,
    plan: [],
    messages: [],
    error: null,
  }));
  async function project(threadId, path, id, role = 'assistant') {
    const thread = session.state.threads.find((t) => t.id === threadId);
    await session.upsert(thread, {
      type: role === 'assistant' ? 'agentMessage' : 'userMessage',
      id,
      text: `[report](${path})`,
    });
    return thread.messages.find((m) => m.id === id);
  }
  const message = await project('authorized', report, 'original-report');
  assert.equal(
    message.attachments.length,
    1,
    'a desktop assistant report outside project roots is a registered resource',
  );
  assert.match(message.text, /codex-attachment:\/\//);
  const first = message.attachments[0];
  assert.equal(
    first.desktopMessageLinks,
    undefined,
    'private source proofs must not leak into the wire attachment',
  );
  assert.equal(
    (await session.download('authorized', first.id)).data.toString(),
    'CURRENT_REPORT_ONE',
  );
  await rename(root, root + '-moved');
  assert.equal(
    (await session.download('authorized', first.id)).data.toString(),
    'CURRENT_REPORT_ONE',
    'An obsolete global root cannot disable an exact registered resource',
  );
  await rename(root + '-moved', root);
  await assert.rejects(session.download('other', first.id), (error) => error.status === 404);
  await assert.rejects(
    session.resolveMessageFile('other', 'original-report', report),
    (error) => error.status === 403,
  );
  await assert.rejects(
    session.resolveMessageFile('authorized', 'original-report', sibling),
    (error) => error.status === 403,
  );
  assert.equal((await project('authorized', sibling, 'user-link', 'user')).attachments.length, 0);
  await assert.rejects(
    session.resolveMessageFile('authorized', 'user-link', sibling),
    (error) => error.status === 403,
  );
  await writeFile(report, 'CURRENT_REPORT_TWO');
  const current = (await session.resolveAttachment('authorized', first.id)).attachment;
  assert.notEqual(current.sha256, first.sha256);
  assert.equal(
    (await session.download('authorized', current.id)).data.toString(),
    'CURRENT_REPORT_TWO',
  );
  assert.equal(
    (await session.resolveMessageFile('authorized', 'original-report', report)).attachment.id,
    current.id,
    'a cached raw link must resolve after the Host rewrites that message',
  );
  if (process.platform !== 'win32') {
    await chmod(report, 0o000);
    await assert.rejects(
      session.resolveAttachment('authorized', current.id),
      (error) => error.status === 403 && error.code === 'EACCES',
    );
    await chmod(report, 0o600);
  }
  await project('authorized', sibling, 'second-approved-report');
  await rm(report);
  await symlink(sibling, report);
  await assert.rejects(
    session.resolveAttachment('authorized', current.id),
    (error) => error.status === 403,
  );
  await assert.rejects(session.download('authorized', current.id), (error) => error.status === 403);
  await rm(report);
  await writeFile(report, 'CURRENT_REPORT_TWO');
  await project('authorized', missing, 'missing-report');
  const currentThread = session.state.threads.find((t) => t.id === 'authorized');
  currentThread.messages = currentThread.messages.filter((m) => m.id !== 'missing-report');

  await assert.rejects(
    session.resolveMessageFile('authorized', 'missing-report', missing),
    (error) => error.status === 410 && error.code === 'ENOENT',
  );
  await writeFile(missing, 'LATE_CREATED_REPORT');
  assert.equal(
    (await session.resolveMessageFile('authorized', 'missing-report', missing)).attachment.name,
    'missing.md',
  );
  await rm(missing);
  await project('authorized', outside, 'directory-report');
  await assert.rejects(
    session.resolveMessageFile('authorized', 'directory-report', outside),
    (error) => error.status === 422 && error.code === 'NOT_REGULAR_FILE',
  );
  const huge = join(outside, 'huge.bin');
  await writeFile(huge, Buffer.alloc(20 * 1024 * 1024 + 1));
  await project('authorized', huge, 'huge-report');
  await assert.rejects(
    session.resolveMessageFile('authorized', 'huge-report', huge),
    (error) => error.status === 413 && error.code === 'FILE_TOO_LARGE',
  );
  const threads = session.state.threads;
  session.save();
  session.close();
  session = new RuntimeSession(options);
  assert.equal(
    (await session.download('authorized', current.id)).data.toString(),
    'CURRENT_REPORT_TWO',
    'source proof survives Host restart',
  );
  session.close();
  RuntimeSession.prototype.connect = async function () {
    this.state.threads = threads;
  };
  DesktopTranscription.prototype.readiness = async () => ({
    available: false,
    principal: null,
    reason: 'resource fixture',
  });
  const token = 'private-resource-test-token-never-logged-0123456789';
  Object.assign(process.env, {
    CODEX_RUNTIME_MODE: 'desktop-runtime',
    HOST_AGENT_STATE_DIR: state,
    HOST_AGENT_ROOT: root,
    HOST_AGENT_TOKEN: token,
    HOST_AGENT_PORT: '0',
    HOST_AGENT_DISCOVERY: '0',
  });
  service = await startRuntimeServer();
  const endpoint = `http://127.0.0.1:${service.server.address().port}`;
  const query = new URLSearchParams({
    hostId: service.session.hostId,
    threadId: 'authorized',
    messageId: 'missing-report',
    reference: missing,
  });
  const response = await fetch(endpoint + '/v1/attachments/resolve-link?' + query, {
    headers: { Authorization: 'Bearer ' + token },
  });
  assert.equal(response.status, 410);
  const error = await response.json();
  assert.equal(error.code, 'ENOENT');
  assert.match(error.error, /ENOENT/);
  assert.match(error.requestId, /^[a-f0-9-]{36}$/);
  const log = await readFile(join(state, 'diagnostics/errors.jsonl'), 'utf8');
  assert(!log.includes(token));
  const entry = log
    .trim()
    .split('\n')
    .map((x) => JSON.parse(x))
    .find((x) => x.requestId === error.requestId);
  assert.equal(entry.status, 410);
  assert.equal(entry.code, 'ENOENT');
  assert.equal(entry.threadId, 'authorized');
  assert.equal(entry.route, '/v1/attachments/resolve-link');
  for (const authenticated of [false, true]) {
    const rejected = await new Promise((resolve, reject) => {
      const request = httpRequest(
        endpoint,
        {
          path: 'http://[',
          headers: authenticated ? { Authorization: 'Bearer ' + token } : {},
        },
        (response) => {
          response.resume();
          response.on('end', () => resolve(response.statusCode));
        },
      );
      request.on('error', reject);
      request.setTimeout(5000, () =>
        request.destroy(new Error('Malformed-target response stalled')),
      );
      request.end();
    });
    assert.equal(rejected, authenticated ? 400 : 401);
  }
  const alive = await fetch(endpoint + '/healthz', {
    headers: { Authorization: 'Bearer ' + token },
  });
  assert.equal(alive.status, 200, 'Malformed-target diagnostics must not stop the Host');
  console.log(
    'Message resources: outside-project preview, message/thread isolation, unreferenced/user-link refusal, current versions, symlink refusal, restart, missing-file error and correlated private HTTP log passed. No model execution.',
  );
} finally {
  service?.close();
  session?.close();
  RuntimeSession.prototype.connect = originalConnect;
  DesktopTranscription.prototype.readiness = originalReadiness;
  await rm(base, { recursive: true, force: true });
}
