package com.company.service.mall.infra;

import com.company.service.mall.domain.Stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 库存仓储的内存实现。
 *
 * <p>【解决什么问题】让 TCC 的 Try/Confirm/Cancel 在单进程内跑通，便于观察库存从
 * "可用"到"冻结"再到"扣减/归还"的完整流转，而不必先搭一套 Redis/DB。
 *
 * <p>【为什么这样设计】Map 里放的是可变的 {@link Stock} 对象：预占、确认、释放都直接改对象内部
 * 的原子变量，仓储只负责按商品找对象。这样内存实现能忠实演示 CAS 语义。
 * 换成 Redis 时必须整体改成 Lua/命令式接口，因为"取回对象 → 改 → 写回"在分布式下不是原子的。
 *
 * <p>【怎么读这段代码】只有一次 Map 操作，没有业务判断；
 * 日志一律用 debug —— 内存实现每个操作都打 info 会刷屏，把下单主流程的日志淹没。
 */
@Repository
public class InMemoryStockRepository implements StockRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStockRepository.class);

    private final ConcurrentMap<Long, Stock> stocks = new ConcurrentHashMap<>();

    /**
     * 保存/覆盖某个商品的库存。幂等：同一个 productId 再写一次是覆盖。
     */
    @Override
    public void save(Stock stock) {
        stocks.put(stock.productId(), stock);
        log.debug("保存库存: productId={}, available={}, reserved={}",
                stock.productId(), stock.available(), stock.reserved());
    }

    /**
     * 按商品查库存。
     *
     * @return 不存在时返回空 Optional；库存不足与商品不存在由上层区分处理
     */
    @Override
    public Optional<Stock> findById(long productId) {
        return Optional.ofNullable(stocks.get(productId));
    }

    @Override
    public long count() {
        return stocks.size();
    }
}
