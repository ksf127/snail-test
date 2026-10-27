package com.company.service.shortlink.infra;

/**
 * 点击量存储。
 *
 * <p>跳转主路径只往内存计数，定时批量落库：
 * 100 万 QPS 下不可能每次跳转都写一次 Redis，那等于把读请求改造成写请求。
 */
public interface ClickStore {

    void increment(String code, long delta);

    long clicks(String code);
}
