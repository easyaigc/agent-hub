# 应用协议与领域契约

2026-10-29 Android 通知恢复 v2 源码已接到可选前台 monitor：完整原意图先落盘、未知只查原 ID，独立关闭与显式缺口接受保持各自收据，待 Host 确认事件不推进游标。JVM 辅助与原生编译分列；共享 UI／迁移入口和真实 DEV 尚待集成负责人验收。iOS v1 与供应商承诺不变，见[Android 候选与交接](plans/android-alignment-20261022/parallel/p9-notifications/recovery-v2-android.md)。

## 可选通知恢复 v2 源码候选（2026-10-28）

新增封闭契约 `notification-recovery-v2`，独立于原通知 v1 与 Codex JSON-RPC。固定 `/v1/notifications/recovery-v2/capabilities`、`operations`（POST 提交／GET 原 ID 查询）、`feed`；现有鉴权与完整目标校验保持。v2 意图包含原 ID、device／target／真实公钥／完整偏好／订阅代次和规范 JSON 摘要，Host 先落盘再应用，旧 unknown 只查原记录。缺席设备撤销持久封住迟到旧注册；分页有稳定 epoch、连续序号、高水位和明确缺口，复位另有完整意图与缺口接受记录。v1/iOS wire 不变；Android v2 与真实场景仍待后续接线验收，无供应商投递承诺。详见[源码与复位边界](plans/android-alignment-20261022/parallel/p9-notifications/recovery-v2-server.md)。

## Host Key 首次授权

实现、辅助检查、真实桌面与实体手机结果见[首次授权验收](codex-host-key-authorization.md)。

2026-10-23：Codex 自动发现只提供候选电脑。首次点选必须输入这台电脑的 Host Key；Host 的 `/connection/claim` 只接受有效 Host Key，缺失／错误密钥及设备令牌均不能颁发新凭据。公开描述声明 `host-key`，旧 `automatic` 可读取但新版手机不走其免密授权。手机仅在加密设备凭据和同一完整工作区目标验证成功后保存连接，原 Host Key 不保存；已有设备凭据继续重连。二维码包含密钥，视为显式凭据交付；手动路径仍校验密钥。实现与真实验收分开记录，旧免密验收不证明本次策略。

状态：`0.1`。默认服务仅提供健康检查；显式启用本机历史模式时额外提供以下只读接口。回复与远程授权的领域类型仍是草案。

完整直接 Host 接口已单独实现，使用 [运行时 HTTP 协议](protocol-runtime.md)：真实回复、上传/下载、事件、停止、排队和审批。下文 Relay 领域对象仍是设计草案，不能混用为实际运行时端点。

## 协议分层

1. iOS ↔ Relay ↔ Host Agent：本产品的版本化应用协议。
2. Host Agent ↔ Codex：上游 App Server JSON-RPC，具体字段取决于安装版本。

手机不直接使用本机 Codex RPC，Relay 不解析其完整调用语义。Adapter 负责把领域命令转换为受支持的上游操作。应用协议版本与 Codex CLI 版本分别协商。

## 已实现：健康检查

`GET /healthz` 返回 HTTP 200：

```json
{
  "service": "host-agent",
  "status": "ok",
  "protocolVersion": "0.1",
  "capabilities": ["health"]
}
```

Relay 使用相同结构，`service` 为 `relay`。`status` 只描述 HTTP 进程存活；不能解释为 Codex、手机或另一台电脑已连接。默认服务的其他路径返回 HTTP 404。历史模式连接就绪时，capabilities 增加 `projects:read`、`threads:read`、`history:read`；读取进程退出后降为 `health`，读取接口返回 503。

契约源位于 `packages/protocol/src/index.ts`。Smoke 检查验证实际构建产物的启动、健康响应，以及尚未实现的对话路由不会返回成功。

## 已实现：本机历史接口

仅 `pnpm dev:history` 或 `CODEX_HISTORY_READ=1` 启用。监听固定限制为 `127.0.0.1`，拒绝带 Origin 的请求以及不匹配的 Host 头，不提供 CORS。该开发接口没有远程配对与授权，不可用于公网服务。

