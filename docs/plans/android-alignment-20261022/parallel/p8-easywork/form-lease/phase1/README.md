# P8 pairing: revocable form/action lease (phase 1)

This phase supersedes the nullable incoming-link presentation guard. It prepares native
scenarios and changes only the EasyWork pairing boundary. No device, Host, real issuer,
original Desktop, camera or model has been used here. The parent owns shared-source
integration, central registration and native leases. Copy/catalog alignment is phase 2.

An actual mounted EasyWork form owns a revocable capability. Each action freezes its
original input and SHA-256, form ID/epoch, Session authority epoch, original full target
cache key/navigation and conversation draft revision/hash. A repeated equal input is a
new action epoch, not permission to revive a previous one. Form disposal, actual Activity
pause/stop, background return, link ABA, target ABA, original draft ABA and connection
input ABA invalidate the old capability. An old finalizer cannot clear another action's
busy owner. Inputs persist as editable drafts independently of submitted authorization.

The shared Session overlay checks the capability before requests and after authorization
or network replies, before its encrypted credential write and before replace/cache/select.
The write and revocation share a monitor. A write authorized just before revocation may
finish, but is rolled back before any stale selection; only that write owner's mapping
is restored. A newer identical write is protected by an in-process root/ref owner token.
No new durable record type or migration/storage API is introduced. Original grant,
claim body/ID, unknown authorization and business receipt records are untouched by this
rollback. This is an in-process ownership guard, not cross-process locking proof.

Late outcomes are separate:

- Revoked before connection commit: late authorization may preserve its original grant
  and immutable claim receipt; the Session cannot install/select it. Late failure cannot
  set a new form's error or clear its busy owner. Nothing is automatically revoked,
  retried or replayed, and local abandonment does not cancel an upstream operation.
- Connection committed while valid: the committed connection remains. Later close or
  background suppresses stale UI completion; it does not undo a completed connection.
  The capability cannot perform a second commit.

`session-hook-overlay.json` and own/parent patches bind exact base/proposed SHA-256.
Parent sources were read only. Verify/rebase only these hunks when the parent base
changes; retain all preceding hooks and notification/voice/discard code. The ordinary
own shared file is restored after isolated compilation. The compiled source manifest
includes the actual own proposed bytes. Do not replace the parent file wholesale.

The unchanged pairing-ui sidecar is reused. Supply its complete `p8PairingUiConfig` and
exact installed `p8PairingUiBuild` app/runner/source hashes. All origins must remain
synthetic loopback, and only the exact `.acceptance` pair is allowed. Use blank
ComponentActivity; never prelaunch MainActivity/default connection restoration. The
independent `.acceptance.easywork` and original installed state stay protected.

`NativeEasyWorkFormLeaseTest` adds 11 separately named native scenarios, grouped in the
checkpoint. Each has a 40-second JUnit limit; run each bounded group under a 240-second
parent controller deadline and the same exclusively leased fixture/device. Its eight
network probes mount the actual product form, obtain its real owner and invoke the
production Session with a bounded test-owned NonCancellable wait. This deliberately
allows an actual native authorization reply to arrive after invalidation, rather than
passing solely because a UI coroutine was cancelled. It is Session-boundary evidence,
not another button-click claim. The preceding 14 methods remain the actual button lane.

The probes cover close with a null link, actual Activity pause/resume, Session background
return, link ABA, target/draft ABA, original draft ABA, input ABA and late rejection.
They require one original claim POST, actual retained native grant/claim after approval,
zero runtime relay dispatch, no connection credential/client selection, unchanged
original unknown business journal and original drafts. The target-return probe also
keeps the other target's unsent draft. Three further cases use actual encrypted native
storage to test revoked-write refusal, owned rollback and newer identical-write safety.
They are not a successful original Desktop workspace connection.

The Host JVM checks exercise revocation, busy ownership, valid commit semantics and a
controlled concurrent write/revocation race. They do not establish Android disk or UI
results. Compilation, JVM checks and Node fixture checks are auxiliary; all new native
methods remain unverified until the parent runs them. Preserve failed attempts and
original operation IDs; never substitute a new input or mark A5 closed. A6 stays paused.
