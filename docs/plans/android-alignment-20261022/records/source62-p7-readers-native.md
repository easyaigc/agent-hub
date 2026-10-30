# Source62：选择与照片历史固定 reader Native

2026-10-30；基于 `169bbefd`，同SDK62未改生产源码或原测试。六个准确方法全部通过；[逐项证据](../parallel/sixty-second-stage-p7-readers-native.json)列402份实际调用。Root独立核对133个成员、2,422,485字节全部正文、九字段stat/nlink、二次metadata及tar footer。

## 本次通过

| 分组               | 限定断言与证据                                                                                                                                                                     |
| ------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SelectionReader 4  | 不存在与空节点不创建namespace/key；原journal空白及payload经既有FD认证保持；缺失嵌套key、未知记录、链接及迟到节点拒绝且不修复/遗漏；仅相机claim可审计，raw或其他scope孤件拒绝并保留 |
| PhotoLocalReader 2 | 不存在/空/历史偏好和permission-attempt只读认证；普通reader不得越界、未知原件/迟到sidecar/缺key拒绝，不生成回退key、不赋予系统权限                                                  |

每项method0、OK (1 test)、overall -1，完整before/after默认资料、APK/UID、boot和麦克风保持EE8F一致，实际锁释放。第一项仅12个空目录/0常规字节，不虚构密文证明。第三项源/目标复制的payload常规文件名、长度和SHA父层独立比较一致；本次原整方法的symlink步骤通过，旧硬链接EACCES属另一夹具，原失败保持。

## 原件与范围

相机数据只在该run的`owned-source-files`内，父层实际采到foreign scope的一字节03孤件及原empty scope；已被Native删除的FFD807原件、瞬态symlink/sidecar未独立捕获。历史permission的tag1首字节可观察，不是父层独立解密或key清单证明。解密/key拒绝语义由原生断言验证，没有启动Main、实际Provider、系统权限请求或媒体库查询。

当前SDK62已有17内存、5文件、5owner准入及6reader方法的限定结果；余8实际owner方法继续。单独准备run外相机、实际Provider与共享缓存IO边界，不以UI空闲推断IO退出。原声/typed ACK、跨PID、48MiB、完整系统/UI/AX/像素、物理/iOS真实DEV及24存储组仍开放。六棵小型原件/key暂留，实际owner退出和精确清理另核；无新SDK或构建缓存。任务/项目、iOS A6暂停，旧未知操作不查询或重放。
