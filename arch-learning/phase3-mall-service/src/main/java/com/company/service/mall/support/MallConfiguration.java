package com.company.service.mall.support;

import com.company.service.mall.infra.InMemoryMessagePublisher;
import com.company.service.mall.infra.InMemoryNotificationGateway;
import com.company.service.mall.infra.MessagePublisher;
import com.company.service.mall.infra.NotificationGateway;
import com.company.service.mall.infra.RabbitMessagePublisher;
import com.company.service.mall.service.OrderEventProjector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消息出口与通知渠道的装配。
 *
 * <p>【解决什么问题】本地开发/单测不应该强依赖 RabbitMQ，但生产又必须用真实 Broker。
 * 如果两套实现靠 if-else 写在业务代码里，业务会被环境判断污染，
 * 而且很容易出现"测试走内存、线上却忘了配 MQ"的错配。
 *
 * <p>【为什么这样设计】用 {@code @ConditionalOnProperty} 在装配层做选择，
 * 业务层只依赖 {@link MessagePublisher} 接口：
 * <ul>
 *   <li>默认（不配参数）走内存实现，保证任何机器上都能起服务、跑测试</li>
 *   <li>配 {@code company.mall.outbox.transport=rabbit} 才装配真实交换机与发送器</li>
 *   <li>{@code @ConditionalOnMissingBean} 允许测试或业务方覆盖通知渠道</li>
 * </ul>
 * 这是典型的"开闭原则"：新增一种 transport 只需加一个 {@code @Bean}，不改调用方。
 *
 * <p>【怎么读这段代码】先看 {@code transport} 条件选出发送器，
 * 然后把内存发布器（如果存在）订阅给事件投影器：
 * publish 是同步回调，等价于真实 MQ 的消费者，使"至少一次投递 + 消费端幂等"
 * 在零外部依赖的测试里也能被完整验证。
 */
@Configuration
@EnableConfigurationProperties(MallProperties.class)
public class MallConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MallConfiguration.class);

    /** 默认实现：进程内 Broker，不依赖任何外部组件。 */
    @Bean
    @ConditionalOnProperty(name = "company.mall.outbox.transport", havingValue = "memory", matchIfMissing = true)
    public InMemoryMessagePublisher inMemoryMessagePublisher() {
        log.info("【装配】消息出口使用内存 Broker（company.mall.outbox.transport=memory）");
        return new InMemoryMessagePublisher();
    }

    /** topic 交换机：routingKey 就是事件名，下游可用 order.* 或 order.paid 精确订阅。 */
    @Bean
    @ConditionalOnProperty(name = "company.mall.outbox.transport", havingValue = "rabbit")
    public TopicExchange mallOrderEventsExchange(MallProperties properties) {
        // topic 交换机 + routingKey 即事件名（order.created / order.paid ...），
        // 下游可以用 order.* 订阅全部、用 order.paid 只订阅支付成功
        log.info("【装配】声明 topic 交换机: exchange={}", properties.getOutbox().getExchange());
        return new TopicExchange(properties.getOutbox().getExchange(), true, false);
    }

    /** 真实 Broker 发送器：必须等 Confirm 才返回，否则 outbox 会把消息误标为已发送。 */
    @Bean
    @ConditionalOnProperty(name = "company.mall.outbox.transport", havingValue = "rabbit")
    public MessagePublisher rabbitMessagePublisher(RabbitTemplate rabbitTemplate, MallProperties properties) {
        log.info("【装配】消息出口使用 RabbitMQ: exchange={}, confirmTimeout={}",
                properties.getOutbox().getExchange(), properties.getOutbox().getConfirmTimeout());
        return new RabbitMessagePublisher(rabbitTemplate,
                properties.getOutbox().getExchange(),
                properties.getOutbox().getConfirmTimeout());
    }

    /** 通知渠道默认用内存实现，失败可注入，便于验证重试与死信。 */
    @Bean
    @ConditionalOnMissingBean(NotificationGateway.class)
    public NotificationGateway notificationGateway() {
        log.info("【装配】通知渠道使用内存实现");
        return new InMemoryNotificationGateway();
    }

    /**
     * 内存 Broker 模式下把 {@link OrderEventProjector} 订阅到发布器。
     *
     * <p>publish 是同步回调，等价于真实 MQ 的消费者：这样"至少一次投递 + 消费端幂等"
     * 在零外部依赖的测试里也能被完整验证。走 Rabbit 时消费者由监听容器承担，
     * 这里拿不到内存发布器，自然不订阅。
     */
    @Bean
    public SmartInitializingSingleton mallInMemoryProjectorSubscription(
            ObjectProvider<InMemoryMessagePublisher> publisherProvider,
            OrderEventProjector projector) {
        return () -> {
            InMemoryMessagePublisher publisher = publisherProvider.getIfAvailable();
            if (publisher != null) {
                publisher.subscribe(projector);
            }
        };
    }
}
