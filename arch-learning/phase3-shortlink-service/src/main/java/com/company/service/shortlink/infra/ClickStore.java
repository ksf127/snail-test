package com.company.service.shortlink.infra;

/**
 * 点击量存储。
 *
 * <p>【解决什么问题】短链要统计每次跳转的点击量，但跳转是 100 万 QPS 的读请求。
 * 如果每次跳转都同步写一次存储，等于把所有读请求改造成写请求，写放大会先把 Redis 打死，
 * 其次跳转延迟也会被写 IO 拉长。这个接口定义"点击量最终落在哪里"，
 * 而"什么时候写"由 {@code ClickRecorder} 的异步聚合决定。
 *
 * <p>【为什么这样设计】接口只暴露累加和读取，不暴露"刷新/批量"：
 * 批量落盘的职责在应用层的聚合器，存储层只负责原子累加。
 * 这样内存版和 Redis 版都可以做到"一次调用一次自增"，语义完全一致。
 *
 * <p>【怎么读这段代码】{@link #increment} 必须支持并发调用且不丢计数；
 * {@link #clicks} 只是给查询接口用，允许读到"最近一个刷新周期前"的值。
 */
public interface ClickStore {

    /**
     * 把某个短码的点击量累加 delta。
     *
     * <p>实现必须是线程安全的；delta 可能大于 1（批量刷新时），
     * 由存储端做原子累加而不是"先读后写"。
     *
     * @param code  短码
     * @param delta 本次增量，通常大于 0
     */
    void increment(String code, long delta);

    /**
     * 读取已落盘的点击量，不含尚未刷新的内存增量。
     *
     * @param code 短码
     * @return 已落盘的累计点击量，没有记录时返回 0
     */
    long clicks(String code);
}
