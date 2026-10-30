import { spawn } from 'node:child_process';
import { readFileSync, lstatSync, existsSync } from 'node:fs';
import { StringDecoder } from 'node:string_decoder';
import { Worker } from 'node:worker_threads';
import { createDesktopNativeSampleBridge } from '../packages/codex-adapter/dist/desktop-native-sample-bridge.js';
import {
  assertPrivateFile,
  closeWindowsPrivateStateBackend,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { resolveNativeSuggestionExecutable } from '../packages/codex-adapter/dist/desktop-native-sample-platform.js';
import {
  forwardOriginalDesktopWithNativeSampler,
  nativeSamplerProtocol,
} from '../packages/codex-adapter/dist/desktop-native-sample-forwarding.js';
import { getWindowsProcessGate } from '../packages/codex-adapter/dist/windows-process.js';
import {
  privateStateExecArgv,
  privateStateNodeOptions,
  registerWindowsTraceTransport,
} from '../packages/codex-adapter/dist/desktop-private-state-native.js';

// Installed private executable override only. Never reachable as a Host command.
const path = process.env.AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG;
if (!path) throw new Error('Private original desktop launcher config required');
assertPrivateFile(path);
if (lstatSync(path).size > 16384) throw new Error('Native launcher config too large');
const config = JSON.parse(readFileSync(path, 'utf8'));
let executable = config.executable;
const args = process.argv.slice(2);
if (process.platform === 'win32' && (args.includes('app-server') || !existsSync(executable))) {
  // Store updates relocate the signed payload. Never execute a stale path or
  // replace the original desktop's execution service with another instance.
  executable = resolveNativeSuggestionExecutable(path, executable);
}
const child = spawn(executable, args, {
  stdio: ['pipe', 'pipe', 'inherit'],
  windowsHide: true,
  env: process.env,
});
const observing =
  args.includes('app-server') &&
  !args.includes('--remote') &&
  !args.includes('daemon') &&
  args.includes('--analytics-default-enabled');
let bridge;
let sampler;
let ending = false;
const closeObservation = () => {
  bridge?.close();
  if (sampler)
    void sampler.close().then((confirmed) => {
      if (!confirmed) process.stderr.write('native_sampler_retirement_unknown\n');
    });
};
const finish = () => {
  if (ending) return;
  ending = true;
  closeObservation();
  child.kill('SIGTERM');
};
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, finish);
child.once('error', () => {
  closeObservation();
  closeWindowsPrivateStateBackend();
  process.exitCode = 1;
  process.stdin.pause();
});
child.once('close', (code) => {
  closeObservation();
  closeWindowsPrivateStateBackend();
  process.exitCode = code ?? 1;
  process.stdin.pause();
});
if (observing && process.platform === 'win32') {
  const startupDeadline =
    process.hrtime.bigint() + BigInt(nativeSamplerProtocol.startupMs) * 1_000_000n;
  sampler = forwardOriginalDesktopWithNativeSampler(
    {
      input: process.stdin,
      childInput: child.stdin,
      childOutput: child.stdout,
      output: process.stdout,
    },
    () => {
      registerWindowsTraceTransport();
      const entry = new URL(
        '../packages/codex-adapter/dist/desktop-native-sample-worker.js',
        import.meta.url,
      );
      const shared = new SharedArrayBuffer(4);
      const worker = new Worker(entry, {
        workerData: {
          kind: 'windows-native-sample',
          entryURL: entry.href,
          configPath: path,
          control: shared,
          startupDeadline,
          windowsProcessGate: getWindowsProcessGate(),
        },
        execArgv: privateStateExecArgv(process.execArgv),
        env: {
          ...process.env,
          NODE_OPTIONS: privateStateNodeOptions(process.env.NODE_OPTIONS ?? ''),
        },
      });
      return { worker, shared };
    },
  );
} else {
  // Establish the original byte streams before any optional observation.
  process.stdin.pipe(child.stdin);
  child.stdout.pipe(process.stdout);
}
if (observing && process.platform !== 'win32') {
  bridge = createDesktopNativeSampleBridge(path);
  // Forward original bytes, including framing, independently of observation.
  // An observation failure never changes a request or stops desktop execution.
  const maximum = 32 * 1024 * 1024;
  const observe = (stream, accept) => {
    const decoder = new StringDecoder('utf8');
    let buffer = '';
    let enabled = true;
    stream.on('data', (chunk) => {
      if (!enabled) return;
      if (Buffer.byteLength(buffer) + chunk.length > maximum) {
        enabled = false;
        buffer = '';
        bridge?.close();
        return;
      }
      buffer += decoder.write(chunk);
      let n;
      while ((n = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, n);
        buffer = buffer.slice(n + 1);
        if (line.trim()) accept(line.endsWith('\r') ? line.slice(0, -1) : line);
      }
    });
  };
  observe(process.stdin, (line) => bridge?.observeOutbound(line));
  observe(child.stdout, (line) => bridge?.observeInbound(line));
}
child.stdin.on('error', finish);
child.stdout.on('error', finish);
process.stdout.on('error', finish);
