# 审计同名字段保留补充

阶段二 `83044a153f1f2e557f0a0ba3c5eaa478fec3f691` 的代码复核发现：原 pending 若包含与审计派生字段同名的未知附加字段，平铺生成 `body`、`tokenHash`、`grantHash` 会遮住原值。本补充在每份审计内保存 `originalPending` 深拷贝以保留所有未知字段；已知 token 留其指纹，确认刷新后不因这份附加审计增加原 token 的存储期限。待确认撤销的原 token 仍由既有加密快照保留。记录只位于 NativeSessionStorage 的加密空间，不打印、外传或写入公开结果。

授权 HTTP 的 body 仍按已提交协议以原 operation ID canonical 编码，不将未知元数据转发到服务，也不改原任务 WIP 的 body/receipt 策略。helper 的原意图、保存栅栏、拒绝与幂等恢复行为不变。新增 1 项宿主回归验证与派生字段及 `originalPending` 自身同名时，原内容仍完整可查；阶段一/二和旧 16/12/19 方法及档案均保留。新 Native 12 方法源码不改，随当前 helper 重新编译。

精确当前输入和包以本阶段 checkpoint 为准。执行宿主检查时同时传阶段二、三的 init script，选择 `AuthorizationCoordinatorTest`、`AuthorizationFenceShapeTest`、`AuthorizationOpaqueAuditTest`；设备仍由统筹使用阶段二 fixture、`p8AuthorizationFenceConfig` 与 240 秒外层期限运行。原生、真实进程恢复与 iOS 通过状态不提升。
