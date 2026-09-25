# 本地撮合与执行引擎

基于内存订单簿 + 本地追加式流水（journal）模拟撮合、账户与成交流水，
保证并发下单、撤改单与成交重放都能得到一致且可解释的结论。

## 模块结构

```
domain/       订单、成交、状态与可区分拒绝原因
account/      账户（现金/持仓各分可用与冻结）
engine/       订单簿（价格优先、时间优先）与撮合引擎（单锁串行化）
risk/         下单前风控（数量、余额/持仓、价格合理性）
persistence/  本地追加式流水，重启重放
api/          REST 接口（下单/撤单/查询）
```

## 撮合规则

- **价格优先、时间优先**：买簿按价格降序、卖簿按价格升序，同价位按撮合序号先进先出。
- **成交价**：一律取对手方（maker）限价。
- **限价单**：未成交剩余数量挂入订单簿，可撤单。
- **市价单**：立即与对手方最优价位依次成交；剩余未成交部分自动撤销并解冻，
  不残留在簿中。市价买单按风控估算价（基准价×(1+偏离阈值)）冻结资金，
  且撮合时不接受高于估算价的对手价，防止透支。
- **部分成交**：允许；剩余数量可撤单，撤单只解冻、不扣款。

## 风控阈值（application.properties 可调）

| 配置 | 默认 | 含义 |
|---|---|---|
| `risk.max-order-quantity` | 100000 | 单笔最大数量 |
| `risk.max-price-deviation-bps` | 2000 | 限价单价格相对基准价最大偏离（万分比，2000=20%） |
| `risk.reference-price` | 10000 | 无成交时的基准价（有成交后用最近成交价） |
| `risk.default-cash` / `risk.default-position` | 1000万 / 100万 | 模拟账户初始资金/持仓 |

风控只读校验、不改动账户；校验通过后由引擎在锁内原子冻结，
因此并发下不会突破限额或出现负余额。超限拒绝原因可区分：
`EXCEED_MAX_ORDER_QUANTITY` / `INSUFFICIENT_BALANCE` /
`INSUFFICIENT_POSITION` / `PRICE_OUT_OF_RANGE`。

## 幂等与重放语义

- **下单幂等**：`clientOrderId` 去重，重复提交返回
  `DUPLICATE_CLIENT_ORDER_ID`，不产生重复成交或重复扣款。
- **撤单拒绝可区分**：`ORDER_NOT_FOUND` / `ORDER_ALREADY_FILLED` /
  `ORDER_ALREADY_CANCELED` / `ORDER_ALREADY_REJECTED`。
- **成交幂等**：每笔成交有全局唯一 `tradeId`，重复投递（`applyTrade`）
  或重复重放同一事件不会重复结算。
- **重放一致性**：订单受理（O）、成交（T）、撤单（C）逐行追加到
  `journal.path`；重启后按序重放恢复账户、订单状态与订单簿，
  可见状态与重启前一致。流水事件均幂等，重复执行重放安全。
- **状态守恒**：成交只改变资金/持仓在账户间与可用/冻结间的分布，
  全系统现金与持仓总量不变；测试对此逐场景断言。

## REST 接口

```
POST   /api/orders            下单 {clientOrderId, accountId, side, type, price, quantity}
DELETE /api/orders/{orderId}  撤单
GET    /api/orders/{orderId}  查询订单
GET    /api/orders/accounts/{accountId} 查询账户
```

## 验证方法

```bash
mvn test
```

- `MatchingEngineTest`：价格/时间优先、市价单、部分成交后撤单、
  撤单拒绝原因可区分、重复提交幂等、风控拒绝且状态不变、成交守恒。
- `ConcurrencyTest`：并发卖出不超持仓、并发买入不超余额且无负余额、
  同一 clientOrderId 并发提交只生效一次、并发撮合后总量守恒。
- `ReplayTest`：重放后账户/订单/最近成交价与重启前一致、
  重复投递同一成交只结算一次、重复重放幂等。

单测与引擎日志均打印订单标识、判定依据与账户快照（`Account{...}`），
可用 `mvn test | grep ASSERT` 查看关键断言依据。
