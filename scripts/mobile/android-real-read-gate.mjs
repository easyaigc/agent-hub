import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { createServer } from 'node:http';

// Runtime-only input from an independent Codex desktop read. Never export real history.
export async function startRealReadGate(path, adb, serial) {
  const proof = JSON.parse(await readFile(path, 'utf8'));
  const base = new URL(proof.endpoint);
  assert(
    base.protocol === 'http:' &&
      base.hostname === '127.0.0.1' &&
      base.port &&
      !base.username &&
      !base.password &&
      base.pathname === '/' &&
      !base.search &&
      !base.hash,
  );
  assert.equal(proof.mode, 'history-only');
  assert.equal(proof.freshModelExecution, false);
  assert.equal(proof.matchedDesktopReply, 1);
  assert.equal(proof.independentDesktopSource, 'Codex app read_thread');
  assert(proof.threadId && proof.hostId && /^[a-f0-9]{64}$/.test(proof.replySha256));
  const requests = [];
  const server = createServer(async (request, response) => {
    const url = new URL(request.url, base);
    requests.push({ method: request.method, path: url.pathname });
    if (
      url.origin !== base.origin ||
      request.method !== 'GET' ||
      !['/v1/workspace', '/v1/history'].includes(url.pathname)
    ) {
      response.writeHead(405).end();
      return;
    }
    try {
      const upstream = await fetch(url, { redirect: 'error', signal: AbortSignal.timeout(45_000) });
      response.writeHead(upstream.status, { 'content-type': 'application/json' });
      response.end(Buffer.from(await upstream.arrayBuffer()));
    } catch {
      response.writeHead(502).end();
    }
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  execFileSync(adb, ['-s', serial, 'reverse', `tcp:${port}`, `tcp:${port}`]);
  return {
    proof,
    requests,
    endpoint: `http://127.0.0.1:${port}`,
    close: async () => {
      await new Promise((resolve) => server.close(resolve));
      execFileSync(adb, ['-s', serial, 'reverse', '--remove', `tcp:${port}`]);
    },
  };
}
