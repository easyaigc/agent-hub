# 原桌面输入建议只读通道调查

## 2026-10-18 来源有效性代替固定一小时限制

用户确认电脑仍保留建议时，手机不应因本产品的时限单独隐藏。已实现可选 `validity:source-turn`：绑定建议依赖最新完成来源、真实原 owner 和完整原生证据，不再按时钟年龄拒绝。`expiresAt` 保留原历史值供旧客户端兼容；新 iOS 对来源绑定值不安排到期任务。旧 Host 响应仍按原时限，未知有效性值拒绝。未绑定观察记录仍有一小时保留限制，与绑定建议的寿命分开。

新增辅助回归验证超过一小时的原始输出仍可读且时间记录不变、证据丢失或冲突拒绝、菜单恢复不清除、来源变化不能展示或采用旧内容；修复前同一专项失败，修复后通过。实体 iPhone、正式 Host 和真实模型任务结果另记，不由 fixture 推定。下方记录保留各历史阶段的实际时限及验收范围；本实现尚不读取桌面 DOM 的手动关闭状态。

## 2026-10-18 手机建议短暂消失的状态修复

实体 iPhone 16 原位更新后的新增核对还发现 Host 来源复核本身的瞬时清空：同一真实空闲 owner 的历史与事件响应中，`suggestionSourceTurnId` 在准确来源与空值之间反复切换。原因是每次只读建议请求的前后来源检查在等待原 owner 之前删除了已确认值；此时并发事件会发布空来源。修复将更新移到复核成功之后，失败、真实新轮次与 owner 不可用仍清除。新增挂起复核回归在旧实现失败、修复后通过；它验证真实生产会话的投影边界，使用合成 owner，不能替代实体手机实证。调试版将来源失效细分为固定原因码，仍不记录正文或目标标识。

手机将长轮询／历史请求的一次临时失败标为离线，原界面又把离线等同于来源失效而清空已显示建议；后续读取成功会重新显示，形成消失再出现的路径。现已将建议的展示资格与联网读取、采用资格分开：仅保留当前导航中曾确认、完整目标／来源仍一致且未过期的正文；恢复时须取得新的运行时快照，空心跳不能重新开放采用。新轮次、确定失效、永久错误和原有效期继续使建议失效。调试版新增至多 64 条无正文、无标识和无地址的状态原因，便于区分传输失败、来源变化、后台与过期。

新增原生传输回归只覆盖本次断线、空心跳、来源恢复和新轮次失效；旧版本在首次断线后实际退回“询问 Codex”，同一专项失败，修复版通过（1 个测试、0 失败）。两次断线／恢复循环中正文保留、等待新来源快照期间采用禁用、新轮次清除旧建议均核对，写请求为零。原生及真机调试构建通过。这些使用隔离模拟器及 HTTP fixture，不能代替正式 Host／真实手机验收；手机部分修复不能替代上述 Host 复核修复，完整结果另行记录。原连接、草稿和已提交操作不参与 fixture，也不新增模型输入。

## 2026-10-18 手机显示延迟专项

新增改动将当前聊天的来源候选读取与历史加载并行；本次新历史确认完整范围后，由会话持有的 reader 发布。首次无候选时，在取得真实来源后立即准备读取。取消的旧空状态界面任务和其他来源的退出回调不能清除新结果。另将初始渲染窗口缩至最近 20 条，并合并历史保存、优先启动网络读取；完整缓存、草稿、提交前落盘及原有效期保持。

本次仅复测新增读取与显示速度、取消边界和原状态保留，未重复既有采用／撤销等场景。正式 Host、原有手机连接、真实已完成聊天及原生建议全文匹配通过；原 owner 前后仍为相同两个完成轮次，没有提交模型输入。实体 iPhone 15 Pro Max 的最终候选两次在约 0.55／0.98 秒发布建议，提示挂载约 1.17／2.03 秒；因此尚未达到稳定一秒内显示。中间构建曾测得 0.87 秒，不能用单个最好结果替代最终版本的波动范围。Host 本机读取、XCTest 查询上界与手机真实挂载时间分别保存。

原生新增专项检查及真机构建通过；五个原连接、76 份草稿的内容摘要与五份既有待核对输入的摘要保持。完整既有功能按原证据复用，不把此次速度专项扩大为全量验收。调试日志仅有耗时，真实标识、正文与设备证据留在本机忽略目录。

## 2026-10-18 日常 Host 与手机原连接验收

日常 Host 已部署统一包 `5d56e639606c2c4a6e9bf4de8553636a651e309e7da7725d656f6c4afab13ddc`，在原状态、密钥、服务入口、Node、工作目录和 Tailscale HTTPS 下运行，复用本机已启用的原生 trace。正式观察器在生成时实际捕获新通知，再以原生请求／返回精确绑定来源；没有移用 DEV Host 的旧观察记录或延长有效期。

实体 iPhone 15 Pro Max（iOS 27.0.1）普通安装 App 通过原有连接完成 A → B → A → B 四次全文匹配。空框灰字、采用与撤销、已有正文追加与撤销、继续编辑后旧撤销失效、跨聊天草稿保留、取消替换保留原文均通过。完整连接列表前后均为相同五项且选择不变；两条真实原 owner 前后各一个相同完成轮次，手机未提交模型输入。本次没有临时 Host、临时 HTTPS、阻断命令代理或新增手机连接。

真实部署发现了仓库内未触发的安装路径缺陷：位于 `node_modules/@codex-manage/codex-adapter/dist` 的观察器无法用仓库相对路径找到 Python helper。修正后将 helper 随依赖放入 `resources/`，观察器优先从包内解析；隔离安装布局回归与日常 Host 的新建议捕获分别通过。早期失败包和两次 HTTP 404 预检保持失败记录；其中原 Codex 创建的一条聊天被持久化为 `source: unknown`，官方会话目录未列出，改用正常目录中的新来源验收，未修改官方数据库。另有无项目测试被原授权根目录排除，未扩大授权范围。

正式手机完整结果保存在本机忽略目录 `.local/suggestion-production-upgrade-20261017/phone/attempt-82f49ce3/report.json`：`existingConnection:true`、`physicalNativeVerified:true`、`allOriginalConnectionsPreserved:true`、`xcodeExitCode:0`，四次正文及四组草稿操作通过。来源证据、服务保留摘要、统一包清单和各机回执在同一私有目录；真实聊天标识、正文、密钥与机器绝对路径不进入 Git。手机冻结源码保留了同时进行的无关发送记录修复，建议实现未变化，单独记录原生与 Host 指纹；不将整个手机源码树描述为与包中旧快照逐字相同。

另外三台电脑已完成相同程序／SKILL 的逐项升级、原身份和配置保留、Host 重启恢复、HTTPS 及原 owner 读取核对。两台 Mac 已准备一次性私有启动器，未打断活跃任务或重启原 Codex，因此尚未启用建议读取；Windows 通知采集器与 trace 权限实现仍缺失，未伪造能力。上述程序升级不构成远端手机建议验收。

SKILL 已整理随包代码、首次正常重启、私有诊断记录、Host 配置持久化与原连接真机验收流程，见 [建议采集与 Codex 重启](../../skills/codex-host-connect/references/desktop-suggestions.md)。原 Codex 必须在启动前获得 `CODEX_ROLLOUT_TRACE_ROOT`；已运行实例不能动态开启，仅重启 Host 不能代替。普通图标的后续启动不自动继承此次诊断环境。完整模型请求／返回的记录会落盘，Host 提取仍只读。外接键盘 Tab、附件组合、确认替换的完整路径及跨平台建议仍未计为本轮真机通过。下方记录保留各个历史阶段的实际范围。

