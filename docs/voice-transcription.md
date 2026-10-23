# 语音转写

## 已实现

设置 → 语音输入 → 默认转写提供“电脑端”和“本地（手机）”，默认电脑端，选择保存在手机上，主输入框与问题回复共用。App 在进入前台、切换电脑及连接配置／运行目标或在线状态变化时提前检查电脑转写就绪状态，前台每 30 秒刷新，结果最多使用 60 秒。点击语音只同步读取当前电脑的检查结果，不发起或等待网络检查；尚未完成、结果过期、Agent 未实现、旧 Host 没有接口、上游认证／路由不可用或就绪读取失败时，立即使用本地听写，显示“电脑转写暂不可用，正在本地听写”。检查晚到不会改变本次录音的后端，只影响下次录音。取消、目标变化及已确认的电脑身份错误不启动新识别。选择本地或退入后台时停止预检，不上传本地音频，也不申请电脑端 AI 数据共享许可。

本地模式恢复此前的 Apple Speech 路径：iOS 26 优先使用 DictationTranscriber / SpeechAnalyzer，在设备处理支持语言并按需安装语音资源；其他系统或语言沿用 SFSpeechRecognizer，支持时使用设备识别，否则由系统决定联网。此处“本地”表示手机系统听写，不保证所有设备、系统与语言均离线。只在启用本地时请求语音识别权限，两种模式都需要麦克风权限。识别文字先预览，完成后回填原草稿，由用户确认发送；取消或切目标后的结果不得覆盖新输入。

以下为电脑端路径：iOS 使用 AVAudioRecorder 录制单声道 16 kHz PCM16 WAV，不调用 Apple Speech、Siri 或设备听写。结束后对录音原文件计算 SHA-256，经已有的具名 Host HTTPS 连接上传。Host 校验大小、RIFF、格式、时长与指纹，直接把原始 WAV 字节包进 multipart，提交 Codex 桌面使用的独立 `https://chatgpt.com/backend-api/transcribe`。不重编码，不通过普通模型 Turn，不生成消息，不自动发送转写文字。

`packages/codex-adapter` 隔离这个版本相关的内部桌面接口。它不是公开的 Codex App Server 转写 API，也不是独立的 Platform API 订阅。官方 metadata-only 认证读取器只允许 initialize、getAuthStatus、account/read、config/read、configRequirements/read，固定 refreshToken=false；凭据存储由官方 CLI 处理，令牌仅留在 Host 内存中。不登录、不刷新、不写认证文件、不运行模型或工具；过期时要求原桌面恢复登录。只接标准 ChatGPT / NO_CONSTRAINT 路由，专用路由、FedRAMP、企业 requirements 或未知配置返回不可用。上游的具体 ASR 模型未公开，不能据此命名为 Whisper。

Host 仅在当前认证与路由可用时声明 `voice:transcribe`。当前电脑端提供者仅接 Codex；其他 Agent 未支持电脑端转写时，App 自动使用用户手机的系统听写，不借用另一台电脑。macOS 使用已有 HTTPS_PROXY 或系统静态 HTTPS 代理；Windows 使用进程代理变量，系统 PAC / Windows 专用代理未验证。

## 窄接口

所有接口需要现有 Host 鉴权和正确 hostId。不能传上游地址、方法、登录令牌、模型名或电脑文件路径。

| 操作 | 方法与路径                                        | 作用                                          |
| ---- | ------------------------------------------------- | --------------------------------------------- |
| 就绪 | GET /v1/voice/readiness                           | available / reason / maxBytes / maxDurationMs |
| 准备 | POST /v1/voice/transcriptions                     | 保存不可变录音清单，尚未提交音频              |
| 上传 | POST /v1/voice/transcriptions/audio?hostId=…&id=… | Content-Type: audio/wav，原始二进制           |
| 查询 | GET /v1/voice/transcriptions?hostId=…&id=…        | 查询同一个操作的回执，不重发上游请求          |

清单字段：hostId、threadId（现有对话 ID 或 null）、draftId（包含完整客户端/账户/对话范围的草稿键）、operationId（小写 UUID）、mediaType=audio/wav、sizeBytes、sha256、durationMs。最多 12 MiB、5 分钟、4 个并发转写、64 个未过期操作。回执重复清单并返回 phase、text、error、expiresAt；状态为 awaiting-audio、transcribing、completed、failed、outcome-unknown。

## 恢复与隔离

