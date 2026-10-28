# 主统筹原生验收入口

安装身份应为 `com.easyai.agenthub.android.acceptance`，runner为同身份 `.test/androidx.test.runner.AndroidJUnitRunner`。使用本候选app与runner的准确SHA、设备/时间段租约；本线未执行下列命令。每个方法单独运行以保留原240秒门槛与分组证据，不把JVM或旧5r/5s结果混入本次通过。

测试类 `com.agenthub.android.NativeMigrationStreamTest`：

1. `fullFiveMinuteWavStreamsThroughExportCandidateAndAtomicActivation`
2. `threeMaximumWavsUseAggregateRawQuotaWithoutBase64OrWholeAudioAllocation`
3. `corruptTruncatedTrailingWrongPasswordAndInterruptedCandidateKeepOriginalSlot`
4. `eachActivationFailureKeepsOriginalFilesAndCandidateThenRetryPublishesOnce`
5. `stageMaximumWavForIndependentColdReader`
6. `activateOriginalMaximumWavInIndependentColdReader`
7. `legacyPortableFileReadsOriginalThenExplicitReplacementWorksBothDirections`
8. `knownSystemChildrenRejectFileExportWithoutDiscardingOrRecoveringTheirOriginals`
9. `provisionalWavExportRejectsWithoutSealPrepareOrRawMutation`
10. `importedRecordingCannotSealOrDeleteSameIdRawFileOwnedBySourceNamespace`

第5项会在 `files/acceptance/P4-owner-callbacks/<ownerCallbacksRun>/migration-stream-<nonce>/stream-cold-writer.json` 产生自己namespace、writerPID、原候选/音频/源密文指纹。writer实际进程退出后，只复制这份本地合成fixture到同一包的 `files/acceptance/activation-cold-<16位小写hex>.json`，以 `migrationActivationFixture` 参数给第6项，必须确认readerPID不同。此fixture不含真实资料或账号，不重建候选、不更换ID。第6项只读/激活原候选并校验9,600,044字节WAV、未知回执、只读限制、后续草稿和原源密文。

第1项的 `maximum-stream.json`记录实际heap限制；API26必须核对50,331,648字节堆和完整成功，而不是原旧接口的提前拒绝。默认原件保护在每项结束的 `default-storage-proof.json`；原始失败、超时、完整包/源码指纹分别保留。其余旧迁移兼容用例按变化补跑，至少覆盖NativeMigrationCapacityTest、NativeMigrationVoiceTest、NativeMigrationActivationTest、NativeMigrationPublicationTest及CryptoTest；不因此声称系统UI、iOS、Windows或真实模型通过。

## 冻结 F53 声音迁移补充

`NativeMigrationSoundTest` 的 7 个方法仅编译完成，待主统筹以准确 app/runner 与设备租约逐项执行：

1. `maximumNormalizedSoundAndHalfStatesMigrateColdWithDestinationUriAndImmutableOriginalAudit`
2. `absentStagedAndRetiredBytesRemainExplicitAcrossActivation`
3. `missingActiveCorruptPendingAndUnindexedBytesRejectWithoutSourceRewrite`
4. `authenticatedInvalidNormalizedBytesNeverPublishOrReplaceOriginalCandidate`
5. `missingRetiredOrUnknownSoundReferencesRequireExplicitRepairWithoutChangingReceipts`
6. `futureCatalogAndPermissionFieldsRejectWithoutReplacingCandidate`
7. `eachActivationInterruptionRollsBackSoundGraphThenOriginalReceiptPreservesLaterEdits`

第1项使用最大 1,278,944 字节归一化音频及存在的 staged/retired 文件，验证原目录、摘要、未知通知审计和禁用状态；第2项验证缺少 pending 文件仍保持未提交状态；第4项提供具有正确摘要及外层认证但格式非法的文件，必须在发布候选前失败且原候选保持有效。缺失引用显式修复测试只修改声音偏好，比较原始 receipts 原文不变。`sound-migration.json` 明确记录 `systemProviderReadVerified=false`，URI 构造和本地读取不能替代系统读取、播放或送达。

另需主统筹执行 API26 48 MiB/物理环境、原默认存储保护及 P9 实际系统 provider/渠道/声音场景；中央 manifest 和功能/设计表更新由主统筹完成。完整应用迁移仍有其他已登记边界，不以本组通过声称 F47/F53/F55/F64 完整通过。

## policy3 独立墓碑与2048活动收据边界

`NativeMigrationNotificationCapacityTest` 4项只编译，待主统筹执行：

1. `legalMaximumSourceKeepsAllOriginalsAndEveryActiveSlotAvailableForNewEvents`
2. `oldPolicy2FullSentinelFailureReproducesThenFreshMigrationUpgradesWithoutRewritingOldOriginal`
3. `normalActiveCapacityAndTtlAreUnchangedWhileImportedIdsSurviveTtlAndSecondMigration`
4. `futureMissingTruncatedOrExtraTombstoneIndexRejectsBeforeReplacingCandidate`

第1项真实生产v1 decoder接受2048合成原收据，迁入后的活动receipt为0、独立索引2048、原审计文本完整，新事件可以claim。第2项先用生产Store复现旧policy2永久占槽的容量失败，再从准确旧来源做新候选，旧源密文/审计条目不变。第3项保留正常2048活动上限和TTL回收，并证明旧事件不因TTL/再次迁移恢复发声资格；第4项校验未来版本、缺失、漏/多/重复索引及未知字段拒绝，claim对未知版本无写入。

`NativeMigrationDeviceStateTest`原6项更新为policy3；首次激活实际增加2条准备记录，回滚覆盖两个新增记录及提交前边界。原`77458db4`与旧APK作为失败候选保留，不改写或宣称其满额通过。旧policy2已激活的相同回执不会因代码升级自动重写；新候选升级与普通运行兼容分别记录。无设备/OS post/Host/模型执行。
