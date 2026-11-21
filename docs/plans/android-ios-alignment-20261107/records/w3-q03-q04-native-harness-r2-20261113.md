# W3：Q03/Q04 独立原生真实 DEV 入口源码

本记录关联 F34/F35/F36/F37/F38/F39/F43/F44 与 Q03/Q04，不是当前完成率或调度总表；状态仍由[权威计划](../progress.md)维护。基线为本地合成 `2021e84c53ddf14774dc65c732564775c4b03695`。本批只增加 androidTest/tool，未改生产语义、Host、Adapter 或 W2 负责的 debug profile。

## 已实现入口与依赖

`MobileGenerationCommandRealDevR2Test` 提供两个明确方法，必须分别在新 instrumentation 进程、全新 task profile 与新设备授权中运行：

- `q03NativeUiUsesOriginalTurnOnce`：真实 MainActivity/Session/production provider，读取实际模型目录，原生菜单选择模型、xhigh、原权限及可选显式速度；真实输入框和发送按钮只提交一次，空附件。核原 journal 不可变正文/实际 operation ID、唯一用户输入、原 Turn 的模型/强度/权限及原答复。没有原生明确选速时省略速度覆盖，不把隐含默认写成 standard。
- `q04NativeQueueAndIndependentStop`：受控首 Turn 单命令进程 40 秒；原队列两条分别取消/提升，按原 operation ID 和同 Turn 立即追加；原 Turn 真实最终答复后，在独立 Turn 再以单命令进程 40 秒测试停止和本机新草稿保留。每工具上限45秒，不扩窗、重开或恢复/重跑来掩盖失败。status 只查询已保存的原 reply ID；未知/失败不触发发送重试。

直接复用 W2 的 `Q02RealReadActivity`／`Q02RealReadInstallation.Permit/prepare/pairFreshTarget/close`，不复制或改写其实现。该类不在本线基线中，必须由总控与 W2 源码合成后编译。使用同 Android UID 内的新逻辑安装，**不是新的 OS sandbox**；不挂载/导入默认资料或原手机凭据，不替换读写 provider，不开启全局 readonly 生产模式。真实系统通知/发现/语音等集成不由此入口证明。

## 冻结与实际 ID

控制器在上机前冻结完整本机 target、新业务 thread/owner、六个编码/主控聊天排除集、设备/包/签名/源指纹、新绑定授权及输入预算。私有 plan、绑定输入和证明只留设备任务目录，源码没有真实凭据或聊天正文。

生产 `MobileSession.send` 自行分配 UUID；本线不伪造或替换它。控制器冻结的是一次 UI 动作的 slot UUID/正文 SHA，生产 journal 在发网前固化实际 operation ID。本线记录该原 journal，再将 slot 与实际 ID 交桌面独立核对。若总控要求在 UI 点击前预填具体 wire operation UUID，当前生产 API 无此参数，本入口不能声称满足；不得通过反射、更换 provider 或改写已保存 intent 规避。

每个动作要求新鲜的独立桌面只读证明，含完整 target/thread/lease/plan SHA、原 Turn、原操作 ID和源指纹；每个动作声明独占新建并 fsync，失败后保留。没有 fixture 端口、Host 代发模型或通用 shell/IPC 入口。新的业务 chat 必须由总控明确绑定，不能选六个工作聊天或按标题接管旧聊天。

## 工具与预算

`scripts/mobile/real-dev-generation-command-plan.py` 仅离线 `budget/validate/freeze`，校验租约、单次预算、正文、空附件、目标和速度省略；不会访问电脑/手机、配对、打开聊天或发送模型。freeze 使用新文件，不覆盖原意图。

`scripts/mobile/real-dev-generation-command-witness.mjs` 仅调用既有 adapter 的 `DesktopIpc.connect/follow/hasOwner/close`，读取准确业务原 owner；不调用 command、Host 写接口或模型。当前工具仅生成 Codex 的可核对证明：未知 wire 字段保留 null，不能推断模型、子代理或工具开始时间；不满足字段时 native 用例拒绝继续。EasyWork 须由原 Electron 的实际产品 observer 提供同契约证明，descriptor、fixture 身份或安装版本不算真实 provider/运行证据。

最小实际模型预算建议（当前均未授权执行）：

