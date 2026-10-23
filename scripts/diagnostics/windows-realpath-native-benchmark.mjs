// Standalone synthetic diagnostic, never a production reader/preload.
// node --experimental-import-meta-resolve <this-file> --program <verified-program-root> --operation <new-v4-UUID>
// Retains only a NEW <program>/.local/windows-realpath-benchmark/<operation>/.
import fs from 'node:fs';
import { createHash } from 'node:crypto';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const fail = (code) => Object.assign(new Error(code), { code });
const fact = (value) => {
  if (!value) throw fail('FIXTURE_FACT_FAILED');
};
const safeLabel = (value) =>
  typeof value === 'string' && /^[A-Za-z0-9_]{1,64}$/.test(value) ? value : null;
const failure = (error) => ({
  returned: false,
  exact: false,
  errorName: safeLabel(error?.name),
  errorCode: safeLabel(error?.code),
});
const outcome = (value, input) => ({
  returned: true,
  type: Buffer.isBuffer(value) ? 'buffer' : typeof value,
  digest: typeof value === 'string' || Buffer.isBuffer(value) ? sha256(value) : null,
  exact: typeof input === 'string' && typeof value === 'string' && value === input,
});

export function parseArguments(args) {
  if (
    args.length !== 4 ||
    args[0] !== '--program' ||
    !isAbsolute(args[1]) ||
    args[2] !== '--operation' ||
    !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(args[3])
  )
    throw fail('EXPECTED_PROGRAM_AND_NEW_V4_OPERATION');
  return { program: resolve(args[1]), operation: args[3].toLowerCase() };
}

// Diagnostic candidate only. In particular, do not normalize case or infer
// readiness from a returned path. Each call tries native afresh, then falls back
// with the original receiver/arguments/error semantics if it is not exact.
export function fastPositive(ordinary, native, counts = {}) {
  const increment = (name) => (counts[name] = (counts[name] ?? 0) + 1);
  return function (...args) {
    if (typeof native === 'function') {
      increment('nativeAttempts');
      try {
        const value = Reflect.apply(native, this, args);
        if (typeof args[0] === 'string' && value === args[0]) {
          increment('nativeExactHits');
          return value;
        }
        increment('nativeNonExact');
      } catch {
        increment('nativeErrors');
      }
    } else increment('nativeMissing');
    increment('ordinaryFallbacks');
    return Reflect.apply(ordinary, this, args);
  };
}

function invoke(fn, input, options) {
  let value,
    error,
    rejected = false;
  const start = process.hrtime.bigint();
  try {
    value = Reflect.apply(fn, fs, options === undefined ? [input] : [input, options]);
  } catch (caught) {
    error = caught;
    rejected = true;
  }
  const elapsedNs = Number(process.hrtime.bigint() - start);
  // Hashing/serialization is outside the timed interval. No path/error message
  // or synthetic file body is included in diagnostic output.
  return { elapsedNs, outcome: rejected ? failure(error) : outcome(value, input) };
}

