# P4 计划、工具审批与异步问题

历史过程档案，保留原日期、失败、限定通过与当时的下一步；**当前状态以[计划与进度总表](../progress.md)为准**。旧分支、门槛及优先级不作为当前执行指令。

<a id="progress-522-591"></a>

## 原进度记录 · 原文第 522–591 行

### P4-4b 计划提出、调整与实施真实验收

2026-10-23 P4-4b/F21/F40：Android真机原生界面完成提出计划、按原计划ID调整、实施完整修订正文三步真实模型验收。各步独立核对原Electron owner、唯一clientId/Turn、规范历史总输入1→2→3、实际plan/plan/default协作模式及显式model/xhigh/priority/readOnly/on-request。调整与实施保留独立主草稿，原不可变意图与Host回执匹配；实施携带完整修订正文和新expectedPlanId，完成后待实施计划消失、模式回默认。产品App与P4-4a逐字节相同，仅新增原生/JS验收器，iOS/KMP/Host/协议未修改。

第一轮提出计划已独立通过，调整在发送前因未找到反馈框失败；后续恢复预检一次共享提示等待失败，另一次调整点击等待失败，均在claim及POST前，新增模型0。保留失败整轮，不重发首次输入。验收器改走本App收起键盘入口，并等IME隐藏及窗口可见尺寸连续稳定700ms；原生零输入预检后，只继续同一原owner的剩余调整/实施。此前单次零输入调整可达不足以宣称稳定，保留反复失败，不把键盘时序认定产品根因。

真实结果由同一产品包的首次提出计划1项和当前runner的调整/实施2项组成；不声称三步全部由最终同一runner执行。恢复入口只接受原失败证明中已完成且唯一的首次输入，先只读复核规范历史恰好1条及原待实施计划完全相同，再允许剩余两条独立输入。两个当前源码指纹绑定；旧轮结果与runner指纹独立保留。

原图逐张核对包含计划正文、调整编辑和最终完整修订输入/回复；部分PixelCopy窗口图仅边框/背景、文字层缺失，且反馈内滚动可裁切卡片标题。这些图片保留为捕获异常/局部范围，不计完整视觉通过，不据此推断手机数据丢失或定位合成器根因。

本项新增真实模型3，P4累计11、全部阶段累计24；历史21条及本次已消费原ID不重放。USB回环不证明蜂窝/VPN。审批、异步问题、通知、控制及完整P3/P4、27节点/20功能、双语/AX/系统合成视觉继续；任务/项目管理暂缓，A6/P7保持暂停，人工归档及蜂窝/VPN待验收。F21/F40整体状态与iOS当前产品验收仍开放。

P4-4b当前两份验收源码保持稳定，完整pnpm check的59条根检查通过（357.68秒）；Android构建与原生真实模型结果单列。15张已查看窗口图及元数据登记，部分文字层缺失仅保留捕获异常，不计完整视觉通过。

### P4-4c 审批与问题界面、原请求回调

2026-10-23 P4-4c/F41/F42/F46：按冻结B013/B015/B018/B019/B020及iOS ApprovalCard/AsyncQuestionDeck逐项对照，Android问题选项改为包含原标签和完整说明的前导对齐整行，44dp最小动作区，单/多选分别暴露原生选择语义；提交增加进度指示，等待期间禁用选择/自定义/提交。原目标与完整已知请求仍匹配时，断线缓存允许编辑本地答案，所有命令仍拒绝。问题选择/自定义草稿按完整已知请求对象和导航代次隔离，内容/题目/Turn/目标改变时重置，不把旧答案移给新问题。

审批与问题业务回调捕获完整目标和导航代次，并逐字段匹配已知原请求DTO及原thread；同ID/Turn内容已变或离开再返回均不授予旧回调写权限。提交前再核对当前前台、连接代次、机器目标、端点、证书及内容共享许可，回答集合复制为本次原输入；等待按原请求/owner隔离，晚到错误受导航/错误代次保护，结束只释放原owner等待。保留允许一次/本会话允许/拒绝/停止及异步跳过的既有具名协议，未增加任意方法或重放路径。

旧产品六反例已确认。首次新包真机/API37各六项中四通过、两项失败：验收器直接调用已移除Compose节点的包装回调，框架在进入业务前拒绝；整组失败保留。当前改为捕获原请求及原导航快照核对业务边界，随后当前动作仍经原生按钮提交。关联七项回归最初各一失败，因旧验收依赖“● One”文本前缀；两处改为原生选中语义，产品未为旧断言恢复伪前缀。最终runner的旧包四项UI反例与此前两项旧业务回调反例分别登记，不合并宣称同一最终runner完成六反例。

本项仅Android审批/问题展示及请求回调子边界，真实模型工具审批允许/拒绝、桌面抢答/失效、真实异步问题/重启与完整控制仍待验收。iOS、KMP、Host、协议源码未改；iOS相同请求内容/导航回调及双端完整F41/F42/F46当前产品验收不由本项补签。Android原生RadioButton/Checkbox与iOS圆形勾选、审批标题图标/色彩及完整卡片/IME/系统合成/双语/AX细节继续列入P6，不宣称全界面一致性通过。问题语音属于暂停的P7，保持暂停。任务/项目管理暂缓，A6/P7暂停，蜂窝/VPN和人工归档待验收；27节点/20功能及P3/P4其余门槛继续。本项新增真实模型0，总24条已消费输入不重放。

