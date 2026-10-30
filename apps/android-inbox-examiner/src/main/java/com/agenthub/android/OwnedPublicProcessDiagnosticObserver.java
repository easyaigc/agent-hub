package com.agenthub.android;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.pm.*;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.UUID;

/** Component probe only. No custody, PendingIntent, provider, target start, notification or click. */
public final class OwnedPublicProcessDiagnosticObserver extends Instrumentation {
    private static final String APP="com.easyai.agenthub.android.acceptance",SELF=APP+".coldexaminer";
    private Bundle args;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    private static void check(boolean v,String code){NaturalProcessVisibility.require(v,code);}
    private static String sha(String text)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.getBytes("UTF-8"));StringBuilder out=new StringBuilder();for(byte b:hash)out.append(String.format(java.util.Locale.ROOT,"%02x",b));return out.toString();
    }
    private void save(File folder,JSONObject value)throws Exception{
        byte[] bytes=value.toString().getBytes("UTF-8");check(bytes.length<=16384,"probe evidence cap");File file=new File(folder,"result.json");check(file.createNewFile(),"probe evidence never replaced");android.system.Os.chmod(file.getAbsolutePath(),384);
        try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.getFD().sync();}
        FileDescriptor fd=android.system.Os.open(folder.getAbsolutePath(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW,0);try{android.system.Os.fsync(fd);}finally{android.system.Os.close(fd);}
    }
    @Override public void onStart(){JSONObject result=new JSONObject();File folder=null;boolean owned=false;
        try{
            check(Build.VERSION.SDK_INT>=26&&Build.VERSION.SDK_INT<=33,"API26-33 component probe only");
            check(SELF.equals(getContext().getPackageName())&&SELF.equals(getTargetContext().getPackageName()),"self target only");
            String run=args.getString("p9PublicProcessProbeRun");check(run!=null&&UUID.fromString(run).toString().equals(run),"canonical fresh probe UUID");
            PackageManager pm=getTargetContext().getPackageManager();InstrumentationInfo info=pm.getInstrumentationInfo(new ComponentName(SELF,getClass().getName()),0);
            check(SELF.equals(info.targetPackage),"never instrument App");int targetUid=pm.getApplicationInfo(APP,0).uid;
            check(targetUid!=android.os.Process.myUid()&&pm.checkSignatures(APP,SELF)==PackageManager.SIGNATURE_MATCH,"separate UID signed App");
            File parent=new File(getTargetContext().getFilesDir().getCanonicalFile(),"public-process-v2-probe");check(parent.mkdirs()||parent.isDirectory(),"own evidence parent");check(!Files.isSymbolicLink(parent.toPath()),"no evidence symlink");
            folder=new File(parent,run);check(folder.mkdir(),"fresh probe directory required");owned=true;android.system.Os.chmod(folder.getAbsolutePath(),448);
            if(Build.VERSION.SDK_INT<31){throw new UnsupportedOperationException("API26-30 natural token/public-process capability unsupported");}
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            NativePublicProcessCapture psBackend=new NativePublicProcessCapture(getTargetContext(),getUiAutomation());
            NativePublicProcessCapture amBackend=new NativePublicProcessCapture(getTargetContext(),getUiAutomation());
            NaturalProcessVisibility.Completed ps;
            try{ps=psBackend.capture(NaturalProcessVisibility.Diagnostic.PS,end);}finally{try{psBackend.persistRaw(folder);}finally{result.put("psCapture",psBackend.evidence());}}
            NaturalProcessVisibility.Ps p=NaturalProcessVisibility.ps(ps,android.os.Process.myUid(),android.os.Process.myPid(),targetUid);
            NaturalProcessVisibility.Completed am;try{am=amBackend.capture(NaturalProcessVisibility.Diagnostic.AM,end);}finally{try{amBackend.persistRaw(folder);}finally{result.put("amCapture",amBackend.evidence());}}
            check(System.nanoTime()<=end,"probe capture/raw evidence deadline");
            NaturalProcessVisibility.Am a=NaturalProcessVisibility.am(am,android.os.Process.myUid(),android.os.Process.myPid(),targetUid,0,getClass().getName());
            result.put("status","component-probe-passed").put("evidenceVersion",2).put("captureBackend","public-fixed-process-v2").put("capabilityGate","diagnostics-only-passed").put("run",run).put("api",Build.VERSION.SDK_INT).put("observerPid",android.os.Process.myPid()).put("observerUid",android.os.Process.myUid())
                .put("systemServerPid",p.systemServerPid).put("systemServerUid",1000).put("currentSelfInstrumentationVerified",true).put("selfInstrumentationComponent",SELF+"/"+getClass().getName())
                .put("psCommandNonce",ps.nonce).put("amCommandNonce",am.nonce).put("psSha256",sha(ps.body)).put("amSha256",sha(am.body)).put("amSectionBoundary",a.sectionBoundary)
                .put("targetUidProcessCount",p.targetPids.size()).put("targetInstrumentationPresent",a.targetPresent).put("commandExitStatus",0).put("stderrEmpty",true)
                .put("naturalProof",false).put("writerCustodyVerified",false).put("systemUiClicks",0).put("targetStarts",0).put("tokenInspection",false);
        }catch(Throwable e){try{if(e instanceof UnsupportedOperationException)result.put("unsupportedReason","API26-30 immutable/activity token and public-process backend admission remain unsupported");result.put("capabilityGate","not-met").put("status",e instanceof UnsupportedOperationException?"unsupported":"failed").put("failureType",e.getClass().getName()).put("failureCode",e instanceof NaturalProcessVisibility.Refused?e.getMessage():"external API failure").put("naturalProof",false);}catch(Exception ignored){}}
        finally{if(owned)try{save(folder,result);}catch(Exception e){try{result.put("status","failed").put("evidenceWriteFailed",true);}catch(Exception ignored){}}}
        Bundle completion=new Bundle();completion.putString("p9CompatProbeResult",result.toString());finish("component-probe-passed".equals(result.optString("status"))?-1:0,completion);
    }
}
