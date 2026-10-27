package com.company.service.mall.infra;

import com.company.service.mall.domain.NotifyTask;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface NotifyTaskRepository {

    void save(NotifyTask task);

    Optional<NotifyTask> findByPaymentNo(String paymentNo);

    List<NotifyTask> findDue(Instant now, int limit);

    long countByStatus(NotifyTask.Status status);

    long count();
}