| 接口                                  | 返回                                                            | 约束                                                          |
| ------------------------------------- | --------------------------------------------------------------- | ------------------------------------------------------------- |
| `GET /v1/workspace`                   | hostId、name、connectionMode、projects、threads、两个下一页游标 | connectionMode 固定为 history-only，线程 state 固定为 unknown |
| `GET /v1/history?hostId=…&threadId=…` | hostId、threadId、messages、nextCursor                          | Host ID 必须一致，线程必须已经由此服务列出                    |

工作区参数 `projectCursor` 与 `threadCursor` 分别使用服务返回的不透明游标，每页最多 100 个。iOS 合并列表时按 ID 去重。历史参数 `cursor` 用于继续加载更早消息；分页线程每页读取 100 个上游 item，映射用户、助手及工具消息，按时间先后返回。旧式历史使用 includeTurns 读取，当前没有旧式历史的应用层分页。

工作区增加 `clientType: { id, name }`，当前实际实现为 `{ "id": "codex", "name": "Codex" }`。客户端类型与电脑名称、connectionMode 独立；未来类型可使用其他字符串 ID，不表示已接入其他客户端。旧 Host 缺少该字段时，iOS 按已有 Codex 接入兼容显示。

项目字段为 id、hostId、name、roots。线程字段为 id、hostId、projectId、title、state。消息字段为 id、role（user/assistant/tool）、text、files、kind、status、detail。历史和实时路径共用工具映射：命令输出、文件 Diff、工具结果和公开推理摘要保持原 item ID 与顺序，跳过空推理及独立计划条目。历史附件只显示通用标记，不传输原附件文件；工具正文可能包含上游原始命令与路径，但不会因此获得下载权限。当前历史接口不返回实时状态，历史工具不能作为运行时连接证明。

历史线程优先保留上游明确的 `projectId`。旧桌面会话的该字段可能为空；Adapter 将 `cwd` 与本 Host 已读取项目的 `roots` 做规范化后的目录精确匹配。唯一匹配时补充显示用的 `projectId`；共享目录有多个匹配时，`projectId` 继续为空，可选 `projectIds` 列出包含该目录的项目视图。iOS 在这些项目下展示同一个线程，点击时保留所选项目上下文与原 Host/Thread 身份。此映射不写回 Codex，不代表新增了官方项目归属。不会按名称或父目录前缀合并；不能匹配的聊天仍在最近列表显示。项目映射保留到当前分页遍历结束，保证后续线程页可匹配前面项目页。

`desktop-runtime` 另读取同一 Codex Home 的桌面侧栏元数据，优先采用本机明确归属与无项目标记，将桌面 legacy 项目 ID 映射为既有 App Server ID；已移除的明确项目不会改按目录串入其他项目。手机创建时保留的归属仅在桌面没有明确标记时兜底。`GET /v1/workspace` 在该模式返回完整 metadata 分页合成的当前快照，两个 next cursor 均为 null；项目顺序和聊天顺序来自本次目录读取，旧列表项不从持久化执行缓存补回。目录中尚无记录但已验证原 owner 的聊天可补入。metadata 文件损坏时刷新失败并保留手机上次缓存，不将该文件作为原运行时在线证据。

非 GET 请求返回 405；无效目标返回 400；本机访问校验失败返回 403；上游读取失败返回 502；读取器不可用返回 503。错误响应只返回固定错误码，不输出历史、路径或上游诊断。Adapter 仅允许 initialize、project/list、thread/list、thread/read、thread/items/list，不存在回复入口。

Swift DTO 使用 Decodable 校验必需字段，客户端额外校验工作区归属和历史目标。版本化 JSON Schema 与自动生成流水线仍待实现；当前只读 DTO 不表示回复草案已经接通。

## 草案：领域对象

