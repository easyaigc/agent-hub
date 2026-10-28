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
    static string Inspect(SafeFileHandle h, string path, bool directory, bool ancestor, bool root, char[] actual) {
        Info info; if(!GetFileInformationByHandle(h,out info) || (info.Attributes&0x400)!=0 || ((info.Attributes&16)!=0)!=directory || (!directory && info.Links!=1)) throw new IOException();
        Array.Clear(actual,0,actual.Length); uint count=GetFinalPathNameByHandle(h,actual,(uint)actual.Length,0);
        if(count==0 || count>=actual.Length || !String.Equals(new string(actual,0,(int)count).Substring(4).TrimEnd('\\'),path.TrimEnd('\\'),StringComparison.OrdinalIgnoreCase)) throw new IOException();
        return info.Volume+":"+info.IndexHigh+":"+info.IndexLow+":"+info.Created.dwHighDateTime+":"+info.Created.dwLowDateTime+":"+Digest(Security(h,ancestor,root));
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
            if(args.Length==4 && args[0]=="snapshot") {
                Console.WriteLine(Snapshot(args[1],args[2],Int32.Parse(args[3]))); return 0;
            }
            if(args.Length!=5 || args[0]!="serve") throw new ArgumentException();
            string root=Canonical(args[1]);
            var probe=new TraceProcess(Int32.Parse(args[2]),Int64.Parse(args[3]),args[4]);
            var json=new JavaScriptSerializer(); json.MaxJsonLength=48*1024*1024;
            Console.WriteLine("{\"ready\":true}"); Console.Out.Flush();
            while(true) {
                string line=Console.ReadLine(); if(line==null || line=="QUIT") break;
                if(line.Length>100000) throw new ArgumentException();
                try {
                    string[] parts=line.Split('\t');
                    if(parts.Length==3 && parts[0]=="S") Console.WriteLine(Snapshot(root,Decode(parts[1]),Int32.Parse(parts[2])));
                    else if(parts.Length==2 && parts[0]=="B") Console.WriteLine(SnapshotMany(root,parts[1]));
                    else if(parts.Length==1 && parts[0]=="P") Console.WriteLine(json.Serialize(probe.Inspect()));
                    else if(parts.Length==1 && parts[0]=="T") Console.WriteLine(json.Serialize(probe.Tokens()));
                    else throw new ArgumentException();
                } catch { Console.WriteLine("{\"error\":\"WINDOWS_TRACE_NATIVE_REJECTED\"}"); }
                Console.Out.Flush();
            }
            probe.Dispose(); return 0;
        } catch { Console.Error.WriteLine("UNSAFE_WINDOWS_TRACE_SNAPSHOT");return 1; }
    }
}
