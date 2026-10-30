using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Threading.Tasks;
using Microsoft.Win32.SafeHandles;
using System.Web.Script.Serialization;

// Fixed read-only trace boundary. Every read retains the complete no-reparse
// ancestor chain without FILE_SHARE_DELETE; ACLs are read from those handles.
internal static class TraceState
{
    [StructLayout(LayoutKind.Sequential)] struct Info {
        public uint Attributes; public System.Runtime.InteropServices.ComTypes.FILETIME Created, Accessed, Written;
        public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
    }
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern SafeFileHandle CreateFile(string path, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);
    [DllImport("kernel32.dll", SetLastError=true)] static extern bool GetFileInformationByHandle(SafeFileHandle handle, out Info info);
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    static extern uint GetFinalPathNameByHandle(SafeFileHandle handle, [Out] char[] path, uint length, uint flags);
    [DllImport("advapi32.dll", SetLastError=true)]
    static extern uint GetSecurityInfo(SafeFileHandle handle, uint type, uint requested, out IntPtr owner, out IntPtr group, out IntPtr dacl, out IntPtr sacl, out IntPtr descriptor);
    [DllImport("advapi32.dll")] static extern uint GetSecurityDescriptorLength(IntPtr descriptor);
    [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr memory);
    // Principal equality is binary SID equality; descriptors are still freshly
    // read and validated on every retained handle, before and after each read.
    static readonly SecurityIdentifier Sid = WindowsIdentity.GetCurrent().User;
    static readonly SecurityIdentifier SystemSid = new SecurityIdentifier("S-1-5-18");
    static readonly SecurityIdentifier AdministratorsSid = new SecurityIdentifier("S-1-5-32-544");
    static readonly SecurityIdentifier InstallerSid = new SecurityIdentifier("S-1-5-80-956008885-3418522649-1831038044-1853292631-2271478464");
    static bool Trusted(SecurityIdentifier sid) { return Sid.Equals(sid) || SystemSid.Equals(sid) || AdministratorsSid.Equals(sid); }
    static string Digest(byte[] bytes) { using(var sha=SHA256.Create()) return Convert.ToBase64String(sha.ComputeHash(bytes)); }
    static byte[] Security(SafeFileHandle handle, bool ancestor, bool root) {
        IntPtr owner,group,dacl,sacl,sd;
        if(GetSecurityInfo(handle,1,5,out owner,out group,out dacl,out sacl,out sd)!=0) throw new IOException();
        try {
            byte[] bytes=new byte[GetSecurityDescriptorLength(sd)]; Marshal.Copy(sd,bytes,0,bytes.Length);
            var acl=new RawSecurityDescriptor(bytes,0);
            if(acl.DiscretionaryAcl==null || acl.Owner==null) throw new UnauthorizedAccessException();
            if(!Trusted(acl.Owner) && !(ancestor && acl.Owner.Equals(InstallerSid))) throw new UnauthorizedAccessException();
            if(root && !acl.Owner.Equals(Sid)) throw new UnauthorizedAccessException();
            bool userAccess=false;
            foreach(GenericAce entry in acl.DiscretionaryAcl) {
                var ace=entry as CommonAce;
                if(ace==null) throw new UnauthorizedAccessException(); // unknown/object/callback ACE: fail closed
                if(ace.IsCallback) throw new UnauthorizedAccessException();
                if(ace.AceQualifier!=AceQualifier.AccessAllowed || (ace.AceFlags&AceFlags.InheritOnly)!=0) continue;
                // Ancestors are namespace anchors, not confidentiality boundaries.
                // Windows can grant Users creation/deletion on a volume root.
                // Retained handles + per-component IDs/ACL digests fence that
                // namespace; root and every contained object remain user-private.
                if(!ancestor && !Trusted(ace.SecurityIdentifier)) throw new UnauthorizedAccessException();
                if(ace.SecurityIdentifier.Equals(Sid) && (ace.AccessMask & 0x20001)==0x20001) userAccess=true;
            }
            // Elevated Windows tokens can default new file ownership to the
            // Administrators group. That principal already has full access in
            // our trusted ACL boundary. Descendants may retain that native owner,
            // but the private root must be user-owned and each DACL must grant
            // this exact user; no foreign owner or broader access is accepted.
            if(!ancestor && !userAccess) throw new UnauthorizedAccessException();
            return bytes;
        } finally { LocalFree(sd); }
    }
    static string Canonical(string path) {
        // No UNC, device namespace, alternate streams, relative paths or trailing aliases.
        if(path.Length<3 || !Char.IsLetter(path[0]) || path[1]!=':' || path[2]!='\\' || path.IndexOf(':',2)>=0 || path.Contains("/")) throw new IOException();
        string full=Path.GetFullPath(path).TrimEnd('\\');
        if(path.TrimEnd('\\')!=full) throw new IOException();
        foreach(string part in path.Substring(3).Split('\\')) if(part.EndsWith(".") || part.EndsWith(" ")) throw new IOException();
        return full;
    }
    internal static string ProducerPath(string path) {
        if(String.IsNullOrEmpty(path) || path.Length>32767 || path.EndsWith("\\",StringComparison.Ordinal))throw new IOException();
        foreach(char c in path)if(c<32 || c=='<' || c=='>' || c=='"' || c=='|' || c=='?' || c=='*')throw new IOException();
        string full=Canonical(path);
        if(path!=full)throw new IOException();
        foreach(string part in path.Substring(3).Split('\\')) {
            if(part.Length==0 || System.Text.RegularExpressions.Regex.IsMatch(part,@"~[0-9]+(?:\.|$)") || System.Text.RegularExpressions.Regex.IsMatch(part,@"^(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)",System.Text.RegularExpressions.RegexOptions.IgnoreCase))throw new IOException();
        }
        return full;
    }
    static string Inspect(SafeFileHandle h, string path, bool directory, bool ancestor, bool root, char[] actual) {
        Info info; if(!GetFileInformationByHandle(h,out info) || (info.Attributes&0x400)!=0 || ((info.Attributes&16)!=0)!=directory || (!directory && info.Links!=1)) throw new IOException();
        Array.Clear(actual,0,actual.Length); uint count=GetFinalPathNameByHandle(h,actual,(uint)actual.Length,0);
        if(count==0 || count>=actual.Length || !String.Equals(new string(actual,0,(int)count).Substring(4).TrimEnd('\\'),path.TrimEnd('\\'),StringComparison.OrdinalIgnoreCase)) throw new IOException();
        return info.Volume+":"+info.IndexHigh+":"+info.IndexLow+":"+info.Created.dwHighDateTime+":"+info.Created.dwLowDateTime+":"+Digest(Security(h,ancestor,root));
    }
    // Reuse the trace reader's Win32 namespace/ID/DACL checks, retaining every
    // component. Producer config/shim reads additionally deny write AND delete
    // sharing until the complete process-chain probe has finished.
    internal sealed class ProducerFile : IDisposable {
        readonly List<SafeFileHandle> handles=new List<SafeFileHandle>();
        readonly List<string> paths=new List<string>(), identities=new List<string>();
        readonly string root; readonly bool directory, privateBoundary, strictPrivate;
        readonly char[] actual; Info initial;
        internal ProducerFile(string rootInput,string pathInput,bool isDirectory,bool isPrivate,bool strict=false) {
            root=ProducerPath(rootInput);string path=ProducerPath(pathInput);
            directory=isDirectory;privateBoundary=isPrivate;strictPrivate=strict;actual=new char[Math.Min(32768,path.Length+6)];
            if(!(String.Equals(root,path,StringComparison.OrdinalIgnoreCase)||path.StartsWith(root+"\\",StringComparison.OrdinalIgnoreCase)))throw new IOException();
            for(string at=path;;) {paths.Add(at);int split=at.LastIndexOf('\\');if(split<2)break;at=at.Substring(0,split);}paths.Reverse();
            try {
                for(int i=0;i<paths.Count;i++) {
                    bool dir=i<paths.Count-1||directory;string native=paths[i].Length==2?paths[i]+"\\":paths[i];
                    var handle=CreateFile("\\\\?\\"+native,dir?0x20080u:0x80020000u,1,IntPtr.Zero,3,0x02200000,IntPtr.Zero);
                    if(handle.IsInvalid){handle.Dispose();throw new IOException();}handles.Add(handle);
                    identities.Add(Inspect(handle,paths[i],dir,Ancestor(i),privateBoundary&&paths[i]==root,actual));
                    if(strictPrivate&&!Ancestor(i))Strict(handle);
                }
                if(!GetFileInformationByHandle(handles[handles.Count-1],out initial))throw new IOException();Check();
            }catch{Dispose();throw;}
        }
        bool Ancestor(int index){return !privateBoundary||paths[index].Length<root.Length;}
        static void Strict(SafeFileHandle handle) {
            var acl=new RawSecurityDescriptor(Security(handle,false,false),0);
            if(!acl.Owner.Equals(Sid)||(acl.ControlFlags&ControlFlags.DiscretionaryAclProtected)==0)throw new UnauthorizedAccessException();
            foreach(GenericAce item in acl.DiscretionaryAcl) {
                var ace=item as CommonAce;
                if(ace==null || ace.IsCallback || (ace.AceQualifier==AceQualifier.AccessAllowed && (ace.AceFlags&AceFlags.InheritOnly)==0 && !ace.SecurityIdentifier.Equals(Sid)))throw new UnauthorizedAccessException();
            }
        }
        internal void Check() {
            for(int i=0;i<handles.Count;i++) {
                if(identities[i]!=Inspect(handles[i],paths[i],i<paths.Count-1||directory,Ancestor(i),privateBoundary&&paths[i]==root,actual))throw new IOException();
                if(strictPrivate&&!Ancestor(i))Strict(handles[i]);
            }
            Info after;if(!GetFileInformationByHandle(handles[handles.Count-1],out after) || (!directory&&(
                initial.SizeHigh!=after.SizeHigh || initial.SizeLow!=after.SizeLow ||
                initial.Written.dwHighDateTime!=after.Written.dwHighDateTime || initial.Written.dwLowDateTime!=after.Written.dwLowDateTime)))throw new IOException();
        }
        FileStream Stream(int maximum) {
            Check();long length=((long)initial.SizeHigh<<32)|initial.SizeLow;
            if(directory||length<=0||length>maximum)throw new IOException();
            // The chain owns this handle; disposing a read stream must not drop
            // the retained file lock before the final kernel identity checks.
            var stream=new FileStream(new SafeFileHandle(handles[handles.Count-1].DangerousGetHandle(),false),FileAccess.Read);
            try{stream.Position=0;return stream;}catch{stream.Dispose();throw;}
        }
        internal byte[] Bytes(int maximum) {
            byte[] bytes;using(var stream=Stream(maximum)) {
                bytes=new byte[(int)stream.Length];int offset=0;
                while(offset<bytes.Length){int n=stream.Read(bytes,offset,bytes.Length-offset);if(n<=0)throw new IOException();offset+=n;}
                if(stream.ReadByte()!=-1)throw new IOException();
            }Check();return bytes;
        }
        internal string Sha256(int maximum) {
            string hash;using(var stream=Stream(maximum))using(var sha=SHA256.Create())hash=BitConverter.ToString(sha.ComputeHash(stream)).Replace("-","").ToLowerInvariant();
            Check();return hash;
        }
        public void Dispose(){for(int i=handles.Count-1;i>=0;i--)handles[i].Dispose();handles.Clear();}
    }
    sealed class NamespaceAnchor {
        public SafeFileHandle Handle;
        public string Identity;
    }
    // Only namespace ancestors ABOVE the private root are shared, for one B
    // command. Their fresh identity/DACL must still equal the initial capture
    // before and after EVERY item. The private root and everything beneath it
    // always receive new kernel access checks through freshly opened handles.
    static Dictionary<string,NamespaceAnchor> OpenNamespaceAnchors(string rootInput) {
        string root=Canonical(rootInput), at=root;
        var paths=new List<string>();
        while(true) { int split=at.LastIndexOf('\\'); if(split<2)break; at=at.Substring(0,split); paths.Add(at); }
        paths.Reverse();
        var anchors=new Dictionary<string,NamespaceAnchor>(StringComparer.OrdinalIgnoreCase);
        var actual=new char[Math.Min(32768,root.Length+6)];
        try {
            foreach(string path in paths) {
                string native=path.Length==2 ? path+"\\" : path;
                var handle=CreateFile("\\\\?\\"+native,0x20080u,3,IntPtr.Zero,3,0x02200000,IntPtr.Zero);
                if(handle.IsInvalid) { handle.Dispose(); throw new IOException(); }
                // Register ownership before inspecting, including failure paths.
                var anchor=new NamespaceAnchor { Handle=handle };
                anchors.Add(path,anchor);
                anchor.Identity=Inspect(handle,path,true,true,false,actual);
            }
            return anchors;
        } catch { foreach(var anchor in anchors.Values)anchor.Handle.Dispose(); throw; }
    }
    static string Snapshot(string rootInput, string pathInput, int maximum) {
        return SnapshotWithAnchors(rootInput,pathInput,maximum,null);
    }
    static string SnapshotWithAnchors(string rootInput, string pathInput, int maximum, Dictionary<string,NamespaceAnchor> anchors) {
        var handles=new List<SafeFileHandle>();
        var ownedHandles=new List<SafeFileHandle>();
        try {
            string root=Canonical(rootInput), path=Canonical(pathInput);
            if(maximum<0 || maximum>32*1024*1024 || !(String.Equals(root,path,StringComparison.OrdinalIgnoreCase)||path.StartsWith(root+"\\",StringComparison.OrdinalIgnoreCase))) throw new IOException();
            var paths=new List<string>(); string at=path;
            // Canonical already resolved/rejected every lexical alias. Build
            // exactly the same component chain without re-normalizing each parent.
            while(true) { paths.Add(at); int split=at.LastIndexOf('\\'); if(split<2) break; at=at.Substring(0,split); }
            paths.Reverse(); var identities=new List<string>();
            // Unicode char[] avoids StringBuilder's per-query native buffer copy.
            // The marshaller owns each call's pin; storage is local to this Snapshot.
            // An accepted DOS path must equal a component of this canonical
            // input. Reserve the device prefix, a root slash and the terminator;
            // keep the original maximum and reject any longer native result.
            var actual=new char[Math.Min(32768,path.Length+6)];
            for(int i=0;i<paths.Count;i++) {
                bool directory=i<paths.Count-1 || maximum==0;
                string native=paths[i].Length==2 ? paths[i]+"\\" : paths[i];
                bool ancestor=paths[i].Length<root.Length;
                NamespaceAnchor anchor=null;
                SafeFileHandle h;
                if(ancestor && anchors!=null && anchors.TryGetValue(paths[i],out anchor)) h=anchor.Handle;
                else {
                    h=CreateFile("\\\\?\\"+native, maximum>0 && !directory ? 0x80020000u : 0x20080u,3,IntPtr.Zero,3,0x02200000,IntPtr.Zero);
                    if(h.IsInvalid) { h.Dispose(); throw new IOException(); } ownedHandles.Add(h);
                }
                handles.Add(h);
                string identity=Inspect(h,paths[i],directory,ancestor,paths[i].Length==root.Length,actual);
                if(anchor!=null && identity!=anchor.Identity)throw new IOException();
                identities.Add(identity);
            }
            byte[] bytes=new byte[0];
            if(maximum>0) {
                Info before,after; var h=handles[handles.Count-1]; if(!GetFileInformationByHandle(h,out before)) throw new IOException();
                long length=((long)before.SizeHigh<<32)|before.SizeLow;
                bytes=new byte[(int)Math.Min(length,maximum)];
                using(var stream=new FileStream(h,FileAccess.Read)) { int offset=0; while(offset<bytes.Length) {int n=stream.Read(bytes,offset,bytes.Length-offset);if(n==0)throw new IOException();offset+=n;}
                    if(!GetFileInformationByHandle(h,out after) || before.SizeHigh!=after.SizeHigh || before.SizeLow!=after.SizeLow || before.Written.dwHighDateTime!=after.Written.dwHighDateTime || before.Written.dwLowDateTime!=after.Written.dwLowDateTime) throw new IOException();
                    // Inspect all handles before the FileStream disposes its target.
                    for(int i=0;i<handles.Count;i++) if(identities[i]!=Inspect(handles[i],paths[i],i<paths.Count-1,paths[i].Length<root.Length,paths[i].Length==root.Length,actual)) throw new IOException();
                }
            } else for(int i=0;i<handles.Count;i++) if(identities[i]!=Inspect(handles[i],paths[i],true,paths[i].Length<root.Length,paths[i].Length==root.Length,actual)) throw new IOException();
            return "{\"identity\":\""+Digest(Encoding.UTF8.GetBytes(String.Join("|",identities)))+"\",\"chain\":[\""+String.Join("\",\"",identities)+"\"],\"bytes\":\""+Convert.ToBase64String(bytes)+"\"}";
        }
        finally { foreach(var h in ownedHandles) h.Dispose(); }
    }
    // Vendor logs are appendable, not private trace state. Keep this boundary
    // separate from Security/Inspect/Snapshot/ProducerFile: a read-only public
    // grant is acceptable here and MUST NOT relax any private-state check.
    const long LogSafeInteger=9007199254740991L;
    const int LogRangeMaximum=1048576;
    const int LogMetadataMaximum=16384;
    internal sealed class LogRangeException : IOException {
        internal readonly string Code;
        internal LogRangeException(string code) { Code=code; }
    }
    static long LogNumber(string text, long maximum) {
        if(String.IsNullOrEmpty(text) || text.Length>16)throw new LogRangeException("WINDOWS_LOG_RANGE_ARGUMENT");
        long value=0;
        foreach(char c in text) {
            if(c<'0' || c>'9' || value>(maximum-(c-'0'))/10)throw new LogRangeException("WINDOWS_LOG_RANGE_ARGUMENT");
            value=value*10+(c-'0');
            if(value>maximum)throw new LogRangeException("WINDOWS_LOG_RANGE_ARGUMENT");
        }
        return value;
    }
    static string LogPath(string path) {
        if(String.IsNullOrEmpty(path) || path.Length<3 || path.Length>32767 ||
            !((path[0]>='A' && path[0]<='Z') || (path[0]>='a' && path[0]<='z')) ||
            path[1]!=':' || path[2]!='\\' || (path.Length>3 && path.EndsWith("\\",StringComparison.Ordinal)))throw new LogRangeException("WINDOWS_LOG_RANGE_PATH");
        foreach(char c in path)if(c<32 || c=='<' || c=='>' || c=='"' || c=='|' || c=='?' || c=='*')throw new LogRangeException("WINDOWS_LOG_RANGE_PATH");
        string full=Canonical(path);
        if(path.TrimEnd('\\')!=full)throw new LogRangeException("WINDOWS_LOG_RANGE_PATH");
        if(path.Length>3)foreach(string part in path.Substring(3).Split('\\')) {
            string device=part.Split('.')[0].TrimEnd(' ');
            if(part.Length==0 || System.Text.RegularExpressions.Regex.IsMatch(part,@"~[0-9]+(?:\.|$)") ||
                System.Text.RegularExpressions.Regex.IsMatch(device,@"\A(?:con|prn|aux|nul|clock\$|conin\$|conout\$|com[1-9\u00b9\u00b2\u00b3]|lpt[1-9\u00b9\u00b2\u00b3])\z",System.Text.RegularExpressions.RegexOptions.IgnoreCase))throw new LogRangeException("WINDOWS_LOG_RANGE_PATH");
        }
        return full;
    }
    // Necessary ADDITION to the caller's fixed Node -> shim -> signed Codex
    // desktop verification and the Host reader's original-pipe owner check.
    // PID/package/hash alone are NOT desktop provenance. No caller-supplied SID,
    // broad capability prefix or APP_PRIVACY label can authorize a writer.
    internal sealed class LogDesktopIdentity : IDisposable {
        const string ExpectedFamily="OpenAI.Codex_2p2nqsd0c76g0";
        [DllImport("kernel32.dll",SetLastError=true)]static extern SafeFileHandle OpenProcess(uint access,bool inherit,uint pid);
        [DllImport("kernel32.dll",SetLastError=true)]static extern uint GetProcessId(SafeFileHandle process);
        [DllImport("kernel32.dll")]static extern uint GetCurrentProcessId();
        [DllImport("kernel32.dll",SetLastError=true)]static extern bool GetProcessTimes(SafeFileHandle process,out long created,out long exited,out long kernel,out long user);
        [DllImport("kernel32.dll",SetLastError=true)]static extern bool ProcessIdToSessionId(uint pid,out uint session);
        [DllImport("kernel32.dll",CharSet=CharSet.Unicode,ExactSpelling=true)]static extern int GetPackageFamilyName(SafeFileHandle process,ref uint count,StringBuilder name);
        [DllImport("userenv.dll",CharSet=CharSet.Unicode,ExactSpelling=true)]static extern int DeriveAppContainerSidFromAppContainerName(string name,out IntPtr sid);
        [DllImport("advapi32.dll")]static extern IntPtr FreeSid(IntPtr sid);
        [DllImport("advapi32.dll",SetLastError=true)]static extern bool OpenProcessToken(SafeFileHandle process,uint access,out SafeFileHandle token);
        [DllImport("advapi32.dll",SetLastError=true)]static extern bool GetTokenInformation(SafeFileHandle token,int kind,IntPtr data,int size,out int returned);
        [StructLayout(LayoutKind.Sequential)]struct Group {public IntPtr Sid;public uint Attributes;}
        [StructLayout(LayoutKind.Sequential)]struct Groups {public uint Count;public Group First;}
        [StructLayout(LayoutKind.Sequential)]struct Luid {public uint Low;public int High;}
        [StructLayout(LayoutKind.Sequential)]struct Statistics {
            public Luid Id,Authentication;public long Expires;public uint Type,Level,Charged,Available,GroupCount,PrivilegeCount;public Luid Modified;
        }
        readonly SafeFileHandle process,token;readonly uint pid,session;readonly long created;
        readonly SecurityIdentifier principal;readonly string tokenKey;readonly bool capabilityMember;
        internal LogDesktopIdentity(int desktopPid) {
            if(desktopPid<1)throw new LogRangeException("WINDOWS_LOG_RANGE_ARGUMENT");pid=(uint)desktopPid;
            try {
                process=OpenProcess(0x1000,false,pid);
                long exited,kernel,user;
                if(process.IsInvalid || GetProcessId(process)!=pid || !GetProcessTimes(process,out created,out exited,out kernel,out user) || exited!=0 || created<=0 ||
                    !ProcessIdToSessionId(pid,out session))throw Rejected();
                string family=Family(process);principal=PackagePrincipal(family);
                token=Token(process);tokenKey=Key(token);capabilityMember=Member(token);Check();
            } catch {Dispose();throw;}
        }
        static LogRangeException Rejected() {return new LogRangeException("WINDOWS_LOG_RANGE_DESKTOP_IDENTITY");}
        static SafeFileHandle Token(SafeFileHandle owner) {
            SafeFileHandle value;
            if(!OpenProcessToken(owner,8,out value)) {if(value!=null)value.Dispose();throw Rejected();}
            if(value==null || value.IsInvalid) {if(value!=null)value.Dispose();throw Rejected();}return value;
        }
        static string Family(SafeFileHandle owner) {
            uint count=0;if(GetPackageFamilyName(owner,ref count,null)!=122 || count<2 || count>256)throw Rejected();
            var name=new StringBuilder((int)count);
            if(GetPackageFamilyName(owner,ref count,name)!=0 || !String.Equals(name.ToString(),ExpectedFamily,StringComparison.OrdinalIgnoreCase))throw Rejected();
            return name.ToString();
        }
        static SecurityIdentifier PackagePrincipal(string family) {
            if(!String.Equals(family,ExpectedFamily,StringComparison.OrdinalIgnoreCase))throw Rejected();
            // Windows documents the PFN -> package SID (-2-) derivation API.
            // The exact -2- -> -3- package-capability RID mapping is an explicit
            // product compatibility rule (WindowsAppSDK discussion #5368), NOT
            // an OS attestation that the current full-trust token contains it.
            // Only this retained, independently verified original package and
            // current user/session/token can authorize this one extra log ACE.
            IntPtr pointer=IntPtr.Zero;
            try {
                if(DeriveAppContainerSidFromAppContainerName(family,out pointer)!=0 || pointer==IntPtr.Zero)throw Rejected();
                var package=new SecurityIdentifier(pointer);var bytes=new byte[package.BinaryLength];package.GetBinaryForm(bytes,0);
                if(bytes.Length!=40 || bytes[0]!=1 || bytes[1]!=8 || bytes[2]!=0 || bytes[3]!=0 || bytes[4]!=0 || bytes[5]!=0 || bytes[6]!=0 || bytes[7]!=15 ||
                    bytes[8]!=2 || bytes[9]!=0 || bytes[10]!=0 || bytes[11]!=0)throw Rejected();
                bytes[8]=3;return new SecurityIdentifier(bytes,0);
            } finally {if(pointer!=IntPtr.Zero)FreeSid(pointer);}
        }
        static IntPtr Information(SafeFileHandle value,int kind,out int length) {
            int required;bool first=GetTokenInformation(value,kind,IntPtr.Zero,0,out required);
            if(first || Marshal.GetLastWin32Error()!=122 || required<4 || required>1024*1024)throw Rejected();
            IntPtr data=Marshal.AllocHGlobal(required);
            try {
                if(!GetTokenInformation(value,kind,data,required,out length) || length<4 || length>required)throw Rejected();
                return data;
            } catch {Marshal.FreeHGlobal(data);throw;}
        }
        static string Key(SafeFileHandle value) {
            int length;IntPtr data=Information(value,10,out length); // TokenStatistics
            try {
                if(length!=Marshal.SizeOf(typeof(Statistics)))throw Rejected();
                var stats=(Statistics)Marshal.PtrToStructure(data,typeof(Statistics));
                if(stats.Type!=1)throw Rejected(); // the process primary token
                return stats.Id.High+":"+stats.Id.Low+":"+stats.Authentication.High+":"+stats.Authentication.Low+":"+stats.Modified.High+":"+stats.Modified.Low;
            } finally {Marshal.FreeHGlobal(data);}
        }
        static SecurityIdentifier TokenSid(IntPtr pointer,IntPtr data,int length) {
            long offset=pointer.ToInt64()-data.ToInt64();
            if(offset<0 || offset>length-8 || Marshal.ReadByte(pointer)!=1)throw Rejected();
            int parts=Marshal.ReadByte(pointer,1);if(parts>15 || 8+parts*4>length-offset)throw Rejected();
            return new SecurityIdentifier(pointer);
        }
        bool Member(SafeFileHandle value) {
            int length;IntPtr data=Information(value,1,out length); // TokenUser
            try {if(length<Marshal.SizeOf(typeof(Group)) || !TokenSid(Marshal.ReadIntPtr(data),data,length).Equals(Sid))throw Rejected();}
            finally {Marshal.FreeHGlobal(data);}
            data=Information(value,12,out length); // TokenSessionId
            try {uint current;if(length!=4 || unchecked((uint)Marshal.ReadInt32(data))!=session ||
                !ProcessIdToSessionId(GetCurrentProcessId(),out current) || current!=session)throw Rejected();}
            finally {Marshal.FreeHGlobal(data);}
            bool found=false;
            foreach(int kind in new[]{2,30}) { // TokenGroups AND TokenCapabilities from the SAME token
                data=Information(value,kind,out length);
                try {
                    uint count=unchecked((uint)Marshal.ReadInt32(data));int start=Marshal.OffsetOf(typeof(Groups),"First").ToInt32(),stride=Marshal.SizeOf(typeof(Group));
                    if(count>4096 || (count>0 && (length<start || count>(uint)((length-start)/stride))))throw Rejected();
                    for(int i=0;i<(int)count;i++) {
                        var group=(Group)Marshal.PtrToStructure(IntPtr.Add(data,start+i*stride),typeof(Group));
                        SecurityIdentifier member=TokenSid(group.Sid,data,length);
                        if(member.Equals(principal) && (group.Attributes&4)!=0 && (group.Attributes&0x10)==0)found=true;
                    }
                } finally {Marshal.FreeHGlobal(data);}
            }
            return found;
        }
        void ProcessCheck(SafeFileHandle value) {
            long now,exited,kernel,user;uint current;
            if(GetProcessId(value)!=pid || !GetProcessTimes(value,out now,out exited,out kernel,out user) || now!=created || exited!=0 ||
                !ProcessIdToSessionId(pid,out current) || current!=session)throw Rejected();
            Family(value);
        }
        internal void Check() {
            ProcessCheck(process);
            if(Key(token)!=tokenKey || Member(token)!=capabilityMember)throw Rejected();
            using(var fresh=OpenProcess(0x1000,false,pid)) {
                if(fresh.IsInvalid)throw Rejected();ProcessCheck(fresh);
                using(var current=Token(fresh))if(Key(current)!=tokenKey || Member(current)!=capabilityMember || Key(current)!=tokenKey)throw Rejected();
                ProcessCheck(fresh);
            }
            if(Key(token)!=tokenKey)throw Rejected();ProcessCheck(process);
        }
        internal bool AllowsWrite(SecurityIdentifier candidate) {
            // Full-trust packaged desktop tokens need not list their package
            // capability. User/session/token continuity is still mandatory;
            // membership is observed for drift, not invented as a prerequisite.
            return principal.Equals(candidate);
        }
        public void Dispose() {if(token!=null)token.Dispose();if(process!=null)process.Dispose();}
    }
    static byte[] LogSecurity(SafeFileHandle handle, bool ancestor, LogDesktopIdentity desktop) {
        IntPtr owner,group,dacl,sacl,sd;
        if(GetSecurityInfo(handle,1,5,out owner,out group,out dacl,out sacl,out sd)!=0)throw new UnauthorizedAccessException();
        try {
            byte[] bytes=new byte[GetSecurityDescriptorLength(sd)];Marshal.Copy(sd,bytes,0,bytes.Length);
            var acl=new RawSecurityDescriptor(bytes,0);
            // Real inherited MSIX log objects can be owned by Administrators.
            // Only current-user/SYSTEM/Administrators owners are accepted in the
            // log boundary; this does not alter private trace owner requirements.
            if(acl.Owner==null || acl.DiscretionaryAcl==null ||
                (!Trusted(acl.Owner) && !(ancestor && acl.Owner.Equals(InstallerSid))))throw new UnauthorizedAccessException();
            foreach(GenericAce item in acl.DiscretionaryAcl) {
                var ace=item as CommonAce;
                if(ace==null || ace.IsCallback || (ace.AceQualifier!=AceQualifier.AccessAllowed && ace.AceQualifier!=AceQualifier.AccessDenied))throw new UnauthorizedAccessException();
                // Above-root anchors retain the existing namespace policy (e.g.
                // Users may create directories on C:). Their handles deny rename
                // and every ID/DACL is revalidated, including fresh path opens.
                // Inside the log root reject ALL non-read foreign grants, even
                // inherit-only grants; no protected-DACL requirement is imposed.
                const uint readOnly=0xA01200A9u; // GENERIC_READ/EXECUTE, READ_CONTROL, SYNCHRONIZE, file read/execute
                if(!ancestor && ace.AceQualifier==AceQualifier.AccessAllowed && !Trusted(ace.SecurityIdentifier) &&
                    (unchecked((uint)ace.AccessMask)&~readOnly)!=0 &&
                    (desktop==null || !desktop.AllowsWrite(ace.SecurityIdentifier)))throw new UnauthorizedAccessException();
            }
            return bytes;
        } finally { LocalFree(sd); }
    }
    static string InspectLog(SafeFileHandle handle,string path,bool directory,bool ancestor,LogDesktopIdentity desktop,char[] actual,out Info info) {
        if(!GetFileInformationByHandle(handle,out info) || (info.Attributes&0x440)!=0 ||
            ((info.Attributes&16)!=0)!=directory || info.Links==0 || (!directory && (info.Links!=1 || (info.Attributes&1)!=0)))throw new IOException();
        Array.Clear(actual,0,actual.Length);uint count=GetFinalPathNameByHandle(handle,actual,(uint)actual.Length,0);
        if(count<4 || count>=actual.Length)throw new IOException();
        string final=new string(actual,0,(int)count);
        if(!final.StartsWith("\\\\?\\",StringComparison.Ordinal) ||
            !String.Equals(final.Substring(4).TrimEnd('\\'),path,StringComparison.OrdinalIgnoreCase))throw new IOException();
        return info.Volume+":"+info.IndexHigh+":"+info.IndexLow+":"+info.Created.dwHighDateTime+":"+info.Created.dwLowDateTime+":"+
            info.Links+":"+info.Attributes+":"+Digest(LogSecurity(handle,ancestor,desktop));
    }
    internal sealed class LogRangeFile : IDisposable {
        readonly List<SafeFileHandle> handles=new List<SafeFileHandle>();
        readonly List<string> paths=new List<string>(),identities=new List<string>();
        readonly string root;readonly bool metadataOnly;readonly char[] actual;readonly LogDesktopIdentity desktop;
        bool directory;Info previous;
        internal LogRangeFile(string rootInput,string pathInput,bool probe,LogDesktopIdentity verifiedDesktop=null) {
            root=LogPath(rootInput);string path=LogPath(pathInput);metadataOnly=probe;
            desktop=verifiedDesktop;if(desktop!=null)desktop.Check();
            if(!(String.Equals(root,path,StringComparison.OrdinalIgnoreCase) || path.StartsWith(root+"\\",StringComparison.OrdinalIgnoreCase)))throw new LogRangeException("WINDOWS_LOG_RANGE_PATH");
            for(string at=path;;) {paths.Add(at);int split=at.LastIndexOf('\\');if(split<2)break;at=at.Substring(0,split);}paths.Reverse();
            actual=new char[Math.Min(32768,path.Length+6)];
            try {
                int metadata=256;
                for(int i=0;i<paths.Count;i++) {
                    bool last=i==paths.Count-1;
                    var handle=Open(i,last && !probe ? 0x80020000u : 0x20080u);handles.Add(handle);
                    if(last) {
                        if(!GetFileInformationByHandle(handle,out previous))throw new IOException();
                        directory=(previous.Attributes&16)!=0;
                        if((directory && !probe) || (String.Equals(root,path,StringComparison.OrdinalIgnoreCase) && !directory))throw new IOException();
                    }
                    Info info;string identity=InspectLog(handle,paths[i],!last || directory,Ancestor(i),desktop,actual,out info);
                    metadata+=identity.Length+3;if(metadata>LogMetadataMaximum)throw new LogRangeException("WINDOWS_LOG_RANGE_PATH");identities.Add(identity);
                    if(last)previous=info;
                }
                Check();
            } catch { Dispose();throw; }
        }
        bool Ancestor(int index) { return paths[index].Length<root.Length; }
        SafeFileHandle Open(int index,uint access) {
            string native=paths[index].Length==2 ? paths[index]+"\\" : paths[index];
            // Pin directory ancestors but let the original logger keep its read,
            // write and delete/rotation handles. A fresh named leaf must still
            // resolve to the retained identity, both before and after reading.
            uint share=index==paths.Count-1 ? 7u : 3u;
            var handle=CreateFile("\\\\?\\"+native,access,share,IntPtr.Zero,3,0x02200000,IntPtr.Zero);
            if(handle.IsInvalid) {handle.Dispose();throw new IOException();}return handle;
        }
        static long Size(Info info) {
            ulong length=((ulong)info.SizeHigh<<32)|info.SizeLow;
            if(length>(ulong)LogSafeInteger)throw new IOException();return (long)length;
        }
        void Progress(Info info) {
            if(!directory) {
                long before=Size(previous),after=Size(info);
                if(after<before || (after==before && (previous.Written.dwHighDateTime!=info.Written.dwHighDateTime ||
                    previous.Written.dwLowDateTime!=info.Written.dwLowDateTime)))throw new LogRangeException("WINDOWS_LOG_RANGE_CHANGED");
            }
            previous=info;
        }
        internal void Check() {
            if(desktop!=null)desktop.Check();
            for(int i=0;i<handles.Count;i++) {
                bool last=i==handles.Count-1;Info info;
                if(identities[i]!=InspectLog(handles[i],paths[i],!last || directory,Ancestor(i),desktop,actual,out info))throw new LogRangeException("WINDOWS_LOG_RANGE_CHANGED");
                if(last)Progress(info);
                // A retained handle alone cannot prove that the name still
                // denotes this file after rename/delete sharing was granted.
                using(var fresh=Open(i,0x20080u)) {
                    if(identities[i]!=InspectLog(fresh,paths[i],!last || directory,Ancestor(i),desktop,actual,out info))throw new LogRangeException("WINDOWS_LOG_RANGE_CHANGED");
                    if(last)Progress(info);
                }
            }
            if(desktop!=null)desktop.Check();
        }
        internal string Snapshot(long offset,int count) {
            if(offset<0 || offset>LogSafeInteger || count<0 || count>LogRangeMaximum || (metadataOnly && count!=0))throw new LogRangeException("WINDOWS_LOG_RANGE_ARGUMENT");
            Check();long captured=directory ? 0 : Size(previous);
            int length=offset>=captured ? 0 : (int)Math.Min((long)count,captured-offset);
            byte[] bytes=new byte[length];
            if(length>0)using(var stream=new FileStream(new SafeFileHandle(handles[handles.Count-1].DangerousGetHandle(),false),FileAccess.Read,1,false)) {
                // No prefix scan/hash or FileStream read-ahead outside the range.
                stream.Seek(offset,SeekOrigin.Begin);int read=0;
                while(read<length) {int n=stream.Read(bytes,read,length-read);if(n<=0)throw new LogRangeException("WINDOWS_LOG_RANGE_CHANGED");read+=n;}
            }
            Check();
            // Captured size describes the EOF used for this fixed range, not a
            // later append. Identity deliberately excludes size/mtime. Across
            // calls the producer MUST compare its 64-byte predecessor guard;
            // metadata never authenticates log text or the current producer PID.
            return "{\"identity\":\""+Digest(Encoding.UTF8.GetBytes(String.Join("|",identities)))+"\",\"chain\":[\""+String.Join("\",\"",identities)+
                "\"],\"bytes\":\""+Convert.ToBase64String(bytes)+"\",\"size\":"+captured.ToString(System.Globalization.CultureInfo.InvariantCulture)+"}";
        }
        public void Dispose() {for(int i=handles.Count-1;i>=0;i--)handles[i].Dispose();handles.Clear();}
    }
    static int LogSnapshotCommand(string[] args) {
        string code;
        try {
            if(args.Length!=6)throw new LogRangeException("WINDOWS_LOG_RANGE_ARGUMENT");
            long offset=LogNumber(args[3],LogSafeInteger);int count=(int)LogNumber(args[4],LogRangeMaximum);
            int pid=(int)LogNumber(args[5],Int32.MaxValue);
            using(var desktop=new LogDesktopIdentity(pid))using(var log=new LogRangeFile(args[1],args[2],count==0,desktop))Console.WriteLine(log.Snapshot(offset,count));
            return 0;
        } catch(LogRangeException error) {code=error.Code;}
          catch(ArgumentException) {code="WINDOWS_LOG_RANGE_ARGUMENT";}
          catch(UnauthorizedAccessException) {code="WINDOWS_LOG_RANGE_UNSAFE";}
          catch {code="WINDOWS_LOG_RANGE_REJECTED";}
        Console.WriteLine("{\"error\":\""+code+"\"}");Console.Error.WriteLine(code);return 1;
    }
    static string Decode(string value) { return Encoding.UTF8.GetString(Convert.FromBase64String(value)); }
    const int BatchRequestBytes=48*1024, BatchResponseBytes=256*1024, BatchMaximum=16384;
    static bool Fields(Dictionary<string,object> value, params string[] names) {
        if(value==null || value.Count!=names.Length)return false;
        foreach(string name in names)if(!value.ContainsKey(name))return false;return true;
    }
    static void UniqueKeys(string text, JavaScriptSerializer json) {
        var objects=new Stack<HashSet<string>>();
        for(int at=0;at<text.Length;at++) {
            if(text[at]=='{')objects.Push(new HashSet<string>(StringComparer.Ordinal));
            else if(text[at]=='}')objects.Pop();
            else if(text[at]=='"') {
                int start=at++;for(;at<text.Length && text[at]!='"';at++)if(text[at]=='\\')at++;
                int next=at+1;while(next<text.Length && Char.IsWhiteSpace(text[next]))next++;
                if(next<text.Length && text[next]==':' && !objects.Peek().Add(json.Deserialize<string>(text.Substring(start,at-start+1))))throw new ArgumentException();
            }
        }
    }
    internal static Dictionary<string,object> ProducerConfiguration(byte[] bytes) {
        if(bytes.Length==0||bytes.Length>16384)throw new ArgumentException();
        string text=new UTF8Encoding(false,true).GetString(bytes);
        var json=new JavaScriptSerializer { MaxJsonLength=16384, RecursionLimit=8 };
        var value=json.DeserializeObject(text) as Dictionary<string,object>;UniqueKeys(text,json);
        if(!Fields(value,"version","target","traceRoot","applicationPath","executable","node","entry","logDirectory","shimPath","shimSha256") ||
            !(value["version"] is int)||(int)value["version"]!=1)throw new ArgumentException();
        var target=value["target"] as Dictionary<string,object>;
        if(!Fields(target,"agentId","hostId","workspaceId","runtimeId"))throw new ArgumentException();
        foreach(string key in new[]{"agentId","hostId","workspaceId","runtimeId"}) {
            string id=target[key] as string;if(String.IsNullOrWhiteSpace(id)||id.Length>256)throw new ArgumentException();foreach(char c in id)if(c<32)throw new ArgumentException();
        }
        foreach(string key in new[]{"traceRoot","applicationPath","executable","node","entry","logDirectory","shimPath"})ProducerPath(value[key] as string);
        string hash=value["shimSha256"] as string;if(hash==null||!System.Text.RegularExpressions.Regex.IsMatch(hash,@"\A[a-f0-9]{64}\z"))throw new ArgumentException();
        return value;
    }
    static object Producer(TraceProcess probe,string encoded) {
        if(encoded.Length>4*((BatchRequestBytes+2)/3))throw new ArgumentException();
        byte[] bytes=Convert.FromBase64String(encoded);
        if(bytes.Length>BatchRequestBytes||Convert.ToBase64String(bytes)!=encoded)throw new ArgumentException();
        string text=new UTF8Encoding(false,true).GetString(bytes);
        var json=new JavaScriptSerializer { MaxJsonLength=BatchRequestBytes, RecursionLimit=4 };
        var request=json.DeserializeObject(text) as Dictionary<string,object>;UniqueKeys(text,json);
        if(!Fields(request,"kind","ProducerPid","Config") || !(request["kind"] is string)||(string)request["kind"]!="producer" ||
            !(request["ProducerPid"] is int)||(int)request["ProducerPid"]<=0 || !(request["Config"] is string))throw new ArgumentException();
        string config=ProducerPath((string)request["Config"]);if(Path.GetFileName(config)!="config.json")throw new ArgumentException();
        return probe.InspectProducer((int)request["ProducerPid"],config);
    }
    static string SnapshotMany(string root, string encoded) {
        if(encoded.Length>4*((BatchRequestBytes+2)/3))throw new ArgumentException();
        byte[] bytes=Convert.FromBase64String(encoded);
        if(bytes.Length>BatchRequestBytes || Convert.ToBase64String(bytes)!=encoded)throw new ArgumentException();
        var json=new JavaScriptSerializer();json.MaxJsonLength=BatchRequestBytes;json.RecursionLimit=8;
        string text=new UTF8Encoding(false,true).GetString(bytes);
        var request=json.DeserializeObject(text) as Dictionary<string,object>;UniqueKeys(text,json);
        if(!Fields(request,"schemaVersion","kind","id","items") || !(request["schemaVersion"] is int) || (int)request["schemaVersion"]!=1 ||
            !(request["kind"] is string) || (string)request["kind"]!="snapshot-many" || !(request["id"] is int) || (int)request["id"]<1)throw new ArgumentException();
        var items=request["items"] as object[];
        if(items==null || items.Length<1 || items.Length>64)throw new ArgumentException();
        int estimate=256;
        for(int index=0;index<items.Length;index++) {
            var item=items[index] as Dictionary<string,object>;
            if(!Fields(item,"index","path","maximum") || !(item["index"] is int) || (int)item["index"]!=index ||
                !(item["path"] is string) || ((string)item["path"]).Length>32767 || !(item["maximum"] is int))throw new ArgumentException();
            string path=Canonical((string)item["path"]);int maximum=(int)item["maximum"];
            if(maximum<0 || maximum>BatchMaximum)throw new ArgumentException();
            // Same conservative bound as JS, before any per-item Snapshot allocation.
            int depth=1;foreach(char c in path)if(c=='\\')depth++;
            estimate+=256+depth*128+4*((maximum+2)/3);
        }
        if(estimate>BatchResponseBytes)throw new ArgumentException();
        var anchors=items.Length>1 ? OpenNamespaceAnchors(root) : null;
        try {
        var results=new string[items.Length];
        Action<int> snapshot=index=> {
            var item=(Dictionary<string,object>)items[index];
            // Full per-item chain/ACL/identity/content checks, including duplicates.
            // Only above-root namespace handles are retained for this command.
            // Do not publish any item before the whole command has succeeded.
            results[index]="{\"index\":"+index+",\"snapshot\":"+SnapshotWithAnchors(root,(string)item["path"],(int)item["maximum"],anchors)+"}";
        };
        // B1 avoids scheduling. Parallel.For joins every in-flight Snapshot even
        // on failure; only this caller assembles/publishes the complete response.
        if(items.Length==1)snapshot(0);
        else Parallel.For(0,items.Length,new ParallelOptions { MaxDegreeOfParallelism=8 },snapshot);
        string result="{\"schemaVersion\":1,\"kind\":\"snapshot-many\",\"id\":"+request["id"]+",\"items\":["+String.Join(",",results)+"]}";
        if(Encoding.UTF8.GetByteCount(result)>BatchResponseBytes)throw new ArgumentException();return result;
        } finally { if(anchors!=null)foreach(var anchor in anchors.Values)anchor.Handle.Dispose(); }
    }
    static int Main(string[] args) {
        try {
            AppContext.SetSwitch("Switch.System.IO.UseLegacyPathHandling", false);
            AppContext.SetSwitch("Switch.System.IO.BlockLongPaths", false);
            Console.InputEncoding=new UTF8Encoding(false); Console.OutputEncoding=new UTF8Encoding(false);
            // Framework's default 256-character writer fragments large pipe replies.
            // Keep each line immediately flushed, with one bounded process-local buffer.
            Console.SetOut(new StreamWriter(Console.OpenStandardOutput(),new UTF8Encoding(false),64*1024,true) { AutoFlush=true });
            if(args.Length>0 && args[0]=="log-snapshot")return LogSnapshotCommand(args);
            if(args.Length==4 && args[0]=="snapshot") {
                Console.WriteLine(Snapshot(args[1],args[2],Int32.Parse(args[3]))); return 0;
            }
            if(args.Length!=5 || args[0]!="serve") throw new ArgumentException();
            string root=Canonical(args[1]);
            using(var probe=new TraceProcess(Int32.Parse(args[2]),Int64.Parse(args[3]),args[4])) {
            var json=new JavaScriptSerializer(); json.MaxJsonLength=48*1024*1024;
            Console.WriteLine("{\"ready\":true}"); Console.Out.Flush();
            while(true) {
                string line=Console.ReadLine(); if(line==null || line=="QUIT") break;
                if(line.Length>100000) throw new ArgumentException();
                try {
                    string[] parts=line.Split('\t');
                    if(parts.Length==3 && parts[0]=="S") Console.WriteLine(Snapshot(root,Decode(parts[1]),Int32.Parse(parts[2])));
                    else if(parts.Length==2 && parts[0]=="B") Console.WriteLine(SnapshotMany(root,parts[1]));
                    else if(parts.Length==2 && parts[0]=="I") {
                        string reply=json.Serialize(Producer(probe,parts[1]));if(Encoding.UTF8.GetByteCount(reply)>256)throw new ArgumentException();Console.WriteLine(reply);
                    }
                    else if(parts.Length==1 && parts[0]=="P") Console.WriteLine(json.Serialize(probe.Inspect()));
                    else if(parts.Length==1 && parts[0]=="T") Console.WriteLine(json.Serialize(probe.Tokens()));
                    else throw new ArgumentException();
                } catch { Console.WriteLine("{\"error\":\"WINDOWS_TRACE_NATIVE_REJECTED\"}"); }
                Console.Out.Flush();
            }
            } return 0;
        } catch { Console.Error.WriteLine("UNSAFE_WINDOWS_TRACE_SNAPSHOT");return 1; }
    }
}
