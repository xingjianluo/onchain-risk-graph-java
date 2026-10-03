# 本地交易图风险传播与资金追踪服务

用**内存账本**模拟链上交易流水，在本地完成并发入账、风险传播、资金追踪与历史快照回放。
所有风险结论都确定、可解释：给出总分、阈值、依据的规则版本，以及每条贡献的完整传播路径与交易序列。

技术栈：Java 21 + Spring Boot 3.4（仅用 `spring-boot-starter-web`，无外部数据库）。

---

## 1. 核心模型

| 模型 | 说明 |
| --- | --- |
| `Transaction` | `txId / fromAddress / toAddress / amount / visibleAt`，`txId` 为幂等键，`visibleAt` 为该交易对查询可见的时间点（毫秒） |
| `RiskLabel` | `address / baseScore(0,1] / effectiveAt`，地址自该时点起成为风险源 |
| `RuleVersion` | 不可变规则版本：`version / effectiveAt / maxHops / decay / riskThreshold` |
| `RiskPath` | 单条传播贡献：风险源、基础分、跳数、贡献分、地址路径 `route`、交易序列 `txIds` |
| `RiskConclusion` | `riskScore`（noisy-OR 聚合）、`risky`、规则版本、阈值、全部贡献路径 |
| `FundFlow` | 快照时点的流入/流出总额与对应交易 id |
| `QueryRejection` | 查询被拒绝的稳定原因码 `reason` + 可解释说明 `message`（见第 6 节） |
| `QueryOutcome<T>` | 查询结果：结论载荷或可解释拒绝，二者恰居其一 |
| `RuleDiffResult` | 同一时点两个规则版本的判定差异：`becameRisky` / `cleared` / `scoreChanges` |
| `ReplayItem` | 批量回放中单个时间点的结果，顺序与请求一一对应 |

---

## 2. 风险传播规则

1. **传播方向沿资金流**：从风险源地址出发，沿 `from -> to` 的交易边逐跳 BFS。
2. **逐跳衰减**：路径第 k 跳的贡献为
   `contribution = baseScore * decay^k`（k=0 即风险源自身，贡献为基础分）。
3. **环路与自环**：每个风险源的 BFS 使用访问集，同一节点只保留首次（即最短跳数/最大贡献）
   路径；自环边与回到已访问节点的环路边直接剪枝——**不会无限传播，也不会重复累加**。
4. **多风险源聚合（noisy-OR）**：同一地址收到来自不同风险源的贡献 c₁..cₙ 时
   `riskScore = 1 − Π(1 − cᵢ)`，天然保证分值落在 [0,1]，且来源相互独立、可分别列出。
5. **判定**：`riskScore >= riskThreshold` 为高风险。结论中同时返回阈值与规则版本号。
6. **确定性**：邻接边按 `txId` 排序、节点按地址字典序遍历，并列情况结论稳定、可重复。
7. 每条结论的日志都会打印：**交易流水、每条传播路径（地址序列+交易序列）、贡献分与阈值比较依据**。

---

## 3. 幂等与冲突语义

以 `txId`（风险标记以 `address`）为幂等键，并发安全（`ConcurrentHashMap.putIfAbsent`）：

| 情况 | `SubmitStatus` | HTTP | 行为 |
| --- | --- | --- | --- |
| 首次提交 | `ACCEPTED` | 200 | 正常计入资金流与图 |
| 同标识 + 内容完全一致 | `DUPLICATE` | 200 | 普通重复投递，幂等忽略，**资金/指标只计一次** |
| 同标识 + 任一内容字段不一致 | `REJECTED_CONFLICT` | 409 | 拒绝写入，消息列出差异字段（如 `toAddress: B != C`），与重复投递可区分 |
| 字段非法（空地址、amount<=0、baseScore 越界等） | `REJECTED_INVALID` | 400 | 拒绝并给出原因 |

并发重复投递时：N 个并发请求中**恰好 1 个 ACCEPTED，其余全部 DUPLICATE**。

---

## 4. 快照与规则一致性语义

### 快照（bounded visibility）

- 每个查询携带时间点 `asOf`，评估只使用
  **`visibleAt <= asOf` 的交易**、**`effectiveAt <= asOf` 的风险标记**。
- 图在每次查询时从账本实时构建不可变快照视图；之后写入的更晚交易**不会影响历史结论**，
  同一时间点可任意次重放，结论逐位一致。
- 资金追踪（流入/流出）与风险传播共用同一快照视图，口径一致。

### 规则版本（可复现历史、非法不静默生效）

- 规则发布后**不可变**，版本号从 1 递增，`effectiveAt` 必须**严格递增**；
  回退或与当前版本时间重叠返回 `REJECTED_CONFLICT`，不改状态。
