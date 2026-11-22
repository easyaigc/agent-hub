# 办公室路由子网自动发现

2026-11-22，F06/F08，UI01/UI02/UI03，S01/S11。当前实施、验收及交付状态只见[唯一完整功能清单](../plans/android-ios-alignment-20261107/progress.md)的F06。本文定义本次修正；117 的显式 CIDR 入口和独立手机历史验收保留在原提交与台账，不构成本次自动学习通过证据。

## 根因与自动来源

117 的办公室范围为空时不启动扫描，提示中的地址只是 placeholder；原“重新查找”只调用 Bonjour／已保存候选／VPN peers。路由器批准子网使 IP 可达，不会把 LAN Host 加入 Tailscale 节点目录，也不会转发 Bonjour。旧 LAN 候选仍有效时可以恢复；DHCP 换址且没有新地址来源时不能推算新 IP。连接表单又把已保存电脑的各种失败统一转成 unavailable，并一律显示重新授权，不能据该按钮断言凭据失效。

普通 iOS App 的公开接口没有可靠枚举另一 App 已批准 VPN 目的范围的契约。`getifaddrs` 可提供本机接口地址及实际掩码，`NWPath` 观察路径，不是 Tailscale 路由目录。方案不读取别的 App 容器／配置／密钥，不抄写缺失的路由表私有常量，不把 Headscale 管理凭据放入手机。相关一手资料：[Apple getifaddrs](https://developer.apple.com/library/archive/documentation/System/Conceptual/ManPages_iPhoneOS/man3/getifaddrs.3.html)、[NETunnelProviderManager](https://developer.apple.com/documentation/networkextension/netunnelprovidermanager)、[VPN 路由](https://developer.apple.com/documentation/networkextension/routing-your-vpn-network-traffic)。资料核验和真实设备返回值分开记录。

自动来源只有两种：手机实际处于 UP/RUNNING 的非回环、非点对点 Wi-Fi／Ethernet 接口的 IPv4 地址与真实连续掩码；或已绑定 Host 经原认证连接返回的真实 LAN 范围。手机不从蜂窝私网地址、utun 地址、单个旧 IP 或常见地址猜 `/24`。Host 复用已有私网地址广告资格，保留中文及用户重命名的真实接口，排除已识别的隧道、回环、内部／host-only 虚接口；网络范围必须完整位于 RFC1918，并来自实际连续掩码。接口显示名称不作为英文正向准入条件。

## 独立已鉴权契约

`GET /v1/discovery/networks?hostId=<saved-host>` 使用现有 Host／设备 token、原 HTTPS 信任、Origin 拒绝与准确 Host 查询校验。仅一个 hostId 参数；重复／附加参数、错误目标拒绝，POST 不支持。响应：

```json
{
  "version": 1,
  "target": {
    "agentId": "codex",
    "hostId": "host",
    "workspaceId": "host",
    "runtimeId": "desktop-runtime"
  },
  "networks": [{ "address": "10.20.5.17", "cidr": "10.20.4.0/23" }]
}
```

`address` 是符合 LAN 广告条件的真实本机接口地址；`cidr` 按该接口真实掩码计算。范围提示不是该手机的 ACL／有效 VPN 路由证明，不证明其中运行 Host，更不授予连接授权。它是独立契约，不向 `/host` 或 VPN peers v1 增加未知字段。网络数组无 peers 的 32 项裁剪，亦不依赖旧描述最多 32 条广告；已鉴权网络清单独立完整读取，不沿用公共 `/host` 的 64 KiB 上限；旧描述体保护保持原样，不裁剪或静默丢弃合法清单。

App 在严格验证 HTTPS、完整原目标及相同 pin 后读取此接口；公开未知 Host 发现不发送凭据，不自动扩展范围。404／不支持版本／畸形或公有范围／目标不符／读取失败不覆盖已有学习数据，不撤销正常连接能力。新接口只依赖现有 Host 程序和正常设备授权，无路由器新组件、新监听、DNS、ACL、WAN 或管理员 token。

## 正常学习、保存与恢复

原主页面前台生命周期已经运行 `CodexHostDiscovery.run`，其中已保存电脑的原认证探测会读取新接口并通过 `SessionStore.updateCodexDiscovery` 保存；不要求先打开添加电脑。正常首次授权和已知电脑显式连接也学习。旧 Host 若无新接口，实际已核证书／目标的 LAN 连接仍可用手机实际接口掩码学习该网段；不会从纯远端 IP 推断范围。

`CodexDirectConnection.discoveryNetworks` 为可选持久字段，和原记录、完整 target、pin 一起保存；旧 JSON 缺字段仍可读取。临时断网／旧端不支持不清除数据，不设短 TTL。新的有效响应更新对应来源；删除某条连接只移除它自身学习数据，其他来源保持。广告、observedEndpoint、凭据、草稿、原操作 ID 和未知写入各自保留。

发现页无需维护或手填 CIDR，自动使用当前真实 LAN 与已绑定电脑学到的范围。117 的旧手填 UserDefaults 值不参与新扫描，也不作为新验收种子。范围去重并消除完全包含的重叠；关联真实 Host 地址优先探测，之后完整惰性扫描其余地址，每个地址仅探测固定 Codex 8796／EasyWork 4319 端口。多个范围轮流调度，合法大范围不饿死其他小范围；不增加前缀硬拒绝或主机数截断，仍复用四并发和单探测原预算。界面显示实际完成／总数及可取消状态。

网络路径或已学范围更新时按代次取消旧扫描并重新调度；手动停止保留本轮结果／进度，迟到回包不新增条目，也不自动覆盖用户的停止意图。退出发现页或进入后台停止扫描；新一轮／范围变化才重置本轮结果。不增加后台定时扫网。地址优先探测和原已保存候选恢复不必等待全范围结束。

初次学习需要至少一条原可信连接可达，可为已有 LAN 或电脑 Tailscale 路径；学习后，所有电脑退出 Tailscale 仍可经手机与路由器发现和重连。若此前从未学习范围、所有旧地址均失效、又没有本地 LAN 提示，则缺少必要信息，不能宣称零前提发现；界面提示先恢复一次原连接，不让用户猜 CIDR。本轮不引入另一台电脑长期运行 Tailscale 的依赖。

## 身份、授权及错误

公共 GET `/host` 的名称仅是候选提示。新电脑点击仍走原 Host Key／设备配对；已保存电脑先核完整目标、原证书，EasyWork 另核绑定公钥，同机换址不新建记录。实际观察地址继续独立于原 32 条广告存储，不关闭证书验证。

显式已保存电脑连接等待真实 workspace 读取，保留其实际失败；仅已认证请求的明确 401 才提供重新授权入口。超时、DNS／VPN／网络不可达、普通 403、目标或证书不符均不能被泛化成凭据撤销，不清 token／记录／草稿或重新 claim。现有通知注册等独立错误不扩大为本次连接缺陷。

## 双端和验收边界

iOS 共用自动范围调度和 Codex／EasyWork 列表。Host 范围来源先由 Codex Host 实现；EasyWork Host 可沿同一领域契约适配，当前不假称其独立提供网络接口。只有 EasyWork 且在远端从未学过范围的组合继续待该适配或一次真实 LAN 学习。Android 自动学习／发现入口未实现，协议、持久化及同一授权边界为待对齐断言，不能借用 iOS 结果。F75/A6 暂停保持。

受影响检查：真实接口掩码而非 `/24` 猜测；40 范围及超过 64 KiB 的 2,048 范围完整学习；中文／重命名接口保留和虚接口排除；旧 Host404、未知版本、畸形／公有范围、target/pin 不符、撤销与断网；正常页面学习、无旧手填种子、关联地址优先、完整末地址、重叠去重、停止保留和迟到屏障；原记录/存储兼容及未知写入不重放。编译、fixture、模拟器、独立15和原用户手机分别留证。

真实退出门槛：已学范围来自本轮正常可信连接，手机在办公室外蜂窝／其他 Wi-Fi 经既有 Tailscale 子网，相关电脑退出 Tailscale且 Host 持续运行，正常自动页发现正确名称、原 pin／目标／凭据连接与同记录冷恢复；原用户手机原5记录/授权/草稿保留由来源协调，不能用原15的新绑定或117手工CIDR结果代替。
