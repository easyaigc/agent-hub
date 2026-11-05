# Windows 建议采集：独立于原桌面启动的可行性研究

2026-11-01。目标是保留原 Codex 已生成的下一轮建议，让采集器的缺失、配置错误、崩溃、挂起或 Host 离线只影响采集。正常图标启动、原 CLI 的 initialize 和应用升级不得依赖采集器。此前停用全局垫片是启动安全处置，完整建议采集仍是待完成目标。

## 2026-11-04 第四轮：前台原生灰字的真实正样本

人类将本轮范围明确缩为**前台聊天**，授权建立临时验收聊天并继续验证“透明转发＋前台只读确认”。原生灰字、原 App 不修改、正常启动习惯不变的要求继续成立；后台全覆盖仍沿用第三轮未通过的结论。

### 本轮结论

**前台路径已取得真实正样本，技术可行性通过。** 同一原桌面进程完成真实模型任务后，自然发起建议请求；独立转发器取得候选全文、来源聊天、来源轮次和原生 response ID。人类提供的原窗口截图与独立电脑观察均显示完整灰字，转发全文与视觉读回的 37 个字符逐字相等。没有采用建议、调用替代预测或用 Host 生成文本。

这是一个真实前台样本的闭环，**不是常驻 Host 功能、全后台覆盖、跨版本兼容或手机验收通过**。UI Automation 树、文档文本和输入控件值均未暴露本次灰字正文；当前只读确认依靠画面，UIA 空值不能证明没有建议。

### 实际执行与证据

| 环节         | 本次结果                                                                                              | 边界                                                                                          |
| ------------ | ----------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------- |
| 原桌面与启动 | 原 GUI、直属 CLI 和正式 Host 保持原进程及启动时间；未修改安装文件、快捷方式或 `CODEX_CLI_PATH`        | 未重启原 App，未部署常驻服务                                                                  |
| 新聊天路由   | 短暂设置官方 `openai_base_url` 指向回环转发器，再恢复原配置；新建临时聊天由原桌面执行                 | 实际原 CLI TCP 连接、真实认证 WS 101 与完成答复分别核对；不宣称任意已加载聊天会热切换路由     |
| 第一条测试   | 简短封闭回答完成，观察窗口中没有灰字                                                                  | 无效正对照，不作为成功或技术不可行证据                                                        |
| 第二条测试   | 同一聊天完成无工具的排期练习后，自然生成原生建议                                                      | 官方桌面聊天入口提交一次，原窗口可见；UI 输入缓存错误另记，未因此重放消息                     |
| 透明转发     | 预测出站请求携带准确 source Thread／Turn，压缩 WS 返回全文与 response ID                              | 固定官方 HTTPS 上游、正常证书校验；认证头仅在转发内存中，不进入观察 Worker 或审计             |
| 前台确认     | 人类截图与独立窗口观察中完整灰字均与候选逐字匹配；两次稳定只读观察一致                                | 没有采用建议；官方导航与后续只读阶段分开，导航可能触发 catch-up                               |
| 本地 OCR     | Windows.Media.Ocr 使用本机中文资源识别所提供 PNG；目标行仅移除 OCR 插入空白后，与候选连同标点完全相等 | 原始 OCR 含额外空格，不能称原始输出逐字符相等；仅本地图片实验，未完成自动抓屏／目标绑定产品链 |

配置事务用原文件摘要和原子替换；恢复后保留原 App 设置界面写入的 `desktop.composer-predictions-enabled = true`。预测开关原先开启，实验曾关闭再开启，最终仍开启；这种刷新可能触发原生 catch-up，不记作被动读取。正样本来自第二轮自然完成后的请求。

### 探针与生产边界

第一版遇到真实 WS 压缩时继续转发、拒绝观察，未采到候选。第二版支持本次协商的 `permessage-deflate`、上下文复用和分片，分离观察 Worker，并限制缓冲与审计预算。九项辅助断言通过，覆盖 HTTP／WS 正文字节转发、来源关联、授权头不入审计、取消、非 101 响应、未知协议只停止观察及压缩连接；这些 fixture 不替代真实桌面正样本。

**不修改原 App、不更换启动入口可以完成本次采集，但固定回环转发仍是推理链依赖，尚未实现转发器故障时的自动直连。** 标准 ChatGPT 上游本次真实认证成功，不代表所有 workspace／租户路由等价。已加载父线程保留配置，也不等于可以给任意已运行聊天无缝接管。

常驻实现仍需配置恢复、服务生命周期和故障恢复，以及准确前台聊天／最新轮绑定；画面缺失、截断、模糊、非空草稿、聊天切换或旧结果必须不发布。屏幕识别用于确认，网络原文用于保存；不能以去标点等宽松 OCR 规则授权逐字一致。本次已经建立可供实现使用的前台正样本，不能再笼统称该前台方案不可行。

