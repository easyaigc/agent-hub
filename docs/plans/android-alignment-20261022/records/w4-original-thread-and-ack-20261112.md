# EasyWork 原线程回执与停止确认边界（2026-11-12）

范围：F37/F38/F39/F43/F44/F61，Q04/Q09。当前状态由唯一计划 `docs/plans/android-ios-alignment-20261107/progress.md` 维护；本文仅记录本轮源码候选和辅助证据，不能关闭整项。

## 发现与实现

- iOS 原 `receipt(client,id)` 未捕获原线程，可能在 `SessionStore.accept` 拒绝前按外层或结果中的错误线程发出 `queue/status`。现在 AgentClient 协议显式传递原 `threadId`；待核对与已结束等待的两个查询调用点均取不可变原输入，EasyWork 在任何队列查询前核对外层和结果层，队列查询只用捕获线程及原消息 ID。Codex 适配器仍使用兼容的原 Host GET 格式，并校验返回线程/操作/Host。原始操作、正文和附件未改写，也不重放。
- 老 Host 缺少或返回 null 线程字段时，不推断未投递，也不猜测队列线程；保留捕获目标并返回 UNKNOWN。已明确标注 queue 的结果仍需准确线程及权威原队列回执。
- Android 停止确认不再使用会将字符串 `"true"` 转成 true 的 `JSONObject.getBoolean`，仅接受 JSON 布尔 true，并在请求前拒绝空原线程/运行 ID。iOS stop/cancelQueue 不再将 JSON 数字 1 桥接为 true。停止 ACK 仅表示服务端确认，不证明工具进程已退出。
- 既有原队列执行/取消、追加旧 Turn 拒绝、原操作 journal、持久化与新草稿隔离已有生产路径；本轮未以未验收为理由重复开发。

## 辅助验证与边界

复用现有 Node→Swift 小型编译入口，直接抽取生产解码器及 receipt/stop/cancelQueue 方法运行，远端为可记录请求的合成 session。新增错误外层/结果/一致错误线程、非字符串线程、空捕获线程、旧 Host 缺失/null 线程，以及真假/数字/字符串/null/容器取消确认。与既有原 ID、目标、UNKNOWN 和队列证据向量一起运行。`node --test --test-concurrency=1 scripts/easywork-receipt-normalization.test.mjs scripts/easywork-unknown-queue-receipt.test.mjs scripts/easywork-client-queue-evidence.test.mjs` 共 77 项通过（7.168 秒），其中停止/取消 20 个 ACK 组合位于一个测试方法中；Swift 四文件语法解析和 75F 生成/源码摘要检查通过。旧源码以相同反例失败，保留原失败日志，不修改预期。

Android 增加三个原生加密对端测试：严格停止 ACK、无效身份零请求、丢 ACK 后仅查询原指纹/ID且只发一次 turn/cancel。本轮未执行 Android instrumentation。

受本次空间和设备时段限制，未运行完整 pnpm check、Android/Xcode 构建、原生设备测试或真实 Electron/模型任务。CLI Swift 辅助通过不代表 iOS App 已编译、真机通过或 Q04/Q09 已关闭；已有真实证据保留各自指纹。最终命令/结果/耗时、源码摘要和资源回执见本任务私有 W4 交付报告。

## 后续真实 DEV 验收

总控分配独立候选与设备后，先编译 iOS/Android 及专用测试；使用准确原 owner 的新验收输入核对 queue 顺序、取消、提升、追加、停止和恢复。丢 ACK 只查原 ID，桌面独立确认输入次数、运行/队列归属与最终结果；同时检验新草稿、换目标及迟到结果。Q09 历史 UNKNOWN 仅查询既有原 ID，不借本测试重放。物理 iPhone 15 Pro Max 使用 Xcode/CoreDevice/XCTest，无线调试；Android 按总控分配的独立验收包/设备执行。API37、短视口、主题、字体与完整 AX 仍单列待验。
