import { spawn } from 'node:child_process';
import { readFileSync, lstatSync, existsSync } from 'node:fs';
import { StringDecoder } from 'node:string_decoder';
import { createDesktopNativeSampleBridge } from '../packages/codex-adapter/dist/desktop-native-sample-bridge.js';
import {
  assertPrivateFile,
  closeWindowsPrivateStateBackend,
} from '../packages/codex-adapter/dist/desktop-platform.js';
import { resolveNativeSuggestionExecutable } from '../packages/codex-adapter/dist/desktop-native-sample-platform.js';

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
let ending = false;
const finish = () => {
  if (ending) return;
  ending = true;
  bridge?.close();
  child.kill('SIGTERM');
};
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, finish);
child.once('error', () => {
  bridge?.close();
  closeWindowsPrivateStateBackend();
  process.exit(1);
});
child.once('close', (code) => {
  bridge?.close();
  closeWindowsPrivateStateBackend();
  process.exitCode = code ?? 1;
  process.stdin.pause();
});
if (observing) {
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
process.stdin.pipe(child.stdin);
child.stdout.pipe(process.stdout);
child.stdin.on('error', finish);
child.stdout.on('error', finish);
process.stdout.on('error', finish);