| 类型              | 语义                                               |
| ----------------- | -------------------------------------------------- |
| `ConversationRef` | `hostId` + `threadId` 唯一定位目标会话             |
| `ProjectSummary`  | 电脑本地项目，路径不参与跨电脑全局身份判断         |
| `ThreadSummary`   | 会话摘要；不能确定实时状态时用 `unknown`           |
| `Page<T>`         | 列表与不透明 `nextCursor`，不能在客户端构造游标    |
| `AttachmentRef`   | 逻辑附件标识、归属、名称、类型、长度与摘要         |
| `ReplyRequest`    | 唯一消息 ID、目标会话、文字、附件与发送模式        |
| `DeliveryState`   | 发送与执行状态，包含无法确认提交结果的 `uncertain` |

这些类型暂时是 TypeScript 草案，不是完成了运行时输入验证的 Schema。接入网络输入前必须增加版本化 Schema、字段限制与跨语言验证。iOS Swift 模型生成或一致性验证尚未实现。

## 草案：回复状态

```mermaid
stateDiagram-v2
    [*] --> pending
    pending --> receivedByHost
    receivedByHost --> submittedToCodex
    submittedToCodex --> completed
    pending --> failed
    receivedByHost --> failed
    submittedToCodex --> failed
    receivedByHost --> uncertain
    submittedToCodex --> uncertain
    uncertain --> completed: 查询确认已完成
    uncertain --> submittedToCodex: 查询确认已提交
    uncertain --> failed: 确认未执行或无法恢复
```

图中为概念状态，TypeScript 使用 `received-by-host` 和 `submitted-to-codex` 等字符串。不能把中继收到消息作为电脑已收到或 Codex 已开始执行的证据。

`steer` 必须带 `expectedTurnId`；只有当前 Turn 与前置条件一致时才能执行。`queue` 和 `steer` 根据目标电脑能力开放，不能假定所有版本都支持。

## 草案：事件恢复与错误

- Host Agent 为输出事件持久化本机递增序号。每台 Host 独立管理恢复位置，多个订阅者收到同一事件标识。
- 重连按客户端确认位置补发，重复事件由客户端去重；日志已过期时重新拉取快照。
- 拟区分离线、未配对、版本不支持、附件未完整上传、Turn 前置条件失败、已失效审批、限流与结果不确定。
- 机器身份及附件归属不能仅依靠手机传入字段，应由配对授权和 Host 本地映射校验。
- 审批 ID 的有效范围包含所属运行时连接；恢复连接后先查询是否仍待处理，不盲目复用旧请求 ID。

具体 WSS 消息信封、密文格式、错误码和 JSON Schema 在跨电脑 PoC 实现时定稿；本次不生成虚构的远程端点。

## 聊天管理与完整历史搜索

