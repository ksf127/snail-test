package com.company.service.shortlink.domain;

import java.time.Instant;

/**
 * 短链实体。
 *
 * <p>不可变：缓存、队列、跨线程传递时不需要额外防御性拷贝。
 *
 * @param id          号段生成的数字 ID，转 Base62 后就是短码
 * @param code        短码（固定 7 位）
 * @param originalUrl 原始长链接
 * @param createdAt   创建时间
 * @param expireAt    过期时间，null 表示永不过期
 */
public record Shortlink(Long id, String code, String originalUrl, Instant createdAt, Instant expireAt) {

    public boolean isExpired(Instant now) {
        return expireAt != null && !expireAt.isAfter(now);
    }
}
