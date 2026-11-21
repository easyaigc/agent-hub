package com.agenthub.android;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Independent test-APK UID. Produces only bounded synthetic files and grants only exact read URIs. */
public final class OwnedExternalSourceActivity extends Activity {
    private static final String TARGET="com.easyai.agenthub.android.acceptance";
    private JSONObject caseRoot;
    private JSONArray readCases() throws Exception {
        File file=new File(getFilesDir(),"owned-documents.json");
        if(!file.exists()) { caseRoot=new JSONObject();return new JSONArray(); }
        caseRoot=new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));return caseRoot.getJSONArray("cases");
    }
    private void writeCases(JSONArray rows) throws Exception {
        AtomicFile file=new AtomicFile(new File(getFilesDir(),"owned-documents.json"));FileOutputStream stream=file.startWrite();
        try { stream.write(caseRoot.put("cases",rows).toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream); }
        catch(Exception e) { file.failWrite(stream);throw e; }
    }
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder result=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(Locale.ROOT,"%02x",b));return result.toString();
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String nonce=getIntent().getStringExtra("nonce"),mode=getIntent().getStringExtra("mode");
        if(!getPackageName().equals(TARGET+".test")||nonce==null||!nonce.matches("[a-f0-9]{8,32}")||!Arrays.asList("send","multiple","view","cleanup").contains(mode)) { finish();return; }
        String prefix="inbox-source-"+nonce+"-",reportName=prefix+"report.txt";
        try {
            JSONArray old=readCases(),rows=new JSONArray();
            for(int i=0;i<old.length();i++) { JSONObject row=old.getJSONObject(i);if(!row.getString("fileName").startsWith(prefix))rows.put(row); }
            if(mode.equals("cleanup")) {
                // Cleanup is one exact recorded nonce; the caller supplies its actual recipient PID.
                // The parent binds the fresh PID/UID/package identity before and after this call.
                if(!nonce.matches("[a-f0-9]{32}"))throw new IOException("Invalid cleanup nonce");
                int targetPid=getIntent().getIntExtra("targetPid",-1);
                String cleanupOperation=getIntent().getStringExtra("cleanupOperation");
                if(targetPid<=0||cleanupOperation==null||!UUID.fromString(cleanupOperation).toString().equals(cleanupOperation))
                    throw new IOException("Invalid cleanup identity");
                File proofPath=new File(getFilesDir(),prefix+"cleanup-proof.json");
                for(String name:Arrays.asList(prefix+"cleanup-proof.json",prefix+"cleanup-proof.json.bak",prefix+"cleanup-proof.json.new",prefix+"failure.txt")) {
                    try {
                        java.nio.file.Files.readAttributes(new File(getFilesDir(),name).toPath(),java.nio.file.attribute.BasicFileAttributes.class,java.nio.file.LinkOption.NOFOLLOW_LINKS);
                        throw new IOException("Prior cleanup evidence exists");
                    } catch(java.nio.file.NoSuchFileException absent) { /* Exact absence only. */ }
                }
                // Only this unpredictable synthetic nonce and exact acceptance recipient.
                // Revoke both possible file grants plus the explicitly granted report URI.
                int targetUid=getPackageManager().getApplicationInfo(TARGET,0).uid;
                ArrayList<String> names=new ArrayList<>(Arrays.asList(prefix+"1.txt",prefix+"2.txt",reportName));
                for(String name:names) {
                    Uri uri=DocumentsContract.buildDocumentUri(getPackageName()+".owned-documents",name);
                    if(name.equals(reportName))revokeUriPermission(TARGET,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    else revokeUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); // Exact synthetic file leaf also covers Intent grants.
                    if(checkUriPermission(uri,targetPid,targetUid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=android.content.pm.PackageManager.PERMISSION_DENIED)
                        throw new IOException("Owned URI cleanup unconfirmed");
                }
                writeCases(rows);
                File[] files=getCacheDir().listFiles();if(files==null)throw new IOException("Cache enumeration unknown");
                for(File file:files)if(file.getName().startsWith(prefix)) {
                    if(java.nio.file.Files.isSymbolicLink(file.toPath())||!file.isFile()||!file.delete())throw new IOException("Owned cleanup failed");
                }
                File[] remaining=getCacheDir().listFiles();if(remaining==null)throw new IOException("Cache verification unknown");
                for(File file:remaining)if(file.getName().startsWith(prefix))throw new IOException("Owned cache remains");
                if(!hash(rows.toString().getBytes(StandardCharsets.UTF_8)).equals(hash(readCases().toString().getBytes(StandardCharsets.UTF_8))))
                    throw new IOException("Unrelated source rows changed");
                JSONObject proof=new JSONObject().put("version",1).put("nonce",nonce).put("sourcePackage",getPackageName())
                    .put("cleanupOperation",cleanupOperation).put("queryTargetPid",targetPid)
                    .put("sourceUid",Process.myUid()).put("sourcePid",Process.myPid()).put("targetPackage",TARGET).put("targetUid",targetUid)
                    .put("exactReadGrantsDenied",3).put("nonceRowsRemoved",true).put("nonceCacheRemoved",true)
                    .put("otherRowsSha256",hash(rows.toString().getBytes(StandardCharsets.UTF_8)));
                AtomicFile evidence=new AtomicFile(proofPath);
                FileOutputStream stream=evidence.startWrite();
                try {
                    android.system.Os.fchmod(stream.getFD(),0600);
                    android.system.StructStat identity=android.system.Os.fstat(stream.getFD());
                    if(!android.system.OsConstants.S_ISREG(identity.st_mode)||identity.st_uid!=Process.myUid()||(identity.st_mode&0777)!=0600||identity.st_nlink!=1)
                        throw new IOException("Private proof identity invalid");
                    stream.write(proof.toString().getBytes(StandardCharsets.UTF_8));stream.getFD().sync();
                    evidence.finishWrite(stream);
                }
                catch(Exception failure) { evidence.failWrite(stream);throw failure; }
                finish();return;
            }
            ArrayList<Uri> uris=new ArrayList<>();JSONArray expected=new JSONArray();int count=mode.equals("multiple")?2:1;
            for(int n=1;n<=count;n++) {
                String name=prefix+n+".txt",text="ANDROID_EXTERNAL_SOURCE_"+nonce+"_"+n+"\n原文件 100% %@\n";
                byte[] bytes=text.getBytes(StandardCharsets.UTF_8);rows.put(new JSONObject().put("fileName",name).put("fileText",text));
                Uri uri=DocumentsContract.buildDocumentUri(getPackageName()+".owned-documents",name);uris.add(uri);
                expected.put(new JSONObject().put("name",name).put("uri",uri.toString()).put("sha256",hash(bytes)).put("size",bytes.length));
            }
            JSONObject report=new JSONObject().put("sourcePackage",getPackageName()).put("sourceUid",Process.myUid()).put("sourcePid",Process.myPid()).put("targetPackage",TARGET).put("mode",mode).put("nonce",nonce).put("readOnlyGrant",true).put("files",expected);
            rows.put(new JSONObject().put("fileName",reportName).put("fileText",report.toString()));writeCases(rows);
            Uri reportUri=DocumentsContract.buildDocumentUri(getPackageName()+".owned-documents",reportName);grantUriPermission(TARGET,reportUri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent outgoing;
            if(mode.equals("view"))outgoing=new Intent(Intent.ACTION_VIEW).setDataAndType(uris.get(0),"text/plain");
            else if(count==1)outgoing=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uris.get(0));
            else outgoing=new Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
            ClipData clip=ClipData.newRawUri("Owned external source",uris.get(0));for(int i=1;i<uris.size();i++)clip.addItem(new ClipData.Item(uris.get(i)));
            outgoing.setClipData(clip);outgoing.setPackage(TARGET).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(outgoing);
        } catch(Exception failure) {
            try {
                java.io.FileDescriptor fd=android.system.Os.open(new File(getFilesDir(),prefix+"failure.txt").getAbsolutePath(),
                    android.system.OsConstants.O_WRONLY|android.system.OsConstants.O_CREAT|android.system.OsConstants.O_EXCL|android.system.OsConstants.O_NOFOLLOW,0600);
                try(FileOutputStream stream=new FileOutputStream(fd)) { stream.write(failure.getClass().getName().getBytes(StandardCharsets.UTF_8));stream.getFD().sync(); }
            }catch(Exception ignored) { /* Retain any original failure evidence. */ }
        } finally { finish(); }
    }
}
