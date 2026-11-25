import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { object, string } from './runtime.js';
import type { NativeSuggestionProducerIdentity } from './desktop-native-sample-platform.js';

/** An original executor generation, not a renderer client ID or stored-history owner. */
export interface OriginalDesktopSource {
  version: 1;
  kind: 'original-desktop-shared-service';
  url: string;
  producer: NativeSuggestionProducerIdentity;
  serverPid: number;
  serverStartedAt: number;
  manifestSha256: string;
  ownerSha256: string;
}

const hash = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');
const positive = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value > 0;
const digest = /^[a-f0-9]{64}$/;

/** Validate the inspector's narrow output before it can authorize any connection. */
export function parseOriginalDesktopSource(
  value: unknown,
  expectedUrl: string,
): OriginalDesktopSource {
  const row = object(value),
    producer = object(row.producer);
  const url = new URL(expectedUrl);
  if (
    url.protocol !== 'ws:' ||
    url.hostname !== '127.0.0.1' ||
    !url.port ||
    url.pathname !== '/' ||
    url.search ||
    url.hash ||
    url.username ||
    url.password ||
    expectedUrl !== url.origin ||
    row.url !== expectedUrl ||
    row.version !== 1 ||
    row.kind !== 'original-desktop-shared-service' ||
    Object.keys(row).some(
      (key) =>
        ![
          'version',
          'kind',
          'url',
          'producer',
          'serverPid',
          'serverStartedAt',
          'manifestSha256',
          'ownerSha256',
        ].includes(key),
    ) ||
    Object.keys(producer).some(
      (key) =>
        ![
          'pid',
          'desktopPid',
          'wrapperStartedAt',
          'desktopStartedAt',
          'logPid',
          'logStartedAt',
        ].includes(key),
    ) ||
    ![row.serverPid, producer.pid, producer.desktopPid].every(
      (pid) => positive(pid) && pid > 1 && pid <= 2147483647,
    ) ||
    ![row.serverStartedAt, producer.wrapperStartedAt, producer.desktopStartedAt].every(
      (at) => positive(at) && at <= Date.now(),
    ) ||
    Number(row.serverStartedAt) < Number(producer.wrapperStartedAt) ||
    Number(producer.wrapperStartedAt) < Number(producer.desktopStartedAt) ||
    new Set([row.serverPid, producer.pid, producer.desktopPid]).size !== 3 ||
    !digest.test(string(row.manifestSha256)) ||
    !digest.test(string(row.ownerSha256))
  )
    throw new Error('Original desktop shared service identity invalid');
  if (producer.logPid !== undefined || producer.logStartedAt !== undefined) {
    if (
      !positive(producer.logPid) ||
      producer.logPid <= 1 ||
      producer.logPid > 2147483647 ||
      !positive(producer.logStartedAt) ||
      producer.logStartedAt < Number(producer.desktopStartedAt) ||
      producer.logStartedAt > Number(producer.wrapperStartedAt) ||
      [row.serverPid, producer.pid, producer.desktopPid].includes(producer.logPid)
    )
      throw new Error('Original desktop utility identity invalid');
  }
  return row as unknown as OriginalDesktopSource;
}

/** Fixed local inspection only. This entry cannot load a thread or start an executor. */
export class OriginalDesktopSourceInspector {
  private readonly helper = fileURLToPath(
    new URL('../resources/desktop-completion-notify-observer.py', import.meta.url),
  );
  private readonly helperSha256 = hash(readFileSync(this.helper));
  constructor(
    private readonly url: string,
    private readonly state = join(homedir(), '.config/agent-hub/shared-desktop'),
  ) {}
  inspect(): OriginalDesktopSource {
    if (process.platform !== 'darwin') throw new Error('Original macOS service required');
    const checkHelper = () => {
      if (hash(readFileSync(this.helper)) !== this.helperSha256)
        throw new Error('Original service inspector changed');
    };
    checkHelper();
    const result = parseOriginalDesktopSource(
      JSON.parse(
        execFileSync(
          '/usr/bin/python3',
          ['-I', this.helper, '--inspect-shared-service', '--shared-desktop-state', this.state],
          { encoding: 'utf8', timeout: 5000, maxBuffer: 8192, stdio: ['ignore', 'pipe', 'pipe'] },
        ),
      ),
      this.url,
    );
    // The private owner record and kernel parent chain must identify the process
    // actually listening at this exact endpoint, rather than an unrelated Node service.
    const endpoint = new URL(this.url);
    const listener = execFileSync(
      '/usr/sbin/lsof',
      [
        '-nP',
        '-a',
        '-p',
        String(result.serverPid),
        '-iTCP:' + endpoint.port,
        '-sTCP:LISTEN',
        '-Fpn',
      ],
      { encoding: 'utf8', timeout: 3000, maxBuffer: 8192, stdio: ['ignore', 'pipe', 'pipe'] },
    );
    const fields = listener.trim().split('\n');
    if (
      fields.filter((line) => line.startsWith('p')).join() !== 'p' + result.serverPid ||
      fields.filter((line) => line.startsWith('n')).join() !== 'n127.0.0.1:' + endpoint.port
    )
      throw new Error('Original desktop listener identity changed');
    checkHelper();
    const confirmed = parseOriginalDesktopSource(
      JSON.parse(
        execFileSync(
          '/usr/bin/python3',
          ['-I', this.helper, '--inspect-shared-service', '--shared-desktop-state', this.state],
          { encoding: 'utf8', timeout: 5000, maxBuffer: 8192, stdio: ['ignore', 'pipe', 'pipe'] },
        ),
      ),
      this.url,
    );
    checkHelper();
    if (JSON.stringify(confirmed) !== JSON.stringify(result))
      throw new Error('Original desktop source changed during inspection');
    return result;
  }
  assert(source: OriginalDesktopSource): void {
    if (JSON.stringify(this.inspect()) !== JSON.stringify(source))
      throw new Error('Original desktop executor generation changed');
  }
}
