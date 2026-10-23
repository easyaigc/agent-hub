# Android 分步实施与验收进度

执行授权：2026-10-22，按优先级分阶段开发、验收，每阶段先登记结果并提交到 Android 专用分支，再进入下一阶段。

专用分支：`codex/android-alignment-20261022`。开发基线 `6d81284e9c352e977140c09197526296d57b9669`；主线未新增待审计提交。旧 `codex/android-native` 及其 73 项未提交/未跟踪文件保留，已做私有只读盘点，不整目录采用。

Task Hub 业务任务/项目及通知、新增项目记录管理暂缓；A6/P7 保持暂停，P9 等待其依赖。没有跨电脑分派授权，本机可执行的门槛先完成，其他环境明确列为未验收。

前置检查点 `7041ec7`、P3 来源准备 `f132d91` 已推送并独立核对。2026-10-23 用户明确要求蜂窝/VPN单列待验收并继续 P3/P4 本机开发，P3-1 元数据检查点已提交，P3-2 侧栏已提交，P3-3 完整查找已提交，P3-4 聊天管理已实施并完成本机辅助检查；完整P2未关闭，API26首缓存画面的顶部黑区/底部裁切继续保留，不以本机后续开发替代这些验收。

| 阶段                      | 优先级/依赖                   | 当前状态                | 本次退出门槛/下一步                                                                     |
| ------------------------- | ----------------------------- | ----------------------- | --------------------------------------------------------------------------------------- |
| P0 冻结与执行现场         | 最高                          | 已完成                  | 最终候选 API26 5/5、API37 5/5、真机 1/1；构建/隔离/规范/审计门槛关闭，阶段提交后进入 P1 |
| P1 目标、能力与恢复契约   | P0 后                         | 检查点已提交/部分待验收 | 机器身份/窄能力/原意图/创建打开/分支/原回执观察；辅助和真实模型/原桌面证明分别记录      |
| P2 连接、读取与弱网       | P1 检查点后/用户允许续行      | 进行中                  | 地址候选/预算、缓存/独立历史/窗口/同聊天重选/错误隔离与真实弱网                         |
| P3 聊天导航与管理         | P1/P2 本机检查点/用户允许续行 | 进行中/P3-4             | 26 来源、9 功能保留；先补元数据与分页持久，再实施侧栏/搜索/聊天生命周期；独立未读仍暂停 |
| P4 输入、队列与控制       | P1/P2 后                      | 待开发                  | 客户端唯一队列、原回执、模式设置、计划/审批/问题及准确一次输入                          |
| P5 桌面建议               | P2/P4 后                      | 待开发                  | 来源/版本/租期、采用/追加/替换/撤销，零模型输入                                         |
| P6 展示、语言与视觉       | P2/P3/P4 后                   | 待开发                  | 语言/文案/正文与链接/全部适用页面状态                                                   |
| P7 系统、语音、存储与阅读 | 明确恢复后                    | 人类暂停                | 等待 A6 恢复指令，保留现有代码和失败记录                                                |
| P8 EasyWork A5 封存       | P1/P2/P4 及适用 P3/P6 后      | 待开发/验收             | 原 A5-1–A5-7 七组、双通道与真实授权分别关闭                                             |
| P9 普通聊天通知           | P7 与供应商后                 | 依赖未就绪              | 真实权限/锁屏/声音/送达；业务通知暂缓                                                   |
| P10 全量回归与交付        | 适用各阶段后                  | 待验收                  | 全矩阵、包/源码/设备/Host 指纹一致；暂停项不得记完成                                    |

## P0 完成记录

