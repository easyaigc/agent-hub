# 阶段82：framework文件通道与原窗口AX观察

阶段81源d99bd131完整辅助回归通过，PR132已合入主线472bde36；源/main CI及公开同步均通过，已清理Stage81分支。当前安卓分支从该源继续，任务/项目CRUD、F75、iOS A6保持暂停。原P7准备handoff与Host操作UNKNOWN封存，不查询、不重放、不清除原作用域。

## 范围与实现门槛

P7独立framework TestUID provider替代本轮失败的app_process准备路径；使用全新版本化namespace/authority/operation，原v1验收与历史数据路径不改。R2源码提案的有界可靠管道、接收端EOF/长度/SHA/fsync/FD关闭/独占发布ACK及有限期限已独审；ACK只确认RX，不证明原写入方完成。实际导出还需原Native完成/原输出指纹、DocsUID与URI grant、原provider进程和稳定审计分别核对。

新的准备操作只按原不可变PREPARED回执及独立PID/UID/startTicks/argv/boot核对远端效果；nonce为源码生成、跨原owner文件与回执一致，不冒充独立进程观测。SDK adb原Popen实际wait/双流EOF/原stderr另核，不由CLI退出码推断远端自然退出；持久provider不假装成一次性helper。当前具体EXPORT运行器和闭合SDK仍在源码准备，IMPORT运行器暂不开放，旧UNKNOWN不借此重放。

P8失败观察R2已独审：保留原绑定窗口、Activity/decor/token/focus/AXID的读前后核对；nullable AX window对象仅作可选元数据，非空异窗/不活动仍拒绝。固定phase与原root.refresh返回值帮助区分下一次UNKNOWN，但旧诊断缺失原因尚不确认。1500ms/2048节点/40深度/4候选限制、原5秒验收、四输入与14字段不改；诊断不能满足产品通过。

共性SDK拟一次准确59 overlay/904输入，包含P7新5路径和P8观察，旧54/899目的与配方冻结；尚未构建或实际验收。P9几何诊断源码已独审，但后续须重锁到最终共享源码，再单独三APK/API36实际验收；两APK不能替代。

## 初始源码结果（后续执行见下文）

仅上述源码提案/静态审查完成；工作树采用、编译、SDK、原生操作、回执及UI仍待本阶段逐项执行。原窗口局部通过、AX失败和完整双端/物理DEV待验沿用阶段81，不关闭完整P7/P8/P9。

## 源码采用与辅助边界

R4十个源码路径已采用：五个既有文件及五个新增文件。具体EXPORT运行器与59/904共享目的已完成双路静态独审；Root修复了Native tick未定义deadline、精确硬链接对重复计byte、迟到实际wait/EOF仍可能升级LOCAL_CLOSED三处问题，旧R3资料保留。精确link pair仍逐路径计数/校验，仅字节共计一次；迟到关闭保留实际wait/EOF事实但保持UNKNOWN。

P8原窗口失败观察采用已审R2，成功验收断言/期限不变。仅新增Android验收工装和闭合SDK/CLI，iOS产品行为、主Main四方法及两SAF方法、NativeBack、迁移密码与旧工装路径不变。此前独立javac缺少BuildConfig；完整工程构建虽通过，但独立Test provider进程仍不能加载目标App类。该编译失败是实际边界信号，后续以四类仅依赖android.jar的独立编译为新增门槛。

当前尚未执行新framework准备/导出、P8新观察或P9；等待完整构建和准确环境准入。所有既有失败及UNKNOWN继续封存。

## 本轮构建与运行门槛

共包SDK0460c51a完整Gradle构建通过（120.24秒，17工具实际wait，904源/92材料独立复核）；这仅证明完整工程编译，未证明独立Test进程能加载目标App类。App1abfab33不变，Test a40972f0已单次install-r并核对完整包/证书，保留数据。

P7运行准备在任何framework操作前停于平台目录门槛：真实Test files为771，规范dataDir为同UID/GID的700私有目录，未chmod。prepare/Native/原生导出均未提交，旧操作不受影响；下一修订只允许规范700私有父目录内的700/771平台files，私有namespace及叶目录仍700，旧已封存SDK保持独立。

P8独立新case在原focused window binding期限内保持UNKNOWN，JUnit1项失败，尚未进入AX断言或新观察，故无节点/tag候选数据；不重放原case。原App进程退出、Test缓存进程与原VM正常退出分别登记；两个本轮VM均实际wait0/双流EOF，端口自然释放。失败scope/alias及全部原数据/密钥保留。完整P7/P8/真实DEV仍待验。

阶段81 PR132的源11325、main11343双job与公开同步11344全部成功；main472bde36/public5fae7a16 tree c7738677一致，Stage81本地/远端分支已普通删除，当前Stage82未提交工作保留。

平台父目录修订R2已双路独审并采用四文件：仅在同Test UID/GID、规范700 dataDir内接受700/771 files，持有原两个目录FD核对身份与父目录fsync；私有namespace/run仍700，通用helpers未放宽。新p7-main-saf-framework-parent-v1保留已封存0460/v4目的，Native协议仍v4。新SDK及实际导出待验，未chmod、未提交原未知操作。

## 独立进程边界修复与原未知回执

