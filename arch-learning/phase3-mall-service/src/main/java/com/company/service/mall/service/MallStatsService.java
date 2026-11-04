package com.company.service.mall.service;

import com.company.service.mall.domain.NotifyTask;
import com.company.service.mall.domain.Order;
import com.company.service.mall.domain.OutboxMessage;
import com.company.service.mall.domain.TccTransaction;
import com.company.service.mall.infra.MessagePublisher;
import com.company.service.mall.infra.NotificationGateway;
import com.company.service.mall.infra.NotifyTaskRepository;
import com.company.service.mall.infra.OrderRepository;
import com.company.service.mall.infra.OutboxMessageRepository;
import com.company.service.mall.infra.PaymentRepository;
import com.company.service.mall.infra.ProductRepository;
import com.company.service.mall.infra.StockRepository;
import com.company.service.mall.infra.TccTransactionRepository;
import com.company.starter.lock.DistributedLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

/**
 * 运行期观测快照：把"库存预占 / outbox / 通知 / 投影"四条链路的状态摊平到一个接口里。
 *
 * <p>【解决什么问题】分布式事务出问题时，单看订单状态是看不出根因的：
 * "订单还是 CREATED"可能是正常待支付，也可能是库存预占残留。
 * 必须同时看到 TCC 分支分布、outbox 积压/死信、通知重试次数、投影进度，
 * 才能判断"数据最终一致"是正常收敛还是碰巧没出错。
 *
 * <p>【为什么这样设计】只读聚合，不写任何状态，所以可以随时调用、重复调用。
 * 一次性把四张表的状态拼成一个不可变 Snapshot，避免调用方分多次查询时
 * 看到"读到一半状态变了"的撕裂视图 —— 这在排查对账差异时非常关键。
 *
 * <p>【怎么读这段代码】{@link #snapshot()} 逐项调用各仓储的 count/countByStatus，
 * 再拼上投影快照和当前生效的 provider（内存 / RabbitMQ / Redis），
 * 从返回的 Snapshot 字段名即可看出每条链路处于什么阶段。
 */
@Service
public class MallStatsService {

    private static final Logger log = LoggerFactory.getLogger(MallStatsService.class);

    private final ProductRepository products;

    private final StockRepository stocks;

    private final OrderRepository orders;

    private final PaymentRepository payments;

    private final OutboxMessageRepository outbox;

    private final NotifyTaskRepository notifyTasks;

    private final TccTransactionRepository tccTransactions;

    private final StockTccService stockTccService;

    private final OrderEventProjector projector;

    private final MessagePublisher publisher;

    private final NotificationGateway notificationGateway;

    private final DistributedLock lock;

    public MallStatsService(ProductRepository products,
                            StockRepository stocks,
                            OrderRepository orders,
                            PaymentRepository payments,
                            OutboxMessageRepository outbox,
                            NotifyTaskRepository notifyTasks,
                            TccTransactionRepository tccTransactions,
                            StockTccService stockTccService,
                            OrderEventProjector projector,
                            MessagePublisher publisher,
                            NotificationGateway notificationGateway,
                            DistributedLock lock) {
        this.products = products;
        this.stocks = stocks;
        this.orders = orders;
        this.payments = payments;
        this.outbox = outbox;
        this.notifyTasks = notifyTasks;
        this.tccTransactions = tccTransactions;
        this.stockTccService = stockTccService;
        this.projector = projector;
        this.publisher = publisher;
        this.notificationGateway = notificationGateway;
        this.lock = lock;
    }

    /**
     * 生成全链路状态快照。
     *
     * <p>幂等性：幂等（纯读）。注意各仓储分别计数，严格来说不是同一时刻的原子视图，
     * 仅用于观测与排障，不用于对账结论。
     *
     * @return 商品/库存/订单/支付/TCC/outbox/通知/投影的当前状态
     */
    public Snapshot snapshot() {
        Snapshot snapshot = new Snapshot(
                products.count(),
                stocks.count(),
                orders.count(),
                payments.count(),
                orders.countByStatus(Order.Status.CREATED),
                orders.countByStatus(Order.Status.PAID),
                orders.countByStatus(Order.Status.CANCELLED),
                orders.countByStatus(Order.Status.CLOSED),
                outbox.countByStatus(OutboxMessage.Status.PENDING),
                outbox.countByStatus(OutboxMessage.Status.SENT),
                outbox.countByStatus(OutboxMessage.Status.DEAD),
                notifyTasks.countByStatus(NotifyTask.Status.PENDING),
                notifyTasks.countByStatus(NotifyTask.Status.SUCCEEDED),
                notifyTasks.countByStatus(NotifyTask.Status.DEAD),
                tccTransactions.countByStatus(TccTransaction.Status.TRIED),
                tccTransactions.countByStatus(TccTransaction.Status.CONFIRMED),
                tccTransactions.countByStatus(TccTransaction.Status.CANCELLED),
                stockTccService.stats(),
                projector.snapshot(),
                publisher.provider(),
                notificationGateway.provider(),
                lock.provider());
        log.debug("商城状态快照: orders={}, outboxPending={}, notifyPending={}, tccTried={}",
                snapshot.orders(), snapshot.outboxPending(), snapshot.notifyPending(), snapshot.tccTried());
        return snapshot;
    }

    public record Snapshot(long products,
                           long stocks,
                           long orders,
                           long payments,
                           long ordersCreated,
                           long ordersPaid,
                           long ordersCancelled,
                           long ordersClosed,
                           long outboxPending,
                           long outboxSent,
                           long outboxDead,
                           long notifyPending,
                           long notifySucceeded,
                           long notifyDead,
                           long tccTried,
                           long tccConfirmed,
                           long tccCancelled,
                           StockTccService.TccStats tcc,
                           OrderEventProjector.Snapshot projector,
                           String publisherProvider,
                           String notificationProvider,
                           String lockProvider) {
    }
}