| 候选         | 输入/模型成本边界                                                                                                                                                                                                                                               |
| ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 默认 Q03     | 保留原无工具唯一 marker 一种：1 条父输入、0 child、0 tool；不改原提案正文或原 ID。预算工具中的模板不是可直接执行的冻结意图                                                                                                                                      |
| 可选 Q03+Q02 | 必须新 operation/body/hash：1 条父输入 + 恰好1个官方本机 Sol/xhigh child；无递归/无关文件网络。child 返回有界长全文供 Q02 独立展开/刷新核对，不能把已有五窗或无 card 的历史当该 child；需另有真实 child owner/Turn/分支证据，当前通用 witness 不伪造 child 证明 |
| Q04          | 5 条文本（预期2独立父 Turn、2 queue、1 steer），取消/执行/停止各最多1次；两次工具各40秒。保守按5条文本可能启动最多5父轮计成本；原窗口结束/自动排空导致新轮时用例拒绝继续，不自动继续/重跑。其他平台/Provider不自动共享预算                                      |

## 检查与未验证

本批只作轻量 Kotlin compiler PSI 语法、与 W2 实际 API 签名核对、Python 离线边界测试、Node 语法和 manifest/生成一致性检查。最初 Kotlin 括号语法失败已修正，原日志保留；没有改变业务断言来通过。

**没有 Android 类型编译、打包、安装、手机 UI、真实模型、队列动作或当前原 owner 实际验证。** 大型构建时段仍由总控分配。W2 API 和当前 native wire/包匹配仍须最终合成编译与原生执行；单元/语法通过不能宣称真实 DEV 通过。profile/新设备绑定在退出 Activity、Session IO 取消及 writer drain 后仍保留到总控保存证据与核销该新绑定；不删除原手机资料/别名。

## 原桌面只读封装预检（2026-11-13）

按总控本轮追加授权，只对W3本聊天既有Turn调用固定`DesktopIpc.connect/follow/hasOwner/close`两次有界只读采样，未将编码聊天用作业务模型输入。原owner匹配、14个原Turn可读；持久化仅type/name、字段键/类型、模型设置标识与原Turn ID，不保存命令正文、工具输出、内部推理或凭据。这是SDK形状校准，不是Q03/Q04业务验收。

实际为`commandExecution`：无name/arguments字段，`command`为string，时间为`startedAtMs`/`completedAtMs`/`durationMs`；403个项的原开始值与`turn.commandExecutionStartedAtMsById[id]`一致，零冲突。模型/强度在`params.collaborationMode.settings.model/reasoning_effort`，直接model/effort可为null；原权限为`:danger-full-access`，sandbox为`dangerFullAccess`。普通输入使用`userMessage.clientId/content`，追加使用`steeringUserMessage.clientUserMessageId/input`；不以server ID替换原提交ID。

新增纯函数evidence模块及6个Node边界用例；witness按上述实际封装提取原时间/输入，固定40秒命令必须逐字匹配，未知wrapper/缺少或冲突时间/额外进程/未知tool一律返回null。未在模型上运行sleep40；形状匹配及合成反例不宣称真实命令已验。`originalToolCount`现在明确计原桌面commandExecution项，不能据此声称SDK仅调用一个工具；额外不可识别poll仍拒绝，不静默忽略。

当前终端API首调用yield最多30000ms。新预算提案使用原`exec_command`一次、40秒固定进程；仅在该原调用返回原session_id且未结束时，允许同session的`write_stdin`必要poll至多一次、最多10000ms，全部执行等待仍<=45000ms，不能启动新命令、补sleep、扩窗或因UNKNOWN重试。原桌面采样没有独立poll的原session参数，`terminalApiPollAssociation`保持null，不能从processId、文字或marker猜造；总控须冻结实际业务owner工具契约与poll预算后才能执行Q04。不得以现有严格一个进程断言冒充已验证poll次数。

Q03优先只走无child最小路径；combined分支继续返回childStarts=null。若将来获新intent/预算，独立child取证建议使用既有`subagentIds(parentItem)`从原父Turn的`collabAgentToolCall.receiverThreadIds`或`subAgentActivity.agentThreadId`提取准确唯一child；冻结父/child/Turn/分支关系后，只对该原child用固定`follow(childId,...,true)`与`hasOwner`取当前快照，再用既有`subagentTranscript`核全文指纹/omitted。`DesktopRuntime.followSubagents/projectSubagent`是现有产品投影参考，不为本轮采样启动其后台批量读取；历史全文可读与当前child owner分别留证。缺少父关系/分支/当前owner或仅有marker时不记child通过；本轮未读其他child、未新建或分派任何Agent。

