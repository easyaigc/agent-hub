import assert from 'node:assert/strict';
import { readFile, writeFile } from 'node:fs/promises';
const source = 'design/mobile/tokens.json';
const tokens = JSON.parse(await readFile(source, 'utf8'));
assert.equal(tokens.version, 1);
const geometry = Object.entries(tokens.geometry).filter(([key]) => key !== 'unit');
geometry.forEach(([key, value]) =>
  assert(
    /^[a-z][A-Za-z]+$/.test(key) &&
      typeof value === 'number' &&
      Number.isFinite(value) &&
      value >= 0,
  ),
);
const colors = ['userBubble', 'userBubbleText'];
for (const name of colors)
  for (const mode of ['lightRGB', 'darkRGB'])
    assert(
      tokens.colors[name][mode].length === 3 &&
        tokens.colors[name][mode].every((n) => typeof n === 'number' && n >= 0 && n <= 1),
    );
const kotlin = `// Generated from ${source}. Edit tokens, then pnpm generate:mobile-design.\npackage com.agenthub.android\n\nimport androidx.compose.ui.graphics.Color\n\ninternal object MobileDesign {\n${geometry.map(([key, value]) => `    const val ${key} = ${value}f`).join('\n')}\n${colors.map((name) => `    fun ${name}(dark: Boolean): Color = if (dark) Color(${tokens.colors[name].darkRGB.map((v) => v + 'f').join(', ')}) else Color(${tokens.colors[name].lightRGB.map((v) => v + 'f').join(', ')})`).join('\n')}\n    fun toolDetail(dark: Boolean) = Color(if (dark) ${tokens.colors.toolDetail.darkGray}f else ${tokens.colors.toolDetail.lightGray}f, if (dark) ${tokens.colors.toolDetail.darkGray}f else ${tokens.colors.toolDetail.lightGray}f, if (dark) ${tokens.colors.toolDetail.darkGray}f else ${tokens.colors.toolDetail.lightGray}f)\n}\n`;
const swift = `// Generated from ${source}. Edit tokens, then pnpm generate:mobile-design.\nimport UIKit\n\nenum MobileDesignGeometry {\n${geometry.map(([key, value]) => `  static let ${key}: ${key.endsWith('Alpha') ? 'Double' : 'CGFloat'} = ${value}`).join('\n')}\n}\nenum MobileDesignColors {\n${colors.flatMap((name) => ['light', 'dark'].map((mode) => `  static let ${name}${mode === 'light' ? 'Light' : 'Dark'}: [CGFloat] = [${tokens.colors[name][mode + 'RGB'].join(', ')}]`)).join('\n')}\n  static let toolDetailLight: CGFloat = ${tokens.colors.toolDetail.lightGray}\n  static let toolDetailDark: CGFloat = ${tokens.colors.toolDetail.darkGray}\n}\n`;
for (const [file, content] of [
  ['apps/android/src/main/kotlin/com/agenthub/android/MobileDesign.kt', kotlin],
  ['apps/ios/Sources/MobileDesign.swift', swift],
]) {
  if (process.argv.includes('--write')) await writeFile(file, content);
  else
    assert.equal(
      await readFile(file, 'utf8'),
      content,
      `${file} is stale; run pnpm generate:mobile-design`,
    );
}
console.log('Mobile design colors/geometry synchronized in Swift and Kotlin');
