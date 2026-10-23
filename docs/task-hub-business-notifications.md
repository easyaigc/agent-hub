# Task Hub 业务通知

状态（2026-10-14）：业务通知契约、加密窄接口、事务 outbox、原生解码和导航已实现。Relay 业务分支已部署，真实任务事件已取得 `accepted` 投递回执；手机实际到达、解密、声音及准确点击仍未验收。正式签名 iPhone 已完成部分连接、登录和恢复场景，不能据此认定业务推送通过；逐项证据见本次验收记录（本地文件 `docs/acceptance/task-management-ios-20261014.md`）。

业务 domain 固定为 `taskhub.business`，与执行通知的 RuntimeTarget／Thread／Run 分开。密文包含 deploymentId、serverInstanceId、primaryHostId、human actorId、projectId、taskId、roundId、revision、eventId 和业务种类。业务角标不进入现有对话未读账本。通知点击输出导航意图，需重新核对服务、人类登录、项目授权和准确轮次；它不执行派发、验收或未知操作重放。

## 发布和授权

业务工程的 `BusinessNotificationsService.enqueue(tx, fact)` 使用调用方事务写 outbox；`taskChanged` 从真实任务状态产生待响应、澄清、验收、失败返修或完成通知。普通进度没有提醒。执行受阻和未知结果使用 `executionChanged(tx, runId)`；前向迁移增加状态代际，重复状态轮询不会重复提醒。

订阅按服务、人类、设备和项目隔离。发送前重新检查原人类设备会话的撤销／到期、actor 状态、项目授权及归档。撤销先保存本地/服务端墓碑，再同步 Relay；已在 APNs 投递中的通知仍可能晚到，点击必须再次核对权限。未知投递只查原密文回执，不重复提交业务命令。

`businessNotificationCipher(privateKey)` 由规范 `notification-runtime` 导出，采用独立的 X25519／HKDF context `agenthub/taskhub.business/v1`，AAD 为 `taskhub.business:<eventId>`。业务后端通过 `BusinessCipher` 窄接口注入该产物，不复制 Host 凭据或伪造运行时目标。私钥由部署负责人保存在该主服务的私有配置中。

Relay 新增 `/v1/business-push/register`、`unregister`、`publish`、`status`、`binding-status` 和仅凭独立管理令牌撤销绑定的 `revoke`。新 `businessPublishers` 配置固定三个服务 ID 和独立 publisher token；可指定该发布者的实际 Apple topic。客户端不能自行选择 topic。Host 与业务 publisher 不能共用同一鉴权 token。设备绑定另包含 human actor、项目、设备 UUID 和订阅代际。

APNs 签名/发送复用既有实现，环境配置缺失时注册返回 `APNsEnvironmentNotConfigured`。`accepted` 只表示 APNs 接受；没有将它解释为 iPhone 已展示。密文使用 APNs 的 `businessNotification` 字段及 `agenthub.business` 分类。Relay 只接触密文和必要路由信息，不运行 Worker、不保存任务库或 Codex 登录。

## 原生接线

主 App 新增 `BusinessNotificationModels.swift` 和 `TaskHubBusinessNotifications.swift`。两个通知扩展均需编译前一个文件；它不依赖 TaskHubStore／Views／RootView。现有 `UNUserNotificationCenter` delegate 按 domain 分派并注册两个分类。

主任务通过 `TaskHubBusinessNotifications.shared.attach(service:actor:deviceId:sessionEpoch:request:)` 注入已鉴权且固定服务、人类、设备和登录代际的具名请求闭包。`APIRequest` 映射到业务后端的订阅、状态、补齐、撤销和阅读标记接口。业务写请求继续使用原业务操作账本及稳定操作 ID。请求超时后只能核对原回执，不能自动创建新订阅请求 ID 重放。

当前修复源码在一次明确保存后，对 pending 通道最多执行六次 GET，总期限十二秒；页面出现、前台恢复和手动刷新合并同一读取。解密绑定只采用同一作用域内最新不可变、已确认保存的偏好；较新的未知保存须先核对原回执。每次读取后核对服务三 ID、人类、设备、登录代际、项目权限、偏好及读取批次。读取鉴权失败只使本机状态失效，不触发撤销写请求。该修复已通过离线辅助检查，独立审查、完整原生编译及设备重验尚待完成。

`pendingNavigation.event` 包含完整目标；冷启动点击也保留意图。主任务校验最新任务和轮次后再打开，旧轮通知进入只读原轮。成功后调用 `navigationConsumed(eventId)`。退出／更换账号调用 `detach`；前台调用 `setActive`，以及只读 `catchUp(projectId:cursor:apply:)`。该方法只在父方成功应用数据后返回游标，游标持久化按原服务、账号和项目隔离。

AppDelegate 已接入主任务的 `TaskHubUploadCoordinator.handleBackgroundEvents`。未知后台 session identifier 立即调用 completionHandler，不宣称恢复了某个上传。

## 当前验证边界

新增测试运行隔离回环 Relay 和注入式 API 边界，使用合成令牌与密钥；没有访问真实 APNs、用户凭据、现有数据库或 liveprimary。测试覆盖 domain／鉴权隔离、加密篡改与跨绑定、去重、撤销、publisher 移除、未知结果重启、权限拒绝和前台降级。

`0010_business_notifications.sql`、规范加密 publisher factory 和业务通知 UI 已进入本次 DEV 部署。真实订阅、服务端绑定及业务事件投递回执已有证据，手机实际推送结果仍待核对。正式签名 iPhone 需分别核对实际 topic／environment、App Group、APNs 接受、锁屏到达、声音、准确 task／round 点击、权限撤销及漏推后前台补齐。既有对话通知通过不能补作业务通知通过。
