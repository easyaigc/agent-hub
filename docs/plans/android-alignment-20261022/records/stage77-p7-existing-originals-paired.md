# 阶段77：已有惰性资料目的端合并

关联F55/F56。状态：R3新SDK及API33首次迁移/同boot不同PID只读cold两项Native有限通过；原first库存失败保持、该run的cold仍未执行。不关闭完整P7，阶段75的空目的端结果不替代本轮。

本轮目的端已有Inbox、document/photo journal及payload原件，但没有真实电脑连接；Android/iOS原有迁移准入继续拒绝合并两套真实连接。来源33条portable原件，目的端五类资料由正式本地producer产生不同文本/PNG，同时保留相同request ID。独立witness从双方实际字节建立预期并集，不用生产merge函数充当oracle。新运行核实来源26文本/7二进制、目的端18文本/6二进制，合并活动图43文本/13二进制；三组catalog合并，另有两个audit记录，不能把57条输入直接当活动条数。

实际核对37个登记store、4个Session实际join、30个外层writer退休返回和2次lease release；另外2个旧root与5个目的端child由production restore退休，调用前登记准确委托句柄，helper实际返回后才确认，不重复await/退出。unknown acquisition与forbidden port均0。完整可见草稿、持久metadata、两侧原件/活动图/原回执及同boot不同PID只读cold逐项核对；这些返回计数不冒称物理FD总数或strict graph。部分失败/未知仍保留原owner和操作，不重放。

九项Native/Host源使用独立existing purpose；Root修正archive header只接受既有Child或新Existing两种准确schema，与正文校验一致。共享SDK仅新增独立两包profile和闭合38源（原36加Witness/具名adapter），不改变旧P9三包入口。R3重新核对895项完整输入、真实main基线3dde5760及新review/guard/SDK seal，没有复用首套seal或三包Host桥。

iOS对照：原资料无损、导入准入、旧授权清空/新reader和未知回执不重放继续适用；平台picker档案格式不同，未声称双端整包互通。密码/预览/确认/取消/错误/成功、短视口/方向/字体/AX及实际Main界面另做新阶段；本阶段没有生产页面/token/文案改动。

真实Provider/SAF/OS grant/相机/麦克风、完整24组、物理DEV及自然崩溃/故障保持待验。任务/项目CRUD、F75和iOS A6继续暂停。所有新运行须使用新发号scope，旧UNKNOWN及真实模型账本保持。

首套SDK seal 17caad942d805c9bc05079afd911cd1843c2f332bb842f47607a33e67385993e，真实895源/38overlay、两包同证书；Root重算92封存材料、17原wait=0/rawComplete、外层实际wait=0/双EOF/fsync。入口首个缺空目录错误在任何构建/操作前退出，保留原回执，修复后只发生一次实际build。两次安装各一次成功；安装监督器最初误要求只有Success单行，实际Streamed Install为两行，核已装SHA后仅修监督，不重装App。

新run38281e4f-5826-4a98-833e-c39fb4a6c738的first原runner实际wait=0，但JUnit为1项/1失败，原pair wait=1/双EOF；在pristineWindow目录断言拒绝，cold未调用，没有接受原件或37store等成功计数。原日志未带具体文件名/点位，不能推造。仅在该run的只读元数据诊断确认：实际目录700/同UID/canonical，五个具名写入锁均为普通空文件、600/nlink1；原installation-*筛选将其交给只允许目录的walk。没有读取正文或改变原失败scope。

Root R3只修test库存：精确五producer控制文件与密文分开，复用NativeInboxOwnership原校验及前后lstat身份/权限/零长度/单链接核对；generation必须来自已验证目录，BEFORE_CONSTRUCTION不接受控制文件，未知项仍拒绝。只核控制文件元数据，不打开/关闭其FD或取得/释放其锁；KeyWindow协议、业务存储与原owner归账不改，目录错误增加点位诊断。原失败保持，不能重放原first/confirm。R66 VM已正常关闭，原Popen实际wait=0/双EOF/fsync及端口释放已核，数据/原key和失败scope保留；新SDK/新run待验。

R2源码审查发现：另开/关闭控制文件只读FD可能扰动进程记录锁，该版没有编译或执行。[固定AOSP实现](https://android.googlesource.com/platform/libcore/+/7267082c39a25fb5afbc4eeb5752c1b276d29344/ojluni/src/main/native/FileDispatcherImpl.c)使用传统fcntl锁；[Linux锁契约](https://man7.org/linux/man-pages/man2/fcntl_locking.2.html)说明同文件其他FD关闭的影响。当前API33镜像未映射该源码，也未做实验；R3直接避免控制文件open/close，不依赖此镜像推断来取得通过。密文与目录的既有读取校验保留。

R3最终SDK seal d7e4d46624a8edf1e23b38ee36228c7142e30e0e8411ac0297d57e9660cdb2a1；Root核92封存材料/17原wait=0/rawComplete及895源/38overlay。App f496cd9b、Test3833c48f，证书相同；已有App逐字相同仅验证，Test只更新一次。新run a0eb537d-b0f9-4070-a681-9305b386dcbe的first PID4531/start66990与cold PID4706/start69554，同boot e365232e-7bd1-4dd0-aeca-5bea4d22b79b/UID10174。两命名JUnit各OK(1 test)、原runner wait=0、原pair wait=0/双EOF/fsync；first原进程独立观察为proc ENOENT且目录不存在后才进入cold。Root按原始字节核证明/worker哈希关联、30条child文本及13条二进制并集，cold活动图/草稿/归档/回执/generation和受保护数据精确保持，两个reader关闭返回。

本次运行采用实际14GiB入口资源门槛与明确reserve/增长预算；原engine没有cold前资源hook，期间抽样只记观测，不伪称同步门槛。全部原worker闭合后正常关闭唯一自有VM，原QEMU wait=0/双EOF/fsync与端口释放已核；R66数据/密钥/各scope保留。Android fresh setup未声称强制显式重新注册，iOS身份策略差异继续单列。真实Provider/SAF/OS/完整UI/物理/24组和双端整包仍未通过，下一步实际Main迁移界面及P8短视口分别验收。

最终完整pnpm check仅执行一次，原Popen wait=0/双EOF/fsync，776.870秒；42组1518项，1456通过、62跳过、0失败/取消。独立parity、生成、九文档格式和diff/cached检查通过。Root核895源码、10绑定及HEAD/index/19路径前后一致，并从本任务官方子Agent记录核实实际Terra/medium。未逐次复制的正式工具返回已从官方own-child记录恢复，没有为了补证重跑；入口没有可核验速度参数。编译/检查与这两项有限Native均不替代完整产品DEV。
