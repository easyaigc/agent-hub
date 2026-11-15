import type { IncomingMessage, ServerResponse } from 'node:http';

/** Receiving a file chunk has a total budget and an independent inactivity budget. */
export function receivingDeadline(request: IncomingMessage, response: ServerResponse) {
  if (request.complete) return;
  const chunk = request.url?.split('?')[0] === '/v1/uploads/chunk';
  const timer = setTimeout(() => fail(), chunk ? 120_000 : 40_000);
  timer.unref();
  function fail() {
    if (request.complete || response.destroyed) return;
    if (!response.headersSent) response.writeHead(408).end();
    request.destroy();
  }
  const inactive = () => {
    if (!request.complete) fail();
  };
  request.setTimeout(30_000, inactive);
  const cleanup = () => {
    clearTimeout(timer);
    request.removeListener('timeout', inactive);
    // The receive inactivity budget must not become a socket timeout for publication.
    request.setTimeout(0);
  };
  request.once('end', cleanup);
  request.once('close', cleanup);
}
