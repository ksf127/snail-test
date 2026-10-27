package com.company.service.shortlink.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * shortlink.* 配置。
 *
 * <p>所有"内存 / Redis"的切换点都收在这里，默认值保证单测与本地启动零中间件依赖。
 */
@ConfigurationProperties(prefix = "shortlink")
public class ShortlinkProperties {

    /**
     * 拼给调用方的短链前缀。
     */
    private String domain = "http://localhost:8120/shortlink";

    private Segment segment = new Segment();

    private Cache cache = new Cache();

    private Bloom bloom = new Bloom();

    private Click click = new Click();

    public static class Segment {

        /**
         * memory / redis。
         */
        private String store = "memory";

        private int step = 10000;

        private double prefetchThreshold = 0.2;

        private String key = "shortlink:segment";

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
        }

        public int getStep() {
            return step;
        }

        public void setStep(int step) {
            this.step = step;
        }

        public double getPrefetchThreshold() {
            return prefetchThreshold;
        }

        public void setPrefetchThreshold(double prefetchThreshold) {
            this.prefetchThreshold = prefetchThreshold;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    public static class Cache {

        /**
         * memory / redis。
         */
        private String remote = "memory";

        private long l1MaxSize = 100_000;

        private Duration l1Ttl = Duration.ofMinutes(10);

        private Duration l1NegativeTtl = Duration.ofSeconds(30);

        private Duration l2Ttl = Duration.ofMinutes(30);

        private Duration l2NegativeTtl = Duration.ofMinutes(2);

        private double ttlJitter = 0.1;

        private Duration lockWait = Duration.ofSeconds(2);

        private Duration lockLease = Duration.ofSeconds(5);

        public String getRemote() {
            return remote;
        }

        public void setRemote(String remote) {
            this.remote = remote;
        }

        public long getL1MaxSize() {
            return l1MaxSize;
        }

        public void setL1MaxSize(long l1MaxSize) {
            this.l1MaxSize = l1MaxSize;
        }

        public Duration getL1Ttl() {
            return l1Ttl;
        }

        public void setL1Ttl(Duration l1Ttl) {
            this.l1Ttl = l1Ttl;
        }

        public Duration getL1NegativeTtl() {
            return l1NegativeTtl;
        }

        public void setL1NegativeTtl(Duration l1NegativeTtl) {
            this.l1NegativeTtl = l1NegativeTtl;
        }

        public Duration getL2Ttl() {
            return l2Ttl;
        }

        public void setL2Ttl(Duration l2Ttl) {
            this.l2Ttl = l2Ttl;
        }

        public Duration getL2NegativeTtl() {
            return l2NegativeTtl;
        }

        public void setL2NegativeTtl(Duration l2NegativeTtl) {
            this.l2NegativeTtl = l2NegativeTtl;
        }

        public double getTtlJitter() {
            return ttlJitter;
        }

        public void setTtlJitter(double ttlJitter) {
            this.ttlJitter = ttlJitter;
        }

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
    }

    public static class Bloom {

        /**
         * memory / redis。
         */
        private String type = "memory";

        private long expectedInsertions = 1_000_000;

        private double falsePositiveRate = 0.0001;

        private String key = "shortlink:bloom";

        /**
         * 启动时用存量短码重建位图。关掉它才能复现"重启后全部误判不存在"的故障。
         */
        private boolean warmUp = true;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public long getExpectedInsertions() {
            return expectedInsertions;
        }

        public void setExpectedInsertions(long expectedInsertions) {
            this.expectedInsertions = expectedInsertions;
        }

        public double getFalsePositiveRate() {
            return falsePositiveRate;
        }

        public void setFalsePositiveRate(double falsePositiveRate) {
            this.falsePositiveRate = falsePositiveRate;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public boolean isWarmUp() {
            return warmUp;
        }

        public void setWarmUp(boolean warmUp) {
            this.warmUp = warmUp;
        }
    }

    public static class Click {

        /**
         * memory / redis。
         */
        private String store = "memory";

        private String hashKey = "shortlink:clicks";

        private long flushIntervalMillis = 1000;

        public String getStore() {
            return store;
        }

        public void setStore(String store) {
            this.store = store;
        }

        public String getHashKey() {
            return hashKey;
        }

        public void setHashKey(String hashKey) {
            this.hashKey = hashKey;
        }

        public long getFlushIntervalMillis() {
            return flushIntervalMillis;
        }

        public void setFlushIntervalMillis(long flushIntervalMillis) {
            this.flushIntervalMillis = flushIntervalMillis;
        }
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public Segment getSegment() {
        return segment;
    }

    public void setSegment(Segment segment) {
        this.segment = segment;
    }

    public Cache getCache() {
        return cache;
    }

    public void setCache(Cache cache) {
        this.cache = cache;
    }

    public Bloom getBloom() {
        return bloom;
    }

    public void setBloom(Bloom bloom) {
        this.bloom = bloom;
    }

    public Click getClick() {
        return click;
    }

    public void setClick(Click click) {
        this.click = click;
    }
}
