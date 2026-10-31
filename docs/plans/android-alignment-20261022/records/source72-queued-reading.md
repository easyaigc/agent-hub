# Source72：真实 IO 排队阅读跨本机生命周期

2026-11-01，阶段46；接续已推送 `c9dc97d7`。本次一个Native组件方法限定通过，不是实际Activity前后台、完整UI或真实Host／模型验收。

## 功能、交互与界面对照

对照iOS的阅读归属和未读未知状态：已进入真实存储锁队列的旧正文写入，在本机生命周期后台→前台换代后不得消费原未读；新当前正文可记已读，独立未见问题仍未读；迟到completed／question不能复活正文或覆盖原scope。此次调用的是生产Store和真实IO队列，在Main上操作其生命周期状态，没有驱动系统后台、屏幕旋转或页面导航，不记实际界面通过。

准确方法为 `NativePhoneReadingStoreEpochTest#actualQueuedVisibleBodyCannotReadAfterBackgroundAndForeground`。Source72两个APK指纹保持，未重建／重装／清数据、没有Host或模型输入。选定方法start1→success0，runner整体正常-1；独立父worker实际退出0，7.278秒。

## 原件与收尾核对

主统筹独立读回全部74份实际捕获（66设备／8本机）的完整双流、EOF／hash、实际argv／PID／退出；包、UID、boot与权限保持。原Cold62的全部字节、每成员九stat、完整tar前后及原基线一致。

该用例在空环境首次通过Android getter创建no_backup，并只新增一个随机reading-store-epoch证据根。已预先准入这个变化，未创建mobile-v1或其他默认族；新家族恰13成员，完整原ledger、原cipher、result及cleanup-01…08全部独立读回。result的两个原件SHA匹配，四步清理的intent／completed与状态递进均齐全；执行ledger／目录／合成alias已由用例自身清理，八份记录报告owned jobs/gates已join。父没有删除或重建密钥，没有重跑原方法。

`storageRetired`仅表示writer shutdown请求返回，不证明所有物理executor线程退出；结果原有 `parentCaptured=false`、`interactiveUiProof=false`保持原字节，主统筹另留独立核对，不改原证明。保留原件是seed基线，不能当成后续已合法修改的最终执行ledger，也不声称已重新解密已移除alias的密文。

主统筹核对摘要：`80ca6bb5d9d3c83b947121f3b916a5b807dd72c451598763c13cb3087aebf5af`。下一阶段P8／P9需验证已有默认目录原件在新连接／首次Main初始化后仍保持，因此这一个准确13成员夹具与Cold62暂保留，原记录给出的6小时复核点继续登记；两组保持门槛关闭且树外证据确认后再准确回收，不保留为产品数据。

## 当前剩余项

下一步先逐方法验收P8四项保存连接（含两个双fixture迟到方法），再处理P9十项原导航claim／偏好撤销；P9旧fixture的owner收尾缺口已有树外源码提案，尚未应用／编译／运行。完整系统前后台、通知角标、48MiB、物理手机、真实桌面模型、24组完整迁移和iOS复验继续待验。任务／项目、项目Git原生UI和iOS A6仍暂停；封存未知操作不查询、不重放；旧失败已发布代次仅留只读恢复待验，不再次激活或重放。
