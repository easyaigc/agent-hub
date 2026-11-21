import java.nio.file.Files

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Opt-in separate install identity for acceptance, preserving DEV/release data.
val acceptanceBuild = providers.gradleProperty("agentHubAcceptance").map {
    require(it == "true" || it == "false") { "agentHubAcceptance must be true or false" }
    it.toBoolean()
}.getOrElse(false)
val acceptanceScenario = providers.gradleProperty("agentHubAcceptanceScenario").getOrElse("default")
require(acceptanceScenario in setOf("default", "document-cold", "photo-cold", "camera-cold")) { "Unknown acceptance scenario" }
require(acceptanceScenario == "default" || acceptanceBuild) { "Cold selection scope requires an acceptance build" }
if (acceptanceScenario != "default") {
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir(".build/native/android-${acceptanceScenario}"))
}

// Explicit public client options. Server credentials never enter an Android build.
// No file means the shipped foreground path remains available without Firebase initialization.
val fcmConfigPath=providers.environmentVariable("AGENT_HUB_FCM_ANDROID_CONFIG").orNull
val fcmOptions:Map<String,String> = if(fcmConfigPath==null)emptyMap() else run {
    val input=file(fcmConfigPath)
    require(input.isFile&&!Files.isSymbolicLink(input.toPath())&&input.length() in 1..65536) { "Invalid private FCM client configuration file" }
    val value=try { groovy.json.JsonSlurper().parseText(input.readText()) as? Map<*,*> } catch(_:Exception) { null }
    require(value!=null&&value.keys==setOf("version","packageName","applicationId","projectId","senderId","apiKey")&&value["version"]==1) { "Invalid FCM client configuration shape" }
    value.filterKeys { it!="version" }.map { (key,value)->
        require(key is String&&value is String&&value.isNotBlank()&&value.length<=256) { "Invalid FCM client option" }
        key to value
    }.toMap()
}
val debugInstallSuffix = when (acceptanceScenario) {
    "document-cold" -> ".acceptance.documents"
    "photo-cold" -> ".acceptance.photos"
    "camera-cold" -> ".acceptance.cameras"
    else -> if (acceptanceBuild) ".acceptance" else ".debug"
}
val fcmPackage=fcmOptions["packageName"]
require(fcmPackage==null||fcmPackage in setOf("com.easyai.agenthub.android","com.easyai.agenthub.android"+debugInstallSuffix)) { "FCM options must match an exact install identity" }

android {
    namespace = "com.agenthub.android"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.easyai.agenthub.android"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        for ((name,key) in mapOf("FCM_PACKAGE" to "packageName", "FCM_APP_ID" to "applicationId", "FCM_PROJECT_ID" to "projectId", "FCM_SENDER_ID" to "senderId", "FCM_API_KEY" to "apiKey"))
            buildConfigField("String", name, groovy.json.JsonOutput.toJson(fcmOptions[key].orEmpty()))
    }
    buildTypes {
        debug {
            applicationIdSuffix = when (acceptanceScenario) {
                "document-cold" -> ".acceptance.documents"
                "photo-cold" -> ".acceptance.photos"
                "camera-cold" -> ".acceptance.cameras"
                else -> if (acceptanceBuild) ".acceptance" else ".debug"
            }
            versionNameSuffix = if (acceptanceScenario != "default") "-${acceptanceScenario}-acceptance" else if (acceptanceBuild) "-acceptance" else "-dev"
        }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { animationsDisabled = true }
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("packages/protocol/fixtures/mobile"))
}

dependencies {
    implementation(project(":mobile-core"))
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // CommonMark AST renders through native Compose; no HTML/WebView execution.
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("androidx.camera:camera-camera2:1.6.0")
    implementation("androidx.camera:camera-lifecycle:1.6.0")
    implementation("androidx.camera:camera-view:1.6.0")
    implementation("com.google.zxing:core:3.5.4")
    // File-only offline ASR. Explicit Android AARs avoid desktop JNA and old 4KB 64-bit natives.
    implementation("com.alphacephei:vosk-android:0.3.75@aar") { isTransitive = false }
    implementation("net.java.dev.jna:jna:5.18.1@aar") { isTransitive = false }
    // Platform transports and API26-compatible X25519; domain rules remain in KMP.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.86")
    implementation("com.google.firebase:firebase-messaging:26.0.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Compile against the App's shipped public API without packaging another verifier or initializer.
    androidTestCompileOnly("androidx.profileinstaller:profileinstaller:1.4.0") { isTransitive = false }
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
