# 原录音只读读回与离线核对

2026-10-30，阶段 37。使用原 API26 隔离现场和 Source62 验收包，对 Source55 已封存的合成原录音执行一次固定 `readOnlyOriginal`；没有重建 writer、发送模型任务或重放丢弃操作。它是原件读回的限定证明，完整语音交互、ASR、系统生命周期与真机 DEV 继续待验。

## 实际结果

- 先完成独立的 12 项只读元数据观察，准确原包、UID、boot、实际 shell/readproc、完整 PS 与 AM 原文均已核对。前一次观察因准确 AVD 名称后的 CRLF/OK 格式被拒绝，仅 1 调用且没有 Native；该原失败保留，新观察使用独立租约。
- 原读取阶段只有一次所选 Native 方法：start 1、method terminal 0、overall -1、`OK (1 test)`，ADB 与外层进程均已知退出，原设备锁已释放。
- 主统筹独立核对该阶段全部 91 份完整原始调用、意图和退出回执；原 109 项冷迁移资料共 4,062,576 文件字节，原 15 项语音资料共 16,641 文件字节，前后完整 tar 与原指纹完全相同，九项 stat 字段及所有正文保持。
- 默认存储、权限/AppOps、UID 和 boot 保持。原读取及捕获前后的完整 AM/PS、正向进程见证和 readproc 证明已按实际原文复核；独立 Test UID 不是旧 Voice PID，不宣称原子冻结。
- Native 新导出的三份文件已完整捕获，WAV 的全部 3244 字节与固定合成输入逐字节一致。Native 使用原既有密钥认证解密；父侧未独立解密或导出密钥。

## 离线验收器修正

首次真实 CLI 失败于 `complete original audit`，原结果保留。原因是 audit 保存完整持久化 row，包含 `phase`、`audioStorage`、`expiresAt`，旧验收器却要求它与不含这些字段的原 DTO 全等。实际 audit 与冻结证明的 `persistedRow` 原字符串完全相同，marker SHA 也匹配。

采用专用分支 `37bd42cd` 的最小修正：严格比较冻结完整原字符串、marker SHA 与全部字段/类型，不丢弃存储字段、不容忍额外或缺失字段，其余门槛保持。7 项测试及 29 个 audit 负例、同一真实捕获的 CLI exit 0 按准确源码、输入、权限及实际 Terra/medium 记录匹配复用；没有重复 Native。源码 SHA 为 `442f3ee8cf10cbf3e7df73324ac89651d1b192deae75bfbc788c75deb439fcd8`。

## 保留与未验

WAV SHA 为 `9d926e59a8259999491bddd13cd65ad6ff3f63916a9dd10a86d8594169ad9550`，完整读回收据 SHA 为 `1d18ac9fca50fb792240ce4ac0d326e2e2ceebfc2a953d60e15a74b7f00a2e50`。原始流、原密文与三份读回文件保存在对应私有证据档案。

本阶段未生成或提交 cleanup ACK，未清理原 fixture 或密钥；后续须按准确原件及独立收尾协议执行，不能重跑原读取来代替核对。无新增 Host/模型或麦克风调用。F64 与完整 P7、界面和 iOS 均不因此整项关闭；任务/项目管理及 iOS A6 继续暂缓。
