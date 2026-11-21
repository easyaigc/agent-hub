package com.agenthub.android

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailabilityLight
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.installations.FirebaseInstallationsException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

internal class NativeChatFirebaseConfiguration(val packageName:String,val applicationId:String,val projectId:String,val senderId:String,val apiKey:String) {
    init {
        require(packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")))
        require(senderId.matches(Regex("[0-9]{6,32}"))&&applicationId.matches(Regex("1:"+senderId+":android:[a-f0-9]{16,64}")))
        require(projectId.matches(Regex("[a-z][a-z0-9-]{4,61}[a-z0-9]"))&&apiKey.matches(Regex("[A-Za-z0-9_-]{20,256}")))
    }
    val fingerprint=easyHash(easyCanonical(JSONObject().put("package",packageName).put("app",applicationId).put("project",projectId).put("sender",senderId).put("apiKey",apiKey)).toByteArray(Charsets.UTF_8))
    fun options():FirebaseOptions=FirebaseOptions.Builder().setApplicationId(applicationId).setProjectId(projectId).setGcmSenderId(senderId).setApiKey(apiKey).build()
    override fun toString()="NativeChatFirebaseConfiguration(redacted)"
    companion object {
        fun current(context:Context):NativeChatFirebaseConfiguration? {
            val values=listOf(BuildConfig.FCM_PACKAGE,BuildConfig.FCM_APP_ID,BuildConfig.FCM_PROJECT_ID,BuildConfig.FCM_SENDER_ID,BuildConfig.FCM_API_KEY)
            if(values.all(String::isEmpty))return null
            require(values.none(String::isEmpty)&&BuildConfig.FCM_PACKAGE==context.packageName) { "后台通知配置与当前安装不匹配" }
            return NativeChatFirebaseConfiguration(values[0],values[1],values[2],values[3],values[4])
        }
    }
}

/** Provider acquisition alone never means that a Host subscription is ready. */
internal class NativeChatNotificationPushProvider(private val context: Context) {
    fun available(): Boolean {
        val code = GoogleApiAvailabilityLight.getInstance().isGooglePlayServicesAvailable(context)
        if (BuildConfig.DEBUG) NativeChatNotificationPushDiagnostic.record("google-services", NativeChatNotificationPushDiagnostic.availability(code))
        return code == ConnectionResult.SUCCESS
    }
    suspend fun token(): String? {
        val attempt = if (BuildConfig.DEBUG) NativeChatNotificationPushDiagnostic.beginToken() else 0L
        var outcome = "exception"
        var failure: Throwable? = null
        try {
            val config = NativeChatFirebaseConfiguration.current(context) ?: run { outcome = "not-configured"; return null }
            if (!available()) { outcome = "google-services-unavailable"; return null }
            val completed = withTimeoutOrNull(15_000) { suspendCancellableCoroutine<Pair<Boolean, String?>> { continuation ->
                messaging(context, config).token.addOnCompleteListener(java.util.concurrent.Executor { it.run() }) { task ->
                    if (BuildConfig.DEBUG) NativeChatNotificationPushDiagnostic.record("token-task",
                        NativeChatNotificationPushDiagnostic.tokenTaskEvent(attempt, task.isSuccessful, continuation.isActive, task.exception))
                    if (continuation.isActive) continuation.resume(true to if (task.isSuccessful) task.result else null)
                }
            } }
            outcome = when { completed == null -> "timeout"; completed.second == null -> "unavailable"; else -> "success" }
            return completed?.second
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            outcome = "cancelled"
            throw cancel
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (BuildConfig.DEBUG) NativeChatNotificationPushDiagnostic.record("token-result",
                NativeChatNotificationPushDiagnostic.tokenResultEvent(attempt, outcome, failure))
        }
    }
    companion object {
        private val initialization = Any()
        fun messaging(context: Context, config: NativeChatFirebaseConfiguration): FirebaseMessaging = synchronized(initialization) {
            require(config.packageName == context.packageName)
            val options = config.options()
            val existing = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
            require(existing == null || existing.options == options)
            val app = existing ?: FirebaseApp.initializeApp(context, options)
            app.isDataCollectionDefaultEnabled = false
            FirebaseMessaging.getInstance().also { it.isAutoInitEnabled = false }
        }
    }
}

/** Fixed debug metadata only: no messages, tokens, options, IDs or record values. */
internal object NativeChatNotificationPushDiagnostic {
    const val TAG = "AgentHubPushState"
    private val lastByStage = HashMap<String, String>()
    private var tokenAttempt = 0L
    private val knownClasses = setOf("com.google.firebase.installations.FirebaseInstallationsException",
        "java.io.IOException", "java.net.UnknownHostException", "java.net.SocketTimeoutException",
        "java.net.ConnectException", "java.net.SocketException", "javax.net.ssl.SSLException",
        "javax.net.ssl.SSLHandshakeException", "javax.net.ssl.SSLPeerUnverifiedException",
        "java.lang.SecurityException", "java.lang.IllegalStateException", "java.util.concurrent.ExecutionException", "com.google.android.gms.cloudmessaging.zzt")
    // Exact constants from the resolved Messaging/Cloud Messaging SDK only. Never log an
    // arbitrary exception message, even when it starts with one of these constants.
    private fun sdkErrorCode(error: Throwable): String? {
        if (error.javaClass.name != "java.io.IOException") return null
        return when (error.message) {
            "SERVICE_NOT_AVAILABLE" -> "SERVICE_NOT_AVAILABLE"
            "INTERNAL_SERVER_ERROR", "InternalServerError" -> "INTERNAL_SERVER_ERROR"
            "INSTANCE_ID_RESET" -> "INSTANCE_ID_RESET"
            "MISSING_INSTANCEID_SERVICE" -> "MISSING_INSTANCEID_SERVICE"
            "TIMEOUT" -> "TIMEOUT"
            else -> null
        }
    }
    private fun sdkIpcErrorCode(error: Throwable): String? {
        if (error.javaClass.name != "com.google.android.gms.cloudmessaging.zzt") return null
        return when (error.message) {
            "Unable to bind to service" -> "UNABLE_TO_BIND"
            "Timed out while binding" -> "BIND_TIMEOUT"
            "Service disconnected" -> "SERVICE_DISCONNECTED"
            "Timed out waiting for response" -> "RESPONSE_TIMEOUT"
            else -> null
        }
    }
    fun availability(code: Int): String = JSONObject().put("stage", "google-services").put("code", code).toString()
    fun failure(error: Throwable?): String {
        val causes = org.json.JSONArray(); var current = error
        repeat(4) {
            val item = current ?: return@repeat
            val type = item.javaClass.name
            val value = JSONObject().put("class", if (type in knownClasses) type else "OTHER")
            sdkErrorCode(item)?.let { value.put("errorCode", it) }
            sdkIpcErrorCode(item)?.let { value.put("ipcErrorCode", it) }
            if (type == "com.google.firebase.installations.FirebaseInstallationsException" && item is FirebaseInstallationsException)
                value.put("status", item.status.name.takeIf { it in setOf("BAD_CONFIG", "UNAVAILABLE", "TOO_MANY_REQUESTS") } ?: "OTHER")
            causes.put(value); current = item.cause
        }
        return JSONObject().put("stage", "token-task").put("causes", causes).toString()
    }
    @Synchronized fun beginToken(): Long {
        val attempt = ++tokenAttempt
        record("token-request", JSONObject().put("stage", "token-request").put("attempt", attempt).toString())
        return attempt
    }
    fun tokenTaskEvent(attempt: Long, successful: Boolean, callerActive: Boolean, error: Throwable?): String {
        require(attempt > 0)
        val value = if (successful) JSONObject().put("stage", "token-task") else JSONObject(failure(error))
        return value.put("attempt", attempt).put("successful", successful).put("callerActive", callerActive).toString()
    }
    fun tokenResultEvent(attempt: Long, outcome: String, error: Throwable? = null): String {
        require(attempt > 0 && outcome in setOf("not-configured", "google-services-unavailable", "timeout", "unavailable", "success", "cancelled", "exception"))
        val value = JSONObject().put("stage", "token-result").put("attempt", attempt).put("outcome", outcome)
        if (error != null) value.put("causes", JSONObject(failure(error)).getJSONArray("causes"))
        return value.toString()
    }
    @Synchronized fun record(stage: String, value: String) {
        require(stage in setOf("google-services", "token-task", "token-timeout", "token-request", "token-result", "enrollment-gate"))
        if (BuildConfig.DEBUG && lastByStage[stage] != value) {
            lastByStage[stage] = value
            android.util.Log.d(TAG, value)
        }
    }
}
