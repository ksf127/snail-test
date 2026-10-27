package com.company.service.shortlink.service;

import com.company.common.core.BizException;
import com.company.common.core.ErrorCode;
import com.company.service.shortlink.domain.Base62;
import com.company.service.shortlink.domain.BloomFilter;
import com.company.service.shortlink.domain.IdGenerator;
import com.company.service.shortlink.domain.Shortlink;
import com.company.service.shortlink.infra.CacheEntry;
import com.company.service.shortlink.infra.ShortlinkCache;
import com.company.service.shortlink.infra.ShortlinkRepository;
import com.company.service.shortlink.support.ShortlinkMetrics;
import com.company.service.shortlink.support.ShortlinkProperties;
import com.company.starter.lock.DistributedLock;
import com.company.starter.lock.LockHandle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

/**
 * 短链主流程。
 *
 * <p>跳转路径的固定顺序（每一步都在挡流量，顺序不能乱）：
 * <pre>
 *   布隆 → L1 → L2 → 分布式锁单飞 → 存储
 * </pre>
 * <ul>
 *   <li>布隆：几 MB 内存挡掉"根本不存在"的随机扫描，且零 IO</li>
 *   <li>L1：本地内存，绝大多数热点请求走到这里就结束</li>
 *   <li>L2：只有 L1 未命中才走，跨实例共享</li>
 *   <li>单飞：只有 L1/L2 同时未命中的少数请求才需要抢锁，锁内二次检查避免重复回源</li>
 *   <li>锁拿不到时直接降级查库：宁可多打一次库，也不让请求排队等待</li>
 * </ul>
 */
@Service
public class ShortlinkService {

    private static final Logger log = LoggerFactory.getLogger(ShortlinkService.class);

    private static final int MAX_URL_LENGTH = 2048;

    private final ShortlinkRepository repository;

    private final IdGenerator idGenerator;

    private final BloomFilter bloomFilter;

    private final ShortlinkCache cache;

    private final DistributedLock lock;

    private final ClickRecorder clickRecorder;

    private final ShortlinkMetrics metrics;

    private final ShortlinkProperties properties;

