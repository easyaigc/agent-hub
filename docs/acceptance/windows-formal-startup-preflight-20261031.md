# Windows 正式启动的只读诊断

基线为 PR81 合并 `8a5fcb6e7a0df62a4de408fea99c49b4dc6a5e16`。本补充保留 PR81 完整 ZIP `6d45415996a9560d7c18b34677f1e8479935d225f1a9c7640fc19d5514b7ebeb` 的五组件 ESM、核心 Native14／预检8及三个 helper cache 源摘要。仅增加诊断资源与脚本；不重建 Host、reader、Native worker 或 launcher，不修改目标私有配置。新源码与补充包须核对自己的新 tag、摘要和变动清单，不能覆盖旧资产或改写旧回执。

## 已证实与尚未知

来源 Windows 已实际通过 PR81 正常 Main 只读 Native 预检、完整 legacy 原 ctime／新幂等／16MiB 产品附件／broad ACL 拒绝恢复／24保存／451字符长路径，以及受控自己的 aux 退出后零 caller 刷新恢复、实际三个 ChildProcess 退出屏障。launcher 和注册 Worker 的 Trace helper resolve/bootstrap/private ACL/PE 也通过；没有 Trace 捕捉、producer 或冷编译证明。这些保留为对应输入和场景的独立通过，不能扩大为正式启动成功。

正式切换后两个不同 Host 子代失败：一个在 `session.connect → loadWindowsDesktop → inspectWindowsInstallation → discoverWindowsDesktopCli → inspectWindowsCli → windowsPowerShellSync`，仅记下 `WINDOWS_HELPER_FAILED`；另一个在 `runtime-server` 启动检查通知 publisher token，Native 返回 `UNSAFE_WINDOWS_PRIVATE_STATE / UnauthorizedAccessException`。此前 worker 安全日志遗漏底层 helper 的退出状态／超时分类及 stderr 固定标记，不能据时间或不同交互上下文的 Get-Acl 推定超时、错误 owner、包身份或文件故障。来源已执行回滚并独立控制恢复；本补充不执行或重放服务动作，尚不能认定正式恢复或根因已确证。

新的安全日志仅增加有限 cause 链中的 allowlist 错误码、完整 uint32 状态（含0）、信号、killed／spawnStarted、stderr 字节数／固定标记和有限 Native 元数据。不输出原 stderr、异常消息、路径、SID、token、环境值或 RPC 正文。不改变 helper 调用、总预算、身份检查、权限或退出屏障。

## 交互上下文的受支持入口

在隔离 PR81 树按补充 manifest 的准确清单应用官方脚本／新增诊断资源，核对五组件、核心 Native14／预检8仍匹配。使用原可信 Node 正常 Main：

```powershell
& '<original-node.exe>' '<candidate>/scripts/windows-private-state-preflight.mjs' --host-config '<formal-private-windows-host.json>' --bundle '<candidate>/bundle.json'
```

原 `--file <existing-private-file> --bundle <bundle>` 入口保持；也可直接检查正式 publisher token 路径，检查不读取 token 内容。`--host-config` 私有读取正式配置，只在自己的进程应用原 Worker 同一环境 allowlist，结束后恢复；不会发布刷新后的配置、启动 RuntimeSession／Host／model、打开端口或准备 Host state。它逐步检查实际闭包、自己的进程上下文、配置私有边界、原 CLI 发现／inspect，以及通知 token 的 Native 原检查；另用同源 `private-state.ps1 -Action check` 对照。Native 失败即整体失败，PowerShell 对照成功不能覆盖它。

诊断允许现有 Native backend 按原规则 bootstrap 自己的共享 helper cache；不会清理或接管旧 cache。配置和检查目标保留 bigint 文件身份／size／mtime／ctime／birthtime 快照，report 明确 backend 关闭与调用方 bootstrap 保持。不读取 token 字节，也不宣称由元数据快照证明 token 内容不变。

