# 阶段86：系统选择器文件名阶段

阶段85已提交0e65ec80并推送安卓专用分支。PR135的iOS通过、Linux11372失败，隔离fixture因果同步修复/完整回归进行中；未合并或删除未交付分支。任务/项目管理、F75和iOS A6暂缓；旧UNKNOWN不重放。

## 问题与改动

上一轮真实Main导出已确认prepare，选入准确自有系统目录。Root只读hierarchy和截图证明文件名初值为AgentHub.codexmigration，与生产NativeMigrationPage的CreateDocument初值一致。测试在唯一SET_FILENAME前却要求UUID目标名，因此未派发设置/保存，无法等待到正确结果。

现在匹配分两个阶段：唯一SET_TEXT前严格要求原默认名与原class/id/目录/window/path；动作返回true后仅标记已派发，后续实际读取必须是目标名，SAVE继续要求完整目标名。标记不是UI接受或业务完成证据，不允许重试、替换控件、跳过回调/授权或放宽时限。原oldField前后相等、原节点/路径/窗口/owner守卫保持。修复也适用于profileV2的旧role-v2；v1的匹配规则不变，不笼统宣称所有旧运行行为未改变。

## 共同SDK与验收边界

五路径采用，905项输入只变5项、900项不变，60overlay/59fixed+self。新增p7-main-saf-filename-phase-v1及对应Root/review/runtime目的，旧Capture160节点/前缀/map/recipe冻结，新增后170节点，wrapper有限无环。EXPORT/IMPORT显式保护Source85 run835a58e0、prepare d6e02b7b和Native13543e28，不恢复原失败。P9冻结DAG不变，P8仅新增共同目的选择，没有修改其原几何/AX成功要求。无发布版App布局/token/文案改动，iOS不受影响。

独审d37eafc6完成971项静态核对；Root核验所有15材料、五组before/after和905投影后采用。当时新SDK/Native尚未执行；后续实际结果见下，不记为整项迁移通过。后续用全新run/operation核对系统选择、唯一设置/保存、真实回调/grant、FD/ACK/密文及原owner；独立producer/IMPORT仍需另外准入，旧EXPORT不冒充输入。

## 待验与后续

P8上一轮同原anchor仍零几何，四输入/14字段/第二case未完成，继续独立诊断，不能放宽AX断言。完整24域、跨系统迁移、F59、语音/未读/生命周期、API26/37和手机DEV保持开放。原R66所有数据/密钥/失败scope保留，Stage85 VM已正常wait0/双EOF/端口关闭；下一次准入重新绑定真实启动与包指纹。

本阶段构建/回归/实际验收结果随后写本档案；总表只维护范围与完成状态，过程不回填到总表。

## 本次构建与回归

SDK2f0cf526实际117.9秒、wait0/双EOF/无溢出，五项Java/Kotlin编译任务均实际执行。App1abfab33、Testf9d5dd55；905输入/13改动前后相同。格式化guard6e1eaaaf与传递给Native的规范JSON guard7db9749a分别记载，不能混用。

完整pnpm check实际790.30秒、wait0/双EOF，46组1544项中1476通过、68跳过、0失败；905输入、13项改动、索引、HEAD及材料/工具不变。本次另外保存可无损读回的真实目录before/after清单，Root独立复算20504→21053、549新增目录；差集仍只作归属候选，不直接授权删除。不把本机通过替代Linux CI。

## 唯一真实导出及原件对账

新run0b2dd747、prepare880e07d9取得PREPARED原回执，之后Native e6cdc5a7仅执行一次。真实系统SHOW_ROOTS、CHOOSE_ROOT、SET_FILENAME、SAVE和callback/grant已观察；准确新目录/节点/窗口/角色都按原守卫核验，文件名顺序修复越过旧卡点。

Native在MainUiTest第466行原始metadata字符串比较失败：首次稀疏合成基线与正常持久化增加空domain/字段顺序不同；第561行收尾的同一比较也拒绝，完整owner退役/安装释放未确认。内部实际wait0/完整双流中JUnit1失败，外层50/UNKNOWN保持，不重放。下一轮先准备完整正常metadata形状，保留所有现有字节级/owner/回执断言，不用忽略字段或伪造成功过关。

Root仅对该准确run的原文件只读核对：原payload-ack为PAYLOAD_COMMITTED；设备stat/SHA记录output.bin为4328字节、fb172d13…，与原ACK和Native预期一致。原ACK声明EOF、sink/receiver关闭、文件和目录同步/发布，这些是原进程持久声明，不是独立历史fsync证明。未调用会写audit的接口、未补写ACK、未恢复任何旧输入，完整双快照审计未完成。

同一准确PID/UID/start/boot下，run-as Test只读FD观察发现Controller与Documents仍有该run的元数据/claim/lock FD，Documents未见output.bin/output.pending FD。因此不能声称所有FD已关闭，也不一概否定已显式Os.close的payload sink、PFD receiver或Pending witness。AOSP Android13/API26查证显示FileInput/OutputStream(FileDescriptor)不拥有原FD，关闭wrapper/channel不能替代原Os.open的close；下一轮仅修复辅助Lease明确缺口和未返回dup异常出口。生产相邻路径未发现同类流包装漏关，SoundProvider极端dup异常出口只列未复现风险，不混入本轮。

原App4603、Host/Native退出后，Root只正常停止原QEMU98994，实际wait0/双EOF/5558与5559关闭。内核进程退出不冒充逻辑owner退役或原ACK前关闭证明；所有原目录/密文/ACK/密钥仍保留。新阶段保护0b2/880/e6三个ID，不接管或重放。

## 并行与资源收尾

P8本次没有另行重试。Source85失败精确发生在首个Native AX sample之前：samples1、freshRootRequests0、visitedNodes0、fatal compose-anchor；19节点/15ID仅来自失败后的诊断，不能称原AX等待遍历。原size仍正未单独记录，唯一根因未确认。下一轮只在原失败预算里补有限祖先/公开几何和scroll观察，不改成功门、增加输入/滚动重试或换窗口/anchor。

PR135的fixture因果修复六个目标场景通过；隔离完整检查因Kotlin/Native下载中断实际失败，使用已核验同版本缓存单文件补齐后重测，不覆盖旧失败。新源码/主线CI与公开同步未通过前不合并/清理分支。

阶段85的131个fixture根和精确TMP已实际回收294748455逻辑字节，253份最小证明保留。阶段86又回收四份已核验重复APK共150111066逻辑字节，原回执/封印不改，保留当前SDK86、回退SDK85和P9三包；SDK84因App副本退休已不完整，不能按原路径复用。APK旧路径在历史argv中出现不等于仍有活跃依赖；替代精确字节路径和指纹已登记。DocsUI快照、旧scope/密钥及共享依赖保护。本轮fixture和TMP在归属/占用/最小证据审查后继续回收。以上逻辑字节不等于实释空间。

执行方实际Terra/medium由Root核对；速度接口无参数、未核实。本轮不关闭完整24域、IMPORT/producer、F59、跨系统、真实模型/手机DEV或P8/P9整项验收。
