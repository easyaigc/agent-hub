# Source82：P7 多原件聚合配额与主线缺口登记

2026-11-01，阶段57，F47/F55/F56/F64／UI12、UI21。**原Native方法与owned配额数据限定通过；原collector／worker退出1及全根严格边界失败保留。** Root已独立核对原数据，不将其等同于完整quota、P7、实际48MiB、UI或物理DEV通过。只新增Android独立原生验收类，未改产品实现、页面、协议或iOS源码。

## 本轮共同断言及范围

共同断言是导出拒绝超额时保留原件、原输出、身份和不可变原操作，不产生隐式录音、发送、转写、重试或激活权限。Android Keystore、原件目录及stream envelope是平台实现；iOS使用Payload／Keychain，PendingVoiceStore音频和metadata仍在现有迁移资料缺口中，不能把Android合成窗口称为双端同格式迁移通过。

单方法`NativeMigrationProvisionalAggregateQuotaTest#tenSeededPairsExportThenEleventhCrossesRealAggregateQuotaWithoutReplacingOriginal`使用10组seeded post-binary／pre-row窗口：20份body共192000880字节，实际调用生产`NativeMigrationExport.sealFile`一次并只发布一次。加入第11组后，22份body共211200968字节，越过生产固定192MiB（201326592字节）聚合上限；第二次export由`NativeMigrationStreamRecords`聚合构造拒绝，发布次数为0，不缩小配额制造边界。

方法内断言和独立原数据核对共同覆盖：原成功output完整SHA-256与九stat不变，无`.new`／`.bak`；source原件、index与row摘要、全部ciphertext和44个source成员九stat保持；原ownerAlias通过不可导出Keystore密钥挑战保持连续。外部核对未解密ciphertext；密钥挑战属于原Native断言。成功输出SHA-256：`32a68cdabc0789ca525586d9f0856e28fc15491b6c626a46138aba03088814d5`。

本轮没有真实raw录音seal、真实row I/O故障、crash／process death、candidate staging／activation、迁移UI／Dialog／picker、48MiB运行时低堆、物理DEV、真实Host或模型。fixture中的`realSealCalled=false`指录音seal；实际迁移`sealFile`调用并不等于真实录音seal。原Native实测`Runtime.maxMemory()`为201326592字节，即192MiB；不是48MiB。未执行sourceCallback，不重放任何原Native或业务输入。

## 候选与SDK

- 源码基线`ded38c8de10cc8bc34af61e18429434ba24f0f28`；阶段分支`codex/android-parallel-stage57-20261101`。
- 新Native类SHA-256：`04385d0f5d96059728c09189db305296dda8fb82dc656ee8aee04e92eb51ce4b`。
- canonical 860输入：`98a8de0ad188a6dbf37dcf588d15bd217ad3626ebcf4f14dbe7d141adc02077f`；expanded 873输入：`46fba37c25917e5e25f8b0e1b5c07e75579fbc4e8bebb0098c841d66992b6aaa`。
- App：`91aa986821b390771265ca07c2754fea0edcf9b026726875a2752058052eef34`；Test：`bb75d5a5648c39c5115a8718fa88e951dfedecc1f8f3069795e0de0cb78d6ba6`。
- Root SDK核对：`7201445205d7492a8e1d8a21b94d507296374e8c723b962da4e3ba5305fe29b7`。实际／冻结输入、三包ZIP／CRC／v2签名、12份SDK原始工具流及13892旧冻结文件／48旧APK完整核对通过，实际build-slot／wrapper／pnpmEntry退出0，内部约5.77秒。examiner未安装；SDK不替代Native、UI或真实DEV。

## 独立新环境、安装和启动基线

新owned API33环境从fresh userdata创建，不克隆旧AVD或userdata。原创建wrapper退出1保留；原avdmanager child退出0后仅执行配置续接并退出0。App和Test installer child分别只执行一次且退出0，两次原capture外层退出1均保留；随后Root独立对账当前已安装包与全部原始流，再执行新的只读finalizer并实际退出0，才接受有效安装状态，不把旧capture改写为成功。

只读inspect、单次创建files根及单次创建acceptance根分别独立核对；fresh baseline为65次guest＋10次local读取、488份完整输入，原执行及独立reader均退出0。两个根仅files／acceptance目录、mode700且为空，no_backup明确ABSENT；Root基线核对SHA-256为`5edb3d1c0f287b6d6041f9d9da5d5a2c6e0c240f2647b6fb4106da097b606297`。

只核对13项明确枚举的只读SDK／AVD配置来源，约8.78GB完整hash；这不是全部实际loaded image／mapped library完整性，也没有读取或固定mutable userdata。heap属性为512m／growthlimit192m，原Native实际maxMemory另按192MiB结果登记，属性不是48MiB通过证据。原物理手机忙碌现场未触碰，API37横屏与蜂窝／VPN仍待验且不阻塞本轮有界推进。

