package com.company.tradejournal.service;

import java.time.LocalDate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.tradejournal.dto.PageResult;
import com.company.tradejournal.dto.PlanRequest;
import com.company.tradejournal.entity.TradePlanEntity;
import com.company.tradejournal.mapper.TradePlanMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 盘前计划：写下「若 X 则 Y」。
 *
 * <p>【解决什么问题】没有计划就无法区分「按体系交易」和「临时起意」。
 * 计划落库后，成交带 {@code planId}，月度统计就能直接给出计划内外的胜率差——
 * 这个差值通常比任何技术指标都更能说明问题。
 *
 * <p>【为什么这样设计】计划只允许三个状态，且只有「已执行 / 已取消」两种收尾方式。
 * 不允许删除计划，因为删掉就无法统计「计划了多少条、实际执行了几条」。
 */
@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final TradePlanMapper planMapper;

    public PlanService(TradePlanMapper planMapper) {
        this.planMapper = planMapper;
    }

    /**
     * 新建计划。
     *
     * @param request 计划内容
     * @return 落库后的计划
     */
    @Transactional(rollbackFor = Exception.class)
    public TradePlanEntity create(PlanRequest request) {
        TradePlanEntity plan = new TradePlanEntity();
        plan.setPlanDate(request.planDate());
        plan.setSymbol(request.symbol());
        plan.setName(request.name());
        plan.setTriggerDesc(request.triggerDesc());
        plan.setActionDesc(request.actionDesc());
        plan.setReason(request.reason());
        plan.setStatus("PENDING");
        planMapper.insert(plan);
        log.info("【交易计划】新建：{} {}｜若 [{}] 则 [{}]｜日期 {}，ID={}",
                request.symbol(), request.name(), request.triggerDesc(), request.actionDesc(),
                request.planDate(), plan.getId());
        if (request.reason() == null || request.reason().isBlank()) {
            // 不阻断，但要提示：没有依据的计划，事后无法判断对错，只能在结果上事后归因
            log.warn("【交易计划】ID={} 没有填写依据，复盘时无法判断是逻辑错了还是执行错了", plan.getId());
        }
        return plan;
    }

    /**
     * 取消计划（未触发就放弃时使用）。
     *
     * @param id 计划 ID
     * @return 更新后的计划
     * @throws BizException 计划不存在时抛出
     */
    @Transactional(rollbackFor = Exception.class)
    public TradePlanEntity cancel(Long id) {
        TradePlanEntity plan = planMapper.selectById(id);
        if (plan == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "计划不存在：" + id);
        }
        plan.setStatus("CANCELLED");
        planMapper.updateById(plan);
        log.info("【交易计划】取消：ID={}，{} {}（原状态 {}）", id, plan.getSymbol(), plan.getName(), plan.getStatus());
        return plan;
    }

    /**
     * 分页查询计划，支持日期区间、状态与代码/名称关键字。
     *
     * @param from    起始日期（含），可为空表示不限
     * @param to      结束日期（含），可为空表示不限
     * @param status  PENDING / EXECUTED / CANCELLED，为空表示不限
     * @param keyword 代码或名称关键字，可为空
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @return 分页结果，按日期倒序
     */
    public PageResult<TradePlanEntity> search(LocalDate from, LocalDate to, String status, String keyword,
                                              long page, long size) {
        long current = page < 1 ? 1 : page;
        long pageSize = size < 1 || size > 200 ? 20 : size;
        String kw = keyword == null ? null : keyword.trim();

        LambdaQueryWrapper<TradePlanEntity> wrapper = new LambdaQueryWrapper<TradePlanEntity>()
                .ge(from != null, TradePlanEntity::getPlanDate, from)
                .le(to != null, TradePlanEntity::getPlanDate, to)
                .eq(status != null && !status.isBlank(), TradePlanEntity::getStatus, status)
                .and(kw != null && !kw.isEmpty(), w -> w.like(TradePlanEntity::getSymbol, kw)
                        .or().like(TradePlanEntity::getName, kw))
                .orderByDesc(TradePlanEntity::getPlanDate)
                .orderByDesc(TradePlanEntity::getId);

        Page<TradePlanEntity> result = planMapper.selectPage(new Page<>(current, pageSize), wrapper);
        log.info("【交易计划】查询 {} ~ {}｜状态 {}｜关键字 {}｜第 {}/{} 页，共 {} 条",
                from == null ? "不限" : from, to == null ? "不限" : to,
                status == null || status.isBlank() ? "全部" : status,
                kw == null || kw.isEmpty() ? "无" : kw,
                result.getCurrent(), result.getPages(), result.getTotal());
        return PageResult.of(result);
    }
}
