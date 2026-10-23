# Agent Hub App 图标

App 显示名称为 `Agent Hub`。2026-10-11 按用户指示，以 EasyWork 桌面图标为基础，缩小主体并将黑底改为柔和的浅蓝紫渐变。

图标沿用 EasyWork 的蓝色带状环绕标志与星形镂空，主体占画布宽度约七成，增加周围留白。背景由浅紫过渡至冰蓝，中心有柔和亮度变化，镂空也透出相同背景；淡阴影帮助标志在浅底上保持层次。使用内置 `image_gen` 工具编辑 EasyWork 的透明 PNG，输出经 `sips` 缩放为 1024 × 1024。

正式资源位于 [`AgentHubIcon.png`](../../apps/ios/Resources/Assets.xcassets/AgentHubIcon.appiconset/AgentHubIcon.png)，是 1024 × 1024、不含透明通道的方形 PNG。图片保持方形，由 iOS 应用系统圆角遮罩；Xcode Asset Catalog 编译其他所需尺寸。Debug 与 Release 使用同一个 `AgentHubIcon` 资源。

名称同时用于主屏幕、设置的关于信息与通用通知标题。工程名、模块名、包内文件名及持久存储格式沿用现有命名。正式设备构建使用 `com.easyai.agenthub`；原个人签名安装保留至加密迁移完成真实验证，不把显示名称更新当作跨标识数据已迁移的证明。

## 本次验证

2026-10-11：确认 PNG 为 1024 × 1024、无透明通道，检查 40px、60px 缩略图及 Xcode 编译后的图标。使用 Xcode 27.0 执行 `pnpm build:ios` 通过；独立 iPhone 17 Pro / iOS 26.0 模拟器安装通过，并在 Device Hub 的系统搜索中核对新版浅蓝紫图标。本次验证范围为资源、编译、模拟器安装与系统图标显示；真机安装另行验收。

正式图标 SHA-256：`ccb34187c3a1f5f4a3632648ea101c0f7af4d9e1c24f3e5e4f9894a6bb2e1b3c`。本次检查证据保存在忽略的 `.local/agent-hub-icon/easywork-light-*`。

## 通知图标同步

