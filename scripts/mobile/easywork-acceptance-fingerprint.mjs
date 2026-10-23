import { createHash } from 'node:crypto';
import { execFile } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { promisify } from 'node:util';

const run = promisify(execFile);
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');

/** Private evidence uses relative source names; credentials and runtime exports are excluded. */
export async function easyWorkAcceptanceFingerprint({ desktopRoot, cwd = process.cwd() }) {
  const sources = async (root, include) => {
    const listed = await run('git', ['ls-files', '-co', '--exclude-standard', '-z'], {
      cwd: root,
      maxBuffer: 8 * 1024 * 1024,
    });
    const files = [...new Set(listed.stdout.split('\0'))].filter(include).sort();
    return Object.fromEntries(
      await Promise.all(
        files.map(async (file) => [file, sha(await readFile(resolve(root, file)))]),
      ),
    );
  };
  const native = await sources(cwd, (file) =>
    /^(apps\/android\/(src\/main\/|build\.gradle)|packages\/mobile-core\/src\/|packages\/protocol\/(schemas\/|fixtures\/mobile\/)|gradle\/|settings\.gradle|build\.gradle|pnpm-lock\.yaml|package\.json)/u.test(
      file,
    ),
  );
  const examiner = await sources(cwd, (file) =>
    /^(apps\/android\/src\/androidTest\/|scripts\/mobile\/(android-easywork-live|easywork-live-host|easywork-acceptance-fingerprint)\.mjs)/u.test(
      file,
    ),
  );
  const desktop = await sources(
    desktopRoot,
    (file) =>
      /^(apps\/(?:desktop|remote-access-service)\/src\/|packages\/.+\/(src\/|package\.json)|package\.json|bun\.lock)/u.test(
        file,
      ) && /\.(?:ts|tsx|js|mjs|json|lock)$/u.test(file),
  );
  return {
    version: 1,
    native,
    examiner,
    desktop,
    productionSha256: sha(JSON.stringify({ native, desktop })),
    examinerSha256: sha(JSON.stringify(examiner)),
  };
}
