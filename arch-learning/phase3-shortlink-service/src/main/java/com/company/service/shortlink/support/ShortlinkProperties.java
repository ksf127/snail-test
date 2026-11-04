package com.company.service.shortlink.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * {@code shortlink.*} 配置。
 *
 * <p>【解决什么问题】短链的每一层都有"内存 / Redis"两种实现，还有各自的容量与 TTL 参数。
 * 如果这些散落在代码里，切换环境就必须改代码。这个类把它们集中成一份可外部化的配置，
 * 让 {@link ShortlinkConfiguration} 可以按配置项选择实现。
 *
 * <p>【为什么这样设计】
 * <ul>
 *   <li>所有默认值都指向内存实现，并且 TTL/容量都有合理默认值，
 *       保证单测与本地启动零中间件依赖；</li>
 *   <li>按组件分组（segment / cache / bloom / click），与配置前缀一一对应，
 *       改哪一层一目了然；</li>
 *   <li>负缓存的 TTL 单独配置：不存在的短码随时可能被创建，负缓存必须比正常缓存短得多。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】先看顶层的 {@code domain} 与四个分组字段，再按分组读内部类的字段说明。
 * 这个类只是配置载体，由 Spring 在启动时绑定并注入，运行期只读，因此线程安全。
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

    /**
     * 号段配置。
     */
    public static class Segment {

        /**
         * memory / redis。memory 仅单进程唯一，redis 多实例唯一。
         */
        private String store = "memory";

        /**
         * 每段号码数量。越大访问存储越少，但进程重启浪费的号码越多。
         */
        private int step = 10000;

        /**
         * 预取阈值比例：剩余量低于 {@code step * 该值} 时异步拉下一段。0 表示关闭预取。
         */
        private double prefetchThreshold = 0.2;

        /**
         * Redis 号段计数器的 key。
         */
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

    /**
     * 两级缓存配置。
     */
    public static class Cache {

        /**
         * memory / redis。memory 仅单机，redis 跨实例共享。
         */
        private String remote = "memory";

        /**
         * L1 最大条目数，超出后 Caffeine 按近似 LRU 淘汰。
         */
        private long l1MaxSize = 100_000;

        /**
         * L1 正常条目 TTL。太长会让删除/改指向延迟生效，太短会增大 L2 压力。
         */
        private Duration l1Ttl = Duration.ofMinutes(10);

        /**
         * L1 负缓存 TTL，必须明显短于正常 TTL，否则刚创建的短链会打不开。
         */
        private Duration l1NegativeTtl = Duration.ofSeconds(30);

        /**
         * L2 正常条目基础 TTL。
         */
        private Duration l2Ttl = Duration.ofMinutes(30);

        /**
         * L2 负缓存基础 TTL。
         */
        private Duration l2NegativeTtl = Duration.ofMinutes(2);

        /**
         * TTL 抖动比例，防止同一批 key 同时过期造成回源尖峰。
         */
        private double ttlJitter = 0.1;

        /**
         * 单飞锁最长等待时间；超时即降级直接查库，避免请求排队。
         */
        private Duration lockWait = Duration.ofSeconds(2);

        /**
         * 单飞锁租约，防止持锁进程崩溃后锁永久不释放。
         */
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

    /**
     * 布隆过滤器配置。
     */
    public static class Bloom {

        /**
         * memory / redis。
         */
        private String type = "memory";

        /**
         * 预期插入的元素个数 n。估小了会让位图过早饱和、误判率飙升。
         */
        private long expectedInsertions = 1_000_000;

        /**
         * 可接受误判率 p。越小位图越大，1e-4 约每个元素 19 bit。
         */
        private double falsePositiveRate = 0.0001;

        /**
         * Redis 位图的 key。
         */
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

    /**
     * 点击统计配置。
     */
    public static class Click {

        /**
         * memory / redis。
         */
        private String store = "memory";

        /**
         * Redis Hash 的 key，field 是短码。
         */
        private String hashKey = "shortlink:clicks";

        /**
         * 点击增量刷盘周期（毫秒）。周期越长主链路越省，但进程崩溃时丢的计数越多。
         */
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