系统通知使用主 App 的同一 `AgentHubIcon`，通知扩展没有独立的品牌图标资源。2026-10-11：以原正式标识 `com.easyai.agenthub` 和既有团队完成主 App、通知服务与内容扩展的真机签名构建及覆盖安装，通过 Device Hub 核对主屏幕显示新版图标。首次前台本地测试通知仍显示旧 C+ 图标；编译产物中的 `AppIcon` 为新版，现象符合 Apple 已确认的系统通知图标缓存问题，用户解锁后复测仍显示旧图标；随后改用独立的 `AgentHubIcon` 资源名称及构建号 19，`pnpm build:ios`、真机签名构建及覆盖安装均通过，主 App 与两个通知扩展版本一致；前台测试横幅仍显示旧 C+。设备重启状态待核对，通知显示同步尚未通过真实验收。[Apple 官方说明](https://developer.apple.com/forums/thread/775787)

本次构建、安装及图标资源摘要保存在忽略的 `.local/agent-hub-icon/notification-*`。本地测试通知只验证图标显示，不作为本次真实任务 APNs 投递或锁屏声音验收证据。

## 本次编辑提示词

```text
Edit this EasyWork desktop logo into a polished, lighter iOS app icon. Preserve the reference logo's exact recognizable geometry: the diagonal blue/cyan wrapped ribbon orb, its star-like negative-space openings, and all curved ribbon strips. Do not replace it or invent a different emblem. Scale the entire logo down uniformly so its widest extent occupies about 68% of the square canvas, centered optically, with generous balanced padding. Preserve its vivid cyan-to-electric-blue coloration and existing subtle dimensional ribbon shading. Replace every transparent/background region, including the holes between ribbons, with a soft luminous very light periwinkle-to-icy-blue gradient: upper left pale lavender, lower right pale sky blue, a gentle pearly glow around the center. The background must feel fresh, soft, light, and elegant, with enough subtle color richness to stand out on a white screen. No black or dark navy background, no black patches in negative-space holes. Add only a very faint diffuse contact shadow to separate the blue logo from the light background; no heavy extrusion or extra decoration. Full-bleed opaque square artwork, no rounded outer corners baked into the image, no border, no text, no new symbols, no mockup. One clean production-ready 1024x1024 app icon.
```

## 历史：EasyWork 黑底版

曾直接复制 EasyWork 现有 `apps/mobile/assets/easywork-app-icon-ios.png`，SHA-256 为 `09401ed66f4b1a8ce1113deeac4f518fa963e3d2c1a3d1ebc7e0d0fe0dfb5115`。已核对 EasyWork 0.1.1 桌面包的 `Contents/Resources/icon.icns` 与该项目 `build/brands/easywork/icon.icns` 摘要一致：`4a8b42ab15cd6e6071fa6b9da53d047e4cbdb4d3723d262bbda771315ad66bf8`。该黑底版通过 Xcode 27.0 编译、独立 iPhone 17 Pro / iOS 26.0 模拟器安装与系统搜索图标检查，证据保存在忽略的 `.local/agent-hub-icon/easywork-*`。这些验收结果属于黑底版。

## 历史：蓝紫底 AI 方案

曾使用内置 `image_gen` 工具编辑白底图标，保留两块几何轮廓，放大主体、调整颜色和材质；输出经 `sips` 缩放为 1024 × 1024。该方案已由 EasyWork 图标替换，其编译与缩略图检查通过，模拟器检查未完成。以下保留当时的优化提示词。

```text
Use case: precise-object-edit / logo-brand.
Asset type: final production iOS app icon for Agent Hub.
Input image: the attached current white-background two-piece AI mark is the EDIT TARGET. Preserve its identity.
Primary request: turn this understated logo on an empty white canvas into a polished, substantial app icon. The user says the emblem is too small and the white background feels overly bare.
Keep: exactly the same TWO-piece abstract AI silhouette — a long diagonal capsule leaning from lower-left to upper-right, and a shorter rounded lozenge at its lower right. Keep the relative proportions, angle, clean separation, placement of the smaller piece and the recognizable geometry of the current mark.
Change 1, SCALE: enlarge the entire mark to occupy about 72 percent of canvas width and 70 percent of height, optically centered. It must feel bold and immediately readable on the home screen, with balanced safe margins, not a small logo surrounded by empty space.
Change 2, BACKGROUND: replace the empty white background with a rich fully opaque edge-to-edge blue/indigo/periwinkle gradient. Deep indigo at the lower-right and outer corners, saturated sapphire through the center, restrained luminous violet at upper-left. Use only a broad, smooth ambient light field to give depth and richness; there should be no visible circles, patterns, extra objects or white border.
Change 3, MARK MATERIAL: recolor BOTH shapes to luminous milky white with subtle pale-lavender highlights and icy-blue edge shading, maintaining strong contrast against the saturated background. Make them shallow satin ceramic inlays with a very restrained soft bevel and delicate close contact shadows, a tactile premium app-icon finish. Keep the silhouette impeccably simple and the front faces nearly flat, not bulging toy plastic, metallic chrome, sparkly crystal or excessive glass reflections.
Style: refined, confident, minimal technology app icon. The richness comes from scale, color contrast and restrained material depth. The AI mark remains the only subject.
Composition/output: actual square 1024x1024 icon artwork, perfectly straight-on. Background covers the entire canvas. Keep the outer canvas square; the iOS system supplies the rounded-corner mask.
Constraints: exactly two emblem shapes. No letters or words, no font glyphs, no robots/faces, no cloud, no network nodes, no rings, no sparkles, no extra symbols, no grain, no texture noise, no decorative frame, no outer white tile, no transparency, no mockup or presentation sheet, no perspective tilt, no watermark. This should be visibly larger and more fully designed as an app icon while preserving the user's selected AI mark.
```

## 历史：白底简约初版

初版使用内置 `image_gen` 工具，以用户提供的 EasyAI 标志截图为参考生成预览；输出经 `sips` 缩放为 1024 × 1024，经用户确认后用于正式资源。参考截图不纳入仓库。该版 SHA-256 为 `fc2dc988fc89555318e8e51a57d5352ee9ee78d7679311e868d1102afb240e6f`，其编译、模拟器安装与主屏幕检查通过，证据保存在忽略的 `.local/agent-hub-icon/minimal-*`。首次安装因通知内容扩展缺少 `CFBundleDisplayName` 被系统拒绝，补齐该必要字段后复验通过；这些结果属于初版。

```text
Use case: logo-brand.
Asset type: minimal app-icon design preview for Agent Hub.
Input image: the attached EasyAI screenshot is the reference for ONLY the small symbol directly to the left of the word EasyAI. Ignore the lettering, tagline, photograph, toolbar and all surrounding content.
Primary request: recreate the visual idea of that small elegant symbol as a clean high-resolution standalone app icon: extremely simple rounded geometry that subtly suggests "AI", rather than spelling the letters with a font.
Subject: exactly TWO separate blue-violet geometric shapes. On the left, a long rounded capsule leaning diagonally from lower-left to upper-right, approximately 33 degrees away from vertical, with fully semicircular ends. At the lower right, a much shorter softly rounded lozenge, nearly circular but with a subtle diagonal orientation matching the long capsule. Carefully follow the relative size, alignment and clean separation of the two shapes in the screenshot. Together their silhouette should have the reference's understated abstract AI feeling. The long capsule is about twice the height of the smaller shape. Both shapes are solid and simple, with immaculate smooth contours.
Style: flat, vector-clean modern technology brand mark. Each shape has only a smooth subtle gradient transitioning from violet at its upper end to rich cool blue at its lower end. No outlines, bevels, 3D, shadows, texture, reflections or glow.
Composition: one centered two-shape emblem, occupying about 55 percent of the square canvas width and height, with generous balanced surrounding space. The smaller shape is beside the lower half of the long capsule, separated by a narrow clear gap.
Background: completely plain opaque cool white edge to edge. Square 1024x1024 canvas, no rounded outer corners baked into the image.
Constraints: deliver ONE actual icon image, not a mockup, not a presentation board. No words, no EasyAI text, no Agent Hub text, no tagline, no visible font glyphs, no robots or faces, no hub nodes, no connections, no network orbits, no sparkles, no cloud shape, no extra geometric pieces, no border, no watermark. The result must be as restrained, simple and memorable as the two-piece mark in the reference.
```

## 历史：Codex+ 生成记录

旧版参考本机原版 Codex 图标的蓝紫渐变、云状底形、白色符号与柔和立体材质，采用独立四瓣底形与 `C+` 组合。`C` 表示 Codex，`+` 表示添加电脑，云状底形表示集中连接多台电脑。该资源已由 Agent Hub 图标替换。

当时使用内置 `image_gen` 工具，以本机原版 Codex 浅色图标作为风格参考生成，经尺寸转换后保存到 Asset Catalog。初版生成提示词如下：

```text
Use case: logo-brand. Asset: final iOS icon for the app named codex+, a phone hub for controlling Codex projects and chats across multiple computers. Input image is a STYLE REFERENCE ONLY: the original Codex desktop icon. Design a distinct new icon that belongs to the same visual family, preserving the reference's icy white tile, sculptural blue-to-periwinkle-to-lavender cloud form, satin/glass material, delicate lit edges, soft shallow depth, and beautifully restrained native macOS/iOS craftsmanship. Replace the original terminal >_ concept with an original custom C+ monogram. Primary shape: one generously rounded, subtly cloud-like blue-violet badge, centrally positioned, softly dimensional, with a simpler distinct four-lobed silhouette rather than tracing the original logo. Inside this badge, put ONLY a clean white uppercase C and a true white plus +. The C should be custom geometric and terminal-inspired, using a squared rounded shape with a large open right side; do NOT use a generic circular font C. Integrate the plus close to the C's opening so the two become a unified monogram, with matching thick stroke weight, optically balanced, legible instantly at home-screen size. Treat the white monogram as slightly raised porcelain with softly rounded edges and very subtle highlights. The C means Codex, + means adding computers, and the cloud-like badge means one connected workspace. Keep the badge approximately 64 percent of the square canvas width with generous white surrounding space. Use the same elegant lavender at top and electric cool blue at bottom as the reference, with smooth intentional gradients and light coming from upper left. Background is opaque softly cool white, edge to edge, no pre-rounded outer tile, no black border, no surrounding transparency, no floating app mockup, no drop shadow outside the canvas. The result must be the actual square 1024x1024 icon artwork. No words, no device drawings, no extra symbols, no terminal > or underscore, no neon green, no wire networks, no texture noise. Make a polished distinctive app icon, not a literal copy of the reference.
```

## 历史：C 与云朵融合修订

使用内置 `image_gen` 工具编辑上一版图标，保留蓝紫云朵与白色加号，将方圆字形替换为随云瓣起伏的连续 `C` 曲线。C 造型修订提示词如下：

```text
Use case: precise-object-edit / logo-brand. Edit the attached current codex+ iOS icon. Keep the existing blue-to-lavender four-lobed cloud silhouette, luminous edge, cool white opaque background, overall composition, scale, and white plus sign unchanged. Redesign ONLY the C so it feels organically designed together with the surrounding cloud. Replace the current generic squared letter C with a bespoke cloud-contour C: a single continuous broad milky-white sculpted ribbon that follows a nested version of the cloud's upper, left, and lower lobes, open on the right toward the plus. The C's top sweep should rise gently into a rounded cloud shoulder, the left arc should swell around the cloud's left lobe, and the lower sweep should curve naturally into its bottom lobe. Use elegant organic convex curves with subtle smooth concave shoulder transitions, rather than straight horizontal bars or a font glyph. Keep the inner counter beautifully open and simple, and keep the C unmistakably readable as C at 60px. The ribbon's two ends should taper subtly and curl slightly toward the plus, with rounded softly beveled terminals; all curvature should echo the outer cloud's radii, making the letter and cloud feel like one designed form. Treat this C as a satin porcelain inlay gently emerging from the blue glass cloud, integrated shallow material depth and ambient occlusion instead of a sticker-like floating letter. The plus remains crisp and balanced in the C's opening with clear separation, unchanged in shape, size, position, and white material. Refined native Apple-style icon material, restrained highlights, matching reference gradient. It should be a noticeably more sculptural and cloud-integrated C, not simply the same squarish C with extra shadow. No extra details, no extra glyphs, no words, no device drawings, no decorative network lines. Deliver a single actual square production icon asset, 1024x1024 opaque PNG artwork, full canvas cool white, no pre-rounded canvas corners, no outside transparent frame, no mockup.
```
