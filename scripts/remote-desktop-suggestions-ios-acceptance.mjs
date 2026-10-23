import { spawn, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { createWriteStream } from 'node:fs';
import { lstat, mkdir, readFile, readdir, realpath, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { basename, dirname, isAbsolute, join, relative } from 'node:path';
import { performance } from 'node:perf_hooks';
import { createInterface } from 'node:readline';
import { fileURLToPath } from 'node:url';
import { stripVTControlCharacters } from 'node:util';

// Deliberately not an installer/Host launcher. The operator supplies a running,
// temporary Windows DEV Host with two existing, owner-loaded completed sources.
// Required env: SUGGESTION_ACCEPTANCE_DIRECTORY (0700, outside this checkout),
// SUGGESTION_IOS_DEVICE_ID (physical UDID), SUGGESTION_IOS_BUILD_NUMBER (integer),
// SUGGESTION_HTTPS_PORT (unused local Tailscale HTTPS port), and exactly one of
// SUGGESTION_REMOTE_PROOF_FILE (0600) / SUGGESTION_REMOTE_PROOF_JSON.
// Build env: explicit JAVA_HOME/KOTLIN_NATIVE_HOME; optional independent private
// GRADLE_USER_HOME (otherwise created in evidence), ANDROID_HOME/ANDROID_SDK_ROOT.
// Proof v1: {schemaVersion:1, platform:"windows", temporaryDEVHost:true,
//   endpoint:"https://<remote-host>:<port>", token:"<DEV-token>",
//   target:{agentId:"codex",hostId:"...",workspaceId:"...",runtimeId:"desktop-runtime"},
//   exactSourceBindingsVerified:true, actualSourceBindings:[ // exactly two
//     {title:"...",textSha256:"<sha256 of exact UTF-8 text>",
//      binding:{sourceThreadId:"...",sourceTurnId:"...",
//        predictionThreadId:"...",predictionTurnId:"..."},observation:{text:"..."}}, ...]}
// token may instead come from SUGGESTION_REMOTE_TOKEN. No registry/IPC fallback,
// account gate, source synthesis, lease extension, task submission, or retry.
// Optional bootstrapReadOnlyThreadId (UUID, distinct from A/B) requires the
// operator's bootstrapSameAuthorizedTask:true declaration and must be the actual
// workspace first thread. It permits only existing reads/read acknowledgements;
// its suggestions/statuses never count as A/B acceptance evidence.
const root = fileURLToPath(new URL('..', import.meta.url));
const check = (ok, code) => {
  if (!ok) throw Object.assign(new Error(code), { acceptanceCode: code });
};
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const canonical = (v) =>
  JSON.stringify(
    v && typeof v === 'object'
      ? Array.isArray(v)
        ? v.map((x) => JSON.parse(canonical(x)))
        : Object.fromEntries(
            Object.keys(v)
              .sort()
              .map((k) => [k, JSON.parse(canonical(v[k]))]),
          )
      : v,
  );
const targetKeys = ['agentId', 'hostId', 'workspaceId', 'runtimeId'];
const targetKey = (v) => JSON.stringify(targetKeys.map((k) => v?.[k]));
const uid = /^[a-zA-Z0-9._-]{1,128}$/;
const uuid = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i;
const suggestionResponseTimeHeader = 'x-agent-hub-suggestion-response-unix-ms';
// settings.gradle.kts evaluates both projects even for an iOS-only task.
const nativeBuildRoots = [
  'apps/ios',
  'apps/android',
  'packages/mobile-core',
  'packages/protocol/schemas',
  'packages/protocol/fixtures/mobile',
  'gradle',
  'gradlew',
  'gradlew.bat',
  'settings.gradle.kts',
  'build.gradle.kts',
  'gradle.properties',
  'scripts/mobile/gradle.mjs',
  'scripts/mobile/build-ios-core.sh',
];
const excludedNativeInput = (path) =>
  path
    .split('/')
    .some(
      (part) =>
        [
          'node_modules',
          'build',
          '.build',
          'DerivedData',
          '.DS_Store',
          '.git',
          '.gradle',
          '.kotlin',
          '.konan',
          '.local',
          '.idea',
          '.swiftpm',
          'xcuserdata',
          'private',
        ].includes(part) ||
        /^\.env(?:\.|$)|^local\.properties$|\.(?:xcuserstate|p12|pfx|keystore|jks|mobileprovision)$/.test(
          part,
        ),
    );
async function nativeBuildManifest(base) {
  const files = {},
    canonicalBase = await realpath(base);
  const visit = async (path) => {
    if (excludedNativeInput(path)) return;
    const file = join(base, path),
      stat = await lstat(file);
    check(
      !stat.isSymbolicLink() && (await realpath(file)) === join(canonicalBase, path),
      'build-input-symlink-refused',
    );
    if (stat.isDirectory())
      for (const name of (await readdir(file)).sort()) await visit(path + '/' + name);
    else {
      check(stat.isFile(), 'regular-build-input-required');
      const bytes = await readFile(file);
      files[path] = {
        sha256: sha(bytes),
        bytes: bytes.length,
        executable: Boolean(stat.mode & 0o111),
      };
    }
  };
  for (const path of nativeBuildRoots) await visit(path);
  return Object.fromEntries(Object.entries(files).sort(([a], [b]) => a.localeCompare(b, 'en')));
}
process.umask(0o077);

async function main() {
  const required = (k) => {
    check(process.env[k], 'required-' + k);
    return process.env[k];
  };
  const evidence = required('SUGGESTION_ACCEPTANCE_DIRECTORY');
  check(isAbsolute(evidence), 'absolute-evidence-directory-required');
  await mkdir(evidence, { recursive: true, mode: 0o700 });
  const privatePath = async (p, directory = false) => {
    const s = await lstat(p),
      rel = relative(await realpath(root), await realpath(p));
    check(
      !s.isSymbolicLink() &&
        s.uid === process.getuid() &&
        !(s.mode & 0o077) &&
        (directory ? s.isDirectory() : s.isFile()),
      'private-owner-and-permissions-required',
    );
    check(
      rel === '..' || rel.startsWith('../') || isAbsolute(rel),
      'private-inputs-must-be-outside-checkout',
    );
    const at = directory ? p : dirname(p);
    if (
      spawnSync('git', ['-C', at, 'rev-parse', '--show-toplevel'], { stdio: 'ignore' }).status === 0
    )
      check(
        spawnSync('git', ['-C', at, 'check-ignore', '--quiet', p], { stdio: 'ignore' }).status ===
          0,
        'private-data-must-not-be-git-trackable',
      );
  };
  await privatePath(evidence, true);
  const directory = join(evidence, 'remote-phone-' + randomUUID());
  await mkdir(directory, { mode: 0o700 });
  const json = (name, value) =>
    writeFile(join(directory, name), JSON.stringify(value, null, 2), { mode: 0o600 });
  const report = {
    status: 'running',
    startedAt: new Date().toISOString(),
    directory,
    failures: [],
    denied: [],
    requests: [],
    upstreamReads: [],
    proxyClients: [],
    suggestions: [],
    renewalCoverage: [],
    matches: [],
    adoptions: [],
    scans: [],
    cleanup: {},
    artifacts: {},
  };
  const save = () => json('report.json', report);
  const fail = (code) => {
    report.failures.push(code);
    process.exitCode = 1;
  };
  let phase = 'inputs',
    proxy,
    child,
    beforeServe,
    ownedServe,
    ownWebKey,
    localProxy,
    serveAttempted = false,
    pending = 0,
    drained;
  let proof, token, endpoint, port, fixture, native, nativeSource, baseline, verifyBuildInputs;
  const env = { ...process.env, DEVELOPER_DIR: '/Applications/Xcode.app/Contents/Developer' };
  for (const k of Object.keys(env)) if (k.startsWith('SUGGESTION_')) delete env[k];
  const tailscale = '/Applications/Tailscale.app/Contents/MacOS/Tailscale';
  const run = (command, args) => {
    check(!report.interrupted || phase === 'cleanup', 'interrupted-before-command');
    const r = spawnSync(command, args, {
      encoding: 'utf8',
      env,
      timeout: 60000,
      maxBuffer: 16 * 1024 * 1024,
    });
    check(!r.error && r.status === 0, 'fixed-command-failed-' + basename(command));
    return r.stdout;
  };
  const serve = () => JSON.parse(run(tailscale, ['serve', 'status', '--json']));
  const interrupted = () => {
    report.interrupted = true;
    fail('interrupted');
    child?.kill('SIGINT');
  };
  process.once('SIGINT', interrupted);
  process.once('SIGTERM', interrupted);
  const sameTarget = (v) =>
    check(targetKey(v) === targetKey(proof.target), 'machine-target-mismatch');
  const route = (path, query = {}) =>
    path + '?' + new URLSearchParams({ hostId: proof.target.hostId, ...query });
  const stamp = () => {
    const unixMs = Date.now();
    return { utc: new Date(unixMs).toISOString(), unixMs, monotonicMs: performance.now() };
  };
  const remote = async (path, method = 'GET', body, proxyClient, bootstrapReadOnly = false) => {
    const record = {
      id: report.upstreamReads.length + 1,
      phase,
      method,
      path: path.split('?')[0],
      requestSha256: sha(path),
      proxyRequestId: proxyClient?.id,
      bootstrapReadOnly,
      started: stamp(),
      status: null,
      httpDate: null,
      responseSha256: null,
    };
    report.upstreamReads.push(record);
    if (bootstrapReadOnly) report.bootstrap.readIds.push(record.id);
    if (proxyClient) proxyClient.upstreamReadId = record.id;
    try {
      const r = await fetch(endpoint + path, {
        method,
        redirect: 'error',
        cache: 'no-store',
        headers: {
          Authorization: 'Bearer ' + token,
          'Cache-Control': 'no-cache',
          ...(body ? { 'Content-Type': 'application/json' } : {}),
        },
        body,
        signal: AbortSignal.timeout(35000),
      });
      record.headersReceived = stamp();
      record.status = r.status;
      record.httpDate = r.headers.get('date');
      record.serverResponseUnixMsHeader = r.headers.get(suggestionResponseTimeHeader);
      record.contentType = r.headers.get('content-type');
      record.bootstrapStatusOnly = bootstrapReadOnly && [409, 503].includes(r.status);
      const chunks = [];
      let bodyError, parseError, value;
      try {
        if (r.body) for await (const chunk of r.body) chunks.push(Buffer.from(chunk));
      } catch (e) {
        bodyError = e;
        record.bodyErrorName = e.name;
      }
      const bytes = Buffer.concat(chunks);
      if (!bodyError)
        try {
          // Response.json uses UTF-8 decoding with BOM removal too.
          value = JSON.parse(new TextDecoder().decode(bytes));
        } catch (e) {
          parseError = e;
          record.jsonErrorName = e.name;
        }
      record.finished = stamp();
      record.responseSha256 = sha(bytes);
      record.responseBytes = bytes.length;
      record.responseComplete = !bodyError;
      if (
        r.status !== 200 ||
        record.path === '/v1/threads/next-turn-suggestion' ||
        bodyError ||
        parseError
      ) {
        // Preserve exact fetch-decoded bytes before any status/source/lease check.
        // The raw payload is private evidence, never a console or test log line.
        record.bodyFile = join(
          directory,
          'upstream-' + record.id + '-response.' + (!bodyError && !parseError ? 'json' : 'body'),
        );
        await writeFile(record.bodyFile, bytes, { mode: 0o600, flag: 'wx' });
        record.evidenceSaved = stamp();
      }
      check(r.status === 200 || record.bootstrapStatusOnly, 'remote-http-' + r.status);
      if (bodyError) throw bodyError;
      if (parseError && !record.bootstrapStatusOnly) throw parseError;
      return {
        value,
        statusBody: record.bootstrapStatusOnly ? bytes : undefined,
        receivedAt: record.finished.unixMs,
        receivedMonotonicMs: record.finished.monotonicMs,
        record,
      };
    } catch (e) {
      record.failure = { code: e.acceptanceCode ?? 'unexpected-' + e.name, errorName: e.name };
      throw e;
    } finally {
      record.finished ??= stamp();
      record.durationMs = record.finished.monotonicMs - record.started.monotonicMs;
    }
  };
  const rows = () => proof.actualSourceBindings;
  const isBootstrapThread = (id) =>
    proof.bootstrapReadOnlyThreadId !== undefined && id === proof.bootstrapReadOnlyThreadId;
  const validateBootstrap = (workspace) => {
    const id = proof.bootstrapReadOnlyThreadId;
    if (id === undefined) {
      check(
        proof.bootstrapSameAuthorizedTask === undefined,
        'bootstrap-declaration-requires-thread',
      );
      return;
    }
    check(
      typeof id === 'string' &&
        uuid.test(id) &&
        rows().every((row) => row.binding.sourceThreadId.toLowerCase() !== id.toLowerCase()),
      'bootstrap-readonly-thread-must-be-distinct-uuid',
    );
    check(
      proof.bootstrapSameAuthorizedTask === true,
      'bootstrap-same-authorized-task-declaration-required',
    );
    if (workspace)
      check(workspace.threads?.[0]?.id === id, 'bootstrap-must-be-workspace-first-thread');
  };
  const suggestionLease = (read) => {
    const { record, receivedAt } = read,
      raw = record.serverResponseUnixMsHeader,
      parsed = typeof raw === 'string' && /^[1-9][0-9]*$/.test(raw) ? Number(raw) : NaN,
      serverTimestampValid = Number.isSafeInteger(parsed) && parsed > 0,
      serverResponseUnixMs = serverTimestampValid ? parsed : NaN;
    let expiresAt = NaN;
    try {
      expiresAt = Date.parse(read.value?.suggestion?.expiresAt);
    } catch {
      /* Invalid expiry fails the lease check below. */
    }
    const ttl = expiresAt - receivedAt,
      serverRemainingLeaseMs = expiresAt - serverResponseUnixMs;
    record.suggestionClock = {
      serverResponseUnixMsHeader: raw ?? null,
      serverResponseUnixMs: serverTimestampValid ? serverResponseUnixMs : null,
      serverRemainingLeaseMs: Number.isFinite(serverRemainingLeaseMs)
        ? serverRemainingLeaseMs
        : null,
      localTtlMs: Number.isFinite(ttl) ? ttl : null,
      localClockChecks: {
        ttlFinite: Number.isFinite(ttl),
        ttlPositive: ttl > 0,
        ttlAtMost6000: ttl <= 6000,
      },
      // One response timestamp only bounds offset; request processing and
      // asymmetric transit make an RTT midpoint an unreliable clock estimate.
      serverClockOffsetBoundsMs: serverTimestampValid
        ? {
            lower: serverResponseUnixMs - receivedAt,
            upper: serverResponseUnixMs - record.started.unixMs,
          }
        : null,
    };
    return { ttl, serverRemainingLeaseMs, serverTimestampValid };
  };
  const suggestion = (read, row, nativeRead) => {
    const { value: v, receivedAt, receivedMonotonicMs, record } = read;
    const observationId = sha(
      JSON.stringify([
        targetKey(proof.target),
        row.binding.predictionThreadId,
        row.binding.predictionTurnId,
      ]),
    );
    const expectedId = sha(
      JSON.stringify([
        targetKey(proof.target),
        row.binding.sourceThreadId,
        row.binding.sourceTurnId,
        observationId,
        row.textSha256,
      ]),
    );
    const s = v?.suggestion,
      textSha256 = typeof s?.text === 'string' ? sha(s.text) : null;
    const { ttl, serverRemainingLeaseMs, serverTimestampValid } = suggestionLease(read);
    const validation = {
      passed: false,
      bodyFile: record.bodyFile,
      responseSha256: record.responseSha256,
      expected: {
        target: proof.target,
        threadId: row.binding.sourceThreadId,
        sourceTurnId: row.binding.sourceTurnId,
        contentSha256: row.textSha256,
        expectedId,
      },
      suggestionState: s === null ? 'null' : s === undefined ? 'missing' : typeof s,
      textSha256,
      ttl: Number.isFinite(ttl) ? ttl : null,
      serverRemainingLeaseMs: Number.isFinite(serverRemainingLeaseMs)
        ? serverRemainingLeaseMs
        : null,
      checks: {
        target: targetKey(v?.target) === targetKey(proof.target),
        schemaVersion: v?.schemaVersion === 1,
        readOnly: v?.readOnly === true,
        source: v?.source === 'native-trace',
        threadId: v?.threadId === row.binding.sourceThreadId,
        sourceTurnId: v?.sourceTurnId === row.binding.sourceTurnId,
        suggestionPresent: Boolean(s),
        validity: s?.validity === 'native-version',
        id: typeof s?.id === 'string' && /^[a-f0-9]{64}$/.test(s.id) && s.id === expectedId,
        text: s?.text === row.observation.text,
        contentSha256: s?.contentSha256 === row.textSha256,
        bodyHash: textSha256 !== null && textSha256 === s?.contentSha256,
        serverTimestampValid,
        serverLeaseFinite: Number.isFinite(serverRemainingLeaseMs),
        serverLeasePositive: serverRemainingLeaseMs > 0,
        serverLeaseAtMost6000: serverRemainingLeaseMs <= 6000,
      },
    };
    record.suggestionValidation = validation;
    try {
      sameTarget(v.target);
      check(
        v.schemaVersion === 1 &&
          v.readOnly === true &&
          v.source === 'native-trace' &&
          v.threadId === row.binding.sourceThreadId &&
          v.sourceTurnId === row.binding.sourceTurnId,
        'source-response-mismatch',
      );
      check(serverTimestampValid, 'precise-server-response-timestamp-required');
      const s = v.suggestion;
      check(
        s &&
          s.validity === 'native-version' &&
          /^[a-f0-9]{64}$/.test(s.id) &&
          s.id === expectedId &&
          s.text === row.observation.text &&
          s.contentSha256 === row.textSha256 &&
          sha(s.text) === s.contentSha256 &&
          serverRemainingLeaseMs > 0 &&
          serverRemainingLeaseMs <= 6000,
        'native-version-bytes-hash-or-six-second-lease-mismatch',
      );
      validation.passed = true;
    } catch (e) {
      validation.failureCode = e.acceptanceCode ?? 'unexpected-' + e.name;
      throw e;
    }
    report.suggestions.push({
      nativeRead,
      upstreamReadId: record.id,
      receivedAt,
      receivedMonotonicMs,
      target: v.target,
      threadId: v.threadId,
      sourceTurnId: v.sourceTurnId,
      id: s.id,
      contentSha256: s.contentSha256,
      expiresAt: s.expiresAt,
      ttl,
      serverRemainingLeaseMs,
    });
  };
  const history = async (label) => {
    const result = {};
    for (const [index, row] of rows().entries()) {
      let cursor,
        page = 0;
      const ids = new Set(),
        cursors = new Set();
      do {
        const { value: v } = await remote(
          route('/v1/history', {
            threadId: row.binding.sourceThreadId,
            ...(cursor ? { cursor } : {}),
          }),
        );
        await json(label + '-history-' + index + '-' + page++ + '.json', v);
        sameTarget(v.readTarget);
        check(
          v.hostId === proof.target.hostId &&
            v.threadId === row.binding.sourceThreadId &&
            v.thread?.title === row.title &&
            v.runtime?.canControl === true &&
            v.runtime.state === 'idle' &&
            v.runtime.suggestionSourceTurnId === row.binding.sourceTurnId,
          'history-live-owner-source-mismatch',
        );
        check(Array.isArray(v.messages), 'history-messages-required');
        for (const m of v.messages.filter((m) => m.role === 'user')) {
          check(
            typeof m.id === 'string' && m.id.length > 0 && m.id.length <= 1024,
            'user-input-id-required',
          );
          ids.add(m.id);
        }
        cursor = v.nextCursor;
        check(
          !cursor || (typeof cursor === 'string' && !cursors.has(cursor) && page < 200),
          'history-pagination-incomplete',
        );
        cursors.add(cursor);
      } while (cursor);
      check(ids.size > 0, 'existing-user-inputs-required');
      result[row.binding.sourceThreadId] = [...ids].sort();
    }
    await json(label + '-input-ids.json', result);
    return result;
  };
  const tree = async (base, sub = '') => {
    const files = {};
    for (const name of (await readdir(join(base, sub))).sort()) {
      const p = join(sub, name),
        s = await lstat(join(base, p));
      check(!s.isSymbolicLink(), 'artifact-symlink-refused');
      if (s.isDirectory()) Object.assign(files, await tree(base, p));
      else if (s.isFile()) files[p] = sha(await readFile(join(base, p)));
    }
    return files;
  };
  try {
    await save();
    check(
      process.platform === 'darwin' && process.env.NODE_TLS_REJECT_UNAUTHORIZED !== '0',
      'mac-source-and-tls-verification-required',
    );
    const device = required('SUGGESTION_IOS_DEVICE_ID'),
      build = required('SUGGESTION_IOS_BUILD_NUMBER');
    port = required('SUGGESTION_HTTPS_PORT');
    check(
      /^[a-f0-9-]{16,64}$/i.test(device) && /^[1-9][0-9]{0,8}$/.test(build),
      'explicit-physical-device-and-build-required',
    );
    check(
      /^[0-9]{4,5}$/.test(port) && String(+port) === port && +port >= 1024 && +port <= 65535,
      'unused-nondefault-https-port-required',
    );
    const proofFile = process.env.SUGGESTION_REMOTE_PROOF_FILE,
      proofJSON = process.env.SUGGESTION_REMOTE_PROOF_JSON;
    check(Boolean(proofFile) !== Boolean(proofJSON), 'exactly-one-private-proof-required');
    if (proofFile) {
      check(isAbsolute(proofFile), 'absolute-proof-path-required');
      await privatePath(proofFile);
    }
    const proofBytes = proofJSON ?? (await readFile(proofFile, 'utf8'));
    proof = JSON.parse(proofBytes);
    token = proof.token ?? process.env.SUGGESTION_REMOTE_TOKEN;
    check(
      typeof token === 'string' && /^[\x21-\x7e]{16,4096}$/.test(token),
      'explicit-dev-token-required',
    );
    check(
      !process.env.SUGGESTION_REMOTE_TOKEN || process.env.SUGGESTION_REMOTE_TOKEN === token,
      'conflicting-dev-token',
    );
    check(
      proof.schemaVersion === 1 &&
        proof.platform === 'windows' &&
        proof.temporaryDEVHost === true &&
        proof.exactSourceBindingsVerified === true &&
        Array.isArray(rows()) &&
        rows().length === 2,
      'verified-temporary-windows-proof-required',
    );
    check(
      targetKeys.every((k) => typeof proof.target?.[k] === 'string' && uid.test(proof.target[k])) &&
        proof.target.agentId === 'codex' &&
        proof.target.runtimeId === 'desktop-runtime',
      'complete-desktop-machine-target-required',
    );
    const url = new URL(proof.endpoint);
    check(
      url.protocol === 'https:' &&
        url.pathname === '/' &&
        !url.search &&
        !url.hash &&
        !url.username &&
        !url.password,
      'fixed-https-origin-required',
    );
    endpoint = url.origin;
    for (const row of rows()) {
      check(
        uuid.test(row.binding?.sourceThreadId) &&
          uuid.test(row.binding?.sourceTurnId) &&
          typeof row.title === 'string' &&
          row.title.trim() &&
          typeof row.observation?.text === 'string' &&
          row.observation.text.length > 0 &&
          sha(row.observation.text) === row.textSha256,
        'exact-source-title-and-text-proof-required',
      );
      check(
        uuid.test(row.binding.predictionThreadId ?? '') &&
          uuid.test(row.binding.predictionTurnId ?? ''),
        'exact-prediction-binding-required',
      );
    }
    for (const row of rows()) {
      if (row.observation.target) sameTarget(row.observation.target);
      if (row.binding.target) sameTarget(row.binding.target);
      if (row.observation.contentSha256)
        check(row.observation.contentSha256 === row.textSha256, 'observation-hash-mismatch');
    }
    check(
      rows()[0].binding.sourceThreadId !== rows()[1].binding.sourceThreadId,
      'two-distinct-existing-sources-required',
    );
    validateBootstrap();
    if (proof.bootstrapReadOnlyThreadId !== undefined)
      report.bootstrap = {
        threadId: proof.bootstrapReadOnlyThreadId,
        declaredSameAuthorizedTask: true,
        readIds: [],
      };
    await json('private-proof.json', proof);
    report.proofSha256 = sha(proofBytes);
    report.target = proof.target;
    report.device = device;
    report.buildNumber = build;
    report.runnerSha256 = sha(await readFile(fileURLToPath(import.meta.url)));
    report.repositoryHead = run('git', ['-C', root, 'rev-parse', 'HEAD']).trim();
    phase = 'freeze-native';
    nativeSource = join(directory, 'native-source');
    native = join(nativeSource, 'apps/ios');
    report.paths = {
      nativeSource,
      native,
      build: join(directory, 'build'),
      mobileCoreBuild: join(nativeSource, '.build/native/mobile-core'),
      result: join(directory, 'result.xcresult'),
    };
    report.buildInputRoots = nativeBuildRoots;
    const trackedInputs = () => {
      check(
        !run('git', [
          '-C',
          root,
          'diff',
          '--name-only',
          '--diff-filter=U',
          '--',
          ...nativeBuildRoots,
        ]).trim(),
        'unmerged-native-build-inputs',
      );
      return [
        ...new Set(
          run('git', ['-C', root, 'ls-files', '--cached', '-z', '--', ...nativeBuildRoots])
            .split('\0')
            .filter((p) => p && !excludedNativeInput(p)),
        ),
      ].sort();
    };
    const tracked = trackedInputs(),
      inputs = await nativeBuildManifest(root);
    check(
      canonical(Object.keys(inputs).sort()) === canonical(tracked),
      'untracked-or-missing-native-build-inputs',
    );
    for (const p of ['gradlew', 'scripts/mobile/build-ios-core.sh'])
      check(inputs[p]?.executable, 'executable-native-build-input-required');
    report.buildInputSha256 = sha(canonical(inputs));
    await json('build-input-manifest.json', {
      schemaVersion: 1,
      roots: nativeBuildRoots,
      sha256: report.buildInputSha256,
      files: inputs,
    });
    await mkdir(nativeSource, { mode: 0o700 });
    for (const [p, input] of Object.entries(inputs)) {
      const bytes = await readFile(join(root, p));
      check(sha(bytes) === input.sha256, 'native-freeze-race');
      await mkdir(dirname(join(nativeSource, p)), { recursive: true, mode: 0o700 });
      await writeFile(join(nativeSource, p), bytes, {
        flag: 'wx',
        mode: input.executable ? 0o700 : 0o600,
      });
    }
    report.buildInputVerifications = [];
    verifyBuildInputs = async (stage) => {
      const result = {
        stage,
        utc: new Date().toISOString(),
        expectedSha256: report.buildInputSha256,
        sourceSha256: null,
        frozenSha256: null,
        passed: false,
      };
      report.buildInputVerifications.push(result);
      try {
        const source = await nativeBuildManifest(root),
          frozen = await nativeBuildManifest(nativeSource);
        result.sourceSha256 = sha(canonical(source));
        result.frozenSha256 = sha(canonical(frozen));
        check(
          canonical(Object.keys(source).sort()) === canonical(trackedInputs()),
          'native-build-input-list-drift',
        );
        check(result.sourceSha256 === result.expectedSha256, 'native-source-drift');
        check(result.frozenSha256 === result.expectedSha256, 'frozen-build-input-drift');
        result.passed = true;
      } catch (e) {
        result.failureCode = e.acceptanceCode ?? 'build-input-inspection-failed';
        throw e;
      } finally {
        await json('build-input-verification-' + stage + '.json', result);
      }
    };
    await verifyBuildInputs('after-freeze');
    const manifest = await tree(native);
    report.nativeSha256 = sha(canonical(manifest));
    for (const [p, hash] of Object.entries(manifest))
      check(sha(await readFile(join(root, 'apps/ios', p))) === hash, 'native-freeze-race');
    await json('native-manifest.json', manifest);
    // Only explicit local toolchain paths cross into the frozen build. Never
    // copy .local/mobile-toolchain.json, user Gradle config or cached outputs.
    report.buildToolchains = {};
    for (const [key, executable] of [
      ['JAVA_HOME', 'bin/java'],
      ['KOTLIN_NATIVE_HOME', 'bin/konanc'],
    ]) {
      const home = required(key);
      check(
        isAbsolute(home) &&
          !home.includes('\0') &&
          (await lstat(await realpath(home))).isDirectory(),
        'valid-native-toolchain-path-required',
      );
      const binary = await realpath(join(home, executable));
      const stat = await lstat(binary);
      check(stat.isFile() && stat.mode & 0o111, 'native-toolchain-executable-required');
      report.buildToolchains[key] = {
        path: home,
        resolvedPath: await realpath(home),
        executableSha256: sha(await readFile(binary)),
      };
    }
    env.GRADLE_USER_HOME ??= join(directory, 'gradle-home');
    check(
      isAbsolute(env.GRADLE_USER_HOME) && !env.GRADLE_USER_HOME.includes('\0'),
      'absolute-private-gradle-home-required',
    );
    await mkdir(env.GRADLE_USER_HOME, { recursive: true, mode: 0o700 });
    await privatePath(env.GRADLE_USER_HOME, true);
    const gradleRelative = relative(nativeSource, await realpath(env.GRADLE_USER_HOME));
    check(
      gradleRelative === '..' || gradleRelative.startsWith('../') || isAbsolute(gradleRelative),
      'gradle-home-must-be-outside-frozen-inputs',
    );
    report.paths.gradleUserHome = env.GRADLE_USER_HOME;
    env.PATH = dirname(process.execPath) + ':' + (env.PATH ?? '');
    phase = 'remote-preflight';
    const { value: health } = await remote('/healthz');
    await json('health.json', health);
    check(
      health.capabilities?.includes('desktop:suggestions:native-version:v1'),
      'native-version-capability-required',
    );
    const { value: workspace } = await remote(route('/v1/workspace'));
    await json('workspace.json', workspace);
    sameTarget(workspace.readTarget);
    check(
      workspace.hostId === proof.target.hostId && workspace.connectionMode === 'desktop-runtime',
      'remote-workspace-mismatch',
    );
    validateBootstrap(workspace);
    if (report.bootstrap) report.bootstrap.workspaceFirstVerified = true;
    for (const row of rows()) {
      const r = await remote(
        route('/v1/threads/next-turn-suggestion', {
          target: JSON.stringify(proof.target),
          threadId: row.binding.sourceThreadId,
          sourceTurnId: row.binding.sourceTurnId,
        }),
      );
      suggestion(r, row, false);
    }
    phase = 'readonly-proxy';
    beforeServe = serve();
    await json('serve-before.json', beforeServe);
    const status = JSON.parse(run(tailscale, ['status', '--json'])),
      dns = status.Self?.DNSName?.replace(/\.$/, '');
    check(dns?.endsWith('.ts.net'), 'own-tailscale-dns-required');
    ownWebKey = dns + ':' + port;
    check(
      !Object.hasOwn(beforeServe.TCP ?? {}, port) &&
        !Object.keys(beforeServe.Web ?? {}).some((k) => k.endsWith(':' + port)) &&
        !Object.hasOwn(beforeServe.AllowFunnel ?? {}, ownWebKey),
      'https-port-already-in-use',
    );
    // HostService.workspace builds its own query without hostId; HostService.url
    // adds hostId to all other ordinary App reads and fixed POSTs. Fork options
    // are loaded by RootView's reply-action task even without opening its menu.
    const reads = {
      '/healthz': ['hostId'],
      '/v1/workspace': ['hostId', 'readerId', 'projectCursor', 'threadCursor'],
      '/v1/history': ['hostId', 'threadId', 'readerId', 'cursor', 'window'],
      '/v1/events': ['hostId', 'threadId', 'readerId', 'epoch', 'after', 'window'],
      '/v1/models': ['hostId'],
      '/v1/threads/next-turn-suggestion': ['hostId', 'target', 'threadId', 'sourceTurnId'],
      '/v1/notifications/status': ['hostId', 'deviceId'],
      '/v1/notifications/events': ['hostId', 'deviceId', 'after'],
      '/v1/threads/fork-options': ['hostId', 'threadId'],
      '/v1/voice/readiness': ['hostId'],
    };
    const posts = {
      '/v1/threads/read': ['hostId', 'threadId', 'readerId', 'position', 'target'],
      '/v1/notifications/register': ['hostId', 'registration'],
      '/v1/notifications/preferences': ['hostId', 'deviceId', 'target', 'preferences'],
      '/v1/notifications/unregister': ['hostId', 'deviceId'],
    };
    const bootstrapReads = new Set([
      '/v1/history',
      '/v1/events',
      '/v1/threads/fork-options',
      '/v1/threads/next-turn-suggestion',
    ]);
    proxy = createServer(async (req, res) => {
      pending++;
      const client = {
        id: report.proxyClients.length + 1,
        started: stamp(),
        method: req.method,
        pathSha256: sha((req.url ?? '').split('?')[0]),
      };
      report.proxyClients.push(client);
      req.once('aborted', () => {
        client.requestAborted = stamp();
      });
      res.once('finish', () => {
        // finish means handed to the transport, not proof the phone consumed it.
        client.responseFinished = stamp();
      });
      res.once('close', () => {
        client.closed = stamp();
        client.closeKind = res.writableFinished ? 'normal' : 'early';
        client.closePhase = phase;
        client.responseStatus = res.headersSent ? res.statusCode : null;
      });
      res.on('error', (e) => {
        report.proxyClientDisconnects = (report.proxyClientDisconnects ?? 0) + 1;
        client.responseError = { at: stamp(), errorName: e.name };
      });
      try {
        check(
          req.headers.authorization === 'Bearer ' + token &&
            req.url.startsWith('/') &&
            !req.url.startsWith('//'),
          'proxy-auth-or-route',
        );
        const u = new URL(req.url, 'http://localhost'),
          method = req.method;
        if (!(method === 'GET' && reads[u.pathname]) && !(method === 'POST' && posts[u.pathname])) {
          report.denied.push({ method, pathSha256: sha(u.pathname) });
          res.writeHead(405).end();
          return;
        }
        for (const k of new Set(u.searchParams.keys()))
          check(
            u.searchParams.getAll(k).length === 1 &&
              (method === 'GET' ? reads[u.pathname] : ['hostId']).includes(k),
            'undeclared-or-duplicate-query-field',
          );
        if (u.searchParams.has('window'))
          check(u.searchParams.get('window') === 'latest-v1', 'readonly-window-value-required');
        if (!['/healthz', '/v1/workspace'].includes(u.pathname))
          check(u.searchParams.has('hostId'), 'request-host-required');
        if (u.searchParams.has('hostId'))
          check(u.searchParams.get('hostId') === proof.target.hostId, 'request-host-mismatch');
        const thread = u.searchParams.get('threadId'),
          bootstrapGet =
            method === 'GET' && bootstrapReads.has(u.pathname) && isBootstrapThread(thread);
        let bootstrapReadOnly = bootstrapGet;
        if (u.pathname === '/v1/events')
          check(uuid.test(thread ?? ''), 'events-specific-thread-required');
        if (thread)
          check(
            rows().some((r) => r.binding.sourceThreadId === thread) || bootstrapGet,
            'request-thread-mismatch',
          );
        if (u.searchParams.has('target')) sameTarget(JSON.parse(u.searchParams.get('target')));
        const sourceRow = rows().find((r) => r.binding.sourceThreadId === thread);
        if (u.pathname === '/v1/threads/next-turn-suggestion')
          check(
            (sourceRow || bootstrapGet) &&
              u.searchParams.has('target') &&
              ['hostId', 'target', 'threadId', 'sourceTurnId'].every((k) =>
                u.searchParams.has(k),
              ) &&
              (sourceRow
                ? u.searchParams.get('sourceTurnId') === sourceRow.binding.sourceTurnId
                : uuid.test(u.searchParams.get('sourceTurnId') ?? '') &&
                  u.searchParams.get('sourceTurnId') !== thread),
            'request-source-mismatch',
          );
        let body;
        if (method === 'POST') {
          const chunks = [];
          let size = 0;
          for await (const chunk of req) {
            size += chunk.length;
            check(size <= 65536, 'post-body-limit');
            chunks.push(chunk);
          }
          body = Buffer.concat(chunks).toString('utf8');
          const data = JSON.parse(body);
          check(
            data &&
              typeof data === 'object' &&
              !Array.isArray(data) &&
              Object.keys(data).length === posts[u.pathname].length &&
              posts[u.pathname].every((k) => Object.hasOwn(data, k)) &&
              data.hostId === proof.target.hostId,
            'post-contract-mismatch',
          );
          if (posts[u.pathname].includes('target')) sameTarget(data.target);
          if (u.pathname === '/v1/notifications/register') sameTarget(data.registration?.target);
          if (data.threadId)
            check(
              rows().some((r) => r.binding.sourceThreadId === data.threadId) ||
                (u.pathname === '/v1/threads/read' && isBootstrapThread(data.threadId)),
              'post-thread-mismatch',
            );
          bootstrapReadOnly = u.pathname === '/v1/threads/read' && isBootstrapThread(data.threadId);
        }
        report.requests.push({
          proxyRequestId: client.id,
          method,
          path: u.pathname,
          queryFields: [...u.searchParams.keys()].sort(),
          ...(body ? { bodyFields: Object.keys(JSON.parse(body)).sort() } : {}),
        });
        const r = await remote(u.pathname + u.search, method, body, client, bootstrapReadOnly),
          v = r.value;
        if (r.record.bootstrapStatusOnly) {
          res
            .writeHead(r.record.status, {
              'Content-Type': r.record.contentType ?? 'application/octet-stream',
              'Cache-Control': 'no-store',
            })
            .end(r.statusBody);
          return;
        }
        if (u.pathname === '/v1/threads/next-turn-suggestion') {
          if (bootstrapGet) {
            // The default selection is read-only navigation, never an A/B match.
            sameTarget(v.target);
            check(
              v.schemaVersion === 1 &&
                v.readOnly === true &&
                v.source === 'native-trace' &&
                v.threadId === thread &&
                v.sourceTurnId === u.searchParams.get('sourceTurnId'),
              'bootstrap-source-response-mismatch',
            );
            const s = v.suggestion,
              { ttl, serverRemainingLeaseMs, serverTimestampValid } = suggestionLease(r);
            r.record.bootstrapSuggestion = {
              empty: s === null,
              ttl: Number.isFinite(ttl) ? ttl : null,
              serverRemainingLeaseMs: Number.isFinite(serverRemainingLeaseMs)
                ? serverRemainingLeaseMs
                : null,
              bodyFile: r.record.bodyFile,
            };
            check(serverTimestampValid, 'precise-server-response-timestamp-required');
            check(
              s === null ||
                (s &&
                  s.validity === 'native-version' &&
                  /^[a-f0-9]{64}$/.test(s.id) &&
                  typeof s.text === 'string' &&
                  s.text.length > 0 &&
                  sha(s.text) === s.contentSha256 &&
                  serverRemainingLeaseMs > 0 &&
                  serverRemainingLeaseMs <= 6000),
              'bootstrap-native-version-hash-or-six-second-lease-mismatch',
            );
          } else suggestion(r, sourceRow, phase === 'native');
        }
        if (['/v1/history', '/v1/workspace'].includes(u.pathname)) sameTarget(v.readTarget);
        else if (v.readTarget) sameTarget(v.readTarget);
        if (u.pathname === '/v1/history') check(v.threadId === thread, 'history-thread-mismatch');
        if (u.pathname === '/v1/events') {
          check(
            v.hostId === proof.target.hostId && Array.isArray(v.events),
            'event-batch-host-shape-mismatch',
          );
          for (const event of v.events) {
            check(
              event?.threadId === thread && event.snapshot?.threadId === thread,
              'event-thread-snapshot-mismatch',
            );
            check(event.snapshot.hostId === proof.target.hostId, 'event-snapshot-host-mismatch');
            sameTarget(event.snapshot.readTarget);
          }
        } else {
          for (const event of v.events ?? [])
            if (event.snapshot?.readTarget) sameTarget(event.snapshot.readTarget);
        }
        res
          .writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
          .end(JSON.stringify(v));
      } catch (e) {
        fail(e.acceptanceCode ?? 'proxy-request-validation-or-upstream-failure');
        if (!res.headersSent && !res.destroyed) res.writeHead(502).end();
        else res.destroy();
      } finally {
        if (--pending === 0) drained?.();
      }
    });
    await new Promise((done, reject) => {
      proxy.once('error', reject);
      proxy.listen(0, '127.0.0.1', done);
    });
    localProxy = 'http://127.0.0.1:' + proxy.address().port;
    serveAttempted = true;
    run(tailscale, ['serve', '--bg', '--https=' + port, localProxy]);
    const during = serve();
    ownedServe = during;
    await json('serve-during.json', during);
    const stripOwn = (v) => {
      const copy = structuredClone(v);
      for (const [k, entry] of [
        ['TCP', port],
        ['Web', ownWebKey],
        ['AllowFunnel', ownWebKey],
      ]) {
        delete copy[k]?.[entry];
        if (!Object.hasOwn(beforeServe, k) && !Object.keys(copy[k] ?? {}).length) delete copy[k];
      }
      return copy;
    };
    check(
      canonical(stripOwn(during)) === canonical(beforeServe) &&
        during.Web?.[ownWebKey]?.Handlers?.['/']?.Proxy === localProxy,
      'serve-original-config-changed',
    );
    fixture = {
      endpoint: 'https://' + ownWebKey,
      token,
      hostId: proof.target.hostId,
      nonce: randomUUID().slice(0, 8),
      adoptionVerification: true,
      existingConnection: false,
    };
    rows().forEach((row, i) => {
      const k = i ? 'B' : 'A';
      Object.assign(fixture, {
        ['thread' + k + 'Id']: row.binding.sourceThreadId,
        ['thread' + k + 'Title']: row.title,
        ['source' + k + 'TurnId']: row.binding.sourceTurnId,
        ['text' + k]: row.observation.text,
      });
    });
    await json('runner-fixture.json', fixture);
    const throughServe = await fetch(fixture.endpoint + route('/v1/workspace'), {
      redirect: 'error',
      cache: 'no-store',
      headers: { Authorization: 'Bearer ' + token },
      signal: AbortSignal.timeout(15000),
    });
    check(throughServe.status === 200, 'temporary-https-read-preflight');
    sameTarget((await throughServe.json()).readTarget);
    const xcode = async (action) => {
      check(!report.interrupted, 'interrupted-before-xcode');
      if (action === 'test-without-building') report.nativeTestStarted = true;
      const log = createWriteStream(join(directory, action + '.log'), { mode: 0o600 });
      log.on('error', () => fail('private-log-write-failed'));
      let sequence = 0;
      const secrets = [
        token,
        JSON.stringify(fixture),
        ...rows().flatMap((r) => [
          r.title,
          r.observation.text,
          ...r.observation.text.split(/\r?\n/),
        ]),
      ].filter(Boolean);
      const redact = (line) => {
        let safe = stripVTControlCharacters(line);
        for (const secret of secrets)
          for (const form of [secret, JSON.stringify(secret).slice(1, -1)])
            safe = safe.replaceAll(form, '[REDACTED]');
        return safe.replace(/Bearer\s+\S+/g, 'Bearer [REDACTED]');
      };
      const diagnostic = (line) => {
        line = stripVTControlCharacters(line);
        // Never retain typed values, assertion values or multiline UI hierarchy.
        if (/\bType\b|typeText|SUGGESTION_DEV_UI_FAILURE|SecureTextField/.test(line))
          return undefined;
        if (
          /^Test (?:Case|Suite) '[A-Za-z0-9_. [\]-]+' (?:started|passed|failed|finished)\b/.test(
            line,
          ) ||
          /^\s*Executed \d+ tests?, with \d+ failures? \(\d+ unexpected\) in [0-9.]+ \([0-9.]+\) seconds\s*$/.test(
            line,
          ) ||
          /^\*\* (?:TEST|BUILD)(?: EXECUTE)? (?:SUCCEEDED|FAILED|CANCELLED) \*\*$/.test(line) ||
          /^Testing (?:started|failed|cancelled):?$/.test(line)
        )
          return redact(line);
        if (
          action === 'build-for-testing' &&
          /^(?:\/[^\r\n]+:\d+(?::\d+)?:\s*(?:error|warning|note):|(?:fatal )?error:|ld:|clang:|xcodebuild: error:)/.test(
            line,
          )
        )
          return redact(line);
        if (action === 'test-without-building' && /^\/[^\r\n]+:\d+(?::\d+)?: error:/.test(line))
          return redact(line.replace(/ error:.*/, ' error: [test assertion details redacted]'));
        if (/^xcodebuild: error:/.test(line)) return redact(line);
        return undefined;
      };
      child = spawn(
        join(env.DEVELOPER_DIR, 'usr/bin/xcodebuild'),
        [
          '-project',
          join(native, 'CodexManage.xcodeproj'),
          '-scheme',
          'CodexManage',
          '-destination',
          'platform=iOS,id=' + device,
          '-derivedDataPath',
          join(directory, 'build'),
          ...(action === 'test-without-building'
            ? ['-resultBundlePath', join(directory, 'result.xcresult')]
            : []),
          '-parallel-testing-enabled',
          'NO',
          '-collect-test-diagnostics',
          'never',
          '-only-testing:CodexManageUITests/CodexManageUITests/testOriginalDesktopReadOnlySuggestionsDEV',
          'ONLY_ACTIVE_ARCH=YES',
          'CURRENT_PROJECT_VERSION=' + build,
          '-allowProvisioningUpdates',
          action,
        ],
        {
          env: { ...env, TEST_RUNNER_SUGGESTION_DEV_FIXTURE_JSON: JSON.stringify(fixture) },
          stdio: ['ignore', 'pipe', 'pipe'],
        },
      );
      for (const stream of [child.stdout, child.stderr])
        createInterface({ input: stream }).on('line', (line) => {
          sequence++;
          log.write(
            JSON.stringify({
              sequence,
              lineSha256: sha(line),
              marker: line.match(/SUGGESTION_DEV_[A-Z_]+/)?.[0],
              diagnostic: diagnostic(line),
            }) + '\n',
          );
          if (action !== 'test-without-building') return;
          const marker = line.slice(line.indexOf('SUGGESTION_DEV_'));
          let m;
          if ((m = marker.match(/^SUGGESTION_DEV_MATCH (\d+) (\S+) (\S+)$/)))
            report.matches.push(m.slice(1));
          if ((m = marker.match(/^SUGGESTION_DEV_ADOPTION_PASS (\d+) local-draft-only$/)))
            report.adoptions.push(+m[1]);
          if ((m = marker.match(/^SUGGESTION_DEV_ORIGINAL_CLIENT (\S+)$/))) {
            report.originalClientId = m[1];
            report.originalClientSequence = sequence;
          }
          if ((m = marker.match(/^SUGGESTION_DEV_CLEANUP (\S+)$/))) {
            // Swift verifies selection using afterRemoval or the restored scan,
            // then closes the picker and emits CLEANUP; no scan follows it.
            report.cleanup.completion = {
              restoredClientId: m[1],
              sequence,
              scanIndex: report.scans.length - 1,
            };
          }
          if ((m = marker.match(/^SUGGESTION_DEV_CLIENT_SCAN (\[.*\]) selected (\S+)$/))) {
            try {
              const ids = JSON.parse(m[1]);
              check(
                Array.isArray(ids) &&
                  ids.every((id) => typeof id === 'string') &&
                  ids.includes(m[2]),
                'client-scan-shape',
              );
              report.scans.push({ ids: [...new Set(ids)].sort(), selectedId: m[2], sequence });
            } catch {
              fail('client-scan-parse');
            }
          }
        });
      const code = await new Promise((done) => {
        child.once('error', () => done(-1));
        child.once('close', done);
      });
      child = undefined;
      await new Promise((done) => {
        if (log.closed) done();
        else {
          log.once('close', done);
          log.end();
        }
      });
      check(code === 0, 'xcode-' + action + '-failed');
    };
    phase = 'build';
    await verifyBuildInputs('before-build');
    await xcode('build-for-testing');
    await verifyBuildInputs('after-build');
    for (const [p, hash] of Object.entries(manifest))
      check(sha(await readFile(join(native, p))) === hash, 'frozen-native-changed');
    const app = join(directory, 'build/Build/Products/Debug-iphoneos/CodexManage.app');
    Object.assign(report.paths, {
      app,
      native,
      build: join(directory, 'build'),
      result: join(directory, 'result.xcresult'),
    });
    const plist = (key) =>
      run('/usr/libexec/PlistBuddy', ['-c', 'Print ' + key, join(app, 'Info.plist')]).trim();
    check(
      plist('CFBundleIdentifier') === 'com.easyai.agenthub' && plist('CFBundleVersion') === build,
      'built-app-identity-or-build-mismatch',
    );
    report.executableSha256 = sha(await readFile(join(app, plist('CFBundleExecutable'))));
    await json('app-manifest.json', await tree(app));
    const frameworkRoot = join(report.paths.mobileCoreBuild, 'xcode-frameworks/Debug');
    report.mobileCoreFrameworks = [];
    for (const sdk of (await readdir(frameworkRoot)).sort()) {
      if (!/^iphoneos[0-9.]*$/.test(sdk)) continue;
      const path = join(frameworkRoot, sdk, 'MobileCore.framework'),
        files = await tree(path);
      check(
        files.MobileCore &&
          files['Headers/MobileCore.h'] &&
          files['Modules/module.modulemap'] &&
          files['Info.plist'],
        'complete-device-mobile-core-framework-required',
      );
      const architectures = run('xcrun', ['lipo', '-archs', join(path, 'MobileCore')])
        .trim()
        .split(/\s+/);
      check(
        architectures.includes('arm64') && !architectures.includes('x86_64'),
        'physical-mobile-core-framework-required',
      );
      const manifestFile =
        'mobile-core-framework-' + report.mobileCoreFrameworks.length + '-manifest.json';
      await json(manifestFile, files);
      report.mobileCoreFrameworks.push({
        path,
        sdk,
        architectures,
        manifestFile,
        sha256: sha(canonical(files)),
        binarySha256: files.MobileCore,
        headerSha256: files['Headers/MobileCore.h'],
        moduleMapSha256: files['Modules/module.modulemap'],
        infoPlistSha256: files['Info.plist'],
        buildInputSha256: report.buildInputSha256,
      });
    }
    check(report.mobileCoreFrameworks.length > 0, 'built-device-mobile-core-framework-required');
    phase = 'device-preflight';
    const detailsFile = join(directory, 'device-details.json');
    run('xcrun', [
      'devicectl',
      'device',
      'info',
      'details',
      '--device',
      device,
      '--json-output',
      detailsFile,
    ]);
    const hardware = JSON.parse(await readFile(detailsFile, 'utf8')).result?.hardwareProperties;
    check(
      hardware?.reality === 'physical' &&
        hardware.deviceType === 'iPhone' &&
        hardware.platform === 'iOS',
      'physical-iphone-required',
    );
    const appsFile = join(directory, 'device-apps-before.json');
    run('xcrun', [
      'devicectl',
      'device',
      'info',
      'apps',
      '--device',
      device,
      '--json-output',
      appsFile,
    ]);
    const installed = JSON.parse(await readFile(appsFile, 'utf8')).result?.apps;
    check(Array.isArray(installed), 'physical-installed-app-inventory-required');
    const current = installed.find((a) => a.bundleIdentifier === 'com.easyai.agenthub');
    check(
      current && /^[0-9]+$/.test(String(current.bundleVersion)) && +build >= +current.bundleVersion,
      'existing-app-build-required-refuse-downgrade',
    );
    report.previousBuildNumber = current.bundleVersion;
    run('xcrun', [
      'devicectl',
      'device',
      'install',
      'app',
      '--device',
      device,
      '--json-output',
      join(directory, 'install.json'),
      app,
    ]);
    report.appInstalled = true;
    phase = 'before-history';
    baseline = await history('before');
    report.inputIdsBefore = baseline;
    phase = 'native';
    await save();
    await xcode('test-without-building');
    await verifyBuildInputs('after-native');
    const expectedMatches = [0, 1, 2, 3].map((i) => [
      String(i),
      rows()[i % 2].binding.sourceThreadId,
      rows()[i % 2].binding.sourceTurnId,
    ]);
    check(
      canonical(report.matches) === canonical(expectedMatches) &&
        canonical(report.adoptions) === '[0,1,2,3]',
      'four-native-matches-and-adoptions-required',
    );
    for (const row of rows()) {
      const hits = report.suggestions
        .filter((r) => r.nativeRead && r.threadId === row.binding.sourceThreadId)
        .sort((a, b) => a.receivedMonotonicMs - b.receivedMonotonicMs);
      const first = hits[0],
        last = hits.at(-1);
      report.renewalCoverage.push({
        threadId: row.binding.sourceThreadId,
        hits: hits.length,
        firstReadId: first?.upstreamReadId,
        lastReadId: last?.upstreamReadId,
        elapsedMonotonicMs: first ? last.receivedMonotonicMs - first.receivedMonotonicMs : null,
        elapsedWallMs: first ? last.receivedAt - first.receivedAt : null,
      });
      check(
        hits.length >= 2 &&
          hits.at(-1).receivedMonotonicMs - hits[0].receivedMonotonicMs > 6000 &&
          Date.parse(hits.at(-1).expiresAt) > Date.parse(hits[0].expiresAt),
        'real-http-renewals-for-both-sources-required',
      );
    }
    report.nativeAssertionsPassed = true;
  } catch (e) {
    fail(phase + ':' + (e.acceptanceCode ?? 'unexpected-' + e.name));
  } finally {
    phase = 'cleanup';
    child?.kill('SIGINT');
    if (baseline)
      try {
        const after = await history('after');
        report.inputIdsAfter = after;
        check(canonical(baseline) === canonical(after), 'user-input-ids-changed');
        report.noAdditionalInputs = true;
      } catch {
        fail('after-history-or-input-comparison-failed');
      }
    if (report.nativeTestStarted) {
      const completion = report.cleanup.completion,
        first = report.scans[0],
        last = report.scans[completion?.scanIndex];
      report.cleanup.temporaryConnectionObserved = Boolean(
        first &&
        completion &&
        report.scans.some(
          (s) =>
            s.sequence > first.sequence &&
            s.sequence < completion.sequence &&
            s.ids.includes(proof.target.hostId) &&
            first.ids.every((id) => s.ids.includes(id)),
        ),
      );
      report.cleanup.phoneRestored = Boolean(
        first &&
        last &&
        report.originalClientId &&
        first.sequence < report.originalClientSequence &&
        report.originalClientSequence < last.sequence &&
        last.sequence < completion.sequence &&
        completion.restoredClientId === report.originalClientId &&
        last.selectedId === first.selectedId &&
        first.selectedId === report.originalClientId &&
        canonical(first.ids) === canonical(last.ids) &&
        !first.ids.includes(proof.target.hostId) &&
        !last.ids.includes(proof.target.hostId) &&
        report.cleanup.temporaryConnectionObserved,
      );
      if (!report.cleanup.phoneRestored)
        fail('phone-restoration-unverified-retain-private-evidence');
    }
    if (serveAttempted)
      try {
        const current = serve();
        await json('serve-cleanup-before.json', current);
        if (current.TCP?.[port] || current.Web?.[ownWebKey]) {
          check(
            current.Web?.[ownWebKey]?.Handlers?.['/']?.Proxy === localProxy &&
              Object.keys(current.Web[ownWebKey].Handlers).length === 1,
            'temporary-port-owner-changed',
          );
          check(
            ownedServe &&
              [
                ['TCP', port],
                ['Web', ownWebKey],
                ['AllowFunnel', ownWebKey],
              ].every(
                ([k, entry]) =>
                  canonical(current[k]?.[entry] ?? null) ===
                  canonical(ownedServe[k]?.[entry] ?? null),
              ),
            'temporary-port-config-changed-or-unknown',
          );
          run(tailscale, ['serve', '--https=' + port, 'off']);
        }
        const after = serve();
        await json('serve-after.json', after);
        check(canonical(after) === canonical(beforeServe), 'serve-config-not-restored');
        report.cleanup.serveRestored = true;
      } catch {
        report.cleanup.serveRestored = false;
        fail('serve-restoration-unverified-retain-private-evidence');
      }
    if (proxy) {
      proxy.closeAllConnections();
      await new Promise((done) => proxy.close(done));
      if (pending)
        await new Promise((done) => {
          drained = done;
        });
      report.cleanup.proxyClosed = true;
    }
    if (verifyBuildInputs)
      try {
        await verifyBuildInputs('cleanup');
        for (const framework of report.mobileCoreFrameworks ?? [])
          check(
            sha(canonical(await tree(framework.path))) === framework.sha256,
            'built-mobile-core-framework-drift',
          );
      } catch (e) {
        fail(e.acceptanceCode ?? 'final-build-input-verification-failed');
      }
    for (const name of (await readdir(directory)).filter(
      (n) => !['build', 'native-source', 'gradle-home', 'report.json', 'report.sha256'].includes(n),
    )) {
      try {
        const path = join(directory, name),
          s = await lstat(path);
        report.artifacts[name] = {
          path,
          sha256: s.isDirectory() ? sha(canonical(await tree(path))) : sha(await readFile(path)),
        };
      } catch (e) {
        if (e.code !== 'ENOENT') fail('artifact-fingerprint-failed');
      }
    }
    process.removeListener('SIGINT', interrupted);
    process.removeListener('SIGTERM', interrupted);
    if (report.denied.some((r) => r.method !== 'GET')) fail('blocked-command-attempted');
    report.status =
      report.nativeAssertionsPassed && report.noAdditionalInputs && !report.failures.length
        ? 'passed'
        : 'failed';
    report.physicalNativeVerified = report.status === 'passed';
    report.finishedAt = new Date().toISOString();
    await save();
    await writeFile(
      join(directory, 'report.sha256'),
      sha(await readFile(join(directory, 'report.json'))) + '\n',
      { mode: 0o600 },
    );
    if (report.status !== 'passed') process.exitCode = 1;
    console.log(
      JSON.stringify({
        status: report.status,
        evidenceDirectory: directory,
        physicalNativeVerified: report.physicalNativeVerified,
      }),
    );
  }
}
await main().catch(() => {
  console.error('Remote suggestion acceptance failed; check explicit private inputs/evidence.');
  process.exitCode = 1;
});
