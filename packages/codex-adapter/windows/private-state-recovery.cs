using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Principal;
using Microsoft.Win32.SafeHandles;

// Local checkpoint primitives, not a Task/Host controller. These methods never
// remove, rename, re-ACL or overwrite an existing object.
public static partial class CodexManagePrivateStateSecurity
{
    public sealed class RecoveryReadLease : IDisposable
    {
        private readonly List<SafeFileHandle> directories = new List<SafeFileHandle>();
        private FileStream stream;
        public byte[] Bytes { get; private set; }
        public string Identity { get; private set; }
        public string Sha256 { get; private set; }
        internal RecoveryReadLease(string path, int maximum) : this(path, maximum, null) { }
        internal RecoveryReadLease(string path, int maximum, string codeSha256) : this(path, maximum, codeSha256, false) { }
        internal RecoveryReadLease(string path, int maximum, string codeSha256, bool runtime) : this(path, maximum, codeSha256, runtime, false) { }
        internal RecoveryReadLease(string path, int maximum, string codeSha256, bool runtime, bool state)
        {
            if (maximum < 1 || maximum > (runtime ? 134217728 : state ? 67108864 : 1048576) || (state && (runtime || codeSha256 != null))) throw new ArgumentException();
            try
            {
                path = CompilationDirectory(path);
                if (state && Path.GetFileName(path) != "runtime.json") throw new ArgumentException();
                bool code = codeSha256 != null;
                if (code)
                {
                    string anchor = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), ".config", "agent-hub") + Path.DirectorySeparatorChar;
                    string name = Path.GetFileName(path);
                    if (runtime) anchor = Path.Combine(anchor, "windows-host-v1", "programs") + Path.DirectorySeparatorChar;
                    if (!path.StartsWith(anchor, StringComparison.OrdinalIgnoreCase) ||
                        (!runtime && name != "windows-host-regular-limited.ps1" && name != "windows-host-regular-domain-common.ps1") ||
                        (runtime && !System.Text.RegularExpressions.Regex.IsMatch(path.Substring(anchor.Length), "^[a-f0-9]{64}\\\\.+$")) ||
                        !System.Text.RegularExpressions.Regex.IsMatch(codeSha256, "^[a-f0-9]{64}$")) throw new ArgumentException();
                }
                PinCompilationDirectory(Path.GetDirectoryName(path), directories);
                SafeFileHandle handle = CreateFile(FullPath(path), 0x80000000u | ReadControl | 0x80u,
                    1, IntPtr.Zero, 3, 0x00200000u, IntPtr.Zero);
                if (handle.IsInvalid) { handle.Dispose(); throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error()); }
                try
                {
                    FileInformation info = CompilationInspect(handle, false);
                    SecurityIdentifier user;
                    using (WindowsIdentity identity = WindowsIdentity.GetCurrent()) user = identity.User;
                    if (user == null) throw new UnauthorizedAccessException();
                    var descriptor = ReadSecurity(handle);
                    if (code)
                    {
                        // Public frozen code has the same trusted owners as
                        // compiler input. This is not a private-data exception.
                        string owner = descriptor.Owner == null ? null : descriptor.Owner.Value;
                        if (owner != user.Value && owner != "S-1-5-18" && owner != "S-1-5-32-544")
                            throw new UnauthorizedAccessException("WRONG_CODE_OWNER");
                    }
                    else CheckRetainedOwner(handle, info, descriptor, user, "check", path);
                    CheckAccess(descriptor, new[] { user.Value, "S-1-5-18", "S-1-5-32-544" });
                    // Inherited private files remain valid. The native lease pins
                    // their entire namespace; no existing ACL is rewritten.
                    stream = new FileStream(handle, FileAccess.Read, 65536, false);
                    handle = null;
                    if (stream.Length > maximum) throw new IOException();
                    Identity = FileIdentity(stream.SafeFileHandle);
                    Bytes = new byte[(int)stream.Length];
                    int offset = 0;
                    while (offset < Bytes.Length)
                    {
                        int count = stream.Read(Bytes, offset, Bytes.Length - offset);
                        if (count == 0) throw new IOException();
                        offset += count;
                    }
                    if (stream.ReadByte() != -1 || stream.Length != Bytes.Length) throw new IOException();
                    using (var hash = SHA256.Create()) Sha256 = BitConverter.ToString(hash.ComputeHash(Bytes)).Replace("-", "").ToLowerInvariant();
                    if (code && Sha256 != codeSha256) throw new UnauthorizedAccessException("CODE_CONTENT_CHANGED");
                }
                finally { if (handle != null) handle.Dispose(); }
            }
            catch { Dispose(); throw; }
        }
        public void Dispose()
        {
            if (stream != null) { stream.Dispose(); stream = null; }
            for (int i = directories.Count - 1; i >= 0; i--) directories[i].Dispose();
            directories.Clear();
        }
    }
    public static RecoveryReadLease OpenRecoveryRead(string path, int maximum)
    {
        return new RecoveryReadLease(path, maximum);
    }
    public static RecoveryReadLease OpenRecoveryCodeRead(string path, int maximum, string expectedSha256)
    {
        if (expectedSha256 == null) throw new ArgumentException();
        return new RecoveryReadLease(path, maximum, expectedSha256);
    }
    public static RecoveryReadLease OpenRecoveryRuntimeRead(string path, int maximum, string expectedSha256)
    {
        if (expectedSha256 == null) throw new ArgumentException();
        return new RecoveryReadLease(path, maximum, expectedSha256, true);
    }
    public static RecoveryReadLease OpenRecoveryStateRead(string path)
    {
        return new RecoveryReadLease(path, 67108864, null, false, true);
    }
    public static string CreateRecoveryFile(string path, byte[] bytes)
    {
        if (bytes == null || bytes.Length > 1048576) throw new ArgumentException();
        var directories = new List<SafeFileHandle>();
        try
        {
            path = CompilationDirectory(path);
            SafeFileHandle directory = PinCompilationDirectory(Path.GetDirectoryName(path), directories);
            CheckCompilationPrivate(directory, true);
            using (SafeFileHandle handle = CreatePrivateFileHandleForPurpose(path, PrivateFilePurpose.NativeRename))
            {
                string identity = FileIdentity(handle);
                using (var output = new FileStream(handle, FileAccess.Write, 65536, false))
                {
                    output.Write(bytes, 0, bytes.Length);
                    output.Flush(true);
                    if (FileIdentity(output.SafeFileHandle) != identity) throw new IOException();
                }
                return identity;
            }
        }
        finally { for (int i = directories.Count - 1; i >= 0; i--) directories[i].Dispose(); }
    }
}
