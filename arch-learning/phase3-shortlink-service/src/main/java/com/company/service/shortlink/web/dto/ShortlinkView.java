package com.company.service.shortlink.web.dto;

import com.company.service.shortlink.domain.Shortlink;

import java.time.Instant;

/**
 * 短链对外视图。
 *
 * <p>【解决什么问题】外部只应该看到"短码 + 短链 + 原链接 + 时间"，
 * 但内部实体里还有发号器的数字 ID。如果直接把实体序列化出去，
 * 就等于把容量与发号策略（当前发到第几号、总共发了多少）暴露给外部。
 * 这一层 DTO 就是内外数据的边界。
 *
 * <p>【为什么这样设计】record 不可变，可以安全地作为响应体在多个线程间传递；
 * {@link #from} 只做字段裁剪，不包含任何业务判断，保证转换逻辑不会成为第二个业务入口。
 *
 * <p>【怎么读这段代码】字段含义见下方参数说明，{@code from} 就是逐字段搬运。
 *
 * @param code        7 位短码
 * @param shortUrl    完整短链（域名 + 短码），由 Service 按配置拼接
 * @param originalUrl 原始长链接
 * @param createdAt   创建时间
 * @param expireAt    过期时间，null 表示永不过期
 */
public record ShortlinkView(String code,
                            String shortUrl,
                            String originalUrl,
                            Instant createdAt,
                            Instant expireAt) {

    /**
     * 实体 → 视图，丢弃内部数字 ID。
     *
     * <p>纯函数，无状态，线程安全。
     *
     * @param link     短链实体，不能为 null
     * @param shortUrl 已拼好的完整短链
     * @return 对外视图
     */
    public static ShortlinkView from(Shortlink link, String shortUrl) {
        // 注意这里不返回 link.id()：数字 ID 是内部发号细节，不对外暴露
        return new ShortlinkView(link.code(), shortUrl, link.originalUrl(), link.createdAt(), link.expireAt());
    }
}
