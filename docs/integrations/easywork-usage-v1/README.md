# EasyWork usage/read v1

本目录保存额度集成的增量差异和输入／输出指纹；以 `manifest.json` 指定的开发现场为输入，保留此前的未提交工作。此补丁不是整个 EasyWork 仓库的导出。

权威读取固定为原桌面认证服务的 `workbench-platform/v1/account-summary` GET；凭据不离开桌面。优先读取明确返回的 availableAmount，否则 amount；API 缺省币种按其既有 resource 钱包契约处理。membership 到期时间不充当余额到期时间。源 JSON 数字先保留词法原值，再规范化为领域十进制字符串。

固定 `usage/read` 请求包含 schemaVersion、四字段机器目标、requestId；原 EasyWork 会话认证及账号约束保持。只有组装额度服务后才声明 `usage:read:v1`，不开放方法或 URL 透传。换账号和来源变化按独立额度快照处理。

公共源码位于 `packages/protocol` 和 `packages/easywork-adapter`。构建后运行 `node scripts/distribute-usage-runtime.mjs <EasyWork checkout>` 分发标准程序；EasyWork 工作空间更新依赖锁后完成桥接组装。该脚本只增加额度模块及导出，保留已有通知运行时代码；先使用目标仓库锁定的 Oxfmt 格式化新适配包，再记录产物指纹。零上下文增量补丁使用 `git apply --unidiff-zero`。

本次最新 master 基线的桥接／控制器 22 项及授权生命周期 2 项测试、标准适配器 7 组测试和全仓 typecheck 均通过。早期四处类型错误由非冻结安装导致的依赖漂移引起，已恢复锁定版本；没有修改模型循环源码。真实原 Electron 线上余额与独立账户摘要比对、原生 iOS 模拟器 LAN／Relay 的两处展示和点击流程通过。实体 iPhone 的独立原生 App 应用内 XCTest 本次通过，snapshotId 和精确余额均与桌面实际回执独立匹配，原生额度视图渲染通过；物理触控仍与模拟器触控证据分列。

控制器为额度读取记录授权代次：授权失效或桥接替换后，之前在途读取返回 not-authenticated，不能把新账号余额交付给旧会话目标。
