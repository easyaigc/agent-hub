// Fixed package resource. Compile this source unchanged; never generate code from config.
// .NET Framework / PowerShell 5 compatible. This executable is NOT a Codex executor.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

public static class AgentHubNativeSampleLauncher
{
    const int ConfigLimit = 16 * 1024;
    const string ConfigVariable = "AGENT_HUB_NATIVE_SUGGESTIONS_CONFIG";

    static void Require(bool condition) { if (!condition) throw new InvalidDataException(); }

    static string PathText(string path)
    {
        Require(!String.IsNullOrWhiteSpace(path) && path.Length >= 3 &&
            Char.IsLetter(path[0]) && path[1] == ':' && path[2] == '\\' &&
            path.IndexOf(':', 2) < 0 && path.IndexOf('/') < 0 && path.IndexOf('\0') < 0);
        string full = Path.GetFullPath(path);
        Require(String.Equals(full, path, StringComparison.OrdinalIgnoreCase));
        Require(!path.EndsWith("\\", StringComparison.Ordinal));
        foreach (string part in path.Substring(3).Split('\\'))
            Require(!part.EndsWith(".", StringComparison.Ordinal) && !part.EndsWith(" ", StringComparison.Ordinal));
        foreach (char c in path) Require(c >= 32);
        return full;
    }

    // Reject junctions, symlinks, device/UNC paths, ADS and noncanonical spellings.
    static string Canonical(string path, bool directory)
    {
        string full = PathText(path);
        Require(directory ? Directory.Exists(full) : File.Exists(full));
        for (string at = full; at != null; at = Path.GetDirectoryName(at))
        {
            Require((File.GetAttributes(at) & FileAttributes.ReparsePoint) == 0);
            if (at == Path.GetPathRoot(at)) break;
        }
        Require(((File.GetAttributes(full) & FileAttributes.Directory) != 0) == directory);
        return full;
    }

    static void Private(string path, bool directory)
    {
        Canonical(path, directory);
        SecurityIdentifier sid = WindowsIdentity.GetCurrent().User;
        FileSystemSecurity acl = directory ? (FileSystemSecurity)Directory.GetAccessControl(path) : File.GetAccessControl(path);
        Require(acl.AreAccessRulesProtected && acl.GetOwner(typeof(SecurityIdentifier)).Equals(sid));
        bool allowed = false;
        foreach (FileSystemAccessRule rule in acl.GetAccessRules(true, true, typeof(SecurityIdentifier)))
        {
            if (rule.AccessControlType != AccessControlType.Allow) continue;
            Require(rule.IdentityReference.Equals(sid));
            allowed = true;
        }
        Require(allowed);
    }

    static string Text(Dictionary<string, object> value, string key)
    {
        object item;
        Require(value.TryGetValue(key, out item) && item is string &&
            !String.IsNullOrWhiteSpace((string)item) && ((string)item).IndexOf('\0') < 0);
        return (string)item;
    }

