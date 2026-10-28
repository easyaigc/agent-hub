# Text visibility and filled-input assertion correction

This is test-only source preparation for F02/F04/F06/F07/F43/F44/F46, S01/S18 and UI03. Production
ConnectionPage, theme, typography, storage and lifecycle are unchanged. Parent retains
central product/design/acceptance registration and actual device leases. iOS behavior,
Task/project and A6 remain unchanged/paused.

Parent candidate source08e96934, App44a0d17a and runnerb6dd861d produced two immutable
first-Provider measurements: default node163px/lineRight161.85715px and font1.3
node220px/lineRight218.10637px, both one line without ellipsis, paragraph width388px.
Both owned PixelCopy images show the complete EasyWork label. Installed Compose
ui-text-android1.12.1 bytecode defines didOverflowWidth as size.width <
multiParagraph.width; hasVisualOverflow ORs that flag with height overflow. The
constraint-width flag alone therefore rejected these labels without demonstrating
clipped characters. Original failures are retained; this is not a new native pass.

The native check now requires exactly one layout whose complete UTF-16 text equals
the independently supplied original. Every line must have continuous start/end ranges
including whitespace and the final original character, no ellipsis and finite bounds.
Every UTF-16 offset has an ordered box, a matching line/range and bounds inside its
line, layout and the actual intersected node/scroll/component clip. Visible code
points must have a nonzero box; surrogate units, combining marks and formatting units
are still covered individually. A 0.05 physical-pixel tolerance absorbs coordinate
float roundoff; it cannot conceal one clipped physical pixel. Wrapped identifiers
still require multiple actual lines. Constraint/overflow flags remain diagnostic.

TextLayoutResult character boxes describe typographic extents; they do not establish
pixel color, contrast or rasterized ink shape. The first Provider probe and failures
also retain bounded PixelCopy of the owned synthetic component. Parent still reviews
pixels and the complete native matrix. The helper has falsifiable missing-final,
skipped-middle content/range/box, omitted-line, ellipsis, nonfinite, invisible-codepoint,
per-character overhang and actual one-pixel horizontal/vertical clip counterexamples.
It is a test-only AndroidTest source; JVM checks reference that exact file by an
opt-in symlink, not an independently implemented validator.

The actual IME English failure prints EditableText=short and InputText=short, but does
not print AnnotatedString annotation lists. It therefore cannot prove which metadata
caused whole-object equality to fail. Filled-field assertions now compare the complete
original UTF-16 content in BOTH actual semantics channels and retain localized AX
labels, focus, native Done, full submit geometry and actual pointer taps. Each check
records content hashes/lengths, span/paragraph counts and plain AnnotatedString equality;
no text normalization or substring match is used. The JVM counterexample proves that
annotation-only changes can differ in object equality while missing/changed content
or a missing InputText channel remains rejected.

## Actual Main remains blocked by the environment

Both actual-Main methods refused before any launch/callback because original default
AtomicFile .bak/.new files exist. Their owner must retain those originals; the current
guard, default hash checks and no-reset policy stay intact. Copy5/writeIO2 passed in
the parent candidate, IME1/2 passed, four viewport methods failed at the original first
assertion, and Main2 are environment refusals. None is rewritten as a pass.

A safe current route is a separately leased, already empty acceptance installation in
a fresh Android test environment owned by the parent: lease exact device/profile,
package+runner+source hashes, zero default connection/result/AtomicFile sidecars and no
other Activity/runner; then p8ActualMainReady=1 plus p8ActualMainStart=1 starts Main
inside instrumentation. Recheck same PID/Activity, default files before/after and warm
callback identity. This does not authorize clearing/reinstalling the existing phone
installation or treating another environment as this phone's pass.

If the same phone must be used, a future one-shot first-Activity storage lease would
have to be installed by instrumentation before Main.onCreate and consumed there before
ANY default NativeSessionStorage, discovery, restore or result-reader construction.
It must bind exact debug acceptance package, process, random namespace/key, immutable
build hashes and owner nonce, reject retained/live Main and malformed/reused leases,
keep ordinary intents unable to install it, and use the leased storage consistently
for all related readers. Current post-onCreate adoptSessionForAcceptance is too late.
That shared-Main integration is a proposal only, not implemented or verified here.

## Auxiliary validation and integration delivery

The exact native helper passes 18 JVM checks, including both observed line-width
profiles, valid wrapped text and supplementary/combining UTF-16 ranges. The complete
AndroidTest Kotlin sources compile with the existing child-only Session/Main shadows
and 14-key resource overlay; all actual shared source files are restored afterward.
This creates no new APK and performs no Android/Host/model action. The combined native
fingerprint contains 480 source/resource inputs, with auxiliary sources separately
hashed in parity-delta.json. The parent must rebuild its actual integrated candidate.

`pnpm check` passed format, five public-sync tests and lint, then stopped at the
pre-existing central native-copy feature-record gap. The leaf does not edit or bypass
parent-owned central coverage. Apply the two source commits in order, then merge the
delivery delta into the parent product/design/acceptance records; viewport4, IME2 and
Main2 retain their separate device/environment gates. Previous positive-commit/cold
fixtures, their package pair and owner leases remain unchanged.
