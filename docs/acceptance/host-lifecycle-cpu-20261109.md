# Host 子进程生命周期与 Trace 回收开销（2026-11-09）

范围：F03／F69，UI01／UI10，S01／S11／S18。基线 `5936eab0b992b10c99df20360be2071e44dc9f75`，本轮只在隔离分支修复和验证；没有更新正式服务、其他电脑、推送、合并或发布。主工作区的其他未提交改动没有进入候选。

## 来源现场与版本边界

来源反馈旧 Host 长时间高 CPU／内存，终止后其三个子进程一起退出；旧现场没有保全，不能据此认定 orphan 或 zombie。launchd 登记的新 Host 的 PPID=1 属于正常托管。后续一分钟来源测量为单核约 59.7%、RSS 2157–2436 MiB，同三个 app-server 的 PPID 一直指向该 Host。没有观察到该分钟重复 spawn。来源五秒 sample 的摘要核验一致，但其 JIT 栈没有 JavaScript 函数名，不能仅由 LStat／JSON／GC 唯一确定函数或内存来源。

本机留存的 R10 发布记录将冻结源码指向 `a9044b05bd9d565966f8a7e8c2fc7973ec68171a`。留存包的 runtime-entry.js SHA-256 与来源给出的 `242de5515416447e8164bc5b73d59a8aa58f4c945de2cea950ddc5370e0a09e7` 相同；这是只有启动调用的短入口，不能证明来源全部模块与该包相同。该冻结提交与本次基线的 history-reader、desktop-native-queue、desktop-thread-preparer、desktop-trace-retention 及其 worker 源码相同。来源实际 Adapter／worker 摘要尚未逐一核对。

## 子进程职责与已复现缺陷

| 连接                   | 调用方与 initialize 名称                                               | 预期存活数量                             |
| ---------------------- | ---------------------------------------------------------------------- | ---------------------------------------- |
| 执行目标的只读历史连接 | DesktopRuntime.reader；codex_manage_history                            | 每个 DesktopRuntime 最多 1               |
| 工作区展示目录连接     | DesktopWorkspaceView.reader；codex_manage_history                      | 每个工作区展示器最多 1，按需启动         |
| 原生输入队列元数据连接 | DesktopNativeQueue → MetadataConnection；codex_manage_empty_metadata   | 每个队列最多 1，按需启动                 |
| 空会话／fork／项目管理 | DesktopThreadPreparer／DesktopWorkspaceManagement → MetadataConnection | 每个在途独立操作短时 1，结束必须确认退出 |
| 聊天管理               | ChatMetadata；agent_hub_chat_metadata                                  | 每个在途操作短时 1                       |
| 语音认证               | readDesktopBackendAuth；codex_manage_voice_auth                        | 每个认证读取短时 1，已有单独退出确认     |

前三种可形成正常的三个常驻 app-server，但没有来源 initialize 记录或描述符证据，不能把来源三个 PID 的职责按启动顺序强行一一对应。MetadataConnection 的多个用途共用 initialize 名称，不以该名称单独授权任何写入。

已复现：队列初始化中 close 没有立即结束该连接；解析异常后 abort 只发送 SIGTERM，尚未退出便允许下一次 spawn；忽略 SIGTERM 的子进程持续存活。读取器原有 close 屏障阻止重叠，但没有升级回收路径，可能长期卡在退出失败。聊天管理有相同单信号收尾缺口。

修复只对持有的 ChildProcess 句柄执行回收，保留 close Promise；SIGTERM 后一秒仍存活则 SIGKILL，五秒内未确认 stdio close 则保留实际连接与失败屏障，禁止替换；晚到的真实 close 确认后允许显式恢复，不永久缓存超时失败。原生队列同时跟踪初始化连接，合并并发连接请求；失败请求不自动重放。准备器正常关闭仍走 EOF／零退出码确认，超时的强制回收不得授权原桌面接管。读取器 close 后的显式 connect 仍受支持，不由关闭自动复活。

## Trace 开销调用链与修复

