# P9-2 / F53 local notification sounds

Task `android-parallel-p9-notifications-20261028`; this increment starts at `400bb57ccbe64b60590d37a0a86e44578b5b80d1` on the same isolated branch. P9-1 source/evidence and unknown operation records are preserved separately. iOS reference: NotificationSounds.swift, NotificationSettings.swift and TaskNotifications.swift. Existing notification preferences, original events/receipts, identity and cross-generation original-event dedupe are unchanged.

## Implemented production path

The independent ordinary-chat settings page now opens default and category sound pages. It offers system default, silence, three builtin sounds, imported sounds, category inheritance/override, explicit preview and imported-item deletion. Global import selects the new default only when the captured preference revision still matches atomically; otherwise the sound is added and the newer choice stays. Deletion resets matching global references to default and removes matching category overrides, preserving other preferences and the original event/receipt bytes. Selection and retirement share the same snapshot → operation lock order, so a late selection cannot reintroduce a deleted sound.

The three v1 builtin WAVs are immutable APK assets with pinned size/frame count/SHA-256. Frequencies and envelope follow the iOS synthesis (soft 660 Hz; chime 660/880; attention 880/660/880; 0.24 seconds each segment). They are generated using the shipping PCM generator and frozen as bytes, rather than deriving persistent system URIs from runtime floating-point results. Future versions must preserve these bytes/IDs or add new versioned IDs.

The actual import entry is ActivityResultContracts.OpenDocument → ContentResolver → bounded private byte snapshot → local PCM/native codec conversion → authenticated catalog/binary publication. It takes no persistent source URI grant and stores no source URI/path. It checks actual stream bytes regardless of metadata size: at most **10 MiB**, nonempty, at most **29 decoded seconds**. The private input snapshot is discarded on success/failure/cancellation. The original document is not edited or removed. Normalized output is mono 22050 Hz signed 16-bit little-endian WAV, at most 1,278,944 bytes. Existing preference `*.caf` strings remain logical sound IDs; they do not claim the Android payload is CAF.

PCM WAV/RIFX (8/16/24/32 integer, 32/64 float and matching extensible PCM), AIFF/AIFC PCM (including little endian and supported float forms) and CAF fixed LPCM (endian/float/high-aligned unpacked values) use the shipping bounded parser. Container layout, frames/alignment, finite samples and output duration are checked; compressed/unknown encodings reject explicitly. WAV/AIFF/CAF input reads a read-only mapping of the bounded private snapshot, keeping the source out of a second full heap allocation. Conversion streams mono samples into a bounded sink with an anti-alias FIR for downsampling and linear interpolation for rate conversion. M4A/MP3 use actual MediaExtractor/MediaCodec output and confirmed PCM format on the device; no fixed output or claimed extension substitutes for decoding. Available compressed codecs depend on the installed system. Unsupported codecs, unavailable/grant-denied documents and malformed/oversized audio have distinct messages.

Preview uses actual MediaPlayer with notification AudioAttributes and a transient AudioFocusRequest. It never posts a test notification or records audio. It handles asynchronous prepare/error/completion, stale prepare/cancellation and focus loss, closes the seekable descriptor and stops on page leave, background, picker launch and deletion. Volume/DND/audio route results require physical verification.

## Sound URI and system channel boundary

`NativeChatNotificationSoundProvider` serves only `content://<this-applicationId>.chat-notification-sounds/v1/<normalized-SHA256>.wav`. It rejects foreign authorities, query/fragment, traversal, extra/encoded path segments and write modes. Metadata is generic `notification.wav`; the original name/path is not exposed. Opening resolves only an active builtin/imported descriptor, authenticates exact bytes and returns a seekable **read-only** descriptor backed by a private temporary file unlinked before return. It cannot expose other cache, recording, migration or attachment files. Identical normalized content shares a stable URI; deleting one duplicate does not remove another active copy. Already-open descriptors/system-cached audio cannot be retroactively erased by app deletion.

NotificationPlatform now resolves custom/builtin content before channel publication, and refuses custom preparation unless the exact non-exported grant-capable provider is registered. The page explicitly shows this integration gap when absent; local import/preview are still available. It never guesses a SystemUI package or manually grants broad access. Actual system grant/read/play is a separate native gate.

Existing default/silent channel IDs remain compatible. Custom variants use stable content digest IDs; user-created sound/importance/badge changes on an existing channel are not overwritten. A category block continues to suppress new variants, and new variants inherit the lowest existing category importance. The page reads actual channel sound and distinguishes system/custom/known local sound. System channel settings remain authoritative. New variants and deleted/retired URI behavior require actual API26/API37/physical tests; system submission, playback and audibility are separate results.

The provider registration is the sole new parent-owned Manifest hook:

```xml
<provider
    android:name=".NativeChatNotificationSoundProvider"
    android:authorities="${applicationId}.chat-notification-sounds"
    android:exported="false"
    android:grantUriPermissions="true" />
```

