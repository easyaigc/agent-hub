# Host 只读操作与恢复映射

本审查依据[统一 Host 协议规范](unified-host-protocol.md)，覆盖 F09 连接恢复及 F72 文档链接读取。实现、模拟器与真实桌面结果和交付只记录在[唯一台账](../plans/ios-easywork-alignment-20261112/README.md)。F75、A6 的暂停范围保持；本次不展开 Git 页面开发或整项验收。

## 实际调用链

审查基线：App／Codex Host `1d0e7131`，EasyWork Host `3f26f35d`。HTTP 与加密信封是同一业务读取的适配入口，不新增 RPC。

| 共同读取                | Codex Host                                                                                 | EasyWork Host／Adapter                                                                              | App 消费与恢复                                                                                   |
| ----------------------- | ------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `project-git.status.v1` | `GET /v1/project-git`，核完整目标和项目，调用 `ProjectGitService.read`                     | `project/git/status`，严格 v2 参数，经绑定与 `files:read`、目录授权，调用 `RemoteProjectGit.status` | `AgentClient.projectGit`；`ProjectGitRules` 校验后消费；连接失败后可在同一目标重取，不查询写回执 |
| `project-git.files.v1`  | `GET /v1/project-git/files`，核目标、项目、快照、工作区和游标                              | `project/git/files`，经相同边界调用 `RemoteProjectGit.files`                                        | `AgentClient.projectGitFiles`；保持快照与工作区校验；读取恢复仍有界                              |
| 文档内部链接解析        | `GET /v1/attachments/resolve-document-link`；原 Thread、来源附件、相对引用和源摘要授权读取 | `resource/resolve-document-link`，原 Thread、source grant、版本、摘要和目录范围逐项验证             | 现有 `EasyWorkDocumentFileResolution`；取消或离开不呈现迟到结果，不产生新的模型输入              |

生产 EasyWork 路径为 `DesktopEncryptedHost` 完整目标和设备授权 → `RemoteCommandService` → `RemoteRuntimeBridge` → 对应读取服务。Git 读取原本绕过持久写命令账本；iOS 漏分类后转而查询不存在的写回执，Host 映射无法弥补这一 App 适配错误。修复只恢复原读取分类，公共页面、草稿和发送状态机继续复用。

## 分类与兼容例外

共同向量 `packages/protocol/fixtures/mobile/operation-recovery-v1.json` 约束 Swift 和 EasyWork 的操作分类。桌面在协议包中的窄分类函数由真实命令入口使用，并以同一向量验证，不按方法前缀猜测。

- 普通只读查询可在断连后使用原 request ID、目标和参数有界重取。连续失败结束本次读取，不转成业务提交。
- Thread subscribe／unsubscribe 与通知 register／unregister／preferences 属于原订阅管理，保持既有恢复规则；不得据其绕过命令账本的实现称为无副作用读取。
- `command/status`、`thread/open-status` 是查询原操作的特殊入口。桌面保留原匹配与核对逻辑，不把它们简化为普通 dispatcher 调用。
- 写入与未知操作继续保存稳定 ID 和不可变意图，丢结果后只查询原回执；缺失或不确定结果不允许再次发送。原管理类适配器自行核对的例外保留。
- 授权范围不由恢复分类推导。完整目标、证书、设备绑定、EasyWork account／grant／DPoP、目录与文档来源校验不变。兼容旧端，不重算旧操作指纹或迁移原账本。

## App 名称分支的有限盘点

仅盘点上述 App 基线中 `RootView.swift` 与 `SessionStore.swift` 直接判断 EasyWork agentId 的 60 处，不能据此宣称全仓审计或运行缺陷复现。

| 分支语义                                                         | 数量 | 收敛边界                                                                                 |
| ---------------------------------------------------------------- | ---: | ---------------------------------------------------------------------------------------- |
| 原生操作、布局、读屏与滚动                                       |   35 | 依据实际渲染／交互策略渐进复用，保留命中区域、选中和阅读位置                             |
| Git 菜单                                                         |    2 | 同一 Menu 有两处相同入口；后续使用共同能力与项目就绪状态，先核实际界面；不因此启动暂停项 |
| Markdown 渲染                                                    |    1 | 沿用同一 `MarkdownBody`，将策略显式化时保留内部链接和来源授权                            |
| 输入决策／原生发送按钮                                           |    2 | 图标、标签和可用性使用同一共同决策，实际执行继续核原目标                                 |
| 草稿清空                                                         |    2 | 保留准确目标、完整 Draft、修订和恢复门禁，不能收敛为无条件清空                           |
| 首次创建与未知恢复                                               |    3 | 保留附件清单、原操作身份和只查询语义；协议差异留适配层                                   |
| 身份迁移与账号兼容                                               |    5 | 保留新安装设备身份、迁移校验、原凭据和未知操作保护                                       |
| 显示名称                                                         |    2 | 允许客户端名称用于展示                                                                   |
| 运行时建议、当前设置、分支说明、资源缓存、模型共享条件、错误投影 |    8 | 按各自共同能力和窄策略判断，逐项复核，不能一并删除检查                                   |

本次读取恢复修复不修改这些页面和状态分支。后续每项收敛先说明共同入口、Host 映射不足之处、旧数据／迟到结果保护和受影响回归范围。

## 验证层次

共同向量与真实 Swift `requestData` 的故障回归是辅助检查；应覆盖读结果丢失、断连后原身份重取、第二次失败有界退出，以及写入／未知操作只查询一次。真实桌面命令入口须证明读取不持久化写回执。原生模拟器、实际 Electron 及部署按实际执行范围另外登记，不用源码或 fixture 补签功能通过。

Android 原传输与恢复实现不由此变更；共同向量可作为后续一致性审查输入，不能据 iOS 或桌面结果标 Android 通过。
