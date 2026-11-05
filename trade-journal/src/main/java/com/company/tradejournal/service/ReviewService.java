package com.company.tradejournal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.tradejournal.constant.DisciplineItem;
import com.company.tradejournal.dto.ReviewRequest;
import com.company.tradejournal.entity.AccountDailyEntity;
import com.company.tradejournal.entity.DailyReviewEntity;
import com.company.tradejournal.entity.DisciplineViolationEntity;
import com.company.tradejournal.entity.PositionDailyEntity;
import com.company.tradejournal.entity.PositionEntity;
import com.company.tradejournal.mapper.AccountDailyMapper;
import com.company.tradejournal.mapper.DailyReviewMapper;
import com.company.tradejournal.mapper.DisciplineViolationMapper;
import com.company.tradejournal.mapper.PositionDailyMapper;
import com.company.tradejournal.mapper.PositionMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 每日复盘提交：一次请求同时写入复盘文字、持仓快照、账户净值、纪律违背。
 *
 * <p>【解决什么问题】复盘最难坚持的原因不是写不出来，而是要填的地方太多、彼此还对不上。
 * 这里把四个入口合成一个事务：{@code cash} 必填、收盘价来了就自动算净值和仓位占比，
 * 使用者只需要填「今天想了什么」和「违反了哪几条」。
 *
 * <p>【为什么净值要自动算】最大回撤完全取决于净值序列，而手工填净值最常见的错误是漏掉现金。
 * 所以默认 {@code equity = cash + Σ(收盘价 × 持仓数量)}，只有券商结算价与手工价不一致时才允许覆盖。
 *
 * <p>【怎么读这段代码】{@link #save} 按「先算 → 再删旧 → 后写新」的顺序：
 * 先算出净值和仓位占比，再删掉当天的旧快照与旧违背明细，最后统一写入。
 * 删旧是必要的，否则重复提交同一天会留下两份快照，回撤和仓位曲线都会翻倍。
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private static final int RATIO_SCALE = 4;

    /** 改造前的单账户数据都归到这里，空 accountId 一律落到默认账户。 */
    private static final long DEFAULT_ACCOUNT_ID = 1L;

    private final DailyReviewMapper reviewMapper;

    private final PositionMapper positionMapper;

    private final PositionDailyMapper positionDailyMapper;

    private final AccountDailyMapper accountDailyMapper;

    private final DisciplineViolationMapper violationMapper;

    public ReviewService(DailyReviewMapper reviewMapper,
                         PositionMapper positionMapper,
                         PositionDailyMapper positionDailyMapper,
                         AccountDailyMapper accountDailyMapper,
                         DisciplineViolationMapper violationMapper) {
        this.reviewMapper = reviewMapper;
        this.positionMapper = positionMapper;
        this.positionDailyMapper = positionDailyMapper;
        this.accountDailyMapper = accountDailyMapper;
        this.violationMapper = violationMapper;
    }

    /**
     * 保存某日复盘，重复提交视为修改。
     *
     * @param request 复盘入参
     * @return 保存后的复盘主记录
     * @throws BizException 持仓快照引用了不存在的持仓、或现金为负时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public DailyReviewEntity save(ReviewRequest request) {
        LocalDate date = request.reviewDate();
        long accountId = request.accountId() == null ? DEFAULT_ACCOUNT_ID : request.accountId();
        List<ReviewRequest.HoldingClose> holdings =
                request.holdings() == null ? List.of() : request.holdings();

        if (request.cash().signum() < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "现金不能为负数");
        }

        // 第一步：按持仓算市值，同时把这些信息暂存成快照实体，净值确定后再补仓位占比
        BigDecimal marketValue = BigDecimal.ZERO;
        List<PositionDailyEntity> snapshots = new ArrayList<>(holdings.size());
        for (ReviewRequest.HoldingClose holding : holdings) {
            PositionEntity position = positionMapper.selectPosition(accountId, holding.symbol());
            if (position == null) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "账户 " + accountId + " 没有持仓，无法生成快照：" + holding.symbol() + "（请先录入成交）");
            }
            BigDecimal value = holding.closePrice()
                    .multiply(BigDecimal.valueOf(position.getQuantity()));
            marketValue = marketValue.add(value);

            PositionDailyEntity snapshot = new PositionDailyEntity();
            snapshot.setAccountId(accountId);
            snapshot.setSnapshotDate(date);
            snapshot.setSymbol(holding.symbol());
            snapshot.setName(position.getName());
            snapshot.setQuantity(position.getQuantity());
            snapshot.setAvgCost(position.getAvgCost());
            snapshot.setClosePrice(holding.closePrice());
            snapshot.setStopLoss(holding.stopLoss());
            snapshot.setTakeProfit(holding.takeProfit());
            snapshot.setLogicValid(holding.logicValid() == null || holding.logicValid());
            snapshots.add(snapshot);
        }

        BigDecimal equity = request.equity() != null
                ? request.equity()
                : request.cash().add(marketValue);
        if (equity.signum() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "账户净值必须大于 0，当前：" + equity);
        }
        for (PositionDailyEntity snapshot : snapshots) {
            BigDecimal value = snapshot.getClosePrice()
                    .multiply(BigDecimal.valueOf(snapshot.getQuantity()));
            snapshot.setPositionRatio(value.divide(equity, RATIO_SCALE, RoundingMode.HALF_UP));
        }

        // 第二步：清掉当天的旧数据，避免重复提交把快照和违背次数叠加
        // 快照按账户删：两个账户同一天各有各的快照，不能互相删掉
        positionDailyMapper.delete(new LambdaQueryWrapper<PositionDailyEntity>()
                .eq(PositionDailyEntity::getAccountId, accountId)
                .eq(PositionDailyEntity::getSnapshotDate, date));
        violationMapper.delete(new LambdaQueryWrapper<DisciplineViolationEntity>()
                .eq(DisciplineViolationEntity::getReviewDate, date));
        for (PositionDailyEntity snapshot : snapshots) {
            positionDailyMapper.insert(snapshot);
        }

        // 第三步：违背明细按条写入，次数从明细条数得出，不接受调用方自报
        List<DisciplineItem> violations = request.violations() == null ? List.of() : request.violations();
        for (DisciplineItem item : violations) {
            DisciplineViolationEntity entity = new DisciplineViolationEntity();
            entity.setReviewDate(date);
            entity.setItemCode(item.name());
            entity.setItemLabel(item.label());
            violationMapper.insert(entity);
        }

        // 第四步：账户净值，每个账户一天一条；用 upsert 保证重复提交是覆盖而不是新增
        AccountDailyEntity account = new AccountDailyEntity();
        account.setAccountId(accountId);
        account.setTradeDate(date);
        account.setEquity(equity);
        account.setCash(request.cash());
        accountDailyMapper.upsert(account);

        // 第五步：复盘主记录
        DailyReviewEntity review = reviewMapper.selectById(date);
        boolean isNew = review == null;
        if (isNew) {
            review = new DailyReviewEntity();
            review.setReviewDate(date);
        }
        review.setMarketNote(request.marketNote());
        review.setStrongSector(request.strongSector());
        review.setWeakSector(request.weakSector());
        review.setMissedOpportunity(request.missedOpportunity());
        review.setEmotion(request.emotion());
        review.setLesson(request.lesson());
        review.setTomorrowTodo(request.tomorrowTodo());
        review.setViolationCount(violations.size());
        if (isNew) {
            reviewMapper.insert(review);
        } else {
            reviewMapper.updateById(review);
        }

        log.info("【每日复盘】账户 {}｜{}｜持仓 {} 只，市值 {}，现金 {}，净值 {}（{}）｜违背 {} 次",
                accountId, date, snapshots.size(), marketValue, request.cash(), equity,
                request.equity() == null ? "系统自动计算" : "手工指定", violations.size());
        if (violations.size() >= 2) {
            // 单日多次违背通常不是自制力问题，而是当天行程或仓位本身超出了可执行范围
            log.warn("【每日复盘】账户 {}｜{} 当日违背 {} 次，逐条看：{}。建议把纪律改成具体时间或动作，而不是继续自我要求",
                    accountId, date, violations.size(), violations.stream().map(DisciplineItem::label).toList());
        }
        return review;
    }

    /**
     * 查询某日复盘。
     *
     * @param date 复盘日期
     * @return 复盘记录，不存在时返回 null
     */
    public DailyReviewEntity findByDate(LocalDate date) {
        return reviewMapper.selectById(date);
    }
}
