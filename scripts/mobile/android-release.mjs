import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHash, randomBytes, X509Certificate } from 'node:crypto';
import { constants, existsSync } from 'node:fs';
import { lstat, mkdir, open, readFile, readdir, unlink } from 'node:fs/promises';
import { isAbsolute, join, resolve } from 'node:path';
import { parseEnv } from 'node:util';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const privateDir = join(root, '.local/android-release');
const configPath = join(root, '.env.android-release.local');
const filingPath = join(root, '.env.android-filing.local');
const applicationId = 'com.easyai.agenthub.android';
const signingNames = [
  'AGENT_HUB_ANDROID_KEYSTORE',
  'AGENT_HUB_ANDROID_STORE_PASSWORD',
  'AGENT_HUB_ANDROID_KEY_ALIAS',
  'AGENT_HUB_ANDROID_KEY_PASSWORD',
];

async function regularFile(path, privateFile = true) {
  const state = await lstat(path);
  assert(state.isFile() && !state.isSymbolicLink(), 'Expected a regular, non-symlink file');
  if (process.platform !== 'win32' && privateFile) {
    assert.equal(state.uid, process.getuid(), 'Private file belongs to another user');
    assert.equal(state.mode & 0o777, 0o600, 'Private file must have mode 0600');
  }
}

async function privateDirectory() {
  for (const path of [
    '.env.android-release.local',
    '.env.android-filing.local',
    '.local/android-release/signing.p12',
  ]) {
    const ignored = spawnSync('git', ['check-ignore', '-q', path], { cwd: root, stdio: 'ignore' });
    assert.equal(ignored.status, 0, 'Private release inputs must be ignored and untracked');
  }
  const parent = join(root, '.local');
  await mkdir(parent, { recursive: true });
  assert((await lstat(parent)).isDirectory(), 'Local state directory must not be a symlink');
  await mkdir(privateDir, { mode: 0o700, recursive: true });
  const state = await lstat(privateDir);
  assert(state.isDirectory() && !state.isSymbolicLink(), 'Release directory must not be a symlink');
  if (process.platform !== 'win32') {
    assert.equal(state.uid, process.getuid(), 'Release directory belongs to another user');
    assert.equal(state.mode & 0o777, 0o700, 'Release directory must have mode 0700');
  }
}

async function savePrivate(path, bytes, exclusive = false) {
  const handle = await open(
    path,
    constants.O_WRONLY |
      constants.O_CREAT |
      (exclusive ? constants.O_EXCL : 0) |
      (constants.O_NOFOLLOW ?? 0),
    0o600,
  );
  try {
    const state = await handle.stat();
    assert(state.isFile(), 'Output must be a regular file');
    if (process.platform !== 'win32') {
      assert.equal(state.mode & 0o777, 0o600, 'Output must have mode 0600');
      assert.equal(state.uid, process.getuid(), 'Output belongs to another user');
    }
    await handle.truncate(0);
    await handle.writeFile(bytes);
    await handle.sync();
  } finally {
    await handle.close();
  }
}

