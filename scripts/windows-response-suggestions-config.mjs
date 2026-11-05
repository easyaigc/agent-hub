import { createHash, randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import {
  readFileSync,
  existsSync,
  lstatSync,
  statSync,
  realpathSync,
  unlinkSync,
  closeSync,
  readdirSync,
} from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import http from 'node:http';
import {
  makePrivateDirectorySync,
  writePrivateFileSync,
  openPrivateFileSync,
  assertPrivateFile,
  inspectDesktopResponseConfigToml,
} from '../packages/codex-adapter/dist/index.js';

const hash = (b) => createHash('sha256').update(b).digest('hex');
const helper = fileURLToPath(
  new URL('../packages/codex-adapter/windows/response-config-replace.ps1', import.meta.url),
);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const bom = Buffer.from([0xef, 0xbb, 0xbf]);

function validateConfig(value) {
  if (
    !value ||
    Array.isArray(value) ||
    Object.keys(value).sort().join(',') !== 'bindingId,port,version' ||
    value.version !== 1 ||
    !Number.isInteger(value.port) ||
    value.port < 1024 ||
    value.port > 65535 ||
    typeof value.bindingId !== 'string' ||
    !uuid.test(value.bindingId)
  )
    throw Error('Invalid private response configuration');
  return value;
}

function health(config) {
  return new Promise((resolveResult) => {
    const req = http.get(
      {
        host: 'localhost',
        port: config.port,
        path: `/_agent_hub/${config.bindingId}/backend-api/health`,
        timeout: 1500,
      },
      (res) => {
        let data = '';
        res.setEncoding('utf8');
        res.on('data', (x) => {
          data += x;
          if (data.length > 4096) req.destroy();
        });
        res.on('end', () => {
          try {
            const v = JSON.parse(data);
            resolveResult(
              res.statusCode === 200 &&
                v.ready === true &&
                v.bindingId === config.bindingId &&
                v.port === config.port,
            );
          } catch {
            resolveResult(false);
          }
        });
      },
    );
    req.on('timeout', () => req.destroy());
    req.on('error', () => resolveResult(false));
  });
}

export async function responseSuggestionsConfig({
  configPath,
  codexHome = join(homedir(), '.codex'),
  operation,
  receiptId,
  testReplace,
  testHealth,
} = {}) {
  if (
    !['install', 'disable', 'status', 'inspect-no-change', 'resolve-no-change'].includes(operation)
  )
    throw Error('Use install, status, disable, inspect-no-change or resolve-no-change');
  const configFile = resolve(configPath),
    target = join(resolve(codexHome), 'config.toml');
  assertPrivateFile(configFile);
  const config = validateConfig(JSON.parse(readFileSync(configFile, 'utf8')));
  const url = `http://localhost:${config.port}/_agent_hub/${config.bindingId}/backend-api`;
  if (
    !existsSync(target) ||
    !lstatSync(target).isFile() ||
    lstatSync(target).isSymbolicLink() ||
    statSync(target).nlink !== 1 ||
    realpathSync(target).toLowerCase() !== resolve(target).toLowerCase() ||
    realpathSync(resolve(codexHome)).toLowerCase() !== resolve(codexHome).toLowerCase()
  )
    throw Error('Existing regular config.toml required');
  const stateDir = join(dirname(configFile), 'response-config-state');
  const stagingRoot = join(resolve(codexHome), '.agent-hub-response-staging');
  const staging = join(stagingRoot, config.bindingId);
  const inspectNoChange = () => {
    if (!uuid.test(receiptId ?? '')) throw Error('Exact receipt ID required');
    const receipt = join(stateDir, `${receiptId}.json`);
    assertPrivateFile(receipt);
    const record = JSON.parse(readFileSync(receipt, 'utf8'));
    if (
      record.bindingId !== config.bindingId ||
      record.applied !== false ||
      !/^[0-9a-f]{64}$/.test(record.beforeSha256 ?? '')
    )
      throw Error('Receipt mismatch');
    const paths = [
      join(dirname(target), `.agent-hub-${receiptId}.tmp`),
      join(stateDir, `${receiptId}.backup`),
      join(staging, `${receiptId}.tmp`),
      join(staging, `${receiptId}.backup`),
    ];
    if (
      hash(readFileSync(target)) !== record.beforeSha256 ||
      paths.some((path) => existsSync(path))
    )
      throw Error('No-change recovery conditions not met');
    return record;
  };
  if (operation === 'inspect-no-change') {
    inspectNoChange();
    return { noChange: true, resolved: false };
  }
  makePrivateDirectorySync(stateDir);
  const lock = join(stateDir, 'config.lock');
  const fd = openPrivateFileSync(lock, 'wx', 0o600);
  try {
    if (operation === 'resolve-no-change') {
      const record = inspectNoChange();
      const resolution = join(stateDir, `${receiptId}.resolved.json`);
      writePrivateFileSync(
        resolution,
        JSON.stringify({
          version: 1,
          receiptId,
          beforeSha256: record.beforeSha256,
          result: 'resolved-no-change',
          explicit: true,
        }),
        { flag: 'wx', mode: 0o600 },
      );
      return { noChange: true, resolved: true };
    }
    for (const name of readdirSync(stateDir)) {
      if (!/^[0-9a-f-]{36}\.json$/i.test(name)) continue;
      const file = join(stateDir, name);
      assertPrivateFile(file);
      const previous = JSON.parse(readFileSync(file, 'utf8'));
      if (previous.applied !== true) {
        const resolvedPath = join(stateDir, name.replace(/\.json$/, '.resolved.json'));
        if (!existsSync(resolvedPath))
          throw Error('Unresolved configuration transaction requires recovery');
        assertPrivateFile(resolvedPath);
        const resolution = JSON.parse(readFileSync(resolvedPath, 'utf8'));
        if (
          resolution.result !== 'resolved-no-change' ||
          resolution.receiptId !== name.slice(0, -5) ||
          resolution.beforeSha256 !== previous.beforeSha256
        )
          throw Error('Invalid resolution receipt');
      }
    }
    const original = readFileSync(target),
      before = hash(original);
    let parsed;
    try {
      parsed = inspectDesktopResponseConfigToml(original, url);
    } catch (error) {
      if (operation === 'status')
        return {
          enabled: false,
          owned: false,
          conflict: true,
          loopbackReady: await (testHealth ? testHealth(config) : health(config)),
        };
      throw error;
    }
    const ownerFile = join(stateDir, 'ownership.json');
    let owner;
    if (existsSync(ownerFile)) {
      assertPrivateFile(ownerFile);
      owner = JSON.parse(readFileSync(ownerFile, 'utf8'));
    }
    const owned =
      parsed.own &&
      owner?.key === 'openai_base_url' &&
      owner?.bindingId === config.bindingId &&
      owner?.applied === true;
    if (parsed.own && !owned) {
      if (operation === 'status')
        return {
          enabled: true,
          owned: false,
          conflict: true,
          loopbackReady: await (testHealth ? testHealth(config) : health(config)),
        };
      throw Error('Matching URL has no ownership receipt');
    }
    const ready = await (testHealth ? testHealth(config) : health(config));
    if (operation === 'status')
      return { enabled: parsed.own, owned: parsed.own, conflict: false, loopbackReady: ready };
    if (operation === 'install' && !ready) throw Error('Loopback forwarder is not ready');
    if ((operation === 'install' && parsed.own) || (operation === 'disable' && !parsed.own))
      return {
        enabled: parsed.own,
        owned: parsed.own,
        conflict: false,
        loopbackReady: ready,
        changed: false,
      };
    const updatedBody =
      operation === 'install'
        ? parsed.prefix + parsed.body
        : parsed.body.slice(parsed.prefix.length);
    const updated = Buffer.concat([
        parsed.hasBom ? bom : Buffer.alloc(0),
        Buffer.from(updatedBody),
      ]),
      after = hash(updated);
    const id = randomUUID(),
      prepared = join(staging, `${id}.tmp`),
      backup = join(staging, `${id}.backup`),
      receipt = join(stateDir, `${id}.json`);
    const record = {
      version: 1,
      operation,
      key: 'openai_base_url',
      bindingId: config.bindingId,
      beforeSha256: before,
      afterSha256: after,
      applied: false,
      concurrency: 'optimistic-not-atomic-cas',
      backup: `${id}.backup`,
    };
    writePrivateFileSync(receipt, JSON.stringify(record), { flag: 'wx', mode: 0o600 });
    let actual;
    try {
      record.stage = 'prepare';
      writePrivateFileSync(receipt, JSON.stringify(record), { mode: 0o600 });
      for (const path of [stagingRoot, staging]) {
        if (
          existsSync(path) &&
          (lstatSync(path).isSymbolicLink() ||
            realpathSync(path).toLowerCase() !== resolve(path).toLowerCase())
        )
          throw Error('Unsafe staging directory');
      }
      makePrivateDirectorySync(stagingRoot);
      makePrivateDirectorySync(staging);
      for (const path of [stagingRoot, staging]) {
        const info = lstatSync(path);
        if (
          !info.isDirectory() ||
          info.isSymbolicLink() ||
          realpathSync(path).toLowerCase() !== resolve(path).toLowerCase()
        )
          throw Error('Unsafe staging directory');
      }
      if (statSync(target, { bigint: true }).dev !== statSync(staging, { bigint: true }).dev)
        throw Error('Staging and target must be on same volume');
      writePrivateFileSync(prepared, updated, { flag: 'wx', mode: 0o600 });
      record.stage = 'replace-started';
      writePrivateFileSync(receipt, JSON.stringify(record), { mode: 0o600 });
      actual = testReplace
        ? await testReplace({ target, prepared, backup, expected: before })
        : JSON.parse(
            execFileSync(
              'powershell.exe',
              [
                '-NoProfile',
                '-NonInteractive',
                '-File',
                helper,
                '-Target',
                target,
                '-Prepared',
                prepared,
                '-Backup',
                backup,
                '-Expected',
                before,
              ],
              { encoding: 'utf8', timeout: 10000 },
            ),
          ).replacedSha256;
      if (actual !== before || hash(readFileSync(target)) !== after) {
        record.replacedSha256 = actual;
        record.conflict = true;
        writePrivateFileSync(receipt, JSON.stringify(record), { mode: 0o600 });
        throw Error('Replacement conflict; retained recovery receipt and backup');
      }
      const ownerTemp = join(stateDir, `${id}.owner`);
      writePrivateFileSync(
        ownerTemp,
        JSON.stringify({
          key: record.key,
          bindingId: record.bindingId,
          beforeSha256: before,
          afterSha256: after,
          applied: operation === 'install',
        }),
        { flag: 'wx', mode: 0o600 },
      );
      if (existsSync(ownerFile)) assertPrivateFile(ownerFile);
      // This state directory is private and the tool's lock serializes our writers.
      const { renameSync } = await import('node:fs');
      renameSync(ownerTemp, ownerFile);
      record.applied = true;
      writePrivateFileSync(receipt, JSON.stringify(record), { mode: 0o600 });
      unlinkSync(backup);
      return {
        enabled: operation === 'install',
        owned: operation === 'install',
        conflict: false,
        loopbackReady: ready,
        changed: true,
      };
    } catch (error) {
      // Unknown or conflicting operations retain the private transaction and any backup.
      record.failure = {
        stage: record.stage ?? 'unknown',
        code: error.code ?? null,
        nativeType: error.nativeType ?? null,
        nativeStep: error.nativeStep ?? null,
      };
      try {
        writePrivateFileSync(receipt, JSON.stringify(record), { mode: 0o600 });
      } catch {
        /* retain original error */
      }
      throw Error(
        `Configuration transaction incomplete; recovery receipt ${receipt}: ${error.message}`,
        { cause: error },
      );
    }
  } finally {
    closeSync(fd);
    unlinkSync(lock);
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    if (process.platform !== 'win32') throw Error('Windows only');
    const args = process.argv.slice(2),
      operation = args.shift(),
      flags = {};
    while (args.length) {
      const k = args.shift(),
        v = args.shift();
      if (!['--config', '--codex-home', '--receipt'].includes(k) || !v || flags[k])
        throw Error('Invalid arguments');
      flags[k] = v;
    }
    if (!flags['--config']) throw Error('--config is required');
    console.log(
      JSON.stringify(
        await responseSuggestionsConfig({
          operation,
          configPath: flags['--config'],
          codexHome: flags['--codex-home'],
          receiptId: flags['--receipt'],
        }),
      ),
    );
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
