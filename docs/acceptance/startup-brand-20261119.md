# 启动 Logo 与呼吸效果（2026-11-19）

范围：F01／F47，UI01／UI10，S01／S11／S18 的启动外观。复用已有 App Logo，系统启动屏静态显示品牌，本地恢复阶段显示轻微缩放与光晕；读取完成立即展示原主界面，不增加品牌最短展示时间。系统减少动态效果时保留静态品牌及恢复文字，非活跃时停止装饰动画。

实现位于 `apps/ios/Sources/CodexManageApp.swift`、`apps/ios/Resources/LaunchScreen.storyboard` 与 `StartupLogo.imageset`，Debug／Release 均配置系统启动 storyboard。未改 Host／wire、存储格式、配对与外部文件入口、不可变输入或恢复策略。仅 Debug 显式 `-startup-brand-acceptance` 将已恢复快照延迟发布 12 秒供视觉验收；常规启动和 Release 没有该等待。

## 修订2：收小品牌元素

用户看过首版实际截图后反馈图标和文字偏大。修订2将 Logo 从 112 pt 收为 80 pt，标题由 22 pt 圆体半粗改为系统正文 medium（默认 17 pt），间距由 24 pt 改为 18 pt；系统静态启动屏同步。光晕从 224 pt 收为 168 pt、颜色透明度减半，减弱阴影；呼吸单程改为 2.2 秒、Logo 最大缩放 1.025。恢复／输入路径和正常启动的等待规则保持。

本修订独立 iPhone 17 Pro／iOS 26.0 arm64 模拟器的构建及原生 XCTest 整例通过：1 通过、0 失败、0 跳过，28.075 秒。沿用原测试，检查恢复品牌标题／文案、恢复结束进入首页、普通冷启动及重启，未新增测试代码。浅色与深色的恢复页原始截图已由主会话查看，缩小的 Logo、系统字体标题与底部真实恢复提示可见；不将系统启动缩放过渡截图算作恢复页通过。`pnpm check:mobile-parity` 通过。

独立资源及证据位于原任务目录下 `refined-v2`，包括 `light.png`、`dark.png`、`summary.json`、`test.xcresult`、`source-sha256.txt`、`build-sha256.txt` 与原始日志，不覆盖首版记录。主会话独立读取官方摘要并核对两份产品源码 SHA-256 仍匹配；当前模拟器可执行文件 SHA-256 为 `bbd25bd078efcdea6c411596b560bd8d8fe097502b3bc7ff47e16f2a82f110e1`（不是安装包摘要）。减少动态效果／非活跃停动、Android、物理手机和正式安装仍维持原未验／未部署边界。

修订2模拟器已删除并独立核对登记不存在，测试 Agent 已关闭。测试 Agent 回报自动审批拒绝其目录删除命令，理由为不允许的 `rm -f` 形式；未换用等价删除命令绕过。主会话通过 Xcode 正式 `clean` 入口成功回收专用编译产物，`DerivedData/Build` 为 0 B；专用 DerivedData 从 521 MiB 降至 241 MiB，剩余索引、SDK／模块缓存及包输入保留在 `refined-v2/DerivedData`，`clean.log` 留存。此缓存无后续开发依赖，收尾责任仍由当前主会话承担；下一复核点为本候选交付收尾，须在存在获准的正式缓存回收入口后再回收，不将保留写成已删除。截图和原始证据保留至本候选审查／集成。

## Gitea 主线组合交付

以 Gitea `BCAI/agent-hub-app/main` 的 `8ae286d1ad81d22849591ea0f31d786eb64a38de` 为基准整合本次启动外观。保留主线新增的 UIKit 导入、EasyWork DEV 预览入口、其他测试和 Stage64 测试 Host 的原系统启动设置；仅主 App 的 Debug／Release 改用品牌 storyboard。品牌视图、storyboard 与 Logo 字节同已确认的修订2，整份 App 组合源码及工程文件按新基准重新验证，旧包结果不直接升级为主线组合通过。

组合候选在独立 iPhone 17／iOS 26.0 arm64 模拟器通过原生整例：1 通过、0 失败、0 跳过，37.249 秒，覆盖启动品牌、恢复后首页与普通重启。官方摘要与真实恢复页截图保存在本机私有交付证据目录，主会话已独立核对通过摘要、截图、整份跟踪源码差异及工程／Swift 源 SHA；本轮模拟器可执行 SHA-256 为 `c4879b3936cdc3c417fc9ee16361b819e2ccb136f25d2af102add0fa1bfd5aa0`。首次构建的 Java 17 发现失败及后续工具恢复记录单列，不改记为通过。

