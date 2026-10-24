# 局域网与双通道配置

默认双通道：局域网 HTTPS 和原 Tailscale Serve HTTPS 共同指向同一 Host。Tailscale 可选，只在局域网使用时不需要安装；需要在外远控时完成两端 Tailscale 登录和 VPN。电脑须联网、保持唤醒，并运行原 Codex 与 Host。未配置远程入口时不能交付为“双通道已就绪”。

## 局域网入口

按 [Host 部署](host.md#身份与本机检查) 检查持久证书、私网 HTTPS、Bonjour、公钥 claim 和新凭据工作区。默认端口 8796，不改变原回环 8794。没有 Tailscale 或 VPN 目录为空不影响局域网。电脑与手机需要网络互通，访客 Wi-Fi／AP 客户端隔离可能阻断；Bonjour 不可用时使用私有二维码补充，不关闭证书验证。

## 保留原 HTTPS 入口

先复用已有 Serve 路由，确需新增时按 [Tailscale](tailscale.md) 或 [Windows](windows.md#tailscale-与手机) 完成。将已经用系统证书校验、鉴权并核对完整机器目标的实际根地址保存为 JSON 数组：

```sh
HOST_AGENT_HTTPS_ENDPOINTS='["https://computer.example.ts.net:8443"]' pnpm service:desktop restart
```

示例域名必须换成实际 Serve 输出；保留原端口和所有原环境／控制配置。Windows 对应设置此环境变量后调用 `pnpm service:windows restart`。两平台均持久保存该变量，最多四个 HTTPS 根地址，限定 Tailscale Serve 域名，不接受路径、查询、凭据或任意公网地址。纯局域网新安装省略此变量；升级不清空已有值，不为切换模式删除身份、证书或设备授权。

用当前凭据读取 `GET /v1/connection-routes?hostId=<准确本机 ID>`，核对 `version: 1`、完整 `target` 和 `httpsEndpoints`。此为独立鉴权只读契约，旧 `/host` 描述不增加字段，旧手机继续可用。列表只提供入口，不证明每条路径在线。

新版手机保留原连接的 Serve 地址，并通过上述接口学习新安装的备用地址；旧 Host 不提供接口时仍保留手机已保存地址。局域网／VPN 直连使用原持久证书，Serve 使用系统 CA 和主机名验证，不能将直连证书套用到 Serve。每次切换核对相同 Agent／Host／workspace／runtime，账号仅沿用既有 Codex 机器策略。

## 自动切换与验收

手机前台及网络变化时探测已保存入口，健康的当前入口继续使用；失败后切换到同目标可达入口。后台不承诺持续探测。地址变化不创建第二份电脑记录，不清除聊天、草稿、附件或操作 ID；在途写请求不自动取消并重放，未知结果仅查询原回执。

分别记录以下结果：无 Tailscale 的局域网发现／授权／真实任务；局域网与原 Serve 的相同目标和鉴权；两方向切换；App 重开及 Host 重启后的地址、授权与草稿保留；发送中换路的唯一输入及原回执。辅助检查、真实原生 App／模型、物理手机切网和目标电脑部署分别记证，不用本机通过替代另一台机器。

交付同时列出局域网地址和远程备用地址、已配置／可达／实测状态，并明确：“只在局域网可不安装 Tailscale；需要在外远控，请完成双通道配置，手机与电脑保持 Tailscale 在线。”
