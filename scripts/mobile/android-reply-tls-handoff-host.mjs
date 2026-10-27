import assert from 'node:assert/strict';
import { readFile, writeFile, realpath, stat } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { resolve, join, dirname, basename, isAbsolute } from 'node:path';
import { preparedAdapterDigest } from '../../packages/codex-adapter/dist/desktop-prepared-bridge.js';
import { startRuntimeServer } from '../../apps/host-agent/dist/runtime-server.js';

// Only a fresh owned successor creates this NEW checkpoint. No historical artifact is rewritten.
const root = await realpath(process.env.ANDROID_REPLY_TLS_CASE_ROOT ?? ''),
  state = await realpath(process.env.HOST_AGENT_STATE_DIR ?? '');
assert(isAbsolute(root) && !root.startsWith(process.cwd() + '/'));
assert(basename(root).startsWith('reply-tls-recovery-live-'));
assert.equal(state, join(root, 'host'));
assert.equal(process.env.CODEX_RUNTIME_MODE, 'desktop-runtime');
assert.equal(process.env.HOST_AGENT_BIND, '127.0.0.1');
const basePath = await realpath(process.env.ANDROID_REPLY_TLS_CHECKPOINT_BASE ?? '');
assert.equal(dirname(basePath), root);
const base = JSON.parse(await readFile(basePath, 'utf8')),
  nonce = process.env.ANDROID_REPLY_TLS_NONCE,
  hostId = process.env.ANDROID_REPLY_TLS_HOST_ID;
assert.match(nonce ?? '', /^[a-f0-9]{8}$/);
assert.equal(base.nonce, nonce);
assert.equal(base.hostId, hostId);
assert.equal(base.adapterSha256, preparedAdapterDigest());
const checkpoint = base.checkpoint;
assert.equal(checkpoint.version, 2);
assert.equal(checkpoint.kind, 'reply-ledger-fenced-handoff-v2');
assert.equal(checkpoint.target.hostId, hostId);
assert(!Object.hasOwn(checkpoint, 'newPid'));
assert(
  Number.isSafeInteger(checkpoint.oldPid) &&
    checkpoint.oldPid > 0 &&
    checkpoint.oldPid !== process.pid,
);
let gone = false;
try {
  process.kill(checkpoint.oldPid, 0);
} catch (e) {
  assert.equal(e.code, 'ESRCH');
  gone = true;
}
assert(gone, 'Actual original writer must have exited');
const sha = (x) => createHash('sha256').update(x).digest('hex');
const bytes = await readFile(join(state, 'runtime.json')),
  runtime = JSON.parse(bytes);
assert.equal(runtime.hostId, hostId);
assert.equal(sha(bytes), checkpoint.ledgerSha256);
assert.equal(runtime.replyLedger.writerPid, checkpoint.oldPid);
const fencePath = await realpath(checkpoint.fenceFile);
assert.equal(dirname(fencePath), root);
const fenceBytes = await readFile(fencePath),
  fence = JSON.parse(fenceBytes);
assert.equal(sha(fenceBytes), checkpoint.fenceSha256);
assert.equal(fence.oldPid, checkpoint.oldPid);
assert.equal(fence.writerNonce, runtime.replyLedger.writerNonce);
assert.equal(fence.ledgerId, runtime.replyLedger.ledgerId);
assert.equal(checkpoint.operations.length, 1);
assert.equal(checkpoint.operations[0].clientMessageId, base.originalReplyId);
assert.equal(checkpoint.operations[0].fingerprint, base.originalReplyFingerprint);
for (const file of [basePath, fencePath, join(state, 'runtime.json')]) {
  const s = await stat(file);
  assert.equal(s.uid, process.getuid());
  assert.equal(s.mode & 0o077, 0);
}
const output = join(root, 'reply-successor-checkpoint.json');
assert.equal(resolve(process.env.HOST_AGENT_REPLY_CHECKPOINT_FILE ?? ''), output);
await writeFile(output, JSON.stringify({ ...checkpoint, newPid: process.pid }), {
  mode: 0o600,
  flag: 'wx',
});
await writeFile(
  join(root, 'successor-checkpoint-producer.json'),
  JSON.stringify({
    nonce,
    hostId,
    pid: process.pid,
    oldPid: checkpoint.oldPid,
    baseSha256: sha(await readFile(basePath)),
    closedFenceSha256: checkpoint.fenceSha256,
    ledgerSha256: checkpoint.ledgerSha256,
    checkpointSha256: sha(await readFile(output)),
    historicalArtifactsRewritten: false,
  }),
  { mode: 0o600, flag: 'wx' },
);
await startRuntimeServer();
