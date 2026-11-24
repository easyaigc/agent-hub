# iOS / Android 功能与一致性清单

LIFE01（F05/F09/F17/F44/F47/F76）规定最近连接事实、当前核对和副作用控制证明分开表达；单个读取取消不撤回共享建连，预览保留暂停页，冷缓存不授控制。 早页跨版本恢复使用旧端保留的真实reader carrier与独立加密快照；先失效丢失标记的旧入口，再建立新阅读记录。整份缓存与远端模式/有效revision/正重叠证明分开，滚动仅更新小索引，受限owner先于I/O跳过。规格与验收关联见[最终计划](../plans/mobile-foreground-connection-20261122.md)；当前用户功能状态只读[唯一完整清单](../plans/android-ios-alignment-20261107/progress.md)，iOS 专项详情沿用原唯一计划。

2026-11-19 F47／F56：补 Android 正式签名、私有密钥稳定复用、缺签名拒绝发布构建和 APK/AAB 证书核对；回环网络配置显式写出原有子域名默认值以通过 release lint，不扩大明文范围。iOS 身份、双端 UI 与产品语义保持。正式安装/升级、备案及商店审核继续独立验收，见[发布准备](../android-release-readiness.md)。

2026-11-23 Host F17/F18/F19/F28/F47：目录与原owner使用统一标题展示格式，避免等价标题反复触发持久化；真实重命名、运行控制、原输入及双端页面协议保持。源码、辅助检查、macOS实机部署与其他平台验收范围见[设计记录](../design/desktop-title-stability.md)。A6/F75暂停保持。

2026-11-23 Host F17/F18/F19/F28/F47：后台保存复用未变内容，其它状态更新时仍校验并复用已知相同历史；阅读、原文、附件授权和在途命令保持原契约。双端页面不变；具体实现、合成对照与目标暖态CPU验证范围见[设计记录](../design/background-persistence-performance.md)。A6/F75暂停保持。

2026-11-23 Host F17/F18/F19/F28/F47 诊断增量：r4 已正式部署并保留原 owner/模型任务，但目标持续 CPU 实测验收失败，不能标记已解决。新增 macOS 私有显式启用、无网络调试端口的 Main CPU 采集与固定阶段计时；辅助证据及目标原 Host 采集分别核对。双端页面/协议和 Windows 状态不变，A6/F75暂停保持。见[采集、关闭与解读](../design/host-cpu-profile.md)。

2026-11-22 F17/F18/F19/F28/F47（UI05，S03/S04/S11）：Codex Host 对原桌面消息增加有界呈现复用及过时快照中止。指纹包含有效状态、原输入和消息上下文；文件字段不使用跨快照缓存，每次仍检查来源、版本与服务错误；显式刷新旁路呈现缓存，打开/下载实时核对。iOS/Android 共用 Host，协议与页面无改动；固定版本附件捕获失败先回收原私有快照/预算，再公布原终态，修复轮询与删除竞态。隔离回归、受控性能与目标正式进程复测分别记录，不新增双端原生通过。设计与验收边界见[Host 消息投影](../design/desktop-projection-performance.md)。

2026-11-21 F77定义视频按需起播、拖动、inline/全屏切换及同版本换读流程，关联F19/F32/F59/F72的既有媒体入口。本文只维护规格和历史来源；当前双端功能与验收只见[唯一完整功能清单](../plans/android-ios-alignment-20261107/progress.md)。专项原始证据见[视频验收记录](../acceptance/mobile-video-streaming-20261121.md)。

2026-11-21 F06/F08：iOS 新增显式办公室私网范围发现，完整扫描 CIDR 的可用地址，不使用 VPN peer 的32条裁剪；仅固定端口的公开描述读取，原目标/证书/公钥与授权保持。Android 对应入口待实现；已完成独立15的真实蜂窝子网发现/首次授权与同记录冷恢复，原用户手机升级保留与新主线组合仍独立验收，未改 WAN/ACL。设计见 [办公室子网发现](../design/office-subnet-discovery.md)，当前状态见唯一完整功能清单的F06，专项记录仅作来源。

共同断言同时包含停止保留已发现结果/进度、后续仍能连接，以及实际路由独立于32条Host广告保存；Android须保持相同目标与授权边界。

2026-11-21 F19/F32/F59/F72：iOS 视频接入固定版本 Range 与共用 inline/全屏会话，保留旧 Host 完整下载和原分享。限定物理播放器已通过17次执行；主线组合、真实部署及其他平台分别核对，范围见[专项记录](../acceptance/mobile-video-streaming-20261121.md)。Android 新播放器保持计划状态，A6/F75暂停不变。

> 当前用户功能及 iOS/Android 实现、模拟器与真机验收仅维护在[唯一完整功能清单](../plans/android-ios-alignment-20261107/progress.md)。本页的规格、日期段落、计数与验证描述保留其原始范围和历史时点，不构成另一份当前状态表。

2026-11-21 F52/F54（UI11，S15/S16）：iOS 独立验收包的 DEBUG 只读观察增加最近八次实际通知 feed 的有界事件 ID、游标、代次和 activeIds，仍限定准确 bundle、完整目标及显式验收环境。只记录已认证且通过现有目标检查的真实读取，不生成事件或改变消费结果；用于区分重复事件去重与单次系统展示。产品页面、生产默认路径和 Android 实现不变；真实模拟器重复 feed、保留通知后处理撤回与交付状态只回填唯一计划，尚未执行的场景不记通过。

2026-11-20 F17／F18／F28／F47（UI04／UI05，S03／S11）：共同断言为原 owner 接收确认不因断线、重启或迟到请求失败退回未知；原操作、正文与附件不变；最新窗口对旧轮有界只读对账，不加载完整工具正文或重发输入。legacy 按实际方法能力查询，null／省略 items 的摘要只核对已接收轮次。当前候选七项辅助回归和真实 Electron／模型／原生模拟器 1/1 通过；iOS／Android 共用 Host，既有协议与页面兼容；正式部署另记，不提升整项功能或 Android 原生状态。

2026-11-20 F17／F19／F32（UI05／UI12，S04／S11／S18）：iOS 消息媒体在文本 Markdown 前保留图片语法的目的地址，呈现按需打开的图片／视频／音频卡片，复用原消息鉴权、下载和系统预览；打开后的图片只生成有界本地缩略图，不在聊天加载时下载视频。Codex Host 补媒体 MIME，当前完整下载回退与尚未实现的流式方案分别见[消息媒体设计](../design/message-media.md)及[专项验收](../acceptance/message-media-20261120.md)。iOS 26 原生模拟器的图片、视频及原样片播放场景已通过，初始解析／下载为零。Android 原有图片链接入口保持，媒体卡片与缩略图待独立对齐；不新增 Android／EasyWork 通过，A6 暂停保持。

2026-11-20 F09／F72：补齐 EasyWork 两项既有 Git 查询的读取恢复分类，桌面文档链接读取保持相同分类；共同向量核对 Swift 与真实桌面命令入口。完整目标、来源授权、写入只查原回执及旧端兼容保持。Android 产品实现未变、未新增通过；实现和结果只读[历史验收记录](../plans/ios-easywork-alignment-20261112/README.md)，映射见[读取恢复设计](../design/host-read-recovery.md)。

2026-11-20 F35/F41：iOS命令审批补齐原请求身份、目标、能力和实际决策回执校验；会话允许依赖桌面明确声明，旧端保留单次操作。问题回答保持当前主线。Android对应记忆许可/回执仍单独跟进；原范围验收、辅助检查和交付分列[历史验收记录](../plans/ios-easywork-alignment-20261112/README.md)。

2026-11-20 F72：iOS EasyWork补齐文档链接适配，来源授权与阅读身份分离，无原消息来源的旧附件保留阅读键。桌面来源和最终发布组件已合PR73。Android内部导航仍待对齐；实现、原验匹配、辅助检查和部署分别回填[历史验收记录](../plans/ios-easywork-alignment-20261112/README.md)，冷启位置及完整矩阵继续保留。

F59 独立接收沿用现有桌面发送与领域文件协议，新增 iOS 接收列表、分块校验和系统打开入口；旧 Host 缺少可选接收目录时保持原上传。Android 对应实现及原生验收继续跟进，具体交付和后续动作统一维护在 iOS × EasyWork 历史计划中。

2026-11-19 F32／F72 CI 辅助：下载取消回归改为观察真实未完成字节进度后取消，并核对 HTTP 提前关闭；首字节 0／1500／5000 ms 延迟的本机原生模拟器夹具通过，取消未传播、缺少进度及普通网络错误均拒绝。产品、双端 UI 与既有验收状态保持；Android、实体手机和真实桌面不新增通过。完整检查与公开 CI 按新组合另验，见[CI 指南](../ci.md)。

2026-11-19 F05 iOS 离线提示修复：暂态读取失败后结束前台连接进度，明确显示连接未完成、可查看缓存；后台补读不把已知失败改回前台连接中，主动重试和成功恢复保持。iOS 各 Agent 共用该呈现规则，目标、授权、缓存、草稿、原操作及传输超时不变。新包原生模拟器通过不可用中继路径、缓存回复／附件名、本地草稿与恢复直连后入口的限定补验；状态／附件／入口日志由主协调人工核对，两份官方用例各 1/0，不把观察值称为额外 XCTest 断言。旧真实 LAN 中断子范围按相同适配／存储／失败分类源码复用，本轮未关闭共享 LAN；Desktop runtime 未就绪、Codex 新 GUI、Android 与完整故障矩阵不新增通过。准确证据及交付只维护在唯一计划。

2026-11-19 F48 普通停止提示补齐：真实 EasyWork 旧 cancelled Run 没有助手正文时，聊天底部复用共同任务状态与本地化文案显示“已停止”，下一条草稿仍可编辑。原生 iOS 模拟器已在准确新包上验证该终态、草稿输入及清空、返回另一聊天不残留旧标签，原数据与绑定保持且零新增模型输入。离线／未同步文案和运行中／在途／已有失败提示的排除来自现有共同规则及源码保护，本轮未新增这些状态的 GUI 验收。Android UI 本次未改，对应原生断言继续独立跟进。准确运行包、旧包失败和当前验收／交付记录见唯一计划。

2026-11-19 F44／F48：将既有候选的明确失败回执修复补入主线开发分支。认证的原命令 failed 且原响应 ok=false 时显示失败及原原因；缺响应或缺少原模型运行依据继续保持结果待确认，矛盾响应拒绝采用。原操作、目标与草稿保持，不新增自动发送。原 R196 真实回执证据保留；当前组合构建、只读原回执和普通提示／草稿补验分别登记在唯一计划。Android 本次未改实现，不能复用为 Android 原生通过。

2026-11-19 F71（UI07／UI12，S04／S18）：iOS 附件序号、选中描边及添加按钮统一配对主题前景／填充，明确禁用和导入中状态；深色图片预览独立使用深色控件环境。Android 既有 primary/onPrimary 配对不变，原生验收独立记录，不改变 A6 暂停和 EasyWork 父项状态。源码、模拟器交互及交付见[专项记录](../acceptance/attachment-dark-20261119.md)。

