package com.agenthub.android;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Independent SDK observer: one isolated original camera, exact output, no shell or target arguments. */
public final class OwnedCameraColdDriver extends Instrumentation {
    private static final String APP="com.easyai.agenthub.android.acceptance.cameras";
    private Bundle args;private String nonce,phase;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);args=arguments;start(); }
    private File file(String suffix) { return new File(getTargetContext().getFilesDir(),"camera-cold-"+nonce+"-"+suffix); }
    private byte[] bytes(String command) throws Exception {
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();
        }
    }
    private String shell(String command) throws Exception { return new String(bytes(command),StandardCharsets.UTF_8).trim(); }
    private JSONObject read(String suffix) throws Exception { return new JSONObject(new String(Files.readAllBytes(file(suffix).toPath()),StandardCharsets.UTF_8)); }
    private JSONObject opened() throws Exception { return read(file("resume-open.json").exists()?"resume-open.json":"open.json"); }
    private void save(String suffix,String value) throws Exception { Files.write(file(suffix).toPath(),value.getBytes(StandardCharsets.UTF_8)); }
    private void intent(String suffix,JSONObject value) throws Exception { if(file(suffix).exists())throw new AssertionError("Original intent exists; inspect its receipt without replay");save(suffix,value.toString(2)); }
    private String record(String activities,String component) { for(String block:activities.split("(?=\\* Hist\\s+#\\d+:)"))if(block.split("\n",2)[0].contains(component))return block;return null; }
    private List<AccessibilityNodeInfo> nodes(String owner) {
        if(android.os.Build.VERSION.SDK_INT>=33)getUiAutomation().clearCache();
        List<AccessibilityNodeInfo> all=new ArrayList<>();AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null&&owner.contentEquals(root.getPackageName())) { root.refresh();visit(root,all); }return all;
    }
    private void visit(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> all) { if(node==null)return;all.add(node);for(int n=0;n<node.getChildCount();n++)visit(node.getChild(n),all); }
    private void capture(String name) throws Exception {
        List<AccessibilityNodeInfo> all=new ArrayList<>();visit(getUiAutomation().getRootInActiveWindow(),all);StringBuilder text=new StringBuilder();
        for(AccessibilityNodeInfo n:all) { Rect box=new Rect();n.getBoundsInScreen(box);text.append(n.getPackageName()).append('|').append(n.getViewIdResourceName()).append('|').append(n.getText()).append('|').append(n.getContentDescription()).append('|').append(n.isEnabled()).append('|').append(box.toShortString()).append('\n'); }
        save(name+".txt",text.toString());Bitmap image=getUiAutomation().takeScreenshot();if(image!=null)try(FileOutputStream out=new FileOutputStream(file(name+".png"))) { image.compress(Bitmap.CompressFormat.PNG,100,out); }finally { image.recycle(); }
    }
    private void click(AccessibilityNodeInfo node) { while(node!=null&&!node.isClickable())node=node.getParent();if(node==null||!node.isVisibleToUser()||!node.isEnabled()||!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Exact native click rejected"); }
    private AccessibilityNodeInfo await(String owner,String label,long timeout) throws Exception {
        long end=SystemClock.uptimeMillis()+timeout;
        while(SystemClock.uptimeMillis()<end) {
            List<AccessibilityNodeInfo> hits=new ArrayList<>();Set<AccessibilityNodeInfo> actions=new HashSet<>();
            for(AccessibilityNodeInfo node:nodes(owner))if(node.isVisibleToUser()&&(label.contentEquals(node.getText()==null?"":node.getText())||label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))) {
                AccessibilityNodeInfo action=node;while(action!=null&&!action.isClickable())action=action.getParent();if(action!=null&&action.isEnabled()&&actions.add(action))hits.add(action);
            }
            if(hits.size()==1)return hits.get(0);if(hits.size()>1)throw new AssertionError("Ambiguous original control: "+label);Thread.sleep(100);
        }
        throw new AssertionError("Exact original control unavailable: "+label);
    }
    private void launch() { getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(APP,"com.agenthub.android.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    private void permission(String caller,int uid,int number) throws Exception {
        String owner=getUiAutomation().getRootInActiveWindow().getPackageName().toString();
        if(!Arrays.asList("com.android.permissioncontroller","com.google.android.permissioncontroller","com.android.packageinstaller","com.google.android.packageinstaller").contains(owner))throw new AssertionError("Unknown permission owner");
        String activities=shell("dumpsys activity activities"),entry=record(activities,owner+"/");save("permission-"+number+"-activities.txt",activities);
        if(entry==null||!entry.contains("launchedFromUid="+uid+" launchedFromPackage="+caller))throw new AssertionError("Exact permission caller UID/package unconfirmed");
        List<AccessibilityNodeInfo> hits=new ArrayList<>();String id=android.os.Build.VERSION.SDK_INT>=30?"permission_allow_one_time_button":"permission_allow_button";
        for(AccessibilityNodeInfo node:nodes(owner))if(node.isVisibleToUser()&&node.isEnabled()&&String.valueOf(node.getViewIdResourceName()).endsWith("/"+id))hits.add(node);
        if(hits.size()!=1)throw new AssertionError("Exact scoped permission decision unavailable");intent("permission-"+number+"-intent.json",new JSONObject().put("owner",owner).put("caller",caller).put("uid",uid).put("decision",id));click(hits.get(0));
    }
    private void awaitImported(String request) throws Exception {
        long end=SystemClock.uptimeMillis()+30000;
        while(SystemClock.uptimeMillis()<end) {
            for(AccessibilityNodeInfo n:nodes(APP))if(n.isVisibleToUser()&&("photo-"+request+".jpg").contentEquals(n.getText()==null?"":n.getText()))return;
            for(AccessibilityNodeInfo n:nodes(APP))if(("existing-"+nonce+".txt").contentEquals(n.getText()==null?"":n.getText())) {
                for(AccessibilityNodeInfo p=n.getParent();p!=null;p=p.getParent())if(p.isScrollable()&&p.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))break;
            }Thread.sleep(150);
        }
        throw new AssertionError("Exact original camera result not visible in draft");
    }
    @Override public void onStart() {
        nonce=args.getString("nonce");phase=args.getString("phase");JSONObject out=new JSONObject();Bundle completion=new Bundle();
        try {
            if(!getTargetContext().getPackageName().equals(APP+".test")||nonce==null||!nonce.matches("[a-f0-9]{32}")||!Arrays.asList("open","resume-open","kill","capture","reconcile","reopen","resume-reopen").contains(phase)||!(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish")))throw new AssertionError("Owned camera SDK scope mismatch");
            if(file(phase+".json").exists())throw new AssertionError("Original phase receipt exists; do not replay");
            int uid=getTargetContext().getPackageManager().getApplicationInfo(APP,0).uid;if(uid==android.os.Process.myUid())throw new AssertionError("Independent observer UID required");
            out.put("phase",phase).put("receiverUid",uid).put("observerUid",android.os.Process.myUid()).put("observerPid",android.os.Process.myPid()).put("cameraLaunches",0);
            if(phase.equals("open")||phase.equals("resume-open")) {
                boolean resume=phase.equals("resume-open");
                if(!resume&&!shell("pidof "+APP).isEmpty())throw new AssertionError("Fresh uninstrumented recipient required");
                ComponentName handler=new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).resolveActivity(getTargetContext().getPackageManager());if(handler==null)throw new AssertionError("System camera unavailable");
                String camera=handler.getPackageName();ApplicationInfo info=getTargetContext().getPackageManager().getApplicationInfo(camera,0);if((info.flags&ApplicationInfo.FLAG_SYSTEM)==0||info.uid==uid)throw new AssertionError("Only the independent resolved system camera");
                if(!resume) { intent("open-intent.json",new JSONObject().put("action","one-original-camera").put("nonce",nonce));launch();await(APP,"camera-original-"+nonce+" 100% %@",30000); }
                String pid=shell("pidof "+APP);if(!pid.matches("[0-9]+"))throw new AssertionError("Original recipient PID unavailable");
                if(resume) {
                    JSONObject failed=read("open.json");String prior=new String(Files.readAllBytes(file("before-kill-activities.txt").toPath()),StandardCharsets.UTF_8),old=record(prior,APP+"/com.agenthub.android.MainActivity");
                    if(!failed.getString("error").equals("java.lang.AssertionError: Original camera caller and stopped saved recipient required")||old==null||!old.contains(" "+pid+":"))throw new AssertionError("Only original pre-permission open may continue");
                } else { click(await(APP,"Add attachment",30000));click(await(APP,"Take photo",15000)); }
                long end=SystemClock.uptimeMillis()+30000;String state="",receiver=null,activity=null;int permissions=0;
                while(SystemClock.uptimeMillis()<end) {
                    AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();state=shell("dumpsys activity activities");
                    if(root!=null) {
                        String owner=root.getPackageName().toString();
                        if(owner.contains("permissioncontroller")||owner.equals("com.android.packageinstaller")||owner.equals("com.google.android.packageinstaller")) {
                            String entry=record(state,owner+"/");if(entry!=null&&entry.contains("launchedFromUid="+uid+" launchedFromPackage="+APP))permission(APP,uid,++permissions);
                            else permission(camera,info.uid,++permissions);
                        }
                        receiver=record(state,APP+"/com.agenthub.android.MainActivity");activity=record(state,camera+"/");
                        if(owner.equals(camera)&&receiver!=null&&activity!=null&&receiver.contains("state=STOPPED")&&(receiver.contains("mHaveState=true")||receiver.contains("haveState=true")))break;
                    }Thread.sleep(100);
                }
                save("before-kill-activities.txt",state);capture("before-kill");
                if(receiver==null||activity==null||!receiver.contains("state=STOPPED")||!(receiver.contains("mHaveState=true")||receiver.contains("haveState=true"))||!activity.contains("launchedFromUid="+uid+" launchedFromPackage="+APP))throw new AssertionError("Original camera caller and stopped saved recipient required");
                String path=shell("run-as "+APP+" find files/camera-output-v1 -type f");if(!path.matches("files/camera-output-v1/[a-f0-9]{32}/photo-[a-f0-9-]{36}\\.jpg"))throw new AssertionError("Only one exact private output may exist");
                String request=path.substring(path.lastIndexOf("photo-")+6,path.length()-4);if(!java.util.UUID.fromString(request).toString().equals(request))throw new AssertionError("Canonical original request required");
                out.put("originalPid",Integer.parseInt(pid)).put("receiverActivity",receiver.split("\n",2)[0]).put("cameraActivity",activity.split("\n",2)[0]).put("cameraPackage",camera).put("cameraUid",info.uid).put("requestId",request).put("outputPath",path).put("cameraLaunches",1).put("savedStoppedReceiver",true).put("permissionDecisions",permissions);
            } else if(phase.equals("kill")) {
                JSONObject original=opened();if(!original.getString("status").equals("passed"))throw new AssertionError("Confirmed original open required");String pid=shell("pidof "+APP),state=shell("dumpsys activity activities"),receiver=record(state,APP+"/com.agenthub.android.MainActivity");
                if(!pid.equals(Integer.toString(original.getInt("originalPid")))||receiver==null||!receiver.contains(original.getString("receiverActivity"))||!receiver.contains("state=STOPPED")||!(receiver.contains("mHaveState=true")||receiver.contains("haveState=true"))||!state.contains(original.getString("cameraActivity"))||nodes(original.getString("cameraPackage")).isEmpty())throw new AssertionError("Original saved camera and PID changed");
                intent("kill-intent.json",original);shell("am kill "+APP);long end=SystemClock.uptimeMillis()+4000;while(!shell("pidof "+APP).isEmpty()&&SystemClock.uptimeMillis()<end)Thread.sleep(100);
                if(!shell("pidof "+APP).isEmpty()) { if(!shell("pidof "+APP).equals(pid)||!shell("run-as "+APP+" id -u").equals(Integer.toString(uid)))throw new AssertionError("Original debug PID/UID changed");shell("run-as "+APP+" kill -9 "+pid);out.put("termination","own-debug-uid-sigkill"); }else out.put("termination","activity-manager-background-kill");
                end=SystemClock.uptimeMillis()+8000;while(!shell("pidof "+APP).isEmpty()&&SystemClock.uptimeMillis()<end)Thread.sleep(100);
                if(!shell("pidof "+APP).isEmpty()||!shell("dumpsys activity activities").contains(original.getString("cameraActivity"))||nodes(original.getString("cameraPackage")).isEmpty())throw new AssertionError("Original camera did not survive recipient death");
                out.put("originalPid",original.getInt("originalPid")).put("originalCameraRetained",true).put("receiverAbsent",true);capture("after-kill");save("after-kill-activities.txt",shell("dumpsys activity activities"));
            } else if(phase.equals("capture")) {
                JSONObject original=opened(),killed=read("kill.json");String pid=shell("pidof "+APP),camera=original.getString("cameraPackage");
                if(!killed.getString("status").equals("passed")||(!pid.isEmpty()&&pid.equals(Integer.toString(original.getInt("originalPid"))))||!shell("dumpsys activity activities").contains(original.getString("cameraActivity"))||nodes(camera).isEmpty())throw new AssertionError("Exact original cold camera required");
                List<AccessibilityNodeInfo> shutters=new ArrayList<>();long end=SystemClock.uptimeMillis()+15000;
                while(SystemClock.uptimeMillis()<end) {
                    shutters.clear();for(AccessibilityNodeInfo n:nodes(camera))if(n.isVisibleToUser()&&n.isEnabled()&&(String.valueOf(n.getViewIdResourceName()).endsWith("/shutter_button")||String.valueOf(n.getViewIdResourceName()).endsWith("/shutter_button_photo")||Arrays.asList("Shutter","Shutter button","Take photo","Capture photo").contains(String.valueOf(n.getContentDescription()))))shutters.add(n);
                    if(shutters.size()==1)break;Thread.sleep(100);
                }
                if(shutters.size()!=1)throw new AssertionError("Exact single camera shutter unavailable");intent("shutter-intent.json",original);click(shutters.get(0));boolean confirmed=false;end=SystemClock.uptimeMillis()+25000;
                while(SystemClock.uptimeMillis()<end&&nodes(APP).isEmpty()) {
                    List<AccessibilityNodeInfo> done=new ArrayList<>();for(AccessibilityNodeInfo n:nodes(camera))if(n.isVisibleToUser()&&n.isEnabled()&&(Arrays.asList("done_button","btn_done","review_done").contains(String.valueOf(n.getViewIdResourceName()).replaceAll("^.*/",""))||Arrays.asList("Done","Use photo").contains(String.valueOf(n.getContentDescription()))))done.add(n);
                    if(done.size()==1&&!confirmed) { intent("confirm-intent.json",original);capture("before-original-confirm");click(done.get(0));confirmed=true; }Thread.sleep(100);
                }
                awaitImported(original.getString("requestId"));String returned=shell("pidof "+APP);if(!returned.matches("[0-9]+")||Integer.parseInt(returned)==original.getInt("originalPid"))throw new AssertionError("New recipient PID required");
                out.put("originalPid",original.getInt("originalPid")).put("returnedPid",Integer.parseInt(returned)).put("requestId",original.getString("requestId")).put("cameraUid",original.getInt("cameraUid")).put("originalCameraResultReturned",true).put("shutterActions",1).put("confirmActions",confirmed?1:0);capture("returned");save("returned-activities.txt",shell("dumpsys activity activities"));
            } else if(phase.equals("reconcile")) {
                JSONObject original=opened(),failed=read("capture.json");
                if(!failed.getString("status").equals("failed")||!failed.getString("error").equals("java.lang.AssertionError: Exact original camera result not visible in draft")||!file("shutter-intent.json").exists()||!file("confirm-intent.json").exists())throw new AssertionError("Only original post-shutter visibility deadline may be reconciled");
                JSONObject local=new JSONObject(shell("run-as "+APP+" cat files/camera-cold-"+nonce+"-inspected.json"));
                if(!local.getString("requestId").equals(original.getString("requestId"))||!local.getString("phase").equals("consumed")||!local.getString("payloadPhase").equals("consumed")||local.getBoolean("rawOutputExists")||local.getInt("parentFileCount")!=2||local.getInt("cameraLaunches")!=0)throw new AssertionError("Exact durable original receipt required");
                String state=new String(Files.readAllBytes(file("capture-failed-activities.txt").toPath()),StandardCharsets.UTF_8),receiver=record(state,APP+"/com.agenthub.android.MainActivity");
                java.util.regex.Matcher token=java.util.regex.Pattern.compile("ActivityRecord\\{([a-f0-9]+) ").matcher(original.getString("receiverActivity"));
                if(receiver==null||!token.find()||!receiver.contains("ActivityRecord{"+token.group(1)+" "))throw new AssertionError("Original returned ActivityRecord required");
                java.util.regex.Matcher pid=java.util.regex.Pattern.compile("app=ProcessRecord\\{[^ ]+ ([0-9]+):").matcher(receiver);if(!pid.find()||Integer.parseInt(pid.group(1))==original.getInt("originalPid"))throw new AssertionError("Original return must have recreated PID");
                out.put("requestId",original.getString("requestId")).put("cameraUid",original.getInt("cameraUid")).put("originalPid",original.getInt("originalPid")).put("returnedPid",Integer.parseInt(pid.group(1)))
                    .put("originalCameraResultReturned",true).put("readOnlyConsumedReceiptReconciled",true).put("originalVisibilityDeadlinePassed",false).put("shutterActions",0).put("confirmActions",0);
            } else {
                JSONObject original=opened();
                if(phase.equals("resume-reopen")) {
                    JSONObject failed=read("reopen.json");if(!failed.getString("error").equals("java.lang.AssertionError: Exact original camera result not visible in draft")||!shell("pidof "+APP).matches("[0-9]+"))throw new AssertionError("Only original opened draft observation may continue");
                }else { if(!shell("pidof "+APP).isEmpty())throw new AssertionError("Recipient must be stopped for cold reopen");intent("reopen-intent.json",original);launch(); }
                awaitImported(original.getString("requestId"));capture(phase.equals("resume-reopen")?"reopened-refreshed":"reopened");out.put("requestId",original.getString("requestId")).put("coldMainReopenWithoutCamera",true).put("receiverPid",Integer.parseInt(shell("pidof "+APP)));
            }
            out.put("status","passed").put("modelInputs",0).put("sendInvoked",false);
        }catch(Throwable error) { try { out.put("status","failed").put("error",error.toString());capture(phase+"-failed");save(phase+"-failed-activities.txt",shell("dumpsys activity activities")); }catch(Exception ignored) { } }
        try { save(phase+".json",out.toString(2));completion.putString("stream",out.toString());finish("passed".equals(out.optString("status"))?-1:0,completion); }catch(Exception error) { completion.putString("stream",error.toString());finish(0,completion); }
    }
}
