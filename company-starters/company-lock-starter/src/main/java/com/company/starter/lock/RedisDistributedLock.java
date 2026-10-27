package com.company.starter.lock;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * 自研 Redis 分布式锁。每个设计点都对应一个真实事故：
 *
 * <ol>
 *   <li><b>SET NX PX 一条命令</b>：先 SETNX 再 EXPIRE 是两步，中间宕机就是永久死锁。
 *       现代 Redis 用 {@code SET key value NX PX 30000} 一次完成，但我们要存重入计数，
 *       所以改用 Lua 脚本，保证"判断存在性 + 写入 + 设过期"在同一段原子代码里。</li>
 *   <li><b>释放要校验持有者</b>：业务超时导致锁过期、别人拿到锁之后，
 *       原持有者如果直接 {@code DEL}，删掉的是别人的锁。用 Lua 把
 *       "校验 token + 计数减一 + 归零才删" 合成原子操作。</li>
 *   <li><b>Hash 而不是 String</b>：{@code {token: 重入次数}} 才能同时支持可重入与原子释放。</li>
 *   <li><b>看门狗</b>：租期是兜底，不是业务时限。业务没结束时按租期 1/3 续期，
 *       关掉看门狗即可复现"锁提前释放 → 并发进入"。</li>
 *   <li><b>本地重入镜像</b>：见 {@link ReentrantTokenRegistry}。</li>
 * </ol>
 *
 * <p>仍未解决的是主从切换：锁只写到 master，异步复制期间 master 挂掉，
 * 新 master 上锁不存在，两个客户端会同时持锁。要彻底解决得上 Redlock 或
 * 带 fencing token 的下游校验，这也是引入 Redisson 做对照的原因之一。
 */
