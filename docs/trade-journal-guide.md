# trade-journal 交易复盘系统 · 完整说明

> 配套文档：[A 股散户学习路径（完整版）](./stock-learning-path.md) · [每日复盘模板](./stock-daily-review-template.md) · [复盘示例](./stock-daily-review-example.md)
>
> **仅为个人记录与分析工具，不构成任何投资建议。**

---

## 一、这个系统解决什么问题

Markdown 复盘模板只能回答「今天怎么样」，回答不了下面这三个问题：

- 这个月计划内和计划外的胜率差多少？
- 账户从最高点最多跌了多少？我扛不扛得住？
- 我最常犯的是哪一条纪律——追涨、改计划、还是亏损加仓？

这三个问题的答案都不在任何单独一天里，而在**多天数据的累计与差值**里。
`trade-journal` 要做的只有两件事：

| 做什么 | 不做什么 |
|---|---|
| 记录：计划、成交、每日快照、复盘文字、纪律违背 | 不接个股行情、不自动选股 |
| 统计：胜率、盈亏比、最大回撤、违背次数，全部由 SQL 与固定公式推出 | 不下单、不预测、不做策略回测（那是 akshare 的活） |

**为什么刻意不接个股行情**：一旦系统能自动拉数据、自动判断，人的决策就会外包给它，
复盘也就变成了「解释系统的输出」，失去意义。记录必须人工，统计必须确定。

唯一的例外是板块指数（`t_sector_daily`）——它只是对照组，用来判断「选错股」还是「踩错方向」，
不参与任何买卖判断，也不和个人记录产生关联。

---

## 二、为什么单日模板不够用

[每日复盘模板](./stock-daily-review-template.md) 是一张「当天的表」，不是数据库。它的设计边界很明确：

| 模板能做 | 模板做不了 |
|---|---|
| 盘前写计划，盘后填执行偏差 | 跨月的胜率与盈亏比 |
| 当天持仓的仓位占比 | 每日净值序列 → 最大回撤 |
| 当天的纪律自检 | 「哪条纪律最常犯」的排序 |
| 一句话总结 | 计划内 vs 计划外的胜率差 |

如果硬把模板当数据库用（同一个 sheet 往下堆日期），两周后一定会出现：
公式引错行、复权后成本对不上、卖出时忘记扣费用、净值漏算现金。

**正确分工**：模板负责「当天怎么想」，系统负责「多天算得准」。两者不冲突。

---

## 三、数据放在哪里：Excel 还是数据库

### 3.1 迁移门槛

| 阶段 | 用什么 | 原因 |
|---|---|---|
| 前 20 个交易日 | Excel / 飞书表格 + 复盘模板 | 摩擦最低。此阶段的目标是「每天写」，不是统计精度 |
| 超过 30 笔，或开始分批建仓 | 本系统（MySQL） | 加权成本、两边费用、每日净值手算必错，且错的方向固定（胜率偏高） |
| 手机速记 | 备忘录 | 只当草稿，当天晚上录进系统，不长期维护两份数据 |

### 3.2 为什么 Excel 一定会错

| 项目 | Excel 常见错法 | 后果 |
|---|---|---|
| 手续费 | 只记「卖出价 - 买入价」 | 胜率系统性偏高，短线策略尤其明显 |
| 分批建仓 | 用均价或最后一笔价格当成本 | 已实现盈亏失真 |
| 每日净值 | 只算持仓市值，漏掉现金 | 回撤算错，通常偏大或偏小且无法察觉 |
| 仓位占比 | 用今天的净值除历史市值 | 历史仓位全部失真 |

系统的做法是把这些口径**写死在代码里**，只有一套算法，不存在「这周用这个公式、下周用那个公式」。

---

## 四、数据模型

### 4.1 表之间的关系

```
t_trade_plan ──(plan_id)──┐
                          ▼
                    t_trade（成交流水）
                          │ 买入/卖出时实时维护
                          ▼
                    t_position（当前持仓，一标的一行）

每个交易日收盘提交一次复盘：
  t_position ──► t_position_daily（当天快照，一天一只一行）
  现金 + 市值 ──► t_account_daily（当天净值，一天一行）
  POST /reviews ──► t_daily_review（当天文字，一天一条）
                    └► t_discipline_violation（当天违背明细，一违一条）

独立参考数据（只读，不参与个人记录）：
  t_sector_daily（板块行情，一天一板块一行）
```

