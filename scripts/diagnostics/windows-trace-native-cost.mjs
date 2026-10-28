// Read-only diagnostic. Compiles the supplied canonical native sources in a
// new private directory; does not install, restart or alter the product helper.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { isAbsolute, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const fixture = String.raw`
using System;using System.IO;using System.Text;using System.Diagnostics;
using System.Collections.Generic;using System.Reflection;using System.Threading;
using System.Web.Script.Serialization;using System.Security.Cryptography;
class TraceCost {
 static long[] calls=new long[4],ticks=new long[4];
 public static void Record(int index,long start){long elapsed=Stopwatch.GetTimestamp()-start;Interlocked.Increment(ref calls[index]);Interlocked.Add(ref ticks[index],elapsed);}
 static object Call(string name,params object[] args){return typeof(TraceState).GetMethod(name,BindingFlags.Static|BindingFlags.NonPublic).Invoke(null,args);}
 static string Hash(string value){using(var sha=SHA256.Create())return BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(value))).Replace("-","").ToLowerInvariant();}
 static object Projection(object value){var item=(Dictionary<string,object>)value;return new{identity=item["identity"],bytesSHA256=Hash((string)item["bytes"])};}
 static int Main(string[] args){try{
  AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling",false);AppContext.SetSwitch("Switch.System.IO.BlockLongPaths",false);
  var json=new JavaScriptSerializer();var input=(Dictionary<string,object>)json.DeserializeObject(Console.In.ReadToEnd());
  string root=(string)input["root"];var items=(object[])input["items"];if(items.Length!=8)throw new Exception("EIGHT_ITEMS_REQUIRED");
  var rows=new List<object>();int id=0;string expected=null;bool equivalent=true;
  foreach(string kind in new[]{"scalar","batch","batch","scalar"}){
   Array.Clear(calls,0,4);Array.Clear(ticks,0,4);var values=new List<object>();
   var process=Process.GetCurrentProcess();double cpu=process.TotalProcessorTime.TotalMilliseconds;long started=Stopwatch.GetTimestamp();
   try{
    if(kind=="scalar")foreach(Dictionary<string,object> item in items)values.Add(json.DeserializeObject((string)Call("Snapshot",root,(string)item["path"],(int)item["maximum"])));
    else{
     var request=new{schemaVersion=1,kind="snapshot-many",id=++id,items=items};
     string encoded=Convert.ToBase64String(Encoding.UTF8.GetBytes(json.Serialize(request)));
     var response=(Dictionary<string,object>)json.DeserializeObject((string)Call("SnapshotMany",root,encoded));
     foreach(Dictionary<string,object> item in (object[])response["items"])values.Add(item["snapshot"]);
    }
    double elapsed=(Stopwatch.GetTimestamp()-started)*1000.0/Stopwatch.Frequency;process.Refresh();double cpuMs=process.TotalProcessorTime.TotalMilliseconds-cpu;
    var metrics=new List<object>();for(int i=0;i<4;i++)metrics.Add(new{category=new[]{"open","inspect","security","finalPath"}[i],calls=calls[i],sumMs=ticks[i]*1000.0/Stopwatch.Frequency});
    var projected=new List<object>();foreach(object value in values)projected.Add(Projection(value));string sha=Hash(json.Serialize(projected));
    if(expected==null)expected=sha;equivalent=equivalent&&sha==expected;
    rows.Add(new{kind=kind,elapsedMs=elapsed,cpuMs=cpuMs,metrics=metrics,projectionSHA256=sha,equalToFirst=sha==expected});
   }catch(Exception error){Console.WriteLine(json.Serialize(new{passed=false,rows=rows,errorType=error.GetBaseException().GetType().FullName}));return 1;}
  }
  Console.WriteLine(json.Serialize(new{passed=equivalent,scope="READ_ONLY_NATIVE_COST_NOT_HTTP_OR_PHONE_ACCEPTANCE",rows=rows}));return equivalent?0:1;
 }catch(Exception error){Console.Error.WriteLine(error.GetBaseException().GetType().FullName);return 1;}}
}
`;

export function instrument(source) {
  const wrappers = [
    [
      'static string Inspect(',
      'static string InspectOriginal(',
      'static string Inspect(SafeFileHandle h,string path,bool directory,bool ancestor,bool root,char[] actual){long at=System.Diagnostics.Stopwatch.GetTimestamp();try{return InspectOriginal(h,path,directory,ancestor,root,actual);}finally{TraceCost.Record(1,at);}}',
    ],
    [
      'static byte[] Security(',
      'static byte[] SecurityOriginal(',
      'static byte[] Security(SafeFileHandle h,bool ancestor,bool root){long at=System.Diagnostics.Stopwatch.GetTimestamp();try{return SecurityOriginal(h,ancestor,root);}finally{TraceCost.Record(2,at);}}',
    ],
  ];
  let observed = source;
  for (const [marker, replacement, wrapper] of wrappers) {
    assert.equal(observed.split(marker).length, 2);
    observed = observed.replace(marker, wrapper + '\n    ' + replacement);
  }
  // Match actual call sites only. P/Invoke declarations and arguments stay intact.
  assert.equal(observed.split('=CreateFile(').length, 3);
  observed = observed.replaceAll('=CreateFile(', '=MeasuredOpen(');
  assert.equal(observed.split('uint count=GetFinalPathNameByHandle(').length, 2);
  observed = observed.replace('uint count=GetFinalPathNameByHandle(', 'uint count=MeasuredPath(');
  const end = observed.lastIndexOf('}');
  return (
    observed.slice(0, end) +
    String.raw`
    static SafeFileHandle MeasuredOpen(string path,uint access,uint share,IntPtr security,uint creation,uint flags,IntPtr template){long at=System.Diagnostics.Stopwatch.GetTimestamp();try{return CreateFile(path,access,share,security,creation,flags,template);}finally{TraceCost.Record(0,at);}}
    static uint MeasuredPath(SafeFileHandle h,char[] path,uint length,uint flags){long at=System.Diagnostics.Stopwatch.GetTimestamp();try{return GetFinalPathNameByHandle(h,path,length,flags);}finally{TraceCost.Record(3,at);}}