环境修复仅使用进程级 Java 17／Kotlin Native 路径和任务独立 Gradle 缓存，通过正式 Gradle／Xcode 从本次组合构建 MobileCore，没有用旧源码框架替代。独立模拟器、DerivedData、任务 Gradle 缓存及未完成的 JDK 下载已回收；必要日志、原始结果、截图与指纹留存于私有 `delivery/evidence`。交付工作树及依赖暂供提交校验使用，完成远端核对后由主会话通过 Git 正式生命周期入口收尾。

开发现场另外四份计划文件不纳入启动页提交。开始时它们与基准主线相同；交付过程中并行维护已更新至 R292，按最新快照保留原内容、指纹与差异，不以最初 R291 快照覆盖后续更新。

## 首版验收与交付边界（历史）

独立本机测试 Agent 执行原生 iOS 模拟器构建与 XCTest/XCUIAutomation；要求使用 `gpt-6.1-terra / medium`。分派接口未提供速度参数，也未返回实际模型／档位运行记录，请求设置不冒充运行核验。

本机独立 iPhone 17 Pro／iOS 26.0 arm64 模拟器，独立 bundle 的实际结果如下：

| 范围                         | 结果                                                                                                                          |
| ---------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| iOS 构建                     | 通过；使用 `pnpm build:ios` 的等价 Xcode 命令，专用 DerivedData 与 bundle，避免覆盖共享编译现场。                             |
| 系统启动屏                   | 已编译进 App；独立读取包内 Info.plist，确认 `UILaunchStoryboardName=LaunchScreen` 及 storyboardc 存在。                       |
| 浅色／深色恢复页             | 实际截图已查看，圆角 Logo、标题、光晕与底部恢复提示可见。系统启动过渡帧单列，不作为恢复页通过。                               |
| 呼吸效果                     | 真实 XCTest 四帧独立复核，Logo 明亮区域宽度为 338／336／346／346 px，约 3% 的轻微变化；测量排除标题、进度转圈及系统启动过渡。 |
| 品牌页结束、普通冷启动与重启 | 原生 XCTest 整例 1 通过、0 失败、0 跳过，27.973 秒；核对品牌标题消失、App 仍前台、原首页添加按钮可见。                        |
| 双端规范检查                 | 最终 `pnpm check:mobile-parity` 通过，含 6 项测试及公共规则／契约向量。未执行整个根 `pnpm check`。                            |
| 减少动态效果／非活跃停动     | 已实现；本次未实际切换系统 Reduce Motion 开关或覆盖后台恢复，不计原生验收通过。                                               |

首次测试构建因 x86_64 链接缺失失败，切换本机原生 arm64 目标后恢复；第一次 arm64 测试因启动容器的辅助功能选择器及截图后时序断言失败，改用实际可见标题、恢复文案与原首页按钮后通过。原失败日志与最终官方 xcresult 摘要分别保留，不改写原结果。主会话复核测试源与产品源指纹仍匹配；最终模拟器可执行文件 SHA-256 为 `95b473d0bbedb0ffa9154a52fbdb5f7d4af228ea458ec79ebb677a121efa2e50`，此值不是安装包摘要。

最小私有证据包括 `evidence/xcresult-summary.json`、`final.xcresult`、`source-sha256.txt`、`build-sha256.txt`、`attachments/manifest.json`、`motion-review.json`、`light-2.png`、`dark-restoration-2.png` 及原始日志，位于下述任务目录。Android 对应外观待对齐，未新增 Android／物理 iPhone／真实桌面／模型任务通过，未安装正式版；未变更 EasyWork 对齐父项状态及 A6 暂停范围。原有其他任务的计划台账改动保留。

## 资源

本任务测试资源与最小证据限定在本机私有 `.local/startup-brand-20261119`；独立模拟器及两个 DerivedData 已回收，主会话核对目录与模拟器登记已移除，测试 Agent 已关闭。保留源码与必要截图、指纹、结果摘要至本候选审查／集成。未创建工作树、开发分支或独立聊天，未提交、推送、安装正式版。
