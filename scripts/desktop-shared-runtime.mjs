#!/usr/bin/env node
import { runDesktopSharedLauncher } from '../packages/codex-adapter/dist/desktop-shared-launcher.js';

// Explicit isolated DEV configuration only. Nothing installs or changes login startup here.
await runDesktopSharedLauncher(process.env.AGENT_HUB_CODEX_EXECUTABLE ?? '', process.argv.slice(2));