P4-4c最终相同App/runner在API26、新手机API33及API37各42方法，共126方法/9完整原生辅助分组通过，四份源码保持稳定：7项审批/问题新断言、7项问题卡/状态/计划/发送回归及28项目标/恢复边界。最终runner旧包4项UI反例确认，旧版早先6反例和所有中间失败分别保留。原18张已查看App窗口图及元数据按完全相同产品包、逐字节不变的原六测试体和捕获助手匹配复用；最终增加的多选/保密方法不补签视觉通过。图中触摸反馈、卡片内滚动裁切及文字层缺失仍为局部证据/捕获异常，P6全视觉保持开放。

真机最终一轮在回归前读取已安装App的ADB命令失败，原生执行0，不计通过；当前设备准确包路径、连接和唤醒分别核对，独立完整读回23546764字节、SHA匹配同一产品包后，仅补跑剩余7+28分组通过，无重装/清数据。其余已通过项未重放；未推断USB/OS根因，未把此辅助恢复当作蜂窝/VPN证明。

### P4-4d 原始工具审批真实验收

2026-10-23 P4-4d/F41/F46：Android新手机原生界面与原Electron owner完成两项真实工具审批：首条请求取消后原Turn中断、原命令declined且未成功执行；第二条原请求允许后，同一原命令completed、exitCode=0并输出专用标识，模型最终回复匹配。原桌面此次仅声明accept/cancel，“拒绝后继续”没有可用动作，准确记录取消/中断及该限制，不冒充decline-and-continue通过。两次审批均独立核对原Host/thread/Turn/request和唯一具名POST、原输入clientId/意图及规范历史总输入1→2，实际模型/xhigh/priority/readOnly/on-request/default参数；审批保留独立主草稿。

新增独立原生与JS真实验收器，每条新输入和原审批动作分别取得不可变单次租约；代理仅接受准确原正文/设置/空附件及原审批ID/Turn/decision，重复和未知结果不重放。模型任务仅打印专用标识，不读写文件、不联系其他聊天或派生任务。共享许可和显式普通模式、权限、速度均经本App原生菜单核对；零输入预检后再提交。消费计数在独立owner看到原用户输入时登记，完成场景和未解决原输入分开。产品App与P4-4c逐字节相同，iOS/KMP/Host/协议及所有生产源码未改。

首轮零输入预检的原生方法成功、业务POST及模型输入均0，但验收器最终CLI摘要读取未初始化unresolvedOriginalCases数组而退出1；原日志/报告与整体失败侧证分别保留，不把原JSON的passed当作整体成功。修正仅JS验收字段后，完整零输入预检CLI退出0，再执行两条全新输入。真实两场景当前同包/runner原生方法、独立原owner、实际工具结果和精确POST总计5条均通过，读取/写入拒绝及未解决原ID均0。

本项新增真实模型2，P4累计13、全部阶段累计26；此前24条及本次已消费输入不重放。仅关闭此原owner的取消/允许工具审批子边界；本会话允许、decline继续（原owner未声明）、跨端抢答/旧请求失效、真实异步问题/重启、控制及完整F41/F46、27节点/20功能/P3/P4继续。iOS当前产品验收不由Android补签。9张当前窗口原图已逐张查看、窗口元数据核对；部分文字层缺失/局部裁切保留为捕获异常，不计全视觉。USB回环不证明蜂窝/VPN，人工归档待验收；任务/项目管理暂缓，A6/P7暂停及P9依赖保持。

P4-4d当前两份验收源码保持稳定，完整pnpm check的59条根检查通过（366.61秒）；当前Android构建、零输入预检和两项真实模型/原owner结果分别记录。原始回执与窗口证据保留在树外私有目录，本项整体功能及完整视觉仍未关闭。

### P4-4d.1 验收器命令范围与原始命令只读复核

2026-10-23 P4-4d.1/F41/F46：补强macOS真实审批验收器的命令范围门禁。原输入租约与请求ID校验之外，审批前必须核对原IPC方法为commandExecution/requestApproval、准确thread/Turn/wire请求ID、可选cwd、原API投影及完整命令，命令只允许本次专用标识的固定zsh/printf调用。追加shell动作、换行、改变wrapper/标识、文件变更请求、错目标/目录/投影或附带问题均在给手机发审批许可前拒绝；拒绝错误不回显原命令。它仅约束本轮验收器，产品App/Host/协议行为未修改。

14项纯Node边界用例接入既有pnpm check中的权限证据测试分组，根检查仍为59条。另以准确原thread/Turn/clientId只读复核前一轮两项：每Turn恰好一条准确printf、无文件变更等条目，状态分别declined及completed/exitCode=0；原任务/输入/审批不重放。旧两项真实结果保持其原指纹，只读复核不补签“此前已运行新前置门禁”。当前真机零输入预检原生1项、CLI整体通过，业务POST和新增模型均0。

新增模型0，P4累计13、总26。新命令门禁的拒绝与绑定为辅助检查，强化门禁尚无新的真实审批输入；此前两项只读匹配与其原始真实证明分别保存。本会话允许、原owner未声明的decline继续、跨端抢答/失效、真实异步问题/Host重启、控制及完整F41/F46/P3/P4/27节点20功能仍开放；任务/项目管理暂缓，A6/P7暂停，蜂窝/VPN及人工归档待验收。

P4-4d.1四份工具源码冻结，完整pnpm check的59条根检查通过（361.03秒），14项门禁用例已纳入。初次文档别名误含根package.json，安全源码路径检查拒绝，9.16秒失败原记录保留；只修正文档别名，未放宽校验器后重跑全组通过。

### P4-4e 真实异步问题与权限继承反例

