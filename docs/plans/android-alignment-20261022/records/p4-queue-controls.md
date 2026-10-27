# P4 原客户端队列、停止、继续与迟到控制

历史过程档案，保留原日期、失败、限定通过与当时的下一步；**当前状态以[计划与进度总表](../progress.md)为准**。旧分支、门槛及优先级不作为当前执行指令。

<a id="progress-592-705"></a>

## 原进度记录 · 原文第 592–705 行

### P4-4f.1 恢复菜单设置一致性

2026-10-24 P4-4f.1：对照iOS的runAgain→send设置回退，Android长按菜单继续/重新运行改为使用当前草稿的已选生成设置，和主按钮继续一致。控制正文仍为明确的新输入；独立草稿正文/文件不发送、不清空，未选择的设置不补造。

旧产品同新测试runner在API37完整3方法分组中2项失败，均准确缺少已选模型；主按钮继续通过，保留失败整组。初次新增测试误用LocalDraftFile.id导致编译失败、原生执行0；修正测试引用manifest.id后才构建/安装，失败日志保留。最终相同产品/runner在API26、新手机API33和API37各4方法，合计12方法/3完整原生辅助分组通过，两份源码冻结。覆盖主继续、长按继续/重启、全部已选模型/强度/权限/速度/计划字段、独立正文/本地文件、原意图摘要、空设置及两次新操作ID。未调用真实模型。

本项为原生HTTP/存储辅助证明，新增模型0，总33、P4累计20条不重放；真实队列/取消/追加/停止/恢复/重启、旧菜单回调与全视觉仍待后续。iOS源码未改，其设置回退仅作源码对照，不补签iOS验收。JS/Host/KMP未改，按相同组件复用前检查点59条完整检查374.90秒，未宣称在新Android包重跑完整pnpm check。Android构建及当前一致性检查分别执行。完整P3/P4、F34/F35/F36/F39/F40/F43/F46保持开放；蜂窝/VPN与人工归档待验收，任务/项目管理暂缓，A6/P7暂停。

### P4-4f.2 原生队列局部证明与发送后设置丢失

2026-10-24 P4-4f.2：新增独立真机原生队列验收器，原输入/队列/取消使用独立一次租约；原Electron owner与官方元数据队列分别只读核对。零输入预检通过；真实整组未通过，发现发送后生成设置被清除的产品差异，优先修复后继续。

首轮验收器新建ID绑定错误，原输入确已消费一次；原失败与只读核对保留，原任务已完成封存。零写入恢复因原任务已结束而拒绝继续，无新输入。新的独立场景绑定成功，但验收器空草稿先选排队按钮失败；修正操作顺序后仅继续原仍活动任务，未重发首条。一项排队输入经官方native queue进入第二个原Turn并完成；第二项省略model/effort/permission/speed等已选字段，被严格验收代理在Host之前拒绝。原生记录导出确认该第二项仍为uncertain，原ID/意图/摘要保留，未用404补签未接受、未重新提交。完整队列编号/中间取消/追加/FIFO验收均未关闭。

新增消费3条：首个已封存原输入1、后一个原输入1及其实际执行的首个队列输入1；P4累计23、全部36条，不重复计只读观察。两个当前原Turn均completed、readOnly/on-request/xhigh/priority，官方队列为空，未发生本次权限提升。4个失败原生整组、两次零输入预检和当前零POST原生只读记录方法1项分别留证。Android产品/Host源码未改；严格设置门禁没有为通过而放宽。

复核完整iOS发送路径：submitted/completed回执清除生成字段，queued回执保留；confirmed首条新建也清除原占位草稿。Android当前对queued也删除generation，下一步仅修复排队确认后的保留，维持普通回复/首条新建重置语义，补连续排队与迟到回执边界，再继续队列及停止/恢复。新增取消/追加/完整FIFO/控制和F34/F35/F36/F37/F38/F39/F40/F43/F44/F46仍开放。只读窗口1张已查看，局部状态不计全视觉。任务/项目管理暂缓，A6/P7暂停，蜂窝/VPN和人工归档待验收；27节点/20功能及完整P3/P4不宣称完成。

P4-4f.2 full workspace check: 59 commands passed in 362.78 seconds; both examiner sources stable. Initial 9.10-second lint failure retained; only unused iteration binding and redundant quote escapes corrected. Full real queue failures remain failures; the one native read-only export passed separately.

### P4-4f.3 排队回执设置保留

2026-10-24 P4-4f.3：修复Android排队确认后清空已选生成设置。严格对照iOS完整发送路径，仅queued回执保留模型、推理档位、权限、速度及协作模式；普通submitted/completed回复与confirmed首条新建仍重置，原目标/草稿版本/脱离等待保护保持。

旧产品同一新增8方法runner在API37整组2项失败：连续排队与切换聊天后迟到的queued回执均丢失全部已选字段；另外6项通过，原失败整组保留。最终相同产品和runner在API26、新手机API33及API37各8项，合计24方法/3完整原生辅助分组通过，三份源码冻结。覆盖连续队列的完整请求字段/不同操作ID、迟到回执两目标独立设置和正文、普通回复与首条新建重置，以及既有继续/重启/独立本地附件/原意图摘要边界。

新增真实输入0，P4累计23、总36条，既有不确定队列原意图已导出且不改写/不重放。真实队列验收器在confirmed首条新建后显式选择首次排队设置，符合双端重置语义；尚未新增真实队列通过。下一步执行独立全新场景的编号/中间取消/重排/运行中追加/FIFO核对，再处理停止/恢复/重启与旧回调。JS/Host/KMP源码未变，按匹配组件复用前检查点59条根检查362.78秒，未宣称新Android包重跑全组。iOS仅源码对照、未新增原生验收；完整P3/P4及功能状态保持开放。蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停，P6视觉与P9依赖保持。

### P4-4f.4 真实原生队列、取消、追加与顺序

2026-10-24 P4-4f.4：修复后的独立Android真机原生队列场景整组通过，当前原生方法1项与CLI均通过；产品及验收源码未再修改。零输入预检另行通过后，新场景仅使用全新不可变输入及原操作ID，不重放前36条。

三项排队均来自官方native queue，准确原Electron owner和官方元数据队列独立核对ID、顺序和正文。手机显示1/2/3；取消准确中间项后只余原1/3并重编号1/2。运行中追加进入原Turn，原sleep命令自然completed/exitCode=0；其后两项依原1→3顺序完成。规范历史恰好4条实际输入，取消项没有对应Turn；三个Turn均completed且默认模型/极高/快速/原readOnly/on-request设置匹配，原回执与不可变意图摘要核对，官方队列最终为空；没有文件变更或其他工具任务。

本项新增实际输入4（主输入、两条最终执行队列、同Turn追加），取消项不计；P4累计27、全部40条。零预检0POST，实际7项具名业务POST含创建准备/确认、三排队、一次取消、一次追加；无拒绝写入/读取，原6个临时端口及reverse均独立核实关闭，正式服务未动。6张App独有窗口图已查看，编号/取消/追加/最终回复只是局部滚动状态，P6全视觉未关闭。旧失败整组、旧不确定意图及前异步命令终态未验证仍原样保留，本次不补签旧失败。立即执行队列、跨端并发及停止/恢复/重启、旧回调等剩余F34/F35/F36/F37/F38/F39/F40/F43/F44/F46与完整P3/P4仍开放。iOS/Windows未新增真实验收；蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