public class RedisDistributedLock implements DistributedLock, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);

    /**
     * 加锁：不存在则写入 token=1；已是自己的锁则计数 +1；否则失败。
     * 两条成功路径都会刷新过期时间。
     */
    private static final RedisScript<Long> ACQUIRE = script("""
            if redis.call('exists', KEYS[1]) == 0 then
                redis.call('hset', KEYS[1], ARGV[1], 1)
                redis.call('pexpire', KEYS[1], ARGV[2])
                return 1
            end
            if redis.call('hexists', KEYS[1], ARGV[1]) == 1 then
                redis.call('hincrby', KEYS[1], ARGV[1], 1)
                redis.call('pexpire', KEYS[1], ARGV[2])
                return 1
            end
            return 0
            """);

    /**
     * 续期：只有仍持有锁的 token 才能续，返回 0 说明锁已丢失。
     */
    private static final RedisScript<Long> RENEW = script("""
            if redis.call('hexists', KEYS[1], ARGV[1]) == 1 then
                return redis.call('pexpire', KEYS[1], ARGV[2])
            end
            return 0
            """);

    /**
     * 释放：不是自己的锁返回 -1（调用方据此告警），归零才删除。
     */
    private static final RedisScript<Long> RELEASE = script("""
            if redis.call('exists', KEYS[1]) == 0 then
                return 0
            end
            if redis.call('hexists', KEYS[1], ARGV[1]) == 0 then
                return -1
            end
            local count = redis.call('hincrby', KEYS[1], ARGV[1], -1)
            if count > 0 then
                redis.call('pexpire', KEYS[1], ARGV[2])
                return count
            end
            redis.call('del', KEYS[1])
            return 0
            """);

    private final StringRedisTemplate template;

    private final CompanyLockProperties properties;

    private final ReentrantTokenRegistry tokens = new ReentrantTokenRegistry();

    private final ConcurrentHashMap<String, ScheduledFuture<?>> renewals = new ConcurrentHashMap<>();

    private final ScheduledExecutorService watchdog;

    private final AtomicInteger lostLocks = new AtomicInteger();

    public RedisDistributedLock(StringRedisTemplate template, CompanyLockProperties properties) {
        this.template = template;
        this.properties = properties;
        this.watchdog = properties.isWatchdog()
                ? Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "company-lock-watchdog");
                    thread.setDaemon(true);
                    return thread;
                })
                : null;
    }

    @Override
    public LockHandle tryLock(String key, Duration leaseTime) {
        Duration lease = leaseTime == null ? properties.getDefaultLeaseTime() : leaseTime;
        String redisKey = properties.getKeyPrefix() + key;
        String leaseMillis = String.valueOf(lease.toMillis());

        ReentrantTokenRegistry.Hold existing = tokens.peek(key);
        boolean reentrant = existing != null;
        String token = reentrant ? existing.token() : ReentrantTokenRegistry.randomToken();

        Long acquired;
        try {
            acquired = template.execute(ACQUIRE, List.of(redisKey), token, leaseMillis);
        } catch (RuntimeException e) {
            log.warn("加锁失败，按未获取处理 key={}", redisKey, e);
            return null;
        }
        if (acquired == null || acquired == 0L) {
            return null;
        }

        if (reentrant) {
            tokens.increment(key);
        } else {
            tokens.register(key, token);
            startWatchdog(redisKey, token, lease);
        }
        return new RedisLockHandle(key, redisKey, token, leaseMillis);
    }

    @Override
    public String provider() {
        return "redis";
    }

    /**
     * 看门狗失效次数。大于 0 说明业务持锁时间超过了租期且续期失败，
     * 这段时间内其他实例可以进入临界区。
     */
    public int lostLockCount() {
        return lostLocks.get();
    }

    @Override
    public void close() {
        renewals.values().forEach(future -> future.cancel(false));
        renewals.clear();
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
    }

    private void startWatchdog(String redisKey, String token, Duration lease) {
        if (watchdog == null) {
            return;
        }
        String ticketKey = ticketKey(redisKey, token);
        long interval = Math.max(100, lease.toMillis() / 3);
        ScheduledFuture<?> future = watchdog.scheduleAtFixedRate(
                () -> renew(redisKey, ticketKey, token, lease),
                interval, interval, TimeUnit.MILLISECONDS);
        renewals.put(ticketKey, future);
    }

    private void renew(String redisKey, String ticketKey, String token, Duration lease) {
        try {
            Long result = template.execute(RENEW, List.of(redisKey), token, String.valueOf(lease.toMillis()));
            if (result == null || result == 0L) {
                // 锁已经不属于我们了：要么过期被抢，要么数据被清。停止续期并计数。
                lostLocks.incrementAndGet();
                cancelWatchdog(ticketKey);
                log.warn("分布式锁已丢失（租期内未完成业务）key={}", redisKey);
            }
        } catch (RuntimeException e) {
            // Redis 抖动时不要停止续期，下一轮再试
            log.warn("看门狗续期异常 key={}", redisKey, e);
        }
    }

    private void cancelWatchdog(String ticketKey) {
        ScheduledFuture<?> future = renewals.remove(ticketKey);
        if (future != null) {
            future.cancel(false);
        }
    }

    private static String ticketKey(String redisKey, String token) {
        return redisKey + '\u0000' + token;
    }

    private static RedisScript<Long> script(String text) {
        return new DefaultRedisScript<>(text, Long.class);
    }

    private final class RedisLockHandle implements LockHandle {

        private final String key;

        private final String redisKey;

        private final String token;

        private final String leaseMillis;

        private final AtomicBoolean released = new AtomicBoolean(false);

        private RedisLockHandle(String key, String redisKey, String token, String leaseMillis) {
            this.key = key;
            this.redisKey = redisKey;
            this.token = token;
            this.leaseMillis = leaseMillis;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String token() {
            return token;
        }

        @Override
        public boolean isHeld() {
            return !released.get();
        }

        @Override
        public void unlock() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            // 重入场景：内层释放只减本地计数，不碰 Redis
            if (!tokens.release(key)) {
                return;
            }
            cancelWatchdog(ticketKey(redisKey, token));
            try {
                Long result = template.execute(RELEASE, List.of(redisKey), token, leaseMillis);
                if (result != null && result == -1L) {
                    log.warn("释放锁时发现持有者已变更，可能有实例超出锁租期仍在执行 key={}", redisKey);
                }
            } catch (RuntimeException e) {
                log.warn("释放锁失败，等待租期自然过期 key={}", redisKey, e);
            }
        }
    }
}
