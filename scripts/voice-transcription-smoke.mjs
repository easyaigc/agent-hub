import './windows-hidden-spawn-guard.mjs';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm, realpath, symlink } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID, createHash } from 'node:crypto';
import { mock, test } from 'node:test';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import { VoiceTranscriptions } from '../apps/host-agent/dist/voice-transcriptions.js';
import { DesktopTranscription } from '../packages/codex-adapter/dist/desktop-transcription.js';

const wave = Buffer.alloc(44 + 32000);
wave.write('RIFF');
wave.writeUInt32LE(wave.length - 8, 4);
wave.write('WAVEfmt ', 8);
wave.writeUInt32LE(16, 16);
wave.writeUInt16LE(1, 20);
wave.writeUInt16LE(1, 22);
wave.writeUInt32LE(16000, 24);
wave.writeUInt32LE(32000, 28);
wave.writeUInt16LE(2, 32);
wave.writeUInt16LE(16, 34);
wave.write('data', 36);
wave.writeUInt32LE(32000, 40);
const sha = (audio) => createHash('sha256').update(audio).digest('hex');
const principal = '1'.repeat(64);
const manifest = () => ({
  hostId: 'voice-test-host',
  threadId: null,
  draftId: 'scoped-draft',
  operationId: randomUUID(),
  mediaType: 'audio/wav',
  sizeBytes: wave.length,
  sha256: sha(wave),
  durationMs: 1000,
});
const expectStatus = (promise, status) =>
  assert.rejects(promise, (error) => error.status === status);

// Observe the exact background task started by upload, including durable save
// and cleanup. Never invoke run a second time or replace its returned Promise.
function observeRuns(broker) {
  const original = broker.run;
  const tasks = [];
  const observer = mock.method(broker, 'run', function (...args) {
    const task = Reflect.apply(original, this, args);
    tasks.push(task);
    return task;
  });
  return { tasks, restore: () => observer.mock.restore() };
}

test('official auth reader uses fixed read-only methods and respects routing and account changes', async () => {
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'voice-auth-')));
  const executable = join(directory, 'metadata-cli');
  const claims = {
    sub: 'user-one',
    exp: Math.floor(Date.now() / 1000) + 3600,
    'https://api.openai.com/auth': { chatgpt_account_id: 'account-one' },
  };
  const config = {
    token: 'header.' + Buffer.from(JSON.stringify(claims)).toString('base64url') + '.signature',
    origin: 'https://chatgpt.com',
    accountId: 'account-one',
    base: 'https://chatgpt.com/backend-api/',
    requirements: null,
  };
  await writeFile(join(directory, 'fixture.json'), JSON.stringify(config));
  await writeFile(
    executable,
    `#!${process.execPath}
import { readFileSync, appendFileSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { join } from 'node:path';
const c = JSON.parse(readFileSync(join(process.env.CODEX_HOME, 'fixture.json')));
createInterface({input:process.stdin}).on('line', line => {
  const q=JSON.parse(line); if(q.method==='initialized') return;
  appendFileSync(join(process.env.CODEX_HOME,'calls.jsonl'),JSON.stringify({method:q.method,params:q.params})+'\\n');
  const results={initialize:{},getAuthStatus:{authMethod:'chatgpt',authToken:c.token},'account/read':{account:{type:'chatgpt'},workspaceRouting:{backendOrigin:c.origin,accountRoutingOverride:'NO_CONSTRAINT',chatgptAccountId:c.accountId}},'config/read':{config:{chatgpt_base_url:c.base}},'configRequirements/read':{requirements:c.requirements}};
  if(!Object.hasOwn(results,q.method) || q.params.refreshToken===true) process.exit(2);
  process.stdout.write(JSON.stringify({id:q.id,result:results[q.method]})+'\\n');
});
`,
    { mode: 0o700 },
  );
  const originalSpawn = childProcess.spawn;
  childProcess.spawn = (command, args, options) =>
    command === executable
      ? originalSpawn(process.execPath, [executable, ...args], options)
      : originalSpawn(command, args, options);
  syncBuiltinESMExports();
  const adapter = new DesktopTranscription(directory, executable);
  try {
    const first = await adapter.readiness();
    assert.equal(first.available, true);
    assert.equal(first.principal, sha(Buffer.from(JSON.stringify(['account-one', 'user-one']))));
    claims.sub = 'user-two';
    config.token =
      'header.' + Buffer.from(JSON.stringify(claims)).toString('base64url') + '.signature';
    await writeFile(join(directory, 'fixture.json'), JSON.stringify(config));
    const second = await adapter.readiness();
    assert.notEqual(first.principal, second.principal);
    config.origin = 'https://workspace.example.invalid';
    await writeFile(join(directory, 'fixture.json'), JSON.stringify(config));
    assert.equal((await adapter.readiness()).available, false);
    config.origin = 'https://chatgpt.com';
    config.requirements = { network: { enabled: true } };
    await writeFile(join(directory, 'fixture.json'), JSON.stringify(config));
    assert.equal((await adapter.readiness()).available, false);
    const calls = (await readFile(join(directory, 'calls.jsonl'), 'utf8'))
      .trim()
      .split('\n')
      .map(JSON.parse);
    assert.deepEqual(
      [...new Set(calls.map((call) => call.method))],
      ['initialize', 'getAuthStatus', 'account/read', 'config/read', 'configRequirements/read'],
    );
    assert.equal(
      calls
        .filter((call) => ['getAuthStatus', 'account/read'].includes(call.method))
        .every((call) => call.params.refreshToken === false),
      true,
    );
  } finally {
    adapter.close();
    childProcess.spawn = originalSpawn;
    syncBuiltinESMExports();
    await rm(directory, { recursive: true, force: true });
  }
});