`DesktopSuggestionObserver.start → desktop-trace-retention-worker.tick → DesktopTraceRetention.process → inventory → FileHandle.read(64 KiB) → directories／sameDirectories → LStat`。

worker 每秒最多处理四个目录，普通保留结果每分钟重试。旧路径在 sealed Trace 仍被占用时先哈希全部 payload，每块读前后核对目录，再运行 lsof，最终返回 occupied。大且持续占用的 Trace 因此每轮产生重复读盘与 LStat，即使 runtime.json 没有写入也会发生。该调用链符合来源 sample 的异步文件读取工作线程特征；本地复现证明它存在，不证明来源全部高占用均由它造成。

最小修复在 inventory 前增加现有 closedMac 的新鲜占用检查。早期结果只允许拒绝回收；原 inventory、删除前占用检查、原子隔离后占用／全内容复核及回收 helper 均保留。不缓存安全通过、不更改 launchd。来源后续只读统计为 225 个 bundle／319495 个文件／约 13.29 GiB，其中 payload 318663 个／约 13.05 GiB；11.533 秒内处理 28 次且无新增回收，近期有 occupied／diagnostic-retention／source-unverified／TRACE_READ_BUDGET。该证据支持积压与重复保留，不能单独量化各项 CPU。

为避免剩余保留／失败项每分钟重复高成本处理，worker 对同一目录的同一结果按 1／2／4／8／15 分钟逐步退避；结果变化回到一分，active-or-unsealed 仍五秒，新目录仍首次扫描即入队。目录删除清除记录。代价是解除占用或补齐证据后，回收最长延后十五分钟；不延迟聊天读取／执行，不缓存删除许可，进入处理时完整重验。进程重启后从冷扫描开始，不能保证启动第一轮为低 CPU，也不跳过未知回收意图。

同负载 fixture 为 225 个一级 Trace 目录，四个合法 sealed user Trace 各 32 MiB payload、500 个额外小文件，测试进程真实持有文件描述符，真实 lsof；两版均为独立 Node 24.22.1 进程，均返回四个 occupied，全部原始字节保持。

| 同负载指标                      |        基线 |        修复 |
| ------------------------------- | ----------: | ----------: |
| 全 inventory 异步读取次数       |        6088 |           0 |
| 全 inventory 读取字节           |   134227728 |           0 |
| LStat 次数                      |      121920 |         172 |
| lsof 子进程（逐次退出，无常驻） |           4 |           4 |
| CPU 用户态＋系统态              |  789.057 ms |   20.809 ms |
| 墙钟                            | 2172.470 ms | 1428.065 ms |
| 测量窗口起始 RSS                |   128.8 MiB |   113.1 MiB |
| 测量窗口采样峰值 RSS            |   131.1 MiB |   113.8 MiB |
| 窗口内峰值增加                  |    2.33 MiB |    0.72 MiB |

该 fixture 的本进程 CPU 时间降低约 97.4%，LStat 降低约 99.86%；CPU 不包含 lsof 子进程 CPU，墙钟受系统及 lsof 成本影响。RSS 每五毫秒、文件读取及子进程结束处采样，可能遗漏瞬态峰值；起始 RSS 含 fixture 构造，不能据此宣称来源 Host 的稳态内存已下降。此负载只有 128 MiB／约两千 payload，不冒称复现来源全部 13.29 GiB／31.9 万文件。

同一性能脚本另验证：关闭文件后真实 retired；无验证来源保留；第一次早拒后才打开文件，仍在原删除前检查返回 occupied；隔离后才打开或修改内容，仍返回 occupied-or-changed。原删除守卫没有由早拒代替。既有 Trace 回收 37 项中 32 通过、5 项平台跳过。

真实 worker 代码配合隔离 synthetic 回收器的虚拟一小时／225 项积压调度验证通过：稳定保留项各不超过十次处理，active 保持短重试，新目录十秒内处理，恢复项十五分钟内重验。它验证调度与公平性，不是实际墙钟一小时的生产 CPU 测量。

