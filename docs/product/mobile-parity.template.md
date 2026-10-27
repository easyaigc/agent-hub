# iOS / Android 功能与一致性清单

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
