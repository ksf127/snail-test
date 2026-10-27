package com.company.service.shortlink.domain;

/**
 * 布隆过滤器：用极小的内存把"一定不存在"的请求挡在缓存和数据库之前。
 *
 * <p>只允许"假阳性"（说不存在时一定不存在；说可能存在时可能不存在）。
 * 删除无法从布隆中移除，因此短链删除后要靠缓存 + 数据库兜底，见 ShortlinkService。
 */
public interface BloomFilter {

    boolean mightContain(String key);

    void add(String key);
}