原始计数、日志、sample 指纹与前后证据保存在本机私有任务目录，不提交实际聊天、附件或机器路径。

## 验收范围

- 真实 Node 子进程、异常传输、初始化关闭、SIGTERM 不响应、串行重连及未知 queue/add 不重放是隔离辅助证据。
- Trace fixture 的真实文件系统、lsof 和全量读取计数是本机性能辅助证据，不是来源正式 Host 的修复前后 CPU／RSS。
- 本机原 Electron 的当前聊天 owner 与真实快照／目录读取已通过独立 metadata 连接核对，只有一个测试 child，结束已确认关闭，零模型输入。真实 owner 写入、手机、Windows、不可捕获的 Host SIGKILL／系统崩溃及来源新包部署未验收。不能声称本次消除了来源 2–3 GiB 内存或所有持续 CPU。
- iOS／Android 使用相同 Host 行为，不改变原生 UI、领域协议、缓存或设备密钥；两端不新增真机通过，A6 暂停保持。

生命周期缺口和 Trace 工作量缺口在当前复现中是两个独立原因，没有证据证明同源。正式环境仍需按准确模块指纹及 retention 状态／JavaScript CPU profile 进一步归因。

## 本轮检查记录

Node 24.22.1 的基线合成生命周期测试为 9 项中 5 项通过、4 项失败；最新构建的 13 项全部通过，包含读取器和队列的晚到 stdio close、未确认时禁止重连及未知写入不重放。它们是真实 Node 子进程的隔离 fixture，不代表正式部署、手机或真实模型写入验收。

原始 `pnpm check` 运行约 5 分 29 秒，退出码 1：`check:agent-contract` 因环境未提供 JDK 17 而停止。使用任务级 JDK／Kotlin 后首次从该断点续跑约 1 分 53 秒、退出码 1，因继承的 Command Line Tools 路径缺少 Xcode 构建工具。再以任务级 Xcode 路径从同一断点续跑约 6 分 45 秒，原生契约通过，后续检查到最后的 native suggestions 组时，读取器刷新测试因退出未确认失败；该组 167 项通过、1 项失败、7 项平台跳过。两次环境调整均未修改系统全局选择。不能将以上分段结果写成单次完整 `pnpm check` 通过。

原失败项为读取器关闭尚未确认时释放关闭事件后的恢复路径，旧实现永久保留 rejected 屏障。最终 late-close 补修后重新构建；13 项生命周期测试、此前失败的读取器刷新测试以及历史、准备、新建、首输入、工作区、fork、队列 IPC 和聊天操作专项均通过。完整 native suggestions 组重新执行退出码 0：168 项通过、7 项平台跳过，耗时约 13 秒。格式、lint、类型和移动端一致性检查通过。未受补修影响的 Trace／Windows 边界及原生契约沿用对应模块未变的先前检查；Windows 平台专属项按实际跳过，不代替 Windows 真机验证。此处 Electron owner 只读证据不等同手机、正式部署或真实模型写入通过。

## 主线集成续轮

后续源码合入授权与以上首轮隔离验收分开记录。以 Gitea 主线 `ce47d4a5f7a3b94a5b4b774b68ce6468ddf73d23` 为基底，仅移植原候选 `874d67bf0a43343fb4290845be7397aa5c368f7d` 的二十个任务文件；保留主线的诊断事件、私有文件 API、可信 CLI 解析和全部现有检查链，不带入原候选父分支的其他开发内容。更新一致性记录为集成后的源码指纹。

原候选的性能对照、生命周期与真实 owner 只读记录保留为历史证据，不冒称新主线构建通过。集成后重新构建并核对受影响的生命周期、目录／历史、准备器、队列、聊天管理、Trace 回收及私有文件边界；正式 Gitea PR 与合并后 CI、公开仓库自动同步按服务器实际回执另存私有交付记录。源码进入主线不等于部署或正式运行环境验收，手机、Windows 与来源高占用的修复后实机对照仍待后续授权范围内完成。