export function runMatrix(cases, ordinary, native, budgetMs = 30000) {
  const counts = {};
  const candidate = fastPositive(ordinary, native, counts);
  const rawNative =
    typeof native === 'function'
      ? native
      : () => {
          throw fail('NATIVE_MISSING');
        };
  const resolvers = { ordinary, rawNative, fastPositive: candidate };
  const report = {
    rounds: 8,
    callsPerBlock: 4,
    deadlineMs: budgetMs,
    complete: false,
    candidateApprovedForProduction: false,
    candidateRejected: false,
    preflight: [],
    rows: [],
    comparison: [],
    candidateCounts: counts,
  };
  const deadline = Date.now() + budgetMs;
  const checkBudget = () => {
    if (Date.now() >= deadline) throw fail('BENCHMARK_BUDGET_EXHAUSTED');
  };
  try {
    for (const [caseName, input, options] of cases) {
      const values = {};
      for (const [name, fn] of Object.entries(resolvers)) {
        checkBudget();
        values[name] = invoke(fn, input, options).outcome;
      }
      const candidateAcceptsOrdinaryRejects = values.fastPositive.exact && !values.ordinary.exact;
      report.candidateRejected ||= candidateAcceptsOrdinaryRejects;
      report.preflight.push({ caseName, values, candidateAcceptsOrdinaryRejects });
    }
    const names = report.candidateRejected
      ? ['ordinary', 'rawNative']
      : ['ordinary', 'rawNative', 'fastPositive'];
    report.resolvers = names;
    report.callsPerResolverPerCasePerRound = names.length * report.callsPerBlock;
    for (let round = 0; round < 8; round++) {
      // Every resolver occupies every position once IN EACH round, including
      // the eight-round three-way case (simple alternating order is unbalanced).
      const base = round % 2 ? [...names].reverse() : names;
      for (const [caseName, input, options] of cases) {
        for (let block = 0; block < names.length; block++) {
          const order = [...base.slice(block), ...base.slice(0, block)];
          const values = {};
          for (const [position, name] of order.entries()) {
            const durationsNs = [],
              outcomes = [];
            for (let call = 0; call < report.callsPerBlock; call++) {
              checkBudget();
              const measured = invoke(resolvers[name], input, options);
              durationsNs.push(measured.elapsedNs);
              outcomes.push(measured.outcome);
            }
            values[name] = outcomes;
            report.rows.push({
              round: round + 1,
              caseName,
              block,
              position,
              resolver: name,
              durationsNs,
              outcomes,
            });
          }
          for (const name of names.filter((name) => name !== 'ordinary')) {
            const acceptsOrdinaryRejects = values[name].some(
              (value, i) => value.exact && !values.ordinary[i].exact,
            );
            report.comparison.push({
              round: round + 1,
              caseName,
              block,
              resolver: name,
              sameOutputAndError: JSON.stringify(values[name]) === JSON.stringify(values.ordinary),
              sameExactDecision: values[name].every(
                (value, i) => value.exact === values.ordinary[i].exact,
              ),
              acceptsOrdinaryRejects,
            });
            if (name === 'fastPositive' && acceptsOrdinaryRejects) report.candidateRejected = true;
          }
        }
      }
    }
    report.complete = true;
  } catch (error) {
    report.failure = failure(error);
  }
  return report;
}

// No recursive mkdir of caller-controlled paths and no reuse/ACL change of an
// existing operation. Only the two fixed parents may already exist.
export function createOperationRoot(program, operation) {
  parseArguments(['--program', program, '--operation', operation]);
  let parent = program;
  for (const name of ['.local', 'windows-realpath-benchmark']) {
    parent = join(parent, name);
    try {
      fs.mkdirSync(parent, { mode: 0o700 });
    } catch (error) {
      if (error.code !== 'EEXIST') throw error;
    }
    const stat = fs.lstatSync(parent);
    if (!stat.isDirectory() || stat.isSymbolicLink() || fs.realpathSync(parent) !== parent)
      throw fail('UNSAFE_OPERATION_PARENT');
  }
  const root = join(parent, operation);
  fs.mkdirSync(root, { mode: 0o700 }); // EEXIST deliberately fails, including empty directories.
  return root;
}

