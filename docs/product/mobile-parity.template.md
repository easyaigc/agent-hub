# iOS / Android 功能与一致性清单

2026-11-04 F69 本机部署候选：在正式 R11 对应的规范提交上叠加空日志候选计数修复和 macOS 私有诊断日志，保留正式 Host 已有诊断、恢复和原生 owner 规则。空文件每次复查，增长后从零读取，已跟踪截断仍拒绝；日志记录生命周期、固定原因、阶段、心跳和结果状态。iOS／Android 共用 Host 来源规则，UI／草稿语义保持，Android 建议 UI 与 A6 状态不变。源码、辅助检查、实际部署与建议来源验收分别记录。

2026-11-04 F34／F36、UI07／UI08、S06：按最新交互要求，输入区模型按钮立即预览当前草稿的模型／推理强度／速度，不等发送；未覆盖字段回落到电脑已知值，重置后跟随电脑。设置页“电脑当前设置”独立读取原 owner，不把未发送选择当作运行参数。iOS／Android 共用 KMP 展示规则，沿用准确目标隔离、草稿持久化与提交边界；仅编辑不发送、不改写电脑当前轮或已提交操作。实现、辅助与设备交互结果见[本轮记录](../acceptance/next-turn-generation-display-20261104.md)，既有 A6 暂停范围保持。

2026-11-03 F59／F19／F32：移除独立文件互传与聊天资源下载预览的 20 MiB 固定上限。新 Codex Host 的文件目录以 `maxFileBytes: null` 表示未配置大小上限，iOS 兼容旧 Host 的有限值；Host、电脑发送 CLI 与 iOS／Android 下载不再按 20 MiB 拒绝。仍验证准确目标、原消息授权、普通文件、不可变长度和 SHA-256，未知传输只查询原回执。大文件仍受可用存储、内存、网络及系统格式支持影响；Markdown／HTML 渲染预算不改变下载与分享能力。双端影响、辅助检查、物理设备与真实桌面证据分列于[专项记录](../acceptance/file-size-limits-20261103.md)。

共同断言：手机只读准确原桌面最新完成轮建议，安全抑制/空结果/取消/离线/换目标即清除显示；采用和撤销只修改本地草稿，刷新不生成模型轮。共享 Host 增加被动结果来源、单会话最新正文保存及 Windows 当前可信 CLI 重定位。iOS 复用既有 native-version 接口与来源校验；Android 尚未实现建议 UI，需后续按同一来源、摘要及租期契约实现，不计新增通过。辅助检查、Windows 原生及实体 iPhone 结果分别登记；A6 保持暂停。

2026-11-01 F03／F69 Windows 启动安全：停用会接管原 Codex 后端的全局建议采集垫片安装。旧安装仅解除有本产品私有清单证明且仍指向原 launcher.exe 的 HKCU CODEX_CLI_PATH，保留其他覆盖、私有配置、身份和运行中的进程；config.json 损坏、旧源码摘要或应用路径失效不阻断恢复。iOS／Android 共用此 Windows Host 限制，UI、macOS 路径及 A6 暂停范围保持；Windows 被动结果采集暂不可安装，真实桌面与手机验收分别记于[启动安全记录](../acceptance/windows-codex-startup-safety-20261101.md)。

## F52：按 topic 隔离 APNs 与 EasyWork publisher（2026-11-01）

Relay 源码支持非默认 `topicKeys` 的逐环境密钥，未知 topic 或缺少的环境不回退到正式 topic。Host publisher 可配置 `topic`、`allowedTargets` 和 `environments`；EasyWork 的完整 Agent、Host、workspace、runtime、account authority/subject 在注册、撤销、状态读取、发布与待投递检查中核对。旧 publisher 省略这些字段时沿用原配置，旧绑定缺少 topic 时仅解释为原默认 topic。

iOS 独立 development 验收环境可据此使用单独 Sandbox 授权；Android/FCM 本轮无实现或配置变化，不能复用 iOS 结果。当前新增辅助边界检查与实际部署、Apple 接受、手机到达分别留证；配置完成不代表 F52 全项通过。

2026-10-31 F36／UI07／UI08 速度图标：模型按钮在标准或未确认加速时不显示闪电，快速显示一个蓝色闪电，极速显示两个相邻蓝色闪电；纯图标紧凑布局的标准速度使用设置图标，入口保持可见。iOS 已修改，Android 对应展示待对齐；速度目录、选择与提交语义不变，原生结果见[专项验收记录](../acceptance/speed-indicator-20261031.md)，A6 既有暂停范围保持。

2026-11-01 F26／UI02／UI04：iOS“选择项目”在“不选择项目”后增加“最近使用”，仅在有记录时显示最近五项；显式选项目或打开项目聊天后前移，按完整目标隔离、重启保留、移除项目隐藏。记录只保存在手机本地，Android待对齐；实现、辅助检查和真机结果见[专项记录](../acceptance/recent-projects-20261101.md)，A6其他暂停项保持。

2026-11-01 F19／F32 可视化验收补记：独立 iOS 模拟器原生整例 1 通过／0 失败／0 跳过，覆盖 HTML 显示、交互、双向滚动、关闭与重开；真实原 owner 保持一次输入。真机两次停在系统自动化初始化，用户要求先完成模拟器，物理保持待复验；Android 交互容器未实施。详见 [专项记录](../acceptance/visualization-20261101.md)。

2026-11-01 F19／F32（UI05／UI07／UI12）可视化：Codex 完整 visualize 内容引用映射为可点击文件入口，沿用原消息／完整目标授权、文件摘要及大小校验；iOS 增加独立 HTML 沙箱预览，支持脚本图表、滚动、关闭返回和原文件分享。仅允许固定可视化 CDN，无原生桥、Host 凭据或任意电脑目录访问；Markdown 阅读页 F72 仍不执行其内嵌 HTML。Android 共用 Host 的引用投影，但交互 HTML 预览与旧 Host 缓存指令兼容待实施，不能记为双端通过。实现、辅助与物理 iPhone 结果见[专项记录](../acceptance/visualization-20261101.md)，A6 保持暂停。

2026-10-30 F03／F12／F69／F75：冻结R2的Host运行能力向PR73主线做实际三方集成，保留共享发现、创建租约和既有业务；Native建议/Trace/GC、子任务及只读Git同源保留。当前iOS/Android页面不移植、不新增通过，F75当前main只登记Host兼容与原生计划；R2旧证据保持原上下文，A6暂停。见[集成记录](../acceptance/codex-update-r2-integration-20261030.md)。

2026-10-30 F03／F69：既有 Windows v1 阅读缓存保留 owner 与内容，启用可重建 v2；Native 创建租约衔接 Node FD，释放确认未知不截断。编译产物由保留句柄发布。R2 与 main 尚未统一，旧 owner 包禁止覆盖已部署 R2；本机辅助不替代 Windows Native／真实更新。双端 UI 不变，A6 暂停。见[专项记录](../acceptance/codex-update-resilience-20261030.md)。

2026-10-30 F02／F03／F69：Windows 私有 helper 初始化已定位为高权限 token 默认 Owner 与 User 不同。创建时显式指定 User 与受保护 DACL，既有 owner 严格拒绝，编译和私有原子写同源处理，退出状态保留 DWORD／0／未知。双端共用 Host，原生页面不变，Windows Native、reader 与真实更新待来源独立验收，macOS 辅助不代验，A6 保持暂停。见[专项记录](../acceptance/codex-update-resilience-20261030.md)。

2026-10-29 F64／UI06／UI21 重试样式修订：iOS 失败态右侧仅显示 arrow.clockwise 图标，使用与左侧 × 一致的 17pt 图标、44pt 触控区和浅色圆形底；不显示“重试转写”文字，保留同名无障碍标签与原操作重试。主输入与问题回复共用，Android 现有图标操作不受影响；本次验收单列于[专项记录](../acceptance/voice-interaction-20261029.md)。

