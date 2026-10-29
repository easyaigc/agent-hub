# P8 EasyWork：七组对账与边界辅助证据

后续源码阶段见 [discovery route writeback](discovery-refresh/README.md)：以父任务已接受 candidate20／cd3c9a12 的准确 Session 为补丁基线，修复凭据 IO 后的旧地址结果提交竞态。12项原函数对照和5项原生编译与父任务既有 cold 通过分开登记；本页以下仍保留初始1e908bea审计及其原始状态，不将历史候选或不同 App/source 混作本次通过。

当前源码候选固定为 `1e908bea1ac75341e17e56a518d7bebd46620c4d`。本线只新增此目录，不改生产代码、共享总表/清单或其他工作线 WIP。**七组均未封存；本次没有原生设备、原桌面或真实模型验收。** A6 和业务暂缓项保持。

[逐功能矩阵](matrix.json)覆盖 7 组、28 个 F 功能，保留各自共同断言、UI/S 场景、交互、iOS 来源与当前源码判断。[源码审计](source-audit.json)绑定 40 份文件的 SHA-256、当前最后变更提交及 11 个历史 P8 节点；矩阵另保留冻结对照之后、准确共同起点之前的 31 个已提交 iOS 增量节点。矩阵引用旧审计的来源映射，不沿用其中过时的功能缺口结论。

| 组   | 当前实现与交互                             | 本线辅助证据                                               | 仍需关闭的真实门槛                                            |
| ---- | ------------------------------------------ | ---------------------------------------------------------- | ------------------------------------------------------------- |
| A5-1 | Provider 注册、完整目标、具名操作          | 目标坐标/设备密钥隔离、任意方法拒绝                        | 原 Codex/EasyWork 切换、草稿/事件/回执隔离                    |
| A5-2 | 发现、QR/连接码、原 Desktop 确认、证书固定 | 绑定身份拒绝                                               | 真摄像头、真实错误证书/绑定/过期、地址变更                    |
| A5-3 | LAN/Relay、加密握手、路径偏好、原回执恢复  | 独立 Node 帧；读跨路径重试；写丢回包只查原回执、未知不重放 | 实际双通道、重启、故障和完整目标核对                          |
| A5-4 | 工作区/历史/模型/计划/压缩、权威事件       | 仅源码对账                                                 | 全历史/搜索/计划卡由现有 EasyWork 线修复并交付，逐界面核对    |
| A5-5 | 创建/回复/原生队列/控制/审批/问题/资源     | 操作 ID、method、target、输入摘要回执校验                  | 真实模型、随机附件、资源字节、原 Thread/Run 逐场景核对        |
| A5-6 | EWDAP、系统 DPoP、密文凭据、刷新/撤销      | 独立 Node JWE；错误收件密钥/投递 ID/篡改拒绝；授权防降级   | 系统密钥、真实过期/新进程刷新、原 Desktop 授权/撤销；公网另列 |
| A5-7 | API26/API37/真机、恢复/隔离和 Codex 回归   | 本线没有设备运行                                           | 主统筹分配准确设备/包/时间租约，冻结后执行全部适用矩阵        |

旧 A5 ledger 的 7 份声明来源与当前候选 **0 份相符**。旧 `c4441782…` APK、`76de80d3…` native+Desktop 组合摘要及历史 1/7、3/7 只代表各自输入。旧 EWDAP 差异包摘要匹配仅证明交付文件完整，不能证明当前 Desktop 部署。原恢复过程额外任务归属未明的失败继续保留。当前没有提升任何旧结果为本候选复用通过；主检出 EasyWork 线的 WIP 未采用。

[验证记录](verification.json)分别登记 JVM、Node/Schema、构建与根检查。Host JVM 运行当前 Android 生产 session/crypto，网络帧对端使用合成实现，Android Base64 改用 JDK 实现，JSON 使用固定版本 `org.json:json:20250517` 兼容运行时；生产协议/回执规则未修改。JSON 平台差异仍须由原生测试核对。它不能证明 Android Keystore、真实 TLS/WSS、系统 UI、Electron 或模型。固定测试密钥和 nonce 只用于公开合成向量。

复现（从仓库根目录；编译命令按主统筹规则取得构建槽）：

```sh
node docs/plans/android-alignment-20261022/parallel/p8-easywork/audit.mjs
node docs/plans/android-alignment-20261022/parallel/p8-easywork/ewdap-vector.mjs
node --test docs/plans/android-alignment-20261022/parallel/p8-easywork/schema.test.mjs
node scripts/mobile/gradle.mjs :android:testDebugUnitTest --tests com.agenthub.android.P8EasyWorkBoundaryTest --init-script docs/plans/android-alignment-20261022/parallel/p8-easywork/boundaries.init.gradle
```

第一次 JVM 运行因仅登记 Java 源目录而未找到 Kotlin 测试，失败日志保留；登记显式 Kotlin 源目录后执行成功，不把 `NO-SOURCE` 计为通过。该 init 脚本只在明确指定时增加本目录测试和固定版本 JUnit/JSON 依赖，不进入产品构建。

[验收意图](acceptance-intents.json)包含九组带不可变计划摘要的待排期场景。它们不是可执行命令；准确设备、包、完整目标与租约绑定另存后才允许真实执行。现有 `android-easywork-live.mjs` 硬编码 `.debug` 安装/runner/存储路径，不能直接对本 `.acceptance` 候选运行，也不能借用另一线 `.acceptance.easywork`。本线只构建，不安装、不启动设备或桌面。

[Provider/设备/OIDC 迁移要求](migration-contract.md)及[拟议严格 Schema](provider-descriptor.schema.json)交给 P7：只迁移描述信息，新安装本机密钥和电脑明确重新配对，不继承 grant/token/OIDC/证书信任。没有自建或接入迁移器。

[parity-delta.json](parity-delta.json)供主统筹按 feature/change ID 集成中央清单，不能覆盖他人新增节点或提升产品状态。本线不创建 PR、不合主线。恢复包、APK、原始日志、文件元数据去重记录和交付/清理回执存放树外私有档案，由最终交付回执给出准确位置与摘要。

[连接偏好菜单的原生选中映射](menu-platform-selection/README.md)按锁定 Compose 1.12.1 的真实角色核对：既有无 Role、无 ToggleableState 的选项必须实际 checkable，并以 checked 表示与独立期望一致的 Selected；完整 owner、窗口、唯一文本关联、可操作性及可见范围仍逐项要求，原 5 秒预算不变。该次仅修正验收断言与补全观测字段，29 个 JVM 向量和 6 个删除门槛反例属于辅助检查。Source46 因缺少 checkable 记录产生的失败仍保留，下一候选原生结果、未选中互斥项、iOS 原生菜单属性及 API26 仍待验，不注入 Tab 或沿用旧通过。