### P4-4f.5 旧控制回调的原目标与版本

2026-10-24 P4-4f.5：Android发送/停止/继续/重启按钮与长按菜单捕获显示时的完整目标、客户端连接版本/信任、导航选择、原Turn/状态、动作及草稿版本。执行前重新核对；旧回调静默拒绝，菜单不跨上下文保留。业务方法同样校验原上下文，当前新手势仍按原规则执行。

同一5方法原生测试在旧产品API37复现5项错误：A继续误发B、A→B→A旧重启、新Turn被旧停止中断、旧继续使用后来修改的权限设置、旧发送提交替换正文。初轮仅因合成Host名称不含fixture而被既有共享许可测试前置拒绝，5个setup失败单列保留；只修正测试命名，没有放宽许可。最终相同测试与8项设置/草稿回归在API26、新手机API33、API37各13项全部通过，共39方法/3完整原生辅助组，四份源码冻结；当前停止和发送正向点击、所有继续/重启/排队设置与独立附件回归保持。

新增模型0，P4累计27、全部40条保持不重放。此项仅原生边界辅助验收，先前真实队列按原产品/runner指纹保存，不能直接补签新包真实通过。JS/Host/KMP未变，匹配组件复用59条根检查362.78秒，当前Android构建/原生与一致性检查分别执行。iOS目前仅按调用时当前上下文检查stop/runAgain，延迟UI回调同类风险需独立复核；未改iOS源码、未新增iOS原生通过。停止请求的迟到成功/失败、重连及并发忙碌归属和真实停止/恢复/重启继续；完整F34/F35/F36/F39/F40/F43/F46与P3/P4开放，P6视觉后续。蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停，P9依赖保持。

### P4-4f.6 停止请求的异步结果归属

2026-10-24 P4-4f.6：Android停止请求改为独立owner管理，忙碌状态绑定原完整目标、连接版本和Turn；同Turn未决停止仍去重，新Turn可发起独立停止。原请求仅在原选择/导航/连接/Turn且仍持有owner时更新界面；旧finally不得清除新owner，迟到失败不覆盖较新的错误，取消协程不转成业务错误。

旧产品API37同一10方法原生分组4项失败：迟到失败覆盖往返导航后的新错误、同目标较新错误被覆盖、旧停止忙碌阻挡新Turn停止、迟到成功刷新返回后的选择；其余6项通过，原失败整组保留。最终10项回调/停止归属与8项设置/草稿回归在API26、新手机API33、API37各18项全部通过，共54方法/3完整原生辅助组、四份源码冻结。真实挂起响应后分别检查不刷新/不覆盖、旧完成不解除新停止忙碌、当前失败仍显示，以及同Turn往返导航后仍仅发起一次停止；当前停止/发送及继续/重启/队列设置回归保持。

新增真实输入0，P4累计27、全部40条不重放。该项为原生接口/存储/界面辅助证据；真实队列保持其旧指纹，当前产品尚待全新停止/恢复/重启的真实模型与原桌面核对。JS/Host/KMP未变，匹配组件复用59条根检查362.78秒，Android当前构建/54项原生及一致性检查分别执行。iOS停止仍使用targetKey与布尔忙碌集合，同类迟到结果边界需独立复核；未更改iOS、未新增iOS/Windows真实通过。完整F34/F35/F36/F39/F40/F43/F46与P3/P4保持开放，P6视觉继续；蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

### P4-4f.7 真实停止场景的首条创建确认差异

2026-10-24 P4-4f.7：新增当前独立真机停止/主按钮继续/菜单重启验收器，限定完整目标、不可变输入、准确原Turn停止和一次租约；产品源码未改。当前零输入预检原生1项/CLI通过，实际原生整组与CLI失败，未发出停止、继续或重启，不宣称真实控制通过。

实际首条已被原Electron owner消费且仅一次，原命令completed/exitCode=0、规范历史1条；原操作与输入封存不重放。Native等待运行状态180秒超时；导出的唯一创建记录为prepared，但submissionAttempted=true且意图摘要匹配。Host原创建为outcome-unknown，当前只读核对准确owner、项目、目录、原clientId及完整首条内容均匹配。该状态差异需继续复现回执顺序，未把当前匹配结果补签此前已确认。原CLI因未绑定公开threadId报告新增0，原报告保留；独立只读副记录校正本次实际消费1，P4累计28、全部41条。

完整pnpm check的59条在两份验收源码冻结下通过357.03秒；初次10.06秒因新原生测试未登记功能关联而失败，补登记且不改校验器后重跑。此前首次新工具lint因未使用助手失败，也保留。5张App独有窗口图已查看，只是未发送草稿/许可前后状态，实际停止画面未取得，P6全视觉开放。原生完整失败组、原意图/已提交标记与Host回执分别保存；6个临时端口及reverse独立核对关闭，正式服务未动。下一步先修复验收器原输入计数，并以原记录零写入恢复、合成迟到prepared回执反例核对状态回退；完成检查点后再继续真实停止/恢复/重启。iOS/Windows本次未新增真实结果；F26/F28/F34/F35/F36/F39/F40/F43/F44/F46及完整P3/P4开放。蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

### P4-4f.7.1 创建回执顺序与原记录零写入恢复

2026-10-24 P4-4f.7.1：修复Android创建回执顺序：已尝试提交后，迟到prepared回执仍严格验证原Host/操作/项目，但不覆盖原提交状态、结果或错误，也不使原查询观察者提前退出。首次提交前的prepared正常接受；新的权威outcome-unknown/confirmed继续生效，原意图和已提交标记保持。验收器最后只读计数可从准确自有broker原记录绑定未公开threadId，不能授权任何后续输入/停止/打开；实际未绑定前计数为未知，不再预填0。

同一6方法原生测试在旧API37完整组4项失败，包括实际挂起HTTP准备读取晚于新提交回执返回后覆盖状态；其余2项通过。修复后6项包含于43项扩展组，覆盖加密重开、全部未决阶段、脱离等待、错目标拒绝、后续权威结果，以及19项命令/创建/恢复和18项控制/设置边界。当前API26与真机各43通过；API37最初43项有1项首次创建后回复超时，同包整组重跑又在菜单用例挂起并180秒退出，两个失败完整/部分组均保留。仅正常冷重启本任务独立API37，保留磁盘、配置/内存/GPU/端口且不wipe；同源码/产品/runner冷启动43项通过。三环境匹配组件链129项/3完整通过组，不称一次无中断全绿，最初超时/挂起的底层原因尚未确认。

全新runner在新产品包上，对前一真实失败的同一个原操作执行真机只读恢复，原生1项与CLI通过。严格GET代理下业务POST/拒绝写入/新增模型均0；同Host ID、令牌保持，原记录prepared/已尝试经原API查询为confirmed，操作ID/正文/意图摘要不变，规范历史前后各1条、原SDK Turn仍completed。独立临时Host和reverse已核实关闭，正式服务未改。原生窗口1张已查看，仅局部原会话/最终回复/空输入，不补签原失败或完整视觉。当前新父验收器未触发新的失败场景，最后只读计数补强属于已实现工具边界，旧CLI原计数0仍由独立副记录校正为1。

