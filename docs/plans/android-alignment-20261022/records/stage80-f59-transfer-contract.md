# 阶段80：F59互传与迁移缺口

固定主线daf966bc核对：iOS独立互传提供手机选文件、上传、查询回执和复制路径。Host契约是具名Host文件传输域，未实现该面板的电脑文件浏览、下载、预览、分享或取消；不能为对齐而虚构接口。

iOS使用FileTransfers pending.dat/.data保留冷启动原件和journal，未知结果仅查询；EncryptedMigration.Payload与SessionStore.migrationPayload未包含这些F59原件/journal。迁移cache key存在不代表资料已迁移。Android F59仍计划/未实现，双端迁移缺口单列。

Android现有分帧存储/reader有21MiB及Int边界，不能直接宣称符合新Host maxFileBytes=null。通用小对象限制保留，先设计独立Long加密快照/FD窄接口、不可变输入/journal、完整target与session generation屏障，并加入迁移owner冻结/释放。不得另开绕过现有owner的明文或OS加密写入路径。

顺序：存储/owner/迁移合同审查 → 原件/unknown与冷恢复 → 现有4动作及旧有限Host/新null兼容 → 双端迁移和Native DEV。每步分别验收；渲染预算、聊天附件额度和迁移archive64MiB仍有各自边界，不能把null解释为无限资源。

本轮仅形成源码合同和窄接口候选，未接入生产路径或运行测试/设备，不更改F59 Android实施状态。任务/项目CRUD、F75与iOS A6暂停保持。
