import {
  DesktopDomBridge,
  readDesktopDomBridgeConfig,
} from '../packages/codex-adapter/dist/index.js';

// Local read-only bridge probe. Config pins an existing endpoint, page and document origin.
// It neither enables debugging nor launches or changes the inspected application.
const [configPath, threadId, ...extra] = process.argv.slice(2);
if (!configPath || !threadId || extra.length) {
  console.error('Usage: node scripts/desktop-dom-suggestion-probe.mjs PRIVATE_CONFIG THREAD_ID');
  process.exitCode = 1;
} else {
  try {
    const bridge = new DesktopDomBridge(readDesktopDomBridgeConfig(configPath));
    console.log(JSON.stringify(await bridge.readExisting(threadId), null, 2));
  } catch (error) {
    console.error(JSON.stringify({ code: error.code ?? 'read-failed', error: error.message }));
    process.exitCode = 1;
  }
}
