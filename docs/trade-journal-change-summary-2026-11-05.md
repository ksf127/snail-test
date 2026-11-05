# trade-journal 本次更新变更摘要

日期：2026-11-05
范围：`trade-journal` 模块（后端接口 + 前端页面 + 文档），未改动其它模块。

## 一、本次目标

1. 列表分页、筛选条件更丰富。
2. 图表改为多序列：净值图同图叠加「账户整体 + 每只标的」（起点归一化 100），月度图每月并排「本月收益 / 环比 / 同比」。
3. 所有使用公式的位置给出算法提示。
4. 全部页面整体优化。
5. 入库支持批量添加与 Excel 导入。

## 二、后端变更

### 2.1 接口清单

| 方法 | 路径 | 变化 |
|---|---|---|
| GET | `/trades?from&to&keyword&side&isPlanned&pnl&sort&page&size` | 由「返回 List」改为分页 + 多条件筛选 |
| POST | `/trades` | 不变 |
| POST | `/trades/batch` | 新增，JSON 数组批量录入，逐行校验 |
| POST | `/trades/import` | 新增，上传 `.xlsx` / `.csv` 批量导入 |
| GET | `/trades/import/template` | 新增，下载带示例的 Excel 模板 |
| GET | `/trades/positions` | 不变 |
| GET | `/plans?from&to&status&keyword&page&size` | 由 `?planDate=` 改为分页 + 多条件筛选 |
| POST | `/plans`、`POST /plans/{id}/cancel` | 不变 |
| GET | `/stats/symbols?from&to` | 新增，单标的聚合 + 收盘价序列 |
| GET | `/stats/monthly?from&to` | 新增，月度收益 + 环比 + 同比 |
| GET | `/stats/overview`、`/stats/equity`、`/stats/trades`、`/stats/drawdown`、`/stats/discipline` | 不变 |
| GET | `/refs/sectors` | 不变 |

### 2.2 分页与筛选

- 统一返回体：`{ total, page, size, rows }`。
- `page` 从 1 开始；`size` 上限 200，越界回落为 20。
- 成交筛选：`keyword`（代码/名称）、`side`、`isPlanned`、`pnl`（WIN/LOSS/NONE）、`sort`（date/pnl）。
- 计划筛选：`status`（PENDING/EXECUTED/CANCELLED）、`keyword`、日期区间。
- 默认排序：成交按日期倒序、ID 倒序；计划按计划日期倒序、ID 倒序。

### 2.3 单标的统计（`/stats/symbols`）

三个数据源取并集：区间成交聚合、区间收盘价快照、当前持仓。返回字段：

```text
symbol, name, tradeCount, sellCount, winCount,
buyAmount, sellAmount, realizedPnl,
firstClose, lastClose, priceChangePct, holdingQuantity,
series[{ tradeDate, closePrice }]
```

口径：

- `buyAmount = Σ(价格 × 数量 + 费用)`
- `sellAmount = Σ(价格 × 数量 − 费用)`
- `priceChangePct = (末收盘价 / 首收盘价 − 1) × 100`
- 胜率在页面按 `winCount / sellCount` 计算，卖出笔数为 0 时显示 `—`。

### 2.4 月度收益（`/stats/monthly`）

按自然月分组账户净值，返回：

```text
month, startEquity, endEquity, returnPct,
momPct, yoyPct, tradeCount, violationCount, partial
```

口径：

- `returnPct = 月末净值 / 月初净值 − 1`
- 月初净值取上月最后一个净值点；区间内第一个有数据的月份用当月首点，并置 `partial = true`
- `momPct = 本月收益率 − 上月收益率`（单位 pp）
- `yoyPct = 本月收益率 − 去年同月收益率`（单位 pp，无数据返回 null）

### 2.5 批量与导入

- `POST /trades/batch`：返回 `{ total, success, failed, errors }`，单行失败不中断，错误带行号（如 `第 2 行：数量必须大于 0`）。
- `POST /trades/import`：
  - `.xlsx` 用 POI 解析，其余按 CSV/TSV，UTF-8、自动去 BOM、自动识别逗号或制表符。
  - 固定列序：`交易日期, 代码, 名称, 方向, 价格, 数量, 费用, 是否计划内, 理由, 卖出条件`。
  - 日期兼容 `yyyy-M-d`、`yyyy/M/d`、`yyyy.M.d`、`yyyyMMdd`；方向兼容 `BUY/SELL/买入/卖出/B/S`；计划内兼容 `是/否/y/n/true/false/1/0/计划内/计划外`。
- `pom.xml` 新增 `org.apache.poi:poi-ooxml:5.5.1`。

### 2.6 新增/修改文件

新增：

- `dto/PageResult.java`、`dto/BatchResult.java`
- `dto/stats/SymbolStat.java`、`SymbolTradeAggRow.java`、`SymbolSnapshotRow.java`、`SymbolPricePoint.java`、`SymbolHoldingRow.java`
- `dto/stats/MonthlyPerformance.java`、`MonthCountRow.java`
- `service/TradeImportService.java`

