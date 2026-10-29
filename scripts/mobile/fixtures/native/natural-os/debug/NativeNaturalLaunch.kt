package com.agenthub.android

import android.content.Intent
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File
import java.util.WeakHashMap

/** Source32-only proposal. Not on the branch's App source path. Apply only after the native store5 gate. */
internal object NativeNaturalLaunch {
    private class Owner(val scope: CoroutineScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)) {
        var plan: NativeChatNotificationColdPlan?=null;var permit: NativeChatNotificationNaturalPermit.State?=null;var publication: JSONObject?=null
        var defaults: NativeNaturalFamilyInventory.Report?=null;var preparing=true;var complete=false;var ignored=0;var entryOwned=false
    }
    private val owners=WeakHashMap<MainActivity,Owner>()
    fun deferIfRequired(activity: MainActivity,restored: Boolean,resume: ()->Unit): Boolean {
        val context=activity.applicationContext
        val route=NativeNaturalEntryPolicy.route(BuildConfig.DEBUG && context.packageName==NativeChatNotificationNaturalPermit.PACKAGE,
            NativeChatNotificationColdStart.active!=null,NativeChatNotificationColdStart.naturalModeEntered){NativeNaturalEntryFence.hasMaterial(context.noBackupFilesDir)}
        if(route in setOf(NativeNaturalEntryPolicy.Route.ORDINARY,NativeNaturalEntryPolicy.Route.LEGACY_ACTIVE))return false
        val owner=Owner();owners[activity]=owner;val original=Intent(activity.intent);val hadRetained=activity.lastCustomNonConfigurationInstance!=null
        if(route==NativeNaturalEntryPolicy.Route.REJECT_NATURAL_RECREATION){
            owner.preparing=false
            activity.setContentView(android.widget.TextView(activity).apply{text="P9 natural recreation blocked; original attempt retained"})
            owner.scope.launch {withContext(NonCancellable+Dispatchers.IO){runCatching{
                NativeNaturalOsContract.append(File(context.noBackupFilesDir,NativeNaturalOsContract.ENTRY_ROOT+"/recreation-rejected.json"),JSONObject().put("phase","natural-recreation-rejected")
                    .put("pid",android.os.Process.myPid()).put("nonce",NativeChatNotificationColdStart.processNonce).put("retainedInputIgnored",restored || hadRetained))
            }}}
            return true
        }
        owner.scope.launch {
            try {
                withContext(NonCancellable+Dispatchers.IO) {
                    NativeNaturalOsContract.beginEntry(context,NativeChatNotificationWire.hash(listOf(original.action,original.dataString,original.component?.flattenToString()).joinToString("|")))
                    owner.entryOwned=true
                    require(!restored && !hadRetained)
                    NativeChatNotificationColdStart.requireNaturalExistingKeyOnly(context)
                    val plan=NativeChatNotificationColdStart.naturalArmedPlan(context);owner.plan=plan
                    require(NativeChatNotificationColdStart.strictTicket(context,original)==plan.ticket)
                    val (raw,published)=NativeNaturalOsContract.load(context,plan);owner.publication=published
                    val store=NativeChatNotificationNaturalStore.forAcceptance(context,plan.lease);val state=store.inspect();owner.permit=state
                    val p=state.original;require(state.phase==NativeChatNotificationNaturalPermit.Phase.ARMED && p.planHash==plan.fingerprint && p.ticket==plan.ticket && p.receiptHash==plan.receiptHash && p.authority==plan.authority)
                    require(p.bootCount==published.getInt("bootCount") && published.getLong("publishedElapsed") in p.issuedElapsed..p.expiresElapsed)
                    require(p.originalTokenHash==NativeChatNotificationWire.hash(plan.storedToken) && p.publishedOsHash==NativeChatNotificationWire.hash(raw) && p.signerSetHash==published.getString("signerHash"))
                    val nowPermission=NativeNaturalOsContract.permission(context,published.getJSONObject("row").getString("channel"))
                    require(NativeNaturalOsContract.permitted(nowPermission) && nowPermission.toString()==published.getJSONObject("permission").toString())
                    val defaults=NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile,true);require(defaults.complete);owner.defaults=defaults
                    require(NativeChatNotificationColdStart.prepareReader(context,plan.lease).fingerprint==plan.fingerprint)
                    // Natural request mode only. Instrumentation absence is established by the external UID observer, not attested here.
                    val observed=NativeChatNotificationNaturalPermit.Observation(p,"original",true,context.packageName,true,true,p.deviceChallengeHash,true,true,true,true,true,true,false,1,0,false,
                        android.provider.Settings.Global.getInt(context.contentResolver,android.provider.Settings.Global.BOOT_COUNT),System.currentTimeMillis(),SystemClock.elapsedRealtime(),android.os.Process.myPid(),NativeChatNotificationColdStart.processNonce)
                    val claim=store.claim(observed).state;owner.permit=claim
                    NativeNaturalOsContract.append(File(context.noBackupFilesDir,NativeNaturalOsContract.ENTRY_ROOT+"/bound-claim.json"),JSONObject().put("version",1).put("lease",plan.lease).put("attempt",p.attempt).put("planHash",plan.fingerprint).put("phase","claimed"))
                }
                ensureActive();require(!activity.isDestroyed && !activity.isChangingConfigurations)
                owner.preparing=false;resume()
            } catch(error: Throwable) { fail(activity,owner,error);if(!activity.isDestroyed)activity.setContentView(android.widget.TextView(activity).apply{text="P9 natural acceptance blocked"}) }
        }
        return true
    }
    fun rejectedIngress(activity: MainActivity) { owners[activity]?.let{it.ignored++} }
    fun owns(activity: MainActivity)=owners.containsKey(activity) || (NativeChatNotificationColdStart.active!=null && NativeChatNotificationColdStart.naturalModeEntered)
    fun blocksIngress(activity: MainActivity,ready: Boolean)=NativeNaturalEntryPolicy.blocksIngress(ready,owns(activity))
    fun canRetain(activity: MainActivity,ready: Boolean)=NativeNaturalEntryPolicy.canRetain(ready,owns(activity))
    fun created(activity: MainActivity) {
        val owner=owners[activity] ?: return;val plan=checkNotNull(owner.plan);val permit=checkNotNull(owner.permit);val context=activity.applicationContext
        owner.scope.launch {
            try {
                val binding=checkNotNull(NativeChatNotificationColdStart.active);require(binding.plan.fingerprint==plan.fingerprint && binding.session===activity.session)
                withTimeout(30000){while(activity.session.restoring || binding.coordinator?.openState!="opened"){require(!activity.isDestroyed);delay(25)}}
                require(activity.session.selectedThread==plan.originalThread && activity.session.text==plan.originalDraft && sameScope(activity.session.selectedClient.scope,binding.ports.scope))
                require(binding.ports.locatorCursors.size==12 && binding.ports.forbiddenCommands.get()==0 && binding.ports.notificationCommands.get()==0 && activity.session.operationRecords.isEmpty())
                withContext(NonCancellable+Dispatchers.IO){
                    val permission=NativeNaturalOsContract.permission(context,checkNotNull(owner.publication).getJSONObject("row").getString("channel"))
                    require(permission.toString()==owner.publication!!.getJSONObject("permission").toString() && NativeNaturalOsContract.permitted(permission))
                    require(NativeNaturalOsContract.others(context,owner.publication!!.getJSONObject("row").getString("tag"))==owner.publication!!.getString("othersHash"))
                    val receipt=NativeChatNotificationColdStart.originalReceipt(binding.storage,plan)
                    require(receipt.consumed && NativeChatRecoveryCanonical.hash(NativeChatNotificationCodec.receipt(receipt.copy(consumed=false)))==plan.receiptHash)
                    binding.storage.awaitQueuedWrites()
                    val defaults=NativeNaturalFamilyInventory.scan(context.noBackupFilesDir.canonicalFile,true);require(defaults.complete && defaults.digest==owner.defaults!!.digest)
                    val store=NativeChatNotificationNaturalStore.forAcceptance(context,plan.lease)
                    store.complete(NativeChatNotificationNaturalPermit.Completion(android.os.Process.myPid(),NativeChatNotificationColdStart.processNonce,SystemClock.elapsedRealtime(),plan.ticket,plan.receiptHash,
                        plan.target.hostId,plan.target.workspaceId,plan.target.runtimeId,true,1,0,0,0,NativeChatNotificationWire.hash(plan.originalDraft)))
                    NativeChatNotificationColdStart.completeNaturalFirstOpen(context,binding)
                    NativeNaturalOsContract.proof(context,plan.lease,permit.original.attempt,"first-open",JSONObject().put("phase","first-open-complete").put("appHash",plan.appHash).put("runnerHash",plan.runnerHash)
                        .put("planHash",plan.fingerprint).put("ticket",plan.ticket).put("receiptHash",plan.receiptHash).put("target",NativeChatNotificationCodec.target(plan.target))
                        .put("thread",plan.originalThread).put("scopeMatched",true).put("locatorPages",binding.ports.locatorCursors.size).put("durableClaimPhase","complete")
                        .put("activityEntries",NativeChatNotificationColdStart.activityEntries.get()).put("defaultSelections",NativeChatNotificationColdStart.defaultSelections.get())
                        .put("draftHash",NativeChatNotificationWire.hash(plan.originalDraft)).put("commands",0).put("ignoredBeforeSession",owner.ignored).put("defaultFamilyUnchanged",true))
                    owner.complete=true
                }
                val newer="Owned natural OS new draft ${plan.lease}"
                withTimeout(30000){while(activity.session.text!=newer){require(!activity.isDestroyed);delay(50)}}
                require(activity.session.selectedThread==plan.originalThread && sameScope(activity.session.selectedClient.scope,binding.ports.scope) && activity.session.operationRecords.isEmpty())
                require(binding.ports.forbiddenCommands.get()==0 && binding.ports.notificationCommands.get()==0 && NativeChatNotificationColdStart.defaultSelections.get()==0)
                withContext(Dispatchers.IO){NativeNaturalOsContract.proof(context,plan.lease,permit.original.attempt,"new-draft",JSONObject().put("phase","new-draft-observed").put("draftHash",NativeChatNotificationWire.hash(newer)).put("ticket",plan.ticket).put("commands",0).put("newIntentHandled",binding.newIntentHandled.get()))}
            } catch(error: Throwable){fail(activity,owner,error)}
        }
    }
    private suspend fun fail(activity: MainActivity,owner: Owner,error: Throwable) {
        val plan=owner.plan;val permit=owner.permit
        if(plan==null || permit==null){if(owner.entryOwned)withContext(NonCancellable+Dispatchers.IO){runCatching{NativeNaturalOsContract.append(File(activity.noBackupFilesDir,NativeNaturalOsContract.ENTRY_ROOT+"/failed.json"),JSONObject().put("phase","unresolved").put("failureType",error.javaClass.name).put("defaultSelections",NativeChatNotificationColdStart.defaultSelections.get()))}};return}
        withContext(NonCancellable+Dispatchers.IO){runCatching{NativeNaturalOsContract.proof(activity.applicationContext,plan.lease,permit.original.attempt,"failed",JSONObject().put("phase","unresolved").put("failureType",error.javaClass.name).put("firstOpenAlreadyComplete",owner.complete).put("defaultSelections",NativeChatNotificationColdStart.defaultSelections.get()))}}
    }
    fun destroyed(activity: MainActivity) { owners.remove(activity)?.scope?.cancel() }
}
