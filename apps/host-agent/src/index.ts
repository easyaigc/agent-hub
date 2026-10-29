import { startHealthService } from '@codex-manage/service-runtime';
import { startHistoryServer } from './history-server.js';
import { startRuntimeServer } from './runtime-server.js';
import { startOptionalHostResponsesProxy } from './responses-proxy.js';

if (process.env.CODEX_RUNTIME_MODE) {
  await startRuntimeServer();
  await startOptionalHostResponsesProxy();
} else if (process.env.CODEX_HISTORY_READ === '1') {
  await startHistoryServer();
} else
  startHealthService({
    service: 'host-agent',
    portVariable: 'HOST_AGENT_PORT',
    addressVariable: 'HOST_AGENT_BIND_ADDRESS',
    defaultPort: 8788,
  });
