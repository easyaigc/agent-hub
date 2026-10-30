// Source-only delivery: do not confuse portable transport fixtures with Win32
// authority, official Store payloads, a real desktop, or phone/model acceptance.
import assert from 'node:assert/strict';
import { execFile, spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { promisify } from 'node:util';
import { Worker } from 'node:worker_threads';
import test from 'node:test';
import ts from 'typescript';
import { frozenBatchFixture } from './fixtures/windows-trace-batch.mjs';

const repository = fileURLToPath(new URL('../', import.meta.url));
const run = promisify(execFile);
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const configPath = String.raw`C:\fixture\private\config.json`;
const request = { kind: 'producer', ProducerPid: 42, Config: configPath };
const desktop = { pid: 123, startedAtUnixMs: 100, executable: String.raw`C:\fixture\desktop.exe` };
const identity = { pid: 42, desktopPid: 123, desktopStartedAt: 100, wrapperStartedAt: 101 };

async function withCodecs(action) {
  const fixture = await frozenBatchFixture();
  try {
    return await action(
      await import(pathToFileURL(join(fixture.directory, 'desktop-trace-native.js'))),
      fixture,
    );
  } finally {
    try {
      fixture.unchanged();
    } finally {
      fs.rmSync(fixture.directory, { recursive: true });
    }
  }
}

test('producer accepts only its fixed PID/config frame, not an arbitrary method or shell', async () => {
  await withCodecs(({ encodeTraceProducerRequest: encode, traceProducerLimits }) => {
    const wire = encode(request);
    assert(wire.startsWith('I\t'));
    assert.deepEqual(JSON.parse(Buffer.from(wire.slice(2), 'base64').toString()), request);
    assert(wire.length <= traceProducerLimits.lineCharacters);
    for (const bad of [
      null,
      [],
      {},
      { ...request, method: 'eval' },
      { ...request, argv: [] },
      { ...request, kind: 'shell' },
      { ...request, Config: undefined },
      { ...request, ProducerPid: undefined },
    ])
      assert.throws(() => encode(bad));
    for (const pid of [0, -1, 1.5, NaN, Infinity, 2147483648, '42', true])
      assert.throws(() => encode({ ...request, ProducerPid: pid }));
    for (const path of [
      'config.json',
      'C:config.json',
      '\\\\server\\share\\config.json',
      '\\\\?\\C:\\private\\config.json',
      'C:/private/config.json',
      'C:\\private\\..\\config.json',
      'C:\\private.\\config.json',
      'C:\\private \\config.json',
      'C:\\private\\config.json:stream',
      'C:\\private\\Config.json',
      'C:\\NUL\\config.json',
      'C:\\PRIVAT~1\\config.json',
      'C:\\private\\config.json\\',
      'C:\\private\\\u0000config.json',
      'C:\\' + 'a'.repeat(32768) + '\\config.json',
      'C:\\' + Array(100).fill('中'.repeat(200)).join('\\') + '\\config.json',
    ])
      assert.throws(() => encode({ ...request, Config: path }), JSON.stringify(path.slice(0, 100)));
  });
});

test('producer replies are exactly four numeric identities bound to the retained desktop instance', async () => {
  await withCodecs(
    ({ decodeTraceProducerIdentity: decode, parseTraceSnapshotBatchJSON: parse }) => {
      assert.deepEqual(decode(identity, request, desktop), identity);
      for (const bad of [
        null,
        [],
        {},
        { ...identity, command: 'private' },
        { ...identity, pid: 43 },
        { ...identity, pid: '42' },
        { ...identity, desktopPid: 124 },
        { ...identity, desktopStartedAt: 101 },
        { ...identity, wrapperStartedAt: 99 },
        { ...identity, wrapperStartedAt: Infinity },
        { ...identity, wrapperStartedAt: Date.now() + 60_000 },
      ])
        assert.throws(() => decode(bad, request, desktop));
      assert.throws(() => parse('{"pid":42,"pid":43}'));
      assert.throws(() => parse('{"pid":42,"p\\u0069d":42}'));
    },
  );
});

test('platform producer boundary is unavailable on main/missing backend and uses only a Worker backend', async () => {
  await withCodecs(async (_codec, fixture) => {
    const source = fs.readFileSync(
      join(repository, 'packages/codex-adapter/src/desktop-platform.ts'),
      'utf8',
    );
    const path = join(fixture.directory, 'actual-platform.js');
    fs.writeFileSync(
      path,
      ts.transpileModule(source, {
        compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 },
      }).outputText,
    );
    const platform = await import(pathToFileURL(path));
    assert.throws(() => platform.windowsSuggestionProducerIdentity(42, configPath), /unavailable/);
    const worker = new Worker(
      `
      const {parentPort}=await import('node:worker_threads');
      const assert=(await import('node:assert/strict')).default;
      Object.defineProperty(process,'platform',{value:'win32'});
      const platform=await import(${JSON.stringify(pathToFileURL(path).href)});
      assert.throws(()=>platform.windowsSuggestionProducerIdentity(42,${JSON.stringify(configPath)}),/unavailable/);
      platform.installWindowsTraceBackend({snapshot(){throw Error('unexpected snapshot')},inspect(){throw Error('unexpected probe')}});
      assert.throws(()=>platform.windowsSuggestionProducerIdentity(42,${JSON.stringify(configPath)}),/unavailable/);
      let calls=0;
      platform.installWindowsTraceBackend({inspectProducer(pid,config){calls++;assert.equal(pid,42);assert.equal(config,${JSON.stringify(configPath)});return ${JSON.stringify(identity)}}});
      assert.deepEqual(platform.windowsSuggestionProducerIdentity(42,${JSON.stringify(configPath)}),${JSON.stringify(identity)});
      assert.equal(calls,1);parentPort.postMessage('ok');parentPort.close();
    `,
      { eval: true, execArgv: ['--input-type=module'] },
    );
    try {
      await new Promise((resolve, reject) => {
        worker.once('error', reject);
        worker.once('message', (value) => {
          try {
            assert.equal(value, 'ok');
            resolve();
          } catch (error) {
            reject(error);
          }
        });
      });
    } finally {
      await worker.terminate();
    }
  });
});

