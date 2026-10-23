# 任务通知与铃声

通知领域契约、Host 持续观察、独立 Relay 推送服务、iOS 设置与通知扩展已实现。编译、边界检查及模拟器结果属于辅助证据；正式签名 iPhone 的前台、后台、锁屏展示和播放结果分别验收。APNs 接受请求不等于手机已显示通知。

## 默认与范围

“设置 → 通知”的总开关默认关闭。开启时申请系统权限。待审批、待回答、计划待确认、暂停、异常中断、失败和操作结果待确认默认开启；正常完成、连接异常和手机主动停止默认关闭。每类可调整，默认覆盖全部已连接电脑，可按电脑关闭。

设置包括前台横幅、声音、角标、对话标题与任务摘要预览及及时通知。新设置默认开启会话标题与待处理概要预览，已有关闭选择保持；及时通知默认关闭。音量、振动、锁屏和专注模式由系统控制，设置页提供系统通知入口。测试通知使用当前默认声音。

## 手机未读对话角标

应用图标数字表示这部手机尚未查看的对话数，汇总全部已添加的真实客户端与电脑。同一完整目标下的对话最多计 1，连续回复和多个待处理事项不重复累加；不同电脑、账户或 runtime 的同名对话分别计数。电脑端已读不会清除手机未读。手机在前台、会话无遮挡、最新内容实际可见时记录已读。看过审批、问题或计划后角标可减少，待处理卡片和运行状态继续保留，阅读不执行审批或回答。

主 App 与通知服务扩展共用设备本地未读账本，使用进程间文件锁和原子写入。加密事件携带完整阅读目标与可选回复版本，重复事件、迟到推送和旧快照不会恢复已经看过的同一内容。迁移不复制另一设备的阅读账本。旧 Host 缺少回复版本时只能依据已有摘要基线、实际缓存回复及通知身份维护手机本地记录。

