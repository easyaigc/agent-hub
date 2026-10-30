import assert from 'node:assert/strict';
import test from 'node:test';
import {
  mkdtemp,
  realpath,
  writeFile,
  readFile,
  rm,
  readdir,
  chmod,
  symlink,
} from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { randomUUID, randomBytes, createHash } from 'node:crypto';
import { FileTransfers } from '../../../apps/host-agent/dist/file-transfers.js';
import {
  protectWindowsStateDirectory,
  protectWindowsStateFile,
} from '../../../packages/codex-adapter/dist/desktop-platform.js';
import { main } from './transfer-host.mjs';

const hostId = '00000000-0000-4000-8000-000000000001';
async function fixture(t) {
  const directory = await mkdtemp(join(await realpath(tmpdir()), 'host-transfer-'));
  protectWindowsStateDirectory(directory);
  t.after(() => rm(directory, { recursive: true, force: true }));
  const registry = {
    schemaVersion: 1,
    hosts: [{ hostId, endpoint: 'https://fixture.invalid', clientKey: 'A'.repeat(48) }],
  };
  await writeFile(join(directory, 'hosts.json'), JSON.stringify(registry), { mode: 0o600 });
  protectWindowsStateFile(join(directory, 'hosts.json'));
  const file = join(directory, '交付文件.bin'),
    bytes = randomBytes(1024 * 80 + 3),
    id = randomUUID();
  await writeFile(file, bytes, { mode: 0o600 });
  const target = { agentId: 'codex', hostId, workspaceId: hostId, runtimeId: 'desktop-runtime' };
  const store = new FileTransfers(hostId, join(directory, 'state'), join(directory, 'received'));
  const calls = [],
    outputs = [];
  let fault,
    corruptReceipt = false,
    receiptExtras = {};
  const transport = async (path, body, authenticated = true) => {
    calls.push({ path, body });
    if (path === '/healthz')
      return {
        http: authenticated ? 200 : 401,
        value: { service: 'host-agent', protocolVersion: '0.1' },
      };
    if (path === '/v1/workspace')
      return {
        http: 200,
        value: {
          hostId,
          connectionMode: 'desktop-runtime',
          readTarget: target,
          capabilities: ['files:upload'],
        },
      };
    const url = new URL(path, 'https://fixture.invalid');
    if (path === '/v1/file-transfers') {
      const value = await store.prepare(body);
      if (fault === 'prepare') {
        fault = undefined;
        throw Object.assign(Error('lost prepare'), { code: 'NETWORK_OUTCOME_UNKNOWN' });
      }
      return { http: 200, value };
    }
    if (url.pathname === '/v1/file-transfers/content') {
      const interrupted = fault === 'partial';
      if (interrupted) fault = undefined;
      try {
        const value = await store.upload(
          id,
          (async function* () {
            yield body.subarray(0, 103);
            if (interrupted) throw Error('disconnected');
            yield body.subarray(103);
          })(),
        );
        if (fault === 'content') {
          fault = undefined;
          throw Error('lost completion response');
        }
        return {
          http: 200,
          value: {
            ...value,
            ...(corruptReceipt ? { sha256: '0'.repeat(64) } : {}),
            ...receiptExtras,
          },
        };
      } catch {
        throw Object.assign(Error('network loss'), { code: 'NETWORK_OUTCOME_UNKNOWN' });
      }
    }
    if (url.pathname === '/v1/file-transfers') {
      try {
        return { http: 200, value: await store.status(url.searchParams.get('id')) };
      } catch (error) {
        if (error.status === 404) return { http: 404 };
        throw error;
      }
    }
    throw Error('Unexpected route: ' + path);
  };
  return {
    directory,
    file,
    bytes,
    id,
    target,
    registry,
    store,
    calls,
    outputs,
    setFault: (value) => {
      fault = value;
    },
    corruptReceipt: () => {
      corruptReceipt = true;
    },
    setReceiptExtras: (value) => {
      receiptExtras = value;
    },
    run: (command, extra = []) =>
      main([command, '--host-id', hostId, '--operation-id', id, ...extra], {
        directory,
        transport,
        output: (value) => outputs.push(JSON.parse(value)),
      }),
    writes: () => calls.filter((call) => call.body !== undefined),
  };
}

test('real receiver publishes exact binary bytes once, repeated send and status only query', async (t) => {
  const f = await fixture(t);
  assert.equal(await f.run('send', ['--file', f.file]), 0);
  const receipt = f.outputs.at(-1).receipt;
  assert.deepEqual(await readFile(receipt.destinationPath), f.bytes);
  assert.equal(receipt.sha256, createHash('sha256').update(f.bytes).digest('hex'));
  await rm(f.file);
  await f.run('send');
  await f.run('status');
  await f.run('resume');
  assert.equal(f.writes().length, 2);
  assert.equal(
    (await readdir(join(f.directory, 'received'))).filter((name) => !name.startsWith('.')).length,
    1,
  );
  await assert.rejects(readFile(join(f.directory, 'file-transfers', f.id, 'content.bin')), {
    code: 'ENOENT',
  });
});

test('lost prepare response survives process reentry; explicit resume uses frozen bytes', async (t) => {
  const f = await fixture(t);
  f.setFault('prepare');
  assert.equal(await f.run('send', ['--file', f.file]), 2);
  await writeFile(f.file, 'changed original');
  await f.run('send');
  await f.run('status');
  assert.equal(f.writes().length, 1);
  assert.equal(f.outputs.at(-1).receipt.phase, 'prepared');
  assert.equal(await f.run('resume'), 0);
  assert.deepEqual(await readFile(f.outputs.at(-1).receipt.destinationPath), f.bytes);
  assert.equal(f.writes().length, 2);
});

