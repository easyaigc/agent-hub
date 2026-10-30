# Source62：选择与照片实际 owner Native

2026-10-30；基于 `1698652a`，同SDK62未改Native源码或断言。七方法各执行一次：**五项限定通过、两项已知失败**。[逐项证据](../parallel/sixty-second-stage-p7-selection-owner-native.json)列721份Native阶段实际调用及58份资源只读预检，共779份；Root全读328成员/85,080字节，含失败原件，不把它们计为通过。

## 已通过五项

- 空选择freeze控制不被误作原始资料，导出后原picker实例交接保持。
- UI busy清除后，真实final-refresh IO仍阻止迁移，退出后允许继续。
- 实际owner/kernel freeze拒绝其他store IO，原journal惰性导出/激活且原picker交接。
- 自有FileProvider读取生成的原字节，consumed receipt与草稿附件在新激活后仍保持惰性原件。
- permission claim阻止准入；冻结时独立写入者被拒绝，照片偏好和尝试记录只进入惰性档案。

五项原method0、OK (1 test)、overall -1。registry启动及照片source是double，自有FileProvider仅生成输入；不计外部系统picker/Provider、OS权限、完整UI或真实DEV通过。

## 两项失败与单列待验

迟到重复registry方法取得唯一method -2/overall -1，外层`assertNull(ownerFailure)`在SelectionOwner:187失败；内层Throwable没有展开。Root读取实际锁定的AndroidX1.13.0 AAR及字节码，确认重复RAW dispatch可返回true但缓存结果而不再次调用callback，夹具对“成功分派”的假设不成立；这不是该轮内层cause的独立采集，正在补完整cause与真实触发边界。参考[AndroidX原始实现](https://android.googlesource.com/platform/frameworks/support/+/be28b01faa4c1821eb8b5de0bb5583b1681923e5/activity/activity/src/main/java/androidx/activity/result/ActivityResultRegistry.java)；本机确切1.13字节码摘要见验收索引。

不可取消照片query方法同样method -2，PhotoLocalOwner:104等待`source.entered`30秒超时，未进入预期迁移场景。Main实际active状态与受控query触发待核，不放宽timeout或closed/lease断言。两个旧run、原回执和完整私有树保持，下一次须新源码/包/lease，不重跑原操作。

第八个run外相机方法未执行。唯一scope公式已源核，但原方法在终态前删除raw和claim，缺独立原件窗口；新retaining候选受安全owner/IO收尾阻碍，仍关闭，不能靠空目录或终态推断原件保留成功。

## 保护与后续

七项完整before/after默认存储、APK/UID、boot、麦克风保持EE8F，实际设备锁均释放，unknown为0。私有全树stat/nlink/tar/footer、共享UI/资源准确原inode/UID/GID和终态空列表均核对；resources实际02700含setgid、GID20076，UIroot0700无setgid。终态没有残留cache文件正文样本；既定UI新regular预期GID10076只用于拒绝错误归属，不据此声称实际新文件group已采到。Controller删除job没有显式join，空列表/Native terminal不等于完整IO或kernel退出。

当前SDK62为17内存、5文件、5准入、6reader、5选择/照片owner限定通过；余3方法含两项失败和一项未执行。原声读回/typed ACK、共享IO/发现生命周期、跨PID、48MiB、完整OS/UI/物理/iOS真实DEV及24存储组仍开放。23小型fixture和声明alias待诊断/实际owner退出后按准确根清理，无新SDK构建缓存；临时classes.jar已回收，原AAR及必要字节码证据保留。任务/项目、iOS A6暂停，业务/model新增输入0，原未知操作不查询或重放。
