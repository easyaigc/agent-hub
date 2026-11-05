# 阶段98：F59 portable 域第一切片

前一阶段97已提交推送40181939；当前从该源码继续，不改写阶段97的硬链接准备失败及完整回归预热采集未知。此处只接入有限实现，完整P7/24域仍开放。

## 实现与双端对照

- Android普通Store与迁移适配器现在共用唯一F59 journal decoder；保留原目标/account、manifest.id、phase、错误及匹配receipt，不写入/claim/retry/退休。新增逻辑名与完整目标派生key一致、certificate已有capture长度约束；旧数据兼容仍须真实原生回归。
- 流式graph核对全部声明journal/chunk、原分块摘要与可完整读取原件的总字节/总SHA，拒绝重复ID、孤立/不明缺失、错目标和额度不足。部分capture保留未封存语义；COMPLETED缺失只作结构允许，仍需后续严格源读取器证明原terminal journal barrier。
- KMP共同规则定义惰性描述符闭合、引用/顺序/唯一性、终态缺失和Long额度，没有OS句柄/源密钥/URI或发送权限。Android graph调用共同规则后仍读取验证真实字节；iOS尚未接入，不把JVM测试当双端原生通过。
- 独立审阅发现共同规则与Schema索引范围不一致，Root统一每原件最多4096块、canonical索引0–4095、全域最多65536描述符；Android实际每journal/单文件额度继续更严格。3GiB辅助场景只有3072个描述符，无真实巨型I/O。
- 闭合Schema使用本仓现有Ajv6支持的draft-07，同shape与严格语义分别校验。合同测试纳入check:mobile-parity，禁止惰性描述符夹带激活、重试、源设备密钥、URI和路径字段。

当前没有接Export/Activation，也没有降低存在F59时整包拒绝。旧v4总192MiB/1024 records、Android原件1GiB/总4GiB及iOS整Data/Payload限制保持；不能只迁移可容纳子集来称完整。

## 实际辅助结果

| 检查            | 本次结果                                  | 边界                                                                                                         |
| --------------- | ----------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| KMP JVM         | 6方法，0失败/0错误/0跳过；6任务执行/1复用 | UNKNOWN缺失、部分capture、跨journal别名、孤立/重复、Long额度、4096/4097和canonical索引；不是字节/OS或iOS运行 |
| Node合同        | 3通过/0失败/0跳过                         | 两处名称边界、惰性无权限及整数/额度，形状不代替完整图/原件校验                                               |
| Android普通编译 | 11任务执行/71 up-to-date，App/Test同签名  | 没有本轮sealed SDK资格、安装、Native或设备UID结果；旧包的31/30结果不迁移到新包                               |

原构建/测试实际wait0、双EOF/关闭和源前后指纹保持，JUnit与原流在私有索引绑定。编译实际输入含新Android/KMP源码；随后Schema仅把未接线的声明改为本仓支持draft-07并单独通过Node合同，应用源码不变。生成/一致性/全仓格式及本阶段提交结果由交付记录核对，完整pnpm check继续补验。首轮生成因Root把根package.json误放进仅允许apps/packages/scripts/design来源的manifest而拒绝；已移除该来源登记，根检查接线改动保留，原生成器/边界不放宽，原失败档案保留。

## 未实现、待验与界面

Graph不是strict source snapshot：真实原writer freeze/exact generation、既有child/key只读打开、固定FD/身份和全库存、sidecar/关闭、原barrier、lease有效期及出版前重验仍未实现，下一阶段优先。还需独立新版本的流式大容量容器、candidate/pointer lineage、目的端惰性原件读取与冷恢复，再逐组验24域和Main实际激活/错误/补偿。

本次没有新增页面/交互/截图；iOS对应独立文件入口及AndroidF59/UNKNOWN、完整迁移preview/激活/失败文案继续逐项对照。真实Host/SAF/手机、主题/方向/字号/AX、自然故障与iOS互通未验，不提升整项F59/UI13。

静态对照另发现iOS prepared或UNKNOWN查询404会回READY的原路径；最小安全候选单列未采用，不能为表面一致让Android回退。legacy READY/严格消费及实际iOS验收仍开放，iOS A6暂停保持。任务/项目F65–67/UI14–19、F75继续暂停；语音、未读、生命周期与P8/P9待验项不删，未知业务操作不重放。
