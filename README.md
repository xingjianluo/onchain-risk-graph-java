# 本地交易图风险传播与资金追踪服务

用**内存账本**模拟链上交易流水，提供并发安全的交易入账、基于资金流向的风险传播、
时间点快照查询与版本化规则管理。目标是在并发入账、重复投递、环路传播、历史回溯
四类场景下，风险结论保持**一致、可复现、可解释**。

基于 Spring Boot 3.4 / Java 21，无外部数据库依赖。

---

## 1. 数据模型

| 对象 | 字段 | 说明 |
|---|---|---|
| `Transaction` | `txId, fromAddress, toAddress, amount, timestamp` | 一笔资金转移，`txId` 为全局幂等键，`timestamp` 为事件时间 |
| `RiskLabel` | `address, baseScore, reason, timestamp` | 人工/外部高风险标记，风险传播的种子，`baseScore ∈ (0,1]` |
| `RiskRule` | `version, maxHops, decay, threshold, effectiveFrom, note` | 一版不可变的风险规则 |
| `RiskConclusion` | `address, score, risky, asOf, ruleVersion, threshold, paths` | 某地址在某时点、某版规则下的风险结论 |
| `RiskPath` | `sourceAddress, baseScore, hops[], contribution` | 一条从风险源头到目标地址的可回溯贡献路径 |

---

## 2. 风险传播规则

### 2.1 方向与跳数

资金沿交易方向流动（`from → to`）。评估地址 `X` 时，引擎在快照图上做
**反向 BFS**：沿“流入 X”的边向上游寻找已被标记的风险源头。

- 源头经 **k 跳** 到达目标的单路径贡献：

  ```
  contribution = baseScore × decay^k
  ```

- 超过 `maxHops` 的上游不再扩展（默认 3 跳）。
- 目标地址自身被标记时为 **0 跳**，贡献即 `baseScore`。

### 2.2 多来源聚合（noisy-or）

多个**相互独立**的风险源头不做线性相加（会超 1 且重复计数），采用概率噪声或：

```
score = 1 − Π_i (1 − contribution_i)
```

- 同一源头若有多条可达路径，只保留**贡献最大（即最短）的一条**，
  因此环路不会让同一来源被重复累加。
- `risky = (score ≥ threshold)`。分数统一保留 3 位小数，避免浮点误差影响判定。

### 2.3 环路与自环

- 反向 BFS 维护 `visited` 集合，每个地址在一次传播中至多扩展一次：
  环路不会造成无限传播。
- 自环交易（`A → A`）命中 visited 后直接忽略，不产生额外跳与贡献。
- 邻接边按 `(timestamp, txId)` 排序后遍历，结论对输入顺序确定、可复现。

### 2.4 可解释性

每条 `RiskConclusion.paths[]` 给出全部贡献来源，按贡献降序排列；每条 `RiskPath`
含源头地址、初始分、逐跳交易（`txId`、起止地址、时间、该跳累计分）与最终贡献。
运行日志以 `[风险结论] / [判定依据] / [传播路径]` 前缀打印完整路径，例如：

```
[风险结论] address=B asOf=500 rule=test-v1 score=0.25 threshold=0.5 risky=false 贡献来源数=1
[判定依据] 源头=S baseScore=1.0 跳数=2 贡献=0.25 路径=S --e1(0.5)--> A --e2(0.25)--> B
[传播路径] 忽略自环 txId=e4 address=A（不重复累加）
```

---

## 3. 幂等与重复投递语义

账本（`InMemoryTransactionLedger`）以 `txId` 为键，使用
`ConcurrentHashMap.putIfAbsent` 保证并发提交下同一笔交易**至多计入一次**。

| 情形 | 行为 | HTTP | 区分方式 |
|---|---|---|---|
| 首次提交 | `ACCEPTED`，`applied=true`，计入资金流 | 200 | `status=ACCEPTED` |
| 同 `txId`、**内容完全一致**的重复/并发投递 | `DUPLICATE`，`applied=false`，幂等忽略 | 200 | `status=DUPLICATE` |
| 同 `txId`、**任一内容字段不一致**（付款方/收款方/金额/时间） | 拒绝，不落账 | **409** | 错误体 `code=CONFLICT` |

> 普通重复投递是**成功但不重复计入**，内容冲突是**失败拒绝**，二者通过
> HTTP 状态与错误码明确区分。非法入参（空 txId、金额 ≤ 0 等）返回
> 400 `INVALID_INPUT`。

---

## 4. 快照与历史回溯语义

