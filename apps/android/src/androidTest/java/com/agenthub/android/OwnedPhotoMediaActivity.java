package com.agenthub.android;

import android.app.Activity;
import android.content.ContentValues;
import android.graphics.*;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Source-test APK only: publish one synthetic photo in a nonce-owned album; never grant that photo to the recipient. */
public final class OwnedPhotoMediaActivity extends Activity {
    private static final String TARGET="com.easyai.agenthub.android.acceptance";
    private String recipient() {
        if(getPackageName().equals(TARGET+".test"))return TARGET;
        if(getPackageName().equals(TARGET+".photos.test"))return TARGET+".photos";
        return null;
    }
    private static final String ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static String hash(byte[] bytes) throws Exception {
        StringBuilder out=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(Locale.ROOT,"%02x",value));return out.toString();
    }
    private byte[] read(File file) throws Exception { return java.nio.file.Files.readAllBytes(file.toPath()); }
    private void atomic(File target,JSONObject value) throws Exception {
        AtomicFile file=new AtomicFile(target);FileOutputStream stream=file.startWrite();
        try { stream.write(value.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(stream); }catch(Exception error) { file.failWrite(stream);throw error; }
    }
    private Uri collection() { return Build.VERSION.SDK_INT>=29?MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY):MediaStore.Images.Media.EXTERNAL_CONTENT_URI; }
    private void publish(String nonce,String suffix,JSONObject report) throws Exception {
        String name="photo-source-"+nonce+"-"+suffix+".txt";
        File target=new File(getFilesDir(),"owned-documents.json");JSONObject root=target.exists()?new JSONObject(new String(read(target),StandardCharsets.UTF_8)):new JSONObject();
        JSONArray old=root.optJSONArray("cases"),rows=new JSONArray();if(old!=null)for(int n=0;n<old.length();n++) { JSONObject row=old.getJSONObject(n);if(!name.equals(row.getString("fileName")))rows.put(row); }
        rows.put(new JSONObject().put("fileName",name).put("fileText",report.toString()));root.put("cases",rows);atomic(target,root);
        grantUriPermission(recipient(),DocumentsContract.buildDocumentUri(getPackageName()+".owned-documents",name),android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);String nonce=getIntent().getStringExtra("nonce"),mode=getIntent().getStringExtra("mode");
        if(recipient()==null||nonce==null||!nonce.matches("[a-f0-9]{32}")||!Arrays.asList("prepare","cleanup").contains(mode)) { finish();return; }
        new Thread(()->{
            synchronized(OwnedPhotoMediaActivity.class) {
                File ledger=new File(getFilesDir(),"photo-media-"+nonce+".json");JSONObject record=null;
                try {
                    if(mode.equals("prepare")) {
                        if(ledger.exists()) {
                            record=new JSONObject(new String(read(ledger),StandardCharsets.UTF_8));
                            if(!record.optString("status").equals("prepared"))throw new IOException("Prior preparation is not replayable; query/cleanup its original nonce");
                            verifyPhoto(record);publish(nonce,"report",record);
                        } else { record=prepare(nonce,ledger);publish(nonce,"report",record); }
                    } else {
                        if(!ledger.exists())throw new IOException("Original owned ledger missing");
                        record=new JSONObject(new String(read(ledger),StandardCharsets.UTF_8));cleanup(record);record.put("status","consumed");atomic(ledger,record);
                        publish(nonce,"cleanup",new JSONObject().put("status","cleaned").put("nonce",nonce).put("onlyOriginalOwnedPhotoRemoved",true));
                    }
                }catch(Exception error) {
                    try { if(record!=null&&mode.equals("prepare")&&!record.optString("status").equals("prepared")) { record.put("status","failed");atomic(ledger,record); }
                        publish(nonce,mode.equals("prepare")?"report":"cleanup",new JSONObject().put("status","failed").put("nonce",nonce).put("errorType",error.getClass().getSimpleName()).put("message",error.getMessage()));
                    }catch(Exception ignored) { }
                }
            }
            runOnUiThread(this::finish);
        },"owned-photo-media-source").start();
    }
    private JSONObject prepare(String nonce,File ledger) throws Exception {
        String album="AgentHub_"+nonce,name="photo-"+nonce+".png",marker="Z";SecureRandom random=new SecureRandom();for(int n=0;n<5;n++)marker+=ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        Bitmap bitmap=Bitmap.createBitmap(640,320,Bitmap.Config.ARGB_8888);byte[] png,pixelBytes;
        try {
            Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setColor(Color.BLACK);text.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.BOLD));text.setTextSize(68);
            canvas.drawText("TOKEN",36,100,text);canvas.drawText(marker,36,225,text);
            int[] pixels=new int[640*320];bitmap.getPixels(pixels,0,640,0,0,640,320);ByteBuffer buffer=ByteBuffer.allocate(pixels.length*4);for(int value:pixels)buffer.putInt(value);pixelBytes=buffer.array();
            ByteArrayOutputStream output=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("Synthetic PNG failed");png=output.toByteArray();
        }finally { bitmap.recycle(); }
        JSONObject record=new JSONObject().put("status","preparing").put("nonce",nonce).put("sourcePackage",getPackageName()).put("sourceUid",android.os.Process.myUid()).put("sourcePid",android.os.Process.myPid())
            .put("album",album).put("fileName",name).put("marker",marker).put("width",640).put("height",320).put("sourcePngSha256",hash(png)).put("pixelSha256",hash(pixelBytes)).put("photoUriExplicitlyGranted",false);
        atomic(ledger,record);
        Uri uri;
        if(Build.VERSION.SDK_INT>=29) {
            ContentValues values=new ContentValues();values.put(MediaStore.Images.Media.DISPLAY_NAME,name);values.put(MediaStore.Images.Media.MIME_TYPE,"image/png");values.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/"+album+"/");values.put(MediaStore.Images.Media.IS_PENDING,1);
            uri=getContentResolver().insert(collection(),values);if(uri==null)throw new IOException("Owned image row unavailable");record.put("uri",uri.toString());atomic(ledger,record);
            try(OutputStream output=getContentResolver().openOutputStream(uri,"w")) { if(output==null)throw new IOException("Owned source writer missing");output.write(png); }
            ContentValues ready=new ContentValues();ready.put(MediaStore.Images.Media.IS_PENDING,0);if(getContentResolver().update(uri,ready,null,null)!=1)throw new IOException("Owned source publication failed");
        } else {
            File parent=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),album);
            if(parent.exists()||!parent.mkdirs())throw new IOException("Unique owned album must be new");File photo=new File(parent,name);
            record.put("legacyPath",photo.getCanonicalPath());atomic(ledger,record);try(FileOutputStream output=new FileOutputStream(photo)){output.write(png);}
            CountDownLatch done=new CountDownLatch(1);AtomicReference<Uri> scanned=new AtomicReference<>();
            MediaScannerConnection.scanFile(getApplicationContext(),new String[]{photo.getCanonicalPath()},new String[]{"image/png"},(path,value)->{scanned.set(value);done.countDown();});
            if(!done.await(15,TimeUnit.SECONDS)||scanned.get()==null)throw new IOException("Owned scan has not confirmed a URI; do not replay");uri=scanned.get();record.put("uri",uri.toString());atomic(ledger,record);
        }
        verifyPhoto(record);record.put("status","prepared");atomic(ledger,record);return record;
    }
    private Uri originalUri(JSONObject record) throws Exception {
        String nonce=record.getString("nonce");if(!nonce.matches("[a-f0-9]{32}")||!record.getString("album").equals("AgentHub_"+nonce)||!record.getString("fileName").equals("photo-"+nonce+".png"))throw new IOException("Owned source identity changed");
        Uri uri=Uri.parse(record.getString("uri"));if(!"content".equals(uri.getScheme())||!"media".equals(uri.getAuthority())||!uri.getPath().matches("/(external|external_primary)/images/media/[0-9]+"))throw new IOException("Exact owned media URI required");return uri;
    }
    private void verifyPhoto(JSONObject record) throws Exception {
        Uri uri=originalUri(record);String[] columns=Build.VERSION.SDK_INT>=29?new String[]{MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.RELATIVE_PATH,MediaStore.Images.Media.OWNER_PACKAGE_NAME}:new String[]{MediaStore.Images.Media.DISPLAY_NAME,MediaStore.Images.Media.DATA};
        try(android.database.Cursor cursor=getContentResolver().query(uri,columns,null,null,null)) {
            if(cursor==null||!cursor.moveToFirst()||!record.getString("fileName").equals(cursor.getString(0)))throw new IOException("Owned photo name changed");
            if(Build.VERSION.SDK_INT>=29) { if(!("Pictures/"+record.getString("album")+"/").equals(cursor.getString(1))||!getPackageName().equals(cursor.getString(2)))throw new IOException("Owned source scope changed"); }
            else if(!record.getString("legacyPath").equals(cursor.getString(1)))throw new IOException("Owned source path changed");
        }
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream input=getContentResolver().openInputStream(uri)) { if(input==null)throw new IOException("Owned source unreadable");byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){if(bytes.size()+count>2*1024*1024)throw new IOException("Owned synthetic source changed size");bytes.write(buffer,0,count);} }
        if(!record.getString("sourcePngSha256").equals(hash(bytes.toByteArray())))throw new IOException("Owned source bytes changed");
    }
    private void cleanup(JSONObject record) throws Exception {
        if(record.optString("status").equals("consumed"))return;
        if(record.has("uri")) { verifyPhoto(record);if(getContentResolver().delete(originalUri(record),null,null)!=1)throw new IOException("Owned image removal unconfirmed"); }
        else if(Build.VERSION.SDK_INT>=29) {
            String where=MediaStore.Images.Media.DISPLAY_NAME+"=? AND "+MediaStore.Images.Media.RELATIVE_PATH+"=? AND "+MediaStore.Images.Media.OWNER_PACKAGE_NAME+"=?";
            getContentResolver().delete(collection(),where,new String[]{record.getString("fileName"),"Pictures/"+record.getString("album")+"/",getPackageName()});
        }
        if(Build.VERSION.SDK_INT<29&&record.has("legacyPath")) {
            String nonce=record.getString("nonce");File parent=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"AgentHub_"+nonce),photo=new File(parent,"photo-"+nonce+".png");
            if(!photo.getCanonicalPath().equals(record.getString("legacyPath")))throw new IOException("Original owned path mismatch");
            if(photo.exists()) { if(!record.getString("sourcePngSha256").equals(hash(read(photo))))throw new IOException("Unknown changed source preserved");if(!photo.delete())throw new IOException("Owned source file removal failed"); }
            getContentResolver().delete(collection(),MediaStore.Images.Media.DATA+"=?",new String[]{photo.getCanonicalPath()});
            if(parent.isDirectory()&&parent.list()!=null&&parent.list().length==0&&!parent.delete())throw new IOException("Owned empty album removal failed");
        }
    }
}