test('voice capability metadata is nonblocking, bounded and cannot revive after close', async () => {
  let clock = 0;
  const timing = mock.method(performance, 'now', () => clock);
  const adapter = new DesktopTranscription();
  let finish,
    reads = 0;
  adapter.readiness = () => {
    reads++;
    return new Promise((done) => {
      finish = done;
    });
  };
  const settle = async (available) => {
    finish({ available, reason: null, principal: 'not-stored-in-capability-hint' });
    await new Promise((done) => setImmediate(done));
  };
  try {
    for (let i = 0; i < 100; i++) assert.equal(adapter.capabilityAvailable, false);
    assert.equal(reads, 1, 'Concurrent metadata projections share one background probe');
    await settle(true);
    assert.equal(adapter.capabilityAvailable, true);
    assert.deepEqual(Object.keys(adapter.capabilityHint).sort(), ['available', 'checkedAt']);
    clock = 10_001;
    assert.equal(adapter.capabilityAvailable, true);
    assert.equal(reads, 2);
    clock = 30_001;
    assert.equal(adapter.capabilityAvailable, false, 'An overdue probe cannot retain availability');
    assert.equal(reads, 2);
    await settle(false);
    assert.equal(adapter.capabilityAvailable, false);
    clock += 10_001;
    assert.equal(adapter.capabilityAvailable, false);
    assert.equal(reads, 3);
    adapter.close();
    await settle(true);
    assert.equal(adapter.capabilityAvailable, false);
    assert.equal(reads, 3, 'Closed capability reads never start another auth helper');
  } finally {
    adapter.close();
    timing.mock.restore();
  }
});

