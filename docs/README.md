# 文档索引

额度与余额的新需求见[实施计划](plans/agent-usage-20261028.md)、[类型与约束](design/agent-usage-contract.md)及[手机 UI](design/agent-usage.md)：F73 已按确认的修订 6 落地协议、Codex／EasyWork 适配及双端原生入口；真实与辅助结果见[验收记录](acceptance/agent-usage-20261028.md)。

Android 的当前范围、已完成/未完成、优先级与待验项统一见[开发计划与进度总表](plans/android-alignment-20261022/progress.md)；详细过程按主题保存在[分阶段档案](plans/android-alignment-20261022/records/README.md)，总表不再追加运行流水。

手机附件快捷入口见 [详细执行计划](plans/mobile-quick-attachments-20261024.md)：iOS 首批已接入最近图片轻提示、加号快选、拍照、选图、文件与系统粘贴，均先加入本地草稿，点击发送后才上传。辅助检查与真实 DEV 分别记录；外部分享为后续批次，Android A6 暂停要求保持。

Android 已获准按计划分阶段实施，当前专用分支、验收门槛与提交记录见[分步进度](plans/android-alignment-20261022/progress.md)。
Windows 已有对话的本轮恢复与独立真机结果见 [Windows 同步复核](windows-conversation-sync.md)，正式原连接验收与独立安装的结果分别登记。

Android 的原始依据见[217 条逐提交对照](plans/android-alignment-20261022/commits.md)和[冻结功能/UI/场景矩阵](plans/android-alignment-20261022/parity.md)。业务项目/任务管理暂缓，A6 保持暂停。[更换后的 Android 真机环境结果](acceptance/android-environment-20261021.md)保留为当时的环境证据，当前阶段结果以总表和对应档案为准。

本轮问题与收敛顺序见 [Windows Trace、桌面接管与 iPhone 验收问题梳理](plans/windows-trace-convergence-review-20261022.md)。P0 生命周期、P1 原 owner 恢复和 P2 Trace 预算已分别闭环；各项证据、历史失败，以及 P3、手机和正式发布的剩余范围在计划中单列。

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
