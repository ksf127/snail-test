package com.company.service.shortlink.web.dto;

import com.company.service.shortlink.domain.Shortlink;

import java.time.Instant;

/**
 * 短链对外视图。
 *
 * <p>不直接返回实体：实体里的数字 id 是内部发号细节，暴露出去等于把容量与发号策略告诉外部。
 */
public record ShortlinkView(String code,
                            String shortUrl,
                            String originalUrl,
                            Instant createdAt,
                            Instant expireAt) {

    public static ShortlinkView from(Shortlink link, String shortUrl) {
        return new ShortlinkView(link.code(), shortUrl, link.originalUrl(), link.createdAt(), link.expireAt());
    }
}
