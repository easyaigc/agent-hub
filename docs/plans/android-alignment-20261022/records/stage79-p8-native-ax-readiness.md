# 阶段79：原生AX与SAF验收器

关联F02/F03/F04/F55/F56、UI01/UI03。当前：两轮SDK通过，P8活动AX根获取仍失败，英文未运行，SAF实际回调待验；不新增产品通过。任务/项目CRUD、F75、iOS A6继续暂停。

阶段78中文短视口在easywork-name原生AX锚点0/1失败，英文未执行；原runner wait0不算JUnit通过。两个外层UNKNOWN和旧scope均保持，本阶段使用新构建/操作号，不重放。Stage78的四条Main迁移路径已有独立有限通过，不能用来替代本轮P8。

封存App的Compose UI版本metadata为1.12.1；对应一手源码表明原生树有可见/遮挡剪枝，同包root存在不保证准确semantics ID已能遍历。这是就绪假说，旧失败原因尚未证实。修订保持原Compose ID/testTag、完整值/label、可读/启用/可见/边界及原Activity/Session/Context；无按text/label降级匹配或输入重试。

只读等待最多5秒/64个新鲜root请求/2048节点/深度40。连续两份独立root须满足全部原断言且几何和可读状态相同。仅显式refresh未就绪、完整树缺锚0、Compose值已精确而AX尚未发布可等待；外部owner/window、多锚、tag/label/结构/几何错误立即拒绝。诊断只有预定义字段ID、anchor和计数，不记录字段正文、密码或全树。

独审发现并由Root在采用前修复：原acquisition、canonical Context发布与全部owner阶段严格关联，只允许按准确UID用户/package/case推导的两个Android CE别名；label关联统一在同一DFS，取得child前检查预算；已观察的重复/缺失label在任何可能等待的后置refresh前拒绝。R3独审无剩余P0/P1/P2。AST、pin和patch核对是辅助证据，未代表Kotlin编译。

独立 `p7-main-p8-short-ui-v2` 为两APK、准确40项overlay/896项预期输入、原七字段generic guard。v1 map/recipe、phase/JUnit/stress契约保持。Host仅v2核对两case finalizer、各14条有序字段证明、两份新鲜样本和原scope/owner；外部Root SDK seal/review SHA仍必须，提案清单不等于实际workspace review。

iOS源码、产品布局/token/文案、生产发现和迁移规则不变。P7 Main保持已通过的bd700e51；共用工具不代表重跑P7或SAF。P8仍Demo/nativeRuntime=false，仅Activity横屏/fontScale2，不证明shipping runtime、系统字号/TalkBack、完整视觉/物理或A5封存。iOS直接导入/无六身份区的差异保持待验。

后续分别登记新review/guard/seal、源码/双包/证书、设备/boot/UID、命名JUnit与字段证据、原inner/outer等待/双流、实际owner释放和VM退出。通过、失败、未验和原UNKNOWN分别保留。

## 本次构建与实际失败

v2 两APK构建通过，SDK seal为 `5593f9cc`，App `1e2f1fcf`、Test `c60c97d3`，准确896项输入。新run `e21321c8-d243-489a-910d-e6560e7f2347` 的中文首case在easywork-name准确原生AX锚点5秒截止失败，英文未执行。19次新root请求中18次完整观察缺锚、1次refresh待就绪；最后截止前重置的零计数不能证明此前未遍历。inner实际wait0但JUnit失败，外层wait1/UNKNOWN与原scope保留，不重放。

原App owner自然退出，已关闭Activity/Session边界；测试包后续缓存PID由模拟器正常退出结束，不能记为自然退出。原QEMU58015实际wait0、双流EOF、5558/5559释放，保留数据与密钥。Root分别复核SDK原材料和Native原始回执。

## SAF与失败诊断的共用下一包

P7接入独立 `p7-main-saf-v1`：准确44项overlay/899项输入，两APK。原四条Main路径保持；增加真实OpenDocument导入候选与CreateDocument保存后新owner还原。加密原文仅在设备侧固定双进程管道流转，使用shell_v2无PTY；真实字节摘要、双owner退出、provider归属及最终原生AX动作均须独立核对。文件以独占临时写入、fsync、atomic link、不覆盖既有目标发布。已有provider不接管；空闲不冒充退出。源码静态审查不等于真实选择器通过。

P8仅在原断言已失败后采集有限元数据：固定tag的merged/unmerged ID、准确Activity decor窗口ID、同原窗口原生节点ID/角色/边界和计数。Dialog窗口没有独立句柄，明确UNKNOWN；不换原anchor、不读字段正文、不扫描其他窗口、不重试输入。独审发现单节点查询会生成语义内容字符串，Root改为集合查询后固定count取ID，并将次生异常转换为无cause/message/stack的class-only记录；原失败身份和必抛行为保留。诊断同步调用使用协作预算，不能宣称强制取消Binder。

