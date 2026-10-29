# P9 原 bootstrap 跨冷启动只读恢复契约（独立提案）

父任务 operation：`parent-p9-source46-newboot-explicit-proof-contract-20261029-1`。本补丁仅新增两个 androidTest 文件；父线负责目标设备重启、读取原始输入、合入及真实执行。本工作线没有设备、安装、通知、Host、模型或业务操作。Source46 是编译兼容基线，不能据此推定当前设备 boot。

旧 `NativeNaturalBootstrapUpgradeReadOnlyTest`、`prepare-upgrade-plan.py` 和旧向量完全不变；旧 sameBoot selector 继续要求原 BOOT_COUNT，父线必须独立核对原 kernel bootId。重启后的新 boot 不得用于旧 selector，也不得改写旧设备证明、bootstrap 原始回执或成功日志。

## 独立输入及不可变意图

新工具：`scripts/mobile/fixtures/native/coldboot/prepare-coldboot-plan.py`，只读取本地文件，输出以 O_EXCL / 0600 创建、fsync 并读回核对，已有输出拒绝覆盖；无 subprocess 或设备调用。原证据与当前观察源分别保留 SHA-256。

输入 JSON 严格字段：

```json
{
  "version": 1,
  "originalIntent": { "path": "<absolute raw write-intent.json>", "sha256": "<sha256>" },
  "originalCompleted": { "path": "<absolute raw completed.json>", "sha256": "<sha256>" },
  "originalDevice": { "path": "<absolute unchanged original device proof>", "sha256": "<sha256>" },
  "originalBootstrapLog": {
    "path": "<absolute original one-pass bootstrap log>",
    "sha256": "<sha256>"
  },
  "originalCiphertext": { "path": "<absolute raw fixed-record ciphertext>", "sha256": "<sha256>" },
  "currentDeviceProof": { "path": "<absolute separate current observation>", "sha256": "<sha256>" },
  "currentBootIdRead": {
    "path": "<absolute raw current kernel boot-id read>",
    "sha256": "<sha256>"
  },
  "currentBootCountRead": {
    "path": "<absolute raw current BOOT_COUNT read>",
    "sha256": "<sha256>"
  },
  "currentInstalled": {
    "uid": 10000,
    "appSha256": "<sha256>",
    "runnerSha256": "<sha256>",
    "signerSetSha256": "<sha256>"
  },
  "recoveryRun": "<fresh canonical UUID>"
}
```

原 `originalDevice` 仍是原来的五字段对象 `{serial,avd,api,bootCount,bootId}`，不得补标或更新。父线在实际新 boot 通过固定只读采集生成新的 `currentDeviceProof`，结构如下；原始 read 保留原字节、单个尾换行可选，禁止把历史字段改成当前值来制造观察证据。

```json
{
  "version": 1,
  "role": "current-coldboot-readonly",
  "source": "parent-fixed-readonly-device-v1",
  "observationId": "<fresh canonical observation UUID>",
  "device": {
    "serial": "<same target>",
    "avd": "<same AVD>",
    "api": 37,
    "bootCount": 2,
    "bootId": "<exact observed current kernel UUID>"
  },
  "bootIdReadSha256": "<raw current kernel boot-id SHA256>",
  "bootCountReadSha256": "<raw current BOOT_COUNT SHA256>"
}
```

上述路径只用于父线本地离线准备，不传给 Android。SHA 是内容固定，不是设备身份的密码学签名；父线实际固定只读采集和前后设备独立核对是信任边界。工具不能凭文件声明证明设备正在运行，native 也不伪称能读取 kernel bootId。

原成功日志必须是一个实际 `initializeEmptyDefaultThroughProductWriteOnce` 通过，原正文只写一次，原始 intent/completed 的哈希链接、bootstrapId、UID / APK / runner / signer tuple 一致。原设备和当前设备必须同 serial / AVD / API37；当前 bootId 与原值不同、BOOT_COUNT 严格更大并精确固定。相同或降低计数、同 bootId、任意 boot 开关、其他设备、UID 或证书均拒绝。APK tuple 可不变，也可升级；不是“任意新 boot”授权。

原密文只允许固定记录 `p9-native-local-bootstrap-v1`，文件为 `mobile-v1/<sha256(recordName)>.bin`，29–8192 字节，原 Source42 v3 编码。当前最窄范围要求原完整 content-hash 清单只有一个 family、一个目录、一个记录。工具按现有清单摘要算法重建原 familyDigest，证明输入密文与原完成回执固定的字节完全一致。密文可以在重启后只读导出：其信任来自不变的原 familyDigest，而不是把导出时间宣称成原采集时间。其他根、额外记录或旧编码拒绝，不做迁移。

