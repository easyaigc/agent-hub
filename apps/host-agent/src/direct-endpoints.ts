import { withWindowsProcessCreation } from '@codex-manage/codex-adapter';
import { execFile } from 'node:child_process';
import { access } from 'node:fs/promises';
import { BlockList, isIP } from 'node:net';
import { networkInterfaces, type NetworkInterfaceInfo } from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import type { VPNDiscoveryPeer } from '@codex-manage/protocol';

export type DirectNetworkKind = 'lan' | 'vpn' | 'unknown';
export type DirectEndpoint = { endpoint: string; networkKind: DirectNetworkKind };
export type DirectAddress = { host: string; networkKind: DirectNetworkKind };
export type VPNPeer = VPNDiscoveryPeer;
export interface DirectAddressProvider {
  readonly id: string;
  addresses(): Promise<readonly DirectAddress[]>;
}

const addresses = new BlockList();
for (const [address, prefix] of [
  ['10.0.0.0', 8],
  ['172.16.0.0', 12],
  ['192.168.0.0', 16],
  ['100.64.0.0', 10],
  ['169.254.0.0', 16],
  ['127.0.0.0', 8],
] as const)
  addresses.addSubnet(address, prefix, 'ipv4');
addresses.addSubnet('fc00::', 7, 'ipv6');
addresses.addSubnet('fe80::', 10, 'ipv6');
addresses.addAddress('::1', 'ipv6');
const loopbacks = new BlockList();
loopbacks.addSubnet('127.0.0.0', 8, 'ipv4');
loopbacks.addAddress('::1', 'ipv6');
function isLoopbackAddress(host: string): boolean {
  const family = isIP(host);
  return family !== 0 && loopbacks.check(host, family === 4 ? 'ipv4' : 'ipv6');
}

export function isLocalLanAddress(address: string): boolean {
  // BlockList also classifies all equivalent IPv4-mapped IPv6 encodings.
  const normalized = address.split('%')[0]!;
  const family = isIP(normalized);
  return family !== 0 && addresses.check(normalized, family === 4 ? 'ipv4' : 'ipv6');
}

export class SystemDirectAddressProvider implements DirectAddressProvider {
  readonly id = 'system-interfaces';
  constructor(
    private readonly interfaces: () => NodeJS.Dict<NetworkInterfaceInfo[]> = networkInterfaces,
  ) {}
  async addresses(): Promise<readonly DirectAddress[]> {
    return Object.entries(this.interfaces()).flatMap(([name, entries]) =>
      (entries ?? [])
        .filter(
          (entry) =>
            !entry.internal &&
            isLocalLanAddress(entry.address) &&
            // Link-local IPv6 scope IDs belong to this computer, not the phone.
            !entry.address.includes('%') &&
            !/^fe[89ab]/i.test(entry.address),
        )
        .map((entry) => ({
          host: entry.address,
          networkKind:
            /^(utun|tun|tap|tailscale|wg|ppp)/i.test(name) ||
            /^100\.(?:6[4-9]|[7-9]\d|1[01]\d|12[0-7])\./.test(entry.address)
              ? ('vpn' as const)
              : /^(en\d|eth\d|wlan|wi-fi|ethernet)/i.test(name)
                ? ('lan' as const)
                : ('unknown' as const),
        })),
    );
  }
}

const execute = promisify(execFile);
export class TailscaleDirectAddressProvider implements DirectAddressProvider {
  readonly id = 'tailscale';
  constructor(
    private readonly options: {
      platform?: NodeJS.Platform;
      executable?: string;
      run?: (executable: string) => Promise<string>;
    } = {},
  ) {}
  private async status(): Promise<Record<string, unknown> | undefined> {
    const platform = this.options.platform ?? process.platform;
    const candidates = this.options.executable
      ? [this.options.executable]
      : platform === 'darwin'
        ? [
            '/Applications/Tailscale.app/Contents/MacOS/Tailscale',
            '/opt/homebrew/bin/tailscale',
            '/usr/local/bin/tailscale',
          ]
        : platform === 'win32'
          ? [
              path.join(
                process.env.ProgramFiles ?? 'C:\\Program Files',
                'Tailscale',
                'tailscale.exe',
              ),
              'tailscale.exe',
            ]
          : ['tailscale'];
    let serialized: string | undefined;
    for (const executable of candidates) {
      try {
        if (path.isAbsolute(executable) && !this.options.run) await access(executable);
        serialized = this.options.run
          ? await this.options.run(executable)
          : (
              await withWindowsProcessCreation(5000, (timeout) =>
                execute(executable, ['status', '--json'], {
                  env: { ...process.env, TAILSCALE_BE_CLI: '1' },
                  timeout,
                  maxBuffer: 512 * 1024,
                  windowsHide: true,
                  encoding: 'utf8',
                }),
              )
            ).stdout;
        break;
      } catch (error) {
        /* An unavailable CLI does not disable system-interface discovery. */
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') return undefined;
      }
    }
    if (serialized === undefined || Buffer.byteLength(serialized) > 512 * 1024) return undefined;
    const value: unknown = JSON.parse(serialized);
    if (!value || typeof value !== 'object') return undefined;
    const status = value as Record<string, unknown>;
    return status.BackendState === 'Running' ? status : undefined;
  }
  async addresses(): Promise<readonly DirectAddress[]> {
    const status = await this.status();
    if (!status) return [];
    const self =
      status.Self && typeof status.Self === 'object'
        ? (status.Self as Record<string, unknown>)
        : {};
    const ips = self.TailscaleIPs ?? status.TailscaleIPs;
    const result: DirectAddress[] = Array.isArray(ips)
      ? ips
          .filter(
            (ip): ip is string =>
              typeof ip === 'string' && isLocalLanAddress(ip) && !ip.includes('%'),
          )
          .map((host) => ({ host, networkKind: 'vpn' }))
      : [];
    const dns =
      typeof self.DNSName === 'string' ? self.DNSName.toLowerCase().replace(/\.$/, '') : '';
    if (dns.length <= 253 && /^[a-z0-9.-]+\.ts\.net$/.test(dns))
      result.push({ host: dns, networkKind: 'vpn' });
    return result;
  }
  async peers(): Promise<VPNPeer[]> {
    const status = await this.status();
    if (!status) return [];
    const peers = status.Peer && typeof status.Peer === 'object' ? Object.values(status.Peer) : [];
    return [status.Self, ...peers]
      .flatMap((entry: unknown): VPNPeer[] => {
        if (!entry || typeof entry !== 'object') return [];
        const peer = entry as Record<string, unknown>;
        if (peer.Online !== true || !['macOS', 'windows', 'linux'].includes(String(peer.OS)))
          return [];
        const addresses = Array.isArray(peer.TailscaleIPs)
          ? peer.TailscaleIPs.filter(
              (ip): ip is string => typeof ip === 'string' && isTailscaleAddress(ip),
            )
          : [];
        if (!addresses.length) return [];
        const name =
          typeof peer.HostName === 'string' && peer.HostName.length
            ? peer.HostName.slice(0, 256)
            : 'VPN 电脑';
        // Export only routable peer hints. Node keys, accounts, public paths and credentials stay local.
        return [{ name, addresses: [...new Set(addresses)].slice(0, 2) }];
      })
      .slice(0, 32);
  }
}