本项新增真实输入0，P4累计28、全部41条均不重放。当前完整59条pnpm check在四份变更源码冻结下通过；Android当前构建、129项组件链与原记录0写入恢复分别留证。iOS/Windows未新增原生验收；iOS相同创建/读取顺序仍需独立复核。下一步提交本检查点后执行全新独立停止/主继续/菜单重启场景；真实控制、完整F26/F28/F34/F35/F36/F39/F40/F43/F44/F46及P3/P4、P6视觉均开放。蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

### P4-4f.7.2 真实原Turn停止、主继续与菜单重启

2026-10-24 P4-4f.7.2：当前独立Android真机首条创建→准确原Turn停止→主按钮继续→长按菜单重新运行完整场景通过，原生1项与CLI均通过。新场景使用全新Host/原操作与输入租约，先前41条封存不重发，产品与验收源码未再修改。

当前零输入预检另行原生1项/CLI通过且业务POST为0；实际仅5项具名POST（准备、确认、停止、继续回复、重启回复），无拒绝读取或写入。原桌面owner和规范历史独立核对恰好3条输入、3个不同Turn：原Turn interrupted，继续与重启两个Turn completed且各自最终回复准确；停止只命中准确原Turn一次。3个Turn均默认模型/极高/快速/readOnly/on-request设置匹配，后两个无工具或文件变更；原保存意图/摘要与每个实际请求逐项一致，当前新草稿为空时发送禁用。

本项新增实际输入3，P4累计31、全部44条；原失败场景、旧CLI计数校正及原记录零写入恢复不改写为此前已通过。原SDK命令条目在最终独立快照仍inProgress/exitCode=null，命令/OS进程终态单列未验证；停止通过范围是原Turn中断，不能据任务中断或UI命令组“已停止”文案补签工具终态，P6需复核该投影文案。6张App独有窗口图已查看，仅局部运行/中断/继续/重启与许可前后，非全视觉。6个临时端口及reverse已独立核对关闭，正式服务未动。当前同代码匹配复用59条根检查357.36秒，当前实际包与129项原生组件链指纹一致；不新增iOS/Windows通过。下一步零模型复现同聊天两个已完成任务之间的旧菜单终态身份绑定，及继续队列立即执行、跨端审批/剩余状态。完整F26/F28/F34/F35/F36/F39/F40/F43/F44/F46/F48与P3/P4/P6开放；蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

### P4-4f.8 已完成任务菜单的用户与Run身份

2026-10-24 P4-4f.8：补齐Android已完成任务的动作身份。除活动Turn外，发送/停止/继续/重启上下文同时捕获最近用户消息ID与Run ID；同一聊天内两个任务都idle且活动Turn为空时，旧菜单也不能作用于后来完成的新任务。同任务正文呈现刷新不改变ID时仍允许动作，新任务的新菜单保持正常。未新增wire字段或模型输入。

同一4方法原生测试在旧API37完整组2项失败：旧重启误作用于同聊天的新已完成任务、同用户条目但新Run的旧继续仍发送；两个正常路径通过，原组保留。当前相同4项与既有18项控制/设置回归在API26、新手机API33及API37各22项全部通过，共66方法/3完整分组、5份回归源码冻结。覆盖空活动Turn、原用户/Run身份、纯呈现刷新、新菜单、既有活动Turn/切换/草稿/迟到停止/设置及独立附件。

本轮最初同新产品的22项API37组在首次创建确认超时，另两环境通过，原失败组保留。源码核对发现该旧用例默认夹具可能在收到submit前就返回confirmed。只收紧该用例配置：实际收到唯一submit请求后才允许confirmed，新增已尝试标记/单次提交断言；首次发送及新聊天跟随回复均等待真实按钮可用，没有加长10秒等待或删除断言。重新构建runner后本轮三环境整组通过；不据此补签前轮超时/挂起底层原因。

新增实际输入0，P4累计31、全部44条均不重放。JS/Host/KMP未变，按相同组件复用上一检查点59条根检查357.36秒；当前Android构建/66项原生和一致性检查分别执行，上一真实控制按原包指纹保留，不宣称新包已跑全部真实场景。对上轮停止原命令追加0输入只读复核：规范历史仍3条、原Turn仍interrupted，原SDK命令后来completed/exitCode=0；这是晚到终态，不证明中断瞬间命令或OS进程已经结束。UI命令组“已停止”措辞继续在P6复核，旧异步命令的独立终态门槛仍开放。iOS终态任务身份/同类回调需独立复核，未新增iOS/Windows原生通过。下一步继续原生队列立即追加/执行、跨端审批和剩余P4断言；完整F34/F35/F36/F39/F40/F43/F44/F46/F48与P3/P4/P6保持开放。蜂窝/VPN及人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

### P4-4f.9 原队列立即追加与原ID执行

2026-10-24 P4-4f.9：Android真机队列条目“立即追加”完整专项通过。验收工具增加独立新场景，限三个不可变输入、准确原运行Turn和一次原队列执行；原生UI实际点击队列行，Host使用同一原消息ID。产品/Host/Adapter/KMP生产源码未改，不重放此前44条。

API26、真机API33和API37各24项原生辅助检查通过，共72方法/3完整分组，覆盖五项队列动作UI与19项命令边界，5份源码冻结。当前零输入预检原生1项与CLI通过、0业务POST；真实原生1项与CLI整组通过。官方native queue独立读取两条准确原ID、正文和顺序，手机编号1/2；点击首条“立即追加”后，原消息ID进入原运行Turn，另一条仍排队并重编号1。原sleep自然结束并由SDK证明completed/exitCode=0；原Turn最终回复追加标记，余项随后在第二个Turn完成。规范历史恰好3条输入、两个completed Turn，队列最终空；原不可变意图与摘要未变、原队列mode未改，未新增另一个显式追加输入。

本轮新增实际输入3，P4累计34、全部47条；排队转追加只计原输入一次。真实5项业务POST为创建准备/确认、两条排队和一次execute，零拒绝读取/写入；6个独立临时端口及reverse核实关闭，正式服务未动。59条根检查357.37秒本次全部通过且2份修改源码冻结。8张App独有窗口图已查看，仅局部分享/编号/追加/结果状态，不关闭P6整体视觉。空闲态队列“立即执行”、跨端审批抢答/失效、未知结果结束等待及生命周期等剩余断言继续开放；完整F37/F38/F43/F44/F46和P3/P4/P6均未整体验收通过。旧失败与未知原记录保留，旧异步命令终态及停止瞬间的工具进程语义不在本项补签。iOS/Windows未新增原生或真实通过；蜂窝/VPN与人工归档单列待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。下一步先补跨端审批/问题失效与其余P4源节点断言。

### P4-4f.10 审批与异步问题的原Turn等待归属