async function toolchain() {
  let local = {};
  try {
    local = JSON.parse(await readFile(join(root, '.local/mobile-toolchain.json'), 'utf8'));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  let javaHome = process.env.JAVA_HOME ?? local.javaHome;
  if (!javaHome && process.platform === 'darwin') {
    javaHome = execFileSync('/usr/libexec/java_home', ['-v', '17'], { encoding: 'utf8' }).trim();
  }
  assert(javaHome, 'Configure JDK 17 using the existing mobile toolchain');
  return { ...local, javaHome };
}

function run(command, args, env, inherit = false) {
  const result = spawnSync(command, args, {
    cwd: root,
    env,
    encoding: 'utf8',
    stdio: inherit ? 'inherit' : ['ignore', 'pipe', 'pipe'],
    maxBuffer: 8 * 1024 * 1024,
  });
  // Do not include child arguments, environment, or diagnostic output in errors.
  assert(
    !result.error && result.status === 0,
    'Android release tool failed; no signing data printed',
  );
  return result.stdout;
}

export function certificateDetails(bytes) {
  const cert = new X509Certificate(bytes);
  const jwk = cert.publicKey.export({ format: 'jwk' });
  assert.equal(jwk.kty, 'RSA', 'Filing export currently requires an RSA certificate');
  return {
    certificateMd5: createHash('md5').update(cert.raw).digest('hex').toUpperCase(),
    certificateSha1: createHash('sha1').update(cert.raw).digest('hex').toUpperCase(),
    certificateSha256: createHash('sha256').update(cert.raw).digest('hex').toUpperCase(),
    rsaModulusHex: Buffer.from(jwk.n, 'base64url').toString('hex').toUpperCase(),
    validFrom: cert.validFrom,
    validTo: cert.validTo,
    publicKeyPem: cert.publicKey.export({ type: 'spki', format: 'pem' }),
  };
}

async function config() {
  await regularFile(configPath);
  const values = parseEnv(await readFile(configPath, 'utf8'));
  assert(
    signingNames.every((key) => values[key]?.trim()),
    'Signing configuration is incomplete',
  );
  assert(isAbsolute(values.AGENT_HUB_ANDROID_KEYSTORE), 'Keystore path must be absolute');
  await regularFile(values.AGENT_HUB_ANDROID_KEYSTORE);
  return { ...process.env, ...Object.fromEntries(signingNames.map((key) => [key, values[key]])) };
}

async function prepareFiling() {
  if (!existsSync(filingPath)) {
    await savePrivate(
      filingPath,
      `# 仅在本机填写；不要在聊天中发送身份证、手机号、密码或证件附件。
# 以原备案后台为准；历史商店资料不作为当前 App 已备案的证明。
FILING_PROVIDER=
FILING_SUBJECT_NAME=合肥博璀人工智能科技有限公司
# 尚未获批则留空，不复制其他 App 或历史资料的号码。
FILING_EXISTING_APP_NUMBER=
FILING_APP_NAME=Agent Hub
FILING_CURRENT_OPERATION=verify-existing-order-before-filling
FILING_EXISTING_ORDER_ID=
FILING_DOMAIN=51easyai.com
# 以下为实际接入/中继/推送等服务清单，不能只填隐私网页域名。
FILING_SERVICE_DOMAINS=
FILING_SERVER_PROVIDER=
FILING_SERVER_REGION=
FILING_RESPONSIBLE_NAME=
FILING_RESPONSIBLE_MOBILE=
FILING_RESPONSIBLE_EMAIL=
# 可填写本机文件路径；证件优先直接交备案平台，不放入源码目录。
FILING_BUSINESS_LICENSE_FILE=
FILING_RESPONSIBLE_ID_FILE=
# 平台要求时补充授权书或前置审批；不需要则由审核确认不适用。
FILING_AUTHORIZATION_FILE=
FILING_EXISTING_ANDROID_PLATFORM_CONFIRMED=
`,
      true,
    );
  }
  await regularFile(filingPath);
  const filing = parseEnv(await readFile(filingPath, 'utf8'));
  const required = [
    'FILING_PROVIDER',
    'FILING_SERVICE_DOMAINS',
    'FILING_SERVER_PROVIDER',
    'FILING_SERVER_REGION',
    'FILING_RESPONSIBLE_NAME',
    'FILING_RESPONSIBLE_MOBILE',
    'FILING_RESPONSIBLE_EMAIL',
    'FILING_EXISTING_ANDROID_PLATFORM_CONFIRMED',
  ];
  await savePrivate(
    join(privateDir, 'filing-private.json'),
    JSON.stringify(
      {
        status: 'draft-not-submitted',
        existingRecordIsUnverified: true,
        intendedOperation:
          filing.FILING_CURRENT_OPERATION || 'verify-existing-order-before-filling',
        applicationId,
        fields: filing,
        missingFields: required.filter((key) => !filing[key]?.trim()),
        signingInfoFile: 'signing-info.json',
      },
      null,
      2,
    ) + '\n',
  );
  console.log(
    'Filing draft prepared; missing field names only: ' +
      required.filter((key) => !filing[key]?.trim()).join(', '),
  );
}

async function signingInfo() {
  await privateDirectory();
  const env = await config();
  const local = await toolchain();
  // SDK launchers also need the configured JDK, beyond Gradle's child environment.
  env.JAVA_HOME = local.javaHome;
  const keytool = join(
    local.javaHome,
    'bin',
    process.platform === 'win32' ? 'keytool.exe' : 'keytool',
  );
  const pem = run(
    keytool,
    [
      '-exportcert',
      '-rfc',
      '-keystore',
      env.AGENT_HUB_ANDROID_KEYSTORE,
      '-alias',
      env.AGENT_HUB_ANDROID_KEY_ALIAS,
      '-storepass:env',
      'AGENT_HUB_ANDROID_STORE_PASSWORD',
    ],
    env,
  );
  const certificate = new X509Certificate(pem);
  const details = { applicationId, ...certificateDetails(certificate.raw) };
  await savePrivate(join(privateDir, 'release-certificate.pem'), certificate.toString());
  await savePrivate(join(privateDir, 'release-certificate.der'), certificate.raw);
  await savePrivate(join(privateDir, 'signing-info.json'), JSON.stringify(details, null, 2) + '\n');
  await savePrivate(
    join(privateDir, 'filing-technical.txt'),
    [
      'Agent Hub Android 备案技术字段（以最终安装包签名复核，非备案批准证明）',
      '包名：' + applicationId,
      '公钥（RSA 模数，十六进制）：' + details.rsaModulusHex,
      'MD5（签名证书指纹，不是 APK 文件 MD5）：' + details.certificateMd5,
      'SHA-1（签名证书）：' + details.certificateSha1,
      'SHA-256（签名证书）：' + details.certificateSha256,
      '公钥格式按原接入商说明选择；PEM 公钥另见 signing-info.json。',
      '不要提交 signing.p12、签名密码或 .env.android-release.local。',
    ].join('\n') + '\n',
  );
  await prepareFiling();
  console.log('Formal certificate exported. SHA-256: ' + details.certificateSha256);
  return { env, local, details };
}

async function initialize() {
  assert(
    process.platform !== 'win32',
    'Initial key provisioning currently supports POSIX private permissions; Windows requires native DACL provisioning',
  );
  await privateDirectory();
  const keyPath = join(privateDir, 'signing.p12');
  if (existsSync(configPath) || existsSync(keyPath)) {
    assert(
      existsSync(configPath) && existsSync(keyPath),
      'Partial signing state retained; recover it before proceeding, never regenerate a published key',
    );
    await signingInfo();
    console.log('Existing signing identity preserved; no key rotation.');
    return;
  }
  const lockPath = join(privateDir, 'provision.lock');
  const lock = await open(lockPath, 'wx', 0o600);
  try {
    assert(
      !existsSync(configPath) && !existsSync(keyPath),
      'Signing state changed; existing identity preserved',
    );
    const secret = randomBytes(32).toString('hex');
    const env = { ...process.env, AGENT_HUB_ANDROID_STORE_PASSWORD: secret };
    const local = await toolchain();
    // Create the private file first: an interrupted provisioning keeps recovery inputs.
    await savePrivate(
      configPath,
      `# 正式 Android 签名密钥；仅本机加载，禁止提交或粘贴进聊天。
# 此密钥用于正式包；不要改签名覆盖开发/验收包。首次发布前请离线加密备份。
AGENT_HUB_ANDROID_KEYSTORE=${keyPath}
AGENT_HUB_ANDROID_STORE_PASSWORD=${secret}
AGENT_HUB_ANDROID_KEY_ALIAS=agenthub-release
AGENT_HUB_ANDROID_KEY_PASSWORD=${secret}
`,
      true,
    );
    const oldMask = process.umask(0o077);
    try {
      run(
        join(local.javaHome, 'bin/keytool'),
        [
          '-genkeypair',
          '-noprompt',
          '-storetype',
          'PKCS12',
          '-keystore',
          keyPath,
          '-alias',
          'agenthub-release',
          '-keyalg',
          'RSA',
          '-keysize',
          '4096',
          '-validity',
          '10000',
          '-dname',
          'CN=Agent Hub Android Release, O=Agent Hub, C=CN',
          '-storepass:env',
          'AGENT_HUB_ANDROID_STORE_PASSWORD',
          '-keypass:env',
          'AGENT_HUB_ANDROID_STORE_PASSWORD',
        ],
        env,
      );
    } finally {
      process.umask(oldMask);
    }
    await regularFile(keyPath);
  } finally {
    await lock.close();
    await unlink(lockPath);
  }
  await signingInfo();
}

export function verifyApkOutput(output, expectedCertificate) {
  const digests = [
    ...output.matchAll(/^Signer #\d+ certificate SHA-256 digest:\s*([a-f0-9]+)\s*$/gim),
  ];
  assert.equal(digests.length, 1, 'Expected exactly one APK signing identity');
  assert.equal(
    digests[0][1].toUpperCase(),
    expectedCertificate,
    'APK signer differs from formal identity',
  );
}

async function sourceFingerprint(env) {
  const files = run(
    'git',
    [
      'ls-files',
      '-z',
      '--cached',
      '--others',
      '--exclude-standard',
      '--',
      'apps/android',
      'packages/mobile-core',
      'gradle',
      'build.gradle.kts',
      'settings.gradle.kts',
      'gradle.properties',
      'scripts/mobile/android-release.mjs',
      'scripts/mobile/gradle.mjs',
    ],
    env,
  )
    .split('\0')
    .filter(Boolean)
    .sort();
  const hash = createHash('sha256');
  for (const file of [...new Set(files)])
    hash.update(file + '\0').update(await readFile(join(root, file)));
  return hash.digest('hex');
}

async function build() {
  const { env, local, details } = await signingInfo();
  const sourceSha256 = await sourceFingerprint(env);
  run(
    process.execPath,
    [
      join(root, 'scripts/mobile/gradle.mjs'),
      ':android:assembleRelease',
      ':android:bundleRelease',
      '-PagentHubReleaseBuild=true',
      '--console=plain',
    ],
    env,
    true,
  );
  const sdk = env.ANDROID_HOME ?? env.ANDROID_SDK_ROOT ?? local.androidSdk;
  assert(sdk, 'Android SDK is required for artifact verification');
  const versions = (await readdir(join(sdk, 'build-tools')))
    .filter((version) => /^\d+(\.\d+)*$/.test(version))
    .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
  assert(versions.length, 'Android SDK build tools are missing');
  const bin = join(sdk, 'build-tools', versions[0]);
  const apk = join(root, '.build/native/android-release/outputs/apk/release/android-release.apk');
  const aab = join(
    root,
    '.build/native/android-release/outputs/bundle/release/android-release.aab',
  );
  const verification = run(
    join(bin, 'apksigner'),
    ['verify', '--verbose', '--print-certs', apk],
    env,
  );
  verifyApkOutput(verification, details.certificateSha256);
  const badging = run(join(bin, 'aapt2'), ['dump', 'badging', apk], env);
  assert(
    badging.includes("package: name='" + applicationId + "'"),
    'Unexpected release application ID',
  );
  assert(!/^application-debuggable/m.test(badging), 'Formal APK must not be debuggable');
  run(join(local.javaHome, 'bin/jarsigner'), ['-verify', aab], env);
  const bundleCertificate = run(
    join(local.javaHome, 'bin/keytool'),
    ['-printcert', '-rfc', '-jarfile', aab],
    env,
  );
  assert.equal(
    certificateDetails(new X509Certificate(bundleCertificate).raw).certificateSha256,
    details.certificateSha256,
    'AAB signer differs from formal identity',
  );
  const artifacts = [];
  for (const source of [apk, aab]) {
    const bytes = await readFile(source);
    const name = source.endsWith('.apk') ? 'agent-hub-release.apk' : 'agent-hub-release.aab';
    await savePrivate(join(privateDir, name), bytes);
    artifacts.push({
      name,
      bytes: bytes.length,
      sha256: createHash('sha256').update(bytes).digest('hex'),
    });
  }
  const sourceSha = run('git', ['rev-parse', 'HEAD'], env).trim();
  assert.equal(
    await sourceFingerprint(env),
    sourceSha256,
    'Release source changed during build; retain artifacts for diagnosis, do not publish',
  );
  await savePrivate(
    join(privateDir, 'build-verification.json'),
    JSON.stringify(
      {
        builtAt: new Date().toISOString(),
        sourceSha,
        sourceSha256,
        applicationId,
        certificateSha256: details.certificateSha256,
        artifacts,
        apkSignatureVerified: true,
        aabSignatureVerified: true,
        debuggable: false,
        installedOnDevice: false,
        filingApproved: false,
        storeApproved: false,
      },
      null,
      2,
    ) + '\n',
  );
  console.log(
    'Signed APK/AAB verified and retained in .local/android-release. No installation, upload or filing submission performed.',
  );
}

async function main() {
  const action = process.argv[2];
  assert(
    process.argv.length === 3 && ['init', 'info', 'build', 'filing'].includes(action),
    'Use android-release.mjs init|info|build|filing',
  );
  if (action === 'init') await initialize();
  else if (action === 'build') await build();
  else if (action === 'info') await signingInfo();
  else {
    await privateDirectory();
    await prepareFiling();
  }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  main().catch((error) => {
    console.error('Android release preparation failed: ' + error.message);
    process.exitCode = 1;
  });
}
