plugins { id("com.android.application") }

// Acceptance-only SDK observer. No Activity, Host transport, dependencies or product permissions.
android {
    namespace = "com.agenthub.inboxexaminer"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.easyai.agenthub.android.acceptance.coldexaminer"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
