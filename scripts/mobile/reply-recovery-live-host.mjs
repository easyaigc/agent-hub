import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
import { resolve, sep } from 'node:path';
import { RuntimeSession } from '../../packages/codex-adapter/dist/runtime-session.js';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';

// This entry point is exclusively for a fresh, examiner-owned DEV process.
const directory = resolve(process.env.HOST_AGENT_STATE_DIR ?? '');
assert(directory.startsWith(resolve('.local/android-development/upstream-migration/UM02') + sep));
const nonce = process.env.UM02_ACCEPTANCE_NONCE;
assert.match(nonce ?? '', /^[a-f0-9]{8}$/);
assert.equal(process.env.CODEX_RUNTIME_MODE, 'desktop-runtime');
assert.equal(process.env.HOST_AGENT_BIND, '127.0.0.1');
if (process.env.UM02_ACCEPTANCE_CHECKPOINT_BASE) {
  const base = JSON.parse(await readFile(process.env.UM02_ACCEPTANCE_CHECKPOINT_BASE, 'utf8'));
  await writeFile(
    process.env.HOST_AGENT_REPLY_CHECKPOINT_FILE,
    JSON.stringify({ ...base, newPid: process.pid }),
    { mode: 0o600, flag: 'wx' },
  );
}
if (process.env.UM02_ACCEPTANCE_FAIL_PREFLIGHT === '1') {
  const validate = RuntimeSession.prototype.validateGeneration;
  assert.equal(typeof validate, 'function');
  let remaining = 1;
  RuntimeSession.prototype.validateGeneration = async function (reply, ...args) {
    assert.equal(resolve(this.options.directory), directory);
    if (remaining && reply.text?.includes('UM02_FIRST_' + nonce)) {
      remaining--;
      await writeFile(
        resolve(directory, 'controlled-preflight-fault.json'),
        JSON.stringify({
          kind: 'isolated-before-model-catalog-fault',
          pid: process.pid,
          nonce,
          injectedAt: new Date().toISOString(),
        }),
        { mode: 0o600, flag: 'wx' },
      );
      throw Error('Controlled isolated preflight read failure');
    }
    return validate.call(this, reply, ...args);
  };
}
await startRuntimeServer();
