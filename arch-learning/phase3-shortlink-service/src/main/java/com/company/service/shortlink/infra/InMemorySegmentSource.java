package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.Base62;
import com.company.service.shortlink.domain.Range;
import com.company.service.shortlink.domain.SegmentSource;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内号段来源：单实例演示与单测使用。
 *
 * <p>【解决什么问题】它让 {@code SegmentIdGenerator} 在没有 Redis 的环境里也能跑起来：
 * 单测不需要任何中间件，本地启动也不需要额外配置。同时它是号段模式的最小实现，
 * 方便对照理解"一次拿一段"到底是什么语义。
 *
 * <p>【为什么这样设计】用 AtomicLong + CAS 而不是 synchronized：
 * 号段申请本身就是低频操作，但 CAS 是无锁的，不会让申请号段的线程阻塞其他读线程。
 * 它<b>只在单进程内唯一</b>：多实例部署时每个实例都会从 1 开始各自发号，必然重复 ——
 * 这正是生产要切到 {@link RedisSegmentSource} 的原因。
 *
 * <p>【怎么读这段代码】先看上限判断（不能超过 Base62 可表示的最大 ID），
 * 再看 CAS 循环：只有成功把 next 推进 step 的线程才拿到这一段。
 * 内部只有一个 AtomicLong，所有操作都是原子的，因此线程安全。
 */
public class InMemorySegmentSource implements SegmentSource {

    /**
     * 下一个待分配的号码，从 1 开始（0 留给"无意义/未初始化"）。
     */
    private final AtomicLong next = new AtomicLong(1);

    /**
     * 申请一段连续号码。
     *
     * @param step 号段长度，必须大于 0
     * @return 长度为 step、与其他调用互不重叠的区间
     * @throws IllegalStateException 号段会超出 Base62 可表示范围时
     */
    @Override
    public Range nextRange(int step) {
        while (true) {
            long start = next.get();
            long end = start + step;
            // end 是半开上界，最后一个可用号是 end-1，用它和 MAX_ID 比较
            if (end - 1 > Base62.MAX_ID) {
                throw new IllegalStateException("号段已耗尽: " + start);
            }
            // CAS 成功才说明这一段归当前线程所有；失败则重读重试
            if (next.compareAndSet(start, end)) {
                return new Range(start, end);
            }
        }
    }
}
