package com.company.service.mall.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 商城实验的全部可调参数。
 *
 * <p>【解决什么问题】分布式事务实验里真正决定"能不能跑起来"的，往往不是业务代码，
 * 而是几个时间参数：等锁多久、消息重试几次、退避多久、通知几次放弃。
 * 如果它们散落在各处的常量里，就没法在压测或演示时快速调整，
 * 也没法回答"为什么这个订单最后进了死信"。
 *
 * <p>【为什么这样设计】全部收敛到一个 {@code @ConfigurationProperties} 类，
 * 默认值就是"内存 + 无外部依赖"，保证 {@code mvn test} 在任何机器上可跑；
 * 把 {@code company.mall.outbox.transport} 改成 {@code rabbit} 即切换成真实 MQ，
 * 业务代码一行都不用改。时间统一用 {@link Duration} 而不是裸 long，
 * 避免"这个 3000 到底是毫秒还是秒"的歧义。
 *
 * <p>【怎么读这段代码】三个内部类对应三条链路：
 * {@link OrderSettings} 管下单/支付/取消与超时关单；
 * {@link OutboxSettings} 管 outbox 中继的批量、重试上限、退避与 confirm 超时；
 * {@link NotifySettings} 管支付结果最大努力通知。改配置时只要找到对应内部类的字段即可。
 */
@ConfigurationProperties(prefix = "company.mall")
public class MallProperties {

    /** 订单链路参数：锁等待/租期、未支付超时。 */
    private OrderSettings order = new OrderSettings();

    /** outbox 中继参数：传输方式、批量、重试与退避。 */
    private OutboxSettings outbox = new OutboxSettings();

    /** 支付通知参数：重试次数、退避与轮询间隔。 */
    private NotifySettings notify = new NotifySettings();

    public OrderSettings getOrder() {
        return order;
    }

    public void setOrder(OrderSettings order) {
        this.order = order;
    }

    public OutboxSettings getOutbox() {
        return outbox;
    }

    public void setOutbox(OutboxSettings outbox) {
        this.outbox = outbox;
    }

    public NotifySettings getNotify() {
        return notify;
    }

    public void setNotify(NotifySettings notify) {
        this.notify = notify;
    }

    /**
     * 订单链路参数。
     *
     * <p>锁参数决定并发下单时"抢不到锁"是快速失败还是排队等待；
     * 超时参数决定库存被预占后多久自动归还。
     */
    public static class OrderSettings {

        /**
         * 下单/支付/取消在订单维度串行化时的等锁时间。
         */
        private Duration lockWait = Duration.ofSeconds(3);

        /** 锁租期：超过这个时间仍未释放，锁视为失效，防止持锁进程崩溃后永久死锁。 */
        private Duration lockLease = Duration.ofSeconds(10);

        /**
         * 未支付超时时间：超过这个时长的 CREATED 订单会被关单并归还库存。
         */
        private Duration payTimeout = Duration.ofMinutes(30);

        public Duration getLockWait() {
            return lockWait;
        }

        public void setLockWait(Duration lockWait) {
            this.lockWait = lockWait;
        }

        public Duration getLockLease() {
            return lockLease;
        }

        public void setLockLease(Duration lockLease) {
            this.lockLease = lockLease;
        }

        public Duration getPayTimeout() {
            return payTimeout;
        }

        public void setPayTimeout(Duration payTimeout) {
            this.payTimeout = payTimeout;
        }
    }

    /**
     * outbox 中继参数。
     *
     * <p>中继是"至少一次"投递：失败就退避重试，超过上限进死信，
     * 因此重试上限与退避曲线直接决定一条消息最终是送达还是进 DEAD。
     */
    public static class OutboxSettings {

        /** 是否开启中继轮询；关闭后事件只会停留在 PENDING，便于观察"未投递"状态。 */
        private boolean enabled = true;

        /**
         * memory：进程内 Broker；rabbit：真实 RabbitMQ。
         */
        private String transport = "memory";

        /** 订单事件交换机名，RabbitMQ 模式下使用。 */
        private String exchange = "mall.order.events";

        /** 每轮最多扫描多少条待发消息，避免一次拉起过多数据占满内存。 */
        private int batchSize = 50;

        /**
         * 超过这个次数进死信，不再无限重试。
         */
        private int maxAttempts = 8;

        /** 指数退避的起点：第一次失败等这么久，之后按次数翻倍。 */
        private Duration baseBackoff = Duration.ofSeconds(1);

        /** 退避上限，防止次数多了之后等待时间失控。 */
        private Duration maxBackoff = Duration.ofMinutes(1);

        /** 中继轮询间隔（毫秒），决定从"业务落库"到"消息可见"的平均延迟。 */
        private long intervalMillis = 500;

        /**
         * 等 Broker Confirm 的超时时间。
         */
        private Duration confirmTimeout = Duration.ofSeconds(3);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTransport() {
            return transport;
        }

        public void setTransport(String transport) {
            this.transport = transport;
        }

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getBaseBackoff() {
            return baseBackoff;
        }

        public void setBaseBackoff(Duration baseBackoff) {
            this.baseBackoff = baseBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public long getIntervalMillis() {
            return intervalMillis;
        }

        public void setIntervalMillis(long intervalMillis) {
            this.intervalMillis = intervalMillis;
        }

        public Duration getConfirmTimeout() {
            return confirmTimeout;
        }

        public void setConfirmTimeout(Duration confirmTimeout) {
            this.confirmTimeout = confirmTimeout;
        }
    }

    /**
     * 支付结果通知参数。
     *
     * <p>这是"最大努力通知"：不保证一定送达，只保证重试有限次；
     * 超过上限进死信，由商户主动查询支付状态兜底。
     */
    public static class NotifySettings {

        /** 是否开启通知轮询；关闭后支付成功只会生成 PENDING 任务。 */
        private boolean enabled = true;

        /** 单条通知最多尝试次数，超过即判定为 DEAD。 */
        private int maxAttempts = 6;

        /** 指数退避起点：通知失败后先等这么久再试。 */
        private Duration baseBackoff = Duration.ofSeconds(1);

        /** 退避上限，避免通知间隔无限拉长。 */
        private Duration maxBackoff = Duration.ofSeconds(30);

        /** 通知轮询间隔（毫秒）。 */
        private long intervalMillis = 1000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public Duration getBaseBackoff() {
            return baseBackoff;
        }

        public void setBaseBackoff(Duration baseBackoff) {
            this.baseBackoff = baseBackoff;
        }

        public Duration getMaxBackoff() {
            return maxBackoff;
        }

        public void setMaxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
        }

        public long getIntervalMillis() {
            return intervalMillis;
        }

        public void setIntervalMillis(long intervalMillis) {
            this.intervalMillis = intervalMillis;
        }
    }
}
