> 本文保留初始候选的设计与历史限定结果。当前组合以主线领域契约和功能清单为准；旧接收端、原生或现场记录不自动适用于新组合，A6 人工暂停继续保持。

# 外部文件来源验收夹具

这是独立的测试 App，只生成本轮合成文件，让 Agent Hub 通过真实系统文件选择器从另一个沙箱导入。它不链接产品模块，不连接 Host 或 Relay，文件模式不申请共享容器、通知、照片或麦克风权限。

使用独立的验收 Bundle ID 和开发签名构建 `AcceptanceFileSource.xcodeproj`。通过 CoreDevice 启动时传入 `--easywork-file-fixture-case <fresh-lowercase-UUID>`；同一个 case 的目录或回执存在时拒绝覆盖。`Documents/fixture-<UUID>.json` 记录准确 Bundle ID、两份文件的路径、大小及 SHA-256，仅作为私有验收资料保存。

验收必须另外证明产品 App 在系统选择前不能直接读取这些文件、实际系统来源和所选文件正确、原生产导入流程及电脑端字节一致。夹具编译或生成文件不能算功能通过；不读取其他已安装 App 的文件，不把此来源推广为 iCloud 或其他云提供商通过。

可选照片夹具模式：通过 `--easywork-photo-fixture-case <fresh-lowercase-UUID>` 准备六张带独立双色图案和case标记的合成PNG。必须实际点击“添加6张合成验收图片”并走系统仅添加照片授权；仅请求Photos addOnly，不读取已有照片、不申请完整相册访问。一次事务只创建这六张新资产，原PNG/摘要、系统回执及新资产ID保存在私有case目录；已有case拒绝覆盖、不自动重放。夹具准备不算产品图片选择通过，六张新增测试资产的实际选择、回调、移除及测试资产清理另行记录。
