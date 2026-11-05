package com.company.tradejournal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.tradejournal.dto.PageResult;
import com.company.tradejournal.dto.TradeRequest;
import com.company.tradejournal.entity.PositionEntity;
import com.company.tradejournal.entity.TradeEntity;
import com.company.tradejournal.entity.TradePlanEntity;
import com.company.tradejournal.mapper.PositionMapper;
import com.company.tradejournal.mapper.TradeMapper;
import com.company.tradejournal.mapper.TradePlanMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成交录入：维护持仓成本，并在卖出时算出已实现盈亏。
 *
 * <p>【解决什么问题】已实现盈亏是胜率和盈亏比的唯一来源。手工记 Excel 时，
 * 大多数人只记「卖出价 - 买入价」，漏掉买卖两边的手续费，也不处理分批建仓的加权成本，
 * 于是统计出来的胜率会系统性偏高。
 *
 * <p>【为什么这样设计】成本口径只有一套，写死在代码里：
 * 买入费用摊进成本价、卖出费用在卖出时直接扣减。这样
 * {@code realizedPnl = 卖出金额 - 卖出费用 - 加权成本 × 数量}，
 * 两边费用都被计入，且不需要额外字段区分。
 *
 * <p>【怎么读这段代码】{@link #record} 只做分发，
 * 买入走 {@link #applyBuy}（改成本价），卖出走 {@link #applySell}（算盈亏、减持仓）。
 * 两者都在同一个事务里，保证流水与持仓不会各写一半。
 */
@Service
public class TradeService {

    private static final Logger log = LoggerFactory.getLogger(TradeService.class);

    private static final int COST_SCALE = 4;

    private static final int AMOUNT_SCALE = 2;

    /** 改造前的单账户数据都归到这里，空 accountId 一律落到默认账户。 */
    private static final long DEFAULT_ACCOUNT_ID = 1L;

    private final TradeMapper tradeMapper;

    private final PositionMapper positionMapper;

    private final TradePlanMapper planMapper;

    public TradeService(TradeMapper tradeMapper, PositionMapper positionMapper, TradePlanMapper planMapper) {
        this.tradeMapper = tradeMapper;
        this.positionMapper = positionMapper;
        this.planMapper = planMapper;
    }

    /**
     * 记录一笔成交，并同步维护持仓。
     *
     * <p>并发安全：单用户复盘场景，不做并发控制；若将来多人使用，
     * 需要在 {@code t_position} 上加乐观锁版本号，否则同一标的并发买入会互相覆盖成本价。
     *
     * @param request 成交入参
     * @return 落库后的成交记录（卖出时含 realizedPnl）
     * @throws BizException 卖出数量超过持仓时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public TradeEntity record(TradeRequest request) {
        String side = request.side().trim().toUpperCase(Locale.ROOT);
        BigDecimal fee = request.fee() == null ? BigDecimal.ZERO : request.fee();
        long accountId = request.accountId() == null ? DEFAULT_ACCOUNT_ID : request.accountId();

        TradeEntity trade = new TradeEntity();
        trade.setAccountId(accountId);
        trade.setTradeDate(request.tradeDate());
        trade.setSymbol(request.symbol());
        trade.setName(request.name());
        trade.setSide(side);
        trade.setPrice(request.price());
        trade.setQuantity(request.quantity());
        trade.setFee(fee);
        trade.setIsPlanned(request.isPlanned() == null || request.isPlanned());
        trade.setPlanId(request.planId());
        trade.setReason(request.reason());
        trade.setSellCondition(request.sellCondition());

        if ("BUY".equals(side)) {
            BigDecimal avgCost = applyBuy(request, fee, accountId);
            log.info("【成交录入】账户 {}｜买入 {} {} 股 @{}，费用 {}，成交后加权成本 {}",
                    accountId, request.symbol(), request.quantity(), request.price(), fee, avgCost);
        } else {
            BigDecimal pnl = applySell(request, fee, accountId);
            trade.setRealizedPnl(pnl);
            // 判读方向写在日志里：卖出后第一眼要看的不是价格，而是这笔到底赚没赚、赚的是执行还是运气
            log.info("【成交录入】账户 {}｜卖出 {} {} 股 @{}，费用 {}，已实现盈亏 {}（{}）",
                    accountId, request.symbol(), request.quantity(), request.price(), fee, pnl,
                    pnl.signum() >= 0 ? "盈利" : "亏损");
        }

        tradeMapper.insert(trade);
        linkPlan(request.planId());
        log.info("【成交录入】结果：成交 ID={}，账户 {}，计划内={}", trade.getId(), accountId, trade.getIsPlanned());
        return trade;
    }

    /**
     * 分页查询区间内的成交明细。
     *
     * <p>筛选全部在数据库里完成，不把整段流水拉进内存再过滤：
     * 交易记录只增不减，一年就是上千行，前端分页时也得先把全量拉下来，越用越慢。
     *
     * @param from     起始日期（含）
     * @param to       结束日期（含）
     * @param keyword  代码或名称的关键字，可为空
     * @param side     BUY / SELL，为空表示不限
     * @param isPlanned 是否计划内，为空表示不限
     * @param pnl      盈亏口径：WIN 盈利、LOSS 亏损、NONE 未产生盈亏，为空表示不限
     * @param sort     排序：date 按日期倒序（默认），pnl 按已实现盈亏倒序
     * @param page     页码，从 1 开始
     * @param size     每页条数，1~200
     * @param accountId 账户 ID，为空表示全部账户
     * @return 分页结果
     */
    public PageResult<TradeEntity> list(LocalDate from, LocalDate to, String keyword, String side,
                                        Boolean isPlanned, String pnl, String sort, long page, long size,
                                        Long accountId) {
        long current = page < 1 ? 1 : page;
        long pageSize = size < 1 || size > 200 ? 20 : size;
        String kw = keyword == null ? null : keyword.trim();

        LambdaQueryWrapper<TradeEntity> wrapper = new LambdaQueryWrapper<TradeEntity>()
                .between(TradeEntity::getTradeDate, from, to)
                .eq(accountId != null, TradeEntity::getAccountId, accountId)
                .eq(side != null && !side.isBlank(), TradeEntity::getSide,
                        side == null ? null : side.trim().toUpperCase(Locale.ROOT))
                .eq(isPlanned != null, TradeEntity::getIsPlanned, isPlanned)
                .and(kw != null && !kw.isEmpty(), w -> w.like(TradeEntity::getSymbol, kw)
                        .or().like(TradeEntity::getName, kw));

        if ("WIN".equalsIgnoreCase(pnl)) {
            wrapper.gt(TradeEntity::getRealizedPnl, BigDecimal.ZERO);
        } else if ("LOSS".equalsIgnoreCase(pnl)) {
            wrapper.lt(TradeEntity::getRealizedPnl, BigDecimal.ZERO);
        } else if ("NONE".equalsIgnoreCase(pnl)) {
            wrapper.isNull(TradeEntity::getRealizedPnl);
        }

        if ("pnl".equalsIgnoreCase(sort)) {
            wrapper.orderByDesc(TradeEntity::getRealizedPnl);
        }
        wrapper.orderByDesc(TradeEntity::getTradeDate).orderByDesc(TradeEntity::getId);

        Page<TradeEntity> result = tradeMapper.selectPage(new Page<>(current, pageSize), wrapper);
        log.info("【成交查询】{} ~ {}｜账户 {}｜关键字 {}｜方向 {}｜{}｜盈亏 {}｜第 {}/{} 页，共 {} 笔",
                from, to, accountId == null ? "全部" : accountId,
                kw == null || kw.isEmpty() ? "无" : kw,
                side == null || side.isBlank() ? "全部" : side,
                isPlanned == null ? "全部来源" : (isPlanned ? "计划内" : "计划外"),
                pnl == null || pnl.isBlank() ? "全部" : pnl,
                result.getCurrent(), result.getPages(), result.getTotal());
        return PageResult.of(result);
    }

    /**
     * 当前持仓列表。
     *
     * @param accountId 账户 ID，为空表示全部账户
     * @return 持仓列表，无持仓时返回空列表
     */
    public List<PositionEntity> positions(Long accountId) {
        return positionMapper.selectHoldings(accountId);
    }

    /**
     * 买入：把买入费用摊进加权成本。
     *
     * @return 新的加权成本
     */
    private BigDecimal applyBuy(TradeRequest request, BigDecimal fee, long accountId) {
        PositionEntity position = positionMapper.selectPosition(accountId, request.symbol());
        int oldQty = position == null || position.getQuantity() == null ? 0 : position.getQuantity();
        BigDecimal oldCostAmount = position == null
                ? BigDecimal.ZERO
                : position.getAvgCost().multiply(BigDecimal.valueOf(oldQty));

        int newQty = oldQty + request.quantity();
        BigDecimal buyAmount = request.price().multiply(BigDecimal.valueOf(request.quantity()));
        BigDecimal newAvgCost = oldCostAmount.add(buyAmount).add(fee)
                .divide(BigDecimal.valueOf(newQty), COST_SCALE, RoundingMode.HALF_UP);

        PositionEntity target = position == null ? new PositionEntity() : position;
        target.setAccountId(accountId);
        target.setSymbol(request.symbol());
        target.setName(request.name());
        target.setQuantity(newQty);
        target.setAvgCost(newAvgCost);
        if (position == null) {
            positionMapper.insertPosition(target);
        } else {
            positionMapper.updatePosition(target);
        }
        return newAvgCost;
    }

    /**
     * 卖出：按加权成本算已实现盈亏，并扣减持仓（减到 0 直接删行）。
     *
     * @return 已实现盈亏
     * @throws BizException 无持仓或卖出数量超过持仓
     */
    private BigDecimal applySell(TradeRequest request, BigDecimal fee, long accountId) {
        PositionEntity position = positionMapper.selectPosition(accountId, request.symbol());
        if (position == null || position.getQuantity() == null || position.getQuantity() <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "没有持仓，不能卖出：" + request.symbol());
        }
        if (request.quantity() > position.getQuantity()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "卖出数量超过持仓：" + request.symbol() + " 当前 " + position.getQuantity()
                            + " 股，本次卖出 " + request.quantity() + " 股");
        }

        BigDecimal costAmount = position.getAvgCost()
                .multiply(BigDecimal.valueOf(request.quantity()));
        BigDecimal netProceeds = request.price()
                .multiply(BigDecimal.valueOf(request.quantity()))
                .subtract(fee);
        BigDecimal pnl = netProceeds.subtract(costAmount).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);

        int remain = position.getQuantity() - request.quantity();
        if (remain == 0) {
            positionMapper.deletePosition(accountId, request.symbol());
        } else {
            position.setQuantity(remain);
            positionMapper.updatePosition(position);
        }
        return pnl;
    }

    /**
     * 成交关联了计划时，把计划标记为已执行。
     *
     * <p>查不到计划就直接跳过：计划可能被误删，但成交本身是事实，不能因为计划丢了就拒收。
     */
    private void linkPlan(Long planId) {
        if (planId == null) {
            return;
        }
        TradePlanEntity plan = planMapper.selectById(planId);
        if (plan == null) {
            log.warn("【成交录入】计划 ID={} 不存在，成交照常记录，但不关联计划", planId);
            return;
        }
        plan.setStatus("EXECUTED");
        planMapper.updateById(plan);
    }
}
