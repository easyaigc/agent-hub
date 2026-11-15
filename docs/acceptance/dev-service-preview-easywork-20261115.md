# F76 EasyWork App 内开发服务预览

日期：2026-11-15。范围：macOS EasyWork 真实 Electron DEV 与原生 iOS 模拟器，LAN/VPN 直连路径。独立候选已完成本文限定验收，未部署正式 EasyWork 或日常 Host。

## 已实现候选

- Codex Host 和 EasyWork 共用 Agent Hub `packages/dev-preview`；EasyWork 接收固定构建产物与 SHA-256 清单，不另行复制维护引擎源码。
- EasyWork EWDAP 创建/关闭具名服务，完整账号/电脑/workspace/runtime、原设备绑定与 `runtime:control` grant 约束保持。独立短期票据只打开固定服务的 WSS 预览流，不进入网页或其他控制接口。
- iOS 复用页面、非持久 WKWebView 和 loopback 网关，使用现有 EasyWork 加密会话与选中的固定证书 LAN 入口；Relay-only 明确要求切换直连。
- 设备撤销与目标变化关闭预览。关闭页面不停止 DEV；HTTP 业务请求不自动重试。Android、Windows、HTTPS 上游、Relay 预览与外部浏览器未实施。

## 检查与当前边界

共享引擎/Host 依赖构建、core typecheck、19 项 Host/打包/发现测试、discovery smoke 与 1757 条双语检查通过；EasyWork 7 项真实本地 HTTP/SSE/WS、IPv4/IPv6、目标/设备/票据/重复操作/关闭边界测试通过。Electron 原生 SQLite/PTY 加载检查通过。以上均为辅助检查，不单独证明原生页面与热更新通过。

EasyWork 首次 main TypeScript 检查出现 6 处模型运行类型错误，定位为本次 Bun 安装重新解析 `latest` 依赖；现已恢复原锁定版本，仅追加本任务 engine/workspace/ws 边，重新使用 frozen-lockfile 安装，未改动模型执行逻辑。恢复后 main TypeScript 检查通过；真实运行结果见下文。

F76 是新增独立功能，不计入既有 iOS × EasyWork F01–F75 唯一对齐台账分母；A6/F75 暂停保持。本记录不提升整项 F76 或其他平台的状态。

## 真实 Electron 与原生模拟器结果

[脱敏证明](mobile-evidence/dev-preview-easywork-20261115.json)与[热更新截图](mobile-evidence/dev-preview-easywork-20261115.png)已独立核对可读取，并逐项匹配当前产品源。真实 EasyWork Electron 42.7.1 使用生产 controller、embedded LAN host 与 EWDAP 加密会话；独立 iPhone 17 Pro / iOS 26.0 arm64 模拟器执行完整产品 App，XCTest **1 通过、0 失败、0 跳过**，build/compile/test 均退出 0。

- 页面从独立随机 A 更新为 B，同源 API 返回匹配内容；Vite HTML 1、API 1、WS upgrade 1，HMR 前后 HTML 请求仍为 1，预览 Cookie 未到上游。
- `runtime/context`、`dev/preview/create`、`dev/preview/close` 各有一次真实响应；关闭获得成功回执。原生网关、固定 TLS 指纹及独立浏览数据存储均走生产实现。
- iOS 聚合输入指纹前后均为 `04527f4e4ec825d846fe060e575889804b202f59576fd42f50a5393b10e785ce`，实际重签后 App 二进制/包树和 Electron 主输出/可执行摘要分别保存在 JSON，不把重签前可执行摘要当作分发包摘要。
- 实际平台登录已通过已有合法浏览器会话完成，手机远程凭据采用既有 loopback DEV authority；这证明本机配对 TLS/EWDAP 路径，不证明生产平台 grant 授予、实体 iPhone、物理 Wi-Fi/VPN 或 Relay。

原生测试通过独立 DEBUG 入口完成生产配对和预览，未通过普通发现/保存电脑页面进入；消息菜单、完整浏览历史和全部 PV 场景不因本次通过自动关闭。截图中有系统通知遮住部分顶栏，页面 B/API 和底栏仍可见；关闭另有 XCTest 操作与真实回执。

## 最终检查、历史失败与资源

共享 core 与 Host 候选完整 `pnpm check` 退出 0；Windows 专属测试在 macOS 按定义跳过。EasyWork main TypeScript、12 项直接相关测试、原生 SQLite/PTY 加载和全仓 `bun run lint` 通过。完整检查后的文档/清单只补格式和投影核对，不声称另跑全链。

先前 profile 路径、过期认证、验收场景遗漏分流和 JDK 配置问题均有明确修复并复测；失败不冒充通过，已修复的环境问题也不继续写作人类阻塞。执行入口请求 Terra/medium，未暴露实际模型/速度运行核验字段，不宣称其已核实。

验收后修复了 runner 对独立进程组的清理，单独通过进程/端口释放冒烟与静态检查；本次产品源码未变，测试驱动的运行前及修订后摘要分别保存，未重复无关 UI 验收。任务模拟器、Electron、端口、profile、站点、重复构建和失败现场均已回收；原始 xcresult 仅留任务私有证据目录作独立测试回执，正式 JSON/PNG 为可读最小证明。

本轮交付在 `codex/f76-easywork-preview-20261115` 开发分支，Agent Hub 与 EasyWork 采用各自正式推送流程；准确提交与远端结果以交付回执为准。未创建 PR、合并主线或替换正式安装。F76 其余平台/通道及既有 F01–F75/A6 暂停状态保持。
