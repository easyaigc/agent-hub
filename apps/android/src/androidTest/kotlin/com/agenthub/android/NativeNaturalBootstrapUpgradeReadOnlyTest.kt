package com.agenthub.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** Separate current-upgrade receipt. Never initializes, reads through or writes NativeSessionStorage. */
@RunWith(AndroidJUnit4::class)
class NativeNaturalBootstrapUpgradeReadOnlyTest {
    @get:Rule(order=0) internal val evidence=NativeNaturalPreflightEvidence("bootstrap-upgrade")
    @get:Rule(order=1) val timeout:org.junit.rules.TestRule=org.junit.rules.Timeout.seconds(120)
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun keys(v:JSONObject,expected:Set<String>){require(v.keys().asSequence().toSet()==expected)}
    private fun uuid(s:String):String{require(UUID.fromString(s).toString()==s);return s}
    private fun hash(s:String):String{require(s.matches(Regex("[0-9a-f]{64}")));return s}
    private fun installed(v:JSONObject){keys(v,setOf("uid","appSha256","runnerSha256","signerSetSha256"));require(v.get("uid") is Int&&v.getInt("uid")>=10000);for(k in listOf("appSha256","runnerSha256","signerSetSha256"))hash(v.getString(k))}
    private fun same(a:JSONObject,b:JSONObject){for(k in listOf("uid","appSha256","runnerSha256","signerSetSha256"))require(a.get(k)==b.get(k))}
    @Test fun reconcileOriginalBootstrapAfterUpgradeReadOnly():Unit=runBlocking {withContext(Dispatchers.IO){
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue("Explicit frozen upgrade plan required; skip is not acceptance",args.getString("p9BootstrapUpgradePlanBase64")!=null&&args.getString("p9BootstrapUpgradePlanSha256")!=null)
        val encoded=checkNotNull(args.getString("p9BootstrapUpgradePlanBase64"));require(encoded.length in 1..11000&&encoded.matches(Regex("[A-Za-z0-9+/]*={0,2}")))
        val raw=java.util.Base64.getDecoder().decode(encoded);require(raw.size in 1..8192&&java.util.Base64.getEncoder().encodeToString(raw)==encoded)
        val planHash=hash(checkNotNull(args.getString("p9BootstrapUpgradePlanSha256")));require(NativeChatNotificationNaturalFiles.sha(raw)==planHash)
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw)).toString()
        val plan=JSONObject(text);keys(plan,setOf("version","action","bootstrapId","upgradeRun","intentSha256","completedSha256","familyDigest","originalInstalled","currentInstalled","originalDeviceProofSha256","originalBootCount","originalBootId"))
        require(plan.get("version") is Int&&plan.getInt("version")==1&&plan.getString("action")=="reconcile-original-after-upgrade-read-only")
        val id=uuid(plan.getString("bootstrapId"));val run=uuid(plan.getString("upgradeRun"));require(run!=id)
        evidence.lease=id;evidence.run=run;evidence.stage="upgrade-scope";NativeChatNotificationNaturalStore.guard(context)
        val old=plan.getJSONObject("originalInstalled");val current=plan.getJSONObject("currentInstalled");installed(old);installed(current)
        for(k in listOf("intentSha256","completedSha256","familyDigest","originalDeviceProofSha256"))hash(plan.getString(k))
        uuid(plan.getString("originalBootId"));require(plan.get("originalBootCount") is Int&&plan.getInt("originalBootCount")>=0)
        require(old.getInt("uid")==current.getInt("uid")&&old.getString("signerSetSha256")==current.getString("signerSetSha256"))
        require(old.getString("appSha256")!=current.getString("appSha256")||old.getString("runnerSha256")!=current.getString("runnerSha256"))
        val joined=JSONObject().put("planSha256",planHash).put("originalInstalled",old).put("currentInstalled",current).put("originalBootstrapId",id).put("upgradeRun",run)
            .put("normalProductWrites",0).put("originalIntentModified",false).put("originalReceiptModified",false)
        evidence.details=joined;evidence.bodyEntered=true
        evidence.stage="upgrade-no-activity";InstrumentationRegistry.getInstrumentation().runOnMainSync {check(Stage.values().flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}.none {it is MainActivity})}
        same(NativeNaturalPreflightEvidence.installed(context),current)
        evidence.stage="read-original-bootstrap";val root=File(context.noBackupFilesDir.canonicalFile,"P9-natural-bootstrap-v1")
        val fence=NativeNaturalBootstrapFence(root,NativeNaturalPreflightEvidence::sync);val (intentBytes,completedBytes)=fence.readCompleted()
        require(NativeChatNotificationNaturalFiles.sha(intentBytes)==plan.getString("intentSha256")&&NativeChatNotificationNaturalFiles.sha(completedBytes)==plan.getString("completedSha256"))
        val intent=JSONObject(intentBytes.toString(Charsets.UTF_8));val completed=JSONObject(completedBytes.toString(Charsets.UTF_8))
        require(intent.getInt("version")==1&&completed.getInt("version")==1&&intent.getString("bootstrapId")==id&&completed.getString("bootstrapId")==id)
        require(intent.getString("phase")=="normal-write-intent"&&completed.getString("phase")=="complete"&&intent.getInt("normalProductWritesPlanned")==1&&completed.getInt("normalProductWrites")==1)
        require(completed.getString("intentSha256")==plan.getString("intentSha256"));same(intent.getJSONObject("installed"),old);same(completed.getJSONObject("installed"),old)
        require(completed.getJSONObject("familiesAfter").getString("familyDigest")==plan.getString("familyDigest"))
        joined.put("originalIntentSha256",plan.getString("intentSha256")).put("originalReceiptSha256",plan.getString("completedSha256"))
        evidence.stage="upgrade-existing-key-only";val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        require(keys.getKey(NativeChatNotificationNaturalStore.KEY,null) is javax.crypto.SecretKey&&keys.aliases().asSequence().count()==1)
        evidence.stage="upgrade-boot-count";val boot=android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT)
        require(boot==plan.getInt("originalBootCount"))
        joined.put("observedBootCount",boot).put("originalBootId",plan.getString("originalBootId")).put("originalDeviceProofSha256",plan.getString("originalDeviceProofSha256"))
            .put("bootIdCheckedByNativeTest",false).put("bootIdParentPrePostCheckRequired",true).put("oldReceiptContainsBoot",false)
        evidence.stage="upgrade-complete-default-byte-inventory";val before=NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile,true);evidence.before=before
        require(before.complete&&before.contentHashes&&before.digest==plan.getString("familyDigest"))
        val reread=fence.readCompleted();require(reread.first.contentEquals(intentBytes)&&reread.second.contentEquals(completedBytes)&&File(root,NativeNaturalBootstrapFence.LOCK).length()==0L)
        val after=NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile,true);evidence.after=after
        require(after.complete&&after.contentHashes&&after.digest==before.digest);same(NativeNaturalPreflightEvidence.installed(context),current)
        require(android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT)==boot)
        joined.put("originalReceiptsRereadUnchanged",true).put("defaultBytesUnchanged",true).put("newStoreLeaseRequired",true).put("newNaturalAttemptRequired",true)
        evidence.stage="upgrade-reconciled-read-only";Unit
    }}
}
