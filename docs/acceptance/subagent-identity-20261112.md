# 子代理快照归属边界（2026-11-12）

本记录仅对应 F18／F19／F20、UI05／UI06、S04／S05 的具名源码与辅助证据，不替代 [Android 当前计划](../plans/android-ios-alignment-20261107/progress.md)或关闭 Q02。

现有 Android 生产路径已经包含加密事件接收、权威 thread/sync 重读、完整 child 详情投影与同 ID Compose 更新。Codex 使用既有 Host 子代理投影及相同卡片；未发现需要新增全新刷新入口的源码依据。实体触摸注入的历史拒绝保留为设备验收问题，不能记作生产刷新路径缺失。

新边界反例在同线程快照内放置两个合法 root：child 的权威正文属于 root A，迟到事件却为同 ID 指向 root B。原投影保留 A 的正文和终态，却用 B 改写 rootRunId。修复令快照已包含的 child 整体优先，事件仅补充尚未出现的 child；不拼接事件片段，不新增输入或读取命令，不修改传输预算。

共享 [SubagentProjectionTest](../../packages/mobile-core/src/commonTest/kotlin/com/agenthub/core/SubagentProjectionTest.kt) 增加归属反例及迟到终态、换 branch 边界。使用已有 Kotlin 2.4.21／serialization 1.11.0 缓存和 JDK 17，直接编译生产 SubagentProjection、MessageProjection、MobileRules 与原测试文件，JUnit 4.13.2 修复前 11 项中 1 项失败，修复后 11 项通过；这是 JVM 辅助验证，未运行 Gradle 或原生构建。

[MobileEasyWorkSubagentTest](../../apps/android/src/androidTest/kotlin/com/agenthub/android/MobileEasyWorkSubagentTest.kt) 新增合成加密 peer 的事件触发长详情刷新与双 root 归属用例，待原生编译和执行。原有 NativeSubagentPresentationTest 已有同 ID 展开详情替换、目标隔离和长文首中尾断言，本轮未执行，不能复用为本候选通过。

下一步由总控在独占构建和设备时段核对完整候选指纹，先运行共享测试及上述 Android 原生两类用例，再在独立验收包与真实 Electron／原 owner 下核对 child、root、branch、首中尾及正文摘要、展开重访和实时增长。Android 实体设备、API37 及 iOS 共享变更回归分别登记；不得以合成 peer 或 JVM 成功代替真实 DEV。
