package com.company.service.shortlink.domain;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 双缓冲号段发号器。
 *
 * <p>【解决什么问题】短链创建需要"全局唯一、单调递增"的数字 ID。
 * 每次发号都去 Redis 自增，会让发号吞吐受限于网络往返；
 * 而进程内自增又无法在多实例之间保证唯一。号段模式是两者的折中：
 * 一次向存储申请一整段（step 个号），进程内用 CAS 消费，剩得不多时后台异步预取下一段。
 * 去掉它，发号要么变得很慢（每次一次 RTT），要么在扩容后出现重复短码。
 *
 * <p>【为什么这样设计】
 * <ul>
 *   <li><b>为什么不用雪花 ID</b>：雪花依赖机器时钟 + 机器号，时钟回拨会产生重复或乱序，
 *       且 64 位 ID 转 Base62 后是 11 位，超出短链 7 位的设计；号段只依赖存储端原子自增，
 *       天然单调唯一，号码本身也很小。</li>
 *   <li><b>为什么预取</b>：取号只做一次 CAS，正常路径完全不碰网络；
 *       预取在剩余量低于阈值时提前把下一段拉回来，把"取号"和"网络往返"解耦。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】按调用顺序读：
 * <ol>
 *   <li>{@link #nextId()}：CAS 抢占 {@code cursor}，成功就返回，失败重试；越界就 {@link #rotate()}；</li>
 *   <li>{@link #maybePrefetch(long)}：剩余量低于 {@code step * prefetchThreshold} 时，用
 *       {@code prefetching} 这个 0/1 原子标记保证同一时刻只有一个预取任务；</li>
 *   <li>{@link #rotate()} 与 {@link #doPrefetch()}：所有"取号段"动作都在 {@code rotateLock} 内串行。</li>
 * </ol>
 * 三条必须守住的性质：
 * <ol>
 *   <li><b>绝不重复</b>：所有 {@link SegmentSource#nextRange(int)} 调用都在同一把锁内串行，
 *       保证拿到的号段单调递增，因此预取与同步取不会出现"回退到更小号段"</li>
 *   <li><b>耗尽才切换</b>：切换发生在 cursor 越过 limit 之后，切换期间新来的线程要么在锁外自旋，
 *       要么进入锁内复查，不会取到旧段的号</li>
 *   <li><b>允许浪费</b>：进程重启会丢掉未用完的号段；异步预取拿到的段也可能被丢弃。
 *       浪费只是少用几个号，重复才是事故</li>
 * </ol>
 *
 * <p>【并发安全】{@code cursor} 用 CAS、{@code limit}/{@code prefetched} 用 volatile
 * 发布、号段切换用 {@code rotateLock}、预取开关用 CAS。因此本类整体线程安全。
 */
public class SegmentIdGenerator implements IdGenerator {

    private static final Logger log = LoggerFactory.getLogger(SegmentIdGenerator.class);

    /**
     * 号段来源：内存或 Redis 实现，决定多实例下是否天然唯一。
     */
    private final SegmentSource source;

    /**
     * 每段号码数量。越大，访问存储的次数越少，但进程重启时浪费的号码也越多。
     */
    private final int step;

    /**
     * 预取阈值，按 step 的比例计算：剩余量低于 {@code step * prefetchThreshold} 时触发预取。
     * 例如 step=10000、阈值=0.2，就是剩余不足 2000 个时开始拉下一段。
     */
    private final double prefetchThreshold;

    /**
     * 预取任务执行器。构造时传 null 会退化成 {@code Runnable::run}（调用线程同步执行），
     * 这样单测里不依赖线程池也能跑。
     */
    private final Executor executor;

    /**
     * 号段切换锁：只在"取号段"时争用，正常取号路径完全不经过它。
     */
    private final Object rotateLock = new Object();

    /**
     * 当前号段的下一个可用号。所有取号线程通过 CAS 在这一个变量上竞争。
     */
    private final AtomicLong cursor = new AtomicLong(0);

    /**
     * 当前号段的上界（不含）。用 volatile 发布：{@code cursor} 更新后其他线程要能看到新上界。
     */
    private volatile long limit;

    /**
     * 预取回来、尚未启用的下一段。用 volatile 发布，写入都发生在 {@code rotateLock} 内。
     */
    private volatile Range prefetched;

    /**
     * 预取开关：0 表示空闲，1 表示已有任务在跑。用 CAS 抢占，保证不会同时发起多个预取。
     */
    private final AtomicInteger prefetching = new AtomicInteger();

    /**
     * 号段来源被调用次数，用于验证"预取是否真的把往返次数降下来了"。
     */
    private final LongAdder sourceCalls = new LongAdder();

    /**
     * 成功预取到号段的次数。
     */
    private final LongAdder prefetchedSegments = new LongAdder();

    /**
     * @param source            号段来源，不能为 null
     * @param step              每段号码数量，必须大于 0
     * @param prefetchThreshold 预取阈值比例，必须落在 [0,1]；0 表示关闭预取
     * @param executor          预取执行器，null 表示在调用线程同步执行
     * @throws IllegalArgumentException 任一参数越界时
     */
    public SegmentIdGenerator(SegmentSource source, int step, double prefetchThreshold, Executor executor) {
        if (source == null) {
            throw new IllegalArgumentException("SegmentSource 不能为空");
        }
        if (step <= 0) {
            throw new IllegalArgumentException("step 必须大于 0: " + step);
        }
        if (prefetchThreshold < 0 || prefetchThreshold > 1) {
            throw new IllegalArgumentException("prefetchThreshold 必须落在 [0,1]: " + prefetchThreshold);
        }
        this.source = source;
        this.step = step;
        this.prefetchThreshold = prefetchThreshold;
        this.executor = executor == null ? Runnable::run : executor;
    }

    /**
     * 取下一个全局唯一 ID。
     *
     * <p>热路径：一次 {@code cursor.get()} + 一次 CAS，没有锁、没有网络。
     * CAS 失败说明别的线程抢先取走了这个号，重读重试即可。
     *
     * @return 全局唯一且单调递增的 ID
     * @throws IllegalStateException 号段来源返回非法数据，或号段耗尽时
     */
    @Override
    public long nextId() {
        while (true) {
            long id = cursor.get();
            if (id < limit) {
                // 只有抢到 [id, id+1) 的线程才能返回 id；失败就重读，避免重复发号
                if (cursor.compareAndSet(id, id + 1)) {
                    // 取号成功后再检查水位：把预取放在返回之前，下一段会尽早就位
                    maybePrefetch(id + 1);
                    return id;
                }
                continue;
            }
            // 当前段已耗尽：进入串行切换
            rotate();
        }
    }

    /**
     * cursor 已越过 limit：串行切换号段。
     *
     * <p>锁内必须复查 {@code cursor.get() < limit}：排队等锁的线程可能已经有别的线程切好了段。
     */
    private void rotate() {
        synchronized (rotateLock) {
            if (cursor.get() < limit) {
                // 别的线程已经切好了，直接回去取号
                return;
            }
            // 必须在锁内复查 prefetched：异步预取也是在锁内写入的
            if (prefetched == null) {
                // 没有预取可用，只能同步取：这一次取号会带上一次存储往返
                log.info("号段已耗尽，同步申请新号段: step={}", step);
                prefetched = fetch();
            } else {
                log.info("切换到预取号段: [{}, {})", prefetched.startInclusive(), prefetched.endExclusive());
            }
            Range next = prefetched;
            prefetched = null;
            // 先发布 limit 再发布 cursor 的先后不是严格必要的（两者都在锁内），
            // 但保持"新段整体可见"的直觉，避免读到一半新段一半旧段
            cursor.set(next.startInclusive());
            limit = next.endExclusive();
        }
    }

    /**
     * 向号段来源申请一段，并校验长度。
     *
     * <p>长度校验是防线而不是冗余：如果来源返回的区间长度不对，
     * 发号器会按错误的边界切段，可能直接造成重复号。
     *
     * @return 长度恰好为 {@code step} 的号段
     * @throws IllegalStateException 来源返回 null 或长度不符时
     */
    private Range fetch() {
        sourceCalls.increment();
        log.info("申请号段: step={}", step);
        Range range = source.nextRange(step);
        if (range == null || range.size() != step) {
            throw new IllegalStateException("号段来源返回了非法号段: " + range + "，期望长度 " + step);
        }
        return range;
    }

    /**
     * 复查剩余量，必要时提交一次异步预取。
     *
     * <p>这里是"高频"路径，只做几次原子读，不产生日志、不阻塞。
     *
     * @param nextCursor 本次取号之后的 cursor 位置
     */
    private void maybePrefetch(long nextCursor) {
        if (prefetchThreshold <= 0) {
            return;
        }
        long threshold = Math.max(1, (long) Math.ceil(step * prefetchThreshold));
        long remaining = limit - nextCursor;
        // 剩余充足，或已有预取在跑/已就绪，都不需要再取
        if (remaining >= threshold || prefetched != null) {
            return;
        }
        // 只有一个线程能把开关从 0 改成 1，其余的立刻返回，避免预取任务风暴
        if (!prefetching.compareAndSet(0, 1)) {
            return;
        }
        try {
            executor.execute(this::doPrefetch);
        } catch (RuntimeException e) {
            // 线程池拒绝时释放开关，下次取号会再尝试；取号本身仍可走同步路径
            prefetching.set(0);
            log.warn("号段预取任务提交失败，将由取号线程同步兜底", e);
        }
    }

    /**
     * 预取任务体：在锁内申请下一段并放进 {@code prefetched}。
     */
    private void doPrefetch() {
        try {
            synchronized (rotateLock) {
                Range range = fetch();
                prefetchedSegments.increment();
                if (prefetched == null) {
                    prefetched = range;
                    log.info("号段预取成功: [{}, {})", range.startInclusive(), range.endExclusive());
                } else {
                    // 同步路径已经补上了号段；丢弃后拿到的这一段（更靠后的号段），只会浪费不会重复
                    log.debug("丢弃重复预取号段: {}", range);
                }
            }
        } catch (RuntimeException e) {
            log.warn("号段预取失败，后续取号会走同步路径", e);
        } finally {
            // 无论成功失败都要释放开关，否则预取会永久停摆
            prefetching.set(0);
        }
    }

    /**
     * 号段来源被调用次数，用于验证"预取是否真的把往返次数降下来了"。
     *
     * @return 累计调用号段来源的次数
     */
    public long sourceCalls() {
        return sourceCalls.sum();
    }

    /**
     * @return 累计成功预取的号段个数
     */
    public long prefetchedSegments() {
        return prefetchedSegments.sum();
    }

    /**
     * @return 当前号段里还没发出去的号码数量，可用于监控"是不是快用完了"
     */
    public long remainingInSegment() {
        return limit - cursor.get();
    }

    /**
     * @return 每段号码数量
     */
    public int step() {
        return step;
    }
}
