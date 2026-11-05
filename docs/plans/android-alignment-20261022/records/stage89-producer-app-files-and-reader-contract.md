# 阶段89：App目录绑定与独立producer读取合同修复

阶段88已按准确9文件提交并正常推送bf80d424；SDK通过，实际producer目录断言失败及完整检查的iOS冷启动失败分别封存，不宣称完整阶段通过。原UNKNOWN、原密文、旧scope/密钥及未派发IMPORT记录保持。本阶段承接该准确源码，最新主线867ab19的新增规则/UI另行逐提交对照，不以旧905输入包充当最新主线通过。

## 实施与独审

采用四文件树外R2，索引d53d9ff7、独审8c6e2c2b。Native新增App专属framework data/files校验：限定targetContext、准确包/UID，校验已知平台父路径、规范路径和dev/inode/UID/GID/完整mode，叶目录禁symlink；framework files允许0700/0771，其余新namespace/run严格0700。持有目录FD并前后核对，mkdir/fsync后finally关闭；已有目录只验证，不改权限或修复。新v2 namespace不读取/接管失败v1目录，旧TEST helper和Main成功断言保持。

两条有限witness报告固定枚举/布尔/mode与错误分类，不输出业务内容。第二条NEW_RUN_DURABLE沿用初始lexical字段，create/verify重新校验规范对象和FD；不能称为第二次独立lexical采样，也不宣称可抵抗恶意同UID路径替换的dirfd原子隔离。历史:516哪个predicate失败仍未确定，真实framework路径行为待新Native观察。

Host producer/import由不同Root schema选择有限匹配SDK/guard/Native binding/namespace，旧入口保持旧合同。新增SDK purpose只供producer与独立IMPORT；旧Export/P8不自动接纳。旧180 AST/prefix/固定映射/配方保持，预期新190 AST，投影仍905/62/61fixed+capture-self，编译三项变化、其余902字节不变；Host producer另固定selfSHA。此为静态预期，尚非执行guard/SDK证明。

Root审查发现R1 producer完成后本地验证视图仍带PRODUCER schema，会被IMPORT reader的有限选择拒绝。R2仅复制出本地只读视图并映射相应IMPORT schema，kind仍PRODUCER、原Root/runtime/操作/目标不变，比较SDK/guard/Native/namespace合同一致；不持久化或提交该视图，不调用真正IMPORT准入或执行。实际IMPORT仍要求另一Root和真实闭合14件。

## 有限辅助结果

三个Python compile/AST与34条纯函数边界向量通过：有限mode/alias、witness缺失/额外/乱序/类型/来源/关键布尔/模式、旧binding拒绝新witness、status/selector/重复或迟到字段/admission摘要等拒绝；阶段88原始FAIL仍拒绝。R1两种原视图均因具体Closed IMPORT Root purpose拒绝，R2两种视图合同匹配且原Root保持。这是合成辅助断言，不是完整source_snapshot、真实14件或Native通过。

执行有一次探索及一次留存wait/EOF的记录运行，如实分开；记录运行exit0、双EOF及完整stdout/stderr hash已由Root核实。原探索stdout从本任务正式日志另存，不推断未保留的独立exit/EOF。CLI --help尚未执行。Root辅助主证明bd6b412e；原记录材料字节保持，移出封存提案目录保存。iOS/Android生产布局、token/文案与迁移wire不改。

## 下一步与单列待验

当前空间不足既有14GiB新SDK/完整检查/运行时门槛，不降低门槛或启动大运行时。先完成本阶段有限检查并提交；资源满足后准确新HEAD/新SDK、新boot与新run/Host/Native ID，实际producer/原PID退出/14件独立核对后，再以第二Root准入同boot/SDK/profile/transport的Main IMPORT。全部旧UNKNOWN不重放。

完整迁移24域/F59可移植原件、语音实录/未读/生命周期、P8/P9完整UI及physical DEV仍待验。并行对照最新主线已发现P8拒绝追加被误标submitted、无result的原队列回执恢复缺口，优先另做有限安全切片；F59生产防遗漏/因果用例已独审，Android独立互传代码提案进行中，均不计当前Native通过。任务/项目管理、F75与iOS A6暂缓。

## 交付与资源

PR135已普通合并33751166，原主线CI11397两项及公开11398通过，GitHub对应tree/trailer独立核实；已正常清理准确stage84本地/远端/tracking分支。最新远端main867ab19包含该合并，祖先关系已核对。阶段85/86/87/88及本阶段分支仍有后续交付依赖，保留至准确PR/主线结果核实再清理，不用强删。

已正常关闭阶段88自有VM并核对actual wait0/双EOF、原进程消失和无监听，R66数据/密钥保留。已回收本任务无打开文件的Android可重建intermediates；当前SDK88、回退86、P9独立诊断和最小必要失败/回执材料保留。其余资源只按准确归属继续收尾，不删除共享/活跃/唯一资料。