Host 先私有保存原音频，再原子落盘 transcribing，之后只提交一次上游。重复清单和上传只返回原回执，任何字节或目标变化均拒绝。网络超时、5xx、响应不兼容或重启后的未完成记录为 outcome-unknown，不自动重放。明确 4xx 拒绝记为 failed；App 的重试只查询/补齐原 ID，不新建上游提交。已向电脑提交的录音不自动切换识别后端，保留原操作查询，避免未知结果重复执行。失败界面提供“重新录音”，仅用户明确点击时才发起新操作；原回执和附件继续保留，晚到结果不会覆盖新录音。

操作绑定当前 ChatGPT accountId 与 token subject 的组合指纹；准备、上传、回执读取和实际提交都核对。换账户后不返回旧转写。Host 音频与文字 24 小时到期后在访问/启动/每小时清理；保留无文字的操作 tombstone，旧 ID 始终返回 410，避免过期未知操作被重新执行。凭据、完整 principal 和文件路径不返回手机。

App 在录音期间保持原草稿，停止后才显示电脑转写结果。后台/录音中断保留当前录音供恢复；失败保留文件及 ID，重试查询原结果。换目标或主动取消会使晚到结果失效；同一草稿被另外编辑时不覆盖。成功回填后由用户普通发送按钮确认。完成录音后，App 在请求 Host 之前将原 WAV 与不可变操作 ID 私有落盘。结束等待、换目标或重启 App 后，回到同一账户、Host、对话和未改动的原草稿，再点语音即可恢复最近一条匹配操作；恢复仅显示“重试转写”，不会自动联网提交。成功回填后清理本地副本；未完成副本 24 小时到期后在访问时清理。录音尚未结束就被系统终止的临时文件不提供跨进程恢复。Host 仍保留可查询原回执，结束等待不宣称取消远端。

首次上传遵守现有 App AI 数据共享确认，专门说明录音会交给 OpenAI 转写。电脑端路径只请求麦克风权限；本地路径另请求 Apple Speech 权限。隐私政策源码同步更新；网站发布是独立操作。

## 验证

`scripts/voice-transcription-smoke.mjs` 检查认证方法白名单/无刷新、专用路由拒绝、完整用户身份变化、原文件指纹、重复上传、持久化回执、重启未知结果、账户隔离、过期 tombstone 和畸形 WAV 不到上游。

2026-10-13 本机 Codex Desktop 26.1007.30912 / CLI 0.162.0-alpha.17.2 的独立转写实际 HTTP 200，Node 24 原生 HTTPS Agent 使用已有代理。未将校验句提供给接口，返回文字与音频忽略标点大小写后一致；认证文件未变化，未创建模型 Turn。此项是独立服务验证，不替代手机验收。

原生构建与真实 iPhone 麦克风链路的本次结果追加在 验收记录（本地文件 `docs/acceptance/voice-transcription-20261013.md`）。Windows、专用账户和 Keychain 存储切换不计为已验收。

2026-10-15 新增默认方式与自动回退；原生构建、路由／设置回归与实际麦克风验收分别记录，历史电脑转写结果不代表新增本地路径已经实测。

## UM05 Android迁移（实现中，尚未封存验收）

Android使用原生AudioRecord录制同样的16kHz／单声道／PCM16 WAV；共享许可、原清单／回执校验通过KMP与iOS共用。录音开始先登记原草稿和操作ID，原WAV在提交前用Android Keystore加密保存。Android可从未封存的私有PCM恢复同一ID并生成首次清单；已封存记录不重编码。匹配恢复只提供显式重试，不自动上传；上传前持久写入attempt栅栏，重试时重新读取磁盘栅栏，原ID返回404也不能重新准备已尝试上传的录音。取消已封存等待保留原录音，迟到结果不能改写新草稿。Android待处理记录上限32，不自动删除未知音频以腾出空间；iOS既有24小时本地清理策略保持，平台存储差异单列。两端构建和公共规则向量仅为辅助证据，当前实际原生录音、真实Host转写与独立iOS门槛仍在验收。

UM05当前安卓封存使用逐块AES-GCM认证的二进制文件，音频不再进入整份Base64 JSON；每块绑定存储键、完整长度和块序号，完整认证后才供原清单摘要校验。原始WAV先保留，密文音频及元数据均保存成功后才清理临时文件，准备／上传栅栏继续单独保存。API26五分钟9,600,044字节边界已实际通过，属于合成时长边界，不能代替五分钟真实麦克风证明。iOS实际设备准备移至独立队列，十秒超时返回可重试错误；迟到准备结果隔离，不继续提交或覆盖草稿。当前实际环境验收仍在进行。

2026-10-19 将就绪检查从点击路径提前到前台准备阶段。延迟预检、连续录音、切电脑隔离、原生构建及真实电脑 ASR 的本次结果分别记录于本地文件 `docs/acceptance/voice-preflight-20261019.md`；实体手机与实际本地麦克风识别另行记证。
