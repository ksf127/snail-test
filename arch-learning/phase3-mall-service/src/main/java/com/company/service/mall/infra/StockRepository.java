package com.company.service.mall.infra;

import com.company.service.mall.domain.Stock;

import java.util.Optional;

/**
 * 库存仓储。
 *
 * <p>【解决什么问题】TCC 需要按商品找到库存对象并做预占/确认/释放，
 * 仓储负责把"商品 ID → 库存"这层寻址从业务流程里隔离出来。
 *
 * <p>【为什么这样设计】注意接口拿到的 {@link Stock} 是可变的：真实实现（Redis / DB 行锁）
 * 需要把"读-改-写"变成一次性原子操作，因此换实现时这个接口大概率要改成
 * {@code reserve(productId, quantity)} 这样的命令式方法。
 * 这里保留对象式接口，是为了让内存实现能直接展示 CAS 语义。
 *
 * <p>【怎么读这段代码】{@link #save} 建档/覆盖；{@link #findById} 取到对象后由
 * {@code StockTccService} 调其预占方法。
 */
public interface StockRepository {

    /**
     * 保存库存。幂等：同一 productId 覆盖。
     */
    void save(Stock stock);

    Optional<Stock> findById(long productId);

    long count();
}
