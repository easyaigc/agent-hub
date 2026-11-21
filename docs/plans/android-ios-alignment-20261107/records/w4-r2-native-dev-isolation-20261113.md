# W4 R2：固定原生 DEV 验收 bundle 的源码候选

对应 F01/F02/F04/F37/F38/F39/F43/F44/F61/F73；关联 Q01–Q04、Q11、UI01/UI07/UI08。本记录只保存本批来源、共同断言及验收入口，当前状态仍由 `../progress.md` 唯一维护。

## 实施边界

基于 `2021e84c53ddf14774dc65c732564775c4b03695`。新增 Debug-only `R2RealDEVApp` / `R2RealDEVNativeTests` target、`R2RealDEV` scheme。固定 App bundle 为 `com.easyai.agenthub.easyworkacceptance.r2task20261112`，测试 bundle 仅加 `.nativetests`；模块分别固定 `R2RealDEVApp` / `R2RealDEVNativeTests`，不扩大旧 native module/bundle guard。

新增 App 复用原 `CodexManageApp`、UI-owned `SessionStore`、真实 `AgentClients` 与原生 `RootView`。仅新 target 编译 `R2_REAL_DEV_ACCEPTANCE`；正式、原 DEV、旧 Acceptance 的 target/config/entitlements、旧 C2 方法与业务断言均不改。Android 不受影响，KMP/Host/Adapter 不改。新 target 没有扩展依赖或 Embed App Extensions phase；权限仅自己的默认 Keychain 组，不配置 App Group、APNS、time-sensitive，亦不注册原 URL/document handlers。上述系统整合、Task Hub、Codex provider、Relay/OIDC、本机其他 target 均不纳入本候选准入或通过。

## 启动与不重放断言

1. `CodexManageApp.init` 在构造任何 Root `StateObject` 前执行 `R2Admission.start`。缺配置、错误 bundle/task/owner/case、目标不完整、pin/key/endpoint 不合格、lease 过期/未生效/超过一小时即本地停止，不构造 Root。App delegate 仍为原无初始化网络工作的 delegate；普通 DEBUG probes 不启动。
2. 输入仅为私有启动环境的 `R2_MANIFEST_BASE64` / `R2_LEASE_BASE64`，内容采用 `R2AdmissionPolicy` 的实际 Codable 结构。manifest 固定本 task、bundle、操作者 Host、case UUID、一个完整 EasyWork target（含账号）、证书 SHA256 pin、公钥及明确本机 HTTPS 直连地址。地址仅接受私有/回环 IPv4 或 `.local`；地址形状不是本机身份的证明，控制方仍须在私有准入记录独立核对其本机归属、完整 target、pin 和公钥，不能仅填写 owner 字段替代核验。lease 绑定 manifest 原字节 SHA256、UUID、生效/到期秒数，模式为 enroll/read/model。
3. 首次安装/启动前还须由控制方证明准确新 bundle 未安装或容器不存在。运行时在自己的容器内核对 Application Support、Documents 和本 bundle 的持久 preferences 为空；仅以 ReturnAttributes 检查自己确切 Keychain 组的 generic-password/key 是否不存在。任何非 NotFound 状态（保留实际 OSStatus）、已有条目或原件均拒绝；不读取旧密钥、不删除/reset、不复制任何原 phone grant/key。系统创建的目录与安装级不存在证明分别记录。
4. 首次成功后以独占写入的 `r2-origin.sha256` 绑定 manifest；以后只核对同一 origin，不清数据、不改 target/case。相同 manifest 可更新有效 lease。重新安装后若 Keychain 仍有条目则拒绝当作“全新”。
5. `SessionStore.init` 在创建产品目录/读取状态前再次准入；恢复后、读取历史页/迁移/`finishLocalRestoration` 前逐一验证**全体** saved clients 的完整 target/candidates/pin，非 EasyWork、demo、legacy 或外部 target 均拒绝。pending creation/fork/reply 的原目标/host 关联也检查。新 target 不启动恢复中的 creation/fork 自动观察；UNKNOWN、原操作 ID、正文、草稿及 journal 保留，只能由后续明确 examiner 查询原 ID。已有 client 时 enroll 启动拒绝，不以重复配对覆盖已保存授权。另在首个身份/网络动作前独占保存并同步 `r2-enrollment-intent.json`（manifest/lease/原 offer ID，不含配对码或凭据）；任何已有意图都拒绝再次配对，包括响应丢失而 Store 仍为空的情形。该本地记录不是新增 Host operation ID，后续须按原 offer/新设备的原绑定元数据核对，不自动重发授权。
6. 新 target 始终由 examiner 持有 Root 自动刷新控制，lease 失效不会回落为普通 discovery/notification/voice/Task Hub 自动启动。`R2_PRODUCT_UI=1` 仅呈现原 `normalBody`，仍用同一 UI-owned Store；默认显示静默载体。没有用替代 AppModel/provider、内存 vault 或 fixture 承接业务。
7. LAN offer 在加载身份/发出探测或 claim/connect 前验证，`saveEasyWork` 在首个 peer read 前验证且仅接受新空 Store 的 enrollment；不接收外部 token/grantRef。连接、provider resolve、host probe、实际请求入口及 publish 前再次校验有效 lease/目标/端点/pin。只读协议方法有明确集合；其他请求必须在 model lease 内逐字节匹配 method、原 operation ID、canonical JSON params SHA256。生产原 journal 的未知结果核对/不重放规则保持，不凭新 lease 重放既有业务。这里的本机 LAN 新 binding 是新授权，不能冒称已执行 EWDAP 新 grant 场景。

