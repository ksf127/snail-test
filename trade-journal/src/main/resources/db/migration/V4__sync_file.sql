-- ============================================================
-- 自动同步记录
--
-- 【解决什么问题】定时任务会反复扫描下载目录，必须有地方记住「这个文件处理过没有」，
-- 否则同一份导出文件每两小时被导一次，成交会翻倍。
--
-- 【为什么用内容指纹】券商导出的文件名经常相同（成交记录.xlsx），靠文件名判断会漏。
-- fingerprint 存文件内容的 SHA-256：内容没变就不再处理；内容变了（多几笔新成交）
-- 当成新文件重导，重复成交由导入服务逐笔跳过。
--
-- 【为什么失败也记】失败文件占住指纹，避免每次扫描重复重试同一份坏文件。
-- ============================================================

CREATE TABLE IF NOT EXISTS t_sync_file
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    fingerprint VARCHAR(64)   NOT NULL COMMENT '文件内容 SHA-256，唯一',
    file_name   VARCHAR(255)  NOT NULL COMMENT '文件名',
    file_path   VARCHAR(1024) NOT NULL COMMENT '完整路径',
    import_type VARCHAR(16)   NULL COMMENT '识别出的类型 trades/daily/positions',
    account_id  BIGINT        NOT NULL DEFAULT 1 COMMENT '导入到哪个账户',
    total       INT           NOT NULL DEFAULT 0 COMMENT '解析出的数据行',
    success     INT           NOT NULL DEFAULT 0 COMMENT '写入行',
    skipped     INT           NOT NULL DEFAULT 0 COMMENT '重复跳过行',
    failed      INT           NOT NULL DEFAULT 0 COMMENT '失败行',
    status      VARCHAR(16)   NOT NULL COMMENT 'IMPORTED/SKIPPED/FAILED',
    message     VARCHAR(1000) NULL COMMENT '识别结果与失败原因',
    scanned_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '扫描时间',
    imported_at DATETIME      NULL COMMENT '入库时间，未写入时为空',
    UNIQUE KEY uk_fingerprint (fingerprint),
    KEY idx_scanned (scanned_at),
    KEY idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='自动同步：下载目录里处理过的文件';
