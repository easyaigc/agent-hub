import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { chmod, mkdir, mkdtemp, readFile, readdir, rm, symlink, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { once } from 'node:events';
import { test } from 'node:test';

async function verifyFixtureExit(fixturePids) {
  const pids = (await readFile(fixturePids, 'utf8').catch(() => ''))
    .trim()
    .split('\n')
    .filter(Boolean)
    .map(Number);
  for (const pid of pids) {
    for (let i = 0; i < 50; i++) {
      try {
        process.kill(pid, 0);
      } catch (e) {
        if (e.code === 'ESRCH') break;
        throw e;
      }
      if (i === 49) throw new Error(`fixture process ${pid} still alive`);
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
  }
}

// Exercises actual OS process loss; the Codex CLI is a fixture, not Electron.
test('file upload resumes after Host process is killed', { timeout: 120_000 }, async () => {
  const base = join(process.cwd(), '.local/upload-resume-20261115/check-remainder');
  await mkdir(base, { recursive: true });
  const root = await mkdtemp(join(base, 'process-'));
  const fixture = join(root, 'codex-fixture.cjs');
  const calls = join(root, 'calls');
  const fixturePids = join(root, 'fixture-pids');
  const token = randomUUID() + randomUUID();
  const hash = (data) => createHash('sha256').update(data).digest('hex');
  const children = [];
  let endpoint;
  try {
    await writeFile(
      fixture,
      `#!${process.execPath}\nconst fs=require('node:fs');fs.appendFileSync(${JSON.stringify(fixturePids)},process.pid+'\\n');require('node:readline').createInterface({input:process.stdin}).on('line',line=>{const m=JSON.parse(line),p=m.params||{};fs.appendFileSync(${JSON.stringify(calls)},(m.method||'response')+'\\n');let result={};if(m.method==='initialize')result={userAgent:'process-test-fixture'};else if(m.method==='thread/start'||m.method==='thread/resume')result={thread:{id:p.threadId||'test-thread',turns:[]}};else if(m.method==='thread/list')result={data:[],nextCursor:null};if(m.id!=null)process.stdout.write(JSON.stringify({id:m.id,result})+'\\n')});`,
    );
    await chmod(fixture, 0o700);
    const env = {
      ...process.env,
      CODEX_RUNTIME_MODE: 'managed-runtime',
      CODEX_EXECUTABLE: fixture,
      HOST_AGENT_BIND: '127.0.0.1',
      HOST_AGENT_PORT: '0',
      HOST_AGENT_STATE_DIR: join(root, 'state'),
      HOST_AGENT_ROOT: root,
      HOST_AGENT_TOKEN: token,
      HOST_AGENT_TRANSFER_DIR: join(root, 'downloads'),
      HOST_AGENT_DISCOVERY: '0',
      HOST_AGENT_DISCOVERY_BONJOUR: '0',
    };
    const start = async () => {
      const child = spawn(
        process.execPath,
        [
          '--input-type=module',
          '-e',
          `import {startRuntimeServer} from './apps/host-agent/dist/runtime-server.js'; const s=await startRuntimeServer(); console.log(JSON.stringify({port:s.server.address().port,target:s.session.readingTarget(),hostId:s.session.hostId}));`,
        ],
        { cwd: process.cwd(), env, stdio: ['ignore', 'pipe', 'pipe'] },
      );
      children.push(child);
      let stdout = '',
        stderr = '';
      child.stderr.on('data', (b) => {
        stderr += b;
      });
      const ready = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('Host startup timeout')), 15000);
        child.once('exit', (code) => {
          clearTimeout(timer);
          reject(new Error(`Host exited before ready: ${code} ${stderr}`));
        });
        child.stdout.on('data', (b) => {
          stdout += b;
          for (const line of stdout.split('\n')) {
            try {
              const v = JSON.parse(line);
              if (v.port && v.target) {
                clearTimeout(timer);
                resolve(v);
                return;
              }
            } catch {
              // Runtime logs may precede its JSON readiness message.
            }
          }
        });
      });
      endpoint = `http://127.0.0.1:${ready.port}`;
      return { child, ...ready };
    };
    const first = await start();
    const { hostId, target } = first;
    const query = (extra = {}, t = target) =>
      '?' + new URLSearchParams({ hostId, target: JSON.stringify(t), ...extra });
    const api = async (path, { body, status = 200, headers = {} } = {}) => {
      const response = await fetch(endpoint + path, {
        method: body === undefined ? 'GET' : 'POST',
        headers: {
          Authorization: 'Bearer ' + token,
          ...headers,
          ...(body === undefined
            ? {}
            : {
                'Content-Type': Buffer.isBuffer(body)
                  ? 'application/octet-stream'
                  : 'application/json',
              }),
        },
        body: body === undefined ? undefined : Buffer.isBuffer(body) ? body : JSON.stringify(body),
      });
      const data = await response.json();
      assert.equal(response.status, status, `${path}: ${JSON.stringify(data)}`);
      return data;
    };
    const workspace = await api('/v1/workspace');
    const thread = await api('/v1/threads', {
      body: { hostId, projectId: workspace.projects[0].id, clientOperationId: randomUUID() },
    });
    await api('/v1/history?' + new URLSearchParams({ hostId, threadId: thread.id }));
    const chunk = Buffer.alloc(256 * 1024, 0x5a);
    const sizeBytes = 16 * 1024 * 1024;
    const digest = createHash('sha256');
    for (let i = 0; i < sizeBytes / chunk.length; i++) digest.update(chunk);
    const manifest = {
      version: 1,
      purpose: 'file-transfer',
      hostId,
      id: randomUUID(),
      name: 'process-16m.bin',
      mediaType: 'application/octet-stream',
      sizeBytes,
      sha256: digest.digest('hex'),
      threadId: null,
      creationId: null,
      attachmentId: null,
    };
    const q = (extra = {}) => query({ id: manifest.id, ...extra });
    const status = () => api('/v1/uploads' + q());
    const send = (offset) =>
      api('/v1/uploads/chunk' + q({ offset: String(offset) }), {
        body: chunk,
        headers: { 'x-content-sha256': hash(chunk) },
      });
    await api('/v1/uploads' + query({}, { ...target, runtimeId: 'wrong' }), {
      body: manifest,
      status: 409,
    });
    await api('/v1/uploads' + query(), { body: manifest });
    const midpoint = sizeBytes / 2;
    for (let offset = 0; offset < midpoint; offset += chunk.length) await send(offset);
    assert.equal((await status()).committedBytes, midpoint);
    const exited = once(first.child, 'exit');
    assert(first.child.kill('SIGKILL'));
    const [, signal] = await exited;
    assert.equal(signal, 'SIGKILL');
    const second = await start();
    assert.notEqual(second.child.pid, first.child.pid);
    assert.deepEqual(second.target, target);
    assert.equal(second.hostId, hostId);
    assert.equal((await status()).committedBytes, midpoint);
    await api('/v1/uploads' + query({ id: manifest.id }, { ...target, runtimeId: 'wrong' }), {
      status: 409,
    });
    for (let offset = midpoint; offset < sizeBytes; offset += chunk.length) await send(offset);
    await api('/v1/uploads/complete' + q(), { body: Buffer.alloc(0) });
    const complete = await status();
    assert.equal(complete.phase, 'completed');
    const files = (await readdir(join(root, 'downloads'))).filter((x) => x !== '.incoming');
    assert.equal(files.length, 1);
    assert.equal(join(root, 'downloads', files[0]), complete.result.destinationPath);
    const output = createHash('sha256');
    for await (const part of createReadStream(complete.result.destinationPath)) output.update(part);
    assert.equal(output.digest('hex'), manifest.sha256);
    const callText = await readFile(calls, 'utf8').catch((e) => {
      if (e.code === 'ENOENT') return '';
      throw e;
    });
    assert(callText.includes('initialize'), 'fixture CLI must actually run');
    assert(!callText.split('\n').includes('turn/start'));
    console.log(
      JSON.stringify({
        bytes: sizeBytes,
        midpoint,
        firstPid: first.child.pid,
        secondPid: second.child.pid,
        firstPort: first.port,
        secondPort: second.port,
        sha256: manifest.sha256,
        files: files.length,
        turnStart: 0,
        runtime: 'Host OS process; Codex CLI fixture; not Electron',
      }),
    );
  } finally {
    for (const child of children) {
      if (child.exitCode === null && child.signalCode === null) {
        const exited = once(child, 'exit');
        child.kill('SIGTERM');
        await exited;
      }
    }
    await verifyFixtureExit(fixturePids);
    await rm(root, { recursive: true, force: true });
  }
});

