# Android 迁移检查点主线交付

2026-10-28：PR [#42](https://git.51easyai.com/BCAI/agent-hub-app/pulls/42)已合并，含P7-5d至5k。候选`8666a29dfefcaf65aa77671d85f6291968951b93`，主线`d5b85fede32d3bc290daaa7d134dcea0b945d927`；合并树与候选树完全一致，原阶段提交保留。原`codex/android-p7-migration-20261028`、`codex/android-p7-complete-20261028`本地和远端引用均已清理，工作树外恢复bundle已验证；继续从主线使用`codex/android-p7-binary-20261028`。

准确候选的Gitea工作空间与iOS两个PR检查均success（run10538，jobs25854/25855）。先前f0cc0f9c的CI仅留作旧指纹证据；纳入Windows PR41后重新核验当前候选。集成时仅功能清单出现追加记录冲突，按ID保留双方387条记录，未覆盖Windows安装/DACL修复；Android/core/protocol实际构建输入与5k原生通过包的源码集合指纹保持一致。

5k最终候选在API26和新手机API33共52辅助、44份默认11字段保护通过，包括缩略图/EXIF/配额脚本修订与许可交互；旧脚本失败和旧候选结果按各自指纹保留。这里没有把fixture、构建、旧包或缓存读取升级为完整真实模型迁移通过。原PR原生附件工具不支持本私有forge URL，项目文档保留实际可访问PR链接。

本次合并后完整P7继续开放：录音/二进制、各子存储/Provider/通知全类型、配额、系统迁移UI与冷会话切换；语音、未读与生命周期随后逐项推进。模型确认累计221/P4205、另1次未知提交封存只读，本次没有新模型输入或旧操作重放。任务和项目业务及iOS A6继续暂缓，环境待验单列；[计划总表](progress.md)保持唯一进度入口。
