import { spawn } from 'node:child_process';

// Native interaction with synthetic inputs; not microphone, ASR or upstream quota proof.
const child = spawn(
  process.execPath,
  [
    'scripts/mobile/native-ios-cases.mjs',
    'testRecordingDraftTapStopsRecording',
    'testRecordingStatusTapStopsRecording',
    'testWaveformTapStopsRecordingAndFinishingTapsDoNotDuplicate',
    'testFailureStatusTapDoesNotRetryTranscription',
  ],
  { stdio: 'inherit', env: { ...process.env, IOS_TEST_CLASS: 'VoiceLifecycleUITests' } },
);
child.once('error', (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
child.once('exit', (code) => {
  process.exitCode = code ?? 1;
});
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => child.kill(signal));
