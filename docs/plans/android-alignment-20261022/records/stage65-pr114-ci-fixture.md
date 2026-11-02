# 阶段65 / PR114：冷分帧预算夹具与CI修订

日期：2026-11-02。仅修订Node辅助验收夹具及观测；不修改Host/Adapter生产实现，不扩大Android、iOS或Windows产品验收状态。

## 原始失败与修订范围

阶段65提交 `93c44ed7c0f04935b60a102694ac7d7dfec251be` 已进入 [PR114](https://git.51easyai.com/BCAI/agent-hub-app/pulls/114)。精确head的CI11151：workspace26939失败，ios26940成功，未合并或重跑原操作。失败为 `windows-trace-worker.test.mjs` 的 `cold-shared-budget` 用例：子进程记录实际1个request，期望2个；不能由此推断后端从未尝试第二次调用。私有官方日志为HTTP206尾段 `177827-243362/243363`，SHA-256 `ad719bdd8c70cb4c391373a26653cc5260fafad8c375409a9e991e04a286cb8b`，不是完整日志。

原夹具两帧均延迟200ms，聚合预算300ms；调度余量不足是代码分析得到的可能原因，尚无证据证明它是原CI运行的唯一根因。修订让真实首帧立即发布、第二帧保持等待至原聚合预算到期；仍保留原300ms预算、每帧Native750ms计时器、owner1000ms上限、原2个request、无局部结果、完整退休及650ms总耗时断言。

`performance.now`观测返回原始样本，不推进或替换时间。每次实际 `Atomics.wait` 的timeout必须严格等于原deadline减去计算该timeout时的实际时钟样本；两次wait分别证明首帧已发布和余帧按原截止时间超时。子进程真实帧ID/数量应为1/8和2/56；Native实际750ms计时器保留两条。等待日志最多64条且要求omitted为0；Native每流原有8192字节、32行上限保留，诊断分流输出，避免一个总长度上限丢弃全部观测。

## 验证与边界

- 初始两项正常定向用例通过。首次负对照误改dist，夹具实际从源码重新编译，因而该次通过不计为有效回归验证；原文件已恢复。
- 有效负对照只在真实加载的 `desktop-trace-native.ts` 临时注入第二帧重新获得300ms预算，准确触发 `Every owner wait must use the same original monotonic deadline without renewal`：actual `299.982958`，expected `296.312792`，原deadline `374.373667`，计算样本 `78.060875`，omitted为0。原Node子进程wait exit1，0.723s。
- 源码精确恢复原24812字节、0644及SHA-256 `a9669f5fcb164f6ad0ad147a2c2e4e8182c94ffe11bc92654425852acfab281c`；正常同一单项wait exit0，0.699s。临时变异及备份不进入交付。
- 首次完整检查因执行范围说明与既定Gradle子步骤冲突而被主动中断，保留exit1/143，不计通过。修正范围后，完整 `pnpm check` 原PID11556实际wait exit0，1068.006s；42组Node汇总tests1518、pass1456、fail0、skipped62，包含脚本安排的重复执行，并非去重功能数量。全链包括其正常Gradle/KMP/Swift辅助步骤。
- 完整检查201408字节原输出SHA-256 `06198cc2235c1211599bad5a7592b5f9d6d7bdce50fe539c0fd67ae7d5e43035`。外层记录脚本在fsync实际wait后因Python哈希函数兼容问题exit1，原输出已独立重算摘要；不覆盖pnpm子进程exit0，也未重放检查。

正式执行方的官方turn_context均核对为 `gpt-6.1-terra` / `medium`；入口无速度参数，实际速度未核验。私有证据：`ci-pr114-fixture-validation`、`ci-pr114-negative-source-validation`、`ci-pr114-full-check-execution`；Root差异复核 `ci-pr114-root-candidate.diff` 9575B / `a7b431fa66e7c6cbb4dcd2310538461ce66aeadc36d0c920344c6d759a837c88`。机器路径、实际对话及凭据不入Git。

## 交付、收尾与未验收

修订将追加原阶段65分支及PR114，随后核对新的精确head CI、实际合并与分支清理；当前不把本机通过写成Linux CI通过。MacBook128已收到新的失败证据通知；其原专用对话仍有未决人工授权，未代答，也未重复分派已合入主线的旧修复。

本轮私有Gradle daemon经正常wrapper停止并独立确认退出；本轮Swift辅助可重建产物已退休，旧T的同类已完成产物亦核验无占用后清理。原日志、回执、源码及当前/回退APK保留；交付后继续清理不再需要的私有依赖和工作树。

本轮没有Windows Native、手机、OS/SystemUI或Host真实产品DEV验收。阶段65原3项Android Native限定结果不变；24域、完整UI及物理DEV仍开放，任务/项目业务、F75、iOS A6仍暂停；未知原操作不查询或重放。
