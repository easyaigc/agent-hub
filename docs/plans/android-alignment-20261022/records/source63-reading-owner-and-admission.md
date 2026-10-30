# Source63：阅读写入归属与验收入口修正

状态：源码已接入，组合 SDK、三 APK 验签/ZIP/manifest 与 P8 新 DEX 声明审计已通过；Native、完整界面和真实 DEV 尚未执行。原 Source62 的 38 项限定通过、两项已知失败和相机一项未验保持原指纹；不能据此关闭 P7/P8。

## 已接入

- F49/F50：Session reset 同步撤销旧写入归属，原 ledger 事务取得存储锁后才核准变更；取消 Job 只负责结束等待。实际 Store 夹具覆盖已排队旧写入、reset、新 owner 合并和旧 finalizer，保留原 ledger 与回执断言。
- F55/F71：迟到 registry 测试保留全部断言并传播原 cause，尚未认定唯一实际根因。照片原查询使用现有生命周期 `refresh()` 端口触发，核真实 RESUMED、观察器注销、查询退出和 lease 顺序，不增加超时或弱化条件。
- F06/F08/F10/F56：P8 采集实现移入 API34 guard 后才创建的 helper，隔离高 API 原型；原 Popup/Surface、原点、token、AX、像素和时间断言保持。低 API 的类反射加载及新 DEX 必须另验；API26 菜单像素仍待专用实现，未称通过。

## 仍待完成

1. 冻结当前组合与构建，核真实 APK、签名、DEX；新候选按原件保护计划升级，旧指纹不移植。
2. 分组执行阅读 fence/ledger/实际 Store 测试，独立核原始回执和保护证据；完整 Session restore 和六条交互行仍须分别补验。
3. 两项 owner 失败只在新候选按新场景复验；相机原件仍缺安全保留窗口，不为取证清理未知写入者。
4. P7 discovery/HTTP/private claim 的实际 IO 归属另由原专用会话完成；P9 固定只读采集器源码并行，不新增通知通过。

## 双端对照与界限

沿用 iOS 的本机阅读、旧目标隔离、迟到结果不覆盖新状态断言；本轮 Android owner/fence 实现不修改 iOS。无页面、文案、token 或用户操作变化，完整交互、屏幕布局、系统权限、物理设备与真实源结果单列。任务/项目管理和 iOS A6 继续暂停；未知业务操作不查询、不重放。

源码精确指纹见产品 manifest 的 `android-source63-reading-owner-and-fixture-admission-20261030`。源码审查与未来构建/原生证据严格分列，不把 source review 当测试通过。

## 本阶段检查结果

- 一致性：生成清单、限定文档格式、`check:mobile-parity`、差异空白检查通过。
- 组合输入：canonical 825 / `59f8d1c0`，expanded 838 / `96fe68fe`；主统筹独立读回完整源码及冻结副本，构建配置 17 项与原候选一致。
- 构建：77.631 秒、exit 0，两条原始流完整到 EOF、无截断/reader error；stderr 仅 Java 弃用 API 提示。App `111f47b3`、Test `ed938883`、examiner `f6c813b5`；同一签名证书，三个 APK 的完整 ZIP/CRC 和 SDK 工具原回执均已核对。
- P8 新 Test DEX：类族 60 类/293 方法，外层 133 方法中上述高 API 原型为 0；6 个相关原型在 API34 helper 与其合成类。工具保留的固定旧字段不能覆盖本次实际输入的 Test SHA；API26 platform jar 字典、真实反射和菜单像素仍未验。
- 独立代码复核确认旧 Ledger 两个 Native selector 的失败收尾有调度缺口：worker 终止未知时仍退役存储。当前不调度这两项，已交原阅读会话进行 Source64 窄修；新 Store 夹具的场景/worker 退出失败会保留原件及 key，finish 自身失败仅按实际已完成步骤记录，不能概称仍保留 key。
- API33 验收环境尚未安装新候选，实际预检/安装/Native 均未执行。新采集器准备与本阶段 SDK 证据分别登记，不用准备成功代替测试通过。

所有 Source62 两项已知失败、相机一项未验及旧未知操作继续保留。规则更新中断后，旧 API26 未知操作的原父 worker 已不在，不能再声称原运行对象仍被持有；这不证明 Native 终态或目标可重新调度，该目标继续封存，不查询或重放。