// Real supervisor/registry/gate/transport/owner retirement; only the native exe
// is replaced by an owned synthetic Node stdio service. No Windows ACL claim.
async function producerService() {
  const fs = await import('node:fs');
  const { createInterface } = await import('node:readline');
  const log = (event) =>
    fs.appendFileSync(
      process.env.PRODUCER_TEST_LOG,
      JSON.stringify({ event, pid: process.pid }) + '\n',
    );
  log('started');
  process.on('exit', () => log('exited'));
  console.log('{"ready":true}');
  createInterface({ input: process.stdin })
    .on('line', (line) => {
      if (line === 'QUIT') {
        log('quit');
        process.exit(0);
      }
      log('request');
      const parts = line.split('\t');
      if (parts.length !== 2 || parts[0] !== 'I') {
        console.log('{"error":"FIXED_PRODUCER_ONLY"}');
        return;
      }
      const request = JSON.parse(Buffer.from(parts[1], 'base64').toString());
      if (
        Object.keys(request).sort().join(',') !== 'Config,ProducerPid,kind' ||
        request.kind !== 'producer' ||
        request.ProducerPid !== 42
      )
        throw Error('unexpected synthetic request');
      if (process.env.PRODUCER_TEST_MODE === 'retire') return;
      const response = { pid: 42, desktopPid: 123, desktopStartedAt: 100, wrapperStartedAt: 101 };
      if (process.env.PRODUCER_TEST_MODE === 'bad-reply') response.environment = 'must reject';
      console.log(JSON.stringify(response));
    })
    .once('close', () => process.exit(0));
}

async function producerOwner() {
  const { parentPort, workerData } = await import('node:worker_threads');
  const { createWindowsTraceBackend } = await import('./desktop-trace-native.js');
  const handle = await createWindowsTraceBackend(
    { pid: 123, startedAtUnixMs: 100, executable: 'C:\\fixture\\desktop.exe' },
    'C:\\fixture\\traces',
  );
  let identities, error;
  try {
    handle.budget(1800);
    identities = [handle.backend.inspectProducer(42, 'C:\\fixture\\private\\config.json')];
    identities.push(handle.backend.inspectProducer(42, 'C:\\fixture\\private\\config.json'));
  } catch (failure) {
    error = failure.message;
  } finally {
    handle.close();
  }
  parentPort.postMessage({ identities, error, mode: workerData.mode });
  parentPort.close();
}

