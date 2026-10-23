# Windows Host 后台启动

2026-10-20 实现；源码与隔离辅助检查不代表 Windows 桌面／手机实机已通过。

## 启动与生命周期

后台 App Server（历史、认证、模型目录、聊天元数据与空会话准备）、Host worker、PowerShell 和原生 ACL helper 使用 `windowsHide: true`。不改变用户手动打开的终端、Windows 默认终端或原 Codex 的官方启动方式。

同一个 `CodexManage Host Agent` 计划任务改由原生 GUI 子系统的 `host-launcher.exe` 启动。它只接受准确的 Node、`windows-desktop-worker.mjs` 和 `windows-host.json` 三个绝对路径，不使用 shell。以 `CREATE_NO_WINDOW | CREATE_SUSPENDED` 创建 worker，先加入当前调用的匿名 Job Object，再恢复执行。Job 设置 `KILL_ON_JOB_CLOSE`：启动器退出时只清理本次 worker 及其后代，避免定时恢复遇到残留 worker；没有 breakaway 或分离启动。绑定失败时清理尚未执行的 child 并以 70 退出。

启动器一直等待原 worker 退出，原样传回 32 位退出码。任务因此在 worker 存活期间保持 Running，原来的 `IgnoreNew` 才能覆盖整段生命周期。登录触发、无限期每分钟恢复、三次失败重试、无执行时限、当前用户 Interactive／Limited 均保留。`Hidden` 任务列表属性不承担窗口隐藏。

启动器与 ACL helper 均由 Windows 已安装的 .NET Framework 编译器从统一源码生成；启动器使用 `/target:winexe`，编译过程本身也隐藏窗口。文件放在当前用户 `LOCALAPPDATA/CodexManage/native-helpers/<源码 SHA-256>/`，核对私有 DACL，不在不同电脑之间复制。程序包同时更新规范目录与 `node_modules` 实际解析副本的源码和编译 JS。

任务管理接受原来的 Node action，或同一个私有 helper 根目录内、严格摘要子目录下的启动器 action；后者参数仍必须精确匹配原 Node／worker／config。其他任务和不同 owner 拒绝。`start` 更新下一次 action 而不打断活实例，旧进程须 `restart` 后才切换；`status.consoleLaunch` 只报告已登记 action（`legacy-console`／`native-hidden-wait`），不证明现有进程或窗口已更新。

`stop/restart` 继续先禁用该任务，再按精确命令、SID、创建时间、父子关系和保留句柄核对并清理。根查询只限定 Node 与 `host-launcher.exe` 两个名称，配置不同或 helper 根目录不同的进程不归本任务。保留总期限、PID 复用拒绝、失败不重启、已脱离任务的残留进程清理及未知模型操作不重放。

