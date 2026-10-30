using System;
using System.IO;
using System.Collections.Generic;
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

    private static string FailureResponse(Exception error)
    {
        string value = "UNSAFE_WINDOWS_PRIVATE_STATE:" + error.GetType().Name;
        string step = error.Data["CodexManage.NativeStep"] as string;
        if (step == "normalize-path" || step == "directory-attributes" || step == "directory-parent" ||
            step == "directory-descriptor" || step == "create-directory" || step == "directory-protect") value += ":" + step;
        return value;
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
                (parts[1] != "protect" && parts[1] != "protect-file" && parts[1] != "check" &&
                 parts[1] != "create-directory" && parts[1] != "create-file" && parts[1] != "release-file"))
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
                    if (parts[1] == "create-file") response = CodexManagePrivateStateSecurity.CreatePrivateFileReceipt(path);
                    else { Execute(parts[1], path); response = "PRIVATE_STATE_OK"; }
                }
                catch (Exception error)
                {
                    response = FailureResponse(error);
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
            System.Runtime.CompilerServices.RuntimeHelpers.RunClassConstructor(typeof(CodexManagePrivateStateSecurity).TypeHandle);
            if (args.Length == 1 && args[0] == "serve") return Serve();
            if (args.Length != 2) throw new ArgumentException();
            if (args[0] == "create-file") Console.Out.WriteLine(CodexManagePrivateStateSecurity.CreatePrivateFileReceipt(args[1]));
            else { Execute(args[0], args[1]); Console.Out.WriteLine("PRIVATE_STATE_OK"); }
            return 0;
        }
        catch (Exception error)
        {
            Console.Error.WriteLine(FailureResponse(error));
            return 1;
        }
    }
}

// Loaded by the bootstrap PowerShell script and compiled into the native worker.
// No privilege adjustment, owner takeover or SACL read/write is performed.
public static partial class CodexManagePrivateStateSecurity
{
    private const uint ReadControl = 0x00020000, WriteDac = 0x00040000;
    private const uint OwnerInformation = 1, DaclInformation = 4;
    private const uint ProtectedDaclInformation = 0x80000000;

