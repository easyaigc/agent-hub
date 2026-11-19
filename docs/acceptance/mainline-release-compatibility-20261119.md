# 主线与 iOS 115 的升级兼容核对（2026-11-19）

范围为本地已发布 114 的功能与当前主线组合；不把本地旧分支整段覆盖到较新的主线，也不将历史真实环境证明改写为本次组合通过。来源保留为独立 Git 提交，交付通过 Gitea PR #188。

| 功能           | 组合处理                                                                                   | 当前验证边界                                              |
| -------------- | ------------------------------------------------------------------------------------------ | --------------------------------------------------------- |
| 刷新同步       | 异步投影期间有界等待，保留原游标的空心跳；真实 owner 丢失与投影失败仍准确报错              | 原生模拟器与真实 Electron 已在前一组合通过，最终组合另列  |
| 上滑发送       | 补回原目标、草稿代次和建议快照捕获；聚焦编辑、语音及弹层不误发                             | 独立原生与提交计数分别复验                                |
| 文档内链接     | 保留当前消息文件授权与下载进度；校验原 Markdown 摘要、字面链接、目标身份及当前文件版本     | 解析与授权边界、原生阅读分别记录                          |
| 剩余额度       | 补回精确十进制剩余数量、周期剩余环与重置倒计时；不自行增加额度                             | KMP 共用向量及双端编译；实际服务数值不由 fixture 替代     |
| 语音任务       | 每个完整目标保持独立控制器；离开页面保留封存转写任务、回到原草稿前复核代次与权限           | 合成录音只验证导航和生命周期，不代表麦克风／上游 ASR      |
| 明确重发       | 仅已确认未提交的失败提供显式新操作，原未知操作只查询、不重放                               | Host 证明与原生操作分别验证                               |
| 子 Agent       | 保留新版桌面活动去重与有界摘要，新增具名父子记录分页读取；旧 Host 仍可显示原摘要           | 只读，不增加执行能力；分页不冒充 live owner，来源明确标记 |
| Git／生成设置  | Git 按实际能力开放给 Codex；下一轮设置即时预览，保留主线更新的目标与权限约束               | 现有只读和原草稿语义保持                                  |
| 客户端与未读   | 补回紧凑列表及按完整目标显式清理当前手机未读，保留主线账本事务与故障诊断；不向电脑确认阅读 | 原生与账本边界分列；Android未新增清理入口                 |
| 其他已发布功能 | 保留较新的主线冷启动恢复、可视化、最近项目、速度展示、文件传输和 APNs 隔离实现             | 不把本次局部验证升级为完整功能或实体手机通过              |

新增 `subagents:history:read:v1` 独立协商。`GET /v1/subagents/history` 必须指定原父线程和原消息，仅允许读取其中明确具名的子线程。新分页游标绑定 Host、机器运行目标、父线程、原消息和子线程；拒绝跨目标沿用。旧版稳定原消息游标继续可读；旧序号游标仅在该子线程中唯一匹配时兼容，跨轮歧义明确要求刷新，仍逐次检查父子归属。分页保存记录与原桌面实时记录分别显示，失去 owner 不转成控制权证明。

iOS × EasyWork 计划仍以既有 `status.json` 为唯一进度源；本次专项不重写整项状态、不恢复 Android A6 暂停。构建、辅助测试、真实 Electron／模拟器交互、签名包、正式 Host 部署及下载链接均按实际指纹分别留证。最终验收与发布结果在冻结源码后补录。

Frozen ad5b7212d9eb78791ba5a29e27924e91fb43ea1b passed pnpm check (exit 0, 907.49 seconds), iOS/Android builds, and 21 selected JVM tests. Real Electron/native simulator sync passed: 10 pending-target polls, zero unavailable, unsent draft retained, no model/management writes or read acknowledgements. Voice lifecycle (7) and swipe (5) auxiliary UI tests passed without skips; synthetic voice is not microphone/ASR proof, and swipe has no independent service submission count.

A real named child returned 15 saved-history messages with exact target/parent/child identity; it had no desktop owner. Initial wrong-message and unnamed-child responses were generic 502 failures. The follow-up maps known validation failures to 403/404/409, with separate current-source boundary and HTTP verification. No real next page was present; actual pagination, cross-parent cursor and detail-sheet interaction remain unverified.

The subsequent F08 main delivery retains authenticated EasyWork routes. Its existing proof and this combined build/delta verification stay separate. The final signed IPA is rebuilt from the actual merged main; unpublished earlier candidates are retired rather than relabelled.

## 后续纠错：已发布交互未被完整保留

2026-11-19 用户报告子 Agent 展示回退后，对照已发布114冻结源确认：本记录所述“保留已发布功能”的源码组合未完整恢复单行／按需完整详情／主子共用组件、昵称和任务标题、Codex Git请求、额度精简详情及录音文字点击停止。较早主线还存在重复且仅允许EasyWork的额度／Git弹窗分支；Swift只警告，实际Codex／demo会打开空白页。本表是当时的组合意图及原验证边界，不能再解释成这些行为已全部保留或通过。

当前修复、逐项根因、原生失败及补测、只读真实来源核对、未验证范围和默认回归门禁统一见[已发布交互回退核对](released-workflow-regressions-20261119.md)。原通过、失败及无真实下一页等历史证据继续保留，不改写为本次全部通过；正式提交、合并和安装另计。
