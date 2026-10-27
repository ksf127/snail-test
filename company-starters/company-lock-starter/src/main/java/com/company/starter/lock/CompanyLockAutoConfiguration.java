package com.company.starter.lock;

import org.redisson.api.RedissonClient;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 按 {@code company.lock.provider} 选一个实现，业务只注入 {@link DistributedLock}。
 *
 * <p>默认 memory：CI 和单测不需要 Redis 也能跑，这是"内存默认 + 真实可切"的落点。
 */
@AutoConfiguration
@EnableConfigurationProperties(CompanyLockProperties.class)
public class CompanyLockAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "company.lock", name = "provider",
            havingValue = "memory", matchIfMissing = true)
    static class MemoryLockConfiguration {

        @Bean
        @ConditionalOnMissingBean(DistributedLock.class)
        DistributedLock memoryDistributedLock(CompanyLockProperties properties) {
            return new InMemoryDistributedLock(properties.getKeyPrefix());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(StringRedisTemplate.class)
    @ConditionalOnBean(StringRedisTemplate.class)
    @ConditionalOnProperty(prefix = "company.lock", name = "provider", havingValue = "redis")
    static class RedisLockConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(DistributedLock.class)
        DistributedLock redisDistributedLock(StringRedisTemplate stringRedisTemplate,
                                             CompanyLockProperties properties) {
            return new RedisDistributedLock(stringRedisTemplate, properties);
        }
    }

    /**
     * Redisson 是 optional 依赖：没引 redisson 时整个配置类不会被加载，
     * 所以这里用 DistributedLock 做返回类型，避免提前解析 Redisson 的类。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RedissonClient.class)
    @ConditionalOnProperty(prefix = "company.lock", name = "provider", havingValue = "redisson")
    static class RedissonLockConfiguration {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean(DistributedLock.class)
        DistributedLock redissonDistributedLock(CompanyLockProperties properties,
                                                RedisProperties redisProperties) {
            return RedissonDistributedLock.create(
                    redisProperties.getHost(),
                    redisProperties.getPort(),
                    redisProperties.getPassword(),
                    redisProperties.getDatabase(),
                    properties.getKeyPrefix());
        }
    }
}
