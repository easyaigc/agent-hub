import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import fs from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const repository = fileURLToPath(new URL('../', import.meta.url));
const processPath = join(repository, 'packages/codex-adapter/windows/trace-process.cs');
const sourceSHA256 = 'd1966565695c9cacaab8d2139478e7ec85ab1856456f16f2dd93936abdcd2366';
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');

function isolatedSource(bytes, pipe) {
  assert.equal(digest(bytes), sourceSHA256, 'Review and re-pin any actual process guard change');
  assert.match(pipe, /^trace-process-fixture-[a-f0-9-]{36}$/);
  const source = bytes.toString('utf8');
  assert.deepEqual(Buffer.from(source), bytes);
  assert.equal(source.split('"codex-ipc"').length, 2, 'Exactly one fixed pipe name');
  const copy = source.replace('"codex-ipc"', `"${pipe}"`);
  assert.equal(copy.split(`"${pipe}"`).length, 2);
  assert.deepEqual(Buffer.from(copy.replace(`"${pipe}"`, '"codex-ipc"')), bytes);
  return copy;
}

// Real Win32 calls and the actual TraceProcess, without replacing a syscall or
// guard. All executable modes below belong to this private synthetic fixture.
const harness = String.raw`
using System;using System.IO;using System.IO.Pipes;using System.Text;
using System.Diagnostics;using System.Reflection;using System.Threading;
using System.Collections.Generic;using System.Runtime.InteropServices;
using System.Security.AccessControl;using System.Security.Principal;
using System.Web.Script.Serialization;

internal static class ProcessFixture {
 const uint QueryRead=0x0410,ReadControl=0x00020000,WriteDac=0x00040000;
 const string RootVariable="CODEX_ROLLOUT_TRACE_ROOT";
 static readonly JavaScriptSerializer Json=new JavaScriptSerializer();
 static readonly byte[] Witness=Encoding.ASCII.GetBytes("owned-vm-witness");
 [DllImport("kernel32.dll",SetLastError=true)]static extern IntPtr OpenProcess(uint access,bool inherit,uint pid);
 [DllImport("kernel32.dll",SetLastError=true)]static extern bool CloseHandle(IntPtr handle);
 [DllImport("kernel32.dll")]static extern IntPtr GetCurrentProcess();
 [DllImport("kernel32.dll",SetLastError=true)]static extern bool GetProcessTimes(IntPtr process,out long created,out long exited,out long kernel,out long user);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,ExactSpelling=true,SetLastError=true)]static extern bool QueryFullProcessImageNameW(IntPtr process,uint flags,[Out]char[] path,ref uint length);
 [DllImport("kernel32.dll",SetLastError=true)]static extern bool ReadProcessMemory(IntPtr process,IntPtr address,byte[] bytes,UIntPtr size,out UIntPtr read);
 [DllImport("kernel32.dll",SetLastError=true)]static extern bool GetNamedPipeServerProcessId(Microsoft.Win32.SafeHandles.SafePipeHandle pipe,out uint pid);
 [DllImport("advapi32.dll",SetLastError=true)]static extern bool OpenProcessToken(IntPtr process,uint access,out IntPtr token);
 [DllImport("advapi32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern bool LookupPrivilegeValue(string system,string name,out Luid value);
 [DllImport("advapi32.dll",SetLastError=true)]static extern bool GetTokenInformation(IntPtr token,int kind,IntPtr data,int size,out int returned);
 [DllImport("advapi32.dll",SetLastError=true)]static extern bool AdjustTokenPrivileges(IntPtr token,bool all,ref Privilege state,int length,IntPtr previous,IntPtr returned);
 [DllImport("advapi32.dll")]static extern uint GetSecurityInfo(IntPtr handle,int kind,uint info,out IntPtr owner,out IntPtr group,out IntPtr dacl,out IntPtr sacl,out IntPtr descriptor);
 [DllImport("advapi32.dll")]static extern uint SetSecurityInfo(IntPtr handle,int kind,uint info,IntPtr owner,IntPtr group,IntPtr dacl,IntPtr sacl);
 [DllImport("advapi32.dll")]static extern uint GetSecurityDescriptorLength(IntPtr descriptor);
 [DllImport("kernel32.dll")]static extern IntPtr LocalFree(IntPtr memory);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern IntPtr CreateJobObject(IntPtr security,string name);
 [DllImport("kernel32.dll",SetLastError=true)]static extern bool SetInformationJobObject(IntPtr job,int kind,ref JobLimits data,uint size);
 [DllImport("kernel32.dll",SetLastError=true)]static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
 [DllImport("kernel32.dll",SetLastError=true)]static extern bool IsProcessInJob(IntPtr process,IntPtr job,out bool answer);
 [StructLayout(LayoutKind.Sequential)]struct Luid {public uint Low;public int High;}
 [StructLayout(LayoutKind.Sequential)]struct Privilege {public uint Count;public Luid Id;public uint Attributes;}
 [StructLayout(LayoutKind.Sequential)]struct JobBasic {public long ProcessTime,JobTime;public uint Flags;public UIntPtr Minimum,Maximum;public uint Active;public UIntPtr Affinity;public uint Priority,Schedule;}
 [StructLayout(LayoutKind.Sequential)]struct IoCounters {public ulong ReadOps,WriteOps,OtherOps,ReadBytes,WriteBytes,OtherBytes;}
 [StructLayout(LayoutKind.Sequential)]struct JobLimits {public JobBasic Basic;public IoCounters Io;public UIntPtr ProcessMemory,JobMemory,PeakProcess,PeakJob;}

 static void Require(bool value,string message){if(!value)throw new Exception(message);}
 static void Win(bool value,string message){if(!value)throw new Exception(message+" (Win32 "+Marshal.GetLastWin32Error()+")");}
 static long Created(IntPtr process){long a,b,c,d;Win(GetProcessTimes(process,out a,out b,out c,out d),"GetProcessTimes");return a;}
 static long Milliseconds(long created){return new DateTimeOffset(DateTime.FromFileTimeUtc(created)).ToUnixTimeMilliseconds();}
 static string Image(IntPtr process){var path=new char[32768];uint n=(uint)path.Length;Win(QueryFullProcessImageNameW(process,0,path,ref n),"Independent image query");Require(n>0&&n<path.Length,"Independent image length");return new string(path,0,(int)n);}
 static bool Same(string a,string b){return String.Equals(a,b,StringComparison.OrdinalIgnoreCase);}
 static SecurityIdentifier CallerSid(){using(var identity=WindowsIdentity.GetCurrent())return identity.User;}
 static Dictionary<string,object> Record(object value){return (Dictionary<string,object>)Json.DeserializeObject(Json.Serialize(value));}
 static string Encode(string value){return Convert.ToBase64String(Encoding.UTF8.GetBytes(value));}
 static string Decode(string value){return Encoding.UTF8.GetString(Convert.FromBase64String(value));}
 static bool Equal(byte[] a,byte[] b){if(a.Length!=b.Length)return false;for(int i=0;i<a.Length;i++)if(a[i]!=b[i])return false;return true;}
 static void ReadWitness(IntPtr process,long address){var bytes=new byte[Witness.Length];UIntPtr read;Win(ReadProcessMemory(process,new IntPtr(address),bytes,(UIntPtr)bytes.Length,out read),"Retained VM_READ witness");Require(read.ToUInt64()==(ulong)bytes.Length&&Equal(bytes,Witness),"Owned witness mismatch");}
 static object Private(TraceProcess probe,string method){return typeof(TraceProcess).GetMethod(method,BindingFlags.Instance|BindingFlags.NonPublic).Invoke(probe,null);}
 static void Reject(Action action,string name){bool rejected=false;try{action();}catch(IOException){rejected=true;}catch(TargetInvocationException error){if(!(error.InnerException is IOException))throw;rejected=true;}Require(rejected,name+" must reject with actual process IOException");}
 static uint PipeOwner(string pipe){using(var client=new NamedPipeClientStream(".",pipe,PipeDirection.InOut)){client.Connect(2000);uint pid;Win(GetNamedPipeServerProcessId(client.SafePipeHandle,out pid),"Independent pipe owner");return pid;}}

 sealed class Job:IDisposable {
  public IntPtr Handle;
  public Job(){Handle=CreateJobObject(IntPtr.Zero,null);Win(Handle!=IntPtr.Zero,"Create private job");try{var limits=new JobLimits();limits.Basic.Flags=0x2000;Win(SetInformationJobObject(Handle,9,ref limits,(uint)Marshal.SizeOf(typeof(JobLimits))),"Kill-on-close job");}catch{CloseHandle(Handle);Handle=IntPtr.Zero;throw;}}
  public void Assign(Process process){Win(AssignProcessToJobObject(Handle,process.Handle),"Assign owned desktop before GO");bool member;Win(IsProcessInJob(process.Handle,Handle,out member),"Check owned job");Require(member,"Owned desktop must join fixture job");}
  public void Dispose(){if(Handle!=IntPtr.Zero){Win(CloseHandle(Handle),"Close private job");Handle=IntPtr.Zero;}}
 }
 sealed class Owned:IDisposable {
  public Process Value;public long Creation;public int Pid;public Dictionary<string,object> Ready;bool stopped;
  public static Owned Start(string path,string arguments,string root,Job job,bool go){
   var owned=new Owned();owned.Value=new Process();owned.Value.StartInfo=new ProcessStartInfo(path,arguments){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=Path.GetDirectoryName(path)};
   owned.Value.StartInfo.EnvironmentVariables[RootVariable]=root;
   try{Require(owned.Value.Start(),"Start owned fixture");var retained=owned.Value.Handle;owned.Pid=owned.Value.Id;owned.Creation=Created(retained);Require(Same(Image(retained),path),"Created fixture executable identity");
    if(job!=null)job.Assign(owned.Value);
    if(go){owned.Value.StandardInput.WriteLine("GO");owned.Value.StandardInput.Flush();}
    var line=owned.Value.StandardOutput.ReadLineAsync();Require(line.Wait(8000),"Owned readiness timeout");Require(line.Result!=null,"Owned fixture exited before ready");owned.Ready=(Dictionary<string,object>)Json.DeserializeObject(line.Result);Require((string)owned.Ready["kind"]=="ready","Owned readiness frame");return owned;
   }catch{owned.Dispose();throw;}
  }
  public void Dispose(){if(stopped)return;try{
   if(!Value.HasExited){try{Value.StandardInput.WriteLine("QUIT");Value.StandardInput.Close();}catch(IOException){}catch(InvalidOperationException){}
    if(!Value.WaitForExit(5000)){Require(Creation!=0&&Created(Value.Handle)==Creation,"Only retained created instance may be stopped");Value.Kill();Require(Value.WaitForExit(5000),"Owned process did not retire");}}
   Require(Value.HasExited,"Owned process still alive");Require(Value.ExitCode==0,"Owned fixture failure: "+Value.StandardError.ReadToEnd());
  }finally{Value.Dispose();stopped=true;}}
 }

 static uint? DebugAttributes(IntPtr token,Luid id){int size;GetTokenInformation(token,3,IntPtr.Zero,0,out size);Require(size>=4&&size<=65536,"Token privilege buffer");IntPtr data=Marshal.AllocHGlobal(size);try{Win(GetTokenInformation(token,3,data,size,out size),"Read own token privileges");int count=Marshal.ReadInt32(data);Require(count>=0&&count<=(size-4)/12,"Token privilege count");for(int i=0;i<count;i++){int at=4+i*12;if((uint)Marshal.ReadInt32(data,at)==id.Low&&Marshal.ReadInt32(data,at+4)==id.High)return (uint)Marshal.ReadInt32(data,at+8);}return null;}finally{Marshal.FreeHGlobal(data);}}
 static object DisableOwnDebug(){IntPtr token;Win(OpenProcessToken(GetCurrentProcess(),0x28,out token),"Open only harness child token");try{Luid id;Win(LookupPrivilegeValue(null,"SeDebugPrivilege",out id),"Lookup debug privilege");uint? before=DebugAttributes(token,id);if(before.HasValue){var state=new Privilege{Count=1,Id=id,Attributes=0};Win(AdjustTokenPrivileges(token,false,ref state,0,IntPtr.Zero,IntPtr.Zero),"Disable own SeDebugPrivilege");Require(Marshal.GetLastWin32Error()==0,"Debug privilege not fully assigned");}uint? after=DebugAttributes(token,id);Require(!after.HasValue||(after.Value&2)==0,"Own SeDebugPrivilege must be disabled");return new{present=before.HasValue,beforeEnabled=before.HasValue&&(before.Value&2)!=0,afterEnabled=after.HasValue&&(after.Value&2)!=0};}finally{CloseHandle(token);}}

 static byte[] DaclBytes(IntPtr process){IntPtr owner,group,dacl,sacl,sd;uint code=GetSecurityInfo(process,6,4,out owner,out group,out dacl,out sacl,out sd);Require(code==0,"Read owned process DACL: "+code);try{Require(dacl!=IntPtr.Zero,"Fixture requires nonnull original DACL");var bytes=new byte[GetSecurityDescriptorLength(sd)];Marshal.Copy(sd,bytes,0,bytes.Length);var acl=new RawSecurityDescriptor(bytes,0).DiscretionaryAcl;var result=new byte[acl.BinaryLength];acl.GetBinaryForm(result,0);return result;}finally{LocalFree(sd);}}
 static void FreshAllowed(int pid){IntPtr handle=OpenProcess(QueryRead,false,(uint)pid);Win(handle!=IntPtr.Zero,"Baseline fresh QUERY_INFORMATION|VM_READ");CloseHandle(handle);}
 static object DenyFresh(TraceProcess probe,Owned desktop,string root){
  var retained=(Process)typeof(TraceProcess).GetField("original",BindingFlags.Instance|BindingFlags.NonPublic).GetValue(probe);
  var privilegeAtDeny=DisableOwnDebug();long witness=Convert.ToInt64(desktop.Ready["witness"]);FreshAllowed(desktop.Pid);ReadWitness(retained.Handle,witness);
  IntPtr restore=OpenProcess(ReadControl|WriteDac|QueryRead,false,(uint)desktop.Pid);Win(restore!=IntPtr.Zero,"Retain owned DACL restore handle");
  IntPtr changed=IntPtr.Zero;bool mutation=false,restored=false;int deniedError=0;byte[] original=null;
  try{
   Require(Created(restore)==desktop.Creation,"Restore handle original identity");original=DaclBytes(restore);var acl=new RawAcl(original,0);acl.InsertAce(0,new CommonAce(AceFlags.None,AceQualifier.AccessDenied,0x10,CallerSid(),false,null));
   var bytes=new byte[acl.BinaryLength];acl.GetBinaryForm(bytes,0);changed=Marshal.AllocHGlobal(bytes.Length);Marshal.Copy(bytes,0,changed,bytes.Length);
   uint code=SetSecurityInfo(restore,6,4,IntPtr.Zero,IntPtr.Zero,changed,IntPtr.Zero);Require(code==0,"Deny only owned fake VM_READ: "+code);mutation=true;
   IntPtr denied=OpenProcess(QueryRead,false,(uint)desktop.Pid);deniedError=Marshal.GetLastWin32Error();if(denied!=IntPtr.Zero)CloseHandle(denied);Require(denied==IntPtr.Zero&&deniedError==5,"Real fresh open must fail ACCESS_DENIED; no synthetic success");
   ReadWitness(retained.Handle,witness);Require(Same(Image(retained.Handle),Image(restore)),"Retained creation image still readable");Require(Created(retained.Handle)==desktop.Creation,"Retained original still alive");
   Reject(()=>Private(probe,"Image"),"Fresh image authority");Reject(()=>probe.Inspect(),"Inspect after own DACL deny");
  }finally{
   try{if(mutation){IntPtr saved=Marshal.AllocHGlobal(original.Length);try{Marshal.Copy(original,0,saved,original.Length);uint code=SetSecurityInfo(restore,6,4,IntPtr.Zero,IntPtr.Zero,saved,IntPtr.Zero);Require(code==0,"Restore original owned DACL: "+code);Require(Equal(DaclBytes(restore),original),"Restored DACL bytes must match");restored=true;}finally{Marshal.FreeHGlobal(saved);}}}
   finally{if(changed!=IntPtr.Zero)Marshal.FreeHGlobal(changed);CloseHandle(restore);}
  }
  Require(restored,"Owned DACL restored");FreshAllowed(desktop.Pid);Positive(probe,desktop,root);
  return new{baselineFreshOpen=true,baselineVmRead=true,debugPrivilege=privilegeAtDeny,freshOpenError=deniedError,retainedVmRead=true,retainedImage=true,restored=restored,recovered=true};
 }

 static object Positive(TraceProcess probe,Owned desktop,string root){
  var record=Record(probe.Inspect());Require(Convert.ToInt32(record["pid"])==desktop.Pid,"Probe original PID");Require(Convert.ToInt64(record["startedAtUnixMs"])==Milliseconds(desktop.Creation),"Probe original creation");Require(Same((string)record["traceRoot"],root),"Real original PEB environment root");
  var children=(object[])record["executors"];Require(children.Length==1,"Exactly one owned executor");var child=(Dictionary<string,object>)children[0];Require(Convert.ToInt32(child["pid"])==Convert.ToInt32(desktop.Ready["childPid"]),"Actual child identity");Require(Convert.ToInt32(child["parentPid"])==desktop.Pid,"Actual child parent");Require(Convert.ToInt64(child["startedAtUnixMs"])==Milliseconds(Convert.ToInt64(desktop.Ready["childCreated"])),"Actual child creation");Require(Same((string)child["traceRoot"],root),"Real child PEB environment root");
  string os=Image(desktop.Value.Handle),module=desktop.Value.MainModule.FileName;Require(Same(os,module),"Controlled fixture only: OS image and Module.FileName");Require(Same((string)Private(probe,"Image"),os),"Actual candidate image query");
  return new{pid=desktop.Pid,creation=desktop.Creation.ToString(),executorPid=child["pid"],session=desktop.Value.SessionId,callerSid=CallerSid().Value,osImage=os,moduleImage=module,scope="OWNED_FAKE_ONLY_NOT_GLOBAL_EQUIVALENCE"};
 }

 static int Run(string root,string pipe){
  var report=new Dictionary<string,object>();var cases=new List<object>();var cleanup=new List<string>();var owned=new List<Owned>();Job job=null;TraceProcess probe=null;bool passed=false;
  report["kind"]="SYNTHETIC_NATIVE_PROCESS_AUTHORITY_NOT_PRODUCT_PROOF";report["cases"]=cases;
  try{
   Require(IntPtr.Size==8,"x64 Framework required");Require(Same(Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location),root),"Harness must run inside its own private root");Require(pipe.StartsWith("trace-process-fixture-",StringComparison.Ordinal),"Private test pipe only");
   // Framework ProcessManager can enable SeDebug in its static initializer.
   // Initialize it first, then disable/re-read only this new harness token.
   using(var self=Process.GetCurrentProcess())Require(Same(self.MainModule.FileName,Assembly.GetExecutingAssembly().Location),"Initialize own Framework process manager");
   report["debugPrivilege"]=DisableOwnDebug();job=new Job();
   string path=Path.Combine(root,"owned-a","desktop.exe"),wrong=Path.Combine(root,"owned-b","desktop.exe"),executor=Path.Combine(root,"executor","codex.exe");
   var desktop=Owned.Start(path,"desktop "+Encode(pipe)+" "+Encode(root)+" "+Encode(executor),root,job,true);owned.Add(desktop);
   Require(PipeOwner(pipe)==desktop.Pid,"Real pipe owner positive baseline");
   probe=new TraceProcess(desktop.Pid,Milliseconds(desktop.Creation),path);cases.Add(new{name="positive",passed=true,metadata=Positive(probe,desktop,root)});
   Require(path.Length==wrong.Length&&Path.GetFileName(path)==Path.GetFileName(wrong)&&File.Exists(wrong),"Same basename different full path and same buffer length");
   Reject(()=>{using(var unexpected=new TraceProcess(desktop.Pid,Milliseconds(desktop.Creation),wrong)){}},"Same basename wrong full path");Positive(probe,desktop,root);cases.Add(new{name="wrong-fullpath",passed=true});
   Reject(()=>{using(var unexpected=new TraceProcess(desktop.Pid,Milliseconds(desktop.Creation)+1,path)){}},"Wrong creation");Positive(probe,desktop,root);cases.Add(new{name="wrong-creation",passed=true});
   var decoy=Owned.Start(wrong,"desktop "+Encode("-")+" "+Encode(root)+" "+Encode(executor),root,job,true);owned.Add(decoy);Require(PipeOwner(pipe)==desktop.Pid&&desktop.Pid!=decoy.Pid,"Actual pipe owner differs from expected own decoy");
   Reject(()=>{using(var unexpected=new TraceProcess(decoy.Pid,Milliseconds(decoy.Creation),wrong)){}},"Wrong pipe owner");Positive(probe,desktop,root);cases.Add(new{name="wrong-pipe-owner",passed=true});
   cases.Add(new{name="fresh-open-denied",passed=true,metadata=DenyFresh(probe,desktop,root)});
   desktop.Dispose();
   // Inspect opens a pipe first: its timeout alone cannot prove HasExited ran.
   Reject(()=>Private(probe,"Check"),"Retained original exited Check");bool inspectRejected=false;try{probe.Inspect();}catch(IOException){inspectRejected=true;}catch(TimeoutException){inspectRejected=true;}Require(inspectRejected,"Exited original Inspect must reject");cases.Add(new{name="original-exited",passed=true,actualCheck=true,inspectRejected=inspectRejected});passed=true;
  }catch(Exception error){report["error"]=error.ToString();}
  finally{
   if(probe!=null)try{probe.Dispose();}catch(Exception e){cleanup.Add(e.ToString());}
   for(int i=owned.Count-1;i>=0;i--)try{owned[i].Dispose();}catch(Exception e){cleanup.Add(e.ToString());}
   if(job!=null)try{job.Dispose();}catch(Exception e){cleanup.Add(e.ToString());}
  }
  report["cleanupErrors"]=cleanup;report["passed"]=passed&&cleanup.Count==0;Console.WriteLine(Json.Serialize(report));return passed&&cleanup.Count==0?0:1;
 }

 static int Desktop(string pipe,string root,string executor){
  // The parent assigns this exact created Process to its private job before GO.
  // EOF before GO means no executor or server can be orphaned in the start gap.
  if(Console.ReadLine()!="GO")return 0;Owned child=null;NamedPipeServerStream server=null;Thread thread=null;IntPtr witness=IntPtr.Zero;Exception pipeFailure=null;bool stopping=false;
  try{
   child=Owned.Start(executor,"app-server",root,null,false);witness=Marshal.AllocHGlobal(Witness.Length);Marshal.Copy(Witness,0,witness,Witness.Length);
   if(pipe!="-"){server=new NamedPipeServerStream(pipe,PipeDirection.InOut,1,PipeTransmissionMode.Byte,PipeOptions.Asynchronous);thread=new Thread(()=>{try{while(!Volatile.Read(ref stopping)){var waiting=server.BeginWaitForConnection(null,null);while(!waiting.IsCompleted&&!Volatile.Read(ref stopping))Thread.Sleep(5);if(Volatile.Read(ref stopping))return;server.EndWaitForConnection(waiting);while(server.ReadByte()!=-1){}server.Disconnect();}}catch(Exception e){if(!Volatile.Read(ref stopping))pipeFailure=e;}});thread.IsBackground=true;thread.Start();}
   Console.WriteLine(Json.Serialize(new{kind="ready",childPid=child.Pid,childCreated=child.Creation.ToString(),witness=witness.ToInt64()}));Console.Out.Flush();
   while(true){string line=Console.ReadLine();if(line==null||line=="QUIT")break;throw new Exception("Unknown fixture command");}
   if(pipeFailure!=null)throw new Exception("Own pipe server failed",pipeFailure);return 0;
  }finally{Volatile.Write(ref stopping,true);try{if(server!=null)server.Dispose();if(thread!=null)Require(thread.Join(3000),"Own pipe thread did not retire");}finally{try{if(child!=null)child.Dispose();}finally{if(witness!=IntPtr.Zero)Marshal.FreeHGlobal(witness);}}}
 }
 static int Main(string[] args){try{
  Console.OutputEncoding=new UTF8Encoding(false);Console.InputEncoding=new UTF8Encoding(false);
  if(args.Length==3&&args[0]=="harness")return Run(Path.GetFullPath(args[1]),args[2]);
  if(args.Length==4&&args[0]=="desktop")return Desktop(Decode(args[1]),Decode(args[2]),Decode(args[3]));
  if(args.Length==1&&args[0]=="app-server"){Console.WriteLine("{\"kind\":\"ready\"}");Console.Out.Flush();while(true){string line=Console.ReadLine();if(line==null||line=="QUIT")return 0;throw new Exception("Unknown executor command");}}
  throw new Exception("Unknown fixture mode");
 }catch(Exception error){Console.Error.WriteLine(error.ToString());return 1;}}
}
`;

