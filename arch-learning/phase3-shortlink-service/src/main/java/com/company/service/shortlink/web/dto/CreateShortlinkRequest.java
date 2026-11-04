package com.company.service.shortlink.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 创建短链入参。
 *
 * <p>【解决什么问题】把 HTTP 请求体映射成一个不可变对象，让 Controller 与 Service
 * 不直接接触 JSON。它也是校验的第一道关口：Bean Validation 在进入业务代码之前
 * 就挡掉空值和超长 URL，避免把明显非法的数据带进发号与落库流程。
 *
 * <p>【为什么这样设计】用 record 而不是可变 POJO：入参在请求处理期间不应被修改，
 * 不可变对象天然线程安全，也便于日志里直接打印。
 * 校验只做"格式层面"的（非空、长度），协议与主机名校验放在 Service，
 * 因为那需要解析 URI，属于业务规则而不是字段约束。
 *
 * <p>【怎么读这段代码】两个字段对应 JSON 里的 {@code url} 与 {@code expireAt}，
 * 注解是长度/非空约束，具体含义见下方参数说明。
 *
 * @param url      原始长链接，必须是 http/https 且带主机名；不能为空，最长 2048
 * @param expireAt 过期时间（ISO-8601），null 表示永不过期；必须晚于当前时间
 */
public record CreateShortlinkRequest(
        @NotBlank(message = "不能为空")
        @Size(max = 2048, message = "长度不能超过 2048")
        String url,
        Instant expireAt) {
}
