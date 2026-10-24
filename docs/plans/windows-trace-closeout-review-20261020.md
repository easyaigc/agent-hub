# Windows Trace 收尾复审与代码级实施方案

日期：2026-10-20（Asia/Shanghai）。当前状态：**组合源码 `7f2b70b` 已完成完整 `pnpm check`（799 项输入无漂移），db 候选的 Windows 原生结果为 378 通过／3 跳过／0 失败；但其真实普通建议第一请求返回 503，约 4137.87ms，矩阵已首失败停止。手机业务未验收，尚未正式发布。**

第 1–9 节保留原只读调查阶段的方案与证据：该阶段没有修改产品、测试或安装实现，已有 Windows 合成诊断和 Xcode 尝试的失败继续保留。后续获授权的候选实施及验证见第 10 节；10.5 保留 4eb 首轮结果，10.6 记录 db473e1 新证据。原文中的“当前”“待实施”和 Runner 74 均按原调查时点理解，不代表最新状态。

追加的[Windows 超时因果专项](windows-trace-timeout-causes-20261020.md)将根因调查与优化并列：重新分解旧阶段计时、核对 727 的退出机制，并用当前原源码做合成工作量计数。它区分实际失败机制、已经确认的结构问题及尚缺证据的 OS 原因，不将“增加预算”视作根治。

## 1. 结论与当前真正的阻塞

当前不是一个已经找到唯一根因的“Windows 死锁”。存在三个相互独立的阻塞，以及一个放大等待时间的交付流程问题：

1. **真实 Windows 读取的性能与稳定性缺口**：旧部署普通建议读取返回 503；新版准备／确认方案虽然已实现，还没有在普通 Host 上完成完整矩阵。提高阶段预算是候选缓解，不能算根治。
2. **最新 Windows 原生源码检查的收尾用例失败**：`slow-operation` 合成测试被外层 15 秒 watchdog 终止，用例实际约 21.67 秒。它使用 Node 假原生子进程，没有执行真实 C# Trace 读取。最新三次保留 15 秒监督及首次创建结果的诊断约 1.48、1.56、1.48 秒完成，取得六个 OS 进程退出记录，未复现旧故障。另实测确认测试已写 gone 时 child exit/close 回调可能尚未到达；原始 21 秒耗时发生在哪个阶段仍未查明。
3. **Xcode 真机通道故障**：iPhone 15 已解锁且官方设备 SDK 可访问；XCTest Runner 在测试正文前被 IDE 通道拒绝，退出 74。因此尚未取得手机 UI 的通过证据，与 Windows HTTP 是否成功是不同问题。
4. **流程耦合过重**：全套 Windows 源码检查、普通 Host 读取、手机安装、基础 UI 检查、正式发布被串成一条链；任何辅助用例失败都使不相关检查停住。解决方式是分别记录和执行各层验收，正式发布时再汇总，不能用删门槛或伪造成功记录解决。

建议顺序：补可定位的阶段证据，修正测试截止与报告缺陷，同时处理已实测的心跳抢占；随后减少 Windows 同一请求中的重复解析与校验工作、统一退场责任。旧计时中 commit 派发前被心跳占用约 156 ms，完整 commit 的 1747 次路径解析耗时约 1.07 秒；原生命令全部完成，不能将该样本概括为 helper 卡死。保留 macOS 当前数据路径，暂不进行大范围异步改写或合并 Worker。

## 2. 审阅基线与证据

审阅源码为 `codex/trace-windows-production` 的 `37e47a1a8e55de58a65e4227933ad59963e9aaea`，不是本文所在主工作区的未提交现场。开始审阅时主工作区 HEAD 为 `3e97f256069b41d8ec1256d3d8d4dff90a7ab33c`，有 14 项既有未提交工作。调查期间主工作区经并行提交推进至 `880ba1cd05a9e227cd23a810a362bbdac8d771ec`，这 14 个文件已进入提交；与本轮开始时逐字节核对仍一致。本任务没有修改这些内容，也没有把并行提交计入 Windows 候选指纹。远端主库在此前核验点尚未合入本任务；本轮未推送、合并或升级各机。

下文文件和函数定位均以审阅基线为准。私有验收包、真实会话、设备连接资料不进入 Git；这里仅记录最小结果、操作 ID 和摘要，用于查找原证据。

下文未写目录的 Adapter TypeScript 文件均位于 `packages/codex-adapter/src/`；Windows C# 文件位于 `packages/codex-adapter/windows/`。部分文件尚未合入主分支，实施者应从上述候选分支核对，不能用主工作区旧版本直接替代。

| 证据层                                                          | 实际结果                                                  | 能证明什么／不能证明什么                                                        |
| --------------------------------------------------------------- | --------------------------------------------------------- | ------------------------------------------------------------------------------- |
| 37e 本机 `pnpm check`                                           | exit 0，788 源文件冻结且无漂移                            | macOS 上的源码与辅助检查；不代替 Windows 实机                                   |
| 旧 Windows 普通读取，操作 `804a570c` 前缀                       | 第一个 A 请求 503，约 3041.56 ms，1/13 后停止             | 旧部署确有端到端失败；不是 37e 已运行失败                                       |
| 旧部署单次计时诊断 `80d6dd36` 前缀                              | prepare 约 2908.62 ms 成功，commit 约 3003.54 ms 失败     | 当时阶段预算下确认阶段不足；不是当前 4/4 秒方案有效的证明，也未确定所有成本来源 |
| 37e Windows Native，操作 `7277923f-30b3-4344-af49-92b1fe87acb4` | exit 1；98 项，97 pass、1 fail、0 skip；后续链未运行      | 最新失败位于 `desktop-trace-lifecycle/slow-operation`                           |
| 本轮单次合成诊断 `e42287e5-63c8-41da-928b-1095341f7335`         | probe exit 0，1526.87 ms，未触发 30 秒诊断 watchdog       | 原收尾故障未复现；不能改写 727 为通过                                           |
| 最小时间线诊断 `97858794-a862-4db7-b325-76af787c4151`           | 三次 exit 0；1481.06/1562.80/1475.44 ms；6 个 OS 退出记录 | 未复现；实证 PID-gone 判据不等于完整回收，原 Native FAIL 保留                   |
| iPhone 15 Xcode Preflight                                       | xcodebuild exit 65；Runner exit 74；测试正文未执行        | SDK 可连接，UI Automation 测试通道尚未成功                                      |

