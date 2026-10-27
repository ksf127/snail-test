package com.company.starter.threadpool;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 引入 company-threadpool-starter 即自动生效。
 *
 * <p>MeterRegistry 是可选依赖：服务没引 Actuator 时 getIfAvailable 返回 null，
 * 线程池照样工作，只是没有指标。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "company.thread-pool", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CompanyThreadPoolProperties.class)
public class CompanyThreadPoolAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ThreadPoolManager threadPoolManager(CompanyThreadPoolProperties properties,
                                               ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new ThreadPoolManager(properties, meterRegistryProvider.getIfAvailable());
    }
}
