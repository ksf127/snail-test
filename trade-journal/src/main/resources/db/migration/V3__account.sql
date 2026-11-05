-- ============================================================
-- 多账户改造
--
-- 【解决什么问题】原来的成交、持仓、净值都只有一份，等于默认「一个人只有一个账户」。
-- 实际使用时券商账户、模拟盘、家人的账户往往要分开算收益，混在一起会让胜率和回撤失真。
--
-- 【为什么这样设计】所有业务表加 account_id，历史数据统一归到 id = 1 的「默认账户」。
-- 这样升级不需要清洗数据：ALTER 时给现存行填默认值 1，旧接口不传 accountId 时也落到 1，
-- 前后端可以分批上线。
--
-- 【怎么读这段代码】先建账户表并写入默认账户，再逐表加列、改主键/唯一键。
-- 改主键必须放在 ADD COLUMN 之后：MySQL 的复合主键要求列已存在。
-- ============================================================

CREATE TABLE IF NOT EXISTS t_account
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(64)    NOT NULL COMMENT '账户名称',
    broker          VARCHAR(64)    NULL COMMENT '券商',
    currency        VARCHAR(8)     NOT NULL DEFAULT 'CNY' COMMENT '币种',
    initial_capital DECIMAL(18, 2) NOT NULL DEFAULT 0 COMMENT '初始资金，算收益率的分母',
    note            VARCHAR(255)   NULL COMMENT '备注',
    active          TINYINT(1)     NOT NULL DEFAULT 1 COMMENT '是否启用，0 表示停用但不删数据',
    created_at      DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='账户：所有成交与净值都挂在一个账户下';

-- 幂等：重复执行迁移时不会插入第二行；已有同名账户时也保持原值
INSERT INTO t_account (id, name, broker, currency, initial_capital, note)
VALUES (1, '默认账户', NULL, 'CNY', 0, '历史数据归属账户')
ON DUPLICATE KEY UPDATE name = '默认账户', note = '历史数据归属账户';

-- 成交流水：加账户列并建索引，列表按账户筛选会用到
ALTER TABLE t_trade
    ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 COMMENT '账户 ID' AFTER id,
    ADD KEY idx_account (account_id);

-- 当前持仓：主键从「代码」改成「账户 + 代码」，否则两个账户持有同一只票会互相覆盖
ALTER TABLE t_position
    ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 COMMENT '账户 ID' FIRST,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (account_id, symbol);

-- 持仓快照：唯一键加账户维度，同一天不同账户可以各自拍快照
ALTER TABLE t_position_daily
    ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 COMMENT '账户 ID' AFTER id,
    DROP INDEX uk_date_symbol,
    ADD UNIQUE KEY uk_account_date_symbol (account_id, snapshot_date, symbol);

-- 每日净值：一天一条改成「每账户一天一条」
ALTER TABLE t_account_daily
    ADD COLUMN account_id BIGINT NOT NULL DEFAULT 1 COMMENT '账户 ID' FIRST,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (account_id, trade_date);
