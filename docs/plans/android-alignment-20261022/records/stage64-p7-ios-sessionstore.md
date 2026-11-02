# 阶段64：iOS SessionStore 同步写入失败保护

2026-11-02，F55/F56/F64，基线 `d7181f9ed9f20981a2e6954c9ebd4055921a2979`。本阶段沿用既有平台container，不新增跨端整包格式承诺。同步写入失败补偿已接入；actual SessionStore成功、写前拒绝与真实写失败补偿共3项Native限定通过。P7/P8/P9仍部分完成，24组迁移库存没有整组关闭。

## 实现与保证边界

旧replaceLegacy按backup旧值→legacy新值→state新值写入，state失败可能留下新legacy/旧state。当前在任何目标写入前，读取三个固定目标的原bytes或absence，并拒绝不可读或非regular对象。同步写入抛错时按相反次序恢复已改变的目标；原来不存在的文件恢复为不存在，未改变的目标跳过补偿写入，最后逐项读回核对。

补偿不全会保留同实例原snapshot并禁写，不把部分恢复写成成功。load仅只读核对完整backup/legacy/state三元组与原snapshot一致后解除恢复gate，再走原有加载路径；不自动重放失败替换。此为同步错误下的bytes/absence补偿边界，不承诺OS崩溃、journal/fsync持久性、权限/元数据复原、完整内存矩阵或实例销毁后恢复内存snapshot。私有R2静态复核的两项P1已关闭，无新P1；静态关闭不代替故障Native结果。

独立新test target含60份production源＋1份test，空SwiftUI Scene host仅1份空入口；生产App 61个Sources成员/设置与旧Stage61九项test文件、target、scheme保持。实际production Store/default Keychain/通知I/O在任务独占Simulator容器运行，未用fake Store。环境准备、原失败与运行准入另见[Native环境档案](stage64-ios-native-harness.md)。

## 本次验收与待验

| 场景                                                                                  | 当前结果                                      | 本场景边界                                                                                                                                                                                                                                                      |
| ------------------------------------------------------------------------------------- | --------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `testActualSessionStoreRestoreSucceedsWithInertOriginalsAndUnknownIntent`             | **本次通过：1 passed / 0 failed / 0 skipped** | 小型合成旧/新草稿与附件，真实默认Keychain/通知I/O；consent=false、generation=21、unread=0；旧/新惰性语音原件wire seal/open及再导出保持；immutable unknown outbox原target/body/opID/fingerprint和retryConsumed保持，禁止重放                                     |
| B1：`testActualSessionStorePostOriginalPublicationPreWriterWritesFailureRetainsState` | **本次通过：1 passed / 0 failed / 0 skipped** | 原件发布后state被设为非regular目录；writer写前预检拒绝，未进入writer写入catch；旧primary保留件、legacy、backup均1257B且同SHA，不当作补偿写入通过                                                                                                                |
| B2：`testActualSessionStoreImmutableRegularStateFailureCompensatesWriterChanges`      | **本次通过：1 passed / 0 failed / 0 skipped** | regular state真实UF_IMMUTABLE导致CocoaNoPermission/POSIX EPERM，进入catch并补偿改变的legacy/backup；原state/legacy1257B同SHA、backup恢复absence。旧状态/资源/badge9/Keychain/bindings/旧新voice再导出/noReplay均断言通过，同inode解除flags的Native teardown通过 |

正式App SDK、独立签名host构建及ENV probe分别保留原范围通过；最终容器guard只改变一份测试源，当前生产App 61份输入与SceneR2官方SDK字节完全匹配复用，未在最后guard更新后重跑App SDK，不把SDK全量2902闭包写成当前Native同一snapshot。三项actual Store Native均实际编译并运行当前0582测试源，compile/Test退出0；ENV不实例化Store、不证明Keychain可写，真实默认Keychain读写来自上述成功Native断言。无Main UI/完整手机DEV、物理设备或真实模型任务通过。新增restore仅小型合成资料图；large/memory/quota、补偿不全与恢复失败分支、完整domain、冷崩溃、完整迁移/语音UI、旧reader实机、物理与跨端wholearchive wire仍待验。

成功用例Root已读全raw及29份已知退出子回执、58份raw stream，2902-input三份源码快照与实际树一致。Root总复核 `stage64-root-actual-sessionstore-success-review.json`（1952B），SHA-256 `3f29a9058fe18236cc44b5226597adf7b9c87fbcfdcfecd6734f9d629a3b513a`；安装包与build二进制匹配复核 `stage64-root-success-installed-binary-match.json`（299B），SHA-256 `094099c80d3fdfebdcc8af7df45a454e38e6a57b63d75ba5435c74b2d15be20b`。上述不把旧Shutdown查询149提升为独立pre-marker证据；实际test空namespace/keys guard已通过，PID有记录但UID未采集。

