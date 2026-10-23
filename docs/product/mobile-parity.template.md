# iOS / Android 功能与一致性清单

版本：3。更新时间：2026-10-12。A0 起始时读取远端 `main` 的 `e80e5a2911fa4aa0a3daafae73d261aed12853ac`，并在独立开发工作树合并用户已有变更，输入快照及合并提交见[阶段实施记录](../acceptance/android-stages.md)。A0 的 14 组／48 次原生合成基线验收已通过；对应范围及未覆盖状态见阶段记录，不能把源码存在或合成回归记为真实 DEV 通过。

A3 推送前已合并最新 main 提交 7d1e62f，新增连接方式、阅读／分页行为纳入本阶段回归；新增独立文件互传 F59 安排在 A6。阶段验收与提交推送均需更新开发进度表，当前 A3 仍未封存。

此清单管理当前 iOS 功能向完整 Android 版本扩展，以及后续任一端的新增／优化。A1 已建立 Android／KMP 工程，两端实际采用共享目标缓存键与输入栏同步规则；安卓目前只有基础示例页面，以下完整产品功能仍待开发，A1 双端模拟器、共同向量及输入性能门槛已通过，证据见阶段记录。iOS 列只代表源码中存在实现，不代表本次真实 DEV 通过；既有验收资料的版本和范围必须另行匹配。执行阶段见[开发计划](../plans/android-native.md)，UI 见[双端规格](../design/mobile-parity.md)，S 编号见[验收场景](../acceptance/mobile-parity.md)。

## 状态与维护方式

- 实现状态分别记录：待开发、开发中、已实现、平台不适用。平台不适用须说明系统依据、等价行为和用户可见差异，不能用它掩盖未开发。
- 真实验收状态分别记录：未验收、本次通过、匹配复用、失败。编译、单元测试、fixture、截图比对是辅助证据，另列结果。
- 每项按 Agent／接入模式／手机平台／设备环境细分。Provider 不声明的能力两端均隐藏或拒绝；“未支持速度”“原桌面不能添加项目”等不能变成 Android 独有缺失或伪造成功。
- 每次任一端新增、优化、修复或调整 UI／协议，先更新对应 F 行的断言、影响端、S／UI 关联和基线，再实现与验收。共用规则同时回归两端；平台改动解释另一端是否受影响。
- 新功能使用新 ID，已有 ID 不改作另一功能、不删除失败记录。删除功能须注明版本与原因；阶段性交付保留所有未完成项。
- `mobile-parity.manifest.json` 是功能定义的唯一入口；本页表格由 `pnpm generate:mobile-parity` 生成。共同断言、来源、UI／S 关联和实现／验证状态均在 manifest 维护，`pnpm check:mobile-parity` 检查完整性及生成内容。

现有真实结果入口：[iOS 本机](../acceptance/ios-local.md)、[EasyWork](../acceptance/easywork-dev.md)、[独立桥接](../acceptance/desktop-prepared-current.md)、[通知](../acceptance/task-notifications.md)、[未读](../acceptance/unread-state.md)、[角标](../acceptance/unread-badge.md)、[权限](../acceptance/permissions.md)、[发送性能](../acceptance/send-performance.md)、[失败提示](../acceptance/receipt-notices.md)、[最终回复操作](../acceptance/run-reply-actions.md)。引用历史结果不自动将本表某项标为通过。

<!-- mobile-parity:generated-tables -->

## 每次变更与验收的登记模板

从本次规划开始，功能变更应附下列信息，并更新对应行；历史基线和既有失败记录保留。

```text
变更版本／提交：<commit or source digest>
功能 ID：<Fxx, new ID if needed>
行为变化与共同断言：<before / after / expected result>
影响范围：<core / iOS / Android / Host / Provider / protocol / UI>
另一端处理：<same change / unaffected with reason / pending implementation>
场景与 UI：<Sxx cases, UIxx states>
iOS 实现／辅助检查／真实验收：<separate statuses and evidence references>
Android 实现／辅助检查／真实验收：<separate statuses and evidence references>
平台差异／阻断／负责人：<reason, equivalent behavior, owner, next action>
```

Android 达到完整交付前，可有分阶段版本，但必须列出待开发和未验收功能。后续新增功能默认覆盖两端；确属平台能力差异时记录适用范围与等价行为，不能只更新完成的一端。
