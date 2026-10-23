# 移动端设计基线

`tokens.json` 保存 A0 从当前 iOS 源码核对的产品颜色与几何。系统语义色仍须按实际浅／深色截图核对；A2 生成两端资源，在此之前它不是应用运行时已接入的公共资源。

`ios-baseline/index.json` 由完整 A0 模拟器报告导出，关联合成输入的测试、状态和图片摘要。当前 89 张合成画面已逐组实际查看，核对范围和尚缺项见 `ios-baseline/review.json`。`reviewed-collected-states` 仅表示已采集的画面已核对；完整 Markdown、放大字体、EasyWork 等后续状态仍须补采，不表示 Android 视觉通过。

截图保持 xcresult 导出的原始字节。索引同时记录 PNG 编码尺寸、EXIF 方向和显示尺寸；横屏截图可能以竖屏像素加 EXIF 旋转保存，比对时先应用 EXIF 方向，不能只按编码宽高判断屏幕方向。

输入性能 fixture 的回最新截图包含测试专用控制按钮，索引标为 `diagnostic-only`，仅核对滚动／草稿断言，不作为 Android 产品布局参考。其他 fixture 如含 DEV 诊断标签，同样在视觉核对记录里明确限定参考区域；不修改图片隐藏产品错误。

复现：

```sh
IOS_SIMULATOR_ID=<dedicated-simulator> node scripts/mobile/ios-baseline.mjs
node scripts/mobile/complete-ios-baseline.mjs .local/mobile-acceptance/A0/<run>/report.json
node scripts/mobile/export-ios-baseline.mjs .local/mobile-acceptance/A0/<complete-run>/report.json
```

这里仅保存合成数据。真实聊天、用户附件、连接密钥、设备账号和日志保留在忽略的私有证据目录。