2026-10-28 F73／UI02／UI03／UI12 修订 6：底部左边固定“齿轮＋设置”，右边为用量圆环及两行小字余额，紧邻收起按钮左侧；余额数值／单位右对齐，无余额省略文字。三入口独立、设置不随数据移动，仍仅[设计稿](../design/agent-usage.md)，原生和真实来源未新增通过。

2026-10-28 F73／UI02／UI03／UI12 修订 5：底部右侧上行始终显示“齿轮＋设置”，下行显示主余额或用量说明，左侧用量百分比圆环保持。明确设置的视觉提示，不再仅靠余额暗示入口；紧凑两行、设置／额度详情／收起独立，仍仅[设计稿](../design/agent-usage.md)，原生与真实来源未新增通过。

2026-10-28 F73／UI02／UI03／UI12 修订 4：用量百分比圆环替换“我”头像，右侧最多两行 13／11pt 小字；有余额显示原单位余额，无余额显示“设置”。未知比例不补零，多个窗口不混算；圆环详情、文字设置、收起操作独立。两个展示位置与切换联动保持，仍仅[设计稿](../design/agent-usage.md)，未新增原生或真实来源验收，A6 原暂停项不变。

2026-10-28 F73／UI02／UI03／UI12 修订 3：用户指定去掉原“个人空间”四字，余额直接放在原入口同一行；头像设置、余额详情、收起侧栏独立。客户端选择弹层继续展示各台额度，切换后底部余额同步；多余额不混算，窗口留在列表与详情。本次仅更新[设计稿](../design/agent-usage.md)，生产功能与真机验收未新增，A6 原暂停范围保持。

2026-10-28 新增 F73 额度与余额规划：credits、算力、货币余额与多个窗口／容量限制可并存，精确数量、缺失语义、完整目标与来源隔离共用。生产协议、Codex Host、EasyWork 标准适配及双端原生入口已落地；按已确认修订 6 实现客户端选择列表和侧栏两处展示，真实与辅助结果分列于 [F73 记录](../acceptance/agent-usage-20261028.md)；[计划](../plans/agent-usage-20261028.md)、[类型草案](../design/agent-usage-contract.md)、[UI](../design/agent-usage.md)。A6 既有暂停项不变。

2026-10-29 F03／F17／F48／F69 共同断言补充：HTTP 存活、运行时就绪、准确原 owner 和存储健康分别判断，503 保留具体错误及诊断 ID，未知输入只查原回执。两端共用 Host 候选，无原生页面或草稿规则改动；iOS／Android 真实效果未验收，辅助与 Mac 只读结果见[专项记录](../research/host-reconnect-diagnostics-20261029.md)。

2026-10-29 F64／UI06／UI21 输入区语音交互：iOS 输入区长按开始录音，空草稿且没有建议时显示“输入消息，长按语音输入”；短按继续编辑。电脑转写失败保留录音条，显示失败标题、错误原因和圆形重试图标，并保留本地转写／重新录音；录音准备失败显示原因与重新录音。重试沿用原操作，仅成功后填入原草稿，不自动发送。Android 仅同步生成文案资源，长按提示和明确失败呈现待对齐；既有电脑转写与重试能力保持。实现与本次验收分别见[专项记录](../acceptance/voice-interaction-20261029.md)，A6 其他暂停项保持。

2026-10-27 03:05 F03/F69续验：统一V19三台均部署，完整冻结检查通过。MacBook独立Native无绑定候选，物理iPhone158.118秒真实历史/空态/草稿/冷重连通过，0输入；M5三通道与非空SHA先通过，随后手机前置建议503/11.880秒，尚未执行本轮XCTest；Windows独立Native两源原预算1.939/1.883秒通过，但正式owner可控时建议503/7.276秒，继续定位。V17 Windows连接专用手机128.196秒通过单列，不能当建议通过。iOS/Android UI不变，Android无新增通过，A6暂停。详见专项连接修复记录。

2026-10-27 F03/F69 Windows prepared 前缀收据返修：其他对话在原生批次句柄关闭后追加日志时，重新打开并比对原前缀完整SHA，核对文件/路径身份且禁止截短；不重放Native调用。真实短负前缀仍保留原大小门禁，只有完整配置载荷确认后才允许尾部增长；完整正文/manifest/载荷、ACL/chain、最终commit和原预算不变。新增正常追加反例旧代码RED，以及前缀改写、未分类增长和截短拒绝；macOS与双端UI不变，Android无新增通过，A6暂停。V19 Windows实测prepare拒绝但未唯一定位具体守卫，本次候选的目标部署及手机结果继续分别验证。

2026-10-27 F03/F69 Windows prepared 候选：规划改为逐行增量累计实际JSON/UTF-8字节与原链/正文响应估计，保留发送前完整帧校验、所有原生收据和64项/48KiB/256KiB及原预算。配置发现先读1KiB，首个配置事件未完整时只对成功读取扩展到原16KiB并保留两份前缀守卫；原生读取失败不扩展或重试，所选完整日志/载荷/原owner/最终commit校验保持，macOS仍用原16KiB路径。B项诊断计数同步当前64上限。145项定向检查127通过/18仅Windows跳过，完整原生与普通Host、手机建议仍待本次候选验证；双端UI不变，Android无新增通过，A6暂停。

2026-10-27 F03／F69 当前统一 V17 已部署三台指定 Host；Windows 640MB 桌面归档的有界检查及正常服务环境恢复已原机确认，建议仍 503，手机尚未通过。物理 iPhone 对 M5 非空建议显示/全文/采用/撤销与冷连接通过，对 MacBook 当前 Native 无预测候选的真实历史/空态/草稿保护通过；不混同两者结果。iOS／Android 产品 UI 无变化，Android 无新增通过，A6 保持暂停。当前结果与历史失败见[专项验收](../acceptance/host-ios-connections-20261026.md)。

2026-10-27 F03/F69：物理 Host 修复验收器新增显式 connectionOnly 分支，仅核对真实扫码连接、准确 A/B 历史、未改草稿、客户端集合和冷重连；该分支不读取或裁定建议，不把 HTTP 503 当作 Native null。原非空和真实空建议断言保持，iOS/Android 产品代码与协议不变，Android 无新增通过，A6 暂停。

2026-10-26 F03/F69 验收续修：隔离metadata假子进程的关闭指令与完成确认改走独立固定Node IPC，先等初始化写入drain，再关自己stdin及Windows libuv副本，避免把关闭确认写入同一条关闭中的管道；生产协议与运行组件不变。Windows新两源独立Native prepare/commit按原预算成功6.111/4.181秒，均真实null，原owner和正式HTTP仍另验；新增仅测试入口覆盖真实历史、A/B无伪灰字/无采用按钮、草稿不变及冷连接，不能以空结果冒充采用正例。两端产品UI无改动，Android无新增通过，A6暂停。

2026-10-26 F69 Windows 原生批次候选：小项上限由8增为64，仍按原48KiB请求、256KiB响应估计、每项16KiB和100000字符命令行上限规划，原生最多8项并发；每个原始Snapshot的完整新鲜打开、前后路径/身份/ACL/内容核验均保持，失败整批拒绝且不重放。原750ms单批与4/7/8秒读取预算不变；不增加权限或跨请求缓存。真实Windows单次计时四组摘要相同，但不包含正式传输，不能据此断言根因或收益；候选实际部署和手机结果仍待验。iOS／Android UI不变，Android无新增通过，A6暂停。

