package com.company.tradejournal;

import org.mybatis.spring.annotation.MapperScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 交易复盘系统启动类。
 *
 * <p>【解决什么问题】每日复盘用 Markdown 记录只能看清当天，看不出「胜率、盈亏比、最大回撤、
 * 纪律违背次数」这些跨周期的规律。本服务把这些数据落成结构化表，统计全部由 SQL 推出，
 * 不再靠手工算公式。
 *
 * <p>【为什么这样设计】只做「记录 + 统计」两件事，不接行情、不下单、不预测。
 * 记录是人工的（复盘本质是主观判断），统计是确定的（SQL 算出来的东西不会漂）。
 * 一旦在这里加入自动选股或自动交易，数据就会被污染，复盘也就失去意义。
 *
 * <p>【怎么读这段代码】按一次典型使用顺序看：
 * 早上 {@code POST /plans} 写计划 → 收盘 {@code POST /trades} 记成交 →
 * 晚上 {@code POST /reviews} 提交当日复盘与快照 → 随时 {@code GET /stats/*} 看统计。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.company.tradejournal.mapper")
public class TradeJournalApplication {

    private static final Logger log = LoggerFactory.getLogger(TradeJournalApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(TradeJournalApplication.class, args);
        log.info("【启动】交易复盘系统已启动，默认端口 8205；建表脚本由 Flyway 在启动时自动执行");
    }
}