` +
    observed.slice(end)
  );
}

export async function run(program, traceRoot) {
  assert.equal(process.platform, 'win32', 'Native Windows required');
  assert(isAbsolute(program) && isAbsolute(traceRoot));
  program = resolve(program);
  const platform = await import(
    pathToFileURL(join(program, 'packages/codex-adapter/dist/desktop-platform.js')).href
  );
  const directory = fs.mkdtempSync(join(program, '.local/trace-native-cost-'));
  platform.protectWindowsStateDirectory(directory);
  const write = (name, value) => {
    const path = join(directory, name);
    fs.writeFileSync(path, value, { mode: 0o600, flag: 'wx' });
    platform.protectWindowsStateFile(path);
    return path;
  };
  try {
    const paths = fs
      .readdirSync(traceRoot, { withFileTypes: true })
      .filter((entry) => entry.isDirectory() && /^trace-[a-f0-9-]+$/i.test(entry.name))
      .map((entry) => join(traceRoot, entry.name, 'manifest.json'))
      .filter((path) => fs.lstatSync(path).isFile())
      .sort()
      .slice(0, 8);
    assert.equal(paths.length, 8);
    const input = JSON.stringify({
      root: traceRoot,
      items: paths.map((path, index) => ({ index, path, maximum: 16384 })),
    });
    const native = fs.readFileSync(join(program, 'packages/codex-adapter/windows/trace-state.cs'));
    const processSource = fs.readFileSync(
      join(program, 'packages/codex-adapter/windows/trace-process.cs'),
    );
    const compiler = join(process.env.SystemRoot, 'Microsoft.NET/Framework64/v4.0.30319/csc.exe');
    assert(fs.existsSync(compiler), 'Existing x64 Framework compiler required');
    const exe = join(directory, 'cost.exe');
    const sources = [
      write('observed.cs', instrument(native.toString('utf8'))),
      write('trace-process.cs', processSource),
      write('cost.cs', fixture),
    ];
    const compile = spawnSync(
      compiler,
      [
        '/nologo',
        '/target:exe',
        '/main:TraceCost',
        '/r:System.Web.Extensions.dll',
        '/out:' + exe,
        ...sources,
      ],
      { timeout: 30000, windowsHide: true, stdio: 'pipe' },
    );
    write('compile.stdout', compile.stdout ?? Buffer.alloc(0));
    write('compile.stderr', compile.stderr ?? Buffer.alloc(0));
    assert.equal(compile.error, undefined);
    assert.equal(compile.status, 0, 'Actual unchanged-guard source must compile');
    platform.protectWindowsStateFile(exe);
    const child = spawnSync(exe, [], {
      input,
      timeout: 8000,
      windowsHide: true,
      stdio: 'pipe',
      maxBuffer: 1024 * 1024,
    });
    write('run.stderr', child.stderr ?? Buffer.alloc(0));
    const result = {
      nativeSourceSHA256: digest(native),
      processSourceSHA256: digest(processSource),
      diagnosticSHA256: digest(fs.readFileSync(new URL(import.meta.url))),
      childExit: child.status,
      signal: child.signal,
      errorCode: child.error?.code ?? null,
      result: child.stdout?.length ? JSON.parse(child.stdout) : null,
    };
    write('result.json', JSON.stringify(result, null, 2));
    console.log(JSON.stringify({ directory, ...result }));
    assert.equal(child.error, undefined);
    assert.equal(child.status, 0);
  } finally {
    platform.closeWindowsPrivateStateBackend();
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  assert.equal(process.argv.length, 4, 'Expected verified program root and actual trace root');
  await run(process.argv[2], process.argv[3]);
}
