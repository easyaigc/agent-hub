# 阶段85：协作发布与保存后视口

阶段84已提交04a62471并推送；PR135的iOS通过、Linux迟到回调测试失败，未合并，隔离修复中；阶段83经PR134合入637928fe，源/主线CI与公开同步通过，阶段83本地/远端分支已清理。此阶段继续P7准备/真实SAF和P8短视口；任务/项目管理、F75及iOS A6暂缓。所有旧UNKNOWN按原边界保留，不重放。

## P7 发布修复

新独立namespace p7-main-saf-owned-v4；旧v3和8e原件不枚举/读取/迁移/接管。wire-v4、authority/selector保持，新的SDK/Root/runtime目的及准确源码/包/启动信息共同绑定新存储语义，旧批准不能复用。原失败明确为SELinux拒绝link，具体errno数字未记录，原8e仍UNKNOWN、Native8f从未派发。

每run创建一个稳定空publication.lock。首次创建也先取得进程内占用权，再打开O_EXCL FD；严格regular/单链接/UID及GID同进程/完整0600/size0。所有合法写入与audit使用同一先占用→FileChannel.tryLock护栏，busy直接拒绝；audit从已验证锁元数据计算空hash，禁止旁路再开关锁FD，避免POSIX关闭同inode其他FD释放锁。无删除/重建锁路径。

在已持锁下，只有lstat明确ENOENT允许同目录rename；任何既存目标类型都拒绝。原写FD与只读witness绑定，witness跨rename、最终inode/长度/mtime/nlink1及目录fsync，保持原sink-close/read-close/publish/ACK顺序。释放锁或锁FD关闭不确定则保留该run进程内占用至进程结束；保留最初失败并附加清理异常，目录FD关闭单独失败仍抛出，不冒充正常释放。

该保证为全部合法参与者遵守锁协议下的no-overwrite，不是内核NOREPLACE，也不抵御原威胁模型已排除的同UID恶意并发。无需NDK/JNI。MAX_FILES保持20，完成态15、保守失败尾部17；pending隐藏，ACK仍须真实文件/目录fsync与关闭结果。不以只读FD关闭或辅助编译证明完整解密/业务完成。

## P8 视口准备

真实Source84证明原控件/窗口/ID一致但clipped边界0；这只是保存等待前后可见性证明存在时序缺口，不足以断言唯一IME、滚动或产品布局原因。现在先确认savedName，再进行最终completeInput/滚动与完整几何准备；原composeAnchor读取在同一5秒/64次内追加已附着/放置、正尺寸和完整同anchor几何硬检查。没有增加业务输入、滚动重试、窗口/anchor替代或AX fallback，原4项输入/14字段和成功断言保持。发布版布局/token/文案及iOS行为不变。

## 源码与验证状态

十路径已采用，60overlay/固定59+self/905输入，895项不变。Capture3caa616e；旧150顶层节点/配方/静态map原样保留，新增后160，wrapper有限无环。P8 Host只增加新目的常量、10处有限成员和一个独立guard分支，旧目的仍保留，P9冻结链不变。

独立最终审查e76bd73b通过；Root逐项读回29材料、10组before/after与905投影。四Java在仅android.jar下编译实际wait0/双EOF，生成10个class并核验清理。新SDK7acfa0d0实际构建106.19秒、Java/Kotlin编译通过；App1abfab33、Test640261fb，905输入/工具前后不变。完整pnpm check实际756.312秒、wait0/双EOF，46组1544项中1476通过、68跳过、0失败；源码/18项改动/暂存/HEAD前后相同。这些不升级F02/F03/F04/F55/F56整项验收。

## 执行顺序与保留

1. 新目的p7-main-saf-publication-lock-v1完成实际905输入/工具/构建/包核对。
2. 全新run/operation先验prepare，再实际Main SAF导出、FD/ACK/关闭/密文与原owner；独立producer/IMPORT随后，旧EXPORT样例不冒充输入。
3. P8新独立两case依次验名称、14字段、长文/短视口/AX及完整菜单/恢复，不以新增硬检查代替实际通过。
4. 本阶段完整辅助回归、提交安卓分支、更新总表/档案后继续24域、F59双端缺口、语音/未读/生命周期。待验与环境矩阵单列。

