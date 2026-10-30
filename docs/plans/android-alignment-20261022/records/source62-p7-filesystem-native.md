# Source62：选择回执存储与当前根目录清单 Native

2026-10-30；基于 `57d36d77`，当前冻结SDK62未变。[逐项证据](../parallel/sixty-second-stage-p7-filesystem-native.json)记录五项原生主体通过、四项完成全部采集、一项后置待验。Root独立读完301份实际调用；四个完整run共47成员、24,461字节，九字段stat、nlink、全部tar正文及footer均已核对。

## 已通过

- 选择回执：新存储发布后迟到回执进入当前代次，旧密文字节保持，普通已退役写入拒绝；重复激活不覆盖原回执。
- 原件保护：未决`.bak`/`.new`拒绝且保持密文；删除本fixture既有key后拒绝写入，不创建替代key或重放审计。
- 当前根清单：未知空/非空节点整例拒绝，不遗漏原目的文件；迟到未知节点及伪装staging前缀拒绝发布，保留原目的字节和迟到原件。

每项只执行一次精确class#method，取得唯一method0、OK (1 test)、overall -1；四项完整before/after默认资料、APK/UID、boot与麦克风状态不变。run外只读检查直属名及canonical祖先；当前framework父级700/771、私有目录700和常规文件600如实记录，不修改权限。原目的文件实际600，未使用预定义封闭644例外。

## 单列待验

`pendingRootPointerIsRefusedBeforeAnyAtomicRecoveryDuringRegistryAudit`原生主体通过；之后SDK只读查询exit255，stdout为3字节`26\n`，stderr为空。后置完整保护快照、该run的stat/tar未采集，不计整项通过。原33份回执保留，没有重跑Native或补造成功。后续只能绑定原run做独立只读核对，不重放原操作。

五项Admission会启动实际Main并构造共享`cache/migration-ui-v1`；不能放入无Main文件组，正在单独建立精确缓存与owner边界。Source62剩余20个文件/owner方法未关闭，其中1项主体过而后置待验。17项内存Native结果保持原范围。

## 证据与范围

父层取得完整终态密文和限定拒绝残留；密钥行为由Native断言验证，没有父层独立解密，也未采瞬态已删除sidecar。当前512MiB不是48MiB，未证明跨PID、OS/Provider、完整UI/AX/像素、物理/iOS真实DEV或完整P7。五棵小型fixture及本次唯一alias暂留，待实际owner退出与原run只读核对后精确回收；原voice15/cold109、旧key及5566未知现场保持。任务/项目、iOS A6暂停，Host/模型新增输入为0。

## 后续独立只读补证

[下一阶段](source62-p7-admission-native.md)以新lease只读同一原run，0新增Native，66份实际调用完成11成员/5,165字节和EE8F前后保护。限定指针门槛现已补证；原33份回执、exit255及旧suite保持，未重放原方法。
