import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const repository = fileURLToPath(new URL('../', import.meta.url));
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const replay = process.argv.includes('--synthetic-replay');

// CLI builds retain the actual Main. Observation builds wrap each full Snapshot
// and count the real native call sites; they never replace a syscall or guard.
const observation = String.raw`
using System;using System.IO;using System.Text;using System.Reflection;
using System.Diagnostics;using System.Collections.Generic;using System.Security.Cryptography;
using System.Threading;using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;using System.Web.Script.Serialization;
class BufferFixture {
 public class ItemRecord {public int index,calls,opens,infos,security,reads,capacity;}
 static readonly object gate=new object();static HashSet<char[]> buffers=new HashSet<char[]>();static SortedDictionary<int,ItemRecord> records=new SortedDictionary<int,ItemRecord>();static SortedDictionary<int,int> lengths=new SortedDictionary<int,int>();static int calls,active,highWater,anchorOpens;static string changedAncestor,ancestorSDDL;
 static List<int> started=new List<int>(),settled=new List<int>();[ThreadStatic]static ItemRecord current;
 static ManualResetEventSlim allStarted=new ManualResetEventSlim(),otherFinished=new ManualResetEventSlim(),failed=new ManualResetEventSlim(),slowWaiting=new ManualResetEventSlim(),releaseSlow=new ManualResetEventSlim();static string mode;static int failureIndex,expectedActive,slowCount;
 public static void Record(char[] actual){if(current==null)return;lock(gate){calls++;current.calls++;if(buffers.Add(actual)){lengths.Add(current.index,actual.Length);current.capacity=actual.Length;}}}
 public static void AnchorOpen(){anchorOpens++;}
 public static void Open(){current.opens++;}public static void Info(){if(current!=null)current.infos++;}public static void Security(){if(current!=null)current.security++;}public static void Read(){current.reads++;}
 public static void AfterAnchors(string root){if(mode!="ancestor-acl")return;
  changedAncestor=Directory.GetParent(root).FullName;var before=Directory.GetAccessControl(changedAncestor);ancestorSDDL=before.GetSecurityDescriptorSddlForm(System.Security.AccessControl.AccessControlSections.Owner|System.Security.AccessControl.AccessControlSections.Group|System.Security.AccessControl.AccessControlSections.Access);
  var next=new System.Security.AccessControl.DirectorySecurity();next.SetSecurityDescriptorSddlForm(ancestorSDDL,System.Security.AccessControl.AccessControlSections.Access);next.AddAccessRule(new System.Security.AccessControl.FileSystemAccessRule(System.Security.Principal.WindowsIdentity.GetCurrent().User,System.Security.AccessControl.FileSystemRights.WriteAttributes,System.Security.AccessControl.AccessControlType.Deny));Directory.SetAccessControl(changedAncestor,next);
  if(Directory.GetAccessControl(changedAncestor).GetSecurityDescriptorSddlForm(System.Security.AccessControl.AccessControlSections.Owner|System.Security.AccessControl.AccessControlSections.Group|System.Security.AccessControl.AccessControlSections.Access)==ancestorSDDL)throw new Exception("Fixture ACL did not change");
 }
 static void RestoreAncestor(){if(changedAncestor==null)return;var restored=new System.Security.AccessControl.DirectorySecurity();restored.SetSecurityDescriptorSddlForm(ancestorSDDL,System.Security.AccessControl.AccessControlSections.Access);Directory.SetAccessControl(changedAncestor,restored);if(Directory.GetAccessControl(changedAncestor).GetSecurityDescriptorSddlForm(System.Security.AccessControl.AccessControlSections.Owner|System.Security.AccessControl.AccessControlSections.Group|System.Security.AccessControl.AccessControlSections.Access)!=ancestorSDDL)throw new Exception("Fixture ACL restore failed");changedAncestor=null;}
 static void Wait(ManualResetEventSlim signal){if(!signal.Wait(5000))throw new Exception("Fixture coordination timed out");}
 public static string Item(int index,string root,string path,int maximum,object anchors=null){
  current=new ItemRecord{index=index};lock(gate){records.Add(index,current);started.Add(index);active++;highWater=Math.Max(highWater,active);if(started.Count==expectedActive)allStarted.Set();}
  try{
   if(mode.StartsWith("parallel-"))Wait(allStarted);
   if(mode=="parallel-order"&&index==0)Wait(otherFinished);
   string result=(string)(anchors==null?Call("Snapshot",root,path,maximum):Call("SnapshotWithAnchors",root,path,maximum,anchors));
   if(mode.StartsWith("parallel-failure")&&index!=failureIndex){lock(gate){if(++slowCount==expectedActive-1)slowWaiting.Set();}Wait(releaseSlow);}
   return result;
  }finally{lock(gate){active--;settled.Add(index);}if(mode.StartsWith("parallel-failure")&&index==failureIndex)failed.Set();if(index!=0)otherFinished.Set();}
 }
 static object Call(string name,params object[] args){return typeof(TraceState).GetMethod(name,BindingFlags.Static|BindingFlags.NonPublic).Invoke(null,args);}
 static object RootQuery(string root){
  string drive=(string)Call("Canonical",Path.GetPathRoot(root));var actual=new char[Math.Min(32768,drive.Length+6)];
  using(var handle=(SafeFileHandle)Call("CreateFile",@"\\?\"+drive+@"\",(uint)0x20080,(uint)3,IntPtr.Zero,(uint)3,(uint)0x02200000,IntPtr.Zero)){
   if(handle.IsInvalid)throw new IOException();
   uint count=(uint)Call("GetFinalPathNameByHandle",handle,actual,(uint)actual.Length,(uint)0);
   if(count==0||count>=actual.Length)throw new IOException();
   return new{canonicalDrive=drive,capacity=actual.Length,count=count,raw=new string(actual,0,(int)count)};
  }
 }
 static string Digest(string value){using(var sha=SHA256.Create())return BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(value))).Replace("-","").ToLowerInvariant();}
 static int Main(string[] args){try{
  AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling",false);AppContext.SetSwitch("Switch.System.IO.BlockLongPaths",false);
  string input=null;if(args.Length==3&&args[0]=="batch"&&args[2]=="--request-stdin"){input=Console.In.ReadToEnd();args[2]=input;}
  Console.OutputEncoding=new UTF8Encoding(false);object result=null;bool rejected=false;string type=null;double elapsed=0;bool completedBeforeRelease=false;int activeBeforeRelease=0;mode=args[0];failureIndex=mode.EndsWith("0")?0:7;
  if(mode.StartsWith("parallel-")){expectedActive=8;int workers,ports;ThreadPool.GetMinThreads(out workers,out ports);if(!ThreadPool.SetMinThreads(Math.Max(workers,16),ports))throw new Exception("Fixture worker availability");}
  if(args.Length==2&&args[0]=="root-query")result=RootQuery(args[1]);
  else{long start=Stopwatch.GetTimestamp();try{
   if(args.Length==4&&args[0]=="single")result=Item(0,args[1],args[2],Int32.Parse(args[3]));
   else if(args.Length==3&&(args[0]=="batch"||args[0]=="replay"||args[0]=="ancestor-acl"))result=Call("SnapshotMany",args[1],args[2]);
   else if(args.Length==3&&mode.StartsWith("parallel-")){
    var pending=Task.Factory.StartNew(()=>Call("SnapshotMany",args[1],args[2]));
    if(mode.StartsWith("parallel-failure")){Wait(failed);Wait(slowWaiting);completedBeforeRelease=pending.IsCompleted;lock(gate){activeBeforeRelease=active;}releaseSlow.Set();}
    result=pending.GetAwaiter().GetResult();
   }else return 1;
  }catch(Exception error){rejected=true;type=error.GetBaseException().GetType().FullName;}finally{RestoreAncestor();}
  elapsed=(Stopwatch.GetTimestamp()-start)*1000.0/Stopwatch.Frequency;}
  if(args[0]=="replay"&&!rejected)using(var sha=SHA256.Create())result=Convert.ToBase64String(sha.ComputeHash(Encoding.UTF8.GetBytes((string)result)));
  Console.WriteLine(new JavaScriptSerializer().Serialize(new{result=result,rejected=rejected,type=type,inputCharacters=input==null?0:input.Length,inputSHA256=input==null?null:Digest(input),calls=calls,anchorOpens=anchorOpens,buffers=buffers.Count,lengths=lengths.Values,records=records.Values,active=active,highWater=highWater,started=started,settled=settled,completedBeforeRelease=completedBeforeRelease,activeBeforeRelease=activeBeforeRelease,elapsedMs=elapsed}));return 0;
 }catch(Exception error){Console.Error.WriteLine(error.GetBaseException().GetType().FullName);return 1;}}
}
`;