2026-10-24 P4-4e/F34/F35/F41/F42/F46/F58：新增独立Android真机异步问题验收器，限定原Host/thread/Turn/question、不可变输入和一次回答租约，实际原Electron owner与规范历史逐项核对。产品源码未改。非阻塞提问、选项/自定义草稿、前后翻页/横滑/收起重开、处理后推进和跳过取得局部真实证据；专用Host重启保持身份、令牌、原动作账本和原问题ID。

两项主输入与三条实际回答共消费5条输入，规范历史总数5，跳过不发模型输入；P4累计18、全部阶段31条均不重放。首个原生完整分组在最终命令核对失败：原Turn完成，但原SDK命令条目仍inProgress/exitCode=null，未验证命令终态；保留原失败，不把异步完成冒充工具终态。当前同产品包的新runner零写入原会话恢复方法通过，随后仅执行原未执行的第二项。第二个完整原生分组因原始生效权限断言失败：原提问readOnly/on-request，结束后回答产生的新Turn变成dangerFullAccess/never；模型/极高/快速保持，未执行工具/文件变更。两个失败分组和只读恢复分别登记，整项异步验收未通过。

优先修复共享Host的结束后异步回答权限继承，再用全新输入验收，不重放旧5条。iOS共用此Host风险，当前iOS未新增验收。10张已查看原App/问题dialog独有窗口图与元数据仅作局部状态证据；黑色背景、滚动裁切及未滚到最终回复不计完整合成视觉，P6继续。F34/F35/F41/F42/F46/F58及27节点/20功能/P3/P4保持开放；后续控制、跨端抢答、多选/保密真实能力、蜂窝/VPN及人工归档待验收；任务/项目管理暂缓，A6/P7暂停，P9依赖保持。

2026-10-24 local refs: main b07bed3c23d38eb41781f0739e9976772c2836f6 contains old codex/android-native 1e0acfa6c709c3ba459962abe72f5502d70537b5. This Android alignment branch remains unmerged. Full workspace check: 59 commands passed in 360.40 seconds, both examiner sources stable; initial 5.81-second documentation format failure retained. Real DEV failures above remain failures.

### P4-4e.1 结束后回答的原权限继承

2026-10-24 P4-4e.1：修复共享Host在任务结束后回答异步问题时回退电脑默认权限。新回答从准确原owner的原问题Turn参数取得已确认权限预设，固定到原回答的不可变意图；无法确认时409且不保存/发送新输入。旧版无固定权限且无回执的原意图不补写、不执行；已有已接受/未知回执继续按原ID核对。运行中追加和跳过保持原路径，普通跟随电脑默认的回复规则不改。

原真实反例readOnly→dangerFullAccess和合成IPC反例:read-only→:workspace分别保留；当前完整IPC边界分组通过，另核对未知预设零写入、旧未提交意图不改写/不执行。全新真机提问与回答共2条输入，专用Host重启保持身份/令牌/原问题；原生整组1项通过，原owner两Turn均completed且readOnly/on-request/user、默认模型/极高/快速相同，无工具/文件变更，规范历史恰好2条。原CLI因1次后台workspace fetch failed整体失败，该读取失败不改记通过，也未推断根因。当前只读恢复CLI和原生1项通过，业务POST与新增输入0；产品与Host源码相同，原实际测试方法/助手逐字节相同，runner仅只读恢复标识选择改变，未宣称全套在最终runner重跑。

本项新增2条，P4累计20、总33条已消费输入均不重放。只关闭本次请求批准权限回退缺陷；前项命令终态、原CLI读取失败、完整异步/审批/跨端抢答/其他权限预设及复杂配置和F34/F35/F41/F42/F46/F58仍开放。iOS共用修复后的Host规则，未新增iOS原生真实验收或更新正式服务；Windows未验收。6张原App/dialog独有窗口图已查看，仅作局部状态证据，完整视觉P6继续。继续队列/追加/停止/恢复；蜂窝/VPN与人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

P4-4e.1 full workspace check: all 59 commands passed in 374.90 seconds; four checkpoint source hashes stable. Android builds, original native actual case and current zero-write native recovery passed under their recorded fingerprints. The original one-read CLI failure remains recorded.

<a id="plan-267-328"></a>

## 原计划记录 · 原文第 267–328 行

2026-10-23 P4-4a/F21/F40：按iOS计划三类界面逐项对照。计划消息默认折叠，区分“计划／正在制定计划…”，展开后显示完整Markdown与独立“复制计划”；复制精确原文，原生测试只在内存保留并恢复先前剪贴板，不输出其内容。步骤独立折叠为“执行计划 · 完成数/总数”，兼容underscore/camel及原legacy进行中状态，更新计数保留展开。待实施卡片只在非运行时显示，消息已有相同正文时不重复，缺正文时提供“查看计划”；实施、调整、反馈文案与顺序同步。反馈和展开按完整目标/导航代次与完整已知计划对象隔离，变更ID/正文/Turn重置，旧回调不得跨目标、重访或同ID改文执行；缓存/等待期间禁用命令但保留可编辑反馈和独立主草稿，提交保持原expectedPlanId及协作模式。

外部编排先因AVD名称CR未trim在安装前失败，三反例尚未运行，保留为0执行；修正后旧包三项UI反例确认。最初关联7项/环境因两处漏截图run参数和旧runtime变化验收假设失败，整组不计通过。旧包同样复现runtime测试失败：旧模拟器未按生产Host拒绝旧完整目标；核对真实runtime-server的前置409和上传后再次复核后，fixture同样在副作用前校验，现断言至多一条原目标HTTP、无换目标/重放、执行0且草稿保留。旧包配修正fixture单项通过，非把新UI归因该历史测试问题。API26六UI中五项通过、一项在验收器后台线程首次获取剪贴板服务时失败，产品复制动作尚未到达；整组失败保留，改主线程取测试服务，产品包未变，当前最终同包/runner矩阵重跑。

