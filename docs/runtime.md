# 完整运行时连接与开发验收

2026-10-31 Windows 建议采集候选：原执行器的输入、输出先建立管道，收到原响应后才启动固定采集 Worker 的身份检查。配置与实际 Node→启动器→原桌面链、原 IPC owner、固定私有根和 vendor-log 根由原生 scope 验证；持久读取不改变六秒 producer 租期。启动、每次原生响应、阶段及最终发布均有期限；队列超限、坏帧、原生错误或未知退出关闭观察器并保留可核对状态，原字节继续转发，未就绪前的帧不重放。当前仅为源码候选，真实部署及验收状态见 [专项记录](acceptance/host-native-suggestions-20261031.md)。

2026-10-29 Host 错误响应现在附诊断 ID 和具体分类，HTTP 存活、bridge／reader 就绪与存储状态分开；日志异步有界写入、只读恢复与显式停止边界见[诊断与恢复](host-reconnect-diagnostics.md)。本次为源码候选，Windows 与手机未验收，不能将所有 503 显示成 Codex 断开。

## 可选模型代理（F74，默认关闭）

图像生成／编辑使用单独的 `HOST_RESPONSES_PROXY_IMAGE_MODELS` 允许清单；默认为空，升级不自动开启。配置准备器可传 `--image-models '["gpt-image-2"]'`，也可同时保留文本 `--models`。图像默认 600 秒超时、并发 1、32 MiB 请求预算；变量、OpenAI SDK 格式、偏好适配和返回限制见[图像代理指南](host-images-proxy.md)。

代理不创建或恢复桌面对话，不执行工具。安装、升级、手机接入均不得自动开启；仅在人类明确要求后设置 `HOST_RESPONSES_PROXY_ENABLED=1`。未设置或为 `0` 时不读代理凭据、不运行认证辅助进程、不创建代理文件、不增加监听。

先构建，再准备配置；下面命令只生成私有配置、独立 API Key 和专用 TLS 身份，生成的开关仍为 `0`：

```bash
pnpm configure:responses-proxy -- --state-dir '<private-proxy-directory>' \
  --models '["<available-model-id>"]' --bind 0.0.0.0 \
  --addresses '["<host-LAN-IP>","<host-VPN-IP>"]' --port 8797
```

读取生成的 `configuration.json` 的 `environment`；只有用户要求启用后，才将开关改成 `1`，连同其余变量传给现有 `scripts/desktop-service.mjs restart` 或 Windows 服务管理器。服务脚本保存显式变量，后续升级保持选择；传 `0` 并重启关闭代理，保留原密钥和回执。不要把 Codex Token 当代理 Key，不在命令行或报告打印密钥。

| 变量                                    | 默认／约束                                 |
| --------------------------------------- | ------------------------------------------ |
| HOST_RESPONSES_PROXY_ENABLED            | 未设置／0 关闭；仅 1 开启                  |
| HOST_RESPONSES_PROXY_BIND               | 127.0.0.1；显式选择 LAN/VPN IP 或 0.0.0.0  |
| HOST_RESPONSES_PROXY_PORT               | 8797，独立于原 Host 端口                   |
| HOST_RESPONSES_PROXY_MODELS             | 文本 JSON 模型清单；与图像清单至少一类非空 |
| HOST_RESPONSES_PROXY_STATE_DIR          | Host 状态目录中的 responses-proxy          |
| HOST_RESPONSES_PROXY_TOKEN_FILE         | 代理状态目录中的 access-token，独立 Key    |
| HOST_RESPONSES_PROXY_TLS_CERT / TLS_KEY | 非回环监听必须同时配置；私钥私有保存       |
| HOST_RESPONSES_PROXY_MAX_BYTES          | 8 MiB，最多配置 32 MiB                     |
| HOST_RESPONSES_PROXY_CONCURRENCY        | 4，范围 1–16                               |
| HOST_RESPONSES_PROXY_TIMEOUT_MS         | 180000，范围 1000–900000                   |

调用端使用 `https://<host-IP>:8797/v1` 和独立 Key，并信任专用证书，保持完整证书和主机名校验。证书过期或地址变化由管理员显式维护，不自动旋转身份。Node 后台可只读挂载证书并用 `NODE_EXTRA_CA_CERTS` 增加单个信任来源，不能关闭 TLS 校验。浏览器经自己的后台调用，不直接持有代理 Key。

建议每次提供稳定 `Idempotency-Key`，未知后查询 `/v1/requests/<id>`，不另起 ID 重试。省略时 Host 生成 ID 并通过 `X-Request-Id` 返回，但无法阻止调用方另发新请求，调用方须关闭 SDK 自动重试。Chat 流在完整验证上游结束后才发出 finish_reason／[DONE]，客户端按结束标记断开不会中断上游完成回执。Host 不自动重试、换账户或换计费方式。显式启用后若代理配置或监听失败，只记录代理 unavailable，不停止现有桌面 Host。4096 条元数据回执达到上限后拒绝新操作；管理员先保存和审查历史，不自动清除旧 ID 腾空间。iOS／Android 页面及默认连接行为不变，不自动获得代理入口。

2026-10-28 Windows 安装预检：按用户要求移除桌面与 CLI 的 Publisher／签名文案／Store 包身份门禁；同一管道句柄、当前用户与登录会话、准确路径／摘要及真实 IPC 方法检查继续执行。私有状态 bootstrap 与常驻 worker 共用文件句柄实现，只读所有者与 DACL、只写受保护 DACL，保留 SACL，不提权或接管他人文件。源码与辅助检查不代表目标 Windows／手机功能通过，见[专项记录](acceptance/windows-install-20261028.md)。

> **组合源码状态（尚未正式部署）**：开发现场已合入 `5b100a1` 与 `2026-10-18T16:28:41.607Z` 的不可变队列修复采样，并补充 EasyWork 发送／查询轮次一致性与拒绝空／重复执行证据的回归。当前正在执行组合检查，最终源冻结、Windows／实体手机验收和统一发布分别记证；旧版本及其他电脑的结果不代替组合版本通过。

实现日期：2026-10-07。早期兼容性基线：Codex CLI 0.160.1；本机升级后使用 0.162.0-alpha.2 重跑验收。上游 App Server 是实验性接口，版本升级需要重跑验收。

Windows 后台启动器、任务迁移／回退和真实验收边界见 [后台启动](windows-background-launch.md)。

## 独立空会话桥接（默认）