2026-10-24 P4-4f.10：Android审批提交忙碌状态改为按准确目标、线程、原请求Turn及审批ID隔离。新任务复用审批ID时，旧提交不锁住新审批/问题；旧完成不解除新请求的等待。保持同一原请求跨导航去重，异步问题所属原Turn不因当前任务Turn前进而改变。未修改wire或持久化Schema，也未重放任何原审批/输入。

相同5项新原生测试在旧API37产品整组2项失败：新任务同ID审批/问题被旧等待禁用；其余3项通过，旧组原样保留。修复后5项与既有7项审批/问题UI/HTTP/Keystore回归，在API26、新手机API33、API37各12项全通过，共36方法/3完整分组，3份回归源码冻结。覆盖原请求ID+Turn、迟到成功/错误、同原请求导航返回、异步任务继续运行的去重及既有本地选择、密码输入语义、缓存只编辑、正文/Schema变化和真实原生等待控件；本轮均为合成请求辅助检查。

新测试最初编译因字符串与in/to缺少空格失败；随后误调用的旧runner不包含新类，只有初始化失败，未执行5个新方法。该编译日志和旧runner初始化失败组均保留，不算产品反例；修正语法后先确认BUILD SUCCESSFUL，再运行有效旧产品5方法反例及当前回归。旧/新有效组的5方法源码摘要一致，未放宽产品断言。

本轮新增实际模型输入0，P4累计34、全部47条封存不重放。当前Android构建及36项原生辅助通过；JS/Host/Adapter/KMP未改，按匹配组件复用上一检查点59条根检查357.37秒，当前一致性检查另行执行；此前“立即追加”真实场景仍按原验收包指纹保存，不据此宣称新包所有真实场景通过。iOS源码仍以目标与审批ID记录等待，同类复用需独立复核，未修改iOS或增加iOS/Windows真实证明。真实跨端审批先处理/失效、异步问题能力条件及其余源节点仍开放，完整F41/F42/F46与P3/P4/P6不关闭。截图仅为辅助测试捕获附件，不新增全视觉签字；蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。下一步继续真实跨端审批失效与各源节点剩余断言。

### P4-4f.11 第二具名客户端处理后的原生审批失效

2026-10-24 P4-4f.11：真实Electron原请求由第二个具名HTTP客户端先处理后，Android权威刷新移除原审批，保留的原回调不取得等待所有权且未发出重复审批POST；独立草稿保持。随后全新输入在原生手机上准确允许其新审批，原命令实际执行成功。此项是具名HTTP客户端竞争及手机失效，不称为桌面UI操作。未改产品、Host、Adapter或KMP生产源码。

当前零输入预检原生1项与CLI通过，0模型/审批写入。真实原生两次完整方法与CLI整组通过，只有两个全新模型输入：首个原工具请求准确绑定Thread/Turn/请求ID/cwd/正文后由第二客户端处理一次；手机只读刷新后旧控件消失、原业务回调不能发出命令。首项按实际cancel记录interrupted，命令未成功执行；第二项手机原生点击当前accept，任务completed，SDK命令completed/exitCode=0且输出与限定printf标记一致。原桌面owner和规范历史独立核对恰好2条输入，默认模型/极高/快速/readOnly/on-request设置匹配，原意图摘要未变，原审批最终为空。

本轮新增实际输入2，P4累计36、全部49条；此前47条及所有已处理审批均不重放。5项真实业务写入分列为手机代理4POST和第二客户端1POST；手机仅处理第二条审批，首条旧回调零POST。零拒绝读取/写入，6个独立临时端口及reverse核对关闭，正式服务未动。本次59条根检查356.12秒通过，2份修改源码冻结；Android构建通过，既有36项审批原生辅助按相同产品/组件源码匹配复用，新增runner不冒充重跑整组。10张App独有窗口图已查看，仅局部审批/失效/结果状态，P6全视觉未关闭。未制造第二次过期审批网络提交，未声称验证刷新前竞态、桌面UI抢答、所有问题类型或完整功能；未知首次绑定的终审计数护栏属于实现，未在本新场景强行制造失败来扩大通过。F41/F43/F44/F46及完整P3/P4仍开放，旧失败和独立异步命令终态门槛保持；iOS/Windows未新增通过。蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。下一步继续B040/B042/O009等原操作恢复、结束等待及重启的剩余断言。

<a id="progress-1018-1030"></a>

## 原进度记录 · 原文第 1018–1030 行

### P4-2k SDK长队列控制与永久只读入口

2026-10-24 P4-2k队列SDK控制有界验收：代码复制402ee58已先提交推送。队列验收器改为独立Session/存储并保留默认真实草稿、附件和版本，旧全屏截图改为自有MainActivity PixelCopy。新增真实SDK从抽屉边缘垂直滚动有界12条原队列，抵达末条q10并只取消该原ID，核对其余原顺序、原线程和独立草稿。与既有独立并发/去重、当前成员/能力/Turn重核、迟到错误/往返导航隔离、许可后二次明确点击、运行中准确Turn、附件摘要/180dp布局六方法组合，API26/API33真机/API37各7项，共21方法/3组通过；18份自有窗口证据核对归属，9图总览及1张真机原图已复核。永久只读DEV入口仅新增准确原Host且单一hostId的connection-routes GET，拒绝额外/重复参数，全部产品POST仍禁止。当前生产包/新runner两项真机原桌面只读与冷恢复方法、六轮原owner/canonical核对通过，仍原2输入、0新增模型且自有Host均正常退出。68条完整根检查550.86秒通过、822份冻结源码保持；结果文字另做格式/一致性复核。产品APK与代码复制相同，队列生产执行逻辑未改；本轮队列命令只作用于本机合成HTTP夹具，不冒充原桌面真实队列取消。真实原owner长队列/同步失败、Diff多行选区/模型代码回复、系统选择菜单及完整字体/深色/无障碍视觉仍开放。源节点和完整P4不关闭，64条原模型输入封存不变，任务/项目管理暂缓，蜂窝/VPN/人工归档待验与A6/P7/语音暂停不变。

后续仍按以下顺序推进：

1. F19/F20 的实际新模型代码回复复制、Diff多行选区/复制、长/波浪/嵌套围栏和系统选择菜单；本轮标准三反引号原生36项保留，不扩大为所有格式通过。
2. O012/O083/O090 的原owner超过30条完整历史/修订补读、真实队列同步故障与旧队列只读核对；本轮12条SDK夹具不替代这一门槛。
3. B017/O028/O029 的实际权限/父子作用域、附件系统Picker和模式/后台细分；业务任务和项目管理继续暂缓。
4. B015/B018/B019 的计划、审批、异步问题版本和被动迟到回调，以及完整视觉/字体/深色/无障碍；保持已通过和未通过批次的独立指纹。

蜂窝/VPN与人工归档继续单列；A6/P7/语音验收暂停、P9依赖保持，不阻塞上述本机工作。原64条输入不追加或重放。

<a id="plan-329-414"></a>

## 原计划记录 · 原文第 329–414 行

2026-10-24 P4-4f.1：对照iOS的runAgain→send设置回退，Android长按菜单继续/重新运行改为使用当前草稿的已选生成设置，和主按钮继续一致。控制正文仍为明确的新输入；独立草稿正文/文件不发送、不清空，未选择的设置不补造。