test('publication rejects symlink targets and conflicting existing files', async () => {
  const base = join(process.cwd(), '.local/upload-resume-20261115/check-remainder');
  await mkdir(base, { recursive: true });
  const root = await mkdtemp(join(base, 'publication-'));
  try {
    const { publishUploadedFile } =
      await import('../packages/codex-adapter/dist/upload-publication.js');
    const data = Buffer.from('publication-boundary');
    const digest = createHash('sha256').update(data).digest('hex');
    const source = join(root, 'source');
    const destination = join(root, 'destination');
    await writeFile(source, data);
    await symlink(source, destination);
    await assert.rejects(
      publishUploadedFile(source, destination, data.length, digest),
      /upload-publication-conflict/,
    );
    await rm(destination);
    await writeFile(destination, data);
    await publishUploadedFile(source, destination, data.length, digest);
    await writeFile(destination, Buffer.alloc(data.length, 0x78));
    await assert.rejects(
      publishUploadedFile(source, destination, data.length, digest),
      /upload-publication-conflict/,
    );
    await mkdir(join(root, 'real-dir'));
    await symlink(join(root, 'real-dir'), join(root, 'linked-dir'));
    await assert.rejects(
      publishUploadedFile(source, join(root, 'linked-dir', 'file'), data.length, digest),
      /invalid-upload-directory/,
    );
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test('receiving deadline clears socket timeout when the body ends', async () => {
  const { EventEmitter } = await import('node:events');
  const { receivingDeadline } = await import('../apps/host-agent/dist/upload-timeouts.js');
  for (const event of ['end', 'close']) {
    const request = new EventEmitter();
    request.url = '/v1/uploads/chunk?id=fixture';
    request.complete = false;
    const timeouts = [];
    request.setTimeout = (ms, handler) => {
      timeouts.push(ms);
      if (handler) request.on('timeout', handler);
      return request;
    };
    const response = { destroyed: false, headersSent: false };
    receivingDeadline(request, response);
    assert.deepEqual(timeouts, [30_000]);
    assert.equal(request.listenerCount('timeout'), 1);
    request.complete = true;
    request.emit(event);
    assert.deepEqual(timeouts, [30_000, 0]);
    assert.equal(request.listenerCount('timeout'), 0);
  }
});

test(
  'large attachment and creation publications remain idempotent after journal restart',
  { timeout: 60_000 },
  async () => {
    const { RuntimeSession } = await import('../packages/codex-adapter/dist/runtime-session.js');
    const { DesktopCreationBroker, creationAttachmentPath } =
      await import('../packages/codex-adapter/dist/desktop-creation.js');
    const base = join(process.cwd(), '.local/upload-resume-20261115/check-remainder');
    await mkdir(base, { recursive: true });
    const root = await mkdtemp(join(base, 'branches-'));
    const options = {
      mode: 'managed-runtime',
      directory: join(root, 'runtime'),
      root,
      name: 'publication fixture',
    };
    const creationDir = join(root, 'creation');
    let session;
    let driverCalls = 0;
    const driver = {
      available: true,
      create: async () => {
        driverCalls++;
        throw new Error('unexpected model submission');
      },
    };
    const setup = () => {
      session = new RuntimeSession(options);
      session.runtime.ready = true; // Isolated fixture exercises production publication, not a live connection.
      session.desktopCreation = new DesktopCreationBroker(
        creationDir,
        session.hostId,
        driver,
        async () => true,
      );
    };
    try {
      const bytes = Buffer.alloc(16 * 1024 * 1024, 0x37);
      const sha256 = createHash('sha256').update(bytes).digest('hex');
      const source = join(root, 'source.bin');
      await writeFile(source, bytes);
      setup();
      const hostId = session.hostId;
      const threadId = 'thread-fixture';
      session.state.threads.push({
        id: threadId,
        hostId,
        projectId: null,
        title: 'fixture',
        state: 'unknown',
        turnId: null,
        plan: [],
        messages: [],
        error: null,
      });
      const attachment = {
        version: 1,
        purpose: 'attachment',
        hostId,
        id: randomUUID(),
        name: 'attachment.bin',
        mediaType: 'application/octet-stream',
        sizeBytes: bytes.length,
        sha256,
        threadId,
        creationId: null,
        attachmentId: null,
      };
      const file = {
        id: randomUUID(),
        name: 'creation.bin',
        mediaType: attachment.mediaType,
        sizeBytes: bytes.length,
        sha256,
      };
      const creationId = randomUUID();
      const input = {
        hostId,
        projectId: 'project-fixture',
        clientOperationId: creationId,
        text: 'fixture',
        attachments: [file],
      };
      const target = { hostId, projectId: input.projectId, directory: root };
      session.desktopCreation.prepare(input, target);
      const creation = {
        ...attachment,
        id: randomUUID(),
        purpose: 'creation-attachment',
        name: file.name,
        threadId: null,
        creationId,
        attachmentId: file.id,
      };
      const firstAttachment = await session.completeResumableUpload(attachment, source);
      const firstCreation = await session.completeResumableUpload(creation, source);
      assert.deepEqual(await session.completeResumableUpload(attachment, source), firstAttachment);
      assert.deepEqual(await session.completeResumableUpload(creation, source), firstCreation);
      assert.equal(session.state.attachments.filter((item) => item.id === attachment.id).length, 1);
      session.close();
      setup();
      assert.equal(session.hostId, hostId);
      assert.deepEqual(await session.completeResumableUpload(attachment, source), firstAttachment);
      assert.deepEqual(await session.completeResumableUpload(creation, source), firstCreation);
      assert.equal(session.state.attachments.filter((item) => item.id === attachment.id).length, 1);
      const record = session.desktopCreation.prepare(input, target);
      assert.deepEqual(record.uploaded, [file.id]);
      const paths = [
        join(
          options.directory,
          process.platform === 'win32' ? 'attachments-v2' : 'attachments',
          attachment.id + '.bin',
        ),
        creationAttachmentPath(creationDir, record, file),
      ];
      for (const path of paths) {
        const digest = createHash('sha256');
        for await (const part of createReadStream(path)) digest.update(part);
        assert.equal(digest.digest('hex'), sha256);
      }
      assert.deepEqual(
        await readdir(
          join(options.directory, process.platform === 'win32' ? 'attachments-v2' : 'attachments'),
        ),
        [attachment.id + '.bin'],
      );
      assert.deepEqual(await readdir(join(creationDir, creationId, file.id)), [file.name]);
      await assert.rejects(
        session.completeResumableUpload({ ...attachment, hostId: 'wrong' }, source),
      );
      await assert.rejects(
        session.completeResumableUpload({ ...creation, sha256: '0'.repeat(64) }, source),
      );
      assert.equal(driverCalls, 0);
      console.log(
        JSON.stringify({
          branches: ['attachment', 'creation-attachment'],
          bytes: bytes.length,
          sha256,
          repeatComplete: true,
          journalRestart: true,
          driverCalls,
          scope: 'production methods with fixture readiness and driver; no Electron',
        }),
      );
    } finally {
      session?.close();
      await rm(root, { recursive: true, force: true });
    }
  },
);
