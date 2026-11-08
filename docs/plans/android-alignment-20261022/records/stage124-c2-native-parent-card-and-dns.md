# 阶段124 Codex 原生父卡片与 EasyWork 原域名

2026-11-08。续接[阶段123](stage123-c2-codex-live-parent-and-copy.md)。验收冻结 Agent Hub 基线 `58484129` 与 Codex 候选 `92f46750`。交付前主线增量为 `e7b6eef1` 的 Windows Responses 转发退役，保留本次实际候选指纹。EasyWork 先前合入的原授权及原内容读取见[阶段122](stage122-c2-easywork-real-dev.md)。

## 共享真实 Host 基线

以候选源码启动独立 Host Agent，连接原 Codex 桌面 runtime。专用 HTTPS 入口校验证书与密钥，只开放准确父会话的历史及事件读取，工作区目录仅返回该会话和所属项目。真实 `latest-v1` 页返回 100 条消息，其中 5 张稳定 ID 的子代理卡片，含进行中与已完成，正文约 11 KiB 至 197 KiB UTF-8。事件接口同一 epoch 的游标从 1455 到 2385，五张卡片 ID 保持，两张正文摘要真实变化。错误会话的读和写请求均在验收入口被拒绝。实际历史正文未落入证据档案。

## 功能、交互与界面

Android API 33 实体设备和 iPhone 均在线。Android 对原一次性验收包名执行本轮一次正常安装，系统再次以 `INSTALL_FAILED_USER_RESTRICTED` 拒绝。候选 App 与 runner 未安装，原 App 和剪贴板未操作。Android 真机 Codex 卡片与系统选择复制继续待验。独立 API 33 模拟器通过生产 `NativeHostReads` 和原生 Compose 卡片验收。真实 HTTPS 读取首次正文摘要与独立记录匹配，完成状态、标题和展开全文正确，第二次网络读取后仍为同一稳定卡片并显示逐字一致正文。该用例没有完整 Session/RootView，也未观察到状态变化。首轮测试 runner 漏编目标类失败保留，最终 1/1。

iOS 独立完整候选因通配签名配置缺少应用组、推送等能力未构建成功。独立限权组件包及 UI Runner 构建并安装成功。官方物理 XCTest 连续两次在运行前发生 IDE 通道拒绝，runner 退出 74，未执行手势或页面断言。随后 CoreDevice 直接启动该独立 App 成功，生产 HostService 仍在 Wi-Fi 路径被系统拒绝，返回 NSURLError -1009，读取代次为 0。没有渲染卡片、状态、刷新或手势结果。原独立验收 App 及 runner 均保持原安装。

## EasyWork 原域名

当前系统、局域网解析和 MagicDNS 对旧域返回 NXDOMAIN，当前 Headscale 只权威解析现用域。旧证书的 SAN 匹配且在有效期，使用进程级精确解析覆盖时，原 origin 保持标准 TLS 且 `/health` 返回 200。历史账户配置存在，尚无法证明其属于旧权威域、仍有效或拥有管理权限。普通 DNS 恢复继续待验；当前 Tailnet、共享解析器和设备设置均保持原状态，原授权未重放。

## 交付与证据

验收范围按准确 Host、设备、源码和签名包分列。CoreDevice 启动诊断意外回显本轮临时只读令牌，已关闭独立 HTTPS 入口与回环 Host，删除令牌、含凭据的临时文件及 Host 状态。Guard 统计 15 次只读转发，写请求和错目标未转发。已回收隔离设备包、AVD、工作树及构建中间产物。过程证据存于私有阶段目录的 `c2-stage124-host`、`c2-stage124-ios`、`c2-stage124-android` 与 `c2-stage124-dns`。本轮只更新验收记录，Codex 仍在专用候选分支等待双端真机门槛。任务和项目管理继续暂缓，未知操作未重放。
