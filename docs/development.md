# 开发指南

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

物理历史性能验收使用 Release 配置的 Kotlin/Native 核心；只有独立副本设置 Swift `DEBUG` 编译条件以启用显式测量探针。正式归档保持正常 Release 条件，不带探针。先前 Debug 核心测量保留原指纹与失败结果，不能改写为 Release 通过；帧预算与真实页增长、锚点门槛保持不变。

历史分页原生回归目前包含九项：本地按钮／下拉、最新页在途时本地展开、排队远端页、排队切换丢弃、延迟第二次布局保位、中部不触发、失败重试与耗尽、在途晚到页隔离。每用例结束保存纯数值原生手势／补偿记录，脚本与实现／断言分别指纹绑定。延迟布局仅用独立DEBUG开关模拟暂态尾部112pt及最终56pt，不进入正式Release或真实历史场景。

本机独立模拟器UI验收按串行调度，避免多个独立xcodebuild同时建立表单连接。连接阶段失败与分页断言分开记录；同源码单项重跑可补齐场景覆盖，但不得将原失败整轮改记为通过。九项场景的聚合记录须核对源码／脚本指纹、实际设备、逐用例结果和零跳过。
