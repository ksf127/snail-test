package com.company.service.shortlink.support;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.LongAdder;

/**
 * 主链路的计数器：每一个都能对应到"某一层挡住了多少请求"，
 * 否则调优只能靠猜。
 */
@Component
public class ShortlinkMetrics {

    private final LongAdder created = new LongAdder();

    private final LongAdder deleted = new LongAdder();

    private final LongAdder resolveRequests = new LongAdder();

    private final LongAdder resolved = new LongAdder();

    private final LongAdder notFound = new LongAdder();

    private final LongAdder bloomRejects = new LongAdder();

    private final LongAdder dbLoads = new LongAdder();

    private final LongAdder singleFlightFallbacks = new LongAdder();

    public void markCreated() {
        created.increment();
    }

    public void markDeleted() {
        deleted.increment();
    }

    public void markResolveRequested() {
        resolveRequests.increment();
    }

    public void markResolved() {
        resolved.increment();
    }

    public void markNotFound() {
        notFound.increment();
    }

    public void markBloomRejected() {
        bloomRejects.increment();
    }

    public void markDbLoaded() {
        dbLoads.increment();
    }

    public void markSingleFlightFallback() {
        singleFlightFallbacks.increment();
    }

    public long created() {
        return created.sum();
    }

    public long deleted() {
        return deleted.sum();
    }

    public long resolveRequests() {
        return resolveRequests.sum();
    }

    public long resolved() {
        return resolved.sum();
    }

    public long notFound() {
        return notFound.sum();
    }

    public long bloomRejects() {
        return bloomRejects.sum();
    }

    public long dbLoads() {
        return dbLoads.sum();
    }

    public long singleFlightFallbacks() {
        return singleFlightFallbacks.sum();
    }

    public Snapshot snapshot() {
        return new Snapshot(created.sum(), deleted.sum(), resolveRequests.sum(), resolved.sum(),
                notFound.sum(), bloomRejects.sum(), dbLoads.sum(), singleFlightFallbacks.sum());
    }

    public record Snapshot(long created,
                           long deleted,
                           long resolveRequests,
                           long resolved,
                           long notFound,
                           long bloomRejects,
                           long dbLoads,
                           long singleFlightFallbacks) {
    }
}