2026-10-26 F69 Windows 准备阶段候选：时间过滤后的新鲜目录按最多八项重新组成前缀和配置载荷批次，避免原目录分组中剩余单项反复逐项读取。每个批次至多八项，仅使用本请求的新鲜收据，过滤掉的 manifest 及每个目录、文件和最终 commit 守卫完整保留；原 4/7/8 秒预算、原 owner、权限、内容与六秒租期不变。辅助回归对照同一实际文件系统上的 scalar 原生接口，准备/提交的完整路径及读取长度多重集和全部收据相等；稀疏场景由 17 个 scalar 调用降到仅根目录 1 个，后变为合格的排除项仍拒绝 commit。当前是源码候选，尚未由本次 Windows 原生或手机验收证明收益。macOS 路径和双端 UI 不变，Android 无新增通过，A6 继续暂停。

2026-10-26 F69 真机续验：人类确认 UI 自动化设置及正常重启后，独立物理 iPhone 15 Pro Max 对指定 MacBook 与 M5 的 V7 正式 Host 完成 A→B→A→B 灰字/全文、采用/撤销及冷启动；原 owner 来源和用户输入集合保持。测试表单未切换失败、测试 helper 局部重选及新旧 App 二进制分别留证；新 App SHA256 为 9d393fe796e94bc007323e70bcfb610ba4e75b6f6b2e006ac5287702b1208e11，两台分别 154.647/118.129 秒通过。Windows V7 正式 prepare 仍耗尽 4 秒，当前候选未部署；这些 Mac 通过不升级 Windows、Android 或完整产品状态。见专项连接修复记录，A6 保持暂停。

2026-10-26 F03／F17：逐机复核发现 desktop open 的只读尾部仍强制发布完整状态头。现在该路径同步执行相同的真实 writer／完整磁盘头／权限及完整内容校验，只有已成功同步的相同字节可复用；任何变化（包括回执）仍在返回前完整发布并同步。若存在待补偿的命令提交，读路径也必须执行原强制同步，不能清除未落实的提交门禁。新增实际 RuntimeSession.open 的合成原 owner 回归覆盖重复打开、相同长度正文/状态、变更标题及待提交命令。iOS／Android 共用 Host，UI、草稿和未知操作不重放规则保持，真机与逐机结果另记，A6继续暂停。

2026-10-26 F03／F17／F69：Host 只读目录变更改为合并后台保存；准确原 owner 的完整消息投影逐字段相等时保留历史引用，正文、附件、状态或 ID 变化仍更新。后台状态仅在真实写入者／完整磁盘头连续、实际权限合格且完整序列化字节等于本进程已同步落盘的头时跳过重复发布；命令显式保存仍每次执行文件及适用平台目录同步，任何新写失败均撤销该可复用证明。故障、未知操作与回执边界保持；iOS／Android 共用 Host，未改变 UI 或草稿规则。辅助检查、各机延迟与物理手机结果分别登记，Android 不新增通过，A6 保持暂停。

Windows 原生 helper 的暖启动将目录保护、逻辑 binary 和物理 binary 的现有私有权限校验合并到一次 PowerShell 进程中执行，每个新 isolate 仍重新完成全部检查，不复用跨请求授权。校验后再次核对实际路径，失败不填充本进程缓存；冷编译和私有临时文件发布边界保持。此改动减少启动进程数，不能据此宣称原生建议、读取时延或手机验收已通过，实际结果单列。

Windows 自动重连更新的可信 CLI 选择同时供新建、分叉、队列和项目管理的下一条元数据连接使用，避免长生命周期辅助对象继续使用失效安装路径。路径提供者仅在 Adapter 内部，逐次要求固定绝对路径，不开放客户端可选命令或方法；原零 Turn 准备、确认退出、原 owner 执行及未知输入不重放边界保持。当前候选的辅助检查和实际 Windows 结果分别登记，不升级手机或 Android 验收状态。

2026-10-26 Windows 连接恢复：原桌面更新后，运行中的 Host 在每次重连重新核对当前管道 owner、用户／登录会话、相邻协议 archive 与可信 CLI，复用启动阶段同一有界发现实现；不写入正式配置，不改 Host 身份，不重放未知输入。旧只读 metadata 进程须确认退出与 stdio 关闭后才能切换 CLI；辅助边界与实际 Windows 原生结果分别登记。iOS／Android 共用 Host 恢复，UI 和草稿规则不变；物理手机 XCTest 启用自动化超时仍为未通过。

2026-10-26 F69／F03：macOS Host 在已配置共享通道但 owner 尚不存在时，仍以准确原桌面直接执行器证明采集来源；完整目标、完成轮次、正文摘要及原生租期断言保持。iOS／Android 共用 Host，未改变两端 UI 或建议采用行为；Windows 连接、两台 Mac 建议和物理手机结果按[本轮记录](../acceptance/host-ios-connections-20261026.md)逐环境登记，Android 不新增原生通过，A6 继续暂停。

2026-10-26 F69：macOS collector 从私有清单 SHA 固定的 wrapper 读取规范绝对 Node shebang，匹配原 GUI 的实际同用户子进程；后续 executor 核对复用首次固定的路径，继续拒绝变更二进制、PID 重用、错误父链、无效清单及任意 Node 孙进程。修复 NVM 安装被硬编码内置 Node 拒绝的 503。25 项 Python 边界检查通过仅为辅助，目标部署和真机仍待验；Android 产品未改且无新增验收，A6 继续暂停。

2026-10-27 F03/F69 物理验收工具续修：真实空建议场景在每次来源切换前通过原生XCUIApplication激活准确独立bundle并等待foreground，保留原目标/历史/无伪提示/草稿/冷重连断言；先前73.470秒已通过前两源空建议后因foreground单次状态门槛失败，未写成产品通过。另一次多行搜索标题失败及Runner信任拒绝均独立保留，签名/配置有效至2027且仅重装自有Runner后可执行。无产品UI/Host/协议变化，Android无新增通过，A6暂停。

2026-10-27 F03/F69 Windows 640129388字节ASAR一次性readFileSync内存分配失败阻断正常服务恢复，统一Adapter改为有界header/条目、逐模块检查和64KiB完整SHA流式读取；前后fd/路径/dev/inode/大小/权限/高精度时间身份保持，不缓存、不省略完整摘要/协议条目验证。640MB低内存夹具、短读、同大小修改、命名空间替换、迟到坏条目和大小边界6项辅助通过，Windows原机仍待部署/普通环境恢复及正式预算/手机验收。Windows断管测试替换不能关闭全部Node复制句柄的夹具为原机已验证的自有C#原始HANDLE关闭/EPIPE子进程，原失败保留，未变生产协议。Android无产品变化和新增通过，A6暂停。

2026-10-28 F36／S06：Codex Host bridge 对 standard／fast／ultrafast 按模型目录稳定 ID 开放，拒绝不支持档位；fast 别名统一为 priority，ultrafast 原样传给原 owner，当前速度优先于旧轮次。iOS 已有多档菜单显式使用原生 menu；Android 消费相同 Host 能力及速度契约，原生界面本轮未改、未新增验收。重启偏好、项目／无项目首条、唯一输入及真实速度回读见[专项记录](../acceptance/host-ultrafast-20261028.md)，A6 保持暂停。

2026-10-26 F71／UI07／UI12 图片预览与多选：iOS采用点图片预览、点独立圆圈选择；最近图片与可访问相册共用按选择顺序排列的暂存选择，统一添加后才保存原草稿，关闭不修改已有草稿。全屏支持选择、左右切换、双击／双指缩放、清晰图片加载失败重试；草稿图片支持预览与移除。已添加标记按本地照片来源保存，旧草稿兼容，仍受五个附件、20 MiB及原目标／草稿代次限制。Android对应UI待A6且保持暂停；实现、辅助检查与物理iPhone／真实模型结果分别登记，完整F71不据此关闭。
2026-10-26 外部附件接入：iOS注册文件打开并新增系统“添加到 Agent Hub”操作扩展，共享文件先原子保存本地收件箱，回到App后确认准确草稿；系统粘贴支持文件URL与文件字节，普通文本和网页链接不转成附件。临时URL在回调内复制，保留名称/字节/摘要，落盘后才消费收件箱，重开/部分导入按固定文件ID防重。20 MiB上限保持，28.3 MB ZIP明确拒绝。Android对应分享接收与文件粘贴仍待A6，当前不启动暂停阶段。实现、辅助与真机/WeChat实际来源结果分别见docs/acceptance/external-attachments-ios-20261026.md。

