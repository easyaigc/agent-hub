# Windows: recover an unloaded desktop conversation

## Problem

A conversation can remain readable in the official catalog after its desktop owner is no longer loaded. History reads succeed, but `canControl` is false. Retrying history does not create an owner. If a Windows desktop-runtime Host has no explicit bridge configuration, it previously omitted `desktop:open`, preventing the mobile send flow from opening that conversation before submitting.

Conversation age is a symptom, not an expiry policy. The distinguishing condition is the missing live desktop owner.

## Implementation

The production Windows desktop-runtime startup opts into an independent prepared bridge. It persists the Host identity before exclusively creating a private `prepared-bridge.json` bound to that identity. Subsequent starts reuse the same file. Explicit prepared, controller or creation drivers take precedence; existing validation policy, invalid configuration and identity mismatches are never overwritten. Managed/shared runtimes and other platforms retain their existing behavior.

Capabilities still require the bridge's existing private-file, exact-Host and live-desktop checks. No history read becomes execution authorization. Both mobile clients reuse their existing explicit-send flow: request a named desktop open, confirm the exact original owner, then submit once. A workspace refresh does not navigate desktop chats. Stable operation IDs and unknown-result handling remain unchanged.

The same recovery exposed a Store installation dependency: its bundled CLI can be readable yet fail external spawning with EPERM. Bounded discovery now prefers the desktop-staged CLI only at an existing official staging path and only if its SHA-256 equals a payload bundled beside the verified current desktop. A configured matching copy is preferred. Mismatched staged copies and loose paths cannot override the bundle. Discovery never launches candidates or restarts the desktop.

## Validation

Validation was performed on native Windows against a running official Codex desktop using isolated Host state and dedicated test conversations. The formal Host service, credentials and business conversations were not changed.

| Scenario                                                                               | Result                          |
| -------------------------------------------------------------------------------------- | ------------------------------- |
| Native private configuration, stable Host identity, restart and live capability        | Passed                          |
| Zero-turn conversation prepared through official metadata API; preparer exit confirmed | Passed                          |
| Conversation initially has no live owner; reading history leaves `canControl` false    | Passed                          |
| Explicit open acquires the exact original desktop owner                                | Passed                          |
| Repeated open uses the same operation receipt                                          | Passed                          |
| One real model input and expected reply in that same desktop conversation              | Passed                          |
| Independent Node process confirms desktop owner and completed reply; exactly one input | Passed                          |
| Isolated Host restart preserves identity, open capability and the same conversation    | Passed                          |
| Android physical UI / installed release                                                | Not run: no ADB device attached |
| iOS physical UI / Xcode                                                                | Not run on this Windows host    |
| Deployment to the formal Host / public release                                         | Not performed                   |

The first native attempt failed before any input because Store CLI selection returned an unlaunchable path. The selection regression is covered by `scripts/windows-host-recovery-smoke.mjs`. A second attempt reached successful cold open but its same-process second observer was rejected by the existing single-bridge retirement guard. Moving the independent observer to a separate Node process allowed the complete test to pass; the guard was not weakened. Neither failed attempt submitted a model input.

Auxiliary checks and the full workspace check are reported separately in the contribution; a build or fixture pass does not replace the missing physical mobile acceptance. Private raw evidence stays outside Git. No real thread IDs, device identities, credentials or conversation exports are included here.

Auxiliary results: build, formatting, lint, typecheck, mobile parity against the public base, Windows private-state/owner recovery, startup CLI discovery, installation/update boundaries, persistence and the 15 JVM `SessionPoliciesTest` cases passed. The default full check first stopped because its private-history parity baseline is absent from the public mirror. With `MOBILE_PARITY_BASE_SHA` set to this contribution's public base, it reached `scripts/runtime-smoke.mjs:899` and stopped at the Windows writable-root path assertion. The full check therefore did not pass; that unrelated managed-runtime assertion was not removed or bypassed.