指纹来源补正：witness只把plan值作为`desktopRuntimeFingerprintReference`，并独立列出当前磁盘文件摘要`observedDesktopExecutableSha256`；这不能证明运行进程加载了该安装物，故实际runtime fingerprint保持null、verified=false并注明来源。原生动作gate必须另有新鲜独立process/runtime/install证明且verified=true，不能用相同冻结引用回填自证。原Turn参数证明与runtime安装物证明分开，元数据新建或旧HTTP成功不替代后者。新增边界用例验证“冻结值=磁盘值”仍不能升级运行时证明。

后续受控补验已取得真实进程绑定：准确socket的唯一可信App PID、ps启动时刻/可执行路径、lsof txt设备/inode/size与当前exe逐项一致，磁盘SHA前后文件身份保持，fresh exact IPC owner/Host联合为环境证明。新增observeDesktopRuntime只执行固定只读ps/lsof和文件读取，不起执行器或操作GUI。witness接受新鲜实际Host GET证明后自身重采环境，只有观测fingerprint与冻结reference相等才verified=true；缺证明仍null，不永久以null代替可核绑定。安装物摘要desktopExecutableSha256和运行时identity fingerprint分开。

产品目录前置已实际失败：专用零Turn工件在官方metadata和原owner均存在，正式Host workspace没有它且history按ID404。Native在配对/发送前要求新鲜product-directory-proof.json和真实可见/可控history，不能注入MobileThread或占位。该门槛等待独立DEV候选完成按ID只读准入后重核。Q04两条origin slot要求显式同一空白task toolWorkdir及正文路径，witness再核原commandExecution.cwd，主检出cwd不接受。Q03 no-tool不依赖Q04工具/poll证明。

总控随后核到更小的既有路径：独立DEV Host启动后，受控启动器调用typed `host.session.open(准确原QA ID)`，走现有openNow的fresh exact owner守卫完成真实metadata投影；再核HTTP workspace/history及手机读取。无需改生产unknown-GET规则，不复制旧验收脚本的placeholder模型。W3尚未修改runtime-session.ts，也不把该已存在typed入口称作已部署DEV通过；具体bootstrap交W2按独占时段执行。

最终分离契约：controlHostId为独立授权的控制来源，Provider actualTarget.hostId可不同；冻结local-mapping.json以sourceAuthority、完整actualTarget/thread/endpoint/lease及实测environment identity绑定，witness再以实际Host GET和自身fresh OS/IPC采样交叉核实，不通过二者ID等号判本机。plan validate/freeze必须显式给--mapping并核文件SHA/原目标/环境摘要。磁盘摘要只比desktopExecutableSha256；综合runtime fingerprint独立比desktopRuntimeFingerprint，两个字段都为64hex且允许不同。新增不同摘要正例/错exe/错runtime及错control/Provider/Host/过期映射反例。W2 profile的旧Codex相等守卫仍属W2/总控处理，不纳入本线12文件。

## 后续只读工具联结候选（2026-11-13）

原12文件已保存为`ac3060a2aa9c2ccde746db37b2a5556180126e66`，原完整`pnpm check`退出0，808.404秒；保留此前依赖缺失及过长TMPDIR导致的两次原失败。此后本节对应的工具改动单独使用相关Node反例、lint、格式和来源/生成检查，不把旧全链通过指纹套到新工具。原生Kotlin文件SHA256仍为`cc0e651e5f28c68778a43af8593040b18bc12a8f9c9d493434f8c7b08d8084b8`，字节冻结供统一Android typing，未在本线运行Android全构建。

新增可选`--child-proof`和`--child-plan`联结独立只读子代理证明：需根计划冻结helper/artifact/source pins，独立scope绑定同一Provider target/控制电脑/QA、实际native clientMessageId及原正文SHA、原parent Turn、有效租约和30秒鲜度。仅独立proof完整、原spawn为1、child原Turn与同Turn官方JSONL均证明Sol/xhigh、完整96行及父子回链一致时可提升combined；还需本线原parent实际设置、最终marker、原输入计数和运行环境证明。缺失/未知child不变为0，marker不算child。实际QA仍无child；本项只是联结工具和反例，不是combined真实通过。

