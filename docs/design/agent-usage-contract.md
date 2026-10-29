# Agent 额度领域契约 v1

关联：[实施计划](../plans/agent-usage-20261028.md)、[手机 UI](agent-usage.md)。版本：v1，设计于 2026-10-28。状态：**已落地生产类型、Schema 及双端共享校验；以下保留设计说明，实际定义以文末链接的生产源码为准**。

## 建模原则

余额是可报告的资源存量，窗口／容量限制是独立约束；二者不是互斥套餐类型。一个额度桶可以包含任意组合，同一快照可以有多个桶。credits、算力、货币、token 与请求的单位必须显式声明，不能互换；套餐名称仅展示，不驱动结构或可用性判断。

复用现有 `RuntimeTarget` 的机器维度。账号／组织／额度池的来源引用只隔离额度数据和解释共享范围，不成为 Codex 连接、命令、草稿或历史的新拒绝条件。来源范围未知时，不在不同电脑间合并或复用额度；即使同账号，多电脑也按各自原运行时读取。

## 类型定义

下列辅助别名须由运行时解析器构造，普通字符串赋值不代表已经满足约束。所有 readonly 数组在 wire 中均为普通 JSON 数组。

```ts
import type { RuntimeTarget } from '@codex-manage/protocol';

type UsageTarget = Readonly<
  Pick<RuntimeTarget, 'agentId' | 'hostId' | 'workspaceId' | 'runtimeId'>
>;
type Id = string; // 非空，有界，稳定的 opaque ID
type Decimal = string; // 规范十进制，允许负余额，不接受指数／NaN
type NonNegativeDecimal = string; // Decimal 的非负子集
type Instant = string; // UTC RFC 3339，精度至毫秒

type Unit =
  | { kind: 'credits'; code: Id; label: string }
  | { kind: 'compute'; code: Id; label: string }
  | { kind: 'tokens'; code: Id; label: string }
  | { kind: 'requests'; code: Id; label: string }
  | { kind: 'currency'; code: string; label: string }
  | { kind: 'custom'; code: Id; label: string };

type UnavailableReason =
  | 'not-returned'
  | 'unsupported'
  | 'not-authenticated'
  | 'forbidden'
  | 'timeout'
  | 'offline'
  | 'upstream-error'
  | 'invalid-data'
  | 'unsupported-version';

type AppliesTo =
  | { kind: 'all' }
  | { kind: 'models'; ids: readonly Id[] }
  | { kind: 'features'; ids: readonly Id[] };

type MetricBase = {
  id: Id;
  label: string;
  appliesTo: AppliesTo;
  observedAt: Instant;
  validUntil: Instant;
};

type UsageMeasure =
  | { kind: 'percentage'; usedPercent: number }
  | {
      kind: 'quantity';
      used: NonNegativeDecimal;
      limit: NonNegativeDecimal;
      unit: Unit;
    };

type LimitReading =
  | { state: 'measured'; usage: UsageMeasure }
  | { state: 'unlimited' }
  | { state: 'unavailable'; reason: UnavailableReason };

type BalanceReading =
  | {
      state: 'amount';
      amount: Decimal;
      entitlement: 'finite' | 'unlimited' | 'unknown';
    }
  | { state: 'unlimited' }
  | { state: 'unavailable'; reason: UnavailableReason };

type BalanceMetric = MetricBase & {
  kind: 'balance';
  unit: Unit;
  reading: BalanceReading;
  expiresAt: Instant | null;
  // 来源同时返回数值与 unlimited 时二者均保留，不能丢掉余额。
};

type WindowMetric = MetricBase & {
  kind: 'window-limit';
  window: {
    durationSeconds: number | null;
    resetsAt: Instant | null;
    resetPolicy: 'fixed' | 'rolling' | 'provider-defined' | 'unknown';
  };
  reading: LimitReading;
};

type CapacityMetric = MetricBase & {
  kind: 'capacity-limit'; // 无明确时间窗口的 token／请求／资源上限
  reading: LimitReading;
};

type ExtensionMetric = MetricBase & {
  kind: 'extension';
  namespace: Id;
  extensionVersion: number;
  valueText: string | null; // 经适配器校验的纯文本兜底，无上游 wire
  note: string | null;
};

type UsageMetric = BalanceMetric | WindowMetric | CapacityMetric | ExtensionMetric;

type Combination =
  | { kind: 'all-of'; metricIds: readonly Id[] }
  | { kind: 'any-of'; metricIds: readonly Id[] }
  | { kind: 'provider-defined' };

type UsageBucket = {
  id: Id;
  label: string | null;
  // 仅来源有权威共享池证据时填写；不由相同套餐／账号名猜测。
  sharedPoolRef: Id | null;
  combination: Combination;
  metrics: readonly UsageMetric[];
};

type UsageIssue = {
  bucketId: Id | null;
  metricId: Id | null;
  reason: UnavailableReason;
};

type UsageSnapshot = {
  snapshotId: Id;
  sampledAt: Instant;
  source: {
    adapterId: Id;
    scopeRef: Id | null; // 本地 opaque 引用，不返回明文账号／邮箱
    scopeLabel: string | null; // 如“个人额度”／“组织共享”，来源未知就留空
    planLabel: string | null;
  };
  coverage: 'complete' | 'partial';
  buckets: readonly UsageBucket[];
  issues: readonly UsageIssue[];
};

type UsageResponse = {
  schemaVersion: 1;
  target: UsageTarget;
  requestId: Id;
} & (
  { status: 'ok'; snapshot: UsageSnapshot } | { status: 'unavailable'; reason: UnavailableReason }
);

interface UsageProvider {
  readonly descriptor: {
    id: 'usage:read:v1';
    version: 1;
    metricKinds: readonly UsageMetric['kind'][];
    updates: 'push' | 'poll';
  };
  read(input: { target: UsageTarget; requestId: Id; signal: AbortSignal }): Promise<UsageResponse>;
}
```

