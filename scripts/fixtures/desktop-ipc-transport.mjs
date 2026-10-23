import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { chmod } from 'node:fs/promises';
import { registerHooks } from 'node:module';
import { connect } from 'node:net';

const windows = process.platform === 'win32';
const endpoints = new Set();
const identities = new WeakMap();
const prepared = new WeakSet();
const ipcUrl = new URL('../../packages/codex-adapter/dist/desktop-ipc.js', import.meta.url).href;
const platformUrl = new URL(
  '../../packages/codex-adapter/dist/desktop-platform.js',
  import.meta.url,
).href;

// Import this helper before dynamically importing the adapter. Only DesktopIpc's
// native platform boundary is synthetic; its connect/framing/disconnect and all
// owner, window and command logic remain the production implementation.
// Other adapter modules still use the real platform helpers (including DACLs).
if (windows) {
  const shim = `export * from ${JSON.stringify(platformUrl)};
export {
  fixtureWindowsPipe as windowsDesktopPipe,
  loadFixtureWindowsDesktop as loadWindowsDesktop,
  openFixtureWindowsPipe as openWindowsDesktopPipe
} from ${JSON.stringify(import.meta.url)};`;
  const shimUrl = `data:text/javascript,${encodeURIComponent(shim)}`;
  registerHooks({
    resolve(specifier, context, next) {
      if (context.parentURL === ipcUrl && specifier === './desktop-platform.js')
        return { url: shimUrl, shortCircuit: true };
      return next(specifier, context);
    },
  });
}

export let fixtureWindowsPipe = '';

export function desktopFixtureEndpoint(unixPath) {
  const address = windows ? `\\\\.\\pipe\\agent-hub-test-${randomUUID()}` : unixPath;
  endpoints.add(address);
  return address;
}

function requireFixture(address) {
  assert(endpoints.has(address), 'Only this fixture process may register a test endpoint');
  if (windows) assert.match(address, /^\\\\\.\\pipe\\agent-hub-test-[0-9a-f-]{36}$/);
}

export async function listenDesktopFixture(server, address) {
  requireFixture(address);
  await new Promise((resolve, reject) => {
    const fail = (error) => reject(error);
    server.once('error', fail);
    server.listen(address, () => {
      server.off('error', fail);
      resolve();
    });
  });
  // Windows pipes live in the kernel, not under the temporary directory. Closing
  // the server releases them; chmod/unlink must only target a Unix socket file.
  if (!windows) await chmod(address, 0o600);
}

function identity(address) {
  requireFixture(address);
  const value = Object.freeze({ pid: process.pid });
  identities.set(value, address);
  return value;
}

export function loadFixtureWindowsDesktop(config) {
  return { archive: { version: '26.928.20755' }, identity: identity(config) };
}

export async function openFixtureWindowsPipe(value) {
  const address = identities.get(value);
  requireFixture(address);
  const socket = connect(address);
  await new Promise((resolve, reject) => {
    socket.once('connect', resolve);
    socket.once('error', reject);
  });
  return socket;
}

export function prepareDesktopFixtureIpc(ipc) {
  requireFixture(ipc.path);
  if (windows && !prepared.has(ipc)) {
    prepared.add(ipc);
    ipc.windowsIdentity = identity(ipc.path);
    const original = ipc.connect;
    ipc.connect = function () {
      // The production equality guard still requires this exact registered
      // endpoint and the synthetic identity. Never allow the real codex pipe.
      requireFixture(this.path);
      fixtureWindowsPipe = this.path;
      return original.call(this);
    };
  }
  return ipc;
}

export function prepareDesktopFixtureRuntime(runtime) {
  prepareDesktopFixtureIpc(runtime.ipc);
  if (windows) runtime.options.windowsDesktopConfig = runtime.ipc.path;
}
