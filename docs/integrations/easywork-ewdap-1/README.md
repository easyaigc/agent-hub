# EasyWork 设备授权依赖差异

手机扫码或输入连接码，由已登录的电脑确认设备；手机不需要 EasyAI 账号登录。Android 与 iOS 原生客户端实现 EWDAP/1，Desktop 与 Relay 必须同时支持该契约。

`implementation.patch` 保存本次在独立 EasyWork DEV 源码中合并的必要差异，包括设备申请、电脑确认、密文设备凭据、DPoP、刷新、撤销、认证直连、原生任务共用的权限边界及相关检查。`manifest.json` 记录输入提交、原开发现场的输入摘要、每个修改文件前后摘要及整个差异摘要。差异已在记录的输入上执行 `git apply --check`；它保留现有持久命令与队列实现，未覆盖原开发工作区。

输入包含 EasyWork 原现场的既有未提交改动，因此先核对 manifest 中的前置文件摘要，在适用的 EasyWork 开发分支审查合并，不对不同版本直接覆盖文件。此目录不包含原现场的未提交工作、凭据、真实对话、设备配置或模型认证。

本次实际验收使用独立原 Electron、原生 App、真实模型和桌面独立核对。辅助检查与实际场景保存于 [A5 进度](../../acceptance/mobile-evidence/A5-execution-progress.json)，开发身份、访问路径和实现指纹各自记录。差异文件的交付不代表公网服务已部署；正式 Desktop OIDC、Relay 上线及实际跨网络拓扑须在对应环境另外验收。

手机平台的凭据、签名密钥和 X25519 身份仍保存在各自设备安全存储。Relay 路由密文及设备授权，模型凭据与任务执行由原 Desktop 拥有。