旧产品同新测试runner在API37完整3方法分组中2项失败，均准确缺少已选模型；主按钮继续通过，保留失败整组。初次新增测试误用LocalDraftFile.id导致编译失败、原生执行0；修正测试引用manifest.id后才构建/安装，失败日志保留。最终相同产品/runner在API26、新手机API33和API37各4方法，合计12方法/3完整原生辅助分组通过，两份源码冻结。覆盖主继续、长按继续/重启、全部已选模型/强度/权限/速度/计划字段、独立正文/本地文件、原意图摘要、空设置及两次新操作ID。未调用真实模型。

本项为原生HTTP/存储辅助证明，新增模型0，总33、P4累计20条不重放；真实队列/取消/追加/停止/恢复/重启、旧菜单回调与全视觉仍待后续。iOS源码未改，其设置回退仅作源码对照，不补签iOS验收。JS/Host/KMP未改，按相同组件复用前检查点59条完整检查374.90秒，未宣称在新Android包重跑完整pnpm check。Android构建及当前一致性检查分别执行。完整P3/P4、F34/F35/F36/F39/F40/F43/F46保持开放；蜂窝/VPN与人工归档待验收，任务/项目管理暂缓，A6/P7暂停。

2026-10-24 P4-4f.2：新增独立真机原生队列验收器，原输入/队列/取消使用独立一次租约；原Electron owner与官方元数据队列分别只读核对。零输入预检通过；真实整组未通过，发现发送后生成设置被清除的产品差异，优先修复后继续。

首轮验收器新建ID绑定错误，原输入确已消费一次；原失败与只读核对保留，原任务已完成封存。零写入恢复因原任务已结束而拒绝继续，无新输入。新的独立场景绑定成功，但验收器空草稿先选排队按钮失败；修正操作顺序后仅继续原仍活动任务，未重发首条。一项排队输入经官方native queue进入第二个原Turn并完成；第二项省略model/effort/permission/speed等已选字段，被严格验收代理在Host之前拒绝。原生记录导出确认该第二项仍为uncertain，原ID/意图/摘要保留，未用404补签未接受、未重新提交。完整队列编号/中间取消/追加/FIFO验收均未关闭。

新增消费3条：首个已封存原输入1、后一个原输入1及其实际执行的首个队列输入1；P4累计23、全部36条，不重复计只读观察。两个当前原Turn均completed、readOnly/on-request/xhigh/priority，官方队列为空，未发生本次权限提升。4个失败原生整组、两次零输入预检和当前零POST原生只读记录方法1项分别留证。Android产品/Host源码未改；严格设置门禁没有为通过而放宽。

复核完整iOS发送路径：submitted/completed回执清除生成字段，queued回执保留；confirmed首条新建也清除原占位草稿。Android当前对queued也删除generation，下一步仅修复排队确认后的保留，维持普通回复/首条新建重置语义，补连续排队与迟到回执边界，再继续队列及停止/恢复。新增取消/追加/完整FIFO/控制和F34/F35/F36/F37/F38/F39/F40/F43/F44/F46仍开放。只读窗口1张已查看，局部状态不计全视觉。任务/项目管理暂缓，A6/P7暂停，蜂窝/VPN和人工归档待验收；27节点/20功能及完整P3/P4不宣称完成。

2026-10-24 P4-4f.3：修复Android排队确认后清空已选生成设置。严格对照iOS完整发送路径，仅queued回执保留模型、推理档位、权限、速度及协作模式；普通submitted/completed回复与confirmed首条新建仍重置，原目标/草稿版本/脱离等待保护保持。

旧产品同一新增8方法runner在API37整组2项失败：连续排队与切换聊天后迟到的queued回执均丢失全部已选字段；另外6项通过，原失败整组保留。最终相同产品和runner在API26、新手机API33及API37各8项，合计24方法/3完整原生辅助分组通过，三份源码冻结。覆盖连续队列的完整请求字段/不同操作ID、迟到回执两目标独立设置和正文、普通回复与首条新建重置，以及既有继续/重启/独立本地附件/原意图摘要边界。

新增真实输入0，P4累计23、总36条，既有不确定队列原意图已导出且不改写/不重放。真实队列验收器在confirmed首条新建后显式选择首次排队设置，符合双端重置语义；尚未新增真实队列通过。下一步执行独立全新场景的编号/中间取消/重排/运行中追加/FIFO核对，再处理停止/恢复/重启与旧回调。JS/Host/KMP源码未变，按匹配组件复用前检查点59条根检查362.78秒，未宣称新Android包重跑全组。iOS仅源码对照、未新增原生验收；完整P3/P4及功能状态保持开放。蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停，P6视觉与P9依赖保持。

2026-10-24 P4-4f.4：修复后的独立Android真机原生队列场景整组通过，当前原生方法1项与CLI均通过；产品及验收源码未再修改。零输入预检另行通过后，新场景仅使用全新不可变输入及原操作ID，不重放前36条。

三项排队均来自官方native queue，准确原Electron owner和官方元数据队列独立核对ID、顺序和正文。手机显示1/2/3；取消准确中间项后只余原1/3并重编号1/2。运行中追加进入原Turn，原sleep命令自然completed/exitCode=0；其后两项依原1→3顺序完成。规范历史恰好4条实际输入，取消项没有对应Turn；三个Turn均completed且默认模型/极高/快速/原readOnly/on-request设置匹配，原回执与不可变意图摘要核对，官方队列最终为空；没有文件变更或其他工具任务。

本项新增实际输入4（主输入、两条最终执行队列、同Turn追加），取消项不计；P4累计27、全部40条。零预检0POST，实际7项具名业务POST含创建准备/确认、三排队、一次取消、一次追加；无拒绝写入/读取，原6个临时端口及reverse均独立核实关闭，正式服务未动。6张App独有窗口图已查看，编号/取消/追加/最终回复只是局部滚动状态，P6全视觉未关闭。旧失败整组、旧不确定意图及前异步命令终态未验证仍原样保留，本次不补签旧失败。立即执行队列、跨端并发及停止/恢复/重启、旧回调等剩余F34/F35/F36/F37/F38/F39/F40/F43/F44/F46与完整P3/P4仍开放。iOS/Windows未新增真实验收；蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.5：Android发送/停止/继续/重启按钮与长按菜单捕获显示时的完整目标、客户端连接版本/信任、导航选择、原Turn/状态、动作及草稿版本。执行前重新核对；旧回调静默拒绝，菜单不跨上下文保留。业务方法同样校验原上下文，当前新手势仍按原规则执行。

同一5方法原生测试在旧产品API37复现5项错误：A继续误发B、A→B→A旧重启、新Turn被旧停止中断、旧继续使用后来修改的权限设置、旧发送提交替换正文。初轮仅因合成Host名称不含fixture而被既有共享许可测试前置拒绝，5个setup失败单列保留；只修正测试命名，没有放宽许可。最终相同测试与8项设置/草稿回归在API26、新手机API33、API37各13项全部通过，共39方法/3完整原生辅助组，四份源码冻结；当前停止和发送正向点击、所有继续/重启/排队设置与独立附件回归保持。

