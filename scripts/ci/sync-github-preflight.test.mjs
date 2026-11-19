import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

// Execute the actual pre-checkout program: the repository is not available at that step.
const workflow = readFileSync(
  new URL('../../.github/workflows/sync-github.yml', import.meta.url),
  'utf8',
);
const script = workflow
  .match(/node --input-type=module <<'PREFLIGHT'\n([\s\S]+?)\n {10}PREFLIGHT/)[1]
  .replace(/^ {10}/gm, '');
const source = 'a'.repeat(40);
const target = 'b'.repeat(40);
const changed = 'd'.repeat(40);

async function fixture(t, options = {}) {
  const root = mkdtempSync(join(tmpdir(), 'agent-hub-sync-preflight-'));
  execFileSync('git', ['init', '--quiet', root]);
  const entries = [
    { mode: '040000', type: 'tree', path: 'fixture', sha: '1'.repeat(40) },
    { mode: '100644', type: 'blob', path: 'fixture.txt', sha: '2'.repeat(40) },
    { mode: '100755', type: 'blob', path: 'run.sh', sha: '3'.repeat(40) },
    { mode: '120000', type: 'blob', path: 'link', sha: '4'.repeat(40) },
    { mode: '160000', type: 'commit', path: 'submodule', sha: '5'.repeat(40) },
    { mode: '100644', type: 'blob', path: '中文.txt', sha: '6'.repeat(40) },
  ];
  // Git itself supplies the expected hash, including directory ordering and all supported modes.
  const tree = execFileSync('git', ['mktree', '--missing'], {
    cwd: root,
    input: entries
      .map((entry) => `${entry.mode} ${entry.type} ${entry.sha}\t${entry.path}\n`)
      .join(''),
    encoding: 'utf8',
  }).trim();
  const output = join(root, 'output');
  const requests = [];
  let sourceReads = 0;
  let targetReads = 0;
  const server = createServer((req, res) => {
    requests.push({ path: req.url, authorization: req.headers.authorization });
    const sourceRequest = req.url.startsWith('/source/');
    res.setHeader('Content-Type', 'application/json');
    if (options.httpFailure && sourceRequest) {
      res.writeHead(401).end(JSON.stringify({ message: 'not authorized' }));
      return;
    }
    if (options.redirect && sourceRequest) {
      res.writeHead(302, { Location: '/public/credential-trap' }).end();
      return;
    }
    let value;
    if (req.url.endsWith('/branches/main')) {
      sourceReads++;
      value = { commit: { id: options.sourceRace && sourceReads > 1 ? changed : source } };
    } else if (req.url.endsWith('/git/ref/heads/main')) {
      targetReads++;
      value = { object: { sha: options.targetRace && targetReads > 1 ? changed : target } };
    } else if (sourceRequest) {
      value = {
        sha: source,
        truncated: Boolean(options.truncated),
        tree: options.invalidEntry ? [{ ...entries[0], path: 'nested/path' }] : entries,
      };
    } else {
      value = {
        sha: target,
        tree: { sha: options.wrongTree ? changed : tree },
        message: options.missingMarker
          ? 'ordinary public commit'
          : `sync\n\nGitea-Source-Commit: ${options.newSource ? changed : source}\n`,
      };
    }
    res.end(options.malformed ? '{broken' : JSON.stringify(value));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => {
    await new Promise((resolve) => server.close(resolve));
    rmSync(root, { recursive: true, force: true });
  });
  const base = `http://127.0.0.1:${server.address().port}`;
  const child = spawn(process.execPath, ['--input-type=module', '-'], {
    env: {
      ...process.env,
      GITEA_SYNC_API: `${base}/source`,
      GITEA_SYNC_REPOSITORY: 'BCAI/agent-hub-app',
      GITEA_SYNC_TOKEN: 'fixture-secret',
      GITHUB_PUBLIC_SYNC_API: `${base}/public`,
      GITHUB_OUTPUT: output,
    },
    stdio: ['pipe', 'pipe', 'pipe'],
  });
  let stdout = '';
  let stderr = '';
  child.stdout.on('data', (data) => {
    stdout += data;
  });
  child.stderr.on('data', (data) => {
    stderr += data;
  });
  child.stdin.end(script);
  const code = await new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', resolve);
  });
  assert.equal(code, 0, stderr);
  assert.ok(!stdout.includes('fixture-secret'));
  assert.ok(!stderr.includes('fixture-secret'));
  return { output: readFileSync(output, 'utf8'), stdout, requests };
}

test('matching stable source marker and exact tree skip all checkout and publish steps', async (t) => {
  const result = await fixture(t);
  assert.equal(result.output, 'needs_sync=false\n');
  assert.match(result.stdout, /unchanged-before-checkout/);
  assert.equal(result.requests.length, 6);
  assert.equal(
    (workflow.match(/if: steps.preflight.outputs.needs_sync != 'false'/g) ?? []).length,
    3,
  );
});

for (const [name, options] of [
  ['a different source marker runs the original sync', { newSource: true }],
  ['a matching source marker with a different tree runs the original sync', { wrongTree: true }],
  ['a missing source marker runs the original sync', { missingMarker: true }],
  ['a truncated source tree cannot be treated as unchanged', { truncated: true }],
  ['invalid root tree entries cannot be treated as unchanged', { invalidEntry: true }],
  ['source movement during the check runs the original sync', { sourceRace: true }],
  ['public head movement during the check runs the original sync', { targetRace: true }],
  ['an unauthorized source API runs the original sync', { httpFailure: true }],
  ['malformed metadata runs the original sync', { malformed: true }],
  ['redirects cannot forward source credentials or cause an unchanged result', { redirect: true }],
]) {
  test(name, async (t) => {
    const result = await fixture(t, options);
    assert.equal(result.output, 'needs_sync=true\n');
    assert.ok(!result.requests.some((request) => request.path.includes('credential-trap')));
  });
}

test('the job token is sent only to the private source metadata API', async (t) => {
  const result = await fixture(t);
  for (const request of result.requests) {
    assert.equal(
      request.authorization,
      request.path.startsWith('/source/') ? 'token fixture-secret' : undefined,
    );
  }
});
