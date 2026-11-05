# 阶段91：同步主线与 EasyWork 原回执安全

阶段90以a5939b4a提交并推送。当前从该准确提交合入主线addf26ad（含PR142/143），同时移植独立审查过的P8安全切片。这里记录当前实现与验收边界；完整检查、原生、真实DEV按各自新指纹回填，不借用阶段89包或主线iOS证据替代Android通过。

## 主线合并与提交对照

主线与本分支有两个merge base，未用单一base覆盖源码。真实merge只有四份parity文档冲突：acceptance/design保留双方追加历史；manifest按alias与不可变change ID并集，701条旧变更均保留且无同ID异文；只有F55 revision/sourceNotes需合并，双方历史保留。产品表仍由原manifest/template生成。Android阶段85–89的FD、系统选择、目录与producer修复保持；主线的授权、阅读、资源、通知、聊天管理、Host及iOS修复保持。

PR143在PR142后补iOS真实桌面先答/手机迟到拒绝的测试与文档，不新增Android生产代码。它的69项限定场景不是Android验收。主线新增18个covered路径使结构库存由905变为条件923；旧SDK仍只证明原905输入。追加p8-easywork-receipt-safety-v1独立purpose，固定923输入/93 overlay/92 fixed+self与准确18个Native资格selector；旧190 AST按原顺序与原文字节保留，新12节点共202。主线与P8新增/修改、package和三个实际生成镜像分别固定，不改旧source API/固定映射免验。源码独审与实际guard/CLI/SDK结果分列；该purpose不准入旧P7/P9方法。

以下为本轮逐提交审查汇总的全部后续组，具体功能/交互/UI仍回原矩阵逐项关闭；主线已实现不等于Android已对齐。

| 组     | 后续范围                                | 当前结论/优先级                            |
| ------ | --------------------------------------- | ------------------------------------------ |
| EW-R01 | turn/steer拒绝与迟到结果                | 本阶段接入；P0，双端/Native/UI待验         |
| EW-R02 | 原队列无result回执只读恢复              | 本阶段接入；P0，真实队列/故障待验          |
| EW-A01 | grantRef冷恢复                          | Android后续；P1                            |
| EW-A02 | refresh先持久化、LAN转发/DPoP authority | Android后续；P1                            |
| EW-A03 | 408/429/5xx与撤销提示去重               | Android后续；P1                            |
| EW-C01 | Provider聊天重命名/归档/删除            | Android接入/完整UI待验；P1，非项目任务CRUD |
| EW-H01 | 历史同步与分页                          | 已有基础，当前指纹补验；P2                 |
| EW-S01 | 子任务有界revision/checkpoint/64项      | Android后续；P1                            |
| EW-P01 | 计划完整步骤ID/顺序、历史运行设置未知   | Android后续；P1                            |
| EW-F01 | 附件status-first、原manifest/offset恢复 | Android后续；P1                            |
| EW-F02 | 资源LRU/活动run/provenance/digest       | Android后续；P1                            |
| EW-V01 | EasyWork语音provider端口                | 现有录音规则基础，接入/实录待验；P1        |
| EW-U01 | 阅读权威/每设备计数/ACK未知             | 现有可见位置规则保留，Provider补齐；P1     |
| EW-B01 | EasyWork分支                            | 通用分支已有，Provider补齐；P2             |
| EW-T01 | transport与生命周期                     | 当前主线差异补齐；P2                       |
| EW-N01 | 通知接入与完整目标路由                  | Android后续；P1                            |
| EW-N02 | push恢复                                | Android后续；P2                            |
| EW-N03 | 声音与生命周期                          | Android后续；P2                            |
| EW-M01 | 完整P7/24域/F59                         | 当前执行；P0/P1                            |
| EW-M02 | F04/F60迁移相邻状态                     | 后续；P2                                   |
| EW-X01 | F37/F42/F56剩余交互/回调                | 按原矩阵继续；P2                           |
| PAUSED | F65–67/UI14–19/F75/iOS A6               | 保持暂停                                   |

## 本阶段安全实现

1. Android对齐iOS的guidance acceptance：严格布尔与accepted/stale/inactive组合，拒绝固定failed并移除原run/expected fallback；保留原输入，不伪装submitted。旧23条generic ACK向量和两端send不变，共用向量扩为41。
2. command/status的turn/start处于submitting/uncertain且没有result时，使用一次捕获的原Session，只GET原thread/messageId的queue/status。原ID/目标不匹配和结构错误明确拒绝；只有typed method_unavailable/queue_unavailable降为原uncertain。null或外层completed不构成已发送，不创建新操作、不重投原输入。
3. Android的nullable queue口仅用于这个具名查询，通用request/result、方法白名单和重试保持。iOS只替换原receipt方法，主线现有typed rejection、stale read和resource/upload-status处理及其他新方法保留；不整文件覆盖。17场景Swift测试扩充主线原9项；Native由4增至18项，原16条断言保留。
4. 中英共享拒绝文案及两条既有Android错误补翻译；原generator生成镜像。没有新页面/token/布局。失败/未知横幅、原队列行和新草稿/晚回调仍须实际交互验收。

独审未发现本切片新增P1、写入或重放路径。原generic raw state=confirmed的journal/UI边界另列：不能因此隐藏unknown等待或释放新草稿。当前合法队列状态集合尚不能按旧桌面源码推定完整新端口；窄队列保守派生与生产调用链因果验收另做，不扩大generic ACK契约。

iOS receipt仍是id-only API，依赖原caller完整目标与pending guard；不宣称新增原wireHash/params冷恢复绑定。加密peer与Foundation stub是辅助测试，不能代替真实Host、原owner和手机。

## 验收、交付与资源

本阶段源码移植及Root语义冲突处理已完成。原generator已实际同步1950键；完整mobile-parity辅助检查、Swift共享41向量及17场景原回执恢复执行通过，Node汇总19通过/0失败/0跳过。原16条Native断言及18方法仍是待执行源码，不由Swift通过替代。完整检查、iOS构建、新SDK、Native18及真实DEV尚待执行。每次结果绑定最终source/purpose/包及环境；阶段88冷启动失败保持，完整检查使用本任务新建的专用模拟器外部预热，不调低原超时，不使用物理iPhone/A6或其他任务设备。

F59生产R2、完整迁移激活/补偿/24域、语音、未读、生命周期与P9继续并行，容量及环境待验单列。所有旧UNKNOWN和原操作意图保留，未知结果先查原回执，不重放。任务/项目管理与F75/iOS A6暂停。

有限生成首轮因Root漏补3个生成镜像的change摘要而失败，仅生成productMD；Root按实际镜像补齐后原检查通过，未放宽覆盖规则。旧失败原流保留，17场景只执行通过一次。本阶段未产生新模型输入。
