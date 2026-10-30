# iOS / Android 功能与一致性清单

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