- 新工作树从冻结主线建立，独立 pnpm 安装、原生输出及验收目录；没有覆盖主检出或旧工作树。Gradle 只复用既有下载缓存，构建输出和工程状态独立。
- Android 增加显式 `agentHubAcceptance=true` 构建选项，独立 application ID 为 `com.easyai.agenthub.android.acceptance`；默认 DEV/正式身份保持原值。APK 与测试 APK 已成功构建，实际签名和包名已读回。
- 修正 F04 的 Codex 机器身份断言、S14 的 EWDAP/1 免手机登录、S17 双语音路线和 S18 全页面范围；X01–X09 登记为既有 F 的设计子项。未把规范修正记为产品验收通过。
- 新手机第一次独立产品安装返回 `INSTALL_FAILED_USER_RESTRICTED`，原失败保留；用户再次允许后，产品和测试 runner 安装成功，包名、数据目录及实际原生流程核对通过，最终 `MobilePhysicalSmokeTest#foregroundNativeControls` 为 1/1。没有真实模型输入。
- 真机初次原生用例找不到恢复消息：验收器现在先核对恢复只增加一条原文，再实际滚动并确认可见；断言更严格。后续截图发现输入法账号提示遮挡，补上 App 自有收起键盘按钮操作后重验；最终恢复正文原图已查看。原失败与遮挡原图单列保留，不作为业务登录或产品通过证明。
- 最终验收器候选在独立 API26 的 `MobileFoundationTest` 5/5 通过；默认 DEV 包另行构建并读回包名不变。API26 早期图中的启动过渡/键盘引导和新手机自定义字体/输入法属于 P6 后续完整视觉矩阵，P0 不据此宣称视觉一致。
- 新建独立 API37 曾冷启动挂起并以 signal 11 退出；设备/内存、短路径、正常 AVD 清单、只读实例、独立公共镜像等原失败保留。按 [Android 官方发布说明](https://developer.android.google.cn/studio/releases/emulator?hl=en)，API37 手机 AVD 至少配置 4GB，本次配置为 4096 MiB。
- 用户明确允许关闭旧 `AgentHub_API37_Arm64` 后，已执行正常关闭并核磁盘资料仍在、配置摘要不变。随后独立 API37 成功冷启动、安装最终候选并完成 `MobileFoundationTest` **5/5**；运行的是本阶段独立包和独立 AVD。该结果保留并行实例影响线索，不宣称已隔离出模拟器内部根因。
- 一致性检查通过；首次缺少 protocol dist 的失败在独立工作空间构建后重验通过。完整仓库检查的 **59 个命令按原顺序分段通过**：首次新 Gradle 缓存下载等待被本任务取消，复用已有同版本缓存/编译器后，从 `check:agent-contract` 续跑至末尾全部返回 0；原取消记录保留，非一次无中断通过。Windows 原生专用项在 macOS 的跳过不作为 Windows 通过。最终原生验收器变化另由真机/API26 与一致性检查覆盖。
- [P0 脱敏证据](../../acceptance/mobile-evidence/android-alignment-P0.json)登记最终包指纹、API26 5 项/API37 5 项/真机 1 项和全部原失败。**P0 适用门槛已关闭**；这些是工程/原生合成基线，不增加真实模型、双端全功能或 A6 通过项。
- 初始检查点 `8a3fa9f3ce78fa6e82625653f16e83fba0c0b866` 已正常推送并独立读回远端一致。P0 关闭记录另作阶段提交；完成远端核对后才开始 P1，精确关闭 SHA 在 P1 开始记录回填。

## P1 当前记录

- P0 关闭提交 `e1ecef6bf9f796ad07eb60986c3b32664fe60437` 已正常推送并核对远端一致，随后开始本阶段；main 仍为冻结基线。
- 逐提交采用旧 WIP 中原打开回执观察的有效差异，重新实现按完整目标/原操作隔离的并发观察；已确认回执直接复用持久记录。等待中的观察者退出后释放门闩，避免按历史操作无限积累；不同操作不互相阻塞。
- 新增真实 HTTP 并发、终态持久复用、独立操作、取消等待、错误回执、目标变化反例，API37 首轮 **6/6**。首次测试编译的 TargetScope 构造错误、首次执行使用错误产物路径及旧 runner 类缺失均保留，修正验收器后重验；尚未据此记本阶段完成。
- [P1 逐项范围](P1-scope.json)保留 **19 个功能、32 个来源提交节点**，继续按 F/UI/S 核对，不把一个恢复修复当作整个阶段完成。新增未知 Provider 的读取/写入零透传反例；安装器在安装前核对 APK 内嵌身份，只接受明确 DEV/acceptance 包。
- API26、API37 和新手机分别完成 **122 项原生辅助检查，共 366 项**。每台由两组组成：89 项机器身份/窄能力/恢复/分支/私有连接，33 项输入执行/状态/Provider。产品 APK 字节一致；89 项的所选验收类、公共工具、生产与资产输入与该 P1 检查点源码精确匹配，标为匹配复用；33 项来自第二 runner。没有把这些分组写成单次无中断 122 项，也不增加真实模型通过数。
- 原生前景验收现在以实际目标包名启动独立 Activity；新手机的 shell 唤醒返回 INJECT_EVENTS 拒绝，改为核对实际 Awake 状态并通过原生运行器执行，没有要求扩大手机权限。原生夹具缺少 TLS 参数、合成用例缺少当前共享许可前置条件、ActivityScenario 等待及原失败均保留。
- `pnpm check` 的 59 个命令本阶段一次连续通过。之后仅 Android 验收器变化，已重新编译并由相应原生组覆盖；当前文档/验收器另补一致性及格式检查。iOS、Windows 与其他电脑本次不据此新增通过。
- 新手机通过真实 Electron 的**项目内新建/首条恢复/回复权威重试**，原生 1 项通过，2 次 GPT-6.1-Sol/xhigh 模型任务均由准确原 owner 独立核对为一次输入。先只读工具、后仅写专用验收文件的实际权限策略分别核对；明确共享许可本身零提交，原操作 ID/正文/权限不变、新草稿保留、结果未知不自动重放。用户手动添加专用目录后，已只读确认真实桌面目录和项目 ID 完全匹配；不属于暂缓的 Task Hub 或新增项目记录管理开发。
- 无项目真实用例另有原生 1 项通过、2 次模型任务独立核对通过，但旧代理读接口白名单发生未分类拒绝，**整体保留失败**。已按当前客户端的具名 `open-status`/`fork-options` 只读接口适配后，项目内用例整体通过；没有重放无项目已完成输入，也不追改原报告。完整无项目门槛仍待原回执只读对账。
- 恢复检查点 `41e5369d92de786dab7991c2730a3b8ebe4eab7d` 已正常推送，独立远端 SHA 一致；P1 仍继续验收，不代表整阶段关闭。
- 新手机**同目录/工作树分支**原生 1/1 通过：专用源聊天两轮、两个分支各一次新输入，共 4 次实际 GPT-6.1-Sol/xhigh；准确所选轮次继承、后续轮次排除、原源码/HEAD/索引与文件字节不变。响应丢失后结束等待、自有 Host 重启、仅查原回执并明确打开通过，新草稿保留。汇总验收器读取错误的模型字段而失败，原报告保留；独立只读对账改核真实 `turn.params`，两条分支和全部原 owner 断言通过，**零新增输入**。没有重跑已完成模型来修补证据。
- 分支产生的临时工作树已保存并核验恢复 bundle、文件和补丁；只停止准确归属这两次完成任务的 8 个空闲运行器子进程，原桌面和 app-server 保留。以普通 `git worktree remove` 实际移除并关闭登记；源验收项目的分支/HEAD/索引保留。
- 分支记录提交 `d1fc697110deb3f9f02d57eeed8d48cff0ad11c3` 已正常推送且远端核对一致。新增**无项目首条混合附件**：新手机原生 1/1、实际 GPT-6.1-Sol/xhigh 1 次输入通过；TXT/PNG 随机码只在附件中，真实模型精确返回，Host 字节数/SHA 与原稿核对一致，实际 owner 是 projectless，原回执 projectId 为 null。使用原生草稿附件生产入口，系统文件选择器仍在 P6，不据此记通过。
- 未加载 owner 的用例准备遇到明确边界：当前产品元数据连接拒绝管理桌面已加载聊天，返回“请在桌面端操作。对话未改变”；该失败为零模型输入。桌面 CUA 工具策略也禁止操作此应用，已请求用户仅归档准确的专用同目录验收聊天；没有换 IPC/改数据库绕过。用户随后明确要求将这项标记待验收并继续后续；本项不再作为进入 P2 的阻塞，不再请求归档，也不尝试绕过 owner 边界。将来明确补验时仍先只读确认卸载并单独运行，不重跑已完成无项目任务。
- 新增原生验收器首次将 LocalDraftFile 当作清单字段访问而编译失败，已改为 manifest 字段并成功构建；独立测试分组开关随后编译通过，原失败与已通过候选源码保留。
- [P1 脱敏证据](../../acceptance/mobile-evidence/android-alignment-P1.json)记录所选组/产物/实现指纹、原失败和未验收门槛。未加载原 owner 主动打开、附件不可变恢复及剩余适用矩阵仍在进行；P1 不记整体通过；按用户最新指示保留该验收缺口并继续 P2。

## P2 当前记录

- 用户明确要求将未加载 owner 场景标记待验收、继续后续。P1 最新代码/证据检查点为 `5ba22bb9cebc2b62ac116c8f8850cdecccf90836`，本地 Android 分支已提交；远端此前独立核对到 `d1fc697`，一次推送被远端鉴权拒绝，原失败保留；正常重试后已独立核对 `5ba22bb` 远端一致。用户续行登记 `bf17260e2f144be6de89c3b258b00779dc9b01dc` 也已推送且核对一致。
- 本阶段保留全部 19 个功能与 35 个来源提交节点，见 [P2 范围](P2-scope.json)。先对照 O159 同聊天重选和 O143 首屏/在途/错误隔离优化，现 Android 重选当前聊天会取消在途历史并重置阅读窗口，已先在 API37 运行原生反例：3 项中 2 项失败，重选产生 3 次历史请求、120 条展开视图回退到末 60 条；不同目标迟到结果反例通过。随后仅修正同目标导航路径，保留原代次/在途 GET/阅读窗口；API26、API37、新手机各 **3/3** 通过，共 9 项，无模型输入。原失败与 API26 属性前置条件误判均保留。
- 同聊天读取修复检查点 `ced8ac5c7a9f3f2f5010105110c811bf9f4f385d` 已正常推送并读回远端一致。
- O143 旧超时离线推断：新增同完整连接目标的验证成功序号；慢历史/目录读取开始后已有更晚成功响应，仅瞬时连接推断不再覆盖新证明，认证/身份/协议失败仍拒绝，显式读取失败提示与草稿保留。先在 API37 运行 6 项，原实现 2 项失败；修复后 API26/API37/新手机各 **6/6**。事件失败分类/补拉仍单列待迁移。
- 分页/耗尽游标、完整运行时权威替换、缓存恢复、移除/编辑目标与迟到历史、HTTP拒绝等扩展分组：API26、API37 与新手机各 **15/15**，共 45 项。旧分页验收器改为同聊天保留早期页、真实切换另一个目标才重置，原来不同目标保护继续断言。API37 扩大分组在既有离线提示用例长等待，保存原生进度、截图并向准确验收进程请求诊断栈；随后原轮完整通过，未重装、取消、重启或重跑，等待根因未隔离。
- 验证成功证据检查点 `1c4ca5b8ff690d651046923a67b466775414d956` 已正常推送且远端 SHA 一致。首屏改用刚验证目录；当前聊天先等具名事件快照最多 8 秒，未取得新历史才补读，重置附带快照时不重复 GET。先修正验收器的 wire/readTarget，再以同一正确验收器核对旧产品：9 项中 3 项失败；新实现三个环境各 **9/9**。扩展读取在 API26/新手机各 **18/18**；API37 原 18 项组合在缓存恢复处超时，保留失败、仅停止准确自有验收进程且不清数据。其 9 项调度、其余 8 项读取、缓存恢复单项分别通过，不能记为一次连续 18 项通过，超时根因仍未隔离。
- 执行回归新手机发现真实缺陷：未知回复回执被当作新建回执解析，提示界面崩溃。先检查操作种类/脱离状态再解码；新增明确查询原回执的反例，在旧产品单项失败，新产品三个环境各 **19/19**，草稿版本保留、仅一次原 POST。旧重置用例要求重复 GET 的断言也已改为准确新轮次内容/控制就绪/无重复 GET；全部原失败保留。上述均为辅助、零模型输入，P1 真实结果仍绑定原指纹。
- 首屏/回执检查点 `37419766d5760e7bcf45df1c985a3e2346a5b32b` 已推送且远端一致。本检查点补齐三种同步提示、完整目标/聊天隔离与透明只读重试，超时保草稿/窗口，较新同聊天实时窗口或历史进度否定旧瞬时错误（包括完整桌面状态未知），认证失败仍拒绝，更早页失败不覆盖最新同步状态。先以新反例核对旧判断失败，再修复；当前相同产物三个环境分别 **10/10 同步、18/18 读取、19/19 执行**，共 **141** 次原生辅助，按三组记录，零模型输入。`pnpm check` **59 命令完整连续通过**；此前未更新指纹即启动检查的失败保留。
- 同步界面溯源逐项关联 **O070 / O088**；读取/错误/超时后续优化关联 **O143 / O159**。四个原提交均只登记已覆盖子断言，超大历史/未知消息、发现/预算、静默补拉和真实模型矩阵未完成部分保留在 [逐节点范围](P2-scope.json)，不以本检查点将整个来源提交标为完成。
- 视觉复核修正默认 Material 紫色/禁用填充与警示符号，采用既有中性色、透明可访问按钮及与原端一致文案/间距。原 API26 过渡混合截图保留；按准确聚焦窗口/真实帧增加 PixelCopy与全屏双采集后，15张所选状态图核对通过，30张原图及指纹保留，OEM字体/系统栏未改，不宣称完整P6。中间 API37 8项组在认证提示处超时仍记失败；仅停止准确自有验收进程且不清数据，当前全部三组通过，原超时根因未隔离，不宣称已修复。
- [P2 当前证据](../../acceptance/mobile-evidence/android-alignment-P2.json)逐组绑定源码/包/runner 与原失败；后续按连接预算、后台发现优先级/增量发布/保留分检查点推进，随后事件失败分类、按有效 checkedAt 推进的静默补拉、同客户端重连阅读矩阵与真实弱网读写。完整 P2 未通过。
- Task Hub 业务管理保持暂缓，A6/P7 保持暂停；P1 待验收项持续保留，不移除、降级或宣称通过。

- 同步提示检查点 `1a2049453e6710b47ebb6f027b417f91c633131e` 已正常推送并核对远端一致。O159 连接描述预算已实现：后台 2 秒，显式点选/手动/二维码 8 秒；目录独立保留 iOS 的 6 秒。单调时钟覆盖排队和持续分段响应，父取消与证书/目标/鉴权拒绝保持准确，原 claim/不可变写入及历史/工作区期限保持原规则。
- 先前候选在三个环境合计 171 项原生辅助结果与原始输入均保留；逐行核对原端发现目录应为 6 秒而非 2 秒，已在提交前修正，并补充慢目录成功/整次截止反例。旧产品预算/持续分段两个反例失败，首版错误 legacy token 导致的 401 和界面截图 ID 前置失败也保留；不放宽鉴权或伪造整组通过。最终修正候选的相同产品/测试包在三个环境分别 **14/14 预算、18/18 私有核心、7/7 界面、18/18 读取**通过，共 **171 项**，按每环境四组记录，零真实模型输入；原生82任务构建通过。源码冻结后 `pnpm check` **59 个命令完整连续通过**，Windows 专属跳过不算 Windows 验收。更早序列虽返回0，但中途校正了源码，仅作旧候选辅助，不替代当前冻结序列。下一步后台发现排序/增量发布/保留及剩余P2矩阵，整 P2 保持进行中。

- 连接预算检查点 `56b4cacbc3fc11c8941abeae6491c094cfff4c1e` 已正常推送并核对远端一致，之后才进入公开发现检查点。按 O008/O143/O159 校正每源32地址和128候选上限，独立已配对来源、空目录续查、地址排序、四并发，验证地址增量发布到原生列表；60秒保留/15秒仅可达提示、前后台/目标/证书/代次保护保持。已保存电脑的自动采用首个可达地址时机单列后续，不以列表显示替代。
- 旧逻辑仅增加只读测试接口后，12项中9项语义反例失败、3项取消/身份保护通过；中间核心12项通过。最终接入与新增生命周期/身份测试后，三个环境各 **14/14** 调度辅助通过；最终相同产品/测试包另有各14项连接预算、18项私有核心、7项界面分组通过，总计 **159项** 原生辅助，按四组/环境记录；共享Agent契约/Swift/JVM检查通过。前一预算检查点全59命令保留，未改Node/Host/TypeScript；本批不新增全序列通过。iOS工程构建通过，但没有增加iPhone或真实模型通过数；全19功能/35来源节点保留，P2仍进行中。

- 公开发现检查点 `130af51c16d83f75a8f765f0236f2fe5afedfef8` 已提交；第一次推送鉴权失败保留，正常重试后独立核对远端一致，再继续已保存地址调度。原24项对照有3项失败：两类等待慢地址、验证目录被离线来源挤掉；另补原列表地址回退反例1项失败，原连接快照2项中编辑反例失败、账号变化保护通过。
- 当前已保存地址与公开发现并行，四槽共享；离线首个已核验地址和健康当前地址及时发布，保留提示没有新证明；原连接/完整目标/证书/reader隔离编辑、移除和重接入，账号元数据与用户名称独立。最终相同产品/测试包三环境各 **29/29调度隔离、14/14预算、18/18私有核心、7/7界面**，合计 **204项**，每环境四组，零模型输入；原生82任务构建通过。新手机中间UI组端口冲突发生在运行前，最终新夹具通过；一次候选复制误用主检出默认目录的DEV包，被内嵌包名保护在三个环境安装前挡下，均未安装，修正为专用树绝对路径冻结后通过。最终格式/静态/一致性检查通过，iOS/共享源未改，P2真实弱网及剩余矩阵继续。

- 已保存地址检查点 `5f4bfb5a4db218ce720b44267110a34257851445` 已推送并独立核对远端一致，再进入事件失败保真。旧逻辑 API26/新手机各10项完整组有7项反例失败；API37原10项超时保留，单独证书反例另为1项失败。逐行核对iOS后，后台事件不弹全局框的验收断言已校正；编译前refresh返回Unit误用join的失败也保留。
- 当前事件严格区分非瞬时/瞬时/取消，按原请求开始的成功/快照顺序隔离旧超时，新成功不能掩盖鉴权/证书/身份错误；实现读取退避但不改原写入/回执恢复。当前相同产品包三环境各7项安全、7项时序、2项真实原生UI，以及10项同步、18项读取、19项执行，共 **189项**，验收器两版本分别绑定。API37原同步10项组合在原生绘帧采样后长等待，中断仅自有验收App且不清数据，原失败/原图/栈保留；5+5精确组补核通过，不记连续10项，根因未隔离。六张精确窗口图已复核、12张原图保留；最终格式/静态/一致性检查通过，静默补拉/真实弱网及整P2仍进行中。

- 事件检查点 `c4b4797cc74a972e80ded6ca39392f9b43424ad4` 已推送并独立核对远端一致，再进入有效进度补读。旧Session三环境各7项对照均有4项失败、3项原保护通过：缺活动/空闲独立补读、无法触发旧目标补读场景、后台读取禁用发送；旧源码/包/原日志保留。
- 当前单一路径独立GET补读按严格推进checkedAt与单调时间调度，重复/更早/等时区/非法时间和缓存不续期，保留8秒宽限、活动约2秒/空闲10秒、同目标合并、窗口/草稿和代次/前后台取消。作为必要前置，仅本次导航首读限制输入；后台/显式刷新保留已就绪输入，新导航不能借旧缓存，真正发送预检保持原流程另验。最终相同产品/测试包三环境各7项对照、11项时间/输入策略、18项读取、10项同步、19项原执行、14项事件分类时序、2项事件UI，共 **243项** 原生辅助，零模型输入。API37同步预先明确5+5分组，旧组合挂起根因仍未隔离；不声称连续全组。当前原生82任务构建及最终格式/静态/一致性检查通过，六张当前事件窗口图复核、12张原图保留；登记脚本可选字段错误已保留并按实际写入点续行；同客户端重连上下文、真实弱网、直接就绪回复、超大/未知消息及整P2继续。

- 有效进度补读检查点 `f4c173f8122c942f8d25c5f2cf234a45835124b5` 已推送且远端一致。继续逐行核对iOS：重选客户端虽建立新读取代次，RootView仅按完整targetKey重置展示；同目标展开窗口仍保留。Android原六项对照三环境各4项失败：两类窗口120收回60、两类旧在途标记阻止新读取；变更runtime草稿隔离及重连不借旧页/游标两项通过。旧产品与当前全部分组使用同一验收器包，原失败保留。
- 当前展示状态与网络代次分开；完整目标不变保展开/草稿，变更目标重置；历史GET有准确导航/连接owner，旧finally只释放自己的槽，同代次合并与新证明门槛保持。当前相同产品/测试包三环境各6项新对照、7项补读/输入、11项时间/输入策略、18项读取、10项同步及19项原执行，共 **213项** 原生辅助，零模型输入。API37同步仍明确5+5，原组合挂起未隔离；不记全组无中断通过。当前原生82任务构建及最终格式/静态/一致性检查通过；同客户端目录读取并发、前后台/路由、真实弱网与滚动视觉矩阵继续，P2不整体关闭。

- 展示/历史归属检查点 `b3ababe321aa6b1371708a1255cb95d7395d2894` 已推送且远端一致，再进入目录在途对照。旧三环境各4项有3项失败：同客户端重选、验证重连的新目录被旧标记挡住；仅换聊天共用目录原保护通过。同一验收器的新实现三个环境各4项全部通过，旧产品/包/日志保留。
- 当前目录有独立客户端导航/连接owner，成功/错误/结束只处理准确owner；同客户端及时新读，旧完成不清新忙碌，仅换聊天继续共用目录。验证重连复用目录及此前展示/历史/补读/输入边界保持；相同产品/测试包三环境各4项目录、6项读取上下文、7项补读/输入、11项时间/输入策略、18项读取、10项同步与19项原执行，共 **225项** 原生辅助，零模型输入，API37同步明确5+5。当前原生82任务构建及最终格式/静态/一致性检查通过，30张当前同步原图保留；下一步前后台/GET切路、真实弱网、原回执、首屏20/大历史/未知消息和友好错误，整P2仍未通过。

- 目录检查点 `6b027f9dc07f04a34711cb5df46d63ab2fc92e0b` 已推送并核远端一致，再接前台路径监听与GET恢复。已有HTTP取消断连实现保持；路径基线/同信号不重启，短突发200ms合并，目录/历史/事件及原回执只读观察轮换，不改变目标/凭据/原POST与操作ID，缓存checkedAt不因轮换变成新进度，同导航旧回写按准确owner隔离。
- 首组实际三环境各10项通过，但汇总器误计11且沿用“只读”旧标签，原包装失败与真实结果均保留；补客户端导航用例、按方法数核对并更正合成POST说明后，中间同包 **312项** 辅助通过。提交前复核发现只重启当前聊天会漏掉原后台回执：三环境单项反例各失败，修复后同一验收器各1/1；按仍配置的完整目标/原记录恢复所有原观察者，移除/不匹配目标不恢复，不重发输入。
- 最终相同产品/测试包三环境各12项恢复/原POST/原回执/平台注册、13项时钟/输入、4项目录、6项上下文、7项补读/输入、18项读取、10项同步、19项原执行、14项事件及2项事件UI，共 **315项** 原生辅助，零真实模型输入。API37同步明确5+5，旧组合等待根因仍未隔离；不记无中断全组。中间312项与最终指纹分开，当前82任务原生构建及格式/静态/一致性检查通过，42张原图保留、六张当前事件窗口复核；真正手机切网/原Electron模型弱网、首屏20/大历史/未知消息、友好错误及完整P2继续。

- GET恢复检查点 `890cc9dcf7fc9c372ad5b485a56ac5a09e2beced` 已推送并核远端一致，再接O038首屏优化。共享定义生成双端首屏20/早期+60；iOS行为不变，Android新目标重置20，保留完整源消息和跨界工具组，同目标展开不丢，实际早期按钮保留原首条锚点/草稿且不多发GET。新4项旧三环境各4失败，当前通过；读取上下文的窗口页验收同时经过本地展开和服务端早期页。
- 当前同产品 **126项** 原生辅助分组通过：三环境各4项新窗口、18项读取、6项上下文、10项同步及4项窗口/手势/Markdown/工具UI，API37同步明确5+5。114项使用第一runner，12项UI使用修订runner；产品包完全相同。原UI4项API26通过，手机/API37首项在原300秒期限内未完成，45秒线程现场及原失败保留；仅修订不旋转手势用例的会话引用、避免重复平台空闲等待后，各4项另行通过，未证明GPU根因或把原失败改通过。共享JVM51项、iOS编译通过；工作区59项连续检查及最终格式/一致性检查通过，18张当前原图保存并核对三组展开前后锚点。O038建议/reader/保存让位、超大/未知消息、友好错误及真实弱网/P2/P6继续，P1卸载owner按人类要求待验，A6/P7暂停保持。

- 首屏检查点 `394dc4ab3ec1d3980c615e2804007dafbae70133` 已推送并核远端一致。O088准确指历史未知结果回执；Native保留typed context、默认折叠、展开原文和准确说明，错误独立可选，不误作当前等待或自动重发，当前未知/失败及目标隔离保持。扩展回归又发现生成设置丢字段，旧包也复现：UI旧整份快照会覆盖新选择；现按完整原目标原子修改当前字段并复核当前选项，当前强度/速度随换模型归并，原已提交设置不改。
- 旧历史6项三环境各4失败；生成设置纠正时序后同一个runner旧4项各4失败、新4项各4通过。最终同产品 **74项** 原生辅助分组通过：三环境各4项表单、6项历史/当前/错误、2项原失败/许可/关闭/加密恢复；另两模拟器各19项原执行；两个明确runner版本，19项仅两模拟器各自连续完成，手机综合19项失败且不计通过，不记单次31全组。旧19手机两项失败、旧包模型丢字段、原菜单节点查找事件和早期许可/前台/安装读取/关闭断言失败均保留；生产菜单未改，原执行触摸前补滚动/显示并核对原GET与无重发；新增控件状态用原生语义动作，完整历史行触摸门槛留P6，单次旧查找原因未隔离。旧许可用例补真实UI、授权前后零POST、显式再次点击单次合成输入；旧前台用例采用既有逐项激活，关闭用例滚入可视区并等待原结果。手机综合19原始6项失败明确登记，不将先前个别通过或模拟器代替整组；焦点实验未证明根因，保留后撤回实验测试改动，仅停本任务验收进程。当前82任务原生构建、格式/静态/一致性检查通过，准确交付App/runner已恢复到手机并保留数据；零真实模型输入，F34/F35/F48/F64与P2/P3/P6整体验收继续，原暂缓项不变。

- 回执/表单检查点 `543cd5ae3c486179006e298448ec4611c998acdf` 已推送且远端一致，手机综合19项6失败保留。继续O016/F19长用户消息：默认六行预览、原文可选与显式展开/收起，附件/草稿不变，完整目标+消息ID隔离。共享设计token生成双端，iOS仅替换原六行常量。最终同一runner旧四项三环境各4失败；新产品三环境各5项布局/原文/64KiB Unicode/目标隔离及两模拟器各4项原阅读手势/Markdown/复制/工具，共 **23项原生辅助** 分组通过；手机原阅读组4项失败独立保留，零模型输入；中间版本及验收器expectedTests元数据笔误单独留证，不叠加为最终通过。iOS编译及工作区59命令完整连续检查通过；最后Android独有省略号/空白保留修正另经构建、最终23项及收尾源一致性检查，六张最终窗口图已复核。手机中间阅读4失败、旧包300秒下拉等待分别保留，最终手机五项不替代该组，根因未隔离。Android48dp/iOS44pt触达差异明确登记；原系统选择菜单/字体/主题/键盘/无障碍、真实长桌面历史与弱网、原手机综合失败、P1卸载owner待验和A6/P7暂停不变，O016和P2仍未关闭。

- 用户气泡检查点 `742c4b69881e25e383ec2a025b926db6755d2fbc` 已推送且远端一致；其23项辅助、手机中间阅读4失败和旧包下拉300秒等待分别保留。继续O146/O148读取友好错误：六类同份v1文案生成Swift/KMP，原生类型分类、证书/TLS优先级和有界因果链；读取弹窗/内联提示采用共同文案，后续本地错误不沿用连接标题，非网络错误与原传输/授权/写入策略不改。iOS分类/本地化行为保持。修正弹窗正文选择器后旧8项三环境各7失败、权限原文/默认标题通过；原误选重复内联文案导致8失败保留。最终同产品 **162项原生辅助** 分组通过：两模拟器各56/手机50，两个明确runner。API37原5项组300秒失败留证，准确自有验收进程停止、不清数据；另1+4通过，不记连续5。手机读取原18项失败分别为确认按钮缺失/1秒等待；只修验收器为10秒且核对新GET/显式错误，保留原手势/确认及缓存草稿断言后，另18项通过。手机同步重试5项组实际按钮等待10秒失败，系统外部触摸INJECT_EVENTS拒绝，均不计通过；未提权/改权限或换注入器。独立外部触摸证明两模拟器旧错误弹窗会消失，修正后保持直到明确确认；不据此断言手机触摸根因。51项JVM、iOS编译、1535条本地化及59命令工作区通过，原编译/定位器/文案断言/lint失败留证，最后Android改动另核原生和源一致性；非读取全局范围及真实弱网/原手机交互失败与完整P2/P6仍保留。

- 友好读取提示检查点 `746bbcc206e4576aa0f4db0609accfbac2346990` 已推送且远端一致。准备真实弱网时发现O088关键缺口：Native虽能解析窗口，实际history和events未发送`window=latest-v1`。现最新/更早history及events都显式选择既有有界路径，原目标/reader/游标/epoch/序号不变，无新POST或权限变化。相同最终runner旧三项三环境各3失败、新三项通过；初始事件fixture缺完整目标的错误先修并保留，不记为生产缺陷。API37原18项300秒失败和原生绘制栈保留，准确自有验收进程停止、不清数据；另以1/9/8独立分组覆盖，不记连续18或根因修复。最终同产品/runner **123项辅助** 分组通过（各41）：实际HTTP请求3、读取18、窗口上下文6、事件安全/排序各7；原失败与真实桌面/弱网/模型证据分开，参数存在不等于真实有界性能已验。最后格式/静态及源/契约一致性检查通过，原手机重试5失败/系统外部触摸阻塞及其他暂缓项保留，P2仍未关闭。

- 有界窗口请求检查点 `a1740656d62b38786245210c71fe792f67de647c` 已推送且远端一致。O157原生读计时现按DEBUG独立验收包、新Activity显式Intent、规范Host/run UUID启用，默认关闭；连接成功/历史响应/当前owner应用前/缓存应用前固定事件，最多256行，不收地址/正文/凭据。异步写入失败不影响结果和草稿，旧证据不覆盖、旧run不串写。最终同双APK **102项辅助** 三环境各34通过（计时7、真实本机窗口HTTP3、上下文6、读取分9/8/1）；7含实际NativeHostReads响应与写失败隔离，未输入真实模型。构建初次require消息lambda插入错误先修并保留；最后所改文档格式、仓库lint/一致性与diff检查通过；三环境各262行实际辅助元数据已在树外保存并逐字段读回核验。实际原桌面弱网、缓存/新内容首屏、输入就绪/终态尚未验收；应用前计时不冒称绘制完成。原手机失败、系统触摸注入拒绝、用户暂缓owner及任务/项目、暂停A6均保留，P2未关闭。

- 原生读计时检查点 `13bdedc90b948740766fb11ef75d1c5d18f02c53` 已推送且远端一致，首次推送鉴权拒绝原日志保留；独立只读远端查询成功后一次推送补齐并核对。随后发现只读弱网代理未允许Native events的target，且工作区错误比较account对象。现按既有Schema严格校验target及四项机器身份，原事件查询不改；合法account仅历史元数据，变更/移除不拒绝同一Codex机器。仍GET/白名单/零上游写。相同最终验收文件旧代理8项2失败，新 **8项Node真实本机HTTP辅助** 全通过；原压缩/带宽/截断/断网/写拒绝检查保留，端侧源码未改，102项Native仅按匹配源码复用。完整`pnpm check` 59个串行检查不间断通过（含当前8项代理HTTP检查），实际原桌面弱网/模型、原手机失败和用户暂缓/暂停项保留，P2未关闭。

- 只读代理检查点 `11689706be0d6b79c332e62362b29198ebe6a821` 已推送且远端一致。P2首次真实真机/独立原生UI与存储/TLS/隔离Host/原Electron验收：**两条原输入各一次**，均sol/xhigh、实际list_projects、SDK文件变更项0，独立核对没有新输入。首条正文显示与“历史挂起、事件继续、保留在线/正文/输入可用”断言通过；第二条实际完成，组合Native **失败保留**：验收器在submitted时错按创建confirmed判断回复（实际稍后completed），未到第二正文可见断言；且未为后续聊天再次选择只读，界面与实际权限为电脑默认完全访问，原只读前提失败不改成通过，P4继续对照双端行为。原网关快照仅创建prepare/submit及reply各一次；独立只读对账确认两条，无重发。19行实际Native元数据、失败截图/语义树/日志与原前置失败保留；验收器修正只编译，不借新模型覆盖失败。外部目录run-as读取权限拒绝保留，未来证据改私有内部目录；网关另有未登记路由拒绝未归因，不宣称全路由通过。配置指纹未变；所改文档格式/一致性及diff检查通过、验收器82任务编译通过，接下来只做零新增输入的正常/受限/严重、截断/全读503/恢复/缓存补验，P2/P4/P6及原暂缓/暂停项不关闭。

- 真实原输入/失败检查点 `4d85df2918671bebc32891f0d025825cedcd93f5` 已推送且远端一致。随后 **2项零新增输入真实补验通过**：同真机/双APK/独立Native存储，正常/受限/严重HTTP带宽/RTT/抖动、实际截断、命名GET全503/恢复、原第二最终正文可见、草稿与两条原记录ID/intent指纹/终态保持；另一真实进程在2项命名数据读503、成功0时先显示磁盘原正文/草稿，恢复后仍不变。Native PID不同，两例新增命令POST/模型均0，独立原桌面确认总输入仍2；原第二权限完全访问、原组合Native失败/只读前提失败保留不清零。20行补验原生计时、3张实际503/恢复/冷缓存图已保存并亲自复核。代理整体132读/1056593线字节、截断1、上游写0、拒绝0；聚合历史峰值2只在此前发输入期间重叠（实现另有authority读，未捕获调用ID，不归因或宣称全局单飞）。自有Node/Host准确PID已实际停止、原桌面/服务和配置指纹及数据保持；所改文档格式/一致性与diff检查通过，正式MainActivity冷启动、真实切网/DNS/TLS拒绝、大真实历史与完整P2/P4/P6继续待验，原暂缓/暂停项保留。

- 真实只读恢复检查点 `057f44436fa6eda641475a34a6e1801fc129338f` 已推送且远端一致。继续真实大聊天时发现“最新读取”误显示“正在加载更早消息”并给短历史插入虚假按钮；已按iOS既有earlierHistoryRequest分离实际更早动作，最新GET仍禁用按钮。相同最终runner旧3项每环境2失败，新3项通过；当前产品 **81项辅助** 分16组通过（每环境27），API37原8项90秒绘制栈超时、手机APK读回60秒测试前超时均保留，后续4+4/8通过不改写原失败。
- **3项真实原桌面大聊天只读Native验收通过**（API26/API37/新手机各1）：真实Host/Adapter/NativeHostReads、原生UI与独立存储，预检5109项/约11MB/8条压缩；各端最新100、2页更早各100，最终300/301条（活跃聊天可增长），首屏20/本地+60、原ID去重/已完成压缩、最新GET后锚点0偏移/草稿/磁盘缓存保留。44行实际计时及原图保存，未提交任何新输入/命令POST。验收器旧未选目标、可选revision、禁用按钮时点、后台latest更新当前page.kind失败分别保留；现在观察真实Native返回处earlier元数据，未改响应或暂停事件；API26旧exec-in未写文件的测试前失败保留，shell -T同权限/同私有文件字节核对后通过。自有只读Host/代理准确停止，原桌面/owner/配置与数据保留；所改文档格式、完整源/契约/向量一致性和diff检查通过。此为所选大历史/锚点/缓存门槛，完整帧/性能/切网/正式MainActivity冷启动、O038建议/reader/保存让位、原手机失败与完整P2/P4/P6继续，用户暂缓/暂停项不变。

- 更早加载/大聊天检查点 `4cd44152b67d745218eb3d3eff1ec692a5d29873` 已推送且远端一致。继续O038导航保存：Android原先点选新聊天先生成/排队导航快照，再启动历史；现在先更新原阅读目标并启动缓存/历史/原回执读取，再于函数返回前保存导航，不引入延后任务，其他setter、草稿及提交前落盘路径不改。实际Native加密存储FIFO同一验收器旧3项各1失败，新3项各通过，断言读取排队顺序、等待GET时导航和即时草稿仍落盘、切回原目标拒绝旧页。
- 当前同双APK **102项辅助** 分24组（各34）及 **3项真实原桌面大聊天Native** 各1通过，无Native重跑、零新增模型输入/命令POST；保留原窗口/ID去重/压缩/刷新锚点/草稿/磁盘缓存。自有只读Host/代理已准确停止，原桌面/owner/配置和数据保持；所改文档格式、完整源/契约/向量一致性与diff检查通过。Session持有一般历史reader已符合既有边界，桌面建议reader/并行预读仍留P5；完整导航视觉/性能、真实切网/DNS/TLS拒绝/正式MainActivity冷启动和完整P2继续，原各轮失败及用户暂缓/暂停项保留。

- 导航读取/保存检查点 `71549cc87239b96d97f4423db9c7df8dadf36225` 已推送且远端一致。生产源码和APK保持，新增 **9项真实Native网络边界**（三环境各3）首次执行通过：真实DNS失败/UnknownHostException与GaiException、实际原生本地关闭TCP端口/ConnectException、临时RSA未受信任证书/SSLHandshakeException及证书链原因；原异常保留且共同标题/不可达或证书文案断言通过，原生原因日志已在树外保存。每端TLS服务应用HTTP请求0/握手拒绝1，自有服务/反向端口/合成临时证书均已清理，零真实模型输入、未关闭信任校验。所改文档格式、完整源/契约/向量一致性及diff检查通过；先前102辅助/3实际大聊天仅按相同生产源码与APK匹配复用，没有重跑。此为NativeHostReads与分类文案边界，完整Compose/Session缓存恢复、已配对证书变更/真实切网/正式MainActivity冷启动及完整P2继续，原各轮失败及用户暂缓/暂停项保留。

- 原生DNS/TCP/证书边界检查点 `22bc954ba0fa08d971daa94cbf68c43612f0107e` 已推送且远端一致。回到旧手机同步失败：当前原生重试单项先通过，再以 **三环境各5+5，共30项辅助** 完整所选组通过；单项不额外计数，也不据其代替完整五项。生产和原验收器未改，保留原performClick/等待/原GET/窗口/草稿/原记录与帧检查，没有语义替换或外部触摸请求。当前产品2c7ac8c/runner fe5204f通过，旧09749e8/11c39e真机五项失败及旧API37绘制等待原记录保留；旧根因未隔离，不归因单一修复，也不记连续十项。所改文档格式、源/契约/向量一致性与 diff 检查通过；其他原手机失败、真实路径/正式MainActivity冷启动和全P2继续，用户暂缓/暂停项保留。

- 当前同步触摸检查点 `dd80da159782fd6fb84e730eba30ce28802ac3d7` 已推送且远端一致。随后 **最终同runner3项实际原桌面读取** 通过：API26/API37回环各1，新手机真实LAN固定证书TLS及Wi-Fi关闭/恢复1。实际TLS客户端peer均匹配手机Wi-Fi前后地址、数据TLS端口无ADB反向转发；原生QR兼容解析与leaf DER pin核对，原消息/两页早期/压缩/最新刷新锚点/草稿/缓存保持。原生OS监控11回调/5种路径，实际断网缓存与草稿可见、恢复自动读通，原目标/pin/展开窗口与ID保持，新增输入/POST/操作0；Wi-Fi实际已恢复开启。
- 较早LAN触摸基线使用独立runner另存；首次radio准备在后台latest把更早按钮从可用变禁用时失败，未到radio-ready、未关闭Wi-Fi，原日志/状态/包/源码保留。验收器仍用原15秒预算、原生touch并核对是否接受；只重试未接受触摸，实际早期失败不重试，原单页单响应/游标/ID断言和正常事件保留。两张真实断网/恢复图亲自复核，不把同步banner插拔的像素位置或全P6宣称通过。自有TLS/只读代理/Host准确停止，原桌面/owner/配置和数据保留；所改文档格式、源/契约/向量一致性与 diff 检查通过。生产APK2c7ac8c不变，旧辅助按匹配源码复用；正式MainActivity冷启/生命周期、VPN/蜂窝/完整性能及全P2继续，原各轮失败和用户暂缓/暂停项保留。

- 实际LAN/Wi-Fi检查点 `894611e90cc8218e43fb49b368a9509561ae8126` 已推送且远端一致。随后 **真机生产MainActivity默认缓存2项实际验收** 通过：相同APK/runner暖态原生读取原桌面并保存100条缓存/草稿，准确独立验收包新进程PID923→2802、数据不清；冷态不注入Session/reader/cache、不reset，在4次命名数据GET503/成功0时实际显示磁盘原正文/ID与草稿，服务恢复后生产前台自动读通、父进程独立确认新history200，原目标/pin/草稿保持，新增模型输入/操作0。两张实际503缓存/恢复图亲自复核。
- 初始空Compose启动未前置目标Activity、agentMessage真实类型样本误筛与目录匹配诊断log的冷验收前置失败分别保留，类型编译笔误先修留证；仅使用既有NativeTestForeground/原生Activity规则后通过，不据此宣称生产启动缺陷。自有TLS/只读代理/Host准确停止，原桌面/owner/配置与默认数据保留，Wi-Fi保持开启；所改文档格式、源/契约/向量一致性与 diff 检查通过。当前生产代码/APK仍不变；API26/API37默认MainActivity冷启、长失联/全生命周期/VPN蜂窝/完整性能及P2继续，A6/P7迁移、用户暂缓项及原各轮失败保持。

- 真机生产MainActivity检查点 `e384041e0ce002e52912b06bf26cfc2115a33727` 已推送且远端一致。同一生产/runner补 **两模拟器暖/冷4项底层原生通过**，加真机累计同字节6项：两模拟器各4个命名数据GET503/成功0时先显示默认磁盘原正文/草稿，恢复后自动新history200，原目标/pin/草稿保持，新输入/操作0；模拟器仅回环TLS/ADB，不代替真机LAN。
- API26原生两项各OK1，但暖包装遇私有诊断JSON并发写损坏，冷包装await字段取在Promise上，原包装/cycle失败均保留；原日志/包/源码/原生证明及0成功/4次503和实际新history只读对账通过，**未重跑暖/冷Native**，不改原报告。API37两项完整包装/循环首次通过。两张实际缓存图已复核，API26整体灰化/letterbox现象未隔离、不记全视觉；所改文档格式、源/契约/向量一致性与 diff 检查通过。自有TLS/代理/Host和两条准确映射已清理、两验收App停止且默认数据保留，原桌面/owner/配置保持。完整P2/长生命周期/首帧/视觉/其他路线及原失败继续，A6/P7迁移和用户暂缓项不变。

- 三环境默认缓存检查点 `a484928dddb404b55db4fee2579502d69857ae0a` 已推送且远端一致。全部35个P2来源节点和19功能的实现边界、代码指纹、证据及后续归属已登记到 [逐节点复核](P2-source-review.json)，原列表不删项。桌面revision重置由Adapter处理，双端运行资格共用有效Turn规则；建议、队列与全视觉仍分别归P5/P4/P6。Windows与VPN实测不冒称Android通过，P2整体仍开放，继续证书变化拒绝和缓存界面恢复。文档检查通过。

- P2 paired-certificate/default-cache checkpoint: source review `57383adeb1183b2215f56f79b7b015c31f8b7022` pushed and independently remote-verified. Physical original-data warm/certificate **2 native phases passed**, same production APK; default-store new process retained target/pin/cache/draft. Changed isolated leaf caused4 real TLS handshake refusals and0 application HTTP; original leaf restoration plus explicit native retry yielded independently confirmed history200,0 new inputs/operations. Original desktop/config/native data preserved; exact owned TLS/proxy/Host stopped. Immediate post-confirm screenshot still has gray dialog layer despite native error-clear/cache assertions: stable exit-frame cause/visual acceptance separately pending. API26/API37 paired UI, route/performance/full P2 remain open; next isolate dismissal and complete emulator matrix. Documentation checks passed.

- P2 paired-certificate stable UI matrix: preceding checkpoint `30d90364ca57cfb918665f9b008a5864dca17673` pushed and independently remote-verified. Same production APK and same final examiner: physical/API26/API37 **6 new underlying native phases passed**, each with4 TLS refusals/0 application HTTP under changed leaf, original target/pin/cache/draft retained, original leaf plus explicit native retry confirmed fresh history200,0 new inputs. Added bounded workspace/history/error-node/native-window settling without removing any original assertion;6 stable cache/recovered frames personally reviewed. Original immediate gray screenshot and unrelated old visual cases retained; individual timing cause not isolated and no production-fix claim. Owned services/own apps stopped, native data retained, own emulator reverses removed. Next: actual same-machine endpoint switching/discovery and timing. Documentation checks passed.

- P2 production automatic-route checkpoint: preceding certificate matrix `b0627b7a1b25baa90bf4fd585d351df115df6172` pushed and independently remote-verified. **3 actual original-owner native cases passed** across physical/API26/API37, same product/examiner: default MainActivity/store normal foreground discovery switched to already advertised same-target/pin alternate after exact owned primary TLS listener closed. Alternate0 successes before cut, fresh alternate history200 after cut independently verified; original credential/draft/sample and80 window retained, visible anchor0dp each,0 inputs/operations. No native injected connect/scan/retry. Six before/after frames reviewed; actual LAN vs emulator two-loopback/ADB separately recorded, API37 existing permission only read. Owned two TLS/proxy/Host and exact own app stopped, native data and original desktop/config/owner retained. Cold persisted alternate, repeated/longer loss/timing/VPN/cellular/full P2 remain open. Documentation checks passed.

- P2 persisted-alternate cold checkpoint: preceding automatic-route `7e851a290543e50bfbabae6a8130cca7b89258b3` pushed/independently remote-verified; remote main still matches frozen base. Physical/API26/API37 **6 new same-examiner native route+cold phases passed**: native local scroll/click20-to80 with0dp route anchor; exact own app new process/no data clear, cold no injected Session/reader/cache/reset/QR, persisted alternate/target/pin/credential/IDs/body/draft and initial20 restored under4 named503/0 successful data each, then automatic alternate history200/0 inputs. Six cold cache/recovered frames reviewed. First15s wait for an offscreen local-more control failed before primary cut/cold and remains original; subsequent readonly native inspection proved existing localEarlier/cursor with no composed earlier tag. Corrected native scrolling using existing pagination pattern, same15s and exact80 assertions, production unchanged. Owned services/own apps/reverses stopped, data/original desktop/config/owner retained. Full timing/long-loss/VPN/cellular/visual/P2 remain open. Documentation checks passed.

- P2 current read observation checkpoint: preceding cold-route `da3d9db74614c68516775ab11914e8495d39315f` pushed/independently remote-verified. **6 final same-examiner native phases passed (3 route/cache setup+3 observation)** across physical/API26/API37. Initial4 named503/0 successful data: original cache and local input edit/restore; then only history withheld~35s while actual events success48/48/49 and pending-history input/new ordinary original-owner content verified,0 new model inputs/operations. Independent actual history200 acknowledged before native exits. First API26 parent gate failed although original native UI/events assertions passed; preserved, fixed native receipt handshake within unchanged60s wait.
- Native observer-to-cache assertion upper bounds: physical2207ms/API261192ms/API372038ms; input edit3338/2055/3139ms. These include runner/bootstrap, scripted order and scroll/edit overhead; not physical-first frame/release startup/SLA/model completion lag. API26 second held retry started29ms before fixture release, another35002ms timeout; its87574ms final scripted observation not normal recovery latency. Historical old-product custom Session627/1189ms observations stay separate. Six current images reviewed, exact owned services/apps/reverses stopped, native data/original desktop/config/owner retained. Full frame/terminal/long-loss/VPN/cellular/performance/P2 remain open. Documentation checks passed.

- P2 default MainActivity physical long-radio checkpoint: preceding read observation `9a4222e674747d4da99b50bf967bd46c3c79b222` pushed/independently remote-verified after preserved first auth failure/bounded normal retry. **2 physical native phases first pass** (actual route/cache setup+real30s Wi-Fi loss): original Wi-Fi enabled, actual off30078ms and restored enabled, settled0 new HTTP/no data ADB reverse; production monitor retained/passive actual2 available+1 lost callbacks, no injected callback. Native offline observation29516ms/116 invariant samples keeps target/pin/credential/draft/sample and80 window; independent fresh history200 before native exit and original visible anchor0dp after recovery,0 inputs/operations. Three online/offline/recovered frames personally reviewed; exact owned services/app stopped, native data/original desktop/config/owner preserved. One cycle only, repeated/VPN/cellular/full frame/live terminal/performance/P2 still open. Documentation checks passed.

## 提交和证据规则

每阶段记录源码/包/runner/设备/Host/Adapter 指纹、适用断言、实际测试数、原失败、未验收项和下一门槛。阶段提交包含进度及脱敏证据；精确提交 SHA 在后续阶段开始时回填，避免提交自引用。只向本专用分支正常推送，独立读回远端 SHA，不合入 main。

私有原件保存在主工作区忽略目录 `.local/android-alignment-20261022/`，包含输入补丁、旧 WIP 盘点、构建日志、原生执行回执及截图。仓库不保存手机序列、凭据或真实聊天/附件。

- P2 confirmed-owner reply checkpoint: preceding `97c29ed57ccf8764ad4d1a0e779bf13c399326e0` pushed/independently remote-verified. Native reply now uses only foreground/current exact target+thread+connection/navigation generation fresh writable owner proof, evaluates it again before POST, and still lets Host enforce owner/target. Background GET cannot block an already-ready reply. Upload-boundary capability/plan/runtime-turn changes prevent old intent. Same final examiner on old product:6 native cases/3 expected send failures; final product **78 auxiliary cases in12 separate groups passed** across physical/API26/API37,0 real model inputs. Native UI single POST/immutable text+read-only setting/journal/no desktop-open or pre-POST extra GET, new-navigation disabled send and original-unknown receipt query/no replay verified. First two invalid canCompose assumptions,3 interim mixed-turn boundary failures, and first final-product physical5000ms timeout retained; individual physical cause not isolated, diagnostic-only separate final cases keep original assertions/budget. Original2 P2 tasks/policy failure and all live fingerprints remain scoped; no automatic current-product live pass. Actual stalled-history once-only model/send and full P2 continue.

- P2 actual confirmed-owner live-send checkpoint: previous `97fc727b7cf5c4976410965ed9c796c8fdd0480c` pushed/independently remote-verified. Same product, opt-in native examiner now waits for independent actual held history before reply. **2 new physical LAN/TLS original-owner model tasks each exactly once**, fixed text/model/xhigh/readOnly validated before forwarding;3 unique prepare/submit/reply POST stages. Actual reply POST4034ms after history stall starts while connection remains held; both independent original turns completed readOnly/on-request with actual list_projects and exact native final. **2 underlying native warm/cold phases passed**,21+6 trace rows, shaped profiles/truncation/503/recovery/draft/original-intent verified, separate-process native custom namespace before-data cache and0 cold POST;6 frames reviewed. These are custom Session/store cases, no default MainActivity or physical-frame timing claim. Gateway old allowlist rejected an auxiliary GET without raw-path trace; source suggests automatic fork-options, exact historical path not proven. Keep overall route coverage partial, fix/reconcile with zero new model inputs. Initial private en0 and parent directory-filter errors corrected before their corresponding service/cold launch. Actual original desktop/appserver/config/canary files retained, exact own services/app stopped/control reverse removed, Wi-Fi1. Original older2 policy/combined failure and all scope/defer/pause records preserved; cumulative actual acceptance inputs13, full P2 open.

- P2 current-product readonly named routes/default cache checkpoint: preceding `cd3ee1ba178a93bb93f310ef69483029b79352f9` pushed/independently remote-verified. Same product; **6 underlying native MainActivity/default-store warm/cold phases passed**, physical/API26/API37 actual LAN/TLS, no data ADB reverse. New processes restore original8 IDs/draft/target/pin before data (4 named503/0 forwarded reads each), independent history200 before exit; actual fork-option GET and enabled native menu, choice never clicked,0 model inputs/write attempts. Authenticated peer GET positive only in counted environments; optional public peer directory unavailable404, no unexpected selected-flow rejection. V1 missing canonical turnId/config/peer-route failures and2 API37 pre-restart descriptor timeouts retained; /host uncounted so only0 recorded authenticated data requests proven. Read permission/AppOps already allowed, no grant/data clear, exact owned AVD restarted preserving package/grant, same-budget new LAN phases passed, original cause unisolated; first launcher private-AVD-root failure also retained.15 frames reviewed: API26 cold before-data gray/letterbox unisolated; API37 intermediate recovered body still disabled composer then final menu enabled; full visual/timing not passed. Strict additive canonical turn.turnId versus Host acceptedTurnId reconciliation covers old2/new2 P2 inputs, no original rewrite/replay, old full-access failure unchanged. Exact own V1/V2 services/apps stopped, native data/original desktop/config/canary files/turns retained. Phone dual SIM absent/Tailscale absent; cellular/VPN scheduling clarification pending while local work continues. Source19/35 and full P2/defer/pause records retained.

- P2 recovery input-ready/capture checkpoint: preceding `54d6560b4d0ce163a5aefef48540f75a9235b37e` pushed/independently remote-verified. Same product, **6 new underlying native warm/cold phases passed** across physical/API26/API37. Original cold60s budget now requires actual history200 ack, online, workspace/history idle, canControl/canCompose and enabled actual send control; never clicked,0 new inputs/POST. Original target/pin/8 IDs/draft/4-before-data503/0 forwarded data and named read/menu assertions preserved. Capture now verifies2 native frame callbacks+bounded300ms accessibility idle;15 frames personally reviewed. API37 current recovered frame enabled, previous intermediate frame retained. API26 before-data capture still black top/bottom crop, original gray/letterbox retained; no stable full-window, root-cause or production-fix claim. Do not repeat the same capture experiment to close it; P2/P6 visual tracking retained. Exact own services/apps stopped/data and original canonical turns/desktop/config retained. Full19/35/source/transport/performance, human defer/pause and cellular/VPN clarification remain.

## P3 来源准备（2026-10-22，尚未开始产品实施）

- [逐项范围](P3-scope.json)与[来源映射](P3-source-review.json)保留全部 **26 个来源提交节点、9 个功能**及其 UI/场景关联；每个节点保留 SHA、原冻结补丁指纹和实施边界，树外另保存可读补丁与提取指纹。合并、重叠提交及历史交付证据均保留，不重复计为功能或 Android 实测。
- 当前 Android 已有 `sidebar-search-toggle`；冻结差异描述中的搜索显隐缺口不作为重复开发任务。已有五条列表展开与 iOS 的分区独立折叠分别核对，桌面有序投影、完整聊天搜索和管理回执仍待实施。
- O016/O021/O164 等混合来源只纳入聊天部分；项目新增/删除管理、Task Hub 继续暂缓。F02/X09 的手机独立未读账本/计数仍随 P7 暂停。历史运行态预禁用按 O150/O152 最新策略复核，不重新引入被后续提交移除的限制。
- 当前为源码范围准备，新增产品改动、模型输入、原生执行与平台通过数均为 **0**。P2 未关闭，手机蜂窝/VPN实际路径缺失及其后续阶段安排仍单列；没有把未回答的安排问题记为同意或整阶段通过。

## P3-1 元数据与分页持久（2026-10-23）

- 用户已明确选择蜂窝/VPN单列待验收并继续本机开发，相关门槛调整已写回总计划、P2/P3范围及证据；完整P2仍未关闭。未加载owner手动归档继续待验收，Task Hub／新增项目管理、A6/P7及依赖项不扩展。
- Android补齐可选归档状态、上次任务状态、待答计数与归档原因，并保留置顶ID、sidebar v1混合项和各分区精确顺序。未知sidebar版本只保留原JSON，不套用v1顺序；目录元数据不增加执行资格，归档与执行状态不互相推断。
- 分页校验准确Host与机器目标，历史账号元数据变化不改变目标身份。缺失投影沿用前页、显式空集合清空；刷新仍替换原目录。已耗尽的项目/聊天游标不会因另一种目录继续分页而复活，缓存编码同步合并后的值。
- 首轮验收器误用普通TargetScope类的copy方法，编译失败已保留并修正；首轮API26原生 **6/7**，捕获合并后游标未同步至缓存JSON的产品问题。修复后相同验收器的三环境42项分组通过；再复用既有无效响应文案、补展示断言，最终候选重新完成 **42项／6组**，三环境各7项元数据＋7项目录/输入就绪回归。中间与最终包指纹分别保存，不合计为一次无中断运行。
- [P3脱敏证据](../../acceptance/mobile-evidence/android-alignment-P3.json)记录最终源码/APK/验收器指纹。真实HTTP回环、加密库重新打开与生产Session恢复均为辅助证据，不称作物理进程终止、原Electron新模型任务或新增视觉通过；本子项新增模型输入为 **0**。完整侧栏、全文查找、管理回执和P3真实DEV继续后续子项。

## P3-2 桌面顺序与侧栏交互（2026-10-23）

- P3-1 检查点 `504b506976c99544c23e94bff08d3fe1d263121c` 已推送并独立核对，随后实施本子项。冻结的 **26 个来源节点、9 个主要功能** 全部保留；仅关闭本检查点适用的侧栏切片，O013 的 EasyWork 首次补齐旧置顶摘要仍随 P8，F02 运行数及完整手势/视觉矩阵继续后续。
- KMP统一准确Host、首个重复项、归档/缺失排除、v1混合置顶与收到的精确顺序、旧Host/未知版本回退、五条限制与搜索临时展开。Swift与Android生产路径均接入，删除相应独立排序/限制/展开规则；目录不增加读写或原owner资格。已有向量全部值与基线相等，新增10条排序和4条显隐向量。
- Android补置顶/最近独立分区折叠，兼容原每客户端的项目、项目列表及最近更多偏好；加密库重新打开与Activity重建分别验证，不把后者当作进程重启。已有项目的新聊天按钮只准备准确项目草稿，未新增项目记录或提交模型。
- API26实际发现搜索完成仍保留文本焦点/输入法：clear、force clear、下一帧及单独native hide的失败保留，改为明确转移到结果列表。新关闭场景又发现隐藏输入退出时键盘未收起，关闭后下一帧转移到主内容，并禁用隐藏/弹层覆盖的搜索字段；重开保留查询和独立聊天草稿。原5秒非焦点/IME不可见预算不放宽。禁用字段不含Focused语义时，验收器明确接受缺失或false，同时要求disabled；重开仍严格核对已启用、非焦点和IME隐藏。
- 最终同一产品/runner候选在API26、API37、新手机各完成 **3项映射/持久＋5项UI，共24项／6组**；只读HTTP回环均零POST。21张当前原图逐张查看：可见顺序、折叠、无键盘搜索/重开、项目草稿和系统/底部边界可读。部分画面保留触摸ripple、滚动位置不同，新手机保留自定义字体；不当作同数据双端像素一致或全视觉矩阵通过，旧API26首缓存捕获几何问题未因此关闭。
- `check:mobile-core` 完成后逐份核实际XML，共 **JVM 53项、iOS Simulator Arm64 53项，各11组、零失败/跳过**。原汇总只选47项，另存完整XML/摘要补上既有4项V9与新增2项Sidebar，不改写原报告；Swift/Kotlin与Android映射核对10＋4向量。iOS编译通过；首轮四UI为3通过/1个懒列表单视口计数失败，改为实际双向滚动并要求8个准确ID后单项通过。最终项目展开也接入共同规则，因此最终四项重新执行，结果按脱敏证据更新，原失败保留。
- [脱敏证据](../../acceptance/mobile-evidence/android-alignment-P3.json)按检查点保留包、源码、原始失败和分组，不把诊断/中间通过叠加为最终24项。本子项新增真实模型输入 **0**；先前13条原意图不重放。全文查找/定位、聊天具名操作/原回执、归档/恢复/批量及原Electron真实DEV尚待 P3-3–P3-6；蜂窝/VPN与手动归档待验收、A6/P7暂停和项目管理暂缓保持。
- 最终iOS四项以 **1＋1＋2三组完整结果包** 通过，7张当前示例原图已查看。原四项连跑前两项通过后，第三项连续出现XCTest动画结束通知60秒等待；只中断准确自有运行，SIGINT未结束清理后以SIGTERM退出143，原包不可完整读取，未计最终通过。相同源码/断言随后独立项目展开50秒、滑动开关24秒、客户端草稿及连续拖动两项完整通过；没有据此推断平台或产品根因，也未把中断前两项重复加总。完整 `pnpm check` **59命令一次连续通过**，iOS/Android构建、共享核心和最终文档一致性分别保留结果，macOS下Windows原生跳过不改记通过。

## P3-3 完整聊天查找与准确定位（2026-10-23）

- 前一检查点 `b440392158e25981b5d9547616b0cef41ce11569` 已推送并独立核对。保留全部26个来源节点、9个主要功能；本项覆盖O017查找部分、O018统一sheet路由与迟到隔离，O017聊天管理仍在P3-4/5，其他相邻功能影响写入产品清单。
- 双端生产接入KMP查询规范化、可搜索文档、UTF16范围校验、上下文片段、长文阈值、分页去重/循环拒绝、查询/数据版本隔离和准确投影定位；删除Swift原独立规则。Foundation/ICU仅提供Unicode匹配/字符边界，Android对ICU候选复核大小写/变音折叠以保留宽度及标点差异；17条新向量覆盖中文、emoji、组合字、ß/连字展开及部分展开拒绝、空白和长上下文，原向量值全部保留。
- Android按准确Host/聊天/机器目标及导航/连接版本独立读完整分页，使用具名只读search-history入口；不附最新窗口参数，不在选择前替换当前历史，不采用搜索响应的执行权限。查询/数据版本变化时旧结果不显示或点击；失败保留缓存搜索并可重试。选择后先关闭原生sheet再显露准确工具/错误/压缩消息、高亮并滚动，长消息保留可选完整原文。完整历史可在首个最新窗口仍挂起时安全落盘，晚到窗口保留已定位前缀；草稿独立不变。
- 最终同一产品/runner在API26、API37、新手机各完成2项Unicode/字段＋6项真实HTTP Session＋5项原生UI，**39项／9组辅助通过**；共享核心实际XML为JVM与iOS Simulator Arm64各**58项／12组**，Swift原生与Android分别核17条向量。iOS自有模拟器完成1个完整分页查找XCTest场景，包括工具/附件/压缩定位、无结果/纯空白查询、503缓存/重试和迟到切换；全程零真实模型输入。
- 原始失败全部保留：ICU宽度误匹配1/2、遗漏具名URL白名单导致Session5/6失败、返回场景未建立初始底部导致UI4/5。真机Espresso系统Back因INJECT_EVENTS拒绝，原4/5单列待验收，不提权或绕过；两模拟器原系统Back同生产包通过。最终矩阵另验应用自己Dialog的返回回调，不能替代真机实际系统按键。iOS初轮Menu缺accessibility ID，改用实际可见且hittable的准确本地化标签；v2整场景通过后补纯空白查询断言，v3当前源码整场景再次通过，不重复累计。
- 30张安卓与6张当前iOS合成原图逐张查看，片段/工具/Diff/附件/长文展开、缓存提示和独立草稿可见；原生标题/弹层/键盘样式、新手机自定义字体、触摸ripple及滚动截断分别记录，不记同数据双端全视觉通过。API26旧缓存几何问题未关闭。原Electron真实DEV、完整手势/视觉及运行数仍随P3-6，蜂窝/VPN、手动归档待验收，A6/P7暂停及项目管理暂缓保持。累计13条既有真实输入不重放。
- 源码、同包矩阵、原失败和截图指纹见[P3脱敏证据](../../acceptance/mobile-evidence/android-alignment-P3.json)；完成当前构建/工作区检查、提交推送并独立读回之后，才进入P3-4。

- 当前Android同包构建、`pnpm build:ios`、共享核心及完整 `pnpm check` **59条命令一次连续通过**；macOS下Windows原生跳过保持未验收，不改记平台通过。最终文档/源码指纹复核后提交并正常推送专用分支，精确SHA按下检查点回填。

## P3-4 聊天管理检查点（2026-10-23）

在 `8d759ce` 的完整查找检查点之后，已实现具名置顶/取消置顶、归档/恢复、删除与子对话警告，原操作按完整机器/聊天目标持久保存，与草稿独立。公共 KMP 管理可用性、任务状态及终态/回执匹配接入双端，Swift 旧规则生产路径移除；归档不制造停止，未知/查询404只查询原ID，结束等待不取消或重放。

目录竞态以旧产品反例复现后修正，确认后新目录不被旧读取占位或覆盖。iOS 手动刷新丢失也经旧实现失败/修复后原生通过；双端晚到管理/查询错误增加原导航/连接约束，Android覆盖A→B→A返回。最终相同APK/runner三环境90项/18组辅助通过，每环境30项；核心JVM/iOSSimulatorArm64各63实际方法/13套件及24新Swift管理向量通过，iOS当前源码刷新/目录/8状态菜单/查找4场景通过，双端构建和完整`pnpm check`59命令通过。原编译、验收器、物理取样、目录反例与源码指纹作废记录均保留。

视觉查看了较早候选Android42原图、最终候选9代表原图与当前iOS30原图；完整相同数据视觉一致性仍未关闭。菜单顺序/图标与多条原操作记录的动作/目标辨识明确列入P3-6，不以功能辅助通过覆盖这些差异。

新增真实模型输入0，既有13次不重放。本检查点登记、提交专用分支后，依序进入P3-5归档只读/列表/批量、P3-6全矩阵，再P4。蜂窝/VPN、人工归档和物理系统返回单列待验收；任务/项目管理与A6/P7暂缓不变。

P3-4 提交登记：`79c5afacdbcd9327640c3df8bcb55480502632e0` 已推送安卓专用分支并独立核对远端；主线未合并。继续 P3-5a 归档列表／只读历史入口，再进入 P3-5b 批量归档。

### 2026-10-23 P3-5a：归档列表与只读历史子检查点

2026-10-23 P3-5a：Android 对话菜单加入已归档对话入口、原目标只读打开、生命周期与最后任务状态、空态、刷新/分页及失败保留/重试；恢复仍复用具名聊天操作并保留独立草稿。列表读取不依赖管理 capability，不新发模型输入。iOS 本子项源码未改；Android 显式分页/离线刷新入口及原生页面样式差异登记至 P3-6，未宣称双端完整视觉或真实 DEV。批量归档仍留 P3-5b，项目管理/任务中心暂缓，A6/P7 暂停。

实现及原始结果参见脱敏P3证据的`archiveListCheckpoint`；最终同一产品/runner在新手机、API26、API37分别完成归档UI5＋原管理UI6＋原管理Session6＋完整查找定位1，合计54项/12组辅助通过；最终九张代表原图和中间十五张原图分别复核，未冒充双端完整视觉/真实DEV。最终稳定源码的完整 `pnpm check` 连续59条根命令通过，原生构建通过；文档最终格式/一致性检查另核。本子项新增真实模型输入0，累计13条既有输入不重放。完成本子检查点后先提交专用分支，再继续P3-5b。

P3-5a 补充只读边界：合成原生反例分别复现归档且运行时仍可控时的设置/停止门禁差异。Android按iOS既有门禁约束执行、队列取消、审批/问题及设置；保留真实任务运行状态和独立恢复操作。原API26合成HTTP服务关闭竞争另作验收工具失败登记，未当成产品失败或通过。最终产物与分组结果另列，P3-5b及P3-6全矩阵仍开。

### 2026-10-23 P3-5b：聊天批量归档

P3-5a 已提交并推送 `449bbe25715ae78bb8aa223926f38e202d8d1c0d`，远端分支/主线及恢复包已独立核验。

2026-10-23 P3-5b：Android新增仅聊天的批量归档页面：按当前客户端/项目及聊天搜索、选择/取消全选、最多100条、确认/取消、加载更多和逐项成功/拒绝/待确认及原ID查询。根目标加密journal在具名POST前保存不可变原意图/标签和提交标记，未知及404不重放；逐项终态保留，部分进度不覆盖其他项目，切换/重启保留草稿与原回执。KMP统一精确请求/目标/有序ID、逐项终态合并和限定选择，Swift归档路径迁移至共享规则；管理提交后的目标复核和旧目录owner隔离同步。项目删除/新项目管理及Task Hub保持暂缓，A6/P7继续暂停。

当前共享核心JVM与iOS Simulator Arm64各67实际方法/14套件通过，Swift实际12条回执向量；最终相同Android产品/runner在新手机、API26、API37各30方法/7组（批量flow5＋session4＋UI3，归档UI5、原管理UI6＋session6、完整查找定位1），合计90方法/21组通过；iOS归档专用合成XCTest1场景通过（36.584秒），双端构建通过。12张代表Android原图和4张iOS原图逐张复核，入口/样式/记录折叠差异保留P3-6。最终源码冻结期间完整 `pnpm check` 连续59条根命令通过；前两次在manifest字段及schema关键字守卫处停止的记录分别保留，未计为整套通过。新增真实模型输入0，既有13条输入不重放。完成本检查点先提交专用分支，再进入P3-6及P4；本任务隔离树仍有具体后续源码/构建用途。

### 2026-10-23 P3-6a：菜单和原操作记录辨识

P3-5b 已提交推送 `eb061a6a823e1de7fbc78d9cd17ea7b51857c89c`，远端SHA、未合并主线及树外恢复包核验完成。

2026-10-23 P3-6a 菜单与原记录辨识：Android按iOS顺序显示置顶、聊天查找、归档/恢复、删除，再归档列表和待核对记录；使用既有Canvas向量系统补pin/unpin/archive/trash图标，保留禁用/删除颜色、准确目标和警告。双端每条待核对聊天操作显示动作、原ID片段和准确聊天标题/无目录时原threadId，查询及结束等待保持原完整ID；Android语义说明含完整对话/操作ID，iOS保留原稳定标识并补标签/值。没有提交/恢复协议、模型输入、未读账本或任务/项目管理改动。

最终同一Android产品/runner在新手机、API26、API37各15方法/4组，合计45方法/12组辅助通过；iOS当前源码2场景通过（原记录查询及8状态生命周期），双端构建与稳定9份源码期间完整`pnpm check`连续59根命令通过。9张Android、8张iOS代表原图逐张查看，未记全视觉通过。物理长菜单坐标注入明确被系统拒绝，不提权或改用全局注入；真机原查询使用应用自己的语义动作，仅作辅助证明，完整触控门槛仍开。背景原回执轮询使早期any/last-GET断言不能隔离点击，最终先仅结束本地等待、待实际GET流静止后严格核对唯一新增原ID查询和零POST重放；其余原始失败均保留。源码及逐组结果绑定P3证据的menuParityCheckpoint。尚待运行数/整体手势与视觉/原Electron真实DEV，随后继续P4；蜂窝/VPN和人工归档不阻塞，A6/P7与任务/项目管理暂停范围不变。

### 2026-10-23 P3-6b：客户端进行中数量

P3-6a `2956eb29596db7dd489e6f159357f0bf4a08fbce` 已推送并独立核对远端，主线未合并；树外补丁、依赖上一检查点的恢复包和工作树登记已完成。

2026-10-23 P3-6b：Android客户端列表新增进行中数量与未知占位及语义说明；KMP统一在线/快照/history-only资格、准确Host、运行中及等待审批的ID去重，归档生命周期独立。Swift原独立计数路径迁移至共享规则，保留demo合并来源、未来模式与归档仍运行的现有行为。未读账本/数量与A6/P7仍暂停；任务/项目管理暂缓。

共享核心JVM/iOS Simulator Arm64各68实际方法/14套件通过，Swift核11条新计数向量；最终同一Android产品/runner三环境各数量3＋侧栏5，合计24方法/6组通过，iOS当前源码数量1场景通过。双端构建、稳定12份源码期间完整`pnpm check`连续59根命令通过；原包装把打印的13条shell头误作根命令数，另存实际package链59条的纠正摘要，不改原报告/退出码。9张Android、3张iOS代表原图逐张复核，完整视觉未关闭。原生v1各2/3失败来自片段/整串断言，实际行已正确显示未知；验收器修正后重新同包通过，生产计数未因此改变。原始失败与源码绑定P3证据runningCountCheckpoint，未将未读/完整P3或真实DEV记为通过。

### 2026-10-23 P3-6c：单条与批量原操作互斥

P3-6b `d8db39c9711b610f3178460f1674e0c8b537cea8` 已推送并独立核对，主线未合并，树外恢复包及工作树登记完成。

2026-10-23 P3-6c：共享准确目录目标/归档/单条发送中及待核对/批量待核对的归档选择资格。iOS补齐单条与批量原操作互斥，并在持久化后发送前复核原目标、当前目录和冲突；Android保留既有互斥并迁移选择规则到KMP。查询保留原ID，其他聊天和独立草稿不受未知结果影响；项目管理与未读暂停范围不变。

iOS旧产品原生反例已复现同聊天批量行仍启用，在首个失败处停止，未发送批量POST；验收器编译错误分别留证。Android原产品先通过15项/3组批量Session边界，最终共享迁移后的同一产品/runner三环境57方法/12组通过（每环境batchFlow5＋batchSession5＋batchUI3＋singleUI6），共享核心JVM/iOS Simulator Arm64各69实际方法/14套件与Swift7条新资格向量通过。当前iOS原生互斥1场景通过，真实合成HTTP严格1single＋1batch原ID及1GET、零额外POST；编译/确认定位失败分别保留。iOS产品build在greenv2通过，greenv3仅验收器firstMatch修正按相同产品源码复用，当前原生完整重验通过。稳定11份源码期间完整`pnpm check`连续59根命令通过；最终源码与逐组结果见P3证据managementConflictCheckpoint。3张Android、3张iOS代表原图逐张查看；iOS未知批量行虽禁用但旧selected1/底部提交仍蓝，发送前门禁拒绝新意图，明确列入下一P3-6d界面修复，与超过10条旧原记录可达一起验收。未关闭完整P3/真实DEV。本项新增真实模型输入0，既有13次不重放。

### 2026-10-23 P3-6d：失效选择与全部原记录界面

P3-6c `a95aae08c59ed6eb113cf592b7fa46b6098afbdf` 已推送并独立核对，主线未合并，树外恢复包及工作树登记完成。

2026-10-23 P3-6d：iOS归档选择按全部仍合资格的原ID清理，搜索隐藏但仍有效的选择保留；底部提交即时核对每个所选ID，不等待清理effect。批量原记录取消固定10条截断，显示完整不可变ID并保留每条原查询。Android生产路径已有相同行为，本项仅新增原生验收，最终产品APK字节与a95检查点完全相同。项目动作、任务中心及未读暂停范围不变。

Android当前同产品/新runner三环境30方法/6组辅助通过，每环境batchUI5＋batchSession5，6张代表原图逐张复核。iOS当前源码2场景通过：11个不同批量原ID中最早未确认记录在后续10条终态之后仍可滚动查到，2次GET只访问最早原ID、零额外POST；独立互斥场景核选择清0/提交灰化、原查询可用及同聊天单条禁写。原生搜索先用中间光标退格留下旧尾部，实际值/层级定位后改用已观察到的本机“清除文本”控件，保留所有原失败；green-v1产品build通过，当前green-v3仅验收器修正按相同产品源码复用，两个完整原生场景重验通过。6张Android和4张iOS代表原图逐张复核；稳定4份源码期间完整`pnpm check`连续59根命令通过。源码与结果绑定P3证据batchRecordUiCheckpoint。此前两次旧版长列表验收器分别在第二条搜索定位/活动搜索隐藏导航栏处停止，均只提交第一条合成批量，不当作10条截断原生反例；截断由原源码确认，最终11条原记录当前场景必须单列通过。本项新增真实模型输入0；完整P3/G4、视觉/真实DEV继续开放。

## P4 输入、队列、生成与交互控制（2026-10-23）

P3-6d `c538c698d6b5894ff8debf3d7f290134229f750c` 已推送并独立核对远端，主线未合并，树外恢复包/台账完成；完整P3手势、全视觉和真实DEV仍开放。P4冻结 **27个来源节点、20个功能**，逐条原提交补丁与冻结SHA相符，见[P4范围](P4-scope.json)与[逐节点复核](P4-source-review.json)。没有删除任何原功能或界面状态。

先核F25本地读取失败保护这一前置，再原客户端队列字段/归属、立即执行/取消与同步错误，之后输入/设置、计划/问题及当前手机/原桌面真实模型闭环。iOS有明确localRecoveryRequired及写授权；Android失败恢复目前仅报错，需要独立加密存储原生反例后再修。队列5字段/执行接口差异已只读确认，尚未开发或验收。蜂窝/VPN与人工归档单列待验收；任务中心/新增项目管理及A6/P7/P9暂停范围保持。本阶段新增真实模型输入0，既有13次不重放。

### P4-1 本地恢复保护

2026-10-23 P4-1：Android本地初始读取/恢复先锁定同目录全部写入和删除；完整解码普通元数据与私有连接合并后才发布状态并解锁。失败保持加密原件，后续外观/草稿变化不覆盖；跨实例及旧异步保存代次受同一保护。恢复前不授予编辑/发送/管理能力，显示存储警告、原数据保留说明和重试，重复重试合并；成功后恢复原目标、草稿、设置与正常保存。iOS既有恢复保护未改，Android不提示用户反复解锁。没有自动清空、迁移原输入、重放或取消远端。

旧产品三环境真实加密夹具均复现58字节原件被327字节空状态覆盖；只操作独立测试命名空间，未触碰用户资料。最终同一产品/runner在API26、API37和新手机各47方法/3组，共141方法/9组辅助通过；稳定8份源码，逐组结果与源码见[P4证据](../../acceptance/mobile-evidence/android-alignment-P4.json)。完整P4、P3真实DEV/全手势/全视觉及既有人类暂缓项仍开放。

本检查点完整`pnpm check`59条根命令在稳定7份源码时通过（354.45秒）；随后仅2个原生验收器的落盘屏障名改为每次唯一，最终产品APK字节不变，当前原生矩阵及manifest检查另验。最终界面与同APK先前6张代表原图按匹配指纹复用，未声称全视觉通过。JUnit返回类型/编译属性/缺少TLS夹具参数、旧目录门控以及API26旧同名屏障失败均保留；未放宽草稿/原文件/单次提交断言。零新增真实模型输入，iOS/KMP本项未改。先提交推送/登记本地恢复子检查点，再进入P4-2；完整20功能和27来源节点未关闭。

P4-1 `7b27217aa941cc5e11adcbfbca66222c3a46788b` 已推送并独立核对远端，恢复包及台账检查完成，主线未合并。

### P4-2a 队列元数据和同步提示

2026-10-23 P4-2a：原生回执补齐五个可选队列字段，HTTP、加密历史、原记录编码和EasyWork归一化完整保留。Schema只为已存在字段补充nullable严格类型，保留未来字段兼容。KMP统一原客户端队列归属、存在性及同步错误判断，Host/legacy-review与已移除项不列活动队列，不建立本地FIFO、不迁移或重发。已接受后的同步错误不被后续成功或普通失败关闭抹掉；源端清除才消失，独立草稿保留。iOS改调同一规则并限定准确host/thread；立即执行/取消门禁和行样式仍留P4-2b。

三环境原产品的真实HTTP/加密重开/回执转换反例各1失败。当前同一Android产品与runner各18方法/3组，共54方法/9组辅助通过；共享核心JVM与iOS Simulator各70方法/14套件，Swift8归属、7提示与83HTTP向量通过。双端构建通过；iOS首轮连接夹具遗漏完整AgentConnectionState，原准备失败保留，当前仅修复夹具后重验。零新增真实模型输入；完整EWDAP、P4-2b及真实DEV未关闭。

当前iOS原生1完整合成场景通过，服务实际17GET/0写命令；完整连接状态夹具修正后，旧Host/已移除队列隐藏、已接受/已关闭失败的同步错误保留、源端清除及独立草稿均核对。6张Android与2张iOS原图已逐张查看；Android大卡片与iOS紧凑行、提示位置/高度等差异保留P4-2b/P4-4，不宣称完整UI一致。真实DEV与完整EWDAP未关闭。

全量检查首轮发现已无调用的iOS DTO计算属性仍依赖KMP，独立Swift解码夹具无法编译；删除这个死入口，保留SessionStore调用共享QueueRules的唯一生产规则。当前build-v3与native-v3重验通过，实际17GET/0写命令；Android与共享核心源码未变，不混报成重跑。所有准备/编译失败均留证。当前6张Android与2张iOS原图已查看，完整布局差异保持开放。

最终稳定18份源码期间完整`pnpm check`连续59条根命令通过，370.71秒。P4-2a仅关闭字段/归属/同步提示辅助子边界，先提交推送/登记，再继续P4-2b；原队列大卡片/紧凑行、提示位置、立即执行与取消的完整门禁及真实DEV仍在清单中，所有27来源和20功能保持。零新增真实模型。

P4-2a `ff1ad1aac3045ad62bfc5af74de493c9ccf7e6bf` 已推送并核对远端。第一次普通推送被远端鉴权拒绝，现有配置普通重试成功，原失败留证；恢复包与台账检查完成，主线未合并。

### P4-2a.1 发送与原查询的Provider路径补齐

2026-10-23 P4-2a.1：继续逐路径复核后补齐EasyWork发送返回、command/status与queue/status转换；此前P4-2a封闭的是历史/加密重开/回执编解码及归属提示子边界。原状态和原错误优先于外层RPC完成；runId优先turnId，只有发送可用原expectedTurn回退，原查询不借当前Run。已接受但仍带queueSource的结果也只回查原queue/status；原ID/Host/thread保持捕获范围。五队列字段、noticeContext及laterTurnId完整保留，已知字段错型拒绝。会话解析改用窄EasyWorkChannels接口，默认真实会话/凭据/传输不变；实际原生适配器与加密会话经独立Peer夹具验证，没有新增执行控件、HostFIFO、重放或真实模型。

保留旧转换逻辑、仅引入窄会话接口后，实际原生适配器和加密会话三环境各1项复现发送丢失queueSource，原反例完整保留，不冒充ff1原APK逐字节复验。当前同一产品/runner三环境各23方法/3组，共69方法/9组通过（Provider4、元数据UI1、接收/元数据/本地恢复回归18）。23条同份版本化向量在Android生产转换器与实际Swift生产解码器通过，Swift原26检查保留共49；核心两运行时各70方法/14套件、Swift86HTTP向量与当前iOS构建通过。Peer在进程内提供独立加密协议，不是实际网络/原桌面/模型证明；iOS生产映射与UI源码未改，未新增iOS真机/真实DEV结果。零新增模型输入。

完整`pnpm check`连续59条根命令通过，351.7秒，12份源码指纹稳定；最终格式和一致性另核。此检查不把macOS下Windows原生跳过改记通过。当前仅关闭Provider回执路径辅助子边界，后续具名执行/取消、回调目标与并发门禁、紧凑UI及真实DEV继续开放。先提交推送和登记，再改后续生产路径。

P4-2a.1 `9894eaa85214988d23171c2671b36e4c4d79982e` 已推送并独立核对远端，恢复包和台账check通过。主线未合并，隔离工作树为后续源码/构建及原生验收保留，按已登记释放条件复核。

### P4-2b.1 具名原客户端队列命令

2026-10-23 P4-2b.1：Android新增具名executeQueue，经AgentRegistry分别调用固定HTTP v1/queue/execute或EasyWork queue/execute；保留原消息ID，当前任务只映射expectedTurnId/expectedRunId，空闲时省略，不发正文/设置/附件或自建FIFO。HTTP完整运行目标仍绑定URL查询，严格请求Schema拒绝正文target、重发正文与null/空白任务ID。EasyWork只把submitted/completed视为执行确认，取消只接受真实Boolean true。队列执行加入内部写白名单，沿用未知写先查原command/status并核对操作ID/方法/目标/指纹、绝不重放。会话/UI尚未接入立即执行按钮，原取消门禁与大卡片差异保留P4-2b.2；iOS既有具名调用不改，共享HTTP契约新增跨语言向量，独立验收。

同一产品APK和runner在手机/API26/API37各39方法、两组，共117方法/6组通过，含具名队列命令7、Provider/接收/原HTTP边界回归32。实际HTTP查询目标、原ID与可选Turn、错误/断连单次写、加密会话的原方法/参数、非确认状态及取消错型、未知写原指纹查询不重放分别核对；零真实模型输入。共享核心JVM/iOSSimulator各70方法/14套件，Swift94HTTP向量通过。本检查点无旧产品原生反例重跑、不新增UI/真机iOS/原桌面DEV通过；初次验收器误用TargetScope.copy的编译失败保留，修正为准确构造后同包矩阵通过。

iOS当前构建通过，完整`pnpm check`连续59条根命令通过，364.58秒，8份源码指纹稳定；Windows原生跳过不记通过。P4-2b.1仅关闭具名命令辅助边界，完整队列功能仍开放；先提交推送/台账登记，再接会话与UI。蜂窝/VPN、人工归档、完整P3/P4、真实DEV与A6/P7暂停项保留。

P4-2b.1 `845ec7dc894c19daa6dddf077cc14a4e672926e9` 已推送并独立核对远端，恢复包和工作树台账check通过。主线未合并。

### P4-2b.2 原队列动作门禁与紧凑行

2026-10-23 P4-2b.2：KMP统一原客户端队列动作的归属/存在性、在线可写、逐项等待及执行capability/canExecuteNow判断；双端只对捕获完整目标与当前原队列成员操作，执行再核对捕获Turn/Run。等待按完整目标/原消息/owner隔离，不锁整份草稿；导航/连接代次、端点和错误代次隔离晚到结果及错误，未知只刷新原历史不重放。共享许可确认不自动续发。Android改为原顺序紧凑行、编号、两行摘要/附件指令、44dp动作、立即执行/追加、逐项等待和180dp有界滚动；使用项目表面色。iOS原生场景暴露旧HTTP队列调用缺少完整target查询，已由捕获readingClient与具名队列query补齐；HostService只增加显式可选query，其他调用不变。提示位置留P4-4，队列外容器、主题/键盘及完整视觉/真实DEV仍开放。

共享核心JVM/iOSSimulator各71方法/14套件及Swift16条队列动作、94HTTP向量通过；后续Android颜色/夹具及Swift HTTP目标修复不改共享源码，按组件匹配复用。本检查点包含明确原生失败与中间候选，不新增真实模型输入。Android完整视觉和iOS晚到竞态/真机仍独立留门禁。

最终v5同一产品/runner三环境96方法/6组通过（各6队列交互+26命令/Provider/元数据/恢复/Composer回归），零真实模型输入。9张当前队列原图逐张查看；项目浅灰表面色已核对，API26空闲截图仍有黑顶/导航重叠变化，后续帧不同，原P2/P6完整窗口/视觉门禁继续，不以重复截图实验或当前断言冒充根因修复。iOS原生v1暴露完整target缺失；修复后v2实际前两项命令和等待通过，但点击居中光标后的追加位置不符精确草稿期望，验收器改为先原生点击文本末端，原精确期望不变，v3重验。失败完整保留。

完整`pnpm check`连续59条根命令通过，361.22秒，16份源码指纹稳定。此后仅iOS验收器将按钮被AX标签遮蔽的内部静态文字查询，改为核对实际运行反馈；仍保留精确草稿、原生截图和expectedTurn请求断言。15份产品/工具源码不改，当前v4原生及最终文档一致性另验，不混报第二次全量检查。v3精确编辑草稿及前两次目标队列请求已走通，但最终验收查询失败，完整失败包保留。O092/O093的输入栏参数节点留在P4-3，未以附件摘要行代替其整体验收。

iOS当前native-v4一完整场景通过，实际28请求/25GET/3原队列POST：空闲原消息执行、另一个原消息取消、附件原消息绑定queue-original-run追加；每次完整target查询由独立夹具严格等值核对，许可确认零自动执行，逐项等待及精确编辑草稿保留。3张当前iOS原图逐张查看，追加文字可见；Android当前9张原图和完整视觉限制另列。16最终源码指纹核对，零真实模型输入。当前仅关闭P4-2b.2辅助子边界，整P3/P4及外部/视觉门禁不关闭；提交推送和台账登记后继续P4-3/P4-4。

P4-2b.2 `f4e7f4d79502e979854ca03f49f6de41301902b7` 已推送并独立核对远端，恢复包与台账check通过；主线未合并，准确隔离源码/构建继续为P4-3/P4-4保留。

### P4-3a 原消息独立回执与本次等待

2026-10-23 P4-3a：共享ReplyRecoveryRules区分原客户端接受、等待释放与终态保护；received/未知/旧Host或已移除queued不作为接受证据。Android准确核对原回执Host/thread/ID/完整目标后，只释放同一原操作当前持有的等待，不清新草稿、不释放新操作；原HTTP未返回也可由已接受/终态回执解除本次等待。received保留原草稿，显式结束等待保留原意图；失败回执保留正文。Native加密journal及iOS分离记录共同保护终态不被晚到非终态确认/超时回退，iOS删除对应旧字面规则，改调同一KMP判断。原生夹具的v2 accepted检查补齐既有canonical可空字段；新增可选树外输出目录。404重试/有界退避、整组输入记录UI、生成设置/真实权限/冷恢复/真DEV仍开放。

同一已提交旧产品APK（字节一致）搭配新验收器，手机/API26/API37各3项均失败：received清正文、accepted不解锁、failed不解锁。首轮修复v1在API37又实际复现晚到submitted覆盖失败并清正文；API26/33的时序通过不计最终产品通过。补充KMP终态规则、Native journal及iOS现有分离记录共用判断；同一三个核心用例当前v2三环境9项通过，后续回归独立运行。共享核心两运行时各72方法/14套件，Swift15等待/8终态向量通过。iOS v1实际独立读请求已运行，但旧夹具缺少4个既有v2可空字段，被严格canonical校验拒绝；正确补齐夹具，保留原12秒与原断言重验，不改产品校验。零新真实模型输入。

中间v2同产品/runner三环境159方法/6组及iOS独立回执/停滞历史完整场景通过；进一步扩展journal实际加密重开断言后，相同v2产品APK在三环境各1项实际失败：submitted被晚到received覆盖。已将保护扩展到原客户端已接受queued/submitted及非终态超时回退，保持不可变正文/指纹；iOS同样调用公共证据合并规则。此前通过留作中间候选，最终v3重新构建和验收，15等待/12合并向量。

完整`pnpm check`连续59根命令通过，372.29秒，11份源码稳定。iOS当前native-v3完整场景通过：3次合成发送、10次primary receipt与4次canonical status GET，历史故意挂起期间7次独立查询；第一原响应晚到不覆盖第二原输入/新草稿，第三未知显式结束等待及重启保留，当前原图1张已查看。不是原桌面/模型/iPhone真机证明。全量检查后新增本地phase反例在三环境各1项失败：received+本地HTTP未知被本轮错误状态相等要求误报目标不匹配。只移除此错误等式并补原生第四场景，完整目标/原ID校验保留；9份共享/iOS/JS源码不变，最终v4原生162及独立contract/parity/format重核，按组件复用既有72/core与iOS-v3及59检查，不混报第二次全量。

最终v4同一App/runner三环境162方法/8组通过：手机/API26各4核心+50回归，API37为4核心+31边界+6队列UI+13执行/元数据UI。API37整50组触及180秒编排总限，保留未完成回执及准确自有TestRunner部分日志，不累计其部分结果；同一验收器/断言/单例时限分31+6+13重验通过，未隔离超时根因，不宣称性能通过。最终`pnpm build:ios`全架构成功；Android最后两文件修正后agent-contract/parity/format/diff检查通过，其他9份共享/iOS/JS输入按匹配指纹复用72/core、iOS-v3与59全量结果。当前11最终源码核对，零新真实模型输入。仅关闭P4-3a辅助子边界；404/退避/旧未知后fallback、整组输入记录UI、设置/权限/冷恢复及真实DEV继续逐项，所有暂缓/暂停条件保留。

P4-3a `9149e809ea5e16e19c3494f654bff0e2c464da16` 已推送并独立核对远端，恢复包与工作树台账check通过，主线未合并。

### P4-3b 原回执404观察、共享退避与只读备用证据

2026-10-23 P4-3b/O048：Android原回复观察独立于历史与事件，暂缺404继续只读查询；共享KMP定义2/4/8/16/30秒上限退避，iOS删除对应字面计算并调用同一规则。原canonical accepted证据允许在先前uncertain回执或重试额度已消耗时读取，仍核对捕获目标/信任/原ID；负证据取得重试权限的原限制不变，既有回执或已消耗额度不获得再次写权限。没有自动发送、重放或更换原输入。分离记录的canonical回查、整组输入记录UI、生成设置/真实权限、冷恢复及完整真实DEV继续开放。

旧9149产品APK字节匹配，新验收器在手机/API26/API37每环境两项失败及一项负权限对照通过，共6失败/3通过；不将后加的额度已消耗场景伪称旧产品反例。当前同一App/runner三环境各68方法、4组，共204方法/12组辅助通过；4项新增含历史HTTP故意挂起、事件响应暂停而原POST仍在途，首次404后原ID继续查询且只发生一次POST；其余逐项核原意图SHA/原ID、GET及权限未增加。canonical夹具只有验收传输改为loopback，域捕获信任仍检查，不是实际TLS/原桌面证据。额度已消耗仅构造本地耐久元数据，没有真实重试或模型执行。

共享核心JVM/iOSSimulator各73方法/14套件，Swift10退避/15等待/12证据合并及94HTTP向量通过；本次源10文件冻结核验。iOS原生与全量检查结果补记如下；整27节点/20功能保持开放，零新增真实模型输入。

Android同一App/runner三环境204方法/12组通过（每环境4新增＋41边界＋6队列UI＋17执行UI）；旧9149同字节产品6失败/3负权限对照通过。共享核心两运行时各73方法/14套件，Swift10退避/15等待/12合并/94HTTP向量通过。iOS原生当前一完整停滞历史场景通过，3合成发送/10原回执GET/5canonicalGET，历史挂起期间6次独立查询；当前原图1张核对及全架构build通过。完整pnpm check59根命令通过，366.98秒，10份源码稳定；零新增真实模型，整功能/真实DEV未关闭。

本检查点没有放宽负证据、重试次数、捕获目标/证书/原ID或不可变正文要求。既有API37整组编排超时、API26视觉变化、系统输入限制及外部待验项保留，不宣称本次解决。先完成本检查点提交/远端核对/恢复包及台账登记，再继续发送记录入口与分离原记录查询，后续输入栏参数、设置/权限、冷恢复按原节点逐项验收。

P4-3b `10a9ebcc738b1d36ff04947a5bf0c1c1591e122c` 已提交推送，远端SHA独立一致，恢复包及台账check通过，主线未合并。

### P4-3c 分离原回复的只读备用查询

2026-10-23 P4-3c/O036/O048：Android把原回复只读观察的捕获目标/证书/不可变意图校验与重试写权限分开；结束本机等待后，仍可具名查询原canonical accepted回执并保留detached状态。负证据与原内容恢复/重试继续使用未分离的原写授权校验，分离、额度已消耗或已有回执均不能新增重试权限。目标、证书或原意图改变仍拒绝备用查询，不迁移原记录、不恢复等待、不重放。iOS既有分离记录查询与KMP规则未改，源码指纹匹配复用前项辅助结果。发送记录列表入口与正文/附件展示仍待下一检查点，完整真实DEV未关闭。

只读原生分组复验结果补记如下；已提交旧产品同字节APK三环境各2项失败，共6失败。当前同产品/runner三环境各16恢复断言原生通过，但首轮汇总器错用旧4项数量，保留失败汇总；修正数量后分组重验，不更改断言/产品。零新增真实模型输入。

最终同一App/runner三环境213方法/13完整分组通过：手机/API26各16恢复+31边界+6队列UI+18执行UI；API37为16+31+6+1独立404+17执行UI。API37组合18触及180秒编排上限，完整失败/自有TestRunner日志保留，部分结果不累计；同断言/单例时限/产物分1+17通过，未隔离组合超时根因，不宣称性能通过。零真实模型输入。当前agent-contract/parity/format/diff通过；P4-3b共享核心73/14各运行时、iOS原生/全架构build与59根检查仅按未改组件源码指纹复用，没有本项重跑之说。

本项仅3份Android源码变化，完整源码指纹和每个原生分组结果均树外保存。先提交、推送、远端核对及台账登记，再继续发送记录界面及后续输入栏/设置/权限检查；所有原27节点、20功能和暂缓范围保留。

P4-3c `ed62ba1683bdc0c33128006830060b66a073c93f` 已推送，远端SHA独立一致，树外恢复包和工作树台账check通过，主线未合并。

### P4-4a（P4-3续接）原发送记录界面

2026-10-23 P4-4a（P4-3续接）：Android对话菜单新增与iOS对应的“发送记录”，按完整目标显示全部已结束等待的原回复，倒序可滚动且不截断。展示可选取的原文、原附件名/类型、原回执状态及错误；旧附件元数据缺失明确说明原记录保留。仅查询准确原ID，逐记录等待/错误与去重，不锁当前草稿、不提供重试/重发。导航、连接、端点、证书及owner隔离晚到错误；目标改变关闭旧列表。只读快照核对加密原记录、来源目标/草稿版本及规范化原正文/设置，不读取或上传附件字节、不为旧记录补造重试授权。原内容编辑复用快照校验，仍先取得强未接受及未分离授权并复核原字节。iOS现有发送记录列表/查询路径保持，KMP规则未变；全视觉/多语言/真实桌面模型DEV及其余P4继续开放。

当前同产品新UI辅助三环境各3场景通过；新增快照拒绝反例及完整原生分组复验中，iOS和共享核心按未改组件匹配复用。零新增真实模型。 3个原生UI场景分别核原文/附件与逐项查询并发、记录内错误及跨目标晚到错误、13条原记录后最早一条可滚动查询且无额外POST。初轮9项通过后，仅增加真实加密快照拒绝原正文/目标被改写的边界验收，产品APK字节完全相同；最终同一App/新runner重验全部分组。

完整`pnpm check`连续59根命令通过，353.39秒，8份源码稳定。之后逐条UI对照定位失败标题需区分原Turn；相同v4产品和新增状态验收三环境各1项失败，保留原回执。补齐原回执七种状态的原生对照：有关联Turn的失败显示“任务执行失败”，无Turn显示“消息提交失败”；状态取准确原回执，不借当前任务。原生加密快照同时证明强未接受时原内容编辑可恢复、原附件字节变化拒绝编辑，分离记录仍可只读展示原附件名称且不授予写权限。 因此只改1份Android原生状态展示及扩展2份原生验收器，其他5份当前生产/界面源码不改，iOS/KMP/JS也不改。最终v5同产物完整原生分组重验及当前contract/parity/format/diff另核，既有59仅按未变组件匹配复用，不混报第二次全量。

七状态验收器v5错误要求同一原ID从cancelled再变interrupted/completed，被既有终态保护正确拒绝，三环境各1项超时；其余3UI场景当次通过但不混报整组。仅修正为7个不同原输入分别查询7状态/Turn，保持所有原ID、精确标题、GET次数、禁止重发和终态保护断言，产品APK字节保持v5不变；最终v6同App/runner完整分组另验。

最终v6同一App/runner三环境各76方法/6组，共228方法/18完整分组通过（恢复17+边界31+队列UI6+执行UI17+独立404读1+记录UI4）。4记录场景分别验证原文/附件及逐项只读查询、记录内与晚到错误、13条后最早原ID可查，以及7个独立原输入的7状态/Turn标题；原生加密快照验证可编辑原字节及错误正文/目标/字节拒绝。当前15张原图逐张查看，0新真实模型。完整59根检查于原稳定8源码/353.39s通过，之后3份Android-only展示/验收修正由最终228及当前contract/parity/format覆盖，其余5份产品/界面文件及全部iOS/KMP/JS输入不变，按组件匹配复用而非宣称二次全量。

本项仅关闭原发送记录入口/完整列表/原文及附件名/准确状态/独立读等待与错误的辅助子边界，不关闭完整F25/F43/F44/F45或27节点/20功能；旧/缺失附件快照的界面兜底仍单列待原生复验。外部暂缓和A6/P7/P9保持。先提交推送/独立远端核对、树外恢复包与台账，再逐项继续输入栏参数、生成偏好/实际权限及首条/纯附件/冷恢复；计划、审批、问题与完整真实DEV后续逐项。

P4-4a `b03ba34ed33fa22b291cdda8e11d2cd96bd59d7f` 已推送，独立远端SHA、树外恢复包与台账check通过；主线未合并。

### P4-3d 运行中输入栏与原客户端模式

2026-10-23 P4-3d/O083/O090/O091/O092/O093：Android运行中输入栏改为与iOS同义的“排队执行／立即追加”分段选择，排队选项保留且能力缺失时禁用；原选择为queue但客户端队列不可用时显示同文说明，保留草稿，不自动改为steer或发送。继续调用同一KMP showsReplyMode及实际session.text/attachments计数；空白、纯附件、移除附件、空闲或无有效Turn逐项核对。能力变化不重写已存偏好，用户显式选择后才允许发送；模式回调携带完整目标，跨目标旧回调拒绝。提交/原回复或首条等待时禁用模式编辑，原请求/Turn/模式不可变；原记录只读查询不锁草稿。iOS既有segmented Picker/queue不可用提示与KMP提交门禁未改，代码匹配参考，不宣称新增iOS原生或真实DEV通过。

旧b03同字节APK配新验收器：文本/附件显隐三环境各1项通过，queue不可用说明三环境各1项失败。当前新4场景三环境12项辅助通过；全关联原生回归运行中，0新真实模型。 新场景分别核实际文本/纯附件/Unicode空白与有效Turn、队列不可用不自动提交而显式steer绑定原Turn、能力丢失偏好与旧目标回调、提交等待禁改且新草稿改模式不回写原请求。KMP核心、iOS和JS未变，仅3份Android源码改动，按未变组件匹配复用原辅助结果；不把fixture作为原桌面/模型或蜂窝证明。

最终同一App/runner三环境各80方法/7组，共240方法/21完整分组通过（新增composer4+恢复17+边界31+队列6+执行17+独立404读1+记录4）。18张新原图逐张查看，原选择/原请求/Turn及未提交草稿保持；零新真实模型。当前agent-contract/parity/format/diff在稳定3份源码期间通过，iOS/KMP/JS/工作空间工具源码未改，原core73/14各运行时、iOS构建及此前59根检查只按未变组件复用，不宣称本项重跑。API26文本图出现既有黑顶/导航重叠变化，原图保留，完整P2/P6/主题/语言/AX/真实DEV门槛继续开放。

本项仅关闭实际输入字段显隐、原客户端不可用queue说明/分段选择、跨目标模式回调与原操作等待禁改的辅助子边界，所有27来源和20功能保持，生成偏好/实际权限、首条/纯附件/冷恢复及其余计划/审批/问题后续逐项。先完成本检查点推送、远端核对、恢复包和台账，再继续生产改动；蜂窝/VPN、人工归档和其余人类暂停范围不变。

P4-3d `cf54e2046f1b1fc6e3f4b35d75d63b8150d3ea7a` 已推送并独立核对远端，树外恢复包及台账check通过，主线未合并。

### P4-3d.1 真实DEV验收器的输入顺序与选择器同步

2026-10-23 P4-3d.1：真实DEV控制验收器同步运行中输入规则。先用原生字段输入并等值核对正文，再选择已声明且可编辑的分段模式，选择后再次核对原文；原reply-mode仅检查显示，直接点reply-mode-queue/steer并核对enabled/selected。保留原提示、nonce、操作数及45秒/240秒等待，不执行旧真实模型场景。本项仅Android验收器修改，产品APK与cf54逐字节相同；iOS/KMP/Host及其他生产源码不变。编译与独立fixture控制边界辅助验证，真实原桌面/模型场景仍待P4真实DEV阶段。

补查整个androidTest目录仅MobileLiveControlsTest仍先点旧reply-mode下拉，并在空草稿时提前等待控件。现保留精确正文和原发送流程，仅调整为正文先行，再具名选择当前分段项；没有占位输入、额外提交或真实模型重跑。当前编译通过，产品APK字节匹配，独立fixture4控制场景三环境复验继续；不把该fixture当作更新后的真实DEV驱动已执行。

当前验收器编译通过，产品APK逐字节匹配cf54；同一产品/新runner三环境各4独立fixture控制场景，共12方法/3组通过，核选中/禁用、正文/附件显隐、能力与目标变化及等待中原模式不变。此执行未调用MobileLiveControlsTest真实场景；原桌面/模型结果仍未验收。当前iOS/KMP/JS/生产源码不变，原240/21与18图按组件匹配复用；0新增真实模型。 先提交验收器同步与结果，独立核对远端、恢复包及台账，再继续生成设置/权限与首条/纯附件/冷恢复；不据此关闭真实DEV或任何原来源节点。

### P4-3e 客户端生成偏好按显式字段合并

2026-10-23 P4-3e：KMP统一客户端生成偏好的显式字段合并，模型动作联动模型/强度/速度，独立权限保留；强度/权限/速度仅更新相应字段，null明确清除。Android旧草稿单项编辑不再覆盖其他已记住默认；即使当前草稿值未变，显式默认动作仍持久更新客户端偏好。保留捕获完整目标、可编辑及目录能力校验；已提交输入不修改。iOS删除原字段合并闭包，调用同一KMP规则，保留其模型归并与原生持久流程。新增版本化10向量及Swift实际调用；iOS夹具支持独立端口和树外证据，原精确交互/参数断言保持。

生成设置选中语义（跟随默认与实际生效项双勾）、标准/快速开关与多档菜单、模型默认的双端联动差异仍需逐项处理；实际owner权限、首条/纯附件/冷恢复及原桌面/模型真实DEV继续开放。27来源节点与20功能整体均未关闭；业务任务/项目暂缓、A6/P7暂停、蜂窝/VPN及人工归档待验收保持。

同一旧产品APK字节匹配，新增三项偏好反例在三环境共9失败，原12项回调对照通过；红v1缺少既有desktop创建能力的前置失败不作产品反例。最终同一App/runner三环境各87方法、8组，共261方法/24组通过，含生成设置7项、原回复/命令/队列/输入/记录回归80项；0新增真实模型输入。共享JVM/iOSSimulator各74方法/15套件、Swift10新偏好向量及原94 HTTP向量通过，iOS完整构建成功。当前iOS原生一完整场景、3合成输入通过：模型/强度/速度/权限、旧聊天隔离、重启恢复、新聊天继承及准确请求参数；6张当前iOS截图和3张Android代表图已查看，Android其余当前回归图留存，完整视觉/双语/AX未关闭。iOS首次启动因另一验收占用8797未执行场景，新增独立端口后v2通过，不停止其他任务。首次全量check在功能变更登记缺失处停止，补齐登记后重跑，失败保留。

完整pnpm check连续59条根命令通过，424.73秒，12份源码指纹稳定；其后只补验收文档，最终parity/format/diff单独核对。Windows原生跳过不记通过。P4-3e仅关闭显式偏好字段合并辅助子边界，27来源节点/20功能、实际owner权限、首条/纯附件/冷恢复、设置页交互与完整真实DEV继续开放。按阶段先提交推送、独立核对远端、恢复包与台账，再继续下一项；主线未合并。

P4-3e `c7ac1e27a0f8038b0c0f7bb0a9055be22d656ecb` 已推送并独立核对远端，恢复包和台账check通过；首次认证拒绝后仅本次使用既有钥匙串helper成功推送，全局配置未改，主线未合并。

### P4-3f 生成设置菜单、速度开关与默认联动

2026-10-23 P4-3f：Android模型和推理强度改为原生菜单，按iOS绑定的实际生效值单项选中，删除默认与生效项双勾；保留强度滑块和显式跟随电脑。速度目录含ultrafast时使用多档菜单，否则为整行快速开关，未声明fast时禁用；重置速度保持独立字段。模型跟随电脑调用已有KMP changeModel，与iOS同样清模型/强度并将已有不支持速度归并standard，保留独立权限。全部动作仍捕获完整目标、复核当前目录/能力和编辑门禁，旧回调不能改新目标。菜单按目标重置；使用蓝色值/上下箭头、48dp触控、16sp行文案、绿色快速开关及项目中性色，移除Material默认紫色。共享核心/iOS/Host/传输不改。

旧c7ac产品APK字节匹配，在三环境各4项失败（缺模型/强度菜单、速度开关/多档菜单、精确默认速度联动），原7项保护各自通过，共12失败/21对照通过。中间v1同App/runner三环境273方法/24组通过，15截图逐张查看发现默认紫色，保留中间结果；修订v2中性色后最终同一App/runner三环境各91方法、8组，共273方法/24组全部通过，含11项生成设置和80项原回复/命令/队列/记录/输入回归；15张当前设置图逐张核对，中性色修正通过，其他回归图留存不冒充完整视觉通过。0新增真实模型输入。KMP/iOS/JS及其10份输入逐一匹配c7ac，复用各74方法/15套件、Swift10偏好向量、iOS原生1场景/3合成输入及59根命令424.73秒结果，不冒充本项重新全量执行。

本项只关闭设置页菜单/开关/默认联动辅助切片；原生滑块与iOS系统控件形状、菜单弹出定位/材质、表单分隔与权限图标/布局仍逐项保留P6视觉门禁，双语/深色/大字体/AX未完整验收。实际owner权限及O028/O029、首条/纯附件/冷恢复、完整原桌面/模型DEV继续开放；27来源节点/20功能整体不关闭。业务任务/项目暂缓、A6/P7暂停、蜂窝/VPN和人工归档待验收保持。

当前3份Android源码指纹稳定，agent-contract、mobile-parity、format及diff检查通过。仅组件匹配复用上一阶段共享/iOS/JS与59根检查，不宣称新的全量/iPhone/真实模型结果。当前检查点提交推送、独立远端/恢复包/台账核对后才继续P4-3权限与首条/纯附件/冷恢复，主线不合并。

P4-3f `a70962901dbd47509644c8911f774d6c15285a89` 已推送并独立核对远端，恢复包与台账check通过；主线未合并。

### P4-3f.1 真实验收入口同步新生成设置控件

2026-10-23 P4-3f.1：三组真实控制、计划/审批、异步问题验收器改用同一原生生成设置选择入口；明确打开模型/强度菜单，速度走已声明的多档菜单或快速开关，再选择原权限。运行时速度已相同而草稿字段为空时，仅本地切换离开再回到请求值，保证原验收提交明确speed；不把生效显示冒充显式设置。保留原模型/参数、提示/nonce、操作数和所有原超时，未执行三组真实模型场景。仅Android验收器修改，产品APK与a709逐字节相同，iOS/KMP/Host/JS/全部生产源码不变。

新夹具v1在API26/手机各13方法有1项运行时fast前置失败，其余各12通过；原因是旧夹具使用settings而正式解析generationSettings，失败发生在新选择动作之前，产品和期望不改。API37合并13方法的单组触及180秒编排限，部分结果不计通过；根因未隔离，无性能通过。正确字段修订后，同一组断言/单例时限分为原11项和新2项，最终同一产品/runner三环境共39方法/6组通过。核对运行时fast与空草稿的区别，显式fast/standard与多档ultrafast均得到准确草稿字段，保留模型/强度/权限；三组真实场景未执行。0新真实模型输入，历史13输入不重放。

原桌面/模型权限与控制/计划/审批/问题真实DEV均未由本项通过。仅standard且未声明fast的目录，其快速开关按产品规则禁用；此旧显式speed验收入口准确拒绝，不能改用隐藏配置或默认为已选择，需独立跟随默认场景核对。业务任务/项目、A6/P7、蜂窝/VPN、人工归档及完整P3/P4/视觉门禁保持。

编译通过，产品包逐字节匹配a709，全部生产源码不变；原273/24和15张当前产品设置图按组件匹配复用，新runner截图保留不新增完整视觉通过。只新增验收器辅助结果，0新真实模型输入。

当前5份验收器源码指纹稳定，agent-contract、mobile-parity、format/diff检查通过；先提交推送/独立远端/恢复包/台账核对，再继续真实权限及首条/纯附件/冷恢复。

P4-3f.1 `80392d2c08781a225a9013b4d1db2c67e62223dd` 已推送并独立核对远端，恢复包与台账check通过，主线未合并。

### P4-3g 真机原owner权限与进程冷恢复

2026-10-23 P4-3g：新增独立Android真机权限验收器，固定4个新输入场景：电脑默认、显式只读、显式完全访问、进程冷启动保留原记录/未发送只读草稿后改跟随默认。模型和xhigh/fast由真实本机目录核对；手机使用实际菜单/输入/发送，具名Host独立状态和端口，另一只读原owner核对准确client ID/Turn、模型/强度/serviceTier、沙盒/审批策略、真实工具退出结果及工作区外专用探针文件。界面选项和模型自述均不替代权限效果。每场景单次发送租约、原正文/指纹、完整目标与原ID固定，拒绝/重复POST须保留为验收失败；未知停止后续场景，保留原查询所需ID/状态，不重放历史13输入。独立工具输出/日志留在树外私有目录，Token脱敏，使用Host公开close释放自己的目录reader/定时器。USB loopback仅为本机功能链路，不声称LAN/TLS/VPN新通过。生产App/iOS/KMP/Host源码未改，产品APK匹配a709。

只读预检确认本机默认full/never及真实模型xhigh/fast；早期零输入预检依次拒绝误用桌面管理工具项目ID、误认Host原始能力为原生派生cap、异步stdin未结束超时，均在模型发送前停止。按Host目录准确根/ID核对，正确原始能力及限时完整写入修订；v4原生1场景零POST通过，遗漏目录reader的清理由公开close补齐，已准确停止本任务reader并核对退出；v5原生1场景零POST通过且进程正常退出。当前v6进一步核对拒绝POST记录/失败门禁与完整缓冲响应，生产/Native源码不变。

仅关闭上述四项Android本机权限/进程恢复子边界及准确原记录只读续验；guardian/子运行作用域、EasyWork/Windows、纯附件/首条混合文件、其他计划/审批/问题/通知/控制和完整P3/P4/双语/AX/视觉仍逐项开放。全部27来源节点与20功能整体不关闭。业务任务/项目暂缓、A6/P7暂停，蜂窝/VPN和人工归档单列待验收；本次USB loopback不当作网络链路新通过。

当前v6真机1完整零输入场景通过，零业务POST/拒绝POST，验收器及自身reader正常退出。真实4场景尚未执行；完整仓库检查后继续。

2026-10-23 P4-3g 本次结果：本次默认、显式只读、显式完全访问、不同进程冷恢复后跟随默认四项，均完成原生手机及原Electron owner核对：实际模型/xhigh/priority、准确原client ID/Turn、沙盒/审批策略、真实工具退出与专用探针效果。只读为实际非零权限拒绝且文件保持，另三项实际写入成功；冷启动保留未发送只读草稿及三条原记录的ID/完整目标/来源/意图指纹。本轮新增真实输入共5条：v2独立核对完成1条但手机场景失败，v3四场景4条；历史13条和本轮已提交输入均未重放。v3四个原生/owner场景通过，但整体因代理拦截4次分支选项GET保持失败。修正只读白名单后，以同一原Host身份/状态、原聊天和原四输入执行零输入恢复：原生1项通过，分支选项GET 200、业务POST 0、运行期间错误0，独立owner再次核对总计4条及原参数；3条warm记录指纹和第4条原意图逐一匹配。关闭自有服务时1条未完成事件读取记录为cleanup cancellation，非运行期错误。原四场景Native方法与辅助代码还原后逐字节匹配实际运行版本，生产APK与a709逐字节相同。v3八张与恢复一张原图均查看，不扩展为全视觉通过。

保留v1缺共享确认导致零业务POST的180秒失败；补齐实际确认页，确认后草稿不变且不自动发送，v7零输入原生通过。v2遗漏creation-receipts只读接口导致手机待确认，准确原输入已独立核对仅执行一次并真实成功，未重发。v3四场景已通过但整体代理读门禁失败，不覆盖该失败报告。首次零输入恢复误把已接受后清空的草稿设置当作显式参数，改为核对空草稿和原运行时实际值；v2恢复通过，没有新增模型输入。登记检查首次alias类型及新脚本unused变量失败已修正，未改产品断言。

仅关闭上述四项Android本机权限/进程恢复子边界及准确原记录只读续验；guardian/子运行作用域、EasyWork/Windows、纯附件/首条混合文件、其他计划/审批/问题/通知/控制和完整P3/P4/双语/AX/视觉仍逐项开放。全部27来源节点与20功能整体不关闭。业务任务/项目暂缓、A6/P7暂停，蜂窝/VPN和人工归档单列待验收；本次USB loopback不当作网络链路新通过。

当前检查点先完成检查、提交推送、独立远端核对、树外恢复包和台账，再继续下一项。

完整pnpm check连续59根命令通过，348.92秒，三份源码指纹稳定。之后只把验收器私有密钥等值断言改为固定不匹配提示，避免失败时打印两侧值；对应差异逐字节核对，其他源码未改。当前静态检查及零输入原记录恢复再验通过：原生1项、原四输入仍各一次、分支GET 200、业务POST 0、运行期错误0，正常退出；另一次关闭阶段事件读取取消单独记录。累计仍5新输入，历史13和本轮5均不重放。这是匹配组件的59检查与当前小修复专项组合，不宣称小修复后再次执行59。

P4-3g `b1f950746303df3826ca2a40efb3ec30372fb82c` 已推送，独立远端、树外恢复包和台账check通过；主线未合并。

2026-10-23 P4-3h：逐条对照O096/O097原补丁；file-only在O097指仅文件flush的Host持久化合同，与纯附件输入分别保留。Android新增三项加密journal/HTTP辅助边界：重建journal后unavailable保持原意图/ID/目标和本地原指纹、撤销旧强未接受授权、禁止任何重试写入；unavailable即使夹带完成回执也不能制造接受或重试；原运行时不可用时，准确accepted回执仍能独立只读恢复并再次读盘保留，禁止重放且无workspace/history读取。Host unavailable的evidence/capturedTarget/fingerprintVersion/fingerprint/receipt保持原null，不把本机已捕获原指纹与Host缺失证明混为一谈。只改Android验收器，生产App/iOS/KMP/Host/JS未改。

新增验收器先因nullable receiver、再因缺progress参数编译失败；第二次失败后编排误复制上轮runner，三环境原17项各通过，新增组均执行0项，数量门禁使整轮失败，不计新增通过。修正编译并先核成功构建及新runner摘要后再执行。

这是实际Android加密存储重新实例化与原生HTTP fixture辅助证据，不是Windows真实file-flush-only/目录flush故障、原桌面运行时离线或Android不同进程的新增证明。匹配复用P4-3g的实际进程恢复和5条新模型输入，不重放历史13或本轮5输入；本项新增模型0。纯附件/首条混合内容另项、O096/O097整节点、全部27节点/20功能和完整P3/P4仍开放。业务任务/项目暂缓、A6/P7暂停、蜂窝/VPN和人工归档待验收保持。

最终同一App/runner三环境各20方法、2组，共60方法/6完整分组通过（原17+新3）；产品APK与P4-3g相同，新增源码仅一份Android验收器。新增模型输入0，本轮累计仍5、历史13不重放。没有UI变化或新增截图。新对象重新读实际Android加密文件只作辅助恢复，不能称不同进程或Windows实际flush通过。 先登记检查点并提交推送、远端/恢复包/台账核对，再继续纯附件及其余P4。

当前单份Android验收器源码指纹稳定，编译及60/6原生分组通过；mobile-parity/format/diff检查通过。所有生产、KMP、iOS和JS源码及产品APK未改，上一检查点59根命令348.92秒与小修复专项仅按组件匹配复用，没有本项重新全量/iPhone/真实Windows之说。