2026-10-09 默认实现为 `CODEX_DESKTOP_PREPARED_BRIDGE_CONFIG`。Host 仅通过官方 API 准备零 Turn 元数据；准备进程退出后，以已有会话 URI 打开准确 ID，原桌面独占执行首条及后续消息。配置不含控制聊天 ID，普通安装或控制聊天的归档不参与运行时就绪判断。完整配置和验收见 [独立空会话桥接](../skills/codex-host-connect/references/prepared-bridge.md)。旧桥接仍用于兼容，其证明不能用于新实现。

回复提交前主动发现准确目标的原 owner 并重新订阅完整快照；同一 owner 的发现响应不代表旧运行缓存仍然新鲜。该桌面协议在重复 `following=true` 时返回完整快照；同一 owner 的修订计数可能随历史流重置，只有新输入标识及准确末轮连续性证实向前推进，才接纳较小修订的完整快照。已退出流及其已知增量链不再覆盖新流，未知缺口仍撤销可控资格并重新读取。重复消息 ID 先核对原不可变回执，不重复执行；只读恢复也不重放模型输入。

正式独立桥接按本机私有配置、Host ID、私有权限与真实运行时就绪开放 `desktop:creations`、`desktop:creations:projectless`、`desktop:open`，无需 verification、Xcode、iPhone 在场或测试报告。正常安装省略 `validationOnly`，用户明确的验证专用配置保留；报告缺失／过期不关闭能力。每次实际操作仍核对原 owner、准备进程退出与持久去重。

真实 DEV 验收继续按最新 `AGENTS.md` 实际执行，绑定已加载代码摘要与同一目标；独立桌面/HTTP 验收和正式原生手机验收分别记录。旧构建通过、fixture 或没有 owner 的历史读取不能代表本次完整能力已验证。以下旧控制聊天／UI driver 的证明门槛仅适用于对应兼容实现。

## 旧控制聊天桥接（兼容）

2026-10-08 已接入正常启动下的原桌面控制聊天 driver，真实 iOS 验收记录见 本机验收（本地文件 `docs/acceptance/ios-local.md`）。未配置且未完成真实门槛的 Host 继续关闭新建。`pnpm check` 包含 journal/附件/并发/超时/进程崩溃恢复、真实工具结果映射和原 cwd 下载边界的 fixture 检查；fixture 不能启用正式能力。

保持官方图标、Dock 或 Spotlight 启动，登录后准备一条原桌面控制聊天，并让它调用内置 list_projects 后结束就绪轮。本地 `CODEX_DESKTOP_CONTROLLER_CONFIG` 指定该聊天及验证记录；只启动本产品 Host，不提供 Codex 启动器。

配置文件由当前用户持有且不可被其他用户写入，示例使用占位符：

```json
{
  "hostId": "<host-agent-id>",
  "threadId": "<original-desktop-controller-thread-id>",
  "desktopVersion": "<verified-desktop-build>",
  "verification": "/path/to/private/controller-verification.json"
}
```

verification 要求 kind=real-desktop-controller、hostId、controllerThreadId、desktopVersion、当前 Host 进程启动时的 adapterSha256，六项 gates 实际通过及 unloadedOpen=true。摘要在模块加载时固定，开发构建替换磁盘文件不会改变仍在运行的 Host 的验收版本；重启加载新构建后仍要求对应证明。真实 XCUITest 与独立 owner/工具结果/首条字节核对完成后才生成；不能以手填 true、fixture 或独立执行实例替代。仅测试阶段可用 validationOnly=true，普通 iOS 不接纳 testing capabilities。

```bash
CODEX_DESKTOP_CONTROLLER_CONFIG=/path/to/private/controller.json pnpm service:desktop restart
```

控制聊天需在原桌面已加载；关闭、Host 加载新构建或验证记录失效后，创建和主动打开关闭，已连接用户会话继续按自身 owner 判断。创建状态分别报告配置、验收记录、版本摘要与控制聊天未加载，只有 owner 未加载时提示打开控制聊天。冷启动自动加载控制聊天尚未验证，必要时在正常启动的原桌面打开控制聊天。创建增加模型调度与一个初始化轮，原生首条用户输入在初始化完成后发送；不能宣称零 Turn 创建。原桌面保持原历史，手机隐藏本产品明确标记的初始化轮。

创建意图在 Adapter 内同时保留确认过的 native projectId 和目录，多个已保存项目可以共享目录。提交与恢复验证所选 ID，而非要求整个目录只有一个项目；用户端仍只传领域 projectId。旧 journal 保留原指纹与保守的唯一目录判断，不自动修改或重放旧操作。Adapter 摘要包含创建 journal 实现。

桌面发送缓存也须符合当前 renderer 的输入格式：文字正文和文件说明必须带 `text_elements` 数组，Adapter 在 start/steer 时补齐缺失的空数组。模型回复成功或服务端规范化的 `userMessage` 不能代替这项检查。旧版本已加载聊天出现同类渲染错误时，本轮通过官方归档／恢复／导航重新读取历史清除坏缓存；不直接编辑历史或安装包，操作前确认目标空闲。该恢复不删除消息，不能用于自动停止其他用户正在执行的聊天。

以下为未启用的旧 UI driver 研究配置，不是当前工作入口：

`pnpm test:desktop-creation:ios` 启动本机隔离 HTTP fixture，使用真实 iOS 模拟器验证新建待确认、重启恢复和查询后渲染，并核对首条只发送一次。它不调用 Codex 模型、不操作原桌面；专用 metadata 端口为 8796，真实 Host 端口随机分配。测试数据使用单独的 App 验收存储。

后续真实 PoC 需要先在允许操作的原桌面上校准 UI profile。profile 按固定构建保存 Codex 模式、输入区、输入框、新聊天标记、发送、明确 standalone 入口、精确工作目录标记、文件选择器和“复制深链接”的 AX selector；每个 selector 需唯一 role 加 identifier 或精确 labels。没有真实 UI 证据时不猜选择器；当前未保存启用 profile。

生产配置在 Host 本地使用 `CODEX_DESKTOP_CREATION_HELPER`、`CODEX_DESKTOP_CREATION_PROFILE`、`CODEX_DESKTOP_CREATION_VERIFICATION` 三个绝对路径，文件必须由本用户持有且不可被其他用户写入。verification 必须记录 kind=real-desktop、desktopVersion、verifiedAt、helperSha256、profileSha256，以及 gates 中 project、empty-project、projectless、first-attachments、owner-controls、recovery 全部实际通过。不能通过手填 true、fixture 或独立执行实例绕过验收。

