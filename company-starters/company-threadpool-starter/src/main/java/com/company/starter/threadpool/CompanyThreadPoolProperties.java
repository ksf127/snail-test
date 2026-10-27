package com.company.starter.threadpool;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 对应 Nacos L1/L2 中的 company.thread-pool.*。
 *
 * <p>线程池参数属于"运行期可调"的配置，不应该出现在代码常量里：
 * 大促前扩容、故障后回滚，都要求改配置即可生效。
 */
@ConfigurationProperties(prefix = "company.thread-pool")
public class CompanyThreadPoolProperties {

    private boolean enabled = true;

    /** 停机时等待任务跑完的时间，超时后强杀。 */
    private Duration awaitTermination = Duration.ofSeconds(30);

    private Map<String, ExecutorProperties> executors = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getAwaitTermination() {
        return awaitTermination;
    }

    public void setAwaitTermination(Duration awaitTermination) {
        this.awaitTermination = awaitTermination;
    }

    public Map<String, ExecutorProperties> getExecutors() {
        return executors;
    }

    public void setExecutors(Map<String, ExecutorProperties> executors) {
        this.executors = executors;
    }

    public static class ExecutorProperties {

        private int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors());

        private int maxPoolSize = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);

        private int queueCapacity = 1024;

        /** 队列容量的硬上限：运行期最多能扩到这里，防止配置写错把内存打满。 */
        private int maxQueueCapacity = 65536;

        private Duration keepAlive = Duration.ofSeconds(60);

        private RejectedPolicy rejectedPolicy = RejectedPolicy.ABORT;

        private boolean allowCoreThreadTimeOut = false;

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getMaxQueueCapacity() {
            return maxQueueCapacity;
        }

        public void setMaxQueueCapacity(int maxQueueCapacity) {
            this.maxQueueCapacity = maxQueueCapacity;
        }

        public Duration getKeepAlive() {
            return keepAlive;
        }

        public void setKeepAlive(Duration keepAlive) {
            this.keepAlive = keepAlive;
        }

        public RejectedPolicy getRejectedPolicy() {
            return rejectedPolicy;
        }

        public void setRejectedPolicy(RejectedPolicy rejectedPolicy) {
            this.rejectedPolicy = rejectedPolicy;
        }

        public boolean isAllowCoreThreadTimeOut() {
            return allowCoreThreadTimeOut;
        }

        public void setAllowCoreThreadTimeOut(boolean allowCoreThreadTimeOut) {
            this.allowCoreThreadTimeOut = allowCoreThreadTimeOut;
        }
    }
}
