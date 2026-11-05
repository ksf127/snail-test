package com.company.tradejournal.dto;

import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * 分页结果。
 *
 * <p>【解决什么问题】列表接口不能把全部流水一次吐给前端。成交越记越多，
 * 一次拉一年的数据不仅首屏变慢，前端也没法在几千行里找到想看的那一笔。
 *
 * <p>【为什么这样设计】只暴露前端渲染真正需要的四个字段：总数、当前页、每页条数、当页数据。
 * 不直接把 MyBatis-Plus 的 {@code IPage} 返回给前端，避免把 pages、orders、
 * optimizeCountSql 这类 ORM 内部字段泄漏进接口契约——将来换分页实现时前端不用改。
 *
 * <p>【怎么读这段代码】构造一律走 {@link #of(IPage)}，不要在 controller 里手工拼字段。
 */
public record PageResult<T>(long total, long page, long size, List<T> rows) {

    /**
     * 从 MyBatis-Plus 的分页对象构造。
     *
     * @param result selectPage 的返回值
     * @return 只含对外字段的分页结果
     */
    public static <T> PageResult<T> of(IPage<T> result) {
        return new PageResult<>(result.getTotal(), result.getCurrent(), result.getSize(), result.getRecords());
    }
}
