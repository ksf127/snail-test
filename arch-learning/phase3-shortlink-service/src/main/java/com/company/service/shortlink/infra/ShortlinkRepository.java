package com.company.service.shortlink.infra;

import com.company.service.shortlink.domain.Shortlink;

import java.util.List;
import java.util.Optional;

/**
 * 短链存储。
 *
 * <p>默认内存实现，真实环境换成 MySQL/分库分表只需要替换这个 Bean；
 * 上层不关心"命中缓存之后那一层"到底是数据库还是别的。
 */
public interface ShortlinkRepository {

    void save(Shortlink link);

    Optional<Shortlink> findByCode(String code);

    boolean deleteByCode(String code);

    List<Shortlink> findAll();

    long count();
}
