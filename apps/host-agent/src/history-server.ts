import { createServer } from 'node:http';
import { randomUUID } from 'node:crypto';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { CodexHistoryReader } from '@codex-manage/codex-adapter';

export async function startHistoryServer(): Promise<void> {
  const address = process.env.HOST_AGENT_BIND_ADDRESS ?? '127.0.0.1';
  if (address !== '127.0.0.1') throw new Error('Development history access must bind to 127.0.0.1');
  const rawPort = process.env.HOST_AGENT_PORT ?? '8788';
  if (!/^\d+$/.test(rawPort) || Number(rawPort) > 65535) throw new Error('Invalid HOST_AGENT_PORT');
  const stateDirectory = resolve(process.env.HOST_AGENT_STATE_DIR ?? '.local/host-agent');
  await mkdir(stateDirectory, { recursive: true, mode: 0o700 });
  const identityPath = resolve(stateDirectory, 'identity.json');
  let hostId: string;
  try {
    const identity: unknown = JSON.parse(await readFile(identityPath, 'utf8'));
    if (
      !identity ||
      typeof identity !== 'object' ||
      !('id' in identity) ||
      typeof identity.id !== 'string' ||
      !/^[\da-f-]{36}$/i.test(identity.id)
    )
      throw new Error('Invalid persisted host identity');
    hostId = identity.id;
  } catch (error) {
    if (!(error instanceof Error) || !('code' in error) || error.code !== 'ENOENT') throw error;
    hostId = randomUUID();
    await writeFile(identityPath, JSON.stringify({ id: hostId }), { mode: 0o600, flag: 'wx' });
  }
  const reader = new CodexHistoryReader(
    hostId,
    process.env.HOST_AGENT_NAME ?? '本机 Mac',
    process.env.CODEX_EXECUTABLE ?? 'codex',
  );
  await reader.connect();
  const server = createServer(async (request, response) => {
    response.setHeader('Content-Type', 'application/json; charset=utf-8');
    response.setHeader('Cache-Control', 'no-store');
    response.setHeader('X-Content-Type-Options', 'nosniff');
    const send = (status: number, body: unknown) => {
      response.writeHead(status);
      response.end(JSON.stringify(body));
    };
    // This local development API is not an authenticated remote endpoint.
    if (request.headers.origin || !/^127\.0\.0\.1(?::\d+)?$/.test(request.headers.host ?? '')) {
      send(403, { error: 'local_access_only' });
      return;
    }
    if (request.method !== 'GET') {
      send(405, { error: 'read_only' });
      return;
    }
    const url = new URL(request.url ?? '/', 'http://127.0.0.1');
    const parameter = (name: string) => {
      const value = url.searchParams.get(name) ?? undefined;
      if (value && value.length > 2048) throw new Error('Invalid parameter');
      return value;
    };
    if (url.pathname === '/healthz') {
      send(200, {
        service: 'host-agent',
        status: 'ok',
        protocolVersion: '0.1',
        capabilities: reader.ready
          ? ['health', 'projects:read', 'threads:read', 'history:read']
          : ['health'],
      });
      return;
    }
    if (!['/v1/workspace', '/v1/history'].includes(url.pathname)) {
      send(404, { error: 'not_found' });
      return;
    }
    if (!reader.ready) {
      send(503, { error: 'codex_reader_unavailable' });
      return;
    }
    try {
      if (url.pathname === '/v1/workspace')
        send(200, await reader.workspace(parameter('projectCursor'), parameter('threadCursor')));
      else {
        const threadId = parameter('threadId');
        if (parameter('hostId') !== hostId || !threadId || threadId.length > 256) {
          send(400, { error: 'invalid_target' });
          return;
        }
        send(200, await reader.history(threadId, parameter('cursor')));
      }
    } catch {
      send(502, { error: 'codex_read_failed' });
    }
  });
  server.on('error', () => {
    reader.close();
    process.exitCode = 1;
  });
  server.listen(Number(rawPort), address, () => {
    const bound = server.address();
    if (bound && typeof bound !== 'string')
      console.log(
        JSON.stringify({
          service: 'host-agent',
          address: bound.address,
          port: bound.port,
          connectionMode: 'history-only',
        }),
      );
  });
  for (const signal of ['SIGTERM', 'SIGINT'] as const)
    process.once(signal, () => {
      reader.close();
      server.close();
      server.closeAllConnections();
    });
}