- 账本与标记册均为**只追加（append-only）**，无修改、无删除。
- 快照查询 `riskOf(address, asOf, ruleVersion)` 与流水回放 `replay(asOf)`
  只读取 **事件时间 `timestamp ≤ asOf`** 的交易与标记（边界含）。
- 因此 `asOf` 之后写入的任何数据都不会改变历史时点的结论；同一 `(address, asOf,
  ruleVersion)` 无论何时查询，结果一致。
- 标记同一地址有多条时，取该时点之前**最新一条**（最新人工结论为准）。

### 规则可复现历史结论

规则以 `version` 为不可变标识、以 `effectiveFrom` 生效：

- `ruleVersion` 为空：自动选择 `effectiveFrom ≤ asOf` 中**生效最晚**的一版
  （并列取版本号最小），反映“当时的规则”。
- `ruleVersion` 非空：用**指定历史版本**规则对当时可见数据重新计算，复现旧结论。
- 每条结论都内嵌 `ruleVersion` 与当时 `threshold`，判定依据自解释。

### 非法/冲突规则不静默生效

- 注册即校验：`maxHops ∈ [0,20]`、`decay ∈ (0,1]`、`threshold ∈ [0,1]`、
  `effectiveFrom ≥ 0`、版本号非空；非法规则返回 **422 `INVALID_RULE`**，不入注册表。
- 同版本号重复提交：内容一致 → 幂等返回；内容不同 → 422 `INVALID_RULE`，
  **绝不覆盖**已生效规则。
- 内置 `default-v1`（maxHops=3, decay=0.5, threshold=0.5, effectiveFrom=0）
  保证任何时点都有确定可用规则。

---

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/transactions` | 交易入账（幂等/冲突区分） |
| POST | `/api/labels` | 新增风险源头标记 |
| POST | `/api/rules` | 注册新规则版本（非法/冲突拒绝） |
| GET  | `/api/addresses/{address}/risk?asOf=&ruleVersion=` | 时间点风险结论（ruleVersion 可省略） |
| GET  | `/api/replay?asOf=` | 回放该时点可见的交易流水 |

示例：

```bash
curl -X POST localhost:8080/api/transactions -H 'Content-Type: application/json' \
  -d '{"txId":"t1","fromAddress":"S","toAddress":"A","amount":100,"timestamp":200}'

curl 'localhost:8080/api/addresses/A/risk?asOf=500'
```

---

## 6. 规则配置项

| 配置项 | 合法范围 | 默认 | 含义 |
|---|---|---|---|
| `version` | 非空字符串，唯一 | `default-v1` | 规则版本标识，不可变 |
| `maxHops` | `[0,20]` 整数 | `3` | 风险向上游传播的最大跳数 |
| `decay` | `(0,1]` | `0.5` | 每跳衰减因子，贡献=`baseScore·decay^hops` |
| `threshold` | `[0,1]` | `0.5` | 判定 `risky` 的风险分阈值（含边界） |
| `effectiveFrom` | `≥0` epoch millis | `0` | 版本生效起点（含） |

> 默认规则由代码内置；运行期可通过 `POST /api/rules` 追加新版本，
> 不支持修改/删除历史版本。

---

## 7. 验证方法

### 运行全部测试

```bash
mvn test
```

测试日志（交易流水、传播路径、判定依据）落盘于：

```
target/surefire-reports/*-output.txt
```

### 测试覆盖矩阵

| 测试类 | 覆盖场景 | 关键断言 |
|---|---|---|
| `IdempotentIngestTest` | 16 线程并发重复入账；同 id 不同内容冲突 | 恰好 1 次 ACCEPTED、15 次 DUPLICATE、账本只增 1；冲突抛 409 `CONFLICT`，与 DUPLICATE 可区分 |
| `RiskPropagationTest` | 环路 S→A→B→A、自环 A→A；跳数衰减与 maxHops 截断；双来源 noisy-or；0 跳标记 | 环路/自环不产生额外路径、不无限传播；贡献/聚合分数精确；路径 txId 序列可回溯 |
| `SnapshotAndRuleTest` | asOf 前后写入的快照一致性；阈值改版与历史复现；非法/冲突规则 | 历史结论不被未来写入改变；显式旧版本可复现；非法/冲突规则抛 `INVALID_RULE` 且原规则不变 |
| `WebApiIntegrationTest` | HTTP 端到端 | 200 DUPLICATE vs 409 CONFLICT vs 422 INVALID_RULE；快照查询分数/路径正确 |

共 14 个测试用例（含 Spring 上下文加载）。

### 手工冒烟

```bash
mvn spring-boot:run
# 依次调用第 5 节接口，观察返回的 score/risky/paths 与错误码
```
