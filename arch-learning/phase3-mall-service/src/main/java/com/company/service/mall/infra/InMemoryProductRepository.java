package com.company.service.mall.infra;

import com.company.service.mall.domain.Product;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 商品仓储的内存实现。
 *
 * <p>【解决什么问题】让商品建档与查询在零外部依赖下可运行，便于单测和本地实验；
 * 同时用 ConcurrentHashMap 暗示"真实实现里这些操作最终要落到带唯一索引的表上"。
 *
 * <p>【为什么这样设计】进程内 Map 替代表/Redis：读写都在同一 JVM，天然没有网络分区，
 * 所以只能用来验证业务流程，验证不了真正的分布式问题。
 * 换成数据库时，{@link #save} 对应 upsert，{@link #findAll} 需要分页，不能照抄全表扫描。
 *
 * <p>【怎么读这段代码】所有方法都是一次 Map 操作，没有业务规则；
 * 这里的日志一律用 debug —— 内存实现每个操作都打 info 会刷屏，
 * 下单链路一轮就能打出几十行，真正有用的业务日志反而被淹没。
 */
@Repository
public class InMemoryProductRepository implements ProductRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryProductRepository.class);

    private final ConcurrentMap<Long, Product> products = new ConcurrentHashMap<>();

    /**
     * 写入/覆盖一件商品。幂等：同样的 productId 再写一次就是覆盖。
     */
    @Override
    public void save(Product product) {
        products.put(product.productId(), product);
        log.debug("保存商品: productId={}", product.productId());
    }

    /**
     * 按 ID 查询。
     *
     * @return 不存在时返回空 Optional，由上层决定是 404 还是业务兜底
     */
    @Override
    public Optional<Product> findById(long productId) {
        return Optional.ofNullable(products.get(productId));
    }

    /**
     * 全量列表，按 productId 升序，保证实验输出可复现。
     */
    @Override
    public List<Product> findAll() {
        return products.values().stream()
                .sorted(Comparator.comparingLong(Product::productId))
                .toList();
    }

    @Override
    public long count() {
        return products.size();
    }
}