依据：[Node child_process](https://nodejs.org/docs/latest-v24.x/api/child_process.html)、[Windows 创建标志](https://learn.microsoft.com/en-us/windows/win32/procthread/process-creation-flags)、[Job Object 绑定](https://learn.microsoft.com/en-us/windows/win32/api/jobapi2/nf-jobapi2-assignprocesstojobobject)、[任务 Hidden 属性](https://learn.microsoft.com/en-us/windows/win32/taskschd/tasksettings-hidden)。

## MSIX 物理路径与当前任务迁移

来源 Windows 的真实任务曾返回 `0x80070002`：Codex 打包环境能看到 `LOCALAPPDATA/CodexManage/native-helpers/...`，文件实际位于该用户的 Codex MSIX `LocalCache/Local`，外部 Task Scheduler 无法打开原逻辑路径。同一包内启动的 native fixture 通过不能证明外部 Scheduler 可启动。来源将准确同一任务临时改成物理路径恢复运行；本节实现处理这一实际场景，新的 Windows 跨上下文／周期结果仍由来源独立验收。

Host 现在在完成编译和原私有 DACL 检查后，对**实际可执行文件**调用 `realpathSync.native`，核对文件名及源码 SHA 目录，并再次核对物理文件 DACL。launcher 和 ACL helper 的执行均使用该物理路径，缓存也保留物理路径并按 `LOCALAPPDATA` 隔离。不关闭 MSIX 虚拟化、不修改系统保护、不改变 helper 存储布局、不引入脚本引擎。

跨打包／未打包入口管理只认可两类明确推导的产品子树：当前 `LOCALAPPDATA/CodexManage/native-helpers`，以及同用户已支持的官方 Codex package family `OpenAI.Codex_2p2nqsd0c76g0/LocalCache/Local/CodexManage/native-helpers`。后者位于该用户 `LOCALAPPDATA/Packages` 下。只有当前源码摘要的 helper 确实存在、逻辑／物理文件及摘要目录均通过私有 owner/DACL 检查，才把该根的准确别名交给任务脚本。不会枚举其他 package、从旧任务任意学习根目录或修补旧别名的权限；其他根、owner 和不同 Node／worker／config 继续拒绝。任务脚本在这些明确根内沿用摘要目录、固定文件名和完整参数校验，已有源码版本的任务保持其升级语义。Package family 只定义这个旧存储别名，不是账号或 Codex 构建版本门槛。

**本次迁移顺序**：保存当前临时物理 action 的 XML 和必要本机文件；在正式程序的**原稳定目录**先按新统一包逐文件更新服务脚本、任务脚本、规范及实际解析的 Adapter 副本，保护原配置／身份／Node／端口。然后从该正式目录运行新版 `service ... status`，确认准确同一物理 action 与 owner 已被识别。独立解压目录的 worker/config 不同，不能用来管理旧任务。新版 `start` 可修正下一次 action 而不终止活实例，实际代码加载切换仍由来源安排该产品 Host 的 stop/start 或 restart，并记录新 PID、实际闭包与真实 HTTPS。

已有 45 秒总停止期限和逐进程身份检查保持。如果 CIM 查询再次超时，仍明确失败并保持任务禁用，不自动继续 restart、不按裸 PID 强杀、不触碰原桌面／其他 DEV。不能把本次路径修复报告为 CIM 性能问题也已解决。旧版本不认识新物理根时，回退仍须先用此次新版核对并停止本产品，再恢复本机旧文件和其准确原任务 XML。

辅助回归包含真实文件上的合成 MSIX `realpath` 映射、物理注册、ACL helper 物理执行、外部入口识别旧物理根、拒绝其他 package/不安全别名，以及在任务脚本中保留活实例、跨上下文 status/start/stop、拒绝其他根／owner／参数。原 14 项保持，新增 4 项任务场景。它们不等于 Windows 原生 Scheduler、窗口或真实手机通过。

依据：[MSIX 文件虚拟化](https://learn.microsoft.com/en-us/windows/msix/desktop/desktop-to-uwp-behind-the-scenes)、[Node 原生 realpath](https://github.com/nodejs/node/blob/main/doc/api/fs.md)。

## 统一包迁移

只由目标电脑负责人安排正式 Host 的停止窗口。其他 DEV、Trace collector、桌面任务及用户终端不属于此次停服范围。**已有临时 MSIX 物理 action 时，优先按上一节先更新管理文件、再核对和停止；下列旧版 stop 步骤只适用于旧入口确实能识别当前任务的安装。**

1. 核对完整 ZIP SHA-256；若分块，先逐块核对、拼回原 ZIP，再核对完整摘要。解压到暂存目录，不覆盖现有安装。
2. 将正式程序变更文件、原 Skill 和 `bundle.json` 保存到该电脑私有回退目录；另保存本机 `.local` 配置／凭据／日志和计划任务 XML，限制该目录为当前用户。用 `Export-ScheduledTask -TaskName 'CodexManage Host Agent'` 保存原 XML；核对单一 action 是已确认的原 Node 或本产品私有 launcher、准确 Node/worker/config 和当前用户 owner。记录旧 PID、实际 Node、程序入口、Host ID、端口和运行依赖闭包。不得把此备份加入分发包或报告。
3. 用**原程序和原 Node**运行 `node scripts/windows-desktop-service.mjs stop`，等待受身份校验的本产品子树退出。保持其他任务运行。原 Task Scheduler 任务应存在且禁用。
4. 将统一包的程序文件按清单合并到同一稳定安装路径，同步实际运行依赖和 Skill。保留 `.local`、Node/runtime、本机独有文件及私有配置；发现本机程序差异先导出，不整目录覆盖、不在目标独立补丁。特别核对嵌套 `dist/node_modules` 没有遮蔽副本。
5. 用原 Node 运行 `node scripts/verify-host-runtime.mjs --entry <实际 runtime-entry.js> --bundle <本包 bundle.json>`。运行 `node scripts/windows-host-launcher-smoke.mjs` 和 `node scripts/windows-control-smoke.mjs`；前者只创建自己的合成 worker，后者使用模拟任务和进程，不改正式任务。分别记录原生与 fixture 结果。
6. 原 Codex 正常运行时，用原 Node 运行 `node scripts/windows-desktop-service.mjs start`（上一步已 stop；直接原位升级则须 `restart`）。核对新 action 为 GUI launcher、精确原 Node/worker/config、当前用户、登录＋PT1M／无截止、IgnoreNew、无执行时限，以及新 launcher／supervisor／child PID。确认原配置和 Host ID、令牌、授权、端口与设备绑定保持。
7. 核对 HTTPS 鉴权与 `desktop-runtime`、`verify-host-runtime` 实际闭包及新 PID。至少跨两个周期观察，确认没有额外 launcher/worker、控制台、Terminal 窗口；再分别记录 child 退出恢复、外层退出恢复、显式 stop 不复活和重新 start 的结果。所有破坏性故障注入先在隔离本任务的安装／状态／端口运行；正式重启由该机负责人安排。真实桌面／原生手机／模型／独立 owner 核对继续逐项记证，不能由 GUI 子系统标记、编译或 Mac fixture 替代。

## 回退

1. 用仍在运行的**新版**及同一原 Node 执行 `windows-desktop-service.mjs stop`，确认任务禁用且本产品 launcher、worker 和 helper 子树均退出。旧版 stop 不识别新 launcher action，不能先换旧文件。
2. 按本机保存的变更清单恢复原程序、全部实际依赖副本、Skill 与旧 `bundle.json`，保持私有状态和身份；配置有非预期变化时先逐项对账，再恢复本机自身备份，不复制其他电脑配置。
3. 在原文件恢复完成后，将第 2 步保存并核对的**本机原 XML**重新登记到相同任务名：`Register-ScheduledTask -TaskName 'CodexManage Host Agent' -Xml (Get-Content -LiteralPath <本机原 XML> -Raw) -Force`。登记前再次检查任务名、单一已核验原 action／准确 Node/worker/config、当前用户；不删除其他任务。恢复原 XML 可能重新启用定时触发，因此必须先恢复程序。
4. 若旧版识别该原 action，用原 Node 执行旧版 `windows-desktop-service.mjs start`／`status`；若回退到不认识临时物理 action 的版本，保持已核验的本机 XML，通过 Task Scheduler 启动并独立核对状态，不能用旧 CLI 重写任务。核对原 action、登录／周期恢复、身份、HTTPS 与闭包。回退到原 Node action 会恢复旧版可见控制台行为。保留新包、失败证据及本机回退备份，结果未知的模型操作仍只查询原 ID。

## 检查范围

`pnpm check` 包含实际 subprocess 的后台选项断言、旧 Node／旧摘要 launcher 迁移、严格拒绝其他 launcher、启动器树清理、现有预算与 PID 复用、模拟周期恢复。`windows-host-launcher-smoke.mjs` 只在 Windows 运行真实编译 GUI 子系统、带空格／中文／特殊字符路径、等待存活、退出码 37 和强制结束启动器后的后代清理；Mac 明确输出 not-run。PowerShell 在 Mac 可编译 C# 并执行任务 fixture，不能因此宣称 Windows Win32、DACL、调度时序、窗口或完整手机功能通过。
