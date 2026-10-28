import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import { mkdtemp, rm } from 'node:fs/promises';
import { syncBuiltinESMExports } from 'node:module';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { inspectDesktopArchive } from '../packages/codex-adapter/dist/desktop-platform.js';

const profile =
  'win32 codex-ipc ipc.sock {"thread-stream-state-changed":11,"thread-follower-start-turn":2,"thread-follower-interrupt-turn":4}';
const hash = (bytes) => createHash('sha256').update(bytes).digest('hex');
function archive(mutate = () => {}) {
  const pkg = Buffer.from('{"version":"26.1002.52244"}');
  const body = Buffer.from(profile);
  const tree = {
    files: {
      'package.json': { offset: '0', size: pkg.length },
      '.vite': {
        files: {
          build: { files: { 'profile.js': { offset: String(pkg.length), size: body.length } } },
        },
      },
    },
  };
  mutate(tree);
  const json = Buffer.from(JSON.stringify(tree));
  const size = 8 + Math.ceil(json.length / 4) * 4;
  const header = Buffer.alloc(8 + size);
  header.writeUInt32LE(4, 0);
  header.writeUInt32LE(size, 4);
  header.writeUInt32LE(size - 4, 8);
  header.writeUInt32LE(json.length, 12);
  json.copy(header, 16);
  return Buffer.concat([header, pkg, body]);
}
async function fixture(run) {
  const dir = await mkdtemp(join(tmpdir(), 'archive-bounds-'));
  try {
    await run(join(dir, 'fixture.asar'));
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}
function patchedReads(path, onRead) {
  const original = fs.readSync;
  const open = fs.openSync;
  let selected;
  fs.openSync = function (name, ...args) {
    const fd = Reflect.apply(open, this, [name, ...args]);
    if (name === fs.realpathSync.native(path)) selected = fd;
    return fd;
  };
  fs.readSync = function (fd, buffer, offset, length, position) {
    return onRead({
      original,
      self: this,
      selected: fd === selected,
      fd,
      buffer,
      offset,
      length,
      position,
    });
  };
  syncBuiltinESMExports();
  return () => {
    fs.openSync = open;
    fs.readSync = original;
    syncBuiltinESMExports();
  };
}

test('640129388-byte archive keeps full-file SHA without whole-archive reads or allocation', () =>
  fixture(async (path) => {
    const prefix = archive();
    const length = 640129388;
    fs.writeFileSync(path, prefix);
    fs.truncateSync(path, length);
    const expected = createHash('sha256').update(prefix);
    const zero = Buffer.alloc(64 * 1024);
    for (let left = length - prefix.length; left > 0;) {
      const count = Math.min(zero.length, left);
      expected.update(zero.subarray(0, count));
      left -= count;
    }
    const child = `import assert from 'node:assert/strict';import fs from 'node:fs';import {syncBuiltinESMExports} from 'node:module';import {inspectDesktopArchive} from ${JSON.stringify(new URL('../packages/codex-adapter/dist/desktop-platform.js', import.meta.url).href)};
const path=process.argv[1],expected=process.argv[2],read=fs.readFileSync,chunk=fs.readSync;let max=0;
fs.readFileSync=function(p,...a){assert.notEqual(p,path,'Do not materialize the installed archive');return Reflect.apply(read,this,[p,...a]);};
fs.readSync=function(fd,b,...a){max=Math.max(max,b.length);assert(b.length<=32*1024*1024,'Bound every input buffer');return Reflect.apply(chunk,this,[fd,b,...a]);};syncBuiltinESMExports();
const before=process.resourceUsage().maxRSS,result=inspectDesktopArchive(path);assert.equal(result.version,'26.1002.52244');assert.equal(result.asarSha256,expected);assert(process.resourceUsage().maxRSS-before<96*1024,'Bound additional peak RSS');console.log(JSON.stringify({fullSHA:true,maxBufferBytes:max,peakDeltaKiB:process.resourceUsage().maxRSS-before}));`;
    const r = spawnSync(
      process.execPath,
      ['--max-old-space-size=96', '--input-type=module', '-e', child, path, expected.digest('hex')],
      { encoding: 'utf8', timeout: 30000, windowsHide: true },
    );
    assert.equal(r.status, 0, r.stderr);
    assert.equal(JSON.parse(r.stdout).fullSHA, true);
  }));

test('partial OS reads retain the exact complete archive digest', () =>
  fixture(async (path) => {
    const bytes = archive();
    fs.writeFileSync(path, bytes);
    const restore = patchedReads(path, (v) =>
      Reflect.apply(v.original, v.self, [
        v.fd,
        v.buffer,
        v.offset,
        v.selected ? Math.min(v.length, 7) : v.length,
        v.position,
      ]),
    );
    try {
      assert.equal(inspectDesktopArchive(path).asarSha256, hash(bytes));
    } finally {
      restore();
    }
  }));

for (const kind of ['namespace-replacement', 'same-size-content-change'])
  test('archive rejects ' + kind + ' during inspection', () =>
    fixture(async (path) => {
      const bytes = Buffer.concat([archive(), Buffer.alloc(1024)]);
      fs.writeFileSync(path, bytes);
      // A fresh write and the injected mutation can share a filesystem clock
      // tick on Linux. Start with an older observable mtime so this exercises
      // the identity fence deterministically without waiting or mocking stats.
      const originalTime = new Date(Date.now() - 10_000);
      fs.utimesSync(path, originalTime, originalTime);
      const before = fs.statSync(path, { bigint: true });
      let changed = false;
      const restore = patchedReads(path, (v) => {
        const count = Reflect.apply(v.original, v.self, [
          v.fd,
          v.buffer,
          v.offset,
          v.length,
          v.position,
        ]);
        if (v.selected && !changed) {
          changed = true;
          if (kind === 'namespace-replacement') {
            fs.renameSync(path, path + '.old');
            fs.writeFileSync(path, bytes);
          } else {
            const fd = fs.openSync(path, 'r+');
            try {
              fs.writeSync(fd, Buffer.from([1]), 0, 1, bytes.length - 1);
            } finally {
              fs.closeSync(fd);
            }
            const after = fs.statSync(path, { bigint: true });
            assert.equal(after.size, before.size);
            assert.notEqual(after.mtimeNs, before.mtimeNs);
          }
        }
        return count;
      });
      try {
        assert.throws(() => inspectDesktopArchive(path), /archive changed/);
        assert(changed);
      } finally {
        restore();
      }
    }),
  );

test('finding the profile cannot skip rejection of a later selected entry', () =>
  fixture(async (path) => {
    fs.writeFileSync(
      path,
      archive((tree) => {
        tree.files['.vite'].files.build.files['late.js'] = {
          size: 0,
          offset: '0',
          link: 'outside',
        };
      }),
    );
    assert.throws(() => inspectDesktopArchive(path), /Unsupported archive entry/);
  }));

test('header and package size bounds reject before a large buffer read', () =>
  fixture(async (path) => {
    const prefix = archive();
    prefix.writeUInt32LE(32 * 1024 * 1024 + 1, 4);
    fs.writeFileSync(path, prefix);
    fs.truncateSync(path, 40 * 1024 * 1024);
    assert.throws(() => inspectDesktopArchive(path), /archive header/);
    fs.writeFileSync(
      path,
      archive((tree) => {
        tree.files['package.json'].size = 8 * 1024 * 1024 + 1;
      }),
    );
    fs.truncateSync(path, 10 * 1024 * 1024);
    assert.throws(() => inspectDesktopArchive(path), /archive entry size/);
  }));
