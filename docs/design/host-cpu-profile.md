# macOS Host 有界 CPU 采集

## 状态和边界

2026-11-23：投影优化 r4 的目标实机持续 CPU 验收未通过。原生 sample 仍出现 GC、JSON、UTF-8 和 SHA256；嵌套栈不能证明具体 JS 调用或各路径 CPU 占比。此增量提供诊断，不宣称已修复持续高 CPU。真实剩余主因须由部署负责人在原 Host 上采集后判断。

仅 macOS、Node 24 的规范 Host 程序支持此入口。旧 r4 PID 没有此内置入口，不能在保持相同 PID 的同时补装。先旁路准备新冻结包、核实际 ESM 闭包和在途操作，由部署负责人按既有安全切换条件仅切换 Host 一次；不能重启原桌面、停止模型任务、重放输入或覆盖状态。无法安全切换时继续保留旧 Host，先取其原生 sample。

普通启动只检查私有 arm 文件，不创建 Worker、周期轮询、GC observer 或调试端口。显式 arm 绑定 Host ID、Node 与 entry 的实际路径和 SHA256，最长15分钟且启动时消费一次；重启不恢复采集、不重放请求。一个 arm 最多三次、每次5–60秒、固定2000µs采样。Worker 用内部 `Session.connectToMainThread()`，只有固定 Profiler enable/setSamplingInterval/start/stop，无任意方法、JS 求值、heap snapshot、网络 Inspector、HTTP 能力或模型输入。原有网络调试端口存在时拒绝启动；若正在使用其他内置 CPU profiler，先由其负责人结束，不能并发争用采样器。

请求必须引用 Worker 发布的当前 PID 和随机 bootId。意图、原操作 ID 与回执留在原私有状态的 `.cpu-profile` 子目录，目录0700、文件0600、同 UID；拒绝符号链接、硬链接控制文件和宽松权限。控制文件上限16KiB，profile最多60000节点、120000样本、16MiB，超限显式失败，不截断有效 profile。原始 profile 含代码 URL、函数名和本机路径，保留本机私有，不放公开报告或日志。

Worker 使用单调截止时间，并在 Main 忙碌时独立请求 stop。CDP 响应等待最多10秒，统计消息最多5秒；这些是客户端等待界限，不能保证 V8/Main 在时限内执行停止或释放全部原生资源。超时记为 unknown，关闭本采集 Session、停止继续采集并保留原操作；不杀 Host、不重复 start。关闭确认以 ready=closed 和原回执核对；unknown 仍需原生 sample/负责人确认，不能当成功或自行重启。arm 到期或显式 stop 都结束此 Worker；下一次 Host 启动不会重新启用。正常 Host 关闭只终止本 Worker 并释放本统计 observer。

## 操作步骤

以下均由目标机部署负责人执行。复用原 LaunchAgent 的绝对 Node 路径、原 Host 状态目录及新冻结程序 entry；不猜测默认目录，不改变身份/令牌/服务配置。CLI 使用同一 Node，arm 尽量紧邻已获授权的 Host 安全切换，15分钟内生效；arm 本身不会启动进程或采集。若尚未消费且错过切换窗口，可用 `disarm --state-dir <原目录> --operation-id <arm 返回的原 operationId>` 精确撤销后重新准备；它不停止 Host，也不删除已消费意图或回执。

```sh
PROFILE_NODE='/absolute/path/to/original/node'
PROFILE_CLI='/absolute/path/to/new/codex-host-connect/scripts/profile-host.mjs'
PROFILE_STATE='/absolute/path/to/original/private/host-state'
PROFILE_ENTRY='/absolute/path/to/new/assets/codex-manage/apps/host-agent/dist/runtime-entry.js'
"$PROFILE_NODE" "$PROFILE_CLI" arm --state-dir "$PROFILE_STATE" --host-id '<original-host-uuid>' --entry "$PROFILE_ENTRY"
```

按原正式生命周期入口安全切换 Host 后，核新 Host PID、原桌面/模型进程启动时间、原 owner/turn/输入集合及独立 ESM 指纹。仅 Host 已可安全退出和保存时才能切换；诊断工具不会替操作者进行切换。

