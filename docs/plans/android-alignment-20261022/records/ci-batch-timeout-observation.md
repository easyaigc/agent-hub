# PR70：Worker 整批超时的计时观察

2026-10-30，阶段 36。PR70 原 head `416a349e` 的 Linux run10802/job26327 在 `actual Worker batch transport: batch-timeout` 的 `answer.elapsed < 1000` 断言失败；iOS26328通过。本机完整检查通过不能覆盖该失败。日志中的 1663.935ms 是整条测试耗时，原日志没有输出 `answer.elapsed` 或阶段时间线，根因尚未证实。

MacBook128 已从新 HTTPS 入口完成只读源码与原日志诊断；后续修复停在该会话的人类授权核对，未代人回答。原任务及诊断证据保留。本地继续审查和交付，不重放旧已完成的文件改写修复。

当前只增加辅助夹具的有界观察，生产实现与断言保持：

- Owner 原样调用实际 `Atomics.wait`，记录真实 timeout 实参、返回状态、控制字，以及读取和关闭的进入/返回时刻；结束后恢复原函数。
- 固定 Native Worker 的 bootstrap 原样调用实际 750ms timer，记录创建、触发、清除；自然退出时保存有界记录，保留原 callback 的参数、this 和 Timeout 对象。
- Main 仍等实际 Owner、Worker 和 child 全部退出后读取记录；缺失或超界明确标为 diagnostic-incomplete。
- 测试输出一份不超过 1900 字节的有效诊断 JSON。原 `elapsed < 1000`、读取拒绝、单请求、首项实际执行、不重放及完整退出断言全部保留；没有提高阈值、跳过或新增重试循环。

后续在准确提交上执行既有检查与一次 Linux CI，区分 Native 派发/回调延迟、Owner 的 1000ms 兜底和关闭阶段耗时。登记过一次 750ms timer 本身不能替代功能性超时证据；本改动也不证明原失败已修复。

本阶段同时合入已在主线的 PR69 Windows 私有状态启动诊断和固定只读验收入口，保留 Android 与 Windows 两边的变更登记。无 Android/iOS 产品代码或原生输入变化；Windows 原机、Android 真机、完整 UI 和 P7 整阶段均不因此增加通过。

## 本批交付与保留

准确新 head `d8fa8f27` 的 run10804/workspace26330（14分31秒）与iOS26331（2分9秒）成功；Linux两次自然样本elapsed为756.490ms及756.484ms，均观察到实际wait通知返回、控制字-1及750ms timer触发，记录完整。它们证明本批准确提交通过，不证明旧run10802根因已修复。

PR70已实际合入main `c99684ba`，主统筹通过API、fetch、双父与祖先关系独立核实。阶段32–36五个已合并开发分支已在Gitea和本地清理，后续stage37/四线分支及开发树保留。旧阶段36空trace清单漏扫目录的结论已由实际目录审计纠正；仅两份能与完整日志timeOrigin逐字段绑定且无依赖的可再生fixture已回收，其他归属不足的候选保留，未批量删除。
