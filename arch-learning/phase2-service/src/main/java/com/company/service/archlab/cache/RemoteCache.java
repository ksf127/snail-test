package com.company.service.archlab.cache;

import java.time.Duration;

/**
 * 二级缓存抽象：只存字符串，序列化与空值策略交给上层。
 *
 * <p>【解决什么问题】实验代码如果直接依赖 Redis 客户端，没有 Redis 的环境（本地、单测、CI）
 * 就跑不起来；而且"缓存未命中"和"缓存里存的空值"在多数封装里都会被折叠成 null，
 * 上层没法区分该不该回源。
 *
 * <p>【为什么这样设计】把 L2 收敛成三个动作（读、写、删），值统一用 String：
 * 序列化格式、空值哨兵由 {@link MultiLevelProductCache} 决定，L2 只负责存取。
 * 这样 memory 实现和 redis 实现可以按配置无差别替换（见两个实现类上的
 * {@code @ConditionalOnProperty}），业务代码一行不用改。
 *
 * <p>【怎么读这段代码】先看 {@link #get(String)} 的返回值约定——
 * null 表示未命中，空值哨兵字符串表示"命中了但数据本身为空"；
 * 再看两个实现类如何遵守这个约定。
 */
public interface RemoteCache {

    /**
     * 读取 L2。
     *
     * @param key 已带 namespace 的完整 key
     * @return 未命中返回 {@code null}，命中空值哨兵时返回哨兵字符串
     */
    String get(String key);

    /**
     * 写入 L2。
     *
     * @param key   已带 namespace 的完整 key
     * @param value 已序列化的值，或空值哨兵
     * @param ttl   过期时间；null/零/负数表示不过期
     */
    void put(String key, String value, Duration ttl);

    /**
     * 删除 L2 中的 key；key 不存在时不应抛异常（删除天然幂等）。
     *
     * @param key 已带 namespace 的完整 key
     */
    void evict(String key);
}
