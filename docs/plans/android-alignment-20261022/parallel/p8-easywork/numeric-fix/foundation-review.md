# iOS/Foundation 只读数值门槛对照

审查对象为本分支 `EasyWorkTransport.swift` 中 `EasyWorkEnvelopeCrypto.open` 的数字条件：`version as? Int == 1`、`sequence as? Int > 0`。`foundation-numeric-review.swift` 在 macOS Foundation 上执行这两个相同条件，先核对源文件仍含对应表达式，再对相同 33 个数值 token 和 2 个独立 Node 正边界帧的数值元数据（共 35 组）比较严格预期。没有修改或启动 iOS App，没有执行设备、加密解密、传输、账号/密钥或模型任务。

本次观察到四个门槛差异：

| 向量                             | 当前 Foundation 条件 | 严格预期               |
| -------------------------------- | -------------------- | ---------------------- |
| sequence = `true`                | 桥接为 Int 1，通过   | Boolean 拒绝           |
| version = `true`                 | 桥接为 Int 1，通过   | Boolean 拒绝           |
| sequence = `9007199254740992`    | 正 Int，通过         | 超出最大安全整数，拒绝 |
| sequence = `9223372036854775807` | 正 Int，通过         | 超出最大安全整数，拒绝 |

这些是认证前的数值条件观察。尤其 sequence 布尔在 header 中仍保留其原类型，不能从本表推断完整 AES-GCM 解密会通过；本线没有执行这种试验，也不宣称 iOS 真机缺陷已验证。version 的 header 当前使用常量 1，是需要相应负责人进一步核对的来源差异。

建议父线安排 iOS 负责人对 NSNumber Boolean 与普通 Number 做明确区分，核对 finite/integral/安全上限，并用同一批合法 Node/legacy 帧与类型负向向量验证。不得直接把 Android 修复、macOS Foundation 条件结果或历史 iOS 结果提升为 iOS 产品通过。A6 及业务暂停不变，本线不继续修改 iOS。
