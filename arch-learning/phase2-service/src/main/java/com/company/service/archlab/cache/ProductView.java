package com.company.service.archlab.cache;

import java.math.BigDecimal;

/**
 * 缓存实验里的商品视图：只带读多写少、适合缓存的字段。
 *
 * <p>真实项目里不要直接缓存数据库实体：字段一变，历史缓存就无法反序列化，
 * 所以缓存对象要独立演进。
 */
public record ProductView(long id, String name, BigDecimal price, int stock) {
}
