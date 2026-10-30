# Windows 正式启动的只读诊断

后续正式启动证据补充改变了核心 Native 的**错误序列化**与一个 Adapter JS，不能沿用下文 PR82「核心 Native 未变」作为新包结论。下文原补充与原通过均按其历史输入保留；本轮变更、复验及受控部署门禁见文末。

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

## PR82 实测之后的最小证据修复

来源报告交互与独立临时 scheduled 两次八步通过。两者实际 Node 均 elevated/high、session 1、无 package identity，默认 Owner 为 Administrators；这不能推广为失败正式 Host 的 token 事实。publisher 的 metadata 句柄身份／descriptor 与 Node 身份比较通过，未读正文。临时任务使用回滚 R10 的 `1de02c2f…` launcher；失败正式 PR81 使用 `3ebb8312…`。它们的 EXE 字节与编译上下文尚未取得同一性证明，payload／模块初始化次序也不同，因此正式失败仍未知。

源码计算显示 `1de02c2f…` 是单个 `host-launcher.cs` 的 SHA-256；`3ebb8312…` 是 launcher、private-state 与 compilation 三份源码原字节按顺序连接的准备 cache key。PR81 launcher 编译的源文件列表仍只有 `host-launcher.cs`。物理 MSIX 路径、cache 名、Principal Limited 或源文件相同都不能代替实际 EXE、进程 token 与 package identity 的证据。

已确认的诊断缺口是常驻 Native `Serve → FailureResponse` 将具体 owner／DACL 拒绝压成 `UnauthorizedAccessException`，丢失现有策略原因。本轮仅把五个固定原因映射为有界 suffix，再由原 Worker 通道解析为 `nativeRejection`：`WRONG_FILE_OWNER`、`NULL_PRIVATE_DACL`、`UNKNOWN_PRIVATE_ACE`、`SHARED_PRIVATE_FILE`、`UNPROTECTED_PRIVATE_DACL`。只有正确异常类型和准确枚举组合被接受；未知 suffix／类型仍退休通道，不重放、不转成功。任意 Exception.Message、路径、SID、token／stderr 正文不出通道。

`CodexManagePrivateStateSecurity` 实现、owner/DACL 的接受拒绝、创建租约、保护幂等、退出屏障和原预算完全保持。原 diagnostic 索引不移动，新增枚举只在末尾追加。核心 C# 改动仍会改变三个准备 cache key；不能把新 Native 编译／启动证据当作未变输入复用。新清单同时列出 Native14 两份、预检8及 cache 源摘要，编译后的 helper EXE 只能由来源记录。

### 下一项只读对照

若失败 PR81 的实际 `3ebb8312…/host-launcher.exe` 保留，Root 先只读核对普通非 reparse 文件、原 owner/DACL、实际 EXE SHA 与创建／启动身份记录。沿用**原 PR82 候选**准备新 nonce/envelope，原 Node、正式 Principal／WorkingDirectory、候选 Worker 与 bundle 不变，仅将临时 action Execute 明确替换为该已保留 EXE；记录全部差异。仍只注册／启动一次、无 trigger／retry，任务和报告各自收尾。不要为了采样重新编译／替换共享 launcher、预建 Host state 或修改权限；文件缺失或没有可信来源时记未知。新的诊断包和 cache 不混入这次单变量对照。

两次都通过只能缩小差异范围，不能模拟 `Host child → refresh 配置 → 实际 RuntimeSession/observer → publisher` 的完整初始化。若此对照仍不能定位，下面的正式诊断窗口才用于采集真实失败，不把只读 probe 命名为正式启动修复。

### 新 Native 的最小前置

在新冻结包的独立隔离目录，以原 Node 正常 Main 依次运行：

```powershell
& '<original-node.exe>' '<candidate>/scripts/windows-private-state-preflight.mjs' --host-config '<formal-private-windows-host.json>' --bundle '<candidate>/bundle.json'
& '<original-node.exe>' '<candidate>/scripts/windows-private-state-rejection-preflight.mjs' --bundle '<candidate>/bundle.json'
```

第二项只在自己的 cwd 下创建随机命名目录和合成文件，使用实际 Host ESM Adapter，向该文件添加 Everyone 读取，要求精确的 SHARED_PRIVATE_FILE 拒绝并在恢复保护后接受；随后关闭 backend 并回收自有 fixture。check 不独立要求保护位，不以解除 DACL protection 后拒绝作为此入口的成功条件。不接收正式文件路径、不改 owner、不读取正式 token。它不是完整 legacy／reader／更新通过。源端保留已通过完整 legacy 和 reader 的场景证明；本轮仅错误序列化与解析变化不要求重复这些未变算法场景，但新 Native 编译/cache、准确拒绝、清理以及新 Adapter closure 必须重新核对。

