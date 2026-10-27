# P5 桌面建议过程档案

当前范围、优先级与进度见[总表](../progress.md)。本档案保留实现、辅助检查、真实验收、失败和待验的区别。

## P5-1 共享规则与 iOS 生产迁移

逐节点保留 [56 个冻结来源与 11 个建议相关增量](../P5-source-review.json)，按八组最终语义继续核对。来源调查、Windows 性能或历史 iOS 证据均不充当当前 Android 通过；任务和项目业务仍暂缓。

`DesktopSuggestionRules` 统一来源/模式配对、机器目标、正文摘要、版本/租期、原草稿版本、填入/追加/替换以及精确撤销。iOS 实际生产类型已调用该核心，删除对应 Swift 重复判断；平台仅保留 SHA-256、ISO 时间解析和 DTO 映射。有限租期续签不改版本，采用仍须使用当前有效租期；已采用草稿的精确撤销不依赖建议继续存在。界面和传输不增加发送动作。

版本化同组向量由 JVM 和编译后的 Swift 生产路径执行。最初类型/语法编译错误保留在私有档案；修正后共享规则、既有读取/预读/来源三模式检查及 iOS 构建分别记录。上述属于辅助证据，Android 的读取、灰字/全文、采用/忽略/撤销、生命周期和真实原 owner 验收仍开放，未把源码存在写为整功能通过。

