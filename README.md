# 本地订单撮合与执行引擎（Order Matching Engine）

基于 Spring Boot 3.4 / Java 21 构建的**单机本地撮合与结算**示例：用内存订单簿 +
本地 JSON Lines 事件流水，模拟限价单/市价单撮合、账户资金与持仓、成交流水、
撤改单以及“重启重放”，保证并发下的一致性、幂等性与状态守恒。

> 所有金额与数量均为 **long 定点数：1 = 0.0001**（即 `SCALE=10000`），避免浮点误差。
> 名义额 `notional = price * quantity / 10000`。

---

## 1. 目录结构

```
src/main/java/com/github/h3yow/ordermatchingenginejava/
├── domain/          # Side/OrderType/OrderStatus/RejectReason、Order、Fill、Account、Position、异常
├── engine/          # OrderBook（价格优先+时间优先）、MatchingEngine（撮合）、Match
├── risk/            # RiskValidator（下单前风控，纯读）
├── store/           # Event / EventStore / FileEventStore（JSON Lines 追加+重放读取）
├── service/         # TradingService（串行锁门面、冻结、结算、幂等、重放）、结果 DTO
├── api/             # REST Controller + 统一异常处理（422 + rejectReason）
└── config/          # TradingProperties（trading.* 配置）
src/test/java/.../tests/
├── MatchingEngineTest.java      # 撮合规则：价格/时间优先、部分成交、市价保护
├── MarketOrderTest.java         # 市价部分成交撤销、无流动性拒绝
├── TradingServiceTest.java      # 幂等下单、撤单拒绝原因、风控、守恒
├── ConcurrencyTest.java         # 16/20 线程并发买入、并发重复 clientOrderId、撤单竞争
├── ReplayConsistencyTest.java   # 重启重放一致、重复成交投递幂等、资金/持仓守恒
└── AmendOrderTest.java          # 改单：成功路径、可区分拒绝、幂等、即时成交、并发、重放
```

---

## 2. 撮合规则

### 2.1 价格优先、时间优先
- 买盘按价格**从高到低**、卖盘按价格**从低到高**排序（`TreeMap`）。
- 同一价格用 FIFO 队列，入队顺序由单调递增的接收序号 `seq` 决定。
- 成交价取 **maker（挂单方）价格**。

### 2.2 限价单 / 市价单
- **限价 BUY**：只与 `卖价 <= 限价` 的盘口成交；**限价 SELL**：只与 `买价 >= 限价` 成交。
- **市价单**：不限价格，但每一档仍须通过参考价滑点保护（`maxDeviationBps`），
  一旦某档不通过即停止（更深档位只会更差）。
- 限价单有剩余 → **留在订单簿**继续挂单；市价单有剩余 → **不入簿，剩余数量立即撤销**
  并回补冻结；若一笔都未成交，返回 `MARKET_NO_LIQUIDITY`。
- 支持**部分成交**：`filledQty` 递增、`remainingQty = quantity - filledQty`。

### 2.3 生命周期
```
NEW（簿内，含部分成交） ──全部成交──▶ FILLED
        │ │
        │  └──改单──▶ NEW（新价/新量、新 seq 排队尾；若与盘口交叉则先即时成交）
        │
         └──撤单──▶ CANCELED（可发生在部分成交之后，仅撤剩余数量）
下单请求未通过风控 ──▶ REJECTED（不分配 orderId、不写流水、账户零变化）
```

---

### 2.4 改单（Amend）

对在簿限价单的**改价、改量或同时改**，无需先撤再下，但**失去原时间优先**。

**能改什么**
- 仅 `NEW` 且仍有剩余数量的**限价单**；`price`/`quantity` 传 `null` 表示该项不变。
- `quantity` 是**新的订单总数量（含已成交部分）**，必须大于 `filledQty`；
  已成交部分不受影响，改的是剩余未成交部分（新剩余 = 新总量 − 已成交）。
  想撤掉全部剩余请用撤单接口。

