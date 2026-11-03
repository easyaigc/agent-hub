using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;
[assembly: DefaultDllImportSearchPaths(DllImportSearchPath.System32)]

// Fixed synthetic Task-domain runner. This executable has no Host, Codex,
// arbitrary command or arbitrary PID termination operation. Compilation uses
// the same retained-source/compiler-Job/publication path as the P1 helpers.
public static class CodexManageHostDomain
{
    const string Uuid = "^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$";
    const uint QueryProcess = 0x00101000;
    [DataContract] public sealed class Plan
    {
        [DataMember] public string format;
        [DataMember] public string operationId;
        [DataMember] public string preparationId;
        [DataMember] public string userSid;
        [DataMember] public int controllerPid;
        [DataMember] public string controllerCreation;
        [DataMember] public string runnerSha256;
        [DataMember] public string limitedScript;
        [DataMember] public string limitedSha256;
        [DataMember] public string commonSha256;
    }
    [DataContract] public sealed class Entered
    {
        [DataMember] public string operationId;
        [DataMember] public int pid;
        [DataMember] public string creation;
        [DataMember] public int argumentCharacters;
        [DataMember] public bool adminRole;
        [DataMember] public string planSha256;
    }
    [DataContract] public sealed class Failure
    {
        [DataMember] public string phase;
        [DataMember] public string type;
        [DataMember] public string hresult;
        [DataMember] public int? workerExitCode;
        [DataMember] public string code;
    }
    [StructLayout(LayoutKind.Sequential)] struct LimitsBasic { public long A, B; public uint Flags; public UIntPtr Min, Max; public uint Count; public UIntPtr Affinity; public uint Priority, Scheduling; }
    [StructLayout(LayoutKind.Sequential)] struct Limits { public LimitsBasic Basic; public ulong A, B, C, D, E, F; public UIntPtr G, H, I, J; }
    [StructLayout(LayoutKind.Sequential)] struct Accounting { public long A, B, C, D; public uint Faults, Total, Active, Terminated; }
    [StructLayout(LayoutKind.Sequential)] struct SecurityAttributes { public uint Length; public IntPtr Descriptor; public int Inherit; }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr CreateJobObjectW(ref SecurityAttributes attributes, string name);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern IntPtr OpenJobObjectW(uint access, bool inherit, string name);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool SetInformationJobObject(IntPtr job, int kind, ref Limits limits, uint bytes);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool QueryInformationJobObject(IntPtr job, int kind, out Accounting value, uint bytes, IntPtr returned);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool TerminateJobObject(IntPtr job, uint exitCode);
    [DllImport("kernel32.dll", SetLastError=true)] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetProcessTimes(IntPtr process, out long creation, out long exit, out long kernel, out long user);
    [DllImport("kernel32.dll", SetLastError=true)] static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr value);
    [DllImport("advapi32.dll", CharSet=CharSet.Unicode, SetLastError=true)] static extern bool ConvertStringSecurityDescriptorToSecurityDescriptorW(string text, uint revision, out IntPtr descriptor, IntPtr size);
    static void Need(bool value, string code) { if (!value) throw new InvalidOperationException(code); }
    static void Win(bool value) { if (!value) throw new Win32Exception(Marshal.GetLastWin32Error()); }
    static string User() { using (var identity = WindowsIdentity.GetCurrent()) return identity.User.Value; }
    static bool Admin() { using (var identity = WindowsIdentity.GetCurrent()) return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator); }
    static string JobName(string operation) { Need(Regex.IsMatch(operation, Uuid), "HOST_DOMAIN_ID_REJECTED"); return "Local\\AgentHubRegularVerify-" + operation; }
    public static string DirectoryFor(string preparation, string operation)
    {
        Need(Regex.IsMatch(preparation, Uuid) && Regex.IsMatch(operation, Uuid) && preparation != operation, "HOST_DOMAIN_ID_REJECTED");
        return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".config", "agent-hub", "host-regular-recovery", preparation, "domain-" + operation);
    }
    static long Creation(IntPtr process) { long c, e, k, u; Win(GetProcessTimes(process, out c, out e, out k, out u)); return c; }
    static void Write(string path, object value) { using (var stream = new MemoryStream()) { new DataContractJsonSerializer(value.GetType()).WriteObject(stream, value); CodexManagePrivateStateSecurity.CreateRecoveryFile(path,stream.ToArray()); } }
    public static string Quote(string value)
    {
        var b = new StringBuilder("\""); int slashes = 0;
        foreach (char c in value) { if (c == '\\') { slashes++; continue; } if (c == '"') b.Append('\\', slashes * 2 + 1); else b.Append('\\', slashes); b.Append(c); slashes = 0; }
        return b.Append('\\', slashes * 2).Append('"').ToString();
    }
    public static string Arguments(string planPath, string planSha)
    { Need(Regex.IsMatch(planSha, "^[a-f0-9]{64}$"), "HOST_DOMAIN_PLAN_REJECTED"); return "fixture " + Quote(planPath) + " " + planSha + " " + new string('v', 11264); }

    public sealed class Scope : IDisposable
    {
        IntPtr job;
        readonly List<IntPtr> processes = new List<IntPtr>();
        public string OperationId { get; private set; }
        public string ControllerCreation { get; private set; }
        public int ControllerPid { get; private set; }
        public Scope(string operation)
        {
            Need(IntPtr.Size == 8, "HOST_DOMAIN_X64_REQUIRED"); OperationId = operation;
            using (var process = Process.GetCurrentProcess()) { ControllerPid = process.Id; ControllerCreation = Creation(process.Handle).ToString(System.Globalization.CultureInfo.InvariantCulture); }
            IntPtr sd; Win(ConvertStringSecurityDescriptorToSecurityDescriptorW("O:" + User() + "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;" + User() + ")", 1, out sd, IntPtr.Zero));
            try {
                var attributes = new SecurityAttributes { Length = (uint)Marshal.SizeOf(typeof(SecurityAttributes)), Descriptor = sd, Inherit = 0 };
                job = CreateJobObjectW(ref attributes, JobName(operation)); int error = Marshal.GetLastWin32Error();
                Need(job != IntPtr.Zero && error != 183, "HOST_DOMAIN_JOB_ALREADY_EXISTS_OR_UNAVAILABLE");
                var limits = new Limits(); limits.Basic.Flags = 0x2000;
                Win(SetInformationJobObject(job, 9, ref limits, (uint)Marshal.SizeOf(typeof(Limits))));
            } catch { if (job != IntPtr.Zero) { CloseHandle(job); job = IntPtr.Zero; } throw; }
            finally { LocalFree(sd); }
        }
        public void Retain(uint pid, string creation)
        {
            IntPtr handle = OpenProcess(QueryProcess, false, pid); Need(handle != IntPtr.Zero, "HOST_DOMAIN_PROCESS_OPEN_FAILED");
            try { bool member; Win(IsProcessInJob(handle, job, out member)); Need(member && Creation(handle).ToString(System.Globalization.CultureInfo.InvariantCulture) == creation && WaitForSingleObject(handle, 0) == 258, "HOST_DOMAIN_PROCESS_ANCHOR_REJECTED"); processes.Add(handle); handle = IntPtr.Zero; }
            finally { if (handle != IntPtr.Zero) CloseHandle(handle); }
        }
        public bool StopAndConfirm()
        {
            Need(job != IntPtr.Zero, "HOST_DOMAIN_JOB_CLOSED"); Win(TerminateJobObject(job, 191));
            var timer = Stopwatch.StartNew();
            while (timer.ElapsedMilliseconds < 10000) {
                Accounting value; Win(QueryInformationJobObject(job, 1, out value, (uint)Marshal.SizeOf(typeof(Accounting)), IntPtr.Zero));
                bool exited = true; foreach (IntPtr process in processes) exited &= WaitForSingleObject(process, 0) == 0;
                if (value.Active == 0 && processes.Count == 3 && exited) return true;
                Thread.Sleep(20);
            }
            return false;
        }
        public void Dispose() { if (job != IntPtr.Zero) { TerminateJobObject(job, 191); CloseHandle(job); job = IntPtr.Zero; } foreach (IntPtr p in processes) CloseHandle(p); processes.Clear(); }
    }
    public static Scope Begin(string operation) { return new Scope(operation); }
    public static int Main(string[] args)
    {
        string directory = null, phase = "arguments"; Process child = null;
        IntPtr controller = IntPtr.Zero, job = IntPtr.Zero;
        int? workerExitCode = null;
        var leases = new List<IDisposable>();
        try {
            Need(args.Length == 4 && args[0] == "fixture" && args[3] == new string('v',11264), "HOST_DOMAIN_ARGUMENTS_REJECTED");
            string planPath = Path.GetFullPath(args[1]);
            var planLease = CodexManagePrivateStateSecurity.OpenRecoveryRead(planPath, 65536); leases.Add(planLease);
            Need(planLease.Sha256 == args[2], "HOST_DOMAIN_PLAN_CHANGED");
            Plan plan;
            using (var stream = new MemoryStream(planLease.Bytes)) plan = (Plan)new DataContractJsonSerializer(typeof(Plan)).ReadObject(stream);
            Need(plan != null && plan.format == "windows-host-regular-domain-plan-v1", "HOST_DOMAIN_PLAN_REJECTED");
            string operation = plan.operationId, preparation = plan.preparationId;
            directory = DirectoryFor(preparation, operation);
            Need(String.Equals(planPath, Path.Combine(directory, "plan.json"), StringComparison.OrdinalIgnoreCase) && User() == plan.userSid && !Admin(), "HOST_DOMAIN_LIMITED_IDENTITY_REJECTED");
            phase = "controller";
            controller = OpenProcess(QueryProcess, false, checked((uint)plan.controllerPid));
            Need(controller != IntPtr.Zero && Creation(controller).ToString(System.Globalization.CultureInfo.InvariantCulture) == plan.controllerCreation && WaitForSingleObject(controller,0) == 258, "HOST_DOMAIN_CONTROLLER_UNAVAILABLE");
            job = OpenJobObjectW(5, false, JobName(operation)); Need(job != IntPtr.Zero, "HOST_DOMAIN_CONTROLLER_JOB_UNAVAILABLE");
            using (var self = Process.GetCurrentProcess()) Win(AssignProcessToJobObject(job,self.Handle));
            // The controller owns the sole persistent Job handle. This member
            // must not keep that handle alive when the controller exits.
            CloseHandle(job); job = IntPtr.Zero;
            Need(WaitForSingleObject(controller,0) == 258, "HOST_DOMAIN_CONTROLLER_GONE");
            phase = "resources";
            var selfLease = CodexManagePrivateStateSecurity.OpenRecoveryRead(Assembly.GetExecutingAssembly().Location, 1048576); leases.Add(selfLease);
            Need(selfLease.Sha256 == plan.runnerSha256, "HOST_DOMAIN_RUNNER_CHANGED");
            string limited = plan.limitedScript;
            var scriptLease = CodexManagePrivateStateSecurity.OpenRecoveryCodeRead(limited,1048576,plan.limitedSha256); leases.Add(scriptLease);
            Need(scriptLease.Sha256 == plan.limitedSha256, "HOST_DOMAIN_SCRIPT_CHANGED");
            var commonLease = CodexManagePrivateStateSecurity.OpenRecoveryCodeRead(Path.Combine(Path.GetDirectoryName(limited), "windows-host-regular-domain-common.ps1"),1048576,plan.commonSha256); leases.Add(commonLease);
            Need(commonLease.Sha256 == plan.commonSha256, "HOST_DOMAIN_SCRIPT_CHANGED");
            using (var self = Process.GetCurrentProcess()) Write(Path.Combine(directory,"entered.json"), new Entered { operationId=operation, pid=self.Id, creation=Creation(self.Handle).ToString(System.Globalization.CultureInfo.InvariantCulture), argumentCharacters=args[3].Length, adminRole=false, planSha256=planLease.Sha256 });
            phase = "limited-worker";
            var info = new ProcessStartInfo(Path.Combine(Environment.SystemDirectory,"WindowsPowerShell","v1.0","powershell.exe"), "-NoProfile -NonInteractive -File " + Quote(limited) + " -Plan " + Quote(planPath));
            info.UseShellExecute=false; info.CreateNoWindow=true; info.WorkingDirectory=directory;
            info.EnvironmentVariables.Remove("PSModulePath"); info.EnvironmentVariables.Remove("PSMODULEPATH");
            child=Process.Start(info); Need(child != null, "HOST_DOMAIN_LIMITED_START_FAILED");
            while (!child.WaitForExit(100)) Need(WaitForSingleObject(controller,0) == 258, "HOST_DOMAIN_CONTROLLER_GONE");
            workerExitCode = child.ExitCode;
            Need(child.ExitCode == 0, "HOST_DOMAIN_LIMITED_WORKER_FAILED");
            return 0;
        } catch (Exception e) {
            if (directory != null) try { Write(Path.Combine(directory,"failure.json"), new Failure { phase=phase, type=e.GetType().FullName, hresult=String.Format("0x{0:X8}",e.HResult), workerExitCode=workerExitCode, code="HOST_DOMAIN_NATIVE_FAILURE" }); } catch { }
            return 190;
        } finally {
            if (child != null) { try { if (!child.HasExited) child.Kill(); child.WaitForExit(3000); } catch { } child.Dispose(); }
            if (job != IntPtr.Zero) CloseHandle(job); if (controller != IntPtr.Zero) CloseHandle(controller);
            for (int i=leases.Count-1;i>=0;i--) leases[i].Dispose();
        }
    }
}
