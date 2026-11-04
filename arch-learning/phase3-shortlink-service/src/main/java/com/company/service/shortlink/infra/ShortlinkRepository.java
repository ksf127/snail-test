package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.Shortlink;

import java.util.List;
import java.util.Optional;

/**
 * 短链存储（数据库层的抽象）。
 *
 * <p>【解决什么问题】它是缓存全部未命中后的最后一道防线，也是短链的唯一真相来源。
 * 创建时写入、删除时移除、缓存回源时按短码查询。去掉它，数据就只存在于易失的缓存里。
 *
 * <p>【为什么这样设计】默认提供内存实现，真实环境换成 MySQL/分库分表只需要替换这个 Bean；
 * 上层不关心"命中缓存之后那一层"到底是数据库还是别的。
 * 接口刻意只按短码查询，不提供按 ID 等其它入口 —— 约束调用方只能走短码，
 * 避免业务层绕过缓存直接按主键查库。
 *
 * <p>【怎么读这段代码】五个方法对应存储的 CRUD 与全量扫描。
 * 实现必须线程安全；{@link #findAll} 与 {@link #count} 只给预热和统计用，允许有轻微延迟。
 */
public interface ShortlinkRepository {

    /**
     * 保存短链；短码已存在时覆盖。
     *
     * @param link 待保存的短链
     */
    void save(Shortlink link);

    /**
     * 按短码查询。
     *
     * @param code 短码
     * @return 命中时返回短链，否则返回空
     */
    Optional<Shortlink> findByCode(String code);

    /**
     * 按短码删除。
     *
     * @param code 短码
     * @return 确实删除了一条时返回 true
     */
    boolean deleteByCode(String code);

    /**
     * 全量查询，供布隆过滤器启动预热使用。
     *
     * @return 当前所有短链
     */
    List<Shortlink> findAll();

    /**
     * @return 当前短链总数
     */
    long count();
}
