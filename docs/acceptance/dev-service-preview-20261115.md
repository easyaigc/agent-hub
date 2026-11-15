# F76 App 内开发服务预览：首期验收

日期：2026-11-15。范围：macOS Codex Host 与原生 iOS App 内单个本地 HTTP DEV 服务；当前未部署日常 Host，未提交或推送。

## 实现范围

- 公共版本化协议、严格创建输入、短期会话与鉴权 WebSocket 数据入口已接生产 Host；常规 HTTP、API 请求体、流式响应及内层 WebSocket 均经 Host HTTP 解析器转发，禁止任意 URL、CONNECT 与管理端口。
- macOS 每次 HTTP 请求和 WebSocket 升级前核对本机监听 PID、进程开始时间及 UID；没有唯一监听或所属用户不符则拒绝。用户从消息或菜单明确登记服务；本轮不声称自动证明监听目录属于聊天项目。
- iOS 原生 loopback 网关、独立非持久 WKWebView、网页会话 Cookie 与 Host 凭据分离，手机请求 Host 时复用现有证书固定。消息本地链接进入指定电脑的预览表单，聊天菜单可手动填写 URL 或端口。后台结束会话，关闭不停止 DEV。
- 短期会话 30 分钟；并发创建同一 operation ID 收敛为一份授权，不同意图拒绝；关闭保留进程内回执。无在途 HTTP/WS 自动重放。Host 重启后不恢复旧授权。
- `ws` 为精确锁定运行依赖，统一接入包复制依赖闭包。Android、EasyWork、Windows、HTTPS 上游、独立浏览器、自动发现/持久服务列表及多 origin 映射继续待开发。

## 证据与限制

[Host 脱敏证据](mobile-evidence/dev-preview-host-20261115.json)记录 Node 24 下两项实际边界测试、源与 dist 指纹：生产 `startRuntimeServer` 路由、真实本地进程身份、HTTP 页面、二进制 POST、SSE 首块、内层 WebSocket 双向、Cookie 隔离、来源/目标拒绝、并发创建、管理端口、关闭与过期/目标变更撤销。CLI JSON-RPC 使用专用 fixture，不是 Electron 或模型验收。

测试发现 `HOST_AGENT_PORT=0` 时未排除实际动态 Host 端口，修复后定向复测通过。过期反例通过调整测试会话的内存到期时间执行，不等同真实等待 30 分钟。

原生结果见下文；Host 的生产路由测试与原生测试使用的薄 Host 分别记证，互不冒充完整真实桌面或物理网络通过。

## 运行与复测

```sh
pnpm check:dev-previews
DEV_PREVIEW_VITE_ENTRY='<installed-vite>/dist/node/index.js' pnpm test:ios:dev-preview
```

验收脚本使用独立模拟器、App/Runner 与站点目录，调用生产 `DevPreviews`，真实 Vite 加同源 API；固定测试 scope 的薄 Host 不代表真实桌面会话集成。默认独立 DerivedData，可显式选择已核对无并发写入的构建缓存；共享缓存不作为可删除临时资源。源码/构建指纹变化、缺失页面/API/HMR、热更新期间重新请求 HTML、未撤销会话或 Cookie 泄漏均不记通过。

完整 `pnpm check` 首次在格式检查阶段受并行工作文件及待格式化验收脚本阻断，后续步骤未运行；具体重测结果另记。早期原生验收构建遭磁盘不足和测试脚本错误，没有启动模拟器，不计产品失败/通过；任务自有重复构建目录已由执行方回收。执行 Agent 请求 Terra/medium，接口未暴露可独立核验的实际模型/速度记录，不把请求参数当作实际设置证据。

## 最终冻结候选的原生结果

[原生脱敏报告](mobile-evidence/dev-preview-ios-20261115.json)与[热更新截图](mobile-evidence/dev-preview-ios-20261115.png)已经核对可读取。完整产品 App 在独立 arm64 DerivedData 编译，独立 iPhone 17 Pro／iOS 26.0 模拟器执行 XCTest：**1 通过、0 失败、0 跳过，退出码 0**，没有运行时警告。所有登记的产品输入文件在本轮前后逐项保持，隔离候选现场指纹均为 `435f5e09b6c5fd17abcb44e6b4d707b3dae565cc98bc36d8f7350b3f8ee31225`。

- 真实 Vite 6.3.5 页面显示 A，原生点击调用同源 API 后显示独立随机返回值；修改实际源码后页面自动显示 B。
- Vite 的 HTML 请求数在热更新前后均为 1，API 为 1、WebSocket upgrade 为 1；生产 `DevPreviews` 记录 HTTP 转发 6、upgrade 1，排除了模拟器直接绕过 Host 的假阳性。
- 原生 Origin 反例及关闭后旧会话均被拒绝，上游计数保持；短期预览 Cookie 没有泄漏给 Vite。
- 关闭、前进、后退、刷新控件存在；前进/后退的历史操作未单独执行。导航、Cookie 登录、多服务隔离、弱网、后台恢复及全部 PV 矩阵不因这次通过自动完成。