本机私有证据位于忽略目录 `.local/windows-suggestion-feasibility-20261104/foreground/`：`foreground-positive-verdict.json`、人类截图、第二版审计、TCP owner、UI 观察、OCR 结果及配置事务。真实聊天正文、身份和机器绝对路径不进入 Git。生产 Adapter、正式 Host、iOS／Android 能力与 A6 状态未改变。

收尾已核实：临时聊天由官方入口归档，目录返回 `notLoaded`；另行确认转发器在途请求、缓冲、连接和丢弃计数为零后正常停止，进程与监听端口消失。临时 URL 已恢复，原配置备份及空恢复目录已精确删除；保留合法预测开关设置、私有最小证据及探针。原 GUI、两条直属 CLI 与正式 Host 的身份、启动时间和正式端口保持。回执为 `foreground-closeout-receipt.json`；没有触碰历史清理拒绝对象。文档差异检查通过，未将文档／忽略探针变化宣称为全项目 `pnpm check` 或生产回归。

## 2026-11-04 第三轮：透明转发与原生灰字的最终判定

本轮人类确认必须采集**原生灰字，覆盖所有聊天，包括后台聊天**，保持正常启动习惯、不修改原 App。Host 独立调用模型生成建议不属于可接受方案。本节是前两轮后的结论；没有新增生产部署或手机验收。

### 明确结论

1. **Windows 支持 Mac 历史 Trace 采集所需机制；当前原生 sample 不能仅靠 Trace 完整采集。** 同版官方 Windows CLI 的普通轮次和旧式 prediction fork 均实际留下全文；`thread/sample` 在 HTTP 与 WS 下均原 RPC 有全文、外层 Trace 无正文。限制来自当前 sample 截获机制，不是 Windows 文件格式或文件权限。没有因此推断当前 Mac 安装也仍走旧分支，更没有新增跨机验收。
2. **Windows 透明 Responses 转发的协议级 PoC 已通过：按原 App 参数结构调用真实 CLI，可取得假服务候选正文及其 wire 来源。** 观察者读取实际出站请求中的来源元数据，不发起替代生成；HTTP 与 WS 的双来源并发均可关联正文、来源轮和 response ID。尚非原桌面自然生成或真实认证通过。独立观察 Worker 故障不阻塞本次转发实验；转发器自身仍是新增推理依赖。
3. **在本次安装版与已验证出口范围内，完整的后台原生灰字采集仍不能交付。** 网络上的生成正文尚未经过桌面解析、安全判断和缓存选择；现有独立读取通道不能可靠证明所有结果中哪一个成为有效灰字。不能将协议级正文采集升级成完整功能通过，也不把“尚未找到可靠出口”写成 Windows 上永远不可能。

### 真实 CLI 透明转发实验

Windows CLI 固定为 `0.166.0-alpha.6`、SHA-256 `925c7d0665ffa49b0dcf748e034a805a04da984164ac92301432073b47789b99`。每次使用独立配置、自有回环假模型服务与临时目录；没有读取真实账号凭据、修改用户配置、附加原 App 或给真实聊天发消息。原始 App 的正常图标、安装文件和正式 Host 保持。

原安装资源 `.vite/build/early-bootstrap-632-62c641245a52ef0e.js`（摘要见后文）的 UTF-16 偏移约 1783654–1784100 显示 native sample 本身传入 `responsesapiClientMetadata`，包括 `prediction_source_thread_id`、`prediction_source_turn_id`、`prediction_trigger`。实验按此结构调用真实 CLI；观察器只从实际出站 `client_metadata["x-codex-turn-metadata"]` 中解析来源，不使用假服务标签、请求顺序或测试预期值作归属。没有来源字段的普通请求不发布为建议。**这是按原 App 参数结构模拟的协议实验，尚非原桌面自然生成的采集。**

| 场景                              | HTTP/SSE     | WebSocket | 实际证明                                                                                                                  |
| --------------------------------- | ------------ | --------- | ------------------------------------------------------------------------------------------------------------------------- |
| 观察 Worker 正常                  | 通过         | 通过      | 两个来源并发、随机 response ID、交错事件和 Unicode 分块；来源聊天／轮、RPC 全文、response ID 和 trim 后 suggestion 均匹配 |
| 观察 Worker 关闭                  | 通过         | 通过      | 原 RPC 与字节传递继续正确；没有观察结果                                                                                   |
| 观察 Worker 抛异常退出            | 通过         | 通过      | 已观察到注入的 Worker 崩溃；原 RPC 与传递继续正确                                                                         |
| 观察 Worker 阻塞                  | 通过         | 通过      | Worker 内阻塞，四项 pending；短实验仍完成，不宣称已验证长期积压／队列满或转发器挂起                                       |
| 内置 provider + `openai_base_url` | 本批未单独验 | 通过      | 独立伪造测试 API key；4 次 WS 升级、8 个请求帧、HTTP 回退为零，来源与字节断言通过                                         |

