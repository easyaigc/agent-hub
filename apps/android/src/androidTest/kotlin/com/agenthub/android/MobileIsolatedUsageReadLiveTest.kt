package com.agenthub.android

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.UsageReadRules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Real Main UI, owned installation and production usage Store/transport. No thread is selected.
 * Input files are a controller-frozen real desktop source proof and a separately authorized new claim.
 * This candidate does not grant its own device lease or substitute a provider/clock/UI.
 */
@RunWith(AndroidJUnit4::class)
class MobileIsolatedUsageReadLiveTest {
    @get:Rule val ui=createEmptyComposeRule()
    @get:Rule val limit=Timeout.seconds(240)
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private var launchOwner: F73UsageActivityLaunch?=null
    private var allCrossEntrySnapshotsMatched=true
    private var usageObservationDeadlineElapsed=0L
    private fun <T> boundedMain(action: ()->T): T=checkNotNull(launchOwner).onMain("live-owned-main",action)
    private fun digest(bytes: ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private fun digest(value: String)=digest(value.toByteArray(Charsets.UTF_8))
    private fun hex(value: String)=value.also {require(it.matches(Regex("[a-f0-9]{64}")))}
    private fun uuid(value: String)=value.also {require(UUID.fromString(it).toString()==it)}
    private fun JSONArray.strings()=(0 until length()).map {getString(it)}
    private fun fileDigest(file: File): String {
        val sha=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {stream->val bytes=ByteArray(65536);while(true){val n=stream.read(bytes);if(n<0)break;sha.update(bytes,0,n)}}
        return sha.digest().joinToString(""){"%02x".format(it)}
    }
    private fun directory(file: File) {
        require(file.canonicalFile==file.absoluteFile&&!Files.isSymbolicLink(file.toPath()))
        val s=android.system.Os.lstat(file.path)
        require(android.system.OsConstants.S_ISDIR(s.st_mode)&&s.st_uid==android.os.Process.myUid()&&s.st_mode and 0x1ff==0x1c0)
    }
    private fun privateJson(file: File,expectedSha: String,maxBytes: Int=65536): JSONObject {
        directory(file.parentFile!!)
        require(file.canonicalFile==file.absoluteFile&&!Files.isSymbolicLink(file.toPath()))
        val fd=android.system.Os.open(file.path,android.system.OsConstants.O_RDONLY or android.system.OsConstants.O_NOFOLLOW,0)
        val raw=try {
            val a=android.system.Os.fstat(fd)
            require(android.system.OsConstants.S_ISREG(a.st_mode)&&a.st_uid==android.os.Process.myUid()&&a.st_nlink==1L&&
                a.st_mode and 0x1ff==0x180&&a.st_size in 1..maxBytes.toLong())
            val bytes=ByteArray(a.st_size.toInt());var at=0
            while(at<bytes.size){val n=android.system.Os.read(fd,bytes,at,bytes.size-at);require(n>0);at+=n}
            require(android.system.Os.read(fd,ByteArray(1),0,1)==0)
            val z=android.system.Os.fstat(fd);val p=android.system.Os.lstat(file.path)
            require(a.st_dev==z.st_dev&&a.st_ino==z.st_ino&&a.st_size==z.st_size&&a.st_mtime==z.st_mtime&&
                p.st_dev==a.st_dev&&p.st_ino==a.st_ino&&p.st_size==a.st_size&&p.st_mode==a.st_mode)
            bytes
        } finally {android.system.Os.close(fd)}
        require(digest(raw)==hex(expectedSha)) {"Private input fingerprint changed"}
        return JSONObject(raw.toString(Charsets.UTF_8))
    }
    private fun publish(folder: File,name: String,value: JSONObject) {
        directory(folder);val file=File(folder,name)
        val fd=android.system.Os.open(file.path,android.system.OsConstants.O_WRONLY or android.system.OsConstants.O_CREAT or
            android.system.OsConstants.O_EXCL or android.system.OsConstants.O_NOFOLLOW,0x180)
        try {val bytes=value.toString().toByteArray(Charsets.UTF_8);var at=0
            while(at<bytes.size){val n=android.system.Os.write(fd,bytes,at,bytes.size-at);require(n>0);at+=n}
            android.system.Os.fsync(fd)
        } finally {android.system.Os.close(fd)}
    }
    private data class Admission(val plan: JSONObject,val folder: File,val expected: JSONObject,val pairing: String)
    private fun admit(): Admission {
        val args=InstrumentationRegistry.getArguments()
        require(args.getString("f73UsageLeaseReady")=="1"&&args.getString("q02IsolatedProfile")=="1"&&args.getString("q02NewBindingAuthorized")=="1")
        require(args.getString("nativeForeground")=="0"&&args.getString("nativeThreadSnapshots")=="0")
        require(BuildConfig.DEBUG&&context.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName==context.packageName+".test")
        val run=uuid(checkNotNull(args.getString("q02Run")))
        val folder=File(context.filesDir.canonicalFile,"acceptance/f73-real-dev/$run");directory(folder)
        val p=privateJson(File(folder,"plan.json"),checkNotNull(args.getString("f73UsagePlanSha256")))
        require(p.getInt("version")==1&&p.getString("suite")=="f73-production-usage-read"&&p.getString("run")==run)
        require(p.getBoolean("isolatedTargetApproved")&&p.getBoolean("newBindingAuthorized")&&p.getString("startupMode")=="new-isolated-installation")
        require(uuid(p.getString("leaseId"))==args.getString("q02LeaseId"))
        require(uuid(p.getString("controlHostId"))==args.getString("q02AuthorizedControlHostId"))
        val remaining=p.getLong("leaseExpiresAtMillis")-System.currentTimeMillis();require(remaining in 60_000..900_000)
        require(p.getInt("api")==android.os.Build.VERSION.SDK_INT)
        val config=context.resources.configuration
        require(config.orientation==android.content.res.Configuration.ORIENTATION_PORTRAIT&&config.orientation==p.getInt("orientation"))
        require(config.locales[0].toLanguageTag()==p.getString("languageTag")&&config.locales[0].language=="zh")
        require(config.fontScale==p.getDouble("fontScale").toFloat())
        require(digest(checkNotNull(android.provider.Settings.Secure.getString(context.contentResolver,android.provider.Settings.Secure.ANDROID_ID)))==hex(p.getString("installationAndroidIdSha256")))
        require(fileDigest(File(context.applicationInfo.sourceDir))==hex(p.getString("appSha256")))
        require(fileDigest(File(i.context.applicationInfo.sourceDir))==hex(p.getString("runnerSha256")))
        val target=checkNotNull(HostReadCodec.target(p.getJSONObject("target")))
        require(target.agentId in setOf("codex","easywork")&&target.hostId==args.getString("f73AuthorizedProviderHostId"))
        if(target.agentId=="codex") {
            uuid(target.hostId)
            require(target.workspaceId==target.hostId&&target.runtimeId=="desktop-runtime")
        }
        require(com.agenthub.core.DomainContract.validate(p.getJSONObject("target").toString(),"target")==null)
        require(java.net.URI(p.getString("endpoint")).scheme=="https")
        val mapping=privateJson(File(folder,"local-provider-mapping.json"),p.getString("localMappingSha256"))
        require(mapping.getString("controlHostId")==p.getString("controlHostId")&&sameScope(HostReadCodec.target(mapping.getJSONObject("providerTarget")),target))
        require(mapping.getString("providerEndpoint")==p.getString("endpoint")&&mapping.getString("providerCertificateSha256")==hex(p.getString("certificateSha256")))
        if(target.agentId=="easywork") {
            require(mapping.getString("providerDesktopPublicKeySha256")==hex(p.getString("desktopPublicKeySha256")))
        }
        require(mapping.getJSONObject("localProcess").getString("sourceHead")==p.getString("desktopSourceHead"))
        val source=privateJson(File(folder,"desktop-usage-source.json"),p.getString("desktopSourceSha256"),262144)
        val body=source.getJSONObject("body")
        if(source.optString("kind")=="desktop-observed-connection-source-projection-v1") {
            require(target.agentId=="easywork"&&source.opt("productGettersSucceeded")==true)
            require(source.getString("projectionMethod")=="exact-source-createConnectionUsageReader")
            require(source.opt("rawHttpOrMobileUsageRead")==false&&source.opt("realProviderReplaced")==false)
            require(source.getJSONObject("productionReaderSourceRef").getString("sha256")==mapping.getString("providerUsageReaderSourceSha256"))
            val observed=privateJson(File(folder,"desktop-connection-observation.json"),source.getJSONObject("actualGetterSourceRef").getString("sha256"))
            val label=privateJson(File(folder,"desktop-scope-label-observation.json"),source.getJSONObject("actualLabelSourceRef").getString("sha256"))
            val connection=observed.getJSONObject("connection");val owner=observed.getJSONObject("owner")
            require(connection.opt("ok")==true&&owner.opt("ok")==true)
            require(connection.getString("status")=="connected"&&connection.getString("mode")=="account")
            require(connection.getJSONObject("account").getString("status")=="signed_in"&&owner.getString("status")=="signed_in")
            require(owner.getString("userIdSha256")==mapping.getString("providerAccountSubjectSha256"))
            require(digest(checkNotNull(target.accountSubject))==owner.getString("userIdSha256"))
            require(label.getString("status")=="signed_in"&&label.getString("userIdSha256")==owner.getString("userIdSha256"))
            require(label.getString("scopeRef")==owner.getString("scopeRef")&&label.get("tenantPresent")==owner.get("tenantPresent"))
            val balance=connection.getJSONObject("balance")
            require(balance.getString("source")=="easyai_platform"&&balance.get("decimalAmount") is String)
            require(!balance.has("stale")||balance.get("stale")==false)
            val snapshot=body.getJSONObject("snapshot");val sampledAt=snapshot.getString("sampledAt")
            require(sampledAt==observed.getString("observationCompletedAt")&&sampledAt==source.getString("observationCompletedAt"))
            require(snapshot.getJSONObject("source").getString("adapterId")=="easywork-balance-v1")
            require(snapshot.getJSONObject("source").getString("scopeRef")==owner.getString("scopeRef"))
            require(snapshot.getJSONObject("source").getString("scopeLabel")==label.getString("scopeLabel"))
            require(snapshot.getString("coverage")=="partial")
            val issues=JSONArray().put(JSONObject().put("bucketId",JSONObject.NULL).put("metricId",JSONObject.NULL).put("reason","not-returned"))
            require(easyCanonical(snapshot.getJSONArray("issues"))==easyCanonical(issues))
            val buckets=snapshot.getJSONArray("buckets");require(buckets.length()==1)
            val bucket=buckets.getJSONObject(0);require(bucket.getString("id")=="easyai_platform")
            require(bucket.getString("label")==label.getString("scopeLabel"))
            val metrics=bucket.getJSONArray("metrics");require(metrics.length()==1)
            val metric=metrics.getJSONObject(0);val reading=metric.getJSONObject("reading")
            require(metric.getString("id")=="balance"&&metric.getString("kind")=="balance"&&reading.getString("state")=="amount")
            require(java.math.BigDecimal(reading.getString("amount")).compareTo(java.math.BigDecimal(balance.getString("decimalAmount")))==0)
            val currency=balance.getString("currency")
            val unit=if(currency=="resource")JSONObject().put("kind","compute").put("code","resource").put("label","点")
                else JSONObject().put("kind","custom").put("code",currency).put("label",currency)
            require(easyCanonical(metric.getJSONObject("unit"))==easyCanonical(unit))
            val refreshed=Instant.parse(balance.getString("refreshedAt")).toEpochMilli()
            require(System.currentTimeMillis()-refreshed in -120_000..p.getInt("maxSourceAgeSeconds")*1000L)
        } else require(target.agentId=="codex"&&source.getInt("httpStatus")==200)
        require(body.getString("requestId")==source.getString("diagnosticId")&&body.getString("requestId")==p.getString("desktopRequestId"))
        require(easyCanonical(body.getJSONObject("target"))==easyCanonical(usageTargetJson(target))&&body.getString("status")=="ok")
        val sampled=Instant.parse(body.getJSONObject("snapshot").getString("sampledAt")).toEpochMilli()
        require(p.getInt("maxSourceAgeSeconds") in 1..300&&System.currentTimeMillis()-sampled in -120_000..p.getInt("maxSourceAgeSeconds")*1000L)
        // This is a real immutable source, not an installed fixture or a replacement provider.
        val expected=JSONObject(UsageReadRules.projectResponse(body.toString(),usageTargetJson(target).toString(),System.currentTimeMillis()))
        require(expected.getString("status")=="ok"&&!expected.optBoolean("stale")&&expected.getJSONArray("metrics").length()>0)
        require(digest(easyCanonical(expected.getJSONObject("source")))==hex(p.getString("sourceScopeSha256")))
        require(semanticDigest(expected)==hex(p.getString("semanticMetricsSha256"))) {"Desktop metric summary does not match its frozen real source"}
        val pairing=privateJson(File(folder,"new-binding-input.json"),p.getString("pairingFileSha256")).getString("input")
        require(digest(pairing)==hex(p.getString("pairingIntentSha256")))
        if(target.agentId=="easywork") {
            val offer=EasyWorkLANOffer.parse(pairing)
            require(sameScope(offer.host.target.scope(),target)&&offer.host.fingerprint==p.getString("certificateSha256"))
            require(digest(easyBytes(offer.host.publicKey))==p.getString("desktopPublicKeySha256"))
            require(offer.host.endpoints.isNotEmpty()&&offer.host.endpoints.all {CodexEndpoints.local(it) in p.getJSONArray("allowedEndpoints").strings()})
        }
        require(!File(folder,"profile-prepared.json").exists()&&!File(folder,"profile-prepare-failed.json").exists()) {"Inspect original attempt; never replay a claim"}
        return Admission(p,folder,expected,pairing)
    }
    private fun usageTargetJson(target: com.agenthub.core.TargetScope)=JSONObject().put("agentId",target.agentId).put("hostId",target.hostId)
        .put("workspaceId",target.workspaceId).put("runtimeId",target.runtimeId)
    private fun semanticDigest(value: JSONObject): String {
        val metrics=value.getJSONArray("metrics");val stable=JSONArray()
        for(index in 0 until metrics.length())stable.put(JSONObject(metrics.getJSONObject(index).toString()).apply {remove("observedAt");remove("validUntil")})
        return digest(easyCanonical(stable))
    }
    private fun projection(profile: Q02RealReadInstallation.Profile): JSONObject = boundedMain {
        val client=profile.checkOnlyApprovedTarget();JSONObject(checkNotNull(profile.session).usage.value(client).toString())
    }
    private fun refreshing(profile: Q02RealReadInstallation.Profile): Boolean = boundedMain {
        checkNotNull(profile.session).usage.refreshing(profile.checkOnlyApprovedTarget())
    }
    private fun checkProjection(a: Admission,value: JSONObject) {
        require(System.currentTimeMillis()<a.plan.getLong("leaseExpiresAtMillis"))
        require(value.optString("status")=="ok"&&!value.optBoolean("stale")&&!value.optBoolean("cached")) {"Production usage is unavailable or stale; see private projection evidence"}
        require(easyCanonical(value.getJSONObject("source"))==easyCanonical(a.expected.getJSONObject("source"))) {"Production source changed from the independent desktop proof"}
        require(semanticDigest(value)==a.plan.getString("semanticMetricsSha256")) {"Actual metric values/reasons differ from the frozen desktop source"}
        require(value.optBoolean("partial")==a.expected.optBoolean("partial")&&easyCanonical(value.getJSONArray("issues"))==easyCanonical(a.expected.getJSONArray("issues"))) {
            "Production coverage/issues differ from the independent desktop source"
        }
        require(value.getJSONArray("metrics").length()==a.expected.getJSONArray("metrics").length())
        val sampled=Instant.parse(value.getString("sampledAt")).toEpochMilli()
        require(sampled<=System.currentTimeMillis()+120_000)
        val metrics=value.getJSONArray("metrics")
        for(index in 0 until metrics.length()) {
            val m=metrics.getJSONObject(index)
            require(Instant.parse(m.getString("observedAt")).toEpochMilli()<=sampled+120_000)
            require(Instant.parse(m.getString("validUntil")).toEpochMilli()>=System.currentTimeMillis())
        }
    }
    private fun visible(a: Admission,text: String) {
        try {
            val node=ui.onAllNodesWithText(text,useUnmergedTree=true).onFirst()
            if(!runCatching {node.assertIsDisplayed();true}.getOrDefault(false))node.performScrollTo()
            node.assertIsDisplayed()
        }
        catch(error: Throwable) {
            if(!File(a.folder,"private-ui-assertion-failure.json").exists())publish(a.folder,"private-ui-assertion-failure.json",JSONObject()
                .put("expectedTextSha256",digest(text)).put("originalFailureClass",error.javaClass.name).put("privateOriginalMessage",error.message).put("privateOriginalStack",error.stackTraceToString()))
            throw AssertionError("F73 production dialog text was not visible; see private failure evidence")
        }
    }
    private fun shot(a: Admission,node: SemanticsNodeInteraction,name: String) {
        ui.waitUntil(5000){i.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName}
        val root=checkNotNull(i.uiAutomation.rootInActiveWindow);require(root.packageName?.toString()==context.packageName)
        val nodes=JSONArray()
        fun visit(n: android.view.accessibility.AccessibilityNodeInfo,depth: Int) {
            require(depth<=64&&nodes.length()<4096)
            require(n.packageName?.toString() in listOf(null,context.packageName))
            val rect=android.graphics.Rect();n.getBoundsInScreen(rect)
            nodes.put(JSONObject().put("bounds",rect.toShortString()).put("class",n.className?.toString())
                .put("textSha256",digest(n.text?.toString().orEmpty())).put("descriptionSha256",digest(n.contentDescription?.toString().orEmpty()))
                .put("visible",n.isVisibleToUser).put("enabled",n.isEnabled).put("clickable",n.isClickable))
            for(index in 0 until n.childCount)n.getChild(index)?.let {c->try {visit(c,depth+1)} finally {c.recycle()}}
        }
        try {visit(root,0)} finally {root.recycle()}
        // captureToImage uses this actual Compose owner's window/bounds, not a global screen image.
        val bitmap=node.captureToImage().asAndroidBitmap();val file=File(a.folder,"$name.png")
        require(!file.exists());require(file.createNewFile());android.system.Os.chmod(file.path,0x180)
        try {file.outputStream().use {require(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}
        finally {bitmap.recycle()}
        require(i.uiAutomation.rootInActiveWindow?.packageName?.toString()==context.packageName)
        publish(a.folder,"$name-ax.json",JSONObject().put("packageName",context.packageName).put("nodes",nodes)
            .put("imageSha256",fileDigest(file)).put("capture","production Compose owner window and node bounds"))
    }
    private fun inspectDialog(a: Admission,profile: Q02RealReadInstallation.Profile,name: String): JSONObject {
        val data=projection(profile);publish(a.folder,"$name-private-projection.json",data);checkProjection(a,data)
        visible(a,"当前客户端额度")
        val source=data.getJSONObject("source")
        if(!source.isNull("scopeLabel"))visible(a,"额度来源："+source.getString("scopeLabel"))
        if(!source.isNull("planLabel"))visible(a,"套餐："+source.getString("planLabel"))
        visible(a,"更新时间："+data.getString("sampledAt"))
        shot(a,ui.onNode(isDialog()),"$name-source-time")
        val metrics=data.getJSONArray("metrics")
        for(index in 0 until metrics.length()) {
            val m=metrics.getJSONObject(index)
            visible(a,usageMetricTitle(m));visible(a,usageMetricText(m))
            if(m.optString("state")=="unavailable")visible(a,UsageReadRules.reasonKey(m.getString("value")))
            val applies=m.getJSONObject("appliesTo")
            visible(a,"适用范围："+if(applies.getString("kind")=="all")"所有模型和功能" else applies.getJSONArray("ids").strings().joinToString(" · "))
            visible(a,when(m.getJSONObject("combination").getString("kind")){"all-of"->"全部限制同时生效";"any-of"->"任一限制可用";else->"按服务端规则"})
            visible(a,"观察时间："+m.getString("observedAt"))
            if(!m.isNull("note"))visible(a,m.getString("note"))
            if(!m.isNull("resetAt"))visible(a,"重置时间："+m.getString("resetAt"))
            if(!m.isNull("expiresAt"))visible(a,"到期时间："+m.getString("expiresAt"))
            shot(a,ui.onNode(isDialog()),"$name-metric-$index")
        }
        require(projection(profile).getString("snapshotId")==data.getString("snapshotId")) {"Production poll changed the inspected snapshot; preserve evidence and re-freeze inputs"}
        return data
    }
    // Selector identity is the same full-target derivation enforced by Profile.checkOnlyApprovedTarget.
    // Every projection()/refreshing() still calls that guard through P3's bounded-main adapter.
    private fun approvedUsageClientId(a: Admission): String {
        val target=checkNotNull(HostReadCodec.target(a.plan.getJSONObject("target")))
        return if(target.agentId=="codex")target.hostId else {
            require(target.agentId=="easywork")
            "easywork-"+digest(easyCanonical(CodexCommandCodec.target(target)))
        }
    }
    private fun usageUiComparisonReason(sameSource: Boolean,sameMetrics: Boolean,sameCoverage: Boolean,
        beforeId: String,afterId: String): String? = when {
        !sameSource -> "source-changed"
        !sameMetrics || !sameCoverage -> "independent-source-reobservation-required"
        beforeId.isEmpty() || afterId.isEmpty() -> "snapshot-id-missing"
        beforeId!=afterId -> "snapshot-advanced-during-ui-observation"
        else -> null
    }
    private fun checkedUiProjection(a: Admission,profile: Q02RealReadInstallation.Profile,name: String): JSONObject {
        val value=projection(profile)
        publish(a.folder,"$name-private-projection.json",value)
        publish(a.folder,"$name-observation-time.json",JSONObject().put("atElapsedMs",SystemClock.elapsedRealtime())
            .put("atWallMillis",System.currentTimeMillis()).put("leaseMillisRemaining",a.plan.getLong("leaseExpiresAtMillis")-System.currentTimeMillis())
            .put("snapshotId",value.optString("snapshotId")).put("sourceTimesRewritten",false))
        try {checkProjection(a,value)} catch(failure: Throwable) {
            val reason=runCatching {usageUiComparisonReason(
                easyCanonical(value.getJSONObject("source"))==easyCanonical(a.expected.getJSONObject("source")),
                semanticDigest(value)==a.plan.getString("semanticMetricsSha256"),
                value.optBoolean("partial")==a.expected.optBoolean("partial")&&easyCanonical(value.getJSONArray("issues"))==easyCanonical(a.expected.getJSONArray("issues")),
                value.optString("snapshotId"),value.optString("snapshotId"))}.getOrNull()
            publish(a.folder,"$name-projection-gate-rejected.json",JSONObject().put("classification",reason?:"original-projection-or-freshness-gate-rejected")
                .put("frozenSemanticMetricsSha256",a.plan.getString("semanticMetricsSha256"))
                .put("actualSemanticMetricsSha256",runCatching {semanticDigest(value)}.getOrNull()?:JSONObject.NULL)
                .put("actualSnapshotId",value.optString("snapshotId")).put("originalExceptionClass",failure.javaClass.name)
                .put("requiresFreshIndependentSource",reason=="independent-source-reobservation-required")
                .put("productDefectEstablishedByThisMismatch",false).put("originalGateBypassed",false))
            throw failure // Never hide a legitimate new value by retaining or rewriting the old one.
        }
        return value
    }
    private fun requireSameUsageUiSnapshot(a: Admission,before: JSONObject,after: JSONObject,name: String,ordinaryPollBoundary: Boolean=false) {
        val reason=usageUiComparisonReason(
            easyCanonical(before.getJSONObject("source"))==easyCanonical(after.getJSONObject("source")),
            semanticDigest(before)==semanticDigest(after),
            before.optBoolean("partial")==after.optBoolean("partial")&&easyCanonical(before.getJSONArray("issues"))==easyCanonical(after.getJSONArray("issues")),
            before.getString("snapshotId"),after.getString("snapshotId"))
        val ordinaryAdvance=ordinaryPollBoundary&&reason=="snapshot-advanced-during-ui-observation"
        if(ordinaryAdvance)allCrossEntrySnapshotsMatched=false
        publish(a.folder,"$name-snapshot-link.json",JSONObject().put("beforeSnapshotId",before.getString("snapshotId"))
            .put("afterSnapshotId",after.getString("snapshotId")).put("sameObservedSnapshot",reason==null)
            .put("classification",reason?:JSONObject.NULL).put("semanticMetricsSha256",semanticDigest(after))
            .put("ordinaryPollBoundary",ordinaryPollBoundary).put("ordinarySnapshotAdvanceAccepted",ordinaryAdvance)
            .put("productDefectEstablishedByThisMismatch",false))
        require(reason==null||ordinaryAdvance) {"F73 UI observation changed: $reason; preserve this attempt, do not replay the old claim or pretend a product defect"}
    }
    private fun assertUsageEntry(value: JSONObject,node: SemanticsNodeInteraction,summary: Boolean) {
        node.assertIsDisplayed()
        if(value.has("balance")) {
            val debt=if(value.optBoolean("balanceDebt"))" · 欠额"else""
            val extra=if(value.optInt("extraBalances")>0)" +${value.optInt("extraBalances")}"else""
            val amount=value.getString("balance");val unit=value.optString("balanceUnit")
            val balance=if(summary)"$amount $unit$debt$extra"else amount+debt
            node.assert(hasAnyDescendant(hasText(balance)))
            if(!summary)node.assert(hasAnyDescendant(hasText(unit+extra)))
        }
        if(summary) {
            val metrics=value.getJSONArray("metrics")
            for(index in 0 until metrics.length()) {
                val m=metrics.getJSONObject(index)
                val metricTag="client-usage-window-"+m.getString("id")
                if(m.optString("kind")=="window-limit") {
                    if(m.optString("state")=="unlimited")node.assert(!hasAnyDescendant(hasTestTag(metricTag)))
                    else node.assert(hasAnyDescendant(hasTestTag(metricTag) and
                        hasText("剩余"+(usageRemaining(m) ?: "暂不可用"),substring=true)))
                } else if(m.optString("kind")=="capacity-limit")
                    node.assert(hasAnyDescendant(hasTestTag(metricTag) and hasText(usageMetricText(m),substring=true)))
            }
            val partial=hasAnyDescendant(hasText("部分额度暂不可用"))
            node.assert(if(value.optBoolean("partial"))partial else !partial)
        } else {
            val ringId=value.optString("ringMetricId")
            val metrics=value.optJSONArray("metrics") ?: JSONArray()
            val metric=(0 until metrics.length()).map {metrics.getJSONObject(it)}.singleOrNull {
                it.optString("id")==ringId&&it.optString("kind")=="window-limit"
            }
            if(ringId.isEmpty()) {
                require(!value.has("percent")) {"A ring percentage must identify its actual window"}
                node.assert(!hasAnyDescendant(hasTestTag("usage-remaining-ring")))
                if(!value.has("balance"))node.assert(hasContentDescription("暂不可用",substring=true))
            } else {
                require(metric!=null&&metric.has("remainingPercent")&&value.has("percent")) {"Missing selected remaining-window projection"}
                val remaining=metric.getDouble("remainingPercent")
                require(remaining.isFinite()&&value.getDouble("percent")==remaining) {"Ring must retain the selected window's remaining percentage"}
                node.assert(hasContentDescription("剩余"+requireNotNull(usageRemaining(metric)),substring=true))
                node.assert(hasAnyDescendant(hasTestTag("usage-remaining-ring") and hasAnyDescendant(
                    hasText("${remaining.coerceIn(0.0,100.0).toInt()}%"))))
            }
            val partial=hasContentDescription("部分额度暂不可用",substring=true)
            node.assert(if(value.optBoolean("partial"))partial else !partial)
        }
    }
    private fun usageObservationWaitMillis(maximumMillis: Long=50_000L): Long =
        minOf(maximumMillis,(usageObservationDeadlineElapsed-SystemClock.elapsedRealtime()).coerceAtLeast(0L)).also {
            require(it>0) {"Original usage observation window expired; do not extend or replay"}
        }
    private fun inspectUsageBusinessDialog(a: Admission,profile: Q02RealReadInstallation.Profile,name: String): JSONObject {
        ui.onNode(isDialog()).assertIsDisplayed()
        ui.waitUntil(usageObservationWaitMillis()){!refreshing(profile)}
        val coverage=checkedUiProjection(a,profile,"$name-coverage")
        val partial=ui.onNode(hasText("部分额度暂不可用") and hasAnyAncestor(isDialog()),useUnmergedTree=true)
        if(coverage.optBoolean("partial"))partial.performScrollTo().assertIsDisplayed() else partial.assertDoesNotExist()
        val details=inspectDialog(a,profile,name)
        requireSameUsageUiSnapshot(a,coverage,details,"$name-coverage-to-details")
        return details
    }
    private fun inspectClientSelectorRoundTrip(a: Admission,profile: Q02RealReadInstallation.Profile,name: String): JSONObject {
        val approvedId=approvedUsageClientId(a)
        checkProjection(a,projection(profile)) // Current lease/approved target before opening the selector.
        ui.onNodeWithTag("current-client-usage").assertIsDisplayed()
        ui.onNodeWithTag("sidebar-client-switch").assertIsDisplayed().assertHasClickAction().performClick()
        ui.onNodeWithTag("sheet-clients").assertIsDisplayed()
        ui.onNodeWithTag("client-$approvedId",useUnmergedTree=true).performScrollTo().assertIsDisplayed().assertHasClickAction()
        val summary=ui.onNodeWithTag("client-usage-summary-$approvedId",useUnmergedTree=true)
            .assert(hasAnyAncestor(hasTestTag("client-$approvedId")))
        ui.waitUntil(usageObservationWaitMillis()){!refreshing(profile)}
        val selected=checkedUiProjection(a,profile,"$name-selector")
        assertUsageEntry(selected,summary,true)
        shot(a,summary,"$name-selector")
        // Summary itself has no OnClick. A real tap in its bounds must reach this exact client row.
        summary.performTouchInput {click(center)}
        ui.waitUntil(5000){ui.onAllNodesWithTag("sheet-clients",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
        ui.onNodeWithTag("current-client-usage").assertIsDisplayed()
        ui.waitUntil(usageObservationWaitMillis()){!refreshing(profile)}
        val footer=checkedUiProjection(a,profile,"$name-footer")
        assertUsageEntry(footer,ui.onNodeWithTag("current-client-usage",useUnmergedTree=true),false)
        shot(a,ui.onNodeWithTag("current-client-usage"),"$name-footer")
        requireSameUsageUiSnapshot(a,selected,footer,"$name-selector-to-footer",ordinaryPollBoundary=true)
        publish(a.folder,"$name-selection-scope.json",JSONObject().put("approvedClientIdSha256",digest(approvedId))
            .put("realClientCount",1).put("sameApprovedTargetReSelected",true).put("secondRealTargetSwitchPerformed",false)
            .put("bindingsCreatedByThisUiPhase",0).put("directProviderCallsByHelper",0)
            .put("selectionMayTriggerOrdinaryWorkspaceAndStatusReads",true))
        return footer
    }
    @Test fun newInstallationProductionUsageFooterDetailsAndRefreshMatchDesktopSource() {
        val a=admit();val p=a.plan;val scope=checkNotNull(HostReadCodec.target(p.getJSONObject("target")))
        val remaining=p.getLong("leaseExpiresAtMillis")-System.currentTimeMillis()
        val permit=Q02RealReadInstallation.Permit(p.getString("run"),p.getString("leaseId"),p.getString("installationAndroidIdSha256"),
            p.getString("appSha256"),p.getString("runnerSha256"),p.getString("signerSha256"),p.getInt("api"),SystemClock.elapsedRealtime()+remaining,
            p.getString("controlHostId"),p.getString("localMappingSha256"),scope,p.getString("endpoint"),p.getJSONArray("allowedEndpoints").strings(),
            p.getString("certificateSha256"),p.getString("pairingIntentSha256"),
            if(scope.agentId=="easywork")p.getString("desktopPublicKeySha256")else null)
        val owner=F73UsageActivityLaunch(context,permit);launchOwner=owner
        var primary: Throwable?=null
        try {
            val profile=try {owner.prepare()}catch(error: Throwable) {
                publish(a.folder,"profile-prepare-failed.json",owner.snapshot().put("failureClass",error.javaClass.name));throw error
            }
            publish(a.folder,"profile-prepared.json",boundedMain {profile.proof()})
            owner.startAndAwait()
            val launchState=owner.snapshot();publish(a.folder,"bounded-launch-state.json",launchState)
            check(launchState.getBoolean("mainObservationComplete")) {"Original launch observation UNKNOWN; do not continue to a claim"}
            usageObservationDeadlineElapsed=minOf(permit.expiresAtElapsed,launchState.getLong("workDeadlineElapsed"))
            ui.waitUntil(30000){boundedMain {profile.session?.restoring==false}}
            val session=boundedMain {checkNotNull(profile.session)}
            boundedMain {require(session.clients.none {!it.isDemo}&&session.operationRecords.isEmpty()&&session.text.isEmpty())}
            runBlocking {withTimeout(minOf(120_000L,permit.expiresAtElapsed-SystemClock.elapsedRealtime())){withContext(Dispatchers.Main){Q02RealReadInstallation.pairFreshTarget(profile,a.pairing)}}}
            ui.waitUntil(30000){!session.loading}
            boundedMain {profile.checkOnlyApprovedTarget();require(session.selectedThread.isEmpty()&&session.text.isEmpty()&&session.operationRecords.isEmpty())}
            ui.onNodeWithTag("open-sidebar").performClick()
            ui.onNodeWithTag("current-client-usage").assertIsDisplayed()
            ui.waitUntil(usageObservationWaitMillis()) {
                val current=projection(profile)
                !refreshing(profile)&&(current.optString("status")=="ok"||current.optString("reason")!="not-returned")
            }
            val footer=checkedUiProjection(a,profile,"footer")
            assertUsageEntry(footer,ui.onNodeWithTag("current-client-usage",useUnmergedTree=true),false)
            shot(a,ui.onNodeWithTag("current-client-usage"),"footer")
            val firstPair=inspectClientSelectorRoundTrip(a,profile,"initial-two-entry")
            ui.onNodeWithTag("current-client-usage").performClick()
            val firstDialog=inspectUsageBusinessDialog(a,profile,"before-refresh")
            requireSameUsageUiSnapshot(a,firstPair,firstDialog,"initial-footer-to-details",ordinaryPollBoundary=true)
            // Original real-clock five-second minimum and production button path remain unchanged.
            var stableId="";var stableAt=SystemClock.elapsedRealtime()
            ui.waitUntil(usageObservationWaitMillis(60_000L)) {
                val current=projection(profile);val id=current.optString("snapshotId");val now=SystemClock.elapsedRealtime()
                if(refreshing(profile)||id!=stableId){stableId=id;stableAt=now}
                id.isNotEmpty()&&!refreshing(profile)&&now-stableAt>=5500
            }
            val before=checkedUiProjection(a,profile,"manual-before")
            ui.onNodeWithText("刷新额度").assertIsDisplayed().assertIsEnabled()
            val clickedAt=SystemClock.elapsedRealtime()
            publish(a.folder,"manual-refresh-action.json",JSONObject().put("clickedAtElapsed",clickedAt).put("previousSnapshotId",before.getString("snapshotId"))
                .put("stableCompletedSnapshotForMillis",clickedAt-stableAt).put("visibleEnabledBeforeClick",true).put("path","Production dialog button only; Store five-second gate unchanged"))
            ui.onNodeWithText("刷新额度").performClick()
            val flightObserved=refreshing(profile)
            val disabledObserved=runCatching {ui.onNodeWithText("刷新额度").assertIsNotEnabled();true}.getOrDefault(false)
            val spinnerObserved=runCatching {ui.onNodeWithTag("usage-refresh-loading",useUnmergedTree=true).assertIsDisplayed();true}.getOrDefault(false)
            val loadingFrameVerified=flightObserved&&disabledObserved&&spinnerObserved
            publish(a.folder,"manual-refresh-loading-observation.json",JSONObject().put("inFlightObserved",flightObserved)
                .put("buttonDisabledObserved",disabledObserved).put("spinnerObserved",spinnerObserved)
                .put("additionalClickAllowed",false).put("missingFrameIsNotAloneAProductDefect",true)
                .put("loadingStateVerified",loadingFrameVerified).put("classification",if(loadingFrameVerified)"loading-frame-observed"else"loading-frame-not-observed"))
            // One read may finish before the next UI sample. Keep this coverage false; never click again to create a frame.
            ui.waitUntil(usageObservationWaitMillis()){!refreshing(profile)&&projection(profile).optString("snapshotId")!=before.getString("snapshotId")}
            checkedUiProjection(a,profile,"manual-after") // A lawful changed amount needs new independent proof; never assert the old amount instead.
            ui.onNodeWithText("刷新额度").assertIsDisplayed().assertIsEnabled()
            ui.onNodeWithTag("usage-refresh-loading",useUnmergedTree=true).assertDoesNotExist()
            val after=inspectUsageBusinessDialog(a,profile,"after-refresh")
            require(Instant.parse(after.getString("sampledAt"))>=Instant.parse(before.getString("sampledAt")))
            publish(a.folder,"manual-refresh-result.json",JSONObject().put("previousSnapshotId",before.getString("snapshotId")).put("newSnapshotId",after.getString("snapshotId"))
                .put("newSnapshotObservedAtElapsed",SystemClock.elapsedRealtime()).put("inFlightObserved",flightObserved).put("buttonDisabledObserved",disabledObserved)
                .put("spinnerObserved",spinnerObserved).put("loadingStateVerified",loadingFrameVerified)
                .put("sourceMatchesDesktop",true).put("semanticMetricsSha256",semanticDigest(after)).put("directProviderCalls",0)
                .put("requestAttribution","Production UI action and newer Store snapshot; wire correlation is optional diagnostic, not an extra gate"))
            ui.onNodeWithText("完成").performClick();ui.onNodeWithTag("current-client-usage").assertIsDisplayed()
            val refreshedPair=inspectClientSelectorRoundTrip(a,profile,"refreshed-two-entry")
            // Opening the selector starts its ordinary poll. Do not freeze a previous dialog's snapshot across that boundary.
            require(Instant.parse(refreshedPair.getString("sampledAt"))>=Instant.parse(after.getString("sampledAt")))
            publish(a.folder,"post-refresh-selector-transition.json",JSONObject().put("manualRefreshSnapshotId",after.getString("snapshotId"))
                .put("currentTwoEntrySnapshotId",refreshedPair.getString("snapshotId"))
                .put("exactManualRefreshSnapshotStillCurrent",after.getString("snapshotId")==refreshedPair.getString("snapshotId"))
                .put("sourceAndFrozenMetricsRechecked",true).put("subsequentOrdinaryPollAllowed",true))
            ui.onNodeWithTag("current-client-usage").performClick()
            val reopened=inspectUsageBusinessDialog(a,profile,"reopened-after-selector")
            requireSameUsageUiSnapshot(a,refreshedPair,reopened,"refreshed-footer-to-reopened-details",ordinaryPollBoundary=true)
            ui.onNodeWithText("完成").performClick();ui.onNodeWithTag("current-client-usage").assertIsDisplayed()
            publish(a.folder,"two-entry-business-result.json",JSONObject().put("productionSelectorOpened",true).put("exactSummaryPhysicallyTapped",true)
                .put("selectorFooterAndDetailsSnapshotsMatched",allCrossEntrySnapshotsMatched)
                .put("postRefreshCurrentSnapshotLinkedToBothEntries",allCrossEntrySnapshotsMatched)
                .put("sameSourceAndMetricsAcrossEntries",true).put("ordinaryPollSnapshotAdvanceObserved",!allCrossEntrySnapshotsMatched)
                .put("partialApplicable",reopened.optBoolean("partial")).put("partialUiAssertionsExecuted",true)
                .put("realClientCount",1).put("secondRealTargetSwitchPerformed",false).put("dualProviderVerified",false)
                .put("realErrorRecoveryPerformed",false).put("fullViewportMatrixPassed",false).put("wireCorrelationRequired",false)
                .put("approvedClientIdSha256",digest(approvedUsageClientId(a))).put("finalSnapshotId",reopened.getString("snapshotId")))
            boundedMain {profile.checkOnlyApprovedTarget();require(session.selectedThread.isEmpty()&&session.text.isEmpty()&&session.operationRecords.isEmpty())}
            publish(a.folder,"usage-read-verdict.json",JSONObject().put("footerAndProductionDialogRead",true).put("realSourceAndExactMetrics",true)
                .put("manualRefreshClicked",true).put("newProductionSnapshot",true).put("loadingStateVerified",loadingFrameVerified)
                .put("wireRequestCorrelationVerified",false).put("fullF73GatePassed",false)
                .put("scopeLabelProvidedBySource",!after.getJSONObject("source").isNull("scopeLabel"))
                .put("noConversationSelected",true).put("newModelAndQueueInputs",0).put("ordinaryStorageMounted",false)
                .put("sourceProofSha256",p.getString("desktopSourceSha256")).put("nativeSystemIntegrations",false))
        } catch(error: Throwable) {
            val failure=AssertionError("F73 real DEV candidate failed; preserve original-failure-private.json and original device result",error)
            primary=failure
            owner.preparedProfile()?.let {profile->runCatching {publish(a.folder,"failure-private-projection.json",projection(profile))}}
            try {
                val state=owner.snapshot()
                publish(a.folder,"original-failure-private.json",JSONObject().put("class",error.javaClass.name).put("message",error.message).put("privateOriginalStack",error.stackTraceToString())
                    .put("profile",state.optJSONObject("profile")?:JSONObject.NULL).put("boundedLaunch",state).put("noAutomaticClaimOrModelReplay",true))
            }catch(evidence: Throwable){if(evidence!==failure)failure.addSuppressed(evidence)}
            throw failure
        } finally {
            var cleanupFailure: Throwable?=null
            fun remember(failure: Throwable){val prior=cleanupFailure;if(prior==null)cleanupFailure=failure else if(prior!==failure)prior.addSuppressed(failure)}
            var resource: JSONObject?=null
            try {resource=owner.cleanup()}catch(failure: Throwable){remember(failure)}
            try {publish(a.folder,"profile-resource-state.json",resource?:owner.snapshot().put("ownedResourcesClosed",false))}catch(failure: Throwable){remember(failure)}
            if(resource?.optBoolean("ownedResourcesClosed")!=true)remember(IllegalStateException("Owned Activity/writer/permission/callback closure incomplete; retain exact profile and do not replay"))
            cleanupFailure?.let {failure->val original=primary;if(original!=null){if(original!==failure)original.addSuppressed(failure)}else throw failure}
        }
    }
    // Separate collection entry. The frozen-Source test and its strict equality gate above stay intact.
    private fun jointPending()=JSONObject().put("SourceAgreementPending",true).put("fullF73GatePassed",false)
        .put("independentDesktopWitnessRequired",true).put("sourceAgreementPassed",false)
    private data class JointSnapshot(val value: JSONObject,val receipt: JSONObject)
    private inner class JointEvidence(val a: Admission,val profile: Q02RealReadInstallation.Profile) {
        val snapshots=JSONArray();val phases=JSONArray()
        fun snapshot(label: String): JointSnapshot {
            require(snapshots.length()<96&&label.matches(Regex("[a-z0-9-]{1,64}")))
            val value=projection(profile) // Original full-target guard; never an expected value or replacement reader.
            val id=value.optString("snapshotId")
            val stem="joint-${snapshots.length()+1}-$label-${digest(id).take(16)}"
            publish(a.folder,"$stem-projection.json",value) // Exact actual JSON, including unrounded values/times.
            val file=File(a.folder,"$stem-projection.json")
            val flight=runCatching {refreshing(profile)}
            val receipt=jointPending().put("label",label).put("snapshotId",id)
                .put("projectionFile",file.name).put("projectionSha256",fileDigest(file)).put("projectionBytes",file.length())
                .put("observedAtWallMillis",System.currentTimeMillis()).put("observedAtElapsedMillis",SystemClock.elapsedRealtime())
                .put("refreshing",flight.getOrNull()?:JSONObject.NULL).put("sourceTimesRewritten",false)
                .put("refreshingObservationFailure",flight.exceptionOrNull()?.javaClass?.name?:JSONObject.NULL)
            snapshots.put(receipt);publish(a.folder,"$stem-receipt.json",receipt)
            flight.exceptionOrNull()?.let {throw it}
            // Live identity, availability and freshness still gate collection. Only desktop VALUE agreement is deferred.
            require(System.currentTimeMillis()<a.plan.getLong("leaseExpiresAtMillis"))
            require(value.optString("status")=="ok"&&!value.optBoolean("stale")&&!value.optBoolean("cached"))
            require(id.isNotEmpty()&&id.length<=256)
            require(easyCanonical(value.getJSONObject("source"))==easyCanonical(a.expected.getJSONObject("source")))
            val sampled=Instant.parse(value.getString("sampledAt")).toEpochMilli()
            require(sampled<=System.currentTimeMillis()+120_000)
            val metrics=value.getJSONArray("metrics");require(metrics.length() in 1..16)
            for(index in 0 until metrics.length()) {
                val metric=metrics.getJSONObject(index)
                require(Instant.parse(metric.getString("observedAt")).toEpochMilli()<=sampled+120_000)
                require(Instant.parse(metric.getString("validUntil")).toEpochMilli()>=System.currentTimeMillis())
            }
            return JointSnapshot(value,receipt)
        }
        fun frame(name: String,node: SemanticsNodeInteraction,assertion: (JSONObject)->Unit): JointSnapshot {
            require(phases.length()<32)
            val before=snapshot("$name-before")
            val stem="joint-frame-${phases.length()+1}-$name-${digest(before.value.getString("snapshotId")).take(16)}"
            var failure: Throwable?=null
            try {assertion(before.value);shot(a,node,stem)}catch(error: Throwable){failure=error}
            val after=try {snapshot("$name-after")}catch(error: Throwable) {
                val prior=failure;if(prior==null)failure=error else if(prior!==error)prior.addSuppressed(error)
                null
            }
            val stable=after!=null&&before.value.getString("snapshotId")==after.value.getString("snapshotId")
                &&easyCanonical(before.value)==easyCanonical(after.value)
            val link=jointPending().put("phase",name).put("before",before.receipt)
                .put("after",after?.receipt?:JSONObject.NULL).put("snapshotStableDuringObservation",stable)
                .put("uiAssertionsReturned",failure==null).put("uiSnapshotAssociationVerified",failure==null&&stable)
                .put("snapshotAdvanceIsNotAloneAProductDefect",true).put("failureClass",failure?.javaClass?.name?:JSONObject.NULL)
            for(suffix in listOf(".png","-ax.json")) {
                val file=File(a.folder,stem+suffix)
                if(file.isFile)link.put(if(suffix==".png")"png" else "ax",JSONObject().put("file",file.name)
                    .put("sha256",fileDigest(file)).put("bytes",file.length()))
            }
            phases.put(link);publish(a.folder,"$stem-link.json",link)
            failure?.let {throw it}
            return checkNotNull(after)
        }
        fun index(completed: Boolean)=jointPending().put("kind","f73-joint-usage-evidence-index-v1")
            .put("collectionCompleted",completed).put("snapshots",snapshots).put("phases",phases)
            .put("run",a.plan.getString("run")).put("leaseId",a.plan.getString("leaseId")).put("target",a.plan.getJSONObject("target"))
            .put("planSha256",fileDigest(File(a.folder,"plan.json"))).put("resourceClosureRequiresSeparateReceipt",true)
            .put("frozenSourceSha256",a.plan.getString("desktopSourceSha256"))
            .put("frozenSemanticMetricsSha256",a.plan.getString("semanticMetricsSha256"))
            .put("everyActualSnapshotNeedsIndependentDesktopComparison",true)
            .put("phoneValuesFedBackToReader",false).put("newSourceOrExpectedDataCreated",false)
            .put("fullViewportMatrixPassed",false).put("dualProviderVerified",false).put("secondRealTargetSwitchPerformed",false)
    }
    private fun jointDialog(e: JointEvidence,name: String): JointSnapshot {
        ui.onNode(isDialog()).assertIsDisplayed();ui.waitUntil(usageObservationWaitMillis()){!refreshing(e.profile)}
        e.frame("$name-coverage",ui.onNode(isDialog())) {data->
            val partial=ui.onNode(hasText("部分额度暂不可用") and hasAnyAncestor(isDialog()),useUnmergedTree=true)
            if(data.optBoolean("partial"))partial.performScrollTo().assertIsDisplayed() else partial.assertDoesNotExist()
        }
        var observed=e.frame("$name-source",ui.onNode(isDialog())) {data->
            visible(e.a,"当前客户端额度")
            val source=data.getJSONObject("source")
            if(!source.isNull("scopeLabel"))visible(e.a,"额度来源："+source.getString("scopeLabel"))
            if(!source.isNull("planLabel"))visible(e.a,"套餐："+source.getString("planLabel"))
            visible(e.a,"更新时间："+data.getString("sampledAt"))
        }
        val metrics=observed.value.getJSONArray("metrics")
        val ids=(0 until metrics.length()).map {metrics.getJSONObject(it).getString("id")}
        for((index,id) in ids.withIndex())observed=e.frame("$name-metric-$index",ui.onNode(isDialog())) {data->
            val current=data.getJSONArray("metrics")
            val metric=(0 until current.length()).map {current.getJSONObject(it)}.single {it.getString("id")==id}
            visible(e.a,usageMetricTitle(metric));visible(e.a,usageMetricText(metric))
            if(metric.optString("state")=="unavailable")visible(e.a,UsageReadRules.reasonKey(metric.getString("value")))
            val applies=metric.getJSONObject("appliesTo")
            visible(e.a,"适用范围："+if(applies.getString("kind")=="all")"所有模型和功能" else applies.getJSONArray("ids").strings().joinToString(" · "))
            visible(e.a,when(metric.getJSONObject("combination").getString("kind")){"all-of"->"全部限制同时生效";"any-of"->"任一限制可用";else->"按服务端规则"})
            visible(e.a,"观察时间："+metric.getString("observedAt"))
            if(!metric.isNull("note"))visible(e.a,metric.getString("note"))
            if(!metric.isNull("resetAt"))visible(e.a,"重置时间："+metric.getString("resetAt"))
            if(!metric.isNull("expiresAt"))visible(e.a,"到期时间："+metric.getString("expiresAt"))
        }
        return observed
    }
    private fun jointSelector(e: JointEvidence,name: String): JointSnapshot {
        val approvedId=approvedUsageClientId(e.a);e.snapshot("$name-pre-selector")
        ui.onNodeWithTag("current-client-usage").assertIsDisplayed()
        ui.onNodeWithTag("sidebar-client-switch").assertIsDisplayed().assertHasClickAction().performClick()
        ui.onNodeWithTag("sheet-clients").assertIsDisplayed()
        ui.onNodeWithTag("client-$approvedId",useUnmergedTree=true).performScrollTo().assertIsDisplayed().assertHasClickAction()
        val summary=ui.onNodeWithTag("client-usage-summary-$approvedId",useUnmergedTree=true)
            .assert(hasAnyAncestor(hasTestTag("client-$approvedId")))
        ui.waitUntil(usageObservationWaitMillis()){!refreshing(e.profile)}
        val selected=e.frame("$name-selector",summary){assertUsageEntry(it,summary,true)}
        summary.performTouchInput {click(center)} // Physical summary tap, same approved target; no helper refresh call.
        ui.waitUntil(5000){ui.onAllNodesWithTag("sheet-clients",useUnmergedTree=true).fetchSemanticsNodes().isEmpty()}
        ui.waitUntil(usageObservationWaitMillis()){!refreshing(e.profile)}
        val footer=e.frame("$name-footer",ui.onNodeWithTag("current-client-usage")) {
            assertUsageEntry(it,ui.onNodeWithTag("current-client-usage",useUnmergedTree=true),false)
        }
        publish(e.a.folder,"joint-$name-selection.json",jointPending().put("selectedSnapshotId",selected.value.getString("snapshotId"))
            .put("footerSnapshotId",footer.value.getString("snapshotId")).put("approvedClientIdSha256",digest(approvedId))
            .put("sameApprovedTargetReSelected",true).put("secondRealTargetSwitchPerformed",false)
            .put("bindingsCreatedByThisUiPhase",0).put("directProviderCallsByHelper",0))
        return footer
    }
    @Test fun newInstallationProductionUsageJointEvidencePendingIndependentDesktopAgreement() {
        require(InstrumentationRegistry.getArguments().getString("f73JointEvidenceReady")=="1") {"Explicit Root joint-evidence opt-in required"}
        val a=admit() // Original frozen Source/owner/target/certificate/freshness and immutable input admission.
        val p=a.plan;val scope=checkNotNull(HostReadCodec.target(p.getJSONObject("target")))
        val permit=Q02RealReadInstallation.Permit(p.getString("run"),p.getString("leaseId"),p.getString("installationAndroidIdSha256"),
            p.getString("appSha256"),p.getString("runnerSha256"),p.getString("signerSha256"),p.getInt("api"),
            SystemClock.elapsedRealtime()+p.getLong("leaseExpiresAtMillis")-System.currentTimeMillis(),p.getString("controlHostId"),
            p.getString("localMappingSha256"),scope,p.getString("endpoint"),p.getJSONArray("allowedEndpoints").strings(),
            p.getString("certificateSha256"),p.getString("pairingIntentSha256"),
            if(scope.agentId=="easywork")p.getString("desktopPublicKeySha256")else null)
        val owner=F73UsageActivityLaunch(context,permit);launchOwner=owner
        var evidence: JointEvidence?=null;var completed=false;var primary: Throwable?=null
        try {
            publish(a.folder,"joint-evidence-intent.json",jointPending().put("kind","f73-joint-usage-evidence-intent-v1")
                .put("run",p.getString("run")).put("leaseId",p.getString("leaseId"))
                .put("frozenSourceAdmissionOnly",true).put("maxProjectionCheckpoints",96).put("maxUIPhases",32)
                .put("manualRefreshClicksMaximum",1).put("oldFrozenSourceTestUnchanged",true))
            val profile=try {owner.prepare()}catch(error: Throwable) {
                publish(a.folder,"profile-prepare-failed.json",owner.snapshot().put("failureClass",error.javaClass.name));throw error
            }
            publish(a.folder,"profile-prepared.json",boundedMain {profile.proof()})
            owner.startAndAwait();val launch=owner.snapshot();publish(a.folder,"bounded-launch-state.json",launch)
            check(launch.getBoolean("mainObservationComplete"))
            usageObservationDeadlineElapsed=minOf(permit.expiresAtElapsed,launch.getLong("workDeadlineElapsed"))
            ui.waitUntil(30000){boundedMain {profile.session?.restoring==false}}
            val session=boundedMain {checkNotNull(profile.session)}
            boundedMain {require(session.clients.none {!it.isDemo}&&session.operationRecords.isEmpty()&&session.text.isEmpty())}
            runBlocking {withTimeout(minOf(120_000L,permit.expiresAtElapsed-SystemClock.elapsedRealtime())){withContext(Dispatchers.Main){Q02RealReadInstallation.pairFreshTarget(profile,a.pairing)}}}
            ui.waitUntil(30000){!session.loading}
            boundedMain {profile.checkOnlyApprovedTarget();require(session.selectedThread.isEmpty()&&session.text.isEmpty()&&session.operationRecords.isEmpty())}
            val e=JointEvidence(a,profile);evidence=e
            ui.onNodeWithTag("open-sidebar").performClick()
            ui.waitUntil(usageObservationWaitMillis()){!refreshing(profile)&&projection(profile).optString("status")=="ok"}
            e.frame("footer",ui.onNodeWithTag("current-client-usage")) {
                assertUsageEntry(it,ui.onNodeWithTag("current-client-usage",useUnmergedTree=true),false)
            }
            jointSelector(e,"initial")
            ui.onNodeWithTag("current-client-usage").performClick();jointDialog(e,"before-refresh")
            var stableId="";var stableAt=SystemClock.elapsedRealtime()
            ui.waitUntil(usageObservationWaitMillis(60_000L)) {
                val id=projection(profile).optString("snapshotId");val now=SystemClock.elapsedRealtime()
                if(refreshing(profile)||id!=stableId){stableId=id;stableAt=now}
                id.isNotEmpty()&&!refreshing(profile)&&now-stableAt>=5500
            }
            val before=e.frame("manual-before",ui.onNode(isDialog())) {ui.onNodeWithText("刷新额度").assertIsDisplayed().assertIsEnabled()}
            require(before.value.getString("snapshotId")==stableId) {"Snapshot advanced after stable window; preserve this case without another click"}
            val clickedAt=SystemClock.elapsedRealtime()
            publish(a.folder,"joint-manual-refresh-action.json",jointPending().put("previousSnapshotId",before.value.getString("snapshotId"))
                .put("clickedAtElapsed",clickedAt).put("stableCompletedSnapshotForMillis",clickedAt-stableAt)
                .put("productionButtonOnly",true).put("maximumClicks",1))
            ui.onNodeWithText("刷新额度").performClick()
            val flight=refreshing(profile)
            val disabled=runCatching {ui.onNodeWithText("刷新额度").assertIsNotEnabled();true}.getOrDefault(false)
            val spinner=runCatching {ui.onNodeWithTag("usage-refresh-loading",useUnmergedTree=true).assertIsDisplayed();true}.getOrDefault(false)
            val loadingRecord=jointPending().put("inFlightObserved",flight).put("buttonDisabledObserved",disabled)
                .put("spinnerObserved",spinner).put("observedAtElapsed",SystemClock.elapsedRealtime())
                .put("additionalClickAllowed",false).put("missingFrameIsNotAloneAProductDefect",true)
            if(flight||disabled||spinner) {
                val phaseBefore=e.phases.length()
                var frameConditions=false
                e.frame("manual-loading",ui.onNode(isDialog())) {
                    frameConditions=runCatching {
                        ui.onNodeWithText("刷新额度").assertIsNotEnabled()
                        ui.onNodeWithTag("usage-refresh-loading",useUnmergedTree=true).assertIsDisplayed();true
                    }.getOrDefault(false) // A fast frame may disappear; preserve it as unverified, never click again.
                }
                val phase=e.phases.getJSONObject(phaseBefore)
                loadingRecord.put("spinnerAndDisabledConfirmedInFrame",frameConditions)
                    .put("loadingStateVerified",flight&&disabled&&spinner&&frameConditions&&phase.getBoolean("uiSnapshotAssociationVerified")
                    &&phase.getJSONObject("before").getBoolean("refreshing")&&phase.getJSONObject("after").getBoolean("refreshing"))
                    .put("phase",phase)
            } else loadingRecord.put("loadingStateVerified",false).put("classification","fast-loading-frame-not-observed")
            publish(a.folder,"joint-manual-refresh-loading.json",loadingRecord)
            ui.waitUntil(usageObservationWaitMillis()){!refreshing(profile)&&projection(profile).optString("snapshotId")!=before.value.getString("snapshotId")}
            val after=e.frame("manual-after",ui.onNode(isDialog())) {
                ui.onNodeWithText("刷新额度").assertIsDisplayed().assertIsEnabled()
                ui.onNodeWithTag("usage-refresh-loading",useUnmergedTree=true).assertDoesNotExist()
            }
            jointDialog(e,"after-refresh")
            publish(a.folder,"joint-manual-refresh-result.json",jointPending().put("previousSnapshotId",before.value.getString("snapshotId"))
                .put("newSnapshotId",after.value.getString("snapshotId")).put("loadingStateVerified",loadingRecord.getBoolean("loadingStateVerified"))
                .put("newSnapshotObservedAtElapsed",SystemClock.elapsedRealtime()).put("wireRequestCorrelationVerified",false))
            ui.onNodeWithText("完成").performClick();jointSelector(e,"refreshed")
            ui.onNodeWithTag("current-client-usage").performClick();jointDialog(e,"reopened")
            ui.onNodeWithText("完成").performClick();ui.onNodeWithTag("current-client-usage").assertIsDisplayed()
            boundedMain {profile.checkOnlyApprovedTarget();require(session.selectedThread.isEmpty()&&session.text.isEmpty()&&session.operationRecords.isEmpty())}
            completed=true
            publish(a.folder,"joint-evidence-result.json",e.index(true).put("kind","f73-joint-usage-evidence-collected-source-pending-v1")
                .put("manualRefreshClickedOnce",true).put("loadingStateVerified",loadingRecord.getBoolean("loadingStateVerified"))
                .put("nativeJUnitSuccessMeansCollectionOnly",true).put("newModelAndQueueInputs",0).put("wireRequestCorrelationVerified",false))
        } catch(error: Throwable) {
            primary=error
            evidence?.let {e->runCatching {e.snapshot("failure")}} // Every saved actual projection stays in the witness-required index.
            try {publish(a.folder,"original-failure-private.json",jointPending().put("class",error.javaClass.name)
                .put("message",error.message).put("privateOriginalStack",error.stackTraceToString()).put("boundedLaunch",owner.snapshot())
                .put("noAutomaticClaimOrModelReplay",true))}catch(save: Throwable){if(save!==error)error.addSuppressed(save)}
            throw error
        } finally {
            var cleanupFailure: Throwable?=null
            fun remember(error: Throwable){val prior=cleanupFailure;if(prior==null)cleanupFailure=error else if(prior!==error)prior.addSuppressed(error)}
            try {publish(a.folder,"joint-evidence-index.json",evidence?.index(completed)?:jointPending().put("collectionCompleted",false))}catch(error: Throwable){remember(error)}
            var resource: JSONObject?=null
            try {resource=owner.cleanup()}catch(error: Throwable){remember(error)}
            try {publish(a.folder,"profile-resource-state.json",resource?:owner.snapshot().put("ownedResourcesClosed",false))}catch(error: Throwable){remember(error)}
            if(resource?.optBoolean("ownedResourcesClosed")!=true)remember(IllegalStateException("Owned resources not closed; retain exact case and do not replay"))
            cleanupFailure?.let {error->val original=primary;if(original!=null){if(original!==error)original.addSuppressed(error)}else throw error}
        }
    }
}
