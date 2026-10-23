import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import path from 'node:path';

// Read-only inventory. This does not accept licenses, install packages, start
// emulators, contact Hosts, or claim that product acceptance has passed.
const args = new Set(process.argv.slice(2));
if ([...args].some((arg) => !['--json', '--ios', '--android'].includes(arg))) {
  console.error('Usage: pnpm check:mobile-env [--json] [--ios | --android]');
  process.exit(2);
}
const checkIOS = !args.has('--android') || args.has('--ios');
const checkAndroid = !args.has('--ios') || args.has('--android');
const result = { scope: 'development-environment-only', checks: [] };
function command(executable, parameters, env = process.env) {
  const value = spawnSync(executable, parameters, {
    encoding: 'utf8',
    timeout: 30_000,
    maxBuffer: 2 * 1024 * 1024,
    env,
  });
  return {
    ok: !value.error && value.status === 0,
    stdout: (value.stdout || '').trim(),
    text: ((value.stdout || '') + (value.stderr || '')).trim(),
  };
}
function check(name, ok, detail) {
  result.checks.push({ name, ok: Boolean(ok), detail });
}
function entries(directory) {
  return existsSync(directory) ? readdirSync(directory).sort() : [];
}

check('Node.js', process.versions.node.startsWith('24.'), process.versions.node);
const pnpm = command('pnpm', ['--version']);
check('pnpm', pnpm.ok && pnpm.text === '11.19.0', pnpm.text || 'Unavailable');

if (checkIOS) {
  const selected = command('xcode-select', ['-p']);
  const developer = process.env.DEVELOPER_DIR || (selected.ok ? selected.text : '');
  const env = { ...process.env, ...(developer ? { DEVELOPER_DIR: developer } : {}) };
  const xcode = command('xcodebuild', ['-version'], env);
  check('Full Xcode', xcode.ok && /^Xcode /m.test(xcode.text), xcode.text || 'Unavailable');
  const sdk = command('xcrun', ['--sdk', 'iphonesimulator', '--show-sdk-version'], env);
  check('iOS Simulator SDK', sdk.ok, sdk.text || 'Unavailable');
  const runtimes = command('xcrun', ['simctl', 'list', 'runtimes', '--json'], env);
  let available = [];
  if (runtimes.ok) {
    try {
      available = JSON.parse(runtimes.stdout).runtimes.filter(
        (runtime) => runtime.isAvailable && runtime.identifier.includes('.iOS-'),
      );
    } catch {
      // Treat malformed inventory as unavailable rather than as a ready runtime.
    }
  }
  check(
    'iOS Simulator runtime',
    available.length > 0,
    available.map((runtime) => `${runtime.name} (${runtime.buildversion})`).join(', ') ||
      'No available iOS runtime',
  );
}

if (checkAndroid) {
  const sdkRoot =
    process.env.ANDROID_HOME ||
    process.env.ANDROID_SDK_ROOT ||
    path.join(homedir(), 'Library', 'Android', 'sdk');
  const javaExecutable = process.env.JAVA_HOME
    ? path.join(process.env.JAVA_HOME, 'bin', 'java')
    : 'java';
  const java = command(javaExecutable, ['-version']);
  const major = Number(java.text.match(/(?:openjdk|java) version "(\d+)/)?.[1] || 0);
  check('Java JDK', java.ok && major >= 17, java.text.split('\n')[0] || 'Unavailable');
  const javac = command(
    process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'javac') : 'javac',
    ['-version'],
  );
  check('Java compiler', javac.ok, javac.text || 'Unavailable');
  check(
    'Android SDK',
    existsSync(sdkRoot),
    existsSync(sdkRoot) ? 'SDK directory present' : 'Missing',
  );
  const cli = path.join(sdkRoot, 'cmdline-tools', 'latest', 'bin', 'sdkmanager');
  const properties = path.join(sdkRoot, 'cmdline-tools', 'latest', 'source.properties');
  const cliRevision = existsSync(properties)
    ? readFileSync(properties, 'utf8').match(/^Pkg.Revision=(.+)$/m)?.[1]
    : undefined;
  check('Android command-line tools', existsSync(cli) && cliRevision, cliRevision || 'Unavailable');
  const androidCLI = command(path.join(sdkRoot, 'cmdline-tools', 'latest', 'bin', 'android'), [
    '--no-metrics',
    '--version',
  ]);
  check('Android CLI', androidCLI.ok, androidCLI.stdout || 'Unavailable');
  const platforms = entries(path.join(sdkRoot, 'platforms')).filter((entry) =>
    existsSync(path.join(sdkRoot, 'platforms', entry, 'android.jar')),
  );
  check('Android SDK platform', platforms.length > 0, platforms.join(', ') || 'Missing');
  const tools = entries(path.join(sdkRoot, 'build-tools')).filter((entry) =>
    existsSync(path.join(sdkRoot, 'build-tools', entry, 'aapt2')),
  );
  check('Android build tools', tools.length > 0, tools.join(', ') || 'Missing');
  const adb = command(path.join(sdkRoot, 'platform-tools', 'adb'), ['version']);
  check('ADB', adb.ok, adb.text.split('\n')[0] || 'Unavailable');
  const emulator = command(path.join(sdkRoot, 'emulator', 'emulator'), ['-version']);
  check('Android Emulator', emulator.ok, emulator.text.split('\n')[0] || 'Unavailable');
  const avds = command(path.join(sdkRoot, 'emulator', 'emulator'), ['-list-avds']);
  check('Android virtual device', avds.ok && avds.stdout.length > 0, avds.stdout || 'No AVD');
  const licenseFile = path.join(sdkRoot, 'licenses', 'android-sdk-license');
  check(
    'Android SDK license record',
    existsSync(licenseFile) && readFileSync(licenseFile, 'utf8').trim().length > 0,
    existsSync(licenseFile)
      ? 'Present; package-specific licenses checked at installation'
      : 'Missing',
  );
  const studio = path.join('/Applications', 'Android Studio.app', 'Contents', 'Info.plist');
  check('Android Studio', existsSync(studio), existsSync(studio) ? 'Installed' : 'Not installed');
}

result.ready = result.checks.every((item) => item.ok);
if (args.has('--json')) {
  console.log(JSON.stringify(result, null, 2));
} else {
  for (const item of result.checks) {
    console.log(`${item.ok ? 'PASS' : 'MISSING'} ${item.name}: ${item.detail}`);
  }
  console.log(
    result.ready
      ? 'Requested development tools are available. Build, launch and product acceptance are separate.'
      : 'Development environment is incomplete; see missing checks above.',
  );
}
process.exitCode = result.ready ? 0 : 1;
