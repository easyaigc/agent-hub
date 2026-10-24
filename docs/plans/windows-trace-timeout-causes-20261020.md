# Windows Trace 超时：因果证据与代码级处理顺序

日期：2026-10-20。原因果审阅基线：`37e47a1a8e55de58a65e4227933ad59963e9aaea`。当前状态：**组合源码 `7f2b70b` 已完成完整 `pnpm check`（799 项输入无漂移），db 候选的 Windows 原生结果为 378 通过／3 跳过／0 失败；但其真实普通建议第一请求返回 503，约 4137.87ms，矩阵已首失败停止。手机业务未验收，尚未正式发布。**

本文补充[整体收尾复审](windows-trace-closeout-review-20261020.md)，将“具体为何超时”与“怎样优化”分别交付。原始证据保留在私有目录；本文不包含真实 Trace 正文、凭据或机器路径。第 1–7 节保留原调查时点的证据、代码位置和待实施方案；其中“当前源码”指上述冻结基线。后续实现与验证状态见第 8 节，不以源码修复改写原 727 的未知原因。

## 1. 目前可以下到哪一级结论

| 问题                                     | 已确认的失败机制                                          | 已确认的成本或结构原因                                                                           | 仍不能断言的根因                                                                    |
| ---------------------------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------- |
| 旧普通 Host 请求 `804a570c`              | 第一个 A 返回 503，约 3041.56 ms，后续矩阵停止            | 该次没有足够的内部阶段记录                                                                       | 不能用另一次 utility 的阶段代替本次 HTTP 归因                                       |
| 旧部署独立计时 `80d6dd36`                | Main 的旧 3 秒 commit 截止先触发，共享 7 秒尚未耗尽       | 心跳先占约 156 ms；完整 Worker commit 中路径解析约 1.07 秒，原生操作区间约 1.44 秒；大量操作累积 | 不知道每个 Win32 调用的成本、实际重复收据比例，也未确定 Worker 最后拒绝的唯一原因   |
| 当前源码的解析与校验工作量               | 静态调用链及五组合成计数都确认重复扫描和重复 guard 登记   | 一份日志随 inference 数增长被反复完整扫描；重复登记继续增加 commit 校验工作                      | 合成次数不是 Windows 耗时；尚未量出它解释旧真实样本的多少毫秒                       |
| 最新 Native `7277923f` 的 slow-operation | 外层 execFile 终止 probe，用例约 21.67 秒；末尾结果为 0 B | 测试截止检查有漏洞，退出阶段缺记录；15 秒 timeout 不等于 15 秒已回收                             | 未分清父计时器迟派发、同步 I/O 长尾与 OS 终止迟滞，不得宣布是杀毒软件或 Worker 死锁 |

**读取超时与收尾超时是两条因果链。** 前者已有定量的阶段解释；后者只能缩小候选，原始失败仍未完全归因。最新 978 三次保留 15 秒监督与首次创建结果的合成诊断均正常退出，并取得 OS 退出时间；同时实测确认测试的 gone 判据早于 child exit/close。原 727 的 21.67 秒仍未归因，不能改记为通过。

## 2. 读取超时：还原 80d6 的实际时间

### 2.1 证据边界

80d6 是独立 Node utility，使用旧部署 906 的实际 Observer、WorkerClient、parser、原桌面来源和真实 native helper，按 Host 的 prepare／中间来源核对／commit 顺序执行。**它没有向普通 Host 发 HTTP 请求。** 它可说明本次 utility 的机制，不能直接认定 804 同因，也不是 37e 的部署验收。

三个 profile 来自同一 OS 进程的 Main、Reader、native I/O isolate；complete=true、dropped=0，停止时没有未结算记录。按各自单调起点换算后关联，不能直接比较不同 isolate 的相对毫秒数。

| Main 阶段       |     实际时间 |
| --------------- | -----------: |
| 入口能力检查    |    0.7462 ms |
| 来源核对 before |    8.3212 ms |
| prepare，成功   | 2908.6219 ms |
| 来源核对 middle |    4.9271 ms |
| commit，503     | 3003.5395 ms |

Main 在 commit 已用约 3002.6940 ms 时执行 fail；总预算尚未过期，尚未收到有效结果。最终来源核对与最终能力检查未执行。启动 ready 的约 4.37 秒、A 前一次 CIM 采样约 3.26 秒均在请求计时前，不能算进 commit。

### 2.2 一个已证实的额外等待：心跳挡在 commit 前

以 Main 调用 commit 为 0：

| 事件                        |     相对时间 |
| --------------------------- | -----------: |
| 一次心跳 P 已开始等待       |   −6.0642 ms |
| P 等待结束                  | +148.9352 ms |
| 随后两次 S 完成             | +154.7315 ms |
| workerReadiness 返回        | +155.4359 ms |
| Worker 开始派发 commit 消息 | +155.9465 ms |
| receipt.commit 进入         | +156.2956 ms |

代码对应 `desktop-suggestion-read-worker.ts:100` 的同步状态采样及 `:114` 的周期心跳，和 `desktop-suggestion-worker-client.ts:376` 从 Main 发送起计算的 commit 截止。Reader 执行同步检查时不能处理 commit 消息，约 156 ms 已实际消耗在阶段预算内。

这是可定位的队头阻塞；它不是整个 3 秒超时的充分原因。仅去掉这 156 ms，仍不能证明剩余全部校验能够通过。

### 2.3 操作很多，而不是某条 native 命令卡死

整个 profile 的 754 条原生命令全部完成、错误帧为 0，使用的是同一个常驻 helper。命令数不等于进程启动次数或独立文件数。

| 命令                  | 次数 |  单次中位数 |  单次最大值 | 记录 wallMs 合计 |
| --------------------- | ---: | ----------: | ----------: | ---------------: |
| P：原实例及执行器检查 |    8 | 176.3061 ms | 250.4648 ms |     1492.1596 ms |
| S：单项 Snapshot      |  270 |   2.3984 ms |  11.0513 ms |      719.3873 ms |
| B：批量信封           |  476 |   4.5749 ms | 114.8485 ms |     2669.8388 ms |

上表覆盖初始化、心跳、prepare、commit 及失败后的尾部，不能把合计直接当请求耗时。该样本没有单条命令超过 750 ms 的证据。三个约 223 KB 的 S 响应分别只用约 2.47、5.08、2.58 ms，因而“大正文吞吐耗尽 3 秒”不是当前主要解释。

按完整 Worker 方法区间拆分：

| 项目                           | prepareBoundSource | receipt.commit |
| ------------------------------ | -----------------: | -------------: |
| 方法总区间                     |       2905.0773 ms |   3000.6781 ms |
| Reader Atomics.wait 区间并集   |       2417.1392 ms |   1574.4244 ms |
| 其中 native 命令区间并集       |       2271.8940 ms |   1435.5704 ms |
| FS 顶层调用累计                |        315.9346 ms |   1284.5880 ms |
| 方法总区间减 wait 与 FS 的余项 |        172.0035 ms |    141.6657 ms |

native 区间包含在 Atomics 等待中，两者不能相加。FS 只有方法内聚合，没有逐调用时间轴；这里使用完整 Worker 方法区间，不伪装成精确裁剪后的 Main 3 秒窗口。余项也不是独立测得的 JSON CPU 时间。

commit 中 `realpathSync` **1747 次／1065.3250 ms**，约占完整方法 35.5%；`lstatSync` 2328 次／218.8231 ms。每条 guard 的前、中、后 canonical/stat 核验解释了大量调用的代码来源，但原始 profile 没有 guard ID 或路径，不能将 1747 除以 3 当实际收据数。

Main 失败后，Worker 仍完成了 4 条 B，最后一条晚于 Main fail 约 149 ms。这表明同步工作无法即时处理 abort 消息；并不说明它最终完成所有收据验证。Worker 末尾也返回 503，原代码将具体拒绝折叠成 null/503，不能进一步断言一定是 deadline 或一定是收据变化。

### 2.4 37e 的 4 秒调整能解决什么

906 到 37e，parser、Observer、Trace transport、C# Snapshot 和进程探针的相关实现字节相同。批量信封、C# Session 查询优化、SID 对象与缓冲复用已经存在于这份旧计时样本；不能重复宣称它们是新版消除瓶颈的证据。

37e 将 prepare/commit 各自改为 4 秒，共享 7 秒，HTTP 8 秒不变。这改变阶段预算分配，**没有降低上述工作量或消除心跳排队**。若 prepare 仍约 2.91 秒、commit 恰用足 4 秒，逻辑总预算只剩约 0.09 秒；不代表新版一定失败，也不构成稳定性的余量证明。

## 3. 当前源码中可以复核的工作量放大

### 3.1 因果链到具体函数

1. `desktop-prediction-trace.ts:1028–1047`：遍历日志中的每个 `inference_started`，调用完整 `readSnapshot()` 后才登记或覆盖 execution。相同 turn 也会先重复求绑定。
2. `readSnapshot():1112–1264`：重新枚举同 predictionThread 的 bundle、读取并解析完整日志、检查目标 turn 的请求；最新结果在 `:1069` 再读一次。
3. `withByteBudget():224–238`：恢复临时正文缓存及字节预算，保留已形成的 guards。64 MiB 活跃缓存上限不是累计扫描次数或 guard 数量上限。
4. `rememberNativeFile():412–455`：即使命中同一次快照的正文缓存，也追加 guard。目录与 catalog 已有各自去重，不能笼统说所有收据都重复。
5. `verify():538–581`：commit 消费全部 guards。`windows/trace-state.cs:164` 对 B 中每项仍独立执行完整 Snapshot；批量减少传输次数，不自动减少校验对象。

设固定日志 N 行、R 个 inference；绑定求值的全日志处理量可达 Θ(R×N)，最终结果再加一次。若同一 turn 有 r 个 inference，每次绑定又访问该 turn 的 r 个请求，可出现 r² 次请求访问的静态路径。同一被选中的 predictionTurn 含多个 request 时，最终建议目前会保守拒绝（`requests.length === 1`）；不同 turn 各有一个 inference 则可以通过。不能通过猜测重试顺序改变语义；拒绝前的额外工作仍值得定位。

### 3.2 本次实际运行的合成计数

使用现有 `preparedTraceFixture`，未改生产源码；parser SHA-256 为 `ee98010f4baa60beb688a1b47893828fc7be89bfdc046ed9ea5c818750a96ccf`。一份 bundle、若干不同 predictionTurn 共享同一来源，只有最新 turn 有一个完整结果。五组 prepare/commit 均返回预期最新结果。

| inference 数 | 日志行数 | readSnapshot 次数 | 该方法完整日志行访问量（推导） | guard 总数／JSON 唯一数 | commit 原生项／命令 | commit canonical 次数 |
| ------------ | -------: | ----------------: | -----------------------------: | ----------------------: | ------------------: | --------------------: |
| 1            |        6 |                 2 |                             12 |                  14／12 |               14／7 |                    46 |
| 2            |        7 |                 3 |                             21 |                  17／13 |               17／9 |                    55 |
| 4            |        9 |                 5 |                             45 |                  23／15 |              23／12 |                    73 |
| 8            |       13 |                 9 |                            117 |                  35／19 |              35／18 |                   109 |
| 16           |       21 |                17 |                            357 |                  59／27 |              59／30 |                   181 |

这里的 357 是入口次数乘固定、完整日志行数的推导，不是逐行计数器，也不包含外层发现扫描。59 条 guard 中 32 条是当时 JSON 表示完全相同的额外副本；这不等于已经证明可以删掉 32 次校验，收据还承担不同时间位置的约束。

这组数据证明了全日志扫描的二次增长，以及 guard/commit 工作在此输入中的线性增长。它使用合成 native/DACL 响应，**没有测 Windows OS 时间**。80d6 未记录路径、实际 B item 数、日志行数或 guard ID，所以不能把本表重复比例套到真实样本。

### 3.3 对应的优化应如何落地

优先方案是 Windows prepare 内的一次解析与证据计划生成，而不是删除 canonical、DACL 或内容检查：

- 在 `scanSourcePredictions/readSnapshot` 之间引入请求内索引，按已取得且被同一快照收据约束的日志，生成每个 turn 的事件范围及请求绑定。每份稳定日志只完整解析一次；最新失败、不完整、来源矛盾仍拒绝。
- 将“此次实际观察的证据”与“最终需履行的校验义务”显式建模。若讨论归并，至少匹配请求 epoch、路径、类型/scope、有效 stat、native identity/祖先链、body 长度/maximum/SHA、prefix 长度/SHA 与增长分类，并证明最终时间位置不被削弱。**只按 path、turn 或 JSON 相同去重均不足够。**
- `verify()` 继续保留读前、回包后、末尾三处约束，以及 commit 前后的 catalog/cwd 核对。不要复活已有反例的 `realpath.native` 快速正向替代，不缓存跨请求权限或正文证明。
- 先计量减少了多少解析、guard 和 native item，再测真实 Windows 时间。若仍由 C# 的固定链成本主导，再研究一个批次内的句柄集合与完整前后检查；这属于后续独立设计，不能简单共享过期祖先证明。

第一阶段限定在 Windows prepared 路径；macOS 的原 parser 行为、双投影比较、通知和文件权限路径保持。共用函数须通过 Windows 分支接入新实现，并用既有 Mac 行为验收。

## 4. 收尾超时：727 的 21.67 秒究竟说明什么

### 4.1 已有时间约束

| 原 727 证据                         | 结果                                                       |
| ----------------------------------- | ---------------------------------------------------------- |
| 用例总时长（reporter）              | 21669.8583 ms                                              |
| 事件序列                            | spawn → started → ready → request(P) → quit → eof          |
| events 最后写入到 result 创建       | 20445.9698 ms                                              |
| result 创建到父测试 runner.log 创建 | 25.4011 ms                                                 |
| 原 result                           | 0 B                                                        |
| execFile 错误                       | code=null，killed=true，signal=SIGTERM，stdout/stderr 为空 |

文件时间是墙钟元数据，不是函数执行点的单调时间。“最后事件后约 20.45 秒才创建空结果”不能直接写成“写文件用了 20.45 秒”。

该 fixture 用假的 Node 子进程，故意不回复 P、忽略 QUIT/EOF；没有真实 C# Trace 读取、MSIX 路径解析或建议 prepare/commit。对应阶段的 3/4/7/8 秒预算不能解释这一合成失败。

