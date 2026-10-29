package com.agenthub.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions

/** Proposal only. Fixed acceptance publication/evidence. No arbitrary filename, endpoint or method. */
internal object NativeNaturalOsContract {
    const val EXAMINER = "com.easyai.agenthub.android.acceptance.coldexaminer"
    const val PERMISSION = "com.easyai.agenthub.android.acceptance.permission.P9_NATURAL_OBSERVER"
    const val CUSTODY = "com.agenthub.android.P9_NATURAL_CUSTODY_V1"
    const val RECORD = "p9-natural-os-publication-v1"
    const val PERMIT_ROOT = NativeNaturalEntryFence.PERMIT_ROOT
    const val ENTRY_ROOT = NativeNaturalEntryFence.ENTRY_ROOT
    fun guard(context: Context) { NativeChatNotificationNaturalStore.guard(context); require(Build.VERSION.SDK_INT >= 31) }
    fun storage(context: Context, lease: String): NativeSessionStorage {
        guard(context); NativeChatNotificationColdPlan.uuid(lease); inventory(context, lease)
        return NativeSessionStorage(context,"P9-native-system-$lease",existingKeyOnly=true)
    }
    fun inventory(context: Context, lease: String): Map<String,String> {
        NativeChatNotificationColdPlan.uuid(lease); val parent = context.noBackupFilesDir.canonicalFile; val root = File(parent,"P9-native-system-$lease")
        require(Files.isDirectory(root.toPath(),NOFOLLOW_LINKS) && !Files.isSymbolicLink(root.toPath()) && root.canonicalFile.parentFile==parent)
        val files=checkNotNull(root.listFiles());require(files.size in 1..16 && files.sumOf{it.length()}<=2_097_152)
        require(files.all{Files.isRegularFile(it.toPath(),NOFOLLOW_LINKS) && !Files.isSymbolicLink(it.toPath()) && it.name.matches(Regex("[a-f0-9]{64}\\.bin")) && it.length() in 29..524288})
        return files.filterNot{it.name==NativeChatNotificationNaturalFiles.sha(RECORD.toByteArray())+".bin"}.associate{it.name to NativeChatNotificationNaturalFiles.sha(it.readBytes())}
    }
    fun permission(context: Context, channel: String): JSONObject {
        val manager=context.getSystemService(NotificationManager::class.java);val value=checkNotNull(manager.getNotificationChannel(channel))
        return JSONObject().put("granted",Build.VERSION.SDK_INT<33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
            .put("appEnabled",manager.areNotificationsEnabled()).put("importance",value.importance).put("badge",value.canShowBadge())
            .put("sound",value.sound?.toString() ?: "none").put("delegated",manager.notificationDelegate!=null)
    }
    fun permitted(value: JSONObject) = value.getBoolean("granted") && value.getBoolean("appEnabled") && !value.getBoolean("delegated") && value.getInt("importance")>0
    fun validate(raw: String): JSONObject {
        require(raw.toByteArray().size in 1..16384);val v=JSONObject(raw)
        NativeChatNotificationWire.keys(v,setOf("version","lease","attempt","plan","planHash","receiptHash","authority","tokenHash","target","appHash","runnerHash","signerHash","examinerHash","uid","bootCount","publishedElapsed","row","permission","othersHash","baseFiles"))
        require(v.getInt("version")==1);NativeChatNotificationColdPlan.uuid(v.getString("attempt"))
        val plan=NativeChatNotificationColdPlan.decode(v.getJSONObject("plan"));require(plan.case==NativeChatNotificationColdCase.ORIGINAL && v.getString("lease")==plan.lease)
        require(v.getString("planHash")==plan.fingerprint && v.getString("receiptHash")==plan.receiptHash && v.getString("authority")==plan.authority && v.getString("tokenHash")==NativeChatNotificationWire.hash(plan.storedToken))
        require(v.getJSONObject("target").toString()==NativeChatNotificationCodec.target(plan.target).toString())
        require(v.getString("appHash")==plan.appHash && v.getString("runnerHash")==plan.runnerHash && chatNoticeHash(v.getString("signerHash")))
        val row=v.getJSONObject("row");NativeChatNotificationWire.keys(row,setOf("tag","id","channel","title","summary","metadata","postTime","creatorPackage","creatorUid","immutable"))
        require(row.getInt("id")==1 && row.getString("channel").startsWith("native-chat-acceptance-v1.${plan.lease}."))
        require(row.getString("metadata").contains(plan.lease) && row.getString("creatorPackage")==NativeChatNotificationNaturalPermit.PACKAGE && row.getInt("creatorUid")==v.getInt("uid") && row.getBoolean("immutable"))
        NativeChatNotificationWire.keys(v.getJSONObject("permission"),setOf("granted","appEnabled","importance","badge","sound","delegated"))
        require(chatNoticeHash(v.getString("examinerHash")) && chatNoticeHash(v.getString("othersHash")))
        require(v.getInt("bootCount")>=0 && v.getLong("publishedElapsed")>=0)
        require(v.getJSONObject("baseFiles").length() in 1..15)
        val base=v.getJSONObject("baseFiles");require(base.keys().asSequence().all{it.matches(Regex("[a-f0-9]{64}\\.bin")) && chatNoticeHash(base.getString(it))})
        return v
    }
    fun load(context: Context,plan: NativeChatNotificationColdPlan): Pair<String,JSONObject> {
        val s=storage(context,plan.lease);val value=JSONObject(checkNotNull(s.read(RECORD)))
        NativeChatNotificationWire.keys(value,setOf("version","phase","publication"));require(value.getInt("version")==1 && value.getString("phase")=="published")
        val raw=value.getString("publication");val v=validate(raw);require(v.getString("planHash")==plan.fingerprint)
        val expected=v.getJSONObject("baseFiles");val current=inventory(context,plan.lease)
        require(current==expected.keys().asSequence().associateWith{expected.getString(it)})
        val receipt=NativeChatNotificationColdStart.originalReceipt(s,plan)
        require(v.getJSONObject("row").getString("tag")==NativeChatNotificationAcceptanceScope.create(context,plan.lease).prefix+NativeChatNotificationWire.hash(NativeChatNotificationCodec.target(receipt.event.target).toString())+"."+receipt.event.id)
        return raw to v
    }
    fun others(context: Context,tag: String)=NativeChatNotificationWire.hash(context.getSystemService(NotificationManager::class.java).activeNotifications.filterNot{it.tag==tag && it.id==1}.map{"${it.tag}:${it.id}:${it.postTime}"}.sorted().joinToString("\n"))
    fun sync(directory: File) { val fd=android.system.Os.open(directory.absolutePath,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW,0)
        try{require(android.system.OsConstants.S_ISDIR(android.system.Os.fstat(fd).st_mode));android.system.Os.fsync(fd)}finally{android.system.Os.close(fd)} }
    fun append(file: File, value: JSONObject) {
        val bytes=value.toString().toByteArray();require(bytes.size<=32768)
        val parent=checkNotNull(file.parentFile)
        java.nio.channels.FileChannel.open(file.toPath(),setOf<java.nio.file.OpenOption>(CREATE_NEW,WRITE,NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use{out->
            sync(parent);val buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())check(out.write(buffer)>0);out.force(true);sync(parent)
        }
    }
    fun beginEntry(context: Context, intentHash: String): File {
        guard(context)
        val bytes=JSONObject().put("version",1).put("pid",android.os.Process.myPid()).put("nonce",NativeChatNotificationColdStart.processNonce)
            .put("intentHash",intentHash).put("phase","entry-attempted").toString().toByteArray()
        return NativeNaturalEntryFence.claim(context.noBackupFilesDir.canonicalFile,bytes,::sync)
    }
    fun proof(context: Context,lease: String,attempt: String,name: String,value: JSONObject) {
        guard(context);NativeChatNotificationColdPlan.uuid(lease);NativeChatNotificationColdPlan.uuid(attempt);require(name in setOf("producer","first-open","new-draft","failed"))
        var folder=context.filesDir.canonicalFile
        for(part in listOf("acceptance","P9-natural-os",lease,attempt)){
            val next=File(folder,part);if(Files.notExists(next.toPath(),NOFOLLOW_LINKS))Files.createDirectory(next.toPath(),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            require(Files.isDirectory(next.toPath(),NOFOLLOW_LINKS) && !Files.isSymbolicLink(next.toPath()) && next.canonicalFile.parentFile==folder);folder=next
        }
        append(File(folder,"$name.json"),value.put("version",1).put("lease",lease).put("attempt",attempt).put("pid",android.os.Process.myPid()).put("nonce",NativeChatNotificationColdStart.processNonce)
            .put("bootCount",android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT)).put("elapsed",android.os.SystemClock.elapsedRealtime())
            .put("requiresIndependentObserver",true).put("fullDevVendorHostModel",false))
    }
}
