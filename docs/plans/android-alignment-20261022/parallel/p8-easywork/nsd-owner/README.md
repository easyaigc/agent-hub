# Android NSD generation retirement

Parent operation `parent-p8-actual-nsd-stop-owner-next-20261029-1` selects the
Source30 Discovery implementation, including its existing P7 common owner context.
This stage supplies narrow platform lifetime code, a precise Discovery patch and
tests. Parent owns integration, device scheduling and native results. No device,
ADB, Host, external peer, independent EasyWork DEV or business action runs in this
leaf. Page/Session/registry and the other worklines remain unchanged. A6 stays paused.

## Source gap and ownership

The frozen Discovery registered a stop closure that read mutable global fields and
a join closure that joined only its coroutine Job. `stopCurrent` cancelled work,
requested SDK stops, swallowed failures and discarded listener references. Browser
stop/start-failure callbacks were empty; resolver stop-failure was empty, and stopping
resolution did not wait for its callback. Cancelling or joining the Job fenced local
work but did not prove the SDK had terminated discovery or resolution.

The proposed `Generation` captures one scope, SDK lifetime and common registration.
Its closure always stops that same instance. `join` separately waits for its cancelled
coroutine scope and the SDK lifetime. Old callbacks cannot clear a newer generation,
resolver or scanner. `current === generation` and immediate retirement guards still
fence publication; these guards are separate from successful SDK teardown evidence.

`NativeNsdLifetime` uses a serial, always-enqueued platform queue and captures one
browser, network handle and each individual resolver. It has an unparented completion
that waiting callers cannot cancel. Start/stop are one-shot; each SDK stop is issued
at most once. Queued, unstarted hints are discarded on retirement. The original
32-entry pending limit and6-second resolve expiry remain.

| Signal                                                       | Ownership consequence                                                                                              |
| ------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------ |
| `onDiscoveryStopped` or `onStartDiscoveryFailed`             | Browser reaches an observed terminal; outstanding resolver/network ownership remains separate                      |
| `onServiceResolved` / `onResolveFailed`                      | That captured resolver reaches a natural terminal, even if its result is no longer publishable                     |
| `onResolutionStopped`                                        | That captured resolver reaches an observed stop terminal                                                           |
| Stop request returns, stop-failure callback, thrown SDK call | No successful SDK terminal is invented; pending ownership remains                                                  |
| Six-second expiry                                            | Suppress that result; API34+ requests stop once, base API26–33 waits for its natural terminal                      |
| Network unregister returns normally                          | That exact callback is synchronously unregistered; already-queued callbacks remain fenced by retirement/generation |
| Network unregister throws                                    | Remains pending; not converted to success                                                                          |
| Caller cancels/times out while joining                       | Only the wait ends; the independent SDK/common owner remains held                                                  |

