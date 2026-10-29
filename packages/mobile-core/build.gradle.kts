import java.util.Base64 as JavaBase64

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

val generatedTests = layout.buildDirectory.dir("generated/test-vectors")
val generatedContracts = layout.buildDirectory.dir("generated/contracts")
val generateContracts by tasks.registering {
    val usageSchema = rootProject.file("packages/protocol/schemas/usage-v1.schema.json")
    inputs.file(usageSchema)
    val schema = rootProject.file("packages/protocol/schemas/agent-management-v1.schema.json")
    val httpSchema = rootProject.file("packages/protocol/schemas/codex-mobile-http-v1.schema.json")
    val discoverySchema = rootProject.file("packages/protocol/schemas/codex-host-discovery-v1.schema.json")
    val taskHubSchema = rootProject.file("packages/protocol/schemas/task-hub-mobile-v1.schema.json")
    inputs.file(taskHubSchema)
    inputs.file(schema)
    inputs.file(httpSchema)
    inputs.file(discoverySchema)
    outputs.dir(generatedContracts)
    doLast {
        val usageEncoded = JavaBase64.getEncoder().encodeToString(usageSchema.readBytes())
        val encoded = JavaBase64.getEncoder().encodeToString(schema.readBytes())
        val httpChunks = JavaBase64.getEncoder().encodeToString(httpSchema.readBytes()).chunked(8192).joinToString(",") { "\"$it\"" }
        val discoveryEncoded = JavaBase64.getEncoder().encodeToString(discoverySchema.readBytes())
        val taskHubEncoded = JavaBase64.getEncoder().encodeToString(taskHubSchema.readBytes())
        generatedContracts.get().file("ManagementSchema.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package com.agenthub.core\ninternal const val managementSchemaBase64 = \"$encoded\"\ninternal val codexHttpSchemaBase64 = listOf($httpChunks).joinToString(\"\")\ninternal const val codexDiscoverySchemaBase64 = \"$discoveryEncoded\"\n")
            appendText("internal const val usageSchemaBase64 = \"$usageEncoded\"\n")
            appendText("internal const val taskHubSchemaBase64 = \"$taskHubEncoded\"\n")
        }
    }
}
val generateTestVectors by tasks.registering {
    val suggestionVectors = rootProject.file("packages/protocol/fixtures/mobile/desktop-suggestions-v1.json")
    inputs.file(suggestionVectors)
    val usageVectors = rootProject.file("packages/protocol/fixtures/mobile/usage-v1.json")
    inputs.file(usageVectors)
    val attachmentVectors = rootProject.file("packages/protocol/fixtures/mobile/attachment-preparation-v1.json")
    inputs.file(attachmentVectors)
    val vectors = rootProject.file("packages/protocol/fixtures/mobile/core-v1.json")
    val stateVectors = rootProject.file("packages/protocol/fixtures/mobile/state-v1.json")
    val replyVectors = rootProject.file("packages/protocol/fixtures/mobile/reply-recovery-v2.json")
    val generationVectors = rootProject.file("packages/protocol/fixtures/mobile/generation-preferences-v1.json")
    inputs.file(generationVectors)
    inputs.file(vectors)
    inputs.file(stateVectors)
    inputs.file(replyVectors)
    val sharingVoiceVectors = rootProject.file("packages/protocol/fixtures/mobile/ai-sharing-voice-v1.json")
    val taskHubVectors = rootProject.file("packages/protocol/fixtures/mobile/task-hub-v1.json")
    inputs.file(taskHubVectors)
    inputs.file(sharingVoiceVectors)
    outputs.dir(generatedTests)
    doLast {
        generatedTests.get().file("DesktopSuggestionTestVectors.kt").asFile.apply {
            parentFile.mkdirs()
            val chunks = JavaBase64.getEncoder().encodeToString(suggestionVectors.readBytes()).chunked(8192).joinToString(",") { "\"$it\"" }
            writeText("package com.agenthub.core\ninternal val desktopSuggestionVectorBase64 = listOf($chunks).joinToString(\"\")\n")
        }
        generatedTests.get().file("UsageTestVectors.kt").asFile.apply {
            parentFile.mkdirs()
            val encoded = JavaBase64.getEncoder().encodeToString(usageVectors.readBytes())
            writeText("package com.agenthub.core\ninternal const val usageVectorBase64 = \"$encoded\"\n")
        }
        generatedTests.get().file("AttachmentPreparationTestVectors.kt").asFile.apply {
            parentFile.mkdirs()
            val encoded = JavaBase64.getEncoder().encodeToString(attachmentVectors.readBytes())
            writeText("package com.agenthub.core\ninternal const val attachmentPreparationVectorBase64 = \"$encoded\"\n")
        }
        generatedTests.get().file("TaskHubTestVectors.kt").asFile.apply {
            parentFile.mkdirs()
            val chunks = JavaBase64.getEncoder().encodeToString(taskHubVectors.readBytes()).chunked(8192).joinToString(",") { "\"$it\"" }
            writeText("package com.agenthub.core\ninternal val taskHubVectorBase64 = listOf($chunks).joinToString(\"\")\n")
        }
        generatedTests.get().file("AISharingVoiceTestVectors.kt").asFile.apply {
            parentFile.mkdirs()
            val chunks = JavaBase64.getEncoder().encodeToString(sharingVoiceVectors.readBytes()).chunked(8192).joinToString(",") { "\"$it\"" }
            writeText("package com.agenthub.core\ninternal val aiSharingVoiceVectorBase64 = listOf($chunks).joinToString(\"\")\n")
        }
        val encoded = JavaBase64.getEncoder().encodeToString(vectors.readBytes())
        generatedTests.get().file("MobileTestVectors.kt").asFile.apply {
            parentFile.mkdirs()
            writeText("package com.agenthub.core\ninternal const val vectorBase64 = \"$encoded\"\n")
        }
        generatedTests.get().file("StateTestVectors.kt").asFile.apply {
            parentFile.mkdirs()
            val chunks = JavaBase64.getEncoder().encodeToString(stateVectors.readBytes()).chunked(8192).joinToString(",") { "\"$it\"" }
            // JVM constants have a 64 KiB modified UTF-8 limit. Do not fold the expanded fixture into one constant.
            writeText("package com.agenthub.core\ninternal val stateVectorBase64 = listOf($chunks).joinToString(\"\")\n")
        }
        generatedTests.get().file("GenerationPreferenceTestVectors.kt").asFile.apply {
            parentFile.mkdirs()
            val chunks = JavaBase64.getEncoder().encodeToString(generationVectors.readBytes()).chunked(8192).joinToString(",") { "\"$it\"" }
            writeText("package com.agenthub.core\ninternal val generationPreferenceVectorBase64 = listOf($chunks).joinToString(\"\")\n")
        }
        generatedTests.get().file("ReplyRecoveryTestVectors.kt").asFile.apply {
            parentFile.mkdirs()
            val chunks = JavaBase64.getEncoder().encodeToString(replyVectors.readBytes()).chunked(8192).joinToString(",") { "\"$it\"" }
            writeText("package com.agenthub.core\ninternal val replyRecoveryVectorBase64 = listOf($chunks).joinToString(\"\")\n")
        }
    }
}

kotlin {
    jvm()
    android {
        namespace = "com.agenthub.core"
        compileSdk = 37
        minSdk = 26
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // Keep x86_64 for the existing generic Simulator build; device ARM64 is
    // compiled separately and never claimed as physical-device acceptance.
    listOf(iosArm64(), iosSimulatorArm64(), iosX64(), macosArm64(), macosX64()).forEach {
        it.binaries.framework {
            baseName = "MobileCore"
            isStatic = true
        }
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(generatedContracts)
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }
        commonTest {
            kotlin.srcDir(generatedTests)
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            }
        }
    }
}
tasks.matching { it.name.startsWith("compileTestKotlin") }.configureEach {
    dependsOn(generateTestVectors)
}
tasks.matching { it.name.startsWith("compileKotlin") || it.name.startsWith("compileAndroidMain") }.configureEach {
    dependsOn(generateContracts)
}