2026-10-26 F64／UI06／UI21：转写提示两侧增加柔和起伏的处理波纹，保留原波形行的居中文字、右侧加载与左侧结束等待。共同断言为1.2秒循环、3–15 pt／dp短柱、次级文字色和10 pt／dp文字间距；不表达完成比例。iOS减少动态效果时静止且离开前台暂停，Android遵循系统动画缩放；波纹不单独朗读。双端已实现，辅助与原生验收范围见[专项记录](../acceptance/voice-transcribing-animation-20261026.md)，A6其他暂停项保持。

2026-10-24 发热排查：两端 EasyWork 改为事件唤醒，稳定 epoch/cursor 的心跳不读历史；重连 epoch 触发权威修复。共享补偿读取规则同时限制成功、失败和旧快照尝试，iOS 不再每秒失败重读。已验证且完整版本元数据不变的缓存消息不重复计算文本摘要，变化内容、元数据及 reset 仍校验；iOS 相同历史不重写缓存。原生合成辅助检查与真机功耗、真实桌面和 Android 逐项验收分开登记，A6 继续暂停。

2026-10-24 发热续验（F17／F18／F49／F50／F56／F68）：iOS 未读与备份只按实际完整字节复用校验，实时快照有界两秒保存；Android 相同正文完整校验加密分片后仅写新头。准确安装的独立 iPhone 两场景前后四项测试通过，持续回复 CPU／逻辑写入／CPU功耗影响分别下降39.0%／85.1%／42.7%；长历史 CPU／写入下降23.7%／72.5%，功耗影响仅降2.5%，热等级仍为2，最大帧未改善。Android 13 项原生辅助测试通过，物理功耗仍待验；保持充电条件不作续航结论，正式App／Host未部署，A6保持暂停。见[真机报告](../research/mobile-thermal-20261024.md)。

2026-10-24 F69 跨项目建议读取续验：指定 Mac 的独立 DEV、正式 HTTPS 和实体 iPhone15 的 A→B→A→B 灰字／全文、采用与撤销均通过；原 owner 用户输入及来源轮次保持。统一包、运行指纹、前期失败和构建隔离单列，CoreDevice 本轮报告 wired 控制通道，产品使用 LAN HTTPS；不升级 Windows、Android 或完整产品状态，A6 保持暂停。见[专项验收](../acceptance/desktop-suggestions-cross-project-20261024.md)。

2026-10-24 长历史性能续修（F17／F19／F20／F56／F68）：目标键编码与选择结果、完整内容投影及 Markdown 叶块减少重复工作；账号/机器/运行时、旧别名、正文/附件/状态及窗口变化仍各自正确失效。物理 iPhone 严格滚动窗口与主线程栈、Android 原生辅助及保留的旧失败在 [专项报告](../research/history-thermal-20261024.md) 中单列；不以辅助或充电状态的功耗指标宣布完整产品、实体 Android 发热或电池续航通过，A6 继续暂停。

2026-10-25 iOS 状态存储写入放大、CPU 基准与静置/混合对照见[专项记录](../research/mobile-performance-io-20261025.md)，Android 无相同的三份状态复制，未新增真机功耗通过。

2026-10-25 持续CPU续修与独立真机范围见[CPU专项记录](../research/mobile-performance-cpu-20261025.md)。

2026-10-25 F71 iOS 附件快捷入口首批核心已实施并完成真机验收（四项匹配复用、最终发送本次通过）：最近图片轻提示、加号快选、拍照和显式粘贴统一保存本地草稿，点击发送后才上传。共享规则处理限额、原草稿代次与去重；iOS 26 及以上完整授权使用 addedDate 判断新增，旧系统和部分授权提供手动快选。原生辅助与真实 DEV 分别登记；Android 待开发且 A6 保持暂停，系统分享为后续批次。见[执行计划](../plans/mobile-quick-attachments-20261024.md)。

2026-10-24 F69／X07：EasyWork 接入桌面已生成建议的只读 runtime-version 通道；完整目标、当前来源轮次、摘要与租期共同约束显示，手机刷新不触发模型。iOS 复用灰字和本地草稿采纳／撤销；Android 尚待实现及验收。见[专项记录](../acceptance/easywork-suggestions-20261024.md)。

2026-10-24 原桌面创建恢复：准备器确认退出且官方 API 已核对准确零 Turn 元数据后，先登记只读会话，再获取原 owner。已拒绝且未写入首条输入的本机准备记录可支持该准确 ID 的显式打开；原创建回执和输入不变，不重放、不以元数据冒充运行时可控。两端共用 Host，无新增手机权限开关；原生与辅助验收分别记证。

2026-10-24 原桌面目录策略简化：desktop-runtime 不再使用 HOST_AGENT_ROOT 作为建议、附件下载或服务就绪的全局授权范围。该旧配置仅保留默认 cwd 兼容语义；托管／共享模式的显式边界和原 Agent 执行权限保持。建议按原 owner、当前实例和准确完成轮验证私有 Trace，不要求项目目录存在；文件下载按本会话已登记资源授权，原消息引用、目标变化、大小与摘要检查保持。iOS／Android 共用 Host 行为，无新增权限界面；实际桌面、模型、实体手机及 Windows 结果分别记证。 详见 [目录策略](../adr/0003-desktop-resource-authorization.md)。

2026-10-24 F69 跨项目建议读取（初始实现，后续由上方目录策略简化取代静态根目录依赖）：Host 从准确原 owner 的最新完成轮取得来源 cwd、规范路径及目录身份，在读取前、中、后同时核对目标、轮次和目录，再把该来源目录传入 Trace 读取。静态 HOST_AGENT_ROOT 继续用于原 Host 范围及未绑定观察记录，不再误拒绝桌面已授权的其他项目建议；不接受客户端目录参数，不扩大文件或命令权限。macOS 与 Windows 共用此内部范围，iOS 展示和 Android 尚未实现的建议 UI 状态不变。辅助检查、Mac 部署、实体手机及 Windows 结果分别登记，未验收项不升级为通过。

2026-10-22 当前执行入口为 [Android 分步进度](../plans/android-alignment-20261022/progress.md)。217 条审计及 70 功能/21 页面/18 场景保留完整覆盖；X01–X09 是既有功能的细分核对项，登记在 UI 规格，不改变功能 ID。Task Hub 业务任务/项目、业务通知和新增项目记录管理暂缓，A6 继续暂停。历史“仅基础示例”等状态按原日期保留，不作为当前源码结论。

版本：3。更新时间：2026-10-12。A0 起始时读取远端 `main` 的 `e80e5a2911fa4aa0a3daafae73d261aed12853ac`，并在独立开发工作树合并用户已有变更，输入快照及合并提交见[阶段实施记录](../acceptance/android-stages.md)。A0 的 14 组／48 次原生合成基线验收已通过；对应范围及未覆盖状态见阶段记录，不能把源码存在或合成回归记为真实 DEV 通过。

A3 推送前已合并最新 main 提交 7d1e62f，新增连接方式、阅读／分页行为纳入本阶段回归；新增独立文件互传 F59 安排在 A6。阶段验收与提交推送均需更新开发进度表，当前 A3 仍未封存。

