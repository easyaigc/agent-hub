# Android 分阶段过程档案

当前总计划、优先级与完成状态只维护在[计划与进度总表](../progress.md)。本目录保存过程和历史依据，不用旧记录的“下一步”“当前分支”替代最新计划。

- [Source67 默认密钥存在性观察](source67-default-alias-observation.md)：独立 Test 入口、842/855 输入与三 APK；设备及产品结果待验。
- [PR70 整批超时计时观察](ci-batch-timeout-observation.md)：保留原失败断言，增加真实 Worker 计时证据；Linux 根因待核对。
- [原录音只读读回](original-voice-readback-20261030.md)：原包单次 Native、91 调用与完整原件/WAV核对，修正离线 audit 比较；UI和清理另验。

## 按主题查阅

| 档案                                                                | 内容边界                                        |
| ------------------------------------------------------------------- | ----------------------------------------------- |
| [2026-10-22 冻结计划与验收方法](baseline-plan.md)                   | 冻结历史，不作为当前状态                        |
| [历史调度与主线交付](dispatch-and-integration.md)                   | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P0 环境、隔离包与执行现场](p0-environment.md)                      | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P1 目标、创建、分支与恢复](p1-target-recovery.md)                  | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P2 连接、缓存、读取与弱网](p2-connection-reading.md)               | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P3 导航、查找与聊天管理](p3-navigation-management.md)              | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P3 原生手势、输入法与侧栏](p3-gestures.md)                         | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P3 实际代码与 Diff 选择复制](p3-selection-copy.md)                 | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 本地恢复、输入、队列基础与生成设置](p4-input-settings.md)       | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 原 owner 权限与附件基础](p4-permissions.md)                     | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 计划、工具审批与异步问题](p4-plans-questions.md)                | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 原客户端队列、停止、继续与迟到控制](p4-queue-controls.md)       | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 首条创建、未知结果与原意图恢复](p4-creation-recovery.md)        | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 配对 TLS 与只读恢复](p4-tls-reading.md)                         | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 超长历史、修订回执与队列故障恢复](p4-history-queue-recovery.md) | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 系统文件、冷回复与迟到回调](p4-files-late-callbacks.md)         | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 原问题恢复、请求替换与 owner 冲突](p4-owner-conflicts.md)       | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 冷 Host 与写入后未知回执](p4-cold-receipts.md)                  | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 审批标题、选区、锚点与键盘可视区](p4-approval-viewport.md)      | 原进度及同期补充，保留原失败/限定通过/指纹      |
| [P4 主线增量与当前候选复验](p4-main-increments.md)                  | 10月25日新增7提交/F71、差异合并与当前P4限定结果 |

P5: [desktop suggestions](p5-desktop-suggestions.md), [source review](../P5-source-review.json).

P6: [展示与设置](p6-presentation-settings.md), [逐项范围](../P6-source-review.json)。

## 如何新增阶段记录

1. 在对应主题档案追加一条检查点；记录范围、对照 iOS 的功能/交互/界面、实现与环境指纹、辅助/真实结果、失败与未验、证据索引。
2. 证据详情放在 `docs/acceptance/mobile-evidence/` 的对应阶段文件；原始日志、截图、附件、真实聊天和凭据保持私有，不放进计划或仓库。
3. 更新总表对应状态/剩余项及交付状态；产品/设计规格只描述当前断言和差异，用检查点链接引用过程，避免复制整段流水。
4. 先提交并推送安卓专用分支、独立核对远端，再开始下一阶段。暂缓项和未知原操作继续单列。

## 本次归档核对

2026-10-25 将原计划 663 行、原进度 1239 行，共 1902 行分为 39 个连续原文区块，按上述主题保存；只调整相对链接和 Markdown 排版，不删原失败或改变通过范围。每个区块保留来源行号与稳定锚点，原计划与原进度中重复的同期摘要仍按来源保留，未冒充额外验收。

[迁移索引](migration-20261025.json)记录原文件指纹、全部来源区间、迁移目标和区块指纹；后续追加不会修改该冻结索引。更早历史继续见[原生 Android 阶段记录](../../../acceptance/android-stages.md)、[旧 A0–A8 技术规划](../../android-native.md)、[逐提交台账](../commits.md)及主线交付记录 [第一次](../merge-20261024.md) / [第二次](../merge-20261024-second.md)。

- [P6 链接与资源](p6-links-resources.md)：受控打开、当前版本/缓存、真实资源与系统预览的实现和限定结果。

- [P6 读取与恢复文案](p6-reading-copy.md)：只读/早页/恢复状态、原数据与本次/辅助证据边界。

- [P6 原记录别名](p6-history-aliases.md)：准确原正文/资源身份、保留边界与实际原生只读核对。

