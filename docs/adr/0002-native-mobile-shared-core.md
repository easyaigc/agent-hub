# ADR-002：原生双端与 Kotlin Multiplatform 公共业务核心

- 日期：2026-10-11。
- 状态：采用，A1 双端同步规则、原生全量回归与性能门槛已通过；工具链固定，后续异步边界由 A2 实施。
- 范围：Android 完整版本，以及 iOS 公共业务规则的渐进提取。

## 背景

iOS 已使用 SwiftUI、URLSession、Keychain、Speech、PhotosPicker、QuickLook 与通知扩展。用户要求 Android 的功能和 UI 遵照当前 iOS，并降低后续双端维护成本。现有 Node Host／Adapter／Relay 已可共用，但移动端能力判断、草稿、回执恢复、事件与阅读逻辑仍在 Swift 中；仅共用服务端不能防止手机行为分叉。

## 决策

Android 使用 Kotlin 与 Jetpack Compose，iOS 保留 SwiftUI。建立 `packages/mobile-core` KMP 模块，共享领域值对象、业务规则及状态机，以窄平台接口承接网络、密码、存储和生命周期。Android 直接依赖 Kotlin 模块，iOS 通过 Kotlin/Native framework 和薄 Swift façade 调用。[KMP 官方既有应用接入方式](https://kotlinlang.org/docs/multiplatform/multiplatform-integrate-in-existing-app.html)、[Android 原生 Compose](https://developer.android.com/compose)

共享核心保持无 UI、无任意远程命令、无电脑本地 Codex wire。既有服务端 `packages/protocol` 继续维护版本化跨语言契约；Schema 生成或一致性验证管理 Kotlin／Swift DTO，领域规则只能有一份生产实现。

## 共享范围与接口

| 核心模块                    | 规则与输出                                                                     | 原始 iOS 来源                                                                |
| --------------------------- | ------------------------------------------------------------------------------ | ---------------------------------------------------------------------------- |
| Identity / CapabilityPolicy | 完整 Agent／account／Host／workspace／runtime／thread 身份，能力与实时可控判断 | `AgentClient.swift`、`Models.swift`、`SessionStore.swift`                    |
| Composer / GenerationPolicy | 目标草稿、模型／推理／权限／速度继承、发送／停止／恢复按钮状态                 | `SessionStore.swift`、`Models.swift`                                         |
| Operation / CreationReducer | 稳定 ID、不可变输入、先持久后提交、outbox、未知查询、结束等待、晚到回执        | `SessionStore.swift`、`SessionPersistence.swift`                             |
| Runtime / EventReducer      | epoch／sequence、去重／缺口、切换代次、snapshot 归并、过期前置条件             | `EasyWorkSession.swift`、`SessionStore.swift`                                |
| MessageProjection           | 工具组、计划／压缩独立行、run 最终回复操作、显示窗口与分组边界                 | `Models.swift`、`RootView.swift`                                             |
| ReadLedger / NoticePolicy   | UTF-8 内容版本、手机独立已读、完整目标去重、分类偏好与通知路由                 | `ThreadReading.swift`、`MobileUnreadBadge.swift`、`NotificationModels.swift` |

核心调用具名 `AgentGateway` 操作，不携带任意方法名。Provider 分别将领域操作映射到 Codex HTTP v1 和 EasyWork v2；Transport 只收发受限帧。UI 不按“Codex／EasyWork”的显示名称硬编码业务分支。

平台接口为 `AgentGateway`、`CredentialStore`、`SessionRepository`、`CryptoProvider`、`Clock/IdSource`、`AttachmentAccess`、`NotificationPresenter`。接口名称为拟定，A2 接入真实异步编排时冻结签名；A1 仅固定同步值对象 façade。密码接口使用成熟平台实现或经验证的库；共享输入编码、AAD、摘要及测试向量，不编写自有密码算法。

初期网络实现保留 Swift URLSession，Android 使用原生网络客户端；安全存储分别使用 Keychain 和 Keystore 保护的密文。公共核心管理持久化语义，平台实现原子文件／事务与失败传播。A1 先验证纯规则，A2 才接入异步编排，不同时替换所有 iOS 平台代码。

## iOS 互通与迁移

开发采用 Monorepo 本地 framework 直接集成；交付缓存产物可以使用同一源码构建的 XCFramework，记录摘要并覆盖设备与模拟器架构。具体集成脚本在 A1 编写，不引入无必要的 CocoaPods 链路。[官方 iOS 集成选项](https://kotlinlang.org/docs/multiplatform/multiplatform-ios-integration-overview.html)

先使用 Kotlin/Native framework 的既有 Objective-C／Swift 互通路径；当前官方 Swift export 标为 Alpha，不将它作为首版发布依赖。[既有互通](https://kotlinlang.org/docs/native-objc-interop.html)、[Swift export 状态](https://kotlinlang.org/docs/native-swift-export.html)

跨语言边界使用明确值对象与非泛型 façade。A1 接入同步纯规则，验证 Kotlin／Swift 值对象、原生调用与持久键字节兼容；该阶段没有异步订阅、回调或跨边界异常。A2 接入异步编排时再引入可取消订阅句柄及可识别错误，同时验收取消、回调线程、引用释放和错误传递。Swift 侧仅负责 `MainActor` 观察、生命周期和 DTO 映射；不将 Kotlin Flow／内部异常裸露为 UI API。此划分与“先纯规则、再异步编排”的迁移顺序一致，不以无实际生产调用的演示异步 API 作为证明。

迁移顺序：目标身份与按钮纯规则 → 能力／设置 → 草稿／不可变操作 → 回执／创建恢复 → 事件／消息投影 → 阅读／通知策略。先以固定向量比较旧 Swift 与新核心结果，随后切换该模块的生产调用并删除重复规则。写操作只允许一个执行编排器；对照阶段只能比较纯结果，不能让两条链路各发送一次。

iOS 原有缓存、密钥、Bundle ID、通知扩展和用户配置保持可升级。新格式带版本和迁移路径；失败回滚，不能靠清除 App 数据完成验收。通知扩展初期共用契约、向量与纯规则结果验证，是否引入完整 framework 按包体／时限实测决定；若保留薄实现，须通过同一向量验证且列明来源，不能出现独立业务定义。

## 协议、设计与一致性治理

已有 `agent-management-v1`、`task-notifications-v1` Schema 与 manifest，以及 Node／Swift 一致性检查继续使用。A2 加 Kotlin 编解码，补齐实际 HTTP 契约；draft DTO 仍不能当作已实现接口。严格输入校验、目标归属和权限判断继续由 Host 执行，手机侧复用不代替服务端校验。

本次[功能清单](../product/mobile-parity.md)是功能范围与断言的唯一维护入口，UI 由[双端规格](../design/mobile-parity.md)定义，真实结果由[验收规范](../acceptance/mobile-parity.md)记录。A2 已将功能 ID、实现／验证状态、源码、UI 和场景定义迁入一份机器 manifest，由其生成展示表；实际阶段证据保持独立，避免把定义检查当作产品通过。

UI token、状态文案和合成测试数据共用，SwiftUI／Compose 渲染分别验证。保留 iOS 的布局、配色与控件位置；原生系统弹窗等差异逐项记录，不承诺跨系统截图逐像素一致。

## A2 接入方式与当前边界

A2 已实现并通过阶段模拟器门槛的同步规则包含能力与原 owner、输入前置条件、生成切换、失败／未知回执、创建身份与恢复，以及消息投影。iOS 通过 DTO façade 实际调用 KMP；Android 的输入判断使用同一核心，其余页面随 A3–A5 接入。源码存在和边界测试通过均不等于完整产品已验收。

历史读取采用 `MobileHistoryGateway` 单一读操作，原生层仍管理 URLSession／凭据／生命周期。公共桥接使用 kotlinx.coroutines 1.11.0 的稳定取消接口及单次回调闩锁，Swift façade 明确回到 MainActor；库不把 Swift Task 取消假定为 Kotlin Job 自动取消。Swift 对象的释放可能等待 Kotlin GC，专项检查实际等待引用释放。[coroutines 官方发行记录](https://github.com/Kotlin/kotlinx.coroutines/releases/tag/1.11.0)、[Kotlin／Swift 异步与异常映射](https://kotlinlang.org/docs/native-objc-interop.html)、[ARC 与线程说明](https://kotlinlang.org/docs/native-arc-integration.html)

通知扩展中的阅读判断从 `reading-policy.json` 受限表达式树生成 Swift／Kotlin，并检查生成文件未陈旧；扩展无需另链接 Kotlin runtime。CryptoKit／MessageDigest 与 JSON／Base64 编解码属于平台边界，使用共同 UTF-8／身份向量核对。生成代码不手工修改，纯判断只维护该定义。Kotlin/Native 另有 macOS framework，供本机命令行契约检查使用，不代替 iOS Simulator 或 Android 验收。

功能定义迁入 `mobile-parity.manifest.json`，展示表由其生成；清单保留全部 F01–F58，代码变更关联功能与实际文件摘要。当前机器门槛检验定义、来源、UI／场景关联和变更覆盖，产品结果仍分别保存在验收证据中，不能将 `in-progress` 公共模块升级为两端全部功能通过。

## 替代方案与代价

| 方案                           | 评估                                                                                    | 结论                                                                                                   |
| ------------------------------ | --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------ |
| 两端完全独立原生 + 共用契约    | iOS 改造少，但回执恢复、能力和阅读规则会长期重复实现                                    | 只用于迁移期或平台边界                                                                                 |
| KMP 公共逻辑 + 原生 UI         | 可逐模块接入，规则复用与现有 iOS UI 保留兼容；增加 Gradle／Kotlin-Native 和桥接维护成本 | 本计划采用                                                                                             |
| Compose Multiplatform 共用 UI  | 可以与 SwiftUI 互嵌，但现有 iOS 复杂系统交互需要重验，扩大 UI 重写范围                  | 本轮不采用；[官方互嵌方式](https://kotlinlang.org/docs/multiplatform/compose-swiftui-integration.html) |
| Flutter／React Native 整体迁移 | 改写现有客户端、平台服务与测试，超出安卓补齐和原生双端目标                              | 本轮不采用                                                                                             |
| Rust／C++ 公共核心             | 可跨语言，但增加一门系统语言、FFI 与原生构建组合，当前主要是业务状态规则                | 本轮不采用                                                                                             |

共享 Swift 源码不能自动获得当前 UIKit、Speech、Keychain 等 Android 实现。本轮不依赖 SwiftUI 自动转译或跨平台 UI 仿真，不设未经实测的“复用百分比”目标。

A1 验证失败时，保留现有 iOS 生产实现和已验证功能，记录阻断及工具链指纹，修复互通后再迁移。若需要改换核心方案，先更新本 ADR 和各阶段验收门槛；不能把停止迁移后两套独立逻辑算作共享完成。

## 成功标准

两个原生客户端实际调用同一核心；修改一条核心规则即可同步改变两端结果。协议三语言一致性、平台取消／生命周期、存储升级与双端真实 DEV 分别通过；有明确功能 ID、UI 状态及证据关联。开发进度和具体阶段见[Android 计划](../plans/android-native.md)。
