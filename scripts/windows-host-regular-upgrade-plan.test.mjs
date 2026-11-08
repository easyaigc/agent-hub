import { test } from 'node:test';
import assert from 'node:assert/strict';
import { planRegularUpgrade } from './windows-host-regular-upgrade-plan.mjs';

const profile = 'C:\\Users\\Example',
  hostId = '11111111-1111-4111-8111-111111111111';
const op = '22222222-2222-4222-8222-222222222222',
  oldOp = '33333333-3333-4333-8333-333333333333';
const anchor = `${profile}\\.config\\agent-hub\\windows-host-v1`;
const configPath = `${anchor}\\instances\\${hostId}\\windows-host.json`;
const args = () => ({
  operationId: op,
  profile,
  expectedCurrentSha256: 'a'.repeat(64),
  desiredRuntimeSha256: 'b'.repeat(64),
  desiredSourceCommit: 'c'.repeat(40),
  launcherSha256: 'd'.repeat(64),
  launcherPath: `${profile}\\.config\\agent-hub\\windows-native-v1\\native-helpers\\${'e'.repeat(64)}\\host-launcher.exe`,
  current: {
    format: 'windows-host-regular-installation-v1',
    hostId,
    operationId: oldOp,
    runtimeSha256: 'f'.repeat(64),
    sourceCommit: '1'.repeat(40),
    program: `${anchor}\\programs\\${'f'.repeat(64)}`,
    state: 'D:\\Host\\state',
    configuration: configPath,
  },
  configuration: {
    environment: {
      CODEX_RUNTIME_MODE: 'desktop-runtime',
      HOST_AGENT_STATE_DIR: 'D:\\Host\\state',
      HOST_AGENT_PORT: '8794',
      HOST_AGENT_NAME: 'Test host',
      HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE: 'D:\\Host\\token',
    },
  },
});

test('preserves the original target and strips only the retired Responses setting', () => {
  const input = args();
  input.configuration.environment.CODEX_DESKTOP_RESPONSES_CONFIG = `${anchor}\\instances\\${hostId}\\response.json`;
  const original = structuredClone(input);
  const result = planRegularUpgrade(input);
  assert.deepEqual(result.oldRoute, original.current);
  assert.equal(result.newRoute.hostId, hostId);
  assert.equal(result.newRoute.state, original.current.state);
  assert.equal(result.newRoute.configuration, configPath);
  assert.equal(result.newRoute.operationId, op);
  assert.equal(result.newRoute.program, `${anchor}\\programs\\${'b'.repeat(64)}`);
  assert.deepEqual(result.oldConfiguration, original.configuration);
  assert.deepEqual(result.newConfiguration, args().configuration);
  assert.equal(result.newAction.configuration, configPath);
  assert.equal(result.newAction.launcher, input.launcherPath);
  assert.equal(
    result.newAction.arguments,
    `"${result.newAction.node}" "${result.newAction.worker}" "${configPath}"`,
  );
  assert.deepEqual(input, original);
});

test('rejects escapes, changed identity, untrusted launcher and invalid digests', () => {
  const cases = [
    (v) => (v.profile = 'C:\\Users\\Example\\..\\Other'),
    (v) => (v.current.program = `${anchor}\\programs\\..\\escape`),
    (v) => (v.current.configuration = `${anchor}\\instances\\${'9'.repeat(8)}\\windows-host.json`),
    (v) => (v.configuration.environment.HOST_AGENT_STATE_DIR = 'D:\\Other'),
    (v) => (v.configuration.environment.HOST_AGENT_PORT = '65536'),
    (v) => (v.launcherPath = 'D:\\untrusted\\host-launcher.exe'),
    (v) => (v.expectedCurrentSha256 = 'bad'),
    (v) => (v.desiredRuntimeSha256 = v.current.runtimeSha256),
    (v) => (v.operationId = oldOp),
    (v) => (v.configuration.environment.HOST_AGENT_TOKEN = 'secret'),
  ];
  for (const mutate of cases) {
    const v = args();
    mutate(v);
    assert.throws(() => planRegularUpgrade(v), /HOST_UPGRADE_PLAN_REJECTED/);
  }
});

test('leaves an installation without the retired setting unchanged', () => {
  const v = args();
  assert.deepEqual(planRegularUpgrade(v).newConfiguration, v.configuration);
});
