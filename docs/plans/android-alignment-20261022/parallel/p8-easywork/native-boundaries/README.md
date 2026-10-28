# P8 原生协议边界 checkpoint

本 checkpoint 从 `64b0e00e008db42627e2f2dc9928902dcd518059` 继续。原七组审计及其 16 份交付文件保持原字节；只增加本子目录和 `NativeEasyWorkProtocolBoundaryTest.kt`。生产代码、中央清单、MainActivity、Manifest、MobileSession 及其他 EasyWork 工作线保持原样。

**状态：15 个 instrumentation 方法已编译并进入本树构建的 runner；没有执行任何设备、网络、Desktop 或真实模型任务。** 编译与宿主探针不构成 API26/API37/真机通过。七组 A5 仍开放。

[checkpoint.json](checkpoint.json)给出准确方法列表、生产源码/独立向量摘要、App/runner 包名/摘要/签名及分组。App 为 `com.easyai.agenthub.android.acceptance`，runner 为 `com.easyai.agenthub.android.acceptance.test`。本次 App 与上一交付字节相同，runner 为包含新测试的当前产物；不可复用旧 runner 冒充本测试已执行。

| 分组                           | 方法数 | 内容                                                                                                                                                                         | 当前状态                                         |
| ------------------------------ | ------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| `P8-native-core`               | 14     | 独立 Node 帧/JWE、篡改/错误收件密钥、完整目标、授权防降级、写丢回包只查原 ID、未知不重放、回执 ID/method/target/hash 拒绝、读跨路重试、路径偏好、任意方法拒绝、专用系统 DPoP | 只编译，未执行                                   |
| `P8-native-numeric-regression` | 1      | `nonIntegralSequenceCannotReuseAuthenticatedIntegerHeader`：修改 `42` 为 `42.5` 必须拒绝                                                                                     | 宿主已复现缺陷；Android 待核对，未忽略或伪造通过 |

每方法 JUnit 上限为 12 秒，协议场景另有 4 秒协程限时；全类 15 方法的上限之和为 180 秒。主统筹必须为每次 instrumentation 再设 **240 秒外层期限**，并按准确设备/包/时间租约执行；两组均小于 20 方法。方法列表可作为 AndroidJUnitRunner 的 `class` 选择器，核心组应逐项列举以排除另列的已知回归用例。不要把该排除写成整体通过，也不要使用 `@Ignore` 消去已知问题。

测试使用真实 Android `JSONObject`/`JSONArray`、`android.util.Base64`、平台 AES/GCM、生产 X25519/会话/回执/DPoP 逻辑。LAN/Relay 工厂替换为内存加密对端，合成 LAN 候选没有 pin，因此不触发生产网络预探测；账号 token 回调直接拒绝调用。没有 ActivityScenario、MainActivity 启动或 NativeSessionStorage/default identity 读取。

DPoP 方法只调用固定合成 device ID `p8-native-protocol-boundary-v1-synthetic-only`。生产 loader 据此生成/复用专用 alias，独立核对签名、非导出私钥、URL/method/token/body 和新 jti。不枚举、不读取、不修改、不删除原身份或原密钥；也不删除自己的专用 key。它不能替代进程重启、真实授权/刷新/撤销或公网服务验收。

与前一 JVM 交付的差异：本 suite 不打包 Base64 shim、不依赖宿主 `org.json:json`；JWE 向量嵌入单一测试源码（与既有 Node 向量深比较相同），普通加密帧从既有测试 asset 读取。错误回执还必须产生准确的 `IllegalArgumentException`/原诊断，测试超时或对端错误不能被算作拒绝成功。

[序号缺陷记录](numeric-envelope-finding.md)保留宿主实际复现、原生待验方法与父线修复建议。`numeric-probe.init.gradle` 仅用于显式选择的宿主探针；其“PASSED”表示缺陷已复现，不表示生产边界正确。

复现宿主探针时，先配置私有树外输出和构建槽，再从仓库根目录调用：

```sh
P8_NATIVE_PROBE_OUTPUT=/private/evidence/numeric-probe-result.json \
node scripts/mobile/gradle.mjs :android:testDebugUnitTest \
  --tests com.agenthub.android.NumericEnvelopeCoercionProbeTest \
  --init-script docs/plans/android-alignment-20261022/parallel/p8-easywork/native-boundaries/numeric-probe.init.gradle \
  -PagentHubAcceptance=true
```

这是占位路径，真实输出由负责人配置。原生准备构建使用锁定 Wrapper 的 `:android:assembleDebug :android:assembleDebugAndroidTest -PagentHubAcceptance=true`；本线没有安装或运行。主统筹在合并其他工作线后必须重新冻结组合源码/包/runner，再执行具名方法并保存实际结果，不沿用本次“编译成功”作原生结果。

[parity-delta.json](parity-delta.json)只供中央按功能/change ID 接入本 checkpoint，不提升 A5 状态。[verification.json](verification.json)区分编译、缺陷探针、根检查与尚未执行的平台门槛；树外恢复/交付位置由最终回执给出。
