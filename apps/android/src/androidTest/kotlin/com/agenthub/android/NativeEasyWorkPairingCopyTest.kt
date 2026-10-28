package com.agenthub.android

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.agenthub.core.TargetScope
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Real product components/resources/resolver/AX on a blank Activity; synthetic target/status only.
 * No sidecar, original Desktop, camera, model or default MainActivity connection restore.
 */
class NativeEasyWorkPairingCopyTest {
    @get:Rule(order=0) val foreground=object: org.junit.rules.ExternalResource() {override fun before() {
        val i=InstrumentationRegistry.getInstrumentation();require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        if(InstrumentationRegistry.getArguments().getString("p8UiForeground")!="1")return
        val fd=i.uiAutomation.executeShellCommand("am start -W -n ${i.targetContext.packageName}/androidx.activity.ComponentActivity")
        assertTrue(android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use {it.readText()}.contains("Status: ok"))
    }}
    @get:Rule(order=1) val ui=createAndroidComposeRule<ComponentActivity>()
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val id=UUID.randomUUID().toString()
    private lateinit var session: MobileSession
    private val editing=mutableStateOf(true)
    private val status=mutableStateOf("请在电脑上确认此设备，核对码：731246")
    private var defaults=emptyMap<String,String>()
    private fun hash(file: File)=file.inputStream().use {input->val digest=java.security.MessageDigest.getInstance("SHA-256");val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)};digest.digest().joinToString("") {"%02x".format(it)}}
    private fun snapshot(): Map<String,String> {val root=File(i.targetContext.noBackupFilesDir,"mobile-v1");return if(!root.exists())emptyMap()else root.walkTopDown().filter {it.isFile}.associate {it.relativeTo(root).path to hash(it)}}
    @Before fun setup() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        val build=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8PairingUiBuild")));for(key in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(key).matches(Regex("[a-f0-9]{64}")));assertEquals(build.getString("appSha256"),hash(File(i.targetContext.applicationInfo.sourceDir)));assertEquals(build.getString("runnerSha256"),hash(File(i.context.applicationInfo.sourceDir)))
        defaults=snapshot();ui.runOnIdle {session=MobileSession(NativeSessionStorage(i.targetContext,"p8-pairing-copy-$id","p8-pairing-copy-$id"));session.resetAcceptance()}
    }
    @After fun finish() {if(::session.isInitialized)ui.runOnIdle {ui.activity.setContent {};session.leaveForeground()};assertEquals(defaults,snapshot())}
    private fun profile(selection: String,preferred: String,dark: Boolean,expectedLanguage: String) {
        val config=Configuration(i.targetContext.resources.configuration).apply {setLocales(LocaleList.forLanguageTags(preferred))}
        val text=NativeL10n(i.targetContext,selection,config);assertEquals(expectedLanguage,text.language)
        val additions=JSONObject(i.context.assets.open("p8-pairing-copy-additions.json").bufferedReader().use {it.readText()}).getJSONArray("entries").objects()
        val shared=JSONObject(i.context.assets.open("localization-catalog.json").bufferedReader().use {it.readText()}).getJSONObject(expectedLanguage)
        fun expected(key: String,arg: String?=null): String {
            val row=additions.firstOrNull {it.getString("key")==key}
            val value=if(row==null)shared.getString(key)else row.getString(if(expectedLanguage=="en")"en" else "zhHans")
            return if(arg==null)value else value.replace("%@",arg)
        }
        for(message in EasyWorkConnectionMessage.entries) {
            val argument=if(message==EasyWorkConnectionMessage.VERIFY)"731246" else null
            val arguments=if(argument==null)emptyArray<Any?>()else arrayOf<Any?>(argument)
            assertEquals("Installed resources must match reviewed source, including English fallback",expected(message.key,argument),text.tr(message.key,*arguments))
        }
        val scope=TargetScope("easywork","https://public-synthetic.invalid","账号","电脑","工作区%@%","运行时")
        val client=MobileClient("synthetic-display", "literal name %@",scope,"http://127.0.0.1:1",false,"easywork-runtime",false,agentConnection=AgentConnection(scope,listOf(AgentCandidate("not-used","relay-http","http://127.0.0.1:1")),"not-used"))
        val peer="PEER %@ raw text must never become a catalog key or visible error"
        val failure=EasyWorkConnectionMessage.failure(IllegalStateException(peer));assertEquals(EasyWorkConnectionMessage.FAILED,failure)
        ui.runOnIdle {ui.activity.setContent {CompositionLocalProvider(LocalConfiguration provides config) {MaterialTheme(colorScheme=if(dark)darkColorScheme() else lightColorScheme()) {Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {ProductTextProvider(selection) {Column {EasyWorkConnectionStatus(status.value);EasyWorkConnectionFailure(failure);Box(Modifier.weight(1f)) {key(editing.value) {ConnectionPage(session,if(editing.value)client else null) {error("No network action in copy fixture")}}}}}}}}}}
        ui.onNodeWithTag("easywork-connecting").assertTextEquals(expected(EasyWorkConnectionMessage.VERIFY.key,"731246")).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,LiveRegionMode.Polite))
        ui.onNodeWithTag("easywork-pair-error").assertTextEquals(expected(EasyWorkConnectionMessage.FAILED.key)).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion,LiveRegionMode.Polite))
        ui.onAllNodesWithText(peer).assertCountEquals(0)
        for((key,value) in listOf("authority" to scope.accountAuthority!!,"subject" to scope.accountSubject!!,"host" to scope.hostId,"workspace" to scope.workspaceId,"runtime" to scope.runtimeId))ui.onNodeWithTag("easywork-target-$key").performScrollTo().assertTextEquals(value)
        ui.onNodeWithTag("easywork-target-label-runtime").performScrollTo().assertTextEquals(expected("运行时"))
        ui.runOnIdle {status.value="untrusted pending %@ text"};ui.onNodeWithTag("easywork-connecting").assertTextEquals(expected(EasyWorkConnectionMessage.PENDING.key))
        assertTrue(text.tr(EasyWorkConnectionMessage.VERIFY.key,"12%@%%").contains("12%@%%"))
        ui.runOnIdle {editing.value=false}
        ui.onNodeWithTag("agent-provider").performScrollTo().assertContentDescriptionEquals(expected("Agent 客户端"));ui.onNodeWithTag("agent-provider-codex").assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab));ui.onNodeWithTag("agent-provider-easywork").performClick().assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role,Role.Tab))
        File(i.targetContext.filesDir,"acceptance/p8-pairing-copy/$id").apply {mkdirs()}.resolve("locale-ax.json").writeText(JSONObject().put("selection",selection).put("preferredLocales",preferred).put("resolvedLanguage",text.language).put("dark",dark).put("verificationUsesCatalogArgument",true).put("peerTextNotEchoed",true).put("literalTargetValuesPreserved",true).put("syntheticComponentFixture",true).toString())
    }
    @Test(timeout=25_000) fun explicitChineseCatalogAndAxIgnoreEnglishSystemPreference()=profile("zh-Hans","en-US",false,"zh-Hans")
    @Test(timeout=25_000) fun explicitEnglishCatalogAndAxIgnoreChineseSystemPreference()=profile("en","zh-CN",true,"en")
    @Test(timeout=25_000) fun followSystemUsesActualChineseResolverAndAx()=profile("system","fr-FR,zh-CN",true,"zh-Hans")
    @Test(timeout=25_000) fun followSystemUsesActualEnglishResolverAndAx()=profile("system","en-US,zh-CN",false,"en")
    @Test(timeout=25_000) fun unsupportedSystemLanguageUsesActualEnglishFallbackAndAx()=profile("system","fr-FR",true,"en")
}