新增严格具名 `POST /v1/threads/actions`、同路径 GET 回执查询，以及 `GET /v1/threads/search-history` 分页只读历史。管理动作固定完整目标、Thread 和稳定操作 ID；上游 wire 只在 Adapter，未知结果不重放。字段、阶段及边界见 [聊天管理与搜索](chat-actions.md#协议)。

工作区及历史中的聊天摘要可带 `lastTurnStatus`（`inProgress`、`completed`、`interrupted`、`failed`），只由原 owner 最新有效轮次投影；旧 Host 可缺省。它用于在 `state:idle` 时区分空闲、已完成及已停止，不改变原 `state`、输入条件或归档状态。`state:unknown` 不因持久化的旧轮次结果被显示为空闲或已停止，单条工具消息的完成也不作为任务完成证据。

已加载原桌面聊天的归档／恢复／删除当前受官方 active writer 拒绝及缺少具名释放入口阻塞，2026-10-20 起界面允许提交并显示官方实际拒绝，原 owner 执行入口仍未完成。未加载元数据的辅助通过不代表四项全功能已交付；见 [聊天管理与搜索](chat-actions.md)。

## UM03 分支严格HTTP契约（已实现并通过适用验收）

共同Schema新增 threadForkRequest、threadForkOptions、threadForkOperation。请求仅hostId、threadId、turnId、clientOperationId、environment；完整本地目标不额外写入严格POST正文。具名GET选项和原ID状态不产生输入，confirmed必须返回不同的准确新Thread ID，所有原请求字段均绑定；终态不得回退或改写。Android和iOS使用同一Schema及KMP判定，编译／向量与实际原owner和Git验收分别记录。

### 2026-10-23 原生聊天管理严格契约

移动 HTTP Schema 新增 `chatActionRequest` / `chatActionReceipt`，只允许 `pin`、`unpin`、`archive`、`unarchive`、`delete`；严格校验字段、操作 ID、Host/线程与完整机器目标。具名 POST 和同一路径 GET 查询接入 Android，原 Host 接口实现不因 Schema 扩充而新增能力。终态不能被晚到的提交中/未知回执降级，已保存的目标、正文与操作 ID 不随查询重写。查询404表示未找到电脑端原记录，不能据此自动重放或推断原动作已拒绝。新增10个 HTTP 向量与17个任务状态、7个管理可用性向量，既有向量保留；完整真实DEV结果单列。

P3-5b 已实现：workspaceManagementRequest/Operation 严格有序ID、1..100去重和逐项pending/submitting/completed/rejected/outcome-unknown契约；Android命令只接受threads:archive，目标/请求/项顺序匹配后共享规则保留原终态和错误。未知结果与404保留原ID，GET不执行；POST前持久标记，跨通道不新增意图。不新增任意方法或项目删除功能。

2026-10-24 P4-4e.1：修复共享Host在任务结束后回答异步问题时回退电脑默认权限。新回答从准确原owner的原问题Turn参数取得已确认权限预设，固定到原回答的不可变意图；无法确认时409且不保存/发送新输入。旧版无固定权限且无回执的原意图不补写、不执行；已有已接受/未知回执继续按原ID核对。运行中追加和跳过保持原路径，普通跟随电脑默认的回复规则不改。

原真实反例readOnly→dangerFullAccess和合成IPC反例:read-only→:workspace分别保留；当前完整IPC边界分组通过，另核对未知预设零写入、旧未提交意图不改写/不执行。全新真机提问与回答共2条输入，专用Host重启保持身份/令牌/原问题；原生整组1项通过，原owner两Turn均completed且readOnly/on-request/user、默认模型/极高/快速相同，无工具/文件变更，规范历史恰好2条。原CLI因1次后台workspace fetch failed整体失败，该读取失败不改记通过，也未推断根因。当前只读恢复CLI和原生1项通过，业务POST与新增输入0；产品与Host源码相同，原实际测试方法/助手逐字节相同，runner仅只读恢复标识选择改变，未宣称全套在最终runner重跑。

本项新增2条，P4累计20、总33条已消费输入均不重放。只关闭本次请求批准权限回退缺陷；前项命令终态、原CLI读取失败、完整异步/审批/跨端抢答/其他权限预设及复杂配置和F34/F35/F41/F42/F46/F58仍开放。iOS共用修复后的Host规则，未新增iOS原生真实验收或更新正式服务；Windows未验收。6张原App/dialog独有窗口图已查看，仅作局部状态证据，完整视觉P6继续。继续队列/追加/停止/恢复；蜂窝/VPN与人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

升级区分历史免密设备凭据与已通过 Host Key 授权的凭据：旧免密绑定缺少 `hostKeyAuthorized` 时不再通过鉴权，用户可在原连接失败后点“使用 Host Key 重新授权”，输入一次后沿用原设备密钥与凭据，保存连接和草稿不清空。原 Host Key 手动／二维码连接及新版已授权设备照常重连。

P5-2移动建议读取使用`codex-mobile-http-v1.schema.json`中的`desktopSuggestionResponse`与可选`runtime.suggestionSourceTurnId`。Codex的具名GET继续绑定机器/聊天/来源轮次及既有鉴权/证书；EasyWork的`thread/suggestion/read`在已鉴权加密会话中映射同一响应投影，保持其账号约束。Android读取预算为八秒，接收体有界，超时可恢复，原消费者取消保持取消。响应匹配不授予命令权限或自动采用；Android会话读取器与界面仍在P5-3接通。
