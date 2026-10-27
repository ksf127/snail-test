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
 * <p>这是布隆过滤器最容易漏掉的一步：内存位图随进程消失，重启后如果直接上线，
 * 所有历史短码都会被判定为"一定不存在"，线上表现为整站 404 —— 而且日志里一条数据库错误都没有。
 *
 * <p>真实系统不会同步全量重建，而是：Redis 布隆持久化 + 从库分批预热 + 高并发期间临时放行。
 * 这里保留同步版本，是为了让"热身 / 不热身"的对照实验可以随时复现。
 */
public class BloomWarmUp {

    private static final Logger log = LoggerFactory.getLogger(BloomWarmUp.class);

    private final ShortlinkRepository repository;

    private final BloomFilter filter;

    private final AtomicBoolean executed = new AtomicBoolean();

    private final AtomicLong warmed = new AtomicLong();

    public BloomWarmUp(ShortlinkRepository repository, BloomFilter filter) {
        this.repository = repository;
        this.filter = filter;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        warmUp();
    }

    /**
     * @return 本次写入位图的短码数量
     */
    public long warmUp() {
        if (!executed.compareAndSet(false, true)) {
            return warmed.get();
        }
        long start = System.nanoTime();
        List<Shortlink> links = repository.findAll();
        for (Shortlink link : links) {
            filter.add(link.code());
        }
        warmed.set(links.size());
        log.info("布隆位图预热完成: {} 条，耗时 {} ms", links.size(), (System.nanoTime() - start) / 1_000_000);
        return links.size();
    }

    public long warmedCount() {
        return warmed.get();
    }

    public boolean executed() {
        return executed.get();
    }
}