`status: "ok"` 表示完成这次来源读取；数据覆盖范围看 `coverage`，单项看 `reading`。网络离线但已有缓存由手机本地 `LoadState` 表达，不能将旧快照包装成新的 `ok`。协议中的时间来自 Host／来源，手机不修改它们。

## 强制校验与语义

| 项目           | 约束                                                                                                                                                                                                                                                         |
| -------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 目标           | 复用当前 RuntimeTarget 的四个必填机器维度；响应须完全匹配请求。读取前后重新核对当前运行时与额度来源，变化则丢弃本次额度结果并重读；Codex 其他功能继续按原身份策略执行                                                                                        |
| ID 与范围      | ID 非空、UTF-8 至多 128 字节，不允许控制字符；每快照 bucket ID 唯一，每桶 metric ID 唯一；metric 身份为 target + scopeRef + bucket ID + metric ID，不用展示名作为主键                                                                                        |
| 数量精度       | 使用下方规范十进制正则；禁止 `-0`、前导零、尾随小数零、空白、指数、NaN／Infinity。整数最多 60 位，小数最多 24 位；非负子集禁止负号。来源精度超界返回 invalid-data，不静默截断。使用十进制运算，Swift Decimal 超出能力时保留字符串／大整数表示，不能转 Double |
| 原值与展示     | 原始精确 quantity 不随 UI 四舍五入改变。余额允许负数并标“欠额”；缺失与零不同。来源原始多余小数零可无损规范化，不能四舍五入后写入 wire                                                                                                                        |
| 单位           | `kind` 严格枚举；`code` 是来源定义的资源 ID，至多 64 字节；currency 额外要求三位大写 ISO 4217 代码；label 至多 80 字符。credits 不标货币符号，不推断与算力／token 的换算；相同 label 不能证明同单位或同池                                                    |
| 百分比与数量   | usedPercent 必须有限且非负，允许来源报告大于 100 的超额；used／limit 非负，允许 used > limit 和 limit = 0。图形限制在 0–100，原值保留；quantity 的 limit = 0 不除零。仅对比例派生显示剩余，不创建虚构的 token 总额                                           |
| 时间           | UTC RFC 3339（`Z`），validUntil 不早于 observedAt；时钟明显异常降级为不可确认，不显示“刚刚”。durationSeconds 为正安全整数或 null；未知时长不冒称 5 小时，`resetsAt: null` 不倒计时。当前时间超过重置时刻不会本地重置额度，只发起只读刷新                     |
| 窗口识别       | 300 分钟→18000 秒，10080 分钟→604800 秒。依据时长和来源语义标注 5 小时／7 天窗口；不能按 primary／secondary 的位置或套餐名称命名。其他时长用实际时长，无法判定用来源标签                                                                                     |
| 无限           | 只有来源显式表示无限，reading 才能是 unlimited。`amount` 还可以同时携带 entitlement=unlimited，保留返回的数值；缺失／null／hasCredits=false 不等于零或无限。无限不画 100% 进度条                                                                             |
| 省略           | 来源未声明的 5 小时限制不生成一行；来源声明某指标但没返回 measurement 时保留该指标的 unavailable。空 buckets/metrics 表示来源此次未提供数据，不表示所有额度无限或已耗尽                                                                                      |
| 组合           | all-of／any-of 只引用本桶已存在且不重复的 metric ID，至少两项；必须有来源权威证据。默认 provider-defined，余额／窗口并列展示。首版禁止嵌套组合树，不用这些 advisory 数据在手机封禁发送                                                                       |
| 池与汇总       | 不自行相加多个桶／电脑／模型／赠送或充值子池。来源若提供权威总余额，独立映射为明确标签和适用范围的余额项，保留子池且不二次相加。sharedPoolRef 只解释共享，不直接触发跨目标合并                                                                               |
| 部分失败       | complete 要求 issues 为空且没有 unavailable measurement；partial 至少有 issue 或 unavailable measurement。整源失败用 unavailable，不携带伪快照。有效兄弟项可保留，不因一坏项伪造整页成功                                                                     |
| 规模与文本     | 每快照最多 32 桶、共 64 指标、64 个 issue；JSON 正文最多 256 KiB。所有标签／valueText／note 为有界纯文本（标签 80 字符，扩展值／说明各 256 字符），不执行 HTML、Markdown、脚本或 URL；超界不得静默截断成 complete                                            |
| 枚举与额外字段 | 已知类型 JSON Schema 禁止未声明字段。未知 schema 主版本返回 unsupported-version；未知 metric kind 在独立元素边界跳过并记 partial/unsupported-version，不使整页与聊天崩溃。未来扩展优先新增 Unit.code 或有界 extension；新增 kind 同步能力与跨语言向量        |

