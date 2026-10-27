# 带程序的完整接入包

完整包的顶层目录是 `codex-host-connect`，包含 `SKILL.md`、参考文档、探测脚本、`bundle.json`，以及 `assets/codex-manage` 内的程序与源码。

另有 `assets/task-hub`：冻结构建的电脑 Web、API、生产运行依赖及迁移，保留独立源 pnpm workspace/锁版本。按 [Task Hub 部署](task-hub.md) 预检后分 primary/executor；macOS primary 受包内 architecture 约束，Windows primary 不支持。安装 Skill 不会自动创建 Task Hub 数据库，也不改变 Host 身份、令牌、端口或 Serve 配置。

## 在另一台电脑使用

收到完整包后，先解压并读取 `SKILL.md`。把整个 `codex-host-connect` 文件夹安装到目标电脑的 Codex skills 目录，保留 `assets`；也可让 Codex 直接按已解压的 Skill 执行。

优先定位当前 Skill 的 `assets/codex-manage`，它就是随包交付的仓库。不要再次要求用户提供仓库地址。若用户已有运行中的安装，先保存并复用其状态、Host ID 和配置，不用随包的新目录创建重复 Host。

程序包含已编译的 Host Agent、Adapter、协议和运行依赖，无需另行安装项目依赖即可运行。目标 Mac 仍需 Node.js 24.x、pnpm 11.19.0、原 Codex 桌面；Tailscale 为跨网络双通道的可选依赖，纯局域网无需安装。`bundle.json` 的 `verifiedBuilds` 只记录历史证据，`versionPolicy=metadata-only` 表示 Codex 应用版本仅作报告元数据，不做版本预检、构建清单比较或兼容性提示；实际协议检查决定可用能力。旧包仍有版本门槛时部署规范仓库的新统一包，不能仅修改包的策略字段。包不携带 Node、Codex、Tailscale、用户身份或手机 App 安装签名。

在 `assets/codex-manage` 中检查：

```bash
node --version
pnpm --version
node --input-type=module -e "await import('./apps/host-agent/dist/runtime-server.js'); console.log('Host runtime imports OK')"
```

预编译程序无需重新构建，按 [Host 部署](host.md) 用 `pnpm service:desktop` 配置。完整源码与 `pnpm-lock.yaml` 同时提供；仅源码安装需要 `pnpm install --frozen-lockfile` 和 `pnpm build`；程序修改及代码检查在规范仓库执行 `pnpm check` 和真实 DEV 验收。不要把仅启动健康检查的 `apps/host-agent/dist/index.js` 当作原桌面接入。

程序目录需保持稳定，因为 macOS 服务记录 Node 和程序绝对路径。将程序移到稳定位置后再首次启动；更新现有安装前保留其私有 `.local`，不要用新包覆盖或复制旧电脑的状态。

## 唯一标准与远端修复回收

主开发电脑的规范仓库统一维护 SKILL、Host Agent、Adapter 和桥接工具。各电脑的安装副本仅用于部署与诊断，不独立维护源码；所有修复先在规范仓库合并、检查、构建，再由同一本机接入包分发给 macOS 与 Windows。远端已有改动先导出无凭据、无运行数据的差异，在规范仓库逐项审查回归，不能覆盖本机已有未提交工作。

启动、监督、采样和诊断工具的优化同样先反馈唯一主开发电脑，在规范仓库修改并提交，通过 GitHub 的准确提交或统一产物交付；不能在目标本地另改一版或仅凭未提交附件执行。交付记录同时保存规范源码提交、GitHub 分发提交或产物地址、文件 SHA-256 和逐机结果。公开分发只包含审查过的源码与无私有数据产物，不把内部开发历史、机器私有计划、凭据、日志或真机证明推入公开仓库。

每次部署核对并记录接入包 SHA-256，同步程序与 SKILL，保留目标自身 Host ID、令牌、原目录配置、Node 与服务路径、端口和控制聊天。核对原 Node 从正式入口实际解析到的全部运行组件：使用随包 `scripts/verify-host-runtime.mjs --entry <实际 Host dist/runtime-entry.js> --bundle <本包 bundle.json>`，读取真实 ESM 依赖闭包并比对包内指纹，不启动 Host，也不证明旧进程内存已更新；文件变化后仍核对必要重启后的新 PID 与正式入口。嵌套 `dist/node_modules` 可能遮蔽工作区中的新版，不能只核对名义 `packages` 目录；结果不匹配时保留差异并部署同一包的实际运行副本后复验。通常只重启本产品 Host；首次开启建议所需的原 Codex 正常重启另按 [建议流程](desktop-suggestions.md) 执行。逐机核对实际运行 Adapter 指纹、桌面 owner 与专项证明；不分发或复用其他电脑的私有配置、历史和通过记录。完整开发顺序见随包仓库的 `docs/development.md`。

发现指纹不一致时，先核对其他已授权部署的任务、包摘要和源码差异。正在验证的新修复必须保留，不为匹配旧 `bundle.json` 回滚；各现场分别记录实际指纹及归属，统一源码和产物完成后再复核，不能将不同版本写成同包通过。

