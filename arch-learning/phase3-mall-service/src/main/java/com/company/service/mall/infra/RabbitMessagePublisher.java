package com.company.service.mall.infra;

import com.company.service.mall.domain.OutboxMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ 出口：等 Broker 的 Confirm 再返回。
 *
 * <p>【解决什么问题】outbox 只有在"消息确实被 Broker 收下"之后才能把记录标记为 SENT。
 * 否则一旦误标，这条消息就永远不会再被重试，下游永远收不到。
 *
 * <p>【为什么这样设计】为什么必须等 Confirm：
 * {@code convertAndSend} 只是把字节写进 TCP 缓冲，Broker 可能没收到、交换机可能不存在、
 * 队列可能没绑定，这些都不会在方法调用时抛异常。只有 correlated confirm 能把
 * "ack / nack" 明确带回来，outbox 才有机会对失败的消息重试而不是误标 SENT。
 *
 * <p>注意 mandatory + returns 处理的是"到达交换机但没有路由到任何队列"，
 * 与 confirm 是两个不同层面的保证，company-mq-starter 已经统一配置。
 *
 * <p>【怎么读这段代码】发送前置 messageId（消费端拿去做幂等）→ 调用 convertAndSend →
 * 在超时时间内等 confirm：ack 正常返回，nack/超时/中断一律抛异常，由中继退避重试。
 * 超时被单独处理，是因为"超时"不代表 Broker 没收到，重试后消费端可能看到两条同 id 消息，
 * 所以幂等键必须提前写好。
 */
public class RabbitMessagePublisher implements MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMessagePublisher.class);

    private final RabbitTemplate rabbitTemplate;

    private final String exchange;

    private final Duration confirmTimeout;

    public RabbitMessagePublisher(RabbitTemplate rabbitTemplate, String exchange, Duration confirmTimeout) {
        this.rabbitTemplate = rabbitTemplate;
        this.exchange = exchange;
        this.confirmTimeout = confirmTimeout;
    }

    /**
     * 发送一条 outbox 消息，并等待 Broker 的 publisher confirm。
     *
     * <p>幂等性：本方法不幂等。同一 message 重复调用会在 Broker 上产生多条同 id 消息，
     * 消费端必须按 messageId 去重。
     *
     * @param message 待投递消息
     * @throws IllegalStateException 未确认、超时、被中断或等待失败
     */
    @Override
    public void publish(OutboxMessage message) {
        CorrelationData correlation = new CorrelationData(Long.toString(message.id()));
        try {
            rabbitTemplate.convertAndSend(exchange, message.topic(), message.payload(), raw -> {
                // messageId 是消费端幂等的依据，必须与 outbox 主键一致
                raw.getMessageProperties().setMessageId(Long.toString(message.id()));
                return raw;
            }, correlation);
            CorrelationData.Confirm confirm = correlation.getFuture()
                    .get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                log.warn("RabbitMQ 未确认消息: outboxId={}, reason={}", message.id(), confirm.getReason());
                throw new IllegalStateException("Broker 未确认: outboxId=" + message.id()
                        + ", reason=" + confirm.getReason());
            }
            log.debug("RabbitMQ 已确认消息: outboxId={}, exchange={}, topic={}",
                    message.id(), exchange, message.topic());
        } catch (InterruptedException e) {
            // 恢复中断标记：吞掉中断会让线程池无法优雅退出
            Thread.currentThread().interrupt();
            log.warn("等待 RabbitMQ Confirm 被中断: outboxId={}", message.id());
            throw new IllegalStateException("等待 Confirm 被中断: outboxId=" + message.id(), e);
        } catch (TimeoutException e) {
            // 超时不等于没收到：消息可能已在 Broker，只是 confirm 没回来，所以只能重试 + 消费端幂等
            log.warn("等待 RabbitMQ Confirm 超时: outboxId={}, timeout={}", message.id(), confirmTimeout);
            throw new IllegalStateException("等待 Confirm 超时(" + confirmTimeout + "): outboxId=" + message.id(), e);
        } catch (ExecutionException e) {
            log.warn("等待 RabbitMQ Confirm 失败: outboxId={}, error={}", message.id(), e.getMessage());
            throw new IllegalStateException("等待 Confirm 失败: outboxId=" + message.id(), e.getCause());
        }
    }

    @Override
    public String provider() {
        return "rabbit";
    }
}
