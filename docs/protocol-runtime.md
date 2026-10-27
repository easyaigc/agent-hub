# 运行时 HTTP 协议 v1

2026-10-26：`runtime.approvals[]` 增加可选 `decisionLabels: Record<string,string>` 展示文案，动作仍来自 `decisions[]`，手机通过原 `/v1/approvals` 仅提交具名 decision。新版手机以 `X-Agent-Hub-Approvals: mcp-v1` 声明支持；旧客户端读取新类型时仅显示拒绝与更新提示，避免将未知动作误标为停止任务。历史与事件读取均执行此兼容投影，不改变 Host 内部原请求。Host 将 MCP 的 accept／decline／cancel、原请求允许的 session／always 与额外权限的 turn／session 响应映射到原运行时，不向手机开放任意 wire、权限对象或方法名。

2026-10-24 原桌面创建恢复：准备器确认退出且官方 API 已核对准确零 Turn 元数据后，先登记只读会话，再获取原 owner。已拒绝且未写入首条输入的本机准备记录可支持该准确 ID 的显式打开；原创建回执和输入不变，不重放、不以元数据冒充运行时可控。两端共用 Host，无新增手机权限开关；原生与辅助验收分别记证。

2026-10-24 原桌面目录策略简化：desktop-runtime 不再使用 HOST_AGENT_ROOT 作为建议、附件下载或服务就绪的全局授权范围。该旧配置仅保留默认 cwd 兼容语义；托管／共享模式的显式边界和原 Agent 执行权限保持。建议按原 owner、当前实例和准确完成轮验证私有 Trace，不要求项目目录存在；文件下载按本会话已登记资源授权，原消息引用、目标变化、大小与摘要检查保持。iOS／Android 共用 Host 行为，无新增权限界面；实际桌面、模型、实体手机及 Windows 结果分别记证。 详见 [目录策略](adr/0003-desktop-resource-authorization.md)。

## 大对话最近页与同步失败（2026-10-18）

已实现可选 `window=latest-v1`，适用于 `GET /v1/history` 与指定 thread 的 `GET /v1/events`。原桌面响应增加 `historyWindow: { version: 1, kind: latest | earlier }` 与 `synchronization: { state: live | live-window | history | unavailable, checkedAt?: string }`。最近页最多 100 条消息，以 2 MiB 为分页目标；单条消息不截断，超过目标时单独一页。`nextCursor` 不透明，绑定准确 Host、Thread 与前一页首消息；继续读取较早消息不刷新原 owner 或执行任务。省略参数保持旧完整快照语义，旧 Host 忽略该参数仍可兼容。

手机合并最近页和已读的较早页，恢复新进度时保留历史、草稿和已提交操作。同步失败固定显示提示与只读重试入口；成功取得 HTTP 响应但 `synchronization.state=unavailable` 的缓存事件不能清除提示，也不能刷新成功时间。Host 在线只代表连接路径可用，不能替代对话实时进度证明。恢复读取不重发消息。

原 owner 无法提供基础快照时，最新页继续从独立只读历史 reader 获取。仅成功读取最新页后十秒内可返回 `history`，`checkedAt` 为该次实际读取的 UTC ISO 毫秒时间；缓存事件沿用原时间，不能延长租期。读取失败立即撤销该标记。手机明确显示“实时状态暂不可用”及最新历史已同步的说明；运行状态与控制能力继续为 unknown/false，不把历史成功冒充原 owner 就绪。重复缓存事件不延后下一次实际历史读取，导航与迟到响应继续按完整目标隔离。

2026-10-23 补充：最新历史页通过只读 `thread/turns/list` 的单轮 `notLoaded` 视图取得终态，仅在返回 Turn ID 与最新消息的 Run ID 一致时投影 `lastTurnStatus`；旧服务不支持时保持未知。新鲜历史已确认 completed／interrupted／failed，且没有当前输入、队列、待确认回执或审批时，iOS 不显示实时重试警告；执行控制仍为 false，运行中、未知或实际读取失败继续提示。历史读取期间到达的已验证原 owner 快照优先，不被慢历史结果降级覆盖。

历史分支中的本地 Markdown 文件链接与实时消息共用附件检查、内容摘要和链接替换路径，下载前继续检查最新字节。只读元数据中的工作目录仅用于解析相对链接，不扩大授权目录；旧 inline 附件不再替代本次检查，缺失或越权文件不能继承旧版本。该修复同时影响使用此 Host 的移动端，平台实测范围见 [专项记录](acceptance/status-preview-20261023.md)。

手机最近页不会为未知回执自动要求原 owner 加载全部历史。独立历史 reader 可按准确操作 ID、正文 SHA-256、线程及唯一 Turn 接受记录恢复纯文字回执；没有精确证据继续未知且不重放。原轮中断保留 `interrupted`，不能称为全部业务动作完成。缺少基础快照时，补丁不能构造完整可控状态；同一等待期间合并快照请求，失败后自动重试退避三十秒，显式命令仍需独立重新核对原 owner。此恢复不会卸载、恢复执行或重建原聊天。

`live-window` 单独表示已核实原 owner 的有界消息增量与当前最近历史窗口。完整消息身份由准确历史消息 ID／Turn 绑定，或来自此前实际完整快照的连续部分基线；未知祖先、修订缺口及身份不符不补造。小型原 owner 设置只读响应续租连接；最近历史与 owner 租期均为十秒，控制仍为 false。手机显示“实时进度已同步”并注明完整桌面状态暂不可用；租期失效恢复历史或失败提示。它不等于 `live` 的完整状态或运行时控制。

回执可带 `noticeContext: historical` 与 `laterTurnId`，只说明已核实原轮结束且电脑继续后续轮，不证明未知输入已执行。`queueSource: host | native` 区分旧 Host 留存记录与客户端队列。旧项带 `queueDisposition: legacy-review`、`queuePresent: false`、`canExecuteNow: false`，仅供核对，不再属于执行队列。缺完整状态不妨碍独立只读查询原生队列。恢复同步不自动执行、迁移或重发旧项。

Adapter 的 IPC 接收上限由 64 MiB 调整为 256 MiB，仍只在完整帧校验后接纳原 owner 快照。超过 256 MiB 且不超过 512 MiB 的帧有界丢弃并报告读取不可用。选中聊天及电脑目录的周期补刷仅在十秒未收到有效流更新时重取完整快照，明确的桌面阅读提示仍立即核对。辅助回归、真实模型、原生 App 与目标机部署结果分别记录。

## 持久 trace 与建议版本刷新（2026-10-18）

当前生产读取优先使用原生 trace；只有显式配置 DOM Bridge 时才使用展示读取。原生路径声明 `desktop:suggestions:native-version:v1`，返回 `source:native-trace`、`validity:native-version` 与六秒读取租期，不声称已经取得输入框显隐状态。来源需同时满足当前桌面实例、准确 Host／Thread／完成 Turn、原生预测元数据、完整成功返回与 task_complete 正文一致；Host 在读取前后核对原 owner 与来源，证据变化清空本次结果。

同来源候选按原生 inference 请求开始时间选择，不能按文件修改时间或完成时间排序。相同时间无法消歧时拒绝；最新请求尚未完整结束、失败、空结果或证据不完整时不退回旧版。已经被更新请求取代的未完成执行不阻塞新结果；旧完成通知不能重新加入被取代的候选。旧桌面实例的记录不参与选择。

iOS 对该能力首次读取即进入前台每两秒只读刷新，同轮次支持版本替换与清空；六秒租期续期不改变建议身份，失败和到期失效，晚到预读不能覆盖正在刷新的结果。用户草稿、已采用文本、撤销和发送状态保持独立。旧 `source-turn` 响应不会降级进入新的生产读取 scope。

macOS 持久配置工具 `scripts/desktop-suggestion-trace-environment.mjs` 在用户登录时设置固定 trace 环境，并立即核对未来启动环境。它不重启或附加原应用；当前实例需正常重开才可继承。登录域环境也可能由同用户后续 Codex CLI 继承，完整请求／返回保存在私有目录，启用前须有明确授权。已注册、当前进程实际环境、新 trace 及普通重启恢复分别验收；登录时自动恢复的应用与 RunAtLoad 存在启动顺序限制，不能据此声称所有整机登录场景已通过。原应用生成后的安全抑制与界面手动隐藏尚不能仅由 trace 判定，严格显隐一致单列未验收。

## 桌面下一轮建议（只读，准确来源绑定）

