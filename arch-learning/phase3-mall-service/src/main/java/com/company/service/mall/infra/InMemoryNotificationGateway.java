package com.company.service.mall.infra;

import com.company.service.mall.domain.NotifyTask;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存版通知渠道：可注入连续失败，用来验证重试与死信。
 */
public class InMemoryNotificationGateway implements NotificationGateway {

    private final AtomicInteger remainingFailures = new AtomicInteger();

    private final List<String> delivered = new CopyOnWriteArrayList<>();

    public void failNext(int times) {
        remainingFailures.set(Math.max(times, 0));
    }

    public List<String> delivered() {
        return List.copyOf(delivered);
    }

    @Override
    public void notify(NotifyTask task) {
        int failures = remainingFailures.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        if (failures > 0) {
            throw new IllegalStateException("模拟通知渠道不可用，剩余失败次数: " + (failures - 1));
        }
        delivered.add(task.paymentNo() + "->" + task.target() + ":" + task.amountCents());
    }

    @Override
    public String provider() {
        return "memory";
    }
}
