package com.company.tradejournal.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 板块行情参考：一天一板块一行。
 *
 * <p>【解决什么问题】复盘时写「银行板块很强」是印象，不是事实。
 * 有了这张表，才能把「我以为的强」和「实际涨跌幅」对上，进而判断
 * 自己的持仓是选股问题还是踩错了方向。
 *
 * <p>【为什么这样设计】只存收盘点位与涨跌幅，不存成分股、不存成交额：
 * 它是复盘时的对照基准，不是行情终端，字段越少越不容易和真实行情源冲突。
 * {@code closeValue} 保留 4 位小数，避免指数点位四舍五入后重算涨跌幅产生偏差。
 *
 * <p>【怎么读这段代码】{@code pctChg} 存的是百分数本身（0.34 表示 +0.34%），
 * 不是 0.0034，这样报表里直接加百分号就能显示，不需要前端再乘 100。
 */
@Data
@TableName("t_sector_daily")
public class SectorDailyEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private LocalDate tradeDate;

    /** 板块代码，如中证银行 399986。 */
    private String sectorCode;

    private String sectorName;

    private BigDecimal closeValue;

    /** 涨跌幅，百分数本身：0.34 = +0.34%。 */
    private BigDecimal pctChg;

    private String note;

    private LocalDateTime createdAt;
}