本节契约已在仓库实现。本机正式 Host 与 iPhone 16 build 77 已完成持久 trace 部署、正常桌面重开后新预测采集、原生结果与正式 HTTP 全文核对，以及设备观察和用户独立确认的本次双端正文一致；原连接、草稿及已提交输入保留。最新证据及复用范围见[持久 trace 正式部署与正常重开验证](research/desktop-suggestion-display-consistency.md#持久-trace-正式部署与正常重开验证2026-10-18)。同页第二个新预测的替换仅有专项回归，严格显隐一致、整机注销登录时序与其他电脑本次部署仍未验收；下方早期 DEV 证据保留原范围。

`GET /v1/threads/next-turn-suggestion?hostId&target&threadId&sourceTurnId` 已实现。四个查询字段必须非空且各出现一次，未知字段拒绝；`target` 是 JSON 编码的完整 Agent／Host／workspace／runtime 目标，可兼容历史 `account` 元数据，但 Codex 账号不参与机器目标判断。请求使用当前 Host 凭据，仅支持 GET；输入无效返回 400，目标、授权目录或来源轮次变化返回 409，读取未就绪返回 503，其他方法返回 405。

原生路径的 HTTP 200 响应包含 `X-Agent-Hub-Suggestion-Response-Unix-Ms`，值为完成来源核对后独立采样的服务端 Unix 毫秒时间，空建议也带此头。跨机验收以同一响应的 `expiresAt` 减去该时间，严格检查剩余租期大于零且不超过 6000 毫秒；本机接收时钟的差值另列为诊断。验收拒绝缺失或非法时间头，并保留原始失败记录。响应正文和手机草稿行为保持原契约。

共同读取能力为 `desktop:suggestions:read:v1`。默认原生路径另声明 `desktop:suggestions:native-version:v1`，要求 desktop-runtime 显式设置 `CODEX_DESKTOP_SUGGESTION_OBSERVER=1` 与 `CODEX_DESKTOP_SUGGESTION_TRACE_ROOT`、采集器实际 listening、取得原桌面启动时间，且 trace 根目录通过权限、路径和目录身份检查。显式配置 `CODEX_DESKTOP_DOM_BRIDGE_CONFIG` 时选择 DOM 候选路径并声明 `desktop:suggestions:displayed:v1`，实际读取仍须核对原 owner、来源及页面。仅设置变量、仅恢复历史记录或只读到普通会话历史均不代表实际可读；接口不启用原桌面 trace 或请求生成预测。

`GET /healthz`、`GET /v1/workspace` 与 `GET /v1/events` 统一合并当前建议读取能力；原生路径在观察器或 trace 不再就绪时撤销，恢复后再声明，显式 DOM 路径的能力按独立配置声明，读取失败不回退 trace。事件响应不能把手机已获知的建议能力覆盖成缺失。对应 HTTP 回归属于辅助边界证据；实体手机正常读取与历史聊天切换的证据各保留其实现范围，能力失效／恢复的故障注入不扩展为全部真机场景通过。

Host 在读取前后核对真实原 owner 的同一最新完成来源 Turn；期间开始新轮、断开、来源变化或证据失效时拒绝或返回空结果。原生路径先按桌面启动时间排除旧实例，再从 session-configured 元数据识别准确来源聊天且 cwd 在授权根目录内的 `composer_predictions`，通过同一请求中的 `prediction_source_thread_id`、`prediction_source_turn_id`、`thread_id` 和 `turn_id` 绑定来源；核对 app-generated 标记、完整模型响应、成功结束及同执行的 `task_complete.last_agent_message`。按首个原生 inference 请求时间选择唯一最新执行，最新未完成、失败、空结果或证据不全时返回空，不回退旧正文；请求时间并列时拒绝消歧。完成通知只佐证已选执行，正文冲突隔离不能被补读绕过，旧通知也不能复活已替换候选。补读不生成模型请求、不伪造通知、不写原历史或绑定记录，返回 `source:'native-trace'` 与 `validity:'native-version'`；它证明原生正文版本，不声称取得当前界面显隐状态。

响应为 `DesktopNextTurnSuggestionResponse`：

```ts
interface DesktopNextTurnSuggestionResponse {
  schemaVersion: 1;
  target: RuntimeTarget;
  threadId: string;
  sourceTurnId: string;
  readOnly: true;
  source?: 'desktop-display' | 'native-trace';
  suggestion: {
    id: string;
    text: string;
    contentSha256: string;
    expiresAt: string;
    validity?: 'source-turn' | 'desktop-display' | 'native-version';
  } | null;
}
```

响应不包含预测执行 ID、原始请求、内部 prompt 或本机路径。建议 ID 绑定完整机器目标、来源 Thread／Turn、原生执行身份或展示正文摘要，续租不改变同一建议的身份。当前原生与 DOM 路径均返回六秒 `expiresAt`，手机前台每两秒只读刷新；失效或到期清除，同轮次允许正文替换与消失。生产 scope 根据能力要求 `native-trace`／`native-version` 或 `desktop-display`／`desktop-display` 的配对，不接纳旧 `source-turn` 响应降级为无限缓存；`source-turn` 与缺少字段仅保留旧协议的兼容含义，未知值拒绝。快照可选 `runtime.suggestionSourceTurnId` 仅由原 owner 当前已完成且空闲的最新轮次提供；新轮开始、owner 不可用或断开时清除，不从持久历史恢复，也不复用完成后已清空的活动 `runtime.turnId`。

早期阶段的实现与验收记录如下，仅描述当时 DEV 范围，当前正式状态以上方最新部署验收为准。

第二阶段 iOS 已实现空输入框灰字和显式采用到本机草稿：追加默认保留原文，替换单独确认；写入前校验来源、导航、草稿目标、正文、编辑版本及有效期。此操作不调用 Host 写接口，也不触发发送。撤销只绑定仍未再次编辑的同一草稿，由草稿层持有；建议过期、只读状态变化或界面重建不撤回已采用文字，也不丢失该本机撤销记录。第二阶段已用另外两条原桌面真实建议，在实体 iPhone 15 Pro Max（iOS 27.0.1）普通安装 App 完成 A → B → A → B 四次来源与全文匹配。采用后撤销、已有草稿追加与撤销、采用后继续编辑并使旧撤销失效、跨聊天草稿保留、取消替换保留原文均通过。手机操作未新增模型输入，独立原 owner 前后仍为各一个相同的完成轮次；原连接已恢复，临时连接及 HTTPS 入口已清理。当时使用独立 DEV Host，日常正式 Host 尚未部署本功能；外接键盘 Tab、附件组合与其他平台未扩展为该轮真机通过。以下描述为第一阶段只读卡片实证。iOS 已实现当前可见聊天的有界只读刷新，核对完整目标、来源、SHA-256 及有效期，并隔离导航与连接变化后的迟到响应。卡片只展示、展开／收起和选择正文，不写草稿、采用或发送。两条本机原建议的精确来源及原生响应／通知正文一致已真实核对；Provider 推理流无重叠，不能将来源与预测生命周期的重叠写成 Provider 并发通过。实体 iPhone 15 Pro Max（iOS 27.0.1）普通安装 App 经临时独立 Host 的真实 HTTPS 完成两聊天四次全文匹配，来源与摘要正确、草稿未改、手机读取无额外模型输入，原 owner 前后仍为各一个相同的完成轮次。原连接已恢复、临时连接及 HTTPS 入口已清理；当时仍依赖用户授权的临时原生 trace，日常正式 Host 尚未部署本功能，详见[来源读取验收](research/desktop-suggestion-read-channel.md)。

## 桌面建议采集记录（只读，保留未绑定原记录）

`GET /v1/desktop/suggestion-observations?hostId&target` 已实现。必须使用当前 Host 凭据，`target` 是 JSON 编码的完整 Agent / Host / workspace / runtime 目标；拒绝重复或未知查询字段，目标不匹配返回 409，写入方法返回 405。该接口不接受聊天 ID 作为候选的来源提示，也不提供生成、采用或发送动作。

desktop-runtime 显式设置 `CODEX_DESKTOP_SUGGESTION_OBSERVER=1` 后，Host 管理 macOS 只读采集进程。就绪时声明独立能力 `desktop:suggestion-observations`；缺 Python、通知配置不匹配、原桌面不可用或采集器故障时不声明就绪。此能力不等于手机可展示已绑定的聊天建议。

响应使用 `schemaVersion:1`、`readOnly:true`、当前 `target`、`collector:disabled|starting|listening|unavailable`、`sourceBinding:unavailable` 和 `observations`。每条记录包括稳定 `id`、完整 `target`、`predictionThreadId`、`predictionTurnId`、原 `text`、`observedAt`、`expiresAt`、`collectorEpoch`，以及固定的 `source:null`、`bindingStatus:unbound`。预测分支 ID 不能当作来源聊天 ID；未绑定记录不得进入聊天建议或草稿。

未绑定观察记录最多 64 条、保留一小时；这一采集记录保留策略不限制上方已绑定建议的来源有效期。记录按授权根目录与机器目标隔离；同一预测 Thread / Turn 去重，内容冲突后隔离，重启恢复不产生模型输入。采集当前是进程参数的尽力观察，极短通知进程可能漏采，不承诺完整事件流。原通知没有来源关联键，故本接口保持未绑定；上方聊天建议接口通过另行读取原生 trace 派生准确绑定。真实本机双预测 HTTP 采集及独立 Host 重启已通过，不能将旧采集接口的重启证据扩大为新接口及手机卡片已全部验收。

## 其他运行时契约

正文文件链接继续复用既有附件契约：Adapter 只为原消息实际引用、经授权及完整性检查的文件生成逻辑链接；`GET /v1/attachments?threadId&id` 仍绑定准确 Host 与对话，并按登记的 SHA-256 重新检查文件。新增的路径写法解析不增加任意路径下载、URL 代理或运行时执行端点。iOS 原生预览与网页浏览分别使用附件鉴权下载和无 Host 凭据的系统 Safari 组件；本机地址、缺失文件及不支持的链接不再静默交给系统。

2026-10-21 已实现 `attachments:resolve-current`：鉴权 `GET /v1/attachments/resolve?hostId&threadId&id` 只接受这三个单值字段，仅按当前对话已登记附件的路径重新检查真实路径、授权目录、大小与摘要。返回 `hostId`、`threadId`、`sourceAttachmentId`、`readTarget` 和不含路径的 `attachment`。内容变化时登记新 ID，不改旧附件、原消息或已提交输入；相同内容复用版本。iOS 每次打开链接、下载和再次预览均先解析，再按返回的精确 ID、大小与摘要使用缓存或下载；解析后再次变化时最多重新解析一次，仍失败则显示错误。未知附件／缺失文件为 410，越权为 403；404 仅用于入口不存在的旧 Host 兼容，不能将文件消失降级为旧缓存预览。辅助回归与真实原桌面／原生 App／模型结果分别记录，不由接口实现推定实体或其他电脑已部署。

新增受私有配置控制的实验读取端点 `GET /v1/desktop/composer-suggestion?hostId&threadId&sourceTurnId&target`。它经 Adapter 的固定 DOM 读取，前后核对原 owner 和最新完成轮次；手机不能传 CDP 地址、方法或脚本。该 PoC 尚无原 Codex 实读／正式能力声明，返回 `desktop-dom / rendered-text` 及观察来源，不冒称已取得原预测生成对象。完整契约与限制见 [Host DOM Bridge PoC](research/desktop-dom-bridge-poc.md)。

创建后台提交在返回前检查已知前置阻塞，不再以异步吞错后读取 `prepared` 代替接收结果。兼容共享输入框的阻塞请求以原 ID 持久保存 `rejected` 和原因；独立空会话桥接允许不同不可变操作分别创建各自的准确 Thread，未知旧操作保持可查且不重放。最新页 `GET /v1/history` 可请求已知原 owner 刷新快照，仍为只读；历史分页不触发额外刷新。iOS 切网仅轮换读取连接，恢复读取不重发提交命令。

工作区可选返回 `pinnedThreadIds: string[]`，按当前 Agent／Host／workspace／runtime 的桌面置顶顺序排列；空数组表示已全部取消置顶，缺省表示旧对端未提供。它只影响侧栏展示，不改变项目归属、最近顺序、执行状态或阅读位置。Codex 从本机桌面命名空间只读投影，EasyWork 从既有 UI 状态读取；后者第一页额外带回超出最近分页范围的置顶摘要，分页游标仍按最近列表推进。手机只展示同目标目录内的有效 ID，未知 ID 不生成占位对话。

`GET /v1/workspace` 新增可选 `sidebar: WorkspaceSidebar`，版本为 1。`pinned` 是有序的 `{kind:'thread'|'project', id}` 混合列表，`recentThreadIds` 是当前筛选后的最近顺序，`projectIds` 是普通项目文件夹顺序，`projectThreadIds` 保存各项目的有序聊天 ID（包括置顶项目）。Adapter 已完成桌面偏好、归档排除、置顶去重及最近交互／手动排序的投影，手机不重新用后台更新时间排序。所有 ID 只能引用同一工作区目录，不创建缺失条目或增加控制能力；未知版本不使用，字段缺省时兼容旧 Host。

`GET /v1/workspace`、`GET /v1/history`、`GET /v1/events` 根据 `Accept-Encoding` 协商 gzip。至少 1 KiB 且压缩有效的成功 JSON 响应使用 `Content-Encoding: gzip`，包含准确的编码后 `Content-Length`、`Vary: Accept-Encoding` 和 `Cache-Control: no-store`；未接受 gzip、显式 `gzip;q=0` 或小响应继续返回原文。客户端先由 HTTP 层解压再解码相同 v1 快照；目标、事件 epoch/cursor、reset 和读取者投影不变，不新增领域能力。此实现不提供消息差量或 SSE，命令、附件与错误响应保持原有编码。

2026-10-14 Codex 身份策略调整为机器目标（`agentId`、`hostId`、`workspaceId`、`runtimeId`）。新 Host 不再从 Codex 登录文件生成账户身份；历史目标的可选 `account` 字段继续可读，但不作为连接或运行目标拒绝条件。私有连接 v1 的 HKDF/AAD 仍严格使用提交 claim 的完整原字段，并回传同一 claim 目标，保持历史客户端解密兼容；设备公钥、撤销、令牌和 TLS 校验仍执行。原回复 v2 指纹保留捕获的完整输入，新策略不改写已提交目标或重放未知输入。源码和隔离边界检查已完成；本机 Host 实际启用后，原设备令牌及不含账号的机器目标两次读取通过。业务 v8 和正式 native32 已实际部署，真机旧连接、草稿和入口读取通过；完整运行时场景继续，见 本轮验收（本地文件 `docs/acceptance/task-management-ios-20261014.md`）。

已增加独立 `voice:transcribe` 能力及就绪、准备、原文件上传和回执查询接口，契约与恢复行为见 [Host 录音转写](voice-transcription.md)。是否可用按当前桌面认证与路由判定，不从聊天模型的音频输入声明推断。

本页描述已实现接口。`packages/protocol` 早期的 ReplyRequest 是设计草案，实际直接连接使用本页扁平字段。Codex JSON-RPC 与桌面内部 IPC 仅出现在 Adapter 内部。

失败回执可附加 `supersededByTurnId`：原 owner 的有序快照确认该失败任务之后已有真实新轮。字段只影响底部提示的相关性，不改变 failed 状态、错误、正文或附件；不适用于未确认提交。原轮错误同时投影为稳定 ID 的 `messages.kind=turnFailure` 历史记录。新手机收到该字段后隐藏过时提示，但仍显示 outbox 中的失败和所有 uncertain；旧 Host 缺少字段时沿用原回执规则，新增字段不改变执行或重试协议。

默认独立桥接的正式 `desktop:creations`、`desktop:creations:projectless`、`desktop:open` 由私有配置、本机 Host／实际桌面版本及真实运行时就绪决定，不要求 verification 或验收报告。正常安装省略 `validationOnly`，用户明确的验证专用配置保留；旧控制聊天兼容 driver 保持原证明规则。此策略不改变鉴权、原 owner、准确 Turn、准备进程退出及持久去重边界，真实 DEV 结果继续实际执行并单列证据。

每个请求都需 `Authorization: Bearer <computer-token>`。拒绝 Origin。除初次获取工作区外，Host ID 必须明确且匹配；对话操作还必须有当前 Host 已登记的 Thread ID。服务端校验输入与附件本地归属。

`commands:target-bound:v1` 声明 HTTP 边界支持可选 Query `target`：完整领域目标的 JSON（`agentId`、`account`、`hostId`、`workspaceId`、`runtimeId`），经过 URL 编码，最长 8192 个字符。Host 在路由／读取请求体前校验当前目标，并在 JSON 请求体、聊天附件和首条附件接收后再次核对；独立文件互传在开始处理流之前核对。类型无效返回 400，机器运行目标变化返回 409；Codex 账号变化不构成拒绝条件，错误目标不能触发创建、上传或执行。原扁平 Host／Thread 字段继续必填；旧客户端不带该参数时沿用原协议。Android 新执行接口携带完整目标，生产执行界面接入时须核对该能力及实时控制状态；模块与辅助检查存在不等于执行闭环已交付。

UM01 的手机存储选择与上述连接身份一致：Codex 读取、缓存和可编辑草稿按机器目标匹配，EasyWork 仍核对账号。该策略不修改任何已捕获请求体或 `target`，不重新计算原操作摘要，也不简化私有 claim 的 HKDF/AAD。旧账号命名空间有多份候选时由用户明确选择；未确定前仍可核对原记录，但不能新提交。换 Host、workspace、runtime 或认证材料仍须原有校验。实际原生与真实执行结果逐端登记在[迁移台账](acceptance/mobile-evidence/upstream-migration.json)。

对话分支已实现三个具名接口：`GET /v1/threads/fork-options?hostId&threadId` 返回当前目录和工作树两种选项的可用性及原因；`POST /v1/threads/fork` 严格接收 `{hostId, threadId, turnId, environment, clientOperationId}`，其中 environment 为 `same-directory` 或 `worktree`；`GET /v1/threads/fork?hostId&threadId&id` 查询原操作。回执保留完整请求，并返回 phase（submitted、confirmed、outcome-unknown、rejected）、forkedThreadId、projectId 和 error。未知字段、非终态或不存在的准确 Turn、跨 Host／Thread 的操作和相同 ID 的不同意图拒绝。手机不能指定目录或任意上游方法。

`threads:fork` 与 `threads:fork:worktree` 只由就绪的独立原桌面桥接声明。Host 通过官方 `thread/fork` 的 `lastTurnId` 保留至指定轮次的历史，准备器只执行元数据操作，正常退出后才由官方会话 URI 交给原桌面；confirmed 还要求准确原 owner、目录和继承历史核对。工作树从源 Git HEAD 创建 detached checkout，复制当前已跟踪改动和非忽略的新文件，保留嵌套工作目录，源分支及暂存区不变；新树位于当前用户的产品私有目录 `~/.config/agent-hub/worktrees/<host-id>`，避免位于源仓库内。不支持的 Git 状态或不安全路径会拒绝准备；实际支持与限制见 分支验收（本地文件 `docs/acceptance/thread-forks.md`）。

分支意图在副作用前持久化；超时、重启或未知结果只查询同 ID，不重发创建、打开或模型输入。手机本地“结束等待”保留不可变回执并停止该操作的自动等待，不表示远端已取消；用户可以显式发起新的分支。迟到结果只更新原回执，不抢占新操作、会话或草稿。成功完成后也可再次主动分支，每次新意图使用新的 ID。真实 DEV 证据与 fixture、编译分别记录。

Thread ID 是 Host 返回的不透明稳定标识。托管模式恢复从未提交过任务的空会话时，内部执行 ID 可能改变，手机和附件仍使用原逻辑 ID；内部映射不进入协议。此恢复只适用于上游明确报告缺少持久历史的空会话，不适用于已有执行记录或共享桌面对话。

| 方法 / 路径            | 请求                                                                         | 返回                                                                     |
| ---------------------- | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| GET /healthz           | 授权头                                                                       | 进程存活与当前运行时 capabilities                                        |
| GET /v1/workspace      | 授权头；可选 projectCursor、threadCursor                                     | hostId、clientType、connectionMode、capabilities、项目与线程、下一页游标 |
| POST /v1/projects      | hostId、name、directory、clientOperationId                                   | 工作区快照；目录必须在授权根下                                           |
| POST /v1/threads       | hostId、projectId、clientOperationId                                         | 对话；重复 operation ID 返回同一对话                                     |
| GET /v1/history        | hostId、threadId；可选 cursor                                                | 消息、runtime、epoch、cursor、nextCursor                                 |
| POST /v1/attachments   | Query: hostId、threadId、name；Content-Type 和 X-Content-SHA256；二进制 body | 附件元数据；最大 20 MB                                                   |
| GET /v1/attachments    | hostId、threadId、id                                                         | 二进制文件；再次检查路径及 SHA-256                                       |
| POST /v1/replies       | 下例                                                                         | 持久发送回执                                                             |
| GET /v1/receipts       | hostId、id                                                                   | 已登记消息结果；不存在 404                                               |
| GET /v1/events         | hostId、epoch、after                                                         | 最长等待 15 秒的实时批次；最多每 75 ms 合并输出                          |
| POST /v1/stop          | hostId、threadId、turnId                                                     | ok；旧 Turn 拒绝                                                         |
| POST /v1/queue/cancel  | hostId、threadId、clientMessageId                                            | ok；已经提交则拒绝                                                       |
| POST /v1/queue/execute | hostId、threadId、clientMessageId；可选 expectedTurnId                       | ok；立即认领原排队项，任务变化或已取消时拒绝                             |
| POST /v1/approvals     | hostId、threadId、turnId、approvalId、decision，或 answers                   | ok；已处理或过期 409                                                     |
| GET /v1/models         | hostId                                                                       | 模型列表；使用电脑当前 Codex 登录                                        |

`POST /v1/threads` 的 projectId 必须是已登记项目 ID，或显式 null。null 仅在 `threads:create:projectless` 能力开放时代表无项目，工作目录为 Host 授权根目录；缺失、空字符串或未知项目拒绝。同一 operation ID 不能跨项目复用。原桌面模式不声明或提供这项创建能力。

`GET /v1/events` 可额外指定已登记的 threadId，仅返回该对话的事件和 reset 快照，未知目标返回 404。cursor 仍是 Host 事件序号，其他对话产生事件时可返回空 events 并推进 cursor。iOS 切换对话会取消旧请求、以空 epoch 重新订阅，从权威快照恢复；不把旧订阅游标直接用于新的目标。省略 threadId 保留完整 Host 批次兼容性。

工作区与历史／事件快照的 `thread.hasUnreadTurn` 保留原桌面 owner 的可选布尔标记。已读广播只提示已有 owner 刷新，不采信其他账户 context 的广播值、不建立 owner，也不授予控制权限。手机浏览历史不导航原桌面；前台在线侧栏每 3 秒刷新工作区，运行中与待审批图标优先。

`threads:read-state` 表示本产品已实现独立手机阅读记录。工作区、历史与事件返回 `readTarget`（Agent、Host、工作区、运行时、账户）和可选 `thread.readState`：`version: 1`、`latest: { revision, messageId, contentVersion }`、当前请求的 `readerId` 及该阅读者的 `read`。`contentVersion` 是最终回复 ID、文字与状态按 UTF-8 字节长度 framing 生成的 SHA-256；同 ID 的内容变化仍推进持久 revision，事件 epoch／游标和时间戳不充当阅读版本。Codex 本地账户仅返回稳定身份的摘要，不返回登录凭据。

`GET /v1/workspace`、`GET /v1/history`、`GET /v1/events` 可带 64 位小写十六进制 `readerId`，只投影该阅读者的记录，不暴露其他阅读者。`POST /v1/threads/read` 严格接收 `hostId`、`threadId`、完整 `target`、`readerId` 和 `position`；目标／账户变化或回复已更新返回 409，不将旧确认应用到新回复。同一阅读者确认同一版本幂等，记录保存在本产品状态中，重启不丢失；不调用桌面写接口、模型或修改官方历史。

iOS 在前台、会话无遮挡、最新回复实际可见且文字版本匹配时先记录本地已读，再保存并同步准确版本。读记录按完整目标隔离，阅读者另按安装设备区分，连接地址不参与身份。首次观察使用当时桌面标记建立独立基线，避免升级时将所有历史变成未读；之后桌面已读变化不代替手机阅读，新回复由版本变化重新提醒。旧快照不降低最新阅读版本，断网保留同版本待同步记录，迟到回执只更新对应目标／版本。迁移不复制另一设备的阅读者与通过记录。缺少新能力或版本字段的旧 Host 仅依据已有摘要、缓存回复和通知维护手机本地阅读基线，不声称手机确认已同步。

回复例子（附件必须已上传到同一对话）：

```json
{
  "hostId": "computer-id",
  "threadId": "thread-id",
  "clientMessageId": "unique-message-id",
  "text": "检查截图并读取附件",
  "attachmentIds": ["uploaded-attachment-id"],
  "mode": "next-turn"
}
```

`mode` 支持 next-turn、queue、steer。steer 必须附 expectedTurnId；可选 model/effort/permissionMode/speed 只用于新一轮/排队，补充指令拒绝设置变更并沿用当前任务设置。最多 10 个附件、100,000 字符、150 KB JSON 请求体。普通文件变为带受控路径的用户文件上下文，图片是 Codex 原生图像输入。

运行时声明 `plan-mode` 时，回复可选 `collaborationMode: "plan" | "default"`，与提交方式 `mode` 独立。省略时继承运行时设置；queue 保存所选模式，到下一轮才应用；steer 不允许切换模式。Adapter 使用明确选择或实际线程继承的模型与推理设置构建上游协作模式，模式内置指令使用 null；未指定 permissionMode 时继承权限。桌面首条创建的 prepare 请求也可带 collaborationMode，它参与操作指纹、持久化与真实首条输入提交，不影响初始化轮。

`runtime.collaborationMode` 同步当前计划／普通模式；`runtime.proposedPlan` 为 null 或 `{id, turnId, text}`。桌面只从最新完成轮的未处理 planImplementation／真实计划确认状态提取；公开运行时从该轮 plan item 提取。`plan` 正文映射为 assistant 消息，手机独立显示可展开的计划卡片；执行步骤 `runtime.plan` 仍单独展示。手机执行时发送 `PLEASE IMPLEMENT THIS PLAN:` 与完整计划、collaborationMode=default；调整时发送用户反馈、collaborationMode=plan。两者附 `expectedPlanId`，Host 和桌面 Adapter 在提交前核对仍为当前计划；新消息 ID 的过期操作拒绝，原消息 ID 重试仍返回同一回执。进行中、离线、只读或 owner 丢失时不开放计划执行／调整。原桌面开始新轮后清除旧计划确认，手机按权威快照更新。

回执含 clientMessageId、hostId、threadId、state、turnId、error，以及原指令 text、attachmentIds，供手机显示排队预览。历史快照保留该会话全部 queued 回执和最近 30 条其他回执，顺序为提交顺序。状态：received → submitting → submitted → completed/interrupted/failed；另有 queued、cancelled、uncertain。相同消息 ID 和内容返回已有记录；ID 相同内容不同拒绝 409。运行时超时或连接断开后不自动再次执行。

队列回执可带 `queueSource`、`queuePresent`、`canExecuteNow`。`queue:execute` 能力开放立即执行按钮；运行时有活动任务时沿用其设置追加到该任务，空闲时开始下一轮。Codex 的原队列设置与当前任务不兼容时保留该项并拒绝立即追加。请求始终引用原消息 ID，不能替换正文或附件；原始 queue 意图保持不变。并发同项动作只提交一次，已确认接管返回已有结果，提交未知时只核对。手机切换目标后晚到结果不能覆盖当前界面。`queue:native` 仅表示经过验证的桌面原生队列接入，不能仅凭后台队列可读声明；Codex 在原生队列列表实际可用后声明该能力，采用允许短时延迟的同步方式；不提供手机与桌面同时操作时的跨端原子保证。

历史快照还含 thread 摘要（id、hostId、projectId、title、state、turnId），用于同步新对话标题与侧栏状态。消息含 id、role（user/assistant/tool）、text、files（兼容历史模式）、kind、status、detail、attachments。命令文本和输出分开；文件修改 detail 是 Diff；工具结果为文本；reasoning 只呈现上游公开摘要。附件含 id、hostId、threadId、name、mediaType、sizeBytes、sha256，不返回下载路径。可选 `resourceId` 为同一对话、同一登记路径的稳定不透明身份，跨内容版本及 Host 重启保持；只用于界面生命周期，下载与提交仍使用版本 `id` 和摘要，不能用资源身份替代完整性校验。缺少该字段时客户端回退到版本 ID。

消息可选 `runId` 标记真实任务归属，助手消息可选 `replyPhase=commentary|final_answer` 区分过程说明与最终回复；由 Adapter 从原 owner、公开事件或历史条目映射，不修改消息 ID。iOS 在 run 结束后仅提供一组最终回复复制／分享，同轮追加不拆组；缺少字段的旧 Host／缓存按用户输入后的回复序列兼容展示。这些字段只用于排版，历史读取不因此成为实时连接或任务结束的证明。

`desktop-runtime` 的 `/v1/workspace` 在返回列表前发现已有原 owner，使用其验证后的快照更新 `state` 与 `turnId`。此行为不依赖手机先请求 `/v1/history`，也不调用 `/v1/desktop/open`。owner 不存在或发现超时返回 `unknown`，不以 metadata 的 `notLoaded` 或持久缓存宣称空闲。列表发现和后台摘要同步不加载聊天正文或开放新的执行能力；`history-only` 的状态仍为 `unknown`。

上下文压缩沿用消息与事件快照，消息 `kind=contextCompaction`、`role=tool`，保留原 item ID 与时间线顺序。Adapter 将公开 `item/started` / `item/completed`，以及桌面 owner 的 `completed` 布尔值和所属 Turn 状态，映射为 `inProgress` / `completed` / `interrupted` / `failed`。owner 丢失、连接中断、Host 重启或压缩 item 从权威快照消失后，尚未确认结束的缓存状态变为 `unknown`，不得继续声称正在压缩。手机将该消息单独显示在聊天中；实时可写连接显示“正在压缩上下文 · 可能需要几分钟”，完成显示“已压缩上下文”，失联显示状态待同步。上游没有提供百分比或剩余时间，不推算进度，也不向手机返回压缩摘要或加密内容。

`runtime` 含 state、turnId、plan、error、approvals、receipts、collaborationMode、proposedPlan、generationSettings。plan 是实际步骤状态，没有捏造的百分比。审批 decisions 使用上游支持的 accept、acceptForSession、decline、cancel 等选项；问题回答为 `answers: { "question-id": ["回答"] }`，手机不可自动提交预选答案。

`runtime.receipts` 是有界近期视图，不能作为手机待确认操作的唯一目录。前台等待恢复按原 `clientMessageId` 退避查询 `GET /v1/receipts`；备用 `GET /v1/replies/status` 的 `accepted` 及内含准确回执也用于正向确认。确认同时解除该次本机发送锁，不要求原 POST 已返回；迟到传输错误不能推翻准确回执或操作后续草稿。所有恢复只读，404／缺项和任务空闲不授权重发，未知项仍可本地结束等待并保留原记录。

Codex 异步问题使用可选 `interactionMode: "async"` 与 `sourceItemId`，`kind` 仍为 `question`。`questions[].id` 是不透明的问题身份；客户端原样提交 `answers`，不解析上游 ID。该问题不将任务状态改为 `waiting-for-approval`。`POST /v1/approvals` 对异步问题接受普通 `answers` 或 `decision: "skip"`；跳过不执行模型输入。运行中回答追加到原 Turn，最近任务结束后回答继续同一聊天，有新任务时旧问题拒绝。处置持久化且并发合并，已提交／未知回复只核对同一回执，不改变内容或自动重发。缺少可选字段的旧审批与同步问题保持兼容。实际范围见 异步问题验收（本地文件 `docs/acceptance/async-questions.md`）。

工作区列表与历史／事件的 thread 摘要增加可选 `pendingAsyncQuestionCount`，按同一 Host／Thread 的有效待答异步问题计算，零表示没有待答问题。该数量独立于任务状态，任务结束后仍可存在；已确认回答或跳过后减少，未确认的回答仍计入。手机最近与项目对话列表用问号气泡显示待答问题，审批保留小手，两者可同时出现；执行中另保留转动标记。旧 Host 未返回该字段时，只从相同目标的已有 runtime 缓存补充，未加载聊天不推断待答。owner 不可用而状态变为 unknown 时，手机保留此前已看到的待答数量；取得有效状态后再更新，缓存标记不授予控制能力。

保存不可变回答意图不表示桌面已接收。失败或未知回答继续出现在待答与通知快照中；同一内容的再次提交只查询原回执，不创建第二条输入。原 owner 接收确认与持久处置确认后，才移除待答；桌面尚未接受的追加草稿不能作为接收证据。Host 的 `ok: true` 表示该问题处置已确认，不承诺官方桌面的问题窗口已关闭；后者需独立 UI 验收。

附件解析与下载使用有界异步 I/O，20 MiB 限制、完整正文和真实文件摘要保持不变。原 owner 的状态与审批先发布，附件解析期间保留上次完整消息；新消息投影完成后再发布事件。显式历史读取等待该线程的完整投影；同一线程的并发快照合并为最新状态，workspace 不等待全目录附件解析。下载每次重新核对真实路径、授权根、文件身份和 SHA-256，删除／内容变化返回冲突，越权路径拒绝。

事件返回 hostId、ready、capabilities、epoch、cursor、reset、events。每项是 threadId 与最新消息/runtime 快照，多个增量合并仍包含全部文字；epoch 变化、游标过期或未来游标时 reset=true，指定 threadId 时仅返回该目标快照，未指定时返回所有登记线程的最新快照。运行时断开时 ready=false，撤销运行时能力，Host 自身的 notifications 和 files:upload 能力仍可声明；恢复连接改变 epoch，客户端重新加载权威历史。历史与回执持久化，事件窗口在内存中；客户端不把历史缓存当作当前运行状态。

错误 JSON 为 `{ "error": "描述" }`。400 无效输入，401 密钥无效，403 Host/附件/目录越界，404 不存在，405 不支持的方法，409 前置条件冲突，413 请求过大，502 上游失败，503 运行时未连接。跨网络必须 HTTPS，iOS 不关闭证书验证。

共享模式每页列表最多 100 项，按 ID 合并已登记列表，返回上游不透明游标；paginated 线程首次读取最近 100 个 item，cursor 继续加载更早记录并与当前快照去重合并。旧式历史使用返回的完整 Turns，没有应用层分页。托管模式只保存本服务创建的线程和输出，没有向独立 stdio 服务恢复任意现有桌面对话的入口。该共享分页实现尚未在本机官方桌面的私有运行时中验收。

工作区包含独立 `clientType: { id, name }`，当前 Codex Adapter 返回 `codex` / `Codex`。iOS 用名称显示连接来源、创建和打开提示，不再用“原桌面”作为客户端名称。电脑名称和传输模式保持各自含义。未被 Codex 加载的聊天及历史分页也保留工具的 kind、status、detail；仅在有效 owner 存在时才允许控制，历史工具不改变 `canControl` 判断。

## Codex 客户端模式的能力边界

`connectionMode=desktop-runtime` 使用相同 HTTP 合约，当前支持 projects:read、threads:read、history:read、reply、attachments:upload/download、events、stop、queue、steer、approvals、plan-mode、models:read、generation-settings。`runtime.canControl` 只在已确认桌面 owner 并收到兼容快照时为 true；iOS 不能只凭工作区 reply 能力启用某条历史的写入口。未加载会话、owner 丢失或不兼容快照关闭写操作；其存储历史仍可读，运行状态不能据此判定。

空闲回复使用 next-turn；queue 只提交到 Codex 原生队列，客户端确认后才返回 queued。不可用或不支持时返回明确的 failed 回执，不回退为 Host FIFO。后续调度由客户端负责。steer 必须携带 expectedTurnId，Host 和 Adapter 核对当前 Turn 后转发原 owner 的追加输入；当前桌面内部命令由 owner 取活动 Turn，不提供额外的原子 expectedTurnId 参数。停止必须带原 active Turn ID，转发使用 user-stop 与 expectedTurnId。审批由同一 owner 处理，原桌面已处理的请求从手机快照移除，不把 Host 缓存当作最终审批状态。手机端模型、推理与权限选择通过 `generation-settings` 和 `models:read` 能力开放。历史与项目分组保留旧会话的 projectIds 显示映射；最近列表刷新采用上游当前顺序。

## 实验性原桌面创建操作

2026-10-08 已接入正常启动的原桌面控制聊天。基础配置默认不提供新建；控制聊天、构建与真实验收记录匹配后才启用。原桌面 `POST /v1/threads` 继续返回 409；新流程在手机首条发送时由原内置工具创建，并通过新 owner 发送原生首条输入。

| 方法 / 路径                           | 请求                                                                           | 返回                                                            |
| ------------------------------------- | ------------------------------------------------------------------------------ | --------------------------------------------------------------- |
| GET /v1/desktop/creation-status       | hostId                                                                         | available、reason                                               |
| POST /v1/desktop/creations            | hostId、clientOperationId、projectId（显式 null 或已有 ID）、text、attachments | 创建操作草稿，尚无 Thread ID                                    |
| POST /v1/desktop/creation-attachments | Query: hostId、id（操作 ID）、attachmentId；二进制 body                        | 所属操作、附件摘要与大小                                        |
| POST /v1/desktop/creations/submit     | hostId、clientOperationId                                                      | 创建操作状态；只在 confirmed 时返回真实 threadId 与 thread 摘要 |
| POST /v1/desktop/creations/detach     | hostId、clientOperationId、acknowledgeUncertain（布尔值）                      | 保留原操作，返回 detached=true；不撤销、不重放远端请求          |
| GET /v1/desktop/creations             | hostId、id                                                                     | 查询并恢复原操作；不重复调用创建工具                            |
| GET /v1/desktop/creation-receipts     | hostId、id                                                                     | 只读核对已有回执；不调用创建或首条输入恢复入口                  |
| POST /v1/desktop/open                 | hostId、threadId、clientOperationId                                            | opening / opened / outcome-unknown                              |
| GET /v1/desktop/open-status           | hostId、threadId、id                                                           | 该目标的打开状态                                                |

附件清单每项为 id、name、mediaType、sizeBytes、sha256。最多 10 项，每项 20 MB；上传字节须与预登记大小和摘要一致。操作 ID 固定目标、正文与清单，复用不同内容返回 409。目录由已登记项目和原内置 list_projects 的实际目录映射解析，不接受手机提供任意本机路径；不能混用两个项目目录里的不同 ID。目录证据不唯一则拒绝。

附件文件名与已有对话上传一致，最多 200 个 UTF-16 单元，不按 UTF-8 字节数限制中文名称；超限明确提示文件名过长。原名称保存在不可变清单中，超过 200 个 UTF-8 字节时使用短内部名称及有限扩展名暂存，防止文件系统单段路径超限；已有短名称暂存路径保持兼容。此规则已实现，真实环境验收与辅助回归分别记录。

状态为 prepared → submitted → confirmed；提交后失败或服务重启转 outcome-unknown，明确在发送前拒绝为 rejected。桌面创建串行处理；尚未结束等待的前一次未知操作会阻止后续创建。明确 detach 后保留原不可变回执与未知状态，允许不同 ID 的新操作；submitted／outcome-unknown 必须 acknowledgeUncertain=true，prepared 转 rejected。对原 ID 再次 submit 默认只返回已有结果。仅下述 input-retryable 原前置检查证明可由显式 submit 继续原输入；普通未知与可能已写入不重放。App 的本地草稿可编辑、清空和删除未引用附件，与不可变创建内容及其附件分别保存。结束等待须明确确认可能已执行，晚到旧回执不覆盖新草稿或当前目标。App 保存不可变创建内容、支持查询；prepared 可明确继续上传，rejected 可返回草稿，confirmed 后进入真实会话且不重复发送首条。

正常启动 driver 位于 Adapter 的 desktop-controller.ts。生产配置要求固定 Host、控制 Thread、桌面构建、Adapter 摘要，以及 real-desktop-controller 六项创建门槛和未加载打开记录；原 owner 丢失即撤掉创建/打开能力。通过后声明 desktop:creations、desktop:creations:projectless、desktop:open，不声明旧接口 threads:create。validationOnly 默认仅声明 desktop:creations:testing 与 desktop:open:testing，普通 iOS 不使用这些测试能力；已验证的独立打开入口见下文。旧 UI driver 未启用。

创建与主动打开可分别验证。`openOnly=true` 配合 `real-desktop-controller-open` 证明仅声明 `desktop:open`，不允许创建；证明仍须匹配 Host、控制聊天、实际桌面版本与当前进程加载的 Adapter 摘要，并包含 `gates.open=true` 和 `unloadedOpen=true`。`openVerification` 可在创建尚待验收或仅处于 `validationOnly` 时保留已验证的正式打开能力，不能用打开证明启用创建。原 owner 丢失或打开证明不匹配时撤掉打开能力；已有聊天仍按其实际 owner 判定可控。Windows 私有状态文件显式设置用户 DACL，创建日志保持文件 fsync 与原子重命名；目录 fsync 只在平台支持时执行。

确认逻辑核对原 create_thread 工具的实际参数/回执、新 IPC owner、实际 cwd / projectless 状态、首条 native userMessage、localImage 与文件字节摘要。官方 project_id 可为空，显示归属保存在 Host 自己的缓存。原桌面先完成明确初始化轮，再接收固定 first-operation-id 的原始首条输入；手机隐藏已标记的初始化轮，不编辑历史。Host 重启查询可恢复已确认的实际 ID，首条输入使用同一回执 ID 去重；无法明确时保留 outcome-unknown。

打开只接受本 Host 已知聊天，通过已配置的原桌面桥接加载准确 ID，确认目标原 owner 后返回 opened。打开不发送占位消息，不创建新聊天。iOS 浏览、刷新、分页及事件补同步直接读取历史，不以打开确认作为前置条件；未加载会话仍可读取历史；同时具备回复和已验证打开能力时，每次点击发送先执行具名打开操作确认准确 owner，不使用历史缓存中的可控状态跳过确认，确认后才上传附件和提交这一次输入，无独立连接按钮。连接操作 ID 持久保存在完整目标的草稿中，结果未知时下一次发送先查询同一操作，不自动重放输入。连接失败保留历史和草稿；目标切换后旧连接结果不触发提交，晚到发送回执不改写新草稿；只读索引暂时遗漏的聊天不会通过浏览自动导航。已加载目标仅确认 owner，不重复导航。Host 提交前再次核对准确 owner；既有历史聊天也可恢复同一 owner，执行阶段仅在明确命令尚未写出的错误下恢复一次，写出后的超时不重放。并发打开同一目标共享动作；iOS 用 Host/Thread 和请求 generation 防止旧结果覆盖新选择。下载额外允许该目标原 owner 确认的 cwd，仍须是当前聊天已经登记的附件；真实路径、符号链接与文件摘要继续核对。最新验证见 发送自动连接验收（本地文件 `docs/acceptance/reply-connect.md`）。

创建恢复的本地边界验证：原生 HTTP fixture 已覆盖未知状态下编辑／清空草稿、detach、正常后台保存后重启、晚到确认不改变新草稿和新操作 ID。Host broker／session 检查覆盖保留原 journal、不重放、明确确认和公开 DTO 的 detach 标识及私有字段隔离。这些是辅助验证，本轮尚未部署到目标电脑或正式 iPhone，也不证明控制聊天的生命周期依赖已消除。

`GET /v1/desktop/open-status` 对结果未知的已有操作，只读重核准确原 owner；收到兼容的真实快照后可返回 opened 并清除原错误，不重复调用打开入口或发送输入。手机发送使用单调时钟的三分钟等待上限，连接结果未知时继续查询原 ID，确认后提交一次。超时或查询失败保留草稿及该连接 ID；已可能写出的模型操作仍按原回执查询，不重放。

## 模型、推理与权限设置（2026-10-09）

2026-10-16 UM02 补充恢复契约：`GET /v1/replies/status` 固定查询完整原 target、hostId、threadId 和 id，响应使用严格 `replyInspection` Schema。v2 强否定证明必须同时具备显式空 receipt、完整原 target/capturedTarget、原 v2 指纹、不同的旧／新进程和已审查隔离交接的账本／围栏摘要，观察时间在 60 秒内。404、字段缺失、旧 Host、普通重启及当前空历史均不能授权再次提交。该接口的 `accepted` 可提供原回执；结果未知继续查询。平台不具备目录持久化等证明条件时也保持查询。

Android 使用公共有序原输入向量核对 Host SHA-256，原证书和加密命名空间参与本地授权检查；点击“重试原消息”会重新读取新鲜证明并先持久消费一次性授权，再提交原内容与设置。修改当前草稿不改变原操作。旧账本缺少捕获上下文时不创建新授权；修改原消息先保护非空新草稿，再结束原等待并恢复为可编辑草稿。结束等待保留远端操作与可查询回执。

独立创建桥接在实际外层 `outcome-unknown` 回执中，通过 `preparation` 提供准确准备状态。仅当已尝试的原操作同时含有非空 `preparedThreadId`、`preparationPhase=input-retryable` 与 `diagnostic.disposition=retry-original-preflight` 时，两端才允许显式提交同一创建操作；不把外层未知改写成 prepared。Host 重新校验原 owner、退出的准备器及原 checkpoint；手机只读查询原创建回执，再提交原意图。Host 状态查询不消费 input-retryable，不从失败前置检查自动继续；只有显式 submit 在 Host 内重新读取原证明并继续原输入。iOS 同时保存原输入授权范围，省略模型的请求不能在重试时被当前模型重新解释；旧记录未保存范围时需重新明确许可，不能默认为已授权。尚未尝试的 prepared 仍按原流程提交。已可能写出的首条保持未知查询，不显示继续提交。受控故障、原生 HTTP、真实原桌面和各目标机器的结果分别记录，不用辅助证明代替实际模型验收。

`GET /v1/models?hostId=<host-id>` 返回当前电脑可用的模型目录：`models: [{id, name, isDefault, defaultEffort, supportedEfforts}]`。Adapter 分页读取 `model/list` 并过滤隐藏模型，使用实际 model 标识；桌面模式通过已有的只读 metadata reader 获取目录，不 resume 或启动任务，不将目录结果作为 owner 连接证明。

`runtime.generationSettings` 为 `{model, effort, permissionMode}`；字段未知时为 null。桌面值来自 owner 最新 Thread 设置、协作模式、权限和最近 Turn。owner 丢失后清除这组实时设置；自定义或未匹配的权限保留未知状态，不推断为完全访问。

回复和桌面创建草稿可选 `model`、`effort`、`permissionMode`，省略沿用电脑。权限值限 `read-only`（请求批准）、`guardian-approvals`（帮我批准）、`full-access`（完全访问）。版本相关 permission profile、approvalPolicy 与 approvalsReviewer 转换留在 Adapter。模型与明确指定的强度须符合该 Host 的实际目录；无效组合返回 400。steer 不接受生成设置变更；queue 保存不可变选择，在轮到该消息时提交。

默认权限在执行时读取目标电脑：桌面连接优先读取该电脑保存的权限选择，再通过只读 `config/read` 解析有效配置；托管连接读取同一官方配置接口。完全访问同时传 `dangerFullAccess` 沙盒和 `never` 审批；未配置时回退为工作区沙盒和自动审批。明确选择的手机权限优先，电脑明确配置的手动审批仍保留。公开 App Server 使用具体 `sandboxPolicy`，桌面的具名权限字段不能替代它。桌面显示以 owner 的 `currentPermissions` 为首要证据，随后才是完整线程设置或最近 Turn；不拼接不同来源的旧权限字段，也不把发送选择直接当作已生效权限。`steer` 不改变当前任务权限。

当前手机的权限选择仅支持上述三档；电脑使用尚不能解析的自定义具名 profile 时，默认请求在提交前明确拒绝，不回落旧缓存的完全访问选择。用户可在手机明确选择受支持档位。配置读取失败同样在提交前返回失败，不标成已执行或自动重放。

设置参与消息和创建操作的内容指纹、去重与重启恢复。桌面 start-turn 的 collaborationMode 对象内同时携带所选 model 和 reasoning_effort，避免显式计划模式使单独 model/effort 字段失效。未选设置时继续继承 owner。模型目录不是公开跨版本的可用性保证，上游的账户／权限限制仍可拒绝提交并产生失败回执。

## 版本化通用管理契约

`packages/protocol/src/agent-management.ts` 与 `schemas/agent-management-v1.schema.json` 定义独立的管理 v1 契约。实际传输继续由 Codex HTTP 和 EasyWork Agent Protocol v2 适配器映射。目标身份包括 Agent、账号、Host、workspace、runtime；连接候选、业务操作 ID、信封序号和事件游标分别保存。公共通信采用严格具名操作，新增 Agent/Transport 通过注册接口扩展。

管理 v1 以可选字段和具名操作扩展模型目录、生成设置、计划版本、计划批准／修订与队列取消。`message.send` 可指定 next-turn、queue 或 steer；steer 绑定准确 Run，设置只用于普通发送、队列和计划执行。权限是 Adapter 提供的描述符和不透明 ID，公共界面不把 Codex 的权限名称套到 EasyWork。新增命令仍受严格字段白名单和完整目标约束。

EasyWork v2 已实现 `model/list`、带设置的 `turn/start`、`plan/action` 和 `queue/list`、`queue/status`、`queue/cancel`。目录来自真实 Provider Registry/Catalog；模型和强度在执行前核对，未支持的速度不声明。计划批准只接受当前 ready/saved 计划及匹配版本，进入同一 Thread 的原生审批入口；修订绑定源计划及版本。排队输入交给客户端，远程层保存不可变输入与回执；交付前保存 dispatching，重启后未知提交转 uncertain，不自动重放。审批、权限和目录授权仍由原桌面及 Host 执行。

EasyWork 当前队列桥接开放 `queue/list`、`queue/status`、`queue/cancel`。手机输入经具名、类型化 IPC 交给桌面已有的持久队列，与桌面输入共用同一列表及调度器。远程层只保存投递意图与回执，没有定时执行器。客户端确认持久接收前不返回 queued；交付未知只核对同一 ID，不重投。旧远程 FIFO 项保留为 legacy-review，不迁移或执行。本次不声明 `queue:execute`；历史实现说明与验收不能替代当前实现证据。客户端队列读取失败通过 `queueSyncError` 明确报告，不能把失败投影成取消。

EasyWork 的 `thread/sync` 投影最新计划、完整队列回执及当前分支的最新真实根 Run。手机只用真实 Run 判断失败／取消和生成设置；终态 Run 不保留活动任务按钮。项目相对路径以 Host 提供的默认工作目录解析，拒绝越界；实际创建通过已授权目录引用，手机不传任意路径给执行器。上述为已实现行为，真实 DEV 状态另见验收记录。

固定产物的版本与摘要保存在 `contract-manifest.json`，EasyWork 保存同一 schema/manifest；`pnpm check:agent-contract` 核对严格 schema 与实际 Swift 编解码，指定 `--easywork-root <checkout>` 可同时检查跨仓库产物。回执 `completed` 表示管理命令已返回确定结果，不表示模型运行完成。EasyWork 的 SQLite 回执复用原 Thread/Run 引用；断开后的 `uncertain` 必须先查询、核对，不能盲目重发。

连接候选可选 `networkKind: lan | vpn | unknown`，旧缓存缺省仍可解码；VPN 保持 `transportId: lan-wss`，保存的 `lan-only` 不改名。EasyWork 的只读 `/host` 和二维码描述保留 v1 与 `endpoints`，增加可选 `endpointHints: [{ endpoint, networkKind }]`，来自同一地址快照。首次绑定要求二维码提供完整目标、公钥及证书指纹，先验证 `/host` 再提交连接。后续发现仅向已保存入口和已知电脑的 Bonjour 入口发送固定指纹的 `/host`，完整目标、公钥匹配后才更新候选，不调用 `/connections`。

2026-10-12 增加私网只读 `GET /discovery/peers?hostId=<当前电脑>`，旧鉴权 `GET /v1/discovery/peers` 保留为兼容入口。请求仅允许这一个查询参数；响应为 `{ version: 1, target: RuntimeTarget, peers: [{ name, addresses }] }`。最多 32 台在线桌面、每台两个 VPN IP，来源完整目标和证书必须与公开发现来源相符；不要求来源已经接入。手机向标准端口发送无凭据公开描述探测；保存目标更新仍需原目标、证书／绑定校验，发现不授予命令能力。

Codex 公开描述可声明 `connectionMode: automatic`。此时私网 `POST /connection/claim` 接受严格 `{ version: 1, target: RuntimeTarget, publicKey: <手机 X25519 base64url 公钥> }`。响应为相同版本、目标、手机公钥及 `ephemeralPublicKey`、12 字节 `nonce`、AES-256-GCM `ciphertext`（末 16 字节为 tag）。目标、公钥与固定域组成按 UTF-16 长度前缀连接的字段串，UTF-8 编码同时用作 HKDF-SHA256 info 和 AEAD AAD；salt 为空，派生 32 字节密钥。加密内容为手机独立 Bearer 凭据，不交付原 Host access-token。该凭据由原运行时路由按完整目标验证；工作区、命令、文件和事件仍鉴权。首次授权限已允许私网来源且拒绝 Origin 的独立 HTTPS 入口；所有目标、输入及持久化边界见 无二维码连接验收（本地文件 `docs/acceptance/vpn-peer-discovery.md`）。

EasyWork 局域网接入通过 HTTPS `GET /host` 获取 `connectionMode: automatic`、完整目标、公钥与证书摘要；`POST /connections` 只接受手机设备 ID、公钥及名称，不要求配对码或桌面确认。服务仅接受本地网络来源，拒绝网页 Origin 和额外字段，保留输入大小及频率限制。手机首次自动保存同一连接取得的证书，后续使用证书固定校验；不允许跳转到其他地址。二维码还须核对其中的目标、公钥和证书。既有配对接口保持兼容，撤销设备不能通过自动连接重新授权。

中继固定连接密码沿用既有 `/pairing/claim` 契约；已激活绑定直接返回，不进入临时配对的等待确认流程。手机首次保存绑定及私有身份，重启后恢复连接，无需再次输入固定密码。上述为已实现行为，当前验收范围见 局域网连接验收（本地文件 `docs/acceptance/lan-connection.md`）。

2026-10-10：`generation-speed` 声明速度设置已接入回复、排队及桌面首条创建。领域 `speed` 为 `standard`、`fast` 或 `ultrafast`，省略继承电脑；模型目录返回 `supportedSpeeds` / `defaultSpeed`。Adapter 按当前模型目录将档位映射为上游 serviceTier（例如 Fast 的 priority），关闭时明确提交 default，不能用省略表示关闭。无效类型、模型未提供的档位及 steer 改档均在提交前拒绝；创建／回复指纹包含所选速度，未知结果恢复保留原意图。owner 快照中的速度只表示该线程设置，不承诺实际加速倍数。

## 通知 v1

事件可在加密正文中携带 `readTarget`（完整阅读目标）和 `readPosition`（回复版本）。阅读目标必须与通知目标的 Agent／Host／workspace／runtime 一致，通知目标已有账户时也必须一致；回复版本仅允许用于具有 thread 和阅读目标的 `completed` 事件，revision 必须为正整数，内容摘要遵循阅读协议。账号与内容摘要不进入 Relay 明文信封。手机使用这些字段合并设备未读账本，不修改电脑阅读状态。

`badgeOnly` 信封用于关闭对应提醒分类、但仍开启手机角标的订阅，必须静音、非及时通知且不包含数值 badge。前台事件同样允许提供这些更新，是否展示横幅仍由分类偏好决定。Relay 发送 background／优先级 5 的静默 APNs；各 Host 不再生成自己的角标总数。旧信封数值字段仍可解析，但 iOS 以设备统一账本计数。

`task-notifications-v1.schema.json` 与 `notification-contract-manifest.json` 固定领域契约及 SHA-256，`notification-contract.mjs` 检查生成漂移。事件携带完整 RuntimeTarget、稳定 SHA-256 ID、类型、时刻和可选聊天／任务／请求／操作身份。偏好有版本、稳定修订与单调 generation；旧设置不能覆盖较新设置。设备注册包括独立手机 ID、X25519 公钥，以及成对出现的 APNs 令牌／开发或生产环境。

事件可选 `taskSummary` 保持 v1 旧缓存兼容，限制为 160 个 UTF-16 单元的单行文本；归一化空白，过滤不可见控制字符、附件数据与本机绝对路径，不切断代理对。摘要绑定实际运行／请求身份；待回答优先使用实际问题正文，缺失时回退任务摘要，审批仍优先任务摘要并可回退实际请求说明。原桌面最新任务结束后，未回答或跳过的异步问题继续使用同一运行／请求通知身份，处置后移除；owner 丢失或未知状态不提供结束证据。标题和摘要只进入加密信封，去重身份不依赖展示文本。`showThreadTitle` 沿用已有偏好字段，统一控制标题、任务摘要与问题预览，默认关闭且保留已有选择。

Codex Host 提供命名接口 `POST /v1/notifications/register`、`POST /v1/notifications/preferences`、`POST /v1/notifications/unregister`、`GET /v1/notifications/status`、`GET /v1/notifications/events`。每次请求沿用 Host 认证和 hostId，注册与偏好同时核对完整 target。EasyWork 在协议 v2 下注册对应 `notification/register`、`notification/preferences`、`notification/unregister`、`notification/status`、`notification/events`，绑定当前手机授权身份。未知字段、方法、非法声音路径和目标均拒绝。订阅更新是幂等元数据操作，不作为模型执行命令回放。

状态明确区分 `foreground-only` 与 `apns`，以及 disabled／pending／ready／unavailable；revision 表示已同步设置。最近投递区分 pending／submitting／accepted／uncertain／rejected，accepted 仅表示 APNs 接受。前台流包含稳定 cursor、明确待处理 activeIds 和状态；APNs 订阅的 events 不重复呈现本地提醒。Relay register／unregister／publish／cancel／status／binding-status 使用独立 Host 发布凭据，手机 revoke 使用仅绑定有效的管理凭据，不提供任意 IPC、shell 或方法透传。详见 [通知运行指南](notifications.md)。

EasyWork 队列持久文件以完整 RuntimeTarget 的固定字段序列摘要分域。工作区迁移遗留的旧账本按目标导入，原始账本保留为私有旧版归档；不执行外目标输入，不复制授权身份。导入完成后原有 FIFO、取消、稳定操作 ID、未知结果与设备隔离规则保持不变。迁移和真实 Host 重启必须独立验收。

## Codex Host Discovery v1

私网 HTTPS 入口的只读 `GET /host` 返回 `version: 1`、`agentId: codex`、名称、完整目标、TLS SHA-256 指纹、全部 HTTPS 根地址和可选 `endpointHints`。严格契约为 `packages/protocol/schemas/codex-host-discovery-v1.schema.json`。原 Host 路由的 `/host` 保留 Bearer 鉴权。

本机私有二维码为 `codex-host://pair?descriptor=<base64url>`，在描述上增加本机客户端 `token`；token 不出现在 HTTP 描述、DNS 或持久手机连接字段中。手机先按二维码指纹校验证书和完整目标，再发送密钥请求工作区，全部匹配才存入 Keychain。控制仍走原 `/v1/*` 具名接口，未引入 Codex 内部 wire 或云端目录。

## 独立电脑文件上传

电脑发送端 `transfer-host.mjs` 复用以下 v1 接口，不增加 Host wire 或任意 shell 通道。发送前保存完整机器目标、稳定 UUID、清单与原文件快照；重复 send/status 只查询，显式 resume 才继续同一内容。详见 [电脑之间的统一发送 CLI](file-transfers.md#电脑之间的统一发送-cli)。

能力为 `files:upload`，由 Host 的 health／workspace／events 声明，独立于聊天附件和原 owner 控制。请求仍需 Bearer 鉴权并明确准确 Host ID；这些操作不执行 Agent 任务，因此不接受 Thread 或手机指定的目录。

| 方法                            | 输入                                                 | 输出                                                 |
| ------------------------------- | ---------------------------------------------------- | ---------------------------------------------------- |
| GET /v1/file-transfers          | Query: hostId                                        | hostId、destinationDirectory、maxFileBytes、files    |
| POST /v1/file-transfers         | JSON: hostId、id、name、mediaType、sizeBytes、sha256 | prepared 或原 completed 回执                         |
| POST /v1/file-transfers/content | Query: hostId、id；原始二进制正文                    | 校验后 completed 回执                                |
| GET /v1/file-transfers          | Query: hostId、id                                    | 该传输的持久回执，必要时核对已发布文件补齐 completed |

`id` 为小写 UUID，`sha256` 为 64 位小写十六进制，文件为 1 至 20 MiB；清单字段严格白名单，文件名为可移植单段名称，不允许路径、控制字符和 Windows 保留名称。回执在清单基础上增加 phase、destinationPath 和 createdAt。接收目录由 Host 固定设置，文件名加入完整 ID，既有文件不覆盖。

同一 ID 不同内容返回 409；进行中的重复上传返回 409，超过两个并发上传返回 429；未知结果先查询，查询不发送正文。completed 是已成功交付的历史记录，用户之后移动或修改文件不会自动重新上传。首次发布原子且不可覆盖；文件发布成功但回执未保存时，重启查询会先校验文件大小、身份及摘要。只读历史服务和默认健康检查 bootstrap 不新增这项能力。实现与验证边界见 [文件互传](file-transfers.md)。

## 项目与对话批量管理

已实现于 `desktop-runtime`：就绪时声明 `projects:delete` 和 `threads:archive:unloaded`，只读历史、托管模式及未接入此能力的 Agent 不声明。

- `POST /v1/workspace-management`：严格请求为 `hostId`、完整 `target`、`clientOperationId`、`action` 和有序 `ids`；动作仅允许 `projects:delete` / `threads:archive`，ID 清单去重且为 1–100 项。返回保存后的原请求和 `items`。
- `GET /v1/workspace-management?hostId=…&id=…&target=…`：`target` 为 JSON 编码的完整目标，只查询原回执；可只读核对未知项的实际项目列表或已归档列表，无变更重放。

每项状态为 `pending`、`submitting`、`completed`、`rejected` 或 `outcome-unknown`，包含准确 ID 及可空 `error`。HTTP 200 是已接收回执，不表示全部成功。Host 分项持久化，不做假原子回滚；成功项继续保留，确定拒绝项独立显示，未知项保留同一 ID 供查询。重复 POST 必须匹配原运行目标、动作和有序 ID，不能替换内容；有未完成或未知操作的同一目标不可用新 ID 重叠提交。重启不继续提交旧批次，已提交项结果未知、未提交项明确未执行。

项目删除通过 Adapter 内官方 `project/delete` 移除项目记录，不删除目录或对话。归档通过官方 `thread/archive`，仅声明 `threads:archive:unloaded`。已确认原桌面 owner 或官方 active writer 拒绝时，不通过其他执行实例接管，也不伪造已归档广播；该项明确拒绝，需在桌面归档。列表可选 `archiveUnavailableReason: "desktop-owner"`，手机据此禁用。写入前检查原 owner 当前快照及排队/未知输入。元数据 helper 不执行 Turn、resume 或模型任务；原历史 reader 的只读白名单保持不变。能力就绪和源码实现独立于逐设备真实验收，验收范围见本地文件 `docs/acceptance/workspace-management-20261017.md`。

## 显式配置的桌面展示候选（2026-10-18）

`GET /v1/threads/next-turn-suggestion` 默认走本节开头的原生 trace 路径；仅显式配置 `CODEX_DESKTOP_DOM_BRIDGE_CONFIG` 时选择只读 DOM 候选，返回 `source:desktop-display` 与 `suggestion.validity:desktop-display` 并声明 `desktop:suggestions:displayed:v1`。两条路径均保留完整机器目标与来源 Turn 契约。DOM 路径连续两次读取并核对来源、正文与身份；无展示返回空值，展示变化或绑定失效拒绝，不回退 trace。未配置 DOM Bridge 不影响已就绪原生路径声明 `desktop:suggestions:native-version:v1`；手机按实际能力选择来源要求。

DOM 候选使用六秒 `expiresAt` 与前台两秒重读，不能按 source-turn 无限缓存。其原 Codex 实读及实体手机展示路径仍未验收，独立 Chromium 与 HTTP fixture 只属辅助证据。已完成的本机正式部署、iPhone 16 build 77、正常桌面重开新 trace 及本次双端正文一致属于原生路径，详见[最新验收范围](research/desktop-suggestion-display-consistency.md#持久-trace-正式部署与正常重开验证2026-10-18)。严格显隐一致与整机注销登录时序仍未验收，也不承诺任意时刻两屏原子一致。

EasyWork 的差量接收契约按 `packages/protocol/fixtures/mobile/easywork-thread-sync-v1.json` 保存实际生产器向量。head 的 hash 是不透明版本；单文本补丁校验 UTF-16 长度和 UTF-8 SHA-256，合并失败不发布部分缓存，仅允许一次无 known 的完整读取。目标不符直接拒绝，关闭或换代的响应不能写回当前缓存，原输入不会随读取重试。Android KMP 与现有 Swift 消费者分别通过同一向量，原生层另核对会话／授权和网络边界；辅助通过不替代真实桌面、手机及模型验收。

UM04 客户端迁移补充：`codex-host-discovery-v1.schema.json` 新增具名 `privateClaim`、`privateReply`、`vpnSnapshot`，不改变根公开描述契约。claim 仅含版本、捕获的完整 target、公钥；响应的版本、原 target 元数据、公钥、临时公钥、nonce、ciphertext 均严格校验。设备身份使用 X25519；版本1上下文以 UTF-16 长度前缀固定包括原账号元数据，作为 HKDF-SHA256 info 和 AES-GCM AAD。双端使用相同向量，不重算旧上下文。公开来源最多4、每来源4地址、目录最多32台／每台2地址、每轮最多64次候选探测，并发最多4；只接受 Tailscale CGNAT／指定 IPv6 前缀，网络代次改变后旧结果作废。发现和鉴权相互独立；未知 claim 结果不自动重发，用户再次点选仍使用原设备公钥，由 Host 既有绑定幂等处理。源码实现、合成原生和真实网络结果分别记录，不据契约存在宣称已验收。

## 历史终态与消息文件资源（2026-10-23）

`GET /v1/attachments/resolve` 解析当前已登记资源的最新文件版本，返回准确目标和当前附件；稳定 resourceId 用于同资源交互身份，旧附件 ID、原消息和输入回执保持不可变。下载前重新检查来源与摘要，缺失资源返回 410，只有旧 Host 缺少接口的 404 允许旧版兼容。

2026-10-23 补充：最新历史页通过只读 `thread/turns/list` 的单轮 `notLoaded` 视图取得终态，仅在返回 Turn ID 与最新消息的 Run ID 一致时投影 `lastTurnStatus`；旧服务不支持时保持未知。新鲜历史已确认 completed／interrupted／failed，且没有当前输入、队列、待确认回执或审批时，iOS 不显示实时重试警告；执行控制仍为 false，运行中、未知或实际读取失败继续提示。历史读取期间到达的已验证原 owner 快照优先，不被慢历史结果降级覆盖。

历史分支中的本地 Markdown 文件链接与实时消息共用附件检查、内容摘要和链接替换路径，下载前继续检查最新字节。只读元数据中的工作目录仅用于解析相对链接，不扩大授权目录；旧 inline 附件不再替代本次检查，缺失或越权文件不能继承旧版本。该修复同时影响使用此 Host 的移动端，平台实测范围见 [专项记录](acceptance/mobile-evidence/status-preview-20261023.json)。

`GET /v1/attachments/resolve-link` 已实现，仅接受 hostId、threadId、messageId、reference 各一次。reference 必须匹配该原桌面助手消息的实际本地 Markdown 链接或此前登记的同消息来源证明；它不是任意文件读取参数。返回准确 Host／thread／message／原引用／readTarget 及当前版本附件。原消息资源可位于项目根外，仍绑定首次规范真实路径；用户消息、跨聊天资源、未引用文件与符号链接改指向均拒绝。`attachments:resolve-message-link` 只在相应桌面运行时声明。

附件失败响应保留 error，并增加稳定 code 与本次 requestId；例如 ENOENT/410、EACCES/403、NOT_REGULAR_FILE/422、FILE_TOO_LARGE/413、FILE_CHANGED/409。手机展示真实原因及诊断编号；旧 Host 未提供该接口时显示实际兼容错误。Host 私有有界诊断包含同一 requestId、路由（不含查询和凭据）、Host/thread/message/resource、阶段、原生错误码及堆栈位置。注册失败也记录，不再静默丢弃；日志不包含请求正文、认证头或文件内容。

## Codex 发现授权兼容（2026-10-24）

2026-10-24：回收 M5 e5bbb73 的 Host Key 修复。新版首次点选需 Key，正确授权后自动重连；兼容旧 automatic 的发现与连接。取消可切换，后台 Codex 401 不重复弹出阻塞提示；新版拒绝不降级。iOS／Android 源码同步，本次原生和逐机部署验收待记录，A6保持暂停。

### 附件元数据漂移与稳定快照

`attachments:resolve-current`、原消息解析和下载继续使用现有路径／来源授权与 SHA-256 契约，不添加新写操作。仅 ctime 改变导致的快照失配允许 Host 最多三次重新授权并完整读取，重试固定首次文件身份；同大小改写并恢复 mtime 仍须重新哈希，不得复用旧缓存或放行旧摘要。持续元数据漂移返回 HTTP 409、`FILE_METADATA_UNSTABLE` 和现有 requestId；它表示未取得稳定快照，不宣称文件内容已变化。实际摘要不符仍返回 `FILE_CHANGED`，目标替换、越权、删除及系统读取错误保持原拒绝。私有日志可核对 `fileChange` 的固定 stat 字段与重试阶段，公共错误不输出这些内部字段。

2026-10-26 阅读版本补充：`contentVersion`校验的是手机实际收到的最终正文，包含`codex-attachment://`链接改写后的内容。原桌面原文与传输正文不同时，Adapter在完整投影原子发布时更新最新版本，以内部持久原文摘要防止后续同一摘要将版本回退。协议字段不变；同ID新内容、旧版本冲突和按阅读者隔离保持。
