import { withWindowsProcessCreation } from '@codex-manage/codex-adapter';
import { createServer, type Server } from 'node:https';
import { type IncomingMessage, type ServerResponse } from 'node:http';
import { X509Certificate, timingSafeEqual, randomUUID } from 'node:crypto';
import { closeSync, existsSync, fstatSync } from 'node:fs';
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { readFile, chmod, lstat, rm, rmdir } from 'node:fs/promises';
import { join } from 'node:path';
import { DirectBonjourPublisher, type DirectBonjourState } from './direct-bonjour.js';
import QRCode from 'qrcode';
import type { VPNDiscoveryPeer, VPNDiscoverySnapshot } from '@codex-manage/protocol';
import {
  DirectEndpointRegistry,
  isLocalLanAddress,
  type DirectEndpoint,
} from './direct-endpoints.js';
import {
  makePrivateDirectorySync,
  openPrivateFileSync,
  openPrivateFile,
  renamePrivateFileSync,
  protectWindowsStateFile,
  protectWindowsStateDirectory,
  assertPrivateFile,
  RuntimeError,
  appendRuntimeDiagnostic,
} from '@codex-manage/codex-adapter';

type Target = {
  agentId: string;
  hostId: string;
  workspaceId: string;
  runtimeId: string;
  account?: { authority: string; subject: string };
};
export type CodexHostDescriptor = {
  version: 1;
  agentId: 'codex';
  name: string;
  target: Target;
  fingerprint: string;
  endpoints: string[];
  endpointHints: DirectEndpoint[];
  connectionMode?: 'host-key';
};
const execute = promisify(execFile);

