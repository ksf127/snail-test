package com.company.service.archlab.cache;

import java.math.BigDecimal;

/**
 * 缓存实验里的商品视图：只带读多写少、适合缓存的字段。
 *
 * <p>【解决什么问题】缓存里到底存什么对象。如果把数据库实体直接塞进缓存，
 * 实体一加字段、一改类型，线上历史缓存就无法反序列化，只能整体清缓存；
 * 而且实体往往带着库存、成本价这类不该对外暴露或更新频繁的字段。
 *
 * <p>【为什么这样设计】这里单独定义一个 {@code ProductView} record：
 * 它是"缓存协议"，只包含读场景真正需要的字段，与数据库表结构解耦，可以独立演进。
 * record 天生不可变，作为缓存值可以安全地在多个线程间共享，不会被谁偷偷改掉。
 *
 * <p>【怎么读这段代码】把 {@code id} 当作缓存 key 的业务部分（真正的 key 还带 namespace），
 * 其余字段就是整段 JSON 存进 L2 的内容；字段类型变化会直接导致旧缓存反序列化失败，
 * 这也是 {@link MultiLevelProductCache} 里要捕获 {@code JsonProcessingException} 的原因。
 *
 * <p>真实项目里不要直接缓存数据库实体：字段一变，历史缓存就无法反序列化，
 * 所以缓存对象要独立演进。
 */
public record ProductView(long id, String name, BigDecimal price, int stock) {
}
