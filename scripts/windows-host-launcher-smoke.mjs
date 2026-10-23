import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { existsSync } from 'node:fs';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { windowsHostLauncherExecutable } from '../packages/codex-adapter/dist/desktop-platform.js';

// No formal Host, desktop, Scheduler, credentials or model. Native Windows
// checks the compiled GUI binary, wait/exit semantics and owned job cleanup.
if (process.platform !== 'win32') {
  console.log(JSON.stringify({ nativeWindowsLauncher: 'not-run', platform: process.platform }));
} else {
  const directory = await mkdtemp(join(tmpdir(), 'host-launcher-'));
  let launcher;
  let launcherClosed = true;
  let launcherError;
  const descendants = [];
  const waitFor = async (condition) => {
    for (let i = 0; i < 200; i++) {
      if (launcherError) throw launcherError;
      if (await condition()) return;
      await delay(50);
    }
    throw Error('Native launcher fixture timed out');
  };
  const alive = (pid) => {
    try {
      process.kill(pid, 0);
      return true;
    } catch {
      return false;
    }
  };
  try {
    const executable = windowsHostLauncherExecutable();
    const pe = await readFile(executable);
    const header = pe.readUInt32LE(0x3c);
    assert.equal(pe.toString('ascii', header, header + 4), 'PE\0\0');
    assert.equal(pe.readUInt16LE(header + 24 + 68), 2, 'Launcher must use Windows GUI subsystem');
    const workspace = join(directory, "中文 space & apostrophe'");
    await mkdir(workspace);
    const worker = join(workspace, 'windows-desktop-worker.mjs');
    const config = join(workspace, 'windows-host.json');
    const ready = join(directory, 'ready.json');
    const release = join(directory, 'release');
    await writeFile(config, '{}');
    await writeFile(
      worker,
      `import {spawn} from 'node:child_process';
import {writeFileSync,existsSync} from 'node:fs';
if(process.argv[2]!==${JSON.stringify(config)})process.exit(88);
const helper=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{windowsHide:true,stdio:'ignore'});
writeFileSync(${JSON.stringify(ready)},JSON.stringify({pid:process.pid,helper:helper.pid}));
setInterval(()=>{if(existsSync(${JSON.stringify(release)}))process.exit(37)},25);`,
    );
    const launch = () => {
      launcherClosed = false;
      launcher = spawn(executable, [process.execPath, worker, config], {
        windowsHide: true,
        stdio: 'ignore',
      });
      return new Promise((resolve) => {
        launcher.once('error', (error) => {
          launcherError = error;
        });
        launcher.once('close', (code, signal) => {
          launcherClosed = true;
          resolve([code, signal]);
        });
      });
    };
    const closed = launch();
    await waitFor(() => existsSync(ready));
    const first = JSON.parse(await readFile(ready, 'utf8'));
    descendants.push(first.pid, first.helper);
    await delay(200);
    assert.equal(launcher.exitCode, null, 'Launcher detached from its live worker');
    assert(alive(first.pid) && alive(first.helper));
    await writeFile(release, 'exit');
    assert.deepEqual(await closed, [37, null], 'Worker exit code was not propagated');
    await waitFor(() => descendants.every((pid) => !alive(pid)));
    await rm(ready);
    await rm(release);
    const killed = launch();
    await waitFor(() => existsSync(ready));
    const second = JSON.parse(await readFile(ready, 'utf8'));
    descendants.push(second.pid, second.helper);
    launcher.kill();
    await killed;
    await waitFor(() => descendants.every((pid) => !alive(pid)));
    console.log(
      JSON.stringify({
        nativeWindowsLauncher: 'passed',
        guiSubsystem: true,
        waitAndExitCode: true,
        ownedJobCleanup: true,
        officialDesktop: 'not-touched',
      }),
    );
  } finally {
    if (launcher && !launcherClosed) {
      const closed = once(launcher, 'close');
      launcher.kill();
      await closed;
    }
    // All PIDs here were created in this fixture and remain owned by its job.
    // Leave evidence on cleanup failure rather than killing arbitrary reused IDs.
    if (descendants.some(alive)) {
      console.error(
        'Native launcher fixture left an owned process; preserve directory: ' + directory,
      );
      process.exitCode = 1;
    } else await rm(directory, { recursive: true, force: true });
  }
}