平台父目录新SDK0d7f410b已通过124.30秒完整构建，904源/92材料/17实际wait独立核对；Test0d74b5a9单次安装并核对完整SHA、证书及UID。仅规范700父目录内700/771 files的例外生效，私有目录仍700，未chmod。

全新prepare操作c6090df6-a664-4f57-af56-ebfbc16549aa仅提交一次；子进程rc0伴随DeadObjectException，没有远端自然退出帧，外层实际rc50，效果保持UNKNOWN。Native操作1419f7b0-5eea-4d13-ac85-970b92e3679a从未派发。原VM消失的原因及原supervisor实际wait/EOF未确认，不推断其已正常关闭。

Root另启本任务VM只读核对原新run；v3目录不存在不足以证明无效果。持久崩溃记录显示准确Test控制进程在prepare同秒因NoClassDefFoundError com.agenthub.android.BuildConfig崩溃，调用点为Lease.validate。只读恢复VM随后正常wait0/双EOF，端口已释放；未重放或启动原provider。该新未知仅可只读核原回执，禁止重放；旧两项封存未知仍禁止查询/接管/清除。

最小修复仅删除目标App BuildConfig.DEBUG依赖，保留PackageManager取得的实际Test/App FLAG_DEBUGGABLE、包名、UID及完整指纹校验。四Java类仅依赖android-37.0/android.jar的独立javac已实际rc0、双EOF，7个class已收尾；这不是设备通过。新独立SDK目的与原生准备/导出仍待执行，已封存旧SDK及失败数据不覆盖。

独立进程四路径修订已双路独审并采用：Lease957228f8、Host58c2242a、P8Hostc71bd68f、Capture793f359c。新p7-main-saf-framework-test-process-v1准确59 overlay/58固定+self/904输入，两APK，旧17目的及109顶层节点保序冻结；Host准入/准备后/最终三处均使用新typed guard。Native v4、v3私有namespace、原窗口binding-v2及成功断言不改。新SDK/真实prepare与导出仍待验。

## 独立进程新包与原始拒绝

新test-process SDKbefd0cb1完整构建112.738秒通过；Root独立复核904源码、92材料及17实际wait/双EOF。App1abfab33保持，Test120f51a5单次install-r，前后证书/UID及规范data700/files771一致。tools-after仅按既有合同新增local.properties摘要，实际内容匹配准确SDK，不是工具漂移。

本机首次调用因输出目录未建失败。Root用纯只读准入与相同模块导入重现准确lstat FileNotFoundError，确认发生在任何Device/远端派发之前；补齐目录后继续相同未提交操作，首轮原始记录保留，不是重放远端操作。

实际prepare 15b2a558-ac1e-406c-b801-d22c34db8acb仅派发一次，run17cbb017-0113-4765-ad44-ae477b38b9be；原子进程15349实际wait0/双EOF，但stderr返回P7V4_REJECTED_IOException，没有PREPARED，效果UNKNOWN。Native bc14c713-91a7-484c-bfa7-dbab06ac9098从未派发，未进入系统导出UI，也无ACK/审计通过。retainedOriginalClients=0只表示没有待收尾本机client，不能推断没有远端效果。

只读复核同boot的Test控制进程PID4501/UID10175/startTicks43815及准确argv，日志记录原异常包装分支；观察时仅本次准确run和namespace不存在，不能证明此前无副作用，也不能定位原IOException具体点。规范Test目录/readlink事实不支持直接归因为平台别名；不更改父目录规则。该操作只可核原回执，禁止重放；旧所有UNKNOWN继续按各自限制保存。

Root自持VM10529已正常wait0/双EOF，5558/5559端口释放；当前及回退包、失败原始数据/密钥和未决意图保留。未动手机、其他VM、Host或原桌面状态。独立Test类加载的辅助边界修复与完整prepare/SAF/UI通过分开，P7仍开放。

下一阶段并行：P7仅增加有限自有拒绝原因码，保留全部guard；P8缓存原绑定循环的有限失败事实，原owner/5秒/成功断言不变；IMPORT消费独立完成producer的准确原件，新SDK/producer/runtime均待准入；P9仍需最终共享源码重锁后三APK/API36，不能用本两APK结果替代。

## 阶段辅助回归与提交范围

完整pnpm check首次因本任务临时路径过长触发desktop-update-boundary Unix socket EINVAL，226.38秒实际退出1，前后快照相同；专用临时目录已核对并清理。只调整执行环境到独占短路径后，完整回归739.68秒实际退出0、双EOF且无截断；904源码、93封存材料（含seal）、14工具及18脏路径前后哈希相同。46组Node报告共1544项：1476通过、68跳过、0失败/取消/todo，另有独立smoke/原生辅助检查。ARM64环境不能执行x64原生目标，不能计为x64通过。

本阶段提交范围为Android验收工装、严格SDK/回执边界和文档；主App APK仍1abfab33，iOS产品、布局与原成功断言未改。实际prepare受控拒绝仍UNKNOWN、Native/SAF/完整迁移未通过，不把辅助回归升级为产品DEV。短路径临时目录已核实回收；候选.local目录按准确来源/日志/占用继续归属审计，当前/回退APK及未知数据/密钥保留。
