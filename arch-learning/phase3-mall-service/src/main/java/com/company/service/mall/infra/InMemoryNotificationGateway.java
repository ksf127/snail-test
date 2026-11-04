package com.company.service.mall.infra;

import com.company.service.mall.domain.NotifyTask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存版通知渠道：可注入连续失败，用来验证重试与死信。
 *
 * <p>【解决什么问题】外部通知必然失败，需要一个可控的假下游来演示
 * "失败 → 退避 → 重试 → 成功/死信"的完整过程，避免依赖真实的短信/商户网关。
 *
 * <p>【为什么这样设计】与 outbox 的内存 Broker 保持同一套思路：用"剩余故障次数"计数器
 * 精确控制接下来几次调用失败，测试可以断言第几次重试会成功。
 * 它只记录已送达的字符串，不实现真实协议；真实网关的耗时、超时、幂等都要在适配层处理。
 *
 * <p>【怎么读这段代码】{@link #notify} 先扣故障余额，有余额就抛异常；
 * 否则追加一条送达记录。日志一律 debug —— 内存实现每个操作都打 info 会刷屏。
 */
public class InMemoryNotificationGateway implements NotificationGateway {

    private static final Logger log = LoggerFactory.getLogger(InMemoryNotificationGateway.class);

    private final AtomicInteger remainingFailures = new AtomicInteger();

    private final List<String> delivered = new CopyOnWriteArrayList<>();

    /**
     * 让接下来的 n 次通知失败。
     *
     * @param times 失败次数，负数按 0 处理
     */
    public void failNext(int times) {
        remainingFailures.set(Math.max(times, 0));
        log.debug("注入通知渠道故障: 接下来 {} 次通知将失败", Math.max(times, 0));
    }

    public List<String> delivered() {
        return List.copyOf(delivered);
    }

    /**
     * 发送通知。
     *
     * <p>幂等性：不幂等，每次成功都会追加一条送达记录；去重由调用方按 paymentNo 保证。
     *
     * @param task 通知任务
     * @throws IllegalStateException 还有剩余故障次数
     */
    @Override
    public void notify(NotifyTask task) {
        int failures = remainingFailures.getAndUpdate(current -> current > 0 ? current - 1 : 0);
        if (failures > 0) {
            log.debug("内存通知渠道拒绝: paymentNo={}, 剩余故障次数={}", task.paymentNo(), failures - 1);
            throw new IllegalStateException("模拟通知渠道不可用，剩余失败次数: " + (failures - 1));
        }
        delivered.add(task.paymentNo() + "->" + task.target() + ":" + task.amountCents());
        log.debug("内存通知渠道送达: paymentNo={}, target={}", task.paymentNo(), task.target());
    }

    @Override
    public String provider() {
        return "memory";
    }
}
