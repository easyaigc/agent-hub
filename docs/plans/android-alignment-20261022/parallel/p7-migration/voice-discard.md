# N069：本地语音丢弃终态

本增量实现 `NativeEncryptedVoiceDiscardPort` 和统一迁移图，尚未绑定实际 Session，也未在设备执行。它只把原录音设为不再提示恢复；没有远端取消、prepare、upload、重放或自动新录音。Controller、MobileSession、界面、Manifest 和中央覆盖表由统筹及语音负责人维护。

端口逐字引用 `a7bb5b5f265b9bcfd7b242db8c512230574cd527` 的 `NativeVoiceDiscard.kt`。依赖摘要和负责人 hook 保存在同目录 `n069-discard-port-dependency.json`、`n069-discard-owner-hook.json`。统筹去重端口，并在准确恢复后的 Session 和 storage 上绑定一次：

```kotlin
session.bindVoiceDiscardIfMissing {
    NativeEncryptedVoiceDiscardPort(VoiceRecordingStore(context, storage))
}
```

只有语音 owner 明确丢弃／重录的路径调用此端口。owner 先停止并等待原录音／转写工作，再等待匹配的 recordingId 和 fingerprint 确认；异常、超时或不匹配仍保留失败状态，不能开始替代录音。后台、导航、关闭界面和普通恢复不调用它。原执行已经发生但结果未知时，本地确认不宣称远端已取消。

## 存储与提交

根操作事务先核对原 ID、完整规范化目标、证书、模型、草稿、正文、创建时间与已知 manifest。Codex 历史账号字段继续遵从既有 RuntimeIdentity 规则。记录名和严格契约见 `voice-discard-contract-v1.json`、`voice-discard-catalog-v1.schema.json`。

每次首次尝试先加密保存原 JSON 的逐字快照并读回；已封口音频核对原长度与 SHA，未封口文件则按 64KiB 流保存准确 raw 证据并前后核对摘要。后者不修复 WAV 头、不截断、不生成 manifest；缺失和空文件有明确描述。单次 catalog 写入是唯一终态提交点，读回核对后才返回确认。提交前确定失败只清理本次未公布暂存，提交后确认丢失或状态未知保留可能已提交证据；相同原意图重试只读核对，不改密文。

`restore` 排除通过原快照与指纹核对的终态；损坏、未来版本及 pending sidecar 直接拒绝，不把失败当作无终态。`seal` 在触碰 raw 文件前拒绝终态，`remove` 保留终态的原记录、音频与审计。迟到原回执可以更新原记录的 phase/expiresAt，不能覆盖首次终态或原快照。既有录音记录没有保存完整返回转写文本／错误，本增量不声称可重建此前未持久的数据。

活动 index 仍限 32 条；开始新录音时移出已核验终态 ID，catalog 独立保留原行，最多 256 条，满额拒绝新增终态。所有文本／二进制仍使用既有加密存储；没有新数据库、明文迁移临时音频或第二套迁移器。

## 统一迁移

导出与校验遍历活动 index 和 catalog 的并集，必须闭合原行、逐字审计、原 sealed 音频或 raw 描述。孤立、缺失、损坏、未来版本、不匹配都拒绝整个归档，不发布部分候选。没有终态的 provisional 仍不支持迁移。已终态 provisional 只允许无 prepare/audio 尝试、无远端 phase 的原始资料。

原子激活沿用既有 generation 路径，当前原录音行加 `migrationReadOnly`，终态、审计及二进制准确保留。重开／再导出继续无恢复提示；导入不把这些资料变成执行授权。导入端不因相同 ID 而删除源命名空间仍拥有的全局 raw 文件。既有 8MiB 文本、21MiB 单 blob、192MiB 总原始资料及 256MiB 加密文件上限不变；256 条 catalog 容量不保证 256 份最大音频可同时迁移。

EasyWork `easywork-authorization-fence:<sha256(ref)>` 已登记为明确缺口，见 `easywork-authorization-fence-intake.json`。当前统一迁移拒绝 EasyWork 设备授权及任何此类未知记录，不能只恢复 grant 漏掉撤销围栏，也不从围栏恢复新设备 authority。非空 foreground v1 journal 仍是此前明确拒绝的缺口，完整迁移未关闭。

## 验证与交付责任

`NativeVoiceDiscardStorageTest` 的 10 方法覆盖真实加密 storage、未封口准确证据、不可变目标、写入故障与丢失确认、损坏／sidecar、迟到回执、统一 file 迁移及 32 条活动容量回收、EasyWork 围栏拒绝。`NativeVoiceDiscardColdTest` 的 2 方法要求分别运行 writer／reader，第二次必须 PID 不同并核对 namespace、原密文、终态、审计与音频；参数为 `voiceDiscardFixture`，只接受 `voice-discard-cold-<16位小写十六进制>.json`。

这 12 方法在本线只编译，未执行 Android instrumentation、真机／模拟器、Host、真实模型或 iOS。冻结源码、包与检查见 `voice-discard-source-audit.json`；最终组合必须重新记录摘要后由统筹执行 owner 绑定、失败不启动替代、导航恢复、独立进程及迁移后不再提示的原生场景。系统同一原文件转写、视觉／双语／无障碍及真实 DEV 仍分别开放，A6 不恢复。
