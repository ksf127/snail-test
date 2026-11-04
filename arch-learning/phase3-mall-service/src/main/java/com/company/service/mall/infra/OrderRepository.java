package com.company.service.mall.infra;

import com.company.service.mall.domain.Order;

import java.util.List;
import java.util.Optional;

/**
 * 订单仓储：主键查询 + 请求号幂等查询 + 状态统计。
 *
 * <p>【解决什么问题】下单/支付/取消/关单都要读写订单，且必须支持"同一请求号只建一张单"。
 *
 * <p>【为什么这样设计】抽出接口是为了把"业务规则"和"存储实现"分开：
 * 内存实现用于本地跑通链路，落库实现只要遵守同一组语义即可替换。
 * 关键在于 {@link #findByRequestId} 必须由存储层保证唯一性，
 * 而不是让调用方"先查后写"——后者在并发下一定会建出两张单。
 *
 * <p>【怎么读这段代码】{@link #save} 是 upsert；两个 find 各自对应一个唯一键；
 * {@link #findAll} 只用于扫描关单，真实实现必须分页。
 */
public interface OrderRepository {

    /**
     * 保存或更新订单（以订单号为主键）。
     *
     * <p>幂等：订单号相同即覆盖。
     */
    void save(Order order);

    Optional<Order> findByOrderNo(String orderNo);

    /**
     * 幂等查询：同一个 requestId 必须能找回第一次创建的订单。
     *
     * <p>幂等：只读，可重复调用。
     */
    Optional<Order> findByRequestId(String requestId);

    List<Order> findAll();

    long countByStatus(Order.Status status);

    long count();
}
