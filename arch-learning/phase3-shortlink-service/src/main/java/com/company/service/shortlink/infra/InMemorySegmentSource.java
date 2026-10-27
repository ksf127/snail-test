package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.Base62;
import com.company.service.shortlink.domain.Range;
import com.company.service.shortlink.domain.SegmentSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内号段来源：单实例演示与单测使用。
 *
 * <p>多实例部署时会各自发号，必然重复 —— 这正是切到 Redis 号段的原因。
 */
public class InMemorySegmentSource implements SegmentSource {

    private final AtomicLong next = new AtomicLong(1);

    @Override
    public Range nextRange(int step) {
        while (true) {
            long start = next.get();
            long end = start + step;
            if (end - 1 > Base62.MAX_ID) {
                throw new IllegalStateException("号段已耗尽: " + start);
            }
            if (next.compareAndSet(start, end)) {
                return new Range(start, end);
            }
        }
    }
}
