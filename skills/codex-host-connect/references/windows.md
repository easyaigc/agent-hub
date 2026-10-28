# Windows 原桌面接入

包内包含 Windows 原桌面 Adapter、命名管道桥接、私有文件 DACL 和计划任务服务。2026-10-09 在构建 26.1002.52244 的目标 Windows 联合验收通过原生核心场景、普通 App 新建和用户真实 iPhone 新建/回复，并独立核对原 owner、创建工具、首条输入、审批和恢复。通过范围绑定该次环境，不自动覆盖其他安装或完整功能清单中的未验项目；程序交付不等于 Windows 全功能通过。

当前安装桌面代码的 Windows 分支使用 `\\.\pipe\codex-ipc` 和四字节长度帧。不检查 Codex 应用版本，不对照构建清单或发兼容性提示；Adapter 核对 Windows 安装归档中的实际方法版本及运行时协议。已有聊天按真实 owner 开放回复、停止和审批，不需要先完成专用聊天验收，也不要求通过证明。默认安装使用 [独立桥接](prepared-bridge.md)，按本机身份、私有权限和真实协议就绪开放正式新建／主动打开，不要求控制聊天、本机报告、Xcode 或手机在场。真实 DEV 继续按完整清单实际执行并单列证据，不能把能力启用报告为全功能实测通过。