export function syntheticCases(root) {
  const held = [];
  const cases = [];
  const setup = {};
  const unsupported = [
    'case-sensitive-NTFS-directory: not enabled or assumed',
    'ancestor-permission-denial: no ACL broadening or privilege changes',
    'junction-loop: not exercised by synchronous in-process timing',
    'concurrent-external-retarget: only deterministic own ancestor replacement exercised',
    'UNC/network/removable/8.3 paths: not exercised',
  ];
  const close = () => {
    for (const fd of held.splice(0)) fs.closeSync(fd);
  };
  const write = (name) => {
    const path = join(root, name);
    fs.mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
    fs.writeFileSync(path, 'synthetic\n', { flag: 'wx', mode: 0o600 });
    return path;
  };
  try {
    const short = write('short.txt'),
      mixed = write('MiXeD/AbC.txt'),
      unicode = write('中文🙂/正文.txt');
    cases.push(
      ['root', root],
      ['short', short],
      ['mixed-original', mixed],
      ['mixed-alternate', join(root, 'mixed', 'abc.TXT')],
      ['unicode', unicode],
      ['missing', join(root, 'absent.txt')],
      ['not-directory', join(short, 'child')],
      ['null-path', null],
      ['undefined-path', undefined],
      ['nul-path', root + '\0bad'],
      ['utf8-option', unicode, { encoding: 'utf8' }],
      ['buffer-option', unicode, { encoding: 'buffer' }],
      ['null-option', short, null],
      ['invalid-encoding', short, { encoding: 'not-an-encoding' }],
      ['buffer-input', Buffer.from(unicode)],
      ['file-url-input', pathToFileURL(unicode)],
    );
    if (process.platform === 'win32')
      cases.push(['drive-case', short[0].toLowerCase() + short.slice(1)]);
    try {
      const long = write(join(...Array(4).fill('long-' + 'x'.repeat(70)), 'leaf.txt'));
      fact(long.length > 300);
      setup.longPathCharacters = long.length;
      cases.push(['long', long]);
    } catch (error) {
      unsupported.push({ caseName: 'long', ...failure(error) });
    }
    for (const mode of ['r', 'r+', 'a']) {
      const file = write('held-' + mode.replace('+', '-plus') + '.txt');
      held.push(fs.openSync(file, mode));
      cases.push(['held-handle-' + mode, file]);
    }
    for (const dangling of [false, true]) {
      const name = dangling ? 'dangling-junction' : 'junction';
      try {
        const destination = dangling
          ? join(root, 'absent-target')
          : dirname(write('target/leaf.txt'));
        fs.symlinkSync(destination, join(root, name), 'junction');
        fact(fs.lstatSync(join(root, name)).isSymbolicLink());
        cases.push([name, join(root, name, 'leaf.txt')]);
      } catch (error) {
        unsupported.push({ caseName: name, ...failure(error) });
      }
    }
    try {
      const file = write('changing/leaf.txt'),
        before = fs.lstatSync(file, { bigint: true });
      setup.beforeRetarget = {
        ordinary: invoke(fs.realpathSync, file).outcome,
        rawNative: invoke(fs.realpathSync.native, file).outcome,
        fastPositive: invoke(fastPositive(fs.realpathSync, fs.realpathSync.native), file).outcome,
      };
      fs.renameSync(join(root, 'changing'), join(root, 'retained'));
      fs.symlinkSync(join(root, 'retained'), join(root, 'changing'), 'junction');
      const after = fs.lstatSync(file, { bigint: true });
      fact(
        fs.lstatSync(join(root, 'changing')).isSymbolicLink() &&
          before.dev === after.dev &&
          before.ino === after.ino,
      );
      setup.retargetInjectedWithSameLeafIdentity = true;
      cases.push(
        ['ancestor-retarget', file],
        ['retained-control', join(root, 'retained', 'leaf.txt')],
      );
    } catch (error) {
      if (error.code === 'FIXTURE_FACT_FAILED') throw error;
      unsupported.push({ caseName: 'ancestor-retarget', ...failure(error) });
    }
    return { cases, setup, unsupported, close };
  } catch (error) {
    close();
    throw error;
  }
}

function contained(root, path) {
  const sub = relative(root, path);
  return sub !== '' && sub !== '..' && !sub.startsWith('..' + sep) && !isAbsolute(sub);
}

// Resolves the Host's actual ESM import condition, NOT source packages/dist.
// Caller must supply --experimental-import-meta-resolve (no resolver child).
export async function inspectProgram(program) {
  if (fs.realpathSync(program) !== program || !fs.lstatSync(program).isDirectory())
    throw fail('CANONICAL_PROGRAM_DIRECTORY_REQUIRED');
  const host = join(program, 'apps/host-agent/dist/runtime-server.js');
  if (!fs.lstatSync(host).isFile() || fs.lstatSync(host).isSymbolicLink())
    throw fail('HOST_ENTRY_REQUIRED');
  const url = new URL(import.meta.resolve('@codex-manage/codex-adapter', pathToFileURL(host).href));
  if (url.protocol !== 'file:' || !url.pathname.endsWith('/dist/index.js'))
    throw fail('UNEXPECTED_ACTUAL_ADAPTER');
  const entry = fs.realpathSync(fileURLToPath(url)),
    dist = dirname(entry),
    adapter = dirname(dist);
  if (
    !contained(program, entry) ||
    JSON.parse(fs.readFileSync(join(adapter, 'package.json'), 'utf8')).name !==
      '@codex-manage/codex-adapter'
  )
    throw fail('ACTUAL_ADAPTER_OUTSIDE_PROGRAM');
  const modules = {};
  for (const name of fs
    .readdirSync(dist)
    .filter((name) => name.endsWith('.js'))
    .sort()) {
    const path = join(dist, name),
      stat = fs.lstatSync(path);
    if (!stat.isFile() || stat.isSymbolicLink()) throw fail('UNSAFE_ADAPTER_MODULE');
    modules[name] = sha256(fs.readFileSync(path));
  }
  for (const name of [
    'desktop-platform.js',
    'windows-process.js',
    'desktop-private-state-native.js',
    'desktop-private-state-native-worker.js',
  ])
    if (!modules[name]) throw fail('PRIVATE_STATE_TRANSPORT_REQUIRED');
  const helperSHA256 = {};
  for (const name of ['private-state.cs', 'private-state.ps1'])
    helperSHA256[name] = sha256(fs.readFileSync(join(adapter, 'windows', name)));
  return {
    adapterURL: pathToFileURL(entry),
    provenance: {
      hostRuntimeServerSHA256: sha256(fs.readFileSync(host)),
      adapterFingerprint: sha256(JSON.stringify(modules)),
      moduleSHA256: modules,
      helperSHA256,
      adapterResolvedFromHostImport: true,
    },
  };
}

