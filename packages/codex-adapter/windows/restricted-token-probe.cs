using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;

// Diagnostic only: fixed Windows PowerShell, fixed no-I/O membership check.
// Does not change UAC, rights, accounts, tasks, Host state or the desktop.
public static class HostRestrictedTokenProbe
{
    public static string Phase { get; private set; }
    public static bool ChildCreated { get; private set; }
    public static bool ChildExitConfirmed { get; private set; }
    public static Result Observation { get; private set; }
    [StructLayout(LayoutKind.Sequential)] struct SidAndAttributes { public IntPtr Sid; public uint Attributes; }
    [StructLayout(LayoutKind.Sequential)] struct BasicLimits { public long A, B; public uint Flags; public UIntPtr C, D; public uint E; public UIntPtr F; public uint G, H; }
    [StructLayout(LayoutKind.Sequential)] struct Limits { public BasicLimits Basic; public ulong A, B, C, D, E, F; public UIntPtr G, H, I, J; }
    [StructLayout(LayoutKind.Sequential, CharSet=CharSet.Unicode)] struct Startup { public uint Size; public string Reserved, Desktop, Title; public uint X, Y, XS, YS, XC, YC, Fill, Flags; public ushort Show, ReservedSize; public IntPtr ReservedBytes, Input, Output, Error; }
    [StructLayout(LayoutKind.Sequential)] struct Child { public IntPtr Process, Thread; public uint Pid, Tid; }
    public sealed class Result { public bool Admin, SameUser, SameSession, JobMember, Exited; public int IntegrityRid; public uint ExitCode; }
    [DllImport("advapi32.dll", SetLastError=true)] static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("advapi32.dll", SetLastError=true)] static extern bool CreateRestrictedToken(IntPtr token, uint flags, uint sidCount, SidAndAttributes[] disabled, uint privilegeCount, IntPtr privileges, uint restrictCount, IntPtr restrict, out IntPtr result);
    [DllImport("advapi32.dll", SetLastError=true)] static extern bool SetTokenInformation(IntPtr token, int kind, ref SidAndAttributes value, uint length);
    [DllImport("advapi32.dll", SetLastError=true)] static extern bool GetTokenInformation(IntPtr token, int kind, IntPtr value, uint length, out uint returned);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool CreateProcessAsUserW(IntPtr token, string executable, StringBuilder command, IntPtr processSecurity, IntPtr threadSecurity, bool inherit, uint flags, IntPtr environment, string cwd, ref Startup startup, out Child child);
    [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthorityCount(IntPtr sid);
    [DllImport("advapi32.dll")] static extern IntPtr GetSidSubAuthority(IntPtr sid, uint index);
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr CreateJobObjectW(IntPtr attributes, string name);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job, int kind, ref Limits limits, uint bytes);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool member);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateProcess(IntPtr process, uint code);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    static void Win(bool ok) { if (!ok) throw new Win32Exception(Marshal.GetLastWin32Error()); }
    static void Need(bool ok) { if (!ok) throw new InvalidOperationException("HOST_RESTRICTED_PROBE_REJECTED"); }
    static IntPtr Sid(string text) { var sid=new SecurityIdentifier(text); var bytes=new byte[sid.BinaryLength]; sid.GetBinaryForm(bytes,0); var ptr=Marshal.AllocHGlobal(bytes.Length); Marshal.Copy(bytes,0,ptr,bytes.Length); return ptr; }
    static int TokenInteger(IntPtr token, int kind) { uint length; var memory=Marshal.AllocHGlobal(4); try { Win(GetTokenInformation(token,kind,memory,4,out length)); return Marshal.ReadInt32(memory); } finally { Marshal.FreeHGlobal(memory); } }
    static int Integrity(IntPtr token) { uint length; GetTokenInformation(token,25,IntPtr.Zero,0,out length); Need(length>0 && length<65536); var memory=Marshal.AllocHGlobal((int)length); try { Win(GetTokenInformation(token,25,memory,length,out length)); var label=(SidAndAttributes)Marshal.PtrToStructure(memory,typeof(SidAndAttributes)); byte count=Marshal.ReadByte(GetSidSubAuthorityCount(label.Sid)); Need(count>0); return Marshal.ReadInt32(GetSidSubAuthority(label.Sid,(uint)(count-1))); } finally { Marshal.FreeHGlobal(memory); } }
    public static Result Run()
    {
        IntPtr original=IntPtr.Zero, restricted=IntPtr.Zero, admin=IntPtr.Zero, medium=IntPtr.Zero, job=IntPtr.Zero, childToken=IntPtr.Zero;
        var child=new Child(); bool exited=false;
        try {
            Phase="open-current-token"; ChildCreated=false; ChildExitConfirmed=false; Observation=null;
            // CreateRestrictedToken preserves the source handle's access mask.
            // TOKEN_ADJUST_DEFAULT is required for lowering integrity below.
            using(var self=Process.GetCurrentProcess()) Win(OpenProcessToken(self.Handle,0x008b,out original));
            admin=Sid("S-1-5-32-544");
            Phase="create-restricted-token";
            Win(CreateRestrictedToken(original,1,1,new[]{new SidAndAttributes{Sid=admin,Attributes=0}},0,IntPtr.Zero,0,IntPtr.Zero,out restricted));
            medium=Sid("S-1-16-8192"); var label=new SidAndAttributes{Sid=medium,Attributes=0x20};
            Phase="lower-integrity";
            Win(SetTokenInformation(restricted,25,ref label,(uint)(Marshal.SizeOf(typeof(SidAndAttributes))+new SecurityIdentifier("S-1-16-8192").BinaryLength)));
            Phase="create-job";
            job=CreateJobObjectW(IntPtr.Zero,null); Need(job!=IntPtr.Zero); var limits=new Limits(); limits.Basic.Flags=0x2000;
            Win(SetInformationJobObject(job,9,ref limits,(uint)Marshal.SizeOf(typeof(Limits))));
            string executable=Path.Combine(Environment.SystemDirectory,"WindowsPowerShell","v1.0","powershell.exe");
            const string code="$i=[Security.Principal.WindowsIdentity]::GetCurrent();if(([Security.Principal.WindowsPrincipal]::new($i)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){exit 19};exit 0";
            var command=new StringBuilder("\""+executable+"\" -NoProfile -NonInteractive -EncodedCommand "+Convert.ToBase64String(Encoding.Unicode.GetBytes(code)));
            var startup=new Startup{Size=(uint)Marshal.SizeOf(typeof(Startup))};
            Phase="create-fixed-process-suspended";
            Win(CreateProcessAsUserW(restricted,executable,command,IntPtr.Zero,IntPtr.Zero,false,0x08000004,IntPtr.Zero,Environment.SystemDirectory,ref startup,out child));
            ChildCreated=true; Phase="assign-job";
            Win(AssignProcessToJobObject(job,child.Process)); bool member; Win(IsProcessInJob(child.Process,job,out member)); Need(member);
            Phase="inspect-actual-child-token";
            // WindowsPrincipal.IsInRole duplicates a primary token before
            // CheckTokenMembership. Keep QUERY and DUPLICATE for that check.
            Win(OpenProcessToken(child.Process,10,out childToken));
            Phase="inspect-child-integrity-and-session";
            var result=new Result{JobMember=member,IntegrityRid=Integrity(childToken),SameSession=TokenInteger(original,12)==TokenInteger(childToken,12)};
            Observation=result;
            Phase="inspect-child-membership";
            using(var a=new WindowsIdentity(original)) using(var b=new WindowsIdentity(childToken)) { result.SameUser=a.User==b.User; result.Admin=new WindowsPrincipal(b).IsInRole(WindowsBuiltInRole.Administrator); }
            Need(!result.Admin && result.SameUser && result.SameSession && result.IntegrityRid==8192);
            Phase="resume-thread";
            Need(ResumeThread(child.Thread)!=UInt32.MaxValue);
            Phase="wait-fixed-check";
            Need(WaitForSingleObject(child.Process,10000)==0); exited=true; ChildExitConfirmed=true; result.Exited=true;
            Phase="read-exit-code"; Win(GetExitCodeProcess(child.Process,out result.ExitCode));
            Phase="check-exit-code"; Need(result.ExitCode==0); Phase="verified"; return result;
        } finally {
            if(child.Process!=IntPtr.Zero && !exited) { TerminateProcess(child.Process,191); ChildExitConfirmed=WaitForSingleObject(child.Process,5000)==0; }
            foreach(var handle in new[]{childToken,child.Thread,child.Process,job,restricted,original}) if(handle!=IntPtr.Zero) CloseHandle(handle);
            if(admin!=IntPtr.Zero) Marshal.FreeHGlobal(admin); if(medium!=IntPtr.Zero) Marshal.FreeHGlobal(medium);
        }
    }
}
