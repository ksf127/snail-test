package com.company.service.mall.infra;

import com.company.service.mall.domain.Order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 订单仓储的内存实现，额外维护 requestId → orderNo 的幂等索引。
 *
 * <p>【解决什么问题】下单接口必须幂等：同一个 requestId 重试时只能返回同一张订单，
 * 不能生成两张。单靠订单号无法查到"这个请求之前处理过没有"，所以要二级索引。
 *
 * <p>【为什么这样设计】{@code requestIndex} 用 {@code putIfAbsent}，等价于数据库上的唯一索引：
 * 并发的两个请求只有一个能占到位置，另一个读到的仍是同一张订单。
 * 这一条"唯一约束"是幂等的根基 —— 如果只在应用层先查后写，两个请求会同时查到空、
 * 然后各自建单。
 *
 * <p>【怎么读这段代码】{@link #save} 同时写主表和索引（索引只写一次，不覆盖）；
 * {@link #findByRequestId} 先查索引再回主表，索引存在但主表缺失说明数据被删，会返回空。
 * 日志一律 debug —— 内存实现每个操作都打 info 会刷屏。
 */
@Repository
public class InMemoryOrderRepository implements OrderRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryOrderRepository.class);

    private final ConcurrentMap<String, Order> orders = new ConcurrentHashMap<>();

    /**
     * requestId → orderNo 的二级索引。真实库上就是唯一索引：
     * 并发下单时由数据库保证同一个 requestId 只有一行能插入成功。
     */
    private final ConcurrentMap<String, String> requestIndex = new ConcurrentHashMap<>();

    /**
     * 保存订单。幂等：订单号相同即覆盖；幂等索引只在首次写入时生效。
     */
    @Override
    public void save(Order order) {
        orders.put(order.orderNo(), order);
        if (order.requestId() != null) {
            // putIfAbsent 模拟唯一索引：抢不到位置的请求不会覆盖别人的映射
            String previous = requestIndex.putIfAbsent(order.requestId(), order.orderNo());
            if (previous != null && !previous.equals(order.orderNo())) {
                log.debug("requestId 已绑定其他订单: requestId={}, 已绑定={}, 本次={}",
                        order.requestId(), previous, order.orderNo());
            }
        }
        log.debug("保存订单: orderNo={}, status={}", order.orderNo(), order.status());
    }

    @Override
    public Optional<Order> findByOrderNo(String orderNo) {
        return Optional.ofNullable(orders.get(orderNo));
    }

    @Override
    public Optional<Order> findByRequestId(String requestId) {
        String orderNo = requestIndex.get(requestId);
        if (orderNo == null) {
            log.debug("幂等查询未命中: requestId={}", requestId);
            return Optional.empty();
        }
        return findByOrderNo(orderNo);
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