前台每 10 秒刷新全部连接的工作区分页，未切换到的电脑也参与汇总。通知分类关闭但角标开启时，Host 可以发送仅供账本更新的加密事件；Relay 使用 `content-available`、background 类型和优先级 5，不带提醒、声音或某台电脑计算的角标总数。iOS 后台更新由系统调度，可能延迟或不投递；重新打开 App 后补齐计数，强制退出期间不能保证及时更新。[Apple 后台通知规则](https://developer.apple.com/documentation/usernotifications/pushing-background-updates-to-your-app)

通知总开关关闭时停止订阅，前台未读记录仍保留；“显示未读对话角标”关闭仅将图标数字归零，不删除阅读记录。关闭某台电脑的通知不会把其他电脑的未读数字清零。专项证据与正式环境待验项见 角标验收（本地文件 `docs/acceptance/unread-badge.md`）。

内置 CAF、系统默认和导入铃声支持全局选择及分类覆盖，分类也可静音。WAV、AIFF、CAF、M4A、MP3 经手机本地 AVFoundation 流式转换为单声道 PCM CAF；输入限制 10 MiB、29 秒，损坏或超限返回明确错误。文件存入 App Group 的 `Library/Sounds`，主 App 与通知扩展共享，音频不会上传。删除正在使用的导入铃声恢复默认选择。[Apple 通知声音要求](https://developer.apple.com/documentation/usernotifications/unnotificationsound)

## 观察与投递边界

`packages/protocol` 定义通知 v1；`packages/notification-runtime` 是通用观察和投递组件。Codex wire 位于 Adapter，EasyWork 接入现有注册适配器、完整目标和命名协议。EasyWork 使用本仓库构建的同一协议与通知组件产物，不能独立修改分发产物。

Host 监听真实运行事件，并独立发现授权目录里的桌面任务，不依赖手机当前聊天或主 App 生命周期。Codex 的桌面广播提供实时发现提示，提示须经实际 owner 核对后接入；后台目录每 10 秒补充发现，EasyWork 为 3 秒。历史目录的 owner 探测最多并发 4 项，避免挤占审批与停止的发现预算。首次快照只建立结束状态基线，现有待审批、待回答、计划和明确未知回执可提醒。已发现任务基线与停止意图保存在 Host 私有状态，重启后继续去重。

通知开启时，新目录任务优先探测；元数据先于原桌面 owner 出现时，在首次发现后的 120 秒内随目录刷新重试，仍遵守并发上限。另一个来源的实时帧仅用作发现提示，即使旧 owner 仍连接，也先重新核对实际 owner，不能直接接纳提示帧的内容或结束状态。关闭通知后取消这项优先发现。

只有实际运行状态和实际结束原因能触发任务结束类型。历史读取、`unknown`、owner 丢失和普通读取取消都不能触发“任务终止”，也不作为审批已处理的证据。连接异常是单独、默认关闭的类型，仅在真实连接由已连接变为断开时生成。未知操作提醒引用已有回执，不重发输入。

桌面新建仍在提交和核对原 owner 时，持久日志里的暂时 `outcome-unknown` 不产生“操作结果待确认”通知；提交结束后仍未知的操作至少观察 30 秒，让正常快照同步完成。Host 通知监测会只读核对已知准确聊天 ID，已确认的操作不会提醒；此核对不调用可能继续首条输入的 driver 恢复入口，缺少 owner 仍保留未知。Host 重启重新保守等待，不立刻补发旧未知事项。点击旧的新建通知时，手机即使已清理成功操作的本地待处理记录，也按原操作 ID 通过具名只读 `creation-receipts` 接口查询 Host 的持久回执，并打开已确认的准确聊天。该接口不会进入原创建查询的首条输入恢复分支。通知观察和点击均不重发输入。

当前 Codex 桌面首个结束快照没有可核对的结束时间时只建立基线，因此尚不能保证首次发现前已经瞬时结束的任务提醒。桌面 wire 未提供明确停止来源时也不能可靠区分桌面主动停止与异常中断；`stopped` 目前只标记本 App 向对应任务提交的停止意图。上述范围需要上游真实事件或独立桌面证据补齐，不能从历史记录推断。

配置推送的订阅统一由 APNs 呈现通知，前台事件仅刷新和核对状态，避免本地与推送双响。没有 APNs 订阅的连接仅提供 App 前台本地提醒，并显示“仅前台提醒”和具体未配置原因。手机需要先打开 App 完成注册和偏好同步；后台不会靠手机轮询维持推送。

电脑与手机以 X25519、HKDF-SHA256、AES-256-GCM 加密摘要，绑定完整目标、绑定 ID 和事件 ID。Relay 只持有目标绑定、设备令牌、密文和投递账本，不能读取聊天摘要或执行模型。Relay 独立持有 APNs 密钥；每台 Host 使用不同发布凭据，每部手机使用可独立撤销的绑定管理凭据。

稳定事件 ID 包含完整目标、聊天、类型、任务／请求／操作身份。Host 与 Relay 使用私有单写入者状态，提交边界先落盘，再发网络请求。订阅更新与撤销具有持久单调代次，旧令牌注册和晚到注销不能覆盖新订阅；先撤销、后到注册也不能复活订阅。队列过滤明确失效事项；Host 尚未取得当前实时证明时不补发待处理事项。APNs 提交后结果不确定的项目保留为 `uncertain`，不会盲目重投。已接受、未接受、排队和未知状态分开显示。摘要超限时先省略可选标题；完整身份仍超限则明确记录未接受，不无限重试。

关闭通知或移除电脑会撤销对应订阅；手机分别保存云端和 Host 的撤销队列，首次注册尚未返回绑定时也保存 Host 撤销。前台恢复网络后重试，Host 也保留云端撤销重试。移除电脑时仅为完成撤销保留所需私有凭据，核对成功后清理；加密迁移包含尚未完成的撤销。APNs 已接受的通知无法由服务保证远程撤回，系统可能出现晚到通用提醒；通知扩展会按最新分类和电脑开关静音并取消角标。点击先核对完整目标并刷新，再打开原聊天及对应事项；当前草稿保持，已处理事项展示最新结果。通知点击不会批准、恢复任务或重发未知操作。

## 通知内容

开启“显示会话标题与待处理概要”后，系统通知标题采用会话标题，副标题显示电脑和通知类型，正文使用可换行的空间展示具体事项。待回答和待审批通知优先采用准确请求的问题或审批说明，计划待确认采用对应计划摘要，缺失时回退同一任务输入；其他类型采用当前任务摘要。关闭预览时仍只显示电脑和类型。新设置默认开启，已保存的关闭选择保持。多个异步问题按各自请求分别展示；原桌面任务结束后，最新任务中尚未回答或跳过的问题继续保留同一通知身份，回答后移除。摘要由 Host 从对应真实运行任务的文字上下文生成并限制长度，使用原有加密信封传递，Relay 不保存摘要明文。旧事件缺少摘要时显示对应类型的简短状态说明。已有预览偏好保持不变，旧安装仅看到类型和电脑时需在设置中开启该开关。缺少标题的旧事件按对话 ID 短码区分。

具体问题与审批概要的本地真实 DEV 已通过；本轮原生展示复用同一候选已真实投递的审批通知。正式手机与 Host 的升级及 APNs 场景另行核对，见 本次验收范围（本地文件 `docs/acceptance/task-notifications.md`）。

新增通知内容扩展，在长按展开的卡片左侧显示标题和摘要，右侧显示电脑与通知类型；普通横幅继续使用系统的固定位置。展开界面只展示信息，点击仍先同步原电脑和聊天，不添加审批、恢复或重发动作。[Apple 自定义通知界面说明](https://developer.apple.com/documentation/usernotificationsui/customizing-the-appearance-of-notifications)

通知顶部的应用图标由 iOS 使用主 App 的 `AgentHubIcon` 资源，沿用 [当前图标设计](design/app-icon.md)；通知服务与内容扩展不维护另一份品牌图标。更新图标后需要覆盖更新手机上的主 App，保持原 Bundle ID、签名团队及扩展标识。部分系统版本可能继续显示旧的通知图标，Apple 已确认该问题并建议重启设备后复核；图标资源更新、手机安装和通知实际显示须分别记录。[Apple 通知图标更新说明](https://developer.apple.com/forums/thread/775787)

审批、问题和计划摘要优先取对应事项；回退的任务输入须匹配同一运行身份。操作结果待确认独立采用原操作 ID 绑定的不可变提交正文，不借用当前任务输入；新建操作缺少已确认会话标题时以“新对话”加首条内容摘要标识。通知只查询和展示，不重新执行输入。内容限制为 160 个 UTF-16 单元，去除控制字符并合并空白，不把附件路径或音频内容拼进摘要。EasyWork 对话标题读取桌面保存的标题，缺少标题时回退到对话文字。生成摘要不额外调用模型。

## 云端部署

运行 `pnpm package:notifications` 生成不含凭据的 Relay 接入包及 SHA-256。服务器需要 Node 24、HTTPS 反向代理和到 Apple APNs HTTP/2 的出站连接。独立服务可与现有服务器上的应用共存，不能替代或破坏现有 Relay。

包内的 systemd 模板使用独立服务账户、回环监听和私有可写状态目录。将 `push.config.example.json` 的占位内容替换为服务器自己的配置，保存为仅服务账户可读写的文件。APNs `.p8`、服务配置与状态文件要求私有权限；密钥不放进接入包或 Git。可使用 HTTPS 地址的独立路径前缀，反向代理须移除该前缀后转发到服务。

```json
{
  "statePath": "/var/lib/codex-notifications/push.json",
  "topic": "<existing-main-app-bundle-identifier>",
  "teamId": "<formal-apple-team-id>",
  "keys": {
    "development": {
      "keyId": "<sandbox-apns-key-id>",
      "keyPath": "/etc/codex-notifications/private/APNs-sandbox.p8"
    },
    "production": {
      "keyId": "<production-apns-key-id>",
      "keyPath": "/etc/codex-notifications/private/APNs-production.p8"
    }
  },
  "hosts": [{ "hostId": "<host-id>", "token": "<independent-host-publisher-token>" }]
}
```

启动服务时设置 `RELAY_PUSH_CONFIG`、`RELAY_BIND_ADDRESS` 和 `RELAY_PORT`。未设置配置的普通 Relay 仍只提供原有 health；成功组装推送服务才声明 `notifications:push`。

Apple 新建 APNs 密钥按 Sandbox 与 Production 区分，服务通过 `keys.development` 与 `keys.production` 分别选择密钥并隔离签名缓存；未配置环境拒绝注册，不宣称该环境已接入。仍支持两个环境的旧密钥可沿用顶层 `keyId` 与 `keyPath`。[Apple 密钥环境说明](https://developer.apple.com/documentation/usernotifications/establishing-a-token-based-connection-to-apns)

每台 Codex Host 设置 `HOST_NOTIFICATION_RELAY_URL` 和 `HOST_NOTIFICATION_PUBLISHER_TOKEN_FILE`；EasyWork 对应 `EASYWORK_NOTIFICATION_RELAY_URL` 和 `EASYWORK_NOTIFICATION_PUBLISHER_TOKEN_FILE`。发布令牌文件只保存该电脑的凭据。HTTPS 地址不包含用户名、密码或令牌。保留原 Host ID、私钥、授权范围、服务路径和端口。Host 更新通过本仓库 `pnpm package:connect` 生成同一接入包及 SHA-256，按电脑分开部署和复验。

macOS 和 Windows 的 Host 自启安装／重启脚本将上述两个 `HOST_` 配置写入各电脑自己的服务配置，凭据只保存文件路径。省略环境变量时保留原值；显式空字符串清除对应配置。Windows worker 仅接受这两个具名键，不扩大为任意环境变量透传。文件路径按安装命令所在目录解析，凭据文件仍须通过本机私有权限检查。

设备首次取得令牌、令牌变化及 App 返回前台时重新核对注册。同一偏好 generation 允许语义相同的 JSON 字段顺序变化，包括各类型铃声映射的顺序；不能把编码器顺序变化当作修改偏好或拒绝新令牌。相同 generation 的实际值变化及旧 generation 仍拒绝。手机通知页分别展示系统权限、APNs 注册状态及各电脑的推送连接状态，系统取得令牌不等于电脑已接入或通知已投递。

EasyWork 组件分发：先在本仓库 `pnpm build`，再运行 `node scripts/distribute-notification-runtime.mjs <easywork-source-checkout>`。产物清单记录每个编译文件和整体 SHA-256，EasyWork 的工作树保留既有改动，并按其门禁执行检查。

## 正式签名与数据保留

主 App、通知服务扩展与通知内容扩展分别使用正式开发者团队的描述文件。主 App 开启 Push Notifications、Time Sensitive Notifications；三个目标共享 App Group 与 Keychain group。Debug 为 APNs development，Release 为 production；注册发送构建环境，Relay 按设备绑定选择 APNs 端点。[Apple 设备注册说明](https://developer.apple.com/documentation/usernotifications/registering-your-app-with-apns)

产品名称为 `Agent Hub`。正式设备构建使用 `com.easyai.agenthub`，通知服务扩展为 `com.easyai.agenthub.NotificationService`，通知内容扩展为 `com.easyai.agenthub.NotificationContent`，App Group 为 `group.com.easyai.agenthub.notifications`。模拟器仍使用既有隔离的 `com.codexmanage.ios` 验收身份；`NOTIFICATION_APP_BUNDLE_ID` 可显式覆盖两者，并同时生成主 App、扩展、App Group 与 Keychain 标识。正式 Apple 注册、签名和设备迁移须以实际结果为准，源码配置不构成已注册证明。不能用同一 `PRODUCT_BUNDLE_IDENTIFIER` 命令覆盖两个 target。开发现场示例：

```sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer \
xcodebuild -project apps/ios/CodexManage.xcodeproj -scheme CodexManage \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  DEVELOPMENT_TEAM='<formal-team-id>' \
  NOTIFICATION_APP_BUNDLE_ID='com.easyai.agenthub' \
  -allowProvisioningUpdates build
```

切换团队前核对原安装的 Bundle Identifier、Team ID、描述文件和数据保留能力。不能以卸载重装代替验证。设置页“安全迁移”提供密码加密导出：PBKDF2-HMAC-SHA256、随机 salt、AES-GCM，覆盖连接凭据、Agent 身份、缓存密钥、草稿、附件、未确认和脱离等待的回执、通知身份及去重记录。迁移文件只通过用户选择的位置保存，不上传。密码至少 12 字符；文件损坏或密码错误不改写数据。

导入只允许尚未连接真实电脑的新安装，并保留目标和操作 ID。同一 Bundle ID 的备份仍可恢复；跨标识导入仅允许原 `com.easyai.codexmanage` 到正式 `com.easyai.agenthub` 的明确迁移，反向导入、通知扩展及其他 App 标识均拒绝。旧版密码加密格式与认证上下文保持兼容。失败回滚已触及的凭据和文件；成功后连接标记为离线、通知关闭并撤销旧推送订阅，核对身份后再手动开启。正式切换前需要真实验证导出文件可解密、附件与回执齐全，并独立验证回执没有重新执行。当前个人签名安装没有 APNs/App Group 能力时，先用兼容原签名的构建完成导出，不能直接覆盖成未经验证的新团队安装。

## 检查与验收

`pnpm check` 包含严格通知契约和通知边界检查；`pnpm check:notifications:native` 在 macOS 上使用 Swift/AVFoundation/CryptoKit 和 ffmpeg 检查合成音频、使用中铃声删除、迁移完整性与加密互通；`pnpm build:ios` 编译主 App 和通知扩展。模拟器设置检查位于 `testTaskNotificationPreferencesAndPermissionDenied`。

`CODEX_EXECUTABLE=<installed-official-cli> pnpm test:notifications:desktop` 在隔离 Host 目录中准备零 Turn 元数据、确认准备进程退出、打开原桌面并提交实际模型任务，独立核对后台发现、真实审批、失效事项过滤和完成提醒。未指定原生设备时，该脚本不证明手机收到通知。失败后使用报告目录设置 `NOTIFICATION_ACCEPTANCE_RESUME`，复用已有任务／请求身份；未知提交结果没有任务 ID 时禁止重放。每次验收保留失败记录和 Adapter 指纹。

同时指定 `IOS_SIMULATOR_ID=<dedicated-simulator>` 时，该脚本冻结原生源码，在真实审批仍待处理时连接原生 App，并从系统通知中心核对会话标题与具体审批概要。原审批已结束时，先核对真实结束状态，再准备一项独立的展示审批，不重放原请求。核对后拒绝测试审批，再验证完成提醒。原生源码指纹、XCTest 结果和真实桌面请求分别留证。已有同一候选真实投递及原生通知归档证明时，可设置 `NOTIFICATION_NATIVE_REUSE_DELIVERED=1` 复验已投递的同一事件，跳过重复配置连接；不能伪造或复活失效事项。此场景仍不证明正式 iPhone 的 APNs 后台或锁屏投递。

`CODEX_EXECUTABLE=<installed-official-cli> IOS_SIMULATOR_ID=<dedicated-simulator> pnpm exec node scripts/notification-creation-ios-acceptance.mjs` 使用隔离 Host、真实桌面和模型核对正常创建的通知抑制，再注入对应已成功操作的旧本地通知，通过原生系统通知中心点击核对 Host 回执导航、草稿保留和不重复输入。通知注入不等于真实 APNs 投递；指定 `NOTIFICATION_CREATION_OUTPUT` 可以复用该目录中已独立核对完成的任务进行原生复验，未知提交禁止自动重放。

这些检查不能证明后台、锁屏收到 APNs。正式验收见 任务通知验收清单（本地文件 `docs/acceptance/task-notifications.md`），每项分别记录实现／环境指纹、本次通过、复用通过、失败或未验收。APNs 接受与手机实际收到必须是不同证据。