**不能改什么（拒绝原因可区分，HTTP 422）**

| 场景 | rejectReason |
| --- | --- |
| 订单不存在 | `ORDER_NOT_FOUND` |
| 订单已全部成交 | `ORDER_ALREADY_FILLED` |
| 订单已撤销 | `ORDER_ALREADY_CANCELED` |
| 非在簿限价单（如市价单，防御分支） | `ORDER_NOT_AMENDABLE` |
| 新总量 ≤ 已成交量 | `INVALID_QUANTITY` |
| 新价 ≤ 0 / 偏离参考价超 band | `INVALID_PRICE` / `PRICE_OUT_OF_BAND` |
| 新总量 > `max-order-quantity` | `QUANTITY_EXCEEDS_LIMIT` |
| 改后冻结超可用现金/可卖持仓 | `INSUFFICIENT_AVAILABLE` |
| 改后净持仓超限 | `POSITION_LIMIT_EXCEEDED` |

**排队与即时成交**
- 改单生效即分配**新 `seq`**：从原价格档位出队，按新价格排到**同价位队尾**，
  不能靠反复改单赖在队首。
- 改单后订单作为 **taker 立即撮合**：改后价格与盘口交叉的部分按 maker 价立即成交
  （复用既有撮合与结算），剩余以新价重新挂簿；全部成交则状态变 `FILLED`。

**与下单同一套风控、同一套冻结**
- 流程：释放旧剩余冻结 → 用“新剩余 + 新价”的影子单走 `RiskValidator.validate`
  （与下单完全相同的校验项）→ 通过才落 `ORDER_AMENDED` 流水并按新剩余重新冻结。
- **拒绝零副作用**：风控拒绝时精确回补旧冻结，订单、账户、订单簿与改单前完全一致。
- 买单冻结单价记录在订单的 `frozenPrice` 上，成交释放按
  `freeze(成交前剩余) − freeze(成交后剩余)` 望远镜式重算，任意时刻残留冻结恰好
  等于 `freeze(remainingQty)`，与重放口径逐字段一致（定点截断也不会漂移）。

**幂等（clientAmendId）**
- 幂等键：`orderId + "|" + clientAmendId`（请求必带 `clientAmendId`）。
- 同一改单请求重复提交（含网络重试、重启后重投）：返回首次结果，`idempotent=true`，
  `fillIds=[]`，不重复改单、不重复调整冻结、不重复刷新排队序号。
- 改单、撤单、成交、下单全部在同一把串行锁内执行：并发改单/撤单/成交的最终状态
  必然自洽（要么先改后撤、要么先撤后改被拒 `ORDER_ALREADY_CANCELED`），
  不会出现重复冻结或“又改又撤”的矛盾状态。

---

## 3. 账户模型与资金/持仓守恒

| 字段 | 含义 |
| --- | --- |
| `cash` | 现金总额 |
| `availableCash` | 可用现金（挂买单冻结后减少） |
| `positions[s].quantity` | 标的总持仓 |
| `positions[s].available` | 可卖持仓（挂卖单冻结后减少） |

**冻结与结算（实时路径）**
- 下买单：按**最坏价**冻结资金
  - 限价单最坏价 = 限价；市价单最坏价 = 参考价 × (1 + 滑点)。
  - `availableCash -= qty * worstPrice / 10000`，最坏价记入订单 `frozenPrice`。
  - 成交时 `cash -= 实际成交额`，释放冻结 `freeze(成交前剩余) − freeze(成交后剩余)`
    与实际额之差**回补** `availableCash`；撤单/市价剩余则把未成交部分的冻结**全额回补**。
- 下卖单：`position.available -= qty` 冻结持仓。
  - 成交时 `position.quantity -= qty`（`available` 已在下单时扣减，成交不再变）；
    撤单则把未成交部分 `available += qty` 回补。
- 改单：先按旧 `frozenPrice` 回补旧剩余冻结，风控通过后再按新价/新剩余重新冻结
  （详见 2.4）。

