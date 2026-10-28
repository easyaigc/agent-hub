# 旧 inline 附件：认证边界与未完成项

本阶段基于 `7f1eb8426f75826e63d6b1364bcca1c1d12c6da7`。结论是**旧满额附件的48MiB流式迁移仍未完成**；本提交提供准确限制、原件保护和可复验失败，不能用拒绝或测试期望拒绝记作迁移通过。

## 合法历史写入与迁移配额分开

历史 `NativeDraftFiles` 从 `66dee5b2915a6ecedcd9d35458251a04da78fe13` 起允许最多20MiB，之后用file/bytes JSON将Base64交普通NativeSessionStorage.write。已核6个版本（66dee5b2、ce7b7ebe、7d4b19af、f0cc0f9c、1e908bea、7fc712d4）的写入方法；普通write/encode没有8MiB文本上限。8MiB payload/12MiB envelope属于后来的迁移边界，不能倒推旧满额原件非法。

因此20MiB是**合法可存在的旧输入**。成功持久化需要当时环境有足够内存；本次没有查用户文件，也没有证明旧App曾在48MiB进程成功写入满额。原生fixture增量生成相同序列化/认证封套用于恢复读取复验，不能当作旧写入路径在低堆通过。

## 实际封套

| 层/版本                     | 内容与认证                                                                         | 本次边界                                         |
| --------------------------- | ---------------------------------------------------------------------------------- | ------------------------------------------------ |
| 本地crypto1                 | 1字节版本、12字节nonce、一次GCM密文/tag；AAD为准确logical name，内容是完整UTF8文本 | 满额旧file/bytes无法独立分块认证                 |
| 本地crypto3                 | 相同外层；加密明文内为name长度/name/完整UTF8文本，AAD为record-v3域                 | 加密name帧仍属于同一个GCM消息                    |
| 本地crypto2/4 binary        | 既有有界认证块；crypto4另有认证name/size头                                         | 先前新v2附件/封口录音可用，不能据此读取旧单tag源 |
| NativeDraftFiles本地record2 | 小型file/storage/payloadId描述符 + 独立crypto4原件                                 | 上阶段新存储，不是旧inline原件的既有格式         |

AOSP的[API26实现](https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/keystore/java/android/security/keystore/AndroidKeyStoreAuthenticatedAESCipherSpi.java)及本机SDK37源码均在GCM解密时缓存输出，到认证成功才复制返回整段结果。`CipherInputStream`或输入分块不能去掉提供者内部缓存。Android文档说明[Keystore密钥不可导出且受用途约束](https://developer.android.com/privacy-and-security/keystore)，生产key只授权GCM，不能换成软件key来读取相同旧源。

推导：20,971,520字节原件的canonical Base64为27,962,028字节，另有JSON/name帧。上述实现的认证缓冲区加结果副本，仅按2倍明文下界就超过50,331,648字节堆；增长容量、输入和App状态还未计入。这个数字是**审计实现的下界推导**，不是已测设备峰值。当前标准API未提供满足本任务约束的认证后有界流；没有发布未认证前缀、分配完整Base64/String、改旧原件、扩堆或复制密钥来制造通过。

## 已实现保护

`NativeMigrationLegacyGcm.limit`只根据观察到的版本和密文长度输出未认证事实与审计下界。`LegacyGcmAuthenticationLimit`是原IncompleteMigrationInventory的子类；保留现有8MiB文本+最大name帧上限，在解密/全值分配前具名拒绝，目的AtomicFile尚未开始。诊断明确authenticationAttempted=false，不把源格式/内容认证为真。损坏大源也只按边界拒绝，不能称其GCM校验通过。

小型历史记录继续原认证/解析路径；原普通read/write、8/12MiB字符串API、新binary大文件路径、原UUID/manifest/摘要/意图/未知回执均不改。本阶段不是满额旧源的变换器；旧满额资料必须继续保留为未支持，而不能静默转成空记录。

## 主统筹原生复验

`com.agenthub.android.NativeMigrationLegacyGcmTest`准备5项，逐方法执行并保留每台源/包/runner/PID/实际heap：

1. maximumFormatOneInlineRemainsUnsupportedWithExactFailureAndOriginalCiphertext
2. maximumFormatThreeInlineRemainsUnsupportedWithExactFailureAndOriginalCiphertext
3. corruptedMaximumSourceIsNotAuthenticatedRepairedOrPublished
4. boundedHistoricalRecordsStillAuthenticateAndImportWithoutSourceRewrite
5. actualKeystoreUpdateWithholdsPlaintextUntilWholeMessageAuthentication

满额fixture以64KiB原件循环、增量Base64编码和真实Keystore加密写出旧单tag密文，没有20MiB夹具ByteArray或大型字符串。前3项验证准确失败、所有原密文/原意图/未知记录及已有导出保留；结果文件fullLegacyMigrationPassed明确为false。第4项只覆盖小型旧v1/v3兼容。第5项只用64KiB原件观察实际provider的update零输出、doFinal完整输出、坏tag拒绝和不可导出key；不能升级为满额通过。默认11字段保护继续。

编译和48MiB JVM策略算术辅助检查通过；本线没有设备/Host/模型操作。Native提供者行为及全部原件保护场景待主统筹执行。下一独立阶段按明确授权合入指定父线依赖，推进阅读/通知共性Schema；此限制不阻塞其他可做范围。
