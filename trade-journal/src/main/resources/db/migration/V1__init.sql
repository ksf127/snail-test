-- 交易复盘系统初始化脚本
-- 设计原则：所有统计都必须能从这几张表用 SQL 推出来，日复盘只是「当天的记录入口」。

CREATE TABLE IF NOT EXISTS t_trade_plan
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_date    DATE          NOT NULL COMMENT '计划日期',
    symbol       VARCHAR(16)   NOT NULL COMMENT '代码',
    name         VARCHAR(32)   NOT NULL COMMENT '名称',
    trigger_desc VARCHAR(255)  NOT NULL COMMENT '若……什么条件触发',
    action_desc  VARCHAR(255)  NOT NULL COMMENT '则……做什么动作',
    reason       VARCHAR(500)  NULL COMMENT '依据，必须可证伪',
    status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / EXECUTED / CANCELLED',
    created_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_plan_date (plan_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='交易计划：若 X 则 Y，盘前写';

CREATE TABLE IF NOT EXISTS t_trade
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    trade_date     DATE          NOT NULL COMMENT '成交日期',
    symbol         VARCHAR(16)   NOT NULL COMMENT '代码',
    name           VARCHAR(32)   NOT NULL COMMENT '名称',
    side           VARCHAR(8)    NOT NULL COMMENT 'BUY / SELL',
    price          DECIMAL(18, 4) NOT NULL COMMENT '成交价',
    quantity       INT           NOT NULL COMMENT '数量',
    fee            DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '佣金+印花税+过户费合计',
    realized_pnl   DECIMAL(18, 2) NULL COMMENT '仅卖出有值：卖出净额 - 加权成本',
    is_planned     TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否计划内，1/0',
    plan_id        BIGINT        NULL COMMENT '关联的计划 ID',
    reason         VARCHAR(500)  NOT NULL COMMENT '买入理由，必须可证伪',
    sell_condition VARCHAR(500)  NULL COMMENT '买入时写下的卖出条件',
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_trade_date (trade_date),
    KEY idx_symbol (symbol)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='成交流水：所有统计的唯一事实来源';

CREATE TABLE IF NOT EXISTS t_position
(
    symbol     VARCHAR(16)    NOT NULL PRIMARY KEY COMMENT '代码',
    name       VARCHAR(32)    NOT NULL COMMENT '名称',
    quantity   INT            NOT NULL COMMENT '当前持仓数量',
    avg_cost   DECIMAL(18, 4) NOT NULL COMMENT '加权成本（含买入费用）',
    updated_at DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='当前持仓：由成交实时维护，卖出时用它算已实现盈亏';

CREATE TABLE IF NOT EXISTS t_position_daily
(
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    snapshot_date  DATE           NOT NULL COMMENT '快照日期',
    symbol         VARCHAR(16)    NOT NULL COMMENT '代码',
    name           VARCHAR(32)    NOT NULL COMMENT '名称',
    quantity       INT            NOT NULL COMMENT '数量',
    avg_cost       DECIMAL(18, 4) NOT NULL COMMENT '成本价',
    close_price    DECIMAL(18, 4) NOT NULL COMMENT '收盘价',
    position_ratio DECIMAL(10, 4) NOT NULL COMMENT '仓位占比 = 市值 / 账户净值',
    stop_loss      DECIMAL(18, 4) NULL COMMENT '止损位',
    take_profit    DECIMAL(18, 4) NULL COMMENT '止盈位',
    logic_valid    TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '买入逻辑是否仍成立',
    UNIQUE KEY uk_date_symbol (snapshot_date, symbol)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='每日持仓快照：画仓位曲线用';

CREATE TABLE IF NOT EXISTS t_account_daily
(
    trade_date DATE           NOT NULL PRIMARY KEY COMMENT '交易日',
    equity     DECIMAL(18, 2) NOT NULL COMMENT '账户净值 = 现金 + 持仓市值',
    cash       DECIMAL(18, 2) NOT NULL COMMENT '现金',
    note       VARCHAR(255)   NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='每日净值：最大回撤只能从这条序列算';

CREATE TABLE IF NOT EXISTS t_daily_review
(
    review_date      DATE         NOT NULL PRIMARY KEY COMMENT '复盘日期',
    market_note      VARCHAR(500) NULL COMMENT '大盘记录，只写事实',
    strong_sector    VARCHAR(128) NULL COMMENT '强势方向',
    weak_sector      VARCHAR(128) NULL COMMENT '弱势方向',
    missed_opportunity VARCHAR(500) NULL COMMENT '看到但没做的机会及原因',
    emotion          VARCHAR(32)  NULL COMMENT '情绪状态',
    lesson           VARCHAR(500) NULL COMMENT '今天最重要的一条经验',
    tomorrow_todo    VARCHAR(500) NULL COMMENT '明日待办',
    violation_count  INT          NOT NULL DEFAULT 0 COMMENT '当日违背纪律次数',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='每日复盘：一天一条';

CREATE TABLE IF NOT EXISTS t_discipline_violation
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    review_date DATE         NOT NULL COMMENT '所属复盘日期',
    item_code   VARCHAR(32)  NOT NULL COMMENT '违背项编码',
    item_label  VARCHAR(64)  NOT NULL COMMENT '违背项名称，冗余存一份便于直接出报表',
    KEY idx_review_date (review_date)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='纪律违背明细：按条记录，月度统计才有依据';