**引擎在每次写操作后断言不变量**（违反即抛出 `IllegalStateException`）：
- `availableCash >= minAvailableCash`（默认 0，**永不出现负余额**）；
- `0 <= position.available <= position.quantity`。
- 全系统现金守恒：买卖双方现金变化之和为 0；标的持仓双边对冲：买卖双方持仓变化之和为 0。

---

## 4. 下单前风控（拒绝原因可区分，且账户零变化）

`RiskValidator` 为**纯读**校验，先校验、后冻结，拒绝时不分配 orderId、不写事件、不动账户。

| 校验 | 拒绝原因 `RejectReason` |
| --- | --- |
| 数量 <= 0 | `INVALID_QUANTITY` |
| 限价 <= 0 | `INVALID_PRICE` |
| 数量 > `trading.max-order-quantity` | `QUANTITY_EXCEEDS_LIMIT` |
| 限价相对参考价偏离 > `trading.max-deviation-bps` | `PRICE_OUT_OF_BAND` |
| 买单冻结后可用现金 < 0 | `INSUFFICIENT_AVAILABLE` |
| 卖单数量 > 可卖持仓 | `INSUFFICIENT_AVAILABLE` |
| 成交后净持仓绝对值 > `trading.max-position` | `POSITION_LIMIT_EXCEEDED` |
| 市价单完全无成交 | `MARKET_NO_LIQUIDITY` |
| 账户不存在 | `UNKNOWN_ACCOUNT` |

> 因为所有写操作都在 `TradingService` 的**同一把锁**内串行完成“校验 → 冻结 →
> 持久化 → 撮合 → 结算”，并发下单不可能两个请求同时通过同一份余额的校验，
> 因而不会突破限额或出现负余额（由 `ConcurrencyTest` 验证）。

---

## 5. 幂等语义

### 5.1 下单幂等（clientOrderId）
- 幂等键：`accountId + "|" + clientOrderId`。
- 同一账户重复提交相同 `clientOrderId`：返回**第一次**的订单结果，`idempotent=true`，
  `fillIds=[]`，**不产生新成交、不重复扣款、不重复冻结**。
- 注意：不同账户使用相同 clientOrderId 互不影响。

### 5.2 撤单的可区分拒绝
对撤单请求返回明确原因（HTTP 422）：

| 场景 | rejectReason |
| --- | --- |
| 订单不存在 | `ORDER_NOT_FOUND` |
| 订单已全部成交 | `ORDER_ALREADY_FILLED` |
| 订单已撤销（含部分成交后撤单） | `ORDER_ALREADY_CANCELED` |

撤单成功仅撤销**剩余数量**，已成交部分不可逆，并回补剩余冻结。

### 5.3 成交事件幂等（fillId）
- 一次撮合生成两条**账户侧流水 `Fill`**（taker 一条、maker 一条），共享同一 `fillId`。
- 幂等键：`fillId + "|" + ownerAccountId`。
- 内部撮合、外部成交投递（`POST /api/trading/fills/deliver`）、重启重放共用同一张
  已结算表；**同一成交事件重复投递/重复重放绝不重复结算**。

### 5.4 改单幂等（clientAmendId）
- 幂等键：`orderId + "|" + clientAmendId`，规则与下单幂等一致（见 2.4）：
  重复提交返回首次结果，不重复改单/冻结/刷新排队序号；
  幂等表随 `ORDER_AMENDED` 流水重放恢复，重启后重投仍命中。

---

## 6. 持久化与重放（Event Sourcing）

### 6.1 事件流水
- 存储：`trading.event-log-dir/events.jsonl`，每行一个 JSON（JSON Lines），同步追加 flush。
- 四类事件：`ORDER_ACCEPTED`（完整订单快照）、`FILL`（账户侧成交流水）、
  `ORDER_CANCELED`、`ORDER_AMENDED`（改后完整快照：新价/新总量/已成交/新 seq/clientAmendId）。
