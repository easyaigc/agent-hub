# P4 冷 Host 与写入后未知回执

历史过程档案，保留原日期、失败、限定通过与当时的下一步；**当前状态以[计划与进度总表](../progress.md)为准**。旧分支、门槛及优先级不作为当前执行指令。

<a id="progress-1189-1204"></a>

## 原进度记录 · 原文第 1189–1204 行

### P4-4o.13 Tool approval conflict and cold Host recovery

2026-10-25 P4-4o.13 工具审批冲突后Host冷重启与新审批恢复：以3b13ff27为源码基线，该提交已提PR #18，主线CI、实际合并和旧分支清理单独登记。本阶段仅验收器、子进程助手和Native测试，产品App368e541d及Host/Adapter/KMP/iOS/协议生产代码未改；严格printf审批命令守卫不变。独立Host助手仅增加准确approval-invalidation-live私有目录白名单。真实新手机API33零输入预检和完整两轮通过：第二具名HTTP客户端先按原请求声明cancel；手机旧审批一次真实Host409，显示“审批已处理或已过期”错误Dialog，明确关闭后触发原Host正常退出并确认PID结束，再以同端口/Host ID/密钥/状态启动新PID。原owner原Turn保持interrupted，旧审批为空且命令未执行；原生刷新后旧卡消失，保留旧业务回调无POST、无等待，独立草稿保留。新模型Turn产生不同审批ID，所有声明按钮可用、无旧等待提示，仅允许一次原指定打印命令，exit0/输出严格匹配，原owner完成。原生与独立官方canonical逐项核2唯一clientId/2Turn、readOnly/on-request/xhigh/priority/default；新增2，累计167/P4累计153、未知0，新增未完成原场景0。两轮共3自有Host进程均退出、端口关闭，3份真实默认草稿/附件/版本保护证明通过，12张自有主窗/Dialog图已查看；预检为竖屏、真实过程为横屏，横屏滚动后的“需要审批”标题顶部局部裁切仍单列，不能视为完整视觉通过。功能/交互/界面对照iOS SessionStore.answer及RootView.ApprovalCard的原审批声明按钮、等待、错误与草稿；iOS原DTO/导航/错误代次源码防护缺口仍待修复和原生复验。本轮无新增iOS/API26/API37/Windows证据；仅受控正常退出的冷进程恢复，不补签崩溃、断电、自然网络或桌面UI操作。合并预检另有3次0输入失败，实际因其他EasyWork独立测试占用UiAutomation，保留回执且未停止其任务；本阶段随后设备空闲再验收通过。F18/F25/F29/F41/F42和P4整体保持开放，20功能/27节点保留，任务和项目管理暂缓，蜂窝/VPN、人工归档、菜单坐标权限、A6/P7/语音暂停、P9依赖、F29导入原草稿及完整视觉矩阵仍待验。