async function producerMain() {
  const assert = (await import('node:assert/strict')).default;
  const fs = await import('node:fs');
  const { Worker } = await import('node:worker_threads');
  const { join } = await import('node:path');
  const { setTimeout: delay } = await import('node:timers/promises');
  const { monitorPrivateStateWorkers } = await import('./monitor.mjs');
  const { registerWindowsTraceTransport, retireWindowsNativeOwner } =
    await import('./desktop-private-state-native.js');
  const { getWindowsProcessGate, registerWindowsProcessWorker } =
    await import('./windows-process.js');
  const directory = process.argv[2],
    mode = process.argv[3];
  process.env.PRODUCER_TEST_LOG = join(directory, 'producer-events.jsonl');
  process.env.PRODUCER_TEST_MODE = mode;
  const rows = () =>
    fs.existsSync(process.env.PRODUCER_TEST_LOG)
      ? fs
          .readFileSync(process.env.PRODUCER_TEST_LOG, 'utf8')
          .trim()
          .split('\n')
          .filter(Boolean)
          .map(JSON.parse)
      : [];
  const until = async (check) => {
    const end = performance.now() + 6000;
    while (!check()) {
      assert(performance.now() < end, 'native owner did not retire');
      await delay(10);
    }
  };
  registerWindowsTraceTransport();
  const monitor = monitorPrivateStateWorkers(
    new URL('./desktop-trace-native-worker.js', import.meta.url),
    join(directory, 'worker-events.jsonl'),
  );
  const owner = new Worker(new URL('./owner.mjs', import.meta.url), {
    execArgv: [],
    workerData: { mode, windowsProcessGate: getWindowsProcessGate() },
  });
  registerWindowsProcessWorker(owner);
  let answer, retirement;
  const completed = new Promise((resolve, reject) => {
    owner.once('message', (value) => {
      answer = value;
    });
    owner.once('error', reject);
    owner.once('exit', resolve);
  });
  try {
    if (mode === 'retire') {
      await until(() => rows().some((row) => row.event === 'request'));
      retirement = await retireWindowsNativeOwner(owner);
      assert.equal(retirement, true);
    }
    await completed;
    await until(() => monitor.active.size === 0);
    const starts = rows().filter((row) => row.event === 'started');
    assert.equal(starts.length, 1);
    for (const { pid } of starts)
      assert.throws(
        () => process.kill(pid, 0),
        (error) => error.code === 'ESRCH',
      );
    fs.writeFileSync(
      join(directory, 'result.json'),
      JSON.stringify({ answer, retirement, rows: rows(), active: monitor.active.size }),
    );
  } finally {
    await owner.terminate();
    await until(() => monitor.active.size === 0);
    monitor.restore();
  }
}

for (const mode of ['good', 'bad-reply', 'retire'])
  test(`registered producer transport: ${mode}, single service and complete native owner reap`, async (t) => {
    const fixture = await frozenBatchFixture();
    let cleanupSafe = false;
    try {
      fs.writeFileSync(
        join(fixture.directory, 'child.mjs'),
        `await (${producerService.toString()})();`,
      );
      fs.writeFileSync(
        join(fixture.directory, 'owner.mjs'),
        `await (${producerOwner.toString()})();`,
      );
      fs.writeFileSync(
        join(fixture.directory, 'main.mjs'),
        `await (${producerMain.toString()})();`,
      );
      const env = Object.fromEntries(
        Object.entries(process.env).filter(([key]) =>
          /^(PATH|SYSTEMROOT|WINDIR|TEMP|TMP|USERPROFILE|HOME|LOCALAPPDATA|APPDATA)$/i.test(key),
        ),
      );
      await run(process.execPath, [join(fixture.directory, 'main.mjs'), fixture.directory, mode], {
        env,
        timeout: 15_000,
        windowsHide: true,
      });
      const result = JSON.parse(fs.readFileSync(join(fixture.directory, 'result.json')));
      assert.equal(result.active, 0);
      if (mode === 'good') {
        assert.deepEqual(result.answer.identities, [identity, identity]);
        assert.equal(result.answer.error, undefined);
        assert.equal(result.rows.filter((row) => row.event === 'request').length, 2);
      } else {
        assert(result.answer.error);
        if (mode === 'retire') assert.equal(result.retirement, true);
      }
      assert.equal(result.rows.filter((row) => row.event === 'started').length, 1);
      assert.equal(result.rows.filter((row) => row.event === 'exited').length, 1);
      cleanupSafe = true;
    } finally {
      try {
        fixture.unchanged();
      } finally {
        if (cleanupSafe) fs.rmSync(fixture.directory, { recursive: true });
        else
          t.diagnostic(
            `Preserved failed transport fixture pending owner/process verification: ${fixture.directory}`,
          );
      }
    }
  });