const tailscaleAddresses = new BlockList();
tailscaleAddresses.addSubnet('100.64.0.0', 10, 'ipv4');
tailscaleAddresses.addSubnet('fd7a:115c:a1e0::', 48, 'ipv6');
export function isTailscaleAddress(host: string): boolean {
  const family = isIP(host);
  return family !== 0 && tailscaleAddresses.check(host, family === 4 ? 'ipv4' : 'ipv6');
}

export class VPNPeerDirectory {
  private cached: VPNPeer[] = [];
  private refreshedAt = 0;
  private pending: Promise<VPNPeer[]> | undefined;
  constructor(private readonly provider = new TailscaleDirectAddressProvider()) {}
  async peers(): Promise<VPNPeer[]> {
    if (this.pending) return this.pending;
    if (Date.now() - this.refreshedAt < 15000) return this.cached;
    this.pending = this.provider
      .peers()
      .catch(() => [])
      .then((peers) => {
        this.cached = peers;
        this.refreshedAt = Date.now();
        return peers;
      })
      .finally(() => {
        this.pending = undefined;
      });
    return this.pending;
  }
}

export class DirectEndpointRegistry {
  #providers = new Map<string, DirectAddressProvider>();
  constructor(
    providers: readonly DirectAddressProvider[] = [
      new SystemDirectAddressProvider(),
      new TailscaleDirectAddressProvider(),
    ],
  ) {
    for (const provider of providers) this.register(provider);
  }
  register(provider: DirectAddressProvider): void {
    this.#providers.set(provider.id, provider);
  }
  async resolve(options: {
    port: number;
    ipv6: boolean;
    bindAddress?: string;
    includeLoopback?: boolean;
  }): Promise<DirectEndpoint[]> {
    const results = await Promise.allSettled(
      [...this.#providers.values()].map((provider) => provider.addresses()),
    );
    const candidates = results.flatMap((result) =>
      result.status === 'fulfilled' ? [...result.value] : [],
    );
    const bind = options.bindAddress;
    const restricted = bind && !['0.0.0.0', '::'].includes(bind);
    if (options.includeLoopback || (restricted && isLoopbackAddress(bind)))
      candidates.push({ host: restricted ? bind : '127.0.0.1', networkKind: 'unknown' });
    const endpoints = new Map<string, DirectEndpoint>();
    for (const candidate of candidates) {
      const host = candidate.host.toLowerCase().replace(/\.$/, '');
      const family = isIP(host);
      if (isLoopbackAddress(host) && !options.includeLoopback && !restricted) continue;
      if ((restricted && host !== bind) || (family === 6 && !options.ipv6)) continue;
      if (!isLocalLanAddress(host) && !/^[a-z0-9.-]+\.ts\.net$/.test(host)) continue;
      const endpoint = `https://${family === 6 ? `[${host}]` : host}:${options.port}`;
      const previous = endpoints.get(endpoint);
      if (!previous || candidate.networkKind === 'vpn')
        endpoints.set(endpoint, { endpoint, networkKind: candidate.networkKind });
    }
    const priority = { lan: 0, unknown: 1, vpn: 2 };
    return [...endpoints.values()]
      .sort(
        (a, b) =>
          priority[a.networkKind] - priority[b.networkKind] || a.endpoint.localeCompare(b.endpoint),
      )
      .slice(0, 32);
  }
}
