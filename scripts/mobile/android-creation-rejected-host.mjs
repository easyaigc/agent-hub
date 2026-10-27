import assert from 'node:assert/strict';
import { readFile, writeFile, realpath, stat } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { DesktopPreparedBridge } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { BeforeDesktopSubmit } from '../../packages/codex-adapter/dist/desktop-creation.js';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';

// Examiner-owned child only. The typed fault is before original create delegation;
// the production broker must produce the rejection. This is not a natural OS refusal.
const supplied = process.env.ANDROID_CREATION_REJECTED_OWNED_ROOT;
assert(supplied && isAbsolute(supplied));
const root = await realpath(supplied);
assert.equal(resolve(supplied), root);
assert.match(basename(root), /^creation-rejected-live-\d+$/);
assert.equal((await stat(root)).mode & 0o077, 0);
const state = await realpath(process.env.HOST_AGENT_STATE_DIR ?? '');
assert.equal(state, join(root, 'host'));
assert.equal(dirname(state), root);
assert.equal(process.env.CODEX_RUNTIME_MODE, 'desktop-runtime');
assert.equal(process.env.HOST_AGENT_BIND, '127.0.0.1');
const manifest = JSON.parse(await readFile(join(root, 'immutable-cases.json'), 'utf8'));
assert.match(manifest.nonce, /^[a-f0-9]{8}$/);
assert.equal(manifest.nonce, process.env.ANDROID_CREATION_REJECTED_NONCE);
assert.equal(manifest.cases.length, 2);
assert.equal(
  JSON.parse(await readFile(join(state, 'runtime.json'), 'utf8')).hostId,
  manifest.hostId,
);
const original = DesktopPreparedBridge.prototype.create;
assert.equal(typeof original, 'function');
const calls = [];
const sha = (value) => createHash('sha256').update(value).digest('hex');
DesktopPreparedBridge.prototype.create = async function (plan) {
  assert.equal(resolve(this.directory), join(state, 'desktop-prepared-bridge'));
  assert.equal(this.hostId, manifest.hostId);
  assert.equal(plan.hostId, manifest.hostId);
  assert.equal(plan.projectId, manifest.projectId);
  assert.equal(await realpath(plan.directory), manifest.projectRoot);
  assert.match(plan.clientOperationId, /^[a-f0-9-]{36}$/);
  assert.deepEqual(plan.attachments, []);
  assert.deepEqual(plan.files, []);
  assert.equal(typeof plan.model, 'string');
  assert.equal(plan.effort, 'xhigh');
  assert.equal(plan.speed, 'fast');
  assert.equal(plan.permissionMode, 'read-only');
  assert.equal(plan.collaborationMode, 'default');
  const index = manifest.cases.findIndex((c) => c.text === plan.text);
  assert([0, 1].includes(index));
  assert(plan.text.includes('ANDROID_P4_REJECT_RETURN_' + manifest.nonce));
  assert(
    !calls.some((c) => c.index === index || c.operationId === plan.clientOperationId),
    'No repeated original driver invocation',
  );
  calls.push({
    index,
    operationId: plan.clientOperationId,
    kind:
      index === 0 ? 'controlled-BeforeDesktopSubmit-before-delegation' : 'delegate-original-create',
    textSha256: sha(plan.text),
  });
  await writeFile(
    join(state, 'controlled-rejection-trace.json'),
    JSON.stringify(
      {
        pid: process.pid,
        hostId: manifest.hostId,
        nonce: manifest.nonce,
        calls,
        scope:
          'Exact examiner-owned typed refusal before original bridge delegation; never a manufactured success or universal absence proof.',
      },
      null,
      2,
    ) + '\n',
    { mode: 0o600 },
  );
  if (index === 0)
    throw new BeforeDesktopSubmit(
      'Controlled owned rejection before original bridge create delegation',
    );
  return original.call(this, plan);
};
await startRuntimeServer();
