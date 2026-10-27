package com.company.service.mall.infra;

import com.company.service.mall.domain.Order;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryOrderRepository implements OrderRepository {

    private final ConcurrentMap<String, Order> orders = new ConcurrentHashMap<>();

    /**
     * requestId → orderNo 的二级索引。真实库上就是唯一索引：
     * 并发下单时由数据库保证同一个 requestId 只有一行能插入成功。
     */
    private final ConcurrentMap<String, String> requestIndex = new ConcurrentHashMap<>();

    @Override
    public void save(Order order) {
        orders.put(order.orderNo(), order);
        if (order.requestId() != null) {
            requestIndex.putIfAbsent(order.requestId(), order.orderNo());
        }
    }

    @Override
    public Optional<Order> findByOrderNo(String orderNo) {
        return Optional.ofNullable(orders.get(orderNo));
    }

    @Override
    public Optional<Order> findByRequestId(String requestId) {
        String orderNo = requestIndex.get(requestId);
        return orderNo == null ? Optional.empty() : findByOrderNo(orderNo);
    }

    @Override
    public List<Order> findAll() {
        return orders.values().stream()
                .sorted(Comparator.comparing(Order::createdAt))
                .toList();
    }

    @Override
    public long countByStatus(Order.Status status) {
        return orders.values().stream().filter(order -> order.status() == status).count();
    }

    @Override
    public long count() {
        return orders.size();
    }
}