Android documents separate natural-resolution and stop-resolution callbacks, with
the stop callback added at API34 (also available through certain T extensions).
This adapter deliberately uses the base SDK≥34 guard; it does not claim extension
support on33. See the official [ResolveListener contract](https://developer.android.com/reference/android/net/nsd/NsdManager.ResolveListener)
and [DiscoveryListener contract](https://developer.android.com/reference/android/net/nsd/NsdManager.DiscoveryListener).
The installed API37 source was also reviewed: it removes its local listener mapping
on some stop failures. That mapping removal is not accepted as the successful-stop
proof requested here. Without a qualifying later terminal, this owner stays pending.
There is no automatic retry, forced completion, increased migration timeout or
synthetic termination flag.

The existing `NativeEasyWorkMigrationOwners.register(stop, join)` API is consumed
unchanged. Its independent retirement scope keeps waiting after a cancelled migration
wait, and its owner set cannot reopen while a join is pending. Thus the Session's
existing30-second migration timeout still fails safely and a second migration is
not made ready by an epoch change, cancelled Job, missing callback or failed stop.
This stage does not implement another migration controller or modify P7's form-action
owner work.

## Platform adapter and iOS comparison

`AndroidNsdPlatform` exposes only discovery, resolution and network registration via
typed handles. Each handle captures its exact NsdManager listener or NetworkCallback;
it never consults a mutable current listener. NSD data still only suggests routes.
The existing pinned target-bound probes, credential reads, candidate publication,
authorization, command handling and all business wire contracts remain unchanged.

| Interaction          | Reviewed iOS `EasyWorkDiscovery.swift`                                                     | Android proposal                                                                                           |
| -------------------- | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------- |
| Start                | One NetServiceBrowser with a `searching` guard, Combine subscriptions on the main run loop | Each start retires the old generation and registers a new captured owner                                   |
| Found/remove/resolve | NetService delegates update local hints; resolve callback checks `searching`               | Captured callbacks use generation/retirement guards; old resolver terminals cannot clear the next resolver |
| Resolve deadline     | NetService receives its existing5-second timeout                                           | Existing6-second expiry remains a publication/stop-request boundary; SDK terminal still required           |
| Stop                 | Calls browser/service `stop`, clears lists/subscriptions and sets `searching=false`        | Nonblocking stop request for UI; migration join separately awaits coroutine and SDK exits                  |
| Exit proof           | Reviewed component has no join of browser/service terminal callbacks                       | SDK/common-owner join is implemented; API26/37 actual proof is still pending                               |
| Network updates      | Known-host/VPN publishers and main run-loop delivery                                       | Exact ConnectivityManager callback unregister return plus queued-event isolation                           |

iOS source is unchanged and matches the observed parent. Its reviewed component does
not establish this new Android SDK shutdown guarantee. Old callback behavior across
iOS stop/start is a separate unverified risk, not a newly observed native failure.
No iOS or cross-platform acceptance is inferred from these Android tests.

## Exact integration and source validation

Apply `discovery.patch` after checking `source-overlay.json`:

| Input                     | SHA-256                                                            |
| ------------------------- | ------------------------------------------------------------------ |
| Frozen Source30 Discovery | `22d48085701e622daf7567dd232282be40a9965b425f46fa04b3866b8b236f66` |
| Proposed Discovery        | `91b63e99c25ca211dbff22b2453e063022b4bcba7d2f7f79696dc554185d6853` |
| Frozen common owner       | `c5c65795d463d54ad17a90fbbe533cbb2b8cee2d5615606d482d9d26a932b271` |

The commit's two new production files and two test files accompany that patch. The
parent already supplies the P7 migration context/registry consumed by the patch and
tests; a standalone older leaf checkout is not the combined production candidate.
No parent source was written or compiled. A changed parent base/common contract
needs a reviewed merge and new fingerprints, including P7's current form ownership.

All695 Source30 input bytes were verified before own-tree compilation. With the
Discovery proposal and four new sources, all699 inputs were checked after successful
AndroidTest Kotlin compilation; aggregate SHA-256
`2de4fd48c7c5e592a0cbe629f0735d6e5c6a786ebee7d4bb2dd77da1e1bc8a4f`.
Temporary source overlays were restored byte-for-byte. No APK was generated.
This is a compiled proposal against the frozen context, not the parent's latest
combined build or an actual SDK run. Native results and the earlier36/4 records are
unchanged and are not evidence for this source.

## Controlled and actual native lanes

The exact production lifetime and frozen common owner compile and pass19 host JVM
tests. They drive a controlled typed platform interface and manual serial queue,
covering late/double callbacks, start/stop exceptions, failure/no callback, cancelled
wait, legacy expiry, modern stop acknowledgement, network unregister failure,
retirement during startup, overlapping generations, and separate Job/SDK exit.
These are dynamic ownership tests, not NsdManager or device passes. The same test
source is prepared for Android as `NativeNsdControlledLifetimeTest`; no Android
execution of it is claimed. Initial compiler assembly/earlier15/18 checks are retained
separately; only the final19-test source is current.

Reproduce using the cached dependencies and the exact frozen common-owner source:

```sh
python3 <stage>/run-controlled-jvm.py --repo <repo> \
  --registry-source <frozen-Source30>/apps/android/src/main/kotlin/com/agenthub/android/NativeEasyWorkMigrationOwners.kt \
  --output <fresh-private-output> --gradle-cache <modules-2/files-2.1> \
  --build-slot <parent-build-slot.py> --java <jdk17-java>
```

`NativeNsdSdkTest` separately prepares two real SDK methods for API26 and37:

1. `actualDiscoveryStartStopNeedsSdkCallback`: real NsdManager discovery must start,
   then stop with its actual callback; actual network unregister and common-owner
   join/reopen are checked. No peer or resolve claim belongs to this lane.
2. `actualOwnedPeerResolutionRetiresOnlyOnObservedTerminal`: register a UUID-specific
   service for this test's inert loopback-bound socket, discover only that owned peer,
   issue actual SDK resolution, then retire. The witness forwards original callbacks
   unchanged and requests no external peer, Host or application-protocol connection.
   The actual winning terminal is recorded. A natural resolve result proves its
   own exit but does not certify the API34+ stop-acknowledgement branch. Missing own
   peer, registration failure or missing callback is a failed/unverified lane, never
   a fake successful resolution. Test-owned advertisement unregister also needs its
   actual callback; closing the socket is not that proof.

Parent supplies `p8NsdBuild` with exact source/App/runner SHA-256 and `expectedSdk`
(26 or37). Package identities, installed APK hashes, SDK and actual API37 permission
are checked before SDK work. No permission window is opened by the test. Protected
default families use the existing64-root/1024-file/262144-byte bounds, including atomic
siblings; no trimming or cap increase. Fresh private evidence UUIDs record observed
signals, whether the stop-ack branch ran, residual stopping state, exact defaults and
cleanup. SDK retirement has one original30-second budget shared with cleanup; failure
does not buy a second retirement budget. Setup/owned-advertisement cleanup are separate
SDK operations, and a timed-out parent must retain their receipts.

Both real methods and the controlled Android class are **unexecuted**. Parent must
freeze a new integrated source/App/runner and schedule each named API26/37 lane with
its device/build fingerprint. If SDK or advertisement ownership remains unknown,
preserve that failed case and reconcile it before another lease assumes a clean
process; do not replay the case to replace its evidence. Actual
EasyWorkDiscovery/Session migration integration, callbacks on target devices, missing
peer cases and API34+ stop-ack branch remain separately recorded. Full issuer/Relay,
physical default scale, model and other P8 gates remain open; no unknown input is
replayed. A5 is false. `parity-delta.json` carries central architecture/product/design/
acceptance registration for parent merge, without overwriting another workline.

On the restored leaf checkout, `pnpm check` passed format, sync checks and lint, then
stopped at the existing `native-copy.json` central feature-record gap. Later root
checks did not run. This failed root check is separate from the successful frozen
proposal compile and controlled JVM results; parent owns the central registration.