Windows 同样默认完整接入自动发现和首次密钥授权连接，按 [Host 的私网接入检查](host.md#身份与本机检查) 验证真实 `/host` 的 `host-key`、缺失／错误密钥拒绝、正确 Host Key 的公钥加密授权和新凭据工作区目标。旧 Host 只可发现时升级统一包，首次点选后必须输入本机 Host Key。默认直连端口 8796；检查本用户证书工具、持久证书、实际监听、私网访问和所需系统网络规则，保留用户明确的关闭设置及原身份配置。不能用 Tailscale Serve、计划任务 Running 或基础回复成功代替首次授权及完整能力检查。

## 预检与启动

macOS 默认完整接入使用的 v2 单实例共享服务安装路径，目前未提供 Windows 安装适配；不要执行 macOS 的 launchctl 工具或把 `CODEX_SHARED_SERVER_URL` 指向另一台电脑。已加载聊天的归档／删除须按本机官方服务实际结果核对，writer 拒绝属于未解决限制，不能以已有聊天回复或未加载管理成功宣称全功能接入。继续完成 Windows 当前全部适用配置与验收，并在交付中单列共享管理限制；源码实现与其他电脑的通过记录不能代替 Windows 验收。

在 Windows 原生 PowerShell/Node 环境执行，使用 Windows 原桌面的用户身份。[官方 Windows 说明](https://learn.chatgpt.com/docs/windows/windows-app) 区分原生和 WSL 环境，不用 WSL 的 Linux HOME 代替桌面目录。

定位当前 Skill 的 `assets/codex-manage`，复用或安装 Node.js 24.x、pnpm 11.19.0，保持原 Codex 正常启动并登录。已编译程序无需重新安装依赖或构建：

```powershell
node --version
pnpm --version
pnpm service:windows preflight
```

预检通过保留的命名管道句柄获取 server PID，检查同一用户 SID、同一登录会话、准确进程路径和 SHA-256，以及实际归档中的 IPC profile。不以 Publisher、签名证书文案、Store 包名称或应用版本作为连接门槛；正式版和 Beta 均按实际协议与功能判断。它不启动另一桌面执行实例，不证明具体 Thread 的 owner 控制。找不到 CLI 时从实际安装中定位 `codex.exe` 并设置 `CODEX_EXECUTABLE`。应用版本不作为预检条件；归档方法版本或真实协议不兼容时报告具体失败。脚本被本机策略拒绝时报告原因，不关闭执行策略或绕过拒绝。

首次安装、修复及升级默认注册并启用登录自启，无需额外询问；正常启动无需测试 Thread ID 或验收报告：

```powershell
$env:HOST_AGENT_NAME = '办公 Windows'
$env:HOST_AGENT_ROOT = 'C:\path\to\default\workspace'
pnpm service:windows start
pnpm service:windows status
```

状态在程序 `.local/desktop-runtime`，DACL 只允许当前用户、SYSTEM 和管理员。密钥为 `access-token`，Host ID 在 `runtime.json`。不能因 Windows 没有 POSIX uid/mode 而跳过私有文件检查。bootstrap 与常驻原生 worker 使用同一文件句柄权限实现，只读取所有者与 DACL、只写受保护 DACL，不读取或修改 SACL，不请求审计特权、不接管其他所有者；重复保护安全目录不要求管理员。NULL DACL、共享授权及重解析点仍拒绝。

服务使用当前用户交互登录会话的 `CodexManage Host Agent` 计划任务，记录 Node/程序路径并在登录时启动；同一任务另有无截止的每分钟周期触发，补启已退出的外层 supervisor。`start`／`restart` 注册或修正本产品任务的当前用户登录及周期触发器和启动设置，启用电池供电运行、禁止并行实例，失败后每分钟重试一次、最多三次。拒绝操作指向其他 worker/config 或其他用户的同名任务。`start` 保留原配置且不重复启动正在运行的 Host；`restart` 重启本产品 Host；`stop` 停止并禁用任务，后续登录及周期补启保持关闭，保留私有状态，之后 `start` 重新启用。不关闭原桌面或停止其他聊天。

安装后检查 `status` 的 `running: true`、`loginAutostart: true` 和 `periodicRecovery: true`（interval=PT1M）；周期配置与实际触发分开核对。loginAutostart要求任务已启用且具有当前用户已启用的登录触发器。核对任务的用户、交互登录、Node/worker/config 路径及启动设置，再执行回环／HTTPS 身份探测；不输出配置中的密钥。用户登录前不运行，原 Codex 仍须正常打开并登录。普通安装无需注销或重启电脑，核对当前任务注册、系统启用和在线状态；真实注销／重启恢复仍在可用窗口实际验收，未执行时记录未验收，任务注册或 fixture 通过不能代替 Windows 实机恢复。

升级旧包时保留 `.local`、原 Host ID、密钥、名称、项目根目录、端口和控制配置。先比较本机 Windows 补丁与随包源码，保留已有适配和未提交修改；源码差异导回主开发仓库审查、合并、检查并构建，再按统一包差异部署，目标电脑不独立维护源码或在正式运行目录构建。然后执行 `pnpm service:windows restart`，它会将旧 `windows-desktop.json` 的 `validationOnly` 改为 false，并使用本次核对身份后的真实安装位置；用户明确的验证专用配置先核对用途并保留。旧 `validationThreadId` 仅供测试选目标，旧证明继续作为记录保存，不再限制日常 Thread。Adapter 对尚未迁移的旧字段也不施加白名单。

回环/HTTPS 和实际 owner 有效后，手机可回复任何属于目标电脑且已加载的聊天。若提示没有 owner，先在正常 Codex 中打开该聊天并刷新。不能把存储历史当作运行时连接成功；没有配置自动打开入口时如实说明这项条件，不归因于 Windows 验收未完成。

## 真实验收与结果报告

验收用于记录效果和定位问题，不是正常回复的前置开关。选一条原桌面已加载、空闲、专用于测试的聊天，勿使用工作聊天；审批测试沿用能真实产生审批请求的权限，不降低整个桌面的权限。

```powershell
$env:DESKTOP_WINDOWS_TEST_THREAD_ID = '<dedicated-loaded-idle-thread-id>'
pnpm test:desktop:windows
```

脚本只针对专用 Thread：检查空闲 owner，核对含随机码的原回复；停止本次 Turn 并观察实际 interrupted；准备确切 `Write-Output` 随机码命令的真实审批，并保存待审批与部分验收报告。首次运行停在该请求；取得该精确请求的授权后，用 `pnpm test:desktop:windows -- --approve-once=<authorized-nonce>` 继续，核对请求身份、命令、原工具输出和请求消失，再检查原桌面 PID/安装摘要不变。会调用模型。失败不生成通过证明，只尝试停止本脚本明确创建的活动 Turn，不重发不确定输入，不批准其他请求。

全部通过才生成受权限保护的 `windows-verification-<nonce>.json`，绑定 Host、构建、可执行文件、归档和当前 Adapter 摘要，记录真实结果。不手填通过、不复制另一电脑的证明。回复已在此前按实际 owner 开放，不需要等待审批测试后才解除只读。核对网络与身份：

```powershell
node skills/codex-host-connect/scripts/probe-host.mjs --state-dir .local/desktop-runtime --endpoint http://127.0.0.1:8794
```

每次连接把验收记录标为未验收、匹配通过、过期或不可读取；缺失/过期不阻止已有聊天控制。旧验收结论保持原环境范围，不因应用版本变化修改桥接配置或补写通过；服务按实际身份和协议启动。只有真实连接可用时才声明回复等能力，原 owner 丢失或协议失败撤销受影响聊天的控制。

正式创建／主动打开使用独立桥接：取得本机 `runtime.json` 的 Host ID，在同一私有状态目录写入 `prepared-bridge.json`，以 `CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG` 的绝对路径加载并重启本产品 Host。正常配置省略 validationOnly，用户明确的验证专用配置保留；不要求 verification，DACL、真实 owner 与协议检查保持有效。开发验收的六项创建和未加载打开证据仍绑定该电脑。可由主开发 Mac 的原生 App 直接连接这台 Windows Host 做联合验收，Windows 独立核对实际 owner、工具调用、输入及恢复日志；连接 macOS Host 的结果不能复用给 Windows。旧控制聊天兼容 driver 可按 [独立验证主动打开](acceptance.md#独立验证主动打开) 分阶段诊断；默认独立桥接配置两者。按 [完整功能清单](full-functions.md) 补验其他适用能力，再从普通正式手机 App 验证新建。没有完整真实证据时准确报告验收未完成和未验证项，不以报告缺失关闭已就绪的独立桥接，也不将未配置新建/主动打开扩散为所有聊天只读。

## Trace 与下一轮建议

完整安装默认持久配置，先向用户说明用途和本机保存范围，继续完成常规设置，不追加逐项授权。手机读取原 Codex 已生成的下一步建议，可查看、采用到草稿和撤销；普通进度、完成和错误继续走原运行时。保留明确关闭或缩小范围的选择，Trace 暂不可用时继续完成其余接入。

在随包稳定程序目录用原 Node 24 执行：

```powershell
& '<原 Node 24 绝对路径>' scripts/windows-suggestion-trace-environment.mjs install
& '<原 Node 24 绝对路径>' scripts/windows-suggestion-trace-environment.mjs status
```

仅在用户明确要求关闭或回退持久 Trace 时执行：

```powershell
& '<原 Node 24 绝对路径>' scripts/windows-suggestion-trace-environment.mjs disable
```

工具管理当前用户 HKCU Environment 的 `CODEX_ROLLOUT_TRACE_ROOT`，默认记录在原生 `%USERPROFILE%\.config\agent-hub\desktop-trace\traces`，保护原生私有 DACL，保留原值及用户后续修改。这个目录避开 Store/MSIX 对 LOCALAPPDATA 的重定向，不能通过放宽路径或权限校验处理别名。完整请求／返回保存在本机，同用户后续 CLI 也可能继承。按原服务配置将 Host 的 `CODEX_DESKTOP_SUGGESTION_OBSERVER=1` 和 `CODEX_DESKTOP_SUGGESTION_TRACE_ROOT` 设为工具返回的实际目录，再按原流程重启本产品 Host，保留身份与连接。

注册表成功只是未来配置。请用户保存工作后择机正常退出并从原图标重开；不强退 Codex／Explorer，不使用 macOS 启动器。原主进程和直属执行器必须实际继承同一目录；旧启动器环境未刷新时，分别核对普通重开和合适时机的重新登录。原生 x64 探针已实现，ARM64／32 位尚未验证。

当前实现按原生请求顺序读取准确来源，不声明桌面输入框实际可见。当前进程、新记录、Host／HTTP、手机展示／采用／撤销、真实登录恢复分别验收；详见随包源码的 `docs/windows-native-trace.md`。缺项照实交付，不能把源码、fixture 或注册成功写成正式链路全通过。

## 局域网与可选 Tailscale

默认双通道，纯局域网无需安装 Tailscale 或手机 VPN。按 [网络配置](network.md) 核对 8796 私网 HTTPS、持久证书、公钥授权及新凭据工作区；Windows 防火墙仅允许所需私有网络来源和端口，不能全局关闭。需要在外远控时完成下面的 Tailscale 入口，保留原地址并将它写入 `HOST_AGENT_HTTPS_ENDPOINTS`；此变量由 `service:windows` 持久保存。尚未完成远程通道时准确交付局域网可用与远程未配置。

## Tailscale 与手机

复用官方 Windows Tailscale，用户完成登录。先查看 `tailscale status` 和 `tailscale serve status`；443 空闲时执行 `tailscale serve --bg 8794`，取实际 HTTPS 域名。保留现有入口，必要时选择空闲 HTTPS 端口，不启用 Funnel。手机加入同一 tailnet 并开启 VPN。

```powershell
node skills/codex-host-connect/scripts/probe-host.mjs --state-dir .local/desktop-runtime --endpoint 'https://<actual-serve-name>.ts.net'
```

按 [必交付参数](../SKILL.md#配置后的必交付参数) 输出实际名称、HTTPS、私有密钥文件位置、Host ID 和各层结果。iPhone 跨网络、附件、原桌面渲染和双实体电脑隔离逐项验收。

外层 supervisor 消失与 child 崩溃是不同验收项。每分钟同任务触发沿用 IgnoreNew，不替换活进程，也不修复活进程 HTTP 挂起；stop 禁用整个任务，start／restart 必须保留双触发。升级后核对 `periodicRecovery`、实际任务 XML 和下一次真实触发，按[外层恢复验收条件](../../../docs/development.md#windows-外层-supervisor-周期恢复2026-10-18)在隔离合成任务记录 0xC000013A 后新 PID、Running 跨周期不重复和 stop 后不复活，再独立核对正式连接。注册成功、Mac fixture 或历史 child 恢复不等于此项 Windows 已通过。

计划任务的 LastTaskResult 必须与 State、实际进程身份及 HTTP 一起核对；Running、原实例未变且 HTTP 正常时，不能仅凭某次周期触发后的非零结果判定现有 Host 故障。不要因此重启健康实例。

`windows-control-smoke.mjs` 是隔离验证器：子 Node 保留真实系统启动环境，在 mock 加载后才切换合成路径；PowerShell 子进程使用真实系统环境并隔离 PSModulePath。关停 fixture 检查先禁用、再停止任务，以及已验证对象的完整清理和无关对象保护，不要求不同 PowerShell 引擎有相同的 Hashtable 遍历顺序。fixture、Windows 原生调度和正式服务连接分别报告；验证器失败不能自动推翻已验收的产品策略或触发生产重启。

## MSIX helper 物理路径

打包 Codex 中的 `LOCALAPPDATA` 文件可能被重定向；同进程 native fixture 成功不能代替外部计划任务启动。新版 helper 对实际 exe 使用原生 realpath 并核对物理 DACL，注册、调用和缓存使用物理路径。当前用户的正常产品目录和明确 Codex MSIX 缓存目录通过摘要文件／私有 DACL 验证后，才成为严格任务别名；其他根／owner／原参数不匹配仍拒绝，不改变系统虚拟化或私有配置。已有临时物理 action 时，先在原稳定目录合并新版管理脚本和两类 Adapter 副本，再用新版 status 识别同一任务，随后由来源安排本产品重启。详见随包 [MSIX 迁移顺序](../assets/codex-manage/docs/windows-background-launch.md#msix-物理路径与当前任务迁移)。45 秒 CIM 停止期限及失败不继续 restart 保留；路径通过不能冒充该性能问题或 Windows 真实 Scheduler 全部通过。

## 后台启动与旧任务迁移

统一程序已为后台 metadata／worker／原生 helper 设置隐藏窗口，并将计划任务 action 迁到等待 worker 退出的 GUI launcher。它保留原登录、每分钟 IgnoreNew 恢复、退出码和准确 stop/status 身份边界，不修改用户终端。`status.consoleLaunch=native-hidden-wait` 只说明 action 已登记；已有活进程须 stop/start 或 restart 才切换，不能仅设置任务 Hidden 属性。按随包 [迁移与回退](../assets/codex-manage/docs/windows-background-launch.md) 保存本机 XML／文件／私有配置、合并统一产物并核对实际依赖闭包。回退先用新版 stop，再还原旧文件及本机原任务 XML；旧版不识别 launcher action。原生 launcher 边界用 `node scripts/windows-host-launcher-smoke.mjs` 独立验证，窗口、调度周期和正式手机功能仍由本机逐项实测。