```sh
"$PROFILE_NODE" "$PROFILE_CLI" status --state-dir "$PROFILE_STATE"
PROFILE_PID='<new-Host-PID-from-status-and-process-check>'
PROFILE_OPERATION='<new-stable-uuid>'
"$PROFILE_NODE" "$PROFILE_CLI" start --state-dir "$PROFILE_STATE" --pid "$PROFILE_PID" --operation-id "$PROFILE_OPERATION" --seconds 30
"$PROFILE_NODE" "$PROFILE_CLI" receipt --state-dir "$PROFILE_STATE" --operation-id "$PROFILE_OPERATION"
```

start 提交不是完成。用同一 operation 查询到 completed，核 profile 文件大小/SHA256、实际起止与实际 duration；不要只等名义30秒就假定成功。结果未知时只查询同一回执；禁止换 ID 重试。需要提前停止或结束全部诊断轮询：

```sh
"$PROFILE_NODE" "$PROFILE_CLI" stop --state-dir "$PROFILE_STATE" --pid "$PROFILE_PID"
"$PROFILE_NODE" "$PROFILE_CLI" receipt --state-dir "$PROFILE_STATE" --operation-id "$PROFILE_OPERATION"
"$PROFILE_NODE" "$PROFILE_CLI" status --state-dir "$PROFILE_STATE"
```

stop 不停止 Host/模型，只请求结束当前采集和本 Worker；核 ready=closed。已完成 profile 仍私有保留，最小证据与原回执对账后由负责人按原留存规则清理本目录，工具不自动删除诊断或历史。回滚仍先对账原状态及在途操作，用原正式入口恢复旧冻结程序，不复制其他电脑状态、不整体覆盖 runtime.json。旧程序会忽略本私有诊断目录。

对于仍在运行的旧 PID，可按现有授权取5秒原生 sample，不发送 SIGUSR1：

```sh
umask 077
/usr/bin/sample "$PROFILE_PID" 5 10 -file '/absolute/private/task-evidence/host-native-sample.txt'
```

同时保留相同时间窗的 process CPU/RSS、实际 PID/启动时间和负载变化。原生 sample 可见 GC/加密/线程栈，不可靠映射 JS；不能冒充 `.cpuprofile`。

## 解读与验证

profile 是 Main isolate 的样本，采样间隔/startTime/endTime/timeDeltas 单位均µs；hitCount是样本数，不是调用次数。按样本的实际 timeDeltas 汇总叶节点及祖先栈，报告未分类、idle、native、GC，勿将 Main 样本占比等同整个进程 CPU。其他 V8/系统 Worker 的 CPU 要对照原生 sample 和进程/Main thread CPU 计数。

同一窗口启用固定标签：持久化 save/serialize/SHA256、原桌面 frame UTF-8/parse、完整投影、附件呈现、实时文件 inspect、HTTP JSON/UTF-8。只计次数、总/最大墙钟耗时；不记录正文、路径、线程或目标 ID。GC observer 也只计事件/墙钟时间。异步阶段含等待，嵌套计时会重叠，不能相加作为 CPU 占比；Main 统计边界与 Worker Profiler 边界存在可见偏移。原命令落盘、文件授权、完整内容、媒体更新、单调业务截止与队列语义不变。采集本身有开销，应保留未采集的同负载 CPU 对照。

辅助验证命令为 `pnpm check:host-cpu-profile` 和 `pnpm check`；实际 macOS Node 子进程覆盖 Main 函数样本、无网络调试端口、私有文件、PID/范围拒绝、原操作不重放、其他内部 Runtime Session 仍可用，以及 Main 忙碌时独立 stop 请求和进程继续运行。Linux 仅覆盖可移植统计/私有文件，不冒充 macOS或目标机通过。此阶段没有新增移动页面/协议，iOS/Android 共用原服务，移动端和 Windows 不新增验收通过。

实现依据：[Node connectToMainThread](https://nodejs.org/download/release/v24.22.1/docs/api/inspector.html#sessionconnecttomainthread)、[Session disconnect](https://nodejs.org/download/release/v24.22.1/docs/api/inspector.html#sessiondisconnect)、[CDP Profiler](https://chromedevtools.github.io/devtools-protocol/tot/Profiler/)。不使用会开放任意本地调试执行入口的 [SIGUSR1 Inspector](https://nodejs.org/learn/getting-started/debugging#security-implications)。