    public ShortlinkService(ShortlinkRepository repository,
                            IdGenerator idGenerator,
                            BloomFilter bloomFilter,
                            ShortlinkCache cache,
                            DistributedLock lock,
                            ClickRecorder clickRecorder,
                            ShortlinkMetrics metrics,
                            ShortlinkProperties properties) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.bloomFilter = bloomFilter;
        this.cache = cache;
        this.lock = lock;
        this.clickRecorder = clickRecorder;
        this.metrics = metrics;
        this.properties = properties;
    }

    public Shortlink create(String url, Instant expireAt) {
        String normalized = normalizeUrl(url);
        Instant now = Instant.now();
        if (expireAt != null && !expireAt.isAfter(now)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "过期时间必须晚于当前时间");
        }
        long id = idGenerator.nextId();
        String code = Base62.encodeFixed(id);
        Shortlink link = new Shortlink(id, code, normalized, now, expireAt);
        repository.save(link);
        // 先写存储再写布隆：反过来会出现"布隆说有、库里没有"的空窗
        bloomFilter.add(code);
        cache.put(code, CacheEntry.of(link));
        metrics.markCreated();
        log.debug("短链已创建: code={}, url={}", code, normalized);
        return link;
    }

    public Optional<Shortlink> resolve(String code) {
        metrics.markResolveRequested();
        if (!Base62.isValid(code)) {
            metrics.markNotFound();
            return Optional.empty();
        }
        if (!bloomFilter.mightContain(code)) {
            metrics.markBloomRejected();
            metrics.markNotFound();
            return Optional.empty();
        }
        ShortlinkCache.Lookup lookup = cache.lookup(code);
        Optional<Shortlink> result = lookup.hit() ? materialize(lookup.entry()) : loadWithSingleFlight(code);
        if (result.isPresent()) {
            metrics.markResolved();
        } else {
            metrics.markNotFound();
        }
        return result;
    }

    /**
     * 302 专用：命中后记一次点击。
     */
    public Optional<Shortlink> resolveForRedirect(String code) {
        Optional<Shortlink> link = resolve(code);
        link.ifPresent(found -> clickRecorder.record(found.code()));
        return link;
    }

    public boolean delete(String code) {
        if (!Base62.isValid(code)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "短码格式不合法: " + code);
        }
        boolean removed = repository.deleteByCode(code);
        cache.evict(code);
        if (removed) {
            metrics.markDeleted();
        }
        // 布隆过滤器不支持删除：删掉的短码仍可能通过布隆，由缓存与存储兜底。
        // 这是刻意接受的代价 —— 换来的是布隆可以做到千万级位图仍然零 IO。
        return removed;
    }

    public long clicks(String code) {
        return clickRecorder.clicks(code);
    }

    public void flushClicks() {
        clickRecorder.flush();
    }

    public String shortUrl(String code) {
        String domain = properties.getDomain();
        if (domain.endsWith("/")) {
            return domain + code;
        }
        return domain + "/" + code;
    }

    public ShortlinkStats stats() {
        ShortlinkCache.Stats cacheStats = cache.stats();
        ShortlinkMetrics.Snapshot snapshot = metrics.snapshot();
        return new ShortlinkStats(
                repository.count(),
                cacheStats.l1Hits(),
                cacheStats.l2Hits(),
                cacheStats.misses(),
                cacheStats.estimatedL1Size(),
                snapshot.created(),
                snapshot.deleted(),
                snapshot.resolveRequests(),
                snapshot.resolved(),
                snapshot.notFound(),
                snapshot.bloomRejects(),
                snapshot.dbLoads(),
                snapshot.singleFlightFallbacks(),
                clickRecorder.recordedTotal(),
                clickRecorder.flushedTotal(),
                clickRecorder.pendingTotal(),
                cache.remoteProvider(),
                lock.provider());
    }

    /**
     * 缓存命中后的收尾：负缓存、逻辑过期都必须在这里统一判断，
     * 否则 L1 与 L2 会各自维护一套过期逻辑。
     */
    private Optional<Shortlink> materialize(CacheEntry entry) {
        if (entry == null || entry.negative() || entry.link() == null) {
            return Optional.empty();
        }
        if (entry.link().isExpired(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.link());
    }

    /**
     * 缓存击穿保护：同一个 key 只允许一个线程回源，其余线程等锁后直接读缓存。
     */
    private Optional<Shortlink> loadWithSingleFlight(String code) {
        LockHandle handle = lock.lock(
                "load:" + code,
                properties.getCache().getLockWait(),
                properties.getCache().getLockLease());
        if (handle == null) {
            metrics.markSingleFlightFallback();
            log.debug("单飞锁等待超时，降级直接回源: code={}", code);
            return loadFromRepository(code);
        }
        try (LockHandle ignored = handle) {
            ShortlinkCache.Lookup second = cache.lookup(code);
            if (second.hit()) {
                return materialize(second.entry());
            }
            return loadFromRepository(code);
        }
    }

    private Optional<Shortlink> loadFromRepository(String code) {
        metrics.markDbLoaded();
        Optional<Shortlink> found = repository.findByCode(code)
                .filter(link -> !link.isExpired(Instant.now()));
        cache.put(code, found.map(CacheEntry::of).orElseGet(CacheEntry::absent));
        return found;
    }

    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "url 不能为空");
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID, "url 长度不能超过 " + MAX_URL_LENGTH);
        }
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "url 必须以 http:// 或 https:// 开头");
        }
        try {
            URI uri = URI.create(trimmed);
            if (uri.getHost() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "url 缺少主机名: " + trimmed);
            }
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "url 格式不合法: " + trimmed);
        }
        return trimmed;
    }

    public record ShortlinkStats(long links,
                                 long cacheL1Hits,
                                 long cacheL2Hits,
                                 long cacheMisses,
                                 long cacheL1EstimatedSize,
                                 long created,
                                 long deleted,
                                 long resolveRequests,
                                 long resolved,
                                 long notFound,
                                 long bloomRejects,
                                 long dbLoads,
                                 long singleFlightFallbacks,
                                 long clicksRecorded,
                                 long clicksFlushed,
                                 long clicksPending,
                                 String remoteCache,
                                 String lockProvider) {
    }
}