function observeSource(source) {
  const hooks = [
    [
      'SnapshotWithAnchors(root,(string)item["path"],(int)item["maximum"],anchors)',
      'BufferFixture.Item(index,root,(string)item["path"],(int)item["maximum"],anchors)',
    ],
    [
      'uint count=GetFinalPathNameByHandle(h,actual,(uint)actual.Length,0);',
      'BufferFixture.Record(actual); ',
    ],
    ['h=CreateFile(', 'BufferFixture.Open(); '],
    ['var handle=CreateFile(', 'BufferFixture.AnchorOpen(); '],
    [
      '        try {\n        var results=new string[items.Length];',
      '        BufferFixture.AfterAnchors(root);\n',
    ],
    ['Info info; if(!GetFileInformationByHandle', 'BufferFixture.Info(); '],
    ['if(!GetFileInformationByHandle(h,out before))', 'BufferFixture.Info(); '],
    ['if(!GetFileInformationByHandle(h,out after)', 'BufferFixture.Info(); '],
    ['if(GetSecurityInfo(', 'BufferFixture.Security(); '],
    ['int n=stream.Read(', 'BufferFixture.Read();'],
  ];
  let observed = source;
  for (const [marker, hook] of hooks) {
    assert.equal(observed.split(marker).length, 2, `Exactly one observation site: ${marker}`);
    observed = observed.replace(
      marker,
      hook + (hook.startsWith('BufferFixture.Item(') ? '' : marker),
    );
  }
  return observed;
}

