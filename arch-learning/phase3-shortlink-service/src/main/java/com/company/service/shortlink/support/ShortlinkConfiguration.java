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
 * <p>切换方式统一是配置项 + {@code @ConditionalOnProperty}，
 * 好处是：单测走内存实现（毫秒级、零依赖），联调改成 redis 只改一行配置，代码零改动。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ShortlinkProperties.class)
public class ShortlinkConfiguration {

    /**
     * 号段预取的线程池名，与 application.yml 中的 company.thread-pool.executors 对应。
     */
    public static final String PREFETCH_POOL = "shortlink-prefetch";

    @Bean
    @ConditionalOnMissingBean
    public ShortlinkRepository shortlinkRepository() {
        return new InMemoryShortlinkRepository();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdGenerator idGenerator(SegmentSource segmentSource,
                                   ShortlinkProperties properties,
                                   ObjectProvider<ThreadPoolManager> threadPools) {
        ThreadPoolManager manager = threadPools.getIfAvailable();
        Executor executor = manager != null && manager.contains(PREFETCH_POOL)
                ? manager.get(PREFETCH_POOL)
                : Runnable::run;
        return new SegmentIdGenerator(
                segmentSource,
                properties.getSegment().getStep(),
                properties.getSegment().getPrefetchThreshold(),
                executor);
    }

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

    @Bean
    @ConditionalOnProperty(prefix = "shortlink.bloom", name = "warm-up", havingValue = "true", matchIfMissing = true)
    public BloomWarmUp bloomWarmUp(ShortlinkRepository repository, BloomFilter bloomFilter) {
        return new BloomWarmUp(repository, bloomFilter);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "shortlink.segment", name = "store", havingValue = "memory", matchIfMissing = true)
    static class MemorySegmentConfiguration {

        @Bean
        public SegmentSource segmentSource() {
            return new InMemorySegmentSource();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "shortlink.segment", name = "store", havingValue = "redis")
    static class RedisSegmentConfiguration {

        @Bean
        public SegmentSource segmentSource(StringRedisTemplate redisTemplate, ShortlinkProperties properties) {
            return new RedisSegmentSource(redisTemplate, properties.getSegment().getKey());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "shortlink.bloom", name = "type", havingValue = "memory", matchIfMissing = true)
    static class MemoryBloomConfiguration {

        @Bean
        public BloomFilter bloomFilter(ShortlinkProperties properties) {
            ShortlinkProperties.Bloom bloom = properties.getBloom();
            return new MemoryBloomFilter(bloom.getExpectedInsertions(), bloom.getFalsePositiveRate());
        }
    }

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

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "shortlink.cache", name = "remote", havingValue = "memory", matchIfMissing = true)
    static class MemoryRemoteCacheConfiguration {

        @Bean
        public RemoteCache remoteCache() {
            return new MemoryRemoteCache();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedisCacheService.class)
    @ConditionalOnProperty(prefix = "shortlink.cache", name = "remote", havingValue = "redis")
    static class RedisRemoteCacheConfiguration {

        @Bean
        public RemoteCache remoteCache(RedisCacheService redisCacheService) {
            return new RedisRemoteCache(redisCacheService);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "shortlink.click", name = "store", havingValue = "memory", matchIfMissing = true)
    static class MemoryClickConfiguration {

        @Bean
        public ClickStore clickStore() {
            return new MemoryClickStore();
        }
    }

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