新 `recoveryRun`、观察 UUID、bootstrapId、两个 bootId 必须互异。父线须真正生成新 UUID，不复用任何旧 lease / attempt / operation。工具输出 canonical Base64 单 argv 令牌及 SHA；新 native selector 在 `files/acceptance/P9-natural-coldboot/<bootstrapId>/<recoveryRun>-intent.json` 独占保存原 plan 字节，存在即拒绝，不覆盖。失败或结果未知仍保留该意图，不能重放旧业务操作。

## 新 selector 与密钥证明

唯一 selector：`com.agenthub.android.NativeNaturalBootstrapColdBootReadOnlyTest#restoreOriginalBootstrapOnExplicitCurrentBootReadOnly`；参数 `p9BootstrapColdBootPlanBase64` / `p9BootstrapColdBootPlanSha256` 来自新工具输出。原 120 秒预算不变。缺参跳过不构成通过。

- acceptance/debug 范围、API37、无 MainActivity、用户存储解锁；实际安装 tuple 在前后均等于当前计划，UID / signer 等于原 tuple。
- 原 Fence 以原只读逻辑读取三文件及零字节 owner.lock，原始回执哈希和字段均固定；不初始化 bootstrap。
- native 实际 BOOT_COUNT 在前后均等于新计划的当前计数；kernel bootId 明确记录为 **未由 native 检查**，父线须在前后核对当前 exact UUID。
- 现有 AndroidKeyStore 中恰有一个安装 alias，直接 `getKey`；不生成、删除或替换 key。仅 alias 存在不算连续性证明。
- 新 test-only helper 用现有 key 以固定原 v3 AAD 认证完整原密文，校验固定记录名和 JSON `{version:1,purpose:"p9-native-key-bootstrap",bootstrapId:<original>}`；payload 数组 finally 清零，不记录明文、key 或密文。
- 不构造 `NativeSessionStorage`，不调用可能恢复 AtomicFile 备份的产品读取；不执行写入、迁移、缓存恢复或默认重建。AES-GCM 成功认证固定原字节，证明现存 key 仍能读取原记录；不宣称能导出硬件 key 指纹。
- 原 ciphertext 在前后读回一致；完整默认 familyDigest、原两个 raw 回执和锁长度不变。仅新增独立 acceptance intent / result 证据，不写产品数据。证据 suite 为 `bootstrap-coldboot`，通过现有证据规则 append-only 保存在原 bootstrapId 下，区分原凭据和本次观察。

## 父线真实验收门槛

父线在原目标保留数据重启后，先完成实际设备身份、kernel bootId / BOOT_COUNT 及当前安装 tuple 的固定只读采集，再准备新计划。selector 执行前后两次独立设备证明必须精确等于计划的 `currentDevice`，原证明保持原 SHA。

只执行新 selector，需一个实际 JUnit test 通过、终码 -1、无 skip / crash / failure；custom status 2 本身不算通过。保存 stdout、native 持久化结果和不可变 intent，逐一核对 planSha / 新 recoveryRun / observationId、当前 tuple、新计数、原 receipt / ciphertext / family SHA；`existingKeyAuthenticatesOriginalCiphertext`、原字节不变、产品写入 0 均须成立。任何 native key 不可用、认证失败、额外默认数据、boot 漂移或缺证据都阻断，不能通过重建、删 key、回填原 boot 或调整旧回执修复。

本次只读恢复不解封旧未知 operation，不重发 SDK 通知。后续自然 producer / observer 必须使用绑定当前 boot 的全新 lease 和 attempt，走原完整准入、8 项拒绝及独立进程核对。task/project/A6 继续暂停。

## 辅助证据与双端影响

本工作线 35 个离线新契约边界向量、14 个旧 sameBoot 回归向量、149 个实际固定 v3 解码器 JVM 检查通过；JVM AES 使用合成 key，不能代替 AndroidKeyStore、冷启动或设备验收。冻结 Source46 加两个测试文件的 Android test APK 编译记录在本次树外 `verification-receipt.json` 和恢复索引中。无当前 boot 真实输入，原生验收为未执行。

功能关联 F52 / F53 / P9：仅 Android acceptance 契约新增，产品通知 UI / 系统通知 / 文案 / iOS 生产行为无变化；共同断言“未知输入不重放、数据不变、目标隔离”保留。父线将本目录 parity delta 归并到正式 product/design/acceptance 账本；本补丁没有以编译或旧 iOS/Android 结果替代新冷启动通过。
