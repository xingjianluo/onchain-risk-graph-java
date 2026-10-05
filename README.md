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
  评估返回可解释拒绝（见第 8 节 `NO_EFFECTIVE_RULE`），而不是静默的默认结论。

### 规则版本回放与影响对比（新增）

- **显式版本回放**：风险查询增加可选参数 `ruleVersion`。给定后，数据快照仍只取
  `asOf`（含）之前可见的交易/标记，但规则**严格使用该已发布版本**，不按时间点自动选择。
  - 指定旧版本复现历史：结果与当时结论一致，**不会被之后发布的版本带偏**；
  - 指定新版本复现历史：同一批历史数据是否翻盘一目了然；
  - 指定不存在的版本：拒绝 `UNKNOWN_RULE_VERSION`（404）；
  - **不传 `ruleVersion` 时行为与旧接口完全一致**（仍按 `effectiveAt <= asOf` 自动选版本）。
- **双版本差异对比** `GET /api/risk/diff`：同一 `asOf` 给 `versionA/versionB`，
  直接列出三类差异（可选 `address` 只对比单地址，否则对比快照内全部地址）：
  - `becameRisky`：A 正常 → B 高风险（**新升高风险**）；
  - `becameSafe`：A 高风险 → B 正常（**解除**）；
  - `scoreOnlyChanged`：判定未翻转但分值变化（每条含 `scoreDelta` 与两版完整路径依据）。

### 批量历史回放（新增）

- `POST /api/risk/replay` 一次给一组点位 `{"points":[{"address","asOf"}, ...]}`，
  点位串行、按请求顺序评估，返回顺序与请求**严格一致**；
- 每个点位独立取 `asOf` 快照，结论与对应的单点查询（单地址或全量）**逐字段一致**；
  单点位地址缺省表示该时点全量；单次最多 `500` 个点位。

#### 批次级一致性保证（commit fence）

- 一批回放**开始瞬间**在全局提交水位钟（`CommitClock`）上固定一个水位 `fence`，
  整批所有点位只见 **`seq <= fence` 的交易、风险标记与规则版本**；
  每个点位自己的 `visibleAt/effectiveAt <= asOf` 时间点过滤照常叠加生效。
- **隔离范围**：回放进行期间并发入账的交易、并发登记的风险地址、并发发布的规则版本，
  提交序号必然大于 `fence`，对**整批**一律不可见——即使它们自带的
  `visibleAt/effectiveAt` 落在本批回放的时间点范围内（“自称时间戳在过去”），
  也不能改写任何历史时点的结论、不能顶替该时点原本应选用的规则版本。
- 因此同一次回放里，不管点位怎么排、重复跑多少遍，每个点位的结论都与
  **回放开始那一刻**逐点单独查到的结果逐字段一致。
- 隔离是**批次级**的，不是永久冻结：批次结束后新提交的数据对后续查询、
  后续批次正常可见（下一轮回放以新一轮开始时刻为界）。
- 时间戳确实晚于某时点的数据（`visibleAt/effectiveAt > asOf`），
  以前不会被该时点算进来，现在依然不会——时间点过滤语义不变。
- 实现要点：写入方在 `synchronized (clock)` 临界区内“分配提交序号 + 落库”，
  水位读取与写入互斥，不会出现“半提交”被某一批看到；
  交易账本/风险标记/规则服务与批量回放共享同一个 `CommitClock` 单例（Spring 注入）。

#### 不使用批量回放时

普通单点查询（`GET /api/risk...`、`/api/funds/...`）**不携带提交水位**，
仍按原有“`asOf`（含）之前可见/生效”的时间点语义实时评估，
接口形态、参数与返回结构完全不变；显式 `ruleVersion` 版本回放与版本对比语义也不变。

### 边界查询的可解释拒绝（新增）

查询类边界情况统一返回 JSON `{"reasonCode","reason","message"}`，
原因码稳定、彼此可区分，HTTP 状态固定；正常查询路径不受影响：

| reasonCode | HTTP | 触发情况 |
| --- | --- | --- |
| `BLANK_ADDRESS` | 400 | 地址为 null、空串或仅空白 |
| `MISSING_AS_OF` | 400 | 缺少必填的 `asOf` |
| `INVALID_AS_OF` | 400 | `asOf` 为负数或不可解析 |
| `INVALID_PARAMETER` | 400 | 其它参数缺失/类型错误（含对比接口缺版本参数、请求体坏 JSON） |
| `INVALID_BATCH` | 400 | 批量点位为空、缺 `asOf`、`asOf` 负或点位超过 500 |
| `UNKNOWN_RULE_VERSION` | 404 | 显式指定的规则版本不存在或版本号非法 |
| `NO_EFFECTIVE_RULE` | 404 | 该时间点之前没有任何已生效的规则版本 |

服务层异常仍继承 `IllegalArgumentException` / `NoSuchElementException`，
旧的直接调用方（含老测试）不断言回归。

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
| GET  | `/api/risk/{address}?asOf=&ruleVersion=` | 单地址风险结论（含路径）；`ruleVersion` 可选，显式版本回放 |
| GET  | `/api/risk?asOf=&ruleVersion=` | 该时点全部地址风险结论；`ruleVersion` 可选 |
| GET  | `/api/risk/diff?asOf=&versionA=&versionB=&address=` | 两个规则版本的判定差异对比（`address` 可选） |
| POST | `/api/risk/replay` | 批量历史回放（点位数组，顺序稳定） |
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

