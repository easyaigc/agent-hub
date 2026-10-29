package com.agenthub.android;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.*;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Separate-UID self instrumentation. Fixed read-only diagnostics, one SystemUI click and one owned
 * draft edit. No target start/instrumentation, PendingIntent.send, force-stop, permission or post. */
public final class OwnedNaturalNoticeObserver extends Instrumentation {
    private static final String APP="com.easyai.agenthub.android.acceptance", SELF=APP+".coldexaminer";
    private static final String PERMISSION=APP+".permission.P9_NATURAL_OBSERVER", ACTION="com.agenthub.android.P9_NATURAL_CUSTODY_V1";
    private static final String SYSTEM="com.android.systemui", ROW=SYSTEM+":id/expandableNotificationRow";
    private Bundle args; private String lease,attempt; private File folder; private int uid;
    private final CountDownLatch custody=new CountDownLatch(1); private volatile JSONObject published; private volatile PendingIntent original; private volatile Throwable custodyError;
    private BroadcastReceiver receiver; private boolean registered,folderOwned;
    private final AtomicBoolean custodyReceived=new AtomicBoolean();private ExecutorService custodyExecutor;private volatile boolean stopping;
    private String psHash,amHash,psCommandNonce,amCommandNonce,amSectionBoundary;private int systemServerPid;private volatile boolean writerVisible;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);args=arguments;start();}
    private static void check(boolean value,String code){if(!value)throw new AssertionError(code);}
    private static String uuid(String value){check(value!=null&&UUID.fromString(value).toString().equals(value),"canonical UUID required");return value;}
    private static String sha(byte[] bytes)throws Exception{byte[] value=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(java.util.Locale.ROOT,"%02x",b));return out.toString();}
    private void save(String name,JSONObject value)throws Exception{
        check(Arrays.asList("ready","custody","click-intent","edit-intent","result").contains(name),"fixed evidence role");
        File file=new File(folder,name+".json");check(file.createNewFile(),"original observer attempt retained");android.system.Os.chmod(file.getAbsolutePath(),384);
        byte[] bytes=value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);check(bytes.length<=32768,"bounded evidence");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.getFD().sync();}
        java.io.FileDescriptor fd=android.system.Os.open(folder.getAbsolutePath(),android.system.OsConstants.O_RDONLY|android.system.OsConstants.O_NOFOLLOW,0);
        try{android.system.Os.fsync(fd);}finally{android.system.Os.close(fd);}
    }
    private String proofShell(String role)throws Exception{
        check(Arrays.asList("first-open","new-draft").contains(role),"fixed proof role");
        String fixed="run-as "+APP+" cat files/acceptance/P9-natural-os/"+lease+"/"+attempt+"/"+role+".json";
        try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(fixed));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1){check(out.size()+n<=262144,"diagnostic bound");out.write(b,0,n);}return out.toString("UTF-8");
        }
    }
    private byte[] diagnosticBytes(ParcelFileDescriptor fd,AtomicInteger total)throws Exception{
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){NaturalProcessVisibility.require(total.addAndGet(n)<=NaturalProcessVisibility.BUDGET,"combined diagnostic budget");out.write(b,0,n);}return out.toByteArray();
        }
    }
    private NaturalProcessVisibility.Completed diagnostic(NaturalProcessVisibility.Diagnostic kind)throws Exception{
        // Only fixed sh + stdin generated from the closed enum and a fresh canonical UUID.
        String nonce=UUID.randomUUID().toString();ParcelFileDescriptor[] io=getUiAutomation().executeShellCommandRwe("/system/bin/sh");
        AtomicInteger total=new AtomicInteger();ExecutorService drains=Executors.newFixedThreadPool(2);
        CompletionService<Map.Entry<Boolean,byte[]>> completed=new ExecutorCompletionService<>(drains);
        try{
            check(io!=null && io.length==3 && io[0]!=null && io[1]!=null && io[2]!=null,"diagnostic stdout/stdin/stderr unavailable");
            completed.submit(()->new AbstractMap.SimpleImmutableEntry<>(false,diagnosticBytes(io[0],total)));
            completed.submit(()->new AbstractMap.SimpleImmutableEntry<>(true,diagnosticBytes(io[2],total)));
            try(OutputStream input=new ParcelFileDescriptor.AutoCloseOutputStream(io[1])){input.write(NaturalProcessVisibility.script(kind,nonce));}
            byte[] stdout=null,stderr=null;
            // Wait for whichever drain completes/fails first so an over-cap stderr cannot deadlock stdout.
            // No new deadline, retry or extension of the existing native phase windows.
            for(int n=0;n<2;n++){
                Map.Entry<Boolean,byte[]> value;
                try{value=completed.take().get();}catch(ExecutionException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw error;}
                if(value.getKey())stderr=value.getValue();else stdout=value.getValue();
            }
            return NaturalProcessVisibility.complete(kind,nonce,checkNotNull(stdout),checkNotNull(stderr));
        }finally{if(io!=null)for(ParcelFileDescriptor fd:io)if(fd!=null)try{fd.close();}catch(Exception ignored){}drains.shutdownNow();}
    }
    private static byte[] checkNotNull(byte[] value){check(value!=null,"both diagnostic streams required");return value;}
    private NaturalProcessVisibility.Ps processes()throws Exception{
        NaturalProcessVisibility.Completed capture=diagnostic(NaturalProcessVisibility.Diagnostic.PS);NaturalProcessVisibility.Ps p=NaturalProcessVisibility.ps(capture,android.os.Process.myUid(),android.os.Process.myPid(),uid);
        psHash=sha(capture.body.getBytes("UTF-8"));psCommandNonce=capture.nonce;systemServerPid=p.systemServerPid;return p;
    }
    private List<Integer> targetPids()throws Exception{
        return processes().targetPids;
    }
    private NaturalProcessVisibility.Am instrumentations(int writerPid)throws Exception{
        NaturalProcessVisibility.Completed capture=diagnostic(NaturalProcessVisibility.Diagnostic.AM);NaturalProcessVisibility.Am am=NaturalProcessVisibility.am(capture,android.os.Process.myUid(),android.os.Process.myPid(),uid,writerPid);amHash=sha(capture.body.getBytes("UTF-8"));amCommandNonce=capture.nonce;amSectionBoundary=am.sectionBoundary;return am;
    }
    private boolean noTargetInstrumentation()throws Exception{
        return !instrumentations(0).targetPresent;
    }
    private JSONObject visibility()throws Exception{
        return new JSONObject().put("version",1).put("currentSelfPidUidSeen",true).put("crossUidSystemServerPid",systemServerPid).put("systemServerUid",1000)
            .put("selfInstrumentationComponent",SELF+"/"+getClass().getName()).put("selfInstrumentationOwnerVerified",true).put("psSha256",psHash).put("amSha256",amHash).put("completeReadAndStructure",true)
            .put("psCommandNonce",psCommandNonce).put("amCommandNonce",amCommandNonce).put("commandExitStatus",0).put("uniqueLastCompletionMarkers",true).put("stderrEmpty",true).put("amSectionBoundary",amSectionBoundary);
    }
    private String apk(String name)throws Exception{
        ApplicationInfo info=getTargetContext().getPackageManager().getApplicationInfo(name,0);check(info.splitSourceDirs==null||info.splitSourceDirs.length==0,"single frozen APK required");File file=new File(info.sourceDir);check(file.length()>0&&file.length()<=134217728,"bounded APK");
        MessageDigest hash=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=input.read(b))!=-1)hash.update(b,0,n);}StringBuilder out=new StringBuilder();for(byte b:hash.digest())out.append(String.format(Locale.ROOT,"%02x",b));return out.toString();
    }
    private void validateCustody(Intent intent)throws Exception{
        check(ACTION.equals(intent.getAction())&&SELF.equals(intent.getPackage())&&intent.getExtras()!=null&&intent.getExtras().keySet().equals(new HashSet<>(Arrays.asList("publication","pendingIntent"))),"closed custody intent");
        String raw=intent.getStringExtra("publication");check(raw!=null&&raw.getBytes("UTF-8").length<=16384,"bounded publication");JSONObject v=new JSONObject(raw);
        Set<String> publicationKeys=new HashSet<>();Iterator<String> keyIterator=v.keys();while(keyIterator.hasNext())publicationKeys.add(keyIterator.next());
        check(publicationKeys.equals(new HashSet<>(Arrays.asList("version","lease","attempt","plan","planHash","receiptHash","authority","tokenHash","target","appHash","runnerHash","signerHash","examinerHash","uid","bootCount","publishedElapsed","row","permission","othersHash","baseFiles"))),"closed publication");
        check(v.getInt("version")==1&&lease.equals(v.getString("lease"))&&attempt.equals(v.getString("attempt")),"original custody identity");
        JSONObject target=v.getJSONObject("target");check(("p9-cold-"+lease).equals(target.getString("hostId"))&&"cold-workspace".equals(target.getString("workspaceId"))&&"cold-runtime".equals(target.getString("runtimeId")),"exact full target");
        check(v.getInt("uid")==uid&&apk(APP).equals(v.getString("appHash"))&&apk(APP+".test").equals(v.getString("runnerHash"))&&apk(SELF).equals(v.getString("examinerHash")),"installed pair/observer unchanged");
        check(v.getInt("bootCount")==android.provider.Settings.Global.getInt(getTargetContext().getContentResolver(),android.provider.Settings.Global.BOOT_COUNT)&&v.getLong("publishedElapsed")<=SystemClock.elapsedRealtime(),"same original boot/elapsed");
        PendingIntent pending=Build.VERSION.SDK_INT>=33?intent.getParcelableExtra("pendingIntent",PendingIntent.class):intent.getParcelableExtra("pendingIntent");
        check(pending!=null&&pending.getCreatorUid()==uid&&APP.equals(pending.getCreatorPackage())&&pending.isActivity()&&pending.isImmutable(),"original immutable system token custody");
        JSONObject row=v.getJSONObject("row");check(row.getInt("creatorUid")==uid&&APP.equals(row.getString("creatorPackage"))&&row.getBoolean("immutable")&&row.getString("metadata").contains(lease),"row identity");
        int writerPid=v.getJSONObject("plan").getInt("writerPid");processes().requireWriter(uid,writerPid);
        check(instrumentations(writerPid).writerPresent,"original authorized writer instrumentation not visible at custody");check(!stopping,"observer stopping; preserve custody without acknowledgement");
        save("custody",new JSONObject().put("lease",lease).put("attempt",attempt).put("publicationSha256",sha(raw.getBytes("UTF-8"))).put("creatorUid",uid).put("immutable",true)
            .put("writerPidObserved",writerPid).put("writerUidObserved",uid).put("visibility",visibility())
            .put("tokenHandling","Original SDK PendingIntent parcelled over signature-protected fixed broadcast; held only, never sent by observer"));
        published=v;original=pending;writerVisible=true;
    }
    private List<AccessibilityNodeInfo> nodes(AccessibilityNodeInfo root){
        List<AccessibilityNodeInfo> out=new ArrayList<>();ArrayDeque<AccessibilityNodeInfo> todo=new ArrayDeque<>();ArrayDeque<Integer> depth=new ArrayDeque<>();todo.add(root);depth.add(0);
        while(!todo.isEmpty()){AccessibilityNodeInfo node=todo.removeFirst();int d=depth.removeFirst();check(out.size()<2048&&d<=48,"AX bound");out.add(node);for(int n=0;n<node.getChildCount();n++){AccessibilityNodeInfo child=node.getChild(n);if(child!=null){todo.add(child);depth.add(d+1);}}}return out;
    }
    private boolean ownRow(AccessibilityNodeInfo node)throws Exception{
        if(!SYSTEM.contentEquals(node.getPackageName()==null?"":node.getPackageName())||!ROW.equals(node.getViewIdResourceName())||!node.isVisibleToUser()||!node.isClickable()||!node.isEnabled())return false;
        if(node.getActionList().stream().noneMatch(a->a.getId()==AccessibilityNodeInfo.ACTION_CLICK))return false;
        Set<String> text=new HashSet<>();for(AccessibilityNodeInfo n:nodes(node))if(n.isVisibleToUser()&&n.getText()!=null)text.add(n.getText().toString());JSONObject row=published.getJSONObject("row");
        return text.contains(row.getString("title"))&&text.contains(row.getString("summary"))&&text.contains(row.getString("metadata"));
    }
    private AccessibilityNodeInfo exactRow()throws Exception{
        long end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(Build.VERSION.SDK_INT>=33)getUiAutomation().clearCache();AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null&&SYSTEM.contentEquals(root.getPackageName())){List<AccessibilityNodeInfo> matches=new ArrayList<>();for(AccessibilityNodeInfo node:nodes(root))if(ownRow(node))matches.add(node);check(matches.size()<=1,"ambiguous original row");if(matches.size()==1)return matches.get(0);}Thread.sleep(50);
        }throw new AssertionError("original UUID row unavailable; no fallback");
    }
    private void crop(AccessibilityNodeInfo node)throws Exception{
        check(ownRow(node),"row ownership before crop");Rect r=new Rect();node.getBoundsInScreen(r);Bitmap full=getUiAutomation().takeScreenshot();check(full!=null,"screenshot unavailable");
        try{check(!r.isEmpty()&&r.left>=0&&r.top>=0&&r.right<=full.getWidth()&&r.bottom<=full.getHeight(),"own crop bounds");Bitmap part=Bitmap.createBitmap(full,r.left,r.top,r.width(),r.height());
            try{ByteArrayOutputStream bytes=new ByteArrayOutputStream();check(part.compress(Bitmap.CompressFormat.PNG,100,bytes)&&bytes.size()<=2097152,"crop bound");File file=new File(folder,"own-row.png");check(file.createNewFile(),"original crop retained");android.system.Os.chmod(file.getAbsolutePath(),384);try(FileOutputStream out=new FileOutputStream(file)){bytes.writeTo(out);out.getFD().sync();}}finally{if(part!=full)part.recycle();}
        }finally{full.recycle();}
    }
    private JSONObject targetProof(String role)throws Exception{
        long end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){String value=proofShell(role).trim();if(value.startsWith("{")){JSONObject p=new JSONObject(value);check(lease.equals(p.getString("lease"))&&attempt.equals(p.getString("attempt")),"same original proof");return p;}Thread.sleep(100);}throw new AssertionError("original target proof unavailable; no re-open");
    }
    private void editOwnedDraftOnce()throws Exception{
        String old="Owned cold original draft "+lease;long end=SystemClock.uptimeMillis()+10000;AccessibilityNodeInfo edit=null;
        while(SystemClock.uptimeMillis()<end){if(Build.VERSION.SDK_INT>=33)getUiAutomation().clearCache();AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null&&APP.contentEquals(root.getPackageName())){List<AccessibilityNodeInfo> matches=new ArrayList<>();for(AccessibilityNodeInfo n:nodes(root))if(n.isVisibleToUser()&&n.isEditable()&&old.contentEquals(n.getText()==null?"":n.getText()))matches.add(n);check(matches.size()<=1,"ambiguous owned draft");if(matches.size()==1){edit=matches.get(0);break;}}Thread.sleep(50);}
        check(edit!=null,"original owned editable draft unavailable");save("edit-intent",new JSONObject().put("lease",lease).put("attempt",attempt).put("action","one owned UI draft edit; never send"));
        Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Owned natural OS new draft "+lease);check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value),"owned draft edit rejected; no retry");
    }
    @Override public void onStart(){JSONObject result=new JSONObject();Bundle completion=new Bundle();int oldFlags=0;boolean flagsSet=false;boolean clicked=false;
        try{
            check(Build.VERSION.SDK_INT>=34&&SELF.equals(getTargetContext().getPackageName())&&SELF.equals(getContext().getPackageName()),"API34+ self instrumentation with separate diagnostic stderr required");
            lease=uuid(args.getString("p9NaturalOsLease"));attempt=uuid(args.getString("p9NaturalOsAttempt"));Context context=getTargetContext();PackageManager pm=context.getPackageManager();
            InstrumentationInfo own=pm.getInstrumentationInfo(new ComponentName(SELF,getClass().getName()),0);check(SELF.equals(own.targetPackage),"never instrument target");
            uid=pm.getApplicationInfo(APP,0).uid;check(uid!=android.os.Process.myUid()&&pm.checkSignatures(APP,SELF)==PackageManager.SIGNATURE_MATCH&&pm.checkSignatures(APP,APP+".test")==PackageManager.SIGNATURE_MATCH,"independent UID/signed pair");
            File parent=new File(context.getFilesDir().getCanonicalFile(),"natural-os-observer");check(parent.mkdirs()||parent.isDirectory(),"own evidence parent");check(!Files.isSymbolicLink(parent.toPath()),"own evidence symlink");
            folder=new File(parent,lease+"-"+attempt);check(folder.mkdir(),"original observer attempt is never replayed");folderOwned=true;android.system.Os.chmod(folder.getAbsolutePath(),448);
            custodyExecutor=Executors.newSingleThreadExecutor();
            receiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){
                if(!custodyReceived.compareAndSet(false,true)){custodyError=new AssertionError("duplicate custody; preserve original");if(isOrderedBroadcast())setResultCode(android.app.Activity.RESULT_CANCELED);return;}
                if(!isOrderedBroadcast()){custodyError=new AssertionError("original ordered custody required");custody.countDown();return;}
                PendingResult reply=goAsync();custodyExecutor.execute(()->{try{
                    validateCustody(i);reply.setResultCode(android.app.Activity.RESULT_OK);
                    reply.setResultData(sha(("p9-visible-v1:"+lease+":"+attempt+":"+published.getString("planHash")+":"+uid+":"+published.getJSONObject("plan").getInt("writerPid")).getBytes("UTF-8")));
                }catch(Throwable e){custodyError=e;reply.setResultCode(android.app.Activity.RESULT_CANCELED);}finally{custody.countDown();try{reply.finish();}catch(Throwable e){custodyError=e;}}});
            }};
            IntentFilter filter=new IntentFilter(ACTION);Handler handler=new Handler(Looper.getMainLooper());if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,filter,PERMISSION,handler,Context.RECEIVER_EXPORTED);else context.registerReceiver(receiver,filter,PERMISSION,handler);registered=true;
            processes();instrumentations(0);
            save("ready",new JSONObject().put("lease",lease).put("attempt",attempt).put("observerUid",android.os.Process.myUid()).put("observerPid",android.os.Process.myPid()).put("targetUid",uid).put("targetInstrumentationStarted",false).put("visibility",visibility()));
            Bundle ready=new Bundle();ready.putString("p9NaturalObserver","ready:"+lease+":"+attempt);sendStatus(2,ready);
            check(custody.await(120,TimeUnit.SECONDS),"custody not received; no click/repost");if(custodyError!=null)throw custodyError;check(published!=null&&original!=null&&writerVisible,"original token/positive writer witness absent");
            long end=SystemClock.uptimeMillis()+30000;int empty=0;while(SystemClock.uptimeMillis()<end){if(targetPids().isEmpty()&&noTargetInstrumentation()){if(++empty==2)break;}else empty=0;Thread.sleep(100);}check(empty==2,"producer/target process still exists; no kill/force-stop");
            check(apk(APP).equals(published.getString("appHash"))&&apk(APP+".test").equals(published.getString("runnerHash")),"installed pair before click");
            AccessibilityServiceInfo service=getUiAutomation().getServiceInfo();oldFlags=service.flags;service.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;getUiAutomation().setServiceInfo(service);flagsSet=true;
            check(getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),"SystemUI shade unavailable");AccessibilityNodeInfo row=exactRow();crop(row);
            check(custodyError==null&&targetPids().isEmpty()&&noTargetInstrumentation()&&row.refresh()&&ownRow(row),"last absent-process/original-row precondition");
            long clickElapsed=SystemClock.elapsedRealtime();check(published.getInt("bootCount")==android.provider.Settings.Global.getInt(getTargetContext().getContentResolver(),android.provider.Settings.Global.BOOT_COUNT),"same boot before click");
            save("click-intent",new JSONObject().put("lease",lease).put("attempt",attempt).put("targetUidPidsBefore",new JSONArray()).put("targetInstrumentationAbsent",true).put("observerUid",android.os.Process.myUid()).put("bootCount",published.getInt("bootCount")).put("elapsed",clickElapsed).put("visibility",visibility()).put("writerPreviouslyVisible",writerVisible).put("action","one exact original SystemUI ACTION_CLICK"));
            clicked=row.performAction(AccessibilityNodeInfo.ACTION_CLICK);check(clicked,"original SystemUI click rejected; no alternative");
            List<Integer> newPids=new ArrayList<>();end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){newPids=targetPids();if(!newPids.isEmpty())break;Thread.sleep(50);}check(newPids.size()==1&&newPids.get(0)!=published.getJSONObject("plan").getInt("writerPid")&&noTargetInstrumentation(),"one new OS target process required");
            // Only AFTER observing the new target PID do fixed run-as reads touch its private proof.
            JSONObject first=targetProof("first-open");check("first-open-complete".equals(first.getString("phase"))&&newPids.contains(first.getInt("pid"))&&first.getInt("activityEntries")==1&&first.getInt("defaultSelections")==0&&first.getInt("commands")==0&&first.getBoolean("defaultFamilyUnchanged"),"first original Activity/claim proof");
            check(first.getInt("bootCount")==published.getInt("bootCount")&&first.getLong("elapsed")>=clickElapsed&&!first.getString("nonce").equals(published.getJSONObject("plan").getString("writerNonce")),"new consumer identity after original click");
            check(first.getJSONObject("target").toString().equals(published.getJSONObject("target").toString())&&first.getBoolean("scopeMatched")&&first.getInt("locatorPages")==12&&"complete".equals(first.getString("durableClaimPhase"))&&("cold-original-"+lease).equals(first.getString("thread")),"original full target/thread/durable claim");
            check(first.getString("planHash").equals(published.getString("planHash"))&&first.getString("receiptHash").equals(published.getString("receiptHash"))&&first.getString("ticket").equals(published.getJSONObject("plan").getString("ticket"))&&first.getString("appHash").equals(published.getString("appHash"))&&first.getString("runnerHash").equals(published.getString("runnerHash")),"same original receipt/install");
            editOwnedDraftOnce();JSONObject newer=targetProof("new-draft");check("new-draft-observed".equals(newer.getString("phase"))&&newer.getInt("pid")==first.getInt("pid")&&newer.getString("ticket").equals(first.getString("ticket"))&&newer.getString("draftHash").equals(sha(("Owned natural OS new draft "+lease).getBytes("UTF-8")))&&newer.getInt("commands")==0,"new owned draft retained");
            check(custodyError==null&&targetPids().equals(newPids)&&noTargetInstrumentation(),"same independent consumer after edit");
            result.put("status","passed").put("systemUiClicks",1).put("targetUidAbsentBefore",true).put("targetInstrumentationAbsent",true).put("newTargetPid",first.getInt("pid")).put("newTargetNonce",first.getString("nonce")).put("bootCount",first.getInt("bootCount")).put("clickElapsed",clickElapsed).put("defaultSelections",0).put("commands",0).put("originalTokenHeldNotSent",true).put("firstProofSha256",sha(first.toString().getBytes("UTF-8"))).put("newDraftProofSha256",sha(newer.toString().getBytes("UTF-8"))).put("writerPreviouslyVisible",writerVisible).put("visibility",visibility());
        }catch(Throwable error){try{result.put("status","failed").put("failureType",error.getClass().getName()).put("failureCode",error instanceof AssertionError || error instanceof NaturalProcessVisibility.Refused?String.valueOf(error.getMessage()):"external API failure").put("systemUiClickAccepted",clicked);}catch(Exception ignored){}}
        finally{
            stopping=true;
            // Release each observer-owned resource independently, then attempt the original result write.
            // A rejected existing attempt never acquires ownership of that old evidence directory.
            try{if(registered)getTargetContext().unregisterReceiver(receiver);}catch(Exception error){cleanupFailure(result,"receiver",error);}
            try{if(custodyExecutor!=null)custodyExecutor.shutdownNow();}catch(Exception error){cleanupFailure(result,"custodyExecutor",error);}
            try{if(flagsSet){AccessibilityServiceInfo s=getUiAutomation().getServiceInfo();s.flags=oldFlags;getUiAutomation().setServiceInfo(s);}}catch(Exception error){cleanupFailure(result,"uiAutomation",error);}
            try{if(folderOwned)save("result",result.put("lease",lease).put("attempt",attempt).put("observerPid",android.os.Process.myPid()).put("observerUid",android.os.Process.myUid()).put("vendorHostModel",false));}catch(Exception error){cleanupFailure(result,"result",error);}
        }
        completion.putString("p9NaturalResult",result.toString());finish("passed".equals(result.optString("status"))?-1:0,completion);
    }
    private static void cleanupFailure(JSONObject result,String stage,Exception error){
        try{result.put("status","failed").put("cleanupFailure-"+stage,error.getClass().getName());}catch(JSONException ignored){}
    }
}
