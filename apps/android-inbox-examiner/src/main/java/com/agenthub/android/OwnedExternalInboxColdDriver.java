package com.agenthub.android;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.accessibility.AccessibilityNodeInfo;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** SDK-only examiner runs in the source APK UID, so it never starts instrumentation in the recipient. */
public final class OwnedExternalInboxColdDriver extends Instrumentation {
    private static final String APP="com.easyai.agenthub.android.acceptance";
    private Bundle args;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);args=arguments;start(); }
    private String shell(String command) throws Exception {
        try(ParcelFileDescriptor.AutoCloseInputStream input=new ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation().executeShellCommand(command))) {
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
            return new String(output.toByteArray(),StandardCharsets.UTF_8).trim();
        }
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo root,String text) {
        if(root==null)return null;
        if(text.contentEquals(root.getText()==null?"":root.getText()))return root;
        for(int n=0;n<root.getChildCount();n++) { AccessibilityNodeInfo hit=find(root.getChild(n),text);if(hit!=null)return hit; }
        return null;
    }
    private AccessibilityNodeInfo awaitFiles(String nonce) throws Exception {
        long end=android.os.SystemClock.uptimeMillis()+45000;
        while(android.os.SystemClock.uptimeMillis()<end) {
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root!=null&&APP.contentEquals(root.getPackageName())&&find(root,"inbox-source-"+nonce+"-1.txt")!=null&&find(root,"inbox-source-"+nonce+"-2.txt")!=null&&
                (find(root,"Files from other apps")!=null||find(root,"来自其他应用的附件")!=null))return root;
            Thread.sleep(100);
        }
        throw new AssertionError("Exact synthetic files and inbox title were not in the recipient native window");
    }
    @Override public void onStart() {
        String nonce=args.getString("nonce"),phase=args.getString("phase");JSONObject result=new JSONObject();Bundle completion=new Bundle();
        try {
            if(!getTargetContext().getPackageName().equals(APP+".test")||nonce==null||!nonce.matches("[a-f0-9]{32}")||!Arrays.asList("receive","reopen").contains(phase))throw new AssertionError("Examiner scope mismatch");
            if(phase.equals("receive")) {
                if(!shell("pidof "+APP).isEmpty())throw new AssertionError("Recipient must be stopped before independent source delivery");
                Intent source=new Intent().setComponent(new ComponentName(APP+".test","com.agenthub.android.OwnedExternalSourceActivity"))
                    .putExtra("nonce",nonce).putExtra("mode","multiple").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getTargetContext().startActivity(source);
            } else {
                if(!shell("pidof "+APP).isEmpty())throw new AssertionError("Recipient must be stopped before cold MAIN reopen");
                getTargetContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    .setComponent(new ComponentName(APP,"com.agenthub.android.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            AccessibilityNodeInfo root=awaitFiles(nonce);String pid=shell("pidof "+APP);if(!pid.matches("[0-9]+"))throw new AssertionError("Exact recipient PID missing");
            int uid=getTargetContext().getPackageManager().getApplicationInfo(APP,0).uid;
            if(uid==android.os.Process.myUid()||pid.equals(Integer.toString(android.os.Process.myPid())))throw new AssertionError("Examiner must be independent UID/PID");
            result.put("phase",phase).put("recipientPid",Integer.parseInt(pid)).put("examinerPid",android.os.Process.myPid()).put("recipientUid",uid).put("examinerUid",android.os.Process.myUid())
                .put("exactFilesInNativeWindow",true).put("nativeColdLaunch",true).put("sourceRelaunched",phase.equals("receive")).put("addOrSendInvoked",false);
            Bitmap image=getUiAutomation().takeScreenshot();if(image==null)throw new AssertionError("Native screenshot unavailable");
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(new File(getTargetContext().getFilesDir(),"inbox-cold-"+nonce+"-"+phase+".png"))) { image.compress(Bitmap.CompressFormat.PNG,100,output); }finally { image.recycle(); }
            AccessibilityNodeInfo later=find(root,"Add later");if(later==null)later=find(root,"稍后添加");
            while(later!=null&&(later.getActions()&AccessibilityNodeInfo.ACTION_CLICK)==0)later=later.getParent();
            if(later==null||!later.isEnabled()||!later.performAction(AccessibilityNodeInfo.ACTION_CLICK))throw new AssertionError("Native Later action rejected");
            long end=android.os.SystemClock.uptimeMillis()+10000;boolean closed=false;
            while(android.os.SystemClock.uptimeMillis()<end) {
                AccessibilityNodeInfo now=getUiAutomation().getRootInActiveWindow();
                if(now!=null&&APP.contentEquals(now.getPackageName())&&find(now,"inbox-source-"+nonce+"-1.txt")==null) { closed=true;break; }
                Thread.sleep(100);
            }
            if(!closed)throw new AssertionError("Later did not close exact inbox");
            result.put("laterClosedWithoutAdd",true).put("status","passed");
        } catch(Throwable error) { try { result.put("status","failed").put("error",error.toString()); }catch(Exception ignored) { } }
        try { java.nio.file.Files.write(new File(getTargetContext().getFilesDir(),"inbox-cold-"+nonce+"-"+phase+".json").toPath(),result.toString(2).getBytes(StandardCharsets.UTF_8)); }
        catch(Exception error) { completion.putString("stream","Evidence write failed");finish(0,completion);return; }
        completion.putString("stream",result.toString());finish("passed".equals(result.optString("status"))?-1:0,completion);
    }
}