## 原操作、独立读回与分层结果

原operation ID：`e75707bf-02b7-4aa4-be43-a95545d0c4ba`；原run：`e96fd9b9-6255-4b22-81c2-a720dfad94c3`。具名启动契约SHA-256：`9e7ec382c451d852145fff923883f83156ee8404fd79cc410781dcf12ea27aa1`。原collector源码`0c84f1a426c30038fb83146d61780d7df14ce683abd322642c5f6892b577ce0e`，245项pure检查；独立streaming完整字节reader源码`e9668c15d3d4d365c06e467b3a457d1d15b0ac5314d12887eef5f9ab2dc85049`，24项pure检查。pure仅作辅助。

| 层次                 | 本轮结果         | 证据与限制                                                                                                                                                                                               |
| -------------------- | ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SDK与有效安装        | 按原指纹接受     | SDK、原安装child和新只读finalizer分别核对；旧wrapper／capture失败保留                                                                                                                                    |
| 启动前fresh baseline | 限定通过         | 65＋10读取／488输入，完整原流与独立Root核对，no_backup ABSENT                                                                                                                                            |
| 原Native方法         | 方法断言通过     | 仅调用1次，exact方法child退出0；原JUnit完整924字节，numtests=1、OK(1 test)、code0，约17.838秒                                                                                                            |
| 原collector执行      | **失败，未升级** | 原managed65158／最终回执`88afb0`外层退出1，worker51992退出1；Native child0不代表collector0                                                                                                               |
| 独立诊断reader       | 执行与读回通过   | 原managed67155／最终回执`96c7e8`，child及外层均退出0；未重跑Native、SDK、安装或原collector                                                                                                               |
| owned配额图与数据    | **限定通过**     | 原90capture＝80device＋10local均actual0；180完整streams、4份snapshot、403498508字节archive完整重算；owned50成员＝5目录＋45regular，全部ciphertext／source九stat／原output及under发布1、over发布0核对通过 |
| 原全根严格边界       | **仍失败**       | 除准入fixture外另有24字节`files/profileInstalled`及files父mtime／ctime变化；不删除或过滤原差异、不放宽旧准入、不将原outer1改0                                                                            |

Root最终有界决策SHA-256：`001c39d49f72595cb7f0d393779158ad2bd73ea876050fffe929bde492ec5eb3`。独立诊断数据报告SHA-256：`30b836575a6bd322fe95251a7e39153bd907544874fcccf702b181eb1c8bc5e2`。当前明确登记`BoundedOwnedQuotaAssertionsAndDataAccepted=true`、`OriginalCollectorCaptureAccepted=false`、`StrictOriginalEntireRootBoundaryPassed=false`；完整quota／P7／48MiB／UI／物理DEV仍未关闭。

### 额外ProfileVerifier cache的静态来源与待验

`files/profileInstalled`原件为24字节，SHA-256：`239230d97145a83b54b38bd3784411be1779dec40194bd487a707b9ab3533789`。已完整核对原stat／archive成员、包内ProfileInstaller 1.4.0与Startup 1.2.0、默认初始化注册及实际编译writer／serializer。原件按big-endian `>iiqq`解码为`(1, 327680, 1793526531290, 0)`，对应ProfileVerifier cache schema、`RESULT_CODE_ERROR_NO_PROFILE_EMBEDDED`及其记录字段；准确APK没有baseline profile assets。它是库cache，不是录音资料，也不改变owned图原件保持结论。

静态来源与格式已确认，原Profile来源核对SHA-256：`d5559da0ac4320738ed3d0facee1da37c65ef992501ae876515bcf7758c09614`。**动态writer调用栈、是否首次启动以及包时间毫秒值均未独立证明**；不得据默认初始化路径、约5–6秒时序或整秒dumpsys推断这些条件已通过。原全根差异与collector失败保持，后续如需关闭更严生命周期断言，另行冻结场景和证明，不重放本原操作。

## 最新main有限缺口与优先级

本轮额外登记阶段56合并`332a291ffe2801ec41c1bb7f49bbed48500fb70f`至main`9492e3b7a9877d0c08615d5fd403512b1e654221`的8个非合并提交／六组有限缺口。Root已独立读取111个变更文件出现项并采纳库存，审计采纳SHA-256：`2c9d2d6d4c7a687f082d665cc7e624c06025d8c0f8abe7f5e25bd2590eb49264`。这是源码／文档审计，未将新main并入860／873候选，也没有新SDK、UI、iOS、Android、物理或真实Host通过。

