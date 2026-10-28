# P7 文件流式迁移第一子阶段

任务 `android-parallel-p7-migration-20261028`；共同起点 `1e908bea1ac75341e17e56a518d7bebd46620c4d`。

已实现候选：满额封口录音通过 `NativeMigrationExport.sealFile(File,password)` → `NativeMigrationCandidateStore.stageFile(id,File,password,replacing,checkpoint)` → 现有 `NativeMigrationActivation.activate(receipt)`。文件流不转 Base64，不分配完整 WAV，不创建明文临时文件。旧字节数组入口仍保持 8/12 MiB 并提前拒绝超额；它的拒绝不是本子阶段成功证据。

文件封装 v2 使用原 PBKDF2-SHA256/210000 与独立域 AES-GCM；64 KiB 分块，AAD 绑定盐、随机流身份、序号、明文长度。独立零长度认证终帧和文件 EOF 拒绝截断、追加与重排。其内部 v3 档案为严格有界原文头加原始二进制；每个音频大小、摘要、44字节 WAV 头、时长和完整原目标图均验证。8 MiB 文本头、单二进制21 MiB、聚合原始二进制192 MiB与外部文件256 MiB分别限制；没有 `largeHeap` 或旧配额常量上调。

候选仍使用同一 `candidate-v1` 槽与 Receipt，新增本地 v3 后端；原始音频通过现有 Keystore format4 的64 KiB块加密保存，每份先读回校验，最后发布唯一小头。相同 ID/摘要重读原候选，替换必须携带准确旧回执且先验证它。失败清理只涉及本次未发布代次，未知发布回执保留可能已提交的块。原 v1/v2 候选只读兼容，既有激活及新流式激活共用同一代次发布/回滚函数。

激活仍限无真实电脑的新安装，验证全部资料后才替换唯一原子指针。原录音/操作变为迁入只读；保留原操作ID、输入/结果字符串、目标、证书、清单、原准备/音频尝试标记与未知回执；更换 readerId，旧共享许可不成为授权，不调用 prepare/upload/发送/模型。重复激活只查原提交回执，之后草稿不被覆盖。

## 检查与限制

本工作线独立依赖和构建目录，app/runner编译通过；本机 JVM 以48 MiB堆运行实际编译的生产文件封装和KDF，32 MiB+17字节往返及错密码/末尾损坏/截断/追加/帧重排5项反例通过。探针仅为辅助证据；Android ICU只以JDK BreakIterator替身处理ASCII测试密码，没有模拟Keystore，不能替代API26。

`NativeMigrationStreamTest`准备10个原生场景：满额9,600,044字节/300秒WAV；三份满额聚合；五类损坏及候选中断；激活中断/重试；满额候选writer；独立PID冷reader；旧portable文件原候选和双向明确替换；已知子存储拒绝；未封口保护；迁入同ID原始WAV归属保护。默认原11字段保护沿用OwnedControlAcceptance，音频/全目录指纹检查采用流式摘要。原满额拒绝测试保留在旧API，不改成通过。Android真机/API26/API37和真实DEV均未执行；由主统筹拿准确设备/包/时间租约后运行，不能把编译或JVM结果升级为Android通过。

中央parity检查如实失败于缺少本工作线源码变更记录；请主统筹接入本目录 `parity-delta.json` 后重跑。共享progress/manifest/设计/验收表与生成目录未编辑。无新UI/文案，UI12的系统文件选择、忙碌/成功/失败/冷会话切换仍待集成；本工作线没有完整F47/F55/F64通过声明。

## 后续窄接口

- 大附件目前仍以legacy `draft-file:<id>` 单条Base64文本保存；本子阶段未声称20 MiB附件或所有聚合配额成功。需要共性存储下一阶段提供流式文本验证/二进制附件过渡；NativeDraftFiles业务调用由主统筹统一接线。
- 未封口WAV当前明确拒绝，保留原文件，不隐式seal。需要VoiceRecordingStore提供命名空间归属清单和捕获同步租约，录音生产者在开写到最终fsync/close期间持有；只拿存储锁无法证明原生录音器已停止。本阶段未改VoiceInputController/View。
- 收件箱/文档/照片/相机回调、payloads及相机outputs都是子命名空间，不能从root成功推断它们已导出；未知/不支持子资料仍需完成封闭清单后方可宣称整安装迁移。
- P8/P9交付版本化Schema/导出描述，由本线统一定义迁移。阅读账本 `mobile-reading-ledger-v1`来自P7阅读线：完整目标/已读位置/通知墓碑/readerId与重新授权必须校验；此分支未复制其他工作线未提交实现，目前未知记录会明确拒绝。

原未知业务操作保持封存，只查原回执；零新模型输入、零ADB/安装/instrument/force-stop/Xcode/原桌面操作。

## 第二子阶段：范围清单与原始 WAV 归属栅栏

新文件导出在写入前及原子提交前核对已知非空系统子存储：external-inbox、recent-photos、photo-permission、document-results、photo-results、camera-results，以及这些前缀下的嵌套payloads/outputs。只读目录清单，不调用AtomicFile恢复，不打开content URI或重启回调；存在资料即以具名scope明确拒绝，旧导出文件和原子存储不变。根索引中未封口录音另以provisional-voice拒绝，原始数据不读入内存、不修复、不seal。

这不是这些子Schema的迁移实现；也没有声称已发现任意未知前缀、未索引全局WAV或原始相机目录。完整安装迁移仍需可证明的注册/归属清单，未知资料不能靠当前根或有限scope检查升级为全量通过。

另外修复迁入UUID与原全局WAV目录的归属风险：VoiceRecordingStore拒绝迁入记录封口，删除迁入记录只移除其加密记录/音频和索引，不触碰可能仍属于源命名空间的同ID临时WAV。旧合法原录音的清理规则保留。新增三个原生场景只使用私有合成资料，设备执行仍待主统筹，不以编译计入通过。
