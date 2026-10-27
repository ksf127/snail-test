package com.company.service.shortlink.domain;

/**
 * 半开区间 [startInclusive, endExclusive)。
 */
public record Range(long startInclusive, long endExclusive) {

    public Range {
        if (startInclusive < 0 || endExclusive <= startInclusive) {
            throw new IllegalArgumentException("非法号段: [" + startInclusive + ", " + endExclusive + ")");
        }
    }

    public long size() {
        return endExclusive - startInclusive;
    }
}
