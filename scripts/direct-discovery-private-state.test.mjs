import assert from 'node:assert/strict';
import test from 'node:test';
import { randomUUID } from 'node:crypto';
import { readFile, rm } from 'node:fs/promises';
import { realpathSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { get } from 'node:https';
import { once } from 'node:events';
import { setTimeout as delay } from 'node:timers/promises';
import { startDirectDiscovery } from '../apps/host-agent/dist/direct-discovery.js';
import { DirectBonjourPublisher } from '../apps/host-agent/dist/direct-bonjour.js';
import {
  assertPrivateFile,
  closeWindowsPrivateStateBackend,
  makePrivateDirectorySync,
} from '../packages/codex-adapter/dist/desktop-platform.js';

async function until(read, predicate) {
  const deadline = Date.now() + (process.platform === 'win32' ? 35_000 : 5000);
  for (;;) {
    let value;
    try {
      value = await read();
    } catch {
      /* The private atomic publication may not exist yet. */
    }
    if (predicate(value)) return value;
    assert(Date.now() < deadline, 'Discovery private state was not published');
    await delay(20);
  }
}

test('Bonjour generation state uses the private writer while pinned HTTPS stays independent', async () => {
  const directory = join(realpathSync(tmpdir()), 'discovery-private-' + randomUUID());
  makePrivateDirectorySync(directory);
  const previous = Object.fromEntries(
    [
      'HOST_AGENT_DISCOVERY_BONJOUR',
      'HOST_AGENT_DISCOVERY_TLS_CERT',
      'HOST_AGENT_DISCOVERY_TLS_KEY',
    ].map((key) => [key, process.env[key]]),
  );
  const start = DirectBonjourPublisher.prototype.start;
  let discovery;
  try {
    // Only this fixture's DNS transport is replaced. Its real publisher state,
    // Host callback, private file writer, TLS listener and cleanup still run.
    DirectBonjourPublisher.prototype.start = function () {
      this.report({ ...this.snapshot(), generation: 1, phase: 'announced-local' });
    };
    process.env.HOST_AGENT_DISCOVERY_BONJOUR = '1';
    delete process.env.HOST_AGENT_DISCOVERY_TLS_CERT;
    delete process.env.HOST_AGENT_DISCOVERY_TLS_KEY;
    discovery = await startDirectDiscovery({
      directory,
      name: 'Synthetic private discovery',
      token: 'fixture-only-token',
      target: () => ({
        agentId: 'codex',
        hostId: '11111111-1111-4111-8111-111111111111',
        workspaceId: 'desktop-workspace',
        runtimeId: 'desktop-runtime',
      }),
      handler: async (_request, response) => {
        response.writeHead(200);
        response.end('fixture-only');
      },
      peers: async () => [],
      port: 0,
      bind: '127.0.0.1',
    });
    const file = join(directory, 'direct-discovery/bonjour-state.json');
    const read = async () => JSON.parse(await readFile(file, 'utf8'));
    const state = await until(read, (value) => value?.phase === 'announced-local');
    assert.equal(state.generation, 1);
    assert.equal(state.localPublicationOnly, true);
    // On elevated Windows the old Node-create + protect path cannot satisfy
    // this strict User-owner check. The shared private boundary is unchanged.
    assertPrivateFile(file);
    const port = discovery.server.address().port;
    const status = await new Promise((resolve, reject) => {
      get(
        { hostname: '127.0.0.1', port, path: '/fixture-health', rejectUnauthorized: false },
        (response) => {
          response.resume();
          response.once('end', () => resolve(response.statusCode));
        },
      ).once('error', reject);
    });
    assert.equal(status, 200);
    const closed = once(discovery.server, 'close');
    discovery.close();
    await closed;
    await until(read, (value) => value?.phase === 'closed');
    assertPrivateFile(file);
  } finally {
    discovery?.close();
    DirectBonjourPublisher.prototype.start = start;
    for (const [key, value] of Object.entries(previous)) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    closeWindowsPrivateStateBackend();
    await rm(directory, { recursive: true, force: true });
  }
});
