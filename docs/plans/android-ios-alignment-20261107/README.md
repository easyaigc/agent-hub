# Android / iOS 最新功能对齐

更新：2026-11-07。冻结主线 `dd1682fb4546360d6018f783480fd2a30a833533`，已按用户补充进入核心优先的双端/双Provider实施与验收。

| 文档                                                         | 用途                                                                         |
| ------------------------------------------------------------ | ---------------------------------------------------------------------------- |
| [开发计划](plan.md)                                          | 新优先级、工作包、依赖、完整交付门槛与连续推进规则                           |
| [完整功能清单](features.md)                                  | 75个F及对应功能、交互、界面；70个活动产品项、4个暂停项、1个Host依赖项        |
| [逐提交账本](commits/README.md)                              | 350个iOS历史节点（332净变化+18净零来源），另补114个共享边界，共464个唯一提交 |
| [验收方案](acceptance.md) / [75行矩阵](acceptance-matrix.md) | 功能/交互/界面三门、迁移24组资料、系统/设备/错误恢复与真实DEV证据            |
| [当前进度](progress.md)                                      | 唯一实时开发/验收状态、阶段交付及下一步；过程不堆在总表                      |
| [待验与暂停](pending.md)                                     | 环境依赖、原UNKNOWN及任务/项目业务暂停范围                                   |

机器可读索引：[features.json](features.json)、[commits.json](commits.json)。过程继续归入[阶段档案](../android-alignment-20261022/records/README.md)。旧10月计划及原验收结果保留为历史，不再作为当前唯一功能清单或调度入口。

新顺序：核心连接/聊天/原回执 → 流式完整展示与控制/审批 → 附件/互传/语音 → 未读/生命周期/通知 → 完整迁移 → 剩余增强 → 冻结交付。每项都核对iOS/Android和Codex/EasyWork，见[三方规则](three-way.md)、[75项四格矩阵](provider-matrix.md)及[五条工作线](workstreams.md)。当前按 C2-SUB 的子代理与长详情推进，未验收部分保留。

C1-RID 已按原回执只读限定范围通过 [PR #163](https://git.51easyai.com/BCAI/agent-hub-app/pulls/163) 合入主线。当前 C2-SUB 子代理与长详情已实现、部分验收，代码保持独立分支；最新结果以[进度](progress.md)和[阶段120](../android-alignment-20261022/records/stage120-c2-subagent-native-reading.md)为准。
