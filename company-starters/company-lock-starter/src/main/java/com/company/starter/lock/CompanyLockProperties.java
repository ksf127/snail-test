package com.company.starter.lock;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对应 Nacos L1 中的 company.lock.*。
 */
@ConfigurationProperties(prefix = "company.lock")
public class CompanyLockProperties {

    /**
     * 锁实现：memory（默认）/ redis / redisson，取值必须小写。
     */
    private Provider provider = Provider.MEMORY;

    /**
     * 业务未显式指定租期时使用的默认值。
     */
    private Duration defaultLeaseTime = Duration.ofSeconds(30);

    /**
     * 是否开启看门狗续期。关掉后租期就是硬上限，
     * 可以用来复现"业务比租期长 → 锁提前释放 → 并发进入"。
     */
    private boolean watchdog = true;

    /**
     * 业务 key 前缀，避免和缓存、限流等其他用途的 key 撞车。
     */
    private String keyPrefix = "company:lock:";

    public enum Provider {
        MEMORY, REDIS, REDISSON
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    public Duration getDefaultLeaseTime() {
        return defaultLeaseTime;
    }

    public void setDefaultLeaseTime(Duration defaultLeaseTime) {
        this.defaultLeaseTime = defaultLeaseTime;
    }

    public boolean isWatchdog() {
        return watchdog;
    }

    public void setWatchdog(boolean watchdog) {
        this.watchdog = watchdog;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
