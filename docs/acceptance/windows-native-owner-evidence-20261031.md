# Windows 正式启动的同句柄 owner 诊断

本项解决拒绝原因不可追溯的问题。正式启动已经返回 `WRONG_FILE_OWNER`，但此前另一个交互进程的 `Get-Acl` 通过，尚不能解释该差异。新增诊断不表示正式失败已修复，也不改变严格 User owner、DACL、对象身份、进程退出屏障或总预算。

## 实际 Native 拒绝事实

`check` 在打开的同一目标句柄上取得文件信息和安全描述符，然后比较描述符的 owner 与 helper 的 `WindowsIdentity.User`。现在只在该比较已拒绝时，向原有固定拒绝后附加有界的 `OWNER1` 元数据。原 `CheckOwner` 与 `CheckAccess` 规则保持。

字段包括：具名 action、实际 helper PID／可取得的开始 FILETIME、helper User SID 摘要、可取得的 helper 默认 Owner SID 摘要、**本次被拒绝的描述符**的 owner 摘要、请求路径摘要、同句柄文件身份摘要、可取得的最终路径摘要和文件／目录类型。Node 另记录 helper PID 是否匹配实际传输子进程的 PID；不匹配仍拒绝，不能转成通过。

SID 摘要是 SID 字符串 UTF-8 的 SHA-256。路径去除扩展路径前缀、将 `/` 转为 `\`、转为不区分大小写的形式后计算 SHA-256；UNC 保留 `\\`。句柄身份摘要采用卷序列号八位十六进制、冒号、文件索引十六位十六进制组成的 UTF-8 字符串。均使用小写十六进制。FILETIME 为十进制字符串，避免 JSON 数字精度损失。

不输出原始 SID、私有路径、文件内容、任意异常正文或任意 `Exception.Data`。可选信息取不到时缺省；采集失败保留原有拒绝。日志投影再次校验版本、枚举、长度、数字范围和摘要格式。该协议没有新增 Native action。

有界共享响应容量由 256 字节变为 1024 字节以容纳固定诊断字段，Worker 和 Main 都执行严格解析。旧结果的字段／长度约束保持；超限、错序、未知或坏摘要仍退役该通道，不重试未知操作。该容量变化不增加 Native 启动／操作／退出时限，也不更改进程并发或环境白名单。

只读启动报告还取得独立 PowerShell metadata helper 的 User 和文件 owner 摘要。如果实际 runtime Native 检查拒绝且携带同句柄证据，`publisherOwnerBinding` 比较 User、owner、文件身份、最终路径、配置路径与传输 PID。无法比较时明确为 `unavailable`。独立 metadata 是另一进程、另一时点的观察，不冒充拒绝时的句柄事实；任何一致标记都不能覆盖 Native 拒绝。

## Root 执行的 Limited 临时任务

先使用同一个冻结候选完成入口、bundle、实际 Host ESM 闭包与 Native 资源校验。以下变量均来自 Root 的私有受控输入，不从示例猜造：

- `$Node`：正式任务所用原 `node.exe` 的绝对路径，核对映像和字节身份。
- `$Program`：本次候选 `assets/codex-manage` 的绝对路径；`$Bundle` 为此包顶层 bundle。
- `$HostConfig`：现存正式 `windows-host.json`，只读，不复制凭据正文到报告。
- `$ProbeDirectory`：尚不存在的本任务独占目录。
- `$FormalWorkerDirectory`：正式 `windows-desktop-worker.mjs` 所在目录。
- `$Launcher`：已核对准确身份与私有权限的正式 launcher。
- `$FormalTaskName`／`$FormalTaskPath`：准确正式任务；`$ProbeTaskName`／`$ProbeTaskPath` 是本次唯一临时任务，不与任何现存任务同名。

先用原 Node 正常 Main 准备 envelope，参数顺序固定：

```powershell
$raw = & $Node "$Program/scripts/windows-private-state-preflight.mjs" `
  --prepare-startup-probe $HostConfig --bundle $Bundle `
  --probe-directory $ProbeDirectory --formal-worker-directory $FormalWorkerDirectory
