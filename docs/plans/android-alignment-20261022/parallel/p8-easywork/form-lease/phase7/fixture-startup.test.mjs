import assert from 'node:assert/strict';
import test from 'node:test';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { once } from 'node:events';
import {
  chmod,
  mkdir,
  mkdtemp,
  readFile,
  readdir,
  realpath,
  rm,
  writeFile,
} from 'node:fs/promises';
import { createConnection } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { canonical, hash } from '../../authorization-boundaries/fixture.mjs';

const bounded = async (promise, ms, label) => {
  let timer;
  try {
    return await Promise.race([
      promise,
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(label)), ms);
      }),
    ]);
  } finally {
    clearTimeout(timer);
  }
};
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const sourceNames = [
  'form-lease/phase7/fixture.mjs',
  'pairing-ui/fixture.mjs',
  'authorization-fence/phase2/fixture.mjs',
  'authorization-boundaries/fixture.mjs',
];

test(
  'real --start from an encoded file URL serves exact health and closes both owned ports',
  { timeout: 20_000 },
  async (t) => {
    const root = await realpath(await mkdtemp(join(tmpdir(), 'p8-positive-startup-')));
    const repo = join(root, 'source space % 中文');
    const sourceRoot = join(repo, 'docs/plans/android-alignment-20261022/parallel/p8-easywork');
    const directory = join(root, 'private state');
    const graph = {};
    let child, exited;
    t.after(async () => {
      if (child && child.exitCode === null && child.signalCode === null) {
        child.kill('SIGTERM');
        try {
          await bounded(exited, 3000, 'Owned startup fixture did not close');
        } catch (error) {
          child.kill('SIGKILL');
          await bounded(exited, 3000, 'Owned startup fixture did not terminate');
          throw error;
        }
      }
      await rm(root, { recursive: true, force: true });
    });
    for (const name of sourceNames) {
      const bytes = await readFile(new URL('../../' + name, import.meta.url));
      const path = join(sourceRoot, name);
      await mkdir(dirname(path), { recursive: true, mode: 0o700 });
      await writeFile(path, bytes, { flag: 'wx', mode: 0o600 });
      graph[name] = sha(bytes);
    }
    const asset = await readFile(
      new URL(
        '../../../../../../../apps/android/src/androidTest/assets/easywork-crypto-vector.json',
        import.meta.url,
      ),
    );
    const assetPath = join(repo, 'apps/android/src/androidTest/assets/easywork-crypto-vector.json');
    await mkdir(dirname(assetPath), { recursive: true, mode: 0o700 });
    await writeFile(assetPath, asset, { flag: 'wx', mode: 0o600 });
    await chmod(root, 0o700);
    child = spawn(process.execPath, [join(sourceRoot, sourceNames[0]), '--start', directory], {
      cwd: root,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    exited = once(child, 'exit');
    let stdout = '',
      stderr = '';
    const ready = new Promise((resolve, reject) => {
      child.once('error', reject);
      child.once('exit', (code, signal) =>
        reject(new Error(`Fixture exited before health: ${code}/${signal}: ${stderr}`)),
      );
      child.stdout.on('data', (chunk) => {
        stdout += chunk;
        if (stdout.length > 65536) return reject(new Error('Bounded fixture stdout exceeded'));
        if (stdout.includes('\n')) {
          try {
            resolve(JSON.parse(stdout.slice(0, stdout.indexOf('\n'))));
          } catch (error) {
            reject(error);
          }
        }
      });
      child.stderr.on('data', (chunk) => {
        stderr += chunk;
        if (stderr.length > 65536) reject(new Error('Bounded fixture stderr exceeded'));
      });
    });
    const config = await bounded(ready, 5000, 'Real fixture --start timed out');
    assert.equal(config.fixture, 'p8-synthetic-positive-commit-v1');
    assert.equal(config.sidecarPid, child.pid);
    assert.deepEqual(config.sourceGraph, graph);
    assert.equal(config.sourceGraphSha256, hash(canonical(graph)));
    assert.equal(config.sourceFixtureSha256, hash(asset));
    assert.deepEqual(JSON.parse(await readFile(join(directory, 'config.json'), 'utf8')), config);
    assert.notEqual(config.authOrigin, config.controlOrigin);
    for (const origin of [config.authOrigin, config.controlOrigin]) {
      const u = new URL(origin);
      assert.equal(u.protocol, 'http:');
      assert.equal(u.hostname, '127.0.0.1');
      assert(Number(u.port) > 0);
    }
    const health = await fetch(config.controlOrigin + '/health', {
      signal: AbortSignal.timeout(1500),
    });
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), {
      ...config,
      onlyLoopback: true,
      realDesktop: false,
      realModel: false,
    });
    // No case is created. Even this synthetic auth service must refuse an unauthenticated read.
    const auth = await fetch(config.authOrigin + '/v2/remote-access/relay/pending', {
      signal: AbortSignal.timeout(1500),
    });
    assert.equal(auth.status, 403);
    assert.deepEqual(await auth.json(), { error: { code: 'no_active_owned_case' } });
    assert(child.kill('SIGTERM'));
    assert.deepEqual(await bounded(exited, 3000, 'SIGTERM did not close real fixture'), [0, null]);
    assert.equal(stderr, '');
    for (const origin of [config.authOrigin, config.controlOrigin]) {
      const port = Number(new URL(origin).port);
      await bounded(
        new Promise((resolve, reject) => {
          const socket = createConnection({ host: '127.0.0.1', port });
          socket.once('connect', () => {
            socket.destroy();
            reject(new Error('Owned fixture port still listens after close'));
          });
          socket.once('error', (error) => {
            socket.destroy();
            if (error.code === 'ECONNREFUSED') resolve();
            else reject(error);
          });
        }),
        1000,
        'Closed-port verification timed out',
      );
    }
    assert.deepEqual(await readdir(directory), ['config.json']);
    t.diagnostic(
      JSON.stringify({
        fixturePid: child.pid,
        startup: 'real CLI --start',
        health: 'exact config and source graph',
        sourceGraphSha256: config.sourceGraphSha256,
        exitCode: child.exitCode,
        closedOwnedPorts: 2,
        cases: 0,
        claims: 0,
        reverseRules: 0,
        realModelInputs: 0,
      }),
    );
  },
);
