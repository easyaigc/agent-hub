import assert from 'node:assert/strict';
import { readFile, writeFile, realpath, stat } from 'node:fs/promises';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { RuntimeSession } from '../../packages/codex-adapter/dist/runtime-session.js';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';

// A fresh examiner-owned Host only. This fault does not manufacture an absence proof:
// the production first-input boundary must independently prove its unentered write hook.
const suppliedRoot = process.env.ANDROID_CREATION_RECOVERY_OWNED_ROOT;
assert(suppliedRoot && isAbsolute(suppliedRoot));
const root = await realpath(suppliedRoot);
assert.equal(resolve(suppliedRoot), root);
assert.match(basename(root), /^creation-recovery-live-\d+$/);
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
assert.equal(manifest.nonce, process.env.ANDROID_CREATION_RECOVERY_NONCE);
assert.equal(manifest.cases.length, 1);
assert(manifest.cases[0].text.includes('ANDROID_P4_CREATE_RECOVERY_' + manifest.nonce));
assert.equal(
  JSON.parse(await readFile(join(directory, 'runtime.json'), 'utf8')).hostId,
  manifest.hostId,
);
const validate = RuntimeSession.prototype.validateGeneration;
assert.equal(typeof validate, 'function');
let faulted = false;
const calls = [];
RuntimeSession.prototype.validateGeneration = async function (reply, ...args) {
  assert.equal(resolve(this.options.directory), directory);
  if (reply.text === manifest.cases[0].text) {
    assert.match(reply.clientMessageId ?? '', /^first-[a-f0-9-]{36}$/);
    assert.match(args[0]?.id ?? '', /^[a-f0-9-]{36}$/);
    assert.equal(reply.effort, 'xhigh');
    assert.equal(reply.speed, 'fast');
    assert.equal(reply.permissionMode, 'read-only');
    const first = !faulted;
    faulted = true;
    calls.push({
      kind: first ? 'controlled-preflight-fault' : 'delegate-production-validation',
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
  }
  return validate.call(this, reply, ...args);
};
await startRuntimeServer();