### 4.2 必须收窄或排除的解释

| 解释                                      | 审阅结论                                                                                                                                 |
| ----------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| 所有测试同时运行导致共享 gate 死锁        | 命令链用 `&&`，Node `--test-concurrency=1`，lifecycle `concurrency:false`；各 probe 的 gate 不跨 OS 进程共享。不能据此说整机没有其他负载 |
| Main beforeExit 累加等了约 20 秒          | broker 每次 hook 共用 1000 ms 截止；正常 probe 先写结果再自然 beforeExit。不能单独解释结果创建前的长间隔                                 |
| gone() 内部等 pipe EOF 多秒               | `desktop-trace-native-worker.ts:66` 直接 destroy 流、通知 shared gone、关闭端口，没有 await EOF；OS child exit 到来之前的迟滞仍未排除    |
| 父 finally 花掉超过 15 秒的约 6.67 秒     | runner 已在用例末尾创建，finally 在其后；现有元数据不支持把主要长间隔归到该 finally                                                      |
| probe 已在 15 秒退出，仅剩管道拖了约 6 秒 | 原错误栈最终从 process onexit 满足 maybeClose；timeout 路径也先销毁输出流。不支持把它当唯一解释                                          |
| slow child 没有写 exit 行就是泄漏         | Windows 强退不会保证执行 JS exit handler；e422 同样只有六条事件，却有独立 exit/close 证明，六条日志不足以判断回收                        |
| 声明的 5+5+5+5+3 秒之和就是原因           | 这是带条件的预算组合，并非固定 sleep。events 创建后约 0.982 秒、case 目录创建后约 1.183 秒已有完整六事件；尚需解释为何观察或退出推进迟滞 |

原采样器确实与整轮测试同时运行，约 178.96 秒内做了 73 次循环，每轮全量 `Get-CimInstance Win32_Process` 后睡眠 750 ms。它是可能影响负载的观察器，**没有逐轮 CPU、I/O、调度耗时，不能据此认定它造成了该次超时**。

### 4.3 15 秒 timeout 为何可能超过 15 秒才返回