原 727 证据 ZIP SHA-256：`653fbc492ed0c14b4a857a1ea81cb009da9b3c7b7367fa99ef8face5a66ff171`。

本轮 e422 证据 ZIP：884301 字节、57 成员，SHA-256 `858a07b9bd23ebcbfc522abac6e7b7ed1a4d6a58962eb8b9ef144d53dd536259`。来源机已独立核对 56 个载荷摘要与 CRC；实际回传完成，传输 ID `c42cefcf-bd0b-4105-8c85-9e2b31dc8446`。实际运行设置为既有模型、`xhigh`。

本轮诊断没有改生产模块：从原失败 fixture 复制 16 个文件，其中 14 个原字节，另两个是先前准备的 probe/bootstrap 阶段记录版本；原 9 个生产 JS 模块、假子进程、platform stub 与内层预算保持。原六进程、788 源文件、旧部署、配置、旧证据及旧 ACL 的前后比较一致。它不是正式候选的新构建或部署。

## 3. 收尾失败：已知事实、确定缺陷、尚未知因

### 3.1 原失败能定位到哪里

`scripts/desktop-trace-lifecycle.test.mjs` 的 `probe()` 在 `slow-operation` 中启动一个故意不响应 `P`、忽略 `QUIT/EOF` 的 Node 子进程。生产关闭逻辑应结束它，然后 Reader 和传输 Worker 退场。

原事件为 `spawn → started → ready → request(P) → quit → eof`。这证明退出请求已送达，**不证明子进程已回收**。原 `result.json` 实际为 0 字节；创建时间距事件文件创建约 21427.59 ms。结果文件创建、序列化、写入和父进程终止的竞争关系没有原始阶段记录，不能据此断言磁盘写入导致卡住。

本轮 e422 中：close 调用返回约 5.46 ms；close 前标记至 owner-exit 约 729.58 ms；结果写入约 0.47 ms；结果 270 字节，probe 的 stdout/stderr EOF、exit、close 均被观察到。自有合成 child 以 SIGTERM 退场，probe 自身正常退出。单次成功说明该同源路径能够正常回收，不说明原失败不存在。

诊断局限：同步阶段记录可能改变时序；新结果文件预先创建；外层观察窗口改为 30 秒；未观察 OS creation identity。不能拿新文件写入很快来排除原环境的 IO 延迟，也不能拿一次正常退场排除竞态。

