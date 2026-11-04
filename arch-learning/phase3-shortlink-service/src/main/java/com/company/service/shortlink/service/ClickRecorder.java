package com.company.service.shortlink.service;

import com.company.service.shortlink.infra.ClickStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 点击计数聚合器：请求线程只做内存自增，后台按秒批量落盘。
 *
 * <p>【解决什么问题】每次跳转都要记一次点击，但跳转是 100 万 QPS 的读请求。
 * 如果每个请求都同步写一次 Redis，等于把所有读请求改造成写请求，Redis 会先被打死，
 * 跳转延迟也会被写 IO 拉长。这个类把「点击上报」从主链路剥离：
 * 跳转线程只做一次本地计数，后台每秒合并成一批写入。
 * 去掉它，点击统计就会成为整个系统最先崩掉的一环。
 *
 * <p>【为什么这样设计】为什么用「内存自增 + 定时批量」：
 * <ul>
 *   <li>主链路零 IO：跳转线程不需要等待任何存储，延迟稳定；</li>
 *   <li>写放大从 100 万次/秒降到 1 次/秒/短码：批量后每次只写一个有聚合值的增量；</li>
 *   <li>代价是最多丢失 1 个刷新周期的计数（进程被 kill -9 时），
 *       这是短链点击统计可以接受的误差。</li>
 * </ul>
 * 为什么用 {@link AtomicLong#getAndSet(long)} 而不是 {@code LongAdder#sumThenReset}：
 * 后者并发调用会丢更新；getAndSet 是原子的，刷新期间新来的自增会落在下一轮。
 *
 * <p>【怎么读这段代码】{@link #record(String)} 是热路径，只有一次 map 查找 + 一次原子自增；
 * {@link #flush()} 由定时任务触发，逐个交换计数器并写入存储；
 * {@link #clicks(String)} 把「已落盘」和「未刷新」两部分加起来，给查询接口一个完整值。
 *
 * <p>【并发安全】Map 用 ConcurrentHashMap，计数器用 AtomicLong，
 * 统计量用 LongAdder。整体线程安全；flush 与 record 并发时不会丢计数，
 * 因为 getAndSet 之后到写入完成之间的新增量只会留在下一轮。
 */
@Component
public class ClickRecorder {

    private static final Logger log = LoggerFactory.getLogger(ClickRecorder.class);

    /**
     * 短码 → 当前刷新周期内尚未落盘的增量。
     */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /**
     * 最终的点击量存储（内存或 Redis）。
     */
    private final ClickStore clickStore;

    /**
     * 累计上报次数，用于验证「记录与刷新是否对得上」。
     */
    private final LongAdder recorded = new LongAdder();

    /**
     * 累计落盘次数。
     */
    private final LongAdder flushed = new LongAdder();

    public ClickRecorder(ClickStore clickStore) {
        this.clickStore = clickStore;
    }

    /**
     * 记录一次点击。跳转热路径调用，必须足够轻。
     *
     * @param code 短码
     */
    public void record(String code) {
        // computeIfAbsent 保证同一短码只有一个计数器；原子自增，不产生锁竞争
        counters.computeIfAbsent(code, key -> new AtomicLong()).incrementAndGet();
        recorded.increment();
    }

    /**
     * 把当前周期内的增量批量写入存储。
     *
     * <p>由 {@code @Scheduled} 按 {@code shortlink.click.flush-interval-millis} 周期触发，
     * 也可以在管理接口里手动调用。
     * 交换计数器用 {@code getAndSet(0)}：刷新期间新来的点击落在下一轮，不会丢失。
     */
    @Scheduled(fixedDelayString = "${shortlink.click.flush-interval-millis:1000}")
    public void flush() {
        // 刷盘前先拍一次缓冲总量：多一次全量遍历，但刷盘是秒级低频动作，这点开销可以接受
        long buffered = pendingTotal();
        // batch[0] = 本批涉及的短码数，batch[1] = 本批点击总数；只为日志统计，不影响业务
        long[] batch = new long[2];
        counters.forEach((code, counter) -> {
            long delta = counter.getAndSet(0);
            if (delta > 0) {
                clickStore.increment(code, delta);
                flushed.add(delta);
                batch[0]++;
                batch[1] += delta;
            }
        });
        if (batch[0] > 0) {
            // 刷盘前缓冲量 - 本轮落库量 = 未落盘量；并发新增可能让落库量略大于快照，按 0 兜底
            long lost = Math.max(0L, buffered - batch[1]);
            // 每次刷盘一条日志：周期是秒级，属于低频动作；没有增量时不打，避免空转噪声
            log.info("【短链-点击刷盘】刷盘前缓冲 {} 次（{} 个短码有增量）→ 刷盘后落库 {} 次，未落盘 {} 次",
                    buffered, batch[0], batch[1], lost);
            log.info("【短链-点击刷盘】判读：正常未落盘应为 0；进程被 kill -9 时最多丢一个刷新周期的计数，"
                    + "点击统计允许这点误差，换来的是主链路零 IO");
        }
    }

    /**
     * 已落库点击量 + 尚未刷新的增量。
     *
     * @param code 短码
     * @return 截至当前时刻的完整点击量
     */
    public long clicks(String code) {
        return clickStore.clicks(code) + pending(code);
    }

    /**
     * @param code 短码
     * @return 当前尚未刷新的增量，没有记录时返回 0
     */
    public long pending(String code) {
        AtomicLong counter = counters.get(code);
        return counter == null ? 0 : counter.get();
    }

    /**
     * @return 所有短码尚未刷新的增量之和
     */
    public long pendingTotal() {
        long total = 0;
        for (AtomicLong counter : counters.values()) {
            total += counter.get();
        }
        return total;
    }

    /**
     * @return 累计上报的点击次数
     */
    public long recordedTotal() {
        return recorded.sum();
    }

    /**
     * @return 累计落盘的点击次数
     */
    public long flushedTotal() {
        return flushed.sum();
    }
}
