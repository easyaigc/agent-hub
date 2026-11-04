package com.agenthub.android;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.*;

/** Test APK only. One fresh UUID lease, one input OR output, no paths or generic control RPC.
 * Never reads OwnedAcceptanceDocuments/history/user storage. All artifacts survive for Root's
 * post-process-exit ledger; an incomplete open/create/close is not retried or deleted here. */
public final class OwnedMainMigrationDocuments extends DocumentsProvider {
    private static final String NAMESPACE="p7-main-saf-owned-v1";
    private static final int MAX=2*1024*1024;
    @Override public boolean onCreate() { return true; }
    private static String sha(File file) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)) {
            byte[] block=new byte[65536];int n;long size=0;
            while((n=in.read(block))!=-1){size+=n;if(size>MAX)throw new IOException("Owned payload bound");digest.update(block,0,n);}
        }
        StringBuilder result=new StringBuilder();for(byte b:digest.digest())result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();
    }
    private static void regular(File file) throws Exception {
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file.toPath()))throw new IOException("Owned regular file required");
        android.system.StructStat st=Os.lstat(file.getPath());
        if(st.st_uid!=Process.myUid()||st.st_nlink!=1||(st.st_mode&0777)!=0600)throw new IOException("Owned mode/UID/link required");
    }
    private static void syncDirectory(File folder) throws Exception {
        java.io.FileDescriptor fd=Os.open(folder.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);
        try {
            android.system.StructStat opened=Os.fstat(fd),current=Os.lstat(folder.getPath());
            if(!OsConstants.S_ISDIR(opened.st_mode)||!OsConstants.S_ISDIR(current.st_mode)||
                opened.st_uid!=Process.myUid()||opened.st_dev!=current.st_dev||opened.st_ino!=current.st_ino)
                throw new IOException("Exact owned directory descriptor required");
            Os.fsync(fd);
        } finally {Os.close(fd);}
    }
    private static void directory(File folder) throws Exception {
        if(Files.isSymbolicLink(folder.toPath())||!folder.isDirectory()||!folder.getCanonicalFile().equals(folder.getAbsoluteFile()))throw new IOException("Exact owned directory required");
        android.system.StructStat st=Os.lstat(folder.getPath());
        if(st.st_uid!=Process.myUid()||(st.st_mode&0777)!=0700)throw new IOException("Owned directory UID/mode required");
    }
    /** Atomic no-clobber publication, not rename-with-replacement. Same-directory link is the
     * only publication primitive; unsupported link/unlink/sync fails and retains original state. */
    private static void publishNoReplace(File pending,File published) throws Exception {
        regular(pending);
        Os.link(pending.getPath(),published.getPath()); // EEXIST is fatal; never replace a final name.
        android.system.StructStat source=Os.lstat(pending.getPath()),target=Os.lstat(published.getPath());
        if(!OsConstants.S_ISREG(source.st_mode)||!OsConstants.S_ISREG(target.st_mode)||
            source.st_uid!=Process.myUid()||source.st_dev!=target.st_dev||source.st_ino!=target.st_ino||
            source.st_nlink!=2||target.st_nlink!=2||(source.st_mode&0777)!=0600)
            throw new IOException("Exact owned publication link pair required");
        // Each fixed pending name has one writer: scope preparation or a claimed provider event.
        Files.delete(pending.toPath());
        syncDirectory(published.getParentFile());regular(published);
    }
    private static JSONObject processFacts() throws Exception {
        int pid=Process.myPid();File statFile=new File("/proc/"+pid+"/stat");
        String stat=new String(Files.readAllBytes(statFile.toPath()),StandardCharsets.UTF_8);
        String ticks=stat.substring(stat.lastIndexOf(") ")+2).split(" ")[19];
        String boot=new String(Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()),StandardCharsets.UTF_8).trim();
        byte[] argv=Files.readAllBytes(new File("/proc/"+pid+"/cmdline").toPath());
        if(argv.length==0||argv.length>8192)throw new IOException("Actual argv bound");
        int end=argv.length;while(end>0&&argv[end-1]==0)end--;
        String name=new String(argv,0,end,StandardCharsets.UTF_8);
        boolean exact=argv[argv.length-1]==0&&name.indexOf('\0')<0;
        StringBuilder hash=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(argv))hash.append(String.format(Locale.ROOT,"%02x",value&255));
        String after=new String(Files.readAllBytes(statFile.toPath()),StandardCharsets.UTF_8);
        if(!after.substring(after.lastIndexOf(") ")+2).split(" ")[19].equals(ticks))throw new IOException("Racing owner");
        return new JSONObject().put("pid",pid).put("uid",Process.myUid()).put("startTicks",ticks).put("bootUuid",boot)
            .put("argvSha256",hash.toString()).put("argvExactName",exact?name:JSONObject.NULL);
    }
    private final class Scope {
        final File folder; final String run,kind,root,title,input,output; final int appUid,docsUid;
        Scope() throws Exception {
            File parent=new File(getContext().getFilesDir(),NAMESPACE);directory(parent);
            String[] entries=parent.list();if(entries==null||entries.length!=1)throw new IOException("One exclusive UUID scope required");
            String child=entries[0];if(!UUID.fromString(child).toString().equals(child))throw new IOException("Canonical UUID directory required");
            folder=new File(parent,child);directory(folder);
            // During preparation a pending file or transient nlink=2 is rejected, never parsed.
            // Host must not start Native until the helper ACK follows unlink+directory fsync.
            File claim=new File(folder,"scope.claimed");regular(claim);
            if(claim.length()!=0||Files.exists(new File(folder,"scope.pending").toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Unfinished scope publication retained");
            File config=new File(folder,"scope.json");regular(config);if(config.length()>4096)throw new IOException("Scope bound");
            JSONObject value=new JSONObject(new String(Files.readAllBytes(config.toPath()),StandardCharsets.UTF_8));
            if(value.length()!=5||value.getInt("schema")!=1)throw new IOException("Scope schema");
            run=value.getString("run");if(!folder.getName().equals(run)||!UUID.fromString(run).toString().equals(run))throw new IOException("Fresh UUID required");
            kind=value.getString("kind");if(!kind.equals("IMPORT")&&!kind.equals("EXPORT"))throw new IOException("Named action required");
            appUid=value.getInt("appUid");docsUid=value.getInt("docsUid");
            if(appUid==getContext().getApplicationInfo().uid||docsUid==appUid||docsUid==getContext().getApplicationInfo().uid)throw new IOException("Distinct provider/App/system owners required");
            root="p7-root-"+run;title="AH_P7_"+run.replace("-","");input="p7-input-"+run;output="p7-output-"+run;
        }
        String id(){return kind.equals("IMPORT")?input:output;}
        String filename(){return "agent-hub-p7-"+kind.toLowerCase(Locale.ROOT)+"-"+run+".codexmigration";}
        File payload(){return new File(folder,kind.equals("IMPORT")?"input.bin":"output.bin");}
        void queryOwner() throws IOException {int uid=Binder.getCallingUid();if(uid!=docsUid&&uid!=getContext().getApplicationInfo().uid)throw new IOException("Only exact system picker may list synthetic rows");}
        void documentOwner(String documentId) throws IOException {
            int uid=Binder.getCallingUid();if(uid==docsUid||uid==getContext().getApplicationInfo().uid)return;
            android.net.Uri uri=DocumentsContract.buildDocumentUri(getContext().getPackageName()+".p7-main-saf",id());
            if(uid!=appUid||!documentId.equals(id())||
                (getContext().checkUriPermission(uri,Binder.getCallingPid(),uid,Intent.FLAG_GRANT_READ_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED&&
                 getContext().checkUriPermission(uri,Binder.getCallingPid(),uid,Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=PackageManager.PERMISSION_GRANTED))throw new IOException("Exact returned document grant required");
        }
        void event(String name,JSONObject value) throws Exception {
            File claim=new File(folder,name+".claimed"),file=new File(folder,name+".pending"),published=new File(folder,name+".json");
            if(!claim.createNewFile())throw new IOException("Original operation already recorded; no replay");
            android.system.Os.chmod(claim.getPath(),0600);
            if(Files.exists(published.toPath(),LinkOption.NOFOLLOW_LINKS)||!file.createNewFile())throw new IOException("Original audit publication unknown");
            android.system.Os.chmod(file.getPath(),0600);
            JSONObject actual=processFacts();if(!actual.optString("argvExactName").equals(getContext().getPackageName()+":p7MainSaf"))throw new IOException("Exact padded provider argv required");
            try(FileOutputStream out=new FileOutputStream(file)){out.write(value.put("run",run).put("kind",kind).put("documentId",id()).put("authority",getContext().getPackageName()+".p7-main-saf").put("providerProcess",actual).toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
            publishNoReplace(file,published);
        }
    }
    private Scope scope() throws FileNotFoundException {
        try{return new Scope();}catch(Exception failure){throw new FileNotFoundException("Owned P7 scope unavailable: "+failure.getClass().getSimpleName());}
    }
    private static FileNotFoundException denied(Exception failure){return new FileNotFoundException("Owned P7 action rejected: "+failure.getClass().getSimpleName());}
    private Cursor row(String[] projection,String[] defaults,Map<String,Object> values) {
        String[] columns=projection==null?defaults:projection;MatrixCursor cursor=new MatrixCursor(columns);
        MatrixCursor.RowBuilder row=cursor.newRow();for(String column:columns)row.add(values.get(column));return cursor;
    }
    @Override public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        Scope s=scope();try {
            s.queryOwner();Map<String,Object> v=new HashMap<>();v.put(Root.COLUMN_ROOT_ID,s.root);v.put(Root.COLUMN_DOCUMENT_ID,s.root);v.put(Root.COLUMN_TITLE,s.title);
            v.put(Root.COLUMN_FLAGS,Root.FLAG_SUPPORTS_IS_CHILD|(s.kind.equals("EXPORT")?Root.FLAG_SUPPORTS_CREATE:0));v.put(Root.COLUMN_MIME_TYPES,"application/octet-stream");v.put(Root.COLUMN_AVAILABLE_BYTES,(long)MAX);
            return row(projection,new String[]{Root.COLUMN_ROOT_ID,Root.COLUMN_DOCUMENT_ID,Root.COLUMN_TITLE,Root.COLUMN_FLAGS,Root.COLUMN_MIME_TYPES,Root.COLUMN_AVAILABLE_BYTES},v);
        }catch(Exception failure){throw denied(failure);}
    }
    @Override public Cursor queryDocument(String id,String[] projection) throws FileNotFoundException {
        Scope s=scope();try {
            s.documentOwner(id);boolean root=id.equals(s.root);if(!root&&!id.equals(s.id()))throw new IOException("Foreign document ID");
            if(!root)regular(s.payload());Map<String,Object> v=new HashMap<>();v.put(Document.COLUMN_DOCUMENT_ID,id);v.put(Document.COLUMN_DISPLAY_NAME,root?s.title:s.filename());
            v.put(Document.COLUMN_MIME_TYPE,root?Document.MIME_TYPE_DIR:"application/octet-stream");
            v.put(Document.COLUMN_FLAGS,s.kind.equals("EXPORT")?(root?Document.FLAG_DIR_SUPPORTS_CREATE:Document.FLAG_SUPPORTS_WRITE):0);v.put(Document.COLUMN_SIZE,root?0L:s.payload().length());
            return row(projection,new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE},v);
        }catch(Exception failure){throw denied(failure);}
    }
    @Override public Cursor queryChildDocuments(String parent,String[] projection,String order) throws FileNotFoundException {
        Scope s=scope();try {
            s.queryOwner();if(!parent.equals(s.root))throw new IOException("Foreign parent");
            if(s.kind.equals("IMPORT")||Files.exists(s.payload().toPath(),LinkOption.NOFOLLOW_LINKS))return queryDocument(s.id(),projection);
            return new MatrixCursor(projection==null?new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE,Document.COLUMN_FLAGS,Document.COLUMN_SIZE}:projection);
        }catch(Exception failure){throw denied(failure);}
    }
    @Override public boolean isChildDocument(String parent,String child) {try {Scope s=scope();s.documentOwner(child);return parent.equals(s.root)&&child.equals(s.id());}catch(Exception failure){return false;}}
    @Override public String createDocument(String parent,String mime,String name) throws FileNotFoundException {
        Scope s=scope();try {
            if(Binder.getCallingUid()!=s.docsUid||!s.kind.equals("EXPORT")||!parent.equals(s.root)||!mime.equals("application/octet-stream")||!name.equals(s.filename()))throw new IOException("Exact owned create required");
            s.event("create",new JSONObject().put("callerUid",Binder.getCallingUid()).put("callerPid",Binder.getCallingPid()).put("displayName",name).put("mimeType",mime));
            if(!s.payload().createNewFile())throw new IOException("Original output exists; no overwrite");android.system.Os.chmod(s.payload().getPath(),0600);return s.output;
        }catch(Exception failure){throw denied(failure);}
    }
    @Override public ParcelFileDescriptor openDocument(String id,String mode,CancellationSignal signal) throws FileNotFoundException {
        final Scope s=scope();try {
            if(Binder.getCallingUid()!=s.appUid||!id.equals(s.id())||!(s.kind.equals("IMPORT")?mode.equals("r"):mode.equals("wt")))throw new IOException("Exact App original open required");
            if(signal!=null)signal.throwIfCanceled();regular(s.payload());
            s.documentOwner(id);
            int required=s.kind.equals("IMPORT")?Intent.FLAG_GRANT_READ_URI_PERMISSION:Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if(getContext().checkUriPermission(DocumentsContract.buildDocumentUri(getContext().getPackageName()+".p7-main-saf",id),Binder.getCallingPid(),Binder.getCallingUid(),required)!=PackageManager.PERMISSION_GRANTED)throw new IOException("Actual exact grant required");
            s.event("open",new JSONObject().put("callerUid",Binder.getCallingUid()).put("callerPid",Binder.getCallingPid()).put("mode",mode));
            return ParcelFileDescriptor.open(s.payload(),ParcelFileDescriptor.parseMode(mode),new Handler(Looper.getMainLooper()),error->{
                try {s.event("close",new JSONObject().put("success",error==null).put("size",s.payload().length()).put("sha256",sha(s.payload())));}
                catch(Exception failure){try {s.event("close-failed",new JSONObject().put("failureType",failure.getClass().getSimpleName()));}catch(Exception ignored){}}
            });
        }catch(Exception failure){throw denied(failure);}
    }
    /** Local adb/run-as CLI entry only. No exported Android component, Binder or provider RPC.
     * Commands/paths/payload cap are closed; Root pins this exact source in its new SDK intent.
     * A partial namespace is permanently retained, never reused, repaired or overwritten here. */
    public static final class LocalLeaseFiles {
        private static final String TEST="com.easyai.agenthub.android.acceptance.test";
        private static int number(String value) throws Exception {
            if(!value.matches("[1-9][0-9]{0,9}"))throw new IOException("Canonical UID/size required");
            return Integer.parseInt(value);
        }
        private static File parent() throws Exception {
            File cwd=new File(".").getCanonicalFile();
            if(!cwd.getName().equals(TEST)||!new File(cwd,"files").isDirectory())throw new IOException("Exact test package run-as cwd required");
            return new File(cwd,"files/"+NAMESPACE);
        }
        private static void writeNew(File file,byte[] bytes) throws Exception {
            java.io.FileDescriptor fd=Os.open(file.getPath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_NOFOLLOW,0600);
            try(FileOutputStream out=new FileOutputStream(fd)){out.write(bytes);out.getFD().sync();}
            syncDirectory(file.getParentFile());
        }
        private static JSONObject json(File file) throws Exception {
            regular(file);if(file.length()>16384)throw new IOException("Finite audit JSON required");
            return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        }
        public static void main(String[] args) throws Exception {
            if(args.length<6)throw new IOException("Finite local lease command required");
            String command=args[0],run=args[1];if(!UUID.fromString(run).toString().equals(run))throw new IOException("Fresh run required");
            int testUid=number(args[2]),appUid=number(args[3]),docsUid=number(args[4]);
            if(Process.myUid()!=testUid||testUid==appUid||docsUid==testUid||docsUid==appUid)throw new IOException("Exact separate UID required");
            if(!Arrays.asList("prepare-import","prepare-export","audit-import","audit-export").contains(command))throw new IOException("Closed local command");
            boolean imported=command.endsWith("import"),prepare=command.startsWith("prepare");String kind=imported?"IMPORT":"EXPORT";
            String operation=args[5];if(!UUID.fromString(operation).toString().equals(operation))throw new IOException("Immutable operation required");
            File parent=parent(),folder=new File(parent,run);
            JSONObject result=new JSONObject().put("schema","p7-main-saf-local-lease-v1").put("run",run).put("kind",kind)
                .put("command",command).put("operationId",operation).put("helperProcess",processFacts());
            if(prepare) {
                if(args.length!=(imported?9:7))throw new IOException("Finite preparation args");
                String sourceExitSha=args[6];if(!sourceExitSha.matches("[a-f0-9]{64}"))throw new IOException("Original exit/intent binding required");
                if(Files.exists(parent.toPath(),LinkOption.NOFOLLOW_LINKS)||!parent.mkdir())throw new IOException("Prior namespace retained; no fallback");
                Os.chmod(parent.getPath(),0700);syncDirectory(parent.getParentFile());directory(parent);
                if(Files.exists(folder.toPath(),LinkOption.NOFOLLOW_LINKS)||!folder.mkdir())throw new IOException("Prior UUID lease retained; never overwrite");
                Os.chmod(folder.getPath(),0700);syncDirectory(parent);directory(folder);
                writeNew(new File(folder,"scope.claimed"),new byte[0]);
                result.put("operationId",operation).put("sourceExitOrIntentSha256",sourceExitSha);
                if(imported) {
                    String expected=args[7];int size=number(args[8]);if(size>MAX||!expected.matches("[a-f0-9]{64}"))throw new IOException("Finite owned input required");
                    File input=new File(folder,"input.bin");java.io.FileDescriptor fd=Os.open(input.getPath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_NOFOLLOW,0600);
                    int total=0;byte[] block=new byte[65536];
                    try(FileOutputStream out=new FileOutputStream(fd)) {
                        while(true){int n=System.in.read(block);if(n<0)break;total+=n;if(total>size)throw new IOException("Input exceeds immutable size");out.write(block,0,n);}out.getFD().sync();
                    }finally {Arrays.fill(block,(byte)0);System.in.close();}
                    if(total!=size||!sha(input).equals(expected))throw new IOException("Original transfer fingerprint differs; no repeat");
                    result.put("bytes",total).put("sha256",expected).put("sourceEof",true).put("payloadFdClosed",true);syncDirectory(folder);
                } else {if(System.in.read()!=-1)throw new IOException("Export never accepts input");System.in.close();}
                writeNew(new File(folder,"handoff.json"),result.toString().getBytes(StandardCharsets.UTF_8));
                JSONObject scope=new JSONObject().put("schema",1).put("run",run).put("kind",kind).put("appUid",appUid).put("docsUid",docsUid);
                File pending=new File(folder,"scope.pending");writeNew(pending,scope.toString().getBytes(StandardCharsets.UTF_8));
                publishNoReplace(pending,new File(folder,"scope.json"));
                result.put("scopePublished",true).put("directorySynced",true);
            } else {
                if(args.length!=6)throw new IOException("Finite audit args");
                if(System.in.read()!=-1)throw new IOException("Audit never accepts input");System.in.close();directory(parent);directory(folder);
                String[] children=parent.list();if(children==null||children.length!=1||!children[0].equals(run))throw new IOException("Exact exclusive UUID scope required");
                android.system.StructStat directory=Os.lstat(folder.getPath());
                if(directory.st_uid!=Process.myUid()||(directory.st_mode&0777)!=0700)throw new IOException("Audit directory UID/mode required");
                JSONObject scope=json(new File(folder,"scope.json"));
                if(scope.length()!=5||scope.getInt("schema")!=1||!scope.getString("run").equals(run)||!scope.getString("kind").equals(kind)||scope.getInt("appUid")!=appUid||scope.getInt("docsUid")!=docsUid)throw new IOException("Audit scope differs");
                Set<String> allowed=new HashSet<>(Arrays.asList("scope.claimed","scope.json","handoff.json",imported?"input.bin":"output.bin","open.claimed","open.json","close.claimed","close.json"));
                if(!imported)allowed.addAll(Arrays.asList("create.claimed","create.json"));
                String[] names=folder.list();if(names==null||!new HashSet<>(Arrays.asList(names)).equals(allowed))throw new IOException("Partial/foreign/failure audit retained");
                JSONObject files=new JSONObject(),documents=new JSONObject();
                for(String name:names) {
                    File file=new File(folder,name);regular(file);if(file.length()>MAX)throw new IOException("Audit bound");
                    files.put(name,new JSONObject().put("bytes",file.length()).put("sha256",sha(file)));
                    if(name.endsWith(".json"))documents.put(name,json(file));
                    if(name.endsWith(".claimed")&&file.length()!=0)throw new IOException("Claim bytes unexpected");
                }
                result.put("files",files).put("documents",documents).put("auditReadFdsClosed",true);
            }
            result.put("helperOutcome","complete").put("stdinEof",true).put("stdinClosed",true);
            byte[] output=result.toString().getBytes(StandardCharsets.UTF_8);if(output.length>16384)throw new IOException("Summary bound");
            System.out.write(output);System.out.write('\n');System.out.flush();System.out.close();System.err.flush();System.err.close();
        }
    }
}
