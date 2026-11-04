package com.company.service.mall.infra;

import com.company.service.mall.domain.Product;

import java.util.List;
import java.util.Optional;

/**
 * 商品仓储。
 *
 * <p>【解决什么问题】下单时要由服务端读取商品售价，不能信任客户端传的金额；
 * 同时提供建档与列表查询。
 *
 * <p>【为什么这样设计】商品是低频写、高频读的数据，接口只提供 save/find，没有删除与更新：
 * 历史订单已经冻结了成交价，商品下架也不该影响已有订单。
 * 抽成接口让内存实现与 DB 实现可替换，业务层不感知存储。
 *
 * <p>【怎么读这段代码】{@link #save} upsert；{@link #findById} 在
 * {@code OrderService.resolveLines} 里被调用，找不到即抛"商品不存在"。
 */
public interface ProductRepository {

    /**
     * 保存/覆盖商品。幂等：同一 productId 覆盖。
     */
    void save(Product product);

    Optional<Product> findById(long productId);

    List<Product> findAll();

    long count();
}
