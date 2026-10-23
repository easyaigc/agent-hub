import type { RuntimeTarget } from './agent-management.js';

/** Private-network, read-only hints. A peer is not a connected or authorized Agent. */
export interface VPNDiscoveryPeer {
  name: string;
  addresses: string[];
}
export interface VPNDiscoverySnapshot {
  version: 1;
  target: RuntimeTarget;
  peers: VPNDiscoveryPeer[];
}
