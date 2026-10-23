using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32.SafeHandles;

// Read-only fixed original-instance probe. Signature verification happens once
// in desktop-pipe.ps1; a retained process handle fences that exact live instance.
internal sealed class TraceProcess : IDisposable {
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe,out uint pid);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool ProcessIdToSessionId(uint processId,out uint sessionId);
    [DllImport("advapi32.dll",SetLastError=true)] static extern bool OpenProcessToken(IntPtr process,uint access,out IntPtr token);
    [DllImport("advapi32.dll",SetLastError=true)] static extern bool GetTokenInformation(IntPtr token,int kind,IntPtr data,int size,out int returned);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("ntdll.dll")] static extern int NtQueryInformationProcess(IntPtr process,int kind,IntPtr data,int size,out int returned);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool ReadProcessMemory(IntPtr process,IntPtr address,byte[] bytes,UIntPtr size,out UIntPtr count);
    [StructLayout(LayoutKind.Sequential)] struct Memory {public IntPtr Base,Allocation;public uint Protection;public UIntPtr Size;public uint State,Protect,Type;}
    [DllImport("kernel32.dll")] static extern UIntPtr VirtualQueryEx(IntPtr process,IntPtr address,out Memory memory,UIntPtr size);
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct Entry { public uint Size,Usage,Pid;public UIntPtr Heap;public uint Module,Threads,Parent;public int Priority;public uint Flags;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=260)] public string Name; }
    [DllImport("kernel32.dll",SetLastError=true)] static extern SafeFileHandle CreateToolhelp32Snapshot(uint flags,uint pid);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool Process32FirstW(SafeFileHandle snapshot,ref Entry entry);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool Process32NextW(SafeFileHandle snapshot,ref Entry entry);
    readonly int pid; readonly long started; readonly string executable; readonly Process original;
    static readonly string User=WindowsIdentity.GetCurrent().User.Value;
    internal TraceProcess(int expectedPid,long expectedStarted,string expectedExecutable) {
        if(IntPtr.Size!=8)throw new IOException(); pid=expectedPid;started=expectedStarted;executable=expectedExecutable;
        original=Process.GetProcessById(pid); var retained=original.Handle; using(var pipe=Pipe())Check();
    }
    static long Started(Process process) { return new DateTimeOffset(process.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds(); }
    // Avoid .NET process-info table refreshes while querying the OS afresh every time.
    static uint Session(Process process) {
        uint session;if(!ProcessIdToSessionId((uint)process.Id,out session))throw new IOException();return session;
    }
    void Check() {
        original.Refresh(); if(original.HasExited || Started(original)!=started ||
            !String.Equals(original.MainModule.FileName,executable,StringComparison.OrdinalIgnoreCase) ||
            Session(original)!=Session(Process.GetCurrentProcess()))throw new IOException();
        using(var identity=Identity(original))if(identity.User.Value!=User)throw new UnauthorizedAccessException();
    }
    NamedPipeClientStream Pipe() {
        var pipe=new NamedPipeClientStream(".","codex-ipc",PipeDirection.InOut,PipeOptions.None);
        try {pipe.Connect(250);CheckPipe(pipe);return pipe;}catch{pipe.Dispose();throw;}
    }
    void CheckPipe(NamedPipeClientStream pipe){uint actual;if(!GetNamedPipeServerProcessId(pipe.SafePipeHandle,out actual)||actual!=(uint)pid)throw new IOException();}
    int[] Children() {
        var result=new List<int>();using(var snapshot=CreateToolhelp32Snapshot(2,0)) {
            if(snapshot.IsInvalid)throw new IOException();var entry=new Entry();entry.Size=(uint)Marshal.SizeOf(typeof(Entry));
            if(!Process32FirstW(snapshot,ref entry))throw new IOException();
            do {if(entry.Parent==(uint)pid && String.Equals(entry.Name,"codex.exe",StringComparison.OrdinalIgnoreCase))result.Add((int)entry.Pid);} while(Process32NextW(snapshot,ref entry));
            if(Marshal.GetLastWin32Error()!=18)throw new IOException();
        }result.Sort();return result.ToArray();
    }
    static WindowsIdentity Identity(Process process) {
        IntPtr token;if(!OpenProcessToken(process.Handle,8,out token))throw new IOException();
        try{return new WindowsIdentity(token);}finally{CloseHandle(token);}
    }
    static byte[] Read(Process process,IntPtr address,int length) {
        if(length<0||length>1024*1024)throw new IOException();var bytes=new byte[length];UIntPtr count;
        if(!ReadProcessMemory(process.Handle,address,bytes,(UIntPtr)length,out count)||count.ToUInt64()!=(ulong)length)throw new IOException();return bytes;
    }
    static IntPtr Parameters(Process process,out int parent) {
        IntPtr basic=Marshal.AllocHGlobal(48);try {int count;if(NtQueryInformationProcess(process.Handle,0,basic,48,out count)!=0)throw new IOException();
            parent=Marshal.ReadIntPtr(basic,40).ToInt32();var peb=Marshal.ReadIntPtr(basic,8);
            return new IntPtr(BitConverter.ToInt64(Read(process,IntPtr.Add(peb,0x20),8),0));
        }finally{Marshal.FreeHGlobal(basic);}
    }
    static string Command(Process process,IntPtr parameters) {
        byte[] value=Read(process,IntPtr.Add(parameters,0x70),16);int length=BitConverter.ToUInt16(value,0);
        return Encoding.Unicode.GetString(Read(process,new IntPtr(BitConverter.ToInt64(value,8)),length));
    }
    static string Root(Process process,IntPtr parameters) {
        var environment=new IntPtr(BitConverter.ToInt64(Read(process,IntPtr.Add(parameters,0x80),8),0));Memory region;
        if(VirtualQueryEx(process.Handle,environment,out region,(UIntPtr)Marshal.SizeOf(typeof(Memory)))==UIntPtr.Zero)throw new IOException();
        long available=(long)region.Size.ToUInt64()-(environment.ToInt64()-region.Base.ToInt64());if(available<=0)throw new IOException();
        string text=Encoding.Unicode.GetString(Read(process,environment,(int)Math.Min(available,1024*1024)));int end=text.IndexOf("\0\0",StringComparison.Ordinal);if(end<0)throw new IOException();
        string found=null;foreach(string entry in text.Substring(0,end).Split('\0')) if(entry.StartsWith("CODEX_ROLLOUT_TRACE_ROOT=",StringComparison.OrdinalIgnoreCase)){if(found!=null)throw new IOException();found=entry.Substring(25);}return found;
    }
    internal object Inspect() {
        using(var pipe=Pipe()) {
        Check();int ignored;string root=Root(original,Parameters(original,out ignored));var entries=new List<object>();
        foreach(int childId in Children())using(var child=Process.GetProcessById(childId)) {
            try {int parent;IntPtr parameters=Parameters(child,out parent);if(parent!=pid)continue;
                using(var identity=Identity(child))if(identity.User.Value!=User)throw new UnauthorizedAccessException();
                if(Session(child)!=Session(original))throw new IOException();
                if(!Regex.IsMatch(Command(child,parameters),@"(?:^|\s)app-server(?:\s|$)"))continue;
                long at=Started(child);string trace=Root(child,parameters);child.Refresh();if(child.HasExited||Started(child)!=at)throw new IOException();
                entries.Add(new{pid=child.Id,parentPid=pid,startedAtUnixMs=at,traceRoot=trace});
            }catch(InvalidOperationException){throw new IOException();}
        }
        if(entries.Count==0)throw new IOException();Check();CheckPipe(pipe);return new{pid=pid,startedAtUnixMs=started,traceRoot=root,executors=entries};
        }
    }
    static object Token(Process process) {
        IntPtr token;if(!OpenProcessToken(process.Handle,8,out token))throw new IOException();try {
            using(var identity=new WindowsIdentity(token))if(identity.User.Value!=User)throw new UnauthorizedAccessException();
            int count;GetTokenInformation(token,4,IntPtr.Zero,0,out count);IntPtr data=Marshal.AllocHGlobal(count);string owner;
            try{if(!GetTokenInformation(token,4,data,count,out count))throw new IOException();owner=new SecurityIdentifier(Marshal.ReadIntPtr(data)).Value;}finally{Marshal.FreeHGlobal(data);}
            data=Marshal.AllocHGlobal(4);int elevated;try{if(!GetTokenInformation(token,20,data,4,out count))throw new IOException();elevated=Marshal.ReadInt32(data);}finally{Marshal.FreeHGlobal(data);}
            return new{pid=process.Id,userSid=User,defaultOwnerSid=owner,elevated=elevated!=0,session=process.SessionId};
        }finally{CloseHandle(token);}
    }
    internal object Tokens(){using(var pipe=Pipe()){Check();var entries=new List<object>();foreach(int childId in Children())using(var child=Process.GetProcessById(childId)){int parent;var parameters=Parameters(child,out parent);if(parent==pid&&Regex.IsMatch(Command(child,parameters),@"(?:^|\s)app-server(?:\s|$)"))entries.Add(Token(child));}using(var current=Process.GetCurrentProcess()){int parent;Parameters(current,out parent);using(var host=Process.GetProcessById(parent)){var result=new{original=Token(original),helper=Token(current),testHost=Token(host),executors=entries};Check();CheckPipe(pipe);return result;}}}}
    public void Dispose(){original.Dispose();}
}