// A separate private HTTPS listener leaves existing loopback and Serve routes intact.
export async function startDirectDiscovery(options: {
  directory: string;
  name: string;
  token: string;
  target: () => Target;
  handler: (request: IncomingMessage, response: ServerResponse) => Promise<void>;
  peers?: () => Promise<VPNDiscoveryPeer[]>;
  claim?: (input: unknown) => unknown;
  registry?: DirectEndpointRegistry;
  port?: number;
  bind?: string;
}) {
  const directory = join(options.directory, 'direct-discovery');
  makePrivateDirectorySync(directory);
  protectWindowsStateDirectory(directory);
  const certPath = process.env.HOST_AGENT_DISCOVERY_TLS_CERT ?? join(directory, 'cert.pem');
  const keyPath = process.env.HOST_AGENT_DISCOVERY_TLS_KEY ?? join(directory, 'key.pem');
  if (
    Boolean(process.env.HOST_AGENT_DISCOVERY_TLS_CERT) !==
    Boolean(process.env.HOST_AGENT_DISCOVERY_TLS_KEY)
  )
    throw Error('Set both discovery TLS files');
  if (!process.env.HOST_AGENT_DISCOVERY_TLS_CERT) {
    try {
      await readFile(certPath);
      await readFile(keyPath);
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error;
      // Refuse a partial identity instead of silently rotating a paired certificate.
      const present = await Promise.all(
        [certPath, keyPath].map((path) =>
          readFile(path).then(
            () => true,
            () => false,
          ),
        ),
      );
      if (present.some(Boolean))
        throw Error('Incomplete discovery certificate; restore the original files', {
          cause: error,
        });
      const openssl =
        process.env.HOST_AGENT_OPENSSL ??
        (process.platform === 'win32'
          ? join(
              process.env.ProgramFiles ?? 'C:\\Program Files',
              'Git',
              'usr',
              'bin',
              'openssl.exe',
            )
          : '/usr/bin/openssl');
      const scratch = join(directory, 'certificate-' + randomUUID());
      if (existsSync(scratch))
        throw Object.assign(new Error('Certificate scratch directory already exists'), {
          code: 'EEXIST',
        });
      makePrivateDirectorySync(scratch);
      const created: { path: string; dev: bigint; ino: bigint }[] = [];
      try {
        // OpenSSL must overwrite our User-owned files, never create Admin-owned outputs.
        for (const name of ['key.pem', 'cert.pem']) {
          const path = join(scratch, name);
          const file = openPrivateFileSync(path, 'wx', 0o600);
          try {
            const identity = fstatSync(file, { bigint: true });
            created.push({ path, dev: identity.dev, ino: identity.ino });
          } finally {
            closeSync(file);
          }
        }
        await withWindowsProcessCreation(15000, (timeout) =>
          execute(
            openssl,
            [
              'req',
              '-x509',
              '-newkey',
              'rsa:2048',
              '-nodes',
              '-keyout',
              join(scratch, 'key.pem'),
              '-out',
              join(scratch, 'cert.pem'),
              '-days',
              '3650',
              '-subj',
              '/CN=Agent Hub Private Host',
            ],
            { timeout, maxBuffer: 65536, windowsHide: true },
          ),
        );
        for (const reserved of created) {
          const current = await lstat(reserved.path, { bigint: true });
          if (
            !current.isFile() ||
            current.isSymbolicLink() ||
            current.dev !== reserved.dev ||
            current.ino !== reserved.ino
          )
            throw new Error('Certificate output identity changed', { cause: error });
          await chmod(reserved.path, 0o600);
          protectWindowsStateFile(reserved.path);
        }
        renamePrivateFileSync(join(scratch, 'key.pem'), keyPath);
        renamePrivateFileSync(join(scratch, 'cert.pem'), certPath);
      } finally {
        for (const reserved of created) {
          // A missing, replaced or uninspectable path is not ours to remove.
          const current = await lstat(reserved.path, { bigint: true }).catch(() => undefined);
          if (
            current?.isFile() &&
            !current.isSymbolicLink() &&
            current.dev === reserved.dev &&
            current.ino === reserved.ino
          )
            await rm(reserved.path, { force: true });
        }
        await rmdir(scratch).catch((error: NodeJS.ErrnoException) => {
          if (error.code !== 'ENOTEMPTY' && error.code !== 'EEXIST') throw error;
        });
      }
    }
  }
  assertPrivateFile(keyPath);
  const cert = await readFile(certPath),
    key = await readFile(keyPath);
  const fingerprint = new X509Certificate(cert).fingerprint256.replaceAll(':', '').toLowerCase();
  let endpoints: DirectEndpoint[] = [];
  const descriptor = (): CodexHostDescriptor => ({
    version: 1,
    agentId: 'codex',
    name: options.name,
    target: options.target(),
    fingerprint,
    endpoints: endpoints.map((item) => item.endpoint),
    endpointHints: endpoints,
    ...(options.claim ? { connectionMode: 'host-key' as const } : {}),
  });
  const server = createServer({ cert, key }, (request, response) => {
    if (!isLocalLanAddress(request.socket.remoteAddress ?? '') || request.headers.origin) {
      response.writeHead(403);
      response.end();
      return;
    }
    if (request.method === 'GET' && request.url === '/host') {
      response.writeHead(200, { 'content-type': 'application/json', 'cache-control': 'no-store' });
      response.end(JSON.stringify(descriptor()));
      return;
    }
    let url: URL;
    try {
      url = new URL(request.url ?? '/', 'https://localhost');
    } catch {
      response.writeHead(400);
      response.end();
      return;
    }
    if (request.method === 'POST' && request.url === '/connection/claim' && options.claim) {
      // Network visibility and possession of a phone public key are not authority.
      // Only the Host Key can authorize issuance, including recovery of a claim.
      const supplied = Buffer.from(request.headers.authorization ?? '');
      const expected = Buffer.from('Bearer ' + options.token);
      if (supplied.length !== expected.length || !timingSafeEqual(supplied, expected)) {
        response.writeHead(401, { 'cache-control': 'no-store' });
        response.end();
        return;
      }
      void (async () => {
        try {
          if (request.headers['content-type'] !== 'application/json')
            throw new RuntimeError(400, 'Invalid content type');
          let length = 0;
          const parts: Buffer[] = [];
          for await (const part of request) {
            length += (part as Buffer).length;
            if (length > 8192) throw new RuntimeError(413, 'Request too large');
            parts.push(part as Buffer);
          }
          let input: unknown;
          try {
            input = JSON.parse(Buffer.concat(parts).toString());
          } catch {
            throw new RuntimeError(400, 'Invalid JSON');
          }
          const reply = options.claim!(input);
          response.writeHead(200, {
            'content-type': 'application/json',
            'cache-control': 'no-store',
          });
          response.end(JSON.stringify(reply));
        } catch (error) {
          response.writeHead(error instanceof RuntimeError ? error.status : 500);
          response.end();
        }
      })();
      return;
    }
    if (url.pathname === '/discovery/peers' && options.peers) {
      const target = options.target();
      if (
        request.method !== 'GET' ||
        url.searchParams.get('hostId') !== target.hostId ||
        [...url.searchParams].length !== 1
      ) {
        response.writeHead(400);
        response.end();
        return;
      }
      // Private-network discovery contains address hints only. Binding and every
      // workspace/command request still require the existing runtime authority.
      void options.peers().then(
        (peers) => {
          const snapshot: VPNDiscoverySnapshot = { version: 1, target, peers };
          response.writeHead(200, {
            'content-type': 'application/json',
            'cache-control': 'no-store',
          });
          response.end(JSON.stringify(snapshot));
        },
        () => {
          response.writeHead(503);
          response.end();
        },
      );
      return;
    }
    void options.handler(request, response);
  });
  server.requestTimeout = 40000;
  server.headersTimeout = 10000;
  let closed = false,
    refreshing = false;
  let timer: ReturnType<typeof setInterval> | undefined;
  let bonjour: DirectBonjourPublisher | undefined;
  let pendingBonjourState: DirectBonjourState | undefined;
  let savingBonjourState = false;
  const saveBonjourState = (state: DirectBonjourState) => {
    pendingBonjourState = state;
    if (savingBonjourState) return;
    savingBonjourState = true;
    void (async () => {
      try {
        while (pendingBonjourState) {
          const value = pendingBonjourState;
          pendingBonjourState = undefined;
          const temporary = join(directory, `bonjour-state.${randomUUID()}.tmp`);
          let identity: { dev: bigint; ino: bigint } | undefined;
          try {
            const file = await openPrivateFile(temporary, 'wx', 0o600);
            try {
              const stat = await file.stat({ bigint: true });
              identity = { dev: stat.dev, ino: stat.ino };
              await file.writeFile(JSON.stringify(value));
            } finally {
              await file.close();
            }
            const current = await lstat(temporary, { bigint: true });
            if (current.dev !== identity.dev || current.ino !== identity.ino)
              throw new Error('Discovery state output identity changed');
            protectWindowsStateFile(temporary);
            renamePrivateFileSync(temporary, join(directory, 'bonjour-state.json'));
          } finally {
            if (identity) {
              const current = await lstat(temporary, { bigint: true }).catch(
                (error: NodeJS.ErrnoException) => {
                  if (error.code === 'ENOENT') return undefined;
                  throw error;
                },
              );
              if (current && current.dev === identity.dev && current.ino === identity.ino)
                await rm(temporary, { force: true });
            }
          }
        }
      } catch {
        try {
          appendRuntimeDiagnostic(
            options.directory,
            { scope: 'direct-bonjour', stage: 'state-persist', hostId: options.target().hostId },
            Object.assign(new Error('Discovery state write failed'), {
              code: 'MDNS_STATE_WRITE_FAILED',
            }),
          );
        } catch {
          /* Public discovery diagnostics never own HTTPS availability. */
        }
      } finally {
        savingBonjourState = false;
        if (pendingBonjourState) saveBonjourState(pendingBonjourState);
      }
    })();
  };
  const close = () => {
    if (closed) return;
    closed = true;
    clearInterval(timer);
    try {
      void bonjour?.close();
    } finally {
      server.close();
      server.closeAllConnections();
    }
  };
  try {
    let bind = options.bind ?? process.env.HOST_AGENT_DISCOVERY_BIND ?? '::';
    const port = options.port ?? Number(process.env.HOST_AGENT_DISCOVERY_PORT ?? 8796);
    const listen = () =>
      new Promise<void>((done, reject) => {
        const error = (error: Error) => {
          server.removeListener('listening', ready);
          reject(error);
        };
        const ready = () => {
          server.removeListener('error', error);
          done();
        };
        server.once('error', error);
        server.once('listening', ready);
        server.listen({ port, host: bind, ipv6Only: false });
      });
    try {
      await listen();
    } catch (error) {
      if (
        bind !== '::' ||
        !['EAFNOSUPPORT', 'EPROTONOSUPPORT', 'EINVAL', 'EADDRNOTAVAIL'].includes(
          (error as NodeJS.ErrnoException).code ?? '',
        )
      )
        throw error;
      bind = '0.0.0.0';
      await listen();
    }
    const actual = server.address();
    if (!actual || typeof actual === 'string') throw Error('Missing discovery listener');
    const registry = options.registry ?? new DirectEndpointRegistry();
    const pagePath = join(directory, 'connect.html');
    const refresh = async () => {
      if (closed || refreshing) return;
      refreshing = true;
      try {
        const next = await registry.resolve({
          port: actual.port,
          ipv6: bind === '::',
          bindAddress: bind,
        });
        if (closed) return;
        endpoints = next;
        const offer = { ...descriptor(), token: options.token };
        const encode = () =>
          'codex-host://pair?descriptor=' +
          Buffer.from(JSON.stringify(offer)).toString('base64url');
        let uri = encode();
        // Preserve a LAN and a VPN bootstrap path when the full address snapshot
        // exceeds QR capacity. A pinned /host read then supplies every address.
        if (Buffer.byteLength(uri) > 2800) {
          offer.endpointHints = [];
          const firstVPN = endpoints.find((item) => item.networkKind === 'vpn')?.endpoint;
          offer.endpoints = [
            ...new Set([
              ...(offer.endpoints[0] ? [offer.endpoints[0]] : []),
              ...(firstVPN ? [firstVPN] : []),
              ...offer.endpoints,
            ]),
          ];
          uri = encode();
          while (Buffer.byteLength(uri) > 2800 && offer.endpoints.length > 2) {
            offer.endpoints.pop();
            uri = encode();
          }
        }
        const svg = await QRCode.toString(uri, { type: 'svg', errorCorrectionLevel: 'L' });
        if (closed) return;
        // Local private file only: credentials never enter DNS, /host, console or logs.
        const temp = join(directory, 'connect.html.tmp');
        const file = await openPrivateFile(temp, 'w', 0o600);
        try {
          await file.writeFile(
            '<!doctype html><meta charset="utf-8"><title>连接 Codex</title><h1>扫描连接 Codex</h1><p>首次扫码，之后自动发现局域网和 VPN 地址。二维码包含本机客户端密钥，请仅向自己的手机展示。</p>' +
              svg +
              '<p>地址变化时重新打开此文件查看最新二维码。</p>',
          );
        } finally {
          await file.close();
        }
        protectWindowsStateFile(temp);
        renamePrivateFileSync(temp, pagePath);
      } finally {
        refreshing = false;
      }
    };
    await refresh();
    timer = setInterval(() => {
      void refresh().catch(() => {
        /* Keep the last identity snapshot. */
      });
    }, 15000);
    timer.unref();
    if (process.env.HOST_AGENT_DISCOVERY_BONJOUR !== '0') {
      bonjour = new DirectBonjourPublisher(
        { name: options.name, hostId: options.target().hostId, port: actual.port },
        saveBonjourState,
      );
      bonjour.start();
    }
    return {
      server: server as Server,
      descriptor,
      pagePath,
      close,
      refresh,
      bonjourState: () => bonjour?.snapshot(),
    };
  } catch (error) {
    // A failed QR/advertisement step may already own the HTTPS port. Retire it
    // before the recovery controller is allowed to start another generation.
    closed = true;
    clearInterval(timer);
    try {
      await bonjour?.close();
    } finally {
      await new Promise<void>((done) => {
        server.close(() => done());
        server.closeAllConnections();
      });
    }
    throw error;
  }
}
