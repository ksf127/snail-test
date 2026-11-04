package com.company.service.mall.infra;

import com.company.service.mall.domain.NotifyTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 最大努力通知任务的内存实现。
 *
 * <p>【解决什么问题】通知任务要能按支付流水号去重、按到期时间批量拉取。
 * 去重靠 paymentNo 作主键，避免同一笔支付被反复通知；批量拉取靠 status + nextAttemptAt 过滤，
 * 避免空转扫描。
 *
 * <p>【为什么这样设计】以 paymentNo 为 key 的 Map 等价于"支付流水号唯一索引"，
 * 重复 enqueue 只会覆盖同一任务，不会产生多条并行通知。
 * {@link #findDue} 只读过滤，真实实现对应 {@code WHERE status='PENDING' AND next_attempt_at<=?}，
 * 多实例部署时要配 {@code SKIP LOCKED} 或抢占标记，否则会重复通知。
 *
 * <p>【怎么读这段代码】写入与查询都是一次 Map/流操作；
 * 日志一律 debug —— 内存实现每个操作都打 info 会刷屏。
 */
@Repository
public class InMemoryNotifyTaskRepository implements NotifyTaskRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryNotifyTaskRepository.class);

    private final ConcurrentMap<String, NotifyTask> tasks = new ConcurrentHashMap<>();

    /**
     * 保存通知任务。幂等：同一支付流水号覆盖原任务。
     */
    @Override
    public void save(NotifyTask task) {
        tasks.put(task.paymentNo(), task);
        log.debug("保存通知任务: paymentNo={}, status={}, attempts={}",
                task.paymentNo(), task.status(), task.attempts());
    }

    @Override
    public Optional<NotifyTask> findByPaymentNo(String paymentNo) {
        return Optional.ofNullable(tasks.get(paymentNo));
    }

    /**
     * 扫描已到期的 PENDING 任务，按到期时间升序取前 {@code limit} 条。
     *
     * <p>幂等：只读，可重复调用。
     *
     * @param now   当前时间
     * @param limit 单批上限
     */
    @Override
    public List<NotifyTask> findDue(Instant now, int limit) {
        return tasks.values().stream()
                .filter(task -> task.status() == NotifyTask.Status.PENDING)
                .filter(task -> !task.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(NotifyTask::nextAttemptAt).thenComparing(NotifyTask::paymentNo))
                .limit(limit)
                .toList();
    }

    @Override
    public long countByStatus(NotifyTask.Status status) {
        return tasks.values().stream().filter(task -> task.status() == status).count();
    }

    @Override
    public long count() {
        return tasks.size();
    }
}