此清单管理当前 iOS 功能向完整 Android 版本扩展，以及后续任一端的新增／优化。A1 已建立 Android／KMP 工程，两端实际采用共享目标缓存键与输入栏同步规则；安卓目前只有基础示例页面，以下完整产品功能仍待开发，A1 双端模拟器、共同向量及输入性能门槛已通过，证据见阶段记录。iOS 列只代表源码中存在实现，不代表本次真实 DEV 通过；既有验收资料的版本和范围必须另行匹配。执行阶段见[开发计划](../plans/android-native.md)，UI 见[双端规格](../design/mobile-parity.md)，S 编号见[验收场景](../acceptance/mobile-parity.md)。

2026-10-26 附件与计划入口调整：iOS打开加号面板时按需请求相册权限，移除最近图片提醒开关及展开收起；最近30张可访问图片以88pt缩略图默认横向排列、左右滑动快选。计划开关常驻输入框上方审批之后、模型之前，明确显示开关状态，沿用原草稿及提交门禁。Android相册快选仍待A6，计划入口需后续同步，当前不启动暂停阶段；本次iOS结果见docs/acceptance/mobile-parity.md的2026-10-26记录。

## 状态与维护方式

- 实现状态分别记录：待开发、开发中、已实现、平台不适用。平台不适用须说明系统依据、等价行为和用户可见差异，不能用它掩盖未开发。
- 真实验收状态分别记录：未验收、本次通过、匹配复用、失败。编译、单元测试、fixture、截图比对是辅助证据，另列结果。
- 每项按 Agent／接入模式／手机平台／设备环境细分。Provider 不声明的能力两端均隐藏或拒绝；“未支持速度”“原桌面不能添加项目”等不能变成 Android 独有缺失或伪造成功。
- 每次任一端新增、优化、修复或调整 UI／协议，先更新对应 F 行的断言、影响端、S／UI 关联和基线，再实现与验收。共用规则同时回归两端；平台改动解释另一端是否受影响。
- 新功能使用新 ID，已有 ID 不改作另一功能、不删除失败记录。删除功能须注明版本与原因；阶段性交付保留所有未完成项。
- `mobile-parity.manifest.json` 是功能定义的唯一入口；本页表格由 `pnpm generate:mobile-parity` 生成。共同断言、来源、UI／S 关联和实现／验证状态均在 manifest 维护，`pnpm check:mobile-parity` 检查完整性及生成内容。

现有真实结果入口：[iOS 本机](../acceptance/ios-local.md)、[EasyWork](../acceptance/easywork-dev.md)、[独立桥接](../acceptance/desktop-prepared-current.md)、[通知](../acceptance/task-notifications.md)、[未读](../acceptance/unread-state.md)、[角标](../acceptance/unread-badge.md)、[权限](../acceptance/permissions.md)、[发送性能](../acceptance/send-performance.md)、[失败提示](../acceptance/receipt-notices.md)、[最终回复操作](../acceptance/run-reply-actions.md)。引用历史结果不自动将本表某项标为通过。

<!-- mobile-parity:generated-tables -->

## 每次变更与验收的登记模板

从本次规划开始，功能变更应附下列信息，并更新对应行；历史基线和既有失败记录保留。

```text
变更版本／提交：<commit or source digest>
功能 ID：<Fxx, new ID if needed>
行为变化与共同断言：<before / after / expected result>
影响范围：<core / iOS / Android / Host / Provider / protocol / UI>
另一端处理：<same change / unaffected with reason / pending implementation>
场景与 UI：<Sxx cases, UIxx states>
iOS 实现／辅助检查／真实验收：<separate statuses and evidence references>
Android 实现／辅助检查／真实验收：<separate statuses and evidence references>
平台差异／阻断／负责人：<reason, equivalent behavior, owner, next action>
```

Android 达到完整交付前，可有分阶段版本，但必须列出待开发和未验收功能。后续新增功能默认覆盖两端；确属平台能力差异时记录适用范围与等价行为，不能只更新完成的一端。

