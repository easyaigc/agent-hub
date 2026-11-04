# EasyWork 移动端消费者整合（2026-11-04）

## 第一组：F73 共享额度解析

从既有未提交消费者差异提取百分比数值边界，整合基线为 `637928fe28f6c28e40497bf4a8978cf398de927a`。现有 iOS 与 Android 均使用共享 `UsageRules`；字符串、布尔值、null、负数及非有限值必须拒绝，合法超过 100% 的数据保留。生产端、协议 Schema、Host/Adapter/SKILL 与产品 UI 保持。

复用既有 `UsageRulesTest` 添加的行为用例，覆盖上述非法数据及有效 123.5%。2026-11-04 本机以指定 Java 17、Android SDK、Kotlin/Native 2.4.21 和既有 Gradle warm cache 执行 `node scripts/mobile/gradle.mjs :mobile-core:jvmTest --tests com.agenthub.core.UsageRulesTest :mobile-core:linkDebugFrameworkIosSimulatorArm64 --no-daemon --offline`，构建成功，实测 25.48 秒；JUnit XML 为 3 项、0 失败、0 错误、0 跳过。生成的 iOS Simulator ARM64 `MobileCore.h` 中 `UsageRules.project` 保留 `NSError **` 与 Swift `project(json:targetJson:nowMillis:)` 错误出口。此为共享核心辅助验证，不代表 iOS App 或真机通过。此前 605 秒人工中断及旧注解位置的 JVM 通过记录独立保留，不改写为本轮结果。原 iOS 68 个限定场景和 Android 阶段84辅助回归保持各自源码与环境指纹，不能据此算本组新验收。

本组不新增整项功能、物理手机、Windows、VPN 或 APNs/锁屏通过结论。双端总体 F73 验证状态保持，A6 仍暂停。其他移动端候选与运行中工作分别整合，不在本组覆盖旧文件或重放业务任务。

## 第二组：iOS EasyWork 授权与原操作恢复

整合基线为 `07d05c1c1368119984ded10bc5805b1e340a3593`。已批准 grant 从原保存记录恢复；刷新保留原 operation ID/请求与响应摘要，并在验证后持久化轮换凭据。LAN 刷新只在完整目标、公钥、证书和明确能力声明均匹配时使用。408/429/5xx 不推成授权撤销，撤销提示按连接身份去重；Codex 的原 401 提示策略保留。

`turn/steer` 的处理完成不等于输入已接受；过期或已结束 Run 的拒绝不标为 submitted。原输入可能已被队列持久接收而尚无命令响应时，查询原 queue/status 并核对 thread/operation；旧 Host 无端口时保留原未知结果，不新建或重发输入。主线事件唤醒、附件生命周期与 native/display/runtime 建议协议保持。

实际辅助检查：

- 回执归一化及原队列查询：11项通过，7.545秒。
- Relay原生Swift fixture：通过，7.003秒；合成DPoP、撤销/暂态、提示身份及重定向边界。原 fixture 变量重名编译失败单独保留，修复后只补失败阶段。
- 真实本机TLS/WebSocket fixture：通过，1.316秒。
- `pnpm build:ios`：通过，102.736秒。

以上为准确源码下的辅助/构建证据，不是本候选新增物理手机、Electron或模型验收。原68个模拟器子场景保持其原源码与依赖指纹；本组合并后不直接复用成新通过，产品整项及Android状态保持。本组不部署、不改共享服务、身份或密钥。

## Group 3: notification/Relay and named management contracts

This group carries the mobile consumer dependency closure: FCM/APNs registration branches, explicit Relay FCM configuration/encrypted delivery, strict notification runtime parsing, and pin/unpin/archive/unarchive/delete/rename intent/outcome contracts. Existing v2 private-write guards and main APNs behavior are retained. No generic shell or method forwarding is introduced.

Only chat-management is added to the public barrel. The package check registers the existing FCM and management regressions. Connect rules, desktop-suggestion-observations, Host and Adapter belong to the canonical 24G group and are untouched. Runtime/runtime-version suggestion semantics stay on main.

Ten mono lossless audio fixtures were independently verified sample-for-sample against deterministic synthesis, including source precision. Stereo and compressed-reference candidates are excluded pending an original generation chain. Their Android consumer migration and actual coverage are separate; fixtures alone do not prove it. Synthetic migration/push vectors contain test-only identities and data, not user archives.

Auxiliary results: pnpm build passed in 17.684 seconds; notification-contract passed in 0.118 seconds; the four direct test files passed 34 tests in 4.919 seconds. The first pnpm check was interrupted after 105.240 seconds when its required iOS helper reached a Gradle distribution download, due to an overly narrow executor instruction. It had not reported a business/type/Schema failure and is not counted as a full pass. Formal checking resumes using the already verified local toolchain/cache; the original interruption is retained. Provider acceptance, native arrival, platform UI and whole-feature status remain distinct. No production push configuration, keys or deployment is included.

## 本轮连贯候选与证据边界

两组已在主线 `78b9dda6a61a7fcb6ebef041cd06a21b517e00cd` 上整合，保留 #137 的 F67/F69、生产建议字段及 Trace 修复。合并只需处理验收／设计说明和按 change ID 合并的 manifest，运行时代码按各组输入摘要核对保持一致。原接收夹具与契约夹具补齐实际依赖，契约摘要同步真实 schema；原失败日志独立保留。正式检查链按有指纹的前序证据和失败后续跑记录覆盖，不记为单个未中断的 pnpm check 进程通过。后续仅补合并影响的文档／格式／接线检查；PR CI、真实 App 连接、产物分发分别登记，不能以源码整合替代。

## 后续消费者：EasyWork 聊天管理

从同机既有实现按依赖提取具名聊天管理、批量归档与能力受控重命名；保留主线其他菜单、建议、语音和删除后的无项目恢复行为。EasyWork适配器严格核对真实Host/设备/原命令/标题后映射本地连接键；Codex继续使用原HTTP严格契约、Host绑定和机器身份政策。结束本地等待只释放当前操作等待，原回执保留可查。

本组新指纹下的共享规则三组JVM测试15项、0失败/错误/跳过，iOS Simulator ARM64框架链接和App构建均通过；契约、建议及持久化边界检查通过。原接线/文案缺项和编译失败记录保留，只重跑受影响环节。既有68场景不自动复用为当前候选通过。EasyWork源仓库的生产端尚待24G准确master基线对账，当前仅记录真实WIP dispatcher/管理服务/命令账本依赖；不先覆盖主线或重放14个旧提交。Android共同语言目录按正式生成器同步，业务管理入口与原生验收状态不升级；实际连接和原生交互验收分开记录。

候选已正常快进至#138主线 1c91b5e36bfd83202b8e327b8fcc1340c7599872，其源码树与验证基线 c3ad3d2ae4fc00c404f76d46ab6200ae45155af5 完全相同，仅提交包装变化。最终本机复测iOS构建68.234秒，契约7.067秒、建议45.394秒、持久化2.768秒均退出0；原生交互、真实桌面/手机连接仍单独开放。
