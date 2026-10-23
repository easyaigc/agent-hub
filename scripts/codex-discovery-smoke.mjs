import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import https from 'node:https';
import Ajv from 'ajv';
import { startDirectDiscovery } from '../apps/host-agent/dist/direct-discovery.js';
import {
  DirectEndpointRegistry,
  TailscaleDirectAddressProvider,
  VPNPeerDirectory,
  isLocalLanAddress,
} from '../apps/host-agent/dist/direct-endpoints.js';

for (const address of [
  '100.64.0.0',
  '100.127.255.255',
  '::ffff:6440:0',
  '::ffff:100.127.255.255',
  'fc00::1',
])
  assert.equal(isLocalLanAddress(address), true, address);
for (const address of ['100.63.255.255', '100.128.0.0', '::ffff:6480:0', '8.8.8.8', '2001:4860::1'])
  assert.equal(isLocalLanAddress(address), false, address);
let addresses = [
  { host: '192.168.3.2', networkKind: 'lan' },
  { host: '100.64.0.1', networkKind: 'vpn' },
  { host: 'fd00::1', networkKind: 'vpn' },
  { host: 'office.test.ts.net', networkKind: 'vpn' },
];
const registry = new DirectEndpointRegistry([
  { id: 'fixture', addresses: async () => addresses },
  {
    id: 'failed',
    addresses: async () => {
      throw Error('unavailable');
    },
  },
]);
const ipv4 = await registry.resolve({ port: 8796, ipv6: false });
assert.equal(ipv4.length, 3);
assert.ok(ipv4.some((row) => row.endpoint === 'https://office.test.ts.net:8796'));
assert.equal((await registry.resolve({ port: 8796, ipv6: true })).length, 4);
for (const payload of [
  { BackendState: 'Stopped', Self: { TailscaleIPs: ['100.64.0.1'] } },
  {
    BackendState: 'Running',
    Self: { TailscaleIPs: ['100.64.0.1'], DNSName: 'office.test.ts.net.' },
    Peer: { private: 'must-not-export' },
  },
]) {
  const found = await new TailscaleDirectAddressProvider({
    run: async () => JSON.stringify(payload),
  }).addresses();
  assert.equal(found.length, payload.BackendState === 'Running' ? 2 : 0);
  assert.equal(JSON.stringify(found).includes('must-not-export'), false);
}
assert.deepEqual(
  await new TailscaleDirectAddressProvider({
    run: async () => {
      throw Object.assign(Error('timeout'), { code: 'ETIMEDOUT' });
    },
  }).addresses(),
  [],
);
let directoryReads = 0;
const peerProvider = new TailscaleDirectAddressProvider({
  run: async () => {
    directoryReads++;
    return JSON.stringify({
      BackendState: 'Running',
      Self: { OS: 'macOS', Online: true, HostName: 'Anchor', TailscaleIPs: ['100.64.0.1'] },
      Peer: {
        desktop: {
          OS: 'windows',
          Online: true,
          HostName: 'Desktop',
          TailscaleIPs: ['100.127.255.255', 'fd7a:115c:a1e0::1', '8.8.8.8'],
          PublicKey: 'must-not-export',
        },
        phone: { OS: 'iOS', Online: true, HostName: 'Phone', TailscaleIPs: ['100.64.0.2'] },
        offline: { OS: 'macOS', Online: false, TailscaleIPs: ['100.64.0.3'] },
        bad: { OS: 'linux', Online: true, TailscaleIPs: ['192.168.1.1', 'fd00::1', '100.128.0.0'] },
      },
    });
  },
});
const peerDirectory = new VPNPeerDirectory(peerProvider);
const directories = await Promise.all(Array.from({ length: 8 }, () => peerDirectory.peers()));
assert.equal(directoryReads, 1, 'Concurrent discovery must share one fixed CLI invocation');
assert.deepEqual(directories[0], [
  { name: 'Anchor', addresses: ['100.64.0.1'] },
  { name: 'Desktop', addresses: ['100.127.255.255', 'fd7a:115c:a1e0::1'] },
]);
assert.equal(JSON.stringify(directories).includes('must-not-export'), false);
await peerDirectory.peers();
assert.equal(directoryReads, 1, 'Repeated requests use a bounded snapshot');
assert.deepEqual(
  await new TailscaleDirectAddressProvider({
    run: async () => {
      throw Object.assign(Error('missing'), { code: 'ENOENT' });
    },
  }).addresses(),
  [],
);
const directory = await mkdtemp(join(tmpdir(), 'codex-discovery-'));
const target = {
  agentId: 'codex',
  hostId: 'host',
  workspaceId: 'host',
  runtimeId: 'desktop',
  account: { authority: 'fixture', subject: 'owner' },
};
const token = 'fixture-secret-'.repeat(3);
let service;
const read = (port, path = '/host', headers = {}) =>
  new Promise((done, reject) => {
    https
      .get({ host: '127.0.0.1', port, path, headers, rejectUnauthorized: false }, (response) => {
        let value = '';
        response.on('data', (chunk) => (value += chunk));
        response.on('end', () => done({ status: response.statusCode, value }));
      })
      .on('error', reject);
  });
