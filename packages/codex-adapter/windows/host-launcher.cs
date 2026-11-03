using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Collections.Generic;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;
using System.Text.RegularExpressions;

// Built as winexe: Task Scheduler itself must never allocate a console.
// No shell, detach or input dispatch; the sole child is the fixed Host worker.
internal static class HostLauncher
{
    [DataContract] private sealed class RuntimeFile { [DataMember] public int bytes=0; [DataMember] public string sha256=null; }
    [DataContract] private sealed class RuntimeManifest { [DataMember] public string format=null; [DataMember] public string sourceCommit=null; [DataMember] public Dictionary<string,RuntimeFile> files=null; }
    private static void Inventory(string root, string directory, HashSet<string> names, ref int count)
    {
        if(++count>25000) throw new InvalidOperationException();
        foreach(string path in Directory.GetFileSystemEntries(directory)) {
            FileAttributes attributes=File.GetAttributes(path);
            if((attributes&FileAttributes.ReparsePoint)!=0) throw new InvalidOperationException();
            if((attributes&FileAttributes.Directory)!=0) Inventory(root,path,names,ref count);
            else { string name=path.Substring(root.Length+1); if(name!="runtime-manifest.json" && !names.Remove(name)) throw new InvalidOperationException(); }
        }
    }
    private static void GuardProtectedRuntime(string[] args, List<IDisposable> leases)
    {
        string anchor=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),".config","agent-hub","windows-host-v1","programs")+Path.DirectorySeparatorChar;
        string worker=Path.GetFullPath(args[1]);
        if(!worker.StartsWith(anchor,StringComparison.OrdinalIgnoreCase)) return;
        string root=Path.GetDirectoryName(Path.GetDirectoryName(worker));
        string manifestSha=Path.GetFileName(root);
        if(!Regex.IsMatch(manifestSha,"^[a-f0-9]{64}$") || !String.Equals(Path.GetDirectoryName(root)+Path.DirectorySeparatorChar,anchor,StringComparison.OrdinalIgnoreCase) ||
            !String.Equals(worker,Path.Combine(root,"scripts","windows-desktop-worker.mjs"),StringComparison.OrdinalIgnoreCase) ||
            !String.Equals(Path.GetFullPath(args[0]),Path.Combine(root,"bin","node.exe"),StringComparison.OrdinalIgnoreCase)) throw new InvalidOperationException();
        var manifestLease=CodexManagePrivateStateSecurity.OpenRecoveryRuntimeRead(Path.Combine(root,"runtime-manifest.json"),2097152,manifestSha); leases.Add(manifestLease);
        RuntimeManifest manifest;
        using(var input=new MemoryStream(manifestLease.Bytes)) manifest=(RuntimeManifest)new DataContractJsonSerializer(typeof(RuntimeManifest),new DataContractJsonSerializerSettings{UseSimpleDictionaryFormat=true}).ReadObject(input);
        if(manifest==null || manifest.format!="windows-host-regular-runtime-v1" || !Regex.IsMatch(manifest.sourceCommit??"","^[a-f0-9]{40}$") || manifest.files==null || manifest.files.Count>20000 ||
            !manifest.files.ContainsKey("bin/node.exe") || !manifest.files.ContainsKey("scripts/windows-desktop-worker.mjs") || !manifest.files.ContainsKey("apps/host-agent/dist/runtime-entry.js")) throw new InvalidOperationException();
        var inventory=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach(var entry in manifest.files) {
            string name=entry.Key;
            if(!Regex.IsMatch(name,"^[a-zA-Z0-9@._/-]+$") || name.StartsWith("/") || entry.Value==null || entry.Value.bytes<0 || entry.Value.bytes>134217728) throw new InvalidOperationException();
            foreach(string part in name.Split('/')) if(part.Length==0 || part=="." || part=="..") throw new InvalidOperationException();
            if(!inventory.Add(name.Replace('/',Path.DirectorySeparatorChar))) throw new InvalidOperationException();
            var lease=CodexManagePrivateStateSecurity.OpenRecoveryRuntimeRead(Path.Combine(root,name.Replace('/',Path.DirectorySeparatorChar)),134217728,entry.Value.sha256); leases.Add(lease);
            if(lease.Bytes.Length!=entry.Value.bytes) throw new InvalidOperationException();
        }
        int directoryCount=0; Inventory(root,root,inventory,ref directoryCount);
        if(inventory.Count!=0) throw new InvalidOperationException();
        Environment.SetEnvironmentVariable("CODEX_MANAGE_WINDOWS_NATIVE_STORAGE","profile-v1");
        Environment.SetEnvironmentVariable("CODEX_DESKTOP_SUGGESTION_OBSERVER","0");
        Environment.SetEnvironmentVariable("CODEX_DESKTOP_TRACE_RETENTION","0");
        Environment.SetEnvironmentVariable("CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG",null);
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct StartupInfo
    {
        public uint Size;
        public string Reserved, Desktop, Title;
        public uint X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
        public ushort ShowWindow, ReservedSize;
        public IntPtr ReservedBytes, StdInput, StdOutput, StdError;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct ProcessInfo
    {
        public IntPtr Process, Thread;
        public uint ProcessId, ThreadId;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct BasicLimits
    {
        public long ProcessTime, JobTime;
        public uint Flags;
        public UIntPtr MinWorkingSet, MaxWorkingSet;
        public uint ActiveProcesses;
        public UIntPtr Affinity;
        public uint Priority, Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct ExtendedLimits
    {
        public BasicLimits Basic;
        public ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes;
        public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateJobObject(IntPtr security, string name);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetInformationJobObject(IntPtr job, int kind, ref ExtendedLimits limits, uint size);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool CreateProcess(string executable, StringBuilder command, IntPtr processSecurity,
        IntPtr threadSecurity, bool inheritHandles, uint flags, IntPtr environment,
        string cwd, ref StartupInfo startup, out ProcessInfo process);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetExitCodeProcess(IntPtr process, out uint code);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool TerminateProcess(IntPtr process, uint code);
    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);

    private static string Quote(string value)
    {
        var result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in value)
        {
            if (c == '\\') { slashes++; continue; }
            result.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
            result.Append(c);
            slashes = 0;
        }
        result.Append('\\', slashes * 2);
        return result.Append('"').ToString();
    }

    private static int Main(string[] args)
    {
        IntPtr job = IntPtr.Zero;
        var child = new ProcessInfo();
        bool completed = false;
        var leases = new List<IDisposable>();
        try
        {
            AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
            AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
            if (args.Length != 3 || !Path.IsPathRooted(args[0]) ||
                !Path.IsPathRooted(args[1]) || !Path.IsPathRooted(args[2]) ||
                !String.Equals(Path.GetFileName(args[0]), "node.exe", StringComparison.OrdinalIgnoreCase) ||
                !String.Equals(Path.GetFileName(args[1]), "windows-desktop-worker.mjs", StringComparison.OrdinalIgnoreCase) ||
                !String.Equals(Path.GetFileName(args[2]), "windows-host.json", StringComparison.OrdinalIgnoreCase)) return 64;
            // Scheduler launch must not load a user/global Node preloader
            // before the fixed worker can validate its configuration.
            Environment.SetEnvironmentVariable("NODE_OPTIONS", null);
            Environment.SetEnvironmentVariable("NODE_PATH", null);
            GuardProtectedRuntime(args,leases);
            job = CreateJobObject(IntPtr.Zero, null);
            var limits = new ExtendedLimits();
            limits.Basic.Flags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            if (job == IntPtr.Zero || !SetInformationJobObject(job, 9, ref limits,
                (uint)Marshal.SizeOf(typeof(ExtendedLimits)))) return 70;
            var startup = new StartupInfo();
            startup.Size = (uint)Marshal.SizeOf(typeof(StartupInfo));
            var command = new StringBuilder(Quote(args[0]) + " " + Quote(args[1]) + " " + Quote(args[2]));
            // CREATE_NO_WINDOW | CREATE_SUSPENDED. Bind the child to its private
            // job BEFORE its first instruction; no orphan or duplicate on a
            // launcher crash, and no race with helpers spawned by the worker.
            if (!CreateProcess(args[0], command, IntPtr.Zero, IntPtr.Zero, false,
                0x08000004, IntPtr.Zero, Path.GetDirectoryName(args[1]), ref startup, out child)) return 70;
            if (!AssignProcessToJobObject(job, child.Process) || ResumeThread(child.Thread) == UInt32.MaxValue) return 70;
            // The scheduler action stays Running for the entire worker lifetime.
            if (WaitForSingleObject(child.Process, UInt32.MaxValue) != 0) return 70;
            uint code;
            if (!GetExitCodeProcess(child.Process, out code)) return 70;
            completed = true;
            return unchecked((int)code);
        }
        catch { return 70; } // No dialogs or private paths in error output.
        finally
        {
            if (child.Process != IntPtr.Zero && !completed) TerminateProcess(child.Process, 70);
            if (child.Thread != IntPtr.Zero) CloseHandle(child.Thread);
            if (child.Process != IntPtr.Zero) CloseHandle(child.Process);
            // Only descendants of this invocation belong to the unnamed job;
            // the official desktop and the user's terminals are never included.
            if (job != IntPtr.Zero) CloseHandle(job);
            for(int i=leases.Count-1;i>=0;i--) leases[i].Dispose();
        }
    }
}