# 用已发布的 v1 在历史时点复现结论（不随时间点自动选版本）
curl 'localhost:8080/api/risk/B?asOf=100&ruleVersion=1'
# 同一时点 v1 vs v2 的影响对比：谁新升高风险、谁解除、分值各变多少
curl 'localhost:8080/api/risk/diff?asOf=400&versionA=1&versionB=2'
# 只对比单地址
curl 'localhost:8080/api/risk/diff?asOf=400&versionA=1&versionB=2&address=B'
# 批量历史回放（address 缺省表示该时点全量）
curl -X POST localhost:8080/api/risk/replay -H 'Content-Type: application/json' \
  -d '{"points":[{"asOf":50},{"address":"B","asOf":150},{"address":"B","asOf":400}]}'
```

边界拒绝示例：

```bash
curl -i 'localhost:8080/api/risk/B'                 # 400 MISSING_AS_OF
curl -i 'localhost:8080/api/risk/B?asOf=-1'        # 400 INVALID_AS_OF
curl -i 'localhost:8080/api/risk/B?asOf=10&ruleVersion=9'  # 404 UNKNOWN_RULE_VERSION
# => {"reasonCode":"MISSING_AS_OF","reason":"MISSING_AS_OF","message":"缺少必填参数 asOf（快照时间点，毫秒）"}
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
| `SnapshotReplayTest` | 不同时间点快照隔离、未来写入不影响历史、noisy-OR 多源聚合、规则 v2 不重写历史、非法/冲突规则不生效、无规则时点显式失败（老用例，持续不回归） |
| `RuleVersionReplayTest` | **显式旧版本回放与当时历史结论一致、不被后发布版本带偏；指定新版本复现历史可翻盘；最新版本时点结果不被算错；v1↔v2↔v3 差异对比列出新升高风险/解除/分值变化（含分值增量与两侧路径依据）；未知版本拒绝** |
| `BoundaryRejectionServiceTest` | **服务层：空白地址、负 asOf、无生效规则、未知版本、空批次等拒绝彼此可区分，且兼容旧异常父类型** |
| `BoundaryRejectionHttpTest` | **HTTP 层：BLANK_ADDRESS / MISSING_AS_OF / INVALID_AS_OF / INVALID_PARAMETER / INVALID_BATCH / UNKNOWN_RULE_VERSION / NO_EFFECTIVE_RULE 各自状态码与原因码稳定可区分，正常查询仍 200** |
| `BatchReplayTest` | **批量回放逐点位与单点查询逐字段一致、返回顺序随请求稳定；3 个写线程持续入账/登记/发布规则期间，历史点位回放 20 轮结论全部不变；空批次/缺 asOf/超上限拒绝** |
| `BatchReplayIsolationTest` | **批次级一致性（commit fence）：回放进行中注入“时间戳落在回放区间内”的交易/风险地址/规则版本，整批 4 个点位与回放开始前逐点查询逐字段一致（含对照组证明无隔离时结论必被改写）；多线程并发写入与 100 点位长批次赛跑结果稳定；多轮重复回放各自以该轮开始时刻为界；两条对照边界——无并发写入时与单点查询一致、时间戳确实更晚的写入依旧被 asOf 过滤** |
| `ApplicationTests` | Spring 全链路 HTTP 语义（200/409/400）、快照风险评估与资金追踪 |

也可只跑批量回放相关测试：

```bash
mvn test -Dtest='BatchReplayTest,BatchReplayIsolationTest'
```

测试日志同时输出到控制台与 `logs/tests.log`，可看到：
**覆盖了哪些场景（`========== 场景N ... ==========` / `[边界N]`）、每条用例的输入结果、
新回放场景的判定依据（批次提交水位 `fence=`、注入的并发写入及其状态、
逐点位与基线逐字段比较、显式版本/阈值、传播路径、贡献分公式 `base*decay^hops`、
新升高风险/解除清单及 noisy-OR 与阈值比较）**。

### 本地手工验证（启动后）

```bash
mvn spring-boot:run
# 另一个终端：按第 6 节示例依次执行入账/标记/发布规则/版本回放/版本对比/批量回放，
# 再用缺 asOf、负 asOf、未知 ruleVersion 等请求观察 7 类可区分拒绝。
```

手工验证批次级隔离（两个终端）：

```bash
# 终端 A：对一个较大的点位数组发起批量回放（点位越多窗口越长）
curl -X POST localhost:8080/api/risk/replay -H 'Content-Type: application/json' \
  -d '{"points":[{"asOf":50},{"address":"B","asOf":150},{"address":"C","asOf":250},{"asOf":250}]}'
# 终端 B（在 A 返回之前）：写入“时间戳落在回放区间内”的数据
curl -X POST localhost:8080/api/transactions -H 'Content-Type: application/json' \
  -d '{"txId":"p1","fromAddress":"P","toAddress":"C","amount":5,"visibleAt":150}'
# A 的结果与写入前逐点单查完全一致；之后再单查 C?asOf=250 才会看到 p1 的影响
# 服务日志中每批回放都打印 fence=水位值，可对照入账日志确认写入发生在批次开始之后
```
