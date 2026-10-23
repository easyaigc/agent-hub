import type { IncomingMessage, ServerResponse } from 'node:http';
import { promisify } from 'node:util';
import { gzip } from 'node:zlib';

const compress = promisify(gzip);

function acceptsGzip(header: string | undefined): boolean {
  let explicit: number | undefined;
  let wildcard = 0;
  for (const entry of header?.split(',') ?? []) {
    const [coding, ...parameters] = entry.trim().toLowerCase().split(';');
    const name = coding?.trim();
    const quality = parameters.find((parameter) => parameter.trim().startsWith('q='));
    const value = quality === undefined ? 1 : Number(quality.trim().slice(2));
    const weight = Number.isFinite(value) && value >= 0 && value <= 1 ? value : 0;
    if (name === 'gzip') explicit = weight;
    if (name === '*') wildcard = weight;
  }
  return (explicit ?? wildcard) > 0;
}

/** Compress authenticated read snapshots off the event loop; wire JSON stays unchanged. */
export async function sendReadJSON(
  request: IncomingMessage,
  response: ServerResponse,
  value: unknown,
): Promise<void> {
  if (response.destroyed) return;
  let bytes = Buffer.from(JSON.stringify(value));
  let encoding: string | undefined;
  if (bytes.length >= 1024 && acceptsGzip(request.headers['accept-encoding'])) {
    // Fast compression avoids adding noticeable CPU latency to each streaming update.
    const compressed = await compress(bytes, { level: 1 });
    if (compressed.length < bytes.length) {
      bytes = compressed;
      encoding = 'gzip';
    }
  }
  if (response.destroyed) return;
  response.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': bytes.length,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    Vary: 'Accept-Encoding',
    ...(encoding ? { 'Content-Encoding': encoding } : {}),
  });
  response.end(bytes);
}
