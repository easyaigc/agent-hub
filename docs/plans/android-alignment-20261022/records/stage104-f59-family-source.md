# 阶段104：F59 旧代完整源库存源码

当前单child版本 f38b8be1 的940输入SDK及八项Native已通过并在阶段103登记。以下是后续新源码，不继承旧八项为本轮通过。

## 源实现

新增 NativeMigrationComputerFileFamilySnapshot，按实际root库存纳入原root/null及全部canonical保留generation，包括未发布目录。每代独立复用已审的真实F59 snapshot/graph/既有key-only解码，不选赢家、不将目录存在解释成publication lineage或恢复权限。未知peer、缺失generation、sidecar/未完成写、孤立原件、缺key等拒绝整族，保持原件与UNKNOWN/原receipt。

共享实际Session/raw Frozen/root/operation/generation以及受限callback生存期；目录/父目录持有FD和前后身份校验保持。新增整体journal/chunk/binary/ciphertext额度，超限拒绝；每个member内部先有界解码，峰值最多已接受源加一个有界member，不是heap或文件系统硬quota。空child不得创建，close未知仍持有原owner，不重试或解冻。

NativeSessionStorage仅追加窄family入口，按准确旧字节应用最小补丁；原current-generation入口继续保留。未接Export/Activation、新封包或UI，不放宽有F59整包拒绝。

## 七项新方法与独审修正

新方法覆盖三代同名原件、旧root孤块、无retained generation的peer、真实旧目录替换、整体journal限额、消费者异常/FD/过期回调、全族空child/原owner失效。夹具用真实普通local publication及MobileSession quiesce，唯一私有namespace和原key集合保护，没有Main/Host/模型或默认prefs。

Root补齐两项P2：同ID/target/name三代使用不同payload/hash，原root为准确COMPLETED receipt、两代为UNKNOWN/null receipt，逐代核对完整row与真实bytes；失效owner场景要求内层断言完成标志，防止进入消费者前失败假通过。普通producer重开沿用阶段102已验证修复，strict snapshot reader仍existing-only。七方法均尚未编译或执行。

## 独立 SDK purpose

新增 p7-f59-family-source-v1，942输入、125覆盖、124固定after+self，仅两APK assemble以及原八方法加新七方法的15项资格。旧224个非terminal AST及main保持，原940/123/8和所有旧purpose不扩围。候选projection不是实际guard，最终self/HEAD/review需Root在实际采用后另封存。

独审指出repo self与实际执行capture可能分离；Root仅在新family guard加overlay self等于当前执行__file__摘要，既有旧函数保持。第19个反例验证“repo与执行工具不同但各自pin均合法”必须拒绝，19项仍待实际执行；不是SDK或Native通过。

## 后续与资源

先完成新编译、19合同反例/实际942 guard、sealed SDK和新boot/现装包保护下的15项Native；再接Long inner payload与窄认证边界、目的端惰性原件、24域/iOS及完整Main交互、方向/字号/AX/手机。并行inner codec只是无加密/无FileAPI候选，其首I/O失败保持边界仍在独审修复，不能当认证容器或迁移通过。

前一阶段已回收替代的SDK101两包及无损压缩静态合成cap原始流，合计净81,940,480已分配字节；旧失败源码/intent/wait/raw与gzip恢复映射保留，旧SDK101不再可整包原位复核。保留当前SDK102及一个SDK97回退包、R66资料；不退旧资源charge，不归因全盘空间变化。任务/项目、F75、iOS A6暂停，未知操作不重放。
