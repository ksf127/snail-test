package com.company.service.mall.infra;

import com.company.service.mall.domain.NotifyTask;

import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Repository
public class InMemoryNotifyTaskRepository implements NotifyTaskRepository {

    private final ConcurrentMap<String, NotifyTask> tasks = new ConcurrentHashMap<>();

    @Override
    public void save(NotifyTask task) {
        tasks.put(task.paymentNo(), task);
    }

    @Override
    public Optional<NotifyTask> findByPaymentNo(String paymentNo) {
        return Optional.ofNullable(tasks.get(paymentNo));
    }

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
