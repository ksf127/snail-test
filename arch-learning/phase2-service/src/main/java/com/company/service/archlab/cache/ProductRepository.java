package com.company.service.archlab.cache;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模拟数据库：用固定耗时 + 回源计数，替代真实 MySQL。
 *
 * <p>【解决什么问题】缓存实验要回答"保护了多少次数据库访问"，但真实数据库的 QPS、
 * 慢查询、连接池都会干扰实验，而且不方便在单测里跑。这里用一个内存 Map 模拟数据库，
 * 把"回源耗时"和"回源次数"做成可配置、可观测的量，实验结论才能量化。
 *
 * <p>【为什么这样设计】{@link #loadMillis} 是故意保留的：如果查库不耗时，
 * 击穿、雪崩这类并发问题根本复现不出来。{@link #loadCount()} 用 {@link AtomicLong}
 * 累计每次 {@link #find}，它就是实验输出里的 {@code dbLoads}——
 * 缓存有没有生效，只看这一个数字，不靠感觉。
 * 注意这个类只负责"查"，没有写方法：本阶段的实验关注读路径的缓存保护。
 *
 * <p>【怎么读这段代码】先看构造函数预置的 50 个商品（id 从 1 到 50），
 * 再看 {@link #find} 的三步：计数 → 模拟耗时 → 返回可空结果；
 * 其中 {@link Optional#empty()} 表示"数据库里确实没有"，与"缓存未命中"是两件事。
 *
 * <p><b>日志说明：</b>这类处于每次请求回源路径上的类，类注释统一约定只用
 * {@code log.debug}，避免高 QPS 下日志把磁盘打满。
 */
public final class ProductRepository {

    private static final Logger log = LoggerFactory.getLogger(ProductRepository.class);

    private static final int CATALOG_SIZE = 50;

    private final Map<Long, ProductView> catalog = new ConcurrentHashMap<>();

    private final AtomicLong loads = new AtomicLong();

    private final long loadMillis;

    /**
     * @param loadMillis 每次查询模拟的数据库耗时（毫秒），用来放大并发竞态的影响
     * @throws IllegalArgumentException 耗时为负时抛出
     */
    public ProductRepository(long loadMillis) {
        if (loadMillis < 0) {
            throw new IllegalArgumentException("loadMillis 不能为负");
        }
        this.loadMillis = loadMillis;
        // 固定 50 条数据：id 从 1 开始，方便实验里用 1 当热点 key、用 999999 当不存在的 key。
        for (long id = 1; id <= CATALOG_SIZE; id++) {
            catalog.put(id, new ProductView(id, "product-" + id, BigDecimal.valueOf(100 + id), 1000));
        }
    }

    /**
     * 模拟一次按主键查询。
     *
     * @param id 商品 id
     * @return 商品存在时返回商品；不存在时返回 {@link Optional#empty()}，
     *         与"缓存未命中"区分开（前者是数据本身没有，后者是缓存里没放）
     */
    public Optional<ProductView> find(long id) {
        loads.incrementAndGet();
        // 耗时放在计数之后：即使查询被中断，也算作一次真实的回源尝试。
        sleep(loadMillis);
        Optional<ProductView> result = Optional.ofNullable(catalog.get(id));
        log.debug("【DB】查询 id={}，耗时={}ms，结果={}", id, loadMillis, result.isPresent() ? "存在" : "不存在");
        return result;
    }

    /** @return 累计回源次数，实验里的 {@code dbLoads} 指标 */
    public long loadCount() {
        return loads.get();
    }

    /** @return 只读的已有商品 id 集合 */
    public Set<Long> ids() {
        return Collections.unmodifiableSet(catalog.keySet());
    }

    /**
     * @param id 商品 id
     * @return 商品是否存在
     */
    public boolean exists(long id) {
        return catalog.containsKey(id);
    }

    /** @return 已排序的商品 id 流，供布隆过滤器预热和雪崩实验选取 key */
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
            // 恢复中断标记，让上层能感知到停止信号，而不是把异常吞掉。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模拟数据库查询被中断", e);
        }
    }
}
