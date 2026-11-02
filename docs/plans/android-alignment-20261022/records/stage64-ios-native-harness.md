# 阶段64：iOS Native hosted 环境与容器准入

2026-11-02。本档案只保存独立Native验收环境、原失败和证据边界；产品实现及逐场景结果见[SessionStore阶段档案](stage64-p7-ios-sessionstore.md)。环境失败不改记为Store产品失败，环境通过也不代表恢复、补偿或完整物理DEV通过；本阶段不需要用户操作手机，A6保持暂停。

## 准备与原失败

1. unhosted runner在准入阶段读取真实Keychain返回OSStatus -34018；尚未创建fixture或SessionStore，0业务恢复执行，不能把单纯runner启动当成功恢复。
2. 增加独立签名blank UIKit host后，iOS27的Scene生命周期要求触发 `UIApplicationEvaluateRuntimeIssueForNoSceneLifecycleAdoption` trap，Native parent65、0 tests；crash和raw保留。只将空host改为SwiftUI Scene入口，并仅为新host生成Scene manifest；不修改业务断言、生产App设置或旧Stage61 target/scheme。
3. empty SwiftUI Scene host的官方构建、签名核对及1项ENV probe通过。ENV读取实际home/bundle/no-group身份并由Root与simctl容器独核，不运行Keychain或SessionStore；adHoc、strict验签及精确bundle ID不是Keychain写权限证明。
4. 旧exact home guard在Keychain/业务fixture之前失败；运行前后观测到同一App的数据容器变化，与guard失败一致，但内部重装机制未验。保留原前置失败，不弱化实际Store断言。
5. official `UseDestinationArtifacts`完整v2请求在Simulator返回70，能力仅支持physical destination；unsupported候选已删，私有intent及失败回执保留。未转用物理设备，也不要求用户配对、解锁或切App。
6. 准入v2固定外部创建的准确fresh Simulator DeviceDataRoot、签名host/test精确ID；运行内读取实际Home与Bundle，并分别核对其UUID路径属于准确设备下的Data/Application和Bundle/Application父链。运行后simctl的App/Data两个路径必须与runtime binding分别完全一致，Root才接受本场景。没有设置HOME或CFFIXED_USER_HOME，不把default目录重定向到fake namespace，不弱化业务断言。

## 已核对的限定证据

- `stage64-root-sdk-environment-review.json`（1164B），SHA-256 `fe385d27d71803e09b66995f5b780cd829b4ff493273cbf58c78a566de19e8ca`：正式production-writer App SDK与只读unhosted环境probe；1180-input三快照稳定，12份已知退出、18份raw stream完整读取。该probe不代表hosted业务通过。
- `stage64-root-hosted-scene-r2-environment-review.json`（1388B），SHA-256 `01d5a706456a11761622ed2d05d31a5e4840c6f2afbecdafff16c88a3ae791e6`：签名Scene host、1项ENV probe；当时完整2902-input四map稳定并与当时实际文件匹配，25份退出/50份raw读取。ENV不访问Keychain或Store。最终容器guard只改变一份测试源，生产App 61份输入与此官方SDK完全匹配复用；最终guard更新后没有重跑App SDK，不声称当前Native与旧SDK全量2902闭包相同。三项actual Store Native实际编译并运行当前0582测试源，compile/Test退出0。
- `stage64-root-runtime-container-admission/source.json`（1088B），SHA-256 `c2b37da71fc5333a5a07ce9e69245911a4d4625f3a0cf73ae633c30d4d1a6d5f`：仅更新测试准入，不改变三项业务断言或production代码。
- 三项actual case运行后App/Data双路径与runtime binding分别完全匹配，已安装host/test与build二进制bytes/SHA一致；Root证据及真实默认Keychain/通知I/O断言见[阶段档案](stage64-p7-ios-sessionstore.md)。

独立pre-get在Shutdown状态返回149，不能证明pre-marker；只保留实际测试空namespace/keys guard与运行后双绑定证据。runtime记录PID，未采UID；不以同一测试内guard替代独立外部pre证据。不宣称该准入能对抗恶意executor；仅适用于任务自有fresh Simulator，不代表正式App、物理设备或用户历史资料安全验收。

## 原件与资源收尾

原unhosted、UIKit Scene trap、旧容器guard和Simulator unsupported错误各自保留原fingerprint/intent/receipt，不改写旧失败、不重放业务输入。两份已关闭失败xcresult先无损归档并核对索引 `stage64-closed-failure-xcresult-lossless-recovery.json`（1082B），SHA-256 `35ab9bfb9af39f3363855dd6e0d88c8832a09cfa134037f5cb681186d31399f2`；失败raw/crash保留最小恢复材料，unsupported候选Products已删除。

成功及两个故障Simulator已实际正常删除，Root逐项核registry与实际device root不存在；更早unhosted Simulator及临时runner均已清。两故障Simulator的Root收尾复核为 `stage64-root-passed-pair-sim-cleanup-review.json`（453B），SHA-256 `41c7ef1c68c18e2fdbbd26c5dc51f73d73432763e73baf1f82f410ac30b43495`。current App/Native Products及framework因PR和当前包匹配复验继续保留；Xcode可重建intermediates/caches在代码commit后由Root核无active FD再精确清理，尚未写成已清，按私有资源台账复核。受保护App、独立R/W现场、共享服务和配置未作为清理对象；无新用户独立聊天，不宣称所有资源已回收。

本档案是过程追溯，当前范围/完成状态/优先级只维护在[总表](../progress.md)，API37横屏、蜂窝/VPN和物理DEV另见[待验清单](../pending-acceptance.md)。
