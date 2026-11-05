-- ============================================================
-- 板块行情参考表
--
-- 用途：给「板块强 / 弱」提供一个可核对的基准，避免复盘时凭印象写
--      「银行很强」，实际当天板块是跌的。
--
-- 说明：下方 INSERT 为示例数据（非真实行情），仅用于页面联调与格式示范；
--      实际使用时按券商或交易所公布的收盘点位替换。
-- ============================================================

CREATE TABLE IF NOT EXISTS `t_sector_daily` (
    `id`          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trade_date`  DATE          NOT NULL COMMENT '交易日',
    `sector_code` VARCHAR(20)   NOT NULL COMMENT '板块代码，如中证银行 399986',
    `sector_name` VARCHAR(50)   NOT NULL COMMENT '板块名称',
    `close_value` DECIMAL(12,4) NOT NULL COMMENT '收盘点位',
    `pct_chg`     DECIMAL(8,4)  NOT NULL COMMENT '涨跌幅百分比，0.34 表示 +0.34%',
    `note`        VARCHAR(200)  DEFAULT NULL COMMENT '备注，如政策事件；示例数据标记为「示例」',
    `created_at`  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '录入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sector_date` (`sector_code`, `trade_date`),
    KEY `idx_sector_date` (`trade_date`, `sector_code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '板块行情参考，一天一板块一行';

INSERT INTO `t_sector_daily`
(`trade_date`, `sector_code`, `sector_name`, `close_value`, `pct_chg`, `note`)
VALUES
('2026-10-26', '399986', '中证银行', 7012.3500,  0.4200, '示例'),
('2026-10-27', '399986', '中证银行', 6998.2000, -0.2000, '示例'),
('2026-10-28', '399986', '中证银行', 7021.6600,  0.3400, '示例'),
('2026-10-29', '399986', '中证银行', 7045.1000,  0.3300, '示例'),
('2026-10-30', '399986', '中证银行', 7032.4800, -0.1800, '示例'),
('2026-11-02', '399986', '中证银行', 7050.9200,  0.2600, '示例'),
('2026-11-03', '399986', '中证银行', 7071.3000,  0.2900, '示例'),
('2026-11-04', '399986', '中证银行', 7042.1500, -0.4100, '示例'),
('2026-11-05', '399986', '中证银行', 7068.4400,  0.3700, '示例');