PR [#18](https://git.51easyai.com/BCAI/agent-hub-app/pulls/18) 已实际合并到 `main 944704d218eafd1fa0fd0298486858a57830abde`；832份候选提交源码、既有同指纹零输入预检及完整68命令检查匹配，当前Linux workspace/iOS CI通过。当前三次0输入预检因其他独立runner占用系统UiAutomation而失败，均保留；未停止其他任务。原 `codex/android-p4-owner-cold-recovery-20261025` 已保存并验证恢复包，再按准确SHA租约删除远端及本地分支。固定主工作区未改。

`pnpm check`: 68 sequential commands passed, 832 frozen source files stable, 551.2 seconds; Windows native22 skipped on macOS. Root log SHA-256: `da2358bec6baafd4ce7339f85e67c476df227ccd206e0a65d9de206d8d44ec5b`. Total confirmed model inputs (including retained known failed scenes) 167, P4 153, unknown0.

### P4-4o.14 Post-write unknown answer and original receipt verification

2026-10-25 P4-4o.14 写入后结果未知的原回答回执核对：上一阶段9b6974a已先提交推送登记，本阶段才开始；3b13ff27已通过PR #18实际合入main944704d2并清理对应本地/远端分支。仅扩展验收器和Native测试，产品App368e541d及Host/Adapter/KMP/iOS/协议生产代码未改。真实新手机API33：原异步问题已结束后，原生在提交前以原问题ID固定其确定性原回答clientMessageId并保存不可变意图；选“继续验收”仅提交一次。真实Host先返回200且持久化action.resolved/原回答与accepted回执成立，验收代理仅把手机响应改为受控503，明确区分服务实际接受和手机回调结果未知，不冒称自然网络丢包。手机错误Dialog显示结果待核对，原问题及独立草稿仍保留；随后生产typed replyDeliveryStatus GET仅查提交前原ID，核对Host/thread/完整target/accepted/fingerprint/version与实际原回执一致。手机明确关闭错误后刷新，原问题卡/窗口消失，Runtime同ID回执有效，原回答正文恰好显示一次，草稿保留；再次触发保留的原业务回调无POST/无等待，原owner后续Turn完成。独立原owner及官方canonical各核2唯一clientId/2 completed Turn，readOnly/on-request/xhigh/priority/default，无工具命令和重复输入；本次新增2，累计169/P4累计155、未知输入0，新增未完成原场景0。两次初始正式尝试均在发出输入前失败（Compose树丢失、主窗口绑定失效），均0输入且原回执保留，未归因用户操作；其根因及配置变化恢复不补签。测试增加仅本专项固定竖屏，并在失败路径/结束释放旋转锁，当前同源码/runner416d3242零输入预检与完整场景通过；两份unfreeze回执及结束后设备自动旋转设置1分别核对。旧预检通过及两次失败仍按原f86f5f34/旧源码分开记账，不覆盖。共5轮、5份真实默认草稿/附件/版本保护证明通过，自有端口关闭，14张自有主窗/Dialog图已查看。iOS逐项对照SessionStore.reconcilePending/query原回执的完整目标/原ID/指纹、RootView.ApprovalCard/回执状态和草稿；本次原回答查询为typed GET及Runtime核对，不补签发送记录页面按钮或iOS真机。iOS原DTO/导航/错误代次源码防护缺口、工具审批横屏标题裁切、完整主题字号/AX/系统合成及配置变化仍开。受控503不替代自然网络、Host崩溃/断电或其他平台证据，无新增API26/API37/Windows/iOS结果。F18/F25/F29/F41/F42及P4整体保持开放，20功能/27节点、历史封存未答场景、蜂窝/VPN、人工归档、菜单坐标权限、A6/P7/语音暂停、P9依赖、F29导入原草稿均保留；任务和项目管理暂缓。

`pnpm check`: 68 sequential commands passed, 832 frozen source files stable, 540.76 seconds; Windows native22 skipped on macOS. Root log SHA-256: `6b5330aa9419d06c2fc5b8681e1708eebee5d766d7212100158a70805872b0a9`. Total confirmed model inputs (including retained known failed scenes) 169, P4 155, unknown0.

两次零输入失败仅保留原始instrumentation失败日志，尚无业务写入或业务回执；未重放。旋转释放的两份实际回执来自当前预检和完整场景的正常结束；before失败路径释放为源码防护，未补签失败路径实测，配置变化/A6继续暂停。

### P4-4o.24 原回答回执时间顺序对账与双冷 Host 只读保持

2026-10-25：优先级2只读核对原回答。重新读取P4-4o.21原始结构化证明发现：coldRestart.receiptAfter是Host刚重启后的uncertain快照，同次coldNative.receipt已completed，最终runtime.json同样completed；三个阶段快照不可混为同一时点。总表中“历史uncertain”改为指定操作已完成时间顺序对账，原始失败/快照不改写，不宣称新发现或修复后端缺陷。

本次只复制本任务同电脑私有验收Host状态至新0700证据目录，凭据0600，不改原证明/正式Host/其他电脑配置。不设置伪造uncertain、不调用回答/发送/创建/打开命令、不接管未加载owner。现有精确原owner在线，独立Electron和canonical在前、冷Host1后、冷Host2后三次核对始终3唯一原输入、3完成Turn、0请求，clientMessageId与原acceptedTurnId精确对应。两个Host进程不同PID、依次启动退出，端口全关；原runtime与token哈希不变。

新增Android只读验收类，独立Keystore命名空间，经真实NativeCodexCommands.receipt和replyDeliveryStatus查询同一Host/thread/operation，原fingerprint、Turn、正文、无附件及完整运行目标校验一致，均completed。手机/API26/API37在两轮冷Host各一次，共6项通过；真实旧回答只显示一次，原3输入未增加；刷新后本机新草稿保持，界面没有该操作的假uncertain卡片，默认drafts/files/versions保持。精确GET白名单只允许原目标读取；后台fork-options亦限定原thread，所有上游请求为GET，零操作写入/零模型输入；完整私有证明以报告/driver指纹绑定。6张自有Compose语义树图已看，只证明本轮暗色界面，没有系统菜单或全主题/字号结论。

三次中间失败保留：AVD名称回执CR换行影响身份比对（未执行原生）；API26收键盘后无障碍根瞬时为空，增加有界等待目标包后通过；旧runner未显式激活手机目标Activity，停在Launcher，仅结束本任务验收App并使用既有nativeForeground开关后通过。只读白名单遗漏后台fork-options导致早期GET被拒，补准确原thread规则后最终零拒绝；不放宽任何写入。所有失败Host正常退出，证据留存。

对照iOS SessionStore.receiptNotices和RootView：completed且无队列同步错误不显示待确认提示；历史uncertain保留历史说明，不能自动重发。当前生产App与P4-4o.23逐字节相同，前阶段42原生方法按其原runner指纹保留，本次新runner只计6项；仅iOS源码对照，没有新增iOS真机结果。已completed状态的再次冷启动保持不等于全新自然故障收敛。新增0输入，累计192/P4累计178；自然断网/崩溃/断电、其他旧原操作、完整队列与视觉矩阵继续单列；任务/项目和A6/P7范围保持暂缓。

证据：[P4-4o.24 结构化记录](../../../acceptance/mobile-evidence/android-alignment-P4.json#originalReceiptRetentionCheckpoint)。精确阶段提交/远端与树外恢复包另行核对；不改写历史失败。
