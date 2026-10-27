package com.company.service.archlab.cache;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

/**
 * 模拟数据库：用固定耗时 + 回源计数，替代真实 MySQL。
 *
 * <p>所有缓存实验的结论都建立在"回源次数"上，所以这里把回源做成可观测的：
 * {@link #loadCount()} 就是打到数据库的 QPS 累计值。
 */
public final class ProductRepository {

    private static final int CATALOG_SIZE = 50;

    private final Map<Long, ProductView> catalog = new ConcurrentHashMap<>();

    private final AtomicLong loads = new AtomicLong();

    private final long loadMillis;

    public ProductRepository(long loadMillis) {
        if (loadMillis < 0) {
            throw new IllegalArgumentException("loadMillis 不能为负");
        }
        this.loadMillis = loadMillis;
        for (long id = 1; id <= CATALOG_SIZE; id++) {
            catalog.put(id, new ProductView(id, "product-" + id, BigDecimal.valueOf(100 + id), 1000));
        }
    }

    /**
     * @return 商品不存在时返回 {@link Optional#empty()}，与"缓存未命中"区分开
     */
    public Optional<ProductView> find(long id) {
        loads.incrementAndGet();
        sleep(loadMillis);
        return Optional.ofNullable(catalog.get(id));
    }

    public long loadCount() {
        return loads.get();
    }

    public Set<Long> ids() {
        return Collections.unmodifiableSet(catalog.keySet());
    }

    public boolean exists(long id) {
        return catalog.containsKey(id);
    }

    public LongStream existingIds() {
        return catalog.keySet().stream().mapToLong(Long::longValue).sorted();
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模拟数据库查询被中断", e);
        }
    }
}
