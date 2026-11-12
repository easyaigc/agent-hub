import assert from 'node:assert/strict';
import { after, test } from 'node:test';
import { mkdtempSync, readFileSync, existsSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { randomUUID } from 'node:crypto';
import { installWindowsPrivateStateFixture } from './fixtures/windows-private-state-boundary.mjs';

const root = mkdtempSync(join(tmpdir(), 'desktop-bridge-defaults-'));
const permission = installWindowsPrivateStateFixture(
  root,
  new URL('../packages/codex-adapter/dist/desktop-platform.js', import.meta.url),
);
const { defaultDesktopPreparedBridge } =
  await import('../packages/codex-adapter/dist/desktop-bridge-defaults.js');
const { RuntimeSession } = await import('../packages/codex-adapter/dist/runtime-session.js');
const { DesktopPreparedBridge } =
  await import('../packages/codex-adapter/dist/desktop-prepared-bridge.js');
const originalPlatform = Object.getOwnPropertyDescriptor(process, 'platform');
after(() => {
  Object.defineProperty(process, 'platform', originalPlatform);
  permission?.restore();
  rmSync(root, { recursive: true, force: true });
});
const options = () => ({
  directory: mkdtempSync(join(root, 'case-')),
  mode: 'desktop-runtime',
  autoConfigureDesktopBridge: true,
});

test('Windows production startup provisions a private Host-bound bridge exactly once', () => {
  Object.defineProperty(process, 'platform', { value: 'win32' });
  const input = options();
  const hostId = randomUUID();
  let persisted = 0;
  const path = join(input.directory, 'prepared-bridge.json');
  const file = defaultDesktopPreparedBridge(input, hostId, () => {
    assert(!existsSync(path));
    persisted++;
  });
  assert.equal(file, join(input.directory, 'prepared-bridge.json'));
  assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')), { hostId });
  const bytes = readFileSync(file);
  assert.equal(
    defaultDesktopPreparedBridge(input, hostId, () => assert.fail('Identity already persisted')),
    file,
  );
  assert.equal(persisted, 1);
  assert.deepEqual(readFileSync(file), bytes);
});

test('explicit driver choices and non-production sessions do not create a fallback', () => {
  Object.defineProperty(process, 'platform', { value: 'win32' });
  const custom = { ...options(), desktopPreparedBridge: join(root, 'explicit.json') };
  assert.equal(defaultDesktopPreparedBridge(custom, randomUUID()), custom.desktopPreparedBridge);
  for (const override of [
    { autoConfigureDesktopBridge: false },
    { mode: 'managed-runtime' },
    { mode: 'shared-runtime' },
    { desktopController: join(root, 'controller.json') },
    { desktopCreation: { helper: 'custom', profile: 'custom', verification: 'custom' } },
  ]) {
    const input = { ...options(), ...override };
    assert.equal(defaultDesktopPreparedBridge(input, randomUUID()), undefined);
    assert.equal(existsSync(join(input.directory, 'prepared-bridge.json')), false);
  }
  Object.defineProperty(process, 'platform', { value: 'darwin' });
  const mac = options();
  assert.equal(defaultDesktopPreparedBridge(mac, randomUUID()), undefined);
  assert.equal(existsSync(join(mac.directory, 'prepared-bridge.json')), false);
});

test('existing validation policy, mismatched identity and malformed files are never rewritten', () => {
  Object.defineProperty(process, 'platform', { value: 'win32' });
  for (const content of [
    JSON.stringify({ hostId: randomUUID(), validationOnly: true, verification: 'original-proof' }),
    '{ malformed configuration',
  ]) {
    const input = options();
    const file = join(input.directory, 'prepared-bridge.json');
    writeFileSync(file, content, 'utf8');
    assert.equal(defaultDesktopPreparedBridge(input, randomUUID()), file);
    assert.equal(readFileSync(file, 'utf8'), content);
  }
});

test('default bridge keeps validation policy and rejects a wrong Host even with a live desktop', async () => {
  Object.defineProperty(process, 'platform', { value: 'win32' });
  const hostId = randomUUID();
  for (const config of [
    { hostId, validationOnly: true, verification: 'original-proof' },
    { hostId: randomUUID() },
  ]) {
    const input = options();
    const file = join(input.directory, 'prepared-bridge.json');
    writeFileSync(file, JSON.stringify(config), 'utf8');
    assert.equal(defaultDesktopPreparedBridge(input, hostId), file);
    const bridge = new DesktopPreparedBridge(
      file,
      hostId,
      { ready: true, desktopAvailable: async () => true },
      join(input.directory, 'bridge'),
      join(root, 'never-execute'),
      root,
      async () => assert.fail('Availability checks cannot submit input'),
    );
    try {
      await bridge.refresh();
      assert.equal(bridge.openAvailable, config.hostId === hostId);
      assert.equal(bridge.openValidationOnly, config.validationOnly === true);
      assert.equal(readFileSync(file, 'utf8'), JSON.stringify(config));
    } finally {
      bridge.close();
    }
  }
});

test(
  'session restart keeps its original identity and bridge, and readiness requires live desktop',
  { skip: process.platform !== 'win32' },
  async () => {
    Object.defineProperty(process, 'platform', originalPlatform);
    const input = {
      ...options(),
      root,
      name: 'Cold-thread recovery fixture',
      executable: join(root, 'never-execute'),
    };
    const first = new RuntimeSession(input);
    const hostId = first.hostId;
    const file = join(input.directory, 'prepared-bridge.json');
    assert.equal(
      JSON.parse(readFileSync(join(input.directory, 'runtime.json'), 'utf8')).hostId,
      hostId,
    );
    assert.equal(JSON.parse(readFileSync(file, 'utf8')).hostId, hostId);
    assert(first.desktopCreationDriver instanceof DesktopPreparedBridge);
    await first.refreshDesktopCreationAvailability();
    assert.equal(first.capabilities.includes('desktop:open'), false);
    first.close();
    const restarted = new RuntimeSession(input);
    try {
      assert.equal(restarted.hostId, hostId);
      assert.equal(JSON.parse(readFileSync(file, 'utf8')).hostId, hostId);
      assert(restarted.desktopCreationDriver instanceof DesktopPreparedBridge);
      assert.equal(restarted.capabilities.includes('desktop:open'), false);
    } finally {
      restarted.close();
    }
  },
);
