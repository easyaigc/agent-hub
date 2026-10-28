package com.agenthub.android

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Synthetic original records only; never invokes P9 identity creation, a monitor or a Host. */
internal object NotificationArchiveFixtures {
    class Identity(val raw: String,val device: String,val publicKey: String,val fingerprint: String) {
        override fun toString()="SyntheticNotificationIdentity(redacted)"
    }
    class Authority(val raw: String,val fingerprint: String) {override fun toString()="SyntheticAuthority(redacted)"}
    fun hash(raw: String)=NativeChatNotificationWire.hash(raw)
    fun canonical(value: Any)=NativeMigrationRecoveryCanonical.value(value)
    fun identity(bound: Boolean=true): Identity {
        val key=X25519PrivateKeyParameters(SecureRandom());val secret=key.encoded
        val public=Base64.getEncoder().encodeToString(key.generatePublicKey().encoded);val device="android-"+UUID.randomUUID()
        val raw=try{JSONObject().put("version",1).put("deviceId",device).put("publicKey",public).put("privateKey",Base64.getEncoder().encodeToString(secret)).put("journalBound",bound).toString()}finally{secret.fill(0)}
        return Identity(raw,device,public,hash(public))
    }
    fun authority(client: MobileClient): Authority {
        val credential="synthetic-original-credential-"+UUID.randomUUID();val target=NativeChatNoticeTarget.from(client.scope!!);val certificate=client.codexDirect?.fingerprint?.lowercase().orEmpty()
        val epoch=NativeMigrationRecoveryCanonical.hash(JSONObject().put("domain","agent-hub.chat-notice-authority.v1").put("target",NativeChatNotificationCodec.target(target))
            .put("certificate",certificate).put("credential",hash(credential)))
        val fingerprint=hash(listOf("codex",target.hostId,target.workspaceId,target.runtimeId,epoch,certificate,hash(credential)).joinToString("\u0000"))
        return Authority(JSONObject().put("version",1).put("authorizations",JSONArray().put(JSONObject().put("client",encodeClient(client)).put("credential",credential).put("epoch",epoch).put("authorization",fingerprint))).toString(),fingerprint)
    }
    private fun event(target: NativeChatNoticeTarget,suffix: String)=NativeChatNotificationCodec.event(NativeChatNotificationEvent(hash("archive-event-$suffix"),target,
        NativeChatNoticeKind.UNCERTAIN,"2026-10-29T00:00:00Z","synthetic-thread",operationId="original-business-unknown"))
    fun v1(identity: Identity,target: NativeChatNoticeTarget,authorization: String,processing: Boolean=false): String {
        val preferences=NativeChatNotificationCodec.preferences(NativeChatNotificationPreferences(enabled=true,revision="original-v1",generation=7))
        val operation=JSONObject().put("id",UUID.randomUUID().toString()).put("kind","register").put("authorization",authorization).put("preferences",preferences)
            .put("state",if(processing)"confirmed"else"unknown").put("at",1L).put("fingerprint",hash(listOf("register",authorization,hash(canonical(preferences))).joinToString("\u0000")))
        if(processing)operation.put("responseState","ready").put("responseDelivery","foreground-only")
        val outcomes=JSONArray();val ids=JSONArray()
        if(processing)for(state in listOf("pending","processing")){val e=event(target,"v1-$state");ids.put(e.getString("id"));outcomes.put(JSONObject().put("event",e).put("fingerprint",hash(canonical(e))).put("state",state).put("at",2L))}
        val row=JSONObject().put("target",NativeChatNotificationCodec.target(target)).put("authorization",authorization).put("preferences",preferences).put("revoked",false).put("cursor","")
            .put("mutations",JSONArray().put(operation)).put("outcomes",outcomes).put("batch",if(!processing)JSONObject.NULL else JSONObject().put("id",UUID.randomUUID().toString())
                .put("before","").put("after",ids.getString(ids.length()-1)).put("revision","original-v1").put("authorization",authorization).put("ids",ids).put("activeIds",ids))
        return JSONObject().put("version",1).put("deviceId",identity.device).put("identity",identity.fingerprint).put("subscriptions",JSONArray().put(row)).toString()
    }
    private fun intent(identity: Identity,target: NativeChatNoticeTarget,preferences: JSONObject,epoch: String,kind: String,gap: JSONObject?=null): JSONObject {
        val raw=JSONObject().put("version",2).put("operationId",UUID.randomUUID().toString()).put("epoch",epoch).put("deviceId",identity.device)
            .put("target",NativeChatNotificationCodec.target(target)).put("publicKey",identity.publicKey).put("preferences",preferences).put("generation",1L).put("kind",kind)
        if(gap!=null)raw.put("gap",gap);raw.put("fingerprint",NativeMigrationRecoveryCanonical.hash(raw));return raw
    }
    fun v2(identity: Identity,target: NativeChatNoticeTarget,authorization: String,mode: String="unknown"): String {
        val preferences=NativeChatNotificationCodec.preferences(NativeChatNotificationPreferences(enabled=true,revision="original-v2",generation=8))
        val epoch=UUID.randomUUID().toString();val original=intent(identity,target,preferences,epoch,"register")
        val operations=JSONArray();val state=if(mode=="unknown")"unknown"else if(mode in setOf("reserved","rejected"))mode else"applied"
        val receipt=if(state=="unknown")JSONObject.NULL else JSONObject().put("version",2).put("epoch",epoch).put("intent",original).put("stage",state).also {r->
            if(state=="rejected")r.put("reason","synthetic-original-rejection")
            if(state=="applied")r.put("status",JSONObject().put("version",1).put("target",NativeChatNotificationCodec.target(target)).put("delivery","foreground-only").put("state","ready").put("revision","original-v2").put("publicKey",identity.publicKey))
        }
        operations.put(JSONObject().put("original",original).put("authorization",authorization).put("state",state).put("receipt",receipt))
        val outcomes=JSONArray();val items=JSONArray();val ids=JSONArray();val gaps=JSONArray();var batch: Any=JSONObject.NULL;var reset: String?=null
        if(mode in setOf("live","reset")) {
            for((index,result)in listOf("pending","processing").withIndex()) {
                val e=event(target,"v2-$mode-$result");val fingerprint=NativeMigrationRecoveryCanonical.hash(e);ids.put(e.getString("id"))
                outcomes.put(JSONObject().put("original",e).put("fingerprint",fingerprint).put("state",result).put("at",3L))
                items.put(JSONObject().put("sequence",index+1).put("event",e).put("fingerprint",fingerprint).put("disposition","eligible"))
            }
            val page=JSONObject().put("version",2).put("epoch",epoch).put("deviceId",identity.device).put("target",NativeChatNotificationCodec.target(target)).put("generation",1L)
                .put("kind","page").put("mode",mode).put("baseline",2L).put("after",0L).put("next",2L).put("through",2L).put("hasMore",false)
                .put("items",items).put("activeIds",ids).put("publicKey",identity.publicKey).put("preferencesFingerprint",NativeMigrationRecoveryCanonical.hash(preferences))
            if(mode=="reset") {
                val gap=JSONObject().put("epoch",epoch).put("after",0L).put("floor",1L).put("head",2L).put("acceptanceId",UUID.randomUUID().toString())
                val resetIntent=intent(identity,target,preferences,epoch,"feed-reset",gap);reset=resetIntent.getString("operationId")
                val snapshotHash=NativeMigrationRecoveryCanonical.hash(JSONArray(outcomes.objects().map {it.getJSONObject("original")}))
                val snapshot=JSONObject().put("id",reset).put("hash",snapshotHash).put("count",2L).put("baseline",2L).put("expiresAt",100_000L)
                operations.put(JSONObject().put("original",resetIntent).put("authorization",authorization).put("state","applied").put("receipt",JSONObject().put("version",2).put("epoch",epoch).put("intent",resetIntent).put("stage","applied").put("snapshot",snapshot)))
                gaps.put(JSONObject(gap.toString()).put("operationId",reset));page.put("resetId",reset).put("snapshotHash",snapshotHash)
            }
            batch=JSONObject().put("id",UUID.randomUUID().toString()).put("operationId",original.getString("operationId")).put("page",page)
                .put("decisions",JSONObject(ids.strings().associateWith {"eligible"})).put("activeIds",ids)
        }
        val row=JSONObject().put("target",NativeChatNotificationCodec.target(target)).put("operations",operations).put("epoch",if(mode=="live")epoch else JSONObject.NULL)
            .put("after",0L).put("through",if(mode=="live")2L else JSONObject.NULL).put("resetId",reset?:JSONObject.NULL).put("lastResetId",reset?:JSONObject.NULL)
            .put("resetAfter",0L).put("resetSequence",if(mode=="reset")ids else JSONArray()).put("batch",batch).put("outcomes",outcomes).put("gaps",gaps)
        return JSONObject().put("version",2).put("deviceId",identity.device).put("identity",identity.fingerprint).put("targets",JSONArray().put(row)).toString()
    }
}