- [P6 执行面板文案](p6-execution-copy.md)：队列/回执/原意图/问题分页的双语与原数据保护。

- [P6 工具展示](p6-tool-presentation.md)：双端共享中英格式、原Provider正文及本次/辅助结果。

- [P6 消息操作触达](p6-action-hit-regions.md)：浮动入口、代码/工具复制、资源分享及原回复只读证据。

- [P6 固定文案与读屏](p6-caption-accessibility.md)：发送/展开、计划/分支状态、聊天归档与连接文案。

- [P7 外部附件对照基线](p7-external-intake-baseline.md)：N048/N049源码继承、工程合并与当前回归边界。

- [P7 显式文件粘贴](p7-explicit-file-paste.md)：原系统剪贴板、真实Provider、本机草稿与有界验收。

- [P7 外部文件收件箱](p7-external-inbox.md)：独立来源、系统接收、准确草稿和持久确认/防重恢复。

- [P7 进程冷恢复](p7-process-cold.md)：独立系统冷唤起、真实PID与部分导入/消费后的原草稿恢复。

- [P7 图片选择与本地准备](p7-photo-picker.md)：原生选图打开/取消、EXIF及准确原请求，正向选图与相机/最近图后续。

P7-2b：API37受控系统正向选图、49原生辅助/默认存储证明与真机/API26待验见[图片档案](p7-photo-picker.md#p7-2b-独立来源与系统正向回调)。

P7-2c：实际系统图片模型读取、冷Host重复输入修复、三环境原回复/空闲态与13默认存储证明见[图片模型档案](p7-photo-model.md)。

P7-3a：拍照本地准备/原请求、API37系统拍摄与取消、108辅助和3原回复只读、113默认存储证明及真机/API26待验见[拍照档案](p7-camera.md)。

- [P7-3b 附件面板、最近30图与原文件回调](p7-attachment-panel.md)：138辅助、7系统、3原回复只读；148默认存储证明，权限/最近新增提示及冷结果继续。

- [I3 问题语音主线3节点](p7-main-integration-i3.md)：完整根检查/模拟器编译，安卓398输入匹配复用；语音原生剩余范围不变。

- [P7-3c 最近新增提示、预览与加密忽略记录](p7-recent-hint.md)：179辅助、9平台、3原回复只读，191默认/偏好证明；原URI冷结果与首次权限仍开。

- [P7-3d 首次照片授权与拒绝后恢复](p7-photo-authorization.md)：156辅助、2系统、3原回复只读，161默认/偏好/授权证明；完整权限和原URI/相机输出冷恢复接续。

- [P7-3e 原照片授权记录进程冷读](p7-photo-authorization.md#p7-3e-原授权记录进程冷读)：2项新增/2默认证明，407既有输入与3d的161项匹配；原URI/相机结果仍开。

- [P7-4a 原文件请求、稳定回调与本地结果恢复](p7-document-recovery.md)：204辅助、6系统取消、3原回复只读及213默认证明；4b实际进程死亡返回和原URI接续。

- [P7-4c 照片原请求/结果与父草稿恢复](p7-photo-recovery.md)：共同协调器、双源界面与239个有效方法断言；实际照片进程死亡、手机长批稳定性及其余矩阵继续。

- [P7-4d 原照片系统冷回调](p7-photo-recovery.md#p7-4d-原照片系统返回与冷开)：API37同任务保存状态后进程死亡、原像素/草稿与冷开无重复通过；无保存状态及其他平台单列。

- [P7-4e 当前共同协调器文档冷回归](p7-document-recovery.md#p7-4e-当前共同协调器系统冷回归)：两SDK本次原结果与冷开同ID/摘要通过；真机及其余系统矩阵单列。

- [P7-4f 相机原请求与私有结果恢复](p7-camera-recovery.md)：权限/结果加密原记录、私有输出认领、结束等待与迟到保留；三环境分组/短视口及有界系统相机，实际进程冷返回接续。

- [P7-4g 原系统相机冷结果](p7-camera-recovery.md#p7-4g-原系统相机进程死亡与冷开)：API37原回执/同ID冷开有界对账，保留AX及时性失败，API26首次引导调用链待验。

- [P7-4h 当前共同协调器文件/照片冷回归](p7-document-recovery.md#p7-4h-当前文件照片共同协调器冷回归)：3组系统冷结果/冷开及6只读有界通过，保留准备失败和照片OS保存状态门槛；继续存储/语音/阅读。

- [P7-5a 父资料恢复页与原附件](p7-local-recovery.md)：三环境各61项、损坏父密文保留/格式失败暂存、明确重试及有界系统/原回复结果；迁移与完整异常矩阵继续。

- [P7-5b 加密迁移封装](p7-migration.md)：三环境18项/18份原文件字节保护及15组双向合成向量通过；本端资料导入、原子回滚与UI继续。

- [P7-5c 迁移候选与原ID冷读](p7-migration.md#p7-5c-迁移候选资料与原id冷读)：三环境72项/3组真实进程冷读与45份原文件保护；旧手机合批超时保留，正式导入/回滚/UI继续。

- [I4 主线图片/阅读/当前参数增量](integration-main-preview.md)：13节点与F72登记、19项最终包/168项限定匹配复用、187份默认资料保护；受控阅读入口和新图片选择UI接续。

I4-2：[Markdown受控阅读](p6-markdown-reader.md)，原生与失败/待验依据见[审查数据](../I4-2-markdown-reader-review.json)。

- [I4-3a 本地大图与草稿预览](p7-image-selection.md)：73辅助、1独立媒体整例和74份默认资料保护；有序多选/统一添加接续。

- [I4-3b 有序选择与统一添加](p7-image-selection.md#i4-3b-有序暂选与统一添加)：62当前辅助/22存储匹配复用、独立媒体1和原回复只读2；手机最终包占用待验，全库/配额/冷恢复接续。

- [I4-3c 可访问图片分页目录](p7-image-selection.md#i4-3c-可访问图片分页目录)：57原生辅助/1真实元数据与52默认保护，三环境目录/继承及实际短视口触摸通过；系统入口/原请求配额和冷恢复接续。

- [I4-3d1 可读态系统选图入口](p7-image-selection.md#i4-3d1-可读相册的系统选图独立入口)：16原生辅助/16默认保护；实际系统Provider与原请求配额接续。

- [P7-5d 原子代次发布](p7-migration.md#p7-5d-原子代次发布与恢复隔离)：API26/真机42辅助、30默认保护及两组不同进程冷读；完整迁移入口与语义继续。

- [P7-5d2 发布前进程中断](p7-migration.md#p7-5d2-发布前受控进程中断)：API26/真机两条受控中断、新PID原资料只读与精确暂存清理，4默认保护；完整导入语义/UI继续。

- [P7-5e 归档元数据校验](p7-migration.md#p7-5e-归档元数据的严格类型边界)：先复现坏草稿替换原候选，再严格类型/字段/数量；当前28辅助（含精确复用）与旧十文件保持，完整导入语义/UI继续。

- [P7-5f 引用与配对凭据](p7-migration.md#p7-5f-附件原输入引用与已验证凭据)：24辅助/默认资料保护，原附件顺序与快照闭合，凭据完整匹配；完整迁移仍继续。

- [P7-5g 内部激活与权限重置](p7-migration.md#p7-5g-新安装激活与迁入授权重置)：56辅助、两组实际冷进程和16默认保护；产品UI/完整清单继续。

- [P7-5h 导出完整性快照](p7-migration.md#p7-5h-导出前完整有效记录快照)：36辅助/默认资料保护，拒绝缺项/未知/损坏和未支持二进制；自动清单及完整导出继续。

- [P7-5i 加密逻辑名与旧格式](p7-migration.md#p7-5i-本机加密逻辑名与旧格式兼容)：86辅助、66默认资料保护及两组不同进程冷读；完整迁移继续。

- [P7-5j 自动文本导出与历史分片](p7-migration.md#p7-5j-自动文本导出与历史分片闭环)：52辅助、36默认资料保护；准确旧依赖与闭合历史图，完整全类型与系统UI继续。

- [P7-5k 缩略图与旧许可](p7-migration.md#p7-5k-缩略图与共享许可迁移)：严格预览/源附件分离、迁入许可清空与原数据保留；旧EXIF脚本失败和最终原生/UI结果分别登记。

- [P7-5l 录音二进制](p7-migration.md#p7-5l-录音二进制身份与原件保护)：新旧格式、跨次分块绑定、原件与默认存储保护；[完整存储清单](../P7-migration-storage-inventory.json)区分实际路径、可迁资料与本机授权。

- [P7-5m 原录音真实进程冷读](p7-migration.md#p7-5m-原录音真实进程冷读)：writer确已退出、新PID/同Keystore原密文读取与5l产品APK匹配复用分别记证。

- [P7-5n 封口录音档案](p7-migration.md#p7-5n-封口录音类型化迁移)：类型化原文/二进制、闭合索引/清单、原子激活、迁入只查原回执及两环境回归；满额/未封口/全类型与UI仍开放。

- [P7-5o 候选/激活冷恢复](p7-migration.md#p7-5o-录音候选与激活跨进程恢复)：两条链路、真实writer退出/新PID、未知回执/草稿与源密文、匹配复用分开登记。

- [P7-5p 迁入语音原生UI](p7-migration.md#p7-5p-迁入语音状态与双语原生交互)：App文案来源、外部错误原文、8中英/内容主题场景与忙碌/取消/原数据保护；系统栏与其他观察分列。

- [P7-5q 主线速度同步](p7-migration.md#p7-5q-主线速度契约同步与原owner参数补验)：N066、三方manifest、旧夹具失败与两环境10项补验分别记录。

- [P7-5r 候选分块与容量预检](p7-migration.md#p7-5r-候选加密分块与容量预检)：本机加密块/认证头、旧候选兼容、48MiB堆OOM与满额预检明确区分。

- [P7-5s 多块原候选冷恢复](p7-migration.md#p7-5s-多块候选真实进程冷恢复与膨胀预检)：4条真实PID边界、3块断言、精确膨胀拒绝与新增上游节点逐项分列。

- [并行统筹交付](parallel-delivery.md)：四线隔离/共同接口、N067/N068合并及当前组合原生恢复态证明；未验项继续单列。

- [Source50 原UI7](../parallel/fiftieth-stage-owned-ax-native.json)与[四态菜单像素](../parallel/fiftieth-stage-p8-menu-pixels-native.json)：限定原生结果、保留失败与独立图审；过程见[并行交付档案](parallel-delivery.md)。

- [Source53：迁移 owner 与语音组合验收](source53-provisional-voice-controls.md)：源码/构建、双SDK限定通过、已知失败与未知分列；当前 owner、UI 和完整 P7 待验。

- [Source54：流式夹具安装目录](source54-stream-fixture-context.md)：真实已知失败、canonical Context 修正和新候选验收分别登记。

- [Source55/56：收件箱与语音恢复](source55-inbox-voice-recovery.md)：实际owner/严格子存储、惰性原件与恢复门槛；组合SDK与Native分别登记。

- [Source55：真实收件箱存储首组](source55-inbox-storage.md)：四项限定通过、硬链接夹具EACCES失败与275份原始调用核对；跨PID/owner/UI继续。

- [Source55：原候选冷恢复与实际owner](source55-inbox-owner-cold.md)：三PID原候选闭环、实际Main三项通过、Intent生命周期夹具失败和严格kernel见证分列。

- [Source55：语音恢复与丢弃回执](source55-voice-recovery.md)：三项Native限定通过、159份原调用；中英/主题/启禁及原WAV证据/准确fixture留存分别登记。

- [Source60：owner Intent 生命周期与五项补验](source60-inbox-owner-fixture.md)：夹具窄修、新包保护两棵原件及5项实际owner限定通过；历史失败与完整界面待验分开。

- [Source62：系统原件与通知恢复组合](source62-system-originals-composition.md)：P7实际选择/照片actor、P9只读inspection、P8独立原点和固定WAV工具；当前组合SDK通过，Native待验。

- [Source62：首组17项Native契约](source62-p7-memory-native.md)：内存原件图/照片审计/ACK解析全部通过，799份实际调用；文件/owner/原声与完整UI分开待验。

- [Source62：回执存储与当前根清单](source62-p7-filesystem-native.md)：4项完整通过，1项原生主体过、后置保护/文件采集待验；301份调用，原操作不重跑。

- [Source62：owner选择回调准入](source62-p7-admission-native.md)：五项实际Main/registry限定通过；原指针以0重跑的独立只读补证，缓存边界分别验证。

- [Source62：选择/照片固定reader](source62-p7-readers-native.md)：六项限定通过；402份调用，全run与源/目标密文复制比较，原链接/相机瞬态及完整系统结果分别留界。

- [Source62：选择/照片实际owner](source62-p7-selection-owner-native.md)：五通过/两已知失败，779份实际调用；相机原件窗口和完整IO退出分别留待验证。

- [Source63：阅读归属与验收入口修正](source63-reading-owner-and-admission.md)：旧写入隔离、实际 Store 夹具、owner 原因诊断与 P8 低 API 类型隔离已接入，SDK/三APK及新DEX审计通过，Native/完整交互待执行；旧Ledger失败收尾缺口单列。

- [Source64：发现、连接与阅读归属](source64-discovery-reading-ownership.md)：同一 Session IO/NSD 真实退出、九处共享接线和阅读夹具收尾修正；Source64r2 SDK/三 APK 通过，Native 待验。

- [Source64r2：API33 阅读与 IO 归属](source64-api33-owner-native.md)：11 方法限定通过，715 份实际调用及原 B 严格保护；外层工具错误、完整网络/UI/DEV 待验分别保留。

- [Source65 组合源码与 SDK](source65-composed-source-sdk.md)：惰性连接意图迁移、准确夹具退出、API26 菜单诊断和 P9 方法分组；原生/系统/DEV 继续待验。

- [Source66 迁移恢复与读取标志](source66-migration-client-restore.md)：Source65实际失败、原件保持、迁移专用恢复/单调Boolean兼容和新候选真实验收。