新增模型0，P4累计27、全部40条保持不重放。此项仅原生边界辅助验收，先前真实队列按原产品/runner指纹保存，不能直接补签新包真实通过。JS/Host/KMP未变，匹配组件复用59条根检查362.78秒，当前Android构建/原生与一致性检查分别执行。iOS目前仅按调用时当前上下文检查stop/runAgain，延迟UI回调同类风险需独立复核；未改iOS源码、未新增iOS原生通过。停止请求的迟到成功/失败、重连及并发忙碌归属和真实停止/恢复/重启继续；完整F34/F35/F36/F39/F40/F43/F46与P3/P4开放，P6视觉后续。蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停，P9依赖保持。

2026-10-24 P4-4f.6：Android停止请求改为独立owner管理，忙碌状态绑定原完整目标、连接版本和Turn；同Turn未决停止仍去重，新Turn可发起独立停止。原请求仅在原选择/导航/连接/Turn且仍持有owner时更新界面；旧finally不得清除新owner，迟到失败不覆盖较新的错误，取消协程不转成业务错误。

旧产品API37同一10方法原生分组4项失败：迟到失败覆盖往返导航后的新错误、同目标较新错误被覆盖、旧停止忙碌阻挡新Turn停止、迟到成功刷新返回后的选择；其余6项通过，原失败整组保留。最终10项回调/停止归属与8项设置/草稿回归在API26、新手机API33、API37各18项全部通过，共54方法/3完整原生辅助组、四份源码冻结。真实挂起响应后分别检查不刷新/不覆盖、旧完成不解除新停止忙碌、当前失败仍显示，以及同Turn往返导航后仍仅发起一次停止；当前停止/发送及继续/重启/队列设置回归保持。

新增真实输入0，P4累计27、全部40条不重放。该项为原生接口/存储/界面辅助证据；真实队列保持其旧指纹，当前产品尚待全新停止/恢复/重启的真实模型与原桌面核对。JS/Host/KMP未变，匹配组件复用59条根检查362.78秒，Android当前构建/54项原生及一致性检查分别执行。iOS停止仍使用targetKey与布尔忙碌集合，同类迟到结果边界需独立复核；未更改iOS、未新增iOS/Windows真实通过。完整F34/F35/F36/F39/F40/F43/F46与P3/P4保持开放，P6视觉继续；蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.7：新增当前独立真机停止/主按钮继续/菜单重启验收器，限定完整目标、不可变输入、准确原Turn停止和一次租约；产品源码未改。当前零输入预检原生1项/CLI通过，实际原生整组与CLI失败，未发出停止、继续或重启，不宣称真实控制通过。

实际首条已被原Electron owner消费且仅一次，原命令completed/exitCode=0、规范历史1条；原操作与输入封存不重放。Native等待运行状态180秒超时；导出的唯一创建记录为prepared，但submissionAttempted=true且意图摘要匹配。Host原创建为outcome-unknown，当前只读核对准确owner、项目、目录、原clientId及完整首条内容均匹配。该状态差异需继续复现回执顺序，未把当前匹配结果补签此前已确认。原CLI因未绑定公开threadId报告新增0，原报告保留；独立只读副记录校正本次实际消费1，P4累计28、全部41条。

完整pnpm check的59条在两份验收源码冻结下通过357.03秒；初次10.06秒因新原生测试未登记功能关联而失败，补登记且不改校验器后重跑。此前首次新工具lint因未使用助手失败，也保留。5张App独有窗口图已查看，只是未发送草稿/许可前后状态，实际停止画面未取得，P6全视觉开放。原生完整失败组、原意图/已提交标记与Host回执分别保存；6个临时端口及reverse独立核对关闭，正式服务未动。下一步先修复验收器原输入计数，并以原记录零写入恢复、合成迟到prepared回执反例核对状态回退；完成检查点后再继续真实停止/恢复/重启。iOS/Windows本次未新增真实结果；F26/F28/F34/F35/F36/F39/F40/F43/F44/F46及完整P3/P4开放。蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.7.1：修复Android创建回执顺序：已尝试提交后，迟到prepared回执仍严格验证原Host/操作/项目，但不覆盖原提交状态、结果或错误，也不使原查询观察者提前退出。首次提交前的prepared正常接受；新的权威outcome-unknown/confirmed继续生效，原意图和已提交标记保持。验收器最后只读计数可从准确自有broker原记录绑定未公开threadId，不能授权任何后续输入/停止/打开；实际未绑定前计数为未知，不再预填0。

同一6方法原生测试在旧API37完整组4项失败，包括实际挂起HTTP准备读取晚于新提交回执返回后覆盖状态；其余2项通过。修复后6项包含于43项扩展组，覆盖加密重开、全部未决阶段、脱离等待、错目标拒绝、后续权威结果，以及19项命令/创建/恢复和18项控制/设置边界。当前API26与真机各43通过；API37最初43项有1项首次创建后回复超时，同包整组重跑又在菜单用例挂起并180秒退出，两个失败完整/部分组均保留。仅正常冷重启本任务独立API37，保留磁盘、配置/内存/GPU/端口且不wipe；同源码/产品/runner冷启动43项通过。三环境匹配组件链129项/3完整通过组，不称一次无中断全绿，最初超时/挂起的底层原因尚未确认。

全新runner在新产品包上，对前一真实失败的同一个原操作执行真机只读恢复，原生1项与CLI通过。严格GET代理下业务POST/拒绝写入/新增模型均0；同Host ID、令牌保持，原记录prepared/已尝试经原API查询为confirmed，操作ID/正文/意图摘要不变，规范历史前后各1条、原SDK Turn仍completed。独立临时Host和reverse已核实关闭，正式服务未改。原生窗口1张已查看，仅局部原会话/最终回复/空输入，不补签原失败或完整视觉。当前新父验收器未触发新的失败场景，最后只读计数补强属于已实现工具边界，旧CLI原计数0仍由独立副记录校正为1。

本项新增真实输入0，P4累计28、全部41条均不重放。当前完整59条pnpm check在四份变更源码冻结下通过；Android当前构建、129项组件链与原记录0写入恢复分别留证。iOS/Windows未新增原生验收；iOS相同创建/读取顺序仍需独立复核。下一步提交本检查点后执行全新独立停止/主继续/菜单重启场景；真实控制、完整F26/F28/F34/F35/F36/F39/F40/F43/F44/F46及P3/P4、P6视觉均开放。蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.7.2：当前独立Android真机首条创建→准确原Turn停止→主按钮继续→长按菜单重新运行完整场景通过，原生1项与CLI均通过。新场景使用全新Host/原操作与输入租约，先前41条封存不重发，产品与验收源码未再修改。

当前零输入预检另行原生1项/CLI通过且业务POST为0；实际仅5项具名POST（准备、确认、停止、继续回复、重启回复），无拒绝读取或写入。原桌面owner和规范历史独立核对恰好3条输入、3个不同Turn：原Turn interrupted，继续与重启两个Turn completed且各自最终回复准确；停止只命中准确原Turn一次。3个Turn均默认模型/极高/快速/readOnly/on-request设置匹配，后两个无工具或文件变更；原保存意图/摘要与每个实际请求逐项一致，当前新草稿为空时发送禁用。