- **先写流水，再改内存状态**，因此不会出现“已扣款但无流水”。

### 6.2 重放
启动时严格按写入顺序重放：
1. `ORDER_ACCEPTED`：装入订单表与 clientOrderId 索引；
2. `FILL`：按 fillId+owner 去重，推进订单 `filledQty` 并把账户从种子状态累计
   （直接口径：买扣款增仓、卖收款减仓）；
3. `ORDER_CANCELED`：推进订单状态为 CANCELED；
4. `ORDER_AMENDED`：把改后快照（价格/总量/已成交/新 seq）覆盖到订单，
   并重建改单幂等表（`orderId+clientAmendId`）；
5. 全部事件应用完后：
   - 仍为 `NEW` 且有剩余的限价单**按 seq 升序**重新挂入订单簿
     （改单刷新过 seq，同价位排队顺序因此与重启前逐字段一致）；
   - 为这些活单**补建冻结**（买按改后限价冻结剩余资金、卖冻结剩余持仓，
     并恢复订单 `frozenPrice`），得到与重启前完全一致的 `availableCash` / 可卖持仓。

重放结束后可见状态（账户快照、订单价格/数量/成交量/状态/seq、订单簿各档位排队顺序、
下单与改单幂等索引）与重启前一致，由 `ReplayConsistencyTest` 与
`AmendOrderTest.amendReplayConsistency` 端到端验证。

---

## 7. 风控阈值配置（`application.properties`）

```properties
# 定点单位 1 = 0.0001
trading.max-order-quantity=1000000   # 单笔最大数量 = 100
trading.max-position=10000000        # 净持仓绝对值上限 = 1000
trading.max-deviation-bps=500        # 价格偏离/市价滑点保护 = 5%
trading.min-available-cash=0         # 可用现金下限（禁止负余额）
trading.event-log-dir=data/trading-log
trading.seed-accounts.alice=10000000000
trading.seed-accounts.bob=10000000000
trading.seed-positions.alice__BTC=1000000   # key 格式：accountId__symbol
```

参考价（用于价格合理性与市价保护）取**最近成交价**；尚无成交时可通过
`TradingService#setReferencePrice(symbol, price)` 注入（测试/初始化用）。

---

## 8. REST API

| 方法与路径 | 说明 |
| --- | --- |
| `POST /api/trading/orders` | 下单（body：clientOrderId/accountId/symbol/side/type/price/quantity） |
| `POST /api/trading/orders/{orderId}/amend` | 改单（body：clientAmendId/price/quantity，null 表示该项不变） |
| `POST /api/trading/orders/{orderId}/cancel` | 撤剩余数量 |
| `POST /api/trading/fills/deliver` | 外部成交事件投递（fillId+owner 幂等） |
| `GET  /api/trading/orders/{orderId}` | 订单详情 |
| `GET  /api/trading/accounts/{accountId}` | 账户快照（现金/可用/持仓） |
| `GET  /api/trading/symbols/{symbol}/book` | 订单簿快照（bids/asks） |

- 业务拒绝返回 **HTTP 422**，body：`{"rejectReason":"...","message":"..."}`。
- 参数错误返回 HTTP 400。

下单示例：
```bash
curl -X POST http://localhost:8080/api/trading/orders \
  -H 'Content-Type: application/json' \
  -d '{"clientOrderId":"c-1","accountId":"bob","symbol":"BTC",
       "side":"BUY","type":"LIMIT","price":10000,"quantity":100}'
```

改单示例（把 1 号单改价为 9900、改总量为 200；quantity 含已成交部分）：
```bash
curl -X POST http://localhost:8080/api/trading/orders/1/amend \
  -H 'Content-Type: application/json' \
  -d '{"clientAmendId":"a-1","price":9900,"quantity":200}'
```

---

## 9. 验证方法

### 9.1 运行全部测试
```bash
mvn test
```
共 28 个用例（6 个测试类），覆盖：

