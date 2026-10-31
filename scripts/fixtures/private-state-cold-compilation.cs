using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Security.AccessControl;
using System.Security.Cryptography;
using Microsoft.Win32.SafeHandles;

// Test-only partial class: no new action is added to the production helper.
public static partial class CodexManagePrivateStateSecurity
{
    private static string ColdFixtureDigest(string source, string name)
    {
        var names = new List<string> { name + ".cs" };
        if (name == "trace-state") names.Add("trace-process.cs");
        if (name != "private-state") names.Add("private-state.cs");
        names.Add("private-state-compilation.cs");
        using (var hash = SHA256.Create())
        {
            foreach (string item in names)
            {
                byte[] bytes = File.ReadAllBytes(Path.Combine(source, item));
                hash.TransformBlock(bytes, 0, bytes.Length, bytes, 0);
            }
            hash.TransformFinalBlock(new byte[0], 0, 0);
            return BitConverter.ToString(hash.Hash).Replace("-", "").ToLowerInvariant();
        }
    }

    private static void ColdFixtureIdentityBoundary(string root, Stopwatch elapsed, int budget)
    {
        Execute("create-directory", root);
        var directories = new List<SafeFileHandle>();
        try
        {
            PinCompilationDirectory(root, directories);
            bool refused = false;
            try { Directory.Move(root, root + ".moved"); }
            catch (IOException error) { refused = (error.HResult & 0xffff) == 32; }
            if (!refused) throw new InvalidOperationException();
        }
        finally
        {
            for (int i = directories.Count - 1; i >= 0; i--) directories[i].Dispose();
        }
        Directory.Move(root, root + ".moved");
        Directory.Move(root + ".moved", root);

        string path = Path.Combine(root, "compiler-A.exe");
        string moved = path + ".moved";
        SafeFileHandle metadata = null, read = null;
        try
        {
            metadata = CreatePrivateFileHandleForPurpose(path, PrivateFilePurpose.CompilerOutput);
            string identity = FileIdentity(metadata);
            // Synthetic completed compiler output, not another compiler run.
            File.WriteAllBytes(path, new byte[] { 77, 90, 1, 2 });
            read = OpenCompilerRead(path, metadata, identity);
            File.Move(path, moved);
            byte[] kept = new byte[] { 66, 45, 107, 101, 101, 112 };
            string otherIdentity, descriptor;
            using (SafeFileHandle other = CreatePrivateFileHandle(path, true))
            {
                WriteCompilationBytes(other, kept, kept.Length, elapsed, budget);
                otherIdentity = FileIdentity(other);
                descriptor = ReadSecurity(other).GetSddlForm(
                    AccessControlSections.Owner | AccessControlSections.Group | AccessControlSections.Access);
            }
            bool refused = false;
            try { using (SafeFileHandle unexpected = OpenCompilerRead(path, metadata, identity)) { } }
            catch (IOException) { refused = true; }
            if (!refused) throw new InvalidOperationException();
            int cleanupCode;
            string cleanupStep;
            if (!CleanupCompilerOutput(path, identity, ref metadata, ref read, true,
                elapsed, budget, out cleanupCode, out cleanupStep)) throw new InvalidOperationException();
            read.Dispose(); read = null;
            if (File.Exists(moved)) throw new InvalidOperationException();
            using (SafeFileHandle other = OpenCompilationWinner(path))
            {
                if (other == null || FileIdentity(other) != otherIdentity ||
                    ReadSecurity(other).GetSddlForm(AccessControlSections.Owner |
                        AccessControlSections.Group | AccessControlSections.Access) != descriptor)
                    throw new InvalidOperationException();
            }
            byte[] after = File.ReadAllBytes(path);
            if (after.Length != kept.Length) throw new InvalidOperationException();
            for (int i = 0; i < kept.Length; i++)
                if (after[i] != kept[i]) throw new InvalidOperationException();
        }
        finally
        {
            if (read != null) read.Dispose();
            if (metadata != null) metadata.Dispose();
        }
    }

    private static uint ColdFixtureCompilerFailure(string source, string local, Stopwatch elapsed, int budget)
    {
        Execute("create-directory", source);
        File.WriteAllText(Path.Combine(source, "private-state.cs"), "#error fixture-only-compiler-failure\n");
        File.WriteAllText(Path.Combine(source, "private-state-compilation.cs"), "// fixture-only\n");
        string cache = Path.Combine(local, "CodexManage", "native-helpers", ColdFixtureDigest(source, "private-state"));
        if (Directory.Exists(cache)) throw new InvalidOperationException();
        uint status = 0;
        bool refused = false;
        try { PrepareNative("private-state", source, cache, CompilationRemaining(elapsed, budget)); }
        catch (InvalidOperationException error)
        {
            if (CompilationFailureStep(error) != "compiler-exit-code") throw;
            object value = error.Data["CodexManage.CompilerExitCode"];
            if (!(value is uint) || (uint)value == 0) throw;
            status = (uint)value;
            refused = true;
        }
        if (!refused || File.Exists(Path.Combine(cache, "private-state.exe")) ||
            Directory.GetFiles(cache).Length != 0) throw new InvalidOperationException();
        return status;
    }

