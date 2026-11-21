import type { RuntimeTarget } from './agent-management.js';

/** A network observed on this authenticated Host, not a VPN route or access grant. */
export interface DiscoveryNetwork {
  address: string;
  cidr: string;
}
export interface DiscoveryNetworksSnapshot {
  version: 1;
  target: RuntimeTarget;
  networks: DiscoveryNetwork[];
}

/** Return only the exact, contiguous RFC1918 interface network. Never infer /24. */
export function discoveryNetwork(address: string, netmask: string): DiscoveryNetwork | undefined {
  const ipv4 = (text: string): number | undefined => {
    const parts = text.split('.');
    if (
      parts.length !== 4 ||
      parts.some((part) => !/^(0|[1-9]\d{0,2})$/.test(part) || Number(part) > 255)
    )
      return undefined;
    return parts.reduce((value, part) => value * 256 + Number(part), 0);
  };
  const ip = ipv4(address),
    mask = ipv4(netmask);
  if (ip === undefined || mask === undefined) return undefined;
  const hostBits = 0xffffffff - mask;
  if ((hostBits & (hostBits + 1)) !== 0) return undefined;
  const count = hostBits + 1;
  const network = Math.floor(ip / count) * count;
  const blocks = [
    [0x0a000000, 0x0affffff],
    [0xac100000, 0xac1fffff],
    [0xc0a80000, 0xc0a8ffff],
  ];
  if (!blocks.some(([first, last]) => network >= first! && network + hostBits <= last!))
    return undefined;
  // A broadcast/network address cannot seed a normal interface. /31 and /32
  // have no broadcast address and retain their existing supported semantics.
  if (count > 2 && (ip === network || ip === network + hostBits)) return undefined;
  const text = [24, 16, 8, 0].map((shift) => (network >>> shift) & 255).join('.');
  return { address, cidr: `${text}/${32 - Math.log2(count)}` };
}