新增 `startup-probe.cs/.ps1` 只使用实际 Node 进程的查询句柄和 token 查询，以及被检查文件的 `READ_CONTROL | FILE_READ_ATTRIBUTES` 共享只读句柄。报告 SID／映像／包名／路径／descriptor 的摘要及有限关系、session、elevation、integrity、进程开始时间和文件身份；无原值或写权限。包信息查询对象是实际 Node，物理路径处于 MSIX LocalCache 本身不作为包身份结论。Node 逻辑／realpath 与 metadata handle 的对象对比单列，未匹配不能被默默当同一目标。

CLI、桌面配置与 publisher Native 检查从闭包返回的实际 Adapter 根动态加载，不能用源码目录另一实例代替；Worker bootstrap 的配置私有检查／报告发布仍沿用其原来源实例。报告 `workerBootstrapAndRuntimeShareModule` 区分两者，结束时分别关闭两实例。回归用源码实例接受而实际 Runtime 实例拒绝的独立边界证明拒绝不会被来源实例或 PowerShell 对照掩盖；这只修正诊断绑定，不认定多实例就是正式失败根因。

## 一次临时 scheduled 诊断

仅由来源 Root 在审阅后创建和运行自己的临时任务；不修改、触发、停止或重装正式任务。先只读取得准确正式任务的 action、Principal.UserId／LogonType／RunLevel、Settings、WorkingDirectory 及实际 launcher／Node 身份和当前服务身份。不得输出凭据。以下准备命令只创建一个此前不存在、位于正式配置／状态和 shared cache 以外的独占诊断目录：

```powershell
& '<original-node.exe>' '<candidate>/scripts/windows-private-state-preflight.mjs' --prepare-startup-probe '<formal-private-windows-host.json>' --bundle '<candidate>/bundle.json' --probe-directory '<new-owned-probe-directory>' --formal-worker-directory '<formal-program>/scripts'
```

记录返回的 envelope `windows-host.json`、report、nonce 及原文件指纹。report 必须是该独占目录的固定文件；Worker 拒绝将输出重定向至正式状态或任意私有文件。只对原正式任务实际为 InteractiveToken 的情况复用其 Principal（包括原 RunLevel），不改 LogonType、不索取密码或切换用户。其他 LogonType 先记录不支持，不能用另一身份模拟。

临时 action 使用原已验证的正式 Native `host-launcher.exe`、原 Node、候选同源 `windows-desktop-worker.mjs`，第三参数为上述 envelope；参数均为三个准确绝对路径并逐个加双引号。WorkingDirectory 按正式 action 保留（空值也记录）；不复制 triggers／重复／失败重启设置。临时任务无 trigger、无自动重试、MultipleInstances=IgnoreNew，由 Root 仅显式启动一次并记录启动回执、实际 launcher／Node／helper 身份及结束。正式任务保持原定义，Native 和 helper 原总预算不变。

Root 可按已核对的实际变量使用下列原生 Scheduler 方法；`$node`、`$candidateWorker`、`$envelope`、`$nonce` 来自上述准确输入／准备回执，不从字符串解析未知命令或环境。不要加 `-Force`，已有同名任务时停止核对原操作。

```powershell
$formal = Get-ScheduledTask -TaskName '<exact-formal-task-name>' -TaskPath '<exact-task-path>'
$logonType = [string]$formal.Principal.LogonType
if (@($formal.Actions).Count -ne 1 -or $logonType -notin @('Interactive', '3')) {
    throw 'Unsupported formal task definition; preserve it unchanged'
}
$probeName = 'CodexManage-Startup-Probe-' + $nonce
$actionOptions = @{
    Execute = $formal.Actions[0].Execute
    Argument = ('"{0}" "{1}" "{2}"' -f $node, $candidateWorker, $envelope)
}
if ($formal.Actions[0].WorkingDirectory) {
    $actionOptions.WorkingDirectory = $formal.Actions[0].WorkingDirectory
}
$action = New-ScheduledTaskAction @actionOptions
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$definition = New-ScheduledTask -Action $action -Principal $formal.Principal -Settings $settings
Register-ScheduledTask -TaskName $probeName -TaskPath $formal.TaskPath -InputObject $definition | Out-Null
Start-ScheduledTask -TaskName $probeName -TaskPath $formal.TaskPath
```

