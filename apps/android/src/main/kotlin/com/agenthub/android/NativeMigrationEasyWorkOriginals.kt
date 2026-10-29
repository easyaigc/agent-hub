package com.agenthub.android

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Frozen read-only authorization inventory. No identity loader, signer, coordinator or channel. */
internal object NativeMigrationEasyWorkOriginals {
    const val IDENTITY="easywork-device-identity"
    const val CREDENTIAL="easywork-credential:"
    const val GRANT="easywork-device-grant:"
    const val FENCE="easywork-authorization-fence:"
    const val CLAIM="easywork-device-claim:"
    const val OIDC="easywork-oidc:"
    val fixed=setOf(IDENTITY,"easywork-oidc-pending","easywork-oidc-last")
    private val prefixes=listOf(CREDENTIAL,GRANT,FENCE,CLAIM,OIDC)
    fun owns(name: String)=name in fixed||prefixes.any(name::startsWith)
    private fun hash(text: String)=NativeChatNotificationWire.hash(text)
    private fun json(raw: String)=StrictMigrationJson.parse(raw.toByteArray(Charsets.UTF_8))
    private fun fields(value: JSONObject,required: Set<String>,optional: Set<String> =emptySet(),extensions: Boolean=false) {
        val keys=value.keys().asSequence().toSet();require(keys.containsAll(required)&&(extensions||keys.all {it in required||it in optional}))
    }
    private fun text(v: JSONObject,k: String,max: Int=8192)=(v.get(k) as String).also {require(it.isNotEmpty()&&it.length<=max&&!it.contains('\u0000'))}
    private fun uuid(value: String)=value.also {require(it.length<=64);UUID.fromString(it)}
    private fun number(v: JSONObject,k: String)=(v.get(k) as Number).also {require(it is Int||it is Long);require(it.toLong() in 0..9_007_199_254_740_991L)}.toLong()
    private fun bytes(raw: String,size: Int): ByteArray {
        require(raw.matches(Regex("[A-Za-z0-9_-]+")))
        return Base64.getUrlDecoder().decode(raw).also {require(it.size==size&&Base64.getUrlEncoder().withoutPadding().encodeToString(it)==raw)}
    }
    // Access/refresh tokens are opaque ASCII originals, not encoded keys to normalize.
    private fun token(raw: String)=raw.also {require(it.matches(Regex("[A-Za-z0-9_-]{43}")))}
    private fun origin(raw: String): String {
        val uri=URI(raw);require(uri.host!=null&&uri.rawUserInfo==null&&uri.rawQuery==null&&uri.rawFragment==null&&uri.rawPath in listOf("","/"))
        require(uri.scheme=="https"||uri.scheme=="http"&&uri.host in setOf("localhost","127.0.0.1","[::1]","::1"))
        return raw.trimEnd('/')
    }
    private fun https(raw: String) {val uri=URI(raw);require(uri.scheme=="https"&&uri.host!=null&&uri.rawUserInfo==null&&uri.rawFragment==null)}
    private fun grantName(value: String)=value.also {require(it.startsWith(GRANT)&&it.removePrefix(GRANT).matches(Regex("[a-f0-9]{64}")))}
    private fun identity(raw: String): EasyWorkIdentity {
        val v=json(raw);fields(v,setOf("deviceId","privateKey","publicKey"));val secret=bytes(text(v,"privateKey",64),32);val public=bytes(text(v,"publicKey",64),32)
        try {require(X25519PrivateKeyParameters(secret,0).generatePublicKey().encoded.contentEquals(public))}finally {secret.fill(0);public.fill(0)}
        return EasyWorkIdentity(text(v,"deviceId",128),v.getString("privateKey"),v.getString("publicKey"))
    }
    private fun binding(v: JSONObject,id: EasyWorkIdentity): EasyWorkBinding {
        // Peer extensions are immutable original evidence, exactly as accepted by the producer.
        val b=EasyWorkBinding(v.toString()).verify(id)
        text(v,"bindingId",512);require(b.desktopId==b.target.desktopDeviceId);bytes(b.desktopPublicKey,32).fill(0)
        CodexCommandCodec.target(b.target.scope());return b
    }
    private fun jwk(v: JSONObject): String {
        fields(v,setOf("kty","crv","x","y"));require(v.get("kty")=="EC"&&v.get("crv")=="P-256")
        val x=bytes(text(v,"x",64),32);val y=bytes(text(v,"y",64),32)
        try {val point=org.bouncycastle.asn1.sec.SECNamedCurves.getByName("secp256r1").curve.decodePoint(byteArrayOf(4)+x+y);require(!point.isInfinity&&point.isValid)}finally {x.fill(0);y.fill(0)}
        return Base64.getUrlEncoder().withoutPadding().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(easyCanonical(v).toByteArray(Charsets.UTF_8)))
    }
    private fun grant(v: JSONObject,id: EasyWorkIdentity): EasyWorkBinding {
        fields(v,setOf("protocol","grantId","deviceId","binding","target","keyVersion","scopes","accessToken","refreshToken","accessExpiresAt","refreshExpiresAt"),extensions=true)
        require(v.get("protocol")=="EWDAP/1"&&v.get("deviceId")==id.deviceId);text(v,"grantId",512)
        val b=binding(v.getJSONObject("binding"),id);require(EasyWorkTarget.decode(v.getJSONObject("target"))==b.target)
        val auth=b.json.getJSONObject("deviceAuthorization");require(auth.get("protocol")=="EWDAP/1"&&auth.get("grantId")==v.get("grantId"))
        require(number(v,"keyVersion")>0&&number(v,"keyVersion")==number(auth,"keyVersion"));bytes(text(auth,"authKeyThumbprint",64),32).fill(0)
        token(text(v,"accessToken",64));token(text(v,"refreshToken",64))
        require(Instant.parse(text(v,"accessExpiresAt"))>Instant.EPOCH&&Instant.parse(text(v,"refreshExpiresAt"))>Instant.EPOCH)
        val scopes=v.getJSONArray("scopes").strings();require(scopes.distinct().size==scopes.size&&scopes.all {it in EasyWorkDeviceGrant.SCOPES}&&scopes.containsAll(listOf("relay:read","relay:write","runtime:read","runtime:control")))
        return b
    }
    private fun saved(v: JSONObject,id: EasyWorkIdentity,ref: String): EasyWorkBinding {
        fields(v,setOf("origin","grant"),extensions=true);val endpoint=origin(text(v,"origin"));val g=v.getJSONObject("grant");val b=grant(g,id)
        require(ref==GRANT+hash(endpoint+"\n"+g.getString("grantId")))
        v.optJSONObject("refreshOperation")?.let {pending->uuid(text(pending,"operationId",36));token(text(pending,"token",64));require(pending.get("token")==g.get("refreshToken"))}
        if(v.has("refreshOperation"))require(v.get("refreshOperation") is JSONObject)
        v.optional("revokeOperationId")?.let(::uuid);return b
    }
    private fun sameGrant(a: JSONObject,b: JSONObject) {
        require(a.get("origin")==b.get("origin")&&a.getJSONObject("grant").get("grantId")==b.getJSONObject("grant").get("grantId"))
        require(easyCanonical(a.getJSONObject("grant").getJSONObject("binding"))==easyCanonical(b.getJSONObject("grant").getJSONObject("binding")))
    }
    private fun fenceKey(ref: String)=FENCE+hash(ref)
    private fun fence(name: String,v: JSONObject,id: EasyWorkIdentity,records: Map<String,String>) {
        fields(v,setOf("version","ref","epoch"),setOf("revoke","refreshResults"),extensions=true)
        require(v.get("version")==1);val ref=grantName(text(v,"ref"));require(name==fenceKey(ref));text(v,"epoch",36).also {require(UUID.fromString(it).toString()==it)}
        if(v.has("refreshResults")) {
            val results=v.getJSONObject("refreshResults");require(results.length()<=10_000)
            results.keys().forEach {operation->uuid(operation);val row=results.getJSONObject(operation)
                fields(row,setOf("operationId","originalPending","tokenHash","body","grantHash"),extensions=true)
                require(row.get("operationId")==operation&&row.getJSONObject("originalPending").get("operationId")==operation)
                require(!row.has("token")&&!row.getJSONObject("originalPending").has("token"))
                require(row.get("body")==easyCanonical(JSONObject().put("operationId",operation)))
                for(key in listOf("tokenHash","grantHash"))require(text(row,key,64).matches(Regex("[a-f0-9]{64}")))
            }
        }
        if(v.has("revoke")) {
            val revoke=v.getJSONObject("revoke");fields(revoke,setOf("state","operationId","body","intentHash","saved"),setOf("localReceipt"),extensions=true)
            require(revoke.get("state") in setOf("pending","confirmed"));val operation=uuid(text(revoke,"operationId",36))
            val body=easyCanonical(JSONObject().put("operationId",operation));require(revoke.get("body")==body)
            val original=json(text(revoke,"saved",NativeMigrationCandidateStore.MAX_PAYLOAD_BYTES));saved(original,id,ref)
            require(original.get("revokeOperationId")==operation)
            val g=original.getJSONObject("grant")
            val expected=hash(easyCanonical(JSONObject().put("ref",ref).put("origin",original.getString("origin")).put("binding",g.getJSONObject("binding"))
                .put("grantId",g.getString("grantId")).put("body",body).put("tokenHash",hash(g.getString("accessToken")))))
            require(revoke.get("intentHash")==expected)
            records[ref]?.let {current->val parsed=json(current);saved(parsed,id,ref);sameGrant(parsed,original)}
            // The producer keeps Revoke.saved as a string snapshot, but writes localReceipt
            // directly from read(ref), as an object. Never coerce or rewrite either original.
            if(revoke.has("localReceipt")){val local=revoke.get("localReceipt") as JSONObject;saved(local,id,ref);sameGrant(local,original)}
        }
    }
    private fun originalGrant(ref: String,records: Map<String,String>): JSONObject?=records[ref]?.let(::json)?:records[fenceKey(ref)]?.let(::json)?.optJSONObject("revoke")?.getString("saved")?.let(::json)
    private fun credential(v: JSONObject,id: EasyWorkIdentity,records: Map<String,String>) {
        fields(v,setOf("binding","privateKey"),setOf("relayBinding","accessToken","grantRef"))
        require(v.get("privateKey")==id.privateKey);val b=binding(v.getJSONObject("binding"),id)
        if(v.has("relayBinding"))require(v.get("relayBinding")==JSONObject.NULL||v.get("relayBinding")is JSONObject)
        v.optJSONObject("relayBinding")?.let {require(binding(it,id).target==b.target)}
        v.optional("accessToken")?.let {require(it.length<=65_536&&it.none {c->c.code<32})}
        v.optional("grantRef")?.let {ref->grantName(ref);val original=originalGrant(ref,records)?:error("Original grant/fence missing")
            val bound=saved(original,id,ref);require(bound==b)}
    }
    private fun claim(name: String,v: JSONObject,id: EasyWorkIdentity,records: Map<String,String>) {
        fields(v,setOf("origin","body"),setOf("claimId","offerId","pendingToken","expiresAt","verificationCode","grantRef"))
        val endpoint=origin(text(v,"origin"));val raw=text(v,"body",65_536);val body=json(raw)
        fields(body,setOf("operationId","connectionCode","device"));uuid(text(body,"operationId",36))
        val code=text(body,"connectionCode",16);require(code.matches(Regex("[0-9A-HJKMNP-TV-Z]{16}")))
        val device=body.getJSONObject("device");fields(device,setOf("deviceId","platform","name","publicKey","authPublicKey"))
        require(device.get("deviceId")==id.deviceId&&device.get("publicKey")==id.publicKey&&device.get("platform")=="android");text(device,"name",100)
        val thumb=jwk(device.getJSONObject("authPublicKey"));require(name==CLAIM+hash(endpoint+"\n"+thumb+"\n"+code))
        if(v.has("claimId")) {uuid(text(v,"claimId",36));text(v,"offerId",512);token(text(v,"pendingToken",64));Instant.parse(text(v,"expiresAt"))}
        else require(setOf("offerId","pendingToken","expiresAt","verificationCode","grantRef").none(v::has))
        v.optional("verificationCode")?.let {require(it.length<=256)}
        v.optional("grantRef")?.let {ref->grantName(ref);val original=originalGrant(ref,records)?:error("Claim original grant/fence missing")
            saved(original,id,ref);require(original.get("origin")==v.get("origin"));require(original.getJSONObject("grant").getJSONObject("binding").getJSONObject("deviceAuthorization").get("authKeyThumbprint")==thumb)}
    }
    private fun oidc(name: String,v: JSONObject,id: EasyWorkIdentity) {
        val pending=name=="easywork-oidc-pending"
        fields(v,setOf("tokenEndpoint","clientId","audience","issuer","expiresAt")+if(pending)setOf("state","verifier")else setOf("accessToken"),extensions=true)
        https(text(v,"tokenEndpoint"));https(text(v,"issuer"));text(v,"clientId",512);text(v,"audience",512);number(v,"expiresAt")
        if(pending){uuid(text(v,"state",36));bytes(text(v,"verifier",128),48).fill(0);return}
        val token=text(v,"accessToken",65_536);val parts=token.split('.');require(parts.size==3)
        val claims=StrictMigrationJson.parse(Base64.getUrlDecoder().decode(parts[1]))
        require(claims.get("iss")==v.get("issuer")&&claims.get("azp")==v.get("clientId")&&claims.get("device_id")==id.deviceId&&claims.get("device_public_key")==id.publicKey&&claims.get("device_platform")=="android")
        val subject=text(claims,"sub",512);number(claims,"exp")
        val audience=claims.get("aud");require(audience==v.get("audience")||audience is JSONArray&&v.get("audience") in audience.strings())
        if(name!="easywork-oidc-last")require(name==OIDC+hash(easyCanonical(JSONArray(listOf(v.getString("issuer"),subject)))))
        v.optional("refreshToken")?.let {require(it.length in 1..65_536)}
    }
    /** Full current family, including unreferenced credential candidates and fence-only crashes. */
    fun validate(records: Map<String,String>): JSONObject? {
        val names=records.keys.filter(::owns).toSet();if(names.isEmpty())return null
        require(names.size<=1024);val identity=identity(records.getValue(IDENTITY))
        for(name in names)if(name!=IDENTITY) {
            val v=json(records.getValue(name))
            when {
                name.startsWith(CREDENTIAL)->{require(name.removePrefix(CREDENTIAL).length in 1..512);credential(v,identity,records)}
                name.startsWith(GRANT)->saved(v,identity,grantName(name))
                name.startsWith(FENCE)->fence(name,v,identity,records)
                name.startsWith(CLAIM)->claim(name,v,identity,records)
                name=="easywork-oidc-pending"||name=="easywork-oidc-last"||name.startsWith(OIDC)->oidc(name,v,identity)
            }
        }
        return JSONObject().put("deviceId",identity.deviceId).put("publicKey",identity.publicKey).put("identitySha256",hash(records.getValue(IDENTITY)))
    }
    /** Modern authenticated names may reveal exact aliases for older hash-only originals. */
    fun legacyNames(discovered: Set<String>,read: (String)->String?): Set<String> {
        val names=(fixed+discovered.filter(::owns)).toMutableSet();val queued=java.util.ArrayDeque(names);val seen=mutableSetOf<String>()
        fun add(name: String){require(name.length<=8192&&!name.contains('\u0000'));if(names.add(name)){require(names.size<=1024);queued.add(name)}}
        while(queued.isNotEmpty()) {
            val name=queued.removeFirst();if(!seen.add(name))continue
            val raw=read(name)?:continue;val value=json(raw)
            when {
                name.startsWith(CREDENTIAL)->value.optional("grantRef")?.let {add(grantName(it));add(fenceKey(it))}
                name.startsWith(FENCE)->add(grantName(value.getString("ref")))
                name.startsWith(CLAIM)->value.optional("grantRef")?.let {add(grantName(it));add(fenceKey(it))}
                name.startsWith(GRANT)->add(fenceKey(name))
                name=="easywork-oidc-last"->run {val parts=value.getString("accessToken").split('.');require(parts.size==3);val claims=StrictMigrationJson.parse(Base64.getUrlDecoder().decode(parts[1]));add(OIDC+hash(easyCanonical(JSONArray(listOf(claims.getString("iss"),claims.getString("sub"))))))}
            }
        }
        return names
    }
}