export async function runBenchmark({ program, operation }, platform, provenance) {
  let root,
    protectedRoot = false,
    fixtures,
    previousCwd;
  const report = {
    schemaVersion: 1,
    kind: 'synthetic-windows-realpath-benchmark',
    operation,
    complete: false,
    platform: process.platform,
    architecture: process.arch,
    node: process.version,
    libuv: process.versions.uv,
    nodeExecutableSHA256: sha256(fs.readFileSync(process.execPath)),
    scriptSHA256: sha256(fs.readFileSync(fileURLToPath(import.meta.url))),
    provenance,
    limitations: [
      'Synthetic resolver evidence only; not DACL/TraceSnapshot/HTTP/ordinary DEV acceptance.',
      'No equivalence proof or production candidate approval; no OS cache flush.',
      '30s matrix deadline checked between synchronous calls; not an OS-call interrupt.',
      'Actual program private-state helper uses its normal managed bootstrap/cache and gate.',
      'Own fixtures/report retained; no existing operation reused, removed or reprotected.',
    ],
  };
  try {
    root = createOperationRoot(program, operation);
    platform.protectWindowsStateDirectory(root);
    protectedRoot = true;
    const fixtureRoot = join(root, 'fixtures');
    fs.mkdirSync(fixtureRoot, { mode: 0o700 });
    fixtures = syntheticCases(fixtureRoot);
    previousCwd = process.cwd();
    process.chdir(fixtureRoot); // Invalid/relative coercions stay in our own fixture.
    report.setup = fixtures.setup;
    report.unsupported = fixtures.unsupported;
    report.matrix = runMatrix(fixtures.cases, fs.realpathSync, fs.realpathSync.native);
    report.complete = report.matrix.complete;
  } catch (error) {
    report.failure = failure(error);
  } finally {
    try {
      fixtures?.close();
      if (previousCwd !== undefined) process.chdir(previousCwd);
      if (protectedRoot) {
        const file = join(root, 'report.json');
        const bytes = JSON.stringify(report) + '\n';
        fs.writeFileSync(file, bytes, { flag: 'wx', mode: 0o600 });
        platform.protectWindowsStateFile(file);
        platform.assertPrivateFile(file);
        report.savedReportSHA256 = sha256(bytes);
      }
    } finally {
      platform.closeWindowsPrivateStateBackend();
    }
  }
  return report;
}

async function main() {
  const args = parseArguments(process.argv.slice(2));
  if (
    process.platform !== 'win32' ||
    process.version !== 'v24.21.0' ||
    process.env.NODE_OPTIONS?.trim() ||
    process.execArgv.length !== 1 ||
    process.execArgv[0] !== '--experimental-import-meta-resolve'
  )
    throw fail('FIXED_WINDOWS_NODE_24_21_NO_PRELOAD_REQUIRED');
  const inspected = await inspectProgram(args.program);
  const gate = await import(new URL('./windows-process.js', inspected.adapterURL).href);
  const shared = gate.getWindowsProcessGate();
  if (
    !(shared instanceof SharedArrayBuffer) ||
    shared.byteLength !== 4 ||
    typeof gate.withWindowsProcessCreation !== 'function'
  )
    throw fail('ACTUAL_PROCESS_GATE_REQUIRED');
  const platform = await import(new URL('./desktop-platform.js', inspected.adapterURL).href);
  for (const name of [
    'protectWindowsStateDirectory',
    'protectWindowsStateFile',
    'assertPrivateFile',
    'closeWindowsPrivateStateBackend',
  ])
    if (typeof platform[name] !== 'function') throw fail('ACTUAL_PRIVATE_STATE_REQUIRED');
  // Protection already gates its fixed native Worker internally. Never nest a
  // second synchronous gate around it, or start any Host/Trace reader/API.
  const report = await runBenchmark(args, platform, inspected.provenance);
  console.log(
    JSON.stringify({
      operation: args.operation,
      complete: report.complete,
      candidateRejected: report.matrix?.candidateRejected ?? null,
      reportSHA256: report.savedReportSHA256 ?? null,
      privateStateCloseRequested: true,
    }),
  );
  if (!report.complete) process.exitCode = 1;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    await main();
  } catch (error) {
    console.error(JSON.stringify({ complete: false, failure: failure(error) }));
    process.exitCode = 1;
  }
}
