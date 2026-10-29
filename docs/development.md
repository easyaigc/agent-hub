# 开发指南

2026-10-29 Host 诊断与只读恢复的字段、轮转、抑制、私有权限、关闭屏障及逐平台故障验收见[专项指南](host-reconnect-diagnostics.md)。先保存具体 503 分类／诊断 ID，未知写入查原回执；不得以增加 timeout、反复重启原 Codex 或重新发送输入代替诊断。

长历史性能续验见 [2026-10-24 专项](research/history-thermal-20261024.md)。`thermal-ios-comparison.mjs --cpu-profile --work-profile` 同时采集相同设备、安装路径和PID的CPU栈、Power Profiler及数值帧；`thermal-ios-results.py <本轮目录>` 只统计正式窗口中的完整手势和移动帧，保留导航/预热累计值但不混为正式最大帧。纯滚动要求24个实测手势；`testThermalMixedInteractionDEV` 与 `--mixed-targets <私有目标元数据文件>` 另要求三客户端17个具名交互步骤、22个历史手势与三次不同的在途回复。远端参数从既有私有清单读取，仅通过 `xcodebuild(1)` 的 `TEST_RUNNER_<VAR>` 内存环境传给Runner，不复制至xctestrun；驱动不导出远端既有消息正文。每轮使用新目录与原始不可变输入标识，模型原owner、轮次和无重放另核对，未知结果不能重新提交。该混合用例需人类授权所选其他客户端，不能因为脚本可用而自动联系远端Agent。

混合交互可用 `--native-only --work-profile` 按普通 XCTest 路径启动 App，核对准确安装路径和 PID，采集进程 CPU、唤醒、逻辑写入及移动帧；该模式没有 Instruments 功耗数据，不能从 CPU 换算瓦特或电池消耗。完整流程缺少 END 或任何步骤时，标准解析器必须拒绝通过。另作已完成公共操作段的数值对照时，须用原 BEGIN 与实际最后共同步骤结束时间，核对目标、步骤、手势及驱动指纹，单列范围，并保留完整流程失败，不改写原始日志。

XCTest 可能在显式安装之后再次安装目标，因此正常启动模式按准确 Bundle ID 查询当前安装 URL，再核对进程路径。旧采集器若因此失败，可从原 XCResult 用 `xcresulttool get log --type action` 导出同轮 `action-log.json`，通过 `thermal-ios-results.py --reconcile-native-identity <本轮目录>` 补充核验。必须在同一 Xcode 启动段同时取得准确 bundle、安装 URL、成功启动 PID，并与原 XCTest AX PID 和冻结 xctestrun 的 App 二进制 SHA 对应；原生完整流程仍须独立通过。输出另存 `metrics-reconciled.json`，不改原报告、不补造采集成功。缺少启动段、身份跨段拼接、PID 不同或二进制变化均拒绝。

2026-10-25 资源测量：`thermal-ios-comparison.mjs --native-only --work-profile --idle-baseline` 在原三客户端交互前增加最多300秒热状态等待及60秒保持真实连接、无UI操作/AX轮询的静置窗口。正式17步/22手势与静置窗口分别解析，不将等待窗口并入CPU平均值。会话探针单列Data.write数据字节、按主状态/备份/历史分类的字节、校验读取字节、成功APFS克隆次数及引用已有数据字节；克隆引用不是新写入数据，编码/校验WallMS也不是CPU时间。计数不覆盖其他组件或物理闪存。`check:ios:persistence` 另覆盖克隆失败回退、内容不符与原子发布失败，原数据保护和命令落盘门禁保持；见[基准与结果](research/mobile-performance-io-20261025.md)。

冷却就绪取Runner原生`ProcessInfo.thermalState`，最终热状态取App数值采样；SwiftUI可访问性值可能缓存，不能作为就绪证明。三客户端的直接XCTest预检可设置`TEST_RUNNER_THERMAL_NAVIGATION_ONLY=1`做8次只读打开，零模型输入；完整测量驱动仍要求三条原模型输入，预检不能作为全流程通过。当前目标在远端被归档时保留远端状态，重新固定有效目标，不将旧输入重放。修订测试源码、重建签名和实际执行的二进制指纹分别留证。

`--native-only`仅支持显式启用逐帧探针的`testThermalMixedInteractionDEV`和`testThermalStreamingHistorySwitchDEV`；其他热性能用例通过Instruments启动启用探针。驱动在创建证据目录、读取私有配置或操作设备前拒绝不支持的组合，避免沿用旧进程遗留的帧文件。一次只读诊断中暴露的缺失探针与PID附加失败分别保留，不能把原生流程通过或发起附加当作采样成功。

独立静置另外在XCTest完成后，以CoreDevice准确启动独立验收bundle，仅开启5秒数值采样，排除30秒恢复后静置65秒；不启用滚动探针或AX轮询。该窗口要求至少50秒实际覆盖，按新进程时间范围切片，并核对没有其他同机XCTest。原生标准滚动关闭数值探针，单列`XCTOSSignpostMetric.scrollDecelerationMetric`实际导出的名称、原单位和三次测量，不把自定义最大移动帧当作Apple hitch ratio。

`pnpm check:ios:publication`在Apple平台编译生产发布属性/方法片段和运行态DTO，检查ObservedObject成员绑定、值快照、同目标游标持久化、首次确认与48个字段变化；目标桩只隔离发布机制。它纳入`pnpm check`，非Apple平台明确跳过原生检查，不能作为手机/原桌面验收替代。

`testThermalStreamingHistorySwitchDEV`使用已明确建立的专用原聊天：保留两轮已完成长回复（800编号行），仅再提交一个新标识的600行回复，先真实翻读旧内容，再切到空白草稿，返回等原轮次完成，总计24手势。原桌面分别核对旧行数、新输入唯一性、xhigh、完成状态以及历史阅读/实际切离时间落在原轮次内；开始导航的标记不能替代切到目标的时间。混合400行流程中未在结束前切离的记录保留失败，不能因补充场景通过而改写。测试运行器可以单独编译后绑定原冻结App，必须另记测试源码/项目/Runner指纹、确认没有重建App并核对App可执行文件摘要；新测试的证据与混合、系统滚动和无XCTest静置分别登记。

2026-10-24 手机功耗验收使用 `scripts/mobile/thermal-ios-comparison.mjs`：指定私有证据目录、准确物理设备、before/after、冻结源码、专用 XCTest、私有连接夹具及签名 xctestrun。只操作独立验收 Bundle。Instruments 外部启动不会替 XCTest 安装目标 App，必须先显式安装准确签名主程序，保存二进制 SHA-256／UUID 和 CoreDevice 回执，并核对安装 URL、当前 Bundle 路径、trace 中的进程路径/PID及 xcresult 物理设备。两版使用相同测量代码、刷新率、手势、亮度和电源条件。

`python3 scripts/mobile/thermal-ios-results.py <private-proof-directory> ...` 核对完整测量窗口、主 App PID、XML 引用和缺失采样，按实际区间与窗口交集加权功耗影响并列出覆盖率。CPU 为单核百分比，会话写入计数为成功原子保存的逻辑字节，非物理闪存。历史帧样本含预热，CPU／功耗窗口排除预热；持续回复窗口包含产品新建／发送、模型等待和输出，不把模型时延变化当作客户端加速。原桌面 owner 另核对唯一输入、完成状态、实际模型／推理档位及完整输出；未知结果只查原记录，不重新发送。

保持充电时只比较同机 App 功耗影响和系统热等级：impact units 不是瓦特，热等级不是摄氏度，整机耗电为零不能推导电池续航。候选未实际安装的无效对照、测试失败与最终有效配对分别保留。验收完成后移除本任务独立 App／运行器及专用模拟器，保留原 App、私有连接和原始证据。

