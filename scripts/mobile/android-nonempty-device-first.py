#!/usr/bin/env python3
"""Closed paired first/read-only-cold admission, single-attempt execution and evidence verification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import uuid

SCHEMA = "p7-nonempty-device-session-v2"
GUARD_SCHEMA = "p7-nonempty-device-source-guard-v2"
ADMISSION_SCHEMA = "p7-nonempty-device-first-admission-v1"
PACKAGE = "com.easyai.agenthub.android.acceptance"
METHOD = "nonemptyDeviceProducersRestoreActualSession"
SELECTOR = "com.agenthub.android.NativeMigrationNonemptyDeviceSessionTest#" + METHOD
CASE = "apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt"
WRAPPER = "apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt"
TOOL = "scripts/mobile/android-nonempty-device-first.py"
CONTRACT = "apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt"
PAIR_TOOL = "scripts/mobile/android-nonempty-device-pair.py"
COLD_METHOD = "nonemptyReadOnlyColdAfterExactOriginalExit"
COLD_SELECTOR = "com.agenthub.android.NativeMigrationNonemptyDeviceSessionTest#" + COLD_METHOD
COLD_SCHEMA = "p7-nonempty-readonly-cold-v1"
EXIT_SCHEMA = "p7-nonempty-original-exit-v1"
TEST_PACKAGE = PACKAGE + ".test"
RUNNER = TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"
PRODUCTION = "apps/android/src/main/kotlin/com/agenthub/android/"
TEST = "apps/android/src/androidTest/kotlin/com/agenthub/android/"
# Pinned source bytes, not a claim that the current branch still has this HEAD.
BASELINE_COMMIT = "f4d389f119bcae69cdcb2793ecf5afdb4b5a8338"
# Actual closed build input inventory, including debug overlays and generator schemas.
INPUT_ROOTS = ['apps/android', 'apps/android-inbox-examiner', 'packages/mobile-core', 'packages/protocol/fixtures/mobile', 'packages/protocol/schemas', 'gradle', 'buildSrc', 'build-logic']
ROOT_BUILD_NAMES = ['build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'settings.gradle.kts']
EXTRA_INPUTS = ['scripts/mobile/gradle.mjs', 'scripts/mobile/android-nonempty-device-first.py', 'package.json', 'pnpm-lock.yaml']
ROOT_PRESENCE = {'apps/android': True, 'apps/android-inbox-examiner': True, 'packages/mobile-core': True, 'packages/protocol/fixtures/mobile': True, 'packages/protocol/schemas': True, 'gradle': True, 'buildSrc': False, 'build-logic': False}
DYNAMIC_SOURCES = ['apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt', 'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyPairContract.kt', 'scripts/mobile/android-nonempty-device-first.py', 'scripts/mobile/android-nonempty-device-pair.py', 'scripts/mobile/android-sdk-capture.py']
GUARD_CAP = 512 * 1024
BASELINE = {
    'apps/android-inbox-examiner/build.gradle.kts': '6c89de1ad6f907e327809118d01f75633c84ead519d618a20f65ae7e7d40b00a',
    'apps/android-inbox-examiner/src/main/AndroidManifest.xml': 'a8b0b0957582cd1c30b1111cfa54d0e5a81286ccd6a7a5d00066febf4a9bc918',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativeFixedProcessCapture.java': 'ce27d9228cf07d7c0d3fdb4e69fda11e2aa31cf75b9c09e5e246e27e31832cf4',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NativePublicProcessCapture.java': 'ecf5b707c6195a00ddb947e26ccb259dfaa803b36ed6f6661ebbf0cb7b198fe8',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/NaturalProcessVisibility.java': 'e2349c7850ba9dfca3229dec48f1f0e61cdf51caef28dcdbff5a0b80deb0aaa6',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedCameraColdDriver.java': '25f82e9e7f84e4cea24157332f02506f9a89cff140938852815496d3880eb34d',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedDocumentColdDriver.java': '2d0aa02dfe2bb3d7fdab00f3dfb557476a0b42cf82f0fb75fb0d683678980ed3',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedExternalInboxColdDriver.java': '68ec213a1cdbe89c5e7bfd31db541f384c3e9faba6c7384c48f8af2b10b7c22b',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedNaturalNoticeObserver.java': 'f4e555c2a35e7a87632c5ad98f7ccba6dc796052e3391fd7910d10746163aa8e',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPhotoColdDriver.java': '355d7dbd7a1f1000da63f0b42c4d5d9dbe6e3a04299c4edfe818bdd29d30299e',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessDiagnosticObserver.java': '0bc8ff0871ae8f36140d84281802938de418fcf545214f58099ede8b2845c7fa',
    'apps/android-inbox-examiner/src/main/java/com/agenthub/android/OwnedPublicProcessNaturalObserver.java': '6124f770385b0af2dc367face15f1494d1b78ed215509e33c6d762fd83df0651',
    'apps/android/README.md': '90d85b19500e354f25f0aa94e3f9cec76d02cd165383a6ca00bd180550132629',
    'apps/android/build.gradle.kts': 'f92f82d4d899b1d86d7e4ba86b365710096abb65306bd5bfdc6065af3f2aad11',
    'apps/android/localization/native-copy.json': '1d8c6b793b3b9e59ac0b2b3728c99269cfd4ecdc0121a6ca9be3a67b4b8bbdbb',
    'apps/android/src/androidTest/AndroidManifest.xml': '5bb2bb5411272cc79828ab2bc2c2d2c9e493c9b1646ab53285fd5dad433dab2c',
    'apps/android/src/androidTest/assets/chat-notification-sounds/synthetic-tone.m4a': '231cfa219204bfa34a3768c092c5e6b19c73eb48af3405e933da535eb694e850',
    'apps/android/src/androidTest/assets/chat-notification-sounds/synthetic-tone.mp3': 'db476aba2e89eff735bbf4434d915b8ce22ec501d83ea4c6c307cc66db4d207a',
    'apps/android/src/androidTest/assets/dense-qr.txt': 'a7af894ed8bb547e50198d95a04d134c5c2ae3137b83feea45f2e4808ac7fdfe',
    'apps/android/src/androidTest/assets/easywork-canonical-keys-v1.json': '7e4674776d94bb44a10ff52c540afa1ab83d40fc9fdcfc432a438e8ba3ed14dd',
    'apps/android/src/androidTest/assets/easywork-crypto-vector.json': 'b511e868a6c893552ba9bc554f1ff57dfb052524420d181dcbc6454dff288104',
    'apps/android/src/androidTest/assets/localization-catalog.json': '85733ca58c3fb745377fc07056184918cd1fe54064252fc646477d6eeaddfc97',
    'apps/android/src/androidTest/assets/p8-pairing-copy-additions.json': '573789483582ce36a43e68e9a15715f35fa7598a008c5e4b01c0bb6072a34cdc',
    'apps/android/src/androidTest/java/com/agenthub/android/NativeSoundForeignWitnessProvider.java': '60161dc8dfef7bc51df8794db3a4e60a0beecdc94ec4d7b0efafa762fc0a737d',
    'apps/android/src/androidTest/java/com/agenthub/android/OwnedAcceptanceDocuments.java': 'e9185d043e3424e6778fabc1da8eae40dccfb973acd1f128b82c68ab5f970780',
    'apps/android/src/androidTest/java/com/agenthub/android/OwnedExternalSourceActivity.java': '03cd2be60d543af493aad086f546f03733196efb9a2fecac50bf0981ee75a656',
    'apps/android/src/androidTest/java/com/agenthub/android/OwnedPhotoMediaActivity.java': '8efa49210ce085e5390887d93c420d2f73f7ff0e40f0fd029e2c9902511c7a02',
    'apps/android/src/androidTest/java/com/agenthub/android/P8WindowPixelCopyDestination.java': '91debf901259874681ecdf59bb083ff651ac4f7c4bd712d0fc2dd43f80e254fa',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/ConversationUsefulProgressTest.kt': '50c9d8c5fd4b0a6681889d8044d11cdabbfd2be7abbc69728097ef7459ac299c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/EasyWorkLiveScenarios.kt': '21034c48ca42fcd6de0911ea226ac7a4cccdc0839a2b5f788752297303b16c91',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/EasyWorkPresentationFixture.kt': 'f5b1d3f915275c475e08960c1003af574cb110a87d8412d539389607a7b9bce7',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileActionCopyTest.kt': 'c543dd8f305503a46c654b4f2ad4f8d2e08e168b62baeff845eb5e3201fce03e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileApplicationSessionLiveTest.kt': 'ad2a72c3b5009dc0818bd246a4f52f92bfebc9737b53afefb88a3713035133b9',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileApprovalHistoryAnchorTest.kt': 'bdf55d10ae11327d391fe3baf5386bb673d6bb1d10a7df8057cf72017379b756',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileApprovalModelLiveTest.kt': '45286179a39189d18ff5f7191ba4273f4a6d89e8982300d384fa23a1062fc1c8',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileApprovalOwnershipTest.kt': 'c5ae0cbcd208769912b991af81b8e1f8ad9ededd02e967b8da32242d0a51f1ec',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileApprovalTitleTest.kt': 'e9196a820662ca07052b086954a752cd51fd5db5515abc1be0c76a7c82ac302c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileApprovalUiTest.kt': '10be43e9cb0255bd3cdc32a30c59afb5bcdcd264b220a03410235ff2bf78e5fb',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileApprovalViewportTest.kt': 'aeaf5376847479668f782d31d0e686ebd178e1228a2c3907ef0f87c40481a6dc',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileArchivedChatsUiTest.kt': '726948c247b77638d3ce9937af8d51d6e2544e78380fb21196a51d23dddc8993',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileAttachmentImageViewerTest.kt': '391cabbc422c1ccef789abcb0eedfc6d0dcb0bbebf1c42eb0f211b87dc51068d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileAttachmentImportOwnershipTest.kt': '46a41aecd2428b273b9d57afe6cdbe95adcdc2eecac1c9552d9edb60e0c05b36',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileAttachmentLiveTest.kt': '86f9b06191c4a5dcdb208caa9f5d5bbecf43003a57ee99d7945b955ee02b1127',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileAttachmentPanelTest.kt': '161ea01b386ad6a676464368140edd12501f9fd6db888f51db78e174501d2282',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCameraCaptureTest.kt': '4be57151c35c9f19ec74f9ba19376dc0e7268c26cd5b9c4971e3413286f70fff',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCameraProcessColdTest.kt': 'b600afaf3ab334c8e7d63ed1be33d6bb960a54996e1f9b7a07109367f583b4fe',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCameraRecoveryTest.kt': 'aa14b357f9c63abef4b5a32dd7c791735cd662791a6b812eb8af5ab998d8898c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCameraSystemTest.kt': 'abfc5728a754f85e7db8297ecea509e43801e05dae574bb4e72ad96d139b7c21',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileChatActionFlowTest.kt': '83971fa472b6fea7ae99518dd3b761a3991e62f6605dae86a09d8ba9ea935404',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileChatActionSessionTest.kt': '1eeb8be3ca9384cd3724aa43873cef7d04665aa2dd7dc0e1e9b02ddb070dc159',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileChatActionUiTest.kt': 'dd2a54011a529333029cec0ee98ed00c33ab917e9cd4ddd4a1d76b900ceb507d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileChatCopyTest.kt': 'f9ebc70a620180b8a09827d89033ec5f4d2aa63800a566db24db68bd3c89c2de',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileChatSearchRulesTest.kt': 'aacd6bc47ada542777fce24048e1fa387ffd1466ff423f368ab3a2126be33295',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileChatSearchSessionTest.kt': '624edd6dbd978f639ff855614a61f74b8631dfdf86128a196145a464f997f131',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileChatSearchUiTest.kt': '09c4b9fbbfc4a6f9008baf6f9fcb21923e39c4ca27ba3f6b9f6d623acaf209ed',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileClientCountTest.kt': 'b4241552a6370e9cb80029ce6accf8606847b95c90b5df7bd597c49cf4fd1e45',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCodeCopyUiTest.kt': 'f246e3aa1fafa426e3bb9a6cd266f27b50c1fe96375b613ba67e5a550620f68f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCodeModelLiveTest.kt': '8fe3a271c8fa88a4a7f9fbfac4d2abb72a1c701fa7ad137c5d2dcd2b6bba2328',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCodeReadOnlyLiveTest.kt': 'adc7937588b90fda23cfe1e308fa2f6513e39a78387c61ea23ded483b158e332',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCommandBoundaryTest.kt': '9ba93e6caefdc59330fb4e3d2340946c63e1e315d40b9e937a9a09d6f909de98',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileConnectionBudgetTest.kt': '9cf04a99da10561b67090628fab5a5f2ff581e1e1cf613bca0da2f97116868ea',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileConnectionFailureTest.kt': 'aab86794ca14e5c2d9b1409b30322cda9023aa763b71e7e94d27c3ce14b954c1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileConsentRegression.kt': 'a4758935f0cae35206faf4cc5c7910b0d5b769ebc27e4fafd291a7ef623e41ae',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileControlCallbackTest.kt': '834d8af117c1c435dff3415e5611ccca6e02c0a591583d2b0277acafa834ae65',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileControlSettingsTest.kt': 'a56d5f9bbd45a65b5bbf504852bb20a22839fdce8bd3c0513edb023970f653c2',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileConversationSyncTest.kt': '43e3f1d9d0094899c0741186106feca9bb88513602b44458dcef8996c5cdad8f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationCallbackDraftTest.kt': 'd3af2a85aebab231f69185800279627955411da5025bff2ba7a8fbceb12209d9',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationDetachFailureTest.kt': '5dd57331a5874e02434f3622a668a1f66c729847ca55af8d3947b71f01b1f25e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationDetachModelLiveTest.kt': '2fa1ed6f0278ba11b786494906626b6ee3aaea02079bd668fcad88b69139250e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationIsolationModelLiveTest.kt': '5bfe8c12871b89ddbf969d907b99acb01fad134bacd7238ac0d3785be3fe5a40',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationIsolationTest.kt': '7d6b8009257655ea3e62a3f0009574a68d5a0d049c539455a3f4ce8387a616b3',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationQueryModelLiveTest.kt': 'ff5ff7747a9245aa5292c493ad7ec89829156a99e4b5d58ed4b7675df44329e3',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationQueryScopeTest.kt': 'c68e399ed017dff130b0fce52b8568e7f2b5c58b920f46948289702d481f2fb8',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationReceiptOrderTest.kt': 'cb8e3e8d05afddca418b783f01ff3c91cfb000750ed5a92d39ad03f2706a112a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationRecoveryConsentTest.kt': 'bd112468a3c4bae942ada30d158880c702d83b0344d99cb673649b27b83dcb00',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationRecoveryModelLiveTest.kt': 'aa598ad575fb856eb3acdfa5548b7c95aef3372e4c32bb627de5891ac8a9b275',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationRestartModelLiveTest.kt': '2b21a2e26d237adb8cbec6912522bfb3ec2d7f11aa2b4879c67fcca87b3155b8',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCreationWaitConfirmationTest.kt': 'ab580d1fa0b7c44cb17ef51b615797917e536a637c901924e923b08ef17ebbbf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileCurrentGenerationDisplayTest.kt': '39b3f25a9698c0a1df8714671b5f2dc7ff79b4eeafb77d3f7fd9bd80063dc700',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileDiscoveryReadTest.kt': '410983bf343a5146430aa260e76017cf67d5c2e6863a067c24e3a8db0ee00995',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileDiscoveryTest.kt': 'f0dac5cd3acfa388a755c470eaccaea24f0a248d1036a1e5d78ede274c2f4bc7',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileDocumentProcessColdTest.kt': 'c4ae6ff09e3e4ecd54751946cf7768f17a4973e7c1a08404339b3d96bd29fc34',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileDocumentRecoveryTest.kt': 'b5984fc5dbf22467ac35f4077f2785f853cb766eb6f80bd837ef23e65b018b4e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileDraftAttachmentUiTest.kt': 'df261b9e9a6b61af1c4a0c4e764371d00adbd2f85be3bfc9c77bac85ca839e03',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEarlierLoadingUiTest.kt': '29082994f382b8c0bd24a7a00ed702ce0b8b172d7bed98ae81cf43534ecc8e89',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkBoundaryTest.kt': 'b21c4402b4917fa769f85cdc237e0d196022f6f7563df35f0c408c34e58a95cc',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkDPoPTest.kt': 'dfa9519b618768153cf32188775b9981ca5b83f84a4dd1d3e62012627ada1b8f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkLiveTest.kt': '3bc58711b807d9a156162bc0b0f064ada8bc78dba0412b4ea2180c97b870c5e6',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkPairingUiTest.kt': 'e97ff2712554cfb182749f47ebc90baa9501f030018a4193dfa0d621859c1948',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkQueueReceiptTest.kt': '70cf890d85606f15d212837dbe7484f0e2444b227b515832c443c97a9423716e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkRestoreLiveTest.kt': 'bd2ce634a3acbcead4c227566cfa066a4186b1509400c596b6f2266b8c9fe4bc',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileErrorDialogInteractionTest.kt': '60801c2a1651a79bc7e33092057818882fe4022c6b860a81b0c04f168ec504b7',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEventFailureTest.kt': '68935a88da764f7d27708a76ca117702282892976f34dd6f61f3dde3c0054c52',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileExecutionCopyTest.kt': '3b86416d0787e8af31ae13c8f194044cf5d0e66bb79d885ff241343e2ea111c9',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileExecutionTest.kt': '799c7401b416de3f18a0ea8a325a40d57cfb059f5cc1c5671c97a0590096ef0a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileExternalReceiverTest.kt': 'f35963af9e446675c61728b81ebbccf158f22bea8aa5bdb1a6c836cf02103ec5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileFailureClassificationTest.kt': 'e88df6f66ec8e6f76c5d5f476f0a4de68fdebdd1f2b1ed7c0da3fc514ca51b72',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileFilePasteTest.kt': '9d7f46225c9fbf8997775bb05d6b88a0c782870a746e1236a9184c1362bfc7cc',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileFilePreviewUiTest.kt': 'b76513fd1a6c08c840264b6521eeb2e80a4dd4c23ac697072350d65a664480d5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileFoundationTest.kt': '2de623a43b04f4ea35f34fdeb6f6d82fc7a97ff3d23a864c047c2b7fff4f81db',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileGenerationCallbackTest.kt': '134126195123f5fc018d359b2a7f9b2e06f4c95c44b3669057c5589ad8bf2f46',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileHistoricalReceiptTest.kt': '078bd9ebb9cad4256a1d43c0589934323458111fddedb9a829d2b6935d93c407',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileHistoryBoundaryTest.kt': 'dea261070a97054c8a2188d42f0bf89814abe656a31ffb6d9335d5d514aaf241',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileHistoryPresentationTest.kt': '0194009e14d8e7203ced96ad11b062db060ec4fdfa5cf7f5cf8981e7c42fbb9b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileIdlePerformanceTest.kt': '29925b89e22ffb5dc593ed602c8d0779eab4fe0367dbd9eff1a168e8a0cabf28',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileInboxColdProcessTest.kt': '476d739280718c49ed7d0ba8ba73f351d69b0cb9c0d565a8895b224cf8a6e3cf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileInboxStorageTest.kt': '003328e0c52663a15bf5695757857f997da27d52739ef7b4e7feb3ee5bfb874f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileInboxUiTest.kt': '554b854c2e738f5ed90a3194ceeed4d2284f5836572026a94ceb222cd8f7ffe8',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileInitialReadingWindowTest.kt': 'f3b4c13cefbc51c0fc7538b4ab401465a7b8982c6ad38cc590c523e09051cada',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLargeOwnerReadLiveTest.kt': '8cc2e17b761c3f2ae16c8e15be577ed2f290429fb06dad495b60ebc376323588',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLiveCodexTest.kt': '561a41a8b5ec773f127b9620761c40c098db97685416572cf1ecdf3c169879c1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLiveControlsTest.kt': '92b42cba7883db6d2463955bff9b3804d26a0e4f036fb967fbfa131e573baadc',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLiveHistoryIdentityTest.kt': '813da4ce72f03fcc9010e4262eb73c656abce4e719cae0910b832b5b988d9684',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLiveHistoryTest.kt': '4368283cb07a0a00102da1a77fb12725fadc6990153e29ca2d15794e0611cda4',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLivePlanApprovalTest.kt': '1e70fedbcc97117f0d5ca43bcd3425528b01046d340ee64d7959ef8eef41ac09',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLiveQuestionsTest.kt': '6f04c1ec3704ce6308defea4b16a8e9d6ba5ea1f428703a40717ee2c5d60e665',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLiveRuntimeFailureTest.kt': '994b826fe6789e3d545820efffcdfa77755ab60aeb0d3ba1cfe13f7bf3a3102b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLocalRecoveryAttachmentTest.kt': 'aeb0dbaeb3f6e74b1fd61fde24b946c8e624c3da9e3be1caf1146e307af89cce',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLocalRecoveryTest.kt': '1117036da1672a2a03a347f574169c0c6697f0665897ff5519aca202118da7c0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLocalRecoveryUiTest.kt': '1aee90f5eb5ddc6f731cfdf0626725faddb635008fcc713eff998ef1eee9c42c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLocalizationTest.kt': '326abe388a68250930456b9a12a4e7f9a008d603e9115f39b94fe686be1a5530',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLocalizedContentTest.kt': '61e9e66e8a776bdfa35458f631f1ffe1c29c84b25fdf9ed3bca8180df7b96f86',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileLocalizedControlsTest.kt': '0267b77f06f5ce33765650a174fa4aced02c63124be7839506866c3a95026f39',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileMachineIdentityTest.kt': 'a1f626ba324bfb50c77f5b2364c0605ecdd54e762a9457cef133e1f66070224d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileMachineIdentityUiTest.kt': 'ca9a73d82fd72111209345cc5b7676454dd911b7ad5bc7c855c72f244e1cdd1c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileMainActivityReadLiveTest.kt': '20646ed39db75aae84c8fd1b3679ecd4708387f4443abed1be1d1bd0cc9aa7f8',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileMainActivityRouteLiveTest.kt': 'c320c0b190ebdf4c8c47a394a15af253fc95d14f1bdb9eba33d8f6c92c8acb02',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileMarkdownFileLiveTest.kt': 'cb94e3231f678b29f3110d412c44ae4c9db07b899adc0b10c4394a882277b27b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileMessageActionOverlapTest.kt': '82684ab9b77476d3e827cb18eae3189a0109ae17afcdc896cde54d4d4ea76b1b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileMessageLinkTest.kt': '756ee873ae5a757df1c6f1a8f03a9722ff46bd031f4b1bd4b2ae88fdd8aaec22',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileNativeTransportFailureTest.kt': 'a87b5b34051ad6a71f7c61c4ea3c37edfdb0fd6977463783660c8ece57b5cfaf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileNavigationCopyTest.kt': 'c028927b9994959247150720bb10a23a5cab259bf6922ffb8a587dbeb65dd535',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileNavigationPersistenceTest.kt': 'bdd8ff8e4fb8334dc033cedba337e8a0c5ed5fe3490847362481329b560ecaa2',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileNetworkReadRecoveryTest.kt': '433f309d640ddd9971e4e729f460ab076a9c99267bf1a40a3ed6d8908f4982a6',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileOpenObservationTest.kt': '4985bc6223239c620dd42ab89a10a5d654ee78863a2b71c7251d553affe82560',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileOperationRecoveryModelLiveTest.kt': '4b9422e29036e951ee3a09aa6e209dbb748e17d2a8baab62a6565b833af08539',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileOrdinaryRepositoryAuditTest.kt': '64e28c4c3a0b44947474d5c59192325ca1d8c8f85bd764d1d8543013891decd6',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileOriginalReceiptReadLiveTest.kt': '7a7e1b691f85afe5535475cc24c3271a60a5d5d27bb03fcad541cd89c913bbf4',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePairedTlsReadLiveTest.kt': '9515c9dc6823092e02405a2cd8277d33489b9d658924628fb804996f58621a61',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePendingAttachmentModelLiveTest.kt': '3de6ffd078a0272105a0dc92bb88f214914789aab0cb3da1099d9a53e4b24b25',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePendingCreationAttachmentTest.kt': '8ee0072eb296d8c31516cfe3c98aa9b0624a5232445b21e42002883cfc727d5b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePendingCreationMessageTest.kt': '061237ca0c3d12769c41e253801e5aa30ef1a2b6a8f05df436a48369140ba27d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePermissionLiveTest.kt': 'af873bec3d9c920fe3fb62fb2688d5489ee555e479d25eff877903bbcc2b1702',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoAuthorizationColdTest.kt': '2637efe63a2c9dd554484fc59da7b4aac0ae6b8101342bb29282394bb71be17c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoAuthorizationSystemTest.kt': '586b885ac3d20f676a6740b5ac489f06cc5e346178e8b09b0e2d6ea146b1fc61',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoAuthorizationTest.kt': '17851fcc64fcf0ccb8ecc516896606d2a39388d36c31848173b316198ac66ccf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoCatalogUiTest.kt': '642a9d710065520205ba8fd3a16ca7ee9c09e38869d46aa2212451c2567c31d8',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoGalleryModelLiveTest.kt': 'fc7d8c5ba059e9004d2418bac8eaece47eb7fedc1f290186410597eb02c38d71',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoModelLiveTest.kt': '392cb99d232c24a9128ef951cbdf7a421b29287faa2c963d2e5eaa310b5ca9f9',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoPickerTest.kt': '413cb659a078ed810553c0dd775bb6d9f5f677c641363a55428b8ad866782e04',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoPositiveTest.kt': '8d5eec17302f812f2330b353167ce4c596e2831cb75685c9fcfefcad1103a42e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoProcessColdTest.kt': 'd877d95aa3ed869e6a2d8f3e3eb66c3f64e0cc9f73609198401edb0699528114',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoRecoveryTest.kt': '14a06508cc16ee24612684620d82e5b227739b64e6a20a3c743fcbb06d96b6c5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoReplyReadLiveTest.kt': 'f73842963ca3cfd0609aabd4ed3f2aa7eca03ffd86356f7560a63a0545e9d076',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhotoStagingTest.kt': '6770c6d5f5452c224e770002b9924f5c227ca35304dca4a7cb14cc70b4959701',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePhysicalSmokeTest.kt': '12da9ce2f699b8e23469fe16f01a3d19e5cb93c5398a72064c180fff7c03db18',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePlanModelLiveTest.kt': 'bf426145f9603b561ecc24ad7fe5f017fd2b978f4b49b4c256342955abfb901f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePlanToggleTest.kt': 'ad952e43b13ed31d7bdefde3768519b093e86ca2d91944b842268cabb4481e86',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePlanUiTest.kt': '32afd0ccd391e5a41b5fb1fa57808bac8904b89e3228f7c614e8f2772f36d774',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePresentationCaptionTest.kt': 'ba3b7dfb150036f71c6bbc0515bf6ecd46bb22b41f44d6909923fcbb4130211f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePrivateConnectionLiveTest.kt': '892c0e66876c72b244f7808d1180058ddae8913caae76f7a0b5fc6cc120becf5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePrivateConnectionProcessRestartTest.kt': 'c45211618a2c3d71b9025f50013bf4592587a679966cbccc1f7f4f70085df778',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePrivateConnectionTest.kt': '2fe76328cb7b24fd3c8062c6f88f7407bd4daa1e624396440e343e5810ab8cfc',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobilePrivateConnectionUiTest.kt': '408c6a849395c0831ed8807703480b08aa7ad5a5f957fa0ca8d4bbbec9a4e1d9',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileQuestionModelLiveTest.kt': 'cb6723a5da81a46fe15b2f62e283f291cc6923458ecc6241054fd67c99998c9b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileQueueActionUiTest.kt': '832c0a5538ed0b77f4e9912fad5d93702efe59d4472fa9a1a2f74ead49d043b4',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileQueueCommandTest.kt': '601c6fe5428889aaea86608667bf84240df89efb32d361c48bbc278d576a87d5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileQueueMetadataTest.kt': '340167b551c1e1d5727164b0a16f60348ea99155ba2c2506a444197871de7428',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileQueueMetadataUiTest.kt': '86c88f737ead7f99ba493eceb397e1e0b9e170a080f526ae3e8abaad50187d32',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileQueueModelLiveTest.kt': '479dbd7bbf6075f1977a6586ee1991593c32189c9e6418eaf2ecc0b18ac1ef03',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReadSchedulingTest.kt': '0e20b39641caf4866133c48a89ef8c4dec866f820eb518a6d7bddde87d982701',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReadTest.kt': '5de30b282c3113fae5c4d0449c466391039e6dae5d6031bb941b9e50dd98457c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReadWindowRequestTest.kt': '2dad14c21a8b022a78a2798f2e5d5b403b389300d1884fb37beab868b200d456',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReadingContextTest.kt': '8334aec55a653af9c78182ef88ef06d794fb66fafb693c117b80adb6d79203e7',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReadingCopyTest.kt': '912820623efc8caa9a5d0f805cf18199b9bdf19425efa19ddbcae81ab8128ad6',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReadingGestureUiTest.kt': '6bb702da48292de1010eedba51cc756c22ff5faaaadff82104ac655841fc033b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReadonlyRoutesLiveTest.kt': 'c4f0f72cf6b726d748cd7e16531e82ec8dc4eb7362c199317c4e5559a76d33dd',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileRealReadTest.kt': '77d5a8b324ddd68ca697ee01b656a965119dd1a11f702f6b1dff74bd00f0b09b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReceiptNoticeTest.kt': '345259b8a16104856c354cb08604ca2abdddc659bf61f7f32dd8a58e0ff9e62b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReceiveTest.kt': '0a786e5f881417a115367c09d7f9ac800e321dc2a357b3b287dcac42dd579aba',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileRecentPhotoHintMediaTest.kt': 'f2244257e9ad4f5697c2bd0c4c2968759c1af3c971bd79c6e79db387bc75cbe1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileRecentPhotoHintTest.kt': 'b52b229b461d78d1c718c9968267b54a35e73b8327f17367ed145b465ba332ed',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileRecentPhotoPreferenceColdTest.kt': '8f428106b010f25eb292fba432967d30c7d7f85def6472c674737a68156d746e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileRecentPhotosMediaTest.kt': '9444cfe01bf930cbb7603ec7e6d31c965f0a2843d90aa2428662216af17ae49f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileRejectedCreationModelLiveTest.kt': '2c5681b42ed7f9b1e76ead0f00571ff4e602725c5dc4a929a53f7d46cba55397',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileRejectedCreationTest.kt': 'c060a2c12534cfde7547b00e4d95b8a92641f1d6c60237b07c0ab67392499598',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReplyRecoveryLiveTest.kt': 'cad89deab797ef8d918d7f9b5feeb021c600525411d7aad3efb30a706b52e630',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReplyRecoveryTest.kt': '32e0202505bc48fb74d784f8e784996140b8fb2e544c67f41d4039f243b117f7',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReplyRecoveryUiTest.kt': 'df73bd19c7d808ad8f2d62dfb1689a655384154b67ecf6d8876d1c972f229f15',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReplyTlsReadAuditLiveTest.kt': 'c2c1cd5bf7d36bb9059bab3626d2f3a6238b7c58a686599513722bbbe18c89c4',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileReplyTlsRecoveryModelLiveTest.kt': '454eb86cee55d44ed89259ff735e1d7396279b7700da7086a601e41c77ea054d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceDownloadTest.kt': '83070976d124ee9de1e26c155911ec36d74852e21bd72ffb3f907782d509e146',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceLiveTest.kt': '9caa66a60471ee573220090b7ce45f5783aa2658599de64b011f764a3ab6d3d0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileResourceUiTest.kt': '7921ebb839ec3d9400fd65b1b98ea5d86bfe4adabaed62a1f15341977ce90514',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSavedRouteDiscoveryTest.kt': '39f7593f125824b981251d6a6cff13dbcdab302537ae59203e392a398d95e15e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSettingsRowTest.kt': 'e7e0b431a21a09d3e2f640d75172290e123c33cf3eae2f167943b0a425b3c921',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSharingCopyTest.kt': 'f99eb82f6cf818294fb1ac3a05db457938196e0cb799e83f35279fb77e4342ba',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSharingVoiceTest.kt': 'ca7162369387aab45ee58a314e8cc6757654fc7bc2e31880eea44aca0f91baba',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSidebarPreferencesTest.kt': '5cbe384a0e2760da13061f9efb2bd28f76cc40b093d8c594bdebc36042b5770d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSidebarProjectionTest.kt': 'f62a7d283c23e8c503dc09358ea1ca77cf821587e6250267725d9874d94eeb07',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSidebarSearchReadLiveTest.kt': 'b5dc6afe67de0aaafe1cb6fd70a81819d677fafd7e95c870f4c7a58f1dca9e75',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSidebarTest.kt': '19e4cae808af2783520a1f54d8871cb0488433a3f1607552366fb0e032f87017',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSidebarTransitionUiTest.kt': '4f8be9b852b5700b64757540ecd68bc07da2e2a369d5233828b435bcd28811e1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSilentCatchUpTest.kt': 'b41ef132d7326ed20ca49703b1836b78273639f9cff576b004203e211125ccf8',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileStateTest.kt': '9e81cd62f0f82295487406b19369dc40a0af3ef3d24dad66f395772741332f4f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileStopModelLiveTest.kt': 'c018626ec00ea1986767414526bbd6bc30cd46a864c09a7550fe35a368f06680',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSuggestionComposerTest.kt': '19060647372495c3e12eb4a3c3ac95998cba161a4def9a2db94ce2011293a190',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSuggestionReaderTest.kt': '6edc7011d5a8cc087d0da49e044e1effbebc3ed64060bb07e3ae1f7da76f5580',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileSuggestionSourceReadTest.kt': '7a6b2bd04ac4e075a4b239d1058ea7d8597261a231c4d6f3948247018066c4bc',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileTargetLifecycleLiveTest.kt': '6be6730935258c3b5a74d77c837e644210f8c277941404961fa67eddc94228eb',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileTerminalControlContextTest.kt': 'b5da93b4ad5779b9fa0bc64da86d8545899d4a3050e4354601974cfed5ab7bb7',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileThreadForkLiveTest.kt': '19fc26a0698c555ee2ffca408e053a2a38e9d298231b517c419aeecf146db2b1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileThreadForkTest.kt': '5ab2b8d26bf67797c0bb58e515a497976a8000672611c20f8721aabb043ac1b8',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileThreadForkUiTest.kt': 'e55bdb739d613c15eb5a3bed5200019e8335160da563c517600dc4a039d87c5f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileToolCopyTest.kt': '35fdfcba0325d1f84838b1b23088daffb0ba6d1af368393aacbba6ab700b3681',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileUserBubbleTest.kt': '7b00c2500dc9f1874058feb190e07954981a87dcfc1796162b58e602edd2c725',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileVoiceLiveTest.kt': 'eab4694704eb23d120165ce54bdd856e05a7454361653f004a50f4f03a07317c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileWeakReadLiveTest.kt': 'ab614e69230324e7a1401faa35cc8a1ce5b1db8c1802287e7663eae22a413f93',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileWorkspaceArchiveFlowTest.kt': '7eb334a2bcbcc1173b9fadb63ea4d3a20ed334a5cf9a996e328bc47bf4f1b01b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileWorkspaceArchiveSessionTest.kt': 'dbdfb7f08496557a1fdbe0c078fb1b114a23af00d15b25f007d0822a16384cc3',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileWorkspaceArchiveUiTest.kt': '656434a396f2a8b2c3e694730d4a791e03af166643537fd231f586569a875072',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileWorkspaceContextTest.kt': 'da750a116fd7c32d9857adbc48d0df800f63bbffb058dec0cd75f9d587de006b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/MobileWorkspaceMetadataTest.kt': 'cbc2dad4a85e4c0ace59641b5d6e2e081f893097db84cd870d84c4b6ff9931f0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeApprovalViewport.kt': '998008a3bb91511c28f9e8085cfdb55901f2778b6973e614dd95de9638df62b1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeBinaryRecordTest.kt': '14a6be893ecf57554466f7f03898c04993870c1d2fb7b15b72b31ccc88eb64ea',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeBootstrapCiphertextProof.kt': 'ac6d66e161104c90e26b88ee1fcf355c7179d63512fbc40f09150067d8a1f230',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeCapturedQuotaColdTest.kt': 'a6857316056c914190d0a0fd4d474136f229020007d643cb2e09f7a8b8615812',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeCapturedQuotaTest.kt': 'f2a48984b77c6a17b77c2b49c3d9f4c8ea24d66ea9dbb54ac9bc22f99f25d032',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeCase3OriginalReadbackTest.kt': 'c5505229918e72f0cad4ad482d21fd3ae5a1425bf34be7f6b97701a057b7bd20',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeCase3ReadbackContract.kt': '74f4c714af7db7c5d2bb835bdcbb2ea2a93d489a68c59af7b2dfe9a26488fb7f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeCase3ReadbackContractTest.kt': 'f5c6d120c23843ebd68f4d5f4ae7e2c50a8873bacee26b47430f3e08e7a90428',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeCase3ReadbackFiles.kt': '9e9adbd866a13223e8c4f2c74e236dfcb32d67b472305d90ea28225a506d5f3b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationAppIntegrationTest.kt': '0adcbeae0736c2f62949cab3f7352ff604815a8cff83e9e7d160d776ff9309ea',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationClaimTest.kt': '9168d0626338e8f0ace40437c846b41555a2c11dd4254cfbff4d1b3fd53a8ec2',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationColdFailureObserverTest.kt': 'ee30641c33ab1adf927620fc68bb740a1d100868f74aad30f7ebfa0adc352615',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationColdReaderTest.kt': '373aed792800c48a0fc2ac54c48695b9130701e615815224f0e0999487a4be72',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationColdWriterTest.kt': 'ab0b3b60273457304c32769d12879204f5c213734a52bdd7bb5c733b77e6d5c3',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationCoordinatorTest.kt': 'ea541ffb76370d3e5e1251237c7aebe10f2fb50d7bec88b6f999608f15a83100',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationCycleEvidence.kt': 'cc7b42e542f3be84edc6710031625fdb9edc2f6b3508d756fa76266f29ff9173',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationCycleFiles.kt': '43c75b95d3873bc449441844929aadbaa145328d13d38b22cdfcda09d1a2a398',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationEnrollmentOriginalsTest.kt': 'af14fccf6428d592844ea921a0a1a2aeb7f8a12f30e9b6cd8b1a1a2c6978462d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationForegroundTest.kt': 'a72ed6c2d848383fa349ced5170d1c7b4952c6feb35a85cf8a64593e5cea9e6d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationGraphMainColdTest.kt': 'd567e1c59be12f459b96195d013cd5117684f5498a4c2b80d190a126cf98f042',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationNaturalBootstrapTest.kt': 'a187645aea036e55e3208476d0bb29f8d5fa80498c5ff18ade33c4da868b2818',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationNaturalStoreTest.kt': '28347635225cb04b10f48c2b401784e7046238d5130492e7d02cb9776e717556',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsColdTest.kt': 'd6a2ec22abe32139c160c7c8f2e514b14b67c5f4f3c3d8c583f11c9dcaf82089',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsCycleInspectionTest.kt': '452735b7b405e557f5e79678554a7679e78108dcfc0af59341b09c02db73453d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsDeniedTest.kt': 'af09b8c863472942059d26f96d230ac3972a11b8125817ff8f97ac82ed6004d2',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsFixture.kt': '62d56e8489eff26d138b6627da8bfcdccc31f96a4b447b3ea71a43f4da888912',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsGrantedTest.kt': '6a4d5cc0a2e9c9bc812247162147fceb8d6671caeff0ff3d431495f93bb0ea74',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationOsProviderTest.kt': '61f3c76df88025866f40357f5b82abcea96d6d7ce6df489b9215a07298b4efd9',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationProviderIntegrationTest.kt': 'fe690a9aa151ba857280a17ecc682c97d91974065e2b50bb69ce3b851dad1af1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationReceiptExistingReadTest.kt': '300a1529d8bb53acbf2fb9910d6aa6205410737886d0e5a197d03602690552bb',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationRecoveryFixture.kt': '2c933424269f7d357fc4fefe87cd6c3251e624b3f5b8989369f232cd26fc2aa5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationRecoveryTest.kt': '26beb050a0d9d8338e6c20749bb190ba48eb32095365597ab79aa5896c6b4818',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationRecoveryUiTest.kt': '628082329b315715ebe45fad940984ec6b1fc7f8fd3deb7f7efd5aea6be6821a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationSettingsUiTest.kt': 'c371ad7e974800ec80e51dc6ec73d49c5dcca55d964637ed1661e8385f6acc75',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationSoundTest.kt': '8573df0efbce32dd909104ce3f61bc54c5abd37b7cb5dda0ac8874b0db51ff0f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationSoundUiTest.kt': '31e0155874d2fc0a928b81faaa200882a557932e0a756ffdf180d441f5f859cc',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationTest.kt': '0718bb1006caae2d152e761b6848c3d0ba8b8fd542c537073aa20e473a3e9a39',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationWorkerCloseFixture.kt': 'af54d8945da432677929c8dfd0d66900c04e87b8787d3fffe09f9d0a636e682c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeChatNotificationWorkerCloseTest.kt': '134ee2a8f8195444a336d14ee3167e37856481ceb7008fca48a466a4e4e8b7eb',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeCodexIoOwnerTest.kt': '757351fabe0f53a9478f7a8af05cad5254dfc1001edadcd8ebc8c8e56b1d63f1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeComposerVoiceArbitrationUiTest.kt': '071239e4ea1fe104cd828e7a8d0fe1d09be39a53b9729bcb2e02a58d53e94f41',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeDefaultAliasObservationTest.kt': 'f2a37ecc8ae3a93ceb5014297d58a80f12cf9499c29441c01abbcb8e26d64aaf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkAuthorizationBoundaryTest.kt': 'bf1945dbe9a917edaa7e7c9c3580f3a746f76e55309607169e66dc5a0574cd0d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkAuthorizationColdTest.kt': '334ea435f113bf287e13fbb0258e17308cce749dbe40623934456c957eaf0ed2',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkAuthorizationFenceTest.kt': 'bfb2b997c5803bfbaa44753c9485edded2a48e1c6a631d213290a3c85cd33d8c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkCandidateRefreshTest.kt': 'eac33b36bb5a7fcfedfcaa779085abb21df152dae1aade967a94bd2a74b5b7be',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkCanonicalTest.kt': 'b1af4e2c8188725dcc398c199fe9e50e224508a06125f510433054142840db8c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkCommitColdTest.kt': '40fb86f23f98945fbd539e7a496417f15fcdacf1b677fbe44a4b2cf14ff82f9a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkConnectionPresentationTest.kt': '0b01bfbe8fe6043ae950d74375cda6a4288e47fce818292bc9ae573238199f99',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkDiscoveryUiTest.kt': '80417b5baa8b162f42add0519af29a3f5a2f45192df13fb79c8e89af85aaf3b5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkFormLeaseTest.kt': '0ad86d6612dd2c96573d1cba710685f0265bfccb181dcc27f93389ac61dd2474',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkMainIntentTest.kt': '7fe9347106ecaf78fb33466e328a8bf04ef0f560b6ae6c77c848c061377a0024',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkPairingCopyTest.kt': '1f00b33cc725cb953735f174c3ffe70588d33c503c732bc82690ff803de9e2b0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkPairingViewportTest.kt': '3ee3f9579c0dd139bacba119fb93e1fd2212d258d5aa64114280e42e53e8848a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkPositiveCommitTest.kt': '028ce7716fb6689f1870cabb2d2e26e1831b7beb9683e2cd02c15dd5b238fcf4',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkProtocolBoundaryTest.kt': '84c6f3e018aea26e7c55c2d1020234167cbc85cf815846b289b109fd2b8f4d9b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkProtocolPeer.kt': 'e0ff3dbad25a2e333b3b802717b1063b1dc6f83b017157a4fc80b501e902f95b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkSavedSelectionTest.kt': '61d63d0a5ff963c15671a7a37e8579c760206ab504f4bae873c103aa0e32182e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkTransportBoundaryTest.kt': '2974f7a441c9cb8a598cc4c19b25fe943f2bdba68e2a0d4885adcba45f7928d0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeEasyWorkWriteGateTest.kt': 'ba00bc6e470b37df79bb5723347c0e62ac5f0ec8b6fd320a2a77b7d700f70501',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeFilePreviewDocumentTest.kt': 'ec04b883cb717130d63fd5d42f0069df5da13657dad93c9e44d12d8a93090ddd',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeGenerationSelection.kt': '3d3b2034d07ea38eeeff3f12651be492591105634a2a88ba3d99daae143cc2bf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMarkdownNoticeProjectionTest.kt': '4b3d5480fe168ca22c69d752154dbdbea7d8b0e8668e3f1bdcc0f7724fdfc519',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationActivationColdTest.kt': '5eaf220a4c7dd555c688437094bbe6142490b79f2aca10508116dc1e08e9158f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationActivationTest.kt': 'b840ac5c48011b4414c483c4ebb76010060959a5276eaa6066b8b4140a9a67a8',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationAncillaryTest.kt': 'cddc5b25da76a37bf9847f7e82d10218faedd557a0526cdbb01b30e1f2f4be7c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationAttachmentTest.kt': '194d453fcb624b3e32d86365d7615fe4f82e00d6aec36ce9fdf4c96599c9e4b3',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationAuthorityReaderTest.kt': 'c0e389675d2e9e2c9fe7b4b92fbef9bf8b0161363e2b4c0394646bcb8bd7686a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationBinaryInventoryTest.kt': '5232bd4c2d391c33f553e9162486ea39103186c376010e247867d26c1e8152a5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationCandidateColdTest.kt': '071095d271e4c9e1f73cce15a5a6aad831a3b0e694b497cecb25cc708ad14949',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationCandidateTest.kt': '114fa084593fc0f62da6863d1b3788fd8a102ae837848d2a30e154039dee0fdf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationCapacityTest.kt': '0ee6aeeb8980efda31afd0c394ed7beedd84c0e5c51452960e6d6cb23073bc11',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationClientGraphAssertions.kt': 'f3f3b92cb2358a235ae2387e77c81c65a67878f00e8928e8c83e23e42d4ae805',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationColdPortPolicyTest.kt': '6dc7e7ead39b9ea0f86e952d73d4cf4c64fff9ced69d4c3db104be6ffa492d47',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationCopyTest.kt': '917d3be41f9ff0e2f4a028fe36dc3c708511c96885c0a8ba92ef9571af527e27',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationCryptoTest.kt': 'bebf6073ac70755af3c7a2b63baaa4804fc5fc569f72aa9d5398608547009ee8',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationDeviceStateTest.kt': '666c722290100b1e11f34f1c38f56fe6dabc1764b96e8002dfa8985204c4f97b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationEasyWorkArchiveColdTest.kt': '713b7e9411c911aa34e3ca2b9c4b7858d6dfdc807c7e2e851cc7f3b591437701',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationEasyWorkArchiveTest.kt': '0239a38fa0ee65c998373ca9ca73a50ec83665e9af8ac78ccfec47008938ca57',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationEasyWorkBusinessColdTest.kt': '978dccb2be2a74250778d86e55c481a8086c09e754233ae951cecc68555c8943',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationEasyWorkBusinessFixtures.kt': 'df61c232cdf66a9cb57cf2465ef1630872c6f6474daec787534ea2d0fd05faa1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationEasyWorkBusinessTest.kt': '106b4892cee429d7ebb18ae17221a545671330b3a430a98cb24440cd9e606f57',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationEasyWorkFixtures.kt': '6a8c47990e5058b24e5e5579cb6f94ec1a04fe873d3b3ef7ed51f96274f5cf84',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationEasyWorkFormActionTest.kt': 'b5a5463868700bead2bf9e07b160ca8774f5766fbc4c96d5eac9ee383fcf53da',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationEasyWorkFormLifecycleTest.kt': '0af1a4a5b5456f3d8090adb668f5b4b1938ae503ca376abd0693cb67d5bd594f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationEasyWorkIoLifecycleTest.kt': '6f1d92f001bf13105ee9c79b5653b69438abaa07ca0990e3019cc27651a14c7b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationEasyWorkIoTest.kt': 'c7a1add5c778647c5de98a05ce4d60cc03a3fb10644ae16cdee3cbdb2db89c68',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationExportTest.kt': 'c1c98f9f670fd5660add745ef4f79292d0a220f77b1487c42b8cc48eee404078',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationForegroundTest.kt': '70b64cbd1c92eed645077b40cc376f271a9abfed150481ec994ed0524793ada2',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationInboxContractTest.kt': '6852e7f802086003f4fd89b0c966f3989cb6de00bf712a6a0db8d7de1fca7868',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationInboxOwnerTest.kt': 'dc27f7f45b675b8b80eb60c384d25bf608157643869394eac7fc3536a08a57cd',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationInboxStorageTest.kt': 'd4a110e6dbc1b9bf859abec7d0d313c57adbc6030820fd0a59a08f714f3313c0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationInertSelectionContractTest.kt': '91b0cc17cbde2a4373fa20aac69d638da337e6179205a0853fe427cbcc9e3734',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationInterruptedTest.kt': 'ecbd8f7a20b7092c9b93ad29e06f81d5c11c7038654153db3b87ce55102dc68d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationInventoryTest.kt': '044855242fb749b03030738f0e4455be3d156a1819a3c11b6b54ac86debda4ed',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationLegacyGcmTest.kt': 'a5bd75ccf8688427931a524d6866af5fd19c1518fcf6039dbca2ea492e9bd398',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationLifecycleIntegrationTest.kt': 'cd25c4c63e16c16c1b6316c8a6ffaabb5228ce6b6ad2fd9b9f62711a23915c3c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationLocalRecordFormatTest.kt': '3dcf380a89503b6c948cb07d76f27980f7d96aa86748785eece1ef69664e700d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMetadataTest.kt': 'df4644876f91d33b486b51aaf5eac59650eb44512baf8b4ec038cbbf3e7a6609',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationMixedProvisionalVersionTest.kt': '234592704bb171643f96b961d4600aae69d4782800070e9ef4b1eb36e51e75dc',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNamesTest.kt': 'b28fa714222526a69362b7dd7c9c6a16c4bcb8d82eeb7bd310bdbba1335b127a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceCase.kt': 'daa028bebbe288408d25f50d51c7cf06ae21d6ea238d58a6e1ca4458f163b781',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNonemptyDeviceSessionTest.kt': '0d71e1ff25bf2514a6716bae788a42133ca24c1e176cf13668be711a71898379',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNotificationArchiveColdTest.kt': 'd5f8fcf5de44dc04c2434c9c1c25ef74e586b4f125786de2e56754cf12d2db69',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNotificationArchiveTest.kt': 'f2803cad8aa0477d2cb9f0217309da098c2df6e77915bbb3aac2ae94e8dedda0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationNotificationCapacityTest.kt': '510e042c82108d0eda344ffe2651fcd75a0c64d2fcc89810548800a673498684',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPhotoLocalContractTest.kt': '74184769120afe866d8392c13db59e1cf154fd00e5c11de2570035ce6e23b6cd',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPhotoLocalOwnerTest.kt': '889571a69512e39d2a7941ff2d46e621139e904c17a088a6652312d4524df076',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPhotoLocalReaderTest.kt': 'a4220519b9af9fc62d9c35092a435e488afdc9d9a8c39b758a45e4bbec4fd5ed',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPostPointerStrictHandoffTest.kt': 'd76fba1665925a9241029f8b2eb348ffa5aaa134940fb3a2489ac39b3830e6cc',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPrivateActivityRule.kt': '28e4651c091a512b39e8387f92b1f67d9c1361cd377a09ae00d5ec293afef4aa',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPrivateClaimColdFixtures.kt': 'fbbb185c59f6ba2ff1c5b3515bc0a37fe3b2fecb5e8d207d7baf10eaed099af3',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPrivateClaimColdTest.kt': 'f72f1655148d1c52c37458ecad8c9541e2a7cf8b78f20a9f7e789c8831506e37',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPrivateClaimFixtures.kt': '48c7a3c086adbac9eab505926de0ff617a99cc540e0731f733e258fab9ee69f6',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPrivateClaimStorageTest.kt': '8b48c04162ab3a6622234e8d31f9fd479508c1595e03fcd22e7ac6083213b2bf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPrivateClaimsContractTest.kt': 'b7fba2f51d11451ac380c8e15ac59365d5ba7442770672f8165cd7997be8e4ef',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationProfilePrewarmTest.kt': 'e94e15e257103ffa5a1320064916960aafd7312ad5acc530bf870e15f256065a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationProvisionalAggregateQuotaTest.kt': 'b50b42c4ab843fd5178a27edf678dffc07f7663c253529467808b774ee7fb7f1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationProvisionalContractTest.kt': '06bf60fb8bbebce74680a9b2bf2340af077ba6975cf17bb58f484c0bbe615f3f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationProvisionalOwnerTest.kt': 'db6f37e034f998656ddbbb9a08b0830221a690a6df0fb688e59ad26c527eb9ce',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationProvisionalVoiceTest.kt': 'a2d12afc8ce18c5e3749c3d7daf4074e594deec5fe6684533dcfb2fdfec68320',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPublicationColdTest.kt': '65258c35443901f215bc0079bffc857c827b1406eb8e4f72ebd1b74b2f6fa2c4',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationPublicationTest.kt': '9f397d5b02a9769ded5750e66f06335a83e1986f15581fde7e7e7ae4f5eff967',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationQuiescenceIntegrationTest.kt': 'b5d13bb3f0ae50fd7b27f743b6aa4189f96fafca01b582cd5521c6af579d3a1c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationReferencesTest.kt': '273f77f49e4e1581f2d06a4149c4a6dbf9a5abf3aac65e70ac61e511868a21f0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationRestorePortAckTest.kt': '0357ae45f5dd47521de9d28e7a1d64a01aa83329ed0abbe828f32dccf5928f7c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationScopeRegistryTest.kt': '76056d3a7e4924a9738379774db0755aefb9cb1c530a4c8c412ee3a40a60644e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationSealCheckpointTest.kt': '74ecb66894a676c02c29d58a960682f73fcf6f0f7929eccfdcd98500a9d196f6',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationSealCheckpointThreePidTest.kt': 'b0cdaf21cb2afee1ea60c3e0135f560dd073fac765edff69aa10830df6f8598c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationSelectionAdmissionTest.kt': '0587a211a8c8b73dfac134809b56a2de19d3ace43f59b84dca84817fbcc5e93c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationSelectionContractTest.kt': '9eecb320be8b7458d4f7bb291f246df1a8583b79c3d08faabfeb769d83ec92a1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationSelectionOwnerTest.kt': 'df07d87766c2b1a0e600a14f9cf4423cb205adf050e8f367f347f31dcfc67b53',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationSelectionReaderTest.kt': '5c442a038dadee1db1c4e627640742acae082703e2007f113b8d2b6b8ee1ba6b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationSoundTest.kt': '27a59cd71d652a689e4d3a7b7dc89c9555f96b5b0e6f0d3ab1fd7eebcb6aaea2',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationStoragePublicationTest.kt': '1d26f681159a678d8f9954adc1f6169b67df9fc92b45b2166eb571ff2f736579',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationStreamTest.kt': 'a434723dcdf229c46089dd310bca25f3589a32185df77e9c15f72e6f7dcb900d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationSystemBoundaryTest.kt': '9fc5347a1fe3b0c580ce8d2f8fc83f18a8c71a95eddae52523602b8649b9d955',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationSystemPickerUiTest.kt': '84489bff0238b426dee4e6391bae66cbdb701850f5362a330e93be2c4201caee',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationTextFrameColdTest.kt': '0de32c84fbaf16a511599f4985f2fd187ec26029773c07dd3f233103cd759fe4',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationTextFrameTest.kt': 'f676ee3c4a91c5754d4c96de65e323625580072b196f02256a6676bbc5488bcf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationTreeGrantPreparationTest.kt': '355e2c43974e04071381e54ad224e11807fbee6cbb8e07acb605d0270939009c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationUncommittedAudioTest.kt': '68098e8b6bf1d9d96f718d48f0224e361c2ff98ec7f99dcd41614442e0d72b37',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceColdTest.kt': '73e7926085ca7c662fde018da578984cab9be1d1d8a0fd3b662eb64952a7bf35',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceTest.kt': 'cc458e326ed9fd2f82aaf91c63e19d557d25bdc9db0a2e94437f4aaca6b922d0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeMigrationVoiceUiTest.kt': '078abb0e1a32e34548e3ba0fad4c08728c4ce7f1a364732f2cd746a5ae543b96',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNaturalBootstrapColdBootReadOnlyTest.kt': '1e8c28e097e6a481b77a974172e7364a50db1f249f2a383a2939ab49b2e00403',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNaturalBootstrapFence.kt': '69478e372cd6ff878a041abe2af7242f576e1cc0eda1984a1d79ba7f7868c445',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNaturalBootstrapUpgradeReadOnlyTest.kt': 'e5a9212c5ae3198e48dca408e9c6359a009acb163e1a57db3e7bb57552951fc3',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNaturalEvidenceBoundary.kt': '1e17ec4f11314f2df60aee9d4429a41a61df39ccee56ed740a9c1db17d8bba5f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNaturalOsProducerTest.kt': '2a5282353738a683a99864606cd94c6af69f868d1ea6b4f1d86b4e5710ca8155',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNaturalPostConsumerInspectionV2Test.kt': '93a46ed0b830610ea9fa33273ab3d7c29a5c876bd3f45f6b594f4103bca7f5f0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNaturalPreflightEvidence.kt': '50de98c947767754bc4f5170306044484e5cda7c957a1ff55ea309b65a249f2e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNaturalProbeDiagnostics.kt': '958cbb9cba6e97b3a93f0b688fa0547458ab5d9a2d421d193c80ce450cacd391',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNoticeReadCoverageTest.kt': 'b6a56429e2acb94363fa1aec07415da78cf4ca0603e5b7b17a8bd5a5cbcbe30e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNoticeTextWitnessTest.kt': '527226ea12162321062029b050654c984b78a30eda39ac1f184c4daa63127bd4',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNoticeVisibilityRulesTest.kt': 'e26fdb9559f540d1584614d6c950f391d2d994a89742db5ad628af0be6109bca',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNoticeVisibilityUiTest.kt': '3565f2c7524a21b1c1a84253c034c0c0f55c843256550945070fcc55ce2f6c30',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNsdControlledLifetimeTest.kt': '67b452534363a53eee03b5da8f447dfcedb285b28a4e3842fe454260e2a145b9',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeNsdSdkTest.kt': '8eb2de7326791530170bd69b58551f0f964a7f8b656eb5fea277eca76fa412f0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeOriginalGalleryColdReadTest.kt': 'ed32a701aa48a877e5cf3b33e33f77e2db5d688eefa5e1987582e432f1cac65a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativePanelNoticeLedgerTest.kt': '6440ac87a33b52baee4cb2787aaebca61a07486a83f193bde192fe6b047222b4',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativePanelNoticeRulesTest.kt': '7a200441a5871601dd298eb23c3fbf91625a126dc0abe68ef738201ec3d998c5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativePanelNoticeSessionTest.kt': 'bfa9d0794e666076bd159984c7a261cacf3532a9c97e2d177a5375a88b9c203b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativePhoneReadingRulesTest.kt': '12f455591c5a082cbab8021efd3d05c059c22ea0a26a0f4d110d31e6487be513',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativePhoneReadingSessionTest.kt': '0912afb79dae01da0cb51f8fa389ae946990c5c828acb75e5701eef469eb4e82',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativePhoneReadingStoreEpochTest.kt': '1a0d91e93a4607b3591e98420ac7d50a52760c242c26e0e20bee4ce8b6a98968',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativePhotoCatalogMediaStoreTest.kt': '2369f223694bdabe45e3276d0df818249b2400f9893e2bdc0da61c3953178d0a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativePhotoCatalogTest.kt': '8462f6dbbd5bfb43e52b4a69970672bae46a6eb564c988f0c4acf3b438336535',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativePrivateClaimFixtureOwner.kt': 'b785e5be53ee5c489a297ff03c7140d81794792c8dec69df65cab41eae004f65',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativePrivateClaimFixtureOwnerTest.kt': '47ba7eace4601e6305262aa4de4e4c54fb815090dce623100f0ac66dc039218c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeProvisionalVoiceRecoveryUiTest.kt': 'b1a76c7b181fdf3b15a55d9014e9722e34b414164723c2d6bc0dc144a53e4eb1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativePublicProcessNaturalOsProducerTest.kt': '6c7b18947d17680e3e2d97ae0ecb506e29c6120a796b8c236e8dbc1de87d9901',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeQuestionVoiceUiTest.kt': '2ef789c81d64840bb612fe903ea9a092135c61731feb6215a1a83a1999607017',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadAcceptanceTimingTest.kt': 'cdffb5633ef4366bce847673d771df3f229c279cca56a1138f1ba3353cfe8741',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadNetworkMonitorTest.kt': 'c52ba2adaa1c9b02eaeebe2d9b11e484f013b2e0f34efad7d319717c175bbd5b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingDirectorySessionTest.kt': '88e6e2cf15bdf6a2eac481d01d236c8ab4592137561606208ab90272810d4a6d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingDirectoryTest.kt': '660f229fe1e95704690d35f46c7479048adce0fce8134bec0f8efa74196cc9a1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingEpochAssertions.kt': '2357876e19e7019fcdd7f6f3c7942fee22ac5d71918b3e362384d15253177072',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingEpochLedgerTest.kt': 'aeeb5f4e21d844e67ec36f575fedad492312639bb961f09b5664c0300599b456',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingFixtureFiles.kt': '3d4321c9f8fb12cd56f2843d7b3df9faafb7f843e3dc10fe3965aff3ef9c60ab',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingFixtureOwnership.kt': '02c1231f6764630efa9b1d5c3c991a49006892765ed73e95c92da74697610e76',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingFixtureOwnershipTest.kt': '62882e32e3adb81c5fa05617fb79fe34dfb9533d8c5e1a2b5122967dd8b8fb8d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingLedgerTest.kt': 'd3edc00c4e5babf277eb270e23cda36c3fe6e172817bbdb2487d57209bda56eb',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingLifecycleTest.kt': '8a3b78a1610c4488a12433fcef48c4d1a80e2259babb401abe277c267c5c535e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingPresentationTest.kt': '21a86f6042bd9ab7b9209cc6c60014cc8d74a288e49c799eef9860993a6d870d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingRetentionSessionTest.kt': 'b76e04bb2fa1ebc96ed232e1ee976f011e3a9981b4170d9489fafa06a783188a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingRetentionTest.kt': '60d24c3f71ff39aa66509f048b8268935ebbfefef3b4caa864a03a2ed046e197',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeReadingWriteFenceTest.kt': 'd8f728de3a6765dd4f231936df3e3afb9633a6d6271fed0a10c196261aea5e94',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeRealFileAsrContract.kt': '9f69db1a8af9e51dd2620651fbfa3da267d651aae7e5ffc5d6019be153bca4be',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeRealFileAsrContractTest.kt': '5627c1a05030431a847911a1c824830212d149c2081482c8f77893b9619925b3',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeRealFileAsrInstallDiagnostics.kt': '2b661c7778c1bd1394d57c4440de6df86295864ad7d933b9653aaa75d54347da',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeRealFileAsrObservations.kt': 'bdb30f461485f6cf9ba4938ee73b616e2b47f9e3ee5eb272d31763d7a83ba605',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeRealSavedVoiceSpeechTest.kt': 'c0c8ab2018f089eaf0626e6e954d56ab6e26dbb3fdf3ed0e4291a6521c36c79c',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeRecentProjectsTest.kt': '455e6a09c59e7f2426a304d9030b39d48030c407c91131136f08bfab57b1404b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeRetiredSavedSelectionFixturePairTest.kt': '15db439e238811f329e168369a40c259535245ca02a206056fcd1f347f9f5348',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSafCancellationObservation.kt': 'de2eddc7ecc12bebcff40246c17c3efced0d344df46dd9cb73a957bd553ad8ac',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSafCancellationObservationTest.kt': 'dfddc412638742e5ffb5b7e7e89ed81e9a53e96c8ae488168406c33397d46557',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceActionTest.kt': '48214e30f99b7c0111053e268bc124f017e3994145ec62ef1597ebcd9c47cdc0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceControllerTest.kt': 'dea2271196b268d9126f48d62a3e115ca79b3a4b624be4f4dd894189df8a6a57',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceDestinationFixture.kt': '55ca55392a43a212e42cebcf5696159cf32f0743779498e16297dbcf0a380360',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceDiagnosticBudget.kt': '0e9cbf7dbeb3735edecbb16b1ecdc682353f8399b7f99fed32e9a0c4de75347b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceDiagnosticBudgetTest.kt': 'e4971fae124914bb12b0265f3ea2a4f81fe216a9403f4e46651f112eac6970cd',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceDownloadTest.kt': 'c37a54595bad49cd6361213225242945cce1d31b9e7253ad82f8a66e8627dfb7',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceExistingPlan.kt': '9ee8ffafc2c7b1c11341fd32b31cef94c183930db678ca7e22ea8eb63b4c3aa6',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceExistingPlanTest.kt': '24c87e730e6087601c2696ebf56f92d6e88bdc798c5ffeb47f1c3e876552f1c0',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceExistingProtection.kt': '3a2b2d7afe4260d323ac2354eccf7536312a949fad76a42aca7cb7afc881514d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceExistingReadOnlyTest.kt': 'be4693dc03caf69f1badc1066289c82261009c75a3bda017b64f2b5786143e10',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceFallbackTest.kt': 'b682f148849fa0b59d39a5396d48324b2435e44f53af128899cf7816c40513e7',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceFallbackUiTest.kt': '5ef846603b883c9bfe92f451ed5816d3991cc5ce919fb03f6a5d08e91d685b41',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceLateReturnContractTest.kt': '301c3b64443eb45ed9d5656e71a7cba3a0ee47e0cb000deeaa94f7cc53b3e431',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceMetadataWitness.kt': '82bf17f262ec022f3bad125c486db81a3037c2b277a8e63fd9b415b0b11e5162',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceMetadataWitnessTest.kt': '940a435992f4e069e11603015eb0d07fc821771969c969ef11a6cb187565b0c4',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceMigrationTest.kt': 'a84acc5a17f8617cc2e7e4106cd443a086c4a879f1a63aade2fd21e64ef68c4b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoicePanelDiagnostics.kt': '74c09412314f382496db7d2048230258f4a879f23cfa7f0352fe006739fdddec',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoicePanelFailureFrame.kt': '61175c22530cabbd0a906413f6ca81b347e68a09fd8f1ead28b625f3e447a5dc',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoicePanelFixture.kt': '9f4b4cb7a530cd5d187611dae8d4ba81cd31f8c652750d0ce7686df8b1b928fd',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoicePanelUiTest.kt': 'e70cc2f69c2acd7bba5cc2b18632d297863ce96e071178a0c8fa2b94ab844d11',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoicePcmTest.kt': 'cc47879c940f250b6da4907ee55f8c7679e95395f49f9f46282aad7d4fa87ce8',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoicePostWriteColdTest.kt': '05e0d23f2ea478df5e6cc796c71f1bb045132ab6265693ec0a22268b7000c51a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoicePostWriteEvidence.kt': '5d94840da06a438e449adc201d788e724abb5b63ca95c4872ebb6eb7ebe943cf',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoicePostWriteProtocol.kt': 'e844619186500340b5d8d732dbc648234b9bd5e2e035d5e6b3bc7627be0bdff3',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceQuestionOwnerUiTest.kt': '7b9636d5bd39be5290b473ea1f864db03306bcc5459dc6f80759c0bf99143dca',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceSessionColdTest.kt': 'c8910d00714de0ba8562e29807bc3d8a91e50dec7548473ff01b4f04229d7be7',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceSessionFixture.kt': '76b6fa97dcbdfbad7d985120e4d53c0874109c0cb347e8689634ecef679debc7',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceSessionTest.kt': 'c151e770f7ce50cae568c99753cd751742accc7c0f945a5ed9f4e3c26acb92ee',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceStartupBudget.kt': 'ed50b4860138cf914ec0275f1f8338b90a3182511cce6580ef986e6c92f4768d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSavedVoiceStartupBudgetTest.kt': '851aa8670697e02707d9690ae3a90c3245b0285c5e5a52e0a6d9fc99185a6da1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSelectionReturnStorageTest.kt': 'e6a1683ceb5351ec1a974730a108d0987d76f9708bbe32cc28b4129cecd8c3b6',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeStorageConcurrencyTest.kt': 'ef24c1c1a29a03bfd27f2a7c17eeed774ad2cf825b3706c151d0ce68b92463cb',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeSystemVoiceTest.kt': 'e85d225fc3476e65007f5c7b5b82a673825867f2295d566a6dcccd4a401c1d0f',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeTestDiagnostics.kt': 'c7be22adc0fe678b0397a916029f13d9a6b9bbd0df89ff7c6315b9a56059fa4a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeTestForeground.kt': '462ca074a11008c11ee860ec363ef87d2ca15cd7c4f8377cd41565a5f4ac9533',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeThreadReadingWireTest.kt': '6f00721d8ac2be8f50247abbee76797b5673cd1207f84ddb1658a04ed4a514f3',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeTreeConfirmationObservationRulesTest.kt': 'c2015cd71b73ae8e5f0da8adc42922f9a3a3025c1f268ec1e9e70968b72df52d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeTreeHomeReadinessRulesTest.kt': '4b5e5a8ab5875e23afa98e8cc5b1f91c4fad73c792ff4e177a22a485e7102c54',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeTreeHomeResolutionRulesTest.kt': '824c56ed352815b3a31b89a68b303862a8b90b171e6df44ceb196eeb7c80e532',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceBinaryColdTest.kt': '539f03327d5fef83f74509dec2cf07c9edb469dc2a989df02415223019afd3e9',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceCapabilityTest.kt': 'c28843ca46b458c87c176570ef73aada40c9ea0f658ffe095746519581912885',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceDestinationKeysTest.kt': 'd3b5ffc8b18e5453bd097d072ae21cd116cbbd84b706677a9819b39d0bf47798',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceDiscardAppIntegrationTest.kt': 'fbf52bfbf16668b3972578d08e9684b3c4083978dff88b83ea2a83f475163289',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceDiscardColdTest.kt': '393da0509980e1385479f7a594c6cfaedde9ed5d51c07b6a16f6d316b43bb404',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceDiscardControllerTest.kt': 'dad450f253e6fe14f4369a876e5d89f19e78de8d3090b5f8cb1d8ea6add2123e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceDiscardStorageTest.kt': 'd1e456e0aa1bc59c9e8c00fac51c63aaf709d74bbb99512c4fffc43db64aa832',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceFailureStyleUiTest.kt': 'd8abac7778b6a7c576ede288f491c8ba9f60faa75cbff02cca404925e818b20e',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceHintUiTest.kt': 'f23f2b266cf0627ba9b5ff4a20e4693bce384da6da042c9b57001dba2ea7d107',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceInteractionUiTest.kt': 'db35756dc33739ceb899457f3ff39eebf8c8678c31482c431a92a7fcb8404c45',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoicePermissionLifecycleTest.kt': 'f5d055c59d387a8f63910be6b1893dbc1fe0306d4c22eadd2a3a4f3ccaae63ee',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoicePreferenceAppIntegrationTest.kt': 'f08ea7afad1ac0b5b4c514c8a35fcdb69df69903696bad4fb40166f2a9f90c21',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoicePreferenceLifecycleTest.kt': 'ef79befb3b91967b3c13b6274979ef6c45c4248d147e861998bbc45fa64c7064',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoicePreferenceStorageTest.kt': 'cbcb23557d7e1a21911e13da45ef28f8edb2672b4c407a0b51fd13e39b03e1d5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceRawGateTest.kt': '6092332a410fd8ddf0e411ae1559d8be3709b175a925b3c616ef6be6a55c76e6',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceRecoveryTest.kt': 'a2351ae7f2d779d4ec36456d078c3e86e0355b9125cb1def0302331bf44a4831',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceSettingsUiTest.kt': '9c5accb3a494668d6daa57b63eb78166aedadc45c570341f8dc4a1748bdc36d4',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NativeVoiceViewportMatrixTest.kt': '7dc5933cb5e8345ae27f7291dc3fd1f690bcfa386c18a3ae4af8bfb4ed356317',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/NotificationArchiveFixtures.kt': 'c0326fa23adfb0e76dae2e6369ff912728091215dc185f8d6e7bb3848f26a0ed',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/OwnedControlAcceptance.kt': '9dfe2693d5b21924cb294616c7071f9a73995c3bcbc512b370acb84155df68c2',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/OwnedLateCallback.kt': '87aa7dacc8db5f1aa58deb134b205685f52eb8969c115e9aa9e746195f313af1',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/OwnedMenuTouch.kt': '27dbb2471e9b19dc975b55705ee9342cb18ee3da04f6d82ac605f08bb7aaf445',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/OwnedPhotoSelection.kt': '352c6c2ef40c114be36cb2efd36d9704638d0d2745d62c89837152aaea4f1332',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/P8Api26CompositeProducer.kt': '19dbca9f183fea6271e3970ec94f19346098389440f0002f894a1b87e8a2a14b',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/P8CommitAcceptance.kt': 'eac88d9ef32ac0eb8b976b7d39c12e6820dcb1880f3830a029152a092a87b010',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/P8FormTouchProbe.kt': '24b1a917e05d527ec94826cd110061d0049f9a156d6ecbf9d867fbbd1c6f841a',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/P8TextVisibility.kt': '86ad6b87ad89443ed975d25dbc0e0779fb1dad16ed9216edc9a54dcd9f9ef33d',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/RecentPhotoHintAcceptance.kt': '8bf98987eccec10e83dcd52bd240edcf2260fcd3e38d4eec22feb319580bd0a5',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/TaskHubDomainTest.kt': '04dde23403282b688279276fbefc7b3943a6fb2c8ddf76d06e3b9993ccc68a30',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/TaskHubLargeStoreTest.kt': 'e5dd87ebe6d4b378b8eff990616d20c18b80173e6042704498474fb721d61353',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/TaskHubLiveSessionTest.kt': '8c36511072f710c66fd7469ce3f916796909790f14c7c97f5b04bd76f8bc1c44',
    'apps/android/src/androidTest/kotlin/com/agenthub/android/TaskHubTransportTest.kt': '685ce5c2511d8038dd41d4dc89c6ed878b5285658cf1b3dbf6d8d9a2b871f662',
    'apps/android/src/debug/AndroidManifest.xml': '52d1002b07e7db60a35cd6727ff5e7cec43bbb5156f8fa486e54309fbd40f0a3',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeChatNotificationGraphColdStart.kt': '84a987c36d5562d5860295c64394f633ea44e61bd25f995df0de33893c72d0b6',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeChatNotificationNaturalFiles.kt': '181a27d4133e126c2d90c3f9bc1a58ecdaa3207c65f61f1ce447233ec4a3b9f6',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeChatNotificationNaturalPermit.kt': 'e0d2155cad422196096c9897ca530f0235b41dd9f8f49bc7ea1c6d3508f9d0cd',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeChatNotificationNaturalStore.kt': '97da6a164d675424090cb9cde5504e8086a06331c6d613cc48e21dbf1463c21b',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationInboxOwnerActivity.kt': '49a0f3482cda6a1a07653260f379d60266cf22752c62b0ae1996cdc0fe7f2666',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeMigrationProvisionalOwnerActivity.kt': 'ab3b398a0e1418c8c0407f3584f471dab9dd20c795e4430f4b0d63772077831b',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalEntryFence.kt': '923b194e253184de52eeec2f2973146066f2e1e1546e54eb260f32eb374bc49a',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalFamilyInventory.kt': '93dc2d638d1cdad95f6ff9f7984c2bedc0144ba95603391839d5418276fc6822',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalLaunch.kt': '267370f8e28899c204a69d878c5076cbe3304c5961d98c16c8aa8f895467be3c',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalOsContract.kt': 'ad3c6847dfc819e8117fc1f337ecdcdf962a39a5bd90ed9ca349e5e27f8cbcc0',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalProbeTransportFlags.kt': '605d2ff583af53eaf055390689515d97707ffe815e81f6a6b53871d64e7550c7',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeNaturalStoreProbeService.kt': 'b06df82bcb8d956bfaba0243d48e88ee7a03db46e389f38af65877ab61242b02',
    'apps/android/src/debug/kotlin/com/agenthub/android/NativeSavedVoicePanelActivity.kt': '0b16797ceb08fff3120439194d5729dea561671c15bfa8c82f57734336f14e4a',
    'apps/android/src/main/AndroidManifest.xml': '03c4e71191758db71b822166418e79a881cf5cc0a850a55ee4d7a3a81f842309',
    'apps/android/src/main/assets/SAVED_VOICE_NOTICES.txt': '4a63e1b5e8f181083fc1626fac8c95cc090bcf31c17910f41e89e25c6206e1a3',
    'apps/android/src/main/assets/chat-notification-sounds/v1/attention.wav': '6ea2939f779d5a29b1d65f811f29811704cfa6cc58ac576a9a55e05a491fdb7f',
    'apps/android/src/main/assets/chat-notification-sounds/v1/chime.wav': '153716aeb2da8febad6e5a4ce79c7d5f268ccc0753c706864edcd9941132c3ec',
    'apps/android/src/main/assets/chat-notification-sounds/v1/soft.wav': '3579f427ab553b0fa3e71b1433a376b98acab91cae2d5a1247d9fa9ba4fab926',
    'apps/android/src/main/assets/saved-voice-models-v1.json': '70003a068c5ebc69e7babc77e5c3009a36ec4bd0e68714d0e37d96e6c9e92f42',
    'apps/android/src/main/kotlin/com/agenthub/android/AISharingConsent.kt': '0737a797da47bff91e196ae5ea4b6a7733b095ace26fdd9d3409b51d53fb290a',
    'apps/android/src/main/kotlin/com/agenthub/android/AISharingViews.kt': '1e7697df7167fd32b9e3d24174b694a5c8209df2725e9cb7ff5d2c8748967095',
    'apps/android/src/main/kotlin/com/agenthub/android/AgentHubApp.kt': '440b620c23e4670001e6a8ab8559c07d30f0495f51358cc93c6e83ff64a558c3',
    'apps/android/src/main/kotlin/com/agenthub/android/AgentRegistry.kt': 'fc5931cbbb8cf2f87f86fef4dcd8131891ae6a9cb291ee5a559cb5404d346968',
    'apps/android/src/main/kotlin/com/agenthub/android/AndroidNsdPlatform.kt': '887bf42d8523b4687830464d4d790e6f32b2958ea410db089d9d1bb7920e9f4c',
    'apps/android/src/main/kotlin/com/agenthub/android/ArchivedChatsPage.kt': '8d83ee7cd247878e34d262dd1491766914862b47baf2483703f4c049a22077d6',
    'apps/android/src/main/kotlin/com/agenthub/android/ChatHistorySearch.kt': '490e24d782fc3e129a58f504f5caae46715d23c804ed2190abf73726235772f2',
    'apps/android/src/main/kotlin/com/agenthub/android/CodexCommands.kt': '5b07bb68cd4122380922888d0b7bda18708fadf9c1f7bf7aa348259882285e2c',
    'apps/android/src/main/kotlin/com/agenthub/android/CodexConnectionBudget.kt': '9b0e403c34b7d284836df3a126ba47488598c8fcedcb589bb32a850339a38ec6',
    'apps/android/src/main/kotlin/com/agenthub/android/CodexConnectionVault.kt': 'b14ac3e948d06927b95c4152a1617261ed0f51954e76c82000dc2f6c0417e4aa',
    'apps/android/src/main/kotlin/com/agenthub/android/CodexDiscovery.kt': '2b201d001db6f638fe3e4a527be69e87c99d81e3d9e1bb1d7de0760764881e22',
    'apps/android/src/main/kotlin/com/agenthub/android/CodexDiscoveryReads.kt': 'c715b71790c4ec810346a2564df0ee90187f50e1ca06877bdaa55288b9a84c17',
    'apps/android/src/main/kotlin/com/agenthub/android/CodexPrivateConnection.kt': '44877f9484d46b7712f6f0914d1566bb527da2d4354c55679f658f516dd6d491',
    'apps/android/src/main/kotlin/com/agenthub/android/CodexPublicDiscovery.kt': '7736f2ab6548e14043ad1883434f330bacf627efdb8ad11aef6025a1a9bbe5d4',
    'apps/android/src/main/kotlin/com/agenthub/android/CodexResources.kt': '6cc3abe3dc8ab42a37db8a3a58ceac0643d5f494e5955faf258f83775d6ccd2e',
    'apps/android/src/main/kotlin/com/agenthub/android/CodexSavedRouteDiscovery.kt': '13d5a9e97b9225a763554cd55d5525d2c9807008267980d2e8f484582734811c',
    'apps/android/src/main/kotlin/com/agenthub/android/ConnectionProductText.kt': 'eab536fa75b38557f4219d9f32632d4d89df21d324acb1966ad27f004860de6e',
    'apps/android/src/main/kotlin/com/agenthub/android/ConversationSynchronization.kt': '6b877824cb60d0700a602238e4a55d00607425477f26e06ae7175b7ab5a93b08',
    'apps/android/src/main/kotlin/com/agenthub/android/ConversationUsefulProgress.kt': '30b86b25a32d0a2e397eb3f03623ea3fe618a353736ddef49ab143a0113bf390',
    'apps/android/src/main/kotlin/com/agenthub/android/DesktopSuggestionDetails.kt': '11baa6702599d8f67c5928813f7b1f818a85e51fcf7cbbce8ee793ee97e19bb1',
    'apps/android/src/main/kotlin/com/agenthub/android/DraftAttachmentStrip.kt': '7d63ada719ec193fbcf4a1094be8c899baae4f31130eb19f90bae803c40160af',
    'apps/android/src/main/kotlin/com/agenthub/android/DraftAttachmentThumbnails.kt': 'c3a6c1ebf19ae674b7935e016aac06aecc106aacab4d915f3f55e7b95e95f475',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkAgentClient.kt': 'ec2c28c5ba9a815e29ee1cca19385f4cc8244a7010a90fa4c254314d9e85b50d',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkAuthorizationCoordinator.kt': '478c572ab47a6b7a328bfdba0fdeb2bf03708b0f556c35e81f0b2309602bbd6e',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkConnectionLease.kt': '305569bddc68620269a0a33629ee8d0eda39ef0358bd027c222fb6496f4924db',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkConnectionLeaseBinding.kt': '6a42b37ee1e3b4ae6f995205acb1799ad5a9085cff6366ffb43594fcdcc1a4bb',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkConnectionPage.kt': '7b3866e964fd7d16c6a2c0799893e447ff456fad13e6dd9d98457432238bc505',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkConnectionPresentation.kt': 'c1b963449fc765feaade62d8a05f7d6f3b475ffb062a770d3343a15dad4415cf',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkConnectionWriteGate.kt': 'f0f222b1cd46d5ac4e268b3096e8d0378129a65de8e79377c8dd5a3ceba4ec35',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkCrypto.kt': '634d513b079c46587e68477d6fdf842113ec17fa48c6e1f0ade61bee39a7e795',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkDPoP.kt': '255db62be9e99719c7f2c2a3bdcac8b9ac29b5f5a90edf44de6862e5ff6e03ba',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkDeviceAuthorization.kt': '1b8681f4fb686fe877eb914e74260fc825dc65fa764e9c3ac938c9ca49ad2256',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkDeviceDelivery.kt': '5df81fb9bc5846a8550bed5e20ed529aec3529a21a2be25dc1e835866200c686',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkDiscovery.kt': 'd9d24b2d56ce22604a7bc72169e56a483d089ebc82800ccc1708f4ecd2c14664',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkEventSignal.kt': 'a53a1eabd7104d3f573aa872e4863884ce1934ec0935ea413d6cf8b0e5485f19',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkLogin.kt': '0b63f3f51435986be1f9b6f413a26a31dbf9a7ce6fb25789e81f183d4a0db99d',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkModels.kt': 'b5bf7289d410341d24f1363773866902dcf5f8eb23ac670ef428e62ead78304e',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkPairing.kt': 'ec380593cd0bde268c3629d76f05bf9ada5633477a99627dbd9567df7a9dc889',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkReceiptNormalization.kt': '9f8987f68589a04527d88fb7a344d6ff2a0b017b8f57d28b506f7a4ea8c8ffe2',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkSession.kt': 'b1765118c85bcba5c70dda0804be297b671b6724459ec6205e72385028d45150',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkThreadSync.kt': 'cc56d0a9ec934af98213009098b4bce220e5002d255af4f7f603aad50ac5d0be',
    'apps/android/src/main/kotlin/com/agenthub/android/EasyWorkTransport.kt': '1b1278fa17b9bbda414045b11539a788b0481a2275f0c0a2c6e53987b1f8ccc9',
    'apps/android/src/main/kotlin/com/agenthub/android/EventReadFailure.kt': '681270894b4cc1034e42949c7bf2b0389c7efa85ed20bc370c7bc80bb6fe7ded',
    'apps/android/src/main/kotlin/com/agenthub/android/ExecutionPanels.kt': '4d84a873bfc0fb86f72c1af1c813447833540dec611a11078ef886262f6931fa',
    'apps/android/src/main/kotlin/com/agenthub/android/ExternalAttachmentInboxPage.kt': '155c1b55eb4310698101e7bc370d0b949497d3c1798e98673f93b846a0b568e9',
    'apps/android/src/main/kotlin/com/agenthub/android/FilePreview.kt': 'eb386a32f24bfde0e2691dc6ffe81b11df310a56cc90c86761f16ce07b854530',
    'apps/android/src/main/kotlin/com/agenthub/android/HostReads.kt': 'bba291e0f62a8c8667bfdb3f49b4ca267877c47bf56905be96893d840258e9e8',
    'apps/android/src/main/kotlin/com/agenthub/android/LocalAttachmentPreparation.kt': '333e114d34d599c218b06bd201143e39356c5ff66169fb711f39f54c4b98a04e',
    'apps/android/src/main/kotlin/com/agenthub/android/MainActivity.kt': '78fbb70c48cbc306c7e2e96ebb3db915c03b68cb0eb10511ab02b39149166563',
    'apps/android/src/main/kotlin/com/agenthub/android/MarkdownContent.kt': '0abddc0bd29057cb223062a6afaaa48b9c31a21bbbc98aafc88241e973a2b9a6',
    'apps/android/src/main/kotlin/com/agenthub/android/MessageActionLayout.kt': '5f280329a581ca85b2a3850647ae8b39660a2dbeba595da401f9991b59462527',
    'apps/android/src/main/kotlin/com/agenthub/android/MessageLinkDestination.kt': 'b24cdd524d673b7ef6f2c92e9e957442dd4e884b08623aefe851adebc9f65113',
    'apps/android/src/main/kotlin/com/agenthub/android/MessageLinks.kt': '5470564fe4e686845b74c1f0e0dcef53b18b9f6c675b18157eb58caca2ccdfed',
    'apps/android/src/main/kotlin/com/agenthub/android/MessageResourceCards.kt': '89e4db1617c3a8b69f6b95491c01a38c924fc24eb3e4d04cf1543632b1c1e505',
    'apps/android/src/main/kotlin/com/agenthub/android/MigratedEasyWorkConnection.kt': '2156e0b3af352c3e2f47b6b493afdcd01b0eb11198ac8d499503dd087ddbe9d6',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileDesign.kt': '77f04e662d589679d7760bb1f282da1a6f0979dd14cab1cf31591f8d58da7c58',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileDesktopSuggestions.kt': 'e606a429ebf24c9815120369ccffd5ce92a7388800d07888fb289f01cc27e563',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileRuntime.kt': 'f6ad26f417c46767d18a80f5d5167dcea83d2466d1f73250a70d057a8674e599',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileSession.kt': '82a70e353839fe75836c02edb68d5d13b52e70894c1ddfb18917b3943bd30862',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileSidebarProjection.kt': 'c3420a8800e3dfc12fe8868d15869a5964a78cb9c696082c05afdaa6be25a338',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileSnapshotCodec.kt': 'f22204d67697c270c53802d2c44400ba639316c416c35b55b2acbb4f631861ff',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileStorageIdentity.kt': '33a6d0787fbae4e658cb0411dc8794e501c37c95b273f3165125bd41b79714c1',
    'apps/android/src/main/kotlin/com/agenthub/android/MobileWorkspaceMetadata.kt': 'e6e981f8f54021340d0af48e3c3afba27374d5eb3e94725e60ffb523a6b17ad4',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeAttachmentImageViewer.kt': '6a02ffb7c9f948b215e30114bfb9fbc972736d86bd55d8f350a7971de5c20709',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeAttachmentInboxStore.kt': '5126fc8ef1003d356092fbf3d8c5ae476abb4779638c3f2b9dc0fb8d730dcf3a',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeAttachmentPanel.kt': '592dcd4a0f18825101a8e1e37922e732360316bf77ff7ae076555fbd99dce16b',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeCameraCapture.kt': '7225bc2a7158578869b18817e0f83e2d3615d931e73dc9bfcb9447f371b08317',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeCameraOutputs.kt': '48ae48a049c149cf144e52796c28deabfdea132aa0519958acde722e37f66e64',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatActions.kt': '966fddc52e271e91981a2752fe2a6a59cc2f01c8775fb2bb4a291563f6464218',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationAcceptanceScope.kt': 'aa430306afb43202544d67cecbbb57679c103ca6b8456c7d62332f7f5a237d9b',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationAuthorizations.kt': '1adb14aa1952c1fd2390aa06a23ff7872fe1a2dcef804f1fa187725ce1d377b5',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationCodec.kt': 'eac9f7ff64ae81a7e0c8d686fa68cd09ec82d8354855d87c46e2b85ae039e6df',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationColdPlan.kt': '00f8aa1c2615e31990dff14634b88fbc42b1c3788d3f738c0b2b03c63ec9f052',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationColdPorts.kt': 'b5e6374e12696a510ecf934b10683c6d39ca1891112fad5fe7039d76af53987e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationColdRouterFiles.kt': '68b358557f20c004a9897373fe873271ab3958a8514c5119e75bcd6a4fc26275',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationColdStart.kt': 'c802500c99d17c83bfa74458f70b0e6ecad9910a754a4260541e06ce8b2e217c',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationCommands.kt': 'd3cb14e7ff918b170fbee66c242422c4b061e6e8f8550c004c67ab05e067418e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationCoordinator.kt': 'cfab9fe4581702256b1316e980f2d8a8819c9b3980330792bd2eb31525494cf1',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationForegroundDelivery.kt': '190f6cc74a55b905354b354f2c393775b5a7556aa1c160877eaf6e78c6cc2deb',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationForegroundMonitor.kt': 'e8941c44c80af7fa8eb6e4235d2eec765904f709ff425d3e362e5c0b9eb03794',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationForegroundStore.kt': '0624cab48fe7c227e65461cbf8fbe4e270c8fc553520795a7eeee544d10d27eb',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationIdentity.kt': 'df819803b2bbbfda7032c708f9840dba58bb9028423bafac2ca1baef1ba0946a',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationModels.kt': '337bf7c419c1c5a8aed084e91c8103c6efd92aaf45b9109ee45aa1285c34f104',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationPcm.kt': 'ac3c2ccd4c1d8e8e766d9720c8d3f29b5268c3e1418b69f5b9c05e91421142c6',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationPlatform.kt': 'eeb55e8ff726b8b7f25f5789291b10c1ec01fc78427f64041d458a3744ea17f0',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationRecoveryCommands.kt': 'f201d241634a039d7d5ad06fac94139bb4e18840d6c536c9a3611d0da6647b66',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationRecoveryController.kt': 'f0c0fd913a0ab8c14b7322731f939f03d2a05b7d8d7cf2209fa42a0bb10a0206',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationRecoveryStatus.kt': 'b7473264138dba410629f02b4edeacb7d632f738d538fbe58e0487a582205d0a',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationRecoveryStore.kt': '27c55bb993a4b7e5ab8499126afbccbea7e1027ea4c4f1b5c9be83139474f309',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationRecoveryWire.kt': '2c9c46ea1a3df2f248d4f96c640dfc3e145ca4fa95ae49a159049968ef4f266a',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationRuntime.kt': '024890eefb8673cb240e6de90df8179b654cb6d673e39c73f220ff1de6885685',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationSessionPort.kt': 'ccddaa9d2a66a1d8b2152c0675a74047291e0a6fe5a8ceb9cc230443b1297c29',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationSettingsPage.kt': 'ab6a85ae4cf5dd15a1bd1590a34daee3854c3f145a789d650467809cc05ad46e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationSettingsState.kt': 'f9a371bae96954acc8965449a62756aef95795c5959855d107245d0008bd06e1',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationSoundDecoder.kt': 'd3b6ec51e38d4a39f21dac18d766811812b69b854d83ad3ffd0482a26c65c8a5',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationSoundLibrary.kt': '45c32f934d01137fc7c1b9f50b9cefac2587282e847ac51d61b0a268d18d57b0',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationSoundPreview.kt': '561e13b60f421fa97004840b1cefeea49820ca4971532abbde39eaea9be15a56',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationSoundProvider.kt': '3630254f8d3a3ce018fda43f36c9224d734d6ea85d37cdec24508f3d34728c3d',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationSoundSettingsPage.kt': '991bbcf01b9cc9c365c4c4dce5072ea456ea5abe733a7b3e6421f61bf72ab90b',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationSoundSettingsState.kt': 'fd396813c5dfb8b3b545563b1bdc882e186c65e7b4e82acdcdfd00a448d26d58',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationStore.kt': '992d804fb4d2d2a4f814ee1944c01a38bd27fcc14e02e2a967c8be8e2019838b',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationSystem.kt': '835c2765c0f2011ff1b54a25805e10e34afc4633e1766dd2c2cac3f4fad23b8a',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationWire.kt': '5a5914b682348ac29cff55fc30c15ef17e2998b3ba73aa653d41087e06fc88ba',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatNotificationsPage.kt': '3cf9ee2a50fd1b08270c2a8afa80197620b9fc90d31947e28113a64a4ce9df1b',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeChatSearch.kt': '21e29384d189be3fdeb7f73bd16fcf78e250953f7aa3bcb4f5fb6890ed8783bc',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeCodexCommands.kt': 'cf6588f4fe39475e923dcad920a893fe642e360f9be8e46e198b28ba26232ce1',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeCodexFlow.kt': '29f907683a920a0c711cfcd8012e0ee458dc99a1f6fdede9d920d72d8d989b65',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeCodexFormAction.kt': '6f7a2ff1360ce4f75ccc570252bd954f2b3fe425fb56fabeccb0072ba9f58cdf',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeComposerTextOwner.kt': 'ab4aee62af16b543ca7165b172c75da0d1c11cb3c31308f3665078a5725c0ede',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeComposerVoiceInput.kt': '661416d3887881ce93dda308417099a430b62c6fa6666de76cf4192dc5c16cde',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeDesktopSuggestionReader.kt': '9bf47fa630596e6fb68815b98a7bbcb341afd647b260d72027f9986a07470283',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeDocumentJournal.kt': '1cb5d489b6ca3986526135f561356a3115f0943616c629da9c1a35e24649bd6d',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeDocumentPicker.kt': '643732f76dfb49d279995e0e222eda2edd8b3b5f406676de7a3e45693ed7ee34',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeDocumentRecovery.kt': '26372c03054dc2288bb2e660c8da4dccec9745d868738c0ef75a2aa212c2f4ee',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeEasyWorkFormAction.kt': '9a27edacef80c402af0bbc2442311ceb8f3f08501831650e8c6c6fb7c9747551',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeEasyWorkMigrationOwners.kt': 'c5c65795d463d54ad17a90fbbe533cbb2b8cee2d5615606d482d9d26a932b271',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeEasyWorkOkHttp55.kt': '8885bfea167debd7e250b64a7d90532fe7cbe42281fa0e091be7dbafc0bfa720',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeExternalAttachments.kt': '39203f3713be8d0b054b04764df7eb96ef530c2e553083cb72e45d20f3c04d67',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeFileIntake.kt': 'c4018c4e6163380b13d34a356831fac6d49ee7832b48406ec73159d4b5a3217d',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeHistoryCache.kt': 'c3a070e79c397bee71421b01cbecf6048edd0c3351f5bd2f4775eb924d07c436',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeHistoryStrings.kt': '250ad3acbfa65ca3e3e70570483fda6f673d00eb43671ce6dc1a8f5d1aad592f',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeHistoryWire.kt': '82e1f4a408f3e92bc86b8ab874c9d709bf533e6b1ad3aedc1a9ec6f7ccb66e1b',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeImageOrientation.kt': 'a0c8049a392b988f14d7faabe924974e54cb6ff4932cb5c5130aedf889644c55',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeInboxOwnership.kt': 'e225c2ba9170ad9bbf871d97728155c51d218055ceaddd0c20a1b43b54c16147',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeLocalInspectionFilesV2.kt': 'a0ab0b2755b8adaba92e274556512a8273abf0529dba5ca6007d2aee520a7df8',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeLocalization.kt': '02d8c9eb61f87541963ba1ed819a02d9ee27f65bb96e68e0c1ed9064d1c5f58d',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMarkdownNoticeProjection.kt': '15321859fbbae275ea142539f572e0ab9db5187b65d4909f527c3c8b1cdb21b9',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationActivation.kt': '9f8239e40506ace7686675f5e40aa700ae27c475e991fd326d33b37d0d4c93b1',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationAncillary.kt': '6fc8925259dc8945805fbcb52e15b956554b9654b828b4dbe1f90aa4b83028ab',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationArchiveValidation.kt': 'bd81c92057a81e102f7794c2779313df2cb2d2a741e4e22aacda54703272a5b9',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationAttachments.kt': 'c1e8cfffdfa5f6b9da8676752001fd300bc8853fcf9a471b29e4b00120015dc7',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationCandidate.kt': '393c8aa81b1bacdedfb6c886f4f8712a2602b9ee9fa17372ea33a090861c5844',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationController.kt': '329236edcac18f8663250080abd1e18b8f2a4d63bd6efb5ec93a08f0f46f4224',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationCrypto.kt': '4a0bca195db5f577eb017b33f33c768c446deb2816d252d607bbb380b36295a1',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationDeviceState.kt': 'b956ba404125d7d021327fbe75c972f17296fbbd2654dbed1c450736cc90cd8e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationDocuments.kt': '5fae731f1dc053ed791864b8c964b37c7a6d68790077c02d26271f21b88f851c',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationEasyWorkArchives.kt': '33ef1e97a31c34641e3d025662e4264d8896e546479baa043caeb2e472fab32e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationEasyWorkBusiness.kt': '3e24ed51b17fa94a3d2631a0bd37dd976044f852260a52ccd44ffca8cd95001a',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationEasyWorkOriginals.kt': 'ce898490bd0de18fb09e2944b9a4fc425584d7e93234a0e547ce690b54d43ec5',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationExistingAliases.kt': 'f383d00bdac5f9abdf1739a0acd4d051fe52b59c0535139fffbd7a5fae300042',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationExistingCandidate.kt': 'e6cff1e2f48ed7f36853c28e02175a43573413bef3e9e2b810f7f4c9fb565680',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationExistingPolicy.kt': '2084dbd91f3032bf07e68d1b3cec37ab0774415faf8ba9f4d15537325d41aa66',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationExistingView.kt': '43b58fcc543e76c19aaea6504e077340ce8ba42a63d6a2b1535a41b6f613e528',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationExport.kt': '277480a5ceb7440957f61447d11d0964a5c7807f5f361c2cffe83f3c29a285bf',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationForeground.kt': 'f66245fd638c45e11c45588efb415042dbdbb88cf5e277f2d780cab78547d701',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationForegroundV2.kt': '597289f051565dc3c4ef92721f16e96167de4a246c539d1cd5efbbed52f231b9',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationForegroundV2Wire.kt': '08e3b573bd6987664919bd25427142946f33f6bdb56baaed98ac53a6172165a5',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationHistory.kt': 'ae4e3f86033630e7f0e8e0445705acf0b4cfa5d7b0401ddda0fee9e3f5a82396',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationInbox.kt': 'ff627837f58033c44e9426815ba7fcf8b7248318eaf44c1d4a7dc551385b2206',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationInboxGraph.kt': '2588279475922af3d9d2e146060f0c22016c83094512c50424022506dd1abe3a',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationInertArchive.kt': '03a8c1abbd193be69efd88dc3be86097d91b3f2fc12207f962313d240aec4f6b',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationIoWork.kt': 'c1c8f7c8f9644a8355706c8eb38bbbb32bfa5eebd63c5331e1836d518e31c537',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationKeyChoice.kt': 'b07713ee2507cb092f0105f024ee689adf5e92cc36af4e343ccdf0b51840f6f3',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationLegacyGcm.kt': 'b2a3a3e98e05187c047e5a5f2ba28f02faa57475409eb4d55425bdab43992417',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationLifecycle.kt': '4a37504cca06a536376e9b52aa829e02834537630d676b03beac886100005b24',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationLocalRecordFormat.kt': 'cd8e06e87e24fa0bb1e40be5f7a9e661c1b639266089907d1894371cc8b22e91',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationMetadata.kt': '2e625aae436e2812445447d45f1ae1dd0e4184eaf4a449cdd8080c400a079b24',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationNotificationArchives.kt': 'dbe1a3278a337368089c6d9ba8a50f2d8090c98c6589cc42afa31ae1a4253c7f',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationNotificationAuthorizations.kt': '9baa08b2866157c18b319befeb4d1996c940c6e0d52a73e3bb3f1ac1b4a9eef4',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationNotificationEnrollment.kt': '4db80a0c9a5d0afcc60ee47103f3e274196c9db809d37bef73b619e47f3b184e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationNotificationTombstones.kt': '5675eab3cbd0be6437e3547e447eaa03d803a94d7ea5c7ad4066ad3ed7bc6b90',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationOriginals.kt': 'e51b94da6a9f1361f74ecc6785159140e5805214584a0dea8499a308e938efb8',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationPage.kt': '28b6e5e8a3120b5e836c8395e4462dc9e4da151b7afe0e172d151b86ef91f91e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationPhotoLocals.kt': '48b09a20fcee8f0878fc4403240dd0b8ef398d635e042bc23c9ea886c5291010',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationPhotoOwners.kt': 'd5ad213d37f112bfc0a4142c059f6e56488539be50f597493eb5e0eebde02584',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationPrivateClaimBoundary.kt': '9af4ad31cc3bd30248fff9bd6f496ed7ccac8e10bca3d7d840898048629fd3e1',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationPrivateClaims.kt': '74f53574cd5daa3a33ee210e59400f76e836edaaea824620f743585bea682116',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationProvisionalFiles.kt': 'b6e5d40ff746ece5aec011dec1cd36ee4f935153954bd961c54ba676f6da9831',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationProvisionalVoice.kt': '899ed303ab07b499a92776aa5b7266917911b65e270ada0b0e963672752c19a0',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationReadLifetime.kt': 'b47f8c1c723e67378203e54ada4302d1a71d889a69c54b14f37c677718b3baaf',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationReadOnlyFile.kt': '270be9037d7ec0574943dd06d61120326cd467a3e8d9055320ebf169a7057bd6',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationRecords.kt': '9715a1748a1f9fad3eacc6a6e255035e62489a7f40d755ec534d63164dd20fe9',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationReferences.kt': '86e9c90fad0baed4981ac3db63d9e0e55225ad74fbf410118f99bf4a180b1960',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationSelectionGraph.kt': '444cebbac89bb83298a1ce1e6d02d65706fe321e7b87e6491b5f10f86256b91c',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationSelectionJournal.kt': '31b32e84b87a9dd5d4c76fc301549d7f10da4257fcb0e943d38020719360538a',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationSelectionOwners.kt': 'a24b9b609a8356e721abaecb55a0d3336bdc4e682c5f3e7be21df2ed9372297e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationSelectionSource.kt': 'aa23ac54ac407d8de346a430835dea3be794a203b58f7f497f9dfaeff638ab07',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationSelections.kt': '95bb11abc4fc78c125bf922aa9dc030899f0243c7ce91876d1530999113a709d',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationSounds.kt': '3d48debf69c5af1aa3c9102c193b9ea55217bdbee846bba201a5f5d32e1d2fe4',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationStreamArchive.kt': '84368c9bea5987f2eede870db3c69b3944daec9897fbd05668c9e102ca2d8a1e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationStreamCandidate.kt': 'd551830a6b095ff8bf55c345dfd46c7e0cc146f19dccdac74849b464486b9b96',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationStreamCrypto.kt': '48124b87233dc16ff5d48e775e39ce6f5a87c38b75f00041e0724d6dd9c38add',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationStreamRecords.kt': '496ad3c0f37cc4419ab6285a89b35dff0e2e15095a034eac63e696c1bb71ae1e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationTextMap.kt': 'f8c95a48de2235e139cc79ee423ce075fc53dd5ea3b51984ab3e116e49404860',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeMigrationVoice.kt': 'c44d243c398912a1cc968bd1eb1a375bf9f859a189dfd57a2d43903f67deefe6',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeNoticeReadCoverage.kt': '06dd12c7e66dfadaa8cd40da5be394e125edbeb81124724748c6ce2a34b2f107',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeNoticeReadText.kt': '864ed471da6b0e3afb7f326a847e8017737170cd64d66729a147d1741820e085',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeNoticeTextWitness.kt': '3c82e22817deec540c34044d5896484b3622b422dbb66502d0864db824608daf',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeNoticeVisibility.kt': 'd492bff28632028c57a3383324454ea9afcda5a7916f19b09357d84c7d27f68e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeNoticeVisibilityView.kt': '0f75cd9b240bf62925714b902ccddc303aa9a6d668d617747c423ab8ed0a56be',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeNsdLifetime.kt': '41ad1bbe19b919324a9644fbefaf7a9f048f1617dab8bfb2ba224182ef174cbb',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeOperationFailure.kt': '014d7d3f98cfe08a535242c2f2ec5dd739e2724e781a2f24fb19480b547710ef',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeOperationJournal.kt': 'c106534476fbab7e0bdb72774fcb7af738195b4ab0d98a92de959515e07cd042',
    'apps/android/src/main/kotlin/com/agenthub/android/NativePanelNoticeSession.kt': '43338376846f9b72a19b9c0ea04f4855667d2abbcee6fa05027ec784d687c6db',
    'apps/android/src/main/kotlin/com/agenthub/android/NativePhoneReadingStore.kt': '4fae0b8e626eef9917e51114207068d8614c4ee068b66e076773ae189feb1d84',
    'apps/android/src/main/kotlin/com/agenthub/android/NativePhotoAuthorization.kt': 'e8235e46888b8cc649408b119034cf09675ab51b76ded279e86e6db7a1fa606f',
    'apps/android/src/main/kotlin/com/agenthub/android/NativePhotoCatalog.kt': 'b3c406417a52c32e5f83550ad604b7e5e72e618afccea7db6bf4bd7058f42ce2',
    'apps/android/src/main/kotlin/com/agenthub/android/NativePhotoIntake.kt': '1467ab96106f1fef2582e44a1e45d0c9a8b657c3421c69c785642a454f1bc9b0',
    'apps/android/src/main/kotlin/com/agenthub/android/NativePhotoOrigins.kt': '83a7dacd76ae2dc24b266548fc7458ff2f26c3da08def98ac7108855dea9f55e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativePhotoPicker.kt': '89a07b96398a3254ea2b60a6f3e07e1ae476dfe0004b76b99d66eb817f5f12f7',
    'apps/android/src/main/kotlin/com/agenthub/android/NativePhotoStaging.kt': '5fa79211b3105cbaa7b57ea08ef12a8ae5d8d4588bed6a3f2cad3f1e8ad9fcc2',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeQRScanner.kt': '5389af59395dcafbeffc01d4cdbde48b8fe68714f622f9262421b50a2dbc3019',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeQuestionVoice.kt': '80d87dd81e964676896f0f539c9d6586f462dee44c9e91a6a6749ae9640d5bb1',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeReadAcceptanceTiming.kt': '135d2dcbcfa465f9d95ea6f4cbb98c7ee1260a602389ccacf62957693b29dbb3',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeReadNetworkMonitor.kt': 'f24b9115dba2a62fcfea103bc4877514ef2b79d7dc475445d82d63376950a72f',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeReadingDirectory.kt': '6627529417572612a87781b7ea9202f76ee2f8b902b6cf202059f2c97d4101dc',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeReadingLedger.kt': '1bfc01e3ff33fc21e3ac4a0b590bfd5a007b6390d09b8dc5920918b4b2480e09',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeReadingRetention.kt': '91d353014bc5ca079091046cf8b352351977d2ebbb686c513c7a5e86b58b27ce',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeReadingWriteFence.kt': '2b12a0fa079d0460e75e66adea649df944fcd142bdb1f2dfefc0778dae224d63',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeRecentPhotoLibrary.kt': 'bace155518d074c1e9395548ff69020c4c7e05157e7ff08d7c3f3be429f1d713',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeRecentPhotoViews.kt': 'a09c6aeb2d6032d6b175a46d845032cdb52d907ee18da80d21c5eedd37959830',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeRecentPhotos.kt': 'be1b06251fad7cfba6ff2bbbcc219170f1cb56009d9961a757149e5457b27511',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeRecentProjects.kt': '3c39346f111b2c4e4226e0dade6e0fc9223de6666592f5bbf985d617e2a715e1',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeReplyRecovery.kt': 'ef42b8183f276af5d86bbd1bcb349e1325cf3df567f8dcf8ec75e00db80aab7a',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeResourcePreview.kt': 'ea70951abcfe2f2df4125ad0af6b715ec29d01a157b90f0b0c95957fdc442133',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeSavedVoiceAction.kt': 'ca34726c46853114859dcbe6ef195802b683919d0eb3ae634c83e9d0a4a5900e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeSavedVoiceDownload.kt': 'bc5654783c5a1aff44fe5eec874ecb13f74080badc55a7ac7086844d7e1abd21',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeSavedVoiceSpeech.kt': '16fffd7877d57caa460da23068f8c47d787b455f05f0bbb390fd67daeac8e867',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeSelectionKind.kt': 'c4cf4165e4dc14e069af7a03c3feb283eda986f42bbe3ceb572bfd6fbf3be4f0',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeSelectionReturnAudit.kt': 'f9b79477f4865cf18dd3f4512a05034e9f93d07e6926d277a7ff2c3663bf9ccb',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeSessionStorage.kt': '7248a18e7999cddc03c64ab418ce8e9636028a6dfe7d5da75d36d4bdc8d96a0f',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeSheets.kt': '6f1c2c280a53fe08fae125aa28c6cc0e3fc1edfa8f209fed06ea52d2c39d10b8',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeSystemVoice.kt': 'e2f51431330ccaa0faaf2f3a0069eab2bc2838e19585c0c809b4666db01cdfad',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeTheme.kt': '52ba1e7943ef1766cdbeb42a94db774ee6797b02138b20f8d149d1fde4afbbdb',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeThreadFork.kt': '94d08eb3d1cf4f36002f1315e0095329336759445baa1c16e2e0a19c6ef84b56',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeThreadReadingWire.kt': '69376350387f794903cb78e8798ae14e889fb71462f9ef82eaaa28244c77c7f6',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceCapability.kt': 'f83df737981d08015d23c36681ac9b6ae0a07e93de314225fe7aa9fb1b4ae9db',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceDiscard.kt': '62ffde4d99adf9ff0a6b687680b30afc37488b421ceb68d12850638156f6468a',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceDiscardStorage.kt': 'a3bfd1429d88bd84af204eaa922aacc1fc6d79b16edc6620bcf561ecc22eebbb',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceDraftDestination.kt': 'd0db1d70267fceba7dbc0f0fdec95f73b8b98c33eb29b6201c04eed986a113b2',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceLocalResult.kt': '60dddfb6a5e006b6784bbbdfdd7757dab4b77b78843e86196e1e2e156924f6e7',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoicePreferenceStorage.kt': '396ff73f387e665e82c7a3344fa1bc3ea7caa4924fe537f189b7b70985a28ecb',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawGate.kt': '0c1d6eff93ce5bbbbb1bd93c7c1b56d66db52ae799553b9f11555afb925837ba',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceRawOwnership.kt': 'e3bfe8cb5d1e714e51886fb9f5b86011e5b6561210b0d047e8e309da46fa320e',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceSettings.kt': '588fe9d505bb689a1a354bbed4912e91bc1bdb4cc26757e67e35b98fd7bb84b7',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeVoiceSettingsPage.kt': '1e114541a700cf85ba18b6ec311fdd6cab6ffbc6d380a61045e289ff6ebb9cfe',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeWireReader.kt': '1997e46c1ee36e2bc6d49d75b6034781722d0ea66caff6b687b1e057ca0b603c',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeWorkspaceArchive.kt': '00c972305033d2a962d7a0e8aefe5ba5cf5c56fd15fe974ec6fc1ab71e7bf8be',
    'apps/android/src/main/kotlin/com/agenthub/android/NativeWorkspaceThreadDirectory.kt': '59f0027f7946e7b7bdb7a225c9de361d70a65fabb49bec2c476204052abd3a58',
    'apps/android/src/main/kotlin/com/agenthub/android/PendingCreationMessage.kt': '5a8ff246d9e5508a6ee275a045b4932f7753fcb63fee5c374c360ee2dada1fde',
    'apps/android/src/main/kotlin/com/agenthub/android/PlanPanels.kt': '934822b44b8fa9513cc6365f5e649901de27f41689ffbedbcfa668f384c5aa56',
    'apps/android/src/main/kotlin/com/agenthub/android/ProductIcons.kt': '5990f5475958811d1e1838a5d2144f3b9459c3eb3b163bf0bf5e834851c7899d',
    'apps/android/src/main/kotlin/com/agenthub/android/ReplyDeliveryRecords.kt': '165d4aa36b75324950ce6148af80409ffc049d3ea3d4df13b7e2c8d22a9c3139',
    'apps/android/src/main/kotlin/com/agenthub/android/SharingProductText.kt': '40973ae44e312602801d208f10a36037e35be88980e94c33c84294787a494d4b',
    'apps/android/src/main/kotlin/com/agenthub/android/Sidebar.kt': '96ea0a07e68400c088a059ca57141d07d9824a8a7942fb1f3c7e0c1886a39776',
    'apps/android/src/main/kotlin/com/agenthub/android/TaskHubClient.kt': '0836ce7d3804f4b97065f102e7f0cb58cc5737a5f456fe40c87b3caeb03c9230',
    'apps/android/src/main/kotlin/com/agenthub/android/TaskHubController.kt': 'a65f50bec2572553247ba2028520d1477a276c965f2871e7598e970a6eb81706',
    'apps/android/src/main/kotlin/com/agenthub/android/TaskHubModels.kt': 'c856ffaf1648be74c13b5a6785a2f2b56e599f13c325d9c30cef473300496c95',
    'apps/android/src/main/kotlin/com/agenthub/android/TaskHubPairing.kt': '7f75d092dffc87004848d3adb6299bfc884d3026580c7ad2bc68ca3d3ad2b3e0',
    'apps/android/src/main/kotlin/com/agenthub/android/TaskHubRepository.kt': '18e824f8562f4155e702def8cf8a630d4d740e15e9ec57a29ff82f5d8c10b205',
    'apps/android/src/main/kotlin/com/agenthub/android/TaskHubSQLitePersistence.kt': '4eb6275710684cbedd3241ca8b20e231b56f1461c0cf5d8784cb647abf2d86a9',
    'apps/android/src/main/kotlin/com/agenthub/android/TaskHubUploadCoordinator.kt': '8e664e59d0b6da4ffa41ed69c9f4f4747e5e2c4959f05d8df62aaf3ffe29aed4',
    'apps/android/src/main/kotlin/com/agenthub/android/ThreadForkViews.kt': '4a09c14b16579e493f03c6950ce7feb531d04cae3f93f60f69aca5e9ab61696b',
    'apps/android/src/main/kotlin/com/agenthub/android/ThreadReading.kt': '08269bf48dea76f82d209ff251aa1ea3f1304893ed350c881ee94babfe99f280',
    'apps/android/src/main/kotlin/com/agenthub/android/ToolContent.kt': '61457fafe12a02d8306e05edad3cd7c9504111154f3b6a9cc97ffb3b9c0bc8bf',
    'apps/android/src/main/kotlin/com/agenthub/android/UsageViews.kt': 'e8851fbf02d4925d42fe7ea02263d0b23c28d410833b19b8dc2d1119d237cb71',
    'apps/android/src/main/kotlin/com/agenthub/android/UserMessageBubble.kt': '81a3a3842b8026c107cfbe433f3cdf39c5492c671823cb14bbcf1e6b414ea690',
    'apps/android/src/main/kotlin/com/agenthub/android/VoiceFailureStyle.kt': '2e493c442bac6f58427ff38fa3e777a8dc13ea3fd3b3c28fd74d794c630e786d',
    'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputController.kt': '76da2f84f30b1a403689b4734baa7a84a8c90e2348d936e5746ccdff230a61f7',
    'apps/android/src/main/kotlin/com/agenthub/android/VoiceInputView.kt': '0bd6cd16493cb3b6790532f433194b486340cc6bca23852bdbc1bbfb7d732a21',
    'apps/android/src/main/kotlin/com/agenthub/android/VoiceRecordingStore.kt': 'ea4b3a66991486c250416b5fb4555b179a4888fb5d4159bea48dbfc52cc087df',
    'apps/android/src/main/kotlin/com/agenthub/android/VoiceRecoveryControls.kt': '1ebb42002b38c3298fba3412b197a6475a31259d518420bf91a3bfd70a09908e',
    'apps/android/src/main/kotlin/com/agenthub/android/VoiceTranscription.kt': '80b73a3c13b754d763cee9902c9dccde03a94d56a090658a786f8bb0f7378560',
    'apps/android/src/main/kotlin/com/agenthub/android/WorkspaceArchivePage.kt': 'd152a2c87330befaf59b4e6f185df9a424b8f064e061651e39967588a3639516',
    'apps/android/src/main/res/drawable/agent_hub_icon.png': 'ccb34187c3a1f5f4a3632648ea101c0f7af4d9e1c24f3e5e4f9894a6bb2e1b3c',
    'apps/android/src/main/res/values-zh/product_strings.xml': '742b15329ccec0eb7d4b91628add9fd98f5df5be3749ce913b2101c6086c7276',
    'apps/android/src/main/res/values/product_strings.xml': 'a790c2a751dbb631b670dc8569725b81b0862f7bd1f3229d6b0f36be718c80ba',
    'apps/android/src/main/res/values/styles.xml': '528a7a6c52fe51d190579d6c8adf39b6616bf71694878a1cf7e298885348634c',
    'apps/android/src/main/res/xml/network_security_config.xml': 'e20024efa2967e66bc0cde8b5431ad656c8b80f0bf6e3cd449a676cfd106373c',
    'apps/android/src/main/res/xml/resource_paths.xml': 'abc5ddaad279492b6ee1a9933d2500ea15f6bbac93fbba604ae9a81029270581',
    'apps/android/src/release/kotlin/com/agenthub/android/NativeChatNotificationGraphColdStart.kt': 'f0ff4a2234a0bc15d6b8118f1bb93cef179e6ce49f1258b3fe4ce9b20a8c5ae3',
    'apps/android/src/release/kotlin/com/agenthub/android/NativeNaturalLaunch.kt': '068fe6cfe84e9f922db5e6894109a14f2075a1db0c627663f347329bf932f176',
    'apps/android/src/test/kotlin/com/agenthub/android/NativeChatNotificationSoundVectors.kt': '50a2dca64e1a73adec3873c7b55673f2edfb16658287033148dc71999c72a540',
    'apps/android/src/test/kotlin/com/agenthub/android/NativeChatNotificationWireVectors.kt': 'a88b2efcfb159f84cdf63baf6094fd5086b502df0fd53d2763a964c5599799b4',
    'build.gradle.kts': 'fd03db71ac06f65fc227ff8342ac347238ce593946ed36f001ce2821aad04ce4',
    'gradle.properties': '823905278784a0a3de8fb19e7377b2a0433438f2c3a0aa5d5e13ecf6768cae02',
    'gradle/wrapper/gradle-wrapper.jar': '497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7',
    'gradle/wrapper/gradle-wrapper.properties': 'e5f6f1ec1a7f6e95b2d9f2b1c1c0a27ea2190e6cf2868b6850e27495af4f3db8',
    'gradlew': 'ab5c0cad16305af2e619c159c1f58dd68d07fab9c11e36701e109c0277407f7a',
    'gradlew.bat': '475c4f08cd57cf2faa819e7f36d72aa93f0ad646ea23a8f7fa3ef54dee1cbc52',
    'package.json': '421376d6f86bf2661bcb4b6d354e4fa54c7a47f193bd325e9c9ca9db17103c34',
    'packages/mobile-core/README.md': '83f755380ca30025872b4d41cee522a7181b49f4653ef0b68a6b863b003f687b',
    'packages/mobile-core/build.gradle.kts': 'cee5f61607a8f9b132bce65cead445ad53b77ddbc50207e730e10e479f814724',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/AISharingRules.kt': '9918cf9f44bf302e79ab8553e515cf43a492233450afbabdd8746eacfba1a746',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/AttachmentPreparationRules.kt': 'dfb5e67632fbde1130a68868bdb517367a873aad4586eff61ede3193096f899a',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ChatManagementRules.kt': '6fff458b4d1b45b37cb59fb3a7d53d10b43932310f0207365d6b086f2d0ce93b',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ChatSearchRules.kt': '7cfff3e3f6d5ed054116a7681850f34d7c16ecd31fbc1b4b298fdbcc4e04b8d7',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/CodexRouteRules.kt': '2f4be2d679a64aa60c6614450e628d7bbc84a1798f16a4504edbf1b1d9d16da7',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/DesktopSuggestionRules.kt': 'f1349c4481ecc3d80f0f06f3cf248a144b399028474b3a936dc3ae1343805fed',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/DomainContract.kt': '800fe59b18b169128efed4919ed6f093e207eef549d6d9d13788b5c995cb0c63',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/FailureCopy.kt': '78f4f8b270a1ff56c8aa1ead2362765557ba64f8b67da9cef0366b5358961191',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/FilePreviewRules.kt': '663488eb05e6fbff8952b60442e026cc5e8f4e3cd252fcd5e692c7ac8e7e6e47',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/GenerationPreferenceRules.kt': '146f2a7ffec3714c25b838bd76f99de7a65aa96f7210666729e441f3c8035ef8',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/HistoryGateway.kt': '26602b924a53b5527771366c19435c257c7ccee77a3201cb5a52dffe45b4e270',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/MessageLinkRules.kt': 'a56e8c54c1fc9de33fab40f2496825bc7d2682b4c474ad2ae17fe252d7ec99dd',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/MessageProjection.kt': 'a7d33b77cdc1c7d4e116d6affb7cfe0c6fa4d8099457ca675b56567cddbc91b3',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/MobileRules.kt': '76d373fb8feef48bb780ab431ef074b23c679df4ff9981dff8453329bd9f05b8',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/OperationPolicies.kt': 'f84b77fc679ca46b7c543b25f9bbf82e7b0d401d4680bfcf5d545462870922da',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/PrivateConnectionRules.kt': '031b711fbebe2e1d44707ea4db8ebe0b71467a8cc17cb909b8bec790a06a7a7f',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ReadingPolicy.kt': '501fe494435a8d7a78c8c7d2f3306d5981e760e3b0d2a678784628ce2ec484f6',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ReplyRecovery.kt': '1f4054afe59d731c1f402e9346446956830135311dba842d4aa4598ec4716359',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/RuntimeIdentity.kt': '6dca5f73299604d62d75bd27e64a2798994c78d6ffdb49ae321b4639c895ce2c',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/SessionPolicies.kt': '111f81146099e25b5b35b5b02bb88bb6e649c8c39532c7e195ca5988b9194561',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/TaskHubRules.kt': '4ae53a6524a336c4c79eaa39c369a7a55e0f243eb6690145b19c681c37f1c934',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ThreadForkRules.kt': '4d450bb656e1d6581ff39d12ffe243d8c131b5c291f7c2a75da18c636adc027b',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ThreadSync.kt': '6792a9ecf053434c23c637c11153d02f5881f126ff27e9905961a5140162f3f7',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/ToolPresentation.kt': '1ca354b0963108854759b2946d67176d710b22c5ad3714e9f35959b6162f8f49',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/UsageRules.kt': '4cd1bd547479c3ecf21fa4f5bde3f7968135c92aad2f1d6d5b5fd2474d76c402',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/WorkspaceArchiveRules.kt': '1a482c4b9a072fc050bd0da11ea80d16993a18c7bff1ab3b2d358d39361a403b',
    'packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/WorkspaceSidebarRules.kt': '7ced65999de6b436075c7495a889814025d3fc5297d19c8e6dbfd9332f64250e',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/AISharingVoiceRulesTest.kt': '4065e069bfb3ed2c2e5c6b86930aa7c9b8554eda994aad4680db263ebef59f7d',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/AttachmentPreparationRulesTest.kt': '478810f5974c763b9696f7d9047f4fe12f831ac7b0695bf401611a998eeb029b',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ChatManagementRulesTest.kt': 'da0f1e8e1ea51a9fb7f1ea2ff059c469cf9c60e18ea454bdcb24240139b57a0b',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ChatSearchRulesTest.kt': '7888800b07477b82c379b12f053f9caa59085cbf668ebe30d4538c3883d034b7',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/CodexRouteRulesTest.kt': 'c56f21e90e32755e3d63124b66025d04496b4ff40eeb2c0fc1a152e2dd164206',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/DesktopSuggestionRulesTest.kt': 'd50826b0d813b0e1eb08bb495835f1197199abfd5bd89745df97b93492eb1d28',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/FilePreviewRulesTest.kt': '1dbc64f434a787eb6813f14daaa0790a069d539033faffd241fc6e30d4d68ab7',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/GenerationPreferenceRulesTest.kt': 'dd66ebc3c9644e2d6d39a0f1ebf20d17f4c2500792eb01461cb58b14dfae5b74',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/MessageLinkRulesTest.kt': 'e6516ed79fb5601306ea5546052ff6a1325c3ba7a2cc1a34672302ddb88b2438',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/MobileRulesTest.kt': '510eefe6a65921621b6bd207b3d930119d6e2b09186720a242c6159c4b89147b',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/OperationPoliciesTest.kt': '20710a0463a9981e929d4fd826adc74fa08fb60422e40a576c68dedc6603e4a2',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/PrivateConnectionRulesTest.kt': 'c55427429b32f248b22681e50c7f008f8cf07413afa86c75fce0348de6e26c2b',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ReplyRecoveryTest.kt': 'bfd468dde75f3432ba56a73ce51ead1bc52aad6be865d4336be36faf573120fe',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/RuntimeIdentityTest.kt': '03ae43facb24ed7471dde4a006e2eafc9dca0ea932d2fd8edfd0e0a2e14985d7',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SessionPoliciesTest.kt': '4a9546ee08d0b471f62fb5fdaf6ec09f5fe4c246f35b88de8d7d3e0158c9341c',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/TaskHubRulesTest.kt': '4853587d2f5ddb6385e0bc38e7aeed17a95d96a446a4566c5817e2214df994fc',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ThreadForkRulesTest.kt': 'cb191ba66fd57dd280649bcc534b158bb3c36b55fccfcfef5caaf650e6991652',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/ThreadSyncTest.kt': 'b37d63e22e956a3ff06a265801a2dce9b2e4c2664d5485e8813bfe8c86c584d9',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/UsageRulesTest.kt': 'a4e4820f1802016e141d1ab30ebdb34e873fa18a72a8891f062cf202a544595f',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/V9SessionRulesTest.kt': '823b5fe5795efa59665189226f68aee47f9589788c75d5660426d51f8039bbc6',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/WorkspaceArchiveRulesTest.kt': 'c8eac7f45bef76db1af07d27ebe73cdcb01daad0bb571ead3ee6db70e144abd6',
    'packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/WorkspaceSidebarRulesTest.kt': 'dfe0684feeacf2ea612049639c8e529240fa8e7e4f001ee6f009e3f0b4ef3aa3',
    'packages/protocol/fixtures/mobile/ai-sharing-voice-v1.json': '62715870082858bb452b3a84d0187b0bb1e430776d7257d3f531cfbafe584f78',
    'packages/protocol/fixtures/mobile/attachment-preparation-v1.json': '77685de4f56d9f096c7091a47d43f6ce4185ebc56b32b7c87eb35dc86804174b',
    'packages/protocol/fixtures/mobile/core-v1.json': 'c47e1840be782686b5a4fdae8acefbe029156c87575a4c80a622e0060e074964',
    'packages/protocol/fixtures/mobile/desktop-suggestions-v1.json': '8286a6c70e8fd87f54a91ef01e836a550b53fad5db751ab4099c5f335f0e0c6b',
    'packages/protocol/fixtures/mobile/easywork-receipt-v1.json': 'f950f5f8cab9dfead71937d829cc1a31fa5e1c271b4f9b83f9162f33106d9494',
    'packages/protocol/fixtures/mobile/easywork-thread-sync-v1.json': 'f400e146f08b85b7b2f374af5a98c302b89cb52a624572d14093af0702a0148f',
    'packages/protocol/fixtures/mobile/generation-preferences-v1.json': 'a0a2db4dc06e88134df161cc144bd539235577fafd435390fe9bbfe6cb059b83',
    'packages/protocol/fixtures/mobile/message-links-v1.json': '044dd083b4e350cc99e3f00cac04654d2e1dd3e85c78fb4b2e8a9f95e5e8f790',
    'packages/protocol/fixtures/mobile/migration-envelope-v1.json': 'ccc63c9fc28babba3ae8c364fa364a663ed7e189bfdf17c8e47433f14b0b999c',
    'packages/protocol/fixtures/mobile/notification-recovery-v2.json': 'e29100066001f2a8b85b9ece54c20bae68acaa4c74f267ebf51fdd581ee25b17',
    'packages/protocol/fixtures/mobile/private-connection-v1.json': 'ada3de18db04faaca783b2adc6b940f32ef6624babd6f515d29a2f6940877a8a',
    'packages/protocol/fixtures/mobile/reading-policy.json': '14d4c82467eac39522fc5bc64c228001a7ff3c3db138504ef1fd846e30f5e207',
    'packages/protocol/fixtures/mobile/reply-recovery-v2.json': '9a121e94658c519a9ed7cbd33b4366dae58489f54861f6595d5cab5043ed89ff',
    'packages/protocol/fixtures/mobile/state-v1.json': '1fba04fd161fb429543341b6f96fd77d70c63e7e396d3e75466fc26a0ba706c3',
    'packages/protocol/fixtures/mobile/task-hub-v1.json': '71df45754df1bfd517adbeb36a0e4896c84d6e606ecec88b63c23bec1962c811',
    'packages/protocol/fixtures/mobile/usage-v1.json': 'd2512e0a4a01fb21316ddf135cef1c96ae38df50e62c67be830303e822783079',
    'packages/protocol/schemas/agent-management-v1.schema.json': 'b306eb000a2130a481f7341dfbc81145588aa8b438eee293a19486058faf04b6',
    'packages/protocol/schemas/codex-host-discovery-v1.schema.json': '2202fb01af21386432dccdf59b620bdc8dc5919e360e0ec897fc3259de344627',
    'packages/protocol/schemas/codex-mobile-http-v1.schema.json': '208945f5cde40e5b5b2fcfcc42615d746558ca1d9be3138b715a7f813ed04d16',
    'packages/protocol/schemas/notification-recovery-v2.schema.json': 'ce4ede23ba7bd9985e472be954635de47fb3ee7d951f6593f3f7c91809de5bf1',
    'packages/protocol/schemas/project-git-v1.schema.json': 'b59821f7a77f7ba211ef1caeaa30f1f9d3bc73da8f6156109f89bcc7314d8e72',
    'packages/protocol/schemas/task-hub-mobile-v1.schema.json': '4ef9a44dd84584b83a25765af9f0b5c84a4e2920b1b427405a0620c6ec01bf96',
    'packages/protocol/schemas/task-notifications-v1.schema.json': '927d4a31a75ad6e569fb609d5dc3bc12589f1ce8bc6617d5b8cac72f8938a6e0',
    'packages/protocol/schemas/usage-v1.schema.json': '7f671128f3c2cd08f00015f154fbd0f4df06c3d1a299142c0d7c9151582f3a36',
    'pnpm-lock.yaml': '344f25b3630735ef951d340ebd98a07b7d9966b81d03d99968f573f30d64d780',
    'scripts/mobile/android-nonempty-device-first.py': '11c13340cee7474bf91b750fc01f07fa9de29fc0db5e7c469e7aa7fa9837b7bd',
    'scripts/mobile/gradle.mjs': 'df2bb5bb8169181df83543de8ff50353dab5f0cf2c9c3e78eb1d103b57ca99dc',
    'settings.gradle.kts': 'c6fcc8c256b8e4ccf39dca4d5569c226c3f3e5c26369ae7d2e11cbff07fe0348',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def file_identity(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mode, info.st_uid,
            info.st_nlink, info.st_mtime_ns, info.st_ctime_ns)


def digest(path, cap=256 * 1024 * 1024):
    path = Path(path); before = path.lstat()
    require(stat.S_ISREG(before.st_mode) and 1 <= before.st_size <= cap, "Unsafe/oversized file")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        opened = os.fstat(descriptor)
        require(file_identity(opened) == file_identity(before), "Hash open changed")
        sha = hashlib.sha256(); total = 0
        while True:
            require(file_identity(os.fstat(descriptor)) == file_identity(opened), "Hash input changed before read")
            chunk = os.read(descriptor, min(65536, cap - total + 1))
            total += len(chunk)
            require(total <= cap, "Hash cumulative byte cap")
            require(file_identity(os.fstat(descriptor)) == file_identity(opened), "Hash input changed during read")
            if not chunk:
                break
            sha.update(chunk)
        require(total == opened.st_size and file_identity(os.fstat(descriptor)) == file_identity(opened), "Hash final descriptor changed")
        calculated = sha.hexdigest()
    finally:
        os.close(descriptor)
    require(file_identity(path.lstat()) == file_identity(before), "Hash final pathname changed")
    return calculated


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")


def no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key")
        result[key] = value
    return result


def read_document(path, maximum=131072):
    path=Path(path); parent=path.parent; info=parent.lstat()
    require(stat.S_ISDIR(info.st_mode) and parent.resolve()==parent.absolute() and info.st_uid==os.getuid() and stat.S_IMODE(info.st_mode)==0o700,"Private JSON parent required")
    before=path.lstat()
    require(stat.S_ISREG(before.st_mode) and before.st_uid == os.getuid() and
            stat.S_IMODE(before.st_mode) == 0o600 and before.st_nlink == 1 and 1 <= before.st_size <= maximum,
            "Private regular proof/admission required")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as source:
        opened = os.fstat(source.fileno())
        require(file_identity(opened) == file_identity(before), "JSON open changed")
        raw = source.read(maximum + 1)
        require(len(raw) == before.st_size and file_identity(os.fstat(source.fileno())) == file_identity(opened), "JSON changed during read")
    require(file_identity(path.lstat()) == file_identity(before), "JSON pathname changed")
    value=json.loads(raw,object_pairs_hook=no_duplicates,parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Non-finite JSON")))
    return raw,value


def read_json(path,maximum=131072):
    return read_document(path,maximum)[1]


def exact(value, keys):
    require(type(value) is dict and set(value) == set(keys), "Closed object keys")


def same(left, right):
    if type(left) is not type(right):
        return False
    if type(left) is dict:
        return set(left) == set(right) and all(same(left[key], right[key]) for key in left)
    if type(left) is list:
        return len(left) == len(right) and all(same(a, b) for a, b in zip(left, right))
    return left == right


def integer(value, low, high):
    require(type(value) is int and low <= value <= high, "Integer range")


def sha(value):
    require(type(value) is str and re.fullmatch(r"[a-f0-9]{64}", value), "SHA-256 type")


def canonical_uuid(value):
    require(type(value) is str and str(uuid.UUID(value)) == value, "Canonical UUID")


def digests(value, cap):
    require(type(value) is dict and 1 <= len(value) <= cap, "Digest map size")
    for key, value_sha in value.items():
        require(type(key) is str and 1 <= len(key) <= 512 and not any(ord(c) < 32 for c in key), "Digest name")
        sha(value_sha)


def write_new(path, value, maximum=131072):
    path = Path(path); parent = path.parent
    info = parent.lstat()
    require(stat.S_ISDIR(info.st_mode) and parent.resolve() == parent.absolute() and
            info.st_uid == os.getuid() and stat.S_IMODE(info.st_mode) == 0o700, "Private output directory required")
    raw = encoded(value); require(1 <= len(raw) <= maximum, "Output size")
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as output:
        output.write(raw); output.flush(); os.fsync(output.fileno())


def input_inventory(repo, overlay_names=()):
    repo=Path(repo).resolve(); names=set(ROOT_BUILD_NAMES+EXTRA_INPUTS)|set(overlay_names)
    actual_roots=sorted(item.name for item in repo.iterdir() if item.is_file() and
        (item.name in {"gradlew","gradlew.bat","gradle.properties"} or item.name.endswith((".gradle",".gradle.kts"))))
    require(actual_roots==ROOT_BUILD_NAMES,"Root Gradle entry inventory changed; explicit contract review required")
    presence={root:(repo/root).is_dir() for root in INPUT_ROOTS}
    require(presence==ROOT_PRESENCE,"Build root presence changed")
    def walk(folder):
        before=folder.lstat()
        require(stat.S_ISDIR(before.st_mode) and folder.resolve()==folder.absolute(),"Input directory link")
        entries=sorted(folder.iterdir(),key=lambda item:item.name)
        require(len(entries)<=4096,"Input directory quota")
        for item in entries:
            info=item.lstat(); require(not stat.S_ISLNK(info.st_mode),"Input link is not approved")
            if stat.S_ISDIR(info.st_mode): walk(item)
            else:
                require(stat.S_ISREG(info.st_mode) and info.st_nlink==1,"Source input must be a single-link regular file")
                names.add(item.relative_to(repo).as_posix())
        after=folder.lstat()
        require((before.st_dev,before.st_ino,before.st_mtime_ns,before.st_ctime_ns)==
                (after.st_dev,after.st_ino,after.st_mtime_ns,after.st_ctime_ns) and
                [item.name for item in entries]==sorted(item.name for item in folder.iterdir()),"Input inventory changed during traversal")
    for root in INPUT_ROOTS:
        if presence[root]: walk(repo/root)
    require(len(names)<=2048 and names==set(BASELINE)|set(overlay_names),"Added/removed build input; explicit contract review required")
    return sorted(names)


def source_guard(repo, source_overlay=None):
    if source_overlay is not None:
        require(type(source_overlay) is dict and len(source_overlay)<=64,"Explicit reviewed source overlay")
        for name,value in source_overlay.items():
            require(type(name) is str and not name.startswith("/") and ".." not in name.split("/") and
                (any(name.startswith(root+"/") for root in INPUT_ROOTS) or name.startswith("scripts/mobile/")),
                "Reviewed overlay path must be an enumerated source/tool input")
            require(not name.endswith((".gradle",".gradle.kts")) and name!="scripts/mobile/gradle.mjs","Build entries require a new static contract")
            sha(value)
    overlay_names=set(DYNAMIC_SOURCES) if source_overlay is None else set(source_overlay)
    repo=Path(repo).resolve(); before=input_inventory(repo,overlay_names)
    actual={}
    for name in before:
        path=repo/name; prior=path.lstat()
        require(stat.S_ISREG(prior.st_mode) and prior.st_nlink==1,"Single-link source input required")
        actual[name]=digest(path,32*1024*1024)
        after=path.lstat()
        require(after.st_nlink==1 and file_identity(prior)==file_identity(after),"Source alias/identity changed during hashing")
    require(sum((repo/name).lstat().st_size for name in before)<=64*1024*1024,"Build source byte quota")
    require(all(actual[name]==value for name,value in BASELINE.items() if name not in overlay_names),"Unapproved baseline production/build input differs")
    if source_overlay is not None: require(all(actual[name]==value for name,value in source_overlay.items()),"Reviewed overlay bytes differ")
    require(input_inventory(repo,overlay_names)==before,"Build inventory changed while hashing")
    return {"schema":GUARD_SCHEMA,"baselineCommit":BASELINE_COMMIT,"roots":INPUT_ROOTS,"rootBuildNames":ROOT_BUILD_NAMES,
            "inventory":before,"sourceOverlay":{name:actual[name] for name in sorted(overlay_names)},"sources":actual}


def read_guard(path):
    return read_json(path,GUARD_CAP)


def write_guard(path,value):
    write_new(path,value,GUARD_CAP)


def admission(args):
    canonical_uuid(args.run); canonical_uuid(args.boot_uuid); integer(args.uid, 1, 2147483647)
    guard = source_guard(args.repo)
    value = {"schema": ADMISSION_SCHEMA, "caseSchema": SCHEMA, "run": args.run, "selectedMethod": METHOD,
             "selector": SELECTOR, "mode": "first", "package": PACKAGE, "uid": args.uid, "bootUuid": args.boot_uuid,
             "appApkSha256": digest(args.app_apk), "testApkSha256": digest(args.test_apk),
             "caseSourceSha256": guard["sources"][CASE], "wrapperSourceSha256": guard["sources"][WRAPPER],
             "sourceGuardSha256": hashlib.sha256(encoded(guard)).hexdigest()}
    return guard, value


def binding(value, admission_sha):
    return {key: value[key] for key in ("appApkSha256", "testApkSha256", "caseSourceSha256", "wrapperSourceSha256",
                                      "sourceGuardSha256", "bootUuid", "package", "uid")} | {"sourceAdmissionSha256": admission_sha}


def verify_first(args, include_documents=False):
    guard, expected = admission(args)
    guard_raw,guard_value=read_document(args.source_guard,GUARD_CAP)
    admission_raw,admission_value=read_document(args.admission)
    require(same(guard_value,guard) and hashlib.sha256(guard_raw).hexdigest()==expected["sourceGuardSha256"],"Source guard mismatch")
    require(same(admission_value,expected),"Admission mismatch")
    bound=binding(expected,hashlib.sha256(admission_raw).hexdigest())
    intent_raw,intent=read_document(args.first_intent); proof_raw,proof=read_document(args.first_proof)
    shared = {"schema", "run", "binding", "pid", "startTicks", "selectedMethod", "anchor"}
    exact(intent, shared)
    exact(proof, shared | {"receipt", "generation", "archiveSha256", "sourceCiphertext", "originalCandidateCiphertext",
          "destinationCiphertext", "originalTextSha256", "originalBinarySha256", "draftKey", "acquiredStores", "acquiredSessions",
          "actualSessionJoinReturns", "actualWriterRetireReturns", "actualLeaseReleaseReturns", "unknownAcquisitions", "forbiddenPortAttempts",
          "draftTextSha256", "sourceTextSha256", "sourceBinarySha256", "activeTextSha256", "activeBinarySha256"})
    for key in shared:
        require(same(proof[key], intent[key]), "Original intent/proof binding changed")
    require(proof["schema"] == SCHEMA and proof["run"] == args.run and same(proof["binding"], bound) and proof["selectedMethod"] == METHOD, "Wrong target/mode")
    integer(args.observed_pid, 1, 2147483647)
    require(proof["pid"] == args.observed_pid and type(proof["pid"]) is int, "Observed original PID mismatch")
    require(type(proof["startTicks"]) is str and re.fullmatch(r"[0-9]{1,24}", proof["startTicks"]) and
            proof["startTicks"] == args.observed_start_ticks, "Observed original start mismatch")
    anchor = proof["anchor"]
    require(type(anchor) is dict and 12 <= len(anchor) <= 128, "Anchor size")
    fixed = {"package", "uid", "run", "filesRoot", "dataScopeIdentity", "runDirectory", "no_backup", "cache", "files"}
    require(fixed <= set(anchor) and "ancestor:/" in anchor and all(key in fixed or key.startswith(("ancestor:/", "observed-mount:/")) for key in anchor), "Anchor names")
    for key, value in anchor.items():
        require(type(key) is str and len(key) <= 512 and type(value) is str and 1 <= len(value) <= 16384, "Anchor type/cap")
    require(anchor["package"] == PACKAGE and anchor["uid"] == str(args.uid) and anchor["run"] == args.run, "Anchor target")
    require(anchor["filesRoot"].startswith("/data/") and anchor["filesRoot"].endswith("/" + PACKAGE + "/files"), "Application files anchor")
    for key in ("runDirectory", "no_backup", "cache", "files", "dataScopeIdentity"):
        fields = anchor[key].split(":")
        require(len(fields) == 4 and all(re.fullmatch(r"[0-9]{1,24}", field) for field in fields), "Directory identity")
        _, _, owner, mode = map(int, fields)
        require(owner == args.uid and stat.S_ISDIR(mode) and stat.S_IMODE(mode) in
                ({0o700, 0o771} if key == "dataScopeIdentity" else {0o700}), "Directory owner/mode")
    exact(proof["receipt"], {"candidateId", "sha256", "records"})
    canonical_uuid(proof["receipt"]["candidateId"]); canonical_uuid(proof["generation"])
    sha(proof["receipt"]["sha256"]); sha(proof["archiveSha256"])
    for name, cap in (("sourceCiphertext", 64), ("originalCandidateCiphertext", 64), ("destinationCiphertext", 128),
                      ("originalTextSha256", 8), ("originalBinarySha256", 1), ("sourceTextSha256", 32),
                      ("sourceBinarySha256", 1), ("activeTextSha256", 32), ("activeBinarySha256", 1)):
        digests(proof[name], cap)
    text = proof["originalTextSha256"]
    fixed_text = {"metadata", "codex-private-connections-v1", "mobile-reading-ledger-v1", "native-chat-notifications-v1", "native-chat-notification-sounds-v1"}
    dynamic = [key for key in text if key.startswith(("operations:", "submission:", "draft-file:"))]
    require(fixed_text <= set(text) and len(dynamic) == 3 and all(sum(key.startswith(prefix) for key in dynamic) == 1 for prefix in ("operations:", "submission:", "draft-file:")), "Nonempty record names")
    require(len(text) == 8 and set(text) == fixed_text | set(dynamic), "Exactly eight text records required")
    binary = proof["originalBinarySha256"]
    sound = next(iter(binary)); require(sound.startswith("native-chat-notification-sound-v1:") and sound.endswith(".caf"), "Normalized sound name")
    canonical_uuid(sound.removeprefix("native-chat-notification-sound-v1:").removesuffix(".caf"))
    require(type(proof["receipt"]["records"]) is int and proof["receipt"]["records"] == 9 == len(text) + len(binary), "Source record count")
    for name, wanted in {"acquiredStores": 27, "acquiredSessions": 4, "actualSessionJoinReturns": 4,
                         "actualWriterRetireReturns": 25, "actualLeaseReleaseReturns": 2, "unknownAcquisitions": 0, "forbiddenPortAttempts": 0}.items():
        require(type(proof[name]) is int and proof[name] == wanted, "Owner/port evidence mismatch")
    sha(proof["draftTextSha256"])
    require(type(proof["draftKey"]) is str and proof["draftKey"].startswith("codex-machine-v2:") and len(proof["draftKey"]) <= 512, "Independent draft key")
    result = {"schema": "p7-nonempty-device-first-host-review-v1", "run": args.run, "selectedMethod": METHOD,
            "firstProofSha256": hashlib.sha256(proof_raw).hexdigest(), "firstIntentSha256": hashlib.sha256(intent_raw).hexdigest(),
            "sourceGuardSha256": expected["sourceGuardSha256"], "sourceAdmissionSha256": bound["sourceAdmissionSha256"],
            "nativeAssertionsReportedComplete": True, "originalPidObserved": True, "coldAccepted": False,
            "fdCountClaimed": False, "controllerStrictGraphClaimed": False, "physicalDeviceClaimed": False}
    return (result,proof) if include_documents else result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    guard_parser = subparsers.add_parser("source-guard")
    guard_parser.add_argument("--repo", required=True); guard_parser.add_argument("--output-dir", required=True)
    for command in ("admit-first", "verify-first"):
        sub = subparsers.add_parser(command)
        for option in ("repo", "app-apk", "test-apk", "run", "boot-uuid", "output-dir"):
            sub.add_argument("--" + option, required=True)
        sub.add_argument("--uid", required=True, type=int)
        if command == "verify-first":
            for option in ("source-guard", "admission", "first-intent", "first-proof", "observed-start-ticks"):
                sub.add_argument("--" + option, required=True)
            sub.add_argument("--observed-pid", required=True, type=int)
    args = parser.parse_args(); output = Path(args.output_dir)
    if args.command == "source-guard":
        guard = source_guard(args.repo); write_guard(output / "source-guard.json", guard)
        print(json.dumps({"schema": GUARD_SCHEMA, "sourceGuardSha256": hashlib.sha256(encoded(guard)).hexdigest()}))
    elif args.command == "admit-first":
        guard, value = admission(args)
        write_guard(output / "source-guard.json", guard); write_new(output / "source-admission.json", value)
        bound = binding(value, digest(output / "source-admission.json"))
        mapping = {"appApkSha256": "nonemptyAppApkSha256", "testApkSha256": "nonemptyTestApkSha256",
                   "caseSourceSha256": "nonemptyCaseSourceSha256", "wrapperSourceSha256": "nonemptyWrapperSourceSha256",
                   "sourceGuardSha256": "nonemptySourceGuardSha256", "sourceAdmissionSha256": "nonemptySourceAdmissionSha256",
                   "bootUuid": "nonemptyBootUuid", "uid": "nonemptyUid"}
        arguments = {destination: str(bound[source]) for source, destination in mapping.items()}
        arguments |= {"class": SELECTOR, "nonemptyMode": "first", "nonemptyRun": args.run}
        write_new(output / "instrumentation-arguments.json", arguments)
        print(json.dumps({"schema": ADMISSION_SCHEMA, "run": args.run, "selector": SELECTOR,
                          "sourceGuardSha256": value["sourceGuardSha256"], "sourceAdmissionSha256": bound["sourceAdmissionSha256"], "coldAccepted": False}))
    else:
        result = verify_first(args); write_new(output / "first-host-review.json", result); print(json.dumps(result))


if __name__ == "__main__":
    main()
