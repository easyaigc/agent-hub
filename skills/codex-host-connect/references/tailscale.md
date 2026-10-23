# Tailscale 安装和 HTTPS

这是跨局域网的私有网络接入，不是本项目的公网 Relay。手机仍需 Tailscale VPN，电脑保持登录、联网和运行。使用官方安装与 CLI 文档，安装时重新确认最新稳定版本，不固定本机版本或过期安装包 URL。

## macOS 安装

先检查是否已有 Tailscale 客户端、登录状态和 Serve 配置；有正常安装就复用，不并排安装两个变体或切换账号。

没有安装时，从 [官方 macOS 安装页](https://tailscale.com/docs/install/mac) 进入 [稳定安装包目录](https://pkgs.tailscale.com/stable/)，取得当前 Standalone `.pkg` 的确切链接。下载到本机私有临时目录，核对来自官方服务器、`pkgutil --check-signature` 显示受信任的 Tailscale 发布者签名，再打开安装包。不要关闭 Gatekeeper 或猜测 `latest.pkg` 地址。系统管理员认证由用户在系统界面完成。

打开 Tailscale，按客户端流程启用系统扩展/VPN 并由用户登录目标私有网络。同一私有网络可使用同一账号或已获授权的成员身份；设备审批如有要求需在管理员界面完成。不创建或读取登录凭据。

CLI 已在 PATH 时使用 `tailscale`。否则先使用应用内的 CLI；可选通过 Standalone 设置中的 CLI integration 安装命令入口，不必改 shell 配置：

```bash
TAILSCALE_BE_CLI=1 /Applications/Tailscale.app/Contents/MacOS/Tailscale version
TAILSCALE_BE_CLI=1 /Applications/Tailscale.app/Contents/MacOS/Tailscale status --json
TAILSCALE_BE_CLI=1 /Applications/Tailscale.app/Contents/MacOS/Tailscale serve status --json
```

`status --json` 可能包含账号与设备信息，只在本机检查，不贴原始输出。确认 BackendState 为 Running、本机在线且加入目标网络。[CLI 说明](https://tailscale.com/docs/reference/tailscale-cli?tab=macos)

## Serve HTTPS

先检查当前 Serve/Funnel 路由。默认 HTTPS 443 空闲时代理本机 Host：

```bash
TAILSCALE_BE_CLI=1 /Applications/Tailscale.app/Contents/MacOS/Tailscale serve --bg 8794
```

首次按 CLI 提供的网页完成 HTTPS 功能启用，相关账户操作由用户完成。Serve 生成 `https://device-name.tailnet-name.ts.net`；记录实际输出，不拼接或猜测 DNS 名称。[Serve 文档](https://tailscale.com/docs/features/tailscale-serve)

若 443 已有别的服务，保留它，选择空闲 HTTPS 端口，例如 `serve --bg --https=8443 8794`，手机地址包含 `:8443`。不执行全局 `serve reset`，不把现有 Funnel 公开入口当作私有入口，不放开整个 tailnet 的访问策略。新入口只需允许目标手机访问所选 HTTPS 端口。

用脚本检查 HTTPS，保持证书校验：

```bash
node skills/codex-host-connect/scripts/probe-host.mjs \
  --state-dir "$CONNECT_REPO/.local/desktop-runtime" \
  --endpoint 'https://device-name.tailnet-name.ts.net'
```

从本机访问 HTTPS 成功不能算手机跨网络通过；后续必须在另一网络/蜂窝网络验收。Tailscale 可能使用直接连接或网络层加密中继；两种都不意味着本项目 Relay 已实现。[连接说明](https://tailscale.com/docs/reference/connection-types)

## 手机配置与排障

按 [官方 iOS 安装说明](https://tailscale.com/docs/install/ios)，让用户安装 Tailscale、允许 VPN 配置、登录目标私有网络并开启连接。默认在 Agent Hub“添加客户端 → Codex → 自动发现”点选电脑，按 [Host 部署检查](host.md#身份与本机检查) 核对私网自动授权及完整工作区目标，首次无需扫码或填写密钥。首次纯跨网且没有 Bonjour／缓存目录来源时，单列发现入口缺项；不能将 Serve HTTPS 成功等同于自动发现或公钥授权可用。

手动地址与二维码保留为补充入口。选择手动添加时填写实际电脑名称、HTTPS 根地址和本机客户端密钥；按 [Skill 交付格式](../SKILL.md#配置后的必交付参数) 提供可用参数，App“客户端密钥”只填令牌原文，不加 `Bearer `，保存到 Keychain。普通探测、日志和可分享报告不包含密钥。

回环失败先修 Host；回环成功而 HTTPS 失败检查 Tailscale 在线、Serve 后端端口、HTTPS 功能、证书和 tailnet 权限。HTTPS 成功但手机失败检查手机 VPN、网络身份与目标端口。401 检查令牌；Host ID 不符立即停止控制并纠正地址/身份，不能自动接受另一电脑。

需要断开本次接入时只移除本次创建的 HTTPS 路由（先检查当前 CLI 的关闭用法），停止本产品 Host；不退出用户原有 Tailscale 网络或删除其他服务。