2026-11-19 F42：EasyWork 桌面补齐运行结束后待答异步问题的完整来源匹配及当前目录页待答数读取；另一端先处置后的迟到回答显示不可重试的原问题失效，保留原答案、投递与操作。iOS／Android 共用原 serverRequest/resolve 契约和目标校验，不新增方法或输入自动重放；Android 实际结果仍独立验收。源码、既有模拟器与真实桌面证据复用、辅助检查和主线交付统一见[历史验收记录](../plans/ios-easywork-alignment-20261112/README.md)。

2026-11-19 F01／F47（UI01／UI10，S01／S11／S18，修订2）：启动阶段复用现有 Agent Hub Logo；按用户反馈将 Logo 缩至 80 pt、标题改为 17 pt 正文 medium，收弱光晕与阴影，本地恢复页保留轻微呼吸及真实恢复提示；恢复完成直接进入主界面，不为动画设置最短等待。减少动态效果时静态显示，非活跃时停止装饰动画。iOS 系统启动屏使用同款静态 Logo，恢复、配对链接及文件收件行为沿用原路径。Android 对应品牌启动与动态效果待对齐，不新增 Android 通过；此项为共用启动外观，不改变 EasyWork 对齐父项与 A6 暂停状态。源码、原生验收和部署分别见[专项记录](../acceptance/startup-brand-20261119.md)。

2026-11-19 F15：iOS同名项目选择完整换行显示长目录，保留可见末级目录；稳定ID、完整路径和原草稿不变。Android原实现及新增可读性验收单列，A6暂停保持；结果统一见[历史验收记录](../plans/ios-easywork-alignment-20261112/README.md)。

2026-11-19 F20：交付原已验结构化工具详情的视口软换行，保留原JSON文本及命令/补丁的横向阅读。iOS普通工具文本阅读路径与原通过源匹配，主线新增子代理历史入口保留并独立验收；Android维持原实现和独立验收边界；源码、模拟器真实Diff/长工具证据和PR统一关联[历史验收记录](../plans/ios-easywork-alignment-20261112/README.md)。

2026-11-19 F20：交付原已验结构化工具详情的视口软换行，保留原JSON文本及命令/补丁的横向阅读。iOS普通工具文本阅读路径与原通过源匹配，主线新增子代理历史入口保留并独立验收；Android维持原实现和独立验收边界；源码、模拟器真实Diff/长工具证据和PR统一关联[历史iOS对齐记录](../plans/ios-easywork-alignment-20261112/README.md)。

> 本文件仅维护功能规范、共同断言、来源及验收关联。全项目当前实现和验收统一见 [唯一完整功能清单](../plans/android-ios-alignment-20261107/progress.md)；[历史iOS对齐记录](../plans/ios-easywork-alignment-20261112/README.md)仅为冻结档案。

2026-11-19 F09：iOS交付原已验EasyWork信封与订阅游标的安全整数修复，旧信封兼容、同会话恢复和原操作去重按原范围复用。Android既有实现及验收边界保持，双方状态统一关联[历史iOS对齐记录](../plans/ios-easywork-alignment-20261112/README.md)。

> 本文件仅维护功能规范、共同断言、来源及验收关联。全项目当前实现和验收统一见 [唯一完整功能清单](../plans/android-ios-alignment-20261107/progress.md)；[历史iOS对齐记录](../plans/ios-easywork-alignment-20261112/README.md)仅为冻结档案。

2026-11-16 F64（UI06／UI21，S04／S18）：iOS 主输入区未聚焦时长按 0.5 秒启动语音；已聚焦时停用父层语音长按识别，交由系统处理粘贴、选择与光标操作。短按仍编辑，麦克风按钮及无障碍语音动作继续显式启动语音，原草稿与转写回填规则保持。Android 本次不改，其同等长按与编辑手势规则待对齐，不新增 Android 通过；实现、构建和原生手势验证分列于[专项记录](../acceptance/composer-focus-longpress-20261116.md)。本次为共用输入区交互修复，不变更 EasyWork 对齐父项状态及 A6 暂停范围。

2026-11-15 F76 EasyWork 后续候选已接入同一原生预览与共享代理，保留账号/设备授权，LAN/VPN 直连限定；状态和结果见[专项记录](../acceptance/dev-service-preview-easywork-20261115.md)。Android 与 Relay 预览待实施，既有 F01–F75 台账分母保持。

2026-11-15 F17／F31／UI05：本轮 Android 加强版冷会话通过独立模拟器和真实原 owner 验收，原历史 ID/原文保留，原生只新增一次输入和回复。此前 iOS 六项补验保留其原始候选/包，CI 编译表达式拆分与公开历史基准选择分别留证。结果与边界见[续验记录](../acceptance/pr3-android-cold-ci-20261115.md)；完整检查/部署按真实回执判断，F75/A6 暂停保持。

2026-11-15 F32／F72（UI05／UI12，S04／S18）：iOS 消息文件准备卡显示实际下载百分比、已收／总字节和实时速度，进度按真实网络接收采样、停滞后速度归零；准备、下载、校验与缓存打开分别显示，完成后沿用原预览／分享，取消或切目标不呈现迟到结果。Codex 沿用配对证书连接与不可变摘要校验，EasyWork 复用原分块资源协议；无 Host／wire／KMP 协议变更。Android 同等进度 UI 待对齐，现有下载不变；本次源码、辅助和交互结果分列于[专项记录](../acceptance/file-download-progress-20261114.md)，未覆盖项不计通过，A6 保持暂停。 本次主线交付范围为消息文件卡；原开发现场的文档内导航候选保持独立，不随本PR合入。

2026-11-14 F17／F31／UI05：Codex Host 统一历史读取与原 owner 的轮次内序号标识，避免切换实时状态时重复旧消息或跨轮覆盖；相同正文的不同原消息继续保留。双端共用 Host，Android 实体手机只读切换及三次刷新通过，iOS、正式部署与新冷会话发送场景未新增验收，见[专项记录](../acceptance/history-owner-identity-20261114.md)。

