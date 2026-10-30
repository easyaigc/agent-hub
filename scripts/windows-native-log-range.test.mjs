// Windows-only, owned NTFS fixtures. This is not Electron/phone/model proof.
// Merely importing this file on another platform does not compile or run native code.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import fs from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const repository = fileURLToPath(new URL('../', import.meta.url));
const digest = (bytes) => createHash('sha256').update(bytes).digest('hex');
const maximum = 1048576;
const metadataMaximum = 16384;

// No substituted product syscalls or mutation hooks. Retain the actual native
// reader, mutate only this fixture's files between its genuine handle checks,
// and exercise the exact fixed CLI handler as well as its native entry point.
const harness = String.raw`
using System;using System.IO;using System.Text;using System.Reflection;
using System.Threading;using System.Diagnostics;using System.Collections.Generic;
using System.Runtime.InteropServices;using System.Security.AccessControl;
using System.Security.Principal;using System.Web.Script.Serialization;
using Microsoft.Win32.SafeHandles;
internal static class LogRangeFixture {
 const int Maximum=1048576;
 static readonly SecurityIdentifier Sid=WindowsIdentity.GetCurrent().User,Everyone=new SecurityIdentifier("S-1-1-0");
 static readonly JavaScriptSerializer Json=new JavaScriptSerializer {MaxJsonLength=4*1024*1024,RecursionLimit=16};
 static readonly List<string> Cases=new List<string>(),Skipped=new List<string>();
 static readonly MethodInfo Command=typeof(TraceState).GetMethod("LogSnapshotCommand",BindingFlags.Static|BindingFlags.NonPublic);
 static readonly MethodInfo PrivateSnapshot=typeof(TraceState).GetMethod("Snapshot",BindingFlags.Static|BindingFlags.NonPublic);
 [DllImport("userenv.dll",CharSet=CharSet.Unicode,ExactSpelling=true)]static extern int DeriveAppContainerSidFromAppContainerName(string name,out IntPtr sid);
 [DllImport("advapi32.dll")]static extern IntPtr FreeSid(IntPtr sid);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern bool CreateHardLink(string path,string existing,IntPtr reserved);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern bool CreateSymbolicLink(string path,string target,uint flags);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern uint GetShortPathName(string path,StringBuilder output,uint count);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern SafeFileHandle CreateFile(string path,uint access,uint share,IntPtr security,uint creation,uint flags,IntPtr template);
 [DllImport("kernel32.dll",SetLastError=true)]static extern bool DeviceIoControl(SafeFileHandle file,uint code,byte[] input,int inputBytes,IntPtr output,int outputBytes,out int returned,IntPtr overlapped);
 [StructLayout(LayoutKind.Sequential)]struct IoCounters {public ulong ReadOps,WriteOps,OtherOps,ReadBytes,WriteBytes,OtherBytes;}
 [DllImport("kernel32.dll",SetLastError=true)]static extern bool GetProcessIoCounters(IntPtr process,out IoCounters counters);
 static void Need(bool value,string message){if(!value)throw new Exception(message);}
 static bool Equal(byte[] a,byte[] b){if(a.Length!=b.Length)return false;for(int i=0;i<a.Length;i++)if(a[i]!=b[i])return false;return true;}
 static Dictionary<string,object> Object(string text){return (Dictionary<string,object>)Json.DeserializeObject(text);}
 static byte[] Bytes(Dictionary<string,object> value){return Convert.FromBase64String((string)value["bytes"]);}
 static long Size(Dictionary<string,object> value){return Convert.ToInt64(value["size"]);}
 static byte[] Pattern(long offset,int count){var bytes=new byte[count];for(int i=0;i<count;i++)bytes[i]=(byte)((offset+i)%251);return bytes;}
 static void Owner(string path,bool directory,SecurityIdentifier owner){
  if(directory){var acl=Directory.GetAccessControl(path);acl.SetOwner(owner);Directory.SetAccessControl(path,acl);}
  else{var acl=File.GetAccessControl(path);acl.SetOwner(owner);File.SetAccessControl(path,acl);}
 }
 static void Private(string path,bool directory){
  // Owned fixture descendants must inherit the user's control before adding
  // the vendor-style Everyone read grant. Otherwise elevated default owners
  // leave children with only Everyone read, failing setup before any case.
  var rule=directory
   ? new FileSystemAccessRule(Sid,FileSystemRights.FullControl,InheritanceFlags.ContainerInherit|InheritanceFlags.ObjectInherit,PropagationFlags.None,AccessControlType.Allow)
   : new FileSystemAccessRule(Sid,FileSystemRights.FullControl,AccessControlType.Allow);
  if(directory){var acl=new DirectorySecurity();acl.SetOwner(Sid);acl.SetAccessRuleProtection(true,false);acl.AddAccessRule(rule);Directory.SetAccessControl(path,acl);}
  else{var acl=new FileSecurity();acl.SetOwner(Sid);acl.SetAccessRuleProtection(true,false);acl.AddAccessRule(rule);File.SetAccessControl(path,acl);}
 }
 static void Fill(string path,int count){using(var file=new FileStream(path,FileMode.Create,FileAccess.Write,FileShare.ReadWrite|FileShare.Delete)){
  int at=0;while(at<count){var bytes=Pattern(at,Math.Min(16384,count-at));file.Write(bytes,0,bytes.Length);at+=bytes.Length;}}
  Owner(path,false,Sid);
 }
 static Dictionary<string,object> Read(string root,string path,long offset,int count){
  string text;using(var file=new TraceState.LogRangeFile(root,path,count==0))text=file.Snapshot(offset,count);
  var value=Object(text);Need(value.Count==4&&value.ContainsKey("identity")&&value.ContainsKey("chain")&&value.ContainsKey("bytes")&&value.ContainsKey("size"),"exact range response");
  Need(Convert.FromBase64String((string)value["identity"]).Length==32,"identity digest");var chain=(object[])value["chain"];Need(chain.Length>1,"complete namespace chain");
  foreach(object item in chain)Need(item is string&&!((string)item).Contains("\\"),"chain carries IDs, not paths or log text");
  Need(Bytes(value).Length<=count&&Encoding.UTF8.GetByteCount(text)<=4*((count+2)/3)+16384,"bounded range and metadata response");return value;
 }
 static void Reject(Action action,string message){bool denied=false;try{action();}catch(Exception error){
  var cause=error.GetBaseException();if(!(cause is IOException)&&!(cause is UnauthorizedAccessException)&&!(cause is ArgumentException))throw;denied=true;}
  Need(denied,"must reject "+message);
 }
 static void Error(params string[] args){
  var output=new StringWriter();var errors=new StringWriter();var savedOut=Console.Out;var savedError=Console.Error;int status;
  try{Console.SetOut(output);Console.SetError(errors);status=(int)Command.Invoke(null,new object[]{args});}
  finally{Console.SetOut(savedOut);Console.SetError(savedError);}
  Need(status==1,"typed failure exit status");var value=Object(output.ToString());Need(value.Count==1&&value.ContainsKey("error"),"error-only stdout");
  string code=(string)value["error"];Need(System.Text.RegularExpressions.Regex.IsMatch(code,@"\AWINDOWS_LOG_RANGE_[A-Z_]+\z"),"fixed error code only");
  Need(errors.ToString()==code+Environment.NewLine,"stderr contains only the fixed code");
 }
 // The CLI is intentionally stateless. A real producer must bind its own cursor
 // and compare this 64-byte predecessor plus identity/size BEFORE trusting rows.
 static bool Guard(Dictionary<string,object> before,Dictionary<string,object> after){return
  (string)before["identity"]==(string)after["identity"]&&Size(after)>=Size(before)&&Bytes(before).Length==64&&Equal(Bytes(before),Bytes(after));}
 static void GrantFile(string path,SecurityIdentifier sid,FileSystemRights rights){var acl=File.GetAccessControl(path);acl.AddAccessRule(new FileSystemAccessRule(sid,rights,AccessControlType.Allow));File.SetAccessControl(path,acl);}
 static void GrantDirectory(string path,SecurityIdentifier sid,FileSystemRights rights,InheritanceFlags inherit){var acl=Directory.GetAccessControl(path);acl.AddAccessRule(new FileSystemAccessRule(sid,rights,inherit,PropagationFlags.None,AccessControlType.Allow));Directory.SetAccessControl(path,acl);}
 static void Junction(string path,string target){
  Directory.CreateDirectory(path);Owner(path,true,Sid);byte[] substitute=Encoding.Unicode.GetBytes(@"\??\"+target),print=Encoding.Unicode.GetBytes(target);
  byte[] buffer=new byte[16+substitute.Length+2+print.Length+2];
  Buffer.BlockCopy(BitConverter.GetBytes(0xA0000003u),0,buffer,0,4);Buffer.BlockCopy(BitConverter.GetBytes((ushort)(buffer.Length-8)),0,buffer,4,2);
  Buffer.BlockCopy(BitConverter.GetBytes((ushort)substitute.Length),0,buffer,10,2);Buffer.BlockCopy(BitConverter.GetBytes((ushort)(substitute.Length+2)),0,buffer,12,2);
  Buffer.BlockCopy(BitConverter.GetBytes((ushort)print.Length),0,buffer,14,2);Buffer.BlockCopy(substitute,0,buffer,16,substitute.Length);Buffer.BlockCopy(print,0,buffer,18+substitute.Length,print.Length);
  using(var file=CreateFile(@"\\?\"+path,0x40000000,7,IntPtr.Zero,3,0x02200000,IntPtr.Zero)){
   int returned;Need(!file.IsInvalid&&DeviceIoControl(file,0x000900A4,buffer,buffer.Length,IntPtr.Zero,0,out returned,IntPtr.Zero),"create owned native junction");}
 }
 static void PackagePrincipalMapping(){
  const string family="OpenAI.Codex_2p2nqsd0c76g0";IntPtr pointer=IntPtr.Zero;
  var method=typeof(TraceState.LogDesktopIdentity).GetMethod("PackagePrincipal",BindingFlags.Static|BindingFlags.NonPublic);
  try{
   Need(DeriveAppContainerSidFromAppContainerName(family,out pointer)==0&&pointer!=IntPtr.Zero,"documented OS package SID derivation");
   var native=new SecurityIdentifier(pointer);var cap=(SecurityIdentifier)method.Invoke(null,new object[]{family});
   byte[] a=new byte[native.BinaryLength],b=new byte[cap.BinaryLength];native.GetBinaryForm(a,0);cap.GetBinaryForm(b,0);
   Need(a.Length==40&&b.Length==40&&a[8]==2&&b[8]==3,"explicit exact package-capability compatibility RID");
   for(int i=0;i<a.Length;i++)if(i!=8)Need(a[i]==b[i],"all OS-derived authority and package hash bytes unchanged");
   Reject(()=>method.Invoke(null,new object[]{"Unrelated.Package_0000000000000"}),"foreign package cannot authorize an extra writer");
   // A test child may inherit package identity. PID/PFN alone is not desktop provenance; the caller must separately verify the original signed owner chain.
   Cases.Add("os-derived-exact-package-compatibility-mapping");
  }finally{if(pointer!=IntPtr.Zero)FreeSid(pointer);}
 }
 static void Ranges(string root,string path){
  long size=new FileInfo(path).Length;Need(size>=4*1024*1024,"real file at least 4 MiB");
  var value=Read(root,path,size-4096,8192);Need(Size(value)==size&&Equal(Bytes(value),Pattern(size-4096,4096)),"tail at actual offset, safely clipped at captured EOF");
  Need(Equal(Bytes(Read(root,path,65537,Maximum)),Pattern(65537,Maximum)),"one MiB range is exact");
  Need(Bytes(Read(root,path,size,Maximum)).Length==0&&Bytes(Read(root,path,size+123,64)).Length==0,"at/beyond EOF");
  Need(Bytes(Read(root,path,9007199254740991L,Maximum)).Length==0,"maximum safe offset without addition overflow");
  var directory=Read(root,root,0,0);var metadata=Read(root,path,0,0);Need(Size(directory)==0&&Bytes(directory).Length==0&&Size(metadata)==size&&Bytes(metadata).Length==0,"directory/file metadata probes");
  Need((string)metadata["identity"]==(string)value["identity"],"probe and range identify the same file");
  string empty=Path.Combine(root,"empty.log");Fill(empty,0);Need(Size(Read(root,empty,0,64))==0&&Bytes(Read(root,empty,0,64)).Length==0,"empty file");
  Cases.Add("four-mib-tail-and-range-boundaries");
  string large=Path.Combine(root,"sparse-large.log");long largeSize=(1L<<30)+32768;
  using(var stream=new FileStream(large,FileMode.CreateNew,FileAccess.ReadWrite,FileShare.ReadWrite|FileShare.Delete)){
   int returned;Need(DeviceIoControl(stream.SafeFileHandle,0x000900C4,null,0,IntPtr.Zero,0,out returned,IntPtr.Zero),"mark owned NTFS file sparse");
   stream.SetLength(largeSize);stream.Position=largeSize-64;var bytes=Pattern(largeSize-64,64);stream.Write(bytes,0,bytes.Length);}
  Owner(large,false,Sid);IoCounters before,after;using(var self=Process.GetCurrentProcess()){
   Need(GetProcessIoCounters(self.Handle,out before),"read fixture IO counters");var tail=Read(root,large,largeSize-64,64);Need(GetProcessIoCounters(self.Handle,out after),"reread fixture IO counters");
   Need(Size(tail)==largeSize&&Equal(Bytes(tail),Pattern(largeSize-64,64)),"size over one GiB, offset beyond one GiB");
   Need(after.ReadBytes>=before.ReadBytes&&after.ReadBytes-before.ReadBytes<128*1024,"range IO does not scan/hash prefix or depend on offset");}
  Cases.Add("over-one-gib-bounded-io");
 }
 static void Appends(string root,string path){
  long cursor=new FileInfo(path).Length;var guard=Read(root,path,cursor-64,64);
  // Deterministic shared writer already open across native handle checks.
  using(var range=new TraceState.LogRangeFile(root,path,false))using(var writer=new FileStream(path,FileMode.Append,FileAccess.Write,FileShare.ReadWrite|FileShare.Delete)){
   var append=Pattern(cursor,4096);writer.Write(append,0,append.Length);writer.Flush();var value=Object(range.Snapshot(cursor,4096));
   Need(Size(value)>=cursor+4096&&Equal(Bytes(value),append),"append growth with original writable logger handle");}
  Need(Guard(guard,Read(root,path,cursor-64,64)),"same guard after append");
  var ready=new ManualResetEvent(false);var stop=new ManualResetEvent(false);Exception failure=null;int writes=0;
  var worker=new Thread(()=>{try{using(var writer=new FileStream(path,FileMode.Append,FileAccess.Write,FileShare.ReadWrite|FileShare.Delete)){
   var bytes=new byte[4096];while(!stop.WaitOne(0)){writer.Write(bytes,0,bytes.Length);writer.Flush();Interlocked.Increment(ref writes);ready.Set();stop.WaitOne(1);}}}
   catch(Exception error){failure=error;ready.Set();}});worker.IsBackground=true;worker.Start();
  try{Need(ready.WaitOne(5000),"append readiness");for(int i=0;i<20;i++)Need(Guard(guard,Read(root,path,cursor-64,64)),"concurrent append preserves identity and predecessor");}
  finally{stop.Set();Need(worker.Join(5000),"owned append thread exited");ready.Dispose();stop.Dispose();}
  Need(failure==null&&writes>1,"real concurrent append work");Cases.Add("shared-writer-and-concurrent-append");Cases.Add("repeated-64-byte-predecessor-guard");
 }
 static void Mutations(string root){
  string path=Path.Combine(root,"mutation.log");Fill(path,4096);long cursor=4096;var original=Read(root,path,cursor-64,64);
  using(var range=new TraceState.LogRangeFile(root,path,false)){
   using(var writer=new FileStream(path,FileMode.Open,FileAccess.Write,FileShare.ReadWrite|FileShare.Delete))writer.SetLength(1024);
   Reject(()=>range.Snapshot(0,64),"truncate retained file");}
  Need(!Guard(original,Read(root,path,cursor-64,64)),"caller rejects cross-call truncation");Cases.Add("truncate-native-and-guard");
  Fill(path,4096);original=Read(root,path,cursor-64,64);
  using(var range=new TraceState.LogRangeFile(root,path,false)){
   DateTime before=File.GetLastWriteTimeUtc(path);using(var writer=new FileStream(path,FileMode.Open,FileAccess.Write,FileShare.ReadWrite|FileShare.Delete)){
    writer.Position=cursor-64;writer.WriteByte(255);}
   File.SetLastWriteTimeUtc(path,before.AddSeconds(2));Reject(()=>range.Snapshot(cursor-64,64),"same-size write/mtime change");}
  var changed=Read(root,path,cursor-64,64);Need((string)original["identity"]==(string)changed["identity"]&&!Guard(original,changed),"metadata identity alone cannot authenticate prefix content");
  Cases.Add("same-size-write-native-and-guard");
  Fill(path,4096);original=Read(root,path,cursor-64,64);
  using(var writer=new FileStream(path,FileMode.Open,FileAccess.Write,FileShare.ReadWrite|FileShare.Delete)){
   writer.Position=cursor-64;writer.WriteByte(255);writer.Position=writer.Length;writer.WriteByte(1);}
  Need(!Guard(original,Read(root,path,cursor-64,64)),"append growth cannot hide changed predecessor bytes");Cases.Add("rewrite-plus-append-guard");
  Fill(path,4096);original=Read(root,path,cursor-64,64);string saved=path+".rotated";
  using(var range=new TraceState.LogRangeFile(root,path,false)){
   File.Move(path,saved);Fill(path,4096);Reject(()=>range.Snapshot(cursor-64,64),"same-name replacement after open");}
  var replacement=Read(root,path,cursor-64,64);Need(Equal(Bytes(original),Bytes(replacement))&&!Guard(original,replacement),"same bytes on new inode still reject continuation");File.Delete(saved);
  Cases.Add("rotation-sharing-and-same-name-replacement");
  FileAttributes attributes=File.GetAttributes(path);try{
   using(var range=new TraceState.LogRangeFile(root,path,false)){File.SetAttributes(path,attributes|FileAttributes.ReadOnly);Reject(()=>range.Snapshot(0,64),"read-only attribute changed");}
   Reject(()=>Read(root,path,0,64),"read-only log at open");Reject(()=>Read(root,path,0,0),"read-only file metadata probe");
  }finally{File.SetAttributes(path,attributes);}Cases.Add("readonly-attribute-rejected");
 }
 static void Acls(string root,string path){
  byte[] rootBefore=Directory.GetAccessControl(root).GetSecurityDescriptorBinaryForm(),fileBefore=File.GetAccessControl(path).GetSecurityDescriptorBinaryForm();
  Need(!Directory.GetAccessControl(root).AreAccessRulesProtected&&!File.GetAccessControl(path).AreAccessRulesProtected,"vendor-style inherited readonly ACLs");Read(root,path,0,64);
  Need(Equal(rootBefore,Directory.GetAccessControl(root).GetSecurityDescriptorBinaryForm())&&Equal(fileBefore,File.GetAccessControl(path).GetSecurityDescriptorBinaryForm()),"reader never mutates vendor ACLs");
  Reject(()=>PrivateSnapshot.Invoke(null,new object[]{root,path,64}),"original Snapshot still rejects public read grant");
  Reject(()=>{using(var file=new TraceState.ProducerFile(root,path,false,true)){}},"producer private boundary still rejects public read grant");
  Cases.Add("inherited-readonly-grant-and-private-snapshot-fence");
  var original=File.GetAccessControl(path);try{
   var readOnly=new FileSecurity();readOnly.SetOwner(Sid);readOnly.SetAccessRuleProtection(true,false);
   foreach(var principal in new[]{Sid,Everyone})readOnly.AddAccessRule(new FileSystemAccessRule(principal,FileSystemRights.Read|FileSystemRights.Synchronize,AccessControlType.Allow));
   File.SetAccessControl(path,readOnly);Need(Equal(Bytes(Read(root,path,0,64)),Pattern(0,64)),"read needs no write grant");
  }finally{File.SetAccessControl(path,original);}Cases.Add("read-with-no-write-grant");
  foreach(FileSystemRights rights in new[]{FileSystemRights.WriteData,FileSystemRights.AppendData,FileSystemRights.WriteExtendedAttributes,FileSystemRights.WriteAttributes,
   FileSystemRights.Delete,FileSystemRights.ChangePermissions,FileSystemRights.TakeOwnership,FileSystemRights.FullControl}){
   try{GrantFile(path,Everyone,rights);Reject(()=>Read(root,path,0,64),"public writable leaf");Error("log-snapshot",root,path,"0","0","0");}
   finally{File.SetAccessControl(path,original);}}
  var rootAcl=Directory.GetAccessControl(root);try{GrantDirectory(root,Everyone,FileSystemRights.DeleteSubdirectoriesAndFiles,InheritanceFlags.None);Reject(()=>Read(root,path,0,64),"public writable root");}
  finally{Directory.SetAccessControl(root,rootAcl);}Cases.Add("foreign-write-and-delete-acls-rejected");
  // Synthetic principals only. No sampled machine SID or broad S-1-15 privilege.
  foreach(var capability in new[]{new SecurityIdentifier("S-1-15-3-1"),new SecurityIdentifier("S-1-15-3-1024-11-22-33-44-55-66-77-88")}){
   try{GrantFile(path,capability,FileSystemRights.FullControl);Reject(()=>Read(root,path,0,64),"unverified capability writer");}
   finally{File.SetAccessControl(path,original);}}
  Reject(()=>new TraceState.LogDesktopIdentity(0),"missing actual native desktop identity");Reject(()=>new TraceState.LogDesktopIdentity(-1),"invalid desktop PID");Cases.Add("unverified-app-principals-fail-closed");
  using(var range=new TraceState.LogRangeFile(root,path,false))try{GrantFile(path,Everyone,FileSystemRights.ReadAttributes);Reject(()=>range.Snapshot(0,64),"even readonly DACL change while retained");}
  finally{File.SetAccessControl(path,original);}Cases.Add("acl-stability-before-after");
  foreach(var trusted in new[]{new SecurityIdentifier("S-1-5-18"),new SecurityIdentifier("S-1-5-32-544")})try{GrantFile(path,trusted,FileSystemRights.FullControl);Read(root,path,0,64);}
  finally{File.SetAccessControl(path,original);}Cases.Add("system-and-administrators-write-grants");
  // Changing an owned fixture's owner requires privileges some test accounts do
  // not have. Never silently count unavailable owner fixtures as passing.
  foreach(var owner in new[]{new SecurityIdentifier("S-1-5-32-544"),new SecurityIdentifier("S-1-5-18")}){
   bool rootChanged=false,fileChanged=false;try{
    try{Owner(root,true,owner);rootChanged=true;Owner(path,false,owner);fileChanged=true;}
    catch(PrivilegeNotHeldException){Skipped.Add("trusted-owner-privilege-"+owner.Value);continue;}
    catch(UnauthorizedAccessException){Skipped.Add("trusted-owner-access-"+owner.Value);continue;}
    Read(root,path,0,64);Cases.Add("trusted-owner-"+owner.Value);
   }finally{if(fileChanged)Owner(path,false,Sid);if(rootChanged)Owner(root,true,Sid);}}
  // Strict user-owned/protected private checks continue to succeed only inside
  // their original contract, and still reject any extra read principal.
  string privateRoot=Path.Combine(Path.GetDirectoryName(root),"private-traces");Directory.CreateDirectory(privateRoot);Private(privateRoot,true);
  string privateFile=Path.Combine(privateRoot,"fixture.json");Fill(privateFile,128);Private(privateFile,false);
  Need(Convert.FromBase64String((string)Object((string)PrivateSnapshot.Invoke(null,new object[]{privateRoot,privateFile,64}))["bytes"]).Length==64,"original private Snapshot success");
  using(var file=new TraceState.ProducerFile(privateRoot,privateFile,false,true,true))Need(file.Bytes(1024).Length==128,"strict producer success");
  GrantFile(privateFile,Everyone,FileSystemRights.Read);Reject(()=>PrivateSnapshot.Invoke(null,new object[]{privateRoot,privateFile,64}),"private leaf public read grant");
  Reject(()=>{using(var file=new TraceState.ProducerFile(privateRoot,privateFile,false,true,true)){}},"strict producer public read grant");
  Cases.Add("original-private-protections-unmodified");
 }
 static void Links(string root,string path){
  string hard=Path.Combine(root,"hardlink.log");Need(CreateHardLink(hard,path,IntPtr.Zero),"owned hard link");try{
   Reject(()=>Read(root,path,0,64),"source nlink greater than one");Reject(()=>Read(root,hard,0,0),"hardlink metadata probe");
  }finally{File.Delete(hard);}Cases.Add("hardlinks-rejected");
  string link=Path.Combine(root,"symlink.log");bool made=CreateSymbolicLink(link,path,2);if(!made)made=CreateSymbolicLink(link,path,0);
  if(made)try{Reject(()=>Read(root,link,0,64),"leaf symbolic link");Reject(()=>Read(root,link,0,0),"symbolic link metadata");Cases.Add("symlinks-rejected");}
  finally{File.Delete(link);}else Skipped.Add("symlink-Win32-"+Marshal.GetLastWin32Error());
  string junction=Path.Combine(root,"junction");try{Junction(junction,root);Reject(()=>Read(root,Path.Combine(junction,Path.GetFileName(path)),0,64),"ancestor directory junction");
   Reject(()=>Read(root,junction,0,0),"junction metadata");Reject(()=>Read(junction,junction,0,0),"root junction");Cases.Add("junctions-rejected");}
  finally{if(Directory.Exists(junction))Directory.Delete(junction);}
  var shortPath=new StringBuilder(32768);uint n=GetShortPathName(path,shortPath,(uint)shortPath.Capacity);Need(n>0&&n<shortPath.Capacity,"query fixture short name");
  if(!String.Equals(shortPath.ToString(),path,StringComparison.OrdinalIgnoreCase)){
   Reject(()=>Read(root,shortPath.ToString(),0,64),"actual 8.3 alias");Cases.Add("actual-short-alias-rejected");
  }else Skipped.Add("8dot3-generation-disabled");
 }
 static void Arguments(string root,string path){
  foreach(string count in new[]{"-1","+1","1.0","1e3","1048577","2147483648","NaN"," 1","1 ","","00000000000000000"})Error("log-snapshot",root,path,"0",count,"0");
  foreach(string offset in new[]{"-1","+1","1.0","1e3","9007199254740992","9223372036854775807","9223372036854775808","NaN"," 1","1 ",""})Error("log-snapshot",root,path,offset,"64","0");
  Error("log-snapshot");Error("log-snapshot",root,path,"0","64");Error("log-snapshot",root,path,"0","64","0","method");
  foreach(string pid in new[]{"0","-1","+1","2147483648","1.0"," 1",""})Error("log-snapshot",root,path,"0","64",pid);
  Reject(()=>Read(root,root,0,64),"root directory with bytes");Reject(()=>Read(path,path,0,0),"file cannot be log root");
  foreach(long offset in new[]{-1L,9007199254740992L,Int64.MaxValue})Reject(()=>Read(root,path,offset,64),"native offset bounds");
  foreach(int count in new[]{-1,Maximum+1,Int32.MaxValue})Reject(()=>Read(root,path,0,count),"native count bounds");
  string[] paths={"relative.log",@"C:relative.log",@"\\server\share\log",@"\\?\C:\log",@"\\.\C:\log",root.Replace('\\','/'),root+@"\..\log",root+@"\x.\log",root+@"\x \log",path+":ads",path+"\\",root+@"\\log",root+@"\NUL",root+@"\CON.txt",root+@"\CON .txt",root+@"\COM1",root+@"\LPT1",root+"\\COM\u00b9",root+@"\CONIN$",root+@"\CONOUT$",root+@"\CLOCK$",root+@"\SHORTN~1\log",root+"\\bad\nlog",root+"\\bad*log",root+"\\bad?log",root+"\\bad|log",root+"\\bad\"log",root+"\\bad<log",root+"\\bad>log",root+"\\"+new string('a',32768)};
  foreach(string bad in paths){Reject(()=>Read(root,bad,0,64),"invalid leaf path");Reject(()=>Read(bad,path,0,64),"invalid root path");Error("log-snapshot",root,bad,"0","64","0");}
  string outside=root+"-other";Directory.CreateDirectory(outside);Private(outside,true);string other=Path.Combine(outside,"log");Fill(other,64);Reject(()=>Read(root,other,0,64),"outside root despite lexical prefix");
  string sub=Path.Combine(root,"metadata-directory");Directory.CreateDirectory(sub);Owner(sub,true,Sid);Need(Size(Read(root,sub,0,0))==0,"descendant directory metadata");
  Cases.Add("fixed-command-count-offset-and-path-boundaries");
 }
 static int Main(string[] args){
  var report=new Dictionary<string,object>();try{
   Need(args.Length==1,"one owned fixture root");AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling",false);AppContext.SetSwitch("Switch.System.IO.BlockLongPaths",false);
   string parent=Path.Combine(args[0],"vendor-parent");Directory.CreateDirectory(parent);Private(parent,true);
   GrantDirectory(parent,Everyone,FileSystemRights.ReadAndExecute|FileSystemRights.Synchronize,InheritanceFlags.ContainerInherit|InheritanceFlags.ObjectInherit);
   string root=Path.Combine(parent,"physical-msix-log-root");Directory.CreateDirectory(root);Owner(root,true,Sid);
   string path=Path.Combine(root,"electron-native-range-file.log");Fill(path,4*1024*1024+173);
   PackagePrincipalMapping();Ranges(root,path);Appends(root,path);Mutations(root);Acls(root,path);Links(root,path);Arguments(root,path);
   report["passed"]=true;report["root"]=root;report["path"]=path;
  }catch(Exception error){report["passed"]=false;report["errorType"]=error.GetBaseException().GetType().FullName;report["reason"]=error.GetBaseException().Message;}
  report["kind"]="OWNED_NTFS_LOG_RANGE_NOT_ELECTRON_ACCEPTANCE";report["cases"]=Cases;report["skipped"]=Skipped;Console.WriteLine(Json.Serialize(report));return (bool)report["passed"]?0:1;
 }
}
`;