| 顺序 | 功能／阶段                        | 已核实来源与计划                                                                                                    | 退出门槛／仍未完成                                                                                                                                                                                                                                  |
| ---- | --------------------------------- | ------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1    | F02/F03；P8/P1                    | `c6705edb`已在main含5个Android生产Kotlin文件及资源；当前冻结候选尚未整合                                            | 主统筹三方集成，核对电脑名／用户名／旧name回退、地址和配对分行、自定义名、automatic缺失转扫码、迟到代次／目标隔离；新SDK、Native、双语主题／AX及真实EasyWork目标／密钥回归                                                                          |
| 2    | F36；P6／UI07、UI08               | `97a6346a`：standard／未确认无bolt、fast单蓝bolt、ultrafast双蓝bolt，紧凑标准保留设置入口                           | Android增量及各密度、禁用、双语主题、AX和48dp触控；速度目录／提交语义保持                                                                                                                                                                           |
| 3    | F19/F32；P6＋P7／UI05、UI07、UI12 | `dd8fcb71`：旧Host／缓存完整visualize指令兼容与独立HTML交互预览                                                     | Android尚无完整容器／旧指令兼容；原消息与目标／摘要／大小授权、UTF-8≤2MiB、无native bridge／Host凭据／持久网站资料／任意本地文件或导航；错误、双向滚动／缩放、原件分享、关闭回草稿与冷生命周期；F72内嵌HTML仍不执行                                 |
| 4    | F26；P3/P6＋P7／UI02、UI04        | `85d90f67`：最近5项目MRU、完整目标隔离与重启保留，属于现有选择／导航增强                                            | Android增量与失效项目／取消／无项目／自动恢复／旧可选字段回归；iOS新增`SavedSession.recentProjects`经migrationPayload.state保留，纳入24域`root-session`保留或明确排除政策，不宣称两端跨格式可直接互导                                               |
| 5    | F52；P9                           | `82dfe17f`：APNs按topic／environment选key、publisher完整目标授权和旧默认兼容                                        | Android／FCM无本次实现变化；真实权限／渠道／供应商／OEM／点击／生命周期分别验收，Apple接受或iOS记录不能替代Android送达                                                                                                                              |
| 6    | F37/F38、F03/F69；P4/P5/P10       | `7104af20`为具名queue／steer／execute工具入口；`f1b43fb3`为Windows全局启动器暂停限制，`e369bbde`为辅助fixture／文档 | 沿原queued-message-id、新协调operation及expected Turn回归；未知查原回执不重放，不改steer生成设置。保留Android已有建议灰字／全文／采用／追加／替换确认／忽略／撤销及native-version／runtime-version契约；新被动真实源、Windows受限环境和完整视觉待验 |

两处引用`docs/acceptance/easywork-discovery-20261031.md`、`docs/acceptance/speed-indicator-20261031.md`在准确目标Git树缺失，只标“仓库证据链接不可用”；不能猜造证据或推断私有证据不存在。上游组合叙述“Android建议UI尚未实现”与当前代码不符，后续整合须保留已有实现与P5结果，并单列真实源／平台／全矩阵待验。

先保存、提交当前Source82准确源码及分层结果，再由主统筹在下一独立Android阶段分支处理上游语义集成、冻结、回归和Gitea→main交付；新main不能追认旧候选通过。

## 合并交付

[PR105](https://git.51easyai.com/BCAI/agent-hub-app/pulls/105) 已于2026-11-01合入Gitea main `08e895c3`；源提交`72f0b487`及同步main后的`1d8709db`，CI11048 workspace／iOS同源通过。唯一manifest冲突保留main全部记录并附加Source82验收类的准确变更记录；纯文档主线缺口不放入只允许源码路径的manifest files。阶段57本地／远端分支已清理，同一隔离工作树转入阶段58。Source82原Native／owned数据限定通过、原collector1／全根严格边界失败及全部未验范围不变；main集成与CI不能升级旧冻结SDK或代替新生产指纹的Android界面验收。

## 后续与资源

P7完整24域、更广多原件与迁移流程、真实row I/O／seal故障、冷读取／实际48MiB、完整语音／未读／生命周期、iOS音频资料缺口及迁移系统UI／物理DEV继续开放。原全根严格边界与动态Profile时序待验不能因owned数据限定通过而关闭。P9真实Main冷入口、实际OS点击和通知完整生命周期继续待验。F65–67、UI14–19、UM06业务、UM07–08、F75原生项目UI及iOS A6暂停，不因F26导航或Host共享限制恢复。

旧Source78失败链、Source79 collector1／tar255及恢复退出未知、Source80原安装外层未知均保持；Source81独立只读通过按原指纹保留。封存未知不查询／重放；真实Host／模型输入仍221，P4为205＋另1封存未知，本轮新增0。

本阶段仅新增Native类及文档，未创建用户独立对话或跨电脑协调；草稿、审核与只读对账中间物在树外按准确归属登记，主统筹于阶段结果／提交明确后回收无依赖的可重建文件。必要原回执、失败证据、源码／包指纹与后续完整保护链单独保留；24小时内复核保留项。独立诊断reader实际Terra／medium已核，实际速度／service tier未核验，不宣称已启用极速。
