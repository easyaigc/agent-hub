# Host 图像生成与编辑代理

对外使用 OpenAI Images API 的路径、请求字段、Base64 返回对象和最终图片 SSE 事件。实际执行使用本机 Codex 的固定图像后端和当前桌面授权；不创建聊天，不调用本机工具。接口格式兼容不代表所有公共 API 参数在这条内部通道具有相同语义。

## 启用与模型

现有代理总开关仍默认关闭。仅配置文本模型不会开放图片入口；图片需要单独设置允许清单：

```bash
pnpm configure:responses-proxy -- --state-dir '<private-proxy-directory>' \
  --image-models '["gpt-image-2"]' --bind 127.0.0.1 --port 8797
```

准备器只生成配置，`HOST_RESPONSES_PROXY_ENABLED` 仍为 `0`。管理员显式启用后，`GET /v1/models` 返回配置允许的文本和图像模型；清单不等于上游授权证明。可同时传 `--models` 保留文本模型。

图像 `model` 当前只接受经过本通道验证的 `gpt-image-2`，省略时使用配置的首个图像模型。其他模型、别名或文本模型返回 400，不静默换模型。后续模型必须在 Adapter 明确增加并独立验收。

补充模型语义核对：本代理的 gpt-image-2 是受限的兼容配置，不是上游实时模型目录。2026-10-29 绕过本地允许清单做独立反例测试时，同一 Codex 后端对一个不存在的模型名仍返回 HTTP 200 和完整图片，响应没有实际模型标识。因此不能用请求成功证明该名称被真正选择，也不能把公开 Images API 的模型清单直接开放到本通道。官方内置图像说明与本机 CLI 指向 gpt-image-2，但实际服务端型号仍不能从响应独立确认；目前不承诺自由选模，保持单一配置和本地拒绝其他名称。参见[官方内置图像说明](https://learn.chatgpt.com/docs/image-generation)。

| 配置                                     | 默认与含义                                       |
| ---------------------------------------- | ------------------------------------------------ |
| `HOST_RESPONSES_PROXY_IMAGE_MODELS`      | `[]`，图像入口关闭；当前可配置 `["gpt-image-2"]` |
| `HOST_RESPONSES_PROXY_IMAGE_TIMEOUT_MS`  | 600000，范围 1000–900000；独立于文本请求         |
| `HOST_RESPONSES_PROXY_IMAGE_CONCURRENCY` | 1，范围 1–4；同时受代理总并发限制                |
| `HOST_RESPONSES_PROXY_IMAGE_MAX_BYTES`   | 32 MiB，最多配置 64 MiB，限制整个请求体          |

共用现有独立代理 Key、TLS 和操作账本。非回环监听需要 TLS；浏览器通过自己的后台调用，不在网页保存代理 Key。升级与服务脚本保留显式配置，不自动启用图像能力。

## 请求与返回

`POST /v1/images/generations` 接受 JSON：

```json
{
  "model": "gpt-image-2",
  "prompt": "A small white circle on a blue background",
  "size": "1536x864",
  "quality": "high",
  "n": 1,
  "output_format": "png"
}
```

`POST /v1/images/edits` 接受现代 JSON 图片引用：

```json
{
  "model": "gpt-image-2",
  "prompt": "Change the background to green and preserve the circle",
  "images": [{ "image_url": "data:image/png;base64,<base64-image>" }],
  "size": "1536x864",
  "input_fidelity": "high"
}
```

编辑也支持 OpenAI SDK 的 `multipart/form-data`：单文件 `image`，多文件重复 `image[]`，可选 PNG `mask`，以及普通文本参数。文件名只作上传元数据，绝不作为本机路径读取；不接受把路径字符串当文件字段。JSON 引用接受 HTTPS URL、PNG/JPEG/WebP data URL 或上游自己的 `file_id`，由上游执行访问控制；Host 不代抓 URL，不提供 Files API，也不保证公共 API 上传的 File ID 可在 Codex 后端使用。

JSON 编辑支持 1–16 张参考图、可选 `mask` 引用。单张本地上传／Base64 图片上限 20 MiB、100 MP；整个请求仍受上表限制。Mask 透传为图片引用，不承诺逐像素保护；远程引用、多图、蒙版与具体图像格式的真实结果分别验收。

返回标准对象，例如：

```json
{
  "created": 1234567890,
  "data": [{ "b64_json": "<actual-image-base64>" }],
  "size": "1672x941",
  "output_format": "png",
  "quality": "medium"
}
```

`size` 和 `output_format` 来自最终文件头，并核对上游元数据；`quality`、`background`、`usage` 仅在上游提供有效对应值时返回。调用方按实际 `output_format` 选择文件后缀，不能按请求值假定编码。单张返回图片上限 20 MiB，上游 JSON 响应总上限 64 MiB。代理不本地放大、裁剪、转码或伪造高分辨率。

## 参数适配边界

| 参数                                      | 本通道行为                                                                                                              |
| ----------------------------------------- | ----------------------------------------------------------------------------------------------------------------------- |
| `prompt`                                  | 保留原文，在后面单独追加输出偏好；转换后仍限制 32000 字符                                                               |
| `size`                                    | 验证公共格式的边长、像素与比例限制；追加约分后的比例、期望宽高和铺满画布要求。像素尺寸是偏好，当前真实测试未证明原生 2K |
| `quality`                                 | 转成细节与渲染取舍偏好，不宣称强制推理档位                                                                              |
| `background`                              | 转成透明 alpha／不透明背景偏好，不伪造透明结果                                                                          |
| `output_format`、`output_compression`     | 转成编码偏好；响应返回实际编码，不保证按偏好转码                                                                        |
| `input_fidelity`                          | 编辑时转成参考图保真偏好；不伪称调用了上游不支持的硬参数                                                                |
| `moderation`、`safety_identifier`、`user` | 结构化传递，不转成绕过上游策略的提示词                                                                                  |
| `n`                                       | 当前仅支持 1；其他值提交前返回 400，不自动拆成多个付费请求                                                              |
| `response_format`                         | 仅 Base64；可省略或传 `b64_json`，不提供 URL 结果托管                                                                   |
| `stream`                                  | 支持 `true`；当前为最终图片事件，不提供局部预览                                                                         |
| `partial_images`                          | 可省略或为 0，其他值提交前返回 400                                                                                      |
| 其他字段                                  | 明确拒绝，不静默透传未知参数、认证信息、上游地址或本机路径                                                              |

响应头 `X-Agent-Hub-Image-Parameters: prompt-preferences` 声明适配语义，非流式的 `X-Agent-Hub-Image-Preference-Fields` 列出本次转成偏好的字段。

## 流式、回执与失败

`stream=true` 使用 `text/event-stream`。等待期间可能收到 SSE 注释心跳，最终为 `image_generation.completed` 或 `image_edit.completed`，含 `b64_json`、`created_at` 和实际输出元数据。它由完整上游 JSON 转换而来，不宣称原始上游 SSE 或实时生成进度。错误在已开始的流内以 `error` 事件结束；提交前错误仍使用正常 HTTP 错误。

每次调用显式提供稳定 `Idempotency-Key`，关闭 SDK 自动重试；操作 ID、请求摘要与上游转换摘要绑定原提交。重复 ID 返回 409，查询 `GET /v1/requests/:id`。最终图片校验与 completed 回执落盘先于最终响应；错误回执也先于错误响应保存。原操作忙碌时重复提交仍返回 409，不把它当新请求排队。

回执只保存元数据、实际输出尺寸和摘要，不保存提示词、参考图、生成图、授权或账号。未取得完整结果、断连、超时和重启恢复均按原未知规则处理；404 不构成重发许可。结束本地等待不等于取消远端任务，未知结果不自动重放。调用方需自行保留成功取得的图片。

OpenAI 格式依据：[生成接口](https://developers.openai.com/api/reference/resources/images/methods/generate)、[编辑接口](https://developers.openai.com/api/reference/resources/images/methods/edit)。实现与实际验证范围见[专项验收](acceptance/host-images-proxy-20261029.md)。
