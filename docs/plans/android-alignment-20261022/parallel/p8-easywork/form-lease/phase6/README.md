# Pairing viewport, filled-field AX and real IME preparation

The reference is iOS `Sheets.swift: AddClientView` (native scrolling Form, segmented
Provider, keyboard Done) and `EasyWorkPairing.swift: EasyWorkConnectionForm` (four methods,
name, QR/manual/relay inputs and public target/account metadata). Android keeps the
existing 48dp controls, 17sp field, 14sp target value, 12sp label and normal productText
resolver. Target/account identifiers remain literal metadata; no sign-in or raw grant,
private key, claim token, attachment or original secret is displayed or captured.

The actual ConnectionPage scroll viewport now consumes remaining IME insets, including
when hosted by NativeSheetHost. Provider tabs form an AX selectable group. EasyWork's
native single-line fields keep their localized AX labels after filling and implement
Done by clearing focus and hiding the real IME. The generic NativeSheets fields and
shared five-file boundary are untouched. The common outer viewport also hosts the
Codex Provider, so its existing connection-layout regression remains a parent gate.
No new text keys, per-language tables or generated resources are introduced here.

Four native methods use the actual product ConnectionPage in an explicitly constrained
320dp-wide 360/320/280dp-high layout with zh/en, light/dark and font 1.0/1.3/2.0. They
scroll to and verify full unclipped target bounds, then deliver native pointer taps to
Provider tabs, menus, discovery rescan and local-invalid submit controls. Filled field
labels, retained values after Provider replacement, nontruncated wrapped long target
identifiers and non-actionable account metadata are asserted. No text-exists-only
assertion counts as reachability. Synthetic invalid QR/scheme/code fail local parsing
before networking; no real endpoint, issuer/Host/model or camera is configured.

Two additional methods mount the actual NativeSheetHost dialog on a blank acceptance
Activity. They find the exact package-owned dialog window, require actual software IME
insets, focus/edit the long public relay address at its final character, exercise native
Done, then check the whole relay submit target above the actual IME and tap it. An absent
software IME or unavailable target fails rather than becoming a pass. Dialog/window
geometry and IME insets are retained as observations. These two are native IME gates;
the four constrained layouts do not claim physical short-screen or IME acceptance.

Every method requires exact .acceptance + .acceptance.test identity before Activity
launch, a fresh bounded namespace/key alias, exact installed app/runner hashes, and
stream-hash preservation of all default mobile-v1 encrypted namespaces. Only synthetic
values are used. No screenshots, screen dumps or original secret logs are generated.
Failures retain their independent namespace and original observation folder.

All six methods are compile-prepared only. Parent owns package/device/permission leases,
real matrix execution, screenshot review if needed using synthetic inputs only, central
feature/design/acceptance registration, ordinary catalog generation and integrated
rebuild. Previous 30 form/copy methods, Main2 and controlled IO2 remain separate pending
gates. A5 is not closed; Task/project, iOS and A6 remain paused.
