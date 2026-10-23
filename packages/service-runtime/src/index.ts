import { createServer } from 'node:http';
import { PROTOCOL_VERSION, type HealthResponse, type ServiceName } from '@codex-manage/protocol';

interface ServiceOptions {
  service: ServiceName;
  portVariable: string;
  addressVariable: string;
  defaultPort: number;
}

export function startHealthService(options: ServiceOptions): void {
  const rawPort = process.env[options.portVariable] ?? String(options.defaultPort);
  if (!/^\d+$/.test(rawPort) || Number(rawPort) > 65535) {
    throw new Error(`${options.portVariable} must be an integer between 0 and 65535`);
  }
  const address = process.env[options.addressVariable] ?? '127.0.0.1';
  const health: HealthResponse = {
    service: options.service,
    status: 'ok',
    protocolVersion: PROTOCOL_VERSION,
    capabilities: ['health'],
  };
  const server = createServer((request, response) => {
    response.setHeader('Content-Type', 'application/json; charset=utf-8');
    response.setHeader('Cache-Control', 'no-store');
    if (request.method === 'GET' && request.url === '/healthz') {
      response.writeHead(200);
      response.end(JSON.stringify(health));
      return;
    }
    response.writeHead(404);
    response.end(JSON.stringify({ error: 'not_found' }));
  });

  server.on('error', (error) => {
    console.error(`${options.service}: ${error.message}`);
    process.exitCode = 1;
  });
  server.listen(Number(rawPort), address, () => {
    const bound = server.address();
    if (!bound || typeof bound === 'string') throw new Error('Expected a TCP server address');
    console.log(
      JSON.stringify({ service: options.service, address: bound.address, port: bound.port }),
    );
  });

  for (const signal of ['SIGINT', 'SIGTERM'] as const) {
    process.once(signal, () => {
      server.close(() => {
        process.exitCode = 0;
      });
      server.closeIdleConnections();
      setTimeout(() => server.closeAllConnections(), 5000).unref();
    });
  }
}
