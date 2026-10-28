# P8：真实 Android 传输边界准备

源码基线为 `25276465207b9f30e7e808aeb6202271a6f62fbb`。本 checkpoint 只新增 `NativeEasyWorkTransportBoundaryTest` 与本目录，不修改生产 EasyWork、旧 16 方法或前 33 份文档。主统筹报告的 strict16 API26/真机通过属于其准确候选与原始证据，本交付不重新声明或迁移该结果。

**状态：12 方法已编译，尚未执行。** 本线没有启动侧车、生成运行证书、分配设备、调用 ADB、运行 instrumentation、访问真实桌面或提交模型输入。7 项 Node 纯函数自检只覆盖既有独立加密向量、AAD/密文篡改、方向隔离、canonical 与有界 WebSocket 分帧，不能证明 TLS 或 Android 运行通过。

测试直接调用生产 `EasyWorkPairing`、`EasyWorkRemoteSession`、`LANFrameTransport`、`RelayFrameTransport` 和默认 registry；不注册内存传输，不替换信任校验，不启动 Activity，不读持久身份或密钥。测试只接受既有公开合成 binding/credential、固定合成 token 和三个不同端口的 `127.0.0.1` origin。

| 分组                      | 方法数 | 准备覆盖的边界                                                                                                                        |
| ------------------------- | -----: | ------------------------------------------------------------------------------------------------------------------------------------- |
| `P8-transport-tls-target` |      5 | 固定证书 HTTPS descriptor 与加密 WSS、变 pin 的真实 TLS 拒绝、descriptor 完整目标拒绝、系统信任拒绝自签 Relay HTTPS、加密响应目标拒绝 |
| `P8-transport-recovery`   |      4 | WSS→Relay 读保留同一 request/hash；写丢回包仅查原 operation；未知写拒绝重放；Relay POST 丢响应后由 WSS 查原 operation                 |
| `P8-transport-relay-http` |      3 | 真实 HTTP post/poll/ack、外 binding 过滤且不确认、302 不转发合成授权                                                                  |

准确方法名和分组选取见 [checkpoint.json](checkpoint.json)。每方法 JUnit 期限 15 秒，场景工作期限 10 秒，控制请求连接/读取各 1.5 秒，收尾期限 2 秒；12 方法 JUnit 上限之和 180 秒。主统筹必须另设 **240 秒外层期限**，按准确设备、包、时间租约串行运行。不得同时操作一个 fixture，不选择整个测试 APK 中的其他类。

## 主统筹运行入口

先在独立树中接入源码和 [parity-delta.json](parity-delta.json)，重新冻结组合源码、fixture 与 App/runner 指纹，再用统一构建槽执行：

```sh
node scripts/mobile/gradle.mjs :android:assembleDebug :android:assembleDebugAndroidTest -PagentHubAcceptance=true --console=plain
```

输出包为 `com.easyai.agenthub.android.acceptance` 和 `com.easyai.agenthub.android.acceptance.test`，本 checkpoint 的 APK/签名摘要见 checkpoint；统筹合并后必须重新核对。执行器为 `androidx.test.runner.AndroidJUnitRunner`。

获得设备租约后，统筹可在仓库根目录启动合成侧车，参数必须是**尚不存在**的树外私有目录：

```sh
node docs/plans/android-alignment-20261022/parallel/p8-easywork/transport-boundaries/fixture.mjs --start /private/evidence/p8-transport-new-run
```

侧车仅依赖 Node 内置模块和本机 OpenSSL，复用 `scripts/mobile/android-discovery-fixture.mjs` 的 RSA-2048、自签证书和 loopback SAN 方案，不调用其设备操作入口、Host issuer 或正式服务。三个 listener 都固定监听 `127.0.0.1` 的临时端口；不接受外部目标。目录 0700、证书私钥/配置/场景记录 0600。私钥不得提交或写入聊天。

统筹读取生成的 `config.json`，给租约设备配置**恰好三个同端口 loopback reverse 映射**，保留现有映射，只清理本次创建且确认归属的映射。将该 JSON 原样作为 instrumentation 的 `p8TransportConfig` 字符串参数；使用 `class=com.agenthub.android.NativeEasyWorkTransportBoundaryTest` 或 checkpoint 内准确方法选择器，并套 240 秒外层期限。入口不自行安装、不调用 ADB、不取得设备租约。运行后保存逐方法原始结果、当前 config/证书摘要、12 个 case JSON、端口映射、源码/APK/runner/fixture 指纹；最后停止本次侧车并核对退出。

fixture 仅接受四个具名协议方法，使用独立 Node X25519/HKDF/AES-GCM 合成对端：`runtime/context`、`thread/read`、`turn/start`、`command/status`。写文本只留在本次合成状态中；恢复回执必须匹配原 operation、method、target 和 intent hash。故障通过真实 socket/HTTP 响应中断注入，原输入不会发往真实模型。明文控制接口限于健康、开始、统计和结束本次场景。它不是产品 Relay、Host 或真实桌面实现。

## 明确缺口

生产 Relay 内部创建系统信任 OkHttp client，目前没有针对独立测试 CA 的注入接口。本交付只准备 **LAN 正向 TLS/WSS、Relay loopback HTTP 加密帧正向、Relay 自签 HTTPS 负向**；未通过绕过校验制造正向 Relay HTTPS。正向 Relay HTTPS、真实 EWDAP v2/刷新/撤销、真实 Host/原桌面/模型及 UI/生命周期均未验收。若统筹需要系统信任 Relay HTTPS，需另行设计相应环境，不能把本合成 HTTP 结果记为该项通过。

本次没有产品行为或 UI/文案变化，iOS 生产路径不变。中央产品/设计/验收矩阵由主统筹按 delta 接入；不在本分支改中央 manifest 或冻结结果。`pnpm check` 的准确结果见 [verification.json](verification.json)，任何中央登记阻塞必须在集成后重新检查。A5 不闭环，A6 继续暂停。