本切片为Android计划展示与回调边界，iOS既有计划界面/发送守卫未改，共享协议/KMP/Host不改。新增Android捕获导航及完整计划对象栅栏单独验证；iOS对应跨目标回调仍留整体F40当前产品未验收清单，不因Android通过而赋予iOS新证明。完整F21/F40、计划真实模型提出/调整/实施、审批/问题/通知/控制、双语/AX/系统合成视觉和27节点/20功能继续；任务/项目管理暂缓、A6/P7暂停，蜂窝/VPN与人工归档待验收。本项新增模型0，历史13和P4累计8（总21）均不重放。

API26在主线程剪贴板验收器修正后另一次运行发生System has crashed，尚未正常完成首个场景；ADB虽在线，真实activity服务缺失，准确验收App无法启动。该整轮失败不计通过。核对本任务独立AVD后正常关闭并冷启动，磁盘数据保留、不wipe；首次启动未传独立AVD_HOME而在打开AVD前失败，修正仅本次进程环境后恢复activity服务。最终完整三环境同包矩阵重新通过；不把系统崩溃归因产品复制或声称已定位OS根因。

P4-4a最终相同App/runner在API26、新手机API33及API37各41方法，共123方法/9完整原生辅助分组通过，七份源码稳定；含6项新计划UI、7项发送/计划/缓存回归及28项目标/存储边界。相同最终runner在旧包的三项UI反例确认，另有旧包/旧fixture的runtime验收失败与修正fixture后的单项通过证明。18张当前App窗口原图及侧证逐张核对；部分原生触摸反馈帧、反馈聚焦的卡片内滚动保留，不等于完整IME/系统合成/双语/AX视觉通过。新增真实模型0，后续真实计划流程继续。

2026-10-23 P4-4b/F21/F40：Android真机原生界面完成提出计划、按原计划ID调整、实施完整修订正文三步真实模型验收。各步独立核对原Electron owner、唯一clientId/Turn、规范历史总输入1→2→3、实际plan/plan/default协作模式及显式model/xhigh/priority/readOnly/on-request。调整与实施保留独立主草稿，原不可变意图与Host回执匹配；实施携带完整修订正文和新expectedPlanId，完成后待实施计划消失、模式回默认。产品App与P4-4a逐字节相同，仅新增原生/JS验收器，iOS/KMP/Host/协议未修改。

第一轮提出计划已独立通过，调整在发送前因未找到反馈框失败；后续恢复预检一次共享提示等待失败，另一次调整点击等待失败，均在claim及POST前，新增模型0。保留失败整轮，不重发首次输入。验收器改走本App收起键盘入口，并等IME隐藏及窗口可见尺寸连续稳定700ms；原生零输入预检后，只继续同一原owner的剩余调整/实施。此前单次零输入调整可达不足以宣称稳定，保留反复失败，不把键盘时序认定产品根因。

真实结果由同一产品包的首次提出计划1项和当前runner的调整/实施2项组成；不声称三步全部由最终同一runner执行。恢复入口只接受原失败证明中已完成且唯一的首次输入，先只读复核规范历史恰好1条及原待实施计划完全相同，再允许剩余两条独立输入。两个当前源码指纹绑定；旧轮结果与runner指纹独立保留。

原图逐张核对包含计划正文、调整编辑和最终完整修订输入/回复；部分PixelCopy窗口图仅边框/背景、文字层缺失，且反馈内滚动可裁切卡片标题。这些图片保留为捕获异常/局部范围，不计完整视觉通过，不据此推断手机数据丢失或定位合成器根因。

本项新增真实模型3，P4累计11、全部阶段累计24；历史21条及本次已消费原ID不重放。USB回环不证明蜂窝/VPN。审批、异步问题、通知、控制及完整P3/P4、27节点/20功能、双语/AX/系统合成视觉继续；任务/项目管理暂缓，A6/P7保持暂停，人工归档及蜂窝/VPN待验收。F21/F40整体状态与iOS当前产品验收仍开放。

P4-4b当前两份验收源码保持稳定，完整pnpm check的59条根检查通过（357.68秒）；Android构建与原生真实模型结果单列。15张已查看窗口图及元数据登记，部分文字层缺失仅保留捕获异常，不计完整视觉通过。

2026-10-23 P4-4c/F41/F42/F46：按冻结B013/B015/B018/B019/B020及iOS ApprovalCard/AsyncQuestionDeck逐项对照，Android问题选项改为包含原标签和完整说明的前导对齐整行，44dp最小动作区，单/多选分别暴露原生选择语义；提交增加进度指示，等待期间禁用选择/自定义/提交。原目标与完整已知请求仍匹配时，断线缓存允许编辑本地答案，所有命令仍拒绝。问题选择/自定义草稿按完整已知请求对象和导航代次隔离，内容/题目/Turn/目标改变时重置，不把旧答案移给新问题。

审批与问题业务回调捕获完整目标和导航代次，并逐字段匹配已知原请求DTO及原thread；同ID/Turn内容已变或离开再返回均不授予旧回调写权限。提交前再核对当前前台、连接代次、机器目标、端点、证书及内容共享许可，回答集合复制为本次原输入；等待按原请求/owner隔离，晚到错误受导航/错误代次保护，结束只释放原owner等待。保留允许一次/本会话允许/拒绝/停止及异步跳过的既有具名协议，未增加任意方法或重放路径。

