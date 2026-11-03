# 阶段76：P9实际SystemUI验收

状态：R4、R5、R6三次不同新scope的完整Native均在点击前失败，未产出PNG；没有重试原操作。当前只有限核实两背景事实及工具链，完整P9未通过。构建与契约细节见[链路记录](stage76-p9-current-chain.md)。

全新API36 ARM64/PlayStore rev7隔离环境首两次启动在boot前CPU/mainloop hang、原launcher实际wait=-11/双EOF。按先前实际工作参数改用直接swiftshader、关闭Vulkan/WiFiPacketStream、明确自有data及2核后启动；第三次使用本轮新AVD，未复制旧资料，不能据此断言唯一根因。初始三包缺失、双boot身份及1080x2400/density420独立核对。

Root核SDK、三原startup文件、真实boot/UID/工具和初始状态后，只放行一次新op9cb50c30-88ac-4ce1-8cb4-3a9097d77ca9。bootstrap与producer原JUnit各OK(1 test)/terminal=-1，observer terminal=0，driver实际wait=1；所有原Host worker返回且无未决registry。Host wait不单独证明设备owner退出。无真实Host/供应商/网络或模型任务。

原部分AX3314B，SHA62e643be02fb5c0e8df1790c5cfe681f160b6689bfcd46d96ebebe972915a963，记录准确owned row内direct backgroundNormal/View，其原bounds下沿超出row。已有非交互属性均false，但actions实际为4/8/64/16908342。现有严格动作集只允许64/128/16908342且最多3项，因此在首次AX拒绝，clickBlocked=true，没有新截图或点击。截图不可用与部分AX不能变作成功证据。

官方Android16 r1 [View源码](https://android.googlesource.com/platform/frameworks/base/+/android-16.0.0_r1/core/java/android/view/View.java#11466)默认公布SELECT4/CLEAR_SELECTION8，且动作执行会改变selected/绘制/无障碍状态；实际镜像与源码tag尚未精确映射，AX class/ID不能证明具体runtime类。R5只为准确role增加18项新鲜语义事实、闭合Schema和policy v2；不执行这些动作、不泛化未知控件或放宽其他节点。独立源码审查及103+2纯向量、18组合成迟到变化通过；四项null/空串均合法但指纹变化，不把其误写为几何拒绝。Java getter、最终Native sameNode、PNG/点击仍须新SDK与新scope验收，旧部分AX不含这些新事实。

收尾：独立确认原bootstrap/producer/observer PID缺失、无包进程/active instrumentation及同UID/APK/boot后，仅撤回本次POST_NOTIFICATIONS grant，false与原flags完全恢复。保存最小原scope/partialAX/回执后，正常关闭VM，原launcher实际wait=0/双EOF，正式删除本次AVD/registry并核对端口释放。专用合成fixture Key随环境退役、不可恢复；原scope不可重放。旧R66、其他VM/手机及真实业务Key未触碰。

R5新op c63868df-ca38-4104-bdec-0923c5013600使用完全新AVD与已复核的新SDK/三startup文件，原bootstrap/producer各OK(1 test)/terminal=-1，observer terminal=0，driver实际退出1，60原Host worker均wait/双EOF/fsync。新partialAX3714B，SHA5f0ae0a86c6f5ece248ba8850084a9dd8f03dbe4f76f49f36424d273948abe81，已有Normal/index2/ordinal1的18项事实和clip；下一backgroundDimmed/index3/ordinal2原bounds同样超出row，在首次AX拒绝，clickBlocked=true。Dimmed节点与其语义事实未写入partial，不能从Normal补默认；仍无PNG/点击，不计P9通过。原bootstrap/observer未记录startTicks，缺失按缺失保存，不推造。

R5也独立确认三原PID消失/无active instrumentation、同boot/UID/APK后，单次撤回本轮通知grant并核对原false/flags；原launcher正常wait=0/双EOF、精确AVD/registry已正式回收。合成fixture Key退役不可恢复，无业务恢复依赖。初次工具返回复制产生零字节文件，不当证据；Root从官方own-child记录恢复原返回，没有为了补证重跑。R6拟重新采两种精确背景各自18项事实并核同row/window/fingerprint，原失败保持不变。

API37、低API、真实Host/供应商/OEM、实体设备、TalkBack和完整界面矩阵继续待验；P7/P8独立工作继续，任务项目管理仍暂停。

R6新op0072dcb7-9cd0-438f-a5e8-09fe760b7034在全新同镜像环境、当前源码与新SDK下执行一次；bootstrap/producer仍各OK(1 test)/terminal=-1，observer terminal=0，driver实际wait=1，60原Host worker均完成双流与wait。原AX4874B，SHA07825d699fc82aaab324f27686d58461aa6922ad93f8685b2436846163128b24，Normal和Dimmed两clip各有真实18项事实；下一expanded/FrameLayout/index4/ordinal3的原bounds超出选中行，首次AX拒绝。不能把两个背景的限定事实写成完整AX/截图/点击通过。

单独一次只读实时布局诊断无动作、无截图，完整lease元数据匹配1处，但无法按class/id唯一确认SystemUI行，因此拒绝且没有稳定布局结论；原窗口只在内存筛选，不长期保留其他行文字。该诊断不补作原scope证明。R6已独立核对原owner关闭及同UID/APK/boot，单次撤回通知grant恢复false/原flags，原launcher实际wait=0/双EOF，准确AVD/registry已正式删除，合成fixture Key退役不可恢复。全流程失败与不可变ledger保持。

下一步：SystemUI内容容器与可见裁剪的关系继续单列；本轮不放宽任意容器。先交付已验证的工具范围，再推进P7已有惰性资料合并、实际Main界面、语音/未读/生命周期；不等待此项，也不升级完整P9。