test('raw audio identity, durable receipts, account isolation and no replay', async () => {
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'voice-transcription-')));
  let calls = 0,
    currentPrincipal = principal,
    finish;
  const transcriber = {
    readiness: async () => ({ available: true, reason: null, principal: currentPrincipal }),
    transcribe: async (audio, account) => {
      calls++;
      assert.equal(sha(audio), sha(wave));
      assert.equal(account, principal);
      return new Promise((resolve) => {
        finish = resolve;
      });
    },
  };
  const broker = new VoiceTranscriptions('voice-test-host', directory, transcriber);
  const runs = observeRuns(broker);
  try {
    const input = manifest();
    assert.equal((await broker.prepare(input)).phase, 'awaiting-audio');
    await expectStatus(broker.prepare({ ...input, draftId: 'different-draft' }), 409);
    await expectStatus(broker.prepare({ ...input, endpoint: 'https://example.invalid' }), 400);
    const changed = Buffer.from(wave);
    changed[100] = 1;
    await expectStatus(broker.upload(input.operationId, changed), 409);
    const submitted = await broker.upload(input.operationId, wave);
    assert.equal(submitted.phase, 'transcribing');
    assert.equal('principal' in submitted, false);
    await broker.upload(input.operationId, wave);
    assert.equal(calls, 1);
    finish('原音频的真实转写');
    assert.equal(runs.tasks.length, 1);
    await runs.tasks[0];
    const receipt = await broker.status(input.operationId);
    assert.equal(receipt.phase, 'completed');
    assert.equal(receipt.text, '原音频的真实转写');
    const restarted = new VoiceTranscriptions('voice-test-host', directory, transcriber);
    assert.equal((await restarted.status(input.operationId)).text, receipt.text);
    await restarted.upload(input.operationId, wave);
    assert.equal(calls, 1);
    currentPrincipal = '2'.repeat(64);
    await expectStatus(restarted.status(input.operationId), 403);
    await expectStatus(restarted.prepare(input), 403);
    await expectStatus(restarted.upload(input.operationId, wave), 403);
    currentPrincipal = principal;
    const path = join(directory, 'voice-transcriptions', input.operationId + '.json');
    const expired = JSON.parse(await readFile(path, 'utf8'));
    expired.expiresAt = new Date(0).toISOString();
    await writeFile(path, JSON.stringify(expired), { mode: 0o600 });
    await restarted.prune();
    await expectStatus(restarted.prepare(input), 410);
    await expectStatus(restarted.upload(input.operationId, wave), 410);
    assert.equal(JSON.parse(await readFile(path, 'utf8')).text, null);
    assert.equal(calls, 1);
  } finally {
    finish?.('原音频的真实转写');
    await Promise.allSettled(runs.tasks);
    runs.restore();
    await rm(directory, { recursive: true, force: true });
  }
});

test('unknown network outcome and Host restart never resubmit; invalid WAV never reaches upstream', async () => {
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'voice-unknown-')));
  let calls = 0;
  const transcriber = {
    readiness: async () => ({ available: true, reason: null, principal }),
    transcribe: async () => {
      calls++;
      throw Error('Connection lost after upload');
    },
  };
  const broker = new VoiceTranscriptions('voice-test-host', directory, transcriber);
  const runs = observeRuns(broker);
  try {
    const bad = Buffer.from(wave);
    bad.write('xxxx', 8);
    const invalid = { ...manifest(), sha256: sha(bad) };
    await broker.prepare(invalid);
    await expectStatus(broker.upload(invalid.operationId, bad), 400);
    assert.equal(calls, 0);
    const input = manifest();
    await broker.prepare(input);
    await broker.upload(input.operationId, wave);
    assert.equal(runs.tasks.length, 1);
    await runs.tasks[0];
    const receipt = await broker.status(input.operationId);
    assert.equal(receipt.phase, 'outcome-unknown');
    const restarted = new VoiceTranscriptions('voice-test-host', directory, transcriber);
    await restarted.prepare(input);
    await restarted.upload(input.operationId, wave);
    assert.equal(calls, 1);
    const path = join(directory, 'voice-transcriptions', input.operationId + '.json');
    const stored = JSON.parse(await readFile(path, 'utf8'));
    stored.phase = 'transcribing';
    await writeFile(path, JSON.stringify(stored), { mode: 0o600 });
    assert.equal((await restarted.status(input.operationId)).phase, 'outcome-unknown');
    assert.equal(calls, 1);
    const linked = manifest();
    await restarted.prepare(linked);
    const external = join(directory, 'other-private-file');
    await writeFile(external, wave, { mode: 0o600 });
    await symlink(external, join(directory, 'voice-transcriptions', linked.operationId + '.wav'));
    await expectStatus(restarted.upload(linked.operationId, wave), 409);
    assert.equal(calls, 1);
    assert.equal(sha(await readFile(external)), sha(wave));
  } finally {
    await Promise.allSettled(runs.tasks);
    runs.restore();
    await rm(directory, { recursive: true, force: true });
  }
});

