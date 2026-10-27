package com.agenthub.android;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test-APK-only provider. Java/framework APIs also work outside the instrumented target classloader. */
public final class OwnedAcceptanceDocuments extends DocumentsProvider {
    @Override public boolean onCreate() { return true; }
    private List<JSONObject> entries() throws FileNotFoundException {
        try (InputStream in = new FileInputStream(new File(getContext().getFilesDir(), "owned-documents.json"))) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) bytes.write(buffer, 0, n);
            JSONArray array = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getJSONArray("cases");
            List<JSONObject> result = new ArrayList<>(); for (int i=0;i<array.length();i++) result.add(array.getJSONObject(i)); return result;
        } catch (Exception e) { throw new FileNotFoundException("Owned synthetic fixture unavailable: " + e.getClass().getSimpleName()); }
    }
    private JSONObject document(String id) throws FileNotFoundException {
        for (JSONObject row : entries()) if (id.equals(row.optString("fileName"))) return row;
        throw new FileNotFoundException("Unknown owned synthetic document");
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection == null ? new String[]{Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES,Root.COLUMN_AVAILABLE_BYTES} : projection;
        Map<String,Object> values = new HashMap<>(); values.put(Root.COLUMN_ROOT_ID,"owned"); values.put(Root.COLUMN_DOCUMENT_ID,"owned-root"); values.put(Root.COLUMN_TITLE,"Agent Hub acceptance files"); values.put(Root.COLUMN_FLAGS,Root.FLAG_SUPPORTS_IS_CHILD); values.put(Root.COLUMN_MIME_TYPES,"text/plain"); values.put(Root.COLUMN_AVAILABLE_BYTES,0L);
        MatrixCursor cursor = new MatrixCursor(columns); MatrixCursor.RowBuilder row = cursor.newRow(); for(String column:columns)row.add(values.get(column)); return cursor;
    }
    private Cursor cursor(List<String> ids,String[] projection) throws FileNotFoundException {
        String[] columns = projection == null ? new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        for(String id:ids) { boolean root = id.equals("owned-root"); JSONObject value = root ? null : document(id);
            Map<String,Object> values = new HashMap<>(); values.put(Document.COLUMN_DOCUMENT_ID,id); values.put(Document.COLUMN_DISPLAY_NAME,root ? "Agent Hub acceptance files" : id); values.put(Document.COLUMN_MIME_TYPE,root ? Document.MIME_TYPE_DIR : "text/plain"); values.put(Document.COLUMN_FLAGS,0); values.put(Document.COLUMN_SIZE,root ? 0 : value.optString("fileText").getBytes(StandardCharsets.UTF_8).length);
            MatrixCursor.RowBuilder row = cursor.newRow(); for(String column:columns)row.add(values.get(column));
        } return cursor;
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException { return cursor(Collections.singletonList(id),projection); }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String order) throws FileNotFoundException {
        if(!parent.equals("owned-root"))throw new FileNotFoundException("Unknown root"); List<String> ids = new ArrayList<>(); for(JSONObject row:entries())ids.add(row.optString("fileName")); return cursor(ids,projection);
    }
    @Override public boolean isChildDocument(String parent,String id) { try { document(id);return parent.equals("owned-root"); } catch(FileNotFoundException e){return false;} }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        if(!mode.equals("r"))throw new FileNotFoundException("Read only"); JSONObject row=document(id);
        File gate=new File(getContext().getFilesDir(),"owned-read-gate.json");
        if(gate.isFile()) {
            try {
                JSONObject held=new JSONObject(new String(java.nio.file.Files.readAllBytes(gate.toPath()),StandardCharsets.UTF_8));
                if(id.equals(held.getString("fileName"))) {
                    java.nio.file.Files.write(new File(getContext().getFilesDir(),"owned-read-started").toPath(),id.getBytes(StandardCharsets.UTF_8));
                    File release=new File(getContext().getFilesDir(),"owned-read-release");long deadline=System.currentTimeMillis()+20000;
                    boolean released=false;
                    while(!released&&System.currentTimeMillis()<deadline) {
                        if(release.isFile())released=held.getString("nonce").equals(new String(java.nio.file.Files.readAllBytes(release.toPath()),StandardCharsets.UTF_8));
                        if(!released)Thread.sleep(25);
                    }
                    if(!released)throw new IOException("Owned read gate timed out");
                    java.nio.file.Files.write(new File(getContext().getFilesDir(),"owned-read-finished").toPath(),id.getBytes(StandardCharsets.UTF_8));
                }
            }catch(Exception failure){
                try { java.nio.file.Files.write(new File(getContext().getFilesDir(),"owned-read-failure").toPath(),(failure.getClass().getSimpleName()+":"+failure.getMessage()).getBytes(StandardCharsets.UTF_8)); }catch(IOException ignored) { }
                throw new FileNotFoundException("Owned synthetic read gate failed");
            }
        }
        File file=new File(getContext().getCacheDir(),id); if(!file.getParentFile().equals(getContext().getCacheDir()))throw new FileNotFoundException("Invalid name");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(row.optString("fileText").getBytes(StandardCharsets.UTF_8));}catch(IOException e){throw new FileNotFoundException("Cannot prepare owned bytes");}
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
    }
}
