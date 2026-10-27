package com.company.service.mall.infra;

import com.company.service.mall.domain.Stock;

import java.util.Optional;

/**
 * 库存仓储。
 *
 * <p>注意接口拿到的 {@link Stock} 是可变的：真实实现（Redis / DB 行锁）
 * 需要把"读-改-写"变成一次性原子操作，因此换实现时这个接口大概率要改成
 * {@code reserve(productId, quantity)} 这样的命令式方法。
 * 这里保留对象式接口，是为了让内存实现能直接展示 CAS 语义。
 */
public interface StockRepository {

    void save(Stock stock);

    Optional<Stock> findById(long productId);

    long count();
}