if ($LASTEXITCODE -ne 0) { throw 'Probe preparation failed; inspect original receipt' }
$prepared = $raw | ConvertFrom-Json
```

prepare 创建本任务私有目录、预留报告和 `windows-host.json` envelope，不注册任务、不启动正式 Host。`launcherProtocol.arguments` 返回三个独立路径，顺序是**当前实际 preparing Node**、候选固定 Worker、生成的配置。`nodeSource=actual-preparing-process` 不代表已证明它是正式 Node；必须与 `$Node` 和实际正式配置核对，`formalNodeIdentityMustBeVerified=true` 明示该门槛。

准确读取正式任务的单一 action、Principal.UserId／LogonType／RunLevel 和 WorkingDirectory。只支持原 Interactive（或数值 3）；保留原 Principal，包括 Limited，不提高权限。可按如下具名入口创建临时任务；先保存操作意图和任务不存在的证据：

```powershell
$formal = Get-ScheduledTask -TaskName $FormalTaskName -TaskPath $FormalTaskPath -ErrorAction Stop
if (@($formal.Actions).Count -ne 1) { throw 'Expected one formal action' }
if ([string]$formal.Principal.LogonType -notin @('Interactive', '3')) {
  throw 'Unsupported formal logon type'
}
$a = @($prepared.launcherProtocol.arguments)
if ($prepared.launcherProtocol.version -ne 1 -or $a.Count -ne 3) {
  throw 'Unsupported fixed launcher protocol'
}
$expected = @($Node, "$Program/scripts/windows-desktop-worker.mjs", $prepared.configuration)
for ($i = 0; $i -lt 3; $i++) {
  if (-not [IO.Path]::IsPathRooted($a[$i]) -or $a[$i] -match '["\r\n]') {
    throw 'Unsafe launcher argument'
  }
  if ([IO.Path]::GetFullPath($a[$i]) -ine [IO.Path]::GetFullPath($expected[$i])) {
    throw 'Launcher argument differs from reviewed input'
  }
}
$argument = ($a | ForEach-Object { '"' + $_ + '"' }) -join ' '
$actionOptions = @{ Execute = $Launcher; Argument = $argument }
if (-not [string]::IsNullOrEmpty($formal.Actions[0].WorkingDirectory)) {
  $actionOptions.WorkingDirectory = $formal.Actions[0].WorkingDirectory
}
$action = New-ScheduledTaskAction @actionOptions
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew
$definition = New-ScheduledTask -Action $action -Principal $formal.Principal -Settings $settings
Register-ScheduledTask -TaskName $ProbeTaskName -TaskPath $ProbeTaskPath -InputObject $definition -ErrorAction Stop
Start-ScheduledTask -TaskName $ProbeTaskName -TaskPath $ProbeTaskPath -ErrorAction Stop
```

不使用 `-Force`、trigger、失败重启或重复计划。只启动一次；命令超时或回执未知时查原任务／报告／进程，不再次 Start。此文档不授权 Mac 远程操作 Windows；正式生命周期仍由 Root 控制。

launcher 只接受三个 rooted 文件路径，文件名必须是 `node.exe`、`windows-desktop-worker.mjs`、`windows-host.json`。wrapper 或别名不是支持入口；退出 64 发生在参数拒绝时，不代表 probe 已运行。保留 Task action 的 WorkingDirectory（包括空值），同时记录 launcher 实际将 Node 初始 cwd 设为**候选 Worker 的父目录**。预检再切到 envelope 登记的正式 Worker 目录，结束恢复。两者不可混为同一个事实；此预检也不执行完整正式 Host 初始化。

成功需核对 nonce、实际五组件、每一步、backend 关闭与 metadata 保持标记，以及 launcher→Node→helper 的实际 PID、开始时间、映像、父链和结束证据。Principal 的 Limited 字段不能替代实际执行 token；`Running` 或 LastTaskResult 单独不能证明通过。保存有限报告和原始回执，不复制凭据正文。

确认所有自有进程结束并保存证据后，只注销准确临时任务并回收其独占目录。正式任务、原 Host／Codex、共享 cache 和其他任务资源保持。

## 证据适用范围

本次修改实际 Native 源和 Adapter 解析，三个 cache key 与相关资源指纹应从新冻结包重新核对。旧输入的 Windows 正向／reader／launcher／Trace 结果保留原适用范围；不能写成新 Native 或正式 Limited 启动已经通过。原权限策略、读取恢复和未知提交不重放规则保持。当前本地测试只证明协议、关联、脱敏和固定入口契约；真实 Limited 预检与后续正式受控切换由 Windows 独立验收。
