param([Parameter(Mandatory=$true)][string]$Receipt, [Parameter(Mandatory=$true)][string]$Root, [Parameter(Mandatory=$true)][string]$Digest)
$ErrorActionPreference = 'Stop'
$deletionStarted = $false
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
public static class TraceRetireAnchor {
 [StructLayout(LayoutKind.Sequential)] struct Info {
  public uint Attributes; public System.Runtime.InteropServices.ComTypes.FILETIME Created,Accessed,Written;
  public uint Volume,SizeHigh,SizeLow,Links,IndexHigh,IndexLow;
 }
 [StructLayout(LayoutKind.Sequential)] struct Disposition { [MarshalAs(UnmanagedType.U1)] public bool DeleteFile; }
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetFileInformationByHandle(SafeFileHandle h,out Info info);
 [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetFileInformationByHandle(SafeFileHandle h,uint kind,ref Disposition info,uint size);
 public static SafeFileHandle OpenFile(string path) {
  var h=CreateFile("\\\\?\\"+path,0x80030000u,4,IntPtr.Zero,3,0x02200000,IntPtr.Zero);
  if(h.IsInvalid){h.Dispose();throw new IOException("TRACE_OCCUPIED");}
  try { Check(h,path);FileIdentity(h);return h;}catch {h.Dispose();throw;}
 }
 // Node 24 libuv fs.c: st_dev=VolumeSerialNumber.LowPart; st_ino=FileId.QuadPart.
 // https://github.com/nodejs/node/blob/v24.16.0/deps/uv/src/win/fs.c
 public static string FileIdentity(SafeFileHandle h) {
  Info i;if(!GetFileInformationByHandle(h,out i)||(i.Attributes&0x411)!=0||i.Links!=1)throw new IOException("TRACE_FILE_IDENTITY");
  return i.Volume+":"+(((ulong)i.IndexHigh<<32)|i.IndexLow);
 }
 public static void DeleteFile(SafeFileHandle h) {
  var d=new Disposition{DeleteFile=true};
  if(!SetFileInformationByHandle(h,4,ref d,(uint)Marshal.SizeOf(typeof(Disposition))))throw new IOException("TRACE_DELETE_REJECTED");
 }

 [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
 static extern SafeFileHandle CreateFile(string path,uint access,uint share,IntPtr sa,uint disposition,uint flags,IntPtr template);
 [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
 static extern uint GetFinalPathNameByHandle(SafeFileHandle h,[Out] char[] path,uint n,uint flags);
 public static SafeFileHandle Open(string path) {
  var h=CreateFile("\\\\?\\"+path,0x20080,3,IntPtr.Zero,3,0x02200000,IntPtr.Zero);
  if(h.IsInvalid) {h.Dispose();throw new IOException("TRACE_ANCHOR_UNAVAILABLE");}
  try { Check(h,path);return h;} catch {h.Dispose();throw;}
 }
 public static void Check(SafeFileHandle h,string path) {
  var b=new char[32768];var n=GetFinalPathNameByHandle(h,b,(uint)b.Length,0);
  if(n==0||n>=b.Length||!String.Equals(new string(b,0,(int)n).Substring(4).TrimEnd('\\'),path.TrimEnd('\\'),StringComparison.OrdinalIgnoreCase))throw new IOException("TRACE_NAMESPACE_CHANGED");
 }
}
'@
$handles = New-Object 'System.Collections.Generic.List[System.IDisposable]'
$streams = New-Object 'System.Collections.Generic.List[System.IO.FileStream]'
function Canonical([string]$Path) {
 if($Path -notmatch '^[A-Za-z]:\\' -or $Path.Substring(2).Contains(':') -or $Path.Contains('/') -or $Path.StartsWith('\\')){throw 'TRACE_PATH_REJECTED'}
 $full=[IO.Path]::GetFullPath($Path).TrimEnd('\')
 if($full -cne $Path.TrimEnd('\')){throw 'TRACE_PATH_ALIAS'}
 return $full
}
$current=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$trusted=@($current,'S-1-5-18','S-1-5-32-544')
function Private([string]$Path,[bool]$Root=$false) {
 $item=Get-Item -LiteralPath $Path -Force
 if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'TRACE_REPARSE_POINT'}
 $acl=Get-Acl -LiteralPath $Path
 $owner=$acl.GetOwner([Security.Principal.SecurityIdentifier]).Value
 if(($Root -and $owner -ne $current) -or $trusted -notcontains $owner){throw 'TRACE_FOREIGN_OWNER'}
 foreach($rule in $acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])) {
  if($rule.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
    ($rule.PropagationFlags -band [Security.AccessControl.PropagationFlags]::InheritOnly) -eq 0 -and
    $trusted -notcontains $rule.IdentityReference.Value){throw 'TRACE_PUBLIC_DACL'}
 }
}
try {
 $Receipt=Canonical $Receipt
 Private $Receipt
 $receiptItem=Get-Item -LiteralPath $Receipt -Force
 if($receiptItem.Length -gt 67108864){throw 'TRACE_RECEIPT_TOO_LARGE'}
 if($Digest -cnotmatch '^[a-f0-9]{64}$'){throw 'TRACE_INTENT_DIGEST'}
 $receiptBytes=[IO.File]::ReadAllBytes($Receipt)
 $intentHash=[Security.Cryptography.SHA256]::Create()
 try{$actualDigest=([BitConverter]::ToString($intentHash.ComputeHash($receiptBytes))).Replace('-','').ToLowerInvariant()}finally{$intentHash.Dispose()}
 if($actualDigest -cne $Digest){throw 'TRACE_INTENT_CHANGED'}
 $r=[Text.Encoding]::UTF8.GetString($receiptBytes)|ConvertFrom-Json
 if($r.version -ne 1 -or @($r.PSObject.Properties).Count -ne 4 -or
    $r.name -notmatch '^trace-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}-[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$'){throw 'TRACE_RECEIPT_INVALID'}
 $root=Canonical $Root
 if((Canonical $r.root) -cne $root){throw 'TRACE_ROOT_MISMATCH'}
 if(!$Receipt.StartsWith($root+'\.agent-hub-suggestions\',[StringComparison]::OrdinalIgnoreCase)){throw 'TRACE_RECEIPT_SCOPE'}
 $directory=Join-Path $root $r.name
 $payloads=Join-Path $directory 'payloads'
 # Retain every namespace component against rename/replacement for the operation.
 $ancestors=New-Object 'System.Collections.Generic.List[string]'
 $at=$payloads
 while($at.Length -gt 3){$ancestors.Add($at);$at=[IO.Path]::GetDirectoryName($at)}
 for($i=$ancestors.Count-1;$i -ge 0;$i--) {
  $path=$ancestors[$i]
  $item=Get-Item -LiteralPath $path -Force
  if(($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0){throw 'TRACE_REPARSE_ANCESTOR'}
  $handles.Add([TraceRetireAnchor]::Open($path))
  if($path.Length -ge $root.Length){Private $path ($path -ieq $root)}
 }
 $top=@(Get-ChildItem -LiteralPath $directory -Force | ForEach-Object {$_.Name} | Sort-Object)
 if(($top -join '|') -cne 'manifest.json|payloads|trace.jsonl'){throw 'TRACE_UNKNOWN_CONTENT'}
 $files=@($r.files)
 if($files.Count -lt 2 -or $files.Count -gt 200002){throw 'TRACE_FILE_COUNT'}
 $actual=@('manifest.json','trace.jsonl')+@(Get-ChildItem -LiteralPath $payloads -Force | ForEach-Object {'payloads/'+$_.Name})
 $expected=@($files|ForEach-Object {$_.name})
 if((@($actual|Sort-Object)-join '|') -cne (@($expected|Sort-Object)-join '|') -or @($expected|Select-Object -Unique).Count -ne $expected.Count){throw 'TRACE_CONTENT_CHANGED'}
 foreach($file in $files) {
  if($file.name -cnotmatch '^(manifest\.json|trace\.jsonl|payloads/[0-9]+\.json)$'){throw 'TRACE_FILE_SCOPE'}
  $path=Join-Path $directory $file.name.Replace('/','\')
  Private $path
  # Delete sharing permits this operation's delete, while denying every other
  # reader/writer. Existing reader or writer handles make the open fail.
  $native=[TraceRetireAnchor]::OpenFile($path)
  try{$stream=New-Object IO.FileStream($native,[IO.FileAccess]::Read)}catch{$native.Dispose();throw}
  $handles.Add($stream);$streams.Add($stream)
  if($file.id -cnotmatch '^[0-9]+:[0-9]+$' -or [TraceRetireAnchor]::FileIdentity($stream.SafeFileHandle) -cne $file.id){throw 'TRACE_FILE_IDENTITY_CHANGED'}
  [TraceRetireAnchor]::Check($stream.SafeFileHandle,$path)
  $info=Get-Item -LiteralPath $path -Force
  $modified=([DateTimeOffset]$info.LastWriteTimeUtc).ToUnixTimeMilliseconds()
  if($stream.Length -ne $file.size -or [Math]::Abs($modified-[Math]::Floor($file.mtimeMs)) -gt 1){throw 'TRACE_FILE_CHANGED'}
  if($file.sha256 -cnotmatch '^[0-9a-f]{64}$'){throw 'TRACE_DIGEST_REQUIRED'}
  $hash=[Security.Cryptography.SHA256]::Create()
  try {$digest=([BitConverter]::ToString($hash.ComputeHash($stream))).Replace('-','').ToLowerInvariant()}finally{$hash.Dispose()}
  if($digest -cne $file.sha256){throw 'TRACE_CONTENT_CHANGED'}
 }
 # All exclusive file leases and namespace handles are still held. No deletion
 # occurs before the entire receipt and all bytes have been checked.
 foreach($path in $ancestors){if($path.Length -ge $root.Length){Private $path ($path -ieq $root)}}
 $deletionStarted = $true
 foreach($stream in $streams){[TraceRetireAnchor]::DeleteFile($stream.SafeFileHandle)}
 for($i=$handles.Count-1;$i -ge 0;$i--){$handles[$i].Dispose()};$handles.Clear()
 [IO.Directory]::Delete($payloads,$false)
 [IO.Directory]::Delete($directory,$false)
 [Console]::WriteLine('{"state":"retired"}')
}catch{
 [Console]::Error.WriteLine('TRACE_RETIRE_REJECTED')
 if(!$deletionStarted){
  [Console]::WriteLine('{"state":"not-started"}')
  exit 2
 }
 exit 1
}finally{
 for($i=$handles.Count-1;$i -ge 0;$i--){$handles[$i].Dispose()}
}
