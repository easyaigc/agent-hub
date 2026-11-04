package com.agenthub.android;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.*;

/** androidTest only. New namespace; this class never opens, enumerates or migrates v1.
 * Same-UID hostile concurrent replacement is outside the existing synthetic-test threat model.
 * Every open still checks nofollow, exact inode, UID/mode/link count and bounded bytes. */
final class P7MainSafLeaseV3 {
    static final String APP="com.easyai.agenthub.android.acceptance", TEST=APP+".test";
    static final String NAMESPACE="p7-main-saf-owned-v3";
    static final String AUTHORITY=TEST+".p7-main-saf-v3", CONTROL=TEST+".p7-main-saf-control-v4";
    static final String PROFILE="p7-main-saf-framework-v4";
    static final int MAX=2*1024*1024, JSON_MAX=16384, MAX_RUNS=16, MAX_FILES=20;
    static final long RUN_MAX=MAX+64L*1024;
    static final Set<String> INTENT_KEYS=Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(
        "schema","profile","run","operationId","kind","appUid","testUid","docsUid","docsPackage","bootUuid",
        "appApkSha256","testApkSha256","certificateSha256","sdkSealSha256","workspaceReviewSha256",
        "sourceGuardSha256","profileSha256","rootApprovalSha256","transportContractReviewSha256",
        "serialSha256","adbSha256","expiresWallMillis","expectedPayloadBytes","expectedPayloadSha256","sourceProducerProofSha256")));
    static final Set<String> NAMES=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "controller-owner.pending","controller-owner.json","document-owner.pending","document-owner.json",
        "scope.claimed","intent.pending","intent.json","scope.pending","scope.json","receipt.pending","receipt.json",
        "output.pending","output.bin","input.pending","input.bin","payload.claimed",
        "create.claimed","create.pending","create.json","open.claimed","open.pending","open.json",
        "payload-ack.claimed","payload-ack.pending","payload-ack.json","payload-failed.claimed","payload-failed.pending","payload-failed.json",
        "read-open.claimed","read-open.pending","read-open.json","read-close.claimed","read-close.pending","read-close.json")));
    static boolean payloadName(String name) {
        return Arrays.asList("output.pending","output.bin","input.pending","input.bin").contains(name);
    }
    private P7MainSafLeaseV3() {}
    /** Diagnostics are only for this class's own fixed guard literals. Raw IO messages
     * and values are never parsed, logged or sent by this classifier. */
    private static final Set<String> REJECTION_CATEGORIES=Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
        "ABSENT","APK","APK_BOUND","AUDIT_BOUND","AUDIT_CHANGED","BOOT",
        "BOUND","BYTE_BOUND","CERTIFICATE","CHANGED","CLAIM","COMPONENT",
        "CREATE","CREATE_REQUIRED","DIRECTORY","DOCS_PACKAGE","DOCS_RESOLVER","DOCS_UID",
        "DOCUMENT_ID","EVENT","EXPIRED","EXPORT_EXPECTATION","EXPORT_MODE","FD_DEADLINE",
        "FD_INVALID","FD_OWNER_EXISTS","FILE","FILE_BOUND","FOREIGN_FILE","GRANT_CALLER",
        "HASH","IMPORT_EXPECTATION","IMPORT_MODE","IMPORT_NOT_COMMITTED","IMPORT_SNAPSHOT","INODE",
        "INPUT_MODE","KEYS","KIND","LINK_PAIR","LIST_CALLER","METADATA_BOUND",
        "METADATA_READ_BOUND","METHOD","MODE","OPEN","OPERATION","OWNER_REPLACED",
        "OWNER_UNKNOWN","PARENT","PARTIAL","PAYLOAD_ACK","PAYLOAD_ACK_FILE","PAYLOAD_BOUND",
        "PAYLOAD_SHA","PAYLOAD_SIZE","PLATFORM_DEVICE","PLATFORM_DIRECTORY","PLATFORM_FILES_PATH","PLATFORM_GID",
        "PLATFORM_IDENTITY","PLATFORM_MODE","PLATFORM_NAMESPACE","PLATFORM_PACKAGE","PLATFORM_PATH_CHANGED","PRIMITIVE",
        "PROCESS","PROCESS_BOUND","PROTOCOL","RECEIPT","RELIABLE_PIPE_REQUIRED","RUN",
        "RUN_BOUND","SCOPE","TEST_IDENTITY","TYPE","UID","URI",
        "URI_ALIAS","UUID"
    )));
    private static final class GuardRejection extends IOException {
        private static final long serialVersionUID=1L;
        private final String category;
        private GuardRejection(String category) { super("P7V4_"+category);this.category=category; }
    }
    static IOException reject(String category) {
        return REJECTION_CATEGORIES.contains(category)?new GuardRejection(category):new IOException("P7V4_UNCLASSIFIED");
    }
    /** Preserve existing retained failureClass values despite the private subtype. */
    static String failureClass(Throwable failure) {
        return failure instanceof GuardRejection?"IOException":failure.getClass().getSimpleName();
    }
    /** Only a private, directly raised guard can carry one fixed category. No cause traversal. */
    static String rejectionCode(Throwable failure) {
        if(failure instanceof GuardRejection) {
            String category=((GuardRejection)failure).category;
            if(REJECTION_CATEGORIES.contains(category))return "IOException_GUARD_"+category;
        }
        return failureClass(failure);
    }
    static String uuid(String value) throws Exception {
        if(value==null||!UUID.fromString(value).toString().equals(value))throw reject("UUID");return value;
    }
    static String hash(byte[] bytes) throws Exception {
        StringBuilder b=new StringBuilder();for(byte v:MessageDigest.getInstance("SHA-256").digest(bytes))b.append(String.format(Locale.ROOT,"%02x",v&255));return b.toString();
    }
    static void exact(JSONObject value,Set<String> keys) throws Exception {
        Set<String> actual=new HashSet<>();Iterator<String> i=value.keys();while(i.hasNext())actual.add(i.next());
        if(!actual.equals(keys))throw reject("KEYS");
    }
    /** Canonical JSON is deliberately limited to this fixed flat primitive metadata contract. */
    static byte[] canonical(JSONObject value) throws Exception {
        TreeSet<String> keys=new TreeSet<>();Iterator<String> i=value.keys();while(i.hasNext())keys.add(i.next());
        StringBuilder out=new StringBuilder("{");for(String key:keys) {
            if(out.length()>1)out.append(',');Object v=value.get(key);out.append(JSONObject.quote(key)).append(':');
            if(v instanceof String)out.append(JSONObject.quote((String)v));
            else if(v instanceof Integer||v instanceof Long)out.append(v.toString());else throw reject("PRIMITIVE");
        }
        byte[] bytes=out.append('}').toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>JSON_MAX)throw reject("BOUND");return bytes;
    }
    static JSONObject fromBundle(String operation,Bundle extras) throws Exception {
        if(extras==null||!extras.keySet().equals(INTENT_KEYS))throw reject("KEYS");
        JSONObject result=new JSONObject();for(String key:new TreeSet<>(INTENT_KEYS)) {
            Object value=extras.get(key);
            if(key.endsWith("Uid")) {if(!(value instanceof Integer))throw reject("TYPE");}
            else if((key.equals("expiresWallMillis")||key.equals("expectedPayloadBytes"))) {if(!(value instanceof Long))throw reject("TYPE");}
            else if(!(value instanceof String))throw reject("TYPE");
            result.put(key,value);
        }
        if(!uuid(operation).equals(result.getString("operationId")))throw reject("OPERATION");return result;
    }
    static void directory(File file) throws Exception {
        if(!file.getCanonicalFile().equals(file.getAbsoluteFile())||Files.isSymbolicLink(file.toPath()))throw reject("DIRECTORY");
        StructStat st=Os.lstat(file.getPath());
        if(!OsConstants.S_ISDIR(st.st_mode)||st.st_uid!=Process.myUid()||(st.st_mode&0777)!=0700)throw reject("DIRECTORY");
    }
    static void regular(StructStat st) throws Exception {
        if(!OsConstants.S_ISREG(st.st_mode)||st.st_uid!=Process.myUid()||st.st_nlink!=1||(st.st_mode&0777)!=0600)throw reject("FILE");
    }
    static void same(StructStat a,StructStat b) throws Exception {
        if(a.st_dev!=b.st_dev||a.st_ino!=b.st_ino||a.st_uid!=b.st_uid||a.st_mode!=b.st_mode||a.st_nlink!=b.st_nlink)throw reject("INODE");
    }
    static void syncDirectory(File folder) throws Exception {
        directory(folder);FileDescriptor fd=Os.open(folder.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);
        try {StructStat opened=Os.fstat(fd);same(opened,Os.lstat(folder.getPath()));if(!OsConstants.S_ISDIR(opened.st_mode))throw reject("DIRECTORY");Os.fsync(fd);}
        finally {Os.close(fd);}
    }
    static void mkdirNew(File folder) throws Exception {
        directory(folder.getParentFile());Os.mkdir(folder.getPath(),0700);directory(folder);syncDirectory(folder.getParentFile());
    }
    /** The platform files container is a narrow exception, never a generic private directory.
     * Its canonical package data ancestor must stay UID-owned 0700. Only this exact files
     * child may be 0700 or Android's observed 0771; namespace/run directories remain 0700. */
    private static final class PlatformFiles {
        final File data,files;final StructStat dataStat,filesStat;
        PlatformFiles(File data,File files,StructStat dataStat,StructStat filesStat) {
            this.data=data;this.files=files;this.dataStat=dataStat;this.filesStat=filesStat;
        }
    }
    private static void platformDirectory(File file,StructStat st,boolean files) throws Exception {
        if(!file.getCanonicalFile().equals(file.getAbsoluteFile())||Files.isSymbolicLink(file.toPath())||
            !OsConstants.S_ISDIR(st.st_mode)||st.st_uid!=Process.myUid())throw reject("PLATFORM_DIRECTORY");
        int mode=st.st_mode&07777;
        if(files?(mode!=0700&&mode!=0771):mode!=0700)throw reject("PLATFORM_MODE");
    }
    private static PlatformFiles readPlatformFiles(Context context) throws Exception {
        ApplicationInfo info=context.getApplicationInfo();
        if(!context.getPackageName().equals(TEST)||info==null||!TEST.equals(info.packageName)||info.uid!=Process.myUid()||
            info.dataDir==null||info.dataDir.isEmpty())throw reject("PLATFORM_PACKAGE");
        // Only authenticated framework package roots may contain Android's user-0 alias.
        // Resolve their ancestors once; never admit a symlink at either directory leaf.
        File rawData=new File(info.dataDir).getAbsoluteFile(),rawFiles=context.getFilesDir();
        if(rawFiles==null)throw reject("PLATFORM_FILES_PATH");
        rawFiles=rawFiles.getAbsoluteFile();
        if(!rawFiles.equals(new File(rawData,"files")))throw reject("PLATFORM_FILES_PATH");
        StructStat rawDataStat=Os.lstat(rawData.getPath()),rawFilesStat=Os.lstat(rawFiles.getPath());
        if(Files.isSymbolicLink(rawData.toPath())||Files.isSymbolicLink(rawFiles.toPath()))throw reject("PLATFORM_DIRECTORY");
        File data=rawData.getCanonicalFile(),files=rawFiles.getCanonicalFile();
        if(!files.equals(new File(data,"files")))throw reject("PLATFORM_FILES_PATH");
        StructStat dataStat=Os.lstat(data.getPath()),filesStat=Os.lstat(files.getPath());
        platformDirectory(data,dataStat,false);platformDirectory(files,filesStat,true);
        if(dataStat.st_gid!=Process.myUid()||filesStat.st_gid!=Process.myUid())throw reject("PLATFORM_GID");
        if(dataStat.st_dev!=filesStat.st_dev)throw reject("PLATFORM_DEVICE");
        samePlatformIdentity(rawDataStat,dataStat);samePlatformIdentity(rawFilesStat,filesStat);
        samePlatformIdentity(rawDataStat,Os.lstat(rawData.getPath()));
        samePlatformIdentity(rawFilesStat,Os.lstat(rawFiles.getPath()));
        return new PlatformFiles(data,files,dataStat,filesStat);
    }
    /** Directory nlink may change when the one admitted namespace is created. Unlike a
     * regular-file alias check, platform directory identity therefore pins inode/UID/GID/
     * full mode rather than nlink. All generic same()/regular() rules remain unchanged. */
    private static void samePlatformIdentity(StructStat a,StructStat b) throws Exception {
        if(a.st_dev!=b.st_dev||a.st_ino!=b.st_ino||a.st_uid!=b.st_uid||a.st_gid!=b.st_gid||a.st_mode!=b.st_mode)throw reject("PLATFORM_IDENTITY");
    }
    private static void checkPlatformFiles(Context context,PlatformFiles expected) throws Exception {
        PlatformFiles current=readPlatformFiles(context);
        if(!current.data.equals(expected.data)||!current.files.equals(expected.files))throw reject("PLATFORM_PATH_CHANGED");
        samePlatformIdentity(expected.dataStat,current.dataStat);samePlatformIdentity(expected.filesStat,current.filesStat);
    }
    private static PlatformFiles platformFiles(Context context) throws Exception {
        PlatformFiles result=readPlatformFiles(context);checkPlatformFiles(context,result);return result;
    }
    /** Called only while both original platform-directory descriptors are held. */
    private static void syncPlatformFiles(Context context,PlatformFiles expected,FileDescriptor dataFd,FileDescriptor filesFd) throws Exception {
        checkPlatformFiles(context,expected);
        samePlatformIdentity(expected.dataStat,Os.fstat(dataFd));samePlatformIdentity(expected.filesStat,Os.fstat(filesFd));
        Os.fsync(filesFd);
        samePlatformIdentity(expected.dataStat,Os.fstat(dataFd));samePlatformIdentity(expected.filesStat,Os.fstat(filesFd));
        checkPlatformFiles(context,expected);
    }
    private static void createPlatformNamespace(Context context,PlatformFiles expected,File folder) throws Exception {
        if(!folder.equals(new File(expected.files,NAMESPACE)))throw reject("PLATFORM_NAMESPACE");
        checkPlatformFiles(context,expected);
        FileDescriptor dataFd=Os.open(expected.data.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);
        try {
            samePlatformIdentity(expected.dataStat,Os.fstat(dataFd));checkPlatformFiles(context,expected);
            FileDescriptor filesFd=Os.open(expected.files.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);
            try {
                samePlatformIdentity(expected.dataStat,Os.fstat(dataFd));samePlatformIdentity(expected.filesStat,Os.fstat(filesFd));
                checkPlatformFiles(context,expected);
                Os.mkdir(folder.getPath(),0700);directory(folder);
                syncPlatformFiles(context,expected,dataFd,filesFd);directory(folder);
            }finally {Os.close(filesFd);}
        }finally {Os.close(dataFd);}
        checkPlatformFiles(context,expected);
    }
    static File parent(Context context,boolean create) throws Exception {
        PlatformFiles platform=platformFiles(context);File folder=new File(platform.files,NAMESPACE);
        if(!Files.exists(folder.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            if(!create)throw reject("ABSENT");createPlatformNamespace(context,platform,folder);
        }
        directory(folder);checkPlatformFiles(context,platform);return folder;
    }
    /** Listing is empty only for this class's own absent-namespace guard. */
    static File parentOrNull(Context context) throws Exception {
        try {return parent(context,false);}
        catch(GuardRejection failure) {
            if(failure.category.equals("ABSENT"))return null;
            throw failure;
        }
    }
    static List<String> runs(File parent) throws Exception {
        directory(parent);String[] names=parent.list();if(names==null||names.length>MAX_RUNS)throw reject("RUN_BOUND");
        List<String> result=new ArrayList<>();for(String name:names){uuid(name);directory(new File(parent,name));result.add(name);}
        Collections.sort(result);return result;
    }
    static Set<String> inventory(File folder) throws Exception {
        directory(folder);String[] names=folder.list();if(names==null||names.length>MAX_FILES)throw reject("FILE_BOUND");
        long total=0;Set<String> result=new TreeSet<>();for(String name:names) {
            if(!NAMES.contains(name)||!result.add(name))throw reject("FOREIGN_FILE");
            StructStat st=Os.lstat(new File(folder,name).getPath());regular(st);
            if(st.st_size<0||st.st_size>(payloadName(name)?MAX:JSON_MAX))throw reject("BOUND");total+=st.st_size;
        }
        if(total>RUN_MAX)throw reject("BYTE_BOUND");return result;
    }
    /** Metadata-only visibility check. A crash during link/unlink may leave nlink=2;
     * that exact partial pair is retained and hidden, not parsed or presented as complete. */
    static boolean publishedCandidate(File folder) throws Exception {
        directory(folder);String[] listed=folder.list();if(listed==null||listed.length>MAX_FILES)throw reject("FILE_BOUND");
        Set<String> names=new HashSet<>(Arrays.asList(listed));Set<String> chargedPairs=new HashSet<>();long total=0;boolean pending=false;
        for(String name:names) {
            if(!NAMES.contains(name))throw reject("FOREIGN_FILE");StructStat st=Os.lstat(new File(folder,name).getPath());
            if(!OsConstants.S_ISREG(st.st_mode)||st.st_uid!=Process.myUid()||(st.st_mode&0777)!=0600||st.st_nlink<1||st.st_nlink>2)throw reject("FILE");
            if(st.st_size<0||st.st_size>(payloadName(name)?MAX:JSON_MAX))throw reject("BOUND");boolean charge=true;
            if(name.endsWith(".pending"))pending=true;
            if(st.st_nlink==2) {
                String peer;
                if(name.equals("output.pending"))peer="output.bin";
                else if(name.equals("output.bin"))peer="output.pending";
                else if(name.equals("input.pending"))peer="input.bin";
                else if(name.equals("input.bin"))peer="input.pending";
                else peer=name.endsWith(".pending")?name.substring(0,name.length()-8)+".json":name.endsWith(".json")?name.substring(0,name.length()-5)+".pending":null;
                if(peer==null||!names.contains(peer))throw reject("LINK_PAIR");same(st,Os.lstat(new File(folder,peer).getPath()));pending=true;
                // Only this verified exact two-name link pair shares a byte charge.
                // Every pathname still counts toward MAX_FILES and is checked independently.
                String pair=name.compareTo(peer)<0?name+":"+peer:peer+":"+name;
                charge=chargedPairs.add(pair);
            }
            if(charge)total+=st.st_size;
        }
        if(total>RUN_MAX)throw reject("BYTE_BOUND");
        return !pending&&names.containsAll(Arrays.asList("scope.claimed","intent.json","scope.json","receipt.json"));
    }
    static void writeNew(File file,byte[] bytes) throws Exception {
        if(bytes.length>JSON_MAX)throw reject("BOUND");directory(file.getParentFile());
        FileDescriptor fd=Os.open(file.getPath(),OsConstants.O_WRONLY|OsConstants.O_CREAT|OsConstants.O_EXCL|OsConstants.O_NOFOLLOW,0600);
        try(FileOutputStream out=new FileOutputStream(fd)) {
            StructStat opened=Os.fstat(fd);regular(opened);same(opened,Os.lstat(file.getPath()));out.write(bytes);out.getFD().sync();same(Os.fstat(fd),Os.lstat(file.getPath()));
        }
        syncDirectory(file.getParentFile());
    }
    static void publish(File pending,File finalFile) throws Exception {
        regular(Os.lstat(pending.getPath()));Os.link(pending.getPath(),finalFile.getPath());
        StructStat a=Os.lstat(pending.getPath()),b=Os.lstat(finalFile.getPath());same(a,b);
        if(!OsConstants.S_ISREG(a.st_mode)||a.st_uid!=Process.myUid()||a.st_nlink!=2||(a.st_mode&0777)!=0600)throw reject("LINK_PAIR");
        Files.delete(pending.toPath());syncDirectory(finalFile.getParentFile());regular(Os.lstat(finalFile.getPath()));
    }
    static void atomicJson(File folder,String stem,JSONObject value) throws Exception {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>JSON_MAX)throw reject("BOUND");
        File pending=new File(folder,stem+".pending");writeNew(pending,bytes);publish(pending,new File(folder,stem+".json"));
    }
    static byte[] read(File file,int cap) throws Exception {
        StructStat before=Os.lstat(file.getPath());regular(before);if(before.st_size<0||before.st_size>cap)throw reject("BOUND");
        FileDescriptor fd=Os.open(file.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);
        try(FileInputStream in=new FileInputStream(fd);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            same(before,Os.fstat(fd));byte[] bytes=new byte[65536];int n,total=0;
            while((n=in.read(bytes))!=-1){total+=n;if(total>cap)throw reject("BOUND");out.write(bytes,0,n);}Arrays.fill(bytes,(byte)0);
            StructStat after=Os.fstat(fd);regular(after);same(before,after);same(after,Os.lstat(file.getPath()));
            if(total!=before.st_size||after.st_size!=before.st_size||after.st_mtime!=before.st_mtime||after.st_ctime!=before.st_ctime)throw reject("CHANGED");return out.toByteArray();
        }
    }
    static JSONObject json(File folder,String name) throws Exception {return new JSONObject(new String(read(new File(folder,name),JSON_MAX),StandardCharsets.UTF_8));}
    static ParcelFileDescriptor immutableFd(File file) throws Exception {
        StructStat before=Os.lstat(file.getPath());regular(before);if(before.st_size>JSON_MAX)throw reject("BOUND");
        FileDescriptor fd=Os.open(file.getPath(),OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_NONBLOCK,0);
        try {same(before,Os.fstat(fd));same(before,Os.lstat(file.getPath()));return ParcelFileDescriptor.dup(fd);}finally {Os.close(fd);}
    }
    private static final String PROCESS_NONCE=UUID.randomUUID().toString();
    static JSONObject facts(String processName) throws Exception {
        int pid=Process.myPid();String path="/proc/"+pid;byte[] argv=boundedSystem(new File(path+"/cmdline"),8192);
        String before=new String(boundedSystem(new File(path+"/stat"),4096),StandardCharsets.UTF_8);
        String ticks=before.substring(before.lastIndexOf(") ")+2).split(" ")[19];if(!ticks.matches("[0-9]{1,24}"))throw reject("PROCESS");
        int end=argv.length;while(end>0&&argv[end-1]==0)end--;
        if(end==0||end==argv.length||!new String(argv,0,end,StandardCharsets.UTF_8).equals(processName))throw reject("PROCESS");
        String after=new String(boundedSystem(new File(path+"/stat"),4096),StandardCharsets.UTF_8);
        if(!after.substring(after.lastIndexOf(") ")+2).split(" ")[19].equals(ticks))throw reject("PROCESS");
        return new JSONObject().put("pid",pid).put("uid",Process.myUid()).put("startTicks",ticks)
            .put("bootUuid",uuid(new String(boundedSystem(new File("/proc/sys/kernel/random/boot_id"),128),StandardCharsets.UTF_8).trim()))
            .put("argvSha256",hash(argv)).put("argvExactName",processName).put("processNonce",PROCESS_NONCE);
    }
    static synchronized JSONObject ownerWitness(File folder,boolean controller) throws Exception {
        String stem=controller?"controller-owner":"document-owner";
        JSONObject current=facts(TEST+(controller?":p7MainSafControlV4":":p7MainSafV3"));
        File finalFile=new File(folder,stem+".json"),pending=new File(folder,stem+".pending");
        if(Files.exists(pending.toPath(),LinkOption.NOFOLLOW_LINKS))throw reject("OWNER_UNKNOWN");
        if(Files.exists(finalFile.toPath(),LinkOption.NOFOLLOW_LINKS)) {
            JSONObject original=json(folder,stem+".json");
            if(!Arrays.equals(canonical(original),canonical(current)))throw reject("OWNER_REPLACED");
            return original;
        }
        atomicJson(folder,stem,current);return current;
    }
    private static byte[] boundedSystem(File file,int cap) throws Exception {
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] b=new byte[1024];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>cap)throw reject("PROCESS_BOUND");out.write(b,0,n);}return out.toByteArray();
        }
    }
    static void validate(Context context,JSONObject v,boolean current,boolean apk) throws Exception {
        exact(v,INTENT_KEYS);if(!v.getString("schema").equals("p7-main-saf-controller-intent-v4")||!v.getString("profile").equals(PROFILE)||!Arrays.asList("EXPORT","IMPORT").contains(v.getString("kind")))throw reject("PROTOCOL");
        uuid(v.getString("run"));uuid(v.getString("operationId"));uuid(v.getString("bootUuid"));
        long expected=v.getLong("expectedPayloadBytes");String expectedHash=v.getString("expectedPayloadSha256"),producer=v.getString("sourceProducerProofSha256");
        String zero=String.join("",Collections.nCopies(64,"0"));
        if(v.getString("kind").equals("EXPORT")) {
            if(expected!=0||!expectedHash.equals(zero)||!producer.equals(zero))throw reject("EXPORT_EXPECTATION");
        } else if(expected<=0||expected>MAX||expectedHash.equals(zero)||producer.equals(zero))throw reject("IMPORT_EXPECTATION");
        if(!context.getPackageName().equals(TEST)||Process.myUid()!=v.getInt("testUid"))throw reject("TEST_IDENTITY");
        PackageManager pm=context.getPackageManager();ApplicationInfo test=pm.getApplicationInfo(TEST,0),app=pm.getApplicationInfo(APP,0);
        int appUid=v.getInt("appUid"),testUid=v.getInt("testUid"),docsUid=v.getInt("docsUid");
        if(appUid<10000||testUid<10000||appUid>=100000||testUid>=100000||docsUid<=0||docsUid>=100000||new HashSet<>(Arrays.asList(appUid,testUid,docsUid)).size()!=3||
            app.uid!=appUid||test.uid!=testUid||(app.flags&ApplicationInfo.FLAG_DEBUGGABLE)==0||(test.flags&ApplicationInfo.FLAG_DEBUGGABLE)==0||
            !Arrays.equals(pm.getPackagesForUid(appUid),new String[]{APP})||!Arrays.equals(pm.getPackagesForUid(testUid),new String[]{TEST}))throw reject("UID");
        String docs=v.getString("docsPackage");if(!docs.equals("com.google.android.documentsui"))throw reject("DOCS_PACKAGE");
        ApplicationInfo picker=pm.getApplicationInfo(docs,0);if(picker.uid!=docsUid||(picker.flags&ApplicationInfo.FLAG_SYSTEM)==0)throw reject("DOCS_UID");
        android.content.pm.ResolveInfo owner=pm.resolveActivity(new Intent(v.getString("kind").equals("IMPORT")?Intent.ACTION_OPEN_DOCUMENT:Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream"),PackageManager.MATCH_DEFAULT_ONLY);
        if(owner==null||!owner.activityInfo.packageName.equals(docs)||owner.activityInfo.applicationInfo.uid!=docsUid)throw reject("DOCS_RESOLVER");
        for(String key:INTENT_KEYS)if(key.endsWith("Sha256")&&!v.getString(key).matches("[a-f0-9]{64}"))throw reject("HASH");
        long now=System.currentTimeMillis();if(current&&(v.getLong("expiresWallMillis")<=now||v.getLong("expiresWallMillis")-now>600000))throw reject("EXPIRED");
        String boot=new String(boundedSystem(new File("/proc/sys/kernel/random/boot_id"),128),StandardCharsets.UTF_8).trim();if(current&&!boot.equals(v.getString("bootUuid")))throw reject("BOOT");
        if(apk)for(String pkg:Arrays.asList(APP,TEST)) {
            PackageInfo p=pm.getPackageInfo(pkg,PackageManager.GET_SIGNING_CERTIFICATES);
            if(p.applicationInfo==null||p.applicationInfo.splitSourceDirs!=null&&p.applicationInfo.splitSourceDirs.length!=0||p.signingInfo==null||p.signingInfo.getApkContentsSigners().length!=1)throw reject("APK");
            if(!hash(p.signingInfo.getApkContentsSigners()[0].toByteArray()).equals(v.getString("certificateSha256")))throw reject("CERTIFICATE");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long count=0;
            try(InputStream in=new FileInputStream(p.applicationInfo.sourceDir)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){count+=n;if(count>512L*1024*1024)throw reject("APK_BOUND");digest.update(b,0,n);}}
            StringBuilder h=new StringBuilder();for(byte b:digest.digest())h.append(String.format(Locale.ROOT,"%02x",b&255));
            if(count==0||!h.toString().equals(v.getString(pkg.equals(APP)?"appApkSha256":"testApkSha256")))throw reject("APK");
        }
    }
    static void component(Context context,boolean controller) throws Exception {
        String name=controller?"OwnedMainMigrationControlV4":"OwnedMainMigrationDocumentsV3";
        ProviderInfo p=context.getPackageManager().getProviderInfo(new ComponentName(TEST,"com.agenthub.android."+name),0);
        String permission=controller?"android.permission.DUMP":"android.permission.MANAGE_DOCUMENTS";
        if(!p.authority.equals(controller?CONTROL:AUTHORITY)||!p.packageName.equals(TEST)||p.applicationInfo.uid!=Process.myUid()||!p.exported||
            !p.processName.equals(TEST+(controller?":p7MainSafControlV4":":p7MainSafV3"))||
            !permission.equals(p.readPermission)||!permission.equals(p.writePermission)||p.grantUriPermissions==controller)throw reject("COMPONENT");
    }
    static final class Scope {
        final Context context;final File folder;final JSONObject intent;final String run,operation,kind,root,title,id,filename;final int appUid,docsUid;
        Scope(Context context,String run,String operation,boolean requirePrepared) throws Exception {
            this.context=context;this.run=uuid(run);folder=new File(parent(context,false),run);directory(folder);
            // Original immutable receipts remain readable even if later document I/O leaves a
            // partial/oversize payload. Listing/write/audit still enforce their full bounds.
            if(requirePrepared)inventory(folder);
            StructStat claim=Os.lstat(new File(folder,"scope.claimed").getPath());regular(claim);if(claim.st_size!=0)throw reject("CLAIM");
            intent=json(folder,"intent.json");validate(context,intent,false,false);
            if(!run.equals(intent.getString("run")))throw reject("RUN");this.operation=intent.getString("operationId");
            if(operation!=null&&!uuid(operation).equals(this.operation))throw reject("OPERATION");
            kind=intent.getString("kind");appUid=intent.getInt("appUid");docsUid=intent.getInt("docsUid");
            root="p7-root-"+run;title="AH_P7_"+run.replace("-","");id="p7-"+(kind.equals("IMPORT")?"input":"output")+"-"+run;filename="agent-hub-p7-"+kind.toLowerCase(Locale.ROOT)+"-"+run+".codexmigration";
            if(requirePrepared) {
                JSONObject scope=json(folder,"scope.json");exact(scope,new HashSet<>(Arrays.asList("schema","run","kind","appUid","docsUid")));
                if(scope.getInt("schema")!=1||!scope.getString("run").equals(run)||!scope.getString("kind").equals(kind)||scope.getInt("appUid")!=appUid||scope.getInt("docsUid")!=docsUid)throw reject("SCOPE");
                JSONObject receipt=json(folder,"receipt.json");
                if(!receipt.getString("status").equals("PREPARED")||!receipt.getString("run").equals(run)||!receipt.getString("operationId").equals(this.operation)||
                    !receipt.getString("intentSha256").equals(hash(canonical(intent)))||!receipt.getBoolean("scopePublished")||!receipt.getBoolean("directorySynced"))throw reject("RECEIPT");
                for(String name:inventory(folder))if(name.endsWith(".pending"))throw reject("PARTIAL");
            }
        }
        File payload(){return new File(folder,kind.equals("IMPORT")?"input.bin":"output.bin");}
        File pendingPayload(){return new File(folder,kind.equals("IMPORT")?"input.pending":"output.pending");}
        JSONObject payloadAck() throws Exception {
            JSONObject ack=json(folder,"payload-ack.json");
            Set<String> fields=new HashSet<>(Arrays.asList("schema","run","kind","operationId","documentId","authority","intentSha256","providerProcess",
                "status","size","sha256","eofObserved","receiverFdClosed","sinkFdClosed","fileSynced","directorySynced","payloadPublished","expectedFingerprintBound","peerStatusIsAcknowledgement"));
            exact(ack,fields);
            if(!ack.getString("schema").equals("p7-main-saf-document-event-v4")||!ack.getString("status").equals("PAYLOAD_COMMITTED")||
                !ack.getString("run").equals(run)||!ack.getString("operationId").equals(operation)||!ack.getString("kind").equals(kind)||
                !ack.getString("documentId").equals(id)||!ack.getString("authority").equals(AUTHORITY)||!ack.getString("intentSha256").equals(hash(canonical(intent)))||
                !ack.getBoolean("eofObserved")||!ack.getBoolean("receiverFdClosed")||!ack.getBoolean("sinkFdClosed")||!ack.getBoolean("fileSynced")||
                !ack.getBoolean("directorySynced")||!ack.getBoolean("payloadPublished")||ack.getBoolean("peerStatusIsAcknowledgement"))throw reject("PAYLOAD_ACK");
            long size=ack.getLong("size");String sha=ack.getString("sha256");
            if(size<=0||size>MAX||!sha.matches("[a-f0-9]{64}")||ack.getBoolean("expectedFingerprintBound")!=kind.equals("IMPORT"))throw reject("PAYLOAD_ACK");
            if(kind.equals("IMPORT")&&(size!=intent.getLong("expectedPayloadBytes")||!sha.equals(intent.getString("expectedPayloadSha256"))))throw reject("PAYLOAD_ACK");
            StructStat st=Os.lstat(payload().getPath());regular(st);if(st.st_size!=size)throw reject("PAYLOAD_ACK_FILE");return ack;
        }
        void event(String name,JSONObject value) throws Exception {
            if(!Arrays.asList("create","open","payload-ack","payload-failed","read-open","read-close").contains(name))throw reject("EVENT");
            writeNew(new File(folder,name+".claimed"),new byte[0]);
            value.put("schema","p7-main-saf-document-event-v4").put("run",run).put("kind",kind).put("operationId",operation)
                .put("documentId",id).put("authority",AUTHORITY).put("intentSha256",hash(canonical(intent)))
                .put("providerProcess",facts(kind.equals("IMPORT")&&(name.equals("payload-ack")||name.equals("payload-failed"))?TEST+":p7MainSafControlV4":TEST+":p7MainSafV3"));atomicJson(folder,name,value);
        }
    }
    static Scope documentScope(Context context,String id) throws Exception {
        String prefix=id.startsWith("p7-root-")?"p7-root-":id.startsWith("p7-output-")?"p7-output-":id.startsWith("p7-input-")?"p7-input-":null;
        if(prefix==null)throw reject("DOCUMENT_ID");Scope s=new Scope(context,uuid(id.substring(prefix.length())),null,true);
        if(!id.equals(s.root)&&!id.equals(s.id))throw reject("DOCUMENT_ID");return s;
    }
}
