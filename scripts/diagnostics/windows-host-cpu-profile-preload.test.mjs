import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtemp, readFile, writeFile, copyFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { spawn } from 'node:child_process';
import { setTimeout as delay } from 'node:timers/promises';
import { pathToFileURL } from 'node:url';

const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
const hostId = '11111111-1111-4111-8111-111111111111';
const operationId = '22222222-2222-4222-8222-222222222222';
const source = new URL('./windows-host-cpu-profile-preload.mjs', import.meta.url);
async function fixture(change = () => {}, wrongEntry = false) {
  const directory = await mkdtemp(join(tmpdir(), 'host-cpu-profile-fixture-'));
  let child;
  try {
    const module = join(directory, 'preload.mjs');
    const worker = join(directory, 'host-fixture.mjs');
    const configuration = join(directory, 'configuration.private.json');
    const mock = join(directory, 'platform.cjs');
    await copyFile(source, module);
    await writeFile(mock, "Object.defineProperty(process,'platform',{value:'win32'});", {
      mode: 0o600,
    });
    await writeFile(
      worker,
      `import{isMainThread,parentPort,Worker}from'node:worker_threads';
if(!isMainThread){parentPort.postMessage('own fixture worker unaffected');}else{
function ownedCpuWork(){let total=0;const end=performance.now()+60;while(performance.now()<end)total+=Math.sqrt(total+1);return total;}
ownedCpuWork();const w=new Worker(new URL(import.meta.url));await new Promise(r=>w.once('message',r));await new Promise(r=>w.once('exit',r));
process.send?.({fixtureReady:true,pid:process.pid});const hold=setInterval(()=>{},500);process.on('message',m=>{if(m==='owned-fixture-stop'){clearInterval(hold);process.disconnect();}});
}`,
      { mode: 0o600 },
    );
    await writeFile(
      configuration,
      JSON.stringify({
        environment: {
          CODEX_RUNTIME_MODE: 'desktop-runtime',
          HOST_AGENT_STATE_DIR: directory,
          HOST_AGENT_TOKEN: 'synthetic-private-value-must-not-be-profiled',
        },
      }),
      { mode: 0o600 },
    );
    await writeFile(join(directory, 'runtime.json'), JSON.stringify({ hostId }), { mode: 0o600 });
    const plan = {
      schemaVersion: 1,
      operationId,
      hostId,
      nodePath: process.execPath,
      nodeSha256: hash(await readFile(process.execPath)),
      workerPath: worker,
      workerSha256: hash(await readFile(worker)),
      configurationPath: configuration,
      configurationSha256: hash(await readFile(configuration)),
    };
    await change(plan, directory);
    await writeFile(join(directory, 'host-cpu-profile-plan.private.json'), JSON.stringify(plan), {
      mode: 0o600,
    });
    const args = [
      '--require',
      mock,
      '--import',
      pathToFileURL(module).href,
      worker,
      configuration,
      wrongEntry ? '--different-helper' : '--host-child',
    ];
    child = spawn(process.execPath, args, { stdio: ['ignore', 'pipe', 'pipe', 'ipc'] });
    let stderr = '',
      ready = false,
      closed = false;
    child.stderr.on('data', (bytes) => (stderr += bytes));
    child.on('message', (message) => (ready = message.fixtureReady === true));
    const exit = new Promise((resolve) =>
      child.once('close', (code) => {
        closed = true;
        resolve(code);
      }),
    );
    const deadline = performance.now() + 8000;
    while (!ready && !closed && performance.now() < deadline) await delay(10);
    assert(ready || closed, 'Only this owned fixture must settle within its deadline');
    const meta = join(directory, `host-cpu-profile-${child.pid}.meta.private.json`);
    let initial;
    try {
      initial = JSON.parse(await readFile(meta, 'utf8'));
    } catch {
      // Rejected preload fixtures intentionally produce no profile metadata.
    }
    if (initial) {
      await writeFile(
        join(directory, 'host-cpu-profile-stop.private.json'),
        JSON.stringify({ operationId: '33333333-3333-4333-8333-333333333333' }),
        { mode: 0o600 },
      );
      await delay(550);
      assert(
        !JSON.parse(await readFile(meta, 'utf8')).profile,
        'A different run cannot stop the sampler',
      );
      await writeFile(
        join(directory, 'host-cpu-profile-stop.private.json'),
        JSON.stringify({ operationId }),
        { mode: 0o600 },
      );
      while (!JSON.parse(await readFile(meta, 'utf8')).stopped && performance.now() < deadline)
        await delay(20);
      const result = JSON.parse(await readFile(meta, 'utf8'));
      assert(
        result.stopped && result.profile.sampleCount > 0,
        'Actual current-process CPU samples required',
      );
      const profile = await readFile(join(directory, `host-cpu-profile-${child.pid}.cpuprofile`));
      assert.equal(hash(profile), result.profile.sha256);
      assert(!profile.includes(Buffer.from('synthetic-private-value-must-not-be-profiled')));
      assert.equal(
        (await readdir(directory)).filter((name) => name.endsWith('.cpuprofile')).length,
        1,
        'Reader/worker imports must not start another profiler',
      );
      assert.equal(result.stopReason, 'owned-stop-marker');
    }
    if (!closed) child.send('owned-fixture-stop');
    const code = await exit;
    return { active: !!initial, code, stderr };
  } finally {
    if (child?.exitCode === null) child.kill(); // Only this test-created, bounded fixture.
    await rm(directory, { recursive: true, force: true });
  }
}

test('exact current-process plan profiles only Main and requires the original stop operation', async () => {
  const result = await fixture();
  assert.equal(result.active, true);
  assert.equal(result.code, 0);
});
for (const field of ['nodeSha256', 'workerSha256', 'configurationSha256'])
  test(`a changed ${field} never starts sampling`, async () => {
    const result = await fixture((plan) => {
      plan[field] = '0'.repeat(64);
    });
    assert.equal(result.active, false);
    assert.equal(result.code, 0);
  });
test('another entry or an unknown plan field never starts sampling', async () => {
  assert.equal((await fixture(() => {}, true)).active, false);
  assert.equal(
    (
      await fixture((plan) => {
        plan.extra = 'unsupported';
      })
    ).active,
    false,
  );
});
test('the exact runtime Host identity is required before the Host entry runs', async () => {
  const result = await fixture((plan) => {
    plan.hostId = '44444444-4444-4444-8444-444444444444';
  });
  assert.equal(result.active, false);
  assert.notEqual(result.code, 0);
  assert.match(result.stderr, /HOST_CPU_PROFILE_TARGET_MISMATCH/);
});
