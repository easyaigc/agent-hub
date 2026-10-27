package com.agenthub.android;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Runs in the independent source UID. Never instruments or force-stops the receiver. */
public final class OwnedDocumentColdDriver extends Instrumentation {
    private static final String APP="com.easyai.agenthub.android.acceptance.documents";
    private Bundle args;
    private String nonce,phase;
    private File file(String suffix) { return new File(getTargetContext().getFilesDir(),"document-cold-"+nonce+"-"+suffix); }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);args=arguments;start(); }
    private String shell(String command) throws Exception {
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8).trim();
        }
    }
    private AccessibilityNodeInfo currentRoot() {
        if(android.os.Build.VERSION.SDK_INT>=33)getUiAutomation().clearCache();
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null)root.refresh();return root;
    }
    private void save(String suffix,String value) throws Exception { Files.write(file(suffix).toPath(),value.getBytes(StandardCharsets.UTF_8)); }
    private void tree(AccessibilityNodeInfo node,StringBuilder out,int depth) {
        if(node==null)return;for(int n=0;n<depth;n++)out.append(' ');out.append(node.getPackageName()).append(" | ").append(node.getViewIdResourceName()).append(" | ").append(node.getText()).append(" | ").append(node.getContentDescription()).append(" | ").append(node.isClickable()).append('\n');
        for(int n=0;n<node.getChildCount();n++)tree(node.getChild(n),out,depth+1);
    }
    private void capture(String suffix) throws Exception {
        StringBuilder out=new StringBuilder();tree(currentRoot(),out,0);save(suffix+".txt",out.toString());
        Bitmap image=getUiAutomation().takeScreenshot();if(image!=null) { try(FileOutputStream stream=new FileOutputStream(file(suffix+".png"))) { image.compress(Bitmap.CompressFormat.PNG,100,stream); }finally { image.recycle(); } }
    }
    private void matches(AccessibilityNodeInfo node,String value,List<AccessibilityNodeInfo> result) {
        if(node==null)return;if(value.contentEquals(node.getText()==null?"":node.getText())||value.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))result.add(node);
        for(int n=0;n<node.getChildCount();n++)matches(node.getChild(n),value,result);
    }
    private AccessibilityNodeInfo await(String owner,String value,long timeout) throws Exception {
        long end=SystemClock.uptimeMillis()+timeout;
        while(SystemClock.uptimeMillis()<end) {
            AccessibilityNodeInfo root=currentRoot();List<AccessibilityNodeInfo> hits=new ArrayList<>();
            if(root!=null&&owner.contentEquals(root.getPackageName()))matches(root,value,hits);
            hits.removeIf(node->!node.isVisibleToUser()||(value.equals("AH_DOC_"+nonce)&&!withinRoots(node)));
            if(hits.size()==1&&hits.get(0).isVisibleToUser()) { AccessibilityNodeInfo action=hits.get(0);while(action!=null&&!action.isClickable())action=action.getParent();if(action==null||action.isEnabled())return hits.get(0); }
            if(hits.size()>1)throw new AssertionError("Ambiguous native target: "+value);Thread.sleep(100);
        }
        capture(phase+"-missing");throw new AssertionError("Exact native target absent: "+owner+" / "+value);
    }
    private boolean withinRoots(AccessibilityNodeInfo node) {
        for(AccessibilityNodeInfo parent=node;parent!=null;parent=parent.getParent())if(String.valueOf(parent.getViewIdResourceName()).endsWith("/roots_list"))return true;
        return false;
    }
    private void click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo leaf=node;while(node!=null&&!node.isClickable())node=node.getParent();
        if(node==null&&leaf!=null&&leaf.isVisibleToUser()&&(("document-cold-"+nonce+".txt").contentEquals(leaf.getText())&&"com.android.documentsui".contentEquals(leaf.getPackageName())||("AH_DOC_"+nonce).contentEquals(leaf.getText())&&withinRoots(leaf)&&Arrays.asList("com.android.documentsui","com.google.android.documentsui").contains(String.valueOf(leaf.getPackageName())))) {
            // API26 DocumentsUI grid handles touch on RecyclerView without an AX click action.
            android.graphics.Rect bounds=new android.graphics.Rect();leaf.getBoundsInScreen(bounds);if(bounds.isEmpty())throw new AssertionError("Owned document has no native bounds");
            try { save(phase+"-owned-document-touch.json",new JSONObject().put("exactFile",leaf.getText()).put("package",leaf.getPackageName()).put("bounds",bounds.toShortString()).toString()); }catch(Exception error) { throw new AssertionError(error); }
            long time=SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,bounds.exactCenterX(),bounds.exactCenterY(),0),up=android.view.MotionEvent.obtain(time,time+80,android.view.MotionEvent.ACTION_UP,bounds.exactCenterX(),bounds.exactCenterY(),0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try { if(!getUiAutomation().injectInputEvent(down,true)||!getUiAutomation().injectInputEvent(up,true))throw new AssertionError("Owned document native touch rejected"); }finally { down.recycle();up.recycle(); }return;
        }
        if(node==null||!node.isEnabled()||!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Native semantic click rejected");
    }
    private void launch() { getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(APP,"com.agenthub.android.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    private boolean savedAndStopped(String record) { return record.contains("state=STOPPED")&&(record.contains("mHaveState=true")||record.contains("haveState=true")); }
    private AccessibilityNodeInfo draftScroller(AccessibilityNodeInfo node) {
        if(node==null)return null;
        if(("existing-"+nonce+".txt").contentEquals(node.getText()==null?"":node.getText())) {
            for(AccessibilityNodeInfo parent=node.getParent();parent!=null;parent=parent.getParent())if(parent.isScrollable())return parent;
        }
        for(int n=0;n<node.getChildCount();n++) { AccessibilityNodeInfo found=draftScroller(node.getChild(n));if(found!=null)return found; }return null;
    }
    private AccessibilityNodeInfo awaitOwnedFileReady(String owner) throws Exception {
        long end=SystemClock.uptimeMillis()+15000;String previous=null;
        while(SystemClock.uptimeMillis()<end) {
            AccessibilityNodeInfo root=currentRoot();List<AccessibilityNodeInfo> all=new ArrayList<>();
            if(root!=null&&owner.contentEquals(root.getPackageName())) { collect(root,all);boolean drawer=false;List<AccessibilityNodeInfo> files=new ArrayList<>();
                for(AccessibilityNodeInfo node:all)if(node.isVisibleToUser()) { if(String.valueOf(node.getViewIdResourceName()).endsWith("/roots_list"))drawer=true;if(("document-cold-"+nonce+".txt").contentEquals(node.getText()==null?"":node.getText()))files.add(node); }
                if(!drawer&&files.size()==1) { AccessibilityNodeInfo file=files.get(0);for(AccessibilityNodeInfo node=file;node!=null;node=node.getParent())if(node.isChecked()||node.isSelected())throw new AssertionError("Original document is already selected; do not select again");Rect bounds=new Rect();file.getBoundsInScreen(bounds);if(!bounds.isEmpty()&&bounds.flattenToString().equals(previous))return file;previous=bounds.flattenToString(); }else previous=null;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Owned file viewport has not settled outside the drawer");
    }
    private void collect(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> result) { result.add(node);for(int n=0;n<node.getChildCount();n++)if(node.getChild(n)!=null)collect(node.getChild(n),result); }
    private void awaitImportedFile() throws Exception {
        // Existing first attachment can occupy the viewport. Scroll only the native draft strip.
        await(APP,"original-"+nonce+" 100% %@",30000);
        for(int n=0;n<3;n++) {
            AccessibilityNodeInfo root=currentRoot();List<AccessibilityNodeInfo> hits=new ArrayList<>();matches(root,"document-cold-"+nonce+".txt",hits);if(hits.size()==1)return;
            AccessibilityNodeInfo scroll=draftScroller(root);if(scroll!=null)scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(300);
        }
        await(APP,"document-cold-"+nonce+".txt",30000);
    }
    @Override public void onStart() {
        nonce=args.getString("nonce");phase=args.getString("phase");JSONObject result=new JSONObject();Bundle completion=new Bundle();
        try {
            if(!getTargetContext().getPackageName().equals(APP+".test")||nonce==null||!nonce.matches("[a-f0-9]{32}")||!Arrays.asList("open","resume-open","kill","terminate","terminate-open","return","select","select-root","select-visible","resume-visible","reopen").contains(phase))throw new AssertionError("Document examiner scope mismatch");
            int uid=getTargetContext().getPackageManager().getApplicationInfo(APP,0).uid;
            if(uid==android.os.Process.myUid())throw new AssertionError("Receiver and source must have independent UIDs");
            result.put("phase",phase).put("receiverUid",uid).put("sourceUid",android.os.Process.myUid()).put("sourcePid",android.os.Process.myPid());
            if(phase.equals("open")||phase.equals("kill")||phase.equals("terminate")||phase.equals("terminate-open")||phase.equals("resume-open")) {
                if(phase.equals("open")&&file("open.json").exists())throw new AssertionError("Original launch receipt already exists; reconcile it without replay");
                if(phase.equals("open")&&!shell("pidof "+APP).isEmpty())throw new AssertionError("Receiver must be stopped after seeding");
                JSONObject previous=null;
                if(phase.equals("terminate")||phase.equals("terminate-open")) {
                    previous=new JSONObject(new String(Files.readAllBytes(file(phase.equals("terminate-open")?"open.json":"kill.json").toPath()),StandardCharsets.UTF_8));
                    if(!"failed".equals(previous.getString("status"))||!previous.getString("error").equals("java.lang.AssertionError: Background receiver did not die")||file("terminate-intent.json").exists())throw new AssertionError("Only the original safe-kill no-op can continue");
                    String current=shell("dumpsys activity activities");if(!current.contains(previous.getString("receiverActivity"))||!current.contains(previous.getString("chooserActivity"))||!shell("pidof "+APP).equals(Integer.toString(previous.getInt("originalPid"))))throw new AssertionError("Original pre-kill process/activity changed");
                }
                if(phase.equals("resume-open")) {
                    previous=new JSONObject(new String(Files.readAllBytes(file("open.json").toPath()),StandardCharsets.UTF_8));
                    if(!"failed".equals(previous.getString("status"))||!previous.getString("error").equals("java.lang.AssertionError: Native semantic click rejected")||file("kill-intent.json").exists())throw new AssertionError("Only the original pre-picker readiness failure can continue");
                    AccessibilityNodeInfo root=currentRoot();if(root==null||!APP.contentEquals(root.getPackageName()))throw new AssertionError("Original receiver window required");
                    save("open-failure-original.json",previous.toString(2));
                }
                if(phase.equals("kill")) {
                    String failedPhase=file("resume-open.json").isFile()?"resume-open":"open";
                    previous=new JSONObject(new String(Files.readAllBytes(file(failedPhase+".json").toPath()),StandardCharsets.UTF_8));
                    if(!"failed".equals(previous.getString("status"))||!previous.getString("error").equals("java.lang.AssertionError: Original receiver must be stopped under exact native chooser")||file("kill-intent.json").exists())throw new AssertionError("Only the original pre-kill stopped-state failure can continue");
                    String original=new String(Files.readAllBytes(file(failedPhase+"-failed-activities.txt").toPath()),StandardCharsets.UTF_8);
                    String current=shell("dumpsys activity activities");
                    for(String record:original.split("(?=\\* Hist\\s+#\\d+:)")) { String header=record.split("\n",2)[0];if(header.contains(APP+"/com.agenthub.android.MainActivity")||header.contains(previous.getString("chooserPackage")+"/"))if(!current.contains(header))throw new AssertionError("Original pending ActivityRecord changed"); }
                    save("open-failure-original.json",previous.toString(2));
                }
                JSONObject config=new JSONObject().put("rootTitle","AH_DOC_"+nonce).put("cases",new JSONArray().put(new JSONObject().put("fileName","document-cold-"+nonce+".txt").put("fileText","ANDROID_DOCUMENT_PROCESS_COLD_"+nonce+"\n原文件 100% %@\n")));
                if(phase.equals("open"))Files.write(new File(getTargetContext().getFilesDir(),"owned-documents.json").toPath(),config.toString().getBytes(StandardCharsets.UTF_8));
                getTargetContext().getContentResolver().notifyChange(DocumentsContract.buildRootsUri(APP+".test.owned-documents"),null);
                ResolveInfo handler=getTargetContext().getPackageManager().resolveActivity(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),0);
                if(handler==null||(handler.activityInfo.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM)==0||handler.activityInfo.applicationInfo.uid==uid)throw new AssertionError("Native system chooser not resolved");
                String chooser=handler.activityInfo.packageName;result.put("chooserPackage",chooser).put("chooserUid",handler.activityInfo.applicationInfo.uid);
                if(phase.equals("open")||phase.equals("resume-open")) { if(phase.equals("open"))launch();click(await(APP,"Add attachment",30000));click(await(APP,"Choose file",15000)); }
                long end=SystemClock.uptimeMillis()+15000;String activities="";
                while(SystemClock.uptimeMillis()<end) {
                    AccessibilityNodeInfo root=currentRoot();activities=shell("dumpsys activity activities");
                    boolean saved=false;for(String record:activities.split("(?=\\* Hist\\s+#\\d+:)"))if(record.split("\n",2)[0].contains(APP+"/com.agenthub.android.MainActivity")&&savedAndStopped(record))saved=true;
                    if(root!=null&&chooser.contentEquals(root.getPackageName())&&activities.contains("launchedFromUid="+uid+" launchedFromPackage="+APP)&&saved)break;Thread.sleep(100);
                }
                if(!activities.contains("launchedFromUid="+uid+" launchedFromPackage="+APP))throw new AssertionError("Chooser exact original caller absent");
                String[] records=activities.split("(?=\\* Hist\\s+#\\d+:)");String receiverRecord=null,chooserRecord=null;
                for(String record:records) { String header=record.split("\n",2)[0];if(header.contains(APP+"/com.agenthub.android.MainActivity"))receiverRecord=record;if(header.contains(chooser+"/"))chooserRecord=record; }
                if(receiverRecord==null||!savedAndStopped(receiverRecord)||chooserRecord==null)throw new AssertionError("Original receiver must be stopped under exact native chooser");
                String pid=shell("pidof "+APP);if(!pid.matches("[0-9]+"))throw new AssertionError("Original receiver PID absent");
                save("before-kill-activities.txt",activities);capture("before-kill");result.put("originalPid",Integer.parseInt(pid)).put("receiverActivity",receiverRecord.split("\n",2)[0]).put("chooserActivity",chooserRecord.split("\n",2)[0]);
                // Preserve the irreversible intent before killing this exact background recipient.
                result.put("status","kill-intent");save(phase.startsWith("terminate")?"terminate-intent.json":"kill-intent.json",result.toString(2));
                if(phase.startsWith("terminate")) {
                    String ownUid=shell("run-as "+APP+" id -u");if(!ownUid.equals(Integer.toString(uid)))throw new AssertionError("Scoped debug UID mismatch");
                    shell("run-as "+APP+" kill -9 "+pid);result.put("termination","own-debug-uid-sigkill");
                } else { shell("am kill "+APP);result.put("termination","activity-manager-background-kill"); }
                end=SystemClock.uptimeMillis()+15000;while(!shell("pidof "+APP).isEmpty()&&SystemClock.uptimeMillis()<end)Thread.sleep(100);
                if(!shell("pidof "+APP).isEmpty())throw new AssertionError("Background receiver did not die");
                AccessibilityNodeInfo root=currentRoot();if(root==null||!chooser.contentEquals(root.getPackageName()))throw new AssertionError("Chooser did not remain foreground");
                String after=shell("dumpsys activity activities");if(!after.contains(result.getString("chooserActivity")))throw new AssertionError("Original chooser ActivityRecord changed");
                save("after-kill-activities.txt",after);capture("after-kill");result.put("backgroundReceiverKilled",true).put("originalChooserRetained",true).put("pickerLaunches",phase.equals("kill")||phase.startsWith("terminate")?0:1);
            } else if(phase.equals("return")||phase.equals("select")||phase.equals("select-root")||phase.equals("select-visible")||phase.equals("resume-visible")) {
                File killed=file("terminate-open.json").isFile()?file("terminate-open.json"):file("terminate.json").isFile()?file("terminate.json"):file("kill.json").isFile()?file("kill.json"):file("resume-open.json").isFile()?file("resume-open.json"):file("open.json");JSONObject original=new JSONObject(new String(Files.readAllBytes(killed.toPath()),StandardCharsets.UTF_8));if(!original.getBoolean("backgroundReceiverKilled")||!shell("pidof "+APP).isEmpty())throw new AssertionError("Original killed receiver receipt required");
                String chooser=original.getString("chooserPackage");String activities=shell("dumpsys activity activities");if(!activities.contains(original.getString("chooserActivity")))throw new AssertionError("Original chooser is no longer present");
                AccessibilityNodeInfo root=currentRoot();if(root==null||!chooser.contentEquals(root.getPackageName()))throw new AssertionError("Original native chooser must be foreground");
                if(phase.equals("select")) {
                    JSONObject failed=new JSONObject(new String(Files.readAllBytes(file("return.json").toPath()),StandardCharsets.UTF_8));
                    if(!failed.getString("error").equals("java.lang.AssertionError: Native semantic click rejected")||!"com.android.documentsui".equals(chooser))throw new AssertionError("Only original API26 unselected grid result may continue");
                }
                if(phase.equals("select-root")) {
                    JSONObject failed=new JSONObject(new String(Files.readAllBytes(file("return.json").toPath()),StandardCharsets.UTF_8));
                    if(!failed.getString("error").equals("java.lang.AssertionError: Ambiguous native target: AH_DOC_"+nonce)||file("selection-intent.json").exists())throw new AssertionError("Only original unselected drawer alias failure may continue");
                }
                boolean visibleContinuation=phase.equals("select-visible")||phase.equals("resume-visible");
                if(phase.equals("resume-visible")) {
                    JSONObject failed=new JSONObject(new String(Files.readAllBytes(file("select-visible.json").toPath()),StandardCharsets.UTF_8));
                    if(!failed.getString("error").startsWith("java.lang.NullPointerException:")||file("visible-select-intent.json").exists())throw new AssertionError("Only original read-only viewport failure before any new touch may continue");
                }
                if(visibleContinuation) {
                    JSONObject failed=new JSONObject(new String(Files.readAllBytes(file("return.json").toPath()),StandardCharsets.UTF_8));
                    if(!"com.android.documentsui".equals(chooser)||!failed.getString("error").equals("java.lang.AssertionError: Exact native target absent: "+APP+" / original-"+nonce+" 100% %@")||!file("selection-intent.json").exists()||!file("return-owned-document-touch.json").exists()||file("visible-select-intent.json").exists())throw new AssertionError("Only original drawer-transition missed touch may be reconciled");
                    android.net.Uri uri=DocumentsContract.buildDocumentUri(APP+".test.owned-documents","document-cold-"+nonce+".txt");
                    if(getTargetContext().checkUriPermission(uri,-1,uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_DENIED)throw new AssertionError("Original receiver already has the result grant; do not select again");
                    result.put("originalChooserStillActiveAndReceiverAbsent",true).put("originalResultReadGrantAbsent",true);
                }
                capture(phase+"-before");if(phase.equals("return")) { click(await(chooser,"Show roots",10000));click(await(chooser,"AH_DOC_"+nonce,15000)); }
                if(phase.equals("select-root"))click(await(chooser,"AH_DOC_"+nonce,15000));
                AccessibilityNodeInfo file=awaitOwnedFileReady(chooser);capture("owned-root");if(!visibleContinuation&&file("selection-intent.json").exists())throw new AssertionError("Original selection intent exists; reconcile without selecting again");save(visibleContinuation?"visible-select-intent.json":"selection-intent.json",new JSONObject().put("sourceFile","document-cold-"+nonce+".txt").put("action","select-one-verified-unselected-owned-document-outside-drawer").toString());click(file);
                // OPEN_DOCUMENT with multiple selection may return immediately or expose the native Open action.
                long end=SystemClock.uptimeMillis()+10000;boolean returned=false;
                while(SystemClock.uptimeMillis()<end) {
                    root=currentRoot();if(root!=null&&APP.contentEquals(root.getPackageName())) { returned=true;break; }
                    if(root!=null&&chooser.contentEquals(root.getPackageName())) { List<AccessibilityNodeInfo> hits=new ArrayList<>();matches(root,"Open",hits);if(hits.size()==1) { click(hits.get(0));break; } }
                    Thread.sleep(100);
                }
                awaitImportedFile();String pid=shell("pidof "+APP);if(!pid.matches("[0-9]+")||Integer.parseInt(pid)==original.getInt("originalPid"))throw new AssertionError("Original receiver not recreated in a different process");
                capture("returned");save("returned-activities.txt",shell("dumpsys activity activities"));result.put("originalPid",original.getInt("originalPid")).put("returnedPid",Integer.parseInt(pid)).put("originalChooserResultReturned",true).put("pickerLaunches",0);
            } else {
                if(!shell("pidof "+APP).isEmpty())throw new AssertionError("Receiver must be stopped before cold MAIN reopen");launch();awaitImportedFile();capture("reopened");result.put("pickerLaunches",0).put("receiverPid",Integer.parseInt(shell("pidof "+APP)));
            }
            result.put("status","passed").put("modelInputs",0).put("sendInvoked",false);
        } catch(Throwable error) { try { result.put("status","failed").put("error",error.toString());capture(phase+"-failed");save(phase+"-failed-activities.txt",shell("dumpsys activity activities")); }catch(Exception ignored) { } }
        try { save(phase+".json",result.toString(2));completion.putString("stream",result.toString());finish("passed".equals(result.optString("status"))?-1:0,completion); }
        catch(Exception failure) { completion.putString("stream",failure.toString());finish(0,completion); }
    }
}