本项新增实际输入3，P4累计31、全部44条；原失败场景、旧CLI计数校正及原记录零写入恢复不改写为此前已通过。原SDK命令条目在最终独立快照仍inProgress/exitCode=null，命令/OS进程终态单列未验证；停止通过范围是原Turn中断，不能据任务中断或UI命令组“已停止”文案补签工具终态，P6需复核该投影文案。6张App独有窗口图已查看，仅局部运行/中断/继续/重启与许可前后，非全视觉。6个临时端口及reverse已独立核对关闭，正式服务未动。当前同代码匹配复用59条根检查357.36秒，当前实际包与129项原生组件链指纹一致；不新增iOS/Windows通过。下一步零模型复现同聊天两个已完成任务之间的旧菜单终态身份绑定，及继续队列立即执行、跨端审批/剩余状态。完整F26/F28/F34/F35/F36/F39/F40/F43/F44/F46/F48与P3/P4/P6开放；蜂窝/VPN和人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.8：补齐Android已完成任务的动作身份。除活动Turn外，发送/停止/继续/重启上下文同时捕获最近用户消息ID与Run ID；同一聊天内两个任务都idle且活动Turn为空时，旧菜单也不能作用于后来完成的新任务。同任务正文呈现刷新不改变ID时仍允许动作，新任务的新菜单保持正常。未新增wire字段或模型输入。

同一4方法原生测试在旧API37完整组2项失败：旧重启误作用于同聊天的新已完成任务、同用户条目但新Run的旧继续仍发送；两个正常路径通过，原组保留。当前相同4项与既有18项控制/设置回归在API26、新手机API33及API37各22项全部通过，共66方法/3完整分组、5份回归源码冻结。覆盖空活动Turn、原用户/Run身份、纯呈现刷新、新菜单、既有活动Turn/切换/草稿/迟到停止/设置及独立附件。

本轮最初同新产品的22项API37组在首次创建确认超时，另两环境通过，原失败组保留。源码核对发现该旧用例默认夹具可能在收到submit前就返回confirmed。只收紧该用例配置：实际收到唯一submit请求后才允许confirmed，新增已尝试标记/单次提交断言；首次发送及新聊天跟随回复均等待真实按钮可用，没有加长10秒等待或删除断言。重新构建runner后本轮三环境整组通过；不据此补签前轮超时/挂起底层原因。

新增实际输入0，P4累计31、全部44条均不重放。JS/Host/KMP未变，按相同组件复用上一检查点59条根检查357.36秒；当前Android构建/66项原生和一致性检查分别执行，上一真实控制按原包指纹保留，不宣称新包已跑全部真实场景。对上轮停止原命令追加0输入只读复核：规范历史仍3条、原Turn仍interrupted，原SDK命令后来completed/exitCode=0；这是晚到终态，不证明中断瞬间命令或OS进程已经结束。UI命令组“已停止”措辞继续在P6复核，旧异步命令的独立终态门槛仍开放。iOS终态任务身份/同类回调需独立复核，未新增iOS/Windows原生通过。下一步继续原生队列立即追加/执行、跨端审批和剩余P4断言；完整F34/F35/F36/F39/F40/F43/F44/F46/F48与P3/P4/P6保持开放。蜂窝/VPN及人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。

2026-10-24 P4-4f.9：Android真机队列条目“立即追加”完整专项通过。验收工具增加独立新场景，限三个不可变输入、准确原运行Turn和一次原队列执行；原生UI实际点击队列行，Host使用同一原消息ID。产品/Host/Adapter/KMP生产源码未改，不重放此前44条。

API26、真机API33和API37各24项原生辅助检查通过，共72方法/3完整分组，覆盖五项队列动作UI与19项命令边界，5份源码冻结。当前零输入预检原生1项与CLI通过、0业务POST；真实原生1项与CLI整组通过。官方native queue独立读取两条准确原ID、正文和顺序，手机编号1/2；点击首条“立即追加”后，原消息ID进入原运行Turn，另一条仍排队并重编号1。原sleep自然结束并由SDK证明completed/exitCode=0；原Turn最终回复追加标记，余项随后在第二个Turn完成。规范历史恰好3条输入、两个completed Turn，队列最终空；原不可变意图与摘要未变、原队列mode未改，未新增另一个显式追加输入。

本轮新增实际输入3，P4累计34、全部47条；排队转追加只计原输入一次。真实5项业务POST为创建准备/确认、两条排队和一次execute，零拒绝读取/写入；6个独立临时端口及reverse核实关闭，正式服务未动。59条根检查357.37秒本次全部通过且2份修改源码冻结。8张App独有窗口图已查看，仅局部分享/编号/追加/结果状态，不关闭P6整体视觉。空闲态队列“立即执行”、跨端审批抢答/失效、未知结果结束等待及生命周期等剩余断言继续开放；完整F37/F38/F43/F44/F46和P3/P4/P6均未整体验收通过。旧失败与未知原记录保留，旧异步命令终态及停止瞬间的工具进程语义不在本项补签。iOS/Windows未新增原生或真实通过；蜂窝/VPN与人工归档单列待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。下一步先补跨端审批/问题失效与其余P4源节点断言。

2026-10-24 P4-4f.10：Android审批提交忙碌状态改为按准确目标、线程、原请求Turn及审批ID隔离。新任务复用审批ID时，旧提交不锁住新审批/问题；旧完成不解除新请求的等待。保持同一原请求跨导航去重，异步问题所属原Turn不因当前任务Turn前进而改变。未修改wire或持久化Schema，也未重放任何原审批/输入。

相同5项新原生测试在旧API37产品整组2项失败：新任务同ID审批/问题被旧等待禁用；其余3项通过，旧组原样保留。修复后5项与既有7项审批/问题UI/HTTP/Keystore回归，在API26、新手机API33、API37各12项全通过，共36方法/3完整分组，3份回归源码冻结。覆盖原请求ID+Turn、迟到成功/错误、同原请求导航返回、异步任务继续运行的去重及既有本地选择、密码输入语义、缓存只编辑、正文/Schema变化和真实原生等待控件；本轮均为合成请求辅助检查。

新测试最初编译因字符串与in/to缺少空格失败；随后误调用的旧runner不包含新类，只有初始化失败，未执行5个新方法。该编译日志和旧runner初始化失败组均保留，不算产品反例；修正语法后先确认BUILD SUCCESSFUL，再运行有效旧产品5方法反例及当前回归。旧/新有效组的5方法源码摘要一致，未放宽产品断言。

本轮新增实际模型输入0，P4累计34、全部47条封存不重放。当前Android构建及36项原生辅助通过；JS/Host/Adapter/KMP未改，按匹配组件复用上一检查点59条根检查357.37秒，当前一致性检查另行执行；此前“立即追加”真实场景仍按原验收包指纹保存，不据此宣称新包所有真实场景通过。iOS源码仍以目标与审批ID记录等待，同类复用需独立复核，未修改iOS或增加iOS/Windows真实证明。真实跨端审批先处理/失效、异步问题能力条件及其余源节点仍开放，完整F41/F42/F46与P3/P4/P6不关闭。截图仅为辅助测试捕获附件，不新增全视觉签字；蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。下一步继续真实跨端审批失效与各源节点剩余断言。