旧产品六反例已确认。首次新包真机/API37各六项中四通过、两项失败：验收器直接调用已移除Compose节点的包装回调，框架在进入业务前拒绝；整组失败保留。当前改为捕获原请求及原导航快照核对业务边界，随后当前动作仍经原生按钮提交。关联七项回归最初各一失败，因旧验收依赖“● One”文本前缀；两处改为原生选中语义，产品未为旧断言恢复伪前缀。最终runner的旧包四项UI反例与此前两项旧业务回调反例分别登记，不合并宣称同一最终runner完成六反例。

本项仅Android审批/问题展示及请求回调子边界，真实模型工具审批允许/拒绝、桌面抢答/失效、真实异步问题/重启与完整控制仍待验收。iOS、KMP、Host、协议源码未改；iOS相同请求内容/导航回调及双端完整F41/F42/F46当前产品验收不由本项补签。Android原生RadioButton/Checkbox与iOS圆形勾选、审批标题图标/色彩及完整卡片/IME/系统合成/双语/AX细节继续列入P6，不宣称全界面一致性通过。问题语音属于暂停的P7，保持暂停。任务/项目管理暂缓，A6/P7暂停，蜂窝/VPN和人工归档待验收；27节点/20功能及P3/P4其余门槛继续。本项新增真实模型0，总24条已消费输入不重放。

P4-4c最终相同App/runner在API26、新手机API33及API37各42方法，共126方法/9完整原生辅助分组通过，四份源码保持稳定：7项审批/问题新断言、7项问题卡/状态/计划/发送回归及28项目标/恢复边界。最终runner旧包4项UI反例确认，旧版早先6反例和所有中间失败分别保留。原18张已查看App窗口图及元数据按完全相同产品包、逐字节不变的原六测试体和捕获助手匹配复用；最终增加的多选/保密方法不补签视觉通过。图中触摸反馈、卡片内滚动裁切及文字层缺失仍为局部证据/捕获异常，P6全视觉保持开放。

真机最终一轮在回归前读取已安装App的ADB命令失败，原生执行0，不计通过；当前设备准确包路径、连接和唤醒分别核对，独立完整读回23546764字节、SHA匹配同一产品包后，仅补跑剩余7+28分组通过，无重装/清数据。其余已通过项未重放；未推断USB/OS根因，未把此辅助恢复当作蜂窝/VPN证明。

2026-10-23 P4-4d/F41/F46：Android新手机原生界面与原Electron owner完成两项真实工具审批：首条请求取消后原Turn中断、原命令declined且未成功执行；第二条原请求允许后，同一原命令completed、exitCode=0并输出专用标识，模型最终回复匹配。原桌面此次仅声明accept/cancel，“拒绝后继续”没有可用动作，准确记录取消/中断及该限制，不冒充decline-and-continue通过。两次审批均独立核对原Host/thread/Turn/request和唯一具名POST、原输入clientId/意图及规范历史总输入1→2，实际模型/xhigh/priority/readOnly/on-request/default参数；审批保留独立主草稿。

新增独立原生与JS真实验收器，每条新输入和原审批动作分别取得不可变单次租约；代理仅接受准确原正文/设置/空附件及原审批ID/Turn/decision，重复和未知结果不重放。模型任务仅打印专用标识，不读写文件、不联系其他聊天或派生任务。共享许可和显式普通模式、权限、速度均经本App原生菜单核对；零输入预检后再提交。消费计数在独立owner看到原用户输入时登记，完成场景和未解决原输入分开。产品App与P4-4c逐字节相同，iOS/KMP/Host/协议及所有生产源码未改。

首轮零输入预检的原生方法成功、业务POST及模型输入均0，但验收器最终CLI摘要读取未初始化unresolvedOriginalCases数组而退出1；原日志/报告与整体失败侧证分别保留，不把原JSON的passed当作整体成功。修正仅JS验收字段后，完整零输入预检CLI退出0，再执行两条全新输入。真实两场景当前同包/runner原生方法、独立原owner、实际工具结果和精确POST总计5条均通过，读取/写入拒绝及未解决原ID均0。

本项新增真实模型2，P4累计13、全部阶段累计26；此前24条及本次已消费输入不重放。仅关闭此原owner的取消/允许工具审批子边界；本会话允许、decline继续（原owner未声明）、跨端抢答/旧请求失效、真实异步问题/重启、控制及完整F41/F46、27节点/20功能/P3/P4继续。iOS当前产品验收不由Android补签。9张当前窗口原图已逐张查看、窗口元数据核对；部分文字层缺失/局部裁切保留为捕获异常，不计全视觉。USB回环不证明蜂窝/VPN，人工归档待验收；任务/项目管理暂缓，A6/P7暂停及P9依赖保持。

P4-4d当前两份验收源码保持稳定，完整pnpm check的59条根检查通过（366.61秒）；当前Android构建、零输入预检和两项真实模型/原owner结果分别记录。原始回执与窗口证据保留在树外私有目录，本项整体功能及完整视觉仍未关闭。

2026-10-23 P4-4d.1/F41/F46：补强macOS真实审批验收器的命令范围门禁。原输入租约与请求ID校验之外，审批前必须核对原IPC方法为commandExecution/requestApproval、准确thread/Turn/wire请求ID、可选cwd、原API投影及完整命令，命令只允许本次专用标识的固定zsh/printf调用。追加shell动作、换行、改变wrapper/标识、文件变更请求、错目标/目录/投影或附带问题均在给手机发审批许可前拒绝；拒绝错误不回显原命令。它仅约束本轮验收器，产品App/Host/协议行为未修改。

