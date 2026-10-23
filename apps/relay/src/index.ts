import { startHealthService } from '@codex-manage/service-runtime';
import { readFileSync } from 'node:fs';
import { startPushServer, assertPushPrivateFile, type PushConfiguration } from './push-server.js';

if (process.env.RELAY_PUSH_CONFIG) {
  assertPushPrivateFile(process.env.RELAY_PUSH_CONFIG);
  const config = JSON.parse(
    readFileSync(process.env.RELAY_PUSH_CONFIG, 'utf8'),
  ) as PushConfiguration;
  const service = await startPushServer(config);
  process.once('SIGTERM', service.close);
  process.once('SIGINT', service.close);
} else
  startHealthService({
    service: 'relay',
    portVariable: 'RELAY_PORT',
    addressVariable: 'RELAY_BIND_ADDRESS',
    defaultPort: 8787,
  });
