import { spawn } from 'node:child_process';
import { mkdir } from 'node:fs/promises';

const device = process.env.IOS_SIMULATOR_ID;
const destination = device
  ? `platform=iOS Simulator,id=${device}`
  : 'platform=iOS Simulator,name=iPhone 17 Pro';
const args = [
  '-project',
  'apps/ios/CodexManage.xcodeproj',
  '-scheme',
  'CodexManage',
  '-destination',
  destination,
  '-derivedDataPath',
  '.build/ios',
  '-resultBundlePath',
  `.local/ios-acceptance-${Date.now()}.xcresult`,
  'CODE_SIGNING_ALLOWED=YES',
  'CODE_SIGN_IDENTITY=-',
  '-parallel-testing-enabled',
  'NO',
  '-skip-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopReplyProgressStopAndApproval',
  '-skip-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopConversationSwitchAndDraftIsolation',
  '-skip-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopPhotoFileUploadAndDownload',
  'test',
];
await mkdir('.local', { recursive: true });
const child = spawn('xcodebuild', args, { stdio: 'inherit', env: process.env });
child.once('error', (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
child.once('exit', (code) => {
  process.exitCode = code ?? 1;
});
for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => child.kill(signal));
