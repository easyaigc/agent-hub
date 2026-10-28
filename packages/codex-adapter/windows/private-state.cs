using System;
using System.IO;
using System.ComponentModel;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;
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

    // Bootstrap and both native entry points share this exact implementation.
    private static void Execute(string action, string requestedPath)
    {
        CodexManagePrivateStateSecurity.Execute(action, requestedPath);
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

// Loaded by the bootstrap PowerShell script and compiled into the native worker.
// No privilege adjustment, owner takeover or SACL read/write is performed.
public static class CodexManagePrivateStateSecurity
{
    private const uint ReadControl = 0x00020000, WriteDac = 0x00040000;
    private const uint OwnerInformation = 1, DaclInformation = 4;
    private const uint ProtectedDaclInformation = 0x80000000;

    static CodexManagePrivateStateSecurity()
    {
        // Also run when PowerShell loads this class without entering Main.
        AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
        AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateFile(string path, uint access, uint share,
        IntPtr security, uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool GetFileInformationByHandle(SafeFileHandle file, out FileInformation info);
    [DllImport("advapi32.dll")]
    private static extern uint GetSecurityInfo(SafeFileHandle file, int type, uint sections,
        out IntPtr owner, out IntPtr group, out IntPtr dacl, out IntPtr sacl, out IntPtr descriptor);
    [DllImport("advapi32.dll")]
    private static extern uint SetSecurityInfo(SafeFileHandle file, int type, uint sections,
        IntPtr owner, IntPtr group, IntPtr dacl, IntPtr sacl);
    [DllImport("advapi32.dll")]
    private static extern uint GetSecurityDescriptorLength(IntPtr descriptor);
    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr memory);

    [StructLayout(LayoutKind.Sequential)]
    private struct FileInformation
    {
        public uint Attributes;
        public System.Runtime.InteropServices.ComTypes.FILETIME Creation, Access, Write;
        public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
    }

    private static RawSecurityDescriptor ReadSecurity(SafeFileHandle file)
    {
        IntPtr owner, group, dacl, sacl, descriptor;
        uint error = GetSecurityInfo(file, 1, OwnerInformation | DaclInformation,
            out owner, out group, out dacl, out sacl, out descriptor);
        if (error != 0) throw new Win32Exception((int)error);
        try
        {
            byte[] bytes = new byte[checked((int)GetSecurityDescriptorLength(descriptor))];
            Marshal.Copy(descriptor, bytes, 0, bytes.Length);
            return new RawSecurityDescriptor(bytes, 0);
        }
        finally { LocalFree(descriptor); }
    }

    private static void CheckOwner(RawSecurityDescriptor current, SecurityIdentifier sid)
    {
        if (current.Owner == null || !current.Owner.Equals(sid))
            throw new UnauthorizedAccessException("WRONG_FILE_OWNER");
    }

    private static void CheckAccess(RawSecurityDescriptor current, string[] allowed)
    {
        // A NULL DACL grants everyone access; an empty enumeration is not proof.
        if (current.DiscretionaryAcl == null ||
            (current.ControlFlags & ControlFlags.DiscretionaryAclPresent) == 0)
            throw new UnauthorizedAccessException("NULL_PRIVATE_DACL");
        foreach (GenericAce entry in current.DiscretionaryAcl)
        {
            QualifiedAce ace = entry as QualifiedAce;
            if (ace == null) throw new UnauthorizedAccessException("UNKNOWN_PRIVATE_ACE");
            if (ace.AceQualifier == AceQualifier.AccessAllowed &&
                Array.IndexOf(allowed, ace.SecurityIdentifier.Value) < 0)
                throw new UnauthorizedAccessException("SHARED_PRIVATE_FILE");
        }
    }

    public static void Execute(string action, string requestedPath)
    {
        if (action != "protect" && action != "protect-file" && action != "check")
            throw new ArgumentException();
        string path = Path.GetFullPath(requestedPath);
        if (!path.StartsWith(@"\\?\", StringComparison.Ordinal))
            path = path.StartsWith(@"\\", StringComparison.Ordinal)
                ? @"\\?\UNC\" + path.Substring(2) : @"\\?\" + path;
        // OPEN_REPARSE_POINT prevents following a redirected final component.
        // Query and update the same retained handle, including the final check.
        uint access = ReadControl | 0x80u | (action == "check" ? 0u : WriteDac);
        using (SafeFileHandle file = CreateFile(path, access, 7, IntPtr.Zero, 3,
            0x02000000u | 0x00200000u, IntPtr.Zero))
        {
            if (file.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
            FileInformation info;
            if (!GetFileInformationByHandle(file, out info))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            if ((info.Attributes & (uint)FileAttributes.ReparsePoint) != 0) throw new IOException();
            bool directory = (info.Attributes & (uint)FileAttributes.Directory) != 0;
            SecurityIdentifier sid;
            using (WindowsIdentity identity = WindowsIdentity.GetCurrent()) sid = identity.User;
            if (sid == null) throw new UnauthorizedAccessException();
            string[] allowed = { sid.Value, "S-1-5-18", "S-1-5-32-544" };
            RawSecurityDescriptor current = ReadSecurity(file);
            CheckOwner(current, sid);
            if (action != "check")
            {
                if ((action == "protect") != directory) throw new IOException();
                // Construct only the DACL. Owner, group and audit data are untouched.
                FileSystemSecurity acl = directory
                    ? (FileSystemSecurity)new DirectorySecurity() : new FileSecurity();
                acl.SetAccessRuleProtection(true, false);
                foreach (string value in allowed)
                {
                    var identity = new SecurityIdentifier(value);
                    var rule = directory
                        ? new FileSystemAccessRule(identity, FileSystemRights.FullControl,
                            InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                            PropagationFlags.None, AccessControlType.Allow)
                        : new FileSystemAccessRule(identity, FileSystemRights.FullControl,
                            AccessControlType.Allow);
                    acl.AddAccessRule(rule);
                }
                RawAcl dacl = new RawSecurityDescriptor(acl.GetSecurityDescriptorBinaryForm(), 0).DiscretionaryAcl;
                byte[] bytes = new byte[dacl.BinaryLength];
                dacl.GetBinaryForm(bytes, 0);
                GCHandle pinned = GCHandle.Alloc(bytes, GCHandleType.Pinned);
                try
                {
                    uint error = SetSecurityInfo(file, 1, DaclInformation | ProtectedDaclInformation,
                        IntPtr.Zero, IntPtr.Zero, pinned.AddrOfPinnedObject(), IntPtr.Zero);
                    if (error != 0) throw new Win32Exception((int)error);
                }
                finally { pinned.Free(); }
                current = ReadSecurity(file);
                CheckOwner(current, sid);
                if ((current.ControlFlags & ControlFlags.DiscretionaryAclProtected) == 0)
                    throw new UnauthorizedAccessException("UNPROTECTED_PRIVATE_DACL");
            }
            CheckAccess(current, allowed);
        }
    }
}
