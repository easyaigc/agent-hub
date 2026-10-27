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
                writeCases(rows);
                File[] files=getCacheDir().listFiles();if(files!=null)for(File file:files)if(file.getName().startsWith(prefix)&&!file.delete())throw new IOException("Owned cleanup failed");
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
            try { java.nio.file.Files.write(new File(getFilesDir(),prefix+"failure.txt").toPath(),failure.getClass().getName().getBytes(StandardCharsets.UTF_8)); }catch(Exception ignored) { }
        } finally { finish(); }
    }
}
