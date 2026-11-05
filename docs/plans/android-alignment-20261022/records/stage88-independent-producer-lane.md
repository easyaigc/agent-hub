# 阶段88：独立原件 producer 与 Main 导入通道

阶段87已提交并推送209e2339：新SDK、完整辅助回归和API33真实SAF导出单方法有限通过。新阶段以该冻结源码继续P7闭环；主线9a01新增共同规则、资源及iOS聊天管理差异另列，旧SDK不冒充最新主线或完整P7通过。PR135源00365b03的CI11395已通过，已普通合并33751166；公开同步11398通过，主线11397 workspace仍在运行。

## 实施范围

只新增android-main-saf-framework-producer.py。既有Native legacy PRODUCER已实现真实加密，采用admission-v1/profile p7-main-saf-v2；framework-v4继续拒绝PRODUCER，不修改旧IMPORT成功门。编译输入仍905项、62overlay和既有Native/SDK/EXPORT/IMPORT/helpers不变。新Host不在Gradle输入中，另由Root的selfSHA、Git HEAD/status和具名helper指纹准确绑定；这不是未审查工具的通用入口。

新CLI只完成独立producer，实际取得原Native PID/UID/start/boot/argv、原始wait/EOF、原PID退出、两次metadata与密文stat/SHA/读取/close/fsync资料后，按既有严格reader验证并发布13份来源材料及source-admission，共14件。原receipt中的processExitVerified=false不改写，独立退出证据另存。创建Root/ledger不能冒充已执行，也不自动调用IMPORT。

独审指出5秒退出观察未约束实际读取。R2只修该block：把原5秒与任何更早outer期限取min，覆盖app_live及最终app_exit，读前后均检查，finally恢复原期限，成功后才发布退出证据；其余46个AST节点及前后字节保持。原提案/独审保持，R2完整静态复核无阻塞；没有虚称原928项重新执行。

## 两阶段真实门槛

阶段87自有VM已正常关闭，旧/新所有scope与密钥保留。下一轮须新boot、准确当前HEAD新SDK，在producer前确认v4 controller/Documents进程均不存在，不接管旧owner。producer和之后IMPORT必须同boot、同SDK/profile与同一预审transport合同；producer实际完成及原PID退出后，再由第二份独立Root绑定真实14件source-admission，才允许唯一Main IMPORT。

输出目录须提前建立为准确0700空目录。未知结果保留原操作和回执，先对账，不重放；不拿任何既有EXPORT当IMPORT原件、不读取/接管旧scope、不补写ACK或伪造关闭。本轮真实执行结果见下；真实14件、IMPORT及完整UI/权限/原件/24域仍未通过。

## 对照与后续

iOS和Android生产UI、布局/token/文案及wire均不改。F55/F56的来源/权限共同断言保持；这是实际Main导入验收通道，不能关闭完整迁移、跨系统/手机DEV、F59、语音、未读或生命周期。P8失败几何与P9完整系统矩阵继续待验；任务/项目、F75及iOS A6暂停。

F59防遗漏/不同cache key覆盖的iOS保护切片已独审，尚未采用，原生因果用例另备；完整F59原件/journal双端归档与Android功能仍未实现。每个阶段先提交并登记后继续，不等外部待验项。

## 本轮实际结果与失败保留

新SDK seal 6c49ef60、905输入/62overlay/61固定pin通过，App/Test与阶段87逐字节相同；实际构建112.911秒，编译任务执行，不能写成仅复用缓存。新增Host脚本另由selfSHA 816d9dfe、准确HEAD/status及具名helper绑定。阶段87完整辅助检查1558项中1490通过、68平台跳过，不能代替本轮Host的完整检查。

API33新boot执行唯一独立producer：run 158ce0d8、Host 84168274、Native 1e2f60d2。原始JUnit为1项1失败，停在NativeMigrationMainSaf.kt:516的目录/规范路径联合断言；Host退出50、UNKNOWN保持，原adb子进程实际wait/双EOF完成但无成功远端退出帧。:515可能已mkdir/chmod/fsync，不宣称零副作用。未生成本轮密文、source-ledger或14来源材料；保留的IMPORT两个操作ID从未派发，不转为通过或再次使用。

只读核对当前两种App数据路径指向同一dev/inode，base现为UID/GID10174、040700目录，本轮run子目录ENOENT，原App PID已退出、boot前后相同。此证据不测量instrumentation targetContext.filesDir或Java canonicalFile，不确定历史断言的哪个条件失败；一条shell cwd命令引号不充分，排除该cwd推断。未读取旧scope或重放业务。

Root独立核对原始raw/wait与只读材料；本轮失败证明为340257cd。自有VM正常关闭，原QEMU实际wait0/双EOF/完整hash核实、原进程消失、5558/5559无监听；数据和密钥保留，R66实际占用2184994816字节未超原累计上限2279235584。关闭证明5d456555；其他运行时、Source25真机及旧UNKNOWN未触碰。

下一阶段仅修复framework App data/files对象绑定与诊断，采用新namespace、SDK purpose和新操作。旧v1失败目录、原意图/回执、旧SDK固定pin与成功断言保持；修复后的真实producer和第二Root独立IMPORT仍须逐项通过，不能以树外提案、HTTP成功或编译替代。

## 完整检查与本轮保留范围

唯一一次pnpm check运行380.105秒，真实退出1；原stdin关闭、stdout/stderr完整双EOF、源码3013路径/9项dirty及905输入前后保持。已执行的15组Node spec为260项、257通过/3跳过/0断言失败，不包含失败的独立smoke，也不代表后续检查完成。

失败发生在attachment-download-native的独立iOS模拟器冷启动：bootstatus在Data Migration阶段达到120秒，原子调用SIGTERM；原finally已正常关闭并删除该自建模拟器，官方列表独立确认。原回执/raw保留；不放宽原测试时限，不重放任何产品操作。之后本机实际可用空间跌至14GiB新运行门槛以下，本轮不新建大运行时，后续新候选需先重新通过资源准入再完成完整检查。

本轮实现与SDK、实际Native失败和环境失败分别登记，不宣称完整阶段验收或可合入。安全回收本任务已完成SDK的17989项Android可重建intermediates（删除前分配348844032字节、无打开文件），当前SDK/回退包、唯一证据、R66密钥及其他任务环境保留。新阶段先采用独审通过的目录修复与本地reader合同修复，编译/Native与完整回归待新候选单列继续。
