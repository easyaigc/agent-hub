# Host DOM Bridge 只读 PoC

2026-10-17。用户明确要求开发由 Host 使用的 Bridge，先解决桌面建议的自动只读读取。截图 OCR 与附件传输不再作为此目标的替代验收。

已实现 Adapter 采集器、Host 的具名 GET 接口和本地探针。当前默认关闭，没有部署正式 Host、增加 App 入口或声明正式建议能力。**原 Codex 的当前 DOM 尚未实读；本页不能充当本机原建议获取成功的证据。**

## 连接与读取

Bridge 使用应用开发环境已提供的本机 CDP 端点。私有配置绑定明确的回环端口、页面 Target ID 和文档 origin；不由手机传入端点、选择器或脚本。Bridge 不负责开启调试端口、重启或修改应用。页面 Target ID 变化后需要重新核对配置；目前没有自动恢复页面目标的实现。

配置文件由当前用户持有并保护为 0600，例如：

```json
{
  "endpoint": "http://127.0.0.1:9222",
  "targetId": "PAGE_TARGET_ID",
  "origin": "app://-"
}
```

`endpoint` 只接受明确端口的 `127.0.0.1` 或 IPv6 回环 HTTP 地址；目录读取禁止重定向。实际 WebSocket 地址必须与配置的主机、端口及准确页面路径一致。设置 `CODEX_DESKTOP_DOM_BRIDGE_CONFIG` 指向该私有文件后，Host 才启用此 PoC 来源；配置错误直接报错。

采集只发送以下 [CDP DOM 方法](https://chromedevtools.github.io/devtools-protocol/tot/DOM/)：

- `DOM.getDocument` 取得初始根节点。
- `DOM.querySelectorAll` 固定定位 `.ProseMirror[role="textbox"]` 和其中的 `[data-cumberbatch] > .cumberbatch-content`。
- `DOM.getBoxModel` 排除没有布局框的节点；要求唯一有布局框的输入框和至多一个建议。
- `DOM.describeNode` 读取选定子树的文本节点及复核已有根节点，避免重复 `getDocument` 导致节点 ID 失效。

没有 JavaScript 求值或任意 CDP 透传入口。读取不会输入、采用、发送、导航或生成预测。已有草稿返回 `draft-present`；空输入且未发现建议返回 `empty`；定位、连接或归属失败返回明确错误，不能用空结果冒充来源可用。每次读取限时、串行，响应有界，正文上限为 8,000 个 UTF-16 code unit。此上限低于当前 [Chromium 节点文本截断边界](https://raw.githubusercontent.com/chromium/chromium/main/third_party/blink/renderer/core/inspector/inspector_dom_agent.cc)，避免将被截断的节点描述交给 App。

DOM 文本保留文本节点、换行及字符，排除外层的采用提示；结果标为 `representation: rendered-text`。它不是原预测插件的原始 `prediction.text`，mention 等 DOM 排版的还原仍需在原应用核对。布局框存在也不等同于完整的屏幕遮挡、裁剪或透明度证明。

## 来源与 Host 接口

```text
GET /v1/desktop/composer-suggestion
    ?hostId=HOST_ID
    &threadId=THREAD_UUID
    &sourceTurnId=TURN_ID
    &target=URL_ENCODED_RUNTIME_TARGET_JSON
Authorization: Bearer <既有 Host 密钥>
```

沿用 Host 的鉴权、Origin 拒绝及电脑身份检查；只允许上述四个唯一查询字段。完整目标继续使用 `agentId / hostId / workspaceId / runtimeId` 的机器身份语义。

Host 在 DOM 读取前后分别向原 owner 取权威快照，要求准确聊天、最新轮次已完成且 `sourceTurnId` 一致，运行目标稳定。历史文件或缓存不能补作原 owner 证据。页面归属由唯一可见输入框的 `data-conversation-id` 或文档路径末段的准确聊天 UUID 提供；两者冲突或均不提供时拒绝返回正文。当前安装源码中的 pending textarea 有聊天属性，并不证明实际 ProseMirror 也有该属性；若原应用的实际 URL 不带聊天 ID，这仍可能成为原环境接入缺口。

成功有建议时返回：

```json
{
  "status": "present",
  "source": "desktop-dom",
  "representation": "rendered-text",
  "threadId": "THREAD_UUID",
  "sourceTurnId": "TURN_ID",
  "sourceTurnBinding": "latest-completed-owner",
  "target": {
    "agentId": "codex",
    "hostId": "HOST_ID",
    "workspaceId": "WORKSPACE_ID",
    "runtimeId": "desktop-runtime"
  },
  "text": "<已经显示的建议正文>",
  "observedAt": "<读取时间>",
  "contentSha256": "<正文摘要>",
  "id": "<完整来源与正文的稳定摘要>"
}
```

`observedAt` 是观察时间，轮次绑定来自稳定的最新 owner 快照，没有冒称已取得预测生成对象中的来源元信息。两个进程之间的复核不提供原子快照保证。当前只读取已显示页面，不会为手机切换桌面聊天；不承诺取得后台聊天的内存缓存。

未配置或源断开为 503；旧轮次、聊天绑定不足、多候选或读取期间切换为 409；字段无效为 400；错误包含可区分的 `code`。API 为受配置控制的 PoC，未加入正式能力目录或原生建议 UI。

本地独立探针使用同一 Adapter：

```bash
pnpm probe:desktop:dom /path/to/private-bridge.json THREAD_UUID
```

该探针只验证 DOM 页面归属并输出读取结果，不单独声明已核对原 owner 或来源轮次；完整检查由 Host 接口完成。

## 验证与未完成项

`pnpm check:desktop-dom-bridge` 启动独立私有 profile 的真实 Chromium，使用合成 HTML 验证 CDP 连接及读取，不连接原 Codex 或用户现有 Chrome。通过与失败回执分别保存在忽略的 `.local/desktop-dom-bridge-smoke/`。没有 Chromium 的环境可用 `DOM_BRIDGE_TEST_CHROME` 指定本机可执行文件。

本次 Node 24.19.0 下的独立 Chromium **15 项通过**，包含 `aria-hidden / inert` 实读、字符与换行保真、并发读取串行、错聊天拒绝、隐藏副本、多候选、已有草稿、无建议、超长拒绝、7,500 字文本完整性、Host 完整目标／旧轮／迟到轮次拒绝及出站方法白名单。`runtime-smoke` 中的实际 HTTP 检查通过鉴权、未配置 503、未声明字段／重复字段拒绝、完整目标必填和 POST 拒绝；该 HTTP 检查使用已有模拟运行时，不作为真实原 owner 验收。构建、类型检查、本次文件 lint 及既有 IPC 边界回归通过。

已运行 `pnpm check`：格式检查通过，随后被另一份现有未提交文件 `packages/codex-adapter/src/message-links.ts:103` 的 `no-control-regex` lint 错误阻断。该文件不属于本次 Bridge 改动，保留原状；没有将部分检查写成全仓检查通过。

原 Codex 的调试端点、实际文档归属、已生成的真实正文、来源轮次及重启后恢复仍未验收。当前电脑操作工具已明确拒绝原 Codex UI 访问，本次没有通过新 Bridge 执行该应用的 DOM 读取。原应用成功实读前，不交付或宣称正式建议同步；本页的独立浏览器结果仅为机制与边界证据。