test('a delayed transcribing status read cannot overwrite a durable completed receipt', async () => {
  const directory = await realpath(await mkdtemp(join(tmpdir(), 'voice-stale-status-')));
  const upstream = Promise.withResolvers();
  const snapshotRead = Promise.withResolvers();
  const releaseRead = Promise.withResolvers();
  const text = 'Synthetic completed transcription';
  let calls = 0;
  const transcriber = {
    readiness: async () => ({ available: true, reason: null, principal }),
    transcribe: async (audio, account) => {
      calls++;
      assert.equal(sha(audio), sha(wave));
      assert.equal(account, principal);
      return upstream.promise;
    },
  };
  const broker = new VoiceTranscriptions('voice-test-host', directory, transcriber);
  const runs = observeRuns(broker);
  const input = manifest();
  let readObserver, pendingStatus;
  try {
    await broker.prepare(input);
    assert.equal((await broker.upload(input.operationId, wave)).phase, 'transcribing');
    assert.equal(runs.tasks.length, 1);
    const originalRead = broker.read;
    let held = false;
    readObserver = mock.method(broker, 'read', async function (id) {
      const snapshot = await Reflect.apply(originalRead, this, [id]);
      if (id === input.operationId && !held) {
        held = true;
        snapshotRead.resolve(snapshot);
        await releaseRead.promise;
      }
      return snapshot;
    });
    pendingStatus = broker.status(input.operationId);
    pendingStatus.catch(snapshotRead.reject);
    const stale = await snapshotRead.promise;
    assert.equal(stale.phase, 'transcribing');
    assert.equal(stale.text, null);

    upstream.resolve(text);
    await runs.tasks[0];
    assert.equal(broker.active.has(input.operationId), false);
    const path = join(directory, 'voice-transcriptions', input.operationId + '.json');
    const before = JSON.parse(await readFile(path, 'utf8'));
    assert.equal(before.phase, 'completed');
    assert.equal(before.text, text);

    // Deliver the already-read old snapshot only after save and active.delete.
    releaseRead.resolve();
    const receipt = await pendingStatus;
    const after = JSON.parse(await readFile(path, 'utf8'));
    assert.deepEqual(
      {
        returnedPhase: receipt.phase,
        returnedTextMatches: receipt.text === text,
        storedPhase: after.phase,
        storedTextMatches: after.text === text,
      },
      {
        returnedPhase: 'completed',
        returnedTextMatches: true,
        storedPhase: 'completed',
        storedTextMatches: true,
      },
      'A stale status read must preserve the already durable completion',
    );
    const restarted = new VoiceTranscriptions('voice-test-host', directory, transcriber);
    assert.equal((await restarted.status(input.operationId)).text, text);
    assert.equal((await restarted.upload(input.operationId, wave)).phase, 'completed');
    assert.equal(calls, 1);
  } finally {
    releaseRead.resolve();
    upstream.resolve(text);
    await Promise.allSettled([...runs.tasks, ...(pendingStatus ? [pendingStatus] : [])]);
    readObserver?.mock.restore();
    runs.restore();
    await rm(directory, { recursive: true, force: true });
  }
});
