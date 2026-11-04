package com.company.service.shortlink.domain;

/**
 * 一个号段，半开区间 {@code [startInclusive, endExclusive)}。
 *
 * <p>【解决什么问题】发号器每次向存储申请"一批号"而不是"一个号"，
 * 需要一个类型把"这批号的起止"说清楚，并在构造时就拒绝非法区间。
 * 没有它，调用方就得同时传两个 long，很容易把开闭区间、+1/-1 搞错。
 *
 * <p>【为什么这样设计】用半开区间而不是闭区间：{@code size()} 直接是
 * {@code end - start}，不用 +1；循环遍历、切分号段时也不会出现差一错误。
 * 这是 JDK 里 {@code subList}、{@code Random.ints} 一贯的约定。
 *
 * <p>【怎么读这段代码】先看紧凑构造器里的三道校验（起点非负、终点必须大于起点），
 * 再看 {@code size()} 就是区间长度。record 字段全部 final，天然不可变、可安全跨线程传递。
 *
 * @param startInclusive 起始号（包含）
 * @param endExclusive   结束号（不包含）
 */
public record Range(long startInclusive, long endExclusive) {

    /**
     * 紧凑构造器：所有创建路径都会先经过这里，因此非法号段不可能被构造出来。
     */
    public Range {
        if (startInclusive < 0 || endExclusive <= startInclusive) {
            throw new IllegalArgumentException("非法号段: [" + startInclusive + ", " + endExclusive + ")");
        }
    }

    /**
     * 本段包含的号码个数。
     *
     * @return {@code endExclusive - startInclusive}
     */
    public long size() {
        return endExclusive - startInclusive;
    }
}
