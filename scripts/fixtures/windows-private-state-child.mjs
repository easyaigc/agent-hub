import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import {
  appendFileSync,
  closeSync,
  existsSync,
  fstatSync,
  mkdirSync,
  openSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import { dirname, isAbsolute, relative, resolve, sep } from 'node:path';
import { createInterface } from 'node:readline';

// Synthetic stdio only. All paths, actions and effects belong to the test fixture.
const config = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const record = (value) => appendFileSync(config.log, JSON.stringify(value) + '\n');
record({ event: 'start', pid: process.pid, args: process.argv.slice(3) });
process.once('exit', () => record({ event: 'exit' }));
process.once('SIGTERM', () => process.exit(0));
// A broken lifecycle test must not leave a permanent fixture process behind.
setTimeout(() => process.exit(91), 45000).unref();

const ready = 'PRIVATE_STATE_READY\t1\n';
if (config.mode === 'bad-ready') process.stdout.write('PRIVATE_STATE_READY\t2\n');
else if (config.mode === 'crlf-ready') process.stdout.write(ready.replace('\n', '\r\n'));
else if (config.mode === 'trailing-ready') process.stdout.write(ready + '1\tPRIVATE_STATE_OK\n');
else if (config.mode === 'exit-ready') process.exit(19);
else if (config.mode === 'startup-timeout') {
  // Deliberately keep stdin open until the real startup deadline retires us.
} else if (config.mode === 'fragmented') {
  process.stdout.write(ready.slice(0, 5));
  setTimeout(() => process.stdout.write(ready.slice(5)), 15);
} else process.stdout.write(ready);

const leases = new Set();
let count = 0;
const lines = createInterface({ input: process.stdin });
lines.on('line', (line) => {
  if (line === 'QUIT') {
    record({ event: 'quit' });
    if (config.mode !== 'late') process.exit(0);
    return;
  }
  const parts = line.split('\t');
  assert.equal(parts.length, 4);
  const [id, action, encoded, cwdEncoded] = parts;
  assert.equal(id, String(++count));
  assert(
    [
      'protect',
      'protect-file',
      'check',
      'create-directory',
      'create-file',
      'release-file',
    ].includes(action),
  );
  const path = Buffer.from(encoded, 'base64').toString('utf8');
  const cwd = Buffer.from(cwdEncoded, 'base64').toString('utf8');
  assert.equal(Buffer.from(path).toString('base64'), encoded);
  assert.equal(Buffer.from(cwd).toString('base64'), cwdEncoded);
  record({ event: 'request', id: count, action, path, cwd });
  if (action === 'release-file') {
    if (config.mode === 'unknown-release') {
      process.stdout.write(`${id}\tPRIVATE_STATE_UNKNOWN\n`);
      return;
    }
    if (!leases.delete(path))
      process.stdout.write(`${id}\tUNSAFE_WINDOWS_PRIVATE_STATE:UnauthorizedAccessException\n`);
    else process.stdout.write(`${id}\tPRIVATE_STATE_OK\n`);
    return;
  }
  let createdIdentity;
  if (config.mode === 'legacy-owner' && path.includes('runtime-history-v1')) {
    process.stdout.write(`${id}\tUNSAFE_WINDOWS_PRIVATE_STATE:UnauthorizedAccessException\n`);
    return;
  }
  if (action.startsWith('create-') && !(config.mode === 'reject-second' && count === 2)) {
    const contained = relative(dirname(config.log), resolve(cwd, path));
    assert(contained !== '..' && !contained.startsWith('..' + sep) && !isAbsolute(contained));
    if (config.mode === 'create-race' && action === 'create-file') {
      writeFileSync(path, 'concurrent-owner', { flag: 'wx' });
      process.stdout.write(
        `${id}\tUNSAFE_WINDOWS_PRIVATE_STATE:PrivateStateAlreadyExistsException\n`,
      );
      return;
    }
    if (action === 'create-directory') mkdirSync(path, { recursive: true, mode: 0o700 });
    else {
      const file = openSync(path, 'wx', 0o600);
      if (config.mode === 'unknown-release') writeFileSync(file, 'preserve-unreleased-A');
      const info = fstatSync(file, { bigint: true });
      const reservation = randomUUID().replaceAll('-', '');
      leases.add(reservation);
      createdIdentity =
        reservation +
        ':' +
        info.dev.toString(16).padStart(8, '0') +
        ':' +
        info.ino.toString(16).padStart(16, '0');
      closeSync(file);
    }
  }
  const ok = `${id}\t${createdIdentity ? 'PRIVATE_STATE_CREATED:' + createdIdentity : 'PRIVATE_STATE_OK'}\n`;
  if (config.mode === 'owner-evidence-v2-partial' && action === 'check') {
    const frame = [
      'protect-file',
      String(process.pid),
      '-',
      '1'.repeat(64),
      '-',
      '2'.repeat(64),
      '3'.repeat(64),
      '4'.repeat(64),
      '-',
      'file',
      'final-path-hash',
      'Win32Exception',
    ].join('|');
    return process.stdout.write(
      `${id}\tUNSAFE_WINDOWS_PRIVATE_STATE:UnauthorizedAccessException:owner-check:OWNER2:${frame}\n`,
    );
  }
  if (config.mode?.startsWith('owner-evidence') && action === 'check') {
    const evidence = [
      action,
      String(process.pid + (config.mode === 'owner-evidence-wrong-pid' ? 1 : 0)),
      '179340245100000000',
      config.mode === 'owner-evidence-bad-hash' ? 'not-a-hash' : '1'.repeat(64),
      '2'.repeat(64),
      '3'.repeat(64),
      '4'.repeat(64),
      '5'.repeat(64),
      '6'.repeat(64),
      'file',
    ].join('|');
    return process.stdout.write(
      `${id}\tUNSAFE_WINDOWS_PRIVATE_STATE:UnauthorizedAccessException:owner-check:OWNER1:${evidence}\n`,
    );
  }
  if (config.mode?.startsWith('access-step-'))
    return process.stdout.write(
      `${id}\tUNSAFE_WINDOWS_PRIVATE_STATE:UnauthorizedAccessException:${config.mode.slice('access-step-'.length)}\n`,
    );
  if (config.mode?.startsWith('directory-step-'))
    process.stdout.write(
      `${id}\tUNSAFE_WINDOWS_PRIVATE_STATE:ArgumentException:${config.mode.slice('directory-step-'.length)}\n`,
    );
  else if (config.mode === 'wrong-id') process.stdout.write(`${count + 1}\tPRIVATE_STATE_OK\n`);
  else if (config.mode === 'stale-id' && count > 1)
    process.stdout.write(`${count - 1}\tPRIVATE_STATE_OK\n`);
  else if (config.mode === 'duplicate') process.stdout.write(ok + ok);
  else if (config.mode === 'crlf-response') process.stdout.write(ok.replace('\n', '\r\n'));
  else if (config.mode === 'non-ascii') process.stdout.write(`${id}\tPRIVATE_STATE_é\n`);
  else if (config.mode === 'oversized') process.stdout.write('x'.repeat(config.responseBytes + 1));
  else if (config.mode === 'malformed') process.stdout.write(`${id}\tPRIVATE_STATE_OK\textra\n`);
  else if (config.mode === 'stderr') process.stderr.write('synthetic native failure\n');
  else if (config.mode === 'exit-operation') process.exit(23);
  else if (config.mode === 'timeout') {
    // Effect recorded above; do not acknowledge or retry it.
  } else if (config.mode === 'async-held') {
    const timer = setInterval(() => {
      if (existsSync(config.log + '.release')) {
        clearInterval(timer);
        process.stdout.write(ok);
      }
    }, 10);
  } else if (config.mode === 'late') setTimeout(() => process.stdout.write(ok), 5500);
  else if (config.mode === 'reject-second' && count === 2)
    process.stdout.write(`${id}\tUNSAFE_WINDOWS_PRIVATE_STATE:UnauthorizedAccessException\n`);
  else if (config.mode === 'max-error')
    process.stdout.write(`${id}\tUNSAFE_WINDOWS_PRIVATE_STATE:${'E'.repeat(128)}\n`);
  else if (config.mode === 'overlong-error')
    process.stdout.write(`${id}\tUNSAFE_WINDOWS_PRIVATE_STATE:${'E'.repeat(129)}\n`);
  else if (config.mode === 'fragmented') {
    process.stdout.write(ok.slice(0, 2));
    setTimeout(() => process.stdout.write(ok.slice(2)), 15);
  } else process.stdout.write(ok);
});
lines.once('close', () => process.exit(0));
