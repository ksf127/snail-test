package com.company.tradejournal.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.company.tradejournal.constant.DisciplineItem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 提交某一日的复盘（重复提交视为修改）。
 *
 * <p>【为什么净值可以由系统算】手填净值最容易把现金漏掉，导致回撤算错。
 * 这里 {@code equity} 允许为空：为空时由 Service 用「现金 + 持仓 × 收盘价」自动算，
 * 只有券商结算价与手工价不一致时才需要传入。
 *
 * @param reviewDate       复盘日期，写入后作为当天唯一标识
 * @param marketNote       大盘记录，只写事实
 * @param strongSector     强势方向
 * @param weakSector       弱势方向
 * @param missedOpportunity 看到但没做的机会及原因
 * @param emotion          情绪状态
 * @param lesson           今天最重要的一条经验
 * @param tomorrowTodo     明日待办
 * @param equity           账户净值，为空则自动计算
 * @param cash             现金余额，必填（自动算净值时要用）
 * @param holdings         持仓收盘信息，空持仓也要传空数组
 * @param violations       当日违背的纪律项
 * @param accountId        所属账户，可为空（空按 1 处理，兼容改造前的单账户调用）
 */
public record ReviewRequest(@NotNull LocalDate reviewDate,
                            String marketNote,
                            String strongSector,
                            String weakSector,
                            String missedOpportunity,
                            String emotion,
                            String lesson,
                            String tomorrowTodo,
                            BigDecimal equity,
                            @NotNull BigDecimal cash,
                            @Valid List<HoldingClose> holdings,
                            List<DisciplineItem> violations,
                            Long accountId) {

    /**
     * 单只持仓的收盘信息。
     *
     * @param symbol     代码
     * @param closePrice 收盘价
     * @param stopLoss   止损位，可为空
     * @param takeProfit 止盈位，可为空
     * @param logicValid 买入逻辑是否仍成立，为空按 true 处理
     */
    public record HoldingClose(@NotBlank String symbol,
                               @NotNull BigDecimal closePrice,
                               BigDecimal stopLoss,
                               BigDecimal takeProfit,
                               Boolean logicValid) {
    }
}