此原生场景采用生产 `DevPreviews` 和真实监听身份检查，外围 Host 为固定测试 target/thread 的薄路由；不宣称原生连接完整 RuntimeSession、真实 Electron、模型或物理 LAN/VPN/TLS。完整 Host 路由另有 CLI fixture 集成测试；真实物理连接仍待相应包与场景验收。

报告的 `productBinarySHA256` 为构建主可执行文件在验收隔离/重签前的摘要，**不是重签后 App 全包或正式分发包摘要**；产品输入与测试转换脚本另有完整摘要。当前源码与限定交互通过不等同已部署或冻结交付包，F76 全范围仍不关闭。

## 最终检查与资源回收

Host 定向测试 3/3、统一接入包 fixture 13/13（明确包含 ws）、发现/恢复测试 7/7 和定向 ESLint 通过。实际 `0.0.0.0`、IPv6 `::1` 及 localhost 解析到 IPv6-only 的服务均通过 HTTPS/WSS Host 边界；该 TLS 测试使用任务证书，不替代手机配对 TLS 验收。localhost 地址族选择在发出 HTTP 前完成，实际网页请求不因地址族失败而重放。

本任务独占的旧候选、重复构建、App/Runner 副本、xcresult、站点、临时服务和证书均已回收，专用模拟器登记已删除；旧孤儿诊断进程经核对 PID/启动时间/命令后正常终止。原生最后阶段卷可用空间从 6.2 到 6.8 GiB，存在并行磁盘活动，不能把该差值当作独占物理回收量。共享 `.build/ios`、正式源码、锁文件与最小验收证据保留；本任务没有创建工作树、分支或独立聊天，也没有提交/推送/部署。

`pnpm check` 曾在并行文件格式检查处停止；清单生成内容已更新，但完整历史指纹校验另受未由本任务修改的 `AgentClient.swift` 等 WIP 记录变化阻断。不能把定向通过解释成全仓检查通过。最终命令快照按实际结果追加，不改写原始失败。

原生原始摘要仅保留在私有 `.local/dev-preview-acceptance/evidence/final-report.json`（约 2.6 KiB），旁有负责人及释放条件：源码和对应正式验收记录提交且指纹保留后可移除。重复截图与源码清单已核对正式 JSON/PNG 的摘要后删除；无需为保留原始摘要留下构建环境。

最终候选验收后，并行工作又修改了两个共享本地化文件和 `Uploads.swift`；正式原生证据继续绑定被测候选的原指纹，不升级为整个较新工作区通过。F76 生产实现文件本身仍匹配；本次额外双语检查通过，完整清单 change coverage 仍准确报告共享文件的记录漂移。

最终轻量检查：`pnpm check` 仅在 `docs/plans/ios-easywork-alignment-20261112/status.json` 的格式处停止，后续阶段未运行；`pnpm check:ios:localization` 通过。9 份相关文档格式及相对文件路径存在性检查通过；manifest 结构和生成投影通过，change coverage 因共享本地化文件后续 WIP 漂移失败。未为消除这些失败修改其他任务内容或伪造指纹。

## 隔离交付候选复验

从已授权开发输入提取的 F76 专用工作树补齐证书会话选择方法后，独立产品构建及 iPhone 17 Pro / iOS 26.0 模拟器 XCTest 通过（1 项，0 失败），真实 Vite 页面、同源 API、HMR 与关闭撤销通过。上述 JSON 和截图已更新为本次隔离结果；Host 为固定测试目标的薄路由，未验证 Electron、模型或物理网络。首次运行缺少本机 Java 配置，配置已有 JDK 17 后重试；随后暴露提取时遗漏的证书会话方法，补齐后复验通过。

报告中 `productBinarySHA256` 为隔离重签前产品主二进制；`acceptanceBinarySHA256` 为实际验收 App 重签后执行文件；`acceptanceBundleTreeSHA256` 按脚本记录的相对路径、类型、权限和内容计算签名后 App 包树摘要，均不是正式分发包摘要。验收重用本任务前一次失败构建的独立 DerivedData，不是共享构建缓存。

隔离树完整 `pnpm check` 已执行：先后在 `check:agent-contract` 因未配置本机 Java、未选择 Xcode 退出；显式使用本机 JDK 17、Kotlin Native 和 Xcode 后，该失败项单独复测通过。完整链正在以修正环境重跑，结果以交付回报为准；此前已通过的检查不等同全链通过。`pnpm build`、F76/打包/发现定向 23 项及本地化检查通过。

## 首期提交后全仓检查

首期已提交并推送 Gitea `codex/f76-dev-preview-20261115`，提交 `2d05a918b245595d01870d7ec8a0d8b23634d22b`，远端 SHA 已核对。使用进程内 Node 24 / JDK 17 / Kotlin Native / Xcode 环境，完整 `pnpm check` 最终退出 0；此前环境失败保留为历史，不代表最终仍阻塞。Windows 专项在 macOS 按测试定义跳过，不升级为 Windows 验收。首期独立交付工作树在保存证据、确认无独有改动或依赖后经 Git 正常移除，分支因未合并保留。EasyWork 后续在另一独立候选开发，不修改本次已推送源码。