No shared Manifest, MobileSession, MainActivity, global settings, migration core, central parity/localization/generated files, Task Hub/business notification or EasyWork FCM code is changed here. The parent-added claim `mayPresent` guard and its Runtime hook are **not touched**. The Platform constructor retains the original `(context,text)` source call and adds an optional `sounds` library parameter; isolated tests/migration fixtures should inject a library using the same NativeSessionStorage as their notification store. Normal application construction uses the existing default installation store.

## Durable state and migration handoff

`native-chat-notification-sounds-v1` is a new closed encrypted catalog, with active/staged/retired descriptors. Imported binary logical names are `native-chat-notification-sound-v1:<UUID>.caf`; bytes use existing NativeSessionStorage authenticated binary v4, not plaintext or base64 JSON. At most 128 active imports and 256 total descriptors are allowed; catalog size is bounded at 256 KiB. Capacity failure preserves data and is visible. Builtin assets are not copied into another secret store.

Import indexes a staged descriptor before writing authenticated bytes, then publishes active metadata. A failed/cancelled import is not auto-promoted or selected. Deletion resets preferences first, then retires metadata, then removes only exact authenticated/hash-matching bytes; partial failures retain an indexed retirement. `settlePending` only removes validated indexed abandoned/retired bytes, never unknown/corrupt data. Cold reads do not replay imports, preview, play, notify or silently reset a corrupt/future catalog. The sound page displays unavailable state and preserves original files if the catalog cannot be read.

P7 receives [closed schema](sounds-storage-v1.schema.json) and [migration requirements](sounds-migration-contract.json). Production `validateEncoded`, `binaryInventory`, `requiredBinaries` and `validateBinary` are the validation/inventory entry points. Required active binaries must exist and match; staged/retired records may have absent bytes only according to that recorded phase. No new migrator is implemented. Source URI grants, player/focus state, system channel/permission state and old package authorities are not transferred as current authority. Notification migration reset/off/re-registration and immutable unknown receipts continue to follow the P9-1 contract.

## Auxiliary evidence and remaining gates

The host check compiles and runs the **shipping PCM implementation** with the installed pinned Kotlin compiler; no emulator, player, notification or model is used. Thirty-one cases cover bit-exact normalized WAV/read-only mapped snapshots; unsigned/integer/float/endian/multichannel samples; AIFF/AIFC/CAF; rate/duration/anti-alias checks; malformed/truncated/oversized/container mismatch; cancellation; 10 MiB/29 seconds; builtin assets; and canonical archive validation. Run through the parent build-slot wrapper with the approved GRADLE_USER_HOME:

```sh
pnpm exec node scripts/mobile/check-chat-notification-sounds.mjs
```

`--write-builtins` is an explicit reproducibility action and refuses to overwrite differing existing bytes. Synthetic 0.25-second AAC/M4A and MP3 assets were generated locally with ffmpeg; their exact hashes and generation recipe are in [asset manifest](sounds-assets.json). They contain no recording or user attachment.

The runner adds 16 `NativeChatNotificationSoundTest` and 4 `NativeChatNotificationSoundUiTest` methods. These exercise real Keystore cold reads/bytes, deletion/reference/receipt preservation, staged/cancelled/retired failure, strict schema/URI, seekable read-only descriptors, actual installed M4A/MP3 codecs, late-import CAS, default/category/builtin/import selection/back and deletion through production pages. They are **compiled, not executed in this workline**. UI choice/deletion can cancel this module's OS notices, so the parent must use its controlled P9 acceptance notification lane. They never click preview/test-notification, launch the OS picker, grant permissions or record audio.

Initial compilation found and fixed one extra parenthesis; the original failed log is preserved. Final app/runner build, host vectors, format/lint and the expected central parity coverage result are recorded against final source in the tree-external sound handoff receipt. Full pnpm check cannot pass until the parent merges the exact [sound parity delta](sounds-parity-delta.json) and [15-string localization delta](sounds-localization-delta.json); checks are not weakened.

Remaining parent gates: register the provider/central deltas and rebuild the integrated candidate; run all new native cases on current API26/API37/physical acceptance identities; then actual picker URI/grant/cancel/cold results, supported/unsupported M4A/MP3/AIFF/CAF variants, full-size/29-second and corrupt input, preview start/stop/focus/background/DND/volume/audio route, real default/category/silent channel sound, system modifications and deletion/upgrade/restart URI access. Actual audible sound requires independent observation. Remote vendor/GMS/non-GMS and true notification arrival remain the separate P9 delivery phase. No device lease, ADB, installation, grant, player invocation, OS notification, recording, original desktop/model operation or new remote submission was performed by this workline.

Platform references: [Android supported formats](https://developer.android.com/media/platform/supported-formats), [MediaCodec raw PCM](https://developer.android.com/reference/android/media/MediaCodec), [stable channel sound URI](https://developer.android.com/reference/android/app/NotificationChannel), [AOSP grantable sound URI handling](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/notification/NotificationRecord.java), [Apple CAF specification](https://developer.apple.com/library/archive/documentation/MusicAudio/Reference/CAFSpec/CAF_spec/CAF_spec.html). These explain the implementation boundary; actual device permission/read/play results remain separate.
