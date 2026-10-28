# Incoming Intent lease revocation

The current parent MainActivity may consume an Intent through externalInbox before
`Session.receiveLink`. Nullable link changes and `receiveLink` alone therefore do not
cover that entry. This small overlay invalidates the pairing authority at the start of
`onNewIntent`, before the existing `coverPanelNotices` and receive logic. It neither
revokes an upstream grant nor retries/replays any input.

The exact parent base is `a713c78f...`; all panel/read/voice/discard/receive code is kept.
`MainActivity.onStop -> Session.leaveForeground` is already wired. The final Session
lease overlay invalidates there, and the actual form lifecycle invalidates on pause/
stop/disposal. AgentHubApp remains unchanged at the recorded `c706ed51...` source hash.

Apply/rebase only the recorded hunk after checking the actual MainActivity base. The
Session and catalog proposals remain in phase2. This phase compiles both own shared
proposals plus the reviewed private resource additions, then restores both original
shared files exactly. The parent must build its integrated source and ordinary generated
catalogs before acquiring the exact device/package/sidecar lease.

No device, MainActivity Intent callback, real Host/issuer/Desktop/camera/model has been
executed here. The existing 30 pairing native methods stay unverified; this hunk's actual
MainActivity Intent execution is an additional parent gate. Compilation and the existing
Host/copy checks do not close A5. A6 remains paused.
