# Positive test scope-value assertion correction

Parent Stage18 reported three of four methods passing on the phone: the closed-form
rollback method and both direct Session methods. The positive callback method reached
its saved callback once, original real touch/full geometry/focus/form/window checks
and both original wait gates, then failed at the first `TargetScope` equality check.
`TargetScope` is an ordinary class and does not define value equality.

Commit `473d3ddc` changes exactly the three scope comparisons in that positive-success
method to a local six-field assertion: `agentId`, `accountAuthority`, `accountSubject`,
`hostId`, `workspaceId`, `runtimeId`. Expected and actual whole scopes must both exist;
each nullable account field is compared directly, so null, empty and nonempty remain
distinct. This covers current saved scope, fresh encrypted restore and switching back
to the original target. Production `TargetScope` and the separate direct-boundary
comparison are unchanged.

No wait budget, input/tap, original assertion, fixture, protocol, production code or
parent checkout was changed. The entire AndroidTest Kotlin source set compiled using
the existing child-only shared-source shadows/resource overlay:483 source/resource
inputs, source-set fingerprint `77c66060df2fb8dbbfe842c7dba164026000a0101b4d26b74c056070d4e34836`.
This is compilation evidence, not a native rerun or a parent integrated APK.

The old failed positive UUID `b066f972-c7cc-415f-ab23-03ef9e6ebc7c` stays sealed. Parent
must rebuild its combined runner, use a new lease/UUID and independently record the
positive method. The preceding three passed methods retain their original candidate
fingerprints; this leaf adds no device pass. iOS and Task/project remain paused, A5
is not closed and A6 remains paused. The delta record is supplied for parent-owned
product/design/acceptance registration.
