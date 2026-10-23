using System;
using System.IO;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Threading;

// Fixed local ACL operations only. No shell, RPC, Codex, or user content input.
internal static class PrivateState
{
    private const int MaxPathBytes = 128 * 1024;
    private const int MaxPathCharacters = 32767;
    private const int MaxFrameCharacters = 2 * ((MaxPathBytes + 2) / 3 * 4) + 64;
    private static readonly UTF8Encoding Utf8 = new UTF8Encoding(false, true);
    private static readonly object Gate = new object();
    private static bool executing;

    // Both entry points execute exactly the same fresh kernel ACL checks.
    private static void Execute(string action, string requestedPath)
    {
        string path = Path.GetFullPath(requestedPath);
        if (!path.StartsWith(@"\\?\", StringComparison.Ordinal))
            path = path.StartsWith(@"\\", StringComparison.Ordinal)
                ? @"\\?\UNC\" + path.Substring(2)
                : @"\\?\" + path;
        if (action != "protect" && action != "protect-file" && action != "check")
            throw new ArgumentException();
        FileAttributes attributes = File.GetAttributes(path);
        if ((attributes & FileAttributes.ReparsePoint) != 0) throw new IOException();
        bool directory = (attributes & FileAttributes.Directory) != 0;
        SecurityIdentifier sid;
        using (WindowsIdentity identity = WindowsIdentity.GetCurrent()) sid = identity.User;
        if (sid == null) throw new UnauthorizedAccessException();
        string[] allowed = { sid.Value, "S-1-5-18", "S-1-5-32-544" };
        if (action != "check")
        {
            if ((action == "protect") != directory) throw new IOException();
            FileSystemSecurity acl = directory
                ? (FileSystemSecurity)new DirectorySecurity()
                : (FileSystemSecurity)new FileSecurity();
            acl.SetOwner(sid);
            acl.SetAccessRuleProtection(true, false);
            foreach (string value in allowed)
            {
                SecurityIdentifier identity = new SecurityIdentifier(value);
                FileSystemAccessRule rule = directory
                    ? new FileSystemAccessRule(identity, FileSystemRights.FullControl,
                        InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                        PropagationFlags.None, AccessControlType.Allow)
                    : new FileSystemAccessRule(identity, FileSystemRights.FullControl,
                        AccessControlType.Allow);
                acl.AddAccessRule(rule);
            }
            if (directory) Directory.SetAccessControl(path, (DirectorySecurity)acl);
            else File.SetAccessControl(path, (FileSecurity)acl);
        }
        FileSystemSecurity current = directory
            ? (FileSystemSecurity)Directory.GetAccessControl(path)
            : (FileSystemSecurity)File.GetAccessControl(path);
        if (current.GetOwner(typeof(SecurityIdentifier)).Value != sid.Value)
            throw new UnauthorizedAccessException();
        foreach (FileSystemAccessRule rule in
            current.GetAccessRules(true, true, typeof(SecurityIdentifier)))
        {
            if (rule.AccessControlType == AccessControlType.Allow &&
                Array.IndexOf(allowed, rule.IdentityReference.Value) < 0)
                throw new UnauthorizedAccessException();
        }
    }

    private static string ReadFrame(Stream input)
    {
        var line = new StringBuilder();
        for (;;)
        {
            int value = input.ReadByte();
            if (value == -1)
            {
                if (line.Length != 0) throw new EndOfStreamException();
                return null;
            }
            if (value == '\n') return line.ToString();
            if (value == 0 || value > 127 || line.Length >= MaxFrameCharacters)
                throw new ArgumentException();
            line.Append((char)value);
        }
    }

    private static string DecodePath(string encoded)
    {
        if (encoded.Length == 0 || encoded.Length > (MaxPathBytes + 2) / 3 * 4)
            throw new ArgumentException();
        byte[] bytes = Convert.FromBase64String(encoded);
        if (bytes.Length > MaxPathBytes || Convert.ToBase64String(bytes) != encoded)
            throw new ArgumentException();
        string path = Utf8.GetString(bytes);
        if (path.Length == 0 || path.Length > MaxPathCharacters || path.IndexOf('\0') >= 0)
            throw new ArgumentException();
        return path;
    }

    private static int Serve()
    {
        Console.InputEncoding = Utf8;
        Console.OutputEncoding = Utf8;
        Console.Out.NewLine = "\n";
        Console.Out.WriteLine("PRIVATE_STATE_READY\t1");
        Console.Out.Flush();
        int previous = 0;
        using (var input = new BufferedStream(Console.OpenStandardInput(), 4096))
        for (;;)
        {
            // Keep reading while Execute runs on one background thread. If the
            // owning Worker dies, EOF ends Main even if a kernel call is stalled.
            string line = ReadFrame(input);
            if (line == null || line == "QUIT") return 0;
            string[] parts = line.Split('\t');
            int id;
            if (parts.Length != 4 || !Int32.TryParse(parts[0], out id) || id <= 0 ||
                previous == Int32.MaxValue || id != previous + 1 || parts[0] != id.ToString() ||
                (parts[1] != "protect" && parts[1] != "protect-file" && parts[1] != "check"))
                throw new ArgumentException();
            lock (Gate)
            {
                if (executing) throw new ArgumentException();
                executing = true;
            }
            previous = id;
            ThreadPool.QueueUserWorkItem(delegate(object ignored)
            {
                string response;
                try
                {
                    string path = DecodePath(parts[2]), cwd = DecodePath(parts[3]);
                    // Relative paths retain the caller's current working directory.
                    Environment.CurrentDirectory = cwd;
                    Execute(parts[1], path);
                    response = "PRIVATE_STATE_OK";
                }
                catch (Exception error)
                {
                    response = "UNSAFE_WINDOWS_PRIVATE_STATE:" + error.GetType().Name;
                }
                lock (Gate)
                {
                    try
                    {
                        Console.Out.WriteLine(parts[0] + "\t" + response);
                        Console.Out.Flush();
                        executing = false;
                    }
                    catch { Environment.Exit(1); }
                }
            });
        }
    }

    private static int Main(string[] args)
    {
        try
        {
            // Framework 4.6.2+ supports extended paths; older application
            // targeting defaults must not reintroduce the 260-character limit.
            AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
            AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
            if (args.Length == 1 && args[0] == "serve") return Serve();
            if (args.Length != 2) throw new ArgumentException();
            Execute(args[0], args[1]);
            Console.Out.WriteLine("PRIVATE_STATE_OK");
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine("UNSAFE_WINDOWS_PRIVATE_STATE:" + error.GetType().Name);
            return 1;
        }
    }
}