分派完整安装／升级时同时要求 [Trace 配置与收尾](desktop-suggestions.md#每次安装与升级的收尾)：先说明原生建议同步的用途，macOS 和原生 x64 Windows 默认完成各平台持久环境和 Host 观察器，保留用户明确选择，不反复询问日志、目录或环境授权；核对就绪后给出正常重开动作。Windows 当前实例、新记录与手机效果按本机实际结果报告。只同步文档时保留运行程序、配置与匹配证明，不借此改变采集设置、重启 Host 或重跑模型验收。

接入默认按 [完整功能清单](full-functions.md) 配置新建、主动打开及全部适用能力，核对实际服务、身份和真实运行时就绪，不以 Xcode、手机在场或报告为安装门禁。真实 DEV 清单仍实际执行，配置、能力启用和实测结果分别记录，不能用健康检查代替场景通过。只同步 SKILL/文档时保留已验证程序、配置和证明，记录文档文件摘要；运行 Adapter 与客户端未变时复用匹配证据，无需重启 Host 或重跑模型。

macOS 默认完整接入还必须执行 [单实例共享通道配置](host.md#默认配置原桌面单实例共享通道macos)：随包 v2 环境工具、私有 launcher、Host 持久 `CODEX_SHARED_SERVER_URL` 与用户正常重开后的服务核对；已完成仍加载聊天的归档／删除不能留作用户报错后的可选修复。共享环境和 Trace 一次准备，保留原图标、云端与远程连接，不自动重开或中断原任务。同步顶层 SKILL、随包 `assets/codex-manage/skills/codex-host-connect` 及实际安装副本，核对文件摘要；文档修订包保留原程序字节／运行指纹及历史验收范围，记录新 ZIP SHA-256，不能把新文档当作目标配置已生效。Windows 共享安装路径尚未实现时准确报告限制，不能宣称两平台全功能。

## Windows 的准确范围

完整包包含 [Windows 原桌面程序](windows.md)：命名管道桥接、DACL、计划任务启动和真实 owner 验收脚本。Windows 已有聊天按实际 owner 直接开放回复，验收仅报告结果；保留用户明确选择的验证专用配置，旧限制先核对用途，不在升级时无条件解除。构建 26.1002.52244 已有目标 Windows 与 Mac 的核心原生联合验收和真实 iPhone 新建/回复记录，其他目标仍逐机执行完整清单，不随包复制通过证明。macOS 使用 launchd/PlistBuddy 路径，Linux 尚未实现。

在 Windows 上直接使用随包程序部署，不要求用户另找仓库或不存在的安装包。`bundle.json` 的 `windowsReplyPolicy=actual-owner` 与 `windowsAcceptancePolicy=report-only` 表示已有聊天回复按真实连接开放，验收状态单独记录；独立桥接采用 `desktopCreationPolicy=runtime-readiness` 和 `desktopAcceptancePolicy=report-only`，正式新建／主动打开同样不以报告为启用条件。默认配置两者及全部适用能力，真实验收另按完整清单实际执行，准确区分安装、能力启用、代码检查与实机通过。不得自动切换为托管执行来代替原桌面控制。

## 重新生成完整包

在拥有 Node.js 24.x、pnpm 11.19.0 和 macOS `ditto` 的构建电脑执行：

```bash
TASK_HUB_BUNDLE="/path/to/frozen-task-hub-artifact" pnpm package:connect
```

输出到忽略的 `.local/skill-distribution/codex-host-connect-complete.zip`，并生成 SHA-256 文件。构建脚本采用明确的源码目录和运行依赖清单，排除 `.local`、真实环境配置、日志、历史、密钥和本机验收证明；不直接压缩整个工作目录。程序只包含本产品实现，不包含 Codex/Tailscale 官方安装程序。

## 建议采集代码与平台范围

完整包包含 `scripts/desktop-suggestion-trace-launch.mjs`、macOS 的 `scripts/desktop-suggestion-trace-environment.mjs` 与通知观察器、Windows 的 `scripts/windows-suggestion-trace-environment.mjs` 与原生 DACL／当前实例 helper、编译的 `DesktopPredictionTraceReader`／`DesktopSuggestionObserver`，以及手机灰字／本机草稿采用源码。原生 trace、通知结果、凭据、设备绑定和验收记录均不随包分发。`bundle.json.originalDesktop.suggestions` 记录两平台实现、默认持久安装方案、实际验收范围及客户端重启要求，元数据不作为运行时能力开关。

首次开启顺序以 [建议流程](desktop-suggestions.md) 为准：先检查现有配置，说明用途与本机保存范围；macOS 默认安装并核对固定登录环境，配置 Host 使用同一实际目录，再交付用户择机正常重开的动作，不使用一次性等待器。仅用户选择本次诊断时准备私有意图、启动器和备份，实际启动独立 `--wait-and-launch` 并核对存活、`waiting-for-normal-quit` 与截止时间后才提示退出。`.command` 只是正常退出后的手动入口，生成文件或 prepare/check 成功不证明等待器运行；未知启动先核对，不重放。打包器从规范目录复制顶层 SKILL 和 `assets/codex-manage/skills` 内来源；只更新文档时核对副本摘要，保留现有运行配置和匹配证明。

按同一 ZIP SHA-256 分发到各目标。超过文件传输单文件上限时，只拆分同一 ZIP 字节并逐块记录 SHA-256，接收后先还原并核对完整 ZIP SHA-256，再解压部署；不是生成各机不同的程序包。升级只按变更文件合并，保存旧文件和本机独有差异，不覆盖 `.local`、私有配置、Task Hub 数据、其他工作树或目标机独有文件。手机已有正式 App 与连接优先保留。
