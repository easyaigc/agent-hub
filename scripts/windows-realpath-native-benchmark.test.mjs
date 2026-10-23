import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import test from 'node:test';
import { fileURLToPath, pathToFileURL } from 'node:url';
import {
  createOperationRoot,
  fastPositive,
  parseArguments,
  runBenchmark,
  runMatrix,
  syntheticCases,
} from './diagnostics/windows-realpath-native-benchmark.mjs';

function fixture(run) {
  const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'realpath-offline-')));
  try {
    return run(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
}

test('CLI accepts only an absolute program and v4 operation, never an output/action override', () => {
  const program = process.cwd(),
    operation = randomUUID();
  assert.deepEqual(parseArguments(['--program', program, '--operation', operation]), {
    program,
    operation,
  });
  for (const args of [
    [],
    ['--program', '.', '--operation', operation],
    ['--program', program, '--operation', '../existing'],
    ['--program', program, '--operation', operation, '--output', program],
    ['--program', program, '--operation', '00000000-0000-0000-0000-000000000000'],
  ])
    assert.throws(() => parseArguments(args), { code: 'EXPECTED_PROGRAM_AND_NEW_V4_OPERATION' });
});

test('fast-positive is fresh and preserves fallback receiver, args, values and original thrown object', () => {
  const receiver = {},
    options = { encoding: 'buffer' },
    counts = {};
  const ordinaryError = new Error('offline sensitive fixture path must not escape');
  let nativeCalls = 0,
    ordinaryCalls = 0,
    nativeValue = 'fixture',
    ordinaryThrows = false;
  const ordinary = function (...args) {
    assert.equal(this, receiver);
    assert.deepEqual(args, ['fixture', options]);
    ordinaryCalls++;
    if (ordinaryThrows) throw ordinaryError;
    return Buffer.from('ordinary output');
  };
  const candidate = fastPositive(
    ordinary,
    function (...args) {
      assert.equal(this, receiver);
      assert.deepEqual(args, ['fixture', options]);
      nativeCalls++;
      if (nativeValue instanceof Error) throw nativeValue;
      return nativeValue;
    },
    counts,
  );
  assert.equal(candidate.call(receiver, 'fixture', options), 'fixture');
  assert.equal(ordinaryCalls, 0);
  for (const value of ['FIXTURE', null, Buffer.from('fixture'), new Error('native error')]) {
    nativeValue = value;
    assert.deepEqual(candidate.call(receiver, 'fixture', options), Buffer.from('ordinary output'));
  }
  ordinaryThrows = true;
  assert.throws(
    () => candidate.call(receiver, 'fixture', options),
    (error) => error === ordinaryError,
  );
  nativeValue = 'fixture';
  assert.equal(candidate.call(receiver, 'fixture', options), 'fixture');
  assert.equal(nativeCalls, 7);
  assert.equal(ordinaryCalls, 5);
  assert.deepEqual(counts, {
    nativeAttempts: 7,
    nativeExactHits: 2,
    nativeNonExact: 3,
    ordinaryFallbacks: 5,
    nativeErrors: 2,
  });
  for (const native of [undefined, null, 'not-a-function']) {
    assert.throws(
      () => fastPositive(ordinary, native).call(receiver, 'fixture', options),
      (error) => error === ordinaryError,
    );
  }
});

test('eight balanced rounds retain all returns/errors and never publish raw paths/error messages', () => {
  const secret = 'private-fixture-value';
  const ordinary = (input) => {
    if (input === null)
      throw Object.assign(new TypeError(secret), { code: 'ERR_INVALID_ARG_TYPE' });
    return input;
  };
  const report = runMatrix(
    [
      ['normal', secret],
      ['error', null],
    ],
    ordinary,
    ordinary,
  );
  assert.equal(report.complete, true);
  assert.equal(report.rounds, 8);
  assert.equal(report.callsPerResolverPerCasePerRound, 12);
  assert.equal(report.rows.length, 8 * 2 * 3 * 3);
  assert.equal(
    report.comparison.every((row) => row.sameOutputAndError && row.sameExactDecision),
    true,
  );
  for (let round = 1; round <= 8; round++) {
    for (const caseName of ['normal', 'error']) {
      for (const resolver of ['ordinary', 'rawNative', 'fastPositive']) {
        const rows = report.rows.filter(
          (row) => row.round === round && row.caseName === caseName && row.resolver === resolver,
        );
        assert.deepEqual(rows.map((row) => row.position).sort(), [0, 1, 2]);
        assert.equal(rows.flatMap((row) => row.durationsNs).length, 12);
        assert.equal(
          rows
            .flatMap((row) => row.outcomes)
            .every((value) => value.returned === (caseName === 'normal')),
          true,
        );
      }
    }
  }
  assert.equal(JSON.stringify(report).includes(secret), false);
  assert.equal(report.candidateApprovedForProduction, false);
  const expired = runMatrix([['normal', secret]], ordinary, ordinary, 0);
  assert.equal(expired.complete, false);
  assert.equal(expired.failure.errorCode, 'BENCHMARK_BUDGET_EXHAUSTED');
  assert.equal(expired.rows.length, 0);
});

test('a concrete accepting counterexample excludes candidate from the full eight-round API comparison', () => {
  let nativeCalls = 0;
  const report = runMatrix(
    [['counterexample', 'exact']],
    () => 'different',
    (path) => {
      nativeCalls++;
      return path;
    },
  );
  assert.equal(report.candidateRejected, true);
  assert.equal(report.preflight[0].candidateAcceptsOrdinaryRejects, true);
  assert.equal(report.complete, true);
  assert.deepEqual(report.resolvers, ['ordinary', 'rawNative']);
  assert.equal(report.rows.length, 8 * 2 * 2);
  assert.equal(
    report.rows.some((row) => row.resolver === 'fastPositive'),
    false,
  );
  assert.equal(nativeCalls, 2 + 8 * 2 * 4);
  const missing = runMatrix([['normal', 'exact']], (path) => path, undefined);
  assert.equal(missing.complete, true);
  assert.equal(
    missing.rows
      .filter((row) => row.resolver === 'rawNative')
      .every((row) => row.outcomes.every((outcome) => outcome.errorCode === 'NATIVE_MISSING')),
    true,
  );
  assert.ok(missing.candidateCounts.nativeMissing > 0);
});

test('new operation is exclusive and refuses an alias parent without reusing or protecting existing data', () =>
  fixture((root) => {
    const operation = randomUUID();
    const created = createOperationRoot(root, operation);
    fs.writeFileSync(join(created, 'retained.txt'), 'retained');
    assert.throws(() => createOperationRoot(root, operation), { code: 'EEXIST' });
    assert.equal(fs.readFileSync(join(created, 'retained.txt'), 'utf8'), 'retained');
    const target = join(root, 'own-target');
    fs.mkdirSync(target);
    fs.renameSync(join(root, '.local/windows-realpath-benchmark'), join(root, '.local/retained'));
    fs.symlinkSync(target, join(root, '.local/windows-realpath-benchmark'), 'junction');
    assert.throws(() => createOperationRoot(root, randomUUID()), {
      code: 'UNSAFE_OPERATION_PARENT',
    });
    assert.deepEqual(fs.readdirSync(target), []);
  }));

test('real local fs exercises alias, retained-leaf retarget, long path, handles and errors (not Windows proof)', () =>
  fixture((root) => {
    const fixtures = syntheticCases(root);
    try {
      assert.equal(fixtures.setup.longPathCharacters > 300, true);
      assert.equal(fixtures.setup.retargetInjectedWithSameLeafIdentity, true);
      for (const name of [
        'junction',
        'ancestor-retarget',
        'long',
        'held-handle-r',
        'held-handle-r+',
        'held-handle-a',
      ])
        assert.ok(
          fixtures.cases.some(([caseName]) => caseName === name),
          name,
        );
      const report = runMatrix(fixtures.cases, fs.realpathSync, fs.realpathSync.native);
      assert.equal(report.complete, true);
      for (const name of ['junction', 'ancestor-retarget'])
        assert.equal(
          report.preflight.find((row) => row.caseName === name).values.ordinary.exact,
          false,
        );
      for (const name of ['null-path', 'undefined-path', 'nul-path', 'invalid-encoding'])
        assert.equal(
          report.preflight.find((row) => row.caseName === name).values.ordinary.returned,
          false,
        );
      assert.equal(JSON.stringify(report).includes(root), false);
      assert.equal(JSON.stringify(report).includes('synthetic\n'), false);
    } finally {
      fixtures.close();
    }
  }));

test('run lifecycle protects only new root/report and closes backend on setup failure and duplicate operation', async () => {
  const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'realpath-lifecycle-')));
  const calls = [],
    operation = randomUUID();
  const platform = {
    protectWindowsStateDirectory(path) {
      calls.push(['directory', path]);
      throw Object.assign(new Error('no path in output'), { code: 'OFFLINE_PROTECT_REJECTED' });
    },
    protectWindowsStateFile(path) {
      calls.push(['file', path]);
    },
    assertPrivateFile(path) {
      calls.push(['check', path]);
    },
    closeWindowsPrivateStateBackend() {
      calls.push(['close']);
    },
  };
  try {
    const report = await runBenchmark({ program: root, operation }, platform, {});
    assert.equal(report.complete, false);
    assert.equal(report.failure.errorCode, 'OFFLINE_PROTECT_REJECTED');
    const expectedRoot = join(root, '.local/windows-realpath-benchmark', operation);
    assert.deepEqual(calls, [['directory', expectedRoot], ['close']]);
    assert.deepEqual(fs.readdirSync(expectedRoot), []);
    calls.length = 0;
    const duplicate = await runBenchmark({ program: root, operation }, platform, {});
    assert.equal(duplicate.failure.errorCode, 'EEXIST');
    assert.deepEqual(calls, [['close']]);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('successful standalone lifecycle saves one private report, restores cwd, closes backend and preserves prior data', async () => {
  const root = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'realpath-report-')));
  const operation = randomUUID(),
    calls = [],
    previousCwd = process.cwd();
  const platform = {
    protectWindowsStateDirectory(path) {
      calls.push(['directory', path]);
    },
    protectWindowsStateFile(path) {
      calls.push(['file', path]);
    },
    assertPrivateFile(path) {
      calls.push(['check', path]);
      assert.ok(fs.lstatSync(path).isFile());
    },
    closeWindowsPrivateStateBackend() {
      calls.push(['close']);
    },
  };
  try {
    const report = await runBenchmark({ program: root, operation }, platform, { offline: true });
    assert.equal(report.complete, true);
    assert.equal(process.cwd(), previousCwd);
    const expectedRoot = join(root, '.local/windows-realpath-benchmark', operation),
      file = join(expectedRoot, 'report.json');
    assert.deepEqual(calls, [
      ['directory', expectedRoot],
      ['file', file],
      ['check', file],
      ['close'],
    ]);
    const bytes = fs.readFileSync(file);
    assert.equal(report.savedReportSHA256, createHash('sha256').update(bytes).digest('hex'));
    assert.equal(bytes.includes(Buffer.from(root)), false);
    assert.equal(bytes.includes(Buffer.from('synthetic\\n')), false);
    if (process.platform !== 'win32') {
      assert.equal(fs.statSync(expectedRoot).mode & 0o777, 0o700);
      assert.equal(fs.statSync(file).mode & 0o777, 0o600);
    }
    calls.length = 0;
    const duplicate = await runBenchmark({ program: root, operation }, platform, {});
    assert.equal(duplicate.complete, false);
    assert.equal(duplicate.savedReportSHA256, undefined);
    assert.deepEqual(calls, [['close']]);
    assert.deepEqual(fs.readFileSync(file), bytes);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('actual Host import-condition resolution selects nested adapter; never executes Host/index or source fallback', () =>
  fixture((root) => {
    const tool = pathToFileURL(
      join(
        dirname(fileURLToPath(import.meta.url)),
        'diagnostics/windows-realpath-native-benchmark.mjs',
      ),
    ).href;
    const write = (name, text) => {
      const path = join(root, name);
      fs.mkdirSync(dirname(path), { recursive: true });
      fs.writeFileSync(path, text);
    };
    write('package.json', '{"type":"module"}');
    write('apps/host-agent/dist/runtime-server.js', 'throw Error("must not run Host");');
    const adapter = 'apps/host-agent/node_modules/@codex-manage/codex-adapter';
    write(
      adapter + '/package.json',
      JSON.stringify({
        name: '@codex-manage/codex-adapter',
        type: 'module',
        exports: { import: './dist/index.js', require: './wrong.cjs' },
      }),
    );
    for (const name of [
      'index',
      'desktop-platform',
      'windows-process',
      'desktop-private-state-native',
      'desktop-private-state-native-worker',
    ])
      write(
        adapter + '/dist/' + name + '.js',
        'throw Error("must not import runtime while resolving");',
      );
    for (const name of ['private-state.cs', 'private-state.ps1'])
      write(adapter + '/windows/' + name, '// synthetic provenance');
    // A misleading source-tree adapter must not be selected by convenience paths.
    write('packages/codex-adapter/dist/index.js', 'throw Error("wrong source copy");');
    const script = `import {inspectProgram} from ${JSON.stringify(tool)}; const result=await inspectProgram(process.argv[1]); console.log(JSON.stringify({entry:result.adapterURL.href,provenance:result.provenance}));`;
    const env = { ...process.env };
    delete env.NODE_OPTIONS;
    const result = JSON.parse(
      execFileSync(
        process.execPath,
        ['--experimental-import-meta-resolve', '--input-type=module', '--eval', script, root],
        { env, encoding: 'utf8', timeout: 5000 },
      ),
    );
    assert.equal(result.entry, pathToFileURL(join(root, adapter, 'dist/index.js')).href);
    assert.equal(result.provenance.adapterResolvedFromHostImport, true);
    assert.equal(Object.keys(result.provenance.moduleSHA256).length, 5);
    assert.match(result.provenance.adapterFingerprint, /^[a-f0-9]{64}$/);
    assert.equal(JSON.stringify(result.provenance).includes(root), false);
  }));