2026-10-17 手机第二阶段：用户已确认输入框内灰字与采用按钮，客户端本地草稿采用已实现并通过独立实体手机验收。第二阶段已用另外两条原桌面真实建议，在实体 iPhone 15 Pro Max（iOS 27.0.1）普通安装 App 完成 A → B → A → B 四次来源与全文匹配。采用后撤销、已有草稿追加与撤销、采用后继续编辑并使旧撤销失效、跨聊天草稿保留、取消替换保留原文均通过。手机操作未新增模型输入，独立原 owner 前后仍为各一个相同的完成轮次；原连接已恢复，临时连接及 HTTPS 入口已清理。此次使用独立 DEV Host，日常正式 Host 尚未部署本功能；外接键盘 Tab、附件组合与其他平台未扩展为本轮真机通过。Host 与来源采集继续只读，未增加生成或提交接口；下方只读卡片、无草稿改动的结论保留为第一阶段实证。

## 2026-10-17 原生请求 trace 的来源绑定路径

本轮已在真实原 Codex 桌面 `26.1013.40934` / CLI `0.163.0-alpha.10.2` 取得原生请求记录，并完成两条原建议的准确来源绑定。Host 只读建议 GET 与原生 iOS 卡片已实现，真实 Host 预检已返回两条绑定建议及一致的 `runtime.suggestionSourceTurnId`；实体 iPhone 15 Pro Max（iOS 27.0.1）的普通安装 App 已完成两聊天 A → B → A → B 共四次全文一致验收。本次运行及 HTTP 验收使用临时独立 DEV Host，日常正式 Host 尚未部署本功能。下面分别记录实现、真实通过和未验收范围。

