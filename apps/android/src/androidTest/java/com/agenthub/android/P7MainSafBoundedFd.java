package com.agenthub.android;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Test-only v4 receiver. The client gets a pipe, never the staging file's writable FD.
 * The worker writes at most hardLimit bytes to disk; a single extra byte is inspected only
 * to reject overflow. A fixed kernel pipe buffer may additionally exist. No disk write
 * reaches the pending file after the limit. This is a byte bound, not hard time cancellation.
 * Strong owners and partial files remain until process exit; no retry/resume/clear API. */
final class P7MainSafBoundedFd {
    private static final Map<String,Transfer> RETAINED=new ConcurrentHashMap<>();
    private P7MainSafBoundedFd() {}
    static ParcelFileDescriptor receive(P7MainSafLeaseV3.Scope scope) throws Exception {
        P7MainSafLeaseV3.validate(scope.context,scope.intent,true,false);
        P7MainSafLeaseV3.writeNew(new File(scope.folder,"payload.claimed"),new byte[0]);
        Transfer owner=new Transfer(scope);
        if(RETAINED.putIfAbsent(scope.run,owner)!=null)throw P7MainSafLeaseV3.reject("FD_OWNER_EXISTS");
        try {
            owner.sink=Os.open(scope.pendingPayload().getPath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_NOFOLLOW,0600);
            P7MainSafLeaseV3.regular(Os.fstat(owner.sink));P7MainSafLeaseV3.same(Os.fstat(owner.sink),Os.lstat(scope.pendingPayload().getPath()));
            owner.pipe=ParcelFileDescriptor.createReliablePipe();
            int flags=Os.fcntlInt(owner.pipe[0].getFileDescriptor(),OsConstants.F_GETFL,0);
            Os.fcntlInt(owner.pipe[0].getFileDescriptor(),OsConstants.F_SETFL,flags|OsConstants.O_NONBLOCK);
            owner.worker=new Thread(owner,"P7V4BoundedReceiver");
            owner.worker.start();owner.started=true;
            // Binder return-value parceling owns the outgoing endpoint. Keeping its object
            // here is not a claim it remains open after framework transfer.
            return owner.pipe[1];
        }catch(Exception|Error failure) {
            owner.failureClass=failure.getClass().getSimpleName();
            if(!owner.started)owner.closeBeforeStart();
            throw failure;
        }
    }
    private static final List<Object> METADATA_OWNERS=Collections.synchronizedList(new ArrayList<>());
    private static int metadataIssued;
    static ParcelFileDescriptor metadata(byte[] bytes) throws Exception {
        if(bytes.length==0||bytes.length>P7MainSafLeaseV3.JSON_MAX)throw P7MainSafLeaseV3.reject("METADATA_BOUND");
        final ParcelFileDescriptor[] pipe;
        synchronized(METADATA_OWNERS) {
            if(metadataIssued>=32)throw P7MainSafLeaseV3.reject("METADATA_READ_BOUND");metadataIssued++;
            pipe=ParcelFileDescriptor.createReliablePipe();METADATA_OWNERS.add(pipe);
        }
        try {
            int flags=Os.fcntlInt(pipe[1].getFileDescriptor(),OsConstants.F_GETFL,0);
            Os.fcntlInt(pipe[1].getFileDescriptor(),OsConstants.F_SETFL,flags|OsConstants.O_NONBLOCK);
            Thread worker=new Thread(()->{
                boolean closeAttempted=false;long end=SystemClock.elapsedRealtime()+5000L;int offset=0;
                try {
                    StructPollfd p=new StructPollfd();p.fd=pipe[1].getFileDescriptor();p.events=(short)OsConstants.POLLOUT;
                    while(offset<bytes.length) {
                        if(SystemClock.elapsedRealtime()>=end)throw new IOException("P7V4_METADATA_DEADLINE");
                        try {
                            int ready=Os.poll(new StructPollfd[]{p},100);if(ready==0)continue;
                            if((p.revents&(OsConstants.POLLERR|OsConstants.POLLHUP|OsConstants.POLLNVAL))!=0)throw new IOException("P7V4_METADATA_PEER");
                            offset+=Os.write(p.fd,bytes,offset,bytes.length-offset);
                        }catch(ErrnoException e){if(e.errno==OsConstants.EINTR||e.errno==OsConstants.EAGAIN)continue;throw e;}
                    }
                    closeAttempted=true;pipe[1].close();
                }catch(Exception failure) {if(!closeAttempted)try{closeAttempted=true;pipe[1].closeWithError("P7V4_METADATA_UNKNOWN");}catch(Exception ignored){}}
            },"P7V4BoundedMetadata");
            METADATA_OWNERS.add(worker);worker.start();return pipe[0];
        }catch(Exception|Error failure) {
            try{pipe[0].close();}catch(Exception ignored){}try{pipe[1].closeWithError("P7V4_METADATA_START");}catch(Exception ignored){}throw failure;
        }
    }
    private static final class Transfer implements Runnable {
        final P7MainSafLeaseV3.Scope scope;
        final long deadline=SystemClock.elapsedRealtime()+60000L;
        FileDescriptor sink;ParcelFileDescriptor[] pipe;Thread worker;
        boolean started,readCloseAttempted,sinkCloseAttempted,readClosed,sinkClosed,eof,fileSynced,directorySynced,published;
        long size;String failureClass,sha256;
        Transfer(P7MainSafLeaseV3.Scope s){scope=s;}
        void budget() throws Exception {if(SystemClock.elapsedRealtime()>=deadline)throw P7MainSafLeaseV3.reject("FD_DEADLINE");}
        void closeRead(boolean error) throws IOException {
            if(readCloseAttempted||pipe==null)return;readCloseAttempted=true;
            if(error)pipe[0].closeWithError("P7V4_RECEIVE_REJECTED");else pipe[0].close();readClosed=true;
        }
        void closeSink() throws Exception {
            if(sinkCloseAttempted||sink==null)return;sinkCloseAttempted=true;Os.close(sink);sinkClosed=true;
        }
        void closeBeforeStart() {
            try{closeRead(true);}catch(Exception ignored){}
            if(pipe!=null)try{pipe[1].closeWithError("P7V4_START_REJECTED");}catch(Exception ignored){}
            try{closeSink();}catch(Exception ignored){}
            failed("START_BOUNDARY");
        }
        void failed(String category) {
            try {scope.event("payload-failed",new JSONObject().put("status","FAILED").put("failureCategory",category)
                .put("failureClass",failureClass==null?"Unknown":failureClass).put("confirmedWrittenBytes",size)
                .put("eofObserved",eof).put("receiverFdClosed",readClosed).put("sinkFdClosed",sinkClosed)
                .put("fileSynced",fileSynced).put("directorySynced",directorySynced).put("payloadPublished",published));}
            catch(Exception ignored){} // Existing claim/pending state is retained UNKNOWN, never repaired.
        }
        @Override public void run() {
            byte[] block=new byte[65536];boolean committed=false;
            try {
                long expected=scope.intent.getLong("expectedPayloadBytes");int limit=expected>0?(int)expected:P7MainSafLeaseV3.MAX;
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                StructPollfd poll=new StructPollfd();poll.fd=pipe[0].getFileDescriptor();
                poll.events=(short)(OsConstants.POLLIN|OsConstants.POLLHUP|OsConstants.POLLERR);
                while(true) {
                    budget();int n;
                    try {
                        int ready=Os.poll(new StructPollfd[]{poll},(int)Math.max(1L,Math.min(100L,deadline-SystemClock.elapsedRealtime())));
                        budget();if(ready==0)continue;
                        if((poll.revents&OsConstants.POLLNVAL)!=0)throw P7MainSafLeaseV3.reject("FD_INVALID");
                        n=Os.read(poll.fd,block,0,(int)Math.min(block.length,(long)limit-size+1L));
                    }catch(ErrnoException e){if(e.errno==OsConstants.EINTR||e.errno==OsConstants.EAGAIN)continue;throw e;}
                    budget();if(n==0){eof=true;break;}
                    if(n<0||size+n>limit)throw P7MainSafLeaseV3.reject("PAYLOAD_BOUND");
                    int written=0;while(written<n){budget();written+=Os.write(sink,block,written,n-written);budget();}
                    digest.update(block,0,n);size+=n;
                }
                // A silent checkError is not a positive peer ACK. EOF, the independently
                // bound expected fingerprint (IMPORT), and our own durable ACK are separate.
                if(!pipe[0].canDetectErrors())throw P7MainSafLeaseV3.reject("RELIABLE_PIPE_REQUIRED");
                pipe[0].checkError();budget();
                if(size<=0||size>P7MainSafLeaseV3.MAX||expected>0&&size!=expected)throw P7MainSafLeaseV3.reject("PAYLOAD_SIZE");
                StringBuilder h=new StringBuilder();for(byte b:digest.digest())h.append(String.format(Locale.ROOT,"%02x",b&255));sha256=h.toString();
                if(expected>0&&!sha256.equals(scope.intent.getString("expectedPayloadSha256")))throw P7MainSafLeaseV3.reject("PAYLOAD_SHA");
                P7MainSafLeaseV3.regular(Os.fstat(sink));P7MainSafLeaseV3.same(Os.fstat(sink),Os.lstat(scope.pendingPayload().getPath()));
                if(Os.fstat(sink).st_size!=size)throw P7MainSafLeaseV3.reject("PAYLOAD_SIZE");
                budget();Os.fsync(sink);budget();fileSynced=true;
                closeSink();closeRead(false);budget();
                // No writable file descriptor escaped. Link+unlink is exclusive and leaves
                // an explicit partial pair after a crash, never a silently adopted file.
                P7MainSafLeaseV3.publish(scope.pendingPayload(),scope.payload());published=true;directorySynced=true;budget();
                scope.event("payload-ack",new JSONObject().put("status","PAYLOAD_COMMITTED").put("size",size).put("sha256",sha256)
                    .put("eofObserved",true).put("receiverFdClosed",true).put("sinkFdClosed",true).put("fileSynced",true)
                    .put("directorySynced",true).put("payloadPublished",true).put("expectedFingerprintBound",expected>0)
                    .put("peerStatusIsAcknowledgement",false));committed=true;
            }catch(Exception|Error failure) {failureClass=failure.getClass().getSimpleName();}
            finally {
                Arrays.fill(block,(byte)0);
                if(!committed) {
                    try{closeRead(true);}catch(Exception ignored){}
                    try{closeSink();}catch(Exception ignored){}
                    failed("RECEIVE_BOUNDARY");
                }
            }
        }
    }
}
