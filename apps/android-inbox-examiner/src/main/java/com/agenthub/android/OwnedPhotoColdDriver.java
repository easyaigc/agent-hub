package com.agenthub.android;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;

/** Independent source UID drives only its exact isolated recipient and its one known photo. */
public final class OwnedPhotoColdDriver extends Instrumentation {
    private static final String APP="com.easyai.agenthub.android.acceptance.photos";
    private Bundle args;private String nonce,phase;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);args=arguments;start(); }
    private File file(String suffix) { return new File(getTargetContext().getFilesDir(),"photo-cold-"+nonce+"-"+suffix); }
    private String shell(String command) throws Exception {
        try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command));ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);return new String(out.toByteArray(),StandardCharsets.UTF_8).trim();
        }
    }
    private JSONObject read(File path) throws Exception { return new JSONObject(new String(Files.readAllBytes(path.toPath()),StandardCharsets.UTF_8)); }
    private void save(String suffix,String value) throws Exception { Files.write(file(suffix).toPath(),value.getBytes(StandardCharsets.UTF_8)); }
    private JSONObject source() throws Exception {
        JSONObject value=read(new File(getTargetContext().getFilesDir(),"photo-media-"+nonce+".json"));
        if(!value.getString("nonce").equals(nonce)||!value.getString("sourcePackage").equals(APP+".test")||value.getInt("sourceUid")!=android.os.Process.myUid()||value.getBoolean("photoUriExplicitlyGranted"))throw new AssertionError("Exact independent owned source required");return value;
    }
    private void sourceActivity(String mode) { getTargetContext().startActivity(new Intent().setComponent(new ComponentName(APP+".test","com.agenthub.android.OwnedPhotoMediaActivity")).putExtra("nonce",nonce).putExtra("mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    private AccessibilityNodeInfo currentRoot() {
        if(android.os.Build.VERSION.SDK_INT>=33)getUiAutomation().clearCache();
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null)root.refresh();return root;
    }
    private List<AccessibilityNodeInfo> nodes(String owner) {
        AccessibilityNodeInfo root=currentRoot();List<AccessibilityNodeInfo> result=new ArrayList<>();
        if(root!=null&&owner.contentEquals(root.getPackageName()))visit(root,result);return result;
    }
    private void visit(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> result) { if(node==null)return;result.add(node);for(int n=0;n<node.getChildCount();n++)visit(node.getChild(n),result); }
    private void capture(String name) throws Exception {
        AccessibilityNodeInfo root=currentRoot();List<AccessibilityNodeInfo> all=new ArrayList<>();visit(root,all);StringBuilder text=new StringBuilder();
        for(AccessibilityNodeInfo node:all) { Rect bounds=new Rect();node.getBoundsInScreen(bounds);text.append(node.getPackageName()).append('|').append(node.getViewIdResourceName()).append('|').append(node.getText()).append('|').append(node.getContentDescription()).append('|').append(node.isEnabled()).append('|').append(node.isVisibleToUser()).append('|').append(bounds.toShortString()).append('\n'); }
        save(name+".txt",text.toString());Bitmap bitmap=getUiAutomation().takeScreenshot();if(bitmap!=null)try(FileOutputStream stream=new FileOutputStream(file(name+".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,stream); }finally { bitmap.recycle(); }
    }
    private AccessibilityNodeInfo await(String owner,String label,long timeout) throws Exception {
        long end=SystemClock.uptimeMillis()+timeout;
        while(SystemClock.uptimeMillis()<end) {
            List<AccessibilityNodeInfo> hits=new ArrayList<>();Set<AccessibilityNodeInfo> actions=new HashSet<>();for(AccessibilityNodeInfo node:nodes(owner))if(node.isVisibleToUser()&&(label.contentEquals(node.getText()==null?"":node.getText())||label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))) {
                AccessibilityNodeInfo action=node;while(action!=null&&!action.isClickable())action=action.getParent();if(action==null)action=node;if(actions.add(action))hits.add(action);
            }
            if(hits.size()==1) { AccessibilityNodeInfo action=hits.get(0);while(action!=null&&!action.isClickable())action=action.getParent();if(action==null||action.isEnabled())return hits.get(0); }
            if(hits.size()>1)throw new AssertionError("Ambiguous native control: "+label);Thread.sleep(100);
        }
        throw new AssertionError("Exact native control absent: "+owner+" / "+label);
    }
    private void click(AccessibilityNodeInfo node) {
        while(node!=null&&!node.isClickable())node=node.getParent();if(node==null||!node.isEnabled()||!node.isVisibleToUser()||!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Exact native click rejected");
    }
    private void launch() { getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(new ComponentName(APP,"com.agenthub.android.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
    private String record(String activities,String component) {
        for(String block:activities.split("(?=\\* Hist\\s+#\\d+:)"))if(block.split("\n",2)[0].contains(component))return block;return null;
    }
    private void awaitImported(JSONObject original) throws Exception {
        long end=SystemClock.uptimeMillis()+30000;String exact=original.getString("fileName"),alias=Uri.parse(original.getString("uri")).getLastPathSegment()+".png";
        while(SystemClock.uptimeMillis()<end) {
            List<AccessibilityNodeInfo> all=nodes(APP);for(AccessibilityNodeInfo node:all)if(node.isVisibleToUser()&&(exact.contentEquals(node.getText()==null?"":node.getText())||alias.contentEquals(node.getText()==null?"":node.getText())))return;
            // The message list is also scrollable. Only the known draft attachment's
            // ancestor may move while locating its second attachment.
            for(AccessibilityNodeInfo node:all)if(("existing-"+nonce+".txt").contentEquals(node.getText()==null?"":node.getText())) {
                for(AccessibilityNodeInfo parent=node.getParent();parent!=null;parent=parent.getParent())if(parent.isScrollable()&&parent.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))break;
            }Thread.sleep(200);
        }
        throw new AssertionError("Exact original normalized photo not visible in native recipient");
    }
    private void choose(String owner,JSONObject original) throws Exception {
        String activities=shell("dumpsys activity activities");
        if(activities.contains("act=android.intent.action.OPEN_DOCUMENT"))throw new AssertionError("Legacy document-backed image route requires separate owned album navigation");
        click(await(owner,"Albums",10000));
        List<AccessibilityNodeInfo> albums=new ArrayList<>();for(AccessibilityNodeInfo node:nodes(owner))if(original.getString("album").contentEquals(node.getText()==null?"":node.getText()))albums.add(node);
        if(albums.size()==1)click(albums.get(0));else {
            String media=shell("content query --uri content://media/external/images/media --projection _id");List<String> ids=new ArrayList<>();Matcher matcher=Pattern.compile("_id=(\\d+)").matcher(media);while(matcher.find())ids.add(matcher.group(1));
            String id=Uri.parse(original.getString("uri")).getLastPathSegment();save("sole-source-media.txt",media);if(!ids.equals(Collections.singletonList(id)))throw new AssertionError("No exact album and global media is not the sole owned source");click(await(owner,"Photos",10000));
        }
        List<AccessibilityNodeInfo> thumbnails=new ArrayList<>();long end=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<end) {
            thumbnails.clear();Set<String> bounds=new HashSet<>();List<AccessibilityNodeInfo> all=nodes(owner),exact=new ArrayList<>();
            for(AccessibilityNodeInfo node:all) { String id=String.valueOf(node.getViewIdResourceName());if(node.isVisibleToUser()&&(id.endsWith("/icon_thumbnail")||id.endsWith("/thumbnail")||id.endsWith("/picker_item_thumbnail")))exact.add(node); }
            for(AccessibilityNodeInfo node:exact.isEmpty()?all:exact) {
                String id=String.valueOf(node.getViewIdResourceName()),description=String.valueOf(node.getContentDescription());
                if(node.isVisibleToUser()&&(id.endsWith("/icon_thumbnail")||id.endsWith("/thumbnail")||id.endsWith("/picker_item_thumbnail")||description.startsWith("Photo taken"))) {
                    Rect box=new Rect();node.getBoundsInScreen(box);if(bounds.add(box.flattenToString()))thumbnails.add(node);
                }
            }
            if(thumbnails.size()==1)break;Thread.sleep(100);
        }
        if(thumbnails.size()!=1)throw new AssertionError("Only one independently known photo may be selected");
        Rect bounds=new Rect();thumbnails.get(0).getBoundsInScreen(bounds);save("selection-intent.json",new JSONObject().put("sourceUri",original.getString("uri")).put("sourcePngSha256",original.getString("sourcePngSha256")).put("bounds",bounds.flattenToString()).toString(2));capture("known-photo-before-selection");click(thumbnails.get(0));
        end=SystemClock.uptimeMillis()+12000;
        while(SystemClock.uptimeMillis()<end&&nodes(APP).isEmpty()) {
            List<AccessibilityNodeInfo> buttons=new ArrayList<>();for(AccessibilityNodeInfo node:nodes(owner))if(node.isEnabled()&&node.getText()!=null&&node.getText().toString().matches("(?i)(Add|Done|Open|Select)(\\s*[（(]1[）)])?"))buttons.add(node);
            if(buttons.size()==1)click(buttons.get(0));Thread.sleep(150);
        }
    }
    @Override public void onStart() {
        nonce=args.getString("nonce");phase=args.getString("phase");JSONObject result=new JSONObject();Bundle completion=new Bundle();
        try {
            if(!getTargetContext().getPackageName().equals(APP+".test")||nonce==null||!nonce.matches("[a-f0-9]{32}")||!Arrays.asList("prepare","open","save-task","choose","choose-recreated","ack-fixture","cleanup","reopen","reopen-scroll").contains(phase)||!(android.os.Build.HARDWARE.equals("ranchu")||android.os.Build.HARDWARE.equals("goldfish")))throw new AssertionError("Owned SDK photo scope mismatch");
            int receiverUid=getTargetContext().getPackageManager().getApplicationInfo(APP,0).uid;if(receiverUid==android.os.Process.myUid())throw new AssertionError("Independent source UID required");
            result.put("phase",phase).put("receiverUid",receiverUid).put("sourceUid",android.os.Process.myUid()).put("sourcePid",android.os.Process.myPid()).put("pickerLaunches",0);
            if(phase.equals("prepare")) {
                if(file("source-intent.json").exists())throw new AssertionError("Original source intent exists; query its ledger without replay");save("source-intent.json",new JSONObject().put("nonce",nonce).put("action","prepare-one-owned-photo").toString());sourceActivity("prepare");
                long end=SystemClock.uptimeMillis()+25000;JSONObject photo=null;
                while(SystemClock.uptimeMillis()<end) { try { photo=source();if(photo.getString("status").equals("prepared"))break; }catch(java.nio.file.NoSuchFileException ignored) { }Thread.sleep(100); }
                if(photo==null||!photo.getString("status").equals("prepared"))throw new AssertionError("Original owned source was not confirmed prepared");result.put("source",photo);
            } else if(phase.equals("open")) {
                JSONObject photo=source();if(!photo.getString("status").equals("prepared")||file("open-intent.json").exists()||!shell("pidof "+APP).isEmpty())throw new AssertionError("Fresh original photo launch required");
                save("open-intent.json",new JSONObject().put("nonce",nonce).put("action","one-native-photo-picker").toString());launch();await(APP,"photo-original-"+nonce+" 100% %@",30000);
                String pid=shell("pidof "+APP);if(!pid.matches("[0-9]+")||getTargetContext().checkUriPermission(Uri.parse(photo.getString("uri")),Integer.parseInt(pid),receiverUid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_DENIED)throw new AssertionError("Photo must be unreadable by exact receiver before selection");
                click(await(APP,"Add attachment",30000));click(await(APP,"Choose image",15000));
                long end=SystemClock.uptimeMillis()+20000;String activities="",receiver=null,chooser=null,owner=null;
                while(SystemClock.uptimeMillis()<end) {
                    AccessibilityNodeInfo root=currentRoot();activities=shell("dumpsys activity activities");
                    if(root!=null&&!APP.contentEquals(root.getPackageName())&&!getTargetContext().getPackageName().contentEquals(root.getPackageName())) {
                        owner=root.getPackageName().toString();receiver=record(activities,APP+"/com.agenthub.android.MainActivity");chooser=record(activities,owner+"/");
                        if(receiver!=null&&chooser!=null&&receiver.contains("state=STOPPED")&&(receiver.contains("mHaveState=true")||receiver.contains("haveState=true")))break;
                    }
                    Thread.sleep(100);
                }
                save("before-kill-activities.txt",activities);capture("before-kill");
                if(receiver==null||chooser==null||!receiver.contains("state=STOPPED")||!(receiver.contains("mHaveState=true")||receiver.contains("haveState=true")))throw new AssertionError("Original photo receiver is not stopped with saved state under the system chooser");
                ApplicationInfo info=getTargetContext().getPackageManager().getApplicationInfo(owner,0);if((info.flags&ApplicationInfo.FLAG_SYSTEM)==0||info.uid==receiverUid||!chooser.contains("launchedFromUid="+receiverUid+" launchedFromPackage="+APP)||!chooser.contains("typ=image/*"))throw new AssertionError("Exact image-only system caller/owner mismatch");
                result.put("originalPid",Integer.parseInt(pid)).put("chooserPackage",owner).put("chooserUid",info.uid).put("receiverActivity",receiver.split("\n",2)[0]).put("chooserActivity",chooser.split("\n",2)[0]).put("pickerLaunches",1);
                save("kill-intent.json",result.toString(2));shell("am kill "+APP);end=SystemClock.uptimeMillis()+5000;while(!shell("pidof "+APP).isEmpty()&&SystemClock.uptimeMillis()<end)Thread.sleep(100);
                if(!shell("pidof "+APP).isEmpty()) { if(!shell("pidof "+APP).equals(pid)||!shell("run-as "+APP+" id -u").equals(Integer.toString(receiverUid)))throw new AssertionError("Original debug PID/UID changed before scoped termination");shell("run-as "+APP+" kill -9 "+pid);result.put("termination","own-debug-uid-sigkill"); }else result.put("termination","activity-manager-background-kill");
                end=SystemClock.uptimeMillis()+10000;while(!shell("pidof "+APP).isEmpty()&&SystemClock.uptimeMillis()<end)Thread.sleep(100);if(!shell("pidof "+APP).isEmpty())throw new AssertionError("Original receiver did not die");
                activities=shell("dumpsys activity activities");if(!activities.contains(result.getString("chooserActivity"))||nodes(owner).isEmpty())throw new AssertionError("Original native chooser was not retained");save("after-kill-activities.txt",activities);capture("after-kill");result.put("backgroundReceiverKilled",true).put("originalChooserRetained",true);
            } else if(phase.equals("save-task")) {
                if(android.os.Build.VERSION.SDK_INT<29||file("save-task-intent.json").exists()||file("selection-intent.json").exists())throw new AssertionError("Only original unselected SDK29+ task may be saved once");
                JSONObject failed=read(file("open.json"));if(!failed.getString("error").equals("java.lang.AssertionError: Original photo receiver is not stopped with saved state under the system chooser"))throw new AssertionError("Original unsaved chooser witness required");
                String before=new String(Files.readAllBytes(file("before-kill-activities.txt").toPath()),StandardCharsets.UTF_8),current=shell("dumpsys activity activities");
                String receiver=record(before,APP+"/com.agenthub.android.MainActivity");if(receiver==null||!receiver.contains("state=PAUSED")||!receiver.contains("mHaveState=false"))throw new AssertionError("Original paused/unsaved recipient witness required");
                String receiverHeader=receiver.split("\n",2)[0];if(!current.contains(receiverHeader))throw new AssertionError("Original recipient ActivityRecord changed");
                AccessibilityNodeInfo root=currentRoot();String owner=root==null?"":root.getPackageName().toString();String chooser=record(current,owner+"/");
                ApplicationInfo info=getTargetContext().getPackageManager().getApplicationInfo(owner,0);
                if(chooser==null||(info.flags&ApplicationInfo.FLAG_SYSTEM)==0||info.uid==receiverUid||!chooser.contains("launchedFromUid="+receiverUid+" launchedFromPackage="+APP)||!chooser.contains("typ=image/*"))throw new AssertionError("Exact original image picker caller required");
                String chooserHeader=chooser.split("\n",2)[0];if(!before.contains(chooserHeader))throw new AssertionError("Original photo chooser ActivityRecord changed");
                Matcher task=Pattern.compile(" t(\\d+)[ }]").matcher(receiverHeader);if(!task.find())throw new AssertionError("Observed original task ID missing");int taskId=Integer.parseInt(task.group(1));String pid=shell("pidof "+APP);if(!pid.matches("[0-9]+"))throw new AssertionError("Original live recipient PID required");
                save("save-task-intent.json",new JSONObject().put("originalPid",Integer.parseInt(pid)).put("taskId",taskId).put("receiverActivity",receiverHeader).put("chooserActivity",chooserHeader).put("action","background-and-return-same-native-task-without-selector-relaunch").toString(2));
                if(!getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME))throw new AssertionError("Native HOME transition rejected");
                long end=SystemClock.uptimeMillis()+15000;String saved=null;
                while(SystemClock.uptimeMillis()<end) { current=shell("dumpsys activity activities");saved=record(current,APP+"/com.agenthub.android.MainActivity");if(saved!=null&&saved.contains("state=STOPPED")&&saved.contains("mHaveState=true"))break;Thread.sleep(100); }
                if(saved==null||!saved.contains("state=STOPPED")||!saved.contains("mHaveState=true")||!current.contains(chooserHeader))throw new AssertionError("Same native task was not saved in background");save("task-background-saved.txt",current);
                try { getUiAutomation().adoptShellPermissionIdentity("android.permission.REORDER_TASKS");getTargetContext().getSystemService(android.app.ActivityManager.class).moveTaskToFront(taskId,0); }
                finally { getUiAutomation().dropShellPermissionIdentity(); }
                end=SystemClock.uptimeMillis()+15000;
                while(SystemClock.uptimeMillis()<end) { current=shell("dumpsys activity activities");if(!nodes(owner).isEmpty()&&current.contains(chooserHeader))break;Thread.sleep(100); }
                saved=record(current,APP+"/com.agenthub.android.MainActivity");if(saved==null||!saved.contains("mHaveState=true")||!current.contains(receiverHeader)||!current.contains(chooserHeader)||!shell("pidof "+APP).equals(pid)||nodes(owner).isEmpty())throw new AssertionError("Same original task/process was not restored under the saved picker");
                result.put("originalPid",Integer.parseInt(pid)).put("chooserPackage",owner).put("chooserUid",info.uid).put("receiverActivity",receiverHeader).put("chooserActivity",chooserHeader).put("osSavedStatePreparedByTaskBackgrounding",true).put("sameOriginalTaskAndPicker",true).put("temporaryTaskReorderPermissionDropped",true);
                save("saved-kill-intent.json",result.toString(2));save("saved-before-kill-activities.txt",current);capture("saved-before-kill");
                if(!shell("run-as "+APP+" id -u").equals(Integer.toString(receiverUid)))throw new AssertionError("Exact debug recipient UID mismatch");shell("run-as "+APP+" kill -9 "+pid);
                end=SystemClock.uptimeMillis()+10000;while(!shell("pidof "+APP).isEmpty()&&SystemClock.uptimeMillis()<end)Thread.sleep(100);current=shell("dumpsys activity activities");
                if(!shell("pidof "+APP).isEmpty()||!current.contains(chooserHeader)||nodes(owner).isEmpty())throw new AssertionError("Original saved picker did not survive recipient death");save("saved-after-kill-activities.txt",current);capture("saved-after-kill");result.put("backgroundReceiverKilled",true).put("originalChooserRetained",true).put("termination","own-debug-uid-sigkill-after-os-state-save");
            } else if(phase.equals("choose")||phase.equals("choose-recreated")) {
                if(phase.equals("choose-recreated")) { JSONObject previous=read(file("choose.json"));if(!previous.getString("error").equals("java.lang.AssertionError: Original unselected cold chooser required")||file("selection-intent.json").exists())throw new AssertionError("Only original pre-selection cold-state check may continue"); }
                JSONObject original=read(file("save-task.json").isFile()?file("save-task.json"):file("open.json"));String beforePid=shell("pidof "+APP);
                if(!original.getBoolean("backgroundReceiverKilled")||(!beforePid.isEmpty()&&(!beforePid.matches("[0-9]+")||Integer.parseInt(beforePid)==original.getInt("originalPid")))||file("selection-intent.json").exists())throw new AssertionError("Original unselected cold chooser required");
                if(!beforePid.isEmpty()) { String activities=shell("dumpsys activity activities");if(!activities.contains(original.getString("receiverActivity")))throw new AssertionError("Original visible recipient ActivityRecord changed");result.put("receiverRecreatedBeforeSelection",true).put("preSelectionPid",Integer.parseInt(beforePid)); }
                String owner=original.getString("chooserPackage");if(nodes(owner).isEmpty()||!shell("dumpsys activity activities").contains(original.getString("chooserActivity")))throw new AssertionError("Exact original chooser absent");JSONObject photo=source();choose(owner,photo);awaitImported(photo);
                int pid=Integer.parseInt(shell("pidof "+APP));if(pid==original.getInt("originalPid"))throw new AssertionError("Receiver process was not recreated");result.put("originalPid",original.getInt("originalPid")).put("returnedPid",pid).put("originalNativeResultReturned",true);capture("returned");save("returned-activities.txt",shell("dumpsys activity activities"));
            } else if(phase.equals("ack-fixture")) {
                JSONObject previous=read(file("choose-recreated.json")),original=read(file("save-task.json"));
                if(!previous.getString("error").equals("java.lang.AssertionError: Exact original normalized photo not visible in native recipient")||!file("selection-intent.json").exists()||file("fixture-error-ack-intent.json").exists())throw new AssertionError("Original returned photo and exact fixture-overlay failure required");
                String pid=shell("pidof "+APP);if(!pid.matches("[0-9]+")||Integer.parseInt(pid)==original.getInt("originalPid")||!shell("dumpsys activity activities").contains(original.getString("receiverActivity")))throw new AssertionError("Original recreated recipient ActivityRecord required");
                boolean knownError=false;for(AccessibilityNodeInfo node:nodes(APP))if("无法连接电脑，请检查网络或稍后重试".contentEquals(node.getText()==null?"":node.getText()))knownError=true;
                if(knownError) { save("fixture-error-ack-intent.json",new JSONObject().put("message","无法连接电脑，请检查网络或稍后重试").put("action","acknowledge-exact-local-fixture-connection-error").toString());click(await(APP,"OK",5000));result.put("knownFixtureConnectionErrorAcknowledged",true); }
                awaitImported(source());capture("returned-after-fixture-ack");save("returned-after-fixture-ack-activities.txt",shell("dumpsys activity activities"));result.put("originalPid",original.getInt("originalPid")).put("returnedPid",Integer.parseInt(pid)).put("originalNativeResultReturned",true);
            } else if(phase.equals("cleanup")) {
                JSONObject proof=new JSONObject(shell("run-as "+APP+" cat files/photo-cold-"+nonce+"-returned.json"));if(!proof.getString("status").equals("passed")||!proof.getBoolean("payloadConsumed"))throw new AssertionError("Confirmed original encrypted result required before source cleanup");
                sourceActivity("cleanup");long end=SystemClock.uptimeMillis()+20000;while(!source().getString("status").equals("consumed")&&SystemClock.uptimeMillis()<end)Thread.sleep(100);if(!source().getString("status").equals("consumed"))throw new AssertionError("Exact owned source cleanup unconfirmed");result.put("onlyOriginalOwnedPhotoRemoved",true);
            } else if(phase.equals("reopen-scroll")) {
                JSONObject previous=read(file("reopen.json"));if(!previous.getString("error").equals("java.lang.AssertionError: Exact original normalized photo not visible in native recipient")||!source().getString("status").equals("consumed"))throw new AssertionError("Only original reopened draft scroll may continue");
                if(!shell("pidof "+APP).matches("[0-9]+"))throw new AssertionError("Original cold-opened recipient must still be running");awaitImported(source());capture("reopened-after-draft-scroll");result.put("coldMainReopenWithoutChooser",true).put("receiverPid",Integer.parseInt(shell("pidof "+APP))).put("onlyOriginalDraftStripScrolled",true);
            } else {
                if(!shell("pidof "+APP).isEmpty())throw new AssertionError("Recipient must be stopped before MAIN reopen");launch();awaitImported(source());capture("reopened");result.put("coldMainReopenWithoutChooser",true).put("receiverPid",Integer.parseInt(shell("pidof "+APP)));
            }
            result.put("status","passed").put("modelInputs",0).put("sendInvoked",false);
        }catch(Throwable error) { try { result.put("status","failed").put("error",error.toString());capture(phase+"-failed");save(phase+"-failed-activities.txt",shell("dumpsys activity activities")); }catch(Exception ignored) { } }
        try { save(phase+".json",result.toString(2));completion.putString("stream",result.toString());finish("passed".equals(result.optString("status"))?-1:0,completion); }catch(Exception error) { completion.putString("stream",error.toString());finish(0,completion); }
    }
}
