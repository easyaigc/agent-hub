import { runDesktopStdioBridge } from '@codex-manage/codex-adapter';

await runDesktopStdioBridge().catch(() => {
  // Do not print configuration, protocol, tokens or original desktop messages.
  process.stderr.write('Codex Manage transport PoC could not start.\n');
  process.exitCode = 1;
});