九批每批两个父轮加两个并发 sample，共 36 次假服务推理请求；不产生真实模型费用。四批 HTTP 各 4 次 SSE；五批 WS 各 4 次升级、8 个请求帧且无 SSE 回退。HTTP 比较的是实体正文，WS 比较的是握手之外的原始帧；都在独立假服务边界核对 SHA-256，并在 CLI 的原 RPC 接收端核对全文。没有把 HTTP 头、传输分块或 TLS 声称为逐字节等价。

监听器关闭后的连接探针实际失败，没有自动直连实现；没有执行代理退出期间的推理恢复测试。该结果不能叫完整 fail-open。假 API key 通过也不能替代真实 ChatGPT 登录、runtime auth 和 workspace tenant routing：对应版本可能重写 origin，或在不符合 Codex backend URL 形状时改变路由判断。[官方配置项](https://learn.chatgpt.com/docs/config-file/config-reference)与固定版本的[路由条件](https://github.com/openai/codex/blob/4f093f2748667f3b0e6a07e3d4055121cbd3ce64/codex-rs/model-provider/src/provider.rs#L264-L287)分别提供配置与实现证据。

该探针不是可部署代理：只验证有界的合成单次生成连接，拒绝同一连接内无法唯一归属的多次生成；压缩、长连接复用、升级兼容、断线恢复、真实认证及长期积压均未新增通过。

### 为什么正文还不能等同于灰字

原 App 先对 sample 的 `text` 执行 JSON 解析及 `{ suggestion: string.trim().nullable() }` 校验，空串归空；再依据当前安全策略和来源轮的 moderation metadata 决定是否返回；最后由 renderer 根据缓存、最新轮、草稿与编辑器状态选用。转发器不能将失败、取消或随后未被采用的结果直接显示到手机。安全记录 `cumberbatch_safety_decision` 仅有来源 Thread／Turn、suppressed 和策略元数据，没有 response ID。当前查询虽会去重，设置清除、取消、重新请求和重启仍不能被等同为“一个来源轮永远只有一个结果”。

本轮还找到比安全日志更强的现成事件：`CodexCumberbatchPredictionFinished` 包含 **predictionResponseId + sourceThreadId + sourceTurnId**，且在主进程安全判断后返回非空 prediction 时记录。`CodexCumberbatchPredictionInteraction` 的 shown／accepted 同样携带 predictionResponseId。已检查安装资源 `webview/assets/async/24191.589dcc9878.js`，SHA-256 `bd23d61085fcad60b8f582112d4ca0695b34204510cff900ba0f87c9c61228d0`，UTF-16 偏移约 3850514–3852340。不能继续笼统宣称桌面从不产生可关联事件。

但是，该事件尚不能作为本方案的数据源：

- 发送链经过 analytics 许可、身份与有限队列，可能丢弃；待发送队列最多 50 项，进程内 debug ring 最多 100 项。已检查的事件链及配置未找到将该 exact event 可靠导出到本机文件的标准开关。
- 出口是独立的 Statsig／Segment 路径，当前 Responses 转发器看不到。进程内调试缓存不等于独立 Host 已可访问的接口。
- 后台 live 路径也会产生 Finished，但受准确 owner、latestTurn、生命周期谓词等条件约束；已有缓存、此前生成、取消或安全返回空均不保证补发。shown 仅在编辑器确实显示且通过显示条件时记录，并按结果 ID 去重，不是全后台状态订阅。

因此，**观察到且可靠取得的 Finished 可作为同一 response ID 的安全后证据，但不存在事件不能证明不存在建议；本轮没有建立该事件的完整独立读取通道。** 不能为了补齐缺口修改遥测隐私设置、重发预测或用时间相邻拼接两个不同 ID。

### Windows 管道与 ETW 的补充验证

Microsoft 的 [PeekNamedPipe](https://learn.microsoft.com/en-us/windows/win32/api/namedpipeapi/nf-namedpipeapi-peeknamedpipe) 能非消费地读取命名或匿名管道的当前缓冲。本机自有匿名管道实验实际验证：24 字节 Peek 后仍剩 24 字节；复制句柄执行 Read 后原缓冲剩 0；原 reader 先读完时观察句柄 Peek 为 0。所有句柄均为探针自行创建并关闭，没有获取原 App 句柄。

这证明 Peek 不是完整记录订阅，复制句柄读取也不是旁路 tee；原接收方先消费就会漏。官方 [FileIo_ReadWrite ETW schema](https://learn.microsoft.com/en-us/windows/win32/etw/fileio-readwrite)列出对象、偏移和字节数等字段，没有定义正文 payload。该结论仅限所查事件类，不概括为所有 ETW provider 或系统工具绝无其他能力。未通过暂停、注入或更换原管道来绕过用户约束。

### 官方回答正文 OTLP 导出的对照实验

另单独验证了官方 `otel.log_agent_responses = true` 和本机 OTLP HTTP／JSON exporter。该配置由当前同一安装 CLI 实际加载；仅写入独立临时配置，导出器只监听回环地址。它不是任意 Rust trace 日志的改名，因此没有仅凭前轮日志过滤结论将其排除。

首次假服务输出缺少 `phase: final_answer`，普通／fork 正对照也没有正文，尽管 exporter 已收到 2 批 24 条记录；该次结果不能判断 sample 是否支持。根据固定版本 [AgentResponseLogger 的入口条件](https://github.com/openai/codex/blob/4f093f2748667f3b0e6a07e3d4055121cbd3ce64/codex-rs/otel/src/agent_response.rs#L39-L47)，仅给三类输出统一补齐该字段，未改变断言。第二次实际收到 **2 批 26 条记录：普通全文 1、旧式 fork 全文 1、sample 全文 0**；三类原 RPC／通知均准确返回全文，sample 来源轮亦匹配。故负结论建立在已成功导出正文的对照上，不是把空 collector 当证据。

固定实现的 [回答记录调用](https://github.com/openai/codex/blob/4f093f2748667f3b0e6a07e3d4055121cbd3ce64/codex-rs/core/src/stream_events_utils.rs#L99-L113)位于 sample 拦截之后，实验与该调用顺序一致。该专门出口仍不能补齐当前 sample 正文，更不能代替桌面安全后灰字。结果仅限本次同版 CLI 合成协议场景，没有推广为所有版本的否定。两轮最小结果、首次失败原因和成功导出的假文本记录已保存在 `evidence/otel/`；两轮临时目录和进程均完成清理，回执为 `otel-cleanup-receipt.json`。

### 交付状态与保留证据

本轮交付的是明确的可行性判断、可复现探针和逐阶段结果；**没有把 Windows 原生灰字全覆盖标记为已实现或已验收**。后续生产方案必须先有原 owner 下可可靠读取或订阅的安全后结果出口，至少提供来源轮、原生 response ID、有效状态和实例代次，并验证不因读取而生成。当前网络正文通道可以复用，但不能独自满足这一门槛。真实 ChatGPT 路由、桌面后台灰字与手机全文／采用／撤销均仍未验收；iOS、Android 和 A6 状态不变。

本机最小证据位于忽略目录 `.local/windows-suggestion-feasibility-20261104/round2/native/`：`windows-proxy-path-probe.mjs`、`transparent-proxy-probe.mjs`、`windows-pipe-semantics-probe.ps1`、管道结果、`proxy-experiment-summary.json`、`proxy-cleanup-receipt.json` 及 `evidence/proxy/`。代理入口脚本 SHA-256 为 `2102b65945e92d3ec34ecdda9a44c53b922922122c161d79535e057f891985ab`，代理模块为 `8ab73c8c2c472e619c5ee6317724030f81e263a3cfacc8400d71cddc56710527`。九个代理实验临时目录已正常清理，全部实验 CLI 和端口退出；原 GUI、直属 CLI、正式 Host 身份保持。前轮首次失败实验中被拒删除的只读 `tmp_pack_4ePT8Y` 与父目录单独保留，没有清除属性或换入口重试；被自动审批拒绝的 source matrix 写入也未重试。两者不改变上述技术结果。

## 2026-11-04 第二轮：Windows Trace 与后台采集实验

用户进一步明确：保持原 App 和正常启动习惯，覆盖所有聊天，包括后台聊天。当前阶段仍在研究，没有生产采集器交付；不能以当前可见窗口的读取代替后台覆盖。

**Mac 历史 Trace 机制在 Windows 上可行；将它直接用于当前 `thread/sample` 原生建议采集则不可行。** 本机同一官方 CLI `0.166.0-alpha.6`、相同文件摘要，在独立配置和本机合成 Responses 服务下实际执行了普通轮次、`thread/sample`、旧式 prediction fork + turn。普通轮次及旧式分叉均留下完整 `inference_completed` 与原文；sample 的原 RPC 返回完整原文和准确来源轮，外层 Trace 仅有 `inference_cancelled`，没有部分响应或正文。HTTP/SSE 与 WebSocket 两种实际传输均复现。这将 Windows 文件机制与当前建议实现的限制明确区分；不是只运行解析器或重放静态 JSON 的结果。

| 独立协议实验                              | 原 RPC／通知全文                 | 外层 Trace                  | 证据边界                                                               |
| ----------------------------------------- | -------------------------------- | --------------------------- | ---------------------------------------------------------------------- |
| 普通轮次，HTTP/SSE、WS                    | 与合成 UTF-8 原文相同            | completed 且有原文          | Windows 可以写出、读回完整 Trace                                       |
| 旧式 prediction fork + turn，HTTP/SSE、WS | 与合成原文相同                   | completed 且有原文          | Mac 历史采集所需机制可在 Windows 工作；不代表原桌面已选择该路径        |
| `thread/sample`，HTTP/SSE、WS             | 与合成原文相同，来源轮匹配       | started + cancelled，无正文 | 当前 sample 的完整结果不在该类 Trace 中，不能放宽终态检查补出缺失字节  |
| SSE 原始诊断日志                          | 可取得 sample 原文及 response ID | Trace 仍无正文              | 仅证明诊断能看见被截获前的事件；后台来源关联另验                       |
| WS 原始诊断日志                           | 可取得 sample 原文及 response ID | Trace 仍无正文              | 实际 3 次升级、6 帧、HTTP 回退为零；诊断内容不等于安全、完整的采集出口 |

### 并发关联试验与排除结果

两条独立父聊天同时发起 sample，假服务给每次请求独立随机的 `x-request-id`、`response.id` 和 item ID，并交错发送、在多字节字符中分块。真实事件顺序为 A created → B created → B output → A output → B completed → A completed；两个原 RPC 均准确返回各自来源轮和全文。

HTTP sample 的取消 Trace 保留对应 `x-request-id`；SSE 正文日志只有事件内容中的 response ID，没有相同的请求 ID、连接 ID 或 span。两者不是同一字段，不能以时间相邻、字串拼接或假服务内部映射作关联。等待超过十秒批处理后，隔离 SQLite 日志已有 244 行，其中有请求 ID 和两条 sample 方法记录，但没有两个 response ID 或建议原文。该结果排除本次尝试的“默认数据库 + 裸 SSE 诊断日志”作为可靠后台采集方案，不能再用短运行产生的空数据库作理由。

WS 的诊断收到消息同样没有 source Thread／Turn 或连接 span；对应版本的 [真实流实现](https://github.com/openai/codex/blob/4f093f2748667f3b0e6a07e3d4055121cbd3ce64/codex-rs/codex-api/src/endpoint/responses_websocket.rs#L394-L398) 明确将 `upstream_request_id` 设为 `None`。WS 隔离日志数据库在批处理后已有 213 行，仍没有 sample 原文或 response ID。因此不能将 HTTP 的响应头关联设想直接套到 WS。

原桌面 stderr 接收链还会逐块 `toString().trim()`，再交桌面 debug 日志；已检查链路没有增量 UTF-8 解码和 JSONL 重组，原始诊断输出能否完整落盘并未通过。提高日志等级不是已完成的生产修复。本次诊断配置只用于全新隔离子进程，没有写入用户环境、注册表、原 App 设置或正式 Host。

### 旧路径选择及其他已查出口

当前安装版确实保留旧式 fork，但由远端 `codex-app-cumberbatch-thread-sample` gate 与 CLI 支持范围选择分支。已查官方配置及安装设置链未找到受支持的本地分流开关；关闭 analytics 不能推定会关闭 gate，Show predictions 控制的是整个建议功能。不能靠修改 bundle、篡改远端实验返回、降级 CLI 或恢复旧启动垫片强行选择路径。

现有内部 `requestUserMessagePrediction` 缓存未命中会生成；follower 投影、公开请求表及本次检查的 RPC 日志没有返回既有建议全文。标准 `openai_base_url` 可改变请求目的地，但本机代理会进入原推理关键路径，不能当作独立、无副作用的旁观采集。界面读取无法满足已确认的后台覆盖范围，也没有新增真实 UI 通过。

截至上述实验，**尚无同时满足当前原生灰字、所有后台聊天、保持启动习惯、不修改原 App 的已验证可部署方案**。这不是 Windows 不支持 Trace，也不是把一次空建议认作永久失效。后续方案必须补齐实际存在且可验证的完整结果／来源出口；不能把 Host 另行生成的建议默认为同一功能。

### 本次实验边界

四批成功实验使用原版 CLI 和隔离本机假服务，没有真实模型调用，也未连接或接管原桌面。首次预检因本机请求未到假服务、收到 502 而失败；只在测试子进程设置回环代理例外和外网拒绝器后成功，机器代理配置没有改变。真实桌面、直属 CLI、正式 Host 的身份和启动时间前后保持，所有测试 CLI 与端口分别核对退出。

当前真实实例的自然 Trace 另按原桌面启动时间筛选了 14 个目录，每个检查前 40 条配置事件；没有匹配本聊天的子预测记录。它只描述这次限定盘点，不能代替上面的协议实验，也不证明其他后台聊天没有建议。最小实验回执和可复现探针保存在忽略目录 `.local/windows-suggestion-feasibility-20261104/round2/`；合成实验、当前自然结果和手机验收分别记证。

## 2026-11-04：新版 Windows 的可行性复核

**取字逻辑已通过隔离验证；正常启动后由独立 Host 取得同一次已放行建议，仍未达到可实施的证据门槛。** 本次检查实际安装的 Codex `26.1029.7803.0`、直属 CLI `0.166.0-alpha.6`，没有复用旧安装版的符号或通过指纹。正式 Host 的未认证健康查询返回 401，认证健康及工作区查询返回 200；工作区为 `desktop-runtime`，但未声明建议读取能力。Host 连通与建议可读分别计证。

本次仅更新研究，未修改生产 Adapter、服务配置、启动链或手机 UI。没有恢复 CLI 全局覆盖、启用调试端口、附加原桌面进程、触发新预测或跨机联系。前后核对原桌面、直属 CLI、正式 Host 的 PID、启动时间和监听端口保持；本次没有手机全文、采用或撤销验收。iOS／Android 的实现与验收状态均不提升，A6 保持暂停。

### 已执行的检查

| 检查                     | 本次结果                                                                                                                                                                                                         | 可证明的范围                                                                                      |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| 当前 CLI 的实际协议      | 官方 CLI 的 `app-server generate-json-schema --experimental` 成功；存在 `thread/sample`、`thread/sample/cancel`、`thread/prediction/request`，生成的 ClientRequest 未找到读取已有预测的 `existing/read/get` 方法 | 实际二进制生成的协议清单；不证明所有内部或未来接口不存在，没有执行这些预测请求                    |
| 主进程建议服务           | `Wu.request` 按 Host、Thread、最新 Turn 使用 `fetchQuery`，未命中时执行 `runPrediction`；`Wh` 服务只暴露资格查询与 `requestUserMessagePrediction`                                                                | 现有请求不能当作无副作用的缓存 getter；主进程内部缓存不等于 Host 可访问的接口                     |
| 新版原函数的隔离执行     | 精确摘要锁定两个安装资源，提取原 getter 和 request 方法；中文、特殊空格、补充平面字符、组合字符、换行逐字节相等；六种隐藏条件返回空。缓存命中、未命中、禁用及不支持目标分别执行，未命中调用合成预测回调一次      | 编辑器取字和请求分支的辅助证据；依赖为合成状态，没有读取真实窗口或执行模型                        |
| 安全日志与 follower 投影 | 安全记录构造含来源 Thread、来源 Turn、suppressed 和策略元数据，未包含 response ID 或正文摘要。检查到的 follower 路径来自 threadStore，会话快照与建议 controller 的查询缓存是不同对象                             | 没有找到从建议缓存到已核实 follower 通道的连接；不将通用 renderer 消息通道视为已授权的外部 getter |
| 有限自然 Trace 盘点      | 扫描 250 个目录的前 40 条事件以识别来源，旧目标匹配四个 `composer_predictions`／`thread_sample` 子记录，均只有一次开始和取消、无 completed、无部分响应；当前聊天在此范围未找到匹配子记录                         | 历史样本本次重读，不能宣称是新版生成失败。普通父聊天中的 inference_completed 不作为建议正文证据   |

[官方 App Server 文档](https://learn.chatgpt.com/docs/app-server) 将 `thread/read` 与 turns/items 查询定义为存储线程及历史读取，本次页面未找到公开的桌面灰字只读接口。文档和本机 Schema 是两份证据；内部 Electron 服务、CLI 协议、Host 领域接口不能互相替代。

### 修正 Trace 来源假设

11 月 1 日的固定上游版本不是本机当前 CLI。`rust-v0.166.0-alpha.6` 标签解析到提交 `4f093f2748667f3b0e6a07e3d4055121cbd3ce64`，本次以固定提交重新取源、交叉校验文件摘要。[Core 流处理](https://github.com/openai/codex/blob/4f093f2748667f3b0e6a07e3d4055121cbd3ce64/codex-rs/core/src/client.rs#L2660) 将 sample 拦截器的输出交给外层 Trace 处理；[SampleInterceptor](https://github.com/openai/codex/blob/4f093f2748667f3b0e6a07e3d4055121cbd3ce64/codex-rs/app-server/src/thread_sample.rs#L365-L379) 自行收集完整响应、交回原请求者，然后等待 owner 清理。**由此推断，该分支可能在桌面得到建议的同时，外层 Trace 仅记录取消且没有正文。** 这是对应版本的源码推断，与重读的历史空载荷现象分别记证，不冒充本次真实灰字逐字验收。

因此，不能继续把“延长等待”“读取父聊天的完成事件”或“允许 cancelled”作为补齐全文的方法。即使另一路 Trace 有完整正文，仍须证明它就是当前桌面采纳的同一次生成；来源 Turn 相同与 `suppressed=false` 本身不足以排除同轮重复生成、迟到或重启后的替换。

### 下一步的最小可行性门槛

进入生产实现前，需要验证原桌面可提供一个**缓存未命中也绝不生成**的窄只读出口，或等价的权威被动结果通道：

1. 输入固定完整机器目标、原 owner、聊天和预期来源 Turn；不开放任意 Electron 方法。
2. 原子取得已经通过安全判断的原文、原生生成标识（如 response ID）、来源 Turn 与当前实例代次；未命中或来源改变返回空。
3. 若产品承诺与桌面正在显示的灰字一致，还需当前编辑器可见状态及版本，不能把主进程缓存直接标成“当前显示”。
4. 独立验证缓存命中、未命中、同轮重新生成、抑制、草稿非空、切换聊天和重启；读取全过程新增预测次数为零，再做 Windows Host 与原生手机全文核对。

本次已检查的签名安装版没有找到满足该门槛的现成出口。上述为待验证契约，尚未开发或部署；只有 Host 侧改动不能证明这个跨进程数据源已建立。不得以恢复旧中继启动链或主动生成新文字替代。

### 指纹与证据

| 已核验资源                                            | SHA-256                                                            |
| ----------------------------------------------------- | ------------------------------------------------------------------ |
| 当前直属 CLI                                          | `925c7d0665ffa49b0dcf748e034a805a04da984164ac92301432073b47789b99` |
| `.vite/build/early-bootstrap-632-62c641245a52ef0e.js` | `c16675e64dae86bdb09f7720f038a8c2f2beacf0e85bbadd369f407d38662aa4` |
| `webview/assets/async/559161.2c0475afc3.js`           | `d968a08a90f4920c37c0195108066b57291b8b05b3c517a593518b9df5e5bf3e` |

本机最小报告、探针、协议方法清单及静态注册链核验保存在忽略目录 `.local/windows-suggestion-feasibility-20261104/`；真实身份和源目录不写入 Git。本次没有生产代码变化，未运行完整 `pnpm check`，不声明全项目回归或功能交付。两名本机执行方已显式请求 Terra／medium；入口无速度字段，实际参数不可回读，不能宣称已核实运行档位。

## 2026-11-01 历史结论

尚未达到开发生产采集器的可行性门槛。已找到值得在目标 Windows 核查的只读来源：**原应用已经写出的 rollout Trace 与安全决策日志**。目前只有静态机制证据，缺少该机同一安装、同一原进程的完整数据及结果关联证明。不能把旧解析器未读到正文扩大成原始载荷必然没有正文，也不能把一个候选方案写成已可用。

本轮未恢复 `CODEX_CLI_PATH`，未开发或部署生产采集器，未改原应用、启动配置、模型输入、身份或密钥。原 Windows Host 的现有入口在只读健康探测时拒绝连接；当前入口尚待补齐，因此 Windows 核查、实施及验收继续待办。历史通过不提升为本次通过。

## 已复核的证据

上游代码固定于 `openai/codex` 的 `67f0994b532b8740f14b5b738af7b7b2a915b1d6`；它不是目标 Windows 安装版本。官方文档及固定源码支持以下有限结论：

| 候选                        | 已确认的机制                                                                       | 当前缺口／结论                                                                    |
| --------------------------- | ---------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| 官方独立只读接口／订阅      | app-server 区分请求响应和通知；该版本 sample 响应定向发给原请求连接                | 未证实旁观连接能取得原 composer 请求／结果，不能用新生成代替读取                  |
| `thread/prediction/request` | 固定版本处理器返回尚未实现，通知类型声明存在                                       | 类型存在不证明原桌面采用、实际发送或缓存可读                                      |
| 既有 Trace＋安全日志        | provider 完成后可能先保存完整响应，再交给 sample 截获器；后续清理可能中断临时 Turn | 有条件候选；须核对原机完整载荷、来源、实际采纳及安全判定的唯一关联                |
| DOM／辅助功能树             | 历史版本建议节点有 `inert`／`aria-hidden`；现有 DOM PoC 只读已经开放的开发端点     | 可见页面、后台覆盖、原始文字和来源轮次证明均有限，不作为完整方案；不为此开启调试  |
| ETW／普通日志               | 事件载荷取决于 provider                                                            | 尚无 Codex 已启用 provider 提供所需正文与关联键的证据，不新增采样、提权或系统配置 |

原始依据：

- [官方 App Server 文档](https://developers.openai.com/codex/app-server)。
- [sample 实现及清理顺序](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/app-server/src/thread_sample.rs#L105-L441)。调用 sample 会生成新内容，不是现有结果 getter。
- [定向响应路由](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/app-server/src/outgoing_message.rs#L751-L789)与[传输选择](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/app-server/src/transport.rs)。
- [客户端写入完成记录的时序](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/core/src/client.rs#L2610-L2660)与[Trace 响应载荷](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/rollout-trace/src/inference.rs#L193-L221)。完整推理结果与原桌面成功采纳是两个判断。
- [未实现的 prediction 请求](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/app-server/src/message_processor.rs#L1466-L1469)及[Trace 启用条件](https://github.com/openai/codex/blob/67f0994b532b8740f14b5b738af7b7b2a915b1d6/codex-rs/rollout-trace/src/thread.rs#L103-L108)。默认行为不能推断目标机实际配置。
- [Microsoft ETW 模型](https://learn.microsoft.com/en-us/windows/win32/etw/about-event-tracing)。

仓库现有 `desktop-prediction-trace.ts` 对终态要求 `completed`；不能简单改为接受 `aborted`。同一份完整 provider 响应仍可能因源历史变化、取消、RPC 失败、解析或安全过滤而未被原桌面采纳。现有安全日志解析只识别来源 Thread、来源 Turn 和 suppressed；重复生成时，这些字段本身不能区分具体结果。

## 先做的 Windows 只读核查

在原机现有权限、程序和服务配置下核对：

1. 原桌面／直属 CLI 的路径、摘要、PID、启动时间及当前用户／会话；真实 Host、workspace、runtime 与已存在的 Trace 根。安装版本只作证据元数据。
2. 原自然 sample 的请求元数据是否保留准确父 Thread 和来源 Turn；不要仅依赖时间、文件名、当前聊天标题或历史字段假设。
3. 同一次推理的 `inference_started`、`inference_completed`、响应引用及完整载荷，关联调用／响应标识和文件身份，区分完整响应、partial 与未完成尾部。
4. 原桌面安全决定与该结果能否唯一对应；同源轮重复请求、迟到结果、缓存命中、进程重启、日志轮转或缺记录时不能猜测对应关系。仅看到一个完整候选，不证明其他尝试不存在。
5. 原桌面确实展示或保留同一建议的独立核对；正文、版本和来源一致，且无额外模型请求或输入重放。

只读核查不设置新启动参数或环境变量，不打开调试端点，不接管／重复读取原 stdio，不改写源文件，不启动预测。私有证据仅留在目标私有任务目录，对外返回字段存在性、数量、长度及摘要。当前入口未连通，以上各项均未取得本次 Windows 结果。

## 进入实现的条件与结构

先取得原机证据，再选择最窄来源；若官方权威 `readExisting` 能直接提供等价的来源、原生成版本和安全通过状态，可使用该契约，不强制复制旧 RPC request ID 或垫片进程链。若证据不足，保持不可用并继续定位，不新增另一种影响启动的替代方案。

通过后拟采用 Host 独立的可选采集进程／Worker，保留有界读取、资源预算、私有权限和具名领域接口。源文件只读打开且共享原程序的写入／轮转，采集读取不能持有阻断原写入的锁。原 Codex 不等待该进程、不经过其管道，也不由它创建或终止；collector 失败只关闭建议能力。新来源应定义与实际权威来源匹配的生产者证明，不能伪装成旧 shim 或盲目移除原 owner、完整目标、DACL、来源、安全判断与租期检查。

本节是候选结构，尚未实现；Trace 根未启用、正文未保存或关联不足均不是通过安装器“强开”功能的理由。

## 验证与交付门槛

- 独立辅助回归：完整／partial／取消、抑制、重复生成、旧轮与迟到、进程和文件身份变化、截断／轮转、并发与损坏配置；失败不重新生成、不回放输入。
- 原生 Windows：collector 不存在、坏配置、崩溃、挂起和 Host 离线时，原桌面正常图标启动与 initialize 成功；仅操作本任务隔离进程，保护既有工作。应用升级后重开与采集恢复分别核对。
- 实际功能：真实原桌面／模型任务、原建议正文与准确来源、Windows Host 及原生手机展示／采用／撤销；iOS 与 Android 分别标记，A6 保持人类暂停。其他平台或 fixture 不代替 Windows 通过。
- 来源统一：确认可行后在主开发仓库实现、检查及冻结构建，统一产物再分发到指定 Windows；不在目标机维护修复分叉。

目前只有研究与静态代码复核完成。开发、Windows 数据源实证及上述实际验证均未完成。

## 本轮资源与证据

两份独立研究报告与只读连接结果保存在主开发电脑私有任务目录，保留至本任务 Windows 验收与收尾完成；不包含登录凭据或真实对话正文。调研临时源码副本已回收，未创建服务、工作树或目标专用聊天。独立 Agent 已完成；创建参数请求 Terra／medium，但接口不暴露实际模型、推理及速度记录，因此不宣称已独立核实运行档位。现有生产改动和前一阶段安全修复保留。
