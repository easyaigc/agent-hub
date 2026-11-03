# 阶段73：Main 菜单的连接偏好与保存恢复

功能 ID：F02、F03、F04。状态：API33 一个 Native 方法、两个呈现 case 有限通过；完整检查通过，提交交付中；P8 七组未封存。

## 功能、交互与界面对照

实际继承 MainActivity 的独立 debug Activity，运行真实 AgentHubApp、MobileSession 与加密保存/恢复。中文浅色、英文深色各从客户端编辑页打开偏好菜单，核对自动、仅局域网、仅远程三个标签与选中状态；依次选择 lan-only、relay-only、automatic、relay-only，每次验证菜单关闭、取消编辑后重新打开及保存值，最后新 Activity/新 Session 恢复 relay-only、语言和主题。

与 iOS AgentClient 的三种偏好/标题、EasyWorkPairing 的已保存连接 Picker、EasyWorkSession 的路径选择源码逐项关联。此次不执行 iOS，也不将源码对应算作双端运行态通过。现有产品布局和文案未改；断言覆盖语义节点与交互，不包括截图、短视口、busy禁用、旋转/字号/TalkBack或实际像素。

fixture 通过产品 setDemoMode(true) 建立有效的 Demo 当前选择，再编辑另一条合成真实连接。凭据、原草稿/选择/目录缓存和合成 unknown 原文保持；typed端口只允许捕获该合成目标的目录/usage尝试并本地拒绝，没有真实网络、Host、模型输入或原 unknown 核对/重放。选中真实客户端的联机偏好路径仍待验。

Main 的 nativeRuntime 默认 true，retained 状态保留该值；false 仅允许独立 debuggable 验收子类关闭平台服务装配。MobileSession 的测试端口默认/null路径保持原注册。Main 原本就参与编译，未增加 sourceSet 排除、Gradle flag/变体或任意运行命令。此次 Main 子类 UI 通过不代表 shipping Main/完整语音、通知、发现、迁移或系统入口装配通过。

## SDK 与原始 Native 证据

- 最终 SDK profile 为 p8-main-preference，881项输入、16项精确overlay、14项固定after pins；共享 capture/Popen/严格APK解析与全源前后检查复用。82/82 tasks执行，17个原进程 wait=0、rawComplete；外层实际wait=0、双EOF。seal：`0e4c4ac69706646400e9c1e2a76225c7d04f01093b8c9b69ff04a722de0c23e8`。
- App SHA：`b2a8a55314f9ecda662fb5f1ee1acdcc54e8774fbd78ec5852a4da3e3ca97caa`；Test SHA：`166a802ae922d8657dbb15e74d000bba0ae359d925d5507f819ee806403e3c8c`。实际安装包、单证书、UID、同boot、原PID/start/argv均有独立Host和Native前后核对。
- 新 run：`54ab9148-cfd0-430f-b1b1-d7dc6359960a`；selector 为 `NativeP8MainPreferenceTest#savedPreferenceThroughMainSubclassMenuPersistsWithoutNativeRuntime`，原始 named JUnit 为 `OK (1 test)`、两个case完成。原adb子进程实际wait=0且双流完整；原App PID/start为5054/111541，独立观察其后不存在，没有把Host wait当作OS对App的wait。
- Host review：`a072e375ff00cf4fcff40a3ef909fb9030398634dbc250774400fbe99a89fb87`；原始stdout：`2d420c93ba3ea7c7e76f2dddfd9e8aba7c6874e6cf7c5c1fbf3c0d6b20eb6beb`；Native evidence：`8e88ca2a1dc31d9ae06e425109627bc8f9a089d20111cbaa78ec4b7914aeb55d`。两case的已登记owner关闭、精确scope/alias移除、默认族为空及原件保护断言完成；不推广成全部系统资源已证明关闭。

## 原失败与修复

审查文件最初误带说明字段，另一次调用误拼SHA，均在SDK前置校验失败；原wait/raw保留，没有把这些入口前错误记成实际构建。首次实际编译发现公开Android SDK不提供O_DIRECTORY，修复两处debug/test辅助源的四个使用点：保持只读/非阻塞/不跟随最终symlink和精确fstat目录身份校验。现在为打开后核对，不能宣称kernel在获取前拒绝错误对象，亦不证明恶意同UID替换或设备驱动open无副作用。

首次设备入口因pm按子串返回App和Test两行，而Host对整段fullmatch，尚未创建admission或instrument即拒绝。修复为有界完整行解析、精确目标唯一匹配，实际双行与13项拒绝边界通过；之后全新SDK封存，已装相同字节没有重装。不存在的verify-bundle CLI调用只记为调用方错误，实际验证使用既有verify_build_bundle API。

首次真正Native run `eb2d8b6b-9e6a-4dbd-8e6d-708ff39be245` 为1项失败：原夹具关闭Demo却保留Demo当前选择，产品恢复按已有规则回退到真实客户端，isDemo断言失败。以真实setDemoMode(true)修正有效前提，原isDemo保留并增加demo布尔和精确选择ID断言，未改生产fallback。新Test包与新scope通过；旧失败、原scope/alias和UNKNOWN诊断原件保留，不升级、不重放。

## 收尾及剩余

本次准确QEMU正常emu kill；原supervisor执行会话返回0，QEMU原wait=0、双流完成，5558/5559无监听。R66数据仅为后续语音原件迁移与旧失败审计保留，24小时内复核；其它设备和常驻服务未处理。两个无占用且字节与完整保留版本相同的旧SDK APK已精确回收约74.57MB；原seal/raw/source及退役回执保留，旧bundle明确为历史部分，不能再冒充当前完整verify通过。当前及必要回退包保留，累计预算不因新目录重置，卷空间变化不归因本任务。

PR120主线CI11209的Linux27029因threads/read预期200实际409失败，iOS27030成功；公开同步11210/27031成功。已按既有授权交MacBook128在专用会话排查，PR121及Linux复验尚未完成；不以重跑或同步成功替代CI修复。

真实模型账本221+0、P4 205+1 sealed unknown保持。完整平台装配、选中真实连接的联机路径、Main真实冷PID/系统入口、截图/短视口/AX/API26/physical、实际issuer/LAN/Relay与七组A5仍待验。任务/项目CRUD、F75、iOS A6继续暂停。

本轮完整 `pnpm check` 原进程实际wait=0、双EOF及正式工具最终exit=0已保存，耗时696.76秒；42组共1518项，1456通过、62跳过、0失败。生成/格式/一致性/差异检查通过，881项源/16项overlay保持最终SDK指纹；辅助检查不升级为真实DEV。初轮仅文档别名含数字违反字母Schema，Root修正名称及三处引用后再生成，旧exit1保留。

主线CI同步：PR121已正常合入 `133a677b`，修复HEAD `61208edc` 的Linux/macOS CI11212均成功。Root按稳定change ID合并manifest，保留阶段73与CI两个独立记录，新增测试脚本对真实投影前/后位置分别断言409与200；生产拒绝规则及Android源码未改。881项Android/SDK输入仍逐文件匹配本阶段原包，原生结果按相同实现指纹复用；组合后的完整检查另记，不将旧失败改成通过。

组合检查完成：导入PR121并解决manifest冲突后，重新生成/格式/工作区与暂存差异/双端一致性和完整 `pnpm check` 均通过。原进程26589实际wait=0、双EOF/fsync与正式finalexit=0，耗时689.58秒，42组1518项中1456通过、62跳过、0失败；881项Android输入仍与原SDK精确匹配，未重跑Native或SDK。
