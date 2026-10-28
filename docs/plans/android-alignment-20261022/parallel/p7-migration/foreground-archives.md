# 非空通知原件的只读归档

本阶段在原安全子集之后支持 P9 非空 v1/v2 journal 与原授权快照的统一迁移。代码和 12 个新原生方法已编译，尚未执行设备、Host 或模型。实际 MainActivity/MobileSession 入口、旧 coordinator 释放、新 Session 重绑与显式启用归主线，本阶段不宣称整条 P7 或真实 DEV 完成。

## 准确源码与读取边界

P9 定义取自 `cc0b6ac8`，见 `foreground-archive-source-intake.json`。v1 继续使用先前封闭 reader；v2 wire 和 journal 纯 decoder 派生到 `NativeMigrationForegroundV2*` 命名空间，删除 identity create、存储写入、reserve/settle、HTTP 和 monitor 路径。它们只校验原 intent/receipt/feed/reset/gap 图，不发出任何具名请求。P9 原生产文件未改，既有 Wire/Codec 与冻结输入分别核对。

新增 `native-chat-notification-authorizations-v1` 的冻结 reader 只返回授权指纹和目标。它核对严格客户端 Schema、原 credential、certificate、完整机器 target、稳定 epoch 与 authorization hash，不创建 SavedConnection/Snapshot/Identity 执行对象。原 journal 仅有授权指纹，不能据此借用当前连接 token 或声称原查询可用。

## 原件与安装权威

源 X25519 私／公钥匹配和 bound journal 仍先检查，私钥及原 active identity 记录不出现在便携归档。只读描述保留 device/public key/fingerprint 上下文和原 identity 文本摘要；这些是原件证据，不能加载为新安装身份。v1/v2 原 journal、不可变操作／正文／指纹、unknown/reserved/applied/rejected 回执、pending/processing 事件、cursor、batch、reset snapshot/gap 与原授权快照逐字保留，不能用新 device/key/operation ID 替代。

原件改用独立 `migration-notification-original-v1:<archiveId>:<kind>` 名字，kind 仅为 foreground-v1、recovery-v2、authorizations-v1。索引 `migration-notification-archives-v1` 版本 1，最多 16 个安装快照、128KiB；其 ID 是描述内容摘要，属于审计索引，不替换业务／通知操作 ID。所有非空原件、描述和摘要必须闭合，孤立、缺失、未来版本、错误引用或超额均拒绝整档。Schema 和详细规则见 `notification-archive-index-v1.schema.json`、`foreground-archive-policy-v1.json`。

现存原授权快照全部保留。缺少的引用按精确 referenced-minus-retained 集合记录，不从 source metadata/vault 的当前 token 补齐。原安装／原密钥／原凭据是否仍真实有效、原未知是否能查，只能按实况独立判断。只读归档存在、文本完整或历史可读都不证明 runtime 可控。

导入端的原 identity/private key、非空 v1/v2 journal 和 retained authority 先校验，再以原文本加入同一准备代次，不能由源记录覆盖。已有只读历史与源索引按内容 ID 合并，原文保持；同 ID 差异或容量不足拒绝。当前真实连接／Vault 非空的 ExistingInstallation 限制保持，未实现任意活跃 profile 合并。源通知展示偏好／OS 回执沿用已有 P7 reset／policy3 审计规则，父线必须阻止该 reset 自动触发远端 off/register。

## 父线窄 hook

有源／目标只读历史或非空目标通知原件时，默认激活拒绝。父线先释放旧 monitor/coordinator、阻止新自动 enrollment，并保留准确原件；随后为准确 storage、candidate 回执和当前静止 epoch 创建 lease：

```kotlin
val lease = NativeMigrationNotificationActivationLease(storage, receipt) {
    notificationOwnerIsStillQuiescent // volatile scalar; no UI/IO or remote call
}
NativeMigrationActivation(context, storage, lease).activate(receipt)
```

lease 在准备前和最终指针发布 checkpoint 的前后再次核对根、候选 ID／SHA／count 与 owner 静止状态。丢失或过期不发布；父线保持静止直到原子激活返回，然后丢弃旧 storage 句柄。相同已发布回执只返回原 generation，不能覆盖后来的目标原件或草稿。

创建新 Session／重绑前，在 IO 读取 `NativeMigrationNotificationArchives.requiresExplicitSetup(freshStorage)`，再在 Main 发布结果并绑定；读取完成前保持 enrollment gate。有只读历史，或已有真实迁移发布且保留本机非空通知原件时，必须保持新订阅需要独立显式人类动作的状态。后一场景即使来源没有通知原件、索引未生成，也不能丢失门槛。仅普通未迁移安装已有 journal 不改变既有恢复行为。发布上下文来自既有本机迁移指针的安全只读存在检查，不增加可 portable 的设备身份或新 authority 记录。不能由导入自动注册、补确认、撤销或重放。父线对本机既有原操作的只读查询仍须使用真实原安装／密钥／凭据，不把源归档加载进活动授权库。实际释放、重绑和显式动作门槛尚待父线接入及原生验收，本阶段 lease 夹具不代替该证明。

审计在 IO 调用 `readOnlyArchives(storage)` 的脱敏 wrapper，按封闭 `Original` enum 读取原文。它没有网络方法，也没有返回执行型 identity／connection 对象。不要把其中授权原文传给活动 P9 loader。

## 检查与仍缺门槛

统一文件导出、候选、prepared generation、实际记录数／source count、摘要、原子指针、回滚和幂等路径沿用现有实现。没有第二套迁移器，也没有安装身份初始化。8MiB 文本／JSON header 上限保持；近满的 v1/v2/授权及多份历史可能超过该上限并整档拒绝。更大原件的类型化流式保存是明确后续门槛，不能截断或部分成功。EasyWork 授权围栏仍单列拒绝，iOS 对应实现／设备结果未推进，A6 不恢复。

`NativeMigrationNotificationArchiveTest` 10 方法覆盖原件／凭据闭合、实际文件迁移／lease、目标非空原件及未知保留、rollback／stale lease、历史去重、缺授权、v2 多状态／live／reset、未知损坏与原子 sidecar、候选不变和真实 P9 decoder 差分前置。最后方法需要组合包含准确 P9 类，缺类是前置失败，不跳过。`NativeMigrationNotificationArchiveColdTest` 2 方法准备真正 writer／reader，参数 `notificationArchiveFixture` 只接受 `notification-archive-cold-<16位小写十六进制>.json`；reader 必须使用 writer 原 fixture、不同 PID、相同原 namespace／密文／目标 identity 与未知记录。两者本线只编译。

原 `NativeMigrationForegroundTest` 的第 3、4 方法属于“非空源拒绝”历史策略。其冻结源码保留于 `25092e2a` 和树外 gzip 原件，原冻结包／结果继续保留，不改写为新通过。当前可执行回归已演进为原件归档精确、无 active authority、缺 owner lease 时激活拒绝，不再留下必然失败的旧断言；第 7 方法的报告字段同时准确标记需要 owner lease。旧空安装／错误 Schema／私钥不可 portable／默认无 lease 拒绝场景继续独立回归。当前 9 项和新增 12 项只编译，原生未执行。新的源／包／方法和本次辅助结果以 `foreground-archive-source-audit.json` 为准；完整 Android、iOS、系统生命周期及真实 DEV 仍分别记录。