2026-11-13 F17／F31／UI05 补充：双端已接入共享快照排序规则，避免旧历史和旧事件覆盖新任务状态；较早前缀页保留当前运行时。共享 JVM 139 项通过，Android 应用及测试 Kotlin 编译通过；原生执行、真机视觉和 iOS 构建未验收，见[专项记录](../acceptance/thread-filtered-events-20261113.md#additional-active-task-investigation)。

> 本文件仅维护功能规范、共同断言、来源及验收关联。全项目当前实现和验收统一见 [唯一完整功能清单](../plans/android-ios-alignment-20261107/progress.md)；[历史iOS对齐记录](../plans/ios-easywork-alignment-20261112/README.md)仅为冻结档案。

2026-11-13 F17／F31／UI05：双端按会话过滤的事件订阅允许全局游标随其他会话前进，空批次不再撤销当前历史新鲜度或触发重复重载；重置、epoch 变化、未就绪和独立有界补读继续保留。Android 较早消息入口仅在用户翻页时忙碌，本地展开不等待后台刷新，远端翻页等待原读取并核对导航及连接代次。实现与共享核心回归已完成，原生设备验证另计，见[专项记录](../acceptance/thread-filtered-events-20261113.md)。

2026-11-12 F05／F28／F43：Windows 普通 desktop-runtime 默认配置独立桥接，已指定驱动和私有配置优先。打开意图与回执在 Host 本机持久化，未知结果不重放。双端既有打开和发送流程不变，实际可用性仍由 Host 私有配置、运行时与原 owner 决定；见[验收记录](../windows-cold-thread-recovery-20261112.md)。

2026-11-12 F26：双端新聊天统一项目选择、按当前连接隔离的最近项目、已登记目录、刷新与分页。首条显式发送仍由原有创建流程处理。目录展示不提供任意远端路径访问；只读或能力缺失不开放创建。见[验收记录](../mobile-new-chat-destination-20261112.md)。

2026-11-09 F17／F19／F32（UI05／UI12，S04／S11／S18）：Codex Host 将普通 Markdown 本地文件引用与上游结构化附件分开。显示、刷新和分页只登记原助手消息的准确引用，不查询文件元数据、不读取或计算摘要，也不按文件大小或扩展名猜测附件；用户点击后才具名解析并校验新鲜字节。显式原生图片／文件字段继续原处理，其他运行模式保持兼容；并发快照仍合并且保留子过程变化，首次／在途投影不误报 live 保持。此根治方案取代前一轮 8 MiB 防卡门槛。iOS／Android 共用 Host；iOS 沿用原文本链接入口，Android 原消息本地链接入口尚未补齐，正式推广保持兼容门禁；辅助及原 MacBook／物理 iPhone 结果分列于[专项记录](../acceptance/history-large-link-20261109.md)，A6 保持暂停。

2026-11-09 F17（UI05，S02／S11）追加：原 MacBook 隔离 DEV 发现共享缓存已被其他读者扩展后，旧上游游标的窗口会误返空页。Host 现以本次上游页的实际消息边界返回有界窗口；成功较早页更新历史可用时间，最新页的观察连续性与实时控制权限独立核对。iOS 验收增加可见正文断言与原生截图；Android 共用 Host 分页行为，原生双端和物理设备的实际通过分别记录，未借用 iPhone 结果。

2026-11-09 F01／F47（UI01／UI10／S01／S11／S18）：iOS 冷启动先绘制本地恢复进度页，聊天状态／历史与任务 SQLite 的读取、解密和解析在后台执行，完整快照就绪后才创建主界面；缓存缺页回退每次恢复最多读取一次旧版整库。启动期间配对链接暂存，外部文件立即进入原收件箱，仍由用户选择草稿；失败沿用原数据保护与重试，不重放未知输入。同轮减少逐页 Keychain 调用：每次恢复仅在局部读取器中复用历史页解密密钥，每页仍独立认证，退出恢复即释放，不跨启动缓存密钥。 Android 的现有恢复路径不改，本次未新增 Android 通过；iOS 构建、恢复回归、交互和真机结果分别记录，A6 继续暂停。

2026-11-18 F51/F52 共同断言补充：逐电脑通知设置包含已保存的非示例 Codex 与 EasyWork 电脑，标识来自各自真实连接元数据；刷新、改名、恢复与删除后列表保持一致。关闭一台电脑只影响该电脑，不能改变其他电脑或通知类型。单台电脑缺少可用连接授权时，保留其资料并单独提示，不阻塞其他已授权电脑的通知登记；存储损坏及目标不匹配仍走原恢复边界，不借用其他电脑的凭据。后台就绪状态来自准确目标的实际登记报告，不能同时显示与其矛盾的固定未就绪提示。此处规定行为，iOS 既有验收沿用，Android 新增场景及当前结果只记于唯一功能清单；验收关联为 UI11、S15/S16。

2026-11-18 对齐约束：F32（S04/S18，UI05/UI07/UI12）HTML 预览默认离线，单文件本次打开可临时允许限定 HTTPS 资源，撤销或关闭后失效；脚本仅在隔离页面执行。F36（UI07/UI08）标准无闪电、快速一个、极速两个，紧凑入口保持可见；F42/F58 自填回答仅用空白判断决定是否提交，非空正文逐字保留。Android 对应生产路径已补入本地集成候选，iOS 行为不变。F52/F54（S15/S16，UI11）Android 以单一 FCM 入口按保存的完整 Codex/EasyWork 目标投递；通知偏好、登记代次、原操作回执、去重和点击草稿保护均不得绕过。Codex 使用显式协商的 v2 推送登记；EasyWork v1 结果不明时只保留原件和只读诊断，不以当前状态追认旧提交。配置、编译、模拟器与真实供应商送达分别核验；当前实施及验收结论仅见[唯一功能清单](../plans/android-ios-alignment-20261107/progress.md)。

2026-11-17 M287补充：最终runner f715已按1188/cf350033正式runner-only编译75.559秒exit0，Root全源/完整ZIP/DEX/签名/XML独核；App c85独立复用原完整构建，4个test-only差异不冒同次App重建。通知实际冻结源为545846a4，前稿36仅两个局部变量名不同，Root实际采纳和编译一致，原文案口径另证。原/新三runner的AXML逐字相同，完整声明为5provider/5总process，运行quiet门仍原3/3；instrumentation从实际XML核，badging无此字段。元数据reader版本差异不得靠改原raw消除。新包未安装/Native，完整98check v4待执行；其它平台和全部真实DEV门不因此升级。
2026-11-17 M286补充：通知新增偏好测试的默认alias依赖已以单文件独占UUID fixture替代，仅新方法变更并保留原4模式断言；实际线程/原storage executor退出、先保存合成原件后精确清理与unknown保留分别取证，尚未SDK/Native执行。自原c85 App编译图起累计仅4个androidTest文件变化，后续runner-only实际结果须独立关联，不伪称重建App。98check v3因Root遗漏标准附件测试的专用Simulator范围而中断，前34完成/35未决/后63未达，非完整通过；该唯一Simulator及6fixture已精确回收，下一轮保留原标准门。iOS233生产源未变、物理iPhone继续延期。当前状态和数量仍只见权威计划。
2026-11-17 Root M285补充（F73，S01/S11/S14/S18，UI02/UI03/UI12）：固定第三profile的两test-only cleanup源与Live观测补丁已具名合成；原67宿主向量7失败/修后0、独审17+50通过及20个JS时序标量反例均仅辅助。真实JSONObject nullable字段省略、普通LAN第三记录和原QUERY/DELETE守卫分别核；无AndroidKeyStore/Native执行。未采到完整loading帧、普通poll跨入口ID前进时对应门明确false，其余断言继续，JUnit0不等于这些格通过；Source/精确数值/coverage/TTL/lease不放宽。此三测试源尚未runner补编，原c85/077及iOS SDK编译不能转签新版本。98check前两轮分别lint及源摘要关联拒绝，原件保留，完整新轮待执行；当前数量与排程只由权威计划决定。
2026-11-17 Root第二批源码边界记录（M283）：F18/F37/F51/F54/F68/F71及F73 Live有限修改已合成，原候选反例与修正分开保留；50项阅读、47项通知、32/17项缓存和16项队列均为宿主辅助。Android1188源实际完整编译81.661秒exit0，Appc85da8df/runner07778ae9已逐源/ZIP/签名独核，未安装；iOS233源App/双架构测试包57.400/314.325秒编译通过，未运行；完整新pnpm check待执行。Live实际两入口和有界生命周期已编译未运行，固定第三资料清理适配尚未进该runner。这里只记来源与有限实现/验证边界，当前开发/验收/数量/排程唯一见权威计划。

2026-11-16 Root首批检查点（M279）：F40/F49/F50/F55/F73有限修改及F04来源核实已通过完整pnpm check 97顺序阶段（713.040秒），F40永久生产函数回归14/14实际纳入该链。Android App/runner完整1184输入、82任务编译通过（sourceSet 2c381f13，App35de7602/runner5a77b3b7，fc12签名）；iOS App和EasyWorkNative测试包在233输入图上双架构编译通过（83.279/303.027秒），新增共享loading观察方法已真实编译。以上为辅助/完整类型和链接证据，新包未安装、新增Native方法及64格均未运行，真实Provider/功能交互像素和整项通过不增加。后续F18/F71/F51/F68候选仍独立、未混入这些指纹；当前状态仅由权威计划维护。

2026-11-16 Root第二切片（F04/F40/F49/F50/F55/F73）：原grant/完整target/旧namespace主链已核，F04开发状态在唯一计划细化为部分实现。迁移新增8个完整生产图测试入口和额度64格前台/超时候选均待Native；iOS额度pending共享可观察、详情loading与partial读屏同步补齐，双端身份/来源/五秒/两槽/消费者规则不变。iOS阅读链路仅源码/宿主证据未证同类缺口，无额外生产修改；真实Provider、交互、像素及物理格不新增通过。模板与manifest是本页生成源，当前进度仍仅由权威计划维护。

2026-11-16 首批集成候选：F40/S08/UI09共享Host的desktopProposedPlan兼容分支在最新同thread/Turn请求completed=true时停止投影，不回退更旧未完成项；原ID/正文/合成计划优先级不变，Android/iOS Codex共同受益，EasyWork无代码影响。F49/F50/S12/S16/UI02/UI11 Android可见正文在原lease/epoch下等待最新协调任务完成，再由原ledger事务核精确版本，遮挡/后台/目标换代不续租，问题通知/旧ACK/新版本不误清。iOS对应时序待本批源码复核。两修复已由Root采用到候选，辅助14/14与5场景不等真实DEV；功能/交互/UI及Provider矩阵仍按权威计划开放。

2026-11-16 P5／F73（S01／S11／S14／S18，UI02／UI03／UI12）：Android 两个额度展示入口及详情共用同一 Store，进行中（含排队）读取必须可观察；手动刷新按钮随共享读取禁用并显示加载，partial 在底部无障碍描述中保留。五秒限频、来源连续性、精确数值、零与未知、合法兄弟指标不变。本次仅 Android 展示和 Store 修复，不改 Provider 协议或 KMP／iOS 源码；Codex／EasyWork 均受影响，iOS 共同断言仍适用但未新增通过。保存真实来源的离线 JVM 回归 18/18；新增默认关闭的原生视口源码候选，64 组合均待 Root 编译和调度，不能升级当前包真实 DEV 或整 F73。当前状态仍由权威计划维护。

2026-11-16 P3 有限候选，F55/N1.1–N1.2（S18/UI12）：Android 的手动/二维码连接原始 `token:<clientId>` 在迁移时按已验证 Codex 客户端与现有32–512字符、无空白边界校验，避免误作 JSON 拒绝合法凭据；原bytes、操作intent/result和惰性claim原件不改，不新增执行或重放能力。22项仅轻量 JVM 凭据边界辅助通过（DTO stub）；3个候选/发布失败重试/冷读 Native 用例已补、未编译执行。iOS源码与迁移格式不改，Codex账号字段不作新拒绝条件；EasyWork凭据继续原惰性archive边界，不接入此原始token通道。完整24域、双端/Provider功能交互界面及真实DEV均未关闭，当前状态由权威计划维护。

2026-11-15 F17／F24（UI05）：已完成原轮次的阅读缓存从 `history-fallback:<turn>:item-N` 衔接到原 owner 的同一 `item-N` 时，同一正文只展示一次。只在完成证据、准确轮次及序号、role/kind/回复阶段、完整正文与detail/replyMessageId均一致时逐项消费；同文不同序号、另一轮次、未知/活动状态、工具及附件仍保留。共享Host Adapter影响iOS与Android，两端原生布局、token、文案和原输入协议不改。实际macOS DEV已观察到6条Host用户投影对应5条权威输入的失败，源码窄修与辅助/部署/真实复验分列；未新增任何整项或iOS/Windows通过，当前状态与退出条件以[权威计划M235](../plans/android-ios-alignment-20261107/progress.md)为准。

2026-11-13 Q02／F18／F19／F20：Codex × Android API 33 实体的独立 DEV 生产卡片场景已实际通过并释放执行权（run `c4e9d1f7-9d28-43d0-8ab6-bc8c65b8dfce`）。真实 Session/provider 与原卡片完成折叠、展开、再次读取和刷新；同一已完成真实 child 的父子身份及 8225 字节详情与独立来源一致。共同断言继续要求全文不被预览截断、刷新保留展开、原生跨行选择与系统 Copy 精确对应原文，菜单和同父聊天搜索页往返保持展开及视口。本轮 302 布局行／16 帧覆盖、156 字节复制及用例收尾恢复空剪贴板已核对；空基线和清理是验收资料保护，不是产品复制后自动清空的规则。详见[本轮验收及指纹](../acceptance/mobile-parity.md#q02-codex-android-20261113-c4e9d1f7)。

本次只细化 F18/F19/F20 的局部通过：按具名 MIUI 临时启动前置执行后恢复原模式，0 新增模型／child／队列／停止操作，独立桌面再次核原父 owner；未新增当前 child owner、进行中输出增长或自然迟到事件证明。iOS、EasyWork、ordinary 主安装／原 grant 恢复和系统集成均不新增通过；F24 的广范围窗口恢复／返回最新不在本例。生产规则、传输上限、设计 token 和文案未改变，旧失败保留，当前状态与退出条件仍由[权威计划 M109–M111](../plans/android-ios-alignment-20261107/progress.md)维护；代码未合主线、未发布。

2026-11-13 W2（F18/F19/F20/F60）：新增debug限定的独立原生DEV资料入口，使用真实MainActivity、Session、provider、卡片和菜单；控制电脑Host与Provider完整目标分别核准。资料根/alias/新设备身份与授权归本任务，原资料不挂载、不复制原grant；系统集成不装配且单列未覆盖。这里只登记源码候选和语法/工具辅助，完整Android编译、实体卡片/实时owner和真实DEV仍待验，不升级整项。来源与退出条件见[权威计划M25](../plans/android-ios-alignment-20261107/progress.md)。

> 当前开发、验收、剩余项及调度只维护在[权威状态和计划](../plans/android-ios-alignment-20261107/progress.md)。本页日期段落和表格保留各提交的需求与来源快照；后续结果以权威计划为准。

2026-11-13 W4 R2（F01/F02/F04/F37/F38/F39/F43/F44/F61/F73，Q01–Q04）：新增固定独立 iOS 原生 DEV target，真实 App/SessionStore/AgentClients/UI 仅在新 bundle 的启动准入后装配；先核新容器/自己的默认 Keychain 组、完整本机 EasyWork target/pin/lease，恢复前核全体 clients，UNKNOWN 保留且只查原 ID。正式/原 DEV/旧 Acceptance 与原 C2/W5 断言不改；Android 无变化。新 target 省略 AppGroup/APNS/time-sensitive/扩展，Codex/Relay/Task Hub 与系统整合不计覆盖；45 个实际 Swift 策略反例、19 个脱敏 usage 来源验收 helper 场景及项目结构为辅助证据；usage 必须经 v1/KMP 验证并匹配独立桌面完整 target/adapter/scope 证据，合法 unavailable 明确未通过且不 skip，新 target 完整编译、签名、原生/真实 DEV 尚未执行。共同断言、双端影响和验收入口见[来源记录](../plans/android-ios-alignment-20261107/records/w4-r2-native-dev-isolation-20261113.md)，当前状态仍由唯一计划维护。

2026-11-13 W3／Q03-Q04（F34/F35/F36/F37/F38/F39/F43/F44）：新增全新任务隔离 profile 的真实 MainActivity/Session/production provider 原生 UI 用例、离线冻结校验与原桌面只读 witness。模型/强度及显式原生速度、原队列/Turn/操作 ID、未知只查询和单次输入均要求具名租约与独立证明；不注入 Host 速度，不改生产语义。最初源码交付时尚未 Android 编译；后续统一App/runner typing由总控另核，本线手机或真实模型仍未执行；iOS 契约未改，其验收仍另列。后续只读witness增加独立child proof联结与精确原进程展示外壳核对，按W5 v2固定官方history契约保留child历史可读/owner未证/控制未知，parent仍须原owner；combined另核同一child执行Turn的零主动工具verified事实；未知poll单列，不升级真实DEV。预算/接口/未验边界见[来源记录](../plans/android-ios-alignment-20261107/records/w3-q03-q04-native-harness-r2-20261113.md)，不升级整项状态。

2026-11-12 F04／F60（Q01）：Android 增加无 Activity 的既有目标元数据准入 helper，仅经 ExistingReader 读取完整目标、保存路由、证书 pin、公钥摘要及原 grant 的 pending／expiry；不恢复普通 session、不导出凭据、不发网络或模型请求。DPoP 新增 internal existing-only 加载入口：缺少原 alias、类型／曲线或系统 KeyStore 不可导出条件不符时拒绝，不创建或替换 key；普通配对和产品正常刷新流程保持原行为。iOS 本轮不受影响。源码与边界辅助、原生反例、实际安装和真实 DEV 分别留证；helper 存在不代表原 grant、runtime 可控或 Q01 已通过。

2026-11-12 W4（F34/F36）：iOS速度回调按当前目录调用KMP generationSpeedAllowed，fast-only拒绝旧standard值，空值继续跟随电脑。Android既有准入保持；14个实际setter抽取场景及真实KMP策略辅助通过，完整App/UI/真实DEV待验。见[来源记录](../plans/android-alignment-20261022/records/w4-ios-speed-setter-20261112.md)。

2026-11-12 W3/W5合成候选：F34/F35/F36已有EasyWork轮次的生成字段仅取原轮settings，缺失保持未知，零轮次才用聊天默认；Android速度旧回调按当前目录具体档位复核。F73共享投影避免桶/指标ID碰撞与叠加时钟容差，Android详情保留数值和无限权益、缺省桶标签使用指标标签。iOS共享投影受影响，Android专属展示与iOS既有语义对齐；只登记源码/轻量辅助，当前原生、完整UI和真实DEV仍待验。当前状态由唯一对齐计划维护，精确源提交见本轮W3记录及合成manifest。

2026-11-12 F18／F19／F20（UI05／UI06，S04／S05）：EasyWork 共享子代理投影以权威快照中的 child、root、branch 和正文为同一整体；迟到事件即使指向同线程内另一个可见 root，也不能重挂已有 child。事件仅为快照尚未包含的 child 提供等待状态。iOS／Android 共用此修复，Codex 适配及传输上限不变；JVM 辅助与未执行的原生门槛见[专项记录](../acceptance/subagent-identity-20261112.md)，当前状态仍以唯一对齐计划为准。

2026-11-12 W4（F37/F38/F39/F43/F44/F61）：iOS 原回执协议显式捕获原 threadId，在任何原队列查询前拒绝错线程；待核对与结束等待查询沿用原 ID/不可变意图。Android 停止与 iOS 停止/取消仅接受 JSON 布尔 true 确认，保持新草稿独立。开发为本轮源码候选；轻量辅助与未验原生/真实 DEV 分列于[专项记录](../plans/android-alignment-20261022/records/w4-original-thread-and-ack-20261112.md)，不关闭整项。

2026-11-12 W4／Q11（F37/F38/F39/F43/F44/F61）：iOS 独立 Simulator 验收宿主的应用标识／默认 Keychain 组按实际 bundle 展开，使用 Xcode 正常签名与模拟权限链接段，保持生产方法和原业务断言。原 `testC2SyntheticEncryptedQueueAndReceipt` 在 SDK27.1／iOS27.0 下实际 1 方法、1 通过、0 失败、0 skip，覆盖错线程提前拒绝、严格布尔 ACK、入账丢 ACK 原 ID 核对与单次发送；这里只升级该合成原生子场景，旧失败与中断仍保留。Android 代码／既有结果不变；原 owner 的真实队列、追加、停止、丢 ACK、新草稿 UI 及完整真实 DEV 仍待各自验收，A6 保持暂停。构建参数、真实二进制两段摘要及验收关联见[来源记录](../plans/android-ios-alignment-20261107/records/w4-q11-native-simulator-20261112.md)，当前状态仍由唯一计划维护。

2026-11-09 F03／F69（UI01／UI10，S01／S11／S18）：Host 元数据辅助进程失败后保留退出与 stdio close 屏障，原生队列初始化中关闭立即回收，只终止本进程创建的准确 child；SIGTERM 后一秒仍存活则升级 SIGKILL，未确认关闭不重建，未知输入不重放。macOS Trace 回收在全 payload inventory 前增加新鲜占用早拒，原删除前后身份、内容与占用校验全部保留；同结果保留／失败项按 1／2／4／8／15 分钟退避，active 仍五秒，新目录不等待旧项。iOS／Android 共用 Host 恢复路径，界面、token、文案不变；本轮为隔离辅助证据，正式服务、真实原 owner 写入、手机与 Windows 未验收，A6 保持暂停。见[专项记录](../acceptance/host-lifecycle-cpu-20261109.md)。

2026-11-08 F69：Windows 退役 Responses 转发和模型 URL 覆盖。iOS 与 Android 连接 Windows 时均不显示来自该转发的灰字，继续按现有能力和有效期处理建议。共同断言是 Host 离线不阻断原 Codex 模型连接，手机端不显示失效建议。Mac 原生来源保持独立。源码、正式 Host 恢复和真机证据分列于[退役记录](../acceptance/windows-response-suggestions-20261104.md#退役与恢复)。

2026-11-04 F69 Windows Responses 采集：按人类最新要求取消生产前台确认门槛，Host 保存原生成的建议全文、来源聊天／轮和 response ID；不把网络候选表述为已验证桌面可见或安全后结果。共同断言为原 owner／最新完成轮校验、错误／取消／空值／新请求不回退旧正文、读取不生成、采用仅改本地草稿。iOS 复用兼容 native-version 接口，Android 状态不提升；macOS 原安全后采集独立保留。实现、辅助、DEV 和真机状态见[专项记录](../acceptance/windows-response-suggestions-20261104.md)，A6 保持暂停。

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

2026-10-22历史执行入口为 [Android 分步进度](../plans/android-alignment-20261022/progress.md)；2026-11-12起当前状态与调度统一转至[权威状态和计划](../plans/android-ios-alignment-20261107/progress.md)。217 条审计及 70 功能/21 页面/18 场景保留完整覆盖；X01–X09 是既有功能的细分核对项，登记在 UI 规格，不改变功能 ID。Task Hub 业务任务/项目、业务通知和新增项目记录管理暂缓，A6 继续暂停。历史“仅基础示例”等状态按原日期保留，不作为当前源码结论。

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
- `mobile-parity.manifest.json` 是功能定义和来源关联入口；本页表格由 `pnpm generate:mobile-parity` 生成，`pnpm check:mobile-parity` 检查完整性及生成内容。manifest 中的实现／验证字段保留为来源快照；Android 对齐的当前状态、数量和调度只由[权威状态和计划](../plans/android-ios-alignment-20261107/progress.md)维护。

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

2026-11-15，PR3 公开 CI 后续修复：F32/F72 下载进度的 Swift KVO 显式属性/类型绑定保持原进度、字节和取消规则；F69 仅稳定原生回归的请求调度，生产建议读取未改。Android、Host、共享核心及协议保持原实现/验收范围，不新增双端功能结论。实际失败、辅助验证及后续交付见 `docs/acceptance/pr3-android-cold-ci-20261115.md`。

2026-11-15，PR3 公开 CI 修复整合上传恢复主线 `50681c2`：保留其 F30/F31/F59 实现、说明和原验收指纹；补齐同类 KVO 编译绑定，重新登记组合源码。Android 冷会话因 Host/适配器变化独立复验，旧指纹保持历史分类。专项结果关联 PR178 与 `docs/acceptance/pr3-android-cold-ci-20261115.md`，不扩大为上传业务整体验收或真机通过。

PR3 后续公开 CI 仅调整下载原生回归的冷 framework 构建阶段与预算，实际下载/Swift 断言期限保持；F32/F72 无产品或双端 UI 变化。1201 文件原生验收源指纹匹配，Android 冷会话按准确源复用，CI 构建不升级手机或上传完整业务结论。

PR3 公开 macOS 的独占模拟器准备与下载业务断言分列：冷 boot/bootstatus 共用10分钟，listener就绪由主机和模拟器独立nonce健康请求核对，错误健康标记必须拒绝且下载计数保持0；仅健康阶段的瞬态传输错误有界等待90秒。原19处Swift业务前置条件、完整下载场景、120秒编译和60秒业务执行保持；fixture元数据请求使用与生产路由一致的专用ephemeral session。失败保留阶段/连接/请求计数、迁移输出尾部和无敏感值代理诊断，不绕过失败或自动重放业务。产品及1201原生源不变，Android冷会话精确源匹配复用。 本项是原生夹具准备/诊断变化，F32/F72双端产品UI、生产超时与原暂停范围保持；不新增手机网络或上传业务验收结论。

2026-11-15 PR3后续组合：保留3e5920d主线F76定义及既有记录，独立追加fixture准备与负向验证；旧de736完整CI和1201源复用保持历史，不替代组合候选重新执行的Android冷会话/原桌面证明。准确候选、原生结果和最终CI/同步见[Gitea PR181](https://git.51easyai.com/BCAI/agent-hub-app/pulls/181)。

2026-11-15 F69 PR3后续CI修正仅影响Worker测试夹具：真实1800ms总预算、发送前过期零帧/零Native等待与退休、以及原在途未知失败/750ms批次/300ms跨帧断言分别留证。双端生产代码和验收状态保持，1250源匹配复用Android冷会话，后续完整CI按准确候选回执核对。

2026-11-13：Android独立DEV资料入口以公开SDK的ApplicationInfo.dataDir与Context.getDataDir共同指向自有目录，设备保护路径仍拒绝；删除不存在的credentialProtectedDataDir字段以修复实际编译错误。关联F18/F19/F20/F60，iOS不受影响，原失败证据保留，修后统一原生编译和真实DEV结果见权威计划。

2026-11-13：F73 iOS将同一额度指标Section及共享原因字符串拆出类型边界，修复实际Swift类型推断失败，显示/顺序/规则和原测试断言不变。F18/F19/F20/F34/F60 Android独立验收入口分别冻结控制电脑与Provider完整机器身份，新DEV Host不要求沿用控制HostID；iOS固定新target完整EasyWork准入不变。实际编译、实机与来源结果见权威计划。

2026-11-13 F73：iOS将KMP返回的原因字符串显式接入既有LocalizedText翻译入口，修复真实原生编译定位出的类型错误；沿用原文案、视图和Store断言，Android不受影响，原失败和续验分列于权威计划。

2026-11-13 Q02：实际原生读取用例与独立资料入口同时区分控制Host和Provider完整机器目标；不以相同HostID代替本机映射，保留新包/证书/设备/来源/lease验证。iOS不受影响，真实子代理读取仍待验。

2026-11-13 F04/F60：Android验收增加独立、无Activity的安装指纹方法，仅从准确acceptance包的targetContext取得ANDROID_ID SHA，并经instrumentation status返回API、UID、包名、实际App SHA及该hash。Android 8+的App/签名/用户作用域不能由adb shell值代替；该hash仅服务独立DEV Permit的安装身份校验，不改变Codex机器目标或账号策略。方法不读写App资料、不访问KeyStore/网络，原metadata断言保持；iOS不受影响，本轮尚未原生运行或取得实测hash。

2026-11-13 F04/F60/F73：上述Android installation fingerprint方法仅追加实际targetContext configuration的languageTag、orientation、fontScale，总八字段一次返回安装身份与F73视口输入；原flags/App SHA/UID/包名校验和原metadata方法保持，AndroidID仍只输出SHA。无Activity/系统设置修改/App资料读写/KeyStore/网络；iOS不受影响，源码辅助检查不代替实体配置或真实UI验收。

2026-11-13 F73：Android新增独立资料真实usage UI只读用例候选，复用已审Q02私有profile与正式新绑定、Main UI和production Store；启动前冻结设备/包/完整Provider target与控制电脑映射、真实桌面来源及指标摘要，实际footer/详情/手动刷新和目标窗口截图分步记证。保持Store五秒节流，不直接调用provider凑刷新；来源变化/过期/不可用按失败或未核保留，瞬时loading与wire请求归属不自动升级。仅测试来源登记，尚未编译/上机；iOS与生产业务规则不变，真实验收见唯一计划。

2026-11-13 Q03/Q04原生驱动：显式速度时只用既有native selector一次选择模型、强度、速度与许可，删除此前手动选择后重复开菜单的步骤；保留全部最终设置、原ID和预算断言。原effort-xhigh节点缺失失败及模型0证据保留，新runner和真实DEV另验；生产UI及iOS均不变。

2026-11-13 Q03：增加默认关闭的selector观察回调及九阶段公开readiness/实际设置/原ID/自有窗口诊断；无法确认图片owner时跳过，无全屏回退。不改变生产、UI动作、原断言、Gate或模型预算，不增加等待/刷新/Sender；iOS不受影响，实际编译/运行另列权威计划。

2026-11-13 Q03/Q04：仅原生验收同步，每次原点击前以单调30秒有界等待唯一enabled真实控件，滚动后确认可见，仍保留原enabled断言、一次点击与所有最终设置/目标/预算门槛；超时仍失败。九阶段诊断不变，无生产/iOS变更、直接赋值或发送重试。当前readiness波动原失败保持，正式typing及真实DEV以权威计划为准。

2026-11-13 独立真实DEV入口：API33实际系统UI_MODE配置变化512触发Q02 Activity重建，原有限lease拒绝导致崩溃。仅debug manifest声明uiMode由该Activity处理，沿用原生ComponentActivity/Compose配置更新；不固定主题/方向，不吞其他配置，不开放再次claim或saved/retained恢复。生产Main、原Session/输入边界和iOS/A6不变；新包及实际结果见唯一权威计划。

2026-11-13 F18/F34–F36：Codex事件cursor是Host全局序号，threadId过滤可产生同epoch、ready且非reset的空批次。该批次只推进游标，不应使已验证聊天菜单/输入配置失去就绪并重载历史；明确reset或epoch改变仍走权威修复，ready=false仍拒绝。双端共享回归先取旧规则反例，实施和实际验收见唯一计划；EasyWork既有适配不变。

2026-11-13 Q02：仅补Android真实DEV原生交互验收，保留卡片完整正文与刷新断言，逐屏记录完整可见行，实际跨行拖选并点系统Copy核Clipboard，验证菜单Back及同一父聊天查找页面返回后的展开/视口。复制范围仅来自独立冻结的本task96行marker；既有未知剪贴板不读取/覆盖，Copy前复核变化与空状态，仅确认属于本次的复制项才清理。原空保持和实际清理分别记录，原生toolbar允许归属已核的同App APPLICATION独立窗口。生产App/iOS不改，无fixture/新child/第二模型；新runner及实际原生结果另验。

2026-11-13 Q03/Q04验收时钟：仅Android真实DEV测试驱动采用Native单调请求挑战及传输clock侧证。原Host观察和proof保持，30秒新鲜度、45秒原工具窗口及lease预算不扩。主App、iOS、产品UI均不改；新runner与真实原Turn另验。选完Sol/xhigh/fast/full-access仅为当前视口实际交互证据。

2026-11-13 Q04 terminal budget evidence (F37/F38/F39/F43/F44; S07/S10/S11; UI07/UI04/UI10): canonical witness binds the complete final stopOrigin Turn to the original native operation ID/body SHA, requires one original bounded command and observed zero child across the two new Q04 Turns, and excludes the completed baseline Q03/its existing child. Unknown/incomplete/conflicting facts stay null. The one-origin queue-completed stage is unaffected. Controller admission stays strictly below40seconds; Native/outer45seconds remains the final fence, with no refreshed tool start or expanded five-body budget. Existing production App/UI and iOS/EasyWork semantics do not change. Pure canonical regressions and private stageReady checks are auxiliary only; new runner and real Q04 results stay separate under the sole current plan. See the [acceptance contract](../acceptance/mobile-parity.md#2026-11-13-q04-terminal-budget-facts-auxiliary-only).

2026-11-13 Q02 exact-tag layout synchronization (F18/F19/F20): after a single SDK toggle touch, the AndroidTest relocates only the same original message tag in the same message-list, then asserts the unchanged expected StateDescription. Wrong/missing cards still fail; no second or semantics click, changed body/expectation or budget increase. New failure-only diagnostics preserve the original Throwable and use exact-tag facts/target-draft-original-intent hashes plus bounded own main-Window AX/PixelCopy, never a whole-display fallback; missing main focus records capture unavailable. Existing successful shot guards/actual popup-menu-toolbar coverage remain byte-identical, with no new main-focus gate. Original084b7.361s failure remains. Production App/iOS/EasyWork and profile/zero-model guards are unchanged; typing and future Native/DEV results stay separate. See the [acceptance contract](../acceptance/mobile-parity.md#2026-11-13-q02-exact-tag-layout-synchronization-auxiliary-source-only).

2026-11-13 Q02 Copy observer (F18/F19/F20): preserve direct matching and additionally prove the exact native Copy label's nearest clickable parent within the same already-owned application window, with exact description, contained bounds and no competing subtree. Ownership/uniqueness, fresh observation, SDK touch, frozen original bytes, empty-only clipboard and5000ms wait remain. Enable/restore AX observer flags around selection creation; bounded owned-window rejection metadata preserves the original failure. Actual7adac Copy identification failure is not rewritten; its exact MIUI rejection cause remains unproved. Production App/UI, iOS and EasyWork are unchanged. See the [acceptance contract](../acceptance/mobile-parity.md#2026-11-13-q02-owned-copy-label-and-parent-observer-source-only).

2026-11-13 Q02 clipboard epoch observer (F18/F19/F20): after the unchanged last empty fence and real Copy touch, bind callbacks, direct checkpoints and returned payload descriptions to one fresh stable epoch with a supported classification trajectory. Notification count is diagnostic. Original447..603/156-byte equality remains mandatory; unique original marker slices establish cleanup-only ownership. A single rechecked own clear and focused empty observations at observer close are separate facts; unknown/new epochs, lost focus or failed close cannot claim restored. Original8538 failure and its existing unverified clipboard remain preserved. AndroidTest only; production App/UI, iOS and EasyWork unchanged. See the [acceptance contract](../acceptance/mobile-parity.md#2026-11-13-q02-clipboard-epoch-and-closed-observation-source-only).

2026-11-13 Q02 auxiliary evidence correction: preserve the original60-vector output but do not count its four payload negatives or unknown-clear precondition as complete rejection coverage because self-assertion exceptions could be swallowed. Credit corrected source and unsafe mutation rejection only from actual new SHA/raw results; original8538 failure and unknown clipboard remain unchanged.

2026-11-13 F19 Clipboard metadata-only AndroidTest: a separately leased default-Application/blank-ComponentActivity diagnostic reads public presence/description metadata only after combined foreground, focus, AppOp and own-window checks. API33 unsafeCheck is not sufficient alone. No body getter, clipboard write/clear, Main/Session, profile/grant or model action; inconsistent observations or failed observer/scenario close remain unknown. This does not prove Copy or restoration. Production App/UI, iOS and EasyWork are unchanged. See the [acceptance contract](../acceptance/mobile-parity.md#2026-11-13-clipboard-metadata-only-diagnosis-source-only).

2026-11-13 corrected auxiliary evidence: private probe `58404862943faacac6b4d44161aebfe95a6ef21df76b94a50745260a56b7e7f3` independently passes the original60 vectors on the actual guard. The old probe still falsely reports60 under the combined unsafe payload mutation; the corrected combined mutation, four individual payload mutations and reversed unknown-clear precondition each fail with exit1 and the exact expected assertion marker. The original swallowed-assertion outputs/failures remain preserved and are not complete boundary proof. Test-only mutants are excluded from formal source/APK. This is auxiliary sensitivity evidence, not actual clipboard/Native/DEV acceptance.

2026-11-13 Q04 role 等待与原资料有限续验（F37/F38/F39/F43/F44/F61；UI07/UI04/UI10）：验收器应按真实 `role=tool`、`status=inProgress` 与准确当前 Turn/Run 等待原工具，不将 `kind=commandExecution` 误判为不是工具；原窗口、意图、正文 SHA、操作／队列 ID 及实际 UI 断言不变。原 Q04/3c8688a7 的 197.422 秒 Native 失败保留，独立来源已确认首条 origin 实际完成，不能因验收器超时重发。

续验仅限本任务原独立 profile 的 test-only existing-owned 入口：新具名租约下先核原完整目标、资料／身份／alias／grant 和原意图，所有权不符或资料缺失在写入前拒绝，不回落创建、不重配对或申请新 grant。仅余四份冻结正文／slot，只有 `stopOrigin` 可占最后一个新增 Turn；原 claim、已消费输入与旧失败保留，40／45 秒围栏及原工具起点不因恢复或刷新证明而重置。入口源码与最终来源须另行冻结，不能把准备材料或局部 typing 当作已执行。

上述 role 等待与原 profile 接线是 Android debug/androidTest 的受控验收接线，不新增普通产品的恢复按钮或保证，不提升 F37/F38/F39/F43/F44/F61 的实现／验证状态；iOS 无受影响代码，EasyWork 仍需独立真实验收。共同断言及反例见[有限续验合同](../acceptance/mobile-parity.md#q04-existing-owned-remaining-contract-20261113)，当前调度仍只由权威计划维护。

2026-11-13 原队列立即执行的显式 Turn 前置条件（主关联 F37，邻接 F38/F43/F44；UI07/UI10、S07/S10/S11）：`expectedTurnId` 已显式指定时，在移除原队列项并 follow 原 owner 后、真正追加前，原 Turn 变为 `null` 或换为另一 Turn 均须拒绝，不能将原输入自动降级为 `next-turn`。追加 wire 保持原 expected Turn；已确认接受的原回执仍只核原结果，已移除但未确认的 detached/uncertain 仍仅查原 ID，不再移除或重新投递。未提供 `expectedTurnId` 的原普通行为保持；这不是禁止所有空闲立即执行。

该项是 Codex Adapter/Host 的生产修复，区别于上述 debug/androidTest 资料恢复。iOS 与 Android 通过同一修复后 Codex Host 均受该前置条件影响，两端本地代码本次不改；EasyWork 独立 Provider 路径未改、须独立实测。当前运行的 Node249 未部署该修复；W3 的有限 fixture/构建、新产物、实际部署和真实 Q04 需分别留证，不把旧249记为新通过。原3c只读证明尚未到队列动作，不能将其失败归因为本竞态。详见[显式 Turn 队列合同](../acceptance/mobile-parity.md#q04-queue-expected-turn-fence-20261113)。

2026-11-13 Q04 existing-owned 来源后续（F37/F38/F39/F43/F44/F61，test-only）：此前 v2 阶段四源按 Root v2 adoption 冻结，Native 采用 `8b2c7f82` 而非旧 `44371a60`。`prepareExistingQ04`／`existingQ04Owned` 仅供 Root 完整准入后的内部 debug 验收调用，要求既有 namespace/key、原目标／身份／grant 及 live owned policy，缺失即拒绝、不回落新建。普通公开构造默认不安装 owned policy，普通路径不变；这不是新增产品恢复模式或普通原 grant 恢复通过。

原 profile 的 rootDecision 只绑定历史不可变旧 lease release，当前 authority 独立引用该 release 与 existing-profile，避免 SHA 自引用；旧 release 的已释放／已消费一份输入／未授权续验字段必须核实，不能据旧回执取得新执行权限。当前 v2 仅源冻结，W4 源审与正式 typing／双包分别留证，未新增真实 Native 或剩余四 slot 执行；来源及边界见[验收补充](../acceptance/mobile-parity.md#q04-existing-owned-v2-source-20261113)。

2026-11-13 Q04 Root v3 写前时窗后续（F37/F38/F39/F43/F44/F61，test-only）：当前四源改为 Native `0cd947f1`／profile helper `f01575ba`，另两源 `2c0256f9`／`f11ce30e` 不变；v2 的来源及 e3bd 通过仅保留原指纹，不转签 v3。每个 slot 的原 proof 只登记一次，在原回复首次同步持久化（含 ready）及新转 submitting 时，重用原 `businessBoundary` 同步核原 proof 30 秒、lease、同一目标／Turn 与原工具 45 秒，跨 Flow 等待超窗即拒绝，不刷新原 proof 或工具 start。

权威／courier 的 v2 合同、默认普通存储路径与原剩余预算不变。三项无模型 Native auxiliary（含实际 Clock 回调 44→46 秒写前拒绝反例）仅已声明、尚未运行，不计 Native 或真实 remaining 通过。最终来源记录及边界见[v3 验收补充](../acceptance/mobile-parity.md#q04-original-proof-write-fence-v3-20261113)。

2026-11-13 Q04 startup v4（test-only）：当前 Native 来源为 `7994abb7`，其余三份 Native/debug/storage 与两份 Node 来源不变。`launchExistingQ04` 仅在既有首个 `ui.waitUntil(30000)` 内，同时等待恢复／加载结束和列表只含原 `old.id`，随后仍核唯一原记录及原 intent SHA；不新增等待窗口，不刷新 proof／工具 start，不改变 v3 同步写前时窗与剩余预算。旧完整整门 728.424 秒／96段退出0保留其原指纹，不能转称 v4 新整门执行；v4 runner／三项辅助与真实 remaining 仍分别待核。见[v4 来源补充](../acceptance/mobile-parity.md#q04-existing-origin-startup-v4-20261113)。

2026-11-14 Q04 首次 Native 前阻塞与显式恢复合同（F37/F38/F39/F43/F44/F61，AndroidTest-only）：本批7078的 controller active/history 来源角色混用，在启动源守卫退出，Native Popen=0、无JNI；这是验收启动受阻，不能记为产品 Native 实际失败或通过。Root已核同reservation的零新正文／新Turn／新child／控制动作事实（证据 `r2-root-confirmed-zero-before-first-native`，ac7b10d0），但固定child已reserved、9份JSON曾暂存并清理、owner锁保留，不能写成零副作用。原3c的197.422秒Native失败与已消费origin另行保留。

Root v5已采用显式pre-Native恢复入口源码，尚未实机／未验收：原833B once继续引用旧authority，原claim／authority／plan不可覆写、删建或重新绑定；须由新的独立RootRecoveryAuthority链接同一reservation、Root已核零事实及新鲜源，原授权到期不得沿用。未知、任何Native Popen或已提交输入均只查原ID，不进入此恢复分支。仅余四份冻结正文、最后一新增Turn／0新child／40秒process／45秒terminal／原session最多一次poll及原proof时钟保护不变。此为Android验收基础设施变化，不新增产品恢复按钮，不改App与三生产源或iOS逻辑；A6继续暂停，EasyWork另验，不提升F计数。见[显式恢复验收边界](../acceptance/mobile-parity.md#q04-pre-native-explicit-recovery-20261114)。

Root v5只改AndroidTest单文件（Native `fa1c66e3`）；恢复必须同时具备 `w3ConfirmedPreNativeRecovery=1` 和 `root-q04-confirmed-pre-native-recovery-authority-v1`，flag本身不授执行。`Q04PreNativeRecoveryAdmission`核同一run／lease／reservation、原不可变引用、known-zero和独立currentAdmission；普通分支保持。新plan仅允许11项时间／runner／来源SHA元数据变化，原oldPlan其余54项及原settings／显式speed／controlHostId从原预算严格核对。courier固定11JSON，即原9份加 `original-root-authority.json`、`original-plan.json`，不传管理key/config/pair材料。App b5b与三生产源不变，当前安装仍a5be旧runner；v5构建中，新增方法／helper未实机。完整来源由本次manifest change登记，旧7994仅从101776 Git／树外快照核，不再把原可变源码路径当作旧指纹证据。

2026-11-14 Q04 Root v6：已知 Native 准入失败后的显式后继与独立只读诊断（F37/F38/F39/F43/F44/F61；UI07/UI10、UI03、S07/S10/S11）。M160/M161 已确认同一7078首次Native确实Popen1、1方法失败（[1,-2]/final-1），但余四正文／新Turn／child／控制动作0；旧3575B“首次Native前零”只属历史，不能再准入该分支。原833B once、新1131B recovery-intent及2026B firstNative marker和旧授权／plan／失败永久保留；新事实 `r2-root-confirmed-native-failed-before-profile-open-no-business`（ef67a1ae）自身也不授后继执行权。

V6 源冻结5b35533b：可信Context根统一canonical的debug helper `0304d5d0`、完整Native `5830710d`（known-failure helper片段33466750）、独立readonly test `52a42c75`；另两源2c0256f9/f11ce30e保持。新的known-failed flag／authority kind须同时成立，原pre-first／ordinary分支不放宽；完整原失败／释放／首marker与原预算、独立fresh／currentAdmission均须核，Unknown不可恢复。只有Root具名审过的canonical修复AppSHA新增第12项元数据例外，余53项plan字段和13项稳定authority冻结，五正文／最后一Turn／0child／40与45秒／原proof不刷新不变。

独立 `inspectExistingOwnedProfileAdmissionAndCloseWithoutActivityOrWrites` 仅用设备elapsed-realtime不倒退、最长60秒的只读lease；不借旧业务lease／ready字段，不装配Activity／Session或新授权，不改原密文／alias／永久marker。73只是有界路径／准入诊断，必须另读原JNI和异常。旧b5b与新canonical App的A/B须分别绑定实际App／runner／source／签名和原资料证据，实际admit→inspect→release／重入拒绝／释放后重获原锁与11bin保持逐项核；未实测不能把源码差异或shell同inode当唯一实物根因，也不能宣称原KeyStore可用。

本条仅Android DEV准入设施源采用，正式Gradle尚未完成、新包未交付／安装，实装仍b5b/a238；新增无模型guard方法及A/B均未验，不新增产品UI或75F状态。iOS产品规则／A6暂停不变，EasyWork、ordinary安装和真实业务控制仍独立验收。详见[known-failed与只读验收边界](../acceptance/mobile-parity.md#q04-known-native-failure-and-readonly-v6-20261114)，当前状态仍只由权威计划维护；上文v5当时包／未启动描述保留历史指纹。

2026-11-14 Root V7 的原锁非创建打开后续（F37/F38/F39/F43/F44/F61；仅Android DEV准入）：debug helper改为 `e74a5cf1`，adoption97d55dcc。原V6来源／构建／五文档检查按其原指纹保留，但未安装的V6包已被替代、禁止用于实机；V7正式包仍待构建／验签，实际安装仍b5b/a238。Native5830710d、readonly52a42c75及另两源保持；不由本次源审／文档检查新增任何75F或真实通过。

单一 `Os.open(O_RDWR|O_NOFOLLOW)` 不CREATE/TRUNCATE；Android借用FD的 `FileOutputStream(fd)` 只供真实channel.tryLock，私有包装器关闭stream/channel后finally关闭原FD。规范锁路径在额外open前同步reserve，重复直接抛原OverlappingFileLockException，不关闭原holder的FD或删除其reserve；自己的reserve保持到全部关闭完成，真实OS锁仍必须取得。原fd/path/inode/dev、regular/uid/mode/size/nlink、子路径拒绝、原key和11bin断言保持。旧rw可能创建、额外同inode FD关闭可能撤锁是条件性源码缺口，不是本次实体已写或已证唯一根因。iOS产品规则／A6暂停与EasyWork独立门槛不变。

A/B仍须独立Root只读准入：旧App A不能继承V7 reserve的安全证明；若旧admit意外成功，进入旧额外open的重入路径需先收窄，不能仅凭最终红／绿继续。73、设备elapsed lease和原异常边界沿V6，V7源采用不授安装、A/B或第二business Native。详见[V7原锁合同](../acceptance/mobile-parity.md#q04-existing-lock-no-create-v7-20261114)。

2026-11-14 Root V8 readonly A/B收窄（F37/F38/F39/F43/F44/F61；AndroidTest-only）：adoption f9e31e31采用只读测试 `a5922622`／27245B，新增 `runtime-source-binding.admissionExpectation`。A为 `known-old-directory-rejection-only`：实际73观测中，两组oldRawContext父路径须abs!=canonical、非symlink、dir／当前UID／0700，且各自canonical等于当前规范父路径、device相同；缺失／不符在旧admit前拒绝，不能记为已验证旧拒绝。旧admit意外成功则立即release并报inconclusive，绝不inspect或再次admit。B为 `current-profile-read-and-lock`，原decode／锁／release／重入拒绝／释放后重获与11bin断言不变。

包与来源分列：A旧App为f015／b5b；B复用实际V7 e74a／App2e234a35及其自己的V7输入／构建来源，新runner单独使用V8 a592测试来源，最终runner manifest待Root复核，不由新test构建冒称App来源。上文V6/V7未构建／待收窄描述保留采用时事实；V7 App现已构建，A/B与第二business Native仍未执行。V7单FD/no-create/open前reserve、原真实失败／三永久记录、73与JNI分离、原预算和75F状态保持；iOS产品规则／A6暂停／EasyWork独立验收不变。见[V8只读分支合同](../acceptance/mobile-parity.md#q04-readonly-old-admission-fence-v8-20261114)。

2026-11-14 Q04 readonly原生结果（F37/F38/F39/F43/F44/F61；UI07/UI10/UI03，S07/S10/S11）：checkpoint `7edf6a06`的实际A保留原1方法失败／JNI及driver inconclusive，Root独立确认精确旧目录拒绝；B实际1方法0fail，JNI `[1,73,73,0]`／final `-1`。B验证原key可用、11份生产解码／保护3记录／原completed操作、持锁重入拒绝、release后重获原锁、closed拒inspect及空锁／原资料前后不变；五份只读临时输入已归档／回收。原传输／Native失败不改写，73不作完成或成功计数。

A旧b5b/f015与B实际V7 e74／App `2e234a35`各自绑定原来源，二者均用V8 a592／runner `2ca74ddd`，不把App归于新test构建。证据basename `actual-readonly-A-root-independent-review.json`／`actual-readonly-B-root-independent-review.json`只关联这个无模型既有profile的读取／锁准入；不是ordinary恢复、模型／队列／Stop或整功能通过。四份业务控制仍0，原1余Origin／0新child不变；75F状态／统计与整F关闭0不变，iOS、A6暂停、EasyWork认证和物理iPhone独立阻塞保持。详见[只读原生实际门槛](../acceptance/mobile-parity.md#q04-readonly-ab-native-result-20261114)；上文采用时未执行／未构建描述保持历史，当前状态仍只由权威计划维护。

2026-11-14 test-only后续：Q04当前cache图使用新独立只读kind与精确当前库存，不把历史B旧库存当当前、不打开Session/writer。B的11项是密文完整性库存，实际解密仅保护3及原journal；新增诊断只认证metadata/index及所引分片，旧随机名UNKNOWN保留。F73的同一生产footer/dialog/刷新方法增加EasyWork受审target/公钥/TLS/LAN offer准入，原Q02工厂已支持该Provider，不替换真实生产链路。两项仅测试源码已准备，编译/Android实机另记；既有失败、预算、功能状态/统计、iOS及A6暂停不变。详见[测试和真实门槛](../acceptance/mobile-parity.md)。

2026-11-14 M182实际：current-cache独立真机只读1/1通过，当前11密文保持，认证3个cache记录/12消息/新增分片；未知和已删旧逻辑名不关闭，非控制/普通恢复通过。F73新增实际getter来源kind与6输入联结，完整grant账户/公钥/TLS与4-field usage wire分别验证，真实partial保留；新runner/手机结果另验，当前不提升功能状态。

2026-11-14 M184：实际新鲜主preload来源经精确生产reader离线期望与真实KMP投影通过；Android×EasyWork唯一Native在真实配对后因debug验收入口误将哈希clientId比作hostId失败，额度页尚未通过。两处入口已按Provider真实ID规则修复，完整target断言保持，生产main/iOS与UI设计不变；新App/runner构建及复验另记。唯一新绑定已正常撤销并独立核persisted revoked，原失败/closed profile与key保留待具名清理，当前状态只见权威计划M184/Q12。

2026-11-14 M186–M187：Android×EasyWork当前真实DEV格已通过：App ce27/runner8d、中文竖屏字号1浅色，指定1方法12.569秒零失败；生产footer/详情/精确单余额/手动刷新/真实加载与新snapshot通过。Root独核3份实际投影与真实桌面来源/语义，实际查看3原PNG；真实partial/缺失指标不冒零。原M184入口失败保留，修复仅debug Provider clientId准入。唯一新绑定已正常一次撤销，旧墓碑保持；原资料/37-25模式/12bindings/5marker完整后置与六输入精确清理另据Root复核。此有限结果不关闭wire请求关联、普通安装/完整视口与双端Provider矩阵；iOS/A6状态不变，唯一当前状态仍见权威计划。

2026-11-14 M188：已审采用两个androidTest专用清理源，仅限本任务两次新F73资料与根alias；原Q04和其他UUID拒绝。完整库存与树外归档、原closed证明、普通LAN唯一绑定撤销、独立进程退出及descriptor只读预检先过；signed allowedMode=query不能被参数升级delete，未知只查原once。只删除选定root key，公共API缺失观察不冒硬件全局擦除。17辅助/SDK编译已过，正式runner及真实query/delete/原资料后置尚未验，iOS和产品UI无变化。

2026-11-14 F73清理检查点（M188后继）：测试源`c48c10c5`单独核应用files可信基础的实际0771，任务子目录0700/文件0600及descriptor约束保持；策略源`7c635c97`不变，旧`6ca86e74`历史记录保留。正式Gradle退出0/90.613秒；新runner `f3db00eb`已唯一实装，App `ce27a87e`、原15份profile元数据/106密文摘要保持。首轮只查记录`query-e6e3eb25`真实Native 1方法0失败/0.207秒：无删除intent、五文件/root alias仍在，DPoP及12个限定派生alias在公共API观察中缺失；该检查点的实际delete和保护后置尚未验。只更新F73测试资源回收证据及源码追踪，不新增生产行为、UI、iOS或整F通过；当前状态继续只由唯一对齐计划维护。

2026-11-14 F73首次有限删除：首份新profile另签`delete-c7999454`并真实Native 1方法0失败/0.365秒，intent存在、profile缺失、remaining为空，root/DPoP/12个限定派生alias在公共API观察中均缺失；不等于硬件或所有未具名key擦除。原十密文树外归档保持，第二份profile与完整其他资料保护后置仍待Root独核；只证明本份测试资源有限清理，不关闭整体回收或产品F73。

2026-11-14 M189–M190有限清理完成：第二份`query-2bdb3a5b`/`delete-e5208b67`真实各1方法0失败（0.216/0.413秒）；与首份结果合核，仅两份新profile/有限root alias清理通过，各14个限定alias在公共API观察中缺失，不推导硬件或全部key擦除。Root恢复前43引用、最终102引用独核确认其他13份profile/96密文/100文件4451245B及原Q04、37/25模式、12bindings/5markers、21份Native证据保持。24份staging输入/四空目录已精确回收，26member归档逐字节核原输入及两份65B once，原once stat/SHA保留。业务runner `8d5e420b`单次正常恢复并实读，App `ce27a87e`不变；首次P7三连接/保护false原件保留，后自然同组件/UID换PID归零，无force-stop或活动App writer/instrumentation。F73仅登记测试资源回收闭环，生产行为/UI、iOS/A6、统计及整F验收不升级，唯一当前状态仍由权威计划维护。

2026-11-14 关联更正：前四条F73清理sourceChange仅关联F73；F60属于Codex机器身份迁移，资源回收不扩展其验收。原`f43c70b4`提交、历史指纹与实际清理证据仍可读取，本次只纠正功能归属。

2026-11-14 M191–M192/F49/F50：已采用Q04阅读ledger六源v2（Root source review `79e29f5d`、综合补丁`8b3b26d4`）。显式文件名+payload permit在锁内按settled previous核delta，foreign rows/scopes保留，仅合并准确QA完整机器目标；pending read在HTTP前按完整target+thread过滤、即发前与finally均重核。普通存储返回原proposed scopes且predicate为true，iOS/UI/KMP wire不变。旧`97d`候选两P1保留且未采用，本次新增11组合辅助及三删门mutant，不重报旧63/3。正式Gradle 82任务含App/AndroidTest Kotlin实际编译，97.176秒退出0；Root逐摘要核1169输入/50份制品工具引用，新App `8c42aa85`/67143603B、runner `a2eca189`/9058076B均唯一fc12签名，两manifest不变且只有各自一份dex内容不同，真实App含scope/dispatch guard。候选未安装，设备Appce27/runner8d保持；当前真实ledger schema兼容及KeyStore/加密/AtomicFile运行、真机/真实HTTP/Host控制未验。F49/F50保持partial/unverified，原失败/marker/UNKNOWN及控制暂停不变，当前状态只见唯一计划。

2026-11-14 M195（F37/F38/F39/F43/F44/F49/F50/F61；S07/S10/S11；UI07/UI04/UI10）：用户已明确批准独立QA产品验收例外，仅原四动作、剩余1 Origin/0新child，开发聊天Host禁投递保持。Root采用统一八源（adoption248de1cf，base c592dad0）：独立post-second-known-zero kind、固定新子目录、sealed Ref/原始UTF-8证据与单QA ledger typed permit；历史manifest六名及旧三kind准入保持。当前11份记录只读诊断逐项经实际生产validator，claim只按生产模板派生一个准确名称；ledger仅内存运行真实save serializer、foreign canonical守卫和pending读取，不装配Session/网络/writer。生产Ledger save编码体无行为抽取已与旧生产save逐字节比较，14辅助通过；Native20辅助及控制器98/25辅助分别保留来源，均不替代新完整构建、安装、真实只读Native及剩余控制。原两次业务失败、旧marker及已删旧名UNKNOWN保持；iOS/产品UI/KMP wire未变，A6暂停。当前状态仅由权威计划决定。

2026-11-14 M198–M200真实结果（F37/F38/F39/F43/F44/F49/F50/F61；S07/S10/S11；UI07/UI04/UI10）：e221/fe6f+a374的全11生产语义与ledger内存兼容真实只读通过；随后业务Native真实失败，首条排队输入已接受并在原任务完成后自动续轮，第二条未发送。取消/执行/即时追加/停止均未通过，原断连异常仍未知，不能以只读兼容升级产品写入或整F。后置当前13/413516及其他12资料/原保护子集已独核，新增逻辑UNKNOWN和共同files时间差保留。余Origin0/新child0，不重放旧case；新正向验收需独立case及额度。产品UI/iOS/KMP wire共同断言保持，具体原始证据见[真实只读与业务失败边界](../acceptance/mobile-parity.md#q04-readonly-pass-and-business-failure-20261114)，当前状态只见唯一计划。

2026-11-14 M201（F03/F37/F38/F39/F43/F44/F49/F50/F61；S07/S10/S11；UI07/UI04/UI10）：Root采用独审通过的两源v2（adoption1779d3a5，base e221c1db，MobileSession78517327/testd224178c）。连接读取仅增加进程内stage/exception-class-chain/transient/新成功抑制来源，保留原fail-closed与恢复策略，不记录异常正文、URL或响应。测试在claim前有界等待原Turn/原操作集合/唯一enabled模式控件，原lease/40与45秒/原proof时效保持，只选择/发送一次；结束、替换、漂移或过期拒绝。失败诊断删除raw ax，完整机器目标及原QA重核后才投影/查询，错scope只身份；两个launch逐项收尾并安全suppressed，原Throwable身份保持，无primary仍抛首cleanup错误，live资源不强释放/不冒已关闭。26项提取方法JVM仿真和静态门通过，仅辅助；正式App+AndroidTest完整编译101.064秒退出0、82任务全部实际执行，Root核1172输入/28refs及新App442b0bc2/runner ced22164双fc12签名，manifest不变且仅App classes4/runner classes3和5改变；构建中间物已回收，尚未安装或新真实DEV。不会取消已接受队列，也不证明原FAILED根因已修；0余Origin/0child，不重放旧case。iOS/产品UI/KMP wire和功能状态不升级，见[诊断及就绪边界](../acceptance/mobile-parity.md#q04-ready-diagnostics-source-20261114)，当前状态只见唯一计划。

2026-11-14 F03/F61（UI01/UI10，S01/S11）Host 私有设备绑定管理草案：新增仅 Host admin 的具名 status/revoke/原 operation 查询候选；投影只含完整机器目标/keySHA/revoked/hostKeyAuthorized。额外字段、目标漂移、不唯一及同ID异输入拒绝；bindings/回执共同原子持久化，未知不重放，设备鉴权临时503保留凭据。iOS/Android App UI均未接入、不变更移动端KMP wire/普通执行权限、不升级整项；辅助与未验边界见[验收草案](../acceptance/mobile-parity.md#host-private-binding-admin-candidate-20261114)，当前状态只见唯一计划。

2026-11-15 Native-v2 采用准备（F03/F37–F39/F43–F44/F61；UI05/UI06/UI07/UI10；S01/S07/S10/S11）：Android fresh-only 独立资料沿原 Session/provider 配对与真实 UI，默认 null 的原始 reply gate 只在具名新 case 安装；真实 journal prepare 后核完整scope/原ID/intent/proof/lease/abort，最后 checker 与原 send 同IO上下文，retry和重复dispatch拒绝。单Origin依次queueCancel→取消并独立核销→queueExecute同Turn执行→steer→保留未发draft的长按Stop，最多4次文本提交，正向要求恰1新Turn/0自动续轮/0child且controller未开始cleanup；最多2Turn只是失败容差。pair前持久化公开intent、正常pair返回后公开proof，均不含凭据且须Host管理员status独立核唯一binding。有限QUERY/独立DELETE只覆盖已paired/closed/revoked且完整归档的准确新profile，pair前失败/Host not_found/空profile不覆盖并保留。此为代码采用准备，提取JVM/静态/HTTP辅助不是Android全编译或真实DEV；iOS与双端产品UI/共同断言不变，整项/统计不升级。 既有UI05正文及UI06工具/计划的完整阅读、折叠和视口矩阵仍各自待验，不能由该控制链升级。详见[Native采用边界](../acceptance/mobile-parity.md#fresh-q04-native-host-adoption-preparation-20261115)；当前状态仍只见唯一计划。

2026-11-15 M239（F37/F38/F39/F60/F61；S07/S10/S11；UI07/UI04/UI10）：已确认上一独立case在Native参数校验阶段失败，尚未启动Activity或配对；原Native once、冻结输入和单向后继边保留。Android debug permit与AndroidTest新增一个具名有限后继身份，原case分支保持；plan与authority必须同一run/lease/nonce，恢复证明必须指向对应前一case，同一模型预算、准确目标、30秒新鲜度及40/45秒工具界限不变。原Native工作目录三条件不放宽；生成端须在签发前校验正文、slot.toolWorkdir与计划目录一致。此为验收基础设施，不增加产品UI或任意恢复入口，iOS无代码影响；正式构建、安装、真实控制及资料保护按权威计划分别取证，不提升整F统计。

2026-11-17 F73 (UI02/UI03/UI12; S01/S11/S14/S18): the common product assertions and decimal/source rules are unchanged. Android adds a separately named joint-evidence test for real balances that may advance after admission, paired with a default-off task-only desktop response witness. Collection, same-snapshot source agreement, UI review and whole-feature acceptance are separate gates; the frozen-source test and its failures remain intact. This changes acceptance tooling, not product UI, iOS behavior or provider semantics. See the [joint evidence contract](../acceptance/mobile-parity.md#f73-joint-snapshot-evidence-20261117); all current status remains in the authoritative plan. F49/F50 reading-fixture changes likewise affect test cleanup only and do not establish real reading/badge acceptance.

### F38：迟到追加的具体原因（2026-11-19）

F38：已完成任务拒绝立即追加时，iOS 在当前目标显示明确原因，并在发送记录保留此原因；后续查询错误优先显示，清除后仍可读取原拒绝原因。新草稿与下一轮模型设置、原操作 ID 和回执保持。共享协议已经返回该原因，此次补齐 iOS 呈现，Android 须按原生实现另验，不新增通过。 当前进度与证据只在[历史验收记录](../plans/ios-easywork-alignment-20261112/README.md)维护。

### F31：上传中断提示与重开后保留原因（2026-11-20）

F31/F59：上传失败、暂停或等待时保留可读原因，重开后仍可查看。旧取消诊断及已知的“未找到传输记录”只转换展示文案，原诊断、目标、传输 ID 和回执保持；继续上传仍先查询原 ID，停止后保留消息，不据此认定远端取消或完成。iOS 补齐呈现，Android 仅同步共用词条，不新增原生通过。当前证据和进度只在[历史验收记录](../plans/ios-easywork-alignment-20261112/README.md)维护。

### F45/F46：首条消息的创建记录与显式恢复（2026-11-20）

iOS 对 EasyWork 首条消息先保存完整目标、原创建和消息 ID、正文、附件清单及显式生成偏好，再创建空聊天；未指定的偏好仍跟随电脑默认。结果未知时只查询原创建 ID。结束等待保留原记录和附件，回到可编辑草稿；查询确认后可显式恢复到原聊天，发送仍是独立动作。恢复不会覆盖目标聊天已有草稿，旧返回也不能推进新发送。附件沿用原准备、分块和完成操作标识，并在准备前查询原状态与回执。普通上传继续使用既有进度、续传和大小限制。Android 对应状态与操作身份须独立对齐，不新增通过；当前进度与证据只在[历史验收记录](../plans/ios-easywork-alignment-20261112/README.md)维护。

### F58/F42：非阻塞问题的完整列表与回答确认（2026-11-20）

iOS 将电脑返回的全部待处理异步问题交给现有分页面板；异步问题保留时，聊天继续显示实际运行或结束状态。阻塞问题仍显示等待回答。回答、跳过和拒绝始终绑定原聊天、Run、会话及工具调用；回答核对 accepted，跳过或拒绝核对 abandoned，再推进面板。原选项、自定义内容与收起重开的草稿沿用既有规则。桌面复用同一领域契约的既有问题服务；Android 对应实现和验收独立记录，状态不因本次移植改变。进度与原验收匹配只见[历史验收记录](../plans/ios-easywork-alignment-20261112/README.md)。

### F22：EasyWork 权威压缩状态（2026-11-20）

iOS 源码候选从桌面 canonical snapshot 的可选压缩状态显示进行中和终态，绑定原聊天、Run、分支及压缩操作；多个操作分别呈现，空 pending 水位不因保留 started 事件重新转圈。只有已安装检查点报告完成，失败、取消、跳过与未知分开显示。旧端未提供该字段时继续读取既有历史诊断，错误的可选元数据不影响普通聊天读取；不新增压缩执行接口。共享 KMP 文案补齐“已跳过”，Android 仅同步此词条及共享呈现规则，EasyWork 实时适配和原生验收仍独立记录。当前源码、辅助检查、真实模拟器及实际运行包分别见[历史验收记录](../plans/ios-easywork-alignment-20261112/README.md)。

### F36 请求速度与实际结果（新增反馈范围）

共同断言：生成速度设置表示请求，不承诺服务方实际采用。通用 runtime 可选 generationSpeed 包含 version=1、runId、requested、status 及已回报时的 actual；只关联当前目标/聊天/分支的准确 Run。缺失、未知或冲突回报显示未确认，旧 Host 未提供回报仍可读取。iOS 设置页显示最近一次回复的请求/实际结果；EasyWork 桌面任务信息使用同一语义。Android 需同步此可选字段与展示；本次不登记 Android 新通过。当前实现/验收/交付只读唯一计划 status.json 的 F36-generation-speed。

F64 的 EasyWork 原录音结果恢复使用共同 `AgentClient.recoverVoice`：只查询捕获的原 ID，不准备或上传录音。许可、转写文本回原草稿与修订屏障继续走公共流程；候选、辅助与实际验收分别关联唯一台账 VOICE01，Android/A6 状态保持。

公共录音控制器在已保存 Host 录音的失败状态下，将手动重试强制为原回执查询；未取得原结果时保留录音，不重新准备或上传。首次封存和明确本地转写保持；Android 对应恢复语义需同步，未新增实现或通过。

### 2026-11-21：F44 EasyWork 请求发送与回执等待

EasyWork 单次请求的 30 秒期限从发送前开始，以单调时钟覆盖发送排队、底层发送和回执等待。到期只关闭原连接；已经认证的原回复即使早于发送回调到达，也继续采用原结果。原操作 ID、草稿和附件保持，写入结果未知时沿既有原回执查询，不重新提交。连接与通道选择仍有独立预算，不将整个恢复流程描述为固定 30 秒。

这是 iOS 适配层修复候选；Codex 路径与 Android 未改，本轮不增加其验收。实际源码、运行包、原生模拟器及真实桌面结果以唯一 EasyWork 计划为准。通知只读观察仅限 DEBUG 独立验收 bundle 与准确完整目标，不注入事件、令牌或假界面状态。

F31 取消上传仍走共同进度/持久意图/终态恢复：EasyWork Adapter 接入其既有严格取消命令，核完整目标及原传输回执；未知结果查询原 ID，不重复上传，已完成文件保留完成结果。旧端能力不足不误报电脑已取消，原消息和附件保留。iOS 源码候选、受控辅助检查和真实模拟器/桌面结果分别记录；Android 当前未改，A6 暂停。

2026-11-21 F29/F39（UI05）：iOS 原生按钮的辅助功能禁用态随实际 UIKit 状态更新，保留原发送/权限门禁和选中态；Android 不使用该 UIKit 适配，无实现或验收升级。独立原生回归与真实聊天结果分别见唯一对接台账。

### F59：EasyWork 独立文件的容量与恢复

独立文件上传复用公共 UploadCenter 的 URL 暂存、分块读取、取消意图和原回执恢复。EasyWork Adapter 将具名 resource 方法映射到共同生命周期；认证 runtime/context 的可选 resources:transfer-limits:v1 能力提供 version=1、上传/下载/独立传输容量和分块上限，null 表示电脑未配置该容量上限。旧端未声明能力时继续采用 20 MiB；聊天附件原额度保持。容量不改变完整目标、设备、清单、偏移和 SHA-256 校验。

恢复先查询原传输与原写回执，再对新准备应用当前额度，降低额度不把既有操作变成一次新上传。独立传输不发送聊天或启动模型。Android 本次未改，A6 保持暂停；公共语义需对应同步，源码候选、辅助检查、真实模拟器/桌面及合并分别见[唯一计划](../plans/ios-easywork-alignment-20261112/README.md)。

F47 桌面独有候选修复：启动时同 scope 的迟到快照不得覆盖非空草稿，保留模型、推理、权限、plan 和 budget；空草稿默认值与用户主动选择保持。iOS／Android 草稿路径未改；桌面真实冷启动验收待唯一台账。

F59 哈希内存修复：iOS/macOS 共用的 UploadCenter 对每个 256 KiB 读取、摘要更新与计数使用自动释放池；wire、摘要、操作 ID、偏移及旧续传语义不变。Android 逻辑未改；macOS 辅助内存检查不代表 iOS 真实内存验收。

### F44/F18 具名模拟器在途恢复验收

限定原生验收可复用正常 App 唯一的状态持有者，读取和保存同一聊天的原不可变操作、草稿及恢复记录，不复制加密状态或创建第二个写入者。该入口需要独立验收 bundle、模拟器、XCTest、恢复模式和准确匹配的用例声明；普通启动、实体、Release 与隔离 R2 的存储及权限边界保持。真实在途故障和原操作恢复需另行验收，当前结论只回填唯一完整功能清单，原始记录保留在iOS专项档案；Android 产品流程未变。
