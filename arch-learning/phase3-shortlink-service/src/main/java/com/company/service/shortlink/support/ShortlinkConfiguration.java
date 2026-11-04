package com.company.service.shortlink.support;

import com.company.service.shortlink.domain.BloomFilter;
import com.company.service.shortlink.domain.IdGenerator;
import com.company.service.shortlink.domain.SegmentIdGenerator;
import com.company.service.shortlink.domain.SegmentSource;
import com.company.service.shortlink.infra.ClickStore;
import com.company.service.shortlink.infra.InMemorySegmentSource;
import com.company.service.shortlink.infra.InMemoryShortlinkRepository;
import com.company.service.shortlink.infra.MemoryBloomFilter;
import com.company.service.shortlink.infra.MemoryClickStore;
import com.company.service.shortlink.infra.MemoryRemoteCache;
import com.company.service.shortlink.infra.RedisBloomFilter;
import com.company.service.shortlink.infra.RedisClickStore;
import com.company.service.shortlink.infra.RedisRemoteCache;
import com.company.service.shortlink.infra.RedisSegmentSource;
import com.company.service.shortlink.infra.RemoteCache;
import com.company.service.shortlink.infra.ShortlinkCache;
import com.company.service.shortlink.infra.ShortlinkRepository;
import com.company.starter.redis.RedisCacheService;
import com.company.starter.threadpool.ThreadPoolManager;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.Executor;

