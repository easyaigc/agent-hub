# Test root Surface and inherited content color

The two original parent dark screenshots were inspected: selected EasyWork text and
literal target values are black on dark cards, while the outer activity is white.
The fixtures supplied only MaterialTheme(darkColorScheme), which does not draw the
background or replace LocalContentColor. Production AgentHubApp already wraps its root
in Surface(color=colors.background). This is a fixture root defect, not evidence for
changing production text/theme colors. Parent source64f4c3ec/App3f93361a/runner611a6070
still bind33 behavior-method passes; the dark visual gate remains unclosed.

Actual Provider/form, copy, lease and viewport fixtures now use that same native Surface
with the current theme background and its automatic contentColorFor(background). The
constrained viewport Surface keeps exactly its320dp width and360/320/280dp height; it is
not wrapped in a full-screen minimum constraint that would erase the short viewport.
The real Sheet/IME fixture also inherits the root Surface. No production color, token,
field/value, callback, visibility/overflow or pointer assertion is changed. Positive
commit fixtures use the same test-root correction in their separate stage.

The parent must reshoot and inspect actual dark Provider and target values under the new
runner; no semantics-only claim or compile result closes visual acceptance. Four strict
TextLayout overflow failures remain independent and still require the separately added
actual layout/IME geometry and cropped synthetic PixelCopy diagnostics. No screenshot
or device operation was performed in this chat; only the supplied original pixels read.
