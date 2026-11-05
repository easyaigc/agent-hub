#!/usr/bin/env python3
"""Controlled local Android SDK capture. Only fixed reviewed build profiles are exposed by CLI."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import select
import shutil
import stat
import subprocess
import sys
import time
import uuid

sys.dont_write_bytecode = True
CAP = 2 * 1024 * 1024
APK_CAP = 256 * 1024 * 1024
_LIVE = {}  # Exact Popen owners are retained before any fallible owner/marker/raw operation.
APP = "com.easyai.agenthub.android.acceptance"
PROFILES = {
    "p7-main-saf-framework-v4": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p8-activity-decor-owner-v1": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p8-window-owner-metadata-v1": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p7-main-saf-v2": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p7-main-saf-v1": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p7-main-p8-short-ui-v2": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p7-main-p8-short-ui-v1": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p7-existing-originals": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p7-child-originals": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p7-voice-originals": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p8-main-preference": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p7-foreground": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p7-pristine": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p7": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}},
    "p9-current-main-v2": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest", ":android-inbox-examiner:assembleDebug"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test"),
        "examiner": (".build/native/android-inbox-examiner/outputs/apk/debug/android-inbox-examiner-debug.apk", APP + ".coldexaminer")}},
    "p9": {"tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest", ":android-inbox-examiner:assembleDebug"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test"),
        "examiner": (".build/native/android-inbox-examiner/outputs/apk/debug/android-inbox-examiner-debug.apk", APP + ".coldexaminer")}},
}


# Distinct framework-v4 two-APK contract; all legacy profile constants/guard bodies stay frozen.
P7_FRAMEWORK_PROFILE = "p7-main-saf-framework-v4"
P7_FRAMEWORK_REVIEW_SCHEMA = "p7-main-saf-framework-build-review-v4"
P7_FRAMEWORK_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java', 'apps/android/src/androidTest/AndroidManifest.xml', 'apps/android/src/androidTest/assets/localization-catalog.json', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'apps/android/src/main/res/values-zh/product_strings.xml', 'apps/android/src/main/res/values/product_strings.xml', 'package.json', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt', 'scripts/mobile/android-child-originals-pair.py', 'scripts/mobile/android-existing-originals-pair.py', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-main-saf-framework.py', 'scripts/mobile/android-main-saf.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py')
P7_FRAMEWORK_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': '2d32b5d43b7af4b533dc3dd72131b0ba19dd6f2b59221ac6c44e8c9eecb6de44', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': 'd6691f3e5f018aae62f49f5c8550014e9732aef7f10b93a206bef1dd52d4bc09', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf', 'apps/android/src/androidTest/AndroidManifest.xml': 'aa82a1a072275d72ee7e29c163895cc7b1a9af8852f08d0f5d2e89d87ba3fc6c', 'apps/android/src/androidTest/assets/localization-catalog.json': 'a9a32b36a011a1f924ad79ddfd27ded7ae52c036764de7a3f95102f4022095e1', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': 'a9232e59346ce707fe5aa1c92a7f28a738c2aaa5ffe7d754949f6bce6a7b0523', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '17b81dcdf27a30d823c0cf8c1a146b66c08db416098f2a2c484d7f20cb6d6064', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': '90914b45a0c45f8f6c01622f19cd4f3774860209538fea4a2a80a3c41868bd27', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '6e6c5613ebfce5c79c289aeebe9ebe9d70b750a99a83ea8cb4d61e5b6310ce26', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '870fc7e9ebe3626502d2411bcf7f5d6ffcdd7b8a30e99f4af95555cb0ee3107b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c', 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': 'b571940cbf7d795aef02260719a1df7436aaa1a2ee5d8d2063faa7caca0147fa', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': 'fe19bf80f08c7a1e7ba212cbb28a6ba1cbd6531872d17d9f30a72d6e52640749', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '6fd4fd535933f5c77438f5098517e6fee31d8693d49408d7035acfb4de94d95f', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4', 'apps/android/src/main/res/values-zh/product_strings.xml': '22fdebc5f43aa28b926379e07d4e6ed1117bef640333bf10439202598c287234', 'apps/android/src/main/res/values/product_strings.xml': 'ab3ccb57bb58d4a3e2806884e226ec4cd3df750963a588b35aa8f2e81250a3dc', 'package.json': '5f5a3ce4c9967b458ef8bff8a86ecbf9ca9dce98072edaef9f4af609905281a0', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': 'efa63e223691a3f2f9364b99cbd3b7dcdf9eeb2099d863b156dad4f987cc5671', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '3c5b887f641e5e4b197c69b9f759dc883a5540d62decc3d56a18a64a78d7ef34', 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5', 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c', 'scripts/mobile/android-main-preference.py': 'b29c3af021f0b68d254c0a9dc78c5ddc8570e375c0ab1a9f7ff838d17a1db6e8', 'scripts/mobile/android-main-saf.py': '3fe7515b621e80831a8c7c0218dc6ec44d1d8dca01a996b33473836cff0c90c8', 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775', 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3', 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4', 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a', 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d', 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9', 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java': '33d19d6e03dc825550d25f2880bf8dedd52452c4d7ec9ae96d569e1b3854115d', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java': 'dbcff358d7a1f657c7ddd1fc6f0623ca3e27b8f4ff4cc4360454f039394aa2bc', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java': 'edf03ea366bc0cb897daeb1cdcaba804507653b4dd22009f801dac38c2dd961b', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java': '22bc6d4c5de570102290628497aa9f4e5af1caa1c8af52b08e60f9ee5899acea', 'scripts/mobile/android-main-saf-framework.py': 'f7fa71340879441c9cfa48fc30a2d7bfad61f73ce9eafe6d759a952a1c47c100'}

def p7_framework_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_FRAMEWORK_OVERLAY_PATHS), "Framework-v4 exact59 source union required")
    require(all(overlay[name] == pin for name, pin in P7_FRAMEWORK_FIXED_AFTER.items()), "Framework-v4 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Framework-v4 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Framework-v4 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Framework-v4 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Framework-v4 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Framework-v4 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Framework-v4 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 904 and
        type(result["sources"]) is dict and len(result["sources"]) == 904 and
        set(result["inventory"]) == set(result["sources"]), "Framework-v4 exact904 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result

# Independently reviewed role/window-readiness purpose on pinned integrated main.
# Legacy SAF-v1 and Main-UI profiles retain their original source maps and recipes.
P7_MAIN_SAF_V2_PROFILE = "p7-main-saf-v2"
P7_MAIN_SAF_V2_REVIEW_SCHEMA = "p7-main-saf-build-workspace-review-v2"
P7_MAIN_SAF_V2_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java', 'apps/android/src/androidTest/AndroidManifest.xml', 'apps/android/src/androidTest/assets/localization-catalog.json', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'apps/android/src/main/res/values-zh/product_strings.xml', 'apps/android/src/main/res/values/product_strings.xml', 'package.json', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt', 'scripts/mobile/android-child-originals-pair.py', 'scripts/mobile/android-existing-originals-pair.py', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-main-saf.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py')
P7_MAIN_SAF_V2_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': '2d32b5d43b7af4b533dc3dd72131b0ba19dd6f2b59221ac6c44e8c9eecb6de44', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': 'd6691f3e5f018aae62f49f5c8550014e9732aef7f10b93a206bef1dd52d4bc09', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf', 'apps/android/src/androidTest/AndroidManifest.xml': '4ee11f1fd5fb23bf68ca5d1173a8a2a6960f1127e6ddb1b96de3bd9c03770c60', 'apps/android/src/androidTest/assets/localization-catalog.json': 'a9a32b36a011a1f924ad79ddfd27ded7ae52c036764de7a3f95102f4022095e1', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': 'a9232e59346ce707fe5aa1c92a7f28a738c2aaa5ffe7d754949f6bce6a7b0523', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '17b81dcdf27a30d823c0cf8c1a146b66c08db416098f2a2c484d7f20cb6d6064', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': 'e22cb9c7bb6a5924cdadbb7688858089e6807f4b5abd93ee8b02860783290330', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '6e6c5613ebfce5c79c289aeebe9ebe9d70b750a99a83ea8cb4d61e5b6310ce26', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': 'f10697074c90bbd109dc34a84fcd403c7dfe4127e4dd050ee502c7995b2ad6f8', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c', 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': 'b571940cbf7d795aef02260719a1df7436aaa1a2ee5d8d2063faa7caca0147fa', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': 'fe19bf80f08c7a1e7ba212cbb28a6ba1cbd6531872d17d9f30a72d6e52640749', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '6fd4fd535933f5c77438f5098517e6fee31d8693d49408d7035acfb4de94d95f', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4', 'apps/android/src/main/res/values-zh/product_strings.xml': '22fdebc5f43aa28b926379e07d4e6ed1117bef640333bf10439202598c287234', 'apps/android/src/main/res/values/product_strings.xml': 'ab3ccb57bb58d4a3e2806884e226ec4cd3df750963a588b35aa8f2e81250a3dc', 'package.json': '5f5a3ce4c9967b458ef8bff8a86ecbf9ca9dce98072edaef9f4af609905281a0', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': 'efa63e223691a3f2f9364b99cbd3b7dcdf9eeb2099d863b156dad4f987cc5671', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '3c5b887f641e5e4b197c69b9f759dc883a5540d62decc3d56a18a64a78d7ef34', 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5', 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c', 'scripts/mobile/android-main-preference.py': 'd9c7d95b0c55215c624a68a421d1a9a374b7bb780dd2d6ecd020e9f0b95b8610', 'scripts/mobile/android-main-saf.py': '3fe7515b621e80831a8c7c0218dc6ec44d1d8dca01a996b33473836cff0c90c8', 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775', 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3', 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4', 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a', 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d', 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9', 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187'}


def main_saf_v2_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_MAIN_SAF_V2_OVERLAY_PATHS), "SAF-v2 exact54 source union required")
    require(all(overlay[name] == pin for name, pin in P7_MAIN_SAF_V2_FIXED_AFTER.items()), "SAF-v2 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "SAF-v2 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "SAF-v2 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "SAF-v2 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "SAF-v2 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "SAF-v2 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "SAF-v2 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 899 and
        type(result["sources"]) is dict and len(result["sources"]) == 899 and
        set(result["inventory"]) == set(result["sources"]), "SAF-v2 exact899 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result


# Independent failure-only metadata purpose; sealed SAF-v1/v2 contracts stay frozen.


# Exact retained Activity decor plus exact dialog token association; prior purposes frozen.
P8_ACTIVITY_DECOR_PROFILE = "p8-activity-decor-owner-v1"
P8_ACTIVITY_DECOR_REVIEW_SCHEMA = "p8-activity-decor-owner-build-review-v1"
P8_ACTIVITY_DECOR_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java', 'apps/android/src/androidTest/AndroidManifest.xml', 'apps/android/src/androidTest/assets/localization-catalog.json', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'apps/android/src/main/res/values-zh/product_strings.xml', 'apps/android/src/main/res/values/product_strings.xml', 'package.json', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt', 'scripts/mobile/android-child-originals-pair.py', 'scripts/mobile/android-existing-originals-pair.py', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-main-saf.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py')
P8_ACTIVITY_DECOR_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': '2d32b5d43b7af4b533dc3dd72131b0ba19dd6f2b59221ac6c44e8c9eecb6de44', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': 'd6691f3e5f018aae62f49f5c8550014e9732aef7f10b93a206bef1dd52d4bc09', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf', 'apps/android/src/androidTest/AndroidManifest.xml': '4ee11f1fd5fb23bf68ca5d1173a8a2a6960f1127e6ddb1b96de3bd9c03770c60', 'apps/android/src/androidTest/assets/localization-catalog.json': 'a9a32b36a011a1f924ad79ddfd27ded7ae52c036764de7a3f95102f4022095e1', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': 'a9232e59346ce707fe5aa1c92a7f28a738c2aaa5ffe7d754949f6bce6a7b0523', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '17b81dcdf27a30d823c0cf8c1a146b66c08db416098f2a2c484d7f20cb6d6064', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': 'e22cb9c7bb6a5924cdadbb7688858089e6807f4b5abd93ee8b02860783290330', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '6e6c5613ebfce5c79c289aeebe9ebe9d70b750a99a83ea8cb4d61e5b6310ce26', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '16497966b4b8e79b41dfce72fd3faf74fb44c16a9bad88fa49f1cea4da3f36ec', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c', 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': 'b571940cbf7d795aef02260719a1df7436aaa1a2ee5d8d2063faa7caca0147fa', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': 'fe19bf80f08c7a1e7ba212cbb28a6ba1cbd6531872d17d9f30a72d6e52640749', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '6fd4fd535933f5c77438f5098517e6fee31d8693d49408d7035acfb4de94d95f', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4', 'apps/android/src/main/res/values-zh/product_strings.xml': '22fdebc5f43aa28b926379e07d4e6ed1117bef640333bf10439202598c287234', 'apps/android/src/main/res/values/product_strings.xml': 'ab3ccb57bb58d4a3e2806884e226ec4cd3df750963a588b35aa8f2e81250a3dc', 'package.json': '5f5a3ce4c9967b458ef8bff8a86ecbf9ca9dce98072edaef9f4af609905281a0', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': 'efa63e223691a3f2f9364b99cbd3b7dcdf9eeb2099d863b156dad4f987cc5671', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '3c5b887f641e5e4b197c69b9f759dc883a5540d62decc3d56a18a64a78d7ef34', 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5', 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c', 'scripts/mobile/android-main-preference.py': '8950de7ce415093384de86e7b1cd16a2d5a05d79ff27b89987114ae38d10d73e', 'scripts/mobile/android-main-saf.py': '3fe7515b621e80831a8c7c0218dc6ec44d1d8dca01a996b33473836cff0c90c8', 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775', 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3', 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4', 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a', 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d', 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9', 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187'}

def p8_activity_decor_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P8_ACTIVITY_DECOR_OVERLAY_PATHS), "Activity-decor-owner-v1 exact54 source union required")
    require(all(overlay[name] == pin for name, pin in P8_ACTIVITY_DECOR_FIXED_AFTER.items()), "Activity-decor-owner-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Activity-decor-owner-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Activity-decor-owner-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Activity-decor-owner-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Activity-decor-owner-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Activity-decor-owner-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Activity-decor-owner-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 899 and
        type(result["sources"]) is dict and len(result["sources"]) == 899 and
        set(result["inventory"]) == set(result["sources"]), "Activity-decor-owner-v1 exact899 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result

P8_OWNER_METADATA_PROFILE = "p8-window-owner-metadata-v1"
P8_OWNER_METADATA_REVIEW_SCHEMA = "p8-window-owner-metadata-build-review-v1"
P8_OWNER_METADATA_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java', 'apps/android/src/androidTest/AndroidManifest.xml', 'apps/android/src/androidTest/assets/localization-catalog.json', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'apps/android/src/main/res/values-zh/product_strings.xml', 'apps/android/src/main/res/values/product_strings.xml', 'package.json', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt', 'scripts/mobile/android-child-originals-pair.py', 'scripts/mobile/android-existing-originals-pair.py', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-main-saf.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py')
P8_OWNER_METADATA_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': '2d32b5d43b7af4b533dc3dd72131b0ba19dd6f2b59221ac6c44e8c9eecb6de44', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': 'd6691f3e5f018aae62f49f5c8550014e9732aef7f10b93a206bef1dd52d4bc09', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf', 'apps/android/src/androidTest/AndroidManifest.xml': '4ee11f1fd5fb23bf68ca5d1173a8a2a6960f1127e6ddb1b96de3bd9c03770c60', 'apps/android/src/androidTest/assets/localization-catalog.json': 'a9a32b36a011a1f924ad79ddfd27ded7ae52c036764de7a3f95102f4022095e1', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': 'a9232e59346ce707fe5aa1c92a7f28a738c2aaa5ffe7d754949f6bce6a7b0523', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '17b81dcdf27a30d823c0cf8c1a146b66c08db416098f2a2c484d7f20cb6d6064', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': 'e22cb9c7bb6a5924cdadbb7688858089e6807f4b5abd93ee8b02860783290330', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '6e6c5613ebfce5c79c289aeebe9ebe9d70b750a99a83ea8cb4d61e5b6310ce26', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': 'baaf3bfcb9b9c28624c165b855fc445d74a2976525927460cd566103cf0c7d74', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c', 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': 'b571940cbf7d795aef02260719a1df7436aaa1a2ee5d8d2063faa7caca0147fa', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': 'fe19bf80f08c7a1e7ba212cbb28a6ba1cbd6531872d17d9f30a72d6e52640749', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '6fd4fd535933f5c77438f5098517e6fee31d8693d49408d7035acfb4de94d95f', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4', 'apps/android/src/main/res/values-zh/product_strings.xml': '22fdebc5f43aa28b926379e07d4e6ed1117bef640333bf10439202598c287234', 'apps/android/src/main/res/values/product_strings.xml': 'ab3ccb57bb58d4a3e2806884e226ec4cd3df750963a588b35aa8f2e81250a3dc', 'package.json': '5f5a3ce4c9967b458ef8bff8a86ecbf9ca9dce98072edaef9f4af609905281a0', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': 'efa63e223691a3f2f9364b99cbd3b7dcdf9eeb2099d863b156dad4f987cc5671', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '3c5b887f641e5e4b197c69b9f759dc883a5540d62decc3d56a18a64a78d7ef34', 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5', 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c', 'scripts/mobile/android-main-preference.py': '0d831fe9fc4129456ee704d54e50a72086561842d13c46aa8157f7c4424d3b5f', 'scripts/mobile/android-main-saf.py': '3fe7515b621e80831a8c7c0218dc6ec44d1d8dca01a996b33473836cff0c90c8', 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775', 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3', 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4', 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a', 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d', 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9', 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187'}


def p8_owner_metadata_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P8_OWNER_METADATA_OVERLAY_PATHS), "Owner-metadata-v1 exact54 source union required")
    require(all(overlay[name] == pin for name, pin in P8_OWNER_METADATA_FIXED_AFTER.items()), "Owner-metadata-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Owner-metadata-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Owner-metadata-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Owner-metadata-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Owner-metadata-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Owner-metadata-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Owner-metadata-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 899 and
        type(result["sources"]) is dict and len(result["sources"]) == 899 and
        set(result["inventory"]) == set(result["sources"]), "Owner-metadata-v1 exact899 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result



# Independent finite SAF build purpose; legacy profile constants and recipes stay unchanged.
P7_MAIN_SAF_PROFILE = "p7-main-saf-v1"
P7_MAIN_SAF_REVIEW_SCHEMA = "p7-main-saf-build-workspace-review-v1"
P7_MAIN_SAF_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java', 'apps/android/src/androidTest/AndroidManifest.xml', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'scripts/mobile/android-child-originals-pair.py', 'scripts/mobile/android-existing-originals-pair.py', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-main-saf.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py')
# Self capture is Root-pinned outside this map to avoid a circular body hash.
# Every other path (including the finite Host CLI) has one exact after pin.
P7_MAIN_SAF_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': '2d32b5d43b7af4b533dc3dd72131b0ba19dd6f2b59221ac6c44e8c9eecb6de44', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': 'd6691f3e5f018aae62f49f5c8550014e9732aef7f10b93a206bef1dd52d4bc09', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf', 'apps/android/src/androidTest/AndroidManifest.xml': '4ee11f1fd5fb23bf68ca5d1173a8a2a6960f1127e6ddb1b96de3bd9c03770c60', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': 'c5728bb572875c23debac935cb75fe91f744f9a0a868d5787bae4cea96c77883', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '6e6c5613ebfce5c79c289aeebe9ebe9d70b750a99a83ea8cb4d61e5b6310ce26', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': 'd32dbd8ce82be19e72aec2d911fb71ba73f1e2fe8035f2627eac0938454ac2ca', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c', 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '99a73b93f9a43e9cc95b76da48962237bce28a0e0851a4f64a1923e3bc3859bd', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4', 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5', 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c', 'scripts/mobile/android-main-preference.py': '2ee36fd1d0cc2f23c45882818b8a95f295b1d64046050b1c562e62510681d684', 'scripts/mobile/android-main-saf.py': '41ffb685978399c5b05d07a90810b15f98807b26b16c21fb1aa336828d9acd48', 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775', 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3', 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4', 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a', 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d', 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9', 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187'}


def main_saf_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_MAIN_SAF_OVERLAY_PATHS), "SAF exact44 source union required")
    require(all(overlay[name] == pin for name, pin in P7_MAIN_SAF_FIXED_AFTER.items()), "SAF fixed after pins differ")
    result = api.source_guard(repo, overlay)
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 899 and
        type(result["sources"]) is dict and len(result["sources"]) == 899 and
        set(result["inventory"]) == set(result["sources"]), "SAF exact899 build inputs")
    # Capture-self remains pinned only by the new external immutable Root review.
    return result


# One separately reviewed shared two-APK build purpose. Existing profiles remain closed.
MAIN_UI_SHARED_PROFILE = "p7-main-p8-short-ui-v1"
MAIN_UI_SHARED_REVIEW_SCHEMA = "p7-main-p8-short-ui-build-workspace-review-v1"
MAIN_UI_SHARED_OVERLAY_PATHS = (
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt',
    'apps/android/src/debug/AndroidManifest.xml',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt',
    'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt',
    'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt',
    'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt',
    'scripts/mobile/android-child-originals-pair.py',
    'scripts/mobile/android-existing-originals-pair.py',
    'scripts/mobile/android-main-preference.py',
    'scripts/mobile/android-nonempty-device-first.py',
    'scripts/mobile/android-nonempty-device-pair.py',
    'scripts/mobile/android-nonempty-foreground-pair.py',
    'scripts/mobile/android-nonempty-pristine-first.py',
    'scripts/mobile/android-sdk-capture.py',
    'scripts/mobile/android-voice-originals-controller.py',
    'scripts/mobile/android-voice-originals-pair.py',
    'scripts/mobile/android-voice-originals-workers.py',
)
# Every non-self after is fixed, including the R2 Host and all Stage77/P9 bytes.
# Capture self and the common source tool are also joined by the external immutable Root
# workspace review; no Host -> capture body hash is embedded to create a dependency cycle.
MAIN_UI_SHARED_FIXED_AFTER = {
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': '2d32b5d43b7af4b533dc3dd72131b0ba19dd6f2b59221ac6c44e8c9eecb6de44',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': 'd6691f3e5f018aae62f49f5c8550014e9732aef7f10b93a206bef1dd52d4bc09',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': 'bd700e510b6a1127a2c0c76ab737b2ef575ff1b59bc6bf9d89e70227d6e33797',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': 'e6f3027c180e341277ffb8c7879b7fa7ec504665b0ab9382470d70d1d0a9b078',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c',
    'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2',
    'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14',
    'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '99a73b93f9a43e9cc95b76da48962237bce28a0e0851a4f64a1923e3bc3859bd',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf',
    'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4',
    'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5',
    'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c',
    'scripts/mobile/android-main-preference.py': '1aabc9cf59b75a9e1e16a7b96cd8fd16366d8990a833acf487d3bbf3c4eade98',
    'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775',
    'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3',
    'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4',
    'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a',
    'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d',
    'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9',
    'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187',
}


def _main_ui_shared_overlay(overlay):
    require(type(overlay) is dict and set(overlay) == set(MAIN_UI_SHARED_OVERLAY_PATHS),
        "Shared Main UI requires its exact forty-source reviewed union")
    require(all(overlay[name] == pin for name, pin in MAIN_UI_SHARED_FIXED_AFTER.items()),
        "Shared Main UI fixed Stage77/P7-Main-R2/P8-R2 after pins differ")


def main_ui_shared_source_guard(api, repo, overlay):
    # Named source-only purpose; full baseline inventory/identity checks remain authoritative.
    _main_ui_shared_overlay(overlay)
    # Preserve the generic seven-key guard expected by existing P7 consumers.
    # SDK seal.profile plus the new review schema carry the distinct build purpose.
    return api.source_guard(repo, overlay)


# New source-only exact-anchor readiness purpose. Never repin or mutate v1 recipes/maps.
MAIN_UI_READINESS_PROFILE = "p7-main-p8-short-ui-v2"
MAIN_UI_READINESS_REVIEW_SCHEMA = "p7-main-p8-short-ui-build-workspace-review-v2"
MAIN_UI_READINESS_OVERLAY_PATHS = tuple(MAIN_UI_SHARED_OVERLAY_PATHS)
MAIN_UI_READINESS_FIXED_AFTER = {
    **MAIN_UI_SHARED_FIXED_AFTER,
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '5e2eafafd04149eef3808db7e73e1829acd2dee806423a525abae9dfd571dcea',
    'scripts/mobile/android-main-preference.py': '48b47c75b995f33a0df745fad172c2dbd98d4c794873f7f02ac92620185f806a',
}


def _main_ui_readiness_overlay(overlay):
    require(type(overlay) is dict and len(overlay) == 40 and set(overlay) == set(MAIN_UI_READINESS_OVERLAY_PATHS),
        "Readiness Main UI v2 requires its exact forty-source reviewed union")
    require(all(overlay[name] == pin for name, pin in MAIN_UI_READINESS_FIXED_AFTER.items()),
        "Readiness Main UI v2 fixed Stage77/P7-Main-R2/readiness after pins differ")


def main_ui_readiness_source_guard(api, repo, overlay):
    _main_ui_readiness_overlay(overlay)
    result = api.source_guard(repo, overlay)
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 896 and
        type(result["sources"]) is dict and len(result["sources"]) == 896 and
        set(result["inventory"]) == set(result["sources"]), "Readiness Main UI v2 exact896 build inputs")
    # Self-capture and source-tool body pins remain external immutable Root review facts.
    return result


# New explicitly reviewed 26+10 scope. The unadopted r1 34-source profile is NOT exposed.
P9_CURRENT_MAIN_PROFILE = "p9-current-main-v2"
P9_CURRENT_MAIN_REVIEW_SCHEMA = "p9-current-main-build-workspace-review-v2"
P9_CURRENT_MAIN_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'scripts/mobile/android-child-originals-pair.py', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py')
# All 35 non-self after hashes include exact current P7/helper/production and P9 r2 bytes.
# Self capture after is Root-reviewed externally and joined by the same-FD Host bridge;
# do not embed a recursive self hash or infer approval from dirty paths.
P9_CURRENT_MAIN_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': '2d32b5d43b7af4b533dc3dd72131b0ba19dd6f2b59221ac6c44e8c9eecb6de44', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': 'd6691f3e5f018aae62f49f5c8550014e9732aef7f10b93a206bef1dd52d4bc09', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': 'fa3784bf5a607aca6ca2fe6b18468ff8c71f2b51602d1c4bbe8d2af065e9116c', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '77216320cc8f21047c10d400585fb72f133edb7f538f33bce58266e1a851673d', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'dc49e9a3bb87f0a52bafc63cec10626861cc6e9351466f41114ee947c4630a9f', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '00f88111a2d80df690c301622d2b38db11ee781c3dda283a1b9c2ca46dfba800', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '2de69cd6e511ca25f10b350c35a6b7730e983bad6535b4ccbd95a29b5ec043aa', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c', 'apps/android/src/debug/AndroidManifest.xml': '7ffdd95963f02030f3c5bcd2ab5a3171114b8bea254ce9f90a8fdbb1ff266464', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '2c8ff5a7be9a36381fe39c64be5d0d97d4087cdc158352dd2bf3673857d9dd4d', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': 'c0a168dbdc3a4bc4a5bf07fa4607d0032957a028b4b09253cba94302cd5fe775', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '99a73b93f9a43e9cc95b76da48962237bce28a0e0851a4f64a1923e3bc3859bd', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4', 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5', 'scripts/mobile/android-main-preference.py': '4cb81b7d2e101a08c070c286b1b8e8d17d8ed84c92be81cf39468c9f3742c53b', 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775', 'scripts/mobile/android-nonempty-device-pair.py': 'e977ccfe527048f1b7c72891c3e4f680ca490d5bdd9ecc259c080a8ac28700a3', 'scripts/mobile/android-nonempty-foreground-pair.py': '3a8b35cd2893450071ed2392e688f56c6f457f923939fc61a305f1d4573b81f5', 'scripts/mobile/android-nonempty-pristine-first.py': '5ffe99fcfcfe467d28d97c9bc2752a232903d77dae70c6837bfba0139a4f4be4', 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d', 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9', 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187'}


def _current_main_v2_overlay(overlay):
    require(type(overlay) is dict and set(overlay) == set(P9_CURRENT_MAIN_OVERLAY_PATHS),
        "Current-main v2 requires exact reviewed child26 plus P9 ten-source union")
    require(all(overlay[name] == pin for name, pin in P9_CURRENT_MAIN_FIXED_AFTER.items()),
        "Current-main v2 fixed current production/P7/helper/P9 after pins differ")


# Separate P7 existing-originals purpose: exact current-main36 plus two finite entries.
# Legacy P7/P8/P9 sets and pins are retained; only this profile accepts the layered P7 bytes.
P7_EXISTING_ORIGINALS_PROFILE = "p7-existing-originals"
P7_EXISTING_ORIGINALS_REVIEW_SCHEMA = "p7-existing-originals-build-workspace-review-v1"
P7_EXISTING_ORIGINALS_OVERLAY_PATHS = (
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt',
    'apps/android/src/debug/AndroidManifest.xml',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt',
    'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt',
    'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt',
    'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt',
    'scripts/mobile/android-child-originals-pair.py',
    'scripts/mobile/android-existing-originals-pair.py',
    'scripts/mobile/android-main-preference.py',
    'scripts/mobile/android-nonempty-device-first.py',
    'scripts/mobile/android-nonempty-device-pair.py',
    'scripts/mobile/android-nonempty-foreground-pair.py',
    'scripts/mobile/android-nonempty-pristine-first.py',
    'scripts/mobile/android-sdk-capture.py',
    'scripts/mobile/android-voice-originals-controller.py',
    'scripts/mobile/android-voice-originals-pair.py',
    'scripts/mobile/android-voice-originals-workers.py',
)
# Self capture is pinned externally in the new Root workspace review and same-FD Host chain.
P7_EXISTING_ORIGINALS_FIXED_AFTER = {
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': '2d32b5d43b7af4b533dc3dd72131b0ba19dd6f2b59221ac6c44e8c9eecb6de44',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': 'd6691f3e5f018aae62f49f5c8550014e9732aef7f10b93a206bef1dd52d4bc09',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '2de69cd6e511ca25f10b350c35a6b7730e983bad6535b4ccbd95a29b5ec043aa',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c',
    'apps/android/src/debug/AndroidManifest.xml': '7ffdd95963f02030f3c5bcd2ab5a3171114b8bea254ce9f90a8fdbb1ff266464',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2',
    'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '2c8ff5a7be9a36381fe39c64be5d0d97d4087cdc158352dd2bf3673857d9dd4d',
    'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': 'c0a168dbdc3a4bc4a5bf07fa4607d0032957a028b4b09253cba94302cd5fe775',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '99a73b93f9a43e9cc95b76da48962237bce28a0e0851a4f64a1923e3bc3859bd',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf',
    'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4',
    'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5',
    'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c',
    'scripts/mobile/android-main-preference.py': '4cb81b7d2e101a08c070c286b1b8e8d17d8ed84c92be81cf39468c9f3742c53b',
    'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775',
    'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3',
    'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4',
    'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a',
    'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d',
    'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9',
    'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187',
}


def _existing_originals_overlay(overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_EXISTING_ORIGINALS_OVERLAY_PATHS),
        "Existing originals requires its exact current-main thirty-eight-source union")
    require(all(overlay[name] == pin for name, pin in P7_EXISTING_ORIGINALS_FIXED_AFTER.items()),
        "Existing originals fixed layered P7/current-main after pins differ")


def require(ok, message):
    if not ok:
        raise ValueError(message)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def identity(info):
    return (info.st_dev, info.st_ino, info.st_mode, info.st_uid, info.st_nlink, info.st_size, info.st_mtime_ns, info.st_ctime_ns)


def private_directory(path, empty=False):
    path = Path(path).absolute(); info = path.lstat()
    require(path.resolve() == path and stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700, "Private canonical directory required")
    if empty:
        require(not any(path.iterdir()), "Fresh empty capture directory required")
    return path


def private_bytes(path, maximum, empty=False):
    path = Path(path); private_directory(path.parent); before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_uid == os.getuid() and stat.S_IMODE(before.st_mode) == 0o600 and before.st_nlink == 1 and (0 if empty else 1) <= before.st_size <= maximum, "Private bounded evidence required")
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        require(identity(os.fstat(fd)) == identity(before), "Evidence open changed")
        chunks = []; size = 0
        while True:
            chunk = os.read(fd, min(65536, maximum - size + 1)); size += len(chunk)
            require(size <= maximum, "Evidence byte quota")
            if not chunk:
                break
            chunks.append(chunk)
        require(size == before.st_size and identity(os.fstat(fd)) == identity(before), "Evidence descriptor changed")
    finally:
        os.close(fd)
    require(identity(path.lstat()) == identity(before), "Evidence path changed")
    return b"".join(chunks)


def same(left,right):
    if type(left) is not type(right):return False
    if type(left) is dict:return set(left)==set(right) and all(same(left[key],right[key]) for key in left)
    if type(left) is list:return len(left)==len(right) and all(same(a,b) for a,b in zip(left,right))
    return left==right


def write_bytes(path, raw):
    path = Path(path); private_directory(path.parent)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        at = 0
        while at < len(raw):
            count = os.write(fd, raw[at:]); require(count > 0, "Evidence write stalled"); at += count
        os.fsync(fd)
    finally:
        os.close(fd)


def write_json(path, value):
    raw = encoded(value); require(len(raw) <= 512 * 1024, "Capture JSON quota"); write_bytes(path, raw)


def parse_json(raw):
    def pairs(rows):
        result = {}
        for key, value in rows:
            require(key not in result, "Duplicate capture JSON key"); result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=pairs,
        parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite JSON")))


def load_json(path, maximum=512 * 1024):
    return parse_json(private_bytes(path,maximum))


def private_digest(path, maximum):
    path=Path(path); private_directory(path.parent); before=path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_uid==os.getuid() and stat.S_IMODE(before.st_mode)==0o600 and before.st_nlink==1 and 0<=before.st_size<=maximum,"Private digest input")
    fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK); digest=hashlib.sha256(); total=0
    try:
        require(identity(os.fstat(fd))==identity(before),"Private digest open changed")
        while True:
            chunk=os.read(fd,65536)
            if not chunk:break
            total+=len(chunk);require(total<=maximum,"Private digest quota");digest.update(chunk)
        require(total==before.st_size and identity(os.fstat(fd))==identity(before),"Private digest FD changed")
    finally:os.close(fd)
    require(identity(path.lstat())==identity(before),"Private digest path changed")
    return {"sha256":digest.hexdigest(),"bytes":total}


def owner_start(child):
    # ps is only an independent read helper. A fast already-exited command needs no live recovery stamp.
    if child.poll() is not None:
        return None
    value = subprocess.run(["/bin/ps", "-p", str(child.pid), "-o", "lstart=", "-o", "uid="], stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=2, check=False)
    require(len(value.stdout) + len(value.stderr) <= 1024, "Owner ps quota")
    if child.poll() is not None:
        return None
    require(value.returncode == 0 and not value.stderr and value.stdout.strip(), "Live owner start observation failed")
    return value.stdout.decode("utf-8").strip()


class CaptureFailure(RuntimeError):
    def __init__(self, owner):
        super().__init__("Capture is permanently UNKNOWN; actual worker wait has returned; inspect owner/error evidence")
        self.owner = owner


class Owner:
    def __init__(self, child, output, prefix, intent, raw_fds):
        self.child = child; self.output = output; self.prefix = prefix; self.intent = intent; self.raw_fds = raw_fds
        self.waited = False; self.code = None; self.primary = None; self.errors = []; self.error_count = 0
        self.owner_start = None; self.marker_attempted = False; self.owner_saved = False; self.marker_saved = False
        self.buffers = {name: bytearray() for name in raw_fds}; self.disk_bytes = {name: 0 for name in raw_fds}
        self.raw_ok = {name: True for name in raw_fds}; self.eof = set(); self.nonblocking = set()

    def fail(self, error, phase):
        if self.primary is None:
            self.primary = error
        self.error_count += 1
        note = {"phase": phase, "type": type(error).__name__, "errno": getattr(error, "errno", None)}
        if len(self.errors) < 16:
            self.errors.append(note)
            if error is not self.primary:
                try:
                    self.primary.add_note("P7 capture secondary failure: " + json.dumps(note, sort_keys=True))
                except BaseException:
                    pass
        # Storage failure must be visible even if neither marker nor receipt can be persisted.
        # This never claims that a failed evidence write succeeded.
        if self.error_count <= 16:
            try:
                os.write(2, encoded({"status": "UNKNOWN", "hostOwnerPid": os.getpid(), "childPid": self.child.pid,
                    "operationId": self.intent["operationId"], "failure": note, "ownerSaved": self.owner_saved,
                    "unknownMarkerSaved": self.marker_saved}) + b"\n")
            except BaseException:
                pass  # _LIVE still strongly owns the exact child; no IO is a precondition to wait.

    def announce(self):
        if self.marker_attempted:
            return
        self.marker_attempted = True
        try:
            write_json(self.output / (self.prefix + "-UNKNOWN.json"), {"schema": "android-capture-held-owner-v1",
                "operationId": self.intent["operationId"], "invocationSha256": sha(encoded(self.intent)),
                "hostOwnerPid": os.getpid(), "childPid": self.child.pid, "childStartObservation": self.owner_start,
                "status": "UNKNOWN", "lateResultCannotUpgrade": True, "rawMayBeIncomplete": True})
            self.marker_saved = True
        except BaseException as error:
            self.fail(error, "UNKNOWN-publication")

    def read_ready(self):
        pipes = {name: pipe for name, pipe in (("stdout", self.child.stdout), ("stderr", self.child.stderr)) if name not in self.eof}
        try:
            ready, _, _ = select.select(list(pipes.values()), [], [], 0.1)
        except BaseException as error:
            self.fail(error, "select")
            ready = [pipe for name, pipe in pipes.items() if name in self.nonblocking]
        for name, pipe in pipes.items():
            if pipe not in ready:
                continue
            try:
                chunk = os.read(pipe.fileno(), 65536)
            except BlockingIOError:
                continue
            except BaseException as error:
                self.fail(error, "read-" + name); continue
            if not chunk:
                self.eof.add(name); continue
            remaining = CAP - len(self.buffers[name]); retained = chunk[:max(0, remaining)]
            self.buffers[name].extend(retained)
            if len(retained) != len(chunk):
                if self.raw_ok[name]:
                    self.fail(ValueError("Raw stream cap exceeded"), "cap-" + name)
                self.raw_ok[name] = False
            if self.raw_ok[name]:
                at = 0
                try:
                    while at < len(retained):
                        count = os.write(self.raw_fds[name], retained[at:]); require(count > 0, "Raw write stalled")
                        at += count; self.disk_bytes[name] += count
                except BaseException as error:
                    self.raw_ok[name] = False; self.fail(error, "raw-write-" + name)

    def wait_once(self):
        if self.waited:
            return
        try:
            self.code = self.child.wait(timeout=0.1); self.waited = True
        except subprocess.TimeoutExpired:
            pass
        except BaseException as error:
            self.fail(error, "actual-wait")


def run_process(output, prefix, argv, cwd, env=None, environment=None, timeout=60, child_umask=0o022, tick=None):
    """Internal fixed-call-site primitive. Every role retains its exact child; no instrumentation signal path."""
    output=private_directory(output); require(re.fullmatch(r"[a-z0-9-]{1,64}",prefix),"Capture prefix")
    intent={"schema":"android-capture-invocation-v1","operationId":str(uuid.uuid4()),"prefix":prefix,
        "argv":list(argv),"cwd":str(Path(cwd).resolve()),"environment":environment or {},"childUmask":child_umask,
        "timeoutSeconds":timeout,"hostOwnerPid":os.getpid(),"createdWallNs":time.time_ns()}
    write_json(output/(prefix+"-invocation.json"),intent)
    raw_fds={}; child=None; owner=None; acquisition_error=None; actual_waited=False; actual_code=None
    try:
        for name in ("stdout","stderr"):
            raw_fds[name]=os.open(output/(prefix+"."+name+".bin"),os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
        # All Owner allocation and registry insertion happen BEFORE any actual child exists.
        # The whole acquisition/attach sequence is enclosed by the local-Popen finally below.
        owner=Owner(None,output,prefix,intent,raw_fds)
        _LIVE[intent["operationId"]]=owner
        child=subprocess.Popen(list(argv),cwd=cwd,env=env,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,umask=child_umask)
        owner.child=child
        try:
            owner.owner_start=owner_start(child)
            write_json(output/(prefix+"-owner.json"),{"schema":"android-capture-owner-v1","operationId":intent["operationId"],
                "invocationSha256":sha(encoded(intent)),"hostOwnerPid":os.getpid(),"childPid":child.pid,"childStartObservation":owner.owner_start})
            owner.owner_saved=True
        except BaseException as error:
            owner.fail(error,"owner-publication")
        for name,pipe in (("stdout",child.stdout),("stderr",child.stderr)):
            try:
                os.set_blocking(pipe.fileno(),False); owner.nonblocking.add(name)
            except BaseException as error:
                owner.fail(error,"nonblocking-"+name)
        deadline=time.monotonic()+timeout
        while not owner.waited or len(owner.eof)!=2:
            if owner.primary is None and time.monotonic()>=deadline:
                owner.fail(TimeoutError("Capture deadline reached; retain original child"),"deadline")
            if owner.primary is not None:owner.announce()
            owner.read_ready(); owner.wait_once()
            if owner.primary is None and tick is not None and not owner.waited:
                try:tick()
                except BaseException as error:owner.fail(error,"observation")
            if owner.waited and owner.primary is not None:break
    except BaseException as error:
        acquisition_error=error
        if owner is not None:
            try:owner.fail(error,"acquire-attach-or-loop")
            except BaseException:pass
    finally:
        # The independent local `child` is the fallback owner, even if attach/registry/metadata failed.
        # No fallible marker, selector, read or bookkeeping operation can bypass its actual wait.
        if child is not None:
            try:
                if owner is not None:
                    try:owner.child=child
                    except BaseException as error:
                        acquisition_error=acquisition_error or error
                while not actual_waited:
                    try:
                        if owner is not None and owner.child is child:
                            if owner.primary is not None:owner.announce()
                            owner.read_ready()
                        else:
                            # Attach failed: discard bounded chunks, retaining the real local Popen.
                            for pipe in (child.stdout,child.stderr):
                                try:
                                    os.set_blocking(pipe.fileno(),False); os.read(pipe.fileno(),65536)
                                except BlockingIOError:pass
                    except BaseException as error:
                        if owner is not None:
                            try:owner.fail(error,"retention-read")
                            except BaseException:pass
                    finally:
                        try:
                            actual_code=child.wait(timeout=0.1); actual_waited=True
                        except subprocess.TimeoutExpired:pass
                        except BaseException as error:
                            if owner is not None:
                                try:owner.fail(error,"retention-actual-wait")
                                except BaseException:pass
            finally:
                # An interruption in the fallback itself still cannot return with our child unconfirmed.
                while not actual_waited:
                    try:
                        for pipe in (child.stdout,child.stderr):
                            try:
                                os.set_blocking(pipe.fileno(),False); os.read(pipe.fileno(),65536)
                            except BlockingIOError:pass
                    except BaseException:pass
                    try:
                        actual_code=child.wait(timeout=0.1); actual_waited=True
                    except subprocess.TimeoutExpired:pass
                    except BaseException as error:
                        acquisition_error=acquisition_error or error
            if owner is not None:
                owner.waited=actual_waited; owner.code=actual_code
        for name,fd in raw_fds.items():
            try:os.fsync(fd)
            except BaseException as error:
                acquisition_error=acquisition_error or error
                if owner is not None:
                    owner.raw_ok[name]=False; owner.fail(error,"fsync-"+name)
            try:os.close(fd)
            except BaseException as error:
                acquisition_error=acquisition_error or error
                if owner is not None:
                    owner.raw_ok[name]=False; owner.fail(error,"close-raw-"+name)
        if child is not None:
            for name,pipe in (("stdout",child.stdout),("stderr",child.stderr)):
                try:pipe.close()
                except BaseException as error:
                    acquisition_error=acquisition_error or error
                    if owner is not None:owner.fail(error,"close-pipe-"+name)
        if owner is not None and child is not None:
            if acquisition_error is not None and owner.primary is None:owner.fail(acquisition_error,"acquisition")
            if owner.code!=0 and owner.primary is None:owner.fail(ValueError("Original command returned nonzero"),"return-code")
            if (len(owner.eof)!=2 or not all(owner.raw_ok.values())) and owner.primary is None:
                owner.fail(ValueError("Incomplete original dual streams"),"raw-completion")
            receipt={"schema":"android-capture-wait-v1","operationId":intent["operationId"],"invocationSha256":sha(encoded(intent)),
                "hostOwnerPid":os.getpid(),"childPid":child.pid,"childStartObservation":owner.owner_start,
                "actualWaitReturned":actual_waited,"returnCode":actual_code,"status":"passed" if owner.primary is None else "UNKNOWN",
                "stdoutSha256":sha(bytes(owner.buffers["stdout"])),"stderrSha256":sha(bytes(owner.buffers["stderr"])),
                "stdoutBytes":len(owner.buffers["stdout"]),"stderrBytes":len(owner.buffers["stderr"]),
                "rawComplete":owner.primary is None,"ownerSaved":owner.owner_saved,"unknownMarkerSaved":owner.marker_saved,
                "failureCount":owner.error_count,"failures":owner.errors}
            try:write_json(output/(prefix+"-wait.json"),receipt)
            except BaseException as error:owner.fail(error,"wait-receipt-publication")
        _LIVE.pop(intent["operationId"],None) # No child, or original local actual wait is now confirmed.
    if acquisition_error is not None and owner is None:raise acquisition_error
    if owner is None or child is None:
        raise acquisition_error or RuntimeError("No child was acquired; invocation remains sealed")
    if owner.primary is not None:raise CaptureFailure(owner) from owner.primary
    return {"intent":intent,"receipt":receipt,"stdout":bytes(owner.buffers["stdout"]),"stderr":bytes(owner.buffers["stderr"])}


def verify_process(output, prefix, argv=None, cwd=None, environment=None, child_umask=None, pins=None):
    output=private_directory(output)
    def read(name,maximum=512*1024,empty=False):
        raw=private_bytes(output/name,maximum,empty)
        if pins is not None:require(pins.get(name)=={"sha256":sha(raw),"bytes":len(raw)},"Sealed capture read changed")
        return raw
    intent_raw=read(prefix+"-invocation.json"); receipt_raw=read(prefix+"-wait.json")
    intent=parse_json(intent_raw); receipt=parse_json(receipt_raw)
    require(set(intent) == {"schema", "operationId", "prefix", "argv", "cwd", "environment", "childUmask", "timeoutSeconds", "hostOwnerPid", "createdWallNs"}, "Closed capture invocation")
    require(intent["schema"] == "android-capture-invocation-v1" and intent["prefix"] == prefix and str(uuid.UUID(intent["operationId"])) == intent["operationId"], "Capture invocation identity")
    require(type(intent["hostOwnerPid"]) is int and intent["hostOwnerPid"] > 0 and type(intent["createdWallNs"]) is int and type(intent["childUmask"]) is int and type(intent["timeoutSeconds"]) is int and 1<=intent["timeoutSeconds"]<=3600, "Capture primitive types")
    require(type(intent["argv"]) is list and all(type(atom) is str and "\0" not in atom for atom in intent["argv"]), "Capture argv types")
    if argv is not None:
        require(intent["argv"] == list(argv), "Wrong captured command")
    if cwd is not None:
        require(intent["cwd"] == str(Path(cwd).resolve()), "Wrong captured cwd")
    if environment is not None:
        require(intent["environment"] == environment, "Wrong captured tool environment")
    if child_umask is not None:
        require(intent["childUmask"] == child_umask, "Wrong child umask")
    expected = {"schema", "operationId", "invocationSha256", "hostOwnerPid", "childPid", "childStartObservation", "actualWaitReturned", "returnCode", "status", "stdoutSha256", "stderrSha256", "stdoutBytes", "stderrBytes", "rawComplete", "ownerSaved", "unknownMarkerSaved", "failureCount", "failures"}
    require(set(receipt) == expected and receipt["schema"] == "android-capture-wait-v1" and receipt["operationId"] == intent["operationId"] and receipt["invocationSha256"] == sha(encoded(intent)), "Capture receipt join")
    require(receipt["actualWaitReturned"] is True and receipt["rawComplete"] is True and receipt["ownerSaved"] is True and receipt["unknownMarkerSaved"] is False and type(receipt["returnCode"]) is int and receipt["returnCode"] == 0 and receipt["status"] == "passed" and type(receipt["failureCount"]) is int and receipt["failureCount"] == 0 and receipt["failures"] == [], "Only completed original successful capture accepted")
    require(receipt["childStartObservation"] is None or type(receipt["childStartObservation"]) is str and 1<=len(receipt["childStartObservation"])<=1024,"Owner start type")
    require(type(receipt["childPid"]) is int and receipt["childPid"] > 0 and receipt["hostOwnerPid"] == intent["hostOwnerPid"] and type(receipt["hostOwnerPid"]) is int, "Owner process join")
    owner = parse_json(read(prefix + "-owner.json"))
    require(same(owner, {"schema": "android-capture-owner-v1", "operationId": intent["operationId"], "invocationSha256": receipt["invocationSha256"], "hostOwnerPid": receipt["hostOwnerPid"], "childPid": receipt["childPid"], "childStartObservation": receipt["childStartObservation"]}), "Actual owner join")
    streams = {}
    for name in ("stdout", "stderr"):
        raw = read(prefix + "." + name + ".bin",CAP,empty=True)
        require(type(receipt[name + "Bytes"]) is int and receipt[name + "Bytes"] == len(raw) and receipt[name + "Sha256"] == sha(raw), "Dual raw stream join")
        streams[name] = raw
    require(not (output / (prefix + "-UNKNOWN.json")).exists(), "A sealed UNKNOWN cannot upgrade after late completion")
    return {"intent":intent,"receipt":receipt,"receiptSha256":sha(receipt_raw),"invocationSha256":sha(intent_raw),**streams}


def _tool(api, path, maximum=256 * 1024 * 1024):
    path = Path(path).resolve(); info = path.lstat()
    require(stat.S_ISREG(info.st_mode), "Actual tool file required")
    return {"path": str(path), "sha256": api.digest(path, maximum), "bytes": info.st_size}


def _environment(args):
    safe = {"JAVA_HOME": str(Path(args.java_home).resolve()), "ANDROID_HOME": str(Path(args.android_sdk).resolve()),
        "ANDROID_SDK_ROOT": str(Path(args.android_sdk).resolve()), "GRADLE_USER_HOME": str(Path(args.gradle_home).resolve()),
        "KOTLIN_NATIVE_HOME": "", "PATH": str(Path(args.java_home).resolve() / "bin") + os.pathsep + str(Path(args.node).resolve().parent) + os.pathsep + "/usr/bin:/bin:/usr/sbin:/sbin"}
    env = dict(os.environ)
    for key in list(env):
        if key in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "NODE_OPTIONS", "GRADLE_OPTS", "JAVA_OPTS"} or key.startswith("ORG_GRADLE_PROJECT_"):
            env.pop(key)
    env.update(safe); return env, safe


def _review_legacy(api, path, expected_sha, profile):
    raw = private_bytes(path, 131072); require(sha(raw) == expected_sha, "Root-reviewed-state fingerprint required")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    if profile == P8_ACTIVITY_DECOR_PROFILE:
        require(value["schema"] == P8_ACTIVITY_DECOR_REVIEW_SCHEMA, "Activity decor reviewed-state schema")
    elif profile == P8_OWNER_METADATA_PROFILE:
        require(value["schema"] == P8_OWNER_METADATA_REVIEW_SCHEMA, "Owner metadata reviewed-state schema")
    elif profile == P7_MAIN_SAF_V2_PROFILE:
        require(value["schema"] == P7_MAIN_SAF_V2_REVIEW_SCHEMA, "SAF-v2 reviewed-state schema")
    elif profile == P7_MAIN_SAF_PROFILE:
        require(value["schema"] == P7_MAIN_SAF_REVIEW_SCHEMA, "SAF reviewed-state schema")
    elif profile == MAIN_UI_READINESS_PROFILE:
        require(value["schema"] == MAIN_UI_READINESS_REVIEW_SCHEMA, "Reviewed readiness v2 state schema")
    elif profile == MAIN_UI_SHARED_PROFILE:
        require(value["schema"] == MAIN_UI_SHARED_REVIEW_SCHEMA, "Reviewed state schema")
    else:
        require(value["schema"] == (P7_EXISTING_ORIGINALS_REVIEW_SCHEMA if profile == P7_EXISTING_ORIGINALS_PROFILE else (P9_CURRENT_MAIN_REVIEW_SCHEMA if profile == P9_CURRENT_MAIN_PROFILE else ("p8-main-build-workspace-review-v1" if profile == "p8-main-preference" else "p7-build-workspace-review-v1"))), "Reviewed state schema")
    api.canonical_uuid(value["reviewId"]); require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}",value["head"]),"Reviewed Git HEAD"); api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]; require(type(rows) is dict and len(rows) <= 64, "Reviewed overlay bound")
    if profile == P8_ACTIVITY_DECOR_PROFILE:
        require(set(rows) == set(P8_ACTIVITY_DECOR_OVERLAY_PATHS), "Activity decor exact54 source set")
        require(all(type(rows[name]) is dict and rows[name].get("afterSha256") == pin
            for name, pin in P8_ACTIVITY_DECOR_FIXED_AFTER.items()), "Activity decor exact fixed after pins")
    elif profile == P8_OWNER_METADATA_PROFILE:
        require(set(rows) == set(P8_OWNER_METADATA_OVERLAY_PATHS), "Owner metadata exact54 source set")
        require(all(type(rows[name]) is dict and rows[name].get("afterSha256") == pin
            for name, pin in P8_OWNER_METADATA_FIXED_AFTER.items()), "Owner metadata exact fixed after pins")
    elif profile == P7_MAIN_SAF_V2_PROFILE:
        require(set(rows) == set(P7_MAIN_SAF_V2_OVERLAY_PATHS), "SAF-v2 exact54 source set")
        require(all(type(rows[name]) is dict and rows[name].get("afterSha256") == pin
            for name, pin in P7_MAIN_SAF_V2_FIXED_AFTER.items()), "SAF-v2 exact fixed after pins")
    elif profile == P7_MAIN_SAF_PROFILE:
        require(set(rows) == set(P7_MAIN_SAF_OVERLAY_PATHS), "SAF exact44 source set")
        require(all(type(rows[name]) is dict and rows[name].get("afterSha256") == pin
            for name, pin in P7_MAIN_SAF_FIXED_AFTER.items()), "SAF exact fixed after pins")
    elif profile == MAIN_UI_READINESS_PROFILE:
        require(set(rows) == set(MAIN_UI_READINESS_OVERLAY_PATHS) and len(rows) == 40, "Readiness Main UI v2 exact40 source set")
        require(all(type(rows[name]) is dict and rows[name].get("afterSha256") == pin
            for name, pin in MAIN_UI_READINESS_FIXED_AFTER.items()), "Readiness Main UI v2 exact fixed after pins")
    elif profile == MAIN_UI_SHARED_PROFILE:
        require(set(rows) == set(MAIN_UI_SHARED_OVERLAY_PATHS), "Shared Main UI exact40 source set")
        require(all(type(rows[name]) is dict and rows[name].get("afterSha256") == pin
            for name, pin in MAIN_UI_SHARED_FIXED_AFTER.items()), "Shared Main UI exact fixed after pins")
    elif profile == P9_CURRENT_MAIN_PROFILE:
        require(set(rows) == set(P9_CURRENT_MAIN_OVERLAY_PATHS), "Current-main v2 exact36 source set")
        require(all(type(rows[name]) is dict and rows[name].get("afterSha256") == pin
            for name, pin in P9_CURRENT_MAIN_FIXED_AFTER.items()), "Current-main v2 exact current after pins")
    elif profile == P7_EXISTING_ORIGINALS_PROFILE:
        require(set(rows) == set(P7_EXISTING_ORIGINALS_OVERLAY_PATHS), "Existing originals exact38 source set")
        require(all(type(rows[name]) is dict and rows[name].get("afterSha256") == pin
            for name, pin in P7_EXISTING_ORIGINALS_FIXED_AFTER.items()), "Existing originals exact layered after pins")
    elif profile == "p8-main-preference":
        require(set(rows) == set(api.P8_MAIN_OVERLAY_PATHS), "P8 requires its exact independent Main/admission/tool source set")
        require(all(rows[name].get("afterSha256") == pin for name, pin in api.P8_MAIN_FIXED_SOURCES.items()),
            "P8 production/debug/test/frozen P7 pins are fixed by its source contract")
    elif profile == "p7-child-originals":
        require(set(rows) == set(['apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt', 'scripts/mobile/android-child-originals-pair.py']),
            "Child originals requires the exact reviewed Stage74 union plus its producer profile and finite entry; old profiles remain closed")
    elif profile == "p7-voice-originals":
        require(set(rows) == set(['apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py']),
            "Voice originals requires exactly the reviewed P8/voice twenty-four-source union; old profiles remain closed")
    elif profile == "p7":
        require(set(rows) == set(api.DYNAMIC_SOURCES), "P7 allows only the six reviewed test/tool overlays")
    elif profile == "p7-foreground":
        require(set(rows) == set(api.DYNAMIC_SOURCES) | {"scripts/mobile/android-nonempty-pristine-first.py", "scripts/mobile/android-nonempty-foreground-pair.py"},
            "Foreground P7 requires exactly the existing Controller profile plus its finite paired adapter")
    elif profile == "p7-pristine":
        require(set(rows) == set(api.DYNAMIC_SOURCES) | {"scripts/mobile/android-nonempty-pristine-first.py"},
            "Pristine P7 allows exactly six existing overlays plus its closed first-only adapter")
    else:
        # The exact Root-approved P9 list is data, never inferred from dirty status or a broad glob.
        # Product main/Core/build entries remain immutable; the authorized debug/examiner/tests are finite.
        require(1 <= len(rows) <= 16 and all(name == "apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt" or
            name.startswith("apps/android-inbox-examiner/src/") or name.startswith("apps/android/src/androidTest/") or
            name.startswith("scripts/mobile/") for name in rows), "P9 overlay outside reviewed debug/examiner/test scope")
    overlay = {}
    for name, pins in rows.items():
        require(type(name) is str and type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit before/after overlay pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Overlay before pin must match declared M baseline")
        if pins["beforeSha256"] is not None:
            api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(type(value["allowedDirtyPaths"]) is list and value["allowedDirtyPaths"] == sorted(set(value["allowedDirtyPaths"])) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in value["allowedDirtyPaths"]), "Exact reviewed dirty paths")
    return raw, value, overlay


def _git(output, suffix, repo, env, safe, review):
    commands = {"root": ["git", "rev-parse", "--show-toplevel"], "head": ["git", "rev-parse", "HEAD"],
        "status": ["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"], "unmerged": ["git", "diff", "--name-only", "--diff-filter=U"]}
    results = {name: run_process(output, "git-" + name + "-" + suffix, argv, repo, env, safe) for name, argv in commands.items()}
    require(results["root"]["stdout"].decode().strip() == str(repo) and results["head"]["stdout"].decode().strip() == review["head"] and not results["unmerged"]["stdout"], "Reviewed worktree/HEAD/unmerged state mismatch")
    raw = results["status"]["stdout"]; require(sha(raw) == review["statusSha256"], "Actual Git state is not Root-reviewed state")
    entries = raw.decode("utf-8").split("\0"); require(entries[-1] == "", "Status framing")
    paths = []
    for entry in entries[:-1]:
        require(len(entry) >= 4 and entry[2] == " " and not any(c in entry[:2] for c in "RCU"), "Rename/copy/conflict requires separate review")
        paths.append(entry[3:])
    require(sorted(paths) == review["allowedDirtyPaths"], "Unapproved dirty paths")
    return commands


def _gradle_home(path):
    path = Path(path).resolve(); require(path.is_dir(), "Existing task-owned Gradle cache required")
    require(not (path / "gradle.properties").exists(), "Unreviewed user Gradle properties")
    for name in ("init.gradle","init.gradle.kts"):
        require(not os.path.lexists(path/name),"Unreviewed top-level Gradle init entry")
    init = path / "init.d"; require(not init.exists() or not any(init.iterdir()), "Unreviewed Gradle init scripts")


def _copy_apk(api, source, destination, created_wall_ns):
    before = source.lstat(); require(stat.S_ISREG(before.st_mode) and not source.is_symlink() and 1 <= before.st_size <= APK_CAP and before.st_mtime_ns >= (created_wall_ns // 1_000_000_000) * 1_000_000_000, "SDK did not produce a fresh bounded APK")
    fd = os.open(source, os.O_RDONLY | os.O_NOFOLLOW); out = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        require(identity(os.fstat(fd)) == identity(before), "APK changed before copy")
        total = 0
        while True:
            chunk = os.read(fd, 65536)
            if not chunk:
                break
            total += len(chunk); require(total <= APK_CAP, "APK byte cap")
            at = 0
            while at < len(chunk):
                n = os.write(out, chunk[at:]); require(n > 0, "APK copy stalled"); at += n
        require(total == before.st_size and identity(os.fstat(fd)) == identity(before) and identity(source.lstat()) == identity(before), "APK changed during copy")
        os.fsync(out)
    finally:
        os.close(out); os.close(fd)
    return api.digest(destination, APK_CAP)


def _apk(role, recipe, result_cert, result_manifest, path, api):
    raw = result_cert["stdout"].decode("utf-8") + result_cert["stderr"].decode("utf-8")
    certs = re.findall(r"^(?:Signer #1|V(?:1|2|3(?:\.1)?|4) Signer):? certificate SHA-256 digest: ([a-fA-F0-9]{64})$", raw, re.M)
    require(re.findall(r"^Number of signers: ([0-9]+)$", raw, re.M) == ["1"] and bool(certs) and len({value.lower() for value in certs}) == 1 and not re.search(r"^Signer #(?:[2-9]|[1-9][0-9]+) certificate", raw, re.M), "Exactly one actual signer certificate required")
    badging = result_manifest["stdout"].decode("utf-8"); lines = [line for line in badging.splitlines() if line.startswith("package: ")]
    require(len(lines) == 1, "Actual APK package metadata required")
    fields = re.findall(r"(?:^|\s)(name|versionCode|versionName)='([^']*)'", lines[0]); values = dict(fields)
    require(len(fields) == len(values) and values.get("name") == recipe[1], "Wrong or duplicate built APK package fields")
    version = values.get("versionCode"); require(version in (None, "") or version.isdigit(), "APK version primitive")
    return {"path": str(path), "sourceRelative": recipe[0], "bytes": path.stat().st_size, "sha256": api.digest(path, APK_CAP),
        "package": recipe[1], "certificateSha256": certs[0].lower(), "versionCode": int(version) if version else None,
        "versionName": values.get("versionName") or None}


def _source_guard_legacy(api, repo, overlay, profile):
    if profile == P8_ACTIVITY_DECOR_PROFILE:
        return p8_activity_decor_source_guard(api, repo, overlay)
    if profile == P8_OWNER_METADATA_PROFILE:
        return p8_owner_metadata_source_guard(api, repo, overlay)
    if profile == P7_MAIN_SAF_V2_PROFILE:
        return main_saf_v2_source_guard(api, repo, overlay)
    if profile == P7_MAIN_SAF_PROFILE:
        return main_saf_source_guard(api, repo, overlay)
    if profile == MAIN_UI_READINESS_PROFILE:
        return main_ui_readiness_source_guard(api, repo, overlay)
    if profile == MAIN_UI_SHARED_PROFILE:
        return main_ui_shared_source_guard(api, repo, overlay)
    if profile == P9_CURRENT_MAIN_PROFILE:
        _current_main_v2_overlay(overlay)
        result = api.source_guard(repo, overlay)
        result["reviewedProfile"] = P9_CURRENT_MAIN_PROFILE
        return result
    if profile == P7_EXISTING_ORIGINALS_PROFILE:
        _existing_originals_overlay(overlay)
        # Preserve the generic P7 guard shape required by the existing pristine adapter.
        return api.source_guard(repo, overlay)
    if profile == "p8-main-preference":
        return api.source_guard(repo, overlay, reviewed_profile=profile)
    return api.source_guard(repo, overlay)


def _review(api, path, expected_sha, profile):
    if profile != P7_FRAMEWORK_PROFILE:
        return _review_legacy(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned framework reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    require(value["schema"] == P7_FRAMEWORK_REVIEW_SCHEMA, "Closed framework-v4 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "Framework reviewed HEAD")
    api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_FRAMEWORK_OVERLAY_PATHS), "Framework exact59 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit framework before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Framework before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(all(overlay[name] == pin for name, pin in P7_FRAMEWORK_FIXED_AFTER.items()), "Framework fixed58 after pins")
    # Capture-self is the sole omitted fixed pin and is explicitly in the independently
    # Root-hashed review, then source guard, tools metadata and final sealed SDK chain.
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay


def _source_guard(api, repo, overlay, profile):
    if profile == P7_FRAMEWORK_PROFILE:
        return p7_framework_source_guard(api, repo, overlay)
    return _source_guard_legacy(api, repo, overlay, profile)


def capture_build(args, source_api):
    api = source_api; require(args.profile in PROFILES, "Closed SDK capture profile")
    os.umask(0o077); output = private_directory(args.output_dir, empty=True); repo = Path(args.repo).resolve()
    raw_review, review, overlay = _review(api, args.reviewed_state, args.reviewed_state_sha256, args.profile)
    require(review["head"] == args.expected_head, "Root-reviewed HEAD mismatch")
    _gradle_home(args.gradle_home); env, safe = _environment(args)
    require(shutil.disk_usage(repo).free >= 10 * 1024**3, "Insufficient free space for controlled SDK build")
    write_bytes(output / "workspace-review.json", raw_review)
    before = _source_guard(api, repo, overlay, args.profile); api.write_guard(output / "source-before.json", before)
    git_commands = _git(output, "before", repo, env, safe, review)
    sdk = Path(args.android_sdk).resolve(); java = Path(args.java_home).resolve(); bt = sdk / "build-tools" / args.build_tools
    require(re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9]+)?", args.build_tools), "Explicit build tools version")
    tools = {"node": _tool(api, args.node), "java": _tool(api, java / "bin/java"), "javaModules": _tool(api, java / "lib/modules", 512 * 1024 * 1024),
        "gradleWrapper": _tool(api, repo / "gradlew"), "gradleEntry": _tool(api, repo / "scripts/mobile/gradle.mjs"),
        "androidJar": _tool(api, sdk / "platforms/android-37.0/android.jar"), "androidPlatform": _tool(api, sdk / "platforms/android-37.0/source.properties"),
        "buildToolsSource": _tool(api, bt / "source.properties"), "aapt2": _tool(api, bt / "aapt2"), "adb": _tool(api, sdk / "platform-tools/adb"),
        "apksigner": _tool(api, bt / "apksigner"), "apksignerJar": _tool(api, bt / "lib/apksigner.jar"),
        "captureTool": _tool(api, __file__), "sourceTool": _tool(api, api.__file__)}
    local = repo / ".local/mobile-toolchain.json"; local_pin = api.digest(local, 131072) if local.exists() else None
    metadata = {"schema": "android-sdk-tools-v1", "profile": args.profile, "repo": str(repo), "tools": tools, "environment": safe,
        "localToolchainSha256": local_pin, "buildTools": args.build_tools, "compileSdk": 37}
    write_json(output / "tools-before.json", metadata)
    commands = {"node-version": [tools["node"]["path"], "--version"], "java-version": [tools["java"]["path"], "-version"],
        "gradle-version": [tools["gradleWrapper"]["path"], "--no-daemon", "--version"], "adb-version": [tools["adb"]["path"], "version"]}
    for name, argv in commands.items():
        run_process(output, name, argv, repo, env, safe)
    commands["build"] = [tools["node"]["path"], "scripts/mobile/gradle.mjs", "-PagentHubAcceptance=true", "--no-daemon", "--no-build-cache", "--no-configuration-cache", "--rerun-tasks", "--console=plain"] + PROFILES[args.profile]["tasks"]
    build = run_process(output, "build", commands["build"], repo, env, safe, timeout=3600, child_umask=0o022)
    require(b"BUILD SUCCESSFUL" in build["stdout"] + build["stderr"], "Actual Gradle successful result absent")
    after = _source_guard(api, repo, overlay, args.profile); require(api.same(before, after), "Build source/input inventory changed")
    api.write_guard(output / "source-after.json", after); _git(output, "after", repo, env, safe, review); _gradle_home(args.gradle_home)
    require((api.digest(local, 131072) if local.exists() else None) == local_pin, "Private effective local toolchain config changed")
    expected_local = ("sdk.dir=" + str(sdk).replace("\\", "\\\\").replace(":", "\\:") + "\n").encode()
    require(api.digest(repo / "local.properties", 131072) == sha(expected_local), "Official entry did not select admitted SDK")
    for item in tools.values():
        require(api.digest(item["path"], max(APK_CAP, item["bytes"])) == item["sha256"], "Actual SDK/compiler/capture tool changed")
    write_json(output / "tools-after.json", metadata | {"localPropertiesSha256": sha(expected_local)})
    apks = output / "apks"; apks.mkdir(mode=0o700); artifacts = {}
    for role, recipe in PROFILES[args.profile]["artifacts"].items():
        path = apks / (role + ".apk"); _copy_apk(api, repo / recipe[0], path, build["intent"]["createdWallNs"])
        commands["cert-" + role] = [tools["apksigner"]["path"], "verify", "--verbose", "--print-certs", str(path)]
        commands["manifest-" + role] = [tools["aapt2"]["path"], "dump", "badging", str(path)]
        cert = run_process(output, "cert-" + role, commands["cert-" + role], repo, env, safe)
        manifest = run_process(output, "manifest-" + role, commands["manifest-" + role], repo, env, safe)
        artifacts[role] = _apk(role, recipe, cert, manifest, path, api)
    require(len({item["certificateSha256"] for item in artifacts.values()}) == 1, "App/Test/examiner signer mismatch")
    files = {}
    for path in sorted(output.rglob("*")):
        if path.is_file():
            files[path.relative_to(output).as_posix()] = private_digest(path,APK_CAP)
    seal = {"schema": "android-sdk-capture-seal-v1", "captureId": str(uuid.uuid4()), "profile": args.profile, "repo": str(repo),
        "sourceGuardSha256": sha(encoded(before)), "reviewedStateSha256": args.reviewed_state_sha256,
        "commands": commands, "gitCommands": git_commands, "artifacts": artifacts, "files": files}
    write_json(output / "capture-seal.json", seal)
    result = verify_build_bundle(output, api, repo, args.profile, before)
    print(json.dumps({"schema": seal["schema"], "captureId": seal["captureId"], "sealSha256": result["sealSha256"], "profile": args.profile, "sourceGuardSha256": result["sourceGuardSha256"]}))
    return result


def verify_build_bundle(bundle_dir, source_api, repo, profile, expected_guard=None):
    api=source_api; output=private_directory(bundle_dir); repo=Path(repo).resolve()
    seal_raw=private_bytes(output/"capture-seal.json",512*1024); seal=parse_json(seal_raw)
    def read_sealed(name,maximum=512*1024):
        raw=private_bytes(output/name,maximum)
        require(seal["files"].get(name)=={"sha256":sha(raw),"bytes":len(raw)},"Sealed document changed")
        return parse_json(raw)
    api.exact(seal, {"schema", "captureId", "profile", "repo", "sourceGuardSha256", "reviewedStateSha256", "commands", "gitCommands", "artifacts", "files"})
    require(profile in PROFILES and seal["schema"] == "android-sdk-capture-seal-v1" and seal["profile"] == profile and seal["repo"] == str(repo), "Captured SDK profile/checkout differs")
    api.canonical_uuid(seal["captureId"]); api.sha(seal["sourceGuardSha256"])
    raw_review, review, overlay = _review(api, output / "workspace-review.json", seal["reviewedStateSha256"], profile)
    require(seal["files"].get("workspace-review.json")=={"sha256":sha(raw_review),"bytes":len(raw_review)},"Sealed reviewed-state differs")
    before=read_sealed("source-before.json"); after=read_sealed("source-after.json")
    require(api.same(before, after) and sha(encoded(before)) == seal["sourceGuardSha256"] and api.same(before, _source_guard(api, repo, overlay, profile)), "Actual build source/inventory chain differs")
    if expected_guard is not None:
        require(api.same(before, expected_guard), "Build was not from the admitted runtime sources")
    entries=list(output.rglob("*"))
    require(all(not path.is_symlink() and (path.is_file() or path.relative_to(output).as_posix()=="apks") for path in entries),"Unknown/symlink bundle entry")
    inventory = {path.relative_to(output).as_posix() for path in entries if path.is_file()}
    require(inventory == set(seal["files"]) | {"capture-seal.json"} and len(inventory) <= 256, "Captured bundle has added/missing evidence")
    for name, pin in seal["files"].items():
        require(type(name) is str and not name.startswith("/") and ".." not in name.split("/") and type(pin) is dict and set(pin) == {"sha256", "bytes"}, "Closed capture file pin")
        api.sha(pin["sha256"]); api.integer(pin["bytes"], 0, APK_CAP)
        path = output / name; private_directory(path.parent)
        require(not path.is_symlink() and path.stat().st_size == pin["bytes"], "Capture evidence identity")
        # Empty dual streams are legitimate; digest() intentionally rejects empty ordinary inputs.
        require(private_digest(path,APK_CAP)==pin,"Captured evidence bytes changed")
    tools=read_sealed("tools-before.json"); tool_after=read_sealed("tools-after.json")
    api.exact(tools, {"schema", "profile", "repo", "tools", "environment", "localToolchainSha256", "buildTools", "compileSdk"})
    require(tools["schema"] == "android-sdk-tools-v1" and tools["profile"] == profile and tools["repo"] == str(repo) and type(tools["compileSdk"]) is int and tools["compileSdk"] == 37, "Actual SDK metadata")
    require(api.same(tool_after, tools | {"localPropertiesSha256": tool_after.get("localPropertiesSha256")}), "SDK after metadata changed")
    api.sha(tool_after["localPropertiesSha256"])
    table = tools["tools"]; expected_tools = {"node", "java", "javaModules", "gradleWrapper", "gradleEntry", "androidJar", "androidPlatform", "buildToolsSource", "aapt2", "adb", "apksigner", "apksignerJar", "captureTool", "sourceTool"}
    api.exact(table, expected_tools)
    for name, item in table.items():
        api.exact(item, {"path", "sha256", "bytes"}); api.sha(item["sha256"]); api.integer(item["bytes"], 1, 512 * 1024 * 1024)
        require(Path(item["path"]).is_absolute() and api.digest(item["path"], max(APK_CAP, item["bytes"])) == item["sha256"], "Admitted actual SDK/tool fingerprint changed")
    require(Path(table["captureTool"]["path"]).resolve() == Path(__file__).resolve() and Path(table["sourceTool"]["path"]).resolve() == Path(api.__file__).resolve(), "Different capture/verifier implementation")
    safe = tools["environment"]; require(type(safe) is dict and set(safe) == {"JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "GRADLE_USER_HOME", "KOTLIN_NATIVE_HOME", "PATH"} and all(type(value) is str for value in safe.values()), "Closed effective SDK environment")
    require(safe["ANDROID_HOME"] == safe["ANDROID_SDK_ROOT"] and safe["KOTLIN_NATIVE_HOME"] == "" and Path(table["java"]["path"]).resolve() == (Path(safe["JAVA_HOME"]) / "bin/java").resolve() and Path(table["androidJar"]["path"]).resolve() == (Path(safe["ANDROID_HOME"]) / "platforms/android-37.0/android.jar").resolve(), "SDK/JVM environment and actual tools disagree")
    require(Path(table["gradleWrapper"]["path"]).resolve()==(repo/"gradlew").resolve() and Path(table["gradleEntry"]["path"]).resolve()==(repo/"scripts/mobile/gradle.mjs").resolve(),"Official build entry location")
    expected_local=("sdk.dir="+safe["ANDROID_HOME"].replace("\\","\\\\").replace(":","\\:")+"\n").encode()
    require(tool_after["localPropertiesSha256"]==sha(expected_local),"Effective official SDK config hash")
    fixed = {"node-version": [table["node"]["path"], "--version"], "java-version": [table["java"]["path"], "-version"], "gradle-version": [table["gradleWrapper"]["path"], "--no-daemon", "--version"], "adb-version": [table["adb"]["path"], "version"],
        "build": [table["node"]["path"], "scripts/mobile/gradle.mjs", "-PagentHubAcceptance=true", "--no-daemon", "--no-build-cache", "--no-configuration-cache", "--rerun-tasks", "--console=plain"] + PROFILES[profile]["tasks"]}
    artifacts = {}
    for role, recipe in PROFILES[profile]["artifacts"].items():
        path = output / "apks" / (role + ".apk")
        fixed["cert-" + role] = [table["apksigner"]["path"], "verify", "--verbose", "--print-certs", str(path)]
        fixed["manifest-" + role] = [table["aapt2"]["path"], "dump", "badging", str(path)]
        cert = verify_process(output, "cert-" + role, fixed["cert-" + role], repo, safe, 0o022,pins=seal["files"])
        manifest = verify_process(output, "manifest-" + role, fixed["manifest-" + role], repo, safe, 0o022,pins=seal["files"])
        artifacts[role] = _apk(role, recipe, cert, manifest, path, api)
    require(api.same(artifacts, seal["artifacts"]) and len({item["certificateSha256"] for item in artifacts.values()}) == 1 and api.same(fixed, seal["commands"]), "Captured APK package/certificate/output association differs")
    captures = {name: verify_process(output, name, argv, repo, safe, 0o022,pins=seal["files"]) for name, argv in fixed.items()}
    require(all(value["intent"]["timeoutSeconds"]==(3600 if name=="build" else 60) for name,value in captures.items()),"Unexpected capture deadline")
    require(b"BUILD SUCCESSFUL" in captures["build"]["stdout"] + captures["build"]["stderr"] and captures["node-version"]["stdout"].startswith(b"v") and b"version" in captures["java-version"]["stdout"] + captures["java-version"]["stderr"] and b"Gradle " in captures["gradle-version"]["stdout"] and b"Android Debug Bridge version" in captures["adb-version"]["stdout"], "Actual compiler/SDK command results missing")
    git_fixed = {"root": ["git", "rev-parse", "--show-toplevel"], "head": ["git", "rev-parse", "HEAD"], "status": ["git", "status", "--porcelain=v1", "-z", "--untracked-files=all"], "unmerged": ["git", "diff", "--name-only", "--diff-filter=U"]}
    require(api.same(git_fixed, seal["gitCommands"]), "Unexpected workspace command")
    for suffix in ("before", "after"):
        g = {name: verify_process(output, "git-" + name + "-" + suffix, argv, repo, safe, 0o022,pins=seal["files"]) for name, argv in git_fixed.items()}
        require(g["root"]["stdout"].decode().strip() == str(repo) and g["head"]["stdout"].decode().strip() == review["head"] and sha(g["status"]["stdout"]) == review["statusSha256"] and not g["unmerged"]["stdout"], "Actual reviewed workspace capture chain differs")
    return {"schema": seal["schema"], "captureId": seal["captureId"], "profile": profile, "sourceGuardSha256": seal["sourceGuardSha256"],
        "artifacts": artifacts, "tools": table, "sealSha256": sha(seal_raw)}


# Additive platform-container purpose. The sealed framework-v4 constants, recipes and
# function bodies above remain unchanged. Native protocol/authorities are still v4.
P7_FRAMEWORK_PARENT_PROFILE = "p7-main-saf-framework-parent-v1"
P7_FRAMEWORK_PARENT_REVIEW_SCHEMA = "p7-main-saf-framework-parent-build-review-v1"
P7_FRAMEWORK_PARENT_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java', 'apps/android/src/androidTest/AndroidManifest.xml', 'apps/android/src/androidTest/assets/localization-catalog.json', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'apps/android/src/main/res/values-zh/product_strings.xml', 'apps/android/src/main/res/values/product_strings.xml', 'package.json', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt', 'scripts/mobile/android-child-originals-pair.py', 'scripts/mobile/android-existing-originals-pair.py', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-main-saf-framework.py', 'scripts/mobile/android-main-saf.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py')
P7_FRAMEWORK_PARENT_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': '2d32b5d43b7af4b533dc3dd72131b0ba19dd6f2b59221ac6c44e8c9eecb6de44', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': 'd6691f3e5f018aae62f49f5c8550014e9732aef7f10b93a206bef1dd52d4bc09', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf', 'apps/android/src/androidTest/AndroidManifest.xml': 'aa82a1a072275d72ee7e29c163895cc7b1a9af8852f08d0f5d2e89d87ba3fc6c', 'apps/android/src/androidTest/assets/localization-catalog.json': 'a9a32b36a011a1f924ad79ddfd27ded7ae52c036764de7a3f95102f4022095e1', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': 'a9232e59346ce707fe5aa1c92a7f28a738c2aaa5ffe7d754949f6bce6a7b0523', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '17b81dcdf27a30d823c0cf8c1a146b66c08db416098f2a2c484d7f20cb6d6064', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': '90914b45a0c45f8f6c01622f19cd4f3774860209538fea4a2a80a3c41868bd27', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '6e6c5613ebfce5c79c289aeebe9ebe9d70b750a99a83ea8cb4d61e5b6310ce26', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '870fc7e9ebe3626502d2411bcf7f5d6ffcdd7b8a30e99f4af95555cb0ee3107b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c', 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': 'b571940cbf7d795aef02260719a1df7436aaa1a2ee5d8d2063faa7caca0147fa', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': 'fe19bf80f08c7a1e7ba212cbb28a6ba1cbd6531872d17d9f30a72d6e52640749', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '6fd4fd535933f5c77438f5098517e6fee31d8693d49408d7035acfb4de94d95f', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4', 'apps/android/src/main/res/values-zh/product_strings.xml': '22fdebc5f43aa28b926379e07d4e6ed1117bef640333bf10439202598c287234', 'apps/android/src/main/res/values/product_strings.xml': 'ab3ccb57bb58d4a3e2806884e226ec4cd3df750963a588b35aa8f2e81250a3dc', 'package.json': '5f5a3ce4c9967b458ef8bff8a86ecbf9ca9dce98072edaef9f4af609905281a0', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': 'efa63e223691a3f2f9364b99cbd3b7dcdf9eeb2099d863b156dad4f987cc5671', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '3c5b887f641e5e4b197c69b9f759dc883a5540d62decc3d56a18a64a78d7ef34', 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5', 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c', 'scripts/mobile/android-main-preference.py': '8d802caa35d393009f07e81161bbe985345efd76916f8b7aba973ed1919348fb', 'scripts/mobile/android-main-saf.py': '3fe7515b621e80831a8c7c0218dc6ec44d1d8dca01a996b33473836cff0c90c8', 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775', 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3', 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4', 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a', 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d', 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9', 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java': '33d19d6e03dc825550d25f2880bf8dedd52452c4d7ec9ae96d569e1b3854115d', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java': 'dbcff358d7a1f657c7ddd1fc6f0623ca3e27b8f4ff4cc4360454f039394aa2bc', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java': 'edf03ea366bc0cb897daeb1cdcaba804507653b4dd22009f801dac38c2dd961b', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java': '6a712759fc028c4095c7187198c0ae735b5c78dea48f4b217f86923b465e0a6e', 'scripts/mobile/android-main-saf-framework.py': '6970602fddfe8e9b795cb490b17c4d24cb8422dd03b00fd778a821259be5eafe'}
PROFILES = {**PROFILES, P7_FRAMEWORK_PARENT_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p7_framework_parent_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_FRAMEWORK_PARENT_OVERLAY_PATHS), "Framework-parent-v1 exact59 source union required")
    require(all(overlay[name] == pin for name, pin in P7_FRAMEWORK_PARENT_FIXED_AFTER.items()), "Framework-parent-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Framework-parent-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Framework-parent-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Framework-parent-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Framework-parent-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Framework-parent-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Framework-parent-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 904 and
        type(result["sources"]) is dict and len(result["sources"]) == 904 and
        set(result["inventory"]) == set(result["sources"]), "Framework-parent-v1 exact904 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result

_platform_parent_previous_review = _review
_platform_parent_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_FRAMEWORK_PARENT_PROFILE:
        return _platform_parent_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned framework reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    require(value["schema"] == P7_FRAMEWORK_PARENT_REVIEW_SCHEMA, "Closed framework-parent-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "Framework reviewed HEAD")
    api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_FRAMEWORK_PARENT_OVERLAY_PATHS), "Framework exact59 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit framework before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Framework before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(all(overlay[name] == pin for name, pin in P7_FRAMEWORK_PARENT_FIXED_AFTER.items()), "Framework-parent fixed58 after pins")
    # Capture-self is the sole omitted fixed pin and is explicitly in the independently
    # Root-hashed review, then source guard, tools metadata and final sealed SDK chain.
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay


def _source_guard(api, repo, overlay, profile):
    if profile == P7_FRAMEWORK_PARENT_PROFILE:
        return p7_framework_parent_source_guard(api, repo, overlay)
    return _platform_parent_previous_source_guard(api, repo, overlay, profile)



# Additive standalone-Test-process purpose. Sealed framework-v4 and parent-v1
# constants, recipes and function bodies stay frozen. Native protocol/authorities remain v4.
P7_FRAMEWORK_TEST_PROCESS_PROFILE = "p7-main-saf-framework-test-process-v1"
P7_FRAMEWORK_TEST_PROCESS_REVIEW_SCHEMA = "p7-main-saf-framework-test-process-build-review-v1"
P7_FRAMEWORK_TEST_PROCESS_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java', 'apps/android/src/androidTest/AndroidManifest.xml', 'apps/android/src/androidTest/assets/localization-catalog.json', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'apps/android/src/main/res/values-zh/product_strings.xml', 'apps/android/src/main/res/values/product_strings.xml', 'package.json', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt', 'scripts/mobile/android-child-originals-pair.py', 'scripts/mobile/android-existing-originals-pair.py', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-main-saf-framework.py', 'scripts/mobile/android-main-saf.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py')
P7_FRAMEWORK_TEST_PROCESS_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': '2d32b5d43b7af4b533dc3dd72131b0ba19dd6f2b59221ac6c44e8c9eecb6de44', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': 'd6691f3e5f018aae62f49f5c8550014e9732aef7f10b93a206bef1dd52d4bc09', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf', 'apps/android/src/androidTest/AndroidManifest.xml': 'aa82a1a072275d72ee7e29c163895cc7b1a9af8852f08d0f5d2e89d87ba3fc6c', 'apps/android/src/androidTest/assets/localization-catalog.json': 'a9a32b36a011a1f924ad79ddfd27ded7ae52c036764de7a3f95102f4022095e1', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': 'a9232e59346ce707fe5aa1c92a7f28a738c2aaa5ffe7d754949f6bce6a7b0523', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '17b81dcdf27a30d823c0cf8c1a146b66c08db416098f2a2c484d7f20cb6d6064', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': '90914b45a0c45f8f6c01622f19cd4f3774860209538fea4a2a80a3c41868bd27', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '6e6c5613ebfce5c79c289aeebe9ebe9d70b750a99a83ea8cb4d61e5b6310ce26', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '870fc7e9ebe3626502d2411bcf7f5d6ffcdd7b8a30e99f4af95555cb0ee3107b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c', 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': 'b571940cbf7d795aef02260719a1df7436aaa1a2ee5d8d2063faa7caca0147fa', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': 'fe19bf80f08c7a1e7ba212cbb28a6ba1cbd6531872d17d9f30a72d6e52640749', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '6fd4fd535933f5c77438f5098517e6fee31d8693d49408d7035acfb4de94d95f', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4', 'apps/android/src/main/res/values-zh/product_strings.xml': '22fdebc5f43aa28b926379e07d4e6ed1117bef640333bf10439202598c287234', 'apps/android/src/main/res/values/product_strings.xml': 'ab3ccb57bb58d4a3e2806884e226ec4cd3df750963a588b35aa8f2e81250a3dc', 'package.json': '5f5a3ce4c9967b458ef8bff8a86ecbf9ca9dce98072edaef9f4af609905281a0', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': 'efa63e223691a3f2f9364b99cbd3b7dcdf9eeb2099d863b156dad4f987cc5671', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '3c5b887f641e5e4b197c69b9f759dc883a5540d62decc3d56a18a64a78d7ef34', 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5', 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c', 'scripts/mobile/android-main-preference.py': 'c71bd68f7c19837664aee91b61138763d354cfabdd2e842fbea4b5ee9dfee189', 'scripts/mobile/android-main-saf.py': '3fe7515b621e80831a8c7c0218dc6ec44d1d8dca01a996b33473836cff0c90c8', 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775', 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3', 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4', 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a', 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d', 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9', 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java': '33d19d6e03dc825550d25f2880bf8dedd52452c4d7ec9ae96d569e1b3854115d', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java': 'dbcff358d7a1f657c7ddd1fc6f0623ca3e27b8f4ff4cc4360454f039394aa2bc', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java': 'edf03ea366bc0cb897daeb1cdcaba804507653b4dd22009f801dac38c2dd961b', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java': '957228f8d741d68a9e325e546a45f6d7227ed110088686c818c2bea3501fcf94', 'scripts/mobile/android-main-saf-framework.py': '58c2242a17e6d34bad88d828dd250a2d9686fcbc35e55ceff970ab1fd8dea747'}
PROFILES = {**PROFILES, P7_FRAMEWORK_TEST_PROCESS_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p7_framework_test_process_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_FRAMEWORK_TEST_PROCESS_OVERLAY_PATHS), "Framework-test-process-v1 exact59 source union required")
    require(all(overlay[name] == pin for name, pin in P7_FRAMEWORK_TEST_PROCESS_FIXED_AFTER.items()), "Framework-test-process-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Framework-test-process-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Framework-test-process-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Framework-test-process-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Framework-test-process-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Framework-test-process-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Framework-test-process-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 904 and
        type(result["sources"]) is dict and len(result["sources"]) == 904 and
        set(result["inventory"]) == set(result["sources"]), "Framework-test-process-v1 exact904 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result

_test_process_previous_review = _review
_test_process_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_FRAMEWORK_TEST_PROCESS_PROFILE:
        return _test_process_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned framework reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    require(value["schema"] == P7_FRAMEWORK_TEST_PROCESS_REVIEW_SCHEMA, "Closed framework-parent-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "Framework reviewed HEAD")
    api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_FRAMEWORK_TEST_PROCESS_OVERLAY_PATHS), "Framework exact59 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit framework before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Framework before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(all(overlay[name] == pin for name, pin in P7_FRAMEWORK_TEST_PROCESS_FIXED_AFTER.items()), "Framework-test-process fixed58 after pins")
    # Capture-self is the sole omitted fixed pin and is explicitly in the independently
    # Root-hashed review, then source guard, tools metadata and final sealed SDK chain.
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay


def _source_guard(api, repo, overlay, profile):
    if profile == P7_FRAMEWORK_TEST_PROCESS_PROFILE:
        return p7_framework_test_process_source_guard(api, repo, overlay)
    return _test_process_previous_source_guard(api, repo, overlay, profile)


def main():
    parser = argparse.ArgumentParser(description=__doc__); sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("capture-build")
    for name in ("repo", "output-dir", "node", "java-home", "android-sdk", "build-tools", "gradle-home", "reviewed-state", "reviewed-state-sha256", "expected-head"):
        p.add_argument("--" + name, required=True)
    p.add_argument("--profile", required=True, choices=sorted(PROFILES)); args = parser.parse_args()
    location = Path(__file__).with_name("android-nonempty-device-first.py")
    spec = importlib.util.spec_from_file_location("capture_sources", location); api = importlib.util.module_from_spec(spec); spec.loader.exec_module(api)
    capture_build(args, api)



# Separate Stage83 two-APK 60/905 observer/import contract; every prior body stays frozen.
P7_FRAMEWORK_OBSERVERS_IMPORT_PROFILE = "p7-main-saf-framework-observers-import-v1"
P7_FRAMEWORK_OBSERVERS_IMPORT_REVIEW_SCHEMA = "p7-main-saf-framework-observers-import-build-review-v1"
P7_FRAMEWORK_OBSERVERS_IMPORT_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java', 'apps/android/src/androidTest/AndroidManifest.xml', 'apps/android/src/androidTest/assets/localization-catalog.json', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'apps/android/src/main/res/values-zh/product_strings.xml', 'apps/android/src/main/res/values/product_strings.xml', 'package.json', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt', 'scripts/mobile/android-child-originals-pair.py', 'scripts/mobile/android-existing-originals-pair.py', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-main-saf-framework-import.py', 'scripts/mobile/android-main-saf-framework.py', 'scripts/mobile/android-main-saf.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py')
P7_FRAMEWORK_OBSERVERS_IMPORT_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': 'f013815ec371057ae1039cfa0ed6c5749dcb8a3d70f9b5455933935795e293da', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': '8f765491a4c08c9fabc1da4db44af98ae4d8065830864e9996d880294f3ca33d', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf', 'apps/android/src/androidTest/AndroidManifest.xml': 'aa82a1a072275d72ee7e29c163895cc7b1a9af8852f08d0f5d2e89d87ba3fc6c', 'apps/android/src/androidTest/assets/localization-catalog.json': 'a9a32b36a011a1f924ad79ddfd27ded7ae52c036764de7a3f95102f4022095e1', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java': '3051f470d1607e5aad33a7a3b02e39452984a740b8dba26b9cb2a632210724ce', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java': '44237ca5bf4a551f21b788fa279106e7befd5c909adc4e189ba3de4ed9875986', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java': '8f42b3525f9380999d41a740d34b0ceda916bf0d70e80f4dcdfc833fbc3680f2', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java': '2516fde3aa83fd6325096e3820ea37c94c8c3418e158bc718006f8d6acd2d050', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': 'a9232e59346ce707fe5aa1c92a7f28a738c2aaa5ffe7d754949f6bce6a7b0523', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '17b81dcdf27a30d823c0cf8c1a146b66c08db416098f2a2c484d7f20cb6d6064', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': '90914b45a0c45f8f6c01622f19cd4f3774860209538fea4a2a80a3c41868bd27', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '6e6c5613ebfce5c79c289aeebe9ebe9d70b750a99a83ea8cb4d61e5b6310ce26', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': 'ad2b0d520a89e15961f79b2d0f9fc0af215716671c16bf0ad5f784d0d20e58de', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c', 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': 'b571940cbf7d795aef02260719a1df7436aaa1a2ee5d8d2063faa7caca0147fa', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': 'fe19bf80f08c7a1e7ba212cbb28a6ba1cbd6531872d17d9f30a72d6e52640749', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '6fd4fd535933f5c77438f5098517e6fee31d8693d49408d7035acfb4de94d95f', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4', 'apps/android/src/main/res/values-zh/product_strings.xml': '22fdebc5f43aa28b926379e07d4e6ed1117bef640333bf10439202598c287234', 'apps/android/src/main/res/values/product_strings.xml': 'ab3ccb57bb58d4a3e2806884e226ec4cd3df750963a588b35aa8f2e81250a3dc', 'package.json': '5f5a3ce4c9967b458ef8bff8a86ecbf9ca9dce98072edaef9f4af609905281a0', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': 'efa63e223691a3f2f9364b99cbd3b7dcdf9eeb2099d863b156dad4f987cc5671', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '3c5b887f641e5e4b197c69b9f759dc883a5540d62decc3d56a18a64a78d7ef34', 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5', 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c', 'scripts/mobile/android-main-preference.py': 'c1679628e9bb376eea3f05b44fcf6ce5fb7bfd8fe612802c99d521356f417d93', 'scripts/mobile/android-main-saf-framework-import.py': '03f7468b28f2896ae8957eeefa7a100755396f51a9d956132d93e7c6a4285b62', 'scripts/mobile/android-main-saf-framework.py': 'f30cc2a072996ff7d598608c0ecfd7d6d20391a9a1d6fce18d3af5fd01a63230', 'scripts/mobile/android-main-saf.py': '3fe7515b621e80831a8c7c0218dc6ec44d1d8dca01a996b33473836cff0c90c8', 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775', 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3', 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4', 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a', 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d', 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9', 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187'}
PROFILES = {**PROFILES, P7_FRAMEWORK_OBSERVERS_IMPORT_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p7_framework_observers_import_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_FRAMEWORK_OBSERVERS_IMPORT_OVERLAY_PATHS), "Framework-observers-import-v1 exact60 source union required")
    require(all(overlay[name] == pin for name, pin in P7_FRAMEWORK_OBSERVERS_IMPORT_FIXED_AFTER.items()), "Framework-observers-import-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Framework-observers-import-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Framework-observers-import-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Framework-observers-import-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Framework-observers-import-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Framework-observers-import-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Framework-observers-import-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 905 and
        type(result["sources"]) is dict and len(result["sources"]) == 905 and
        set(result["inventory"]) == set(result["sources"]), "Framework-observers-import-v1 exact905 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result

_observers_import_previous_review = _review
_observers_import_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_FRAMEWORK_OBSERVERS_IMPORT_PROFILE:
        return _observers_import_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned framework reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    require(value["schema"] == P7_FRAMEWORK_OBSERVERS_IMPORT_REVIEW_SCHEMA, "Closed framework-observers-import-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "Framework reviewed HEAD")
    api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_FRAMEWORK_OBSERVERS_IMPORT_OVERLAY_PATHS), "Framework exact60 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit framework before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Framework before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(all(overlay[name] == pin for name, pin in P7_FRAMEWORK_OBSERVERS_IMPORT_FIXED_AFTER.items()), "Framework-observers-import fixed59 after pins")
    # Capture-self is the sole omitted fixed pin and is explicitly in the independently
    # Root-hashed review, then source guard, tools metadata and final sealed SDK chain.
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay


def _source_guard(api, repo, overlay, profile):
    if profile == P7_FRAMEWORK_OBSERVERS_IMPORT_PROFILE:
        return p7_framework_observers_import_source_guard(api, repo, overlay)
    return _observers_import_previous_source_guard(api, repo, overlay, profile)

# Separate P9 v2 three-APK 60/905 diagnostic contract; no runtime admission is implied.
P9_GEOMETRY_V2_PROFILE = 'p9-systemui-geometry-diagnostic-v2'
P9_GEOMETRY_V2_REVIEW_SCHEMA = "p9-systemui-geometry-build-workspace-review-v2"
P9_GEOMETRY_V2_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java', 'apps/android/src/androidTest/AndroidManifest.xml', 'apps/android/src/androidTest/assets/localization-catalog.json', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt', 'apps/android/src/debug/AndroidManifest.xml', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt', 'apps/android/src/main/res/values-zh/product_strings.xml', 'apps/android/src/main/res/values/product_strings.xml', 'package.json', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt', 'scripts/mobile/android-child-originals-pair.py', 'scripts/mobile/android-existing-originals-pair.py', 'scripts/mobile/android-main-preference.py', 'scripts/mobile/android-main-saf-framework-import.py', 'scripts/mobile/android-main-saf-framework.py', 'scripts/mobile/android-main-saf.py', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-nonempty-foreground-pair.py', 'scripts/mobile/android-nonempty-pristine-first.py', 'scripts/mobile/android-sdk-capture.py', 'scripts/mobile/android-voice-originals-controller.py', 'scripts/mobile/android-voice-originals-pair.py', 'scripts/mobile/android-voice-originals-workers.py')
P9_GEOMETRY_V2_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': 'f013815ec371057ae1039cfa0ed6c5749dcb8a3d70f9b5455933935795e293da', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': '8f765491a4c08c9fabc1da4db44af98ae4d8065830864e9996d880294f3ca33d', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf', 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf', 'apps/android/src/androidTest/AndroidManifest.xml': 'aa82a1a072275d72ee7e29c163895cc7b1a9af8852f08d0f5d2e89d87ba3fc6c', 'apps/android/src/androidTest/assets/localization-catalog.json': 'a9a32b36a011a1f924ad79ddfd27ded7ae52c036764de7a3f95102f4022095e1', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java': '3051f470d1607e5aad33a7a3b02e39452984a740b8dba26b9cb2a632210724ce', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d', 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java': '44237ca5bf4a551f21b788fa279106e7befd5c909adc4e189ba3de4ed9875986', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java': '8f42b3525f9380999d41a740d34b0ceda916bf0d70e80f4dcdfc833fbc3680f2', 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java': '2516fde3aa83fd6325096e3820ea37c94c8c3418e158bc718006f8d6acd2d050', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': 'a9232e59346ce707fe5aa1c92a7f28a738c2aaa5ffe7d754949f6bce6a7b0523', 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '17b81dcdf27a30d823c0cf8c1a146b66c08db416098f2a2c484d7f20cb6d6064', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': '90914b45a0c45f8f6c01622f19cd4f3774860209538fea4a2a80a3c41868bd27', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '6e6c5613ebfce5c79c289aeebe9ebe9d70b750a99a83ea8cb4d61e5b6310ce26', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': 'ad2b0d520a89e15961f79b2d0f9fc0af215716671c16bf0ad5f784d0d20e58de', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a', 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c', 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce', 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2', 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14', 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': 'b571940cbf7d795aef02260719a1df7436aaa1a2ee5d8d2063faa7caca0147fa', 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': 'fe19bf80f08c7a1e7ba212cbb28a6ba1cbd6531872d17d9f30a72d6e52640749', 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d', 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '6fd4fd535933f5c77438f5098517e6fee31d8693d49408d7035acfb4de94d95f', 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf', 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4', 'apps/android/src/main/res/values-zh/product_strings.xml': '22fdebc5f43aa28b926379e07d4e6ed1117bef640333bf10439202598c287234', 'apps/android/src/main/res/values/product_strings.xml': 'ab3ccb57bb58d4a3e2806884e226ec4cd3df750963a588b35aa8f2e81250a3dc', 'package.json': '5f5a3ce4c9967b458ef8bff8a86ecbf9ca9dce98072edaef9f4af609905281a0', 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': 'efa63e223691a3f2f9364b99cbd3b7dcdf9eeb2099d863b156dad4f987cc5671', 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '3c5b887f641e5e4b197c69b9f759dc883a5540d62decc3d56a18a64a78d7ef34', 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5', 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c', 'scripts/mobile/android-main-preference.py': 'c1679628e9bb376eea3f05b44fcf6ce5fb7bfd8fe612802c99d521356f417d93', 'scripts/mobile/android-main-saf-framework-import.py': '03f7468b28f2896ae8957eeefa7a100755396f51a9d956132d93e7c6a4285b62', 'scripts/mobile/android-main-saf-framework.py': 'f30cc2a072996ff7d598608c0ecfd7d6d20391a9a1d6fce18d3af5fd01a63230', 'scripts/mobile/android-main-saf.py': '3fe7515b621e80831a8c7c0218dc6ec44d1d8dca01a996b33473836cff0c90c8', 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775', 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3', 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4', 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a', 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d', 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9', 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187'}
PROFILES = {**PROFILES, P9_GEOMETRY_V2_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest", ":android-inbox-examiner:assembleDebug"],
    "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test"),
        "examiner": (".build/native/android-inbox-examiner/outputs/apk/debug/android-inbox-examiner-debug.apk", APP + ".coldexaminer")}}}


def p9_geometry_v2_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P9_GEOMETRY_V2_OVERLAY_PATHS), "P9-geometry-v2 exact60 source union required")
    require(all(overlay[name] == pin for name, pin in P9_GEOMETRY_V2_FIXED_AFTER.items()), "P9-geometry-v2 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "P9-geometry-v2 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "P9-geometry-v2 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "P9-geometry-v2 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "P9-geometry-v2 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "P9-geometry-v2 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "P9-geometry-v2 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 905 and
        type(result["sources"]) is dict and len(result["sources"]) == 905 and
        set(result["inventory"]) == set(result["sources"]), "P9-geometry-v2 exact905 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    result["reviewedProfile"] = P9_GEOMETRY_V2_PROFILE
    return result


def _p9_geometry_v2_review(api, path, expected_sha, profile):
    require(profile == P9_GEOMETRY_V2_PROFILE, "Closed P9 geometry v2 reviewed purpose")
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned framework reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    require(value["schema"] == P9_GEOMETRY_V2_REVIEW_SCHEMA, "Closed p9-geometry-v2 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "Framework reviewed HEAD")
    api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P9_GEOMETRY_V2_OVERLAY_PATHS), "P9-geometry-v2 exact60 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit framework before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Framework before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(all(overlay[name] == pin for name, pin in P9_GEOMETRY_V2_FIXED_AFTER.items()), "P9-geometry-v2 fixed59 after pins")
    # Capture-self is the sole omitted fixed pin and is explicitly in the independently
    # Root-hashed review, then source guard, tools metadata and final sealed SDK chain.
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay


_p9_geometry_v2_previous_review = _review
_p9_geometry_v2_previous_source_guard = _source_guard


def _review(api, path, expected_sha, profile):
    if profile == P9_GEOMETRY_V2_PROFILE:
        return _p9_geometry_v2_review(api, path, expected_sha, profile)
    return _p9_geometry_v2_previous_review(api, path, expected_sha, profile)


def _source_guard(api, repo, overlay, profile):
    if profile == P9_GEOMETRY_V2_PROFILE:
        return p9_geometry_v2_source_guard(api, repo, overlay)
    return _p9_geometry_v2_previous_source_guard(api, repo, overlay, profile)

# Stage84 is a new independently reviewed two-APK purpose. Source83 contracts above
# remain byte-for-byte intact; capture-self is pinned only by external Root review.
P7_PLATFORM_ALIAS_AX_PROFILE = "p7-main-saf-platform-alias-ax-v1"
P7_PLATFORM_ALIAS_AX_REVIEW_SCHEMA = "p7-main-saf-platform-alias-ax-build-review-v1"
P7_PLATFORM_ALIAS_AX_OVERLAY_PATHS = P7_FRAMEWORK_OBSERVERS_IMPORT_OVERLAY_PATHS
P7_PLATFORM_ALIAS_AX_FIXED_AFTER = {**P7_FRAMEWORK_OBSERVERS_IMPORT_FIXED_AFTER,
    'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java': 'f86aefe644758283ac18e53ac6a2c65aefbf0d7e71e58dd9db8aaef2ae4ee1f2',
    'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java': '43cf0d1c773048621f610b8af098e9affef55ad6d68496c6af369dfe93447bb1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '78602f95107605d67a3c4aa7613e2f4278625700ba8ede891e914d8b0ed539ea',
    'scripts/mobile/android-main-saf-framework.py': '7f997ff48dee7fcb7007b93e17a9e8ad1735797bc3c262fea8f2b68ba97d67b6',
    'scripts/mobile/android-main-saf-framework-import.py': '0d9ae9089fe8d3b099c0765234a0ed74948f811aa0110a1f4a1d3023447136a0',
    'scripts/mobile/android-main-preference.py': '3623b295a629bac3cb15e4a20a8bff58675063365445905ad9cdc870ace808c3',
}
PROFILES = {**PROFILES, P7_PLATFORM_ALIAS_AX_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p7_platform_alias_ax_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_PLATFORM_ALIAS_AX_OVERLAY_PATHS), "Platform-alias-AX-v1 exact60 source union required")
    require(all(overlay[name] == pin for name, pin in P7_PLATFORM_ALIAS_AX_FIXED_AFTER.items()), "Platform-alias-AX-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Platform-alias-AX-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Platform-alias-AX-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Platform-alias-AX-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Platform-alias-AX-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Platform-alias-AX-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Platform-alias-AX-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 905 and
        type(result["sources"]) is dict and len(result["sources"]) == 905 and
        set(result["inventory"]) == set(result["sources"]), "Platform-alias-AX-v1 exact905 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result

_platform_alias_ax_previous_review = _review
_platform_alias_ax_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_PLATFORM_ALIAS_AX_PROFILE:
        return _platform_alias_ax_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned framework reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    require(value["schema"] == P7_PLATFORM_ALIAS_AX_REVIEW_SCHEMA, "Closed platform-alias-AX-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "Framework reviewed HEAD")
    api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_PLATFORM_ALIAS_AX_OVERLAY_PATHS), "Framework exact60 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit framework before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Framework before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(all(overlay[name] == pin for name, pin in P7_PLATFORM_ALIAS_AX_FIXED_AFTER.items()), "Platform-alias-AX fixed59 after pins")
    # Capture-self is the sole omitted fixed pin and is explicitly in the independently
    # Root-hashed review, then source guard, tools metadata and final sealed SDK chain.
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay

def _source_guard(api, repo, overlay, profile):
    if profile == P7_PLATFORM_ALIAS_AX_PROFILE:
        return p7_platform_alias_ax_source_guard(api, repo, overlay)
    return _platform_alias_ax_previous_source_guard(api, repo, overlay, profile)


# Stage85 publication-lock is an additive two-APK purpose. All earlier profile
# recipes/fixed pins/guard bodies remain frozen; capture-self stays externally Root-pinned.
P7_PUBLICATION_LOCK_PROFILE = "p7-main-saf-publication-lock-v1"
P7_PUBLICATION_LOCK_REVIEW_SCHEMA = "p7-main-saf-publication-lock-build-review-v1"
P7_PUBLICATION_LOCK_OVERLAY_PATHS = P7_FRAMEWORK_OBSERVERS_IMPORT_OVERLAY_PATHS
P7_PUBLICATION_LOCK_FIXED_AFTER = {**P7_PLATFORM_ALIAS_AX_FIXED_AFTER,
    'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java': 'e3c794fc7ce53313e8006dbe4784a2c80586d2c0c5080118885f1464f02cfa73',
    'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java': 'f3f4993066c9d9d393621bc101597c6f666196517863f68a5053e70e053a02c5',
    'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java': '1338f8742f3c5a29f5f5cb274b54e6b7e51c36c0800ed8ba7445764d691825cd',
    'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java': '8ec239f0574225e12ce1c4b6e72b02757e5ac29b437c41113ae368964e7a5671',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': '5470168fd63d8316e4baccd6fcbede32c84bbc2d63ca1dfb8dd4f13643a20cb9',
    'scripts/mobile/android-main-saf-framework.py': '6e14796dc56177bdd98c5048300197515e1f268fbf6b8f3260a07e64bb5daa04',
    'scripts/mobile/android-main-saf-framework-import.py': 'e20849c633182c8f3c711c90b8f40b7528de8630414a4144e0787ed332ee6b29',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '3628870c4806ce67c5af9dc44485affa89f7ddfe278df3a0e8397bd542a45722',
    'scripts/mobile/android-main-preference.py': '443a2b664db8310818dbede573f5b4d1bf42101785828a82d65a308a69d2d624',
}
PROFILES = {**PROFILES, P7_PUBLICATION_LOCK_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p7_publication_lock_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_PUBLICATION_LOCK_OVERLAY_PATHS), "Publication-lock-v1 exact60 source union required")
    require(all(overlay[name] == pin for name, pin in P7_PUBLICATION_LOCK_FIXED_AFTER.items()), "Publication-lock-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Publication-lock-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Publication-lock-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Publication-lock-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Publication-lock-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Publication-lock-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Publication-lock-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 905 and
        type(result["sources"]) is dict and len(result["sources"]) == 905 and
        set(result["inventory"]) == set(result["sources"]), "Publication-lock-v1 exact905 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result

_publication_lock_previous_review = _review
_publication_lock_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_PUBLICATION_LOCK_PROFILE:
        return _publication_lock_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned framework reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    require(value["schema"] == P7_PUBLICATION_LOCK_REVIEW_SCHEMA, "Closed publication-lock-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "Framework reviewed HEAD")
    api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_PUBLICATION_LOCK_OVERLAY_PATHS), "Framework exact60 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit framework before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Framework before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(all(overlay[name] == pin for name, pin in P7_PUBLICATION_LOCK_FIXED_AFTER.items()), "Publication-lock fixed59 after pins")
    # Capture-self is the sole omitted fixed pin and is explicitly in the independently
    # Root-hashed review, then source guard, tools metadata and final sealed SDK chain.
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay

def _source_guard(api, repo, overlay, profile):
    if profile == P7_PUBLICATION_LOCK_PROFILE:
        return p7_publication_lock_source_guard(api, repo, overlay)
    return _publication_lock_previous_source_guard(api, repo, overlay, profile)


# Stage86 filename-phase is an additive two-APK purpose. All earlier profile
# recipes/fixed pins/guard bodies remain frozen; capture-self stays externally Root-pinned.
P7_FILENAME_PHASE_PROFILE = "p7-main-saf-filename-phase-v1"
P7_FILENAME_PHASE_REVIEW_SCHEMA = "p7-main-saf-filename-phase-build-review-v1"
P7_FILENAME_PHASE_OVERLAY_PATHS = P7_PUBLICATION_LOCK_OVERLAY_PATHS
P7_FILENAME_PHASE_FIXED_AFTER = {**P7_PUBLICATION_LOCK_FIXED_AFTER,
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': '3324d605369ad429d436bf91e18c798502eb099867ad904045f7d83fe2475121',
    'scripts/mobile/android-main-saf-framework.py': '5c03a05ff048ff3c5d5e97fdd926af1074acd6c59a329643b8823d8c6ec53311',
    'scripts/mobile/android-main-saf-framework-import.py': 'f15a17128dd8679eab46d48e090148afb28c29895966d3ad9173a97415dede42',
    'scripts/mobile/android-main-preference.py': '27b5c007aa84882d27eaf8fef7f55cf5645edf9c8f50978e5ef0dae0a0385e9c',
}
PROFILES = {**PROFILES, P7_FILENAME_PHASE_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p7_filename_phase_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_FILENAME_PHASE_OVERLAY_PATHS), "Filename-phase-v1 exact60 source union required")
    require(all(overlay[name] == pin for name, pin in P7_FILENAME_PHASE_FIXED_AFTER.items()), "Filename-phase-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Filename-phase-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Filename-phase-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Filename-phase-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Filename-phase-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Filename-phase-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Filename-phase-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 905 and
        type(result["sources"]) is dict and len(result["sources"]) == 905 and
        set(result["inventory"]) == set(result["sources"]), "Filename-phase-v1 exact905 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result

_filename_phase_previous_review = _review
_filename_phase_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_FILENAME_PHASE_PROFILE:
        return _filename_phase_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned framework reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    require(value["schema"] == P7_FILENAME_PHASE_REVIEW_SCHEMA, "Closed filename-phase-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "Framework reviewed HEAD")
    api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_FILENAME_PHASE_OVERLAY_PATHS), "Framework exact60 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit framework before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Framework before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(all(overlay[name] == pin for name, pin in P7_FILENAME_PHASE_FIXED_AFTER.items()), "Filename-phase fixed59 after pins")
    # Capture-self is the sole omitted fixed pin and is explicitly in the independently
    # Root-hashed review, then source guard, tools metadata and final sealed SDK chain.
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay

def _source_guard(api, repo, overlay, profile):
    if profile == P7_FILENAME_PHASE_PROFILE:
        return p7_filename_phase_source_guard(api, repo, overlay)
    return _filename_phase_previous_source_guard(api, repo, overlay, profile)


# Stage87 explicit-FD is an additive two-APK purpose. All earlier profile
# recipes/fixed pins/guard bodies remain frozen; capture-self stays externally Root-pinned.
P7_EXPLICIT_FD_PROFILE = "p7-main-saf-explicit-fd-v1"
P7_EXPLICIT_FD_REVIEW_SCHEMA = "p7-main-saf-explicit-fd-build-review-v1"
P7_EXPLICIT_FD_OVERLAY_PATHS = P7_FILENAME_PHASE_OVERLAY_PATHS + (
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/UsageRules.kt',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/UsageRulesTest.kt',
)
P7_EXPLICIT_FD_FIXED_AFTER = {**P7_FILENAME_PHASE_FIXED_AFTER,
    'package.json': '86f7087dc2a891a264caef6981db53a5c3e3ead2445defcd0ea24df5177a9a48',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/UsageRules.kt': '0312f4f1e55dbc71e525596db1bc23c86286f1d9bd9ae13277f2387ec4d8fc69',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/UsageRulesTest.kt': '6e184bc72ff0965a81807ff312a42683f87d52e4f8e9e60f7f93ef630f6276cd',
    'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java': '64aa8326a477c9f86085c234af2ebc2fe84ad04c681904a6f50be8e2edcacddb',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': 'e198dd4c0cab075d7a36d6ffa982ffdd895af71d42d7f615807812cd86b61449',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '857a608e1b7a57772fd62e8d02286eb223539fbbf7e5639cd649531ae78b2737',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '6f54e060bed637fc88601bb33ec59291364a0dc66a28111aae2e8fd58f69fb53',
    'scripts/mobile/android-main-preference.py': 'cc8a5714ba03b1da3b918e400627f7cf1c5a05f37a4f749a0bb4a87e1d8216b2',
    'scripts/mobile/android-main-saf-framework-import.py': 'cd4f9e1905ca4130e8693adc976cd0f190066ce848c9b57c000cfa40447af651',
    'scripts/mobile/android-main-saf-framework.py': '3bf018c5277e2ab82d2ab0ab44e11de23efe2a7c6df1cbfbffa6a164eda5b328',
}
PROFILES = {**PROFILES, P7_EXPLICIT_FD_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p7_explicit_fd_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_EXPLICIT_FD_OVERLAY_PATHS), "Explicit-FD-v1 exact62 source union required")
    require(all(overlay[name] == pin for name, pin in P7_EXPLICIT_FD_FIXED_AFTER.items()), "Explicit-FD-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Explicit-FD-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Explicit-FD-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Explicit-FD-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Explicit-FD-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Explicit-FD-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Explicit-FD-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 905 and
        type(result["sources"]) is dict and len(result["sources"]) == 905 and
        set(result["inventory"]) == set(result["sources"]), "Explicit-FD-v1 exact905 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result

_explicit_fd_previous_review = _review
_explicit_fd_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_EXPLICIT_FD_PROFILE:
        return _explicit_fd_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned framework reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    require(value["schema"] == P7_EXPLICIT_FD_REVIEW_SCHEMA, "Closed explicit-fd-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "Framework reviewed HEAD")
    api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_EXPLICIT_FD_OVERLAY_PATHS), "Framework exact62 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit framework before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Framework before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(all(overlay[name] == pin for name, pin in P7_EXPLICIT_FD_FIXED_AFTER.items()), "Explicit-FD fixed61 after pins")
    # Capture-self is the sole omitted fixed pin and is explicitly in the independently
    # Root-hashed review, then source guard, tools metadata and final sealed SDK chain.
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay

def _source_guard(api, repo, overlay, profile):
    if profile == P7_EXPLICIT_FD_PROFILE:
        return p7_explicit_fd_source_guard(api, repo, overlay)
    return _explicit_fd_previous_source_guard(api, repo, overlay, profile)


# Fresh APP-parent producer purpose. Earlier maps, guards, recipes and Root records
# remain frozen. The new Root-pinned capture hash is deliberately outside its own map.
P7_PRODUCER_APP_FILES_PROFILE = "p7-main-saf-producer-app-files-v2"
P7_PRODUCER_APP_FILES_REVIEW_SCHEMA = "p7-main-saf-producer-app-files-build-review-v2"
P7_PRODUCER_APP_FILES_OVERLAY_PATHS = P7_EXPLICIT_FD_OVERLAY_PATHS
P7_PRODUCER_APP_FILES_FIXED_AFTER = {**P7_EXPLICIT_FD_FIXED_AFTER,
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': 'aae53404a620a4f59903bc2940b38b0e987a883ee9590ee8c76375f546190cde',
    'scripts/mobile/android-main-saf-framework-import.py': 'eecf65ddd83cd9926b987db5ccf808e98e62541e2fb19ea87bfc4b1b5730dff9',
}
PROFILES = {**PROFILES, P7_PRODUCER_APP_FILES_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p7_producer_app_files_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_PRODUCER_APP_FILES_OVERLAY_PATHS), "Producer-APP-files-v2 exact62 source union required")
    require(all(overlay[name] == pin for name, pin in P7_PRODUCER_APP_FILES_FIXED_AFTER.items()), "Producer-APP-files-v2 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # This independent static contract also pins package.json, an EXTRA_INPUTS entry.
    # The legacy generic guard deliberately forbids overlaying such build inputs.
    # Reuse its strict inventory/hash/identity rules here without mutating its baseline,
    # broadening legacy path acceptance, or accepting any non-enumerated build override.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Producer-APP-files-v2 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Producer-APP-files-v2 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Producer-APP-files-v2 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Producer-APP-files-v2 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Producer-APP-files-v2 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Producer-APP-files-v2 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 905 and
        type(result["sources"]) is dict and len(result["sources"]) == 905 and
        set(result["inventory"]) == set(result["sources"]), "Producer-APP-files-v2 exact905 build inputs")
    # Capture-self remains externally Root-pinned; the seven-key generic guard is unchanged.
    return result

_producer_app_files_previous_review = _review
_producer_app_files_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_PRODUCER_APP_FILES_PROFILE:
        return _producer_app_files_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned framework reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths"})
    require(value["schema"] == P7_PRODUCER_APP_FILES_REVIEW_SCHEMA, "Closed producer-app-files-v2 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "Framework reviewed HEAD")
    api.sha(value["statusSha256"])
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_PRODUCER_APP_FILES_OVERLAY_PATHS), "Framework exact62 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit framework before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Framework before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(all(overlay[name] == pin for name, pin in P7_PRODUCER_APP_FILES_FIXED_AFTER.items()), "Producer-APP-files fixed61 after pins")
    # Capture-self is the sole omitted fixed pin and is explicitly in the independently
    # Root-hashed review, then source guard, tools metadata and final sealed SDK chain.
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay

def _source_guard(api, repo, overlay, profile):
    if profile == P7_PRODUCER_APP_FILES_PROFILE:
        return p7_producer_app_files_source_guard(api, repo, overlay)
    return _producer_app_files_previous_source_guard(api, repo, overlay, profile)



# Stage91 receipt-safety is a separate two-APK source purpose. Old purposes and
# their exact inventories, fixed pins, recipes and Root records remain frozen.
# The reviewed selector list is SDK eligibility metadata, not Native admission.
P8_RECEIPT_SAFETY_PROFILE = "p8-easywork-receipt-safety-v1"
P8_RECEIPT_SAFETY_REVIEW_SCHEMA = "p8-easywork-receipt-safety-build-review-v1"
P8_RECEIPT_SAFETY_ADDITIONAL_OVERLAY_PATHS = (
    'apps/android/localization/native-copy.json',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkQueueReceiptTest.kt',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkProtocolPeer.kt',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkAgentClient.kt',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkReceiptNormalization.kt',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkSession.kt',
    'packages/mobile-core/build.gradle.kts',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ChatManagementRules.kt',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/DeviceReadingRules.kt',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ProjectGitRules.kt',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SubagentProjection.kt',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/WorkspaceArchiveRules.kt',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ChatManagementRulesTest.kt',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/EasyWorkChatManagementRulesTest.kt',
    'packages/protocol/fixtures/mobile/easywork-migration-crypto-v1.json',
    'packages/protocol/fixtures/mobile/easywork-push-envelope-v1.json',
    'packages/protocol/fixtures/mobile/easywork-receipt-v1.json',
    'packages/protocol/fixtures/mobile/task-sound-fixtures.manifest.json',
    'packages/protocol/fixtures/mobile/task-sound-fixtures.md',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-24.aiff',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-24.caf',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-32-big.caf',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-32.wav',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-big.caf',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.caf',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.wav',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-float64-big.caf',
    'packages/protocol/fixtures/mobile/task-sound-synthetic.aiff',
    'packages/protocol/fixtures/mobile/task-sound-synthetic.caf',
    'packages/protocol/schemas/agent-management-v1.schema.json',
    'packages/protocol/schemas/task-notifications-v1.schema.json',
)
P8_RECEIPT_SAFETY_OVERLAY_PATHS = P7_PRODUCER_APP_FILES_OVERLAY_PATHS + P8_RECEIPT_SAFETY_ADDITIONAL_OVERLAY_PATHS
P8_RECEIPT_SAFETY_NATIVE_SELECTORS = (
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#actualSendAndOriginalQueryPreserveTheSameQueueEvidenceAndRunIdentity',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#acceptedNativeQueueMarkerStillQueriesOnlyTheOriginalMessageAndPreservesTerminalState',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#rawFailedOutcomeAndTurnAreNeverReplacedByOuterCompletedAcknowledgement',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#sameVersionedReceiptWireVectorsMatchTheProductionSwiftDecoder',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#actualSteerRejectionsAndOriginalQueryCannotBecomeSubmittedOrBorrowTheOldRun',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#unknownAndSubmittingCommandsReadOnlyTheirOriginalQueueWithoutReplayingInput',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#missingCommandAcknowledgementCanRecoverAnAuthoritativeQueuedAdmission',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#authenticatedNullAndOnlyTypedOldPortAbsenceKeepTheOriginalUnknown',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#authorizationAndUnclassifiedNotFoundAreNotSwallowedOrMadeRetryable',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#wrongOriginalQueueIdentityCannotSettleTheCapturedInput',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#alteredCommandMetadataIsRejectedBeforeAnyQueueRead',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#aDifferentOriginalResultCannotBeRelabeledAsTheCapturedReceipt',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#malformedCommandResultsDoNotTriggerAnOriginalQueueQuery',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#nullCompletedResultAndOtherMethodsNeverInventSubmissionOrBorrowAQueue',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#malformedOrEmptyQueueObjectsAreNotTheSameAsAuthenticatedNull',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#refreshedCandidatePathsDoNotChangeTheOriginalConnectionIdentity',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#replacedCredentialOwnerIsRejectedBeforeAnyStatusRead',
    'com.agenthub.android.MobileEasyWorkQueueReceiptTest#delayedOriginalQueueResultCannotCompleteAfterItsOriginalOwnerCloses',
)
P8_RECEIPT_SAFETY_FIXED_AFTER = {**P7_PRODUCER_APP_FILES_FIXED_AFTER,
    'apps/android/localization/native-copy.json': 'a07407cc6f498d379c7ac526d487a26444a8c7416ab422c7db9dd0b0505f9761',
    'apps/android/src/androidTest/assets/localization-catalog.json': '0351b9a038914dc584d64234ea5a8f21e891042abaf4744ea750db14e96a5d87',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkQueueReceiptTest.kt': 'eab49df4d093dbb2c1d482001143079364fd6a7bd52751422169d090baa08ca5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkProtocolPeer.kt': '856351afc999eda16534d9733ca58b87eaf1b879287449e33a716a7813bdbbdc',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkAgentClient.kt': '5222b416d5dc515cfe3e13e928581c01df44be761add15aa3619181e668b062c',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkReceiptNormalization.kt': 'bbd13988432924ed47ba8f4120e038a4addc34f5b568b61dd08fa160712a8d28',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkSession.kt': 'bdefad1b3b16194da24ae1c66d63203a89ed0543e37160bba71755be6d4c711d',
    'apps/android/src/main/res/values-zh/product_strings.xml': 'a737dba8791dc8079431e91289ee06a089ce2d5bad66a12e3ef1d685f32aebe9',
    'apps/android/src/main/res/values/product_strings.xml': '7185ffd8445d192922f790132b74b233803586b9772f7d134363a2af981062de',
    'package.json': '07aeedc035684fd482372c4085873effb23afa5e0754ed872e1011f1db5c2cdd',
    'packages/mobile-core/build.gradle.kts': '78ad08a3339f34accb7b38bf10cfd670775c842b076cb4fce1939151e1d8c58b',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ChatManagementRules.kt': '81c6c44047c3bbf5f7fbc368af7302ce727e0ea0505af653a5f04eca8472ee33',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/DeviceReadingRules.kt': '94e3b49ea1ccd065fd058e63c5b1d66246fbdab774ee7bc5e1903d58b1de8883',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ProjectGitRules.kt': 'aafd704e667a2ab115e5ab132939c14e19c5676ebd4017054da8ca78d25eae71',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SubagentProjection.kt': '5823a929fe2b4ab2d4b2b9f0f76b2276071b9ecf974ce47915c1e602dcff1937',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/WorkspaceArchiveRules.kt': '22668fe24cd9843454b5363d77286f7bf93e7a1c7b4ac5c8c2987ede4d26b397',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ChatManagementRulesTest.kt': '4345c2613a399df13a3a83c3ba24a237d23ab48cff2d0946dabfe148c7432cba',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/EasyWorkChatManagementRulesTest.kt': 'd58978a62ccaf2d09cd098c5404aeb24c16be4928cbb50dae62bcaafb517c57c',
    'packages/protocol/fixtures/mobile/easywork-migration-crypto-v1.json': '18b7490a1b4ee72dcfaafaf8b955e96814bd1b7b025458578a92b6647fdd3296',
    'packages/protocol/fixtures/mobile/easywork-push-envelope-v1.json': '2935d6c6354004549cba7bdefd8a346446dcd3473c1bc68343cd38a5801937dd',
    'packages/protocol/fixtures/mobile/easywork-receipt-v1.json': '38edb15d562bafc6be55ab4fdb7c5afa1eb47aa0f7378d82f204c25caeec84de',
    'packages/protocol/fixtures/mobile/task-sound-fixtures.manifest.json': 'e000a91870e89f01824c8fdd5b7093b6f6941ba3cc2d60b1534a538e29634352',
    'packages/protocol/fixtures/mobile/task-sound-fixtures.md': 'de128ab4a57e8b1ae7e6c7d23f22b4fe71df7cc381453788ccd9e6e65af02078',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-24.aiff': '948ae3435193740cff64743cd63d2547e6ec21406e98a5c48c3643c2d7604445',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-24.caf': '73f1661d0474e99e860771ecf653cf689017099050add697dec20b9686b206e5',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-32-big.caf': '2112d3026d7fd34e25fd8d4d23eb26bad2ce67202c68ee1575b6393f85778d1f',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-32.wav': '6a152280eaf1978b56caedd569f5c6325324e0d093065dff4befb2938450e417',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-big.caf': '6fe6a6c23b6f8445aa42919f7a6a7ed91cfc08eab361de379c9af6e86cb2a991',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.caf': '495188a63f4bb6a32f6641cb3c1b820824b6cc979c587a064ebe0c99e8f5204e',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.wav': '57ad32020e107c73075b15c896ad4b69614c2343cf7261cca65743c84af8d676',
    'packages/protocol/fixtures/mobile/task-sound-synthetic-float64-big.caf': 'fa0a83f1da441a227c0e3d95801931495074c2c6e634780f424ea91c69b5872d',
    'packages/protocol/fixtures/mobile/task-sound-synthetic.aiff': '6e892732a18240c371a496c88366919a94b4f8fb31afec870f1e63f31eb07600',
    'packages/protocol/fixtures/mobile/task-sound-synthetic.caf': '4b4818ba108d7b2f84b2c433c47bb0e01c71007127eec5439af6f286a0f49cdd',
    'packages/protocol/schemas/agent-management-v1.schema.json': '708cda4e1d5751aa9d01e19e034928bd7c313d325b69d9c8b720a2452a938553',
    'packages/protocol/schemas/task-notifications-v1.schema.json': '630ec397ab606c4acfdb31c4d85bfe342a1efa5bacb983044d5afe0db2552899',
}
PROFILES = {**PROFILES, P8_RECEIPT_SAFETY_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p8_receipt_safety_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P8_RECEIPT_SAFETY_OVERLAY_PATHS), "Receipt-safety-v1 exact93 source union required")
    require(set(P8_RECEIPT_SAFETY_FIXED_AFTER) == set(P8_RECEIPT_SAFETY_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "Receipt-safety-v1 exact92 fixed source set")
    require(all(overlay[name] == pin for name, pin in P8_RECEIPT_SAFETY_FIXED_AFTER.items()), "Receipt-safety-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    # Enumerate the actual full build roots. New files and build/schema changes
    # belong only to this exact overlay; the generic baseline is not rewritten.
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "Receipt-safety-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "Receipt-safety-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "Receipt-safety-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "Receipt-safety-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "Receipt-safety-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "Receipt-safety-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 923 and
        type(result["sources"]) is dict and len(result["sources"]) == 923 and
        set(result["inventory"]) == set(result["sources"]), "Receipt-safety-v1 exact923 build inputs")
    # Self remains externally Root-pinned by review, tools and final SDK seal.
    return result

_receipt_safety_previous_review = _review
_receipt_safety_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P8_RECEIPT_SAFETY_PROFILE:
        return _receipt_safety_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned receipt-safety reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths", "nativeSelectors"})
    require(value["schema"] == P8_RECEIPT_SAFETY_REVIEW_SCHEMA, "Closed receipt-safety-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "Receipt-safety reviewed HEAD")
    api.sha(value["statusSha256"])
    require(type(value["nativeSelectors"]) is list and value["nativeSelectors"] == list(P8_RECEIPT_SAFETY_NATIVE_SELECTORS), "Receipt-safety exact18 Native eligibility selectors")
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P8_RECEIPT_SAFETY_OVERLAY_PATHS), "Receipt-safety exact93 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit receipt-safety before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "Receipt-safety before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(set(P8_RECEIPT_SAFETY_FIXED_AFTER) == set(P8_RECEIPT_SAFETY_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "Receipt-safety exact92 fixed source set")
    require(all(overlay[name] == pin for name, pin in P8_RECEIPT_SAFETY_FIXED_AFTER.items()), "Receipt-safety fixed92 after pins")
    # The self pin is admitted only through this immutable externally hashed review.
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay

def _source_guard(api, repo, overlay, profile):
    if profile == P8_RECEIPT_SAFETY_PROFILE:
        return p8_receipt_safety_source_guard(api, repo, overlay)
    return _receipt_safety_previous_source_guard(api, repo, overlay, profile)


# Stage96 is a separate F59 + original-receipt two-APK purpose. All older
# profile constants, function bodies, inventories and recipes remain frozen.
# Selector metadata is eligibility only; it never grants Native or Host admission.
P7_F59_RECEIPTS_PROFILE = "p7-f59-and-original-receipts-v1"
P7_F59_RECEIPTS_REVIEW_SCHEMA = "p7-f59-and-original-receipts-build-review-v1"
P7_F59_RECEIPTS_ADDITIONAL_OVERLAY_PATHS = ('apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileDurabilityTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeLegacyReplyRetrySafetyTest.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentHubApp.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentRegistry.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/CodexCommands.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeCodexCommands.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileDurableFiles.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileModel.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileStore.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFiles.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFilesPage.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationActivation.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationExistingPolicy.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeReplyRecovery.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSessionStorage.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSheets.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawGate.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawOwnership.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/ProductIcons.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/Sidebar.kt')
P7_F59_RECEIPTS_OVERLAY_PATHS = P8_RECEIPT_SAFETY_OVERLAY_PATHS + P7_F59_RECEIPTS_ADDITIONAL_OVERLAY_PATHS
P7_F59_RECEIPTS_NATIVE_SELECTORS = ('com.agenthub.android.NativeComputerFileDurabilityTest#actualWriteReplacementAndIndependentEngineReadKeepExactCiphertext',
 'com.agenthub.android.NativeComputerFileDurabilityTest#everyControlledSidecarRefusesReadWriteAndRemoveWithoutRecovery',
 'com.agenthub.android.NativeComputerFileDurabilityTest#wrongOrMissingCipherDigestCannotRetireTheOriginal',
 'com.agenthub.android.NativeComputerFileDurabilityTest#matchingCipherDigestRetiresOnlyTheExactRecordAndConfirmsAbsence',
 'com.agenthub.android.NativeComputerFileDurabilityTest#nonPrivateRecordModeRefusesAllOperationsWithoutChangingTheRecord',
 'com.agenthub.android.NativeComputerFileDurabilityTest#symlinkRecordCannotReadReplaceOrDeleteItsOwnedExternalTarget',
 'com.agenthub.android.NativeComputerFileDurabilityTest#hardLinkedRecordIsRejectedAndBothOwnedNamesKeepTheSameBytes',
 'com.agenthub.android.NativeComputerFileDurabilityTest#truncatedRecordAndInvalidNewCiphertextAreNotSilentlyRepaired',
 'com.agenthub.android.NativeComputerFileDurabilityTest#competingEngineCannotEnterHeldKernelLockAndOriginalWriterRemainsUsable',
 'com.agenthub.android.NativeLegacyReplyRetrySafetyTest#coldVersionOneReadyNotWrittenCannotRetryOrRestoreIntoANewDraft',
 'com.agenthub.android.NativeLegacyReplyRetrySafetyTest#androidGuardLeavesSharedRuleAndFirstJournalTransitionUnchanged',
 'com.agenthub.android.NativeLegacyReplyRetrySafetyTest#freshStrongProofStillAllowsExactlyOneJournalConsumption',
 'com.agenthub.android.NativeLegacyReplyRetrySafetyTest#strongLabelCannotBypassFreshnessTrustTargetOrReadOnlyGuards',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#actualSendAndOriginalQueryPreserveTheSameQueueEvidenceAndRunIdentity',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#acceptedNativeQueueMarkerStillQueriesOnlyTheOriginalMessageAndPreservesTerminalState',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#rawFailedOutcomeAndTurnAreNeverReplacedByOuterCompletedAcknowledgement',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#sameVersionedReceiptWireVectorsMatchTheProductionSwiftDecoder',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#actualSteerRejectionsAndOriginalQueryCannotBecomeSubmittedOrBorrowTheOldRun',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#unknownAndSubmittingCommandsReadOnlyTheirOriginalQueueWithoutReplayingInput',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#missingCommandAcknowledgementCanRecoverAnAuthoritativeQueuedAdmission',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#authenticatedNullAndOnlyTypedOldPortAbsenceKeepTheOriginalUnknown',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#authorizationAndUnclassifiedNotFoundAreNotSwallowedOrMadeRetryable',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#wrongOriginalQueueIdentityCannotSettleTheCapturedInput',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#alteredCommandMetadataIsRejectedBeforeAnyQueueRead',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#aDifferentOriginalResultCannotBeRelabeledAsTheCapturedReceipt',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#malformedCommandResultsDoNotTriggerAnOriginalQueueQuery',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#nullCompletedResultAndOtherMethodsNeverInventSubmissionOrBorrowAQueue',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#malformedOrEmptyQueueObjectsAreNotTheSameAsAuthenticatedNull',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#refreshedCandidatePathsDoNotChangeTheOriginalConnectionIdentity',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#replacedCredentialOwnerIsRejectedBeforeAnyStatusRead',
 'com.agenthub.android.MobileEasyWorkQueueReceiptTest#delayedOriginalQueueResultCannotCompleteAfterItsOriginalOwnerCloses')
P7_F59_RECEIPTS_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': 'f013815ec371057ae1039cfa0ed6c5749dcb8a3d70f9b5455933935795e293da',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': '8f765491a4c08c9fabc1da4db44af98ae4d8065830864e9996d880294f3ca33d',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf',
 'apps/android/localization/native-copy.json': '6b2e051f1c59e6fa3e8f19bf67ef5a9abf91abbca771fb2c22ab5f4e2e774406',
 'apps/android/src/androidTest/AndroidManifest.xml': 'aa82a1a072275d72ee7e29c163895cc7b1a9af8852f08d0f5d2e89d87ba3fc6c',
 'apps/android/src/androidTest/assets/localization-catalog.json': '8c22c1553eb50f9a23b6aed494677e6d25d944207cae1375908c2245fac5fbad',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java': 'f3f4993066c9d9d393621bc101597c6f666196517863f68a5053e70e053a02c5',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java': '1338f8742f3c5a29f5f5cb274b54e6b7e51c36c0800ed8ba7445764d691825cd',
 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java': '8ec239f0574225e12ce1c4b6e72b02757e5ac29b437c41113ae368964e7a5671',
 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java': '64aa8326a477c9f86085c234af2ebc2fe84ad04c681904a6f50be8e2edcacddb',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': 'a9232e59346ce707fe5aa1c92a7f28a738c2aaa5ffe7d754949f6bce6a7b0523',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkQueueReceiptTest.kt': 'eab49df4d093dbb2c1d482001143079364fd6a7bd52751422169d090baa08ca5',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '17b81dcdf27a30d823c0cf8c1a146b66c08db416098f2a2c484d7f20cb6d6064',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileDurabilityTest.kt': '63eb204bbd81f8de24ac1e5a742ab9da495003c87dc9dc9ae32e3cd6441e47c2',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkProtocolPeer.kt': '856351afc999eda16534d9733ca58b87eaf1b879287449e33a716a7813bdbbdc',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeLegacyReplyRetrySafetyTest.kt': '1ac749b91365d1042eba6f4627c978b27baf7e7dc40908713e01a87ef67d98cf',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': 'aae53404a620a4f59903bc2940b38b0e987a883ee9590ee8c76375f546190cde',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '857a608e1b7a57772fd62e8d02286eb223539fbbf7e5639cd649531ae78b2737',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '6f54e060bed637fc88601bb33ec59291364a0dc66a28111aae2e8fd58f69fb53',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c',
 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c',
 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce',
 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2',
 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentHubApp.kt': '038a9481b030f582d510c351f8c2ce49966005d0efa1491369f5b2890c28b1fa',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentRegistry.kt': '3ee5afc1f237f6ed7f27f35ae00ec499372e7777f1e48b1b91f48b8c41b065fa',
 'apps/android/src/main/kotlin/com/agenthub/android/CodexCommands.kt': '8334e01c9fee65e796c53f8bebda25c4a04f7d6806634d82d438defd9ecc0bc0',
 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': 'b571940cbf7d795aef02260719a1df7436aaa1a2ee5d8d2063faa7caca0147fa',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkAgentClient.kt': '5222b416d5dc515cfe3e13e928581c01df44be761add15aa3619181e668b062c',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkReceiptNormalization.kt': 'bbd13988432924ed47ba8f4120e038a4addc34f5b568b61dd08fa160712a8d28',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkSession.kt': 'bdefad1b3b16194da24ae1c66d63203a89ed0543e37160bba71755be6d4c711d',
 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': '221ee4171b352154238a7c019c2d80afc0a215002a6048dbbfe472d23dcd43f2',
 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d',
 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': 'ce4e3d3ed0b3e19437b60ebf598b5edfc45fd0582baef082c4640f52b2472d0d',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeCodexCommands.kt': 'b4ae491497033e179e1b4f2549146d9d496021062c595b05a49d2f2b9742244c',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileDurableFiles.kt': '603addff0f87553030cb7c63ac36dfeea26a817748876df490d57546c537bf46',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileModel.kt': '667dfdca68128bb294a367528c15d80dd535fe788e03750281fa55a762003e78',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileStore.kt': '4837549a0c8301be8c6a28b006aecaf49ad01741d96a5aa9cccad3cd29c35ab3',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFiles.kt': 'd9519869a954a9c1bed93114f77c2cb2fff4e139676df7410afe1ff309d8d3dc',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFilesPage.kt': '52f778ac6c8878d0dfb74de0f1df15de444c553b552c91633a4673829080bf72',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationActivation.kt': '4049489bb89c3bca34ee32ac483cd36dc257b50ed3864e614b79ff58320dd81f',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationExistingPolicy.kt': '034c7add951508a49fb83f118767ac764350a89e2f39a6dba2cd3ad4450956f9',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeReplyRecovery.kt': '30414b9944b47038e665e257baf8f8a75afa6cc698e10aca9182a8f6a9b82834',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSessionStorage.kt': 'eead47bcb98181e698c375124233ebbc8d9965af6dbb154b8cb0a0e99b0733f9',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSheets.kt': '2204fdd39e3e2ea46dee82d1f04f420cdb5f5ffcbd9d047ece4b960f9892cc32',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawGate.kt': '57a48abaa41481d1dc7c751089c0d32089b7ebe7ac66d44cd60006e9d46a705f',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawOwnership.kt': '97146e786a153ddfc1ac0dc560f1fdd87aa0d03bcd7b24b8a659b1a1f93b3644',
 'apps/android/src/main/kotlin/com/agenthub/android/ProductIcons.kt': '5b0bd956f8f8eb6931c75c41229f1b91073d4cbac2ed9eac57ab869d42a451ae',
 'apps/android/src/main/kotlin/com/agenthub/android/Sidebar.kt': 'c56b8e28d4990254f9e68785aa3779e6c91de2012ea0e85f3fe155fa09171ee9',
 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4',
 'apps/android/src/main/res/values-zh/product_strings.xml': '02192274fcedd07e86926472145e844cbdfa60bbacd8f10b39f5deda1e9fa999',
 'apps/android/src/main/res/values/product_strings.xml': '46a4180fe74b8f0c583fcf5f33192018df48a076136c84e8da54f19658322543',
 'package.json': '07aeedc035684fd482372c4085873effb23afa5e0754ed872e1011f1db5c2cdd',
 'packages/mobile-core/build.gradle.kts': '78ad08a3339f34accb7b38bf10cfd670775c842b076cb4fce1939151e1d8c58b',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ChatManagementRules.kt': '81c6c44047c3bbf5f7fbc368af7302ce727e0ea0505af653a5f04eca8472ee33',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/DeviceReadingRules.kt': '94e3b49ea1ccd065fd058e63c5b1d66246fbdab774ee7bc5e1903d58b1de8883',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ProjectGitRules.kt': 'aafd704e667a2ab115e5ab132939c14e19c5676ebd4017054da8ca78d25eae71',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': 'efa63e223691a3f2f9364b99cbd3b7dcdf9eeb2099d863b156dad4f987cc5671',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SubagentProjection.kt': '5823a929fe2b4ab2d4b2b9f0f76b2276071b9ecf974ce47915c1e602dcff1937',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/UsageRules.kt': '0312f4f1e55dbc71e525596db1bc23c86286f1d9bd9ae13277f2387ec4d8fc69',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/WorkspaceArchiveRules.kt': '22668fe24cd9843454b5363d77286f7bf93e7a1c7b4ac5c8c2987ede4d26b397',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ChatManagementRulesTest.kt': '4345c2613a399df13a3a83c3ba24a237d23ab48cff2d0946dabfe148c7432cba',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/EasyWorkChatManagementRulesTest.kt': 'd58978a62ccaf2d09cd098c5404aeb24c16be4928cbb50dae62bcaafb517c57c',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '3c5b887f641e5e4b197c69b9f759dc883a5540d62decc3d56a18a64a78d7ef34',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/UsageRulesTest.kt': '6e184bc72ff0965a81807ff312a42683f87d52e4f8e9e60f7f93ef630f6276cd',
 'packages/protocol/fixtures/mobile/easywork-migration-crypto-v1.json': '18b7490a1b4ee72dcfaafaf8b955e96814bd1b7b025458578a92b6647fdd3296',
 'packages/protocol/fixtures/mobile/easywork-push-envelope-v1.json': '2935d6c6354004549cba7bdefd8a346446dcd3473c1bc68343cd38a5801937dd',
 'packages/protocol/fixtures/mobile/easywork-receipt-v1.json': '38edb15d562bafc6be55ab4fdb7c5afa1eb47aa0f7378d82f204c25caeec84de',
 'packages/protocol/fixtures/mobile/task-sound-fixtures.manifest.json': 'e000a91870e89f01824c8fdd5b7093b6f6941ba3cc2d60b1534a538e29634352',
 'packages/protocol/fixtures/mobile/task-sound-fixtures.md': 'de128ab4a57e8b1ae7e6c7d23f22b4fe71df7cc381453788ccd9e6e65af02078',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-24.aiff': '948ae3435193740cff64743cd63d2547e6ec21406e98a5c48c3643c2d7604445',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-24.caf': '73f1661d0474e99e860771ecf653cf689017099050add697dec20b9686b206e5',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-32-big.caf': '2112d3026d7fd34e25fd8d4d23eb26bad2ce67202c68ee1575b6393f85778d1f',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-32.wav': '6a152280eaf1978b56caedd569f5c6325324e0d093065dff4befb2938450e417',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-big.caf': '6fe6a6c23b6f8445aa42919f7a6a7ed91cfc08eab361de379c9af6e86cb2a991',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.caf': '495188a63f4bb6a32f6641cb3c1b820824b6cc979c587a064ebe0c99e8f5204e',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.wav': '57ad32020e107c73075b15c896ad4b69614c2343cf7261cca65743c84af8d676',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float64-big.caf': 'fa0a83f1da441a227c0e3d95801931495074c2c6e634780f424ea91c69b5872d',
 'packages/protocol/fixtures/mobile/task-sound-synthetic.aiff': '6e892732a18240c371a496c88366919a94b4f8fb31afec870f1e63f31eb07600',
 'packages/protocol/fixtures/mobile/task-sound-synthetic.caf': '4b4818ba108d7b2f84b2c433c47bb0e01c71007127eec5439af6f286a0f49cdd',
 'packages/protocol/schemas/agent-management-v1.schema.json': '708cda4e1d5751aa9d01e19e034928bd7c313d325b69d9c8b720a2452a938553',
 'packages/protocol/schemas/task-notifications-v1.schema.json': '630ec397ab606c4acfdb31c4d85bfe342a1efa5bacb983044d5afe0db2552899',
 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5',
 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c',
 'scripts/mobile/android-main-preference.py': 'cc8a5714ba03b1da3b918e400627f7cf1c5a05f37a4f749a0bb4a87e1d8216b2',
 'scripts/mobile/android-main-saf-framework-import.py': 'eecf65ddd83cd9926b987db5ccf808e98e62541e2fb19ea87bfc4b1b5730dff9',
 'scripts/mobile/android-main-saf-framework.py': '3bf018c5277e2ab82d2ab0ab44e11de23efe2a7c6df1cbfbffa6a164eda5b328',
 'scripts/mobile/android-main-saf.py': '3fe7515b621e80831a8c7c0218dc6ec44d1d8dca01a996b33473836cff0c90c8',
 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775',
 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3',
 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4',
 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a',
 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d',
 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9',
 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187'}
PROFILES = {**PROFILES, P7_F59_RECEIPTS_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p7_f59_receipts_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_F59_RECEIPTS_OVERLAY_PATHS), "F59-receipts-v1 exact113 source union required")
    require(set(P7_F59_RECEIPTS_FIXED_AFTER) == set(P7_F59_RECEIPTS_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "F59-receipts-v1 exact112 fixed source set")
    require(all(overlay[name] == pin for name, pin in P7_F59_RECEIPTS_FIXED_AFTER.items()), "F59-receipts-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "F59-receipts-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "F59-receipts-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "F59-receipts-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "F59-receipts-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "F59-receipts-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "F59-receipts-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 930 and
        type(result["sources"]) is dict and len(result["sources"]) == 930 and
        set(result["inventory"]) == set(result["sources"]), "F59-receipts-v1 exact930 build inputs")
    return result

_f59_receipts_previous_review = _review
_f59_receipts_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_F59_RECEIPTS_PROFILE:
        return _f59_receipts_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned F59-receipts reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths", "nativeSelectors"})
    require(value["schema"] == P7_F59_RECEIPTS_REVIEW_SCHEMA, "Closed F59-receipts-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "F59-receipts reviewed HEAD")
    api.sha(value["statusSha256"])
    require(type(value["nativeSelectors"]) is list and value["nativeSelectors"] == list(P7_F59_RECEIPTS_NATIVE_SELECTORS), "F59-receipts exact31 Native eligibility selectors")
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_F59_RECEIPTS_OVERLAY_PATHS), "F59-receipts exact113 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit F59-receipts before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "F59-receipts before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(set(P7_F59_RECEIPTS_FIXED_AFTER) == set(P7_F59_RECEIPTS_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "F59-receipts exact112 fixed source set")
    require(all(overlay[name] == pin for name, pin in P7_F59_RECEIPTS_FIXED_AFTER.items()), "F59-receipts fixed112 after pins")
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay

def _source_guard(api, repo, overlay, profile):
    if profile == P7_F59_RECEIPTS_PROFILE:
        return p7_f59_receipts_source_guard(api, repo, overlay)
    return _f59_receipts_previous_source_guard(api, repo, overlay, profile)


# Independent frozen-current-F59 source snapshot build/Native purpose.
# Prior purpose contracts and functions remain unchanged.
P7_F59_FROZEN_SOURCE_PROFILE = "p7-f59-frozen-source-v1"
P7_F59_FROZEN_SOURCE_REVIEW_SCHEMA = "p7-f59-frozen-source-build-review-v1"
P7_F59_FROZEN_SOURCE_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java',
 'apps/android/localization/native-copy.json',
 'apps/android/src/androidTest/AndroidManifest.xml',
 'apps/android/src/androidTest/assets/localization-catalog.json',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java',
 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java',
 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkQueueReceiptTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileDurabilityTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileSnapshotFixture.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileSnapshotTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkProtocolPeer.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeLegacyReplyRetrySafetyTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt',
 'apps/android/src/debug/AndroidManifest.xml',
 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt',
 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt',
 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentHubApp.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentRegistry.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/CodexCommands.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkAgentClient.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkReceiptNormalization.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkSession.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeCodexCommands.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileDurableFiles.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileJournal.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileModel.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileStore.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFiles.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFilesPage.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationActivation.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileGraph.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileSnapshot.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileSourceFiles.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationExistingPolicy.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeReplyRecovery.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSessionStorage.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSheets.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawGate.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawOwnership.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/ProductIcons.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/Sidebar.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt',
 'apps/android/src/main/res/values-zh/product_strings.xml',
 'apps/android/src/main/res/values/product_strings.xml',
 'package.json',
 'packages/mobile-core/build.gradle.kts',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ChatManagementRules.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ComputerFileMigrationRules.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/DeviceReadingRules.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ProjectGitRules.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SubagentProjection.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/UsageRules.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/WorkspaceArchiveRules.kt',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ChatManagementRulesTest.kt',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ComputerFileMigrationRulesTest.kt',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/EasyWorkChatManagementRulesTest.kt',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/UsageRulesTest.kt',
 'packages/protocol/fixtures/mobile/easywork-migration-crypto-v1.json',
 'packages/protocol/fixtures/mobile/easywork-push-envelope-v1.json',
 'packages/protocol/fixtures/mobile/easywork-receipt-v1.json',
 'packages/protocol/fixtures/mobile/task-sound-fixtures.manifest.json',
 'packages/protocol/fixtures/mobile/task-sound-fixtures.md',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-24.aiff',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-24.caf',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-32-big.caf',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-32.wav',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-big.caf',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.caf',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.wav',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float64-big.caf',
 'packages/protocol/fixtures/mobile/task-sound-synthetic.aiff',
 'packages/protocol/fixtures/mobile/task-sound-synthetic.caf',
 'packages/protocol/schemas/agent-management-v1.schema.json',
 'packages/protocol/schemas/computer-file-originals-v1.schema.json',
 'packages/protocol/schemas/task-notifications-v1.schema.json',
 'scripts/mobile/android-child-originals-pair.py',
 'scripts/mobile/android-existing-originals-pair.py',
 'scripts/mobile/android-main-preference.py',
 'scripts/mobile/android-main-saf-framework-import.py',
 'scripts/mobile/android-main-saf-framework.py',
 'scripts/mobile/android-main-saf.py',
 'scripts/mobile/android-nonempty-device-first.py',
 'scripts/mobile/android-nonempty-device-pair.py',
 'scripts/mobile/android-nonempty-foreground-pair.py',
 'scripts/mobile/android-nonempty-pristine-first.py',
 'scripts/mobile/android-sdk-capture.py',
 'scripts/mobile/android-voice-originals-controller.py',
 'scripts/mobile/android-voice-originals-pair.py',
 'scripts/mobile/android-voice-originals-workers.py',
 'scripts/mobile/computer-file-originals-contract.test.mjs')
P7_F59_FROZEN_SOURCE_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': 'f013815ec371057ae1039cfa0ed6c5749dcb8a3d70f9b5455933935795e293da',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': '8f765491a4c08c9fabc1da4db44af98ae4d8065830864e9996d880294f3ca33d',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf',
 'apps/android/localization/native-copy.json': '6b2e051f1c59e6fa3e8f19bf67ef5a9abf91abbca771fb2c22ab5f4e2e774406',
 'apps/android/src/androidTest/AndroidManifest.xml': 'aa82a1a072275d72ee7e29c163895cc7b1a9af8852f08d0f5d2e89d87ba3fc6c',
 'apps/android/src/androidTest/assets/localization-catalog.json': '8c22c1553eb50f9a23b6aed494677e6d25d944207cae1375908c2245fac5fbad',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java': 'f3f4993066c9d9d393621bc101597c6f666196517863f68a5053e70e053a02c5',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java': '1338f8742f3c5a29f5f5cb274b54e6b7e51c36c0800ed8ba7445764d691825cd',
 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java': '8ec239f0574225e12ce1c4b6e72b02757e5ac29b437c41113ae368964e7a5671',
 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java': '64aa8326a477c9f86085c234af2ebc2fe84ad04c681904a6f50be8e2edcacddb',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': 'a9232e59346ce707fe5aa1c92a7f28a738c2aaa5ffe7d754949f6bce6a7b0523',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkQueueReceiptTest.kt': 'eab49df4d093dbb2c1d482001143079364fd6a7bd52751422169d090baa08ca5',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '17b81dcdf27a30d823c0cf8c1a146b66c08db416098f2a2c484d7f20cb6d6064',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileDurabilityTest.kt': '63eb204bbd81f8de24ac1e5a742ab9da495003c87dc9dc9ae32e3cd6441e47c2',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileSnapshotFixture.kt': '6d57f7a87cbeb34de2caa53057af569d779cc84a13909816e2c3f89316b0b1b0',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileSnapshotTest.kt': 'c9780f2e0851ba7b982284a95b1b5f1eea0e5d9bf9aefdd5cd9012d869b0eac4',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkProtocolPeer.kt': '856351afc999eda16534d9733ca58b87eaf1b879287449e33a716a7813bdbbdc',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeLegacyReplyRetrySafetyTest.kt': '1ac749b91365d1042eba6f4627c978b27baf7e7dc40908713e01a87ef67d98cf',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': 'aae53404a620a4f59903bc2940b38b0e987a883ee9590ee8c76375f546190cde',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '857a608e1b7a57772fd62e8d02286eb223539fbbf7e5639cd649531ae78b2737',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '6f54e060bed637fc88601bb33ec59291364a0dc66a28111aae2e8fd58f69fb53',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c',
 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c',
 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce',
 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2',
 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentHubApp.kt': '038a9481b030f582d510c351f8c2ce49966005d0efa1491369f5b2890c28b1fa',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentRegistry.kt': '3ee5afc1f237f6ed7f27f35ae00ec499372e7777f1e48b1b91f48b8c41b065fa',
 'apps/android/src/main/kotlin/com/agenthub/android/CodexCommands.kt': '8334e01c9fee65e796c53f8bebda25c4a04f7d6806634d82d438defd9ecc0bc0',
 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': 'b571940cbf7d795aef02260719a1df7436aaa1a2ee5d8d2063faa7caca0147fa',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkAgentClient.kt': '5222b416d5dc515cfe3e13e928581c01df44be761add15aa3619181e668b062c',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkReceiptNormalization.kt': 'bbd13988432924ed47ba8f4120e038a4addc34f5b568b61dd08fa160712a8d28',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkSession.kt': 'bdefad1b3b16194da24ae1c66d63203a89ed0543e37160bba71755be6d4c711d',
 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': '221ee4171b352154238a7c019c2d80afc0a215002a6048dbbfe472d23dcd43f2',
 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d',
 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': 'ce4e3d3ed0b3e19437b60ebf598b5edfc45fd0582baef082c4640f52b2472d0d',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeCodexCommands.kt': 'b4ae491497033e179e1b4f2549146d9d496021062c595b05a49d2f2b9742244c',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileDurableFiles.kt': '603addff0f87553030cb7c63ac36dfeea26a817748876df490d57546c537bf46',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileJournal.kt': 'f4c15a2b593f322048b5c91ad38e97fa56b54ab9937990cb7b87397d78676646',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileModel.kt': '667dfdca68128bb294a367528c15d80dd535fe788e03750281fa55a762003e78',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileStore.kt': '734f5607fc8a2355ec317da9090e58081f90e0efa65ed4a63deb472ef805246b',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFiles.kt': 'd9519869a954a9c1bed93114f77c2cb2fff4e139676df7410afe1ff309d8d3dc',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFilesPage.kt': '52f778ac6c8878d0dfb74de0f1df15de444c553b552c91633a4673829080bf72',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationActivation.kt': '4049489bb89c3bca34ee32ac483cd36dc257b50ed3864e614b79ff58320dd81f',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileGraph.kt': 'c3a68e94f507d30f10f97b9c6ad9679fee52916cdfed806e28550256328dacd6',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileSnapshot.kt': '8e31c8baef7ed4e596b45e24b8dddda5452b7794e53b4512a1faa13911b55e3d',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileSourceFiles.kt': 'e7e0d876c4bc94697fcd95900663ae45ef556d19feb9d27c203b1283f3223d64',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationExistingPolicy.kt': '034c7add951508a49fb83f118767ac764350a89e2f39a6dba2cd3ad4450956f9',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeReplyRecovery.kt': '30414b9944b47038e665e257baf8f8a75afa6cc698e10aca9182a8f6a9b82834',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSessionStorage.kt': '9c6fa6deee999aee523246349371b38db745e64803b530243e8889dd8b1eae6a',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSheets.kt': '2204fdd39e3e2ea46dee82d1f04f420cdb5f5ffcbd9d047ece4b960f9892cc32',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawGate.kt': '57a48abaa41481d1dc7c751089c0d32089b7ebe7ac66d44cd60006e9d46a705f',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawOwnership.kt': '97146e786a153ddfc1ac0dc560f1fdd87aa0d03bcd7b24b8a659b1a1f93b3644',
 'apps/android/src/main/kotlin/com/agenthub/android/ProductIcons.kt': '5b0bd956f8f8eb6931c75c41229f1b91073d4cbac2ed9eac57ab869d42a451ae',
 'apps/android/src/main/kotlin/com/agenthub/android/Sidebar.kt': 'c56b8e28d4990254f9e68785aa3779e6c91de2012ea0e85f3fe155fa09171ee9',
 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4',
 'apps/android/src/main/res/values-zh/product_strings.xml': '02192274fcedd07e86926472145e844cbdfa60bbacd8f10b39f5deda1e9fa999',
 'apps/android/src/main/res/values/product_strings.xml': '46a4180fe74b8f0c583fcf5f33192018df48a076136c84e8da54f19658322543',
 'package.json': '9d6fa4d4b9366df9d4fbd08dfeb482e22646280c7d6917aee11d9a28cc7b89c1',
 'packages/mobile-core/build.gradle.kts': '78ad08a3339f34accb7b38bf10cfd670775c842b076cb4fce1939151e1d8c58b',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ChatManagementRules.kt': '81c6c44047c3bbf5f7fbc368af7302ce727e0ea0505af653a5f04eca8472ee33',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ComputerFileMigrationRules.kt': '41b8cc833e4adff9761fc712853334d529831121df12695f8a77b25622795741',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/DeviceReadingRules.kt': '94e3b49ea1ccd065fd058e63c5b1d66246fbdab774ee7bc5e1903d58b1de8883',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ProjectGitRules.kt': 'aafd704e667a2ab115e5ab132939c14e19c5676ebd4017054da8ca78d25eae71',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': 'efa63e223691a3f2f9364b99cbd3b7dcdf9eeb2099d863b156dad4f987cc5671',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SubagentProjection.kt': '5823a929fe2b4ab2d4b2b9f0f76b2276071b9ecf974ce47915c1e602dcff1937',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/UsageRules.kt': '0312f4f1e55dbc71e525596db1bc23c86286f1d9bd9ae13277f2387ec4d8fc69',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/WorkspaceArchiveRules.kt': '22668fe24cd9843454b5363d77286f7bf93e7a1c7b4ac5c8c2987ede4d26b397',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ChatManagementRulesTest.kt': '4345c2613a399df13a3a83c3ba24a237d23ab48cff2d0946dabfe148c7432cba',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ComputerFileMigrationRulesTest.kt': '8868b40cf00ae096cb8ec7a2eb0cba55336d4bd667c5a00ed0005634c881c276',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/EasyWorkChatManagementRulesTest.kt': 'd58978a62ccaf2d09cd098c5404aeb24c16be4928cbb50dae62bcaafb517c57c',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '3c5b887f641e5e4b197c69b9f759dc883a5540d62decc3d56a18a64a78d7ef34',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/UsageRulesTest.kt': '6e184bc72ff0965a81807ff312a42683f87d52e4f8e9e60f7f93ef630f6276cd',
 'packages/protocol/fixtures/mobile/easywork-migration-crypto-v1.json': '18b7490a1b4ee72dcfaafaf8b955e96814bd1b7b025458578a92b6647fdd3296',
 'packages/protocol/fixtures/mobile/easywork-push-envelope-v1.json': '2935d6c6354004549cba7bdefd8a346446dcd3473c1bc68343cd38a5801937dd',
 'packages/protocol/fixtures/mobile/easywork-receipt-v1.json': '38edb15d562bafc6be55ab4fdb7c5afa1eb47aa0f7378d82f204c25caeec84de',
 'packages/protocol/fixtures/mobile/task-sound-fixtures.manifest.json': 'e000a91870e89f01824c8fdd5b7093b6f6941ba3cc2d60b1534a538e29634352',
 'packages/protocol/fixtures/mobile/task-sound-fixtures.md': 'de128ab4a57e8b1ae7e6c7d23f22b4fe71df7cc381453788ccd9e6e65af02078',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-24.aiff': '948ae3435193740cff64743cd63d2547e6ec21406e98a5c48c3643c2d7604445',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-24.caf': '73f1661d0474e99e860771ecf653cf689017099050add697dec20b9686b206e5',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-32-big.caf': '2112d3026d7fd34e25fd8d4d23eb26bad2ce67202c68ee1575b6393f85778d1f',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-32.wav': '6a152280eaf1978b56caedd569f5c6325324e0d093065dff4befb2938450e417',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-big.caf': '6fe6a6c23b6f8445aa42919f7a6a7ed91cfc08eab361de379c9af6e86cb2a991',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.caf': '495188a63f4bb6a32f6641cb3c1b820824b6cc979c587a064ebe0c99e8f5204e',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.wav': '57ad32020e107c73075b15c896ad4b69614c2343cf7261cca65743c84af8d676',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float64-big.caf': 'fa0a83f1da441a227c0e3d95801931495074c2c6e634780f424ea91c69b5872d',
 'packages/protocol/fixtures/mobile/task-sound-synthetic.aiff': '6e892732a18240c371a496c88366919a94b4f8fb31afec870f1e63f31eb07600',
 'packages/protocol/fixtures/mobile/task-sound-synthetic.caf': '4b4818ba108d7b2f84b2c433c47bb0e01c71007127eec5439af6f286a0f49cdd',
 'packages/protocol/schemas/agent-management-v1.schema.json': '708cda4e1d5751aa9d01e19e034928bd7c313d325b69d9c8b720a2452a938553',
 'packages/protocol/schemas/computer-file-originals-v1.schema.json': '03d9727fd8c54be5e20505727f833967d54b8328fa9dbb96e7eff58fb8bf768a',
 'packages/protocol/schemas/task-notifications-v1.schema.json': '630ec397ab606c4acfdb31c4d85bfe342a1efa5bacb983044d5afe0db2552899',
 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5',
 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c',
 'scripts/mobile/android-main-preference.py': 'cc8a5714ba03b1da3b918e400627f7cf1c5a05f37a4f749a0bb4a87e1d8216b2',
 'scripts/mobile/android-main-saf-framework-import.py': 'eecf65ddd83cd9926b987db5ccf808e98e62541e2fb19ea87bfc4b1b5730dff9',
 'scripts/mobile/android-main-saf-framework.py': '3bf018c5277e2ab82d2ab0ab44e11de23efe2a7c6df1cbfbffa6a164eda5b328',
 'scripts/mobile/android-main-saf.py': '3fe7515b621e80831a8c7c0218dc6ec44d1d8dca01a996b33473836cff0c90c8',
 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775',
 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3',
 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4',
 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a',
 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d',
 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9',
 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187',
 'scripts/mobile/computer-file-originals-contract.test.mjs': 'fee68f3f9165e4db0eaca2d6ec79e6e5b91c546ac9c2429bfbaaccb91db6c7cd'}
P7_F59_FROZEN_SOURCE_NATIVE_SELECTORS = ('com.agenthub.android.NativeComputerFileSnapshotTest#boundedDirectoryOverflowClosesItsActualStreamAndExpiredRealOwnerCannotEnumerate',
 'com.agenthub.android.NativeComputerFileSnapshotTest#replacedRecordIdentityIsRejectedWithoutReopeningOrChangingTheReplacement',
 'com.agenthub.android.NativeComputerFileSnapshotTest#absentNamespaceAndChildKeyCannotBeCreatedByAnExistingOnlySnapshot',
 'com.agenthub.android.NativeComputerFileSnapshotTest#unknownOriginalUsesRealCiphertextAndItsCallbacksRefuseAfterTheLeaseScopeReturns',
 'com.agenthub.android.NativeComputerFileSnapshotTest#controlledSidecarAndAuthenticatedOrphanRejectTheWholeSourceWithoutRepair',
 'com.agenthub.android.NativeComputerFileSnapshotTest#wrongInstalledKeyAndReplacedActualGenerationAreBothRejected',
 'com.agenthub.android.NativeComputerFileSnapshotTest#completedPartialRetirementRequiresTheRealReadonlyFileAndDirectoryBarriers',
 'com.agenthub.android.NativeComputerFileSnapshotTest#missingUnknownAndWrongTerminalReceiptCannotBeAcceptedAsRetiredOriginals')
PROFILES = {**PROFILES, P7_F59_FROZEN_SOURCE_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p7_f59_frozen_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_F59_FROZEN_SOURCE_OVERLAY_PATHS), "F59-frozen-source-v1 exact123 source union required")
    require(set(P7_F59_FROZEN_SOURCE_FIXED_AFTER) == set(P7_F59_FROZEN_SOURCE_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "F59-frozen-source-v1 exact122 fixed source set")
    require(all(overlay[name] == pin for name, pin in P7_F59_FROZEN_SOURCE_FIXED_AFTER.items()), "F59-frozen-source-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "F59-frozen-source-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "F59-frozen-source-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "F59-frozen-source-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "F59-frozen-source-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "F59-frozen-source-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "F59-frozen-source-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 940 and
        type(result["sources"]) is dict and len(result["sources"]) == 940 and
        set(result["inventory"]) == set(result["sources"]), "F59-frozen-source-v1 exact940 build inputs")
    return result

_f59_frozen_source_previous_review = _review
_f59_frozen_source_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_F59_FROZEN_SOURCE_PROFILE:
        return _f59_frozen_source_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned F59-frozen-source reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths", "nativeSelectors"})
    require(value["schema"] == P7_F59_FROZEN_SOURCE_REVIEW_SCHEMA, "Closed F59-frozen-source-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "F59-frozen-source reviewed HEAD")
    api.sha(value["statusSha256"])
    require(type(value["nativeSelectors"]) is list and value["nativeSelectors"] == list(P7_F59_FROZEN_SOURCE_NATIVE_SELECTORS), "F59-frozen-source exact8 Native eligibility selectors")
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_F59_FROZEN_SOURCE_OVERLAY_PATHS), "F59-frozen-source exact123 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit F59-frozen-source before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "F59-frozen-source before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(set(P7_F59_FROZEN_SOURCE_FIXED_AFTER) == set(P7_F59_FROZEN_SOURCE_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "F59-frozen-source exact122 fixed source set")
    require(all(overlay[name] == pin for name, pin in P7_F59_FROZEN_SOURCE_FIXED_AFTER.items()), "F59-frozen-source fixed122 after pins")
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay

def _source_guard(api, repo, overlay, profile):
    if profile == P7_F59_FROZEN_SOURCE_PROFILE:
        return p7_f59_frozen_source_guard(api, repo, overlay)
    return _f59_frozen_source_previous_source_guard(api, repo, overlay, profile)


# Independent future F59-family source purpose. Source-only preparation.
# Previous purposes remain frozen; final capture self pin belongs to Root review.
P7_F59_FAMILY_SOURCE_PROFILE = "p7-f59-family-source-v1"
P7_F59_FAMILY_SOURCE_REVIEW_SCHEMA = "p7-f59-family-source-build-review-v1"
P7_F59_FAMILY_SOURCE_OVERLAY_PATHS = ('apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java',
 'apps/android/localization/native-copy.json',
 'apps/android/src/androidTest/AndroidManifest.xml',
 'apps/android/src/androidTest/assets/localization-catalog.json',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java',
 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java',
 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkQueueReceiptTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileDurabilityTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileFamilySnapshotTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileSnapshotFixture.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileSnapshotTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkProtocolPeer.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeLegacyReplyRetrySafetyTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt',
 'apps/android/src/debug/AndroidManifest.xml',
 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt',
 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt',
 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentHubApp.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentRegistry.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/CodexCommands.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkAgentClient.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkReceiptNormalization.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkSession.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeCodexCommands.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileDurableFiles.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileJournal.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileModel.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileStore.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFiles.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFilesPage.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationActivation.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileFamilySnapshot.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileGraph.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileSnapshot.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileSourceFiles.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationExistingPolicy.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeReplyRecovery.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSessionStorage.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSheets.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawGate.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawOwnership.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/ProductIcons.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/Sidebar.kt',
 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt',
 'apps/android/src/main/res/values-zh/product_strings.xml',
 'apps/android/src/main/res/values/product_strings.xml',
 'package.json',
 'packages/mobile-core/build.gradle.kts',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ChatManagementRules.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ComputerFileMigrationRules.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/DeviceReadingRules.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ProjectGitRules.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SubagentProjection.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/UsageRules.kt',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/WorkspaceArchiveRules.kt',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ChatManagementRulesTest.kt',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ComputerFileMigrationRulesTest.kt',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/EasyWorkChatManagementRulesTest.kt',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/UsageRulesTest.kt',
 'packages/protocol/fixtures/mobile/easywork-migration-crypto-v1.json',
 'packages/protocol/fixtures/mobile/easywork-push-envelope-v1.json',
 'packages/protocol/fixtures/mobile/easywork-receipt-v1.json',
 'packages/protocol/fixtures/mobile/task-sound-fixtures.manifest.json',
 'packages/protocol/fixtures/mobile/task-sound-fixtures.md',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-24.aiff',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-24.caf',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-32-big.caf',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-32.wav',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-big.caf',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.caf',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.wav',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float64-big.caf',
 'packages/protocol/fixtures/mobile/task-sound-synthetic.aiff',
 'packages/protocol/fixtures/mobile/task-sound-synthetic.caf',
 'packages/protocol/schemas/agent-management-v1.schema.json',
 'packages/protocol/schemas/computer-file-originals-v1.schema.json',
 'packages/protocol/schemas/task-notifications-v1.schema.json',
 'scripts/mobile/android-child-originals-pair.py',
 'scripts/mobile/android-existing-originals-pair.py',
 'scripts/mobile/android-main-preference.py',
 'scripts/mobile/android-main-saf-framework-import.py',
 'scripts/mobile/android-main-saf-framework.py',
 'scripts/mobile/android-main-saf.py',
 'scripts/mobile/android-nonempty-device-first.py',
 'scripts/mobile/android-nonempty-device-pair.py',
 'scripts/mobile/android-nonempty-foreground-pair.py',
 'scripts/mobile/android-nonempty-pristine-first.py',
 'scripts/mobile/android-sdk-capture.py',
 'scripts/mobile/android-voice-originals-controller.py',
 'scripts/mobile/android-voice-originals-pair.py',
 'scripts/mobile/android-voice-originals-workers.py',
 'scripts/mobile/computer-file-originals-contract.test.mjs')
P7_F59_FAMILY_SOURCE_FIXED_AFTER = {'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeRweProcessCapture.java': '240537ab210090a390eac8e480dd53d0f31d062460cd89ae7aa65536f92221e8',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '19c69743716f5a7c599566055ff65d4f3134695aecb4f189fa41d2e897e65efc',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '1006bf92d477c7a9fdea00edbd9a4a8e18a7b0b522b06e88a99961ed0e784c59',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': 'f013815ec371057ae1039cfa0ed6c5749dcb8a3d70f9b5455933935795e293da',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedP9SystemUiEvidence.java': '8f765491a4c08c9fabc1da4db44af98ae4d8065830864e9996d880294f3ca33d',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': 'a2a53d3070ed8fb2af0e59e5b7a9d4249e7e8db6ee9cc3a0d9d7ddafe265adbf',
 'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '635b54a3ae32e553160b56b2b365445502ff91d75d53fb28573d0a30e1b398bf',
 'apps/android/localization/native-copy.json': '6b2e051f1c59e6fa3e8f19bf67ef5a9abf91abbca771fb2c22ab5f4e2e774406',
 'apps/android/src/androidTest/AndroidManifest.xml': 'aa82a1a072275d72ee7e29c163895cc7b1a9af8852f08d0f5d2e89d87ba3fc6c',
 'apps/android/src/androidTest/assets/localization-catalog.json': '8c22c1553eb50f9a23b6aed494677e6d25d944207cae1375908c2245fac5fbad',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationControlV4.java': 'f3f4993066c9d9d393621bc101597c6f666196517863f68a5053e70e053a02c5',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocuments.java': '7feb3256beec8bad6fb9aea06e77d20b9a7f2c9f22462b32fd329b4a81993a0d',
 'apps/android/src/androidTest/java/com/agenthub/android/OwnedMainMigrationDocumentsV3.java': '1338f8742f3c5a29f5f5cb274b54e6b7e51c36c0800ed8ba7445764d691825cd',
 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafBoundedFd.java': '8ec239f0574225e12ce1c4b6e72b02757e5ac29b437c41113ae368964e7a5671',
 'apps/android/src/androidTest/java/com/agenthub/android/P7MainSafLeaseV3.java': '64aa8326a477c9f86085c234af2ebc2fe84ad04c681904a6f50be8e2edcacddb',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': 'a9232e59346ce707fe5aa1c92a7f28a738c2aaa5ffe7d754949f6bce6a7b0523',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkQueueReceiptTest.kt': 'eab49df4d093dbb2c1d482001143079364fd6a7bd52751422169d090baa08ca5',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '17b81dcdf27a30d823c0cf8c1a146b66c08db416098f2a2c484d7f20cb6d6064',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': '5e121b85a1d131f94a65f4cabadeb1f02a0690db657fa43e4f7aff5e616af817',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileDurabilityTest.kt': '63eb204bbd81f8de24ac1e5a742ab9da495003c87dc9dc9ae32e3cd6441e47c2',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileFamilySnapshotTest.kt': '5a822e55cedccf5748f0e3b2d7cba6843765049c22dadf8aa0ad18518c1d967d',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileSnapshotFixture.kt': '0b5219171f7879423efc54fde94ef931baefd0ece86c173d0a310d61c56e82f4',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileSnapshotTest.kt': 'c9780f2e0851ba7b982284a95b1b5f1eea0e5d9bf9aefdd5cd9012d869b0eac4',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkProtocolPeer.kt': '856351afc999eda16534d9733ca58b87eaf1b879287449e33a716a7813bdbbdc',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeLegacyReplyRetrySafetyTest.kt': '1ac749b91365d1042eba6f4627c978b27baf7e7dc40908713e01a87ef67d98cf',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationChildOriginalsProfile.kt': '77f8f77e847c719143e5a3ad9d8b5a6454afba8b6a2071060a5c520ab4a1432b',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExistingOriginalsWitness.kt': '6eef1747da82d27d4674196476026a298481ab6ba47b35761117f09b911bbb20',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainSaf.kt': 'aae53404a620a4f59903bc2940b38b0e987a883ee9590ee8c76375f546190cde',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMainUiTest.kt': '857a608e1b7a57772fd62e8d02286eb223539fbbf7e5639cd649531ae78b2737',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': '5600b74549464575dd554366f9cc7017baee4fb0a88f01a76325ee6e1f2e798e',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': 'a202d504df24e965bc61bab758f2ebe04870355cc3d3328a081daf4158e93c4e',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt': '08e679e87726a7c5cfe4f1a4d0ab92c21a7c14b777fbd1b49ba498d75241eab2',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceCase.kt': '5c5e38bef9254e0c3d059e8f45068e78b9bb6d41c116cded388cb88ad1fe9781',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsDeviceSessionTest.kt': 'f8aaffcfca94f4b94171cbfd2619346693ca35f130a25023c18a32678d3719eb',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceOriginalsPairContract.kt': '01b09b2d799b18040130c2170361e0c28ce7b060ea529b1264346f6026c41932',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainAdmission.kt': 'b0e1f2593e82fc97754ef62873bd070d8b23cdf9f0bed233e79044118e93b31e',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP8MainPreferenceTest.kt': '6f54e060bed637fc88601bb33ec59291364a0dc66a28111aae2e8fd58f69fb53',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeP9SystemUiScopedBootstrapTest.kt': 'f490b3161b0baf900ad6a54717721c59cbf7405f2786e4f0639b8cb0ef08916a',
 'apps/android/src/androidTest/kotlin/com/agenthub/android/P8MainScopeBoundary.kt': '90ca84c62835aa01defd18187ec046c6132b5eb88b856d016fd450b2d85f7c3c',
 'apps/android/src/debug/AndroidManifest.xml': '665d8491eacdfd1be56503c5d7312a829da4743a5a24d94033fb3efb2c878a6c',
 'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': 'ca2f8005efc4be53a0c213d59cc0e710dbe9cdb9e2793dabcad8fa4c327351ce',
 'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': '0403ead475d9f55dfabb40962d2cbb36af5d199db866587fc66e85456d7f40f2',
 'apps/android/src/debug/kotlin/com/agenthub/android/P8MainPreferenceActivity.kt': '1ca25c77ec44a16c32f454d95874358200783f31b84b2215df03bd6943cbfe14',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentHubApp.kt': '038a9481b030f582d510c351f8c2ce49966005d0efa1491369f5b2890c28b1fa',
 'apps/android/src/main/kotlin/com/agenthub/android/AgentRegistry.kt': '3ee5afc1f237f6ed7f27f35ae00ec499372e7777f1e48b1b91f48b8c41b065fa',
 'apps/android/src/main/kotlin/com/agenthub/android/CodexCommands.kt': '8334e01c9fee65e796c53f8bebda25c4a04f7d6806634d82d438defd9ecc0bc0',
 'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': 'b571940cbf7d795aef02260719a1df7436aaa1a2ee5d8d2063faa7caca0147fa',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkAgentClient.kt': '5222b416d5dc515cfe3e13e928581c01df44be761add15aa3619181e668b062c',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkReceiptNormalization.kt': 'bbd13988432924ed47ba8f4120e038a4addc34f5b568b61dd08fa160712a8d28',
 'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkSession.kt': 'bdefad1b3b16194da24ae1c66d63203a89ed0543e37160bba71755be6d4c711d',
 'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': '221ee4171b352154238a7c019c2d80afc0a215002a6048dbbfe472d23dcd43f2',
 'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '3aaebc40df41150bcf3c12f7f3ff88a014959f20a812d54384e3325e02cc055d',
 'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': 'ce4e3d3ed0b3e19437b60ebf598b5edfc45fd0582baef082c4640f52b2472d0d',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeCodexCommands.kt': 'b4ae491497033e179e1b4f2549146d9d496021062c595b05a49d2f2b9742244c',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileDurableFiles.kt': '603addff0f87553030cb7c63ac36dfeea26a817748876df490d57546c537bf46',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileJournal.kt': 'f4c15a2b593f322048b5c91ad38e97fa56b54ab9937990cb7b87397d78676646',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileModel.kt': '667dfdca68128bb294a367528c15d80dd535fe788e03750281fa55a762003e78',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileStore.kt': '734f5607fc8a2355ec317da9090e58081f90e0efa65ed4a63deb472ef805246b',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFiles.kt': 'd9519869a954a9c1bed93114f77c2cb2fff4e139676df7410afe1ff309d8d3dc',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFilesPage.kt': '52f778ac6c8878d0dfb74de0f1df15de444c553b552c91633a4673829080bf72',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationActivation.kt': '4049489bb89c3bca34ee32ac483cd36dc257b50ed3864e614b79ff58320dd81f',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileFamilySnapshot.kt': 'ddf88aded811f7912a1674d18604c8dca0791f1a3a87c2b408e10b5528ef0289',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileGraph.kt': 'c3a68e94f507d30f10f97b9c6ad9679fee52916cdfed806e28550256328dacd6',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileSnapshot.kt': 'ea8ab83cf06861d1967eee177e7a1e4f567abde64b829f22d68d7d3ed389a6e2',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileSourceFiles.kt': 'e7e0d876c4bc94697fcd95900663ae45ef556d19feb9d27c203b1283f3223d64',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationExistingPolicy.kt': '034c7add951508a49fb83f118767ac764350a89e2f39a6dba2cd3ad4450956f9',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeReplyRecovery.kt': '30414b9944b47038e665e257baf8f8a75afa6cc698e10aca9182a8f6a9b82834',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSessionStorage.kt': 'efd0c0b82c3ede528d62a2db80c3db654d3a8d00c1d54e0cf7cd90be4d9e592e',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeSheets.kt': '2204fdd39e3e2ea46dee82d1f04f420cdb5f5ffcbd9d047ece4b960f9892cc32',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePcmWriter.kt': 'abc482f97cba1342b39b767d4dd914af67d04b4c5b695cef451abb2d8ab035bf',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawGate.kt': '57a48abaa41481d1dc7c751089c0d32089b7ebe7ac66d44cd60006e9d46a705f',
 'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawOwnership.kt': '97146e786a153ddfc1ac0dc560f1fdd87aa0d03bcd7b24b8a659b1a1f93b3644',
 'apps/android/src/main/kotlin/com/agenthub/android/ProductIcons.kt': '5b0bd956f8f8eb6931c75c41229f1b91073d4cbac2ed9eac57ab869d42a451ae',
 'apps/android/src/main/kotlin/com/agenthub/android/Sidebar.kt': 'c56b8e28d4990254f9e68785aa3779e6c91de2012ea0e85f3fe155fa09171ee9',
 'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '38cb07a63db7dc69f539febda7d44e3229f173d6068a3424035d5fbc22afd8b4',
 'apps/android/src/main/res/values-zh/product_strings.xml': '02192274fcedd07e86926472145e844cbdfa60bbacd8f10b39f5deda1e9fa999',
 'apps/android/src/main/res/values/product_strings.xml': '46a4180fe74b8f0c583fcf5f33192018df48a076136c84e8da54f19658322543',
 'package.json': '9d6fa4d4b9366df9d4fbd08dfeb482e22646280c7d6917aee11d9a28cc7b89c1',
 'packages/mobile-core/build.gradle.kts': '78ad08a3339f34accb7b38bf10cfd670775c842b076cb4fce1939151e1d8c58b',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ChatManagementRules.kt': '81c6c44047c3bbf5f7fbc368af7302ce727e0ea0505af653a5f04eca8472ee33',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ComputerFileMigrationRules.kt': '41b8cc833e4adff9761fc712853334d529831121df12695f8a77b25622795741',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/DeviceReadingRules.kt': '94e3b49ea1ccd065fd058e63c5b1d66246fbdab774ee7bc5e1903d58b1de8883',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ProjectGitRules.kt': 'aafd704e667a2ab115e5ab132939c14e19c5676ebd4017054da8ca78d25eae71',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': 'efa63e223691a3f2f9364b99cbd3b7dcdf9eeb2099d863b156dad4f987cc5671',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SubagentProjection.kt': '5823a929fe2b4ab2d4b2b9f0f76b2276071b9ecf974ce47915c1e602dcff1937',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/UsageRules.kt': '0312f4f1e55dbc71e525596db1bc23c86286f1d9bd9ae13277f2387ec4d8fc69',
 'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/WorkspaceArchiveRules.kt': '22668fe24cd9843454b5363d77286f7bf93e7a1c7b4ac5c8c2987ede4d26b397',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ChatManagementRulesTest.kt': '4345c2613a399df13a3a83c3ba24a237d23ab48cff2d0946dabfe148c7432cba',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ComputerFileMigrationRulesTest.kt': '8868b40cf00ae096cb8ec7a2eb0cba55336d4bd667c5a00ed0005634c881c276',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/EasyWorkChatManagementRulesTest.kt': 'd58978a62ccaf2d09cd098c5404aeb24c16be4928cbb50dae62bcaafb517c57c',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '3c5b887f641e5e4b197c69b9f759dc883a5540d62decc3d56a18a64a78d7ef34',
 'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/UsageRulesTest.kt': '6e184bc72ff0965a81807ff312a42683f87d52e4f8e9e60f7f93ef630f6276cd',
 'packages/protocol/fixtures/mobile/easywork-migration-crypto-v1.json': '18b7490a1b4ee72dcfaafaf8b955e96814bd1b7b025458578a92b6647fdd3296',
 'packages/protocol/fixtures/mobile/easywork-push-envelope-v1.json': '2935d6c6354004549cba7bdefd8a346446dcd3473c1bc68343cd38a5801937dd',
 'packages/protocol/fixtures/mobile/easywork-receipt-v1.json': '38edb15d562bafc6be55ab4fdb7c5afa1eb47aa0f7378d82f204c25caeec84de',
 'packages/protocol/fixtures/mobile/task-sound-fixtures.manifest.json': 'e000a91870e89f01824c8fdd5b7093b6f6941ba3cc2d60b1534a538e29634352',
 'packages/protocol/fixtures/mobile/task-sound-fixtures.md': 'de128ab4a57e8b1ae7e6c7d23f22b4fe71df7cc381453788ccd9e6e65af02078',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-24.aiff': '948ae3435193740cff64743cd63d2547e6ec21406e98a5c48c3643c2d7604445',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-24.caf': '73f1661d0474e99e860771ecf653cf689017099050add697dec20b9686b206e5',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-32-big.caf': '2112d3026d7fd34e25fd8d4d23eb26bad2ce67202c68ee1575b6393f85778d1f',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-32.wav': '6a152280eaf1978b56caedd569f5c6325324e0d093065dff4befb2938450e417',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-big.caf': '6fe6a6c23b6f8445aa42919f7a6a7ed91cfc08eab361de379c9af6e86cb2a991',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.caf': '495188a63f4bb6a32f6641cb3c1b820824b6cc979c587a064ebe0c99e8f5204e',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float32.wav': '57ad32020e107c73075b15c896ad4b69614c2343cf7261cca65743c84af8d676',
 'packages/protocol/fixtures/mobile/task-sound-synthetic-float64-big.caf': 'fa0a83f1da441a227c0e3d95801931495074c2c6e634780f424ea91c69b5872d',
 'packages/protocol/fixtures/mobile/task-sound-synthetic.aiff': '6e892732a18240c371a496c88366919a94b4f8fb31afec870f1e63f31eb07600',
 'packages/protocol/fixtures/mobile/task-sound-synthetic.caf': '4b4818ba108d7b2f84b2c433c47bb0e01c71007127eec5439af6f286a0f49cdd',
 'packages/protocol/schemas/agent-management-v1.schema.json': '708cda4e1d5751aa9d01e19e034928bd7c313d325b69d9c8b720a2452a938553',
 'packages/protocol/schemas/computer-file-originals-v1.schema.json': '03d9727fd8c54be5e20505727f833967d54b8328fa9dbb96e7eff58fb8bf768a',
 'packages/protocol/schemas/task-notifications-v1.schema.json': '630ec397ab606c4acfdb31c4d85bfe342a1efa5bacb983044d5afe0db2552899',
 'scripts/mobile/android-child-originals-pair.py': '93d21c46d9641f6354e106721429570e1531253faa94b7cd155d4ebf0797bbb5',
 'scripts/mobile/android-existing-originals-pair.py': 'a2821b09d30435a6b3a6af8bd625818ebd68a879345ef0c11cf53d2d5bdfe18c',
 'scripts/mobile/android-main-preference.py': 'cc8a5714ba03b1da3b918e400627f7cf1c5a05f37a4f749a0bb4a87e1d8216b2',
 'scripts/mobile/android-main-saf-framework-import.py': 'eecf65ddd83cd9926b987db5ccf808e98e62541e2fb19ea87bfc4b1b5730dff9',
 'scripts/mobile/android-main-saf-framework.py': '3bf018c5277e2ab82d2ab0ab44e11de23efe2a7c6df1cbfbffa6a164eda5b328',
 'scripts/mobile/android-main-saf.py': '3fe7515b621e80831a8c7c0218dc6ec44d1d8dca01a996b33473836cff0c90c8',
 'scripts/mobile/android-nonempty-device-first.py': '47a2df7800252c46d3c466cba9df5bdf13817c7d3a67178cd73ac810e42dc775',
 'scripts/mobile/android-nonempty-device-pair.py': 'e0014e428525689426a2e849ff5dfc6edb719818bb2a42e831a90151de576ea3',
 'scripts/mobile/android-nonempty-foreground-pair.py': '951f81ebcb2d4d1998930c5edc8a29e35fc1a163fe13bf2c4fcb998d969ddbd4',
 'scripts/mobile/android-nonempty-pristine-first.py': '2a537c4b3270575177cd3e9661eb1383eaee688f42cdb65dcf2bd60cd6317e3a',
 'scripts/mobile/android-voice-originals-controller.py': '0895246bbd8336856da95ed1ac2376596cfa927262d40247fcf8ca70f029475d',
 'scripts/mobile/android-voice-originals-pair.py': '126d224437a1543fcc84c3358f9e40739c3bb905cd3d66265acf15b587c8eae9',
 'scripts/mobile/android-voice-originals-workers.py': 'ac8392bc03e76b0dc859cf79e97a1d808840c331b6fe5eeef05973d43e40f187',
 'scripts/mobile/computer-file-originals-contract.test.mjs': 'fee68f3f9165e4db0eaca2d6ec79e6e5b91c546ac9c2429bfbaaccb91db6c7cd'}
P7_F59_FAMILY_SOURCE_NATIVE_SELECTORS = ('com.agenthub.android.NativeComputerFileSnapshotTest#boundedDirectoryOverflowClosesItsActualStreamAndExpiredRealOwnerCannotEnumerate',
 'com.agenthub.android.NativeComputerFileSnapshotTest#replacedRecordIdentityIsRejectedWithoutReopeningOrChangingTheReplacement',
 'com.agenthub.android.NativeComputerFileSnapshotTest#absentNamespaceAndChildKeyCannotBeCreatedByAnExistingOnlySnapshot',
 'com.agenthub.android.NativeComputerFileSnapshotTest#unknownOriginalUsesRealCiphertextAndItsCallbacksRefuseAfterTheLeaseScopeReturns',
 'com.agenthub.android.NativeComputerFileSnapshotTest#controlledSidecarAndAuthenticatedOrphanRejectTheWholeSourceWithoutRepair',
 'com.agenthub.android.NativeComputerFileSnapshotTest#wrongInstalledKeyAndReplacedActualGenerationAreBothRejected',
 'com.agenthub.android.NativeComputerFileSnapshotTest#completedPartialRetirementRequiresTheRealReadonlyFileAndDirectoryBarriers',
 'com.agenthub.android.NativeComputerFileSnapshotTest#missingUnknownAndWrongTerminalReceiptCannotBeAcceptedAsRetiredOriginals',
 'com.agenthub.android.NativeComputerFileFamilySnapshotTest#originalAndTwoPublishedGenerationsKeepEverySameNamedOriginalAndReceiptSeparate',
 'com.agenthub.android.NativeComputerFileFamilySnapshotTest#orphanedOriginalInOldRootRefusesTheWholeFamilyWhileCurrentEmptyChildStaysAbsent',
 'com.agenthub.android.NativeComputerFileFamilySnapshotTest#peerFromNoRetainedGenerationRefusesBeforeAnyFamilyConsumerOrMissingKeyCreation',
 'com.agenthub.android.NativeComputerFileFamilySnapshotTest#replacingOldGenerationDirectoryWithSameNameInvalidatesTheHeldRealDirectory',
 'com.agenthub.android.NativeComputerFileFamilySnapshotTest#perMemberValidRecordsCannotMultiplyTheSingleFamilyJournalBudget',
 'com.agenthub.android.NativeComputerFileFamilySnapshotTest#consumerFailureReturnsAllFamilyFdsAndOldCallbacksExpireWithoutLosingTheOriginalOwner',
 'com.agenthub.android.NativeComputerFileFamilySnapshotTest#absentChildrenInEveryGenerationRemainAbsentAndLiveOwnerInvalidationRejectsOldAndNewReads')
PROFILES = {**PROFILES, P7_F59_FAMILY_SOURCE_PROFILE: {
    "tasks": [":android:assembleDebug", ":android:assembleDebugAndroidTest"], "artifacts": {
        "app": (".build/native/android/outputs/apk/debug/android-debug.apk", APP),
        "test": (".build/native/android/outputs/apk/androidTest/debug/android-debug-androidTest.apk", APP + ".test")}}}

def p7_f59_family_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_F59_FAMILY_SOURCE_OVERLAY_PATHS), "F59-family-source-v1 exact125 source union required")
    require(set(P7_F59_FAMILY_SOURCE_FIXED_AFTER) == set(P7_F59_FAMILY_SOURCE_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "F59-family-source-v1 exact124 fixed source set")
    require(all(overlay[name] == pin for name, pin in P7_F59_FAMILY_SOURCE_FIXED_AFTER.items()), "F59-family-source-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    require(overlay["scripts/mobile/android-sdk-capture.py"] == api.digest(Path(__file__), 32 * 1024 * 1024), "F59-family-source-v1 executing capture tool self pin differs")
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "F59-family-source-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "F59-family-source-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "F59-family-source-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "F59-family-source-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "F59-family-source-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "F59-family-source-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 942 and
        type(result["sources"]) is dict and len(result["sources"]) == 942 and
        set(result["inventory"]) == set(result["sources"]), "F59-family-source-v1 exact942 build inputs")
    return result

_f59_family_source_previous_review = _review
_f59_family_source_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_F59_FAMILY_SOURCE_PROFILE:
        return _f59_family_source_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned F59-family-source reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths", "nativeSelectors"})
    require(value["schema"] == P7_F59_FAMILY_SOURCE_REVIEW_SCHEMA, "Closed F59-family-source-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "F59-family-source reviewed HEAD")
    api.sha(value["statusSha256"])
    require(type(value["nativeSelectors"]) is list and value["nativeSelectors"] == list(P7_F59_FAMILY_SOURCE_NATIVE_SELECTORS), "F59-family-source exact15 Native eligibility selectors")
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_F59_FAMILY_SOURCE_OVERLAY_PATHS), "F59-family-source exact125 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit F59-family-source before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "F59-family-source before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(set(P7_F59_FAMILY_SOURCE_FIXED_AFTER) == set(P7_F59_FAMILY_SOURCE_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "F59-family-source exact124 fixed source set")
    require(all(overlay[name] == pin for name, pin in P7_F59_FAMILY_SOURCE_FIXED_AFTER.items()), "F59-family-source fixed124 after pins")
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay

def _source_guard(api, repo, overlay, profile):
    if profile == P7_F59_FAMILY_SOURCE_PROFILE:
        return p7_f59_family_source_guard(api, repo, overlay)
    return _f59_family_source_previous_source_guard(api, repo, overlay, profile)


# Separately closed F59 inner framing build, retaining the previous family
# inputs and adding only the five audited Android/KMP/fixture sources.
P7_F59_INNER_SOURCE_PROFILE = "p7-f59-inner-source-v1"
P7_F59_INNER_SOURCE_REVIEW_SCHEMA = "p7-f59-inner-source-build-review-v1"
P7_F59_INNER_SOURCE_FIXED_AFTER = {
    **P7_F59_FAMILY_SOURCE_FIXED_AFTER,
    "apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileInnerPayloadTest.kt": "b966f82843aef76e1754a3682124d0f3a2d788631fe1966d0c4640870e22d4ed",
    "apps/android/src/main/kotlin/com/agenthub/android/NativeComputerFileInnerPayload.kt": "003d076e0e89fba3ee9e5f38adcb5e79965bd8e30073bdf237dcd988bc8296fa",
    "package.json": "44092211eb62e90a04c61b56ccd8dafc2b6326151d006c5adc3aa134b0666376",
    "packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ComputerFileInnerPayloadRules.kt": "dc44fabb643edef87a3a73bc8279b870849299a32db4834cb4b70c51f7484da3",
    "packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ComputerFileInnerPayloadRulesTest.kt": "822799dd9ce61b18dc701554be52ee2fcb607c364e121b4425007f2a159f6f54",
    "packages/protocol/fixtures/mobile/computer-file-inner-payload-v1.json": "0bb56c32b77e0533180fa635f814ddf334bdb33e15078deeab8bf2fae68a131d",
    "pnpm-lock.yaml": "0f95a5393674dd868459d71c1c799b40642f7364abdfd19df8349a5bd89ea6a5",
}
P7_F59_INNER_SOURCE_OVERLAY_PATHS = tuple(sorted({
    *P7_F59_INNER_SOURCE_FIXED_AFTER, "scripts/mobile/android-sdk-capture.py",
}))
P7_F59_INNER_SOURCE_NATIVE_SELECTORS = (*P7_F59_FAMILY_SOURCE_NATIVE_SELECTORS,
    "com.agenthub.android.NativeComputerFileInnerPayloadTest#independentContractBytesRoundTripWithoutTreatingTheSyntheticRecordsAsACompleteGraph",
    "com.agenthub.android.NativeComputerFileInnerPayloadTest#unknownManifestTypesOrdinalsLengthsTruncationAndTrailingBytesNeverReturnSuccess",
    "com.agenthub.android.NativeComputerFileInnerPayloadTest#invalidDescriptorOrBudgetIsRejectedBeforeTheFirstDestinationByte",
    "com.agenthub.android.NativeComputerFileInnerPayloadTest#scopedStreamsExpireAndPartialConsumptionCannotBeConvertedIntoAReceipt",
    "com.agenthub.android.NativeComputerFileInnerPayloadTest#partialOutputFailureIsKeptEvenIfTheSourceCatchesItAndAttemptsAnotherWrite",
    "com.agenthub.android.NativeComputerFileInnerPayloadTest#partialInputFailureIsNotReadAgainOrHiddenByTheConsumer",
    "com.agenthub.android.NativeComputerFileInnerPayloadTest#firstPartialOutputErrorRemainsPrimaryWhenTheCallbackThenThrowsAnotherError",
    "com.agenthub.android.NativeComputerFileInnerPayloadTest#firstPartialInputErrorRemainsPrimaryWhenTheCallbackThenThrowsAnotherError",
)
PROFILES = {**PROFILES, P7_F59_INNER_SOURCE_PROFILE: PROFILES[P7_F59_FAMILY_SOURCE_PROFILE]}

def p7_f59_inner_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_F59_INNER_SOURCE_OVERLAY_PATHS), "F59-inner-source-v1 exact131 source union required")
    require(set(P7_F59_INNER_SOURCE_FIXED_AFTER) == set(P7_F59_INNER_SOURCE_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "F59-inner-source-v1 exact130 fixed source set")
    require(all(overlay[name] == pin for name, pin in P7_F59_INNER_SOURCE_FIXED_AFTER.items()), "F59-inner-source-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    require(overlay["scripts/mobile/android-sdk-capture.py"] == api.digest(Path(__file__), 32 * 1024 * 1024), "F59-inner-source-v1 executing capture tool self pin differs")
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "F59-inner-source-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "F59-inner-source-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "F59-inner-source-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "F59-inner-source-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "F59-inner-source-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "F59-inner-source-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 947 and
        type(result["sources"]) is dict and len(result["sources"]) == 947 and
        set(result["inventory"]) == set(result["sources"]), "F59-inner-source-v1 exact947 build inputs")
    return result

_f59_inner_source_previous_review = _review
_f59_inner_source_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_F59_INNER_SOURCE_PROFILE:
        return _f59_inner_source_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned F59-inner-source reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths", "nativeSelectors"})
    require(value["schema"] == P7_F59_INNER_SOURCE_REVIEW_SCHEMA, "Closed F59-inner-source-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "F59-inner-source reviewed HEAD")
    api.sha(value["statusSha256"])
    require(type(value["nativeSelectors"]) is list and value["nativeSelectors"] == list(P7_F59_INNER_SOURCE_NATIVE_SELECTORS), "F59-inner-source exact23 Native eligibility selectors")
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_F59_INNER_SOURCE_OVERLAY_PATHS), "F59-inner-source exact131 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit F59-inner-source before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "F59-inner-source before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(set(P7_F59_INNER_SOURCE_FIXED_AFTER) == set(P7_F59_INNER_SOURCE_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "F59-inner-source exact130 fixed source set")
    require(all(overlay[name] == pin for name, pin in P7_F59_INNER_SOURCE_FIXED_AFTER.items()), "F59-inner-source fixed130 after pins")
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay

def _source_guard(api, repo, overlay, profile):
    if profile == P7_F59_INNER_SOURCE_PROFILE:
        return p7_f59_inner_source_guard(api, repo, overlay)
    return _f59_inner_source_previous_source_guard(api, repo, overlay, profile)


# Separately closed F59 typed family projection build. Earlier source
# profiles retain their independent exact pins and selectors.
P7_F59_TYPED_SOURCE_PROFILE = "p7-f59-typed-source-v1"
P7_F59_TYPED_SOURCE_REVIEW_SCHEMA = "p7-f59-typed-source-build-review-v1"
P7_F59_TYPED_SOURCE_FIXED_AFTER = {
    **P7_F59_INNER_SOURCE_FIXED_AFTER,
    "apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileFamilySnapshotTest.kt": "85b9dc61d82de97c5650ec64bbea4fd57d7805e2f433ac5875e420ec4bed78d6",
    "apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComputerFileSnapshotFixture.kt": "90baf7416c9031a23a984e6446b7d8657d1b08af37eb9dd08c1abec4dfdef5da",
    "apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileFamilySnapshot.kt": "0f3f9dfe434c0205c50e4eec9f5b143bb778b3d468d2ac12eb5ad81ac7a8068c",
    "apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileGraph.kt": "cde1734ac6432b175cf31b944a83787b5619eea14e852ef03f39b7e17d3c4f0d",
    "apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationComputerFileSnapshot.kt": "7d3da80b5f200d535cf94198473d39213fdb0c687f1b6c7de4602ce0ab37fef2",
    "packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ComputerFileFamilyInventory.kt": "aecea2dd907d3cbf24363a04c8d9fbd1906f948ae7569b7e36f91340dcb4d178",
    "packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ComputerFileFamilyInventoryTest.kt": "85c14b568dd22161b6844da8481cec77b287e854bfe40122935260fb642a4b5b",
}
P7_F59_TYPED_SOURCE_OVERLAY_PATHS = tuple(sorted({
    *P7_F59_TYPED_SOURCE_FIXED_AFTER, "scripts/mobile/android-sdk-capture.py",
}))
P7_F59_TYPED_SOURCE_NATIVE_SELECTORS = (*P7_F59_INNER_SOURCE_NATIVE_SELECTORS,
    "com.agenthub.android.NativeComputerFileFamilySnapshotTest#typedInventoryRetainsAbsentEmptyLiveAndExactRetiredRootWithOriginalPins",
)
PROFILES = {**PROFILES, P7_F59_TYPED_SOURCE_PROFILE: PROFILES[P7_F59_INNER_SOURCE_PROFILE]}

def p7_f59_typed_source_guard(api, repo, overlay):
    require(type(overlay) is dict and set(overlay) == set(P7_F59_TYPED_SOURCE_OVERLAY_PATHS), "F59-typed-source-v1 exact133 source union required")
    require(set(P7_F59_TYPED_SOURCE_FIXED_AFTER) == set(P7_F59_TYPED_SOURCE_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "F59-typed-source-v1 exact132 fixed source set")
    require(all(overlay[name] == pin for name, pin in P7_F59_TYPED_SOURCE_FIXED_AFTER.items()), "F59-typed-source-v1 fixed after pins differ")
    for value in overlay.values():
        api.sha(value)
    require(overlay["scripts/mobile/android-sdk-capture.py"] == api.digest(Path(__file__), 32 * 1024 * 1024), "F59-typed-source-v1 executing capture tool self pin differs")
    names = set(overlay)
    repo = Path(repo).resolve()
    before = api.input_inventory(repo, names)
    actual = {}
    for name in before:
        path = repo / name
        prior = path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink == 1, "F59-typed-source-v1 single-link source input required")
        actual[name] = api.digest(path, 32 * 1024 * 1024)
        after = path.lstat()
        require(after.st_nlink == 1 and api.file_identity(prior) == api.file_identity(after), "F59-typed-source-v1 source alias/identity changed during hashing")
    require(sum((repo / name).lstat().st_size for name in before) <= 64 * 1024 * 1024, "F59-typed-source-v1 build source byte quota")
    require(all(actual[name] == value for name, value in api.BASELINE.items() if name not in names), "F59-typed-source-v1 unapproved baseline production/build input differs")
    require(all(actual[name] == value for name, value in overlay.items()), "F59-typed-source-v1 reviewed overlay bytes differ")
    require(api.input_inventory(repo, names) == before, "F59-typed-source-v1 build inventory changed while hashing")
    result = {"schema": api.GUARD_SCHEMA, "baselineCommit": api.BASELINE_COMMIT,
        "roots": api.INPUT_ROOTS, "rootBuildNames": api.ROOT_BUILD_NAMES,
        "inventory": before, "sourceOverlay": {name: actual[name] for name in sorted(names)}, "sources": actual}
    api.exact(result, {"schema", "baselineCommit", "roots", "rootBuildNames", "inventory", "sourceOverlay", "sources"})
    require(type(result["inventory"]) is list and len(result["inventory"]) == 949 and
        type(result["sources"]) is dict and len(result["sources"]) == 949 and
        set(result["inventory"]) == set(result["sources"]), "F59-typed-source-v1 exact949 build inputs")
    return result

_f59_typed_source_previous_review = _review
_f59_typed_source_previous_source_guard = _source_guard

def _review(api, path, expected_sha, profile):
    if profile != P7_F59_TYPED_SOURCE_PROFILE:
        return _f59_typed_source_previous_review(api, path, expected_sha, profile)
    raw = private_bytes(path, 131072)
    require(sha(raw) == expected_sha, "Externally Root-pinned F59-typed-source reviewed state")
    value = parse_json(raw)
    api.exact(value, {"schema", "reviewId", "head", "statusSha256", "sourceOverlay", "allowedDirtyPaths", "nativeSelectors"})
    require(value["schema"] == P7_F59_TYPED_SOURCE_REVIEW_SCHEMA, "Closed F59-typed-source-v1 reviewed-state schema")
    api.canonical_uuid(value["reviewId"])
    require(type(value["head"]) is str and re.fullmatch(r"[a-f0-9]{40}", value["head"]), "F59-typed-source reviewed HEAD")
    api.sha(value["statusSha256"])
    require(type(value["nativeSelectors"]) is list and value["nativeSelectors"] == list(P7_F59_TYPED_SOURCE_NATIVE_SELECTORS), "F59-typed-source exact24 Native eligibility selectors")
    rows = value["sourceOverlay"]
    require(type(rows) is dict and set(rows) == set(P7_F59_TYPED_SOURCE_OVERLAY_PATHS), "F59-typed-source exact133 reviewed source set")
    overlay = {}
    for name, pins in rows.items():
        require(type(pins) is dict and set(pins) == {"beforeSha256", "afterSha256"}, "Explicit F59-typed-source before/after pins")
        require(pins["beforeSha256"] == api.BASELINE.get(name), "F59-typed-source before pin must match frozen baseline")
        if pins["beforeSha256"] is not None: api.sha(pins["beforeSha256"])
        api.sha(pins["afterSha256"]); overlay[name] = pins["afterSha256"]
    require(set(P7_F59_TYPED_SOURCE_FIXED_AFTER) == set(P7_F59_TYPED_SOURCE_OVERLAY_PATHS) - {"scripts/mobile/android-sdk-capture.py"}, "F59-typed-source exact132 fixed source set")
    require(all(overlay[name] == pin for name, pin in P7_F59_TYPED_SOURCE_FIXED_AFTER.items()), "F59-typed-source fixed132 after pins")
    dirty = value["allowedDirtyPaths"]
    require(type(dirty) is list and dirty == sorted(set(dirty)) and all(type(name) is str and not name.startswith("/") and ".." not in name.split("/") for name in dirty), "Exact reviewed dirty paths")
    return raw, value, overlay

def _source_guard(api, repo, overlay, profile):
    if profile == P7_F59_TYPED_SOURCE_PROFILE:
        return p7_f59_typed_source_guard(api, repo, overlay)
    return _f59_typed_source_previous_source_guard(api, repo, overlay, profile)


if __name__ == "__main__":
    main()
