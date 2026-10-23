import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  writeFileSync,
} from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

// Preparation/check never launch anything. Launch is an explicit, one-use diagnostic
// action through LaunchServices; it never quits an app or changes global environment.
const app = '/Applications/ChatGPT.app';
const executable = join(app, 'Contents/MacOS/ChatGPT');
const executor = join(app, 'Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex');
const [mode, input, ...extra] = process.argv.slice(2);
if (
  process.platform !== 'darwin' ||
  !['--prepare', '--check', '--launch', '--wait-and-launch'].includes(mode) ||
  !input ||
  extra.length
)
  throw new Error(
    'Usage on macOS: node scripts/desktop-suggestion-trace-launch.mjs --prepare|--check|--launch|--wait-and-launch <absolute-private-directory>',
  );
if (!isAbsolute(input)) throw new Error('An absolute private directory is required');
const root = resolve(input);
process.umask(0o077);

function directory(path, create = false) {
  if (create) mkdirSync(path, { recursive: true, mode: 0o700 });
  let cursor = path;
  while (cursor !== dirname(cursor)) {
    if (lstatSync(cursor).isSymbolicLink())
      throw new Error('Symbolic directory paths are not allowed');
    cursor = dirname(cursor);
  }
  const info = lstatSync(path);
  if (!info.isDirectory() || info.uid !== process.getuid() || (info.mode & 0o777) !== 0o700)
    throw new Error('Directory must be owned by this user with mode 0700');
}

function originalProcesses() {
  // Never read command arguments/environment, which may contain unrelated private data.
  const output = execFileSync('/bin/ps', ['-axo', 'pid=,ppid=,uid=,lstart=,comm='], {
    encoding: 'utf8',
    env: { ...process.env, LC_ALL: 'C' },
  });
  return output.split('\n').flatMap((line) => {
    const match = /^\s*(\d+)\s+(\d+)\s+(\d+)\s+((?:\S+\s+){4}\S+)\s+(.*)$/.exec(line);
    if (
      !match ||
      ![executable, executor].includes(match[5]) ||
      Number(match[3]) !== process.getuid()
    )
      return [];
    return [
      {
        pid: Number(match[1]),
        ppid: Number(match[2]),
        uid: Number(match[3]),
        startedAt: match[4].replace(/\s+/g, ' '),
        executable: match[5],
      },
    ];
  });
}
const fingerprint = (path) => createHash('sha256').update(readFileSync(path)).digest('hex');
const shellQuote = (text) => "'" + text.replaceAll("'", "'\\''") + "'";
const traceRoot = join(root, 'traces');
const intent = join(root, 'launch-intent.json');
const claim = join(root, 'launch-claim');