### Root 控制的正式诊断窗口与回滚

只使用新发布的**完整统一包**和冻结的逐文件守卫，不把 PR82 的 19 文件覆盖到正式 R10。发布清单绑定旧 R10 ZIP `281dc46a…`、source `a9044b05…` 和五组件实际指纹，并登记本轮完整包与 PR81 的运行差异。切换前 Root 必须再次取实际 bundle／五闭包／完整 managed 文件、配置与活跃任务依赖；任何不匹配或新增正式版本先停止复制并对账，不用旧清单覆盖。保留已有 observer／retention／Native config 等环境字段。

1. 保存准确 managed 文件旧字节和“不存在”项、原 Task XML、配置摘要及保护进程完整身份；`.local`、密钥、证书、授权、历史、附件、两代 cache、Task Hub、共享 helper cache 不进入程序回滚清单。
2. Root 经支持的 stop 结束**当前** Host launcher/supervisor/child，逐个核对 PID＋开始时间＋映像＋父／命令和端口释放；原桌面／CLI 连续保留。退出屏障不明确时不复制、不启动第二实例。
3. 按逐文件旧 SHA／不存在守卫差异安装同源 program 与 Skill，核对 actual ESM、两个 Native 资源副本及 bundle。不得整目录覆盖或从活动 R11 工作树取件。使用原支持 start 一次，保留其配置刷新账本；不手改权限、NodeOptions、argv、登录／任务身份或产品预算。
4. 记录实际 launcher／supervisor／Host 代次、固定 worker failure 字段（helper stage/cause/status/marker、nativeRejection 等）与就绪结果。预检成功或 Task Running 不算 ready。观察窗口不改变产品超时／重试策略；出现失败先保存有限原始证据，Root 正常 stop 该代次，不靠连续部署试错。未采集到的原因继续记未知。
5. 失败时仅恢复上述 managed 旧字节，删除本次原来不存在且当前 SHA 仍匹配的新程序文件，原 `.local` 等数据全部保留；用原支持 start 恢复 R10，独立验证三路鉴权 health/workspace 与同 Host、原连接和桌面连续性。不可把新旧过程间缺少快照的元数据变化归因给任一版本。

本轮解决的是已证实的诊断信息丢失；正式故障根因、升级成功及分页正式验证仍由来源后续证据决定。临时 scheduled probe 和新包的独立运行输入分别留存，不覆盖旧资产、回执或成功证明。

## 验收器契约修正（PR83 发布后）

来源对固定 PR83 blob 的静态审阅指出原拒绝夹具假阴性：assertPrivateFile 调用 Native check，而 Execute 只在 protect/protect-file 分支内验证 DiscretionaryAclProtected。当前 User owner 且仅可信主体的继承 DACL 不会因保护位解除而必然被 check 拒绝。故旧夹具的 UNPROTECTED_PRIVATE_DACL 断言不对应产品读取契约；这是源码可确定的夹具问题，不是 Windows 已实测失败或正式历史 Native 故障原因。

本次移除该额外权限变更与错误断言，保留 Everyone 读取的 SHARED_PRIVATE_FILE、原异常类型／step／拒绝分类、恢复后接受、同对象身份、关闭与清理断言。报告只声明 shared-allow 分支，不再输出 unprotectedAclRejected。其余五枚举映射保留原边界测试，产品安全类、所有运行 JS、Native14／预检8、prepared 摘要和三项 cache key 全部不变，不收紧读取规则。

最近回归运行实际 CLI 并模拟原 check 契约：合成 Native 仅拒绝共享允许主体，可信继承保持可读。旧脚本在该场景应失败，修正脚本通过；这是合成 OS/Native 边界证据，不能替代 Windows 真实执行。来源仅需在已核对的原 PR83 隔离包应用有旧 SHA 守卫的验收器／本文件补充，再执行同一正常 Main 入口；无需重建运行模块或重跑未变 reader/legacy。新脚本的 Windows 结果仍待来源给出。

PR83 已发布资产、原回执与原摘要保留不改。本修正另用规范仓库提交、Gitea PR/main、官方 GitHub 同步及不可变补充 tag 分发；补充只适用于精确 PR83 完整包，不能覆盖正式 R10 或混入活动 R11。原 PR82＋保留 PR81 launcher 的来源对照保持独立，不追加执行或改其输入。

首次 PR84 CI 另暴露已有 reader 夹具快照竞态：connect 写出 initialized 不代表子进程已消费它，日志可能在“Host 游标拒绝不写 RPC”快照之间增加正常握手行。夹具现在用自己的 PID 记录确认已消费 initialized，再冻结日志；原完整无 RPC 断言保留。只增加夹具内有界等待，不改产品握手、恢复、时限或旧进程退出屏障。原失败 CI 回执保留，受影响夹具重新验证。
