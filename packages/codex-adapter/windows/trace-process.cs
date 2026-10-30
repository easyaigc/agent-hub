using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32.SafeHandles;

// Read-only fixed original-instance probe. Signature verification happens once
// in desktop-pipe.ps1; a retained process handle fences that exact live instance.
internal sealed class TraceProcess : IDisposable {
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe,out uint pid);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool ProcessIdToSessionId(uint processId,out uint sessionId);
    [DllImport("kernel32.dll",SetLastError=true)] static extern IntPtr OpenProcess(uint access,bool inherit,uint pid);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool GetProcessTimes(IntPtr process,out long created,out long exited,out long kernel,out long user);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,ExactSpelling=true,SetLastError=true)]
    static extern bool QueryFullProcessImageNameW(IntPtr process,uint flags,[Out] char[] path,ref uint length);
    [DllImport("advapi32.dll",SetLastError=true)] static extern bool OpenProcessToken(IntPtr process,uint access,out IntPtr token);
    [DllImport("advapi32.dll",SetLastError=true)] static extern bool GetTokenInformation(IntPtr token,int kind,IntPtr data,int size,out int returned);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
    [DllImport("ntdll.dll")] static extern int NtQueryInformationProcess(IntPtr process,int kind,IntPtr data,int size,out int returned);
    [DllImport("kernel32.dll",SetLastError=true)] static extern bool ReadProcessMemory(IntPtr process,IntPtr address,byte[] bytes,UIntPtr size,out UIntPtr count);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,ExactSpelling=true)] static extern int GetPackageFullName(IntPtr process,ref uint length,StringBuilder name);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,ExactSpelling=true)] static extern int GetPackagePathByFullName(string name,ref uint length,StringBuilder path);
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct TrustFile {public uint Size;[MarshalAs(UnmanagedType.LPWStr)]public string Path;public IntPtr Handle,Subject;}
    [StructLayout(LayoutKind.Sequential)] struct TrustData {public uint Size;public IntPtr Callback,Client;public uint UI,Revocation,Choice;public IntPtr File;public uint Action;public IntPtr State,URL;public uint Flags,Context;}
    [StructLayout(LayoutKind.Sequential)] struct ProviderSigner {public uint Size;public System.Runtime.InteropServices.ComTypes.FILETIME VerifiedAt;public uint Count;public IntPtr Certificates;public uint Type;public IntPtr Signer;public uint Error;}
    [DllImport("wintrust.dll",ExactSpelling=true)]static extern int WinVerifyTrust(IntPtr window,ref Guid action,ref TrustData data);
    [DllImport("wintrust.dll",ExactSpelling=true)]static extern IntPtr WTHelperProvDataFromStateData(IntPtr state);
    [DllImport("wintrust.dll",ExactSpelling=true)]static extern IntPtr WTHelperGetProvSignerFromChain(IntPtr data,uint index,bool counterSigner,uint counterIndex);
    [StructLayout(LayoutKind.Sequential)] struct Memory {public IntPtr Base,Allocation;public uint Protection;public UIntPtr Size;public uint State,Protect,Type;}
    [DllImport("kernel32.dll")] static extern UIntPtr VirtualQueryEx(IntPtr process,IntPtr address,out Memory memory,UIntPtr size);
    [StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)] struct Entry { public uint Size,Usage,Pid;public UIntPtr Heap;public uint Module,Threads,Parent;public int Priority;public uint Flags;[MarshalAs(UnmanagedType.ByValTStr,SizeConst=260)] public string Name; }
    [DllImport("kernel32.dll",SetLastError=true)] static extern SafeFileHandle CreateToolhelp32Snapshot(uint flags,uint pid);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool Process32FirstW(SafeFileHandle snapshot,ref Entry entry);
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)] static extern bool Process32NextW(SafeFileHandle snapshot,ref Entry entry);
    readonly int pid; readonly long started; readonly string executable; readonly Process original;
    static readonly string User=WindowsIdentity.GetCurrent().User.Value;
    internal TraceProcess(int expectedPid,long expectedStarted,string expectedExecutable) {
        if(IntPtr.Size!=8)throw new IOException(); pid=expectedPid;started=expectedStarted;executable=expectedExecutable;
        original=Process.GetProcessById(pid);try {var retained=original.Handle; using(var pipe=Pipe())Check();}catch{original.Dispose();throw;}
    }
    static long Started(Process process) { return new DateTimeOffset(process.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds(); }
    // Avoid .NET process-info table refreshes while querying the OS afresh every time.
    static uint Session(Process process) {
        uint session;if(!ProcessIdToSessionId((uint)process.Id,out session))throw new IOException();return session;
    }
    // Preserve Framework's fresh QUERY_INFORMATION | VM_READ access check.
    // Query the OS creation-image name, bound to the retained original instance.
    string Image() {
        if(String.IsNullOrEmpty(executable)||executable.Length>32767)throw new IOException();
        IntPtr process=OpenProcess(0x0400|0x0010,false,(uint)pid);if(process==IntPtr.Zero)throw new IOException();
        try {
            long created,exited,kernel,user;
            if(!GetProcessTimes(process,out created,out exited,out kernel,out user)||
                created!=original.StartTime.ToUniversalTime().ToFileTimeUtc())throw new IOException();
            var path=new char[executable.Length+1];uint length=(uint)path.Length;
            if(!QueryFullProcessImageNameW(process,0,path,ref length)||length==0||
                length>=(uint)path.Length||length!=(uint)executable.Length||path[(int)length]!='\0')throw new IOException();
            for(int at=0;at<(int)length;at++)if(path[at]=='\0')throw new IOException();
            return new string(path,0,(int)length);
        }finally{CloseHandle(process);}
    }
    void Check() {
        original.Refresh(); if(original.HasExited || Started(original)!=started ||
            !String.Equals(Image(),executable,StringComparison.OrdinalIgnoreCase) ||
            Session(original)!=Session(Process.GetCurrentProcess()))throw new IOException();
        using(var identity=Identity(original))if(identity.User.Value!=User)throw new UnauthorizedAccessException();
    }
    NamedPipeClientStream Pipe() {
        var pipe=new NamedPipeClientStream(".","codex-ipc",PipeDirection.InOut,PipeOptions.None);
        try {pipe.Connect(250);CheckPipe(pipe);return pipe;}catch{pipe.Dispose();throw;}
    }
    void CheckPipe(NamedPipeClientStream pipe){uint actual;if(!GetNamedPipeServerProcessId(pipe.SafePipeHandle,out actual)||actual!=(uint)pid)throw new IOException();}
    int[] Children(){return Children(pid,"codex.exe");}
    static int[] Children(int parent,string name) {
        var result=new List<int>();using(var snapshot=CreateToolhelp32Snapshot(2,0)) {
            if(snapshot.IsInvalid)throw new IOException();var entry=new Entry();entry.Size=(uint)Marshal.SizeOf(typeof(Entry));
            if(!Process32FirstW(snapshot,ref entry))throw new IOException();
            do {if(entry.Parent==(uint)parent && String.Equals(entry.Name,name,StringComparison.OrdinalIgnoreCase))result.Add((int)entry.Pid);} while(Process32NextW(snapshot,ref entry));
            if(Marshal.GetLastWin32Error()!=18)throw new IOException();
        }result.Sort();return result.ToArray();
    }
    static WindowsIdentity Identity(Process process) {
        return Identity(process.Handle);
    }
    static WindowsIdentity Identity(IntPtr process) {
        IntPtr token;if(!OpenProcessToken(process,8,out token))throw new IOException();
        try{return new WindowsIdentity(token);}finally{CloseHandle(token);}
    }
    static byte[] Read(Process process,IntPtr address,int length) {
        if(length<0||length>1024*1024)throw new IOException();var bytes=new byte[length];UIntPtr count;
        if(!ReadProcessMemory(process.Handle,address,bytes,(UIntPtr)length,out count)||count.ToUInt64()!=(ulong)length)throw new IOException();return bytes;
    }
    static IntPtr Parameters(Process process,out int parent) {
        IntPtr basic=Marshal.AllocHGlobal(48);try {int count;if(NtQueryInformationProcess(process.Handle,0,basic,48,out count)!=0)throw new IOException();
            parent=Marshal.ReadIntPtr(basic,40).ToInt32();var peb=Marshal.ReadIntPtr(basic,8);
            return new IntPtr(BitConverter.ToInt64(Read(process,IntPtr.Add(peb,0x20),8),0));
        }finally{Marshal.FreeHGlobal(basic);}
    }
    static string Command(Process process,IntPtr parameters) {
        byte[] value=Read(process,IntPtr.Add(parameters,0x70),16);int length=BitConverter.ToUInt16(value,0);
        return Encoding.Unicode.GetString(Read(process,new IntPtr(BitConverter.ToInt64(value,8)),length));
    }
    static string Root(Process process,IntPtr parameters) {
        var environment=new IntPtr(BitConverter.ToInt64(Read(process,IntPtr.Add(parameters,0x80),8),0));Memory region;
        if(VirtualQueryEx(process.Handle,environment,out region,(UIntPtr)Marshal.SizeOf(typeof(Memory)))==UIntPtr.Zero)throw new IOException();
        long available=(long)region.Size.ToUInt64()-(environment.ToInt64()-region.Base.ToInt64());if(available<=0)throw new IOException();
        string text=Encoding.Unicode.GetString(Read(process,environment,(int)Math.Min(available,1024*1024)));int end=text.IndexOf("\0\0",StringComparison.Ordinal);if(end<0)throw new IOException();
        string found=null;foreach(string entry in text.Substring(0,end).Split('\0')) if(entry.StartsWith("CODEX_ROLLOUT_TRACE_ROOT=",StringComparison.OrdinalIgnoreCase)){if(found!=null)throw new IOException();found=entry.Substring(25);}return found;
    }
    static bool Same(string a,string b){return String.Equals(a,b,StringComparison.OrdinalIgnoreCase);}
    static int Parent(IntPtr handle) {
        IntPtr basic=Marshal.AllocHGlobal(48);try {int count;
            if(NtQueryInformationProcess(handle,0,basic,48,out count)!=0||count<48)throw new IOException();
            long parent=Marshal.ReadIntPtr(basic,40).ToInt64();if(parent<1||parent>Int32.MaxValue)throw new IOException();return (int)parent;
        }finally{Marshal.FreeHGlobal(basic);}
    }
    static string ProcessImage(IntPtr handle) {
        var path=new char[32768];uint length=(uint)path.Length;
        if(!QueryFullProcessImageNameW(handle,0,path,ref length)||length==0||length>=path.Length)throw new IOException();
        return TraceState.ProducerPath(new string(path,0,(int)length));
    }
    // Retained handles, exact kernel creation ticks and a fresh access check on
    // both sides of every probe prevent PID reuse and stale cached Process data.
    sealed class ProcessIdentity : IDisposable {
        internal readonly Process Value;internal readonly int Id,ParentId;internal readonly long Created,Milliseconds;internal readonly string ImagePath;
        internal ProcessIdentity(int id) {
            if(id<1)throw new IOException();Id=id;Value=Process.GetProcessById(id);
            try {
                var handle=Value.Handle;long exited,kernel,user;
                if(!GetProcessTimes(handle,out Created,out exited,out kernel,out user)||exited!=0)throw new IOException();
                ParentId=Parent(handle);ImagePath=ProcessImage(handle);Milliseconds=new DateTimeOffset(DateTime.FromFileTimeUtc(Created)).ToUnixTimeMilliseconds();Check();
            }catch{Value.Dispose();throw;}
        }
        void CheckHandle(IntPtr handle) {
            long created,exited,kernel,user;uint session,current;
            if(!GetProcessTimes(handle,out created,out exited,out kernel,out user)||created!=Created||exited!=0||
                Parent(handle)!=ParentId||!Same(ProcessImage(handle),ImagePath)||
                !ProcessIdToSessionId((uint)Id,out session)||!ProcessIdToSessionId((uint)Process.GetCurrentProcess().Id,out current)||session!=current)throw new IOException();
            using(var identity=Identity(handle))if(identity.User.Value!=User)throw new UnauthorizedAccessException();
        }
        internal void Check() {
            CheckHandle(Value.Handle);IntPtr fresh=OpenProcess(0x0400|0x0010,false,(uint)Id);if(fresh==IntPtr.Zero)throw new IOException();
            try{CheckHandle(fresh);}finally{CloseHandle(fresh);}
        }
        public void Dispose(){Value.Dispose();}
    }
    string PackageRoot(out string packageName) {
        packageName=null;uint count=0;int status=GetPackageFullName(original.Handle,ref count,null);
        if(status==15700)return null; // unpackaged: no inferred Store trust
        if(status!=122||count<2||count>32768)throw new IOException();var name=new StringBuilder((int)count);
        if(GetPackageFullName(original.Handle,ref count,name)!=0)throw new IOException();packageName=name.ToString();
        count=0;if(GetPackagePathByFullName(packageName,ref count,null)!=122||count<2||count>32768)throw new IOException();
        var path=new StringBuilder((int)count);if(GetPackagePathByFullName(packageName,ref count,path)!=0)throw new IOException();
        string root=TraceState.ProducerPath(path.ToString());if(!executable.StartsWith(root+"\\",StringComparison.OrdinalIgnoreCase))throw new IOException();return root;
    }
    void Application(string configured) {
        if(Same(configured,executable))return;
        // Only a historical version of THIS current OS-reported package family
        // may differ. No account, basename, PATH search or arbitrary ancestor trust.
        string full;string root=PackageRoot(out full);if(root==null)throw new IOException();
        string[] current=full.Split('_');if(current.Length!=5)throw new IOException();
        string parent=Path.GetDirectoryName(root)+"\\";
        if(!configured.StartsWith(parent,StringComparison.OrdinalIgnoreCase))throw new IOException();
        string relative=configured.Substring(parent.Length);int slash=relative.IndexOf('\\');if(slash<1)throw new IOException();
        string[] old=relative.Substring(0,slash).Split('_');
        if(old.Length!=5||!Same(old[0],current[0])||!Same(old[2],current[2])||!Same(old[3],current[3])||!Same(old[4],current[4])||
            !Regex.IsMatch(old[1],@"\A[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+\z")||!Same(relative.Substring(slash+1),executable.Substring(root.Length+1)))throw new IOException();
    }
    static void SignedCli(string path) {
        // A process package name alone is not proof of Store signature kind.
        // Verify the actual configured CLI's Authenticode policy and read the
        // signer from THAT successful trust state, not an unrelated embedded cert.
        // No UI, revocation fetch or network/certificate download on this reader.
        var file=new TrustFile {Size=(uint)Marshal.SizeOf(typeof(TrustFile)),Path=path};
        IntPtr pointer=Marshal.AllocHGlobal(Marshal.SizeOf(typeof(TrustFile)));bool marshaled=false;
        var data=new TrustData {Size=(uint)Marshal.SizeOf(typeof(TrustData)),UI=2,Choice=1,File=pointer,Action=1,Flags=0x1010};
        var action=new Guid("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");
        try {
            Marshal.StructureToPtr(file,pointer,false);marshaled=true;
            if(WinVerifyTrust(new IntPtr(-1),ref action,ref data)!=0||data.State==IntPtr.Zero)throw new IOException();
            IntPtr provider=WTHelperProvDataFromStateData(data.State);if(provider==IntPtr.Zero)throw new IOException();
            IntPtr signer=WTHelperGetProvSignerFromChain(provider,0,false,0);if(signer==IntPtr.Zero)throw new IOException();
            var verified=(ProviderSigner)Marshal.PtrToStructure(signer,typeof(ProviderSigner));
            if(verified.Error!=0||verified.Count<1||verified.Count>64||verified.Certificates==IntPtr.Zero)throw new IOException();
            // CRYPT_PROVIDER_CERT begins with DWORD cbStruct and PCCERT_CONTEXT.
            int size=Marshal.ReadInt32(verified.Certificates);if(size<IntPtr.Size*2)throw new IOException();
            IntPtr certificate=Marshal.ReadIntPtr(verified.Certificates,IntPtr.Size);if(certificate==IntPtr.Zero)throw new IOException();
            using(var cert=new X509Certificate2(certificate))if(!Regex.IsMatch(cert.Subject,@"(?:^|,\s*)(?:CN|O)=""?OpenAI(?:[, ""]|$)",RegexOptions.IgnoreCase))throw new IOException();
        }finally {
            try{if(data.State!=IntPtr.Zero){data.Action=2;WinVerifyTrust(new IntPtr(-1),ref action,ref data);}}
            finally{if(marshaled)Marshal.DestroyStructure(pointer,typeof(TrustFile));Marshal.FreeHGlobal(pointer);}
        }
    }
    sealed class ProducerChain : IDisposable {
        readonly TraceProcess owner;internal ProcessIdentity Node,Shim;
        TraceState.ProducerFile configFile,shimFile,traceDirectory,logDirectory;
        internal Dictionary<string,object> Config;
        internal string Text(string key){return (string)Config[key];}
        internal ProducerChain(TraceProcess owner,int producerPid,string configPath) {
            this.owner=owner;
            try {
                owner.Check();configPath=TraceState.ProducerPath(configPath);if(Path.GetFileName(configPath)!="config.json")throw new IOException();
                string directory=Path.GetDirectoryName(configPath);
                configFile=new TraceState.ProducerFile(directory,configPath,false,true,true);
                Config=TraceState.ProducerConfiguration(configFile.Bytes(16384));
                Node=new ProcessIdentity(producerPid);Shim=new ProcessIdentity(Node.ParentId);
                if(Node.Id==owner.pid||Shim.Id==owner.pid||Node.Id==Shim.Id||Shim.ParentId!=owner.pid||
                    !Same(Node.ImagePath,Text("node"))||!Same(Path.GetFileName(Node.ImagePath),"node.exe")||
                    !Same(Shim.ImagePath,Text("shimPath"))||!Same(Shim.ImagePath,Path.Combine(directory,"launcher.exe"))||
                    !Same(Path.GetFileName(Text("executable")),"codex.exe")||
                    Path.GetFileName(Text("entry"))!="desktop-native-suggestions-runtime.mjs"||
                    Same(Text("executable"),Shim.ImagePath)||Same(Text("executable"),owner.executable)||
                    owner.original.StartTime.ToUniversalTime().ToFileTimeUtc()>Shim.Created||Shim.Created>Node.Created)throw new IOException();
                owner.Application(Text("applicationPath"));
                shimFile=new TraceState.ProducerFile(directory,Shim.ImagePath,false,true,true);
                if(shimFile.Sha256(16*1024*1024)!=Text("shimSha256"))throw new IOException();
                traceDirectory=new TraceState.ProducerFile(Text("traceRoot"),Text("traceRoot"),true,true);
                logDirectory=new TraceState.ProducerFile(Text("logDirectory"),Text("logDirectory"),true,false);
                Check();
            }catch{Dispose();throw;}
        }
        internal void Check() {
            owner.Check();Node.Check();Shim.Check();configFile.Check();shimFile.Check();traceDirectory.Check();logDirectory.Check();
            if(shimFile.Sha256(16*1024*1024)!=Text("shimSha256"))throw new IOException();
        }
        public void Dispose() {
            if(logDirectory!=null)logDirectory.Dispose();if(traceDirectory!=null)traceDirectory.Dispose();
            if(shimFile!=null)shimFile.Dispose();if(configFile!=null)configFile.Dispose();if(Shim!=null)Shim.Dispose();if(Node!=null)Node.Dispose();
        }
    }
    internal object InspectProducer(int producerPid,string configPath) {
        using(var pipe=Pipe())using(var chain=new ProducerChain(this,producerPid,configPath)) {
            chain.Check();Check();CheckPipe(pipe);
            return new{pid=chain.Node.Id,desktopPid=pid,desktopStartedAt=started,wrapperStartedAt=chain.Node.Milliseconds};
        }
    }
    void WrappedExecutors(string root,List<object> entries) {
        // Only the fixed immediate shim -> configured Node -> original CLI
        // edges are considered. Names are enumeration hints, never authority.
        foreach(int shimId in Children(pid,"launcher.exe"))using(var shim=new ProcessIdentity(shimId)) {
            if(shim.ParentId!=pid)continue;
            string config=Path.Combine(Path.GetDirectoryName(shim.ImagePath),"config.json");
            foreach(int nodeId in Children(shimId,"node.exe"))using(var chain=new ProducerChain(this,nodeId,config)) {
                if(chain.Shim.Id!=shimId||chain.Shim.Created!=shim.Created||!Same(root,chain.Text("traceRoot")))throw new IOException();
                int parent;string nodeRoot=Root(chain.Node.Value,Parameters(chain.Node.Value,out parent));
                if(parent!=shimId||!Same(root,nodeRoot))throw new IOException();
                foreach(int childId in Children(nodeId,"codex.exe"))using(var child=new ProcessIdentity(childId)) {
                    if(child.ParentId!=nodeId||child.Created<chain.Node.Created||!Same(Path.GetFileName(child.ImagePath),"codex.exe"))throw new IOException();
                    // The actual desktop may update its signed CLI independently of
                    // its Store package. Accept only its real child under the OS
                    // package or the official per-user versioned Codex bin root.
                    // The historical configured executable is never authority.
                    string package;string packageRoot=PackageRoot(out package);
                    string looseRoot=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"OpenAI","Codex","bin");
                    string imageRoot=packageRoot;
                    if(packageRoot==null||!child.ImagePath.StartsWith(packageRoot+"\\",StringComparison.OrdinalIgnoreCase)) {
                        if(!child.ImagePath.StartsWith(looseRoot+"\\",StringComparison.OrdinalIgnoreCase)||
                            !Regex.IsMatch(child.ImagePath.Substring(looseRoot.Length+1),@"\A[a-fA-F0-9]{16}\\codex\.exe\z",RegexOptions.IgnoreCase))throw new IOException();
                        imageRoot=looseRoot;
                    }
                    using(var image=new TraceState.ProducerFile(imageRoot,child.ImagePath,false,false)) {
                        SignedCli(child.ImagePath);
                        IntPtr parameters=Parameters(child.Value,out parent);if(parent!=nodeId||!Regex.IsMatch(Command(child.Value,parameters),@"(?:^|\s)app-server(?:\s|$)"))throw new IOException();
                        string trace=Root(child.Value,parameters);if(!Same(root,trace))throw new IOException();
                        child.Check();chain.Check();shim.Check();image.Check();
                        entries.Add(new{pid=child.Id,parentPid=nodeId,startedAtUnixMs=child.Milliseconds,traceRoot=trace});
                    }
                }
            }
        }
    }
    internal object Inspect() {
        using(var pipe=Pipe()) {
        Check();int ignored;string root=Root(original,Parameters(original,out ignored));var entries=new List<object>();
        foreach(int childId in Children())using(var child=Process.GetProcessById(childId)) {
            try {int parent;IntPtr parameters=Parameters(child,out parent);if(parent!=pid)continue;
                using(var identity=Identity(child))if(identity.User.Value!=User)throw new UnauthorizedAccessException();
                if(Session(child)!=Session(original))throw new IOException();
                if(!Regex.IsMatch(Command(child,parameters),@"(?:^|\s)app-server(?:\s|$)"))continue;
                long at=Started(child);string trace=Root(child,parameters);child.Refresh();if(child.HasExited||Started(child)!=at)throw new IOException();
                entries.Add(new{pid=child.Id,parentPid=pid,startedAtUnixMs=at,traceRoot=trace});
            }catch(InvalidOperationException){throw new IOException();}
        }
        // Preserve the direct official executor path and its existing contract.
        // Unrelated child names cannot veto a valid direct original executor.
        if(entries.Count==0)WrappedExecutors(root,entries);
        if(entries.Count==0)throw new IOException();Check();CheckPipe(pipe);return new{pid=pid,startedAtUnixMs=started,traceRoot=root,executors=entries};
        }
    }
    static object Token(Process process) {
        IntPtr token;if(!OpenProcessToken(process.Handle,8,out token))throw new IOException();try {
            using(var identity=new WindowsIdentity(token))if(identity.User.Value!=User)throw new UnauthorizedAccessException();
            int count;GetTokenInformation(token,4,IntPtr.Zero,0,out count);IntPtr data=Marshal.AllocHGlobal(count);string owner;
            try{if(!GetTokenInformation(token,4,data,count,out count))throw new IOException();owner=new SecurityIdentifier(Marshal.ReadIntPtr(data)).Value;}finally{Marshal.FreeHGlobal(data);}
            data=Marshal.AllocHGlobal(4);int elevated;try{if(!GetTokenInformation(token,20,data,4,out count))throw new IOException();elevated=Marshal.ReadInt32(data);}finally{Marshal.FreeHGlobal(data);}
            return new{pid=process.Id,userSid=User,defaultOwnerSid=owner,elevated=elevated!=0,session=process.SessionId};
        }finally{CloseHandle(token);}
    }
    internal object Tokens(){using(var pipe=Pipe()){Check();var entries=new List<object>();foreach(int childId in Children())using(var child=Process.GetProcessById(childId)){int parent;var parameters=Parameters(child,out parent);if(parent==pid&&Regex.IsMatch(Command(child,parameters),@"(?:^|\s)app-server(?:\s|$)"))entries.Add(Token(child));}using(var current=Process.GetCurrentProcess()){int parent;Parameters(current,out parent);using(var host=Process.GetProcessById(parent)){var result=new{original=Token(original),helper=Token(current),testHost=Token(host),executors=entries};Check();CheckPipe(pipe);return result;}}}}
    public void Dispose(){original.Dispose();}
}
