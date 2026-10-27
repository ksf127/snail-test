package com.company.service.mall.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 商城实验的全部可调参数。
 *
 * <p>默认值就是"内存 + 无外部依赖"，保证 {@code mvn test} 在任何机器上可跑；
 * 把 {@code company.mall.outbox.transport} 改成 {@code rabbit} 即切换成真实 MQ。
 */
@ConfigurationProperties(prefix = "company.mall")
public class MallProperties {

    private OrderSettings order = new OrderSettings();

    private OutboxSettings outbox = new OutboxSettings();

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

    public static class OrderSettings {

        /**
         * 下单/支付/取消在订单维度串行化时的等锁时间。
         */
        private Duration lockWait = Duration.ofSeconds(3);

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

    public static class OutboxSettings {

        private boolean enabled = true;

        /**
         * memory：进程内 Broker；rabbit：真实 RabbitMQ。
         */
        private String transport = "memory";

        private String exchange = "mall.order.events";

        private int batchSize = 50;

        /**
         * 超过这个次数进死信，不再无限重试。
         */
        private int maxAttempts = 8;

        private Duration baseBackoff = Duration.ofSeconds(1);

        private Duration maxBackoff = Duration.ofMinutes(1);

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

    public static class NotifySettings {

        private boolean enabled = true;

        private int maxAttempts = 6;

        private Duration baseBackoff = Duration.ofSeconds(1);

        private Duration maxBackoff = Duration.ofSeconds(30);

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