2026-10-21 F69 候选：Windows 批内并行仍执行每项完整来源校验，进程使用 fresh-open 创建映像查询；iOS 只扩展独立 bundle 的正常 UI 验收准备，不改产品界面或草稿规则。当前真实 Windows 普通模式与 iPhone15 Xcode 结果待验，macOS 与 Android 的既有状态保持。参见 [当前验收范围](../acceptance/mobile-parity.md#windows-trace-当前失败与修复候选2026-10-21)。

2026-10-30 Source52：F33/F42/F46/F55/F56/F58/F64的既有原件保护采用闭合Plan v2，并以ASR原harness编码核对原canonical。完整基线和原地更新保持原件；唯一修正后Native主体通过，最终父PID关联仍待验。未改产品UI、生产wire/fingerprint或iOS，不升格完整功能、迁移或真实DEV。见[阶段证据](../plans/android-alignment-20261022/parallel/fifty-second-stage-current-original-authentication.json)。

2026-10-30 Source53：P7原件迁移先冻结真实owner和writer，未封口录音按原字节惰性保存；F64长按/读屏/麦克风共用当前草稿入口，圆形失败重试及模型安装取消/反馈已接入候选。真实Activity私有安装在启动前绑定；当前r5构建、双SDK六基础方法及干净API26三项真实Main owner有界通过；完整父侧原件、UI与真实DEV仍待验，原失败/未知及API37材料保护拒绝分别保留，不新增完整功能通过。见[阶段范围](../plans/android-alignment-20261022/parallel/fifty-third-stage-provisional-voice-controls.json)。

Source54：只修Android流式验收夹具的canonical安装目录选择；保留首次导出已知失败，产品和iOS不变。新API26六项Native断言通过、五项父侧原件闭合，含三PID原候选激活与冷读；链接元数据/完整P7/真实DEV仍待验。 见[Source54档案](../plans/android-alignment-20261022/records/source54-stream-fixture-context.md)。

Source55/56：收件箱真实producer/writer、严格子存储和惰性原件接入；选择日志/载荷语义核已实现，实际owner/reader/export未完。未封口恢复提示及finish、丢弃未知时的模型入口修正接入。Root组合SDK/一致性通过，Native/完整功能仍待验。 见[Source55/56档案](../plans/android-alignment-20261022/records/source55-inbox-voice-recovery.md)。

Source55合同首组：新包API26的Inbox3/Selection4七项Native语义与完整默认保护通过，Root核329份原始调用；余16项storage/owner/voice及AX/像素/Provider/真实DEV待验，不新增完整P7或iOS通过。见[Source55/56档案](../plans/android-alignment-20261022/records/source55-inbox-voice-recovery.md)。

Source60：仅修Android owner验收夹具的Intent生命周期收尾，原五方法及产品/iOS不变。新SDK五项实际Main owner、原候选及语音密文安装保护限定通过，旧Source55失败留档；完整UI/SAF/AX/像素、API37/真机与真实DEV继续待验。见[补验档案](../plans/android-alignment-20261022/records/source60-inbox-owner-fixture.md)。

Source62组合候选：P7选择原件、最近照片偏好/权限尝试作为惰性审计迁移，实际owner/writer与未知scope拒绝接线；P9仅用已有密钥只读恢复原通知，P8补独立原点及低API反射签名保护，语音原声工具分读回与显式ACK清理。Root组合SDK/一致性通过；Native/OS/UI及真实DEV待验，产品iOS不变，不新增完整P7通过。见[组合档案](../plans/android-alignment-20261022/records/source62-system-originals-composition.md)。

Source62首组：新包API26的选择惰性原件/返回审计8、照片偏好/权限审计4、固定ACK解析5项Native内存契约通过；Root核799份原调用，升级原件保护72份另列。文件/实际owner、真实跨PID、原WAV读回与清理、完整UI/OS/物理/真实DEV仍开，不将解析器ACK向量当真实操作授权。见[首组档案](../plans/android-alignment-20261022/records/source62-p7-memory-native.md)。

Source62文件组：选择回执/既有key保护与未知scope发布栅栏4项Native完整通过；另1项主体过、后置SDK查询exit255，单列待验且未重跑。其余20文件/owner方法及原声、系统/完整UI/物理/真实DEV仍开，iOS产品未改。见[文件档案](../plans/android-alignment-20261022/records/source62-p7-filesystem-native.md)。

Source62准入组：五项实际Main/registry的超限、捕获失败及同一次审计写入恢复限定通过；原指针以后续独立只读补证，0重跑。原请求pending不冒充归档，Provider/OS/完整UI/物理/iOS真实DEV仍待验；余14文件/owner方法继续。见[准入档案](../plans/android-alignment-20261022/records/source62-p7-admission-native.md)。

Source62 reader组：六项既有FD/Keystore Native限定通过，缺项/未知/链接及原件只读保护保持；父层比较源/目标密文复制、run内03孤件与历史tag1，不声称独立解密或系统权限。余8 owner、原声/系统/完整UI/物理/iOS真实DEV待验。见[reader档案](../plans/android-alignment-20261022/records/source62-p7-readers-native.md)。

Source62 owner组：五项Main/IO/自有FileProvider与照片审计限定通过；重复registry/照片query两项已知失败，原回执保持并补诊断，不降低断言。相机原件窗口未验，当前余3方法及系统/完整UI/物理/iOS真实DEV仍开。见[owner档案](../plans/android-alignment-20261022/records/source62-p7-selection-owner-native.md)。

Source63：Android 阅读 reset 撤销排队旧写入，新增实际 Store/原件保护夹具；owner 失败诊断和 P8 高 API 原型隔离已接入源码。当前 SDK/Native/完整交互待验，旧结果原样保留，iOS 产品无改动。见[对应档案](../plans/android-alignment-20261022/records/source63-reading-owner-and-admission.md)。

Source63 检查更新：组合 SDK 与三 APK 验证通过，P8 新 DEX 外层上述高 API 原型为 0；Native/反射/完整交互未执行。旧 Ledger 两项测试的未知 worker 收尾缺口已单列，禁止据其夹具清理行为关闭真实 IO 退出。见[阶段结果](../plans/android-alignment-20261022/records/source63-reading-owner-and-admission.md)。

Source64：Android Discovery/连接动作共用 Session IO 归属，迁移等实际 HTTP/断连和 NSD/resolve/network 终态；阅读 worker 未退出不退役/清理，清理意图及已知结果分列。源码已接入，SDK/Native/完整交互待验，iOS 无改动，见[阶段档案](../plans/android-alignment-20261022/records/source64-discovery-reading-ownership.md)。

Source64r2 检查更新：公开目录 FD 同步 helper 修正测试夹具编译后，830/843 输入组合 SDK 与三 APK 验证通过；主统筹独立读回全源与原始工具结果。本候选 Native/系统/完整交互仍待验，旧失败不覆盖。见[阶段结果](../plans/android-alignment-20261022/records/source64-discovery-reading-ownership.md)。

Source64r2/API33：阅读旧写入隔离 3 方法、受控 IO 归属 8 方法本次通过，原 B 前后保护严格相等。仅为控制方法证据，实际 NSD/HTTP、Store/Ledger、完整 P7、系统/UI 与物理 DEV 仍待验；iOS 无源码变化。见[独立档案](../plans/android-alignment-20261022/records/source64-api33-owner-native.md)。

2026-10-30 F03／F02：Host 工作区独立 metadata reader 改为共享当前可信安装发现，修复 Codex 更新后仍固定旧 CLI 路径的目录读取缺口。共同断言：同一 Host/workspace/runtime、原设备凭据、缓存／草稿及未知操作 ID 不变；仅完整只读目录限次重试，不重放回复、新建、审批、停止或管理操作。iOS／Android 共用 Host，端侧 UI 与 KMP 不变；两端均保留原连接并按既有读取恢复，未新增原生通过。实现、辅助回归、Windows 原机与手机结果分列于 [恢复记录](../acceptance/codex-update-resilience-20261030.md)，A6 保持暂停。

原录音只读补证不改变两端产品UI：准确原包单次Native及完整原件/WAV核对限定通过，完整F64/P7/系统仍开放。离线验收器按完整持久化row核对，详见[检查点](../plans/android-alignment-20261022/records/original-voice-readback-20261030.md)。

2026-10-31 F03/F31/F32：Windows 常驻 Native 权限拒绝补齐固定原因，权限接受/拒绝与未知操作不重放规则保持；iOS/Android 共用 Host，端侧 UI、协议、草稿和原连接无改动，不新增原生通过。PR82 来源 scheduled/交互预检通过不能代替正式 Host；新 Native/cache 需限域复验，详见[正式诊断门禁](../acceptance/windows-formal-startup-preflight-20261031.md#pr82-实测之后的最小证据修复)。A6 保持暂停。

2026-10-31 F03/F31/F32：拒绝验收器按既有 check 契约只验证共享允许主体拒绝及恢复接受，移除错误的保护位拒绝假设；产品 owner/DACL、运行字节和 iOS/Android UI/协议无变化。旧脚本静态问题与合成回归不冒充 Windows 实测，见[契约修正](../acceptance/windows-formal-startup-preflight-20261031.md#验收器契约修正pr83-发布后)。

2026-10-31 F02/F03：目录恢复夹具等待自身子进程消费 initialized 后再冻结无 RPC 快照，保留原 Host 游标拒绝断言；产品握手、恢复、预算、原生双端 UI/协议均不变。CI 失败与修正辅助证据分列，不新增 Windows／手机通过。

Source68（F49/F50/F55/F56 及迁移资料涉及的 F06/F08/F10/F51–F54/F64）：Android 增加原租约到新 Session 的严格迁移交接、完整旧密文核对和统一只读 FD 生命周期，并新增排队阅读跨前后台组件入口。851/864 组合 SDK/三 APK 本次通过，原编译及外层记录失败保留；三 PID 冷迁移、该阅读组件 Native、完整交互/UI 与真机仍待验。iOS 共同断言和产品 UI 不变，不新增双端通过，见[限定检查点](../plans/android-alignment-20261022/records/source68-cold-migration-reading.md)。

Source69：保留 main209 的全部 Host/协议与 F75 计划，冷夹具诊断按实际 PID 隔离。853/866 组合 SDK/三包独立核对通过；P9 共用未绑定采集器的 67 项、P8 窗口关联的 14 项纯回归仅为辅助。iOS/Android 产品 UI 不变；项目相关原生界面按当前暂停，三进程与完整交互/DEV仍待验，见[检查点](../plans/android-alignment-20261022/records/source69-main-cold-owner.md)。

Source70（F06/F08/F09、F51/F54/F55/F56）：已保存 Relay 记录按 ID 选择；本地通知导航 v2 claim/ack、进行中的配置撤销和迁移清空已实现。853/866 SDK与三包核验通过，十项 Native 仅编译；实际 Session／页面、原始通知和真实 DEV 未验。iOS 作为行为对照、不新增通过；见[限定记录](../plans/android-alignment-20261022/records/source70-saved-relay-and-notification-navigation.md)。

Source71保存连接回归已编译：实际页面在线回调、离线Session及迟到失效尚未执行，P7仅新只读基线通过；详见[档案](../plans/android-alignment-20261022/records/source71-saved-selection-and-cold-baseline.md)。

Source72修复严格冷恢复中BOUND写账本的顺序冲突，保持权限和原回执；SDK通过，新Native／UI仍未验。Source69原失败generation不重放；详见[记录](../plans/android-alignment-20261022/records/source72-bound-reading-handoff.md)。

2026-11-01 阶段45：公共Native采集工具按准确代码字段及正则类型修正辅助准入；双端产品和UI不变，公共live attestor未绑定，不新增原生通过。详见[独立记录](../plans/android-alignment-20261022/records/public-collector-code-identity-20261101.md)。

2026-11-01 Source72新API33三PID冷链：writer／原候选激活与阅读恢复／第三进程原回执审计三方法限定通过；163项设备及24项本机守卫、31／61／62成员完整原件与九stat由主统筹核对，remoteCalls=0，不重放原操作。完整迁移、语音／未读生命周期、UI／物理DEV及iOS仍未新增整项通过，见[本次原生记录](../plans/android-alignment-20261022/records/source72-fresh-native.md)。

2026-11-01 阶段46：F49/F50/F56：Source72真实IO队列中的旧正文跨本机生命周期换代拒绝、新正文不消费独立问题及迟到隔离，一个Native组件方法限定通过；iOS共同断言不变，无新增系统／UI／物理DEV通过。见[本次记录](../plans/android-alignment-20261022/records/source72-queued-reading.md)。

2026-11-01 阶段47：F06/F08/F09：Source72保存连接四方法／六夹具限定通过，含生产行touch及离线／四类迟到隔离；完整P8及实际服务未封存。F51/F54/F55/F56的P9夹具known owned收尾窄修仅Source73 SDK通过，产品行为与iOS不变，Native待验。见[P8本次记录](../plans/android-alignment-20261022/records/source72-p8-saved-native.md)及[P9 SDK记录](../plans/android-alignment-20261022/records/source73-p9-fixture-owner-join.md)。

2026-11-01 阶段48，F51/F54/F55/F56：Source73 原导航回调 10/10项限定通过，仅 Test 更新，App 字节不变；真实 Main 入口的本地回调、claim／ACK及失效隔离按方法限定，不新增系统通知触摸、冷 PID、完整 UI、物理 DEV 或 iOS 通过。默认启动 runtime／私有 handle／物理 executor 收尾仍未证实。见[本次原生记录](../plans/android-alignment-20261022/records/source73-p9-native-navigation.md)。

2026-11-01 阶段49，F33/F55/F64：Android原录音迁移的特殊文件非阻塞拒绝及原件保持在API33限定通过；只核对导出已返回后的读取端，不宣称全进程FD关闭、完整UI或iOS通过。完整语音迁移的未提交audio窗口及iOS音频资料范围继续待实现／验收。见[Source74档案](../plans/android-alignment-20261022/records/source74-provisional-fifo.md)。

2026-11-01 阶段50，F47/F55/F56/F64：Android未提交audio窗口采用明确v2惰性catalog，原row/index及raw/audio两份分别封闭并计quota，v1读取保留；仅只读复制，不seal补manifest、不恢复旧recorder或重放。当前新四场景4项限定通过、原两项warm2项限定通过、纯内存契约3项辅助通过；真实seal中断／冷PID／mixed／低堆／完整UI和物理DEV未验。iOS Payload未含PendingVoiceStore音频/metadata，缺口单列；见[Source75记录](../plans/android-alignment-20261022/records/source75-uncommitted-audio-v2.md)。

2026-11-01 阶段51，F47/F55/F56/F64：Android真实seal在audio写入后／row提交前的具名已知中断及后续惰性export/stage/activate/reexport，API33一方法限定通过；真实row I/O故障、断电／进程死亡、冷PID、完整UI及物理DEV未验。公开构造器／默认算法及Source75格式保持，不seal重试或恢复活动录音；iOS音频／metadata范围缺口不变，见[Source76记录](../plans/android-alignment-20261022/records/source76-real-seal-checkpoint-warm.md)。

2026-11-01 阶段52，F53/F56／UI11：Android长导入铃声动作行给预览weight、删除保留空间并提供“删除＋原标题”读屏名称，复用small shape避免高pill裁字。当前中浅／英深两项受控组件限定通过；r3方法通过但collector退出1／实际PNG视觉失败保持。Compose受控字体／组件结果不替代OS字体／TalkBack、试听／通知、完整生命周期或iOS／物理DEV；见[逐项记录](../plans/android-alignment-20261022/records/source77-sound-actions-viewport.md)。

2026-11-01 阶段53，F47/F55/F56/F64：Android新增同一原候选的writer／activate／只读audit三次独立原生方法，SDK与同原候选三次Native方法按本范围限定通过，397份原捕获经独立读回；writer／activate两个原collector退出1保持，Root只读重核后接续，audit原collector退出0，不重跑seal／export／stage／activate。具名注入IOException不等于真实row I/O、断电或进程死亡，三PID不证明旧PID退出；UI／物理DEV、iOS音频缺口及完整24组保持待验。见[Source78原候选记录](../plans/android-alignment-20261022/records/source78-seal-original-three-pid.md)。

2026-11-01 阶段54，F06/F09／UI03：共用发现电脑行补Role.Button，对齐iOS EasyWork／Codex的Button语义，布局和原callback保持；原online方法增加角色断言、4原方法保持。Source79当前3方法／4夹具／397份完整捕获限定通过；第4方法仅Native终态0，tar采集关闭导致完整数据读回待验，不重跑Native。共用Codex角色受影响但不增加真实DEV通过，完整P8／系统读屏／截图原点／API26及真实服务另验，见[本轮记录](../plans/android-alignment-20261022/records/source79-p8-discovery-row-role.md)。

2026-11-01 阶段55，F47/F55/F56/F64：Android新增mixed v1/v2惰性目录原生验收方法，保留既有v1原件并将独立seeded未提交audio窗口合成同一v2 catalog；旧项显式null、新项独立audio descriptor、三份binary分别核对，不恢复recorder或重放。SDK及本轮单方法Native／完整数据核对限定通过：97新成员、三份binary、六类坏图拒绝与原回执保持；原安装工具外层退出未知单列，未重放。同进程warm、seeded状态不证明真实seal故障、低堆或生命周期。iOS PendingVoiceStore音频／metadata缺口、完整24组及UI／物理DEV继续待验，见[本轮记录](../plans/android-alignment-20261022/records/source80-p7-mixed-provisional.md)。

2026-11-01 阶段56，F51/F53/F54/F55/F56：Source81新增Android分开调用的writer及existingReadOnly消费验收，不同PID只读消费限定通过；原pending nonce／terminal ACK、未知v2原intent、夹具metadata草稿字段、隐私和完整合成导入声音按阶段核对。SDK859/872及安装零数据差异通过；不同PID不证明旧进程退出或真实死亡，显式now不是实际TTL。nonce／v2原intent是Android附加恢复保障，不声称iOS同构journal；准确target、隐私及声音为共同断言。无Main／OS通知／播放／UI／真实Host或模型；完整P9、P7低堆／24组及物理DEV继续待验。见[逐项记录](../plans/android-alignment-20261022/records/source81-p9-existing-receipt-read.md)。

2026-11-01 阶段57，F47/F55/F56/F64：Source82只新增Android聚合配额原生验收类：10组seeded窗口20份body共192000880字节实际迁移sealFile发布一次，第11组22份共211200968字节越过真实192MiB上限；原Native单次child0与owned50成员／5目录／45regular及原output、全部ciphertext、44个source九stat、index／rows／ownerAlias连续限定通过。SDK860/873、fresh baseline及独立诊断reader0已核；90capture／180完整streams／4snapshot完整读回。原collector及worker1、全根严格边界失败不升级；额外24B ProfileVerifier cache静态来源／原件已确认，动态调用／首次启动／包时间毫秒未独立证。原Native实际maxMemory192MiB；不是实际48MiB／真实录音seal／row I/O故障／死亡／activation／UI／物理／Host模型。完整quota、24域、iOS音频资料及P7继续开放，不重放。见[逐项记录](../plans/android-alignment-20261022/records/source82-p7-aggregate-quota.md)。

2026-11-01 主线有限缺口登记：只读登记main 332a291f至9492e3b7八个非合并提交／六组有限缺口：先集成c6705edb已有Android EasyWork五文件及资源，再补F36速度图标、F19/F32旧visualize与独立HTML交互容器、F26最近五项目及recentProjects资料政策；F52 APNs共享限制与P4 queue／F69 Windows来源仅边界回归。未并入Source82冻结指纹，未新增产品通过；Android已有建议UI／契约及历史结果保留，两处缺失证据链接不造证。暂停范围不变。见[六组优先级及边界](../plans/android-alignment-20261022/records/source82-p7-aggregate-quota.md#最新main有限缺口与优先级)。

2026-11-02 阶段58，F47/F55/F56/F64：Source83新增公开ProfileVerifier预热与独立实际48MiB方法，使用非传递compile-only公开API，不重复打包库。SDK861/886、完整预热/基线/配额原始与独立回读通过：files6→56、原6保护、owned50/44source、10对192000880字节sealFile发布1、11对211200968字节越过201326592上限发布0，实际maxMemory50331648。90捕获/180完整流、全部tar403512320字节。旧Source82严格失败不变；seeded不代表真实seal/rowIO故障、死亡/激活/完整24域/UI/物理DEV，iOS源码不变。 见[独立档案](../plans/android-alignment-20261022/records/source83-p7-low-heap-quota.md)。

2026-11-02 阶段59，F26/F47/F55/F56：Android最近五项目按完整运行目标保存，显式导航前移、不可用保留隐藏、旧回调失效，Codex账号策略不变；可选版本化metadata归档/替换恢复，不创建别名。对齐iOS左取消/普通17号正文及最近/全部分组。Source86官方SDK863/888、7项Native和三原图源契约对照限定通过；同进程新Session不等于OS冷入口，完整P7/UI/物理DEV仍开放，Source84失败及Source85视觉差异保留，iOS源码不变。 见[独立档案](../plans/android-alignment-20261022/records/source86-p7-recent-projects.md)。

2026-11-02 阶段60，F50/F51/F54/F55/F56／UI11：Source94 API33合成加密既有回执图的实际Main入口、三项Native、两张原图及归档读回限定通过。功能核对原ACK/终态/未知回执，交互保留后续聊天和新草稿，界面两图及真实同实例STOP→DESTROY单列；iOS源码不改，双端实施/验收状态保持。未覆盖OS零进程创建、SystemUI实际点击、真实Host/I/O崩溃、完整UI/API37/physical DEV或完整P7/P9。详见[Source94档案](../plans/android-alignment-20261022/records/source94-main-cold-entry-accepted.md)。

2026-11-02 阶段61，F55/F56/F64：iOS既有迁移container已接入独立惰性语音原件档案；无原件仍v1，非空采用Envelope/Payload v2与独立AAD，旧reader显式拒绝。原音频/metadata/control保留，不注入普通恢复、prepare/upload/replay或许可。iOS SDK本次通过，macOS四源CLI结果按精确指纹匹配复用；Android既有格式不变，未证明iOS↔Android整包互通。Payload/SessionStore运行态、双端Native/DEV、真实I/O崩溃及完整UI/物理仍待验。见[阶段61档案](../plans/android-alignment-20261022/records/stage61-p7-voice-originals.md)。

2026-11-02 阶段62，F02/F03/F04／UI01/UI03：完整target/replacement方法1（内含stale-target、stale-replacement两case）在API33 isolated Compose/typed fake ports下通过，保留当前draft/target/replacement client并拒绝迟到read旧选择/cache写回，各case三份protected密文不变。两组退役合成fixture先全原件只读取证，后独立Native精确清理；不是Main菜单、issuer、真实网络/模型/桌面/物理DEV或全A5/shared default family独核通过。iOS源码与双端实施/验收状态保持；原参数/closed-canonical失败保留，不重放。见[阶段62档案](../plans/android-alignment-20261022/records/stage62-p8-target-replacement.md)。

2026-11-02 阶段63，F55/F56/F64：真实iOS production Payload/types在专用arm64 Simulator完成9项原生组件用例（9通过/0失败/0跳过），正式SDK通过；初次x86链接失败/0tests保留。覆盖v1/v2、严格字段/AAD/篡改/source witness、惰性导入/再导出及原件archive已发布后失败保留；第9项不是actualSessionStore恢复/回滚。未实例化SessionStore/controller/transport，无MainUI/Host/模型，L10n负例读取runner自有语言UserDefaults；生产default family/keychain/通知store未访问。actualSessionStore成功/真实I/O故障、24域/激活/崩溃、旧reader实机、双端对应矩阵/UI/物理仍待验，不证明整包互通或完整P7，所有实施/验收状态保持。见[阶段63档案](../plans/android-alignment-20261022/records/stage63-p7-ios-native-payload.md)。

2026-11-02 阶段64，F55/F56/F64：iOS SessionPersistenceWriter已接入三个固定路径原bytes/absence快照、写前非regular拒绝及同步写入失败逆序补偿；补偿不全保留同实例snapshot并禁写，只读核对完整三元组一致后才解除gate。actual production SessionStore/default Keychain/通知I/O的成功、非regular预检和真实写失败补偿各1项Native限定通过，旧/新草稿附件、惰性语音原件再导出与immutable unknown outbox不重放保持；B1在写前预检拒绝，B2以真实UF_IMMUTABLE regular state的CocoaNoPermission/POSIX EPERM进入catch并补偿legacy/backup，原backup absence保持；补偿失败恢复分支仍待验。无OS崩溃/journal持久性、权限元数据或实例销毁后snapshot恢复承诺。独立hosted入口与环境失败另记，不新增UI/物理DEV通过。Android完整代次/单认证指针源码对照未确认同类P1，未新增Android验收；24域/完整UI/双端整包互通及P7仍未完成，所有implementation/verification/shared状态保持，A6暂停。见[阶段64档案](../plans/android-alignment-20261022/records/stage64-p7-ios-sessionstore.md)。

阶段65 F55/F56：Android固定既有候选补本地v1/v2只读兼容，共用原strict receipt/chunk/来源与资料图校验；1项内存校验及2项实际存储/Controller恢复与冷回执Native限定通过。iOS与既有状态不变；严格owner故障/完整UI/物理/24域开放，见[阶段档案](../plans/android-alignment-20261022/records/stage65-p7-legacy-cold-recovery.md)。

F03 Windows 常规 Host 恢复续接：新增私有冻结运行包、原身份/状态绑定、准备及正式任务切换/回滚候选；建议观察和保留关闭。真实 Scheduler 只读启动八项通过，UAC 关闭现场仍是高完整性用户上下文，不计 Limited 隔离通过。共同断言为原 Host/workspace/runtime、设备凭据和未知操作不重放保持；iOS/Android UI、协议及连接资料结构不变。正式服务与跨机 HTTPS、双端手机均尚未验收，不新增原生手机通过，A6保持暂停。见[恢复契约和分层结果](../windows-host-regular-recovery.md)。

F31/F32 Windows 启动诊断：HTTP错误诊断在原64项队列内预留容量，并使用独立限速，防止权限检查事件洪峰挤掉已返回客户端的诊断ID；不扩大日志字段、保留正文或放宽私人数据权限。当前为本地候选，双端UI/协议不变，正式Host和手机验收另计。

F59 Windows 文件接收：原生目录安全检查对重解析点等拒绝保持，转换为已有的409目录冲突契约并保留内部原因；不创建或跟随受拒目标，不放宽owner/DACL。双端既有错误处理和20 MiB/摘要/未知操作规则保持，未新增手机验收。

F03 Windows 常规恢复整合：Windows交付记录正式Host免重启启用，Mac独立核对原证书钉扎的LAN/Tailscale鉴权和准确Host/desktop-runtime工作区。旧合成操作仍pending/历史未确认；当前运行包不在本次整合中替换，建议调研仍停止。此项不升级登录自启、回滚或双端手机验收，iOS/Android UI与wire不变。见[本次整合证据](../acceptance/windows-regular-host-recovery-20261103.md)。
