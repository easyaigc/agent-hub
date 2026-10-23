# 文件互传

状态：第一版已实现手机向 Codex Host 上传普通文件；电脑向手机的独立传输、EasyWork 接入和后台上传尚未实现。聊天附件的既有上传／下载继续使用原入口。真实验收结果见 文件互传验收（本地文件 `docs/acceptance/file-transfers.md`）。

## 电脑之间的统一发送 CLI

`skills/codex-host-connect/scripts/transfer-host.mjs` 提供 `send`、`status`、`resume`，复用 Codex Host 的 `files:upload` 接收协议，不提交模型任务，也不要求聊天。目标连接来自当前用户私有 `~/.config/agent-hub/hosts.json`；Windows 使用原生 `%USERPROFILE%`，不使用 WSL HOME。脚本可独立安装到同目录，无运行依赖。跨电脑传输及通知仍须有人类用户对相应目标和任务的明确授权。

```bash
node /path/to/transfer-host.mjs send --host-id HOST_ID --operation-id LOWERCASE_UUID --file /path/to/archive.zip
node /path/to/transfer-host.mjs status --host-id HOST_ID --operation-id SAME_UUID
node /path/to/transfer-host.mjs resume --host-id HOST_ID --operation-id SAME_UUID
```

每次操作验证 HTTPS 证书、鉴权和真实 Agent／Host／workspace／runtime；Codex 账号变化不改变机器目标。首次文件写请求前，将不可变目标、清单和原字节持久保存到私有 `file-transfers/<UUID>/`。原文件修改不影响已保存内容；同 ID 的不同文件或目标拒绝。macOS 私有目录为 0700、文件为 0600；Windows 校验当前用户原生 DACL，保存文件使用私有 DACL。密钥不作为参数或回执输出，不接受 HTTP 或重定向。

登记清单后发送原始二进制正文，并逐字段核对回执。重复 `send` 只查询，即使原登记返回丢失且查询为 404，也不自动重新写入；`status` 始终只读远端。显式 `resume` 先查询，再用相同 ID 和原快照继续登记或上传；已完成操作始终返回原回执。传输中断、HTTP 错误或结果未知返回退出码 2 并保留快照，完成返回 0，本地配置、身份或完整性检查失败返回 1。确认 completed 后删除本地内容快照，保留清单及回执，不删除目标文件。恢复上传发送完整原文件，接收端按 ID 去重；不提供分块续传。

本地真实接收器边界回归与各机器的真实 HTTPS、磁盘核对、模型读取和部署结果分别记录，不把辅助检查或历史手机证明写为本次跨机通过。`contact-host.mjs` 继续负责具名聊天协调，`--text-file` 仍只表示消息正文。

## 手机入口

打开侧栏，在当前电脑的“新聊天”下方点击“文件互传”。页面固定绑定打开时的连接，显示电脑名称、接收目录、“选择文件上传”、待完成记录及已上传记录。文件选择器支持多选，按顺序上传，每个文件须非空且不超过 20 MiB。上传完成后可复制电脑路径。

上传不要求选择项目或聊天，不创建聊天、不提交模型任务。电脑默认接收目录为当前用户的 `Downloads/Agent Hub`；Host 可用 `HOST_AGENT_TRANSFER_DIR` 设置固定接收目录，手机不能提供任意目录。保存名称加入完整传输 UUID，保留扩展名，既有文件不被覆盖。

旧 Host 和尚未实现该能力的 Agent 仍显示入口，页面说明需要更新或尚未支持；不以聊天附件能力冒充独立文件互传。

## 完整性与恢复

手机先复制文件到本地受保护存储，保存不可变清单、稳定传输 ID 和目标。记录使用已有 Keychain 缓存密钥加密，按完整 Agent／账户／Host／workspace／runtime 隔离，与聊天草稿和消息 outbox 分开。

首次远程写入前，将待确认状态落盘。Host 预登记文件清单，再流式接收字节并校验长度和 SHA-256，临时文件不会显示为上传完成。校验后以不可覆盖的原子方式发布到固定目录，并持久保存完成回执。最多两个上传同时执行。

同一 ID 的不同清单返回冲突；同一文件的重复完成请求返回原回执。Host 在发布文件后、保存完成回执前重启时，查询会重新核对字节并补齐回执，不重新上传。手机失败或重启后只查询待确认记录；只有已确认尚未完成的记录才显示显式上传按钮。断线后的重新上传复用原 ID，不生成另一份文件。

“已上传”是成功交付的历史回执。用户随后移动、编辑或删除电脑文件，不使旧操作重新执行，也不阻止其他文件上传。清理手机保留文件和 Host 历史回执的完整管理入口后续补充；当前完成后移除手机临时副本，未确认文件保留。

## 范围与限制

当前使用前台 HTTPS 请求和已有证书固定；上传状态显示阶段，不提供精确字节进度、分块续传或后台传输保证。图片若从 Files 选择，作为原文件上传；相册入口后续补充。文件接收独立于 Agent 运行状态，但现有 Host 服务的启动仍沿用运行时模式。

默认目录、私有状态和鉴权由本机 Host 管理，不经 Relay，不调用 shell 或透传桌面 IPC。符号链接接收目录、路径穿越、系统保留文件名和越界请求被拒绝。正式 iPhone 构建 37 与四台 Codex Host 的逐机结果见验收记录；单机结果不能替代其他电脑或未覆盖的网络场景。