2026-10-24 P4-4f.11：真实Electron原请求由第二个具名HTTP客户端先处理后，Android权威刷新移除原审批，保留的原回调不取得等待所有权且未发出重复审批POST；独立草稿保持。随后全新输入在原生手机上准确允许其新审批，原命令实际执行成功。此项是具名HTTP客户端竞争及手机失效，不称为桌面UI操作。未改产品、Host、Adapter或KMP生产源码。

当前零输入预检原生1项与CLI通过，0模型/审批写入。真实原生两次完整方法与CLI整组通过，只有两个全新模型输入：首个原工具请求准确绑定Thread/Turn/请求ID/cwd/正文后由第二客户端处理一次；手机只读刷新后旧控件消失、原业务回调不能发出命令。首项按实际cancel记录interrupted，命令未成功执行；第二项手机原生点击当前accept，任务completed，SDK命令completed/exitCode=0且输出与限定printf标记一致。原桌面owner和规范历史独立核对恰好2条输入，默认模型/极高/快速/readOnly/on-request设置匹配，原意图摘要未变，原审批最终为空。

本轮新增实际输入2，P4累计36、全部49条；此前47条及所有已处理审批均不重放。5项真实业务写入分列为手机代理4POST和第二客户端1POST；手机仅处理第二条审批，首条旧回调零POST。零拒绝读取/写入，6个独立临时端口及reverse核对关闭，正式服务未动。本次59条根检查356.12秒通过，2份修改源码冻结；Android构建通过，既有36项审批原生辅助按相同产品/组件源码匹配复用，新增runner不冒充重跑整组。10张App独有窗口图已查看，仅局部审批/失效/结果状态，P6全视觉未关闭。未制造第二次过期审批网络提交，未声称验证刷新前竞态、桌面UI抢答、所有问题类型或完整功能；未知首次绑定的终审计数护栏属于实现，未在本新场景强行制造失败来扩大通过。F41/F43/F44/F46及完整P3/P4仍开放，旧失败和独立异步命令终态门槛保持；iOS/Windows未新增通过。蜂窝/VPN、人工归档待验收，任务/项目管理暂缓，A6/P7暂停、P9依赖保持。下一步继续B040/B042/O009等原操作恢复、结束等待及重启的剩余断言。

### P4-4o.27 原 owner 菜单与四审批决策

PR #24 accepted candidate6086bd55 merged into mainc5388cef after workspace/iOS CI success. Guard observed already-merged state and did not replay the merge. Candidate tree and ancestry independently matched; old Android branch deleted locally/remotely, bundle verified outside tree. New branch follows exact main; other worktrees untouched.

iOS canStop/confirmationDialog retains stop while a running task has a new draft. Android had coupled menu visibility to the main composer action, hiding stop when the draft made that action send. Initial test incorrectly sought stop-turn; corrected test used actual send-message and reproduced missing action-stop on old production App. Fix exposes canStop and named stopTask while preserving full ComposerActionContext validation, current Turn, navigation, connection and draft version. No draft is sent.

Final exact runner: 71 methods /28 groups /5 viewport profiles passed; 71 default draft/file/version proofs, 20 exact four-decision HTTP payloads and15 menu native SDK actions. All four declared decision values keep original request order and one POST per case. Native ACTION_CLICK is distinct from screen-coordinate injection and TalkBack speech. Initial runner passed71 methods but did not apply requested menu theme; that result remains historical. Examiner now applies/reads theme and the full71 were rerun with the final runner. API37 landscape7 methods remain unexecuted under user deferral.

Real preflight first lacked required prior report, then an empty current preflight thread was rejected by reader; both0 inputs. Launcher now byte-matches a historical successful zero-input report and only reads its original directory. Current preflight and new physical original-owner stop/continue/restart chain passed with independent desktop/canonical checks:3 new inputs,0 unresolved, owned ports closed. Nonempty running draft specific scene is native synthetic only; this real menu chain had an empty draft. Turn interruption does not prove OS tool-process terminal state. Prior unknown operations remain sealed. Inputs201 total/P4187. Final29 images reviewed; no full feature/stage closure.

Next: long-answer short-IME scroll/selection and field appearance; remaining real four-decision matrix, natural receipt recovery and iOS callback gaps remain open. API37 landscape, cellular/VPN and manual archive pending; task/project business deferred; A6/P7/F71 voice/system work paused.

证据：[P4-4o.27 结构化记录](../../../acceptance/mobile-evidence/android-alignment-P4.json#ownerMenuFourDecisionsCheckpoint)。精确阶段提交/远端与树外恢复包另行核对；不改写历史失败。

### P4-4o.29 非空草稿原菜单真实验收与四决策协议边界

PR27 delivered the prior two accepted feature commits and formatting, preserved current main changes, passed workspace/iOS CI and merged at9b4b3265; candidate tree equality and exact local/remote old branch deletion independently verified. New continuation branch was created before further development.

The strict four-decision examiner orders decline,accept,cancel,acceptForSession(session last) and refuses undeclared decisions. Current original command provider actually declared string accept/cancel plus a typed acceptWithExecpolicyAmendment object. That object is not acceptForSession. First fresh case stopped before any answer because decline was absent:1 model input,0 approval posts; original request remains sealed, not answered/replayed. Existing synthetic four-button/HTTP results remain auxiliary; no full real four-decision pass claimed. Guard17 tests retain exact original target, turn, request ID, command/cwd and no appended shell action.

Added a real nonempty-draft stop examiner. It first creates one bounded sleep task, types its own distinct local draft, long-presses send and selects the exact owned Stop menu row. It verifies the original Turn interrupted, draft identical, composer still Send, one accepted input. Only after recording that proof does it explicitly clear this synthetic local draft, then perform fresh Continue and Restart gestures. Final independent owner/canonical check verifies3 unique original client IDs/3 turns, read-only/xhigh/fast/default settings, first interrupted/later completed, no draft text sent and0 pending owner requests. All9 native/menu frames reviewed; no coordinate-injection pass claimed.

First stop attempt failed in examiner HTTP counting after the actual stop:creation prepare+submit+stop are3 requests, not3 model inputs. Corrected to immutable submitted-operation/claim counts. The failed case retains1 input and is sealed; a fresh independent case then passed3 inputs without resuming/replaying the prior operation. A private read-only post-check first expected inputText instead of desktop text discriminator; corrected reader interpretation and reran GET-only audit, without any product input.

This checkpoint adds5 actual inputs including both failures, totals209/P4195. Original default drafts/files remain protected by isolated native session evidence. API37 landscape, system Copy/AX, unsupported provider decisions and natural receipt recovery remain separately open. Business task/project management remains deferred. Continue P4 callback/recovery, then P5/P6/authorized AndroidP7.

证据：[P4-4o.29 结构化记录](../../../acceptance/mobile-evidence/android-alignment-P4.json#ownerDraftAndDecisionBoundaryCheckpoint)。精确阶段提交/远端与树外恢复包另行核对；不改写历史失败。
