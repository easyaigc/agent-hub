package com.agenthub.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

/** Public RFC7748 vector plus synthetic audit state. Actual native P8 writers; no network/signer. */
internal object NativeMigrationEasyWorkFixtures {
    class Seed(val storage: NativeSessionStorage,val identity: EasyWorkIdentity,val grantRef: String,val credentialName: String,val claimName: String,val coordinator: Any,val revoke: Any?,val originalNames: Set<String>)
    private fun call(value: Any,name: String,vararg args: Any?): Any?=value.javaClass.methods.single {it.name==name&&it.parameterTypes.size==args.size}.invoke(value,*args)
    private fun encoded(bytes: ByteArray)=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    private fun unsigned(value: BigInteger)=value.toByteArray().let {raw->if(raw.size==33)raw.copyOfRange(1,33)else ByteArray(32-raw.size)+raw}
    fun seed(context: Context,storage: NativeSessionStorage,mode: String="pending",refreshAudit: Boolean=false): Seed {
        require(mode in setOf("pending","confirmed","confirmed-missing","guard-only","active"))
        val vector=JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("easywork-crypto-vector.json").use {String(it.readBytes(),Charsets.UTF_8)}).getJSONObject("credential")
        val binding=JSONObject(vector.getJSONObject("binding").toString()).put("mobileDeviceId","p7-original-"+UUID.randomUUID())
        val identity=EasyWorkIdentity(binding.getString("mobileDeviceId"),vector.getString("privateKey"),binding.getString("mobilePublicKey"))
        storage.write(NativeMigrationEasyWorkOriginals.IDENTITY,identity.json().toString())
        val public=KeyPairGenerator.getInstance("EC").apply {initialize(ECGenParameterSpec("secp256r1"))}.generateKeyPair().public as ECPublicKey
        val jwk=JSONObject().put("kty","EC").put("crv","P-256").put("x",encoded(unsigned(public.w.affineX))).put("y",encoded(unsigned(public.w.affineY)))
        val thumb=encoded(java.security.MessageDigest.getInstance("SHA-256").digest(easyCanonical(jwk).toByteArray()))
        val grantId="grant-"+UUID.randomUUID();binding.put("deviceAuthorization",JSONObject().put("protocol","EWDAP/1").put("grantId",grantId).put("authKeyThumbprint",thumb).put("keyVersion",1))
        val grant=JSONObject().put("protocol","EWDAP/1").put("grantId",grantId).put("deviceId",identity.deviceId).put("binding",binding).put("target",binding.getJSONObject("target"))
            .put("keyVersion",1).put("scopes",JSONArray(listOf("relay:read","relay:write","runtime:read","runtime:control")))
            .put("accessToken","a".repeat(43)).put("refreshToken","b".repeat(43)).put("accessExpiresAt","2030-01-01T00:00:00Z").put("refreshExpiresAt","2031-01-01T00:00:00Z").put("futureGrant","original extension")
        val origin="https://relay.example.invalid";val ref=NativeMigrationEasyWorkOriginals.GRANT+easyHash((origin+"\n"+grantId).toByteArray())
        val type=Class.forName("com.agenthub.android.EasyWorkAuthorizationCoordinator");val coordinator=type.getDeclaredConstructor(NativeSessionStorage::class.java).newInstance(storage)
        call(coordinator,"install",ref,origin,grant)
        val initial=JSONObject(requireNotNull(storage.read(ref))).put("futureOwner","original unknown extension")
        initial.put("refreshOperation",JSONObject().put("operationId",UUID.randomUUID().toString()).put("token",grant.getString("refreshToken")).put("futurePending","original pending extension"));storage.write(ref,initial.toString())
        if(refreshAudit) {
            val unusable: (JSONObject)->String?={null}
            val access=requireNotNull(call(coordinator,"access",ref,unusable));val refresh=requireNotNull(call(access,"getRefresh"))
            val next=JSONObject(grant.toString()).put("accessToken","d".repeat(43)).put("refreshToken","e".repeat(43))
            call(coordinator,"refreshed",ref,refresh,next)
        }
        val verify: (JSONObject)->Unit={value->require(value.getJSONObject("grant").getString("grantId")==grantId)}
        val revoke=if(mode in setOf("guard-only","active"))null else call(coordinator,"revocation",ref,verify)
        if(mode in setOf("confirmed","confirmed-missing"))call(coordinator,"revoked",ref,requireNotNull(revoke))
        if(mode=="confirmed-missing")check(storage.read(ref)==null)
        if(mode=="guard-only")storage.remove(ref)
        val credential=JSONObject().put("binding",binding).put("privateKey",identity.privateKey).put("grantRef",ref).toString()
        val credentialName=if(mode=="guard-only")"" else actualCandidateWrite(storage,credential)
        val code="0123456789ABCDEF";val claim=NativeMigrationEasyWorkOriginals.CLAIM+easyHash((origin+"\n"+thumb+"\n"+code).toByteArray())
        if(mode!="guard-only") {
            val body=easyCanonical(JSONObject().put("operationId",UUID.randomUUID().toString()).put("connectionCode",code).put("device",JSONObject()
                .put("deviceId",identity.deviceId).put("platform","android").put("name","synthetic archive phone").put("publicKey",identity.publicKey).put("authPublicKey",jwk)))
            storage.write(claim,JSONObject().put("origin",origin).put("body",body).put("claimId",UUID.randomUUID().toString()).put("offerId","offer-"+UUID.randomUUID())
                .put("pendingToken","c".repeat(43)).put("expiresAt","2027-01-01T00:00:00Z").put("verificationCode","synthetic-code").put("grantRef",ref).toString())
        }
        val names=mutableSetOf(NativeMigrationEasyWorkOriginals.IDENTITY,NativeMigrationEasyWorkOriginals.FENCE+easyHash(ref.toByteArray()))
        if(storage.read(ref)!=null)names.add(ref)
        if(credentialName.isNotEmpty())names.add(credentialName)
        if(mode!="guard-only")names.add(claim)
        requireNotNull(NativeMigrationEasyWorkOriginals.validate(names.associateWith {requireNotNull(storage.read(it))}))
        return Seed(storage,identity,ref,credentialName,if(mode=="guard-only")""else claim,coordinator,revoke,names)
    }
    /** Recreate only the documented post-confirmation/pre-delete cut, using exact writer output. */
    fun resumeConfirmedCleanup(seed: Seed) {
        val fenceName=seed.originalNames.single {it.startsWith(NativeMigrationEasyWorkOriginals.FENCE)}
        val fence=JSONObject(requireNotNull(seed.storage.read(fenceName)))
        val local=fence.getJSONObject("revoke").getJSONObject("localReceipt")
        seed.storage.write(seed.grantRef,local.toString())
        val verify: (JSONObject)->Unit={value->require(value.getJSONObject("grant").get("grantId")==local.getJSONObject("grant").get("grantId"))}
        check(call(seed.coordinator,"revocation",seed.grantRef,verify)==null)
        check(seed.storage.read(seed.grantRef)==null)
    }
    fun oidc(storage: NativeSessionStorage,identity: EasyWorkIdentity): Set<String> {
        val issuer="https://acceptance.example";val subject="synthetic-original";val audience="easyai:remote-access";val client="synthetic-client"
        val claims=JSONObject().put("iss",issuer).put("sub",subject).put("azp",client).put("aud",audience).put("exp",1L)
            .put("device_id",identity.deviceId).put("device_public_key",identity.publicKey).put("device_platform","android")
        val token=encoded("{\"alg\":\"none\"}".toByteArray())+"."+encoded(claims.toString().toByteArray())+".synthetic-original"
        val session=JSONObject().put("tokenEndpoint",issuer+"/token").put("clientId",client).put("audience",audience).put("issuer",issuer)
            .put("expiresAt",1L).put("accessToken",token).put("refreshToken","synthetic-original-refresh")
        val key=NativeMigrationEasyWorkOriginals.OIDC+easyHash(easyCanonical(JSONArray(listOf(issuer,subject))).toByteArray())
        storage.write(key,session.toString());storage.write("easywork-oidc-last",session.toString())
        storage.write("easywork-oidc-pending",JSONObject().put("tokenEndpoint",issuer+"/token").put("clientId",client).put("audience",audience).put("issuer",issuer)
            .put("expiresAt",1L).put("state",UUID.randomUUID().toString()).put("verifier",encoded(ByteArray(48){7})).toString())
        return setOf(key,"easywork-oidc-last","easywork-oidc-pending")
    }
    private fun actualCandidateWrite(storage: NativeSessionStorage,raw: String): String {
        val authorityType=Class.forName("com.agenthub.android.EasyWorkConnectionAuthority");val authority=authorityType.getDeclaredConstructor().newInstance()
        val originType=Class.forName("com.agenthub.android.EasyWorkConnectionOrigin");val origin=originType.getDeclaredConstructor(String::class.java,String::class.java,String::class.java,String::class.java).newInstance("synthetic-target","synthetic-navigation","original-revision","0".repeat(64))
        val inputType=Class.forName("com.agenthub.android.EasyWorkConnectionInput");val input=inputType.getDeclaredConstructor(String::class.java,String::class.java,String::class.java,String::class.java).newInstance("save","synthetic","","original")
        val form=call(authority,"form",{origin})!!;val lease=call(form,"begin",input,{input})!!
        val ref="agent-synthetic-"+call(lease,"getId");val name=NativeMigrationEasyWorkOriginals.CREDENTIAL+ref
        val type=Class.forName("com.agenthub.android.EasyWorkConnectionCredentialWrites");val writes=type.getField("INSTANCE").get(null)
        call(writes,"write",storage,name,raw,lease)
        // Release only the in-memory write-ownership receipt. No metadata pointer is committed.
        call(writes,"committed",storage,name,lease);call(lease,"releaseBusy")
        require(storage.read(name)==raw);return name
    }
}
