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

## 5. 配置项（`src/main/resources/application.properties`）

这些配置**仅在启动时生成初始不可变规则版本 v1**；运行期调整请走规则发布接口（生成新版本）。

| 配置项 | 默认 | 取值/含义 |
| --- | --- | --- |
| `risk.initial-effective-at` | `0` | 初始规则生效时间点（毫秒） |
| `risk.max-hops` | `3` | 最大传播跳数，`0` 表示只认风险源本身 |
| `risk.decay` | `0.5` | 每跳衰减系数，`(0,1]` |
| `risk.risk-threshold` | `0.5` | 高风险判定阈值，`(0,1]` |

---

## 6. HTTP 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/transactions` | 交易入账（幂等） |
| POST | `/api/labels` | 登记风险标记（幂等） |
| POST | `/api/rules` | 发布新规则版本（严格递增、校验合法性） |
| GET  | `/api/risk/{address}?asOf=` | 单地址风险结论（含路径） |
| GET  | `/api/risk?asOf=` | 该时点全部地址风险结论 |
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
```

---

## 7. 验证方法

```bash
mvn test
```

| 测试类 | 覆盖内容 |
| --- | --- |
| `IdempotencyTest` | 20 线程并发重复同一笔交易：恰好 1 次 ACCEPTED、19 次 DUPLICATE、资金流只计一次；同标识内容冲突返回 REJECTED_CONFLICT 且消息含差异字段；非法交易 REJECTED_INVALID |
| `CyclePropagationTest` | 自环 `B->B` 与环路 `B->C->B`：无无限传播、无重复累加；A/B/C 分值分别为 1.0/0.5/0.25；路径与交易序列可回溯；重复计算结论稳定 |
| `SnapshotReplayTest` | 不同时间点快照隔离、未来写入不影响历史、noisy-OR 多源聚合、规则 v2 不重写历史、非法/冲突规则不生效、无规则时点显式失败 |
| `ApplicationTests` | Spring 全链路 HTTP 语义（200/409/400）、快照风险评估与资金追踪 |

测试日志（`logs/` 或控制台）会打印：**交易流水清单、各地址风险判定、每条传播路径与贡献来源、noisy-OR 聚合与阈值比较的判定依据**。
