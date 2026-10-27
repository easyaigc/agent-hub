import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';

// Runs the production native parser. UI, desktop and physical-device proof are separate.
if (process.platform !== 'darwin') {
  console.log('Native Markdown parser tests skipped: macOS and Xcode are required.');
} else {
  const child = spawn(
    'xcrun',
    [
      'swift',
      'test',
      '--package-path',
      'apps/ios/FilePreviewKit',
      '--scratch-path',
      '.local/preview-kit-build',
      '--disable-index-store',
      '-j',
      '2',
    ],
    {
      stdio: 'inherit',
      env: {
        ...process.env,
        DEVELOPER_DIR:
          !process.env.DEVELOPER_DIR || process.env.DEVELOPER_DIR.endsWith('/CommandLineTools')
            ? '/Applications/Xcode.app/Contents/Developer'
            : process.env.DEVELOPER_DIR,
      },
    },
  );
  const code = await new Promise((done, reject) => {
    child.once('error', reject);
    child.once('close', done);
  });
  assert.equal(code, 0, 'Native file preview parser checks failed');
}