修改：

- `controller/TradeController.java`、`controller/PlanController.java`、`controller/StatController.java`
- `service/TradeService.java`、`service/PlanService.java`、`service/StatService.java`
- `mapper/StatMapper.java`
- `pom.xml`（POI 依赖）

## 三、前端变更

`src/main/resources/static/index.html` 单文件重写（原生 HTML + fetch，无 CDN、无构建），由 4 个页签扩展为 5 个：

| 页签 | 主要内容 |
|---|---|
| 统计（整体） | 区间筛选 + 快捷区间；5 个 KPI；账户净值 + 各标的多折线对比图；月度收益/环比/同比分组柱状图 + 明细表；成交明细分页筛选；计划内外对比；板块走势与明细；按月纪律违背 |
| 标的分析（单项目） | 标的盈亏贡献条形图（含「账户合计」整体行）；点行切换「该标的 + 账户整体」双折线；关键字/盈亏/持仓/排序筛选 + 分页 |
| 盘前计划 | 新增计划表单；日期、状态、关键字筛选；分页；取消 |
| 记成交 | 单笔录入；批量粘贴；xlsx/csv 上传；模板下载；导入结果逐行报错；当前持仓；成交流水分页筛选 |
| 每日复盘 | 六块文字、持仓收盘价、7 项纪律校验、按日期读取、提交 |

图表实现：

- 引入 **ECharts 5.5.1** 本地文件 `static/vendor/echarts.min.js`（1.03 MB），页面用 `<script>` 直接引用，不依赖 CDN、离线可用。
- 多序列折线 `indexLineChart`：净值图同图叠加「账户净值」与区间内有快照的每只标的，各序列以区间首值归一化为 100，共用一条 Y 轴，图例带各自区间涨跌，tooltip 显示指数与原始净值/股价；板块图（中证银行 + 账户净值）、单标的页（该标的 + 账户整体）复用同一函数。
- 分组柱状 `monthlyBarChart`：月度图每月并排三根柱（本月收益 % / 环比 pp / 同比 pp），颜色区分，值为 null 时不画柱（避免把「无数据」读成 0）。
- 横向柱状 `contribBarChart`：标的盈亏贡献，绿盈红亏，灰色虚线标出「账户合计」，右侧橙色第二轴显示区间涨跌。
- 图表实例按容器 id 缓存复用（`chartOf`），容器统一 `class="echart"`；窗口 resize 与页签切回时触发 `resize()`，隐藏页签不会把尺寸算成 0。

公式提示：

- 使用 `?` + `data-tip`，鼠标悬停显示算法，覆盖区间收益、最大回撤、胜率/盈亏比、环比、同比、标的区间涨跌、加权成本、净值。
- 口径：`pctChg`、`returnPct`、`priceChangePct` 均为百分数本身；环比/同比为百分点差（pp）；`maxDrawdown` 为 0~1 小数。

## 四、行为变化与兼容性

- **不兼容变更**：`GET /trades` 与 `GET /plans` 的返回结构由数组改为 `{total,page,size,rows}`；`GET /plans` 不再接受 `planDate`，改用 `from`/`to`。
- 批量/导入均为逐行独立处理，单行失败不影响其它行。
- 导入的成交会照常参与持仓、已实现盈亏与全部统计，口径与单笔录入一致。

## 五、验证结果

- `mvn -o -B -ntp -pl trade-journal -am -DskipTests install` 构建通过。
- 接口实测：
  - `/stats/symbols` 返回 2 个标的、序列 7 个点
  - `/stats/monthly` 返回 2026-10（`partial=true`，环比/同比为空）与 2026-11（环比 +0.36pp）
  - `/trades?page=1&size=3` 返回 `total=6`、3 行
  - `/plans?page=1&size=3` 返回 `total=3`、3 行
  - 批量导入 `total=2 success=1 failed=1`（非法方向被单行拒绝）；CSV 导入成功
  - 模板下载 3795 字节，可正常打开
- 浏览器逐页验证：5 个页签、KPI、折线/柱状图、单标的曲线、分页文本、持仓表、7 个纪律复选框均正常，控制台无 error。
- 多序列图表复验：净值图图例 3 项（账户净值 +0.78%、600000 +2.58%、000001 −7.93%），三条线起点均落在 100 基准；月度图图例三项，26-11 并排「本月收益 +0.57%」与「环比 +0.36pp」两根柱，26-10 无上月基数故不出环比柱；无 error toast。
- 联调写入的 TEST01/TEST02 测试数据已清理，数据恢复为 6 笔成交、600000 持仓 150 股。

## 六、文档与后续

- 已更新 `docs/trade-journal-guide.md`：接口表、6.5 分页与筛选、6.6 批量与 Excel 导入、7.4 前端页面。
- 本地调试实例在 8206；IDEA 内的 8205 实例需重启后才能加载新 class 与新页面。
