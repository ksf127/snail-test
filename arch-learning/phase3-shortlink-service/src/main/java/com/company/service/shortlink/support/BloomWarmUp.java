package com.company.service.shortlink.support;

import com.company.service.shortlink.domain.BloomFilter;
import com.company.service.shortlink.domain.Shortlink;
import com.company.service.shortlink.infra.ShortlinkRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 启动时用存量数据重建布隆位图。
 *
 * <p>【解决什么问题】内存版布隆过滤器的位图挂在 JVM 里，进程一重启就全空。
 * 如果重启后直接对外服务，所有历史短码都会被判定为"一定不存在"，
 * 线上表现为整站 404 —— 而且日志里一条数据库错误都没有，排查方向很容易跑偏。
 * 这个类在应用就绪后把存量短码重新灌进位图，消除这个空窗。
 *
 * <p>【为什么这样设计】
 * <ul>
 *   <li>为什么用 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
 *       此时所有 Bean 已装配完毕、端口已就绪，预热用的是完整可用的组件；</li>
 *   <li>为什么只跑一次：用 CAS 抢占 {@code executed}，重复触发（事件重放、手动调用）
 *       不会重复灌入，也不会因为并发而重复计数；</li>
 *   <li>为什么这里是同步全量：为了让"热身 / 不热身"的对照实验可以随时复现。
 *       真实系统不会同步全量重建，而是：Redis 布隆持久化 + 从库分批预热 +
 *       高并发期间临时放行（预热完成前先让请求穿透到缓存/存储，而不是直接返回 404）。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】{@link #onReady()} 只是事件入口，真正逻辑在 {@link #warmUp()}：
 * 抢一次性标记 → 全量读 → 逐条 add → 记录耗时与条数。
 * 线程安全由 AtomicBoolean/AtomicLong 保证。
 */
public class BloomWarmUp {

    private static final Logger log = LoggerFactory.getLogger(BloomWarmUp.class);

    private final ShortlinkRepository repository;

    private final BloomFilter filter;

    /**
     * 一次性执行标记：0→1 成功的那个线程才真正执行预热。
     */
    private final AtomicBoolean executed = new AtomicBoolean();

    /**
     * 最近一次预热写入的短码数量，供统计与重复调用返回。
     */
    private final AtomicLong warmed = new AtomicLong();

    public BloomWarmUp(ShortlinkRepository repository, BloomFilter filter) {
        this.repository = repository;
        this.filter = filter;
    }

    /**
     * 应用就绪后自动预热。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        warmUp();
    }

    /**
     * 执行（或读取）预热结果。
     *
     * <p>重复调用直接返回上次的数量，不会重复灌位图。
     *
     * @return 本次写入位图的短码数量；重复调用时返回上次的结果
     */
    public long warmUp() {
        if (!executed.compareAndSet(false, true)) {
            log.debug("布隆位图预热已执行过，跳过: 已预热 {} 条", warmed.get());
            return warmed.get();
        }
        long start = System.nanoTime();
        List<Shortlink> links = repository.findAll();
        // 注意：这里是在启动阶段同步遍历，数据量大时会拉长启动时间；
        // 生产环境应改成分批从从库读取，避免一次性把主库和内存打满
        for (Shortlink link : links) {
            filter.add(link.code());
        }
        warmed.set(links.size());
        log.info("布隆位图预热完成: {} 条，耗时 {} ms", links.size(), (System.nanoTime() - start) / 1_000_000);
        return links.size();
    }

    /**
     * @return 已预热的短码数量
     */
    public long warmedCount() {
        return warmed.get();
    }

    /**
     * @return 是否已经执行过预热
     */
    public boolean executed() {
        return executed.get();
    }
}
