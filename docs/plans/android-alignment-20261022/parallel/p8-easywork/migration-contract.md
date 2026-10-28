# P7 接口交付：EasyWork 描述信息与设备授权分离

这是拟议的严格输入要求，尚未接入生产迁移器。本线没有修改迁移核心、Provider 注册或授权流程。[描述 Schema](provider-descriptor.schema.json)只用于交给 P7 审查；本线负向 Schema 检查也只证明该提案的结构约束。

1. 导出/导入只携带 Provider ID、完整原目标、用户路径偏好和候选 origin。对象和嵌套目标拒绝未知字段，数量/长度有界。Origin 还必须经过现有适配器的规范化、私网/Relay 信任策略与重定向拒绝；正则通过不赋予连接权限。不得自动读取候选或向导入地址提交任何凭据。
2. `requiresPairing=true`、`credentialState=none` 固定。导入后仅可阅读准确原目标的缓存/历史，不能标记 connected/authorized/can-control，不从另一目标、账号或路径继承授权。EasyWork 的 authority/subject 仍是其身份维度；Codex 的机器身份政策不能外推到 EasyWork。
3. 不接受/复制手机设备 ID、X25519 私钥、系统 DPoP key/alias、grant/binding、access/refresh token、PKCE verifier、OIDC session、pending claim/refresh/revoke intent、授权引用或旧证书信任。新安装使用本机安全存储身份，通过新的电脑连接码/二维码和原 Desktop 确认重新配对。手机不要求账号登录；真实 Desktop OIDC 仍由电脑及服务端负责。
4. 未知旧授权类型或旧手机 OIDC 状态只保留为不可执行的迁移诊断，不能自动交换/刷新、降级到旧 Bearer 或主动创建新 grant。明确重新配对前没有有效授权。迁移失败原子回滚，不污染现有安装的凭据或连接。
5. 原不可变提交、操作 ID、正文/附件/设置摘要和只读回执由现有迁移核心保留；本提案不重建、改写或重放它们。导入后匹配回执必须核对原目标及原 ID；新授权不改变旧操作的归属。
6. P7 集成须分别提供：严格解码/再编码向量、秘密/未知字段拒绝、失败回滚和双端原生导入 UI；新安装的本机密钥与原 Desktop EWDAP 确认、真实过期刷新和撤销必须另验。现有 JVM 或旧 A5 结果不替代这些门槛。

交付项对应 F07/F09/F43/F44/F46，并供 F47/F55 的既有迁移负责人评审；本线不宣称 F47/F55 新实现或通过。
