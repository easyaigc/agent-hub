# 阶段74：语音原件首次迁移与只读冷恢复

功能 ID：F55、F56、F64。状态：API33 两个命名 Native 方法有限通过；完整检查通过，PR123已合入；完整 P7 未完成。

## 功能、交互与界面对照

从 VoiceInputController 提取既有 NativeVoicePcmWriter，采集仍先取得相同 raw owner，按原顺序创建/截断、写初始 WAV header、追加 PCM、同步、完成 header 和关闭。初始化失败关闭已取得的 descriptor，保留原错误及关闭错误；close 不补封口。生产录音、取消、时长/配额与计量路径保持，真实麦克风正常/取消/中断回归仍须单独验收。

新验收使用真实写入组件生成 16kHz/mono/16-bit 确定性 PCM，覆盖 S 已封口并密封、R 未完成 header 的 raw、U audio-committed 后 metadata 发布前的命名异常。S 的 attempted 标记只是本地合成资料，不调用真实 prepare/upload/status/receipt。U 是已知 checkpoint 异常，不能记成自然磁盘失败或崩溃。

source 原件经实际 export、普通 Controller 的 show/recoverCandidate/confirmImport、真实 Session 恢复，再由不同进程 existing-only reader 读取同一候选和原回执。sealed 原 WAV/manifest 保持但为 migrationReadOnly；unfinished 和 U 的 row/index/raw/audio 保存在惰性目录，不恢复录音或重放。共 16 条 portable（11 text、5 binary），目的端新增审计和墓碑后为 13 text、5 binary；基础连接/草稿/阅读/回执等仍是合成基底，不是16条真实模型历史。

iOS VoiceInput/PendingVoiceStore 同为 WAV 原件；CAF 属通知声音。iOS 的独立惰性原件和 Android sealed/provisional 恢复策略、Envelope 版本与整包互通仍有开放边界，本次没有执行 iOS。没有页面/token/文案变化；功能数据和 Controller 状态的通过不能代替 Main 密码/预览/取消/错误/成功、短视口/方向/字体/AX、SAF 或双端物理界面逐项验收。

## SDK 与原生证据

- 精确 SDK profile p7-voice-originals，888项源、24项显式overlay；共享 Popen、全源前后守卫、严格 APK/证书解析保持。82/82任务、17条原wait=0/rawComplete；外层实际wait=0、双EOF/fsync。seal：`202609086c700be62f56bdb2649882c26c6b537489168eefbefa524e387bce99`；guard：`4cfb1771230b0edd2532fa7c397da7df4a0408c361df83227f002074f67a649b`。Root逐项重哈希92份封存材料与888项源。
- App SHA：`11ffcec2c03b43aee54e06c8169ee5cad776eade72b0ec25626e10c80a1620c4`；Test SHA：`85bd2321330d65f5bc472ba4474f2af7ea4a3208a539dc8a99ebf28ee6fe9721`。实际安装、单证书、UID、boot、live PID/start/argv均独立核对；最终App/Test均与已通过版相同，核对安装指纹后未重复安装。
- 新 run：`b0fa972b-4a12-4520-90e3-4482d92da326`。两个 selector 为 `NativeMigrationVoiceOriginalsDeviceSessionTest#voiceOriginalsControllerProducesPortableArchives` 和 `#voiceOriginalsReadOnlyColdAfterExactOriginalExit`。原始stdout各有准确方法名和 `OK (1 test)`；adb原子进程wait=0、双流完整，外层正式工具最终exit=0。
- 首次 PID/start 4044/9983，自然退出后冷进程4182/12039；同boot `910f1775-1c55-45b1-a9d9-36937e564998`。原proc缺失与目录缺失分别观察，未强停/杀App制造退出。两reader关闭，first/cold原件hash图、原控制文件与alias保持；没有把Host wait说成OS对App的wait。
- first proof：`4bf8054ee4d994f7b1d9108bf5b8930454682c41b73ac09dc554daf934fbede0`；cold proof：`830753666985a3a5a8b529f3a24700be4bacbd0c507f47b038ff56ed13b82051`；完整证据索引：`ea7c30f5e35e45e58957258d7d08e20b76b61208189cec44681718e62da2367b`。Root复核63项原始材料，未靠执行方结论判通过。

## 原失败与修复

