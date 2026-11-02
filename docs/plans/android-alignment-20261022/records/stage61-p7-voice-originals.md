# 阶段61：iOS 迁移中的惰性语音原件

2026-11-02，F55/F56/F64；基线 `e9257cb253b62fbfb95e62a371b00853b1563edc`。本轮接入既有 iOS container 的语音原件保留，不是新的 iOS↔Android 整包互通格式。P7 仍为部分完成；Android sealed/provisional 原件实现与既有验收保持各自边界。

## 原问题、实现与兼容

既有 iOS Payload 没有包含 PendingVoiceStore 的原音频及 metadata/control，单独复制普通会话不足以保留完整语音现场。Root 采用 Source90 R1→R4 的六文件 source-only 补丁；将 pending、terminal、expired、orphan 原音频与关联资料保存为**独立惰性档案**，不注入普通恢复、prepare、上传、重放或许可路径。原已提交操作、回执与未知结果保持，不重新发送。

- 无原件时维持 Envelope/Payload v1；非空原件使用 v2 与 distinct AAD，使旧 reader 显式拒绝。顶层闭合解码拒绝重复、未知字段，原 source witness 在加密前后保持稳定。
- active、全部 cold stages、saved、incoming 共同预检物理 64MiB／128 文件；完整 ownership/content journal 在 copy 前 fsync，不能在复制后补记归属。
- 完整 cold 场景可恢复；partial、unknown、缺失 journal、链接与冲突保留并阻断。既有 finalized 不补 journal；仅原 journal 提供完整 byteproof 的 exact duplicate 可清，不删除未证实原件。
- 业务失败恢复原业务状态；已有效发布的惰性原件仍保留、计 quota 并可再导出，不能宣称所有原件均随业务 rollback 删除。

这是平台既有容器的版本增量。共享 migration-envelope-v1 schema、协议端点及 Host 不变；Android 格式、密钥和原件权限不由此转换为 iOS 的格式或执行权限。

## 功能、交互、界面与剩余门槛

| 范围       | 已实现／辅助证据                                                             | 仍待验收                                                             |
| ---------- | ---------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| 原件与版本 | 原音频、metadata/control 惰性保留；空 v1／非空 v2、独立 AAD、严格解码        | iOS 原生 Payload 导出/导入、旧 reader 拒绝及完整 SessionStore 运行态 |
| 存储与失败 | 共同物理 quota、copy 前 journal、完整 cold 恢复；不完整状态保留阻断          | 真实 I/O 崩溃、进程死亡/激活、24 域与实际存储故障矩阵                |
| 用户交互   | 导入不赋予正常语音恢复、录音、发送、重试或许可；原目标与已提交操作保持       | 原生迁出/迁入、失败返回与新草稿不被覆盖的双端逐项核对                |
| 界面       | 未新增普通语音自动恢复/发送/许可入口；本轮 SDK/CLI 不作 UI 通过              | UI06/UI12/UI21 的页面、文案、失败提示、短视口、方向、字体与可访问性  |
| 平台差异   | iOS container 与 Android sealed/provisional 归档各自保留，不新增共享格式承诺 | iOS↔Android 整包互通、双端 Native/真实 DEV 与物理矩阵                |

## 证据分层

**匹配复用的 macOS CLI。** Source90 R3 独立复核的两项 P2 已关闭，无新 P0/P1/P2。R4 增加 two disjoint sequential publish、exact duplicate 删除后 next publish 两个真实 filesystem case，连同原用例，以 `-Onone` 实际编译 PID53449、运行 PID53498，各 wait0；54 条 unnecessary-try warning、无 error。Root 已读四份完整 raw、四源 H 与补丁链。当前 T 的四份 CLI 输入逐字匹配该结果，**本轮未重跑**；CLI 不覆盖 iOS Payload 的条件编译分支或 SessionStore 运行态。

私有原件 `source90-r4-root-boundary-cli-review.json`：918B，SHA-256 `48660f6ce4496c60d371b49c90e634360ef782507a8d0bd9c5836393615a87e2`。匹配四源为下表的 EncryptedMigration、PendingVoiceMigration、PendingVoiceMigrationStages 与 CLI 脚本。