规范十进制正则（位数和负零另行校验）：

```regex
^-?(0|[1-9][0-9]*)(\.[0-9]*[1-9])?$
```

未来 Schema 应使用 `oneOf`／判别字段约束字段互斥，例如 unlimited 不能夹带 usedPercent，quantity 必须同时有 used／limit／unit，unavailable 必须有 reason 且不混入正常 value。上表中的唯一性、引用完整性、时间顺序和十进制关系由共享语义校验器补充；不能以 TypeScript 编译通过替代 wire 校验。

读取端采用两层解析：先严格校验 envelope、主版本、目标、规模与指标的判别字段；再逐项对已知类型执行严格 Schema 与语义校验。未知 kind 在元素边界跳过，形成手机本地的 partial 展示投影，不先用整份已知类型 oneOf 拒绝整个快照。该本地投影保留来源 snapshotId、观察时间和原始响应摘要，不冒充 Host 重新返回的快照；服务端适配器只发布本版本已定义的类型或有界 extension。

## 具名传输与缓存

计划中的 Codex HTTP 入口为 `GET /v1/usage?target=<URL 编码 UsageTarget JSON>`，只接受该查询字段，使用原 Host HTTPS／设备密钥认证；不接受任意上游方法、URL、账号或 bearer token。EasyWork 入口为固定 RPC `usage/read`，参数 `{ schemaVersion: 1, target, requestId }`，添加到现有受授权的只读方法白名单；Relay 只路由原有密文。二者返回同一 UsageResponse，公共手机 `readUsage` 窄接口按 provider 适配。

HTTP 的 requestId 由 Host 生成并回传；EasyWork 使用调用方 requestId 关联只读响应。两者 requestId 仅用于跟踪请求，不是模型输入 operation ID，也不触及已提交输入或未知操作回执。HTTP 状态分别表达认证／格式／目标错误；正常的来源暂不可用可返回 HTTP 200 + unavailable；传输失败由客户端本地状态处理。禁止将服务不支持伪装为余额零。

Host 上游缓存与并发合并按 target + provider + 已确认 scopeRef 分组。scopeRef 未知时最多在同一运行时本次连接内复用；来源变化清除该额度缓存并增加本地读取代际，不更换 Host 身份／密钥，不触及聊天缓存或草稿。手机以完整 target、来源和页面读取代际防止迟到覆盖；LAN／Relay 切换仍是同一目标。响应 `Cache-Control: no-store`，额度缓存留在既有受保护的本地存储，禁止共享代理缓存。