**关键点：日复盘是入口，不是主表。** 所有统计都从 `t_trade` 与 `t_account_daily` 推出来，
复盘表只存「文字」和「违背次数」这类无法结构化的内容。

### 4.2 八张表

#### t_trade_plan —— 盘前计划

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| plan_date | DATE | 计划日期 |
| symbol / name | VARCHAR | 代码 / 名称 |
| trigger_desc | VARCHAR | 若……（例如「跌破 18.20」） |
| action_desc | VARCHAR | 则……（例如「卖出全部 500 股」） |
| reason | VARCHAR | 依据，必须可证伪，允许为空但会告警 |
| status | VARCHAR | PENDING / EXECUTED / CANCELLED |

**不提供删除接口**：删掉就无法统计「计划了几条、执行了几条」。

#### t_trade —— 成交流水（唯一事实来源）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| trade_date | DATE | 成交日期 |
| symbol / name | VARCHAR | 代码 / 名称 |
| side | VARCHAR | BUY / SELL |
| price / quantity | DECIMAL / INT | 成交价 / 数量 |
| fee | DECIMAL | 佣金 + 印花税 + 过户费合计 |
| realized_pnl | DECIMAL | 仅卖出有值 |
| is_planned | TINYINT | 是否计划内，默认 1 |
| plan_id | BIGINT | 关联计划，可为空 |
| reason | VARCHAR | 买入理由，必填且必须可证伪 |
| sell_condition | VARCHAR | 买入时写下的卖出条件 |

#### t_position —— 当前持仓

| 字段 | 类型 | 说明 |
|---|---|---|
| symbol | VARCHAR | 主键（一标的一行） |
| name | VARCHAR | 名称 |
| quantity | INT | 当前数量 |
| avg_cost | DECIMAL(18,4) | 加权成本，**已含买入费用** |

数量减到 0 时直接删行，不留 `quantity = 0` 的僵尸记录。

#### t_position_daily —— 每日持仓快照

| 字段 | 类型 | 说明 |
|---|---|---|
| snapshot_date + symbol | DATE + VARCHAR | 唯一键 |
| quantity / avg_cost | INT / DECIMAL | 当时的数量与成本 |
| close_price | DECIMAL | 当日收盘价 |
| position_ratio | DECIMAL | 市值 / 当日净值，**写入时算好** |
| stop_loss / take_profit | DECIMAL | 止损位 / 止盈位 |
| logic_valid | TINYINT | 买入逻辑是否仍成立 |

`position_ratio` 必须在写入时算：净值每天都在变，用今天的净值去除历史市值会把历史仓位全算错。

#### t_account_daily —— 每日净值

| 字段 | 类型 | 说明 |
|---|---|---|
| trade_date | DATE | 主键，一天一行 |
| equity | DECIMAL | 账户净值 = 现金 + 持仓市值 |
| cash | DECIMAL | 现金 |
| note | VARCHAR | 备注，可为空 |

**最大回撤只能从这条序列算。** 它既不在成交表里，也不在复盘表里，这张表是必需的。

#### t_daily_review —— 复盘文字

| 字段 | 类型 | 说明 |
|---|---|---|
| review_date | DATE | 主键，一天一条 |
| market_note | VARCHAR | 大盘记录，只写事实 |
| strong_sector / weak_sector | VARCHAR | 强势 / 弱势方向 |
| missed_opportunity | VARCHAR | 看到但没做的机会及原因 |
| emotion | VARCHAR | 情绪状态 |
| lesson | VARCHAR | 今天最重要的一条经验 |
| tomorrow_todo | VARCHAR | 明日待办 |
| violation_count | INT | 冗余字段，写入时由明细条数回填 |

#### t_discipline_violation —— 纪律违背明细

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| review_date | DATE | 所属复盘日期 |
| item_code | VARCHAR | 编码（枚举名） |
| item_label | VARCHAR | 中文名，冗余存一份便于直接出报表 |

固定七项，与复盘模板的自检表一一对应：

| 编码 | 含义 |
|---|---|
| CHANGE_PLAN | 盘中改计划 |
| OFF_PLAN | 计划外交易 |
| CHASE | 追涨杀跌 |
| FOLLOW_TIP | 听消息下单 |
| AVERAGE_DOWN | 亏损加仓 |
| OVER_POSITION | 超仓位上限 |
| OVERTRADE | 盯盘过频 |

