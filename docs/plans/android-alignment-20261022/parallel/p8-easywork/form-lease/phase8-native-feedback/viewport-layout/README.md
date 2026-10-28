# Strict Provider layout failure diagnostics

All four constrained viewport attempts failed at the original Provider EasyWork
TextLayoutResult assertion before entering form fields. Parent raw source64f4c3ec and
App3f93361a/runner611a6070 bind those failures. The instrumentation log combines three
assertions on one source line and contains no measured sizes/overflow values, so a
production layout cause cannot yet be concluded from it. No product UI change or pass
is invented in this diagnostic stage; all original failures remain immutable.

Before the same strict assertions, each check now saves actual text hashes/lengths,
TextLayoutResult size, paragraph size, constraints, density/font scale, line bounds and
ellipsis/overflow flags, semantic clipped/unclipped/root/window bounds, scroll clip,
synthetic viewport and actual IME/window geometry. A failed check also requests bounded
PixelCopy of only the known synthetic component rectangle in the exact acceptance
Activity window. It records capture status/failure separately and never captures the
system composite, another App, an original draft or a credential. No screenshots have
been taken in this chat. The original full text, visual-overflow, wrapped identifier,
full-visible touch bounds and actual pointer taps remain required. There is no retry
until overflow disappears or relaxation of the assertion. Parent must execute this
source to obtain measurements/owned pixels and then resolve the demonstrated cause.
