# 用户语音默认：真实加密存储与迁移

冻结依赖 `b2a4f589db49e4555f1c9679d9f6e32d9d1856aa` 已通过依赖 merge `d4176347` 导入。冲突采用父线的缓冲 GCM、短 namespace 与 P9 异步设置版本；原前台迁移9测试及 claim 保护与依赖逐字一致。该 merge 只供本线编译，不整体回挑给父线，也不是 stage4/主线验收通过。

`NativeEncryptedVoicePreferenceStore(storage)` 实现既有 `NativeVoicePreferenceStore`。唯一小记录 `native-voice-preference-v1` 为 `{"version":1,"defaultProvider":"desktop"}` 或 `system`，UTF-8最多256字节。严格拒绝重复字段、非整数版本、未知枚举、额外字段、未来版本/记录名、损坏、超额与待恢复`.bak/.new`。读取不写默认值；无记录返回null，由设置层保持desktop。保存先校验已有原件，不隐式修复；相同有效选择不重写密文。保存与读取在IO执行，使用同根事务、原加密AtomicFile和代次栅栏，等待锁后再次核对取消；已提交的原子选择不因晚到取消回滚。

只保存用户选择，不保存系统服务/语言模型/网络能力、授权、活动录音、操作、目标、草稿或自动启动。改变默认只影响后续明确开始的语音动作，不改变已有捕获或原始未知操作。

## 精确绑定入口

父线在Main线程选择好已恢复的storage与Session后，对这个Session只绑定一次：

```kotlin
session.voiceSettings.bind(NativeEncryptedVoicePreferenceStore(storage))
```

不要从重组的Page重复绑定，不使用跨Session单例，也不要把旧store交给新安装。语音owner负责其独立的unbind/rebind生命周期；父线在迁移前停用旧设置写入，等待其已进入的原子保存结束，迁移后重新打开storage并绑定新Session。adapter没有另起后台队列；保存成功才返回，失败继续抛出。NativeVoiceSettings原有late-load/revision/coalescing保护仍由原owner维护。本线未编辑VoiceSettings、MobileSession、VoiceSettingsPage、Manifest或中央表；真实App绑定还需父线完成。

## 迁移优先级与 iOS 对照

已接入同一个库存/候选/激活图，旧有界入口与文件流入口均严格验证。目标有有效记录意味着目标已有显式选择，逐字保留；目标缺失才采纳源显式选择；双方缺失不物化desktop。源选择仍留在原候选与原密文中，不因目标优先而改写候选。目标未知/损坏也不能被源默认静默覆盖，须失败保留。新增目标保留记录纳入原prepared内容摘要、source/actual计数、原子指针、回滚和幂等回执；旧store不能越代覆盖后来选择。

依据为当前iOS源码：VoiceTranscriptionPreference位于UserDefaults；SavedSession、EncryptedMigration.Payload和restoreMigration都未搬运或重置此键，目标显式选择原本保留。Android新增记录只在目标尚未选择时导入源默认，是本次新增能力。iOS枚举为desktop/local，Android为desktop/system，不能把iOS的local字符串当作本记录合法值；当前iOS未知UserDefaults值回退desktop，而本Android记录严格拒绝，两端差异显式登记，未修改iOS或宣称一致性通过。A6仍暂停。

## 原生准备与真实限制

`NativeVoicePreferenceStorageTest`共10方法，均未在本线设备执行：

1. `missingPreferenceIsReadOnlyAndRealBindingKeepsDesktopWithoutCreatingARecord`
2. `bothChoicesAreEncryptedAtomicAndReadFromAReopenedHandleWithSameValueRetryReadOnly`
3. `corruptFutureEnumsAndPendingFilesPreserveOriginalsAndRejectLoadSaveAndExport`
4. `actualSettingsBindingReportsFailureInsteadOfPretendingACorruptChoiceWasPersisted`
5. `sourceChoiceMigratesWithoutRetargetingOriginalUnknownOperationsOrStartingVoice`
6. `destinationExplicitChoiceWinsAndMissingSourceNeverCreatesAnImplicitDesktopRecord`
7. `invalidSourceCandidatesCannotReplaceTheOriginalAndFutureRecordNamesAreNotSkipped`
8. `targetChoiceAndCandidateSurviveInvalidTargetRollbackAndStaleWriter`
9. `stageUserDefaultForIndependentColdReader`
10. `readOriginalUserDefaultInIndependentColdReader`

前8项使用真实加密storage/adapter与既有迁移，覆盖失败不假报persisted、原unknown intent/result不改、目标优先/缺失矩阵、回滚及旧代写入拒绝。没有recognizer、权限/能力探测、录音、实际Host或模型调用；“重开handle”只是辅助，不能等同进程冷重启。

第9项在本次owner目录生成`voice-preference-cold-writer.json`，含合成namespace、原PID和密文指纹；writer真实退出后，父线仅复制这份fixture到同包`files/acceptance/voice-default-cold-<16小写hex>.json`。以`voicePreferenceFixture`参数提供文件名给第10项；后者要求reader PID不同、namespace规范、全部密文不变，并用真实adapter绑定新NativeVoiceSettings读出system。不得新建替代偏好冒充原冷读。准确app/runner/设备/时间租约和默认11字段保护由父线执行。

构建、格式与源码指纹为辅助证据，不能替代这些原生结果、真实ASR或完整DEV。非空通知v1 journal迁移仍明确拒绝且未完成；不以拒绝项计迁移成功。父线负责统一真实绑定、中央parity/设计/验收登记和实际设备运行。
