package com.agenthub.android

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID

/** Real SDK callbacks only. The second method advertises its own inert loopback socket;
 * no external peer, Host, application protocol, credential or model is used.
 * Callback witness delegates every terminal unchanged. It cannot fabricate an outcome.
 */
class NativeNsdSdkTest {
    private val i get()=InstrumentationRegistry.getInstrumentation()
    private val id=UUID.randomUUID().toString()
    private val type="_p8"+id.replace("-","").take(8)+"._tcp."
    private lateinit var build: JSONObject
    private lateinit var folder: File
    private lateinit var sdk: NativeNsdLifetime<NsdServiceInfo>
    private lateinit var owners: NativeEasyWorkMigrationOwners
    private var owner: NativeEasyWorkMigrationOwners.Owner?=null
    private val events=java.util.Collections.synchronizedList(mutableListOf<String>())
    private val started=CompletableDeferred<Unit>()
    private val resolutionIssued=CompletableDeferred<Unit>()
    private val registered=CompletableDeferred<NsdServiceInfo>()
    private val unregistered=CompletableDeferred<Unit>()
    private var registration: NsdManager.RegistrationListener?=null
    private var registrationAttempted=false
    @Volatile private var registrationFailed=false
    private var socket: ServerSocket?=null
    @Volatile private var ownedName: String?=null
    @Volatile private var resolvedOwnPeer=false
    private var defaultBefore=emptyMap<String,String>()
    private var sdkJoined=false
    private var advertisementJoined=false
    private var defaultsUnchanged=false
    private val retirementStarted=java.util.concurrent.atomic.AtomicLong()
    @get:Rule val outcome=object: TestWatcher() {
        override fun succeeded(description: Description)=record(description,"passed")
        override fun failed(error: Throwable,description: Description)=record(description,"failed",error.javaClass.name)
    }
    private fun sha(file: File)=file.inputStream().use {input->val d=java.security.MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536);while(true){val n=input.read(buffer);if(n<0)break;d.update(buffer,0,n)};d.digest().joinToString("") {"%02x".format(it)}}
    private fun defaults(): Map<String,String> {
        val base=i.targetContext.noBackupFilesDir.toPath();if(!Files.exists(base))return emptyMap()
        val result=linkedMapOf<String,String>();var roots=0
        Files.newDirectoryStream(base,"mobile-v1*").use {entries->for(root in entries) {
            val name=root.fileName.toString();if(name!="mobile-v1"&&!name.startsWith("mobile-v1-"))continue
            check(++roots<=64);check(!Files.isSymbolicLink(root))
            Files.walk(root).use {walk->val iterator=walk.iterator();while(iterator.hasNext()) {
                val path=iterator.next();check(!Files.isSymbolicLink(path));if(!Files.isRegularFile(path))continue
                check(result.size<1024);result[base.relativize(path).toString()]=sha(path.toFile())
            }}
        }}
        check(easyCanonical(JSONObject(result)).toByteArray().size<=262144);return result
    }
    @Before fun preflight() {
        require(i.targetContext.packageName=="com.easyai.agenthub.android.acceptance"&&i.context.packageName=="com.easyai.agenthub.android.acceptance.test")
        build=JSONObject(requireNotNull(InstrumentationRegistry.getArguments().getString("p8NsdBuild")))
        for(key in listOf("sourceSha256","appSha256","runnerSha256"))require(build.getString(key).matches(Regex("[a-f0-9]{64}")))
        require(build.getInt("expectedSdk") in listOf(26,37));assertEquals(build.getInt("expectedSdk"),Build.VERSION.SDK_INT)
        assertEquals(build.getString("appSha256"),sha(File(i.targetContext.applicationInfo.sourceDir)));assertEquals(build.getString("runnerSha256"),sha(File(i.context.applicationInfo.sourceDir)))
        defaultBefore=defaults()
        folder=File(i.targetContext.filesDir,"acceptance/p8-nsd-owner/$id").apply {check(!exists()&&mkdirs())}
        if(Build.VERSION.SDK_INT>=37)check(i.targetContext.checkSelfPermission(CodexHostDiscovery.LOCAL_PERMISSION)==android.content.pm.PackageManager.PERMISSION_GRANTED) {"Actual local-network permission is required; no permission UI is opened by this test"}
    }
    private fun start(stopAtResolution: Boolean) {
        val native=AndroidNsdPlatform(i.targetContext)
        val witness=object: NativeNsdPlatform<NsdServiceInfo> by native {
            override fun discovery(type: String,events: NativeNsdPlatform.Discovery<NsdServiceInfo>): NativeNsdPlatform.Request = native.discovery(type,object: NativeNsdPlatform.Discovery<NsdServiceInfo> by events {
                override fun found(service: NsdServiceInfo) {if(ownedName!=null&&service.serviceName==ownedName)events.found(service)}
                override fun lost(service: NsdServiceInfo) {if(ownedName!=null&&service.serviceName==ownedName)events.lost(service)}
            })
            override fun resolution(service: NsdServiceInfo,events: NativeNsdPlatform.Resolution<NsdServiceInfo>): NativeNsdPlatform.Request {
                val request=native.resolution(service,object: NativeNsdPlatform.Resolution<NsdServiceInfo> by events {
                    override fun resolved(service: NsdServiceInfo) {resolvedOwnPeer=service.serviceName==ownedName&&service.port==socket?.localPort;events.resolved(service)}
                })
                return object: NativeNsdPlatform.Request by request {
                    override fun start() {request.start();resolutionIssued.complete(Unit);if(stopAtResolution)stopSdk()}
                }
            }
        }
        owners=NativeEasyWorkMigrationOwners()
        sdk=NativeNsdLifetime(witness,AndroidNsdQueue(),type,{}, {}, {},{event,code->
            events.add(if(code==null)event else "$event:$code")
            if(event=="discovery-started")started.complete(Unit)
        })
        owner=owners.register(::stopSdk,sdk::awaitStopped);sdk.start()
    }
    private fun stopSdk() {retirementStarted.compareAndSet(0,android.os.SystemClock.elapsedRealtime());sdk.stop()}
    private suspend fun joinSdk() {
        val remaining=30_000-(android.os.SystemClock.elapsedRealtime()-retirementStarted.get())
        check(retirementStarted.get()>0&&remaining>0) {"Original 30-second retirement budget exhausted"}
        withTimeout(remaining){owners.stopAndJoin()};sdkJoined=true
    }
    @Test fun actualDiscoveryStartStopNeedsSdkCallback()=runBlocking {
        start(false);withTimeout(12_000){started.await()}
        owner!!.retire();joinSdk()
        assertTrue(sdk.isStopped);assertTrue(events.contains("discovery-stopped"));assertTrue(events.contains("network-unregistered"))
        assertFalse(resolutionIssued.isCompleted);owners.reopen();assertFalse(owners.stopping)
    }
    @Test fun actualOwnedPeerResolutionRetiresOnlyOnObservedTerminal()=runBlocking {
        val nsd=checkNotNull(i.targetContext.getSystemService(NsdManager::class.java))
        socket=ServerSocket(0,1,InetAddress.getLoopbackAddress())
        val service=NsdServiceInfo().apply {serviceName="p8-owner-"+id.replace("-","").take(16);serviceType=type;port=socket!!.localPort}
        registration=object: NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo){ownedName=info.serviceName;registered.complete(info)}
            override fun onRegistrationFailed(info: NsdServiceInfo,code: Int){registrationFailed=true;registered.completeExceptionally(IllegalStateException("owned-registration-failed:$code"))}
            override fun onServiceUnregistered(info: NsdServiceInfo){unregistered.complete(Unit)}
            override fun onUnregistrationFailed(info: NsdServiceInfo,code: Int){events.add("owned-unregistration-failed:$code")}
        }
        registrationAttempted=true;nsd.registerService(service,NsdManager.PROTOCOL_DNS_SD,registration!!)
        withTimeout(12_000){registered.await()};start(true);withTimeout(20_000){started.await();resolutionIssued.await()}
        // SDK stop may race a natural resolve callback. Record which terminal actually won.
        owner!!.retire();joinSdk()
        assertTrue(events.contains("discovery-stopped"));assertTrue(events.contains("network-unregistered"));assertTrue(sdk.isStopped)
        assertTrue(events.any {it=="resolution-stopped"||it=="resolution-resolved"||it.startsWith("resolution-failed:")})
        if(events.contains("resolution-resolved"))assertTrue(resolvedOwnPeer)
        owners.reopen();assertFalse(owners.stopping)
    }
    @After fun cleanup()=runBlocking {
        try {
            if(::sdk.isInitialized&&!sdkJoined) {owner?.retire();joinSdk();owners.reopen()}
        }finally {
            try {
                registration?.let {listener->
                    if(unregistered.isCompleted)advertisementJoined=true
                    else if(registrationAttempted&&!registrationFailed) {
                        i.targetContext.getSystemService(NsdManager::class.java).unregisterService(listener)
                        withTimeout(12_000){unregistered.await()};advertisementJoined=true
                    }
                }
            }finally {socket?.close();if(::folder.isInitialized){defaultsUnchanged=defaultBefore==defaults();assertTrue(defaultsUnchanged)}}
        }
    }
    private fun record(description: Description,result: String,error: String?=null) {
        if(!::folder.isInitialized)return
        val snapshot=synchronized(events){events.toList()}
        File(folder,"sdk-proof.json").writeText(JSONObject().put("caseId",id).put("method",description.methodName).put("junitResult",result).put("failureClass",error).put("build",build)
            .put("sdk",Build.VERSION.SDK_INT).put("events",JSONArray(snapshot)).put("sdkJoinObserved",sdkJoined)
            .put("resolutionRequestReturned",resolutionIssued.isCompleted).put("resolutionStopAckObserved",snapshot.contains("resolution-stopped"))
            .put("naturalResolutionTerminalObserved",snapshot.any {it=="resolution-resolved"||it.startsWith("resolution-failed:")})
            .put("ownedAdvertisementAttempted",registrationAttempted).put("ownedAdvertisementJoined",advertisementJoined)
            .put("ownedRegistrationFailureObserved",registrationFailed)
            .put("defaultFilesUnchanged",defaultsUnchanged).put("commonOwnerStillStopping",if(::owners.isInitialized)owners.stopping else JSONObject.NULL)
            .put("peerStatus",if(!registrationAttempted)"no peer used; discovery-only lane" else if(!resolutionIssued.isCompleted)"owned peer unavailable; resolution remains unverified" else "own process advertisement; no external peer or Host")
            .put("boundary","Actual Android adapter callbacks and synchronous network unregister. A natural terminal does not certify the API34+ stop-ack branch; no business/model/pairing proof.").toString())
    }
}