14项纯Node边界用例接入既有pnpm check中的权限证据测试分组，根检查仍为59条。另以准确原thread/Turn/clientId只读复核前一轮两项：每Turn恰好一条准确printf、无文件变更等条目，状态分别declined及completed/exitCode=0；原任务/输入/审批不重放。旧两项真实结果保持其原指纹，只读复核不补签“此前已运行新前置门禁”。当前真机零输入预检原生1项、CLI整体通过，业务POST和新增模型均0。

新增模型0，P4累计13、总26。新命令门禁的拒绝与绑定为辅助检查，强化门禁尚无新的真实审批输入；此前两项只读匹配与其原始真实证明分别保存。本会话允许、原owner未声明的decline继续、跨端抢答/失效、真实异步问题/Host重启、控制及完整F41/F46/P3/P4/27节点20功能仍开放；任务/项目管理暂缓，A6/P7暂停，蜂窝/VPN及人工归档待验收。

2026-10-24 P4-4e/F34/F35/F41/F42/F46/F58：新增独立Android真机异步问题验收器，限定原Host/thread/Turn/question、不可变输入和一次回答租约，实际原Electron owner与规范历史逐项核对。产品源码未改。非阻塞提问、选项/自定义草稿、前后翻页/横滑/收起重开、处理后推进和跳过取得局部真实证据；专用Host重启保持身份、令牌、原动作账本和原问题ID。

两项主输入与三条实际回答共消费5条输入，规范历史总数5，跳过不发模型输入；P4累计18、全部阶段31条均不重放。首个原生完整分组在最终命令核对失败：原Turn完成，但原SDK命令条目仍inProgress/exitCode=null，未验证命令终态；保留原失败，不把异步完成冒充工具终态。当前同产品包的新runner零写入原会话恢复方法通过，随后仅执行原未执行的第二项。第二个完整原生分组因原始生效权限断言失败：原提问readOnly/on-request，结束后回答产生的新Turn变成dangerFullAccess/never；模型/极高/快速保持，未执行工具/文件变更。两个失败分组和只读恢复分别登记，整项异步验收未通过。

优先修复共享Host的结束后异步回答权限继承，再用全新输入验收，不重放旧5条。iOS共用此Host风险，当前iOS未新增验收。10张已查看原App/问题dialog独有窗口图与元数据仅作局部状态证据；黑色背景、滚动裁切及未滚到最终回复不计完整合成视觉，P6继续。F34/F35/F41/F42/F46/F58及27节点/20功能/P3/P4保持开放；后续控制、跨端抢答、多选/保密真实能力、蜂窝/VPN及人工归档待验收；任务/项目管理暂缓，A6/P7暂停，P9依赖保持。

2026-10-24 P4-4e.1：修复共享Host在任务结束后回答异步问题时回退电脑默认权限。新回答从准确原owner的原问题Turn参数取得已确认权限预设，固定到原回答的不可变意图；无法确认时409且不保存/发送新输入。旧版无固定权限且无回执的原意图不补写、不执行；已有已接受/未知回执继续按原ID核对。运行中追加和跳过保持原路径，普通跟随电脑默认的回复规则不改。

原真实反例readOnly→dangerFullAccess和合成IPC反例:read-only→:workspace分别保留；当前完整IPC边界分组通过，另核对未知预设零写入、旧未提交意图不改写/不执行。全新真机提问与回答共2条输入，专用Host重启保持身份/令牌/原问题；原生整组1项通过，原owner两Turn均completed且readOnly/on-request/user、默认模型/极高/快速相同，无工具/文件变更，规范历史恰好2条。原CLI因1次后台workspace fetch failed整体失败，该读取失败不改记通过，也未推断根因。当前只读恢复CLI和原生1项通过，业务POST与新增输入0；产品与Host源码相同，原实际测试方法/助手逐字节相同，runner仅只读恢复标识选择改变，未宣称全套在最终runner重跑。

本项新增2条，P4累计20、总33条已消费输入均不重放。只关闭本次请求批准权限回退缺陷；前项命令终态、原CLI读取失败、完整异步/审批/跨端抢答/其他权限预设及复杂配置和F34/F35/F41/F42/F46/F58仍开放。iOS共用修复后的Host规则，未新增iOS原生真实验收或更新正式服务；Windows未验收。6张原App/dialog独有窗口图已查看，仅作局部状态证据，完整视觉P6继续。继续队列/追加/停止/恢复；蜂窝/VPN与人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

### P4-4o.22 问题主操作区与原生选项无障碍

2026-10-25：4bb7899文档重整后按优先级1续行。对照iOS answerActions/ApprovalSubmitButtonStyle，旧Android原生反例约79.6dp按钮面对355.4dp内容区失败。现提交按钮填满剩余宽度，至少44dp、半粗体、10dp圆角；异步跳过至少44dp并与提交间隔12dp。颜色依页面原生语义色，系统按压反馈及完整像素一致性另验。最终App a09140c6/runner2f573278在真机/API26/API37横竖屏六组通过；模拟器竖屏2倍、横屏1.3倍字号，手机不改字体。每环境另9项回归，共33原生方法：单/多选切换、必填禁用、原生秘密掩码、提交中禁用、请求/导航隔离、缓存编辑、准确原Host/thread/Turn/请求仅1次HTTP、独立草稿与默认存储保持。SDK实际ACTION_CLICK及checked核对，Compose角色另核；无TalkBack朗读证明。