test(
  'Windows native log ranges: real tail/append/guard/identity/ACL/private-boundary fixtures',
  {
    skip:
      process.platform !== 'win32'
        ? 'requires Windows x64 Framework and NTFS; not portable native proof'
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
    assert(compiler, 'existing Framework compiler required; no package/tool installation');
    fs.mkdirSync(join(repository, '.local'), { recursive: true });
    const directory = fs.realpathSync.native(
      fs.mkdtempSync(join(repository, '.local/log-range-native-')),
    );
    const statePath = join(repository, 'packages/codex-adapter/windows/trace-state.cs');
    const processPath = join(repository, 'packages/codex-adapter/windows/trace-process.cs');
    const state = fs.readFileSync(statePath),
      processSource = fs.readFileSync(processPath);
    const write = (name, bytes) => {
      const path = join(directory, name);
      fs.writeFileSync(path, bytes, { flag: 'wx' });
      protectWindowsStateFile(path);
      return path;
    };
    let cleanupSafe = true;
    const execute = (file, args, milliseconds) => {
      cleanupSafe = false;
      const result = withWindowsProcessCreation(milliseconds, (timeout) =>
        spawnSync(file, args, {
          timeout,
          windowsHide: true,
          env: windowsPowerShellEnvironment(),
          maxBuffer: 2 * 1024 * 1024,
          stdio: 'pipe',
        }),
      );
      cleanupSafe = !result.error && result.signal === null && result.status !== null;
      assert.equal(
        result.error,
        undefined,
        'timeout is not a native rejection or evidence of process cleanup',
      );
      assert.equal(result.signal, null);
      return result;
    };
    try {
      protectWindowsStateDirectory(directory);
      const stateCopy = write('trace-state.cs', state),
        processCopy = write('trace-process.cs', processSource);
      const fixtureCopy = write('fixture.cs', harness),
        cli = join(directory, 'trace-state.exe'),
        fixture = join(directory, 'fixture.exe');
      const flags = ['/nologo', '/platform:x64', '/r:System.Web.Extensions.dll'];
      let result = execute(
        compiler,
        [...flags, '/main:TraceState', '/out:' + cli, stateCopy, processCopy],
        30_000,
      );
      assert.equal(result.status, 0, result.stderr?.toString() + result.stdout?.toString());
      protectWindowsStateFile(cli);
      result = execute(
        compiler,
        [...flags, '/main:LogRangeFixture', '/out:' + fixture, stateCopy, processCopy, fixtureCopy],
        30_000,
      );
      assert.equal(result.status, 0, result.stderr?.toString() + result.stdout?.toString());
      protectWindowsStateFile(fixture);
      result = execute(fixture, [directory], 60_000);
      assert.equal(result.status, 0, result.stderr?.toString() + result.stdout?.toString());
      const report = JSON.parse(result.stdout.toString());
      assert.equal(report.passed, true);
      assert.equal(report.kind, 'OWNED_NTFS_LOG_RANGE_NOT_ELECTRON_ACCEPTANCE');
      for (const name of [
        'os-derived-exact-package-compatibility-mapping',
        'four-mib-tail-and-range-boundaries',
        'over-one-gib-bounded-io',
        'shared-writer-and-concurrent-append',
        'repeated-64-byte-predecessor-guard',
        'truncate-native-and-guard',
        'same-size-write-native-and-guard',
        'rewrite-plus-append-guard',
        'rotation-sharing-and-same-name-replacement',
        'readonly-attribute-rejected',
        'inherited-readonly-grant-and-private-snapshot-fence',
        'read-with-no-write-grant',
        'foreign-write-and-delete-acls-rejected',
        'unverified-app-principals-fail-closed',
        'acl-stability-before-after',
        'system-and-administrators-write-grants',
        'original-private-protections-unmodified',
        'hardlinks-rejected',
        'junctions-rejected',
        'fixed-command-count-offset-and-path-boundaries',
      ])
        assert(report.cases.includes(name), name);
      for (const reason of report.skipped) t.diagnostic(`Not verified: ${reason}`);
      // Positive CLI coverage additionally needs the caller's separately verified
      // original desktop identity. An owned unpackaged fixture cannot stand in for
      // that PFN/token/signature/pipe provenance. No real desktop is discovered or
      // contacted by this test. Missing authorization is an explicit skipped case.
      const desktopPid = process.env.AH_NATIVE_LOG_DESKTOP_PID;
      await t.test(
        'fixed CLI against an explicitly supplied, already verified original desktop PID',
        {
          skip: !desktopPid
            ? 'set AH_NATIVE_LOG_DESKTOP_PID only after the fixed signed desktop chain/pipe checks; native token proof unavailable'
            : false,
        },
        () => {
          assert.match(desktopPid, /^[1-9][0-9]*$/);
          assert(Number(desktopPid) <= 2147483647);
          const size = fs.statSync(report.path).size;
          for (const count of [0, 64, maximum]) {
            const offset = count === 0 ? 0 : size - count;
            const reply = execute(
              cli,
              ['log-snapshot', report.root, report.path, String(offset), String(count), desktopPid],
              15_000,
            );
            assert.equal(reply.status, 0, reply.stderr?.toString());
            assert.equal(reply.stderr.toString(), '');
            const value = JSON.parse(reply.stdout.toString());
            assert.deepEqual(Object.keys(value).sort(), ['bytes', 'chain', 'identity', 'size']);
            assert.equal(value.size, size);
            assert.equal(Buffer.from(value.bytes, 'base64').length, count);
            assert(reply.stdout.length <= 4 * Math.ceil(count / 3) + metadataMaximum);
            if (count > 0) {
              const fd = fs.openSync(report.path, 'r');
              try {
                const expected = Buffer.alloc(count);
                assert.equal(fs.readSync(fd, expected, 0, count, offset), count);
                assert.deepEqual(Buffer.from(value.bytes, 'base64'), expected);
              } finally {
                fs.closeSync(fd);
              }
            }
          }
        },
      );
      for (const args of [
        [],
        ['0', '64'],
        ['0', '-1', '0'],
        ['9007199254740992', '64', '0'],
        ['0', '64', '0'],
        ['0', '64', '-1'],
        ['0', '64', '2147483648'],
        ['0', '64', '0', 'shell'],
      ]) {
        const reply = execute(cli, ['log-snapshot', report.root, report.path, ...args], 15_000);
        assert.equal(reply.status, 1);
        const value = JSON.parse(reply.stdout.toString());
        assert.deepEqual(Object.keys(value), ['error']);
        assert.match(value.error, /^WINDOWS_LOG_RANGE_[A-Z_]+$/);
        assert.equal(reply.stderr.toString().trim(), value.error);
      }
      assert.deepEqual(fs.readFileSync(statePath), state, 'product source stayed frozen');
      assert.deepEqual(
        fs.readFileSync(processPath),
        processSource,
        'parallel producer/process source stayed frozen',
      );
      t.diagnostic(
        JSON.stringify({
          stateSHA256: digest(state),
          processSHA256: digest(processSource),
          harnessSHA256: digest(harness),
          cases: report.cases,
        }),
      );
      t.diagnostic(
        'OS-derived package SID compatibility mapping does not prove log authorship or token membership. Real original Electron/PID/full-trust log and phone acceptance require separate evidence.',
      );
    } finally {
      closeWindowsPrivateStateBackend();
      if (cleanupSafe) fs.rmSync(directory, { recursive: true });
      else
        t.diagnostic(
          `Preserved owned native fixture pending exact process/resource verification: ${directory}`,
        );
    }
  },
);