    static string Digest(string path)
    {
        using (var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read))
        using (var sha = SHA256.Create())
            return BitConverter.ToString(sha.ComputeHash(stream)).Replace("-", "").ToLowerInvariant();
    }

    // ProcessStartInfo on Framework has no ArgumentList. This is the Windows CRT
    // argv encoding, not shell quoting: double trailing slashes and those before ".
    public static string QuoteArgument(string value)
    {
        if (value == null || value.IndexOf('\0') >= 0) throw new ArgumentException();
        var result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in value)
        {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') result.Append('\\', slashes * 2 + 1).Append(c);
            else result.Append('\\', slashes).Append(c);
            slashes = 0;
        }
        return result.Append('\\', slashes * 2).Append('"').ToString();
    }

    static Task Output(Stream source, Stream destination)
    {
        return Task.Run(delegate {
            byte[] buffer = new byte[65536];
            bool writable = true;
            int count;
            while ((count = source.Read(buffer, 0, buffer.Length)) != 0)
            {
                if (!writable) continue; // Drain a closed caller pipe, never block the original CLI.
                try { destination.Write(buffer, 0, count); destination.Flush(); }
                catch (IOException) { writable = false; }
                catch (ObjectDisposedException) { writable = false; }
            }
        });
    }

    public static int Main(string[] args)
    {
        Process child = null;
        try
        {
            string self = Canonical(Assembly.GetExecutingAssembly().Location, false);
            string directory = Path.GetDirectoryName(self);
            Private(directory, true);
            Private(self, false);
            string fixedConfig = Path.Combine(directory, "config.json");
            string configured = Environment.GetEnvironmentVariable(ConfigVariable);
            // A supplied environment path must name the SAME fixed private config.
            // No caller-controlled alternate entry, shell or config search path.
            Require(String.IsNullOrEmpty(configured) || String.Equals(configured, fixedConfig, StringComparison.OrdinalIgnoreCase));
            Private(fixedConfig, false);
            using (var configFile = new FileStream(fixedConfig, FileMode.Open, FileAccess.Read, FileShare.Read))
            {
                Require(configFile.Length > 0 && configFile.Length <= ConfigLimit);
                string json;
                using (var reader = new StreamReader(configFile, new UTF8Encoding(false, true), false, ConfigLimit, true))
                    json = reader.ReadToEnd();
                var serializer = new JavaScriptSerializer { MaxJsonLength = ConfigLimit, RecursionLimit = 8 };
                var config = serializer.DeserializeObject(json) as Dictionary<string, object>;
                Require(config != null && config.Count == 10 && config.ContainsKey("version") &&
                    config["version"] is int && (int)config["version"] == 1);
                var target = config.ContainsKey("target") ? config["target"] as Dictionary<string, object> : null;
                Require(target != null && target.Count == 4);
                foreach (string key in new [] { "agentId", "hostId", "workspaceId", "runtimeId" })
                {
                    string text = Text(target, key);
                    Require(text.Length <= 256);
                    foreach (char c in text) Require(c >= 32);
                }
                foreach (string key in new [] { "traceRoot", "logDirectory" }) Canonical(Text(config, key), true);
                foreach (string key in new [] { "node", "entry", "shimPath" }) Canonical(Text(config, key), false);
                // Store versions replace their installation paths. These are historical
                // hints only; the fixed Node entry resolves the current trusted pipe owner
                // and CLI before starting the original CLI with exactly these argv.
                foreach (string key in new [] { "applicationPath", "executable" }) PathText(Text(config, key));
                string node = Text(config, "node"), entry = Text(config, "entry"), executable = Text(config, "executable");
                Require(String.Equals(self, Text(config, "shimPath"), StringComparison.OrdinalIgnoreCase));
                Require(Text(config, "shimSha256").Length == 64 && Digest(self) == Text(config, "shimSha256"));
                Require(String.Equals(Path.GetFileName(node), "node.exe", StringComparison.OrdinalIgnoreCase));
                Require(String.Equals(Path.GetFileName(executable), "codex.exe", StringComparison.OrdinalIgnoreCase));
                Require(Path.GetFileName(entry) == "desktop-native-suggestions-runtime.mjs");
                Require(!String.Equals(executable, self, StringComparison.OrdinalIgnoreCase) &&
                    !String.Equals(executable, Text(config, "applicationPath"), StringComparison.OrdinalIgnoreCase));
                var command = new StringBuilder(QuoteArgument(entry));
                // Every original argv element follows entry, including empty and Unicode arguments.
                foreach (string argument in args) command.Append(' ').Append(QuoteArgument(argument));
                var start = new ProcessStartInfo(node, command.ToString()) {
                    UseShellExecute = false, CreateNoWindow = true,
                    RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true
                };
                start.EnvironmentVariables[ConfigVariable] = fixedConfig;
                // Child Node must run only the fixed entry, never ambient preloads.
                start.EnvironmentVariables.Remove("NODE_OPTIONS");
                start.EnvironmentVariables.Remove("NODE_PATH");
                child = Process.Start(start);
                Require(child != null);
                Process running = child;
                // Do not wait for caller stdin after child exit (the caller may keep it open).
                Task.Run(delegate {
                    try { Console.OpenStandardInput().CopyTo(running.StandardInput.BaseStream); }
                    catch (IOException) { }
                    catch (ObjectDisposedException) { }
                    finally { try { running.StandardInput.Close(); } catch (InvalidOperationException) { } }
                });
                Task stdout = Output(child.StandardOutput.BaseStream, Console.OpenStandardOutput());
                Task stderr = Output(child.StandardError.BaseStream, Console.OpenStandardError());
                child.WaitForExit();
                int exitCode = child.ExitCode;
                try { Task.WaitAll(stdout, stderr); } catch (AggregateException) { /* preserve original exit */ }
                return exitCode;
            }
        }
        catch
        {
            // Never turn an already-started original CLI's failure into a helper exit code.
            if (child != null) { child.WaitForExit(); return child.ExitCode; }
            Console.Error.WriteLine("WINDOWS_NATIVE_LAUNCHER_REJECTED");
            return 125;
        }
        finally { if (child != null) child.Dispose(); }
    }
}
