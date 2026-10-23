# Mobile Core

双原生 App 的公共业务核心。A1 输入栏与目标键已通过双端验收；A2 迁入状态／契约；各阶段实际验收结果以仓库阶段记录为准。

`MobileRules` 管理目标键、阅读字节前像与输入栏优先级，`SessionPolicies` 管理能力／原 owner、输入前置条件、生成切换与回执，`OperationPolicies` 管理创建身份／未知结果及事件游标，`MessageProjection` 保留原消息 ID 并投影工具、错误、压缩和最终正文。iOS 使用 DTO façade；Android 直接调用同一 Kotlin 模块，页面按阶段接入。

`MobileHistoryGateway` 只有具名历史读取，平台保留网络／凭据。公共 coroutine 桥接负责取消、单次回调和目标校验，Swift 明确派送至 MainActor。领域 `DomainContract` 读取 management v1 Schema；实际 Codex HTTP 的三个读取响应使用独立 `CodexHttpContract`，不能将领域 draft DTO 当作已实现端点。

阅读判断由 `packages/protocol/fixtures/mobile/reading-policy.json` 单一定义生成 Kotlin／Swift，通知扩展无需链接 Kotlin runtime。CryptoKit／MessageDigest、JSON／Base64 与原子存储留在平台；共同 UTF-8／身份向量核对字节。iOS 升级迁移处理全目标键及旧关联记录，不重发已提交意图，冲突旧数据保留。

同一合成输入位于 `packages/protocol/fixtures/mobile/core-v1.json` 与 `state-v1.json`。JVM、Kotlin/Native、Swift Simulator 与 Android instrumentation 分别执行；Swift 迁移专项还对照 A1 原投影实现。源码与测试工具指纹在运行前后核对，生成资源／framework 在忽略的 `.build/native`。

```sh
IOS_SIMULATOR_ID=<dedicated-simulator> pnpm check:mobile-core
IOS_SIMULATOR_ID=<dedicated-simulator> pnpm check:ios-state
ANDROID_SERIAL=<dedicated-emulator> MOBILE_PHASE=A2 pnpm test:android
```

iOS 使用静态 `MobileCore.framework` 与 Objective-C／Swift 互通，Xcode 在 Sources 前运行 `scripts/mobile/build-ios-core.sh`。保留 iOS 设备 ARM64、模拟器 ARM64／x86_64；macOS framework 仅用于命令行契约检查。ARM64 电脑不执行 x86_64 测试，编译不冒充运行证明。框架不持有电脑本地 Codex wire、UI、安全存储或任意远程执行接口。