旧 driver 的校准/真实专项验收仍未完成。当前控制聊天入口声明 desktop:creations、desktop:creations:projectless 与 desktop:open；原来的项目创建与 `POST /v1/threads` 仍拒绝。新流程暂存首条内容和附件，以实际工具结果接入同一真实会话。API 见 [运行时协议](protocol-runtime.md#实验性原桌面创建操作)。

此前 UI 路线的真实验收被电脑操作工具访问原桌面的安全限制阻止；未执行原生 driver 控制该 App。当前控制聊天通道通过原桌面自己的内置工具工作，没有采用该 UI 路线，二者的验收记录分开保存。

## 原桌面单实例共享服务（macOS，默认完整接入）

`scripts/desktop-shared-environment.mjs` 提供 `install/status/apply/disable`。macOS 首次接入、增加电脑、完整修复及升级由 [codex-host-connect Skill](../skills/codex-host-connect/SKILL.md) 默认配置此路径，不要求用户再次选择；明确缩小范围或 CLI 配置冲突时保留原选择并报告限制，只同步文档不改变现有配置。版本 2 安装器只设置当前用户的 `CODEX_CLI_PATH`，并注册恢复该本机路径的登录任务；不启用全局 `CODEX_APP_SERVER_FORCE_CLI`，以保留原 durable／云端与远程连接。迁移时仅移除私有清单及摘要证明属于旧版配置器的全局强制开关，后续用户设置保持；它不启动、退出或复制 ChatGPT。用户从原 Dock、Spotlight 或原应用正常重开后，原 Electron 通过本机窄启动适配器启动唯一官方执行服务，保留真实原生工具环境，Host 以第二条连接管理相同会话。正式使用保持一个原应用实例、一个 Dock 图标。

```bash
node scripts/desktop-shared-environment.mjs install \
  --program /path/to/stable/codex-manage \
  --executable /path/to/original/codex \
  --url ws://127.0.0.1:57443
CODEX_SHARED_SERVER_URL=ws://127.0.0.1:57443 pnpm service:desktop restart
node scripts/desktop-shared-environment.mjs status
```

Host 保持 `desktop-runtime`：发送、停止、审批、创建、打开、原生队列和建议仍使用原桌面链路；`CODEX_SHARED_SERVER_URL` 只把五项固定聊天管理操作连接到同一官方服务，不降级为独立元数据写入。启动环境、Host 在线、原桌面已实际切换与原生功能验收分别记证。旧桌面尚未重开时不能宣称已接通；已有任务不得被强退，未知操作不重放。配置器拒绝覆盖其他 CLI 设置，并提供 `disable` 恢复；本机 CLI 设置由同用户后续 GUI 进程继承；旧版本迁移不会修改已运行进程的环境，因此仍需正常重开原应用，不修改官方安装包或历史。

本次已实现安装适配及边界回归；正式切换与本次模拟器验收结果另行记录。Windows 未启用该路径。

## 同一执行进程研究原型

此前 [原桌面同进程 stdio 代理 PoC](research/desktop-stdio-poc.md) 已被“官方启动方式不变”的产品约束排除，准备与启动器停用。通信 fixture 保留为研究记录，不能启用正式 Host/iOS。正常启动时的工具管道只读探针被独立 Host 身份校验拒绝，后续见 [正常启动入口研究](research/desktop-normal-start-entry.md)。

## 已实现的功能

- 多客户端切换，电脑和对话共同确定发送目标；独立草稿、历史缓存与待确认发送记录。
- 新建项目（授权目录内的已有文件夹）、新建持久对话、模型与推理设置、真实多轮文字回复。
- 系统 PhotosPicker 和 Files 导入；照片转为 PNG/JPEG；单文件 20 MB、单条最多 10 个附件。
- 原始文件上传、SHA-256 双端校验、原生 `localImage` 输入；普通文件以电脑受控路径交给工具读取。
- 真实消息增量、命令输出、工具结果、文件 Diff、计划步骤和执行状态；Markdown 标题/列表/引用/表格、代码块复制、图片预览、Quick Look、下载与系统分享。
- 执行中补充指令、完成后发送、取消排队、停止指定 Turn、命令/文件审批与问题回答。
- 唯一消息 ID 去重、持久提交记录、明确的结果不确定状态；前台长轮询、退避重连与权威快照补同步。
- Bearer 密钥校验；iOS Keychain；远程 HTTPS，回环开发 HTTP。非回环监听必须提供 TLS 证书。

这些是直接连接 Host Agent 的功能。Relay 仍只有健康检查；未实现公网中继、扫码配对、APNs、内容端到端加密、分片续传、后台上传或 App Store 分发。

## 接入模式

`managed-runtime` 显式启动 Agent 自己的 stdio Codex 服务，仅管理本服务创建并登记的对话。电脑上的其他桌面对话不会自动纳入，也不会为它们启动第二个执行实例。项目目录与附件根目录由启动者授权。

托管进程显式设置 `tools.update_plan.enabled=true`，让模型能提交真实计划步骤；默认配置是否开放该工具会影响计划事件。该覆盖只用于本服务启动的进程，共享模式沿用已有服务的工具配置。上游字段见 [官方配置 Schema](https://raw.githubusercontent.com/openai/codex/main/codex-rs/core/config.schema.json)。

`shared-runtime` 的本次 DEV 实现通过显式 `CODEX_SHARED_SERVER_URL=ws://127.0.0.1:<port>` 直接连接同一个官方 App Server，一帧一个 JSON-RPC 消息；关闭 Host 只关闭自己的连接。原先 `app-server proxy` 是控制 socket 的字节转发，不能用于转换 WebSocket。未提供本机共享地址时失败，不另起执行服务。

macOS DEV 的 `scripts/desktop-shared-runtime.mjs` 是原桌面的本地 executable override：保留初始化前的原生检查调用；正式执行服务由原 Electron 子进程环境启动，桌面 stdio 转接到该服务，Host 再用第二连接接入。工具上下文由原桌面自然提供，禁止从别的进程读取、复制或伪造工具管道和调用者身份。相同桌面的辅助连接复用同一服务，配置或归属不符则拒绝。本次启动器、发送／停止／审批／内置工具，以及后台保留、已完成、已停止对话的归档删除已完成真实原生模拟器验收，详情见 mobile-parity 验收记录。当前仍为隔离 macOS DEV，未安装为正式入口，不能据此声称其他平台和全部旧功能已通过。

正式方案只保留用户原来的一个桌面应用实例及一个 Dock 图标，不额外打开同名应用。隔离 DEV 可以使用独立配置的第二实例，结束后必须退出；它不能成为正式运行依赖。发送、停止、审批、内置工具及后台保留／已完成／已停止对话的归档和删除必须逐项真实验收，通过前不更新正式 Host 或手机。

`history-only` 保留原先的专用只读路径，UI 禁用新建、发送和上传，状态为未知。

`desktop-runtime` 是单独的实验性桌面协调 IPC 适配，历史实机验证环境包括 **26.930.61225 / CLI 0.160.1** 和 **26.1002.52244 / CLI 0.162.0-alpha.2**，这些记录不作为版本门槛或提示清单。通过桌面已有的用户私有 socket 查找会话 owner，订阅版本 11 的快照/patches，再将回复、指定 Turn 停止、命令/文件审批与问题回复转发给同一个 owner。没有接管私有 stdio、修改安装包、重启桌面或启动第二个执行任务。

这不是 OpenAI 承诺的公开稳定接口。`CODEX_DESKTOP_VERSION` 可省略；不检查 Codex 应用版本，不对照已验证构建清单，不发版本兼容性提示。能取得的版本仅作验收元数据，读取失败不影响启动；独立桥接的 `desktopVersion` 可省略，旧值无需校正。socket/父目录所有权、权限、IPC 初始化和原 owner 仍必须有效。stream 版本不匹配、owner 退出或修订号缺口会撤掉该会话的控制能力，缺口请求新的权威快照，不继续应用失序增量。断开和超时保留 uncertain，不自动重发。Windows 另检查安装归档中的实际方法版本和管道身份；已有聊天的回复、停止和审批不受专用验收 Thread 或报告限制。验收记录单独标为未验收、匹配通过、过期或不可读取，并绑定实际安装版本／加载构建，不信任旧环境标签；独立桥接不以报告状态关闭能力，旧兼容 driver 保持其证明规则。

原桌面模式使用只读 reader 列出项目和历史；reader 绝不 resume/start/submit。只有桌面 owner 的状态快照可证明任务在运行。默认独立桥接按已知准确 ID 使用官方会话 URI 打开未加载聊天，并在原 owner 可用后允许回复；旧控制聊天入口仅作兼容。Host 订阅关闭可能卸载已完成会话的 owner；对本桥接已确认创建的目标，新的回复会在去重和只读发现后按原 ID 重新取得 owner，不准备另一条会话，也不重发已有或未知输入。原桌面沿用自身权限、模型与工具设置；手机运行中发送可选排队或立即追加：排队只提交到客户端原生队列，客户端确认后才显示已排队，并由客户端调度；Host 仅保存不可变意图、去重与回执，不维护执行 FIFO 或自动续发。立即追加经原 owner 的 steer 进入当前 Turn。手机可为下一轮选择模型、支持的推理强度与权限模式；原生排队沿用当前桌面设置，无法表示的覆盖明确拒绝。新增桌面项目仍未开放。

对原桌面已加载、但 reader 列表遗漏的 agent-created 聊天，显式 `GET /v1/history` 可通过有效的原 owner 快照登记到本产品缓存；ID 与 local 不匹配或未知目标无 owner 时拒绝。主动打开使用独立 POST 操作，不由 reader 恢复执行；不会写官方历史或项目元数据。原生首条消息、初始化轮及控制聊天生命周期见 [正常启动入口研究](research/desktop-normal-start-entry.md)。

```bash
CODEX_RUNTIME_MODE=desktop-runtime \
CODEX_EXECUTABLE=/path/to/installed/codex \
HOST_AGENT_ROOT=/path/to/authorized/projects \
HOST_AGENT_STATE_DIR=/path/to/private/desktop-bridge-state \
HOST_AGENT_NAME="My Codex Desktop" \
HOST_AGENT_PORT=8794 \
pnpm dev:runtime
```

桌面 IPC 接收器按四字节长度头逐帧处理，不再在每次 socket 分片到达时复制整份接收缓存。完整 payload 直接解析；碎片 payload 按已校验长度分配一次，每个正文 byte 最多复制一次，单帧接收上限为 256 MiB，超过该值且不大于 512 MiB 的帧有界丢弃。半帧在连接关闭时释放，各连接不共享接收缓存。`pnpm check:desktop-framing` 验证碎片、合包、非法长度、断线与接收复制量；辅助性能测量和真实桌面／原生结果分别记录在 接收缓存验收（本地文件 `docs/acceptance/receive-cache.md`）。

原桌面自身可能在发送前丢弃超大广播；此时接收端没有 skipped-frame 记录也不能证明成功。最近页使用独立只读历史作为进度恢复路径，明确区分 `live`、有实际读取时间和十秒租期的 `history`、以及 `unavailable`。手机保留实时状态不可用提示，不据此开放命令；完整快照恢复退避，未知输入不重放。

`live-window` 是有界的实时观察：当前原 owner 的连续增量只更新身份已核实的完整消息，历史最近页独立核对当前 Turn。最多保留两个 Turn、每轮三十项、单项 256 KiB；未知祖先不补造，修订缺口丢弃部分基线。曾取得的完整快照可提供有界连续基线，避免旧格式历史的临时消息 ID 破坏恢复；没有基线时仅通过准确历史消息 ID 绑定 opaque entity 与 Turn。原 owner 的小型设置只读请求续租连接，不能授予 `hasOwner` 或命令资格。电脑断开、owner 变化、历史过期或身份不能对齐时退回明确的历史／不可用状态。完整原始快照仍受官方广播上限约束，观察结果不称为完整桌面状态。

分页历史中的旧未知回执通过独立只读 `thread/read` 对账，不再要求原 owner 加载完整历史。只有准确操作 ID、正文 SHA-256、线程及唯一 Turn 接受记录匹配的纯文字输入才恢复回执，原轮中断保留 `interrupted`，不能改称业务动作全部完成。读取失败和正文不符保留未知；后续轮仅作为历史归属，不能证明旧消息执行。电脑原生队列可独立只读刷新，Host 遗留项明确标记为仅供核对；无论原生队列未就绪、已可用或不支持，恢复观察都不会自动执行、迁移或重发旧 Host 项。

手机可见阅读与工作区未读投影共用后台串行写入器，避免主线程争用通知扩展的共享文件锁；同一目标连续阅读合并为最高阅读位置及通知集合。文件锁冲突只重试本地账本写入，不能触发远程消息或队列重放。最终失败按锁繁忙、后台中断、空间不足或实际阶段／错误码提示，保留已有账本；私有诊断只记录计数和错误码，不包含目标、正文、凭据或账本内容。

有界基线只在完整快照或缓存首次建立时扫描，健康流后续直接应用增量，不再按每个 token 重扫近期工具输出。单项大小预算在 JSON 正文复制前检查，限制节点数和深度；超大工具字符串不额外物化。2026-10-19 起移除 Host FIFO 回退。旧项仅供核对，冷启动、原生队列未就绪或已确认不支持时均不自动执行。

macOS 默认选择安装桌面创建的用户 IPC socket；`CODEX_SOCKET` 可明确指定该入口。它与 `shared-runtime` 的 App Server WebSocket 使用不同协议，不可互换。iOS 模拟器添加 `http://127.0.0.1:8794` 及本机 access-token；真机通过有效 HTTPS 连接 Host，不能直接访问电脑回环地址。

避免终端结束导致桥接进程退出，可在完成构建后启用 macOS 用户 launchd job：

```bash
pnpm build
HOST_AGENT_ROOT=/path/to/authorized/projects pnpm service:desktop start
pnpm service:desktop status
# 更改授权根目录或更新构建后，重启自己的桥接进程
HOST_AGENT_ROOT=/path/to/authorized/projects pnpm service:desktop restart
pnpm service:desktop stop
```

脚本读取实际安装构建，未验证版本提示后继续，仅启动本产品服务；私有 plist、日志、hostId 和密钥位于忽略的开发状态目录。`start`／`restart` 另将权限为 0600 的同一配置注册到当前用户 `~/Library/LaunchAgents/com.codexmanage.host.desktop.plist`，并启用本产品 job、解除 launchctl 禁用状态，由 launchd 在下次登录时自动恢复，KeepAlive 处理服务意外退出。`status` 分别报告当前 job 是否运行与 `loginAutostart` 注册是否存在，后者不能单独证明系统未禁用或服务在线；`stop` 卸载本产品 job 并移除自启注册，保留原配置与操作状态。启动／停止不修改官方桌面实例，用户仍须正常启动并登录原桌面。注册及回环／HTTPS 探测与真实注销、重启电脑、跨网络验收分别记证。

Windows 对应 `pnpm service:windows start`／`restart`，默认注册并启用当前用户交互登录的 `CodexManage Host Agent` 计划任务；已存在的同一任务也修正登录触发器和启动设置。允许电池供电运行、忽略并行启动，失败后每分钟重试、最多三次；`stop` 停止并禁用任务，保留配置，`start` 恢复启用。`status` 的 `loginAutostart` 核对任务与当前用户登录触发器均启用，与 `running` 分开。macOS 和 Windows 的 Host 自启均发生在用户登录后，不自动冷启动原 Codex；Tailscale 在线与原桌面 owner 可用仍须分别核对。安装／修复／升级的 SKILL 默认执行自启配置，用户明确关闭时尊重其选择。

## 本机托管模式

先安装并登录 Codex。生产安装应将状态目录放在仓库之外，下面使用忽略的开发目录：

```bash
mkdir -p .local/workspace
CODEX_RUNTIME_MODE=managed-runtime \
HOST_AGENT_ROOT="$PWD/.local/workspace" \
HOST_AGENT_STATE_DIR="$PWD/.local/runtime" \
pnpm dev:runtime
```

日志只提示地址和密钥文件位置。添加 iOS 客户端时，模拟器填写 `http://127.0.0.1:8788`，把本机 `access-token` 文件内容填入密钥字段。密钥不进入 App 的 JSON 缓存。模拟器采用 ad hoc 签名和专用模拟器 entitlements，保证真实 Keychain 可用；真机签名需要开发团队。

电脑加入多个客户端时，每个服务必须使用不同状态目录；`runtime.json` 内的 hostId 是稳定身份。复制状态目录会复制身份，不能拿来作为另一台电脑的独立身份。

## 远程连接

逐台电脑的部署/接入使用仓库 [codex-host-connect Skill](../skills/codex-host-connect/SKILL.md)：保持回环原桌面 Host，通过 Tailscale Serve 提供私有 HTTPS，再在手机添加目标客户端。`pnpm package:connect` 生成带已编译 Host Agent、运行依赖和完整源码的 Skill 包，目标电脑可从 `assets/codex-manage` 部署，无需另外提供仓库。macOS 有已有真实验收；Windows 新增实验性原桌面命名管道、DACL、计划任务和验收脚本，已有聊天按实际 owner 开放回复、停止和审批，真实回复/停止/审批验收单独记录，不作为回复的前置开关，见 [Windows 接入](../skills/codex-host-connect/references/windows.md)。Linux 尚未实现。Skill 及只读检查工具已实现；Tailscale 真实部署、iPhone 跨网络与两台实体电脑仍需逐机验收。Tailscale 的网络层中继不等于本项目 Relay，后者仍仅有健康检查。

直接 HTTPS：

```bash
CODEX_RUNTIME_MODE=managed-runtime \
HOST_AGENT_ROOT=/path/to/authorized/projects \
HOST_AGENT_STATE_DIR=/path/to/private/state \
HOST_AGENT_BIND=0.0.0.0 \
HOST_AGENT_TLS_CERT=/path/to/certificate.pem \
HOST_AGENT_TLS_KEY=/path/to/private-key.pem \
pnpm dev:runtime
```

iPhone 使用它信任的有效 HTTPS 证书和可访问地址。也可将回环 Host Agent 放在可信 HTTPS 反向代理后。客户端不跳过证书验证，不接受远程明文 HTTP。不提供自动穿透 NAT 的能力。

共享服务连接：

```bash
CODEX_RUNTIME_MODE=shared-runtime \
CODEX_SHARED_SERVER_URL=ws://127.0.0.1:19400 \
HOST_AGENT_ROOT=/path/to/authorized/projects \
HOST_AGENT_STATE_DIR=/path/to/private/state \
pnpm dev:runtime
```

这仅连接已由原桌面启动的同一服务，不启动或重启桌面应用。地址不可用时失败，不回退到独立执行服务。本次仍为隔离 DEV，不能用于绕过正式验收和单实例要求。

## 故障与数据

手机先上传完整附件，再提交回复。上传失败保留草稿。提交前持久保存 immutable outbox，重试沿用相同消息 ID 和相同内容。Host 在 RPC 之前保存提交记录，重复 ID 的内容不同会拒绝；已知拒绝标为 failed，超时/断开标为 uncertain，禁止自动重放。

发送时固定目标、正文、附件、模式、模型和推理设置。原始 outbox 保存成功后，仅在草稿仍匹配该发送快照时清空输入框，允许继续写下一条；确认只消费对应 outbox 一次，不再修改输入框。发送期间切换电脑或更改设置不会覆盖已提交内容，迟来的确认也不会清掉下一条草稿。明确未执行的失败可以恢复原文与本地附件，但必须先清空当前草稿；不确定结果先查询回执，不盲目重发。

排队消息只交给客户端队列，收到客户端确认后才显示已排队。Host 不再自动续发或执行旧队列；保留不可变意图、去重与回执。客户端不可用时明确返回未提交，队列同步失败明确提示；未知结果只核对同一 ID。线程恢复时通过 Codex Turns 的 ID/clientId 核对提交记录，不自动重发。

上游未发送首条消息的空线程可能没有可恢复的 rollout。只有托管模式中没有历史、从未进入提交阶段的空会话，才在上游明确报告缺少 rollout 后重新建立空线程；手机使用稳定逻辑 ID，重启前上传的附件仍属于原会话。内部执行 ID 不发送给手机。有过提交或历史的会话、共享桌面对话均不走此路径，也不会重放任务。

上游进程或 proxy 意外断开后，Host 撤掉执行能力、清除失效审批、标记运行状态未知，按 1–15 秒退避重新连接；只重建本服务自己的托管进程或共享 proxy。RPC 尚未返回和已经返回两种断开都会保留 uncertain。恢复时切换事件 epoch 并重新同步历史，只对账同一操作 ID，不自动重放不确定消息。Host 不调度或续发客户端队列；原生队列由客户端独立管理，也不由 Host 启动/重启官方桌面端。

事件序号在进程内递增，512 条保留窗口，用启动 epoch 区分重启。输出最多每 75 ms 合并为一份最新快照，继续包含完整增量结果。游标过期或 epoch 变化时返回权威快照；事件日志本身没有落盘，线程快照与 receipts 采用原子 JSON 持久化。这里不宣称跨进程 exactly-once。

下载只能访问在当前对话中登记的附件。路径经 realpath 校验，必须位于授权工作目录或 Agent 状态目录，符号链接越界被拒绝。文件变化导致摘要不符时要求刷新。普通文件能传输不意味着 Codex 能解析所有 Office/PDF/音视频内容。

模型登录凭据仍由 Codex 本机管理。客户端密钥对应电脑级访问，当前没有细粒度多用户权限；撤销方式为停止 Host 并轮换 `access-token` 后重新启动。审批只支持当前连接的命令、文件修改和用户问题。托管/共享模式拒绝其他服务器请求；原桌面模式把未适配的审批保留给桌面，不自动代答。

## 验证

Windows Trace 回收器与其他固定 PowerShell 工具复用同一子进程环境规范，移除继承的 `PSModulePath`，避免从其他入口启动时误载不兼容的 PowerShell 模块。父进程和全局环境不修改；回收前的身份、独占句柄、摘要及未开始删除回执规则保持原约束。

原桌面提权运行时，新建的原 Trace 文件可由 Administrators 持有。回收器只对准确 Trace bundle 使用现有的 Win32 句柄、根目录当前用户 owner、受信任子项 owner、DACL、命名空间和文件身份检查，不把仅适用于 Host 私有配置的当前用户 owner 检查套在原 Trace 上，也不修改原 Trace ACL。Native 安装器仅能将本次排他新建对象的提权默认 Administrators owner 设置为当前用户；既有文件和目录仍须严格归当前用户，未知或既有外来 owner 不接管。该兼容代码与 Windows 原生验收分别记录，macOS 检查不代表 Windows 通过。

该原生快照接口的零字节预算选择目录操作；文件身份检查使用正的一字节预算，完整载荷仍由各自有界预算读取。不能把文件元数据检查当作零预算目录探测。Windows owner／retention 原生用例必须实际验证此边界，macOS 跳过不构成通过。

```bash
pnpm check
pnpm build:ios
pnpm check:runtime
# 需完整 Xcode、iPhone 模拟器、已登录的 Codex；会实际调用模型
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer pnpm test:live:ios
# 独立验证空会话重启、稳定目标和重启前附件的真实读取；会实际调用模型
pnpm test:live:runtime
```

`check:runtime` 使用 JSONL 协议 fixture 验证授权、归属、上传摘要、增量与进度、去重、队列不可用时明确失败且不执行、停止、审批竞争、问题回复、下载、恢复和持久记录，不调用模型，不算真实 Codex 验收。

真实模型验收与原生 UI 结果单独记录在 本机验收（本地文件 `docs/acceptance/ios-local.md`）。原桌面模式使用独立的真实验收；两台物理电脑、iPhone 实机、后台/锁屏与公网链路没有被协议 fixture 替代。

参考：[OpenAI App Server](https://learn.chatgpt.com/docs/app-server)、[官方 CLI 命令](https://learn.chatgpt.com/docs/developer-commands)。

原桌面复现必须选择已经在桌面打开的专用空闲验收会话，脚本会发送测试消息、停止其任务并批准一条无文件读写的 printf 命令，不操作其他会话：

```bash
DESKTOP_ACCEPTANCE_THREAD_ID=<dedicated-desktop-test-thread-id> \
CODEX_EXECUTABLE=/path/to/installed/codex \
HOST_AGENT_ROOT=/path/to/authorized/projects \
DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer \
pnpm test:desktop:ios
```

创建/打开专项使用已经运行的真实 Host 和原桌面控制聊天；不会启动替代执行实例。项目 ID 从该 Host 的 workspace 获取。用例会在所选项目和无项目环境创建自己的验收聊天；审批目标必须是专用空闲会话，并已使用会实际发起请求的 on-request 权限设置。未加载目标应是已在 Host 历史索引中的专用聊天，脚本会先检查原 owner 不存在。

```bash
DESKTOP_CREATION_PROJECT_ID=<host-project-id> \
DESKTOP_EMPTY_PROJECT_ID=<host-empty-project-id> \
DESKTOP_ACCEPTANCE_THREAD_ID=<dedicated-control-test-thread-id> \
DESKTOP_ACCEPTANCE_THREAD_TITLE='<dedicated-control-test-title>' \
DESKTOP_UNLOADED_THREAD_ID=<known-unloaded-test-thread-id> \
DESKTOP_UNLOADED_THREAD_TITLE='<known-unloaded-test-title>' \
DESKTOP_IOS_TESTS=testOriginalDesktopProjectCreation,testOriginalDesktopEmptyAndProjectlessCreation,testOriginalDesktopCreationRestartRecovery,testOriginalDesktopHostRestartRecovery,testOriginalDesktopReplyProgressStopAndApproval,testOriginalDesktopPhotoFileUploadAndDownload,testOriginalDesktopConversationSwitchAndDraftIsolation,testOriginalDesktopUnloadedOpenAndReply \
IOS_SIMULATOR_ID=<booted-simulator-id> \
DEVELOPER_DIR=/path/to/Xcode.app/Contents/Developer \
pnpm test:desktop:controller:ios
```

生成当前代码/构建的真实启用记录后，`testOriginalDesktopRegularAppCreation` 另行验证不带 `-ui-testing` 的普通 App 创建。元数据 fixture 只提供真实连接和本轮随机码；创建、回复和附件仍提交真实原桌面，不把 fixture 当作执行器。结果包及独立工具结果、首条消息、附件字节、owner 和原进程保持检查写到忽略目录。

`pnpm check` 的 desktop IPC fixture 只验证边界与修订号恢复，不替代此真实模型/iOS 验收。私有状态、日志、截图、令牌与历史均在忽略的 `.local` 中。

手机计划模式需要升级后的 Host 声明 `plan-mode`。已有桌面计划正文显示独立计划卡片，已完成计划支持实施与调整；手机“＋”菜单可为新一轮选择计划模式；原生排队沿用当前客户端模式，不能表示的模式覆盖明确拒绝。实施以完整计划开始同一 owner 的新轮，模式切回 default；调整保持 plan。未明确选择模型、推理强度或权限时继续继承电脑。旧 Host 未声明能力时不显示模式菜单，历史连接始终只读。协议与原生 fixture 已覆盖映射和操作；真实原桌面及 iPhone 专项尚待验收。

手机模型／强度与权限入口位于输入栏，旧 Host 未声明 `generation-settings` 时隐藏。设置按电脑和聊天保存在草稿；发送或创建首条时固定选择，后续切换目标不会改变已提交内容。请求批准、帮我批准与完全访问均映射当前桌面真实预设。选择跟随电脑会移除手机覆盖；正在执行的补充指令沿用当前任务，原生排队也沿用当前桌面设置，不能表示的覆盖明确拒绝。模型目录读取、协议 fixture 和模拟器专项与真实桌面任务／物理 iPhone 验收分开记录。

任务通知配置、签名、APNs 独立服务与加密迁移见 [任务通知与铃声](notifications.md)。未配置 APNs 时仅覆盖前台；后台和锁屏必须用正式签名 iPhone 实测。Host 升级保留私有通知状态与原身份，不能复制到另一台电脑。

### Windows 原会话打开前的元数据同步

独立元数据连接确认取消归档，不代表原桌面已清除归档抑制或装载原会话。Windows 的显式已有会话打开在无原 owner 时，读取同一 Host 的新鲜完整官方目录；只有唯一准确目标明确未归档，才发送固定 `thread-unarchived` v1 提示（`hostId=local`、准确 `conversationId`），然后使用既有 URI。提示成功不代表打开成功，仍需准确原 owner 的新鲜完整快照；查询旧未知操作不重发提示或导航。其他平台保持原路径。该兼容处理不启动额外执行器、不做归档循环，2026-10-23 新包的真实 Windows 原 A/B 专项通过：B 从 fresh 无 owner 经一次产品打开恢复，同连接 A/B 的原 owner、完整 canonical 快照及原一轮一输入摘要均匹配，Main 独立核对通过。结果只覆盖独立产品桥接路径，正式 Host 未部署新版，不能替代 Trace 业务矩阵或手机验收。

## Codex 局域网与原 HTTPS 双通道

默认双通道，Tailscale 是在外远控的可选依赖；纯局域网无需 VPN。两条路使用同一 Host、授权、草稿和回执。安装流程把实测 Serve 根地址 JSON 数组保存到 `HOST_AGENT_HTTPS_ENDPOINTS`；服务通过鉴权 `GET /v1/connection-routes?hostId=<准确 ID>` 交付严格 v1 路径快照，旧 `/host` 不变。手机保留旧 Serve 地址，局域网固定证书与 Serve 系统信任分开；前台健康路径保留，失败后自动换到同目标可达入口，未知写请求只核对原回执。详见 [网络接入](../skills/codex-host-connect/references/network.md)，逐平台及跨机真实结果另记。

Windows 双通道配置沿 service → 私有配置 → worker 的严格环境边界传递；三处必须同时接受 `HOST_AGENT_HTTPS_ENDPOINTS`。未知字段和非字符串仍拒绝，Host 再验证根地址与数量，不能通过放开整个环境来修复。Windows 原生首次验收发现白名单缺项，原包未切入该机正式服务；修复后统一新包逐机复验，原失败记录保留。

### Codex 自动更新后的目录恢复

原桌面模式的完整工作区目录 reader 与主 runtime 共享当前可信 CLI，不永久固定启动环境中的旧 Store 路径。运行期间 CLI 消失、桌面 owner 正常变化或只读传输失效时，Host 合并重新发现、等待旧 reader 完全退出，并最多重读一次幂等目录。连续失败按 1–15 秒退避；无需把每次 Codex 更新变成重装 Host 的流程。macOS 保留显式 CLI 或原固定 bundle 路径，Windows 保留原可信 owner／CLI 与协议校验。

`/healthz` 的 HTTP 200 表示服务存活；同时核对 `status`、`runtime` 和 `workspaceCatalog`，`not-read` 不能当目录通过。不可用时目录返回准确 503 原因码和健康 degraded；无可信替代、坏协议或旧进程退出未确认要保留现场，不能通过关闭信任检查继续。回复、新建、审批、停止及管理操作的未知结果仍查询原回执，自动目录恢复不会重放输入。真实更新／重开和手机验收详见 [专项记录](acceptance/codex-update-resilience-20261030.md)。

## 建议 Trace 的保留策略（2026-10-30）

在原正式 Host 服务环境显式设置 CODEX_DESKTOP_TRACE_RETENTION=1，保留原 CODEX_DESKTOP_SUGGESTION_OBSERVER 和 CODEX_DESKTOP_SUGGESTION_TRACE_ROOT；只按原环境重启本产品 Host。设置0或移除开关停止后续回收，保留已保存建议。隔离验收进程不得指向正式 Trace 并开启回收。每会话一条最新建议或失效记录存于 Trace 根下 .agent-hub-suggestions；Host 私有状态 trace-retention-<机器目标摘要>.json 记录当前进程计数与有界保留原因。新服务必须连同读取兼容与精简存储一起部署，不能先删原始 Trace 再升级 reader。取消、失败、空结果原始载荷保留24小时；未知身份和仍在使用资料不会仅凭年龄删除。

## 原生建议采集配置（2026-10-30，候选）

Host 可显式配置 CODEX_DESKTOP_NATIVE_SUGGESTIONS_CONFIG 指向本机私有配置；配置路径与身份不能跨电脑复制。macOS 使用 scripts/desktop-native-suggestions-environment.mjs install，参数为 --program <program> --executable <original-cli> --trace-root <private-root> --target-file <private-target.json>，复用已有共享环境 URL；Windows 使用 scripts/windows-native-suggestions-environment.mjs install --config <private-input.json>，仅管理有不可变清单证明的当前用户 CODEX_CLI_PATH。配置不退出、不重启原桌面。待用户保存工作并正常重开后，核对当前 producer、原生父链、实际实现指纹及 Host 能力。Windows 日志使用有界原生 offset/count 读取，允许追加、保持对象身份，不能把文件大小超过 1 MiB 当作失败。

### 统一升级保持既有配置

上述被动 producer 的独立签名／Store／父链边界保持，通用桌面连接继续沿用当前用户／会话、真实管道 owner、路径／SHA 与协议契约。同步本轮程序和指引时保留已有开关、私有路径与原桌面；不因文档变更重新安装 producer、扩大保留策略或强制重开 Codex。Windows Native、旧 history 布局和真实 reader 恢复仍按同源新包独立验证。

### 冻结 R9/R10 的统一升级

Bonjour 发布由独立 generation worker 处理：旧 worker 退出确认后才重试，发现失败不会退役原固定证书 HTTPS。状态由现有私有创建／原子发布边界保存，不能用普通 Node 创建后仅改 DACL 替代 User-owner 创建。Native sample bridge 对大型帧仅有界扫描顶层路由字段，忽略无关历史内嵌的 thread/sample；歧义和重复顶层字段按准确分类拒绝，保留原候选、取消、退出和未知操作边界。

Windows 旧附件兼容按真实登记引用、目标和稳定文件快照验证；旧 Admin-owner 叶文件不因此获得全局私有状态权限。新附件保持 `attachments-v2`，原字节、owner、ACL、引用不变。统一包保留已部署 R10 与 Native config 环境字段，不回写较旧快照。冻结来源、普通 Main 验收入口及逐平台限制见[本轮记录](acceptance/codex-update-r10-legacy-20261030.md)。

### Windows 首次私有创建的路径处理

私有目录创建使用有界 Unicode Win32 路径与属性 API，避免 Framework 宿主在初始化之前缓存旧 BCL 路径开关。仅缺失路径可递归创建，拒绝访问、reparse、非目录及既有非 User-owner 对象继续拒绝；创建时设置 owner/DACL，预算、租约及退出屏障不变。拒绝保留固定 nativeStep，不含路径或原消息。新 Native 输入须重新做 Windows 普通 Main 预检和全缺失 Session 根／旧布局验收，见[专项入口与证据范围](acceptance/windows-native-first-directory-20261031.md)。

2026-10-31 F03／F31／F32：增加正式 Windows 启动的具名只读预检与独占 scheduled envelope，保留原 Worker 环境白名单；有界日志分类不输出 stderr／凭据。PR81 五组件和核心 Native 输入保持；来源完整 legacy／reader 辅助已通过，正式启动两处拒绝仍需按真实上下文诊断，不计正式升级通过。iOS／Android UI 和未知操作不重放规则不变，A6保持暂停。见[入口与证据边界](acceptance/windows-formal-startup-preflight-20261031.md)。

Windows 私有 Native 的 UnauthorizedAccessException 现在只对五种既定策略原因携带固定 nativeRejection，未知类型/分类仍失败关闭，不传路径/SID/原始消息。权限算法和预算保持；新 C# 改变准备 cache key，须重新核对新输入。正式 R10 切换需完整统一包、逐文件旧 SHA 守卫与 Root 控制回滚，PR82 只读 probe 不能冒充正式初始化。见[最小诊断与复验](acceptance/windows-formal-startup-preflight-20261031.md#pr82-实测之后的最小证据修复)。

`WRONG_FILE_OWNER` 现可附带拒绝时同句柄的 owner／路径／身份摘要，以及真实 helper User、PID／开始时间和传输 PID 关联。该元数据仅用于诊断，严格 User owner／DACL 和原预算不变；不可将其他进程的 `Get-Acl` 通过写成 Native 接受。官方 prepare 提供版本化固定三路径并标明 preparing Node 来源，Root 验证正式 Node 后才能以原 Principal 创建独占 Limited 只读任务；不操作正式 Host。新 Native 输入和准确的复验／清理边界见[诊断入口](acceptance/windows-native-owner-evidence-20261031.md)。实际正式拒绝原因仍待目标机证据解释。

PR86 正式失败栈的 `runtime-server.js:195` 对应通知构造器，不能继续作为 publisher token 失败的证明。通知锁／临时快照现使用原 Native 私有创建接口；新 `OWNER2` 允许字段采集局部失败并保留固定阶段，bootstrap 证据经 PowerShell 与共享槽传递，Host 用固定角色／action 区分路径。新增 `windows-notification-state-preflight.mjs` 仅在自有合成目录验证真实通知模块创建／重开，不读原状态或凭据。原权限／执行时限／未知操作不重放保持，正式目标及 token 事实仍由实际回执决定。见[最小复验](acceptance/windows-notification-private-startup-20261031.md)。

Framework 的 `Exception.Data` 会拒绝不可序列化的值。私有定型 owner 证据须满足该存储约束，且附加诊断失败不能替换原始 `WRONG_FILE_OWNER`；协议仍只输出显式固定 frame，不序列化任意异常 Data。编译使用 net48 引用不等于在 Windows Framework 上执行；.NET 8 的反射结果不能覆盖这一差异。详见[证据存储兼容性与复验边界](acceptance/windows-owner-evidence-serialization-20261031.md)。

诊断读取也须保留原拒绝：先确定是 owner 拒绝，再在保护内取得 Data、检查键和读取私有定型值；缺键或 Data getter／Contains／索引器异常只表示证据不可用。Framework 只读字典的缺键行为与 .NET8 不同，原只读 Data 回归保留，并加入跨 CLR 故障字典及固定阶段报告。PR88 已取得 Windows 通知 OWNER2／实际子进程／同句柄通过；managed 后续失败不撤销该范围，也不代表 CLI／serve 或 Trace 协议已验证。见[读取边界与复验](acceptance/windows-owner-evidence-data-lookup-20261031.md)。
