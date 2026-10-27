package com.company.service.mall.support;

import com.company.service.mall.infra.InMemoryMessagePublisher;
import com.company.service.mall.infra.InMemoryNotificationGateway;
import com.company.service.mall.infra.MessagePublisher;
import com.company.service.mall.infra.NotificationGateway;
import com.company.service.mall.infra.RabbitMessagePublisher;
import com.company.service.mall.service.OrderEventProjector;

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
 * <p>所有实现都用 {@code matchIfMissing} 的默认值指向内存版：
 * 不配任何参数就能起服务、跑测试；配了 {@code transport: rabbit} 才需要真实 Broker。
 */
@Configuration
@EnableConfigurationProperties(MallProperties.class)
public class MallConfiguration {

    @Bean
    @ConditionalOnProperty(name = "company.mall.outbox.transport", havingValue = "memory", matchIfMissing = true)
    public InMemoryMessagePublisher inMemoryMessagePublisher() {
        return new InMemoryMessagePublisher();
    }

    @Bean
    @ConditionalOnProperty(name = "company.mall.outbox.transport", havingValue = "rabbit")
    public TopicExchange mallOrderEventsExchange(MallProperties properties) {
        // topic 交换机 + routingKey 即事件名（order.created / order.paid ...），
        // 下游可以用 order.* 订阅全部、用 order.paid 只订阅支付成功
        return new TopicExchange(properties.getOutbox().getExchange(), true, false);
    }

    @Bean
    @ConditionalOnProperty(name = "company.mall.outbox.transport", havingValue = "rabbit")
    public MessagePublisher rabbitMessagePublisher(RabbitTemplate rabbitTemplate, MallProperties properties) {
        return new RabbitMessagePublisher(rabbitTemplate,
                properties.getOutbox().getExchange(),
                properties.getOutbox().getConfirmTimeout());
    }

    @Bean
    @ConditionalOnMissingBean(NotificationGateway.class)
    public NotificationGateway notificationGateway() {
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
