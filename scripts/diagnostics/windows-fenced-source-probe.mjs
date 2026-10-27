import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdir, readFile, readdir, realpath, writeFile } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as delay } from 'node:timers/promises';

const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
export function parseProbePlan(value) {
  const fields = [
    'format',
    'operationId',
    'hostId',
    'threadId',
    'sourceTurnId',
    'program',
    'hostConfig',
    'runtimeState',
    'output',
    'adapterFingerprint',
    'protocolFingerprint',
  ];
  assert(value && typeof value === 'object' && !Array.isArray(value));
  assert.equal(Object.keys(value).length, fields.length);
  assert(Object.keys(value).every((key) => fields.includes(key)));
  assert.equal(value.format, 'windows-fenced-source-probe-v1');
  for (const key of ['operationId', 'hostId', 'threadId', 'sourceTurnId'])
    assert(uuid.test(value[key]));
  assert.notEqual(value.threadId, value.sourceTurnId);
  for (const key of ['program', 'hostConfig', 'runtimeState', 'output'])
    assert(typeof value[key] === 'string' && isAbsolute(value[key]));
  for (const key of ['adapterFingerprint', 'protocolFingerprint'])
    assert(/^[a-f0-9]{64}$/.test(value[key]));
  return structuredClone(value);
}
export async function compiledFingerprint(directory) {
  const files = {};
  for (const name of (await readdir(directory)).filter((name) => name.endsWith('.js')).sort())
    files[name] = digest(await readFile(join(directory, name)));
  assert(Object.keys(files).length, 'Empty compiled package');
  return digest(JSON.stringify(files));
}
export async function runProbe(planFile) {
  assert.equal(process.platform, 'win32', 'Native Windows only');
  assert.equal(process.versions.node.split('.')[0], '24', 'Use the installed Node 24');
  assert.notEqual(
    process.env.AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS,
    '1',
    'Normal diagnostic environment required',
  );
  const plan = parseProbePlan(JSON.parse(await readFile(planFile, 'utf8')));
  const adapter = join(plan.program, 'packages/codex-adapter/dist');
  for (const [directory, expected] of [
    [adapter, plan.adapterFingerprint],
    [join(plan.program, 'node_modules/@codex-manage/codex-adapter/dist'), plan.adapterFingerprint],
    [join(plan.program, 'packages/protocol/dist'), plan.protocolFingerprint],
    [join(plan.program, 'node_modules/@codex-manage/protocol/dist'), plan.protocolFingerprint],
  ])
    assert.equal(
      await compiledFingerprint(directory),
      expected,
      'Verified compiled package required',
    );
  const platform = await import(pathToFileURL(join(adapter, 'desktop-platform.js')));
  for (const file of [planFile, plan.hostConfig, plan.runtimeState])
    platform.assertPrivateFile(file);
  const runtime = JSON.parse(await readFile(plan.runtimeState, 'utf8'));
  assert.equal(runtime.hostId, plan.hostId, 'Exact installed Host identity required');
  const config = JSON.parse(await readFile(plan.hostConfig, 'utf8'));
  const stateDirectory = config.environment?.HOST_AGENT_STATE_DIR;
  assert(typeof stateDirectory === 'string' && isAbsolute(stateDirectory));
  assert.equal(
    await realpath(plan.runtimeState),
    await realpath(join(stateDirectory, 'runtime.json')),
    'The service configuration must own this Host runtime state',
  );
  const traceRoot = config.environment?.CODEX_DESKTOP_SUGGESTION_TRACE_ROOT;
  assert(
    typeof traceRoot === 'string' && isAbsolute(traceRoot),
    'Actual configured trace root required',
  );
  const windowsConfig = config.environment?.CODEX_DESKTOP_WINDOWS_CONFIG;
  assert(typeof windowsConfig === 'string' && isAbsolute(windowsConfig));
  platform.assertPrivateFile(windowsConfig);
  process.env.CODEX_DESKTOP_WINDOWS_CONFIG = windowsConfig;
  await mkdir(plan.output, { mode: 0o700 });
  platform.protectWindowsStateDirectory(plan.output);
  const { DesktopSuggestionWorkerClient } = await import(
    pathToFileURL(join(adapter, 'desktop-suggestion-worker-client.js'))
  );
  const target = {
    agentId: 'codex',
    hostId: plan.hostId,
    workspaceId: plan.hostId,
    runtimeId: 'desktop-runtime',
  };
  const client = new DesktopSuggestionWorkerClient({
    target,
    traceRoot,
    directory: join(plan.output, 'reader-state'),
  });
  const owned = new Map();
  const sample = () => {
    const worker = client.worker;
    if (worker && !owned.has(worker)) {
      const info = { threadId: worker.threadId, exited: false };
      owned.set(worker, info);
      worker.once('exit', (code) => Object.assign(info, { exited: true, exitCode: code }));
    }
    return {
      available: client.available,
      sampleAgeMs: client.availableAt > 0 ? performance.now() - client.availableAt : null,
      generation: client.generation,
      readerPresent: !!client.worker,
      retiring: client.retiring.size,
    };
  };
  const result = {
    format: 'windows-fenced-source-probe-result-v1',
    operationId: plan.operationId,
    target,
    threadId: plan.threadId,
    sourceTurnId: plan.sourceTurnId,
    startedAt: new Date().toISOString(),
    actualNodePid: process.pid,
    scope:
      'Independent one-source Native client only; no Host/owner/phone acceptance, model input or source creation',
    startupLimitMs: 180000,
    callerBudgetMs: 8000,
    prepareCalls: 0,
    commitCalls: 0,
    readerStates: [],
  };
  let receipt;
  let timer;
  try {
    client.start();
    const startup = performance.now();
    while (!client.available && performance.now() - startup < result.startupLimitMs) {
      result.readerStates.push(sample());
      await delay(250);
    }
    result.initialAvailability = sample();
    assert(client.available, 'Native reader did not become available');
    const started = performance.now();
    const abort = new AbortController();
    timer = setTimeout(() => abort.abort(), result.callerBudgetMs);
    result.prepareCalls++;
    receipt = await client.prepare(
      plan.threadId,
      plan.sourceTurnId,
      started + result.callerBudgetMs,
      abort.signal,
    );
    result.prepareMs = performance.now() - started;
    result.commitCalls++;
    const committed = performance.now();
    const value = await receipt.commit();
    result.commitMs = performance.now() - committed;
    result.readMs = performance.now() - started;
    assert(result.readMs < result.callerBudgetMs);
    assert.equal(value.schemaVersion, 1);
    assert.equal(value.readOnly, true);
    assert.equal(value.source, 'native-trace');
    assert.equal(value.threadId, plan.threadId);
    assert.equal(value.sourceTurnId, plan.sourceTurnId);
    for (const [key, expected] of Object.entries(target))
      assert.equal(value.target?.[key], expected);
    assert(value.suggestion === null || value.suggestion?.validity === 'native-version');
    const text = value.suggestion?.text;
    if (text !== undefined) {
      assert.equal(typeof text, 'string');
      assert.equal(digest(text), value.suggestion.contentSha256);
    }
    result.native = {
      isNull: value.suggestion === null,
      textLength: text?.length ?? 0,
      textSha256: text === undefined ? null : digest(text),
    };
    result.readPassed = true;
  } catch (error) {
    result.readPassed = false;
    result.error = {
      name: error?.name,
      status: error?.status ?? null,
      code: typeof error?.code === 'string' ? error.code : null,
    };
  } finally {
    clearTimeout(timer);
    receipt?.dispose();
    sample();
    client.close();
    const closing = performance.now();
    while (
      (client.worker || client.retiring.size || [...owned.values()].some((item) => !item.exited)) &&
      performance.now() - closing < 20000
    )
      await delay(100);
    result.finalState = sample();
    result.ownedReaders = [...owned.values()];
    result.readerExitConfirmed =
      !client.worker &&
      client.retiring.size === 0 &&
      result.ownedReaders.every((item) => item.exited);
    result.nativeProcessClosure =
      'Operator must separately verify this probe PID and Native descendants/stdio exited';
    result.endedAt = new Date().toISOString();
    const output = join(plan.output, 'probe-result.private.json');
    await writeFile(output, JSON.stringify(result, null, 2), { mode: 0o600, flag: 'wx' });
    platform.protectWindowsStateFile(output);
  }
  return result;
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  assert.equal(process.argv.length, 3, 'Expected one private plan JSON path');
  const result = await runProbe(resolve(process.argv[2]));
  console.log(
    JSON.stringify({
      readPassed: result.readPassed,
      readerExitConfirmed: result.readerExitConfirmed,
      prepareCalls: result.prepareCalls,
      commitCalls: result.commitCalls,
      readMs: result.readMs,
      native: result.native,
      error: result.error,
    }),
  );
  process.exitCode = result.readPassed && result.readerExitConfirmed ? 0 : 1;
}
