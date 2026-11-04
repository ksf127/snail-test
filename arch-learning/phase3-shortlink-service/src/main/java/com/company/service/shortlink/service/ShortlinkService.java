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
 * 短链主流程：创建、解析（跳转）、删除、统计。
 *
 * <p>【解决什么问题】它是整个短链服务的编排层，把发号、编码、布隆、两级缓存、单飞锁、
 * 存储、点击聚合这些零件按固定顺序串起来。去掉它，每个 Controller 都要自己拼这套流程，
 * 顺序一旦写错（比如先写布隆后落库）就会产生"布隆说有、库里没有"的脏状态。
 *
 * <p>【为什么这样设计】跳转路径的固定顺序（每一步都在挡流量，顺序不能乱）：
 * <pre>
 *   布隆 → L1 → L2 → 分布式锁单飞 → 存储
 * </pre>
 * <ul>
 *   <li>布隆：几 MB 内存挡掉"根本不存在"的随机扫描，且零 IO，所以放最前面；</li>
 *   <li>L1：本地内存，绝大多数热点请求走到这里就结束；</li>
 *   <li>L2：只有 L1 未命中才走，跨实例共享；</li>
 *   <li>单飞：只有 L1/L2 同时未命中的少数请求才需要抢锁，锁内二次检查避免重复回源；</li>
 *   <li>锁拿不到时直接降级查库：宁可多打一次库，也不让请求排队等待。</li>
 * </ul>
 * 之所以"布隆放在缓存前面"：布隆的单次判断是纯内存位运算，比一次 Redis 往返快一到两个数量级，
 * 挡在最外层收益最大；而且它只可能误杀"不存在"的请求，不会误杀真实短链。
 *
 * <p>【怎么读这段代码】先读 {@link #create}（写路径），再读 {@link #resolve}
 * （读路径的四层漏斗），最后读 {@link #loadWithSingleFlight} 和 {@link #loadFromRepository}
 * （缓存未命中后的回源）。字段全部是构造器注入的线程安全组件。
 *
 * <p>【并发安全】本类是无状态单例：所有字段 final、且被注入的实现都是线程安全的，
 * 因此可以放心被大量请求线程并发调用。
 */
@Service
public class ShortlinkService {

    private static final Logger log = LoggerFactory.getLogger(ShortlinkService.class);

    /**
     * 原始 URL 的最大长度，与 DTO 上的校验保持一致。
     */
    private static final int MAX_URL_LENGTH = 2048;

    private final ShortlinkRepository repository;

    private final IdGenerator idGenerator;

    private final BloomFilter bloomFilter;

    private final ShortlinkCache cache;

    /**
     * 分布式锁：只在缓存双未命中时用于"单飞"，防止同一短码被大量线程同时回源。
     */
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

    /**
     * 创建短链：校验 URL → 发号 → Base62 编码 → 落库 → 登记布隆 → 写缓存。
     *
     * <p>创建是低频写操作，日志用 info 留痕（与百万 QPS 的跳转路径区别对待）。
     * 方法本身无状态，依赖的组件均线程安全。
     *
     * @param url      原始长链接，必须是 http/https 且带主机名
     * @param expireAt 过期时间，null 表示永不过期
     * @return 新创建的短链
     * @throws BizException URL 非法或过期时间不晚于当前时间时
     * @throws IllegalStateException 号段耗尽时
     */
    public Shortlink create(String url, Instant expireAt) {
        String normalized = normalizeUrl(url);
        Instant now = Instant.now();
        if (expireAt != null && !expireAt.isAfter(now)) {
            // 过期时间必须在未来，否则刚创建就不可用
            throw new BizException(ErrorCode.PARAM_INVALID, "过期时间必须晚于当前时间");
        }
        long id = idGenerator.nextId();
        // 短码完全由 ID 决定，不需要查重：号段保证 ID 全局唯一
        String code = Base62.encodeFixed(id);
        Shortlink link = new Shortlink(id, code, normalized, now, expireAt);
        repository.save(link);
        // 先写存储再写布隆：反过来会出现"布隆说有、库里没有"的空窗
        bloomFilter.add(code);
        cache.put(code, CacheEntry.of(link));
        metrics.markCreated();
        log.info("短链已创建: code={}, id={}, url={}, expireAt={}", code, id, normalized, expireAt);
        return link;
    }

    /**
     * 解析短码（跳转主链路）：布隆 → L1/L2 → 单飞回源。
     *
     * <p><b>这是百万 QPS 的热路径，只能打 debug 日志</b>：info 级别的每条日志都会带来
     * 字符串拼接、磁盘/网络 IO 与锁竞争，在百万 QPS 下会直接成为瓶颈。
     * 实际生产应改为采样日志或异步日志，并配合 {@link ShortlinkMetrics} 做统计。
     *
     * @param code 短码
     * @return 命中的短链；短码非法、不存在或已过期时返回空
     */
    public Optional<Shortlink> resolve(String code) {
        metrics.markResolveRequested();
        // 格式校验放在最前面：非法短码连布隆都不用查
        if (!Base62.isValid(code)) {
            metrics.markNotFound();
            log.debug("短码格式非法，直接拒绝: {}", code);
            return Optional.empty();
        }
        if (!bloomFilter.mightContain(code)) {
            // 布隆说"一定不存在"就可以直接返回，这一步零 IO，是抗随机扫描的核心
            metrics.markBloomRejected();
            metrics.markNotFound();
            // 布隆拦截是低频但关键的动作，记 info 便于观察"挡掉了多少无效流量"；
            // 若线上被扫描流量打成高频，这里必须改成采样日志，否则日志本身会成为瓶颈
            log.info("布隆过滤器拦截不存在的短码: {}", code);
            return Optional.empty();
        }
        ShortlinkCache.Lookup lookup = cache.lookup(code);
        // 命中就只做一次"负缓存/逻辑过期"的统一判断；未命中才进入单飞回源
        Optional<Shortlink> result = lookup.hit() ? materialize(lookup.entry()) : loadWithSingleFlight(code);
        if (result.isPresent()) {
            metrics.markResolved();
            log.debug("短码解析命中: code={}, level={}", code, lookup.level());
        } else {
            metrics.markNotFound();
            log.debug("短码解析未命中或已过期: code={}, level={}", code, lookup.level());
        }
        return result;
    }

    /**
     * 302 专用：命中后记一次点击。
     *
     * <p>点击只进内存（见 {@link ClickRecorder}），不在这里做任何存储写入，
     * 因此本方法给跳转主链路增加的只是一次 map 查找 + 一次原子自增。
     *
     * @param code 短码
     * @return 命中的短链；不存在时返回空
     */
    public Optional<Shortlink> resolveForRedirect(String code) {
        Optional<Shortlink> link = resolve(code);
        // 只有真正命中并返回 302 时才计数，404 不算点击
        link.ifPresent(found -> clickRecorder.record(found.code()));
        return link;
    }

    /**
     * 删除短链：先删存储，再失效两层缓存。
     *
     * <p>删除是低频管理操作，用 info 留痕。
     *
     * @param code 短码
     * @return 确实删掉了一条时返回 true
     * @throws BizException 短码格式非法时
     */
    public boolean delete(String code) {
        if (!Base62.isValid(code)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "短码格式不合法: " + code);
        }
        boolean removed = repository.deleteByCode(code);
        // 存储删成功后再失效缓存：顺序反了会出现"缓存已清、库里还在"的短暂复活
        cache.evict(code);
        if (removed) {
            metrics.markDeleted();
            log.info("短链已删除: code={}", code);
        } else {
            log.warn("删除短链时未找到记录: code={}", code);
        }
        // 布隆过滤器不支持删除：删掉的短码仍可能通过布隆，由缓存与存储兜底。
        // 这是刻意接受的代价 —— 换来的是布隆可以做到千万级位图仍然零 IO。
        return removed;
    }

    /**
     * @param code 短码
     * @return 已落盘 + 未刷新的点击量
     */
    public long clicks(String code) {
        return clickRecorder.clicks(code);
    }

    /**
     * 手动触发一次点击增量刷盘（线上由定时任务负责）。
     */
    public void flushClicks() {
        clickRecorder.flush();
    }

    /**
     * 按配置的域名拼出完整短链。
     *
     * @param code 短码
     * @return 可直接返回给调用方的短链 URL
     */
    public String shortUrl(String code) {
        String domain = properties.getDomain();
        // 允许配置里带不带结尾斜杠，避免拼出双斜杠
        if (domain.endsWith("/")) {
            return domain + code;
        }
        return domain + "/" + code;
    }

    /**
     * 汇总运行期计数，供 /links/stats 展示。
     *
     * <p>所有计数都是近似快照（LongAdder 分段求和），不保证跨字段严格一致，
     * 但足以判断"哪一层在挡流量"。
     *
     * @return 存储条数、缓存分层命中、主链路计数与点击统计
     */
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
     *
     * @param entry 缓存条目，允许为 null
     * @return 有效且未过期的短链；负缓存或已过期返回空
     */
    private Optional<Shortlink> materialize(CacheEntry entry) {
        // 负缓存表示"确认不存在"，必须和"没缓过"区分开，否则穿透会重新出现
        if (entry == null || entry.negative() || entry.link() == null) {
            return Optional.empty();
        }
        // 过期时间不参与缓存 TTL 的设计：缓存可能还没过期，但业务上已经到期
        if (entry.link().isExpired(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(entry.link());
    }

    /**
     * 缓存击穿保护：同一个 key 只允许一个线程回源，其余线程等锁后直接读缓存。
     *
     * <p>锁等待时间与租约由配置决定；拿不到锁时<b>直接降级查库</b>，
     * 不做排队 —— 在百万 QPS 下，让请求排队比多打一次库更危险。
     *
     * @param code 短码
     * @return 回源得到的短链；不存在时为 {@code CacheEntry.absent()} 对应的空
     */
    private Optional<Shortlink> loadWithSingleFlight(String code) {
        LockHandle handle = lock.lock(
                "load:" + code,
                properties.getCache().getLockWait(),
                properties.getCache().getLockLease());
        if (handle == null) {
            metrics.markSingleFlightFallback();
            // 降级属于异常路径，用 warn 暴露"锁竞争/锁服务异常"，但不影响请求继续
            log.warn("单飞锁等待超时，降级直接回源: code={}", code);
            return loadFromRepository(code);
        }
        try (LockHandle ignored = handle) {
            // 锁内二次检查：等锁期间可能已有别的线程把数据放进了缓存
            ShortlinkCache.Lookup second = cache.lookup(code);
            if (second.hit()) {
                return materialize(second.entry());
            }
            return loadFromRepository(code);
        }
    }

    /**
     * 真正的回源：查存储，并把结果（含"不存在"）写回两级缓存。
     *
     * <p>这是低频但关键的动作（正常情况下每秒只有极少数请求会走到这里），
     * 用 info 记录，便于发现"缓存是不是失效了、单飞是不是没生效"。
     * 注意命中和未命中都要写缓存：未命中写负缓存，才能挡住后续的重复穿透。
     *
     * @param code 短码
     * @return 未过期的短链；不存在或已过期返回空
     */
    private Optional<Shortlink> loadFromRepository(String code) {
        metrics.markDbLoaded();
        Optional<Shortlink> found = repository.findByCode(code)
                // 逻辑过期：存储里有记录但已到期，也要当作不存在，并且写入负缓存
                .filter(link -> !link.isExpired(Instant.now()));
        cache.put(code, found.map(CacheEntry::of).orElseGet(CacheEntry::absent));
        log.info("缓存回源存储: code={}, 命中={}", code, found.isPresent());
        return found;
    }

    /**
     * 校验并规范化原始 URL。
     *
     * @param url 原始长链接
     * @return 去掉首尾空白后的 URL
     * @throws BizException 为空、超长、协议不符或缺少主机名时
     */
    private String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "url 不能为空");
        }
        String trimmed = url.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID, "url 长度不能超过 " + MAX_URL_LENGTH);
        }
        // 只允许 http/https：限制协议可以挡住 javascript:、file: 这类危险跳转
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "url 必须以 http:// 或 https:// 开头");
        }
        try {
            URI uri = URI.create(trimmed);
            // 有协议但没主机名（如 "http:///path"）无法跳转，必须提前拒绝
            if (uri.getHost() == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "url 缺少主机名: " + trimmed);
            }
        } catch (IllegalArgumentException e) {
            // URI.create 在格式错误时抛 IllegalArgumentException，统一转成业务异常
            throw new BizException(ErrorCode.PARAM_INVALID, "url 格式不合法: " + trimmed);
        }
        return trimmed;
    }

    /**
     * 主链路计数快照。
     *
     * @param links                 存储中的短链总数
     * @param cacheL1Hits           L1 命中次数
     * @param cacheL2Hits           L2 命中次数
     * @param cacheMisses           两层都未命中的次数
     * @param cacheL1EstimatedSize  L1 估算条目数
     * @param created               创建次数
     * @param deleted               删除次数
     * @param resolveRequests       解析请求总数
     * @param resolved              解析成功次数
     * @param notFound              解析失败（不存在/非法/过期）次数
     * @param bloomRejects          被布隆过滤器拦截的次数
     * @param dbLoads               回源存储次数
     * @param singleFlightFallbacks 单飞锁降级次数
     * @param clicksRecorded        累计上报的点击次数
     * @param clicksFlushed         累计落盘的点击次数
     * @param clicksPending         尚未落盘的点击次数
     * @param remoteCache           当前 L2 实现名
     * @param lockProvider          当前分布式锁实现名
     */
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
