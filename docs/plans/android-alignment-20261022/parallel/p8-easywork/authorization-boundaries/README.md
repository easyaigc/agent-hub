# P8：EWDAP 设备授权边界准备

本 checkpoint 基于 `10b2060c943b5891225d0e32800769f98a81b90d`。接口路径为 `/v2/remote-access`，授权和 JWE 的协议字段仍为 **`EWDAP/1`**。生产源码、旧 16/12 方法、传输 checkpoint 的 7 个文件及此前 39 份 P8 文档全部冻结。

**19 方法已编译，未执行：18 个核心方法、1 个具名竞争回归。** 7 项 Node 自检通过，只核对独立 JWE/既有公开向量、ES256 DPoP、原意图摘要与篡改拒绝。本线未启动网络夹具、分配设备、调用 ADB、读真实身份、访问 Host/原桌面或提交模型输入。编译和 Node 结果不代表 AndroidKeystore、存储、网络或 iOS 运行通过。

| 分组                                | 方法数 | 准备核对的断言                                                                                                                                                         |
| ----------------------------------- | -----: | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `P8-auth-claim`                     |      5 | 注册与电脑待确认提示；POST 回包丢失保留原 body/operation；已持久 claim 只续原 poll；明确拒绝/过期移除本次 pending                                                      |
| `P8-auth-grant-refresh`             |      9 | 新鲜 grant 的 v2 实际授权；刷新与保存；同实例并发共享一次刷新；丢回包保留原 token/operation；取消后的关闭 socket 迟到；错误目标/元数据拒绝；refresh 过期；服务撤销拒绝 |
| `P8-auth-revoke-isolation`          |      4 | 撤销确认后移除 grant；丢回包恢复原 DELETE；独立 grant/目标/DPoP 隔离；二维码地址/字段/连接码拒绝                                                                       |
| `P8-auth-revoke-refresh-regression` |      1 | 已确认撤销后，仍在等待的刷新回包不得恢复本地 grant。源码发现缺少共用保存屏障，**预期可能失败，尚未运行**                                                               |

准确方法、选择器和摘要见 [checkpoint.json](checkpoint.json)，状态断言及未覆盖项见 [assertions.json](assertions.json)。每方法 JUnit 期限 12 秒，场景期限 8 秒，收尾期限 1.5 秒，控制连接/读取各 1 秒；19 方法上限之和 228 秒。统筹须另设 **240 秒外层期限**，按设备/包/时间租约串行运行。核心组选择前 18 个具名方法；竞争回归必须单列保留原结果，不得用 `@Ignore`、修改断言或核心组通过掩盖它。

测试使用生产 `EasyWorkDeviceAuthorization`、`EasyWorkIdentity.load`、`EasyWorkDPoP`、`NativeSessionStorage`、`RelayFrameTransport` 和系统 HTTP 客户端。所有测试存储均位于准确验收 App 内新建的 `p8-auth-<case UUID>-<a|b>` 命名空间，使用 `p8-auth-storage-<case UUID>-<a|b>` AES alias；初始化前要求目录不存在。只向该新空间预置既有公开 RFC7748 身份，device ID 为 `p8-auth-<case UUID>-<a|b>`。DPoP loader 据此创建/复用专属系统签名 alias；不枚举、不读取、不移除默认身份、原密钥或其他空间。

每次取得新授权客户端时重新打开同一测试 namespace/key alias，由生产存储解密原记录。这验证的是同进程新客户端/存储实例恢复的准备路径，尚未覆盖真实进程终止。测试不导出 Keystore 私钥，不删除专用测试 keys 或存储。统筹保留 case 记录后，可依据精确 case ID 另行处理这些已确认归属的合成资料。

`pair`、`headers`、`revoke` 再次显式调用时，生产客户端从持久记录读取原 operation/body/token，重新调用对应具名 POST/DELETE；当前客户端没有独立授权 status 查询接口。夹具按原意图幂等，要求新的有效 DPoP `jti`；日志只存 token/body 摘要、公钥 thumbprint、operation、完整目标和方法，不记录私钥或完整凭据。这些均为合成授权操作，不提交业务输入。

## 统筹入口

先按独立 delta 接入中央矩阵，再在组合源码上通过统一构建槽运行：

```sh
node scripts/mobile/gradle.mjs :android:assembleDebug :android:assembleDebugAndroidTest -PagentHubAcceptance=true --console=plain
```

App 为 `com.easyai.agenthub.android.acceptance`，runner 包为 `com.easyai.agenthub.android.acceptance.test`，执行器为 `androidx.test.runner.AndroidJUnitRunner`。组合候选须重新冻结 source/APK/runner/fixture 摘要，不沿用本 checkpoint 的编译结果声称组合候选已验收。

取得准确设备租约后，统筹可从仓库根目录启动侧车，参数为尚不存在的树外私有目录：

```sh
node docs/plans/android-alignment-20261022/parallel/p8-easywork/authorization-boundaries/fixture.mjs --start /private/evidence/p8-auth-new-run
```

只依赖 Node 内置模块，两 listener 固定监听 `127.0.0.1` 临时端口。统筹读取 `config.json`，建立该设备的恰好两个同端口 loopback reverse 映射，保留其他映射；将 JSON 原样作为 `p8AuthorizationConfig` 字符串参数。选择 checkpoint 的准确方法并设 240 秒外层期限。入口不自行安装或调用 ADB。保存逐方法原始结果、`testMethod` 对应的 case JSON、config、端口映射及所有组合指纹；最后停止本次侧车，确认退出，只释放本次创建的映射。

Node 自检入口为 `node --test docs/plans/android-alignment-20261022/parallel/p8-easywork/authorization-boundaries/fixture.test.mjs`，导入不会监听或生成身份文件。运行侧车目录 0700，配置/场景记录 0600；所有凭据仅为本次合成公开测试值。

## 实际缺口与交付界限

当前授权 HTTP 客户端使用系统信任，无测试 CA 注入；本夹具采用生产 DEBUG 明确允许的 loopback HTTP。系统信任 HTTPS、真实服务和桌面人工批准、nonce challenge、全部错误码、真实进程重启、同一 grant 跨多个授权实例的并发、iOS 运行均未覆盖。客户端重建不等同进程重启。iOS 对应 actor、Keychain/Secure Enclave 与当前 Android Keystore 分别记证，不能互相替代；iOS actor 文件内没有 Android 对应的 `revoke` 方法，平台完整撤销路径需其负责人核对。

[source-findings.md](source-findings.md)记录撤销/迟到刷新竞争的源码依据及具名回归，不修改生产实现，不把未运行项写成已复现。无产品 UI/文案变化；[parity-delta.json](parity-delta.json)供主统筹单写中央产品/设计/验收矩阵。本分支不接管旧 EasyWork DEV 的 UI/历史/搜索/计划卡。七组仍开放，A6 保持暂停。
