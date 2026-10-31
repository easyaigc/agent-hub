using System;
using System.Text;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using Microsoft.Win32.SafeHandles;

// Diagnostic metadata only: no file data access, DACL/owner write, privilege
// adjustment, impersonation, process creation, Host or desktop operation.
public static class CodexManageStartupProbe
{
    [StructLayout(LayoutKind.Sequential)]
    private struct FileInfo
    {
        public uint Attributes;
        public System.Runtime.InteropServices.ComTypes.FILETIME Creation, Access, Write;
        public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
    }
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool GetTokenInformation(IntPtr token, int kind, IntPtr value, int length, out int needed);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetPackageFullName(IntPtr process, ref uint length, StringBuilder name);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern int GetPackageFamilyName(IntPtr process, ref uint length, StringBuilder name);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool ProcessIdToSessionId(uint pid, out uint session);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetProcessTimes(IntPtr process, out long creation, out long exit, out long kernel, out long user);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool QueryFullProcessImageName(IntPtr process, uint flags, StringBuilder name, ref uint size);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern SafeFileHandle CreateFile(string path, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetFileInformationByHandle(SafeFileHandle file, out FileInfo info);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint GetFullPathName(string path, uint size, StringBuilder value, IntPtr part);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern uint GetFinalPathNameByHandle(SafeFileHandle file, StringBuilder value, uint size, uint flags);
    [DllImport("advapi32.dll")]
    private static extern uint GetSecurityInfo(SafeFileHandle file, int type, uint sections, out IntPtr owner, out IntPtr group, out IntPtr dacl, out IntPtr sacl, out IntPtr descriptor);
    [DllImport("advapi32.dll")]
    private static extern uint GetSecurityDescriptorLength(IntPtr descriptor);
    [DllImport("kernel32.dll")]
    private static extern IntPtr LocalFree(IntPtr value);

    private static string Hash(string text)
    {
        using (SHA256 hash = SHA256.Create())
            return BitConverter.ToString(hash.ComputeHash(Encoding.UTF8.GetBytes(text))).Replace("-", "").ToLowerInvariant();
    }
    private static string PathKey(string path)
    {
        if (path.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase)) path = @"\\" + path.Substring(8);
        else if (path.StartsWith(@"\\?\", StringComparison.Ordinal)) path = path.Substring(4);
        return path.Replace('/', '\\').ToLowerInvariant();
    }
    private static IntPtr TokenValue(IntPtr token, int kind)
    {
        int size;
        GetTokenInformation(token, kind, IntPtr.Zero, 0, out size);
        if (size < 4 || size > 65536) throw new Win32Exception(Marshal.GetLastWin32Error());
        IntPtr value = Marshal.AllocHGlobal(size);
        if (GetTokenInformation(token, kind, value, size, out size)) return value;
        int error = Marshal.GetLastWin32Error();
        Marshal.FreeHGlobal(value);
        throw new Win32Exception(error);
    }
    private static string TokenSid(IntPtr token, int kind)
    {
        IntPtr value = TokenValue(token, kind);
        try { return new SecurityIdentifier(Marshal.ReadIntPtr(value)).Value; }
        finally { Marshal.FreeHGlobal(value); }
    }
    private static Dictionary<string, object> Package(IntPtr process, bool family)
    {
        uint length = 0;
        int code = family ? GetPackageFamilyName(process, ref length, null) : GetPackageFullName(process, ref length, null);
        if (code == 15700) return new Dictionary<string, object> { { "state", "none" } };
        if (code != 122 || length < 1 || length > 32768)
            return new Dictionary<string, object> { { "state", "error" }, { "code", code } };
        var value = new StringBuilder((int)length);
        code = family ? GetPackageFamilyName(process, ref length, value) : GetPackageFullName(process, ref length, value);
        return code == 0 ? new Dictionary<string, object> { { "state", "present" }, { "hash", Hash(value.ToString()) } }
            : new Dictionary<string, object> { { "state", "error" }, { "code", code } };
    }
    public static Dictionary<string, object> Capture(int nodePid, string path)
    {
        if (nodePid < 1) throw new ArgumentException();
        IntPtr process = OpenProcess(0x1000, false, (uint)nodePid), token = IntPtr.Zero;
        if (process == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        try
        {
            if (!OpenProcessToken(process, 8, out token)) throw new Win32Exception(Marshal.GetLastWin32Error());
            string user = TokenSid(token, 1), owner = TokenSid(token, 4);
            var result = new Dictionary<string, object> {
                { "pid", nodePid }, { "userHash", Hash(user) }, { "ownerHash", Hash(owner) },
                { "ownerMatchesUser", user == owner }, { "ownerIsAdministrators", owner == "S-1-5-32-544" },
                { "packageFull", Package(process, false) }, { "packageFamily", Package(process, true) }
            };
            uint session;
            if (ProcessIdToSessionId((uint)nodePid, out session)) result["sessionId"] = session;
            long created, exited, kernel, cpu;
            if (GetProcessTimes(process, out created, out exited, out kernel, out cpu)) result["startedFileTime"] = created.ToString();
            uint imageSize = 32768;
            var image = new StringBuilder((int)imageSize);
            if (QueryFullProcessImageName(process, 0, image, ref imageSize)) result["imagePathHash"] = Hash(PathKey(image.ToString()));
            IntPtr elevation = TokenValue(token, 20);
            try { result["elevated"] = Marshal.ReadInt32(elevation) != 0; }
            finally { Marshal.FreeHGlobal(elevation); }
            string integrity = TokenSid(token, 25);
            int rid;
            if (Int32.TryParse(integrity.Substring(integrity.LastIndexOf('-') + 1), out rid))
                result["integrity"] = rid < 0x1000 ? "untrusted" : rid < 0x2000 ? "low" : rid < 0x3000 ? "medium" : rid < 0x4000 ? "high" : "system-or-higher";
            using (WindowsIdentity helper = WindowsIdentity.GetCurrent())
            {
                result["helperUserMatchesNode"] = helper.User != null && helper.User.Value == user;
                if (helper.User != null) result["helperUserHash"] = Hash(helper.User.Value);
            }
            if (String.IsNullOrEmpty(path)) return result;
            if (path.Length > 32767 || path.IndexOf('\0') >= 0) throw new ArgumentException();
            var full = new StringBuilder(32768);
            uint n = GetFullPathName(path, (uint)full.Capacity, full, IntPtr.Zero);
            if (n == 0 || n >= full.Capacity) throw new Win32Exception(Marshal.GetLastWin32Error());
            string normalized = full.ToString();
            if (!normalized.StartsWith(@"\\?\", StringComparison.Ordinal))
                normalized = normalized.StartsWith(@"\\", StringComparison.Ordinal) ? @"\\?\UNC\" + normalized.Substring(2) : @"\\?\" + normalized;
            using (SafeFileHandle file = CreateFile(normalized, 0x20080, 7, IntPtr.Zero, 3, 0x02200000, IntPtr.Zero))
            {
                if (file.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
                FileInfo info;
                if (!GetFileInformationByHandle(file, out info)) throw new Win32Exception(Marshal.GetLastWin32Error());
                var data = new Dictionary<string, object> {
                    { "directory", (info.Attributes & 16) != 0 }, { "reparse", (info.Attributes & 1024) != 0 },
                    { "identityHash", Hash(info.Volume.ToString("x8") + ":" + (((ulong)info.IndexHigh << 32) | info.IndexLow).ToString("x16")) }
                };
                var final = new StringBuilder(32768);
                uint size = GetFinalPathNameByHandle(file, final, (uint)final.Capacity, 0);
                if (size > 0 && size < final.Capacity) data["finalPathHash"] = Hash(PathKey(final.ToString()));
                IntPtr own, group, dacl, sacl, descriptor;
                uint error = GetSecurityInfo(file, 1, 5, out own, out group, out dacl, out sacl, out descriptor);
                if (error != 0) throw new Win32Exception((int)error);
                try
                {
                    byte[] bytes = new byte[checked((int)GetSecurityDescriptorLength(descriptor))];
                    Marshal.Copy(descriptor, bytes, 0, bytes.Length);
                    var security = new RawSecurityDescriptor(bytes, 0);
                    data["ownerMatchesNodeUser"] = security.Owner != null && security.Owner.Value == user;
                    if (security.Owner != null) data["ownerHash"] = Hash(security.Owner.Value);
                    data["protected"] = (security.ControlFlags & ControlFlags.DiscretionaryAclProtected) != 0;
                    data["daclPresent"] = security.DiscretionaryAcl != null && (security.ControlFlags & ControlFlags.DiscretionaryAclPresent) != 0;
                    data["descriptorHash"] = Hash(Convert.ToBase64String(bytes));
                    int unknown = 0, unsafeAllow = 0;
                    if (security.DiscretionaryAcl != null)
                    foreach (GenericAce entry in security.DiscretionaryAcl)
                    {
                        QualifiedAce ace = entry as QualifiedAce;
                        if (ace == null) { unknown++; continue; }
                        if (ace.AceQualifier == AceQualifier.AccessAllowed && ace.SecurityIdentifier.Value != user &&
                            ace.SecurityIdentifier.Value != "S-1-5-18" && ace.SecurityIdentifier.Value != "S-1-5-32-544") unsafeAllow++;
                    }
                    data["unknownAceCount"] = unknown; data["unsafeAllowCount"] = unsafeAllow;
                }
                finally { LocalFree(descriptor); }
                result["file"] = data;
            }
            return result;
        }
        finally { if (token != IntPtr.Zero) CloseHandle(token); CloseHandle(process); }
    }
}
