# EasyWork 移动端消费者整合（2026-11-04）

## 第一组：F73 共享额度解析

从既有未提交消费者差异提取百分比数值边界，整合基线为 `637928fe28f6c28e40497bf4a8978cf398de927a`。现有 iOS 与 Android 均使用共享 `UsageRules`；字符串、布尔值、null、负数及非有限值必须拒绝，合法超过 100% 的数据保留。生产端、协议 Schema、Host/Adapter/SKILL 与产品 UI 保持。

复用既有 `UsageRulesTest` 添加的行为用例，覆盖上述非法数据及有效 123.5%。2026-11-04 本机以指定 Java 17、Android SDK、Kotlin/Native 2.4.21 和既有 Gradle warm cache 执行 `node scripts/mobile/gradle.mjs :mobile-core:jvmTest --tests com.agenthub.core.UsageRulesTest :mobile-core:linkDebugFrameworkIosSimulatorArm64 --no-daemon --offline`，构建成功，实测 25.48 秒；JUnit XML 为 3 项、0 失败、0 错误、0 跳过。生成的 iOS Simulator ARM64 `MobileCore.h` 中 `UsageRules.project` 保留 `NSError **` 与 Swift `project(json:targetJson:nowMillis:)` 错误出口。此为共享核心辅助验证，不代表 iOS App 或真机通过。此前 605 秒人工中断及旧注解位置的 JVM 通过记录独立保留，不改写为本轮结果。原 iOS 68 个限定场景和 Android 阶段84辅助回归保持各自源码与环境指纹，不能据此算本组新验收。

本组不新增整项功能、物理手机、Windows、VPN 或 APNs/锁屏通过结论。双端总体 F73 验证状态保持，A6 仍暂停。其他移动端候选与运行中工作分别整合，不在本组覆盖旧文件或重放业务任务。