## 专用 native 入口与后续准入

`R2RealDEVNativeTests/R2RealDEVAdmissionTests` 不修改或替代原 C2/Usage Store suite。后续每轮只允许明确的 `-only-testing:` 方法，不默认执行整个 scheme：

- `testR2FreshLocalEnrollment`：enroll lease、空 Store、新容器和新 identity；私有 `R2_FRESH_LAN_DESCRIPTOR` 必须来自已核本机 target。本方法使用原 LAN 配对/保存链；不从旧 phone 数据取授权。需要控制方另行批准真实授权并记录新 binding。
- `testR2CurrentTargetUsageRead`：沿用同 origin、新授权与 read lease；先要求私有 `R2_USAGE_SOURCE_PROOF_BASE64`（测试文件 `R2UsageVerification.Proof` 的实际 Codable 结构）。version=1 记录绑定 manifest SHA256、caseId、完整 target（含 EasyWork 账号）、独立桌面已核 adapterId/scopeRef 与原桌面观察文件 observationSHA256，不从本次手机响应自举预期来源；缺证明在 provider/read 前失败。实际 provider 保留 requestId/目标/版本校验，再调用真实 `UsageRules.shared.project`；仅当前非 stale、至少一个 amount/measured/unlimited 额度且 adapter/scope 与独立证明匹配才接受。合法 `unavailable` 明确失败、sourceVerified=false，不 skip；空额度、全部 unavailable、错误目标/版本/来源或陈旧快照也不通过。partial 原样保留，只记有界来源可用，不单独证明 F73 Store/UI、Q01–Q04 或模型通过。

  每次将精确原始返回的 SHA256/字节、脱敏状态/原因、target/source/请求摘要、KMP projection 摘要和验证结果保存到自己 Documents/r2-usage-evidence 新 UUID 文件与 keepAlways XCTest attachment。没有原始金额、账号、标签、token/key 或任意远端错误正文；未取得 provider 返回时只记录阶段，不伪造 raw 响应。

- `testR2ForeignClientRejectedBeforeProvider`：只构造临时外部 runtime client，验证 resolve 本地拒绝，原 Store 不改。

审核后才分配大型 slot 构建：`pnpm --filter @codex-manage/ios build:r2:native:simulator`。物理构建使用 `pnpm --dir apps/ios exec xcodebuild` 的同一 `R2RealDEV` / Debug / `generic/platform=iOS` / `build-for-testing` 入口，复用同一 DerivedData；签名 profile/team 通过私有参数提供，不写入仓库。实际签名后须核读 bundle/module、默认组、entitlements、nested code、UUID、包/源码 SHA 及实际 SDK/runtime，不能把 wildcard profile 可匹配当作签名或安装成功。使用明确物理设备、官方 CoreDevice/XCTest，安装、授权、启动、模型另行按控制方分配执行；A6/正式/原 DEV 均不操作。

后续 Q01 核新身份/新授权/准确目标与路由；Q02 核同目标只读与离线；Q03 核原 owner 的 queue/steer/stop/cancel；Q04 用准确新输入/新原操作 ID 做真实模型与桌面独立核对。任何 UNKNOWN 只查原 ID。F73 还须在控制方合成 W5 后，对同一候选执行原 C2 与 W5 新 Store 方法，再分列真实 provider、UI、离线与模型证据；本批不得替它们记通过。

## 本批辅助与未验

实际策略源码和从 `NotificationModels.swift` 原样抽取的 `AgentRuntimeTarget` / `AgentAccount` 经 Apple Swift 编译执行：45 场景，包含错误 task/bundle/owner、外部完整 target/账号、pin/transport/endpoint、过期/未来/超长 lease、method/ID/params 替换，以及实际文件系统上首次 enrollment 意图持久化、重复尝试拒绝且原字节不变。Node 还检查 target graph、最小权限、旧 module guard 与原 C2 方法指纹。另 19 个实际 Swift 来源验收 helper 场景覆盖合法 unavailable 必须失败、完整目标/账号/manifest/source 替换、过期/空/不可用额度及脱敏原字节摘要。19 场景使用 projection fixtures，只验证新增验收 helper；真实 KMP 调用位于 native 方法，本批未执行，不称共享策略或原生通过。该检查是宿主 macOS 辅助，未执行 Keychain 存储/手机路径。

本批未启动 Xcode/Gradle 大构建、未新建 Simulator、未安装/启动 iPhone App、未读取旧 phone grant、未配对/刷新授权、未发模型。语法解析单独记为语法；新 target 的完整类型编译/链接、签名/安装、原生执行和真实 DEV 仍未验。先交精确源码差异，待控制方 review；旧 Q11 的原 xcresult/失败/签名反例保持原环境和原结论，不迁移到新 bundle。