上述临时设置仅允许自己的诊断在电池状态运行，不复制正式 task settings 或改变 helper 总预算；这也是定义差异的一部分。Root 按原始 PID＋开始时间＋映像＋父进程证明自己这次实例结束，再核对 report 和最终任务状态。失败不自动重试；精确临时任务的注销和报告收尾由 Root 在保存回执后执行，不能用按名称批量终止。

`Interactive` 是 PowerShell 输入枚举名，固定值 `3` 对应微软 [TASK_LOGON_INTERACTIVE_TOKEN](https://learn.microsoft.com/en-us/windows/win32/api/taskschd/ne-taskschd-task_logon_type)；CIM 原始值与 PowerShell 返回投影不能混为一谈。示例兼容这两种表示，并原样复用已读取的 Principal；不将 Password／S4U 或其他值映射为 Interactive。目标实际返回类型和值需由 Root 只读记录，本机资料查证不冒充 Windows 实测。

Worker 对私有 envelope 走具名只读分支，继承启动环境，随后将自己的 cwd 切到登记的正式 Worker 目录再应用正式 allowlist 和执行相同预检；不进入 supervisor／Host-child／配置 refresh。它将有限结果写入预留的私有 report，并以成功0／失败1退出。核对 nonce、actual5、每步结果与实际 token／package／session／cwd／环境摘要；不能把 LastTaskResult 或 Running 单独当作预检通过。若在读取 envelope 或写 report 的私有边界前失败，保留实际任务结果和已知身份，空 report 不是成功，不绕开保护补写或重跑。

这个 probe 的 action payload、实例和 bootstrap 模块顺序与正式 Host 不同；启动器相同、定义相近或交互预检成功都不证明完整上下文等价。微软 [Task Principal](https://learn.microsoft.com/en-us/windows/win32/taskschd/principal)、[LogonType](https://learn.microsoft.com/en-us/windows/win32/taskschd/principal-logontype)、[RunLevel](https://learn.microsoft.com/en-us/windows/win32/taskschd/principal-runlevel) 与 [GetPackageFullName](https://learn.microsoft.com/en-us/windows/win32/api/appmodel/nf-appmodel-getpackagefullname) 的实际调用对象和适用范围分别核对，任何未采集项记未知。

最后仅回收本次准确临时任务和诊断目录；保留最小报告／输入摘要、操作回执及失败候选，原正式服务、桌面和其他任务资源不动。结果回本专用聊天，不经 Host 另发消息。不同上下文的差异用于决定下一项最小产品修复，不能据本诊断包宣称正式启动问题已修复。

## 复用、复验与回滚

原五组件、核心 Native14／预检8、cache key 和原验收脚本未变的 Windows 证明可按实际输入复用；不要求重复已过完整 legacy、reader、launcher 或 Trace helper 辅助。新增两份 diagnostic C#/PS1、预检／Worker 诊断分支及日志分类需要新的辅助与 Windows 执行证据。编译／合成边界回归不代表 Windows PInvoke 或 scheduled 实测。

正式部署仍由来源 Root 在最新实际冻结基线与私有配置对账后决定。本轮先交付可在隔离候选执行的诊断补充；不改变正式 R10 或强制原桌面重开。回退只撤回本补充准确管理文件，保留正式配置／身份／密钥／证书／历史／附件／Task Hub、observer／retention 和 Native config；新 helper cache 不删除。iOS／Android 共同连接、草稿、回执和未知操作不重放规则不变；未增加任一端原生功能通过。