#### t_sector_daily —— 板块行情参考

| 字段 | 类型 | 说明 |
|---|---|---|
| id | BIGINT | 主键 |
| trade_date | DATE | 交易日 |
| sector_code / sector_name | VARCHAR | 板块代码 / 名称，银行板块为 `399986 中证银行` |
| close_value | DECIMAL(12,4) | 收盘点位，保留 4 位避免重算涨跌幅时产生偏差 |
| pct_chg | DECIMAL(8,4) | 涨跌幅**百分数本身**，`0.34` 表示 +0.34% |
| note | VARCHAR | 备注，示例数据标记为「示例」 |

`(sector_code, trade_date)` 唯一，重复导入同一天不会产生重复行。

**为什么要有这张表**：复盘时写「银行很强」是印象，不是事实。有了基准才能回答
「持仓没涨，是我选错了股，还是整个板块都在跌」——这两件事的改法完全不同。

**数据来源**：`V2__sector_daily.sql` 里预置的 9 个交易日是**示例点位，不是真实行情**，
仅用于页面联调与格式示范。真实数据用 `sector-import` profile 一次性导入
（`SectorDailyImporter`，会先清掉 `note='示例'` 的行），
具体命令见 [7.5 替换真实行情](#75-替换真实行情板块参考数据)。

---

## 五、统计口径

所有指标只有一个算法，写在 `StatService` 里，不依赖调用方传公式。

### 5.1 已实现盈亏

```
买入：avg_cost = (原成本金额 + 成交金额 + 买入费用) / 新数量
卖出：realized_pnl = 卖出金额 - 卖出费用 - avg_cost × 数量
```

两边费用都被计入，且不需要额外字段区分「成本」和「费用」两个口径。

### 5.2 胜率与盈亏比

```
winRate      = 盈利笔数 / 已平仓笔数
profitFactor = 平均盈利 / |平均亏损|
```

口径细节：

- 只统计 `side = 'SELL'` 且 `realized_pnl` 非空的记录。买入没有已实现盈亏，混进来会把样本数算大。
- 按 `is_planned` 分组，直接给出计划内外对比——这个差值通常比任何技术指标都更能说明问题。
- **没有亏损样本时，盈亏比返回 `null` 而不是一个大数**。它在数学上不存在，给 999 会让人误以为策略极好，实际只是样本不够。

### 5.3 最大回撤

```
从第 1 天起维护历史最高净值 peak；
每天计算 drawdown = (peak - 当日净值) / peak，取最大值；
同时记录对应的 peakDate（峰）与 troughDate（谷）。
```

示例：净值 100 → 120 → 90 → 110，最大回撤 = (120 − 90) / 120 = **25%**，峰 11-03、谷 11-04。

净值点少于 2 个时返回 0 与空日期，不报错——第一天本来就没有回撤可算。

### 5.4 纪律违背

按月、按违背项分组计数，返回每月总数与明细排序。月度复盘只看一个问题：
**排第一的那条是什么，这个月的改法就从它开始。**

---

## 六、接口

统一返回体：

```json
{ "code": 0, "message": "OK", "data": { } }
```

`code != 0` 时 `data` 为 null，`message` 为可读原因；参数校验失败返回 400，资源不存在返回 404。

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/plans` | 盘前写计划 |
| GET | `/plans?from=…&to=…&status=PENDING&keyword=600000&page=1&size=20` | 分页查计划 |
| POST | `/plans/{id}/cancel` | 条件未触发，取消计划 |
| POST | `/trades` | 记一笔成交 |
| GET | `/trades?from=…&to=…&keyword=&side=&isPlanned=&pnl=&sort=&page=1&size=20` | 分页区间成交 |
| POST | `/trades/batch` | 批量录入（JSON 数组），逐行校验 |
| POST | `/trades/import` | 上传 `.xlsx` / `.csv` 批量导入 |
| GET | `/trades/import/template` | 下载 Excel 导入模板 |
| GET | `/trades/positions` | 当前持仓 |
| POST | `/reviews` | 提交当日复盘 |
| GET | `/reviews/2026-11-05` | 查某日复盘 |
| GET | `/stats/overview?from=…&to=…` | 胜率 + 回撤 + 违背，一次取回 |
| GET | `/stats/trades?from=…&to=…` | 只看胜率与盈亏比 |
| GET | `/stats/drawdown?from=…&to=…` | 只看最大回撤 |
| GET | `/stats/equity?from=…&to=…` | 净值序列，供前端画曲线 |
| GET | `/stats/symbols?from=…&to=…` | 单标的聚合（笔数/胜率/买卖额/持仓）+ 收盘价序列 |
| GET | `/stats/monthly?from=…&to=…` | 月度收益 + 环比 + 同比 |
| GET | `/stats/discipline?from=…&to=…` | 只看纪律违背 |
| GET | `/refs/sectors?from=…&to=…&sector=399986` | 板块行情参考（只读） |

### 6.1 写计划

```http
POST /plans
Content-Type: application/json

{
  "planDate": "2026-11-05",
  "symbol": "600000",
  "name": "浦发银行",
  "triggerDesc": "跌破 18.20",
  "actionDesc": "卖出全部 500 股",
  "reason": "买入逻辑是股息率 > 5%，股息率跌破 4.5% 即卖出"
}
```

### 6.2 记成交

```http
POST /trades
Content-Type: application/json

{
  "tradeDate": "2026-11-05",
  "symbol": "600000",
  "name": "浦发银行",
  "side": "BUY",
  "price": 18.50,
  "quantity": 500,
  "fee": 5.00,
  "isPlanned": true,
  "planId": 1,
  "reason": "股息率 5.2%，处于近五年 20% 分位",
  "sellCondition": "股息率跌破 4.5%，或单笔亏损达到 2%"
}
```

卖出时系统自动返回 `realizedPnl`。卖出数量超过持仓会被拒绝（400），不会出现负持仓。

### 6.3 提交复盘

```http
POST /reviews
Content-Type: application/json

{
  "reviewDate": "2026-11-05",
  "marketNote": "沪深 300 涨 0.6%，成交额 8200 亿",
  "strongSector": "银行、电力",
  "weakSector": "半导体",
  "missedOpportunity": "上午看到电力异动，没做，因为不在观察池内",
  "emotion": "平静",
  "lesson": "计划外交易全部发生在午休看手机之后",
  "tomorrowTodo": "把手机放在另一个房间",
  "cash": 21500,
  "holdings": [
    { "symbol": "600000", "closePrice": 18.62, "stopLoss": 18.20, "takeProfit": 20.50, "logicValid": true }
  ],
  "violations": ["CHASE", "OVERTRADE"]
}
```

`equity` 不传时由系统按 `cash + Σ(收盘价 × 数量)` 算。
**建议永远不要手填**——手填最常见的错误就是漏掉现金，回撤会因此算错。

同一天重复提交视为修改：旧快照与旧违背明细会先删后插，不会重复累计。

### 6.4 看统计

```http
GET /stats/overview?from=2026-11-01&to=2026-11-30
```

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "from": "2026-11-01",
    "to": "2026-11-30",
    "trades": [
      { "isPlanned": true,  "closedTrades": 18, "winTrades": 11, "winRate": 0.6111,
        "avgWin": 320.50, "avgLoss": -180.20, "totalPnl": 1676.70, "profitFactor": 1.78 },
      { "isPlanned": false, "closedTrades": 5,  "winTrades": 2,  "winRate": 0.4000,
        "avgWin": 210.00, "avgLoss": -290.00, "totalPnl": -450.00, "profitFactor": 0.72 }
    ],
    "drawdown": {
      "maxDrawdown": 0.0680,
      "peakDate": "2026-11-11",
      "troughDate": "2026-11-19",
      "peakEquity": 102300.00,
      "troughEquity": 95343.60,
      "samples": 21
    },
    "discipline": [
      { "month": "2026-11", "total": 5,
        "items": [
          { "itemCode": "CHASE", "itemLabel": "追涨杀跌", "count": 2 },
          { "itemCode": "OFF_PLAN", "itemLabel": "计划外交易", "count": 2 },
          { "itemCode": "OVERTRADE", "itemLabel": "盯盘过频", "count": 1 }
        ] }
    ],
    "totalViolations": 5
  }
}
```

### 6.5 分页与筛选

成交和计划都走同一套分页参数，返回体是 `{ total, page, size, rows }`。

| 参数 | 适用 | 取值 |
|---|---|---|
| `from` / `to` | 成交、计划 | `yyyy-MM-dd`，按交易日期闭区间 |
| `keyword` | 成交、计划、标的 | 代码或名称模糊匹配 |
| `side` | 成交 | `BUY` / `SELL` |
| `isPlanned` | 成交 | `true` / `false` |
| `pnl` | 成交 | `WIN` 盈利、`LOSS` 亏损、`NONE` 未实现 |
| `sort` | 成交 | `date`（日期倒序）/ `pnl`（盈亏倒序） |
| `status` | 计划 | `PENDING` / `EXECUTED` / `CANCELLED` |
| `page` / `size` | 全部 | `page` 从 1 开始；`size` 上限 200，越界回落到 20 |

### 6.6 批量与 Excel 导入

```http
POST /trades/batch
Content-Type: application/json

[ { "tradeDate": "2026-11-05", "symbol": "600000", "name": "浦发银行",
    "side": "BUY", "price": 18.5, "quantity": 100, "fee": 5,
    "isPlanned": true, "reason": "放量突破", "sellCondition": "跌破 17.9" } ]
```

返回 `{ total, success, failed, errors }`。**逐行独立事务语义**：某一行校验失败只记进 `errors`，
不影响其他行；错误信息带行号，例如 `第 2 行：数量必须大于 0`。

```http
POST /trades/import        （multipart/form-data，字段名 file）
GET  /trades/import/template
```

- `.xlsx` 用 POI 解析，其他后缀按 CSV/TSV 处理（UTF-8，自动识别逗号或制表符，自动去 BOM）。
- 列顺序固定：`交易日期, 代码, 名称, 方向, 价格, 数量, 费用, 是否计划内, 理由, 卖出条件`。
- 日期接受 `yyyy-M-d`、`yyyy/M/d`、`yyyy.M.d`、`yyyyMMdd`；方向接受 `BUY/SELL/买入/卖出/B/S`；
  计划内接受 `是/否/y/n/true/false/1/0/计划内/计划外`。
- 模板接口直接返回可打开的 xlsx，带两行示例，不用手写表头。

### 6.7 自动同步与自动下载

券商网页没有对外接口，系统不保存账号密码；两步都做成「文件」这条最稳定的路径。

**自动同步**：交易日 09:35、11:35、13:05、15:05 扫描下载目录，按内容 SHA-256 去重，
先按文件名、再按表头判断是成交流水 / 每日资金 / 当前持仓，调与手工导入同一套服务入库。

```http
GET  /imports/sync/status
POST /imports/sync/run
```

**自动下载**：交易日 09:33、11:33、13:03、15:03 用 Playwright 启动本机 Chrome，
复用人工登录一次的 Cookie，打开配置的券商导出页，按步骤点导出；文件落盘后立即触发一次同步。
页面地址与选择器全部在 `trade-journal.download.brokers[].steps` 里，页面改版只改配置。

```http
GET  /downloads/status
POST /downloads/run?broker=华泰
POST /downloads/login?broker=华泰
```

首次使用先调 `/downloads/login` 打开有头浏览器登录一次，关闭窗口后登录态留在
`~/.trade-journal/browser/<券商名>`。步骤语法（click / fill / press / waitForSelector / wait / download）
与排查表见 [pages/07-import.md](pages/07-import.md) 第 12 节。

---

## 七、部署与启动

### 7.1 前置条件

- JDK 17、Maven 3.9+
- MySQL 8

### 7.2 建库并启动

```bash
mysql -uroot -p -e "CREATE DATABASE IF NOT EXISTS trade_journal DEFAULT CHARSET utf8mb4"

mvn -pl trade-journal -am spring-boot:run
```

建表脚本由 Flyway 在启动时自动执行（`src/main/resources/db/migration/V1__init.sql`），不需要手动跑 DDL。

### 7.3 可配置项（环境变量）

| 变量 | 默认值 | 说明 |
|---|---|---|
| `SERVER_PORT` | 8205 | 服务端口 |
| `MYSQL_HOST` | 127.0.0.1 | 数据库地址 |
| `MYSQL_PORT` | 3306 | 端口 |
| `MYSQL_DB` | trade_journal | 库名 |
| `MYSQL_USER` | root | 用户 |
| `MYSQL_PASSWORD` | root | 密码 |

自动同步与自动下载在 `application.yml` 的 `trade-journal.sync` / `trade-journal.download` 下配置：
扫描目录可用 `SYNC_DIR` 覆盖；自动下载复用本机 Chrome/Edge（`channel: chrome|msedge`），
不需要执行 `playwright install`，但需要先用 `/downloads/login` 手动登录一次。

服务不接注册中心：复盘数据是个人数据，不应该依赖 Nacos 才能启动。

### 7.4 前端页面

启动后浏览器打开 **http://localhost:8205/** 即可，不需要单独装前端依赖或跑构建。

页面是 `src/main/resources/static/index.html` 一个文件（原生 HTML + fetch，无外部 CDN），有五个标签页：

| 标签页 | 能做的事 |
|---|---|
| 统计（整体） | 区间筛选与快捷区间；5 个 KPI；账户净值 + 各标的多折线对比；月度收益/环比/同比分组柱状图 + 月度明细；成交明细分页筛选；计划内外对比；板块走势与明细；按月纪律违背 |
| 标的分析（单项目） | 标的盈亏贡献条形图（含「账户合计」整体行）；点表格行切换「该标的 + 账户整体」双折线；单标的分页筛选（关键字/盈亏/是否持仓/排序） |
| 盘前计划 | 写「若 X 则 Y」；按日期、状态、关键字分页查询；取消计划 |
| 记成交 | 单笔录入；批量粘贴（CSV/TSV 一行一笔）；上传 .xlsx/.csv；下载模板；导入结果逐行报错；当前持仓；区间成交流水分页筛选 |
| 每日复盘 | 填文字、持仓收盘价、纪律自检，一键提交；可按日期读取已保存内容 |

口径与公式在页面上都有提示：凡是带 `?` 的字段，鼠标悬停会显示算法。当前覆盖：

- 区间收益 = 末净值 / 首净值 − 1
- 最大回撤 = 相对此前峰值的最大跌幅
- 胜率 = 盈利平仓笔数 / 平仓总笔数；盈亏比 = 平均盈利 / 平均亏损绝对值
- 环比 = 本月收益率 − 上月收益率（单位 pp）
- 同比 = 本月收益率 − 去年同月收益率（单位 pp，无数据留空）
- 标的区间涨跌 = 末收盘价 / 首收盘价 − 1
- 加权成本 =（原成本金额 + 本次成交金额 + 买入费用）÷ 新数量
- 净值 = 现金 + Σ(持仓数量 × 当日收盘价)

图表统一用 **ECharts 5.5.1**，库文件放在 `static/vendor/echarts.min.js` 随包发布，不走 CDN：

- **多序列折线**（`indexLineChart`）：净值图把「账户净值」与区间内有快照的每只标的画在同一张图上。
  各序列以区间首个值归一化为 100，共用一条 Y 轴，图例带各自区间涨跌；
  tooltip 悬停显示指数与原始净值 / 股价。板块图（中证银行 + 账户净值）、单标的页（该标的 + 账户整体）复用同一函数。
- **分组柱状**（`monthlyBarChart`）：月度图每月并排「本月收益 % / 环比 pp / 同比 pp」三根柱。
  值为 null 时**不画柱**——把「没有基数」画成 0 会被读成「收益为零」。
- **横向柱状**（`contribBarChart`）：标的盈亏贡献，绿盈红亏，灰色虚线标出「账户合计」，右侧橙色第二轴显示区间涨跌。

图表容器统一 `class="echart"`，页签从隐藏状态切回时重新 `resize()`。库是本地文件，关掉网络也能正常显示。

它只是这套 REST 接口的一个客户端：关掉页面、直接用 curl 或 Postman 调接口，数据完全等价。
将来若要接入 `company-web` 的前端工程，后端不需要任何改动。

### 7.5 替换真实行情（板块参考数据）

`V2__sector_daily.sql` 里的 9 个交易日是示例点位，只用于联调。真实数据由
[SectorDailyImporter](../trade-journal/src/main/java/com/company/tradejournal/tool/SectorDailyImporter.java) 导入：
它是一个 `ApplicationRunner`，只在 `sector-import` profile 下启用，正常启动服务时不会执行。

**先装一次依赖模块**（`spring-boot:run` 不带 `-am` 时依赖必须已在本地仓库）：

```bash
mvn -q -pl trade-journal -am -DskipTests install
```

**导入最近 30 天**（默认行为）：

```bash
mvn -pl trade-journal spring-boot:run -Dspring-boot.run.profiles=sector-import
```

**指定区间**：

```bash
mvn -pl trade-journal spring-boot:run -Dspring-boot.run.profiles=sector-import \
  -Dspring-boot.run.arguments="--trade-journal.sector-import.start=2026-10-01 --trade-journal.sector-import.end=2026-11-05"
```

**可配置项**（也可写在 `application-sector-import.yml` 里）：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `trade-journal.sector-import.enabled` | false | 只有设为 true 才会执行 |
| `trade-journal.sector-import.secid` | `0.399986` | `市场.代码`，0=深市、1=沪市；换成其他指数同理 |
| `trade-journal.sector-import.start` / `end` | 空 | 形如 `2026-10-01`；留空表示「今天往前 30 天」 |
| `trade-journal.sector-import.purge-sample` | true | 写入前先删掉 `note = '示例'` 的行 |

几个行为要提前知道：

- **数据源**：东方财富 kline 接口（`klt=101` 日线、`fqt=0` 不复权），无需鉴权。返回的每行是
  `日期,开,收,高,低,量,额,振幅,涨跌幅,涨跌额,换手`，代码里按下标 2 取收盘、下标 8 取涨跌幅。
- **幂等**：写入走 `INSERT ... ON DUPLICATE KEY UPDATE`，同一区间重复跑结果一致，不会产生重复行。
- **示例数据清理**：`purge-sample=true` 时先删 `note='示例'` 的行，导入的真实数据 `note` 为空，
  两者不会混在一张表里；想保留示例可设为 false。
- **空数据不写库**：区间内全是非交易日或接口异常时只打 warn 日志，不会清空已有数据。
- **涨跌幅口径**：接口返回的是百分数本身（如 `0.34`），与 `t_sector_daily.pct_chg` 的定义一致，不做换算。

导入完成后刷新统计页，「板块参考 · 银行」的区间涨跌幅就会从示例值变成真实值；
`t_sector_daily` 是只读参考表，导入器不碰任何个人交易数据。

---

## 八、日常怎么用

| 时间 | 动作 | 接口 |
|---|---|---|
| 08:40–09:10 | 写当天「若 X 则 Y」 | `POST /plans` |
| 盘中触发 | 记成交，带上 `planId` | `POST /trades` |
| 触发条件未出现 | 取消计划，不要留着 | `POST /plans/{id}/cancel` |
| 15:00–15:20 | 填复盘文字 + 持仓收盘价 + 违背项 | `POST /reviews` |
| 周末 | 拉本周统计 | `GET /stats/overview` |
| 每月末 | 拉月度统计，只看排第一的那条纪律 | `GET /stats/overview` |

**一条使用纪律**：成交当天录，不要攒到周末。
当天记得清价格、费用和理由；隔天补记必然漏费用，统计从源头就不可信。

---

## 九、和 Markdown 模板怎么配合

| | 复盘模板（Markdown） | trade-journal |
|---|---|---|
| 定位 | 当天怎么想、为什么 | 多天算得准 |
| 内容 | 完整六块，含自由书写 | 结构化字段 + 必须填的部分 |
| 使用阶段 | 前 20 个交易日，或任何需要深想的一天 | 超过 30 笔后作为主记录 |
| 关系 | 可以作为 `/reviews` 的草稿 | 数据落库后不再需要手工汇总 |

建议过渡期做法：白天在模板里写，晚上只把结构化部分（成交、收盘价、违背项）录进系统，
其他文字原样贴进 `marketNote / lesson / tomorrowTodo`。不要长期维护两份数据。

---

## 十、已知限制与后续可加

**当前不做**（有意为之，避免过度设计）：

- 不做多用户与权限：单用户个人工具，`t_position` 无用户维度，将来多人使用需要加乐观锁。
- 不做行情自动拉取：收盘价手工填，避免引入数据源依赖与复权口径问题。
- 不做 FIFO 成本：只用加权平均，分批建仓足够，且卖出盈亏可解释。

**将来值得加**（按需要，不提前做）：

| 需求 | 做法 |
|---|---|
| 每日收盘价自动填 | 接 akshare 定时任务，注意复权口径与停牌处理 |
| 基准对比 | 新增 `t_benchmark_daily`，存沪深 300 收盘，算超额收益 |
| 持仓逻辑失效提醒 | 快照里 `logic_valid = 0` 的持仓在统计接口里单独列出 |
| 导出月报 | 把 `/stats/overview` 结果渲染成 Markdown 或 HTML |
