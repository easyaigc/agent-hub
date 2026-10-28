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
