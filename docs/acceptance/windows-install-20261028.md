# Windows 安装修复（2026-10-28）

范围为 F03／F69 的 Host 安装，不改变双端 UI、缓存／草稿身份、设备授权、未知操作或已提交输入。按用户要求，Publisher 与 Store 包身份不作为连接条件；实际管道、用户、会话、进程路径／SHA 和 IPC profile 继续核对。

bootstrap PowerShell 加载与常驻原生 worker 相同的 C# 权限实现。保留目标文件句柄并拒绝重解析点，先核对当前所有者，只通过 DACL_SECURITY_INFORMATION 与 PROTECTED_DACL_SECURITY_INFORMATION 写权限；不设置 Owner／Group／SACL，不启用 SeSecurityPrivilege，不接管其他所有者。写后重新读取实际所有者和 DACL，拒绝 NULL DACL 与外部主体授权。

辅助检查包含缺少发布者字段时的真实配置发现／迁移流程，以及 Windows 原生重复 bootstrap／worker 保护、共享 ACL 拒绝／修复、错误类型、junction 拒绝和无签名 CLI 摘要检查。Windows 原生用例在非 Windows 平台明确跳过，不计实机通过。

本机 `pnpm check` 全量通过，`pnpm build` 通过；PowerShell 语法解析及共享 C# 源码编译通过，打包回归 13 项通过。完整 ZIP 的 CRC、UTF-8、无重复条目、源码与部署 helper 一致性、真实 ESM 依赖闭包及分片合并字节均已核对。Windows 安装专用的两组原生用例在本机 macOS 明确跳过。检查早期的未使用导入与 JDK／Xcode 定位问题已修正；失败记录与最终日志分开保留。目标新 Windows 的普通用户安装、Codex Beta 真实协议、桌面 owner、手机连接／功能及重启恢复尚未验收；历史或其他平台结果不替代本次结果。A6 继续保持人类暂停。