下一新SDK、P7真实SAF和P8诊断结果分别登记；本节尚未新增Native通过。任务/项目CRUD、F75、A6暂停，物理与完整矩阵待验。

SAF shared SDK first build failed at compileDebugAndroidTestKotlin: O_DIRECTORY is absent from the public SDK API. No seal/Native result was produced; original wait1/UNKNOWN retained. Root fixed both Kotlin/Java directory sync sites using public O_NOFOLLOW plus fstat/lstat directory/UID/inode checks before fsync and finally-close. New source guard, approval and SDK are required; no retry of the original build operation.

Pre-build independent review required public O_NONBLOCK on both directory opens to reject a substituted FIFO without blocking before fstat. R2 guard was not approved and no SDK ran from it. R3 retains all directory/UID/inode/fsync/finally boundaries and requires a fresh guard.

The next distinct SDK build reached javac and failed because Os.unlink is also absent from the public API. No seal/Native passed. Root audited every Os/OsConstants reference in both new helpers against the actual compile SDK; pending unlink now uses public Files.delete after verifying the exact owned regular hard-link pair. The private single-writer/provider-absent preparation boundary and no-clobber publication remain. All old build receipts remain UNKNOWN, not replayed.

## 共包 SDK 与系统选择器观察

修订后的独立 SAF SDK 构建通过：seal `9411b5e95d140905a1ea175cee622fce17e0504a23286460549d3be6928014c0`，App `1e2f1fcf`、Test `4fbb089f`，证书 `fc12e68f`。Root逐项复核92份封存材料、17个原工具wait/双流和899份当前源码；整包93文件。此前两次不同源码构建失败、以及仅生成guard但未批准SDK的版本分别保留，不当作重跑成功。

独立API33系统选择器展示与只读观察确认：导航ImageButton无资源ID，直接位于toolbar下且标准描述Show roots；保存Button为android:id/button1、文字SAVE；文件名EditText为android:id/title；当前目录可用breadcrumb_text。原验收器要求所有控制预先有ID，因此尚不能交付完整SAF profile。根目录与文档行未观察，不猜填或静默降级；后续窄契约须将独立观察与当前owned UUID目录/文档的运行时断言分开。

OPEN展示命令已确认，但首观察因dumpsys window windows不含全局焦点而失败；保留原失败。随后修正为dumpsys window，仅只读观察原窗口，没有重放OPEN；CREATE只展示一次。各新UUID XML均已精确删除，未选择、打开或保存文件。此处是系统控件元数据证据，不证明Native AX windowId、SAF回调、界面全矩阵或物理设备通过。下一新P8诊断使用同一SDK，但独立boot/run/原始回执。

## 本轮最终 Native 与后续边界

共包新run `8882a286-01db-4519-ac8a-97afb597b8f0`，API33新boot；Test只更新一次为 `4fbb089f`，App字节及UID/签名不变。实际JUnit仍为1项/1失败、英文未执行；原错误是在首次活动AX根获取时返回null，不能记成等待5秒后缺锚。原inner wait0/完整18717字节与outer wait1/UNKNOWN分别保存。

失败后有限观察确认本轮merged/unmerged ID均为232，与原anchor相等；准确Activity decor窗口ID为2，但原active windowId尚未建立。不能把失败后的窗口补成原窗口证明，也不能用visited=0推断没有节点。Dialog窗口仍UNKNOWN，诊断整体UNKNOWN/incomplete；旧缺锚失败原因不能据此认定。后续核对原生无障碍连接与窗口发布时序，保全部匹配断言，不重放输入。

原Activity/Session/IO/writer关闭，原App PID4317/start28557自然退出；Test UID进程4422随VM正常停止结束，不计自然退出。原QEMU33368实际wait0/双流EOF及完整流摘要、端口5558/5559释放已核，失败scope/alias未删除。R66累计增长214847488字节，在原512MiB预算内。

低空间时按精确清单退役18个旧SDK APK及4个本任务可重建目录，私有空闲Gradle正常停止。当前9411及上一个安装包5593、源码/操作回执/密钥/数据保留；旧包体已退役的bundle不再具备完整验证或匹配复用资格。具体逐项凭据保存在私有资源台账。

P7角色profile R3候选已静态独审，尚未采纳/编译/真实回调；其原30秒deadline传入所有动作并在最终输入前复核。P9硬性只读DIAGNOSTIC_ONLY候选尚缺新的API36三APK闭合purpose/guard；两者不借本轮SDK宣称通过。下一阶段在本阶段提交并更新总表后继续，任务/项目CRUD、F75与A6暂停。

完整根命令 pnpm check 已通过：756.468秒，42组Node汇总1518项（1456通过、62跳过、0失败），其他smoke在同一原命令内完成。Root重核原wait0、双流原始字节/摘要和899份源码；本次Swift专属68个中间文件已核后回收。此检查不能提升P8失败或未运行的SAF/物理矩阵。主线同期Windows恢复已前进，PR前保留共同F03历史并显式对账。
