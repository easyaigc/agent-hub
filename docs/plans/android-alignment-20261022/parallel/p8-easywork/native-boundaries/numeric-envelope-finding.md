# P8-NATIVE-NUMERIC-001：认证头序号的有损转换

当前生产源码没有修改。**宿主兼容 JSON 已实际复现；Android instrumentation 尚未运行。** 本记录交给父线评审，不能写成 API26/真机缺陷已现场确认，也不能把探针通过写成安全边界通过。

复现输入是既有独立 Node 加密向量中的合法 `frame`，其认证序号为整数 `42`。只将外层 JSON 的 `sequence` 改成数值 `42.5`，保持 nonce/ciphertext/其他字段不变，再调用当前生产 `EasyWorkCrypto.open`。宿主 `org.json:json:20250517` 上结果为：解密成功、返回序号 `42`、正文与原 Node payload 一致。树外结果 `numeric-probe-result.json` 和对应 JUnit/log 记录该观察。

来源边界为 `EasyWorkCrypto.kt` 的 `open` 及 `header`：二者都先调用 `JSONObject.getLong("sequence")`，后者把转换后的 Long 写入 AAD。发生小数到 Long 的有损转换时，外层 `42.5` 因而复用原 `42` 的认证字节。独立回归方法 `nonIntegralSequenceCannotReuseAuthenticatedIntegerHeader` 要求它拒绝；没有 `@Ignore`。在未修正源码上，它可能在 Android 同样失败，必须保留实际结果。

这不是取得明文密钥或破解 AES-GCM 的证明。已确认的问题是 JSON 输入的整数契约没有在规范化前得到检查，允许不同外层值映射到同一认证序号；跨实现解析、回放/确认键和 Schema 的一致性风险仍需父线评估。

建议父线在 `open` 和 AAD 生成前以同一窄函数验证原值：必须是 JSON Number、有限、范围 `1..9007199254740991`，且无小数部分；字符串不得经 `getLong` 接纳。验证后只使用已验证的精确整数生成 AAD 和回放键。现有 `RelayFrameTransport.acknowledge` 已有 Number/finite/integral/range 检查，可作为同仓边界参考。版本字段的 `getInt` 有同类转换入口，建议一并审查，但本 checkpoint 没有扩大为该项已复现结论。

修复需要保留合法 Node/legacy 向量与双端互通，补充小数/数字字符串/布尔/null/越界/非有限负向向量；iOS/Foundation 应独立核对，不能从此次宿主结果推断 iOS 行为。修复也不得触发未知原输入重放或迁移既有操作 ID。按本线授权，生产修复由父线或既有负责人实施。
