# Source66：迁移恢复入口与历史读取标志

Source65 真实首项迁移曾失败：普通恢复尾部尝试一次受控 fake read，随后 metadata/vault 的整客户端比较拒绝；原件、默认存储、权限、UID 和 boot 严格保持，完整74调用及释放已核。原失败、14成员/21,519字节原夹具及原密钥保留；没有实际网络业务输入，也不重放该原用例或原操作。

本次把夹具接到现有 `restoreForMigration()`，在实际受监督流程内核对恢复前后的 metadata/vault 原文；准确对象、退役确认及 coordinator 最后同步 IO 屏障保持。真实 quiesce 会把历史 `requiresReadTarget=false` 加强为 `true`，vault 仍保留旧值。产品图校验现在只对这个可选 Boolean 使用既有“缺省=false”语义，并允许单向加强；true 不能变 false/缺省，NULL/字符串拒绝，所有其他客户端字段仍深等值，两个原 JSON 不改。

新的首项完整 Native 用例会先执行16项真实 JSON 图边界：允许既有默认/单调形态，拒绝弱化、错误类型、内部自洽但跨记录的 runtime、证书、路由、reader 和未知字段差异。原始 fixture 的 false/null 种子没有改成新版形态来遮盖旧失败。合法旧存储 alias 选择与 vault 元数据差异仍是独立兼容缺口，本检查点不宣称已解决。

841规范/854扩展输入的组合 SDK 已完成，构建6.16秒、三 APK ZIP/V2与同一证书通过。新 B 将逐次升级 App/Test并核中间旧Test原路径/SHA/UID；每次 B/C 还完整读取旧Source65原树，只在比较时排除共享祖先与本地取证文件名，原树每个成员/字节/元数据均保留。API33 六项 warm 已各执行一次通过，首项同时执行16图边界；Root独审540份实际调用、18个完整tar，以及122份升级调用，原B与旧Source65失败原树严格保持、实际锁释放。三项冷链、完整系统/UI/物理DEV继续待验；iOS代码未改，任务/项目和iOS A6仍暂停。

公共摘要见[组合候选](../parallel/sixty-sixth-stage-migration-client-restore.json)，原始日志、SDK和设备原件留私有验收目录。Root与执行方严格区分源码、辅助检查和 Native 真实结果。

## 主线与资源收尾

[PR67](https://git.51easyai.com/BCAI/agent-hub-app/pulls/67) 的准确合并头双CI通过并实际合入`622c30f4`，19个stage13–31分支本地/远端引用及临时集成工作树均核对消失，原主工作区与活跃并行树保留。该树删除前占用约1.85 GiB，非可用空间增量证明。清理时两个Swift构建依赖嵌套检出的删除前Git状态未单独记录，不能事后证明其中无独有修改；这一核验缺口与正常无force删除回执一并保留。新Native夹具/原密钥仅用于后续冷读与精准清理，原件未删除。