首次 SDK 封存成功，但 Native run `4e9b83d6-c758-4b15-b8b1-563631f77a36` 在目的端 current 对比失败，cold 未调用。TargetScope 是无 equals 的普通 class；两个 decode 的 data class 引用相等断言不代表内容相等。仅修新夹具为 scope.matches、完整 canonical 12项值属性及原字节比较，保留旧完整比较断言；不修改 TargetScope、VoiceStore、生产恢复或许可语义。新Test、新run完成两项，旧失败及其数据/KEY原样保留，未重放或改判。

旧SDK外层argv未单独封存，本轮按正式argparse与已核旧工具/build invocation重建并保存新argv，不冒充历史原文。一次只读 verifier 调用误把expected_guard传为hash，保留失败后按原API对象参数完成验证；未因此重跑外部操作。旧首轮 .build/native 前置分配未测，不能宣称其准确增量；本轮实际目录前后差额单独记录。

## 收尾与后续

本轮QEMU正常emu kill，原launcher持有Popen实际wait=0，双流完成；无原PID或5558/5559监听，观察到TCP TIME_WAIT，未将即时bind失败说成仍有活跃模拟器。R66原基准1,440,178,176B，当前1,579,986,944B，累计增长139,808,768B，低于512MiB cap；free为22,681,903,104B。保留当前实际SDK、必要回退包、旧失败及本轮最小证据，阶段替代产物按精确指纹/无占用继续回收；不重置累计预算或触碰其它设备/服务。

下一顺序：其余系统迁移域、已有资料合并/SAF和真实麦克风/中断恢复；P8剩余与P9通知独立并行。完整24项、自然I/O/崩溃、Main/SAF/UI/物理、双端整包互通与iOS补偿失败恢复仍待验。真实模型账本221+0、P4 205+1 sealed unknown不变；任务/项目CRUD、F75与iOS A6暂停。

## 交付检查

本轮 generate、限定文档格式、工作区/暂存差异、mobile parity 与完整 pnpm check 均通过。完整检查原PID851实际wait=0、双EOF/fsync及正式工具finalexit=0，耗时728.08秒；42组1518项，1456通过、62跳过、0失败。该次检查的首/格式化后/结束888项源和24项overlay与当时SDK guard一致；随后仅两个新验收文件的空白规范化，Kotlin行与Python AST未变，完整检查按该非语义差异匹配复用。最终源码另重建SDK并重跑first/cold；辅助检查不代替真实DEV。F55/F56/F64只增加本阶段来源、revision和限定说明，双方完整implementation/verification状态未升级。

两份已被当前包或必要回退包逐字替代、无占用的旧App APK已精确回收，分配上界133,652,480B；原Test、source/seal/raw/JUnit及退役回执保留，对应旧bundle明确为partial，不能再做当前完整verify。R66与本轮未决复核资料按24小时登记保留；不以旧目录名重置累计配额。

CI专项收尾：PR121修复及PR122限定P8均已真实合入main，PR122源CI11236两任务成功。准确主线CI11237的workspace27089/iOS27090均成功，公开同步11238成功，公开提交7585290d的tree与Gitea-Source-Commit均匹配cba23af8。MacBook128交付的PR121源分支按61208edc删除，Root从Gitea独立回读确认不存在；其任务临时资源/凭据已取回指纹回执，专用协调聊天归档操作confirmed且独立thread元数据archived=true。没有重跑原操作或停止正式服务。

最终暂存检查补充：先前普通git diff --check未包含未跟踪源，首次实际暂存后发现新PairContract第194行尾空白与workers末尾多空行，原提交执行在commit/push/PR前停止。Root只规范这两处，Python AST与Kotlin行内容相同，未改生产语义。最终SDK的App/Test与前版逐字相同，仍以新guard和新run重新执行两个Native方法并核对完整原件。前一1496d285通过版与最初失败版均保留各自原指纹，不重标结果；最终暂存差异、生成及一致性门槛另行核对。

交付终态：源5242f173经PR123正常合入48884eea，parents和整树与source一致；源CI11240的workspace27093/iOS27094成功，准确main CI11242的27096/27097成功。阶段74本地/origin/tracking三类引用清理，stage75当前现场不变。自动同步11243/27098成功，公开77f6e144的tree和来源trailer匹配；无直接公开推送。