两个故障用例Root复核 `stage64-root-two-actual-restore-failures-review.json`（5591B），SHA-256 `6997a393394b2febaf842a31da4f2dbed589483bc9d8890fd29735db0c4841bc`：44份实际已知退出回执、88份full raw已读取；每case前/后的完整2902-input都匹配成功case批准baseline，各runtime Home/Bundle与shutdown前simctl独立双路径一致。B1旧primary保留件/legacy/backup和B2旧state/legacy均为1257B、SHA-256 `385c9790ea07149f154eeac6118fb93e330df124f7b34781bcde66c698074e6e`；B2原state同inode且teardown后flags=0，backup不存在。两case UID均未采集。运行后fixture的8份physical archive补充文件与库存bundle capture4 files是不同清单口径，不写成全配额或完整库存通过。

## Android对照与双端状态

源码对照未确认Android存在同类新legacy/旧state可达P1：Android先写/验证完整独立generation，再提交单authenticated AtomicFile pointer；发布前旧profile保持，发布后新generation整体权威。此为源码结论，指针提交/确认真实I/O故障、发布后权威/恢复及完整UI仍待验，不新增Android产品实现或Native通过。typed restore ack仍仅未编译提案，无closure，不记入本阶段implemented。

对照 `stage64-android-counterpart-code-review/review.json`（13685B），SHA-256 `f59b01492b6e4ecf6f61698fec64af27e5061c5d551789a6847f2909ed350c5d`，16 pins已由Root核对；Source75/80/83的8份core当前bytes一致，仅保留各自旧scope，不能把macOS CLI、Android fixture或历史配额验收套为本次iOS Native。F55/F56/F64仅更新source alias与revision，全部implementation/verification/shared状态及其他功能保持。UI06/UI12/UI21未新增界面结果，Android相应页面继续待对齐/验收。

## 本阶段五份源码指纹

source alias `IOSMigrationSessionStoreErrorAtomic`、change ID `stage64-p7-ios-sessionstore-error-atomic`记录输入修订，不表示完整功能已验收。当前接入顺序以hosted adoption、Scene fix、runtime-container admission三份私有接入记录后者覆盖前者；旧Stage61测试原bytes保持。

| 相对路径                                                                                        | bytes | SHA-256                                                            |
| ----------------------------------------------------------------------------------------------- | ----- | ------------------------------------------------------------------ |
| `apps/ios/Sources/SessionPersistence.swift`                                                     | 15634 | `34686f2d4406b750881ac13cff7626abb6eb21c306de30990578a0b8edc0efa2` |
| `apps/ios/CodexManage.xcodeproj/project.pbxproj`                                                | 79957 | `b73125b5ca7bfce23c69a21be9bf06dec82fea3f4c61d2f17bdea71d7086e3e6` |
| `apps/ios/Tests/Stage64SessionStoreNativeTests.swift`                                           | 28084 | `0582dadde4ea9196715c44c53b80796d352e3e143b02b6594a1039b7b5a3b3d9` |
| `apps/ios/Tests/Stage64StoreTestHost.swift`                                                     | 336   | `87bd8b4d92d739c16caa64156931ec5d4d9db2a82062da3f2a571315cdb20f93` |
| `apps/ios/CodexManage.xcodeproj/xcshareddata/xcschemes/Stage64SessionStoreNativeTests.xcscheme` | 1868  | `e3b9412d08926ad5ba5ed54c1a1ae3909ade564f7e3817fed39392b781df2eff` |

## 交付与资源状态

本阶段[PR113](https://git.51easyai.com/BCAI/agent-hub-app/pulls/113)已合入main `6adc9ff93d46d38e554943088913d348d25751c4`，head `99598238417e5b04f49e24db7671e9459dca08f7`，CI11145 workspace26931/ios26932成功，阶段64本地/origin/tracking分支已清理。Root已核对远端merge双亲与tree等于head；不复用前阶段CI冒充本次通过。

成功、B1和B2 Simulator均已正常shutdown/delete，Root逐项确认registry与实际device root不存在；更早unhosted Simulator亦已清理，临时runner均已清。故障pair收尾复核 `stage64-root-passed-pair-sim-cleanup-review.json`（453B），SHA-256 `41c7ef1c68c18e2fdbbd26c5dc51f73d73432763e73baf1f82f410ac30b43495`，8份实际wait/16份full raw读取，两个delete退出0。current App/Native Products及framework因PR和当前包匹配复验的具体依赖继续保留；提交后Root核对无active FD，已回收609112064B allocated的本阶段可重建Xcode intermediates/caches；实际包及必要证据保持，私有收尾回执另存。失败raw/crash与两份closed xcresult无损归档索引保留；正式App、R/W保护现场、配置与原指纹不动，无新用户独立聊天。

本轮Host/model输入+0，累计221、P4为205，另1个未知原操作封存不查询或重放。任务/项目F65–F67、UM06后续业务/UM07–08、F75及iOS A6物理暂停；API37横屏、蜂窝/VPN和其他系统依赖仍单列，不阻塞本机可执行工作。