test('Windows batch keeps private fresh opens, per-item guards and one-command namespace ownership', () => {
  const source = fs.readFileSync(
    join(repository, 'packages/codex-adapter/windows/trace-state.cs'),
    'utf8',
  );
  const batch = source.indexOf('    static string SnapshotMany(');
  const dispatch = source.indexOf('        var anchors=items.Length>1');
  assert.equal(
    digest(
      source.slice(
        source.indexOf('// Fixed read-only trace boundary.'),
        source.indexOf('    sealed class NamespaceAnchor'),
      ),
    ),
    '5c73b89775c9086ce91bb8fffa10154db5f0e34b7b8ff2f52448155021adda0b',
    'Original ACL, path and complete Inspect functions remain byte-identical',
  );
  assert.equal(
    digest(source.slice(batch, dispatch)),
    '6731c53137d08e767d8233a2b2db7fb3adb0f62df54f0fa3b130007e825f8720',
    'All request/response validation stays before any namespace capture',
  );
  assert.equal(
    digest(
      source.slice(
        source.indexOf('            byte[] bytes=new byte[0];'),
        source.indexOf('        finally { foreach(var h in ownedHandles)'),
      ),
    ),
    '67050a2d0aef89221faad418bee52cf405ae707f678e4551ceb078d45222c43b',
    'Body and complete post-read guards remain unchanged',
  );
  assert.equal(
    digest(source.slice(source.indexOf('    static int Main'))),
    'f462c685ab354623b844e2ddf9823a14d45edeacd0b98a1d3a2292a4979b6e4c',
    'S/P/serve/error wire publication remains unchanged',
  );
  assert.match(source, /if\(ancestor && anchors!=null && anchors.TryGetValue/);
  assert.match(source, /if\(anchor!=null && identity!=anchor.Identity\)throw new IOException/);
  assert.match(
    source,
    /finally \{ if\(anchors!=null\)foreach\(var anchor in anchors.Values\)anchor.Handle.Dispose\(\); \}/,
  );
  assert.match(
    source,
    /else Parallel.For\(0,items.Length,new ParallelOptions \{ MaxDegreeOfParallelism=8 \},snapshot\);/,
  );
  observeSource(source);
});

test(
  'Windows native path buffers preserve complete snapshots, every Inspect and rejection',
  { skip: process.platform !== 'win32' ? 'Requires real Windows Framework/DACL/PInvoke' : false },
  async (t) => {
    const {
      protectWindowsStateDirectory: protectDirectory,
      protectWindowsStateFile: protectFile,
      windowsPowerShellEnvironment,
      closeWindowsPrivateStateBackend,
    } = await import('../packages/codex-adapter/dist/desktop-platform.js');
    const { withWindowsProcessCreation } =
      await import('../packages/codex-adapter/dist/windows-process.js');
    const statePath = join(repository, 'packages/codex-adapter/windows/trace-state.cs');
    const processPath = join(repository, 'packages/codex-adapter/windows/trace-process.cs');
    const source = fs.readFileSync(statePath, 'utf8');
    const processSource = fs.readFileSync(processPath);
    const allocation = 'var actual=new char[Math.Min(32768,path.Length+6)];';
    assert.equal(source.split(allocation).length, 2, 'Exactly one canonical allocation');
    const oldSource = source.replace(allocation, 'var actual=new char[32768];');
    const marker = 'uint count=GetFinalPathNameByHandle(h,actual,(uint)actual.Length,0);';
    assert.equal(source.split(marker).length, 2, 'Exactly one unchanged native path query');
    const base = join(repository, '.local');
    fs.mkdirSync(base, { recursive: true, mode: 0o700 });
    const directory = fs.realpathSync(fs.mkdtempSync(join(base, 'trace-buffer-native-')));
    protectDirectory(directory);
    t.diagnostic(`Synthetic-only artifacts preserved: ${directory}`);
    const write = (name, bytes) => {
      const path = join(directory, name);
      fs.writeFileSync(path, bytes, { flag: 'wx', mode: 0o600 });
      protectFile(path);
      return path;
    };
    let sequence = 0;
    const run = (executable, args, budget = 5000, env, input) => {
      const result = withWindowsProcessCreation(budget, (timeout) =>
        spawnSync(executable, args, {
          timeout,
          env,
          input,
          windowsHide: true,
          stdio: 'pipe',
          maxBuffer: 4 * 1024 * 1024,
        }),
      );
      const label = `call-${++sequence}`;
      write(label + '.stdout', result.stdout ?? Buffer.alloc(0));
      write(label + '.stderr', result.stderr ?? Buffer.alloc(0));
      if (input !== undefined) write(label + '.stdin', input);
      write(
        label + '.json',
        JSON.stringify({
          executable,
          args,
          pid: result.pid ?? null,
          inputBytes: input === undefined ? null : Buffer.byteLength(input),
          inputSHA256: input === undefined ? null : digest(input),
          status: result.status,
          signal: result.signal,
          errorCode: result.error?.code ?? null,
        }),
      );
      assert.equal(result.error, undefined, 'Infrastructure failure is not native rejection');
      assert.equal(result.signal, null);
      assert(Number.isInteger(result.status));
      return {
        status: result.status,
        stdout: result.stdout.toString('utf8'),
        stderr: result.stderr.toString('utf8'),
      };
    };
    const compiler = ['Framework64', 'Framework']
      .map((name) => join(process.env.SystemRoot, 'Microsoft.NET', name, 'v4.0.30319/csc.exe'))
      .find((path) => fs.existsSync(path));
    assert(compiler, 'Existing Framework compiler required; no download/fallback');
    const entry = write('observation.cs', observation);
    const frozenProcess = write('trace-process.cs', processSource);
    const binaries = {};
    const report = {
      kind: 'SYNTHETIC_NATIVE_BUFFER_EQUIVALENCE_NOT_REAL_TRACE',
      sourceSHA256: digest(source),
      processSHA256: digest(processSource),
      cases: [],
      replay: [],
    };
    try {
      for (const [version, text] of [
        ['old', oldSource],
        ['new', source],
      ]) {
        binaries[version] = {};
        for (const observed of [false, true]) {
          const label = version + (observed ? '-observed' : '-cli');
          const cs = write(label + '.cs', observed ? observeSource(text) : text);
          const exe = join(directory, label + '.exe');
          const compiled = run(
            compiler,
            [
              '/nologo',
              '/target:exe',
              '/main:' + (observed ? 'BufferFixture' : 'TraceState'),
              '/r:System.Web.Extensions.dll',
              '/out:' + exe,
              cs,
              frozenProcess,
              ...(observed ? [entry] : []),
            ],
            30000,
          );
          assert.equal(compiled.status, 0, 'Actual same-source C# compilation must succeed');
          protectFile(exe);
          binaries[version][observed ? 'observed' : 'cli'] = exe;
        }
      }
      const mkdir = (path) => {
        fs.mkdirSync(path, { mode: 0o700 });
        protectDirectory(path);
        return path;
      };
      const root = mkdir(join(directory, 'data'));
      const folder = mkdir(join(root, 'folder'));
      let deep = folder;
      for (let i = 0; deep.length <= 280; i++) deep = mkdir(join(deep, 'component-' + i));
      const file = (path, text) => {
        fs.writeFileSync(path, text, { flag: 'wx', mode: 0o600 });
        protectFile(path);
        return path;
      };
      const short = file(join(folder, 'short.txt'), 'synthetic short bytes');
      const long = file(join(deep, 'long-数据-🧪.txt'), 'synthetic long bytes');
      const unicode = file(join(folder, '数据-🧪.txt'), 'synthetic non-BMP bytes');
      const empty = file(join(folder, 'empty.txt'), '');
      const full = file(join(folder, 'full.txt'), 'x'.repeat(16384));
      assert(long.length > 260);
      const items = [
        { path: short, maximum: 64 },
        { path: root, maximum: 0 },
        { path: long, maximum: 64 },
        { path: deep, maximum: 0 },
        { path: unicode, maximum: 64 },
        { path: short, maximum: 1 },
        { path: full, maximum: 16384 },
        { path: long, maximum: 64 },
      ];
      const expected = new Map();
      const key = (item) => JSON.stringify(item);
      const assertRecord = (record, item, sharedAncestors = 0) => {
        const value = expected.get(key(item));
        assert.equal(record.calls, value.chain.length * 2, 'Exact two complete Inspect passes');
        assert.equal(
          record.opens,
          value.chain.length - sharedAncestors,
          'Private root and every contained component are freshly opened for every item',
        );
        assert.equal(
          record.security,
          value.chain.length * 2,
          'Every Inspect freshly reads security',
        );
        assert.equal(record.infos, value.chain.length * 2 + (item.maximum > 0 ? 2 : 0));
        if (value.bytes.length > 0) assert(record.reads > 0, 'File body is actually read');
        else assert.equal(record.reads, 0);
      };
      const assertProof = (observed, batchItems) => {
        assert.equal(observed.active, 0, 'All calls settled before returning');
        assert(observed.highWater >= 1 && observed.highWater <= Math.min(8, batchItems.length));
        assert.deepEqual(
          observed.started.toSorted((a, b) => a - b),
          batchItems.map((_, i) => i),
        );
        assert.deepEqual(
          observed.settled.toSorted((a, b) => a - b),
          batchItems.map((_, i) => i),
        );
        assert.deepEqual(
          observed.records.map((record) => record.index),
          batchItems.map((_, i) => i),
        );
        const ancestors =
          batchItems.length > 1
            ? expected.get(key({ path: root, maximum: 0 })).chain.length - 1
            : 0;
        assert.equal(
          observed.anchorOpens,
          ancestors,
          'Only above-root anchors are opened once for this command',
        );
        observed.records.forEach((record, index) =>
          assertRecord(record, batchItems[index], ancestors),
        );
      };
      for (const item of [...items, { path: empty, maximum: 1 }, { path: folder, maximum: 0 }]) {
        const values = [];
        for (const version of ['old', 'new']) {
          const result = run(binaries[version].cli, [
            'snapshot',
            root,
            item.path,
            String(item.maximum),
          ]);
          assert.equal(result.status, 0);
          const value = JSON.parse(result.stdout);
          values.push(value);
          const resultObserved = run(binaries[version].observed, [
            'single',
            root,
            item.path,
            String(item.maximum),
          ]);
          assert.equal(resultObserved.status, 0);
          const observed = JSON.parse(resultObserved.stdout);
          assert.equal(observed.rejected, false);
          assert.deepEqual(JSON.parse(observed.result), value);
          assert.equal(observed.calls, value.chain.length * 2);
          assert.equal(observed.buffers, 1);
          assert.deepEqual(observed.lengths, [
            version === 'old' ? 32768 : Math.min(32768, item.path.length + 6),
          ]);
        }
        assert.deepEqual(values[1], values[0], 'Complete identity/chain/base64 bytes must match');
        expected.set(key(item), values[0]);
        report.cases.push({
          path: item.path,
          maximum: item.maximum,
          chainLength: values[0].chain.length,
          snapshotSHA256: digest(JSON.stringify(values[0])),
        });
      }
      const request = Buffer.from(
        JSON.stringify({
          schemaVersion: 1,
          kind: 'snapshot-many',
          id: 1,
          items: items.map((item, index) => ({ index, ...item })),
        }),
      ).toString('base64');
      for (const batchItems of [
        ...Array.from({ length: 8 }, (_, index) => items.slice(0, index + 1)),
        Array.from({ length: 32 }, (_, index) => ({ ...items[index % items.length] })),
        Array.from({ length: 64 }, () => ({ ...items[0] })),
        [
          { path: empty, maximum: 1 },
          { path: full, maximum: 16384 },
        ],
      ]) {
        const count = batchItems.length;
        const batchRequest = Buffer.from(
          JSON.stringify({
            schemaVersion: 1,
            kind: 'snapshot-many',
            id: count,
            items: batchItems.map((item, index) => ({ index, ...item })),
          }),
        ).toString('base64');
        const batchValues = [];
        for (const version of ['old', 'new']) {
          const result =
            count > 8
              ? run(
                  binaries[version].observed,
                  ['batch', root, '--request-stdin'],
                  5000,
                  undefined,
                  batchRequest,
                )
              : run(binaries[version].observed, ['batch', root, batchRequest]);
          assert.equal(result.status, 0);
          const observed = JSON.parse(result.stdout);
          assert.equal(observed.rejected, false);
          const batch = JSON.parse(observed.result);
          assert.deepEqual(
            batch.items.map((item) => item.index),
            batchItems.map((_, index) => index),
          );
          assert.equal(batch.id, count);
          assert.deepEqual(
            batch.items.map((item) => item.snapshot),
            batchItems.map((item) => expected.get(key(item))),
          );
          assert.equal(
            observed.calls,
            2 * batchItems.reduce((sum, item) => sum + expected.get(key(item)).chain.length, 0),
          );
          assert.equal(
            observed.buffers,
            count,
            'Duplicates still acquire independent full-chain receipts',
          );
          assert.deepEqual(
            observed.lengths,
            batchItems.map((item) =>
              version === 'old' ? 32768 : Math.min(32768, item.path.length + 6),
            ),
          );
          assertProof(observed, batchItems);
          assert(observed.highWater <= 8, 'At most eight full native reads may overlap');
          if (count === 1) assert.equal(observed.highWater, 1);
          batchValues.push(batch);
        }
        assert.deepEqual(batchValues[1], batchValues[0]);
      }
      const ancestorFailure = run(binaries.new.observed, ['ancestor-acl', root, request], 15000);
      assert.equal(ancestorFailure.status, 0, 'Fixture restores the original ancestor ACL');
      const rejectedAncestor = JSON.parse(ancestorFailure.stdout);
      assert.equal(
        rejectedAncestor.rejected,
        true,
        'ACL change after namespace capture rejects the whole batch',
      );
      assert.equal(rejectedAncestor.result, null, 'No partial snapshot is published');
      assert.equal(
        rejectedAncestor.anchorOpens,
        expected.get(key({ path: root, maximum: 0 })).chain.length - 1,
      );
      const nextCommand = run(binaries.new.observed, ['batch', root, request]);
      assert.equal(nextCommand.status, 0);
      const freshCommand = JSON.parse(nextCommand.stdout);
      assert.equal(
        freshCommand.rejected,
        false,
        'The next B captures fresh anchors after ACL restoration',
      );
      assertProof(freshCommand, items);
      report.cases.push({
        kind: 'ancestor-acl-after-capture',
        rejected: true,
        nextCommandFresh: true,
      });
      const ordered = run(binaries.new.observed, ['parallel-order', root, request], 15000);
      assert.equal(ordered.status, 0);
      const reordered = JSON.parse(ordered.stdout);
      assert.equal(reordered.rejected, false);
      assert.equal(
        reordered.highWater,
        8,
        'The bounded executor really overlaps eight full Snapshots',
      );
      assert.notEqual(
        reordered.settled[0],
        0,
        'A later index is forced to finish before item zero',
      );
      assertProof(reordered, items);
      assert.deepEqual(
        JSON.parse(reordered.result).items,
        items.map((item, index) => ({ index, snapshot: expected.get(key(item)) })),
        'Response order and duplicate values are independent of completion order',
      );
      for (const failureIndex of [0, 7]) {
        const failedItems = items.map((item, index) => ({
          index,
          ...item,
          ...(index === failureIndex ? { path: join(root, 'missing.txt') } : {}),
        }));
        const encoded = Buffer.from(
          JSON.stringify({
            schemaVersion: 1,
            kind: 'snapshot-many',
            id: 9,
            items: failedItems,
          }),
        ).toString('base64');
        const result = run(
          binaries.new.observed,
          ['parallel-failure' + failureIndex, root, encoded],
          15000,
        );
        assert.equal(result.status, 0);
        const observed = JSON.parse(result.stdout);
        assert.equal(observed.rejected, true, 'Actual CreateFile rejection fails the entire batch');
        assert.equal(observed.type, 'System.IO.IOException');
        assert.equal(observed.result, null, 'No partial items are returned on failure');
        assert.equal(observed.highWater, 8);
        assert.equal(
          observed.completedBeforeRelease,
          false,
          'Failure waits for the blocked in-flight item',
        );
        assert.equal(observed.activeBeforeRelease, 7);
        assert.equal(observed.active, 0);
        assert.deepEqual(
          observed.started.toSorted(),
          items.map((_, index) => index),
          'No fallback or repeated Snapshot',
        );
        assert.deepEqual(
          observed.settled.toSorted(),
          items.map((_, index) => index),
        );
        for (const record of observed.records) {
          if (record.index !== failureIndex)
            assertRecord(
              record,
              items[record.index],
              expected.get(key({ path: root, maximum: 0 })).chain.length - 1,
            );
        }
      }
      const validRequest = () => ({
        schemaVersion: 1,
        kind: 'snapshot-many',
        id: 10,
        items: items.map((item, index) => ({ index, ...item })),
      });
      const invalid = [];
      for (const [label, change] of [
        [
          'late invalid index',
          (value) => {
            value.items[7].index = 0;
          },
        ],
        [
          'late invalid maximum',
          (value) => {
            value.items[7].maximum = 16385;
          },
        ],
        [
          'late integer overflow',
          (value) => {
            value.items[7].maximum = 2147483648;
          },
        ],
        [
          'late path alias',
          (value) => {
            value.items[7].path += ':alias';
          },
        ],
        [
          'response estimate',
          (value) => {
            value.items.forEach((item) => {
              item.path = root + '\\a'.repeat(300);
            });
          },
        ],
        [
          'empty batch',
          (value) => {
            value.items = [];
          },
        ],
        [
          '65th item',
          (value) => {
            value.items = Array.from({ length: 65 }, (_, index) => ({ ...items[0], index }));
          },
        ],
      ]) {
        const value = validRequest();
        change(value);
        invalid.push([label, Buffer.from(JSON.stringify(value)).toString('base64')]);
      }
      invalid.push(['request bytes', Buffer.from(' '.repeat(48 * 1024 + 1)).toString('base64')]);
      invalid.push([
        'duplicate key',
        Buffer.from(JSON.stringify(validRequest()).replace('"id":10', '"id":9,"id":10')).toString(
          'base64',
        ),
      ]);
      for (const [label, encoded] of invalid) {
        // The request-size rejection itself exceeds CreateProcessW's argv limit.
        // Keep the full invalid request; feed only this fixture through stdin.
        const result = run(
          binaries.new.observed,
          ['batch', root, '--request-stdin'],
          5000,
          undefined,
          encoded,
        );
        assert.equal(result.status, 0);
        const observed = JSON.parse(result.stdout);
        assert.equal(observed.inputCharacters, encoded.length, label);
        assert.equal(observed.inputSHA256, digest(encoded), label);
        assert.equal(observed.rejected, true, label);
        assert.equal(observed.result, null, label);
        assert.deepEqual(
          observed.started,
          [],
          'Whole request must validate before any Snapshot: ' + label,
        );
        assert.equal(observed.calls, 0);
        assert.equal(observed.buffers, 0);
      }
      const boundary = run(binaries.new.observed, ['root-query', root]);
      assert.equal(boundary.status, 0);
      const raw = JSON.parse(boundary.stdout).result;
      assert.match(raw.canonicalDrive, /^[A-Za-z]:$/);
      assert.equal(raw.capacity, raw.canonicalDrive.length + 6);
      assert.equal(raw.raw.toLowerCase(), ('\\\\?\\' + raw.canonicalDrive + '\\').toLowerCase());
      assert.equal(raw.count, raw.raw.length);
      assert(raw.count < raw.capacity, 'Drive root includes extra slash and NUL within +6');
      report.rootDriveReadOnlyQuery = raw; // Not a claim that a drive is an accepted private root.
      const reject = (label, path, maximum = 64, inputRoot = root) => {
        for (const version of ['old', 'new']) {
          const result = run(binaries[version].cli, ['snapshot', inputRoot, path, String(maximum)]);
          assert.equal(result.status, 1, label);
          assert.equal(result.stdout.trim(), '');
          assert.equal(result.stderr.trim(), 'UNSAFE_WINDOWS_TRACE_SNAPSHOT');
        }
        report.cases.push({ rejected: label });
      };
      reject('ADS alias', short + ':stream');
      reject('trailing alias', short + '.');
      reject('device namespace root', short, 64, '\\\\?\\' + root);
      const link = join(folder, 'hardlink.txt');
      fs.linkSync(short, link);
      try {
        reject('hardlinked leaf', short);
      } finally {
        fs.unlinkSync(link);
      }
      const junction = join(root, 'junction');
      fs.symlinkSync(folder, junction, 'junction');
      try {
        reject('junction chain', join(junction, 'short.txt'));
      } finally {
        fs.unlinkSync(junction);
      }
      for (const [path, directoryTarget] of [
        [short, false],
        [root, true],
      ]) {
        try {
          const changed = run(
            join(process.env.SystemRoot, 'System32/WindowsPowerShell/v1.0/powershell.exe'),
            [
              '-NoProfile',
              '-NonInteractive',
              '-Command',
              "$p=$env:TRACE_BUFFER_TARGET;$s=[Security.Principal.SecurityIdentifier]::new('S-1-1-0');$a=Get-Acl -LiteralPath $p;$a.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($s,'ReadAttributes','Allow'));Set-Acl -LiteralPath $p -AclObject $a;$found=@((Get-Acl -LiteralPath $p).GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])|Where-Object{$_.IdentityReference -eq $s -and $_.AccessControlType -eq 'Allow' -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::ReadAttributes)});if($found.Count -eq 0){throw 'fixture-acl-not-observed'};Write-Output 'fixture-acl-observed'",
            ],
            30000,
            { ...windowsPowerShellEnvironment(), TRACE_BUFFER_TARGET: path },
          );
          assert.equal(changed.status, 0);
          assert.equal(changed.stdout.trim(), 'fixture-acl-observed');
          reject(directoryTarget ? 'foreign root DACL' : 'foreign file DACL', short);
        } finally {
          if (directoryTarget) protectDirectory(path);
          else protectFile(path);
        }
      }
      if (replay) {
        // Four fixed, balanced process pairs; full SnapshotMany, no guard cache.
        // Reflection/counter and process overhead mean these are auxiliary only.
        for (let round = 0; round < 4; round++) {
          const rows = [];
          for (const version of round % 2 === 0 ? ['old', 'new'] : ['new', 'old']) {
            const result = run(binaries[version].observed, ['replay', root, request]);
            assert.equal(result.status, 0);
            const observed = JSON.parse(result.stdout);
            assert.equal(observed.rejected, false);
            assert.equal(observed.buffers, 8);
            rows.push({ version, ...observed });
          }
          assert.equal(rows[0].result, rows[1].result);
          assert.equal(rows[0].calls, rows[1].calls);
          report.replay.push({ round, rows });
        }
      }
      report.status = 'SYNTHETIC_EQUIVALENCE_PASS';
      report.productionTimingOrHTTPAcceptance = false;
    } catch (error) {
      report.status = 'FAILED_PRESERVED';
      report.errorName = error.name;
      throw error;
    } finally {
      try {
        assert.equal(fs.readFileSync(statePath, 'utf8'), source, 'Canonical C# source drift');
        assert.deepEqual(fs.readFileSync(processPath), processSource, 'Process guard source drift');
        write('RESULT.json', JSON.stringify(report, null, 2));
      } finally {
        closeWindowsPrivateStateBackend();
      }
    }
  },
);
