package com.company.service.mall.infra;

import com.company.service.mall.domain.OutboxMessage;

/**
 * 消息出口。业务层只知道"把消息发出去"，不关心是内存还是 RabbitMQ。
 *
 * <p>【解决什么问题】outbox 中继需要一个"发出去到底成没成"的明确信号。
 * 接口把"发送"抽象成可能抛异常的操作，中继据此决定标记 SENT 还是安排重试。
 *
 * <p>【为什么这样设计】约定：只有 Broker 明确确认（publisher confirm）才允许正常返回，
 * 其余情况一律抛异常，交给 outbox 重试。这是"可靠投递"最关键的一条纪律 ——
 * 发送方不能把"写进 socket"当成"消息已送达"。
 *
 * <p>【怎么读这段代码】{@link #publish} 正常返回 = 可以标记 SENT；抛异常 = 必须重试；
 * {@link #provider} 用于日志区分当前用的是内存还是 RabbitMQ。
 */
public interface MessagePublisher {

    void publish(OutboxMessage message);

    String provider();
}
