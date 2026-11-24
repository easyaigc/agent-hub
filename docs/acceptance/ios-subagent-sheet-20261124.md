# iOS 子任务详情关闭与手动 CI（2026-11-24）

范围：F20 的 iOS 子任务详情关闭、重新打开与嵌套返回。Android 的生产实现与验收结论不受本次改动影响。全项目当前状态仍由完整功能清单维护。

原 Gitea CI run 15276 的 iOS job 32965 在 testLongChildDetailOpensClosesAndReopensWithOriginalText 中失败：点击完成的事件已合成，随后在原 10 秒期限内仍能找到详情 ScrollView。原文显示断言与 Xcode 构建此前已通过。未修改的本机源码在独立 iPhone 17 Pro / iOS 26.0 上执行一次原案例通过，因此不能把这次偶发失败唯一归因于环境或 dismiss 的内部实现。

详情原先通过环境 dismiss 请求关闭，但呈现选择由外部 SubagentHistoryPresentation 持有，详情内还可嵌套另一层同类呈现。本次让每层完成按钮和失效退出调用该层呈现者提供的关闭动作，直接清除对应 selection，并核对原呈现标识，避免关闭动作依赖环境层级。目标切换、请求取消与原文内容保持原有规则。

原关闭十秒断言保留，增加重新打开后的第二次关闭、正文卸载和原聊天可操作断言。新增合成的两层子任务详情，用实际原生界面验证关闭子层保留父层、再次打开子层以及最终返回聊天。原结构化工具与原生长按复制案例一同执行。

按用户明确选择，完整 CI 仅保留 workflow_dispatch 手动入口，Gitea 和 GitHub 的 push/pull_request 不再自动启动 workspace 或 iOS 构建与测试。独立 sync-github 工作流的主线 push、定时补查和手动入口继续保留。

## 本次验证

- 未修改本机基线：iPhone 17 Pro / iOS 26.0，原关闭案例执行一次通过，原 CI 偶发超时没有在该次本机运行复现。
- 候选初次准备：新 Gradle 缓存下载触发构建期限，零测试执行；复用已验证下载缓存后 `pnpm build:ios` 成功。
- 新增嵌套案例首轮：父子层有两个同标识关闭按钮，测试因多匹配在点击前失败；定位修正为当前唯一可交互按钮，未改变产品可见文案或关闭断言。
- 组合构建与测试尝试：首次测试包编译消耗总体预算，首例在关闭后重开过程中被中断，结果包不完整；不登记为用例通过或失败。
- 最终准备：Xcode 27.0（27A266a）`build-for-testing` 成功，用时 29.951 秒；保持同一测试产物执行 `test-without-building`，每项仍使用 180 秒 XCTest 执行期限与 420 秒进程期限。
- 最终原生结果：独占 iPhone 16 Pro Max / iOS 26.0（23A343），官方逐项与合并结果均核对，4 passed、0 failed、0 skipped、0 expected failures。四项方法与仓库子任务夹具列表一致，主会话独立复读合并 xcresult 并核对源码、App 与 Test 二进制 SHA-256。
- 完整检查首轮在功能来源覆盖记录处停止；补齐 F20 当前源码、测试与夹具指纹后重新执行，原失败记录保留。
- 原候选 `a1e1512b` 完整 `pnpm check`：通过，耗时 17 分 23 秒。
- 集成主线 `a7796bb9` 后完整 `pnpm check`：通过。
- CI 触发配置解析通过：完整 CI 只有 `workflow_dispatch`，workspace/iOS 手动任务保留；独立 GitHub 同步仍保留 push、schedule 与 workflow_dispatch。本轮未手动触发云端完整 CI。

- `testLongChildDetailOpensClosesAndReopensWithOriginalText`：通过。
- `testClosingNestedChildDetailPreservesParentPresentation`：通过。
- `testStructuredChildTimelineToolGroupAndCommandOutput`：通过。
- `testNativeLongPressSelectAndCopyChildDetail`：通过。

## 输入与边界

- `SubagentHistory.swift` SHA-256：`12cf55455bbc940ea30dbe034761c76ca70af1d46442a7bd919919aa41a635c9`。
- `EasyWorkChatManagementUITests.swift` SHA-256：`0d6441dcab86a1976b239d54e2480cb99ae375d9418f0fc63d0b5f4ea1ab8691`。
- App 二进制 SHA-256：`f1d4275c81001989559545ceec866269daf58c83fa0b1daebce8d44fe548c24a`。
- Test 二进制 SHA-256：`6e57558a6447b7e4bc0af106a1e7ccefdc74b7aa03706283a9b517487c9755f1`。

原始 xcresult、逐项结果、构建/测试日志与资源回执保存在本机私有验收目录。模拟器回归仅验证本次公共呈现行为，不替代真实桌面模型、网络、物理手机或完整 F20 验收；原有 iOS 用户确认与 Android 结论保留。当前功能状态只回填[唯一完整功能清单](../plans/android-ios-alignment-20261107/progress.md)。
