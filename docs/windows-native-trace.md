# Windows 原生 Trace 与下一轮建议

Windows 原生 x64、同用户提权环境已验证持久环境配置、原 Codex 与直属执行器正常重开后的环境继承，以及当前实例自然生成的 Trace。两条真实来源的四 ID、原文摘要、原 owner、六秒租期续读和串轮次拒绝已有历史通过证明；不得将其当作后续代码与部署的本次验收。

当前候选尚未切换正式 Host。上一版完整 Windows 原生源码回归为 236 pass、0 fail、2 个 macOS 专用 skip；其后普通 DEV 的首个建议读取仍为 503，耗时约 3058ms，其余矩阵立即停止。独立的单次计时读取返回 200、约 4328ms，两次来源扫描分别约 2119ms、1370ms；它属于带计时运行，不能替代普通读取失败或证明唯一根因。失败与成功证据分别保留。

最新源码将 Windows 的一次建议请求分为不公开正文的准备和最终确认，中间仍由 Host 重新核对原 owner／来源。准备只解析一次，确认重新核对完整输入收据及原生文件守卫；不跨 HTTP 缓存结果。Windows 准备和最终确认各限四秒，两阶段及中间等待共用七秒绝对截止时间，整个 HTTP 从请求开始限八秒。路径算法、单原生命令 750ms 和最终校验后的六秒租期保持。新源码检查、当前 Windows 原生回归、普通读取、iPhone 15 DEV 与正式既有连接验收分别绑定实际新指纹，未完成项保持待验收。非提权、ARM64／32 位和真实注销后恢复仍未专项验证。

以下各阶段保留历史实现与失败背景；其中“待验收”按该阶段记录理解，最新交付结论以同一产物的正式验收记录为准。

## Windows 进程创建与退出边界（2026-10-19）

新增常驻权限实现的首次 Windows 检查保留为失败：25 个辅助用例通过，真实 C# CLI／serve 比较在首个 CLI 超时停止。CLI 已返回成功标记并以 0 退出，但输出管道仍未结束。16 组独立对照中，原夹具立即创建 Worker 后的管道方式复现三次超时；先确认服务就绪或改用普通文件输出可以完成。异步时间线记录到进程退出与最终管道关闭相差约 4.94 秒，正常关闭该次 backend 后管道才结束。这证明并发启动下的管道生命周期问题，没有直接枚举 Windows 句柄，不能把超时改判为 ACL 拒绝或成功。

统一源码增加 `windows-process.ts` 的进程创建协调：Main 持有共享缓冲并注入实际 reader／native Worker，Host／Adapter 同一进程内可并发的 Windows 子进程创建入口使用同一临界区。同步创建保持至调用返回，异步创建在构造返回时释放，不等待常驻进程结束；等待消耗原预算，不延长原进程超时。持锁 Worker 仅在确证退出后按原 token 清锁，结果未知不重放。两个 native transport 在直接 child 已退出后显式关闭三路 stdio，分别验证 child、管道与 Worker 退场，不能只凭 PID 消失宣称完整回收。

上述修复处于本次源码回归与 Windows 实机复验阶段；原失败、对照观察和旧成功记录分别保留。CLI／serve 等价用例保留“立即创建后调用”的原顺序，不以预热、等待 ready、普通文件输出或放宽超时替代正式回归。通过后仍需同产物真实读取、性能与实体手机验收，才能更新正式部署结果。

## 复用与适配

复用 `DesktopPredictionTraceReader` 的 schema v1 解析、四个原生 ID、fork/cwd、app-generated 标记、请求顺序、完整成功响应和 task_complete 正文核对。Windows 观察器直接按准确来源查询原生记录，不伪造完成通知，不声明通知观察能力，也不声称桌面输入框实际可见。所有模型输入与采用/发送入口保持独立。

`desktop-platform` 负责 Windows 窄接口。原具名管道验证官方签名、同用户与会话；新增 PID 和进程启动时间核对，防 PID 复用。只读原主进程及其直属 app-server 的一个环境变量，不返回完整环境。该辅助探针目前支持原生 x64 Windows；无法查询、布局不符或权限不足均关闭读取，不申请绕过权限。ARM64/32 位未验证。