// Actual Win32 process/file handles. The actors are OWNED synthetic C# exes,
// including a synthetic node.exe; this is neither a Node nor Store signature test.
const harness = String.raw`
using System;using System.IO;using System.IO.Pipes;using System.Diagnostics;
using System.Text;using System.Reflection;using System.Threading;
using System.Collections.Generic;using System.Runtime.InteropServices;
using System.Security.AccessControl;using System.Security.Principal;
using System.Security.Cryptography;using System.Web.Script.Serialization;
internal static class ProducerFixture {
 static readonly JavaScriptSerializer Json=new JavaScriptSerializer();
 static readonly SecurityIdentifier Sid=WindowsIdentity.GetCurrent().User;
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern bool CreateHardLink(string path,string existing,IntPtr reserved);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern bool CreateSymbolicLink(string path,string target,uint flags);
 [DllImport("kernel32.dll")]static extern IntPtr CreateJobObject(IntPtr security,string name);
 [DllImport("kernel32.dll")]static extern bool CloseHandle(IntPtr handle);
 [DllImport("kernel32.dll")]static extern bool SetInformationJobObject(IntPtr job,int kind,ref JobLimits data,uint size);
 [DllImport("kernel32.dll")]static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
 [StructLayout(LayoutKind.Sequential)]struct JobBasic{public long ProcessTime,JobTime;public uint Flags;public UIntPtr Minimum,Maximum;public uint Active;public UIntPtr Affinity;public uint Priority,Schedule;}
 [StructLayout(LayoutKind.Sequential)]struct IoCounters{public ulong ReadOps,WriteOps,OtherOps,ReadBytes,WriteBytes,OtherBytes;}
 [StructLayout(LayoutKind.Sequential)]struct JobLimits{public JobBasic Basic;public IoCounters Io;public UIntPtr ProcessMemory,JobMemory,PeakProcess,PeakJob;}
 sealed class Job:IDisposable{internal IntPtr Handle;internal Job(){Handle=CreateJobObject(IntPtr.Zero,null);Need(Handle!=IntPtr.Zero,"create fixture job");try{var limits=new JobLimits();limits.Basic.Flags=0x2000;Need(SetInformationJobObject(Handle,9,ref limits,(uint)Marshal.SizeOf(typeof(JobLimits))),"fixture kill-on-close job");}catch{Dispose();throw;}}public void Dispose(){if(Handle!=IntPtr.Zero){Need(CloseHandle(Handle),"close fixture job");Handle=IntPtr.Zero;}}}
 static void Need(bool ok,string why){if(!ok)throw new Exception(why);}
 static bool Same(string a,string b){return String.Equals(a,b,StringComparison.OrdinalIgnoreCase);}
 static long Started(Process p){return new DateTimeOffset(p.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds();}
 static Dictionary<string,object> Object(object value){return (Dictionary<string,object>)Json.DeserializeObject(Json.Serialize(value));}
 static string Hash(string path){using(var sha=SHA256.Create())using(var file=File.OpenRead(path))return BitConverter.ToString(sha.ComputeHash(file)).Replace("-","").ToLowerInvariant();}
 static void Private(string path,bool directory){var rule=new FileSystemAccessRule(Sid,FileSystemRights.FullControl,AccessControlType.Allow);if(directory){var acl=new DirectorySecurity();acl.SetOwner(Sid);acl.SetAccessRuleProtection(true,false);acl.AddAccessRule(rule);Directory.SetAccessControl(path,acl);}else{var acl=new FileSecurity();acl.SetOwner(Sid);acl.SetAccessRuleProtection(true,false);acl.AddAccessRule(rule);File.SetAccessControl(path,acl);}}
 static void Reject(Action action,string name){bool rejected=false;try{action();}catch(Exception error){var e=error.GetBaseException();if(!(e is IOException)&&!(e is UnauthorizedAccessException)&&!(e is ArgumentException)&&!(e is TimeoutException))throw;rejected=true;}Need(rejected,"must reject "+name);}
 static object PrivateCall(object instance,string name,params object[] args){return instance.GetType().GetMethod(name,BindingFlags.Instance|BindingFlags.NonPublic).Invoke(instance,args);}
 sealed class Owned:IDisposable{
  internal Process P;internal Dictionary<string,object> Ready;long ticks;bool launched;
  internal Owned(string path,string mode,string root,string pipe,Job job){P=new Process();P.StartInfo=new ProcessStartInfo(path,mode+" \""+root+"\" \""+pipe+"\""){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true};P.StartInfo.EnvironmentVariables["CODEX_ROLLOUT_TRACE_ROOT"]=root;
   try{Need(P.Start(),"start fixture");launched=true;var h=P.Handle;ticks=P.StartTime.ToUniversalTime().Ticks;if(job!=null)Need(AssignProcessToJobObject(job.Handle,h),"own fixture job");P.StandardInput.WriteLine("GO");P.StandardInput.Flush();var read=P.StandardOutput.ReadLineAsync();Need(read.Wait(8000)&&read.Result!=null,"fixture readiness");Ready=(Dictionary<string,object>)Json.DeserializeObject(read.Result);}catch{Dispose();throw;}}
  public void Dispose(){if(P==null)return;try{if(!launched)return;if(!P.HasExited){try{P.StandardInput.WriteLine("QUIT");P.StandardInput.Close();}catch(IOException){}if(!P.WaitForExit(8000)){Need(P.StartTime.ToUniversalTime().Ticks==ticks,"kill only owned exact fixture");P.Kill();Need(P.WaitForExit(5000),"fixture exit");}}Need(P.ExitCode==0,"fixture child failed: "+P.StandardError.ReadToEnd());}finally{P.Dispose();P=null;}}
 }
 static int Actor(string mode,string root,string pipe){if(Console.ReadLine()!="GO")return 0;Owned child=null,direct=null;NamedPipeServerStream server=null;Thread thread=null;bool stop=false;Exception pipeError=null;
  try{if(mode=="desktop"){child=new Owned(Path.Combine(root,"private","launcher.exe"),"shim",root,pipe,null);direct=new Owned(Path.Combine(root,"direct","codex.exe"),"app-server",root,pipe,null);
   server=new NamedPipeServerStream(pipe,PipeDirection.InOut,1,PipeTransmissionMode.Byte,PipeOptions.Asynchronous);thread=new Thread(()=>{try{while(!Volatile.Read(ref stop)){var pending=server.BeginWaitForConnection(null,null);while(!pending.IsCompleted&&!Volatile.Read(ref stop))Thread.Sleep(5);if(Volatile.Read(ref stop))return;server.EndWaitForConnection(pending);while(server.ReadByte()!=-1){}server.Disconnect();}}catch(Exception error){if(!Volatile.Read(ref stop))pipeError=error;}});thread.IsBackground=true;thread.Start();
  }else if(mode=="shim")child=new Owned(Path.Combine(root,"node","node.exe"),"node",root,pipe,null);
  else if(mode=="node")child=new Owned(Path.Combine(root,"cli","codex.exe"),"app-server",root,pipe,null);
  else Need(mode=="app-server","known actor");
  int producer=mode=="node"?Process.GetCurrentProcess().Id:child==null?0:Convert.ToInt32(child.Ready["producerPid"]);
  Console.WriteLine(Json.Serialize(new{producerPid=producer,started=Started(Process.GetCurrentProcess()),directPid=direct==null?0:direct.P.Id}));Console.Out.Flush();
  while(true){string input=Console.ReadLine();if(input==null||input=="QUIT")break;throw new Exception("unknown fixture input");}if(pipeError!=null)throw pipeError;return 0;
  }finally{Volatile.Write(ref stop,true);if(server!=null)server.Dispose();if(thread!=null)Need(thread.Join(3000),"fixture pipe exit");try{if(direct!=null)direct.Dispose();}finally{if(child!=null)child.Dispose();}}
 }
 static int Run(string root,string pipe){var cases=new List<string>();var report=new Dictionary<string,object>();Owned desktop=null;TraceProcess probe=null;Job job=null;
  try{AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling",false);AppContext.SetSwitch("Switch.System.IO.BlockLongPaths",false);
   string exe=Assembly.GetExecutingAssembly().Location;foreach(string directory in new[]{"private","node","cli","direct","logs"}){string path=Path.Combine(root,directory);Directory.CreateDirectory(path);Private(path,true);}
   string desktopPath=Path.Combine(root,"desktop.exe"),shim=Path.Combine(root,"private","launcher.exe"),node=Path.Combine(root,"node","node.exe"),cli=Path.Combine(root,"cli","codex.exe");
   foreach(string path in new[]{desktopPath,shim,node,cli,Path.Combine(root,"direct","codex.exe")}){File.Copy(exe,path);Private(path,false);}
   string config=Path.Combine(root,"private","config.json");var value=new Dictionary<string,object>{{"version",1},{"target",new{agentId="codex",hostId="fixture-host",workspaceId="fixture-workspace",runtimeId="fixture-runtime"}},{"traceRoot",root},{"applicationPath",desktopPath},{"executable",cli},{"node",node},{"entry",Path.Combine(root,"desktop-native-suggestions-runtime.mjs")},{"logDirectory",Path.Combine(root,"logs")},{"shimPath",shim},{"shimSha256",Hash(shim)}};
   File.WriteAllText((string)value["entry"],"// unused synthetic actor entry",new UTF8Encoding(false));Private((string)value["entry"],false);
   Action save=()=>{File.WriteAllText(config,Json.Serialize(value),new UTF8Encoding(false));Private(config,false);};save();
   job=new Job();desktop=new Owned(desktopPath,"desktop",root,pipe,job);int producer=Convert.ToInt32(desktop.Ready["producerPid"]);
   probe=new TraceProcess(desktop.P.Id,Started(desktop.P),desktopPath);
   var identity=Object(probe.InspectProducer(producer,config));Need(identity.Count==4&&Convert.ToInt32(identity["pid"])==producer&&Convert.ToInt32(identity["desktopPid"])==desktop.P.Id&&Convert.ToInt64(identity["desktopStartedAt"])==Started(desktop.P),"exact four-field identity");
   using(var p=Process.GetProcessById(producer))Need(Convert.ToInt64(identity["wrapperStartedAt"])==Started(p),"real producer start");cases.Add("known-wrapper-producer");
   var direct=Object(probe.Inspect());var executors=(object[])direct["executors"];Need(executors.Length==1&&Convert.ToInt32(((Dictionary<string,object>)executors[0])["pid"])==Convert.ToInt32(desktop.Ready["directPid"]),"direct executor compatibility");cases.Add("direct-original-compatible");
   Reject(()=>probe.InspectProducer(Convert.ToInt32(desktop.Ready["directPid"]),config),"wrong immediate parent");cases.Add("wrong-parent");
   object saved=value["node"];value["node"]=Path.Combine(root,"different","node.exe");save();Reject(()=>probe.InspectProducer(producer,config),"full node image");value["node"]=saved;save();cases.Add("wrong-node-image");
   saved=value["shimSha256"];value["shimSha256"]=new string('0',64);save();Reject(()=>probe.InspectProducer(producer,config),"shim hash");value["shimSha256"]=saved;save();cases.Add("shim-hash");
   var acl=File.GetAccessControl(config);acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier("S-1-1-0"),FileSystemRights.Read,AccessControlType.Allow));File.SetAccessControl(config,acl);try{Reject(()=>probe.InspectProducer(producer,config),"public DACL");}finally{Private(config,false);}cases.Add("public-dacl");
   string alias=Path.Combine(root,"config-hardlink.json");Need(CreateHardLink(alias,config,IntPtr.Zero),"create owned hardlink");try{Reject(()=>probe.InspectProducer(producer,config),"nlink");}finally{File.Delete(alias);}cases.Add("config-nlink");
   string original=config+".saved";File.Move(config,original);bool symbolic=false;try{symbolic=CreateSymbolicLink(config,original,2);if(!symbolic)symbolic=CreateSymbolicLink(config,original,0);if(symbolic){Reject(()=>probe.InspectProducer(producer,config),"reparse config");cases.Add("reparse-config");}else report["reparseNotRunWin32"]=Marshal.GetLastWin32Error();}finally{if(symbolic)File.Delete(config);File.Move(original,config);}
   var type=typeof(TraceProcess).GetNestedType("ProcessIdentity",BindingFlags.NonPublic);var retained=type.GetConstructor(BindingFlags.Instance|BindingFlags.NonPublic,null,new[]{typeof(int)},null).Invoke(new object[]{producer});
   try{var created=type.GetField("Created",BindingFlags.Instance|BindingFlags.NonPublic);long before=(long)created.GetValue(retained);created.SetValue(retained,before+1);Reject(()=>PrivateCall(retained,"Check"),"retained creation mismatch");created.SetValue(retained,before);PrivateCall(retained,"Check");}finally{((IDisposable)retained).Dispose();}cases.Add("retained-creation-change");
   Reject(()=>{using(var wrong=new TraceProcess(desktop.P.Id,Started(desktop.P)+1,desktopPath)){}},"original start changed");cases.Add("original-start-change");
   var wire=typeof(TraceState).GetMethod("Producer",BindingFlags.Static|BindingFlags.NonPublic);foreach(string input in new[]{"{\"kind\":\"shell\",\"ProducerPid\":1,\"Config\":\"C:\\\\config.json\"}","{\"kind\":\"producer\",\"ProducerPid\":0,\"Config\":\"C:\\\\config.json\"}","{\"kind\":\"producer\",\"ProducerPid\":1,\"Config\":\"C:\\\\config.json\",\"method\":\"eval\"}","{\"kind\":\"producer\",\"ProducerPid\":1,\"ProducerPid\":2,\"Config\":\"C:\\\\config.json\"}"}){string frame=Convert.ToBase64String(Encoding.UTF8.GetBytes(input));Reject(()=>wire.Invoke(null,new object[]{probe,frame}),"malformed fixed action");}cases.Add("native-malformed-contract");
   probe.InspectProducer(producer,config);desktop.Dispose();desktop=null;Reject(()=>probe.InspectProducer(producer,config),"exited retained original");cases.Add("retained-original-exit");report["passed"]=true;
  }catch(Exception error){report["passed"]=false;report["error"]=error.ToString();}
  finally{if(probe!=null)probe.Dispose();try{if(desktop!=null)desktop.Dispose();}finally{if(job!=null)job.Dispose();}}
  report["kind"]="OWNED_SYNTHETIC_WIN32_NOT_OFFICIAL_DESKTOP_PROOF";report["cases"]=cases;report["forcedPidReuse"]=false;report["crossSession"]="not-run: requires an authorized accessible same-user process in another session";Console.WriteLine(Json.Serialize(report));return (bool)report["passed"]?0:1;
 }
 static int CrossSession(int id,string expectedImage){using(var other=Process.GetProcessById(id))using(var current=Process.GetCurrentProcess()){
  var handle=other.Handle;Need(other.SessionId!=current.SessionId,"fixture must really have a different session");Need(Same(other.MainModule.FileName,expectedImage),"exact authorized cross-session fixture image");
  var identity=typeof(TraceProcess).GetMethod("Identity",BindingFlags.Static|BindingFlags.NonPublic,null,new[]{typeof(Process)},null);using(var user=(WindowsIdentity)identity.Invoke(null,new object[]{other}))Need(user.User.Equals(Sid),"cross-session fixture must have the same user SID");
  var type=typeof(TraceProcess).GetNestedType("ProcessIdentity",BindingFlags.NonPublic);var ctor=type.GetConstructor(BindingFlags.Instance|BindingFlags.NonPublic,null,new[]{typeof(int)},null);
  Reject(()=>{using(var unexpected=(IDisposable)ctor.Invoke(new object[]{id})){}},"same-user different session");Console.WriteLine("{\"differentSessionRejected\":true}");return 0;
 }}
 static int Main(string[] args){try{Console.OutputEncoding=new UTF8Encoding(false);Console.InputEncoding=new UTF8Encoding(false);if(args.Length==3&&args[0]=="run")return Run(args[1],args[2]);if(args.Length==3&&args[0]=="cross-session")return CrossSession(Int32.Parse(args[1]),args[2]);if(args.Length==3)return Actor(args[0],args[1],args[2]);throw new Exception("fixed fixture modes only");}catch(Exception error){Console.Error.WriteLine(error.ToString());return 1;}}
}
`;

