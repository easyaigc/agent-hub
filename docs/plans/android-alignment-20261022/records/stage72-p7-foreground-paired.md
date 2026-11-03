# 阶段72：通知原件归档的首次迁移与只读冷进程

功能 ID：F55、F56。状态：API33 两项原生组件有限通过，完整检查通过，提交交付中；P7 未关闭。

## 功能、交互与界面

沿实际本地生产者建立通知身份、saved connection/authorization、foreground-v1 unknown 与 recovery-v2 unknown。全部使用新 UUID 的隔离 source Storage；没有构造通知 HTTP/命令实现，也没有 confirm、settle、query 或重放原 unknown。实际 export 将原九条资料与一个通知安装档案目录、三条只读原件合成13条记录，即12条文本与1条 normalized CAF。

普通 Controller 的 stage/recover/PREVIEW/一次 confirmImport/真实 Session 工厂恢复通过。目的在 confirm 前没有由夹具预建 root KEY；六观察窗前五无 KEY、DONE 后有。正常 quiesce 会保存 metadata，可在 Activation 前建 KEY，首次触发点未观测。source 原件和密文、candidate/active pins、通知及阅读 reset、独立新草稿与原 unknown 不变均有实际断言。

四个 active authority 名不进入 candidate；源身份私钥没有作为档案原件迁移。目的仅保留安装 context、原件摘要及三条惰性审计原文，要求 fresh setup，不能用归档恢复旧授权、回调或许可。iOS `SessionStore.restoreMigration` 会恢复身份/绑定和 pending-revocation，Android 当前为 archive-only；这一已有行为差异保持待统一，不能将本轮测试算作双端完整一致。

交互覆盖 Controller 状态、新 Session/Storage 身份与就绪，随后原进程自然退出、不同进程只读恢复审计。没有产品页面/token/文案或 iOS 代码变化；Main 文件选择、密码/预览/取消/错误/成功窗口、方向/短视口/字体/AX、系统通知投递及物理界面仍待逐项对照 iOS。

## 原始验收与保护边界

- 新 run：`d6bb006c-3740-4d8e-baa8-e9f1cd6ce99e`。first selector 为 `NativeMigrationNonemptyDeviceSessionTest#nonemptyForegroundControllerProducesPortableArchives`，cold 为 `#nonemptyForegroundReadOnlyColdAfterExactOriginalExit`；每项原始 named JUnit 都是 `OK (1 test)`。
- first PID/start 为4226/19766，独立观察到原进程自然退出后，cold PID/start 为4397/21840；同一 kernel boot。两个原 Host worker 与外层实际 wait=0、双流完整，未在中间重启设备或杀原进程。
- first proof：`f5269393099381dcd40e2e5037bcd5889a9c079633f4c6caa07866ebbf0f8030`；cold proof：`6564f5e581ebb0b78c86a2d04b728cdbeed5a0e4c80fbd3446b90bddf438c73e`。
- 登记27 stores、4 Session join、25外层 writer retire 与2生产 old-root 退役、2 lease release 返回。cold 不构造 writer/KEY/Session/lease，不 prepare/activate/confirm；两个 ExistingView reader 的实际 close 返回，受保护 no_backup/cache/files、原 first documents 及 fixture alias presence 前后不变。没有声称 OS FD/executor 终止、Controller worker join或 Keystore 内部磁盘完全不变。
- SDK profile `p7-foreground`：876项输入、8项精确 reviewed overlay，复用原 capture/Popen、SDK工具、APK解析与旧九条契约，未放宽旧 selector。17个原进程实际 wait=0、原始流完整，82 Gradle tasks；capture seal：`096763f8be4504fca82edf36c8a2d1aa404e97484c5977cf037a2d9828c8afdd`。
- App SHA：`39c99bfd17dadf7947ad708f8e49eea2108eb294e277fed62d6acfbfc5dc7fc7`；Test SHA：`47679a05bb7a85f27934d7e1279a684e81e721579621dfb21834d4ac7781fff4`。安装后实际两包字节、UID、路径及证书核对；Test manifest 版本为空，PackageInfo 的0/null仅为回退值。

合成 focused 覆盖旧九条、新13条及 cold 的有限正负边界，source guard与差异检查通过；raw/wait-false 和新 selector 旧 extra arguments 未动态覆盖。合成正例不是 Native 证明。

本轮 `pnpm check` 原进程实际 wait=0、双流 EOF；42组共1518项，1456通过、62跳过、0失败，耗时708.22秒。原始输出摘要与完成记录一致，检查后876项源码/8项overlay指纹保持。原外层工具会话末回执未保存，未将子进程完成记录伪称外层回执，也未为补回执重跑。生成、格式、差异及双端一致性检查通过。

首次 SDK 外层因未预建空 bundle，在 API 第一条目录 lstat 即失败，尚无采集子进程或 Gradle；原 wait=1/trace 保留。Root 独立确认未提交 SDK 后预建目录，以独立外层意图完成首次实际构建，没有重复原构建。安装 App 的原命令已 wait=0，调用方因 adb 提示过严拒绝后只继续尚未执行的 Test 安装；原回执保留，没有重装同一 App 来掩盖失败。

## 收尾与剩余范围

实际源 archive 已单文件只读导出，26,707字节，SHA-256 `f8aa57e0a48620907401f1c6af023e86af1b9ef0816e2c4266d42dfdf36cd14d` 与 proof 一致，仅供后续独立互通检查。原始数据和凭证保留在私有目录，未提交真实聊天、用户附件、密钥或机器路径。

本阶段 SDK 后回收 C 的已完成缓存约882MB上限、T 的已完成中间件约1.24GB上限；当前/回退包、源码、SDK及原始证据指纹保留。仅 T 自己的 IDLE Gradle daemon 经正常 stop，其他进程未处理。分配上限不等于卷实际释放量；历史全任务累计空间口径仍有缺口，不据共享卷变化宣称预算全部已证实。

Root 自有模拟器已正常关闭，Root 亲自等到原执行会话返回0；AVD、fixture、KEY/data保留供接续独立资料域验收与审计，24小时内复核。关机后原 same-boot scope不能直接续用，后续配对使用新 UUID 并在同一启动周期内完成。真实模型账本221+0、P4 205+1 sealed unknown不变。

完整系统清单24行含排除和暂停项，不等于24条 wire record。本轮不关闭语音原件、inbox/selection/photo 等其余应迁移域、目标已有资料合并、iOS补偿失败恢复、自然I/O故障/崩溃、SAF/UI/physical、双端整包及通知身份策略差异。任务/项目CRUD、F75、A6继续暂停。
