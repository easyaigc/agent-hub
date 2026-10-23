# 文档索引

远程功能逐项对照使用 [Codex / EasyWork 验收清单](acceptance/remote-access-checklist.md)，用户真实手机的本轮结果见 EasyWork iPhone 验收（本地文件 `docs/acceptance/easywork-iphone.md`）。

| 文档                                                                | 内容                                                             |
| ------------------------------------------------------------------- | ---------------------------------------------------------------- |
| [架构设计](architecture.md)                                         | 系统组件、数据流、运行时接入、可靠性和安全边界                   |
| [MVP 范围](product/mvp.md)                                          | 核心流程、首版范围、里程碑                                       |
| [iOS 初版 UI / UX](design/ui-ux-v1.md)                              | 聊天主界面、左侧项目与对话、客户端切换、视觉规范                 |
| [应用协议](protocol.md)                                             | 当前接口、领域契约草案、未来通信语义                             |
| [可行性验证](feasibility.md)                                        | 本机验证证据、限制、后续 PoC 验收                                |
| [开发指南](development.md)                                          | 环境、工作空间命令、iOS 和 CI                                    |
| 本机 iOS 验收（本地文件 `docs/acceptance/ios-local.md`）            | 原生交互、真实历史、复现命令与剩余边界                           |
| Codex 异步问题验收（本地文件 `docs/acceptance/async-questions.md`） | 不阻塞任务的问题、手机回答、跳过及真实联调结果                   |
| [ADR-001](adr/0001-monorepo-toolchain.md)                           | 原生 iOS 与 pnpm + Turborepo 的选型                              |
| -------------------------------------------------------             | ---------------------------------------------------------------- |
| [本机移动开发环境](mobile-development-environment.md)               | Android／iOS 工具、模拟器、USB 调试与本次证据                    |
| [本机 iOS 验收](acceptance/ios-local.md)                            | 原生交互、真实历史、复现命令与剩余边界                           |
| [Android 开发计划](plans/android-native.md)                         | 完整原生 Android 的阶段、交付物、依赖与完成门槛                  |
| [上游合并迁移计划](plans/android-upstream-migration.md)             | A6 暂停、iOS／Host bridge 逐提交合并、Android 对应迁移及双端回归 |
| [双端功能清单](product/mobile-parity.md)                            | F01–F58 功能、源码入口、共同断言与维护规则                       |
| [Android 阶段实施](acceptance/android-stages.md)                    | A0–A8 实施状态、逐阶段模拟器门槛与证据索引                       |
| [双端 UI 规格](design/mobile-parity.md)                             | iOS 对照、设计 token、UI 状态与系统差异                          |
| [双端验收规范](acceptance/mobile-parity.md)                         | S01–S18 场景、环境矩阵、指纹与真实证据                           |
| [ADR-002](adr/0002-native-mobile-shared-core.md)                    | KMP 业务核心与两端原生 UI 的复用方案                             |
| [Codex 异步问题验收](acceptance/async-questions.md)                 | 不阻塞任务的问题、手机回答、跳过及真实联调结果                   |

文档中的“已实现”描述当前仓库代码；“已验证”描述已执行的实验；“拟实现”或“草案”描述后续目标。单机实验通过不代表已完成跨电脑或 iPhone 实机验证。