`trace-state.cs` 使用 CreateFile 的 OPEN_REPARSE_POINT/BACKUP_SEMANTICS，保持整个祖先链的无 share-delete 句柄，GetFinalPathNameByHandle 验证实际路径，按句柄取 owner/DACL、volume/file ID/创建时间及链接数。私有根目录必须属于本用户 SID；每个对象的 DACL 必须明确授予该用户读取，允许的主体只有本用户、SYSTEM、Administrators。内部对象允许保留上述受信主体的原生 owner，支持提权 token 默认以 Administrators 创建文件；根目录不能降为组所有，非受信 SID owner 或额外访问主体仍拒绝。未知/回调 ACE、重解析点、硬链接、UNC、ADS 和命名空间别名均拒绝。祖先保留 Windows 系统原有访问规则，通过每次打开保留的句柄及全链身份/ACL 指纹验证命名空间。每个逻辑快照交叉核对所有已见组件和 Node stat 的实际 file ID，读取前后及最终均复核。文件内容预算、追加前缀及失败关闭语义沿用公共解析器。

建议只在原主进程与所有准确直属执行器都指向同一私有根目录时开放。当前实例身份（PID/启动时间/执行器集合/路径）读取前后不一致则返回空；实例切换在有界重试后重新建立范围。排除本实例启动前的旧 bundle，按 native inference 请求开始顺序取唯一最新版本；最新失败、未完成、空内容或歧义不回退旧结果。原 owner 和来源轮次仍由现有 runtime-session 前后核对。响应保留 native-trace/native-version 与六秒租期；手机仍需前台只读刷新。

## Windows 异步读取与预算

正式 Host 的 Windows 观察器使用专用读取 Worker，主线程能力查询只读取有界心跳状态。Windows 建议路由依次执行当前 owner／来源核对、Worker 准备、中间 owner／来源核对、同一 Worker 最终确认、最后 owner／来源核对。准备结果只返回请求内的随机标记和就绪元数据，正文留在 Worker；最终确认成功前不向 Host 交付建议。macOS 保留原两次新鲜投影及版本比较。

准备阶段记录所有实际读取输入的收据，包括目录过滤与排序结果、正负发现前缀、完整正文摘要和长度、配置 cwd 的规范路径及授权根归属。确认阶段重新读取并比较这些输入，原生 Snapshot 仍检查完整祖先、DACL、身份、读取前后同文件和内容；原 Node 三处新鲜路径核对也保留。较大正文在派发前选择原 single，小项才使用最多两项的既有 batch；已派发错误不 fallback。确认前后重查发现目录和 cwd，防止中间追加的新配置、更新的预测或目录别名被旧解析结果忽略。变化时关闭本次读取，不重新发现或重放。

Windows 准备阶段的来源发现也按最多两个候选分层读取：先校验 manifest，再读取未被时间过滤排除的发现前缀，最后读取已验证引用的配置 payload。符合既有单项 16KiB 与帧大小约束时使用两项 batch；大项、缓存命中和剩余单项在派发前选择原 single。每项仍执行独立原生 Snapshot、完整链及读取前后检查，整批完成新鲜状态复查后才交付结果，已派发失败不转 single。三处即时规范路径检查、64MiB 活跃预算、请求内收据及最终来源核对保持。新增分层与错误边界的本地辅助回归已通过，真实 Windows 延迟、普通读取和手机结果待本候选复测，不能用旧版本通过替代。

读取线程启动时通过既有签名/Store 校验确认原管道 owner；常驻 C# 辅助进程保留准确原进程句柄，每次探针核对原管道 PID、进程启动时间/可执行路径、同用户/会话及准确直属执行器。PID 消失或实例切换即失败并重新认证，不因历史缓存继续可用。辅助进程仅接受固定的快照、原实例探针、token 元数据和关闭操作；路径锁定为启动时的私有 Trace 根，没有任意 shell、RPC 或环境读取入口。所有文件与链守卫仍每次读取重新执行，进程复用不等于跨请求绑定缓存。

