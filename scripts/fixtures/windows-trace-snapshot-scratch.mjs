import assert from 'node:assert/strict';
import fs from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const repository = fileURLToPath(new URL('../../', import.meta.url));

// Compile the actual C# Snapshot with an observation-only hook immediately before
// GetFinalPathNameByHandle. No syscall/guard is replaced, and no real Trace is read.
export async function windowsTraceScratchFixture() {
  assert.equal(process.platform, 'win32');
  const { protectWindowsStateDirectory, protectWindowsStateFile, windowsTraceSnapshot } =
    await import('../../packages/codex-adapter/dist/desktop-platform.js');
  const { withWindowsProcessCreation } =
    await import('../../packages/codex-adapter/dist/windows-process.js');
  const base = join(repository, '.local');
  fs.mkdirSync(base, { recursive: true, mode: 0o700 });
  const directory = fs.realpathSync(fs.mkdtempSync(join(base, 'trace-scratch-')));
  protectWindowsStateDirectory(directory);
  const root = join(directory, 'data');
  fs.mkdirSync(root, { mode: 0o700 });
  protectWindowsStateDirectory(root);
  let deep = root;
  for (let i = 0; deep.length <= 280; i++) {
    deep = join(deep, 'synthetic-component-' + i);
    fs.mkdirSync(deep, { mode: 0o700 });
    protectWindowsStateDirectory(deep);
  }
  const long = join(deep, 'long-数据-🧪.txt'),
    short = join(root, 'short.txt');
  fs.writeFileSync(long, 'long synthetic bytes', { mode: 0o600 });
  fs.writeFileSync(short, 'short synthetic bytes', { mode: 0o600 });
  protectWindowsStateFile(long);
  protectWindowsStateFile(short);
  assert(long.length > 260);
  const source = fs.readFileSync(
    join(repository, 'packages/codex-adapter/windows/trace-state.cs'),
    'utf8',
  );
  const marker = 'uint count=GetFinalPathNameByHandle(h,actual,(uint)actual.Length,0);';
  const itemCall = 'Snapshot(root,(string)item["path"],(int)item["maximum"])';
  assert.equal(source.split(marker).length, 2, 'Exactly one actual native path query site');
  assert.equal(source.split(itemCall).length, 2, 'Exactly one full Snapshot call per batch item');
  const instrumented = join(directory, 'trace-state-observed.cs');
  fs.writeFileSync(
    instrumented,
    source
      .replace(marker, 'ScratchFixture.Record(actual); ' + marker)
      .replace(
        itemCall,
        'ScratchFixture.Item(index,root,(string)item["path"],(int)item["maximum"])',
      ),
    { mode: 0o600 },
  );
  const entry = join(directory, 'entry.cs'),
    executable = join(directory, 'scratch.exe');
  fs.writeFileSync(
    entry,
    `using System;using System.IO;using System.Text;using System.Reflection;
using System.Collections.Generic;using System.Security.AccessControl;using System.Security.Principal;using System.Web.Script.Serialization;
class ScratchFixture {
 static readonly object gate=new object();static HashSet<char[]> buffers=new HashSet<char[]>();static SortedDictionary<int,int> capacities=new SortedDictionary<int,int>();static SortedDictionary<int,int> itemCalls=new SortedDictionary<int,int>();
 [ThreadStatic]static int itemIndex;static int calls;static int mutateAt;static string root;static bool injected;
 public static string Item(int index,string inputRoot,string path,int maximum){itemIndex=index;return (string)Call("Snapshot",inputRoot,path,maximum);}
 public static void Record(char[] actual){lock(gate){if(actual.Length<8||actual.Length>32768)throw new Exception("Fixture path query capacity outside bounds");if(buffers.Add(actual))capacities.Add(itemIndex,actual.Length);calls++;
  int count;itemCalls.TryGetValue(itemIndex,out count);itemCalls[itemIndex]=count+1;
  if(mutateAt==calls){var acl=Directory.GetAccessControl(root);acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier("S-1-1-0"),FileSystemRights.ReadAttributes,AccessControlType.Allow));Directory.SetAccessControl(root,acl);
   bool observed=false;foreach(FileSystemAccessRule rule in Directory.GetAccessControl(root).GetAccessRules(true,true,typeof(SecurityIdentifier)))
    if(rule.IdentityReference.Value=="S-1-1-0"&&rule.AccessControlType==AccessControlType.Allow&&(rule.FileSystemRights&FileSystemRights.ReadAttributes)!=0)observed=true;
   if(!observed)throw new Exception("Fixture mutation not observed");injected=true;}}}
 static object Call(string method,params object[] args){return typeof(TraceState).GetMethod(method,BindingFlags.Static|BindingFlags.NonPublic).Invoke(null,args);}
 static int Main(string[] args){try{
  AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling",false);AppContext.SetSwitch("Switch.System.IO.BlockLongPaths",false);
  Console.OutputEncoding=new UTF8Encoding(false);Console.Out.NewLine="\\n";
  if(args.Length!=3)return 1;root=args[1];var json=new JavaScriptSerializer();object result=null;bool rejected=false;
  if(args[0]=="mutate"){mutateAt=Int32.Parse(args[2])+1;try{Call("Snapshot",root,root,0);}catch(TargetInvocationException){rejected=true;}}
  else if(args[0]=="single")result=Call("Snapshot",root,args[2],64);
  else if(args[0]=="batch")result=Call("SnapshotMany",root,args[2]);else return 1;
  Console.WriteLine(json.Serialize(new{result=result,calls=calls,buffers=buffers.Count,capacities=capacities.Values,itemCalls=itemCalls.Values,injected=injected,rejected=rejected}));return 0;
 }catch(Exception error){Console.Error.WriteLine(error.GetBaseException().GetType().FullName);return 1;}}
}
`,
    { mode: 0o600 },
  );
  const compiler = ['Framework64', 'Framework']
    .map((name) => join(process.env.SystemRoot, 'Microsoft.NET', name, 'v4.0.30319/csc.exe'))
    .find((path) => fs.existsSync(path));
  assert(compiler, 'Framework compiler required');
  withWindowsProcessCreation(30000, (timeout) =>
    execFileSync(
      compiler,
      [
        '/nologo',
        '/target:exe',
        '/main:ScratchFixture',
        '/r:System.Web.Extensions.dll',
        '/out:' + executable,
        entry,
        instrumented,
        join(repository, 'packages/codex-adapter/windows/trace-process.cs'),
      ],
      { timeout, windowsHide: true, stdio: 'pipe' },
    ),
  );
  const call = (...args) =>
    JSON.parse(
      withWindowsProcessCreation(5000, (timeout) =>
        execFileSync(executable, args, {
          timeout,
          windowsHide: true,
          encoding: 'utf8',
          stdio: 'pipe',
        }),
      ),
    );
  const expected = (path) => {
    const value = windowsTraceSnapshot(root, path, 64);
    return { identity: value.identity, chain: value.chain, bytes: value.bytes.toString('base64') };
  };
  try {
    const expectedLong = expected(long),
      expectedShort = expected(short);
    for (const [path, value] of [
      [long, expectedLong],
      [short, expectedShort],
    ]) {
      const observed = call('single', root, path);
      assert.deepEqual(JSON.parse(observed.result), value);
      assert.equal(observed.buffers, 1, 'One scratch buffer per independent Snapshot');
      assert.deepEqual(observed.capacities, [Math.min(32768, path.length + 6)]);
      assert.equal(
        observed.calls,
        value.chain.length * 2,
        'Every chain component still inspected before and after',
      );
      assert.deepEqual(observed.itemCalls, [value.chain.length * 2]);
    }
    for (const count of [1, 2, 8]) {
      const paths = Array.from({ length: count }, (_, index) => (index % 2 === 0 ? long : short));
      const expectedValues = paths.map((path) => (path === long ? expectedLong : expectedShort));
      const request = {
        schemaVersion: 1,
        kind: 'snapshot-many',
        id: 1,
        items: paths.map((path, index) => ({ index, path, maximum: 64 })),
      };
      const observed = call('batch', root, Buffer.from(JSON.stringify(request)).toString('base64'));
      const batch = JSON.parse(observed.result);
      assert.deepEqual(
        batch.items.map((item) => item.index),
        paths.map((_, index) => index),
      );
      assert.deepEqual(
        batch.items.map((item) => item.snapshot),
        expectedValues,
      );
      assert.equal(
        observed.buffers,
        count,
        'Even duplicate paths retain independent per-item scratch storage and native proofs',
      );
      assert.deepEqual(
        observed.capacities,
        paths.map((path) => Math.min(32768, path.length + 6)),
      );
      assert.equal(
        observed.calls,
        2 * expectedValues.reduce((sum, value) => sum + value.chain.length, 0),
      );
      assert.deepEqual(
        observed.itemCalls,
        expectedValues.map((value) => value.chain.length * 2),
        'Each item, including duplicates, performs both complete fresh inspection passes',
      );
    }
    const rootDepth = windowsTraceSnapshot(root, root).chain.length;
    const changed = call('mutate', root, String(rootDepth));
    assert.deepEqual(changed.capacities, [Math.min(32768, root.length + 6)]);
    assert.equal(changed.injected, true, 'READ_ATTRIBUTES allow ACE actually added in own fixture');
    assert.equal(
      changed.rejected,
      true,
      'Post-read ACL check must remain fresh after buffer reuse',
    );
  } finally {
    // Restore only this newly-created test root, never another/private denied root.
    protectWindowsStateDirectory(root);
  }
}
