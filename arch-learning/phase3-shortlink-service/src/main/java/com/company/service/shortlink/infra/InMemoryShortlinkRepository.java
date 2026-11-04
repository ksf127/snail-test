package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.Shortlink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 内存短链存储，同时记录读次数，用来在测试里证明"布隆/缓存确实挡住了数据库"。
 *
 * <p>【解决什么问题】它扮演真实环境里 MySQL/分库分表的角色，让整条链路可以在零依赖下跑通。
 * 更重要的是 {@code findCalls} 这个计数器：它把"回源次数"变成可观测数字，
 * 否则无法验证布隆过滤器、L1、L2、单飞锁各自挡掉了多少流量。
 *
 * <p>【为什么这样设计】用 ConcurrentHashMap 而不是加锁的 HashMap：
 * 跳转路径读多写少，ConcurrentHashMap 的读是无锁的，不会成为压测瓶颈。
 * 计数器用 LongAdder 而不是 AtomicLong：多线程高频自增时 LongAdder 的竞争更小。
 *
 * <p>【怎么读这段代码】CRUD 都是对 Map 的直接操作，重点看 {@link #findByCode}
 * 里先自增计数再查 Map 的顺序，以及 {@link #findAll} 为什么返回新列表（防止外部改内部状态）。
 * 所有方法都线程安全。
 */
public class InMemoryShortlinkRepository implements ShortlinkRepository {

    /**
     * 短码 → 短链。真实环境这里是数据库表，key 是唯一索引。
     */
    private final Map<String, Shortlink> links = new ConcurrentHashMap<>();

    /**
     * 回源查询次数，测试断言与监控"布隆/缓存是否真的挡住流量"的核心指标。
     */
    private final LongAdder findCalls = new LongAdder();

    /**
     * 保存或覆盖一条短链（按短码做唯一键）。
     *
     * @param link 待保存的短链，不能为 null
     */
    @Override
    public void save(Shortlink link) {
        links.put(link.code(), link);
    }

    /**
     * 按短码查询。
     *
     * <p>每次调用都会累加 {@code findCalls}，无论是否命中 —— 因为"打到存储"本身就是成本。
     *
     * @param code 短码
     * @return 命中时返回短链，否则返回空 Optional
     */
    @Override
    public Optional<Shortlink> findByCode(String code) {
        findCalls.increment();
        return Optional.ofNullable(links.get(code));
    }

    /**
     * 按短码删除。
     *
     * @param code 短码
     * @return 确实删掉了一条时返回 true
     */
    @Override
    public boolean deleteByCode(String code) {
        return links.remove(code) != null;
    }

    /**
     * 返回全量短链，供布隆过滤器预热使用。
     *
     * <p>返回新列表而不是内部视图：调用方遍历时其他线程可能写入，
     * ConcurrentHashMap 的迭代器虽然是弱一致的，但拷贝一份语义更清晰。
     *
     * @return 当前所有短链的快照
     */
    @Override
    public List<Shortlink> findAll() {
        return new ArrayList<>(links.values());
    }

    /**
     * @return 当前短链总数
     */
    @Override
    public long count() {
        return links.size();
    }

    /**
     * @return 累计回源查询次数（含未命中）
     */
    public long findCalls() {
        return findCalls.sum();
    }
}