test('lost upload completion queries original receipt without retransmission', async (t) => {
  const f = await fixture(t);
  f.setFault('content');
  await f.run('send', ['--file', f.file]);
  assert.equal(f.outputs.at(-1).status, 'outcome-unknown');
  assert.equal(await f.run('send'), 0);
  assert.equal(f.writes().length, 2);
});

test('interrupted stream is never published; explicit resume reuses the ID and validates snapshot', async (t) => {
  const f = await fixture(t);
  f.setFault('partial');
  await f.run('send', ['--file', f.file]);
  assert.equal((await f.store.status(f.id)).phase, 'prepared');
  await f.run('send');
  assert.equal(f.writes().length, 2);
  await f.run('resume');
  assert.equal(f.writes().length, 3);
  assert.deepEqual(await readFile(f.outputs.at(-1).receipt.destinationPath), f.bytes);
});

test('unknown prepare with no remote receipt stays query-only until explicit resume', async (t) => {
  const f = await fixture(t);
  f.setFault('prepare');
  await f.run('send', ['--file', f.file]);
  await rm(join(f.directory, 'state', 'file-transfers', f.id + '.json'));
  await f.run('send');
  assert.equal(f.outputs.at(-1).status, 'not-found');
  assert.equal(f.writes().length, 1);
  await f.run('resume');
  assert.equal(f.outputs.at(-1).status, 'completed');
});

test('full machine target mismatch prevents all remote writes', async (t) => {
  for (const key of ['agentId', 'hostId', 'workspaceId', 'runtimeId']) {
    const f = await fixture(t);
    f.target[key] = 'different';
    await assert.rejects(f.run('send', ['--file', f.file]), {
      code: 'HOST_RUNTIME_IDENTITY_MISMATCH',
    });
    assert.equal(f.writes().length, 0);
  }
});

test('changed source intent and changed saved bytes cannot overwrite an existing transfer', async (t) => {
  const f = await fixture(t);
  f.setFault('prepare');
  await f.run('send', ['--file', f.file]);
  await writeFile(f.file, 'different');
  await assert.rejects(f.run('send', ['--file', f.file]), { code: 'TRANSFER_INTENT_IMMUTABLE' });
  await writeFile(join(f.directory, 'file-transfers', f.id, 'content.bin'), 'different');
  await assert.rejects(f.run('resume'), { code: 'SAVED_CONTENT_CHANGED' });
  assert.equal(f.writes().length, 1);
});

test('mismatching receipt cannot be reported as successful delivery', async (t) => {
  const f = await fixture(t);
  f.corruptReceipt();
  await assert.rejects(f.run('send', ['--file', f.file]), { code: 'TRANSFER_RECEIPT_MISMATCH' });
  assert.equal(f.outputs.length, 0);
  assert.deepEqual(
    await readFile(join(f.directory, 'file-transfers', f.id, 'content.bin')),
    f.bytes,
  );
});

test('file receipts expose bounded metadata without echoing arbitrary remote file bodies', async (t) => {
  const f = await fixture(t);
  const encoded = randomBytes(32 * 1024).toString('base64');
  f.setReceiptExtras({ bytes: encoded, content: { base64: encoded }, text: 'remote body' });
  assert.equal(await f.run('send', ['--file', f.file]), 0);
  const output = JSON.stringify(f.outputs.at(-1));
  assert(Buffer.byteLength(output) + 1 <= 16 * 1024);
  assert(!output.includes(encoded));
  assert(!output.includes('remote body'));
  assert.deepEqual(await readFile(f.outputs.at(-1).receipt.destinationPath), f.bytes);
  const saved = await readFile(join(f.directory, 'file-transfers', f.id, 'receipt.json'), 'utf8');
  assert(!saved.includes(encoded));
});

test('oversized file receipt fields are rejected while the original bytes remain queryable', async (t) => {
  const f = await fixture(t);
  f.setReceiptExtras({ destinationPath: '测'.repeat(1400) });
  await assert.rejects(f.run('send', ['--file', f.file]), { code: 'TRANSFER_RECEIPT_MISMATCH' });
  assert.equal(f.outputs.length, 0);
  assert.deepEqual(
    await readFile(join(f.directory, 'file-transfers', f.id, 'content.bin')),
    f.bytes,
  );
});

test('HTTP registry, invalid filenames, public credentials and source symlinks are rejected', async (t) => {
  const f = await fixture(t);
  f.registry.hosts[0].endpoint = 'http://fixture.invalid';
  await writeFile(join(f.directory, 'hosts.json'), JSON.stringify(f.registry));
  await assert.rejects(f.run('send', ['--file', f.file]), { code: 'HTTPS_ROOT_REQUIRED' });
  f.registry.hosts[0].endpoint = 'https://fixture.invalid';
  await writeFile(join(f.directory, 'hosts.json'), JSON.stringify(f.registry));
  const invalid = join(f.directory, 'a'.repeat(181));
  await writeFile(invalid, 'invalid');
  await assert.rejects(f.run('send', ['--file', invalid]), { code: 'INVALID_FILE_MANIFEST' });
  if (process.platform !== 'win32') {
    const link = join(f.directory, 'source-link');
    await symlink(f.file, link);
    await assert.rejects(f.run('send', ['--file', link]), { code: 'REGULAR_SOURCE_FILE_REQUIRED' });
    await chmod(join(f.directory, 'hosts.json'), 0o644);
    await assert.rejects(f.run('send', ['--file', f.file]), { code: 'UNSAFE_PRIVATE_PERMISSIONS' });
  }
  assert.equal(f.writes().length, 0);
});