诊断失败保留：文字为可操作节点的子节点，已选单选项无重复点击但checked保持；收键盘后重新定位保密字段，EditableText掩码和InputText原文分开核对。测试编译缺getOrNull、手机APK指纹读取失败后正常重试、API26 activity服务失联及初次遗漏专用AVD目录的启动失败单列；仅重开本任务API26、保留磁盘/配置并恢复字号后通过，不宣称隔离出系统根因。API37旧横屏异步弹窗内容69.7%低于75%门槛，列下一优先级，本阶段不算通过。

同包真机零输入预检与原owner问题整例通过：旧问题真实Host409、新问题替换及Host冷重启；独立Electron和canonical确认3唯一输入/3完成Turn、0待回答请求。原回答冷回执本次为completed，clientMessageId/Turn精确匹配canonical完成轮；不把旧uncertain改写成成功，也不宣称所有自然恢复已关闭。42张最终原生及11张真实/预检自有窗口图已看，中间图另存。新增3，累计189/P4累计175；旧未完成原输入封存，任务/项目暂缓及A6/P7/语音暂停等范围保持。

证据：[P4-4o.22 结构化记录](../../../acceptance/mobile-evidence/android-alignment-P4.json#questionActionAccessibilityCheckpoint)。精确阶段提交/远端与树外恢复包另行核对；不改写历史失败。

### P4-4o.23 横屏异步问题高度与键盘下完整触达

2026-10-25：对照 iOS AsyncQuestionSheet/answerActions，保留上一阶段 API37 横屏内容69.7%反例及75%门槛。本轮 Material3 1.4.0 原始源码与原生窗口核对后，移除默认外层handle，显示24dp视觉抓手（IME时隐藏）；close仍48dp，保留原生拖动/返回并在面板提供无障碍dismiss。大面板fillMaxHeight；极短键盘窗口用至少200dp的有限内容列和外层滚动，内部问题读区仍有限，不随IME重挂载输入框。异步输入沿用平台TextField焦点滚动，去掉重复显式bringIntoView；同步输入保留原策略。视觉抓手点击关闭方式与iOS不同，不能宣称像素完全一致。Material3参考：[官方API](https://developer.android.com/reference/kotlin/androidx/compose/material3/ModalBottomSheet.composable)；精确1.4.0源码包另留树外。

最终同一App/runner在真机/API26/API37各横竖屏六组专项，加各10项回归共42原生方法通过。API37横屏内容75.648%，API26横屏88.33%，全部开启/重开达75%及顶部20%内门槛；模拟器竖屏2倍/横屏1.3倍字号，手机不改系统字号。实际IME可见时，输入/跳过/提交/前页/后页/关闭六类控件逐一滚至完整高度且至少44dp；不宣称同屏同时容纳。翻页第二请求为空、回第一页原输入保持；关闭/重开与无障碍dismiss保留请求草稿，聊天独立草稿和默认drafts/files/versions保持。共114张最终原生图，选36张及11张真实/预检自有窗口图人工看图；其余图保留，不宣称全看。键盘截图为自有窗口PixelCopy，黑区不包含系统IME；IME状态另用WindowInsets证明。

失败与修正过程保留：仅fillMaxHeight和44dp抓手仍不满足高度门槛；API26输入149/191px裁切，重复bringIntoView请求在翻页/销毁时出现未取消队列异常；修正滚动和IME抓手后按最终候选重跑。测试字符串插值、分页等待与截图辅助脚本语法错误均保留，不能算产品通过；未认定单一AndroidX根因。上一阶段API26系统服务恢复记录保持。

同一最终包真机零输入预检及真实原owner整例通过：旧问题真实Host409，新请求替换、Host冷重启及原回答completed回执。独立Electron/canonical核3唯一输入与3完成Turn、0待答、无重复执行；clientMessageId/Turn匹配。自有Host均正常退出且端口关闭。新增3输入，累计192/P4累计178。历史uncertain→终态自然收敛仍待专门只读核对，不能由本次completed替代；旧未回答原操作封存。任务/项目暂缓与A6/P7暂停保持。

证据：[P4-4o.23 结构化记录](../../../acceptance/mobile-evidence/android-alignment-P4.json#asyncQuestionViewportCheckpoint)。精确阶段提交/远端与树外恢复包另行核对；不改写历史失败。

### P4-4o.26 普通/保密回复行数与原生输入就绪边界

2026-10-25：f4463348提交/推送/远端/恢复包核对后继续。对照iOS RootView.questionCustomInput：SecureField单行、普通TextField纵向1...4行。旧Android未限制行数且保密字段未明确密码键盘/完成；同一新原生用例在旧生产App9946e0b5输入24行后实际触发BringIntoViewRequestPriorityQueue的uncancelled requests present，失败帧保留。现普通maxLines4，保密singleLine/maxLines1、KeyboardType.Password、autoCorrectEnabled=false、ImeAction.Done，不绑定自动发送。原文仍保存在本地原请求草稿，不裁正文；API37初次单例正常136dp/保密64dp，后续五组按实际字号预算验证。

排查提交禁用：同生产App6db54460、旧验收顺序在真机捕获单选/多选正确、requestCanAnswer=true、保密输入为空、按钮disabled；旧步骤未先证明IME/正文已进入，不能据此断言产品清空已输入内容或权限错误。验收现先点击焦点并等待实际IME，核InputText原文，再触发Done，等IME隐藏后复核原文与零自动POST，最终按钮及准确HTTP正文继续严格核对；没有靠等待放宽原文/目标/一次提交断言。最终三环境整类与8次附加重复合计11份就绪证明全匹配，SDK状态/掩码保持；旧API37同类快照没有同等前置证据，唯一原因无法追认，保留历史观察。

环境与辅助失败分别留证：首次测试DpRect.height编译失败后使用实际上下界差；一次误用旧runner产生NoTests不算产品反例。API37旋转确认与实际Activity方向不一致，原生前置失败及Settings/WM实际方向对账均保留；仅重启本任务API37客体，无wipe/卸载/清数据，系统指纹、准确安装路径与专用私有数据标记保持，探测曾恢复横屏但后续冻结仍失稳。当前API37横屏3项未取得新环境通过，不复用旧包或旧runner冒充。仅该AVD的临时旋转设置按原值恢复，其他AVD不操作。真机零输入预检曾在hideKeyboard等待失败，0模型/0写入；测试辅助改为核当前附着聚焦的自有窗口、输入先等IME并核正文、隐藏后700ms稳定，再预检/整例；不把旧等待失败推断为手机断连或要求人工重装。

最终冻结App/runner：真机与API26横竖屏、API37竖屏五组，模拟器2倍/1.3倍字号及每环境11项整类，共48项主矩阵通过，8项附加重复另计，共56项。当前API37横屏对应3项单列，不算完整六组通过。普通24行与保密1600+字原文不截断，字段分别按四/单行预算，Done零提交、独立草稿与默认drafts/files/versions保持。原有异步75%大高度、六控件逐项完整触达、分页/收起/语义关闭/重开在适用五组复验。58张最终原生/真实/预检选图及2张历史失败图已看；其余图片保留。长普通回复在极短键盘视口的完整触达/内滚动/选区尚未单独关闭，不把行数上限当作全部四行同时可见或全视觉通过；Outlined浮动标签/字体与iOS plain风格差异归P6继续。

最终同包真机零输入预检及真实原owner整例通过。独立Electron/canonical确认3唯一输入/3完成Turn、真实旧问题409、新请求替换、0待答，Host旧进程退出和新PID、本次原回答completed/指纹/Turn一致；所有验收Host已退出，端口关闭。新增3，累计198/P4累计184，旧未回答/失败操作不补答不重放。当前仅Kotlin UI和验收辅助变化，82构建及mobile-parity另核，f4463348的完整pnpm check/核心/iOS构建保留原指纹，不当作本轮iOS真机或完整跨平台结果。任务/项目、F71及A6/P7暂停、蜂窝/VPN、人工归档等待验保持，不阻塞下一阶段原owner菜单与剩余控制。

证据：[P4-4o.26 结构化记录](../../../acceptance/mobile-evidence/android-alignment-P4.json#questionFieldLinesCheckpoint)。精确阶段提交/远端与树外恢复包另行核对；不改写历史失败。

### P4-4o.28 长字段短视口与原生选区编辑

352f9ff was committed/pushed and remote/bundle/progress verified before continuing. Old production App on API26 landscape/font1.3 with actual IME reproduced clipping: field164.19049dp, visible399px/density2.625=152dp. Failure and window remain preserved.

Question fields now follow the iOS plain-field hierarchy:15sp text/20sp line height,10dp padding,44dp minimum target,10dp corner and faint border. Normal answer keeps at most4 lines, reducing visible lines from actual async viewport/font metrics when needed; all text stays editable and scrolls internally. Secret remains one line/password keyboard/no correction/Done without auto-submit. The async field stays in the same tree across IME changes; no new voice/system work resumed.

The native test enters24 lines with actual IME, requires the whole field above keyboard and >=44dp, selects all/start/end through an exact raw-text SDK node, checks stable product selection, scrolls inside, edits beyond24 lines, collapses/reopens without loss, and checks0 answer POST/default draft/file/version protection. The same API26 case now121.14287dp/318px fully visible. Separate first/end/selected/scrolled/edited frames show content movement; source text is retained.

Auxiliary failures remain separate. Initial Kotlin implicit receiver compile error was fixed by an explicit outer viewport value; that shell accidentally ran the old APK after failed build, which is excluded from green evidence. First SDK examiner treated an unchanged selection no-op return as failure; no-op is now recorded without unnecessary action. Earlier runner SDK accepted but product end selection timed out once; sole cause unassigned. Description-based node lookup found no matching native editable node. Final examiner targets exact full raw text and waits product selection stable300ms. Native SDK selection indices returned-1 while Compose range matched on API26; unavailable system indices are recorded separately. When indices are available they must match too. No original business operation is retried.

Final exact App/runner five-profile mandatory56 methods plus1 diagnostic, and complete12-method class on3 environments, remain bound to the audit. API37 landscape4 question methods and old menu7 gates remain deferred. New real physical original-owner question/stale409/replacement/cold receipt adds3 inputs; independent canonical audit validates exact original identities/no duplicates/0 blocking original-owner requests; the deliberate pending new async question remains unselected after cold recovery. Totals204/P4190. Complete UI/AX, natural receipt recovery, real four-decision/nonempty-draft stop scenes and iOS callbacks remain open; task/project business deferred and A6/P7/F71 paused.

All57 selected final native/live images were reviewed. All36 SDK selection-index checks across3 environments returned unavailable(-1), while product selection matched; no system AX position proof is claimed. Earlier API26 timeout remains a historical observation. Cold screenshot pending-question bar is the deliberate new async question: old answer visible and completed receipt verified, new question remains unselected as required.

证据：[P4-4o.28 结构化记录](../../../acceptance/mobile-evidence/android-alignment-P4.json#questionShortFieldCheckpoint)。精确阶段提交/远端与树外恢复包另行核对；不改写历史失败。
