# Source77：长导入铃声的动作与受控视口

2026-11-01，阶段52，F53/F56、UI11。当前r4：**中浅／英深两项受控组件限定通过**。本候选修复长标题动作布局、删除读屏名称和高多行按钮裁字；真实OS字体／TalkBack／完整通知生命周期和物理DEV仍待验。

## iOS对照与产品变化

iOS `NotificationSettings.swift:59–63` 保留独立预览／删除动作，并给删除按钮“删除＋原铃声标题”的可访问名称。Android原行先测量无权重的长预览按钮，不能保证后面的删除宽度；通用“删除”也缺少条目身份。现在动作行fillMaxWidth、预览weight(1f)，给删除动作保留宽度；使用现有“删除”译文与原显示标题组成读屏名称。

r3的实际PNG进一步发现高多行TextButton的pill圆角裁掉首尾字，尽管语义矩形完整。r4为该预览按钮复用 `MaterialTheme.shapes.small`，避免高pill几何侵入文字；不增加颜色／字号／词条，不改iOS、选择／存储／预览生命周期或已提交操作。

## 原失败与证据边界

- r1本机脚本路径错误在构建／保护执行之前停止；原记录保留。
- r2编译缺少Modifier.testTag扩展的导入，以真实exit1结束；只修该import后进入r3，不把原失败改成成功。
- r3 SDK通过；中文方法原生终态为通过，但collector漏认已有default-storage-proof并以exit1停止。主统筹只读核对原99捕获、32个本地APK及38根，保存data baseline；没有重放原Native。data核对不等于collector原运行通过。
- 主统筹逐张读取r3原PNG后确认pill裁字，**视觉失败保留**。原data baseline schema为`source77-root-data-baseline-v1`，UiAccepted=false、retainedUiFailures=1；40根／files468／no_backup94仅作为r4安装保护基线，不升级为UI通过。

r3视觉核对SHA-256：`87caddf53faeda882b59a41cc508b71ba097579e2b32740ff6c21e2dc5834db3`，原PNG SHA-256：`bdea278f5aa07195fc7f0f714722ac2a5160e1ebe067ae7c1b5ccb37d348d88e`。新candidate的结果只按新字节／新独立方法登记，原失败、原件和回执不被覆盖。r3安装100与旧中文99共199份捕获只属旧失败资料，不并入r4通过数。

## r4指纹与逐项结果

- canonical：`f477057f79c3a9a692c01ecef425bb38951d579bd105acbefdbed6abf5b6ba61`（856输入）；expanded：`86a247efe2d124155c5ec0bd4ce97efc628065fbd341019b19ef3d99277f6c46`（869输入）。
- App：`4a8fcdeff4e07c3d536fd9a465ad63bfaea4a71c400952a758a44d97205006cb`。
- Test：`8e3f99f0e5bfd5d34994a020a4b78142a9dc554943cc34c69eebb5dec2a9d264`。
- SDK主统筹核对：`22853c54d21e033f159542ac8bd3b5c6e6929e6a2b4ee56ff68ac356d54578e2`；实际Gradle退出0、实际／冻结输入、三APK ZIP／CRC／单v2签名及12份SDK工具原始流核对通过，旧33APK和9540份冻结输入保持。SDK并非UI通过。

安装：限定通过，100份捕获，主统筹核对`ac833fe9fd56080051134f2ea76782acabad563a0f79c86eb03bce5eeede8847`。

| 场景       | 原生／原件核对 | PNG逐张视觉 | 本次状态         | 主统筹核对SHA-256                                                  |
| ---------- | -------------- | ----------- | ---------------- | ------------------------------------------------------------------ |
| 中文／浅色 | 限定通过       | 限定通过    | 受控组件限定通过 | `7eb8114c8d8ffc697b09bc7d1a1d35ce4a5ad61e0b45a49eacf427d21ebe6b21` |
| 英文／深色 | 限定通过       | 限定通过    | 受控组件限定通过 | `c90e0e6409e409e76913efc29847a24ff7004288a9cebfb307e2c7c412461074` |

r4本候选已核对的安装与Native合计298份捕获；两Native各自的数据终态与实际PNG视觉分别判定。安装保护基线40根、中文之后42根、英文之后44根；最终files480／no_backup100，两个旧FIFO仍仅元数据核对，每阶段35个本机APK的全部字节及当前身份独立复核。每新夹具四份文件（含准确default-storage-proof）完整核对。12项纯检查及原始流已由主统筹核对通过（`53d789fa9802fe1181e0aa9b51bb6b02be37d80ccfc0e820b0e8a2afa1af413b`），只作辅助，不替代UI。

中文／浅色实际PNG SHA-256：`fd6978a328298e9b349a89d469b5982e46fe8ed6503a789f4b6fc9f83faaec72`；逐张视觉核对：`7d625373a2a6bc5035e2be215f50666cbaf7fd88bf61c0cb60a6d0e279f3bbcb`。

英文／深色实际PNG SHA-256：`2fbefadc05d8f9ac53a8b3ec01afa00c2787929e53d3c6059252bcc1348fad22`；逐张视觉核对：`d226f533ed37e1af2ac00773974e35f2eda8e6eec0373952eb3d1cf68ea5387c`。

两方法均使用独立synthetic导入、80字标题、受控320dp和Compose fontScale2。先滚动较高预览，再核对删除而不再次滚动；48dp可点击区、完整unclipped与visible四边一致、视口内边界及动作不重叠必须全部成立。超高／裁切仍失败，不接受clipped矩形代替完整可见。PNG／window元数据与几何JSON分别核对；随后只删精确新建的synthetic铃声，并核对全局／分类回默认和原默认资料。

预览按钮只核对动作与可点击性，不实际播放；系统通知未发、用户原铃声未删。组件截图不是系统合成全屏；Compose字体覆盖不是实际OS font2；语义名称不是TalkBack真实播报。默认存储证明、旧精确根及两个FIFO元数据保护独立核对；writer关闭／全部私有handle／物理executor和全进程FD未新增证明，旧失败不关闭。

## 继续推进与待验

P7仍优先，下一步Source78三PID原件／原回执方案仅准备就绪、未编译／验收。[Source75](source75-uncommitted-audio-v2.md)／[Source76](source76-real-seal-checkpoint-warm.md)原记录按各自候选保留：mixed v1/v2、多原件quota／48MiB低堆、真实row I/O／断电／进程死亡与冷读取、完整24组／iOS音频资料缺口继续待验。

P9实际OS字体／TalkBack、旋转／完整视觉、实际试听／音频路由／通知渠道、低API／OEM／供应商、消费进程冷读及物理DEV另列；业务通知、任务／项目、F75原生项目UI与A6继续暂停。未知结果不重放，原失败与准确现场保留；本阶段不增加Host／模型输入。当前APK／冻结源和准确UI夹具因后续保护仍保留，无具体依赖的中间物按精确归属回收。