新数据只按实际 observedAt 判新鲜；从缓存读取不能重写 observedAt／validUntil。读取失败可以保留同一已确认来源的旧快照，但 UI 必须标上次读取和缓存。来源不连续或账号额度范围无法确认时暂时撤下旧数值，显示同步中；这仅影响额度信息。

## Codex 映射

| Codex 字段              | 领域映射与注意事项                                                                                                                                 |
| ----------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| rateLimitsByLimitId     | 每个 key 为一个桶；不存在才使用 legacy rateLimits 建立单桶。两份数据不叠加；映射采用稳定 ID，primary／secondary 各保持来源身份                     |
| primary／secondary      | 非 null 时各形成 window-limit；按实际 windowDurationMins、usedPercent、resetsAt 投影。上游分钟、Unix 秒转为领域秒、UTC 时间；不能按槽位猜时长      |
| credits.balance         | 存在合法十进制就返回 balance(amount)，原样保留数值精度再无损规范化；unit 为来源原生 credits，UI 标 credits。各桶重复 credit 字段不据此产生累计总额 |
| credits.unlimited       | 显式 true 且无数值时为 unlimited；同时有数值则 amount + entitlement=unlimited。false 不推断余额，空 balance 显示暂不可用                           |
| credits.hasCredits      | 来源的可用性提示，不能换算为余额，也不能用 false 将缺失 balance 补成 "0"。若同时有合法数值就保留数值；矛盾来源状态单独诊断，不封禁手机发送         |
| planType                | source.planLabel 展示元数据；不驱动窗口数量、余额显隐和目标有效性                                                                                  |
| limitName／limitId      | 有界桶标签／opaque ID；normalModelSlug 等适用范围只有在已核实语义时映射 appliesTo，否则保留桶标签，不猜模型权限                                    |
| 重置券／upsell／账户 ID | 不作为 credits.balance 返回，不混入资源余额；首版不消费重置券、不发邮件、不暴露明文账户身份或整份上游响应                                          |

Codex provider 仅走官方元数据读取能力，不启动模型轮、加载／恢复聊天或开启第二个执行器。优先复用 Host 现有只读 metadata 生命周期和严格白名单；若需新 metadata helper，先明确独立白名单、退出与断连边界，不能以普通控制聊天承载额度生命周期。真实来源可能只返回周窗口和 credits，必须保留这种形态。

## EasyWork 映射与待核对项

EasyWork provider 应在原桌面的认证与计费适配边界内读取权威余额，返回通用模型；手机不直接访问其供应商或登录令牌。来源是算力点就声明 compute 单位，是 credits 就声明 credits，真实货币才声明 currency；不要仅把中文“算力”改名成 credits。赠送、购买、组织共享、模型专项、到期时间及时间窗口按来源独立映射。

U3 必须核对：实际 billing API／桌面 service、余额精度与单位、组织／个人范围、免费和付费池关系、到期／重置语义、源端缓存时间、设备可读权限，以及是否支持更新事件。未核实前状态为待接入，不新增虚构端点或字段；来源只返回一个余额也完全合法，未提供窗口不制造卡片。会话 token-usage 只能做未来独立的任务用量功能，不从它扣减或倒推账户余额。

## 已落地的 v1

生产定义以 [usage.ts](../../packages/protocol/src/usage.ts) 和 [JSON Schema](../../packages/protocol/schemas/usage-v1.schema.json) 为准，KMP 的 [UsageRules](../../packages/mobile-core/src/commonMain/kotlin/com/agenthub/core/UsageRules.kt) 同时校验两端。HTTP 请求仅允许单个 target 查询项；requestId 由 Host 生成，EasyWork 则回显固定 RPC 的 requestId。余额摘要使用字符串进位，详情保留完整原值；数量型限额仅为显示比例进行精确整数除法，不将余额转为 Double。相等的余额不建立共享池，legacy 与具名 map 不重复导入。

provider-defined 组合不由手机解释为 all-of／any-of。环选择只取有来源的首个有效全局窗口（其次总量），不会求和、平均或推断哪个窗口耗尽意味着整个账号不可用。上游未返回的窗口不创建占位假数据。扩展类型保持命名空间和有界文本，未知 v1 子类型在元素边界跳过并形成手机本地 partial 投影。