- 查询某时间点时自动选取 `effectiveAt <= asOf` 的最新版本，结论中记录 `ruleVersion`；
  **规则变更只影响其生效后的判定，历史结论永远按当时版本复现。**
- 非法取值（`maxHops<0`、`decay∉(0,1]`、`threshold∉(0,1]`）返回 `REJECTED_INVALID`。
- 启动时若 `risk.*` 配置无法生成初始版本，应用直接启动失败；若查询时点没有任何规则版本，
  评估抛出异常而不是返回静默的默认结论。

---

## 5. 规则版本回放与影响对比

### 显式指定版本复现

- 单点查询与批量回放都可携带可选参数 `ruleVersion`，**显式用某个已发布版本复现结论**：
  数据可见性仍按 `asOf` 快照口径（`visibleAt/effectiveAt <= asOf`），但传播参数
  （maxHops/decay/threshold）取自指定版本，与当时该时点的实际生效版本无关。
- 指定**旧版本**跑出的结果与当时的历史结论**逐位一致**——规则版本发布后不可变，
  之后发布的新版本不会带偏旧版本的复现结果。
- **不指定 `ruleVersion` 时行为完全不变**：仍按 `effectiveAt <= asOf` 的最新版本判定，
  当前最新规则在新时点的判定不受任何影响。
- 指定的版本号必须已发布：`<=0` 拒绝 `INVALID_RULE_VERSION`，不存在拒绝 `UNKNOWN_RULE_VERSION`。

### 版本对比（`GET /api/risk/compare`）

同一时间点 `asOf`、两个已发布版本 `baseVersion` / `targetVersion` 分别全量评估，输出：

| 字段 | 含义 |
| --- | --- |
| `becameRisky` | 从正常变为高风险的地址（含两个版本各自分值与 Δ） |
| `cleared` | 从高风险解除为正常的地址（含分值变化） |
| `scoreChanges` | 所有分值或判定有变化的地址（含上述两类），按地址字典序 |

---

## 6. 边界查询的可解释拒绝

查询类接口（风险评估 / 资金追踪 / 回放 / 对比）不再对边界问题返回含糊错误或通用 500，
而是返回**稳定、彼此可区分**的拒绝体 `{"reason": "...", "message": "..."}`：

| `reason` | HTTP | 触发场景 |
| --- | --- | --- |
| `INVALID_ADDRESS` | 400 | 地址为空或全空白 |
| `INVALID_AS_OF` | 400 | 时间点为负数 |
| `INVALID_RULE_VERSION` | 400 | 显式版本号 <= 0 |
| `UNKNOWN_RULE_VERSION` | 422 | 显式版本号从未发布（或超出回放边界） |
| `NO_EFFECTIVE_RULE` | 422 | 该时间点之前没有任何已生效规则版本 |
| `EMPTY_REPLAY_REQUEST` | 422 | 批量回放请求缺少地址或时间点列表为空 |

`reason` 为稳定枚举值可机读，`message` 含实际入参便于排查；正常查询路径不受影响。

---

## 7. 批量历史回放（`POST /api/risk/replay`）

请求体 `{"address": "Z", "asOfs": [150, 250, 350], "ruleVersion": null}`：

- **一致性**：每个时间点的结论与逐点单独查询**完全一致**（同一套快照评估逻辑）。
- **顺序稳定**：返回列表与请求中的 `asOfs` 一一对应、顺序一致（允许乱序与重复时间点）。
- **并发隔离**：回放开始时截取一致性边界 `ReplayScope`（账本入账序号、标记登记序号、
  规则版本号三线同时定格）。回放进行期间新入账的交易（哪怕 `visibleAt` 在过去）、
  新登记的标记、新发布的规则版本**都不会进入本批结果**；
  单个时间点的拒绝（如非法 asOf）不影响其余时间点，按点返回拒绝原因。
- `ruleVersion` 可空：空则每个时间点按各自的时间点语义选版本，非空则整批用指定版本复现。

### 对旧接口的兼容范围

- 不携带 `ruleVersion` 的查询、不携带新字段的请求，行为与之前完全一致；
- 原有提交类接口（入账/标记/规则发布）的状态机与 HTTP 映射不变；
- 唯一行为变化：查询类接口的边界情况由异常（500 或含糊错误）改为上述可机读拒绝
  （400/422 + `reason`），正常入参的响应体结构不变。

---

## 8. 配置项（`src/main/resources/application.properties`）

这些配置**仅在启动时生成初始不可变规则版本 v1**；运行期调整请走规则发布接口（生成新版本）。

