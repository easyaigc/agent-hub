# P8 数值校验窄修复 checkpoint

从 `bf5d1a7aeaaea4623078973f8e58ca70e288e7ab` 继续。生产修改仅限 `EasyWorkCrypto.kt`；原七组 16 份档案、原生准备 checkpoint 的 7 份档案及旧缺陷探针保持原字节。原 audit.mjs 固定历史共同起点，不在修复候选上改写或当作当前通过门槛。原 15 个原生方法正文不改，只新增 `strictInboundNumbersRejectWrongTypesBoundsAndVersion`，全类现为 16 方法。

`open` 在构建 AAD/返回回放序号前，将原始已解码值交给同一私有函数：必须是 Number、有限、无小数部分且在 `1..9007199254740991` 内。保留精度的 Number 还做精确 Long 转换与数值一致性检查；version 必须为整数 1。验证结果一次传入 header，header 不再用 `getLong/getInt` 把外层值有损转换。合法整数仍产生原 v1 认证字节，legacy sender-session 兼容不变；没有变更密钥、算法、payload、协议版本、回执或重放策略。沿用原错误文案，没有增加共享 UI 文案。

新的宿主正确性验证与历史 `numeric-probe` 分离。`validation.init.gradle` 只选择原 10 项安全回归和新增 2 项验证，不编入旧缺陷探针；**旧探针的 PASSED 只代表旧基线缺陷复现，不能在本修复中充当通过证明。**

[numeric-vectors.json](numeric-vectors.json)由独立 Node 加密实现生成两个有效帧，序号分别为 1 和最大安全整数；另有 33 组原始数值 token（6 合法、27 拒绝），并检查两个缺失字段。Node/legacy 原帧继续保留。验证针对 JSON 解码后保留的 Number 值，不引入原始 payload 的另一次解析或重编码。

新增原生方法嵌入同一组向量，使用真实 Android JSONObject/Base64 和生产 crypto；错误值必须在数值/JSON 校验阶段拒绝，不能以 GCM 错误或超时算通过。它仍有 12 秒方法上限。主统筹可单独选择旧 `nonIntegralSequenceCannotReuseAuthenticatedIntegerHeader` 和新方法作为 2 方法数值组，外层期限不超过 240 秒；其余原 14 方法维持旧分组。全类 16 方法的 JUnit 上限之和为 192 秒，每组小于 20 方法。本线没有执行任何 instrumentation。

[checkpoint.json](checkpoint.json)保存准确方法、源码/App/runner/向量指纹与状态；[verification.json](verification.json)区分当前宿主通过、原生编译、中央登记待接入及原生未执行。父线正在做的未修 baseline 原生失败应保留原包及 runner，不能用本修复产物覆盖该输入。

只读 iOS/Foundation 审查见 [foundation-review.md](foundation-review.md)。观察到的 4 个差异仅为纯宿主数值门槛结果，未改 iOS 产品或执行 iOS 设备/GCM/真实任务。双端差异须交父线与相应负责人决定后续修复。

复现（编译/Swift 命令需先取得统一两槽锁，输出使用私有树外位置）：

```sh
node docs/plans/android-alignment-20261022/parallel/p8-easywork/numeric-fix/make-vectors.mjs
P8_NUMERIC_VALIDATION_OUTPUT=/private/evidence/fixed-numeric-result.json \
node scripts/mobile/gradle.mjs :android:testDebugUnitTest \
  --tests com.agenthub.android.NumericEnvelopeValidationTest \
  --tests com.agenthub.android.P8EasyWorkBoundaryTest \
  --init-script docs/plans/android-alignment-20261022/parallel/p8-easywork/numeric-fix/validation.init.gradle \
  -PagentHubAcceptance=true
```

上面的绝对路径为占位路径。[parity-delta.json](parity-delta.json)提供中央 change 记录及双端影响；本线不修改中央清单、架构总表、其他 EasyWork 生产文件或 PR。原生/API26/真机及完整真实 DEV 仍由父线按组合源码/包/租约验收，A5 七组没有封存。
