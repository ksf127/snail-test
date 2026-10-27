package com.company.service.mall.infra;

import com.company.service.mall.domain.OutboxMessage;

/**
 * 消息出口。业务层只知道"把消息发出去"，不关心是内存还是 RabbitMQ。
 *
 * <p>约定：只有 Broker 明确确认（publisher confirm）才允许正常返回，
 * 其余情况一律抛异常，交给 outbox 重试。这是"可靠投递"最关键的一条纪律 ——
 * 发送方不能把"写进 socket"当成"消息已送达"。
 */
public interface MessagePublisher {

    void publish(OutboxMessage message);

    String provider();
}