test(
  'Windows native producer: real retained identities, parent/image/hash/DACL/nlink/reparse fences',
  {
    skip:
      process.platform !== 'win32'
        ? 'requires Windows x64 Framework/NTFS; portable tests are not native proof'
        : false,
  },
  async (t) => {
    const {
      protectWindowsStateDirectory,
      protectWindowsStateFile,
      closeWindowsPrivateStateBackend,
      windowsPowerShellEnvironment,
    } = await import('../packages/codex-adapter/dist/desktop-platform.js');
    const { withWindowsProcessCreation } =
      await import('../packages/codex-adapter/dist/windows-process.js');
    const compiler = ['Framework64', 'Framework']
      .map((name) => join(process.env.SystemRoot, 'Microsoft.NET', name, 'v4.0.30319/csc.exe'))
      .find((path) => fs.existsSync(path));
    assert(compiler, 'existing x64 Framework compiler required');
    fs.mkdirSync(join(repository, '.local'), { recursive: true });
    const directory = fs.realpathSync(fs.mkdtempSync(join(repository, '.local/producer-native-')));
    const sourcePath = join(repository, 'packages/codex-adapter/windows/trace-process.cs');
    const statePath = join(repository, 'packages/codex-adapter/windows/trace-state.cs');
    const source = fs.readFileSync(sourcePath),
      state = fs.readFileSync(statePath);
    const pipe = 'producer-native-fixture-' + randomUUID();
    const original = source.toString('utf8');
    assert.equal(original.split('"codex-ipc"').length, 2);
    const isolated = original.replace('"codex-ipc"', JSON.stringify(pipe));
    assert.equal(
      isolated.replace(JSON.stringify(pipe), '"codex-ipc"'),
      original,
      'only product substitution is the private pipe literal',
    );
    const write = (name, bytes) => {
      const path = join(directory, name);
      fs.writeFileSync(path, bytes, { flag: 'wx' });
      protectWindowsStateFile(path);
      return path;
    };
    const execute = (file, args, milliseconds) =>
      withWindowsProcessCreation(milliseconds, (timeout) =>
        spawnSync(file, args, {
          timeout,
          windowsHide: true,
          env: windowsPowerShellEnvironment(),
          maxBuffer: 1024 * 1024,
          stdio: 'pipe',
        }),
      );
    let cleanupSafe = false;
    try {
      protectWindowsStateDirectory(directory);
      const processCopy = write('trace-process.cs', isolated),
        stateCopy = write('trace-state.cs', state),
        entry = write('fixture.cs', harness);
      const executable = join(directory, 'fixture.exe');
      const compiled = execute(
        compiler,
        [
          '/nologo',
          '/platform:x64',
          '/main:ProducerFixture',
          '/r:System.Web.Extensions.dll',
          '/out:' + executable,
          processCopy,
          stateCopy,
          entry,
        ],
        30_000,
      );
      assert.equal(compiled.error, undefined);
      assert.equal(compiled.signal, null);
      assert.equal(compiled.status, 0, compiled.stderr?.toString() + compiled.stdout?.toString());
      protectWindowsStateFile(executable);
      const result = execute(executable, ['run', directory, pipe], 90_000);
      assert.equal(
        result.error,
        undefined,
        'timeout is not a native rejection or proof of cleanup',
      );
      assert.equal(result.signal, null);
      assert.equal(result.status, 0, result.stderr?.toString() + result.stdout?.toString());
      const report = JSON.parse(result.stdout.toString());
      assert.equal(report.passed, true);
      assert.equal(report.kind, 'OWNED_SYNTHETIC_WIN32_NOT_OFFICIAL_DESKTOP_PROOF');
      for (const name of [
        'known-wrapper-producer',
        'direct-original-compatible',
        'wrong-parent',
        'wrong-node-image',
        'shim-hash',
        'public-dacl',
        'config-nlink',
        'retained-creation-change',
        'original-start-change',
        'native-malformed-contract',
        'retained-original-exit',
      ])
        assert(report.cases.includes(name), name);
      if (!report.cases.includes('reparse-config'))
        t.diagnostic(
          `reparse fixture unavailable: Win32 ${report.reparseNotRunWin32}; not a passed reparse case`,
        );
      const otherPid = process.env.AH_NATIVE_PRODUCER_OTHER_SESSION_PID;
      const otherImage = process.env.AH_NATIVE_PRODUCER_OTHER_SESSION_IMAGE;
      await t.test(
        'reject a separately authorized accessible same-user process in another real Windows session',
        {
          skip:
            !otherPid || !otherImage
              ? 'provide the exact PID and image of a separately owned same-user session fixture; no global process scan'
              : false,
        },
        () => {
          assert(/^[1-9][0-9]*$/.test(otherPid));
          assert(Number(otherPid) <= 2147483647);
          const result = execute(executable, ['cross-session', otherPid, otherImage], 15_000);
          assert.equal(result.error, undefined);
          assert.equal(result.signal, null);
          assert.equal(result.status, 0, result.stderr?.toString());
          assert.deepEqual(JSON.parse(result.stdout.toString()), {
            differentSessionRejected: true,
          });
        },
      );
      t.diagnostic(
        'Forced OS PID reuse, official wrapped Store CLI and real desktop/phone acceptance remain unverified.',
      );
      assert.deepEqual(fs.readFileSync(sourcePath), source);
      assert.deepEqual(fs.readFileSync(statePath), state);
      t.diagnostic(
        JSON.stringify({
          processSHA256: digest(source),
          stateSHA256: digest(state),
          harnessSHA256: digest(harness),
          cases: report.cases,
        }),
      );
      cleanupSafe = true;
    } finally {
      closeWindowsPrivateStateBackend();
      // A failed/timed-out native run leaves its precise fixture for ownership and
      // process/job verification; never delete a possibly live executable tree.
      if (cleanupSafe) fs.rmSync(directory, { recursive: true });
      else
        t.diagnostic(`Preserved failed native fixture pending resource verification: ${directory}`);
    }
  },
);
