package com.company.service.mall.infra;

import com.company.service.mall.domain.Order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    void save(Order order);

    Optional<Order> findByOrderNo(String orderNo);

    /**
     * 幂等查询：同一个 requestId 必须能找回第一次创建的订单。
     */
    Optional<Order> findByRequestId(String requestId);

    List<Order> findAll();

    long countByStatus(Order.Status status);

    long count();
}
