package com.agenthub.android

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** New natural proposal producer. Existing-key store5 is a parent adoption gate, not a claimed pass.
 * The independent observer must already be waiting. No Activity/start/send-to-target/permission change. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion=31,maxSdkVersion=33)
class NativePublicProcessNaturalOsProducerTest {
    @get:Rule val timeout: org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Test fun publishOneOriginalSdkNoticeAndSealNaturalPermit(): Unit=runBlocking {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Explicit natural lease/attempt; skip is not OS proof",args.getString("p9NaturalOsLease")!=null && args.getString("p9NaturalOsAttempt")!=null)
        val lease=NativeChatNotificationColdPlan.uuid(checkNotNull(args.getString("p9NaturalOsLease")))
        val attempt=NativeChatNotificationColdPlan.uuid(checkNotNull(args.getString("p9NaturalOsAttempt")))
        NativeNaturalOsContract.guard(context);var stage="preflight";var failure:Throwable?=null;var posted=false;var armed=false;var custodySent=false
        var bindings:JSONObject?=null;var publicationHash:String?=null
        var defaultsBefore:NativeNaturalFamilyInventory.Report?=null;var writerVisibilityAcknowledged=false
        try {
            stage="default-families-before"
            defaultsBefore=withContext(Dispatchers.IO){NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile,true)}
            require(defaultsBefore!!.complete && defaultsBefore!!.contentHashes){"Incomplete original default-family inventory; no producer seed"}
            stage="preflight"
            instrumentation.runOnMainSync {require(Stage.values().flatMap{ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.none{it is MainActivity})}
            require(context.checkSelfPermission(NativeNaturalOsContract.PERMISSION)==PackageManager.PERMISSION_GRANTED)
            require(context.packageManager.checkSignatures(context.packageName,NativeNaturalOsContract.EXAMINER)==PackageManager.SIGNATURE_MATCH)
            val examiner=context.packageManager.getApplicationInfo(NativeNaturalOsContract.EXAMINER,0);require(examiner.uid!=Process.myUid() && examiner.splitSourceDirs.isNullOrEmpty())
            val examinerHash=NativeChatNotificationNaturalFiles.sha(File(examiner.sourceDir).readBytes())
            val manager=context.getSystemService(NotificationManager::class.java);require(manager.areNotificationsEnabled() && manager.notificationDelegate==null)
            if(android.os.Build.VERSION.SDK_INT>=33)require(context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
            withContext(Dispatchers.IO){
                for(name in listOf(NativeNaturalOsContract.PERMIT_ROOT,NativeNaturalOsContract.ENTRY_ROOT,NativeChatNotificationColdRouterFiles.NAMESPACE,"P9-native-system-$lease"))require(Files.notExists(File(context.noBackupFilesDir,name).toPath(),NOFOLLOW_LINKS))
                NativeChatNotificationColdStart.requireHooks(context);NativeChatNotificationColdStart.requireNaturalExistingKeyOnly(context)
            }
            val started=SystemClock.elapsedRealtime()
            stage="seed-original";val plan=withContext(Dispatchers.IO){NativeChatNotificationColdStart.seedWriter(context,lease,NativeChatNotificationColdCase.ORIGINAL)}
            val scope=NativeChatNotificationAcceptanceScope.create(context,lease);val storage=withContext(Dispatchers.IO){NativeNaturalOsContract.storage(context,lease)}
            val receipt=withContext(Dispatchers.IO){NativeChatNotificationColdStart.originalReceipt(storage,plan)}
            val text=NativeL10n(context,"en");val computer="P9 natural $lease";val presentation=NativeChatNoticePresentation.create(receipt.event,computer,true){text.tr(it)}
            val platform=NativeChatNotificationPlatform.forAcceptance(context,text,scope,storage)
            val channel=withContext(Dispatchers.Main.immediate){checkNotNull(platform.prepareAsync(receipt.event.kind,"none",false){true})}
            val tag=scope.prefix+NativeChatNotificationWire.hash(NativeChatNotificationCodec.target(receipt.event.target).toString())+"."+receipt.event.id
            val permission=NativeNaturalOsContract.permission(context,channel);require(NativeNaturalOsContract.permitted(permission))
            val others=NativeNaturalOsContract.others(context,tag)
            val before=withContext(Dispatchers.IO){NativeNaturalOsContract.inventory(context,lease)}
            withContext(Dispatchers.IO){storage.write(NativeNaturalOsContract.RECORD,JSONObject().put("version",1).put("phase","post-unknown").put("publication",JSONObject.NULL).toString())}
            stage="one-platform-post"
            val preferences=withContext(Dispatchers.IO){NativeChatNotificationStore(storage).preferences()}
            withContext(Dispatchers.Main.immediate){platform.post(receipt,preferences,computer,0,channel)}
            val row=withContext(Dispatchers.IO){withTimeout(10000){while(manager.activeNotifications.none{it.tag==tag && it.id==1})delay(20)};manager.activeNotifications.single{it.tag==tag && it.id==1}}
            val notice=row.notification;val pending=checkNotNull(notice.contentIntent)
            require(row.packageName==context.packageName && row.uid==Process.myUid() && notice.channelId==channel && notice.visibility==Notification.VISIBILITY_PRIVATE)
            require(pending.creatorUid==Process.myUid() && pending.creatorPackage==context.packageName && pending.isActivity && pending.isImmutable)
            val canonical=PendingIntent.getActivity(context,0,NativeChatNotificationPlatform.openIntent(context,plan.ticket),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            require(canonical!=null && canonical==pending && notice.flags and Notification.FLAG_AUTO_CANCEL!=0)
            require(notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString()==presentation.title && notice.extras.getCharSequence(Notification.EXTRA_TEXT).toString()==presentation.summary && notice.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString()==presentation.metadata)
            posted=true;stage="seal-published"
            val installed=withContext(Dispatchers.IO){NativeChatNotificationNaturalStore.installed(context)}
            val publication=JSONObject().put("version",1).put("lease",lease).put("attempt",attempt).put("plan",plan.json()).put("planHash",plan.fingerprint).put("receiptHash",plan.receiptHash)
                .put("authority",plan.authority).put("tokenHash",NativeChatNotificationWire.hash(plan.storedToken)).put("target",NativeChatNotificationCodec.target(plan.target))
                .put("appHash",installed.appHash).put("runnerHash",installed.runnerHash).put("signerHash",installed.signerSetHash).put("examinerHash",examinerHash).put("uid",installed.uid)
                .put("bootCount",android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT)).put("publishedElapsed",SystemClock.elapsedRealtime())
                .put("row",JSONObject().put("tag",tag).put("id",1).put("channel",channel).put("title",presentation.title).put("summary",presentation.summary).put("metadata",presentation.metadata)
                    .put("postTime",row.postTime).put("creatorPackage",pending.creatorPackage).put("creatorUid",pending.creatorUid).put("immutable",true))
                .put("permission",permission).put("othersHash",others).put("baseFiles",JSONObject(before)).toString()
            NativeNaturalOsContract.validate(publication)
            // Export only fixed synthetic correlation fields; never the raw sealed record or token.
            publicationHash=NativeChatNotificationWire.hash(publication)
            bindings=JSONObject().put("planHash",plan.fingerprint).put("ticket",plan.ticket).put("receiptHash",plan.receiptHash).put("target",NativeChatNotificationCodec.target(plan.target))
                .put("appHash",installed.appHash).put("runnerHash",installed.runnerHash).put("signerHash",installed.signerSetHash).put("examinerHash",examinerHash).put("uid",installed.uid)
                .put("writerPid",plan.writerPid).put("writerNonce",plan.writerNonce).put("publicationSha256",publicationHash)
            val challenge=ByteArray(32).also{java.security.SecureRandom().nextBytes(it)}
            withContext(Dispatchers.IO){
                require(NativeNaturalOsContract.inventory(context,lease)==before)
                storage.write(NativeNaturalOsContract.RECORD,JSONObject().put("version",1).put("phase","published").put("publication",publication).toString())
                val original=NativeChatNotificationNaturalPermit.Original(lease,attempt,plan.ticket,plan.fingerprint,plan.receiptHash,plan.authority,NativeChatNotificationWire.hash(plan.storedToken),installed.appHash,installed.runnerHash,installed.signerSetHash,
                    NativeChatNotificationNaturalFiles.sha(challenge),NativeChatNotificationWire.hash(publication),installed.uid,android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT),
                    plan.issuedAt,started,started+NativeChatNotificationColdPlan.TTL,plan.writerPid,plan.writerNonce)
                NativeChatNotificationNaturalStore.forAcceptance(context,lease).arm(original,challenge);challenge.fill(0)
            }
            armed=true;stage="one-custody-broadcast"
            val acknowledgement=CompletableDeferred<Pair<Int,String?>>()
            context.sendOrderedBroadcast(Intent(NativeNaturalOsContract.CUSTODY).setPackage(NativeNaturalOsContract.EXAMINER).putExtra("publication",publication).putExtra("pendingIntent",pending),NativeNaturalOsContract.PERMISSION,
                object:android.content.BroadcastReceiver(){override fun onReceive(c:android.content.Context,i:Intent){acknowledgement.complete(resultCode to resultData)}},
                android.os.Handler(android.os.Looper.getMainLooper()),android.app.Activity.RESULT_CANCELED,null,null)
            custodySent=true
            val response=acknowledgement.await() // bounded by this selector's existing overall timeout; never resend on unknown
            require(response.first==android.app.Activity.RESULT_OK && response.second==NativeChatNotificationWire.hash("p9-visible-v1:$lease:$attempt:${plan.fingerprint}:${installed.uid}:${plan.writerPid}"))
            writerVisibilityAcknowledged=true
            require(NativeNaturalOsContract.permission(context,channel).toString()==permission.toString() && NativeNaturalOsContract.others(context,tag)==others)
        } catch(error:Throwable){failure=error;throw error}
        finally {withContext(NonCancellable+Dispatchers.IO){
            val afterResult=runCatching{NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile,true)};val defaultsAfter=afterResult.getOrNull()
            val comparable=defaultsBefore?.complete==true && defaultsBefore?.contentHashes==true && defaultsAfter?.complete==true && defaultsAfter?.contentHashes==true
            val unchanged=comparable && defaultsBefore?.digest!=null && defaultsBefore?.digest==defaultsAfter?.digest
            val inventoryFailure=afterResult.exceptionOrNull() ?: if(unchanged)null else IllegalStateException("Producer default-family inventory incomplete or changed")
            NativeNaturalOsContract.proof(context,lease,attempt,"producer",JSONObject().put("passed",failure==null && inventoryFailure==null).put("stage",stage).put("sdkOriginalPublished",posted).put("permitArmed",armed).put("custodySentOnce",custodySent)
                .put("bindings",bindings ?: JSONObject.NULL).put("writerVisibilityAcknowledged",writerVisibilityAcknowledged)
                .put("familiesBefore",defaultsBefore?.let{JSONObject(it.summary())} ?: JSONObject.NULL).put("familiesAfter",defaultsAfter?.let{JSONObject(it.summary())} ?: JSONObject.NULL)
                .put("defaultInventoryComplete",comparable).put("defaultUnchanged",if(comparable)unchanged else JSONObject.NULL).put("defaultInventoryFailureType",inventoryFailure?.javaClass?.name ?: JSONObject.NULL)
                .put("activityEntries",NativeChatNotificationColdStart.activityEntries.get()).put("defaultSelections",NativeChatNotificationColdStart.defaultSelections.get()).put("failureType",failure?.javaClass?.name ?: inventoryFailure?.javaClass?.name ?: JSONObject.NULL)
                .put("boundary","Synthetic original event/local ports; actual SDK posting only. No natural click/Delivery.finish/vendor/Host/model result."))
            if(failure==null && inventoryFailure!=null)throw inventoryFailure
        }}
    }
}
