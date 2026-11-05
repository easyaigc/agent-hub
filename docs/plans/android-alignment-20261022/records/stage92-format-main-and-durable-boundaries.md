# 阶段92：格式修正、主线续接与公共持久性边界

阶段91已提交推送32f21759，PR145为草稿；未解除草稿、合并主线或清理未合并分支。本阶段处理真实检查失败并续接主线ae0fa4b2（PR144），继续保留原操作、失败与UNKNOWN，不重放原业务。

## 本次检查与格式修正

32f21759的pnpm build:ios实际wait0，243秒、双EOF/关闭且无溢出，原始输出BUILD SUCCEEDED。构建后可用空间不足14GiB，第一运行器未启动完整检查。自有模拟器正常关闭删除，官方确认消失；源码不变。

随后完整pnpm check首次实际执行，在首项Prettier因easywork-receipt-v1.json格式退出1，后续链式检查全部未执行，不记测试通过/跳过。其自有模拟器及临时缓存均正常回收，原流/回执保持。原Prettier仅格式化该JSON，16756变为16716字节；41项严格有序JSON及字符串外去空白原文完全等价，未改原23项或新增18项的字段、顺序、预期。

尚未构建的P8 purpose只重锁这一fixture的准确after摘要，923/93/92+self、18项资格和schema均不变。旧190 AST/原文前缀保持，当前202节点仅这一个新P8映射常量变化；旧P7/P9不变。原32f的review、923投影、11条辅助拒绝向量、guard与全部材料保持历史字节；从未capture-build或准入Native，不把重锁当未知操作重试。新HEAD须另建Root review、材料、guard、资源准入及包，不拿旧材料证明新包。

## 主线逐提交续接

PR144只增加iOS队列验收记录及check-ios-state/verify-ios-migration两份工具，不改iOS或Android生产代码。实际merge仍只有四份parity文档冲突：保留双端追加历史，F37 sourceNotes合并双方，alias与不可变change ID并集，旧703条记录无同ID异文。产品表继续原generator。不能据主线iOS限定场景将Android F37或整项P8改为通过。

## P7公共持久性缺口与下一组修复

F59 R2独审发现1个P1、2个P2，禁止采用；R1/R2均只在树外。准确SDK37源码表明AtomicFile.finishWrite对部分sync/close/rename失败仅记日志，delete也忽略失败。F59目录fsync不能证明journal文件及rename成功，可能尚存READY却已开始POST；部分删除失败不能移除索引和额度，也不能继续泛称全部原件仍在。R3正在实现F59具名strict端口与精确删除/文案，尚未编译、执行或采用。

同类基础边界分组审计，静态发现不冒充故障复现：

| 公共组        | 已定位边界                                                                                           | 后续验收                                                           |
| ------------- | ---------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| 原操作及原件  | outbox的not-written到uncertain可能软写未落盘；用户重试同原ID可绕过只读检查，不能据此宣称自动新ID发送 | 原file/rename/fsync失败、冷恢复、原ID只读、旧ready不可追溯当未发送 |
| 语音原件      | 加密audio/metadata软写后可能删除唯一raw WAV；远端prepare/upload前状态同依赖                          | 原音频保留、真实写入/删除故障、停止/死亡/重开，不自动上传          |
| 通知变更      | 已有journal更新丢失时存在冷恢复新mutation ID路径                                                     | 保留首次journalBound缺失拒绝；逐路径故障/冷恢复/去重               |
| 迁移authority | candidate/chunks/generation/pointer有读回/锁及部分sidecar拒绝，但不等于file durability               | 严格发布/补偿/冷读/原件一致及24域                                  |
| 共享与授权    | consent revoke、EasyWork refresh/revoke保存有同类边界                                                | 原权限/令牌代次与撤销/刷新回执恢复，不能和模型重放混为一项         |

修复按文本、二进制、generation三类窄端口共用同一真实持久性机制；各功能继续保留具名writer、权限与目标边界。先完成F59/R3及公共原件/操作门槛，随后按组接入，不全局替换普通write，不触暂停的任务/项目业务。新严格写入也不能追溯证明旧READY/not-written未发送，既有UNKNOWN必须原ID核对。

完整迁移激活/24域、语音、未读、生命周期和P8/P9完整交互/界面继续。F59 Host无配置大小上限的语义保持，Android提案1GiB/file、4GiB/4096块/32行仍为未对齐容量，portable迁移未完成；更新F59共同断言移除历史20MiB固定门槛，不把提案计实现。任务/项目、F75及iOS A6暂停。

## 资源与交付

已按准确逐文件身份/摘要、无打开句柄及构建已结束的证据回收24297个自有Xcode/Android中间文件和重复APK，合计约1.08GB allocated；实际df前后另记，不把其他任务引起的空间变化归本任务。当前iOS Products/符号、SDK88/89、P9、Source25、R66所有数据/密钥及历史回执/intent/raw保持；85 App退役且86同摘要代表保留，旧85 bundle不再原地verify。没有清共享缓存、停止其他服务或删除其他树。

修正后全仓检查、当前新SDK/18 Native及真实owner/手机仍待验；主线/PR/源CI各按实际回执核对，草稿不等于已合并。过程原件在任务私有证据区，正式计划只保留范围/优先级/状态。