try {
  const options = {
    directory,
    target: () => target,
    name: 'Fixture Codex',
    token,
    port: 0,
    bind: '127.0.0.1',
    registry,
    peers: () => peerDirectory.peers(),
    handler: async (_req, res) => {
      res.writeHead(401);
      res.end();
    },
  };
  service = await startDirectDiscovery(options);
  const port = service.server.address().port;
  const before = service.descriptor();
  const schema = JSON.parse(
    await readFile('packages/protocol/schemas/codex-host-discovery-v1.schema.json', 'utf8'),
  );
  const validate = new Ajv().compile(schema);
  assert.equal(validate(before), true, JSON.stringify(validate.errors));
  assert.equal(
    validate({ ...before, token }),
    false,
    'The public descriptor must not include a credential',
  );
  assert.equal((await read(port)).status, 200);
  assert.equal((await read(port, 'http://[')).status, 400);
  assert.equal((await read(port)).value.includes(token), false);
  const unpaired = await read(port, '/discovery/peers?hostId=host');
  assert.equal(unpaired.status, 200);
  assert.deepEqual(JSON.parse(unpaired.value), { version: 1, target, peers: directories[0] });
  for (const query of ['', '?hostId=other', '?hostId=host&hostId=host', '?hostId=host&extra=1'])
    assert.equal((await read(port, '/discovery/peers' + query)).status, 400);
  assert.equal(
    (await read(port, '/discovery/peers?hostId=host', { Origin: 'https://untrusted.example' }))
      .status,
    403,
  );
  assert.equal((await read(port, '/v1/workspace')).status, 401);
  assert.equal((await read(port, '/host', { Origin: 'https://untrusted.example' })).status, 403);
  assert.equal((await stat(service.pagePath)).mode & 0o777, 0o600);
  assert.ok((await readFile(service.pagePath, 'utf8')).includes('<svg'));
  addresses = [];
  await service.refresh();
  assert.equal(service.descriptor().fingerprint, before.fingerprint);
  assert.deepEqual(service.descriptor().target, before.target);
  service.close();
  service = await startDirectDiscovery(options);
  assert.equal(service.descriptor().fingerprint, before.fingerprint);
  console.log(
    JSON.stringify({
      passed: true,
      scenarios: [
        'CGNAT and mapped IPv6',
        'IPv4 fallback advertisement',
        'Tailscale self-only and missing/timeout',
        'online desktop VPN peer hints without private metadata',
        'peer bounds and coalesced cached reads',
        'read-only no credentials',
        'authenticated command boundary',
        'private QR file',
        'address refresh',
        'certificate restart preservation',
      ],
    }),
  );
} finally {
  service?.close();
  await rm(directory, { recursive: true, force: true });
}
