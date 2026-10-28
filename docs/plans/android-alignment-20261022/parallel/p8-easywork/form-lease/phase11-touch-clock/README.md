# Actual form touch and rule-owned clock progress

Parent candidate cbddbc96 / App9c97812e / runner8f26c84d passed canonical4 and the two
explicit Session boundary methods. The actual form/button2 timed out at12s and retain
their original failures and UUIDs. Raw final-observations AND sidecar close records for
609f5030 and aa6a22bf each contain one claim POST and one GET, resolution approved,
held0, no relay/read and savedCallbacks0. Thus the first claim-pending gate finished
and the approval action ran; the failure is at the second final-read-held gate. Those
requests are synthetic, not a real Host/model submission, and do not justify a replay.

The current helper used a real runBlocking/delay HTTP polling loop for both gates.
That loop did not advance the Compose rule's clock, inherited by the actual form's
rememberCoroutineScope. In installed Compose ui-test1.12.1, waitUntil advances one
rule-owned frame when autoAdvance is enabled and yields to Android between checks.
The native input dispatcher sends touch MotionEvents directly to its owner View;
a Window.Callback-only probe would miss this route. These facts were read from the
matching official source artifact, not inferred from a different Compose version.

The minimal scheduling commit makes pending accept a wait strategy with its existing
real-time implementation as the default. The actual form button lane waits for idle
after its one performTouchInput click and supplies the rule's waitUntil to BOTH gates.
Each remains12s and the existing50s method limit remains unchanged. There is no fixed
sleep, manual arbitrary clock jump, semantic-button click, second tap, changed case ID,
new acceptance fallback or direct Session substitute. Direct Session and cold lanes
retain the original default wait. The closed-form candidate-disappearance observation
also uses the same12s rule wait because the original form owns its cleanup coroutine;
freshly reopened Session restoration retains the real-time wait. The harness gap is confirmed in source; native
causal closure and UI success still require a parent run with fresh leased UUIDs.

## Bounded observation, separate from the scheduling change

A non-consuming pointer modifier surrounds only the synthetic test scene. Initial and
Final passes record real delivered pointer positions, phases, consumption and event/
observation uptimes; it does not mutate Compose state or advance the clock. Pointer
records cap at32; lifecycle and window-focus records cap at16 each, and losing records
fails. One actual down/up pair with one pointer must lie inside and within1.5 physical
pixels of the original button center. No pointer is injected by this observer.

Before input, the original button must be fully visible against unclipped dimensions,
the actual scroll viewport and system-visible window. The expected center, root/window
bounds, screen/decor origins, density and actual IME/system-bar insets are recorded.
The exact Activity/window identities, resumed lifecycle, window focus, current focused
View class and mounted form-owner identity are retained; pause/stop/destroy or focus
loss fail. The same original form must survive both pending gates. No original secret,
field text, screenshot, raw token or arbitrary window content is captured.

Each gate records start/end wall time and rule clock, autoAdvance, poll count and the
condition result. An immutable button-observations.json is written in finally, also on
failure, with the last phase and pointer/lifecycle/focus traces. It complements the
unchanged fixture final-observations; it does not relabel a timeout as success. Proof
snapshots post to the Activity main loop but do not call a Compose synchronization API
inside the gate condition/final snapshot. The framework wait alone drives progression.

The saved callback once, pre-metadata candidate existence, exact target selection,
closed-form rollback, grant/claim/unknown raw bytes, hashes and default-file protection
assertions remain unchanged. No production source, Node fixture, sourceGraph protocol,
Sidecar/Host/device or parent checkout is changed. Product/design/acceptance records
remain parent-owned; this stage is an AndroidTest synchronization/diagnostic delta,
not a production UI or iOS behavior change. Task/project, iOS and A6 remain paused.

The complete native test sources compile with the existing child-only Session/Main
shadows and14-key resource overlay. No APK or device execution is produced in this
leaf. Parent must rebuild its combined runner, run form2 under new leases, then retain
separate direct-Session2/cold2 and broader matrices. Original failed IDs remain closed.
