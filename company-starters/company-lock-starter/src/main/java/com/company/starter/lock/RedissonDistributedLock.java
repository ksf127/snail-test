package com.company.starter.lock;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redisson 实现，用来和自研版本做对照。
 *
 * <p>自研版本没做的部分：
 * <ul>
 *   <li>看门狗默认开启，且续期逻辑经过高并发验证</li>
 *   <li>可重入、公平锁、读写锁、信号量等一整套锁语义</li>
 *   <li>释放失败、连接断开时的补偿与重连</li>
 *   <li>Redlock / 多节点配置</li>
 * </ul>
 *
 * <p>它同样解决不了主从切换导致的双持锁，这一点要靠 fencing token 在下游兜底。
 */
public class RedissonDistributedLock implements DistributedLock, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedissonDistributedLock.class);

    private final RedissonClient client;

    private final String keyPrefix;

    private final boolean ownsClient;

    private RedissonDistributedLock(RedissonClient client, String keyPrefix, boolean ownsClient) {
        this.client = client;
        this.keyPrefix = keyPrefix == null ? "" : keyPrefix;
        this.ownsClient = ownsClient;
    }

    /**
     * 按 spring.data.redis.* 的连接信息创建单机客户端。
     */
    public static RedissonDistributedLock create(String host, int port, String password, int database,
                                                 String keyPrefix) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setPassword(password == null || password.isBlank() ? null : password);
        return new RedissonDistributedLock(Redisson.create(config), keyPrefix, true);
    }

    public static RedissonDistributedLock wrap(RedissonClient client, String keyPrefix) {
        return new RedissonDistributedLock(client, keyPrefix, false);
    }

    @Override
    public LockHandle tryLock(String key, Duration leaseTime) {
        RLock lock = client.getLock(keyPrefix + key);
        try {
            boolean acquired = (leaseTime == null || leaseTime.isZero() || leaseTime.isNegative())
                    // 不指定租期 = 交给看门狗，默认 30s 租期、每 10s 续一次
                    ? lock.tryLock(0, TimeUnit.MILLISECONDS)
                    : lock.tryLock(0, leaseTime.toMillis(), TimeUnit.MILLISECONDS);
            return acquired ? new RedissonLockHandle(key, lock) : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            log.warn("Redisson 加锁异常 key={}", key, e);
            return null;
        }
    }

    @Override
    public String provider() {
        return "redisson";
    }

    @Override
    public void close() {
        if (ownsClient && !client.isShutdown()) {
            client.shutdown();
        }
    }

    private static final class RedissonLockHandle implements LockHandle {

        private final String key;

        private final RLock lock;

        private final AtomicBoolean released = new AtomicBoolean(false);

        private RedissonLockHandle(String key, RLock lock) {
            this.key = key;
            this.lock = lock;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String token() {
            // Redisson 的持锁凭证由客户端内部维护，不对外暴露
            return Thread.currentThread().getName();
        }

        @Override
        public boolean isHeld() {
            return !released.get() && lock.isHeldByCurrentThread();
        }

        @Override
        public void unlock() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (RuntimeException e) {
                log.warn("释放 Redisson 锁失败，等待租期过期 key={}", key, e);
            }
        }
    }
}
