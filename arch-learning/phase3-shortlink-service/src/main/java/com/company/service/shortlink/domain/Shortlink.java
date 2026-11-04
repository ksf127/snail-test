package com.company.service.shortlink.domain;

import java.time.Instant;

/**
 * 短链实体。
 *
 * <p>【解决什么问题】它是整条链路唯一的数据载体：创建时落库、缓存时序列化、
 * 跳转时取出 {@code originalUrl} 做 302。所有层（存储、缓存、Web）都围绕这个对象流转。
 *
 * <p>【为什么这样设计】用 record 做成不可变对象：缓存、异步队列、跨线程传递时
 * 不需要额外防御性拷贝，也不存在"某个线程改了字段导致其他请求拿到脏数据"的问题。
 * 过期判断做成方法（{@link #isExpired(Instant)}）而不是在构造时固化，
 * 是因为同一份缓存条目可能被读到多次，过期必须在"读的那一刻"判断。
 *
 * <p>【怎么读这段代码】字段含义见下方参数说明；唯一的方法 isExpired 只有一行，
 * 注意 {@code expireAt == null} 表示永不过期，而不是"已过期"。
 *
 * @param id          号段生成的数字 ID，转 Base62 后就是短码
 * @param code        短码（固定 7 位）
 * @param originalUrl 原始长链接
 * @param createdAt   创建时间
 * @param expireAt    过期时间，null 表示永不过期
 */
public record Shortlink(Long id, String code, String originalUrl, Instant createdAt, Instant expireAt) {

    /**
     * 判断在给定时刻是否已过期。
     *
     * <p>用 {@code !isAfter} 而不是 {@code isBefore}：刚好等于过期时刻也算过期。
     *
     * @param now 用于比较的当前时间，由调用方传入以便测试可控
     * @return 已过期返回 true；{@code expireAt} 为 null 时恒为 false
     */
    public boolean isExpired(Instant now) {
        return expireAt != null && !expireAt.isAfter(now);
    }
}
