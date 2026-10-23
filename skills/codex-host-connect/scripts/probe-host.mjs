import { parseArgs } from 'node:util';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

class ProbeError extends Error {
  constructor(code) {
    super(code);
    this.code = code;
  }
}

const requireValue = (condition, code) => {
  if (!condition) throw new ProbeError(code);
};
const object = (value) => value && typeof value === 'object' && !Array.isArray(value);
const uuid = (value) => typeof value === 'string' && /^[a-f0-9-]{36}$/i.test(value);

async function privateFile(path) {
  const stat = await lstat(path);
  requireValue(
    stat.isFile() &&
      !stat.isSymbolicLink() &&
      (process.platform === 'win32' || !(stat.mode & 0o077)) &&
      (process.getuid === undefined || stat.uid === process.getuid()),
    'UNSAFE_PRIVATE_FILE',
  );
  if (process.platform === 'win32') {
    const helper = [
      new URL(
        '../assets/codex-manage/packages/codex-adapter/windows/private-state.ps1',
        import.meta.url,
      ),
      new URL('../../../packages/codex-adapter/windows/private-state.ps1', import.meta.url),
    ]
      .map((url) => fileURLToPath(url))
      .find(existsSync);
    requireValue(helper, 'WINDOWS_PRIVATE_STATE_HELPER_REQUIRED');
    try {
      execFileSync(
        join(
          process.env.SystemRoot ?? 'C:\\Windows',
          'System32/WindowsPowerShell/v1.0/powershell.exe',
        ),
        ['-NoProfile', '-NonInteractive', '-File', helper, '-Action', 'check', '-Path', path],
        { stdio: 'pipe', timeout: 15_000 },
      );
    } catch {
      throw new ProbeError('UNSAFE_PRIVATE_FILE');
    }
  }
  return readFile(path, 'utf8');
}

/** Read only our HTTP contracts. Never reads or modifies Codex history or wire state. */
export async function probeHost({ endpoint, stateDirectory }) {
  let base;
  try {
    base = new URL(endpoint);
  } catch {
    throw new ProbeError('INVALID_ENDPOINT');
  }
  requireValue(
    !base.username &&
      !base.password &&
      !base.search &&
      !base.hash &&
      ['/', ''].includes(base.pathname) &&
      (base.protocol === 'https:' ||
        (base.protocol === 'http:' && ['127.0.0.1', 'localhost', '[::1]'].includes(base.hostname))),
    'HTTPS_REQUIRED_EXCEPT_LOOPBACK',
  );
  const token = (await privateFile(join(stateDirectory, 'access-token'))).trim();
  requireValue(token.length >= 32 && !/\s/.test(token), 'INVALID_TOKEN_FILE');
  const state = JSON.parse(await privateFile(join(stateDirectory, 'runtime.json')));
  requireValue(object(state) && uuid(state.hostId), 'INVALID_LOCAL_HOST_ID');
  const request = (path, authenticated) =>
    fetch(new URL(path, base), {
      method: 'GET',
      redirect: 'error',
      headers: authenticated ? { Authorization: 'Bearer ' + token } : {},
      signal: AbortSignal.timeout(15_000),
    });
  const unauthenticated = await request('/healthz', false);
  await unauthenticated.body?.cancel();
  requireValue(unauthenticated.status === 401, 'AUTHENTICATION_NOT_ENFORCED');
  const healthResponse = await request('/healthz', true);
  requireValue(healthResponse.status === 200, 'AUTHENTICATED_HEALTH_FAILED');
  const health = await healthResponse.json();
  requireValue(
    object(health) &&
      health.service === 'host-agent' &&
      health.status === 'ok' &&
      health.protocolVersion === '0.1',
    'INCOMPATIBLE_HEALTH',
  );
  const workspaceResponse = await request('/v1/workspace', true);
  requireValue(workspaceResponse.status === 200, 'WORKSPACE_FAILED');
  const workspace = await workspaceResponse.json();
  requireValue(object(workspace) && workspace.hostId === state.hostId, 'WRONG_HOST');
  requireValue(workspace.connectionMode === 'desktop-runtime', 'ORIGINAL_DESKTOP_REQUIRED');
  requireValue(
    Array.isArray(workspace.projects) &&
      Array.isArray(workspace.threads) &&
      Array.isArray(workspace.capabilities) &&
      workspace.capabilities.every((value) => typeof value === 'string'),
    'INCOMPATIBLE_WORKSPACE',
  );
  return {
    status: 'passed',
    checkedAt: new Date().toISOString(),
    endpoint: base.origin,
    hostId: workspace.hostId,
    connectionMode: workspace.connectionMode,
    https: base.protocol === 'https:',
    authenticationEnforced: true,
    hostIdentityMatched: true,
    indexedProjects: workspace.projects.length,
    indexedThreads: workspace.threads.length,
    advertisedCapabilities: workspace.capabilities,
    acceptance: {
      originalOwnerControls: 'not-tested',
      desktopCreationAndOpen: 'not-tested',
      desktopVisualRendering: 'not-tested',
      physicalIPhoneCrossNetwork: 'not-tested',
      twoPhysicalComputers: 'not-tested',
    },
  };
}

async function main() {
  const { values } = parseArgs({
    options: {
      endpoint: { type: 'string', default: 'http://127.0.0.1:8794' },
      'state-dir': { type: 'string' },
      report: { type: 'string' },
      help: { type: 'boolean' },
    },
  });
  if (values.help) {
    console.log(
      'Usage: node probe-host.mjs --state-dir /path/to/private/state [--endpoint https://host] [--report /path/to/new/private-report.json]',
    );
    return;
  }
  requireValue(values['state-dir'], 'STATE_DIRECTORY_REQUIRED');
  const report = await probeHost({
    endpoint: values.endpoint,
    stateDirectory: resolve(values['state-dir']),
  });
  const output = JSON.stringify(report, null, 2);
  // A new private file only; never overwrite a state file, token or verification record.
  if (values.report)
    await writeFile(resolve(values.report), output + '\n', { mode: 0o600, flag: 'wx' });
  console.log(output);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    // Network/server errors may include paths, URLs or private content. Emit only a stable code.
    console.error(
      JSON.stringify({
        status: 'failed',
        error: error instanceof ProbeError ? error.code : 'PROBE_FAILED',
      }),
    );
    process.exitCode = 1;
  });
}