每个观察器最多一个逻辑读取在途，无待执行队列；从准备开始到最后 Host 来源核对结束都保留占位，并发建议请求快速返回 503。Windows 准备与确认各限 4000ms，两阶段、消息传递及中间来源等待共享 7000ms 的单调绝对截止时间；Host 在收到请求时另建立 8000ms 总截止时间，覆盖前、中、后三次来源核对。直接读取兼容接口仍限 3250ms。各边界均检查余量，迟到或失配结果不能恢复能力；同步文件系统调用不能被抢占，仍须实测实际延迟。单原生响应 750ms、48MiB 帧及 2500ms 后台退役机制保持。

Worker 将准备状态绑定到准确代次、请求、机器目标、Thread、来源 Turn 和一次性标记。确认只消费一次；中止、关闭、超时、通道退出及身份变化会释放正文和收据，旧回包不能复活。正常周期检查若取得相同实例及起点，不应使有效准备误失效；实际实例、根、权限或来源变化仍拒绝。不会向 Main 增加任意反向 RPC，也不保存跨请求建议绑定。

能力提示使用最多 5000ms 的真实就绪样本，携带采样时的同进程单调时间；消息到达不重新延长旧样本。提示不能替代每阶段和末尾的实际进程、根、权限、文件及来源检查。六秒建议租期只在最终文件与实例确认完成后生成，最后一次 Host 来源核对还会检查剩余租期，不能重置有效期。前台刷新在请求完成后继续等待约两秒，过期时撤下旧建议。

来源发现与候选正文仍受 64MiB 活跃缓存预算约束。准备完成释放正文缓存，只保留本请求必要的摘要、收据和已选结果；缓存命中不重复计费。有效空结果与读取失败保持不同语义。所有失败和历史旧结果分别保存，定向夹具只作源码辅助证据，不能替代普通 Windows 和实体手机验收。

真实 Windows 预检也发现，普通 health/workspace 每次等待语音登录元数据会增加数秒。Host 现将展示用的语音能力布尔状态放到后台刷新：十秒后触发新探针，超过三十秒未取得新结果即撤销提示，同一时刻只运行一个探针；不缓存令牌或 principal。实际语音就绪、录音准备与转写仍走原有实时身份校验，普通读取不等待这项检查。关闭后迟到探针不能重新开放能力。此状态提示与建议的六秒租期分别管理。

失败负载的分段测量进一步确认，主线程重复同步启动私有状态 helper 与原子写入、health 排队长尾重合。源码现为每个 isolate 复用固定本机 helper，所有调用仍逐次重新保护／核对 owner、DACL、对象类型和重解析点，CLI 与常驻入口共用实现。启动、操作及关闭分别限时，响应严格关联请求 ID；超时、退出或畸形帧撤销通道，不重放可能已执行的当前操作，确认旧子进程退出后才允许后续独立调用新建。同步账本、原子发布与既有 Trace 预算／六秒租期保持，实际成功率和延迟改善仍待新版 Windows 复验。

Main 统一持有权限与 Trace 传输 Worker，读取方通过 owner MessagePort 使用各自固定通道；所属读取线程被强退时，传输线程仍可接收关闭并回收自己的子进程。Trace 的关闭处理在等待原生 ready 前注册，关闭会中止本次启动且迟到 ready 不能重新开放能力。broker 不接受可执行入口、CLI 参数或环境透传；DEV 计时工具仅在本机显式预加载时为准确的 Trace Worker 补回自身探针，不影响权限 helper 或生产启动策略。Mac 的六场景合成生命周期结果为辅助证据，Windows 原生进程回收须独立核对。

## Windows 会话 ID 查询候选（已实现，2026-10-19）