| 测试类 | 覆盖点 |
| --- | --- |
| `MatchingEngineTest` | 价格优先、同价 FIFO 时间优先、部分成交入簿、市价保护带、不交叉不成交 |
| `MarketOrderTest` | 市价部分成交剩余撤销并回补冻结、无流动性拒绝、无持仓卖单拒绝 |
| `TradingServiceTest` | clientOrderId 幂等、撤单三类拒绝原因、部分成交后撤单冻结回补、超数量/价格偏离/资金不足拒绝且账户不变、卖空拒绝 |
| `ConcurrencyTest` | 16 线程并发吃单不超卖且无负余额；20 线程相同 clientOrderId 仅成交一次；并发撤单竞争返回 ALREADY_FILLED |
| `ReplayConsistencyTest` | 重启重放后账户/订单簿/幂等索引完全一致；重复投递 fillId 只结算一次（重启后仍去重）；现金总量与持仓双边守恒 |
| `AmendOrderTest` | 改价/改量冻结重算且失去队首、部分成交后改量、八类可区分拒绝且零副作用、同 clientAmendId 幂等、改价交叉即时成交、并发改单/撤单/成交竞争自洽、改单混合流水重启重放逐字段一致（含排队顺序与改单幂等表） |

测试日志（控制台 INFO）按要求打印**订单标识**（orderId/clientOrderId/fillId）、
**判定依据**（命中的规则/拒绝原因）与**账户快照**（现金、可用现金、各标的持仓总量与可卖量），例如：
```
order handled: orderId=2, clientOrderId=b1, side=BUY, ..., fillIds=[1],
  snapshot=AccountSnapshot{account=bob, cash=9999999850, availableCash=9999999850,
  positions={BTC=qty=150,avail=150}}
[cancelReasons] filledOrder=2 判定=ORDER_ALREADY_FILLED; canceledOrder=3 判定=ORDER_ALREADY_CANCELED
[concurrentDup] threads=20 idempotentHits=19 realFilled=10000 ...
```

### 9.2 手工端到端冒烟
```bash
mvn -DskipTests package
java -jar target/order-matching-engine-java-1.0-SNAPSHOT.jar \
     --trading.event-log-dir=/tmp/ome/events \
     --trading.seed-accounts.alice=10000000000 \
     --trading.seed-accounts.bob=10000000000 \
     --trading.seed-positions.alice__BTC=1000000
# 下单/成交后直接 kill 进程，再用同一 --trading.event-log-dir 重启，
# GET 账户与订单即可看到与重启前一致的状态。
```

### 9.3 查看持久化流水
```bash
cat data/trading-log/events.jsonl
```
每行一个事件，可用任意 JSON 工具检查；删除该文件即“清空环境从零开始”。

---

## 10. 范围与说明
- 本实现为**单 JVM 单实例**模型：并发安全依赖进程内串行锁；多实例部署需要把锁、
  顺序号与事件存储换成外部组件（如数据库/分区日志），语义保持一致。
- 不实现手续费、自成交预防（crash-only 场景下订单状态以前序事件为准）、撮合后的
  行情发布；最近成交价已作为参考价供风控使用。

### 10.1 改单能力的兼容性边界
- **对既有行为无回退**：下单/撤单/风控/幂等/成交投递/重放的对外语义不变，
  既有 20 个用例全部继续通过。
- 事件流水**向后兼容**：旧流水（无 `ORDER_AMENDED`、订单无 `amendId`/`frozenPrice`
  字段）可正常重放——缺失字段按 null/0 处理，活单冻结单价在重放补冻结时恢复为限价。
- 新流水包含 `ORDER_AMENDED` 事件，**不可**用旧版本代码重放（旧版本不认识该事件类型）。
- 改单不引入新的订单状态：订单仍是 NEW/FILLED/CANCELED/REJECTED 四态，
  改单只是在 NEW 内的价格/数量/序号迁移（可能触发成交进入 FILLED）。
