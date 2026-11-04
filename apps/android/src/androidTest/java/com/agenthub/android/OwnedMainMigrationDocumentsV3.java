package com.agenthub.android;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.system.Os;
import android.system.OsConstants;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;

/** Framework Test-UID P7 v4 synthetic provider. Exact UUID IDs; never opens legacy v1.
 * Only fully published scopes are listed. Listing never reads the binary payload. */
public final class OwnedMainMigrationDocumentsV3 extends DocumentsProvider {
    private static final String[] ROOT_COLUMNS={Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES,Root.COLUMN_AVAILABLE_BYTES};
    private static final String[] DOCUMENT_COLUMNS={Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE};
    @Override public boolean onCreate(){return true;}
    private void self() throws Exception {P7MainSafLeaseV3.component(getContext(),false);P7MainSafLeaseV3.facts(P7MainSafLeaseV3.TEST+":p7MainSafV3");}
    private static FileNotFoundException denied(Exception e){return new FileNotFoundException("P7V4_DOCUMENT_REJECTED_"+e.getClass().getSimpleName());}
    private void queryOwner(P7MainSafLeaseV3.Scope s) throws Exception {
        if(Binder.getCallingUid()!=s.docsUid&&Binder.getCallingUid()!=getContext().getApplicationInfo().uid)throw P7MainSafLeaseV3.reject("LIST_CALLER");
    }
    private void owner(P7MainSafLeaseV3.Scope s,String id,int grant) throws Exception {
        int uid=Binder.getCallingUid();if(uid==s.docsUid||uid==getContext().getApplicationInfo().uid)return;
        if(uid!=s.appUid||!id.equals(s.id)||getContext().checkUriPermission(DocumentsContract.buildDocumentUri(P7MainSafLeaseV3.AUTHORITY,id),
            Binder.getCallingPid(),uid,grant)!=PackageManager.PERMISSION_GRANTED)throw P7MainSafLeaseV3.reject("GRANT_CALLER");
    }
    private MatrixCursor cursor(String[] projection,String[] defaults){return new MatrixCursor(projection==null?defaults:projection);}
    private void row(MatrixCursor cursor,Map<String,Object> values) {
        MatrixCursor.RowBuilder out=cursor.newRow();for(String name:cursor.getColumnNames())out.add(values.get(name));
    }
    @Override public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        try {
            self();String boot=P7MainSafLeaseV3.facts(P7MainSafLeaseV3.TEST+":p7MainSafV3").getString("bootUuid");
            MatrixCursor out=cursor(projection,ROOT_COLUMNS);File parent=new File(getContext().getFilesDir(),P7MainSafLeaseV3.NAMESPACE);
            if(!Files.exists(parent.toPath(),LinkOption.NOFOLLOW_LINKS))return out;
            for(String run:P7MainSafLeaseV3.runs(parent)) {
                File folder=new File(parent,run);
                // Incomplete history is preserved and hidden, never adopted as a new lease.
                if(!P7MainSafLeaseV3.publishedCandidate(folder))continue;
                JSONObject receipt=P7MainSafLeaseV3.json(folder,"receipt.json");if(!"PREPARED".equals(receipt.optString("status")))continue;
                P7MainSafLeaseV3.Scope s=new P7MainSafLeaseV3.Scope(getContext(),run,null,true);queryOwner(s);
                if(!s.intent.getString("bootUuid").equals(boot))continue;
                if(s.kind.equals("IMPORT"))s.payloadAck(); // historical metadata remains readable only by exact controller URI
                Map<String,Object> v=new HashMap<>();v.put(Root.COLUMN_ROOT_ID,s.root);v.put(Root.COLUMN_DOCUMENT_ID,s.root);v.put(Root.COLUMN_TITLE,s.title);
                v.put(Root.COLUMN_FLAGS,Root.FLAG_SUPPORTS_IS_CHILD|(s.kind.equals("EXPORT")?Root.FLAG_SUPPORTS_CREATE:0));v.put(Root.COLUMN_MIME_TYPES,"application/octet-stream");v.put(Root.COLUMN_AVAILABLE_BYTES,(long)P7MainSafLeaseV3.MAX);row(out,v);
            }
            return out;
        }catch(Exception e){throw denied(e);}
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        try {
            self();P7MainSafLeaseV3.Scope s=P7MainSafLeaseV3.documentScope(getContext(),id);boolean root=id.equals(s.root);
            owner(s,id,s.kind.equals("IMPORT")?Intent.FLAG_GRANT_READ_URI_PERMISSION:Intent.FLAG_GRANT_WRITE_URI_PERMISSION);long size=0;
            if(!root&&Files.exists(s.payload().toPath(),LinkOption.NOFOLLOW_LINKS)) {
                android.system.StructStat st=Os.lstat(s.payload().getPath());P7MainSafLeaseV3.regular(st);
                if(st.st_size<0||st.st_size>P7MainSafLeaseV3.MAX)throw P7MainSafLeaseV3.reject("BOUND");size=st.st_size;
            } else if(!root&&s.kind.equals("IMPORT"))throw P7MainSafLeaseV3.reject("IMPORT_NOT_COMMITTED");
            Map<String,Object> v=new HashMap<>();v.put(Document.COLUMN_DOCUMENT_ID,id);v.put(Document.COLUMN_DISPLAY_NAME,root?s.title:s.filename);
            v.put(Document.COLUMN_MIME_TYPE,root?Document.MIME_TYPE_DIR:"application/octet-stream");v.put(Document.COLUMN_FLAGS,s.kind.equals("EXPORT")?(root?Document.FLAG_DIR_SUPPORTS_CREATE:Document.FLAG_SUPPORTS_WRITE):0);v.put(Document.COLUMN_SIZE,size);
            MatrixCursor out=cursor(projection,DOCUMENT_COLUMNS);row(out,v);return out;
        }catch(Exception e){throw denied(e);}
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String sort) throws FileNotFoundException {
        try {
            self();P7MainSafLeaseV3.Scope s=P7MainSafLeaseV3.documentScope(getContext(),parent);queryOwner(s);
            if(!parent.equals(s.root))throw P7MainSafLeaseV3.reject("PARENT");
            if(Files.exists(s.payload().toPath(),LinkOption.NOFOLLOW_LINKS)||Files.exists(new File(s.folder,"create.json").toPath(),LinkOption.NOFOLLOW_LINKS))return queryDocument(s.id,projection);
            return cursor(projection,DOCUMENT_COLUMNS);
        }catch(Exception e){throw denied(e);}
    }
    @Override public boolean isChildDocument(String parent,String child) {
        try {self();P7MainSafLeaseV3.Scope s=P7MainSafLeaseV3.documentScope(getContext(),parent);owner(s,child,s.kind.equals("IMPORT")?Intent.FLAG_GRANT_READ_URI_PERMISSION:Intent.FLAG_GRANT_WRITE_URI_PERMISSION);return parent.equals(s.root)&&child.equals(s.id);}
        catch(Exception e){return false;}
    }
    @Override public String createDocument(String parent,String mime,String name) throws FileNotFoundException {
        try {
            self();P7MainSafLeaseV3.Scope s=P7MainSafLeaseV3.documentScope(getContext(),parent);P7MainSafLeaseV3.validate(getContext(),s.intent,true,false);
            if(!s.kind.equals("EXPORT")||Binder.getCallingUid()!=s.docsUid||!parent.equals(s.root)||!mime.equals("application/octet-stream")||!name.equals(s.filename))throw P7MainSafLeaseV3.reject("CREATE");
            P7MainSafLeaseV3.ownerWitness(s.folder,false);
            s.event("create",new JSONObject().put("callerUid",Binder.getCallingUid()).put("callerPid",Binder.getCallingPid()).put("displayName",name).put("mimeType",mime));
            // No writable file exists yet; openDocument returns a bounded pipe receiver.
            return s.id;
        }catch(Exception e){throw denied(e);}
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        try {
            self();final P7MainSafLeaseV3.Scope s=P7MainSafLeaseV3.documentScope(getContext(),id);P7MainSafLeaseV3.validate(getContext(),s.intent,true,false);
            if(Binder.getCallingUid()!=s.appUid||!id.equals(s.id))throw P7MainSafLeaseV3.reject("OPEN");
            if(signal!=null)signal.throwIfCanceled();
            if(s.kind.equals("EXPORT")) {
                if(!mode.equals("wt"))throw P7MainSafLeaseV3.reject("EXPORT_MODE");owner(s,id,Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if(!Files.exists(new File(s.folder,"create.json").toPath(),LinkOption.NOFOLLOW_LINKS))throw P7MainSafLeaseV3.reject("CREATE_REQUIRED");
                s.event("open",new JSONObject().put("callerUid",Binder.getCallingUid()).put("callerPid",Binder.getCallingPid()).put("mode",mode));
                return P7MainSafBoundedFd.receive(s);
            }
            if(!mode.equals("r"))throw P7MainSafLeaseV3.reject("IMPORT_MODE");owner(s,id,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            JSONObject ack=s.payloadAck();
            // Compare immutable staged bytes before exposing the one read-only consumer FD.
            byte[] bytes=P7MainSafLeaseV3.read(s.payload(),P7MainSafLeaseV3.MAX);
            try {if(bytes.length!=ack.getLong("size")||!P7MainSafLeaseV3.hash(bytes).equals(ack.getString("sha256")))throw P7MainSafLeaseV3.reject("IMPORT_SNAPSHOT");}
            finally {Arrays.fill(bytes,(byte)0);}
            P7MainSafLeaseV3.ownerWitness(s.folder,false);
            s.event("read-open",new JSONObject().put("callerUid",Binder.getCallingUid()).put("callerPid",Binder.getCallingPid()).put("mode",mode));
            ParcelFileDescriptor result=ParcelFileDescriptor.open(s.payload(),ParcelFileDescriptor.MODE_READ_ONLY,new Handler(Looper.getMainLooper()),error->{
                try {s.event("read-close",new JSONObject().put("success",error==null).put("failureClass",error==null?"None":error.getClass().getSimpleName())
                    .put("fdCloseOnly",true).put("fullPayloadReadProven",false));}catch(Exception ignored){}
            });
            try {P7MainSafLeaseV3.regular(Os.fstat(result.getFileDescriptor()));P7MainSafLeaseV3.same(Os.fstat(result.getFileDescriptor()),Os.lstat(s.payload().getPath()));
                if(Os.fstat(result.getFileDescriptor()).st_size!=ack.getLong("size"))throw P7MainSafLeaseV3.reject("IMPORT_SNAPSHOT");}
            catch(Exception changed){result.closeWithError("P7V4_IMPORT_OPEN_IDENTITY");throw changed;}
            return result;
        }catch(Exception e){throw denied(e);}
    }
}