    public static int RunColdCompilationFixture(string[] args)
    {
        string phase = "input", name = null;
        var elapsed = Stopwatch.StartNew();
        const int budget = 30000;
        bool invokedNative = false;
        try
        {
            if (args.Length != 3 || (args[2] != "private-state" && args[2] != "trace-state" &&
                args[2] != "host-launcher")) throw new ArgumentException();
            string source = Path.GetFullPath(args[0]), local = Path.GetFullPath(args[1]);
            name = args[2];
            if (Directory.Exists(local)) throw new InvalidOperationException();
            // Only this fixture child uses an exclusive cache. SystemRoot,
            // module search path, identity and all process budgets are retained.
            Environment.SetEnvironmentVariable("LOCALAPPDATA", local);
            string digest = ColdFixtureDigest(source, name);
            string cache = Path.Combine(local, "CodexManage", "native-helpers", digest);
            if (Directory.Exists(cache)) throw new InvalidOperationException();
            phase = "cold-prepare";
            invokedNative = true;
            string cold = PrepareNative(name, source, cache, CompilationRemaining(elapsed, budget));
            phase = "cold-result";
            string executable = Path.Combine(cache, name + ".exe");
            using (SafeFileHandle check = OpenCompilationWinner(executable))
                if (check == null || cold != "PRIVATE_STATE_NATIVE_READY:" + FileIdentity(check))
                    throw new InvalidOperationException();
            using (var bytes = File.OpenRead(executable))
                if (bytes.ReadByte() != 77 || bytes.ReadByte() != 90) throw new InvalidOperationException();
            phase = "warm-prepare";
            string warm = PrepareNative(name, source, cache, CompilationRemaining(elapsed, budget));
            if (warm != cold) throw new InvalidOperationException();
            bool boundary = name == "private-state";
            uint compilerFailure = 0;
            if (boundary)
            {
                phase = "identity-boundary";
                ColdFixtureIdentityBoundary(Path.Combine(local, "identity-fixture"), elapsed, budget);
                phase = "controlled-compiler-failure";
                compilerFailure = ColdFixtureCompilerFailure(Path.Combine(local, "invalid-source"), local, elapsed, budget);
            }
            CheckCompilationBudget(elapsed, budget);
            Console.WriteLine("{\"kind\":\"native-cold-compilation\",\"passed\":true,\"native\":\"" + name +
                "\",\"phase\":\"complete\",\"coldCacheAbsentBefore\":true,\"coldPrepared\":true," +
                "\"cacheDigest\":\"" + digest + "\",\"nativeIdentity\":\"" +
                cold.Substring("PRIVATE_STATE_NATIVE_READY:".Length) + "\"," +
                "\"warmSameIdentity\":true,\"privatePeChecked\":true,\"identityBoundary\":" +
                (boundary ? "true" : "null") + ",\"identityBoundarySynthetic\":" +
                (boundary ? "true" : "null") + ",\"controlledCompilerFailureCode\":" +
                (boundary ? compilerFailure.ToString(CultureInfo.InvariantCulture) : "null") +
                ",\"controlledCompilerFailurePreserved\":" + (boundary ? "true" : "null") +
                ",\"exclusiveChildLocalAppData\":true," +
                "\"budgetMs\":30000,\"existingStateRead\":false,\"formalHostStarted\":false," +
                "\"productionHelperExecutableStarted\":false," +
                "\"windowsPInvokeExecuted\":true}");
            return 0;
        }
        catch (Exception error)
        {
            string step = null, status = "null";
            try
            {
                step = CompilationFailureStep(error);
                if (step != null && !System.Text.RegularExpressions.Regex.IsMatch(step, "^[a-z][a-z0-9-]{0,39}$")) step = null;
                object value = error.Data["CodexManage.CompilerExitCode"];
                if (value is uint) status = ((uint)value).ToString(CultureInfo.InvariantCulture);
            }
            catch { }
            Console.WriteLine("{\"kind\":\"native-cold-compilation\",\"passed\":false,\"native\":" +
                (name == null ? "null" : "\"" + name + "\"") + ",\"phase\":\"" + phase +
                "\",\"failureType\":\"" + error.GetType().Name + "\",\"nativeStep\":" +
                (step == null ? "null" : "\"" + step + "\"") + ",\"compilerExitCode\":" + status +
                ",\"windowsPInvokeExecuted\":null,\"nativePreparationInvoked\":" +
                (invokedNative ? "true" : "false") + "}");
            return 1;
        }
    }
}

public static class PrivateStateColdCompilationHarness
{
    public static int Main(string[] args) { return CodexManagePrivateStateSecurity.RunColdCompilationFixture(args); }
}
