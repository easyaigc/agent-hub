# Actual warm MainActivity Intent acceptance preparation

Two bounded native methods exercise `MainActivity.startActivity -> onNewIntent -> receive`
on the same actual Activity and adopted isolated Session. One Intent has no data; the other
is an unsupported text-only SEND with a synthetic pairing URI. The real attachment Inbox
must consume the latter before `receiveLink`, while the incoming pairing link remains null.
The old capability must refuse its write block despite unchanged target/navigation/draft.
Removing only the phase3 Main invalidation call is the required parent negative control;
a direct `onNewIntent` invocation or a copied dispatcher is not this scenario.

The parent must supply an already resumed, idle, debuggable `.acceptance` MainActivity,
without real clients or pending operations, and `p8ActualMainReady=1`. This class never
launches or recreates MainActivity, never resets/restores the default Session's data,
and never uses a foreground shell command. An unavailable ready Activity fails before
adoption. Exact installed app/runner hashes are required in `p8PairingUiBuild`.

Each method uses a fresh `p8-main-intent-<UUID>` encrypted namespace and key alias. It
stream-hashes all default `mobile-v1*` encrypted files before adoption and after restoring
the original Activity Session, and retains the original synthetic grant/claim/unknown
operation journal byte for byte. No pairing request, file-provider read, runtime command,
Host, model, camera, `.easywork` package or default credential is involved. Proof records
contain assertions and synthetic identifiers only; no screenshots or secret values.

The exact phase2 Session and phase3 Main proposals remain the only required shared
hunks. Preserve the parent's P7 cover, P9 dispatcher and voice/discard hooks. Existing
11 native form-lease methods continue to own late approval/failure and disk-write races;
these two methods add actual Activity routing coverage without repeating that lane.

Prepared and compile checked only. Parent owns integrated build, package/device lease,
actual positive/negative-control execution, final parity tables and acceptance closure.
A5 is not closed; A6 and Task/project plus iOS work remain paused.