/**
 * 所有可切换组件的装配处。
 *
 * <p>【解决什么问题】短链的每一层都有"内存"和"Redis"两种实现。
 * 如果业务代码直接 new 其中一个，就无法做到"单测零依赖、生产用共享存储"。
 * 这个配置类把选择权收拢到配置项（{@code shortlink.*.store / type / remote}），
 * 让上层代码只依赖接口。
 *
 * <p>【为什么这样设计】切换方式统一是配置项 + {@code @ConditionalOnProperty}：
 * <ul>
 *   <li>单测走内存实现（毫秒级、零依赖），联调改成 redis 只改一行配置，代码零改动；</li>
 *   <li>Redis 相关实现额外加 {@code @ConditionalOnClass}：类路径上没有 Redis 客户端时
 *       不会因为缺少 Bean 而启动失败；</li>
 *   <li>默认值全部是 memory，保证本地启动不需要任何中间件；</li>
 *   <li>号段预取的线程池是可选依赖：没有 {@link ThreadPoolManager} 时退化为
 *       调用线程同步执行，功能不受影响，只是少了异步预取。</li>
 * </ul>
 *
 * <p>【怎么读这段代码】先看外层三个通用 Bean（存储、发号器、两级缓存），
 * 再看下面成对出现的内部配置类：每一对都是"memory（默认）+ redis（显式开启）"。
 * 成对阅读能清楚看出同一个接口有哪两种落地方式。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortlinkProperties.class)
public class ShortlinkConfiguration {

    /**
     * 号段预取的线程池名，与 application.yml 中的 company.thread-pool.executors 对应。
     */
    public static final String PREFETCH_POOL = "shortlink-prefetch";

    /**
     * 短链存储。默认内存实现；真实环境提供自己的 {@code ShortlinkRepository} Bean 即可覆盖。
     *
     * @return 内存存储
     */
    @Bean
    @ConditionalOnMissingBean
    public ShortlinkRepository shortlinkRepository() {
        return new InMemoryShortlinkRepository();
    }

    /**
     * 号段发号器：把号段来源 + 每段大小 + 预取阈值组装成双缓冲发号器。
     *
     * <p>预取线程池按名字从 {@link ThreadPoolManager} 取；取不到就退回同步执行，
     * 保证在未配置线程池的环境（如单测）也能正常发号。
     *
     * @param segmentSource 号段来源（memory 或 redis）
     * @param properties    号段配置
     * @param threadPools   可选线程池管理器
     * @return 号段发号器
     */
    @Bean
    @ConditionalOnMissingBean
    public IdGenerator idGenerator(SegmentSource segmentSource,
                                   ShortlinkProperties properties,
                                   ObjectProvider<ThreadPoolManager> threadPools) {
        ThreadPoolManager manager = threadPools.getIfAvailable();
        Executor executor = manager != null && manager.contains(PREFETCH_POOL)
                ? manager.get(PREFETCH_POOL)
                // 没有专用线程池时退化为调用线程同步执行：慢一点，但功能正确
                : Runnable::run;
        return new SegmentIdGenerator(
                segmentSource,
                properties.getSegment().getStep(),
                properties.getSegment().getPrefetchThreshold(),
                executor);
    }

    /**
     * 两级缓存：L1 Caffeine + 配置指定的 L2。
     *
     * @param remoteCache L2 实现
     * @param properties  缓存配置（容量、四个 TTL、抖动比例）
     * @return 两级缓存
     */
    @Bean
    @ConditionalOnMissingBean
    public ShortlinkCache shortlinkCache(RemoteCache remoteCache, ShortlinkProperties properties) {
        ShortlinkProperties.Cache cache = properties.getCache();
        return new ShortlinkCache(remoteCache,
                cache.getL1MaxSize(),
                cache.getL1Ttl(),
                cache.getL1NegativeTtl(),
                cache.getL2Ttl(),
                cache.getL2NegativeTtl(),
                cache.getTtlJitter());
    }

    /**
     * 布隆位图预热器。默认开启（{@code matchIfMissing = true}）；
     * 关掉它可以复现"重启后全部短码被误判不存在"的故障。
     *
     * @param repository   存储，用于全量读取存量短码
     * @param bloomFilter  布隆过滤器
     * @return 预热器
     */
    @Bean
    @ConditionalOnProperty(prefix = "shortlink.bloom", name = "warm-up", havingValue = "true", matchIfMissing = true)
    public BloomWarmUp bloomWarmUp(ShortlinkRepository repository, BloomFilter bloomFilter) {
        return new BloomWarmUp(repository, bloomFilter);
    }

    /**
     * 内存号段：单进程唯一，默认实现，单测与本地启动零依赖。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "shortlink.segment", name = "store", havingValue = "memory", matchIfMissing = true)
    static class MemorySegmentConfiguration {

        @Bean
        public SegmentSource segmentSource() {
            return new InMemorySegmentSource();
        }
    }

    /**
     * Redis 号段：多实例共享同一个计数器，{@code shortlink.segment.store=redis} 时启用。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "shortlink.segment", name = "store", havingValue = "redis")
    static class RedisSegmentConfiguration {

        @Bean
        public SegmentSource segmentSource(StringRedisTemplate redisTemplate, ShortlinkProperties properties) {
            return new RedisSegmentSource(redisTemplate, properties.getSegment().getKey());
        }
    }

    /**
     * 内存布隆：零 IO，但多实例各自一份、重启需预热，默认实现。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "shortlink.bloom", name = "type", havingValue = "memory", matchIfMissing = true)
    static class MemoryBloomConfiguration {

        @Bean
        public BloomFilter bloomFilter(ShortlinkProperties properties) {
            ShortlinkProperties.Bloom bloom = properties.getBloom();
            return new MemoryBloomFilter(bloom.getExpectedInsertions(), bloom.getFalsePositiveRate());
        }
    }

    /**
     * Redis 布隆：多实例共享位图、重启不丢，但会消耗 Redis QPS。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "shortlink.bloom", name = "type", havingValue = "redis")
    static class RedisBloomConfiguration {

        @Bean
        public BloomFilter bloomFilter(StringRedisTemplate redisTemplate, ShortlinkProperties properties) {
            ShortlinkProperties.Bloom bloom = properties.getBloom();
            return new RedisBloomFilter(redisTemplate, bloom.getKey(),
                    bloom.getExpectedInsertions(), bloom.getFalsePositiveRate());
        }
    }

    /**
     * 内存 L2：单机可用，多实例之间失效不可见，仅用于测试/演示。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "shortlink.cache", name = "remote", havingValue = "memory", matchIfMissing = true)
    static class MemoryRemoteCacheConfiguration {

        @Bean
        public RemoteCache remoteCache() {
            return new MemoryRemoteCache();
        }
    }

    /**
     * Redis L2：跨实例共享，重启不丢，生产使用。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisCacheService.class)
    @ConditionalOnProperty(prefix = "shortlink.cache", name = "remote", havingValue = "redis")
    static class RedisRemoteCacheConfiguration {

        @Bean
        public RemoteCache remoteCache(RedisCacheService redisCacheService) {
            return new RedisRemoteCache(redisCacheService);
        }
    }

    /**
     * 内存点击存储：重启即丢，仅单机/测试使用。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "shortlink.click", name = "store", havingValue = "memory", matchIfMissing = true)
    static class MemoryClickConfiguration {

        @Bean
        public ClickStore clickStore() {
            return new MemoryClickStore();
        }
    }

    /**
     * Redis 点击存储：多实例汇总，生产使用。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "shortlink.click", name = "store", havingValue = "redis")
    static class RedisClickConfiguration {

        @Bean
        public ClickStore clickStore(StringRedisTemplate redisTemplate, ShortlinkProperties properties) {
            return new RedisClickStore(redisTemplate, properties.getClick().getHashKey());
        }
    }
}
