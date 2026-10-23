param([Parameter(Mandatory)][int]$ExpectedPid)
$ErrorActionPreference='Stop'
try {
    foreach($name in @('Microsoft.PowerShell.Security','Microsoft.PowerShell.Utility','Microsoft.PowerShell.Management','CimCmdlets')) {
        Import-Module (Join-Path $PSHOME ('Modules\'+$name)) -ErrorAction Stop
    }
    Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
public static class CodexTraceProcess {
    [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr OpenProcess(uint access,bool inherit,int pid);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("advapi32.dll",SetLastError=true)] static extern bool OpenProcessToken(IntPtr process,uint access,out IntPtr token);
    [DllImport("ntdll.dll")] static extern int NtQueryInformationProcess(IntPtr process,int kind,IntPtr data,int size,out int returned);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool ReadProcessMemory(IntPtr process,IntPtr address,byte[] bytes,UIntPtr size,out UIntPtr count);
    [StructLayout(LayoutKind.Sequential)] struct Memory { public IntPtr Base,Allocation;public uint Protection;public UIntPtr Size;public uint State,Protect,Type; }
    [DllImport("kernel32.dll")] static extern UIntPtr VirtualQueryEx(IntPtr process,IntPtr address,out Memory memory,UIntPtr size);
    static byte[] Read(IntPtr process,IntPtr address,int length) { var b=new byte[length];UIntPtr count;if(!ReadProcessMemory(process,address,b,(UIntPtr)length,out count)||count.ToUInt64()!=(ulong)length)throw new Exception();return b; }
    // Return exactly one non-secret diagnostic key. Never expose an environment block.
    public static string TraceRoot(int pid) {
        if(IntPtr.Size!=8) throw new Exception("X64_REQUIRED");
        IntPtr process=OpenProcess(0x410,false,pid), token=IntPtr.Zero, basic=Marshal.AllocHGlobal(48);
        if(process==IntPtr.Zero) throw new Exception();
        try {
            if(!OpenProcessToken(process,8,out token))throw new Exception();
            using(var owner=new WindowsIdentity(token)) if(owner.User.Value!=WindowsIdentity.GetCurrent().User.Value)throw new Exception();
            int returned;if(NtQueryInformationProcess(process,0,basic,48,out returned)!=0)throw new Exception();
            var peb=Marshal.ReadIntPtr(basic,8);
            var parameters=new IntPtr(BitConverter.ToInt64(Read(process,IntPtr.Add(peb,0x20),8),0));
            var environment=new IntPtr(BitConverter.ToInt64(Read(process,IntPtr.Add(parameters,0x80),8),0));
            Memory region;if(VirtualQueryEx(process,environment,out region,(UIntPtr)Marshal.SizeOf(typeof(Memory)))==UIntPtr.Zero)throw new Exception();
            long available=(long)region.Size.ToUInt64()-(environment.ToInt64()-region.Base.ToInt64());
            if(available<=0 || available>16*1024*1024)throw new Exception();
            string text=Encoding.Unicode.GetString(Read(process,environment,(int)Math.Min(available,1024*1024)));
            int end=text.IndexOf("\0\0",StringComparison.Ordinal);if(end<0)throw new Exception();
            string found=null;foreach(string item in text.Substring(0,end).Split('\0')) if(item.StartsWith("CODEX_ROLLOUT_TRACE_ROOT=",StringComparison.OrdinalIgnoreCase)) {if(found!=null)throw new Exception();found=item.Substring(25);}
            return found;
        } finally { Marshal.FreeHGlobal(basic);if(token!=IntPtr.Zero)CloseHandle(token);CloseHandle(process); }
    }
}
'@
    $main=Get-Process -Id $ExpectedPid
    $session=[System.Diagnostics.Process]::GetCurrentProcess().SessionId
    if($main.SessionId -ne $session){throw 'SESSION_CHANGED'}
    $started=$main.StartTime.ToUniversalTime().Ticks
    $executors=@(Get-CimInstance Win32_Process -Filter "ParentProcessId=$ExpectedPid AND Name='codex.exe'" | ForEach-Object {
        $child=Get-Process -Id $_.ProcessId
        if($child.SessionId -ne $session){throw 'SESSION_CHANGED'}
        # Discovery excludes Host history readers (not children of the original desktop).
        if($_.CommandLine -match '(?:^|\s)app-server(?:\s|$)') {
            $childStarted=$child.StartTime.ToUniversalTime().Ticks
            $childTraceRoot=[CodexTraceProcess]::TraceRoot($child.Id)
            if((Get-Process -Id $child.Id).StartTime.ToUniversalTime().Ticks -ne $childStarted){throw 'INSTANCE_CHANGED'}
            @{pid=$child.Id;parentPid=$ExpectedPid;startedAtUnixMs=[DateTimeOffset]::new($child.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds();traceRoot=$childTraceRoot}
        }
    })
    $traceRoot=[CodexTraceProcess]::TraceRoot($ExpectedPid)
    if((Get-Process -Id $ExpectedPid).StartTime.ToUniversalTime().Ticks -ne $started -or $executors.Count -eq 0){throw 'INSTANCE_CHANGED'}
    @{pid=$ExpectedPid;startedAtUnixMs=[DateTimeOffset]::new($main.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds();traceRoot=$traceRoot;executors=$executors} | ConvertTo-Json -Depth 4 -Compress
} catch { [Console]::Error.WriteLine('WINDOWS_TRACE_INSTANCE_UNAVAILABLE');exit 1 }
