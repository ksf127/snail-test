package com.company.service.shortlink.domain;

/**
 * 号段来源：负责回答"下一批可用的号码区间是什么"。
 *
 * <p>【解决什么问题】如果每发一个号就访问一次存储（Redis/DB），发号吞吐就被网络往返锁死。
 * 这个接口把"一次交互拿 step 个号"抽象出来，发号器在内存里消费一整段，
 * 把"每号一次往返"降成"每 step 个号一次"。去掉它，发号器和存储实现会耦合在一起。
 *
 * <p>【为什么这样设计】两种实现对照：
 * <ul>
 *   <li>内存：AtomicLong，单进程唯一，单测零依赖，但多实例部署必然重复；</li>
 *   <li>Redis：INCRBY 一次拿一整段，多实例共享同一个 key，天然唯一。</li>
 * </ul>
 * 这也是"号段模式为什么比雪花 ID 更适合短链"的落点：唯一性由存储端的原子自增保证，
 * 不依赖机器时钟，时钟回拨也不会产生重复。
 *
 * <p>【怎么读这段代码】先看 {@link #nextRange(int)} 的契约（返回长度必须等于 step、
 * 相邻两次返回的区间不能重叠），再看两个实现如何用 CAS / Redis 原子命令满足它。
 */
public interface SegmentSource {

    /**
     * 申请下一段号码。
     *
     * <p>实现必须保证不同调用（包括不同实例之间）返回的区间互不重叠，
     * 否则会直接导致短码重复。
     *
     * @param step 期望的号段长度，必须大于 0
     * @return 长度为 step 的半开区间
     * @throws IllegalStateException 号段耗尽或存储端异常时
     */
    Range nextRange(int step);
}