Node v24.21.0 的 `execFile` 在同步 spawn 返回后安装 JS timer；timer 执行终止逻辑，Promise 的回调仍通过 child close 结算。定时器的声明时间不是精确派发保证。[Node execFile 源码](https://github.com/nodejs/node/blob/v24.21.0/lib/child_process.js)、[定时器语义](https://github.com/nodejs/node/blob/v24.21.0/doc/api/timers.md)。

Windows libuv 对 SIGTERM 和 SIGKILL 均调用 `TerminateProcess`。Microsoft 明确区分发起终止与最终退出：未完成 I/O 必须完成或取消后，目标进程才能退出。因此“换成 SIGKILL”不构成这里的 Windows 根治方案。[libuv Windows 实现](https://github.com/nodejs/node/blob/v24.21.0/deps/uv/src/win/process.c)、[TerminateProcess](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-terminateprocess)。

这些是平台机制，**不是 727 的 OS 执行记录**。目前至少有两类仍然相容的路径：

1. 父 timer 很晚才派发，或 kill 调用本身迟返回；probe 此前又经历同步调用/调度长尾。
2. 父按时发起终止，但目标仍有待完成/取消的 I/O，进程退出与回调较晚。

空结果让“末尾文件创建/写入与终止交叉”值得优先查，但既可能是主要停顿，也可能只是此前停顿后的受害点。不能命名某个杀毒软件、磁盘或驱动为根因。

### 4.4 e422 为什么还不足以消除疑点

e422 单次 probe 1526.8706 ms 正常结束，close 前到 owner exit 729.5762 ms，result 写调用 0.4663 ms，child exit/close 和 stdout/stderr EOF 均观察到。但它预先创建 result，用同步标记记录阶段，外层改为 spawn 加 30 秒观察，没有原整套前序与 CIM 采样器负载。

因此它验证了同源正常退出链可以完成，**没有原样验证 727 的“首次创建结果 + execFile 15 秒终止”交叉边界**。继续原样重跑 e422 到绿，信息增益很低。

### 4.5 本次 Windows 历史现场补查结果

操作 `747736ac-255e-4c49-a478-960520aac02f` 已在原 Windows 专用聊天执行只读取证，保持原模型与实际 `xhigh`；未重跑 fixture、启用日志或修改产品。

- 原 8 个 mode 中，只有 slow-operation 的 result 是 0 B。其文件身份、字节摘要及创建/最后写入时间与较早 b284 记录一致。
- 执行前的装配记录没有该 case/result；父测试只创建 case 目录，源码中已知的 result 写入点是 probe 的 finally。已审阅的采集、复制、封包和 e422 装配均写入独立目录，未找到辅助脚本创建或截断原 result 的路径。**没有文件操作审计，仍不能证明 OS 范围内唯一写入者，也没有原 probe 的独立进程创建身份。**
- 查询失败附近 UTC 01:47:30–01:48:30：System 中指定的存储、资源耗尽和电源 provider，以及 Defender Operational 通道均可读，没有匹配事件。Sysmon 通道不存在，因此没有其进程退出记录。通道当前启用不证明当时覆盖或日志保留完整；无匹配记录不能排除文件过滤驱动、短暂 I/O 延迟或调度停顿。
- 当前 788 源文件与原六个进程身份匹配；旧证据保留。本轮只新增私有报告，没有调整安全软件或系统审计。

该补查进一步降低了“取证脚本后来制造空文件”的解释空间，却没有捕获当时的函数栈或 I/O。下一次诊断应优先补 **父 timer/kill/exit 三个时刻，以及 probe 最后一个未返回的同步调用**。这是按区分能力安排的排查顺序，不是已经证明 I/O 比调度更可能。

### 4.6 最小时间线诊断：保持原监督边界

本轮最小诊断使用独立私有副本，9 个 frozen 产品 JS 与 727 完全相同，仅在原 probe/bootstrap seam 加观测。实际运行与结果以归档原始记录为准，不把工具装配或本机校准记作 Windows 通过。首个装配操作 `b88e4c16` 停在 `INCOMING_CHECK_ONLY`，未创建测试根目录，case 为 0；原工具没有保留具体子检查项，不能将它解释成 727 的原因。后续 `97858794` 只修诊断装配的数据检查与错误记录，继续同一组三个尚未执行的 case；不改原接收文件 ACL 或产品权限实现。

这次恢复了 e422 没有保留的三个条件：真实 `execFile({timeout:15000})`、默认 stdin/stdout/stderr 管道、result 在 probe finally 中第一次创建。没有提前创建 result，也没有替代 snapshot 写入；5/5/5/5/3 秒内层条件等待和 late-true 语义原样保留，以免诊断时先把问题改掉。预先限定最多三次，首次失败停止；没有整套 Native 前序或原 CIM sampler，因此仍不是原负载的完整重放。

观测分三层，全部不记录真实 Trace 正文：

- **父层：** 在加载 `child_process` 前观察原 builtin `delayedKill` 的安装、触发、清除；记录准确 ChildProcess 的 kill 前后、stdio end/close、exit/close、回调与 Promise 结算。单独的故障注入校准须先在目标 Node 上证实钩子有效。execFile 调用前后只代表同步初始化窗口，不能称为 `CreateProcess` 的独立耗时。
- **probe/native 层：** 同步文件调用、signal-zero、until 余量、client close、初始 Reader owner exit、结果序列化及首次写入；native 的 750 ms 请求 timer、250 ms kill timer、QUIT、kill 和退出事件。每条标记保留 PID、isolate、单调时间、墙钟和丢标计数，并在下一条报告上一条标记自身的写入成本。末条标记未返回时仍保留未知，不能把同步标记的长尾全算给被观测调用。
- **OS 层：** 独立观察器仅从本次 spawn 通知取得 probe/helper PID，打开 QUERY_LIMITED_INFORMATION/SYNCHRONIZE 句柄，校对创建时间窗口，保留同一 handle 到 signaled，再调用 GetProcessTimes 取得真实退出 FILETIME。没有全进程枚举、CIM 轮询或终止权限。20 ms 轮询时间另记，不充当 OS 退出时刻。[GetProcessTimes 契约](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-getprocesstimes)。

跨进程 UTC 对照受毫秒量化和时钟连续性限制；创建窗口匹配也不是从原 spawn handle 传递身份，打开之前的 PID 复用竞态没有被数学上完全排除。观测缺失或窗口不匹配必须记为未观察，不能补“已退场”。OS 观察器不发起 PID 终止动作，因此该限制不会变成它的误杀风险。

### 4.7 根据时间线选择修改点，而不是统一延长 timeout

| 后续实际捕获的模式                                      | 支持的原因层级                                           | 对应代码级方案（待实施）                                                                                                                                                                                            |
| ------------------------------------------------------- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| execFile 返回前耗时长，15 秒 timer 迟至该窗口后才安装   | 父同步启动/初始化成本；不是原 timer 安装后的迟派发       | `scripts/desktop-trace-lifecycle.test.mjs:524` 分开 launch 与受监督运行预算，记录两个区间；需要系统栈才能细分 CreateProcess/初始化成本                                                                              |
| timer 已安装，fire 明显晚于其截止，kill 到 OS exit 较快 | 父事件循环/同步调用/调度迟派发                           | 父报告记录真实 fire 与 deadline；将高频同步读取与报告工作移出监督关键路径，不能声称 OS 必须在 timer 声明时刻退出                                                                                                    |
| timer/kill 正常，OS exit 晚                             | 发出终止至真实退场之间存在迟滞                           | `desktop-trace-native-worker.ts:77–95` 保留同一 ChildProcess 及 retiring 状态直到实际退场；记录 killRequested/exitObserved，禁止仅凭 killed=true 宣称回收，也不能靠 SIGKILL 解决 Windows 同一 TerminateProcess 路径 |
| OS exit 早，JS exit/close 晚                            | Node 事件派发或管道清理迟观察                            | 分开 OS exit、JS exit、stdio close、callback；先查拥有该事件循环的同步工作，不把迟回调当活进程泄漏                                                                                                                  |
| result write enter 后无 return，OS exit 与文件创建交叉  | 最后写调用未完成；尚不能确定 open/write/close 或具体驱动 | `scripts/desktop-trace-lifecycle.test.mjs:261–272` 不让最终失败报告只依赖被终止的 probe；父进程保存已知 phase 与原始空/部分文件。只在捕获对应系统栈后考虑原生 I/O 取消/异步方案                                     |
| 所有新轮次正常                                          | 本轮未复现                                               | 保留原 727 FAIL/未归因；不从正常均值推断历史单例，不合并发布                                                                                                                                                        |

无论最终属于哪种模式，`until():68–73` 的 strict deadline 应在 check 返回后再判时间，避免 late-true；`probe():267` 的 owner 耗时应在真实 owner exit 事件记录；父 `:524–567` 的失败留证与回收预算应明确分离。这三个测试/观测缺陷已经由代码确认，但不能单独解释 727 的 21.67 秒。以上仅为项目方案，本轮没有修改这些产品或仓库测试实现。macOS 数据路径保持，后续任何共用测试变化都需独立回归。

### 4.8 本轮 Windows 实测：时间线已取得，21.67 秒未复现

操作 `97858794` 已完成固定三次，使用原 Node v24.21.0/x64 与实际 `xhigh`。来源机已核验回传 ZIP 的 110 个成员、109 个载荷 SHA/CRC，并核对 sealed tools、17 个 fixture 文件和原 9 个产品 JS。三个 probe 均 exit 0；这是合成诊断结果，**不是整套 Native、普通 Host 或手机验收通过**。

下表为实际单调时间差，单位 ms；同步调用区间含开始标记的写入开销。跨 isolate 使用 `hrtimeNs`，没有直接相减各自的 `performance.now()`。

| 观测                                     |    第一次 |    第二次 |    第三次 |
| ---------------------------------------- | --------: | --------: | --------: |
| execFile 至回调完成后的记录点            | 1481.0566 | 1562.7977 | 1475.4377 |
| execFile 同步初始化窗口                  |    9.4005 |    7.3718 |    8.4447 |
| native 750 ms timer：安装标记→触发标记   |  756.7638 |  764.1610 |  757.8286 |
| native 250 ms timer：安装标记→触发标记   |  261.1596 |  261.4191 |  260.1526 |
| 首次 client.close→Reader owner exit      |  734.2728 |  744.4617 |  734.9568 |
| helper kill 调用前→返回 true             |    0.3234 |    0.2641 |    0.2455 |
| result 首次 writeFileSync 调用区间       |    0.4509 |    0.3710 |    0.4138 |
| 本轮最长已观测同步调用（均为 events 读） |   30.5237 |   28.3533 |   19.4082 |
| probe beforeExit hook 区间               |    0.1168 |    6.3228 |    5.3245 |

三个真实父 15 秒 timer 均安装一次、清除一次，未触发、未调用 parent kill。另两个明确标为故障注入的校准分别验证了 builtin timeout 触发/own-handle kill，以及正常退出时清除 timeout；它们只证明记录器覆盖有效，不是 727 重现。

OS observer 成功保留三对 probe/helper 的句柄，六个创建窗口匹配、六个退出时间取得，errors=0、remaining=0。它没有按 PID 杀进程，也没有全进程枚举。probe 的 OS 退出至父 JS exit 的 UTC 对照约为 **21.89、24.57、22.53 ms**；这是带毫秒量化/时钟限制的观测，不是精确的内核调度测量。它直接说明两种时间不能混用，但本轮毫秒级间隔不能解释原 6.67 秒超额。

所有已记录 lost/registryLost 均为 0；最长已完成 marker 写调用约 0.4523 ms。三个 result 开始标记自身写入分别约 0.0060、0.0044、0.0041 ms，不能把表中区间称为完全无观测开销的磁盘耗时。probe 中已标记的同步调用都有返回/异常配对；第二次 native Worker 缺少 beforeExit/exit 的四个末尾标记，各 isolate 最后一条 marker 的完成成本也不能从下一条恢复。**lost=0 不等于所有生命周期事件都完整。** 六个 OS 进程退出已有独立证明，native Worker 内部缺项仍按缺项保留。

### 4.9 新确认的缺陷与原超时剩余原因

**新确认：现有测试会先写“gone”，后观察到 child exit/close。** 第二次 `result.json` 首次写完在相对 1521.5080 ms，helper JS exit/close 分别在 1527.7110/1527.9449 ms；第三次分别为 1437.5275 ms 和 1443.0432/1443.2482 ms。两次结果已写 `children.gone: true`，前面的 `process.kill(pid,0)` 返回 ESRCH。该单调顺序证实：`scripts/desktop-trace-lifecycle.test.mjs:250–268` 中该结果字段的形成和写入没有等待完整的 child 退出通知与流清理。**本轮最终父回调均在已采到的 OS 退出之后，不能据此声称整个 case 提前 PASS，或已证实资源泄漏。**

两次 OS FILETIME 也晚于 result 写入的 UTC 标记约数毫秒，但这里保留时钟量化限制，不把这种差值扩写成已精确量出内核存活窗口，也不据此断言该时刻子进程仍可执行。Node v24.21.0 的 `uv__kill(...,0)` 在 `GetExitCodeProcess` 返回非 STILL_ACTIVE 时可直接返回 ESRCH；此分支不再等待句柄 signaled。它与本次“PID 检查已否定存活、回收事件尚未到达”相容。[固定版本 libuv 源码](https://github.com/nodejs/node/blob/v24.21.0/deps/uv/src/win/process.c#L1250)。

**对应实施方案：** 在同一测试的固定 spawn/bootstrap seam 记录准确 ChildProcess 的 exit/close，并利用已有 `transport-monitor.mjs` 对准确 native Worker 退场形成独立条件；在严格总截止内全部满足后才将回收记为完成。PID signal-zero 只保留为辅助观察，不作为充分条件。Reader 退出、helper 退出、stdio 完成和 native Worker 退出各用独立字段；缺证据保留 unknown/retiring，不自动重放或按历史 PID 强杀。测试层先修成功判据和父进程留证，不为修测试而重写 macOS 产品路径。

这是一项真实的观测/验收缺陷，**不是已找到 727 的 21.67 秒唯一根因**。本轮能确认同源退出链可按 750 ms 请求超时→250 ms 退场宽限→kill→退出完成，首次创建 result 也能正常完成；不能排除原运行中的偶发同步 I/O 长尾、父 timer 迟派发、OS 终止迟滞或背景负载影响。events 同步读取本轮已达 19–31 ms，说明高频同步轮询有实际成本，但不能外推成原 20.45 秒。

原 727 没有 timer-fire/kill 与 OS exit 的原始记录，其时间线不能由本轮三次正常样本补造。后续若再次复现，应按 4.7 的事件顺序定位最后一个未返回调用，再决定是否需要限定 PID/路径的系统 I/O 栈取证；本轮不启用全系统 ETW、关闭安全软件、扩大权限或增加超时来制造通过。原 FAIL 保留，合并、统一发布与 iPhone 正式通过均未发生。

## 5. 诊断契约：先定义如何判因，再决定改哪里

下面是诊断与后续计量契约。4.6 的计时器/I/O/退出观测只加在隔离副本中；仓库产品、测试与安装脚本未改，读取工作量及心跳的新生产计量仍待实施：

| 必须区分的原因                           | 观测位置                                                                                                                       | 判定规则                                                                                     |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------- |
| 父 deadline 派发迟 vs OS 终止迟          | 原 execFile 的 launch、timer/kill 调用前后、OS process exit、各流 close、Promise settle                                        | timer 很晚且随后 exit 快，指向父调度/同步阻塞；timer 按时但 exit 晚，进一步查目标 I/O/终止链 |
| Reader 等 native vs Reader 不响应 close  | `desktop-trace-native.ts call`、Reader close、owner exit；native worker 750 ms timer、QUIT、250 ms kill 前后、child exit、gone | 标出停在哪一条边；不能只看 client.close 返回或 killed=true                                   |
| records/PID 检查慢 vs 结果文件创建写入慢 | `probe()` 的 records、signal-zero、JSON 序列化、原 flags 的 write 调用前后                                                     | 同步调用开始后的长空白与之前的停顿分开；缺 return 的调用记未完成，不估造耗时                 |
| 观察器改变时序                           | 保留原新文件行为与预算，固定少量有/无诊断及有/无原 sampler 的对照                                                              | 每项固定运行次数，保留失败，不用“重跑至绿”作结论；对照改变因素必须明确                       |
| 校验工作量 vs C#/FS 单次成本             | 每请求统计日志行、读字节、全部/唯一 guard、B items、phase 内 FS 分布；native 只记固定类别与时间                                | 只有次数下降且相同语义下真实耗时下降，才能认定该优化解决了对应成本                           |
| 心跳抢占                                 | ready→prepared→commit 间的状态采样和消息 dispatch                                                                              | 统计 prepared 期间抢占次数、queueWait；消除排队不等于删除最终实例检查                        |

根因判定必须有至少一条能连接“触发条件 → 具体等待/工作 → 截止/终止”的时间线。若历史没有记录、最小诊断也未复现，就交付“原单例未归因、已排除哪些、剩哪些”，不能改写成已修复。

## 6. 原因到实施工作的对应关系

| 优先级 | 具体实施点                                                                                                                                   | 对应原因                                          | 完成时应交出的证据                                                                |
| ------ | -------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------- | --------------------------------------------------------------------------------- |
| P0     | `desktop-suggestion-read-worker.ts` 的心跳调度：prepared/committing 期间暂停非必要周期采样，idle 恢复；由本请求已有最终 fresh guard 更新状态 | 实测约 156 ms 的队头阻塞                          | commit 派发不再被后台心跳占用；TTL、失效、取消、来源核对与最终实例检查均保留      |
| P0     | WorkerClient/Reader/native 固定 phase/reason 诊断；错误不再只剩统一 503                                                                      | 旧样本无法分清 Worker 最后拒绝原因                | deadline、native reject、来源变化、收据变化、回收未完各有明确且不含正文的内部原因 |
| P0     | lifecycle 的严格单调截止与父进程结果报告，保留原 execFile 监督边界作对照                                                                     | late-true 漏洞、0 B 结果、退出边界失明            | 每个阶段有因果时间，迟到成功不算通过，强退后仍有完整父报告                        |
| P1     | Windows 请求内日志索引和证据计划，谨慎合并等价义务                                                                                           | 重复扫描、重复 guard 登记                         | 同样负面输入仍拒绝；完整扫描和有效校验次数下降，真实 Windows 阶段余量得到实测     |
| P1     | Main 统一 Reader/native 回收与代次恢复，旧代确认退场再建新代                                                                                 | 结构性的责任分散与代次重叠风险，尚非 727 已证原因 | child exit、管道、Worker 退场分别确认，失败不重放、不误杀；生命周期组通过         |
| 后续   | native 批次内成本拆解，再评估句柄集合或 Windows 异步读取                                                                                     | C#/OS 内部剩余成本尚未测清                        | 对应 Win32 成本与收益数据；不凭“异步更快”重写全平台                               |

不能用延长所有 timeout、取消权限守卫、关闭安全软件、扩大并发、重复启动 helper 或重放未知请求代替上述工作。macOS 的数据路径和验收结果独立保留；Windows 改动通过后，仍需普通 Host、原桌面和 Xcode iPhone 15 各层真实验收，才可合并与统一更新。

## 7. 可追溯性

- 80d6 原始证据包 SHA-256：`5a2b1ae0dad2892f8c94cb0541a2426896261bb5d1031b897d6f210c040b7f84`。本轮重新按同 PID 单调时间关联，未重跑旧请求。
- 727 原包：`653fbc492ed0c14b4a857a1ea81cb009da9b3c7b7367fa99ef8face5a66ff171`；旧失败与 0 B 结果保留。
- e422 原包：`858a07b9bd23ebcbfc522abac6e7b7ed1a4d6a58962eb8b9ef144d53dd536259`；仅单次合成诊断，未代替正式验收。
- 747 历史补查包：96018 字节、33 成员，SHA-256 `97b6feabc5bba8c57e40b4baacad98d51155b1a8c6e0cf81a9835284f27683b3`；来源机已核验 32 载荷字节摘要及 CRC。主报告摘要 `afb5ce094c5ce7c151479bb7d6e0dc1f74b00c82976ba4727119799ccd7ce3b2`。
- 新增合成计数只使用仓库现有 fixture 与原 parser，原实现字节不变；五组隔离临时数据由 fixture 清理。诊断脚本、完整计数、独立审阅保留在本机忽略目录。
- 978 最小诊断包：921997 字节、110 成员，SHA-256 `7bb08733e8d86c9ed3103e6f512ed0ce356b82ffe2376fcaf5d8eb49b317cd8a`。来源机完成 109 载荷 CRC/摘要、sealed tools 和 fixture 指纹核对；三次结果均保留，前后 8 项保护对比全匹配。原六进程、788 源文件、旧程序与配置、原证据和原 ACL 未变。诊断工具只在忽略目录中准备，产品和仓库测试文件无修改。
- 原调查阶段没有产品实现变更或部署；该阶段 Xcode 的 Runner 74 失败与 Windows 两类超时分开记录。后续候选实施及编译结果见第 8 节，不覆盖旧失败。

## 8. 本轮候选实施进展（2026-10-20）

以下是候选工作树中的实际实现，不是对旧样本的重新判因：

1. **退休屏障与代次恢复**：Main 为准确 owner 保存 native transport 的实际退出 Promise。Reader 失效后立即撤销能力；只有 Reader 实际退出且其全部 native transport 已确认退休，才安排下一代。Trace 的 gone 同时等待 child exit、child close 和三路 stdio close；强退或无确认的状态保留为未知，不重建或重放。250ms child 关闭宽限、750ms native 响应和 2500ms Reader 关闭宽限保持。
2. **心跳与请求分离**：Reader 在 prepare 期间暂停周期采样，持有 prepared 收据及 commit 时继续暂停，避免已排队心跳先占 commit 的处理窗口；结束、失败或合法中止后恢复 idle 心跳，关闭或清理失败不复活。每次实际读取、最终就绪检查、来源核对与 5000ms 能力提示 TTL 仍保留。
3. **请求内日志索引**：仅 Windows prepared 路径复用同一份有完整收据约束的事件解码和按 turn 索引；每次访问仍重新取得文件收据并核对 stat、native identity、长度与 SHA。索引持有的正文继续计入 64MiB 活跃预算，释放正文时清理索引。没有按 path 或相同 JSON 删除 guard；三处 fresh canonical、全部 native item、目录/cwd 与最终来源检查保持。macOS 与非 prepared 读取仍走原解析路径。它减少重复解码，不代表已减少 commit 的原生/FS 工作，也不证明旧失败已消除。
4. **固定诊断摘要**：Host、WorkerClient、Reader 与 native Worker 发布固定 phase/reason 和数值状态，包括请求内计数、代次、时间及退出确认；不序列化业务目标 ID、建议正文、文件路径、令牌或任意 error 对象。诊断不增加 Trace 重读、不改变 RPC 或成功判据，观察失败不能开放能力。
5. **父报告与严格截止**：测试用例与父监督报告通过 partial→rename 发布；条件检查前后都核对单调截止，晚到 true 不算及时成功。父层保存 probe exit/close、输出流和错误元数据，缺失、空或部分结果明确区分；外层异常只在日志尚不存在时补写，不覆盖原 stdout/stderr，已有非零退出回归增加输出哨兵。

本轮本地四项窄回归、生命周期/父报告 10 项及 heartbeat 16 项均已通过；这些分项辅助结果不相加冒充 Windows Native 总数。BOM 修正后的 db473e1 已取得完整本地 `pnpm check` exit 0，以及自身的 Windows Native 381 项＝378 pass、3 Mac-only skip、0 fail；两 BOM 反例通过。macOS 单来源读取/续租两次 HTTP 200（约 241/236ms）、原 owner 保持；iPhone 15 App90 在用户重启解锁后基础测试正文 1 pass、0 fail，原生输入与 db 未变。上述结果均不替代 Windows 普通矩阵、当前 Host 实际 helper 加载、手机建议业务、正式服务或逐机发布验收。

原 727 的 21.67 秒、0 B 结果与既有失败保留，唯一原因仍未知；此前三次正常诊断不能倒推旧运行的 timer、I/O 或 OS 退出时序。prepare/commit 各 4000ms、共享 7000ms、HTTP 8000ms、legacy reader/RPC 3000/3250ms 与六秒 lease 不变。实施与后续结果汇总见[整体收尾复审第 10 节](windows-trace-closeout-review-20261020.md#10-本轮候选实施与验证状态2026-10-20)。

## 修复后的首轮原生证据（2026-10-20）

4eb候选的真实Windows完整规范检查单次exit/close 0，376通过、3项明确Mac-only skip、0失败。收尾slow-operation为773.014ms，保留原parent15000ms且未触发kill；当前通过条件使用实际Reader/transport退出、child exit/close和三条stdio close，不以PID0探测单独判成功。parent独立报告、partial→rename与迟到true拒绝均已进入该组。795源码/lock、76JS/五FP/9ESM与六角色/7013旧文件的前后对照一致；原始完整日志及737合成fixture原件均已回传并核SHA/CRC。详见[实施进展](windows-trace-closeout-review-20261020.md#105-已取得的首轮真实结果与-bom-等价修正)。

这是修复明确退场判据、心跳排队和重复解码后的新通过，不能倒推出原727唯一OS/IO根因。独立审查另发现并修正了索引的逐行BOM兼容边界；4eb的通过仍只绑定4eb，BOM修正后的新指纹另验。真实普通Host与手机建议链路亦不由source-native通过代替。

## BOM 修正后的同源更新（db473e1）

db473e1 的完整 Windows Native 单次 exit/close 0，用时 685976.5289ms；`slow-operation` 为 731.4518ms，Reader 在 close 后约 3.032ms 退出，实际 child exit/close、三流 close 及 transport 退出齐备，原 parent 15000ms 监督未触发。795 源码、76 JS、五 FP/九条 ESM、8677 旧文件及六角色保持，回传 873 成员/872 载荷 CRC 与 SHA 已核。完整来源与验收范围见[整体收尾复审 10.6](windows-trace-closeout-review-20261020.md#106-db473e1bom-修正后的同源实证与剩余验收)。4eb 和旧 727 的原始结果均保留，不用本次通过认定旧长尾的唯一原因。

DEV 首次 `6ca` 在 preflight 停止：缺 Main 冻结 intent，另整份聚合身份采集 PowerShell 脚本在 30000ms 边界 `ETIMEDOUT`，未 stage/退休/启动。脚本含 CIM、端口查询、六角色匹配、六次映像哈希及 JSON 输出，无内部阶段时点；provider、端口、哈希及 PowerShell 启动/退出均未区分，不能将整脚本超时归为 CIM 调用卡住。`9957d3e6` 已匹配 Main 冻结 intent、当前 receipt/turn；准确六 PID 窄 CIM 返回且完整 identity 匹配，后续 preflight/stage 结果待回。旧失败保留；后续身份成功既不倒推原聚合脚本唯一原因，也不构成普通矩阵、手机业务通过或旧 727 根因证据。

## 最新真实入口失败与组合状态（2026-10-20）

- `db473e1` 的单次完整 Windows source-native 为 381 项，378 通过、3 个明确 macOS-only skip、0 失败。来源机已核对回传 ZIP 的 873 成员／872 载荷 SHA 与 CRC，795 源码、76 个 JS、五组指纹／9 ESM 及 8677 旧文件／六角色均保持。slow-operation 为 731.4518ms；15 秒父监督未触发，实际子进程 exit/close、三条 stdio close 与 transport 退出均已记录。这些结论仍限定在该源码和执行范围，原 727 的唯一根因继续未知。
- 补齐独立提交意图后，`9957d3e6` 对 DEV 完成一次替换：旧根及当次 14 个子孙约 2789.51ms 退场，30 秒启动窗口内完成原维护回执恢复，health/workspace 为 200。新程序实际 entry、完整 1243 投影、76 个运行 JS、22 资源和五组指纹均由来源机核对；原桌面、正式 Host、来源输入、密钥及路由保持。建议读取前的 loaded-helper 为 UNOBSERVED，缓存文件相同不代表新 Host 已执行该映像。
- 来源机随后执行普通 HTTPS 矩阵 `2ffe922a-db6e-476b-8e66-fa275855b7ac`，startup health 200 后，第一条 A 于 2026-10-20 15:51:40.758Z 发起，4137.87125ms 后返回 503（“原桌面建议读取暂不可用”）。原始响应和完整单调时点已私有保存；其余 12 个正例、两个负例、35 秒 idle 及手机业务均未继续，没有重试筛绿。该时长可能与阶段截止有关，但**尚未采到该请求内部阶段，不能直接认定 prepare、commit、Native I/O 或解析中的哪一项超时**。
- 下一步使用已实现的固定无内容诊断摘要，安排一次有界、只读的真实 A 诊断，区分 Main／Reader／native 阶段和实际退场；不放宽原 4s／7s／8s 判定，不把独立 utility 的结果直接冒充这次普通 HTTP 的内部时序。请求内索引的静态改进和原生测试通过，尚不能证明普通入口的剩余工作量已经解决。
- 本机另将远端已提交的弱网历史及错误处理合并为 `7f2b70b27c354ccc28df1af1fdef68bdf6845b29`；冻结树 `b0e0b974e6eaece33e146d47e4110ba1dee343cc` 的 799 项输入在完整检查前后逐字节一致，检查 exit 0，独立审查无 P0/P1 阻断。该组合仅改变 Adapter 的 runtime-session 历史浏览等待，Trace／Windows 核心其余 53 个源码与资源字节不变；既有 Native 证据保留模块范围，不能标成新组合整体原生通过。后续新代码、产物、Windows 普通读取及 App91 真机业务均须按实际结果记证，暂不合并发布或全机更新。

### 首请求的初始化边界与最小补点

单次独立诊断 `cfe14bbc` 已取得原始证明并由来源机核验 40 成员／39 载荷的 CRC、SHA 和随包工具原字节：准确 A 的正文摘要、身份及 5995ms 剩余租期匹配，三次 fresh source 通过。prepare 为 3168.5399ms、commit 为 2847.0401ms，完整 A 为 6031.2515ms；只读连接约 5058.58ms，Observer 启动至首次能力采样约 5349.70ms，均单列在 A 窗口之外。native 生命周期累计 886 次响应、4612.5783ms，包含启动／状态采样，不能据此拆分两个业务阶段；Reader 只记录到一次 state，prepare／commit 间没有周期采样。实际 child exit／stdio close、Reader／transport 退休及父 exit／close／callback 齐备。原磁盘观测文件前后均不存在，Host 内存状态未观测；本次属于新独立 Reader，**不替代原 Host HTTP，不解释其 503，也不复现旧 727**。原始包 SHA-256 为 `a16079eb1292fe83721eeec2adfbf73f5c29a15590ce797a063cbb84cb3f0d32`。

代码复核确认，Host 在监听成功后主动调用 `suggestionObserver.start()`；health 不等待 Reader。原生建议能力则必须收到当前 Reader 的真实就绪样本，且样本年龄小于 5000ms。`DesktopSuggestionWorkerClient.prepare()` 不会启动或等待初始化，未就绪时立即拒绝；其四秒从 Main 发出消息前开始计算。Reader 收到请求后暂停 heartbeat，不能撤销此前已经执行中的同步采样。因此，启动未完成、消息排队和准备扫描是不同假设，不能将旧工具的约 4.37 秒启动耗时套到本次约 4.14 秒 HTTP 失败上。

现有固定摘要的 `parse`、`verify`、`native_start` 只定义、未实际采样；部分数值累计于整个代次，不能当单请求耗时。最小补点限定为：client 发出／收到 prepare 和 commit、Reader 进入／完成阶段、Reader 侧完整 P/S/B/T 往返与等待／解码计数、纯解析和三步最终核验。失败读与成功退休分别标记，不使用“最后一个 ok”覆盖失败。固定数字与枚举投影不得包含业务 ID、路径、正文或错误文本，默认关闭，不新增读取、重试或系统枚举。补点代码与验证结果另记，不能倒补本次原始失败的内部时间线。

### 已确认的重复工作与对应代码方案

`scanSourcePredictions()` 对每条 inference 再调用 `readSnapshot()`；请求内日志索引省了解码，但同线程 bundle 枚举、逻辑收据和 guard 登记仍会重复。`TraceSnapshot.file()` 的命中字节缓存不等于再次执行了原生读取，必须分别计数；commit 仍逐项复核全部 guards。64MiB 约束活跃正文占用，不能限制累计核验工作量。这是已确认的结构问题，尚不能称为本次 503 的已证耗时来源。

若实际摘要指向该部分，修复按 Windows prepared-only 的请求内证据计划推进：同一线程收齐全部 bundle 一次，按 turn 保留严格事件次序和歧义规则；只合并相同文件版本、完整 native 身份链和相同全文长度／SHA 的核验义务。保留每个用途的约束、capture 与 commit 前后目录／cwd 边界、三处 canonical／stat、完整 native／DACL／SHA、Host 三次 fresh source 和最终就绪检查。第一步不合并 prefix 与全文、不同长度或增长语义的 prefix，不跨请求缓存绑定。最新失败／未完成不回旧版、重复请求不去重成单条、跨 bundle／逐行 BOM／目录变化／同 stat 改写及 64MiB 反例必须保持拒绝。真实计数与阶段余量证明收益后，再进行普通 HTTP 和 Xcode 真机验收；macOS 及非 prepared 路径保持独立。

## 实际 Host 细分时间线与优化分流（a1e5530）

`a1e5530` 合入已提交的聊天管理主线和固定诊断补点，完整 `pnpm check` 单次 exit 0，799 份输入前后不变，耗时 477820.995292ms。实体 iPhone 15 目标的 App91 `build-for-testing` exit 0，176 份构建输入无变化；此时尚未安装，不能作为手机业务通过。独立统一包为 50229088B，SHA-256 `cc91442295f479088ec4821b4d5b7a33ae8d020d5401c993013e8297a64e83b1`，TaskHub 原载荷保持。

Windows `74dbcf0e` 三组同源窄回归为 36＋3＋23＝62 pass、0 fail/skip，均有实际 exit/close；未选择的测试不算通过，旧 db381 不冒充新源码完整 Native。新独立 DEV 的 1245 个投影文件、799 源码、76 JS、五组指纹／9 ESM 和 22 资源均与来源包核对，实际加载的原生 helper 也已记录。只对准确旧 DEV 退休一次，原桌面和正式服务、来源输入、配置与凭据保持。

来源机随后仅执行一次真实 HTTPS A，200／4905.253833ms，建议身份、正文摘要及服务端租期匹配。`f30e3aa9` 只读收集的 26 成员／25 载荷 SHA、CRC、原日志前缀与快照 CAS 已复核，11 条固定摘要能在同一 Host 进程内关联唯一读取序列。Windows 的摘要时长与来源机 HTTP 时长分别记载，不相减解释跨机耗时。

| 本次实际范围            | 观测                                                                       |
| ----------------------- | -------------------------------------------------------------------------- |
| Host 总 span            | 4738.5147ms，终态 ok                                                       |
| 三次 source fence       | 合计 18.2142ms                                                             |
| Host prepare／commit    | 2143.1138／2575.4127ms                                                     |
| Parser 纯解析           | 累计 915 次、7.9846ms                                                      |
| Parser 核验             | verify_inputs 2.6951ms；verify 2380.8843ms；verify_inputs_final 23.6909ms  |
| 请求内登记              | readSnapshot 2 次；catalog walk 准备 6 次、提交后累计 10 次；guard 750 条  |
| Reader prepare 原生边界 | P 2 次／343.8297ms；S 305 次／464.0994ms；B 222 次、444 items／1017.9088ms |
| Reader commit 原生边界  | P 1 次／159.7739ms；S 4 次／9.5098ms；B 374 次、748 items／1275.7311ms     |

这些范围有包含关系，不能相加成总耗时；native 边界含发送、等待和解码，不能当成 C# CPU 或磁盘 I/O 的单独时间。已观测 P/S/B 全部完成，无 failed/pending；T 未采样。服务持续运行，未为补生命周期终态而关闭它。原 4137.87125ms／503 和 727／21.67 秒的内部时间线仍无法由本次成功倒补。

**本次可以确认的瓶颈层次：** source 查询和纯 JSON 解码很小，工作集中在大量文件／目录证据的采集与最终核验。仅两次 binding 读取不能支持“同线程重复解析主导”的结论。七组合成操作数还显示：一个匹配 inference 加 16 个无关 eligible bundle 时，95 个 guard 中只有 5 份额外同版本全文；其余 80 项来自不同证据。该合成比例不套到本次 750 项，但已足以说明少量正文去重不会自动解决整个 root 的增长成本。

### 对应代码工作

- `desktop-suggestion-worker-client.ts` 有一处成功终态诊断问题：先改为 `committed` 再选 deadline，会输出等待释放阶段的 shared RPC 边界，不能代表刚完成的 commit timer。`8ead6c6` 已在状态切换前仅固定诊断数值，保留原状态、清 timer、发布和 resolve 顺序；真实 Worker 窄回归 11 pass、0 fail/skip，包括 phase/shared 分流、同步发布及诊断关闭边界。原 a1 摘要字段按此限制保留，实际 4s／7s timer 未被本问题放宽。
- `trace-state.cs` 的纯计算候选曾保留每次 fresh Open、Info、FinalPath、GetSecurityInfo 和完整 descriptor 复制，只在完整 raw bytes、ancestor/root 与固定 SID 策略相同、同一 S/B 命令内复用成功校验／SHA，限 8 项／64KiB。它没有缓存路径、句柄或旧授权。**本轮不采用，代码及测试已封存后收回；已有对照未显示稳定收益，不增加生产复杂度。**
- 不采用共享祖先句柄来略过 fresh CreateFile；这样可能跳过 DACL 更新后的实际访问拒绝。也不以路径／mtime 或旧分类略过不同 bundle，不清 Trace 历史，不增加预算。

本机 macOS 的同包真实读取／6100.65ms 后续租为两次 200（377.78／239.92ms），来源输入与原进程保持、临时服务退出确认；范围仅为本机单来源。Windows 普通矩阵、App91 建议业务、正式更新和其他电脑仍待后续实际结果。证明包 SHA-256：`adc60e2fc0fd0383f4ae43cea71e41afdab3ad2fe254a849edc16384fa14d9a4`。

### Xcode App91 真机辅助验证（2026-10-21）

准确 iPhone 15 的 App91 已从有线 Xcode 工具链安装，安装前两份加密状态备份逐字节相同，安装后、首次启动前密文仍一致；没有卸载或改写用户状态。首次无 fixture 的普通启动报告实际锚定本次备份 SHA，记录 6 个连接、76 个草稿、5 个待定回复、0 个待定创建。草稿与已提交输入摘要与历史报告一致；旧连接摘要与本次不同，且旧报告未锚定安装前备份，因此不宣称连接完整语义已逐项保持。

本次复核 176 项 iOS 输入和 46 项产物未漂移，App、Runner 及扩展签名有效。准确单方法 `testPhysicalAgentHubFeaturePreflight` 通过原 `xctestrun` 执行，首次 `xcodebuild` exit 0，约 20.37 秒；这只验证本次基础测试通道，不替代 Windows 建议展示、采用／撤销、续租或手机业务验收。未使用手机镜像，未新建代理或触发 Windows 模型输入。

### 纯 SD memo 的采用决定

固定对照包绑定原样 `a1e5530` C# 和冻结候选，二者用同一 fixture 编译、持久 stdin，按预定顺序执行 60 次合成 S／B2 调用与 30 次完整结果比较；首样本、预热和 12 轮测量分开。来源机已核回传 54 成员／53 载荷的 CRC、字节摘要及完整 raw，60 次调用和 30 次完整结果相等、四个编译器／helper exit/close 0 均与原件匹配。内层状态为 `PASS_SYNTHETIC_AB_ONLY`，外层 PowerShell exit/close 1 且只留统一失败标签，Node 父进程实际退出未采样，因此整轮不能标为通过。S 方法中位数为 1.6786→1.5998ms、P95 为 2.4571→2.8958ms；B2 为 3.4280→3.1558ms、P95 为 4.2825→3.4460ms。区间重叠且 S 尾值更高，不能证明稳定收益、纯 SD 成本或真实 Host 改善。原包 SHA-256：`2c2a1b235a571671cf85bddd5663ef1789551ca8699df72791a53ec75998ef96`。

工具的 catch 把 Node 调用后的异常也折叠为 setup 失败，是已确认的取证缺口；没有异常类型、行号或 Node 父进程实际退出记录时，不能猜成 C#、文件 I/O、StrictMode 或 `$LASTEXITCODE` 的根因，也不能用四个子进程退出替代父退出。保留本次失败，不重跑筛绿、不强制 exit 0。后续真实普通验收继续使用已验证的原始 C#，候选原件及辅助测试封存在私有证据目录。

原始 UTC 还排除了一个错误分支：PowerShell 于 17:54:57.477Z exit/close，Node 的 `BEGIN` 是 17:55:00.144Z，内层结束标记是 17:55:02.600Z。因此不能解释为“Node 正常完成后，PowerShell 才异常”；这些进程各自的单调时钟起点不同，跨进程仅比较 UTC，不能相减 `performance.now()`。后续只读取得同一 CBC Node 的原始 4096B PE 头，来源机独立解析 `e_lfanew=120`、Machine `0x8664`、Magic `0x020B`、Subsystem `3`（CUI），完整文件 SHA 与原样本一致；GUI 子系统假设的前提不成立。[PowerShell 官方对 GUI 程序等待的说明](https://devblogs.microsoft.com/powershell/managing-processes-in-powershell/)只能说明该候选机制，不能当成本机原因。PE 证据包 SHA-256：`40daeb3f842a59d823e1d6adf852ed8cecd846faef374560a4c0365081c6f233`。旧具体异常、原 Node OS 退出及原 727 唯一原因继续未知，不再用新工具错误替代它们。

`7f4431a` 的下一次完整检查首次在 9.294 秒 exit 1：format／lint 已过，parity 要求登记 client 当前字节摘要，后续组未执行；不是 Windows 业务通过或失败。已按原契约补充 F69 的诊断变更记录，绑定 client／test 实际 SHA，原功能状态与 Android planned 状态保持，窄 parity 全链及 6 项测试通过。该首次失败及未部署候选保留，新完整检查、普通 Host 和手机业务仍分别等待实际执行。

### 与最新主库组合后的验收边界

补齐记录后的 `d062702` 完整 `pnpm check` 单次 exit 0，401301.401125ms，799 项输入及 HEAD／index 前后保持。随后接收已提交的 `098e6c5` 主库增量；两个实际 merge bases 与递归虚拟 base 分别留证，八处冲突按双方内容解决，没有覆盖主工作区的未提交改动。组合为 829 项受控输入，57 条历史 feature 记录完整保留，另用一条组合记录绑定新 Swift／build 元数据，F70 partial 与既有未验收状态保持。

来源级逐文件核对显示，五组件的 76 个运行 TypeScript 源、10 个 Windows helper 和 Python observer 均与 d062 原字节相同，C# source key 仍为 `2273fd960f3a3adf51f620f9c4acaca5a4ab385b10bd57d876c16792c1afc2b4`；新包编译指纹仍需实际验证。上游变化另包含 Windows 停服脚本的批量 fresh 身份检查和保留句柄 SID 查询，原 45 秒命令预算不变；必须另做原生身份自检、隔离 fixture 和准确 DEV 退场／重启，不能用旧 Trace native 结果替代。其自检脚本属于外层服务工具，不计入 Adapter 的 22 份资源，仍须核包内实际字节。

移动端准备 build92，四个原生目标、八份目标配置继承同一版本，签名与 bundle 身份保持。旧 App91 的 176 项输入中已有 12 项变化，另有新增 TaskHub 规则／Schema／向量等输入，因此重新构建 KMP 与 App 后再通过 Xcode 真实验收；旧基础测试不升级为组合通过。组合完整检查、共享核心检查、Windows 普通读取、手机建议业务及正式逐机更新均按新证据分别记录。

### bc8 组合后的分范围实证补记（2026-10-21）

本节补记提交 `bc8b077972fe6be43017322d01a5095006900477` 的后续结果，不追改前文按当时证据记录的待验状态或原 FAIL。构建、共享核心、单来源真实读取、Windows 窄回归与手机业务分别记证；旧 727／21.67 秒的唯一原因仍未知，原普通入口 A／503 等失败记录继续保留。

**完整检查与移动端辅助验证。** 组合后的 829 项受控输入完成一次完整 `pnpm check`，exit 0，429433.852875ms（约 429.434 秒）；源码、HEAD、index 前后保持，工作树 clean。另一次 `check:mobile-core` 为 JVM 51／51、iOS Simulator Native 51／51，无失败、错误或跳过，Swift／Kotlin 与 TaskHub 向量通过。App92 使用全新构建目录，面向准确实体 iPhone 目标的 `build-for-testing` exit／close 0；829 项输入、82 项产品命名空间记录及 4 个 framework 实体文件已锁定。四个项目目标均为 build92，签名验证通过；Xcode 自动生成的 Runner 外层容器实际版本为 1，其内嵌 UITests bundle 为 92。这些是构建与辅助结果，不代表手机业务或 Android 真机验收通过。

**App92 升级、基础 preflight 与连接摘要。** 准确 iPhone 已由 91 正常升级至 92，未卸载。安装前稳定持久密文与安装后、首次启动前字节一致；首次普通启动没有 fixture 或额外参数。升级前持久快照与首个 App92 加载快照的完整 Client、草稿及 outbox 编码摘要一致。只执行一次 `testPhysicalAgentHubFeaturePreflight`，1 pass、0 fail／skip，exit／close 0，21032.596417ms；范围只是基础数据入口及 Xcode 通道，不含 Windows 建议展示、A／B 切换、采用／撤销或续租。

随后加载报告的完整 Client 摘要确有变化，草稿和 outbox 摘要及计数保持；因此**贯穿 preflight 的全部连接语义保持仍为 partial／UNVERIFIED**。Client 摘要覆盖完整编码和数组顺序，不能因连接数相同宣称逐项配置保持，也不能以加密 nonce 解释已解码摘要变化，或把 online／发现更新当成本次已证原因。具体字段／顺序未知，不能据此断言丢配置或归因迁移、XCTest。报告属于持久加载快照，不是测试结束时的逐字段实时盘点；亦不证明外部附件、外部凭据或全部创建记录 payload 保持。源码复核未发现由本次组合 delta 可证的新持久化缺陷，实际差异继续单列未证。

**macOS 同包、单来源真实读取。** 新私有 QA 使用同一 bc8 统一包，对一个既有已完成来源实际读取两次 HTTP，均为 200，耗时 636.9601／197.2550ms。首次读取后等待 6100.8672ms，再读的建议身份保持、到期时间实际续租，两次服务端剩余租期均为 5997ms。原 owner、turn 与输入保持，没有新模型输入；临时 QA、子进程和监听退出已确认。范围仅为本机单来源真实读取及大于 6 秒的续租，不替代 Windows、两来源手机业务、完整产品或正式更新验收。

**Windows phase1：来源闭包与选定回归。** 回传 1912 成员／1911 载荷的 SHA、CRC 和安全命名空间已核；Main 四份元数据、冻结意图和实际 receipt／完整正文摘要一致。829 源记录与 Git、Native ZIP、冻结输入对齐；实际 1274 项 stage 投影全部与来源 Host ZIP 字节相同，含 76 个逻辑 JS／139 份物理副本、5 组文件指纹、9 条 ESM 解析边、22 份资源及额外 4 个外层 Windows 脚本。22 资源是 10 个 Windows 物理源的 20 份副本加 2 份 Python 资源。既有 C# source key 与随包 cache EXE 字节一致，**缓存相同不证明新 bc8 Host 或本轮 suite 已加载该映像**。

一次 locked install 后，三组选定 Node 回归为 38、3、23 项，合计 64／64、0 fail／cancel／skip，各有实际 exit／close 0。另三项不计入 Node testcase 总数：control smoke 使用模拟 native／Scheduler／provider，非真实停服证明；owner primitive 的真实 retained handle／token SID 读取及无效句柄拒绝通过，但 handle 断言有既有容差，不能写零 handle delta；launcher smoke 使用实际 Windows launcher 和自有合成 worker／helper，含刻意非零退出／强退路径，外层 0 不代表每层均 exit 0。生命周期八场景的父 15 秒监督均未触发；child／stdio／transport 退休按各场景实际记录，不补造本轮未采的 kernel 时点或旧 727 原因。

phase1 采样窗口内六角色及 A／B owner／输入前后匹配；本阶段没有 DEV 切换、新 bc8 Host 启动、真实建议 HTTP、普通 13 项／负例／35 秒 idle 或手机业务。源码装配首次 `SOURCE_CONTAINMENT` 准备失败、资源路径分类及采集器生成错误均保留；后续仅继续已知自有空目录的装配，不写成全程首次全零。保护证据另有限定：历史连接注册表元数据变化在本轮开始前已存在，原因未观测；启动配置原前后标签指向不同文件，不能把那两行哈希当同一文件前后相等。其它已逐项核对的静态项和密钥元数据按各自范围记录，不扩写为跨历史全局无损证明。

**首次真实 cleanup 仍为 FAIL。** 独立自有合成进程树用例只加载冻结的 13 个 canonical 函数，传入 `Task=null`。canonical 返回耗时 25768ms、10 次查询；之后精确断言要求 tracked／owner 均为 18，实际为 19／19，直接触发失败。已知 18 个经身份校验的保留句柄在 failure cleanup 前均退出，兜底 cleanup Kill 为 0；后续严格验证并未因此通过，第 19 个身份也未记录。

父进程退出码为 -1；17 个 child 在其后约 10.391～11.757ms 全部退出码 0，但事件只有 ready，没有 stdin-EOF、natural-EOF、slow-stall 起止或 JS exit／close dispatch。该紧邻退出与[固定版本 libuv 的 Windows Job 相关实现](https://github.com/nodejs/node/blob/v24.21.0/deps/uv/src/win/process.c#L1016-L1034)及 [Microsoft Job Objects 机制](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects)相容，**仅是候选解释，实际 Job membership 与第 19 个身份未知**；不能凭退出码 0 写自然 EOF 成功。外层 60 秒监督和 command budget 超时未触发，但没有逐查询／等待／kill 的分段时点，不能把 25768ms 归给某段，更不能据本轮排除 5 秒等待缺陷。原 fixture 未覆盖到所要求的 slow-child stall／至少 5 秒后强退判据；Scheduler 的 Running 状态 5 秒轮询分支亦未执行。首 FAIL 和未知项保留，不删计数／改期待筛绿，不推定唯一产品或 fixture 根因，也不将已知 18 个退场外推为全机其它进程均未受影响。

**新 plain DEV 与普通入口再次失败。** `4cd7617b` 对同一 bc8 产物完成一次准确 DEV 切换。旧根与当次 14 个后代在 2789.5945ms 内退场／身份消失，监听释放后启动新实例；这是 Windows termination，不保证执行应用层 finally，也没有逐 child kernel ExitTime 证明。新实例的 76 JS／5 指纹、22 资源和 1274 stage 前后摘要均匹配；五个保护角色、正式服务与 A/B 原输入保持。实际新树内已观察到直属 Trace helper，source key 与原始 C#、EXE/FileID 绑定；child launch provenance 明确关闭额外诊断及 timing preload。一次维护 history GET 恢复原操作，health/workspace 均 200，这些只表示就绪。

来源机随后执行新的普通矩阵 `0f10abdc`。首个 health 为 200／87.635ms，native 建议能力为 true；A 在 2026-10-20 19:55:54.839 UTC 发出，于 **4070.858292ms 后返回 503**，原错误响应为 45B。单请求、原 8000ms HTTP 上限、没有诊断或预热；原始响应、UTC／单调时点和绑定已私有封存。后续 12 个正例、2 个负例及 35 秒 idle 全部停止、未重跑。该普通入口仍失败，不能将先前 a1 的诊断读取成功当成稳定性修复，也不能把约四秒直接归为某一内部阶段：本请求没有开启内部摘要。

**下一步根因核对和代码优化边界。** 原成功摘要已经确认大量文件／目录证据采集与核验占主导，纯解析约 8ms，不应继续把 JSON 解码作为主要优化。现源码又确认 TS 的 `traceSnapshotBatchLimits.items`、C# `SnapshotMany`、prepared discovery 宽度与 final verify 分组都限制为 2。可审候选是 Windows prepared-only 扩大有界 RPC 分组，并把已需要的目录收据一起按原项核验；它减少往返，不减少证明项，不把 native 等待误记为纯 I/O。

拟改位置为 `desktop-trace-native.ts` 的一致协议上限、`trace-state.cs` 的同源上限、`desktop-prediction-trace.ts` 的 `TraceSnapshot.files`／目录分组／`verify`／`discoverCandidates`。仍要求每项独立 fresh Open、Info、FinalPath、GetSecurityInfo、完整 native chain／身份／内容 hash、原 guard 和前后 stat；不复用跨请求分类或路径授权、不删 guard、不以目录／mtime 不变跳过文件核验。48KiB 请求、256KiB 响应、16KiB 单项、750ms native、4s phase、7s shared、8s HTTP 与 6s lease 均不增加。超出帧或单项上限须派发前选择原 scalar 路径，已经派发的 B 失败不能 fallback 或重试。完整 B 成功前不暴露任何部分结果。macOS、非 prepared 路径、root／ancestor 现有验证保持，任何更宽批次还需后项改写／ACL／reparse／坏 index／超帧／大文件与退出时序反例。

上述仍是候选实现方向，未由这次 503 证明为唯一原因或已有收益。现安排同一 bc8 程序、仅 child-local 固定诊断开关变化的对照 DEV，先保留失败后的既有日志及当时点非递归目录计数，再按预定单次请求采集内部阶段；新观察不倒填旧失败。原 727／21.67 秒的唯一 timer／I/O／进程退出原因继续未知；生产合并、逐机更新和完整手机业务均继续等待实际结果。

**当前手机版本变动。** App92 的基础通过只绑定当次安装与测试。此后两次 Xcode inventory 查询显示正式 App 已为 96，安装产物、操作者及源码来源未确认；现有加载报告比原92报告更新，但没有 build 字段，`sourceSha256` 是持久数据摘要而非代码摘要。尚不能从版本号或该报告认定执行了哪份源码。旧准备工具会安装92，已停止使用该入口，避免覆盖当前 App；另按 Xcode 官方本机 `xcodebuild.xctestrun(5)` 的 `UseDestinationArtifacts` 机制准备仅安装已绑定测试 Runner、保留现有 App 的兼容性验收。该准备未运行；即使未来通过也须标明当前 App96 兼容性范围，不能冒充 bc8 手机源码整体通过。

### Trace 增长的根本边界与定位契约（提案）

B2 扩到 B8 对充分填满的可批项最多约减少 75% 的 RPC 次数，不是减少 75% 总耗时；每项完整原生检查、Node 三点 stat／canonical、body／prefix hash 及 catalog 仍是 O(N)。准备阶段先读取 manifest／prefix／session-configured payload 才能判断候选无关，因此缓存“上次无关”、仅比目录名或 mtime 都不能等价移除该候选的 fresh proof。单批工作变多也可能增加原 750ms 命令预算的压力，需要原生实测。当前 162 个 bundle／161 个 thread 的数量只是失败之后的一次非递归采样，不是原请求的目录快照，也不能单独证明本次超时原因。

现有 live `desktopSuggestionSource` 只证明 source turn，不给出该 turn 全部 prediction attempt；`Thread.forkedFromId`、thread source、自定义字段、分页列表及通知可作定位 hint，但尚无覆盖隐藏／ephemeral、最新 pending／failed 和跨页一致性的完整性承诺。不能从列表零项认定没有建议，也不能用最后一次成功通知替代最新尝试。仅在 Host 写一个增量 JSON 索引，不能凭空补出 producer 未提供的完整性。

真正缩小内容扫描需 producer／原 owner 提供版本化、只读、具名的来源定位接口（拟名 `readLatestComposerPredictionLocator`，当前未实现、不能向桌面发送）。目标包含完整机器／owner generation／sourceThreadId／sourceTurnId；返回一致 catalog revision／complete-through 与最新尝试集合，含 predictionThreadId／predictionTurnId／traceId、原请求排序依据及 pending／failed／completed／none／ambiguous。attempt 在 Trace 获得候选资格之前就必须完整登记，重启、丢事件或未完整加载返回 unknown。不能先索引最新成功，再异步补失败。

Reader 可复用 `readSnapshot(predictionThreadId, predictionTurnId)` 定向分支，仍完整收齐该线程全部 bundle，保留所有选中文件的 manifest／完整 log／sequence／request-response hash／cwd／native 全链与三次 source fence。prepare 取得的定位 revision 还须在 commit 交付前由同 producer／owner 再确认，覆盖扫描与核验窗口；同一 sourceTurn 的新失败／pending、并列最新、epoch 或 revision 变化均拒绝旧结果，lease 在最终守卫后产生。此时无关目录的名称枚举可能仍为 O(N)，但内容及完整文件核验可降到相关 k 项。

当前公开接口及本机静态 schema 未提供上述保证；若桌面内部亦无此注册表，需要上游正式暴露或改进 producer，不能在本轮 parser 补丁中虚构权威 locator。fswatch／USN／oplock 也尚无本项目可复核的一致性证明，不作为快速替换。最少需闭合：漏通知的新失败、原 bundle 追加新 attempt、同 sourceTurn 并列、producer 重启／索引缺口、prepare 后新增、所选文件 ACL／祖先 retarget／同 stat 正文重写。本节是根本优化的接口提案，B8 则是当前可验证的常数项优化；二者均不能代替这次 503 的内部时间线。

### B8 当前代码及本地验证（2026-10-21）

已在隔离任务工作树实际应用有界B8候选：`desktop-trace-native.ts` 与 `trace-state.cs` 同源容量8，`desktop-prediction-trace.ts` 增加prepared-only目录B0、files连续规划、final guard贪心分组及发现层宽度8，diagnostics固定数值item边界同步8。没有实现权威locator、跨请求缓存或删减guard；macOS原路径及非prepared Windows最终B2保留。新C# source key和实际EXE尚待新统一包在Windows生成／加载后确认，旧2273缓存不能冒充新C#。

首轮Adapter构建exit/close0，四组功能测试339pass、0fail、6Windows-only skip，约136.20秒；829输入、HEAD/index/dirty diff前后保持。独审补充的test-only修订随后单次9pass/0fail/skip，504.973292ms；逐项义务以不可变合成数据上的有效(path,maximum)多重集比较（S传domain cap、B传实际stat-bound length，不能宣称raw maximum数值原本相等）。八个prefix用例明确在同一B8第7/8项执行时真实注入append正例、rewrite/truncate及同leaf祖先retarget负例，断言注入、单B无scalar重放、三次canonical、prefix匹配句柄关闭及最终fence按实际结果发生。两组有加强场景重叠，不合计为348个独立用例。功能结果均为本地真实Node文件系统与synthetic Windows链／DACL回复，C#/NTFS权限、Windows ordinary13/负例/idle、手机及正式发布仍待本次新指纹实际验证。

同一bc8代码、仅child诊断开关开启的单次真实A副本为200／5806.790791ms，身份、正文摘要和剩余lease匹配。原日志暂报Host5710.03ms、prepare3153.61ms、commit2543.37ms、纯解析7.76ms及825guard；原件已回传，详细阶段与对照仍独立复核。它不是普通模式成功，也不能倒填19:55首A503或原727内部阶段。

### 已核实的阶段变化、完整检查与退出屏障缺口

`06f513da` 的 64 成员／63 载荷已独立核对 CRC、SHA、原日志前缀与提交意图。同一读取的 P／S／B 共 1000 次，其中 B 654 次、1308 items；已采各项 failed／pending 均为零，未观测 T 或在线实例的退休终态。对照先前 a1 单次诊断，prepare 增加 1010.4979ms，commit 减少 32.0425ms；prepare 的 B 边界累计增加 954.3278ms，是最大的已观测变化。B 次数仅增加约 9.46%，均值却由 4.5852 增至 8.1162ms；commit 的 B 次数也增加，但累计时间反而降低。两次 catalog、时点和环境不是受控对照，不能把增量唯一归因于目录增长、磁盘、C# CPU 或 RPC。纯解析仍约 8ms；已确定应优化证据采集／核验的往返常数，而非优先改 JSON 解码。旧普通 503 和原 727 的内部失败原因仍未知。

`45770e9` 的首次完整检查在 desktop-framing 创建 Unix socket 时失败：采集器将 TMPDIR 放在过长的证据目录下，完整地址含终止符约 150B，超过当前 macOS SDK `sockaddr_un.sun_path[104]`。此为本次检查环境错误，原始失败保留，后续组未运行。只将采集器临时目录改为短的私有目录，源码、预算和测试不变后，从头单次 `pnpm check` exit／close 0，404140.050958ms；829 份输入、HEAD、tree、index 与组件 dist 前后保持。这是该提交的本地完整检查，不是新 C#／Windows 普通入口或手机通过。

`962d0255` 的自有 detached 进程树诊断满足原合成断言：18 个精确 tracked／owner，16 个 child 实际 stdin EOF 后自然 exit 0，一个 slow child 保持 stall 后最终 -1；18 个原句柄及只读副本闭合，最终所有副本 Wait0 均为 0。包含诊断 finalization 的命令 37418ms，小于原 45000ms，entry exit／close 0、40188.2153ms，小于外层 60000ms。5000ms grace delegate 返回 false 时 slow 仍未 signaled，说明该轮确实经过等待；此轮带观测开销，不能作为无诊断停服性能或 Task Scheduler 分支验收。旧 19／19 首失败保留；specific Job、每次 Kill 时间和原 727 的唯一原因仍未观测。

独审确认了一个额外的产品 P2：2000ms `Wait-HostProcessExit` 原 delegate 已返回 true，随后同一保留进程对象的只读副本仍得到 Wait0=258；稍后的完整收尾边界才为 0。该程序顺序反例不依赖跨机时钟或 ExitTime 精度，证明 `HasExited` 不能承担严格 kernel-signaled 屏障。[Microsoft Framework Process 实现](https://github.com/microsoft/referencesource/blob/main/System/services/monitoring/system/diagnosticts/Process.cs)的非 STILL_ACTIVE 退出码快捷路径与此现象相容；未采目标 System.dll 指令路径，故机制保留为有源码支持的推断。完整 Stop 返回瞬间未独立采样，不能宣称测得完整 Stop 提前返回多少毫秒。最小修复为仅在 Windows `Wait-HostProcessExit` 使用原保留 Process 的 `WaitForExit(0)`，保持共享 5000／2000、100ms 轮询、45000 总预算、原身份及 owner 校验和句柄生命周期；异常拒绝，不新增 PID 重开、kill 或 macOS 改动。修复与新真实验收分别记录，不能将本轮合成总体 PASS 直接写成屏障已通过。

该单行产品修复已落地；现有 PowerShell fixture 增加独立 `KernelSignaled` 状态及三场景，并接入原 control smoke。旧产品加新测试为三项 RED，补丁加新测试为三项 GREEN：延后 signal 才准许重启、永不 signal 拒绝重启、真实 disposed／未关联 .NET Process 方法异常沿原 `Where-Object` 管道拒绝，保留 finally 释放。此为 portable PowerShell 实际执行、Scheduler／身份模拟的边界证据，不是 WindowsPS5.1 或真实内核 GREEN。Framework 的无效 handle 仍有直接 true 分支；本实现依赖准入时已保留并验证的有效句柄，tracked Process 在两次 wait 前不 Close／Dispose／重新关联，仅 finally 释放。该持有关系不允许被后续重构破坏。

另合入已提交主库 `a57a50f` 的 iOS 弱网发现／同聊天读取合并／首次事件优先修复，保留双方原验收记录和 Trace／目标代次／未知操作守卫，准备已知源码 build97 原位升级。主库文档现提供 build96 正式安装的任务记录，但版本相同并不独立证明当前手机二进制的源码字节。原未执行的 App96 兼容入口保留；新组合源码的构建／安装及Windows、Mac与手机结果分范围见下节，旧通过不扩大、旧失败不追改。

### 36d633a 组合源码的实际验收与 Phone97 初始化失败（2026-10-21）

本节是组合源码 `36d633a6eeaf352afc4423ace7f571401a61666a` 的最新事实汇总：829份tracked输入，tree为 `b061a43e425979573d813e4f6b2ed90684cb4ed1`。前文按阶段记载的pending与FAIL均保留，不将当前通过倒填旧失败，也不把构建、Native、HTTP、手机和正式发布混为同一范围。当前Phone源码锁仍固定该提交。

#### 本机检查、构建与Mac真实读取

| 范围                             | 实际结果                                                                                                                                | 验证边界                                                                                                                                                                                         |
| -------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 完整 `pnpm check`                | exit／close 0／0，424300.611875ms；829份输入字节／modes、HEAD／tree／index及组件dist前后稳定                                            | Node24.21.0、pnpm11.19.0；24个portable PowerShell mock场景实际执行，23项skip原样保留；不是WindowsPS5.1／原生内核证据                                                                             |
| `pnpm build:ios`                 | Simulator构建exit／close 0／0，34337.597208ms，实际build97                                                                              | 构建辅助；KMP UP-TO-DATE／SKIPPED按原记录，不冒称全部重新编译                                                                                                                                    |
| 准确实体目标 `build-for-testing` | 全新DerivedData，exit／close 0／0，32297.143875ms；829输入稳定，7个bundle签名校验通过                                                   | 当时只构建；App／扩展／UITest为97，Xcode生成Runner外层为1，完整products／framework／xctestrun已冻结，不是后续手机业务通过                                                                        |
| Mac同源新包                      | 两次真实GET均200，完整正文约368.185／240.825ms；首读完整返回后等6102.744ms，同建议／来源／正文而expiry更新，服务端剩余lease5990／5988ms | 原owner／Turn／输入摘要前后不变，无新增模型输入或重试。临时QA父exit／close0，已观察helper身份消失和监听释放；未采每个helper独立exitcode／stdio-close，不宣称它们均exit0，不代替Windows或正式升级 |

统一候选产物绑定如下。它们不是正式发行、formal既有连接或fleet通过回执；本地Main升级仍只有只读差异／备份／CAS映射，尚未执行。

| 产物                                      | 字节数   | SHA-256                                                            |
| ----------------------------------------- | -------- | ------------------------------------------------------------------ |
| `codex-host-connect-36d633a-clean.zip`    | 50345686 | `2a637b406eedb5281a3c3314635501e90de49e925907c5217b699c4b3f98694b` |
| `windows-native-source-36d633a-clean.zip` | 43503642 | `08428cb41c14a4c06e4965e1a13169c2edab6719d9947ca2e89fe7605e7fac0a` |

#### Windows入口失败与完整Native的实际分流

首次SourceContainment准备在写文件前停止，零文件落地；仅机械修正路径规范化比较后准备完成，产品／已封工具未改。依赖安装exit／close0／0，13951.5849ms。018首次Native入口在shell不能解析 `node` 时exit／close1／1，719.6513ms；bf／60后续环境／cmd控制也在语法或入口层停止。三轮均未进入测试组，不能记成产品测试失败或完整Native通过；原stdout／stderr和失败均保留。

环境证据必须区分对象：保留的caller env对象只有9键，含 `PATHEXT=.CPL`、`ComSpec=null`；另一个fresh Node caller实测 `PATHEXT=null`／`ComSpec=null`，fresh PowerShell Process采样的.CPL也不是前者。Machine／User／Process分层采样不能合并为“所有fresh Node都为.CPL”。A的DOSKEY／语法输出和B约176.6088ms语法失败属于当时控制器观察；未采实际OS命令行，不能据此锁定唯一quoting层或双重转义原因。

公开[PowerShell 7.5.4源码](https://github.com/PowerShell/PowerShell/blob/v7.5.4/src/System.Management.Automation/engine/AutomationEngine.cs#L39-L58)在Windows的PATHEXT为空时设置.CPL，为一种可能机制；目标WindowsPS5.1具体DLL及完整父子链未采，不能当作该机已证触发路径。随后私有运行器只修正受控child env，并直接以绝对Node和pnpm CLI argv数组执行原 `check:windows-native` 顺序链，没有删测试、改预算、添加A／B文字成功门槛或修改用户／系统注册表。

新入口实际完整执行一次：761131.5893ms，**436项中433 pass、3 skip、0 fail，exit／close0**。独立raw审计无阻断：829源码快照及Main Native ZIP对应、1274项stage未漂移、76个逻辑JS／139份物理项目JS、5FP、9条ESM边、22份资源（10个Windows源的20份副本加2份Python资源）按各自范围核验；165份导出程序文件与统一Host ZIP字节一致。原smoke结果独立于Node testcase总数，不合并虚增；3项skip继续单列。

**4份输出JSON权限差异不抹平。** `native.started.json`、`native.execution.json`、`source.before.json`、`source.after.json`的实际owner为Administrators，继承私有DACL、foreignAllow=0；未声称protected或currentSID创建，也未事后改原ACL。它们是运行器输出元数据，不是Trace根／文件或产品guard输入。其余代码、目录及precreated raw记录的currentSID／protected证据依原范围保留，不由这4份差异推成产品权限失败或新增总门禁。

**严格v4清理是独立合成OS证据。** 新Windows运行按13个canonical AST函数原字节绑定，18个精确tracked／owner，16个child自然EOF后exit0，一个slow child在grace后以非零退出；第一次Wait返回false时17个保留句柄只读副本Wait0=0、1个Wait0=258；第二次Wait返回true时18个Wait0均为0，fault0，entry exit／close0，outer未触发。它验证同句柄kernel-signaled屏障在该合成场景通过，不宣称真实Scheduler stop／start、每次Kill时间、具体Job身份或无诊断性能。旧19／19首失败及962d提前成功反例仍保留。

#### 关闭诊断的普通Windows矩阵

新plain DEV关闭diagnostics／preload。Main完整原件字节审计以及独立ordinary语义／Phone97来源绑定复核均无阻断：13个正例、2个预期HTTP409负例；完整正文、target、四来源ID、hash、native-trace／native-version及服务端六秒lease一致，续租expiry更新。各请求单在途，maxInFlight=1；35秒idle实际35002.132084ms，最慢建议4566.862416ms。不能改写成“所有读取低于两秒”，也不提升为大规模性能或任意Trace增长的保证。

Main核对回传1580份payload的SHA／CRC、165份程序与统一包一致、5FP及实际helper；独立语义复核含238项检查，并明确未运行手机、未读token、未发网络或重跑Native。其请求query依据hash绑定的matrix构造，未独立保留完整wire query，不能把该范围扩大。原matrix内部idle和稍后单独计时样本的微小差别按各原件保留；这里采用Main最终35002.132084ms样本。该PASS不覆盖原各版首A／503，原失败无重试、无改写。

#### Phone97安装成功，测试正文前初始化失败

标准Phone97流程已完成构建与同bundle正常原位安装，实际为App97；7个bundle签名verify／display均0。随后XCTest Runner在测试正文前失败，xcresult为 `Timed out while enabling automation mode`：**0 matches、0 adoptions、未观察QA**。这不是建议正文／采用断言失败，也不是业务通过；没有同条件重试。用户已被请求检查“设置→开发者→Enable UI Automation”，当前仍待实际设置／恢复结果，不能宣称通道已恢复。

清理后Serve已恢复，自有proxy已关闭，对应端口无listener。当前持久密文4047622B与升级前两份副本逐字节完全一致；该证据只证明所采密文字节保持，不能上升为UI连接选择、展示／采用／撤销、所有草稿及submitted状态的语义恢复通过。早前实体构建辅助和本次安装／初始化失败分别保存，不把7项签名验证当7项业务通过。

Phone业务、正式既有连接及完整fleet继续待实际验收；本记录不提升F69或Android planned状态，不改变原lease／预算／权限条件。旧727的0B结果、约21.67秒时序与唯一OS根因仍**UNKNOWN**；新运行器入口问题、B8／退出屏障修复及当前成功均不能倒推出其唯一原因。

#### 对应代码的23处定位

以下行号仅绑定 `36d633a`。表中列的是实现／反例落点，不是23次独立实机通过；私有controller的env／argv修正也不冒充仓库新增产品能力。

| #   | 路径与行号                                                               | 保留的实现／验证边界                                                                 |
| --- | ------------------------------------------------------------------------ | ------------------------------------------------------------------------------------ |
| 1   | `package.json:41`                                                        | 原check:windows-native顺序链及Windows平台断言；本轮私有运行器没有删除或替换该链      |
| 2   | `scripts/windows-host-task.ps1:224-231`                                  | Wait-HostProcessExit对原保留Process调用WaitForExit(0)，共享预算/100ms轮询保持        |
| 3   | `scripts/windows-host-task.ps1:234-279`                                  | Stop原身份/owner/retained handles，5s grace与2s final等待共享45s；finally统一Dispose |
| 4   | `scripts/windows-host-task-fixture.ps1:153-183`                          | HasExited与KernelSignaled独立的合成Process行为                                       |
| 5   | `scripts/windows-host-task-fixture.ps1:215-241`                          | 延后signal/永不signal/真实Process方法异常三反例                                      |
| 6   | `scripts/windows-control-smoke.mjs:439-568`                              | 实际PS选择、24场景按原返回值断言，包含三项kernel边界                                 |
| 7   | `packages/codex-adapter/src/desktop-trace-native.ts:19-25`               | B8统一边界：最多8项、单项16KiB、48KiB请求/256KiB响应                                 |
| 8   | `packages/codex-adapter/src/desktop-trace-native.ts:54-105`              | 预派发fits与请求验证；不能由容量扩展放宽帧边界                                       |
| 9   | `packages/codex-adapter/windows/trace-state.cs:136-168`                  | 同源上限8；每个item仍独立Snapshot并完整检查，整批成功前无partial                     |
| 10  | `packages/codex-adapter/src/desktop-prediction-trace.ts:538-610`         | prepared directoryGroup的B0规划，保留目录守卫与前后原生收据                          |
| 11  | `packages/codex-adapter/src/desktop-prediction-trace.ts:734-827`         | files连续规划，缓存/大项/帧边界派发前scalar分流                                      |
| 12  | `packages/codex-adapter/src/desktop-prediction-trace.ts:835-914`         | 最终guard按fits贪心分组；prepared宽度8，非prepared保留2                              |
| 13  | `packages/codex-adapter/src/desktop-prediction-trace.ts:1273-1360`       | prepared发现按最多8候选分层manifest/prefix/payload与目录收据                         |
| 14  | `scripts/windows-prepared-trace.test.mjs:1035-1051`                      | scalar/batch有效(path,maximum)多重集保形，并区分往返数降低                           |
| 15  | `scripts/windows-prepared-trace.test.mjs:1287-1379`                      | B8后项失败、7/8项身份变更及directory错误不partial/不scalar重放                       |
| 16  | `scripts/windows-prepared-trace.test.mjs:1422-1536`                      | 同一B8后项执行中的prefix变化及最终canonical约束                                      |
| 17  | `packages/codex-adapter/src/desktop-suggestion-worker-limits.ts:1-10`    | phase4000、legacy3000/3250、shared7000、hintTTL5000、close2500                       |
| 18  | `packages/codex-adapter/src/desktop-trace-native-worker.ts:145-169`      | 单native响应750ms，B8未扩大此界限                                                    |
| 19  | `packages/codex-adapter/src/desktop-suggestion-worker-client.ts:341-375` | prepare从原始起点取min(shared7s,HTTPdeadline)并限制phase4s                           |
| 20  | `packages/codex-adapter/src/desktop-suggestion-worker-client.ts:433-575` | 原nonce/代次/绝对截止与commit4s；迟到回复不得恢复旧代                                |
| 21  | `packages/codex-adapter/src/desktop-suggestion-read-worker.ts:179-241`   | worker commit保留原shared截止，再夹4s，不重基总预算                                  |
| 22  | `apps/host-agent/src/runtime-server.ts:658-745`                          | HTTP8s绝对截止、before/middle/final来源核对，正文在commit后才交付                    |
| 23  | `packages/codex-adapter/src/desktop-suggestion-observer.ts:557-583`      | 最终完整核对成功后才生成6s native-version租期                                        |

### 1a Windows 再失败与 3508 Phone 附件业务 closeout（2026-10-21）

本节追加最新实际结果，不覆盖前文各提交的通过、失败或未运行记录；附件业务、Windows 建议与正式发布分别记账。

#### Windows 1a DEV 与两次单独的首 A 失败

`1a6a80c` Windows DEV 阶段采用 Host ZIP SHA-256 前缀 `4096…`、Adapter 指纹前缀 `fe376…`。选定五条命令的实际 exit／close 均为 0，plain DEV 419820 ready，实际 helper 映像已有观察；这不是本次完整 `check:windows-native`。原完整 Native／严格 v4 清理只有 `36d633a` 的实测及按不变字节限定的复用，不能填写成 `1a` 新跑完整套件。

Main ordinary 首 A 实际返回 **503／4112.546833ms**，首失败停止，其余矩阵未运行。`d46` 原始日志没有请求内部时序；稍后取得的 172 目录／171 thread 元数据也不是该失败时点的 Root 快照，不能证明当时目录规模或某个阶段耗尽预算。

随后 `bb88` 只将同 `1a` 程序开启既有 `AGENT_HUB_WINDOWS_TRACE_DIAGNOSTICS=1`，新 DEV 为 507872，程序与 5FP 保持。Main 另一次单独 GET 从 `2026-10-21T03:39:34.497Z` 到 `03:39:38.633Z`，再返回 **503／4136.433417ms**；这是保留原失败后的诊断对照，不是首 A 自动重试或 ordinary 通过。来源机 03:43 初观察显示，**仅该次诊断 GET** 的 client prepare 触发 `phase_deadline`，选中 phase 边界，sharedRemaining 约 2987ms，Host prepare 被拒。`6066` raw 尚待复核，内部 parse／native 工作量仍在回收，**唯一原因未定**；不能据这个阶段边界预设具体解析或 I/O 原因，也不倒填 plain 4112.546833ms 请求或旧 `727`。

`packages/codex-adapter/src/desktop-suggestion-worker-limits.ts` 与现有 Host 路由边界不变：prepare／commit 各 4 秒、两阶段共享 7 秒、HTTP 8 秒、单 native 750ms、最终完整校验后 lease 6 秒。接近 4 秒的总耗时不能代替内部截止／拒绝证据。旧 `727` 约 21.67 秒失败的唯一 OS 根因仍 **UNKNOWN**，本次日志或新失败不能倒填其原因。

#### 3508 App99 附件实际通过，不是 Windows TracePhone 通过

`3508fe93` 相对 d5 只修改 `apps/ios/Tests/CodexManageUITests.swift` 的 QuickLook 容器 descendants label／value helper，以及 parity 第 67 条记录；生产代码字节未变，历史记录与 feature 状态保留。新物理 App99／Runner 从 3508 源码重新编译、签名并正常同 bundle 安装，全 830 源码与 241 native 输入的冻结／前后 guard 通过。独审确认本次实际签名产物和构建安装回执的来源，不把生产源码不变写成设备二进制精确回读。

实际附件 XCTest 正文 **950.825 秒、1 test／0 failure、exit／close 0**，REPORT SHA-256 为 `2662a6c66d7a453cd4aa5fcc47b483968c1a772441d7d6f5cb0a1bd227be989b`。原断言依次验证旧链接 V1、V2 原地修改时旧预览保留／重开 V2、V3 原子替换时旧预览保留／重开 V3、App 重启后打开 V3，以及正常末尾恢复 M4。source 的 V2／V3 各仅一次原 operation 提交，字节校验与原 receipt 对应，控制器 phase3／ack3 后自然 exit0；同阶段 ACK 的重复交付不等于重复执行 operation。

该附件由 MacBook **当前已部署 Host** 承载，旧指纹前缀为 `bc38…`／`677…`，不是统一新 `1a` 正式 fleet。此 PASS 不代表 Windows Trace 建议展示／采用／撤销通过。旧 helper AX 16.449 秒失败、d5 全局 QL TextView 318.088 秒失败、pre-XCTest 的 0644 reference 私有路径检查失败均原样保留；修正与新通过不改写旧结果，也不解释 Windows 503。

#### 持久化独审的有限 scope 与最终待办

安装前后采集的 cipher 字节相同。已有 old-load 快照均显示 6 clients／76 drafts，完整 draft 与 outbox 摘要分别相等；clientsSHA 则不同，原因 **UNKNOWN**。before-load 的 sourceSHA 未锚定实际 preinstall cipher，`baselineAvailable=false`；after-load 的 sourceSHA 也不等于最终复制 cipher。因此它们是两个加载快照，不是本次测试开始／结束的精确字段基线，不能把差异归因本次，也不能猜为 online 自然更新或据 count 相等宣称所有连接保持。

`apps/ios/Sources/SessionStore.swift` 对有序 `loaded.clients` 的完整 Codable 编码取摘要，`apps/ios/Sources/Models.swift` 的 Client 含目标／候选路径、online 等字段；selectedClientId 另存。`apps/ios/Sources/SessionPersistence.swift` 的 load-report 只留计数、整体摘要、原加载字节 SHA 与写入时间，不能反推历史 Client 字段。独审没有找到能锚定这两个摘要的成对精确字段基线；完整 Client／三摘要保持仍 **UNVERIFIED**，不是已证丢配置，也不是已证无损。原报告的有限 scope 保留，不为补报告新增产品 DEBUG audit、新测试框架或重跑已通过的 950 秒业务。

截至本节，附件业务实际 PASS 与持久化证据缺口分别保留；Windows ordinary、新 Windows TracePhone 的原 8 秒 HTTP／6 秒 lease 业务仍待通过。本轮正式 Host 切换、向主库合并发布及统一 fleet 部署均 **NOTSTARTED**，不宣称全交付，不提升 F69／Android 状态。后续只按实际新指纹和独立结果更新，不以旧 Native、旧 ordinary 或 MacBook 附件来源替代。

### 6066 实际阶段时间线与下一项最小优化（2026-10-21）

`6066` 回传的 90 成员 CRC、89 载荷摘要与 13 条原始固定 JSON 已独立核验。该诊断请求的 client prepare 在 4013.1662ms 触发 phase 截止，shared 仍余 2987.3985ms；Host 的 source fence 为 5.0848ms、prepare 为 4016.2246ms，未观察到 commit／verify。原生 750ms 过期事件没有出现，不能将最后一次未完成调用改写为已证 native 超时。

Reader 请求窗口为 3966.1552ms。其串行原生调用等待合计 3656.8412ms（约 92.2%），响应解码 31.4226ms；parser 快照中 987 次纯解码／索引累计 4.4139ms。B 共请求 103 次／824 项，102 完成、1 失败、pending 最终为 0；parser 记录 1 次 readSnapshot、3 次 catalog、821 个 guard。前 102 次已完成 B 累计 elapsed 2694.8697ms，最后未完成 B 已观测 733.7413ms。该长尾在上层截止时被中断，不能当正常服务耗时。wait 已包含在 native elapsed 内，parser 快照与请求／lifetime 是不同范围，不相加；等待仍包含调度、IPC、C# 和 OS 工作，缺少进一步分解，不能宣布磁盘、杀毒软件、CPU 或 GC 为唯一原因。

本次读取失败后，产品事件报告 native child 与三路 stdio 关闭，以及 Reader／transport 退出；相对 client 截止分别约 130.7502ms、132.7391ms。独立内核退出时点／退出码仍未观察，正常退休不覆盖 503，也不解释旧 `727` 的 21.67 秒。

先实现更小、无并发的 Windows 候选：`packages/codex-adapter/windows/trace-state.cs` 的每个 Snapshot 将 `char[32768]` 改为 `char[Math.Min(32768,path.Length+6)]`。原实现仅 102 个已完成 B 对应的 816 次 Snapshot 就分配约 51MiB 字符有效载荷，并在每个 ancestor 前后整块清零；这是已证静态工作量，不是已证长尾 GC。新容量依据既有严格 DOS canonical 等值条件，保留设备前缀、根斜杠和 NUL 余量及原 32768 上限。Windows API 的成功长度不含 NUL、容量必须包含 NUL，容量不足返回所需容量，见 [Microsoft GetFinalPathNameByHandleW](https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-getfinalpathnamebyhandlew)。原 `count==0`／`count>=buffer.Length` 拒绝、最终路径比较、完整 handle 链、全部 ACL／identity／内容与前后检查均不变；没有扩大缓冲重试、缓存或 fallback，macOS 路径不改。

候选仍须在真实 Windows 比较旧／新完整 Snapshot 输出、短／深／长于 260 字符／非 BMP 路径及原拒绝边界，并核对 guard 调用未减少，再完成同产物回归和普通／手机验收。性能收益与最终通过均待实际结果；不以少执行证明、增加预算或删除目录校验换取成功。

### a31 原生通过、ordinary 失败与诊断转发缺口（2026-10-21）

`a31c53f` 的本地完整检查 exit 0；Windows 完整 Native 单次 exit／close 0，437 项＝434 pass、3 Mac-only skip、0 fail。原生对照与资源绑定支持新 source key `5f37…`，不以相同的五组 JS 指纹代替 C#／实际映像证明；它不代表普通 HTTP 或手机业务通过。

`d14` 在启动工具的 30 秒接受窗口末尾发生后置 metadata query 超时，工具 FAILED 保留。该窗口混合进程／端口采集、维护历史、owner 核对等工作，不是单次 CIM 调用耗时，也不能据此断言 Host 未启动。后续独立只读 `976` 取得 health／workspace 双 200，并观察 Host 的实际 helper 子进程，source key `5f37…`、image SHA 前缀 `b431…`；这是新时点的 ready／loaded，不能回填 d14 的原窗口。

首次 ordinary `0ee` 仅执行第一条真实 A，返回 **503／3789.915709ms**，其余正例、负例与 idle 阶段首失败停止，没有重试筛绿。该 HTTP 工具未测内部预算，不能将不到四秒的总时长认定为 prepare、native 750ms 或其它固定拒绝原因。`849` 后续只读采样确认旧 Host 518828、helper 519420、原 parent 431688 不在，18794 无 listener；只在已查 Application 日志窗口内，`Application Error`／`.NET Runtime` 没有匹配错误事件。独立退出时刻／码、Job membership 与唯一原因均 UNKNOWN，既不能把无事件当正常退出，也不能把 parent 不在当因果证明。旧 `727` 的约 21.67 秒唯一原因继续 UNKNOWN。

**确定的 observability 接缝。** `packages/codex-adapter/src/windows-trace-diagnostics.ts` 已将固定摘要写成 `{windowsTrace: event}` 到 stderr。`scripts/host-worker-supervisor.mjs` 的 stderr data／逐行消费在非 `host-runtime-failure` 时直接 continue，因此这类 Trace 摘要不会到达 canonical worker 的现有诊断日志。`scripts/windows-desktop-worker.mjs` 的 `report` 才负责私有日志与轮转。这个静态缺口不证明旧 plain 请求一定生成过摘要；诊断默认关闭、进程退出前未发出或未完整交付的记录仍可能缺失，不能补造 prepare／parse／native 时序。

候选已实施最小修复：只在上述 supervisor 消费接缝，为既有诊断开关精确为 `1` 时的 `windowsTrace` 增加固定 schema／枚举／数值投影，经原 `report` 进入原私有、有界轮转日志；保留现有 `host-runtime-failure` 分支。不原样透传或 spread 任意 stderr JSON、正文、路径、配置、凭据或 error 对象；scope／reason／phase、P/S/B/T 和 parser 计数等按 factory 既有契约重投影，payload 不能覆盖 supervisor 持有的 child 身份。原有界分片／逐行处理、stdio 消费与退出／重试语义保持，不改变返回、timer、4／4／7／8 秒预算、750ms 或六秒 lease，不增加 RPC、Trace 读取或每项热循环日志。

已有 `scripts/host-lifecycle-smoke.mjs` 增加真实 factory 四种 scope 经子进程 stderr 分片／多行传递、未知字段和非法类型不泄露、默认关闭及非 `1` 开关不转发、超限行拒绝、reporter 抛错后原退出码与恢复仍保留的边界检查。Node 24.21 上该 smoke、既有 `scripts/windows-host-recovery-smoke.mjs`、格式、lint 和语法检查退出 0；这是 macOS 上真实 Node 子进程的辅助证据，尚非新版完整检查或 Windows canonical pipeline 实测。风险是宽透传导致隐私泄漏、无限缓冲或额外日志开销，以及把缺记录误当零耗时；保留实际日志缺失状态。

Main 另行准备独立 Scheduler 直接 CBC Node 的同 a31 诊断路径，用来保留该次固定摘要和退出证据；它不修补 canonical supervisor，也不计正式服务、自启／恢复、ordinary 或 TracePhone 通过。当前只记录已有事实与待实现接缝，新的诊断结果由 Main 另行追加，不倒填旧失败或重放未知输入。

### 独立同代 A 的失败链已闭合（2026-10-21）

在独立、无触发器、无 Scheduler 重启的手动任务中，同一 a31 Host 仅启动一次。安装模型会话结束后，Main 核对真实 HTTPS 的 health／workspace 双 200 和完整机器目标，再发唯一一次 A：HTTP **503／4119.903917ms**。源码、五组 JS、139 份物理 JS、22 份资源及 `5f37…`／`b431…` 映像关联已分别核对；这不是组合候选 `dc43122` 的 Windows 通过。取证操作 `3dd338a1…` 保留 121 条固定原始 JSON 行；按实际 PID、Reader generation 12／request 1 和各自时钟域关联，不能用 Main 与 Windows 的单调值或 UTC 差裸算内部耗时。

本次失败链为：首个 source fence **7.0712ms** → prepare → client 在 **4003.5195ms** 记录 `phase_deadline`／mask 1，phase 余量 **−3.8901ms**、共享余量仍 **2996.1458ms** → Host prepare 被拒并返回 503。没有观察到 prepared、commit 或最终 verify，不把它们记为零耗时完成。

| 本次请求内工作         | 原始观察                                                                                                                                     | 解释范围                                                                                             |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| P 进程／Trace 环境核对 | 1 次完成，wait 324.8853ms                                                                                                                    | 不是文件批读取，也不是独立纯 Win32 CPU 时间                                                          |
| S 单项读取             | 3 次完成，wait 共 12.0401ms                                                                                                                  | 保留单项计数，不与 B 合并次数                                                                        |
| B 完整批检查           | 27 次、请求 216 项；26 完成、最后 1 次失败，wait 共 3338.5758ms、decode 21.1051ms                                                            | 请求项不等于完成项；包含派发、C#／OS、pipe 和唤醒，不能直接称纯磁盘 I/O                              |
| parser                 | 240 次纯解析共 2.9828ms；catalog 1 次、guard 209、readSnapshot 0 次                                                                          | 发现／校验尚未完成；3659.4537ms 的 parser 整体窗口与 native／prepare 重叠，不再相加                  |
| 退休与退出             | 稍后 native `childExited/stdioClosed` 为 true，client `readerExited/transportExited` 为 true；client 同线程 deadline 到退休确认为 152.8332ms | 本次读取失败后的回收已确认，不能把它写成读成功；独立内核 ExitTime／ExitCode 及三路各自时间戳仍未取得 |

因此，本次已定位的直接阻塞是**完整发现／原生校验未能在 prepare 四秒内完成**，不是纯解析耗时，也没有本次收尾卡住的证据。没有独立 native750ms 到期事件，`native_budget` 投影不能代替该证据。底层等待具体归于哪项 Win32 API、磁盘／过滤驱动、CPU 调度或 GC 仍 UNKNOWN。该结论不倒填旧 `0ee` 或旧 `727` 的约 21.67 秒。

A 前空闲窗口还观察到 P 失败引起 Reader 自然退休和恢复，外层 Host 始终同代。某代 lifetime 中 P 为 669 次／668 完成／1 失败、wait 累计约 246923ms；这是约十一分钟的累计，不能填进本次 A。该线索要求独立关注进程采样开销和可用性，不能仅以 Host 在线断言建议已就绪。

### 当前最小候选与增长边界

仅 Windows C# 候选调整两处，实际编译与验收待完成：

- `TraceState.SnapshotMany` 在原 B1…8、48KiB 请求、256KiB 响应与总深度估计内，最多并行现有八项独立完整 Snapshot。B1 走直接路径；响应仍按 index，重复项独立执行；全部已在途项结束后才返回整批成功或失败。每项完整新开 handle、Info／FinalPath／Security、内容及前后身份校验、独立缓冲保持，不重用祖先权限、不缓存或 partial／fallback。新实际扫描在 209 个 guard 时已耗尽期限，故不能把先前两路重叠的理论收益当成足够；八路的峰值句柄、内存、调度与文件锁重叠必须实测。
- `TraceProcess.Check` 候选以 fresh `OpenProcess(QUERY_INFORMATION | VM_READ)` 后的 `QueryFullProcessImageNameW(flag 0)` 核对 OS 创建映像名称，并以完整创建时间绑定原 retained process；保留原退出、毫秒创建时间、SID／session、原 pipe owner、父子执行器及环境校验。路径仍严格与 expected executable 比较，不增加别名或 fallback。它替换 Framework 模块路径查询的实现，不宣称所有环境下两种字符串恒等；不同路径按拒绝处理，真实 Windows 必须核对当前支持范围与全部拒绝边界。

原 750ms、4／4／7／8 秒及六秒 lease 均不增加，macOS 路径不改。资源重叠与直接映像查询只是减少已证工作量的候选，不是已证 GC／OS 根因或业务通过。全 Root 的 O(N) 发现与最终核验成本仍在；长期缩小范围仍需上文所述能证明最新失败／pending 完整性的 producer 定位契约，不能用未经当前证明的跨请求缓存代替。

### 验收准备故障与产品超时分开记录（2026-10-21）

`727b2c5` 的新 Windows 原生套件尚未开始。首次装配停在归档复制时磁盘不足，原生 DriveInfo 仅剩约 1 MiB，完整包内存摘要匹配，但磁盘目标为零字节；源码／程序解压、依赖安装和 Native BEGIN 均为零。用户释放空间后，实际可用约 7.30 GiB。这个错误不证明 Trace 写满磁盘，也不是 C# 测试失败，更不能解释旧 21.67 秒。

Windows 随后已正常重启并更新原桌面，旧进程不再存在，正式服务按原任务恢复；无触发器的临时 DIRECT 诊断任务没有实例和监听，退出原因仍未知。重新绑定只读采集器的准确当前路径后，完整历史检查又先停在维护线程。该采集器原本只调用 `follow(id, timeout, true)`；`true` 仅刷新原 owner，不请求完整历史。适配器已有 `completeHistory(id)`，经准确 owner 调用固定的 `thread-follower-load-complete-history` 并等待对应 revision，不提交模型输入。新私有采集器须记录原分页形状，必要时每个指定线程调用一次该方法，再保留原 `isComplete`、全部实体／岛引用、原 owner 和输入摘要检查。此调用缺口是源码已确认的准备错误，实际分页值、补齐结果与 Native 执行仍须真实回执，不能删守卫或把缺字段当空历史。

独立 iPhone 签名准备也曾因命令行 `No Accounts` 停止。复核时，旧 Xcode 界面仍显示账号／团队，但命令行和磁盘账号索引为空；正常重开后界面也显示登录入口。此前直接归因用户未登录不准确，索引原先为何为空仍未知。用户完成正常登录后，持久账号计数变为一，同一候选的独立 bundle `build-for-testing` exit／close 均为零，四个独立签名 bundle 的团队、设备描述文件、实际 App Group／Keychain 已核对，物理设备回执为 Wi-Fi `localNetwork`。这些仅证明签名、编译和连接准备恢复，不是 XCTest 正文、Windows 建议或手机业务通过。

### 组合候选原生测试的命令行传递缺陷（2026-10-21）

`27cdacd` 完整 Windows Native 已实际开始；首次失败报告为新增 buffer 拒绝矩阵启动 `new-observed.exe` 时 `spawnSync ENAMETOOLONG`，不是仅凭缺少输出判断 C# 拒绝。源码中 `request bytes` 用例将 49,153 字节（48 KiB + 1）的输入编码成 **65,540 个 Base64 字符**后作为单个 argv；仅该参数已经超过 [Microsoft CreateProcessW 文档](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw)的整条命令行 32,767 字符上限（含终止符）。原始远端调用参数、首次失败／清理和前后保护边界的回传原件仍待 Main 独立核验，不把报告提升为整组或完整链通过。

本次最小修复仅在 `scripts/windows-trace-buffer-native.test.mjs` 的**观测夹具**增加 stdin 请求入口；拒绝矩阵的完整原始 Base64 从 stdin 传入，真正的 `TraceState.SnapshotMany` 仍由原路径反射调用。夹具返回接收字符数与 SHA-256，JS 独立比较完整输入，并留存 `.stdin`、PID、输入摘要及原 stdout／stderr。49,153 字节用例、其它非法请求、零 Snapshot／零系统调用／零缓冲区和整体拒绝断言保留，5 秒原调用预算不变；不缩小输入、不跳用例，也不修改产品 helper 的 stdin 协议、C# guard、权限或 macOS 路径。此为测试启动基础设施修复，尚待新源码完整检查和 Windows 实测；旧失败继续保留，不能解释旧727约21.67秒唯一OS根因。
