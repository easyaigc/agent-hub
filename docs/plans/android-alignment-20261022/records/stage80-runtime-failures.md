# 阶段80：实际运行失败与后续门槛

本次严格SAF-v2共包R3封存f6d9db06通过，127.218秒；Root复核92材料、17工具实际wait0及899源码。App/Test与已安装5317字节一致，无重复安装。该构建是辅助证据，不是P7/P8产品通过。

| 场景            | 实际结果                                                                                                    | 保留与下一步                                                                                                                         |
| --------------- | ----------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------ |
| P7 EXPORT准备   | 原handoff操作fab4fe68的sink adb实际wait137；Host两流EOF、FD关闭，但缺远端helper回执，transportUnknown=true  | UNKNOWN不重放。尚未进入JUnit/原生界面、URI回调或provider字节验证。保留R66原件/keys/可能部分scope，另修支持的Test UID准备路径         |
| P8原窗口/长字段 | 原run6ae54f82实际JUnit 1项/1失败，首中文case在NativeP8MainPreferenceTest:188原decor归属检查失败；英文未执行 | inner wait0/rawComplete不是JUnit通过；outer wait1。不得把Context、Activity或后置窗口补成原表单证明；先采集只读类/type/token/对象身份 |
| 资源            | 两轮原VM各正常actualwait0、双流EOF；端口TIME_WAIT自然消退后可绑定                                           | 未停止其他模拟器/正式服务。Test缓存进程随VM退出不算自然退出；失败scope不清除                                                         |

P7原日志存在runas_app/app_process的SELinux拒绝，但尚无已证因果；原sink stderr只保留摘要，不能还原或猜测根因。后续需限量保存安全诊断，并使用已支持的Android组件/FD路径；不root、不改SELinux、不扩大shell接口。

Android 13可能将DecorContext建立在applicationContext上，ContextWrapper链不必保留Activity；这仍不是本次失败的已证原因。原Activity token与候选application窗口token可提供归属线索，不能替代准确focused decor及其AX窗口ID；subwindow token规则另行处理。先补元数据，再选择修复，不放宽现有断言。

预备阶段的目录缺失、错误Git cwd及执行服务容量故障都未触发Native/helper输入，原回执保留；已明确修正。P7真正handoff提交后的UNKNOWN与这些纯本地前置失败分开，不因环境恢复重放。

详见[阶段80源码/构建档案](stage80-main-and-owned-window.md)；功能、交互、界面及物理DEV继续分别关闭，任务/项目CRUD、F75、iOS A6暂停。

## 主线交付与分支收尾

阶段79/80经[PR131](https://git.51easyai.com/BCAI/agent-hub-app/pulls/131)正常合入main fda80542，源0ba8d903。源CI11320、主线CI11321的workspace/iOS均成功，公开同步11322成功；GitHub b06b819c的tree fb495d5b与Gitea完全匹配。两个已合并开发分支经准确SHA、无工作树使用和祖先关系核对后，均完成本地/远端普通删除；Stage81工作树和其他refs保留。该交付不改变P7 UNKNOWN或P8失败结论。