总控随后明确Q04按一个原40秒进程及显式空白cwd验收，终端API poll关联单列unknown，不作为队列功能前置。只读本聊天既有原Turn（无新试探执行）观察到`/bin/zsh -lc`展示外壳；一轮518条中60条为单引号、453条为双引号、5条为其他引号形状，cwd均存在。当前仅接受原literal或由该literal构造的**一个精确单引号zsh整串**，不解析shell、不包含匹配、不接受其他flags/追加命令/改时长/错cwd/第二进程；输出原renderer命令SHA、形状枚举、原item ID与startedAtMs。初轮507条与后续只读采样的计数不同按各次原指纹保留。原JSONL只有外层exec自定义调用，leaf exec/poll call_id尚无可核映射，保持null。原进程允许必要的同session有界poll完成，不起新命令/补sleep，总执行等待不超过45秒；不声称SDK调用次数已验。

本task私有阶段控制器只读具名HTTPS GET/原桌面和准确Android测试证据目录，写各stage不可变proof及SHA。`native-ui-generation-selection.json`是首个触发，随后按`observed-<slot>.json`中的真实SavedOperation与独立owner/原队列状态自动供证，**不等待claim才发布proof**。精确serial/package/run/租约/包/源/证书必须由总控冻结；ADB仅白名单证据文件，stdin传proof，不读取/回显配对adminKey，不安装/启动instrumentation、授权设备或代发模型/队列。Q04的同原进程窗口由控制器自动供证；未知/失败只保留原ID。控制器源和反例保存在本task私有交付，不含在产品运行路径；尚未手机执行或给出可执行冻结配置。

后续按总控复核的W5 v2只读history契约收口：固定四个helper（含`q02-history-access.mjs`）的pins及实际方法名。Parent仍要求新鲜完整原owner；child可为`original-desktop-ipc`原owner，或在原child owner不可用后经固定官方`thread/read(includeTurns=true)`取得的`official-history`。后者必须保持`ownerVerified=false/controlAvailable=null`，核对冻结元数据可执行文件/SHA、同一准确child、源完整性、同Turn JSONL/实际Sol-xhigh和最终正文；不能注册owner或把历史读取宣称为运行控制。存在的parent声明冲突拒绝；未报告backlink保留null，由重新核过的原parent显式边和root图限定。Reader关闭状态独立保留，`close-unconfirmed`不写成已关闭。原ID/正文/parent Turn、Provider mapping、runtime/原生成设置与最终marker门槛保持，0/null不互换；相关Node正反例为辅助，不代表真实child已执行。

同一实际parent Turn另明确核准工具预算：必须恰好一个与独立原spawn item ID/child相同的spawn；其余仅同child的已识别必要wait/close及已识别非工具消息/生命周期元数据。原`commandExecution`、file/network、未知工具、另一个spawn、resume/send-input或错child均拒绝，不把`originalProcessCount`遇collab返回的null当0。Proof分别写已核parent零command/零未准工具及预算已证；同一实际child Turn的no-extra-tool事实由W5独立证据负责，不能由本线parent断言代替。

最终combined再显式联结W5的`child.toolFacts` schema1：其Turn ID须同时匹配child最后执行Turn和官方turn_context，authority一致，execution/context/source/terminal均已核实且非继承Turn；`noToolsVerified=true`、主动/已知主动工具数均为数值0，activeItems和未知类型列表均为空。缺字段、null、未知/在途/未完整来源或仅有Q02完成标志不能证明零工具。完整历史可含继承的父Turn，`turnCount`不再被误当新增child执行次数；仅选中的实际执行Turn事实参与此预算。此门槛只限combined验收，不降低一般Q02历史可读/来源标注能力，也不把历史可读当运行控制。正反例为辅助，无新真实观察或模型输入。

新DEV Host的`observeDesktopRuntime`已实际只读通过（0.836秒），QA仍0Turn；综合FP为`5804c2047fad69085335286fd3c1601832b347746c1805ab82551d2960fefa2e`。该FP来自实际进程/socket/inode/磁盘SHA及fresh原owner，磁盘SHA与综合FP仍分列；具名原件在本task私有交付，不作为模型租约或手机通过。Kotlin冻结字节未改；总控另报统一App/runner typing成功，具体包指纹与真实DEV仍按总控原件独立登记，不以本线源码/Node检查替代。