Windows MSIX 原生 helper 的物理路径、严格旧根别名及当前临时修正任务的迁移见 [MSIX 专项](windows-background-launch.md#msix-物理路径与当前任务迁移)。`node scripts/windows-native-helper-path-smoke.mjs` 验证合成重定向、物理 helper 和跨入口候选根；配合 `DESKTOP_TASK_FIXTURE_PWSH=<pwsh> node scripts/windows-control-smoke.mjs` 保留原 14 项并增加 4 项任务场景。真实 Scheduler 与两周期验收另记，不修改系统虚拟化策略。

Windows GUI launcher、后台 helper、保留任务生命周期的迁移、逐机实测与精确回退见 [Windows 后台启动](windows-background-launch.md)。`pnpm check` 在 Windows 另执行真实 launcher 隔离检查；Mac 使用 `DESKTOP_TASK_FIXTURE_PWSH=<pwsh>` 执行任务 fixture，并可用 `Add-Type -Path packages/codex-adapter/windows/host-launcher.cs` 编译检查。它们分别记为辅助证据，Windows 窗口、正式任务、手机和模型结果由目标独立记录。

发送回执自动核对的专项回归：在专用模拟器上运行 `REPLY_WAIT_AUTOMATIC=1 IOS_SIMULATOR_ID=<dedicated-simulator> node scripts/reply-wait-ios-fixture.mjs`，覆盖快照省略回执、原 HTTP 响应挂起期间按 ID 自动确认并释放发送锁、迟到超时与新操作隔离，以及无回执时结束等待和重启保留草稿。追加 `REPLY_WAIT_STALLED_HISTORY=1` 将历史请求持续挂起，要求准确回执仍独立恢复，并记录历史尚未返回时的查询；历史完成不是回执核对的前置条件。真实 Electron／原生 App／两次模型任务使用相同变量及 `CODEX_EXECUTABLE=<installed-official-cli> node scripts/reply-wait-desktop-ios-acceptance.mjs`；只查询原回执，独立 owner 核对两条 xhigh 输入各一次。该命令新建专用 DEV 对话，不能用于重放已有未知输入。

发送等待恢复：使用专用模拟器运行 `DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer IOS_SIMULATOR_ID=<dedicated-simulator> node scripts/reply-wait-ios-fixture.mjs`，以真实 HTTP 挂起原生发送，覆盖脱离等待、旧响应与新发送重叠、终态失败、记录查询和重启；这是合成回归。`node scripts/composer-state-ios-fixture.mjs` 另覆盖终态／未知快照保留历史轮次 ID、空输入与排队／追加显隐、清空草稿后恢复建议及全文采用按钮的浅／深色展示。真实 DEV 使用同一模拟器设置及 `CODEX_EXECUTABLE=<installed-official-cli> node scripts/reply-wait-desktop-ios-acceptance.mjs`，在专用零 Turn 对话中通过原生 App 向原 Electron owner 各提交一次 xhigh 输入，由本地代理延迟回执并独立核对两次模型输入和原轮次。模型已执行而界面验收未完成时，设置 `REPLY_WAIT_RESUME_REPORT=<original-run-directory>`，只查询同一回执并验证重启恢复，禁止再次提交；原失败记录及其摘要保留。独立 Host、原生副本与证据保存在忽略目录，不替代实体手机或其他电脑的验收。

正文链接回归：`node scripts/message-links-smoke.mjs` 在构建后核对本地路径写法、版本变化、目录及会话边界；`node scripts/attachment-performance-smoke.mjs` 另覆盖真实 HTTP 的当前版本解析、鉴权、未知字段／重复字段拒绝及跨对话、删除与符号链接边界。macOS 的 `node scripts/attachment-download-native-smoke.mjs` 编译真实 Swift 下载函数，通过合成 HTTP 检查旧缓存更新、两次有界解析、旧 Host 兼容、响应目标／来源错配和损坏字节拒绝，已纳入 `pnpm check`。`node scripts/message-links-desktop-ios-acceptance.mjs` 冻结本次 Host／原生源码，创建专用原桌面 xhigh 任务与专用模拟器，验收文件链接 Quick Look、同路径修改／原子替换后直接打开、已缓存附件再次预览、LAN 网页、错误提示及重启恢复，并独立核对原 owner 和手机缓存字节。需完整 Xcode、原桌面与可达 LAN 地址；使用 `MESSAGE_LINKS_ACCEPTANCE_DIR` 恢复同一记录，只查询原操作，不重放模型输入。通过后清理专用模拟器，证据留在忽略目录。

完整 Android 原生版本与 KMP 公共核心的实施见[开发计划](plans/android-native.md)。早期计划阶段尚无 Android 工程、Gradle Wrapper 或安卓验收命令；下述阶段已建立对应工程，计划中的 `pnpm build:android` 等入口建立后再加入本指南的可执行命令表。JS 工作空间继续只使用 pnpm，Android／KMP 使用固定版本 Gradle Wrapper，iOS 保留 Xcode。任一端功能变更需更新[双端清单](product/mobile-parity.md)、[UI 状态](design/mobile-parity.md)和[逐平台验收](acceptance/mobile-parity.md)；KMP 接入后的 iOS 回归是交付必需项。

## 工具版本

本机工具、SDK、模拟器及 USB 真机调试的实际准备记录见[移动开发环境](mobile-development-environment.md)。`pnpm check:mobile-env` 为已实现的工具盘点入口，独立环境样例与产品工程分别记录。

- Node.js：24.x，`.node-version` 记录当前基线 24.19.0。
- pnpm：11.19.0，根 `package.json` 的 `packageManager` 固定版本。
- TypeScript：6.0.3，与当前 ESLint 插件的支持范围匹配。
- iOS：部署目标 iOS 17.0，使用完整 Xcode 与对应 Simulator SDK。当前本机可用 Xcode 27.0。

不同时维护多个包管理器锁文件。依赖版本集中在 `pnpm-workspace.yaml` Catalog，首次安装生成的 `pnpm-lock.yaml` 需随代码提交；CI 使用 frozen lockfile。

## Host、桥接工具与 SKILL 的统一分发

主开发电脑的本仓库是唯一标准。SKILL 的规范源码为 `skills/codex-host-connect`；Host、平台桥接与协议适配的规范源码为 `apps/host-agent`、`packages/codex-adapter` 及对应的 `scripts`。安装到 Codex skills 目录的副本和目标电脑中的随包源码都属于部署副本，不能成为另一套标准。

所有电脑端修复先在本仓库实现。Windows 或另一台 Mac 已经产生的改动，先以不含凭据和运行数据的差异文件回收，逐项审查并合并有效修复，保留已有未提交工作；不能直接覆盖本地目录，也不能在不同电脑继续维护不同实现。平台差异留在统一 Adapter 中，各电脑仅保存自身配置。

统一升级按以下顺序执行：

1. 在本仓库合并源码与 SKILL，更新对应设计、协议和运行文档，执行 `pnpm check`；涉及原生工程时另执行 `pnpm build:ios`。
2. 将通过检查的 Git commit 归档到独立冻结源码目录，确认 Host、Relay、Protocol、Service Runtime、Notification Runtime、Adapter 六个组件的 `dist` 均为空或尚不存在；使用锁定依赖，按 Protocol → Service Runtime → Notification Runtime → Adapter → Host → Relay 顺序，逐组件执行 `pnpm exec tsc --project <组件>/tsconfig.json`。在同一冻结目录以 `TASK_HUB_BUNDLE=<冻结的 Task Hub 目录> node scripts/package-host-connect.mjs` 生成完整接入包与 SHA-256。不得复制开发工作区中被忽略的 `dist` 或复用构建缓存；普通增量构建或干净 Git status 不能证明没有旧编译文件。保存 commit、源码与配置输入摘要及六组件发射文件清单。同一次分发的 macOS 和 Windows 必须使用这一个包，记录包摘要、Adapter 指纹和各目标实际桌面版本。
3. 目标电脑先保存程序、差异与私有配置，核对包摘要后更新稳定程序和 SKILL。保留 Host ID、密钥、项目根目录、Node、服务路径、端口及既有配置。完整接入按 [Trace 流程](../skills/codex-host-connect/references/desktop-suggestions.md) 简要解释手机建议同步与本机保存范围，macOS 和原生 x64 Windows 默认使用各自持久环境工具配置 Trace 及 Host 观察器，不逐项追加记录授权；已有匹配配置复用，用户明确关闭或仅本次诊断时保留。持久环境就绪后由用户择机正常退出并从原图标重开，只有本次诊断才用独立等待器。必要时只重启本产品 Host，不强退原 Codex；当前进程、新 Trace、手机效果与注销恢复分别记证。Windows 实现及本机验收范围见 [Windows Trace](windows-native-trace.md)，不把源码或其他电脑通过写成当前目标全通过。默认核对当前用户登录自启，用户明确关闭时除外。
4. 逐机核对回环与 HTTPS 身份、实际 owner、创建与主动打开，并记录真实原生客户端和模型任务的结果。使用原 Node 运行 `scripts/verify-host-runtime.mjs --entry <正式 Host dist/runtime-entry.js> --bundle <统一包 bundle.json>`，核对实际 ESM 解析到的直接及传递依赖指纹；检查嵌套 `dist/node_modules`，不能把工作区目录摘要当作正式进程已加载证明；工具核对解析到的磁盘文件，变更后仍核对必要重启后的 PID、入口与原环境。客户端可由有 Xcode 的开发电脑配合验收，桌面 owner 和执行证据仍须在各目标独立核对。

冻结目录必须先执行 `pnpm install --frozen-lockfile`，建立自身的 `node_modules`，再编译六组件；不得链接或借用其他工作区的 `node_modules`。若依赖状态核验触发自动安装或共享模块目录重建确认，保留该次失败场景，从新的独立冻结目录重新安装，不得为绕过确认设置 `CI`、修改 `confirmModulesPurge` 或强行通过共享目录核验。

打包器在暂存和生成指纹前，要求五个实际分发的运行组件中，平铺 `dist/*.js` 与当前非声明 `src/*.ts` 的发射文件名完整对应。发现无源码的孤儿或缺失项立即拒绝；保留旧产物，从新的冻结源码、空 `dist` 重建，不静默过滤文件或改写指纹来制造一致。该清单检查不证明同名 JS 字节来自当前源码，仍须执行上述干净冻结构建。`pnpm check:skill` 包含真实打包边界回归：合法发射可生成包，注入无源 JS 后不得生成 ZIP 或摘要文件。

发现指纹不一致时，先核对其他已授权部署的任务、包摘要和源码差异。正在验证的新修复必须保留，不为匹配旧 `bundle.json` 回滚；各现场分别记录实际指纹及归属，统一源码和产物完成后再复核，不能将不同版本写成同包通过。

不分发 `.local`、`.env`、令牌、真实聊天、用户附件或某台电脑的通过证明。旧证明仅在其目标身份、桌面版本和实际运行 Adapter 指纹仍匹配时复用；不匹配时将相应真实验收结果标为待复验；独立桥接能力仍按本机私有配置、实际身份、私有权限和真实运行时就绪判断；Codex 应用版本只记录验收环境，不作为配置或启动条件，不因报告缺失／过期关闭正式新建／打开。部署成功、编译通过、fixture 通过和逐机真实验收分别记录。

首次接入、增加电脑、修复和升级默认完成 [SKILL 完整功能清单](../skills/codex-host-connect/references/full-functions.md)，包括桥接生命周期独立性、项目内/空项目/无项目新建、未加载主动打开和正式手机入口；只有用户明确缩小范围时才交付子集。已实现但未开放的能力先定位修复，不能通过缩小清单排除。普通安装不要求目标电脑具备 Xcode、iPhone 在场或测试报告，正式独立桥接默认省略 `validationOnly`，保留用户明确选择的验证专用配置。安装配置、能力启用与真实 DEV 验收分别记录；各项仍须实际执行并记为本次通过、匹配复用、失败、未验收和未实现，只有所有适用必验项通过且正式能力可用时才报告完整实测通过。仅更新 SKILL/文档不改变运行 Adapter 指纹，应保持程序、私有配置和证明，核对文档摘要并复用匹配证据，不触发无关服务重启或模型任务。

桥接的恢复、路由和创建策略属于本仓库的 Host／Adapter 实现，不由各电脑中的模型聊天执行维护。普通聊天归档不能中断服务；当前控制聊天实现未满足该约束，不能以反归档或发送修复消息完成验收。发布前必须在两个目标及正式 iPhone 重测聊天归档、Host 重启和未知操作恢复。

独立桥接的回复恢复针对本 Host 已知的准确聊天 ID，包含已有历史聊天。显式发送授权连接该目标；只读历史不触发打开。执行命令尚未写出的 owner 失败可以恢复同一原 owner；写出后的超时与未知结果只能查询。服务重启保留原私有配置和程序路径。Windows 权限保护使用随包固定用途工具，安装仍核对实际私有权限；开发验收必须实际验证拒绝宽权限及连续原子保存；不能用反复重启或延长 PowerShell 超时代替状态保存故障的修复。

## 安装与运行

```bash
pnpm install
pnpm dev
```

Turborepo 先构建依赖，再启动共享包编译监听与应用监听。服务默认仅监听本机，端口分别是 Relay 8787、Host Agent 8788。

只启动一个服务及其依赖监听：

```bash
pnpm dev:host
pnpm dev:relay
```

分别复制应用内的配置示例，按需要调整端口：

```bash
cp apps/host-agent/.env.example apps/host-agent/.env
cp apps/relay/.env.example apps/relay/.env
```

这些命令在应用目录启动时会加载其 `.env`。Host Agent 的配置示例包含运行模式、授权目录、状态目录、绑定地址和 TLS；Relay 仍只有绑定地址与端口，不存在配对或内容中继配置。直接运行工作空间的 `dev` 脚本前必须先构建共享依赖；推荐使用上述根命令。

默认构建产物可独立启动健康检查服务；完整运行时使用显式 `CODEX_RUNTIME_MODE`，见 [运行指南](runtime.md)：

```bash
pnpm build
pnpm --filter @codex-manage/host-agent start
pnpm --filter @codex-manage/relay start
```

## 验证命令

Swift 状态回归使用生产声明和随源码版本化的旧算法参考，不依赖内部 Git 历史。先运行 `pnpm build:ios`，再为本任务专用的已启动模拟器设置 `IOS_SIMULATOR_ID=<simulator-id>` 并运行 `pnpm check:ios-state`；结果属于原生辅助边界检查，真机与真实模型验收分别记录。

Git 跟踪核心源码、构建配置、可复用测试与必要设计文档。单次运行日志、验收报告、设备截图、商店审核记录和设计预览留在本地，由 `.gitignore` 排除；`docs/acceptance` 仅保留通用远程接入检查清单。文档中的本地验收路径用于定位当前电脑的证据，不随源码分发，也不代表其他环境已通过。

| 命令                     | 内容                                                                     |
| ------------------------ | ------------------------------------------------------------------------ |
| `pnpm format`            | 格式化 TypeScript、JavaScript、JSON、YAML 和文档                         |
| `pnpm format:check`      | 校验上述格式                                                             |
| `pnpm lint`              | ESLint 静态检查                                                          |
| `pnpm typecheck`         | 按依赖顺序准备类型并检查 TypeScript                                      |
| `pnpm build`             | 按依赖顺序构建 Node.js 应用与共享库                                      |
| `pnpm smoke`             | 构建后启动真实服务进程，验证动态端口、健康响应和不可用的远程路由         |
| `pnpm check`             | 格式、lint、类型、构建与 health smoke、历史及运行时协议边界              |
| `pnpm build:ios`         | 编译并 ad hoc 签名模拟器应用，配置 Keychain entitlements；无需开发团队   |
| `pnpm check:mobile-env`  | 盘点本机 Android／iOS 工具与 SDK；支持 `--android`、`--ios`、`--json`    |
| `pnpm test:ios`          | 运行 XCUITest 原生交互验收；本机历史服务运行时额外验收真实浏览           |
| `pnpm check:history`     | 校验只读 Adapter 的实际 JSONL 边界、分页顺序和断开行为                   |
| `pnpm check:runtime`     | 校验完整运行时协议边界，使用 fixture，不调用模型                         |
| `pnpm check:ios:sidebar` | macOS 原生合成账本基准，核对侧栏未读快照的行查询不依赖文件读取           |
| `pnpm test:live:ios`     | 启动独立真实运行时并验收原生发送、混合附件、控制和多 Host 隔离；调用模型 |
| `pnpm test:live:runtime` | 真实验证空会话重启、稳定目标、附件读取和两步计划事件；调用模型           |
| `pnpm dev:runtime`       | 显式启动真实托管或共享模式，详见运行指南                                 |
| `pnpm dev:history`       | 构建并启动明确标注的本机 Codex 历史连接；只监听回环地址                  |

Smoke 使用操作系统分配的临时端口，退出时停止所启动的进程。它不需要 Codex 安装、模型凭据或真实用户数据，不验证远程管理功能。

## 原生 iOS

置顶列表辅助回归：`DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer IOS_SIMULATOR_ID=<dedicated-simulator> SIDEBAR_REFRESH_TESTS=testSidebarPinnedConversations node scripts/sidebar-refresh-ios-fixture.mjs`，覆盖桌面顺序投影、折叠重启、搜索及取消置顶。真实 DEV：先构建 Host，再以同样的专用模拟器和 `CODEX_EXECUTABLE=<installed-official-cli>` 运行 `node scripts/pinned-receipts-desktop-ios-acceptance.mjs`；它只读当前桌面的实际置顶，在新建的零 Turn 专用对话中由原生 App 提交一次 xhigh 模型任务，独立 owner 核对输入、档位、终态和回执。产物与独立 native 副本保存在忽略的 `.local/pinned-receipts/`。不改用户置顶偏好，不向已有置顶对话发送输入；模拟器不替代正式 iPhone 和远端部署证据。

EasyWork 接收回归使用 `node scripts/easywork-receive-native-smoke.mjs`，在 macOS 编译实际 Swift 差量合并器及 Relay transport，覆盖 Unicode／UTF-16、仅状态变更、完整替换、分支 reset、旧对端、错误基线、临时消息变为持久消息、确认合并、连接关闭和确认失败。可加 `--easywork-root <desktop-checkout>` 使用桌面实际差量生产器；`--baseline-transport <saved-before.swift>` 对照原 Transport 在 250/600 ms 延迟下的批量接收，桌面生产器模式另测真实 AES-GCM 信封的 512/128 Kbps 限速下载。默认合成信封场景使用单文本变更消息，可加 `--rich-wire` 检查推理／正文／工具混合消息。混合消息可返回整条变更消息，仅省略未变化消息，不能把纯文本后缀收益套给全部消息。仅传合成内容，结果在 `.local/easywork-receive-performance/`；Linux 明确报告未执行 Swift，不将其当作原生通过。

真实 EasyWork DEV 使用桌面仓库所需的 Bun 运行时执行 `bun scripts/easywork-receive-desktop-ios-acceptance.mjs --easywork-root <desktop-checkout> --device <dedicated-simulator>`。Agent Hub 的依赖及构建仍使用 pnpm，不增加 Bun 锁文件。入口复用桌面既有隔离 DEV、真实身份导入和只读独立证据收集，通过原生 UI 分别走 LAN/WSS 与 Relay 完成唯一模型输入、消息增量同步、工具结果、完成状态及 App 重开恢复；不能用这一兼容验收声称物理慢网、OIDC 或公网通过。预备登录模式只允许打开隔离实例的正常登录流程，严格身份／模型就绪检查通过后才运行模型测试。

发送与后台同步重叠专项使用专用模拟器运行 `IOS_SIMULATOR_ID=<dedicated-simulator> node scripts/composer-slow-network-ios-fixture.mjs`。该原生 HTTP fixture 将历史响应挂起，核对后台加载不闪提示、不禁用发送、一次点击在历史响应前提交，且已连接对话没有额外打开请求；使用 `COMPOSER_SYNC_BASELINE=1` 与 `IOS_PROJECT_PATH=<saved-before-project>` 可记录优化前的禁用复现。它不执行模型。真实 DEV 先构建 Host，再用 `COMPOSER_SYNC_DEV=1 IOS_SIMULATOR_ID=<dedicated-simulator> CODEX_EXECUTABLE=<installed-official-cli> node scripts/message-receive-desktop-ios-acceptance.mjs`，以普通启动 App、512 Kbps/250 ms 限速，在真实历史读取挂起时单击发送第二条；独立桌面 owner 核对两次 xhigh 输入、各一次执行、完成内容及零额外打开请求。

真机专项先备份现有 App 容器，沿用原 Bundle Identifier、团队和 Keychain 原位更新，再设置 `RECEIVE_PHYSICAL=1`、`RECEIVE_IOS_DEVICE_ID=<physical-device>`、`RECEIVE_ACCEPTANCE_NATIVE_PATH=<frozen-native-project-folder>`、`IOS_DERIVED_DATA_PATH=<signed-device-build>`、`RECEIVE_PUBLIC_ENDPOINT=https://<own-host>:<temporary-https-port>`、`RECEIVE_TAILSCALE_HTTPS_PORT=<unused-port>`、`RECEIVE_KBPS=128`、`RECEIVE_RTT_MS=600` 与上述真实 DEV 参数。HTTPS 端口须未被占用，先认证准确 Host，再启动原生验收；结束时只撤销本次端口并保留原 Serve 配置。真机运行器通过 Xcode 环境接收私有测试参数，控制留在电脑端，不依赖运行器联网；正式 App 实际走 HTTPS 限速通道。电脑在首条完成后挂起下一次历史读取，原生 App 连续采样发送可用性后单击第二条，Host 核对在挂起期间已接收并确认，再释放补拉。独立 owner 核对唯一输入、xhigh 和完成结果；手机移除本次临时连接并恢复原客户端选择。原连接、草稿、待确认操作保持原目标，未知结果不重放。日志、源码摘要、签名产物、数据备份与失败记录保留在忽略目录；该代理实测不代表丢包、蜂窝切换或公网拥塞矩阵已通过。

真机断言失败后先核对原 Host 回执及桌面轮次，不重跑已有输入。若仅连接清理失败，可单独运行 `testPhysicalSlowNetworkRestoreDEV`，向 runner 注入原来的 `SYNC_TEMPORARY_CLIENT_ID` 和 `SYNC_ORIGINAL_CLIENT_ID`；该用例只移除本次连接、恢复原选择，并普通重启核对。保留原失败结果，将发送断言、独立 owner 审计与恢复通过分别关联，不能把恢复重试写成原 XCTest 整体通过。选择当前连接后移除会自动关闭选择器，清理逻辑须兼容这一行为。

消息接收的编码与慢网定位先执行 `pnpm build && node scripts/read-json-smoke.mjs`，再运行 `node scripts/message-receive-performance.mjs`。后者只生成合成对话，经实际 HTTP 响应限速，对照 identity/gzip、20/160 条消息、10 Mbps/512 Kbps/128 Kbps 及 80/250/600 ms 往返延迟；记录首批字节、完整接收、字节数和解码耗时。它不包含 TLS、丢包、模型推理或原生 UI，不当作真实手机端到端结果。结果保存在忽略的 `.local/message-receive-performance/`。

真实 DEV 使用 `DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer IOS_SIMULATOR_ID=<dedicated-simulator> CODEX_EXECUTABLE=<installed-official-cli> node scripts/message-receive-desktop-ios-acceptance.mjs`，必须先构建 Host。脚本准备零 Turn 元数据、确认准备进程退出，由原桌面独占执行原生 App 发出的一次 xhigh 模型任务；独立 IPC 核对真实输入、档位与完成结果。手机流量经 512 Kbps/250 ms 限速代理，记录压缩事件和 UI 首次／最终可见时间；这些 UI 时间来自辅助功能检测，不是逐帧渲染采样。使用全新 Host 状态、模拟器和构建目录，失败报告保留，不重放未知输入；仅元数据准备成功而 owner 尚未就绪时，可通过 `RECEIVE_ACCEPTANCE_THREAD_ID` 继续同一零 Turn 专用目标。该参数对已有模型轮立即拒绝。真实 iPhone、其他 Host、丢包和公网另行验收。

交互卡顿定位后的辅助回归使用 `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer node scripts/reading-index-native-smoke.mjs`，仅生成独立合成键和账本，核对兼容索引及后台合并、扩展锁竞争和迟到旧投影。另运行 `pnpm check:ios:sidebar`；实体采样、代码／产物指纹和真实验证范围见 [交互性能专项](acceptance/ui-interaction-performance-20261014.md)。

离线历史回归使用 `DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer IOS_PHYSICAL_DEVICE_ID=<paired-iPhone> pnpm test:ios:history-scroll`，或将设备参数换成 `IOS_SIMULATOR_ID=<dedicated-simulator>`。脚本冻结源码和执行权限，在独立构建目录与独立真机 Bundle 中执行两项测试：2400 条合成历史的八次实际滑动，以及短列表展开后保持阅读位置、返回最新。检查实际移动采样、最多两次投影构建及 3pt 位置误差；移动帧采样 p95 小于 25ms、最大小于 50ms 的预算仅由物理手机执行，模拟器不作为帧率证据。保存指标、指纹及 xcresult，要求两项实际执行且零失败、零跳过，不能把零测试视为通过。 同名手机不能作为目标身份：脚本按显式设备 ID 启动，并核对 xcresult 每个实际设备配置；缺失或不同 ID 一律失败。GUI 仅作诊断时也须先核对准确 ID，再用相同结果门禁核对，其他设备的通过不得复用。真机副本仅为此测试移除通知能力并使用已缓存的开发签名，不修改正式工程或覆盖正式 App；不连接 Host、不调用模型。另运行 `pnpm check:ios-state`（设置专用模拟器）验证缓存失效边界，以及 `node scripts/history-pagination-ios-fixture.mjs` 验证分页位置、失败重试和晚到页；分页脚本同时核对实现与测试断言的指纹。真实 HTTPS／原桌面读取单列，不用合成真机代替。 联网历史使用 `HISTORY_SCROLL_LIVE_FIXTURE=<private-json> IOS_PHYSICAL_DEVICE_ID=<exact-device-id> pnpm test:ios:history-live`；私有配置须指向已有聊天及 HTTPS，分别运行滑动帧预算和真实更早页插入两项，要求两项通过、零失败／跳过、实际远端消息增长及锚点误差不超过3pt。读取和 UI 操作不提交模型输入。

联网真实历史使用 `DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer IOS_PHYSICAL_DEVICE_ID=<paired-iPhone> HISTORY_SCROLL_LIVE_FIXTURE=<private-json-path> pnpm test:ios:history-live`。配置含已有 HTTPS endpoint、Host／thread 与访问令牌，仅保存在私有文件；脚本通过 Xcode 官方 `TEST_RUNNER_` 环境变量传入运行器，不写入命令行或公开报告。只读打开已有原桌面会话，不提交模型输入；冻结源码、保存本次 CoreDevice 配对／隧道／实际传输方式。验收除八次真实手势及物理帧预算外，还要求真实 Host 更早页使原生消息数量增加、旧阅读锚点保持 3pt 内，并返回最新。读取失败或重复页不能以位置未动冒充通过；零执行、跳过或 XCTest 引导失败均保留为失败。正式版覆盖以本次联网真机通过为前提；普通安装版复核沿用已有连接且不启用测试重置。

新建结果未知时的草稿恢复可运行 `pnpm test:ios:creation-recovery`，使用独立模拟器、构建目录和 HTTP fixture，验证编辑／清空、明确结束等待、后台保存后重启、旧回执晚到及原操作不重复提交。该用例不执行桌面模型，不能替代两台目标电脑和正式 iPhone 的验收。立即强制终止进程发生在草稿防抖保存窗口内时，最新输入的持久化仍未保证；该失败记录与正常后台恢复的通过记录分别保留。

长中文附件文件名专项：先构建 Host，再以专用 `IOS_SIMULATOR_ID`、`CODEX_EXECUTABLE` 和 Xcode 的 `DEVELOPER_DIR` 运行 `node scripts/attachment-filename-desktop-ios-acceptance.mjs`。脚本冻结本次原生与 Host 副本，经系统 Files 选择长中文名 Markdown 附件，新建无项目对话并显式选择 xhigh；校验码仅在文件正文中，独立原 owner 核对真实读取、唯一首条输入、准备进程正常退出及字节摘要。可用 `ATTACHMENT_FILENAME_BASELINE=<修改前保存的 desktop-creation.js>` 先复现旧版“附件清单无效”且没有创建，再在相同原生构建中验收修复版。记录保存在忽略的 `.local/attachment-filename-native/`，不重启共享 Host；模拟器与实体手机、正式部署分别记录。

创建性能改动先运行 `pnpm check:ios:persistence`，用真实 Swift 保存器验证大历史阻塞时的小状态独立落盘、更新合并、目标隔离、写入失败和导入边界。真实手机 benchmark 保留优化前不可覆盖的私有目录及 SHA-256 清单，优化后使用同一设备、Host、项目／无项目、图片字节、推理设置及六组输入；同时记录点击、Host prepared／confirmed、原 owner 的完成时间，分开报告创建确认与模型回复耗时。`node scripts/creation-benchmark.mjs observe --runtime <private-runtime-dir> --output <new-private-after-dir> --prefix PHONE-LATENCY-B` 只观察明确合成标记，不创建或重放任务；界面点击时间写入 `clicks.json` 后，使用 `summarize` 与 `compare` 子命令生成核对和前后对比。真实回执、输入与容器备份仅保留在忽略目录，提交的验收说明不得含凭据或真实对话。专用模拟器避免与其他 XCTest 共用设备造成进程终止；fixture、编译和物理设备结果分别保存。

打开 `apps/ios/CodexManage.xcodeproj`，选择 `CodexManage` Scheme 和 iPhone 模拟器。

如果命令行当前指向 Command Line Tools，但已安装完整 Xcode，可以仅为当前命令指定工具目录：

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer pnpm build:ios
```

构建产物放在仓库忽略的 `.build/ios`，不更改系统全局 Xcode 选择。App 显示名称为 `Codex+`，`Resources/Assets.xcassets` 包含正式启动图标。真机和分发阶段配置开发团队、受控 Bundle Identifier 和签名；当前 `com.codexmanage.ios` 是工程占位标识。更新已安装的 App 时沿用原 Bundle Identifier 与开发团队，以保留沙盒与 Keychain 中的连接配置。

当前 UI 为聊天主界面和左侧抽屉，没有底部页签。默认无设备；“体验界面”提供明确标注的本地示例客户端和回复。系统 PhotosPicker、文件导入、应用沙盒附件、按客户端与对话保存的草稿，以及外观设置已实现。照片异步导入固定绑定选择时的草稿目标。

本机验收：

```bash
# 终端一，需已安装并登录 Codex；不会提交任务
pnpm dev:history
# 终端二
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer pnpm test:ios
```

测试默认选择 `iPhone 17 Pro` 模拟器，可用 `IOS_SIMULATOR_ID` 指定其他已安装的模拟器。结果存入忽略的 `.local/*.xcresult`；测试草稿使用独立的应用存储目录。真实历史测试在本机服务未启动时跳过，必须核对日志中的跳过数量，不能据此宣称真实连接通过。真实连接测试不主动导出历史截图。

对话列表下拉刷新专项可运行 `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer node scripts/sidebar-refresh-ios-fixture.mjs`。它使用本地 HTTP fixture 验证原生顶部 loading、更新后的对话列表、草稿与展开状态保留，以及失败后的缓存保留；另回归侧栏搜索与折叠。此检查不调用模型，也不代表真实桌面运行时验收。

输入性能与草稿保存专项使用 `DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer IOS_SIMULATOR_ID=<dedicated-simulator> pnpm test:ios:input`。独立测试目录生成约 10 MB 历史缓存，测量输入处理的 P95／最大耗时、聊天区域计算次数和主线程写盘次数；同时检查中文多行粘贴、发送、后台与重启恢复、目标隔离、听写草稿和停止／恢复。诊断入口仅在 DEBUG 的专用测试参数下出现。结果、原生源码摘要和日志存入忽略的 `.local/composer-performance/`；该专项使用本地示例，不调用模型。真实原桌面的输入、排队和追加仍使用已有 `pnpm test:desktop:composer:ios` 独立核对。

输入栏任务按钮专项可运行 `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer pnpm test:ios:composer`，可用 `IOS_SIMULATOR_ID` 选择隔离的模拟器。用例覆盖正常完成后的禁用发送、中断／失败／暂停后的恢复、运行中草稿的排队／立即追加、停止后的草稿保留，以及恢复请求的 Host、Thread 与 `next-turn` 路由。状态由本地 HTTP fixture 或界面示例提供，不启动 Codex，也不证明实际桌面暂停接口已经实现。

真实 Codex 输入栏验收使用 `pnpm test:desktop:composer:ios`，需设置 `DESKTOP_ACCEPTANCE_THREAD_ID` 为已在 Codex 加载且空闲的专用验收聊天，并以 `IOS_SIMULATOR_ID` 指定隔离模拟器。普通启动 App 向该聊天提交等待命令、三条排队指令、取消第二条及同轮追加；独立原 owner 快照核对消息只提交一次、取消指令未进入 owner、队列按 FIFO 执行、追加保持同一 Turn，以及完成后的空输入禁用发送。脚本仅创建 Host 缓存与只读 reader，执行始终由已有原 owner 完成。原始记录和 xcresult 留在忽略的 `.local/desktop-composer-acceptance`，不提交真实聊天或密钥。

客户端编辑专项可运行 `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer node scripts/client-edit-ios-fixture.mjs`。本地 HTTP fixture 覆盖编辑入口、取消、离线改名、当前选择与草稿保留、身份不一致和密钥错误拒绝、地址与密钥更新及重启恢复；不访问真实 Codex 运行时。

上下文压缩专项可运行 `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer node scripts/context-compaction-ios-fixture.mjs`。原生用例检查压缩独立于工具折叠、开始与完成、中断、失败、失联与恢复、客户端隔离及重启恢复。`pnpm check` 另检查公开 item 生命周期和桌面 owner 快照/patches 的完成标记映射；这些 fixture 不触发真实任务压缩。

`pnpm dev:history` 通过专用 stdio App Server 读取项目与持久化历史，不调用 resume、start 或 turn 接口，不接管桌面执行实例。稳定 Host ID 保存在忽略的 `.local/host-agent/identity.json`。`CODEX_EXECUTABLE` 可指定 CLI 路径，`HOST_AGENT_STATE_DIR` 可指定持久状态目录。只读开发路径仅提供回环 HTTP。运行时客户端另支持 HTTPS、Keychain、真实发送与附件，配置见 [运行指南](runtime.md)。

模型／推理／权限记忆和速度控制的辅助原生用例为 `node scripts/generation-settings-ios-fixture.mjs`，覆盖新建首条、设置重启保留和已有聊天隔离。真实 DEV 使用 `DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer IOS_SIMULATOR_ID=<dedicated-simulator> node scripts/generation-settings-desktop-ios-acceptance.mjs`（先 `pnpm build`）：建立独立 Host 状态，原桌面执行三次真实输入，独立 owner 核对所选模型、强度、权限及速度开启／关闭。验证配置仅用于此次 DEV，不替代正式接入证明或物理 iPhone 验收。

## 源码推送与合并

开发主仓库为 Gitea 的 `BCAI/agent-hub-app`，本地 `origin` 指向该仓库，主分支为 `main`。默认推送、开发分支和 PR 均以 Gitea 为目标，审查后合并到 Gitea `main`。仓库本地配置使用 `remote.pushDefault=origin` 和 `branch.main.pushRemote=origin`，`main` 跟踪 `origin/main`。

GitHub 的 `easyaigc/agent-hub` 由 Gitea 主线同步工作流更新：仅同步已合并的 `main`，保留独立公开历史。开发／PR 分支不推送到 GitHub，也不在 GitHub 创建开发 PR；GitHub 页面出现建 PR 提示不表示已创建 PR。用户另行指定的公开分发任务按明确授权处理，不改变默认开发流程。

## CI

`.github/workflows/ci.yml` 包含两个独立 Job：

- Linux：安装锁定依赖，先运行 `pnpm build` 生成协议包，再运行完整 `pnpm check`。
- macOS：使用共享 Scheme 编译 iOS Simulator 应用，不要求账户签名。

公开源码仓库为 [easyaigc/agent-hub](https://github.com/easyaigc/agent-hub)，默认分支为 `main`。上述工作流使用 GitHub Actions；完整工作区检查包含 macOS 桌面边界 fixture 与 Swift/KMP 契约，因此在 macOS runner 执行。iOS 模拟器编译单列任务，两项均不宣称 Android 或 Windows 原生验收通过。本地检查通过不代表远程 CI 已执行，远程结果以 Actions 的实际运行记录为准。当前未配置发布任务。首次公开版本采用独立源码快照，不包含内部开发历史或本地私有验收资料。

源码远程仓库为 [BCAI/agent-hub-app](https://git.51easyai.com/BCAI/agent-hub-app.git)，默认分支为 `main`。Gitea Actions 使用仓库专用 Linux 容器与 macOS 原生运行器，配置和故障核对见 [CI 运行指南](ci.md)。分支通过 PR 触发检查，主线推送和手动触发也执行检查；同一分支的新检查取消过期运行。每次验收须核对服务器记录的提交、运行器、Job 结论和日志，本地检查不能替代远程 CI。当前未配置发布任务。

## 后续集成约定

- 接入实现放在 `packages/codex-adapter`；Host Agent 调用领域边界，不在 UI 或 Relay 中复制 RPC 细节。
- 新增网络输入前补齐运行时 Schema、授权与归属校验，不能把 TypeScript 类型当作网络验证。
- Codex 兼容版本与能力记录写入验证文档；不得因为一个接口存在就宣称完整桌面接入已经通过。
- 文档中的实际能力、实验状态和产品范围随实现更新。

计划模式原生专项使用 `DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer node scripts/plan-mode-ios-fixture.mjs`，可通过 `IOS_SIMULATOR_ID` 选择模拟器。覆盖旧工具角色的计划独立展示、桌面模式标签、调整与完整计划执行、手机菜单开关及只读禁用。HTTP fixture 不启动 Codex；`pnpm check` 另外覆盖公开运行时协作模式映射、桌面模型设置继承、旧计划拒绝、去重与创建模式指纹。

模型／推理／权限原生专项：`DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer node scripts/generation-settings-ios-fixture.mjs`，可指定 `IOS_SIMULATOR_ID`。独立 HTTP fixture 不启动 Codex，测试模型切换、受限强度、权限选择、聊天隔离、App 重启草稿恢复与只读禁用，并检查竖屏／横屏下工具栏同排、控件不重叠且触控区域不小于 44 pt。`pnpm check` 覆盖目录分页、无效模型／强度拒绝、计划模式中的模型覆盖、真实 wire 权限字段以及创建操作设置指纹。

通知模块使用 `pnpm check:notifications` 验证契约及持久边界，`pnpm check:notifications:native` 在 macOS 上验证合成音频与 Node/CryptoKit 互通。`pnpm package:notifications` 生成无凭据云端包及 SHA-256；Host 包纳入同一 `notification-runtime`。EasyWork 只接收本仓库构建产物，按其门禁验证，不独立维护通知组件源码。签名迁移、部署与逐项真实验收见 [通知指南](notifications.md) 和 验收清单（本地文件 `docs/acceptance/task-notifications.md`）。

## Host 持久化失败与 Windows worker 恢复

统一包带 `pnpm check:host-lifecycle`（已构建包可直接运行 `node scripts/host-lifecycle-smoke.mjs`）。入口只创建系统临时目录、合成状态和回环随机端口，注入保护步骤超时与发布拒绝，检查旧状态、限次重试、私有文件、自己的临时文件清理、关闭及真实 Node 子进程恢复。Windows 成功写入调用真实原生 ACL helper；它不接触正式 8794、原桌面、配置、身份或模型。M4 结果仅为辅助证据，Windows 必须独立执行并记录 nativeWindowsAcl=true。

正式服务继续使用同一个 `windows-host.json` 和计划任务动作；监督器维护子进程，停止／重启只针对本产品任务。stop/restart 先禁用已核对 owner/action 的任务，保留准确安装与配置的进程句柄，验证 SID、创建时间及父子关系后清理本产品实例树；等待子进程收尾最多五秒，残留进程按句柄终止，清理失败不继续 restart。进程查询按 Node 名称和父 PID 限定，每批最多 16 个父 PID，已核对身份不重复查询；PS stop/restart 总期限 45 秒，Node 外层 60 秒，其他动作 20/35 秒；CIM 单次等待最多五秒、强制退出共享等待最多两秒，均受总期限约束。控制结果提供实际 elapsedMs、查询和 owner 计数，超时明确失败，即使事后端口已消失也不宣称命令成功。不要用任务 Ready 代替子进程退出证明，也不要按 Node 名称或端口批量终止进程。不要手动并行启动 worker。`service:windows status` 返回 lastTaskResult、lastRunTime、restartCount 和 restartInterval；任务 Running 只表示监督器存在，须另核对 8794、HTTPS、Host ID 与 desktop-runtime。私有日志位于同配置目录的 `worker-diagnostics/worker.jsonl` 和 `worker.previous.jsonl`，保留当前用户私有 ACL，不纳入接入包。原始未知异常、Task Scheduler 事件和部署证据分别保存，不能从 RestartCount=3 推断实际三次失败。Microsoft MS-TSCH 的 [RestartOnFailure](https://learn.microsoft.com/en-us/openspecs/windows_protocols/ms-tsch/2ff4aa5a-7bc4-449f-bbb1-27475645867f) 描述启动条件不满足或 action 启动失败的重试，不能据此推断任意运行后退出均会重启。worker 只覆盖其自身仍存活时的 child 退出；2026-10-18 新增下述同任务周期触发，覆盖外层 supervisor 结束后的补启。

## iOS 中英文文案

已实现简体中文和英文。默认依次匹配系统首选语言中的中文或英文，其他语言回退英文；设置中的“语言”可选择跟随系统、中文或 English，立即生效并保存到本机。更新文案不重建 RootView 的状态身份，已有草稿、附件、当前目标及不可变操作保持原样。项目名、用户消息、模型输出、远端提供的正文和原操作回执不作为翻译键。

`apps/ios/Sources/Localization.swift` 统一解析语言、选择资源包和处理插值。界面、错误提示及通知类型使用 `L10n.tr("文案")`；插值参数独立传入，文件名中的百分号、中文或格式标记不会被二次解释。中英文 `Localizable.strings` 位于 `apps/ios/Resources/{zh-Hans,en}.lproj`，以稳定的中文原文为键；新增条目须同时补齐两份资源并保留插值个数。SwiftUI 界面通过 AppStorage 观察语言变更，根视图提供对应 Locale。已有枚举的 Codable 原始值保持兼容，显示标签使用独立的 title。

主 App 将语言选择同步到 App Group，通知服务扩展和内容扩展各自打包同一资源。扩展新生成的类型与默认摘要使用所选语言；已收到的通知、服务端标题和用户摘要不改写。`InfoPlist.strings` 另提供相机、局域网、麦克风和语音识别的中英文说明；系统权限弹窗按 iOS 的应用语言显示。 国际发行的资源缺省语言为英文；App 内隐私与帮助链接随所选界面语言切换到对应中英文页面，外链切换不修改连接、草稿或已提交操作。

在 macOS 运行 `pnpm check:ios:localization`，检查资源完整性、格式参数、真实 Foundation 资源查找、语言切换、通知展示及原始插值内容。`LocalizationUITests` 覆盖系统语言匹配、设置实时切换、重启保存及草稿保留；既有中文 UI 用例显式指定自己的语言，产品普通启动仍跟随系统。带独立 Host 的真实桌面专项为 `testOriginalDesktopLanguageSwitchDEV`，需要测试 runner 注入私有 `LANGUAGE_DEV_FIXTURE`，未提供时明确跳过。编译、辅助检查、模拟器、真实桌面模型任务与物理 iPhone 验收分别记证，模拟器通过不代表真机或后台 APNs 已验收。

## iOS 本机备份与锁屏回归

修改会话恢复、Keychain、角标账本或发现策略时，执行 `pnpm check:ios:persistence`、`pnpm check:unread-badge`、`pnpm check:codex-discovery`、`pnpm check:ios:localization`、`pnpm check` 和 `pnpm build:ios`。真实设备须使用普通签名启动，分别核对锁屏保持、后台终止后冷启动、锁屏后台启动及蜂窝 VPN 发现；调试器、模拟器和正常前台重开不能替代这些场景。

恢复前保存完整 App 容器、可取得的通知组数据、故障报告及输入摘要。覆盖更新保留原 Bundle、团队、Keychain 和设备身份，不卸载或重新配对。确认准确来源设备后，停止该 App，保留当前状态字节，再安装同设备的原加密状态；先核对传输前后 SHA-256，正常启动由原钥匙串解密。核对 `session-load-report.json` 中的输入摘要、连接／草稿数量、草稿摘要及历史索引；该报告不含正文、地址、目标或凭据。不可读时维持暂停写入，不能生成替代密钥或通过空状态继续。

恢复只能覆盖仍存在的备份及缓存范围。备份之后新增、且未留在其他文件中的未发送草稿不能凭空重建；必须单列这一限制。原操作恢复只查询同一回执，不自动重发。现场、原备份、恢复后备份、签名产物及每项实际结果均保存在私有目录并保留清单；不提交真实数据或手机凭据。

## Windows Host 异常恢复边界（2026-10-17）

worker 每次启动 Host 子进程前重新核对真实管道 owner、当前用户／会话、可信 OpenAI 发布者及 IPC archive profile，然后发现官方 CLI。候选包括 owner 旁的 resources/bin 布局与当前用户官方 `OpenAI/Codex/bin/<digest>/codex.exe`；CLI 单独核对签名或同 owner 的 Store 包来源，拒绝重解析路径。没有仍有效的已配置可信 CLI，且多个不同可信 loose CLI 无法唯一确定时报告 `WINDOWS_CLI_AMBIGUOUS`，不按修改时间猜测。版本只记录元数据。通过后仅原子更新桌面 executable/archive/version 和 Host 的 CLI/version，保留其余私有配置；不修改 Host ID、密钥、prepared bridge、授权 root、任务配置、官方安装或历史，不重启原桌面。

PS5 helper、pipe bridge、原桌面 URI 打开及任务管理的子进程环境剔除大小写任意的 `PSModulePath`，由 PS5 重建模块路径，父进程与用户全局环境不变。安全 helper 显式导入系统 Security/Utility/Management 模块，模块加载失败报告 `WINDOWS_POWERSHELL_MODULE_UNAVAILABLE`，不归类为签名或 DACL 不可信。参考[微软模块路径说明](https://learn.microsoft.com/powershell/module/microsoft.powershell.core/about/about_psmodulepath)。

后台持久化每轮限次退避，耗尽后冷却一分钟并主动继续，业务事件不会重置本轮预算；历史缓存逐 Thread 调度，冷却项不阻塞其他项。仅重试保存最新内存状态，不执行模型命令或重发未知输入。关闭取消定时器及 continuation，并仅执行既有最终 flush。命令提交仍须立即满足原持久化边界；Windows 目录 flush 未证明的限制保持明确。

监督器只在确认进程退出后自动拉起。另每 30 秒以 5 秒超时读取本机鉴权 health，在状态变化时记录 `healthy`、`http-unhealthy` 或 `persistence-degraded`；HTTP 失败不会杀死活进程。原桌面未运行、配置需要刷新、模块故障、配置已刷新和保存降级由固定诊断码区分。任务调度的 running/LastTaskResult 只是计划任务状态；不能替代 Host HTTP 或原桌面就绪证明。桌面暂未运行时子进程按既有有界退避等待下一启动核对；运行中的 owner/CLI 更新不会触发主动重启 Host。需保留活跃工作，等待自然退出或用户授权重启。

辅助检查：`pnpm check` 包含 `host-lifecycle-smoke.mjs`（无后续业务事件的 ENOSPC 冷却恢复／关闭）、`windows-host-recovery-smoke.mjs`（可信 API fixture、摘要路径、配置保留、真实隔离 HTTP 子进程超时与恢复）、`windows-powershell-smoke.mjs`（子环境隔离；在 Windows 上实际运行 PS5 签名和 ACL 命令）。macOS 执行这些检查不能证明 Windows 真实模块、签名、DACL、正式任务或手机链路已通过。

Windows 本机验收应先保存本机配置与日志并核对统一包 SHA-256，在隔离目录执行上述检查与 `windows-private-state-acceptance.mjs`，分别从 PS7 和 PS5 启动核对。通过 `service:windows preflight` 只读核对当前真实 owner、CLI 和协议。配置漂移的自动刷新与正式服务恢复须在本机按授权升级后独立记录；不为探测停止正式聊天，不重放未知操作。部署、真实 Electron／手机／模型验收及未覆盖项分别列证，不能用进程崩溃恢复代替全部自愈场景。

## 原建议日常 Host 升级

2026-10-18 增加漏通知补读。`desktop-prediction-trace.test.mjs` 覆盖没有通知的原生来源恢复、完整完成消息一致性、来源多候选、伪造标记／越界 cwd／不完整尾部，以及无关大日志追加、目录正常新增内容与前缀被改写；`desktop-suggestion-observer.test.mjs` 覆盖去重、原有效期、冲突隔离及扫描失败不降级。目录身份核对路径、设备、inode、所有者和权限，不以随正常写入变化的目录链接数判定身份；普通文件仍拒绝硬链接。两个文件可用 `node --test --test-concurrency=1` 顺序运行以隔离权限与文件竞态回归。真实恢复须保留漏采时的原生证据和旧通知目录，核对最新完成来源及手机全文，不靠改时间、补造通知或重新发送原输入制造通过。

建议启用与正常客户端退出流程见 [SKILL 建议参考](../skills/codex-host-connect/references/desktop-suggestions.md)。macOS 与 Windows 服务将 `CODEX_DESKTOP_SUGGESTION_OBSERVER` 与 `CODEX_DESKTOP_SUGGESTION_TRACE_ROOT` 持久保存，并保留已有 Node、程序和工作目录；显式 `HOST_AGENT_STATE_DIR` 保留外置私有状态。原 Codex 的 `CODEX_ROLLOUT_TRACE_ROOT` 必须在原客户端启动时继承，不能由 Host 重启动态开启。默认使用各平台持久环境工具，让后续正常图标启动继承设置；一次性诊断环境不等于持久配置。Windows 另核对注册表、启动器及准确原主进程／直属执行器，见 [Windows 安装](../skills/codex-host-connect/references/windows.md#trace-与下一轮建议)。

原有连接专项沿用 `scripts/desktop-suggestions-ios-acceptance.mjs`，设置 `SUGGESTION_EXISTING_CONNECTION=1`、`SUGGESTION_VERIFY_ADOPTION=1`、准确 `SUGGESTION_ORIGINAL_CLIENT_ID`、本机既有 `SUGGESTION_HOST_STATE_DIRECTORY` 和实际 `SUGGESTION_DEPLOYED_PROGRAM_ROOT`。来源证明须在该日常 Host 实际采集的新建议上产生，不能移植另一 Host 观察记录。该模式不启动第二个 Host，不配置 Serve，不向 App 新增、修改或删除连接，runner 不把令牌写入连接表单。它核对实际部署程序摘要，通过私有登记中的既有 HTTPS 访问正常服务，在普通原生 App 的原有连接实测四组交互，并检查完整连接集合与选择未变。此模式没有命令阻断代理，零新增输入以准确原 owner 前后核对和实际 UI 操作记录确认，不写成代理阻断证明。

## Windows 外层 supervisor 周期恢复（2026-10-18）

已实现同一个 `CodexManage Host Agent` 任务的双触发：当前用户登录触发，以及从配置后一分钟开始、每分钟重复的 TimeTrigger。重复不设 Duration 或 EndBoundary，StopAtDurationEnd=false；[微软 Duration 说明](https://learn.microsoft.com/windows/win32/taskschd/taskschedulerschema-duration-repetitiontype-element)定义未设时无限重复。任务仍为当前用户 Interactive/Limited，动作、Node/worker/config 与工作目录保持不变；沿用 MultipleInstances=IgnoreNew，[运行中忽略新触发](https://learn.microsoft.com/windows/win32/taskschd/taskschedulerschema-multipleinstancespolicy-settingstype-element)，不排队、不终止现有实例，不启动另一类执行器。保留原 RestartCount=3／每分钟失败重试，但不把它当作外层存活保证。

`start`／`restart` 同时登记两种触发，因此不会再把这项周期补救覆盖成仅登录；已运行时 start 只更新任务设置。`stop`／restart 的收尾首先禁用整个准确身份任务，登录和时间触发一并停止；只有显式 start／restart 才重新启用。原子保存、启动路径刷新及未知输入不重放保持原行为。活进程 HTTP 不健康仍只有诊断，周期触发不会杀死活进程；未登录、电脑休眠、Task Scheduler 停止／禁用、计划任务被删除或用户显式关闭不在“一分钟必恢复”的承诺内。

状态新增 `outerRecoveryPolicy=periodic-trigger-ignore-running`、实际核对后的 `periodicRecovery`／`periodicRecoveryInterval` 及调度器 `nextRunTime`。periodicRecovery=true 需要任务启用、有效无期限每分钟 TimeTrigger 和 IgnoreNew；旧登录-only、禁用、有限期、会结束运行、错误间隔或并行策略均返回 false。它只证明配置，不证明下一次触发实际执行、HTTP 健康或桌面 owner 就绪；running 与旧 recoveryPolicy 继续分别表示任务状态及 child 监督策略。

辅助回归：`windows-control-smoke.mjs` 的 PowerShell fixture 新增 `outer-recovery`，执行实际产品任务脚本并模拟调度器：Ready／3221225786（0xC000013A）后周期补启、Running 不重复、stop 后不补启、再次 start／restart 保持两触发及原动作／用户、错误设置不冒充有效恢复。在 Mac 可通过 `DESKTOP_TASK_FIXTURE_PWSH=<isolated-pwsh>` 执行；这些是模拟 Task Scheduler 的行为证据，不能代替 Windows 时间调度实测。

Windows 真实验收条件（由目标本机负责人执行并绑定本次包 SHA）：

1. 使用本机原有隔离合成任务／脚本，准确 `process.exit(-1073741510)`，记录首轮 LastTaskResult=3221225786、Ready、Enabled=true 和已无该合成实例；不停止正式 Host、原桌面或聊天。
2. 核对无期限每分钟触发的实际任务 XML、下一次触发时间，以及下一次真实启动的新 PID／脚本启动计数和时间；等待下一次触发后确有运行，不能用 Register/Set 成功、手动 start 或 child 崩溃恢复替代。Windows 正在运行的既有 audit 可继续同一证据链，不重放未知模型操作。
3. 合成脚本下一轮保持存活，跨至少两次周期仍为同一 PID、计数不增加；显式 stop 禁用后跨至少两次周期无重启；再次 start／restart 后保留两触发、原 SID／路径／配置、IgnoreNew。仅测试合成任务的退出；不为验收杀正式工作。
4. 正式任务的设置补齐后，分别核对 status、回环、原 Tailscale HTTPS／直连身份、Host ID、desktop-runtime、原 owner 和已提交回执；正式恢复、注销／重启、手机／模型效果单列。Mac 辅助检查不将这些未执行项记为通过。

2026-10-18 来源 Windows 的报告表明前一包已加载、正常到健康日志后外层与 child 均消失，而 0xC000013A 未触发原失败重启；尚无结束来源证据。不得归因于用户关窗、磁盘满或旧包未部署。该事件暴露了此前没有验收的外层恢复边界，本次补启策略不等同于确认最初退出原因。

## 受控弱网读取验收（2026-10-21）

用户已将本次弱网方式改为可控网络故障，并授权 Windows 部署及进一步验证。`pnpm dev:weak-network-read-proxy` 用私有连接清单中的明确 `WEAK_NETWORK_HOST_ID` 和 `WEAK_NETWORK_THREAD_ID` 建立只读 HTTPS 上游；它只在回环监听，测试 App 使用临时代理凭据，真实 Host 密钥不会交给 App 或写入结果。必须先核对授权和 Host/runtime/原 owner，不能把这条入口用作跨电脑任务分派。

代理在全部并发响应之间共享 1024/128/64 Kbit/s 带宽，分别加入 120/600/1000 ms RTT 和 30/150/250 ms 可重复抖动；控制端可注入单历史读取挂起、真实响应截断及全通道 503，再恢复原链接。真实 `Accept-Encoding` 和上游 `Content-Encoding` 保留，按实收编码字节限速，解码后只用于完整目标验证；不能把未协商压缩的 JSON 长度视为手机实际传输量。该方案不模拟无线电唤醒、TCP 丢包重传或 TLS 握手。

`pnpm check:weak-network-proxy` 验证聚合限速、目标与方法拒绝、gzip 字节与完整解码、上游身份错误和断流恢复。原生入口为 `testWeakNetworkRealWindowsFirstHistory`、`testWeakNetworkRealWindowsTimeoutRecovery`；编译后在独立设备/模拟器执行，按实际环境分别保存结果。真实 Windows 内容、受控网络、原生界面、物理手机和正式版本更新是不同证据；上传统一包等并发任务造成的上游等待另行记录，最终耗时在部署完成后重测。见[本次范围](acceptance/mobile-evidence/controlled-weak-network-20261021.json)。

Windows 实机部署若因 `GetOwnerSid` 的 WMI 提供程序延迟而触发 `HOST_PROCESS_OWNER_QUERY_FAILED` 或 45 秒停服预算，使用规范仓库的句柄查询修复：从已保留、稍后用于终止的同一进程句柄以 `TOKEN_QUERY` 读取 SID；仍检查 PID、启动时间、映像、命令行及当前用户，拒绝查询失败，不扩大超时、不启用调试权限。参见 [OpenProcessToken](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-openprocesstoken)。在 Windows 上先运行 `powershell -NoProfile -File scripts/windows-host-owner-native.ps1 -ScriptPath scripts/windows-host-task.ps1`，记录冷启动、100 次原生查询、无效句柄拒绝及句柄泄漏检查。随后运行生命周期 fixture，再核对实际服务停止、重启、登录自启及真实加载路径；fixture 不能代替逐机结果。

物理 iPhone 的限速计时可在独立验收 bundle 上显式设置 `AGENT_HUB_READ_ACCEPTANCE_HOST` 与唯一 UUID `AGENT_HUB_READ_ACCEPTANCE_RUN`。DEBUG 仅在该 bundle 记录最多 256 条连接、真实历史响应和数据应用的单调时间元数据到私有 Caches；计时期间切离设备屏幕预览，随后再读取记录和核对实际界面，避免无线调试画面占用被限速的链路。数据应用时间不冒充像素出现时间；正式 bundle 不启用此记录。

停服身份重验按最多 16 个 PID 合并请求：先打开并保留各自进程句柄，再查询本批新元数据，逐项比较创建时间、路径、命令行、句柄启动时间和所属用户。重复扫描已核对进程不重开句柄；失败与退出路径均释放尚未移交的句柄。宽子树、批量身份漂移、拒绝时零进程终止与句柄释放由生命周期 fixture 覆盖，总停服预算仍为 45 秒。

原生受控弱网目录保留全部真实条目，只将限定验收聊天排在首位，防止新连接自动打开未授权读取的聊天；目录因此重新编码，历史与事件仍保留原始压缩字节。单历史停顿必须先确认实际请求已消耗故障，再测客户端截止，不能从一次被合并的刷新点击开始推断。物理真机使用系统3G配置与普通App路径，屏幕预览流量移出计时间隔；它不能称为实际蜂窝验收。

P3共享核心核对支持 `MOBILE_PHASE=P3`（兼容原A/UM阶段）；`MOBILE_CORE_ACCEPTANCE_OUTPUT`可指定工作树外的私有输出目录，未指定时保留既有默认。`IOS_SIMULATOR_ID`仍须明确指定本任务模拟器。`pnpm check:mobile-core`运行KMP的JVM/iOS Simulator Arm64、框架构建及Swift调用向量；原报告选择的suite计数与实际全部Gradle XML可能不同，统计时逐份核对并保存XML，不能把选择子集当作全部或重复加总。同平台辅助与真实设备/原Electron结果分别记录。

2026-10-23 P3-3验证：check:mobile-core读取实际12组XML，每运行时58项；state-v1新增17条chatSearch向量，真实Foundation/ICU适配分别核对，既有向量保留。Android原生MobileChatSearchRulesTest/SessionTest/UiTest按2+6+5分别执行，同APK/runner三环境39项/9组；Dialog自身返回回调与系统按键注入分开，真机INJECT_EVENTS拒绝不以提权补齐。iOS scripts/chat-search-ios-fixture.mjs支持CHAT_SEARCH_ACCEPTANCE_OUTPUT树外证据目录；CHAT_MENU_FIXTURE=0仅执行完整分页查找场景，具名菜单动作未运行，零真实模型。所有原失败/中间包保存，最终源码/包/报告/36帧指纹见P3脱敏证据；完整P3/G4不由这些辅助结果代替。

### P3-4 聊天管理辅助验证

Android 使用独立验收 applicationId 与锁定 Wrapper，依次运行 `MobileChatActionFlowTest`、`MobileChatActionSessionTest`、`MobileChatActionUiTest`，并回归现有完整查找规则、Session 和工具/压缩定位。真实手机与 API26/API37 分别登记相同 APK/runner 指纹；合成 HTTP 动作不计真实模型或原桌面写入证明。公共层使用 `pnpm check:mobile-core` 的 JVM/iOSSimulatorArm64 XML 和实际 Swift 桥接向量，不能只看 Gradle 成功字样。iOS `CHAT_MENU_FIXTURE=refresh|catalog|1|0` 分别覆盖手动刷新并发、确认后目录归属、管理菜单、完整查找；绑定专用模拟器和树外证据，运行期间不得修改受指纹保护的源码。

2026-10-23 P3-5a：Android 对话菜单加入已归档对话入口、原目标只读打开、生命周期与最后任务状态、空态、刷新/分页及失败保留/重试；恢复仍复用具名聊天操作并保留独立草稿。列表读取不依赖管理 capability，不新发模型输入。iOS 本子项源码未改；Android 显式分页/离线刷新入口及原生页面样式差异登记至 P3-6，未宣称双端完整视觉或真实 DEV。批量归档仍留 P3-5b，项目管理/任务中心暂缓，A6/P7 暂停。 原生检查只访问本机合成HTTP fixture并使用独立验收bundle；没有真实模型输入，没有操作原Electron/正式手机数据。

P3-5a 补充只读边界：合成原生反例分别复现归档且运行时仍可控时的设置/停止门禁差异。Android按iOS既有门禁约束执行、队列取消、审批/问题及设置；保留真实任务运行状态和独立恢复操作。原API26合成HTTP服务关闭竞争另作验收工具失败登记，未当成产品失败或通过。最终产物与分组结果另列，P3-5b及P3-6全矩阵仍开。

2026-10-23 P3-5b：Android新增仅聊天的批量归档页面：按当前客户端/项目及聊天搜索、选择/取消全选、最多100条、确认/取消、加载更多和逐项成功/拒绝/待确认及原ID查询。根目标加密journal在具名POST前保存不可变原意图/标签和提交标记，未知及404不重放；逐项终态保留，部分进度不覆盖其他项目，切换/重启保留草稿与原回执。KMP统一精确请求/目标/有序ID、逐项终态合并和限定选择，Swift归档路径迁移至共享规则；管理提交后的目标复核和旧目录owner隔离同步。项目删除/新项目管理及Task Hub保持暂缓，A6/P7继续暂停。 根pnpm包装原生锁定Gradle；schema分块避免JVM常量上限。原生测试使用本机合成HTTP和独立bundle，失败原件保留，源码/包/runner/环境指纹分别核对。

2026-10-23 P3-6a 菜单与原记录辨识：Android按iOS顺序显示置顶、聊天查找、归档/恢复、删除，再归档列表和待核对记录；使用既有Canvas向量系统补pin/unpin/archive/trash图标，保留禁用/删除颜色、准确目标和警告。双端每条待核对聊天操作显示动作、原ID片段和准确聊天标题/无目录时原threadId，查询及结束等待保持原完整ID；Android语义说明含完整对话/操作ID，iOS保留原稳定标识并补标签/值。没有提交/恢复协议、模型输入、未读账本或任务/项目管理改动。 原生验收分别冻结平台实际编译源码、产品/runner及fixture；系统菜单ID丢失使用准确文案作定位补充，查询目标仍以服务端原ID独立核对。

2026-10-23 P3-6b：Android客户端列表新增进行中数量与未知占位及语义说明；KMP统一在线/快照/history-only资格、准确Host、运行中及等待审批的ID去重，归档生命周期独立。Swift原独立计数路径迁移至共享规则，保留demo合并来源、未来模式与归档仍运行的现有行为。未读账本/数量与A6/P7仍暂停；任务/项目管理暂缓。 计数只消费当前已保存且机器隔离的快照，不额外触发联网或授权；无需为展示计数恢复暂停的未读账本。

2026-10-23 P3-6c：共享准确目录目标/归档/单条发送中及待核对/批量待核对的归档选择资格。iOS补齐单条与批量原操作互斥，并在持久化后发送前复核原目标、当前目录和冲突；Android保留既有互斥并迁移选择规则到KMP。查询保留原ID，其他聊天和独立草稿不受未知结果影响；项目管理与未读暂停范围不变。 在保存不可变批量意图后复核目标/目录及原冲突；自有operationId仅免除对自己的批量冲突，不免除其他未解决操作。

2026-10-23 P3-6d：iOS归档选择按全部仍合资格的原ID清理，搜索隐藏但仍有效的选择保留；底部提交即时核对每个所选ID，不等待清理effect。批量原记录取消固定10条截断，显示完整不可变ID并保留每条原查询。Android生产路径已有相同行为，本项仅新增原生验收，最终产品APK字节与a95检查点完全相同。项目动作、任务中心及未读暂停范围不变。 用户可编辑选择与已提交不可变批量意图分开；资格变化立即禁发并清选择，不改原ID/标签、取消远端或重放。

2026-10-23 P4-1：Android本地初始读取/恢复先锁定同目录全部写入和删除；完整解码普通元数据与私有连接合并后才发布状态并解锁。失败保持加密原件，后续外观/草稿变化不覆盖；跨实例及旧异步保存代次受同一保护。恢复前不授予编辑/发送/管理能力，显示存储警告、原数据保留说明和重试，重复重试合并；成功后恢复原目标、草稿、设置与正常保存。iOS既有恢复保护未改，Android不提示用户反复解锁。没有自动清空、迁移原输入、重放或取消远端。 独立验收仅创建唯一namespace/keyAlias的合成加密文件；受保护文件只由测试修复夹具恢复，产品不会覆盖失败原件。完整系统生命周期与真实DEV分别保留。

2026-10-23 P4-2a：原生回执补齐五个可选队列字段，HTTP、加密历史、原记录编码和EasyWork归一化完整保留。Schema只为已存在字段补充nullable严格类型，保留未来字段兼容。KMP统一原客户端队列归属、存在性及同步错误判断，Host/legacy-review与已移除项不列活动队列，不建立本地FIFO、不迁移或重发。已接受后的同步错误不被后续成功或普通失败关闭抹掉；源端清除才消失，独立草稿保留。iOS改调同一规则并限定准确host/thread；立即执行/取消门禁和行样式仍留P4-2b。 EasyWork normalization is a native boundary check, not full EWDAP or real-model proof.

2026-10-23 P4-2a.1：继续逐路径复核后补齐EasyWork发送返回、command/status与queue/status转换；此前P4-2a封闭的是历史/加密重开/回执编解码及归属提示子边界。原状态和原错误优先于外层RPC完成；runId优先turnId，只有发送可用原expectedTurn回退，原查询不借当前Run。已接受但仍带queueSource的结果也只回查原queue/status；原ID/Host/thread保持捕获范围。五队列字段、noticeContext及laterTurnId完整保留，已知字段错型拒绝。会话解析改用窄EasyWorkChannels接口，默认真实会话/凭据/传输不变；实际原生适配器与加密会话经独立Peer夹具验证，没有新增执行控件、HostFIFO、重放或真实模型。 Native in-process encrypted Peer proves adapter/session/correlation, not real WSS/HTTP network, device authorization, original Desktop or model execution.

2026-10-23 P4-2b.1：Android新增具名executeQueue，经AgentRegistry分别调用固定HTTP v1/queue/execute或EasyWork queue/execute；保留原消息ID，当前任务只映射expectedTurnId/expectedRunId，空闲时省略，不发正文/设置/附件或自建FIFO。HTTP完整运行目标仍绑定URL查询，严格请求Schema拒绝正文target、重发正文与null/空白任务ID。EasyWork只把submitted/completed视为执行确认，取消只接受真实Boolean true。队列执行加入内部写白名单，沿用未知写先查原command/status并核对操作ID/方法/目标/指纹、绝不重放。会话/UI尚未接入立即执行按钮，原取消门禁与大卡片差异保留P4-2b.2；iOS既有具名调用不改，共享HTTP契约新增跨语言向量，独立验收。

2026-10-23 P4-2b.2：KMP统一原客户端队列动作的归属/存在性、在线可写、逐项等待及执行capability/canExecuteNow判断；双端只对捕获完整目标与当前原队列成员操作，执行再核对捕获Turn/Run。等待按完整目标/原消息/owner隔离，不锁整份草稿；导航/连接代次、端点和错误代次隔离晚到结果及错误，未知只刷新原历史不重放。共享许可确认不自动续发。Android改为原顺序紧凑行、编号、两行摘要/附件指令、44dp动作、立即执行/追加、逐项等待和180dp有界滚动；使用项目表面色。iOS原生场景暴露旧HTTP队列调用缺少完整target查询，已由捕获readingClient与具名队列query补齐；HostService只增加显式可选query，其他调用不变。提示位置留P4-4，队列外容器、主题/键盘及完整视觉/真实DEV仍开放。

2026-10-23 P4-3a：共享ReplyRecoveryRules区分原客户端接受、等待释放与终态保护；received/未知/旧Host或已移除queued不作为接受证据。Android准确核对原回执Host/thread/ID/完整目标后，只释放同一原操作当前持有的等待，不清新草稿、不释放新操作；原HTTP未返回也可由已接受/终态回执解除本次等待。received保留原草稿，显式结束等待保留原意图；失败回执保留正文。Native加密journal及iOS分离记录共同保护终态不被晚到非终态确认/超时回退，iOS删除对应旧字面规则，改调同一KMP判断。原生夹具的v2 accepted检查补齐既有canonical可空字段；新增可选树外输出目录。404重试/有界退避、整组输入记录UI、生成设置/真实权限/冷恢复/真DEV仍开放。

补充保护已接受的queued/submitted不被晚到received/未知/超时降级；相同v2产品APK的新持久化反例三环境各1项失败后，原journal/双端共享合并规则补齐，原密文重开、正文和指纹保持。

本地传输phase与最近远端回执分别保存；received后HTTP结果未知是合法组合，不以状态相等作为目标身份校验，Host/thread/ID及完整目标检查保留。

2026-10-23 P4-3b/O048：Android原回复观察独立于历史与事件，暂缺404继续只读查询；共享KMP定义2/4/8/16/30秒上限退避，iOS删除对应字面计算并调用同一规则。原canonical accepted证据允许在先前uncertain回执或重试额度已消耗时读取，仍核对捕获目标/信任/原ID；负证据取得重试权限的原限制不变，既有回执或已消耗额度不获得再次写权限。没有自动发送、重放或更换原输入。分离记录的canonical回查、整组输入记录UI、生成设置/真实权限、冷恢复及完整真实DEV继续开放。

2026-10-23 P4-3c/O036/O048：Android把原回复只读观察的捕获目标/证书/不可变意图校验与重试写权限分开；结束本机等待后，仍可具名查询原canonical accepted回执并保留detached状态。负证据与原内容恢复/重试继续使用未分离的原写授权校验，分离、额度已消耗或已有回执均不能新增重试权限。目标、证书或原意图改变仍拒绝备用查询，不迁移原记录、不恢复等待、不重放。iOS既有分离记录查询与KMP规则未改，源码指纹匹配复用前项辅助结果。发送记录列表入口与正文/附件展示仍待下一检查点，完整真实DEV未关闭。

2026-10-23 P4-4a（P4-3续接）：Android对话菜单新增与iOS对应的“发送记录”，按完整目标显示全部已结束等待的原回复，倒序可滚动且不截断。展示可选取的原文、原附件名/类型、原回执状态及错误；旧附件元数据缺失明确说明原记录保留。仅查询准确原ID，逐记录等待/错误与去重，不锁当前草稿、不提供重试/重发。导航、连接、端点、证书及owner隔离晚到错误；目标改变关闭旧列表。只读快照核对加密原记录、来源目标/草稿版本及规范化原正文/设置，不读取或上传附件字节、不为旧记录补造重试授权。原内容编辑复用快照校验，仍先取得强未接受及未分离授权并复核原字节。iOS现有发送记录列表/查询路径保持，KMP规则未变；全视觉/多语言/真实桌面模型DEV及其余P4继续开放。

2026-10-23 P4-3d/O083/O090/O091/O092/O093：Android运行中输入栏改为与iOS同义的“排队执行／立即追加”分段选择，排队选项保留且能力缺失时禁用；原选择为queue但客户端队列不可用时显示同文说明，保留草稿，不自动改为steer或发送。继续调用同一KMP showsReplyMode及实际session.text/attachments计数；空白、纯附件、移除附件、空闲或无有效Turn逐项核对。能力变化不重写已存偏好，用户显式选择后才允许发送；模式回调携带完整目标，跨目标旧回调拒绝。提交/原回复或首条等待时禁用模式编辑，原请求/Turn/模式不可变；原记录只读查询不锁草稿。iOS既有segmented Picker/queue不可用提示与KMP提交门禁未改，代码匹配参考，不宣称新增iOS原生或真实DEV通过。

2026-10-23 P4-3d.1：真实DEV控制验收器同步运行中输入规则。先用原生字段输入并等值核对正文，再选择已声明且可编辑的分段模式，选择后再次核对原文；原reply-mode仅检查显示，直接点reply-mode-queue/steer并核对enabled/selected。保留原提示、nonce、操作数及45秒/240秒等待，不执行旧真实模型场景。本项仅Android验收器修改，产品APK与cf54逐字节相同；iOS/KMP/Host及其他生产源码不变。编译与独立fixture控制边界辅助验证，真实原桌面/模型场景仍待P4真实DEV阶段。

2026-10-23 P4-3e：KMP统一客户端生成偏好的显式字段合并，模型动作联动模型/强度/速度，独立权限保留；强度/权限/速度仅更新相应字段，null明确清除。Android旧草稿单项编辑不再覆盖其他已记住默认；即使当前草稿值未变，显式默认动作仍持久更新客户端偏好。保留捕获完整目标、可编辑及目录能力校验；已提交输入不修改。iOS删除原字段合并闭包，调用同一KMP规则，保留其模型归并与原生持久流程。新增版本化10向量及Swift实际调用；iOS夹具支持独立端口和树外证据，原精确交互/参数断言保持。

生成设置选中语义（跟随默认与实际生效项双勾）、标准/快速开关与多档菜单、模型默认的双端联动差异仍需逐项处理；实际owner权限、首条/纯附件/冷恢复及原桌面/模型真实DEV继续开放。27来源节点与20功能整体均未关闭；业务任务/项目暂缓、A6/P7暂停、蜂窝/VPN及人工归档待验收保持。

2026-10-23 P4-3f：Android模型和推理强度改为原生菜单，按iOS绑定的实际生效值单项选中，删除默认与生效项双勾；保留强度滑块和显式跟随电脑。速度目录含ultrafast时使用多档菜单，否则为整行快速开关，未声明fast时禁用；重置速度保持独立字段。模型跟随电脑调用已有KMP changeModel，与iOS同样清模型/强度并将已有不支持速度归并standard，保留独立权限。全部动作仍捕获完整目标、复核当前目录/能力和编辑门禁，旧回调不能改新目标。菜单按目标重置；使用蓝色值/上下箭头、48dp触控、16sp行文案、绿色快速开关及项目中性色，移除Material默认紫色。共享核心/iOS/Host/传输不改。

本项只关闭设置页菜单/开关/默认联动辅助切片；原生滑块与iOS系统控件形状、菜单弹出定位/材质、表单分隔与权限图标/布局仍逐项保留P6视觉门禁，双语/深色/大字体/AX未完整验收。实际owner权限及O028/O029、首条/纯附件/冷恢复、完整原桌面/模型DEV继续开放；27来源节点/20功能整体不关闭。业务任务/项目暂缓、A6/P7暂停、蜂窝/VPN和人工归档待验收保持。

2026-10-23 P4-3f.1：三组真实控制、计划/审批、异步问题验收器改用同一原生生成设置选择入口；明确打开模型/强度菜单，速度走已声明的多档菜单或快速开关，再选择原权限。运行时速度已相同而草稿字段为空时，仅本地切换离开再回到请求值，保证原验收提交明确speed；不把生效显示冒充显式设置。保留原模型/参数、提示/nonce、操作数和所有原超时，未执行三组真实模型场景。仅Android验收器修改，产品APK与a709逐字节相同，iOS/KMP/Host/JS/全部生产源码不变。

原桌面/模型权限与控制/计划/审批/问题真实DEV均未由本项通过。仅standard且未声明fast的目录，其快速开关按产品规则禁用；此旧显式speed验收入口准确拒绝，不能改用隐藏配置或默认为已选择，需独立跟随默认场景核对。业务任务/项目、A6/P7、蜂窝/VPN、人工归档及完整P3/P4/视觉门禁保持。

2026-10-23 P4-3g：新增独立Android真机权限验收器，固定4个新输入场景：电脑默认、显式只读、显式完全访问、进程冷启动保留原记录/未发送只读草稿后改跟随默认。模型和xhigh/fast由真实本机目录核对；手机使用实际菜单/输入/发送，具名Host独立状态和端口，另一只读原owner核对准确client ID/Turn、模型/强度/serviceTier、沙盒/审批策略、真实工具退出结果及工作区外专用探针文件。界面选项和模型自述均不替代权限效果。每场景单次发送租约、原正文/指纹、完整目标与原ID固定，拒绝/重复POST须保留为验收失败；未知停止后续场景，保留原查询所需ID/状态，不重放历史13输入。独立工具输出/日志留在树外私有目录，Token脱敏，使用Host公开close释放自己的目录reader/定时器。USB loopback仅为本机功能链路，不声称LAN/TLS/VPN新通过。生产App/iOS/KMP/Host源码未改，产品APK匹配a709。

仅关闭上述四项Android本机权限/进程恢复子边界及准确原记录只读续验；guardian/子运行作用域、EasyWork/Windows、纯附件/首条混合文件、其他计划/审批/问题/通知/控制和完整P3/P4/双语/AX/视觉仍逐项开放。全部27来源节点与20功能整体不关闭。业务任务/项目暂缓、A6/P7暂停，蜂窝/VPN和人工归档单列待验收；本次USB loopback不当作网络链路新通过。

2026-10-23 P4-3g 本次结果：本次默认、显式只读、显式完全访问、不同进程冷恢复后跟随默认四项，均完成原生手机及原Electron owner核对：实际模型/xhigh/priority、准确原client ID/Turn、沙盒/审批策略、真实工具退出与专用探针效果。只读为实际非零权限拒绝且文件保持，另三项实际写入成功；冷启动保留未发送只读草稿及三条原记录的ID/完整目标/来源/意图指纹。本轮新增真实输入共5条：v2独立核对完成1条但手机场景失败，v3四场景4条；历史13条和本轮已提交输入均未重放。v3四个原生/owner场景通过，但整体因代理拦截4次分支选项GET保持失败。修正只读白名单后，以同一原Host身份/状态、原聊天和原四输入执行零输入恢复：原生1项通过，分支选项GET 200、业务POST 0、运行期间错误0，独立owner再次核对总计4条及原参数；3条warm记录指纹和第4条原意图逐一匹配。关闭自有服务时1条未完成事件读取记录为cleanup cancellation，非运行期错误。原四场景Native方法与辅助代码还原后逐字节匹配实际运行版本，生产APK与a709逐字节相同。v3八张与恢复一张原图均查看，不扩展为全视觉通过。

保留v1缺共享确认导致零业务POST的180秒失败；补齐实际确认页，确认后草稿不变且不自动发送，v7零输入原生通过。v2遗漏creation-receipts只读接口导致手机待确认，准确原输入已独立核对仅执行一次并真实成功，未重发。v3四场景已通过但整体代理读门禁失败，不覆盖该失败报告。首次零输入恢复误把已接受后清空的草稿设置当作显式参数，改为核对空草稿和原运行时实际值；v2恢复通过，没有新增模型输入。登记检查首次alias类型及新脚本unused变量失败已修正，未改产品断言。

当前检查点先完成检查、提交推送、独立远端核对、树外恢复包和台账，再继续下一项。

完整pnpm check连续59根命令通过，348.92秒，三份源码指纹稳定。之后只把验收器私有密钥等值断言改为固定不匹配提示，避免失败时打印两侧值；对应差异逐字节核对，其他源码未改。当前静态检查及零输入原记录恢复再验通过：原生1项、原四输入仍各一次、分支GET 200、业务POST 0、运行期错误0，正常退出；另一次关闭阶段事件读取取消单独记录。累计仍5新输入，历史13和本轮5均不重放。这是匹配组件的59检查与当前小修复专项组合，不宣称小修复后再次执行59。

2026-10-23 P4-3h：逐条对照O096/O097原补丁；file-only在O097指仅文件flush的Host持久化合同，与纯附件输入分别保留。Android新增三项加密journal/HTTP辅助边界：重建journal后unavailable保持原意图/ID/目标和本地原指纹、撤销旧强未接受授权、禁止任何重试写入；unavailable即使夹带完成回执也不能制造接受或重试；原运行时不可用时，准确accepted回执仍能独立只读恢复并再次读盘保留，禁止重放且无workspace/history读取。Host unavailable的evidence/capturedTarget/fingerprintVersion/fingerprint/receipt保持原null，不把本机已捕获原指纹与Host缺失证明混为一谈。只改Android验收器，生产App/iOS/KMP/Host/JS未改。

新增验收器先因nullable receiver、再因缺progress参数编译失败；第二次失败后编排误复制上轮runner，三环境原17项各通过，新增组均执行0项，数量门禁使整轮失败，不计新增通过。修正编译并先核成功构建及新runner摘要后再执行。

这是实际Android加密存储重新实例化与原生HTTP fixture辅助证据，不是Windows真实file-flush-only/目录flush故障、原桌面运行时离线或Android不同进程的新增证明。匹配复用P4-3g的实际进程恢复和5条新模型输入，不重放历史13或本轮5输入；本项新增模型0。纯附件/首条混合内容另项、O096/O097整节点、全部27节点/20功能和完整P3/P4仍开放。业务任务/项目暂缓、A6/P7暂停、蜂窝/VPN和人工归档待验收保持。

最终同一App/runner三环境各20方法、2组，共60方法/6完整分组通过（原17+新3）；产品APK与P4-3g相同，新增源码仅一份Android验收器。新增模型输入0，本轮累计仍5、历史13不重放。没有UI变化或新增截图。新对象重新读实际Android加密文件只作辅助恢复，不能称不同进程或Windows实际flush通过。 先登记检查点并提交推送、远端/恢复包/台账核对，再继续纯附件及其余P4。

当前单份Android验收器源码指纹稳定，编译及60/6原生分组通过；mobile-parity/format/diff检查通过。所有生产、KMP、iOS和JS源码及产品APK未改，上一检查点59根命令348.92秒与小修复专项仅按组件匹配复用，没有本项重新全量/iPhone/真实Windows之说。

2026-10-23 P4-3i/UI07：按iOS附件行对照，Android改为独立移除按钮、32dp缩略图/24dp文档图标、原文件名和真实大小标签。名称/缩略图没有隐含移除或预览动作，按钮有准确文件名的可访问性描述。显示与移除均捕获完整草稿目标/导航版本及原manifest，重访同一目标拒绝旧回调，本地未恢复或发送中禁用移除。导入或显式原文件读取核对原字节后，生成独立的v1加密缩略缓存；缓存绑定原manifest与原SHA、校验自身摘要/尺寸，128px/96KiB上限，显示最多两项并行读小缓存，不在界面自动重新解密大原图。缓存只用于展示，不作为原文件可提交或模型执行的证据；实际上传仍读并校验全部原字节，坏缓存不改原文件，缓存失败不使有效原附件丢失。旧小图可由完整校验后补缓存，旧大图无缓存时先保留文档显示，下一次显式验证原文件读取再补缓存。iOS既有行、KMP、Host与协议不改；本地缓存为可重建派生数据，不更改原附件/意图格式。 导入完成后再次核对本地恢复门禁，避免恢复期间发布导入结果。JPEG缩略缓存应用实际EXIF八方向（旋转与镜像），只变换显示像素，不改原JPEG字节。新界面截图使用当前已聚焦App窗口的PixelCopy及窗口/目标侧证，与全屏合成结果分列，不把已留存的API26灰缩放/旧层混合宣称修复。

仅附件行/展示缓存/目标栅栏切片，完整F25/F29/F30/F31、27节点/20功能仍开放。只读/离线准备仍受原canCompose门禁限制，系统图片选择器/最多五图/图片规范化、旧大图无缓存迁移、超过100MP或32768边长图片的文档回退、完整视觉/双语/AX及首条/纯附件真实内容闭环另项保留。大小单位/舍入使用系统格式，Android48dp与iOS44pt触控差异按P6核对。本项新增模型0，不重放历史13或P4本轮5；业务任务/项目暂缓、A6/P7暂停，蜂窝/VPN和人工归档单列待验收。

附件行v1三项UI通过；旧节点框架回调在到达产品动作前拒绝跨卸载调用，整组失败保留。现冻结原动作目标参数调用真实Session接口，保留跨目标/重访和原文件断言。额外复核大图显示内存后采用独立加密小缓存，该中间候选进行了5项UI/缓存及关联回归，不增加模型输入。

中间同App/runner三环境303方法/30组通过，六份源码稳定；API26部分全屏截图出现既有灰缩放/旧层，保留且不计视觉通过。之后补导入返回门禁及当前窗口取图、JPEG方向；API26系统EXIF写入器拒绝准备样本，发生在产品JPEG验证前，六项整组失败保留。改用标准APP1方向字节向量；后续SDK读取器仍失败，独立ImageIO复核与AndroidX最终结果见下。0新模型。

P4-3i最终采用AndroidX ExifInterface 1.4.2，显式锁定原Camera依赖已解析的同一版本；依赖图核对通过。API26系统读取器对合法APP1样本返回0，独立ImageIO确认方向1，原失败保留；最终八方向实际像素、尺寸及原JPEG字节断言在三环境通过，不将具体系统解析原因作已定位结论。

最终同一App/runner在API26、新手机API33及API37各102方法，共306方法/30完整分组通过，七份源码指纹稳定。含附件UI六项和存储、边界、队列、发送/回执、模式、生成设置回归；均为原生辅助证据，新增真实模型0。当前聚焦App窗口18张原图及18份PixelCopy/目标侧证已逐项查看核对，完整系统合成和P6视觉仍开放。

当前完整pnpm check连续59根命令通过，369.04秒，七份源码稳定；Android构建及Exif依赖图通过。之后仅更新验收文档，当前parity/format/diff另核。首条混合及纯附件真实模型内容、独立进程原字节恢复、其余P4计划/审批/问题/通知/控制继续，不复发历史输入；完整P3/P4未关闭。

末尾空行清理后的最终包已重新完成三环境306方法/30组，七份当前源码稳定；原同数量结果单独保留。最终18窗口证据中14张与已查看原图逐字节匹配复用、4张当前重新查看，18份窗口/目标侧证核对通过。59命令369.04秒为该单处空白变化前的稳定源码证据；之后当前构建、全原生矩阵及文档/暂存检查分别通过，不将原59说成空白修正后的重跑。

2026-10-23 P4-3j：补充原生手机纯附件首条、纯附件回复、首条文字与图片混合的独立真实内容验收器。先确认未发送纯附件草稿及设置实际落盘，强制停止准确验收App，再由不同进程恢复完整目标、清单和原字节；预检在两个原生阶段通过，业务POST/上传/模型输入均0。实际场景分别单次租约，具名原操作只提交一次；原桌面owner、准确client ID/Turn、canonical总输入数、实际Host文件和本地原提交快照逐项独立核对。

仅测试/验收编排变化，生产App/iOS/KMP/Host不改；完整B011/B013/B017/O092/O093、F25/F29/F30/F31及27节点/20功能不关闭。本项由App自有导入API准备附件并操作原生发送/设置/同意界面，不能代替系统文档/照片选择器、图片规范化、双语/AX及完整视觉验收。实际内容场景运行中，尚不宣称通过；历史13及P4此前5条输入不重放。任务/项目管理暂缓、A6/P7暂停，蜂窝/VPN与人工归档单列待验收。

P4-3j三个本机真实内容子场景已逐项通过：纯附件首条在原v1失败整轮中单项实际通过，当前仅接续未完成的纯附件回复及首条文字/图片混合，新命名空间两项通过。原桌面准确owner/client ID/Turn及canonical总数、真实Host原字节和手机不可变source快照逐项吻合；三个原输入各执行一次。本项新增模型3条，此前P4五条累计为8，历史13与本轮8均不重放。生产APK和Adapter与7f76d4c相同；预检2、实际4个原生阶段及只读导出1项分别登记，多个runner版本按组件匹配，不称同一runner重跑全套。

原v1遗漏回复前显式生成设置，被代理在转发Host前拦截；整轮保持失败，已执行首条单项保留。只读导出被拦截原回复的准确ID/意图/原文件快照，原桌面canonical实核仅首条，旧回复未执行，后续proxy也明确拒绝旧ID重放。v2接续因新Host共享授权前置缺失，在租约前失败，业务POST/上传/新模型均0；v3补准确作用域同意并断言授权后不变/不自动发送，使用新ID和新随机内容接续两项，零拒绝读写、零运行期或关闭期读错误，未复发旧输入。原两次失败不改为通过。

仅关闭上述Android本机内容及不同进程原字节恢复子边界；完整B011/B013/B017/O092/O093、F25/F29/F30/F31及27节点/20功能、完整P3/P4继续。通过App自有导入API准备附件并操作原生发送/设置/同意界面，不能代替系统文档/照片选择器、图片规范化、双语/AX及完整视觉。8张当前窗口原图已逐张查看，7张通过场景/1张失败保留，8份窗口侧证核对；仅App窗口，不扩为系统合成证明。任务/项目管理暂缓、A6/P7暂停，蜂窝/VPN与人工归档单列待验收；后续计划/审批/问题/通知/控制继续。

P4-3j当前两份验收源码保持稳定，完整pnpm check连续59根命令通过，352.72秒；Android构建、脚本静态检查、实际三输入组件链、8份窗口侧证和自有Host监听退出已核对。之后仅更新文档并执行当前parity/format/暂存差异检查；不重发任何原输入。

2026-10-23 P4-4a/F21/F40：按iOS计划三类界面逐项对照。计划消息默认折叠，区分“计划／正在制定计划…”，展开后显示完整Markdown与独立“复制计划”；复制精确原文，原生测试只在内存保留并恢复先前剪贴板，不输出其内容。步骤独立折叠为“执行计划 · 完成数/总数”，兼容underscore/camel及原legacy进行中状态，更新计数保留展开。待实施卡片只在非运行时显示，消息已有相同正文时不重复，缺正文时提供“查看计划”；实施、调整、反馈文案与顺序同步。反馈和展开按完整目标/导航代次与完整已知计划对象隔离，变更ID/正文/Turn重置，旧回调不得跨目标、重访或同ID改文执行；缓存/等待期间禁用命令但保留可编辑反馈和独立主草稿，提交保持原expectedPlanId及协作模式。

外部编排先因AVD名称CR未trim在安装前失败，三反例尚未运行，保留为0执行；修正后旧包三项UI反例确认。最初关联7项/环境因两处漏截图run参数和旧runtime变化验收假设失败，整组不计通过。旧包同样复现runtime测试失败：旧模拟器未按生产Host拒绝旧完整目标；核对真实runtime-server的前置409和上传后再次复核后，fixture同样在副作用前校验，现断言至多一条原目标HTTP、无换目标/重放、执行0且草稿保留。旧包配修正fixture单项通过，非把新UI归因该历史测试问题。API26六UI中五项通过、一项在验收器后台线程首次获取剪贴板服务时失败，产品复制动作尚未到达；整组失败保留，改主线程取测试服务，产品包未变，当前最终同包/runner矩阵重跑。

本切片为Android计划展示与回调边界，iOS既有计划界面/发送守卫未改，共享协议/KMP/Host不改。新增Android捕获导航及完整计划对象栅栏单独验证；iOS对应跨目标回调仍留整体F40当前产品未验收清单，不因Android通过而赋予iOS新证明。完整F21/F40、计划真实模型提出/调整/实施、审批/问题/通知/控制、双语/AX/系统合成视觉和27节点/20功能继续；任务/项目管理暂缓、A6/P7暂停，蜂窝/VPN与人工归档待验收。本项新增模型0，历史13和P4累计8（总21）均不重放。

API26在主线程剪贴板验收器修正后另一次运行发生System has crashed，尚未正常完成首个场景；ADB虽在线，真实activity服务缺失，准确验收App无法启动。该整轮失败不计通过。核对本任务独立AVD后正常关闭并冷启动，磁盘数据保留、不wipe；首次启动未传独立AVD_HOME而在打开AVD前失败，修正仅本次进程环境后恢复activity服务。最终完整三环境同包矩阵重新通过；不把系统崩溃归因产品复制或声称已定位OS根因。

P4-4a最终相同App/runner在API26、新手机API33及API37各41方法，共123方法/9完整原生辅助分组通过，七份源码稳定；含6项新计划UI、7项发送/计划/缓存回归及28项目标/存储边界。相同最终runner在旧包的三项UI反例确认，另有旧包/旧fixture的runtime验收失败与修正fixture后的单项通过证明。18张当前App窗口原图及侧证逐张核对；部分原生触摸反馈帧、反馈聚焦的卡片内滚动保留，不等于完整IME/系统合成/双语/AX视觉通过。新增真实模型0，后续真实计划流程继续。

2026-10-23 P4-4b/F21/F40：Android真机原生界面完成提出计划、按原计划ID调整、实施完整修订正文三步真实模型验收。各步独立核对原Electron owner、唯一clientId/Turn、规范历史总输入1→2→3、实际plan/plan/default协作模式及显式model/xhigh/priority/readOnly/on-request。调整与实施保留独立主草稿，原不可变意图与Host回执匹配；实施携带完整修订正文和新expectedPlanId，完成后待实施计划消失、模式回默认。产品App与P4-4a逐字节相同，仅新增原生/JS验收器，iOS/KMP/Host/协议未修改。

第一轮提出计划已独立通过，调整在发送前因未找到反馈框失败；后续恢复预检一次共享提示等待失败，另一次调整点击等待失败，均在claim及POST前，新增模型0。保留失败整轮，不重发首次输入。验收器改走本App收起键盘入口，并等IME隐藏及窗口可见尺寸连续稳定700ms；原生零输入预检后，只继续同一原owner的剩余调整/实施。此前单次零输入调整可达不足以宣称稳定，保留反复失败，不把键盘时序认定产品根因。

真实结果由同一产品包的首次提出计划1项和当前runner的调整/实施2项组成；不声称三步全部由最终同一runner执行。恢复入口只接受原失败证明中已完成且唯一的首次输入，先只读复核规范历史恰好1条及原待实施计划完全相同，再允许剩余两条独立输入。两个当前源码指纹绑定；旧轮结果与runner指纹独立保留。

原图逐张核对包含计划正文、调整编辑和最终完整修订输入/回复；部分PixelCopy窗口图仅边框/背景、文字层缺失，且反馈内滚动可裁切卡片标题。这些图片保留为捕获异常/局部范围，不计完整视觉通过，不据此推断手机数据丢失或定位合成器根因。

本项新增真实模型3，P4累计11、全部阶段累计24；历史21条及本次已消费原ID不重放。USB回环不证明蜂窝/VPN。审批、异步问题、通知、控制及完整P3/P4、27节点/20功能、双语/AX/系统合成视觉继续；任务/项目管理暂缓，A6/P7保持暂停，人工归档及蜂窝/VPN待验收。F21/F40整体状态与iOS当前产品验收仍开放。

P4-4b当前两份验收源码保持稳定，完整pnpm check的59条根检查通过（357.68秒）；Android构建与原生真实模型结果单列。15张已查看窗口图及元数据登记，部分文字层缺失仅保留捕获异常，不计完整视觉通过。

2026-10-23 P4-4c/F41/F42/F46：按冻结B013/B015/B018/B019/B020及iOS ApprovalCard/AsyncQuestionDeck逐项对照，Android问题选项改为包含原标签和完整说明的前导对齐整行，44dp最小动作区，单/多选分别暴露原生选择语义；提交增加进度指示，等待期间禁用选择/自定义/提交。原目标与完整已知请求仍匹配时，断线缓存允许编辑本地答案，所有命令仍拒绝。问题选择/自定义草稿按完整已知请求对象和导航代次隔离，内容/题目/Turn/目标改变时重置，不把旧答案移给新问题。

审批与问题业务回调捕获完整目标和导航代次，并逐字段匹配已知原请求DTO及原thread；同ID/Turn内容已变或离开再返回均不授予旧回调写权限。提交前再核对当前前台、连接代次、机器目标、端点、证书及内容共享许可，回答集合复制为本次原输入；等待按原请求/owner隔离，晚到错误受导航/错误代次保护，结束只释放原owner等待。保留允许一次/本会话允许/拒绝/停止及异步跳过的既有具名协议，未增加任意方法或重放路径。

旧产品六反例已确认。首次新包真机/API37各六项中四通过、两项失败：验收器直接调用已移除Compose节点的包装回调，框架在进入业务前拒绝；整组失败保留。当前改为捕获原请求及原导航快照核对业务边界，随后当前动作仍经原生按钮提交。关联七项回归最初各一失败，因旧验收依赖“● One”文本前缀；两处改为原生选中语义，产品未为旧断言恢复伪前缀。最终runner的旧包四项UI反例与此前两项旧业务回调反例分别登记，不合并宣称同一最终runner完成六反例。

本项仅Android审批/问题展示及请求回调子边界，真实模型工具审批允许/拒绝、桌面抢答/失效、真实异步问题/重启与完整控制仍待验收。iOS、KMP、Host、协议源码未改；iOS相同请求内容/导航回调及双端完整F41/F42/F46当前产品验收不由本项补签。Android原生RadioButton/Checkbox与iOS圆形勾选、审批标题图标/色彩及完整卡片/IME/系统合成/双语/AX细节继续列入P6，不宣称全界面一致性通过。问题语音属于暂停的P7，保持暂停。任务/项目管理暂缓，A6/P7暂停，蜂窝/VPN和人工归档待验收；27节点/20功能及P3/P4其余门槛继续。本项新增真实模型0，总24条已消费输入不重放。

P4-4c最终相同App/runner在API26、新手机API33及API37各42方法，共126方法/9完整原生辅助分组通过，四份源码保持稳定：7项审批/问题新断言、7项问题卡/状态/计划/发送回归及28项目标/恢复边界。最终runner旧包4项UI反例确认，旧版早先6反例和所有中间失败分别保留。原18张已查看App窗口图及元数据按完全相同产品包、逐字节不变的原六测试体和捕获助手匹配复用；最终增加的多选/保密方法不补签视觉通过。图中触摸反馈、卡片内滚动裁切及文字层缺失仍为局部证据/捕获异常，P6全视觉保持开放。

真机最终一轮在回归前读取已安装App的ADB命令失败，原生执行0，不计通过；当前设备准确包路径、连接和唤醒分别核对，独立完整读回23546764字节、SHA匹配同一产品包后，仅补跑剩余7+28分组通过，无重装/清数据。其余已通过项未重放；未推断USB/OS根因，未把此辅助恢复当作蜂窝/VPN证明。

2026-10-23 P4-4d/F41/F46：Android新手机原生界面与原Electron owner完成两项真实工具审批：首条请求取消后原Turn中断、原命令declined且未成功执行；第二条原请求允许后，同一原命令completed、exitCode=0并输出专用标识，模型最终回复匹配。原桌面此次仅声明accept/cancel，“拒绝后继续”没有可用动作，准确记录取消/中断及该限制，不冒充decline-and-continue通过。两次审批均独立核对原Host/thread/Turn/request和唯一具名POST、原输入clientId/意图及规范历史总输入1→2，实际模型/xhigh/priority/readOnly/on-request/default参数；审批保留独立主草稿。

新增独立原生与JS真实验收器，每条新输入和原审批动作分别取得不可变单次租约；代理仅接受准确原正文/设置/空附件及原审批ID/Turn/decision，重复和未知结果不重放。模型任务仅打印专用标识，不读写文件、不联系其他聊天或派生任务。共享许可和显式普通模式、权限、速度均经本App原生菜单核对；零输入预检后再提交。消费计数在独立owner看到原用户输入时登记，完成场景和未解决原输入分开。产品App与P4-4c逐字节相同，iOS/KMP/Host/协议及所有生产源码未改。

首轮零输入预检的原生方法成功、业务POST及模型输入均0，但验收器最终CLI摘要读取未初始化unresolvedOriginalCases数组而退出1；原日志/报告与整体失败侧证分别保留，不把原JSON的passed当作整体成功。修正仅JS验收字段后，完整零输入预检CLI退出0，再执行两条全新输入。真实两场景当前同包/runner原生方法、独立原owner、实际工具结果和精确POST总计5条均通过，读取/写入拒绝及未解决原ID均0。

本项新增真实模型2，P4累计13、全部阶段累计26；此前24条及本次已消费输入不重放。仅关闭此原owner的取消/允许工具审批子边界；本会话允许、decline继续（原owner未声明）、跨端抢答/旧请求失效、真实异步问题/重启、控制及完整F41/F46、27节点/20功能/P3/P4继续。iOS当前产品验收不由Android补签。9张当前窗口原图已逐张查看、窗口元数据核对；部分文字层缺失/局部裁切保留为捕获异常，不计全视觉。USB回环不证明蜂窝/VPN，人工归档待验收；任务/项目管理暂缓，A6/P7暂停及P9依赖保持。

P4-4d当前两份验收源码保持稳定，完整pnpm check的59条根检查通过（366.61秒）；当前Android构建、零输入预检和两项真实模型/原owner结果分别记录。原始回执与窗口证据保留在树外私有目录，本项整体功能及完整视觉仍未关闭。

2026-10-23 P4-4d.1/F41/F46：补强macOS真实审批验收器的命令范围门禁。原输入租约与请求ID校验之外，审批前必须核对原IPC方法为commandExecution/requestApproval、准确thread/Turn/wire请求ID、可选cwd、原API投影及完整命令，命令只允许本次专用标识的固定zsh/printf调用。追加shell动作、换行、改变wrapper/标识、文件变更请求、错目标/目录/投影或附带问题均在给手机发审批许可前拒绝；拒绝错误不回显原命令。它仅约束本轮验收器，产品App/Host/协议行为未修改。

14项纯Node边界用例接入既有pnpm check中的权限证据测试分组，根检查仍为59条。另以准确原thread/Turn/clientId只读复核前一轮两项：每Turn恰好一条准确printf、无文件变更等条目，状态分别declined及completed/exitCode=0；原任务/输入/审批不重放。旧两项真实结果保持其原指纹，只读复核不补签“此前已运行新前置门禁”。当前真机零输入预检原生1项、CLI整体通过，业务POST和新增模型均0。

新增模型0，P4累计13、总26。新命令门禁的拒绝与绑定为辅助检查，强化门禁尚无新的真实审批输入；此前两项只读匹配与其原始真实证明分别保存。本会话允许、原owner未声明的decline继续、跨端抢答/失效、真实异步问题/Host重启、控制及完整F41/F46/P3/P4/27节点20功能仍开放；任务/项目管理暂缓，A6/P7暂停，蜂窝/VPN及人工归档待验收。

2026-10-24 P4-4e/F34/F35/F41/F42/F46/F58：新增独立Android真机异步问题验收器，限定原Host/thread/Turn/question、不可变输入和一次回答租约，实际原Electron owner与规范历史逐项核对。产品源码未改。非阻塞提问、选项/自定义草稿、前后翻页/横滑/收起重开、处理后推进和跳过取得局部真实证据；专用Host重启保持身份、令牌、原动作账本和原问题ID。

两项主输入与三条实际回答共消费5条输入，规范历史总数5，跳过不发模型输入；P4累计18、全部阶段31条均不重放。首个原生完整分组在最终命令核对失败：原Turn完成，但原SDK命令条目仍inProgress/exitCode=null，未验证命令终态；保留原失败，不把异步完成冒充工具终态。当前同产品包的新runner零写入原会话恢复方法通过，随后仅执行原未执行的第二项。第二个完整原生分组因原始生效权限断言失败：原提问readOnly/on-request，结束后回答产生的新Turn变成dangerFullAccess/never；模型/极高/快速保持，未执行工具/文件变更。两个失败分组和只读恢复分别登记，整项异步验收未通过。

优先修复共享Host的结束后异步回答权限继承，再用全新输入验收，不重放旧5条。iOS共用此Host风险，当前iOS未新增验收。10张已查看原App/问题dialog独有窗口图与元数据仅作局部状态证据；黑色背景、滚动裁切及未滚到最终回复不计完整合成视觉，P6继续。F34/F35/F41/F42/F46/F58及27节点/20功能/P3/P4保持开放；后续控制、跨端抢答、多选/保密真实能力、蜂窝/VPN及人工归档待验收；任务/项目管理暂缓，A6/P7暂停，P9依赖保持。

2026-10-24 P4-4e.1：修复共享Host在任务结束后回答异步问题时回退电脑默认权限。新回答从准确原owner的原问题Turn参数取得已确认权限预设，固定到原回答的不可变意图；无法确认时409且不保存/发送新输入。旧版无固定权限且无回执的原意图不补写、不执行；已有已接受/未知回执继续按原ID核对。运行中追加和跳过保持原路径，普通跟随电脑默认的回复规则不改。

原真实反例readOnly→dangerFullAccess和合成IPC反例:read-only→:workspace分别保留；当前完整IPC边界分组通过，另核对未知预设零写入、旧未提交意图不改写/不执行。全新真机提问与回答共2条输入，专用Host重启保持身份/令牌/原问题；原生整组1项通过，原owner两Turn均completed且readOnly/on-request/user、默认模型/极高/快速相同，无工具/文件变更，规范历史恰好2条。原CLI因1次后台workspace fetch failed整体失败，该读取失败不改记通过，也未推断根因。当前只读恢复CLI和原生1项通过，业务POST与新增输入0；产品与Host源码相同，原实际测试方法/助手逐字节相同，runner仅只读恢复标识选择改变，未宣称全套在最终runner重跑。

本项新增2条，P4累计20、总33条已消费输入均不重放。只关闭本次请求批准权限回退缺陷；前项命令终态、原CLI读取失败、完整异步/审批/跨端抢答/其他权限预设及复杂配置和F34/F35/F41/F42/F46/F58仍开放。iOS共用修复后的Host规则，未新增iOS原生真实验收或更新正式服务；Windows未验收。6张原App/dialog独有窗口图已查看，仅作局部状态证据，完整视觉P6继续。继续队列/追加/停止/恢复；蜂窝/VPN与人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.1：对照iOS的runAgain→send设置回退，Android长按菜单继续/重新运行改为使用当前草稿的已选生成设置，和主按钮继续一致。控制正文仍为明确的新输入；独立草稿正文/文件不发送、不清空，未选择的设置不补造。

旧产品同新测试runner在API37完整3方法分组中2项失败，均准确缺少已选模型；主按钮继续通过，保留失败整组。初次新增测试误用LocalDraftFile.id导致编译失败、原生执行0；修正测试引用manifest.id后才构建/安装，失败日志保留。最终相同产品/runner在API26、新手机API33和API37各4方法，合计12方法/3完整原生辅助分组通过，两份源码冻结。覆盖主继续、长按继续/重启、全部已选模型/强度/权限/速度/计划字段、独立正文/本地文件、原意图摘要、空设置及两次新操作ID。未调用真实模型。

本项为原生HTTP/存储辅助证明，新增模型0，总33、P4累计20条不重放；真实队列/取消/追加/停止/恢复/重启、旧菜单回调与全视觉仍待后续。iOS源码未改，其设置回退仅作源码对照，不补签iOS验收。JS/Host/KMP未改，按相同组件复用前检查点59条完整检查374.90秒，未宣称在新Android包重跑完整pnpm check。Android构建及当前一致性检查分别执行。完整P3/P4、F34/F35/F36/F39/F40/F43/F46保持开放；蜂窝/VPN与人工归档待验收，任务/项目管理暂缓，A6/P7暂停。

2026-10-24 P4-4f.2：新增独立真机原生队列验收器，原输入/队列/取消使用独立一次租约；原Electron owner与官方元数据队列分别只读核对。零输入预检通过；真实整组未通过，发现发送后生成设置被清除的产品差异，优先修复后继续。

首轮验收器新建ID绑定错误，原输入确已消费一次；原失败与只读核对保留，原任务已完成封存。零写入恢复因原任务已结束而拒绝继续，无新输入。新的独立场景绑定成功，但验收器空草稿先选排队按钮失败；修正操作顺序后仅继续原仍活动任务，未重发首条。一项排队输入经官方native queue进入第二个原Turn并完成；第二项省略model/effort/permission/speed等已选字段，被严格验收代理在Host之前拒绝。原生记录导出确认该第二项仍为uncertain，原ID/意图/摘要保留，未用404补签未接受、未重新提交。完整队列编号/中间取消/追加/FIFO验收均未关闭。

新增消费3条：首个已封存原输入1、后一个原输入1及其实际执行的首个队列输入1；P4累计23、全部36条，不重复计只读观察。两个当前原Turn均completed、readOnly/on-request/xhigh/priority，官方队列为空，未发生本次权限提升。4个失败原生整组、两次零输入预检和当前零POST原生只读记录方法1项分别留证。Android产品/Host源码未改；严格设置门禁没有为通过而放宽。

复核完整iOS发送路径：submitted/completed回执清除生成字段，queued回执保留；confirmed首条新建也清除原占位草稿。Android当前对queued也删除generation，下一步仅修复排队确认后的保留，维持普通回复/首条新建重置语义，补连续排队与迟到回执边界，再继续队列及停止/恢复。新增取消/追加/完整FIFO/控制和F34/F35/F36/F37/F38/F39/F40/F43/F44/F46仍开放。只读窗口1张已查看，局部状态不计全视觉。任务/项目管理暂缓，A6/P7暂停，蜂窝/VPN和人工归档待验收；27节点/20功能及完整P3/P4不宣称完成。

2026-10-24 P4-4f.3：修复Android排队确认后清空已选生成设置。严格对照iOS完整发送路径，仅queued回执保留模型、推理档位、权限、速度及协作模式；普通submitted/completed回复与confirmed首条新建仍重置，原目标/草稿版本/脱离等待保护保持。

旧产品同一新增8方法runner在API37整组2项失败：连续排队与切换聊天后迟到的queued回执均丢失全部已选字段；另外6项通过，原失败整组保留。最终相同产品和runner在API26、新手机API33及API37各8项，合计24方法/3完整原生辅助分组通过，三份源码冻结。覆盖连续队列的完整请求字段/不同操作ID、迟到回执两目标独立设置和正文、普通回复与首条新建重置，以及既有继续/重启/独立本地附件/原意图摘要边界。

新增真实输入0，P4累计23、总36条，既有不确定队列原意图已导出且不改写/不重放。真实队列验收器在confirmed首条新建后显式选择首次排队设置，符合双端重置语义；尚未新增真实队列通过。下一步执行独立全新场景的编号/中间取消/重排/运行中追加/FIFO核对，再处理停止/恢复/重启与旧回调。JS/Host/KMP源码未变，按匹配组件复用前检查点59条根检查362.78秒，未宣称新Android包重跑全组。iOS仅源码对照、未新增原生验收；完整P3/P4及功能状态保持开放。蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停，P6视觉与P9依赖保持。

2026-10-24 P4-4f.4：修复后的独立Android真机原生队列场景整组通过，当前原生方法1项与CLI均通过；产品及验收源码未再修改。零输入预检另行通过后，新场景仅使用全新不可变输入及原操作ID，不重放前36条。

三项排队均来自官方native queue，准确原Electron owner和官方元数据队列独立核对ID、顺序和正文。手机显示1/2/3；取消准确中间项后只余原1/3并重编号1/2。运行中追加进入原Turn，原sleep命令自然completed/exitCode=0；其后两项依原1→3顺序完成。规范历史恰好4条实际输入，取消项没有对应Turn；三个Turn均completed且默认模型/极高/快速/原readOnly/on-request设置匹配，原回执与不可变意图摘要核对，官方队列最终为空；没有文件变更或其他工具任务。

本项新增实际输入4（主输入、两条最终执行队列、同Turn追加），取消项不计；P4累计27、全部40条。零预检0POST，实际7项具名业务POST含创建准备/确认、三排队、一次取消、一次追加；无拒绝写入/读取，原6个临时端口及reverse均独立核实关闭，正式服务未动。6张App独有窗口图已查看，编号/取消/追加/最终回复只是局部滚动状态，P6全视觉未关闭。旧失败整组、旧不确定意图及前异步命令终态未验证仍原样保留，本次不补签旧失败。立即执行队列、跨端并发及停止/恢复/重启、旧回调等剩余F34/F35/F36/F37/F38/F39/F40/F43/F44/F46与完整P3/P4仍开放。iOS/Windows未新增真实验收；蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.5：Android发送/停止/继续/重启按钮与长按菜单捕获显示时的完整目标、客户端连接版本/信任、导航选择、原Turn/状态、动作及草稿版本。执行前重新核对；旧回调静默拒绝，菜单不跨上下文保留。业务方法同样校验原上下文，当前新手势仍按原规则执行。

同一5方法原生测试在旧产品API37复现5项错误：A继续误发B、A→B→A旧重启、新Turn被旧停止中断、旧继续使用后来修改的权限设置、旧发送提交替换正文。初轮仅因合成Host名称不含fixture而被既有共享许可测试前置拒绝，5个setup失败单列保留；只修正测试命名，没有放宽许可。最终相同测试与8项设置/草稿回归在API26、新手机API33、API37各13项全部通过，共39方法/3完整原生辅助组，四份源码冻结；当前停止和发送正向点击、所有继续/重启/排队设置与独立附件回归保持。

新增模型0，P4累计27、全部40条保持不重放。此项仅原生边界辅助验收，先前真实队列按原产品/runner指纹保存，不能直接补签新包真实通过。JS/Host/KMP未变，匹配组件复用59条根检查362.78秒，当前Android构建/原生与一致性检查分别执行。iOS目前仅按调用时当前上下文检查stop/runAgain，延迟UI回调同类风险需独立复核；未改iOS源码、未新增iOS原生通过。停止请求的迟到成功/失败、重连及并发忙碌归属和真实停止/恢复/重启继续；完整F34/F35/F36/F39/F40/F43/F46与P3/P4开放，P6视觉后续。蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停，P9依赖保持。

2026-10-24 P4-4f.6：Android停止请求改为独立owner管理，忙碌状态绑定原完整目标、连接版本和Turn；同Turn未决停止仍去重，新Turn可发起独立停止。原请求仅在原选择/导航/连接/Turn且仍持有owner时更新界面；旧finally不得清除新owner，迟到失败不覆盖较新的错误，取消协程不转成业务错误。

旧产品API37同一10方法原生分组4项失败：迟到失败覆盖往返导航后的新错误、同目标较新错误被覆盖、旧停止忙碌阻挡新Turn停止、迟到成功刷新返回后的选择；其余6项通过，原失败整组保留。最终10项回调/停止归属与8项设置/草稿回归在API26、新手机API33、API37各18项全部通过，共54方法/3完整原生辅助组、四份源码冻结。真实挂起响应后分别检查不刷新/不覆盖、旧完成不解除新停止忙碌、当前失败仍显示，以及同Turn往返导航后仍仅发起一次停止；当前停止/发送及继续/重启/队列设置回归保持。

新增真实输入0，P4累计27、全部40条不重放。该项为原生接口/存储/界面辅助证据；真实队列保持其旧指纹，当前产品尚待全新停止/恢复/重启的真实模型与原桌面核对。JS/Host/KMP未变，匹配组件复用59条根检查362.78秒，Android当前构建/54项原生及一致性检查分别执行。iOS停止仍使用targetKey与布尔忙碌集合，同类迟到结果边界需独立复核；未更改iOS、未新增iOS/Windows真实通过。完整F34/F35/F36/F39/F40/F43/F46与P3/P4保持开放，P6视觉继续；蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.7：新增当前独立真机停止/主按钮继续/菜单重启验收器，限定完整目标、不可变输入、准确原Turn停止和一次租约；产品源码未改。当前零输入预检原生1项/CLI通过，实际原生整组与CLI失败，未发出停止、继续或重启，不宣称真实控制通过。

实际首条已被原Electron owner消费且仅一次，原命令completed/exitCode=0、规范历史1条；原操作与输入封存不重放。Native等待运行状态180秒超时；导出的唯一创建记录为prepared，但submissionAttempted=true且意图摘要匹配。Host原创建为outcome-unknown，当前只读核对准确owner、项目、目录、原clientId及完整首条内容均匹配。该状态差异需继续复现回执顺序，未把当前匹配结果补签此前已确认。原CLI因未绑定公开threadId报告新增0，原报告保留；独立只读副记录校正本次实际消费1，P4累计28、全部41条。

完整pnpm check的59条在两份验收源码冻结下通过357.03秒；初次10.06秒因新原生测试未登记功能关联而失败，补登记且不改校验器后重跑。此前首次新工具lint因未使用助手失败，也保留。5张App独有窗口图已查看，只是未发送草稿/许可前后状态，实际停止画面未取得，P6全视觉开放。原生完整失败组、原意图/已提交标记与Host回执分别保存；6个临时端口及reverse独立核对关闭，正式服务未动。下一步先修复验收器原输入计数，并以原记录零写入恢复、合成迟到prepared回执反例核对状态回退；完成检查点后再继续真实停止/恢复/重启。iOS/Windows本次未新增真实结果；F26/F28/F34/F35/F36/F39/F40/F43/F44/F46及完整P3/P4开放。蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.7.1：修复Android创建回执顺序：已尝试提交后，迟到prepared回执仍严格验证原Host/操作/项目，但不覆盖原提交状态、结果或错误，也不使原查询观察者提前退出。首次提交前的prepared正常接受；新的权威outcome-unknown/confirmed继续生效，原意图和已提交标记保持。验收器最后只读计数可从准确自有broker原记录绑定未公开threadId，不能授权任何后续输入/停止/打开；实际未绑定前计数为未知，不再预填0。

同一6方法原生测试在旧API37完整组4项失败，包括实际挂起HTTP准备读取晚于新提交回执返回后覆盖状态；其余2项通过。修复后6项包含于43项扩展组，覆盖加密重开、全部未决阶段、脱离等待、错目标拒绝、后续权威结果，以及19项命令/创建/恢复和18项控制/设置边界。当前API26与真机各43通过；API37最初43项有1项首次创建后回复超时，同包整组重跑又在菜单用例挂起并180秒退出，两个失败完整/部分组均保留。仅正常冷重启本任务独立API37，保留磁盘、配置/内存/GPU/端口且不wipe；同源码/产品/runner冷启动43项通过。三环境匹配组件链129项/3完整通过组，不称一次无中断全绿，最初超时/挂起的底层原因尚未确认。

全新runner在新产品包上，对前一真实失败的同一个原操作执行真机只读恢复，原生1项与CLI通过。严格GET代理下业务POST/拒绝写入/新增模型均0；同Host ID、令牌保持，原记录prepared/已尝试经原API查询为confirmed，操作ID/正文/意图摘要不变，规范历史前后各1条、原SDK Turn仍completed。独立临时Host和reverse已核实关闭，正式服务未改。原生窗口1张已查看，仅局部原会话/最终回复/空输入，不补签原失败或完整视觉。当前新父验收器未触发新的失败场景，最后只读计数补强属于已实现工具边界，旧CLI原计数0仍由独立副记录校正为1。

本项新增真实输入0，P4累计28、全部41条均不重放。当前完整59条pnpm check在四份变更源码冻结下通过；Android当前构建、129项组件链与原记录0写入恢复分别留证。iOS/Windows未新增原生验收；iOS相同创建/读取顺序仍需独立复核。下一步提交本检查点后执行全新独立停止/主继续/菜单重启场景；真实控制、完整F26/F28/F34/F35/F36/F39/F40/F43/F44/F46及P3/P4、P6视觉均开放。蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.7.2：当前独立Android真机首条创建→准确原Turn停止→主按钮继续→长按菜单重新运行完整场景通过，原生1项与CLI均通过。新场景使用全新Host/原操作与输入租约，先前41条封存不重发，产品与验收源码未再修改。

当前零输入预检另行原生1项/CLI通过且业务POST为0；实际仅5项具名POST（准备、确认、停止、继续回复、重启回复），无拒绝读取或写入。原桌面owner和规范历史独立核对恰好3条输入、3个不同Turn：原Turn interrupted，继续与重启两个Turn completed且各自最终回复准确；停止只命中准确原Turn一次。3个Turn均默认模型/极高/快速/readOnly/on-request设置匹配，后两个无工具或文件变更；原保存意图/摘要与每个实际请求逐项一致，当前新草稿为空时发送禁用。

本项新增实际输入3，P4累计31、全部44条；原失败场景、旧CLI计数校正及原记录零写入恢复不改写为此前已通过。原SDK命令条目在最终独立快照仍inProgress/exitCode=null，命令/OS进程终态单列未验证；停止通过范围是原Turn中断，不能据任务中断或UI命令组“已停止”文案补签工具终态，P6需复核该投影文案。6张App独有窗口图已查看，仅局部运行/中断/继续/重启与许可前后，非全视觉。6个临时端口及reverse已独立核对关闭，正式服务未动。当前同代码匹配复用59条根检查357.36秒，当前实际包与129项原生组件链指纹一致；不新增iOS/Windows通过。下一步零模型复现同聊天两个已完成任务之间的旧菜单终态身份绑定，及继续队列立即执行、跨端审批/剩余状态。完整F26/F28/F34/F35/F36/F39/F40/F43/F44/F46/F48与P3/P4/P6开放；蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.8：补齐Android已完成任务的动作身份。除活动Turn外，发送/停止/继续/重启上下文同时捕获最近用户消息ID与Run ID；同一聊天内两个任务都idle且活动Turn为空时，旧菜单也不能作用于后来完成的新任务。同任务正文呈现刷新不改变ID时仍允许动作，新任务的新菜单保持正常。未新增wire字段或模型输入。

同一4方法原生测试在旧API37完整组2项失败：旧重启误作用于同聊天的新已完成任务、同用户条目但新Run的旧继续仍发送；两个正常路径通过，原组保留。当前相同4项与既有18项控制/设置回归在API26、新手机API33及API37各22项全部通过，共66方法/3完整分组、5份回归源码冻结。覆盖空活动Turn、原用户/Run身份、纯呈现刷新、新菜单、既有活动Turn/切换/草稿/迟到停止/设置及独立附件。

本轮最初同新产品的22项API37组在首次创建确认超时，另两环境通过，原失败组保留。源码核对发现该旧用例默认夹具可能在收到submit前就返回confirmed。只收紧该用例配置：实际收到唯一submit请求后才允许confirmed，新增已尝试标记/单次提交断言；首次发送及新聊天跟随回复均等待真实按钮可用，没有加长10秒等待或删除断言。重新构建runner后本轮三环境整组通过；不据此补签前轮超时/挂起底层原因。

新增实际输入0，P4累计31、全部44条均不重放。JS/Host/KMP未变，按相同组件复用上一检查点59条根检查357.36秒；当前Android构建/66项原生和一致性检查分别执行，上一真实控制按原包指纹保留，不宣称新包已跑全部真实场景。对上轮停止原命令追加0输入只读复核：规范历史仍3条、原Turn仍interrupted，原SDK命令后来completed/exitCode=0；这是晚到终态，不证明中断瞬间命令或OS进程已经结束。UI命令组“已停止”措辞继续在P6复核，旧异步命令的独立终态门槛仍开放。iOS终态任务身份/同类回调需独立复核，未新增iOS/Windows原生通过。下一步继续原生队列立即追加/执行、跨端审批和剩余P4断言；完整F34/F35/F36/F39/F40/F43/F44/F46/F48与P3/P4/P6保持开放。蜂窝/VPN及人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.9：Android真机队列条目“立即追加”完整专项通过。验收工具增加独立新场景，限三个不可变输入、准确原运行Turn和一次原队列执行；原生UI实际点击队列行，Host使用同一原消息ID。产品/Host/Adapter/KMP生产源码未改，不重放此前44条。

API26、真机API33和API37各24项原生辅助检查通过，共72方法/3完整分组，覆盖五项队列动作UI与19项命令边界，5份源码冻结。当前零输入预检原生1项与CLI通过、0业务POST；真实原生1项与CLI整组通过。官方native queue独立读取两条准确原ID、正文和顺序，手机编号1/2；点击首条“立即追加”后，原消息ID进入原运行Turn，另一条仍排队并重编号1。原sleep自然结束并由SDK证明completed/exitCode=0；原Turn最终回复追加标记，余项随后在第二个Turn完成。规范历史恰好3条输入、两个completed Turn，队列最终空；原不可变意图与摘要未变、原队列mode未改，未新增另一个显式追加输入。

本轮新增实际输入3，P4累计34、全部47条；排队转追加只计原输入一次。真实5项业务POST为创建准备/确认、两条排队和一次execute，零拒绝读取/写入；6个独立临时端口及reverse核实关闭，正式服务未动。59条根检查357.37秒本次全部通过且2份修改源码冻结。8张App独有窗口图已查看，仅局部分享/编号/追加/结果状态，不关闭P6整体视觉。空闲态队列“立即执行”、跨端审批抢答/失效、未知结果结束等待及生命周期等剩余断言继续开放；完整F37/F38/F43/F44/F46和P3/P4/P6均未整体验收通过。旧失败与未知原记录保留，旧异步命令终态及停止瞬间的工具进程语义不在本项补签。iOS/Windows未新增原生或真实通过；蜂窝/VPN与人工归档单列待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。下一步先补跨端审批/问题失效与其余P4源节点断言。

2026-10-24 P4-4f.10：Android审批提交忙碌状态改为按准确目标、线程、原请求Turn及审批ID隔离。新任务复用审批ID时，旧提交不锁住新审批/问题；旧完成不解除新请求的等待。保持同一原请求跨导航去重，异步问题所属原Turn不因当前任务Turn前进而改变。未修改wire或持久化Schema，也未重放任何原审批/输入。

相同5项新原生测试在旧API37产品整组2项失败：新任务同ID审批/问题被旧等待禁用；其余3项通过，旧组原样保留。修复后5项与既有7项审批/问题UI/HTTP/Keystore回归，在API26、新手机API33、API37各12项全通过，共36方法/3完整分组，3份回归源码冻结。覆盖原请求ID+Turn、迟到成功/错误、同原请求导航返回、异步任务继续运行的去重及既有本地选择、密码输入语义、缓存只编辑、正文/Schema变化和真实原生等待控件；本轮均为合成请求辅助检查。

新测试最初编译因字符串与in/to缺少空格失败；随后误调用的旧runner不包含新类，只有初始化失败，未执行5个新方法。该编译日志和旧runner初始化失败组均保留，不算产品反例；修正语法后先确认BUILD SUCCESSFUL，再运行有效旧产品5方法反例及当前回归。旧/新有效组的5方法源码摘要一致，未放宽产品断言。

本轮新增实际模型输入0，P4累计34、全部47条封存不重放。当前Android构建及36项原生辅助通过；JS/Host/Adapter/KMP未改，按匹配组件复用上一检查点59条根检查357.37秒，当前一致性检查另行执行；此前“立即追加”真实场景仍按原验收包指纹保存，不据此宣称新包所有真实场景通过。iOS源码仍以目标与审批ID记录等待，同类复用需独立复核，未修改iOS或增加iOS/Windows真实证明。真实跨端审批先处理/失效、异步问题能力条件及其余源节点仍开放，完整F41/F42/F46与P3/P4/P6不关闭。截图仅为辅助测试捕获附件，不新增全视觉签字；蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。下一步继续真实跨端审批失效与各源节点剩余断言。

2026-10-24 P4-4f.11：真实Electron原请求由第二个具名HTTP客户端先处理后，Android权威刷新移除原审批，保留的原回调不取得等待所有权且未发出重复审批POST；独立草稿保持。随后全新输入在原生手机上准确允许其新审批，原命令实际执行成功。此项是具名HTTP客户端竞争及手机失效，不称为桌面UI操作。未改产品、Host、Adapter或KMP生产源码。

当前零输入预检原生1项与CLI通过，0模型/审批写入。真实原生两次完整方法与CLI整组通过，只有两个全新模型输入：首个原工具请求准确绑定Thread/Turn/请求ID/cwd/正文后由第二客户端处理一次；手机只读刷新后旧控件消失、原业务回调不能发出命令。首项按实际cancel记录interrupted，命令未成功执行；第二项手机原生点击当前accept，任务completed，SDK命令completed/exitCode=0且输出与限定printf标记一致。原桌面owner和规范历史独立核对恰好2条输入，默认模型/极高/快速/readOnly/on-request设置匹配，原意图摘要未变，原审批最终为空。

本轮新增实际输入2，P4累计36、全部49条；此前47条及所有已处理审批均不重放。5项真实业务写入分列为手机代理4POST和第二客户端1POST；手机仅处理第二条审批，首条旧回调零POST。零拒绝读取/写入，6个独立临时端口及reverse核对关闭，正式服务未动。本次59条根检查356.12秒通过，2份修改源码冻结；Android构建通过，既有36项审批原生辅助按相同产品/组件源码匹配复用，新增runner不冒充重跑整组。10张App独有窗口图已查看，仅局部审批/失效/结果状态，P6全视觉未关闭。未制造第二次过期审批网络提交，未声称验证刷新前竞态、桌面UI抢答、所有问题类型或完整功能；未知首次绑定的终审计数护栏属于实现，未在本新场景强行制造失败来扩大通过。F41/F43/F44/F46及完整P3/P4仍开放，旧失败和独立异步命令终态门槛保持；iOS/Windows未新增通过。蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。下一步继续B040/B042/O009等原操作恢复、结束等待及重启的剩余断言。

2026-10-24 P4-4g.1：Android真机已接受原回复在确认丢失后的恢复专项通过。专用独立代理在转发原输入后丢弃成功确认，返回504并暂时503阻断相关读取；原模型实际执行，未伪造成功历史。手机显式结束等待，原记录保留且新草稿持久保存；独立App和准确Host子进程重启后，只读查询同一原回执恢复completed，原ID、原意图/摘要、已尝试标记与detached保持。产品/Host/Adapter/KMP生产源码未改。

当前零输入预检原生1项与CLI通过；真实两阶段原生完整方法2项与CLI通过。准确原Electron owner/规范历史在结束等待、重启后查询和最终复核均恰好2条输入、两个completed Turn，默认模型/极高/快速/readOnly/on-request设置匹配，无工具或文件操作。业务写入仅第一阶段创建准备、创建确认、原回复各一次；第二阶段强制零POST，原记录页面只查原回执，原生界面及加密元数据证实新草稿未被回填或清除。ADB仅只读采样独立验收包，观察到前后App PID集合不同；Host由本验收启动的子进程正常退出后以新PID启动，原Host ID与密钥摘要保持。

首个零输入预检在进入原生用例前因模型目录请求未带Host ID返回403，原日志/0输入/子进程正常退出均保留。修正具名GET的准确Host参数，并核对ready令牌文件属于本次私有状态目录；重新预检通过。原工具版本59条检查354.48秒另留原指纹；最终工具版本59条356.67秒全通过，2份源码冻结。该工具问题不作为手机产品失败或通过，也不覆盖旧失败组。

本轮新增实际输入2，P4累计38、全部51条；此前49条及所有已提交审批封存不重放。确认丢失属于明确的独立代理故障注入，本项只验证已接受原回复分支；未声称运行中的远端任务已被结束等待取消。全部8个相关临时端口（含早期失败Host）和reverse核对关闭，三个场景的Host子进程均正常退出；正式服务未动。10张App自有主窗口/对话框图已查看，只是局部未知/结束等待/发送记录/草稿，P6全视觉未关闭。B040/B042仅此原回复恢复子断言新增通过，强否定未执行后的显式恢复、O009多个创建及未知首条并行、O012长历史证据、未加载owner人工归档和其余源节点仍开放；完整F25/F26/F43/F44/F46和P3/P4不关闭。iOS/Windows未新增本次原生或真实通过；蜂窝/VPN、人工归档单列待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。下一步继续O009创建隔离、生命周期及P4其余逐提交断言。

2026-10-24 P4-4g.2a：补齐Android独立新建的原生隔离断言。通过可控合成Provider延迟两个准确创建结果，执行真实Compose/NativeCodexFlow/加密意图账本：首项显式结束等待，第二项使用新操作ID；捕获实际提交协程Job并等其完整结束后核对旧成功/失败不能改变新等待、目标、草稿、设置或新错误。生产代码、wire和Schema未改，本项无真实模型。

三项新用例分别覆盖旧成功到达时第二项仍在途、第二项已确认后旧成功到达、第二项目标已有新错误时旧失败到达；逐项核对各原ID只提交一次、准确两个正文、原意图和摘要不变、第一项detached保持。与既有6项创建回执顺序/HTTP/加密重开及8项控制/设置组合，API26、新手机API33、API37各17项完整通过，共51项/3完整分组、4份源码冻结；没有使用全局截图。

最初API26完整17项通过，API33/37各有一项新用例在首次发送等待超时，原失败和通过组保留，未定性产品缺陷。补充自有窗口输入法隐藏后，API26/37完整组通过，但API33三项在输入法隐藏前置超时；该失败组继续保留。最终按已有真机验收方式，先完成本地恢复再安装合成Provider，使用App自身“收起键盘”清理焦点，核对窗口高度稳定和当前发送动作可用，并增加发送超时诊断。原10秒提交等待和所有结果断言未放宽；本次通过不追认前轮失败或宣称底层超时原因均已关闭。

新增实际输入0，P4累计38、全部51条封存不重放。Android构建和当前51项原生辅助通过；所有生产/JS/Host/KMP组件未变，按匹配源码复用上一检查点59条根检查356.67秒，当前一致性检查另行执行，未把新runner当成整包真实复验。O009仅客户端独立创建/迟到结果子断言新增辅助通过；真实两个原桌面Thread、创建中结束等待/第二项/晚到确认、兼容driver条件及其余源节点仍开放，完整F25/F26/F43/F44/F46和P3/P4不关闭。iOS/Windows未新增本次通过，P6全视觉仍开放；蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。下一步在相同隔离现场做两次全新真实创建及迟到原结果验收，旧输入不复用。

2026-10-24 P4-4g.2b：Android新手机完成两次独立真实创建及第一次实际Provider返回晚到的有界验收。两条全新无工具输入分别通过原生Compose、实际NativeCodexCommands、独立Host子进程和原桌面执行，分别得到两个准确原Thread；第一项显式结束等待后发起第二项，第二项目标和独立草稿建立后，才释放第一次原生真实返回。生产代码、wire及Schema未改。

测试装饰器只延迟第一次生产调用已经取得的真实返回，未伪造成功或历史；本次第一次实际返回phase=prepared，按原值登记。为排除移动端后台观察提前结束场景，仅准确第一操作ID的创建GET注入503，POST均原样转发。第一项接受证据由独立原owner及canonical历史核对，再只读查询同一原创建回执；手机准确“结束等待”后完整等待其协程结束，再通过新聊天/原项目选择UI进行第二次创建。释放第一协程前后逐项核对第二项目标、独立草稿、模型设置、忙碌状态、两个不可变原ID/正文/摘要、第一项detached及第二项confirmed保持。

零输入预检原生1项完整通过；随后真实原生1项完整通过，CLI整体通过。两条新输入分别在两个独立原Thread且各一条canonical用户输入，均completed、精确最终marker、default/xhigh/fast/read-only/on-request且无工具；两次prepare、两次submit和一次第一项detach共5个POST，原提交不重放，拒绝写/读均0。15张自有MainActivity/Dialog的PixelCopy局部状态图逐张查看，12个自有端口及ADB reverse实际关闭，独立Host正常退出，正式服务未改。当前相同App/runner、冻结5份恢复源码的20项原生恢复检查在API26、新手机API33、API37各一完整组通过，共60项；属于合成证明/原生HTTP/加密账本辅助证据，无额外真实模型，不代替真实强not-written恢复。当前冻结两份新验收源码完整59条根检查通过，耗时354.4秒；Android构建通过。上一检查点51项/3完整环境原生辅助按相同生产App及4份相同测试源码匹配复用，没有冒充本次新runner的三环境或整包复验。

首轮实际组在第二项已确认后、自有窗口截图前置检查得到0个聚焦窗口，完整原生组失败；晚到返回后的结果断言未完成，该失败不追认为通过。原组两个真实Thread/两条canonical输入已经完成并封存，原5个POST、日志及局部状态图保留，不重放。未把窗口现象归因其他App或用户操作，也未宣称底层原因关闭。补充准确自有MainActivity/Dialog窗口的10秒就绪等待及失败诊断，零输入预检增加第二次选项目/设置/草稿/窗口转换；所有原结果断言保持，最终使用两条全新输入验收。

本检查点共新增4条，其中首轮失败组2条、最终通过组2条；P4累计42、全部55条真实输入封存，不重放任何旧操作。O009新增真实两Thread/结束等待/第二项/实际旧返回隔离子场景通过，完整创建重启只读恢复、兼容共享driver拒绝与生命周期等剩余断言保持开放；B040/B042强not-written显式恢复、长历史及其余源节点继续逐项核。F25/F26/F43/F44/F46及P3/P4整体不关闭。iOS/Windows未新增本次通过，P6完整视觉仍开放；蜂窝/VPN、人工归档单列待验收，任务/项目管理暂缓，A6/P7暂停和P9依赖不变。下一步先核对结束等待失败晚到的原记录/新界面错误归属，再继续新的强not-written恢复及其不可变原许可。

2026-10-24 P4-4g.3：补齐Android原创建结束等待通知的诊断持久化与取消传播。NativeCodexFlow将非取消通知失败仅写入对应detached原记录的本地error，不改原执行phase/result、ID、正文、摘要、附件、目标或新草稿；更新前核对原记录和原诊断，保留同一原操作较新的回执诊断。取消贯穿Flow和Session，不作为通知失败保存或弹出。wire、Schema、KMP规则和界面入口未改。

与冻结iOS SessionStore的recordCreationFailure/detachDesktopCreation逐项核对：结束本地等待后，通知失败仍归属于原detached创建；iOS现有路径未改，也不新增iOS通过。旧Android会吞掉通知失败和取消。相同5项新原生用例在旧API37的完整组中4失败/1通过：三类原记录诊断缺失、取消被吞；较新原回执错误优先这一保护原来通过。当前5项在三环境均通过，并覆盖独立草稿和只读UI查询、第二创建仍在途、第二已确认目标/设置/新错误、原确认phase/result保持、原拒绝诊断优先、加密账本冷读及取消。

当前生产App上5项新用例、3项独立创建、6项回执顺序、8项控制/设置和20项原恢复在API26、新手机API33、API37各42项完整通过，共126项/3组、8份源码冻结。该辅助runner与最终真实runner分别登记；8份源码及生产App匹配复用，不宣称最终runner整包三环境重跑。当前6份变更源码完整59条根检查通过（352.53秒），Android构建及当前一致性另核。零输入真机预检原生1项、真实原生1项及CLI完整通过。

本次全新一条输入在原桌面准确原Thread/canonical历史各一次，completed、精确marker、default/xhigh/fast/read-only/on-request、无工具。prepare/submit/detach各一次共3POST；代理原样转发detach且Host实际返回confirmed+detached后，仅丢弃其确认并显式返回503。手机冷读核对原本地诊断已加密持久，新草稿/设置及全局错误保持；第一次实际prepared返回晚到后诊断和不可变意图仍保持。随后点击原查询入口，仅GET核同一个已接受回执，原记录confirmed+detached、诊断清除，新草稿和目标保持。没有重放原输入，也没有把丢失detach确认解释为任务取消。9张自有MainActivity/Dialog局部PixelCopy图逐张查看，6个自有端口和ADB reverse关闭，独立Host正常退出，正式服务未改。USB回环故障注入不等于真实弱网或蜂窝/VPN。

本轮新增1条，P4累计43、全部56条真实输入封存，旧操作不重放。O009仅新增原创建通知失败的归属/取消/显式查询子断言及本机真实有界通过；完整创建重启只读恢复、兼容driver、生命周期和其余源节点保持开放。B040/B042权威强not-written显式恢复、长历史和P3/P4完整门槛继续。F25/F26/F43/F44/F46不整体关闭；iOS/Windows没有本次新增真实通过，P6完整视觉仍开放。蜂窝/VPN、人工归档单列待验收；任务/项目管理暂缓，A6/P7暂停、P9依赖不变。下一步新的强not-written恢复及不可变原许可，继续按检查点提交。

2026-10-24 P4-4g.4：Android完成新操作的权威尚未写入→显式提交原内容有界验收。既有生产代码未改；新增原生许可/恢复辅助与独立Host验收入口，只在该新Host的准确nonce/正文/首条操作上注入一次generation前置读取失败。尚未写入证明由真实生产first-input边界根据未进入执行hook独立生成，验收器不伪造成功、历史或缺失证明。

原生Compose/NativeCodexFlow/加密账本新增四项：两个被动查询不继续前置、显式原继续使用不变正文且保新草稿、模拟撤回原许可后必须同一scope重新授权并再次显式操作、缺诊断不开放及确认后的旧回调不重复提交；与20项原恢复组合，API26、新手机API33、API37各24项完整通过，共72项/3组、6份源码冻结。撤回使用既有原生许可API，原生授权弹窗/继续交互实际执行；不宣称设置撤回页或完整隐私/语音验收。辅助与真实runner分别登记，生产App及6份辅助源码匹配，没有把最新runner写成单次整包三环境通过。

零输入预检原生1项及CLI完整通过；真实原生1项及CLI完整通过。第一次提交尝试在实际Host的generation前置失败，准确原owner/官方canonical均为零轮次；持久准备器已退出0，Host原checkpoint的writer PID、Host/runtime、原ID/指纹和not-written边界逐项核对。独立只读文件观察保存该原证明，摘要与父验收器一致。两个creation-receipts GET及一个creations GET前后仍零轮次、受控验证调用未恢复，手机自动观察也未推进提交。随后原生“提交原内容”使用同一操作ID、原正文/附件清单/设置和原许可scope；新草稿保留，未被当作原内容发送。最终同一个prepared Thread上恰好一条canonical原输入、一个completed轮次，原文逐字独立核对，default/xhigh/fast/read-only/on-request、无工具，精确最终marker；一次prepare与两次同原submit共3POST，原意图/摘要/许可和两次submit正文保持。只有证明未写入的这条新操作被显式继续，所有更早已接受输入不重放。

最终冻结4份新源码完整59条根检查通过（360.87秒），Android构建与当前一致性另核；8张自有MainActivity/Dialog的PixelCopy局部图逐张查看，6个自有端口/ADB reverse实际关闭，独立Host正常退出，正式服务未改。补充归档内容审计首版误用原桌面turns空字段，原失误保留；改用产品desktopTurns对同一份canonical turnHistory归档只读复核，精确原文/零轮次/原checkpoint/同一准备Thread通过，未增加输入，不把该解析失误记作产品或原生场景失败。USB回环不等于蜂窝/VPN或真实弱网。

F64仅关联P4既有原许可与授权不重放子断言，iOS原许可路径未改；A6/P7和语音仍保持人类暂停，没有恢复相关开发或新增其真实通过。

本轮新增1条，P4累计44、全部57条真实输入封存。B040/B042新增首条权威not-written/双GET族不推进/显式原继续及原许可有界通过；配对TLS的回复强否定、创建重启只读、兼容driver/生命周期、附件细分和其余源节点继续，完整功能与P3/P4仍开放。逐行对照另确认iOS待创建原正文/附件气泡和结束等待确认框在Android缺失；拒绝后的可见状态/返回编辑也须先验证。下个检查点优先补齐这些原创建界面/交互，完整P6视觉仍待后续。iOS/Windows未新增本次通过；蜂窝/VPN、人工归档单列待验收，任务/项目管理暂缓，A6/P7暂停与P9依赖不变。

2026-10-24 P4-4g.5a：Android补齐待创建消息原正文与附件名气泡，沿用用户气泡样式/六行展开。首次授权点击不创建气泡；显式提交后、初始权威读取等待期间，界面使用只供展示的原ID/正文/文件名快照；账本可用后按原意图摘要缓存正文与附件元数据。新草稿不会替换原气泡，按完整目标/项目隔离，确认或脱离等待后隐藏；明确拒绝才显示“未发送”并去掉spinner，未知结果仍显示“发送中…”。附件清单不依赖本地字节，保留同名条目。该展示快照没有执行、重试或恢复命令的用途，生产Host/协议/共享执行规则未改，iOS对应既有路径未改。

修改前API37的六个原生场景均复现缺失气泡，原失败组保留；最终仅调整其中一个准备阶段语义查询为查找后代文本，其余原场景保留。当前六项覆盖初始权威读取未返回、独立新草稿、项目切换、确认后隐藏、脱离等待后隐藏及拒绝/纯元数据/同名附件；另与创建隔离3、脱离等待失败5、原许可恢复4、回执顺序6、控制设置8、回复恢复20项组合，API26/手机API33/API37各52项整组通过，共156项/3组。脱离等待辅助调用既有session API，尚不代表新增确认弹窗通过。

真实DEV先做零输入原生预检，再用全新独立Host及原桌面/真机执行同一有界场景。首次受控generation前置读取失败，由生产边界记录权威not-written；独立只读原owner/canonical三次零轮次核对及原checkpoint文件留证。真机验证原消息可见、编辑新草稿后仍显示原正文/发送状态；随后明确“提交原内容”，同一原ID/意图/许可/准备Thread恰好接受一条原输入并完成，确认后原气泡隐藏且新草稿保留。父验收独立核对精确原文、原ID与两次submit正文摘要、default/xhigh/fast/read-only/on-request及无工具最终marker。共3POST（prepare一次、submit两次，其中首尝试接受0、明确继续接受1）；所有旧已接受输入保持封存。原生完整方法/CLI均通过。真实附件字节细分仍开放，本轮同名附件名/无字节为原生辅助证明。

最终7份登记源码冻结，完整59条根检查通过（362.13秒），Android构建及当前一致性另通过；三环境辅助runner与真实runner分开记录，App及被选辅助源码匹配。首次根检查在manifest格式校验失败，修正格式后重新完整通过，保留初次失败，不计作产品/真实场景失败。8张自有MainActivity/Dialog PixelCopy局部图逐张查看，6个自有端口及ADB reverse已关闭，Host正常退出，未改正式服务；局部图不等于完整P6视觉通过，USB回环不等于蜂窝/VPN。

本轮新增1条，P4累计45、全部58条真实输入封存。B040/B042及O009仅补原创建消息呈现与隔离子断言，完整功能/源节点/P3/P4仍开放。下一阶段先补结束等待的明确确认/继续等待分支，并验证明确拒绝后的返回编辑；配对TLS回复强否定、创建重启只读、兼容driver/生命周期、附件细分及其余节点继续。蜂窝/VPN、人工归档单列待验收；任务/项目管理暂缓，A6/P7/语音暂停与P9依赖不变。iOS/Windows未新增本次通过。

2026-10-24 P4-4g.5b：Android创建记录“结束等待”使用Material AlertDialog承载iOS confirmationDialog对应的确认选项和说明文案，平台弹窗呈现差异明确登记、“继续等待”和“保留记录并返回编辑”。弹窗生成一次性原请求，绑定完整目标、原ID/意图摘要及导航/连接版本；取消只关闭该请求，确认先消费准确请求对象再写原记录与通知Host。重复请求、已取消、离开或往返项目、已确认/已脱离记录的旧请求不生效。弹窗显示期间不自动叠加异步问题页；后台与验收重置关闭该临时弹窗。Host/协议/执行规则不变，iOS既有路径未改。

修改前五项原生场景均复现缺少确认框。初版辅助错误保留：把已卸载Compose ClickableNode的语义动作当作可保留产品回调，API26/手机API33各57项中4项在框架点击音效层异常；API37组的180秒验收预算在第53项截断，未计整组通过，也未归因为手机/用户问题。改为保留界面实际生成的产品原请求，在取消后调用、同一主线程连续确认两次、通知完成后再调用、切项目/往返及原确认后检查无重复通知；确认请求的一次性消费也同步实现。最终五项与气泡6/创建隔离3/通知失败5/原许可恢复4/回执顺序6/控制设置8/回复恢复20组合，API26/手机API33/API37各57项完整通过，共171项/3组、源码冻结；整组预算扩为300秒，单项产品断言未放宽。既有两个辅助及两个真实入口补上确认步骤，未重放此前双创建实际输入。

当前源码零输入真机预检完整通过后，新独立Host/原桌面/真机执行一条全新模型输入。原桌面已接受并完成一条原输入，准确原owner及官方canonical独立核对；手机收到的真实submit返回仍为prepared并仅延迟其本地返回。真机实际打开确认框并选择“继续等待”，断言没有发出detach且原记录未脱离；再次打开并确认，准确原操作只通知一次。父代理真实转发该detach，Host已持久confirmed+detached，但仅通知回包丢失；手机把该失败保存在原记录，新草稿/设置保持。释放原迟到prepared返回后仍保持新草稿，随后原生原记录菜单GET确认并清除原诊断，无重发。加密账本冷读与完整原生/CLI通过；父验收共3POST（prepare/submit/detach各一）和恰好一条canonical原输入，精确最终marker/default/xhigh/fast/read-only/on-request/无工具。结束等待不撤销原桌面已执行任务。

最终9份登记源码冻结，完整59条根检查通过（364.53秒）；初版同样通过的59条按旧指纹保留，不充作当前版本。Android构建、当前一致性另通过；本次三环境辅助与真实runner/App指纹一致。10张自有MainActivity/Dialog PixelCopy局部图逐张查看，包含实际确认框；6个自有端口和ADB reverse已关闭，Host正常退出，正式服务未改。部分窗口证明不等于完整P6视觉，USB回环不等于蜂窝/VPN。

本轮新增1条，P4累计46、全部59条真实输入封存。O009及B040/B042仅补原创建结束等待确认、取消/重复/晚到隔离子断言；完整源节点/功能/P3/P4继续开放。下一阶段先验证并补齐明确拒绝后的返回草稿与发送门槛，保存原意图且不覆盖当前编辑内容。创建重启只读、配对TLS回复强否定、兼容driver/生命周期、附件细分与完整视觉等继续；蜂窝/VPN、人工归档单列待验收，任务/项目管理暂缓，A6/P7/语音暂停及P9依赖不变，iOS/Windows未新增本次通过。

2026-10-24 P4-4g.5c.1 原生辅助检查点：Android明确拒绝的新建记录新增“返回草稿”，仅按准确原ID/摘要/目标和拒绝状态做本地账本脱离，不调用Host，也不覆盖当前草稿正文、文件与设置。拒绝时保留禁用的发送箭头，仍允许编辑及添加当前草稿附件；返回后下一次明确发送取得新ID。只有当前目标最新未脱离的拒绝记录阻止再次发送；较早拒绝记录保持可查询，但其返回/结束等待按钮禁用，避免影响较新创建。拒绝的当前记录也保留结束等待确认/继续等待分支。iOS既有对应路径未改，Host/协议/共享执行规则未改。

修改前API37六项原生场景有5失败/1通过：缺本地返回、拒绝后仍可发送、拒绝时缺结束等待入口；未知结果不冒充拒绝原来通过。最终新增9项原生Compose/NativeCodexFlow/加密账本辅助：拒绝发送门槛、保独立正文/两份本地附件/设置且原清单仍一份、未知无拒绝返回、缺原文件字节的纯元数据返回、拒绝结束等待确认、新显式ID不重放、项目往返旧请求无效、账本较新确认先到时不脱离、旧版较早拒绝不遮较新确认且旧操作禁用。与既有57项组合，API26/手机API33/API37各66项完整通过，共198项/3组，辅助源码冻结。最终API26/手机API33两组按同App、runner和全部选定源码匹配复用，API37在根检查结束后单独完整重跑通过，非同一次同时三环境。首次最终组API37在360秒验收预算附近仅得到49项完成、无断言失败的部分输出，原错误未保存killed字段，按未完成保留且不作产品/AVD/用户原因结论；重跑预算600秒，单项断言未放宽。直接调用当前原请求/局部账本用于竞态边界，均为fixture；没有把模拟Provider或本地字节当真实模型/系统Picker通过。

最终5份登记源码冻结，完整59条根检查通过（362.21秒），Android构建与当前一致性另通过。收紧旧拒绝按钮前的198项/3组及其根59条按旧指纹另保留，不充作最终版本。当前辅助APK/App和各组源码/日志摘要分别登记。此次仅为原生辅助检查点，新增真实模型输入0，未新增原桌面/真实Host的本功能验收，也未新增截图视觉通过；先提交保存已检查实现，再继续独立真实DEV阶段。

P4累计46、全部59条先前真实输入继续封存。本轮实现与辅助结果不关闭功能、源节点或P3/P4。下一项真实DEV要用全新自有Host的受控typed提交前拒绝、真机本地返回且无Host命令、显式不同新ID的一条真实输入，再由准确原owner/官方canonical独立核对；零输入整方法预检先行，旧输入不重放。源码对照另发现未知创建等待时的本地附件准备与iOS尚有差异，已列P3/P6待原生验证，不能据源码读法宣称系统Picker已通过。真实附件/纯文件及创建重启、配对TLS回复强否定、兼容driver/生命周期、完整视觉仍开放；蜂窝/VPN、人工归档单列待验收，任务/项目管理暂缓，A6/P7/语音暂停及P9依赖不变，iOS/Windows未新增通过。

2026-10-24 P4-4g.5c.2 有界真实DEV：独立自有Host在原bridge创建委托之前注入一次typed拒绝，生产Broker返回明确rejected。真机显示原正文/未发送/禁用发送箭头，独立新草稿可编辑；结束等待确认选择继续等待不脱离，随后明确返回草稿仅改变本地原记录的detached标记，保留原ID/意图/摘要/许可和当前新草稿/设置，无Host命令。原菜单两次查询不会重开等待或执行，冷读加密账本一致；Host原回执仍rejected且remote detached=false，与本地detached=true分别核对。生产源码未改。

零输入完整原生方法预检通过后，全新现场仅显式提交一次不同新ID的新正文。父验收核对prepare/submit各两次共4POST、无detach/upload；原拒绝只在受控钩子停于原bridge委托之前，新的原bridge只委托一次。原owner与官方canonical独立核对新clientId、精确新正文、一个completed模型轮及default/xhigh/fast/read-only/on-request，无工具；旧被拒正文未重放。原回执独立只读5次均保持rejected/threadId=null；新请求确认后导航准确新聊天并清空已发送草稿。完整真机原生方法及只读CLI均通过，新增真实输入1，P4累计47、全部60条真实输入封存。受控拒绝不是自然OS拒绝或通用未写入证明；USB回环也不是蜂窝/VPN路径证明。

最终8份登记源码冻结，完整59条根检查通过（354.55秒），Android构建及当前一致性另通过。App指纹与上一原生检查点一致，选定16份辅助源码仍匹配，沿用198项/3完整组的辅助证明（API37一组当时完整重跑、API26/手机API33两组同指纹匹配复用，非同时三环境）；本次真实runner与该辅助runner摘要分开登记，不声称最新runner重跑198项。9张自有MainActivity/Dialog局部图逐张查看，6个自有端口及ADB reverse均关闭、两Host正常退出，正式Host未改；未把popup查询或局部截图算完整视觉。

B040/B042/O009仅补明确拒绝、本地返回和不同新输入的子断言，完整功能、源节点、P3/P4仍开放。下一阶段继续原创建App/Host重启后仅查询原回执，以及未知创建等待时本地附件准备与iOS的差异；配对TLS回复强否定、真实附件/纯文件、兼容driver/生命周期、完整视觉及其余来源继续分项。蜂窝/VPN、人工归档单列待验收；任务/项目管理暂缓，F64仅关联既有许可/无自动提交，A6/P7/语音暂停及P9依赖不变，iOS/Windows未新增本次通过。

2026-10-24 P4-4g.6a 原生辅助检查点：Android在创建结果待核对但已结束主动提交时，允许准备当前本地草稿附件，对齐iOS既有附件菜单/importData路径；仅移除本地导入的创建等待限制，主动发送中的限制、最多十份与完整目标/导航版本约束保留。待核对原请求继续禁止再次发送，原ID/意图/摘要/许可和原附件清单不受新草稿文件增删影响。iOS、Host、协议、KMP及上传/继续规则均未改。

修改前API37完整五项有4失败/1通过：未知结果时本地添加被拦截，主动发送中禁止导入原来通过。修改后新增五项覆盖原文件上传一次、独立新文件添加/移除原草稿行、显式继续仅用原清单并保留当前正文/文件/设置；没有继续资格的未知结果只准备本地文件且仍不能发送；项目切换和往返的旧选择器目标无效；十份上限；主动提交中仍禁止导入。与六项既有附件条/真实加密文件辅助和66项相邻创建/回执/许可/控制辅助组合，API26/手机API33/API37各77项完整通过，共231项/3组，选定源码冻结。Provider为fixture、文件字节为合成数据；Compose控件断言不等于系统Picker或本功能真实模型通过。

7份登记源码冻结，完整59条根检查通过（371.11秒），Android构建及当前一致性另通过。初版新增验收器的字段名编译错误在执行前修正；第一版原生组在初始读取等待控制点未完成，保留两项失败及后续部分输出，不作产品原因结论。验收器改为提交等待控制点后，同一最终五项在旧产品完整复现4失败/1通过，再验证本次最小修改；单项断言未因产品通过放宽。当前App/runner及各组源码/日志分别登记。本检查点只新增原生辅助通过，没有新增本功能截图视觉/原桌面/真实Host通过。

新增真实输入0，P4累计47、全部60条先前真实输入继续封存。B040/B042/O009仅补未知创建等待中的独立附件准备子断言，不关闭源节点、功能或完整P3/P4。下一检查点先零输入完整真机方法预检，再用全新自有Host的generation前置故障及显式原内容继续，核对新本地文件不上传、不进原模型输入且确认后保留；由原owner和官方canonical独立核对，旧输入不重放。创建App/Host重启只读恢复、配对TLS回复强否定、真实附件/纯文件/系统Picker、兼容driver/生命周期、完整视觉及其余节点保持开放。蜂窝/VPN、人工归档单列待验收；任务/项目管理暂缓，A6/P7/语音暂停及P9依赖不变，iOS/Windows未新增本次通过。

2026-10-24 P4-4g.6b 有界真实DEV：真机在原创建generation前置故障、生产边界已有权威not-written且原输入尚未接受时，使用生产本地导入API准备下一草稿合成文件；原生文件条可见，原文件清单/本地解密字节/摘要分别核对。当前新正文和文件独立于原创建，发送仍禁用；尝试当前发送不产生额外Host命令。随后仅明确继续原内容，沿用原ID/准备Thread/意图/许可，确认后下一草稿正文、同一新文件及设置全部保留。生产源码未改；本地API导入不是系统Picker通过。

零输入完整原生方法预检通过后，全新独立Host/原桌面/真机场景恰好接受一条原输入。受控generation前置故障只制造失败，not-written由生产边界与原checkpoint证明，树外只读观察器另保存原文件快照；原owner/官方canonical在三次零轮次核对中不执行，三次原GET家族查询不继续。显式继续后父验收核对prepare一次、submit两次共3POST且两次submit摘要相同、同一原操作ID、零upload；原owner中恰好一个用户消息、clientId匹配原ID、content仅含精确原正文，模型/极高/快速/只读/批准策略正确且无工具，官方canonical另核对仅一个输入。新本地文件清单和字节摘要在继续前后相同，不在原意图或模型输入中。完整真机原生方法及只读CLI均通过；新增真实输入1，P4累计48、全部61条真实输入封存。USB回环不替代蜂窝/VPN。

最终8份登记源码冻结，完整59条根检查通过（353.68秒），Android构建与当前一致性另通过。App及此前18份辅助源码与上一检查点匹配，按指纹复用231项/3组原生辅助；真实runner单独登记，不冒充最新runner重跑231项。新增验收器自检曾发现私有项目目录少定位一层及源码指纹列表仍指向旧父脚本：首次预检在Host启动前ENOENT退出、零输入；修正后中断已失效的自有旧根检查并保留部分输出/退出回执，重新完整执行当前冻结59条，不将旧检查当通过。私有实际运行包装器也核对本轮检查/辅助证据准确路径后才允许启动。8张自有MainActivity/Dialog局部图逐张查看，6个自有端口及reverse关闭、两Host正常退出，正式服务未改；完整视觉仍未验收。

B040/B042/O009仅补创建等待中的本地附件准备及原内容继续子断言，完整功能/源节点/P3/P4仍开放。下一项为原创建App/Host重启后仅查询原回执和本地草稿/附件恢复；配对TLS回复强否定、真实附件/纯文件/系统Picker、兼容driver/生命周期、完整视觉及其余提交节点继续分项。蜂窝/VPN、人工归档单列待验收；任务/项目管理暂缓，F64仅关联既有许可/无自动提交，A6/P7/语音暂停及P9依赖不变，iOS/Windows未新增本次通过，旧真实输入不重放。

2026-10-24 P4-4g.7 有界真实DEV：Android原创建在generation前置故障后已有生产权威not-written证明，独立新草稿/本地文件/设置保存到默认加密存储；仅停止自有验收App和独立Host，再以同一Host身份/令牌启动新Host进程并正常打开新App进程。第二段不reset、不重新connect、不复制元数据、不重新导入文件，恢复准确完整目标/原操作/原意图/许可和同一新文件字节。先原生查询及独立原回执读取，重启前后保持零模型输入；随后手机明确继续同一原ID/准备会话/原正文，恰好接受一次，确认后新草稿/文件/设置仍保留。生产源码未改。

零输入预检与真实场景各完成两段完整真机方法，均核对App/Host新旧PID不同、Host身份/令牌不变及默认加密恢复。真实现场第一轮只在自有子进程的generation校验注入失败，not-written由生产边界/原checkpoint证明；树外只读观察器保存原文件快照。原owner/官方canonical五次零输入核对、两个原GET家族共五次独立查询均不执行；新Host仍保留原writer PID的原checkpoint，不能改写成新PID冒充恢复。显式继续才进入新进程的执行边界；父验收核对3POST（prepare/首次submit在阶段0，明确继续submit在阶段1）、同一原ID和相同submit摘要、零upload。原owner核对仅一个精确原正文/clientId、content仅文本、原设置/只读/批准策略及无工具，官方canonical另核对一个输入。新增真实输入1，P4累计49、全部62条真实输入封存。

最终11份登记源码冻结，完整59条根检查通过（356.56秒），Android构建及当前一致性另通过。App及既有18份辅助源码匹配，按指纹复用231项/3组原生辅助，真实runner分开登记。自有验收子进程跨重启仅保留一次受控失败的trace，并记录/拒绝明确手机声明前的意外validation尝试；任何这类尝试都会使场景失败，不能被防护拦截后算作产品通过。本次没有提前尝试，后续仅两次正常generation读取；保护不改生产checkpoint或元数据、不制造成功。本场景未发生编排/实际失败，无旧结果替代当前真实结果。9张自有MainActivity/Dialog局部图逐张查看，8个自有端口记录及reverse核对关闭、四Host进程均正常退出，正式服务未改。USB回环、本地API合成文件及受控正常退出不等于蜂窝/VPN、系统Picker、完整视觉、突然崩溃或全部Host生命周期通过。

B040/B042/O009仅新增原创建的正常进程重启、默认加密恢复、只读查询及后续明确原内容继续子断言；完整功能/源节点/P3/P4仍开放。下一项先用零模型原生场景核对创建查询的来源目标与迟到错误反馈隔离，当前只是源码观察，尚非已复现缺陷；按结果修复/登记，再继续配对TLS回复强否定、真实附件/纯文件/系统Picker、兼容driver/生命周期及其余提交节点。蜂窝/VPN、人工归档单列待验收；任务/项目管理暂缓，F64仅关联原许可/无自动提交，A6/P7/语音暂停及P9依赖不变，iOS/Windows未新增本次通过，旧真实输入不重放。

2026-10-24 P4-4g.8a 原生辅助检查点：Android原创建的显式查询校验当前来源项目、原ID/目标/意图摘要及完整连接/导航版本，旧来源记录在其他项目不能发起GET。查询回执仍写入原加密账本、保留准确线程元数据；只有原查询仍拥有当前导航和等待owner时，才可导航或清理未编辑草稿。迟到错误不进入其他目标或往返后的新导航，也不能覆盖较新错误；原本当前有效查询的错误显示、确认导航/清理继续保留。其他操作调用保持既有默认呈现，Host/协议/KMP未改。

修改前API37完整七项4失败/3通过：旧来源在其他项目仍可发起GET、迟到错误在项目往返后出现、迟到确认接管往返后的导航/清空草稿、旧错误覆盖较新错误。当前失败、另一项目错误隔离及当前确认这三项正向/相邻控制原来通过。修改后同一七项原生Compose/NativeCodexFlow/加密账本场景全部通过，与既有77项创建/回执/许可/附件/控制辅助组成API26/手机API33/API37各84项整组，共252项/3组，19份选定源码冻结。原生夹具用已定义的非回复404边界结束被动观察，再只挂起明确查询的GET，防止把后台轮询当手动查询；每项均核对只有原首条合成提交、无detach/新输入。迟到确认仍核对冷账本confirmed，不能用丢弃真实回执代替保护新界面。

7份登记源码冻结，完整59条根检查通过（367.68秒），Android构建和当前一致性另通过。此检查点仅新增原生辅助结果，零真实模型输入，P4累计49、全部62条已接受输入保持封存。没有新增本功能的真实Host/原桌面验收或截图视觉通过，也不把fixture当真实模型/系统Picker。iOS对应query目前按key/pending校验，已登记潜在同类边界需要iOS原生复核；本轮未改iOS，不据源码或Android结果宣称iOS通过/失败。

B040/B042/O009只补显式创建查询的来源/迟到呈现和错误反馈辅助断言，完整功能/源节点/P3/P4仍开放。下一阶段优先用本任务已封存、已脱离等待且原Host已关闭的专用记录做全新只读验收：先核对准确身份/Adapter/原owner/手机原账本，所有POST禁止，只GET原操作；原状态不可用时不伪造或复制账本、不重放模型输入。配对TLS回复强否定、其他迟到回调变体、长历史证据、真实附件/纯文件/系统Picker、兼容driver/生命周期及完整视觉继续分项。蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7/语音暂停及P9依赖不变，Windows未新增通过。

2026-10-24 P4-4g.8b 真实只读检查点：在当前Android代码/独立真机App中，从真实加密账本加载P4-4g.3同一已确认且已脱离等待的原创建记录，未复制/重建记录、未重放原输入。先核对准确Host/原项目、当前Adapter及原桌面版本和原owner，再以当前Host程序重开这份已停止的本任务私有状态；每次启动前均把原状态完整私有归档，逐文件读回核对摘要，保留原身份/密钥/旧验收证据。此轮产品代码与632e447相同，仅新增原生/脚本验收入口，未改Host/协议/KMP/iOS。

当前源码真机预检和正式场景各完整一项通过：预检一次真实原操作GET；正式场景由对话菜单逐次发起四次查询，三次可控503分别核对切到另一无项目草稿不弹旧错、项目往返后的新导航不弹旧错、当前有效失败仍显示原生错误Dialog，最后一次真实GET核对confirmed回执、准确原ID/线程/意图摘要及独立草稿/附件/设置保留。切换调用正常Session导航接口，不能代表完整项目选择界面验收；本地合成附件直接经生产导入API写入，冷读真实加密文件字节核对，不能代表系统Picker。等待原查询结束时只读本App原busy状态，不修改/伪造产品状态或回执。代理拒绝全部远端POST，五次查询均指向同一原ID，无其他模型输入/继续/取消/打开；USB回环和可控传输失败不代表蜂窝/VPN或自然弱网。

两轮前/后/最终共六份本次原owner与独立canonical读取，仍各只有原来一条已完成输入，原owner的clientId与原正文精确匹配；旧真实模型任务按相同Adapter/桌面/操作指纹匹配复用，此处仅新增当前真机/Host查询证据，不把旧模型执行冒充本轮新执行。七张本App MainActivity/Dialog PixelCopy截图已查看，六个自有端口关闭、两个Host正常退出。既有API26/手机API33/API37的252项/三整组辅助按相同App及19份选定源码匹配复用，原辅助runner与本次新runner摘要分别保存；未宣称使用新runner重跑252项。完整59条根检查在最终七份源码冻结下通过（353.7秒），Android构建和当前一致性另通过。新增真实输入0，P4累计49、全部62条原输入保持封存。

预检前两次因验收脚本误读不存在的可选连接状态字段未进入原生场景；第三次因验收代理错误拒绝初次未绑定workspace读取而原生失败，均零查询/零输入，旧结果和关闭证明保留，修正后才通过。另保留初版固定等待的已通过预检，不代替最终等待原协程结束的版本；首轮根检查lint空catch失败、次轮59命令虽退出0但运行期间验收源码更新导致sourceStable=false，均不计最终冻结检查通过，原日志/摘要/旧源文件保留。

B040/B042/O009仅补原创建显式查询的限定真实断言，完整功能/源节点/P3/P4仍开放。接下来优先审查被动创建观察器及其他迟到回调，不能由本次显式查询推定通过；配对TLS回复强否定、长历史原回执、真实附件/纯文件/系统Picker、未加载owner/兼容driver/生命周期、完整视觉仍分项验收。iOS潜在同类边界仍需iOS原生核对，本轮未改、未新增iOS/Windows通过。蜂窝/VPN、人工归档待验收；业务任务/项目管理暂缓，A6/P7/语音暂停与P9依赖不变。

2026-10-24 P4-4g.8c 回调草稿保护复核：新增七项确定性原生Compose/NativeCodexFlow/真实加密账本辅助，分别挂起首条submit响应和后台原创建GET。核对后来编辑文字、编辑后重新输入与原正文相同的文字、后台确认期间的文字加附件/纯文件草稿，以及原设置保留；原记录的ID/正文/意图摘要/许可/原版本及cold confirmed结果不变，只一次合成原提交、无detach或第二输入。两项正向控制继续确认未编辑原草稿可正常导航并释放，保留现有iOS相应自动确认行为。本组未复现覆盖新草稿问题，生产代码/Host/协议/KMP/iOS均未改。

API26、手机API33、API37各七项完整通过，本次21项/三整组，20份选定辅助源码冻结。既有252项/三组按相同App及原19份选定源码匹配复用，新旧runner摘要分别保存；合计覆盖273项为组合证据，没有使用当前runner完整重跑每环境91项。最终六份登记源码冻结，完整59条根检查通过（354.93秒），Android构建与当前一致性另通过。本次无新截图视觉结论，无真实Host/原owner/模型执行新增通过；新增真实输入0，P4累计49、全部62条原输入继续封存。

初版每环境七项完整运行，其中五项通过/两项失败，三组原失败及源码/runner保留。失败原因是验收错误要求在原创建等待期间更改生成设置，而真实策略明确禁用；设置在调用前后一直保持原值。本版明确断言等待时canConfigureGeneration=false、被拒绝的configure调用不改变设置，随后确认原记录和新草稿/附件/原设置都保持；没有据此修改产品或把初版失败改记通过。

本轮只补B013/B040/B042/O009中初始和被动创建回调对已编辑草稿的辅助边界；显式继续的原真实证据仍按P4-4g.6b指纹分列，本轮不冒充重跑。完整功能/源节点/P3/P4、配对TLS回复强否定、长历史原回执、系统Picker/完整视觉与兼容driver生命周期仍开放。既有附件纯文件三例按P4-3j限定指纹保留，不把泛称“剩余附件验收”抹去已有通过；Windows file-only持久化/O097另按实际含义核对。iOS/Windows未新增通过，蜂窝/VPN、人工归档待验收，任务/项目管理暂缓、A6/P7/语音暂停与P9依赖不变。

2026-10-24 P4-4h.1 配对TLS零输入预检：独立生产Host连接本机原桌面运行时，验收器自有TLS边缘从实际Host取得机器目标并提供公开描述。真机使用生产QR解析、证书钉扎、NativeHostReads/NativeCodexCommands路径；错误指纹发生真实SSLHandshakeException/CertificateException，服务端收到证书拒绝告警，手机未发布连接/保存凭据。正确指纹连接后读取真实工作区/模型并核对既有专用项目，原生选择极高/快速/只读，准备本地合成文件和未发送草稿，明确许可后原记录仍为空。全部业务POST在边缘禁止，本次无创建/回复/上传/模型输入。

最终完整真机方法一项通过，实际TLS协议TLSv1.3；Host自有创建/回执均为零，三端口关闭、Host正常退出，两张自有MainActivity PixelCopy原图已查看。App与原19/20份辅助源码匹配，分别复用252项/三组和21项/三组，共273项组合证据，旧辅助runner与新真实runner分列，不冒充当前runner整组重跑。最终九份登记源码冻结，完整59条根检查通过（359.95秒），Android构建、单文件lint及当前一致性另通过。新增真实输入0，P4累计49、全部62条原输入继续封存。

首次方法在正确配对/工作区读取后失败：验收器错误要求模型列表响应包含Host ID，实际契约只返回模型列表；因模型读取被验收器拒绝而超时，原日志/源码/runner保留、零业务POST。修正检查器后试运行完整通过，再把验收目标比较限定Agent/Host/workspace/runtime，Codex账号只作历史元数据，最终源码另跑通过；本轮没有切换账号实测。试运行、原失败及最终各自三端口均核对关闭，未把失败改为通过。

本轮为B040/B042回复恢复的配对TLS前置环境，尚未执行强否定证明、原ID显式重试或真实模型任务。TLS边缘和公开描述由验收器提供，本次不代表生产网关安装/签发、二维码相机扫描、私有设备授权握手、系统Picker、真实LAN/蜂窝/VPN或完整界面验收。配对入口通过Session生产方法，自动发现/读网络监控在本验收App内暂停，仅使用自有端点。下一步按真实原writer退出/账本/精确原输入指纹审查私有operator fence，再用全新独立任务验收GET不续发和显式原ID只发一次；普通404/进程重启不得充当强否定，既有封存任务不追加、不重放。完整P3/P4及跨阶段功能仍开放；iOS/Windows无新增通过，蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7/语音暂停与P9依赖不变。

整轮真实场景失败，以下仅为通过的原生恢复及桌面核对断言，不能作为完整场景通过。2026-10-24 P4-4h.2 配对TLS原回复恢复：全新独立验收聊天仅接受首条和原回复两条输入。原回复第一次在验收边缘明确拦截且未转发；旧Host无该回执，状态为unavailable，手机不得重试。原生另存后续草稿和本地附件，实际关闭自有App及旧Host后核对原桌面owner/canonical仍仅首条。旧writer PID、已关闭账本、writer nonce及ledger ID与精确原回复指纹共同形成单条私有operator审计证明；继任Host用自身真实PID新建checkpoint，不改旧证明。默认加密冷恢复保留配对证书/完整机器目标/许可/设置/后续草稿和附件字节，GET取得精确强否定后才显式原ID重试一次。原桌面和独立canonical最后均恰好两条，后续草稿/附件未发送。

同一最终源码零输入预检和真实场景分别两项完整真机方法通过；前者两张、后者四张自有窗口PixelCopy原图已查看。真实场景四次原生业务POST尝试中三次转发，原回复首次503未转发，原ID重试仅一次；GET自身不续发。实际极高/快速/只读/default设置及无工具调用由原owner核对。每个场景四端口关闭、两个Host正常退出，App/Host PID均真实变化。17份登记源码冻结，完整59条根检查通过（355.62秒）。产品包未变；原辅助252项/三组与回调21项/三组按各自源码及runner指纹匹配复用，共273项组合证据，不记当前新runner单次整组。新增真实输入2，P4累计51、全阶段64条，全部封存不追加不重放。 两次未列入验收器GET白名单的读取被拒绝，最终外层零拒绝断言失败；原失败日志和17份冻结源码保留，尚未定位准确路径，不能假定拒绝无影响。

真实输入前静态复核发现验收器尚未在原创建响应带回thread ID时及时登记只读范围，先前完整根检查和零输入预检按原指纹保留；最初prepare仅预留意图，不能误要求已生成线程。最终在任一带实际线程的成功创建响应交给手机前核对准备器退出/原metadata并绑定范围，另跑零输入预检及完整根检查；中间源码/检查记录保留，不冒充最终源码结果。这些前置检查未新增模型输入。 真实模型两项原生方法均通过后，外层发现两次Unknown read route，整轮明确失败。两条输入在owner/canonical均已完成并封存，结果未知输入为零；后续只读定位，不追加、不重新执行这条模型场景。

优先用只读方式定位验收器遗漏GET；不得把后续修订的零输入结果替换本次失败。私有operator证明是针对这条明确未转发原回复的人工语义审查，不是普通404/重启自动生成强否定，也不是Windows file-flush-only耐久证明。TLS边缘/公开描述由验收器提供；生产网关安装/签发、二维码相机、设备授权握手、系统Picker、真实LAN/蜂窝/VPN及全界面矩阵未由本轮验收。业务项目/任务管理暂缓，人工归档与蜂窝/VPN单列待验收；A6/P7/语音暂停和P9依赖保持。iOS/Windows无新增通过，完整27来源节点/20功能及P3/P4不关闭；下一步继续来源节点逐字段缺口、legacy/presence/大历史投影和本机原生交互验收。

2026-10-24 P4-4h.3 配对TLS只读续验：用上一阶段封存的同一验收聊天及手机原加密数据，恢复原自有TLS端点/证书/Host身份，全部产品POST禁止。诊断模式保留原白名单，准确采到终态回复分支按钮LaunchedEffect自动预读的/v1/threads/fork-options；修订仅将该具名GET纳入准确原thread范围，并为未来未知路由登记路径。生产Android/Host/Adapter未修改，原回复恢复验收器的白名单已修订，但未重跑两条模型输入。

最终冻结源码下诊断和修订模式各一项完整真机方法通过，前者准确拒绝fork-options、后者真实200并验证同目录可用；通过生产NativeCodexCommands读取，再由Session加载后核对当前终态轮次可分支，没有执行分支或新建。原记录意图/摘要/已消费标记保持，不能重试；完整机器目标、配对证书、许可、后续草稿和本地文件ID/字节保持。两轮合计六次独立原owner/canonical核对均恰好原两条输入，两张自有MainActivity PixelCopy原图已查看。20份登记源码冻结，完整59条根检查通过（354.58秒）。产品包未变，辅助252项/三组及回调21项/三组匹配复用，共273组合证据，旧runner与本次runner分列。新增真实输入0，P4累计51、全阶段64条封存。

只读首次诊断错误要求工作区GET必须带查询Host ID，实际允许省略，身份仍由证书/令牌和响应目标校验；该原失败及源码保留。修正后诊断完整通过，首次修订模式真实fork-options已200，但验收器误用原owner轮次投影id（实际turnId）导致缺字段；原失败与当时源码保留。修正为turnId并与独立canonical ID相等后，最终两模式重跑通过。旧根检查仅运行约一分钟便为这次验收器字段修正停止，只终止准确自有检查进程树，原日志/源码指纹保留，另跑最终完整59条。五轮均零产品POST/零新增输入，逐轮原Host状态先树外归档并逐文件读回核对；五轮各三个端口最终均关闭、五个Host正常退出。

上一阶段P4-4h.2的整轮模型场景仍为失败：其原报告及指纹未修改，只有两项原生恢复及桌面核对有限断言通过。本次零输入只读成功不能替换它，也不新增当前源码的全流程模型通过。既有64条原输入不追加不重放。TLS边缘/公开描述仍为验收器自有，不覆盖生产网关安装/签发、相机扫码、设备授权握手、系统Picker、真实LAN/蜂窝/VPN或完整视觉。继续来源节点逐字段缺口、legacy/presence/大历史投影与P3原生交互；完整27节点/20功能及P3/P4仍开放，iOS/Windows无新增通过。人工归档和蜂窝/VPN单列待验收，任务/项目管理暂缓，A6/P7/语音暂停及P9依赖保持。

2026-10-24 P4-4i.1 当前队列presence/legacy/长历史原生补验：只修改验收代码，队列界面通过独立加密命名空间的生产MobileSession/AgentHubApp呈现，默认真实验收App的drafts/draftFiles/draftVersions前后逐字段不变。所有截图改为自有MainActivity PixelCopy窗口。新增160条合成历史，首屏20条及本地展开80条后，客户端队列顺序/原ID和同步错误仍独立保留；本地展开不新增历史GET，刷新消除同步错误后原队列与未发送草稿保持。

API26、API37和新手机API33，各3项原生元数据编解码/加密重读/缺省与null及未知字段/已知字段类型拒绝/EasyWork规范化，以及2项原生UI方法，共15项完整检查通过。UI核对native present及旧兼容项保序，旧Host FIFO、legacy-review及removed项不显示为新可执行队列；已接受/失败回执的queueSyncError不被后续成功或普通失败关闭抹掉，电脑原错误消除后才移除提示。全部场景GET、零操作记录/零真实模型输入，17份选定源码冻结，产品包未变。九个自有窗口文件中六份唯一像素内容已查看。原辅助273项按相应App/源码/runner指纹匹配复用，与新15项组成288项组合证据，不是同一个runner单次整组。

本轮仅原生验收Kotlin和一致性登记变化，Android构建82任务、当前格式与一致性检查通过。未重跑整套59条工作区命令；上一阶段59条连续检查的20份登记源码仍逐项匹配，保留其对应辅助证据和独立指纹，不列为本轮新59条通过。旧metadata UI曾用默认会话与全屏截图的源码指纹保留，当前测试采用独立会话及准确窗口；本轮三个最终整组无失败。

这里是合成原生辅助验收，未向真实桌面制造队列同步故障或执行新的队列命令；此前真实队列任务仍按原记录封存。160条是本地历史窗口投影，不代表Host超大帧、真实原owner长对话补读、完整滚动/IME/AX或所有视觉状态。API26历史首缓存几何、蜂窝/VPN和人工归档的待验收不变。P4累计51、全阶段64条输入封存，上一轮TLS整轮模型失败继续保留；完整27节点/20功能及P3/P4仍开放，iOS/Windows无新增通过。任务/项目管理暂缓，A6/P7/语音暂停及P9依赖保持；继续其他来源字段和P3侧栏/搜索/聊天原生交互。

2026-10-24 P3-6a 修复真实聊天内完整历史查找：Android专用searchHistory请求原来遗漏必需Host ID并携带实时窗口readerId，真实Host返回403，界面仅能退回缓存搜索。现按iOS AgentClient及HostService通用URL组装对照，显式带hostId/threadId及可选cursor，完整搜索不带readerId/window；普通历史和事件阅读身份/窗口保持。Host/Adapter/iOS生产实现未改，Android仅修正该请求组装；搜索UI辅助验收改用独立加密命名空间及自有MainActivity/Dialog PixelCopy，保护默认真实草稿。

旧产品包严格查询反例HTTP400失败；仅移除readerId的中间包，又在必需Host绑定反例HTTP403失败，两份源码/包/runner/原日志保留。最终产品API26和新手机各连续14项，API37明确9项数据/规则加5项UI两组，共42项/四组通过。严格契约同时断言普通latest历史仍带readerId和latest-v1；覆盖完整分页、循环/错误/目标与晚到隔离、原生工具/压缩/长原文/附件/Diff命中、缓存失败重试、关闭弹层定位和自有Dialog返回回调。默认drafts/draftFiles/draftVersions逐字段不变。

最终产品真机通过两项完整只读方法：侧栏搜索临时展开但存储项目折叠状态恢复、同一原聊天重选；专用完整历史GET真实200后结果按准确消息ID在关闭原生弹层后定位高亮。另一项核对升级后的默认加密冷恢复：原回复意图/摘要/consumed及不可重试、后续草稿/附件ID和字节、证书/目标/许可保留。六次独立原owner与canonical核对始终原两条输入，全部产品GET，无新模型输入。原模型输入属于旧App历史证据，本次不宣称新模型任务通过。34个自有窗口文件已核对归属，10张代表图已查看。最终29份登记源码冻结，完整59条根检查通过（369.88秒），原生构建及当前一致性另通过。

首次真实只读预检在手机动作前遇到控制端口已有ADB反向映射，原映射保留，验收器改为避开已占用端口。随后漏列完整搜索具名GET的验收器失败、旧产品和中间产品的真实403/缓存回退失败分别保留，没有把缓存结果算完整读取。新增边界用例首次Kotlin字面量空格编译错误日志保留，修正后才运行反例。中间包三环境42项尝试中API37整14项有1项持续绘帧AppNotIdleException失败、13项通过，原整组仍失败；最终API37分组通过不能代替该连续整组，空闲超时根因仍未隔离。六轮自有Host状态均先树外逐文件归档读回，六Host正常退出、18个端口记录均关闭。

最终App指纹已变化，此前288项原生辅助保持各自历史App/runner/源码证据，不列为本次当前包匹配通过。P4累计51、全阶段64条输入封存，原TLS整轮模型失败报告与指纹保持，零输入成功不替换它。完整P3/P4、全部来源节点/功能/视觉仍开放；连续手势/IME/代码横滚、API37原空闲问题、API26首缓存几何及剩余聊天管理真实DEV继续。蜂窝/VPN和人工归档单列待验收，任务/项目管理暂缓，A6/P7/语音暂停及P9依赖保持。iOS/Windows无新增通过，生产网关签发、相机扫码、系统Picker与真实LAN/蜂窝/VPN不由自有TLS边缘验收覆盖。

### P3-6b 真机手势与输入法验收

2026-10-24 P3-6b 真机手势与输入法：仅扩展Android原生验收器，产品/Host/Adapter/iOS和JS工具未改，产品包与P3-6a相同。原生实际触摸覆盖左边缘打开侧栏、侧栏纵滚不切换目标、横滑关闭、聊天中部横滑不误开、输入框横滑不误开且保留正文。输入法用自有WindowInsets确认实际显示，输入探针真实保存至加密草稿，再经finally恢复原稿并核对键盘隐藏及可见高度稳定；附件字节与原已消费意图/摘要/版本保持。侧栏重开先等待关闭动画到屏外，过渡中快速重触仍未验收。

人类允许后，同一runner安装成功且安装字节SHA与准备包一致，没有卸载或清数据。最终一项完整真机真实只读方法通过，含侧栏查询/折叠状态恢复、完整历史GET200和弹层关闭后准确消息定位；三次原owner/canonical独立核对仍只有原两条输入，零产品POST/新模型输入。四张自有MainActivity/Dialog PixelCopy均查看；不代表完整视觉矩阵。当前29份源登记中仅该原生验收文件变化，原生构建及当前一致性检查通过；前阶段59条根检查仅其余28份匹配复用，本次未新跑59。前阶段42项按相同产品/执行类/其余源码匹配复用，runner不同，不记为新跑42；模拟器同runner各一项既有契约仅作安装启动验证，不额外累计。

早期手势方法通过但未显式断言键盘显示，不能替代最终断言；补断言后首次在重开侧栏的搜索禁用处失败，尚未进入IME检查。加入动画落定等待后首次runner安装超时，普通重试又明确INSTALL_FAILED_USER_RESTRICTED，两次原回执保留；人类允许后才成功。最终手势通过后的冷启动补验被桌面版本精确门禁拦下：原26.1016.61520已更新为26.1016.62342，手机方法/自有Host未启动；末次owner核对亦失败，不能推断新版本owner状态或冷恢复通过。旧失败及旧通过按各自源码/环境指纹保留。此前四轮自有Host正常退出、12个端口记录关闭，状态树外归档逐文件读回验证；版本门禁轮无自有Host/端口。

下一阶段先对新桌面版本作只读兼容性和原owner核对，独立登记后再补当前runner冷恢复；不通过修改旧报告或跳过门禁续验。全阶段64/P4共51条模型输入继续封存，不追加/重放。完整P3/P4、全部来源节点和完整视觉仍开放；物理系统返回、代码横滚/选择、连续过渡手势、剩余聊天管理真实DEV、API37旧整组空闲失败及API26首缓存几何继续待验。蜂窝/VPN、人工归档待验；任务/项目管理暂缓，A6/P7/语音暂停、P9依赖保持。

### P3-6b.1 新桌面只读核对与读取投影失败

2026-10-24 P3-6b.1 新桌面只读兼容性检查点（部分通过）：验收器增加显式准确桌面版本参数，默认仍锁原报告版本，实际版本必须完全相等；分别记录原/预期/观察版本，旧报告保持不可变。产品、Host/Adapter、Android/iOS未改。桌面新进程26.1016.62342已就绪，具名打开前核对原聊天在未归档目录中唯一且项目根相同；原入口打开后准确owner/完成轮次与canonical对账通过。最初单次发现早于界面接管，失败记录保留；后续按产品已有有界发现等待复核，不将单次未发现误报为兼容失败。

同一产品包及5e9586fd测试runner在新桌面下，一项完整真机只读冷恢复通过：默认加密原草稿、附件ID/字节、目标/证书/设置、原意图/摘要/版本及已消费不可重试保持。后续侧栏/搜索方法失败前后共五次独立owner/canonical对账仍各原两条输入；全部产品GET，无新模型输入或POST。两张自有窗口图已查看，两轮Host正常退出、六个端口记录关闭，原Host状态均树外归档读回；桌面安装文件及准确进程指纹前后一致。当前29份源码冻结，完整59条根检查通过（360.43秒）；原生包/runner未变，构建与此前42项执行类/产品匹配证据单列引用，不记本次新跑。

新桌面下侧栏方法在重选聊天后、进入完整查找前，按正文获取唯一原回复时失败，尚未执行后续定位/IME/手势，不能记整组兼容通过。独立Host持久缓存已保存八条投影：两轮各自的user/assistant同时保留旧msg标识与新item序号标识，而原owner/canonical仍各两条输入、两条助手回复；确认是读取投影身份衔接重复候选，不是重复发送。原始失败、缓存副本及SHA保留；下一检查点修复该边界，不用first/text去放宽验收，也不清缓存或改旧操作。

P3-6b原桌面版本的手势通过证据保持原指纹；新桌面的搜索/手势整轮未通过，冷恢复子项通过单列。64条历史模型输入（P4共51）继续封存，原TLS整轮失败不被只读结果替换。完整P3/P4、来源节点、全部交互和视觉矩阵仍开放；蜂窝/VPN和人工归档待验，任务/项目管理暂缓，A6/P7/语音暂停及P9依赖保持。

### P3-6b.2 原对话身份衔接与真机复核

2026-10-24 P3-6b.2 修复桌面重启后的原对话重复投影：原Adapter只处理临时序号到稳定ID的衔接；新桌面原owner重建序号ID时，旧稳定消息被当作缺失历史保留。新增反向衔接仅接受已确认completed的准确原轮次，按role/kind/回复阶段/完整正文一一对应，逐个消费匹配数量；真实同文多条、未匹配前缀、不同轮次、工具和附件保持，未知/活动轮次不能借临时ID移除稳定消息。仅Adapter读取身份规则和验收器变化，不改Android/iOS生产代码、上游历史或原提交协议。该Host共享投影影响双端；iOS/Windows本轮不新增原生通过。

旧实现新增针对性反例失败，修复后原身份边界与反向重启/同文数量/完成与未知轮次/回复阶段/工具/附件保护全部通过。对失败缓存副本只读核对得到8到4，副本未修改；实际独立DEV Host正常读取后也持久为原四条消息，无清缓存/改历史。手机沿用相同产品APK与runner，新桌面26.1016.62342下两项完整只读方法通过：侧栏临时搜索/折叠恢复及同聊天重选、完整历史真实200、原生Sheet关闭后准确消息定位、边缘/列表/输入框手势与实际IME显示/收起，再单独冷启动恢复原草稿/附件字节/目标/设置和已消费不可重试的原意图/摘要/版本。六次独立owner/canonical对账始终原两条输入；全部产品GET，零新模型输入/POST。

最终31份登记源码冻结，完整59条根检查通过（360.58秒），Adapter当前构建及当前一致性另通过。五个自有窗口文件共四张唯一图均查看；两轮自有Host正常退出、六个端口记录关闭，原状态均树外归档逐文件读回。手机APK/runner未变，无重装/卸载/清数据；前42项仅按Android产品/执行类相同保留为组件辅助，不称本次新跑42或当前完整Host链路通过。只读验收器为新Adapter增加显式准确摘要门禁，默认仍锁旧实现摘要，原/预期/实际摘要分别记录；所有POST继续拒绝。桌面安装/进程指纹稳定。

P3-6b.1原失败报告、八条缓存副本与旧反例保持，P3-6b原版本手势通过仍保留原指纹；本项关闭的是已复现的完成对话身份反向衔接子边界。原TLS整轮模型失败仍失败，零输入只读成功不替代真实新模型验收；全阶段64/P4共51条原输入不追加/重放。正式Host未部署本候选，后续按统一产物分发规则处理。未加载owner/分页缺少完成证据时仍保留未知身份历史，相关路径另列验收。完整P3/P4、全部来源节点、连续过渡触摸/物理返回/代码选择及完整视觉、剩余聊天管理/控制真实DEV仍开放；API37旧连续整组空闲失败和API26首缓存几何继续待验。蜂窝/VPN及人工归档待验，任务/项目管理暂缓，A6/P7/语音暂停及P9依赖保持。

### P3-6b.3 代码横滚、正文选择与列表手势

2026-10-24 P3-6b.3 阅读手势补验：新增独立加密命名空间的生产MobileSession/AgentHubApp原生验收器，真实SDK触摸代码横向滚动、从产品24dp边缘区内反向滚动、正文长按和选择结束柄拖动、消息列表纵滚；核对实际滚动范围/起点坐标、两个可见原生选择柄及拖动变化，不以直接设置滚动或选择状态代替手势。全程侧栏未误开，当前聊天、未发送草稿和18条合成历史保持；列表本地竖滚不新增历史GET。默认真实drafts/draftFiles/draftVersions前后逐字段不变，未触碰剪贴板或创建操作记录。

最终相同产品APK/runner在API26、新手机API33及API37各连续3项，共9项/三整组通过，16份登记源码稳定。首版9项通过但未显式量化代码横滚的全局起点，强化坐标断言后重建并重新验收，旧9项保留原runner/源码，不叠加为18。原生构建82任务及当前一致性通过；本次仅新增原生验收器，完整59条根检查引用ac4619c检查点，未新跑59，Host/Adapter/生产Android/iOS未改。当前产品SHA保持，runner已变化；旧42项与此前真实DEV仍按各自指纹保留，不称整套重跑。

iOS源码逐项对照：SidebarPanGestureView排除文本编辑控件和横向滚动容器，MarkdownBody代码ScrollView使用原生文本选择；Android生产嵌套滚动和SelectionContainer在本次实际指针验收通过。18个自有MainActivity PixelCopy文件归属已核对，三张六图接触表及三张选择后代表原图已查看，正文实际高亮扩展且草稿始终可见。选择柄用原生语义单独断言，MainActivity截图不覆盖独立Popup/系统浮动工具栏，不能据此关闭其视觉。选择测试参考AndroidX原生测试方法，且已用当前安装1.12.1 API核对；无新增依赖。

本项仅补B003/B006的代码滚动、正文选择和列表手势子边界；代码文本选择/复制、连续抽屉过渡快速重触、物理返回、完整浅深色/字体/AX/系统工具栏矩阵及剩余聊天管理真实DEV继续。API26旧首缓存几何、API37旧连续14项空闲失败保留，新的3项整组不替代旧失败。邻接F19/F24等登记不扩大P3冻结26节点/9主功能，也不将P6记完成。全阶段64/P4共51条模型输入不追加/重放，0新增模型/产品POST；蜂窝/VPN和人工归档待验，任务/项目管理暂缓，A6/P7/语音暂停及P9依赖不变。

### P4-4i.2 历史原回执、离线重开与目标隔离

2026-10-24 P4-4i.2 历史回执本轮补验：将原有6项原生验收迁移至独立加密命名空间的生产MobileSession/AgentHubApp及实际HTTP读取链路，新增超过30条原回执的加密缓存离线重开、队列同步错误显示优先级、混入其他Host/聊天回执时整页拒绝发布3项；生产Android/Host/Adapter/iOS未修改。

API26、新手机API33与API37各连续9项，共27项/三整组通过，18份登记源码稳定。覆盖当前未知与历史未知分离、同操作ID跨目标隔离、折叠/展开原文、错误在折叠区外保留、失败提示显式关闭、错误类型不强转展示字段；36条中最旧未知回执经实际加密保存和Session重建，在HTTP503离线状态仍可展开原文；队列同步错误优先显示，解除后回到原历史未知。全部请求为GET，默认真实drafts/draftFiles/draftVersions逐字段不变，无操作记录、新模型输入或重放。原生构建82任务和当前一致性检查通过；本轮没有重跑根59条，引用ac4619c的31份源码/360.58秒旧检查，不能计为本次新通过。

逐项对照iOS RootView历史回执DisclosureGroup、队列错误优先级和失败关闭交互，以及SessionStore目标筛选。静态发现Android展示层没有同样的显式筛选，但原有MobileRuntime.decode已在HTTP和缓存入口严格拒绝任意外部目标回执；实际原生验收确认整页被拒绝，原当前失败、草稿与运行时保持，未复现泄漏、未新增冗余生产修复。最初期待混合目标可加载的验收器在API37设置阶段超时，错误预期源码、runner及失败日志已保留，另有首次编译空白语法失败；均不记为生产反例。27个自有MainActivity PixelCopy文件归属和指纹已核对，三张九图接触表及四张代表原图已查看，覆盖三环境旧回执展开及新手机外部目标拒绝状态。系统Popup/浮动工具栏不在MainActivity图片内，完整视觉矩阵不关闭。

仅补B002/B019/O016/O083/O090/O097相关历史回执元数据、原文、隔离及界面子断言，不将整节点记完成；O097 Windows file-flush-only持久化、O012原owner长历史及真实进程重启仍待对应环境验收，本轮为加密存储/Session重新实例化，不冒充进程重启。旧指纹辅助/真实DEV各自保留，本轮27项不与旧批次相加声称全套重跑。全阶段64/P4共51条原模型输入封存，0新增；P3抽屉连续过渡、代码选择/复制和剩余聊天管理，P4剩余控制交互及完整浅深色/字体/AX仍开。蜂窝/VPN、人工归档单列待验；任务和项目管理暂缓，A6/P7/语音暂停，P9依赖不变。

### P3-6b.4 侧栏关闭重触隔离与真机回归

2026-10-24 P3-6b.4 修复关闭侧栏过渡中的误触：旧产品实际SDK点按主菜单原位置，会激活仍在屏幕上的侧栏电脑切换并弹出列表；保存API37反例与原APK/runner后，在保持侧栏挂载与动画的父层Initial指针事件中，消费已关闭且无拖动时的剩余触摸，防止点击/拖动流入子控件。关闭结束后仍可正常重新打开，搜索状态与独立草稿保留；Host/Adapter/iOS未改。

最终相同新产品APK/runner在API26、新手机API33、API37各连续6项，共18项/三整组通过：快速主菜单重触不误开电脑列表、实际移动搜索位置不改变已保存查询、关闭中拖动不重新打开；代码横滚与24dp边缘反向滚动、正文长按/两个选择柄及结束柄拖动、列表竖滚回归通过，默认真实三份草稿元数据不变。首轮修复结果17/18，新手机失败在验收器仍使用关闭前搜索坐标的几何前置断言；改读移动后实际坐标，保留首轮源码/runner/失败后，整组三环境重跑，不叠加为35。首次反例被最终截图焦点断言遮蔽，补显式sheet-clients断言及指针证明再次确认旧产品反例；两次原失败都保留。

新手机连接当前真实原桌面26.1016.62342，独立DEV Host/配对TLS链路下，完整聊天查找/定位、侧栏手势/输入法与随后独立冷启动读取两方法通过；六轮原owner/独立canonical核对始终仅原2条输入，0POST/0新增模型。原后续草稿、附件字节、目标/原操作ID/正文/设置/指纹与已消耗恢复状态保持；没有追加/重放原64条模型输入。此为现有原输入的只读复核，不冒充新模型或新跨机验收。原生构建82任务及当前一致性通过；本轮只改Android原生，59条根检查引用ac4619c旧检查，不计本次新跑59。

iOS对照SidebarDrawer.updateInteraction：过渡期间panelSurface禁用交互，容器仍按targetOpen或interacting接收触摸，因此早期重触可以忽略，但不能激活已关闭侧栏控件。本修复保持此窄边界，不以移动布局或重建列表替代触摸隔离。36份合成自有MainActivity文件、三张十二图接触表及四张代表原图已核对；真实DEV五个自有Main/Dialog窗口文件、接触表与一张原图已核对。选择柄/系统浮动工具栏视觉和完整动效/浅深色/字体/AX仍待专项验收；Android关闭时遮罩先消失、iOS随动画淡出的已有视觉差异继续登记，不能称整套视觉一致。

本项仅补B003/B006关闭过渡与阅读手势子断言，不关闭完整P3/P4、功能或原提交节点。产品APK已变，本轮P4-4i.2此前27项保留ae92原指纹，不能算当前新包全P4重验。代码文本选择/复制、关闭遮罩淡出、系统返回和其他连续过渡、聊天管理真实DEV、P4剩余控制/来源审计仍继续。全阶段64/P4共51模型输入封存；任务/项目管理暂缓，蜂窝/VPN与人工归档单列待验，A6/P7/语音暂停及P9依赖不变。

### P3-6b.5 关闭遮罩联动与原窗口像素验收

2026-10-24 P3-6b.5 修复侧栏关闭遮罩视觉差异：旧产品在侧栏仍可见时直接移除遮罩，实际自有窗口21个采样点亮度由打开199跳回255；保存API37原APK/runner/像素反例后，Android保留关闭中的遮罩并按同一侧栏位置淡出。关闭期间遮罩继续吸收触摸且从无障碍树隐藏，保留上一阶段关闭侧栏控件的触摸隔离；未重建列表、未改Host/Adapter/iOS。

最终同一产品APK/runner与17份稳定源码，选定API26/API37各完整7项及新手机随后完整7项，共21项分组通过（14+7）。新窗口像素场景加上一阶段全部6项触摸/搜索/阅读回归均覆盖，默认真实drafts/draftFiles/draftVersions保持。最终源码首次三环境整轮20/21，新手机一项找不到Compose界面宿主，其余6项通过；仅重跑手机整组7项通过，模拟器原7项按相同指纹复用，因此本最终源码批次共尝试28/通过27方法，不宣称三设备一次整轮21项全绿。更早19/21与18/21两批原生窗口焦点/点按未达到预期状态失败保留；验收器增加每次点按前本应用焦点门槛、打开后的状态核对和冻结时钟结束后显式推进至动画结束。未把这些失败归因用户操作。

新产品包在当前真实原桌面26.1016.62342、独立DEV Host与配对TLS路径下，新手机完整检索/定位/侧栏手势/输入法及随后独立冷启动读取两方法通过，六轮原owner与独立canonical核对仍只有原2条输入，0POST/0新模型。原后续草稿、附件字节、原目标/操作ID/正文/设置/指纹与已消耗恢复状态不变；旧64条模型输入不追加或重放。原生构建82任务及当前一致性通过；本轮仅改Android原生，59条根检查保留ac4619c旧指纹与日志，未新跑59。

逐项对照iOS SidebarDrawer.applyPosition的侧栏位移与backdrop.alpha联动。三环境在相同关闭中时点均测得199→245→255，关闭后回到原白色像素，侧栏仍可见时保留淡化遮罩。48份自有MainActivity窗口文件归属/指纹及六张分组接触表总览已核对，三环境各一张关闭中代表原图已查看；真实DEV五个自有Main/Dialog窗口文件、接触表与一张原图已核对。接触表为总览，不能代替完整视觉矩阵。本项没有重新测量iOS动效曲线，也未覆盖深色/大字体/AX或系统选择工具栏，不宣称双端逐帧一致。

仅补B003/B006关闭遮罩与触摸子断言，完整P3/P4、功能和原提交节点继续开放。P3代码文本选择/复制、系统返回、其他连续过渡与完整视觉、聊天管理真实DEV，P4剩余控制与来源审计仍按优先级推进。此前P4-4i.2历史回执27项保留ae92原产品指纹，不能算当前新产品全P4重验；本次只读复核保留当前新包证据。全阶段64/P4共51模型输入封存，任务/项目管理暂缓，蜂窝/VPN和人工归档单列待验，A6/P7/语音暂停、P9依赖不变。

物理历史性能验收使用 Release 配置的 Kotlin/Native 核心；只有独立副本设置 Swift `DEBUG` 编译条件以启用显式测量探针。正式归档保持正常 Release 条件，不带探针。先前 Debug 核心测量保留原指纹与失败结果，不能改写为 Release 通过；帧预算与真实页增长、锚点门槛保持不变。

历史分页原生回归目前包含九项：本地按钮／下拉、最新页在途时本地展开、排队远端页、排队切换丢弃、延迟第二次布局保位、中部不触发、失败重试与耗尽、在途晚到页隔离。每用例结束保存纯数值原生手势／补偿记录，脚本与实现／断言分别指纹绑定。延迟布局仅用独立DEBUG开关模拟暂态尾部112pt及最终56pt，不进入正式Release或真实历史场景。

本机独立模拟器UI验收按串行调度，避免多个独立xcodebuild同时建立表单连接。连接阶段失败与分页断言分开记录；同源码单项重跑可补齐场景覆盖，但不得将原失败整轮改记为通过。九项场景的聚合记录须核对源码／脚本指纹、实际设备、逐用例结果和零跳过。

2026-10-24 iOS 多安装包网络归属：独立验收版与正式版同时安装时，主程序的 Mach-O UUID 必须各自唯一；Bundle ID 不同不足以隔离系统网络限制。App target 关闭共享的 Debug dylib 启动壳，并将 Bundle ID 纳入 Swift module 名，分别构建后用 `xcrun dwarfdump --uuid` 核对。XCTest Runner 继续使用 `scripts/mobile/isolated-ios-runner.mjs` 的独立副本和签名核验；App 与 Runner 两者都须核对。不得通过卸载正式版、清除手机全局权限或放宽证书校验来恢复测试。依据 [Apple TN3178](https://developer.apple.com/documentation/technotes/tn3178-checking-for-and-resolving-build-uuid-problems)；各次失败与真机结果分别保留。

## 移动性能回归（2026-10-25）

`pnpm check:mobile-idle-performance`运行无变化事件与快照辅助边界；`pnpm check:ios:publication`检查实际生产片段的绑定、不可变快照、游标落盘、首次确认和48项运行态字段变化。`python3 scripts/mobile/thermal-ios-results.test.py`验证数字窗口、单位、完整覆盖及失败闭合；`pnpm check:ios:persistence`检查持久化失败边界。辅助结果不替代真机。

`thermal-ios-comparison.mjs`对准确独立App、签名产物、物理设备和当前安装路径取证；使用私有`--fixture`，不上传连接凭据或原对话。`--native-only --work-profile`只允许`testThermalMixedInteractionDEV`和`testThermalStreamingHistorySwitchDEV`；其他场景须Instruments启动。`--cpu-profile --work-profile`记录准确PID栈；持续回复原生测试支持`THERMAL_EXTERNAL_LAUNCH`，防止采样期间二次启动App。完整混合为3客户端、17步、22手势、3次400行原输入；严格实际切离时序失败保留，800行旧历史+600行新原回复的补充场景独立核对原owner运行区间。仅具名专用性能对话可输入；未知结果先核对、不重放，远端已有业务对话只读。

CPU百分比是单核等价；帧间隔不是系统hitch，充电不能用于电池放电结论。公开报告只含指标与摘要，私有XCTest界面记录留本机。详见[完整对照与负向结果](research/mobile-performance-cpu-20261025.md)。

超快模式专项：先 `pnpm build`，为独立模拟器设置 `IOS_SIMULATOR_ID`、`GENERATION_ULTRAFAST=1`，运行 `node scripts/generation-settings-desktop-ios-acceptance.mjs`。隔离工作树可用 `GENERATION_PROJECT_ROOT` 指定真实已保存项目的根目录，`GENERATION_ACCEPTANCE_DIRECTORY` 将私有记录保存到树外。核对两条 ultrafast 首条（含重启与项目继承）、快速与标准切回，以及原 owner 的唯一输入和实际 serviceTier；不支持的模型由辅助 IPC 边界拒绝。模拟器不代替真机或其他 Host 部署，见[本轮验收](acceptance/host-ultrafast-20261028.md)。

生成设置的原生测试已通过、仅独立核对需要恢复时，使用 `GENERATION_VERIFY_DIRECTORY=<original-run-directory> GENERATION_PROJECT_ROOT=<saved-project-root> node scripts/generation-settings-desktop-ios-acceptance.mjs`。该入口保留原报告，要求相同 Adapter／原生源码／断言与准确模拟器证据，只查询原 owner 和回执，不再次启动 UI 测试或提交模型输入；标准速度接受桌面规范化的 null，未知档位保持拒绝。

协调客户端私有目录的tlsPin为sha256与certificatePath嵌套对象，只接受一张公有自签PEM和精确DER SHA256。先验证源码/真实TLS与完整包，再安装本机客户端，核对目标Host/desktop-runtime和实际workspace后新建专用对话。证书不加入全局信任；标准HTTPS无pin路径保持原行为。源码包的既有声音向量按固定路径及字节指纹登记，未知音频/源码仍拒绝。