最新 978 诊断恢复原 15 秒 execFile、默认 stdio 和 result 首次创建边界，固定三次均正常。750/250 ms native timer 的实际间隔分别约 757–764/260–261 ms；result 首次写入约 0.37–0.45 ms。第二、三次在 helper exit/close 回调到达前已写出 `children.gone: true`，将之前的 PID 存活判据风险推进为实际证据。它仍不能解释原 21.67 秒；第二次 native Worker 四条末尾标记缺失也未掩盖。完整时间线、证据包摘要与代码级修正见[专项 4.6–4.9](windows-trace-timeout-causes-20261020.md#46-最小时间线诊断保持原监督边界)。

### 3.2 静态代码已确定的测试问题

- `until()` 只在条件为假时检查截止时间。若检查调用或事件循环延迟后才返回真，它可能把超时后的成功算作及时完成。
- `probe()` 可以串行经历启动 5 秒、等待请求 5 秒、退出 5 秒，以及 finally 再等待退出 5 秒、子进程消失 3 秒。路径的等待预算合计可达 23 秒，外层 `execFile(... timeout: 15000)` 却只有 15 秒，且尚未计入同步 IO 和报告时间。**失败收尾预算不一致是确定缺陷，但尚不能证明它是原 21.67 秒异常的唯一原因。**
- 核心阶段没有持久的单调耗时记录，最后才写结果；外层强退时可能只留下空结果。失败报告自身与被测进程的生存期绑在一起。
- 现有结果字段 `ownerExitAfterCloseMs` 在 finally 的子进程清理之后才计算，并非 owner exit 事件的实际时刻。本轮该字段为 1005 ms，而直接事件差为 729.58 ms；实施时应在事件发生处采样，分开记录 Reader、child 和管道的退场耗时。
- `process.kill(pid, 0)` 仅能作为该时刻的存活观察，不能替代本次 ChildProcess 对象的 exit/close 或 OS 创建身份；不得按遗留 PID 杀进程。

Node 定时器不保证精确触发时刻；`exit` 与子进程 stdio 完整关闭的 `close` 也不同。设计上应分别记录，而不是把“请求终止”“进程退出”“管道关闭”“已回收”当成同一状态。依据：[Node timers](https://github.com/nodejs/node/blob/v24.21.0/doc/api/timers.md)、[Node child_process](https://github.com/nodejs/node/blob/v24.21.0/doc/api/child_process.md)。这些是 API 语义依据，不是本案操作系统根因证明。

### 3.3 下一次诊断应区分的假设

| 假设                               | 必须取得的区分证据                                             | 可以采取的修复                                    |
| ---------------------------------- | -------------------------------------------------------------- | ------------------------------------------------- |
| Reader/transport 未及时退出        | close 请求、Reader exit、transport exit 与共享生命周期各自时间 | 按 5.2 统一回收；不能只放大 timeout               |
| 子进程已退出但管道未关闭           | ChildProcess exit 与 close/EOF 的间隔                          | 修正句柄/流所有权和 drain，不把 kill 返回值当回收 |
| 同步 records/存活检查/结果 IO 长尾 | 调用前后单调标记、外部监督时间线                               | 移除高频同步测试 IO、父进程写报告；不得删业务守卫 |
| 测试 deadline/调度失真             | 绝对截止、每阶段余量、事件循环延迟、外层强退时间               | 独立业务判定预算和失败留证预算                    |

e422 及最新 978 的固定三次均未重现，原故障保持“未归因”。不继续无条件跑整套检查，也不反复重跑至绿后忽略第一次失败。若实施诊断改进后仍无法复现，应给出预先定义的重复次数、负载条件和置信限制，原失败保留。

进一步收窄：原父测试的 runner.log 在整个用例末尾才创建，因此 callback 之后的 finally 不能解释主要长间隔；Main `beforeExit` 共用 1000 ms 截止，也不能单独解释结果创建前约 20 秒。Windows `TerminateProcess` 的 I/O 取消与最终退出语义，使“父 timer 迟派发”和“已请求终止、实际退出迟滞”仍需区分。完整依据、排除项和最小判因方案见[因果专项](windows-trace-timeout-causes-20261020.md)第 4–5 节。

## 4. 当前方案为何复杂，以及应该保留什么

当前 Windows 拓扑：

```text
Host HTTP / Main
  ├─ 原 owner / 来源 Turn 前、中、后三次核对
  ├─ DesktopSuggestionWorkerClient（代次、prepare/commit、截止与重试）
  │    └─ Reader Worker（同步解析、同步 Atomics 等待）
  └─ Main 内固定 broker（不是独立进程）
       └─ Native I/O Worker（处理 pipe、QUIT、kill、gone）
            └─ 常驻 trace-state.exe（Windows 句柄、DACL、文件快照）
```

Reader 内复用 `DesktopSuggestionObserver`，构造过程还会初始化私有观察目录和历史 observations/conflicts，因此可能引入另一条 private-state Worker/helper 路径。这些机制各有目的，但来源读取、通知存储、进程监督、失败回收和读取性能耦合较深。

现有预算：prepare/commit 各 4000 ms，共享 7000 ms；HTTP 总计 8000 ms；单 native 操作 750 ms；同步调用等待至多 1000 ms；旧兼容 reader/RPC 3000/3250 ms；能力提示 TTL 5000 ms；后台 Reader 退役 2500 ms；native QUIT 后 250 ms 请求 kill；成功建议租期 6000 ms。**不能把这些数字简单相加或逐层重新计时。**

需要保留的业务与隔离语义：

- 完整机器运行目标、准确 Thread/来源 Turn、原 owner、进程实例及授权目录一致。
- 最新请求优先；最新失败、不完整或歧义不能退回旧建议。
- Windows 句柄身份、重解析点、owner/DACL、祖先链和内容验证；批处理不得接受部分成功。
- prepare 不公开正文，commit 一次消费；中止、迟到和旧代次不能复活结果。
- 六秒租期从最终文件/实例校验后开始，最终 Host 核对不延长它。
- 手机预览是只读；采用进入本机草稿，可撤销，不自动发送、不额外调用模型。

普通安装不应依赖报告通过、手机在场或再次询问 Trace 授权；产品安全校验与安装授权交互是两件事。默认持久 Trace、保留明确退出选择的安装策略继续沿用。

## 5. 待实施的代码级方案

### 5.1 P0：先让一次失败能够被定位

涉及：`desktop-suggestion-worker-client.ts` 的 `prepare/acceptFenced/fail`；`desktop-suggestion-read-worker.ts` 的 `state`、prepare/commit 分支；`desktop-trace-native.ts` 的 `call`；`desktop-trace-native-worker.ts` 的 `reply/close/gone`；`apps/host-agent/src/runtime-server.ts` 的 next-turn-suggestion 路由。

建议新增内部 `windows-trace-diagnostics.ts`，只传固定类别：`phase`、`reason`、`generation`、进程内 request 序号、单调起止/余量、退出/EOF 是否观察到。类别至少覆盖 `gate_wait`、`native_start`、`native_response`、`parse`、`verify`、`prepare`、`source_fence`、`commit`、`close`、`reap`。不给公共 HTTP 增加任意诊断入口，不记录正文、令牌、完整路径或会话内容。

HTTP 仍可向用户返回统一的暂不可用，但内部应区分 `source_changed`、`phase_deadline`、`native_rejected`、`transport_lost`、`shutdown_incomplete`。只在终态/失败输出紧凑汇总；常规采样不高频同步落盘。诊断关闭时不改变预算、调用次数、启动参数或结果。

验收：故障注入能唯一指出失败阶段；迟到结果仍拒绝；诊断开关不改变产品语义；普通 Windows 路径得到自己的阶段耗时，不能借用历史计时注入数据。

另一个已定位的 P0 是 `desktop-suggestion-read-worker.ts` 的周期状态采样：旧样本在 prepared 后执行 P 与两次 S，约 156 ms 后才派发 commit，而 Main 已开始计算阶段预算。建议 prepared/committing 时暂停非必要心跳，由该请求已有的最终 fresh guard 提供状态，idle 后恢复；必须保留 TTL、失效和最终实例检查。此改动解决明确的排队成本，不能单独保证全部校验及时完成。

### 5.2 P0：统一 Windows 生命周期和回收责任

涉及：`DesktopSuggestionWorkerClient.fail/close/start`、`registerFixedNativeTransport`、`createWindowsTraceTransport`、native worker 的 `close/gone`。

现有 `fail()` 在 1 秒后可重建 Reader，旧 Reader 却有 2.5 秒退役窗口；旧 native 还有独立退场过程。可能出现旧实例仍退役、新实例已启动的重叠。它是已确认的结构性风险，不是原 727 根因结论。

建议由 Main 的固定 supervisor 唯一持有每代 Reader、transport 和自有 ChildProcess 的回收责任。可以新增 Windows 专用 `windows-trace-session.ts`，避免把 Trace 状态继续堆入通用 private-state 模块。

其中 Main 持有代次与回收协调权，实际 ChildProcess 对象仍由 native I/O Worker 持有并报告退场结果；不跨线程传递该对象，也不退化为按历史 PID 执行 kill。

建议内部状态：

```text
idle → starting → ready → preparing → prepared → committing → ready
任一活动态 → retiring → closed
已确认 closed 后，新的独立读取才可建立下一代
未能证明退场 → unavailable（保留诊断，不宣布 closed，不重放旧请求）
```

实施要求：

1. 请求失败立即撤销能力、拒绝本请求；后台回收不拖住 HTTP 返回。
2. 关闭幂等；同一代只有一条关闭流程。消息端口关闭不等于 OS 子进程已退出。
3. 对外提供可等待的关闭结果，例如 `closed: Promise<CloseOutcome>`；结果明确区分 `exited/drained/timedOut`，不得把 kill 已发送写成已回收。
4. 仅针对实际持有的子进程对象/已绑定句柄执行 QUIT 和有界终止，保留 Windows 进程创建 gate、原 owner exit 关联及启动取消检查。
5. 旧代明确退场后才允许替代代次。新建只为后续独立请求，不自动重做失败的 prepare/commit 或未知操作。
6. Main `beforeExit` 仅作最终保护，正常退出走显式监督流程；不能靠 unref 或进程自然退出完成主要回收。

不要直接把 Reader 和 native I/O Worker 合并：Reader 当前 `Atomics.wait` 同步等待响应，同一事件循环无法同时接收子进程 stdout。保留这两个执行域，先减少状态责任，后续再评估 Windows 专用异步接口。

验收：启动前取消、gate 等待取消、操作中关闭、强退 Reader、native 忽略 QUIT、native 提前退出、pipe 不完整、连续关闭、旧代晚到、新代创建各自有断言；不得杀无关进程或出现双代叠加。macOS 原路径不参与这些状态转换。

### 5.3 P0：修测试收尾，不用放宽产品判定换绿

涉及：`scripts/desktop-trace-lifecycle.test.mjs` 的 `until/probe`、逐 mode 外层 runner，以及 `scripts/windows-trace-worker.test.mjs` 的退役断言。

- `until` 用单调绝对截止；在检查条件前后均核余量，条件晚到为真仍算超时。
- 测试业务窗口、清理窗口和报告窗口分别定义。保留原生产 250/750/2500 ms 等边界断言；外层 containment 必须容纳失败路径与报告，不能在报告前随意截断。更长外层窗口不扩大业务通过条件。
- 父进程持有 ChildProcess、记录 exit/close/EOF 和最后阶段，负责最终报告。子进程的结果采用完整临时文件后发布；半写、空文件和缺失明确归为报告未完成，不能缺省为 pass。
- 固定阶段事件通过管道或内存批量记录，减少每 15 ms 同步读写日志。保留失败现场，不用清理失败目录掩盖结果。
- 已修的最终一次 `abort` 断言保留其精确约束：只允许 commit 退役末尾出现一次，不忽略中间、重复或错代事件。

验收先跑精确失败用例和生命周期组，再跑当前源码完整 Windows Native；零未解释的 failure/cancelled，平台 skip 逐项说明。成功一次只证明该次，不删除原失败。

### 5.4 P1：把 Windows 来源读取与 Mac 通知存储分开

涉及：`DesktopSuggestionObserver.constructor/createWindowsReader/prepareBoundSource`、`runSuggestionReadWorker`。

建议提取 Windows 专用读取会话，保存必要的实例、root、epoch、parser receipt 和冲突判定。Windows 的来源读取不应为了复用 Mac observer 而完整承担通知存储生命周期；已有 observations/conflicts 如参与正确性判定，必须明确迁移或保守拒绝，不能直接删除它们。

可先提供固定内部接口：`probeReadiness()`、`prepareSource(target, thread, turn, deadline, signal)`、一次性 `commit()`/`dispose()`、`close()`。它们属于 Adapter 内部，不扩展为任意远程 RPC，也不混入 Host 的 owner/source 业务判定。

macOS 继续使用已有 Python 通知、observer 持久化和同步文件读取。首阶段只改变 Windows 构造入口及其实现；不做全平台 observer 重写。

### 5.5 P1：减少请求内重复工作，不能缓存掉新鲜性检查

涉及：`desktop-prediction-trace.ts` 的 `scanSourcePredictions/readSnapshot`、`TraceSnapshot` 的 `catalog/verifyInputs/verifyGuard`，以及 `windows/trace-state.cs` 的 `Snapshot`/batch 分支。

当前扫描每个 `inference_started` 时又调用 `readSnapshot()` 解析对应绑定；最终所选结果再读一次。准备/确认已经减少了一部分跨阶段重复，但请求内仍可能重复解析日志。现有两项 batch 仅合并传输，每项 C# `Snapshot` 仍独立验证完整链。

第一步建议建立**本请求内**的解析索引：对已取得且受同一 `TraceSnapshot` 收据保护的字节解析一次，生成 `turnId → binding/event span`；选最新请求后解析正文，commit 仍重新验证所有输入和最终实例。索引随请求 dispose，不跨请求复用权限、句柄身份或正文证明。

大目录必须用工作量与时间一起约束。候选数上限不保证 7 秒内完成；记录候选数、事件行数、读取字节、原生命令数和阶段耗时，超预算明确 unavailable，不能截断扫描后把较旧候选当最新。只有未来取得可靠生产方索引/Trace 世代契约，才能另案设计跨请求增量索引；mtime 缓存不能证明候选完备性。

验收数据集涵盖空、常用、中等、大目录及最新失败/歧义/追加写入。对比同一数据、相同硬件上的冷/热端到端耗时和操作数；性能目标以新鲜实测确定，不能把历史单次收益写成当前收益。不先继续增加 4/7/8 秒预算。

### 5.6 P2：有测量收益后才做 Windows 异步读取

若 P0/P1 后主要成本仍是同步接口与跨 Worker 交接，可增加 Windows 专用 Promise/AbortSignal backend，取消 Reader 的同步 Atomics 等待，再评估减少一层 transport。前提是保留 Main-owned 回收、固定操作协议、全部新鲜守卫和晚到拒绝。macOS 的同步 backend 不因此更换。

这一阶段不是当前解阻的前置条件，不能把收尾扩成没有边界的框架重写。

## 6. 不影响 macOS 的边界与回归

| 边界          | 约束                                                              | 验证                                                            |
| ------------- | ----------------------------------------------------------------- | --------------------------------------------------------------- |
| HTTP 读取语义 | 保留 Darwin 原有两次投影、建议 ID 比较和前中后来源核对            | 精确来源变更、双投影不同、断连、晚到及租期检查                  |
| Observer      | Mac Python 通知、持久化、root/Trace 身份与启停流程保持            | observer/prediction/environment 现有回归，加真实 Mac 原桌面建议 |
| 私有权限      | Windows DACL 与 Mac uid/mode 各自实现；不改共享协议来迁就 Windows | 双平台目录/文件替换与权限失败拒绝                               |
| 生命周期      | 新 supervisor 仅 Windows 选择；Mac 不创建 Windows worker/helper   | Mac 启停、正常读取、后台通知无新增 Windows 资源                 |
| 手机          | 协议字段与采用/撤销语义保持                                       | 同一签名 App 分别连接实际 Mac、Windows，证据分开                |

共享 parser 若做一次解析索引，先固定旧输入/输出边界，再比较 Mac/Windows 同语义结果；该部分不能仅凭“Windows 分支”宣称 Mac 无影响。必要全仓检查为 `pnpm check`；只有原生源或工程改变才重建 iOS。真实 Mac 和 Windows 证据均绑定实际构建，跨机不可代验。

## 7. iPhone 15：本轮实际执行与后续方案

### 7.1 本轮真实结果

- 使用 Xcode 27 官方 `devicectl` 与 `xcodebuild test-without-building`，没有使用镜像或 iPhone 16。
- 官方锁状态读取成功：`passcodeRequired=false`、`unlockedSinceBoot=true`。受限执行环境最初无法初始化 CoreDevice；允许访问系统服务后读取成功，这不是手机密码故障。
- 对既有 App88 做测试前后只读加密状态备份；两次摘要相同。历史 load-report 不匹配当前状态，明确不把旧报告中的数量当作新加载证明。
- 复核已构建 App89 的完整 82 项产品及已锁定 framework alias，复制相同字节到新验收目录，未重建源码。
- 点名运行已有 `testPhysicalAgentHubFeaturePreflight`，只计划检查 App 启动、已有数据入口和连接切换入口，不发送业务输入。
- 实际 Runner 已启动，但通道 `dtxproxy:XCTestDriverInterface:XCTestManager_IDEInterface` 被拒绝，随后 IDE disconnection；Runner code 74，xcodebuild code 65。
- xcresult 显示系统启动失败 1、通过 0；不是用例断言失败，更不是 1 项业务测试已执行。测试正文尚未开始。
- 事后 SDK 证明实际仍安装 **App88**，App89 未装入；测试前后加密状态 SHA-256 相同。此次未产生新的正常加载证明。
- 本轮只读 load-report watcher 已用本任务停止标记结束，113 次采样只观察到同一份历史报告。后续 Xcode 窗口须另行记录其实际观察状态，不能把已停止的 watcher 当作持续监测证明。

本轮结果：**Xcode 设备访问通过；XCTest 通道失败；App UI、Windows 建议展示/采用/撤销未验收。** 不再因旧记录要求用户输入并未出现的密码。

### 7.2 Xcode 通道的下一步诊断

先核对实际 Xcode/SDK、iOS 27.0.1、Development 签名/描述文件、Runner 与设备 Developer Mode/测试管理连接；保存同一次失败的 xcresult 和设备测试管理日志。当前材料只证明通道拒绝，尚不足以确定签名、DDI、设备服务或 IDE 会话的唯一原因。

使用已有最小 Recovery 测试（不启动 Agent Hub）先验证 XCTest 控制通道。若原产品支持 `test-without-building`，使用同一锁定产物和新结果目录；不要为了绕过失败混用不同 SDK 的 Runner，也不关闭锁屏密码。必要的设备服务重连/IDE 会话恢复应在确认没有其他测试占用时进行，不能终止无关任务。

通道通过后重新执行相同 Preflight，再进行绑定真实 Host 的建议验收。每次重试要有新的环境证据或明确恢复动作，避免仅重复命令碰运气。

已有用例的准确调用形态：

```sh
DEVELOPER_DIR='<XCODE>/Contents/Developer' xcodebuild test-without-building \
  -xctestrun '<LOCKED_STAGED_PRODUCTS>/CodexManage_iphoneos27.0-arm64.xctestrun' \
  -destination 'platform=iOS,id=<IPHONE15_UDID>' \
  -only-testing:CodexManageUITests/CodexManageUITests/testPhysicalAgentHubFeaturePreflight \
  -parallel-testing-enabled NO -collect-test-diagnostics never \
  -resultBundlePath '<NEW_PRIVATE_RESULT>/preflight.xcresult'
```

这是已执行过的命令形态和后续入口，**不是成功凭证**。`test-without-building` 仍可能安装指定 App 与 Runner；每次须先备份并核对实际安装版本。

### 7.3 功能验收按层推进

1. 基础设备/备份/启动检查可独立做，不等待整个 Windows Native 套件；不得给 Windows 专用 runner 填假成功回执来解锁。
2. 当前 Windows 候选先获得完整 Native 和普通 13 场景结果，以及真实运行 Host/已加载 native helper 的对应指纹。
3. 真实 Windows 两条来源 A/B/A/B：手机灰字、全文、准确来源、不串轮次；六秒租期到期与续读（同一建议 ID、到期时间前移）均独立核对。
4. 采用仅进本机草稿、撤销恢复原草稿；原 owner 无新增手机输入、无额外模型轮。已有正文场景按原追加/替换规则，不为测试清空未知草稿。
5. 全局连接集合、选择、草稿和不可变提交在窗口前后核对；只清本任务 QA 数据。临时 DEV 与正式已有 Windows 连接分别验证。
6. 正常启动普通 App 的加载报告要与刚保存的状态关联；旧 load-report 或只比较数量不能替代完整保持证明。

## 8. 验收与发布流程的根本简化

建议维护一份机器可读、按场景分层的验收记录，字段包括：源码/产物摘要、平台、scope、执行命令、实际退出码、pass/fail/skip、原证据引用、依赖的具体结果。报告状态与安装能力判断分离。

阶段建议为：

| 阶段               | 准入                             | 通过条件                                 | 失败后继续什么                       |
| ------------------ | -------------------------------- | ---------------------------------------- | ------------------------------------ |
| 静态与构建         | 冻结源码                         | 相关检查、完整构建、源码无漂移           | 可继续不依赖构建的设备检查           |
| Windows lifecycle  | 相同生产模块/平台记录            | 关闭、回收、取消和晚到断言               | 保留精确失败；做单例诊断，不重打整包 |
| Windows Native     | 当前原生候选                     | 完整适用组通过                           | 可做独立 Mac/手机通道检查            |
| Windows 普通 Host  | 同一候选的独立 DEV 部署          | 全部既定矩阵及真实 helper 绑定           | 不把带计时工具结果冒充普通结果       |
| Xcode 通道/基础 UI | 正确设备与同一锁定产品           | Runner 握手、Preflight、保持证明         | 记录系统层失败，不归罪于业务 API     |
| 手机功能           | 当前 Host 确实可读、正确手机构建 | 全部来源/租期/采用/撤销/保持场景         | 修对应层；不重跑不相关已有效结果     |
| 正式发布           | 所有适用场景有效                 | 合并实际主库、一次正式打包、逐机更新核对 | 未满足时明确候选状态                 |

避免为每轮都复制/散落一套 runner 和“空字段待补”模板：后续将稳定执行器回收到项目 `scripts/`，参数由严格 schema 的数据 manifest 提供。测试输出不可变，状态按实际回执更新；数据 manifest 不能提供任意 shell、入口或方法名。阶段失败保留已完成结果，只有代码/环境/依赖变化影响的范围重新执行；发布仍要求完整覆盖。

内容摘要按闭包区分：Host、Adapter JS、native 源/实际二进制、iOS 原生输入/签名产物、Skill/文档。只改文档不重建手机；只改 Windows transport 不借此宣称 Mac 不需回归共享接口；不把整个候选版本号变化等同所有旧证据失效。

默认持久 Trace 的产品说明继续一次讲清：桌面已生成建议同步到手机、预览/采用/撤销、不自动发送；普通进度/完成/错误不依赖 Trace。私有记录范围与 CLI 环境继承简要告知，用户明确关闭时保留。真实系统登录或重开要求按实际状况处理，不为常规安装增加层层确认。

## 9. 实施交接与完成定义

推荐拆成三个可审查变更，各自在规范仓库维护：

1. **诊断与生命周期正确性**：5.1–5.3。先让失败可归因、关闭可证明，保留业务预算与当前功能。
2. **Windows 请求工作量与职责分离**：5.4–5.5。以真实阶段测量选择优化；P2 异步重构另案，不捆绑收尾。
3. **验收执行器整理与正式交付**：分层记录，Xcode 通道恢复后完成手机功能；全部适用门槛满足才合并和正式发布。

完成定义不是“构建成功”或“一次诊断为绿”：当前源码的 Windows Native、普通 Host 矩阵、实际 helper、macOS 保持、Xcode iPhone 15 功能及数据保持均须有证据。届时只从实际合并后的主库生成同一个正式 Host/SKILL 包，各机保留自身身份、密钥、端口、Trace 根与未提交工作，按原顺序逐机更新并分别核对。

**原调查阶段已完成：证据复审、一次 Windows 定位诊断、一次 Xcode 真机尝试、数据保持核对和本代码级方案；当时未完成项原样保留，后续实施及状态以第 10 节为准。** 任务工作树仍用于保留待交接源码和产物依赖，不在未交付时删除。

## 10. 本轮候选实施与验证状态（2026-10-20）

实现已进入候选工作树；来源为同一仓库，尚不等于新 Windows 部署通过：

- **retirement**：Main 绑定真实 owner Worker 与每条 native transport 的退出结果，Reader 失败立即撤能力，确认 Reader/native 退场后才安排恢复。Trace child 的 exit、close 和三路 stdio close 齐备才发布 gone；未知或强退缺确认不当作成功，不凭 PID0 允许新代次。250/750/2500ms 界限保持。
- **heartbeat**：Windows prepare、prepared 和 commit 期间暂停周期状态采样，处理完成、失败或中止后恢复 idle 心跳；关闭及清理异常不会重启心跳。实际请求和最终就绪/来源守卫不减少，能力提示 TTL 仍为 5000ms。
- **index**：Windows prepared 的单请求日志索引减少同一日志的重复事件解码，按完整新收据复核后才能复用解析值；持有正文计入原活跃预算，释放时清索引。全部 guard、原生 Snapshot item、三处 canonical 和目录/cwd 义务保留，没有按路径或 JSON 去重校验。macOS/非 prepared 原行为保持。
- **numeric diagnostics**：固定 phase/reason 与数值/布尔元数据覆盖 Host、client、reader、native 的读取和退场阶段，不输出正文、业务 ID、路径或凭据，不增加读取或放宽成功条件。
- **parent atomic + strict deadline**：probe 结果及父监督报告采用 partial→rename；条件求值前后均检查原单调截止。父侧记录实际 exit/close/stdio，空/部分结果不能当通过；失败日志已改为仅在原日志不存在时补写，带 stdout/stderr 哨兵的非零退出回归经过相同保护 helper。

| 本轮分项                                  | 当前结果                                  | 可证明范围                                                                                     |
| ----------------------------------------- | ----------------------------------------- | ---------------------------------------------------------------------------------------------- |
| 本地四项窄回归                            | 通过                                      | 本机编译及定向源码边界，非 Windows 实机                                                        |
| 生命周期及父报告                          | 10 项通过                                 | 合成 Node child、严格截止、原始报告保留                                                        |
| heartbeat                                 | 16 项通过                                 | 请求期间暂停、idle 恢复、失败/关闭与晚到隔离                                                   |
| iPhone 15 App90                           | 基础测试正文 1 pass、0 fail               | 用户重启解锁后通道恢复；不是建议展示/采用/撤销业务验收                                         |
| db473e1 完整 `pnpm check`                 | exit 0，795 输入无漂移、clean             | 本轮完整本地检查；原生输入与已测 build90 未变                                                  |
| db473e1 Windows Native                    | 381 项：378 pass、3 Mac-only skip、0 fail | 当前源码原生套件；不替代普通 Host 或手机业务                                                   |
| db473e1 macOS 单来源读取/续租             | 两次 HTTP 200，约 241/236ms               | 原 owner 保持；仅一条来源的读取/续租回归                                                       |
| Windows 普通 Host / 当前 Host 实际 helper | 待验收                                    | 首次 DEV preflight 停止；后续已匹配 intent/receipt/turn 与六角色身份，preflight/stage 结果待回 |
| 手机建议业务 / 正式连接 / 逐机发布        | 待验收                                    | 基础正文、source-native 和历史通过均不代替这些结果                                             |

分项结果不相加冒充 Windows suite 总数。旧 727 FAIL、21.67 秒未知原因、0 B 结果，以及旧 Xcode Runner 74 均不回写或删除；本轮修正确定的退出判据、证据保留和可观测性问题，仍不能宣布找到原长尾唯一根因。原业务预算、六秒租期、无模型输入和未知不重放约束保持。后续由 Main 继续同源普通 Host、实际 helper 与手机业务验收，再依既有授权统一合并、打包和更新，不增加新的审批流程。

### 10.5 已取得的首轮真实结果与 BOM 等价修正

候选 `4ebfdc8b6d9e05a25b965d5380687e2f57335117` 的完整本机 `pnpm check` 和 `pnpm build:ios` 均 exit 0，795 输入无漂移。真实 macOS 使用同包隔离 Host，对一条已有来源取得当前完整 owner、当前实例 Trace 和两次 HTTP 200（约251/217ms），跨6100ms保持预测 ID并更新六秒 lease，原轮次与原桌面/执行器身份保持，没有模型输入。首个本机验收脚本漏做历史读取登记而404的尝试保留；补齐具名历史读取只修正了验收前置，不是产品修复。此范围是一条来源的 Mac读取/续租回归，不冒充两来源或完整手机通过。

同一4eb源码在真实 Windows x64 上仅安装一次、完整 `pnpm run check:windows-native` 一次，exit/close均0：379项＝376通过、0失败、3项源码明确 macOS-only skip。原 `slow-operation` 本轮773.014ms，Reader在close后约2.809ms退出，parent仍用15000ms watchdog且未触发，结果/transport/child exit与close及三流close都已留证。795源码/lock、76编译JS、五FP及9条ESM闭包匹配；六运行角色/端口、7013受保护旧文件未变。回传ZIP为2790490B、873成员，来源机已核872载荷SHA与全部CRC，whole SHA256 `2641d7ef27362eeeba61cb92019c83d63ed05f4d7efe2516a61a3f4116f8a5e7`。这不证明原727/21.67秒的唯一原因，原FAIL保留；也不代表新版DEV已部署或ordinary13已通过。

iPhone15构建90已通过Xcode真实基础测试正文：1通过、0失败，普通安装与既有界面存在。重启前同构建Runner74失败原件保留；用户正常重启并解锁后该通道恢复。不能据此断言原拒绝策略的唯一根因；建议展示/采用/撤销业务仍须独立执行。

后续独立审查发现Windows prepared索引对同thread、prefix未分类bundle的行首UTF-8 BOM与旧binding解码不等价：旧逐行TextDecoder会去单个BOM，新string缓存曾错误拒绝。已最小区分binding/discovery模式：binding保持旧去BOM语义，discovery在查缓存前仍拒BOM，不能因先缓存binding而扩大接受集；fatal UTF-8、原始行字节长度、全部guard和Mac非prepared路径不变。两个针对反例先RED，修正后相关三份本机测试169通过、0失败、18平台skip。该修正后的源码需要自己的检查/新包和Windows验收；不得将上述4eb通过直接改记为新指纹通过。

### 10.6 db473e1：BOM 修正后的同源实证与剩余验收

冻结候选 `db473e1ebc065a29e7c728ae9affda6f095e4b7d` 的完整本地 `pnpm check` exit 0，795 输入无漂移且 clean。真实 Windows 单次安装及单次 `pnpm run check:windows-native` 均 exit/close 0；Native 用时 685976.5289ms，共 381 项＝378 pass、3 项源码明确 Mac-only skip、0 fail，两个 BOM 反例均通过。这是 db473e1 自身的新证据，不复用 4eb 的通过。

本次 `slow-operation` 为 731.4518ms，Reader 在 close 后约 3.032ms 退出；child exit/close、三路 stdio close 与 transport 退出均已记录，原 parent 15000ms 监督未触发。795 源码/lock、76 编译 JS、五 FP 与九条 ESM 闭包匹配，8677 个受保护旧文件及六运行角色保持。回传 ZIP 为 2997573B、873 成员，来源机核验 872 载荷 SHA 和全部 CRC，whole SHA-256 为 `6c4c3183db4773d36f5bcf03f0935d789014706d400d11439162a7846f613643`。原 727 的 21.67 秒与 0 B 失败保留，唯一根因仍未知；本次通过不倒推旧运行的 timer、I/O 或 OS 时序。

同源 macOS 一条已有来源两次 HTTP 200（约 241/236ms）完成续租，原 owner 不变。App90 在用户正常重启、解锁后的基础测试正文为 1 pass、0 fail，原生输入与 db473e1 未变；这不证明手机建议展示、采用入可撤销本地草稿或不自动发送等业务已通过。

首次 DEV 操作 `6ca` 在 preflight 停止：缺少 Main 冻结 intent；另整份身份采集 PowerShell 脚本在 30000ms 的整脚本边界返回 `ETIMEDOUT`。该脚本依次包含 name-filtered `Get-CimInstance`、`Get-NetTCPConnection`、六角色匹配、六次映像 `Get-FileHash` 及 JSON 输出；没有内部阶段时点，未区分 CIM provider、端口查询、文件哈希或 PowerShell 启动/退出耗时，不能写成 CIM 调用本身卡住 30 秒。该次未 stage、未退休旧 DEV、未启动新 DEV，STOP 原证据保留。后续操作 `9957d3e6` 已匹配 Main 独立冻结 intent、当前 receipt/turn，准确六 PID 的窄 CIM 查询已返回且完整 identity 匹配；已开始后续流程，preflight/stage 结果仍待回。这不能倒推旧聚合脚本超时的唯一原因，也不能解释旧 727 失败；普通矩阵、当前 Host 新 helper 加载、手机业务、formal 与 fleet 均未因此通过。最终文案随真实后续结果更新，本段不作为部署或发布通过回执。

## 最新真实入口失败与组合状态（2026-10-20）

- `db473e1` 的单次完整 Windows source-native 为 381 项，378 通过、3 个明确 macOS-only skip、0 失败。来源机已核对回传 ZIP 的 873 成员／872 载荷 SHA 与 CRC，795 源码、76 个 JS、五组指纹／9 ESM 及 8677 旧文件／六角色均保持。slow-operation 为 731.4518ms；15 秒父监督未触发，实际子进程 exit/close、三条 stdio close 与 transport 退出均已记录。这些结论仍限定在该源码和执行范围，原 727 的唯一根因继续未知。
- 补齐独立提交意图后，`9957d3e6` 对 DEV 完成一次替换：旧根及当次 14 个子孙约 2789.51ms 退场，30 秒启动窗口内完成原维护回执恢复，health/workspace 为 200。新程序实际 entry、完整 1243 投影、76 个运行 JS、22 资源和五组指纹均由来源机核对；原桌面、正式 Host、来源输入、密钥及路由保持。建议读取前的 loaded-helper 为 UNOBSERVED，缓存文件相同不代表新 Host 已执行该映像。
- 来源机随后执行普通 HTTPS 矩阵 `2ffe922a-db6e-476b-8e66-fa275855b7ac`，startup health 200 后，第一条 A 于 2026-10-20 15:51:40.758Z 发起，4137.87125ms 后返回 503（“原桌面建议读取暂不可用”）。原始响应和完整单调时点已私有保存；其余 12 个正例、两个负例、35 秒 idle 及手机业务均未继续，没有重试筛绿。该时长可能与阶段截止有关，但**尚未采到该请求内部阶段，不能直接认定 prepare、commit、Native I/O 或解析中的哪一项超时**。
- 下一步使用已实现的固定无内容诊断摘要，安排一次有界、只读的真实 A 诊断，区分 Main／Reader／native 阶段和实际退场；不放宽原 4s／7s／8s 判定，不把独立 utility 的结果直接冒充这次普通 HTTP 的内部时序。请求内索引的静态改进和原生测试通过，尚不能证明普通入口的剩余工作量已经解决。
- 本机另将远端已提交的弱网历史及错误处理合并为 `7f2b70b27c354ccc28df1af1fdef68bdf6845b29`；冻结树 `b0e0b974e6eaece33e146d47e4110ba1dee343cc` 的 799 项输入在完整检查前后逐字节一致，检查 exit 0，独立审查无 P0/P1 阻断。该组合仅改变 Adapter 的 runtime-session 历史浏览等待，Trace／Windows 核心其余 53 个源码与资源字节不变；既有 Native 证据保留模块范围，不能标成新组合整体原生通过。后续新代码、产物、Windows 普通读取及 App91 真机业务均须按实际结果记证，暂不合并发布或全机更新。
