import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';
import { join, relative, win32 } from 'node:path';
import { tmpdir } from 'node:os';
import { fileURLToPath, pathToFileURL } from 'node:url';
import ts from 'typescript';

const source = fileURLToPath(
  new URL('../../packages/codex-adapter/src/desktop-prediction-trace.ts', import.meta.url),
);
const hash = (value) => createHash('sha256').update(value).digest('hex');

// Real Node filesystem in an isolated Windows namespace. Native chain/DACL
// replies are synthetic; this is NOT real C#/NTFS acceptance on any platform.
export async function preparedTraceFixture(run) {
  const directory = fs.realpathSync(fs.mkdtempSync(join(tmpdir(), 'prepared-trace-')));
  const drive = join(directory, 'drive');
  const root = 'C:\\fixture\\traces';
  const local = (path) => join(drive, ...path.slice(3).split('\\'));
  const remote = (path) => 'C:\\' + relative(drive, path).split(/[\\/]/).join('\\');
  fs.mkdirSync(local(root), { recursive: true, mode: 0o700 });
  const write = (name, text) => fs.writeFileSync(join(directory, name), text, { mode: 0o600 });
  const input = fs.readFileSync(source, 'utf8'),
    sourceSHA = hash(input);
  let compiled = ts.transpileModule(input, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
  }).outputText;
  compiled = compiled
    .replace(/(['"])node:fs\1/g, "'./fs.mjs'")
    .replace(/(['"])node:path\1/g, "'./path.mjs'")
    .replace(
      /(['"])\.\/desktop-trace-native\.js\1/g,
      JSON.stringify(
        new URL('../../packages/codex-adapter/dist/desktop-trace-native.js', import.meta.url).href,
      ),
    );
  write('package.json', '{"type":"module"}');
  write('reader.mjs', compiled + '\nexport {TraceSnapshot};\n');
  write(
    'desktop-suggestion-store.js',
    ts.transpileModule(
      fs.readFileSync(
        new URL('../../packages/codex-adapter/src/desktop-suggestion-store.ts', import.meta.url),
        'utf8',
      ),
      { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } },
    ).outputText,
  );
  write(
    'windows-trace-diagnostics.js',
    ts.transpileModule(
      fs.readFileSync(
        new URL('../../packages/codex-adapter/src/windows-trace-diagnostics.ts', import.meta.url),
        'utf8',
      ),
      { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } },
    ).outputText,
  );
  write(
    'path.mjs',
    "import {win32} from 'node:path';export const {dirname,isAbsolute,join,parse,relative,resolve}=win32;\n",
  );
  write(
    'fs.mjs',
    `import fs from 'node:fs';import {join,relative} from 'node:path';
const drive=${JSON.stringify(drive)};
const local=p=>join(drive,...p.slice(3).split('\\\\'));
const remote=p=>'C:\\\\'+relative(drive,p).split(/[\\\\/]/).join('\\\\');
let hooks={};export const install=value=>hooks=value;
export const constants=fs.constants,closeSync=fs.closeSync,fstatSync=fs.fstatSync,readSync=fs.readSync;
export const lstatSync=(p,...args)=>{const value=fs.lstatSync(local(p),...args);return hooks.stat?.(p,value)??value};
export const realpathSync=p=>{hooks.canonical?.(p);return remote(fs.realpathSync(local(p)))};
export const openSync=(p,...args)=>fs.openSync(local(p),...args);
export const opendirSync=p=>{hooks.catalog?.(p);return fs.opendirSync(local(p))};
`,
  );
  write(
    'desktop-platform.js',
    `let backend;
export const install=value=>backend=value;
export const assertPrivateFile=()=>{throw Error('fixture has no persistent-store backend')};
export const makePrivateDirectorySync=()=>{throw Error('fixture has no persistent-store backend')};
export const openPrivateFileSync=()=>{throw Error('fixture has no persistent-store backend')};
export const renamePrivateFileSync=()=>{throw Error('fixture has no persistent-store backend')};
export const windowsTraceSnapshot=(...args)=>backend.snapshot(...args);
export const windowsTraceSnapshotMany=(...args)=>backend.snapshotMany(...args);
export const windowsTraceSnapshotBatchAvailable=()=>backend.batch;
export const protectWindowsStateDirectory=()=>{};export const protectWindowsStateFile=()=>{};
`,
  );
  const fixtureSource = fs.readFileSync(new URL('./desktop-trace.mjs', import.meta.url), 'utf8');
  write(
    'data.mjs',
    fixtureSource.replace(
      '../../packages/codex-adapter/dist/desktop-platform.js',
      './desktop-platform.js',
    ),
  );
  const data = await import(pathToFileURL(join(directory, 'data.mjs')).href);
  const platform = await import(pathToFileURL(join(directory, 'desktop-platform.js')).href);
  const filesystem = await import(pathToFileURL(join(directory, 'fs.mjs')).href);
  const module = await import(pathToFileURL(join(directory, 'reader.mjs')).href);
  const calls = [],
    canonical = [],
    catalogs = [];
  let change,
    batchReply,
    stat,
    batch = true;
  let aclEpoch = 0;
  const snapshot = (path, maximum) => {
    const paths = [];
    for (let at = path; ; at = win32.dirname(at)) {
      paths.unshift(at);
      if (at === win32.parse(at).root) break;
    }
    const chain = paths.map((part) => {
      const info = fs.lstatSync(local(part), { bigint: true });
      assert(!info.isSymbolicLink());
      return `1:${info.ino >> 32n}:${info.ino & 0xffffffffn}:0:0:${hash(String(info.mode) + ':' + aclEpoch)}`;
    });
    return {
      identity: createHash('sha256').update(chain.join('|')).digest('base64'),
      chain,
      bytes: maximum ? fs.readFileSync(local(path)).subarray(0, maximum) : Buffer.alloc(0),
    };
  };
  platform.install({
    get batch() {
      return batch;
    },
    snapshot(_root, path, maximum = 0) {
      calls.push({ kind: 'S', path, maximum });
      change?.('before', path, maximum);
      const result = snapshot(path, maximum);
      change?.('after', path, maximum);
      return result;
    },
    snapshotMany(_root, items) {
      calls.push({ kind: 'B', items });
      const values = items.map(({ path, maximum }) => {
        change?.('before', path, maximum);
        const result = snapshot(path, maximum);
        change?.('after', path, maximum);
        return result;
      });
      return batchReply ? batchReply(values) : values;
    },
  });
  filesystem.install({
    stat: (path, value) => stat?.(path, value) ?? value,
    canonical: (path) => canonical.push(path),
    catalog: (path) => catalogs.push(path),
  });
  const descriptor = Object.getOwnPropertyDescriptor(process, 'platform');
  const create = (ids) => {
    const f = data.recoverableFixture(local(root), ids);
    f.configured.value.cwd = root;
    data.writeJson(f.configured.path, f.configured.value);
    return f;
  };
  Object.defineProperty(process, 'platform', { value: 'win32' });
  try {
    await run({
      ...module,
      data,
      root,
      local,
      remote,
      create,
      reader: new module.DesktopPredictionTraceReader(root),
      calls,
      canonical,
      catalogs,
      prepare(reader, f, latestOnly = true) {
        return reader.prepareSourcePredictions(f.sourceThreadId, f.sourceTurnId, 0, latestOnly);
      },
      reset() {
        calls.length = 0;
        canonical.length = 0;
        catalogs.length = 0;
      },
      change(value) {
        change = value;
      },
      stat(value) {
        stat = value;
      },
      batch(value) {
        batch = value;
      },
      batchReply(value) {
        batchReply = value;
      },
      changeAcl() {
        aclEpoch++;
      },
    });
  } finally {
    Object.defineProperty(process, 'platform', descriptor);
    assert.equal(hash(fs.readFileSync(source)), sourceSHA, 'Parser source changed during fixture');
    fs.rmSync(directory, { recursive: true, force: true });
  }
}
