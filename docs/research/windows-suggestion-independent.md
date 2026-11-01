# Windows 建议采集：独立于原桌面启动的可行性研究

2026-11-01。目标是保留原 Codex 已生成的下一轮建议，让采集器的缺失、配置错误、崩溃、挂起或 Host 离线只影响采集。正常图标启动、原 CLI 的 initialize 和应用升级不得依赖采集器。此前停用全局垫片是启动安全处置，完整建议采集仍是待完成目标。

## 当前结论

尚未达到开发生产采集器的可行性门槛。已找到值得在目标 Windows 核查的只读来源：**原应用已经写出的 rollout Trace 与安全决策日志**。目前只有静态机制证据，缺少该机同一安装、同一原进程的完整数据及结果关联证明。不能把旧解析器未读到正文扩大成原始载荷必然没有正文，也不能把一个候选方案写成已可用。

本轮未恢复 `CODEX_CLI_PATH`，未开发或部署生产采集器，未改原应用、启动配置、模型输入、身份或密钥。原 Windows Host 的现有入口在只读健康探测时拒绝连接；当前入口尚待补齐，因此 Windows 核查、实施及验收继续待办。历史通过不提升为本次通过。

## 已复核的证据

上游代码固定于 `openai/codex` 的 `67f0994b532b8740f14b5b738af7b7b2a915b1d6`；它不是目标 Windows 安装版本。官方文档及固定源码支持以下有限结论：

| 候选                        | 已确认的机制                                                                       | 当前缺口／结论                                                                    |
| --------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| 官方独立只读接口／订阅      | app-server 区分请求响应和通知；该版本 sample 响应定向发给原请求连接                | 未证实旁观连接能取得原 composer 请求／结果，不能用新生成代替读取                  |
| `thread/prediction/request` | 固定版本处理器返回尚未实现，通知类型声明存在                                       | 类型存在不证明原桌面采用、实际发送或缓存可读                                      |
| 既有 Trace＋安全日志        | provider 完成后可能先保存完整响应，再交给 sample 截获器；后续清理可能中断临时 Turn | 有条件候选；须核对原机完整载荷、来源、实际采纳及安全判定的唯一关联                |
| DOM／辅助功能树             | 历史版本建议节点有 `inert`／`aria-hidden`；现有 DOM PoC 只读已经开放的开发端点     | 可见页面、后台覆盖、原始文字和来源轮次证明均有限，不作为完整方案；不为此开启调试  |
| ETW／普通日志               | 事件载荷取决于 provider                                                            | 尚无 Codex 已启用 provider 提供所需正文与关联键的证据，不新增采样、提权或系统配置 |

原始依据：

- [官方 App Server 文档](https://developers.openai.com/codex/app-server)。
- [sample 实现及清理顺序](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/app-server/src/thread_sample.rs#L105-L441)。调用 sample 会生成新内容，不是现有结果 getter。
- [定向响应路由](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/app-server/src/outgoing_message.rs#L751-L789)与[传输选择](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/app-server/src/transport.rs)。
- [客户端写入完成记录的时序](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/core/src/client.rs#L2610-L2660)与[Trace 响应载荷](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/rollout-trace/src/inference.rs#L193-L221)。完整推理结果与原桌面成功采纳是两个判断。
- [未实现的 prediction 请求](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/app-server/src/message_processor.rs#L1466-L1469)及[Trace 启用条件](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/rollout-trace/src/thread.rs#L103-L108)。默认行为不能推断目标机实际配置。
- [Microsoft ETW 模型](https://learn.microsoft.com/en-us/windows/win32/etw/about-event-tracing)。

仓库现有 `desktop-prediction-trace.ts` 对终态要求 `completed`；不能简单改为接受 `aborted`。同一份完整 provider 响应仍可能因源历史变化、取消、RPC 失败、解析或安全过滤而未被原桌面采纳。现有安全日志解析只识别来源 Thread、来源 Turn 和 suppressed；重复生成时，这些字段本身不能区分具体结果。

## 先做的 Windows 只读核查

在原机现有权限、程序和服务配置下核对：

1. 原桌面／直属 CLI 的路径、摘要、PID、启动时间及当前用户／会话；真实 Host、workspace、runtime 与已存在的 Trace 根。安装版本只作证据元数据。
2. 原自然 sample 的请求元数据是否保留准确父 Thread 和来源 Turn；不要仅依赖时间、文件名、当前聊天标题或历史字段假设。
3. 同一次推理的 `inference_started`、`inference_completed`、响应引用及完整载荷，关联调用／响应标识和文件身份，区分完整响应、partial 与未完成尾部。
4. 原桌面安全决定与该结果能否唯一对应；同源轮重复请求、迟到结果、缓存命中、进程重启、日志轮转或缺记录时不能猜测对应关系。仅看到一个完整候选，不证明其他尝试不存在。
5. 原桌面确实展示或保留同一建议的独立核对；正文、版本和来源一致，且无额外模型请求或输入重放。

只读核查不设置新启动参数或环境变量，不打开调试端点，不接管／重复读取原 stdio，不改写源文件，不启动预测。私有证据仅留在目标私有任务目录，对外返回字段存在性、数量、长度及摘要。当前入口未连通，以上各项均未取得本次 Windows 结果。

## 进入实现的条件与结构

先取得原机证据，再选择最窄来源；若官方权威 `readExisting` 能直接提供等价的来源、原生成版本和安全通过状态，可使用该契约，不强制复制旧 RPC request ID 或垫片进程链。若证据不足，保持不可用并继续定位，不新增另一种影响启动的替代方案。

通过后拟采用 Host 独立的可选采集进程／Worker，保留有界读取、资源预算、私有权限和具名领域接口。源文件只读打开且共享原程序的写入／轮转，采集读取不能持有阻断原写入的锁。原 Codex 不等待该进程、不经过其管道，也不由它创建或终止；collector 失败只关闭建议能力。新来源应定义与实际权威来源匹配的生产者证明，不能伪装成旧 shim 或盲目移除原 owner、完整目标、DACL、来源、安全判断与租期检查。

本节是候选结构，尚未实现；Trace 根未启用、正文未保存或关联不足均不是通过安装器“强开”功能的理由。

## 验证与交付门槛

- 独立辅助回归：完整／partial／取消、抑制、重复生成、旧轮与迟到、进程和文件身份变化、截断／轮转、并发与损坏配置；失败不重新生成、不回放输入。
- 原生 Windows：collector 不存在、坏配置、崩溃、挂起和 Host 离线时，原桌面正常图标启动与 initialize 成功；仅操作本任务隔离进程，保护既有工作。应用升级后重开与采集恢复分别核对。
- 实际功能：真实原桌面／模型任务、原建议正文与准确来源、Windows Host 及原生手机展示／采用／撤销；iOS 与 Android 分别标记，A6 保持人类暂停。其他平台或 fixture 不代替 Windows 通过。
- 来源统一：确认可行后在主开发仓库实现、检查及冻结构建，统一产物再分发到指定 Windows；不在目标机维护修复分叉。

目前只有研究与静态代码复核完成。开发、Windows 数据源实证及上述实际验证均未完成。

## 本轮资源与证据

两份独立研究报告与只读连接结果保存在主开发电脑私有任务目录，保留至本任务 Windows 验收与收尾完成；不包含登录凭据或真实对话正文。调研临时源码副本已回收，未创建服务、工作树或目标专用聊天。独立 Agent 已完成；创建参数请求 Terra／medium，但接口不暴露实际模型、推理及速度记录，因此不宣称已独立核实运行档位。现有生产改动和前一阶段安全修复保留。
