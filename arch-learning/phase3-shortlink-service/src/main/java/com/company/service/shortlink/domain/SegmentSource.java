package com.company.service.shortlink.domain;

/**
 * 号段来源。
 *
 * <p>两种实现对照：
 * <ul>
 *   <li>内存：AtomicLong，单进程唯一，单测零依赖</li>
 *   <li>Redis：INCRBY 一次拿一整段，多实例下天然唯一</li>
 * </ul>
 * 共同点：一次交互拿 step 个号，把"每号一次网络往返"降成"每 step 个号一次"。
 */
public interface SegmentSource {

    /**
     * 申请下一段。
     */
    Range nextRange(int step);
}
