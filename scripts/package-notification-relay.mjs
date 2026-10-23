import { mkdir, cp, writeFile, readFile, mkdtemp, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';

const destination = resolve(process.argv[2] ?? '.local/notification-relay-bundle');
await mkdir(destination, { recursive: true });
const stage = await mkdtemp(join(destination, 'stage-'));
try {
  const root = join(stage, 'codex-notification-relay');
  for (const name of ['protocol', 'service-runtime', 'notification-runtime']) {
    const target = join(root, 'node_modules/@codex-manage', name);
    await mkdir(target, { recursive: true });
    await cp('packages/' + name + '/dist', join(target, 'dist'), { recursive: true });
    await cp('packages/' + name + '/package.json', join(target, 'package.json'));
  }
  await cp('apps/relay/dist', join(root, 'dist'), { recursive: true });
  await writeFile(
    join(root, 'package.json'),
    JSON.stringify(
      {
        name: 'codex-notification-relay',
        private: true,
        type: 'module',
        engines: { node: '>=24 <25' },
      },
      null,
      2,
    ) + '\n',
  );
  await cp('apps/relay/push.config.example.json', join(root, 'push.config.example.json'));
  await writeFile(
    join(root, 'codex-notifications.service'),
    `[Unit]\nDescription=Codex Manage notification relay\nAfter=network-online.target\nWants=network-online.target\n\n[Service]\nUser=codex-notifications\nGroup=codex-notifications\nWorkingDirectory=/opt/codex-notification-relay\nEnvironment=RELAY_PUSH_CONFIG=/etc/codex-notifications/private/push.json\nEnvironment=RELAY_BIND_ADDRESS=127.0.0.1\nEnvironment=RELAY_PORT=8787\nExecStart=/usr/bin/node /opt/codex-notification-relay/dist/index.js\nRestart=on-failure\nUMask=0077\nNoNewPrivileges=true\nPrivateTmp=true\nProtectSystem=strict\nProtectHome=true\nReadWritePaths=/var/lib/codex-notifications\n\n[Install]\nWantedBy=multi-user.target\n`,
  );
  const archive = join(destination, 'codex-notification-relay.tar.gz');
  execFileSync('tar', ['-czf', archive, '-C', stage, 'codex-notification-relay']);
  const sha256 = createHash('sha256')
    .update(await readFile(archive))
    .digest('hex');
  await writeFile(archive + '.sha256', sha256 + '  codex-notification-relay.tar.gz\n');
  console.log(JSON.stringify({ archive, sha256, credentialsIncluded: false }));
} finally {
  await rm(stage, { recursive: true, force: true });
}
