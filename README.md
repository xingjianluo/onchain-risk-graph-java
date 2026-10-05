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
- **批量回放额外提供“整批一致性”**：一批开始时先冻结一份覆盖交易/标记/规则的全量快照，
  回放期间并发写入（哪怕时间戳自称在回放区间内）不进入本批；细节见下文“批量历史回放”。
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

### 批量历史回放（整批一致性 / 快照隔离）

- `POST /api/risk/replay` 一次给一组点位 `{"points":[{"address","asOf"}, ...]}`，
  点位串行、按请求顺序评估，返回顺序与请求**严格一致**；单点位地址缺省表示该时点全量；
  单次最多 `500` 个点位。
- **一批回放一旦开始，这一批每个点位的结论就固定下来。**
  批次在评估前先对三类数据源各采集一次**全量不可变快照**（交易账本 / 风险地址标记 / 规则版本），
  本批所有点位都只在这同一份快照上评估。于是：
  - 回放进行期间**并发入账的交易**、**并发登记的风险地址**、**并发发布的规则版本**，
    哪怕它们自带的 `visibleAt/effectiveAt` 落在本批回放区间内（看上去“那个时点就已经存在”），
    也一律**不属于该快照**，不能进入这一批任何点位的结论，**也不会顶替该时点原本应选用的
    规则版本**；同一点位重复跑、点位任意排列，结论都与回放开始那一刻逐点单独查询的结果
    **逐字段一致（含每条传播路径与交易序列）**。
  - 每个点位仍在快照内独立按 `visibleAt/effectiveAt <= asOf` 取可见子集；
    **时间戳确实晚于该时点的数据（回放开始前就已存在的），过去不会算进来，现在也依然不会。**
  - 点位结论与“回放开始那一刻”对应的单点查询（单地址或全量）逐字段一致；点位串行、
    返回顺序只跟请求走。
- **隔离范围仅限这一次批量回放内部**：不使用批量回放的普通单点查询
  （`GET /api/risk/{address}`、`GET /api/risk`、`/api/risk/diff`、`/api/funds/...`）
  **行为完全不变**——仍是每次查询读取当时的实时状态、按时间点评估，接口形态与返回结构兼容。
  批次结束后再发起的**新一批**回放会重新采集快照，因此能看到批次之间已提交/已发布的数据
  （即不是全局冻结）。
- 隔离边界示例（见 `BatchReplaySnapshotIsolationTest`）：
  - 多轮回放并发进行，期间注入规则 `v2(effectiveAt=100)`、风险地址 `C(effectiveAt=100)`、
    交易 `g1(visibleAt=100)`：每轮各点位仍等于回放前基线（C@250 仍是 v1 下 0.25 safe，
    全量@250 仍只有 A/B/C）；而批次结束后的普通单点查询按实时语义变为 v2、C score=1 risky、
    全量含新节点 D；
  - 回放期间写入时间戳**确实更晚**（晚于全部点位）的数据：本批历史点位与该时点普通查询都不含，
    到其时间点之后的普通查询正常可见——证明“未来数据不可见”的原语义未变。

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
| `BatchReplaySnapshotIsolationTest` | **整批一致性（快照隔离）：多轮批量回放并发进行、且在回放开始后注入“时间戳落在回放区间内”的三类数据（并发入账交易 g1@100、并发登记风险地址 C@100、并发发布规则 v2@100），断言每轮每个点位（乱序+重复）都与回放开始前逐点单点查询逐字段（含传播路径）一致、返回顺序随请求；批次后普通单点查询仍按实时时间点语义看到新数据（隔离仅限本批）。对照1：无并发写入时批量=逐点查询；对照2：回放中写入时间戳确实更晚（900/950）的数据不进入历史点位，但在其时间点之后的普通查询可见** |
| `ApplicationTests` | Spring 全链路 HTTP 语义（200/409/400）、快照风险评估与资金追踪 |

测试日志同时输出到控制台与 `logs/tests.log`，可看到：
**覆盖了哪些场景（`========== 场景N ... ==========` / `[边界N]`）、每条用例的输入结果、
新回放场景的判定依据（显式版本/阈值、传播路径、贡献分公式 `base*decay^hops`、
新升高风险/解除清单及 noisy-OR 与阈值比较）**。批量快照隔离用例还会打印
`批量回放数据快照采集完成 capturedAt=...：交易 N 笔，风险标记 M 个，规则版本 K 个`、
`本批结论自此刻固定`、回放中注入了哪些区间内数据、以及“批次内不变 vs 批次后普通查询可见”
的对照判定依据，便于核对隔离是否生效。

### 本地手工验证（启动后）

```bash
mvn spring-boot:run
# 另一个终端：按第 6 节示例依次执行入账/标记/发布规则/版本回放/版本对比/批量回放，
# 再用缺 asOf、负 asOf、未知 ruleVersion 等请求观察 7 类可区分拒绝。
```

复现“批量回放期间并发写入不改写本批历史结论”，可在批量回放进行的同时并发执行：

```bash
# 1) 回放开始前，先逐点查询留底
curl 'localhost:8080/api/risk/C?asOf=250'          # 期望按当时规则（v1）出结论
curl 'localhost:8080/api/risk?asOf=250'            # 期望节点集合不含之后才加的地址
# 2) 发起批量回放（点位可重复、乱序）
curl -X POST localhost:8080/api/risk/replay -H 'Content-Type: application/json' \
  -d '{"points":[{"asOf":250},{"address":"C","asOf":250},{"asOf":250}]}'
# 3) 在该批次进行期间并发写入“自称时间戳落在回放区间内”的数据
curl -X POST localhost:8080/api/transactions -H 'Content-Type: application/json' \
  -d '{"txId":"g1","fromAddress":"A","toAddress":"D","amount":50,"visibleAt":100}'
curl -X POST localhost:8080/api/labels -H 'Content-Type: application/json' \
  -d '{"address":"C","baseScore":1.0,"effectiveAt":100}'
curl -X POST localhost:8080/api/rules -H 'Content-Type: application/json' \
  -d '{"maxHops":3,"decay":0.5,"riskThreshold":0.2,"effectiveAt":100}'
# 断言：这一批回放结果仍等于第 1 步留底（规则版本/分值/节点集合都不变）；
# 批次结束后再发一次普通查询 / 新一批回放，才会看到上面三类写入（实时时间点语义不变）。
```

> 说明：规则版本 `effectiveAt` 必须严格递增，手工复现时 `v2 effectiveAt=100` 要晚于
> 初始 v1 的 `effectiveAt=0`；自动化测试通过“快照采集闸口”确定性地把注入卡在每轮回放开始之后，
> 因此无需靠时序运气即可稳定复现旧实现会失败、修复后通过。