| 配置项 | 默认 | 取值/含义 |
| --- | --- | --- |
| `risk.initial-effective-at` | `0` | 初始规则生效时间点（毫秒） |
| `risk.max-hops` | `3` | 最大传播跳数，`0` 表示只认风险源本身 |
| `risk.decay` | `0.5` | 每跳衰减系数，`(0,1]` |
| `risk.risk-threshold` | `0.5` | 高风险判定阈值，`(0,1]` |

---

## 9. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/transactions` | 交易入账（幂等） |
| POST | `/api/labels` | 登记风险标记（幂等） |
| POST | `/api/rules` | 发布新规则版本（严格递增、校验合法性） |
| GET  | `/api/risk/{address}?asOf=[&ruleVersion=]` | 单地址风险结论（含路径）；可选指定已发布规则版本复现 |
| GET  | `/api/risk?asOf=` | 该时点全部地址风险结论 |
| GET  | `/api/risk/compare?asOf=&baseVersion=&targetVersion=` | 同一时点两个规则版本的判定差异 |
| POST | `/api/risk/replay` | 批量历史回放：`{"address","asOfs":[...],"ruleVersion"?}` |
| GET  | `/api/funds/{address}?asOf=` | 资金流入/流出汇总 |

示例：

```bash
curl -X POST localhost:8080/api/transactions -H 'Content-Type: application/json' \
  -d '{"txId":"t1","fromAddress":"A","toAddress":"B","amount":100,"visibleAt":100}'
curl -X POST localhost:8080/api/labels -H 'Content-Type: application/json' \
  -d '{"address":"A","baseScore":1.0,"effectiveAt":0}'
curl 'localhost:8080/api/risk/B?asOf=100'
curl 'localhost:8080/api/funds/B?asOf=100'
curl -X POST localhost:8080/api/rules -H 'Content-Type: application/json' \
  -d '{"maxHops":2,"decay":0.5,"riskThreshold":0.3,"effectiveAt":500}'

# 显式用 v1 复现 t=100 的结论（不受后续版本影响）
curl 'localhost:8080/api/risk/B?asOf=100&ruleVersion=1'
# 同一时点对比 v1 与 v2 的判定差异
curl 'localhost:8080/api/risk/compare?asOf=600&baseVersion=1&targetVersion=2'
# 批量回放一段时间
curl -X POST localhost:8080/api/risk/replay -H 'Content-Type: application/json' \
  -d '{"address":"B","asOfs":[100,300,600]}'
# 边界拒绝示例：空白地址 / 无生效规则 / 版本不存在
curl 'localhost:8080/api/risk/%20?asOf=100'        # 400 INVALID_ADDRESS
curl 'localhost:8080/api/risk/B?asOf=100&ruleVersion=99'  # 422 UNKNOWN_RULE_VERSION
```

---

## 10. 验证方法

```bash
mvn test
```

| 测试类 | 覆盖内容 |
| --- | --- |
| `IdempotencyTest` | 20 线程并发重复同一笔交易：恰好 1 次 ACCEPTED、19 次 DUPLICATE、资金流只计一次；同标识内容冲突返回 REJECTED_CONFLICT 且消息含差异字段；非法交易 REJECTED_INVALID |
| `CyclePropagationTest` | 自环 `B->B` 与环路 `B->C->B`：无无限传播、无重复累加；A/B/C 分值分别为 1.0/0.5/0.25；路径与交易序列可回溯；重复计算结论稳定 |
| `SnapshotReplayTest` | 不同时间点快照隔离、未来写入不影响历史、noisy-OR 多源聚合、规则 v2 不重写历史、非法/冲突规则不生效、无规则时点显式失败 |
| `RuleVersionReplayTest` | 显式指定旧版本复现与历史结论逐位一致、不被后续版本带偏；不指定时时间点语义与最新版本判定正确；版本对比列出正常→高风险、高风险→解除与分值变化 |
| `BoundaryRejectionTest` | 六类边界拒绝（空白地址/负时间点/非法版本号/版本不存在/无生效规则/空回放请求）原因码彼此可区分、HTTP 语义明确、响应稳定可重复；正常查询路径不受影响 |
| `BatchReplayTest` | 批量回放与逐点单查完全一致、顺序与请求一致；回放边界内不受进行中写入影响（含过去时点的迟到交易与新规则）；50 批并发回放在持续写入/发版下全部稳定；单点拒绝不中断整批 |
| `ReplayApiTest` | 对比/回放/显式版本接口的 HTTP 语义与响应结构 |
| `ApplicationTests` | Spring 全链路 HTTP 语义（200/409/400）、快照风险评估与资金追踪 |

测试日志（`logs/` 或控制台）会打印：**交易流水清单、各地址风险判定、每条传播路径与贡献来源、
noisy-OR 聚合与阈值比较的判定依据**；回放场景额外打印**回放边界（ReplayScope）、
每个回放点的结论或拒绝原因、版本对比的逐地址分值变化**。
