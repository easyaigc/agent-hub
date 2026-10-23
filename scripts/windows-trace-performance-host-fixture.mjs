import fs from 'node:fs';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { RuntimeSession } from '../packages/codex-adapter/dist/runtime-session.js';
import { DesktopSuggestionObserver } from '../packages/codex-adapter/dist/desktop-suggestion-observer.js';
import { DesktopTranscription } from '../packages/codex-adapter/dist/desktop-transcription.js';
import { DesktopWorkspaceView } from '../packages/codex-adapter/dist/desktop-workspace-view.js';
import { protectWindowsStateDirectory } from '../packages/codex-adapter/dist/desktop-platform.js';
import { recoverableFixture } from './fixtures/desktop-trace.mjs';
import { startRuntimeServer } from '../apps/host-agent/dist/runtime-server.js';

// Dedicated local fixture: native files/ACLs/parser and real Host routing;
// source ownership, instance readiness and runtime history are synthetic.
const root = process.env.TRACE_PERF_FIXTURE_ROOT;
fs.mkdirSync(root, { recursive: true });
protectWindowsStateDirectory(root);
const { fixture: f } = recoverableFixture(root);
if (process.env.TRACE_PERF_NATIVE_PROBE === '1') {
  const now = Date.now();
  f.manifest.started_at_unix_ms = now;
  fs.writeFileSync(join(f.directory, 'manifest.json'), JSON.stringify(f.manifest));
  for (const event of f.events) event.wall_time_unix_ms += now;
  f.saveEvents();
}
const token = randomUUID() + randomUUID();
RuntimeSession.prototype.connect = async function () {
  Object.defineProperty(this.runtime, 'ready', { value: true });
};
RuntimeSession.prototype.desktopSuggestionSource = async () => ({
  threadId: f.sourceThreadId,
  sourceTurnId: f.sourceTurnId,
});
DesktopTranscription.prototype.readiness = async () => ({
  available: false,
  reason: 'Synthetic fixture',
  principal: null,
});
DesktopWorkspaceView.prototype.workspace = async function () {
  return {
    hostId: this.session.hostId,
    ready: true,
    capabilities: this.session.capabilities,
    projects: [],
    threads: [],
    nextProjectCursor: null,
    nextThreadCursor: null,
  };
};
if (process.env.TRACE_PERF_STAGE === 'before') {
  DesktopSuggestionObserver.prototype.start = function () {
    this.phase = 'listening';
    this.desktopStartedAtUnixMs = 1;
    this.windowsInstanceStable = () => true;
  };
} else {
  const { DesktopSuggestionWorkerClient } =
    await import('../packages/codex-adapter/dist/desktop-suggestion-worker-client.js');
  DesktopSuggestionObserver.prototype.createWindowsReader = function () {
    return new DesktopSuggestionWorkerClient(
      {
        directory: this.options.directory,
        root: this.options.root,
        traceRoot: this.options.traceRoot,
        target: this.options.target(),
      },
      pathToFileURL(join(import.meta.dirname, 'windows-trace-performance-reader-fixture.mjs')),
    );
  };
}
Object.assign(process.env, {
  CODEX_RUNTIME_MODE: 'desktop-runtime',
  HOST_AGENT_STATE_DIR: join(root, 'state'),
  HOST_AGENT_ROOT: root,
  HOST_AGENT_TOKEN: token,
  HOST_AGENT_PORT: '0',
  HOST_AGENT_BIND: '127.0.0.1',
  HOST_AGENT_DISCOVERY: '0',
  HOST_AGENT_DISCOVERY_BONJOUR: '0',
  CODEX_DESKTOP_SUGGESTION_OBSERVER: '1',
  CODEX_DESKTOP_SUGGESTION_TRACE_ROOT: root,
});
for (const key of [
  'CODEX_DESKTOP_DOM_BRIDGE_CONFIG',
  'CODEX_DESKTOP_WINDOWS_CONFIG',
  'CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG',
  'CODEX_DESKTOP_CONTROLLER_CONFIG',
  'HOST_AGENT_TLS_CERT',
  'HOST_AGENT_TLS_KEY',
  'HOST_NOTIFICATION_RELAY_URL',
  'HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE',
])
  delete process.env[key];
const service = await startRuntimeServer();
process.send({
  kind: 'ready',
  url: `http://127.0.0.1:${service.server.address().port}`,
  target: service.session.readingTarget(),
  hostId: service.session.hostId,
  threadId: f.sourceThreadId,
  sourceTurnId: f.sourceTurnId,
  token,
});
process.on('message', async (message) => {
  if (message.kind === 'close') {
    await service.close();
    process.disconnect();
  }
});
