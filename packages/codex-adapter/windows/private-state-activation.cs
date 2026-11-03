using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text.RegularExpressions;
using Microsoft.Win32.SafeHandles;

// The responsible activation controller qualifies the new boot and binds the
// rollback capsule before calling Archive. This primitive only moves the one
// retained original marker into its existing operation; it never deletes it.
public static partial class CodexManagePrivateStateSecurity
{
    public sealed class ActivationArchiveLease : IDisposable
    {
        readonly List<SafeFileHandle> directories=new List<SafeFileHandle>();
        readonly bool installation;
        FileStream stream;
        public byte[] Bytes { get; private set; }
        public string Identity { get; private set; }
        public string Sha256 { get; private set; }
        public string ArchivePath { get; private set; }
        internal ActivationArchiveLease(string expectedSha256, string expectedIdentity, bool installation)
        {
            this.installation=installation;
            try {
                string root=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),".config","agent-hub",installation ? "windows-host-v1" : "host-migrations");
                string path=Path.Combine(root,installation ? "current.json" : "pending.json");
                SafeFileHandle parent=PinCompilationDirectory(root,directories);
                CheckCompilationPrivate(parent,true);
                var handle=CreateFile(FullPath(path),0x80000000u|0x00010000u|ReadControl|0x80u,1,IntPtr.Zero,3,0x00200000u,IntPtr.Zero);
                if(handle.IsInvalid) { int error=Marshal.GetLastWin32Error(); handle.Dispose(); throw new Win32Exception(error); }
                try {
                    FileInformation info=CompilationInspect(handle,false);
                    SecurityIdentifier user; using(var current=WindowsIdentity.GetCurrent()) user=current.User;
                    if(user==null) throw new UnauthorizedAccessException();
                    var descriptor=ReadSecurity(handle);
                    CheckRetainedOwner(handle,info,descriptor,user,"qualified-retirement",path);
                    CheckAccess(descriptor,new[]{user.Value,"S-1-5-18","S-1-5-32-544"});
                    stream=new FileStream(handle,FileAccess.Read,4096,false); handle=null;
                    if(stream.Length<1 || stream.Length>4096) throw new IOException();
                    Bytes=new byte[(int)stream.Length]; int offset=0;
                    while(offset<Bytes.Length) { int n=stream.Read(Bytes,offset,Bytes.Length-offset); if(n==0) throw new IOException(); offset+=n; }
                    if(stream.ReadByte()!=-1) throw new IOException();
                    Identity=FileIdentity(stream.SafeFileHandle);
                    using(var hash=SHA256.Create()) Sha256=BitConverter.ToString(hash.ComputeHash(Bytes)).Replace("-","").ToLowerInvariant();
                    if(Sha256!=expectedSha256 || Identity!=expectedIdentity) throw new InvalidOperationException("HOST_RETIREMENT_MARKER_CHANGED");
                } finally { if(handle!=null) handle.Dispose(); }
            } catch { Dispose(); throw; }
        }
        public string Archive(string legacyOperationId)
        {
            if(stream==null || ArchivePath!=null || !Regex.IsMatch(legacyOperationId,"^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$")) throw new InvalidOperationException();
            CheckArchiveTarget(legacyOperationId);
            string directory=ArchiveDirectory(legacyOperationId);
            SafeFileHandle retained=PinCompilationDirectory(directory,directories);
            CheckCompilationPrivate(retained,true);
            if(FileIdentity(stream.SafeFileHandle)!=Identity) throw new IOException();
            int error;
            string name=ArchiveName(legacyOperationId);
            if(!PublishCompilation(stream.SafeFileHandle,retained,directory,name,out error)) throw new Win32Exception(error);
            if(FileIdentity(stream.SafeFileHandle)!=Identity) throw new IOException();
            ArchivePath=Path.Combine(directory,name);
            return Identity;
        }
        public void CheckArchiveTarget(string legacyOperationId)
        {
            if(stream==null || ArchivePath!=null || !Regex.IsMatch(legacyOperationId??"","^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$")) throw new InvalidOperationException();
            string directory=ArchiveDirectory(legacyOperationId);
            SafeFileHandle retained=PinCompilationDirectory(directory,directories);
            CheckCompilationPrivate(retained,true);
            if(File.Exists(Path.Combine(directory,ArchiveName(legacyOperationId)))) throw new InvalidOperationException("HOST_RETIREMENT_ARCHIVE_ALREADY_EXISTS");
        }
        string ArchiveDirectory(string operationId) {
            string hub=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),".config","agent-hub");
            return installation ? Path.Combine(hub,"windows-host-v1") : Path.Combine(hub,"host-migrations",operationId);
        }
        string ArchiveName(string operationId) { return installation ? "current.rolled-back-"+operationId+".json" : "pending.retired.json"; }
        public void Dispose()
        {
            if(stream!=null) { stream.Dispose(); stream=null; }
            for(int i=directories.Count-1;i>=0;i--) directories[i].Dispose();
            directories.Clear();
        }
    }
    public static ActivationArchiveLease RetainLegacyPending(string expectedSha256, string expectedIdentity)
    {
        if(!Regex.IsMatch(expectedSha256??"","^[a-f0-9]{64}$") || !Regex.IsMatch(expectedIdentity??"","^[a-f0-9]{8}:[a-f0-9]{16}$")) throw new ArgumentException();
        return new ActivationArchiveLease(expectedSha256,expectedIdentity,false);
    }
    public static ActivationArchiveLease RetainRegularInstallation(string expectedSha256, string expectedIdentity)
    {
        if(!Regex.IsMatch(expectedSha256??"","^[a-f0-9]{64}$") || !Regex.IsMatch(expectedIdentity??"","^[a-f0-9]{8}:[a-f0-9]{16}$")) throw new ArgumentException();
        return new ActivationArchiveLease(expectedSha256,expectedIdentity,true);
    }
    [DllImport("kernel32.dll")] public static extern ulong GetTickCount64();
}
