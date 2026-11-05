package com.agenthub.android;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;

/** Framework-created, androidTest Test-UID controller; never a standalone app_process.
 * Signature-level DUMP is additionally enforced by the manifest. This does not expose shell,
 * arbitrary IPC, arbitrary paths, payload encodings, production state or a generic method API. */
public final class OwnedMainMigrationControlV4 extends ContentProvider {
    private final Object preparationLock=new Object();
    @Override public boolean onCreate(){return true;}
    private void shell() throws Exception {
        if(Binder.getCallingUid()!=Process.SHELL_UID||Binder.getCallingUid()/100000!=0)throw new SecurityException("P7V4_SHELL_USER0");
        getContext().enforceCallingPermission("android.permission.DUMP","P7V4_DUMP");
        P7MainSafLeaseV3.component(getContext(),true);
        P7MainSafLeaseV3.facts(P7MainSafLeaseV3.TEST+":p7MainSafControlV4");
    }
    @Override public Bundle call(String method,String argument,Bundle extras) {
        try {
            shell();
            if(!Arrays.asList("prepare-export","prepare-import").contains(method))throw P7MainSafLeaseV3.reject("METHOD");
            JSONObject intent=P7MainSafLeaseV3.fromBundle(argument,extras);
            if(!method.equals("prepare-"+intent.getString("kind").toLowerCase(Locale.ROOT)))throw P7MainSafLeaseV3.reject("KIND");
            P7MainSafLeaseV3.validate(getContext(),intent,true,true);
            synchronized(preparationLock){prepare(intent);}
            return new Bundle(); // content call text is NEVER parsed or treated as an ACK.
        } catch(Exception failure) {throw new IllegalStateException("P7V4_REJECTED_"+P7MainSafLeaseV3.rejectionCode(failure));}
    }
    private void prepare(JSONObject intent) throws Exception {
        File parent=P7MainSafLeaseV3.parent(getContext(),true);
        List<String> previous=P7MainSafLeaseV3.runs(parent);
        if(previous.size()>=P7MainSafLeaseV3.MAX_RUNS)throw P7MainSafLeaseV3.reject("RUN_BOUND");
        String run=intent.getString("run"),operation=intent.getString("operationId");
        File folder=new File(parent,run);
        // mkdir is exclusive. Existing runs, even the same operation/intent, are never reused.
        P7MainSafLeaseV3.mkdirNew(folder);
        // Created once in the exclusive new v4 run; never replaced, removed or recreated.
        P7MainSafLeaseV3.createPublicationLock(folder);
        P7MainSafLeaseV3.writeNew(new File(folder,"scope.claimed"),new byte[0]);
        byte[] canonical=P7MainSafLeaseV3.canonical(intent);
        P7MainSafLeaseV3.atomicBytes(folder,"intent",canonical);
        try {
            P7MainSafLeaseV3.ownerWitness(folder,true);
            JSONObject scope=new JSONObject().put("schema",1).put("run",run).put("kind",intent.getString("kind"))
                .put("appUid",intent.getInt("appUid")).put("docsUid",intent.getInt("docsUid"));
            P7MainSafLeaseV3.atomicJson(folder,"scope",scope);
            JSONObject receipt=base(intent).put("status","PREPARED").put("scopePublished",true)
                .put("directorySynced",true).put("payloadAccepted",false).put("payloadFdClosed",true);
            // Last no-clobber publication is the immutable ACK, after scope fsync/close.
            P7MainSafLeaseV3.atomicJson(folder,"receipt",receipt);
        } catch(Exception failure) {
            // A partial/failing run is retained. No repair, overwrite, deletion or second copy.
            // If publication was already attempted, keep the unknown pending state untouched.
            if(!Files.exists(new File(folder,"receipt.json").toPath(),LinkOption.NOFOLLOW_LINKS)&&
                !Files.exists(new File(folder,"receipt.pending").toPath(),LinkOption.NOFOLLOW_LINKS)) {
                try {P7MainSafLeaseV3.atomicJson(folder,"receipt",base(intent).put("status","FAILED")
                    .put("scopePublished",false).put("directorySynced",false).put("failureCategory","PREPARATION_BOUNDARY")
                    .put("failureClass",P7MainSafLeaseV3.failureClass(failure)));}catch(Exception ignored){}
            }
            throw failure;
        }
    }
    private JSONObject base(JSONObject intent) throws Exception {
        return new JSONObject().put("schema","p7-main-saf-controller-receipt-v4").put("run",intent.getString("run"))
            .put("operationId",intent.getString("operationId")).put("kind",intent.getString("kind"))
            .put("intentSha256",P7MainSafLeaseV3.hash(P7MainSafLeaseV3.canonical(intent)))
            .put("controllerProcess",P7MainSafLeaseV3.facts(P7MainSafLeaseV3.TEST+":p7MainSafControlV4"));
    }
    private P7MainSafLeaseV3.Scope target(Uri uri) throws Exception {
        if(!"content".equals(uri.getScheme())||!P7MainSafLeaseV3.CONTROL.equals(uri.getAuthority())||uri.getQuery()!=null||uri.getFragment()!=null)throw P7MainSafLeaseV3.reject("URI");
        List<String> p=uri.getPathSegments();
        if(p.size()!=5||!p.get(0).equals("v4")||!Arrays.asList("EXPORT","IMPORT").contains(p.get(3))||
            !Arrays.asList("read-original-receipt","read-payload-ack","receive-input","audit").contains(p.get(4)))throw P7MainSafLeaseV3.reject("URI");
        String run=P7MainSafLeaseV3.uuid(p.get(1)),operation=P7MainSafLeaseV3.uuid(p.get(2));
        Uri exact=new Uri.Builder().scheme("content").authority(P7MainSafLeaseV3.CONTROL).appendPath("v4")
            .appendPath(run).appendPath(operation).appendPath(p.get(3)).appendPath(p.get(4)).build();
        if(!exact.toString().equals(uri.toString()))throw P7MainSafLeaseV3.reject("URI_ALIAS");
        P7MainSafLeaseV3.Scope scope=new P7MainSafLeaseV3.Scope(getContext(),run,operation,false);
        if(!scope.kind.equals(p.get(3)))throw P7MainSafLeaseV3.reject("KIND");return scope;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
        try {
            shell();P7MainSafLeaseV3.Scope s=target(uri);
            if(uri.getLastPathSegment().equals("receive-input")) {
                if(!mode.equals("w")||!s.kind.equals("IMPORT"))throw P7MainSafLeaseV3.reject("INPUT_MODE");
                // The exact shell caller is checked on every open, not delegated to URI grants.
                new P7MainSafLeaseV3.Scope(getContext(),s.run,s.operation,true);
                return P7MainSafBoundedFd.receive(s);
            }
            if(!mode.equals("r"))throw P7MainSafLeaseV3.reject("MODE");
            if(uri.getLastPathSegment().equals("read-payload-ack")) {
                if(Files.exists(new File(s.folder,"payload-ack.json").toPath(),LinkOption.NOFOLLOW_LINKS)) {
                    s.payloadAck();return P7MainSafLeaseV3.immutableFd(new File(s.folder,"payload-ack.json"));
                }
                if(Files.exists(new File(s.folder,"payload-failed.json").toPath(),LinkOption.NOFOLLOW_LINKS))
                    return P7MainSafLeaseV3.immutableFd(new File(s.folder,"payload-failed.json"));
                JSONObject pending=base(s.intent).put("schema","p7-main-saf-ack-observation-v4").put("status","PENDING");
                return P7MainSafBoundedFd.metadata(pending.toString().getBytes(StandardCharsets.UTF_8));
            }
            if(uri.getLastPathSegment().equals("read-original-receipt")) {
                JSONObject receipt=P7MainSafLeaseV3.json(s.folder,"receipt.json");
                if(!receipt.getString("run").equals(s.run)||!receipt.getString("operationId").equals(s.operation)||
                    !receipt.getString("intentSha256").equals(P7MainSafLeaseV3.hash(P7MainSafLeaseV3.canonical(s.intent))))throw P7MainSafLeaseV3.reject("RECEIPT");
                return P7MainSafLeaseV3.immutableFd(new File(s.folder,"receipt.json"));
            }
            byte[] bytes=audit(s).toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>P7MainSafLeaseV3.JSON_MAX)throw P7MainSafLeaseV3.reject("AUDIT_BOUND");
            return P7MainSafBoundedFd.metadata(bytes);
        }catch(Exception failure){throw new FileNotFoundException("P7V4_READ_REJECTED_"+P7MainSafLeaseV3.rejectionCode(failure));}
    }
    private JSONObject audit(P7MainSafLeaseV3.Scope s) throws Exception {
        try(P7MainSafLeaseV3.Publication held=P7MainSafLeaseV3.publication(s.folder)) {return auditHeld(s,held);}
    }
    private JSONObject auditHeld(P7MainSafLeaseV3.Scope s,P7MainSafLeaseV3.Publication held) throws Exception {
        held.check();
        Set<String> before=P7MainSafLeaseV3.inventory(s.folder);JSONObject files=new JSONObject(),documents=new JSONObject();
        for(String name:before) {
            // Opening/closing another FD for this lock inode could release our process's
            // POSIX lock. The held guard checks its exact regular empty inode instead.
            byte[] bytes=name.equals(P7MainSafLeaseV3.PUBLICATION_LOCK)?new byte[0]:P7MainSafLeaseV3.read(new File(s.folder,name),P7MainSafLeaseV3.payloadName(name)?P7MainSafLeaseV3.MAX:P7MainSafLeaseV3.JSON_MAX);
            files.put(name,new JSONObject().put("bytes",bytes.length).put("sha256",P7MainSafLeaseV3.hash(bytes)));
            if(name.endsWith(".json"))documents.put(name,new JSONObject(new String(bytes,StandardCharsets.UTF_8)));
            if(name.endsWith(".claimed")&&bytes.length!=0)throw P7MainSafLeaseV3.reject("CLAIM");Arrays.fill(bytes,(byte)0);
        }
        held.check();if(!before.equals(P7MainSafLeaseV3.inventory(s.folder)))throw P7MainSafLeaseV3.reject("AUDIT_CHANGED");
        Set<String> complete=new HashSet<>(Arrays.asList(P7MainSafLeaseV3.PUBLICATION_LOCK,"controller-owner.json","scope.claimed","intent.json","scope.json","receipt.json","payload.claimed",
            s.kind.equals("IMPORT")?"input.bin":"output.bin","payload-ack.claimed","payload-ack.json"));
        if(s.kind.equals("EXPORT"))complete.addAll(Arrays.asList("document-owner.json","create.claimed","create.json","open.claimed","open.json"));
        boolean done=before.equals(complete);
        if(done) {
            new P7MainSafLeaseV3.Scope(getContext(),s.run,s.operation,true);
            JSONObject ack=s.payloadAck(),payload=files.getJSONObject(s.payload().getName());
            done=ack.getLong("size")==payload.getLong("bytes")&&ack.getString("sha256").equals(payload.getString("sha256"));
        }
        String status=done?"STAGING_COMMITTED":"PARTIAL";
        if(s.kind.equals("IMPORT")) {
            complete.addAll(Arrays.asList("document-owner.json","read-open.claimed","read-open.json","read-close.claimed","read-close.json"));
            if(before.equals(complete)) {
                JSONObject ack=s.payloadAck(),payload=files.getJSONObject("input.bin"),close=documents.getJSONObject("read-close.json");
                if(ack.getLong("size")==payload.getLong("bytes")&&ack.getString("sha256").equals(payload.getString("sha256"))&&
                    close.getBoolean("success")&&close.getBoolean("fdCloseOnly")&&!close.getBoolean("fullPayloadReadProven"))status="CONSUMER_FD_CLOSED";
            }
        }
        // Consumer FD close is separate from actual full read, decrypt and business result.
        held.check();return base(s.intent).put("schema","p7-main-saf-controller-audit-v4").put("status",status)
            .put("files",files).put("documents",documents).put("auditReadFdsClosed",true);
    }
    @Override public String getType(Uri uri){try {shell();target(uri);return "application/json";}catch(Exception e){throw new SecurityException("P7V4_TYPE_REJECTED");}}
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String o){throw new SecurityException("P7V4_QUERY_DISABLED");}
    @Override public Uri insert(Uri u,ContentValues v){throw new SecurityException("P7V4_INSERT_DISABLED");}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new SecurityException("P7V4_UPDATE_DISABLED");}
    @Override public int delete(Uri u,String s,String[] a){throw new SecurityException("P7V4_DELETE_DISABLED");}
}