R66原8e/v3及P8失败scope/alias、P9离线AVD合成Keystore继续按准确保留条件保存；原本地P8外层UNKNOWN未改写，首次Native回执独立。当前build/签名上下文仍需新SDK/验收，已完成阶段的可重建fixture在最小证据核对后及时回收，保护共享缓存、正式环境和全部原件。

## 本次原生结果

P7唯一新run835a58e0、prepare d6e02b7b取得原不可变PREPARED回执，控制器PID4844/UID10175/启动及完整意图独立关联，发布锁实际准备已确认；不凭本地退出码0判断效果。Native13543e28仅执行一次，内部adb实际wait0/双EOF但JUnit1项失败，外层50/UNKNOWN保留。SHOW_ROOTS、CHOOSE_ROOT已执行，准确自有目录就绪；在设置文件名之前等待超时，未SET_FILENAME/SAVE、无回调/授权确认/expectedBytes，不能记为导出或迁移通过。

Root只读核对同一Task/目录的真实hierarchy与截图：android:id/title为EditText，实际默认值AgentHub.codexmigration；生产NativeMigrationPage同样以此默认名启动。测试却在SET_FILENAME前要求UUID目标名，形成顺序缺陷。下一阶段按默认名→唯一设置动作→目标名观察分开，原失败不重放。原v3未读取或接管。

P8新run28fd9ce6的首次Native仅执行一次：原App5924、窗口及绑定明确，首中文浅色case名称替换/IME确认通过；保存后同anchor232仍得到[0,0,0,0]可见边界，原5秒/64预算内准确几何检查拒绝，19个AX节点/15个ID均无232。四项输入/14字段及第二英文深色case未完成。内部实际wait0/完整输出中JUnit1失败；外层非零/UNKNOWN不改判。原scope/alias cleanup未开始、状态UNKNOWN，保留，不删除/重试。

此前P8本地fresh草案字段读取错误发生在outer/Native前，native空目录证明首次执行尚未发生；修正wrapper与原失败分别留存。最终准入对完整AVD写层计数，不用约10MB基础镜像代替约2GB总分配。Root独立读回11条fresh原始回执及SDK/sourceguard后才批准首次Native。原P7 App4977及P8 App5924、各Host/adb进程均已退出；Root核对准确启动实例、App PID缺席及Host/Native进程退出后，仅正常停止本任务emulator 5558；原supervisor 38578实际等待QEMU 38580退出码0、双EOF，5558/5559端口关闭。R66完整写层分配2,124,832,768字节、容量2,279,235,584字节；磁盘、Keystore与scope原件保留，没有wipe或删除。

## CI与资源

PR135的Linux11372在cold64 duplicate-late的firstPassed断言失败，iOS通过。原fixture每帧100ms发重复回复，而首逻辑读取有两个subframe；源码证明存在先后缺口，具体CI调度缺原事件证据，不宣称已复现。隔离修复只在完整逻辑读取成功后释放一次原id1重复，独立观察原Native worker退出后再下一次读取；保留750ms/1800ms和原拒绝/退休断言。2/8/64的六个相邻场景通过，完整检查进行中；未凭本机结果合并失败PR。

阶段84的131个已归属fixture目录及专用TMP实际回收294748140逻辑字节，253份最小证明保留。四份旧SDK重复App副本实际删除267302492逻辑字节，原封印与回执不改，相关旧bundle现不完整、不能按原路径复用；当前SDK85、回退SDK84及P9原三包仍完整。逻辑大小不等于实释空间。阶段85本轮fixture待最小证明、占用和归属核对后回收，不清共享依赖/缓存或离线Keystore原件。

Root核对执行方官方记录为GPT-6.1 Terra/medium；接口不提供速度参数，速度未核实。以上模拟器与辅助结果不替代API26/37、真机、iOS完整迁移、24域、F59或真实模型/桌面DEV验收。
