import { spawn } from 'node:child_process';

// Exercise the real SwiftUI entry point, detail lifecycle and shared tool rows.
// Synthetic demo data is auxiliary UI evidence, never a live desktop proof.
const child = spawn(
  process.execPath,
  [
    'scripts/mobile/native-ios-cases.mjs',
    'testLongChildDetailOpensClosesAndReopensWithOriginalText',
    'testClosingNestedChildDetailPreservesParentPresentation',
    'testStructuredChildTimelineToolGroupAndCommandOutput',
    'testNativeLongPressSelectAndCopyChildDetail',
  ],
  { stdio: 'inherit', env: { ...process.env, IOS_TEST_CLASS: 'EasyWorkSubagentReadingUITests' } },
);
child.once('error', (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
child.once('exit', (code) => {
  process.exitCode = code ?? 1;
});
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => child.kill(signal));
