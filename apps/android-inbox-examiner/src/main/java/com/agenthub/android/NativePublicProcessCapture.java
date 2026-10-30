package com.agenthub.android;

import android.Manifest;
import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import org.json.JSONObject;
import java.util.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;

/** Public API candidate, same observer Linux UID. DUMP is temporary own UiAutomation identity only.
 * Global visibility is never inferred from adoption: complete self/system/writer parsers must pass. */
final class NativePublicProcessCapture {
    private static final ReentrantLock OWNER=new ReentrantLock();private static volatile boolean unresolved;
    private final UiAutomation automation;private final Context context;
    NativeFixedProcessCapture last;boolean permissionDropped,independentStreamsOpened;private JSONObject rawEvidence;String phase="not-started";
    NativePublicProcessCapture(Context context,UiAutomation automation){this.context=context;this.automation=automation;}
    NaturalProcessVisibility.Completed capture(NaturalProcessVisibility.Diagnostic kind,long deadline)throws Exception{
        NaturalProcessVisibility.require(Build.VERSION.SDK_INT>=31&&Build.VERSION.SDK_INT<=33,"public process candidate supports API31-33 only; older token gate unsupported");
        NaturalProcessVisibility.require(context.getPackageName().equals(NaturalProcessVisibility.SELF)&&context.getApplicationInfo().uid==android.os.Process.myUid()&&(context.getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)!=0,"own debug observer scope");
        NaturalProcessVisibility.require(!unresolved,"previous child/drain/permission cleanup unresolved; no new capture");
        NaturalProcessVisibility.require(OWNER.tryLock(NativeFixedProcessCapture.remaining(deadline),TimeUnit.NANOSECONDS),"capture ownership deadline");
        boolean adopted=false;Process owned=null;Throwable failure=null;permissionDropped=false;
        AtomicBoolean abandon=new AtomicBoolean();AtomicReference<Process> launched=new AtomicReference<>();FutureTask<Process> launch=null;Thread launcher=null;
        try{
            NaturalProcessVisibility.require(!unresolved,"previous capture unresolved");last=new NativeFixedProcessCapture(kind,UUID.randomUUID().toString());phase="adopt-own-dump";
            adopted=true;automation.adoptShellPermissionIdentity(Manifest.permission.DUMP);
            NativeFixedProcessCapture.remaining(deadline-250_000_000L);
            // Exact argv, no shell/string parsing, stdin, arbitrary method or executable path.
            List<String> argv;
            switch(kind){case PS:argv=Arrays.asList("/system/bin/ps","-A","-n","-o","UID,PID,NAME");break;case AM:argv=Arrays.asList("/system/bin/dumpsys","activity","processes");break;default:throw new IllegalArgumentException("fixed diagnostic kind");}
            phase="fixed-child-start";final long launchEnd=deadline-300_000_000L;
            launch=new FutureTask<>(()->{NativeFixedProcessCapture.remaining(launchEnd);Process p=new ProcessBuilder(argv).redirectErrorStream(false).start();launched.set(p);if(abandon.get())p.destroyForcibly();return p;});
            launcher=new Thread(launch,"p9-fixed-launch");launcher.setDaemon(true);launcher.start();
            try{owned=launch.get(NativeFixedProcessCapture.remaining(launchEnd),TimeUnit.NANOSECONDS);}catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw error;}
            phase="independent-streams";independentStreamsOpened=true;
            NaturalProcessVisibility.Completed value=last.collect(owned,deadline-50_000_000L);phase="verified-v2";return value;
        }catch(Throwable error){failure=error;phase="refused";if(error instanceof Exception)throw (Exception)error;if(error instanceof Error)throw (Error)error;throw new AssertionError(error);}
        finally{
            Throwable cleanup=null;abandon.set(true);
            if(owned==null&&launch!=null){
                Process late=launched.get();if(late!=null){try{last.collect(late,deadline-50_000_000L);}catch(Throwable error){cleanup=error;}}
                if(!launch.isDone()||launched.get()!=null&&(!last.childExited||!last.drainsStopped)){unresolved=true;if(cleanup==null)cleanup=new IllegalStateException("V2 fixed process launch/exit unresolved; sealed");}
                launch.cancel(true);
            }
            if(owned!=null&&(last==null||!last.childExited||!last.drainsStopped)){unresolved=true;cleanup=new IllegalStateException("V2 capture lifecycle unresolved; new captures sealed");}
            try{if(adopted)automation.dropShellPermissionIdentity();permissionDropped=true;}catch(Throwable error){unresolved=true;if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error);}
            if(launcher!=null){try{long left=deadline-System.nanoTime();if(left>0)TimeUnit.NANOSECONDS.timedJoin(launcher,left);if(launcher.isAlive()){unresolved=true;if(cleanup==null)cleanup=new IllegalStateException("V2 launch thread unresolved");}}catch(Throwable error){unresolved=true;if(cleanup==null)cleanup=error;else cleanup.addSuppressed(error);}}
            if(System.nanoTime()>deadline&&failure==null&&cleanup==null)cleanup=new IllegalStateException("V2 whole capture deadline exceeded");
            OWNER.unlock();
            if(cleanup!=null){phase="cleanup-refused";if(failure!=null)failure.addSuppressed(cleanup);else if(cleanup instanceof Exception)throw (Exception)cleanup;else throw new AssertionError(cleanup);}
        }
    }
    private static String sha(byte[] bytes)throws Exception{byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder text=new StringBuilder();for(byte b:hash)text.append(String.format(Locale.ROOT,"%02x",b));return text.toString();}
    void persistRaw(File folder)throws Exception{
        if(last==null)return;
        File own=context.getFilesDir().getCanonicalFile(),canonical=folder.getCanonicalFile();
        String relative=own.toPath().relativize(canonical.toPath()).toString();
        NaturalProcessVisibility.require(folder.getAbsoluteFile().equals(canonical)&&relative.matches("(?:natural-public-process-observer-v2/[0-9a-f-]{36}-[0-9a-f-]{36}|public-process-v2-probe/[0-9a-f-]{36})")&&Files.isDirectory(canonical.toPath(),LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(canonical.toPath())&&(Files.getAttribute(canonical.toPath(),"unix:uid",LinkOption.NOFOLLOW_LINKS) instanceof Number)&&((Number)Files.getAttribute(canonical.toPath(),"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue()==android.os.Process.myUid(),"own V2 evidence directory only");
        rawEvidence=new JSONObject();NaturalProcessVisibility.ReadStats counts=last.budget.stats();
        for(int index=0;index<2;index++){
            String role=index==0?"stdout":"stderr";byte[] bytes=index==0?last.stdout:last.stderr;boolean eof=index==0?counts.stdoutEof:counts.stderrEof;int count=index==0?counts.stdout:counts.stderr;
            if(bytes==null){rawEvidence.put(role,new JSONObject().put("available",false).put("completeBytes",false));continue;}
            NaturalProcessVisibility.require(bytes.length<=counts.cap,"bounded retained raw stream");String name="diagnostic-"+last.kind.name()+"-"+last.nonce+"-"+role+".bin";File file=new File(canonical,name);
            try(FileChannel out=FileChannel.open(file.toPath(),EnumSet.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())NaturalProcessVisibility.require(out.write(b)>0,"raw evidence write");out.force(true);}
            byte[] reread=Files.readAllBytes(file.toPath());NaturalProcessVisibility.require(Arrays.equals(bytes,reread),"raw evidence readback");
            rawEvidence.put(role,new JSONObject().put("available",true).put("file",name).put("bytes",bytes.length).put("sha256",sha(bytes)).put("completeBytes",eof&&bytes.length==count));
        }
        FileDescriptor fd=android.system.Os.open(canonical.getAbsolutePath(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW,0);try{android.system.Os.fsync(fd);}finally{android.system.Os.close(fd);}
    }
    JSONObject evidence()throws Exception{
        JSONObject value=new JSONObject().put("evidenceVersion",2).put("captureBackend","public-fixed-process-v2").put("phase",phase).put("launcherUid",android.os.Process.myUid()).put("childUidIndependentlyVerified",false).put("linuxShellUidClaimed",false).put("adoptedPermission",Manifest.permission.DUMP).put("permissionDropCallCompleted",permissionDropped).put("unresolvedCaptureSealed",unresolved);
        if(last!=null){NaturalProcessVisibility.ReadStats s=last.budget.stats();value.put("kind",last.kind.name()).put("commandNonce",last.nonce).put("capBytes",s.cap).put("stdoutBytesObserved",s.stdout).put("stderrBytesObserved",s.stderr).put("combinedBytesObserved",(long)s.stdout+s.stderr).put("stdoutEof",s.stdoutEof).put("stderrEof",s.stderrEof).put("byteCountsAreLowerBounds",!s.stdoutEof||!s.stderrEof).put("independentStderrAvailable",independentStreamsOpened).put("processExitStatus",last.exitStatus).put("childExitConfirmed",last.childExited).put("drainsStopped",last.drainsStopped).put("completionVerified",last.verified&&permissionDropped&&!unresolved);}
        value.put("rawEvidence",rawEvidence==null?JSONObject.NULL:rawEvidence);return value;
    }
}
