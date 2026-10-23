using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;

// Built as winexe: Task Scheduler itself must never allocate a console.
// No shell, detach or input dispatch; the sole child is the fixed Host worker.
internal static class HostLauncher
{
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
        try
        {
            AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
            AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
            if (args.Length != 3 || !Path.IsPathRooted(args[0]) ||
                !Path.IsPathRooted(args[1]) || !Path.IsPathRooted(args[2]) ||
                !String.Equals(Path.GetFileName(args[0]), "node.exe", StringComparison.OrdinalIgnoreCase) ||
                !String.Equals(Path.GetFileName(args[1]), "windows-desktop-worker.mjs", StringComparison.OrdinalIgnoreCase) ||
                !String.Equals(Path.GetFileName(args[2]), "windows-host.json", StringComparison.OrdinalIgnoreCase)) return 64;
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
        }
    }
}