test('process native fixture pins actual source and changes only its private pipe name', () => {
  isolatedSource(fs.readFileSync(processPath), 'trace-process-fixture-' + randomUUID());
});

test(
  'Windows actual process probe rejects image, creation, pipe, exit and fresh-open authority changes',
  {
    skip:
      process.platform !== 'win32'
        ? 'skipNative: requires real Windows x64 Framework/Win32'
        : false,
  },
  async (t) => {
    const {
      protectWindowsStateDirectory: protectDirectory,
      protectWindowsStateFile: protectFile,
      windowsPowerShellEnvironment,
      closeWindowsPrivateStateBackend,
    } = await import('../packages/codex-adapter/dist/desktop-platform.js');
    const { withWindowsProcessCreation } =
      await import('../packages/codex-adapter/dist/windows-process.js');
    const source = fs.readFileSync(processPath);
    const pipe = 'trace-process-fixture-' + randomUUID();
    const compiler = ['Framework64', 'Framework']
      .map((name) => join(process.env.SystemRoot, 'Microsoft.NET', name, 'v4.0.30319/csc.exe'))
      .find((path) => fs.existsSync(path));
    assert(compiler, 'Existing Framework compiler required; no installation/fallback');
    const base = join(repository, '.local');
    fs.mkdirSync(base, { recursive: true, mode: 0o700 });
    const directory = fs.realpathSync(fs.mkdtempSync(join(base, 'trace-process-native-')));
    t.diagnostic(`Owned synthetic fixture artifacts preserved: ${directory}`);
    const write = (name, bytes) => {
      const path = join(directory, name);
      fs.writeFileSync(path, bytes, { flag: 'wx', mode: 0o600 });
      protectFile(path);
      return path;
    };
    const run = (executable, args, timeout) =>
      withWindowsProcessCreation(timeout, (budget) =>
        spawnSync(executable, args, {
          timeout: budget,
          windowsHide: true,
          env: windowsPowerShellEnvironment(),
          stdio: 'pipe',
          maxBuffer: 4 * 1024 * 1024,
        }),
      );
    try {
      protectDirectory(directory);
      const copy = write('trace-process.cs', isolatedSource(source, pipe));
      const entry = write('fixture.cs', harness);
      write(
        'inputs.json',
        JSON.stringify({
          kind: 'SYNTHETIC_NATIVE_PROCESS_AUTHORITY_NOT_PRODUCT_PROOF',
          sourceSHA256,
          isolatedSHA256: digest(fs.readFileSync(copy)),
          harnessSHA256: digest(harness),
          onlyProductSubstitution: 'one fixed pipe literal',
          pipe,
        }),
      );
      const executable = join(directory, 'fixture.exe');
      const compiled = run(
        compiler,
        [
          '/nologo',
          '/platform:x64',
          '/optimize-',
          '/r:System.Web.Extensions.dll',
          '/out:' + executable,
          entry,
          copy,
        ],
        30000,
      );
      write('compile.stdout', compiled.stdout ?? Buffer.alloc(0));
      write('compile.stderr', compiled.stderr ?? Buffer.alloc(0));
      assert.equal(compiled.error, undefined, 'Compiler infrastructure failure');
      assert.equal(compiled.signal, null);
      assert.equal(compiled.status, 0, 'Compile the actual process source plus owned harness');
      protectFile(executable);
      for (const [folder, file] of [
        ['owned-a', 'desktop.exe'],
        ['owned-b', 'desktop.exe'],
        ['executor', 'codex.exe'],
      ]) {
        const path = join(directory, folder);
        fs.mkdirSync(path, { mode: 0o700 });
        protectDirectory(path);
        fs.copyFileSync(executable, join(path, file), fs.constants.COPYFILE_EXCL);
        protectFile(join(path, file));
      }
      const result = run(executable, ['harness', directory, pipe], 90000);
      write('native.stdout', result.stdout ?? Buffer.alloc(0));
      write('native.stderr', result.stderr ?? Buffer.alloc(0));
      write(
        'native-exit.json',
        JSON.stringify({ status: result.status, signal: result.signal, error: result.error?.code }),
      );
      assert.equal(
        result.error,
        undefined,
        'Harness infrastructure failure; never native rejection',
      );
      assert.equal(result.signal, null);
      assert.equal(result.status, 0, 'Owned native authority scenarios and cleanup must pass');
      const report = JSON.parse(result.stdout.toString('utf8'));
      assert.equal(report.kind, 'SYNTHETIC_NATIVE_PROCESS_AUTHORITY_NOT_PRODUCT_PROOF');
      assert.equal(report.passed, true);
      assert.deepEqual(report.cleanupErrors, []);
      assert.equal(report.debugPrivilege.afterEnabled, false);
      assert.deepEqual(
        report.cases.map((item) => [item.name, item.passed]),
        [
          'positive',
          'wrong-fullpath',
          'wrong-creation',
          'wrong-pipe-owner',
          'fresh-open-denied',
          'original-exited',
        ].map((name) => [name, true]),
      );
      const denied = report.cases.find((item) => item.name === 'fresh-open-denied').metadata;
      for (const field of [
        'baselineFreshOpen',
        'baselineVmRead',
        'retainedVmRead',
        'retainedImage',
        'restored',
        'recovered',
      ])
        assert.equal(denied[field], true, field);
      assert.equal(denied.freshOpenError, 5);
      assert.equal(denied.debugPrivilege.afterEnabled, false);
      assert.deepEqual(fs.readFileSync(processPath), source, 'Actual process source drift');
    } finally {
      closeWindowsPrivateStateBackend();
    }
  },
);
