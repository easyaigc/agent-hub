# 与原授权任务 WIP 的窄合并说明

输入为专用分支已提交的 `9f79fe5ef5ec028f510b796d86c5c4e0704f2494`，不是主检出未提交现场。本线没有读取或带入主检出的临时 HTTP 分类、批准续读、根级 refreshReceipts、过期原刷新确认 WIP。不要整文件覆盖原任务。

按以下调用点接入，保留原任务已有的请求/重试政策：

1. `claims` 保留；实例 `refreshes` 替换为按 canonical root/ref 的协调器。新增 helper 复用已经存在的 `coordinationKey`、`operationTransaction`、read/write/remove，NativeSessionStorage 不改。
2. 批准结果验证后，通过 `coordinator.install` 保存 ref。已撤销 ref 拒绝保存，已存在且身份一致的轮换 grant 保留。原任务批准续读、原 claim/body/verificationCode/grantRef 等处理继续保留。
3. `validAccess` 的可用 token 判断放进协调器读取最新记录的 callback；HTTP 与 Keystore 保持事务外。保留原任务关于过期 pending 的原回执确认政策，本线只沿用已提交基线的 expiry 判断，未实现或替换该 WIP。请求继续使用协调器给出的原 operation/body/token。
4. 原任务对响应的验证/原回执选择完成后，将**已验证**完整 grant 交 `refreshed`。helper 会复核最新 pending、epoch、旧 grant 和本地撤销，再以最新根 JSON 深合并；根级未知字段包括原任务 `refreshReceipts` 不改。本协调器的审计位于单独 fence 的 `refreshResults`，不与该根字段合并或迁移名字。
5. `headers` 在生成 DPoP 后、返回前复核当前 binding/token 和撤销栅栏。已返回或正在发送的请求仍由服务端裁决，不宣称撤回网络操作。
6. `revoke` 独立于 refresh 网络等待；持久 pending 栅栏先写，恢复固定原 snapshot/operation/body/token。确认校验不变，先存 confirmed receipt 再删 ref；不删除未知操作。没有 ref/未知 fence 工作时不加载或生成 identity/signer。

协调器 v1 记录 `easywork-authorization-fence:<SHA256(ref)>` 包含 root 内的逻辑 ref、epoch、原撤销完整快照/指纹、确认后的本地 receipt，以及确认刷新的 pending 元数据/指纹审计。它从不授予权限，也不自动清除撤销。未知 JSON 字段保留，已确认审计未新增淘汰策略；容量策略须另评审，不能裁掉活动栅栏或未知意图。

中央单写动作：按两个 delta 的 change ID 与最后准确文件摘要登记产品 F07/F09/F43/F44/F46；设计矩阵加入 pending 撤销提示，保留 confirmed 原提示；验收分别登记父线原基线、17 宿主/4 Node/编译、本次未运行的 12 原生方法。架构/协议说明追加“Android 本地授权在持久 revoke 栅栏下保存，未知与确认撤销均拒绝迟到刷新和旧批准的权限恢复；同 ID 授权恢复保持原意图，新 DPoP proof 不重放业务输入”。迁移负责人须保留这一新逻辑记录，未知记录继续整档拒绝。

新可见诊断为“设备授权撤销结果待确认，请核对电脑状态”；confirmed 继续使用“设备授权已移除，请重新配对”。保存记录无效、原刷新被替代、当前授权更新、目标变化的诊断均只拒绝本地保存/返回，不清理原记录。UI 布局、token、页面和原桌面流程无变化；iOS 不据此升级验收状态。
