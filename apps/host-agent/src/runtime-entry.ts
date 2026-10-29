import { startRuntimeServer } from './runtime-server.js';
import { startOptionalHostResponsesProxy } from './responses-proxy.js';
await startRuntimeServer();
await startOptionalHostResponsesProxy();
