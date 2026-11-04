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

/** Separate-UID self instrumentation. Fixed read-only diagnostics, one SystemUI click and one owned
 * draft edit. No target start/instrumentation, PendingIntent.send, force-stop, permission or post. */
public final class OwnedNaturalNoticeObserver extends Instrumentation {
    private static final String APP="com.easyai.agenthub.android.acceptance", SELF=APP+".coldexaminer";
    private static final String PERMISSION=APP+".permission.P9_NATURAL_OBSERVER", ACTION="com.agenthub.android.P9_NATURAL_CUSTODY_V1";
    private static final String SYSTEM="com.android.systemui", ROW=SYSTEM+":id/expandableNotificationRow";
    private Bundle args; private String lease,attempt; private File folder; private int uid;
    private static final String DIAGNOSTIC_MODE="systemui-rejected-node-readonly-v1", DIAGNOSTIC_SELECTOR="first-rejection-next-direct-row-child-v1";
    private boolean diagnosticRequested,modeValidated,normalActionsAllowed; private String diagnosticId;
    private void configureMode() {
        check(args!=null,"explicit original observer arguments");
        diagnosticRequested=args.containsKey("p9SystemUiMode")||args.containsKey("p9SystemUiDiagnosticSelector")||args.containsKey("p9SystemUiDiagnosticId");
        Set<String> expected=new HashSet<>(Arrays.asList("p9NaturalOsLease","p9NaturalOsAttempt"));
        if(diagnosticRequested) {
            expected.addAll(Arrays.asList("p9SystemUiMode","p9SystemUiDiagnosticSelector","p9SystemUiDiagnosticId"));
            check(DIAGNOSTIC_MODE.equals(args.getString("p9SystemUiMode"))&&DIAGNOSTIC_SELECTOR.equals(args.getString("p9SystemUiDiagnosticSelector")),"fixed read-only diagnostic mode/selector");
            diagnosticId=uuid(args.getString("p9SystemUiDiagnosticId"));
            check(Build.VERSION.SDK_INT==36&&!diagnosticId.equals(lease)&&!diagnosticId.equals(attempt)&&!lease.equals(attempt),"API36 new distinct diagnostic scope");
        }
        check(args.keySet().equals(expected),"closed observer selectors; no arbitrary controls");
        modeValidated=true;normalActionsAllowed=!diagnosticRequested;
    }
    private void requireNormalAction(String role) {check(modeValidated&&normalActionsAllowed&&!diagnosticRequested,"DIAGNOSTIC_ONLY blocks "+role);}
    private static void closed(JSONObject value,String... fields)throws Exception {
        Set<String> actual=new HashSet<>();Iterator<String> names=value.keys();while(names.hasNext())actual.add(names.next());
        check(actual.equals(new HashSet<>(Arrays.asList(fields))),"closed diagnostic publication field set");
    }
    private void diagnosticPublication(JSONObject value)throws Exception {
        check(modeValidated&&diagnosticRequested&&!normalActionsAllowed,"validated diagnostic-only mode");
        JSONObject target=value.getJSONObject("target"),plan=value.getJSONObject("plan"),row=value.getJSONObject("row");
        closed(target,"agentId","hostId","workspaceId","runtimeId");
        check("codex".equals(target.getString("agentId"))&&("p9-cold-"+lease).equals(target.getString("hostId"))
            &&"cold-workspace".equals(target.getString("workspaceId"))&&"cold-runtime".equals(target.getString("runtimeId")),"fixed synthetic diagnostic target");
        closed(plan,"lease","case","ticket","writerPid","writerNonce","issuedAt","appHash","runnerHash","receiptHash","authority");
        check(lease.equals(plan.getString("lease"))&&"original".equals(plan.getString("case"))&&plan.getInt("writerPid")>0,"original diagnostic fixture only");
        uuid(plan.getString("ticket"));uuid(plan.getString("writerNonce"));
        check(plan.getString("appHash").equals(value.getString("appHash"))&&plan.getString("runnerHash").equals(value.getString("runnerHash"))
            &&plan.getString("receiptHash").equals(value.getString("receiptHash"))&&plan.getString("authority").equals(value.getString("authority")),"diagnostic original plan/install binding");
        closed(row,"tag","id","channel","title","summary","metadata","postTime","creatorPackage","creatorUid","immutable");
        JSONObject expectedTarget=new JSONObject().put("agentId","codex").put("hostId","p9-cold-"+lease).put("workspaceId","cold-workspace").put("runtimeId","cold-runtime");
        String prefix="native-chat-acceptance-v1."+lease+".";
        String expectedTag=prefix+sha(expectedTarget.toString().getBytes("UTF-8"))+"."+sha(("cold-event:"+lease).getBytes("UTF-8"));
        check(row.getInt("id")==1&&expectedTag.equals(row.getString("tag"))&&(prefix+"completed.none.no-badge").equals(row.getString("channel"))
            &&row.getInt("creatorUid")==uid&&APP.equals(row.getString("creatorPackage"))&&row.getBoolean("immutable")&&row.getLong("postTime")>0,"exact original synthetic notification identity");
        for(String field:Arrays.asList("title","summary","metadata"))check(!row.getString(field).isEmpty()&&row.getString(field).length()<=4096,"bounded original synthetic visible correlation");
        JSONObject permission=value.getJSONObject("permission");closed(permission,"granted","appEnabled","importance","badge","sound","delegated");
        check(permission.getBoolean("granted")&&permission.getBoolean("appEnabled")&&permission.getInt("importance")>0
            &&!permission.getBoolean("badge")&&"none".equals(permission.getString("sound"))&&!permission.getBoolean("delegated"),"exact silent no-badge synthetic preparation");
        check(row.getString("metadata").contains(lease)&&value.getLong("publishedElapsed")>=0
            &&SystemClock.elapsedRealtime()-value.getLong("publishedElapsed")<=900000,"fresh synthetic diagnostic publication");
    }
    private final CountDownLatch custody=new CountDownLatch(1); private volatile JSONObject published; private volatile PendingIntent original; private volatile Throwable custodyError;
    private BroadcastReceiver receiver; private boolean registered,folderOwned;
    private final AtomicBoolean custodyReceived=new AtomicBoolean();private ExecutorService custodyExecutor;private volatile boolean stopping;
    private String psHash,amHash,psCommandNonce,amCommandNonce,amSectionBoundary;private int systemServerPid;private volatile boolean writerVisible;
    private final JSONObject identityEvidence=new JSONObject();private volatile JSONObject writerLive;private volatile String publicationSha256;
    private JSONObject identitySnapshot()throws Exception{synchronized(identityEvidence){return new JSONObject(identityEvidence.toString());}}
    @Override public void onCreate(Bundle arguments){args=arguments;diagnosticRequested=arguments!=null&&(arguments.containsKey("p9SystemUiMode")||arguments.containsKey("p9SystemUiDiagnosticSelector")||arguments.containsKey("p9SystemUiDiagnosticId"));super.onCreate(arguments);start();}
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
        check(Arrays.asList("producer","first-open","new-draft").contains(role),"fixed proof role");
        String fixed="run-as "+APP+" cat files/acceptance/P9-natural-os/"+lease+"/"+attempt+"/"+role+".json";
        try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(fixed));ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1){check(out.size()+n<=262144,"diagnostic bound");out.write(b,0,n);}return out.toString("UTF-8");
        }
    }
    private volatile JSONObject psCapture,amCapture;
    private JSONObject diagnosticCaptures()throws Exception{return new JSONObject().put("PS",psCapture==null?JSONObject.NULL:psCapture).put("AM",amCapture==null?JSONObject.NULL:amCapture);}
    private NaturalProcessVisibility.Completed diagnostic(NaturalProcessVisibility.Diagnostic kind)throws Exception{
        check(folderOwned&&folder!=null,"owned diagnostic evidence directory");
        NativeRweProcessCapture backend=new NativeRweProcessCapture(getUiAutomation(),folder);
        try{return backend.capture(kind);}finally{if(kind==NaturalProcessVisibility.Diagnostic.PS)psCapture=backend.evidence();else amCapture=backend.evidence();}
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
            .put("psCommandNonce",psCommandNonce).put("amCommandNonce",amCommandNonce).put("commandExitStatus",0).put("uniqueLastCompletionMarkers",true).put("stderrEmpty",true).put("amSectionBoundary",amSectionBoundary).put("diagnosticCaptures",diagnosticCaptures());
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
        if(diagnosticRequested)diagnosticPublication(v);
        int writerPid=v.getJSONObject("plan").getInt("writerPid");processes().requireWriter(uid,writerPid);
        check(instrumentations(writerPid).writerPresent,"original authorized writer instrumentation not visible at custody");check(!stopping,"observer stopping; preserve custody without acknowledgement");
        writerLive=OwnedP9SystemUiEvidence.liveIdentity(this,folder,identityEvidence,NativeRweProcessCapture.IdentityRole.WRITER,writerPid,uid,v.getJSONObject("plan").getString("writerNonce"),v.getInt("bootCount"));
        check(!stopping,"observer stopping after live writer sample; no acknowledgement");
        save("custody",new JSONObject().put("lease",lease).put("attempt",attempt).put("publicationSha256",sha(raw.getBytes("UTF-8"))).put("creatorUid",uid).put("immutable",true)
            .put("writerPidObserved",writerPid).put("writerUidObserved",uid).put("writerLiveIdentity",writerLive).put("visibility",visibility())
            .put("tokenHandling","Original SDK PendingIntent parcelled over signature-protected fixed broadcast; held only, never sent by observer"));
        publicationSha256=sha(raw.getBytes("UTF-8"));published=v;original=pending;writerVisible=true;
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
        long end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){if(Build.VERSION.SDK_INT>=34)getUiAutomation().clearCache();AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null&&SYSTEM.contentEquals(root.getPackageName())){List<AccessibilityNodeInfo> matches=new ArrayList<>();for(AccessibilityNodeInfo node:nodes(root))if(ownRow(node))matches.add(node);check(matches.size()<=1,"ambiguous original row");if(matches.size()==1)return matches.get(0);}Thread.sleep(50);
        }throw new AssertionError("original UUID row unavailable; no fallback");
    }
    private JSONObject targetProof(String role)throws Exception{
        long end=SystemClock.uptimeMillis()+30000;while(SystemClock.uptimeMillis()<end){String value=proofShell(role).trim();if(value.startsWith("{")){JSONObject p=new JSONObject(value);check(lease.equals(p.getString("lease"))&&attempt.equals(p.getString("attempt")),"same original proof");return p;}Thread.sleep(100);}throw new AssertionError("original target proof unavailable; no re-open");
    }
    private void editOwnedDraftOnce()throws Exception{
        requireNormalAction("draft lookup/edit");
        String old="Owned cold original draft "+lease;long end=SystemClock.uptimeMillis()+10000;AccessibilityNodeInfo edit=null;
        while(SystemClock.uptimeMillis()<end){if(Build.VERSION.SDK_INT>=34)getUiAutomation().clearCache();AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null&&APP.contentEquals(root.getPackageName())){List<AccessibilityNodeInfo> matches=new ArrayList<>();for(AccessibilityNodeInfo n:nodes(root))if(n.isVisibleToUser()&&n.isEditable()&&old.contentEquals(n.getText()==null?"":n.getText()))matches.add(n);check(matches.size()<=1,"ambiguous owned draft");if(matches.size()==1){edit=matches.get(0);break;}}Thread.sleep(50);}
        check(edit!=null,"original owned editable draft unavailable");save("edit-intent",new JSONObject().put("lease",lease).put("attempt",attempt).put("action","one owned UI draft edit; never send"));
        requireNormalAction("ACTION_SET_TEXT");
        Bundle value=new Bundle();value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Owned natural OS new draft "+lease);check(edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value),"owned draft edit rejected; no retry");
    }
    @Override public void onStart(){JSONObject result=new JSONObject(),preClickEvidence=new JSONObject();Bundle completion=new Bundle();int oldFlags=0;boolean flagsSet=false;boolean clicked=false;
        try{
            result.put("preClickSystemUi",preClickEvidence.put("state","not-started").put("clickBlocked",true));
            check(Build.VERSION.SDK_INT>=34&&SELF.equals(getTargetContext().getPackageName())&&SELF.equals(getContext().getPackageName()),"API34+ self instrumentation with separate diagnostic stderr required");
            lease=uuid(args.getString("p9NaturalOsLease"));attempt=uuid(args.getString("p9NaturalOsAttempt"));configureMode();Context context=getTargetContext();PackageManager pm=context.getPackageManager();
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
            if(diagnosticRequested) {
                check(modeValidated&&!normalActionsAllowed&&Build.VERSION.SDK_INT==36,"hard read-only diagnostic branch");
                // The shade must already be visible. This lane performs NO global UI action.
                AccessibilityNodeInfo diagnosticRow=exactRow();
                try {
                    check(custodyError==null&&targetPids().isEmpty()&&noTargetInstrumentation()&&diagnosticRow.refresh()&&ownRow(diagnosticRow),"same original notification before diagnostic read");
                    JSONObject diagnostic=new JSONObject();result.put("diagnostic",diagnostic);
                    OwnedP9SystemUiEvidence.diagnoseRejectedOutsideOnly(this,folder,diagnosticRow,
                        OwnedP9SystemUiEvidence.preClickBinding(lease,attempt,publicationSha256,writerLive),diagnosticId,DIAGNOSTIC_SELECTOR,diagnostic);
                    check(custodyError==null&&targetPids().isEmpty()&&noTargetInstrumentation(),"no target launch during diagnostic read");
                    result.put("status","DIAGNOSTIC_ONLY").put("diagnosticOutcome",diagnostic.getString("outcome")).put("diagnostic",diagnostic)
                        .put("mode",DIAGNOSTIC_MODE).put("selector",DIAGNOSTIC_SELECTOR).put("diagnosticId",diagnosticId)
                        .put("productPassed",false).put("systemUiClicks",0).put("screenshotCaptures",0).put("draftEdits",0).put("appMessagesSent",0).put("shadeActionInvocations",0)
                        .put("originalTokenHeldNotSent",true).put("writerPreviouslyVisible",writerVisible).put("bootCount",published.getInt("bootCount")).put("visibility",visibility());
                } finally {diagnosticRow.recycle();}
            } else {
                requireNormalAction("open notification shade");
            check(getUiAutomation().performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS),"SystemUI shade unavailable");AccessibilityNodeInfo row=exactRow();
            long clickElapsed;OwnedP9SystemUiEvidence.PreClick selectedEvidence=null;
            try{
                requireNormalAction("pre-click screenshot/capture");
                selectedEvidence=OwnedP9SystemUiEvidence.captureBeforeClick(this,folder,row,OwnedP9SystemUiEvidence.preClickBinding(lease,attempt,publicationSha256,writerLive),preClickEvidence);
                check(custodyError==null&&targetPids().isEmpty()&&noTargetInstrumentation()&&row.refresh()&&ownRow(row),"last absent-process/original-row precondition");
                clickElapsed=SystemClock.elapsedRealtime();check(published.getInt("bootCount")==android.provider.Settings.Global.getInt(getTargetContext().getContentResolver(),android.provider.Settings.Global.BOOT_COUNT),"same boot before click");
                save("click-intent",new JSONObject().put("lease",lease).put("attempt",attempt).put("targetUidPidsBefore",new JSONArray()).put("targetInstrumentationAbsent",true).put("observerUid",android.os.Process.myUid()).put("bootCount",published.getInt("bootCount")).put("elapsed",clickElapsed).put("visibility",visibility()).put("writerPreviouslyVisible",writerVisible).put("preClickSystemUi",selectedEvidence.intentEvidence()).put("action","one exact original SystemUI ACTION_CLICK"));
                check(custodyError==null,"custody changed after immutable click intent; no click/replay");
                selectedEvidence.verifyBeforeClick(this,row,OwnedP9SystemUiEvidence.preClickBinding(lease,attempt,publicationSha256,writerLive));
                requireNormalAction("ACTION_CLICK");
                clicked=row.performAction(AccessibilityNodeInfo.ACTION_CLICK);check(clicked,"original SystemUI click rejected; no alternative");
            }finally{if(selectedEvidence!=null)selectedEvidence.close();row.recycle();}
            List<Integer> newPids=new ArrayList<>();end=SystemClock.uptimeMillis()+15000;while(SystemClock.uptimeMillis()<end){newPids=targetPids();if(!newPids.isEmpty())break;Thread.sleep(50);}check(newPids.size()==1&&newPids.get(0)!=published.getJSONObject("plan").getInt("writerPid")&&noTargetInstrumentation(),"one new OS target process required");
            // Only AFTER observing the new target PID do fixed run-as reads touch its private proof.
            JSONObject first=targetProof("first-open");check("first-open-complete".equals(first.getString("phase"))&&newPids.contains(first.getInt("pid"))&&first.getInt("activityEntries")==1&&first.getInt("defaultSelections")==0&&first.getInt("commands")==0&&first.getBoolean("defaultFamilyUnchanged"),"first original Activity/claim proof");
            check(first.getInt("bootCount")==published.getInt("bootCount")&&first.getLong("elapsed")>=clickElapsed&&!first.getString("nonce").equals(published.getJSONObject("plan").getString("writerNonce")),"new consumer identity after original click");
            check(first.getJSONObject("target").toString().equals(published.getJSONObject("target").toString())&&first.getBoolean("scopeMatched")&&first.getInt("locatorPages")==12&&"complete".equals(first.getString("durableClaimPhase"))&&("cold-original-"+lease).equals(first.getString("thread")),"original full target/thread/durable claim");
            check(first.getString("planHash").equals(published.getString("planHash"))&&first.getString("receiptHash").equals(published.getString("receiptHash"))&&first.getString("ticket").equals(published.getJSONObject("plan").getString("ticket"))&&first.getString("appHash").equals(published.getString("appHash"))&&first.getString("runnerHash").equals(published.getString("runnerHash")),"same original receipt/install");
            check(writerLive!=null,"original live writer identity required");
            JSONObject producer=targetProof("producer"),bindings=producer.getJSONObject("bindings");
            String writerNonce=published.getJSONObject("plan").getString("writerNonce"),consumerNonce=uuid(first.getString("nonce"));
            check(producer.getBoolean("passed")&&producer.getBoolean("sdkOriginalPublished")&&producer.getBoolean("permitArmed")&&producer.getBoolean("custodySentOnce")&&producer.getBoolean("writerVisibilityAcknowledged")
                &&bindings.getInt("uid")==uid&&bindings.getInt("writerPid")==published.getJSONObject("plan").getInt("writerPid")&&writerNonce.equals(bindings.getString("writerNonce"))
                &&bindings.getString("planHash").equals(published.getString("planHash"))&&bindings.getString("publicationSha256").equals(publicationSha256),"original completed producer bound to custody");
            OwnedP9SystemUiEvidence.matchProof(producer,writerLive,writerNonce);
            JSONObject firstLive=OwnedP9SystemUiEvidence.liveIdentity(this,folder,identityEvidence,NativeRweProcessCapture.IdentityRole.FIRST_CONSUMER,first.getInt("pid"),uid,consumerNonce,published.getInt("bootCount"));
            OwnedP9SystemUiEvidence.matchProof(first,firstLive,consumerNonce);
            check(first.getString("bootId").equals(writerLive.getString("bootId")),"same independent kernel boot writer to first consumer");
            requireNormalAction("first-main screenshot");
            JSONObject firstUi=OwnedP9SystemUiEvidence.capture(this,folder,lease,OwnedP9SystemUiEvidence.Scene.FIRST_MAIN);
            editOwnedDraftOnce();JSONObject newer=targetProof("new-draft");check("new-draft-observed".equals(newer.getString("phase"))&&newer.getInt("pid")==first.getInt("pid")&&newer.getString("ticket").equals(first.getString("ticket"))&&newer.getString("draftHash").equals(sha(("Owned natural OS new draft "+lease).getBytes("UTF-8")))&&newer.getInt("commands")==0,"new owned draft retained");
            check(custodyError==null&&targetPids().equals(newPids)&&noTargetInstrumentation(),"same independent consumer after edit");
            check(newer.getLong("startTicks")==first.getLong("startTicks")&&consumerNonce.equals(newer.getString("nonce"))&&newer.getString("bootId").equals(first.getString("bootId"))&&newer.getInt("bootCount")==first.getInt("bootCount"),"new draft must retain original expected consumer lifetime/nonce/boot");
            JSONObject laterLive=OwnedP9SystemUiEvidence.liveIdentity(this,folder,identityEvidence,NativeRweProcessCapture.IdentityRole.LATER_CONSUMER,first.getInt("pid"),uid,consumerNonce,published.getInt("bootCount"));
            OwnedP9SystemUiEvidence.matchProof(newer,laterLive,consumerNonce);OwnedP9SystemUiEvidence.matchProof(first,laterLive,consumerNonce);
            requireNormalAction("later-draft screenshot");
            JSONObject laterUi=OwnedP9SystemUiEvidence.capture(this,folder,lease,OwnedP9SystemUiEvidence.Scene.LATER_DRAFT);
            result.put("processIdentityComplete",true).put("producerProofSha256",sha(producer.toString().getBytes("UTF-8"))).put("firstMainUi",firstUi).put("laterDraftUi",laterUi).put("newTargetStartTicks",first.getLong("startTicks"));
            result.put("status","passed").put("systemUiClicks",1).put("targetUidAbsentBefore",true).put("targetInstrumentationAbsent",true).put("newTargetPid",first.getInt("pid")).put("newTargetNonce",first.getString("nonce")).put("bootCount",first.getInt("bootCount")).put("clickElapsed",clickElapsed).put("defaultSelections",0).put("commands",0).put("originalTokenHeldNotSent",true).put("firstProofSha256",sha(first.toString().getBytes("UTF-8"))).put("newDraftProofSha256",sha(newer.toString().getBytes("UTF-8"))).put("writerPreviouslyVisible",writerVisible).put("visibility",visibility());
            } // diagnostic-only and normal acceptance lanes cannot fall through into each other
        }catch(Throwable error){try{result.put("status",diagnosticRequested?"DIAGNOSTIC_ONLY":"failed").put("failureType",error.getClass().getName()).put("failureCode",error instanceof AssertionError || error instanceof NaturalProcessVisibility.Refused?String.valueOf(error.getMessage()):"external API failure").put("systemUiClickAccepted",clicked).put("diagnosticCaptures",diagnosticCaptures());
            if(diagnosticRequested)result.put("diagnosticOutcome","REFUSED").put("productPassed",false).put("systemUiClicks",0).put("screenshotCaptures",0).put("draftEdits",0).put("appMessagesSent",0).put("shadeActionInvocations",0);
        }catch(Exception ignored){}}
        finally{
            stopping=true;
            // Release each observer-owned resource independently, then attempt the original result write.
            // A rejected existing attempt never acquires ownership of that old evidence directory.
            try{if(registered)getTargetContext().unregisterReceiver(receiver);}catch(Exception error){cleanupFailure(result,"receiver",error);}
            try{if(custodyExecutor!=null)custodyExecutor.shutdownNow();}catch(Exception error){cleanupFailure(result,"custodyExecutor",error);}
            try{if(flagsSet){AccessibilityServiceInfo s=getUiAutomation().getServiceInfo();s.flags=oldFlags;getUiAutomation().setServiceInfo(s);}}catch(Exception error){cleanupFailure(result,"uiAutomation",error);}
            try{if(folderOwned)save("result",result.put("lease",lease).put("attempt",attempt).put("observerPid",android.os.Process.myPid()).put("observerUid",android.os.Process.myUid()).put("processIdentityEvidence",identitySnapshot()).put("vendorHostModel",false));}catch(Exception error){cleanupFailure(result,"result",error);}
        }
        completion.putString("p9NaturalResult",result.toString());finish("passed".equals(result.optString("status"))?-1:0,completion);
    }
    private static void cleanupFailure(JSONObject result,String stage,Exception error){
        try{if("DIAGNOSTIC_ONLY".equals(result.optString("status")))result.put("diagnosticOutcome","REFUSED");else result.put("status","failed");result.put("cleanupFailure-"+stage,error.getClass().getName());}catch(JSONException ignored){}
    }
}
