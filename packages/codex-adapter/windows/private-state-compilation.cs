using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Threading;
using Microsoft.Win32.SafeHandles;

// Fixed cold bootstrap only. A metadata-only creation handle leases compiler A;
// its retained read handle supplies bytes to a separately created publication B.
// Only B's WRITE/DELETE creation handle publishes. No shell, caller-supplied
// compiler switches, privilege changes or owner adoption.
public static partial class CodexManagePrivateStateSecurity
{
    private const uint MaxCompilationOutputBytes = 64 * 1024 * 1024;

    // Fixed diagnostic labels only. The first failure survives later cleanup;
    // no exception message, pathname, command line or source content is emitted.
    private const string CompilationStepKey = "CodexManage.NativeStep";
    private const string CompilationCleanupStepKey = "CodexManage.NativeCleanupStep";
    private const string CompilationCleanupCodeKey = "CodexManage.NativeCleanupWin32";
    private static Exception CompilationAt(Exception failure, string step)
    {
        if (!failure.Data.Contains(CompilationStepKey)) failure.Data[CompilationStepKey] = step;
        return failure;
    }
    private static Win32Exception CompilationFailure(string step, int code)
    {
        var failure = new Win32Exception(code);
        CompilationAt(failure, step);
        return failure;
    }
    private static Win32Exception CompilationLastError(string step)
    {
        return CompilationFailure(step, Marshal.GetLastWin32Error());
    }
    private static string CompilationFailureStep(Exception failure)
    {
        return failure.Data[CompilationStepKey] as string;
    }
    private static void CompilationCleanupFailure(Exception primary, Exception cleanup)
    {
        if (primary.Data.Contains(CompilationCleanupStepKey)) return;
        primary.Data[CompilationCleanupStepKey] = CompilationFailureStep(cleanup) ?? "cleanup";
        var native = cleanup as Win32Exception;
        if (native != null) primary.Data[CompilationCleanupCodeKey] = native.NativeErrorCode;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct CompilationStartupInfo
    {
        public uint Size;
        public IntPtr Reserved, Desktop, Title;
        public uint X, Y, XSize, YSize, XCountChars, YCountChars, FillAttribute, Flags;
        public ushort ShowWindow, ReservedSize;
        public IntPtr ReservedBytes, StdInput, StdOutput, StdError;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct CompilationStartupInfoEx
    {
        public CompilationStartupInfo Startup;
        public IntPtr Attributes;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct CompilationProcessInfo
    {
        public IntPtr Process, Thread;
        public uint ProcessId, ThreadId;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct CompilationBasicLimits
    {
        public long ProcessTime, JobTime;
        public uint Flags;
        public UIntPtr MinWorkingSet, MaxWorkingSet;
        public uint ActiveProcesses;
        public UIntPtr Affinity;
        public uint Priority, Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct CompilationExtendedLimits
    {
        public CompilationBasicLimits Basic;
        public ulong ReadOperations, WriteOperations, OtherOperations, ReadBytes, WriteBytes, OtherBytes;
        public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [StructLayout(LayoutKind.Sequential)]
    private struct CompilationJobAccounting
    {
        public long UserTime, KernelTime, PeriodUserTime, PeriodKernelTime;
        public uint PageFaults, TotalProcesses, ActiveProcesses, TerminatedProcesses;
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct CompilationRenameInfo
    {
        public uint Flags; // FileRenameInfo: ReplaceIfExists = FALSE, including padding.
        public IntPtr RootDirectory;
        public uint FileNameLength;
        public char FileName;
    }

    [DllImport("kernel32.dll", EntryPoint = "CreateJobObjectW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CompilationCreateJob(IntPtr security, string name);
    [DllImport("kernel32.dll", EntryPoint = "SetInformationJobObject", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationSetJobLimits(IntPtr job, int kind,
        ref CompilationExtendedLimits limits, uint size);
    [DllImport("kernel32.dll", EntryPoint = "QueryInformationJobObject", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationQueryJob(IntPtr job, int kind,
        out CompilationJobAccounting accounting, uint size, IntPtr returned);
    [DllImport("kernel32.dll", EntryPoint = "TerminateJobObject", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationTerminateJob(IntPtr job, uint code);
    [DllImport("kernel32.dll", EntryPoint = "InitializeProcThreadAttributeList", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationInitializeAttributes(IntPtr attributes,
        int count, uint flags, ref UIntPtr size);
    [DllImport("kernel32.dll", EntryPoint = "UpdateProcThreadAttribute", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationUpdateAttribute(IntPtr attributes, uint flags,
        UIntPtr attribute, IntPtr value, UIntPtr size, IntPtr previous, IntPtr returned);
    [DllImport("kernel32.dll", EntryPoint = "DeleteProcThreadAttributeList")]
    private static extern void CompilationDeleteAttributes(IntPtr attributes);
    [DllImport("kernel32.dll", EntryPoint = "CreateProcessW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationCreateProcess(string executable, StringBuilder command,
        IntPtr processSecurity, IntPtr threadSecurity, [MarshalAs(UnmanagedType.Bool)] bool inheritHandles,
        uint flags, IntPtr environment, string cwd, ref CompilationStartupInfoEx startup,
        out CompilationProcessInfo process);
    [DllImport("kernel32.dll", EntryPoint = "WaitForSingleObject", SetLastError = true)]
    private static extern uint CompilationWait(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll", EntryPoint = "GetExitCodeProcess", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationExitCode(IntPtr process, out uint code);
    [DllImport("kernel32.dll", EntryPoint = "CloseHandle")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationClose(IntPtr handle);
    [DllImport("kernel32.dll", EntryPoint = "SetFileInformationByHandle", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationSetFileInfo(SafeFileHandle file, int kind, IntPtr info, uint size);
    [DllImport("kernel32.dll", EntryPoint = "FlushFileBuffers", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationFlush(SafeFileHandle file);
    [DllImport("kernel32.dll", EntryPoint = "ReOpenFile", SetLastError = true)]
    private static extern SafeFileHandle CompilationReOpen(SafeFileHandle original,
        uint access, uint share, uint flags);
    [DllImport("kernel32.dll", EntryPoint = "ReadFile", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationRead(SafeFileHandle file, [Out] byte[] buffer,
        uint count, out uint read, IntPtr overlapped);
    [DllImport("kernel32.dll", EntryPoint = "WriteFile", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CompilationWrite(SafeFileHandle file, IntPtr buffer,
        uint count, out uint written, IntPtr overlapped);

    private static int CompilationRemaining(Stopwatch elapsed, int budgetMs)
    {
        return (int)Math.Max(0L, (long)budgetMs - elapsed.ElapsedMilliseconds);
    }
    private static void CheckCompilationBudget(Stopwatch elapsed, int budgetMs)
    {
        if (CompilationRemaining(elapsed, budgetMs) == 0) throw new InvalidOperationException();
    }
    private static string CompilationDirectory(string path)
    {
        if (String.IsNullOrWhiteSpace(path) || !Path.IsPathRooted(path)) throw new ArgumentException();
        string full = Path.GetFullPath(path).TrimEnd('\\');
        // Fixed local installation/cache paths, not UNC/device paths or streams.
        if (full.Length < 3 || !Char.IsLetter(full[0]) || full[1] != ':' || full[2] != '\\' ||
            full.IndexOf(':', 2) >= 0) throw new ArgumentException();
        foreach (string part in full.Substring(3).Split('\\'))
            if (part.Length == 0 || part.EndsWith(".") || part.EndsWith(" ")) throw new ArgumentException();
        return full;
    }
    private static FileInformation CompilationInspect(SafeFileHandle file, bool directory)
    {
        FileInformation info;
        if (!GetFileInformationByHandle(file, out info)) throw CompilationLastError("inspect-file");
        if ((info.Attributes & (uint)FileAttributes.ReparsePoint) != 0 ||
            ((info.Attributes & (uint)FileAttributes.Directory) != 0) != directory ||
            (!directory && info.Links != 1)) throw new IOException();
        return info;
    }
    private static void CheckCompilationPrivate(SafeFileHandle file, bool directory)
    {
        FileInformation info = CompilationInspect(file, directory);
        SecurityIdentifier user;
        using (WindowsIdentity identity = WindowsIdentity.GetCurrent()) user = identity.User;
        if (user == null) throw new UnauthorizedAccessException();
        RawSecurityDescriptor current = ReadSecurity(file);
        CheckRetainedOwner(file, info, current, user, "compilation-check", null);
        CheckAccess(current, new[] { user.Value, "S-1-5-18", "S-1-5-32-544" });
        if ((current.ControlFlags & ControlFlags.DiscretionaryAclProtected) == 0)
            throw new UnauthorizedAccessException("UNPROTECTED_PRIVATE_DACL");
    }
    private static SafeFileHandle PinCompilationDirectory(string path, List<SafeFileHandle> leases)
    {
        string cursor = Path.GetPathRoot(path);
        var components = new List<string> { cursor };
        foreach (string part in path.Substring(cursor.Length).Split('\\'))
        {
            cursor = Path.Combine(cursor, part);
            components.Add(cursor);
        }
        SafeFileHandle last = null;
        foreach (string component in components)
        {
            // Namespace-only ancestors are never made private or adopted. Keep
            // every component non-reparse and unrenamable until csc has exited.
            // FILE_LIST_DIRECTORY shares the FILE_READ_DATA bit. Unlike a
            // metadata-only handle, it makes no-DELETE sharing a real barrier.
            last = CreateFile(FullPath(component), ReadControl | 0x80u | FileReadData, 3, IntPtr.Zero,
                3, 0x02200000u, IntPtr.Zero);
            leases.Add(last);
            if (last.IsInvalid) throw CompilationLastError("pin-directory");
            CompilationInspect(last, true);
        }
        return last;
    }
    private static SafeFileHandle OpenCompilationWinner(string path)
    {
        // A concurrent publisher may still hold DELETE access on its creation
        // handle. This read-only receipt handle shares it; Node subsequently
        // compares this exact identity with both logical and physical stats.
        SafeFileHandle file = CreateFile(FullPath(path), ReadControl | 0x80u, 7,
            IntPtr.Zero, 3, 0x02200000u, IntPtr.Zero);
        if (file.IsInvalid)
        {
            int error = Marshal.GetLastWin32Error();
            file.Dispose();
            if (error == 2 || error == 3) return null;
            throw CompilationFailure("open-winner", error);
        }
        try
        {
            CheckCompilationPrivate(file, false);
            FileInformation info = CompilationInspect(file, false);
            if (info.SizeHigh == 0 && info.SizeLow == 0) throw new IOException();
            return file;
        }
        catch { file.Dispose(); throw; }
    }
    private static string CompilationQuote(string value)
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
    private static bool ConfirmCompilationExit(IntPtr job, IntPtr process, Stopwatch elapsed, int budgetMs)
    {
        if (CompilationWait(process, (uint)CompilationRemaining(elapsed, budgetMs)) != 0) return false;
        for (;;)
        {
            CompilationJobAccounting accounting;
            if (!CompilationQueryJob(job, 1, out accounting,
                (uint)Marshal.SizeOf(typeof(CompilationJobAccounting)), IntPtr.Zero)) return false;
            if (accounting.ActiveProcesses == 0) return true;
            int remaining = CompilationRemaining(elapsed, budgetMs);
            if (remaining == 0) return false;
            Thread.Sleep(Math.Min(10, remaining));
        }
    }
    private static void CompileNative(string name, string sourceDirectory, string output,
        List<string> sources, Stopwatch elapsed, int budgetMs, ref bool compilerExited)
    {
        string system = CompilationDirectory(Environment.GetEnvironmentVariable("SystemRoot"));
        string compiler = null;
        foreach (string framework in new[] { "Framework64", "Framework" })
        {
            string candidate = Path.Combine(system, "Microsoft.NET", framework, "v4.0.30319", "csc.exe");
            if (File.Exists(candidate)) { compiler = candidate; break; }
        }
        if (compiler == null) throw new FileNotFoundException();
        var command = new StringBuilder(CompilationQuote(compiler) + " /noconfig /nologo /target:" +
            (name == "host-launcher" ? "winexe" : "exe") + " /optimize+");
        // Disable implicit response files and reference only the selected
        // system Framework directory, never a cwd/LIB-supplied assembly.
        string frameworkDirectory = Path.GetDirectoryName(compiler);
        foreach (string assembly in new[] { "System.dll", "System.Core.dll" })
            command.Append(" ").Append(CompilationQuote("/r:" + Path.Combine(frameworkDirectory, assembly)));
        if (name != "host-launcher")
            command.Append(" ").Append(CompilationQuote("/r:" + Path.Combine(frameworkDirectory, "System.Web.Extensions.dll")));
        command.Append(" ").Append(CompilationQuote("/out:" + output));
        foreach (string source in sources) command.Append(" ").Append(CompilationQuote(source));
        IntPtr job = IntPtr.Zero, attributes = IntPtr.Zero, jobValue = IntPtr.Zero, handlesValue = IntPtr.Zero;
        var child = new CompilationProcessInfo();
        bool initialized = false, started = false;
        try
        {
            job = CompilationCreateJob(IntPtr.Zero, null); // Unnamed, non-inheritable, owned by this PS.
            if (job == IntPtr.Zero) throw CompilationLastError("create-job");
            var limits = new CompilationExtendedLimits();
            limits.Basic.Flags = 0x2000; // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            if (!CompilationSetJobLimits(job, 9, ref limits,
                (uint)Marshal.SizeOf(typeof(CompilationExtendedLimits))))
                throw CompilationLastError("set-job-limits");
            UIntPtr size = UIntPtr.Zero;
            CompilationInitializeAttributes(IntPtr.Zero, 2, 0, ref size);
            if (size == UIntPtr.Zero) throw CompilationLastError("initialize-attributes-size");
            attributes = Marshal.AllocHGlobal(checked((int)size.ToUInt64()));
            if (!CompilationInitializeAttributes(attributes, 2, 0, ref size))
                throw CompilationLastError("initialize-attributes");
            initialized = true;
            jobValue = Marshal.AllocHGlobal(IntPtr.Size);
            Marshal.WriteIntPtr(jobValue, job);
            // PROC_THREAD_ATTRIBUTE_JOB_LIST assigns ownership atomically with
            // CreateProcess. Even a kill between creation and return cannot
            // orphan a suspended compiler outside the job. No weaker fallback.
            if (!CompilationUpdateAttribute(attributes, 0, new UIntPtr(0x0002000du),
                jobValue, new UIntPtr((uint)IntPtr.Size), IntPtr.Zero, IntPtr.Zero))
                throw CompilationLastError("set-job-list");
            var security = new SecurityAttributes { Length = Marshal.SizeOf(typeof(SecurityAttributes)),
                Descriptor = IntPtr.Zero, Inherit = true };
            using (SafeFileHandle sink = CreateOwnedFile("NUL", 0xc0000000u, 3,
                ref security, 3, 0, IntPtr.Zero))
            {
                if (sink.IsInvalid) throw CompilationLastError("open-nul");
                handlesValue = Marshal.AllocHGlobal(IntPtr.Size);
                Marshal.WriteIntPtr(handlesValue, sink.DangerousGetHandle());
                // Only the NUL stream is inherited. Neither the output lease
                // nor the job handle can escape into csc or its descendants.
                if (!CompilationUpdateAttribute(attributes, 0, new UIntPtr(0x00020002u),
                    handlesValue, new UIntPtr((uint)IntPtr.Size), IntPtr.Zero, IntPtr.Zero))
                    throw CompilationLastError("set-handle-list");
                var startup = new CompilationStartupInfoEx();
                startup.Startup.Size = (uint)Marshal.SizeOf(typeof(CompilationStartupInfoEx));
                startup.Startup.Flags = 0x100; // STARTF_USESTDHANDLES: suppress all compiler content.
                startup.Startup.StdInput = startup.Startup.StdOutput = startup.Startup.StdError = sink.DangerousGetHandle();
                startup.Attributes = attributes;
                int cleanupReserve = Math.Min(1000, Math.Max(1, budgetMs / 4));
                if (CompilationRemaining(elapsed, budgetMs) <= cleanupReserve) throw new InvalidOperationException();
                // CREATE_NO_WINDOW | EXTENDED_STARTUPINFO_PRESENT. Fixed compiler
                // and sources only; no shell and no second compile attempt.
                if (!CompilationCreateProcess(compiler, command, IntPtr.Zero, IntPtr.Zero, true,
                    0x08080000u, IntPtr.Zero, sourceDirectory, ref startup, out child))
                    throw CompilationLastError("create-compiler");
                started = true;
                compilerExited = false;
                int waitMs = Math.Max(0, CompilationRemaining(elapsed, budgetMs) - cleanupReserve);
                uint waited = CompilationWait(child.Process, (uint)waitMs);
                if (waited == 0xffffffffu) throw CompilationLastError("wait-compiler");
                if (waited != 0) throw new InvalidOperationException();
                uint code;
                if (!CompilationExitCode(child.Process, out code)) throw CompilationLastError("compiler-exit-code");
                if (code != 0)
                {
                    var failure = new InvalidOperationException();
                    // A primitive UInt32 is Framework-serializable. Diagnostic
                    // attachment is optional and never replaces the failure.
                    try { failure.Data["CodexManage.CompilerExitCode"] = code; } catch { }
                    throw CompilationAt(failure, "compiler-exit-code");
                }
            }
        }
        finally
        {
            try
            {
                if (started)
                {
                    // Also fence any compiler descendants. Confirmation spends
                    // only the remainder of the original budget, never a new one.
                    CompilationTerminateJob(job, 1);
                    compilerExited = ConfirmCompilationExit(job, child.Process, elapsed, budgetMs);
                }
            }
            finally
            {
                if (child.Thread != IntPtr.Zero) CompilationClose(child.Thread);
                if (child.Process != IntPtr.Zero) CompilationClose(child.Process);
                if (job != IntPtr.Zero) CompilationClose(job);
                if (initialized) CompilationDeleteAttributes(attributes);
                if (attributes != IntPtr.Zero) Marshal.FreeHGlobal(attributes);
                if (jobValue != IntPtr.Zero) Marshal.FreeHGlobal(jobValue);
                if (handlesValue != IntPtr.Zero) Marshal.FreeHGlobal(handlesValue);
            }
        }
        if (!compilerExited) throw new InvalidOperationException();
        CheckCompilationBudget(elapsed, budgetMs);
    }
    private static bool PublishCompilation(SafeFileHandle reservation, SafeFileHandle directory, string cacheDirectory, string name,
        out int error)
    {
        CheckCompilationPrivate(directory, true);
        // The Win32 rename wrapper resolves relative names before issuing the
        // native request. A non-NULL RootDirectory can therefore reach the FS
        // together with an absolute name and fail with ERROR_INVALID_PARAMETER.
        // Use its documented absolute-name form. Every namespace component is
        // still pinned non-reparse without DELETE sharing until this completes;
        // the source remains B's original creation handle, never a path reopen.
        byte[] bytes = Encoding.Unicode.GetBytes(FullPath(Path.Combine(cacheDirectory, name)));
        int offset = Marshal.OffsetOf(typeof(CompilationRenameInfo), "FileName").ToInt32();
        int size = checked(Math.Max(Marshal.SizeOf(typeof(CompilationRenameInfo)), offset + bytes.Length + 2));
        IntPtr info = Marshal.AllocHGlobal(size);
        try
        {
            for (int i = 0; i < size; i++) Marshal.WriteByte(info, i, 0);
            // RootDirectory stays NULL; ReplaceIfExists stays FALSE.
            Marshal.WriteInt32(info, Marshal.OffsetOf(typeof(CompilationRenameInfo), "FileNameLength").ToInt32(), bytes.Length);
            Marshal.Copy(bytes, 0, IntPtr.Add(info, offset), bytes.Length);
            // FileRenameInfo (3), ReplaceIfExists=false. The SAME retained
            // creation handle is renamed into the pinned cache directory.
            if (CompilationSetFileInfo(reservation, 3, info, (uint)size)) { error = 0; return true; }
            error = Marshal.GetLastWin32Error();
            return false;
        }
        finally { Marshal.FreeHGlobal(info); }
    }
    private static void DiscardCompilation(SafeFileHandle reservation, string step)
    {
        IntPtr disposition = Marshal.AllocHGlobal(1);
        try
        {
            Marshal.WriteByte(disposition, 1); // FILE_DISPOSITION_INFO.DeleteFile (BOOLEAN).
            if (!CompilationSetFileInfo(reservation, 4, disposition, 1))
                throw CompilationLastError(step);
        }
        finally { Marshal.FreeHGlobal(disposition); }
    }
    private static SafeFileHandle OpenCompilerRead(string path, SafeFileHandle metadata, string identity)
    {
        // A still has its original metadata reservation. This is not a
        // pathname lock: compare two live handles before accepting any bytes.
        // The comparison bridges two simultaneously live handles, never an
        // ID recorded after releasing A or a pathname reopened for deletion.
        if (metadata == null || metadata.IsClosed || FileIdentity(metadata) != identity) throw new IOException();
        SafeFileHandle read = CreateFile(FullPath(path), 0x80000000u | ReadControl | 0x80u,
            7, IntPtr.Zero, 3, 0x00200000u, IntPtr.Zero);
        try
        {
            if (read.IsInvalid) throw CompilationLastError("open-compiler-read");
            CheckCompilationPrivate(read, false);
            if (FileIdentity(read) != identity || FileIdentity(metadata) != identity) throw new IOException();
            return read;
        }
        catch { read.Dispose(); throw; }
    }
    private static void WriteCompilationBytes(SafeFileHandle file, byte[] bytes, int count,
        Stopwatch elapsed, int budgetMs)
    {
        GCHandle pinned = GCHandle.Alloc(bytes, GCHandleType.Pinned);
        try
        {
            int offset = 0;
            while (offset < count)
            {
                CheckCompilationBudget(elapsed, budgetMs);
                uint written;
                if (!CompilationWrite(file, IntPtr.Add(pinned.AddrOfPinnedObject(), offset),
                    (uint)(count - offset), out written, IntPtr.Zero))
                    throw CompilationLastError("write-compiled");
                if (written == 0 || written > (uint)(count - offset)) throw new IOException();
                offset += (int)written;
            }
        }
        finally { pinned.Free(); }
    }
    private static uint CompilationOutputSize(SafeFileHandle file)
    {
        FileInformation info = CompilationInspect(file, false);
        if (info.SizeHigh != 0 || info.SizeLow == 0 || info.SizeLow > MaxCompilationOutputBytes)
            throw new IOException();
        return info.SizeLow;
    }
    private static void CopyCompilation(SafeFileHandle source, string sourceIdentity,
        SafeFileHandle publication, string publicationIdentity, Stopwatch elapsed, int budgetMs)
    {
        CheckCompilationPrivate(source, false);
        CheckCompilationPrivate(publication, false);
        if (FileIdentity(source) != sourceIdentity || FileIdentity(publication) != publicationIdentity)
            throw new IOException();
        FileInformation empty = CompilationInspect(publication, false);
        if (empty.SizeHigh != 0 || empty.SizeLow != 0) throw new IOException();
        uint size = CompilationOutputSize(source), copied = 0;
        byte[] buffer = new byte[65536];
        while (copied < size)
        {
            CheckCompilationBudget(elapsed, budgetMs);
            uint read, requested = Math.Min((uint)buffer.Length, size - copied);
            if (!CompilationRead(source, buffer, requested, out read, IntPtr.Zero))
                throw CompilationLastError("read-compiled");
            if (read == 0 || read > requested) throw new IOException();
            WriteCompilationBytes(publication, buffer, (int)read, elapsed, budgetMs);
            copied += read;
        }
        CheckCompilationBudget(elapsed, budgetMs);
        uint extra;
        if (!CompilationRead(source, buffer, 1, out extra, IntPtr.Zero))
            throw CompilationLastError("read-compiled-end");
        if (extra != 0 || CompilationOutputSize(source) != size || CompilationOutputSize(publication) != size ||
            FileIdentity(source) != sourceIdentity || FileIdentity(publication) != publicationIdentity)
            throw new IOException();
        CheckCompilationPrivate(source, false);
        CheckCompilationPrivate(publication, false);
        CheckCompilationBudget(elapsed, budgetMs);
        if (!CompilationFlush(publication)) throw CompilationLastError("flush-compiled");
        CheckCompilationBudget(elapsed, budgetMs);
    }
    private static void RecordCompilation(SafeFileHandle record, string entry, Stopwatch elapsed, int budgetMs)
    {
        // Entries consist only of fixed keys, product GUID filenames, identities
        // and numeric error codes. This private journal is not cleanup authority.
        CheckCompilationPrivate(record, false);
        byte[] bytes = Encoding.UTF8.GetBytes(entry + "\n");
        WriteCompilationBytes(record, bytes, bytes.Length, elapsed, budgetMs);
        CheckCompilationBudget(elapsed, budgetMs);
        if (!CompilationFlush(record)) throw CompilationLastError("flush-journal");
    }
    private static bool CleanupCompilerOutput(string path, string identity, ref SafeFileHandle metadata,
        ref SafeFileHandle read, bool compilerExited, Stopwatch elapsed, int budgetMs, out int error, out string failureStep)
    {
        error = 0;
        failureStep = null;
        if (metadata == null) return true;
        if (!compilerExited || CompilationRemaining(elapsed, budgetMs) == 0) return false;
        try
        {
            if (read == null) read = OpenCompilerRead(path, metadata, identity);
            // Keep A's read handle alive across lease release and ReOpenFile.
            // A renamed/replaced pathname cannot redirect this deletion to B.
            metadata.Dispose();
            metadata = null;
            using (SafeFileHandle deletion = CompilationReOpen(read, 0x00010000u | ReadControl,
                7, 0x00200000u))
            {
                if (deletion.IsInvalid) throw CompilationLastError("reopen-compiler");
                CheckCompilationPrivate(deletion, false);
                DiscardCompilation(deletion, "discard-compiler");
            }
            return true;
        }
        catch (Exception failure)
        {
            var native = failure as Win32Exception;
            if (native != null) error = native.NativeErrorCode;
            failureStep = CompilationFailureStep(failure) ?? "cleanup-compiler";
            // Leave A plus its pre-existing journal. No pathname fallback,
            // owner/ACL change, sharing downgrade or uncertain deletion retry.
            return false;
        }
    }

    public static string PrepareNative(string name, string sourceDirectory, string cacheDirectory, int budgetMs)
    {
        var elapsed = Stopwatch.StartNew();
        if ((name != "private-state" && name != "trace-state" && name != "host-launcher") ||
            budgetMs <= 0 || budgetMs > 30000) throw new ArgumentException();
        sourceDirectory = CompilationDirectory(sourceDirectory);
        cacheDirectory = CompilationDirectory(cacheDirectory);
        var directories = new List<SafeFileHandle>();
        var sourceLeases = new List<FileStream>();
        SafeFileHandle compilerMetadata = null, compilerRead = null, publication = null, record = null;
        string compilerTemporary = null, compilerIdentity = null;
        bool published = false, compilerExited = true;
        Exception preparationFailure = null;
        string preparationStep = "pin-source-directory";
        try
        {
            PinCompilationDirectory(sourceDirectory, directories);
            var sourceNames = new List<string> { name + ".cs" };
            if (name == "trace-state") sourceNames.Add("trace-process.cs");
            // Exactly the Node Set insertion order; raw bytes, no separators.
            if (name != "private-state") sourceNames.Add("private-state.cs");
            sourceNames.Add("private-state-compilation.cs");
            preparationStep = "hash-source";
            string digest;
            using (var hash = SHA256.Create())
            {
                byte[] buffer = new byte[65536];
                foreach (string fileName in sourceNames)
                {
                    CheckCompilationBudget(elapsed, budgetMs);
                    var handle = CreateFile(FullPath(Path.Combine(sourceDirectory, fileName)), 0x80000000u,
                        1, IntPtr.Zero, 3, 0x00200000u, IntPtr.Zero);
                    FileStream source = null;
                    try
                    {
                        if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
                        CompilationInspect(handle, false);
                        source = new FileStream(handle, FileAccess.Read, buffer.Length, false);
                        sourceLeases.Add(source);
                    }
                    catch { if (source != null) source.Dispose(); else handle.Dispose(); throw; }
                    int count;
                    while ((count = source.Read(buffer, 0, buffer.Length)) != 0)
                    {
                        CheckCompilationBudget(elapsed, budgetMs);
                        hash.TransformBlock(buffer, 0, count, buffer, 0);
                    }
                }
                hash.TransformFinalBlock(new byte[0], 0, 0);
                digest = BitConverter.ToString(hash.Hash).Replace("-", "").ToLowerInvariant();
            }
            string local = CompilationDirectory(Environment.GetEnvironmentVariable("LOCALAPPDATA"));
            string expected = Path.Combine(local, "CodexManage", "native-helpers", digest);
            if (!String.Equals(cacheDirectory, expected, StringComparison.OrdinalIgnoreCase)) throw new ArgumentException();
            CheckCompilationBudget(elapsed, budgetMs);
            preparationStep = "create-cache";
            Execute("create-directory", cacheDirectory);
            preparationStep = "pin-cache";
            SafeFileHandle directory = PinCompilationDirectory(cacheDirectory, directories);
            CheckCompilationPrivate(directory, true);
            string executable = Path.Combine(cacheDirectory, name + ".exe");
            using (SafeFileHandle existing = OpenCompilationWinner(executable))
            {
                if (existing != null)
                {
                    CheckCompilationBudget(elapsed, budgetMs);
                    return "PRIVATE_STATE_NATIVE_READY:" + FileIdentity(existing);
                }
            }
            CheckCompilationBudget(elapsed, budgetMs);
            string compilerName = name + "." + Guid.NewGuid().ToString("D") + ".compile.tmp.exe";
            string publicationName = name + "." + Guid.NewGuid().ToString("D") + ".publish.tmp.exe";
            compilerTemporary = Path.Combine(cacheDirectory, compilerName);
            // Persist precise intended paths before A can exist. Created events
            // below distinguish owned objects from intentions. No journal ID
            // authorizes a future pathname deletion, even on ID-reusing FSes.
            preparationStep = "create-journal";
            record = CreatePrivateFileHandle(compilerTemporary + ".lease.jsonl", true);
            RecordCompilation(record, "{\"version\":1,\"compiler\":\"" + compilerName +
                "\",\"publication\":\"" + publicationName + "\",\"cleanupByPath\":false}", elapsed, budgetMs);
            preparationStep = "create-compiler-output";
            compilerMetadata = CreatePrivateFileHandleForPurpose(compilerTemporary, PrivateFilePurpose.CompilerOutput);
            compilerIdentity = FileIdentity(compilerMetadata);
            RecordCompilation(record, "{\"created\":\"compiler\",\"identity\":\"" + compilerIdentity + "\"}", elapsed, budgetMs);
            var compileSources = new List<string> { Path.Combine(sourceDirectory, name + ".cs") };
            if (name == "trace-state") compileSources.Add(Path.Combine(sourceDirectory, "trace-process.cs"));
            if (name == "private-state") compileSources.Add(Path.Combine(sourceDirectory, "private-state-compilation.cs"));
            // A grants only READ_CONTROL | READ_ATTRIBUTES. In particular it
            // must not borrow NodeHandoff's FILE_READ_DATA access: Framework
            // csc can refuse that existing output with CS0016. Publication and
            // cleanup still use A's retained identity and B's own creation FD.
            preparationStep = "compile";
            CompileNative(name, sourceDirectory, compilerTemporary, compileSources, elapsed, budgetMs, ref compilerExited);
            preparationStep = "check-compiler-output";
            CheckCompilationPrivate(compilerMetadata, false);
            CompilationOutputSize(compilerMetadata);
            preparationStep = "open-compiler-read";
            compilerRead = OpenCompilerRead(compilerTemporary, compilerMetadata, compilerIdentity);
            preparationStep = "create-publication";
            publication = CreatePrivateFileHandle(Path.Combine(cacheDirectory, publicationName), true);
            string identity = FileIdentity(publication);
            RecordCompilation(record, "{\"created\":\"publication\",\"identity\":\"" + identity + "\"}", elapsed, budgetMs);
            preparationStep = "copy-compiled";
            CopyCompilation(compilerRead, compilerIdentity, publication, identity, elapsed, budgetMs);
            CheckCompilationBudget(elapsed, budgetMs);
            int publicationError;
            preparationStep = "publish";
            published = PublishCompilation(publication, directory, cacheDirectory, name + ".exe", out publicationError);
            if (published)
            {
                CheckCompilationPrivate(publication, false);
                if (FileIdentity(publication) != identity) throw new IOException();
                using (SafeFileHandle receipt = OpenCompilationWinner(executable))
                    if (receipt == null || FileIdentity(receipt) != identity) throw new IOException();
                CheckCompilationBudget(elapsed, budgetMs);
                return "PRIVATE_STATE_NATIVE_READY:" + identity;
            }
            using (SafeFileHandle winner = OpenCompilationWinner(executable))
            {
                // An existing winner can produce different Win32 collision /
                // sharing errors while its publisher still holds the lease.
                // Accept only a freshly checked target; otherwise retain the
                // actual failed rename code. Never retry or replace anything.
                if (winner == null) throw CompilationFailure("publish", publicationError);
                CheckCompilationBudget(elapsed, budgetMs);
                return "PRIVATE_STATE_NATIVE_READY:" + FileIdentity(winner);
            }
        }
        catch (Exception failure)
        {
            preparationFailure = CompilationAt(failure, preparationStep);
            throw;
        }
        finally
        {
            try
            {
                int compilerCleanupError;
                string compilerCleanupStep;
                bool compilerCleaned = CleanupCompilerOutput(compilerTemporary, compilerIdentity, ref compilerMetadata,
                    ref compilerRead, compilerExited, elapsed, budgetMs, out compilerCleanupError, out compilerCleanupStep);
                if (!compilerCleaned && preparationFailure != null)
                {
                    Exception cleanup = compilerCleanupError != 0
                        ? (Exception)CompilationFailure(compilerCleanupStep ?? "cleanup-compiler", compilerCleanupError)
                        : CompilationAt(new IOException(), compilerCleanupStep ?? "cleanup-compiler");
                    CompilationCleanupFailure(preparationFailure, cleanup);
                }
                bool publicationCleaned = publication == null || published;
                Exception publicationCleanupFailure = null;
                if (!publicationCleaned)
                {
                    try
                    {
                        // csc never opens B. Its failed/losing copy can always
                        // be deleted through the original WRITE/DELETE handle.
                        DiscardCompilation(publication, "discard-publication");
                        publicationCleaned = true;
                    }
                    catch (Exception failure) { publicationCleanupFailure = failure; }
                }
                if (record != null)
                {
                    if (compilerCleaned && publicationCleaned) DiscardCompilation(record, "discard-journal");
                    else
                    {
                        try
                        {
                            var native = publicationCleanupFailure as Win32Exception;
                            RecordCompilation(record, "{\"retainedCompiler\":" + (compilerCleaned ? "false" : "true") +
                                ",\"compilerExited\":" + (compilerExited ? "true" : "false") +
                                ",\"compilerCleanupCode\":" + compilerCleanupError.ToString(System.Globalization.CultureInfo.InvariantCulture) +
                                ",\"retainedPublication\":" + (publicationCleaned ? "false" : "true") +
                                ",\"publicationPublished\":" + (published ? "true" : "false") +
                                ",\"publicationCleanupCode\":" + (native == null ? "0" : native.NativeErrorCode.ToString(System.Globalization.CultureInfo.InvariantCulture)) +
                                "}", elapsed, budgetMs);
                        }
                        catch { } // The initial paths/created events remain; never renew the budget to log.
                    }
                }
                if (publicationCleanupFailure != null) throw publicationCleanupFailure;
            }
            catch (Exception cleanup)
            {
                if (preparationFailure == null) throw;
                CompilationCleanupFailure(preparationFailure, cleanup);
            }
            finally
            {
                if (compilerRead != null) compilerRead.Dispose();
                if (compilerMetadata != null) compilerMetadata.Dispose();
                if (publication != null) publication.Dispose();
                if (record != null) record.Dispose();
                foreach (FileStream source in sourceLeases) source.Dispose();
                for (int i = directories.Count - 1; i >= 0; i--) directories[i].Dispose();
            }
        }
    }
}
