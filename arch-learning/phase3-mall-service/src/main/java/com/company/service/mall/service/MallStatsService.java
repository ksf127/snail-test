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

import org.springframework.stereotype.Service;

/**
 * 运行期观测快照：把"库存预占 / outbox / 通知 / 投影"四条链路的状态摊平到一个接口里。
 *
 * <p>做分布式事务实验时，只看业务结果是看不出问题的 ——
 * 必须能同时看到 TCC 的空回滚次数、outbox 的死信条数、通知的重试次数，
 * 才能判断"数据最终一致"到底是正常收敛还是碰巧没出错。
 */
@Service
public class MallStatsService {

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

    public Snapshot snapshot() {
        return new Snapshot(
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