if (mode === '--prepare') {
  directory(root, true);
  if (readdirSync(root).length) throw new Error('Preparation requires a new empty directory');
  directory(traceRoot, true);
  const processes = originalProcesses();
  const apps = processes.filter((p) => p.executable === executable);
  const ownedExecutors = processes.filter(
    (p) => p.executable === executor && apps.some((a) => a.pid === p.ppid),
  );
  const launcher = join(root, 'launch-original-codex.command');
  writeFileSync(
    intent,
    JSON.stringify(
      {
        version: 2,
        application: app,
        applicationSha256: fingerprint(executable),
        executorSha256: fingerprint(executor),
        traceRoot,
        diagnosticRequestHeaders: true,
        originalApplicationMustBeStopped: true,
        modelInputsSubmitted: 0,
        originalProcesses: [...apps, ...ownedExecutors],
      },
      null,
      2,
    ) + '\n',
    { mode: 0o600, flag: 'wx' },
  );
  writeFileSync(
    launcher,
    '#!/bin/zsh\nset -eu\n' +
      [process.execPath, fileURLToPath(import.meta.url), '--launch', root]
        .map(shellQuote)
        .join(' ') +
      '\n',
    { mode: 0o700, flag: 'wx' },
  );
  console.log(
    JSON.stringify({
      prepared: true,
      launched: false,
      launcher,
      traceRoot,
      originalPids: [...apps, ...ownedExecutors].map((p) => p.pid),
    }),
  );
} else {
  directory(root);
  directory(traceRoot);
  const info = lstatSync(intent);
  if (
    !info.isFile() ||
    info.isSymbolicLink() ||
    info.nlink !== 1 ||
    info.uid !== process.getuid() ||
    (info.mode & 0o777) !== 0o600
  )
    throw new Error('Invalid private launch intent');
  const value = JSON.parse(readFileSync(intent, 'utf8'));
  if (
    value.version !== 2 ||
    value.application !== app ||
    value.traceRoot !== traceRoot ||
    value.applicationSha256 !== fingerprint(executable) ||
    value.executorSha256 !== fingerprint(executor) ||
    !Array.isArray(value.originalProcesses)
  )
    throw new Error('Installation or prepared intent changed; prepare a new directory');
  const blockingPids = () =>
    originalProcesses()
      .filter(
        (p) =>
          p.executable === executable ||
          value.originalProcesses.some(
            (old) =>
              old.pid === p.pid &&
              old.uid === p.uid &&
              old.startedAt === p.startedAt &&
              old.executable === p.executable,
          ),
      )
      .map((p) => p.pid);
  let blockers = blockingPids();
  const used = existsSync(claim) || readdirSync(traceRoot).length > 0;
  if (mode === '--check') {
    console.log(
      JSON.stringify({
        readyToLaunch: !used && blockers.length === 0,
        launched: false,
        blockingPids: blockers,
        alreadyUsed: used,
      }),
    );
  } else {
    if (used)
      throw new Error('This one-use launch directory has already been used; prepare a fresh one');
    if (mode === '--wait-and-launch') {
      // Explicitly authorized helper: wait for the user to quit normally, never
      // terminate the original application or its executors. Survives our parent.
      mkdirSync(join(root, 'wait-claim'), { mode: 0o700 });
      const deadline = Date.now() + 600_000;
      const saveWait = (phase) =>
        writeFileSync(
          join(root, 'waiting.json'),
          JSON.stringify({ phase, pid: process.pid, deadline, blockingPids: blockers }) + '\n',
          { mode: 0o600 },
        );
      saveWait('waiting-for-normal-quit');
      console.log(JSON.stringify({ waitingForNormalQuit: true, pid: process.pid, deadline }));
      while (blockers.length && Date.now() < deadline) {
        await delay(1000);
        blockers = blockingPids();
      }
      if (blockers.length) {
        saveWait('timed-out-without-launch');
        throw new Error('Timed out waiting for normal quit; nothing was stopped or launched');
      }
      directory(root);
      directory(traceRoot);
      if (
        value.applicationSha256 !== fingerprint(executable) ||
        value.executorSha256 !== fingerprint(executor)
      ) {
        saveWait('installation-changed-without-launch');
        throw new Error('Installation changed while waiting; nothing was launched');
      }
      saveWait('original-processes-exited');
    }
    if (blockers.length)
      throw new Error(
        'The original app or its recorded executor is still running. Quit normally first; nothing was stopped or launched.',
      );
    if (existsSync(claim) || readdirSync(traceRoot).length > 0)
      throw new Error('This one-use launch directory has already been used; prepare a fresh one');
    mkdirSync(claim, { mode: 0o700 }); // Atomic claim; do not replay an uncertain launch.
    if (blockingPids().length)
      throw new Error('The original app started concurrently; no launch was requested');
    // No -n: LaunchServices reuses any original instance that races this check.
    // Its umask is not assumed. The 0700 root protects all nested native files.
    execFileSync('/usr/bin/open', ['-a', app, '--env', 'CODEX_ROLLOUT_TRACE_ROOT=' + traceRoot], {
      stdio: 'ignore',
    });
    writeFileSync(
      join(root, 'launch-receipt.json'),
      JSON.stringify(
        {
          launchRequestedAt: new Date().toISOString(),
          traceRoot,
          mechanism: 'LaunchServices',
          originalRuntimeVerified: false,
          sourceBindingVerified: false,
        },
        null,
        2,
      ) + '\n',
      { mode: 0o600, flag: 'wx' },
    );
    console.log(
      JSON.stringify({
        launchRequested: true,
        originalRuntimeVerified: false,
        sourceBindingVerified: false,
      }),
    );
  }
}
