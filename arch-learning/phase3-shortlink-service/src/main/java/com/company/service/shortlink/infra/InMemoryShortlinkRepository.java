package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.Shortlink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 内存存储，同时记录读次数，用来在测试里证明"布隆/缓存确实挡住了数据库"。
 */
public class InMemoryShortlinkRepository implements ShortlinkRepository {

    private final Map<String, Shortlink> links = new ConcurrentHashMap<>();

    private final LongAdder findCalls = new LongAdder();

    @Override
    public void save(Shortlink link) {
        links.put(link.code(), link);
    }

    @Override
    public Optional<Shortlink> findByCode(String code) {
        findCalls.increment();
        return Optional.ofNullable(links.get(code));
    }

    @Override
    public boolean deleteByCode(String code) {
        return links.remove(code) != null;
    }

    @Override
    public List<Shortlink> findAll() {
        return new ArrayList<>(links.values());
    }

    @Override
    public long count() {
        return links.size();
    }

    public long findCalls() {
        return findCalls.sum();
    }
}
