import assert from 'node:assert/strict';
import { readFile, writeFile, realpath, stat } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { RuntimeSession } from '../../packages/codex-adapter/dist/runtime-session.js';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';

// A fresh examiner-owned Host only. This fault does not manufacture an absence proof:
// the production first-input boundary must independently prove its unentered write hook.
const suppliedRoot = process.env.ANDROID_CREATION_RESTART_OWNED_ROOT;
assert(suppliedRoot && isAbsolute(suppliedRoot));
const root = await realpath(suppliedRoot);
assert.equal(resolve(suppliedRoot), root);
assert.match(basename(root), /^creation-restart-live-\d+$/);
assert.equal((await stat(root)).mode & 0o077, 0);
const directory = await realpath(process.env.HOST_AGENT_STATE_DIR ?? '');
assert.equal(directory, join(root, 'host'));
assert.equal(dirname(directory), root);
assert.equal(process.env.CODEX_RUNTIME_MODE, 'desktop-runtime');
assert.equal(process.env.HOST_AGENT_BIND, '127.0.0.1');
const casesFile = join(root, 'immutable-cases.json');
assert.equal(await realpath(casesFile), casesFile);
const manifest = JSON.parse(await readFile(casesFile, 'utf8'));
assert.match(manifest.nonce, /^[a-f0-9]{8}$/);
assert.equal(manifest.nonce, process.env.ANDROID_CREATION_RESTART_NONCE);
assert.equal(manifest.cases.length, 1);
assert(manifest.cases[0].text.includes('ANDROID_P4_CREATION_RESTART_' + manifest.nonce));
assert.equal(
  JSON.parse(await readFile(join(directory, 'runtime.json'), 'utf8')).hostId,
  manifest.hostId,
);
const validate = RuntimeSession.prototype.validateGeneration;
assert.equal(typeof validate, 'function');
const startIndex = Number(process.env.ANDROID_CREATION_RESTART_HOST_START_INDEX);
assert(startIndex === 0 || startIndex === 1);
const preflightOnly = process.env.ANDROID_CREATION_RESTART_PREFLIGHT_ONLY === '1';
let faulted = false;
let calls = [];
let originWriterPid = process.pid;
let originalClientId, originalThreadId;
if (startIndex === 1 && !preflightOnly) {
  const prior = JSON.parse(
    await readFile(join(directory, 'controlled-generation-fault.json'), 'utf8'),
  );
  assert.equal(prior.hostId, manifest.hostId);
  assert.equal(prior.nonce, manifest.nonce);
  assert.equal(prior.calls.length, 1);
  assert.equal(prior.calls[0].kind, 'controlled-preflight-fault');
  assert.equal(prior.calls[0].writerPid, prior.pid);
  originalClientId = prior.calls[0].clientMessageId;
  originalThreadId = prior.calls[0].threadId;
  assert.match(originalClientId, /^first-[a-f0-9-]{36}$/);
  const prep = JSON.parse(
    await readFile(
      join(directory, 'desktop-prepared-bridge', originalClientId.slice(6) + '.json'),
      'utf8',
    ),
  );
  assert.equal(prep.hostId, manifest.hostId);
  assert.equal(prep.clientOperationId, originalClientId.slice(6));
  assert.equal(prep.threadId, originalThreadId);
  assert.equal(prep.phase, 'input-retryable');
  assert.equal(prep.exitCode, 0);
  assert.equal(prep.inputBoundary?.state, 'not-written');
  assert.equal(prep.inputBoundary.checkpoint.writerPid, prior.pid);
  calls = prior.calls;
  originWriterPid = prior.pid;
  faulted = true;
  await writeFile(
    join(directory, 'controlled-generation-fault.json'),
    JSON.stringify(
      { ...prior, pid: process.pid, originWriterPid, resumedWithoutValidation: true },
      null,
      2,
    ) + '\n',
    { mode: 0o600 },
  );
}
RuntimeSession.prototype.validateGeneration = async function (reply, ...args) {
  assert.equal(resolve(this.options.directory), directory);
  if (reply.text === manifest.cases[0].text) {
    assert.match(reply.clientMessageId ?? '', /^first-[a-f0-9-]{36}$/);
    assert.match(args[0]?.id ?? '', /^[a-f0-9-]{36}$/);
    assert.equal(reply.effort, 'xhigh');
    assert.equal(reply.speed, 'fast');
    assert.equal(reply.permissionMode, 'read-only');
    assert(!preflightOnly, 'Zero-input preflight may never validate a model input');
    originalClientId ??= reply.clientMessageId;
    originalThreadId ??= args[0].id;
    assert.equal(reply.clientMessageId, originalClientId);
    assert.equal(args[0].id, originalThreadId);
    const first = !faulted;
    faulted = true;
    let explicitClaimVerified = false;
    if (!first) {
      try {
        const claim = JSON.parse(
          await readFile(join(directory, 'examiner-explicit-original-claim.json'), 'utf8'),
        );
        assert.equal(claim.nonce, manifest.nonce);
        assert.equal(claim.hostId, manifest.hostId);
        assert.equal(claim.clientMessageId, originalClientId);
        assert.equal(claim.threadId, originalThreadId);
        assert.equal(claim.textSha256, createHash('sha256').update(reply.text).digest('hex'));
        explicitClaimVerified = true;
      } catch {
        /* Record the attempted early validation and fail closed below. */
      }
    }
    calls.push({
      writerPid: process.pid,
      kind: first
        ? 'controlled-preflight-fault'
        : explicitClaimVerified
          ? 'delegate-production-validation'
          : 'unexpected-validation-before-explicit-native-claim',
      clientMessageId: reply.clientMessageId,
      threadId: args[0].id,
      textSha256: createHash('sha256').update(reply.text).digest('hex'),
      at: new Date().toISOString(),
    });
    await writeFile(
      join(directory, 'controlled-generation-fault.json'),
      JSON.stringify(
        {
          pid: process.pid,
          originWriterPid,
          nonce: manifest.nonce,
          hostId: manifest.hostId,
          calls,
          scope:
            'Only this owned preflight; real production boundary/receipt and SDK must prove not-written.',
        },
        null,
        2,
      ) + '\n',
      { mode: 0o600 },
    );
    if (first) throw Error('Controlled owned generation catalog preflight fault');
    assert(
      explicitClaimVerified,
      'Unexpected original validation before explicit native continuation claim',
    );
  }
  return validate.call(this, reply, ...args);
};
await startRuntimeServer();