结构化证据：[P5-1](../../../acceptance/mobile-evidence/android-alignment-P5.json#sharedPolicyMigrationCheckpoint)。每个后续检查点先提交、更新总表，再继续；不逐次等待确认。

## P5-2 Typed original-source read adapters

Added typed read-only AgentReads/Registry dispatch, Codex fixed GET with existing token/TLS/full source target and bounded512KiB read, and EasyWork named encrypted thread/suggestion/read with exact channel runtime/account validation. Shared response/runtime-source contract is consumed by Android and iOS; native SHA/UTC adapters call common source/version/validity rules. Codex account metadata is omitted from machine query and may differ in response without changing machine identity. Eight-second read deadline fences late delivery; child timeout remains recoverable, owning cancellation remains cancellation. No new command, prediction request, upload or automatic adoption.

Initial compilation nullable-target mismatch and unsupported nullable schema spelling were retained and fixed using existing repository oneOf/const-null convention. Failed JVM/native groups remain failed; negative-only cases from those groups are not promoted. Current phone/API26/API37 each5 native methods passed, current15GET/0POST with15 default metadata proofs. Native positive response used current device clock, unchanged synthetic6-second lease and exact UTF-8/hash.15 new HTTP vectors (109total) exercised common JVM/iOS Simulator and Android; collector now includes new suggestion policy suites. iOS build passed. These are auxiliary read/codec/deadline results, no actual source owner or native UI claim; source reader/UI and real original-owner evidence continue without confirmation stop. No new real inputs; total215/P4201. Business task/project management and unrelated iOS A6 remain paused.

[Evidence](../../../acceptance/mobile-evidence/android-alignment-P5.json#sourceReadAdaptersCheckpoint).

Final P5-2 reconciliation: registered the missing EasyWork read-only method and verified actual encrypted peer recovery/identity/source through the production registry. Current phone/API26/API37 each6 methods passed,18total;15GET plus9encrypted read frames,0command/status or writes,18default-state proofs. Earlier bounded5-method groups and schema failures remain preserved. Native attachment download smoke fixture gained its unrelated type declaration, and final full pnpm check passed after the initial failure. Reader/UI/real original-owner acceptance still open; total215/P4201 unchanged.

## P5-3a Native reader and shared read-ahead

Reader and read-ahead implement single-flight consumer ownership, one fallback only for failed prefetch, independent cancellation, scope/navigation fences, current lease renewal and exact expiry. Transient reads clear and recover; invalid source/hash ends the current context; overlays pause without extending a lease. Kotlin failed Deferred cancellation semantics and same-version Compose state equality were handled explicitly. Empty clear is idempotent to avoid a recomposition loop.

Phone/API26/API37 each9 native methods passed with27 default metadata proofs,0 real inputs/commands. These are coroutine/state auxiliary checks, no rendered UI or original-owner claim. Original source and Compose integration remain open and continue immediately. Evidence: [P5-3a](../../../acceptance/mobile-evidence/android-alignment-P5.json#nativeReaderCheckpoint).

## P5-3b 会话、输入框与本地草稿

已接通会话持有的读取器/预读、真实历史确认、短暂复核保留与采用资格分离。灰字不进入可编辑正文；全文可选，采用/追加/替换须原目标、来源、当前租期及文本版本仍一致。忽略按共同内容版本登记，同ID新正文可重新展示；iOS生产路径同步使用共同键，并限制旧详情窗口回调。撤销只恢复准确未改动的本地文本，不依赖来源继续存在。文本编辑版本独立于文件/设置版本；自动发送清空不会复活旧提示。

新回调边界限制原导航与文本版本，允许同一输入流连续编辑，拒绝外部替换后的旧回调。共享provider自身取消与拥有读取器的协程取消分开；前者可恢复，后者退出。叠层状态在composition中订阅，暂停后不通过普通历史刷新续租。初始化/清空保持幂等。早期Kotlin编译、采集器仍预期2项而实际3项、API26 Dialog截图接口限制、IME/菜单瞬态、错误使用可更新SetText语义动作模拟旧闭包等失败逐组留存；没有把某个症状唯一归因于后续防护改动。原生输入owner的实际生产方法另以保留回调验证。

最终每环境8项Compose交互本次通过，共24；11项读取/输入回调边界和14项原文件导入/原owner菜单回归每环境通过，主按钮颜色修正后按精确来源匹配复用75项。复用前后唯一源码差异为建议详情主按钮前景token，逆向补丁还原整体来源指纹核对通过；未把旧APK写为新APK重跑。15张原生子树/Dialog图已复核，发现并修正深色按钮默认紫色文字。手机实际跟随系统深色，两模拟器所选图为浅色；helper的theme参数不是已应用主题证明，完整矩阵仍开放。

共享JVM/iOS模拟器、Swift生产建议各组与iOS构建通过，完整pnpm check通过；后续Android单独改动再跑原生及最终parity。既有两条真实聊天原owner/canonical仍3条原输入，具名建议GET均200但正文为空，故真实源采用继续待验，未自行生成替代建议。新增真实输入0，累计215/P4201。长文短视口、文件/设置采用组合、预读计时与实际源链继续P5-3c；附件菜单旧瞬态、系统/其他平台缺口单列，业务任务/项目和无关iOS A6不扩大范围。

[结构化证据](../../../acceptance/mobile-evidence/android-alignment-P5.json#nativeComposerCheckpoint)。当前阶段提交后继续，不逐次确认。

## N032 主线合流

P5-3b提交后同步主线fa2f14db的双端转写状态波纹。只读审查一个新增来源节点，保留P5原输入/窗口守卫及主线动画。manifest按功能和变更ID三方合并、生成表重新生成，双方历史证据不覆盖。波纹继承来源不能代表本轮Android视觉或ASR通过；组合候选继续构建与后续本地检查，真实源/菜单旧观察和任务项目暂缓保持。

## P5-3c 旧原生节点、预读及长文组合

原视图退出曾取消新导航已经发起的预读，三环境受控检查均发现多一次读取；现仅回收原导航，新历史确认前不展示/采用，确认后共享原先一次只读结果。外部替换正文改用独立原生编辑节点，同一输入流连续编辑保留原节点；迟到输入不能恢复旧正文。两端建议按钮绑定呈现时的来源、内容和文本版本，Android原生按钮也随这些版本退役，避免旧动作调用新回调。旧SDK按钮脱离原生节点时可能在系统点击音之前抛出明确的未挂载异常，测试仅接受该准确拒绝，并另核原动作与正文不变。

当前真机/API26/API37各14项界面通过；API37另12项读取、14项原文件/菜单回归，加上手机深色4项和API26深色横屏双倍字号4项，共76项本次通过。另两环境各12读取及14回归按逆向单补丁还原整体源码指纹匹配复用52项；两组之间唯一差异是多弹窗正向测试的显式合成租期，产品APK完全相同。旧122项、补图时手机一次撤销未生效、旧预读重复、旧原生节点与API37正向弹窗到期失败全部保留原结果，不倒改通过。未将撤销旧失败的唯一原因归给某一防护。原生操作前等待布局稳定，不重试点击。

长文以实际测量行高顺序滚动，限定16行均完整可读，双选柄与操作至少44dp分别核对；大字短横屏需9次扫描。全文视觉fixture120秒、多弹窗正向fixture60秒显式登记，产品六秒租期与独立到期检查保持。文件原字节、清单、独立设置和撤销正文逐项相同，附件弹窗在原灰字到期后仍保持。8张当前Android原生子树/Dialog图与iOS深色主按钮图复核，整页token/系统复制/全部字号方向未关闭。

iOS当前独立模拟器清空恢复和租期到期/恢复两项原生检查通过，均为合成来源、零写入；共同规则未改，旧共享及生产规则证据按原指纹保留。原桌面只读建议200但无正文；另一历史验收来源owner未加载，止于身份检查，没有打开或生成新输入。新增真实输入0，累计215/P4201。当前Android构建、iOS原生编译及parity分别通过；本次无TS/共同核心变化，不冒称重跑完整工作区检查。

[结构化证据](../../../acceptance/mobile-evidence/android-alignment-P5.json#nativeCombinationCheckpoint)。当前本地范围提交后进入P6语言/设置/展示及Android P7，真实来源、系统与其他平台缺口继续单列，任务/项目业务和无关iOS A6保持暂缓。
