import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { appendFile, readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

// Public snapshots have independent history. Preserve the complete event range
// instead of requiring a private Gitea commit in a public clone.
export function selectMobileParityBase({ eventName, event = {}, manifestBase, override, cwd }) {
  const git = (...args) =>
    execFileSync('git', args, { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  const commit = (value, description) => {
    assert(typeof value === 'string' && /^[a-f0-9]{7,64}$/.test(value), `Invalid ${description}`);
    try {
      return git('rev-parse', '--verify', `${value}^{commit}`);
    } catch {
      throw new Error(`Missing ${description}: fetch its history before running mobile parity`);
    }
  };
  if (override) return commit(override, 'explicit mobile parity base');
  if (eventName === 'pull_request')
    return commit(event.pull_request?.base?.sha, 'pull request base');
  if (eventName === 'push' && event.before && !/^0+$/.test(event.before))
    return commit(event.before, 'previous push revision');
  assert(['push', 'workflow_dispatch'].includes(eventName), 'Unsupported parity CI event');
  if (event.inputs?.mobile_parity_base)
    return commit(event.inputs.mobile_parity_base, 'manual mobile parity base');
  try {
    return commit(manifestBase, 'manifest mobile parity base');
  } catch {
    // A missing explicit PR/push/override base above always fails closed.
  }
  const head = git('rev-parse', 'HEAD^{commit}');
  try {
    const main = git('rev-parse', '--verify', 'refs/remotes/origin/main^{commit}');
    const base = git('merge-base', head, main);
    if (base !== head) return base;
  } catch {
    // A full public main checkout may have no remote-tracking ref.
  }
  try {
    return git('rev-parse', '--verify', 'HEAD^1^{commit}');
  } catch {
    throw new Error('No comparison history; provide an available mobile_parity_base revision');
  }
}

async function main() {
  const eventName = process.env.GITHUB_EVENT_NAME ?? process.env.GITEA_EVENT_NAME;
  const eventPath = process.env.GITHUB_EVENT_PATH ?? process.env.GITEA_EVENT_PATH;
  const output = process.env.GITHUB_ENV ?? process.env.GITEA_ENV;
  assert(eventName && eventPath && output, 'CI event payload and environment file are required');
  const event = JSON.parse(await readFile(eventPath, 'utf8'));
  const manifest = JSON.parse(await readFile('docs/product/mobile-parity.manifest.json', 'utf8'));
  const base = selectMobileParityBase({
    eventName,
    event,
    manifestBase: manifest.baselineCommit,
    override: process.env.MOBILE_PARITY_BASE_SHA,
    cwd: process.cwd(),
  });
  await appendFile(output, `MOBILE_PARITY_BASE_SHA=${base}\n`);
  console.log(`Mobile parity comparison base: ${base} (${eventName})`);
}
if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) await main();