原预测的临时 Session 也会创建原生 rollout trace。新 Session 读取 `CODEX_ROLLOUT_TRACE_ROOT`，记录 `inference_started` 事件和实际 HTTP／WebSocket 请求 payload；请求中的 `client_metadata["x-codex-turn-metadata"]` 包含 `prediction_source_thread_id`、`prediction_source_turn_id`，并带预测执行的 `thread_id`、`turn_id`。这使来源和预测执行能够按同一请求中的字段连接，无需按时间、正文或目录猜测。实现依据为对应版本的 [Session 初始化](https://github.com/openai/codex/blob/rust-v0.163.0-alpha.10.2/codex-rs/core/src/session/session.rs#L1281)、[trace 初始化](https://github.com/openai/codex/blob/rust-v0.163.0-alpha.10.2/codex-rs/rollout-trace/src/thread.rs#L106)和[请求元数据](https://github.com/openai/codex/blob/rust-v0.163.0-alpha.10.2/codex-rs/core/src/responses_metadata.rs#L330)。安装二进制的相应符号已只读核对；此结论不推广到其他版本。

Adapter 的 `DesktopPredictionTraceReader.readBinding(predictionThreadId, predictionTurnId)` 只查指定执行的 trace，核对 manifest、事件外层和内层、请求外层和元数据中的执行身份；来源字段必须一致，显式 fork 来源存在时也必须相符。`readPrediction` 进一步读取同一执行的完成响应，提取原 `suggestion`、完成时间及响应摘要。缺失、部分写入、读取期间变化、冲突及权限不符均返回 `null`，每次重新读取，不永久缓存已验证结论。根目录必须属于当前用户且为 `0700`，根内允许原生默认 `0755/0644`，拒绝其他用户可写、链接和路径越界。当前仅实现 POSIX；Windows 不能由 Unix mode 位推定权限。原始请求、响应、诊断路径和执行 ID 不进入手机建议响应。

本次原进程已在用户明确授权的临时正常重启后带入 trace 环境，原程序环境继承和实际文件读取已验证。`scripts/desktop-suggestion-trace-launch.mjs --prepare <absolute-private-directory>` 只准备一次性启动器与安装指纹；`--check` 只核对状态。`--wait-and-launch` 可在这次已获准重启前预置，最多等待十分钟，直到原应用及原直属执行器普通退出，再通过 LaunchServices `open -a ... --env ...` 正常启动原应用。它不结束现有进程、不用 `-n`，不改全局环境、安装包或配置文件；等待超时或安装指纹变化时不启动。启动前核对原主进程和 prepare 时原直属执行器的 PID／启动时间／用户／路径，独立 history reader 不被当作原执行器。一次性 claim 防止重复使用；本次 v3 已实际完成等待退出、启动和环境继承，不能扩大为默认启动、自动重启或跨平台产品能力。LaunchServices 的 umask 不作假设，实际保密边界是私有根目录。

**启用原生 trace 是单独的诊断动作，不属于纯读取。** 原记录器会写出完整请求／返回，并添加诊断请求标识；本次临时启用及正常重启已经获准，Host 读取器本身不启动预测、不修改记录、不采用或发送建议。退出本次实例后通过正常入口启动即可不再由此启动器注入变量；已生成的私有诊断文件不会自动删除。完整请求／返回、真实身份及正文仅留在私有验收目录，不提交 Git。

### 本次真实通过

- 两条专用来源聊天均由原桌面原 owner 完成一个真实模型轮次，来源轮次的实际推理档位为 `xhigh`。来源 Turn 的执行区间重叠 **25.268 秒**；原桌面自行生成的两条预测生命周期重叠 **5.755 秒**。Provider 请求／推理流没有重叠，因此本次证明多来源生命周期并发下的准确绑定，不证明 Provider 推理并发。
- 每条实际请求的 `x-codex-turn-metadata` 都同时包含准确来源 Thread／Turn 与预测 Thread／Turn，解析结果逐字段与原记录一致。原生完成响应的 `suggestion` 与既有 notifier 采集正文完全一致，没有使用时间顺序、文本相似度或手工来源映射。
- 原 owner 前后核对来源不变，独立原生读取确认每个来源聊天各一个 `completed` 轮次；原桌面来源日志均为 `suppressed=false`。日志证明原预测未被该判定抑制，不替代原输入框视觉读取。
- 新 `GET /v1/threads/next-turn-suggestion` 的真实 Host HTTP 预检已返回两条准确绑定的建议；对应历史快照的 `runtime.suggestionSourceTurnId` 分别一致。早期观察接口的鉴权、方法、错误 runtime 和 Host 重启结果保留其原范围，不自动计作新接口的全部恢复场景通过。

手机验收使用实体 iPhone 15 Pro Max、iOS 27.0.1 和普通安装 App，没有启用 fixture 产品模式。经临时独立 Host 的真实 HTTPS，按 A → B → A → B 切换，两条建议共四次原生全文匹配；HTTP 的 `threadId/sourceTurnId/contentSha256` 均对应原来源。草稿保持原样，建议卡片没有采用或发送按钮；本次手机读取提交模型输入为 0。原 owner 前后独立核对仍为两聊天各一个 `completed` 轮次，准确 Turn 不变。完整客户端列表前后核对确认原连接已恢复、临时连接已删除；本次临时 HTTPS 入口已关闭。

核心绑定结果索引为本机忽略目录 `.local/suggestion-native-trace-20261017-v3/binding-result.json`；其中 `exactSourceBindingsVerified`、`concurrentBindingVerified` 和 `nativeSourceReadsMatched` 为 true，该早期绑定阶段的 `mobileVerified:false` 保留原样。后续实体手机完整通过证据为同目录 `phone/attempt-bcaf2d10/report.json`，`status:passed`、`physicalNativeVerified:true`、`xcodeExitCode:0`。两次 `pnpm check`、`pnpm build:ios` 及各次真机构建通过，作为辅助证据单列，不替代手机实际匹配与独立 owner 核对。所有证据均保存在私有目录，不提交真实聊天标识、正文、机器路径或凭据。

### 已实现的 Host 与手机读取边界

`GET /v1/threads/next-turn-suggestion?hostId&target&threadId&sourceTurnId` 按完整机器目标和准确来源读取，每次前后重验真实原 owner、原生请求／响应和通知正文；冲突、不完整、非唯一、过期或来源变化时关闭该结果。只有 `CODEX_DESKTOP_SUGGESTION_OBSERVER=1`、`CODEX_DESKTOP_SUGGESTION_TRACE_ROOT` 与实际采集／私有目录就绪同时满足时才声明 `desktop:suggestions:read:v1`。既有观察接口继续返回 `source:null` / `bindingStatus:unbound`，不会把派生绑定写进观察记录。

`runtime.suggestionSourceTurnId` 由原 owner 的最新已完成轮次提供，活动 `turnId` 完成后清空不再造成来源缺失；运行中、断开、owner 不可用或来源失效时清除，历史缓存不能恢复读取资格。原生 iOS 已实现当前可见聊天的只读卡片与有界刷新，核对目标、来源、正文摘要、过期和导航／连接世代，提供展开／收起及正文选择。采用、草稿写入、替换和发送均未实现或启用，详见[已实现运行时契约](../protocol-runtime.md#桌面下一轮建议只读准确来源绑定)。

原生手机展示、两聊天反复切换、草稿保留及读取无额外模型输入已通过；本次链路仍依赖用户授权的临时原生 trace 和独立 DEV Host。日常正式 Host 尚未部署本功能，Windows、其他电脑及默认启动下的持续可用性仍未验收。早期 OCR 传输、DOM fixture 和未绑定采集保留其原范围，手机通过由本次独立实体证据支持。

另一条候选 `thread/prediction/request` 在该精确版本的 [message processor](https://github.com/openai/codex/blob/rust-v0.163.0-alpha.10.2/codex-rs/app-server/src/message_processor.rs#L1450) 明确返回尚未实现；其 schema 中的通知类型不能当成当前可调用或已发布的读取出口。本次没有调用该生成请求。

初查：2026-10-15；新版复核：2026-10-17。**以下保留各阶段当时的失败、阻塞和辅助结果，当前状态以上方原生 trace 实证为准。** 早期受控单会话核对仍不能升级为并发自动绑定证据；本次新增通过依赖同一实际请求中的四个身份字段。

## 2026-10-17 来源出口复核与常驻采集修复

继续核对当前安装代码和两条真实并发记录，确认普通 owner IPC 的缺失发生在原桌面的分发边界：`thread/started` 携带 `ephemeral:true` 时，主进程仅记录预测执行 ID 就返回，早于窗口及 owner 状态广播；已有项目观察器也只保留 `id/projectId`。因此不能通过增加 Host 的普通 IPC 订阅取得 fork 父来源。现有执行器日志没有可用于这四条来源／预测记录的共享 trace/span，预测的 parent/root Turn 为空；共享执行器身份不构成聊天归属。

本次还只读核对已有本机代理日志、Sentry 缓存和明确的 Statsig 失败／待发事件队列，均未取得来源映射。原产品事件 `CodexCumberbatchPredictionFinished` 的内存 payload 含来源与预测 ID，但现有可读通道没有发布该 payload。固定版本的[完成通知实现](https://github.com/openai/codex/blob/rust-v0.163.0/codex-rs/hooks/src/legacy_notify.rs)只序列化执行身份；其环境来自进程环境快照，也未添加预测来源。没有为此改代理、截获 TLS、附加进程或修改原应用。

常驻采集修复同时补充原进程的启动时间、UID 与父进程关系核对，防止仅凭路径接受重用 PID；执行器集合变化后退出旧监听，由 Host 重新发现。Host 对启动和心跳设置 45 秒期限，超时立即撤销采集就绪状态，并只终止、重启自己创建的观察进程。这些修复不新增来源映射，也不消除短生命周期通知可能漏读的限制。

真实独立 Host 回归已通过：仅暂停本任务创建的观察器，HTTP 在约 45 秒后返回 unavailable，约 4 秒后新观察器就绪；随后在用户已授权的新建测试聊天完成普通模型任务，由原桌面自行产生预测，Host HTTP 实际读到完整新正文。原生读取独立确认测试聊天的准确 Thread / Turn 已完成，但仍不将内容对应关系升级为自动精确 join，记录继续为 unbound。原配置摘要保持一致，临时 Host 与其观察进程已清理，正式服务未替换。私有回执及指纹位于本机忽略的 `.local/suggestion-host-lifecycle-20261017`，不提交真实内容。`pnpm check`、六项观察记录边界和十二项 Python 生命周期检查通过；原进程身份 API 真实自检通过，新增／替换执行器的竞态覆盖属于辅助测试，没有为验收重启原 Codex。手机未接入、未验收。

手机接入仍以前后核对真实 owner 的最新已完成轮次及权威 prediction→source 映射为前提。iOS 当前活动 `turnId` 在完成后会清空，不能用来代替来源轮次。建议不写草稿、不自动采用或发送；未绑定内容继续只保存在独立观察通道。

## 2026-10-17 19:46 CST Host 并发采集与绑定阻塞

新增 Adapter `DesktopSuggestionObserver`，通过固定本机进程启动只读观察器，并从其私有 stdout 接收预测；不依赖模型聊天维持监听。观察器退出后有界退避重启，关闭 Host 时终止本次拥有的观察器。原通知配置、原安装与历史保持不变。

`CODEX_DESKTOP_SUGGESTION_OBSERVER=1` 在 desktop-runtime 启用采集。只在当前观察器实际就绪时声明 `desktop:suggestion-observations`；该能力表示采集记录读取，不表示来源已绑定。已实现的受鉴权 GET 接口及未绑定语义见[运行时契约](../protocol-runtime.md)。原有 DOM PoC 保留为独立路径。

本次真实 DEV 使用独立 Host 身份、状态与端口，两条专用原桌面聊天运行普通模型任务。实际通过 HTTP 取得两条不同预测 Thread / Turn 和完整正文；查询原执行器日志确认两个预测执行区间重叠。另验证未鉴权 401、POST 405、错误 runtime 409，以及重启同一独立 Host 后原身份与两条记录逐字段保持一致，重启没有提交模型输入。原配置摘要前后一致。监听与独立 Host 均已停止，日常正式 Host 没有替换。

### 精确绑定尚未实现

完成通知带预测 Thread / Turn；桌面的安全判定日志带来源 Thread / Turn。重新沿真实预测的 `rpc.request_id=thread/fork:...` 查询所有关联执行器日志，只找到预测分支初始化，没有来源 ID；桌面日志也没有该 request ID。SessionStart / Stop 的现有输入不包含 fork 父 ID。既有 owner 协调 IPC 不转发原始预测 item，当前无具名只读入口到原 app-server 内存线程元数据。已识别的 renderer 产品事件在内存中含完整映射，但未找到可供 Host 使用的现成只读发布通道。

因此，响应始终保留 `source:null`、`bindingStatus:unbound`，不按完成顺序、时间接近或文本相似度关联；API 也不接受外部提交来源映射。当前只能独立保存预测执行身份，不能完成用户要求的多聊天自动精确绑定。手机聊天内的建议展示未实现、未验收；没有把未绑定内容投到任一聊天，也没有增加采用、草稿写入或发送动作。

持久记录按授权根目录及完整机器目标隔离、限量和过期；相同执行身份去重，正文冲突后隔离该身份并跨重启保留。存储异常仅关闭该采集器，不覆盖损坏证据或阻止其余 Host 功能。六项辅助边界检查覆盖并发未绑定、冲突恢复、标识／目录／时间拒绝、目标隔离、目录重定向和存储异常。真实 HTTP／配置摘要／重启回执和原文保存在本机忽略的私有验收目录，不提交 Git。

产品化仍需要一个获准的权威只读记录，同时包含预测与来源的两组 Thread / Turn，并核对 Host / runtime、最新来源和失效状态。当前采集接口不能代替这个记录。

## 2026-10-17 11:35 UTC 原完成通知实读通过

用户明确授权自行创建验证聊天后，通过内置工具创建本机专用聊天，完成两轮普通中文方案评审。没有要求助手输出建议 JSON，也没有主动调用内部预测生成方法；原桌面自行生成下一轮输入建议。

可用出口是原有 `notify` 完成通知。原执行器启动既有 Sky 通知程序时，最后一个参数包含 `thread-id`、`turn-id`、`last-assistant-message` 和 `input-messages`。只读观察这个子进程的参数，在接收端丢弃正文之前即可取得完整数据。预测通知的输入明确标记为 app-generated suggested next user message，正文为 `{"suggestion":"..."}`，与普通助手回复不同。

本次真实证据：

- 第一轮用本机进程元数据观察器捕获原预测 JSON，281 字节；第二轮用仓库的 kqueue 观察器再次捕获原预测 JSON，325 字节。两者都来自原桌面直接拥有的执行器及既有通知程序，不是独立执行器、OCR 或合成事件。
- 普通完成通知的正文 SHA-256 与原历史中准确 Thread / Turn 的助手 item 独立核对一致。第二轮工具实际捕获普通完成和随后的预测完成；最终记录的 60.081 秒观察消耗 0.322 秒 CPU。
- 原 owner IPC 权威快照确认验证聊天恰有两个已完成轮次，最新轮次与第二轮预期来源一致。原桌面各有一条未抑制的预测日志，分别携带这两个来源 Turn。两条建议分别延续各轮最后提出的不同设计问题。
- 本次来源结论为**受控单会话核对通过**：依据准确 owner、已知受控轮次、独立桌面来源日志及内容对应关系。通知本身没有父来源 ID，不能把毫秒级时间邻近升级为并发情况下的可靠自动 join。工具继续输出 `sourceBindingVerified:false`，不自动投影到手机建议领域模型。

工具为 [`scripts/desktop-completion-notify-observer.py`](../../scripts/desktop-completion-notify-observer.py)。它先校验原桌面与执行器关系、固定通知程序和现有配置，再用 macOS `kqueue` 观察 fork / exec / exit；只有准确通知进程才会读取参数。它不修改通知配置、不读取原 stdio、不附加调试器、不访问 UI，不提交模型输入。普通完成只保存元数据与正文摘要；候选必须同时匹配 app-generated 请求标识、客户端和 JSON 结构，才保存正文。真实内容及本机身份只保存在私有目录，不提交 Git。

```sh
python3 scripts/desktop-completion-notify-observer.py \
  --output-dir .local/suggestion-observer \
  --duration-seconds 300
```

仅支持本次验证的 macOS 既有 Sky notify 配置；默认最多 15 分钟，SIGINT 会保存结果并关闭观察。`--application-path` 和 `--codex-home` 可明确指定原安装与配置位置；路径或配置不符时失败，不改写配置。极短进程仍可能在检查前退出，因此此 PoC 不承诺无损捕获。正式 Host 服务尚未接入它，也未开放新的能力声明或 App 接口。本次临时监听均已停止，验证聊天保留供复核。

这次已经解决“实际取得本机原建议正文”的可行性问题。后续产品化需要带来源 ID 的可靠关联、重复与失效处理、生命周期和跨版本验证；不再把普通 IPC 空监听或独立 Chromium 检查作为正文提取门槛。

## 2026-10-17 11:00 UTC 新样本的原 IPC 与存储实读

用户要求继续生成返回结果、既有 IPC 和数据库方向。本轮实际使用原桌面当前的只读通道，没有运行独立 Chromium 检查，也没有提交新的预测或模型任务。

- 从新增的未抑制预测日志取得准确来源 Thread / Turn；通过原 owner IPC 重新发现并跟随其中一条原聊天，取得权威快照，确认最新轮次已完成且与日志来源 Turn 一致。递归检查快照，没有预测字段或建议 JSON。
- 从相近时段的真实执行器日志找到四个临时分支候选及四个 message item ID。对两个仍可由原生工具识别的分支实际调用 `read_thread`，均返回 `ephemeral threads do not support thread/turns/list`；对其中一个执行零等待 `wait_threads`，同样失败。这里已排除仅因旧分支过期而读取失败的情况，仍未取得正文。时间邻近不能证明临时分支与来源轮次的直接关联。
- 通过原协调 IPC 对两个候选执行准确 owner discovery，均没有可跟随 owner。成功读取普通来源聊天不代表临时预测分支也经过该 owner 状态发布。
- 使用 SQLite 只读连接查询四个候选在历史轮次、item、realtime item、线程索引、spawn edge、队列、目录、时间线账本和可视化建议表中的记录，均为零。没有把可视化建议或已排队输入当作 composer prediction。
- 另以这四个分支 ID、四个实际 message item ID 和预测元数据键扫描当前 app origin 的 Local Storage、Session Storage 和 IndexedDB：七个文件，解码 436 条记录，包括独立 Snappy blob，未命中。扫描没有解码错误，原文件读取期间稳定；仍沿用既有离线解析器的 CRC／事务视图限制，不据此断言所有位置永远不存储。

生成日志的来源身份与原 owner 最新完成轮次已核对，但**原建议正文仍为零条，核心实读失败，App 同步未验收**。本轮没有为此增加生产能力或部署服务。原生读取失败回执、IPC 出站白名单／结果、四个候选的数据库结果、存储读取摘要及安装包指纹保存于忽略的私有研究目录；真实身份和会话内容不提交 Git。

## 2026-10-17 按用户澄清开发 Host DOM Bridge

用户指出截图 OCR 偏离核心，并明确要求开发 Host 工具连接 DOM，再由 App 调用 Host。已停止 OCR／手机传输方向，新增固定 CDP DOM 读取 Adapter、本地探针及 Host 的具名 GET 接口；私有配置默认关闭，未部署正式服务或开放正式能力。机制、API、来源复核和本机原应用未实读的限制见 [Host DOM Bridge PoC](desktop-dom-bridge-poc.md)。下面的 OCR 记录保留为历史辅助结果，不再列作原建议来源门槛的通过项。

## 2026-10-17 继续只读尝试与截图来源的实际 Host 传输

用户再次要求尽可能实际取得正文，核心是只读。新增两项持久化检查，没有改原数据：

- 直接只读扫描 SQLite 主文件、WAL 及独立 blob，共 15 个文件、1,857,447,250 字节，覆盖 UTF-8、UTF-16LE／BE 和 JSON Unicode 转义形式。主历史库中找到的文字，经实际 SQL 归属核对属于本次调查的助手／文件改动记录，不属于来源聊天；其他文件没有匹配。原文件在本次扫描期间稳定。没有把残留字节匹配当作原预测提取成功。
- 发现此前 LevelDB 记录扫描未展开的独立 IndexedDB 压缩 blob。按照 [Chromium 的值封装实现](https://raw.githubusercontent.com/chromium/chromium/main/third_party/blink/renderer/modules/indexeddb/idb_value_wrapping.cc)，识别 `ff 11 02` 的 Snappy 封装并实际解压：264,490 字节展开为 776,967 字节，没有预测字段、原文或前缀。另检查 42 条 IndexedDB 记录，只有一个外部 blob 引用，没有内联压缩值。该检查针对磁盘数据，没有使用页面、调试器或进程内存。

同时用 Apple Vision 对**用户已经提供的截图文件**实际运行本机 OCR，没有获取新截图或访问原窗口。第一次识别多出一个汉字；调整图片内的识别区域并启用语言校正后，第二次识别得到实际建议正文，与用户截图对照值仅有一处空格差异。保留原始识别文字，没有按对照值人工改写；精确字符串比较为 false、忽略空白比较为 true。

识别结果明确标为 `user-supplied-screenshot-ocr`、`originalRuntimeExtraction: false`。重新读取准确原 owner，仍确认两个已完成来源轮次、最新预测来源 Turn 一致。随后将识别结果作为**当前调查聊天的普通输出文件**，经已运行的真实 Host 历史读取发现附件，再通过现有下载接口实际 GET 返回 122 字节，SHA-256 与 OCR 文件一致。来源聊天未被写入，没有增加新用户输入或模型 Turn。

随后在独立新建的 iOS 模拟器中编译并普通启动原生 App，手动连接元数据仅提供真实 Host 地址和已有凭据；历史、附件和正文均来自已运行的实际 Host。验证代理只转发 GET，App 尝试更新阅读位置的 11 次 POST 被拒绝，没有转发模型输入或源聊天写入。原生界面实际下载文件、打开 Quick Look 并断言完整识别正文，**1 项测试通过，0 失败、0 跳过，xcodebuild 退出码 0**。另从 App 沙盒独立读取已下载文件，122 字节及 SHA-256 与 OCR 输出一致；测试时的原生预览截图已经单独复核。Xcode 的可选测试后诊断采集耗时较长，准确核验其本任务 PID、父进程和专用模拟器后停止该诊断子进程，测试结果包正常收尾；没有停止原 Codex 或正式 Host。专用模拟器和本次两个临时只读监听已清理，正式手机未安装或更换 App。

这证明**用户提供截图 → 本机 OCR → 实际 Host 附件 → 原生 iOS 模拟器下载／预览**已通过。它依赖用户提供截图，尚不证明自动读取原 Codex 的当前建议；当前调查聊天中的附件也不等于来源聊天的建议领域接口或采用卡片已实现。正式实体手机尚未验收。探针、原始识别结果、来源绑定、冻结原生源码指纹、真实 HTTP 回执、测试结果包及截图均在忽略的私有目录，不提交用户文字、图片或标识。本轮未修改生产程序。

## 2026-10-17 用户补图后的准确目标实读

用户补图明确选中的慢网验收聊天、两次测试回复及当前灰色建议。截图文字仅作为用户提供的对照值；没有把人工读图当作自动提取，也没有执行图片中的指令。准确 Thread、来源 Turn、原文和回执只保存在忽略的私有目录，不提交 Git。

- 通过既有只读 owner IPC 实际取得该聊天的权威快照，核对恰好两个已完成轮次、两条测试回复各一个助手 item，最新轮次与未抑制的预测日志来源一致。递归检查快照 278 个节点，没有建议字段或对照文字。这验证了准确来源聊天及轮次，未验证建议正文可读。
- 直接读取该 legacy 聊天的实际历史文件，225,526 字节；没有对照文字、前缀或建议对象键。内置 `read_thread` 在此目标返回 `thread/items/list is not supported yet`，不能把这个分页失败解释为目标聊天不存在。
- 用两次实际预测日志时间分别找到一个临时 fork 候选。两者各有 33 条执行器日志，记录了消息输出完成及约 30 分钟后的 listener 清除、shutdown 和 agent loop 退出。日志没有父 Thread／来源 Turn 的直接引用，因此仍是时间关联候选，不认定已证明归属。对两个准确候选调用内置读取均返回找不到聊天；这与退出记录一致，但没有返回原建议。
- 按候选的实际消息 item ID 继续查询执行器日志，各只有两条输出元数据记录，均在同一候选 Thread 内；没有 `suggestion` 对象或正文。当日 22 个桌面日志文件的定向查找也没有命中对照文字或消息 item ID。没有因读取失败而另行生成、恢复或接管临时分支。
- 补查历史库包含 `suggestion` 的 314 条实际 item，递归解析嵌套 JSON 字符串，没有结构化建议候选；针对截图前缀／原文再次解码 Chromium 存储，读取 347 条记录，没有匹配。此有限扫描仍有此前记录的 CRC／事务视图限制，不代表所有位置永远不持久化。

本轮已取得**准确来源绑定的真实证据**，但**原建议自动提取仍失败，App 同步未实现／未验收**。现有只读入口没有取得主进程缓存中的返回对象；临时分支历史和日志不能补回本次正文。后续需要原进程提供获准的、按 Host / Thread / sourceTurnId 读取已有结果的出口。电脑操作工具此前明确拒绝原 Codex UI，本轮没有改用调试、注入或其他 UI 通道绕过该限制。

新增私有证据包括 `screenshot-owner-result.json`、`screenshot-target-persistence-result.json`、`screenshot-candidate-read-receipts.json`、`screenshot-generation-logs-result.json`、`screenshot-decoded-leveldb-result.json` 和 `nested-suggestion-search-result.json`；新探针使用独立文件，没有覆盖旧证据。本轮未修改正式 Host / iOS、原应用或登录配置，没有创建临时工作树。

## 2026-10-17 扩大数据库检查并复用语音转写鉴权

用户要求继续增加实际尝试，并明确允许数据库读取、参考已实现的语音转写。新增证据如下，均未提交模型输入或访问被拒绝的原窗口。

### 真实 HTTP：预测配置读取 200

复用语音转写已有的 `readDesktopBackendAuth`，只调用原有 metadata 白名单、固定 `refreshToken: false`，确认辅助进程退出后，以内存中的现有凭据向安装代码中确证的 `GET https://chatgpt.com/backend-api/wham/predictions/config` 发起一次请求。沿用已有代理，未改路由、未跟随重定向、未重试或试探其他路径。

**实际 HTTP 200**，JSON 返回 1,409 字节，字段为 `is_enabled / prompt / prompt_version / prediction_reasoning_effort / unsupported_models / sample_rate / live_sample_rate / catch_up_sample_rate`。本机实际开关为 true，prompt_version 为 `5`，live_sample_rate 为 1；预测推理档位由配置指定为 `high`。没有调用生成或修改用户开发任务的推理设置。

该结果验证语音转写所用鉴权方式可用于此已知配置接口；响应没有 `text` 或 `suggestion`，不能将配置读取写成建议提取成功。没有实际调用独立生成 API，也没有推断其他地址具有相同权限。令牌未输出或落盘，原 `auth.json` 字节摘要前后一致；保存的响应摘要只含配置字段、prompt 长度／摘要，不保存凭据或 prompt 正文。

### SQLite 与 Chromium 存储：扩展到实际记录解码

- 额外读取桌面自己的 `sqlite/codex-dev.db`，检查目录、自动化、时间线账本等实际表；`thread_timeline_ledger` 为 0 行。`live_visualization_suggestions` 也为 0 行，且其业务语义是可视化建议，不能当作输入框预测。
- 约 1.7 GB 的 `thread_history_1.sqlite` 中，有 54 条记录包含预测相关词。逐条解析 item JSON 后，44 条为命令、8 条为文件改动、2 条为助手正文，均来自相关研究／源码描述；未发现 `suggestion / predictionThreadId / sourceTurnId` 等实际对象键。不能将本次研究回显的文本当作原预测数据。
- `state_5.sqlite` 没有 `composer_predictions` 来源记录；补查 memories 库的表结构，没有发现预测结果存储契约。临时预测不在当前持久线程索引中。
- 对 Chromium Local Storage、IndexedDB、Session Storage 的 6 个数据文件解析 LevelDB SST／WAL，实际解压 Snappy 块，并以 UTF-8／UTF-16LE 查找标记，共读取 295 条记录，未匹配 `cumberbatch / predictionThreadId / predictionTurnId / prediction_source_thread_id / composer_predictions / suggestion` 目标标记。原文件读取期间稳定，未通过数据库引擎打开、加锁、恢复或写回，也没有导出原值。此探针未校验 CRC 或重建最新事务视图，不能把有限扫描扩大为所有存储的永久否定结论。

### 临时聊天的新线索与实际读取结果

以一条原预测日志时间为中心查询执行器日志，436 条记录中找到一个未在持久线程表登记、由 `Codex Desktop` 的 `thread/fork` 创建的候选 ID。该候选有 30 条日志记录，包含真实临时任务执行；**没有找到与已选原 Thread／来源 Turn 的直接关联，因此只记为候选，不认定它就是该条预测分支。** 日志也没有建议正文。

对这个从真实日志取得的准确 ID，已实际尝试：

| 路径                                                     | 本次结果                                                                                        |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------------------- |
| 内置 `read_thread`                                       | 返回 `ephemeral threads do not support thread/turns/list`，确认为临时会话的分页边界，未返回正文 |
| 内置 `wait_threads`，timeout=0                           | 返回同一分页限制，未取得状态或正文                                                              |
| 既有 owner IPC，仅准确 ID 的 discovery／following 白名单 | 原 owner 未找到，快照 0；没有发模型输入                                                         |
| 独立只读 reader，`thread/read` + `includeTurns: true`    | 读取被拒绝，未用 resume 或 start 接管；本轮辅助进程已确认退出                                   |

本轮的新增真实通过项是**独立 HTTP 配置访问及鉴权复用**，新增定位是**实际临时会话及其读取边界**。真实原建议正文、原聊天／来源轮次绑定和 App 同步仍未通过。没有新增生产能力、修改正式 Host / iOS 或用独立生成替换原结果。

私有探针及结果保存在 `.local/suggestion-feasibility-20261017/`，包括 `probe-prediction-config.mjs`、`probe-leveldb-readonly.py`、`deep-history-marker-audit.json`、`decoded-leveldb-audit.json`、临时会话各读取回执与 `expanded-real-evidence.json`。未创建临时工作树。

## 2026-10-17 真实提取尝试：当前访问阻塞

用户进一步要求实际提取本机一条原建议、核对聊天和来源轮次，再同步到 App。本轮执行真实工具和连接检查，没有再用 fixture 作为交付结果：

| 实际动作                                 | 返回结果                                                                                                                                                           |
| ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 标准电脑操作工具读取当前正在运行的原应用 | 精确路径解析后，明确拒绝：`Computer Use is not allowed to use the app 'com.openai.codex' for safety reasons.`。已停止该 UI 路径，没有换截图、OCR、调试器或注入绕过 |
| 安装 CLI 的 `app-server daemon version`  | 退出码 1，默认 `app-server-control.sock` 不存在，未连接到运行时                                                                                                    |
| 安装 CLI 的 `app-server proxy`           | 退出码 1，同一默认控制 socket 不存在；未发送 JSON-RPC，未启动执行实例                                                                                              |
| 内置 `read_thread` 读取准确目标          | 真实返回成功，目标 Thread 和原预测日志来源 Turn 均匹配，轮次 `completed`；返回结构为 `thread / page / turns` 等，没有建议字段或预测结果                            |
| 官方 remote-control 登记状态，只读查询   | 本机登记表为空，没有可用于连接原执行器的已登记远程入口；未创建登记或扩大访问                                                                                       |

本次不是仅复用旧 UI 限制：标准工具在当前原应用上再次实际返回了明确拒绝。该拒绝也不能通过用户重复确认转化为允许的调试注入。内置聊天读取只证明对应内容可读，不证明能读取主进程的预测返回对象或渲染器插件 state。

本轮结果为 **真实原建议正文未取得，App 同步验证未开始**。已定位内部函数／DOM 结构，尚无可供当前 Host 调用的原结果读取入口。继续完成需要环境提供获准的原结果只读访问通道；另起预测执行器或人工输入文本不能补作这次自动提取成功。

私有实际回执、内置读取结果的无正文摘要及来源比对保存在 `.local/suggestion-feasibility-20261017/real-attempt-20261017T094731Z/`。没有修改原应用、启动方式、登录凭据、正式 Host / iOS 服务，也没有创建临时工作树。

## 2026-10-17 继续追踪生成接口与界面节点

用户要求分别沿生成请求／响应和 Electron 页面节点调查。本轮已定位两条具体链路，上一轮“未打通”的结论限定为 **Host 现有 owner IPC 未取得正文**，不能扩大为没有生成接口或没有可读 DOM 节点。下面区分当前安装代码的结构证据、隔离执行的辅助验证和尚未完成的原窗口实读。

### 生成接口：已定位请求、事件与返回结构

当前 renderer 订阅 `turn-completed`，核对自己仍是原 owner、来源为普通非临时会话、最新 Turn 与事件一致后，调用：

```text
cumberbatch.requestUserMessagePrediction({
  hostId, threadId, latestTurnId, generationTrigger: "live"
})
```

进入聊天时还有 `generationTrigger: "catch_up"` 路径。主进程按 Host / Thread / Turn 查询内存缓存；命中可直接返回，未命中才进入生成。`/wham/predictions/config` 读取远端开关、采样、prompt、prompt_version、prediction_reasoning_effort 等配置；**此路径不是返回建议正文的生成接口**。没有抓取本机登录凭据或主动调用这个私有 HTTP 路径。

真实生成所调用的安装客户端辅助函数已继续追到其内部实现：

1. 对原 Thread 调用 `resumeThread` 获取当前配置并核对预测适用性。
2. 经原 App Server 客户端调用 `thread/fork`，传入原 `threadId`、`experimentalPredictionMode: true`、`ephemeral: true`、`excludeTurns: true` 和 `threadSource: "composer_predictions"`，返回临时预测 Thread ID。
3. 在该临时 Thread 调用 `turn/start`，输入配置中的预测 prompt 并要求只返回 `{ "suggestion": string | null }`。请求元信息带 `prediction_source_thread_id`、`prediction_source_turn_id`、`prediction_trigger`；`turnTrigger` 为 `composer_predictions`。
4. 原客户端监听该预测 Thread / Turn 的 `item/agentMessage/delta`、`item/completed` 和 `turn/completed`。完整 item 可覆盖累计 delta；完成后解析 JSON，提取 `suggestion`。`turn/start` 的即时确认只给 Turn，正文来自随后事件。
5. 完成或失败后取消该临时 Thread 的订阅。预测 fork 失败时当前调用禁止另建 fresh Thread 兜底。

预测辅助函数返回 `text`、`predictionThreadId`、`predictionTurnId`、模型／推理档位、promptVersion、耗时等；控制器随后加入 `sourceTurnId` 和 `generationTrigger`，返回 renderer。例如结构如下，均为占位值：

```json
{
  "text": "<原始建议正文>",
  "sourceTurnId": "<原聊天来源轮次>",
  "predictionThreadId": "<临时预测聊天>",
  "predictionTurnId": "<临时预测轮次>",
  "generationTrigger": "live",
  "promptVersion": "<配置版本>"
}
```

这条链路目前使用普通 fork／turn 及其消息事件，不能把另一个实验类型 `thread/prediction/updated` 当作截图建议实际已采用的返回路径。没有将配置 HTTP 路径、普通模型后台 HTTP 地址和 App Server JSON-RPC 混为同一个接口；本轮没有截获原进程的线上 HTTP 请求或响应。

### 界面节点：已定位具体 span 与插件数据

安装 renderer 中的输入预测插件直接创建以下结构；它是输入框中的 ProseMirror decoration：

```html
<span class="select-none" data-cumberbatch="" inert aria-hidden="true">
  <span class="cumberbatch-content"><!-- 建议内容 --> </span>
  <!-- 部分场景还有 Press Tab to accept 提示 span -->
</span>
```

正文候选选择器为 `[data-cumberbatch] > .cumberbatch-content`，可进一步限定于已确认的 `.ProseMirror[role="textbox"]` 输入框。读取外层整个 span 会混入采用提示，不能直接当建议正文。`aria-hidden` 影响辅助功能树，`inert` 限制交互；它们本身没有移除 DOM 中的文本。

数据流也已定位：renderer Query 返回完整预测对象 → 输入视图 transaction 的 `cumberbatch` PluginKey metadata → 插件 state 中的 `prediction.text` → `parsePromptText` → DOM serializer → 上述 span。原插件自己的读取函数先校验展示条件，再直接返回 `prediction.text`，说明 DOM 前仍有保留原始文本的数据层。DOM 经排版和 mention 转换后可能丢失原始换行或标记，不能默认与原文本逐字一致。

显示要求包括输入文档只有一个空 paragraph、没有输入法组合、没有活动 mention 菜单。采用后文本进入真实草稿；新轮、清除预测或不符合显示条件时不再装饰。节点本身不携带 Host / Thread / 来源 Turn，且页面可能有多个输入框或预热视图，因此将来读取必须另核对准确页面目标、可见输入框及来源轮次，不能全局取第一个匹配元素。DOM 未出现也不能直接解释为该聊天未生成建议。

### 本轮辅助执行与仍缺的实证

从当前安装包精确提取生成辅助函数及插件读取／展示条件函数，在独立 Node VM 中以合成 client 和事件运行 **11 项检查，全部通过**：流式 JSON、完整 item 覆盖 delta、明确无建议、无效 JSON、失败轮次、fork 失败不另开任务，以及空输入读取、已输入正文、输入法组合／组合标记和 mention 菜单的显示隔离。未执行整个安装模块、未附加 Electron、未读取密钥、未发真实 RPC 或模型任务；JSON 校验器使用测试替身，没有将其写成实际 Zod 模块已验收。

私有探针为 `.local/suggestion-feasibility-20261017/probe-generation-contract.mjs`，结果和合成请求／事件保存在同目录；当前安装依赖模块、生成辅助函数和 DOM 片段均有摘要或原文件供本机复核。该 fixture 证明已定位函数的行为与所述结构一致，**不是截获原桌面的一次真实预测，也不是原页面 DOM 提取通过**。

原 Codex App 的实时 UI 访问此前已被电脑操作工具明确限制，本轮没有通过远程调试、注入、修改安装包或另一个 UI 工具绕过。两条候选来源均已具体定位；下一实践门槛是取得可用且符合访问边界的原 renderer 只读通道，或由原进程发布上述带来源的生成结果，再将同一条真实正文经 Host 送入 App 核对。当前没有修改或部署 Host / iOS 功能。

## 2026-10-17 新版实际复核

本机现运行整合版桌面 `26.1013.40934`，安装 CLI 为 `0.163.0-alpha.10.2`。本轮重新读取当前安装代码、重新导出实验 schema，并实际连接已完成预测的原会话 owner；没有沿用旧安装包的结论充当本次证据。安装归档 SHA-256 为 `5ddc4e48259aa6f979e04529b1a5a4ae54b0f5c4ad6db5c6e004924c10916ddb`。

**本机确实已经生成过建议，但现有只读入口仍未取得正文。** 当日桌面日志中找到 40 条 `cumberbatch_safety_decision`；对应调用仅在预测 `text` 非空时记录，包含来源 Thread / Turn 与是否抑制，不含建议正文。该记录证明预测生成路径在本机实际运行，不证明输入框曾显示或手机已同步。

本轮选取当前项目内、未归档、最新轮次已完成且存在未抑制预测日志的目标，进行 15 秒有界被动观察：

| 检查                | 本次结果                                                              | 证明范围                                                                     |
| ------------------- | --------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| 原 owner 与来源轮次 | 准确目标 owner 可用；最新 Turn 与预测日志来源一致，状态为 `completed` | 已覆盖“完成轮次且预测已生成”，补充前轮只观察运行中聊天的不足                 |
| 原 owner 快照       | 收到 1 次权威快照，未发现建议字段                                     | 该目标本次快照不承载预测缓存                                                 |
| 原始 IPC 帧         | 目标预测通知 0；嵌套预测候选 0                                        | 缓存结果没有经本次通道交付；观察开始在生成之后，不证明生成瞬间的全部通知行为 |
| 执行副作用          | 预测请求 0，模型输入 0；原桌面及其执行子进程集合前后相同              | 没有为验证另行生成或启动替代执行器                                           |
| Host / iOS          | 未增加接口、能力声明或占位显示                                        | App 同步未实现，原生真实 DEV 未验收                                          |

新版静态调用链仍为 `requestUserMessagePrediction` → 内存 `fetchQuery` → 缓存未命中时执行预测分支。服务对 renderer 只提供可用性查询和预测请求；没有发现只读缓存 getter。该控制器没有持久保存结果的调用，`getDebugLog` 的数组也没有在该生成路径填入结果。遥测只带预测身份、耗时、用量和交互动作，不带预测正文，不能据此还原文本。

新版实验 schema 仍只有 `thread/prediction/request` 和 `thread/prediction/updated`，前者只有 `threadId / sourceTurnId` 参数并返回空确认，没有缓存读取模式。当前安装模块没有调用前者，通知策略仍将后者标为 `false` 并加入初始化 opt-out。实际两个原桌面执行子进程均未发现具名 Unix listener 或 TCP listener；本轮未启动 daemon、接管 stdio 或更改初始化参数。[官方文档](https://learn.chatgpt.com/docs/app-server)确认 opt-out 按连接抑制通知，当前检索页面没有 prediction 的缓存读取说明。

持久化旁路也作了只读复核：四个相关 SQLite 库的表结构没有独立预测结果表，当前 47,726 条执行器日志记录未匹配预测来源标记或建议 JSON；桌面日志只见上述元信息。对 ambient、Local Storage、IndexedDB 与 Session Storage 共 32 个文件作原始字节探查，未匹配截图文本片段或预测身份键；这种扫描不能排除压缩、编码差异或其他未覆盖存储。全局持久状态只见已接受预测的布尔标记，没有取得预测正文。ambient 数据不能当作输入框预测。

私有证据和探针保存在忽略目录 `.local/suggestion-feasibility-20261017/`，包括安装模块摘要、当前 schema、各存储检查和准确 owner 观察记录；目标身份及真实安装代码不提交 Git。观察探针对每条出站帧检查只读白名单，仅允许初始化、准确 owner 发现、被动 following 和拒绝承担执行的 discovery response；保存正文为 0。本轮没有创建临时工作树，也没有变更正式服务。

**阶段结论：确认本机具备预测功能；在当前版本、正常启动和现有只读通道约束下，尚未打通原建议正文提取，不能宣布同步可行性通过。** 下一前置条件仍是原桌面的具名已生成结果读取／发布出口，须同时提供来源轮次和失效语义。另行生成、从回复猜测下一步或人工抄录截图都不能作为原建议同步验收。

## 2026-10-15 初查结果

本机安装的桌面 `26.1007.30912` 仍通过 Electron 的 `cumberbatch` 服务生成、缓存并显示输入预测。现有 owner 协调 IPC 没有预测读取或订阅方法，其会话快照不承载该查询缓存。没有找到能供独立 Host 读取这条原建议的现成出口。

相比第一轮研究，后续导出的安装 CLI 完整实验协议确实存在 `thread/prediction/request` 和 `thread/prediction/updated`。最新被动订阅核对进一步确认：当前原桌面初始化时明确 opt out 预测通知，原执行器没有公开的具名监听入口，已有 owner 协调 IPC 在有界观察中没有转发预测。因此不能笼统地说“Codex 协议完全没有预测接口”，也不能凭这两个类型存在声明同源接入成功。

电脑操作工具对原 Codex App 的读取明确返回安全限制。本次没有改用截图、OCR、辅助功能脚本或其他界面自动化通道绕过该拒绝。

## 已核对的证据

### 安装 CLI 的实验协议

使用安装 CLI 自身的工具子命令导出 schema；该操作不启动 App Server，不创建会话或模型轮：

```sh
<installed-codex-cli> app-server generate-json-schema --experimental --out <private-research-dir>
```

导出结果：

| 类型                                  | 实际结构                               | 对只读接入的含义                                           |
| ------------------------------------- | -------------------------------------- | ---------------------------------------------------------- |
| `ThreadPredictionRequestParams`       | `threadId`、`sourceTurnId`             | 没有 `cacheOnly` 或查询已生成结果的模式                    |
| `ThreadPredictionRequestResponse`     | 空对象                                 | 请求响应本身不包含缓存建议                                 |
| `ThreadPredictionUpdatedNotification` | `threadId`、`sourceTurnId`、`result`   | 协议具备有归属的结果通知类型，但需来自执行原预测的同一服务 |
| `ThreadPredictionResult`              | `completed` 加可空 `text`，或 `failed` | 文本、明确无建议与失败可以区分                             |
| `Thread`（`thread/read` 返回）        | 无建议缓存字段                         | 不能把普通历史读取当作预测缓存读取                         |

没有调用 `thread/prediction/request`。仅凭 schema 不能证明它是只读、会命中桌面已有缓存或不会触发另一次生成，因此不将它作为读取探针。

官方 [App Server 文档](https://learn.chatgpt.com/docs/app-server)提供上述 schema 导出流程；本次检索的页面没有描述 prediction 的读取行为。以当前安装 CLI 的实验契约为补充证据，不把文档缺项理解为协议永久不支持。

### 已安装桌面代码

对当前安装包作只读检查，没有修改或执行提取的代码：

- `cumberbatch` 的 `requestUserMessagePrediction` 调用 `request`，后者使用 `fetchQuery`。缓存未命中时进入 `runPrediction`，不是纯缓存 getter。
- 预测通过临时 `ephemeral` 分支生成，主进程返回 `text / sourceTurnId / predictionThreadId / predictionTurnId` 等结果，renderer 将结果放入输入插件。不能用另一执行实例重新生成来替代同步。
- 当前主进程及前端代码没有 `thread/prediction/request` 调用；`thread/prediction/updated` 在通知消费表中标为 `false`。发现该协议类型不表示截图中的预测已经通过它发布。
- 现有协调 IPC 的版本方法表包括 owner、stream、回复、设置、审批和队列等方法，没有预测读取或订阅。`queued-followups` 是已排队输入，不是灰色预测，不混用这两个概念。
- 主进程对 renderer 暴露的 `cumberbatch` 服务只有可用性查询和预测请求；未找到对外的 `readExisting` 缓存 getter。内部 `getDebugLog` 不是稳定的建议读取契约，也没有被当作外部接口调用。
- 当前原桌面执行子进程按正常 stdio 启动；本次只读进程/文件描述符清单未显示可供独立 Host 连接的具名 App Server Unix listener。没有接管其管道或更改官方启动方式。

安装包 SHA-256：`75e397e2280dde93b70a85aa926f4282c728eacc84dfeb15ba7fb6ddd84628af`。该摘要只界定本次检查环境，不作为连接门槛。提取模块、完整 schema 和详细结构记录保存在忽略的私有研究目录，不提交安装代码、真实会话或机器私有路径。

### 原桌面界面工具限制

本次针对原 Codex App 的只读 UI 检查，电脑操作工具明确拒绝访问，原因是该 App 的安全限制。没有执行后续界面动作，也没有改用其他电脑操作通道绕过此拒绝。用户授权继续实现功能，不代表工具已解除该限制。

### 2026-10-15 原运行时被动订阅复核

用户进一步要求只订阅原桌面同一运行时的实验通知，不重复生成、不改变启动方式。本轮未再次尝试被拒绝的 UI 入口，只检查协议结构、原执行器现有监听信息和既有协调 IPC。

**初始化链路已追踪到实际配置。** 当前安装代码的通知策略将 `thread/prediction/updated` 标为 `false`；这些 false 项经 `Object.entries(...).filter(...)` 组成 opt-out 列表，导出后被原桌面初始化构造器写入 `initialize.params.capabilities.optOutNotificationMethods`。这比“前端不展示”更具体：原桌面的这一连接明确请求上游不要发送该通知。官方 [notification opt-out 说明](https://learn.chatgpt.com/docs/app-server)也将其定义为逐连接抑制，不是跨连接订阅或回放机制。

**现有共享入口已实际尝试。** 安装 CLI 的 `app-server daemon version` 与 `app-server proxy` 都只连接现有默认端点，实际均以 `No such file or directory` 失败；proxy 未发送任何 JSON-RPC。没有执行 daemon start、bootstrap、restart、enable-remote-control 或 stdio 启动。原桌面直接执行子进程的文件描述符只读核对显示具名 Unix socket 与 TCP listener 均为 0。因此没有把另起的 reader 或 daemon 当作原运行时订阅成功。

**既有原 owner IPC 已真实被动跟随。** 使用当前准确 Thread ID 做 owner discovery，确认 `state.id / hostId=local / 原 owner` 后跟随，得到如下结果：

| 观察            | 原 owner 快照 | 目标预测通知 | 额外核对                                                              |
| --------------- | ------------- | ------------ | --------------------------------------------------------------------- |
| 45 秒窗口       | 14 次         | 0            | 没有建议字段；原 owner 始终可用                                       |
| 15 秒原始帧复核 | 5 次          | 0            | 复核原始帧的方法名及嵌套方法，不依赖 Adapter 已映射事件；预测候选为 0 |

探针对所有出站帧设置只读白名单，只允许初始化、准确目标 owner discovery、following=true 和明确拒绝承担执行的 discovery response。没有调用预测、start、resume、fork、steer、设置或审批方法；没有创建 reader。两次观察前后原桌面及执行子进程集合相同。第二次解码也允许原生通知没有 `hostId` 的形态，避免仅因缺少该字段而漏计；未知发布者仍不能赋予来源可信度。

这两个窗口观察的是正在运行的原会话，**没有等待某次新预测完成，也没有新生成或读取到一条预测正文**。零事件仅说明该窗口及通道没有看到预测，不能证明所有轮次或未来版本都不会发出该事件；19 次原 owner 快照只证明当前状态订阅正常，不能记为建议同步通过。

私有记录包括只读探针、初始化结构核对和 `passive-subscription-audit.json`，逐份观察结果均带摘要；不提交原会话、发布者 ID、进程 ID 或正文。本次未新增生产能力、Host 路由或 iOS 占位功能，后续 App 实现仍依赖真正可用的来源。

## 早期研究尚缺的桌面侧契约

需要原桌面提供具名的缓存读取或订阅，至少具备以下语义：

1. 准确绑定原 Host、Thread 和来源 Turn，只返回该目标已生成的结果。
2. 缓存未命中明确返回无结果，不启动预测、临时会话或普通模型 Turn。
3. 轮次变化和结果失效可观测，晚到结果不能绑定到新轮次。
4. 通过独立 Host 可使用的受支持通道提供，不要求修改官方程序、启动链路、历史或普通控制聊天。
5. 读取返回建议、明确无建议、读取不可用三种可区分状态，能与桌面实际显示独立核对。

在该契约或等价的合规扩展点出现前，本仓库无法仅靠新增一个 Host HTTP 路由读出 Electron 私有内存。访问受保护 App Tools、调试注入或重新运行预测均不构成这项前置条件的完成。

## 当前实施及验收状态

| 项目                                      | 本次状态                                                               |
| ----------------------------------------- | ---------------------------------------------------------------------- |
| 建议卡片产品方案                          | 用户已选定                                                             |
| 当前安装包、实验协议与原连接 opt-out 核对 | 已完成，只读研究证据                                                   |
| 原 owner 有界被动跟随及原始帧复核         | 已完成，19 次快照，未观察到预测；不作为完整功能验收                    |
| 原桌面同一条建议只读通道                  | 两条真实原生响应／通知正文一致，精确来源绑定通过                       |
| 多来源并发                                | 来源轮重叠 25.268 秒，预测生命周期重叠 5.755 秒；Provider 推理流未重叠 |
| Host / Adapter 只读接口                   | 已实现，真实 HTTP 预检返回两条绑定建议及一致来源轮次                   |
| iOS 只读卡片                              | 实体 iPhone 普通 App 两聊天四次全文匹配，草稿保持，读取无额外模型输入  |
| 采用、追加、替换、撤销、发送              | 保留为早期交互设计，当前只读范围未实现或启用                           |
| 原桌面 + 原生 App + 真实模型 DEV          | 本机原桌面／真实模型／实体 iPhone／独立 owner 的本次只读链路通过       |
| 日常正式服务、统一包与其他电脑部署        | 本次未验收，不由独立 DEV Host 推定                                     |

本页仅记录对应实现和证据范围：本次只读链路已通过实体手机与原桌面独立核对；主动注入迟到响应、过期或断线等辅助边界不扩大为全部真机故障场景通过。后续状态见[建议卡片设计](../design/codex-next-turn-suggestions.md)，本机结果不扩展为默认启动、正式部署或其他环境通过。
