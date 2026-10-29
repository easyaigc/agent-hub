# Host 诊断与只读恢复

2026-10-29 源码候选。Windows 迁移现场的那次 503 尚未取得准确 route／response body，不能认定为 IPC、存储或账号问题。Windows 正式进程未由本任务重启、升级或验收；部署后按包 SHA 重新核对。

## 请求与分层状态

所有 Host HTTP 错误响应增加 `code`、`requestId`、`diagnosticId`，响应头为 `X-Agent-Hub-Diagnostic-Id`。ID 由服务器生成，不能用客户端传入的请求头覆盖。正常响应同样有该响应头。原 JSON 错误正文继续保留具体原因；不要把任意 503 都转换成“Codex 断开”。

| 内部分类                                             | 含义与处理                                                  |
| ---------------------------------------------------- | ----------------------------------------------------------- |
| `CODEX_RUNTIME_DISCONNECTED`                         | bridge 或只读 reader 未就绪；恢复只读连接                   |
| `HOST_STATE_SAVE_FAILED`                             | 操作状态保存失败；保留原操作 ID，查询原回执，不重发         |
| `DESKTOP_OWNER_UNAVAILABLE`／`DESKTOP_OWNER_CHANGED` | 该聊天的准确原 owner 不可用或已变化；重新核对 owner         |
| `SUGGESTION_OWNER_UNAVAILABLE`                       | 建议来源 owner 不可用                                       |
| `SUGGESTION_READ_BUSY`／`SUGGESTION_READ_DEADLINE`   | 建议读取互斥或截止；不等同运行时掉线                        |
| `DESKTOP_TRANSCRIPTION_UNAVAILABLE`                  | 原桌面转写能力不可用                                        |
| `HOST_CLOSED`                                        | 已关闭代次拒绝迟到回复                                      |
| `WINDOWS_PRIVATE_STATE_UNAVAILABLE`                  | 辅助通道启动／响应／退出不可用；查看阶段与 PID              |
| `UNSAFE_WINDOWS_PRIVATE_STATE`                       | 本次权限保护／校验被拒绝；不放宽 OWNER、DACL 或重解析点守卫 |

`GET /healthz` 的 200 只说明 HTTP 服务响应。`runtime.ready`、`runtime.bridge`、`runtime.reader` 和已有 `persistence` 分开报告；`commandCommitPending`、`historyPending`、`historyStalled` 不能被 HTTP 状态掩盖。聊天控制仍以准确目标的现有 `canControl`／原 owner 查询为准。`diagnostics` 返回启动 ID、程序指纹、待写队列、抑制计数与日志故障标记。未更改设备凭据、Codex 账号策略或模型输入规则。

## 日志位置、隐私和限额

位置为当前 `HOST_AGENT_STATE_DIR/diagnostics/errors.jsonl`，轮转为 `.1`、`.2`。每文件最多 2 MiB，共最多 6 MiB；写入前删除超过七天未写的这三个固定文件，不扫描或删除其他诊断目录。目录 0700、文件 0600；Windows 每批仍执行真实原生 OWNER／DACL 保护与校验，不缓存权限成功。文件或目录符号链接／重解析点失败关闭写入。

记录字段采用固定 Schema：发生时的 Unix／单调时间、写入时间、Host／boot ID、Adapter 指纹、scope／stage、服务器 request ID、已解析且符合格式的 operation ID、允许清单中的 pathname、HTTP 方法／status／分类、耗时、ready／bridge／reader、提交保存门禁、历史待写与阻塞数量。只记录符合 UUID／摘要格式的 thread／message／resource ID；未知路径写 `/other`。

Windows 辅助通道记录启动等待、准备、spawn gate、原生派发／响应、deadline 剩余、同步／异步等待耗时、队列等待、request 序号、worker／native PID、退出码、取消发起方、迟到响应和清理确认。PID 与时间、boot／worker ID 联合关联；PID 本身不授予恢复或终止权。原生执行耗时含其管道写入到确认的往返，不能冒充 C# 内部各系统调用耗时。

每十秒记录有界事件循环 mean／max 延迟、日志队列／抑制数。主线程仅保存 64 条最近事件和最多 64 条待写事件，同时至多一个批次在途、每批最多 64 条、每秒最多接收 200 条；过量抑制计数可见。ring 仅内存，不开放包含内部上下文的新 HTTP 接口。原生 worker 诊断使用独立线程消息通道，不混入执行协议；日志写入器及其辅助子通道被排除，避免递归日志。

独立 worker 执行文件、轮转与 Windows 辅助等待，不让错误响应重新同步执行 DACL 保护。写入失败丢弃该批并报告日志故障，30 秒冷却后由下一事件尝试同一写入器；worker 自身异常退出不由日志路径无限重建。写入器创建失败不阻止 Host 启动。日志为有界尽力记录；队列满、权限失败或关闭期限内未落盘时可能缺项，须同时核对丢弃计数，不能以“没有日志”证明没有失败。

不记录 Authorization、完整 query、用户正文、附件内容、原始 stderr、错误 message／stack、机器文件路径或密钥。旧版包含路径或 stack 的日志不会重写；按本机私有资料规则单独保管。

## 重连与停止

RuntimeSession 只有一个重连定时器与一次在途恢复，失败按 1、2、4、8、15 秒基数退避，并添加 ±20% 抖动；成功重置。只读 reader 和 DesktopRuntime 合并并发 connect。reader 先确认前一子进程 exit 及 stdio close，Windows bridge 先确认前一辅助进程 close，未确认则拒绝替换。Windows 私有辅助通道仍以实际 `gone` 与退出屏障允许新代次，权限拒绝保留同一通道，不自动重放失败调用。

IPC close 立即撤销 ready、取消正在准备的桥接；初始化和 reader 返回时核对代次及实际对象。reader EOF、流错误或退出撤销就绪并走既有恢复。DesktopRuntime 显式 close 后不可再次连接，RuntimeSession close 清除定时器，迟到成功不能重新发布 ready 或模型输入。Windows bridge 在构造 Duplex 后立即消费取消／关闭产生的流错误，并只结束自己的辅助进程。

命令与只读恢复分开：断线将已提交但未确认操作保留为 uncertain，恢复后查询原 ID；没有自动重新发送输入、审批、创建或原生保护请求。保留既有原 owner 验证和原 Host supervisor／登录自启 stop 策略；没有重启原 Codex、轮换密钥或不断重启 Host 的新策略。同步命令提交仍保持原持久化门禁，慢保护仍可能阻塞该原同步边界；本次没有用日志或延长 timeout 掩盖这一限制。

## 验证与部署

辅助检查运行 `pnpm check`，新增 `scripts/runtime-reconnect-diagnostics.test.mjs` 已接入根检查与 Windows 检查。Windows 原生用原机的 `pnpm check:windows-native`；不能用 Mac 的合成辅助通道通过代替 C#／NTFS 或 Task Scheduler 通过。

按 `bundle.json` 和 `verify-host-runtime.mjs` 比对实际 ESM 闭包后，再按原服务入口升级本产品 Host。保留原身份、令牌、目录、端口、TLS、设备授权与已有未知回执。部署只做差异合并。发生 503 时先保存诊断 ID／HTTP 状态／具体分类及非敏感健康快照；凭原 ID 查询操作，不重复提交来“验证重连”。当前已恢复的 Windows 无需为取证重启原桌面。

平台与本次结果见[专项验收](research/host-reconnect-diagnostics-20261029.md)。