**本次 iOS SDK 构建。** 正式 `pnpm build:ios` 原 PID17241、wait0／BUILD SUCCEEDED；六文件构建前后精确匹配，主 target Sources58→60，两 helper 各一次，四份 production Swift 实际编译。Root 已核完整约1.55MB stdout、261B stderr、chunk H、Info.plist、当前 binary 与源六份。产物为 `com.codexmanage.ios`、`iphonesimulator27.1`、arm64+x86_64，binary 119231376B，SHA-256 `e38ee94cb323f8b9348f5cd0b93f24df71f76e4dcf1bf9f7949855866e83958a`。

私有原件 `stage61-root-ios-sdk-review.json`：862B，SHA-256 `232f9f4ccb4d4d12d2dbf6ff9b0154af0a2693860011a232ca643a6246909a9f`。Simulator **未启动**，构建不算 Native 方法、交互、界面或真实 DEV 通过。本次 focused 检查亦已完成：`generate:mobile-parity`、限定文件 Prettier、`check:mobile-parity`、`git diff --check` 原 child 分别 PID34446／34512／34553／34634，实际 wait0；六源前后不变。Root 已核9个检查/状态 child及18条完整 raw，复核原件 `stage61-root-focused-checks-review.json` 为1602B、SHA-256 `06396ce6f167dc5345eef9c184dd8a449916eb168fcb222cd930d96cd0db75f1`。未改 TypeScript/工具实现，阶段60已成功的全仓检查按原范围保留，不重跑冒充本轮结果；本阶段提交继续单独核对。

## 实际六文件指纹

Root 接入记录 `stage61-root-source-application.json`：1501B，SHA-256 `2d85dc5c9bd0b6d48f5dd5e39096b65df5c3c0a81023d4ccf82e90ecaa72efe2`；source-only patch SHA-256 `697cc5b5287a72d2973e72485d41e9bffab5be4fc8c214a90265c18a7433db49`。

| Git 相对路径                                              | 字节数 | SHA-256                                                            |
| --------------------------------------------------------- | ------ | ------------------------------------------------------------------ |
| `apps/ios/Sources/EncryptedMigration.swift`               | 10757  | `ca00d635574836e418a04ab20c2f4bc29a576c6dec422965f3710070d8026260` |
| `apps/ios/Sources/SessionStore.swift`                     | 267803 | `161bd772bff06b62a015ffc2b70d3b7d3e37893ecb0a11c9697b46a147817d09` |
| `apps/ios/CodexManage.xcodeproj/project.pbxproj`          | 50348  | `8bd4fcb01cdae0a991fa8d26aaeb6f294773a5d2a37b2a835cf581c21462c877` |
| `apps/ios/Sources/PendingVoiceMigration.swift`            | 33536  | `1458235e4029a6f67b11c3f856171481bee6c4e4285d3cec6d1a1f37a6863bd4` |
| `apps/ios/Sources/PendingVoiceMigrationStages.swift`      | 28335  | `e97006b14b8a88ebb5c8b773218b2d8bbb2c7fa9f1476219db2b41d53e8aad98` |
| `scripts/mobile/verify-ios-pending-voice-migration.swift` | 36994  | `227227dfcb3f2cd8b86986d42f942ca5d9fa3a794cb1c2f9707c5e5d5cf13dc7` |

## 交付、失败保留与资源

上一阶段 [Source94](source94-main-cold-entry-accepted.md) 已由 [PR108](https://git.51easyai.com/BCAI/agent-hub-app/pulls/108) 合入 main `8ff12d7d21b28c86a131984e415f99d80ec576ce`，CI11104 workspace26844／ios26845 成功，阶段60本地及 origin 分支已清理。本阶段仍在专用分支交付中。

原 Source91 reader 失败及原图/namespace/key 保持原记录，不升级、不重放。本阶段的 partial/unknown/缺失 journal/冲突材料保留阻断；已有效发布的惰性原件不因业务回滚被删除。任务和项目管理、F75、iOS A6 保持暂停。Host/模型新增0、累计221，P4为205，另1个未知原操作封存，不查询或重放。

Root 核完整 SDK 原件及当前 binary 后，已在无 open FD 下回收四根可重建 cache（7019文件/links、547900KiB）；当前 Products app/dSYM、六源与必要证明保留。重复 stdout 无损 gzip 为76916B并逐字解压核对，known/stderr/meta不动。收尾回执 SHA-256 `2aee51d161cca39eac3f85171c23cc831b41f1c251891459b4268278d86e20a8`。
