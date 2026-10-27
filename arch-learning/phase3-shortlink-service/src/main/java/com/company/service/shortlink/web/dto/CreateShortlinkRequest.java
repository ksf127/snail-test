package com.company.service.shortlink.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建短链入参。
 *
 * @param url      原始长链接，必须是 http/https 且带主机名
 * @param expireAt 过期时间，null 表示永不过期
 */
public record CreateShortlinkRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 2048, message = "长度不能超过 2048")
        String url,
        Instant expireAt) {
}
