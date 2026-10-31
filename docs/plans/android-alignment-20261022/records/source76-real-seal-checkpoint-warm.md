# Source76：真实 seal 具名边界的已知中断 warm 验收

2026-11-01，阶段51，关联 F47/F55/F56/F64。Android API33 的一个真实本机 seal 具名边界方法限定通过；安装100份、Native99份共199份捕获由主统筹独立核对。它不是实际 row I/O 故障、断电、进程死亡、冷读取或完整 P7 通过。

## 变更与实际流程

保留公开双参数 VoiceRecordingStore 构造器和默认 seal 算法；仅增加内部、独立 debug 验收包可用的具名边界，核对实际 package／UID／debug 身份后才允许构造副作用。回调固定在加密 audio 写入完成、row manifest 提交之前；没有任意阶段／方法透传或生产恢复入口。

本次使用 synthetic PCM 调用真实 seal 函数一次。真实 WAV 标准化和奇数尾部裁切已发生，结果为131116字节；具名 callback只触发一次并抛已知IOException，row写入尚未提交。因此 `realSealFunction=true`、`namedCheckpoint=true`、`knownInjectedInterruption=true`，而 `realRowIoFailure=false`、`realPowerLoss=false`、`realProcessDeath=false`、`rowWriteSubmitted=false`。不能把具名注入异常写成真实磁盘失败或崩溃。

原 row/index密文保持；raw在真实seal中已按既有算法标准化，后续保护使用准确的中断后raw/audio与九stat基线，不能声称seal前raw字节未变。异常返回后重新取得准确raw freeze，随后实际执行export／stage／activate／re-export；Source75 v2惰性catalog保留两份原件、原row/index，目的设备不恢复活动recorder，后来的独立草稿保持。原seal和业务操作均未重放。

## 本次指纹与证据

- canonical：`9b7067bf9e2a57d512a229e557aeedb5d17b5fd86ada45abe9135c4d4fae5b57`（856输入）；expanded：`b99e5068ed1d94fe6d1311a76ecf1554fbbcaaa947aafba0fc39433a2b1f0574`（869输入）。
- App：`107f5479d5d3122b5547857a5e7ad8da8efaa3c7c713d276a74ed045461eb81d`。
- Test：`597dab0a01a7bd3b75e45ea9af612faed46d9742b4a2fd1db3dc16b41ba1a88b`。
- SDK主统筹核对：`5ee812c74e5bc528e2e4a54e8c42c1c009e55261b3bdb71009560483bbaa60f2`；实际Gradle退出0，实际与冻结输入、三APK／ZIP／CRC／单v2签名及12份SDK工具原始流核对通过，旧27APK与7802份冻结源码保持。
- 安装主统筹核对：`c20eb3567794ebb02cc364710c63751efc5b4fefea3d14d525a39edd38fe5529`，92次设备＋8次本机守卫；不卸载、清数据或删除密钥。
- warm主统筹核对：`2c7fd96792485199ac906aefbb8ba03fb35cda86ea064793d388e31626652f4f`；准确方法 `NativeMigrationSealCheckpointTest#realSealNamedAudioCommitInterruptionExportsBothOriginalsInertWithoutReplay`，91次设备＋8次本机守卫，原始终态、完整类型化目录、regular字节与九stat独立读回。

37个既有准确根与旧两个FIFO元数据保持；FIFO没有被读取、提供peer或删除。warm新增一个夹具根，最终files462／no_backup91；58项变化为57个新成员和acceptance父目录链接变化，未引入未知业务资料。新增夹具原件保留作本轮对照和后续只读对账；三PID场景将使用新独立原件，不重放本run；私有handle全部关闭、物理executor终止、全进程FD、UI及physical DEV证明仍为false，旧失败未升级。

## 双端影响与后续

Android惰性v2迁移格式与既有算法不变，未改产品页面、设计token或文案。iOS当前迁移Payload未包含PendingVoiceStore音频与metadata，本轮不新增iOS实现或通过；UI21完整交互仍待验。

下一阶段先推进P9长导入铃声的预览／删除动作布局和读屏名称，再推进同候选三PID原件／原回执冷读取。实际row I/O故障、断电／进程死亡、mixed v1/v2、多份quota及48MiB低堆、完整24组／系统UI／物理手机和真实DEV继续待验。任务／项目管理、F75原生项目UI和iOS A6仍暂停；未知结果不重放，既有失败与原件独立保留。

本轮没有新增模型输入。当前APK、冻结源、准确新夹具及模拟器只因后续原件／冷读取准入继续保留；已替代且无依赖的可重建产物按精确归属清理。Source77、三PID和其余待验均未由本记录计作通过。
