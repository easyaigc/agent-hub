# EasyWork 发现名称与直连同步

本次先刷新 EasyWork 的远程 `master`，确认 `dbff1626bf05b652d8ebef4ba7a192d31ab98dfa` 已有自动直连、动态局域网／VPN 地址及托管 Bonjour 广播。本机旧开发基线缺少这些增量；按文件同步到 EasyWork 开发现场，保留较新的 TLS 身份实现和其他任务改动，没有整库覆盖。

`implementation.patch` 仅保存相对于上述最新远程主线的新增优化：电脑名、操作系统用户名与平台的可选 `computer` 展示元数据；默认名称；63 字节内不拆 UTF-8 的 Bonjour 名称；设备 ID 尾部区分；对应真实 HTTPS／二维码回归及 DTO 字段。设备 ID、账号、工作区、运行时、证书、设备授权和操作回执不变；系统用户名不作为登录账号或授权条件。

`manifest.json` 保存基线和逐文件前后 SHA-256，补丁已在该基线的独立临时索引执行 `git apply --cached --check`。不同版本应先核对实际前置摘要并审查差异；不要覆盖已有开发现场。同步到源码、合并主线、制作安装包及部署是不同节点，本补丁不证明运行中的电脑已更新。

本机网络对用户截图两台 EasyWork 的只读发现确认：设备 ID 不同，但前八位都是 `device-8`；服务均未声明 `connectionMode: automatic`，因此手机拒绝自动申请连接。实际电脑名、地址和目标证据保留在本机私有验收目录，不进入公开源码。验证范围见[专项记录](../../acceptance/easywork-discovery-20261031.md)。
