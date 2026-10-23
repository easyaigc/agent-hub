import { spawn, execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { readFile, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
let local = {};
try {
  local = JSON.parse(await readFile(resolve(root, '.local/mobile-toolchain.json'), 'utf8'));
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
const env = { ...process.env };
env.GRADLE_USER_HOME ??= resolve(root, '.local/gradle-home');
env.JAVA_HOME ??= local.javaHome;
if (!env.JAVA_HOME && process.platform === 'darwin')
  env.JAVA_HOME = execFileSync('/usr/libexec/java_home', ['-v', '17'], { encoding: 'utf8' }).trim();
const sdk =
  env.ANDROID_HOME ??
  env.ANDROID_SDK_ROOT ??
  local.androidSdk ??
  (process.platform === 'darwin' ? resolve(homedir(), 'Library/Android/sdk') : undefined);
if (sdk && existsSync(sdk)) {
  env.ANDROID_HOME = sdk;
  await writeFile(
    resolve(root, 'local.properties'),
    'sdk.dir=' + sdk.replaceAll('\\', '\\\\').replaceAll(':', '\\:') + '\n',
  );
}
const args = process.argv.slice(2);
const nativeHome = env.KOTLIN_NATIVE_HOME ?? local.kotlinNativeHome;
if (nativeHome) args.push('-Pkotlin.native.home=' + nativeHome);
const child = spawn(resolve(root, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew'), args, {
  cwd: root,
  env,
  stdio: 'inherit',
  shell: process.platform === 'win32',
});
child.once('error', (error) => {
  console.error(error.message);
  process.exitCode = 1;
});
child.once('close', (code) => {
  process.exitCode = code ?? 1;
});