`trace-process.cs` 的原进程／自身及直属执行器热路径会话比较，已由 .NET Framework `Process.SessionId` 改为逐次调用 Win32 `ProcessIdToSessionId`；API 失败仍抛出异常并关闭本次读取。微软的 [.NET 参考实现](https://github.com/Microsoft/referencesource/blob/main/System/services/monitoring/system/diagnosticts/Process.cs)通过 `HaveNtProcessInfo` 取得进程信息后读取该字段；固定 PID 的 [ProcessIdToSessionId API](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-processidtosessionid)直接查询会话，返回失败时不能使用结果。本候选只减少会话字段的查询开销，不增加跨请求缓存或提权。

固定八轮 API 对照中，原进程、自身和直属执行器合计 **24/24 次 session 值相同**；本次微测报告的耗时如下，单位为毫秒，不是完整建议 HTTP 的端到端耗时或成功率：

| 对象                | .NET Framework SessionId | Win32 ProcessIdToSessionId |
| ------------------- | -----------------------: | -------------------------: |
| 原进程（orig）      |                  20.6118 |                     0.0105 |
| 自身（self）        |                 21.74975 |                    0.00025 |
| 直属执行器（child） |                  17.4314 |                    0.00285 |

另测 `MainModule` 为 0.3936ms，其读取实现与 Token 逻辑均未改；同文件 Token 元数据中的 `SessionId` 读取也保持原路径。原管道 PID、保留进程句柄、启动时间／可执行路径、同用户／会话、准确父子关系、Trace 根及 DACL 的所有守卫保持，原生 750ms、数据读取 1800ms、RPC 2000ms 和六秒租期预算不变。

当前仍等待 `fieldProfile375`，1.8 秒读取临界问题尚未闭环；固定 API 对照不能证明 SessionId 是原现场超时、空建议或 503 的唯一原因，也不承诺全部读取低于两秒。**新 C# 候选的真实 Windows native／DEV／实体手机尚未重跑，未发布**；旧原生回归、旧现场读取与其他手机结果不能替代本候选的复验。

## Windows 最终校验批处理候选（已实现，2026-10-19）

Windows 原生 backend 的最终 `Snapshot.verify()` 已实现固定内部批处理，每 batch 最多两项。每项仍单独调用原 C# `Snapshot`，独立打开并核对完整祖先链、owner／DACL、对象身份及内容；重复路径也分别执行，不共享祖先句柄或校验结果，不新增跨请求缓存。只合并固定命令交付，不将两个原生快照合成一次权限校验，macOS 原路径保持。

原 Node 校验仍逐项保留：派发前核对节点，回包后重新取新鲜节点状态、核对 native 身份／链及原前缀，再作节点复查。前缀对应文件在另一项执行期间变化时，通过匹配的打开句柄重新核对旧前缀，不能用元数据或其他项的结果代替；任何 native 或内容守卫失败仍拒绝。本批全部项成功且完整响应通过 schema、请求 ID、index／顺序、身份链和正文长度校验后才接受结果，不发布部分成功。

批处理请求为严格 UTF-8／Base64 的固定字段格式，解码 JSON 请求上限 48KiB、响应上限 256KiB，每项 `maximum` 上限 16KiB。派发前按项数、原前缀读取长度、路径／请求大小和保守响应估算规划；不适合 batch 的大项及剩余单项直接走原 single 路径。**这是派发前选择，不是 batch 错误后的 fallback**；已派发的失败、超时或畸形回包保持失败关闭，不改用 single 或重放未知操作。

整 batch 共用原有 750ms 原生响应上限，不按项重置预算；reader 1800ms、RPC 2000ms、就绪状态 TTL 2500ms 和建议六秒 lease 均不变。原 owner／来源、最新版本／歧义、Node 前后及回包新鲜复查保持，不改 HTTP 契约、权限或安装策略，不新增提权和任意操作入口。

证据分开记录：前一份 `3e98fe4` 源码在目标 Windows 的原生辅助回归已有 164 项通过，随后首个普通 A 建议读取仍返回 503，该失败及原日志保留；不能由原生辅助检查推定普通读取成功。新源码的本机 focused 统计为 220 pass、0 fail、20 platform skip，仅属辅助证据，跳过项不能算通过。**新 batch 的真实 Windows／普通读取／DEV／实体 phone 尚未验收、未发布**；没有据此证明旧 503 已修复、原现场唯一原因或全部读取低于两秒。Android 建议仍保持 planned。

## 持久环境工具

普通安装使用统一接入包，无需准备源码开发环境或 XCTest。开发者只在隔离源码副本构建统一产物，再使用原 Node 24 执行以下工具；不要在正式已运行程序目录重装依赖或构建：

```powershell
& '<原 Node 24 路径>' scripts/windows-suggestion-trace-environment.mjs install
& '<原 Node 24 路径>' scripts/windows-suggestion-trace-environment.mjs status
```

仅在用户明确要求关闭或回退持久 Trace 时执行：

```powershell
& '<原 Node 24 路径>' scripts/windows-suggestion-trace-environment.mjs disable
```

默认状态目录位于当前 Windows 用户原生 USERPROFILE 下的 `.config/agent-hub/desktop-trace`；完整模型请求和返回只写本机私有 traces 子目录。此位置避免 Store/MSIX 对 LOCALAPPDATA 的包缓存重定向；不接受路径别名来绕过原生守卫，也不修改系统目录权限。工具只管理 HKCU Environment 的 CODEX_ROLLOUT_TRACE_ROOT，保存原值/类型，广播 Environment 变更，不改其他登录环境、不退出/启动/重启 Codex、不改正式 Host。遇到已有未归属设置或用户后续修改则保留并拒绝覆盖。disable 恢复自己管理的原设置，保留历史文件；已经运行的应用仍保留启动时环境，正常退出后才会停止该实例的后续记录。

正常接入默认持久配置并简要告知用途与本机范围；已有本机授权直接继续，不重复确认。用户明确关闭则保留选择。注册表配置成功不代表当前应用生效：Explorer 的 WM_SETTINGCHANGE 继承、普通图标重开和重新登录分别验证。用户自行选择正常重开时机；若启动器仍带旧环境，需从已刷新环境的原图标启动或在合适时机重新登录，不能自动结束 Explorer/桌面或打断任务。持久变量也可能被同用户后续 Codex CLI 继承。

主仓库统一部署时，将原 Host 配置中的 CODEX_DESKTOP_SUGGESTION_OBSERVER=1 和 CODEX_DESKTOP_SUGGESTION_TRACE_ROOT 设为工具返回目录，按原服务流程切换；当前验收阶段尚未切换正式服务。环境注册、当前进程继承、新原生记录、Host/HTTP、桌面实际展示、手机展示/采用/撤销分别记证，缺项不能写成全通过。

## 验证

`pnpm check:windows-native` 是 Windows 原桌面相关边界的开发检查，先确认当前为原生 Windows，再按以下阶段依次执行：

1. `format:check`、`lint`、`typecheck`、`build`，以及只读 runtime closure checker 合成测试 `verify-host-runtime.test.mjs`、计时工具离线测试 `windows-suggestion-timing-import.test.mjs`、常驻权限通道故障与同源码 CLI／serve 等价测试 `windows-private-state-native.test.mjs`、慢启动关闭及强退回收合成测试 `desktop-trace-lifecycle.test.mjs`。
2. `windows-private-state-acceptance.mjs` 的真实本机 DACL、公开读取拒绝、重新保护、24 次原子写入与长路径检查。
3. `history-smoke.mjs`、`runtime-persistence-smoke.mjs`、`desktop-framing-smoke.mjs`。
4. `windows-control-smoke.mjs`、`desktop-ipc-smoke.mjs`、`desktop-stream-generation.test.mjs` 与 `desktop-live-window.test.mjs`、`large-conversation-sync-smoke.mjs`。
5. `desktop-suggestion-observer.test.mjs`。
6. `voice-transcription-smoke.mjs`。
7. `windows-prediction-trace.test.mjs`、`windows-trace-worker.test.mjs` 与 `windows-prepared-trace.test.mjs`。

所有阶段通过 `&&` 串行连接，任一失败立即停止，不重试或忽略退出码；所有 `node --test` 使用 `--test-concurrency=1`，避免原生重负载测试与语音测试并发导致资源争用。测试本身的平台专用 skip 按实际结果单列，例如 Windows 不执行 macOS 通知用例，不能将跳过项写成通过。closure checker 仅解析 ESM 路径并检查合成文件指纹，不执行 Host 模块，也不代表当前安装包的实际 closure 已验收。新增命令尚需目标 Windows 实跑，不能以静态审查推定通过。

Windows 本次完整 `pnpm check` 已停在 `runtime-smoke.mjs` 的 managed-runtime Unix shebang fixture 启动处；该阻塞不是原桌面链路的验收结果，也不能报告完整检查通过。原 `pnpm check` 保留全部检查和失败条件；专项命令按上述固定范围组织检查，不按运行结果选择跳过失败项，不能替代全仓检查。

开发检查仅在隔离源码副本的仓库根目录执行，使用 Node 24 与 pnpm 11；不要在正式已运行的 Host／接入包目录执行 `pnpm install` 或 `pnpm build`：

```powershell
pnpm install --frozen-lockfile
pnpm check:windows-native
```

合成性能探针在上述检查结束后单独运行，不与语音或原生测试并行：

```powershell
& '<原 Node 24 路径>' scripts/windows-trace-performance.mjs after . '<本机私有合成验收目录>'
```

这些开发检查不替代真实原进程身份、当前实例自然 Trace、真实 HTTP 建议及实体手机展示／采用／撤销验收，各项按环境和产物指纹另记。普通用户安装统一产物无需执行开发检查或 XCTest；实体手机 DEV 验收所需的系统 UI Automation 设置属于独立验收流程。

隔离 DEV 的分段计时可显式设置 `AGENT_HUB_TRACE_TIMING_DEV=1`，以 Node `--import` 加载 `scripts/diagnostics/windows-suggestion-timing-import.mjs` 的 `file:` URL；不要写入系统 `NODE_OPTIONS` 或正式服务配置。工具只包装现有固定方法，不发起模型任务或服务，单 isolate 最长记录 90 秒、最多 20,000 条；到期在该源码副本 `.local/windows-suggestion-timing/` 写入私有结果，移除预加载后正常恢复 DEV。其三角色分别是 main、suggestion-reader、native-channel；`complete` 仅表示当前 isolate 未丢记录且无未完成 span。须按 PID、Worker 线程号和单调时钟核对所有实际参与的实例，缺失或被替换的 Worker 不能按零耗时处理。父子区间、异步等待和事件循环阻塞分开统计，失败请求及诊断本身的开销单列，不能用失败负载的耗时宣称成功读取性能通过。

Windows 边界检查使用合成数据和本机真实 NTFS/DACL/句柄，覆盖公开读 ACL、硬链接、junction、ADS、对象替换、直属执行器路径失配和持久配置归属。公共 Trace 解析测试中的 POSIX chmod/uid 和 fs.readSync 注入场景仍需在 macOS 跑，不能用 Windows 结果替代。原生文件格式依据本机 0.163.0-alpha.10.2 执行器与同版本官方源码；二进制含变量、上游无平台排除不是原桌面新 Trace 的实测证明。

性能 fixture 只模拟当前实例就绪与来源 owner，完整 Trace 解析、文件/权限和真实 Host 路由都实际执行；并发 health/workspace/events 用另一个 Node 进程测量，避免把同线程计时误当主循环无阻塞。真实未开启原进程探针、冷启动、常驻探针、默认 token owner 和未经 SetOwner 的新建文件单独记录。当前实测原 Codex、执行器和测试 Node 都为同用户提权 token；非提权模式仍待专项验证。真实新 Trace、手机和生产吞吐不能由这些合成结果替代。

参考：

- https://github.com/openai/codex/blob/rust-v0.163.0-alpha.10.2/codex-rs/rollout-trace/src/thread.rs
- https://github.com/openai/codex/blob/rust-v0.163.0-alpha.10.2/codex-rs/rollout-trace/src/writer.rs
- https://github.com/openai/codex/blob/rust-v0.163.0-alpha.10.2/codex-rs/rollout-trace/src/bundle.rs
