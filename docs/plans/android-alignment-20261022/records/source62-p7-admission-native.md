# Source62：实际 owner 的选择回调准入 Native

2026-10-30；基于 `7a861a45`，同一SDK62未改生产源码或测试断言。[逐项证据](../parallel/sixty-second-stage-p7-admission-native.json)记录五项实际Main/registry原生方法全部通过。Root独立核对415份原调用、156个成员/21,003字节完整stat/tar/footer。平台launch为double，不是系统Picker或Provider通过。

## 本次边界

| 场景              | 原生结果                                                           |
| ----------------- | ------------------------------------------------------------------ |
| 来源数量超过上限  | 原lease失效；第一原请求保持pending，迟到替换不生效，不伪造归档     |
| 精确UTF-8字节超限 | 不截断、不同步抛给回调；原请求未归档且不能退休                     |
| 捕获契约失败      | 失效先于捕获失败，原请求保持pending，不恢复旧权限                  |
| 原Intent快照失败  | 记录准确uncaptured requestId，后来的替换不能冒充原文               |
| 原审计写入待恢复  | 同一次原写入对账持久化第一来源，迟到替换不覆盖，不重新调用Provider |

五项各自唯一method0、OK (1 test)、overall -1；完整before/after默认存储、APK/UID、boot、麦克风不变，实际设备锁释放。前四项pending是预期的安全拒绝，不代表这些原始URI已可持久归档或恢复完成。`kernelLeasesReleased`来自Native断言，父层未独立证明kernel退出，也未独立采集完整原回调向量。

## 共享缓存与原指针补证

另53份只读调用确认framework cache目录为02771（含setgid）、UID10076/GID20076；准确`cache/migration-ui-v1`为0700、同UID/GID，直属名为空。每项执行前后分别绑定真实祖先、原目录inode/device及空列表，只观察准确根，不读其他缓存子体；已存在目录的chmod只称权限幂等，不称ctime或全metadata未变。

旧指针方法的原Native主体通过、后置SDK读取exit255记录保持。新lease只读同一个原run，0新增Native，66份调用核对11成员/5,165字节、未恢复的原`.bak`及完整EE8F前后保护。此项现以原Native指纹加独立只读补证关闭该限定门槛；旧suite不改、不重放。阶段共534份调用，新增Native仍只有五项。

## 后续

当前SDK62已有17内存、5文件与5准入方法的限定证据；余14文件/owner方法继续，包括六项reader。原声WAV读回/typed ACK清理、跨PID、48MiB、OS/权限/实际Provider、完整UI/AX/像素、物理/iOS真实DEV和全部24存储组仍未关闭。五棵新增小fixture与此前五棵原件暂留，须核实际owner退出、源定义精确alias和独立采集后按根逐项回收；无新构建缓存。任务/项目、iOS A6暂停，原业务未知操作不查询或重放。
