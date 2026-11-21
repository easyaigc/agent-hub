import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { chmod, mkdtemp, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import { join } from 'node:path';
import { syncBuiltinESMExports } from 'node:module';
import { test } from 'node:test';
import { discoveryNetwork } from '../packages/protocol/dist/index.js';
import {
  SystemDirectAddressProvider,
  VPNPeerDirectory,
} from '../apps/host-agent/dist/direct-endpoints.js';

const entry = (address, netmask = '255.255.255.0', extra = {}) => ({
  address,
  netmask,
  family: 'IPv4',
  internal: false,
  mac: '00:00:00:00:00:00',
  cidr: null,
  ...extra,
});

test('network arithmetic preserves exact masks and rejects invalid ranges', () => {
  for (const [ip, mask, expected] of [
    ['10.22.17.9', '255.255.240.0', '10.22.16.0/20'],
    ['10.8.9.10', '255.0.0.0', '10.0.0.0/8'],
    ['172.16.0.0', '255.255.255.254', '172.16.0.0/31'],
    ['192.168.1.255', '255.255.255.255', '192.168.1.255/32'],
  ])
    assert.deepEqual(discoveryNetwork(ip, mask), { address: ip, cidr: expected });
  for (const [ip, mask] of [
    ['10.2.3.4', '255.0.255.0'],
    ['10.2.3.4', '255.255.255.256'],
    ['10.2.3.4', '0.0.0.0'],
    ['8.8.8.8', '255.255.255.0'],
    ['172.16.1.1', '255.224.0.0'],
    ['192.168.1.1', '255.254.0.0'],
    ['100.64.0.1', '255.255.255.0'],
    ['127.0.0.1', '255.0.0.0'],
    ['10.1.2.0', '255.255.255.0'],
    ['10.1.2.255', '255.255.255.0'],
    ['010.1.2.3', '255.255.255.0'],
  ])
    assert.equal(discoveryNetwork(ip, mask), undefined, `${ip} ${mask}`);
});

test('system provider only exposes eligible LAN IPv4 networks without truncation', async () => {
  const fixtures = Object.fromEntries(
    Array.from({ length: 40 }, (_, i) => [`en${i}`, [entry(`10.${i}.17.9`, '255.255.240.0')]]),
  );
  Object.assign(fixtures, {
    eth40: [entry('172.16.0.0', '255.255.255.254')],
    wlan0: [entry('192.168.1.255', '255.255.255.255')],
    'Wi-Fi': [entry('10.5.0.0', '255.0.0.0')],
    ethernet0: [
      entry('10.1.2.3', '255.0.255.0'),
      entry('8.8.8.8'),
      entry('100.64.0.1'),
      entry('127.0.0.1'),
    ],
    en41: [
      entry('10.1.2.3', '255.255.255.0', { internal: true }),
      entry('::1', 'ffff:ffff::', { family: 'IPv6' }),
    ],
    utun0: [entry('10.1.2.3')],
    tun0: [entry('10.1.2.3')],
    tap0: [entry('10.1.2.3')],
    ppp0: [entry('10.1.2.3')],
    bridge0: [entry('10.1.2.3')],
    awdl0: [entry('10.1.2.3')],
    veth0: [entry('10.1.2.3')],
    vmnet0: [entry('10.1.2.3')],
    mystery0: [entry('10.2.3.4')],
    以太网: [entry('192.168.30.17', '255.255.254.0')],
    'Office USB Adapter': [entry('172.20.17.9', '255.255.240.0')],
    'vEthernet (Default Switch)': [entry('10.1.2.3')],
    'VirtualBox Host-Only Ethernet Adapter': [entry('10.1.2.3')],
  });
  const provider = new SystemDirectAddressProvider(() => fixtures);
  const networks = provider.networks();
  assert.equal(networks.length, 46);
  assert.deepEqual(networks[0], { address: '10.0.17.9', cidr: '10.0.16.0/20' });
  assert.ok(networks.some((x) => x.cidr === '10.0.0.0/8'));
  assert.ok(networks.some((x) => x.cidr === '172.16.0.0/31'));
  assert.ok(networks.some((x) => x.cidr === '192.168.1.255/32'));
  assert.ok(networks.some((x) => x.address === '10.2.3.4'));
  assert.ok(networks.some((x) => x.address === '192.168.30.17' && x.cidr === '192.168.30.0/23'));
  assert.ok(networks.some((x) => x.address === '172.20.17.9' && x.cidr === '172.20.16.0/20'));
  assert.equal(
    networks.some((x) => x.address === '10.1.2.3'),
    false,
  );
  assert.deepEqual(
    (await provider.addresses()).find((x) => x.host === '10.2.3.4'),
    { host: '10.2.3.4', networkKind: 'unknown' },
  );
});

test(
  'runtime HTTP enforces authorization and keeps network and VPN peer snapshots separate',
  { timeout: 30000 },
  async () => {
    const root = await mkdtemp(join(os.tmpdir(), 'discovery-networks-'));
    const executable = join(root, 'codex-fixture.cjs');
    const token = randomUUID() + randomUUID();
    const originalInterfaces = os.networkInterfaces;
    const originalPeers = VPNPeerDirectory.prototype.peers;
    const originalEnv = { ...process.env };
    let service;
    try {
      await writeFile(
        executable,
        `#!${process.execPath}\nrequire('node:readline').createInterface({input:process.stdin}).on('line',line=>{const m=JSON.parse(line);let result={};if(m.method==='initialize')result={userAgent:'discovery-fixture'};else if(m.method==='thread/list')result={data:[],nextCursor:null};if(m.id!=null)process.stdout.write(JSON.stringify({id:m.id,result})+'\\n')});\n`,
      );
      await chmod(executable, 0o700);
      os.networkInterfaces = () => ({ en0: [entry('10.22.17.9', '255.255.240.0')] });
      syncBuiltinESMExports();
      VPNPeerDirectory.prototype.peers = async () => [
        { name: 'synthetic', addresses: ['100.64.0.7'] },
      ];
      Object.assign(process.env, {
        CODEX_RUNTIME_MODE: 'managed-runtime',
        CODEX_EXECUTABLE: executable,
        HOST_AGENT_BIND: '127.0.0.1',
        HOST_AGENT_PORT: '0',
        HOST_AGENT_STATE_DIR: join(root, 'state'),
        HOST_AGENT_ROOT: root,
        HOST_AGENT_TOKEN: token,
        HOST_AGENT_TRANSFER_DIR: join(root, 'downloads'),
        HOST_AGENT_DISCOVERY: '0',
        HOST_AGENT_DISCOVERY_BONJOUR: '0',
      });
      const { startRuntimeServer } = await import('../apps/host-agent/dist/runtime-server.js');
      service = await startRuntimeServer();
      const base = `http://127.0.0.1:${service.server.address().port}`;
      const hostId = service.session.hostId;
      const target = service.session.readingTarget();
      const query = `?hostId=${encodeURIComponent(hostId)}`;
      const get = (path, options = {}) =>
        fetch(base + path, {
          ...options,
          headers: { Authorization: `Bearer ${token}`, ...options.headers },
        });
      let response = await get('/v1/discovery/networks' + query);
      assert.equal(response.status, 200);
      assert.deepEqual(await response.json(), {
        version: 1,
        target,
        networks: [{ address: '10.22.17.9', cidr: '10.22.16.0/20' }],
      });
      assert.equal((await fetch(base + '/v1/discovery/networks' + query)).status, 401);
      assert.equal(
        (
          await get('/v1/discovery/networks' + query, {
            headers: { Authorization: 'Bearer incorrect' },
          })
        ).status,
        401,
      );
      assert.equal((await get('/v1/discovery/networks?hostId=wrong')).status, 400);
      assert.equal((await get('/v1/discovery/networks' + query + '&extra=1')).status, 400);
      assert.equal((await get('/v1/discovery/networks' + query + '&hostId=' + hostId)).status, 400);
      assert.equal(
        (
          await get('/v1/discovery/networks' + query, {
            headers: { Origin: 'https://untrusted.example' },
          })
        ).status,
        403,
      );
      assert.notEqual(
        (await get('/v1/discovery/networks' + query, { method: 'POST' })).status,
        200,
      );
      response = await get('/v1/discovery/peers' + query);
      assert.equal(response.status, 200);
      assert.deepEqual(await response.json(), {
        version: 1,
        target,
        peers: [{ name: 'synthetic', addresses: ['100.64.0.7'] }],
      });
    } finally {
      service?.close();
      os.networkInterfaces = originalInterfaces;
      syncBuiltinESMExports();
      VPNPeerDirectory.prototype.peers = originalPeers;
      for (const key of Object.keys(process.env))
        if (!(key in originalEnv)) delete process.env[key];
      Object.assign(process.env, originalEnv);
      await rm(root, { recursive: true, force: true });
    }
  },
);