    [StructLayout(LayoutKind.Sequential)]
    private struct SecurityAttributes
    {
        public int Length;
        public IntPtr Descriptor;
        [MarshalAs(UnmanagedType.Bool)] public bool Inherit;
    }
    [DllImport("kernel32.dll", EntryPoint = "CreateDirectoryW", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CreateOwnedDirectory(string path, ref SecurityAttributes security);
    [DllImport("kernel32.dll", EntryPoint = "CreateFileW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateOwnedFile(string path, uint access, uint share,
        ref SecurityAttributes security, uint creation, uint flags, IntPtr template);

    [DllImport("kernel32.dll", EntryPoint = "GetFullPathNameW", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
    private static extern uint NativeFullPath(string path, uint length, StringBuilder buffer, IntPtr filePart);
    [DllImport("kernel32.dll", EntryPoint = "GetFileAttributesW", CharSet = CharSet.Unicode, ExactSpelling = true, SetLastError = true)]
    private static extern uint NativeAttributes(string path);

    // PowerShell may have already cached Framework legacy path switches before
    // Add-Type. Do not send extended paths through BCL path normalization/IO.
    private static string FullPath(string path)
    {
        try
        {
            if (String.IsNullOrWhiteSpace(path) || path.IndexOf('\0') >= 0) throw new ArgumentException();
            if (path.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase)) path = @"\\" + path.Substring(8);
            else if (path.StartsWith(@"\\?\", StringComparison.Ordinal))
            {
                if (path.Length < 7 || !Char.IsLetter(path[4]) || path[5] != ':' ||
                    (path[6] != '\\' && path[6] != '/')) throw new ArgumentException();
                path = path.Substring(4);
            }
            var buffer = new StringBuilder(32768);
            uint size = NativeFullPath(path, (uint)buffer.Capacity, buffer, IntPtr.Zero);
            if (size == 0) throw new Win32Exception(Marshal.GetLastWin32Error());
            if (size >= buffer.Capacity) throw new PathTooLongException();
            path = buffer.ToString();
            return path.StartsWith(@"\\", StringComparison.Ordinal)
                ? @"\\?\UNC\" + path.Substring(2) : @"\\?\" + path;
        }
        catch (Exception error) { MarkStep(error, "normalize-path"); throw; }
    }
    private static string ParentDirectory(string path)
    {
        int root;
        if (path.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase))
        {
            int server = path.IndexOf('\\', 8);
            if (server <= 8) throw new IOException();
            int share = path.IndexOf('\\', server + 1);
            root = share < 0 ? path.Length : share + 1;
        }
        else if (path.Length >= 7 && path[5] == ':' && path[6] == '\\') root = 7;
        else throw new IOException();
        int end = path.Length;
        while (end > root && path[end - 1] == '\\') end--;
        if (end <= root) return null;
        int separator = path.LastIndexOf('\\', end - 1);
        return path.Substring(0, Math.Max(root, separator));
    }
    private static void MarkStep(Exception error, string step)
    {
        if (!error.Data.Contains("CodexManage.NativeStep")) error.Data["CodexManage.NativeStep"] = step;
    }
    private static byte[] NewDescriptor(bool directory)
    {
        SecurityIdentifier user;
        using (WindowsIdentity identity = WindowsIdentity.GetCurrent()) user = identity.User;
        if (user == null) throw new UnauthorizedAccessException();
        FileSystemSecurity security = directory
            ? (FileSystemSecurity)new DirectorySecurity() : new FileSecurity();
        // This owner is supplied only to CREATE_NEW/CreateDirectory, never applied
        // to an existing object or inferred from the token's default Owner group.
        security.SetOwner(user);
        security.SetAccessRuleProtection(true, false);
        foreach (string value in new[] { user.Value, "S-1-5-18", "S-1-5-32-544" })
        {
            var sid = new SecurityIdentifier(value);
            security.AddAccessRule(directory
                ? new FileSystemAccessRule(sid, FileSystemRights.FullControl,
                    InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                    PropagationFlags.None, AccessControlType.Allow)
                : new FileSystemAccessRule(sid, FileSystemRights.FullControl, AccessControlType.Allow));
        }
        return security.GetSecurityDescriptorBinaryForm();
    }
    private static void EnsureDirectory(string path, bool target)
    {
        string step = "directory-attributes";
        try
        {
            // Ancestors remain namespace only; never change an existing owner.
            uint attributes = NativeAttributes(path);
            if (attributes != UInt32.MaxValue)
            {
                if ((attributes & (uint)FileAttributes.ReparsePoint) != 0 ||
                    (attributes & (uint)FileAttributes.Directory) == 0) throw new IOException();
                step = "directory-protect";
                if (target) Execute("protect", path);
                return;
            }
            int missing = Marshal.GetLastWin32Error();
            if (missing != 2 && missing != 3) throw new Win32Exception(missing);
            step = "directory-parent";
            string parent = ParentDirectory(path);
            if (String.IsNullOrEmpty(parent) || parent == path) throw new IOException();
            EnsureDirectory(parent, false);
            step = "directory-descriptor";
            GCHandle pinned = GCHandle.Alloc(NewDescriptor(true), GCHandleType.Pinned);
            try
            {
                var security = new SecurityAttributes { Length = Marshal.SizeOf(typeof(SecurityAttributes)),
                    Descriptor = pinned.AddrOfPinnedObject(), Inherit = false };
                step = "create-directory";
                if (!CreateOwnedDirectory(path, ref security))
                {
                    int error = Marshal.GetLastWin32Error();
                    if (error != 183) throw new Win32Exception(error);
                    step = "directory-protect";
                    Execute(target ? "protect" : "check", path);
                }
                else { step = "directory-protect"; Execute("protect", path); }
            }
            finally { pinned.Free(); }
        }
        catch (Exception error) { MarkStep(error, step); throw; }
    }
    private sealed class PrivateStateAlreadyExistsException : IOException { }

    private static string FileIdentity(SafeFileHandle file)
    {
        FileInformation info;
        if (!GetFileInformationByHandle(file, out info)) throw new Win32Exception(Marshal.GetLastWin32Error());
        if ((info.Attributes & (uint)(FileAttributes.ReparsePoint | FileAttributes.Directory)) != 0)
            throw new IOException();
        ulong index = ((ulong)info.IndexHigh << 32) | info.IndexLow;
        return info.Volume.ToString("x8", System.Globalization.CultureInfo.InvariantCulture) + ":" +
            index.ToString("x16", System.Globalization.CultureInfo.InvariantCulture);
    }

    private static SafeFileHandle CreatePrivateFileHandle(string path, bool rename = false)
    {
        path = FullPath(path);
        Execute("check", ParentDirectory(path));
        GCHandle pinned = GCHandle.Alloc(NewDescriptor(false), GCHandleType.Pinned);
        SafeFileHandle file = null;
        try
        {
            var security = new SecurityAttributes { Length = Marshal.SizeOf(typeof(SecurityAttributes)),
                Descriptor = pinned.AddrOfPinnedObject(), Inherit = false };
            // Retain a no-delete-share handle whenever the caller needs a lease.
            file = CreateOwnedFile(path, ReadControl | 0x80u | (rename ? 0x40000000u | 0x00010000u : 0u),
                3, ref security, 1, 0x00200000u, IntPtr.Zero);
            if (file.IsInvalid) {
                int error = Marshal.GetLastWin32Error();
                if (error == 80 || error == 183) throw new PrivateStateAlreadyExistsException();
                throw new Win32Exception(error);
            }
            RawSecurityDescriptor created = ReadSecurity(file);
            SecurityIdentifier user;
            using (WindowsIdentity identity = WindowsIdentity.GetCurrent()) user = identity.User;
            CheckOwner(created, user);
            CheckAccess(created, new[] { user.Value, "S-1-5-18", "S-1-5-32-544" });
            if ((created.ControlFlags & ControlFlags.DiscretionaryAclProtected) == 0)
                throw new UnauthorizedAccessException("UNPROTECTED_PRIVATE_DACL");
            FileIdentity(file); // Prove type and identity through this very handle.
            return file;
        }
        catch { if (file != null) file.Dispose(); throw; }
        finally { pinned.Free(); }
    }

    private static readonly Dictionary<string, SafeFileHandle> CreationLeases = new Dictionary<string, SafeFileHandle>();
    public static string CreatePrivateFileReceipt(string path)
    {
        // Keep A alive and prohibit delete/rename until Node has opened its FD.
        // Bounded leases are channel-local and disappear with native EOF/exit.
        lock (CreationLeases)
        {
            if (CreationLeases.Count >= 64) throw new IOException();
            SafeFileHandle file = CreatePrivateFileHandle(path);
            try {
                string identity = FileIdentity(file), token = Guid.NewGuid().ToString("N");
                CreationLeases.Add(token, file);
                return "PRIVATE_STATE_CREATED:" + token + ":" + identity;
            }
            catch { file.Dispose(); throw; }
        }
    }
    private static void ReleasePrivateFile(string token)
    {
        Guid parsed;
        if (token == null || token.Length != 32 || !Guid.TryParseExact(token, "N", out parsed)) throw new ArgumentException();
        lock (CreationLeases) {
            SafeFileHandle file;
            if (!CreationLeases.TryGetValue(token, out file)) throw new UnauthorizedAccessException();
            CreationLeases.Remove(token);
            file.Dispose();
        }
    }
    private static void CreatePrivateFile(string path) { CreatePrivateFileReceipt(path); }

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
        if (action == "release-file") { ReleasePrivateFile(requestedPath); return; }
        if (action == "create-directory") { EnsureDirectory(FullPath(requestedPath), true); return; }
        if (action == "create-file") { CreatePrivateFile(FullPath(requestedPath)); return; }
        if (action != "protect" && action != "protect-file" && action != "check")
            throw new ArgumentException();
        string path = FullPath(requestedPath);
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
